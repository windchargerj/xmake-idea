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
import com.intellij.openapi.actionSystem.UiDataProvider
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.serviceOrNull
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.ui.components.JBLabel
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
            // The snapshot's kind-level state is the kind's *effective* answer - the
            // endpoint its client would talk to. An instance the user pointed at the
            // same kind reads that; a differently named one reads as its own.
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

        private val overview: JComponent by lazy {
            JPanel(BorderLayout()).apply {
                isOpaque = false
                border = JBUI.Borders.empty(12)
                val snapshots = project.serviceOrNull<XMakeServicesSnapshotService>()
                // The two sentences are one paragraph: stacked at the top with a breath
                // between them, not pinned to opposite edges of the pane.
                add(
                    JPanel().apply {
                        isOpaque = false
                        layout = javax.swing.BoxLayout(this, javax.swing.BoxLayout.Y_AXIS)
                        add(JBLabel(snapshots?.statusMessage ?: "Show this window or the XMake tab to probe the build cache and service status"))
                        add(javax.swing.Box.createVerticalStrut(JBUI.scale(6)))
                        add(JBLabel("Select a service to edit its endpoint, connect, or disconnect"))
                    },
                    BorderLayout.NORTH,
                )
            }
        }

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
            endpointOf(project, node) ?: "not configured",
            serviceStatusIcon(node.state, node.probed),
            null,
        )

        override fun getId(): String = node.instance.name

        override fun getContentComponent(): JComponent = detail

        override fun getToolbarActions(): ActionGroup =
            DefaultActionGroup(ConnectServiceAction(), DisconnectServiceAction(), RemoveServiceAction())

        override fun uiDataSnapshot(sink: DataSink) {
            sink[XMAKE_SERVICE_KIND_KEY] = node.instance.kind
            sink[XMAKE_SERVICE_INSTANCE_KEY] = node.instance
        }
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

/** Connects the selected service kind; the snapshot service explains a missing token
 *  instead of letting `xmake service --connect` hang on a stdin prompt. */
private class ConnectServiceAction :
    DumbAwareAction("Connect", "Connect this service kind through xmake service", AllIcons.Actions.Execute) {

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(event: AnActionEvent) {
        val snapshots = event.project?.serviceOrNull<XMakeServicesSnapshotService>()
        val kind = event.getData(XMAKE_SERVICE_KIND_KEY)
        event.presentation.isEnabled = kind != null &&
            snapshots?.probing == false &&
            snapshots?.snapshot?.services?.get(kind)?.endpoint != null
    }

    override fun actionPerformed(event: AnActionEvent) {
        val kind = event.getData(XMAKE_SERVICE_KIND_KEY) ?: return
        event.project?.xmakeServicesSnapshots?.connectService(kind)
    }
}

/** Disconnects the selected service kind. */
private class DisconnectServiceAction :
    DumbAwareAction("Disconnect", "Disconnect this service kind through xmake service", AllIcons.Actions.Suspend) {

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

/** The + toolbar entry: one item per kind not yet in the tree - the Docker Add
 *  group's shape. Adding puts the kind in the tree and fires the reset the tree
 *  re-queries on. */
private class AddServiceActionGroup : DefaultActionGroup(
    "Add XMake Service",
    true,
) {

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
                        val name = com.intellij.openapi.ui.Messages.showInputDialog(
                            project,
                            "Name this ${kindName(kind)} instance (e.g. the host it runs on):",
                            "Add ${kindName(kind)}",
                            null,
                        )?.trim().takeUnless { it.isNullOrEmpty() } ?: return
                        XMakeServicesTreeState.getInstance(project)
                            .add(XMakeServiceInstance(kind, name))
                        resetTree(project)
                    }
                }
            }.toTypedArray()
}

/** Removes the selected kind from the tree; its stored endpoint stays in Settings. */
private class RemoveServiceAction : DumbAwareAction(
    "Remove from Tree",
    "Take this service out of the tree; its endpoint stays in Settings",
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
        resetTree(project)
    }
}

/** Asks the Services tree to re-read [XMakeServicesContributor.getServices]. */
private fun resetTree(project: Project) {
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
