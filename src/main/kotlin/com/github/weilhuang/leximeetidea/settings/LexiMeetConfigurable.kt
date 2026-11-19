package com.github.weilhuang.leximeetidea.settings

import com.intellij.ide.util.PropertiesComponent
import com.intellij.openapi.options.BoundConfigurable
import com.intellij.ui.dsl.builder.bindSelected
import com.intellij.ui.dsl.builder.panel

private const val HIGHLIGHT = "com.github.weilhuang.leximeetidea.highlight"
private const val DEFAULT_HIGHLIGHT = false

/** 一个 IDE 安装共享阅读偏好。工具窗与设置页使用同一保存位置。 */
internal object EncounterPreferences {
    var highlight: Boolean
        get() = PropertiesComponent.getInstance().getBoolean(HIGHLIGHT, DEFAULT_HIGHLIGHT)
        set(value) = PropertiesComponent.getInstance().setValue(HIGHLIGHT, value, DEFAULT_HIGHLIGHT)
}

internal class LexiMeetConfigurable : BoundConfigurable("词遇") {
    override fun createPanel() = panel {
        group("遇见") {
            row {
                checkBox("持续高亮命中的单词")
                    .bindSelected(
                        { EncounterPreferences.highlight },
                        { EncounterPreferences.highlight = it },
                    )
                    .comment("适用于编辑器和项目树。关闭后仍可从底部词表定位，目标会短暂高亮。")
                    .applyToComponent { name = "leximeet-setting-highlight" }
            }
        }
        group("桌面端连接") { row { label("在底部「词遇」工具窗连接桌面端并确认只读权限。") } }
    }
}
