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
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.application.EDT
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.ui.SimpleToolWindowPanel
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.JBFont
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import io.xmake.run.command.XMakeCommandFactory
import io.xmake.run.command.xmakeExecutor
import io.xmake.run.command.xmakeCommands
import io.xmake.run.target.activeOrSingleXMakeBuildProfile
import io.xmake.utils.cli.XMakeServiceKind
import io.xmake.utils.cli.XMakeServiceVerb
import io.xmake.utils.notify
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.awt.BorderLayout
import java.awt.Font
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.JButton
import javax.swing.JCheckBox
import javax.swing.JComponent
import javax.swing.JPanel

/**
 * The "Services" content of the XMake tool window: the build cache state (the persisted
 * `ccache` option and the program xmake actually resolves) plus one row per `xmake service`
 * kind, fed by snapshot probes only. The probe runs through the project's XMakeExecutor on the
 * toolkit's own host (a bundled script, see probeservices.lua), and every state-changing
 * action runs a real xmake command on the same lane with its output in the Output console -
 * the panel itself never touches the toolkit, a socket, or a file.
 *
 * There is no resident listener: the snapshot refreshes when the tab is activated, on
 * Refresh, and after any toggle/connect action, so nothing probes while another tab is up.
 */
class XMakeServicesPanel(private val project: Project) : SimpleToolWindowPanel(true), Disposable {

    private var snapshot: XMakeServicesSnapshot? = null

    /** True while [applySnapshot] moves the checkbox, so the user's listener ignores it. */
    private var applyingCheckboxState = false

    /**
     * True while a probe or a toggle/connect sits on the executor lane or runs against it.
     * The panel disables its controls then - the lane serializes against builds, so a
     * click during the wait would queue a second command to run after the first, and
     * nothing else tells the user the wait is on. Cleared by the EDT hop that lands the
     * result or the failure.
     */
    @Volatile
    private var probing = false

    private val messageLabel = JBLabel("Activate this tab or press Refresh to probe the build cache and service status")

    private val cacheCheckBox = JCheckBox("ccache (--ccache)")
    private val cacheToolLabel = JBLabel()

    private val serviceRows: Map<XMakeServiceKind, ServiceRow> =
        XMakeServiceKind.entries.associateWith(::ServiceRow)

    init {
        val toolbar = ActionManager.getInstance().createActionToolbar(
            "XMake Services Toolbar",
            DefaultActionGroup(
                object : DumbAwareAction("Refresh", "Re-probe the build cache and service status", AllIcons.Actions.Refresh) {
                    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT
                    override fun update(event: AnActionEvent) {
                        event.presentation.isEnabled = !probing
                    }
                    override fun actionPerformed(event: AnActionEvent) {
                        refresh()
                    }
                },
            ),
            true,
        ).apply { targetComponent = this@XMakeServicesPanel }
        setToolbar(toolbar.component)
        layout = BorderLayout()
        setContent(JBScrollPane(buildSections()))
        cacheCheckBox.addActionListener {
            if (!applyingCheckboxState) {
                toggleCcache(cacheCheckBox.isSelected)
            }
        }
    }

    /** One probe, submitted on the executor lane; the result reaches Swing in one EDT hop.
     *  Re-entrant calls are dropped: the tab's activation listener and this action both
     *  land here, and one probe in flight is one probe the lane needs. */
    fun refresh() {
        runThenReprobe { _, _ ->
            // nothing to run first: this is the probe only
        }
    }

    private fun buildSections(): JComponent {
        val sections = JPanel(GridBagLayout())
        var gridy = 0
        fun addRow(component: JComponent) {
            sections.add(
                component,
                GridBagConstraints().apply {
                    gridx = 0
                    gridy = gridy++
                    weightx = 1.0
                    fill = GridBagConstraints.HORIZONTAL
                    anchor = GridBagConstraints.WEST
                    insets = JBUI.insetsLeft(12)
                },
            )
        }

        addRow(sectionHeader("Build cache"))
        addRow(
            row(cacheCheckBox, JBLabel("Project option - toggling reconfigures the project")),
        )
        addRow(row(JBLabel("Effective:"), cacheToolLabel))
        // TODO: cache statistics (hit rate, cache size) from `ccache --print-stats` /
        // `sccache --print-stats` have no row yet: the probe cannot verify their output
        // formats without a cache tool installed, so the panel ships without them
        // (docs/roadmap-designs/build-services.md section 2).

        addRow(sectionHeader("XMake services"))
        for (kind in XMakeServiceKind.entries) {
            addRow(row(serviceRows.getValue(kind).panel))
        }
        addRow(messageLabel)
        return sections
    }

