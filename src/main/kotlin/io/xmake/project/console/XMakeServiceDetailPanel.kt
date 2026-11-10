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
import com.intellij.ui.dsl.builder.Row
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
     *  full sentence reads as the subtitle under the title. */
    private val statusPill = JPanel(FlowLayout(FlowLayout.LEFT, 0, 0)).apply { isOpaque = false }

    /** The full status sentence under the title: what the pill's two words compress. */
    private val statusSentence = JBLabel().apply {
      foreground = com.intellij.util.ui.UIUtil.getLabelInfoForeground()
      setAllowAutoWrapping(true)
    }
    private val endpointField = JBTextField(18).apply { emptyText.text = "host:port" }
    private val applyButton = JButton("Apply")
    private val clearButton = JButton("Clear")
    private val connectButton = JButton("Connect")
    private val disconnectButton = JButton("Disconnect")
    private val reconnectButton = JButton("Reconnect")

    /** The optional `--session` name the next Connect rides: a stable session id the
     *  name derives, for a second connection to one daemon. Blank connects as usual;
     *  the value is a connect-time option and is not persisted. */
    private val sessionField = JBTextField(12).apply { emptyText.text = "optional" }

    /** Whether a token is stored for the endpoint - the connect precondition, as a
     *  fact on the page instead of a tooltip. */
    private val tokenLabel = JBLabel()

    /** The daemon verbs: server-side lifecycle, run through xmake like everything else. */
    private val startButton = JButton("Start")
    private val stopButton = JButton("Stop")
    private val restartButton = JButton("Restart")

    /** The file verbs: project sync to, and pulls from, the daemon; the xmake program
     *  sync; and the clean that sweeps synced files off the daemon. */
    private val syncButton = JButton("Sync Project")
    private val pullButton = JButton("Pull from Daemon")
    private val syncXmakeButton = JButton("Sync Xmake...")
    private val cleanButton = JButton("Clean Files...")

    /** The diagnostics verbs: the daemon's own logs and status view, printed into the
     *  XMake console - the panel's state comes from the probe, these are the tool's
     *  own words for cross-checking. */
    private val logsButton = JButton("Show Logs")
    private val statusButton = JButton("Raw Status")

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
    private var lastProbed = false
    private var busy = false

    /** The annotation rows; hidden as rows, not as labels - a hidden label inside a grid
     *  row still reserves the row's height, the panel's own version of an empty line. */
    private var connectionTargetRow: Row? = null
    private var hintRow: Row? = null

    /** The bus subscription, alive only while the panel sits on a component tree: the
     *  Services tree rebuilds its descriptors on every reset, and a retired panel must
     *  stop listening instead of keeping the connection alive. */
    private var busConnection: MessageBusConnection? = null

    init {
        isOpaque = false
        border = JBUI.Borders.empty(12, 16)
        // Rows live at the page's top, at their natural height: a BorderLayout NORTH
        // keeps the grid from stretching its rows over an otherwise empty pane.
        add(page(), BorderLayout.NORTH)

        applyButton.addActionListener { applyEndpointField() }
        clearButton.addActionListener {
            project.xmakeServicesSnapshots.clearEndpoint(instanceName, kind)
        }
        connectButton.addActionListener {
            // The instance's own record rides the connect: two instances of one kind
            // connect to the addresses their nodes name, not to whichever was applied
            // last. The session field rides along when the user named one.
            project.xmakeServicesSnapshots.connectService(
                kind,
                project.getService(XMakeServiceEndpointsService::class.java).configuredEndpoint(instanceName),
                sessionField.text.trim().takeUnless { it.isEmpty() },
            )
        }
        disconnectButton.addActionListener {
            project.xmakeServicesSnapshots.disconnectService(kind)
        }
        reconnectButton.addActionListener {
            project.xmakeServicesSnapshots.reconnectService(kind)
        }
        startButton.addActionListener { project.xmakeServicesSnapshots.startDaemon(kind) }
        stopButton.addActionListener { project.xmakeServicesSnapshots.stopDaemon(kind) }
        restartButton.addActionListener { project.xmakeServicesSnapshots.restartDaemon(kind) }
        syncButton.addActionListener { project.xmakeServicesSnapshots.syncProject(kind) }
        pullButton.addActionListener {
            val path = com.intellij.openapi.ui.Messages.showInputDialog(
                project,
                "Pull which file or directory from the daemon?",
                "Pull from ${kindName(kind)}",
                null,
            )?.trim().takeUnless { it.isNullOrEmpty() } ?: return@addActionListener
            project.xmakeServicesSnapshots.pullFrom(kind, path)
        }
        syncXmakeButton.addActionListener {
            val path = com.intellij.openapi.ui.Messages.showInputDialog(
                project,
                "Sync which xmake program directory to the daemon?",
                "Sync Xmake Sources to ${kindName(kind)}",
                null,
            )?.trim().takeUnless { it.isNullOrEmpty() } ?: return@addActionListener
            project.xmakeServicesSnapshots.syncXmakeSources(kind, path)
        }
        cleanButton.addActionListener {
            // The sweep deletes files on the daemon's host, so the scope is chosen and
            // confirmed here - the command itself only runs what was confirmed.
            val scope = com.intellij.openapi.ui.Messages.showChooseDialog(
                project,
                "Which files should the ${kindName(kind)} daemon forget?",
                "Clean Files",
                com.intellij.util.ui.UIUtil.getQuestionIcon(),
                arrayOf("This project's files", "Every project's files"),
                "This project's files",
            )
            if (scope < 0) return@addActionListener
            val all = scope == 1
            val answer = com.intellij.openapi.ui.Messages.showYesNoDialog(
                project,
                if (all) {
                    "Removes every project's synced files from the ${kindName(kind)} daemon. " +
                        "The files stay on the machines that synced them."
                } else {
                    "Removes this project's synced files from the ${kindName(kind)} daemon. " +
                        "The files stay on this machine."
                },
                "Clean Files",
                com.intellij.util.ui.UIUtil.getWarningIcon(),
            )
            if (answer != com.intellij.openapi.ui.Messages.YES) return@addActionListener
            project.xmakeServicesSnapshots.cleanFiles(kind, all)
        }
        logsButton.addActionListener { project.xmakeServicesSnapshots.showLogs(kind) }
        statusButton.addActionListener { project.xmakeServicesSnapshots.showStatus(kind) }
        // Enter in the field applies - the field is the primary input, the Apply button
        // stays for mouse users.
        endpointField.addActionListener { applyEndpointField() }
        // Editing the field retires the red outline a refused apply left behind: the
        // next apply is judged on its own text.
        endpointField.document.addDocumentListener(object : DocumentListener {
            override fun insertUpdate(event: DocumentEvent) = clearEndpointError()
            override fun removeUpdate(event: DocumentEvent) = clearEndpointError()
            override fun changedUpdate(event: DocumentEvent) = clearEndpointError()

            private fun clearEndpointError() {
                endpointField.putClientProperty("JComponent.outline", null)
            }
        })
        // Buttons start in their gated state; the first show() lands right after.
        refreshEnabled()
    }

    /** The page's one grid: the title with its pill and the full status under it, the
     *  endpoint editor, the connection verbs, the token fact, the daemon and file
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
        row {
            cell(statusSentence).align(AlignX.FILL)
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
            cell(reconnectButton)
        }
        row("Session") {
            cell(sessionField).apply {
                component.toolTipText =
                    "Optional --session name for the next Connect: the name derives a stable session id, " +
                        "so a second connection to one daemon does not reuse the first one's session"
            }
        }
        row("Token") {
            cell(tokenLabel)
        }
        row("Daemon") {
            cell(startButton)
            cell(stopButton)
            cell(restartButton)
        }
        row("Files") {
            cell(syncButton)
            cell(pullButton)
            cell(syncXmakeButton)
            cell(cleanButton)
        }
        row("Diagnostics") {
            cell(logsButton)
            cell(statusButton)
        }
        connectionTargetRow = row {
            cell(connectionTargetLabel).align(AlignX.FILL)
        }
        hintRow = row {
            cell(hintLabel).align(AlignX.FILL)
        }
    }

    /** Renders one probe result. A focused endpoint field keeps the user's typing: the
     *  next probe may land while they edit. Owners render busy state right after this
     *  with [setBusy] - the last call wins, which is what the tab's render path does. */
    fun show(state: XMakeServiceState, probed: Boolean) {
        lastState = state
        lastProbed = probed
        val word = state.shortStatus(probed)
        statusPill.removeAll()
        statusPill.add(Badge(word, serviceTone(state, probed)))
        statusPill.toolTipText = serviceStatusText(state, probed)
        setWrappingText(statusSentence, serviceStatusText(state, probed))
        val ownEndpoint = project.getService(XMakeServiceEndpointsService::class.java)
            .configuredEndpoint(instanceName)
        if (!endpointField.isFocusOwner) {
            endpointField.text = ownEndpoint ?: state.endpoint ?: ""
        }
        renderToken(state)
        renderHint(state)
        renderConnectionTarget(ownEndpoint)
        refreshEnabled()
    }

    /** The token fact: whether the endpoint on record has a stored token - the one
     *  precondition Connect cannot satisfy from the IDE, so the page says where it
     *  stands instead of leaving the reader to hit the balloon. */
    private fun renderToken(state: XMakeServiceState) {
        if (state.endpoint == null) {
            tokenLabel.text = "-"
            tokenLabel.toolTipText = null
            return
        }
        if (state.tokenKnown) {
            tokenLabel.text = "stored for ${state.endpoint}"
            tokenLabel.toolTipText = null
        } else {
            tokenLabel.text = "not stored"
            tokenLabel.toolTipText =
                "Run `xmake service --connect --${kind.flag}` once in a terminal to store it " +
                    "(server-side setup: `xmake service --add-user`, `xmake service --gen-token`)"
        }
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
        hintRow?.visible(text.isNotEmpty())
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
        connectionTargetRow?.visible(text.isNotEmpty())
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
        connectButton.isEnabled = !busy && lastProbed && state?.endpoint != null && state.connected != true
        connectButton.toolTipText = when {
            busy -> "Probing the build cache and service status..."
            !lastProbed -> "Probe the services first - press Refresh in the toolbar"
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
        reconnectButton.isEnabled = !busy && lastProbed
        reconnectButton.toolTipText = when {
            busy -> "Probing the build cache and service status..."
            !lastProbed -> "Probe the services first - press Refresh in the toolbar"
            else -> "Reconnect this service through xmake service --reconnect"
        }
        // The daemon, file, and diagnostics verbs run real xmake commands; their
        // outcome is the command's own error, so the gate is only the busy lane - the
        // same rule the node toolbar's actions apply.
        startButton.isEnabled = !busy
        stopButton.isEnabled = !busy
        restartButton.isEnabled = !busy
        syncButton.isEnabled = !busy
        pullButton.isEnabled = !busy
        syncXmakeButton.isEnabled = !busy
        cleanButton.isEnabled = !busy
        logsButton.isEnabled = !busy
        statusButton.isEnabled = !busy
        sessionField.isEnabled = !busy
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
