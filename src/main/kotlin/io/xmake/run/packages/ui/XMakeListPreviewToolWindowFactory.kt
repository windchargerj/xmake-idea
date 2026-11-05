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
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.ColoredTreeCellRenderer
import com.intellij.ui.JBColor
import com.intellij.ui.DocumentAdapter
import com.intellij.ui.ListSpeedSearch
import com.intellij.ui.SearchTextField
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.TreeSpeedSearch
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.ui.treeStructure.SimpleTree
import com.intellij.util.ui.JBFont
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import com.intellij.util.ui.tree.TreeUtil
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Component
import java.awt.Dimension
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import java.awt.RenderingHints
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.awt.event.MouseMotionAdapter
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.DefaultListModel
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.JTree
import javax.swing.ListSelectionModel
import javax.swing.ScrollPaneConstants
import javax.swing.event.DocumentEvent
import javax.swing.tree.DefaultMutableTreeNode
import javax.swing.tree.DefaultTreeModel
import javax.swing.tree.TreeSelectionModel

/**
 * The packages-list prototype preview: five tabs, one per candidate the master list may
 * move to, each labeled, each carrying the same fake packages, the same live filter, and
 * the same sort - the choice the user makes by looking. A design-review scaffold, to be
 * removed once the option is picked.
 *
 * A - JBList, the platform's public list, two-line rows, virtualized.
 * B - SimpleTree, the Services-window tree, grouped and collapsible.
 * C - the Marketplace internals' shape, simulated with public pieces (the internals
 *     themselves are @ApiStatus.Internal and bind their own model).
 * D - a fully hand-drawn panel: rounded selection, hover, the Marketplace feel, at the
 *     price of hand-rolled behavior.
 * E - a simulated table: the column grid the user settled on, hand-painted - aligned
 *     columns, zebra rows, hover and rounded selection, no JTable.
 */
internal class XMakeListPreviewToolWindowFactory : ToolWindowFactory, DumbAware {

    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val options = listOf(
            "A · JBList 双行" to optionA(),
            "B · SimpleTree 分组树" to optionB(),
            "C · Marketplace 内部样式（模拟）" to optionC(),
            "D · 自绘 Panel（悬浮+圆角选中）" to optionD(),
            "E · 模拟表格（手绘网格）" to optionE(),
        )
        val contents = options.map { (label, component) ->
            com.intellij.ui.content.ContentFactory.getInstance()
                .createContent(component, label, false)
        }
        contents.forEach { toolWindow.contentManager.addContent(it) }
        toolWindow.contentManager.setSelectedContent(contents.first())
    }
}

/** One fake package the preview lists; the figure the real service would carry. */
private data class PreviewPkg(
    val name: String,
    val version: String,
    val repo: String,
    val description: String,
    val installed: Boolean,
) {
    val status: String get() = if (installed) "installed" else "available"
}

private fun previewPackages(): List<PreviewPkg> = listOf(
    PreviewPkg("rsync", "3.4.1", "xmake-repo", "rsync is an open source utility that provides fast incremental file transfer.", true),
    PreviewPkg("strawberry-perl", "5.32.0×1", "xmake-repo", "Strawberry Perl is a perl environment for MS Windows containing all you need to run and develop perl applications.", false),
    PreviewPkg("zstd", "v1.5.7", "xmake-repo", "Zstandard - Fast real-time compression algorithm.", true),
    PreviewPkg("openssl", "1.1.1-w", "xmake-repo", "A robust, commercial-grade, and full-featured toolkit for TLS and SSL.", false),
    PreviewPkg("nasm", "2.16.03", "xmake-repo", "Netwide Assembler (NASM) is an 80x86 assembler.", false),
    PreviewPkg("cmake", "4.3.4", "xmake-repo", "A cross-platform family of tools designed to build, test and package software.", false),
    PreviewPkg("ninja", "v1.13.2", "xmake-repo", "Small build system for use with gyp or CMake.", false),
    PreviewPkg("python", "3.x", "xmake-repo", "The python programming language.", true),
    PreviewPkg("meson", "1.12.1", "xmake-repo", "Fast and user friendly build system.", false),
    PreviewPkg("pkgconf", "2.5.1", "xmake-repo", "A program which helps to configure compiler and linker flags.", false),
    PreviewPkg("lua", "5.4.6", "xmake-repo", "Lua is a powerful, efficient, lightweight, embeddable scripting language.", false),
    PreviewPkg("doctest", "2.4.12", "xmake-repo", "The fastest feature-rich C++11/14/17/20 single-header testing framework.", false),
)

