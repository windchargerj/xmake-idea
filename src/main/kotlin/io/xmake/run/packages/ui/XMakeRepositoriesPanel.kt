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

import com.intellij.ide.BrowserUtil
import com.intellij.icons.AllIcons
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.invokeLater
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.SimpleToolWindowPanel
import com.intellij.openapi.util.text.StringUtil
import com.intellij.ui.ColorUtil
import com.intellij.ui.JBColor
import com.intellij.ui.SearchTextField
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.border.CustomLineBorder
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBPanelWithEmptyText
import com.intellij.util.ui.JBFont
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import com.intellij.util.ui.components.BorderLayoutPanel
import io.xmake.run.packages.XMakePackagesService
import io.xmake.run.packages.XMakePackagesSnapshot
import io.xmake.run.packages.XMakeRepoState
import java.awt.BorderLayout
import java.awt.Component
import java.awt.Font
import java.awt.event.ActionEvent
import java.awt.event.KeyEvent
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.AbstractAction
import javax.swing.BoxLayout
import javax.swing.DefaultListModel
import javax.swing.JComponent
import javax.swing.JList
import javax.swing.JPanel
import javax.swing.KeyStroke
import javax.swing.ListCellRenderer
import javax.swing.ListSelectionModel
import javax.swing.SwingConstants
import javax.swing.SwingUtilities
import javax.swing.event.DocumentEvent

/**
 * The Repositories tab of the XMake Packages tool window: where the project's package
 * asks resolve from, as one sectioned list.
 *
 * A list, not a table and not a master-detail page. A repository has one long,
 * variable-width fact - its url or on-disk path - and two short ones, its branch and
 * its origin; columns would slice that into unreadable strips in a tool window, and a
 * details pane would only repeat what the row already says. So each row carries the
 * whole truth on two lines: the name and origin on the first, the location (with the
 * one sentence that explains a default) under it. Selecting a row is what the toolbar
 * acts on; there is no second page to keep in step.
 *
 * The sections are the page's other job. `xmake repo --list` reports a project set and
 * a global set, but the global set also carries the three repositories xmake loads on
 * every start - the package index, the precompiled-binary mirror, and the repository
 * bundled inside its installation, whose location is a path on the toolkit host. A
 * user who never added anything would read all three as their own. The sections name
 * the origin and its meaning, and the default rows explain themselves.
 *
 * The page owns no repository state: it renders the service's one snapshot and the
 * message-bus topic keeps it in step with the Packages tab. The CLI's own verbs are the
 * toolbar's - add, remove, update-all, clear-one-set, plus refresh; mutations confirm
 * and stream into the XMake console.
 */
