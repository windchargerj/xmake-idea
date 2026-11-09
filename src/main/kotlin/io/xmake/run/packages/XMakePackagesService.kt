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
import io.xmake.utils.cli.XMakeCommandLine
import io.xmake.run.command.XMakeCommand
import io.xmake.run.command.XMakeCommandException
import io.xmake.run.command.XMakeCommandFactory
import io.xmake.run.command.XMakeConsoleOptions
import io.xmake.run.command.xmakeCommands
import io.xmake.run.command.xmakeExecutor
import io.xmake.run.target.SELECT_BUILD_PROFILE_FIRST
import io.xmake.run.target.activeOrSingleXMakeBuildProfile
import io.xmake.utils.cli.XMakePackageSpec
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
private sealed class Pipeline {
    data object Idle : Pipeline()
    data class Running(val mutation: Boolean, val doing: String?) : Pipeline()
}

/**
 * The pipeline's exclusive states. A query for "is the lane busy with a mutation and
 * what is it narrating" has one answer per state - the three flags the older fields
 * carried could describe combinations that cannot happen, and a reader could catch
 * one mid-teardown. [Idle] and [Running] are the only values; [Pipeline.Running.mutation]
 * decides whether the operation may be abandoned, [Pipeline.Running.doing] is what the
 * status line narrates (null: the caller narrates its own loading row).
 */
@Service(Service.Level.PROJECT)
internal class XMakePackagesService(private val project: Project) {

    /** The last snapshot, or null before the first refresh landed. */
    var snapshot: XMakePackagesSnapshot? = null
        private set

    /** The pipeline's exclusive state - one immutable value, assigned in one reference
     *  write, so no reader ever sees a torn combination (busy without its mutation flag
     *  or its narration). The quiet-lane queries never appear here. */
    @Volatile
    private var pipeline: Pipeline = Pipeline.Idle

    /** The last operation's failure, for the view's summary line; null after a success. */
    var lastError: String? = null
        private set

    /** True while an operation occupies the pipeline; the view disables its actions then. */
    val busy: Boolean
        get() = pipeline is Pipeline.Running

    /**
     * What the pipeline is doing while [busy]: null names an info query - the details
     * pane's own "Loading" (the quiet-lane probes never surface here) - anything else is
     * the operation the grayed actions wait for, so a long install reads as a reason,
     * not as a stuck panel.
     */
    val currentOperation: String?
        get() = (pipeline as? Pipeline.Running)?.doing

