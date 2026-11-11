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
package io.xmake.run.packages.ui

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.invokeLater
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.SimpleToolWindowPanel
import com.intellij.ui.DoubleClickListener
import com.intellij.ui.JBColor
import com.intellij.ui.border.CustomLineBorder
import com.intellij.ui.OnePixelSplitter
import com.intellij.ui.SearchTextField
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.TableSpeedSearch
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.ListTableModel
import com.intellij.util.ui.UIUtil
import com.intellij.util.ui.components.BorderLayoutPanel
import io.xmake.run.packages.XMakePackageInfo
import io.xmake.run.packages.XMakePackagesService
import io.xmake.run.packages.XMakePackagesSnapshot
import java.awt.BorderLayout
import java.awt.Color
import java.awt.event.ActionEvent
import java.awt.event.KeyEvent
import java.awt.event.MouseEvent
import javax.swing.AbstractAction
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.KeyStroke

/**
 * The Packages tab of the XMake Packages tool window: the search field over the package
 * table, and the selected package's details in a headered pane on the right - the name
 * in the Marketplace page's title face, the repository meta beside it, the one action
 * that fits the row, and the `--info` data as the info grid below. The repositories the
 * packages resolve against have their own tab ([XMakeRepositoriesPanel]); this page
 * only names them in the rows and the details.
 *
 * The rows come from `require --list` - name, the version the project asks for, the
 * repository, and the installed/declared status that the lazily fetched, cached
 * `require --info` reports. Search swaps the rows to the repository hits, and Esc, or
 * an emptied field plus Enter, brings the declared list back. Dependencies are not
 * rows: the depgraph's answer for the selected package reads in the details grid.
 * Two clicks on a row open the install flow, an installed row excluded - the
 * toolbar's guard holds there too. Everything mutates only through a
 * confirmation naming the exact command line, then streams into the XMake console.
 * The view reflects; it never computes package state itself.
 */