internal class XMakeRepositoriesPanel(
    internal val project: Project,
    parentDisposable: Disposable,
) : SimpleToolWindowPanel(true, false), Disposable {

    internal val service = XMakePackagesService.getInstance(project)

    /** The page's verbs; owns the console hop's scope. */
    private val actions = XMakeRepositoryActions(this)

    /** The rows, already ordered by origin - project, global, xmake defaults. */
    private val model = DefaultListModel<XMakeRepoState>()

    /** The list, one row per repository; the section label rides the first row of each
     *  origin so the section is never a selectable row of its own. */
    internal val list = object : JBList<XMakeRepoState>(model) {
        override fun getToolTipText(event: MouseEvent): String? {
            val index = locationToIndex(event.point)
            if (index !in 0 until model.size) return null
            val repo = model.getElementAt(index)
            return buildString {
                append("<html><b>").append(esc(repo.name)).append("</b>")
                append("<br>").append(esc(repo.url))
                repo.branch?.let { append("<br>Branch: ").append(esc(it)) }
                append("<br>").append(esc(scopeSentence(repo.scope)))
                defaultRepoNote(repo)?.let { append("<br>").append(esc(it)) }
                append("</html>")
            }
        }
    }.apply {
        selectionMode = ListSelectionModel.SINGLE_SELECTION
        fixedCellHeight = -1
        cellRenderer = RepositoryCellRenderer()
    }

    private val listScroll = JBScrollPane(list)

    /** The master column's canvas: the list, or the empty/loading/failure card when
     *  there is no listing to show. */
    private val masterHost = JPanel(BorderLayout())

    private val emptyCard = JBPanelWithEmptyText().apply {
        isOpaque = true
        background = mainBackground()
    }

    /** The filter above the list: a local narrowing - unlike the Packages search it
     *  never leaves the machine, so it answers as the letters land. */
    private val filterField = SearchTextField(true).apply {
        textEditor.emptyText.text = "Filter repositories"
        textEditor.putClientProperty("JTextField.Search.Gap", JBUI.scale(6))
        textEditor.putClientProperty("JTextField.Search.GapEmptyText", JBUI.scale(-1))
        textEditor.background = JBColor.namedColor("Plugins.SearchField.background", UIUtil.getListBackground())
        textEditor.document.addDocumentListener(object : com.intellij.ui.DocumentAdapter() {
            override fun textChanged(event: DocumentEvent) {
                rebuild(service.snapshot)
            }
        })
        exitFilterOnEscape(textEditor)
    }

    /** The page's only header strip: the filter over the list it narrows, the verbs at
     *  the row's right edge - the Packages tab's own header arrangement. */
    private val headerRow = BorderLayoutPanel().apply {
        isOpaque = true
        background = JBColor.namedColor("Plugins.SearchField.background", UIUtil.getListBackground())
        border = JBUI.Borders.compound(
            CustomLineBorder(infoBorderColor(), JBUI.insetsBottom(1)),
            JBUI.Borders.empty(6, 8),
        )
        addToCenter(filterField)
        addToRight(actions.toolbar().component)
    }

    /** The status line's label: the listing's size by origin, the operation in flight,
     *  the failure. */
    private val statusLabel = JBLabel()

    private val statusPanel = BorderLayoutPanel().apply {
        isOpaque = false
        border = JBUI.Borders.compound(
            CustomLineBorder(infoBorderColor(), JBUI.insetsTop(1)),
            JBUI.Borders.empty(4, 8),
        )
        addToCenter(statusLabel)
    }

    init {
        // Two clicks open the repository the row names - the list's own link idiom.
        list.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(event: MouseEvent) {
                if (event.clickCount != 2 || !SwingUtilities.isLeftMouseButton(event)) return
                val index = list.locationToIndex(event.point)
                if (index !in 0 until model.size) return
                val repo = model.getElementAt(index)
                if (isRemote(repo.url)) BrowserUtil.browse(repo.url)
            }
        })
        exitFilterOnEscape(list)
        masterHost.add(listScroll, BorderLayout.CENTER)

        setContent(
            JBUI.Panels.simplePanel()
                .addToCenter(
                    JPanel(BorderLayout()).apply {
                        add(headerRow, BorderLayout.NORTH)
                        add(masterHost, BorderLayout.CENTER)
                    },
                )
                .addToBottom(statusPanel),
        )

        project.messageBus.connect(parentDisposable).subscribe(
            XMakePackagesService.TOPIC,
            XMakePackagesService.Listener { snapshot ->
                invokeLater { if (!project.isDisposed) onSnapshot(snapshot) }
            },
        )
        // Both tabs are created together and the Packages tab issues the first refresh;
        // this guard covers a window whose Packages content was never constructed.
        if (service.snapshot == null && !service.busy && service.lastError == null) {
            service.refreshRepositories()
        }
        onSnapshot(service.snapshot)
    }

    /** The subscription rides the content's disposal; nothing else outlives the panel. */
    override fun dispose() {
        actions.dispose()
    }

    /** The selected repository; the toolbar's remove and clear verbs read it. */
    internal fun selectedRepository(): XMakeRepoState? = list.selectedValue

    /** The selected origin: the repository's own group. */
    internal fun selectedOrigin(): RepoOrigin? = selectedRepository()?.let(::originOf)

    /** One publish: rebuild the rows from the snapshot with the selection carried over,
     *  then re-narrate the status line. */
    private fun onSnapshot(snapshot: XMakePackagesSnapshot?) {
        rebuild(snapshot)
        updateSummary(snapshot)
    }

    private fun rebuild(snapshot: XMakePackagesSnapshot?) {
        val selectedName = selectedRepository()?.name
        val query = filterField.text.trim().lowercase()
        model.removeAllElements()

        if (snapshot != null) {
            snapshot.repositories
                .sortedBy { originOf(it).ordinal }
                .filter { matches(it, query) }
                .forEach(model::addElement)
        }

        when {
            snapshot == null && service.lastError != null -> {
                showEmpty("Could not list the repositories: ${service.lastError}", "Refresh") {
                    service.refreshRepositories()
                }
            }
            snapshot == null -> showEmpty("Loading the repositories...")
            snapshot.repositories.isEmpty() -> showEmpty(
                "No repositories - add one to resolve packages from",
                "Add Repository",
            ) { actions.addRepositoryDialog(defaultGlobal = false) }
            model.size == 0 && query.isNotEmpty() -> showEmpty("No repository matches '${filterField.text.trim()}'")
            else -> showList()
        }

        val restored = (0 until model.size).firstOrNull { model.getElementAt(it).name == selectedName }
        if (restored != null) {
            list.selectedIndex = restored
            list.ensureIndexIsVisible(restored)
        }
    }

    /** Whether a row survives [query]: its name, location, branch, or the words its
     *  origin reads as - "default" gathers xmake's three, "project" the item's own. */
    private fun matches(repo: XMakeRepoState, query: String): Boolean {
        if (query.isEmpty()) return true
        val origin = originOf(repo)
        return repo.name.lowercase().contains(query) ||
            repo.url.lowercase().contains(query) ||
            repo.branch?.lowercase()?.contains(query) == true ||
            repo.scope.lowercase().contains(query) ||
            origin.title.lowercase().contains(query) ||
            origin.hint.lowercase().contains(query)
    }

    /** Swaps the master column to the list. */
    private fun showList() {
        if (masterHost.components.firstOrNull() === listScroll) return
        masterHost.removeAll()
        masterHost.add(listScroll, BorderLayout.CENTER)
        masterHost.revalidate()
        masterHost.repaint()
    }

    /** Swaps the master column to the empty/loading/failure card, with an optional
     *  link - the state's own way out. */
    private fun showEmpty(text: String, link: String? = null, onLink: (() -> Unit)? = null) {
        list.clearSelection()
        val empty = emptyCard.emptyText
        empty.clear()
        empty.text = text
        if (link != null && onLink != null) {
            empty.appendSecondaryText(link, SimpleTextAttributes.LINK_PLAIN_ATTRIBUTES) { onLink() }
        }
        if (masterHost.components.firstOrNull() === emptyCard) return
        masterHost.removeAll()
        masterHost.add(emptyCard, BorderLayout.CENTER)
        masterHost.revalidate()
        masterHost.repaint()
    }

    /** The status line's narration: the listing's size split by origin, the operation
     *  in flight, and the last failure; red when the last operation failed. */
    private fun updateSummary(snapshot: XMakePackagesSnapshot?) {
        val repositories = snapshot?.repositories.orEmpty()
        statusLabel.text = buildString {
            when {
                snapshot == null && service.lastError != null ->
                    append("Could not list the repositories: ").append(service.lastError)
                snapshot == null -> append("Loading the repositories...")
                repositories.isEmpty() -> append("No repositories")
                else -> {
                    val counts = repositories.groupingBy(::originOf).eachCount()
                    val parts = listOfNotNull(
                        counts[RepoOrigin.PROJECT]?.takeIf { it > 0 }?.let { "$it project" },
                        counts[RepoOrigin.GLOBAL]?.takeIf { it > 0 }?.let { "$it global" },
                        counts[RepoOrigin.DEFAULT]?.takeIf { it > 0 }?.let { "$it from xmake" },
                    )
                    append(repositoryCount(repositories.size))
                    if (parts.isNotEmpty()) append(" - ").append(parts.joinToString(", "))
                }
            }
            service.currentOperation?.let { operation -> append(" - ").append(operation) }
            service.lastError?.let { append(" - last operation failed: ").append(it) }
        }
        statusLabel.foreground =
            if (service.lastError == null) UIUtil.getLabelForeground() else JBColor.RED
    }

    /** The single-line repository count the status line reads. */
    private fun repositoryCount(count: Int): String =
        "$count " + if (count == 1) "repository" else "repositories"

    /** One row: a section label above the first repository of each origin, then the
     *  name and origin on the first line and the location (or the default's one-line
     *  explanation) on the second. The section label rides the first row rather than
     *  being a row of its own, so it is never selectable and never breaks the toolbar. */
    private inner class RepositoryCellRenderer : JPanel(BorderLayout()), ListCellRenderer<XMakeRepoState> {

        private val section = JBLabel().apply {
            font = JBFont.label().deriveFont(Font.BOLD)
            border = JBUI.Borders.emptyBottom(3)
        }

        private val icon = JBLabel().apply {
            border = JBUI.Borders.emptyRight(8)
            verticalAlignment = SwingConstants.CENTER
        }

        private val nameLabel = JBLabel().apply {
            font = JBFont.label().deriveFont(Font.BOLD)
        }

        private val locationLabel = JBLabel()

        private val tag = JBLabel().apply {
            horizontalAlignment = SwingConstants.RIGHT
            border = JBUI.Borders.emptyLeft(8)
        }

        private val text = JPanel().apply {
            isOpaque = false
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            add(nameLabel)
            add(locationLabel)
        }

        private val row = JPanel(BorderLayout()).apply {
            isOpaque = false
            add(icon, BorderLayout.WEST)
            add(text, BorderLayout.CENTER)
            add(tag, BorderLayout.EAST)
        }

        init {
            isOpaque = true
            border = JBUI.Borders.empty(6, 10)
            add(
                JPanel(BorderLayout()).apply {
                    isOpaque = false
                    add(section, BorderLayout.NORTH)
                    add(row, BorderLayout.CENTER)
                },
                BorderLayout.CENTER,
            )
        }

        override fun getListCellRendererComponent(
            list: JList<out XMakeRepoState>,
            value: XMakeRepoState,
            index: Int,
            isSelected: Boolean,
            cellHasFocus: Boolean,
        ): Component {
            val origin = originOf(value)
            val firstOfOrigin = index == 0 || originOf(list.model.getElementAt(index - 1)) != origin
            section.isVisible = firstOfOrigin
            if (firstOfOrigin) {
                section.text = sectionText(origin)
            }
            nameLabel.text = value.name
            locationLabel.text = locationText(value)
            tag.text = tagText(value)
            icon.icon = if (isRemote(value.url)) AllIcons.Nodes.PpWeb else AllIcons.Nodes.Folder

            val cellBackground =
                if (isSelected) UIUtil.getListSelectionBackground(cellHasFocus) else UIUtil.getListBackground()
            val primary =
                if (isSelected) UIUtil.getListSelectionForeground(cellHasFocus) else UIUtil.getLabelForeground()
            val secondary = if (isSelected) ColorUtil.mix(primary, cellBackground, 0.25) else infoForeground()
            background = cellBackground
            nameLabel.foreground = primary
            locationLabel.foreground = secondary
            tag.foreground = secondary
            section.foreground = if (isSelected) secondary else infoForeground()
            return this
        }
    }

    /** The section's own words: the origin and what it means, in the list's gray. */
    private fun sectionText(origin: RepoOrigin): String = "${origin.title} - ${origin.hint}"

    /** The second line: the location, or - for xmake's defaults - the one phrase that
     *  says what the repository is for. The built-in's line names its installation
     *  role before the path, so the path never reads as a stray local directory. */
    private fun locationText(repo: XMakeRepoState): String = when {
        originOf(repo) != RepoOrigin.DEFAULT -> repo.url
        repo.name == "xmake-repo" -> "Package index - ${repo.url}"
        repo.name == "build-artifacts" -> "Precompiled binary artifacts - ${repo.url}"
        repo.name == "builtin-repo" -> "Bundled in the xmake installation - ${repo.url}"
        else -> repo.url
    }

    /** The right-hand tag: the origin word, and the branch when the repository pins
     *  one. The origin is repeated here because the section may be filtered away. */
    private fun tagText(repo: XMakeRepoState): String {
        val origin = when (originOf(repo)) {
            RepoOrigin.PROJECT -> "Project"
            RepoOrigin.GLOBAL -> "Global"
            RepoOrigin.DEFAULT -> if (isBuiltIn(repo)) "Bundled" else "Default"
        }
        return repo.branch?.let { "$origin · $it" } ?: origin
    }

    private fun esc(text: String): String = StringUtil.escapeXmlEntities(text)

    /** Esc clears the filter and restores every row - from the field as from the list. */
    private fun exitFilterOnEscape(component: JComponent) {
        component.getInputMap(JComponent.WHEN_FOCUSED)
            .put(KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0), EXIT_FILTER)
        component.actionMap.put(EXIT_FILTER, object : AbstractAction() {
            override fun actionPerformed(event: ActionEvent) {
                filterField.text = ""
            }
        })
    }

    private companion object {

        private const val EXIT_FILTER = "XMakeRepositories.exitFilter"
    }
}

