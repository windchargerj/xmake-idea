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
package io.xmake.project.console

import com.intellij.execution.services.ServiceViewContributor
import com.intellij.execution.services.ServiceViewDescriptor
import com.intellij.icons.AllIcons
import com.intellij.ide.projectView.PresentationData
import com.intellij.navigation.ItemPresentation
import com.intellij.openapi.actionSystem.ActionGroup
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DataKey
import com.intellij.openapi.actionSystem.DataSink
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.UiDataProvider
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.serviceOrNull
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.ValidationInfo
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBTextField
import com.intellij.util.messages.MessageBusConnection
import com.intellij.util.ui.JBUI
import io.xmake.icons.XMakeIcons
import io.xmake.utils.cli.XMakeServiceKind
import java.awt.BorderLayout
import javax.swing.JComponent
import javax.swing.JPanel

/** One added service instance: the kind it runs as and the name the user gave it -
 *  the Docker pattern, where several containers of one image coexist under their own
 *  names. The name also keys the endpoint the instance carries. */
data class XMakeServiceInstance(val kind: XMakeServiceKind, val name: String) {

    /** The tree node's text, the kind and the instance apart. */
    fun displayName(): String = "${kindName(kind)} - $name"
}

/**
 * The service instances the user added to the Services tree, persisted per project:
 * the tree starts empty, an Add dialog names the instance, the node stays until
 * removed. Several instances of one kind may coexist - two remote build hosts, say -
 * each carrying its own endpoint record.
 */
@Service(Service.Level.PROJECT)
@State(name = "XMakeServicesTreeState", storages = [Storage("xmake.services.xml")])
internal class XMakeServicesTreeState : PersistentStateComponent<XMakeServicesTreeState.State> {

    private var state = State()

    data class State(var instances: List<Instance> = emptyList()) {

        data class Instance(var kind: String = "", var name: String = "")
    }

    fun instances(): List<XMakeServiceInstance> = state.instances.mapNotNull { entry ->
        val kind = XMakeServiceKind.entries.firstOrNull { it.name == entry.kind } ?: return@mapNotNull null
        XMakeServiceInstance(kind, entry.name)
    }

    fun add(instance: XMakeServiceInstance) {
        if (state.instances.none { it.name == instance.name }) {
            state.instances += State.Instance(instance.kind.name, instance.name)
        }
    }

    fun remove(name: String) {
        state.instances = state.instances.filter { it.name != name }
    }

    override fun getState(): State = state

    override fun loadState(loaded: State) {
        state = loaded
    }

    companion object {
        fun getInstance(project: Project): XMakeServicesTreeState = project.getService(XMakeServicesTreeState::class.java)
    }
}

/** The instances the user added, as the tree reads them. */
internal fun addedInstances(project: Project): List<XMakeServiceInstance> =
    XMakeServicesTreeState.getInstance(project).instances()

/** The selected node's service kind, for the tree-node actions. */
internal val XMAKE_SERVICE_KIND_KEY: DataKey<XMakeServiceKind> = DataKey.create("io.xmake.service.kind")
internal val XMAKE_SERVICE_INSTANCE_KEY: DataKey<XMakeServiceInstance> = DataKey.create("io.xmake.service.instance")

/**
 * The XMake services as a platform Services contributor: one root ("XMake") with one
 * node per [XMakeServiceKind], status-iconized in the tree, editable and connectable
 * in the detail pane - the tree+detail shape of the Run Dashboard / SSH connections
 * the user already knows, next to which this window lives.
 *
 * The node data is a pure read of [XMakeServicesSnapshotService]'s cached snapshot:
 * the tree queries [getServices] synchronously, so this path never probes - the
 * snapshot service runs the probe on the executor lane and announces results with a
 * `RESET` event (G8/G10 of docs/roadmap-designs/audit-services.md). Registered in
 * plugin.xml as `com.intellij.serviceViewContributor`; the "Services View" plugin
 * ships the window itself and picks the extension up dynamically.
 */
class XMakeServicesContributor : ServiceViewContributor<XMakeServicesContributor.XMakeServiceNode> {

    /** One tree node: an added service instance and what the last probe saw of its
     *  kind. The instance's own endpoint rides the endpoints service, keyed by name. */
    data class XMakeServiceNode(
        val instance: XMakeServiceInstance,
        val state: XMakeServiceState,
        /** False before the first probe lands: nothing is known yet, not even that
         *  the kind is unconfigured. */
        val probed: Boolean,
    )

