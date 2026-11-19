package com.github.weilhuang.leximeetidea.uitest

import com.intellij.ide.AppLifecycleListener
import com.intellij.ide.projectView.ProjectView
import com.intellij.ide.ui.LafManager
import com.intellij.ide.util.PropertiesComponent
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity
import com.intellij.openapi.util.SystemInfo
import com.intellij.openapi.vfs.JarFileSystem
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.openapi.wm.WindowManager
import com.intellij.ui.SimpleColoredComponent
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.mac.foundation.Foundation
import com.intellij.ui.mac.foundation.ID
import com.intellij.util.concurrency.AppExecutorUtil
import java.awt.Component
import java.awt.Container
import java.awt.Rectangle
import java.awt.Window
import java.awt.event.ActionEvent
import java.awt.event.KeyEvent
import java.awt.event.MouseEvent
import java.awt.image.BufferedImage
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.time.Instant
import java.util.concurrent.FutureTask
import javax.imageio.ImageIO
import javax.swing.JButton
import javax.swing.JCheckBox
import javax.swing.JComboBox
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JList
import javax.swing.JScrollPane
import javax.swing.JTable
import javax.swing.JTree
import javax.swing.KeyStroke
import javax.swing.SortOrder
import javax.swing.SwingUtilities
import javax.swing.text.JTextComponent

/** IDE 打开项目窗口前禁用原生激活，覆盖项目启动活动执行前的时间段。 */
class ReadingUiBackgroundListener : AppLifecycleListener {
    override fun appFrameCreated(commandLineArgs: List<String>) {
        if (!SystemInfo.isMac || System.getProperty("leximeet.test") != "true") return
        val output = System.getProperty("leximeet.ui.output") ?: return
        var prohibited = false
        Foundation.executeOnMainThread(false, true) {
            val application = Foundation.invoke("NSApplication", "sharedApplication")
            Foundation.invoke(application, "setActivationPolicy:", 2)
            Foundation.invoke(application, "hide:", ID.NIL)
            prohibited = Foundation.invoke(application, "activationPolicy").toInt() == 2
        }
        check(prohibited) { "无法在创建项目窗口前禁止隔离 IDEA 激活" }
        Files.writeString(Path.of(output).resolve("idea-background-prepared"), "ok\n")
    }
}

/** 在独立 IDEA 进程中向自有测试界面派发 Swing 事件。测试不移动系统鼠标， 不读取日常项目，也不调用业务插件内部方法。 */
class ReadingUiActivity : ProjectActivity {
    override suspend fun execute(project: Project) {
        val output = System.getProperty("leximeet.ui.output") ?: return
        if (System.getProperty("leximeet.test") != "true") return
        val fixture = Path.of(project.basePath ?: return)
        if (!Files.isRegularFile(fixture.resolve(".leximeet-ui-fixture"))) return
        AppExecutorUtil.getAppExecutorService().execute {
            val test = ReadingUi(project, fixture, Path.of(output))
            try {
                if (SystemInfo.isMac) {
                    check(
                        Files.isRegularFile(Path.of(output).resolve("idea-background-prepared"))
                    ) {
                        "后台防护没有在项目窗口前启用"
                    }
                }
                test.run()
            } catch (failure: Throwable) {
                test.recordFailure(failure)
            }
        }
    }
}

