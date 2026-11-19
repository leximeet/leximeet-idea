package com.github.weilhuang.leximeetidea.reading

import com.intellij.ide.projectView.ProjectView
import com.intellij.ide.projectView.ProjectViewNode
import com.intellij.openapi.editor.ScrollType
import com.intellij.openapi.editor.markup.HighlighterLayer
import com.intellij.openapi.editor.markup.HighlighterTargetArea
import com.intellij.openapi.editor.markup.RangeHighlighter
import com.intellij.openapi.editor.markup.TextAttributes
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindowManager
import java.awt.Font
import javax.swing.JTree
import javax.swing.Timer

/** 用户主动定位产生独立的短时标记，持续高亮开关不影响它。 */
internal class EncounterNavigation(private val project: Project) {
    private var selectedWord: MatchKey? = null
    private val visited = mutableSetOf<MatchPosition>()
    private var editorFlash: Pair<MatchLocation.Code, RangeHighlighter>? = null
    @Volatile private var treeFlash: TreeFlash? = null
    private val expiry = Timer(2000) { clearFlash() }.apply { isRepeats = false }

    private data class TreeFlash(
        val node: ProjectViewNode<*>,
        val label: String,
        val span: WordSpan,
    )

    fun locate(word: MatchedWord<MatchLocation>, tree: JTree?, target: MatchPosition?): String? {
        if (selectedWord != word.key) {
            selectedWord = word.key
            visited.clear()
        }
        visited.retainAll(word.locations.mapTo(HashSet()) { it.position })
        // 跳转会切换活动编辑器并改变词表排序，一轮内始终优先使用尚未去过的位置。
        val locations =
            if (target == null) {
                val (pending, seen) = word.locations.partition { it.position !in visited }
                pending + seen
            } else word.locations.filter { it.position == target }
        for (location in locations) {
            val located =
                when (location) {
                    is MatchLocation.Code -> openCode(location)
                    is MatchLocation.Tree -> location.tree === tree && selectNode(location)
                }
            if (located) {
                if (target == null && location.position in visited) visited.clear()
                visited.add(location.position)
                expiry.restart()
                return location.caption
            }
        }
        return null
    }

    fun isFlashing(hit: MatchLocation.Code): Boolean = editorFlash?.first == hit

    fun treeSpan(node: ProjectViewNode<*>, label: String): WordSpan? {
        val flash = treeFlash ?: return null
        if (flash.label != label) return null
        val old = flash.node
        val sameNode =
            node === old ||
                (node.javaClass == old.javaClass &&
                    node.value != null &&
                    node.value == old.value &&
                    node.parent?.value == old.parent?.value)
        return flash.span.takeIf { sameNode }
    }

    private fun openCode(location: MatchLocation.Code): Boolean {
        if (!location.matchesDocument()) return false
        val file = location.file
        val editor =
            if (file == null) {
                location.editor.takeIf { it.contentComponent.isShowing }
            } else {
                if (!file.isValid) return false
                FileEditorManager.getInstance(project)
                    .openTextEditor(
                        OpenFileDescriptor(project, file, location.span.start).apply {
                            isUseCurrentWindow = true
                        },
                        false,
                    )
            } ?: return false
        if (!location.matchesDocument(editor)) return false
        editor.caretModel.moveToOffset(location.span.start)
        editor.selectionModel.removeSelection()
        editor.scrollingModel.scrollToCaret(ScrollType.CENTER)
        clearFlash()
        val mark =
            editor.markupModel.addRangeHighlighter(
                location.span.start,
                location.span.end,
                HighlighterLayer.SELECTION - 1,
                TextAttributes(null, EncounterColors.jump, null, null, Font.PLAIN),
                HighlighterTargetArea.EXACT_RANGE,
            )
        editorFlash = location.copy(editor = editor) to mark
        return true
    }

    private fun selectNode(location: MatchLocation.Tree): Boolean {
        val target = location.row
        val tree = location.tree
        val path =
            tree.getPathForRow(target.index)?.takeIf { it.projectRow(target.index) == target }
                ?: (maxOf(0, target.index - 160)..minOf(tree.rowCount - 1, target.index + 160))
                    .asSequence()
                    .mapNotNull { index ->
                        tree.getPathForRow(index)?.takeIf {
                            it.projectRow(index)?.sameNode(target) == true
                        }
                    }
                    .singleOrNull()
                ?: return false
        val node = descriptor(path.lastPathComponent) as? ProjectViewNode<*>
        clearFlash()
        ToolWindowManager.getInstance(project).getToolWindow("Project")?.show()
        tree.selectionPath = path
        tree.scrollPathToVisible(path)
        if (node != null) {
            treeFlash = TreeFlash(node, target.label, location.span)
            ProjectView.getInstance(project).refresh()
        }
        return true
    }

    fun clearEditedFlash() {
        if (editorFlash?.first?.matchesDocument() == false) clearFlash()
    }

    fun clear() {
        selectedWord = null
        visited.clear()
        clearFlash()
    }

    private fun clearFlash() {
        expiry.stop()
        editorFlash?.let { (location, mark) ->
            if (!location.editor.isDisposed && mark.isValid)
                location.editor.markupModel.removeHighlighter(mark)
        }
        editorFlash = null
        if (treeFlash != null) {
            treeFlash = null
            if (!project.isDisposed) ProjectView.getInstance(project).refresh()
        }
    }
}
