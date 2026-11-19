package com.github.weilhuang.leximeetidea.toolWindow

import com.github.weilhuang.leximeetidea.reading.LearningStatus
import com.github.weilhuang.leximeetidea.reading.MatchKey
import com.github.weilhuang.leximeetidea.reading.MatchLocation
import com.github.weilhuang.leximeetidea.reading.MatchPosition
import com.github.weilhuang.leximeetidea.reading.MatchedWord
import com.github.weilhuang.leximeetidea.reading.ProjectEncounter
import com.github.weilhuang.leximeetidea.reading.WordFilters
import com.github.weilhuang.leximeetidea.reading.WordTag
import com.intellij.icons.AllIcons
import com.intellij.openapi.ui.ComboBox
import com.intellij.ui.DocumentAdapter
import com.intellij.ui.JBColor
import com.intellij.ui.OnePixelSplitter
import com.intellij.ui.SearchTextField
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.table.JBTable
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import java.awt.BorderLayout
import java.awt.Component
import java.awt.Cursor
import java.awt.FlowLayout
import java.awt.Font
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.Rectangle
import java.awt.RenderingHints
import java.awt.event.ActionEvent
import java.awt.event.KeyEvent
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.AbstractAction
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JTable
import javax.swing.KeyStroke
import javax.swing.ListSelectionModel
import javax.swing.RowSorter
import javax.swing.SortOrder
import javax.swing.SwingConstants
import javax.swing.SwingUtilities
import javax.swing.event.DocumentEvent
import javax.swing.table.AbstractTableModel
import javax.swing.table.DefaultTableCellRenderer
import javax.swing.table.TableCellRenderer
import javax.swing.table.TableRowSorter

