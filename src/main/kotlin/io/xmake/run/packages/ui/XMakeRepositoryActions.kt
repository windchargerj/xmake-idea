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
package io.xmake.run.packages.ui

import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionToolbar
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.actionSystem.Separator
import com.intellij.openapi.application.EDT
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.util.text.StringUtil
import io.xmake.project.console.XMakeConsoleManager
import io.xmake.utils.notify
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import javax.swing.JComponent

/**
 * The Repositories page's verbs: the CLI's four repository commands plus the read-only
 * refresh, and the console hop. One collaborator of [XMakeRepositoriesPanel]; the verbs
 * read the panel's selection and the service's busy state, and every mutation streams
 * into the XMake console.
 *
 * Add, remove, and clear follow the tree's groups: a group is a set, so the toolbar's
 * clear names the selected set, and add defaults its scope to the selected group.
 * xmake's own defaults cannot be removed or cleared - xmake reloads them on every
 * start - so the verbs stand down on that group. Update pulls what already exists and
 * destroys nothing, so it runs on the click.
 */
internal class XMakeRepositoryActions(private val panel: XMakeRepositoriesPanel) {

    /** Drives only the Show-in-Console hop onto the EDT; cancelled with the panel. */
    private val consoleScope = CoroutineScope(SupervisorJob() + Dispatchers.EDT)

    internal fun toolbar(): ActionToolbar {
        val group = DefaultActionGroup(
            RefreshAction(),
            Separator.create(),
            AddRepositoryAction(),
            RemoveRepositoryAction(),
            Separator.create(),
            UpdateRepositoriesAction(),
            ClearRepositoriesAction(),
            Separator.create(),
            ShowInConsoleAction(),
        )
        return ActionManager.getInstance()
            .createActionToolbar("XMakeRepositoriesView", group, true)
            .apply { targetComponent = panel }
    }

    /** The add flow, shared by the toolbar and the group pages' links: the name, url,
     *  optional branch, and the scope; [defaultGlobal] only pre-checks the box for a
     *  caller that already stands in the global group. */
    internal fun addRepositoryDialog(defaultGlobal: Boolean) {
        val dialog = AddRepositoryDialog(panel.project, defaultGlobal)
        if (!dialog.showAndGet()) return
        val (name, url, branch, global) = dialog.values() ?: return
        panel.service.addRepository(name, url, branch, global)
    }

    /** Re-reads the repository listing; mutations land here too, through the service. */
    private inner class RefreshAction : DumbAwareAction(
        "Refresh",
        "Re-read the repository list (xmake repo --list)",
        AllIcons.Actions.Refresh,
    ) {

        override fun getActionUpdateThread() = ActionUpdateThread.EDT

        override fun update(event: AnActionEvent) {
            event.presentation.isEnabled = !panel.service.busy
        }

        override fun actionPerformed(event: AnActionEvent) {
            panel.service.refreshRepositories()
        }
    }

    /** The add flow's toolbar entry: adding while looking at the global group pre-checks
     *  the global scope, so the dialog opens on the set the user is already in. */
    private inner class AddRepositoryAction : DumbAwareAction(
        "Add Repository",
        "Register a package repository (xmake repo --add <name> <url> [branch])",
        AllIcons.General.Add,
    ) {

        override fun getActionUpdateThread() = ActionUpdateThread.EDT

        override fun update(event: AnActionEvent) {
            event.presentation.isEnabled = !panel.service.busy
        }

        override fun actionPerformed(event: AnActionEvent) {
            addRepositoryDialog(defaultGlobal = panel.selectedOrigin() == RepoOrigin.GLOBAL)
        }
    }

    /** The remove flow: the selected repository, confirmed - its origin picks the set
     *  xmake answers for, so a global repository is really removed. xmake's own defaults
     *  stand down: xmake reloads them on every start, so the remove would be a no-op. */
    private inner class RemoveRepositoryAction : DumbAwareAction(
        "Remove Repository",
        "Take the selected repository out (xmake repo --remove <name>)",
        AllIcons.General.Remove,
    ) {

        override fun getActionUpdateThread() = ActionUpdateThread.EDT

        override fun update(event: AnActionEvent) {
            val repo = panel.selectedRepository()
            val origin = repo?.let(::originOf)
            event.presentation.isEnabled =
                repo != null && origin != RepoOrigin.DEFAULT && !panel.service.busy
            event.presentation.description = when {
                repo == null -> "Select a repository to remove"
                origin == RepoOrigin.DEFAULT ->
                    "'${repo.name}' is provided by xmake and is loaded on every start; it cannot be removed"
                else -> "Take '${repo.name}' out of the ${repoScopeWord(repo)} repositories " +
                    "(xmake repo --remove ${repo.name}${if (repo.scope == "global") " --global" else ""})"
            }
        }

        override fun actionPerformed(event: AnActionEvent) {
            val repo = panel.selectedRepository() ?: return
            val scope = repoScopeWord(repo)
            if (Messages.showYesNoDialog(
                    panel.project,
                    "Remove the $scope repository '${repo.name}' (${repo.url})?",
                    "Remove Repository",
                    Messages.getWarningIcon(),
                ) != Messages.YES
            ) return
            panel.service.removeRepository(repo.name, repo.scope == "global")
        }
    }

    /** One `xmake repo --update`: refreshes every local repository from its remote.
     *  It destroys nothing - the click is the confirmation. xmake's own update reads
     *  both the project's and the global set. */
    private inner class UpdateRepositoriesAction : DumbAwareAction(
        "Update All",
        "Fetch the latest from each repository's remote (xmake repo --update)",
        AllIcons.Actions.Download,
    ) {

        override fun getActionUpdateThread() = ActionUpdateThread.EDT

        override fun update(event: AnActionEvent) {
            event.presentation.isEnabled = !panel.service.busy
        }

        override fun actionPerformed(event: AnActionEvent) {
            panel.service.updateRepositories()
        }
    }

