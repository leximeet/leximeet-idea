package com.github.weilhuang.leximeetidea.reading

import com.github.weilhuang.leximeetidea.connection.DesktopLinkService
import com.github.weilhuang.leximeetidea.connection.MAX_CANDIDATES
import com.github.weilhuang.leximeetidea.settings.EncounterPreferences
import com.intellij.ide.projectView.ProjectView
import com.intellij.ide.projectView.ProjectViewNode
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.editor.EditorKind
import com.intellij.openapi.editor.event.EditorMouseEvent
import com.intellij.openapi.editor.event.EditorMouseEventArea
import com.intellij.openapi.editor.event.EditorMouseListener
import com.intellij.openapi.editor.event.EditorMouseMotionListener
import com.intellij.openapi.editor.markup.EffectType
import com.intellij.openapi.editor.markup.HighlighterLayer
import com.intellij.openapi.editor.markup.HighlighterTargetArea
import com.intellij.openapi.editor.markup.RangeHighlighter
import com.intellij.openapi.editor.markup.TextAttributes
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.Project
import com.intellij.ui.SimpleColoredComponent
import com.intellij.ui.SimpleTextAttributes
import com.intellij.util.concurrency.AppExecutorUtil
import java.awt.Font
import java.awt.Point
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.time.Instant
import java.util.IdentityHashMap
import java.util.concurrent.CompletableFuture
import javax.swing.JTree
import javax.swing.Timer

@Service(Service.Level.PROJECT)
internal class ProjectEncounter(private val project: Project) : Disposable {
    private val connection = service<DesktopLinkService>().link
    private val hover = HoverCard(project, ::visibleProjection, connection::card)
    private val navigation = EncounterNavigation(project)
    private val editorMatches = IdentityHashMap<Editor, List<MatchLocation.Code>>()
    private val surfaces = IdentityHashMap<Editor, List<RangeHighlighter>>()
    private var words = emptyList<MatchedWord<MatchLocation>>()
    private var tree: JTree? = null
    private var work: CompletableFuture<*>? = null
    private var lastInput: Input? = null
    private var retryAt = Instant.EPOCH
    @Volatile private var projection: ReadingProjection? = null
    @Volatile private var generation = 0L
    @Volatile private var active = false

    @Volatile
    var highlightsEnabled = EncounterPreferences.highlight
        private set

    var matching = false
        private set

    var status = ""
        private set

    private val timer = Timer(320) { update() }

    private data class Slice(val editor: Editor, val stamp: Long, val start: Int, val end: Int)

    private data class Input(val slices: List<Slice>, val tree: JTree?, val rows: List<ProjectRow>)

    private val editorMouse =
        object : EditorMouseListener, EditorMouseMotionListener {
            override fun mouseMoved(event: EditorMouseEvent) {
                val editor = event.editor
                if (editor.project !== project) return
                val current = visibleProjection() ?: return hover.leave()
                if (event.area != EditorMouseEventArea.EDITING_AREA || !event.isOverText)
                    return hover.leave()
                val offset = event.offset
                val words = editorMatches[editor].orEmpty()
                val index =
                    words.binarySearch { mark ->
                        when {
                            offset < mark.span.start -> 1
                            offset >= mark.span.end -> -1
                            else -> 0
                        }
                    }
                val mark =
                    words.getOrNull(index)?.takeIf {
                        it.stamp == editor.document.modificationStamp &&
                            (highlightsEnabled || navigation.isFlashing(it))
                    } ?: return hover.leave()
                val point = editor.offsetToXY(mark.span.start)
                point.y += editor.lineHeight + 2
                hover.over(
                    editor.contentComponent,
                    mark,
                    mark.span.text,
                    point,
                    editor.lineHeight,
                    current,
                )
            }

            override fun mouseExited(event: EditorMouseEvent) {
                if (event.editor.project === project) hover.leave()
            }

            override fun mousePressed(event: EditorMouseEvent) {
                if (event.editor.project === project) hover.clear()
            }
        }