    private fun sectionHeader(text: String): JComponent =
        JBLabel(text).apply {
            font = JBFont.label().biggerOn(2f).deriveFont(Font.BOLD)
        }

    private fun row(vararg components: JComponent): JComponent =
        JPanel().apply {
            layout = BoxLayout(this, BoxLayout.X_AXIS)
            isOpaque = false
            components.forEachIndexed { index, component ->
                if (index > 0) {
                    add(Box.createHorizontalStrut(JBUI.scale(12)))
                }
                add(component)
            }
            alignmentX = LEFT_ALIGNMENT
        }

    /** Toggles the persisted ccache option with `xmake f --ccache=y|n` after a confirmation:
     *  the configure pass re-runs, and with a cache tool in use the next build rebuilds
     *  affected targets. */
    private fun toggleCcache(enable: Boolean) {
        if (project.activeOrSingleXMakeBuildProfile == null) {
            setMessage("Select an XMake build profile first!")
            showCheckboxState(!enable)
            return
        }
        if (!confirmCcacheToggle(enable)) {
            showCheckboxState(!enable)
            return
        }
        runThenReprobe { console, factory ->
            project.xmakeExecutor.execute(console, factory.createCcacheToggle(enable))
        }
    }

    private fun confirmCcacheToggle(enable: Boolean): Boolean =
        Messages.showYesNoDialog(
            project,
            "Toggling ccache runs xmake f --ccache=${if (enable) "y" else "n"}, which reconfigures the project. " +
                "With a cache tool in use the next build rebuilds affected targets. Continue?",
            "Toggle Ccache",
            Messages.getQuestionIcon(),
        ) == Messages.YES

    /** Connects one service kind. The one-time connect of a user-protected server prompts
     *  for a password on stdin, which the command channel cannot answer - so a connect
     *  without a stored token is explained instead of silently hanging the lane. */
    private fun connectService(kind: XMakeServiceKind) {
        val state = snapshot?.services?.get(kind) ?: return
        val endpoint = state.endpoint ?: return
        if (!state.tokenKnown) {
            project.notify(
                "No token is stored for $endpoint, so connecting would ask for a password on stdin. " +
                    "Run `xmake service --connect --${kind.flag}` in a terminal once to store it " +
                    "(server-side setup: `xmake service --add-user`, `xmake service --gen-token`).",
                title = "XMake service needs a token",
            )
            return
        }
        runThenReprobe { console, factory ->
            project.xmakeExecutor.execute(console, factory.createServiceAction(XMakeServiceVerb.CONNECT, kind))
        }
    }

    private fun disconnectService(kind: XMakeServiceKind) {
        runThenReprobe { console, factory ->
            project.xmakeExecutor.execute(console, factory.createServiceAction(XMakeServiceVerb.DISCONNECT, kind))
        }
    }

    /** Runs [operation] on the executor lane and re-probes the snapshot afterwards, so the
     *  panel shows what xmake persisted, not what the command exit code suggests. Failures
     *  land in the message label and keep the last good snapshot.
     *
     *  The controls disable for the whole wait - including the lane queue ahead of this
     *  task, which a running build occupies - and re-enable when the result or the failure
     *  lands. A call while one is in flight is dropped: it would only stack a second
     *  command behind the first. */
    private fun runThenReprobe(operation: suspend (console: XMakeConsole, factory: XMakeCommandFactory) -> Unit) {
        val profile = project.activeOrSingleXMakeBuildProfile
        if (profile == null) {
            setMessage("Select an XMake build profile first!")
            return
        }
        if (probing) return
        probing = true
        setControlsEnabled(false)
        setMessage(
            if (project.xmakeExecutor.laneBusy) {
                "Waiting for the running build or command to finish, then probing the build cache and service status..."
            } else {
                "Probing the build cache and service status..."
            },
        )
        @Suppress("DeferredResultUnused") // fire-and-forget: the panel's state narrates progress
        project.xmakeExecutor.submit {
            try {
                val console = XMakeConsoleManager.getInstance(project).awaitReady()
                val factory = project.xmakeCommands(profile)
                operation(console, factory)
                val output = project.xmakeExecutor.captureStandardOutput(factory.createBuildServicesProbe())
                val probed = XMakeServicesProbeParser.parse(output)
                withContext(Dispatchers.EDT) {
                    probing = false
                    setControlsEnabled(true)
                    if (probed == null) {
                        messageLabel.text = "The services probe produced no data (xmake ${factory.toolkit.name})."
                    } else {
                        applySnapshot(probed)
                        messageLabel.text = "Probed at the last refresh; press Refresh to update."
                    }
                }
            } catch (cancellation: CancellationException) {
                // The project is going away with the probe still queued; the flag is all
                // the state that must not stay stuck.
                probing = false
                throw cancellation
            } catch (error: Exception) {
                reportFailure(error)
            }
        }
    }

