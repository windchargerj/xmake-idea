/*!A Xmake integration in IntelliJ IDEA/Clion
 *
 * Licensed under the Apache License 2.0 (the "License");
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
package io.xmake.project.console

import com.intellij.openapi.Disposable
import com.intellij.openapi.options.Configurable
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogPanel
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBTextField
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.bindText
import com.intellij.ui.dsl.builder.panel
import com.intellij.util.messages.MessageBusConnection
import com.intellij.util.ui.UIUtil
import io.xmake.utils.cli.XMakeServiceKind
import javax.swing.JButton
import javax.swing.JComponent

/**
 * The build services' configuration: one section per instance the user added to the
 * Services tree - the tree's own names and the endpoint records under them, the same
 * keys the tree's editor reads - each with its endpoint field and the connect,
 * disconnect and clear verbs, on the platform's own settings builder - rows, captions,
 * and spacing are its, not ours to align.
 *
 * These are settings, not a monitor: a remote build host, a distcc cluster, a remote
 * cache are where the build goes, and the values persist per project. But the page is
 * not blind while it is open: every group answers with the status words and icon the
 * tree shows (probing, connected, not configured), re-read on
 * [XMakeServicesSnapshotService]'s TOPIC, and the page's own line turns red when the
 * last operation failed - the Packages panel's red-line rule.
 *
 * The endpoints commit through the page's Apply (or OK; Enter applies too) rather
 * than at every keystroke: a half-typed `h:9` must not pass the format check and
 * queue a setservice run that a busy probe then drops whole, and Cancel must be able
 * to roll an unapplied edit back.
 */
class XMakeServicesConfigurable(private val valProject: Project) : Configurable, Disposable {

    private val snapshotService = valProject.xmakeServicesSnapshots
    private val endpoints = valProject.getService(XMakeServiceEndpointsService::class.java)

    /** The panel is built once per page open; the DSL owns its layout. */
    private var page: DialogPanel? = null

    /** The page's own answer line: the service's status words, red when the last
     *  operation failed - the Packages panel's red-line rule. */
    private var statusLine: JBLabel? = null

    /** That line's re-probe button; off while a probe or a state change sits on the
     *  executor lane - a second click would only queue behind the first. */
    private var refreshButton: JButton? = null

    /** One row per tree instance, kept so [refreshStatus] can re-render statuses and
     *  re-gate the controls without rebuilding the page. */
    private var instanceRows: List<InstanceRow> = emptyList()

    /** The snapshot announcements, connected for the page's lifetime and cut loose
     *  with the page itself. */
    private var connection: MessageBusConnection? = null

    override fun getDisplayName(): String = "Services"

    override fun createComponent(): JComponent {
        val built = buildPage()
        page = built
        connection?.disconnect()
        connection = valProject.messageBus.connect().also { bus ->
            bus.subscribe(XMakeServicesSnapshotService.TOPIC,
                XMakeServicesSnapshotService.Listener { refreshStatus() })
        }
        // The build already read the service's state, but the render is one path: the
        // same pass the announcements run paints the first one too.
        refreshStatus()
        return built
    }

    override fun isModified(): Boolean = page?.isModified() ?: false

    override fun apply() {
        page?.apply()
    }

    /** Reset is no place to probe: the platform calls it right after building the page
     *  (and again on Cancel), so a refresh here would queue an xmake probe behind a
     *  running build merely for opening Settings. The initial probe is the tool
     *  window's own, on its shown listener. */
    override fun reset() {
        page?.reset()
    }

    override fun disposeUIResources() {
        connection?.disconnect()
        connection = null
        page = null
        instanceRows = emptyList()
        statusLine = null
        refreshButton = null
    }

    override fun dispose() {}