    private val treeMouse =
        object : MouseAdapter() {
            override fun mouseMoved(event: MouseEvent) {
                val source = tree ?: return
                val current = visibleProjection() ?: return hover.leave()
                val row = source.getRowForLocation(event.x, event.y)
                val path = source.getPathForRow(row) ?: return hover.leave()
                val bounds = source.getRowBounds(row) ?: return hover.leave()
                val renderer =
                    source.cellRenderer.getTreeCellRendererComponent(
                        source,
                        path.lastPathComponent,
                        source.isRowSelected(row),
                        source.isExpanded(row),
                        source.model.isLeaf(path.lastPathComponent),
                        row,
                        false,
                    ) as? SimpleColoredComponent ?: return hover.leave()
                val index = renderer.findFragmentAt(event.x - bounds.x)
                if (index < 0) return hover.leave()
                val fragments = renderer.iterator()
                repeat(index) { if (fragments.hasNext()) fragments.next() }
                val word = if (fragments.hasNext()) fragments.next() else return hover.leave()
                if (
                    fragments.textAttributes.style and SimpleTextAttributes.STYLE_UNDERLINE == 0 ||
                        current.choices(word).isEmpty()
                )
                    return hover.leave()
                hover.over(
                    source,
                    path to index,
                    word,
                    Point(event.x, bounds.y + bounds.height + 2),
                    bounds.height,
                    current,
                )
            }

            override fun mouseExited(event: MouseEvent) = hover.leave()

            override fun mousePressed(event: MouseEvent) = hover.clear()
        }

    init {
        val events = EditorFactory.getInstance().eventMulticaster
        events.addEditorMouseListener(editorMouse, this)
        events.addEditorMouseMotionListener(editorMouse, this)
    }

    /** 工具窗可见且获得只读授权时才维护项目快照。 */
    fun setActive(value: Boolean) {
        if (value == active) return
        if (!value) {
            stop()
        } else if (connection.view.readable) {
            active = true
            retryAt = Instant.EPOCH
            timer.start()
            update()
        }
    }

    /** 手动和自动刷新共享同一条异步任务，刷新时保留有效的上一轮结果。 */
    fun refresh() {
        if (!active || work != null) return
        retryAt = Instant.EPOCH
        update(force = true)
    }

    fun visibleProjection(): ReadingProjection? =
        projection?.takeIf { active && connection.view.allows(it) }

    fun matchedWords(): List<MatchedWord<MatchLocation>> =
        if (visibleProjection() == null) emptyList() else words

    fun setHighlight(value: Boolean) {
        EncounterPreferences.highlight = value
        syncHighlight()
    }

    fun locateWord(key: MatchKey, target: MatchPosition? = null): String? {
        val match = matchedWords().firstOrNull { it.key == key } ?: return null
        hover.clear()
        return navigation.locate(match, tree, target).also { if (it == null) lastInput = null }
    }

    fun card(choice: WordChoice, snapshot: ReadingProjection) = connection.card(choice, snapshot)

    fun treeFlash(node: ProjectViewNode<*>, label: String): WordSpan? =
        navigation.treeSpan(node, label)

    private fun syncHighlight() {
        val desired = EncounterPreferences.highlight
        if (desired == highlightsEnabled) return
        highlightsEnabled = desired
        hover.clear()
        drawHighlights(visibleProjection())
        if (active && !project.isDisposed) ProjectView.getInstance(project).refresh()
    }