/** Who put a repository there: the project's own additions, the user's global additions,
 *  or xmake itself. The origin decides the section and which verbs apply - only the
 *  first two can be removed or cleared. */
internal enum class RepoOrigin(val title: String, val hint: String) {
    PROJECT("Project", "this project only"),
    GLOBAL("Global", "shared by every project"),
    DEFAULT("xmake defaults", "always present"),
}

/** The three repositories xmake loads on its own every start: the community package
 *  index, the precompiled binary mirror, and the repository bundled in its install
 *  directory. A global entry with one of these names is xmake's default slot, not a
 *  repository the user added. */
internal val XMAKE_DEFAULT_REPOSITORIES: Set<String> =
    setOf("xmake-repo", "build-artifacts", "builtin-repo")

/** A repository's origin. Scope wins for local repositories: xmake's defaults are all
 *  global, so a local row is the user's by definition. */
internal fun originOf(repo: XMakeRepoState): RepoOrigin = when {
    repo.scope == "local" -> RepoOrigin.PROJECT
    repo.name in XMAKE_DEFAULT_REPOSITORIES -> RepoOrigin.DEFAULT
    else -> RepoOrigin.GLOBAL
}

/** What each of xmake's defaults is for - the sentence that turns a strange row into an
 *  understood one. Null for a repository xmake did not provide. */