    override fun getViewDescriptor(project: Project): ServiceViewDescriptor = XMakeRootDescriptor(project)

    override fun getServices(project: Project): List<XMakeServiceNode> {
        val added = addedInstances(project)
        if (added.isEmpty()) return emptyList()
        val snapshot = project.serviceOrNull<XMakeServicesSnapshotService>()?.snapshot
        return added.map { instance ->
            // The snapshot's kind-level state is the kind's *effective* answer - what
            // the client would talk to. It feeds the node's icon and status text; the
            // node's location reads the instance's own record only, so an address
            // applied by a sibling instance never reads as this one's.
            val kindState = snapshot?.services?.get(instance.kind)
                ?: XMakeServiceState(instance.kind, null, false, XMakeReachability.UNTESTED, false)
            XMakeServiceNode(
                instance = instance,
                state = kindState,
                probed = snapshot != null,
            )
        }
    }

    override fun getServiceDescriptor(project: Project, service: XMakeServiceNode): ServiceViewDescriptor =
        XMakeServiceNodeDescriptor(project, service)

    /** The root's presentation and overview pane. Not a
     *  [com.intellij.execution.services.ServiceViewToolWindowDescriptor] on purpose:
     *  the plain root-in-Services form shares the platform window with the Run
     *  Dashboard and the SSH connections, which is how the SSH contributor presents
     *  itself too (audit-services.md section 5). */
    private class XMakeRootDescriptor(private val project: Project) : ServiceViewDescriptor {

        private val overview: JComponent by lazy { XMakeRootOverviewPanel(project) }

        override fun getPresentation(): ItemPresentation =
            PresentationData("XMake", null, XMakeIcons.XMAKE, null)

        override fun getContentComponent(): JComponent = overview

        override fun getToolbarActions(): ActionGroup =
            DefaultActionGroup(AddServiceActionGroup(), RefreshServicesAction())

        override fun getId(): String = "XMake"
    }

    /** One service kind's tree node: name, endpoint, status icon, and the detail pane
     *  that edits and connects it. */
    private class XMakeServiceNodeDescriptor(
        private val project: Project,
        private val node: XMakeServiceNode,
    ) : ServiceViewDescriptor, UiDataProvider {

        /** A fresh panel per descriptor keeps Swing and bus wiring disposable by
         *  construction: the tree rebuilds descriptors on every RESET, and nothing
         *  here outlives the rebuild that dropped the node's old state. */
        private val detail: JComponent by lazy {
            XMakeServiceDetailPanel(project, node.instance.kind, node.instance.name).apply {
                show(stateFor(project, node), node.probed)
                setBusy(project.serviceOrNull<XMakeServicesSnapshotService>()?.probing ?: false)
            }
        }

        override fun getPresentation(): ItemPresentation = PresentationData(
            node.instance.displayName(),
            // The location reads this instance's own record only. The kind-level answer
            // in the snapshot belongs to whichever instance applied last; repeating it
            // here would dress one host's address up as another node's, so an instance
            // without its own record reads as what it is: not configured.
            project.getService(XMakeServiceEndpointsService::class.java)
                .configuredEndpoint(node.instance.name) ?: "not configured",
            serviceStatusIcon(node.state, node.probed),
            null,
        )

        override fun getId(): String = node.instance.name

        override fun getContentComponent(): JComponent = detail

        override fun getToolbarActions(): ActionGroup = DefaultActionGroup(
            ConnectServiceAction(),
            DisconnectServiceAction(),
            RemoveServiceAction(),
            // The + rides the node's toolbar too: adding a second instance of one kind
            // must not start with a trip back to the root - the Docker pattern the tree
            // follows is add-while-looking-at-the-thing.
            AddServiceActionGroup(),
        )

        override fun uiDataSnapshot(sink: DataSink) {
            sink[XMAKE_SERVICE_KIND_KEY] = node.instance.kind
            sink[XMAKE_SERVICE_INSTANCE_KEY] = node.instance
        }
    }
}

