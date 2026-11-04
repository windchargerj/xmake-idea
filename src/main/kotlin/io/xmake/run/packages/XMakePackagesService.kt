/*!A Xmake integration in IntelliJ IDEA/Clion
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 * Copyright (C) 2015-present, Xmake Open Source Community.
 */
package io.xmake.run.packages

import com.intellij.execution.ui.ConsoleViewContentType
import com.intellij.openapi.application.invokeLater
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.text.StringUtil
import com.intellij.util.messages.Topic
import io.xmake.project.console.XMakeConsoleManager
import io.xmake.run.command.XMakeCommand
import io.xmake.run.command.XMakeCommandFactory
import io.xmake.run.command.XMakeConsoleOptions
import io.xmake.run.command.xmakeCommands
import io.xmake.run.command.xmakeExecutor
import io.xmake.run.target.SELECT_BUILD_PROFILE_FIRST
import io.xmake.run.target.activeOrSingleXMakeBuildProfile
import io.xmake.utils.notify
import io.xmake.utils.runCancellable
import kotlinx.coroutines.CancellationException

/**
 * The project's xrepo package state: the declared packages, the dependency graph, the
 * per-package info cache, and the search results, published on the message bus so the
 * packages view stays in step.
 *
 * The service owns the one snapshot; xmake remains the source of truth - every figure
 * comes from one `xmake require` invocation, nothing is computed plugin-side. All queries
 * run through [Project.xmakeExecutor] on its single lane, so package commands serialize
 * against builds and configuration by construction. Failures degrade: the last good
 * snapshot stays, [lastError] carries the message, and the view keeps working.
 */
@Service(Service.Level.PROJECT)
internal class XMakePackagesService(private val project: Project) {

    /** The last snapshot, or null before the first refresh landed. */
    var snapshot: XMakePackagesSnapshot? = null
        private set

    /** The last operation's failure, for the view's summary line; null after a success. */
    var lastError: String? = null
        private set

    /** True while an operation occupies the pipeline; the view disables its actions then. */
    @Volatile
    var busy: Boolean = false
        private set

    /**
     * What the pipeline is doing while [busy]: null names an info fetch - the details
     * pane's own "Loading" - anything else is the operation the grayed actions wait for,
     * so a long install reads as a reason, not as a stuck panel.
     */
    @Volatile
    var currentOperation: String? = null
        private set

    /**
     * Reloads the declared packages and the dependency graph. The graph is a nicety: a
     * failed graph only leaves the rows childless, while a failed listing is the failure
     * the view reports. Search mode is not an exit here - the hits ride across a refresh
     * as the tree's filtered view (an install started from a hit lands with the hits
     * still shown); only Esc or an emptied search field leaves the view, via [exitSearch].
     */
    fun refresh() {
        if (busy) return
        runQuery("refreshing the package list") { factory -> loadSnapshot(factory) }
    }

    /** Searches the configured repositories; the hits - and the `note:`/`error:` lines
     *  xmake printed around them - replace the tree's rows until [exitSearch]. */
    fun search(term: String) {
        if (busy) return
        runQuery("searching the repositories for '$term'") { factory ->
            val results = XMakePackageParsers.parseSearchResults(
                project.xmakeExecutor.captureStandardOutput(factory.createRequireSearch(term)),
            )
            (snapshot ?: XMakePackagesSnapshot(emptyList(), null)).copy(searchResults = results)
        }
    }

    /**
     * Leaves search mode without re-querying: the declared packages come back from the
     * snapshot the search rode on, so Esc, or an empty search box plus Enter, costs no
     * lane time. No-op while an operation holds the pipeline or search mode is not on.
     */
    fun exitSearch() {
        if (busy) return
        val current = snapshot ?: return
        if (current.searchResults == null) return
        snapshot = current.copy(searchResults = null)
        project.messageBus.syncPublisher(TOPIC).packagesChanged(snapshot)
    }

    /**
     * Fetches one package's `--info` in the background - the installed marker and the
     * details pane's data. Always re-queries: the search hits and the declared list can
     * carry the same name with different resolutions (a project pins one version, the
     * repository offers another), and a cached answer from one context would misdescribe
     * the row the user clicked in the other.
     */
    fun fetchInfo(packageName: String) {
        if (busy) return
        val current = snapshot ?: return
        busy = true
        currentOperation = null
        project.xmakeExecutor.submit {
            try {
                val factory = packageFactory()
                val info = runCancellable {
                    XMakePackageParsers.parsePackageInfo(
                        project.xmakeExecutor.captureStandardOutput(factory.createRequireInfo(packageName)),
                    )
                }.getOrNull()
                finish(current.copy(infos = current.infos + (packageName to info)), null)
            } catch (cancellation: CancellationException) {
                busy = false
                currentOperation = null
                throw cancellation
            } catch (error: Exception) {
                Log.warn("XMake package info query failed for '$packageName'", error)
                finish(current, error.message ?: error.toString())
            }
        }
    }

