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
import com.intellij.openapi.ui.Messages
import com.intellij.notification.NotificationType
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionToolbar
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CustomShortcutSet
import com.intellij.openapi.actionSystem.DataContext
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.actionSystem.Separator
import com.intellij.openapi.actionSystem.Presentation
import com.intellij.openapi.actionSystem.ex.ComboBoxAction
import com.intellij.openapi.application.invokeLater
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.DumbAwareToggleAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.SimpleToolWindowPanel
import com.intellij.openapi.util.text.StringUtil
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.ui.AutoScrollToSourceHandler
import com.intellij.ui.ColoredTableCellRenderer
import com.intellij.ui.ColoredTreeCellRenderer
import com.intellij.ui.DoubleClickListener
import com.intellij.ui.JBColor
import com.intellij.ui.OnePixelSplitter
import com.intellij.ui.PopupHandler
import com.intellij.ui.ScrollPaneFactory
import com.intellij.ui.SideBorder
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.TreeTableSpeedSearch
import com.intellij.ui.treeStructure.treetable.ListTreeTableModelOnColumns
import com.intellij.ui.treeStructure.treetable.TreeTable
import com.intellij.ui.treeStructure.treetable.TreeTableModel
import com.intellij.ui.components.JBLabel
import com.intellij.util.containers.Convertor
import com.intellij.util.text.DateFormatUtil
import com.intellij.util.ui.ColumnInfo
import com.intellij.util.ui.JBFont
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import com.intellij.util.ui.components.BorderLayoutPanel
import com.intellij.util.ui.tree.TreeUtil
import io.xmake.run.command.RemotePathMapper
import io.xmake.run.analysis.AnalysisService
import io.xmake.run.analysis.AnalysisFrame
import io.xmake.run.analysis.AnalysisIssue
import io.xmake.run.analysis.AnalysisReport
import io.xmake.run.analysis.AnalysisSeverity
import io.xmake.run.analysis.AnalysisStack
import io.xmake.run.tidy.runClangTidy
import io.xmake.utils.notify
import java.awt.Color
import java.awt.Component
import java.awt.FlowLayout
import java.awt.datatransfer.StringSelection
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.io.File
import java.util.Date
import javax.swing.Icon
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.JTable
import javax.swing.JTextArea
import javax.swing.JTree
import javax.swing.KeyStroke
import javax.swing.SwingUtilities
import javax.swing.Timer
import javax.swing.tree.DefaultMutableTreeNode
import javax.swing.tree.TreeNode
import javax.swing.tree.TreePath
import javax.swing.tree.TreeSelectionModel

/**
 * The sanitizer analysis view: the findings of the last run as one navigable tree-table.
 *
 * Issues group by type, identical reports carry a count instead of repeating, every stack
 * frame of every role opens its source line, and the details pane keeps the report's own
 * words for the selected finding - the issue row or any stack row under it. It is the
 * runtime's console report, restructured - the same information, readable without
 * scrolling through interleaved program output.
 *
 * The rows are a [TreeTable]: severity leads the tree column, and type, occurrences, and
 * the source location fill the cells beside it. The Accumulate switch unions every run of
 * this session into one view - findings merge on their signature, occurrences sum, and
 * every row keeps the run that found it so its host paths still map to local files.
 * Stderr lines no parser consumed trail the findings as one Unparsed output group, so a
 * report the parser only partly understood is visible here, not just in the console.
 *
 * Next Finding and Previous Finding walk the rows in their shown order - select, scroll,
 * and open the source line - with Alt+Down / Alt+Up bound while the view holds the focus.
 * The Group-by-File switch re-roots the same rows under the file each finding points into,
 * and the status line names the file the findings hit most.
 *
 * The status line is the VCS commit view's shape: a hairline above, the run's own words
 * on the left (red when the run failed), the counts on the right colored per severity -
 * the commit legend's job, done with severity colors. The bare canvas speaks through the
 * tree's own empty text, with the ways to run - and out of the filter - as its links.
 */