/** The root's overview pane: the snapshot service's last status line over the road-in
 *  hint. The pane outlives single probes, so the status line re-reads on every snapshot
 *  event - a message read once at build time would freeze on whatever was current then,
 *  the probing narration and the failure lines never reaching the reader. */
private class XMakeRootOverviewPanel(private val project: Project) : JPanel(BorderLayout()), Disposable {

    private val statusLabel = JBLabel()

    /** The bus subscription, alive only while the pane sits on a component tree - the
     *  same shape the detail panel's wiring takes. */
    private var busConnection: MessageBusConnection? = null

    init {
        isOpaque = false
        border = JBUI.Borders.empty(24, 16)
        // The empty state is a page, not two floating labels: the mark anchors it, the
        // heading states what this node is, and the status and the hint read under it.
        add(
            JPanel().apply {
                isOpaque = false
                layout = javax.swing.BoxLayout(this, javax.swing.BoxLayout.Y_AXIS)
                add(JBLabel("Build services").apply {
                    font = com.intellij.util.ui.JBFont.create(
                        com.intellij.util.ui.UIUtil.getLabelFont().deriveFont(java.awt.Font.BOLD, 16f),
                    )
                })
                add(javax.swing.Box.createVerticalStrut(JBUI.scale(6)))
                add(statusLabel)
                add(javax.swing.Box.createVerticalStrut(JBUI.scale(4)))
                add(JBLabel("Select a service to edit its endpoint, connect, or disconnect").apply {
                    foreground = com.intellij.util.ui.UIUtil.getLabelInfoForeground()
                })
                add(javax.swing.Box.createVerticalGlue())
            },
            BorderLayout.NORTH,
        )
        render()
    }

    override fun addNotify() {
        super.addNotify()
        if (busConnection != null || project.isDisposed) return
        val connection = project.messageBus.connect(this)
        connection.subscribe(
            XMakeServicesSnapshotService.TOPIC,
            XMakeServicesSnapshotService.Listener { render() },
        )
        busConnection = connection
        // Events may have passed by while the pane was off the tree: arrive current.
        render()
    }

    override fun removeNotify() {
        busConnection?.dispose()
        busConnection = null
        super.removeNotify()
    }

    override fun dispose() {}

    /** The status line the snapshot service owns; the fallback names the actual probe
     *  trigger - showing the XMake tool window - not this window, which the reader is
     *  standing in while reading it. */
    private fun render() {
        if (project.isDisposed) return
        statusLabel.text = project.serviceOrNull<XMakeServicesSnapshotService>()?.statusMessage
            ?: "Open the XMake tool window to probe the build cache and service status"
    }
}

/** The endpoint the instance carries: its own record if the user set one, else the
 *  kind's effective answer from the probe. */
internal fun endpointOf(project: Project, node: XMakeServicesContributor.XMakeServiceNode): String? =
    project.getService(XMakeServiceEndpointsService::class.java).configuredEndpoint(node.instance.name)
        ?: node.state.endpoint

/** The state an instance presents: the kind's probe answer, with the instance's own
 *  endpoint where one is recorded. */
internal fun stateFor(project: Project, node: XMakeServicesContributor.XMakeServiceNode): XMakeServiceState =
    endpointOf(project, node)?.let { node.state.copy(endpoint = it) } ?: node.state

/** Connects the selected instance: its own endpoint record, when it has one, rides
 *  the connect so the kind's connection is re-pointed at it first - two instances of
 *  one kind otherwise both connect to whichever address was applied last. The
 *  snapshot service explains a missing token instead of letting
 *  `xmake service --connect` hang on a stdin prompt. */
private class ConnectServiceAction :
    DumbAwareAction("Connect", "Connect this service instance through xmake service", AllIcons.Actions.Execute) {

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(event: AnActionEvent) {
        val project = event.project
        val snapshots = project?.serviceOrNull<XMakeServicesSnapshotService>()
        val instance = event.getData(XMAKE_SERVICE_INSTANCE_KEY)
        // Connect needs an address on record: the instance's own, or the kind's
        // effective answer the connect would fall back to.
        val endpoint = if (project != null && instance != null) {
            project.getService(XMakeServiceEndpointsService::class.java).configuredEndpoint(instance.name)
                ?: snapshots?.snapshot?.services?.get(instance.kind)?.endpoint
        } else {
            null
        }
        event.presentation.isEnabled = instance != null &&
            snapshots?.probing == false &&
            endpoint != null
    }

    override fun actionPerformed(event: AnActionEvent) {
        val project = event.project ?: return
        val instance = event.getData(XMAKE_SERVICE_INSTANCE_KEY) ?: return
        val endpoint = project.getService(XMakeServiceEndpointsService::class.java)
            .configuredEndpoint(instance.name)
        project.xmakeServicesSnapshots.connectService(instance.kind, endpoint)
    }
}

