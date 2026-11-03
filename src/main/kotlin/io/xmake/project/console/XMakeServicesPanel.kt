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
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.components.serviceOrNull
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
import io.xmake.run.command.xmakeExecutor
import io.xmake.run.target.SELECT_BUILD_PROFILE_FIRST
import io.xmake.run.target.activeOrSingleXMakeBuildProfile
import io.xmake.utils.cli.XMakeServiceKind
import java.awt.BorderLayout
import java.awt.Component
import java.awt.Font
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.JCheckBox
import javax.swing.JComponent
import javax.swing.JPanel

/**
 * The "Services" content of the XMake tool window: the build cache state (the persisted
 * `ccache` option and the program xmake actually resolves) plus one editable, status-
 * iconized row per `xmake service` kind - the same [XMakeServiceDetailPanel] the
 * platform Services contributor shows in its detail pane, fed by the same
 * [XMakeServicesSnapshotService]. The panel itself never touches the toolkit, a
 * socket, or a file: probing and state changes run on the project's executor lane
 * with their output in the Console tab.
 *
 * There is no resident listener: the snapshot refreshes when the tab is activated
 * (the factory wires that), when the platform Services window shows, and after any
 * action - so nothing probes while both surfaces are hidden.
 */
class XMakeServicesPanel(private val project: Project) : SimpleToolWindowPanel(true), Disposable {

    private val snapshots get() = project.xmakeServicesSnapshots

    /** True while [applyCheckboxState] moves the checkbox, so the user's listener ignores it. */
    private var applyingCheckboxState = false

    private val messageLabel = JBLabel(INITIAL_MESSAGE)

    private val cacheCheckBox = JCheckBox("ccache (--ccache)")
    private val cacheToolLabel = JBLabel()

    private val serviceRows: Map<XMakeServiceKind, XMakeServiceDetailPanel> =
        XMakeServiceKind.entries.associateWith { XMakeServiceDetailPanel(project, it) }

    init {
        val toolbar = ActionManager.getInstance().createActionToolbar(
            "XMakeServicesView",
            DefaultActionGroup(
                object : DumbAwareAction("Refresh", "Re-probe the build cache and service status", AllIcons.Actions.Refresh) {
                    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT
                    override fun update(event: AnActionEvent) {
                        event.presentation.isEnabled =
                            event.project?.serviceOrNull<XMakeServicesSnapshotService>()?.probing != true
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
        // Every probe or state change - including the ones started from the platform
        // Services window - lands here as a re-render; no manual refresh remains.
        project.messageBus.connect(this).subscribe(
            XMakeServicesSnapshotService.TOPIC,
            XMakeServicesSnapshotService.Listener { render() },
        )
        render()
    }

    /** One probe, submitted on the executor lane. Re-entrant calls are dropped by the
     *  snapshot service: the tab's activation listener and this action both land there. */
    fun refresh() {
        snapshots.refresh()
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
            addRow(serviceRows.getValue(kind))
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
            alignmentX = Component.LEFT_ALIGNMENT
        }

    /** Toggles the persisted ccache option with `xmake f --ccache=y|n` after a confirmation:
     *  the configure pass re-runs, and with a cache tool in use the next build rebuilds
     *  affected targets. The command and its re-probe run on the snapshot service's
     *  lane flow, so both surfaces show the effect without a manual refresh. */
    private fun toggleCcache(enable: Boolean) {
        if (project.activeOrSingleXMakeBuildProfile == null) {
            showMessage(SELECT_BUILD_PROFILE_FIRST)
            showCheckboxState(!enable)
            return
        }
        if (!confirmCcacheToggle(enable)) {
            showCheckboxState(!enable)
            return
        }
        snapshots.runThenReprobe { console, factory ->
            project.xmakeExecutor.execute(console, factory.createCcacheToggle(enable))
        }
    }

    private fun confirmCcacheToggle(enable: Boolean): Boolean =
        Messages.showYesNoDialog(
            project,
            "Toggling ccache runs xmake f --ccache=${if (enable) "y" else "n"}, which reconfigures the project. " +
                "With a cache tool in use the next build rebuilds affected targets. Continue?",
            "Toggle ccache",
            Messages.getQuestionIcon(),
        ) == Messages.YES

    /** Re-renders everything from the snapshot service: the cache section, each service
     *  detail panel, the busy gate, and the status line. One EDT hop per landed result
     *  (the topic fires from the service's EDT section). */
    private fun render() {
        val snapshot = snapshots.snapshot
        applyingCheckboxState = true
        cacheCheckBox.isSelected = snapshot?.cache?.enabled ?: cacheCheckBox.isSelected
        applyingCheckboxState = false
        cacheCheckBox.isEnabled = !snapshots.probing
        cacheToolLabel.text = when {
            snapshot == null -> ""
            snapshot.cache.program != null -> snapshot.cache.program
            else -> "No cache tool found - builds run uncached"
        }
        for ((kind, row) in serviceRows) {
            val state = snapshot?.services?.get(kind) ?: kind.unprobedState()
            row.show(state, probed = snapshot != null)
            row.setBusy(snapshots.probing)
        }
        showMessage(snapshots.statusMessage ?: INITIAL_MESSAGE, snapshots.statusIsFailure)
    }

    /** The status line's one write path. [failure] carries the color rule the Packages
     *  panel already follows - a red line means the last operation failed - so the probe
     *  failure reads as one there too. */
    private fun showMessage(message: String, failure: Boolean = false) {
        messageLabel.text = message
        messageLabel.foreground = if (failure) JBColor.RED else UIUtil.getLabelForeground()
    }

    private fun showCheckboxState(enabled: Boolean) {
        applyingCheckboxState = true
        cacheCheckBox.isSelected = enabled
        applyingCheckboxState = false
    }

    override fun dispose() {
    }

    private companion object {
        const val INITIAL_MESSAGE =
            "Activate this tab or the platform Services window to probe the build cache and service status"
    }
}
