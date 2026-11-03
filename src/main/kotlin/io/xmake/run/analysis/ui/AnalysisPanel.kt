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
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionToolbar
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DataContext
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.actionSystem.Separator
import com.intellij.openapi.actionSystem.ex.ComboBoxAction
import com.intellij.openapi.application.invokeLater
import com.intellij.openapi.editor.colors.EditorColorsManager
import com.intellij.openapi.editor.colors.EditorFontType
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.SimpleToolWindowPanel
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.ui.AutoScrollToSourceHandler
import com.intellij.ui.ColoredTableCellRenderer
import com.intellij.ui.ColoredTreeCellRenderer
import com.intellij.ui.DoubleClickListener
import com.intellij.ui.OnePixelSplitter
import com.intellij.ui.PopupHandler
import com.intellij.ui.ScrollPaneFactory
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.TreeTableSpeedSearch
import com.intellij.ui.treeStructure.treetable.ListTreeTableModelOnColumns
import com.intellij.ui.treeStructure.treetable.TreeTable
import com.intellij.ui.treeStructure.treetable.TreeTableModel
import com.intellij.util.containers.Convertor
import com.intellij.util.text.DateFormatUtil
import com.intellij.util.ui.ColumnInfo
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.tree.TreeUtil
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
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.io.File
import java.util.Date
import javax.swing.Icon
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JTable
import javax.swing.JTextArea
import javax.swing.JTree
import javax.swing.SwingUtilities
import javax.swing.tree.DefaultMutableTreeNode
import javax.swing.tree.TreeNode
import javax.swing.tree.TreePath
import javax.swing.tree.TreeSelectionModel

/**
 * The sanitizer analysis view: the findings of the last run as one navigable tree-table.
 *
 * Issues group by type, identical reports carry a count instead of repeating, every stack
 * frame of every role opens its source line, and the details pane keeps the report's own
 * words for the selected finding. It is the runtime's console report, restructured - the
 * same information, readable without scrolling through interleaved program output.
 *
 * The rows are a [TreeTable]: severity leads the tree column, and type, occurrences, and
 * the source location fill the cells beside it. The Accumulate switch unions every run of
 * this session into one view - findings merge on their signature, occurrences sum, and
 * every row keeps the run that found it so its host paths still map to local files.
 */
