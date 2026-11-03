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
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.SimpleToolWindowPanel
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.ui.ColoredTreeCellRenderer
import com.intellij.ui.ScrollPaneFactory
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.TreeSpeedSearch
import com.intellij.ui.treeStructure.Tree
import com.intellij.util.ui.JBUI
import io.xmake.run.command.RemotePathMapper
import io.xmake.run.analysis.AnalysisService
import io.xmake.run.analysis.AnalysisFrame
import io.xmake.run.analysis.AnalysisIssue
import io.xmake.run.analysis.AnalysisReport
import io.xmake.run.analysis.AnalysisSeverity
import io.xmake.run.analysis.AnalysisStack
import java.awt.datatransfer.StringSelection
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.io.File
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
    }

    private var errorsOnly = false

    init {
        tree.isRootVisible = false
        tree.selectionModel.selectionMode = TreeSelectionModel.SINGLE_TREE_SELECTION
        tree.cellRenderer = Renderer()
        tree.model = treeModel
        TreeSpeedSearch.installOn(tree, true) { path -> path.lastPathComponent.toString() }
        tree.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(event: MouseEvent) {
                if (event.clickCount == 2) navigateSelection()
            }
        })
        tree.addTreeSelectionListener {
            val node = tree.lastSelectedPathComponent as? IssueNode
            details.text = node?.issue?.let(::renderDetails).orEmpty()
        }

        toolbar = toolbar().component
        setContent(JBUI.Panels.simplePanel().apply {
            addToTop(summaryLabel.apply { border = JBUI.Borders.empty(4, 8) })
            addToCenter(ScrollPaneFactory.createScrollPane(tree, true))
            addToBottom(detailsPanel())
        })

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

    private fun toolbar(): ActionToolbar {        val group = DefaultActionGroup(RerunAction(), ClearAction(), ErrorsOnlyAction(), CopyReportAction())
        return ActionManager.getInstance()
            .createActionToolbar("XMakeSanitizerView", group, true)
            .apply { targetComponent = this@AnalysisPanel }
    }

    private fun detailsPanel(): JComponent = JBUI.Panels.simplePanel().apply {
        preferredSize = JBUI.size(0, 140)
        addToTop(JLabel("Details").apply { border = JBUI.Borders.empty(4, 8) })
        addToCenter(ScrollPaneFactory.createScrollPane(details, true))
    }

    /** Rebuilds the tree from [report]; null shows the empty hint instead. */
    fun refresh(report: AnalysisReport?) {
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
        val node = tree.lastSelectedPathComponent ?: return
        val frame = when (node) {
            is FrameNode -> node.frame
            is IssueNode -> node.issue.primaryFrame
            else -> null
        } ?: return
        navigate(frame)
    }

    /** Opens the source line one frame names, mapping host paths to local files first. */
    private fun navigate(frame: AnalysisFrame) {
        val file = frame.file ?: return
        val local = resolveLocalFile(file) ?: return
        val virtual = LocalFileSystem.getInstance().refreshAndFindFileByIoFile(local) ?: return
        OpenFileDescriptor(project, virtual, (frame.line ?: 1) - 1, (frame.column ?: 1) - 1).navigate(true)
    }

    /** Host form first, then project-relative, matching how the toolchain compiles sources. */
    private fun resolveLocalFile(file: String): File? {
        val report = AnalysisService.getInstance(project).report
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

        override fun actionPerformed(event: AnActionEvent) {
            AnalysisService.getInstance(project).publish(null)
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
            refresh(AnalysisService.getInstance(project).report)
        }
    }

    private inner class CopyReportAction : DumbAwareAction(
        "Copy Report",
        "Copy the findings as text",
        AllIcons.Actions.Copy,
    ) {

        override fun actionPerformed(event: AnActionEvent) {
            val report = AnalysisService.getInstance(project).report ?: return
            val text = report.issues.joinToString("\n\n", postfix = "\n") { renderDetails(it) }
            CopyPasteManager.getInstance().setContents(StringSelection(text))
        }
    }

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