/** The sort keys the preview's combo offers; status sorts installed first. */
private enum class SortKey(val label: String) {
    NAME("Sort: Name"),
    VERSION("Sort: Version"),
    STATUS("Sort: Status"),
}

private fun List<PreviewPkg>.filtered(term: String): List<PreviewPkg> {
    val query = term.trim().lowercase()
    val hit = if (query.isEmpty()) this else filter {
        it.name.lowercase().contains(query) || it.description.lowercase().contains(query)
    }
    return hit
}

private fun List<PreviewPkg>.sortedBy(key: SortKey): List<PreviewPkg> = when (key) {
    SortKey.NAME -> sortedBy { it.name.lowercase() }
    SortKey.VERSION -> sortedBy { it.version }
    SortKey.STATUS -> sortedWith(compareByDescending<PreviewPkg> { it.installed }.thenBy { it.name.lowercase() })
}

/** The filter-plus-sort header every option carries, so the comparison includes how
 *  filtering and sorting sit in each shape. [onChanged] reruns with the current term
 *  and key on either edit. */
private fun filterBar(onChanged: (term: String, key: SortKey) -> Unit): JComponent {
    val field = SearchTextField(false).apply {
        textEditor.emptyText.text = "Filter packages"
    }
    val sort = ComboBox(SortKey.entries.map { it.label }.toTypedArray())
    fun fire() {
        onChanged(field.text.trim(), SortKey.entries[sort.selectedIndex])
    }
    field.textEditor.document.addDocumentListener(object : DocumentAdapter() {
        override fun textChanged(event: DocumentEvent) {
            fire()
        }
    })
    sort.addActionListener { fire() }
    return JPanel(BorderLayout()).apply {
        isOpaque = false
        border = JBUI.Borders.empty(6, 8)
        add(field, BorderLayout.CENTER)
        add(sort, BorderLayout.EAST)
    }
}

/** The gray caption that names what each tab is, under the tab label. */
private fun caption(text: String): JComponent = JBLabel(text).apply {
    foreground = infoForeground()
    font = JBFont.label().biggerOn(-1f)
    border = JBUI.Borders.empty(0, 8, 4, 8)
}

private fun metaOf(pkg: PreviewPkg): String =
    listOfNotNull(pkg.version, pkg.repo, pkg.status).joinToString(" · ")

/** Option A: the platform's JBList, two-line rows, virtualized, speed search on. */
private fun optionA(): JComponent {
    val all = previewPackages()
    val model = DefaultListModel<PreviewPkg>()
    val list = JBList(model).apply {
        selectionMode = ListSelectionModel.SINGLE_SELECTION
        fixedCellHeight = -1
        cellRenderer = object : JPanel(BorderLayout()), javax.swing.ListCellRenderer<PreviewPkg> {
            private val nameLabel = JBLabel().apply { font = JBFont.label().deriveFont(java.awt.Font.BOLD) }
            private val metaLabel = JBLabel().apply { foreground = infoForeground() }
            private val lines = JPanel(java.awt.GridLayout(0, 1)).apply {
                isOpaque = false
                add(nameLabel)
                add(metaLabel)
            }

            init {
                isOpaque = true
                border = JBUI.Borders.empty(6, 10)
                add(lines, BorderLayout.CENTER)
            }

            override fun getListCellRendererComponent(
                list: javax.swing.JList<out PreviewPkg>,
                value: PreviewPkg,
                index: Int,
                selected: Boolean,
                focused: Boolean,
            ): Component {
                if (selected) {
                    background = list.selectionBackground
                    nameLabel.foreground = list.selectionForeground
                    metaLabel.foreground = list.selectionForeground
                } else {
                    background = UIUtil.getListBackground()
                    nameLabel.foreground = UIUtil.getListForeground()
                    metaLabel.foreground = infoForeground()
                }
                nameLabel.text = value.name
                metaLabel.text = metaOf(value)
                return this
            }
        }
        emptyText.text = "No package matches the filter"
    }
    ListSpeedSearch.installOn(list)
    val bar = filterBar { term, key ->
        val hit = all.filtered(term).sortedBy(key)
        model.clear()
        hit.forEach { model.addElement(it) }
    }
    all.sortedBy(SortKey.NAME).forEach { model.addElement(it) }
    return panel(list.ofScrollBar(), bar, caption("平台公开 API（com.intellij.ui.components.JBList）· 虚拟化 · 双行 renderer · 速度搜索内建"))
}

