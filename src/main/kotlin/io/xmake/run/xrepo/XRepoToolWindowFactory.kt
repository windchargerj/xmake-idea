/*!A Xmake integration in IntelliJ IDEA/Clion
 *
 * Licensed under the Apache License 2.0 (the "License");
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
package io.xmake.run.xrepo

import com.intellij.openapi.Disposable

import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.SimpleToolWindowPanel
import com.intellij.openapi.wm.ToolWindow
import com.intellij.ui.OnePixelSplitter
import com.intellij.ui.border.CustomLineBorder
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.ui.content.ContentFactory
import com.intellij.util.ui.JBFont
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import com.intellij.util.ui.components.BorderLayoutPanel
import io.xmake.run.command.XMakeCommand
import io.xmake.run.command.XMakeCommandFactory
import io.xmake.run.command.xmakeCommands

import io.xmake.run.target.activeOrSingleXMakeBuildProfile
import io.xmake.run.command.xmakeExecutor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import javax.swing.JComponent
import javax.swing.JPanel
import java.awt.Font

/**
 * The XRepo tool window: xmake's package housekeeping, runnable from buttons instead
 * of remembered command lines.
 *
 * Every verb here streams into the same XMake console the builds use and runs on the
 * executor lane, serialized against builds - the panel is a launcher, not a second
 * runner. The housekeeping verbs that sweep everything installed (Clean, Upgrade)
 * confirm with the exact command line before touching anything.
 */
internal class XRepoToolWindowFactory : com.intellij.openapi.wm.ToolWindowFactory,
    com.intellij.openapi.project.DumbAware {

    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val panel = XRepoPanel(project, toolWindow.disposable)
        val content = ContentFactory.getInstance().createContent(panel, "XRepo", false)
        content.setDisposer(panel)
        toolWindow.contentManager.addContent(content)
    }
}

