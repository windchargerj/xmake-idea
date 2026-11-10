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
import com.intellij.openapi.actionSystem.Separator
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
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.panel
import com.intellij.util.messages.MessageBusConnection
import com.intellij.util.ui.JBUI
import io.xmake.icons.XMakeIcons
import io.xmake.run.command.xmakeExecutor
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
            ReconnectServiceAction(),
            Separator.create(),
            SyncServiceAction(),
            PullServiceAction(),
            Separator.create(),
            StartDaemonAction(),
            StopDaemonAction(),
            RestartDaemonAction(),
            Separator.create(),
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
    private val hintLabel = JBLabel().apply {
        foreground = com.intellij.util.ui.UIUtil.getLabelInfoForeground()
    }

    /** The quick-start row: one link per kind, each adding a configured instance
     *  without a dialog - the Docker flow's spirit, that a first look costs no
     *  typing. Hidden once the tree holds instances; the + path stays for the
     *  deliberate second instance. */
    private val quickStart = JPanel(java.awt.FlowLayout(java.awt.FlowLayout.LEFT, 0, 0)).apply { isOpaque = false }

    /** The build cache section: the persisted ccache option and the program the probe
     *  resolved, the project-level half of the snapshot. The checkbox flips the option
     *  through the same lane-and-reprobe flow every other verb takes. */
    private val cacheHeading = JBLabel("Build cache").apply {
        font = com.intellij.util.ui.JBFont.label().deriveFont(java.awt.Font.BOLD)
    }
    private val cacheToggle = javax.swing.JCheckBox("Project option: ccache")
    private val cacheProgram = JBLabel().apply {
        foreground = com.intellij.util.ui.UIUtil.getLabelInfoForeground()
    }

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
                add(JBLabel("Build services", XMakeIcons.XMAKE, javax.swing.SwingConstants.LEADING).apply {
                    font = com.intellij.util.ui.JBFont.create(
                        com.intellij.util.ui.UIUtil.getLabelFont().deriveFont(java.awt.Font.BOLD, 16f),
                    )
                    iconTextGap = JBUI.scale(8)
                })
                add(javax.swing.Box.createVerticalStrut(JBUI.scale(6)))
                add(statusLabel)
                add(javax.swing.Box.createVerticalStrut(JBUI.scale(4)))
                add(hintLabel)
                add(javax.swing.Box.createVerticalStrut(JBUI.scale(8)))
                add(quickStart)
                add(javax.swing.Box.createVerticalStrut(JBUI.scale(16)))
                add(cacheHeading)
                add(javax.swing.Box.createVerticalStrut(JBUI.scale(4)))
                add(cacheToggle)
                add(javax.swing.Box.createVerticalStrut(JBUI.scale(2)))
                add(cacheProgram)
                add(javax.swing.Box.createVerticalGlue())
            },
            BorderLayout.NORTH,
        )
        // One link per kind: the click adds the instance at xmake's default endpoint
        // and the probe the apply queues reports the truth into the same page.
        XMakeServiceKind.entries.forEach { kind ->
            quickStart.add(
                com.intellij.ui.components.ActionLink("Add ${kindName(kind)}") {
                    addInstanceWithDefaults(project, kind)
                }.apply {
                    toolTipText = "Adds a ${kindName(kind)} instance at ${kind.defaultEndpoint()} and probes it"
                },
            )
            if (kind != XMakeServiceKind.entries.last()) {
                quickStart.add(javax.swing.Box.createHorizontalStrut(JBUI.scale(16)))
            }
        }
        // The cache toggle rides the executor lane and re-probes, the way every other
        // verb on this page does; the render pass re-reads the answer.
        cacheToggle.addActionListener {
            val snapshots = project.serviceOrNull<XMakeServicesSnapshotService>() ?: return@addActionListener
            snapshots.runThenReprobe { console, factory ->
                project.xmakeExecutor.execute(console, factory.createCcacheToggle(cacheToggle.isSelected))
            }
        }
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
        val snapshots = project.serviceOrNull<XMakeServicesSnapshotService>()
        statusLabel.text = snapshots?.statusMessage
            ?: "Open the XMake tool window to probe the build cache and service status"
        // The hint answers the reader's actual next step: an empty tree offers the
        // one-click adds below; a filled one says what selecting does.
        val instances = XMakeServicesTreeState.getInstance(project).instances()
        hintLabel.text = when (instances.size) {
            0 -> "Start with a local default - everything stays editable afterwards"
            else -> "${instances.size} instance(s) - select one to edit its endpoint, connect, or disconnect"
        }
        quickStart.isVisible = instances.isEmpty()
        quickStart.parent?.revalidate()
        quickStart.parent?.repaint()
        // The cache half of the snapshot: the persisted option rides the checkbox, the
        // resolved program reads beside it. A checkbox the user is editing keeps its
        // state until the probe lands.
        val snapshot = snapshots?.snapshot
        val busy = snapshots?.probing ?: false
        if (!cacheToggle.hasFocus()) {
            cacheToggle.isSelected = snapshot?.cache?.enabled ?: false
        }
        cacheToggle.isEnabled = !busy
        cacheToggle.toolTipText = when {
            busy -> "Probing the build cache and service status..."
            else -> "Flips the persisted ccache option (xmake f --ccache=y|n) and re-probes"
        }
        cacheProgram.text = when {
            snapshot == null -> ""
            snapshot.cache.program != null -> "Effective: ${snapshot.cache.program}"
            else -> "No cache tool found - builds run uncached"
        }
    }
}