/** Option B: the Services-window tree, the packages grouped and collapsible. */
private fun optionB(): JComponent {
    val all = previewPackages()
    val tree = SimpleTree().apply {
        selectionModel.selectionMode = TreeSelectionModel.SINGLE_TREE_SELECTION
        emptyText.text = "No package matches the filter"
    }
    tree.cellRenderer = object : ColoredTreeCellRenderer() {

        override fun customizeCellRenderer(
            tree: JTree,
            value: Any?,
            selected: Boolean,
            expanded: Boolean,
            leaf: Boolean,
            row: Int,
            hasFocus: Boolean,
        ) {
            val node = value as? DefaultMutableTreeNode ?: return
            when (val payload = node.userObject) {
                is String -> append(payload, SimpleTextAttributes(SimpleTextAttributes.STYLE_BOLD, infoForeground()))
                is PreviewPkg -> {
                    append(payload.name, SimpleTextAttributes.REGULAR_BOLD_ATTRIBUTES)
                    append("   " + metaOf(payload), SimpleTextAttributes.GRAYED_ATTRIBUTES)
                }
            }
        }
    }
    TreeSpeedSearch.installOn(tree)
    fun rebuild(term: String, key: SortKey) {
        val hits = all.filtered(term).sortedBy(key)
        val declared = hits.filter { it.installed }
        val available = hits.filter { !it.installed }
        val root = DefaultMutableTreeNode("root")
        if (declared.isNotEmpty()) {
            val group = DefaultMutableTreeNode("Declared packages - ${declared.size}")
            declared.forEach { group.add(DefaultMutableTreeNode(it)) }
            root.add(group)
        }
        if (available.isNotEmpty()) {
            val group = DefaultMutableTreeNode("Available in the repositories - ${available.size}")
            available.forEach { group.add(DefaultMutableTreeNode(it)) }
            root.add(group)
        }
        tree.model = DefaultTreeModel(root)
        TreeUtil.expandAll(tree)
    }
    val bar = filterBar { term, key -> rebuild(term, key) }
    rebuild("", SortKey.NAME)
    return panel(tree.ofScrollBar(), bar, caption("平台公开 API（com.intellij.ui.treeStructure.SimpleTree）· Services 窗范式 · 分组可折叠 · 单行渲染"))
}

/** Option C: the Marketplace internals' shape, simulated with public pieces - the
 *  internals bind their own plugin model and are @ApiStatus.Internal. */
