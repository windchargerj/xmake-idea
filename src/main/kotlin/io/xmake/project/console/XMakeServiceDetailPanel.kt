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

import com.intellij.icons.AllIcons
import com.intellij.openapi.Disposable
import com.intellij.openapi.components.serviceOrNull
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.text.StringUtil
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBTextField
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.panel
import com.intellij.util.messages.MessageBusConnection
import com.intellij.util.ui.JBFont
import com.intellij.util.ui.JBUI
import io.xmake.run.target.SELECT_BUILD_PROFILE_FIRST
import io.xmake.utils.cli.XMakeServiceKind
import io.xmake.utils.ui.Badge
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.Font
import javax.swing.Icon
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.event.DocumentEvent
import javax.swing.event.DocumentListener

/** The human name of one service kind, shared by the tree nodes and the detail rows. */
internal fun kindName(kind: XMakeServiceKind): String = when (kind) {
    XMakeServiceKind.REMOTE_BUILD -> "Remote build"
    XMakeServiceKind.DISTCC_BUILD -> "Distcc"
    XMakeServiceKind.REMOTE_CACHE -> "Remote cache"
}

/** The status line of one service kind, worded exactly like the pre-contributor rows:
 *  what the client persists and what the fresh reachability check says apart. */
internal fun serviceStatusText(state: XMakeServiceState, probed: Boolean): String = when {
    !probed -> "probing..."
    state.endpoint == null -> "not configured"
    state.connected && state.reachable == XMakeReachability.YES -> "connected / reachable"
    state.connected && state.reachable == XMakeReachability.NO -> "connected (persisted) / unreachable now"
    state.connected -> "connected (persisted)"
    state.reachable == XMakeReachability.YES -> "not connected / server reachable"
    else -> "not connected"
}

/** The short state words the summary line shows: two words at most - the full
 *  sentence (what is persisted vs what is live) rides the tooltip. */
internal fun XMakeServiceState.shortStatus(probed: Boolean): String = when {
    !probed -> "probing"
    endpoint == null -> "not configured"
    connected && reachable == XMakeReachability.YES -> "connected"
    connected && reachable == XMakeReachability.NO -> "unreachable"
    connected -> "connected"
    reachable == XMakeReachability.YES -> "server reachable"
    else -> "not connected"
}

/** The state's tone for the detail pill and the tree icon alike: green connected,
 *  yellow connected-but-server-down, red reachable-and-refused, gray everything else. */
internal fun serviceTone(state: XMakeServiceState, probed: Boolean): java.awt.Color = when {
    !probed || state.endpoint == null -> com.intellij.util.ui.UIUtil.getLabelInfoForeground()
    state.connected && state.reachable != XMakeReachability.NO -> JBColor(0x3F7E33, 0x57965C)
    state.connected -> JBColor(0x8A6A00, 0xC9A34C)
    state.reachable == XMakeReachability.NO -> JBColor(0xC0392B, 0xCF6A5E)
    else -> com.intellij.util.ui.UIUtil.getLabelInfoForeground()
}

internal fun serviceStatusIcon(state: XMakeServiceState, probed: Boolean): Icon = when {
    !probed || state.endpoint == null -> AllIcons.General.InspectionsOKEmpty
    state.connected && state.reachable != XMakeReachability.NO -> AllIcons.General.InspectionsOK
    state.connected && state.reachable == XMakeReachability.NO -> AllIcons.General.InspectionsWarning
    state.reachable == XMakeReachability.NO -> AllIcons.General.InspectionsError
    else -> AllIcons.General.InspectionsOKEmpty
}

/**
 * The editor and switchboard of one service instance, shared by both surfaces: the XMake
 * tool window's tab embeds one per kind, and the platform Services contributor shows
 * the selected node's instance in its detail pane.
 *
 * The page is built on the platform's own DSL, the same grid the settings page uses:
 * the title row carries the instance's name and its status pill at the far edge, and
 * the Endpoint and Connection rows share one label column, so fields and verbs line up
 * down the page instead of each row starting wherever its caption ended. Long
 * annotations wrap to the pane's width rather than clipping at its edge.
 *
 * The panel is a plain view: it renders the state its owner pushes via [show], re-renders
 * itself on every [XMakeServicesSnapshotService.TOPIC] event so an action's result is
 * visible where the action happened, and every button forwards to
 * [XMakeServicesSnapshotService], which runs the real xmake command on the executor lane
 * and re-probes - the panel itself never touches the toolkit, a socket, or a file.
 */
