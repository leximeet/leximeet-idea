package com.github.weilhuang.leximeetidea.reading

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.popup.JBPopup
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.ui.JBColor
import com.intellij.ui.awt.RelativePoint
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBTextArea
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Dimension
import java.awt.Font
import java.awt.Point
import java.awt.Window
import java.awt.event.ComponentAdapter
import java.awt.event.ComponentEvent
import java.util.concurrent.CompletableFuture
import javax.swing.BorderFactory
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.JComboBox
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.SwingUtilities
import javax.swing.Timer

private const val CARD_TEXT_WIDTH = 330
private const val CARD_PADDING = 16
private val cardSurface = JBColor(Color(0xFAFCFC), Color(0x282C30))
private val cardOutline = JBColor(Color(0xD6E3E1), Color(0x47525A))
private val cardSecondary = JBColor(Color(0x535F65), Color(0xB8C3CA))

/** 延迟触发避免移动指针时频繁查询；卡片不申请焦点，异步返回时再次核对目标与资料租期。 */
internal class HoverCard(
    private val project: Project,
    private val current: () -> ReadingProjection?,
    private val load: (WordChoice, ReadingProjection) -> CompletableFuture<WordCard>,
) {
    private class Hit(
        val anchor: JComponent,
        val identity: Any,
        val token: String,
        val point: Point,
        val rowHeight: Int,
        val projection: ReadingProjection,
    )

    private var hit: Hit? = null
    private var popup: JBPopup? = null
    private var host: Window? = null
    private var request: CompletableFuture<WordCard>? = null
    private val layoutChange =
        object : ComponentAdapter() {
            override fun componentResized(e: ComponentEvent) = clear()

            override fun componentMoved(e: ComponentEvent) = clear()
        }
    private val delay = Timer(280) { show() }.apply { isRepeats = false }
    private val hideDelay =
        Timer(180) {
                val content = popup?.content
                if (content?.mousePosition == null) clear()
            }
            .apply { isRepeats = true }

    fun over(
        anchor: JComponent,
        identity: Any,
        token: String,
        point: Point,
        rowHeight: Int,
        projection: ReadingProjection,
    ) {
        hideDelay.stop()
        val old = hit
        if (
            old?.anchor === anchor &&
                old.identity == identity &&
                old.projection === projection &&
                (popup?.isVisible == true || delay.isRunning)
        )
            return
        clear()
        hit = Hit(anchor, identity, token, point, rowHeight, projection)
        // 移动或缩放宿主窗口会让词卡原先的屏幕坐标失效。
        host = SwingUtilities.getWindowAncestor(anchor)
        host?.addComponentListener(layoutChange)
        delay.restart()
    }

    fun leave() {
        delay.stop()
        if (popup == null) clear() else hideDelay.restart()
    }

    fun clear() {
        delay.stop()
        hideDelay.stop()
        request?.cancel(false)
        request = null
        popup?.cancel()
        popup = null
        hit = null
        host?.removeComponentListener(layoutChange)
        host = null
    }

    private fun show() {
        val target = hit ?: return
        if (!valid(target)) return
        val choices = target.projection.choices(target.token)
        if (choices.isEmpty()) return
        val card =
            JPanel(BorderLayout(JBUI.scale(8), JBUI.scale(9))).apply {
                name = "leximeet-word-card"
                background = cardSurface
                border =
                    BorderFactory.createCompoundBorder(
                        BorderFactory.createLineBorder(cardOutline),
                        JBUI.Borders.empty(13, CARD_PADDING),
                    )
            }
        val body =
            JPanel().apply {
                layout = BoxLayout(this, BoxLayout.Y_AXIS)
                isOpaque = false
            }
        if (choices.size > 1) {
            val options =
                listOf("选择词条（%d）".format(choices.size)) +
                    choices.mapIndexed { i, choice ->
                        val source =
                            if (choice.ref.get("kind").asString == "custom") "个人词" else "桌面词典"
                        "%d. %s · %s".format(i + 1, choice.headword, source)
                    }
            val selector =
                JComboBox(options.toTypedArray()).apply {
                    name = "leximeet-word-choice"
                    putClientProperty("html.disable", true)
                    addActionListener {
                        val selected = choices.getOrNull(selectedIndex - 1)
                        if (selected != null) showChoice(target, selected, body)
                        else {
                            request?.cancel(false)
                            fill(body, target.token, null, "有多个词条，请选择要查看的词条")
                            placeLoadedCard(target)
                        }
                    }
                }
            card.add(selector, BorderLayout.NORTH)
            fill(body, target.token, null, "有多个词条，请选择要查看的词条")
        } else {
            showChoice(target, choices.single(), body)
        }
        card.add(body, BorderLayout.CENTER)
        popup =
            JBPopupFactory.getInstance()
                .createComponentPopupBuilder(card, null)
                .setProject(project)
                .setRequestFocus(false)
                .setFocusable(false)
                .setCancelOnClickOutside(true)
                .setCancelOnWindowDeactivation(true)
                .setResizable(false)
                .setMovable(false)
                .createPopup()
                .also {
                    it.showInScreenCoordinates(
                        target.anchor,
                        screenLocation(target, card.preferredSize.height),
                    )
                }
    }

    private fun placeLoadedCard(target: Hit) {
        val shown = popup ?: return
        shown.pack(false, true)
        shown.setLocation(screenLocation(target, shown.size.height))
    }

    private fun screenLocation(target: Hit, cardHeight: Int): Point {
        val point = RelativePoint(target.anchor, target.point).screenPoint
        host?.let { frame ->
            // 占位与正文同宽。IDEA 窄窗右侧为词卡留出实际宽度。
            val left = frame.locationOnScreen.x
            val right = left + frame.width - JBUI.scale(CARD_TEXT_WIDTH + 2 * CARD_PADDING + 20)
            point.x = point.x.coerceIn(left, right.coerceAtLeast(left))
        }
        val visible = target.anchor.visibleRect
        val top = target.anchor.locationOnScreen.y + visible.y
        val above = point.y - target.rowHeight - cardHeight - JBUI.scale(4)
        // 底部空间不足时从单词上方展开，避免遮住 IDEA 底部的词遇操作。
        if (point.y + cardHeight > top + visible.height && above >= top) point.y = above
        return point
    }

    private fun valid(target: Hit): Boolean =
        hit === target &&
            !project.isDisposed &&
            target.anchor.isShowing &&
            current() === target.projection

    private fun showChoice(target: Hit, selected: WordChoice, body: JPanel) {
        request?.cancel(false)
        fill(body, selected.headword, null, "正在读取桌面词卡…")
        val pending = load(selected, target.projection)
        request = pending
        pending.whenComplete { card, _ ->
            ApplicationManager.getApplication().invokeLater {
                if (!valid(target) || request !== pending || popup?.isVisible != true)
                    return@invokeLater
                fill(body, selected.headword, card, "词卡暂不可用")
                placeLoadedCard(target)
            }
        }
    }

    private fun fill(body: JPanel, headword: String, card: WordCard?, placeholder: String) {
        body.removeAll()
        body.add(
            JBLabel().apply {
                name = "leximeet-card-word"
                putClientProperty("html.disable", true)
                text = card?.headword ?: headword
                font = font.deriveFont(Font.BOLD, JBUI.scale(19).toFloat())
            }
        )
        card?.phonetic?.takeIf(String::isNotBlank)?.let {
            body.add(
                JBLabel().apply {
                    putClientProperty("html.disable", true)
                    text = it
                    foreground = cardSecondary
                }
            )
        }
        body.add(Box.createVerticalStrut(JBUI.scale(8)))
        val definitions = card?.definitions.orEmpty().take(3)
        body.add(
            text(
                if (card == null) placeholder
                else definitions.joinToString("\n") { it.take(180) }.ifBlank { "暂无桌面释义" },
                "leximeet-card-definition",
            )
        )
        card?.note?.let {
            body.add(Box.createVerticalStrut(JBUI.scale(8)))
            body.add(text("笔记  $it", "leximeet-card-note"))
        }
        if (card != null) {
            body.add(Box.createVerticalStrut(JBUI.scale(9)))
            body.add(
                JBLabel(
                        listOf("词遇桌面端", card.statusLabel)
                            .filter(String::isNotBlank)
                            .joinToString("  ·  ")
                    )
                    .apply { foreground = cardSecondary }
            )
        }
        body.revalidate()
        body.repaint()
    }

    private fun text(value: String, componentName: String) =
        JBTextArea(value).apply {
            name = componentName
            isEditable = false
            isFocusable = false
            isOpaque = false
            lineWrap = true
            wrapStyleWord = true
            border = JBUI.Borders.empty()
            font = JBUI.Fonts.label()
            setSize(JBUI.scale(CARD_TEXT_WIDTH), Short.MAX_VALUE.toInt())
            preferredSize = Dimension(JBUI.scale(CARD_TEXT_WIDTH), preferredSize.height)
            alignmentX = JComponent.LEFT_ALIGNMENT
        }
}
