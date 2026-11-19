package com.github.weilhuang.leximeetidea.toolWindow

import com.github.weilhuang.leximeetidea.reading.MatchKey
import com.github.weilhuang.leximeetidea.reading.MatchLocation
import com.github.weilhuang.leximeetidea.reading.MatchPosition
import com.github.weilhuang.leximeetidea.reading.MatchedWord
import com.github.weilhuang.leximeetidea.reading.ProjectEncounter
import com.github.weilhuang.leximeetidea.reading.ReadingProjection
import com.github.weilhuang.leximeetidea.reading.WordCard
import com.github.weilhuang.leximeetidea.reading.WordChoice
import com.intellij.icons.AllIcons
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.ui.ComboBox
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.util.ui.JBDimension
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import java.awt.BorderLayout
import java.awt.Component
import java.awt.Cursor
import java.awt.Font
import java.awt.Point
import java.awt.Rectangle
import java.awt.event.ActionEvent
import java.awt.event.KeyEvent
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.util.concurrent.CompletableFuture
import javax.swing.AbstractAction
import javax.swing.BoxLayout
import javax.swing.DefaultComboBoxModel
import javax.swing.DefaultListCellRenderer
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JList
import javax.swing.JPanel
import javax.swing.KeyStroke
import javax.swing.ListSelectionModel
import javax.swing.ScrollPaneConstants
import javax.swing.Scrollable
import javax.swing.SwingUtilities
import javax.swing.text.DefaultCaret