    /**
     * Builds one package command on the executor lane and hands it to [onReady] on the EDT.
     * The factory construction resolves the project directory, which may touch the local
     * disk, so it never runs on the EDT - yet the confirmation dialog must show the exact
     * command line before anything runs, hence the two hops. A failure is a notification.
     */
    fun prepareCommand(prepare: XMakeCommandFactory.() -> XMakeCommand, onReady: (XMakeCommand) -> Unit) {
        if (busy) return
        busy = true
        currentOperation = "preparing the package command"
        project.xmakeExecutor.submit {
            var command: XMakeCommand? = null
            var failure: String? = null
            try {
                command = packageFactory().prepare()
            } catch (cancellation: CancellationException) {
                busy = false
                currentOperation = null
                throw cancellation
            } catch (error: Exception) {
                Log.warn("XMake package command preparation failed", error)
                failure = error.message ?: error.toString()
            }
            invokeLater {
                if (project.isDisposed) return@invokeLater
                busy = false
                currentOperation = null
                failure?.let { project.notify("XMake packages", StringUtil.escapeXmlEntities(it)) }
                command?.let(onReady)
            }
        }
    }

    /**
     * Runs one confirmed package mutation - install, upgrade, uninstall - with its output
     * streaming into the XMake console, then refreshes so the view shows what xmake did,
     * not what the exit code suggests. The command comes prebuilt from the confirmation
     * flow, with `-y` already baked in by the factory.
     */
    fun runPackageCommand(command: XMakeCommand, description: String) {
        if (busy) return
        busy = true
        currentOperation = description
        project.xmakeExecutor.submit {
            var failed: Exception? = null
            try {
                val console = XMakeConsoleManager.getInstance(project).awaitReady()
                console.clear()
                console.print("xmake: $description\n", ConsoleViewContentType.SYSTEM_OUTPUT)
                project.xmakeExecutor.execute(console, command, XMakeConsoleOptions(showConsole = true))
            } catch (cancellation: CancellationException) {
                busy = false
                currentOperation = null
                throw cancellation
            } catch (error: Exception) {
                failed = error
                Log.warn("XMake package command failed: $description", error)
            }
            // TODO(v2): snapshot xmake-requires.lock before the command and diff it after;
            // a change raises a balloon with a Show diff action (docs/roadmap-designs/xrepo-packages.md).
            invokeLater {
                if (project.isDisposed) return@invokeLater
                failed?.let { error ->
                    // The balloon names the exact command line, not the confirmation-flow
                    // sentence: "$description" is the long "Installing 'x' - xmake fetches,
                    // builds, and installs it." wording, which reads garbled after "failed".
                    // The summary line and the console banner keep the sentence - it fits
                    // those two.
                    project.notify(
                        "XMake packages",
                        "xmake ${StringUtil.escapeXmlEntities(command.commandLine.commandLineString)} failed: " +
                            StringUtil.escapeXmlEntities(error.message ?: "an unknown error"),
                    )
                }
                runQuery("refreshing the package list") { factory -> loadSnapshot(factory) }
            }
        }
    }

    /** One read-only pipeline run: profile in, snapshot out, failure into [lastError].
     *  [doing] names the run for the view, which has no other way to tell a grayed
     *  refresh from a grayed install. */
    private fun runQuery(
        doing: String,
        operation: suspend (factory: XMakeCommandFactory) -> XMakePackagesSnapshot,
    ) {
        busy = true
        currentOperation = doing
        project.xmakeExecutor.submit {
            try {
                finish(operation(packageFactory()), null)
            } catch (cancellation: CancellationException) {
                busy = false
                currentOperation = null
                throw cancellation
            } catch (error: Exception) {
                Log.warn("XMake packages query failed", error)
                finish(snapshot, error.message ?: error.toString())
            }
        }
    }

    /** The fresh state one refresh answers: the declared packages, plus the dependency graph
     *  when xmake produced one. The search results ride over unchanged - the hits stay the
     *  tree's filtered view across a refresh; nothing but [exitSearch] clears them. */
    private suspend fun loadSnapshot(factory: XMakeCommandFactory): XMakePackagesSnapshot {
        val packages = XMakePackageParsers.parseRequireList(
            project.xmakeExecutor.captureStandardOutput(factory.createRequireList()),
        )
        val depgraph = runCancellable {
            XMakePackageParsers.parseDepgraph(
                project.xmakeExecutor.captureStandardOutput(factory.createRequireDepgraph()),
            )
        }.getOrNull()
        return XMakePackagesSnapshot(packages, depgraph, searchResults = snapshot?.searchResults)
    }

    private fun packageFactory(): XMakeCommandFactory {
        val profile = project.activeOrSingleXMakeBuildProfile
            ?: throw IllegalStateException(SELECT_BUILD_PROFILE_FIRST)
        return project.xmakeCommands(profile)
    }

    private fun finish(newSnapshot: XMakePackagesSnapshot?, error: String?) {
        busy = false
        currentOperation = null
        snapshot = newSnapshot
        lastError = error
        project.messageBus.syncPublisher(TOPIC).packagesChanged(snapshot)
    }

    companion object {
        val TOPIC: Topic<Listener> = Topic("XMake packages state", Listener::class.java)

        fun getInstance(project: Project): XMakePackagesService = project.service()
    }

    fun interface Listener {

        fun packagesChanged(snapshot: XMakePackagesSnapshot?)
    }
}

private val Log = logger<XMakePackagesService>()