internal class AnalysisPanel(
    private val project: Project,
    parentDisposable: Disposable,
) : SimpleToolWindowPanel(true, true), Disposable {

    private val model = ListTreeTableModelOnColumns(DefaultMutableTreeNode(ROOT), COLUMNS)
    private val treeTable = TreeTable(model)
    private val summaryLabel = JLabel()
    private val details = JTextArea().apply {
        isEditable = false
        lineWrap = true
        wrapStyleWord = true
        // The report's own words, aligned the way the console shows them.
        font = EditorColorsManager.getInstance().globalScheme.getFont(EditorFontType.PLAIN)
    }

    private var errorsOnly = false

    /** The Accumulate switch: the union of every history run instead of the last run alone. */
    private var accumulate = false

    /**
     * The report the view shows - the latest one by default, an older one after a history
     * pick. It follows every publish; picking from the history only swaps it locally. The
     * accumulated view ignores it and re-derives from the history instead.
     */
    private var displayed: AnalysisReport? = null

    /** The finding rows the view shows now, whatever mode built them; Copy Report reads them. */
    private var shownIssues: List<IssueNode> = emptyList()

    /** The view's Autoscroll-to-Source switch; the handler and its toolbar toggle share it. */
    private var autoScrollMode = false

    private val autoScroll = object : AutoScrollToSourceHandler() {

        override fun isAutoScrollMode() = autoScrollMode

        override fun setAutoScrollMode(state: Boolean) {
            autoScrollMode = state
        }

        // The rows carry no Navigatable data, so the default data-context routing would
        // find nothing; the view knows how to open the selection itself.
        override fun scrollToSource(tree: Component) {
            navigateSelection()
        }
    }

    /** The tree column's renderer: the severity of a finding, the role of a stack, the frame's function. */
    private val treeColumnRenderer = object : ColoredTreeCellRenderer() {

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
                    icon = severityIcon(value.issue.severity)
                    append(severityTitle(value.issue.severity), SimpleTextAttributes.REGULAR_BOLD_ATTRIBUTES)
                }
                is StackNode -> append(value.stack.title, SimpleTextAttributes.GRAYED_ATTRIBUTES)
                is FrameNode -> append(value.frame.function, SimpleTextAttributes.REGULAR_ATTRIBUTES)
                else -> append(value.toString(), SimpleTextAttributes.REGULAR_ATTRIBUTES)
            }
        }
    }

    /** The Type cell: the runtime's classification with the finding's own first line beside it. */
    private val typeCellRenderer = object : ColoredTableCellRenderer() {

        override fun customizeCellRenderer(
            table: JTable,
            value: Any?,
            selected: Boolean,
            focused: Boolean,
            row: Int,
            column: Int,
        ) {
            when (val node = nodeAtRow(table, row)) {
                is IssueNode -> {
                    append(node.issue.type, SimpleTextAttributes.REGULAR_BOLD_ATTRIBUTES)
                    append(
                        "  ${node.issue.message.lineSequence().firstOrNull().orEmpty()}",
                        SimpleTextAttributes.REGULAR_ATTRIBUTES,
                    )
                }
                else -> append(value?.toString().orEmpty(), SimpleTextAttributes.REGULAR_ATTRIBUTES)
            }
        }
    }

    /** The Count cell: the occurrences marker, blank when the finding happened once. */
    private val countCellRenderer = object : ColoredTableCellRenderer() {

        override fun customizeCellRenderer(
            table: JTable,
            value: Any?,
            selected: Boolean,
            focused: Boolean,
            row: Int,
            column: Int,
        ) {
            val count = value as? Int ?: return
            if (count > 1) append("×$count", SimpleTextAttributes.GRAYED_ATTRIBUTES)
        }
    }

    /** The Location cell: where the finding points, grayed like the old tree's annotations. */
    private val locationCellRenderer = object : ColoredTableCellRenderer() {

        override fun customizeCellRenderer(
            table: JTable,
            value: Any?,
            selected: Boolean,
            focused: Boolean,
            row: Int,
            column: Int,
        ) {
            (value as? String)?.let { append(it, SimpleTextAttributes.GRAYED_ATTRIBUTES) }
        }
    }

    init {
        treeTable.setRootVisible(false)
        treeTable.tree.selectionModel.selectionMode = TreeSelectionModel.SINGLE_TREE_SELECTION
        treeTable.setTreeCellRenderer(treeColumnRenderer)
        // The Location column absorbs the width the fixed columns leave free.
        treeTable.autoResizeMode = JTable.AUTO_RESIZE_LAST_COLUMN
        treeTable.columnModel.getColumn(COLUMN_TYPE).cellRenderer = typeCellRenderer
        treeTable.columnModel.getColumn(COLUMN_COUNT).cellRenderer = countCellRenderer
        treeTable.columnModel.getColumn(COLUMN_LOCATION).cellRenderer = locationCellRenderer
        tuneColumnWidths()
        TreeTableSpeedSearch.installOn(treeTable, Convertor { path ->
            path.lastPathComponent.toString()
        }).setCanExpand(true)
        // The platform's own double-click handling: keyboard and accessibility included.
        object : DoubleClickListener() {
            override fun onDoubleClick(event: MouseEvent): Boolean {
                navigateSelection()
                return true
            }
        }.installOn(treeTable)
        treeTable.tree.addTreeSelectionListener {
            val node = treeTable.tree.lastSelectedPathComponent as? IssueNode
            details.text = node?.issue?.let(::renderDetails).orEmpty()
        }
        // A table keeps its selection on right-click; the popup actions address the selected
        // row, so select the row under the cursor first - the tree column does that itself.
        treeTable.addMouseListener(object : MouseAdapter() {
            override fun mousePressed(event: MouseEvent) = selectRowUnderCursor(event)
            override fun mouseReleased(event: MouseEvent) = selectRowUnderCursor(event)
        })
        // The platform's autoscroll machinery - its timing, its toggle action - driving the
        // view's own navigation.
        autoScroll.install(treeTable)
        // The Problems view's right-click vocabulary: jump to the source, copy the finding.
        PopupHandler.installPopupMenu(
            treeTable,
            DefaultActionGroup(JumpToSourceAction(), CopyFindingAction()),
            "XMakeAnalysisView",
        )

        toolbar = toolbar().component
        // The Problems view's shape: the findings above, the selected one's report below,
        // the split remembered between sessions.
        val split = OnePixelSplitter(true, SPLIT_KEY, 0.72f).apply {
            firstComponent = JBUI.Panels.simplePanel().apply {
                addToTop(summaryLabel.apply { border = JBUI.Borders.empty(4, 8) })
                addToCenter(ScrollPaneFactory.createScrollPane(treeTable, true))
            }
            secondComponent = detailsPanel()
        }
        setContent(split)

        refresh(AnalysisService.getInstance(project).report)
        project.messageBus.connect(parentDisposable).subscribe(
            AnalysisService.TOPIC,
            AnalysisService.Listener { report ->
                invokeLater { if (!project.isDisposed) refresh(report) }
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
            AccumulateAction(),
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
     * Shows [report] - the newly published one, or a history pick - and remembers it as the
     * displayed report; null shows the empty hint. In accumulate mode the view re-derives
     * the union from the history, which already contains the published report.
     */
    fun refresh(report: AnalysisReport?) {
        displayed = report
        refreshView()
    }

    /** Re-renders the view from the current state; every toolbar toggle lands here. */
    private fun refreshView() {
        if (accumulate) showAccumulated() else showSingle(displayed)
    }

    /** The findings of one report, as the last run - or a history pick - saw them. */
    private fun showSingle(report: AnalysisReport?) {
        if (report == null) {
            showEmpty()
            return
        }
        val nodes = visible(report.issues).map { IssueNode(it, report) }
        summaryLabel.text =
            "${report.source} on '${report.targetName}': " +
                    "${report.errorCount} error(s), ${report.warningCount} warning(s) - " +
                    "${report.totalFindings} finding(s) at ${report.issues.size} unique place(s)"
        show(nodes)
    }

    /** Every history run's findings merged into one view, newest sighting per signature. */
    private fun showAccumulated() {
        val history = AnalysisService.getInstance(project).history
        if (history.isEmpty()) {
            showEmpty()
            return
        }
        val findings = mergedFindings()
        val nodes = findings
            .filter { !errorsOnly || it.issue.severity == AnalysisSeverity.ERROR }
            .sortedWith(
                compareByDescending<MergedFinding> { it.issue.severity }
                    .thenByDescending { it.issue.count },
            )
            .map { IssueNode(it.issue, it.source) }
        val issues = nodes.map(IssueNode::issue)
        summaryLabel.text =
            "Accumulated from ${history.size} run(s): " +
                    "${issues.count { it.severity == AnalysisSeverity.ERROR }} error(s), " +
                    "${issues.count { it.severity == AnalysisSeverity.WARNING }} warning(s) - " +
                    "${issues.sumOf(AnalysisIssue::count)} finding(s) at ${issues.size} unique place(s)"
        show(nodes)
    }

    private fun show(nodes: List<IssueNode>) {
        shownIssues = nodes
        val root = DefaultMutableTreeNode(ROOT)
        nodes.forEach(root::add)
        // The model is replaced in place, so column widths, renderers, and the speed
        // search survive; setRoot fires the structure change both the tree and the
        // table's adapter listen for.
        model.setRoot(root)
        // A structure change collapses everything; findings are few, so expand them again.
        TreeUtil.expandAll(treeTable.tree)
    }

    private fun showEmpty() {
        shownIssues = emptyList()
        summaryLabel.text = NO_FINDINGS
        details.text = ""
        show(emptyList())
    }

    /** The severity filter and the error-first, most-often ordering the rows show in. */
    private fun visible(issues: List<AnalysisIssue>): List<AnalysisIssue> =
        issues.filter { !errorsOnly || it.severity == AnalysisSeverity.ERROR }
            .sortedWith(
                compareByDescending<AnalysisIssue> { it.severity }
                    .thenByDescending { it.count },
            )

    /**
     * Unions every history report's findings on [AnalysisIssue.signature]: the first
     * sighting donates the finding - the history is newest first, so that is the newest
     * report that saw it - and every later sighting only adds its occurrences.
     */
    private fun mergedFindings(): List<MergedFinding> {
        val bySignature = LinkedHashMap<String, MergedFinding>()
        for (report in AnalysisService.getInstance(project).history) {
            for (issue in report.issues) {
                val seen = bySignature[issue.signature]
                bySignature[issue.signature] = if (seen == null) MergedFinding(issue, report)
                else MergedFinding(seen.issue.copy(count = seen.issue.count + issue.count), seen.source)
            }
        }
        return bySignature.values.toList()
    }

    private fun tuneColumnWidths() {
        val columns = treeTable.columnModel
        columns.getColumn(COLUMN_SEVERITY).preferredWidth = JBUI.scale(120)
        columns.getColumn(COLUMN_TYPE).preferredWidth = JBUI.scale(340)
        columns.getColumn(COLUMN_COUNT).preferredWidth = JBUI.scale(60)
        columns.getColumn(COLUMN_LOCATION).preferredWidth = JBUI.scale(280)
    }

    /** The row's node the renderer customizes; table rows and tree rows are one to one. */
    private fun nodeAtRow(table: JTable, row: Int): Any? =
        (table as? TreeTable)?.tree?.getPathForRow(row)?.lastPathComponent

    /** A right-press on any column selects the row under the cursor for the popup. */
    private fun selectRowUnderCursor(event: MouseEvent) {
        if (!SwingUtilities.isRightMouseButton(event)) return
        val row = treeTable.rowAtPoint(event.point)
        if (row >= 0 && !treeTable.isRowSelected(row)) treeTable.setRowSelectionInterval(row, row)
    }

    private fun navigateSelection() {
        val node = treeTable.tree.lastSelectedPathComponent as? DefaultMutableTreeNode ?: return
        when (node) {
            is FrameNode -> navigate(node.frame, node.owningIssue()?.source)
            is IssueNode -> navigate(node.issue.primaryFrame ?: return, node.source)
            else -> Unit
        }
    }

    /** The frame the selection names - a stack frame, or the issue's primary one. */
    private fun selectedFrame(): AnalysisFrame? {
        val node = treeTable.tree.lastSelectedPathComponent as? DefaultMutableTreeNode ?: return null
        return when (node) {
            is FrameNode -> node.frame
            is IssueNode -> node.issue.primaryFrame
            else -> null
        }
    }

    /** The issue the selection belongs to: the node itself, or anything below one. */
    private fun selectedIssue(): AnalysisIssue? =
        (treeTable.tree.lastSelectedPathComponent as? DefaultMutableTreeNode)?.owningIssue()?.issue

    /** The issue row a node belongs to: itself, or the nearest ancestor issue row. */
    private fun DefaultMutableTreeNode.owningIssue(): IssueNode? {
        var cursor: TreeNode? = this
        while (cursor is DefaultMutableTreeNode) {
            if (cursor is IssueNode) return cursor
            cursor = cursor.parent
        }
        return null
    }

    /** Opens the source line one frame names, mapping host paths to local files first. */
    private fun navigate(frame: AnalysisFrame, report: AnalysisReport?) {
        val file = frame.file ?: return
        val local = resolveLocalFile(file, report) ?: return
        val virtual = LocalFileSystem.getInstance().refreshAndFindFileByIoFile(local) ?: return
        OpenFileDescriptor(project, virtual, (frame.line ?: 1) - 1, (frame.column ?: 1) - 1).navigate(true)
    }

    /** Host form first, then project-relative, matching how the toolchain compiles sources.
     *  The mapping comes from the report that found the finding, so accumulated rows and
     *  history picks alike resolve against the run that produced them. */
    private fun resolveLocalFile(file: String, report: AnalysisReport?): File? {
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

    /**
     * The session's reports, newest first; picking one only changes what the view shows.
     * While the accumulated union is shown the picker steps aside: the union already
     * contains every run, and a single report would contradict it.
     */
    private inner class HistoryAction : ComboBoxAction() {

        override fun getActionUpdateThread() = ActionUpdateThread.EDT

        override fun update(event: AnActionEvent) {
            super.update(event)
            val history = AnalysisService.getInstance(project).history
            event.presentation.isEnabled = history.isNotEmpty() && !accumulate
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
            refreshView()
        }
    }

    /** The accumulated view's switch: union every run's findings, or the last run alone again. */
    private inner class AccumulateAction : DumbAwareAction(
        "Accumulate",
        "Show the union of every run's findings, or the last run alone again",
        AllIcons.Actions.GroupBy,
    ) {

        override fun getActionUpdateThread() = ActionUpdateThread.EDT

        override fun update(event: AnActionEvent) {
            event.presentation.text = if (accumulate) "Latest Only" else "Accumulate"
        }

        override fun actionPerformed(event: AnActionEvent) {
            accumulate = !accumulate
            refreshView()
        }
    }

    private inner class CopyReportAction : DumbAwareAction(
        "Copy Report",
        "Copy the findings as text",
        AllIcons.Actions.Copy,
    ) {

        override fun getActionUpdateThread() = ActionUpdateThread.EDT

        override fun update(event: AnActionEvent) {
            event.presentation.isEnabled = shownIssues.isNotEmpty()
        }

        override fun actionPerformed(event: AnActionEvent) {
            val text = shownIssues.joinToString("\n\n", postfix = "\n") { renderDetails(it.issue) }
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

    /** One finding row: the issue and the run that found it, with its stacks beneath. */
    private class IssueNode(val issue: AnalysisIssue, val source: AnalysisReport) :
        DefaultMutableTreeNode(issue) {
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

    /** One accumulated row: the newest sighting's finding, its occurrences summed over the runs. */
    private class MergedFinding(val issue: AnalysisIssue, val source: AnalysisReport)

    private companion object {

        /** The splitter's persistence key; the user's split survives restarts. */
        private const val SPLIT_KEY = "XMakeAnalysisView"

        /** The invisible tree root; the panel swaps it per refresh. */
        private const val ROOT = "root"

        /** Shown when nothing is accumulated and no report exists. */
        private const val NO_FINDINGS = "No findings - run sanitized, or run clang-tidy from the toolbar"

        private const val COLUMN_SEVERITY = 0
        private const val COLUMN_TYPE = 1
        private const val COLUMN_COUNT = 2
        private const val COLUMN_LOCATION = 3

        /** The table's columns; the first hosts the expandable tree, so severity leads it. */
        private val COLUMNS: Array<ColumnInfo<Any, *>> =
            arrayOf(SeverityColumn, TypeColumn, CountColumn, LocationColumn)

        private fun severityIcon(severity: AnalysisSeverity): Icon = when (severity) {
            AnalysisSeverity.ERROR -> AllIcons.General.Error
            AnalysisSeverity.WARNING -> AllIcons.General.Warning
            AnalysisSeverity.NOTE -> AllIcons.General.Information
        }

        private fun severityTitle(severity: AnalysisSeverity): String =
            severity.name.lowercase().replaceFirstChar(Char::uppercase)

        /** The finding's point, as `file:line[:column]`; null when unsymbolized. */
        private fun location(frame: AnalysisFrame): String? {
            val file = frame.file ?: return null
            return buildString {
                append(file)
                frame.line?.let { append(':').append(it) }
                frame.column?.let { append(':').append(it) }
            }
        }

        /**
         * Severity leads the tree column: a column becomes the tree column by declaring
         * [TreeTableModel] as its class, exactly what the platform's tree column does.
         */
        private object SeverityColumn : ColumnInfo<Any, AnalysisSeverity?>("Severity") {

            override fun valueOf(item: Any): AnalysisSeverity? = (item as? IssueNode)?.issue?.severity

            override fun getColumnClass(): Class<*> = TreeTableModel::class.java
        }

        private object TypeColumn : ColumnInfo<Any, String?>("Type") {

            override fun valueOf(item: Any): String? = (item as? IssueNode)?.issue?.type
        }

        private object CountColumn : ColumnInfo<Any, Int?>("Count") {

            override fun valueOf(item: Any): Int? = (item as? IssueNode)?.issue?.count
        }

        private object LocationColumn : ColumnInfo<Any, String?>("Location") {

            override fun valueOf(item: Any): String? = when (item) {
                is IssueNode -> item.issue.primaryFrame?.let(::location)
                is FrameNode -> location(item.frame)
                else -> null
            }
        }
    }
}
