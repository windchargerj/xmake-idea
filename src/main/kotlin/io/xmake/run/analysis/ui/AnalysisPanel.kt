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
package io.xmake.run.analysis.ui

import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionToolbar
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.DataContext
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.actionSystem.Separator
import com.intellij.openapi.actionSystem.ex.ComboBoxAction
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.SimpleToolWindowPanel
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.ui.AutoScrollToSourceHandler
import com.intellij.ui.ColoredTreeCellRenderer
import com.intellij.ui.OnePixelSplitter
import com.intellij.ui.PopupHandler
import com.intellij.ui.ScrollPaneFactory
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.TreeSpeedSearch
import com.intellij.ui.treeStructure.Tree
import com.intellij.util.text.DateFormatUtil
import com.intellij.util.ui.JBUI
import io.xmake.run.command.RemotePathMapper
import io.xmake.run.analysis.AnalysisService
import io.xmake.run.analysis.AnalysisFrame
import io.xmake.run.analysis.AnalysisIssue
import io.xmake.run.analysis.AnalysisReport
import io.xmake.run.analysis.AnalysisSeverity
import io.xmake.run.analysis.AnalysisStack
import io.xmake.run.tidy.runClangTidy
import java.awt.Component
import java.awt.datatransfer.StringSelection
import com.intellij.ui.DoubleClickListener
import java.awt.event.MouseEvent
import java.io.File
import java.util.Date
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JTextArea
import javax.swing.JTree
import javax.swing.tree.DefaultMutableTreeNode
import javax.swing.tree.DefaultTreeModel
import javax.swing.tree.TreeSelectionModel

/**
 * The sanitizer analysis view: the findings of the last run as one navigable tree.
 *
 * Issues group by type, identical reports carry a count instead of repeating, every stack
 * frame of every role opens its source line, and the details pane keeps the report's own
 * words for the selected finding. It is the runtime's console report, restructured - the
 * same information, readable without scrolling through interleaved program output.
 */