    /** True when the operation holding the pipeline may not be abandoned: an install or
     *  uninstall must run out, while a read (list, search, info) is safe to cancel. */
    val busyWithMutation: Boolean
        get() = (pipeline as? Pipeline.Running)?.mutation == true

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
     *  behind a read preempts it; behind a mutation - or before the catalog ever landed
     *  (cancelling the first refresh would leave the declared list empty for good) - it
     *  queues as [pendingSearch]. The info cache clears with the mode: a name answered
     *  under search queries afresh as declared, since the two contexts resolve
     *  differently. */
    fun search(term: String) {
        if (busy && (busyWithMutation || snapshot == null)) {
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
        // The closure is what a selection still needs: the batched prefetch already
        // answered the row's facts, but only a single-package ask can attribute the
        // closure to the package that needs it.
        if (current.closures.containsKey(packageName)) return
        val asked = XMakePackageSpec.parse(packageName)
        // The query rides the quiet lane, outside the claim machinery: it flips no
        // busy flag and narrates nothing, so selections answer live while a build or
        // an install holds the exclusive lane. Both reads degrade honestly on a store
        // mid-write, which is the price of not freezing the browsing.
        project.xmakeExecutor.submitQuery {
            val base = snapshot
            try {
                // ONE spawn: the bundled query runs xmake's own info and scan actions
                // for this package - the answer carries no flag-version sensitivity,
                // because the script calls the modules the CLI itself calls. The text
                // parsers read the whole output; the store scan of the ask rides in
                // the same answer.
                val query = runQuery { packageFactory().createPackagesQuery(asked) }
                val answer = query.getOrNull()
                val info = answer?.let { text ->
                    runCancellable { XMakePackageParsers.parsePackageInfoText(text, packageName) }.getOrNull()
                }
                val closure = answer?.let { text ->
                    runCancellable { XMakePackageParsers.parsePackageClosureText(text) }.getOrNull()
                }.orEmpty()
                val bare = asked.name
                val versions = if (answer == null || bare.isEmpty() || packageName.contains("::")) {
                    emptyList()
                } else {
                    XMakePackageParsers.parseInstalledScan(answer)
                        .filter { it.require == bare.lowercase() }
                        .mapNotNull { it.version }
                }
                // The store composes the info: the asked version sitting in the scanned
                // inventory is what "installed" means here - one truth, read from the
                // same spawn. A landed answer - a failed parse included - is the record,
                // whatever it said.
                val composed = info?.copy(installed = versions.contains(info.version))
                val failure = when {
                    composed != null -> null
                    answer == null -> query.exceptionOrNull()?.describeFailure()
                        ?: "the query answered nothing"
                    else -> "the query output carried no info for '$packageName'"
                }
                base?.copy(
                    infos = base.infos + (packageName to composed),
                    closures = base.closures + (packageName to closure),
                    installedVersions = base.installedVersions + (bare.lowercase() to versions),
                    infoFailures = if (composed == null) {
                        base.infoFailures + (packageName to (failure ?: "the info ask failed"))
                    } else {
                        base.infoFailures - packageName
                    },
                )
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                Log.warn("XMake package info query failed", error)
                snapshot?.let {
                    finish(
                        it.copy(infoFailures = it.infoFailures + (packageName to (error.message ?: error.toString()))),
                        null,
                    )
                }
            }
        }
    }

    /** One captured query; a failure keeps its own sentence. */
    private suspend fun runQuery(build: () -> XMakeCommand) = runCancellable {
        project.xmakeExecutor.captureStandardOutput(build())
    }

    /** The failure's own sentence; anything but a command failure keeps its message. */
    private fun Throwable.describeFailure(): String =
        (this as? XMakeCommandException)?.summary ?: message ?: toString()

    /**
     * Whether the active profile's toolkit answers [ask] - the panels question in the
     * DSL's own grammar, and the answer is whether the line renders. False when no
     * profile stands, so a gated verb stays off until a toolkit exists to answer it.
     */
    fun supports(ask: XMakeCommandLine.() -> Unit): Boolean = try {
        packageFactory().supports(ask)
    } catch (_: Exception) {
        false
    }

    /**
     * Scans what is actually installed through `xmake require --scan` and answers the
     * inventory as the view's rows. Local cost: the scan reads the installed package
     * store on the toolkit host, no repository round trip.
     */
    fun scanInstalled() {
        claim("scanning installed packages") { factory ->
            val base = snapshot
            val inventory = scanInventory(factory)
            base?.copy(
                installed = inventory,
                installedVersions = inventory.groupByVersion(),
            )
        }
    }

    /** One full-store scan: the inventory rows, or empty when the scan died. */
    private suspend fun scanInventory(factory: XMakeCommandFactory): List<XMakePackageInfo> =
        runCancellable {
            XMakePackageParsers.parseInstalledScan(
                project.xmakeExecutor.captureStandardOutput(factory.createRequireScan()),
            )
        }.getOrNull().orEmpty()

    /** The inventory grouped per package name - the version cache's shape, so the full
     *  scan feeds the same cache the targeted scan does. */
    private fun List<XMakePackageInfo>.groupByVersion(): Map<String, List<String>> =
        groupBy({ it.require }, { it.version ?: "" })
            .mapValues { (_, versions) -> versions.filter(String::isNotEmpty) }

    /** Leaves the installed view; the inventory stays in the snapshot, so re-entering
     *  the view costs nothing until a refresh drops it. */
    fun exitInstalled() {
        val current = snapshot ?: return
        if (current.installed == null) return
        snapshot = current.copy(installed = null)
        project.messageBus.syncPublisher(TOPIC).packagesChanged(snapshot)
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
        pipeline = Pipeline.Running(mutation, doing)
        val token = ClaimToken()
        claimToken.set(token)
        return token
    }

    /** Releases the claim [token] stands for; a claim already taken over or released
     *  stays untouched. */
    private fun releaseClaim(token: ClaimToken) {
        if (!claimToken.compareAndSet(token, null)) return
        pipeline = Pipeline.Idle
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
        // The graph is a nicety and the flag is not universal: below the capability the
        // ask is skipped, not fired and errored - the tree degrades to childless rows.
        val depgraph = if (factory.supports({ require { depgraph = true } })) {
            runCancellable {
                XMakePackageParsers.parseDepgraph(
                    project.xmakeExecutor.captureStandardOutput(factory.createRequireDepgraph()),
                )
            }.getOrNull()
        } else {
            null
        }
        // The installed inventory, when the view is on, is re-scanned rather than
        // carried: a mutation just changed the store, and the view's whole job is to
        // say what the store holds now. Its names refill the version cache - the one
        // installed truth, same as the toggle and the query feed it.
        val carried = snapshot?.installed
        val inventory = if (carried != null) {
            scanInventory(factory)
        } else {
            null
        }
        return XMakePackagesSnapshot(
            packages,
            depgraph,
            searchResults = snapshot?.searchResults,
            installed = inventory,
            installedVersions = inventory?.groupByVersion() ?: snapshot?.installedVersions ?: emptyMap(),
        )
    }

    private fun packageFactory(): XMakeCommandFactory {
        val profile = project.activeOrSingleXMakeBuildProfile
            ?: throw IllegalStateException(SELECT_BUILD_PROFILE_FIRST)
        return project.xmakeCommands(profile)
    }

    private fun finish(newSnapshot: XMakePackagesSnapshot?, error: String?) {
        pipeline = Pipeline.Idle
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
