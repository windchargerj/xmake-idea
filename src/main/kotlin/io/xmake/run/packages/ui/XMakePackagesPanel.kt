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

import com.intellij.icons.AllIcons
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionToolbar
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.actionSystem.Separator
import com.intellij.openapi.application.EDT
import com.intellij.openapi.application.invokeLater
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.ui.SimpleToolWindowPanel
import com.intellij.openapi.util.text.StringUtil
import com.intellij.ui.ColoredTableCellRenderer
import com.intellij.ui.ColorUtil
import com.intellij.ui.DoubleClickListener
import com.intellij.ui.JBColor
import com.intellij.ui.border.CustomLineBorder
import com.intellij.ui.OnePixelSplitter
import com.intellij.ui.ScrollPaneFactory
import com.intellij.ui.SearchTextField
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.TableSpeedSearch
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBPanelWithEmptyText
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.ui.table.TableView
import com.intellij.util.ui.AsyncProcessIcon
import com.intellij.util.ui.ColumnInfo
import com.intellij.util.ui.JBFont
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.ListTableModel
import com.intellij.util.ui.UIUtil
import com.intellij.util.ui.components.BorderLayoutPanel
import io.xmake.project.console.XMakeConsoleManager
import io.xmake.run.command.XMakeCommand
import io.xmake.run.packages.XMakePackageInfo
import io.xmake.run.packages.XMakePackagesService
import io.xmake.run.packages.XMakePackagesSnapshot
import io.xmake.utils.notify
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Dimension
import java.awt.Font
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import java.awt.RenderingHints
import java.awt.event.ActionEvent
import java.awt.event.KeyEvent
import java.awt.event.MouseEvent
import javax.swing.AbstractAction
import javax.swing.JEditorPane
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.JTable
import javax.swing.KeyStroke
import javax.swing.ListSelectionModel
import javax.swing.ScrollPaneConstants

/**
 * The XMake Packages view: the search field over the package table, and the selected
 * package's details in a headered pane on the right - the name in the Marketplace page's
 * title face, the repository meta beside it, the one action that fits the row, and the
 * `--info` data as the info grid below.
 *
 * The rows come from `require --list` - name, the version the project asks for, the
 * repository, and the installed/declared status that the lazily fetched, cached
 * `require --info` reports. Search swaps the rows to the repository hits, and Esc, or
 * an emptied field plus Enter, brings the declared list back. Dependencies are not
 * rows: the depgraph's answer for the selected package reads in the details grid.
 * Two clicks on a row open the install flow. Everything mutates only through a
 * confirmation naming the exact command line, then streams into the XMake console.
 * The view reflects; it never computes package state itself.
 */