/** The endpoint an instance of [kind] starts from: xmake's own daemon default on the
 *  local host (client_config.lua's ports, G10 of build-services.md). */
internal fun XMakeServiceKind.defaultEndpoint(): String = "127.0.0.1:$defaultPort"

/** The default instance name: the endpoint's host, uniquified across the tree - the
 *  names are the tree's identity keys and unique across kinds, so a second local
 *  instance of any kind becomes `127.0.0.1-2`, not a duplicate. */
internal fun defaultInstanceName(project: Project, endpoint: String): String {
    val host = endpoint.substringBefore(':')
    val treeState = XMakeServicesTreeState.getInstance(project)
    val taken = treeState.instances().mapTo(mutableSetOf()) { instance -> instance.name }
    return generateSequence(host) { candidate -> "$candidate-2" }.first { candidate -> candidate !in taken }
}

/**
 * Adds an instance of [kind] at [kind]'s default endpoint and probes it: the whole
 * quick-start in one call, the same path the Add dialog's OK takes with its fields
 * untouched. The instance lands configured; the apply queues the re-probe that
 * reports the real reachability.
 */
internal fun addInstanceWithDefaults(project: Project, kind: XMakeServiceKind) {
    addInstance(project, kind, defaultInstanceName(project, kind.defaultEndpoint()), kind.defaultEndpoint())
}

/** The one add path the dialog and the quick links share: store the instance, apply
 *  the endpoint (which queues the lane round trip and the re-probe), reset the tree. */
internal fun addInstance(project: Project, kind: XMakeServiceKind, name: String, endpoint: String?) {
    XMakeServicesTreeState.getInstance(project).add(XMakeServiceInstance(kind, name))
    endpoint?.let { value ->
        project.xmakeServicesSnapshots.applyEndpoint(name, kind, value)
    }
    resetTree(project)
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
            // A connect before the first probe has nothing to verify the token
            // against; the service explains the missing probe, and the button stays
            // off so the click cannot look dead.
            snapshots.snapshot != null &&
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
            snapshots.snapshot?.services?.get(kind)?.connected == true
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

    constructor() : super("XMake Service", true)

    init {
        // The mark and the plain name, not a generic plus carrying an "Add" command: the
        // entry announces what lives under it, and the dropdown's kinds carry the act.
        templatePresentation.icon = XMakeIcons.XMAKE
        templatePresentation.description = "The build service kinds to put in the tree"
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
                        // Add applies through the one shared path: the instance lands
                        // configured, and the reprobe that the apply queues reports it.
                        addInstance(project, kind, dialog.instanceName(), dialog.endpointValue())
                    }
                }
            }.toTypedArray()
}

/**
 * The Add dialog: both fields start filled - the name from the default endpoint's host,
 * the endpoint at xmake's own daemon default - so accepting the dialog as it opened is
 * a valid, sensible add. Editing either stays one field away; a duplicate or a
 * malformed endpoint is rejected in the dialog, not after it.
 */
private class AddServiceInstanceDialog(
    project: Project,
    kind: XMakeServiceKind,
) : DialogWrapper(project) {

    private val treeState = XMakeServicesTreeState.getInstance(project)

    private val nameField = JBTextField(24)
    private val endpointField = JBTextField(24)

    init {
        title = "Add ${kindName(kind)}"
        setOKButtonText("Add")
        // The defaults ARE the form: the name derives from the endpoint's host, the
        // endpoint is the port xmake's own client.conf falls back to. A user who means
        // the local default just presses Add.
        val defaultEndpoint = kind.defaultEndpoint()
        nameField.text = defaultInstanceName(project, defaultEndpoint)
        endpointField.text = defaultEndpoint
        init()
    }

    override fun createCenterPanel(): JComponent = panel {
        row("Name:") {
            cell(nameField).align(AlignX.FILL)
        }
        row("Endpoint:") {
            cell(endpointField).align(AlignX.FILL)
        }
    }

    override fun getPreferredFocusedComponent(): JComponent = nameField

    override fun doValidate(): ValidationInfo? {
        val name = nameField.text.trim()
        val endpoint = endpointField.text.trim()
        return when {
            name.isEmpty() -> ValidationInfo("Name the instance", nameField)
            treeState.instances().any { it.name == name } ->
                ValidationInfo("\"$name\" is already in the tree", nameField)
            endpoint.isNotEmpty() && XMakeServiceEndpointFormat.parse(endpoint) == null ->
                ValidationInfo("The endpoint must read host:port", endpointField)
            else -> null
        }
    }

    fun instanceName(): String = nameField.text.trim()

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
    "Sync Project",
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
    "Pull from Daemon",
    "Pull a file or directory from the daemon into the project (xmake service --pull)",
    AllIcons.Actions.Download,
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
    "Start Daemon",
    "Start the daemon service (xmake service --start)",
    AllIcons.Actions.Execute,
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
    "Stop Daemon",
    "Stop the daemon service (xmake service --stop)",
    AllIcons.Actions.Suspend,
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
    "Restart Daemon",
    "Restart the daemon service (xmake service --restart)",
    AllIcons.Actions.Rerun,
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