private class ReadingUi(
    private val project: Project,
    private val fixture: Path,
    private val output: Path,
) {
    private val baseline = System.getProperty("leximeet.ui.baseline") == "true"
    private val uiOnly = System.getProperty("leximeet.ui.only") == "true"
    private val fullDecompiler = System.getProperty("leximeet.ui.fullDecompiler") == "true"
    private val events = mutableListOf<String>()

    fun run() {
        mark("idea-project-open")
        val manager = edt { ToolWindowManager.getInstance(project) }
        val view =
            await("IDEA 工具窗注册", Duration.ofSeconds(100)) {
                edt { manager.getToolWindow(if (baseline) "MyToolWindow" else "LexiMeet") }
            }
        val editor = open(find("src/resilience/Resilience.java"))
        mark("idea-source-open")
        val tree =
            await("Project View 就绪") {
                edt {
                    manager.getToolWindow("Project")?.show()
                    ProjectView.getInstance(project).currentProjectViewPane?.tree?.takeIf {
                        it.rowCount >= 1
                    }
                }
            }
        edt {
            val frame = WindowManager.getInstance().getFrame(project) ?: error("找不到 IDEA 项目窗口")
            frame.isAutoRequestFocus = false
            frame.setSize(1280, 830)
            val laf = LafManager.getInstance()
            laf.setCurrentUIThemeLookAndFeel(requireNotNull(laf.defaultLightLaf))
            laf.updateUI()
            view.show()
        }
        if (baseline) {
            await("旧模板控件可见") { edt { view.component.takeIf { it.isShowing } } }
            snap("before-idea")
            step("旧模板基线")
            mark("passed")
            return
        }

        expandTree(tree)
        check(edt { view.anchor.toString() == "bottom" }) { "词遇工具窗未停靠在底部" }
        waitState("未发现桌面端")
        check(edt { !refreshAvailable() })
        if (uiOnly) {
            verifyStandalone()
            return
        }
        mark("desktop-missing")
        step("独立 IDEA 未发现桌面端时无法刷新读取")
        waitState("已发现")
        step("IDEA 找到真实桌面端")

        click("leximeet-connect")
        waitState("等待确认")
        check(edt { !refreshAvailable() })
        click("leximeet-cancel")
        waitState("已发现")
        mark("plugin-cancelled")
        step("IDEA 发起邀请后取消，未打开读取权限")

        waitState("等待确认")
        await("桌面端发起邀请") {
            edt { label("leximeet-link-detail").text.takeIf { it.contains("桌面端邀请") } }
        }
        snap("idea-invitation")
        await("桌面设置显示等待确认") {
            true.takeIf { Files.isRegularFile(output.resolve("desktop-invitation-visible")) }
        }
        click("leximeet-confirm")
        waitState("已连接")
        mark("idea-connected")
        step("桌面端设置发起邀请，IDEA 用户确认后连通读词接口")

        val before = edt { editor.document.text }
        val stamp = edt { editor.document.modificationStamp }
        edt {
            editor.caretModel.moveToOffset(2)
            editor.selectionModel.setSelection(0, 3)
        }
        await("已打开的工具窗连接后自动匹配当前项目") {
            edt { wordRow("wander", "Resilience.java:").takeIf { it >= 0 } }
        }
        edt {
            check(component("leximeet-encounter") == null)
            check(!highlightBox().isSelected)
            check(ranges(editor, "wander").isEmpty())
            check(treeMark(tree, "wander_notes.txt", "wander") == null)
        }
        snap("after-words-light-default", includeCard = false)
        step("连接后打开的工具窗自动匹配，编辑器和项目树均无默认持续高亮")
        edt { highlightBox().doClick() }
        awaitMark(editor, "Resilience")
        awaitMark(editor, "wander")
        await("项目名被标记") { edt { treeMark(tree, "resilience-workspace", "resilience") } }
        await("文件夹被标记") { edt { treeMark(tree, "resilience", "resilience", exact = true) } }
        await("Java 文件节点被标记") { edt { treeMark(tree, "Resilience", "Resilience", exact = true) } }
        await("普通文件被标记") { edt { treeMark(tree, "wander_notes.txt", "wander") } }
        await("JAR 名称被标记") { edt { treeMark(tree, "resilience-tool.jar", "resilience") } }
        await("JAR 内文件节点被标记") { edt { treeMark(tree, "subtle.txt", "subtle") } }
        await("JAR 内 class 节点被标记") { edt { treeMark(tree, "WanderResilience", "Wander") } }
        check(edt { ranges(editor, "LEXIUNMATCHEDSENTINEL").isEmpty() })
        assertUnchanged(editor, before, stamp, verifyCursor = true)
        step("自动匹配覆盖项目、目录、文件、JAR 名称及源码，保留文档、选区和光标")

        awaitCard("wander") { hoverEditor(editor, "wander") }
        snap("after-idea-editor-card")
        step("源码标记悬浮显示桌面真实释义")
        leaveHover(editor.contentComponent)
        awaitCard("resilience") { hoverTree(tree, "resilience", "resilience") }
        snap("after-idea-tree-card")
        step("项目树目录文字悬浮显示词卡")
        leaveHover(tree)
        verifyTableControls(editor)
        verifyWordList(editor, tree)

        val archive = find("archives/resilience-tool.jar")
        val archiveRoot =
            JarFileSystem.getInstance().getJarRootForLocalFile(archive)
                ?: error("IDEA 未找到 JAR 文件系统")
        val doc =
            open(archiveRoot.findFileByRelativePath("fixture/subtle.txt") ?: error("JAR 词条夹具缺失"))
        awaitMark(doc, "subtle")
        check(edt { !doc.document.isWritable })
        step("JAR 内的文本文件以只读方式打开并标记")
        if (fullDecompiler) {
            // 只在明确启用完整反编译的沙箱中确认自生成 JAR，避免模态窗激活应用。
            edt {
                PropertiesComponent.getInstance().setValue("decompiler.legal.notice.accepted", true)
            }
        }
        val compiled =
            open(
                archiveRoot.findFileByRelativePath("fixture/WanderResilience.class")
                    ?: error("JAR class 夹具缺失")
            )
        val decompiled = edt { compiled.document.text }
        check(decompiled.contains("class WanderResilience") && decompiled.contains("wander"))
        if (fullDecompiler) check(decompiled.contains("return \"subtle\""))
        check(edt { !compiled.document.isWritable })
        awaitMark(compiled, "wander")
        val archiveWord = if (fullDecompiler) "subtle" else "wander"
        awaitMark(compiled, archiveWord)
        awaitCard(archiveWord) { hoverEditor(compiled, archiveWord) }
        snap("after-idea-jar-card")
        step("IDEA 的 " + (if (fullDecompiler) "JAR 反编译正文" else "JAR class 签名视图") + "可标记并悬浮真实词卡")
        edt {
            WindowManager.getInstance().getFrame(project)?.setSize(1000, 760)
            val laf = LafManager.getInstance()
            laf.setCurrentUIThemeLookAndFeel(requireNotNull(laf.defaultDarkLaf))
            laf.updateUI()
        }
        await("窗口重新排版时关闭旧词卡") { edt { true.takeIf { component("leximeet-word-card") == null } } }
        awaitMark(compiled, archiveWord)
        awaitCard(archiveWord) { hoverEditor(compiled, archiveWord) }
        edt {
            val card = component("leximeet-word-card") ?: error("窄窗词卡未显示")
            val frame = WindowManager.getInstance().getFrame(project) ?: error("IDEA 窗口未显示")
            val overflow =
                card.locationOnScreen.x + card.width - frame.locationOnScreen.x - frame.width
            check(overflow <= 0) { "词卡超出 IDEA 窗口右边缘 ${overflow}px" }
            val controls = component("leximeet-tool-window") ?: error("词遇工具窗未显示")
            check(card.locationOnScreen.y + card.height <= controls.locationOnScreen.y) {
                "反编译词卡遮住了底部操作"
            }
        }
        snap("after-idea-jar-dark")
        step("IDEA 暗色窄窗口中标记与词卡可见")
        leaveHover(compiled.contentComponent)
        snap("after-words-dark", includeCard = false)
        verifyArchiveJump(compiled, archiveWord)

        edt { view.hide() }
        await("收起工具窗后清除匹配与标记") {
            edt {
                true.takeIf {
                    ranges(compiled, "wander").isEmpty() &&
                        cardText().isEmpty() &&
                        component("leximeet-word-table") == null &&
                        component("leximeet-word-detail") == null
                }
            }
        }
        assertUnchanged(editor, before, stamp, verifyCursor = false)
        step("收起词遇工具窗后暂停匹配，清除词卡与临时标记")

        edt { view.show() }
        await("重新打开工具窗后重新匹配") {
            edt { wordRow("wander", "WanderResilience.class:").takeIf { it >= 0 } }
        }
        awaitMark(compiled, "wander")
        step("重新打开工具窗后自动刷新并恢复高亮偏好")
        activateWord("wander", "WanderResilience.class:")
        awaitWordDetails("wander")
        awaitCard("wander") { hoverEditor(compiled, "wander") }
        mark("desktop-stop-requested")
        waitState("连接已中断")
        await("断连后的只读资料失效") {
            edt {
                true.takeIf {
                    !refreshAvailable() &&
                        ranges(compiled, "wander").isEmpty() &&
                        cardText().isEmpty() &&
                        component("leximeet-word-table") == null &&
                        component("leximeet-word-detail") == null
                }
            }
        }
        step("桌面进程停止后词表、词卡与高亮消失")
        mark("idea-lost")
        waitState("已连接")
        await("重连后自动刷新词表") {
            edt { wordRow("wander", "WanderResilience.class:").takeIf { it >= 0 } }
        }
        step("同一桌面工作区重启后恢复连接并自动匹配")
        mark("idea-resumed")
        waitState("已发现")
        check(edt { !refreshAvailable() })
        step("桌面端设置明确断开后需要重新授权")

        click("leximeet-connect")
        waitState("等待确认")
        click("leximeet-confirm")
        waitState("已连接")
        click("leximeet-disconnect")
        waitState("已发现")
        step("IDEA 明确断开后停止读取")
        mark("passed")
    }

    /** 起一套真实 IDEA，不准备 Electron，验证未连接时的操作与明暗窄窗。 */
    private fun verifyStandalone() {
        check(edt { label("leximeet-link-detail").text.contains("自动发现") })
        check(edt { button("leximeet-discover").isFocusable })
        snap("standalone-light")
        saveHighlightSetting(expected = false, value = true, snapshot = "after-settings-light")
        click("leximeet-discover")
        waitState("未发现桌面端")
        check(edt { !refreshAvailable() })
        step("底部工具窗说明如何启动桌面端，重新检测后保持未连接")
        edt {
            WindowManager.getInstance().getFrame(project)?.setSize(1000, 760)
            LafManager.getInstance().let { laf ->
                laf.setCurrentUIThemeLookAndFeel(requireNotNull(laf.defaultDarkLaf))
                laf.updateUI()
            }
        }
        await("暗色窄窗中的重新检测") {
            edt { button("leximeet-discover").takeIf { it.isShowing && it.isEnabled } }
        }
        snap("standalone-dark")
        step("暗色窄窗保留可聚焦的重新检测按钮")
        saveHighlightSetting(expected = true, value = false, snapshot = "after-settings-dark")
        step("未连接也能从齿轮打开原生设置，切换主题并重新打开后读回已保存的偏好")
        mark("passed")
    }

    /** 对 Swing 表头和筛选组件派发事件，同时核对实际 Desktop 返回的收藏数据。 */
    private fun verifyTableControls(editor: Editor) {
        await("刷新按钮可用") { edt { true.takeIf { refreshAvailable() } } }
        edt {
            val table = wordTable()
            val columns = (0 until table.columnCount).map(table::getColumnName)
            check(columns == listOf("单词", "学习状态", "标签", "首处位置", "次数"))
            check(hasSortIndicator(table, "次数"))
            val count = table.rowCount
            check(count > 4)
            val refresh = button("leximeet-refresh")
            refresh.doClick(0)
            check(!refresh.isEnabled && refresh.text == "刷新中…")
            check(table.rowCount == count) { "手动刷新时上一轮有效结果被清空" }
        }
        await("手动刷新完成") { edt { true.takeIf { refreshAvailable() && wordTable().rowCount > 4 } } }
        step("手动刷新异步执行，刷新中保留词表并阻止重复请求")

        edt {
            val table = wordTable()
            val countColumn = table.columnIndex("次数")
            val before = (0 until table.rowCount).map { table.getValueAt(it, countColumn) as Int }
            check(before == before.sortedDescending() && before.distinct().size >= 2)
            clickHeading(table, "次数")
            val after = (0 until table.rowCount).map { table.getValueAt(it, countColumn) as Int }
            check(after == after.sorted()) { "次数列没有按数字升序排列" }
            clickHeading(table, "单词")
            val nameOrder = table.rowSorter.sortKeys.first()
            check(
                nameOrder.column == table.columnIndex("单词") &&
                    nameOrder.sortOrder == SortOrder.ASCENDING
            )
            check(hasSortIndicator(table, "单词"))
        }
        activateWord("subtle", "Resilience.java:")
        await("排序后依据视图行定位真实单词") { edt { flashOffset(editor, "subtle") } }
        awaitWordDetails("subtle")
        snap("after-sorted-detail-light", includeCard = false)
        click("leximeet-detail-close")
        step("点击表头可切换数字与单词排序，排序后点击仍定位正确词条")

        searchWords("WANDER RESILIENCE.JAVA")
        filterBy("leximeet-status-filter", "未学习")
        filterBy("leximeet-tag-filter", "已收藏")
        await("组合搜索、学习状态和标签") {
            edt {
                val table = wordTable()
                true.takeIf {
                    table.rowCount > 0 &&
                        (0 until table.rowCount).all {
                            table.cell(it, "单词") == "wander" &&
                                table.cell(it, "学习状态") == "未学习" &&
                                table.cell(it, "标签") == "学习目标 · 已收藏"
                        }
                }
            }
        }
        snap("after-filtered-light", includeCard = false)
        filterBy("leximeet-status-filter", "学习中")
        check(
            edt {
                wordTable().rowCount == 0 && label("leximeet-word-count").text.startsWith("0 / ")
            }
        )
        click("leximeet-reset-filters")
        edt {
            val table = wordTable()
            check(
                table.rowCount > 4 &&
                    table.rowSorter.sortKeys.first().sortOrder == SortOrder.DESCENDING
            )
            check(table.rowSorter.sortKeys.first().column == table.columnIndex("次数"))
            check(!button("leximeet-reset-filters").isEnabled)
        }
        step("按单词和出处组合搜索，状态与真实收藏标签可叠加筛选，重置恢复次数排序")
    }

    private fun clickHeading(table: JTable, title: String) {
        val header = table.tableHeader
        val bounds = header.getHeaderRect(table.columnIndex(title))
        header.dispatchEvent(
            MouseEvent(
                header,
                MouseEvent.MOUSE_CLICKED,
                System.currentTimeMillis(),
                0,
                bounds.x + bounds.width / 2,
                bounds.y + bounds.height / 2,
                1,
                false,
                MouseEvent.BUTTON1,
            )
        )
    }

    private fun hasSortIndicator(table: JTable, title: String): Boolean {
        val index = table.columnIndex(title)
        val header = table.tableHeader
        val label =
            header.defaultRenderer.getTableCellRendererComponent(
                table,
                title,
                false,
                false,
                -1,
                index,
            ) as? JLabel ?: return false
        return label.icon != null && label.preferredSize.width <= header.getHeaderRect(index).width
    }

    private fun filterBy(name: String, option: String) = edt {
        val control = component(name) as? JComboBox<*> ?: error("缺少筛选 $name")
        control.selectedItem = option
        check(control.selectedItem == option)
    }

    /** 以实际表格、渲染器和编辑器验证词表与持续高亮可以独立使用。 */
    private fun verifyWordList(editor: Editor, tree: JTree) {
        await("源码词及重复位置出现在词表") {
            edt {
                val row = wordRow("wander", "Resilience.java:")
                true.takeIf { row >= 0 && (wordTable().cell(row, "次数") as Int) >= 2 }
            }
        }
        edt {
            check(wordTable().getColumnName(0) == "单词")
            check(wordRow("lexiunmatchedsentinel") < 0)
        }
        snap("after-words-light", includeCard = false)
        searchWords("WANDER")
        await("关键词筛选并忽略大小写") { edt { wordRow("wander", "Resilience.java:").takeIf { it >= 0 } } }
        step("词表按词展示源码位置和命中次数，搜索不区分大小写")
        edt { highlightBox().doClick() }
        await("停止持续高亮后词表仍有结果") {
            edt {
                true.takeIf {
                    !highlightBox().isSelected &&
                        ranges(editor, "wander").isEmpty() &&
                        treeMark(tree, "wander_notes.txt", "wander") == null &&
                        wordRow("wander", "Resilience.java:") >= 0
                }
            }
        }
        step("关闭持续高亮移除项目树和编辑器下划线，词表仍然可用")

        activateWord("wander", "Resilience.java:")
        val first = await("从词表跳到源码并高亮") { edt { flashOffset(editor, "wander") } }
        awaitWordDetails("wander")
        snap("after-word-jump-light")
        activateWord("wander", "Resilience.java:")
        await("再次点击跳到同词下一个偏移") { edt { flashOffset(editor, "wander")?.takeIf { it != first } } }
        val source = edt {
            val line = editor.document.getLineNumber(first)
            val column = first - editor.document.getLineStartOffset(line)
            "Resilience.java:${line + 1}:${column + 1}"
        }
        activateSource(source)
        await("从右侧出处精确返回指定代码列") { edt { flashOffset(editor, "wander")?.takeIf { it == first } } }
        snap("after-word-source-light", includeCard = false)
        step("单击词表在右侧读取桌面真实词卡，点击卡内出处可精准定位")
        await("定位高亮到期，持续高亮依旧关闭") {
            edt {
                true.takeIf {
                    flashOffset(editor, "wander") == null &&
                        ranges(editor, "wander").isEmpty() &&
                        !highlightBox().isSelected
                }
            }
        }
        step("单击词表准确定位并高亮，重复点击跳到下一处，短时高亮自动清除")
        click("leximeet-detail-close")
        check(edt { component("leximeet-word-detail") == null && wordRow("wander") >= 0 })

        searchWords("resilience")
        val attempts = edt {
            val row = wordRow("resilience")
            check(row >= 0) { "没有用于定位项目树的 resilience 词行" }
            wordTable().cell(row, "次数") as Int
        }
        for (index in 0 until attempts) {
            activateWord("resilience")
            if (edt { label("leximeet-jump-result").text.startsWith("已定位 · 项目树") }) break
        }
        await("词表定位选中项目树节点并短暂标记") {
            edt {
                val row = tree.selectionRows?.singleOrNull() ?: return@edt null
                val hit = textOf(renderer(tree, row))
                true.takeIf {
                    label("leximeet-jump-result").text.startsWith("已定位 · 项目树") &&
                        hit.contains("resilience", ignoreCase = true) &&
                        treeMark(tree, hit, "resilience", exact = true)?.first ==
                            tree.getRowBounds(row)
                }
            }
        }
        awaitWordDetails("resilience")
        step("词表定位项目树时选中目标节点，仅短暂标记该节点的命中词")
        searchWords("")
        saveHighlightSetting(expected = false, value = true)
        await("应用设置同步恢复工具窗和高亮") {
            edt {
                true.takeIf {
                    highlightBox().isSelected &&
                        ranges(editor, "wander").isNotEmpty() &&
                        treeMark(tree, "wander_notes.txt", "wander") != null
                }
            }
        }
        step("设置页读取工具窗偏好，保存后同步恢复持续高亮")
    }

    private fun verifyArchiveJump(editor: Editor, word: String) {
        searchWords(word)
        activateWord(word, "WanderResilience.class:", enter = true)
        val first = await("Enter 定位反编译文档中的精确命中") { edt { flashOffset(editor, word) } }
        check(edt { !editor.document.isWritable })
        awaitWordDetails(word)
        snap("after-archive-jump-dark", includeCard = false)
        step("词表的 Enter 可准确跳转到活动 JAR 的只读代码")

        // 跨文件后词表会把新活动编辑器排在前面。确认顺序变化后仍能走遍尚未访问的位置。
        val editors = FileEditorManager.getInstance(project)
        val originalFile = requireNotNull(editor.virtualFile)
        val positions = mutableSetOf(originalFile to first)
        val count = edt { wordTable().cell(wordRow(word), "次数") as Int }
        var reachedTree = false
        for (index in 1 until count) {
            activateWord(word)
            if (edt { label("leximeet-jump-result").text.startsWith("已定位 · 项目树") }) {
                reachedTree = true
                break
            }
            val target =
                await("继续定位到另一条可见的代码命中") {
                    edt {
                        val active = editors.selectedTextEditor ?: return@edt null
                        val file = active.virtualFile ?: return@edt null
                        flashOffset(active, word)?.let { file to it }
                    }
                }
            check(positions.add(target)) { "活动编辑器改变后重复定位了同一处" }
            await("词表将新活动编辑器排到词条前面") {
                edt { wordRow(word, "${target.first.name}:").takeIf { it >= 0 } }
            }
        }
        check(reachedTree && positions.any { it.first != originalFile }) { "同一个词应能遍历多个编辑器并继续定位项目树" }
        open(originalFile)
        await("返回反编译文档并刷新词表") {
            edt {
                true.takeIf {
                    editors.selectedTextEditor === editor &&
                        wordRow(word, "${originalFile.name}:") >= 0 &&
                        highlightBox().isSelected
                }
            }
        }
        searchWords("")
        step("活动编辑器切换并更新词表顺序后，同词仍逐处跳转到其他源码和项目树")
    }

    private fun wordTable() = (component("leximeet-word-table") as? JTable) ?: error("缺少遇见词表")

    private fun JTable.columnIndex(title: String): Int =
        (0 until columnCount).first { getColumnName(it) == title }

    private fun JTable.cell(row: Int, column: String): Any = getValueAt(row, columnIndex(column))

    private fun wordRow(word: String, location: String = ""): Int {
        val table = wordTable()
        return (0 until table.rowCount).firstOrNull { row ->
            table.getValueAt(row, 0) == word &&
                table.cell(row, "首处位置").toString().startsWith(location)
        } ?: -1
    }

    private fun searchWords(query: String) = edt {
        val input = component("leximeet-word-search-input") as? JTextComponent ?: error("未发现词表搜索框")
        input.text = query
    }

    private fun activateWord(word: String, location: String = "", enter: Boolean = false) {
        await("词表中的 $word $location") { edt { wordRow(word, location).takeIf { it >= 0 } } }
        edt {
            val table = wordTable()
            val row = wordRow(word, location)
            check(row >= 0)
            if (enter) {
                table.setRowSelectionInterval(row, row)
                val key = KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, 0)
                val actionName =
                    table.getInputMap(JComponent.WHEN_ANCESTOR_OF_FOCUSED_COMPONENT).get(key)
                val action = table.actionMap.get(actionName) ?: error("词表 Enter 未绑定定位操作")
                action.actionPerformed(ActionEvent(table, ActionEvent.ACTION_PERFORMED, "Enter"))
            } else {
                val cell = table.getCellRect(row, 0, false)
                table.dispatchEvent(
                    MouseEvent(
                        table,
                        MouseEvent.MOUSE_CLICKED,
                        System.currentTimeMillis(),
                        0,
                        cell.x + 5,
                        cell.y + cell.height / 2,
                        1,
                        false,
                        MouseEvent.BUTTON1,
                    )
                )
            }
        }
    }

    private fun highlightBox() =
        component("leximeet-highlight") as? JCheckBox ?: error("缺少工具窗持续高亮开关")

    private fun awaitWordDetails(word: String) {
        await("右侧出现 $word 的真实释义和音标") {
            edt {
                val detail = component("leximeet-word-detail") ?: return@edt null
                val definition =
                    (component("leximeet-detail-definition") as? JTextComponent)?.text.orEmpty()
                val meta = (component("leximeet-detail-meta") as? JLabel)?.text.orEmpty()
                val scroll = component("leximeet-detail-scroll") as? JScrollPane
                val table = wordTable()
                true.takeIf {
                    label("leximeet-detail-word").text.equals(word, true) &&
                        definition.any { it in '\u4e00'..'\u9fff' } &&
                        definition != "暂无桌面释义" &&
                        !definition.startsWith("正在读取") &&
                        !definition.startsWith("词卡暂不可用") &&
                        meta.contains("·") &&
                        meta.contains("未学习") &&
                        scroll?.viewport?.viewPosition?.y == 0 &&
                        detail.locationOnScreen.x >= table.locationOnScreen.x + table.width
                }
            }
        }
    }

    /** 用出处列表真实渲染的「文件:行:列」选择位置，不读取生产插件内部状态。 */
    @Suppress("UNCHECKED_CAST")
    private fun activateSource(caption: String) = edt {
        val list = component("leximeet-detail-sources") as? JList<Any> ?: error("未显示项目出处")
        val row =
            (0 until list.model.size).firstOrNull { index ->
                val cell =
                    list.cellRenderer.getListCellRendererComponent(
                        list,
                        list.model.getElementAt(index),
                        index,
                        false,
                        false,
                    ) as? JLabel
                cell?.text == caption
            } ?: error("出处缺少 $caption")
        list.ensureIndexIsVisible(row)
        val bounds = list.getCellBounds(row, row)
        list.dispatchEvent(
            MouseEvent(
                list,
                MouseEvent.MOUSE_CLICKED,
                System.currentTimeMillis(),
                0,
                bounds.x + 8,
                bounds.y + bounds.height / 2,
                1,
                false,
                MouseEvent.BUTTON1,
            )
        )
    }

    private fun flashOffset(editor: Editor, word: String): Int? =
        editor.markupModel.allHighlighters
            .firstOrNull { mark ->
                val color = mark.getTextAttributes(editor.colorsScheme)?.backgroundColor
                mark.isValid &&
                    color?.let { it.rgb and 0xffffff in setOf(0xC7EDDF, 0x28574D) } == true &&
                    mark.startOffset == editor.caretModel.offset &&
                    mark.endOffset <= editor.document.textLength &&
                    editor.document.charsSequence
                        .subSequence(mark.startOffset, mark.endOffset)
                        .toString()
                        .equals(word, ignoreCase = true)
            }
            ?.startOffset

    /** 设置对话框是模态窗口，由测试进程内部事件打开、保存和绘制。 */
    private fun saveHighlightSetting(expected: Boolean, value: Boolean, snapshot: String? = null) {
        await("设置齿轮可用") { edt { button("leximeet-settings").takeIf { it.isEnabled } } }
        ApplicationManager.getApplication().invokeLater { button("leximeet-settings").doClick() }
        val setting =
            await("IDEA 原生词遇设置") {
                edt(ModalityState.any()) { component("leximeet-setting-highlight") as? JCheckBox }
            }
        val image =
            edt(ModalityState.any()) {
                check(setting.isSelected == expected) { "设置没有读回持续高亮偏好" }
                val window = SwingUtilities.getWindowAncestor(setting) ?: error("缺少设置对话框")
                val bitmap = snapshot?.let { printWindow(window) }
                if (setting.isSelected != value) setting.doClick()
                val ok =
                    walk(window).filterIsInstance<JButton>().firstOrNull {
                        it.text == "OK" && it.isShowing
                    } ?: error("设置缺少可见的 OK 按钮")
                ok.doClick()
                bitmap
            }
        if (image != null) ImageIO.write(image, "png", output.resolve("$snapshot.png").toFile())
        await("关闭设置对话框") { edt(ModalityState.any()) { true.takeIf { !setting.isShowing } } }
    }

    private fun expandTree(tree: JTree) {
        await("项目树展开样例目录") {
            edt {
                for (row in 0 until tree.rowCount.coerceAtMost(40)) {
                    if (!tree.isExpanded(row)) tree.expandRow(row)
                }
                val names = (0 until tree.rowCount).map { textOf(renderer(tree, it)) }
                true.takeIf {
                    listOf(
                            "wander_notes.txt",
                            "resilience-tool.jar",
                            "subtle.txt",
                            "WanderResilience",
                        )
                        .all { name -> names.any { it.contains(name) } }
                }
            }
        }
    }

    private fun renderer(tree: JTree, row: Int): SimpleColoredComponent? {
        val path = tree.getPathForRow(row) ?: return null
        return tree.cellRenderer.getTreeCellRendererComponent(
            tree,
            path.lastPathComponent,
            tree.isRowSelected(row),
            tree.isExpanded(row),
            tree.model.isLeaf(path.lastPathComponent),
            row,
            false,
        ) as? SimpleColoredComponent
    }

    private fun textOf(renderer: SimpleColoredComponent?): String {
        val fragments = renderer?.iterator() ?: return ""
        return fragments.asSequence().joinToString("")
    }

    private fun treeMark(
        tree: JTree,
        filename: String,
        word: String,
        exact: Boolean = false,
    ): Pair<Rectangle, Int>? {
        for (row in 0 until tree.rowCount) {
            val cell = renderer(tree, row) ?: continue
            val text = textOf(cell)
            if (
                if (exact) text != filename && !text.startsWith("$filename ")
                else !text.contains(filename)
            )
                continue
            val spans = cell.iterator()
            var index = 0
            while (spans.hasNext()) {
                val part = spans.next()
                if (
                    part == word &&
                        spans.textAttributes.style and SimpleTextAttributes.STYLE_UNDERLINE != 0
                ) {
                    return (tree.getRowBounds(row) ?: break) to index
                }
                index++
            }
        }
        return null
    }

    private fun hoverTree(tree: JTree, name: String, word: String) = edt {
        val (bounds, fragment) = treeMark(tree, name, word, exact = true) ?: error("项目树词未标记")
        val row = tree.getRowForLocation(bounds.x + 1, bounds.y + 1)
        val cell = renderer(tree, row) ?: error("项目树渲染器不可用")
        val x =
            (0 until bounds.width).firstOrNull { cell.findFragmentAt(it) == fragment }
                ?: error("项目树文字片段没有可悬浮的像素")
        check(tree.mouseMotionListeners.any { it.javaClass.name.contains("ProjectEncounter") }) {
            "实际项目树没有词遇鼠标监听器"
        }
        move(tree, bounds.x + x + 2, bounds.y + bounds.height / 2)
    }

    private fun hoverEditor(editor: Editor, word: String) = edt {
        val area = editor.scrollingModel.visibleArea
        val p =
            ranges(editor, word)
                .asSequence()
                .map { editor.offsetToXY(it.first + 1) }
                .firstOrNull(area::contains) ?: error("悬浮词不在编辑器可见区域")
        move(editor.contentComponent, p.x + 1, p.y + editor.lineHeight / 2)
    }

    private fun move(component: Component, x: Int, y: Int) {
        component.dispatchEvent(
            MouseEvent(
                component,
                MouseEvent.MOUSE_MOVED,
                System.currentTimeMillis(),
                0,
                x,
                y,
                0,
                false,
            )
        )
    }

    private fun leaveHover(component: Component) = edt { move(component, 1, 1) }

    private fun ranges(editor: Editor, text: String) =
        editor.markupModel.allHighlighters
            .filter { mark ->
                mark.isValid &&
                    mark.getTextAttributes(editor.colorsScheme)?.effectColor?.let {
                        it.red in listOf(0x17, 0x73, 0x28, 0x8A, 0x68, 0xC1)
                    } == true &&
                    mark.startOffset < mark.endOffset &&
                    mark.endOffset <= editor.document.textLength &&
                    editor.document.charsSequence
                        .subSequence(mark.startOffset, mark.endOffset)
                        .toString() == text
            }
            .map { it.startOffset until it.endOffset }

    private fun awaitMark(editor: Editor, word: String) {
        await("编辑器标记 $word") { edt { ranges(editor, word).firstOrNull() } }
    }

    private fun assertUnchanged(editor: Editor, text: String, stamp: Long, verifyCursor: Boolean) =
        edt {
            check(editor.document.text == text && editor.document.modificationStamp == stamp) {
                "编辑器内容被修改"
            }
            if (verifyCursor) {
                check(
                    editor.caretModel.offset == 2 &&
                        editor.selectionModel.selectionStart == 0 &&
                        editor.selectionModel.selectionEnd == 3
                ) {
                    "插件改变了编辑器光标或选区"
                }
            }
        }

    private fun awaitCard(word: String, movePointer: () -> Unit) {
        await("悬浮词卡 $word") {
            // 切换文件或主题期间可能重建标记，在真实标记上续发合成移动事件。
            movePointer()
            edt {
                val title = component("leximeet-card-word") as? JLabel
                cardText().takeIf {
                    title?.text.equals(word, true) &&
                        it.any { c -> c in '\u4e00'..'\u9fff' } &&
                        !it.contains("词卡暂不可用") &&
                        !it.contains("暂无桌面释义") &&
                        !it.contains("正在读取")
                }
            }
        }
    }

    private fun cardText() =
        (component("leximeet-card-definition") as? JTextComponent)?.text.orEmpty()

    private fun find(name: String): VirtualFile =
        LocalFileSystem.getInstance().refreshAndFindFileByNioFile(fixture.resolve(name))
            ?: error("找不到测试文件 $name")

    private fun open(file: VirtualFile): Editor {
        edt { FileEditorManager.getInstance(project).openFile(file, false) }
        return await("打开 " + file.name) { edt { visibleEditor(file) } }
    }

    private fun visibleEditor(file: VirtualFile): Editor? =
        FileEditorManager.getInstance(project).selectedTextEditor?.takeIf {
            it.virtualFile == file && it.contentComponent.isShowing
        }

    private fun waitState(text: String) {
        await("连接状态 $text", Duration.ofSeconds(70)) {
            edt { label("leximeet-link-state").text.takeIf { it.startsWith(text) } }
        }
    }

    private fun click(name: String) {
        await("按钮 $name 可操作") { edt { button(name).takeIf { it.isShowing && it.isEnabled } } }
        edt { button(name).doClick() }
    }

    private fun button(name: String) = (component(name) as? JButton) ?: error("缺少按钮 $name")

    private fun refreshAvailable(): Boolean =
        (component("leximeet-refresh") as? JButton)?.isEnabled == true

    private fun label(name: String) = (component(name) as? JLabel) ?: error("缺少标签 $name")

    private fun component(name: String): Component? =
        Window.getWindows()
            .asSequence()
            .filter { it.isShowing }
            .flatMap(::walk)
            .firstOrNull { it.name == name && it.isShowing }

    private fun walk(root: Component): Sequence<Component> =
        sequenceOf(root) + (root as? Container)?.components.orEmpty().asSequence().flatMap(::walk)

    private fun printWindow(window: Window, includeCard: Boolean = false): BufferedImage {
        val bitmap = BufferedImage(window.width, window.height, BufferedImage.TYPE_INT_RGB)
        val graphics = bitmap.createGraphics()
        try {
            window.printAll(graphics)
            val card = if (includeCard) component("leximeet-word-card") else null
            val popup = card?.let(SwingUtilities::getWindowAncestor)
            if (popup != null && popup !== window && popup.isShowing) {
                val pos = popup.locationOnScreen
                val origin = window.locationOnScreen
                graphics.translate(pos.x - origin.x, pos.y - origin.y)
                popup.printAll(graphics)
            }
        } finally {
            graphics.dispose()
        }
        return bitmap
    }

    private fun snap(name: String, includeCard: Boolean = true) {
        val image = edt {
            val frame = WindowManager.getInstance().getFrame(project) ?: error("没有截图窗口")
            printWindow(frame, includeCard = includeCard)
        }
        ImageIO.write(image, "png", output.resolve("$name.png").toFile())
    }

    private fun <T> await(
        description: String,
        timeout: Duration = Duration.ofSeconds(35),
        value: () -> T?,
    ): T {
        val deadline = Instant.now().plus(timeout)
        var failure: Throwable? = null
        while (Instant.now().isBefore(deadline)) {
            try {
                value()?.let {
                    return it
                }
            } catch (problem: Exception) {
                failure = problem
            }
            Thread.sleep(130)
        }
        throw AssertionError("等待超时：$description", failure)
    }

    private fun <T> edt(modality: ModalityState? = null, action: () -> T): T {
        val task = FutureTask(action)
        val application = ApplicationManager.getApplication()
        if (modality == null) application.invokeAndWait(task)
        else application.invokeAndWait(task, modality)
        return task.get()
    }

    private fun mark(name: String) {
        Files.writeString(output.resolve(name), "ok\n")
    }

    private fun step(description: String) {
        events.add("PASS $description")
        Files.writeString(output.resolve("steps.txt"), events.joinToString("\n", postfix = "\n"))
    }

    fun recordFailure(failure: Throwable) {
        Files.writeString(output.resolve("failure.txt"), failure.stackTraceToString())
        runCatching { snap("failure") }
    }
}