    /** The clear flow: every added repository in the selected set - the group itself
     *  or the selected repository's group - with a warning. xmake clears one set per
     *  invocation ([--global] selects the shared one), and a button whose label names
     *  the set is the confirmation's first half. xmake's defaults are not a clearable
     *  set: they come back on every start. */
    private inner class ClearRepositoriesAction : DumbAwareAction(
        "Clear Repositories",
        "Remove every added repository in the selected set (xmake repo --clear)",
        AllIcons.Actions.GC,
    ) {

        override fun getActionUpdateThread() = ActionUpdateThread.EDT

        override fun update(event: AnActionEvent) {
            val origin = panel.selectedOrigin()
            event.presentation.isEnabled =
                (origin == RepoOrigin.PROJECT || origin == RepoOrigin.GLOBAL) && !panel.service.busy
            event.presentation.text = when (origin) {
                RepoOrigin.PROJECT -> "Clear Project Repositories"
                RepoOrigin.GLOBAL -> "Clear Global Repositories"
                else -> "Clear Repositories"
            }
            event.presentation.description = when (origin) {
                RepoOrigin.PROJECT ->
                    "Remove every added repository of this project (xmake repo --clear)"
                RepoOrigin.GLOBAL ->
                    "Remove every added global repository (xmake repo --clear --global)"
                RepoOrigin.DEFAULT ->
                    "Repositories provided by xmake are loaded on every start and cannot be cleared"
                null -> "Select a group or a repository in the set to clear"
            }
        }

        override fun actionPerformed(event: AnActionEvent) {
            val origin = panel.selectedOrigin() ?: return
            val global = origin == RepoOrigin.GLOBAL
            val scope = if (global) "global" else "project"
            if (Messages.showYesNoDialog(
                    panel.project,
                    "Remove every added repository in the $scope set? xmake's default repositories " +
                        "stay, and the project's packages keep resolving against what remains.",
                    if (global) "Clear Global Repositories" else "Clear Project Repositories",
                    Messages.getWarningIcon(),
                ) != Messages.YES
            ) return
            panel.service.clearRepositories(global)
        }
    }

    private inner class ShowInConsoleAction : DumbAwareAction(
        "Show in Console",
        "Activate the XMake console, where the repository commands stream their output",
        AllIcons.Actions.Preview,
    ) {

        override fun getActionUpdateThread() = ActionUpdateThread.EDT

        override fun actionPerformed(event: AnActionEvent) {
            consoleScope.launch {
                try {
                    XMakeConsoleManager.getInstance(panel.project).awaitReady().showOutput()
                } catch (cancelled: kotlinx.coroutines.CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    // The console does not exist in a disposed project; nothing to show then.
                    if (!panel.project.isDisposed) {
                        panel.project.notify(
                            "The XMake console is not available",
                            StringUtil.escapeXmlEntities(error.message ?: ""),
                        )
                    }
                }
            }
        }
    }

    /** The console hop's scope dies with the panel. */
    fun dispose() {
        consoleScope.cancel()
    }
}

/**
 * The add-repository dialog: the name, the url, the optional branch, and the scope the
 * add saves to. [defaultGlobal] pre-checks the scope box when the caller stands in the
 * global group; everything is editable later by remove-and-readd, so no field carries
 * ceremony beyond the format check - xmake judges the url when the add runs.
 */
internal class AddRepositoryDialog(
    project: com.intellij.openapi.project.Project,
    defaultGlobal: Boolean = false,
) : com.intellij.openapi.ui.DialogWrapper(project) {

    private val nameField = com.intellij.ui.components.JBTextField(20).apply {
        emptyText.text = "e.g. my-repo"
    }
    private val urlField = com.intellij.ui.components.JBTextField(32).apply {
        emptyText.text = "https://... or a local path"
    }
    private val branchField = com.intellij.ui.components.JBTextField(16).apply {
        emptyText.text = "optional - e.g. main"
    }
    private val globalCheck =
        com.intellij.ui.components.JBCheckBox("Add to the global repositories", defaultGlobal)

    init {
        title = "Add Repository"
        setOKButtonText("Add")
        init()
    }

    override fun createCenterPanel(): JComponent = com.intellij.ui.dsl.builder.panel {
        row("Name:") { cell(nameField).align(com.intellij.ui.dsl.builder.AlignX.FILL) }
        row("URL:") { cell(urlField).align(com.intellij.ui.dsl.builder.AlignX.FILL) }
        row("Branch:") { cell(branchField).align(com.intellij.ui.dsl.builder.AlignX.FILL) }
        row { cell(globalCheck).comment("Unchecked saves the repository to this project only") }
    }

    override fun getPreferredFocusedComponent(): JComponent = nameField

    override fun doValidate(): com.intellij.openapi.ui.ValidationInfo? = when {
        nameField.text.isBlank() ->
            com.intellij.openapi.ui.ValidationInfo("Name the repository", nameField)
        urlField.text.isBlank() ->
            com.intellij.openapi.ui.ValidationInfo("The repository needs a url or path", urlField)
        else -> null
    }

    /** The dialog's answer, or null when a validation gap slipped through. */
    fun values(): Tuple4? =
        nameField.text.trim().takeUnless { it.isEmpty() }?.let { name ->
            urlField.text.trim().takeUnless { it.isEmpty() }?.let { url ->
                Tuple4(name, url, branchField.text.trim().takeUnless(String::isEmpty), globalCheck.isSelected)
            }
        }

    /** The add's four facts, as one value. */
    data class Tuple4(val name: String, val url: String, val branch: String?, val global: Boolean)
}