internal fun defaultRepoNote(repo: XMakeRepoState): String? = when (repo.name) {
    "xmake-repo" ->
        "xmake's default package index: the community repository xmake require searches for packages."
    "build-artifacts" ->
        "xmake's mirror of precompiled package artifacts, which xmake tries before building a package from source."
    "builtin-repo" ->
        "Ships inside the xmake installation. Its location is a filesystem path on the toolkit host, " +
            "not a remote url - that is why this row has no address to open."
    else -> null
}

/** The scope's meaning in one sentence, for the row's tooltip. */
internal fun scopeSentence(scope: String): String = if (scope == "local") {
    "Local - this project's package searches resolve against it."
} else {
    "Global - every project on this machine resolves against it."
}

/** The word the toolbar's confirmations read: xmake's local scope is the project's set. */
internal fun repoScopeWord(repo: XMakeRepoState): String =
    if (repo.scope == "local") "project" else "global"

/** The built-in repository xmake loads from its own installation on every run. */
internal fun isBuiltIn(repo: XMakeRepoState): Boolean = repo.name == "builtin-repo"

/** Whether the repository's url is a remote one xmake clones with git; anything else
 *  is an on-disk path xmake reads in place. */
internal fun isRemote(url: String): Boolean {
    val lower = url.lowercase()
    return lower.startsWith("http://") ||
        lower.startsWith("https://") ||
        lower.startsWith("git@") ||
        lower.startsWith("ssh://") ||
        lower.startsWith("git://")
}