internal class AnalysisPanel(
    private val project: Project,
    parentDisposable: Disposable,
) : SimpleToolWindowPanel(true, true), Disposable {

    private val model = ListTreeTableModelOnColumns(DefaultMutableTreeNode(ROOT), COLUMNS)
    private val treeTable = TreeTable(model)
    private val details = JTextArea().apply {
        isEditable = false
        lineWrap = true
        wrapStyleWord = true
        // A report reads in the UI face, not the editor's monospace: it is prose with
        // file names in it, not code.
        font = JBFont.label()
        // The body shares its "Details" heading's left edge, so the lower half of the
        // view reads as one aligned column instead of three stray indents.
        margin = JBUI.insets(0, 12, 0, 0)
    }

    /** The details pane's heading: the selection's location, so the lower half says which
     *  finding it reports once the table above scrolled away from the row. */
    private val detailsHeading = JBLabel(DETAILS_TITLE).apply {
        border = JBUI.Borders.empty(8, 12, 4, 8)
    }

    /** The component the tool window focuses when it activates: the findings table, so
     *  the keyboard flow - walk the findings, jump to source - starts without a mouse
     *  click first. */
    internal val focusTarget: JComponent get() = treeTable

    /** The status line's left text: the run's description without the counts the legend
     *  carries - the narration tick below rewrites the line without rebuilding the rows. */
    private var statusText: String = ""

    /** The status line's left label: the run's description, its failure note, and the
     *  check-wait narration; red when the shown run failed. */
    private val statusLabel = JBLabel()

    /** The status line's right legend: one colored label per count, rebuilt per refresh
     *  - the commit legend's slot, filled with severity colors instead of file statuses. */
    private val legendPanel = JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(8), 0)).apply {
        isOpaque = false
    }

    /** The bottom row itself: a hairline above, the description on the left, the colored
     *  counts on the right - the VCS commit view's status panel, reporting the run. */
    private val statusPanel = BorderLayoutPanel().apply {
        isOpaque = false
        border = JBUI.Borders.compound(SideBorder(JBColor.border(), SideBorder.TOP), JBUI.Borders.empty(4, 8))
        addToCenter(statusLabel)
        addToRight(legendPanel)
    }

    private var errorsOnly = false

    /** The Accumulate switch: the union of every history run instead of the last run alone. */
    private var accumulate = false

    /** The Group-by-File switch: the findings re-rooted under the file each points into. */
    private var groupByFile = false

    /** Where the next/previous finding steps from when nothing is selected. Every re-show
     *  drops it, so a new report starts navigation over from whatever gets selected. */
    private var navigationCursor = -1

    /**
     * The report the view shows - the latest one by default, an older one after a history
     * pick. It follows every publish; picking from the history only swaps it locally. The
     * accumulated view ignores it and re-derives from the history instead.
     */
    private var displayed: AnalysisReport? = null

    /** The finding rows the view shows now, whatever mode built them; Copy Report reads
     *  them and Next/Previous Finding walk them in this order. */
    private var shownIssues: List<IssueNode> = emptyList()

    /** The narration the status line last carried; the tick rewrites only on a change. */
    private var lastCheckNarration: String = ""

    /**
     * Narrates a queued or running check in the status line. The report only publishes
     * when the run ends, so there is no bus event to ride for the wait itself: this
     * polls the service's cheap claim/process flags on the EDT and rewrites the label
     * only when the wait state flips. Presentation only - it drives no state. The same
     * tick refreshes the toolbar's actions, whose enabled flags (the check's claim, the
     * history's size) flip with no event this panel would otherwise hear.
     */
    private val narrationTimer = Timer(NARRATION_MS) {
        val narration = checkWaitNarration()
        if (narration != lastCheckNarration) {
            lastCheckNarration = narration
            statusLabel.text = statusLineText()
        }
        // Without a pass here the buttons wait for an unrelated event - a finished check
        // leaves Run grey and Stop lit until one comes; this is the panel's own pulse.
        toolbarActions.updateActionsImmediately()
    }

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

    /** One instance each, shared by the toolbar, the popup, and the tree's shortcuts -
     *  registering the shortcut below must not fork the actions it registers. */
    private val nextFindingAction = NextFindingAction()
    private val previousFindingAction = PreviousFindingAction()

    /** The toolbar, held by its interface: the empty text's "Show everything" link turns
     *  the Errors Only filter off and repaints the toggle in the same stroke. */
    private val toolbarActions: ActionToolbar = toolbar()

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
                is UnparsedGroupNode -> append(
                    value.toString(),
                    SimpleTextAttributes.GRAYED_BOLD_ATTRIBUTES,
                )
                is UnparsedLineNode -> append(value.line, SimpleTextAttributes.GRAYED_ATTRIBUTES)
                is FileGroupNode -> append(
                    value.toString(),
                    SimpleTextAttributes.GRAYED_BOLD_ATTRIBUTES,
                )
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
            val node = treeTable.tree.lastSelectedPathComponent as? DefaultMutableTreeNode
            when (node) {
                is IssueNode -> showDetails(renderDetails(node.issue), node.issue)
                // The raw stderr lines a parser never consumed: the details pane is where
                // they read verbatim, the tree row only carries a clipped preview.
                is UnparsedGroupNode -> showDetails(node.lines.joinToString("\n"))
                is UnparsedLineNode -> showDetails(node.line)
                // A stack or frame row shows its finding's report too - the ancestor
                // issue's - so reading the frames never blanks the pane they belong to.
                is DefaultMutableTreeNode -> {
                    val issue = node.owningIssue()?.issue
                    showDetails(issue?.let(::renderDetails), issue)
                }

                else -> showDetails(null)
            }
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
        // Alt+Down / Alt+Up walk the findings wherever the panel holds the focus - the
        // details pane included, so the keyboard flow never starts with a mouse click -
        // while the keys stay free outside the view.
        nextFindingAction.registerCustomShortcutSet(
            CustomShortcutSet(KeyStroke.getKeyStroke("alt DOWN")), this, this
        )
        previousFindingAction.registerCustomShortcutSet(
            CustomShortcutSet(KeyStroke.getKeyStroke("alt UP")), this, this
        )
        // The Problems view's right-click vocabulary: jump to the source, walk the findings,
        // copy the finding.
        PopupHandler.installPopupMenu(
            treeTable,
            DefaultActionGroup(
                JumpToSourceAction(),
                nextFindingAction,
                previousFindingAction,
                Separator.create(),
                CopyFindingAction(),
            ),
            "XMakeAnalysisView",
        )

        toolbar = toolbarActions.component
        // The platform's Problems view: the findings beside the selected one's report,
        // not stacked above it - the bottom tool window is wide and short, and a
        // left/right split gives both halves a readable width. The split is remembered
        // between sessions.
        val split = OnePixelSplitter(false, SPLIT_KEY, 0.62f).apply {
            firstComponent = ScrollPaneFactory.createScrollPane(treeTable, true)
            secondComponent = detailsPanel()
        }
        // The commit view's status line rides under the whole split: it reports the run,
        // not a pane of it.
        setContent(JBUI.Panels.simplePanel().addToCenter(split).addToBottom(statusPanel))

        refresh(AnalysisService.getInstance(project).report)
        project.messageBus.connect(parentDisposable).subscribe(
            AnalysisService.TOPIC,
            AnalysisService.Listener { report ->
                invokeLater { if (!project.isDisposed) refresh(report) }
            },
        )
    }

    /** The narration tick runs only while the tab is on screen: the panel now lives in
     *  the XMake window, and an unselected Analysis tab must not poll on its behalf. */
    override fun addNotify() {
        super.addNotify()
        narrationTimer.start()
    }

    override fun removeNotify() {
        narrationTimer.stop()
        super.removeNotify()
    }

    /** The subscriptions and the narration timer ride on the content's disposal; nothing
     *  else outlives the panel. */
    override fun dispose() {
        narrationTimer.stop()
    }

    private fun toolbar(): ActionToolbar {
        val group = DefaultActionGroup(
            RunClangTidyAction(),
            StopCheckAction(),
            RerunAction(),
            Separator.create(),
            nextFindingAction,
            previousFindingAction,
            Separator.create(),
            HistoryAction(),
            ClearAction(),
            Separator.create(),
            ErrorsOnlyAction(),
            AccumulateAction(),
            GroupByFileAction(),
            autoScroll.createToggleAction(),
            CopyReportAction(),
        )
        return ActionManager.getInstance()
            .createActionToolbar("XMakeAnalysisView", group, true)
            .apply { targetComponent = this@AnalysisPanel }
    }

    private fun detailsPanel(): JComponent = JBUI.Panels.simplePanel().apply {
        addToTop(detailsHeading)
        addToCenter(ScrollPaneFactory.createScrollPane(details, true).apply { border = JBUI.Borders.empty() })
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
        // A failed run leads with it: the status label is one line the narrow tool window
        // elides from the tail, so the failure rides first to survive. Partial findings
        // next to the failure must never read as a clean, finished check; the panel's
        // word for it is "check" - it covers the sanitized runs too; the tool name stays
        // in the balloon alone.
        val failureNote = report.failure?.let { failure -> "check failed: $failure$STATUS_DIVIDER" }.orEmpty()
        setStatus(
            failureNote + "${report.source} on '${report.targetName}'" +
                    topLocationNote(nodes.map(IssueNode::issue)) +
                    unparsedNote(report.unparsed.size),
            legendCounts(report.errorCount, report.warningCount, report.totalFindings, report.issues.size),
            failure = report.failure != null,
        )
        val emptyHint = when {
            nodes.isNotEmpty() -> null
            // A run that died before finishing is not the clean-run state, even when it
            // parsed no findings at all: the failure is the headline, the run the link.
            report.failure != null -> failureHint(report.failure)
            // A published report the parser read fully but that found nothing: not the
            // no-report-at-all state, so it says what the run was, not what to run.
            report.issues.isEmpty() -> EmptyHint("No findings in this run - the target came back clean")
            else -> filteredHint("in this run", report.totalFindings)
        }
        show(nodes, report.unparsed, emptyHint)
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
        // Every run's unrecognized lines, de-duplicated: the same stray output - the
        // output-cap marker, say - repeats across runs that hit the same wall.
        val unparsed = history.flatMap { it.unparsed }.distinct()
        setStatus(
            "Accumulated from ${plural(history.size, "run")}" +
                    topLocationNote(issues) +
                    unparsedNote(unparsed.size),
            legendCounts(
                issues.count { it.severity == AnalysisSeverity.ERROR },
                issues.count { it.severity == AnalysisSeverity.WARNING },
                issues.sumOf(AnalysisIssue::count),
                issues.size,
            ),
        )
        val total = findings.sumOf { it.issue.count }
        val emptyHint = when {
            nodes.isNotEmpty() -> null
            findings.isEmpty() -> EmptyHint("No findings across ${plural(history.size, "run")}")
            else -> filteredHint("across ${plural(history.size, "run")}", total)
        }
        show(nodes, unparsed, emptyHint)
    }

    /** Swaps the rows and the in-canvas empty state; [unparsed] trails the findings as
     *  one group so a report the parser only partly understood is visible here, and
     *  [hint] is what the bare table says when nothing else is left to show. */
    private fun show(nodes: List<IssueNode>, unparsed: List<String>, hint: EmptyHint?) {
        shownIssues = nodes
        navigationCursor = -1
        val root = DefaultMutableTreeNode(ROOT)
        if (groupByFile) fileGroupNodes(nodes).forEach(root::add) else nodes.forEach(root::add)
        if (unparsed.isNotEmpty()) root.add(UnparsedGroupNode(unparsed))
        // The bare canvas speaks through the tree's own StatusText: the state's words,
        // every way out a platform link, not a hand-drawn hint.
        treeTable.emptyText.clear()
        hint?.let { empty ->
            treeTable.emptyText.text = empty.text
            empty.actions.forEach { (label, action) ->
                treeTable.emptyText.appendSecondaryText(
                    label,
                    SimpleTextAttributes.LINK_PLAIN_ATTRIBUTES,
                ) { action() }
            }
        }
        // The model is replaced in place, so column widths, renderers, and the speed
        // search survive; setRoot fires the structure change both the tree and the
        // table's adapter listen for.
        model.setRoot(root)
        // A structure change collapses everything; findings are few, so expand them again.
        TreeUtil.expandAll(treeTable.tree)
        // The swap drops the selection: until a row is picked, the details pane says what
        // it is for instead of keeping the last finding's report on screen.
        if (treeTable.tree.selectionPath == null) showDetails(null)
    }

    private fun showEmpty() {
        shownIssues = emptyList()
        // The guidance lives in the tree's empty text; the status line stays blank until
        // a report - or a check's wait narration - gives it something to say.
        setStatus("", emptyList())
        showDetails(null)
        show(emptyList(), emptyList(), noReportHint())
    }

    /** The status line's whole left text: the description, then the check-wait narration -
     *  the narration's divider dropped when no description stands ahead of it. */
    private fun statusLineText(): String =
        if (statusText.isEmpty()) lastCheckNarration.removePrefix(STATUS_DIVIDER)
        else statusText + lastCheckNarration

    /** The status line's one write path: the left line's text (the narration tick re-adds
     *  the check-wait words) and the legend's counts. The color rule the Packages and
     *  Services panels already follow stands: a red line means the last operation failed. */
    private fun setStatus(description: String, counts: List<Count>, failure: Boolean = false) {
        statusText = description
        lastCheckNarration = checkWaitNarration()
        statusLabel.text = statusLineText()
        statusLabel.foreground = if (failure) ERROR_COLOR else UIUtil.getLabelForeground()
        updateLegend(counts)
    }

    /** The status legend's counts: the error and warning segments only when they count
     *  something - a zero in red reads as an alarm - and the total only when a finding
     *  happened, so a clean run shows no legend at all. */
    private fun legendCounts(errors: Int, warnings: Int, findings: Int, places: Int): List<Count> =
        listOfNotNull(
            Count(plural(errors, "error"), ERROR_COLOR).takeIf { errors > 0 },
            Count(plural(warnings, "warning"), WARNING_COLOR).takeIf { warnings > 0 },
            Count(
                "${plural(findings, "finding")} at ${plural(places, "unique place")}",
                UIUtil.getLabelForeground(),
            ).takeIf { findings > 0 },
        )

    /** Swaps the legend's segments; no counts means no legend, the way the commit legend
     *  hides itself when nothing is included. */
    private fun updateLegend(counts: List<Count>) {
        legendPanel.removeAll()
        counts.forEach { count ->
            legendPanel.add(JBLabel(count.text).apply { foreground = count.color })
        }
        legendPanel.isVisible = counts.isNotEmpty()
        legendPanel.revalidate()
        legendPanel.repaint()
    }

    /** The bare canvas when nothing has run yet: the ways in, clickable - the project
     *  check, and the last sanitized configuration when the session remembers one. */
    private fun noReportHint(): EmptyHint {
        val actions = mutableListOf<Pair<String, () -> Unit>>()
        actions += "Run analysis" to { runClangTidy(project) }
        if (AnalysisService.getInstance(project).lastSanitizedSettings != null) {
            actions += "Run sanitized" to { AnalysisService.getInstance(project).rerunLast() }
        }
        return EmptyHint(NO_FINDINGS, actions)
    }

    /** The bare canvas when the Errors Only filter hides every finding: the words, and
     *  the switch off as the way out. */
    private fun filteredHint(where: String, hidden: Int): EmptyHint = EmptyHint(
        "No errors $where - ${plural(hidden, "finding")} hidden by the Errors Only filter",
        listOf(
            "Show everything" to {
                errorsOnly = false
                refreshView()
                // The toolbar toggle reads this flag on its update pass; the immediate
                // one repaints the lit button instead of waiting for a hover.
                toolbarActions.updateActionsImmediately()
            },
        ),
    )

    /** The bare canvas when the run failed without producing a row: the failure is the
     *  headline, the check the way out. */
    private fun failureHint(failure: String): EmptyHint = EmptyHint(
        "Check failed: $failure",
        listOf("Run analysis" to { runClangTidy(project) }),
    )

    /** The wait narration for a queued or running check, mirroring the Services panel's
     *  lane-wait wording: a check rides the same serialized lane as builds, so one
     *  queued behind a long build must not read as a dead panel. Empty when the lane
     *  carries no check. */
    private fun checkWaitNarration(): String {
        val service = AnalysisService.getInstance(project)
        return when {
            service.isCheckRunning -> "${STATUS_DIVIDER}check running"
            service.isCheckQueued -> "${STATUS_DIVIDER}check queued on the xmake lane"
            else -> ""
        }
    }

    /** The summary's tail for the stderr lines no parser consumed; empty for a fully
     *  parsed run, so an under-reported finding count is never silent about why. */
    private fun unparsedNote(count: Int): String =
        if (count == 0) "" else "${STATUS_DIVIDER}${plural(count, "unparsed line")}"

    /** The summary's "where it mostly happens" tail, over the rows shown: the file whose
     *  findings occur most often, by name since the Location column carries the full path.
     *  Empty when no shown finding symbolizes to a source file. */
    private fun topLocationNote(issues: List<AnalysisIssue>): String {
        val top = issues.groupBy { it.primaryFrame?.file }
            .filterKeys { it != null }
            .maxByOrNull { (_, group) -> group.sumOf(AnalysisIssue::count) }
            ?: return ""
        val file = top.key ?: return ""
        return "${STATUS_DIVIDER}most in ${File(file).name} " +
                "(${plural(top.value.sumOf(AnalysisIssue::count), "finding")})"
    }

    /** The findings re-rooted under one node per file - the file each finding's primary
     *  frame names, alphabetically; findings without a symbolized file gather under their
     *  own group at the end. Within a file the incoming severity-first order stays, so
     *  grouping only changes where a finding sits, never which findings show. */
    private fun fileGroupNodes(nodes: List<IssueNode>): List<FileGroupNode> {
        val placed = nodes.mapNotNull { node ->
            node.issue.primaryFrame?.file?.let { file -> file to node }
        }.groupBy({ (file, _) -> file }, { (_, node) -> node })
        val unplaced = nodes.filter { it.issue.primaryFrame?.file == null }
        val groups = placed.toSortedMap().mapTo(mutableListOf()) { (file, findings) ->
            FileGroupNode(file, findings)
        }
        if (unplaced.isNotEmpty()) groups += FileGroupNode(NO_FILE, unplaced)
        return groups
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

    /**
     * Steps to the next (or previous) finding in the shown order and opens its source.
     * The step starts from the selected finding - a stack or frame selection counts for
     * the issue row it hangs under - else from the stored cursor; with neither, forward
     * starts at the first row and backward at the last. The order is [shownIssues]'s,
     * whatever the grouping put where: navigation always reads severity-first.
     */
    private fun stepFinding(step: Int) {
        if (shownIssues.isEmpty()) return
        val from = selectedIssueNode()?.let(shownIssues::indexOf) ?: navigationCursor
        val target = if (from < 0) {
            if (step > 0) 0 else shownIssues.size - 1
        } else {
            (from + step).mod(shownIssues.size)
        }
        navigationCursor = target
        reveal(shownIssues[target])
    }

    /** The finding row the selection belongs to: the issue itself, or its ancestor. */
    private fun selectedIssueNode(): IssueNode? =
        (treeTable.tree.lastSelectedPathComponent as? DefaultMutableTreeNode)?.owningIssue()

    /** Puts one finding row on screen: expands to it, selects it on the tree and the table
     *  together (the platform's own sync, [TreeTable.addSelectedPath]), scrolls it into
     *  view, and opens the source line the finding names. */
    private fun reveal(node: IssueNode) {
        val path = TreePath(node.path)
        treeTable.tree.expandPath(path)
        treeTable.clearSelection()
        treeTable.addSelectedPath(path)
        val row = treeTable.selectedRow
        if (row >= 0) {
            treeTable.scrollRectToVisible(treeTable.getCellRect(row, COLUMN_SEVERITY, true))
        }
        navigateSelection()
    }

    /** Opens the source line one frame names, mapping host paths to local files first;
     *  a path that resolves to no local file says so in a balloon - a silent no-op would
     *  read as a dead click. */
    private fun navigate(frame: AnalysisFrame, report: AnalysisReport?) {
        val file = frame.file ?: return
        val local = resolveLocalFile(file, report)
        val virtual = local?.let { LocalFileSystem.getInstance().refreshAndFindFileByIoFile(it) }
        if (virtual == null) {
            project.notify(
                "Cannot locate the source file",
                "${StringUtil.escapeXmlEntities(file)} - the remote path is not mapped to a local file",
                NotificationType.WARNING,
            )
            return
        }
        OpenFileDescriptor(project, virtual, (frame.line ?: 1) - 1, (frame.column ?: 1) - 1).navigate(true)
    }

    /** Host form first, then project-relative, then as-is - the shared candidate ladder in
     *  [RemotePathMapper.localCandidates], first existing file wins. The mapping comes from
     *  the report that found the finding, so accumulated rows and history picks alike
     *  resolve against the run that produced them. */
    private fun resolveLocalFile(file: String, report: AnalysisReport?): File? {
        val mapper = report?.toolkit?.let { toolkit ->
            report.hostWorkingDirectory?.let {
                RemotePathMapper.forToolkit(toolkit, it, project)
            }
        }
        return RemotePathMapper.localCandidates(mapper, File(file).toPath(), project)
            .firstOrNull { candidate -> candidate.toFile().isFile }
            ?.toFile()
    }

    /** The details pane's one write path: a finding's own words, or - [text] null, when
     *  nothing is selected - the gray placeholder that names what the pane is for. The
     *  heading follows the selection: the finding's file and line while a row is chosen,
     *  the bare title otherwise. */
    private fun showDetails(text: String?, issue: AnalysisIssue? = null) {
        detailsHeading.text = issue?.primaryFrame?.let { frame ->
            frame.file?.let { file ->
                "Details - ${File(file).name}${frame.line?.let { line -> ":$line" } ?: ""}"
            }
        } ?: DETAILS_TITLE
        if (text == null) {
            details.text = DETAILS_HINT
            details.foreground = SimpleTextAttributes.GRAYED_ATTRIBUTES.fgColor
        } else {
            details.text = text
            details.foreground = UIUtil.getLabelForeground()
        }
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
        "Run the Clang-Tidy check on the project (xmake check clang.tidy)",
        AllIcons.Actions.Execute,
    ) {

        override fun getActionUpdateThread() = ActionUpdateThread.EDT

        override fun update(event: AnActionEvent) {
            // One check at a time, queued or running: the claim is taken at the click, so
            // the disabled button covers the whole wait - the lane may sit behind a build.
            val project = event.project
            event.presentation.isEnabled =
                project != null && !AnalysisService.getInstance(project).isCheckActive
        }

        override fun actionPerformed(event: AnActionEvent) {
            runClangTidy(event.project ?: return)
        }
    }

    /** The check's off switch: cancels a check still queued on the lane, and kills the
     *  process of one already running - its partial findings still publish. */
    private inner class StopCheckAction : DumbAwareAction(
        "Stop",
        "Stop the queued or running check",
        AllIcons.Actions.Suspend,
    ) {

        override fun getActionUpdateThread() = ActionUpdateThread.EDT

        override fun update(event: AnActionEvent) {
            event.presentation.isEnabled = AnalysisService.getInstance(project).isCheckActive
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

    private inner class ClearAction :
        DumbAwareAction("Clear", "Clear the current findings and the run history", AllIcons.Actions.GC) {

        override fun getActionUpdateThread() = ActionUpdateThread.EDT

        override fun update(event: AnActionEvent) {
            // Nothing to wipe, nothing to ask about: the button grays out on an empty
            // view instead of confirming a no-op.
            val service = AnalysisService.getInstance(project)
            event.presentation.isEnabled = service.report != null || service.history.isNotEmpty()
        }

        override fun actionPerformed(event: AnActionEvent) {
            // The wipe takes the session with it - the history can hold a whole debugging
            // afternoon - so the click asks, the way the repositories' clear does.
            if (Messages.showYesNoDialog(
                    project,
                    "Clear the current findings and the whole run history?",
                    "Clear Analysis Findings",
                    Messages.getQuestionIcon(),
                ) != Messages.YES
            ) return
            AnalysisService.getInstance(project).clearAll()
        }
    }

    /**
     * The session's reports, newest first; picking one only changes what the view shows.
     * While the accumulated union is shown the picker steps aside: the union already
     * contains every run, and a single report would contradict it.
     */
    private inner class HistoryAction : ComboBoxAction() {

        /** The combo the toolbar built: its tooltip tracks the run a pick shows, since
         *  the button's word stays "History" - a timestamp that grows and shrinks with
         *  every publish shakes the toolbar, and the view's own rule is a lit button,
         *  not a text swap. */
        private var combo: JComponent? = null

        // The picker is the panel's one toolbar control without an action description, so
        // its tooltip rides on the combo itself - same fragment style as the descriptions.
        override fun createCustomComponent(presentation: Presentation, place: String): JComponent =
            super.createCustomComponent(presentation, place)
                .apply { toolTipText = "Pick an earlier run to display" }
                .also { combo = it }

        override fun getActionUpdateThread() = ActionUpdateThread.EDT

        override fun update(event: AnActionEvent) {
            super.update(event)
            val history = AnalysisService.getInstance(project).history
            event.presentation.isEnabled = history.isNotEmpty() && !accumulate
            event.presentation.text = "History"
            combo?.toolTipText = if (!accumulate) {
                displayed?.let { "History: ${historyLabel(it)}" } ?: "Pick an earlier run to display"
            } else {
                "Pick an earlier run to display"
            }
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

    /** The severity filter's switch, selected while the rows show errors only. A
     *  ToggleAction so the toolbar itself carries the state - a lit button says what the
     *  mode is, instead of a text swap that names the next action. */
    private inner class ErrorsOnlyAction : DumbAwareToggleAction(
        "Errors Only",
        "Show only the errors, or everything again",
        AllIcons.General.Filter,
    ) {

        override fun getActionUpdateThread() = ActionUpdateThread.EDT

        override fun isSelected(event: AnActionEvent): Boolean = errorsOnly

        override fun setSelected(event: AnActionEvent, state: Boolean) {
            errorsOnly = state
            refreshView()
        }
    }

    /** The accumulated view's switch, selected while every run's findings are unioned. */
    private inner class AccumulateAction : DumbAwareToggleAction(
        "Accumulate",
        "Show the union of every run's findings, or the last run alone again",
        AllIcons.Actions.GroupBy,
    ) {

        override fun getActionUpdateThread() = ActionUpdateThread.EDT

        override fun isSelected(event: AnActionEvent): Boolean = accumulate

        override fun setSelected(event: AnActionEvent, state: Boolean) {
            accumulate = state
            refreshView()
        }
    }

    /** The Group-by-File switch: one tree node per file the findings point into, the
     *  findings beneath it, instead of the flat severity-first list. Presentation only -
     *  the row order navigation follows and Accumulate's sums are untouched. */
    private inner class GroupByFileAction : DumbAwareToggleAction(
        "Group by File",
        "Group the findings under the file each points into, or flat again",
        AllIcons.Actions.GroupByFile,
    ) {

        override fun getActionUpdateThread() = ActionUpdateThread.EDT

        override fun isSelected(event: AnActionEvent): Boolean = groupByFile

        override fun setSelected(event: AnActionEvent, state: Boolean) {
            groupByFile = state
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

    /** Walks the findings forward: each step selects the next finding row, scrolls to it,
     *  and opens its source, so a report reads without reaching for the mouse row by row. */
    private inner class NextFindingAction : DumbAwareAction(
        "Next Finding",
        "Go to the next finding and open its source line",
        AllIcons.Actions.NextOccurence,
    ) {

        override fun getActionUpdateThread() = ActionUpdateThread.EDT

        override fun update(event: AnActionEvent) {
            event.presentation.isEnabled = shownIssues.isNotEmpty()
        }

        override fun actionPerformed(event: AnActionEvent) {
            stepFinding(1)
        }
    }

    /** Walks the findings backward; the counterpart of [NextFindingAction]. */
    private inner class PreviousFindingAction : DumbAwareAction(
        "Previous Finding",
        "Go to the previous finding and open its source line",
        AllIcons.Actions.PreviousOccurence,
    ) {

        override fun getActionUpdateThread() = ActionUpdateThread.EDT

        override fun update(event: AnActionEvent) {
            event.presentation.isEnabled = shownIssues.isNotEmpty()
        }

        override fun actionPerformed(event: AnActionEvent) {
            stepFinding(-1)
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
                "${report.source} - ${plural(report.totalFindings, "finding")}"

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

    /** One file-group row of the Group-by-File view: the file its findings point into,
     *  labeled with how many occurrences the group carries. Its children are issue rows. */
    private class FileGroupNode(val file: String, findings: List<IssueNode>) :
        DefaultMutableTreeNode(file) {
        private val occurrenceCount = findings.sumOf { it.issue.count }

        init {
            findings.forEach(::add)
        }

        override fun toString(): String = "$file - ${plural(occurrenceCount, "finding")}"
    }

    private class FrameNode(val frame: AnalysisFrame) : DefaultMutableTreeNode(frame) {
        override fun toString(): String = frame.function
    }

    /** The findings tree's last row: the stderr lines no parser consumed, grouped so a
     *  partly unread report or a dropped output tail shows up in the view instead of
     *  quietly shrinking the finding count. Each child keeps one line verbatim. */
    private class UnparsedGroupNode(val lines: List<String>) :
        DefaultMutableTreeNode("Unparsed output - ${plural(lines.size, "line")}") {

        override fun toString(): String = "Unparsed output - ${plural(lines.size, "line")}"
    }

    /** One stderr line no parser consumed, exactly as the run emitted it. */
    private class UnparsedLineNode(val line: String) : DefaultMutableTreeNode(line) {

        override fun toString(): String = line
    }

    /** One accumulated row: the newest sighting's finding, its occurrences summed over the runs. */
    private class MergedFinding(val issue: AnalysisIssue, val source: AnalysisReport)

    /** What the bare table says when no rows show: the words, plus each clickable way
     *  out as a label-action pair, rendered as the tree's StatusText links. */
    private class EmptyHint(
        val text: String,
        val actions: List<Pair<String, () -> Unit>> = emptyList(),
    )

    /** One count of the status legend: the number-unit wording and the color it shows in. */
    private class Count(val text: String, val color: Color)

    private companion object {

        /** The splitter's persistence key; the user's split survives restarts. */
        private const val SPLIT_KEY = "XMakeAnalysisView"

        /** The invisible tree root; the panel swaps it per refresh. */
        private const val ROOT = "root"

        /** The Group-by-File heading of findings the parser could not place in any file. */
        private const val NO_FILE = "Unknown location"

        /** The no-report canvas's words; the links beside it name the ways to run. */
        private const val NO_FINDINGS = "No findings yet"

        /** The status line's divider between its summary notes - a middle dot, since the
         *  words it separates (paths, failure messages) carry dashes of their own. */
        private const val STATUS_DIVIDER = " · "

        /** The details pane's words when no row is selected: what the pane is for. */
        private const val DETAILS_HINT = "Select a finding to see its report"

        /** The details heading's bare form; the selection adds the finding's location. */
        private const val DETAILS_TITLE = "Details"

        /** The legend's severity colors: the platform's label error/warning foregrounds,
         *  theme-aware like the commit legend's own file-status attributes. */
        private val ERROR_COLOR: Color = SimpleTextAttributes.ERROR_ATTRIBUTES.fgColor
        private val WARNING_COLOR: Color = JBColor.namedColor("Label.warningForeground", 0xA46704, 0xBA9752)

        /** How often the summary's check-wait narration polls the service's flags. */
        private const val NARRATION_MS = 500

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

        /** The count's phrase - "1 finding", "2 findings": the number decides the plural,
         *  so no "(s)" of machine-assembled text shows up in the panel's words. */
        private fun plural(count: Int, word: String): String = "$count $word${if (count == 1) "" else "s"}"

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