/** Disconnects the selected instance's kind - the connection the verbs act on is one
 *  per kind, which the node's detail pane spells out when the instance's own record
 *  is not what it points at. */
private class DisconnectServiceAction :
    DumbAwareAction("Disconnect", "Disconnect this service instance through xmake service", AllIcons.Actions.Suspend) {

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(event: AnActionEvent) {
        val snapshots = event.project?.serviceOrNull<XMakeServicesSnapshotService>()
        val kind = event.getData(XMAKE_SERVICE_KIND_KEY)
        event.presentation.isEnabled = kind != null &&
            snapshots?.probing == false &&
            snapshots?.snapshot?.services?.get(kind)?.connected == true
    }

    override fun actionPerformed(event: AnActionEvent) {
        val kind = event.getData(XMAKE_SERVICE_KIND_KEY) ?: return
        event.project?.xmakeServicesSnapshots?.disconnectService(kind)
    }
}

/** The + toolbar entry: one item per kind, every time - the Docker Add group's shape,
 *  and one kind may carry several instances (two remote build hosts, say). Adding
 *  names the instance, puts it in the tree and fires the reset the tree re-queries on.
 *
 *  Registered in plugin.xml both on the root's own toolbar and inside the platform's
 *  tool-window "+" group (`ServiceView.AddService`): the model drops an empty
 *  contributor's root outright - `ServiceModel.reset` discards the node when
 *  `getServices` comes back empty - so a brand-new project has no root toolbar to
 *  reach the add entry from, and the tool-window-level one is the only path left. */
internal class AddServiceActionGroup : DefaultActionGroup {

    constructor() : super("Add XMake Service", true)

    init {
        templatePresentation.icon = AllIcons.General.Add
        templatePresentation.description = "Add a build service to the tree"
    }

    override fun getChildren(event: AnActionEvent?): Array<AnAction> =
        XMakeServiceKind.entries
            .map { kind ->
                object : DumbAwareAction(kindName(kind)) {
                    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

                    override fun update(event: AnActionEvent) {
                        event.presentation.isEnabled = event.project != null
                    }

                    override fun actionPerformed(event: AnActionEvent) {
                        val project = event.project ?: return
                        val dialog = AddServiceInstanceDialog(project, kind)
                        if (!dialog.showAndGet()) return
                        val name = dialog.instanceName() ?: return
                        XMakeServicesTreeState.getInstance(project)
                            .add(XMakeServiceInstance(kind, name))
                        // The endpoint the user typed in the dialog applies through the
                        // same live path the detail panel uses, before the reset: the
                        // node lands configured, and the reprobe that the apply queues
                        // reports it.
                        dialog.endpointValue()?.let { endpoint ->
                            project.xmakeServicesSnapshots.applyEndpoint(name, kind, endpoint)
                        }
                        resetTree(project)
                    }
                }
            }.toTypedArray()
}

/** The Add dialog: everything the user already has in hand - the instance's name and
 *  its endpoint - lands in one pass, and Add applies the endpoint through the same
 *  live path the detail panel uses, so the node appears configured and the probe that
 *  follows reports the real thing. A blank name or a duplicate or a malformed endpoint
 *  is rejected in the dialog, not after it. */