private fun optionC(): JComponent {
    val all = previewPackages()
    val rows = JPanel().apply { layout = BoxLayout(this, BoxLayout.Y_AXIS) }
    fun row(pkg: PreviewPkg): JComponent = JPanel(BorderLayout()).apply {
        isOpaque = true
        background = mainBackground()
        border = JBUI.Borders.empty(10, 10, 10, 10)
        add(
            JBLabel(AllIcons.Plugins.PluginLogo).apply { verticalAlignment = javax.swing.JLabel.TOP },
            BorderLayout.WEST,
        )
        add(
            JPanel(java.awt.GridLayout(0, 1)).apply {
                isOpaque = false
                border = JBUI.Borders.emptyLeft(10)
                add(JBLabel(pkg.name).apply { font = JBFont.label().deriveFont(java.awt.Font.BOLD) })
                add(JBLabel(metaOf(pkg)).apply { foreground = infoForeground() })
                add(wrappedText(pkg.description).apply { foreground = infoForeground() })
            },
            BorderLayout.CENTER,
        )
        add(
            JButton("Install").apply {
                isEnabled = false
                preferredSize = Dimension(JBUI.scale(72), preferredSize.height)
            },
            BorderLayout.EAST,
        )
    }
    fun rebuild(term: String, key: SortKey) {
        rows.removeAll()
        all.filtered(term).sortedBy(key).forEach { rows.add(row(it)) }
        rows.revalidate()
        rows.repaint()
    }
    val bar = filterBar { term, key -> rebuild(term, key) }
    rebuild("", SortKey.NAME)
    return panel(rows.ofScrollBar(), bar, caption("Marketplace 行样式复刻（图标+粗名+灰色 meta+右缘按钮）· 非虚拟化 · 真实方案绑定内部 API，不推荐直接用"))
}

/** Option D: the fully hand-drawn panel - rounded selection, hover, the Marketplace
 *  feel, every behavior hand-rolled. */
private fun optionD(): JComponent {
    val all = previewPackages()
    val rows = JPanel().apply { layout = BoxLayout(this, BoxLayout.Y_AXIS) }
    var hovered: PreviewPkg? = null
    var selected: PreviewPkg? = null
    var current: List<PreviewPkg> = emptyList()

    fun rebuild(term: String, key: SortKey) {
        current = all.filtered(term).sortedBy(key)
        if (selected != null && current.none { it === selected }) selected = null
        rows.removeAll()
        current.forEach { pkg ->
            val row = object : JPanel(BorderLayout()) {
                init {
                    isOpaque = false
                    border = JBUI.Borders.empty(8, 10)
                    add(
                        JPanel(java.awt.GridLayout(0, 1)).apply {
                            isOpaque = false
                            add(
                                Box.createHorizontalBox().apply {
                                    add(JBLabel(pkg.name).apply { font = JBFont.label().deriveFont(java.awt.Font.BOLD) })
                                    add(Box.createHorizontalStrut(JBUI.scale(8)))
                                    add(
                                        Chip(
                                            pkg.status,
                                            badgeForeground(pkg.status),
                                            badgeBackground(pkg.status),
                                        ),
                                    )
                                },
                            )
                            add(JBLabel("${pkg.version} · ${pkg.repo}").apply { foreground = infoForeground() })
                            add(wrappedText(pkg.description).apply { foreground = infoForeground() })
                        },
                        BorderLayout.CENTER,
                    )
                    addMouseListener(object : MouseAdapter() {
                        override fun mouseClicked(event: MouseEvent) {
                            selected = if (selected === pkg) null else pkg
                            rows.repaint()
                        }

                        override fun mouseExited(event: MouseEvent) {
                            hovered = null
                            rows.repaint()
                        }
                    })
                    addMouseMotionListener(object : MouseMotionAdapter() {
                        override fun mouseMoved(event: MouseEvent) {
                            hovered = pkg
                            rows.repaint()
                        }
                    })
                }

                override fun paintComponent(graphics: Graphics) {
                    val g = graphics as Graphics2D
                    g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
                    if (selected === pkg) {
                        g.color = listSelectionColor()
                        g.fillRoundRect(2, 0, width - 4, height, 12, 12)
                    } else if (hovered === pkg) {
                        g.color = listHoverColor()
                        g.fillRoundRect(2, 0, width - 4, height, 12, 12)
                    }
                }
            }
            rows.add(row)
        }
        rows.revalidate()
        rows.repaint()
    }
    val bar = filterBar { term, key -> rebuild(term, key) }
    rebuild("", SortKey.NAME)
    return panel(rows.ofScrollBar(), bar, caption("完全自绘 panel · 圆角选中+悬浮高亮 · 行为（键盘/速度搜索/a11y）需全部手搓 · 数据量小可接受"))
}

