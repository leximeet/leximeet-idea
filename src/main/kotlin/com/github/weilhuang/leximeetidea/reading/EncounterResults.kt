package com.github.weilhuang.leximeetidea.reading

import com.google.gson.JsonObject
import com.intellij.ide.projectView.ProjectViewNode
import com.intellij.ide.util.treeView.PresentableNodeDescriptor
import com.intellij.openapi.editor.Document
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.vfs.VirtualFile
import java.util.Locale
import javax.swing.JTree
import javax.swing.tree.DefaultMutableTreeNode
import javax.swing.tree.TreePath

/** 用工作区返回的词条身份区分同形词，同一组中的位置保留扫描顺序。 */
internal data class MatchKey(val word: String, val refs: Set<JsonObject>)

internal enum class WordTag(val label: String) {
    TARGET("学习目标"),
    COLLECTED("已收藏"),
}

internal data class MatchedWord<T>(
    val key: MatchKey,
    val locations: List<T>,
    val statuses: Set<LearningStatus>,
    val tags: Set<WordTag>,
) {
    val statusText = statuses.sorted().joinToString(" · ") { it.label }
    val tagText = tags.sorted().joinToString(" · ") { it.label }
}

/** 关键字可跨单词、状态、标签和任意出处组合，筛选只处理本轮匹配结果。 */
internal class WordFilters(
    query: String,
    private val status: LearningStatus?,
    private val tag: WordTag?,
) {
    private val terms = query.trim().split(Regex("\\s+")).filter(String::isNotEmpty)

    fun <T> includes(word: MatchedWord<T>, caption: (T) -> String): Boolean =
        (status == null || status in word.statuses) &&
            (tag == null || tag in word.tags) &&
            terms.all { term ->
                word.key.word.contains(term, ignoreCase = true) ||
                    word.statusText.contains(term, ignoreCase = true) ||
                    word.tagText.contains(term, ignoreCase = true) ||
                    word.locations.any { caption(it).contains(term, ignoreCase = true) }
            }
}

internal fun <T> groupMatches(
    tokens: Sequence<Pair<String, T>>,
    projection: ReadingProjection,
): List<MatchedWord<T>> =
    tokens
        .mapNotNull { (token, location) ->
            val choices = projection.words[token].orEmpty()
            if (choices.isEmpty()) null
            else
                MatchKey(token.lowercase(Locale.ROOT), choices.map { it.ref }.toSet()) to
                    (location to choices)
        }
        .groupBy({ it.first }, { it.second })
        .map { (key, hits) ->
            val choices = hits.flatMap { it.second }
            val tags = buildSet {
                if (choices.any { it.inTarget }) add(WordTag.TARGET)
                if (choices.any { it.collected }) add(WordTag.COLLECTED)
            }
            // 一行可能对应多个合法词条。保留这些词条的状态与标签供用户筛选。
            MatchedWord(
                key,
                hits.map { it.first }.distinct(),
                choices.mapNotNull { LearningStatus.from(it.status) }.toSet(),
                tags,
            )
        }
        .sortedWith(
            compareByDescending<MatchedWord<T>> { it.locations.size }.thenBy { it.key.word }
        )

internal sealed interface MatchLocation {
    val span: WordSpan
    val caption: String
    val sourceLabel: String
    val position: MatchPosition

    data class Code(
        val editor: Editor,
        val stamp: Long,
        val line: Int,
        val column: Int,
        override val span: WordSpan,
    ) : MatchLocation {
        val file: VirtualFile? = editor.virtualFile
        override val caption = "${file?.name ?: "编辑器"}:${line + 1}"
        override val sourceLabel = "$caption:${column + 1}"
        override val position = MatchPosition.Code(editor.document, stamp, span.start)

        fun matchesDocument(target: Editor = editor): Boolean {
            if (target.isDisposed || target.document !== position.document) return false
            val doc = target.document
            return doc.modificationStamp == stamp &&
                span.end <= doc.textLength &&
                doc.charsSequence.subSequence(span.start, span.end).toString() == span.text
        }
    }

    data class Tree(val tree: JTree, val row: ProjectRow, override val span: WordSpan) :
        MatchLocation {
        override val caption = "项目树 · ${row.label}"
        override val sourceLabel = caption
        override val position = MatchPosition.Tree(tree, row.lineage, row.label, span.start)
    }
}

/** 调整活动编辑器、拆分视图或展开目录后，仍用同一个身份识别本轮已定位的位置。 */
internal sealed interface MatchPosition {
    data class Code(val document: Document, val stamp: Long, val offset: Int) : MatchPosition

    data class Tree(
        val tree: JTree,
        val lineage: List<String>,
        val label: String,
        val offset: Int,
    ) : MatchPosition
}

/** 行号只是定位提示。同名节点还要核对项目树路径和 VirtualFile。 */
internal data class ProjectRow(val index: Int, val label: String, val lineage: List<String>) {
    fun sameNode(other: ProjectRow) = label == other.label && lineage == other.lineage
}

internal fun TreePath.projectRow(index: Int): ProjectRow? {
    val leaf = descriptor(lastPathComponent) as? PresentableNodeDescriptor<*> ?: return null
    val label = presentationText(leaf.presentation)
    if (label.isBlank()) return null
    val lineage =
        path.map { item ->
            when (val node = descriptor(item)) {
                is ProjectViewNode<*> ->
                    node.virtualFile?.url ?: "${node.javaClass.name}:${node.name}"
                is PresentableNodeDescriptor<*> -> "${node.javaClass.name}:${node.name}"
                else -> node.toString()
            }
        }
    return ProjectRow(index, label, lineage)
}

internal fun descriptor(cell: Any): Any = (cell as? DefaultMutableTreeNode)?.userObject ?: cell