internal class XMakePackagesPanel(
    internal val project: Project,
    parentDisposable: Disposable,
) : SimpleToolWindowPanel(true, true), Disposable {

    internal val service = XMakePackagesService.getInstance(project)

    /** The panel's verbs, as one composed table; owns the console hop's scope. */
    private val actions = XMakePackageActions(this)

    /** The right-hand page: the selected package's facts, versions, and links. */
    private val details = XMakePackageDetailsPane(this)

    /** The master list: columns, model, sorter, renderers - one collaborator. */
    private val tableComponent = XMakePackageTable(this)

    internal val tableModel get() = tableComponent.tableModel

    internal val table get() = tableComponent.table

    internal val tableSorter get() = tableComponent.tableSorter

    /** The row's status word - the table family's own triage, forwarded. */
    internal fun statusOf(row: PackageRow): String? = tableComponent.statusOf(row)

    internal val tableScroll get() = tableComponent.tableScroll

    /** The snapshot the table was built from; an info-only update repaints, never
     *  rebuilds, so a selection (and its loading detail) survives a background info
     *  landing. */
    private var tableSource: XMakePackagesSnapshot? = null

    /** The master/details splitter; the table on the left, the details page right. */
    private lateinit var split: OnePixelSplitter

    /** The status line's label: the list's size, the search mode, the operation the grayed
     *  actions wait for, and the failure note; red when the last operation failed. */
    private val statusLabel = JBLabel()

    /** The bottom row itself: a hairline above, the narration on the left - the VCS
     *  commit view's status panel, reporting the list the way the analysis view reports
     *  the run. */
    private val statusPanel = BorderLayoutPanel().apply {
        isOpaque = false
        border = JBUI.Borders.compound(
            CustomLineBorder(infoBorderColor(), JBUI.insetsTop(1)),
            JBUI.Borders.empty(4, 8),
        )
        addToCenter(statusLabel)
    }

    /** The toolbar's search field; kept so an Esc exit can empty it with the view. The
     *  history popup and the tuned paddings are the Marketplace search field's, and it
     *  sits over the list - the master column's own header - at a width a search field
     *  is, not the column's. Typing filters the rows in view as it lands; Enter hands
     *  the term to the repositories. */
    private val searchField = SearchTextField(true).apply {
        textEditor.emptyText.text = "Search packages"
        textEditor.putClientProperty("JTextField.Search.Gap", JBUI.scale(6))
        textEditor.putClientProperty("JTextField.Search.GapEmptyText", JBUI.scale(-1))
        textEditor.background = JBColor.namedColor("Plugins.SearchField.background", UIUtil.getListBackground())
        textEditor.document.addDocumentListener(object : com.intellij.ui.DocumentAdapter() {
            override fun textChanged(event: javax.swing.event.DocumentEvent) {
                applyRowFilter(text)
                // The rows already narrow as the letters land; the repositories are asked
                // once the typing pauses - the Marketplace page's own bargain between
                // feeling instant and not firing a remote query per keystroke.
                searchDebounce.restart()
            }
        })
        textEditor.addActionListener {
            // Enter asks now rather than on the pause: the user has said the term is done.
            searchDebounce.stop()
            searchNow(text)
        }
        exitSearchOnEscape(textEditor)
    }

    /** Fires the repository query once typing pauses; one timer for the field's life, so
     *  every keystroke replaces the pending ask instead of stacking one. */
    private val searchDebounce: javax.swing.Timer by lazy {
        javax.swing.Timer(SEARCH_DEBOUNCE_MS) { searchNow(searchField.text) }
    }

    /** Hands [rawTerm] to the repositories - the debounce and Enter both land here. An
     *  empty term leaves search mode; a one-letter term asks nothing of the
     *  repositories, and the line says so instead of the Enter landing silent. */
    private fun searchNow(rawTerm: String) {
        val term = rawTerm.trim()
        when {
            term.isEmpty() -> service.exitSearch()
            term.length < MIN_SEARCH_LENGTH -> {
                // The repositories hear nothing of a one-letter ask; without this line
                // the Enter would land silent and read as dead.
                statusLabel.text = "Type at least $MIN_SEARCH_LENGTH characters to search the repositories"
            }
            else -> {
                service.search(term)
                // Behind a mutation the search queues; the line says so instead of the
                // field going silent. A claim the search started names itself in the
                // same line right away.
                service.pendingSearch?.let { queued -> narrateQueuedSearch(queued) }
                    ?: updateSummary(service.snapshot)
            }
        }
    }

    /** Filters the rows in view by [term]: the name, the description, or the status word
     *  containing it - "available" gathers everything the repositories offer. Empty shows
     *  everything again. The sorter applies the filter before its order. */
    private fun applyRowFilter(term: String) {
        val trimmed = term.trim()
        val query = trimmed.lowercase()
        tableSorter.rowFilter = if (query.isEmpty()) {
            null
        } else {
            object : javax.swing.RowFilter<ListTableModel<PackageRow>, Int>() {
                override fun include(
                    entry: Entry<out ListTableModel<PackageRow>, out Int>,
                ): Boolean {
                    val pkg = tableModel.getItem(entry.identifier)
                    return pkg.name.lowercase().contains(query) ||
                        pkg.description?.lowercase()?.contains(query) == true ||
                        statusOf(pkg)?.lowercase()?.contains(query) == true
                }
            }
        }
        // The empty state speaks for what is on screen: a filter that hides every row
        // says so with the term as typed, not the lowercased one the match ran on - and
        // while the first load is still landing there are no rows to miss yet, so the
        // loading narration stays instead of a false "no package matches".
        val empty = table.emptyText
        when {
            service.snapshot == null -> {}
            query.isNotEmpty() && table.rowCount == 0 -> {
                empty.clear()
                empty.text = "No package matches '$trimmed'"
            }
            else -> applyEmptyText(service.snapshot)
        }
    }

    /** The master column's header row: the search field and the package actions. */
    private lateinit var searchRow: JPanel

    /** The band the master column's two header strips occupy - the search row plus the
     *  table header - measured from the components themselves. The details header takes
     *  this height, so the two hairlines under the strips land on the same y. */
    internal fun headerBandHeight(): Int =
        if (::searchRow.isInitialized) {
            searchRow.preferredSize.height + table.tableHeader.preferredSize.height
        } else {
            0
        }

    /** The versions of [name] the package store holds on disk: the cache the probe's
     *  targeted scan, the full inventory, and the refills after a mutation all write -
     *  one cache, one truth, keyed by the bare name. */
    internal fun onDiskVersions(name: String): Set<String> =
        bareName(name)
            ?.let { bare -> service.snapshot?.installedVersions?.get(bare) }
            .orEmpty()
            .toSet()

    init {        TableSpeedSearch.installOn(table)
        // Selecting a row is what fetches its info; the cache makes re-selection free.
        table.selectionModel.addListSelectionListener { event ->
            if (event.valueIsAdjusting) return@addListSelectionListener
            selectedPackageName()?.let { name ->
                service.fetchInfo(name)
                renderDetails(name)
            } ?: renderDetails(null)
        }
        // Esc leaves search mode from wherever focus sits; the search field gets the same
        // binding when it is created.
        exitSearchOnEscape(table)
        // The platform's own double-click handling (the Analysis view's pattern): two
        // clicks on a row are the Install entry - where a search hit starts its flow.
        // The toolbar's guard rides along: an installed row does not reinstall, so the
        // double-click stays quiet instead of re-asking what the button refuses.
        object : DoubleClickListener() {
            override fun onDoubleClick(event: MouseEvent): Boolean {
                val row = selectedRow() ?: return false
                if (infoFor(row.name)?.installed == true || row.isInstalled) return true
                actions.installPackage(row.name)
                return true
            }
        }.installOn(table)

        // The master column's own header: the search field and the package actions on
        // one row - the tools live with the list they act on. A term typed while an
        // operation holds the lane is queued by the service and searched when the lane
        // frees. The hairline under the row is the Marketplace page's line between the
        // search field and the rows.
        searchRow = BorderLayoutPanel().apply {
            isOpaque = true
            background = JBColor.namedColor("Plugins.SearchField.background", UIUtil.getListBackground())
            border = JBUI.Borders.compound(
                CustomLineBorder(infoBorderColor(), JBUI.insetsBottom(1)),
                JBUI.Borders.empty(6, 8),
            )
            // The field rides the left slot at the 220 width its preference declares; a
            // center slot would stretch it across the column.
            addToCenter(searchField)
            addToRight(actions.toolbar().component)
        }

        split = OnePixelSplitter(false, SPLIT_KEY, 0.7f).apply {
            firstComponent = JPanel(BorderLayout()).apply {
                add(searchRow, BorderLayout.NORTH)
                add(tableScroll, BorderLayout.CENTER)
            }
            secondComponent = details.detailsCard
        }

        setContent(
            JBUI.Panels.simplePanel()
                .addToCenter(split)
                .addToBottom(statusPanel),
        )
        // The status line promises Esc without naming a component, so the exit-search
        // action also rides the root's WHEN_IN_FOCUSED_WINDOW map: focus on the toolbar,
        // the details page, or anywhere else in the panel leaves search mode just the
        // same. The table and the field keep their focused bindings.
        getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW)
            .put(KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0), EXIT_SEARCH)
        actionMap.put(EXIT_SEARCH, object : AbstractAction() {
            override fun actionPerformed(event: ActionEvent) {
                leaveSearch()
            }
        })

        project.messageBus.connect(parentDisposable).subscribe(
            XMakePackagesService.TOPIC,
            XMakePackagesService.Listener { snapshot ->
                invokeLater { if (!project.isDisposed) onSnapshot(snapshot) }
            },
        )
        // Whatever the service already holds, then the first load; the subscription is in
        // place first so an in-flight refresh cannot land unseen.
        onSnapshot(service.snapshot)
        renderDetails(null)
        service.refresh()
    }

    /** The subscriptions ride on the content's disposal; nothing else outlives the panel. */
    override fun dispose() {
        actions.dispose()
        searchDebounce.stop()
        details.dispose()
    }

    /** One publish: rebuild the rows when their shape changed, repaint for a fresh info,
     *  and re-render the details of whatever stays selected. A failed first load publishes
     *  null over null - no identity change - yet must still reach [rebuild], or the table
     *  would keep promising "Loading" while only the status line knows the failure. */
    private fun onSnapshot(snapshot: XMakePackagesSnapshot?) {
        val structureChanged = snapshot?.packages !== tableSource?.packages ||
                snapshot?.depgraph !== tableSource?.depgraph ||
                snapshot?.searchResults !== tableSource?.searchResults
        tableSource = snapshot
        if (structureChanged || (snapshot == null && service.lastError != null)) {
            rebuild(snapshot)
        } else {
            // The info cache moved: the rows' meta and status re-read it on paint.
            table.repaint()
        }
        updateSummary(snapshot)
        // A selection made while a refresh held the lane gets its info now; cached or busy
        // calls inside are no-ops.
        selectedPackageName()?.let { name ->
            service.fetchInfo(name)
            renderDetails(name)
            table.repaint()
        }
    }

    /** Swaps the rows: search results while searching, else the declared packages. The
     *  hits aggregate by name - one row per package carrying every version the repo
     *  answered, the leading one in the Version cell and the rest counted there. A
     *  rebuild is a refresh, not a reset: the scroll position and the selection carry
     *  over, so a post-action refresh reads as the rows updating in place. */
    private fun rebuild(snapshot: XMakePackagesSnapshot?) {
        val selectedName = selectedPackageName()
        val position = tableScroll.viewport.viewPosition
        val results = snapshot?.searchResults
        val installed = snapshot?.installed
        tableModel.items = when {
            // The installed inventory: what `--scan` reports as actually on disk.
            installed != null -> installed.map { pkg ->
                PackageRow(
                    name = pkg.require,
                    requirement = null,
                    version = pkg.version,
                    description = pkg.description,
                    repo = null,
                    isHit = false,
                    isInstalled = true,
                )
            }
            results != null -> {
                results.hits
                    .groupBy { it.name }
                    .map { (name, hits) ->
                        val lead = hits.first()
                        PackageRow(
                            name = name,
                            requirement = null,
                            version = lead.version,
                            description = lead.description,
                            repo = lead.repo,
                            isHit = true,
                            versions = hits.mapNotNull { it.version },
                        )
                    }
            }
            else -> snapshot?.packages.orEmpty().map { entry ->
                PackageRow(
                    name = entry.name,
                    requirement = entry.requirement.ifEmpty { null },
                    version = null,
                    description = infoFor(entry.name)?.description,
                    repo = null,
                    isHit = false,
                )
            }
        }
        applyEmptyText(snapshot)
        restoreSelection(selectedName)
        tableScroll.viewport.viewPosition = position
        // The viewport revalidates after the items swap; re-apply once layout has settled,
        // or the fresh rows would still pull it back to the top.
        invokeLater { tableScroll.viewport.viewPosition = position }
    }

    /** The bare table speaks through its own StatusText: the state's words, and every way
     *  out a platform link - Refresh after a failure, the declared list after a miss. */
    private fun applyEmptyText(snapshot: XMakePackagesSnapshot?) {
        val empty = table.emptyText
        empty.clear()
        val results = snapshot?.searchResults
        when {
            snapshot == null && service.lastError != null -> {
                empty.text = "Could not list packages: ${service.lastError}"
                unpackHint()?.let { hint -> empty.appendSecondaryText(hint, SimpleTextAttributes.GRAYED_ATTRIBUTES, null as java.awt.event.ActionListener?) }
                empty.appendSecondaryText(
                    "Refresh",
                    SimpleTextAttributes.LINK_PLAIN_ATTRIBUTES,
                ) { service.refresh() }
            }
            snapshot == null -> empty.text = LOADING
            results != null -> {
                // Zero hits plus a diagnostic is a broken toolchain answering exit 0
                // (xmake 2.8.7 without unzip/7zip), not a misspelled term - the line is
                // the difference between "try another term" and "fix your xmake"
                // (docs/roadmap-designs/audit-packages.md, sections 1.6 and 3.1).
                empty.text = if (results.diagnostics.isEmpty()) "No hits - try another term" else "No hits"
                results.diagnostics.firstOrNull()?.let {
                    empty.appendSecondaryText(
                        "xmake reported: $it",
                        SimpleTextAttributes.GRAYED_ATTRIBUTES,
                        null as java.awt.event.ActionListener?,
                    )
                    unpackHint()?.let { hint ->
                        empty.appendSecondaryText(hint, SimpleTextAttributes.GRAYED_ATTRIBUTES, null as java.awt.event.ActionListener?)
                    }
                }
                empty.appendSecondaryText(
                    "Back to the declared packages",
                    SimpleTextAttributes.LINK_PLAIN_ATTRIBUTES,
                ) { leaveSearch() }
            }
            else -> {
                empty.text = "No packages declared"
                empty.appendSecondaryText(
                    "Type in the search field to find a package, or declare add_requires(...) in xmake.lua",
                    SimpleTextAttributes.GRAYED_ATTRIBUTES,
                    null as java.awt.event.ActionListener?,
                )
            }
        }
    }

    /** The fix a broken toolchain asks for, as one hint line: the missing unzip/7zip is
     *  the 2.8.7 repo query's cause of death (exit 255), and the exact command to run is
     *  worth more than the diagnosis repeated. */
    internal fun unpackHint(): String? {
        val brokenToolchain = sequenceOf(service.lastError, service.snapshot?.searchResults?.diagnostics?.firstOrNull())
            .filterNotNull()
            .any { it.contains("unzip", ignoreCase = true) || it.contains("7zip", ignoreCase = true) }
        if (!brokenToolchain) return null
        return "fix: install unzip or p7zip on the toolkit host (e.g. sudo apt install unzip), or update xmake"
    }

    /** Puts the selection back on the row carrying [name] - quietly: no scroll-to-visible,
     *  the restored viewport wins, and the selection listener's info fetch is a cache hit
     *  or the ordinary lazy load. */
    private fun restoreSelection(name: String?) {
        if (name == null) return
        val modelRow = tableModel.items.indexOfFirst { it.name == name }
        if (modelRow < 0) return
        val viewRow = table.convertRowIndexToView(modelRow)
        table.setRowSelectionInterval(viewRow, viewRow)
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
            else -> "Loading the declared packages..."
        }
        val filtered = table.rowCount
        val allRows = tableModel.rowCount
        val error = service.lastError
        statusLabel.text = buildString {
            append(base)
            if (filtered != allRows) append(" - ").append(filtered).append(" of ").append(allRows).append(" shown")
            // While an operation holds the pipeline the grayed buttons need their reason on
            // show - the same narration the Services panel's message line gives.
            service.currentOperation?.let { operation -> append(" - ").append(operation) }
            if (error != null) append(" - last operation failed: ").append(error)
        }
        statusLabel.foreground = if (error == null) UIUtil.getLabelForeground() else JBColor.RED
    }

    /** The package name the selection carries - through the sorter's view-to-model
     *  conversion, since the rows may be re-sorted. */
    private fun selectedPackageName(): String? = selectedRow()?.name

    /** The details page renders through its collaborator; the panel forwards. */
    internal fun renderDetails(name: String?) = details.renderDetails(name)

    /** The flows the details links and the double-click share; the actions own them. */
    internal fun installPackage(ask: String) = actions.installPackage(ask)

    internal fun uninstallPackage(ask: String) = actions.uninstallPackage(ask)

    /** The selected row itself, for the state its info query has not answered yet - the
     *  installed inventory's rows carry their proof on the row, not in an info. */
    internal fun selectedRow(): PackageRow? {
        val viewRow = table.selectedRow
        if (viewRow < 0) return null
        return tableModel.getItem(table.convertRowIndexToModel(viewRow))
    }

    internal fun infoFor(name: String): XMakePackageInfo? = service.snapshot?.infos?.get(name)

    /** The failed info ask's own complaint, when the ask landed one - the quiet probe
     *  records its failures here instead of anywhere in the busy state. */
    internal fun failureFor(name: String): String? = service.snapshot?.infoFailures?.get(name)

    /** The bare package name, the way the closure's require fields read. */
    internal fun bareName(pkgRequire: String?): String? =
        pkgRequire?.trim()?.substringBefore(" ")?.substringAfterLast("::")?.lowercase()

    /** Esc leaves search mode from the table as from the field - and empties the field,
     *  so it does not claim a term the view no longer filters by. Outside search mode it
     *  is a no-op. A mutation started from a hit keeps the view (the hits ride across
     *  its refresh); a read in flight is abandoned, a queued search dropped. */
    private fun leaveSearch() {
        if (service.snapshot?.searchResults == null) return
        if (service.busyWithMutation && service.pendingSearch == null) return
        searchField.text = ""
        service.exitSearch()
    }

    private fun exitSearchOnEscape(component: JComponent) {
        component.getInputMap(JComponent.WHEN_FOCUSED)
            .put(KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0), EXIT_SEARCH)
        component.actionMap.put(EXIT_SEARCH, object : AbstractAction() {
            override fun actionPerformed(event: ActionEvent) {
                leaveSearch()
            }
        })
    }

    /** The narration an Enter pressed while a mutation holds the lane gets - the search
     *  is queued, so the line names what will run, not a retype (docs/roadmap-designs/
     *  audit-packages.md, section 3.3). The next publish overwrites it. */
    private fun narrateQueuedSearch(term: String) {
        statusLabel.text = buildString {
            append("Searching for '$term' once the current operation finishes")
            service.currentOperation?.let { operation -> append(" - ").append(operation) }
            service.lastError?.let { append(" - last operation failed: ").append(it) }
        }
    }

    /** One row of the packages table: a declared entry, or a repository hit while
     *  searching. A hit's row aggregates every version the repo answered for the name -
     *  the details page lists them with their install links. The info-driven cells (the
     *  resolved meta, the status) re-read the service's snapshot on paint. */
    private companion object {

        /** The splitter's persistence key; the user's split survives restarts. */
        private const val SPLIT_KEY = "XMakePackagesView"

        /** The input-map key of the exit-search binding, shared by the table, the field,
         *  and the panel root's window-wide map. */
        private const val EXIT_SEARCH = "XMakePackages.exitSearch"

        private const val LOADING = "Loading the declared packages..."

        /** How long typing must pause before the repositories are asked. */
        private const val SEARCH_DEBOUNCE_MS = 600

        /** Shorter than this, a term asks nothing of the repositories - one letter
         *  matches too much to be worth a remote round trip. */
        private const val MIN_SEARCH_LENGTH = 2
    }
}

