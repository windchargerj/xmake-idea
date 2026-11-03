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
import com.intellij.openapi.actionSystem.ActionToolbar
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.actionSystem.Presentation
import com.intellij.openapi.actionSystem.Separator
import com.intellij.openapi.actionSystem.ex.CustomComponentAction
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.ui.SimpleToolWindowPanel
import com.intellij.ui.JBColor
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.SideBorder
import com.intellij.ui.TitledSeparator
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import com.intellij.util.ui.components.BorderLayoutPanel
import io.xmake.run.command.xmakeExecutor
import io.xmake.run.target.SELECT_BUILD_PROFILE_FIRST
import io.xmake.run.target.activeOrSingleXMakeBuildProfile
import io.xmake.utils.cli.XMakeServiceKind
import java.awt.BorderLayout
import java.awt.Component
import java.awt.Container
import java.awt.Graphics
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.JCheckBox
import javax.swing.JComponent
import javax.swing.JPanel

/**
 * The "Services" content of the XMake tool window: a toolbar row over two sections -
 * the build cache state (the persisted `ccache` option and the program xmake actually
 * resolves) and one editable, status-iconized row per `xmake service` kind - the same
 * [XMakeServiceDetailPanel] the platform Services contributor shows in its detail pane,
 * fed by the same [XMakeServicesSnapshotService]. Clicking a row selects the kind the
 * toolbar's Connect and Disconnect act on - the tab's stand-in for the Services tree's
 * selection, whose actions follow the selected node the same way. The endpoint field
 * and Apply/Clear stay in the row (audit-services.md section 3: xmake owns the value,
 * the IDE edits it in place). The panel itself never touches the toolkit, a socket, or
 * a file: probing and state changes run on the project's executor lane with their
 * output in the Console tab.
 *
 * There is no resident listener: the snapshot refreshes when the tab is activated
 * (the factory wires that), when the platform Services window shows, and after any
 * action - so nothing probes while both surfaces are hidden.
 */
class XMakeServicesPanel(private val project: Project) : SimpleToolWindowPanel(true, true), Disposable {

    private val snapshots get() = project.xmakeServicesSnapshots

    /** True while [showCheckboxState] moves the checkbox, so the user's listener ignores it. */
    private var applyingCheckboxState = false

    /** The service kind the user last clicked; the toolbar's Connect and Disconnect act
     *  on it. Null until a row is clicked - the actions stay off, like a Services tree
     *  with nothing selected. Read by the actions' background update() - volatile so a
     *  press on the EDT is visible there without further ado. */
    @Volatile
    private var selectedKind: XMakeServiceKind? = null

    private val messageLabel = JBLabel(INITIAL_MESSAGE)

    /** The status line itself: a hairline above, the narration on the left - the VCS
     *  commit view's status panel the Packages and Analysis views already use. */
    private val messagePanel = BorderLayoutPanel().apply {
        isOpaque = false
        border = JBUI.Borders.compound(SideBorder(JBColor.border(), SideBorder.TOP), JBUI.Borders.empty(4, 8))
        addToCenter(messageLabel)
    }

    private val cacheCheckBox = JCheckBox()
    private val cacheToolLabel = JBLabel()

    private val serviceRows: Map<XMakeServiceKind, XMakeServiceDetailPanel> =
        XMakeServiceKind.entries.associateWith { XMakeServiceDetailPanel(project, it) }

    /** The rows wrapped in their click-to-select surfaces; filled once by [buildSections]. */
    private val rowSurfaces = mutableMapOf<XMakeServiceKind, SelectableRow>()

    /** The toolbar, held by its interface: a selection change and every landed snapshot
     *  refresh the Connect/Disconnect enablement immediately - a plain row click gives
     *  the toolbar no other update event. */
    private val actionToolbar: ActionToolbar = ActionManager.getInstance().createActionToolbar(
        "XMakeServicesView",
        DefaultActionGroup(
            RefreshAction(),
            Separator.create(),
            ConnectAction(),
            DisconnectAction(),
            Separator.create(),
            CcacheToggleAction(),
        ),
        true,
    ).apply { targetComponent = this@XMakeServicesPanel }

    init {
        toolbar = actionToolbar.component
        setContent(
            JBUI.Panels.simplePanel()
                .addToCenter(JBScrollPane(buildSections()))
                .addToBottom(messagePanel),
        )
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
        fun addRow(component: JComponent, top: Int = 2) {
            sections.add(
                component,
                GridBagConstraints().apply {
                    gridx = 0
                    gridwidth = GridBagConstraints.REMAINDER
                    gridy = gridy++
                    weightx = 1.0
                    fill = GridBagConstraints.HORIZONTAL
                    anchor = GridBagConstraints.WEST
                    insets = JBUI.insets(top, 12, 2, 12)
                },
            )
        }
        fun addFiller() {
            sections.add(
                JPanel().apply { isOpaque = false },
                GridBagConstraints().apply {
                    gridx = 0
                    gridwidth = GridBagConstraints.REMAINDER
                    gridy = gridy++
                    weightx = 1.0
                    weighty = 1.0
                    fill = GridBagConstraints.BOTH
                },
            )
        }

        addRow(TitledSeparator("Build cache"), top = 8)
        cacheCheckBox.text = "ccache"
        cacheCheckBox.toolTipText = "Project option (xmake f --ccache): toggling reconfigures the project"
        addRow(row(cacheCheckBox, commentLine("Effective:"), cacheToolLabel))
        // TODO: cache statistics (hit rate, cache size) from `ccache --print-stats` /
        // `sccache --print-stats` have no row yet: the probe cannot verify their output
        // formats without a cache tool installed, so the panel ships without them
        // (docs/roadmap-designs/build-services.md section 2).

        addRow(TitledSeparator("Services"), top = 8)
        for (kind in XMakeServiceKind.entries) {
            val surface = SelectableRow(kind, serviceRows.getValue(kind))
            rowSurfaces[kind] = surface
            addRow(surface)
        }
        addFiller()
        return sections
    }

