package com.github.weilhuang.leximeetidea.toolWindow

import com.github.weilhuang.leximeetidea.connection.DesktopConnection
import com.github.weilhuang.leximeetidea.connection.DesktopLinkService
import com.github.weilhuang.leximeetidea.connection.LinkPhase
import com.github.weilhuang.leximeetidea.reading.ProjectEncounter
import com.github.weilhuang.leximeetidea.settings.EncounterPreferences
import com.github.weilhuang.leximeetidea.settings.LexiMeetConfigurable
import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.components.service
import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBLabel
import com.intellij.ui.content.ContentFactory
import com.intellij.util.ui.JBDimension
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Component
import java.awt.FlowLayout
import java.awt.Font
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.event.HierarchyEvent
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.Icon
import javax.swing.JButton
import javax.swing.JPanel
import javax.swing.Timer

internal class LexiMeetToolWindow : ToolWindowFactory, DumbAware {
    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val panel =
            ReadingPanel(
                project,
                toolWindow,
                service<DesktopLinkService>().link,
                project.service<ProjectEncounter>(),
            )
        val content = ContentFactory.getInstance().createContent(panel, "", false)
        content.setDisposer(
            Disposer.newDisposable("LexiMeet 界面").apply { Disposer.register(this) { panel.stop() } }
        )
        toolWindow.contentManager.addContent(content)
    }
}

/** 打开底部工具窗时自动匹配，连接和刷新状态都留在工具栏。 */
private class ReadingPanel(
    project: Project,
    private val toolWindow: ToolWindow,
    private val connection: DesktopConnection,
    private val encounter: ProjectEncounter,
) : JPanel(BorderLayout()) {
    private val state =
        JBLabel().apply {
            name = "leximeet-link-state"
            font = font.deriveFont(Font.BOLD)
            iconTextGap = JBUI.scale(8)
            putClientProperty("html.disable", true)
        }
    private val detail =
        JBLabel().apply {
            name = "leximeet-link-detail"
            foreground = JBColor.namedColor("Label.infoForeground", JBColor.GRAY)
        }
    private val detect = button("discover", "重新检测", "在本机重新查找词遇桌面端") { connection.discover() }
    private val connect = button("connect", "连接桌面端", primary = true) { connection.request() }
    private val confirm = button("confirm", "确认只读连接", primary = true) { connection.confirm() }
    private val cancel = button("cancel", "取消邀请") { connection.cancel() }
    private val disconnect = button("disconnect", "断开连接", "结束本机只读连接") { connection.disconnect() }
    private val refresh =
        JButton("刷新", AllIcons.Actions.Refresh).apply {
            name = "leximeet-refresh"
            preferredSize = JBDimension(108, 28)
            toolTipText = "重新匹配当前编辑器和项目树"
            accessibleContext.accessibleName = "刷新匹配词表"
            addActionListener {
                encounter.refresh()
                update()
            }
        }
    private val highlight =
        JBCheckBox("持续高亮").apply {
            name = "leximeet-highlight"
            toolTipText = "持续标记编辑器和项目树中的命中词"
            addActionListener { encounter.setHighlight(isSelected) }
        }
    private val settings =
        JButton(AllIcons.General.Settings).apply {
            name = "leximeet-settings"
            preferredSize = JBDimension(28, 28)
            toolTipText = "词遇设置"
            accessibleContext.accessibleName = "词遇设置"
            addActionListener {
                ShowSettingsUtil.getInstance()
                    .showSettingsDialog(project, LexiMeetConfigurable::class.java)
            }
        }
    private val words = EncounterWordsPanel(encounter)
    private val commands = listOf(detect, connect, confirm, cancel, disconnect)
    private val phaseIcons = LinkPhase.entries.associateWith { StatusDot(it) }
    private val timer = Timer(180) { update() }

    init {
        name = "leximeet-tool-window"
        getAccessibleContext().accessibleName = "词遇桌面连接与遇见"
        border = JBUI.Borders.empty(12, 16)
        val controls =
            JPanel(FlowLayout(FlowLayout.TRAILING, JBUI.scale(6), 0)).apply {
                add(cancel)
                add(disconnect)
                add(detect)
                add(connect)
                add(confirm)
                add(highlight)
                add(refresh)
                add(settings)
            }
        val status =
            JPanel().apply {
                layout = BoxLayout(this, BoxLayout.Y_AXIS)
                add(state)
                add(Box.createVerticalStrut(JBUI.scale(4)))
                detail.border = JBUI.Borders.emptyLeft(JBUI.scale(15))
                detail.putClientProperty("html.disable", true)
                add(detail)
            }
        val contents =
            JPanel(BorderLayout(JBUI.scale(12), 0)).apply {
                add(status, BorderLayout.CENTER)
                add(controls, BorderLayout.EAST)
            }
        add(contents, BorderLayout.NORTH)
        add(words, BorderLayout.CENTER)
        connection.start()
        addHierarchyListener { event ->
            if (event.changeFlags and HierarchyEvent.SHOWING_CHANGED.toLong() != 0L) update()
        }
        timer.start()
        update()
    }

    fun stop() {
        timer.stop()
        encounter.setActive(false)
        words.closeDetail()
    }

    private fun button(
        id: String,
        text: String,
        tooltip: String? = null,
        primary: Boolean = false,
        run: () -> Unit,
    ) =
        JButton(text).apply {
            name = "leximeet-$id"
            toolTipText = tooltip
            if (primary) font = font.deriveFont(Font.BOLD)
            addActionListener { run() }
        }

    private fun update() {
        val current = connection.view
        val ready = current.readable
        val phase = current.phase
        val busy = connection.busy
        encounter.setActive(ready && toolWindow.isVisible && isShowing)
        state.icon = phaseIcons[phase]
        state.text =
            when (phase) {
                LinkPhase.LOOKING -> "正在检测桌面端…"
                LinkPhase.MISSING -> "未发现桌面端"
                LinkPhase.FOUND -> "已发现桌面端"
                LinkPhase.INVITED -> "等待确认连接"
                LinkPhase.CONNECTED -> "已连接词遇桌面端"
                LinkPhase.INTERRUPTED -> "连接已中断"
                LinkPhase.UNAVAILABLE -> "桌面连接不可用"
            }
        state.toolTipText = current.name.ifBlank { null }
        detail.text =
            when {
                !current.error.isNullOrBlank() -> current.error
                phase == LinkPhase.INVITED && current.desktopInitiated -> "桌面端邀请 IDEA 只读查词"
                phase == LinkPhase.INVITED -> "允许访问桌面词库和词卡"
                phase == LinkPhase.MISSING -> "启动词遇桌面端后会自动发现"
                phase == LinkPhase.FOUND -> "连接后可在项目树与代码中查词"
                phase == LinkPhase.INTERRUPTED -> "正在尝试恢复连接"
                ready -> encounter.status
                else -> ""
            }
        detail.toolTipText = detail.text.ifBlank { null }
        detect.isVisible = phase == LinkPhase.MISSING || phase == LinkPhase.INTERRUPTED
        connect.isVisible = phase == LinkPhase.FOUND
        confirm.isVisible = phase == LinkPhase.INVITED
        cancel.isVisible = phase == LinkPhase.INVITED
        disconnect.isVisible = ready
        commands.forEach { it.isEnabled = it.isVisible && !busy }
        refresh.isVisible = ready
        refresh.isEnabled = ready && !busy && !encounter.matching
        refresh.text = if (encounter.matching) "刷新中…" else "刷新"
        highlight.isVisible = ready
        highlight.isSelected = EncounterPreferences.highlight
        words.isVisible = ready
        words.update()
    }
}

