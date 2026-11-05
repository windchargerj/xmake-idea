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

/** The kinds the user added to the Services tree, persisted per project: the Docker
 *  pattern - the tree starts empty, an Add action puts a service in, the node stays
 *  until removed. */
@Service(Service.Level.PROJECT)
@State(name = "XMakeServicesTreeState", storages = [Storage("xmake.services.xml")])
internal class XMakeServicesTreeState : PersistentStateComponent<XMakeServicesTreeState.State> {

    private var state = State()

    data class State(var kinds: List<String> = emptyList())

    fun isAdded(kind: XMakeServiceKind): Boolean = kind.name in state.kinds

    fun add(kind: XMakeServiceKind) {
        if (kind.name !in state.kinds) state.kinds += kind.name
    }

    fun remove(kind: XMakeServiceKind) {
        state.kinds -= kind.name
    }

    override fun getState(): State = state

    override fun loadState(loaded: State) {
        state = loaded
    }

    companion object {
        fun getInstance(project: Project): XMakeServicesTreeState = project.getService(XMakeServicesTreeState::class.java)
    }
}

/** The kinds the user added, as the tree reads them. */
internal fun addedKinds(project: Project): List<XMakeServiceKind> =
    XMakeServicesTreeState.getInstance(project).state.kinds.mapNotNull { name ->
        XMakeServiceKind.entries.firstOrNull { it.name == name }
    }

/** The selected node's service kind, for the tree-node actions. */
internal val XMAKE_SERVICE_KIND_KEY: DataKey<XMakeServiceKind> = DataKey.create("io.xmake.service.kind")

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

    /** One tree node: a service kind and what the last probe saw of it. */
    data class XMakeServiceNode(
        val kind: XMakeServiceKind,
        val state: XMakeServiceState,
        /** False before the first probe lands: nothing is known yet, not even that
         *  the kind is unconfigured. */
        val probed: Boolean,
    )

    override fun getViewDescriptor(project: Project): ServiceViewDescriptor = XMakeRootDescriptor(project)

    override fun getServices(project: Project): List<XMakeServiceNode> {
        val added = addedKinds(project)
        if (added.isEmpty()) return emptyList()
        val snapshot = project.serviceOrNull<XMakeServicesSnapshotService>()?.snapshot
        return added.map { kind ->
            XMakeServiceNode(
                kind = kind,
                state = snapshot?.services?.get(kind)
                    ?: XMakeServiceState(kind, null, false, XMakeReachability.UNTESTED, false),
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
            XMakeServiceDetailPanel(project, node.kind).apply {
                show(node.state, node.probed)
                setBusy(project.serviceOrNull<XMakeServicesSnapshotService>()?.probing ?: false)
            }
        }

        override fun getPresentation(): ItemPresentation = PresentationData(
            kindName(node.kind),
            node.state.endpoint ?: "not configured",
            serviceStatusIcon(node.state, node.probed),
            null,
        )

        override fun getId(): String = node.kind.name

        override fun getContentComponent(): JComponent = detail

        override fun getToolbarActions(): ActionGroup =
            DefaultActionGroup(ConnectServiceAction(), DisconnectServiceAction(), RemoveServiceAction())

        override fun uiDataSnapshot(sink: DataSink) {
            sink[XMAKE_SERVICE_KIND_KEY] = node.kind
        }
    }
}

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
            .filterNot { event?.project?.let { p -> addedKinds(p) }?.contains(it) == true }
            .map { kind ->
                object : DumbAwareAction(kindName(kind)) {
                    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

                    override fun update(event: AnActionEvent) {
                        event.presentation.isEnabled = event.project != null
                    }

                    override fun actionPerformed(event: AnActionEvent) {
                        val project = event.project ?: return
                        XMakeServicesTreeState.getInstance(project).add(kind)
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
        event.presentation.isEnabled = event.getData(XMAKE_SERVICE_KIND_KEY) != null
    }

    override fun actionPerformed(event: AnActionEvent) {
        val project = event.project ?: return
        val kind = event.getData(XMAKE_SERVICE_KIND_KEY) ?: return
        XMakeServicesTreeState.getInstance(project).remove(kind)
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