private fun listSelectionColor(): Color = JBColor.namedColor("Plugins.lightSelectionBackground", UIUtil.getListSelectionBackground(false))

private fun listHoverColor(): Color = JBColor.namedColor("Plugins.hoverBackground", UIUtil.getListSelectionBackground(true))

/** Option E: the column grid the user settled on, hand-painted - a header row, aligned
 *  columns, zebra rows, hover and a rounded selection, and no JTable underneath. */
private fun optionE(): JComponent {
    val table = SimulatedTable(previewPackages())
    val bar = filterBar { term, key -> table.show(term, key) }
    return panel(table.ofScrollBar(), bar, caption("手绘模拟表格 · 表头点击排序（▲▼）/拖拽边缘调宽/悬浮/选中 · 无 JTable · 行为全部手搓"))
}

/**
 * Option E as one self-contained component: a hand-painted table with a working header -
 * click a title to sort ascending, descending, then none, with the arrow drawn in the
 * header; drag a title's edge to resize its column; hover lights the row and the header
 * cell; click a row to select it. No JTable underneath - every behavior is the panel's.
 */
private class SimulatedTable(private val packages: List<PreviewPkg>) : JPanel(null) {

    private val columns = arrayOf("Name", "Description", "Version", "Status")

    /** The column widths the header edges resize; the description takes what remains. */
    private val widths = intArrayOf(JBUI.scale(140), 0, JBUI.scale(90), JBUI.scale(90))

    private var rows: List<PreviewPkg> = packages
    private var sortColumn = -1
    private var sortAscending = true
    private var hoverRow = -1
    private var hoverColumn = -1
    private var selectedRow = -1
    private var resizeColumn = -1
    private var resizeStartX = 0
    private var resizeStartWidth = 0

    private val headerHeight = JBUI.scale(30)
    private val rowHeight = JBUI.scale(30)
    private val edgeSlack = JBUI.scale(4)

    init {
        isOpaque = true
        background = mainBackground()
        relayout()
        addComponentListener(object : java.awt.event.ComponentAdapter() {
            override fun componentResized(event: java.awt.event.ComponentEvent) {
                relayout()
            }
        })
        addMouseListener(object : MouseAdapter() {
            override fun mouseExited(event: MouseEvent) {
                hoverRow = -1
                hoverColumn = -1
                repaint()
            }

            override fun mousePressed(event: MouseEvent) {
                if (event.y < headerHeight) {
                    val edge = edgeAt(event.x)
                    if (edge >= 0) {
                        resizeColumn = edge
                        resizeStartX = event.x
                        resizeStartWidth = if (edge == 1) descWidth() else widths[edge]
                    }
                }
            }

            override fun mouseClicked(event: MouseEvent) {
                if (event.y < headerHeight) {
                    val column = columnAt(event.x)
                    if (column >= 0) {
                        if (sortColumn == column && sortAscending) sortAscending = false
                        else {
                            sortColumn = column
                            sortAscending = true
                        }
                        applySort()
                    }
                    return
                }
                val row = rowAt(event.y)
                if (row >= 0) selectedRow = row
                repaint()
            }

            override fun mouseReleased(event: MouseEvent) {
                resizeColumn = -1
            }
        })
        addMouseMotionListener(object : MouseMotionAdapter() {
            override fun mouseDragged(event: MouseEvent) {
                if (resizeColumn >= 0) {
                    val delta = event.x - resizeStartX
                    when (resizeColumn) {
                        0 -> widths[0] = (resizeStartWidth + delta).coerceAtLeast(JBUI.scale(60))
                        1 -> widths[1] = (resizeStartWidth + delta).coerceAtLeast(JBUI.scale(80))
                        2 -> widths[2] = (resizeStartWidth + delta).coerceAtLeast(JBUI.scale(50))
                    }
                    relayout()
                    repaint()
                }
            }

            override fun mouseMoved(event: MouseEvent) {
                if (event.y < headerHeight) {
                    hoverColumn = columnAt(event.x)
                    hoverRow = -1
                    cursor = if (edgeAt(event.x) >= 0) {
                        java.awt.Cursor.getPredefinedCursor(java.awt.Cursor.E_RESIZE_CURSOR)
                    } else {
                        java.awt.Cursor.getPredefinedCursor(java.awt.Cursor.HAND_CURSOR)
                    }
                } else {
                    hoverColumn = -1
                    hoverRow = rowAt(event.y)
                    cursor = java.awt.Cursor.getDefaultCursor()
                }
                repaint()
            }
        })
    }