/** 底部只读词表。搜索复用本轮结果，点击或 Enter 才移动编辑器或项目树。 */
internal class EncounterWordsPanel(private val encounter: ProjectEncounter) :
    JPanel(BorderLayout()) {
    private val model = WordTable()
    private val defaultSort =
        listOf(
            RowSorter.SortKey(WordColumn.COUNT.ordinal, SortOrder.DESCENDING),
            RowSorter.SortKey(WordColumn.WORD.ordinal, SortOrder.ASCENDING),
        )
    private val sorter = TableRowSorter(model).apply { sortKeys = defaultSort }
    private var words = emptyList<MatchedWord<MatchLocation>>()
    private var selectedWord: MatchKey? = null
    private val search =
        SearchTextField(false).apply {
            name = "leximeet-word-search"
            textEditor.name = "leximeet-word-search-input"
            textEditor.emptyText.text = "搜索单词、出处"
            textEditor.columns = 14
            textEditor.accessibleContext.accessibleName = "搜索单词、出处、状态或标签"
            addDocumentListener(
                object : DocumentAdapter() {
                    override fun textChanged(event: DocumentEvent) = filter(resetViewport = true)
                }
            )
        }
    private val statusFilter =
        filterBox("status-filter", "全部状态", LearningStatus.entries.map { it.label }, "按学习状态筛选")
    private val tagFilter =
        filterBox("tag-filter", "全部标签", WordTag.entries.map { it.label }, "按词条标签筛选")
    private val reset =
        JButton("重置").apply {
            name = "leximeet-reset-filters"
            toolTipText = "清空搜索和筛选，恢复默认排序"
            addActionListener {
                search.text = ""
                statusFilter.selectedIndex = 0
                tagFilter.selectedIndex = 0
                sorter.sortKeys = defaultSort
                updateReset()
            }
        }
    private val count =
        JBLabel().apply {
            name = "leximeet-word-count"
            foreground = UIUtil.getContextHelpForeground()
        }
    private val jumpStatus =
        JBLabel().apply {
            name = "leximeet-jump-result"
            putClientProperty("html.disable", true)
            foreground = UIUtil.getContextHelpForeground()
        }
    private val table =
        JBTable(model).apply {
            name = "leximeet-word-table"
            accessibleContext.accessibleName = "遇见匹配词表"
            rowSorter = sorter
            rowHeight = JBUI.scale(26)
            selectionModel.selectionMode = ListSelectionModel.SINGLE_SELECTION
            tableHeader.reorderingAllowed = false
            tableHeader.toolTipText = "点击列名改变排序"
            val nativeHeader = tableHeader.defaultRenderer
            tableHeader.defaultRenderer =
                TableCellRenderer { owner, value, selected, focused, row, column ->
                    nativeHeader
                        .getTableCellRendererComponent(owner, value, selected, focused, row, column)
                        .also { component ->
                            if (component is JLabel) {
                                val sorted =
                                    sorter.sortKeys.firstOrNull()?.takeIf {
                                        it.column == owner?.convertColumnIndexToModel(column)
                                    }
                                component.icon =
                                    when (sorted?.sortOrder) {
                                        SortOrder.ASCENDING -> AllIcons.General.ChevronUp
                                        SortOrder.DESCENDING -> AllIcons.General.ChevronDown
                                        else -> null
                                    }
                                component.horizontalTextPosition = SwingConstants.LEFT
                                component.iconTextGap = JBUI.scale(3)
                            }
                        }
                }
            setStriped(true)
            setShowGrid(false)
            cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
            toolTipText = "点击或按 Enter 定位，再点同一个词跳到下一处"
            for (column in WordColumn.entries) {
                columnModel.getColumn(column.ordinal).apply {
                    preferredWidth = JBUI.scale(column.preferredWidth)
                    minWidth = JBUI.scale(column.minWidth)
                    cellRenderer =
                        when (column) {
                            WordColumn.WORD -> WordTextRenderer(bold = true)
                            WordColumn.TAGS ->
                                WordTagRenderer { index ->
                                    this@EncounterWordsPanel.model.rows[index].tags
                                }
                            WordColumn.COUNT -> null
                            else -> WordTextRenderer()
                        }
                }
            }
            columnModel.getColumn(WordColumn.COUNT.ordinal).maxWidth = JBUI.scale(96)
            addMouseListener(
                object : MouseAdapter() {
                    override fun mouseClicked(event: MouseEvent) {
                        if (SwingUtilities.isLeftMouseButton(event) && event.clickCount == 1)
                            openWord(rowAtPoint(event.point))
                    }
                }
            )
            getInputMap(JComponent.WHEN_ANCESTOR_OF_FOCUSED_COMPONENT)
                .put(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, 0), "leximeet.locate")
            actionMap.put(
                "leximeet.locate",
                object : AbstractAction() {
                    override fun actionPerformed(event: ActionEvent) = openWord(selectedRow)
                },
            )
        }
    private val detail = EncounterWordDetail(encounter, ::closeDetail, ::openSource)
    private val split =
        OnePixelSplitter(false, 0.64f).apply { setHonorComponentsMinimumSize(false) }

    init {
        border = JBUI.Borders.emptyTop(8)
        sorter.addRowSorterListener {
            updateReset()
            table.tableHeader.repaint()
        }
        val filters =
            JPanel(FlowLayout(FlowLayout.TRAILING, JBUI.scale(5), 0)).apply {
                add(statusFilter)
                add(tagFilter)
                add(reset)
            }
        val top =
            JPanel(BorderLayout(JBUI.scale(6), 0)).apply {
                border = JBUI.Borders.emptyBottom(5)
                add(search, BorderLayout.CENTER)
                add(filters, BorderLayout.EAST)
            }
        val footer =
            JPanel(BorderLayout(JBUI.scale(10), 0)).apply {
                border = JBUI.Borders.emptyTop(4)
                add(jumpStatus, BorderLayout.CENTER)
                add(count, BorderLayout.EAST)
            }
        // 搜索和定位状态属于左侧词表。右侧从同一高度起排，窄工具窗也能先读到释义。
        split.firstComponent =
            JPanel(BorderLayout()).apply {
                add(top, BorderLayout.NORTH)
                add(JBScrollPane(table), BorderLayout.CENTER)
                add(footer, BorderLayout.SOUTH)
            }
        add(split, BorderLayout.CENTER)
        filter()
        update()
    }

    fun update() {
        val current = encounter.matchedWords()
        if (current !== words) {
            words = current
            filter()
        }
        table.emptyText.text =
            when {
                encounter.matching && words.isEmpty() -> "正在匹配当前范围…"
                words.isEmpty() -> "当前范围没有匹配的单词"
                else -> "没有符合筛选条件的单词"
            }
        if (words.isEmpty()) {
            jumpStatus.text = ""
            jumpStatus.toolTipText = null
        }
        refreshDetail()
    }

    private fun filterBox(id: String, all: String, labels: List<String>, description: String) =
        ComboBox((listOf(all) + labels).toTypedArray()).apply {
            name = "leximeet-$id"
            toolTipText = description
            accessibleContext.accessibleName = description
            addActionListener { filter(resetViewport = true) }
        }

    private fun filter(resetViewport: Boolean = false) {
        val selected = rowAt(table.selectedRow)?.key
        val filters =
            WordFilters(
                search.text,
                LearningStatus.entries.getOrNull(statusFilter.selectedIndex - 1),
                WordTag.entries.getOrNull(tagFilter.selectedIndex - 1),
            )
        val filtered = words.filter { filters.includes(it, MatchLocation::sourceLabel) }
        model.replace(filtered)
        val modelIndex = filtered.indexOfFirst { it.key == selected }
        val index = if (modelIndex < 0) -1 else table.convertRowIndexToView(modelIndex)
        if (index >= 0) table.setRowSelectionInterval(index, index)
        if (resetViewport) {
            val bounds = if (index < 0) Rectangle(0, 0, 1, 1) else table.getCellRect(index, 0, true)
            table.scrollRectToVisible(bounds)
        }
        val size =
            if (filtered.size == words.size) "${filtered.size}"
            else "${filtered.size} / ${words.size}"
        count.text = "$size 个词 · ${filtered.sumOf { it.locations.size }} 处"
        updateReset()
        if (resetViewport) refreshDetail()
    }

    private fun updateReset() {
        reset.isEnabled =
            search.text.isNotBlank() ||
                statusFilter.selectedIndex > 0 ||
                tagFilter.selectedIndex > 0 ||
                sorter.sortKeys != defaultSort
    }

    /** 表头排序后，Swing 的行号属于视图。所有选择和跳转先还原成结果中的行。 */
    private fun rowAt(index: Int) =
        if (index in 0 until table.rowCount) model.rows[table.convertRowIndexToModel(index)]
        else null

    private fun openWord(index: Int) {
        val word = rowAt(index) ?: return
        table.setRowSelectionInterval(index, index)
        selectedWord = word.key
        showJumpResult(encounter.locateWord(word.key))
        refreshDetail(retry = true)
    }

    private fun openSource(position: MatchPosition) {
        val key = selectedWord ?: return
        showJumpResult(encounter.locateWord(key, position))
    }

    private fun showJumpResult(result: String?) {
        jumpStatus.text = if (result == null) "位置已变化，正在重新匹配" else "已定位 · $result"
        jumpStatus.toolTipText = jumpStatus.text
    }

    private fun refreshDetail(retry: Boolean = false) {
        val key = selectedWord ?: return
        val matched = model.rows.firstOrNull { it.key == key }
        if (matched == null || !detail.show(matched, retry)) {
            closeDetail()
        } else if (split.secondComponent == null) {
            split.secondComponent = detail
        }
    }

    fun closeDetail() {
        selectedWord = null
        detail.clear()
        split.secondComponent = null
        jumpStatus.text = ""
        jumpStatus.toolTipText = null
    }

    private class WordTable : AbstractTableModel() {
        var rows = emptyList<MatchedWord<MatchLocation>>()
            private set

        fun replace(filtered: List<MatchedWord<MatchLocation>>) {
            if (rows == filtered) return
            rows = filtered
            fireTableDataChanged()
        }

        override fun getRowCount() = rows.size

        override fun getColumnCount() = WordColumn.entries.size

        override fun getColumnName(column: Int) = WordColumn.entries[column].title

        override fun getColumnClass(column: Int) =
            if (WordColumn.entries[column] == WordColumn.COUNT) Int::class.javaObjectType
            else String::class.java

        override fun getValueAt(row: Int, column: Int): Any =
            with(rows[row]) {
                when (WordColumn.entries[column]) {
                    WordColumn.WORD -> key.word
                    WordColumn.STATUS -> statusText
                    WordColumn.TAGS -> tagText
                    WordColumn.LOCATION -> locations.first().caption
                    WordColumn.COUNT -> locations.size
                }
            }
    }
}

