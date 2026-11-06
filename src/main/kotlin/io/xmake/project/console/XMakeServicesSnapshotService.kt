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
 * Copyright (C) 2015-present, XMake Open Source Community.
 */
package io.xmake.project.console

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.EDT
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.serviceOrNull
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.text.StringUtil
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ex.ToolWindowManagerListener
import com.intellij.util.messages.Topic
import io.xmake.run.command.XMakeCommandFactory
import io.xmake.run.command.xmakeExecutor
import io.xmake.run.command.xmakeCommands
import io.xmake.run.target.SELECT_BUILD_PROFILE_FIRST
import io.xmake.run.target.activeOrSingleXMakeBuildProfile
import io.xmake.utils.cli.XMakeServiceKind
import io.xmake.utils.cli.XMakeServiceVerb
import io.xmake.utils.notify
import io.xmake.utils.publish
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The one owner of the last services snapshot and of the probe that produces it. Both
 * the platform Services tree (via [XMakeServicesContributor]) and the XMake tool
 * window's tab read from here, so the two surfaces can never drift apart.
 *
 * The probe is deliberately not resident: it runs when a surface asks for a refresh -
 * the Services tool window being shown, the tab being activated, the Refresh action,
 * or right after a state-changing command - and never while every surface is hidden
 * (docs/roadmap-designs/build-services.md section 3). The probe itself is the same
 * one command as before (`probeservices.lua` through the project's executor lane),
 * and every state change is a real xmake command on the same lane followed by a
 * re-probe, so the surfaces show what xmake persisted, not what an exit code
 * suggests.
 *
 * This is also the thread-inversion dam the platform contributor needs:
 * `getServices` is queried synchronously by the Services tree, so it only ever reads
 * the cached [snapshot]; the 1-30 s probe lives on the executor lane and announces
 * its result with a `RESET` event the tree re-queries on.
 */
@Service(Service.Level.PROJECT)
class XMakeServicesSnapshotService(private val project: Project) : Disposable {

    /** A probe or a state change landed; subscribers re-read the service's state. */
    fun interface Listener {
        fun snapshotUpdated()
    }

    private val probeGate = Any()

    @Volatile
    private var currentSnapshot: XMakeServicesSnapshot? = null

    /** True while a probe or a toggle/connect sits on the executor lane or runs against
     *  it. Controls on both surfaces disable then - the lane serializes against builds,
     *  so a click during the wait would queue a second command to run after the first.
     *  Cleared by the EDT hop that lands the result or the failure. */
    @Volatile
    var probing: Boolean = false
        private set

    /** The one status line both surfaces show; null before the first interaction. */
    @Volatile
    var statusMessage: String? = null
        private set

    /** Whether [statusMessage] names a failure, so the surfaces can color the line red -
     *  the "a red line means the last operation failed" rule the Packages panel follows. */
    @Volatile
    var statusIsFailure: Boolean = false
        private set

    private val messageBusConnection = project.messageBus.connect(this)
    private val endpoints get() = project.getService(XMakeServiceEndpointsService::class.java)

    init {
        // The configuration page and the status bar have no activation callback of their
        // own, so the snapshot follows the XMake window the console lives in: showing it
        // re-probes, and nothing probes while the window stays hidden.
        messageBusConnection.subscribe(ToolWindowManagerListener.TOPIC, object : ToolWindowManagerListener {
            override fun toolWindowShown(toolWindow: ToolWindow) {
                if (toolWindow.id == XMAKE_TOOL_WINDOW_ID) {
                    refresh()
                }
            }
        })
    }

    /** The last probe result, or null before the first one lands. Reading it is cheap
     *  and side-effect-free - this is what `getServices` and every render path use. */
    val snapshot: XMakeServicesSnapshot?
        get() = currentSnapshot

    /** One probe, submitted on the executor lane; the result reaches the surfaces in
     *  one EDT hop. Re-entrant calls are dropped: the activation listener, the shown
     *  listener and this action can all land here, and one probe in flight is one
     *  probe the lane needs. */
    fun refresh() {
        runThenReprobe { _, _ ->
            // nothing to run first: this is the probe only
        }
    }

    /** Connects one service kind through `xmake service --connect`. The one-time
     *  connect of a user-protected server prompts for a password on stdin, which the
     *  command channel cannot answer - so a connect without a stored token is
     *  explained instead of silently hanging the lane.
     *
     *  [endpoint] is the calling instance's own record when it has one: the kind's
     *  client.conf is re-pointed at it before the connect verb runs, so two instances
     *  of one kind connect to the addresses their nodes name instead of whichever
     *  record was applied last. */
    fun connectService(kind: XMakeServiceKind, endpoint: String? = null) {
        val state = currentSnapshot?.services?.get(kind) ?: return
        val effective = endpoint ?: state.endpoint ?: return
        if (!state.tokenKnown) {
            project.notify(
                "XMake services",
                "No token is stored for ${StringUtil.escapeXmlEntities(effective)}, so connecting would ask for a " +
                    "password on stdin. Run `xmake service --connect --${kind.flag}` in a terminal once to store it " +
                    "(server-side setup: `xmake service --add-user`, `xmake service --gen-token`).",
            )
            return
        }
        runThenReprobe { console, factory ->
            if (endpoint != null && endpoint != state.endpoint) {
                project.xmakeExecutor.execute(console, factory.createSetServiceEndpoint(kind, endpoint))
            }
            project.xmakeExecutor.execute(console, factory.createServiceAction(XMakeServiceVerb.CONNECT, kind))
        }
    }

    /** Disconnects one service kind through `xmake service --disconnect`. */
    fun disconnectService(kind: XMakeServiceKind) {
        runThenReprobe { console, factory ->
            project.xmakeExecutor.execute(console, factory.createServiceAction(XMakeServiceVerb.DISCONNECT, kind))
        }
    }

    /** Reconnects one service kind through `xmake service --reconnect`. */
    fun reconnectService(kind: XMakeServiceKind) {
        runThenReprobe { console, factory ->
            project.xmakeExecutor.execute(console, factory.createServiceAction(XMakeServiceVerb.RECONNECT, kind))
        }
    }

    /** Syncs the project files to the daemon of [kind] through `xmake service --sync`. */
    fun syncProject(kind: XMakeServiceKind) {
        runThenReprobe { console, factory ->
            project.xmakeExecutor.execute(console, factory.createServiceAction(XMakeServiceVerb.SYNC, kind))
        }
    }

    /** Pulls [remotePath] from the daemon of [kind] through `xmake service --pull`. */
    fun pullFrom(kind: XMakeServiceKind, remotePath: String) {
        runThenReprobe { console, factory ->
            project.xmakeExecutor.execute(
                console,
                factory.createServicePull(kind, remotePath),
            )
        }
    }

    /** Starts the daemon of [kind] through `xmake service --start`. */
    fun startDaemon(kind: XMakeServiceKind) {
        runThenReprobe { console, factory ->
            project.xmakeExecutor.execute(console, factory.createServiceAction(XMakeServiceVerb.START, kind))
        }
    }

    /** Stops the daemon of [kind] through `xmake service --stop`. */
    fun stopDaemon(kind: XMakeServiceKind) {
        runThenReprobe { console, factory ->
            project.xmakeExecutor.execute(console, factory.createServiceAction(XMakeServiceVerb.STOP, kind))
        }
    }

    /** Restarts the daemon of [kind] through `xmake service --restart`. */
    fun restartDaemon(kind: XMakeServiceKind) {
        runThenReprobe { console, factory ->
            project.xmakeExecutor.execute(console, factory.createServiceAction(XMakeServiceVerb.RESTART, kind))
        }
    }

    /** Sets one kind's endpoint from the editor. The value is validated, recorded in
     *  the project's [XMakeServiceEndpointsService], and materialized into xmake's own
     *  project-local client.conf by the bundled setservice.lua script on the executor
     *  lane - the IDE never writes the config itself, because on WSL/SSH toolkits it
     *  lives on the other host. A blank value clears the entry instead. */
    fun applyEndpoint(kind: XMakeServiceKind, raw: String) = applyEndpoint(kind.name, kind, raw)

    /** The instance form: [key] is the instance's name, [kind] the service kind whose
     *  client.conf entry the endpoint materializes into. */
    fun applyEndpoint(key: String, kind: XMakeServiceKind, raw: String) {
        val endpoint = raw.trim()
        if (endpoint.isEmpty()) {
            clearEndpoint(key, kind)
            return
        }
        if (XMakeServiceEndpointFormat.parse(endpoint) == null) {
            setMessage(ENDPOINT_INVALID_MESSAGE)
            return
        }
        if (endpoints.configuredEndpoint(key) == endpoint) {
            // What this instance already points at: an apply would only queue a lane
            // round trip.
            return
        }
        endpoints.setConfiguredEndpoint(key, endpoint)
        runThenReprobe { console, factory ->
            project.xmakeExecutor.execute(console, factory.createSetServiceEndpoint(kind, endpoint))
        }
    }

    /** Removes the key's project-local endpoint entry (and the stale persisted
     *  connection state with it) through the same script as [applyEndpoint]. [kind]
     *  is the service whose client.conf entry the empty endpoint materializes into -
     *  the caller knows it; guessing it from the key's name cleared a differently
     *  named instance into the wrong kind's config. */
    fun clearEndpoint(key: String, kind: XMakeServiceKind) {
        val hasAny = endpoints.configuredEndpoint(key) != null
        if (!hasAny) {
            return
        }
        endpoints.setConfiguredEndpoint(key, null)
        runThenReprobe { console, factory ->
            project.xmakeExecutor.execute(console, factory.createSetServiceEndpoint(kind, ""))
        }
    }

    /** Runs [operation] on the executor lane and re-probes the snapshot afterwards, so
     *  the surfaces show what xmake persisted, not what the command exit code suggests.
     *  Public for the tab's ccache toggle - the one non-service operation that shares
     *  this flow.
     *
     *  The controls disable for the whole wait - including the lane queue ahead of this
     *  task, which a running build occupies - and re-enable when the result or the
     *  failure lands. A call while one is in flight is dropped: it would only stack a
     *  second command behind the first. */
    fun runThenReprobe(operation: suspend (console: XMakeConsole, factory: XMakeCommandFactory) -> Unit) {
        val profile = project.activeOrSingleXMakeBuildProfile
        if (profile == null) {
            setMessage(SELECT_BUILD_PROFILE_FIRST)
            return
        }
        synchronized(probeGate) {
            if (probing) return
            probing = true
        }
        setMessage(
            if (project.xmakeExecutor.laneBusy) {
                "Waiting for the running build or command to finish, then probing the build cache and service status..."
            } else {
                "Probing the build cache and service status..."
            },
        )
        @Suppress("DeferredResultUnused") // fire-and-forget: the status message narrates progress
        project.xmakeExecutor.submit {
            try {
                val console = XMakeConsoleManager.getInstance(project).awaitReady()
                val factory = project.xmakeCommands(profile)
                operation(console, factory)
                val output = project.xmakeExecutor.captureStandardOutput(factory.createBuildServicesProbe())
                val probed = XMakeServicesProbeParser.parse(output)
                withContext(Dispatchers.EDT) {
                    probing = false
                    statusIsFailure = false
                    if (probed == null) {
                        // keep the last good snapshot: the probe produced nothing new
                        statusMessage =
                            "The services probe produced no data (xmake ${factory.toolkit.name}) - press Refresh to try again"
                    } else {
                        currentSnapshot = probed
                        statusMessage = PROBED_MESSAGE
                    }
                    publishSnapshotChanged()
                }
            } catch (cancellation: CancellationException) {
                // The project is going away with the probe still queued; the flag is all
                // the state that must not stay stuck.
                probing = false
                throw cancellation
            } catch (error: Exception) {
                Log.warn("XMake services probe failed", error)
                withContext(Dispatchers.EDT) {
                    probing = false
                    statusIsFailure = true
                    statusMessage = "XMake services probe failed: ${error.message} - press Refresh to try again"
                    // a failed toggle/connect must not leave the surfaces describing a
                    // state xmake refused: the last good snapshot simply stays
                    publishSnapshotChanged()
                }
            }
        }
    }

    /** Announces new state everywhere: the plugin's own surfaces re-render from
     *  [TOPIC], and the platform Services tree re-queries `getServices` on the RESET
     *  event fired alongside - the promise the probe's doc makes. */
    private fun publishSnapshotChanged() {
        project.publish(TOPIC) { snapshotUpdated() }
        resetTree(project)
    }

    private fun setMessage(message: String) {
        statusMessage = message
        statusIsFailure = false
        project.publish(TOPIC) { snapshotUpdated() }
    }

    override fun dispose() {
    }

    companion object {
        private val Log = logger<XMakeServicesSnapshotService>()

        @Topic.ProjectLevel
        val TOPIC: Topic<Listener> = Topic.create("XMake services snapshot updated", Listener::class.java)

        const val ENDPOINT_INVALID_MESSAGE = "Enter the endpoint as host:port, e.g. 192.168.0.192:9691"

        const val PROBED_MESSAGE = "Probed - the status refreshes itself after every action"
    }
}

val Project.xmakeServicesSnapshots: XMakeServicesSnapshotService
    get() = getService(XMakeServicesSnapshotService::class.java)
        ?: error("Failed to get XMakeServicesSnapshotService for $this")
