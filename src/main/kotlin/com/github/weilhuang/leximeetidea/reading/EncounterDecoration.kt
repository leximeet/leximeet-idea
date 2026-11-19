package com.github.weilhuang.leximeetidea.reading

import com.intellij.ide.projectView.PresentationData
import com.intellij.ide.projectView.ProjectViewNode
import com.intellij.ide.projectView.ProjectViewNodeDecorator
import com.intellij.ide.util.treeView.PresentableNodeDescriptor.ColoredFragment
import com.intellij.ui.JBColor
import com.intellij.ui.SimpleTextAttributes

internal object EncounterColors {
    private val target = JBColor(0x176A91, 0x73C8F3)
    private val saved = JBColor(0x28724F, 0x8AD5A8)
    private val both = JBColor(0x6850A0, 0xC1A7F2)
    val jump = JBColor(0xC7EDDF, 0x28574D)

    fun color(choices: List<WordChoice>): JBColor =
        when {
            choices.any { it.collected } && choices.any { it.inTarget } -> both
            choices.any { it.inTarget } -> target
            else -> saved
        }
}

internal fun presentationText(presentation: PresentationData): String {
    val fragments = presentation.coloredText
    return if (fragments.isEmpty()) presentation.presentableText.orEmpty()
    else fragments.joinToString("") { it.text }
}

/** 只装饰 Project View 的文字片段。目录、文件与归档条目仍由 IDEA 本来的树负责。 */
internal class EncounterNodeDecorator : ProjectViewNodeDecorator {
    override fun decorate(node: ProjectViewNode<*>, data: PresentationData) {
        val project = node.project ?: return
        val encounter = project.getServiceIfCreated(ProjectEncounter::class.java) ?: return
        val projection = encounter.visibleProjection() ?: return
        val label = presentationText(data)
        val flash = encounter.treeFlash(node, label)
        val showAll = encounter.highlightsEnabled
        if (!showAll && flash == null) return
        val ranges =
            CodeWords.spans(label).mapNotNull { span ->
                if (!showAll && span != flash) return@mapNotNull null
                projection
                    .choices(span.text)
                    .takeIf { it.isNotEmpty() }
                    ?.let { span to EncounterColors.color(it) }
            }
        if (ranges.isEmpty()) return
        val fragments =
            data.coloredText.toList().ifEmpty {
                listOf(ColoredFragment(label, SimpleTextAttributes.REGULAR_ATTRIBUTES))
            }
        data.clearText()
        var position = 0
        for (fragment in fragments) {
            val end = position + fragment.text.length
            var cursor = position
            for ((span, color) in ranges) {
                if (span.end <= position || span.start >= end) continue
                val start = maxOf(span.start, position)
                val stop = minOf(span.end, end)
                if (cursor < start) {
                    data.addText(
                        fragment.text.substring(cursor - position, start - position),
                        fragment.attributes,
                    )
                }
                val current = fragment.attributes
                data.addText(
                    fragment.text.substring(start - position, stop - position),
                    SimpleTextAttributes(
                        if (span == flash) EncounterColors.jump else current.bgColor,
                        color,
                        color,
                        current.style or SimpleTextAttributes.STYLE_UNDERLINE,
                    ),
                )
                cursor = stop
            }
            if (cursor < end)
                data.addText(fragment.text.substring(cursor - position), fragment.attributes)
            position = end
        }
    }
}