private class AddServiceInstanceDialog(
    project: Project,
    private val kind: XMakeServiceKind,
) : DialogWrapper(project) {

    private val treeState = XMakeServicesTreeState.getInstance(project)

    private val nameField = JBTextField(24).apply { emptyText.text = "e.g. the host it runs on" }
    private val endpointField = JBTextField(24).apply { emptyText.text = "host:port (optional now - editable later)" }

    init {
        title = "Add ${kindName(kind)}"
        setOKButtonText("Add")
        init()
    }

    override fun createCenterPanel(): JComponent = JPanel(java.awt.GridBagLayout()).apply {
        isOpaque = false
        fun label(text: String, row: Int): Unit = add(
            JBLabel(text),
            java.awt.GridBagConstraints().apply {
                gridx = 0
                gridy = row
                weightx = 0.0
                anchor = java.awt.GridBagConstraints.WEST
                insets = JBUI.insets(6, 0, 6, 8)
            },
        )
        fun field(component: JComponent, row: Int): Unit = add(
            component,
            java.awt.GridBagConstraints().apply {
                gridx = 1
                gridy = row
                weightx = 1.0
                anchor = java.awt.GridBagConstraints.WEST
                fill = java.awt.GridBagConstraints.HORIZONTAL
                insets = JBUI.insets(6, 0, 6, 0)
            },
        )
        label("Name:", 0)
        field(nameField, 0)
        label("Endpoint:", 1)
        field(endpointField, 1)
    }

    override fun getPreferredFocusedComponent(): JComponent = nameField

    override fun doValidate(): com.intellij.openapi.ui.ValidationInfo? {
        val name = nameField.text.trim()
        val endpoint = endpointField.text.trim()
        return when {
            name.isEmpty() -> com.intellij.openapi.ui.ValidationInfo("Name the instance", nameField)
            treeState.instances().any { it.name == name } ->
                com.intellij.openapi.ui.ValidationInfo("\"$name\" is already in the tree", nameField)
            endpoint.isNotEmpty() && XMakeServiceEndpointFormat.parse(endpoint) == null ->
                com.intellij.openapi.ui.ValidationInfo("The endpoint must read host:port", endpointField)
            else -> null
        }
    }

    fun instanceName(): String? = nameField.text.trim().takeUnless { it.isEmpty() }

    fun endpointValue(): String? = endpointField.text.trim().takeUnless { it.isEmpty() }
}

/** Removes the selected instance from the tree and drops its endpoint record with it:
 *  the record is keyed by the instance's name and nothing else reads that key - the
 *  Settings page reads kind-named records only - so keeping it would orphan it. */
private class RemoveServiceAction : DumbAwareAction(
    "Remove from Tree",
    "Take this instance out of the tree and drop its endpoint record with it",
    AllIcons.General.Remove,
) {

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(event: AnActionEvent) {
        event.presentation.isEnabled = event.getData(XMAKE_SERVICE_INSTANCE_KEY) != null
    }

    override fun actionPerformed(event: AnActionEvent) {
        val project = event.project ?: return
        val instance = event.getData(XMAKE_SERVICE_INSTANCE_KEY) ?: return
        XMakeServicesTreeState.getInstance(project).remove(instance.name)
        // A plain store write, not clearEndpoint: clearing would queue a lane round
        // trip materializing an empty endpoint into the kind's client.conf, which the
        // instance's siblings still point at.
        project.getService(XMakeServiceEndpointsService::class.java)
            .setConfiguredEndpoint(instance.name, null)
        resetTree(project)
    }
}

/** Reconnects this service kind; xmake re-establishes the persisted session. */
private class ReconnectServiceAction : DumbAwareAction(
    "Reconnect",
    "Reconnect this service through xmake service --reconnect",
    AllIcons.Actions.Refresh,
) {

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(event: AnActionEvent) {
        val kind = event.getData(XMAKE_SERVICE_KIND_KEY)
        event.presentation.isEnabled = kind != null &&
            event.project?.xmakeServicesSnapshots?.probing == false
    }

    override fun actionPerformed(event: AnActionEvent) {
        val kind = event.getData(XMAKE_SERVICE_KIND_KEY) ?: return
        event.project?.xmakeServicesSnapshots?.reconnectService(kind)
    }
}

/** Syncs the project files to this kind's daemon. */
private class SyncServiceAction : DumbAwareAction(
    "Sync project",
    "Sync current project files to the daemon (xmake service --sync)",
    AllIcons.Actions.Commit,
) {

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(event: AnActionEvent) {
        val kind = event.getData(XMAKE_SERVICE_KIND_KEY)
        event.presentation.isEnabled = kind != null &&
            event.project?.xmakeServicesSnapshots?.probing == false
    }

    override fun actionPerformed(event: AnActionEvent) {
        val kind = event.getData(XMAKE_SERVICE_KIND_KEY) ?: return
        event.project?.xmakeServicesSnapshots?.syncProject(kind)
    }
}