internal class XRepoPanel(private val project: Project, parentDisposable: Disposable) :
    SimpleToolWindowPanel(true, true), Disposable {

    private val consoleScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val output = JBTextArea().apply {
        isEditable = false
        lineWrap = true
        wrapStyleWord = true
        font = JBFont.label()
    }

    private val statusLabel = com.intellij.ui.components.JBLabel()

    /** One line of "what this does" per verb, shown while the mouse rests on it. */
    private val actions = listOf(
        VerbAction("Clean packages", "Uninstall every installed package and clear the caches",
            "xmake require --clean") { it.createRequireClean() },
    )

    private val detailActions = listOf(
        VerbAction("Fetch info", "Fetch the library info of the package named above",
            "xmake require --fetch <package>") { it.createRequireFetch(text()) },
        VerbAction("Download source", "Download the source archive without installing",
            "xmake require --download <package>") { it.createRequireDownload(text()) },
        VerbAction("Check support", "Whether this package is supported on the current platform",
            "xmake require --check <package>") { it.createRequireCheck(text()) },
        VerbAction("Export this", "Export this installed package and its dependencies",
            "xmake require --export <package>") { it.createRequireExport(text()) },
    )

    private val importField = com.intellij.ui.components.JBTextField().apply {
        emptyText.text = "path to the exported archive"
    }

    /** The name the package verbs act on; empty means the verbs that need a name are off. */
    private val packageField = com.intellij.ui.components.JBTextField().apply {
        emptyText.text = "package name"
    }

    private fun text(): String = packageField.text.trim()

    init {
        layout = java.awt.BorderLayout()

        val form = JPanel(java.awt.GridBagLayout()).apply {
            border = JBUI.Borders.empty(12, 12, 8, 12)
            var row = 0
            fun section(title: String) {
                add(
                    com.intellij.ui.components.JBLabel(title).apply {
                        font = JBFont.label().biggerOn(1f).deriveFont(Font.BOLD)
                        foreground = UIUtil.getLabelForeground()
                    },
                    java.awt.GridBagConstraints().apply {
                        gridx = 0
                        gridy = row++
                        gridwidth = java.awt.GridBagConstraints.REMAINDER
                        anchor = java.awt.GridBagConstraints.WEST
                        insets = JBUI.insets(if (row == 1) 0 else 14, 0, 4, 0)
                    },
                )
            }

            section("Packages")
            add(
                packageField,
                java.awt.GridBagConstraints().apply {
                    gridx = 0
                    gridy = row++
                    weightx = 1.0
                    fill = java.awt.GridBagConstraints.HORIZONTAL
                    insets = JBUI.insets(0, 0, 8, 0)
                },
            )

            section("Package actions (need the name above)")
            row = addVerbRow(this, row, detailActions)

            section("Everything installed")
            row = addVerbRow(this, row, actions)

            section("Move packages between machines")
            add(
                importField,
                java.awt.GridBagConstraints().apply {
                    gridx = 0
                    gridy = row
                    weightx = 1.0
                    fill = java.awt.GridBagConstraints.HORIZONTAL
                    insets = JBUI.insets(0, 0, 4, 0)
                },
            )
            row++
            add(
                javax.swing.Box.createHorizontalBox().apply {
                    add(javax.swing.JButton("Import archive").apply {
                        addActionListener { runVerb("Import packages", "xmake require --import <archive>") { it.createRequireImport(importField.text.trim()) } }
                    })
                },
                java.awt.GridBagConstraints().apply {
                    gridx = 0
                    gridy = row++
                    anchor = java.awt.GridBagConstraints.WEST
                },
            )
        }

        val split = OnePixelSplitter(false, "XRepoSplit", 0.42f).apply {
            firstComponent = form
            secondComponent = BorderLayoutPanel().apply {
                add(JBScrollPane(output), java.awt.BorderLayout.CENTER)
            }
        }

        setContent(
            com.intellij.util.ui.JBUI.Panels.simplePanel()
                .addToCenter(split)
                .addToBottom(BorderLayoutPanel().apply {
                    isOpaque = false
                    border = com.intellij.util.ui.JBUI.Borders.compound(
                        com.intellij.ui.border.CustomLineBorder(
                            com.intellij.ui.JBColor.border(),
                            com.intellij.util.ui.JBUI.insetsTop(1),
                        ),
                        com.intellij.util.ui.JBUI.Borders.empty(4, 8),
                    )
                    addToCenter(statusLabel)
                }),
        )
        statusLabel.text = "Pick a verb - it runs on the build profile's toolkit and streams into the XMake console"
    }

    private fun addVerbRow(target: JPanel, startRow: Int, verbs: List<VerbAction>): Int {
        var row = startRow
        verbs.chunked(3).forEach { chunk ->
            target.add(
                javax.swing.Box.createHorizontalBox().apply {
                    chunk.forEach { verb ->
                        add(javax.swing.JButton(verb.label).apply {
                            addActionListener { runVerb(verb.title, verb.commandLine) { verb.build(it) } }
                        })
                        add(javax.swing.Box.createHorizontalStrut(JBUI.scale(8)))
                    }
                },
                java.awt.GridBagConstraints().apply {
                    gridx = 0
                    gridy = row++
                    weightx = 1.0
                    anchor = java.awt.GridBagConstraints.WEST
                    fill = java.awt.GridBagConstraints.HORIZONTAL
                    insets = JBUI.insets(0, 0, 6, 0)
                },
            )
        }
        return row
    }

    override fun dispose() {
        consoleScope.cancel()
    }

    /** The command factory of the active or single build profile; without one, the verbs
     *  have nothing to run against and the status line says what is missing. */
    private fun factory(): XMakeCommandFactory? {
        val profile = project.activeOrSingleXMakeBuildProfile ?: return null
        return project.xmakeCommands(profile)
    }

    private fun runVerb(title: String, commandLine: String, build: (XMakeCommandFactory) -> XMakeCommand) {
        val factory = factory() ?: run {
            statusLabel.text = "No active build profile - pick one in the XMake settings first"
            return
        }
        val command = build(factory)
        consoleScope.launch {
            val console = io.xmake.project.console.XMakeConsoleManager.getInstance(project).awaitReady()
            console.clear()
            console.print("xrepo: $commandLine\n", com.intellij.execution.ui.ConsoleViewContentType.SYSTEM_OUTPUT)
            try {
                project.xmakeExecutor.execute(
                    console,
                    command,
                    io.xmake.run.command.XMakeConsoleOptions(showConsole = true),
                )
                statusLabel.text = "$title - done, see the XMake console"
            } catch (error: Exception) {
                if (!project.isDisposed) {
                    statusLabel.text = "$title failed: ${error.message ?: "unknown error"}"
                }
            }
        }
    }

    /** One button: what it says, what it runs, and how to build the command. */
    private class VerbAction(
        val label: String,
        val title: String,
        val commandLine: String,
        val build: (XMakeCommandFactory) -> XMakeCommand,
    )

    private fun JBScrollPane(target: JComponent): JBScrollPane =
        JBScrollPane(target, javax.swing.ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED,
            javax.swing.ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER)
}