/** 点色会随 IDE 主题切换，连接状态同时用文字表达。 */
private class StatusDot(phase: LinkPhase) : Icon {
    private val dotColor =
        when (phase) {
            LinkPhase.CONNECTED -> JBColor(Color(0x087E64), Color(0x62C7A9))
            LinkPhase.FOUND,
            LinkPhase.INVITED -> JBColor(Color(0x286F9C), Color(0x88BDF1))
            LinkPhase.INTERRUPTED -> JBColor(Color(0xA15C23), Color(0xE0A574))
            else -> JBColor.GRAY
        }

    override fun getIconWidth() = JBUI.scale(7)

    override fun getIconHeight() = iconWidth

    override fun paintIcon(c: Component?, g: Graphics, x: Int, y: Int) {
        val graphics = g.create() as Graphics2D
        try {
            graphics.setRenderingHint(
                RenderingHints.KEY_ANTIALIASING,
                RenderingHints.VALUE_ANTIALIAS_ON,
            )
            graphics.color = dotColor
            graphics.fillOval(x, y, iconWidth, iconHeight)
        } finally {
            graphics.dispose()
        }
    }
}

/** IDE 的动作系统可搜索和分配快捷键，插件不拦截编辑器输入。 */
internal class EncounterAction : AnAction(), DumbAware {
    override fun getActionUpdateThread() = ActionUpdateThread.EDT

    override fun update(event: AnActionEvent) {
        event.presentation.isEnabled = event.project != null
    }

    override fun actionPerformed(event: AnActionEvent) {
        val project = event.project ?: return
        val window = ToolWindowManager.getInstance(project).getToolWindow("LexiMeet") ?: return
        if (window.isVisible) project.service<ProjectEncounter>().refresh() else window.show()
    }
}
