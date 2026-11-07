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
import io.xmake.project.toolkit.XMakeCapability
import io.xmake.run.command.XMakeCommand
import io.xmake.run.command.XMakeCommandFactory
import io.xmake.run.packages.XMakePackageInfo
import io.xmake.run.packages.XMakePackagesService
import io.xmake.run.packages.XMakePackagesSnapshot
import io.xmake.utils.cli.XMakePackageSpec
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
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.JTable
import javax.swing.JTextPane
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
 * Two clicks on a row open the install flow, an installed row excluded - the
 * toolbar's guard holds there too. Everything mutates only through a
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

    /** The header-click sorter: one click sorts the column, another flips the direction -
     *  the arrows render in the platform header. The status column sorts by its state's
     *  weight, so the groups land installed, declared, available - the order the eye
     *  triages. The version column counts the dot-separated segments numerically, so
     *  1.10.0 lands after 1.9.0 where the lexicographic order would read it ahead. */
    private val tableSorter = javax.swing.table.TableRowSorter(tableModel).apply {
        setComparator(COLUMN_VERSION, java.util.Comparator<String> { left, right ->
            compareVersions(left, right)
        })
        setComparator(COLUMN_STATUS, java.util.Comparator<String> { left, right ->
            statusWeight(left) - statusWeight(right)
        })
    }

    private val table = TableView(tableModel).apply {
        fillsViewportHeight = true
        rowSorter = tableSorter
        selectionModel.selectionMode = ListSelectionModel.SINGLE_SELECTION
        // No grid, no cell spacing, taller rows: the stripes carry the rhythm instead of
        // lines, and the text sits in padded, vertically centered cells.
        rowHeight = JBUI.scale(28)
        setShowGrid(false)
        intercellSpacing = Dimension(0, 0)
        tableHeader.font = JBFont.label().biggerOn(-1f).deriveFont(Font.BOLD)
        tableHeader.reorderingAllowed = false
        // The header sorts on click; the hand says so. A column's edge still drags its
        // width - the drag works even while the hand shows.
        tableHeader.cursor = java.awt.Cursor.getPredefinedCursor(java.awt.Cursor.HAND_CURSOR)
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

    /** Hands [term] to the repositories - the debounce and Enter both land here. An
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

    /** The details pane's title: the package name in the Marketplace header face. */
    private val detailsName = JBLabel().apply {
        font = JBFont.create(UIUtil.getLabelFont().deriveFont(Font.BOLD, 18f))
        alignmentY = 0.5f
    }

    /** The badges the title row carries: the state the row reads, then the platforms the
     *  package answers - the Marketplace header's tag row, attached to the name. */
    private val detailsBadges = JPanel(java.awt.FlowLayout(java.awt.FlowLayout.LEFT, JBUI.scale(6), 0)).apply {
        isOpaque = false
        alignmentY = 0.5f
    }

    /** The details pane's meta line: the state, then the project's ask, repository, and
     *  resolved version in one gray run. */
    private val detailsMeta = JBLabel().apply { foreground = infoForeground() }

    /** The repository page, as a link at the header's right edge - the one place a
     *  reader leaves the IDE to. The link data is the url, so a render replaces the
     *  whole label rather than mutating it. */
    private var detailsRepoLink: JComponent = JPanel()

    /** The header's link row: the package's own project page and its documentation,
     *  told from the source archive the query reports. A forge repository answers both -
     *  the project page is its root, the docs its readme; any other host answers its
     *  site root as the homepage and nothing as docs. Neither link goes to the package
     *  definition or the repository index: those are plumbing, not the project. */
    private fun packageLinks(name: String?): JComponent? {
        val (docs, guide) = xmakePackageLinks(name)
        val links = javax.swing.Box.createHorizontalBox()
        var any = false
        fun addLink(label: String, url: String) {
            if (any) links.add(javax.swing.Box.createHorizontalStrut(JBUI.scale(12)))
            links.add(
                com.intellij.ui.components.labels.LinkLabel<String>(
                    label,
                    com.intellij.icons.AllIcons.Ide.External_link_arrow,
                ).apply {
                    verticalAlignment = javax.swing.SwingConstants.CENTER
                    setListener(
                        { _, target: String? ->
                            target?.takeIf(String::isNotEmpty)?.let(com.intellij.ide.BrowserUtil::browse)
                        },
                        url,
                    )
                },
            )
            any = true
        }
        docs?.let { addLink("Docs", it) }
        guide?.let { addLink("Guide", it) }
        return links.takeIf { any }
    }

    /** The header's right slot: the repository link when the query carried one, empty
     *  otherwise - a render replaces its content. */
    private val detailsRepoSlot = BorderLayoutPanel().apply { isOpaque = false }

    /** The second header row: the meta facts at the left, the repository link at the
     *  right edge - the row that keeps the header from reading as an empty band. */
    private val detailsHeaderFacts = BorderLayoutPanel().apply {
        isOpaque = false
        addToCenter(detailsMeta)
        addToRight(detailsRepoSlot)
    }

    /** The band the master column's two header strips occupy - the search row plus the
     *  table header - measured from the components themselves. The details header takes
     *  this height, so the two hairlines under the strips land on the same y. */
    internal fun headerBandHeight(): Int =
        if (::searchRow.isInitialized) {
            searchRow.preferredSize.height + table.tableHeader.preferredSize.height
        } else {
            0
        }

    /** The master column's header strip; assigned in init, before any layout runs. */
    private lateinit var searchRow: JPanel

    /** The header stack: the title row - name and its badges - over the facts row, and
     *  a hairline under all of it, at the master column's band height. The actions live
     *  in the master column's toolbar; the header only describes. */
    private val detailsHeader = object : JPanel(GridBagLayout()) {
        override fun getPreferredSize(): Dimension {
            val own = super.preferredSize
            val band = headerBandHeight()
            return Dimension(own.width, if (band > 0) band else own.height)
        }
    }.apply {
        isOpaque = true
        background = mainBackground()
        border = JBUI.Borders.compound(
            CustomLineBorder(infoBorderColor(), JBUI.insetsBottom(1)),
            JBUI.Borders.empty(10, 12, 8, 12),
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
        row(1, detailsHeaderFacts)
    }

    /** The details page's body: one HTML document, so the typography is real - a
     *  paragraph for the description, lettered section headings, indented dependency
     *  entries, install and uninstall links beside each version. The links route back
     *  to the same confirmation flows the toolbar uses, and the loading row embeds its
     *  spinner here - hence the text pane, the editor pane's component-embedding one. */
    private val detailsBody = JTextPane().apply {
        isEditable = false
        isOpaque = false
        contentType = "text/html"
        editorKit = htmlKit()
        border = JBUI.Borders.empty(8, 12, 12, 12)
        addHyperlinkListener { event ->
            if (event.eventType != javax.swing.event.HyperlinkEvent.EventType.ACTIVATED) return@addHyperlinkListener
            val target = event.description ?: return@addHyperlinkListener
            when {
                target.startsWith("action://install/") -> installPackage(target.removePrefix("action://install/"))
                target.startsWith("action://uninstall/") -> uninstallPackage(target.removePrefix("action://uninstall/"))
            }
        }
    }

    /** The info query's spinner: the loading row embeds it in the details body, so one
     *  icon animates the wait - the query rides the executor lane, over SSH in the
     *  worst case, and the icon says the click was heard - reused across renders. */
    private val infoLoading = AsyncProcessIcon("XMakePackagesInfoLoading")

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

    /** The right pane of the split; swaps between the details page and the
     *  nothing-selected card - the loading state renders in the page's body. */
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
        // The toolbar's guard rides along: an installed row does not reinstall, so the
        // double-click stays quiet instead of re-asking what the button refuses.
        object : DoubleClickListener() {
            override fun onDoubleClick(event: MouseEvent): Boolean {
                val row = selectedRow() ?: return false
                if (infoFor(row.name)?.installed == true || row.isInstalled) return true
                installPackage(row.name)
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
        consoleScope.cancel()
        searchDebounce.stop()
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
            FetchAction(),
            DownloadAction(),
            CheckAction(),
            ExportAction(),
            ImportAction(),
            CleanAction(),
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

    /** The selected row itself, for the state its info query has not answered yet - the
     *  installed inventory's rows carry their proof on the row, not in an info. */
    private fun selectedRow(): PackageRow? {
        val viewRow = table.selectedRow
        if (viewRow < 0) return null
        return tableModel.getItem(table.convertRowIndexToModel(viewRow))
    }

    private fun infoFor(name: String): XMakePackageInfo? = service.snapshot?.infos?.get(name)

    /** The failed info ask's own complaint, when the ask landed one - the quiet probe
     *  records its failures here instead of anywhere in the busy state. */
    private fun failureFor(name: String): String? = service.snapshot?.infoFailures?.get(name)

    /** The status of a row, in the table's own words: the installed truth when the info
     *  cache holds one, "declared" for a project requirement otherwise - a hit is neither
     *  until its info lands on selection. */
    private fun statusOf(row: PackageRow): String? = when {
        infoFor(row.name)?.installed == true -> INSTALLED
        row.isHit -> AVAILABLE
        row.isInstalled -> INSTALLED
        else -> DECLARED
    }

    /** The triage weight behind the status sort: installed first, then the project's own
     *  declarations, then what the repositories merely offer. */
    private fun statusWeight(state: String?): Int = when (state) {
        INSTALLED -> 0
        DECLARED -> 1
        AVAILABLE -> 2
        else -> 3
    }

    /** The version sort's order: the dot-separated segments count numerically, so
     *  1.10.0 lands after 1.9.0 - the lexicographic order reads the ten as a one. A
     *  segment's leading digits are the number (the "(+n)" count a hit's cell carries
     *  rides along harmlessly), a segment without digits counts 0, and a missing
     *  version sorts last. */
    private fun compareVersions(left: String?, right: String?): Int {
        if (left == null && right == null) return 0
        if (left == null) return 1
        if (right == null) return -1
        val leftSegments = left.split('.')
        val rightSegments = right.split('.')
        for (index in 0 until maxOf(leftSegments.size, rightSegments.size)) {
            val diff = leftSegments[index].leadingNumber() - rightSegments[index].leadingNumber()
            if (diff != 0) return diff
        }
        return 0
    }

    /** The number a version segment opens with: its leading digits, or 0 without any. */
    private fun String.leadingNumber(): Int = takeWhile(Char::isDigit).toIntOrNull() ?: 0

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
     * page - whose body carries the spinner row while the info is not there yet - or
     * the nothing-selected card. The spinner holds during an exclusive operation too:
     * the probe rides the quiet lane, so the answer lands live even mid-build.
     */
    private fun renderDetails(name: String?) {
        detailsPackage = name
        when {
            name == null -> {
                showDetailsCard(detailsEmpty)
                return
            }
            // No info record and no failure record: the quiet probe is in flight - the
            // spinner holds until its answer lands on the snapshot.
            infoFor(name) == null && failureFor(name) == null -> {
                showDetailsCard(detailsPane)
                detailsName.text = name
                renderBadges(name, tableModel.items.firstOrNull { it.name == name }, null)
                detailsMeta.text = " "
                renderRepoLink(null)
                detailsBody.text = wrapHtml("<p class='g'>Loading '${esc(name)}'...</p>")
                detailsBody.caretPosition = 0
                detailsBody.insertComponent(infoLoading)
            }
            infoFor(name) == null -> {
                showDetailsCard(detailsPane)
                detailsName.text = name
                renderBadges(name, tableModel.items.firstOrNull { it.name == name }, null)
                detailsMeta.text = " "
                renderRepoLink(null)
                // The probe's own complaint when there is one - "xmake answered
                // garbage" reads differently from "never asked" or "the process died".
                val why = failureFor(name)
                detailsBody.text = wrapHtml(
                    buildString {
                        append("<p class='g'>No info available for '${esc(name)}'")
                        why?.let { append(" - ").append(esc(it)) }
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
                renderRepoLink(name)
                detailsBody.text = wrapHtml(detailsHtml(name))
                detailsBody.caretPosition = 0
            }
        }
    }

    /** The badges the title row carries: the row's state first - its tone carries the
     *  meaning, so the pill is a quiet tint rather than a shouting slab - then the
     *  platforms the package answers. Read fresh on every render, like the meta line. */
    private fun renderBadges(name: String, row: PackageRow?, info: XMakePackageInfo?) {
        detailsBadges.removeAll()
        val state = when {
            info?.installed == true -> INSTALLED
            row?.isHit == true -> AVAILABLE
            else -> DECLARED
        }
        detailsBadges.add(Chip(state, badgeForeground(state)))
        info?.platforms?.forEach { platform ->
            detailsBadges.add(Chip(platform, infoForeground()))
        }
        detailsBadges.revalidate()
        detailsBadges.repaint()
    }

    /** Puts the repository link - or nothing - in the header's right slot. */
    private fun renderRepoLink(name: String?) {
        detailsRepoSlot.removeAll()
        packageLinks(name)?.let { detailsRepoSlot.addToRight(it) }
        detailsRepoSlot.revalidate()
        detailsRepoSlot.repaint()
    }

    /** Swaps the right pane between the two cards: the nothing-selected state and the
     *  details page - the loading state renders in the page's body. */
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
        val green = ColorUtil.toHex(badgeForeground(INSTALLED))
        val mono = java.awt.Font(java.awt.Font.MONOSPACED, java.awt.Font.PLAIN, base.size).family
        kit.styleSheet.apply {
            addRule("body { font-family: ${base.family}; font-size: ${base.size}pt; }")
            addRule("p { margin: 0 0 8px; }")
            addRule(".sec { font-weight: bold; color: #$gray; margin: 14px 0 4px; }")
            addRule(".dep { margin: 0 0 8px 12px; }")
            addRule(".v { color: #$gray; }")
            addRule(".g { color: #$gray; }")
            // The state word wears the badge's green: the table's Status column, the
            // header's badge, and this word read as one language.
            addRule(".ok { color: #$green; }")
            addRule(".count { color: #$gray; font-weight: normal; }")
            // Paths read in the mono face one point down - the editor's own convention
            // for anything a user may copy into a shell.
            addRule(".mono { font-family: $mono; font-size: ${base.size - 1}pt; color: #$gray; }")
            addRule("a { color: #$link; text-decoration: none; }")
        }
        return kit
    }

    /** Escapes [text] for the HTML document the details page renders. */
    private fun esc(text: String): String = StringUtil.escapeXmlEntities(text)

    /** The selected package the details page currently renders; the version links route
     *  their confirmation flows through it. */
    private var detailsPackage: String? = null

    /** The versions of [name] the package store holds on disk: the cache the probe's
     *  targeted scan, the full inventory, and the refills after a mutation all write -
     *  one cache, one truth, keyed by the bare name. */
    private fun onDiskVersions(name: String): Set<String> =
        bareName(name)
            ?.let { bare -> service.snapshot?.installedVersions?.get(bare) }
            .orEmpty()
            .toSet()

    /** The document the details page renders for a package whose info landed. */
    private fun detailsHtml(name: String): String {
        val info = infoFor(name) ?: return ""
        val row = tableModel.items.firstOrNull { it.name == name }
        val page = StringBuilder()

        // The description leads: it is what the user came to read. The versions follow
        // before the dependencies - choosing which version to install or remove is the
        // action the page serves, and it must not sit below fourteen entries of closure.
        info.description?.let { page.append("<p>").append(esc(it)).append("</p>") }

        // The versions the view knows of: the ones the search answered, plus every
        // version the store holds on disk (the targeted scan rode the info query). Each
        // version reads its true state - the one this project resolves to and references
        // is "in use", the rest the store holds are "installed", the others "available" -
        // and each pin installs or uninstalls. xmake's grammar pins a version with a
        // space ("zstd 1.5.6") - the @ separates a repository from the name. The
        // toolbar's actions stay whole-package - this section is where a version chooses.
        val versions = row?.versions.orEmpty()
        val installedNow = if (info.installed) info.version else null
        val onDisk = onDiskVersions(name)
        val allVersions = versions + onDisk.filter { onDiskVersion -> versions.none { it == onDiskVersion } }
        if (allVersions.isNotEmpty()) {
            page.append("<p class='sec'>VERSIONS <span class='count'>(")
                .append(allVersions.size).append(")</span></p>")
            allVersions.forEach { version ->
                val pinned = "$name $version"
                page.append("<div class='dep'><b>").append(esc(version)).append("</b>")
                when {
                    version == installedNow -> {
                        page.append(" <span class='ok'>in use</span> <a href='action://uninstall/")
                            .append(esc(pinned)).append("'>Uninstall</a>")
                    }
                    version in onDisk -> {
                        page.append(" <span class='ok'>installed</span> <a href='action://uninstall/")
                            .append(esc(pinned)).append("'>Uninstall</a>")
                    }
                    else -> {
                        page.append(" <span class='g'>available</span> <a href='action://install/")
                            .append(esc(pinned)).append("'>Install</a>")
                    }
                }
                page.append("</div>")
            }
        }

        // The facts sit before the closure: the paths answer "where is it", which a
        // reader checks before asking what it needs.
        page.append("<p class='sec'>INFO</p>")
        page.append("<div class='dep'>")
        info.installdir?.let {
            page.append("<span class='g'>Install directory:</span> <span class='mono'>")
                .append(esc(elideMiddle(it, 48))).append("</span><br>")
        }
        info.cachedir?.let {
            page.append("<span class='g'>Cache directory:</span> <span class='mono'>")
                .append(esc(elideMiddle(it, 48))).append("</span><br>")
        }
        page.append("</div>")

        // The closure closes the page - the deepest detail, read last.
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

    /** The one mutation shape every action reads: the title, the ask's sentence, and the
     *  command built on the factory - whose toolkit resolution runs off the EDT inside
     *  [XMakePackagesService.prepareCommand], while this confirmation stays on it. */
    private fun confirmAndRun(
        title: String,
        what: String,
        prepare: XMakeCommandFactory.() -> XMakeCommand,
    ) {
        service.prepareCommand(prepare = prepare) { command ->
            confirmAndRun(title, what, command)
        }
    }

    /** The Install flow the toolbar button, a double-click on a row, and the details
     *  page's version links share. [ask] is the xmake package ask: a bare name, or a
     *  name pinned to one version (`rsync 3.4.1`) - parsed into a spec here, the one
     *  grammar the command ever sees. */
    private fun installPackage(ask: String) {
        val spec = XMakePackageSpec.parse(ask)
        confirmAndRun("Install package", "Installing '${spec.render()}' - xmake fetches, builds, and installs it.") {
            createRequireInstall(spec)
        }
    }

    /** The Uninstall flow the toolbar button and the details page's version link share.
     *  [ask] is the xmake package ask: a bare name, or a name pinned to one version
     *  (`zstd 1.5.6`) - the pin decides which installed version's directory xmake removes. */
    private fun uninstallPackage(ask: String) {
        val spec = XMakePackageSpec.parse(ask)
        confirmAndRun("Uninstall package", "Uninstalling '${spec.render()}'.") {
            createRequireUninstall(spec)
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

    /** The package housekeeping verbs act on the selected package; the helpers below
     *  route each through the confirmation flow the install action uses. */
    private fun selectedOrNotify(): String? = selectedPackageName().also {
        if (it == null) statusLabel.text = "Select a package first"
    }

    private inner class FetchAction : DumbAwareAction(
        "Fetch",
        "Fetch the package's library info from the repository (xmake require --fetch)",
        AllIcons.General.Information,
    ) {

        override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT

        override fun update(event: AnActionEvent) {
            event.presentation.isEnabled = !service.busy && selectedPackageName() != null
        }

        override fun actionPerformed(event: AnActionEvent) {
            val name = selectedOrNotify() ?: return
            confirmAndRun("Fetch package", "Fetching the library info of '$name'.") {
                createRequireFetch(XMakePackageSpec.parse(name))
            }
        }
    }

    private inner class DownloadAction : DumbAwareAction(
        "Download source",
        "Download the package's source archive without installing (xmake require --download; " +
            "xmake 3.1.2+ - the button answers for the toolkit it runs on)",
        com.intellij.icons.AllIcons.Actions.Download,
    ) {

        override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT

        override fun update(event: AnActionEvent) {
            event.presentation.isEnabled = !service.busy && selectedPackageName() != null &&
                service.supports(XMakeCapability.REQUIRE_DOWNLOAD)
        }

        override fun actionPerformed(event: AnActionEvent) {
            val name = selectedOrNotify() ?: return
            confirmAndRun("Download source", "Downloading the source archive of '$name'.") {
                createRequireDownload(XMakePackageSpec.parse(name))
            }
        }
    }

    private inner class CheckAction : DumbAwareAction(
        "Check support",
        "Whether this package is supported on the current platform (xmake require --check; " +
            "xmake 3.1.2+ - the button answers for the toolkit it runs on)",
        com.intellij.icons.AllIcons.Actions.Help,
    ) {

        override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT

        override fun update(event: AnActionEvent) {
            event.presentation.isEnabled = !service.busy && selectedPackageName() != null &&
                service.supports(XMakeCapability.REQUIRE_CHECK)
        }

        override fun actionPerformed(event: AnActionEvent) {
            val name = selectedOrNotify() ?: return
            confirmAndRun("Check support", "Checking whether '$name' is supported here.") {
                createRequireCheck(XMakePackageSpec.parse(name))
            }
        }
    }

    private inner class ExportAction : DumbAwareAction(
        "Export",
        "Export the installed package and its dependencies to a portable archive (xmake require --export)",
        com.intellij.icons.AllIcons.ToolbarDecorator.Export,
    ) {

        override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT

        override fun update(event: AnActionEvent) {
            val name = selectedPackageName()
            event.presentation.isEnabled = !service.busy && name != null &&
                (infoFor(name)?.installed == true || selectedRow()?.isInstalled == true)
        }

        override fun actionPerformed(event: AnActionEvent) {
            val name = selectedOrNotify() ?: return
            // The destination is xmake's own default: a "packages" directory in the
            // project. Naming it is what makes the verb honest - an export whose
            // destination nobody knows reads as a no-op.
            confirmAndRun(
                "Export package",
                "Exporting the installed '$name' and its dependencies into the project's " +
                    "'packages' directory.",
            ) { createRequireExport(XMakePackageSpec.parse(name)) }
        }
    }

    private inner class ImportAction : DumbAwareAction(
        "Import",
        "Import previously exported packages (xmake require --import)",
        com.intellij.icons.AllIcons.ToolbarDecorator.Import,
    ) {

        override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT

        override fun update(event: AnActionEvent) {
            event.presentation.isEnabled = !service.busy
        }

        override fun actionPerformed(event: AnActionEvent) {
            // --import takes package NAMES, searched in --packagedir (default "packages"
            // in the project) - xmake's own help text and its import implementation read
            // the arguments as requires, not as an archive path.
            val name = com.intellij.openapi.ui.Messages.showInputDialog(
                project,
                "Package to import from the project's 'packages' directory:",
                "Import packages",
                null,
            )?.trim().takeUnless { it.isNullOrEmpty() } ?: return
            confirmAndRun("Import packages", "Importing '$name' from the 'packages' directory.") {
                createRequireImport(XMakePackageSpec.parse(name))
            }
        }
    }

    private inner class CleanAction : DumbAwareAction(
        "Clean",
        "Clear the package caches and uninstall the packages nothing references; with a " +
            "selection, just that package (xmake require --clean)",
        com.intellij.icons.AllIcons.Actions.GC,
    ) {

        override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT

        override fun update(event: AnActionEvent) {
            event.presentation.isEnabled = !service.busy
        }

        override fun actionPerformed(event: AnActionEvent) {
            // xmake's own semantics, both 2.8.7 and 3.1.2: --clean clears the caches and
            // uninstalls the NOT-REFERENCED packages - the projects still using one keep
            // it. The names scope the sweep to a selection when there is one.
            val name = selectedPackageName()
            confirmAndRun(
                "Clean packages",
                if (name == null) {
                    "Clearing the package caches and uninstalling the packages no project references."
                } else {
                    "Clearing the caches of '$name' and uninstalling it if nothing references it."
                },
            ) {
                createRequireClean(name?.let { listOf(XMakePackageSpec.parse(it)) }.orEmpty())
            }
        }
    }

    /** Toggles the table between the declared packages and the `--scan` inventory of
     *  what is actually installed. The scan runs once on first show; the snapshot keeps
     *  the inventory, so re-entering the view costs nothing until a refresh drops it. */
    private inner class ShowInstalledToggle : DumbAwareAction(
        "Installed",
        "Show the packages actually installed (xmake require --scan)",
        com.intellij.icons.AllIcons.Nodes.PackageLocal,
    ) {

        private var showing = false

        override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT

        override fun update(event: AnActionEvent) {
            event.presentation.isEnabled = !service.busy
        }

        override fun actionPerformed(event: AnActionEvent) {
            showing = !showing
            if (showing) {
                service.scanInstalled()
            } else {
                service.exitInstalled()
            }
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
            val row = selectedRow()
            event.presentation.isEnabled = !service.busy && row != null &&
                infoFor(row.name)?.installed != true && !row.isInstalled
        }

        override fun actionPerformed(event: AnActionEvent) {
            installPackage(selectedPackageName() ?: return)
        }
    }

    private inner class UpgradeAction : DumbAwareAction(
        "Upgrade",
        "Upgrade the selected package, or every declared one without a selection, to the " +
            "newest version its requirement allows (xmake require --upgrade)",
        AllIcons.Actions.Upload,
    ) {

        override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT

        override fun update(event: AnActionEvent) {
            event.presentation.isEnabled = !service.busy
        }

        override fun actionPerformed(event: AnActionEvent) {
            val name = selectedPackageName()
            confirmAndRun(
                "Upgrade packages",
                if (name == null) {
                    "Upgrading every declared package to the newest version its requirement allows."
                } else {
                    "Upgrading '$name' to the newest version its requirement allows."
                },
            ) { createRequireUpgrade(name?.let(XMakePackageSpec::parse)) }
        }
    }

    private inner class UninstallAction : DumbAwareAction(
        "Uninstall",
        "Uninstall the selected package after confirming the exact command",
        AllIcons.Actions.Uninstall,
    ) {

        override fun getActionUpdateThread() = ActionUpdateThread.EDT

        override fun update(event: AnActionEvent) {
            val name = selectedPackageName()
            event.presentation.isEnabled = !service.busy && name != null &&
                (infoFor(name)?.installed == true ||
                        selectedRow()?.isInstalled == true ||
                        onDiskVersions(name).isNotEmpty())
        }

        override fun actionPerformed(event: AnActionEvent) {
            val row = selectedRow() ?: return
            val name = row.name
            // Bare-name uninstall resolves "latest" and misses a non-latest install; the
            // version the row or the info vouches for is what gets pinned. An installed
            // view's row names its version outright; a declared row falls to the info's
            // resolved version, then to the first the store holds.
            val version = row.takeIf { it.isInstalled }?.version
                ?: infoFor(name)?.version?.takeIf { infoFor(name)?.installed == true }
                ?: onDiskVersions(name).firstOrNull()
            val spec = XMakePackageSpec(name).pinnedTo(version)
            confirmAndRun("Uninstall package", "Uninstalling '${spec.render()}'.") {
                createRequireUninstall(spec)
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
        /** True for the rows of the installed inventory - their status reads INSTALLED
         *  without an info query, since the scan already proved it. */
        val isInstalled: Boolean = false,
    ) {

        override fun toString(): String = name
    }

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

        private const val COLUMN_NAME = 0
        private const val COLUMN_DESCRIPTION = 1
        private const val COLUMN_VERSION = 2
        private const val COLUMN_REPO = 3
        private const val COLUMN_STATUS = 4
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
private fun infoBorderColor(): Color = JBColor.namedColor("Plugins.borderColor", JBColor.border())

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

/** One badge: a pill carrying a word - the state, or a platform the package answers -
 *  attached to the name, the way the Marketplace header carries its tags.
 *
 *  The fill is the tone at a low alpha over the pane, not a solid slab: it stays soft in
 *  both themes, the text keeps full contrast, and a hairline of the same tone closes the
 *  edge. Horizontal padding is generous, vertical snug - the proportion that reads as a
 *  label rather than a button. */
internal class Chip(text: String, private val tone: Color) : JPanel(null) {

    private val label = JBLabel(text)

    init {
        isOpaque = false
        alignmentY = 0.5f
        label.foreground = tone
        label.font = JBFont.label()
        add(label)
    }

    override fun paintComponent(graphics: Graphics) {
        val g = graphics as Graphics2D
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        val radius = height
        g.color = ColorUtil.toAlpha(tone, 26)
        g.fillRoundRect(0, 0, width, height, radius, radius)
        g.color = ColorUtil.toAlpha(tone, 60)
        g.drawRoundRect(0, 0, width - 1, height - 1, radius, radius)
    }

    override fun getPreferredSize(): Dimension {
        val text = label.preferredSize
        return Dimension(text.width + JBUI.scale(PAD_X) * 2, text.height + JBUI.scale(PAD_Y) * 2)
    }

    override fun doLayout() {
        label.setBounds(
            JBUI.scale(PAD_X),
            (height - label.preferredSize.height) / 2,
            label.preferredSize.width,
            label.preferredSize.height,
        )
    }

    private companion object {
        private const val PAD_X = 10
        private const val PAD_Y = 3
    }
}