    private fun buildPage(): DialogPanel {
        instanceRows = emptyList()
        val instances = addedInstances(valProject)
        val pageStatus = JBLabel()
        statusLine = pageStatus
        return panel {
            if (instances.isEmpty()) {
                // The tree starts empty by design - the page answers the empty state
                // instead of rendering kind sections the tree knows nothing of.
                row { comment("No services added yet - add one in the Services tool window") }
            }
            instances.forEach { instance ->
                val kind = instance.kind
                val status = JBLabel()
                val field = JBTextField(endpoints.configuredEndpoint(instance.name) ?: "")
                group(instance.displayName()) {
                    row {
                        comment(
                            when (kind) {
                                XMakeServiceKind.REMOTE_BUILD -> "Build on a remote host over SSH"
                                XMakeServiceKind.DISTCC_BUILD -> "Distribute compilation across distcc hosts"
                                XMakeServiceKind.REMOTE_CACHE -> "Reuse build artifacts from a remote cache"
                            },
                        )
                    }
                    row { cell(status) }
                    row("Endpoint") {
                        // The field binds into the page: Apply (or OK) commits it through
                        // the snapshot service, so a half-typed value never queues a
                        // setservice run per keystroke, and Cancel rolls an unapplied
                        // edit back.
                        cell(field).align(AlignX.FILL)
                            .bindText(
                                { endpoints.configuredEndpoint(instance.name) ?: "" },
                                { snapshotService.applyEndpoint(instance.name, kind, it) },
                            )
                            .comment("host:port")
                    }
                    // Enter in the field applies the page - the dialog's own default
                    // button would land on the same page.apply() either way.
                    field.addActionListener { page?.apply() }
                    row {
                        val connect = button("Connect") { snapshotService.connectService(kind) }.component
                        val disconnect = button("Disconnect") { snapshotService.disconnectService(kind) }.component
                        val clear = button("Clear") { snapshotService.clearEndpoint(instance.name, kind) }.component
                        instanceRows += InstanceRow(instance, field, status, connect, disconnect, clear)
                    }
                }
            }
            row {
                cell(pageStatus)
                refreshButton = button("Refresh") { snapshotService.refresh() }.component
            }
        }
    }

    /** Re-renders the status lines and the control gates from the service's current
     *  state: the words and icon the tree shows per instance, the page's own answer
     *  line, and the busy gate - while a probe or a state change sits on the executor
     *  lane, every control is off, for a click then would only queue behind it. Runs
     *  on every snapshot announcement, on the EDT the publisher hops through. */
    private fun refreshStatus() {
        val snapshot = snapshotService.snapshot
        val probed = snapshot != null
        val busy = snapshotService.probing
        statusLine?.let { line ->
            line.text = snapshotService.statusMessage
                ?: "Open the XMake tool window to probe the services and see the live state"
            line.foreground = if (snapshotService.statusIsFailure) JBColor.RED else UIUtil.getLabelInfoForeground()
        }
        refreshButton?.isEnabled = !busy
        instanceRows.forEach { row ->
            val state = stateOf(row.instance, snapshot)
            row.status.text = state.shortStatus(probed)
            row.status.icon = serviceStatusIcon(state, probed)
            row.status.toolTipText = serviceStatusText(state, probed)
            row.field.isEnabled = !busy
            // The verbs follow the state exactly as the tree's detail panel gates them:
            // connect needs an endpoint and no persisted connection, disconnect the
            // connection, clear an entry to remove.
            row.connect.isEnabled = !busy && state.endpoint != null && !state.connected
            row.disconnect.isEnabled = !busy && state.connected
            row.clear.isEnabled = !busy && state.endpoint != null
        }
    }

    /** The state one instance presents: the kind's probe answer with the instance's
     *  own endpoint record where one is set - the same mapping the Services tree's
     *  nodes read, so the page and the tree cannot disagree about who points where. */
    private fun stateOf(instance: XMakeServiceInstance, snapshot: XMakeServicesSnapshot?): XMakeServiceState {
        val kindState = snapshot?.services?.get(instance.kind) ?: instance.kind.unprobedState()
        return endpoints.configuredEndpoint(instance.name)
            ?.let { kindState.copy(endpoint = it) }
            ?: kindState
    }

    /** One instance's controls, kept so [refreshStatus] can re-render and re-gate them
     *  without rebuilding the page. */
    private class InstanceRow(
        val instance: XMakeServiceInstance,
        val field: JBTextField,
        val status: JBLabel,
        val connect: JButton,
        val disconnect: JButton,
        val clear: JButton,
    )
}