internal class XMakePackagesPanel(
    private val project: Project,
    parentDisposable: Disposable,
) : SimpleToolWindowPanel(true, true), Disposable {

    private val service = XMakePackagesService.getInstance(project)

    /** The columns of the table: the name leading, the description the flexible one. */
    private val nameColumn = object : ColumnInfo<PackageRow, String>("Name") {

        override fun valueOf(item: PackageRow): String = item.name
    }

    private val versionColumn = object : ColumnInfo<PackageRow, String?>("Version") {

        /** The version of record: what `xmake.lua` pins, or the leading version a hit's
         *  repository offers - every further version counted beside it, the whole list
         *  readable in the details pane where the install links live. */
        override fun valueOf(item: PackageRow): String? = when {
            !item.isHit -> item.requirement
            item.versions.size > 1 -> item.version?.let { "$it (+${item.versions.size - 1})" }
            else -> item.version
        }
    }

    private val repoColumn = object : ColumnInfo<PackageRow, String?>("Repo") {

        override fun valueOf(item: PackageRow): String? =
            if (item.isHit) item.repo else sourceLabel(item.name, infoFor(item.name))
    }

    private val statusColumn = object : ColumnInfo<PackageRow, String?>("Status") {

        override fun valueOf(item: PackageRow): String? = statusOf(item)
    }

    private val descriptionColumn = object : ColumnInfo<PackageRow, String?>("Description") {

        override fun valueOf(item: PackageRow): String? = item.description
    }

    private val tableModel = ListTableModel<PackageRow>(
        nameColumn,
        descriptionColumn,
        versionColumn,
        repoColumn,
        statusColumn,
    )

    private val table = TableView(tableModel).apply {
        fillsViewportHeight = true
        selectionModel.selectionMode = ListSelectionModel.SINGLE_SELECTION
        // No grid, no cell spacing, taller rows: the stripes carry the rhythm instead of
        // lines, and the text sits in padded, vertically centered cells.
        rowHeight = JBUI.scale(28)
        setShowGrid(false)
        intercellSpacing = Dimension(0, 0)
        tableHeader.font = JBFont.label().biggerOn(-1f).deriveFont(Font.BOLD)
        tableHeader.reorderingAllowed = false
        columnModel.getColumn(COLUMN_NAME).minWidth = JBUI.scale(100)
        columnModel.getColumn(COLUMN_NAME).preferredWidth = JBUI.scale(120)
        columnModel.getColumn(COLUMN_DESCRIPTION).minWidth = JBUI.scale(180)
        columnModel.getColumn(COLUMN_DESCRIPTION).preferredWidth = JBUI.scale(400)
        columnModel.getColumn(COLUMN_VERSION).preferredWidth = JBUI.scale(90)
        columnModel.getColumn(COLUMN_REPO).preferredWidth = JBUI.scale(110)
        columnModel.getColumn(COLUMN_STATUS).preferredWidth = JBUI.scale(90)
        emptyText.text = LOADING
    }

    /** The table's scroll pane; a rebuild restores its viewport so a refresh keeps the
     *  user's place instead of throwing the rows back to the top. */
    private val tableScroll = ScrollPaneFactory.createScrollPane(table, true)

    /** The name cell: the package's own name in bold. */
    private val nameCellRenderer = object : CellRenderer() {

        override fun paintCell(value: Any?) {
            append(value?.toString().orEmpty(), SimpleTextAttributes.REGULAR_BOLD_ATTRIBUTES)
        }
    }

    /** The description cell: a hit's repository description, grayed - the column is the
     *  flexible one, so the text has room before the narrow columns. */
    private val descriptionCellRenderer = object : CellRenderer() {

        override fun paintCell(value: Any?) {
            append(value?.toString().orEmpty(), SimpleTextAttributes(SimpleTextAttributes.STYLE_PLAIN, infoForeground()))
        }
    }

    /** The Repo cell: grayed, like the analysis view's Location column. */
    private val repoCellRenderer = object : CellRenderer() {

        override fun paintCell(value: Any?) {
            (value as? String)?.let { append(it, SimpleTextAttributes(SimpleTextAttributes.STYLE_PLAIN, infoForeground())) }
        }
    }

    /** The Version cell: grayed like the other resolved facts. */
    private val versionCellRenderer = object : CellRenderer() {

        override fun paintCell(value: Any?) {
            append(value?.toString().orEmpty(), SimpleTextAttributes(SimpleTextAttributes.STYLE_PLAIN, infoForeground()))
        }
    }

    /** The Status cell: the state word in its badge color - the row and the details
     *  header read as one language. */
    private val statusCellRenderer = object : CellRenderer() {

        override fun paintCell(value: Any?) {
            when (value as? String) {
                INSTALLED -> append(INSTALLED, SimpleTextAttributes(SimpleTextAttributes.STYLE_PLAIN, badgeForeground(INSTALLED)))
                AVAILABLE -> append(AVAILABLE, SimpleTextAttributes(SimpleTextAttributes.STYLE_PLAIN, badgeForeground(AVAILABLE)))
                DECLARED -> append(DECLARED, SimpleTextAttributes(SimpleTextAttributes.STYLE_PLAIN, infoForeground()))
                else -> {}
            }
        }
    }

    /** The cell base: stripes the unselected rows, pads the cell so the text breathes
     *  and sits centered in the taller row. */
    private abstract inner class CellRenderer : ColoredTableCellRenderer() {

        override fun customizeCellRenderer(
            table: JTable,
            value: Any?,
            selected: Boolean,
            focused: Boolean,
            row: Int,
            column: Int,
        ) {
            border = JBUI.Borders.empty(5, 8, 5, 8)
            if (!selected) {
                background = if (row % 2 == 1) stripeColor() else UIUtil.getListBackground()
            }
            paintCell(value)
        }

        abstract fun paintCell(value: Any?)
    }

    /** The stripe one row deeper than the list background - the zebra that carries the
     *  rhythm now the grid lines are gone. */
    private fun stripeColor(): Color = JBColor.namedColor("Table.stripeColor", JBColor(0xF7F8FA, 0x40454B))

    /** The snapshot the table was built from; an info-only update repaints, never
     *  rebuilds, so a selection (and its loading detail) survives a background info
     *  landing. */
    private var tableSource: XMakePackagesSnapshot? = null

    /** Drives only the Show-in-Console hop onto the EDT; cancelled with the panel. */
    private val consoleScope = CoroutineScope(SupervisorJob() + Dispatchers.EDT)

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
     *  is, not the column's. */
    private val searchField = SearchTextField(true).apply {
        textEditor.emptyText.text = "Search packages"
        textEditor.putClientProperty("JTextField.Search.Gap", JBUI.scale(6))
        textEditor.putClientProperty("JTextField.Search.GapEmptyText", JBUI.scale(-1))
        textEditor.background = JBColor.namedColor("Plugins.SearchField.background", UIUtil.getListBackground())
        preferredSize = Dimension(JBUI.scale(220), preferredSize.height)
        textEditor.addActionListener {
            val term = text.trim()
            if (term.isEmpty()) {
                service.exitSearch()
            } else {
                service.search(term)
                // Behind a mutation the search queues; the line says so instead of the
                // field going silent. A claim the search started names itself in the
                // same line right away.
                service.pendingSearch?.let { queued -> narrateQueuedSearch(queued) }
                    ?: updateSummary(service.snapshot)
            }
        }
        exitSearchOnEscape(textEditor)
    }

    /** The details pane's title: the package name in the Marketplace header face. */
    private val detailsName = JBLabel().apply {
        font = JBFont.create(UIUtil.getLabelFont().deriveFont(Font.BOLD, 18f))
    }

    /** The badges the title row carries: the row's state and the platforms the package
     *  answers - the Marketplace header's tag row, attached to the name. */
    private val detailsBadges = JPanel(java.awt.FlowLayout(java.awt.FlowLayout.LEFT, JBUI.scale(6), 0)).apply {
        isOpaque = false
    }

    /** The details pane's meta line: repository, resolved version, the project's ask. */
    private val detailsMeta = JBLabel().apply { foreground = infoForeground() }

    /** The header stack: the title row - name and its badges - over the meta line, and
     *  a hairline under all of it. The actions live in the master column's toolbar; the
     *  header only describes. */
    private val detailsHeader = JPanel(GridBagLayout()).apply {
        isOpaque = true
        background = mainBackground()
        border = JBUI.Borders.compound(
            CustomLineBorder(infoBorderColor(), JBUI.insetsBottom(1)),
            JBUI.Borders.empty(10, 16, 8, 16),
        )
        fun row(gridy: Int, component: JComponent, stretch: Boolean = true) = add(
            component,
            GridBagConstraints().apply {
                this.gridy = gridy
                gridx = 0
                weightx = 1.0
                anchor = GridBagConstraints.WEST
                fill = if (stretch) GridBagConstraints.HORIZONTAL else GridBagConstraints.NONE
                insets = JBUI.insets(if (gridy == 0) 0 else 6, 0)
            },
        )
        row(0, javax.swing.Box.createHorizontalBox().apply {
            add(detailsName)
            add(javax.swing.Box.createHorizontalStrut(JBUI.scale(10)))
            add(detailsBadges)
        })
        row(1, detailsMeta, stretch = false)
    }

    /** The details page's body: one HTML document, so the typography is real - a
     *  paragraph for the description, lettered section headings, indented dependency
     *  entries, install and uninstall links beside each version. The links route back
     *  to the same confirmation flows the toolbar uses. */
    private val detailsBody = JEditorPane().apply {
        isEditable = false
        isOpaque = false
        contentType = "text/html"
        editorKit = htmlKit()
        border = JBUI.Borders.empty(8, 16, 12, 16)
        addHyperlinkListener { event ->
            if (event.eventType != javax.swing.event.HyperlinkEvent.EventType.ACTIVATED) return@addHyperlinkListener
            val target = event.description ?: return@addHyperlinkListener
            when {
                target.startsWith("action://install/") -> installPackage(target.removePrefix("action://install/"))
                target.startsWith("action://uninstall/") -> uninstallPackage(target.removePrefix("action://uninstall/"))
            }
        }
    }

    /** The info query's spinner; reused across rebuilds so one icon animates the wait,
     *  wherever the body currently sits. */
    private val infoLoading = AsyncProcessIcon("XMakePackagesInfoLoading")

    /** The loading card: the query rides the executor lane, over SSH in the worst case;
     *  the spinner says the click was heard. */
    private val detailsLoading = JPanel(java.awt.FlowLayout(java.awt.FlowLayout.LEFT, JBUI.scale(8), 0)).apply {
        isOpaque = true
        background = mainBackground()
        border = JBUI.Borders.empty(12, 16)
        add(infoLoading)
        add(JBLabel("Loading...").apply { foreground = infoForeground() })
    }

    /** The details pane as the Marketplace page lays it out: the header fixed, the page
     *  scrolling under it. */
    private val detailsPane = JPanel(BorderLayout()).apply {
        isOpaque = true
        background = mainBackground()
        add(detailsHeader, BorderLayout.NORTH)
        add(
            JBScrollPane(
                detailsBody,
                ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED,
                ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER,
            ).apply { border = JBUI.Borders.empty() },
            BorderLayout.CENTER,
        )
    }

    /** The nothing-selected card the Marketplace page shows instead of the details. */
    private val detailsEmpty = JBPanelWithEmptyText().apply {
        isOpaque = true
        background = mainBackground()
        emptyText.text = "Select a package to read its details"
    }

    /** The right pane of the split; swaps between the details, the loading card, and
     *  the nothing-selected card. */
    private val detailsCard = JPanel(BorderLayout())

    init {
        table.columnModel.getColumn(COLUMN_NAME).cellRenderer = nameCellRenderer
        table.columnModel.getColumn(COLUMN_DESCRIPTION).cellRenderer = descriptionCellRenderer
        table.columnModel.getColumn(COLUMN_VERSION).cellRenderer = versionCellRenderer
        table.columnModel.getColumn(COLUMN_REPO).cellRenderer = repoCellRenderer
        table.columnModel.getColumn(COLUMN_STATUS).cellRenderer = statusCellRenderer
        TableSpeedSearch.installOn(table)
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
        object : DoubleClickListener() {
            override fun onDoubleClick(event: MouseEvent): Boolean {
                val name = selectedPackageName() ?: return false
                installPackage(name)
                return true
            }
        }.installOn(table)

        // The master column's own header: the search field and the package actions on
        // one row - the tools live with the list they act on. A term typed while an
        // operation holds the lane is queued by the service and searched when the lane
        // frees. The hairline under the row is the Marketplace page's line between the
        // search field and the rows.
        val searchRow = BorderLayoutPanel().apply {
            isOpaque = true
            background = JBColor.namedColor("Plugins.SearchField.background", UIUtil.getListBackground())
            border = JBUI.Borders.compound(
                CustomLineBorder(infoBorderColor(), JBUI.insetsBottom(1)),
                JBUI.Borders.empty(6, 8),
            )
            addToCenter(searchField)
            addToRight(toolbar().component)
        }

        val split = OnePixelSplitter(false, SPLIT_KEY, 0.7f).apply {
            firstComponent = JPanel(BorderLayout()).apply {
                add(searchRow, BorderLayout.NORTH)
                add(tableScroll, BorderLayout.CENTER)
            }
            secondComponent = detailsCard
        }

        setContent(
            JBUI.Panels.simplePanel()
                .addToCenter(split)
                .addToBottom(statusPanel),
        )

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
        consoleScope.cancel()
        infoLoading.dispose()
    }

    private fun toolbar(): ActionToolbar {
        val group = DefaultActionGroup(
            RefreshAction(),
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
        tableModel.items = if (results != null) {
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
        } else {
            snapshot?.packages.orEmpty().map { entry ->
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
            else -> empty.text = "No packages declared"
        }
    }

    /** The fix a broken toolchain asks for, as one hint line: the missing unzip/7zip is
     *  the 2.8.7 repo query's cause of death (exit 255), and the exact command to run is
     *  worth more than the diagnosis repeated. */
    private fun unpackHint(): String? {
        val evidence = sequenceOf(service.lastError, service.snapshot?.searchResults?.diagnostics?.firstOrNull())
            .filterNotNull()
            .firstOrNull { it.contains("unzip", ignoreCase = true) || it.contains("7zip", ignoreCase = true) }
            ?: return null
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
            else -> "Press Refresh to list the declared packages"
        }
        val error = service.lastError
        statusLabel.text = buildString {
            append(base)
            // While an operation holds the pipeline the grayed buttons need their reason on
            // show - the same narration the Services panel's message line gives.
            service.currentOperation?.let { operation -> append(" - ").append(operation) }
            if (error != null) append(" - last operation failed: ").append(error)
        }
        statusLabel.foreground = if (error == null) UIUtil.getLabelForeground() else JBColor.RED
    }

    /** The package name the selection carries. */
    private fun selectedPackageName(): String? = table.selectedObject?.name

    private fun infoFor(name: String): XMakePackageInfo? = service.snapshot?.infos?.get(name)

    /** The status of a row, in the table's own words: the installed truth when the info
     *  cache holds one, "declared" for a project requirement otherwise - a hit is neither
     *  until its info lands on selection. */
    private fun statusOf(row: PackageRow): String? = when {
        infoFor(row.name)?.installed == true -> INSTALLED
        row.isHit -> AVAILABLE
        else -> DECLARED
    }

    /** The bare package name, the way the closure's require fields read. */
    private fun bareName(pkgRequire: String?): String? =
        pkgRequire?.trim()?.substringBefore(" ")?.substringAfterLast("::")?.lowercase()

    /** The row's source: the external-manager prefix for `vcpkg::`/`conan::` names, else
     *  the repository `--info` reported. */
    private fun sourceLabel(name: String, info: XMakePackageInfo?): String? {
        if ("::" in name) return "${name.substringBefore("::")}::"
        return info?.repoName?.let { "from $it" }
    }

    /**
     * Renders the selected package: the header (name, badges, meta) over the details
     * page, the loading card while the query rides the lane, or the nothing-selected
     * card.
     */
    private fun renderDetails(name: String?) {
        detailsPackage = name
        when {
            name == null -> {
                showDetailsCard(detailsEmpty)
                return
            }
            // A busy pipeline with a named operation is not loading this info at all -
            // the fetchInfo call was a no-op; say what the wait is really on.
            infoFor(name) == null && service.busy && service.currentOperation != null -> {
                showDetailsCard(detailsPane)
                detailsName.text = name
                renderBadges(name, tableModel.items.firstOrNull { it.name == name }, null)
                detailsMeta.text = " "
                detailsBody.text = wrapHtml(
                    "<p class='g'>Waiting for the current operation to finish before loading the info of " +
                        "'${esc(name)}' - ${esc(service.currentOperation ?: "working")} - then the row loads " +
                        "its info, or select it again</p>",
                )
                detailsBody.caretPosition = 0
            }
            infoFor(name) == null && service.busy -> {
                showDetailsCard(detailsLoading)
                return
            }
            infoFor(name) == null -> {
                showDetailsCard(detailsPane)
                detailsName.text = name
                renderBadges(name, tableModel.items.firstOrNull { it.name == name }, null)
                detailsMeta.text = " "
                detailsBody.text = wrapHtml(
                    buildString {
                        append("<p class='g'>No info available for '${esc(name)}' - the info query answered nothing usable")
                        service.lastError?.let { append(": ").append(esc(it)) }
                        append(".</p><p class='g'>")
                        append(unpackHint()?.let { esc(it) } ?: "The xmake on this host may not answer require --info; updating xmake may fix it")
                        append("</p>")
                    },
                )
                detailsBody.caretPosition = 0
            }
            else -> {
                showDetailsCard(detailsPane)
                detailsName.text = name
                val row = tableModel.items.firstOrNull { it.name == name }
                val info = infoFor(name)
                renderBadges(name, row, info)
                detailsMeta.text = when {
                    // A package that exists nowhere still reports version "latest"; the
                    // missing repo is the "not found" signal, not a failed query.
                    info != null && info.repoName == null -> "not found in the configured repositories"
                    else -> listOfNotNull(
                        row?.requirement?.let { "requires $it" },
                        info?.repoName ?: row?.repo,
                        info?.version ?: row?.version?.let { "v$it" },
                    ).joinToString(" · ").ifEmpty { " " }
                }
                detailsBody.text = wrapHtml(detailsHtml(name))
                detailsBody.caretPosition = 0
            }
        }
    }

    /** The badges the title row carries: the row's state, then the platforms the package
     *  answers - read fresh on every render, the way the meta line is. */
    private fun renderBadges(name: String, row: PackageRow?, info: XMakePackageInfo?) {
        detailsBadges.removeAll()
        val state = when {
            info?.installed == true -> Badge(INSTALLED, badgeForeground(INSTALLED), badgeBackground(INSTALLED))
            row?.isHit == true -> Badge(AVAILABLE, badgeForeground(AVAILABLE), badgeBackground(AVAILABLE))
            else -> Badge(DECLARED, badgeForeground(DECLARED), badgeBackground(DECLARED))
        }
        detailsBadges.add(Chip(state.text, state.foreground, state.background))
        info?.platforms?.forEach { platform ->
            detailsBadges.add(
                Chip(
                    platform,
                    infoForeground(),
                    JBColor.namedColor("Plugins.tagBackground", JBColor(0xEAEAEC, 0x4D4D4D)),
                ),
            )
        }
        detailsBadges.revalidate()
        detailsBadges.repaint()
    }

    /** Swaps the right pane among the three cards: the nothing-selected state, the
     *  details page, and the loading spinner. */
    private fun showDetailsCard(card: JComponent) {
        detailsCard.removeAll()
        detailsCard.add(card, BorderLayout.CENTER)
        detailsCard.revalidate()
        detailsCard.repaint()
    }

    /** The HTML kit the details page renders with: the label font as the base, the
     *  metadata gray for captions, the platform link color for the actions. */
    private fun htmlKit(): javax.swing.text.html.HTMLEditorKit {
        val kit = javax.swing.text.html.HTMLEditorKit()
        val base = UIUtil.getLabelFont()
        val gray = ColorUtil.toHex(infoForeground())
        val link = ColorUtil.toHex(JBColor.namedColor("Link.activeForeground", JBColor(0x1D73BF, 0x4A88C7)))
        kit.styleSheet.apply {
            addRule("body { font-family: ${base.family}; font-size: ${base.size}pt; }")
            addRule("p { margin: 0 0 8px; }")
            addRule(".sec { font-weight: bold; color: #$gray; margin: 14px 0 4px; }")
            addRule(".dep { margin: 0 0 8px 12px; }")
            addRule(".v { color: #$gray; }")
            addRule(".g { color: #$gray; }")
            addRule("a { color: #$link; text-decoration: none; }")
        }
        return kit
    }

    /** Escapes [text] for the HTML document the details page renders. */
    private fun esc(text: String): String = StringUtil.escapeXmlEntities(text)

    /** The selected package the details page currently renders; the version links route
     *  their confirmation flows through it. */
    private var detailsPackage: String? = null

    /** The document the details page renders for a package whose info landed. */
    private fun detailsHtml(name: String): String {
        val info = infoFor(name) ?: return ""
        val row = tableModel.items.firstOrNull { it.name == name }
        val page = StringBuilder()

        // The description leads: it is what the user came to read.
        info.description?.let { page.append("<p>").append(esc(it)).append("</p>") }

        // The closure the query answered, as its own section; where it said nothing,
        // the project's depgraph still names what the package needs.
        val closure = service.snapshot?.closures?.get(name).orEmpty()
        val asked = bareName(name)
        val dependencies = closure.filter { bareName(it.require) != asked }
        val declaredDependencies = service.snapshot?.depgraph?.packages
            ?.firstOrNull { it.name == name }?.deps
        if (dependencies.isNotEmpty()) {
            page.append("<p class='sec'>DEPENDENCIES - ${dependencies.size}</p>")
            dependencies.forEach { dependency ->
                page.append("<div class='dep'><b>").append(esc(dependency.require.ifEmpty { "?" })).append("</b>")
                dependency.version?.let { page.append(" <span class='v'>").append(esc(it)).append("</span>") }
                dependency.description?.let { page.append("<br><span class='g'>").append(esc(it)).append("</span>") }
                page.append("</div>")
            }
        } else if (!declaredDependencies.isNullOrEmpty()) {
            page.append("<p class='sec'>DEPENDENCIES</p><div class='dep'><span class='g'>")
                .append(esc(declaredDependencies.joinToString(", ")))
                .append("</span></div>")
        }

        // The versions the view knows of: the ones the search answered, each installable
        // at its pin; the installed reference, uninstallable. The installed marker only
        // lands when the info's references say so - the resolved version alone is what
        // the query answered, not a proof of installation. The toolbar's actions stay
        // whole-package - this section is where a version chooses.
        val versions = row?.versions.orEmpty()
        val installedNow = if (info.installed) info.version else null
        if (versions.isNotEmpty() || info.installed) {
            page.append("<p class='sec'>VERSIONS</p>")
            versions.forEach { version ->
                val pinned = "$name@$version"
                page.append("<div class='dep'><b>").append(esc(version)).append("</b>")
                if (version == installedNow) {
                    page.append(" <span class='g'>installed</span> <a href='action://uninstall/").append(esc(name))
                        .append("'>Uninstall</a>")
                } else {
                    page.append(" <span class='g'>available</span> <a href='action://install/").append(esc(pinned))
                        .append("'>Install</a>")
                }
                page.append("</div>")
            }
            if (installedNow != null && versions.none { it == installedNow }) {
                page.append("<div class='dep'><b>").append(esc(installedNow))
                    .append("</b> <span class='g'>installed</span> <a href='action://uninstall/").append(esc(name))
                    .append("'>Uninstall</a></div>")
            }
            info.references.orEmpty().forEach { reference ->
                page.append("<div class='dep'><span class='g'>")
                    .append(esc(elideMiddle(reference.dir ?: "?", 56)))
                reference.date?.let { page.append("   ").append(esc(it)) }
                page.append("</span></div>")
            }
        }

        // The facts the header cannot carry.
        page.append("<p class='sec'>INFO</p>")
        page.append("<div class='dep'>")
        info.installdir?.let { page.append("<span class='g'>Install directory:</span> ").append(esc(elideMiddle(it, 64))).append("<br>") }
        info.cachedir?.let { page.append("<span class='g'>Cache directory:</span> ").append(esc(elideMiddle(it, 64))).append("<br>") }
        if (info.installed) {
            page.append("<span class='g'>References:</span> ${info.references?.size ?: 0} - installed")
        }
        page.append("</div>")
        return page.toString()
    }

    /** Wraps [body] in the document shell the kit renders. */
    private fun wrapHtml(body: String): String =
        "<html><head></head><body>$body</body></html>"

    /** Cuts [text] through the middle once it passes [maxChars], keeping both ends. */
    private fun elideMiddle(text: String, maxChars: Int): String {
        if (text.length <= maxChars) return text
        val head = maxChars / 2
        val tail = maxChars - head
        return text.take(head) + " ... " + text.takeLast(tail)
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

    /** The Install flow the toolbar button, a double-click on a row, and the details
     *  page's version links share. [spec] is the xmake package ask: a bare name, or a
     *  name pinned to one version (`rsync@3.4.1`). */
    private fun installPackage(spec: String) {
        service.prepareCommand(prepare = { createRequireInstall(spec) }) { command ->
            confirmAndRun("Install package", "Installing '$spec' - xmake fetches, builds, and installs it.", command)
        }
    }

    /** The Uninstall flow the toolbar button and the details page's version link share. */
    private fun uninstallPackage(name: String) {
        service.prepareCommand(prepare = { createRequireUninstall(name) }) { command ->
            confirmAndRun("Uninstall package", "Uninstalling '$name'.", command)
        }
    }

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

    private inner class InstallAction : DumbAwareAction(
        "Install",
        "Install the selected package after confirming the exact command",
        AllIcons.Actions.Install,
    ) {

        override fun getActionUpdateThread() = ActionUpdateThread.EDT

        override fun update(event: AnActionEvent) {
            event.presentation.isEnabled = !service.busy && selectedPackageName() != null
        }

        override fun actionPerformed(event: AnActionEvent) {
            installPackage(selectedPackageName() ?: return)
        }
    }

    private inner class UpgradeAction : DumbAwareAction(
        "Upgrade",
        "Upgrade every installed package to the newest version its requirement allows (xmake require --upgrade)",
        AllIcons.Vcs.Fetch,
    ) {

        override fun getActionUpdateThread() = ActionUpdateThread.EDT

        override fun update(event: AnActionEvent) {
            event.presentation.isEnabled = !service.busy
        }

        override fun actionPerformed(event: AnActionEvent) {
            service.prepareCommand(prepare = { createRequireUpgrade() }) { command ->
                confirmAndRun(
                    "Upgrade packages",
                    "Upgrading every installed package to the newest version its requirement allows.",
                    command,
                )
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
            event.presentation.isEnabled = !service.busy && selectedPackageName() != null
        }

        override fun actionPerformed(event: AnActionEvent) {
            val name = selectedPackageName() ?: return
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

    /** One row of the packages table: a declared entry, or a repository hit while
     *  searching. A hit's row aggregates every version the repo answered for the name -
     *  the details page lists them with their install links. The info-driven cells (the
     *  resolved meta, the status) re-read the service's snapshot on paint. */
    private class PackageRow(
        val name: String,
        val requirement: String?,
        val version: String?,
        val description: String?,
        val repo: String?,
        val isHit: Boolean,
        val versions: List<String> = emptyList(),
    ) {

        override fun toString(): String = name
    }

    private companion object {

        /** The splitter's persistence key; the user's split survives restarts. */
        private const val SPLIT_KEY = "XMakePackagesView"

        /** The input-map key of the exit-search binding, shared by the table and the field. */
        private const val EXIT_SEARCH = "XMakePackages.exitSearch"

        private const val LOADING = "Loading the declared packages... - or press Refresh"

        private const val COLUMN_NAME = 0
        private const val COLUMN_DESCRIPTION = 1
        private const val COLUMN_VERSION = 2
        private const val COLUMN_REPO = 3
        private const val COLUMN_STATUS = 4
    }
}

/** The marketplace page's panel background, shared by the details pane and the list. */
internal fun mainBackground(): Color = JBColor.namedColor("Plugins.background", UIUtil.getListBackground())

/** The marketplace page's hairline color, shared by the row separators. */
private fun infoBorderColor(): Color = JBColor.namedColor("Plugins.borderColor", JBColor.border())

/** The gray the metadata reads in - the Marketplace meta line's color. */
internal fun infoForeground(): Color = JBColor.namedColor("Label.infoForeground", UIUtil.getLabelInfoForeground())

/** The row's state words; the table, the details header, and the badges read them. */
internal const val INSTALLED = "installed"
internal const val DECLARED = "declared"
internal const val AVAILABLE = "available"

/** The badge palette of one state word: green reads installed, blue available, gray declared. */
internal data class Badge(val text: String, val foreground: Color, val background: Color)

internal fun badgeForeground(state: String): Color = when (state) {
    INSTALLED -> JBColor(0x3F7E33, 0x57965C)
    AVAILABLE -> JBColor(0x1D73BF, 0x4A88C7)
    else -> infoForeground()
}

internal fun badgeBackground(state: String): Color = when (state) {
    INSTALLED -> JBColor(0xE1F6DA, 0x314131)
    AVAILABLE -> JBColor(0xD8EDF8, 0x27363F)
    else -> JBColor.namedColor("Plugins.tagBackground", JBColor(0xEAEAEC, 0x4D4D4D))
}

/** One badge: a rounded chip carrying a word - the state, or a platform the package
 *  answers - attached to the name, the way the Marketplace header carries its tags. */
internal class Chip(text: String, foreground: Color, background: Color) : JPanel(null) {

    private val label = JBLabel(text)

    init {
        isOpaque = false
        label.foreground = foreground
        label.font = JBFont.label()
        add(label)
    }

    override fun paintComponent(graphics: Graphics) {
        val g = graphics as Graphics2D
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        g.color = background
        g.fillRoundRect(0, 0, width, height, height, height)
    }

    override fun getPreferredSize(): java.awt.Dimension {
        val text = label.preferredSize
        return java.awt.Dimension(text.width + PAD_X * 2, text.height + PAD_Y * 2)
    }

    override fun doLayout() {
        label.setBounds(PAD_X, (height - label.preferredSize.height) / 2, label.preferredSize.width, label.preferredSize.height)
    }

    private companion object {
        private const val PAD_X = 12
        private const val PAD_Y = 4
    }
}
