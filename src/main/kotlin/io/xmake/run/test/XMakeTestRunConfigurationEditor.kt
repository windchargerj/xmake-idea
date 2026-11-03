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
package io.xmake.run.test

import com.intellij.notification.NotificationType
import com.intellij.openapi.options.SettingsEditor
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.util.text.StringUtil
import com.intellij.ui.CheckboxTree
import com.intellij.ui.CheckboxTreeBase.CheckPolicy
import com.intellij.ui.CheckedTreeNode
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextField
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.panel
import com.intellij.util.execution.ParametersListUtil
import io.xmake.run.command.xmakeCommands
import io.xmake.run.command.xmakeExecutor
import io.xmake.run.target.activeOrSingleXMakeBuildProfile
import io.xmake.utils.notify
import io.xmake.utils.ui.coroutineScope
import io.xmake.utils.ui.uiEdt
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.awt.Dimension
import javax.swing.JComponent
import javax.swing.JTree
import javax.swing.tree.DefaultMutableTreeNode

/** The test run's own options: the filters the action takes and its failure output. */
internal class XMakeTestRunConfigurationEditor : SettingsEditor<XMakeTestRunConfiguration>() {

    private val testFilters = JBTextField().apply { emptyText.text = "test_*  or  -g quick" }
    private val outputOnFailure = JBCheckBox("Print the output of failing tests")

    /** The project behind the configuration being edited; a browse before any reset has
     *  nothing to query and stays silent. */
    private var editedProject: Project? = null

    /** The listing runs an xmake query, so it starts off the EDT the way the run editor's
     *  driver detection does: one cancellable job at a time, landing on the EDT under any
     *  open modal dialog - this editor lives inside one. */
    private val queryScope = coroutineScope()
    private var queryJob: Job? = null

    override fun resetEditorFrom(configuration: XMakeTestRunConfiguration) {
        editedProject = configuration.project
        testFilters.text = configuration.testFilters
        outputOnFailure.isSelected = configuration.outputOnFailure
    }

    override fun applyEditorTo(configuration: XMakeTestRunConfiguration) {
        configuration.testFilters = testFilters.text.trim()
        configuration.outputOnFailure = outputOnFailure.isSelected
    }

    override fun createEditor(): JComponent = panel {
        row("Test filters:") {
            cell(testFilters).align(AlignX.FILL)
                .comment("Group and name patterns passed to xmake test, e.g. test_* or -g quick")
            button("Browse...") { browseTests() }
        }
        row {
            cell(outputOnFailure)
        }
    }