    /** A gray line: the section's comment or caption, not a heading - the sections'
     *  headings are the [TitledSeparator]s above them. */
    private fun commentLine(text: String): JComponent =
        JBLabel(text).apply { foreground = SimpleTextAttributes.GRAYED_ATTRIBUTES.fgColor }

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

    /** Marks [kind] as the row the toolbar's Connect and Disconnect act on, and refreshes
     *  their enablement now - a row click is an EDT event like any other, but the toolbar
     *  listens for none of it. */
    private fun selectKind(kind: XMakeServiceKind) {
        val previous = selectedKind
        if (previous === kind) {
            return
        }
        selectedKind = kind
        if (previous != null) {
            rowSurfaces.getValue(previous).repaint()
        }
        rowSurfaces.getValue(kind).repaint()
        actionToolbar.updateActionsAsync()
    }

    /** The selected kind's state as the last probe saw it - the same unprobed fallback
     *  the rows render - or null with nothing selected. */
    private fun selectedState(): XMakeServiceState? =
        selectedKind?.let { snapshots.snapshot?.services?.get(it) ?: it.unprobedState() }

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
        actionToolbar.updateActionsAsync()
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

    /** One service row wrapped in the click-to-select surface the toolbar's Connect and
     *  Disconnect follow. The highlight is the standard list selection background, shown
     *  through the row's non-opaque panel; a press anywhere in the row - its own buttons
     *  and endpoint field included, the way a press anywhere on a Services tree node
     *  selects it - lands the selection before the control's own action runs. */
    private inner class SelectableRow(val kind: XMakeServiceKind, content: JComponent) : JPanel(BorderLayout()) {

        init {
            isOpaque = false
            add(content, BorderLayout.CENTER)
            selectOnPress(content, kind)
        }

        override fun paintComponent(graphics: Graphics) {
            if (selectedKind === kind) {
                graphics.color = UIUtil.getListSelectionBackground(true)
                graphics.fillRect(0, 0, width, height)
            }
            super.paintComponent(graphics)
        }
    }

    /** Press selects [kind] on [component] and every child it has now - the detail row's
     *  controls are built once, so no deeper listener is ever owed. */
    private fun selectOnPress(component: Component, kind: XMakeServiceKind) {
        component.addMouseListener(object : MouseAdapter() {
            override fun mousePressed(event: MouseEvent) {
                selectKind(kind)
            }
        })
        (component as? Container)?.components?.forEach { selectOnPress(it, kind) }
    }

    private inner class RefreshAction :
        DumbAwareAction("Refresh", "Re-probe the build cache and service status", AllIcons.Actions.Refresh) {

        override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

        override fun update(event: AnActionEvent) {
            event.presentation.isEnabled = !snapshots.probing
        }

        override fun actionPerformed(event: AnActionEvent) {
            refresh()
        }
    }

    /** Connects the selected kind - the toolbar twin of the row's own Connect button and
     *  the platform Services window's node action (same icon, same gating). The snapshot
     *  service explains a missing token instead of letting `xmake service --connect`
     *  hang on a stdin prompt. */
    private inner class ConnectAction :
        DumbAwareAction("Connect", "Connect the selected service kind through xmake service", AllIcons.Actions.Execute) {

        override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

        override fun update(event: AnActionEvent) {
            val state = selectedState()
            event.presentation.isEnabled = state != null && !snapshots.probing && state.endpoint != null
        }

        override fun actionPerformed(event: AnActionEvent) {
            selectedKind?.let { snapshots.connectService(it) }
        }
    }

    /** Disconnects the selected kind, gated on a persisted connection like the row's own
     *  Disconnect button. */
    private inner class DisconnectAction :
        DumbAwareAction("Disconnect", "Disconnect the selected service kind through xmake service", AllIcons.Actions.Suspend) {

        override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

        override fun update(event: AnActionEvent) {
            val state = selectedState()
            event.presentation.isEnabled = state != null && !snapshots.probing && state.connected
        }

        override fun actionPerformed(event: AnActionEvent) {
            selectedKind?.let { snapshots.disconnectService(it) }
        }
    }

    /** The ccache switch rides the toolbar as its custom component - the same checkbox
     *  as before, with its state and enablement still written by [render]. */
    private inner class CcacheToggleAction : DumbAwareAction(
        "ccache",
        "Toggles the project's ccache option through xmake f --ccache",
        null,
    ), CustomComponentAction {

        override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT

        override fun update(event: AnActionEvent) {
            event.presentation.isEnabled = !snapshots.probing
        }

        override fun actionPerformed(event: AnActionEvent) = Unit

        override fun createCustomComponent(presentation: Presentation, place: String): JComponent = cacheCheckBox
    }

    private companion object {
        const val INITIAL_MESSAGE =
            "Activate this tab or the platform Services window to probe the build cache and service status"
    }
}
