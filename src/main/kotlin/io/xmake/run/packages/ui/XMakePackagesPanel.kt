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
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionToolbar
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.actionSystem.Presentation
import com.intellij.openapi.actionSystem.Separator
import com.intellij.openapi.actionSystem.ex.CustomComponentAction
import com.intellij.openapi.application.EDT
import com.intellij.openapi.application.invokeLater
import com.intellij.openapi.editor.colors.EditorColorsManager
import com.intellij.openapi.editor.colors.EditorFontType
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.text.StringUtil
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.ui.SimpleToolWindowPanel
import com.intellij.ui.ColoredTreeCellRenderer
import com.intellij.ui.JBColor
import com.intellij.ui.OnePixelSplitter
import com.intellij.ui.ScrollPaneFactory
import com.intellij.ui.SearchTextField
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.TreeSpeedSearch
import com.intellij.ui.treeStructure.Tree
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import com.intellij.util.ui.tree.TreeUtil
import io.xmake.project.console.XMakeConsoleManager
import io.xmake.run.command.XMakeCommand
import io.xmake.run.packages.XMakePackageInfo
import io.xmake.run.packages.XMakePackageParsers
import io.xmake.run.packages.XMakePackageSearchEntry
import io.xmake.run.packages.XMakePackagesService
import io.xmake.run.packages.XMakePackagesSnapshot
import io.xmake.run.packages.XMakeRequireEntry
import io.xmake.utils.notify
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.awt.Dimension
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JTextArea
import javax.swing.JTree
import javax.swing.tree.DefaultMutableTreeNode
import javax.swing.tree.DefaultTreeModel
import javax.swing.tree.TreeSelectionModel

/**
 * The XMake Packages view: the declared packages as one tree, the selected package's
 * `--info` data beneath, and the toolbar's search and confirmed package actions.
 *
 * The tree rows come from `require --list`, the dependency children from the depgraph
 * query (a failed graph just means childless rows), and the details pane from a lazily
 * fetched, cached `require --info` per selection - its `references` key is the installed
 * badge, because the listing's own detail lines survive an uninstall. Everything mutates
 * only through a confirmation naming the exact command line, then streams into the XMake
 * console. The view reflects; it never computes package state itself.
 */