private enum class WordColumn(val title: String, val preferredWidth: Int, val minWidth: Int) {
    WORD("单词", 150, 68),
    STATUS("学习状态", 100, 67),
    TAGS("标签", 126, 103),
    LOCATION("首处位置", 400, 104),
    COUNT("次数", 84, 80),
}

private class WordTextRenderer(private val bold: Boolean = false) : DefaultTableCellRenderer() {
    init {
        putClientProperty("html.disable", true)
    }

    override fun getTableCellRendererComponent(
        table: JTable,
        value: Any?,
        isSelected: Boolean,
        hasFocus: Boolean,
        row: Int,
        column: Int,
    ): Component {
        super.getTableCellRendererComponent(table, value, isSelected, hasFocus, row, column)
        if (bold) font = table.font.deriveFont(Font.BOLD)
        toolTipText = text.takeIf(String::isNotBlank)
        return this
    }
}

/** 复用两个绘制组件。标签用文字说明含义，浅底色只用于分组。 */
private class WordTagRenderer(private val tagsAt: (Int) -> Set<WordTag>) :
    JPanel(FlowLayout(FlowLayout.LEADING, JBUI.scale(3), JBUI.scale(3))), TableCellRenderer {
    private val badges =
        mapOf(
            WordTag.TARGET to
                TagBadge(
                    WordTag.TARGET.label,
                    JBColor(0x0E5679, 0xA4D5F1),
                    JBColor(0xE8F3FB, 0x253B4B),
                ),
            WordTag.COLLECTED to
                TagBadge(
                    WordTag.COLLECTED.label,
                    JBColor(0x206145, 0xB0DFCA),
                    JBColor(0xEAF5ED, 0x293C34),
                ),
        )

    init {
        badges.values.forEach { add(it) }
    }

    override fun getTableCellRendererComponent(
        table: JTable,
        value: Any?,
        isSelected: Boolean,
        hasFocus: Boolean,
        row: Int,
        column: Int,
    ): Component {
        val tags = tagsAt(table.convertRowIndexToModel(row))
        badges.forEach { (tag, badge) -> badge.isVisible = tag in tags }
        isOpaque = isSelected
        background = if (isSelected) table.selectionBackground else table.background
        return this
    }
}

private class TagBadge(label: String, ink: JBColor, private val fill: JBColor) : JBLabel(label) {
    init {
        foreground = ink
        font = JBUI.Fonts.smallFont()
        border = JBUI.Borders.empty(1, 4)
    }

    override fun paintComponent(graphics: Graphics) {
        val shading = graphics.create() as Graphics2D
        try {
            shading.setRenderingHint(
                RenderingHints.KEY_ANTIALIASING,
                RenderingHints.VALUE_ANTIALIAS_ON,
            )
            shading.color = fill
            shading.fillRoundRect(0, 0, width, height, JBUI.scale(8), JBUI.scale(8))
        } finally {
            shading.dispose()
        }
        super.paintComponent(graphics)
    }
}
