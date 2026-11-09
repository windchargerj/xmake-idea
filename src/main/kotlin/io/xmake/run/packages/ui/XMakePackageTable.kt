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

import com.intellij.ui.ColoredTableCellRenderer
import com.intellij.ui.JBColor
import com.intellij.ui.ScrollPaneFactory
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.table.TableView
import com.intellij.util.ui.ColumnInfo
import com.intellij.util.ui.JBFont
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.ListTableModel
import com.intellij.util.ui.UIUtil
import java.awt.Color
import java.awt.Dimension
import java.awt.Font
import javax.swing.JTable
import javax.swing.ListSelectionModel
import io.xmake.run.packages.XMakePackageInfo

/**
 * The master list: the columns, the model, the header-click sorter, and the cell
 * renderers. One collaborator of [XMakePackagesPanel]; the status and repo cells read
 * the panel's snapshot helpers, so a repaint always speaks the latest published truth.
 */
internal class XMakePackageTable(private val panel: XMakePackagesPanel) {



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
            if (item.isHit) item.repo else sourceLabel(item.name, panel.infoFor(item.name))
    }

    private val statusColumn = object : ColumnInfo<PackageRow, String?>("Status") {

        override fun valueOf(item: PackageRow): String? = panel.statusOf(item)
    }

    private val descriptionColumn = object : ColumnInfo<PackageRow, String?>("Description") {

        override fun valueOf(item: PackageRow): String? = item.description
    }

    internal val tableModel = ListTableModel<PackageRow>(
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
    internal val tableSorter = javax.swing.table.TableRowSorter(tableModel).apply {
        setComparator(COLUMN_VERSION, java.util.Comparator<String> { left, right ->
            compareVersions(left, right)
        })
        setComparator(COLUMN_STATUS, java.util.Comparator<String> { left, right ->
            statusWeight(left) - statusWeight(right)
        })
    }

    internal val table = TableView(tableModel).apply {
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
    internal val tableScroll = ScrollPaneFactory.createScrollPane(table, true)

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

    init {
        table.columnModel.getColumn(COLUMN_NAME).cellRenderer = nameCellRenderer
        table.columnModel.getColumn(COLUMN_DESCRIPTION).cellRenderer = descriptionCellRenderer
        table.columnModel.getColumn(COLUMN_VERSION).cellRenderer = versionCellRenderer
        table.columnModel.getColumn(COLUMN_REPO).cellRenderer = repoCellRenderer
        table.columnModel.getColumn(COLUMN_STATUS).cellRenderer = statusCellRenderer
    }


    /** The status of a row, in the table's own words: the installed truth when the info
     *  cache holds one, "declared" for a project requirement otherwise - a hit is neither
     *  until its info lands on selection. */
    internal fun statusOf(row: PackageRow): String? = when {
        panel.infoFor(row.name)?.installed == true -> INSTALLED
        row.isHit -> AVAILABLE
        row.isInstalled -> INSTALLED
        else -> DECLARED
    }

    /** The triage weight behind the status sort: installed first, then the project's own
     *  declarations, then what the repositories merely offer. */
    internal fun statusWeight(state: String?): Int = when (state) {
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
    internal fun compareVersions(left: String?, right: String?): Int {
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

    /** The row's source: the external-manager prefix for `vcpkg::`/`conan::` names, else
     *  the repository `--info` reported. */
    internal fun sourceLabel(name: String, info: XMakePackageInfo?): String? {
        if ("::" in name) return "${name.substringBefore("::")}::"
        return info?.repoName?.let { "from $it" }
    }
}

/** The table's column order, by index. */
internal const val COLUMN_NAME = 0
internal const val COLUMN_DESCRIPTION = 1
internal const val COLUMN_VERSION = 2
internal const val COLUMN_REPO = 3
internal const val COLUMN_STATUS = 4

/** The first load's promise, on the empty table. */
internal const val LOADING = "Loading the declared packages..."