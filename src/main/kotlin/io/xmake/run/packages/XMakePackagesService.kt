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
import kotlinx.coroutines.Deferred
import java.util.concurrent.atomic.AtomicReference

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

    /** True when the operation holding the pipeline may not be abandoned: an install or
     *  uninstall must run out, while a read (list, search, info) is safe to cancel. */
    @Volatile
    var busyWithMutation: Boolean = false
        private set

    /** The one command this service has on the executor's lane, so a newer request can
     *  abandon a read ([inFlight] cancelled) and queue behind a mutation. */
    @Volatile
    private var inFlight: Deferred<*>? = null

    /** A search typed while a mutation holds the pipeline; it is searched when the lane
     *  frees, so the user's second Enter waits for the install rather than dying. */
    @Volatile
    var pendingSearch: String? = null
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
        claim("refreshing the package list") { factory -> loadSnapshot(factory) }
    }

    /** Searches the configured repositories; the hits - and the `note:`/`error:` lines
     *  xmake printed around them - replace the tree's rows until [exitSearch]. A search
     *  behind a read preempts it; behind a mutation it queues as [pendingSearch]. The
     *  info cache clears with the mode: a name answered under search queries afresh as
     *  declared, since the two contexts resolve differently. */
    fun search(term: String) {
        if (busy && busyWithMutation) {
            pendingSearch = term
            return
        }
        inFlight?.takeIf { !busyWithMutation }?.cancel()
        claim("searching the repositories for '$term'") { factory ->
            val results = XMakePackageParsers.parseSearchResults(
                project.xmakeExecutor.captureStandardOutput(factory.createRequireSearch(term)),
            )
            (snapshot ?: XMakePackagesSnapshot(emptyList(), null))
                .copy(searchResults = results, infos = emptyMap(), closures = emptyMap())
        }
    }

    /**
     * Leaves search mode without re-querying: the declared packages come back from the
     * snapshot the search rode on, so Esc, or an empty search box plus Enter, costs no
     * lane time. A read still in flight is abandoned, a queued search dropped; a
     * mutation keeps running and its trailing refresh keeps the exit (the snapshot it
     * loads carries the cleared search results). The info cache clears with the mode,
     * like [search]'s. No-op when search mode is not on.
     */
    fun exitSearch() {
        pendingSearch = null
        inFlight?.takeIf { !busyWithMutation }?.cancel()
        val current = snapshot ?: return
        if (current.searchResults == null) return
        snapshot = current.copy(searchResults = null, infos = emptyMap(), closures = emptyMap())
        project.messageBus.syncPublisher(TOPIC).packagesChanged(snapshot)
    }

    /**
     * Fetches one package's `--info` in the background - the installed marker and the
     * details pane's data. One query per package per context: the landed answer, a
     * failed parse included, is the info cache's record, so the view's publishes - each
     * of which asks for the selected row's info again - stop at it instead of spawning
     * the query forever. A mode flip clears the cache ([search], [exitSearch]), which
     * is what keeps the two contexts' resolutions apart.
     */
    fun fetchInfo(packageName: String) {
        val current = snapshot ?: return
        if (busy) return
        if (current.infos.containsKey(packageName)) return
        claim(null) { factory ->
            val base = snapshot
            val output = runCancellable {
                project.xmakeExecutor.captureStandardOutput(factory.createRequireInfo(packageName))
            }.getOrNull()
            // One query per package per context: a landed answer - a failed parse
            // included - is the record, whatever it said. The closure rides with the
            // asked package's own answer; it is what the query handed back.
            var info = output?.let { runCancellable { XMakePackageParsers.parsePackageInfo(it, packageName) }.getOrNull() }
            var closure = output?.let { runCancellable { XMakePackageParsers.parsePackageClosure(it) }.getOrNull() }.orEmpty()
            // The JSON flag is newer than some toolkits: a 2.8.7 answers its usage text
            // for --format=json. The plain --info form predates it and every toolkit
            // reads it - the fallback fetch, same lane pass, closure included: the text
            // answer lists the whole dependency closure as its blocks.
            if (info == null) {
                val plain = runCancellable {
                    project.xmakeExecutor.captureStandardOutput(factory.createRequireInfoText(packageName))
                }.getOrNull()
                plain?.let { text ->
                    runCancellable { XMakePackageParsers.parsePackageInfoText(text, packageName) }.getOrNull()
                        ?.let { textInfo ->
                            info = textInfo
                            closure = runCancellable {
                                XMakePackageParsers.parsePackageClosureText(text)
                            }.getOrNull().orEmpty()
                        }
                }
            }
            base?.copy(infos = base.infos + (packageName to info), closures = base.closures + (packageName to closure))
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
        val token = holdClaim(mutation = true, doing = "preparing the package command")
        val deferred = project.xmakeExecutor.submit {
            var command: XMakeCommand? = null
            var failure: String? = null
            try {
                command = packageFactory().prepare()
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                Log.warn("XMake package command preparation failed", error)
                failure = error.message ?: error.toString()
            }
            invokeLater {
                if (project.isDisposed) return@invokeLater
                releaseClaim(token)
                failure?.let { project.notify("XMake packages", StringUtil.escapeXmlEntities(it)) }
                command?.let(onReady)
            }
        }
        inFlight = deferred
        deferred.invokeOnCompletion { releaseClaim(token) }
    }

    /**
     * Runs one confirmed package mutation - install, upgrade, uninstall - with its output
     * streaming into the XMake console, then refreshes so the view shows what xmake did,
     * not what the exit code suggests. The command comes prebuilt from the confirmation
     * flow, with `-y` already baked in by the factory.
     */
    fun runPackageCommand(command: XMakeCommand, description: String) {
        if (busy) return
        val token = holdClaim(mutation = true, doing = description)
        val deferred = project.xmakeExecutor.submit {
            var failed: Exception? = null
            try {
                val console = XMakeConsoleManager.getInstance(project).awaitReady()
                console.clear()
                console.print("xmake: $description\n", ConsoleViewContentType.SYSTEM_OUTPUT)
                project.xmakeExecutor.execute(console, command, XMakeConsoleOptions(showConsole = true))
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                failed = error
                Log.warn("XMake package command failed: $description", error)
            }
            // TODO(v2): snapshot xmake-requires.lock before the command and diff it after;
            // a change raises a balloon with a Show diff action (docs/roadmap-designs/xrepo-packages.md).
            invokeLater {
                if (project.isDisposed) return@invokeLater
                // Released before the refresh is asked for, so the refresh's busy gate
                // opens on this EDT pass rather than racing the completion handler.
                releaseClaim(token)
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
                refresh()
            }
        }
        inFlight = deferred
        deferred.invokeOnCompletion { releaseClaim(token) }
    }

    /**
     * Claims the pipeline for one read - list, search, info - and records its command,
     * so a newer request can abandon it. The read's snapshot is published by [finish];
     * it answers null when there is nothing to publish. The claim releases when the
     * command's task completes - through [finish], a failure, or a cancellation that
     * stops the task before its first line.
     */
    private fun claim(
        doing: String?,
        operation: suspend (factory: XMakeCommandFactory) -> XMakePackagesSnapshot?,
    ) {
        val token = holdClaim(mutation = false, doing = doing)
        val deferred = project.xmakeExecutor.submit {
            try {
                operation(packageFactory())?.let { finish(it, null) }
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                Log.warn("XMake packages query failed", error)
                finish(snapshot, error.message ?: error.toString())
            }
        }
        inFlight = deferred
        deferred.invokeOnCompletion {
            releaseClaim(token)
            invokeLater { drainPendingSearch() }
        }
    }

    /** Marks the pipeline held by the caller's claim token. Set before the command is
     *  submitted, so no completion can outrun the claim it belongs to. */
    private fun holdClaim(mutation: Boolean, doing: String?): ClaimToken {
        busy = true
        busyWithMutation = mutation
        currentOperation = doing
        val token = ClaimToken()
        claimToken.set(token)
        return token
    }

    /** Releases the claim [token] stands for; a claim already taken over or released
     *  stays untouched. */
    private fun releaseClaim(token: ClaimToken) {
        if (!claimToken.compareAndSet(token, null)) return
        busy = false
        busyWithMutation = false
        currentOperation = null
    }

    /** The pipeline's current claim, or null when the lane is free for a new request. */
    private val claimToken = AtomicReference<ClaimToken?>(null)

    private class ClaimToken

    /** Runs the search queued behind a mutation, once the lane has freed. */
    private fun drainPendingSearch() {
        val next = pendingSearch ?: return
        pendingSearch = null
        search(next)
    }

    /** The fresh state one refresh answers: the declared packages, plus the dependency graph
     *  when xmake produced one. The search results ride over unchanged - the hits stay the
     *  tree's filtered view across a refresh; nothing but [exitSearch] clears them. The info
     *  and closure caches drop: a refresh is a new context, and a cached empty answer - from
     *  a query that failed while the toolchain was mid-upgrade, say - must not outlive it. */
    private suspend fun loadSnapshot(factory: XMakeCommandFactory): XMakePackagesSnapshot {
        val packages = XMakePackageParsers.parseRequireList(
            project.xmakeExecutor.captureStandardOutput(factory.createRequireList()),
        )
        val depgraph = runCancellable {
            XMakePackageParsers.parseDepgraph(
                project.xmakeExecutor.captureStandardOutput(factory.createRequireDepgraph()),
            )
        }.getOrNull()
        return XMakePackagesSnapshot(
            packages,
            depgraph,
            searchResults = snapshot?.searchResults,
        )
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