    /** The description column takes what the current width leaves the others. */
    private fun descWidth(): Int =
        (width - widths[0] - widths[2] - widths[3] - JBUI.scale(24)).coerceAtLeast(JBUI.scale(80))

    /** Filters to [term] and applies the header's sort state; the entry point the filter
     *  bar reruns. */
    fun show(term: String, key: SortKey) {
        val query = term.trim().lowercase()
        val hit = if (query.isEmpty()) packages else packages.filter {
            it.name.lowercase().contains(query) || it.description.lowercase().contains(query)
        }
        rows = when (sortColumn) {
            0 -> hit.sortedBy { it.name.lowercase() }
            1 -> hit.sortedBy { it.description.lowercase() }
            2 -> hit.sortedBy { it.version }
            3 -> hit.sortedWith(compareByDescending<PreviewPkg> { it.installed }.thenBy { it.name.lowercase() })
            else -> hit.sortedBy(key)
        }
        if (!sortAscending) rows = rows.reversed()
        selectedRow = -1
        relayout()
        repaint()
    }

    private fun applySort() = show("", SortKey.NAME)

    private fun relayout() {
        widths[1] = descWidth()
        preferredSize = Dimension(width, headerHeight + rows.size * rowHeight + JBUI.scale(4))
        revalidate()
    }

    private fun columnAt(x: Int): Int {
        var left = JBUI.scale(8)
        columns.indices.forEach { column ->
            val right = left + widths[column]
            if (x >= left && x < right) return column
            left = right
        }
        return -1
    }

    /** The column whose header edge [x] sits close enough to drag. */
    private fun edgeAt(x: Int): Int {
        var left = JBUI.scale(8)
        columns.indices.forEach { column ->
            val right = left + widths[column]
            if (kotlin.math.abs(x - right) <= edgeSlack && column < 2) return column
            left = right
        }
        return -1
    }

    private fun rowAt(y: Int): Int {
        val row = (y - headerHeight) / rowHeight
        return if (row in rows.indices) row else -1
    }

    override fun getPreferredSize(): Dimension = Dimension(width, headerHeight + rows.size * rowHeight + JBUI.scale(4))