internal class XMakePackagesPanel(
    private val project: Project,
    parentDisposable: Disposable,
) : SimpleToolWindowPanel(true, true), Disposable {

    private val service = XMakePackagesService.getInstance(project)

    private val model = DefaultTreeModel(DefaultMutableTreeNode(ROOT))
    private val tree = Tree(model).apply {
        isRootVisible = false
        showsRootHandles = true
        selectionModel.selectionMode = TreeSelectionModel.SINGLE_TREE_SELECTION
        emptyText.text = LOADING
    }
    private val summaryLabel = JLabel()
    private val details = JTextArea().apply {
        isEditable = false
        lineWrap = true
        wrapStyleWord = true
        // The package's own data, aligned the way the console shows it.
        font = EditorColorsManager.getInstance().globalScheme.getFont(EditorFontType.PLAIN)
    }

    /** The snapshot the tree was built from; an info-only update repaints, never rebuilds,
     *  so a selection (and its loading detail) survives a background info landing. */
    private var treeSource: XMakePackagesSnapshot? = null

    /** Drives only the Show-in-Console hop onto the EDT; cancelled with the panel. */
    private val consoleScope = CoroutineScope(SupervisorJob() + Dispatchers.EDT)

    /** The tree column's renderer: name, requirement, source, installed badge, and the
     *  search hits' description in place. */
    private val packageRenderer = object : ColoredTreeCellRenderer() {

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
                is PackageNode -> {
                    val info = service.snapshot?.infos?.get(value.entry.name)
                    if (info?.installed == true) icon = AllIcons.General.InspectionsOK
                    append(value.entry.name, SimpleTextAttributes.REGULAR_BOLD_ATTRIBUTES)
                    if (value.entry.requirement.isNotEmpty()) {
                        append(" : ${value.entry.requirement}", SimpleTextAttributes.GRAYED_ATTRIBUTES)
                    }
                    sourceLabel(value.entry.name, info)
                        ?.let { append("   $it", SimpleTextAttributes.GRAYED_ATTRIBUTES) }
                    if (info != null) {
                        append(
                            if (info.installed) "  installed" else "  not installed",
                            SimpleTextAttributes.GRAYED_ATTRIBUTES,
                        )
                    }
                }
                is SearchNode -> {
                    append(value.entry.name, SimpleTextAttributes.REGULAR_BOLD_ATTRIBUTES)
                    value.entry.version?.let { append("-$it", SimpleTextAttributes.GRAYED_ATTRIBUTES) }
                    value.entry.description?.let { append("  $it", SimpleTextAttributes.REGULAR_ATTRIBUTES) }
                    value.entry.repo?.let { append("  (in $it)", SimpleTextAttributes.GRAYED_ATTRIBUTES) }
                }
                is DepNode -> {
                    append(value.name, SimpleTextAttributes.REGULAR_ATTRIBUTES)
                    value.version?.let { append(" : $it", SimpleTextAttributes.GRAYED_ATTRIBUTES) }
                }
                else -> append(value.toString(), SimpleTextAttributes.REGULAR_ATTRIBUTES)
            }
        }
    }

    init {
        tree.cellRenderer = packageRenderer
        TreeSpeedSearch.installOn(tree)
        // Selecting a row is what fetches its info; the cache makes re-selection free.
        tree.addTreeSelectionListener {
            selectionPackageName()?.let { name ->
                service.fetchInfo(name)
                renderDetails(name)
            } ?: run { details.text = "" }
        }

        toolbar = toolbar().component
        // The Analysis view's shape: the rows above, the selected package's data below,
        // the split remembered between sessions.
        val split = OnePixelSplitter(true, SPLIT_KEY, 0.65f).apply {
            firstComponent = JBUI.Panels.simplePanel().apply {
                addToTop(summaryLabel.apply { border = JBUI.Borders.empty(4, 8) })
                addToCenter(ScrollPaneFactory.createScrollPane(tree, true))
            }
            secondComponent = JBUI.Panels.simplePanel().apply {
                addToTop(JLabel("Details").apply { border = JBUI.Borders.empty(4, 8) })
                addToCenter(ScrollPaneFactory.createScrollPane(details, true))
            }
        }
        setContent(split)

        project.messageBus.connect(parentDisposable).subscribe(
            XMakePackagesService.TOPIC,
            XMakePackagesService.Listener { snapshot ->
                invokeLater { if (!project.isDisposed) onSnapshot(snapshot) }
            },
        )
        // Whatever the service already holds, then the first load; the subscription is in
        // place first so an in-flight refresh cannot land unseen.
        onSnapshot(service.snapshot)
        service.refresh()
    }

    /** The subscriptions ride on the content's disposal; nothing else outlives the panel. */
    override fun dispose() {
        consoleScope.cancel()
    }

    private fun toolbar(): ActionToolbar {
        val group = DefaultActionGroup(
            RefreshAction(),
            SearchAction(),
            Separator.create(),
            InstallAction(),
            UpgradeAction(),
            UninstallAction(),
            Separator.create(),
            ShowInConsoleAction(),
        )
        return ActionManager.getInstance()
            .createActionToolbar("XMakePackagesView", group, true)
            .apply { targetComponent = this@XMakePackagesPanel }
    }

    /** One publish: rebuild the rows when their shape changed, repaint for a fresh info,
     *  and re-render the details of whatever stays selected. */
    private fun onSnapshot(snapshot: XMakePackagesSnapshot?) {
        val structureChanged = snapshot?.packages !== treeSource?.packages ||
                snapshot?.depgraph !== treeSource?.depgraph ||
                snapshot?.searchResults !== treeSource?.searchResults
        treeSource = snapshot
        if (structureChanged) rebuild(snapshot) else tree.repaint()
        updateSummary(snapshot)
        // A selection made while a refresh held the lane gets its info now; cached or busy
        // calls inside are no-ops.
        selectionPackageName()?.let { name ->
            service.fetchInfo(name)
            renderDetails(name)
        }
    }

    /** Swaps the rows: search results while searching, else the declared packages with
     *  their dependency children (a missing graph simply leaves them childless). */
    private fun rebuild(snapshot: XMakePackagesSnapshot?) {
        val root = DefaultMutableTreeNode(ROOT)
        val results = snapshot?.searchResults
        if (results != null) {
            results.forEach { root.add(SearchNode(it)) }
        } else {
            snapshot?.packages?.forEach { entry ->
                val node = PackageNode(entry)
                XMakePackageParsers.dependencyChildren(snapshot.depgraph, entry.name)
                    .forEach { dep -> node.add(DepNode(dep.name, dep.version)) }
                root.add(node)
            }
        }
        tree.emptyText.text = when {
            snapshot == null -> LOADING
            results != null -> "No hits - try another term"
            else -> "No packages declared"
        }
        model.setRoot(root)
        TreeUtil.expandAll(tree)
    }

    private fun updateSummary(snapshot: XMakePackagesSnapshot?) {
        val base = when {
            snapshot?.searchResults != null ->
                "Search: ${snapshot.searchResults.size} hit(s) - Refresh returns to the package list"
            snapshot != null -> "${snapshot.packages.size} declared package(s)"
            else -> "Press Refresh to list the declared packages"
        }
        val error = service.lastError
        summaryLabel.text = if (error == null) base else "$base - last operation failed: $error"
        summaryLabel.foreground = if (error == null) UIUtil.getLabelForeground() else JBColor.RED
    }

    /** The package name the selection carries: a row, a search hit, or a dependency child. */
    private fun selectionPackageName(): String? = when (val node = tree.lastSelectedPathComponent) {
        is PackageNode -> node.entry.name
        is SearchNode -> node.entry.name
        is DepNode -> node.name
        else -> null
    }

    private fun renderDetails(name: String) {
        val info = service.snapshot?.infos?.get(name)
        details.text = when {
            info == null && service.busy -> "Loading the info of '$name' - xmake require --info --format=json $name"
            info == null -> "No info available for '$name' - the query failed or produced nothing usable"
            else -> renderInfo(name, info)
        }
    }

    private fun renderInfo(name: String, info: XMakePackageInfo): String = buildString {
        append("package: ").append(name).append('\n')
        info.version?.let { append("version: ").append(it).append('\n') }
        info.description?.let { append("description: ").append(it).append('\n') }
        if (info.repoName == null) {
            // A package that exists nowhere still reports version "latest"; the missing
            // repo is the "not found" signal, not a failed query.
            append("repo: not found in the configured repositories\n")
        } else {
            append("repo: ").append(info.repoName)
            info.repoUrl?.let { append(" (").append(it).append(')') }
            append('\n')
        }
        info.installdir?.let { append("installdir: ").append(it).append('\n') }
        info.cachedir?.let { append("cachedir: ").append(it).append('\n') }
        if (info.platforms.isNotEmpty()) {
            append("platforms: ").append(info.platforms.joinToString(", ")).append('\n')
        }
        if (info.installed) {
            append("references: ").append(info.references?.size ?: 0).append(" - installed\n")
            info.references.orEmpty().forEach { reference ->
                append("  ").append(reference.dir ?: "?")
                reference.date?.let { append("  (").append(it).append(')') }
                append('\n')
            }
        } else {
            append("references: none - not installed\n")
        }
    }

    /** The row's source: the external-manager prefix for `vcpkg::`/`conan::` names, else
     *  the repository `--info` reported. */
    private fun sourceLabel(name: String, info: XMakePackageInfo?): String? {
        if ("::" in name) return "${name.substringBefore("::")}::"
        return info?.repoName?.let { "from $it" }
    }

    /** The confirmation the mutating actions share: the exact command line, the packages
     *  it touches, then the run with `-y` already baked in. */
    private fun confirmAndRun(title: String, what: String, command: XMakeCommand) {
        // TODO(v2): name the expected xmake-requires.lock effect here when the policy is on
        // (docs/roadmap-designs/xrepo-packages.md); the diff balloon ships with it.
        if (Messages.showYesNoDialog(
                project,
                "xmake will run:\n\n    ${command.commandLine.commandLineString}\n\n$what\n" +
                    "The output streams into the XMake console.",
                title,
                Messages.getQuestionIcon(),
            ) != Messages.YES
        ) return
        service.runPackageCommand(command, what)
    }

    private inner class RefreshAction :
        DumbAwareAction("Refresh", "Reload the declared packages and the dependency graph", AllIcons.Actions.Refresh) {

        override fun getActionUpdateThread() = ActionUpdateThread.EDT

        override fun update(event: AnActionEvent) {
            event.presentation.isEnabled = !service.busy
        }

        override fun actionPerformed(event: AnActionEvent) {
            service.refresh()
        }
    }

    /** The search field rides the toolbar as the action's custom component; Enter runs it. */
    private inner class SearchAction : DumbAwareAction(
        "Search Packages",
        "Type a term and press Enter to search the configured repositories",
        AllIcons.Actions.Search,
    ),
        CustomComponentAction {

        override fun getActionUpdateThread() = ActionUpdateThread.EDT

        override fun update(event: AnActionEvent) {
            event.presentation.isEnabled = !service.busy
        }

        override fun actionPerformed(event: AnActionEvent) = Unit

        override fun createCustomComponent(presentation: Presentation, place: String): JComponent =
            SearchTextField(false).apply {
                textEditor.emptyText.text = "Search packages (Enter)"
                textEditor.addActionListener {
                    val term = text.trim()
                    if (term.isNotEmpty()) service.search(term)
                }
                preferredSize = Dimension(JBUI.scale(220), preferredSize.height)
            }
    }

    private inner class InstallAction : DumbAwareAction(
        "Install",
        "Install the selected package after confirming the exact command",
        AllIcons.Actions.Install,
    ) {

        override fun getActionUpdateThread() = ActionUpdateThread.EDT

        override fun update(event: AnActionEvent) {
            event.presentation.isEnabled = !service.busy && selectionPackageName() != null
        }

        override fun actionPerformed(event: AnActionEvent) {
            val name = selectionPackageName() ?: return
            service.prepareCommand(prepare = { createRequireInstall(name) }) { command ->
                confirmAndRun("Install package", "Installing '$name' - xmake fetches, builds, and installs it.", command)
            }
        }
    }

    private inner class UpgradeAction : DumbAwareAction(
        "Upgrade",
        "Upgrade every installed package after confirming the exact command",
        AllIcons.Actions.Download,
    ) {

        override fun getActionUpdateThread() = ActionUpdateThread.EDT

        override fun update(event: AnActionEvent) {
            event.presentation.isEnabled = !service.busy
        }

        override fun actionPerformed(event: AnActionEvent) {
            service.prepareCommand(prepare = { createRequireUpgrade() }) { command ->
                confirmAndRun("Upgrade packages", "Upgrading every installed package.", command)
            }
        }
    }

    private inner class UninstallAction : DumbAwareAction(
        "Uninstall",
        "Uninstall the selected package after confirming the exact command",
        AllIcons.Actions.Uninstall,
    ) {

        override fun getActionUpdateThread() = ActionUpdateThread.EDT

        override fun update(event: AnActionEvent) {
            event.presentation.isEnabled = !service.busy && selectionPackageName() != null
        }

        override fun actionPerformed(event: AnActionEvent) {
            val name = selectionPackageName() ?: return
            service.prepareCommand(prepare = { createRequireUninstall(name) }) { command ->
                confirmAndRun("Uninstall package", "Uninstalling '$name'.", command)
            }
        }
    }

    private inner class ShowInConsoleAction : DumbAwareAction(
        "Show in Console",
        "Activate the XMake console, where the package commands stream their output",
        AllIcons.Actions.Preview,
    ) {

        override fun getActionUpdateThread() = ActionUpdateThread.EDT

        override fun actionPerformed(event: AnActionEvent) {
            consoleScope.launch {
                try {
                    XMakeConsoleManager.getInstance(project).awaitReady().showOutput()
                } catch (error: Exception) {
                    // The console does not exist in a disposed project; nothing to show then.
                    if (!project.isDisposed) {
                        project.notify(
                            "The XMake console is not available",
                            StringUtil.escapeXmlEntities(error.message ?: ""),
                        )
                    }
                }
            }
        }
    }

    /** One declared package row: the listing entry, with dependency children beneath. */
    private class PackageNode(val entry: XMakeRequireEntry) :
        DefaultMutableTreeNode(entry) {

        override fun toString(): String = entry.name
    }

    /** One search hit row. */
    private class SearchNode(val entry: XMakePackageSearchEntry) :
        DefaultMutableTreeNode(entry) {

        override fun toString(): String = entry.name
    }

    /** One dependency child: a package name and the version the graph resolved. */
    private class DepNode(val name: String, val version: String?) : DefaultMutableTreeNode(name) {

        override fun toString(): String = name
    }

    private companion object {

        /** The splitter's persistence key; the user's split survives restarts. */
        private const val SPLIT_KEY = "XMakePackagesView"

        /** The invisible tree root; the panel swaps it per refresh. */
        private const val ROOT = "root"

        private const val LOADING = "Loading the declared packages - or press Refresh"
    }
}
