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
import java.awt.Font
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import java.awt.event.ActionEvent
import java.awt.event.KeyEvent
import java.awt.event.MouseEvent
import javax.swing.AbstractAction
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

        /** The version of record: what `xmake.lua` pins, or what a hit's repository
         *  offers. The resolved installed version reads in the details pane, where the
         *  `--info` data lives. */
        override fun valueOf(item: PackageRow): String? =
            if (item.isHit) item.version else item.requirement
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
    private val nameCellRenderer = object : ColoredTableCellRenderer() {

        override fun customizeCellRenderer(
            table: JTable,
            value: Any?,
            selected: Boolean,
            focused: Boolean,
            row: Int,
            column: Int,
        ) {
            append(value?.toString().orEmpty(), SimpleTextAttributes.REGULAR_BOLD_ATTRIBUTES)
        }
    }

    /** The description cell: a hit's repository description, grayed - the column is the
     *  flexible one, so the text has room before the narrow columns. */
    private val descriptionCellRenderer = object : ColoredTableCellRenderer() {

        override fun customizeCellRenderer(
            table: JTable,
            value: Any?,
            selected: Boolean,
            focused: Boolean,
            row: Int,
            column: Int,
        ) {
            append(value?.toString().orEmpty(), SimpleTextAttributes.GRAYED_ATTRIBUTES)
        }
    }

    /** The Repo cell: grayed, like the analysis view's Location column. */
    private val repoCellRenderer = object : ColoredTableCellRenderer() {

        override fun customizeCellRenderer(
            table: JTable,
            value: Any?,
            selected: Boolean,
            focused: Boolean,
            row: Int,
            column: Int,
        ) {
            (value as? String)?.let { append(it, SimpleTextAttributes.GRAYED_ATTRIBUTES) }
        }
    }

    /** The Status cell: "installed" reads as the state, "declared" as the weaker one. */
    private val statusCellRenderer = object : ColoredTableCellRenderer() {

        override fun customizeCellRenderer(
            table: JTable,
            value: Any?,
            selected: Boolean,
            focused: Boolean,
            row: Int,
            column: Int,
        ) {
            when (value as? String) {
                INSTALLED -> append(INSTALLED, SimpleTextAttributes.REGULAR_ATTRIBUTES)
                AVAILABLE -> append(AVAILABLE, SimpleTextAttributes.GRAYED_ATTRIBUTES)
                DECLARED -> append(DECLARED, SimpleTextAttributes.GRAYED_ATTRIBUTES)
                else -> {}
            }
        }
    }

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
     *  history popup and the tuned paddings are the Marketplace search field's. */
    private val searchField = SearchTextField(true).apply {
        textEditor.emptyText.text = "Search packages"
        textEditor.putClientProperty("JTextField.Search.Gap", JBUI.scale(6))
        textEditor.putClientProperty("JTextField.Search.GapEmptyText", JBUI.scale(-1))
        textEditor.background = JBColor.namedColor("Plugins.SearchField.background", UIUtil.getListBackground())
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

    /** The details pane's meta line: repository, resolved version, the project's ask. */
    private val detailsMeta = JBLabel().apply { foreground = infoForeground() }

    /** The details header's one action: the install or uninstall this row asks for, the
     *  way the Marketplace header carries the Install button. */
    private val detailsAction = JButton()

    /** The header stack: name, meta, and the action row, over a hairline - the
     *  Marketplace details header's rows and insets, stacked on a GridBag. */
    private val detailsHeader = JPanel(GridBagLayout()).apply {
        isOpaque = true
        background = mainBackground()
        border = JBUI.Borders.compound(
            CustomLineBorder(infoBorderColor(), JBUI.insetsBottom(1)),
            JBUI.Borders.empty(12, 20, 10, 20),
        )
        fun row(gridy: Int, component: JComponent, stretch: Boolean = true) = add(
            component,
            GridBagConstraints().apply {
                this.gridy = gridy
                gridx = 0
                weightx = 1.0
                anchor = GridBagConstraints.WEST
                fill = if (stretch) GridBagConstraints.HORIZONTAL else GridBagConstraints.NONE
                insets = JBUI.insets(if (gridy == 0) 0 else 8, 0)
            },
        )
        row(0, detailsName)
        row(1, detailsMeta, stretch = false)
        row(2, BorderLayoutPanel().apply {
            isOpaque = false
            addToCenter(JPanel().apply { isOpaque = false })
            addToRight(detailsAction.apply { isOpaque = false })
        }, stretch = false)
    }

    /** The details grid: caption-value rows rebuilt per selection or info landing, in a
     *  scroll pane that never scrolls sideways - the long values wrap instead. */
    private val detailsBody = JPanel(GridBagLayout()).apply {
        isOpaque = true
        background = mainBackground()
    }

    /** The details pane as the Marketplace page lays it out: the header fixed, the info
     *  grid scrolling under it. */
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

    /** The right pane of the split; swaps between the details and the empty card. */
    private val detailsCard = JPanel(BorderLayout())

    init {
        table.columnModel.getColumn(COLUMN_NAME).cellRenderer = nameCellRenderer
        table.columnModel.getColumn(COLUMN_DESCRIPTION).cellRenderer = descriptionCellRenderer
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

        val split = OnePixelSplitter(false, SPLIT_KEY, 0.7f).apply {
            firstComponent = tableScroll
            secondComponent = detailsCard
        }

        // The search row sits above the split, outside the toolbar: focus survives
        // toolbar rebuilds, and a term typed while an operation holds the lane is queued
        // by the service and searched when the lane frees. The hairline under it is the
        // Marketplace page's line between the search field and the rows.
        val searchRow = JPanel(BorderLayout()).apply {
            isOpaque = true
            background = JBColor.namedColor("Plugins.SearchField.background", UIUtil.getListBackground())
            border = JBUI.Borders.compound(
                CustomLineBorder(infoBorderColor(), JBUI.insetsBottom(1)),
                JBUI.Borders.empty(6, 8),
            )
            add(searchField, BorderLayout.CENTER)
        }

        toolbar = toolbar().component
        setContent(
            JBUI.Panels.simplePanel()
                .addToTop(searchRow)
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

    /** Swaps the rows: search results while searching, else the declared packages. A
     *  rebuild is a refresh, not a reset: the scroll position and the selection carry
     *  over, so a post-action refresh reads as the rows updating in place. */
    private fun rebuild(snapshot: XMakePackagesSnapshot?) {
        val selectedName = selectedPackageName()
        val position = tableScroll.viewport.viewPosition
        val results = snapshot?.searchResults
        tableModel.items = if (results != null) {
            results.hits.map { hit ->
                PackageRow(
                    name = hit.name,
                    requirement = null,
                    version = hit.version,
                    description = hit.description,
                    repo = hit.repo,
                    isHit = true,
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
                }
                empty.appendSecondaryText(
                    "Back to the declared packages",
                    SimpleTextAttributes.LINK_PLAIN_ATTRIBUTES,
                ) { leaveSearch() }
            }
            else -> empty.text = "No packages declared"
        }
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

    /** The row's source: the external-manager prefix for `vcpkg::`/`conan::` names, else
     *  the repository `--info` reported. */
    private fun sourceLabel(name: String, info: XMakePackageInfo?): String? {
        if ("::" in name) return "${name.substringBefore("::")}::"
        return info?.repoName?.let { "from $it" }
    }

    /**
     * Renders the selected package: the Marketplace header (name, meta, the one action
     * that fits the row) over the info grid, or the nothing-selected card. The
     * narration while the info has not landed is the same one the old text pane gave.
     */
    private fun renderDetails(name: String?) {
        if (name == null) {
            showDetailsPane(false)
            return
        }
        showDetailsPane(true)
        detailsName.text = name
        val row = tableModel.items.firstOrNull { it.name == name }
        val info = infoFor(name)
        detailsMeta.text = when {
            // A package that exists nowhere still reports version "latest"; the missing
            // repo is the "not found" signal, not a failed query.
            info != null && info.repoName == null -> "not found in the configured repositories"
            else -> listOfNotNull(
                row?.requirement?.let { "requires $it" },
                info?.repoName ?: row?.repo,
                info?.version ?: row?.version?.let { "v$it" },
            ).joinToString(" · ").ifEmpty { " " }
        }
        detailsAction.action = when {
            info?.installed == true -> uninstallAction(name)
            else -> installAction(name)
        }
        detailsAction.isEnabled = !service.busy
        renderInfoGrid(name, info)
    }

    /** Swaps the right pane between the details and the nothing-selected card. */
    private fun showDetailsPane(withDetails: Boolean) {
        detailsCard.removeAll()
        detailsCard.add(if (withDetails) detailsPane else detailsEmpty, BorderLayout.CENTER)
        detailsCard.revalidate()
        detailsCard.repaint()
    }

    /** Rebuilds the details grid for [name]; the header already carries the identity. */
    private fun renderInfoGrid(name: String, info: XMakePackageInfo?) {
        detailsBody.removeAll()
        var gridy = 0
        fun constraints(row: Int, column: Int, insetsLeft: Int, fillMode: Int): GridBagConstraints =
            GridBagConstraints().apply {
                gridy = row
                gridx = column
                anchor = GridBagConstraints.WEST
                weightx = if (column == 0) 0.0 else 1.0
                fill = fillMode
                insets = JBUI.insets(3, insetsLeft, 3, 8)
            }

        fun addRow(caption: String, value: JComponent) {
            detailsBody.add(
                JBLabel(caption).apply { foreground = infoForeground() },
                constraints(gridy, 0, 20, GridBagConstraints.NONE),
            )
            detailsBody.add(value, constraints(gridy, 1, 12, GridBagConstraints.HORIZONTAL))
            gridy++
        }

        fun addTextRow(caption: String, text: String?) {
            if (!text.isNullOrEmpty()) addRow(caption, JBLabel(text))
        }

        fun addWrappedRow(caption: String, text: String) {
            addRow(caption, wrappedText(text))
        }

        fun addNarration(text: String) {
            val row = gridy
            detailsBody.add(
                wrappedText(text),
                GridBagConstraints().apply {
                    gridy = row
                    gridx = 0
                    gridwidth = GridBagConstraints.REMAINDER
                    weightx = 1.0
                    fill = GridBagConstraints.HORIZONTAL
                    anchor = GridBagConstraints.WEST
                    insets = JBUI.insets(4, 20)
                },
            )
            gridy++
        }

        fun addFiller() {
            val row = gridy
            detailsBody.add(
                JPanel().apply { isOpaque = false },
                GridBagConstraints().apply {
                    gridy = row
                    gridx = 0
                    gridwidth = GridBagConstraints.REMAINDER
                    weighty = 1.0
                    fill = GridBagConstraints.BOTH
                },
            )
        }

        when {
            // A busy pipeline with a named operation is not loading this info at all - the
            // fetchInfo call was a no-op; say what the wait is really on.
            info == null && service.busy && service.currentOperation != null -> {
                addNarration(
                    "Waiting for the current operation to finish before loading the info of '$name'... - " +
                        "${service.currentOperation} - then the row loads its info, or select it again",
                )
                addFiller()
            }
            info == null && service.busy -> {
                addNarration("Loading the info of '$name'... - xmake require --info --format=json $name")
                addFiller()
            }
            info == null -> {
                addNarration("No info available for '$name' - the query failed or produced nothing usable")
                addFiller()
            }
            else -> {
                tableModel.items.firstOrNull { it.name == name }?.requirement
                    ?.let { addTextRow("Requirement", it) }
                info.version?.let { addTextRow("Version", it) }
                info.description?.let { addWrappedRow("Description", it) }
                addRow(
                    "Repo",
                    JBLabel(
                        when {
                            // A package that exists nowhere still reports version "latest"; the
                            // missing repo is the "not found" signal, not a failed query.
                            info.repoName == null -> "not found in the configured repositories"
                            else -> listOfNotNull(info.repoName, info.repoUrl?.let { "($it)" }).joinToString(" ")
                        },
                    ),
                )
                // `--info` carries no dependency field; the depgraph query is the honest
                // source. A package outside the project's graph (a search hit) simply has
                // no line here.
                val dependencies = service.snapshot?.depgraph?.packages
                    ?.firstOrNull { it.name == name }?.deps
                if (!dependencies.isNullOrEmpty()) {
                    addTextRow("Depends on", dependencies.joinToString(", "))
                }
                info.installdir?.let { addWrappedRow("Install directory", it) }
                info.cachedir?.let { addWrappedRow("Cache directory", it) }
                if (info.platforms.isNotEmpty()) {
                    addTextRow("Platforms", info.platforms.joinToString(", "))
                }
                if (info.installed) {
                    addTextRow("References", "${info.references?.size ?: 0} - installed")
                    info.references.orEmpty().forEach { reference ->
                        addRow(
                            "",
                            JBLabel(
                                buildString {
                                    append(reference.dir ?: "?")
                                    reference.date?.let { append("  (").append(it).append(')') }
                                },
                            ),
                        )
                    }
                } else {
                    addTextRow("References", "none - not installed")
                }
                addFiller()
            }
        }
        detailsBody.revalidate()
        detailsBody.repaint()
    }

    /** A value that wraps instead of widening the grid: the description, the paths. A
     *  wrapping text area sized by GridBag settles at the pane width once laid out, and
     *  the scroll pane never scrolls sideways - the old text pane's wrapping, kept per
     *  value. */
    private fun wrappedText(text: String): JComponent = JBTextArea(text).apply {
        isEditable = false
        lineWrap = true
        wrapStyleWord = true
        isFocusable = false
        isOpaque = false
        font = JBFont.label()
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

    /** The Install flow the toolbar button, the details header, and a double-click on a
     *  row share. */
    private fun installPackage(name: String) {
        service.prepareCommand(prepare = { createRequireInstall(name) }) { command ->
            confirmAndRun("Install package", "Installing '$name' - xmake fetches, builds, and installs it.", command)
        }
    }

    /** The details header's Install action for one package. */
    private fun installAction(name: String) = object : AbstractAction("Install") {
        override fun actionPerformed(event: ActionEvent) {
            installPackage(name)
        }
    }

    /** The details header's Uninstall action for one installed package. */
    private fun uninstallAction(name: String) = object : AbstractAction("Uninstall") {
        override fun actionPerformed(event: ActionEvent) {
            service.prepareCommand(prepare = { createRequireUninstall(name) }) { command ->
                confirmAndRun("Uninstall package", "Uninstalling '$name'.", command)
            }
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
     *  searching. The info-driven cells (the resolved meta, the status) re-read the
     *  service's snapshot on paint, so an info landing is a repaint, not a rebuild. */
    private class PackageRow(
        val name: String,
        val requirement: String?,
        val version: String?,
        val description: String?,
        val repo: String?,
        val isHit: Boolean,
    ) {

        override fun toString(): String = name
    }

    private companion object {

        /** The splitter's persistence key; the user's split survives restarts. */
        private const val SPLIT_KEY = "XMakePackagesView"

        /** The input-map key of the exit-search binding, shared by the table and the field. */
        private const val EXIT_SEARCH = "XMakePackages.exitSearch"

        private const val LOADING = "Loading the declared packages... - or press Refresh"

        private const val INSTALLED = "installed"
        private const val DECLARED = "declared"
        private const val AVAILABLE = "available"

        private const val COLUMN_NAME = 0
        private const val COLUMN_DESCRIPTION = 1
        private const val COLUMN_VERSION = 2
        private const val COLUMN_REPO = 3
        private const val COLUMN_STATUS = 4
    }
}

/** The marketplace page's panel background, shared by the details pane and the list. */
private fun mainBackground(): Color = JBColor.namedColor("Plugins.background", UIUtil.getListBackground())

/** The marketplace page's hairline color, shared by the row separators. */
private fun infoBorderColor(): Color = JBColor.namedColor("Plugins.borderColor", JBColor.border())

/** The gray the metadata reads in - the Marketplace meta line's color. */
private fun infoForeground(): Color = JBColor.namedColor("Label.infoForeground", UIUtil.getLabelInfoForeground())