    override fun paintComponent(graphics: Graphics) {
        super.paintComponent(graphics)
        val g = graphics as Graphics2D
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
        val font = JBFont.label()
        g.font = font
        val metrics = g.fontMetrics
        val left = JBUI.scale(8)
        val descWidth = descWidth()

        // The header: the titles with their sort arrows, the hovered column lit, the
        // bottom hairline separating it from the rows.
        g.color = JBColor.namedColor("Table.stripeColor", JBColor(0xF7F8FA, 0x40454B))
        g.fillRect(0, 0, width, headerHeight)
        if (hoverColumn >= 0) {
            g.color = listHoverColor()
            var hoverLeft = left
            columns.indices.take(hoverColumn).forEach { hoverLeft += widths[it] }
            g.fillRect(hoverLeft, 0, widths[hoverColumn], headerHeight)
        }
        g.font = JBFont.label().biggerOn(-1f).deriveFont(java.awt.Font.BOLD)
        g.color = infoForeground()
        var columnLeft = left
        columns.indices.forEach { column ->
            val title = columns[column]
            g.drawString(title, columnLeft + JBUI.scale(2), (headerHeight + metrics.ascent) / 2 - 1)
            if (sortColumn == column) {
                val arrowX = columnLeft + metrics.stringWidth(title) + JBUI.scale(6)
                val centerY = headerHeight / 2
                g.color = UIUtil.getListForeground()
                if (sortAscending) {
                    g.fillPolygon(intArrayOf(arrowX, arrowX + 8, arrowX + 4), intArrayOf(centerY + 3, centerY + 3, centerY - 3), 3)
                } else {
                    g.fillPolygon(intArrayOf(arrowX, arrowX + 8, arrowX + 4), intArrayOf(centerY - 3, centerY - 3, centerY + 3), 3)
                }
                g.color = infoForeground()
            }
            columnLeft += widths[column]
        }
        g.color = JBColor.border()
        g.fillRect(0, headerHeight - 1, width, 1)

        // The rows: zebra, hover, selection; each cell text at its column's x, elided to
        // the column's width; the status in its state color.
        g.font = font
        rows.forEachIndexed { index, pkg ->
            val top = headerHeight + index * rowHeight
            val isSelected = index == selectedRow
            when {
                isSelected -> g.color = listSelectionColor()
                index == hoverRow -> g.color = listHoverColor()
                index % 2 == 1 -> g.color = JBColor.namedColor("Table.stripeColor", JBColor(0xF7F8FA, 0x40454B))
                else -> g.color = mainBackground()
            }
            g.fillRect(0, top, width, rowHeight)
            val y = top + (rowHeight + metrics.ascent) / 2 - 1
            g.color = if (isSelected) listSelectionForeground() else UIUtil.getListForeground()
            g.drawString(elide(pkg.name, metrics, widths[0] - JBUI.scale(8)), left + JBUI.scale(2), y)
            g.color = infoForeground()
            g.drawString(elide(pkg.description, metrics, descWidth - JBUI.scale(8)), left + widths[0] + JBUI.scale(2), y)
            g.drawString(elide(pkg.version, metrics, widths[2] - JBUI.scale(8)), left + widths[0] + descWidth + JBUI.scale(2), y)
            g.color = badgeForeground(pkg.status)
            g.drawString(pkg.status, left + widths[0] + descWidth + widths[2] + JBUI.scale(2), y)
        }
    }
}
private fun listSelectionForeground(): Color = UIUtil.getListSelectionForeground(true)

/** Cuts [text] to [maxWidth] with an ellipsis. */
private fun elide(text: String, metrics: java.awt.FontMetrics, maxWidth: Int): String {
    if (metrics.stringWidth(text) <= maxWidth) return text
    var end = text.length
    while (end > 0 && metrics.stringWidth(text.substring(0, end) + "…") > maxWidth) end--
    return text.substring(0, end) + "…"
}

private fun JComponent.ofScrollBar(): JComponent = JBScrollPane(
    this,
    ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED,
    ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER,
).apply { border = JBUI.Borders.empty() }

/** The preview page's frame: caption on top, the filter bar, then the list itself. */
private fun panel(content: JComponent, bar: JComponent, captionLine: JComponent): JComponent =
    JPanel(GridBagLayout()).apply {
        isOpaque = true
        background = mainBackground()
        val constraints = GridBagConstraints().apply {
            gridx = 0
            weightx = 1.0
            anchor = GridBagConstraints.WEST
            fill = GridBagConstraints.HORIZONTAL
        }
        constraints.gridy = 0
        add(captionLine, constraints)
        constraints.gridy = 1
        add(bar, constraints)
        constraints.gridy = 2
        constraints.weighty = 1.0
        constraints.fill = GridBagConstraints.BOTH
        add(content, constraints)
    }

/** A wrapping value, the details pane's. */
private fun wrappedText(text: String): JComponent = JBTextArea(text).apply {
    isEditable = false
    lineWrap = true
    wrapStyleWord = true
    isFocusable = false
    isOpaque = false
    font = JBFont.label()
}