class XMakeServiceDetailPanel(
    private val project: Project,
    val kind: XMakeServiceKind,
    /** The instance this panel edits; its endpoint record is keyed by this name. */
    val instanceName: String = kind.name,
) : JPanel(BorderLayout()), Disposable {

    /** The state as a pill - the same badge language the packages page speaks, so one
     *  glance answers "is my build server up" in either panel. Rebuilt per render; the
     *  full sentence rides the tooltip. */
    private val statusPill = JPanel(FlowLayout(FlowLayout.LEFT, 0, 0)).apply { isOpaque = false }
    private val endpointField = JBTextField(18).apply { emptyText.text = "host:port" }
    private val applyButton = JButton("Apply")
    private val clearButton = JButton("Clear")
    private val connectButton = JButton("Connect")
    private val disconnectButton = JButton("Disconnect")

    /** The kind-level connection the Connect/Disconnect verbs actually act on, spelled
     *  out beside them when it is not what this panel's field shows. */
    private val connectionTargetLabel = JBLabel().apply {
        foreground = com.intellij.util.ui.UIUtil.getLabelInfoForeground()
        setAllowAutoWrapping(true)
    }
    private val hintLabel = JBLabel().apply {
        foreground = com.intellij.util.ui.UIUtil.getLabelInfoForeground()
        setAllowAutoWrapping(true)
    }

    /** The last snapshot's state and the busy flag, so every control's enablement is
     *  recomputed from exactly what the probe allowed instead of guessed. */
    private var lastState: XMakeServiceState? = null
    private var busy = false

    /** The bus subscription, alive only while the panel sits on a component tree: the
     *  Services tree rebuilds its descriptors on every reset, and a retired panel must
     *  stop listening instead of keeping the connection alive. */
    private var busConnection: MessageBusConnection? = null

    init {
        isOpaque = false
        border = JBUI.Borders.empty(12, 16, 12, 16)
        // Rows live at the page's top, at their natural height: a BorderLayout NORTH
        // keeps the grid from stretching its rows over an otherwise empty pane.
        add(page(), BorderLayout.NORTH)
    }

    /** The page's one grid: the title with its pill, the endpoint editor, the connection
     *  verbs, and the two annotation rows. */
    private fun page(): JComponent = panel {
        row {
            cell(
                JBLabel("${kindName(kind)} - $instanceName").apply {
                    font = JBFont.label().biggerOn(2f).deriveFont(Font.BOLD)
                },
            ).align(AlignX.FILL)
            cell(statusPill)
        }
        row("Endpoint") {
            cell(endpointField).align(AlignX.FILL).apply {
                component.preferredSize = Dimension(JBUI.scale(260), component.preferredSize.height)
                // Narrow details panes shrink the field instead of clipping the row's
                // buttons away.
                component.minimumSize = Dimension(JBUI.scale(120), component.minimumSize.height)
            }
            cell(applyButton)
            cell(clearButton)
        }
        row("Connection") {
            cell(connectButton)
            cell(disconnectButton)
        }
        row {
            cell(connectionTargetLabel).align(AlignX.FILL)
        }
        row {
            cell(hintLabel).align(AlignX.FILL)
        }
    }

    /** Renders one probe result. A focused endpoint field keeps the user's typing: the
     *  next probe may land while they edit. Owners render busy state right after this
     *  with [setBusy] - the last call wins, which is what the tab's render path does. */
    fun show(state: XMakeServiceState, probed: Boolean) {
        lastState = state
        val word = state.shortStatus(probed)
        statusPill.removeAll()
        statusPill.add(Badge(word, serviceTone(state, probed)))
        statusPill.toolTipText = serviceStatusText(state, probed)
        val ownEndpoint = project.getService(XMakeServiceEndpointsService::class.java)
            .configuredEndpoint(instanceName)
        if (!endpointField.isFocusOwner) {
            endpointField.text = ownEndpoint ?: state.endpoint ?: ""
        }
        renderHint(state)
        renderConnectionTarget(ownEndpoint)
        refreshEnabled()
    }

    /** The busy gate's share: while an operation runs, every control is off. Between
     *  probes the buttons follow the snapshot state only: connect needs an endpoint,
     *  disconnect a persisted connection, clear an entry to remove. */
    fun setBusy(busy: Boolean) {
        this.busy = busy
        refreshEnabled()
    }

    /** The render path every snapshot event reruns: the same state the contributor's
     *  tree node computes (the kind's probe answer with this instance's own endpoint
     *  where one is recorded), shown and busy-gated from the freshest snapshot. */
    private fun renderFromSnapshot() {
        if (project.isDisposed) return
        val snapshots = project.serviceOrNull<XMakeServicesSnapshotService>()
        val snapshot = snapshots?.snapshot
        val kindState = snapshot?.services?.get(kind) ?: kind.unprobedState()
        val state = project.getService(XMakeServiceEndpointsService::class.java)
            .configuredEndpoint(instanceName)
            ?.let { kindState.copy(endpoint = it) }
            ?: kindState
        show(state, snapshot != null)
        setBusy(snapshots?.probing ?: false)
    }

    /** The hint row, loudest voice first: the last operation's failure in the error
     *  red, a refused apply with the field outlined, the token nudge. The probe's
     *  progress and "probed" narration stay off the panel - the summary line carries
     *  the state and the root overview the running status message. */
    private fun renderHint(state: XMakeServiceState) {
        val snapshots = project.serviceOrNull<XMakeServicesSnapshotService>()
        val message = snapshots?.statusMessage
        val failed = snapshots?.statusIsFailure == true
        // A refused apply is a failed operation like a failed probe: the red line rule
        // the status message's failure flag spells out.
        val refused = message == XMakeServicesSnapshotService.ENDPOINT_INVALID_MESSAGE ||
            message == SELECT_BUILD_PROFILE_FIRST
        val text = when {
            failed || refused -> message ?: ""
            state.endpoint == null -> "Set an endpoint first - connecting needs one"
            state.tokenKnown -> ""
            else -> "No token is stored for this endpoint - run `xmake service --connect --${kind.flag}` once in a terminal"
        }
        setWrappingText(hintLabel, text)
        hintLabel.foreground = if (failed || refused) {
            JBColor.RED
        } else {
            com.intellij.util.ui.UIUtil.getLabelInfoForeground()
        }
        hintLabel.parent?.isVisible = text.isNotEmpty()
        endpointField.putClientProperty(
            "JComponent.outline",
            if (message == XMakeServicesSnapshotService.ENDPOINT_INVALID_MESSAGE) "error" else null,
        )
    }

    /** The kind-level connection the verbs act on: the snapshot's record for the kind,
     *  which every instance of the kind shares. The row annotates the divergence only -
     *  connect re-points that record at this instance's endpoint before connecting, and
     *  disconnect ends whatever connection the kind holds. */
    private fun renderConnectionTarget(ownEndpoint: String?) {
        val kindEndpoint = project.serviceOrNull<XMakeServicesSnapshotService>()
            ?.snapshot?.services?.get(kind)?.endpoint
        val text = when {
            ownEndpoint == null || kindEndpoint == null || kindEndpoint == ownEndpoint -> ""
            else ->
                "Connect points the shared ${kindName(kind)} connection at this instance's $ownEndpoint first; " +
                    "Disconnect ends the connection it holds ($kindEndpoint)"
        }
        setWrappingText(connectionTargetLabel, text)
        connectionTargetLabel.parent?.isVisible = text.isNotEmpty()
    }

    /** The apply verb sets what the field holds: a blank field would silently clear
     *  the configured endpoint, so blank applies do nothing - clearing is the Clear
     *  button's own explicit verb. */
    private fun applyEndpointField() {
        if (endpointField.text.isBlank()) return
        project.xmakeServicesSnapshots.applyEndpoint(instanceName, kind, endpointField.text)
    }

    private fun refreshEnabled() {
        val state = lastState
        // Clear answers for a record of this instance's own only: the snapshot service
        // drops a clear with no record, so lighting the button on the kind-level answer
        // would make the click a silent nothing.
        val clearable = project.getService(XMakeServiceEndpointsService::class.java)
            .configuredEndpoint(instanceName) != null
        endpointField.isEnabled = !busy
        applyButton.isEnabled = !busy
        clearButton.isEnabled = !busy && clearable
        // The verbs stay on the page and say why they are off, the way the tree node's
        // toolbar actions do: a button that vanishes leaves the row jumping and the
        // reason unspoken.
        connectButton.isEnabled = !busy && state?.endpoint != null && state?.connected != true
        connectButton.toolTipText = when {
            busy -> "Probing the build cache and service status..."
            state == null -> "The status is unknown until a probe runs"
            state.connected -> "Connected - disconnect first"
            state.endpoint == null -> "Configure an endpoint before connecting"
            else -> "Connect this service instance through xmake service"
        }
        disconnectButton.isEnabled = !busy && state?.connected == true
        disconnectButton.toolTipText = when {
            busy -> "Probing the build cache and service status..."
            state == null -> "The status is unknown until a probe runs"
            state.connected -> "Disconnect this service instance through xmake service"
            else -> "Not connected"
        }
    }

    override fun addNotify() {
        super.addNotify()
        if (busConnection != null || project.isDisposed) return
        val connection = project.messageBus.connect(this)
        connection.subscribe(
            XMakeServicesSnapshotService.TOPIC,
            XMakeServicesSnapshotService.Listener { renderFromSnapshot() },
        )
        busConnection = connection
        // Events may have passed by while the panel was off the tree: arrive current.
        renderFromSnapshot()
    }

    override fun removeNotify() {
        busConnection?.dispose()
        busConnection = null
        super.removeNotify()
    }

    override fun dispose() {}
}

/**
 * Writes [text] into a wrapping label: HTML makes the platform measure the words at the
 * pane's width instead of clipping the tail, and the label keeps the plain text as its
 * tooltip so a fully wrapped caption still reads in one line.
 */
internal fun setWrappingText(label: JBLabel, text: String) {
    label.text = if (text.isEmpty()) "" else "<html>${StringUtil.escapeXmlEntities(text)}</html>"
    label.toolTipText = text.ifEmpty { null }
}