    private fun update(force: Boolean = false) {
        if (!active || project.isDisposed) return
        if (!connection.view.readable) {
            stop()
            return
        }
        syncHighlight()
        navigation.clearEditedFlash()
        val projectView = ProjectView.getInstance(project)
        attachTree(projectView.currentProjectViewPane?.tree)
        val captured = readInput()
        val current = visibleProjection()
        val now = Instant.now()
        if (lastInput != captured) {
            hover.clear()
            editorMatches.keys
                .toList()
                .filter { editor ->
                    captured.slices.none { it.editor === editor } ||
                        editorMatches[editor]?.firstOrNull()?.stamp !=
                            editor.document.modificationStamp
                }
                .forEach { editor ->
                    editorMatches.remove(editor)
                    discardMarks(editor, surfaces.remove(editor).orEmpty())
                }
        }
        if (current == null && projection != null) clear()
        if (
            !force &&
                lastInput == captured &&
                current != null &&
                current.until.isAfter(now.plusSeconds(2))
        )
            return
        if (work != null || now.isBefore(retryAt)) return

        val snapshots =
            captured.slices.map { slice ->
                slice.editor.document.immutableCharSequence.subSequence(slice.start, slice.end)
            }
        val ticket = generation
        matching = true
        val next =
            CompletableFuture.supplyAsync(
                    {
                        val editorSpans =
                            snapshots.mapIndexed { index, text ->
                                CodeWords.spans(text, captured.slices[index].start)
                            }
                        val treeTokens =
                            captured.rows
                                .asSequence()
                                .flatMap { CodeWords.spans(it.label).asSequence() }
                                .map { it.text }
                        // 给树和当前编辑器都保留预算；窗口变化会产生下一份快照。
                        val tokens =
                            (treeTokens.distinct().take(160) +
                                    editorSpans.asSequence().flatten().map { it.text })
                                .distinct()
                                .take(MAX_CANDIDATES)
                                .toList()
                        editorSpans to tokens
                    },
                    AppExecutorUtil.getAppExecutorService(),
                )
                .thenCompose { (spans, tokens) ->
                    connection
                        .match(tokens) { active && generation == ticket }
                        .thenApply { spans to it }
                }
        work = next
        next.whenComplete { value, _ ->
            ApplicationManager.getApplication().invokeLater {
                if (work !== next || project.isDisposed) return@invokeLater
                work = null
                matching = false
                if (!active) return@invokeLater
                if (value == null) {
                    if (visibleProjection() == null) clear()
                    lastInput = null
                    retryAt = Instant.now().plusMillis(1000)
                    status = "刷新未完成，稍后重试"
                    return@invokeLater
                }
                val (spans, nextProjection) = value
                if (!connection.view.allows(nextProjection) || captured != readInput())
                    return@invokeLater
                apply(captured, spans, nextProjection)
            }
        }
    }

    private fun readInput(): Input {
        val active = FileEditorManager.getInstance(project).selectedTextEditor
        val editors =
            EditorFactory.getInstance()
                .allEditors
                .filter {
                    it.project === project &&
                        it.editorKind == EditorKind.MAIN_EDITOR &&
                        !it.isDisposed
                }
                .sortedBy { it !== active }
                .take(8)
        return Input(editors.map(::slice), tree, treeRows())
    }

    private fun slice(editor: Editor): Slice {
        val doc = editor.document
        val area = editor.scrollingModel.visibleArea
        if (doc.textLength == 0) return Slice(editor, doc.modificationStamp, 0, 0)
        val first =
            (editor.xyToLogicalPosition(Point(0, area.y)).line - 18).coerceIn(0, doc.lineCount - 1)
        val last =
            (editor.xyToLogicalPosition(Point(0, area.y + area.height)).line + 28).coerceIn(
                first,
                minOf(doc.lineCount - 1, first + 240),
            )
        val from = doc.getLineStartOffset(first)
        return Slice(
            editor,
            doc.modificationStamp,
            from,
            minOf(doc.getLineEndOffset(last), from + 36_000),
        )
    }

    private fun treeRows(): List<ProjectRow> {
        val current = tree ?: return emptyList()
        if (current.rowCount == 0) return emptyList()
        val area = current.visibleRect
        val start = (current.getClosestRowForLocation(0, area.y) - 12).coerceAtLeast(0)
        val end =
            (current.getClosestRowForLocation(0, area.y + area.height) + 14).coerceIn(
                start,
                minOf(current.rowCount - 1, start + 160),
            )
        return (start..end).mapNotNull { index -> current.getPathForRow(index)?.projectRow(index) }
    }