/** Pulls a path from this kind's daemon, asked by input dialog. */
private class PullServiceAction : DumbAwareAction(
    "Pull from daemon",
    "Pull a file or directory from the daemon into the project (xmake service --pull)",
    com.intellij.icons.AllIcons.Actions.Download,
) {

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(event: AnActionEvent) {
        val kind = event.getData(XMAKE_SERVICE_KIND_KEY)
        event.presentation.isEnabled = kind != null &&
            event.project?.xmakeServicesSnapshots?.probing == false
    }

    override fun actionPerformed(event: AnActionEvent) {
        val project = event.project ?: return
        val kind = event.getData(XMAKE_SERVICE_KIND_KEY) ?: return
        val path = com.intellij.openapi.ui.Messages.showInputDialog(
            project,
            "Pull which file or directory from the daemon?",
            "Pull from ${kindName(kind)}",
            null,
        )?.trim().takeUnless { it.isNullOrEmpty() } ?: return
        project.xmakeServicesSnapshots.pullFrom(kind, path)
    }
}

/** Starts this kind's daemon. */
private class StartDaemonAction : DumbAwareAction(
    "Start daemon",
    "Start the daemon service (xmake service --start)",
    com.intellij.icons.AllIcons.Actions.Execute,
) {

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(event: AnActionEvent) {
        val kind = event.getData(XMAKE_SERVICE_KIND_KEY)
        event.presentation.isEnabled = kind != null &&
            event.project?.xmakeServicesSnapshots?.probing == false
    }

    override fun actionPerformed(event: AnActionEvent) {
        val kind = event.getData(XMAKE_SERVICE_KIND_KEY) ?: return
        event.project?.xmakeServicesSnapshots?.startDaemon(kind)
    }
}

/** Stops this kind's daemon. */
private class StopDaemonAction : DumbAwareAction(
    "Stop daemon",
    "Stop the daemon service (xmake service --stop)",
    com.intellij.icons.AllIcons.Actions.Suspend,
) {

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(event: AnActionEvent) {
        val kind = event.getData(XMAKE_SERVICE_KIND_KEY)
        event.presentation.isEnabled = kind != null &&
            event.project?.xmakeServicesSnapshots?.probing == false
    }

    override fun actionPerformed(event: AnActionEvent) {
        val kind = event.getData(XMAKE_SERVICE_KIND_KEY) ?: return
        event.project?.xmakeServicesSnapshots?.stopDaemon(kind)
    }
}

/** Restarts this kind's daemon. */
private class RestartDaemonAction : DumbAwareAction(
    "Restart daemon",
    "Restart the daemon service (xmake service --restart)",
    com.intellij.icons.AllIcons.Actions.Rerun,
) {

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(event: AnActionEvent) {
        val kind = event.getData(XMAKE_SERVICE_KIND_KEY)
        event.presentation.isEnabled = kind != null &&
            event.project?.xmakeServicesSnapshots?.probing == false
    }

    override fun actionPerformed(event: AnActionEvent) {
        val kind = event.getData(XMAKE_SERVICE_KIND_KEY) ?: return
        event.project?.xmakeServicesSnapshots?.restartDaemon(kind)
    }
}

/** Asks the Services tree to re-read [XMakeServicesContributor.getServices]; the
 * snapshot service fires it with every announced snapshot change. */
internal fun resetTree(project: Project) {
    project.messageBus.syncPublisher(com.intellij.execution.services.ServiceEventListener.TOPIC)
        .handle(com.intellij.execution.services.ServiceEventListener.ServiceEvent.createResetEvent(XMakeServicesContributor::class.java))
}

/** Re-probes the snapshot; the tree re-queries on the RESET event that lands with it. */
private class RefreshServicesAction :
    DumbAwareAction("Refresh", "Re-probe the build cache and service status", AllIcons.Actions.Refresh) {

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(event: AnActionEvent) {
        event.presentation.isEnabled = event.project?.serviceOrNull<XMakeServicesSnapshotService>()?.probing == false
    }

    override fun actionPerformed(event: AnActionEvent) {
        event.project?.let { project ->
            if (project.serviceOrNull<XMakeServicesSnapshotService>() != null) {
                project.xmakeServicesSnapshots.refresh()
            }
        }
    }
}