    /** Lists the project's tests and offers them for picking; a confirmed pick folds into
     *  the filter field on top of what is already there. */
    private fun browseTests() {
        val project = editedProject
        if (project == null || project.isDisposed) return
        val profile = project.activeOrSingleXMakeBuildProfile
        if (profile == null) {
            project.notify("Select an XMake build profile before picking tests")
            return
        }
        queryJob?.cancel()
        queryJob = queryScope.launch {
            val output = try {
                project.xmakeExecutor.submit {
                    val query = project.xmakeCommands(profile).createTestListQuery()
                    project.xmakeExecutor.captureStandardOutput(query)
                }.await()
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (error: Exception) {
                project.notify(
                    "Failed to list the project's tests",
                    StringUtil.escapeXmlEntities(error.message ?: error.javaClass.simpleName),
                )
                return@launch
            }
            val entries = parseTestEntries(output)
            if (entries.isEmpty()) {
                project.notify(
                    "No tests were found in this project",
                    "Type test names into the filter field, or check that the project's targets define tests.",
                    NotificationType.WARNING,
                )
                return@launch
            }
            withContext(uiEdt) { pickTests(project, entries) }
        }
    }

    private fun pickTests(project: Project, entries: List<XMakeTestEntry>) {
        val dialog = XMakeTestSelectionDialog(project, entries)
        dialog.show()
        if (!dialog.isOK()) return
        val chosen = dialog.selectedFilters()
        if (chosen.isEmpty()) return
        // The run parses the field the same way, so the merge keeps its canonical form
        // and a test picked twice never lands in the field twice.
        val kept = ParametersListUtil.parse(testFilters.text.trim())
        testFilters.text = (kept + chosen).distinct().joinToString(" ")
    }
}

/** One test the enumeration script reported: the target that carries it, its own name,
 *  and the group xmake filters by, empty when neither the test nor the target set one. */
private data class XMakeTestEntry(val target: String, val test: String, val group: String) {
    val name: String
        get() = "$target/$test"
}

/** Reads the enumeration script's output. Its lines are `target/test<TAB>group`, and the
 *  tab is the discriminator: nothing else xmake prints into a query's output carries one. */
private fun parseTestEntries(output: String): List<XMakeTestEntry> =
    output.lineSequence().mapNotNull(::parseTestEntry).toList()

private fun parseTestEntry(line: String): XMakeTestEntry? {
    val parts = line.trim().split('\t', limit = 2)
    val name = parts.firstOrNull().orEmpty()
    if (parts.size != 2 || !name.contains('/') || name.any { it.isWhitespace() }) return null
    val target = name.substringBefore('/')
    val test = name.substringAfter('/')
    if (target.isEmpty() || test.isEmpty()) return null
    return XMakeTestEntry(target, test, parts[1].trim())
}

/** The test picker: one parent per target, one child per test, checked states propagating
 *  between them. A checked target means every test of it and is written back as one
 *  whole-target pattern, the target name with a `*` wildcard; anything short of that
 *  writes each checked test under its full name. */
private class XMakeTestSelectionDialog(
    project: Project,
    entries: List<XMakeTestEntry>,
) : DialogWrapper(project, true) {

    private val root = CheckedTreeNode("tests").apply {
        entries.groupBy(XMakeTestEntry::target).forEach { (target, targetTests) ->
            add(CheckedTreeNode(target).apply {
                targetTests.forEach { add(CheckedTreeNode(it)) }
            })
        }
    }

    private val tree = CheckboxTree(
        object : CheckboxTree.CheckboxTreeCellRenderer() {
            override fun customizeRenderer(
                tree: JTree,
                value: Any,
                selected: Boolean,
                expanded: Boolean,
                leaf: Boolean,
                row: Int,
                hasFocus: Boolean,
            ) {
                when (val item = (value as? DefaultMutableTreeNode)?.userObject) {
                    is String -> textRenderer.append(item)
                    is XMakeTestEntry -> {
                        textRenderer.append(item.test)
                        if (item.group.isNotEmpty()) {
                            textRenderer.append("  (${item.group})", SimpleTextAttributes.GRAYED_ATTRIBUTES)
                        }
                    }
                }
            }
        },
        root,
        // Checking a target selects its every test, and a fully selected target collapses
        // back into one whole-target pattern; the default policy leaves parents unchecked.
        CheckPolicy.PROPAGATE_EVERYTHING_POLICY,
    ).apply { isRootVisible = false }

    init {
        title = "Select Tests"
        init()
    }

    override fun createCenterPanel(): JComponent = JBScrollPane(tree).apply {
        preferredSize = dialogSize
    }

    fun selectedFilters(): List<String> = buildList {
        for (index in 0 until root.childCount) {
            val targetNode = root.getChildAt(index) as? CheckedTreeNode ?: continue
            val target = targetNode.userObject as? String ?: continue
            val tests = (0 until targetNode.childCount)
                .mapNotNull { targetNode.getChildAt(it) as? CheckedTreeNode }
            if (targetNode.isChecked || tests.isNotEmpty() && tests.all { it.isChecked }) {
                add("$target/*")
            } else {
                tests.filter { it.isChecked }.forEach { node ->
                    (node.userObject as? XMakeTestEntry)?.let { add(it.name) }
                }
            }
        }
    }

    private companion object {
        val dialogSize = Dimension(420, 320)
    }
}
