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

import com.intellij.icons.AllIcons
import com.intellij.openapi.project.Project
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.JBFont
import com.intellij.util.ui.JBUI
import io.xmake.utils.cli.XMakeServiceKind
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import javax.swing.Icon
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JPanel

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

/** The status icon of one service kind: green connected, yellow connected-but-server-
 *  down, red unreachable, gray idle/not configured. The colors carry the state the
 *  text spells out, so a Services tree scan answers "is my build server up?". */
internal fun serviceStatusIcon(state: XMakeServiceState, probed: Boolean): Icon = when {
    !probed || state.endpoint == null -> AllIcons.General.InspectionsOKEmpty
    state.connected && state.reachable != XMakeReachability.NO -> AllIcons.General.InspectionsOK
    state.connected && state.reachable == XMakeReachability.NO -> AllIcons.General.InspectionsWarning
    state.reachable == XMakeReachability.NO -> AllIcons.General.InspectionsError
    else -> AllIcons.General.InspectionsOKEmpty
}

/**
 * The editor and switchboard of one service kind, shared by both surfaces: the XMake
 * tool window's tab embeds one per kind, and the platform Services contributor shows
 * the selected node's instance in its detail pane.
 *
 * The panel is a plain view: it renders the state its owner pushes via [show], and
 * every button forwards to [XMakeServicesSnapshotService], which runs the real xmake
 * command on the executor lane and re-probes - the panel itself never touches the
 * toolkit, a socket, or a file.
 */
class XMakeServiceDetailPanel(private val project: Project, val kind: XMakeServiceKind) : JPanel(GridBagLayout()) {

    private val statusLabel = JBLabel()
    private val endpointField = JBTextField(18).apply { emptyText.text = "host:port" }
    private val applyButton = JButton("Apply")
    private val clearButton = JButton("Clear")
    private val connectButton = JButton("Connect").apply {
        toolTipText = "Connect through xmake service --connect, then re-probe the status"
    }
    private val disconnectButton = JButton("Disconnect").apply {
        toolTipText = "Disconnect through xmake service --disconnect, then re-probe the status"
    }
    private val hintLabel = JBLabel().apply { font = JBFont.label() }

    /** The last snapshot's state and the busy flag, so every control's enablement is
     *  recomputed from exactly what the probe allowed instead of guessed. */
    private var lastState: XMakeServiceState? = null
    private var busy = false

    init {
        isOpaque = false
        val kindLabel = JBLabel(kindName(kind)).apply { font = JBFont.label().biggerOn(2f).deriveFont(java.awt.Font.BOLD) }
        addRow(0, kindLabel)
        addRow(1, JBLabel("Status:"), statusLabel)
        addRow(2, JBLabel("Endpoint:"), endpointField, applyButton, clearButton)
        addRow(3, connectButton, disconnectButton)
        addRow(4, hintLabel)

        applyButton.addActionListener {
            project.xmakeServicesSnapshots.applyEndpoint(kind, endpointField.text)
        }
        clearButton.addActionListener {
            project.xmakeServicesSnapshots.clearEndpoint(kind)
        }
        connectButton.addActionListener {
            project.xmakeServicesSnapshots.connectService(kind)
        }
        disconnectButton.addActionListener {
            project.xmakeServicesSnapshots.disconnectService(kind)
        }
    }

    private fun addRow(row: Int, vararg components: JComponent) {
        val line = JPanel(GridBagLayout()).apply { isOpaque = false }
        components.forEachIndexed { index, component ->
            line.add(
                component,
                GridBagConstraints().apply {
                    gridx = index
                    anchor = GridBagConstraints.WEST
                    fill = if (component === endpointField) GridBagConstraints.HORIZONTAL else GridBagConstraints.NONE
                    weightx = if (component === endpointField) 1.0 else 0.0
                    insets = JBUI.insetsRight(6)
                },
            )
        }
        add(
            line,
            GridBagConstraints().apply {
                gridy = row
                gridx = 0
                weightx = 1.0
                anchor = GridBagConstraints.WEST
                fill = if (row == 0) GridBagConstraints.NONE else GridBagConstraints.HORIZONTAL
                insets = JBUI.insets(3, 0)
            },
        )
    }

    /** Renders one probe result. A focused endpoint field keeps the user's typing: the
     *  next probe may land while they edit. Owners render busy state right after this
     *  with [setBusy] - the last call wins, which is what the tab's render path does. */
    fun show(state: XMakeServiceState, probed: Boolean) {
        lastState = state
        statusLabel.text = serviceStatusText(state, probed)
        statusLabel.icon = serviceStatusIcon(state, probed)
        if (!endpointField.isFocusOwner) {
            endpointField.text = state.endpoint
                ?: project.getService(XMakeServiceEndpointsService::class.java).configuredEndpoint(kind)
                ?: ""
        }
        hintLabel.text = when {
            state.endpoint == null || state.tokenKnown -> ""
            else -> "No token is stored for this endpoint - connecting needs one (see Console)"
        }
        refreshEnabled()
    }

    /** The busy gate's share: while an operation runs, every control is off. Between
     *  probes the buttons follow the snapshot state only: connect needs an endpoint,
     *  disconnect a persisted connection, clear an entry to remove. */
    fun setBusy(busy: Boolean) {
        this.busy = busy
        refreshEnabled()
    }

    private fun refreshEnabled() {
        val state = lastState
        val configured = state?.endpoint != null ||
            project.getService(XMakeServiceEndpointsService::class.java).configuredEndpoint(kind) != null
        endpointField.isEnabled = !busy
        applyButton.isEnabled = !busy
        clearButton.isEnabled = !busy && configured
        connectButton.isEnabled = !busy && state?.endpoint != null
        disconnectButton.isEnabled = !busy && state?.connected == true
    }
}