    private fun apply(input: Input, spans: List<List<WordSpan>>, next: ReadingProjection) {
        hover.clear()
        editorMatches.clear()
        projection = next
        lastInput = input
        for ((index, slice) in input.slices.withIndex()) {
            val editor = slice.editor
            val doc = editor.document
            if (editor.isDisposed || doc.modificationStamp != slice.stamp) continue
            editorMatches[editor] =
                spans[index]
                    .asSequence()
                    .filter { next.words[it.text]?.isNotEmpty() == true }
                    .take(2000)
                    .map { span ->
                        val line = doc.getLineNumber(span.start)
                        val column = span.start - doc.getLineStartOffset(line)
                        MatchLocation.Code(editor, slice.stamp, line, column, span)
                    }
                    .toList()
        }
        // 当前编辑器在前。拆分视图里的同一文档范围只计一次，树上的每一实际行单独计数。
        val code =
            input.slices
                .asSequence()
                .flatMap { editorMatches[it.editor].orEmpty().asSequence() }
                .distinctBy { it.position }
        val treeLocations =
            input.tree?.let { view ->
                input.rows.asSequence().flatMap { row ->
                    CodeWords.spans(row.label).asSequence().map { span ->
                        MatchLocation.Tree(view, row, span)
                    }
                }
            } ?: emptySequence()
        words = groupMatches((code + treeLocations).map { it.span.text to it }, next)
        status = ""
        drawHighlights(next)
        if (highlightsEnabled) ProjectView.getInstance(project).refresh()
    }

    private fun drawHighlights(current: ReadingProjection?) {
        removeMarks()
        if (!highlightsEnabled || current == null) return
        for ((editor, hits) in editorMatches) {
            if (editor.isDisposed) continue
            surfaces[editor] =
                hits.mapNotNull { hit ->
                    if (hit.stamp != editor.document.modificationStamp) return@mapNotNull null
                    val span = hit.span
                    val choices = current.choices(span.text)
                    if (choices.isEmpty()) return@mapNotNull null
                    editor.markupModel.addRangeHighlighter(
                        span.start,
                        span.end,
                        HighlighterLayer.ADDITIONAL_SYNTAX,
                        TextAttributes(
                            null,
                            null,
                            EncounterColors.color(choices),
                            EffectType.LINE_UNDERSCORE,
                            Font.PLAIN,
                        ),
                        HighlighterTargetArea.EXACT_RANGE,
                    )
                }
        }
    }

    private fun attachTree(next: JTree?) {
        if (next === tree) return
        tree?.removeMouseMotionListener(treeMouse)
        tree?.removeMouseListener(treeMouse)
        tree = next
        next?.addMouseMotionListener(treeMouse)
        next?.addMouseListener(treeMouse)
    }

    private fun discardMarks(editor: Editor, words: List<RangeHighlighter>) {
        if (!editor.isDisposed)
            for (word in words) {
                if (word.isValid) editor.markupModel.removeHighlighter(word)
            }
    }

    private fun removeMarks() {
        surfaces.forEach(::discardMarks)
        surfaces.clear()
    }

    private fun clear() {
        val hadProjection = projection != null
        projection = null
        lastInput = null
        hover.clear()
        removeMarks()
        words = emptyList()
        editorMatches.clear()
        navigation.clear()
        if (hadProjection && !project.isDisposed) ProjectView.getInstance(project).refresh()
    }

    private fun stop() {
        active = false
        generation++
        timer.stop()
        work?.cancel(false)
        work = null
        matching = false
        status = ""
        clear()
        attachTree(null)
    }

    override fun dispose() {
        val app = ApplicationManager.getApplication()
        if (app.isDispatchThread) stop() else app.invokeLater { stop() }
    }
}