internal class AnalysisPanel(
    private val project: Project,
    parentDisposable: com.intellij.openapi.Disposable,
) : SimpleToolWindowPanel(true, true), com.intellij.openapi.Disposable {

    private val tree = Tree()
    private val treeModel = DefaultTreeModel(DefaultMutableTreeNode("root"))
    private val summaryLabel = JLabel()
    private val details = JTextArea().apply {
        isEditable = false
        lineWrap = true
        wrapStyleWord = true
        // The report's own words, aligned the way the console shows them.
        font = com.intellij.openapi.editor.colors.EditorColorsManager.getInstance().globalScheme.getFont(com.intellij.openapi.editor.colors.EditorFontType.PLAIN)
    }

    private var errorsOnly = false

    /**
     * The report the tree shows - the latest one by default, an older one after a history
     * pick. It follows every publish; picking from the history only swaps it locally.
     */
    private var displayed: AnalysisReport? = null

    /** The view's Autoscroll-to-Source switch; the handler and its toolbar toggle share it. */
    private var autoScrollMode = false

    private val autoScroll = object : AutoScrollToSourceHandler() {

        override fun isAutoScrollMode() = autoScrollMode

        override fun setAutoScrollMode(state: Boolean) {
            autoScrollMode = state
        }

        // The tree's nodes carry no Navigatable data, so the default data-context routing
        // would find nothing; the view knows how to open the selection itself.
        override fun scrollToSource(tree: Component) {
            navigateSelection()
        }
    }

    init {
        tree.isRootVisible = false
        tree.selectionModel.selectionMode = TreeSelectionModel.SINGLE_TREE_SELECTION
        tree.cellRenderer = Renderer()
        tree.model = treeModel
        TreeSpeedSearch.installOn(tree, true) { path -> path.lastPathComponent.toString() }
        // The platform's own double-click handling: keyboard and accessibility included.
        object : DoubleClickListener() {
            override fun onDoubleClick(event: MouseEvent): Boolean {
                navigateSelection()
                return true
            }
        }.installOn(tree)
        tree.addTreeSelectionListener {
            val node = tree.lastSelectedPathComponent as? IssueNode
            details.text = node?.issue?.let(::renderDetails).orEmpty()
        }
        // The platform's autoscroll machinery - its timing, its toggle action - driving the
        // view's own navigation.
        autoScroll.install(tree)
        // The Problems view's right-click vocabulary: jump to the source, copy the finding.
        PopupHandler.installPopupMenu(
            tree,
            DefaultActionGroup(JumpToSourceAction(), CopyFindingAction()),
            "XMakeAnalysisView",
        )

        toolbar = toolbar().component
        // The Problems view's shape: the findings above, the selected one's report below,
        // the split remembered between sessions.
        val split = OnePixelSplitter(true, SPLIT_KEY, 0.72f).apply {
            firstComponent = JBUI.Panels.simplePanel().apply {
                addToTop(summaryLabel.apply { border = JBUI.Borders.empty(4, 8) })
                addToCenter(ScrollPaneFactory.createScrollPane(tree, true))
            }
            secondComponent = detailsPanel()
        }
        setContent(split)

        refresh(AnalysisService.getInstance(project).report)
        project.messageBus.connect(parentDisposable).subscribe(
            AnalysisService.TOPIC,
            AnalysisService.Listener { report ->
                com.intellij.openapi.application.invokeLater { if (!project.isDisposed) refresh(report) }
            },
        )
    }

    /** The subscriptions ride on the content's disposal; nothing else outlives the panel. */
    override fun dispose() = Unit

    private fun toolbar(): ActionToolbar {
        val group = DefaultActionGroup(
            RunClangTidyAction(),
            StopCheckAction(),
            RerunAction(),
            Separator.create(),
            HistoryAction(),
            ClearAction(),
            Separator.create(),
            ErrorsOnlyAction(),
            autoScroll.createToggleAction(),
            CopyReportAction(),
        )
        return ActionManager.getInstance()
            .createActionToolbar("XMakeAnalysisView", group, true)
            .apply { targetComponent = this@AnalysisPanel }
    }

    private fun detailsPanel(): JComponent = JBUI.Panels.simplePanel().apply {
        addToTop(JLabel("Details").apply { border = JBUI.Borders.empty(4, 8) })
        addToCenter(ScrollPaneFactory.createScrollPane(details, true))
    }

    /**
     * Shows [report] in the tree - the newly published one, or a history pick - and remembers
     * it as the displayed report; null shows the empty hint instead.
     */
    fun refresh(report: AnalysisReport?) {
        displayed = report
        val root = DefaultMutableTreeNode("root")
        if (report == null) {
            summaryLabel.text = "No findings - run sanitized, or run clang-tidy from the toolbar"
            details.text = ""
        } else {
            val visible = report.issues
                .filter { !errorsOnly || it.severity == AnalysisSeverity.ERROR }
                .sortedWith(
                    compareByDescending<AnalysisIssue> { it.severity }
                        .thenByDescending { it.count },
                )
            visible.forEach { root.add(IssueNode(it)) }
            summaryLabel.text =
                "${report.source} on '${report.targetName}': " +
                        "${report.errorCount} error(s), ${report.warningCount} warning(s) - " +
                        "${report.totalFindings} finding(s) at ${report.issues.size} unique place(s)"
        }
        treeModel.setRoot(root)
        expandIssues()
    }

    private fun expandIssues() {
        var row = 0
        while (row < tree.rowCount) {
            tree.expandRow(row)
            row++
        }
    }

    private fun navigateSelection() {
        selectedFrame()?.let(::navigate)
    }

    /** The frame the selection names - a stack frame, or the issue's primary one. */
    private fun selectedFrame(): AnalysisFrame? {
        val node = tree.lastSelectedPathComponent ?: return null
        return when (node) {
            is FrameNode -> node.frame
            is IssueNode -> node.issue.primaryFrame
            else -> null
        }
    }

    /** The issue the selection belongs to: the node itself, or anything below one. */
    private fun selectedIssue(): AnalysisIssue? {
        var node = tree.lastSelectedPathComponent as? DefaultMutableTreeNode ?: return null
        while (true) {
            if (node is IssueNode) return node.issue
            node = node.parent as? DefaultMutableTreeNode ?: return null
        }
    }

    /** Opens the source line one frame names, mapping host paths to local files first. */
    private fun navigate(frame: AnalysisFrame) {
        val file = frame.file ?: return
        val local = resolveLocalFile(file) ?: return
        val virtual = LocalFileSystem.getInstance().refreshAndFindFileByIoFile(local) ?: return
        OpenFileDescriptor(project, virtual, (frame.line ?: 1) - 1, (frame.column ?: 1) - 1).navigate(true)
    }

    /** Host form first, then project-relative, matching how the toolchain compiles sources.
     *  The mapping comes from the displayed report, so history picks resolve against the
     *  run that found them. */
    private fun resolveLocalFile(file: String): File? {
        val report = displayed
        val mapper = report?.toolkit?.let { toolkit ->
            report.hostWorkingDirectory?.let {
                RemotePathMapper.forToolkit(toolkit, it, project)
            }
        }
        val mapped = mapper?.toLocal(File(file).toPath())?.toFile()
        if (mapped?.isFile == true) return mapped
        val basePath = project.basePath ?: return null
        return File(basePath, file).takeIf(File::isFile) ?: File(file).takeIf(File::isFile)
    }

    private fun renderDetails(issue: AnalysisIssue): String = buildString {
        append(issue.severity).append(": ").append(issue.type).append('\n')
        append(issue.message).append('\n')
        if (issue.count > 1) append("occurrences: ").append(issue.count).append('\n')
        issue.stacks.forEach { stack ->
            append('\n').append(stack.title).append('\n')
            stack.frames.forEachIndexed { index, frame ->
                append("  #").append(index).append(' ').append(frame.function)
                frame.file?.let { path ->
                    append(" (").append(path)
                    frame.line?.let { append(':').append(it) }
                    frame.column?.let { append(':').append(it) }
                    append(')')
                }
                append('\n')
            }
        }
    }

    /** One click from findings to more findings: the project check fills the same view. */
    private class RunClangTidyAction : DumbAwareAction(
        "Run Clang-Tidy",
        "Check the project through xmake check clang.tidy",
        AllIcons.Actions.Find,
    ) {

        override fun getActionUpdateThread() = ActionUpdateThread.EDT

        override fun update(event: AnActionEvent) {
            // One check at a time; the stop button owns the running one.
            val project = event.project
            event.presentation.isEnabled =
                project != null && !AnalysisService.getInstance(project).isCheckRunning
        }

        override fun actionPerformed(event: AnActionEvent) {
            runClangTidy(event.project ?: return)
        }
    }

    /** The check's off switch: kills the process the run registered, partial findings stay. */
    private inner class StopCheckAction : DumbAwareAction(
        "Stop",
        "Stop the check running now",
        AllIcons.Actions.Suspend,
    ) {

        override fun getActionUpdateThread() = ActionUpdateThread.EDT

        override fun update(event: AnActionEvent) {
            event.presentation.isEnabled = AnalysisService.getInstance(project).isCheckRunning
        }

        override fun actionPerformed(event: AnActionEvent) {
            AnalysisService.getInstance(project).stopCheck()
        }
    }

    private inner class RerunAction :
        DumbAwareAction("Rerun", "Run the last sanitized configuration again", AllIcons.Actions.Restart) {

        override fun getActionUpdateThread() = ActionUpdateThread.EDT

        override fun update(event: AnActionEvent) {
            event.presentation.isEnabled = AnalysisService.getInstance(project).lastSanitizedSettings != null
        }

        override fun actionPerformed(event: AnActionEvent) {
            AnalysisService.getInstance(project).rerunLast()
        }
    }

    private inner class ClearAction : DumbAwareAction("Clear", "Drop the current findings", AllIcons.Actions.GC) {

        override fun getActionUpdateThread() = ActionUpdateThread.EDT

        override fun actionPerformed(event: AnActionEvent) {
            // A clear drops both the current report and the history - a clean slate again.
            AnalysisService.getInstance(project).publish(null)
        }
    }

    /** The session's reports, newest first; picking one only changes what the tree shows. */
    private inner class HistoryAction : ComboBoxAction() {

        override fun getActionUpdateThread() = ActionUpdateThread.EDT

        override fun update(event: AnActionEvent) {
            super.update(event)
            val history = AnalysisService.getInstance(project).history
            event.presentation.isEnabled = history.isNotEmpty()
            event.presentation.text = displayed?.let(::historyLabel) ?: "History"
        }

        override fun createPopupActionGroup(button: JComponent, dataContext: DataContext): DefaultActionGroup {
            val group = DefaultActionGroup()
            AnalysisService.getInstance(project).history.forEach { entry ->
                group.add(object : DumbAwareAction(historyLabel(entry)) {

                    override fun getActionUpdateThread() = ActionUpdateThread.EDT

                    override fun actionPerformed(event: AnActionEvent) {
                        // Showing an older report is presentation-only; the service's
                        // current one stays what it is, so no publish here.
                        refresh(entry)
                    }
                })
            }
            return group
        }
    }

    private inner class ErrorsOnlyAction : DumbAwareAction(
        "Errors Only",
        "Show only the errors, or everything again",
        AllIcons.General.Filter,
    ) {

        override fun getActionUpdateThread() = ActionUpdateThread.EDT

        override fun update(event: AnActionEvent) {
            event.presentation.text = if (errorsOnly) "Show All" else "Errors Only"
        }

        override fun actionPerformed(event: AnActionEvent) {
            errorsOnly = !errorsOnly
            refresh(displayed)
        }
    }

    private inner class CopyReportAction : DumbAwareAction(
        "Copy Report",
        "Copy the findings as text",
        AllIcons.Actions.Copy,
    ) {

        override fun getActionUpdateThread() = ActionUpdateThread.EDT

        override fun update(event: AnActionEvent) {
            event.presentation.isEnabled = displayed != null
        }

        override fun actionPerformed(event: AnActionEvent) {
            val report = displayed ?: return
            val text = report.issues.joinToString("\n\n", postfix = "\n") { renderDetails(it) }
            CopyPasteManager.getInstance().setContents(StringSelection(text))
        }
    }

    private inner class JumpToSourceAction : DumbAwareAction(
        "Jump to Source",
        "Open the source line the selection names",
        AllIcons.Actions.EditSource,
    ) {

        override fun getActionUpdateThread() = ActionUpdateThread.EDT

        override fun update(event: AnActionEvent) {
            event.presentation.isEnabled = selectedFrame() != null
        }

        override fun actionPerformed(event: AnActionEvent) {
            navigateSelection()
        }
    }

    private inner class CopyFindingAction : DumbAwareAction(
        "Copy Finding",
        "Copy the selected finding's report as text",
        AllIcons.Actions.Copy,
    ) {

        override fun getActionUpdateThread() = ActionUpdateThread.EDT

        override fun update(event: AnActionEvent) {
            event.presentation.isEnabled = selectedIssue() != null
        }

        override fun actionPerformed(event: AnActionEvent) {
            val issue = selectedIssue() ?: return
            CopyPasteManager.getInstance().setContents(StringSelection(renderDetails(issue)))
        }
    }

    /** One history entry's label: when it ran, what found the issues, how many it found. */
    private fun historyLabel(report: AnalysisReport): String =
        "${DateFormatUtil.formatTime(Date.from(report.timestamp))} - " +
                "${report.source} - ${report.totalFindings} finding(s)"

    private class IssueNode(val issue: AnalysisIssue) : DefaultMutableTreeNode(issue) {
        init {
            issue.stacks.forEach { stack -> add(StackNode(stack)) }
        }

        override fun toString(): String = issue.type
    }

    private class StackNode(val stack: AnalysisStack) : DefaultMutableTreeNode(stack) {
        init {
            stack.frames.forEach { frame -> add(FrameNode(frame)) }
        }

        override fun toString(): String = stack.title
    }

    private class FrameNode(val frame: AnalysisFrame) : DefaultMutableTreeNode(frame) {
        override fun toString(): String = frame.function
    }

    private companion object {
        /** The splitter's persistence key; the user's split survives restarts. */
        private const val SPLIT_KEY = "XMakeAnalysisView"
    }

    private class Renderer : ColoredTreeCellRenderer() {

        override fun customizeCellRenderer(
            tree: JTree,
            value: Any,
            selected: Boolean,
            expanded: Boolean,
            leaf: Boolean,
            row: Int,
            hasFocus: Boolean,
        ) {
            when (value) {
                is IssueNode -> {
                    icon = when (value.issue.severity) {
                        AnalysisSeverity.ERROR -> AllIcons.General.Error
                        AnalysisSeverity.WARNING -> AllIcons.General.Warning
                        AnalysisSeverity.NOTE -> AllIcons.General.Information
                    }
                    append(value.issue.type, SimpleTextAttributes.REGULAR_BOLD_ATTRIBUTES)
                    append("  ${value.issue.message.lineSequence().firstOrNull().orEmpty()}", SimpleTextAttributes.REGULAR_ATTRIBUTES)
                    if (value.issue.count > 1) {
                        append("  ×${value.issue.count}", SimpleTextAttributes.GRAYED_ATTRIBUTES)
                    }
                }
                is StackNode -> append(value.stack.title, SimpleTextAttributes.GRAYED_ATTRIBUTES)
                is FrameNode -> {
                    append(value.frame.function, SimpleTextAttributes.REGULAR_ATTRIBUTES)
                    value.frame.file?.let { file ->
                        val location = buildString {
                            append(file)
                            value.frame.line?.let { append(':').append(it) }
                            value.frame.column?.let { append(':').append(it) }
                        }
                        append("  $location", SimpleTextAttributes.GRAYED_SMALL_ATTRIBUTES)
                    }
                }
                else -> append(value.toString(), SimpleTextAttributes.REGULAR_ATTRIBUTES)
            }
        }
    }
}