/** Where xmake documents a package: its index entry and the guide, told from its name. */
internal fun xmakePackageLinks(name: String?): XMakePackageLinks {
    val guide = "https://xmake.io/guide/package-management/package-management-in-project.html"
    if (name.isNullOrBlank()) return XMakePackageLinks(docs = null, guide = null)
    val bare = name.trim()
    // An external-manager package has no entry in the index; the guide still applies.
    if ("::" in bare) return XMakePackageLinks(docs = null, guide = guide)
    val encoded = java.net.URLEncoder.encode(bare, Charsets.UTF_8).replace("+", "%20")
    return XMakePackageLinks(docs = "https://packages.xmake.io/packages/$encoded/", guide = guide)
}

/** The two xmake pages a package answers: its own entry in the package index - the
 *  documentation of versions, license, and install commands - and the guide that
 *  explains using packages in a project at all. */
internal data class XMakePackageLinks(val docs: String?, val guide: String?)

/** The marketplace page's panel background, shared by the details pane and the list. */
internal fun mainBackground(): Color = JBColor.namedColor("Plugins.background", UIUtil.getListBackground())

/** The marketplace page's hairline color, shared by the row separators. */
internal fun infoBorderColor(): Color = JBColor.namedColor("Plugins.borderColor", JBColor.border())

/** The gray the metadata reads in - the Marketplace meta line's color. */
internal fun infoForeground(): Color = JBColor.namedColor("Label.infoForeground", UIUtil.getLabelInfoForeground())

/** The row's state words; the table, the details header, and the badges read them. */
internal const val INSTALLED = "installed"
internal const val DECLARED = "declared"
internal const val AVAILABLE = "available"

/** The badge palette of one state word: green reads installed, blue available, gray declared. */
internal fun badgeForeground(state: String): Color = when (state) {
    INSTALLED -> JBColor(0x3F7E33, 0x57965C)
    AVAILABLE -> JBColor(0x1D73BF, 0x4A88C7)
    else -> infoForeground()
}


internal class PackageRow(
    val name: String,
    val requirement: String?,
    val version: String?,
    val description: String?,
    val repo: String?,
    val isHit: Boolean,
    val versions: List<String> = emptyList(),
    /** True for the rows of the installed inventory - their status reads INSTALLED
     *  without an info query, since the scan already proved it. */
    val isInstalled: Boolean = false,
) {

    override fun toString(): String = name
}