    /** The busy gate over every clickable the panel owns: the cache checkbox, each service
     *  row's buttons, and the toolbar's Refresh action read the same [probing] flag. */
    private fun setControlsEnabled(enabled: Boolean) {
        cacheCheckBox.isEnabled = enabled
        serviceRows.values.forEach { row -> row.setBusy(!enabled) }
    }

    private fun applySnapshot(newSnapshot: XMakeServicesSnapshot) {
        snapshot = newSnapshot
        showCheckboxState(newSnapshot.cache.enabled)
        cacheToolLabel.text = when {
            newSnapshot.cache.program != null -> newSnapshot.cache.program
            else -> "No cache tool found - builds run uncached"
        }
        for ((kind, state) in newSnapshot.services) {
            serviceRows.getValue(kind).show(state)
        }
    }

    private fun showCheckboxState(enabled: Boolean) {
        applyingCheckboxState = true
        cacheCheckBox.isSelected = enabled
        applyingCheckboxState = false
    }

    private suspend fun reportFailure(error: Exception) {
        Log.warn("XMake services probe failed", error)
        withContext(Dispatchers.EDT) {
            probing = false
            setControlsEnabled(true)
            messageLabel.text = "XMake services probe failed: ${error.message}"
            // a failed toggle/connect must not leave the panel describing a state xmake refused
            snapshot?.let { applySnapshot(it) }
        }
    }

    private fun setMessage(message: String) {
        messageLabel.text = message
    }

    private fun statusText(state: XMakeServiceState): String = when {
        state.endpoint == null -> "not configured"
        state.connected && state.reachable == XMakeReachability.YES -> "connected / reachable"
        state.connected && state.reachable == XMakeReachability.NO -> "connected (persisted) / unreachable now"
        state.connected -> "connected (persisted)"
        state.reachable == XMakeReachability.YES -> "not connected / server reachable"
        else -> "not connected"
    }

    private fun kindName(kind: XMakeServiceKind): String = when (kind) {
        XMakeServiceKind.REMOTE_BUILD -> "Remote build"
        XMakeServiceKind.DISTCC_BUILD -> "Distcc"
        XMakeServiceKind.REMOTE_CACHE -> "Remote cache"
    }

    private inner class ServiceRow(private val kind: XMakeServiceKind) {

        private val endpointLabel = JBLabel()
        private val statusLabel = JBLabel()
        private val connectButton = JButton("Connect").apply {
            addActionListener { connectService(kind) }
        }
        private val disconnectButton = JButton("Disconnect").apply {
            addActionListener { disconnectService(kind) }
        }

        /** The last snapshot's state, so the busy gate can re-enable exactly what the
         *  snapshot allows instead of guessing. */
        private var state: XMakeServiceState? = null

        /** The busy gate's share: while an operation runs, every button is off. */
        private var busy = false

        val panel: JComponent = row(
            JBLabel(kindName(kind)),
            endpointLabel,
            statusLabel,
            connectButton,
            disconnectButton,
        )

        fun show(state: XMakeServiceState) {
            this.state = state
            endpointLabel.text = state.endpoint ?: "-"
            statusLabel.text = statusText(state)
            statusLabel.foreground = if (state.connected && state.reachable == XMakeReachability.NO) {
                JBColor.RED
            } else {
                UIUtil.getLabelForeground()
            }
            refreshButtons()
        }

        fun setBusy(busy: Boolean) {
            this.busy = busy
            refreshButtons()
        }

        private fun refreshButtons() {
            val state = this.state
            connectButton.isEnabled = !busy && state?.endpoint != null
            disconnectButton.isEnabled = !busy && state?.connected == true
        }
    }

    override fun dispose() {
    }

    private companion object {
        private val Log = logger<XMakeServicesPanel>()
    }
}