/** 底部词表的右侧词卡。释义走既有只读接口，项目出处只在本机定位。 */
internal class EncounterWordDetail(
    private val encounter: ProjectEncounter,
    close: () -> Unit,
    jump: (MatchPosition) -> Unit,
) : JPanel(BorderLayout()) {
    private data class Selection(val key: MatchKey, val owner: String, val revision: String) {
        fun matches(snapshot: ReadingProjection) =
            owner == snapshot.ownerKey && revision == snapshot.revision
    }

    private var selection: Selection? = null
    private var choices = emptyList<WordChoice>()
    private var positions = emptyList<MatchLocation>()
    private var pending: CompletableFuture<WordCard>? = null
    private var loaded: WordCard? = null
    private val word =
        JBLabel().apply {
            name = "leximeet-detail-word"
            putClientProperty("html.disable", true)
            font = font.deriveFont(Font.BOLD, JBUI.scale(18).toFloat())
        }
    private val meta =
        JBLabel().apply {
            name = "leximeet-detail-meta"
            putClientProperty("html.disable", true)
            foreground = UIUtil.getContextHelpForeground()
        }
    private val chooser =
        ComboBox<String>().apply {
            name = "leximeet-detail-choice"
            putClientProperty("html.disable", true)
            isVisible = false
            addActionListener {
                if (isVisible) {
                    val choice = selectedChoice()
                    if (choice == null) {
                        pending?.cancel(false)
                        pending = null
                        loaded = null
                        render(selection?.key?.word.orEmpty(), "选择要查看的词条")
                    } else load(choice)
                }
            }
        }
    private val definitions = DetailText("释义", "leximeet-detail-definition")
    private val forms = DetailText("词形", "leximeet-detail-forms")
    private val examples = DetailText("例句", "leximeet-detail-examples")
    private val note = DetailText("个人笔记", "leximeet-detail-note")
    private val sourceTitle = JBLabel().apply { font = font.deriveFont(Font.BOLD) }
    private val sources =
        JBList<MatchLocation>().apply {
            name = "leximeet-detail-sources"
            accessibleContext.accessibleName = "可定位的项目出处"
            selectionMode = ListSelectionModel.SINGLE_SELECTION
            fixedCellHeight = JBUI.scale(22)
            cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
            toolTipText = "点击或按 Enter 定位到这处命中"
            cellRenderer =
                object : DefaultListCellRenderer() {
                    override fun getListCellRendererComponent(
                        list: JList<*>?,
                        value: Any?,
                        index: Int,
                        selected: Boolean,
                        hasFocus: Boolean,
                    ): Component {
                        super.getListCellRendererComponent(list, value, index, selected, hasFocus)
                        putClientProperty("html.disable", true)
                        text = (value as? MatchLocation)?.sourceLabel.orEmpty()
                        if (!selected)
                            foreground =
                                JBColor.namedColor(
                                    "Link.activeForeground",
                                    JBColor(0x215DBC, 0x75A7F0),
                                )
                        return this
                    }
                }
            addMouseListener(
                object : MouseAdapter() {
                    override fun mouseClicked(event: MouseEvent) {
                        if (!SwingUtilities.isLeftMouseButton(event) || event.clickCount != 1)
                            return
                        val index = locationToIndex(event.point)
                        if (index >= 0 && getCellBounds(index, index).contains(event.point))
                            jump(model.getElementAt(index).position)
                    }
                }
            )
            getInputMap(JComponent.WHEN_FOCUSED)
                .put(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, 0), "leximeet.locate.source")
            actionMap.put(
                "leximeet.locate.source",
                object : AbstractAction() {
                    override fun actionPerformed(event: ActionEvent) {
                        selectedValue?.let { jump(it.position) }
                    }
                },
            )
        }
    private val scroll =
        JBScrollPane(
                DetailBody().apply {
                    add(definitions)
                    add(group(sourceTitle, sources))
                    add(forms)
                    add(examples)
                    add(note)
                },
                ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED,
                ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER,
            )
            .apply {
                name = "leximeet-detail-scroll"
                border = JBUI.Borders.empty()
            }

    init {
        name = "leximeet-word-detail"
        getAccessibleContext().accessibleName = "遇见词卡"
        border = JBUI.Borders.empty(3, 12, 1, 0)
        val dismiss =
            JButton(AllIcons.Actions.Close).apply {
                name = "leximeet-detail-close"
                toolTipText = "关闭词卡"
                accessibleContext.accessibleName = "关闭词卡"
                preferredSize = JBDimension(25, 25)
                addActionListener { close() }
            }
        val heading =
            JPanel(BorderLayout()).apply {
                add(word, BorderLayout.CENTER)
                add(dismiss, BorderLayout.EAST)
            }
        add(
            JPanel().apply {
                layout = BoxLayout(this, BoxLayout.Y_AXIS)
                add(heading)
                add(meta)
                add(chooser)
                listOf(heading, meta, chooser).forEach { it.alignmentX = JComponent.LEFT_ALIGNMENT }
            },
            BorderLayout.NORTH,
        )
        add(scroll, BorderLayout.CENTER)
    }

    fun show(match: MatchedWord<MatchLocation>, retry: Boolean): Boolean {
        val snapshot = encounter.visibleProjection() ?: return false
        val next = Selection(match.key, snapshot.ownerKey, snapshot.revision)
        val current = snapshot.choices(match.locations.first().span.text)
        if (current.isEmpty()) return false
        if (positions != match.locations) {
            positions = match.locations
            sources.setListData(positions.toTypedArray())
            sourceTitle.text = "项目出处 · ${positions.size} 处"
        }
        if (selection != next) {
            clearRequest()
            selection = next
            choices = current
            chooser.isVisible = false
            chooser.model =
                DefaultComboBoxModel(
                    (listOf("选择词条") +
                            choices.map {
                                val origin =
                                    if (it.ref.get("kind")?.asString == "custom") "个人词" else "桌面词典"
                                "${it.headword} · $origin"
                            })
                        .toTypedArray()
                )
            chooser.isVisible = choices.size > 1
            scroll.verticalScrollBar.value = 0
            val choice = selectedChoice()
            if (choice == null) render(match.key.word, "选择要查看的词条") else load(choice)
        } else if (retry && pending == null && loaded == null) {
            selectedChoice()?.let(::load)
        }
        return true
    }

    fun clear() {
        clearRequest()
        selection = null
        choices = emptyList()
        positions = emptyList()
        sources.setListData(emptyArray())
        chooser.isVisible = false
        render("", "")
    }

    private fun selectedChoice(): WordChoice? =
        choices.singleOrNull() ?: choices.getOrNull(chooser.selectedIndex - 1)

    private fun clearRequest() {
        pending?.cancel(false)
        pending = null
        loaded = null
    }

    private fun load(choice: WordChoice) {
        val snapshot = encounter.visibleProjection() ?: return
        val expected = selection?.takeIf { it.matches(snapshot) } ?: return
        clearRequest()
        render(choice.headword, "正在读取桌面词卡…")
        scroll.verticalScrollBar.value = 0
        val request = encounter.card(choice, snapshot)
        pending = request
        request.whenComplete { card, _ ->
            ApplicationManager.getApplication().invokeLater {
                if (pending !== request || selection != expected) return@invokeLater
                pending = null
                val active = encounter.visibleProjection()
                // 切词、撤权或资料版本变化后，旧请求不能填回可见的词卡。
                if (active == null || !expected.matches(active)) return@invokeLater
                loaded = card
                render(choice.headword, "词卡暂不可用，单击词条重试")
                // 内容重新布局后再复位，避免保留上一张卡片的滚动位置。
                SwingUtilities.invokeLater {
                    if (selection == expected && loaded === card)
                        scroll.viewport.viewPosition = Point()
                }
            }
        }
    }

    private fun render(headword: String, placeholder: String) {
        val card = loaded
        word.text = card?.headword ?: headword
        meta.text =
            card
                ?.let {
                    listOf(it.phonetic, it.statusLabel)
                        .filter(String::isNotBlank)
                        .joinToString("  ·  ")
                }
                .orEmpty()
        meta.isVisible = meta.text.isNotBlank()
        definitions.showText(
            if (card == null) placeholder
            else card.definitions.joinToString("\n").ifBlank { "暂无桌面释义" }
        )
        forms.showText(card?.forms.orEmpty().joinToString(" · "))
        examples.showText(card?.examples.orEmpty().joinToString("\n\n"))
        note.showText(card?.note.orEmpty())
        revalidate()
        repaint()
    }

    private class DetailBody : JPanel(), Scrollable {
        init {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
        }

        override fun getPreferredScrollableViewportSize() = preferredSize

        override fun getScrollableTracksViewportWidth() = true

        override fun getScrollableTracksViewportHeight() = false

        override fun getScrollableUnitIncrement(
            visible: Rectangle,
            orientation: Int,
            direction: Int,
        ) = JBUI.scale(18)

        override fun getScrollableBlockIncrement(
            visible: Rectangle,
            orientation: Int,
            direction: Int,
        ) = visible.height
    }

    private class DetailText(title: String, id: String) : JPanel(BorderLayout()) {
        private val body =
            JBTextArea().apply {
                name = id
                isEditable = false
                lineWrap = true
                wrapStyleWord = true
                isOpaque = false
                border = JBUI.Borders.empty()
                font = JBUI.Fonts.label()
            }

        init {
            val heading = JBLabel(title).apply { font = font.deriveFont(Font.BOLD) }
            add(group(heading, body), BorderLayout.CENTER)
            alignmentX = JComponent.LEFT_ALIGNMENT
        }

        fun showText(value: String) {
            // 主题切换会重装插入点，刷新文本前关闭它的自动滚动。
            (body.caret as? DefaultCaret)?.updatePolicy = DefaultCaret.NEVER_UPDATE
            body.text = value
            isVisible = value.isNotBlank()
        }
    }
}

private fun group(title: JBLabel, body: JComponent) =
    JPanel(BorderLayout(0, JBUI.scale(4))).apply {
        border = JBUI.Borders.emptyTop(7)
        alignmentX = JComponent.LEFT_ALIGNMENT
        add(title, BorderLayout.NORTH)
        add(body, BorderLayout.CENTER)
    }
