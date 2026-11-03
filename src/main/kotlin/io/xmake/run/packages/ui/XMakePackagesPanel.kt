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
import com.intellij.ui.DoubleClickListener
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
import java.awt.event.ActionEvent
import java.awt.event.KeyEvent
import java.awt.event.MouseEvent
import java.util.Collections
import javax.swing.AbstractAction
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JTextArea
import javax.swing.JTree
import javax.swing.KeyStroke
import javax.swing.tree.DefaultMutableTreeNode
import javax.swing.tree.DefaultTreeModel
import javax.swing.tree.TreePath
import javax.swing.tree.TreeSelectionModel

/**
 * The XMake Packages view: the declared packages as one tree, the selected package's
 * `--info` data beneath, and the toolbar's search and confirmed package actions.
 *
 * The tree rows come from `require --list`, the dependency children from the depgraph
 * query (a failed graph just means childless rows), and the details pane from a lazily
 * fetched, cached `require --info` per selection - its `references` key is the installed
 * badge, because the listing's own detail lines survive an uninstall. Search swaps the
 * rows to the repository hits, which then stay the tree's filtered view across refreshes;
 * Esc, or an emptied field plus Enter, brings the declared list back. Two clicks on a row
 * open the install flow. Everything mutates only through a confirmation naming the exact
 * command line, then streams into the XMake console. The view reflects; it never computes
 * package state itself.
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

    /** The tree's scroll pane; a rebuild restores its viewport so a refresh keeps the
     *  user's place instead of throwing the rows back to the top. */
    private val treeScroll = ScrollPaneFactory.createScrollPane(tree, true)

    /** The toolbar's search field; kept so an Esc exit can empty it with the view. */
    private var searchField: SearchTextField? = null

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
        // Esc leaves search mode from wherever focus sits; the search field gets the same
        // binding when the toolbar creates it below.
        exitSearchOnEscape(tree)
        // The platform's own double-click handling (the Analysis view's pattern): two
        // clicks on a row are the Install entry - where a search hit starts its flow.
        object : DoubleClickListener() {
            override fun onDoubleClick(event: MouseEvent): Boolean {
                val name = selectionPackageName() ?: return false
                installPackage(name)
                return true
            }
        }.installOn(tree)

        toolbar = toolbar().component
        // The Analysis view's shape: the rows above, the selected package's data below,
        // the split remembered between sessions.
        val split = OnePixelSplitter(true, SPLIT_KEY, 0.65f).apply {
            firstComponent = JBUI.Panels.simplePanel().apply {
                addToTop(summaryLabel.apply { border = JBUI.Borders.empty(4, 8) })
                addToCenter(treeScroll)
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
     *  and re-render the details of whatever stays selected. A failed first load publishes
     *  null over null - no identity change - yet must still reach [rebuild], or the tree
     *  would keep promising "Loading" while only the summary line knows the failure. */
    private fun onSnapshot(snapshot: XMakePackagesSnapshot?) {
        val structureChanged = snapshot?.packages !== treeSource?.packages ||
                snapshot?.depgraph !== treeSource?.depgraph ||
                snapshot?.searchResults !== treeSource?.searchResults
        treeSource = snapshot
        if (structureChanged || (snapshot == null && service.lastError != null)) {
            rebuild(snapshot)
        } else {
            tree.repaint()
        }
        updateSummary(snapshot)
        // A selection made while a refresh held the lane gets its info now; cached or busy
        // calls inside are no-ops.
        selectionPackageName()?.let { name ->
            service.fetchInfo(name)
            renderDetails(name)
        }
    }

    /** Swaps the rows: search results while searching, else the declared packages with
     *  their dependency children (a missing graph simply leaves them childless). A rebuild
     *  is a refresh, not a reset: the scroll position, the expanded rows, and the selection
     *  carry over, so a post-action refresh reads as the rows updating in place. */
    private fun rebuild(snapshot: XMakePackagesSnapshot?) {
        val selectedName = selectionPackageName()
        val expandedRows = expandedRowKeys()
        val position = treeScroll.viewport.viewPosition
        val root = DefaultMutableTreeNode(ROOT)
        val results = snapshot?.searchResults
        if (results != null) {
            results.hits.forEach { root.add(SearchNode(it)) }
        } else {
            snapshot?.packages?.forEach { entry ->
                val node = PackageNode(entry)
                XMakePackageParsers.dependencyChildren(snapshot.depgraph, entry.name)
                    .forEach { dep -> node.add(DepNode(dep.name, dep.version)) }
                root.add(node)
            }
        }
        tree.emptyText.text = when {
            snapshot == null && service.lastError != null ->
                // The first query never landed; the reason sits in lastError, and the
                // summary line above the tree repeats it in red.
                "Could not list packages: ${service.lastError} - fix the problem and press Refresh"
            snapshot == null -> LOADING
            results != null ->
                // Zero hits plus a diagnostic is a broken toolchain answering exit 0
                // (xmake 2.8.7 without unzip/7zip), not a misspelled term - the line is
                // the difference between "try another term" and "fix your xmake"
                // (docs/roadmap-designs/audit-packages.md, sections 1.6 and 3.1).
                results.diagnostics.firstOrNull()
                    ?.let { "No hits - xmake reported: $it" }
                    ?: "No hits - try another term"
            else -> "No packages declared"
        }
        model.setRoot(root)
        if (expandedRows.isEmpty()) {
            // A fresh view opens fully, as it always has.
            TreeUtil.expandAll(tree)
        } else {
            // The root first - the rows hang off it - then the rows the user had open.
            tree.expandPath(TreePath(root.path))
            restoreExpanded(root, expandedRows)
        }
        restoreSelection(selectedName)
        treeScroll.viewport.viewPosition = position
        // The viewport revalidates after the model swap; re-apply once layout has settled,
        // or the fresh rows would still pull it back to the top.
        invokeLater { treeScroll.viewport.viewPosition = position }
    }

    /** A row's rebuild-stable key: the nodes are rebuilt on every swap, so expansion is
     *  matched on the user object, not on node identity. */
    private fun rowKey(userObject: Any?): String? = when (userObject) {
        is XMakeRequireEntry -> "pkg:${userObject.name}"
        is XMakePackageSearchEntry -> "hit:${userObject.name}"
        is DepNode -> "dep:${userObject.name}"
        else -> null
    }

    /** The keys of the rows the user had expanded, taken before the swap. */
    private fun expandedRowKeys(): Set<String> {
        val root = model.root as? DefaultMutableTreeNode ?: return emptySet()
        val keys = mutableSetOf<String>()
        for (node in Collections.list(root.depthFirstEnumeration())) {
            val mutable = node as? DefaultMutableTreeNode ?: continue
            if (mutable !== root && tree.isExpanded(TreePath(mutable.path))) {
                rowKey(mutable.userObject)?.let { keys.add(it) }
            }
        }
        return keys
    }

    private fun restoreExpanded(node: DefaultMutableTreeNode, keys: Set<String>) {
        for (index in 0 until node.childCount) {
            val child = node.getChildAt(index) as? DefaultMutableTreeNode ?: continue
            val key = rowKey(child.userObject)
            if (key != null && key in keys) tree.expandPath(TreePath(child.path))
            restoreExpanded(child, keys)
        }
    }

    /** Puts the selection back on the row carrying [name] - quietly: no scroll-to-visible,
     *  the restored viewport wins, and the selection listener's info fetch is a cache hit
     *  or the ordinary lazy load. */
    private fun restoreSelection(name: String?) {
        if (name == null) return
        val root = model.root as? DefaultMutableTreeNode ?: return
        val match = Collections.list(root.depthFirstEnumeration())
            .filterIsInstance<DefaultMutableTreeNode>()
            .firstOrNull { selectionName(it.userObject) == name } ?: return
        tree.addSelectionPath(TreePath(match.path))
    }

    /** The package name a row's user object carries - the same reading the selection
     *  listener applies to the live selection. */
    private fun selectionName(userObject: Any?): String? = when (userObject) {
        is XMakeRequireEntry -> userObject.name
        is XMakePackageSearchEntry -> userObject.name
        is DepNode -> userObject.name
        else -> null
    }

    private fun updateSummary(snapshot: XMakePackagesSnapshot?) {
        val results = snapshot?.searchResults
        val base = when {
            results != null -> buildString {
                append("Search: ").append(results.hits.size).append(" hit(s)")
                results.diagnostics.firstOrNull()?.let { append(" - xmake reported: ").append(it) }
                append(" - Esc, or an emptied field plus Enter, returns to the package list")
            }
            snapshot != null -> "${snapshot.packages.size} declared package(s)"
            else -> "Press Refresh to list the declared packages"
        }
        val error = service.lastError
        summaryLabel.text = buildString {
            append(base)
            // While an operation holds the pipeline the grayed buttons need their reason on
            // show - the same narration the Services panel's message line gives.
            service.currentOperation?.let { operation -> append(" - ").append(operation) }
            if (error != null) append(" - last operation failed: ").append(error)
        }
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
            // A busy pipeline with a named operation is not loading this info at all - the
            // fetchInfo call was a no-op; say what the wait is really on.
            info == null && service.busy && service.currentOperation != null ->
                "Waiting for the current operation to finish before loading the info of '$name'... - " +
                        "${service.currentOperation} - then the row loads its info, or select it again"
            info == null && service.busy ->
                "Loading the info of '$name'... - xmake require --info --format=json $name"
            info == null -> "No info available for '$name' - the query failed or produced nothing usable"
            else -> renderInfo(name, info)
        }
    }

    private fun renderInfo(name: String, info: XMakePackageInfo): String = buildString {
        append("package: ").append(name).append('\n')
        info.version?.let { append("version: ").append(it).append('\n') }
        info.description?.let { append("description: ").append(it).append('\n') }
        // `--info` carries no dependency field; the depgraph query is the honest source.
        // A package outside the project's graph (a search hit) simply has no line here.
        val dependencies = service.snapshot?.depgraph?.packages
            ?.firstOrNull { it.name == name }?.deps
        if (!dependencies.isNullOrEmpty()) {
            append("depends on: ").append(dependencies.joinToString(", ")).append('\n')
        }
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

    /** The Install flow the toolbar button and a double-click on a row share. */
    private fun installPackage(name: String) {
        service.prepareCommand(prepare = { createRequireInstall(name) }) { command ->
            confirmAndRun("Install package", "Installing '$name' - xmake fetches, builds, and installs it.", command)
        }
    }

    /** Esc leaves search mode from [component] - the tree, or the search field - and
     *  empties the field, so it does not claim a term the view no longer filters by.
     *  Outside search mode it is a no-op. */
    private fun exitSearchOnEscape(component: JComponent) {
        component.getInputMap(JComponent.WHEN_FOCUSED)
            .put(KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0), EXIT_SEARCH)
        component.actionMap.put(EXIT_SEARCH, object : AbstractAction() {
            override fun actionPerformed(event: ActionEvent) {
                if (service.snapshot?.searchResults == null) return
                // The lane holds exitSearch until the operation ends; emptying the field
                // before that would claim a term the view still filters by.
                if (service.busy) return
                searchField?.text = ""
                service.exitSearch()
            }
        })
    }

    /** The narration an Enter pressed while an operation holds the lane gets - the same
     *  "wait for the current operation" line the details pane shows, so the key press is
     *  answered instead of silently dropped (docs/roadmap-designs/audit-packages.md,
     *  section 3.3). The next publish overwrites it. */
    private fun narrateBusyWait() {
        summaryLabel.text = buildString {
            append("Waiting for the current operation to finish - ")
            append(service.currentOperation ?: "working")
            append(" - then try again")
            service.lastError?.let { append(" - last operation failed: ").append(it) }
        }
    }

    private inner class RefreshAction :
        DumbAwareAction("Refresh", "Re-probe the declared packages and the dependency graph", AllIcons.Actions.Refresh) {

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

        override fun actionPerformed(event: AnActionEvent) {
            // The icon click re-runs the term the field already holds - the only search
            // entry besides the field's own Enter.
            searchField?.let { field ->
                val term = field.text.trim()
                when {
                    term.isEmpty() -> service.exitSearch()
                    service.busy -> narrateBusyWait()
                    else -> service.search(term)
                }
            }
        }

        override fun createCustomComponent(presentation: Presentation, place: String): JComponent =
            SearchTextField(false).apply {
                textEditor.emptyText.text = "Search packages"
                textEditor.addActionListener {
                    val term = text.trim()
                    when {
                        term.isEmpty() ->
                            // An emptied field plus Enter is one way out of search mode: the
                            // declared list comes back from the snapshot the search rode on,
                            // no lane time spent.
                            service.exitSearch()
                        service.busy ->
                            // The field accepts keys between the toolbar's update() calls, so
                            // an Enter can land mid-operation; narrate the wait, keep the term.
                            narrateBusyWait()
                        else -> service.search(term)
                    }
                }
                // Esc is the other way out, from the field as from the tree.
                exitSearchOnEscape(textEditor)
                preferredSize = Dimension(JBUI.scale(220), preferredSize.height)
            }.also { field -> searchField = field }
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
            installPackage(selectionPackageName() ?: return)
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

        /** The input-map key of the exit-search binding, shared by the tree and the field. */
        private const val EXIT_SEARCH = "XMakePackages.exitSearch"

        /** The invisible tree root; the panel swaps it per refresh. */
        private const val ROOT = "root"

        private const val LOADING = "Loading the declared packages... - or press Refresh"
    }
}
