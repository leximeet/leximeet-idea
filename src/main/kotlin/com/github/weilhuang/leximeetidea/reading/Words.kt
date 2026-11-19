package com.github.weilhuang.leximeetidea.reading

import com.google.gson.JsonObject
import java.time.Instant

internal data class WordSpan(val start: Int, val end: Int, val text: String)

internal object CodeWords {
    private val run = Regex("[A-Za-z0-9]+")
    private val boundary =
        Regex(
            "(?<=[a-z])(?=[A-Z])|(?<=[A-Z])(?=[A-Z][a-z])|(?<=[0-9])(?=[A-Za-z])|(?<=[A-Za-z])(?=[0-9])"
        )

    /** UTF-16 范围与 IDE 文档一致。按代码命名边界拆词，保留原拼写供桌面精确匹配。 */
    fun spans(text: CharSequence, base: Int = 0): List<WordSpan> = buildList {
        for (part in run.findAll(text)) {
            var start = base + part.range.first
            for (token in boundary.split(part.value)) {
                val end = start + token.length
                if (token.length in 2..120 && token.first().isLetter())
                    add(WordSpan(start, end, token))
                start = end
            }
        }
    }
}

/** 身份保留 Desktop 返回的 WordRef，界面不以拼写猜测词条。 */
internal class WordChoice(
    val ref: JsonObject,
    val headword: String,
    val inTarget: Boolean,
    val collected: Boolean,
    val status: String,
)

internal enum class LearningStatus(val code: String, val label: String) {
    NEW("new", "未学习"),
    LEARNING("learning", "学习中"),
    REVIEW("review", "待复习"),
    MASTERED("mastered", "已熟悉");

    companion object {
        fun from(code: String) = entries.firstOrNull { it.code == code }
    }
}

internal data class WordCard(
    val headword: String,
    val phonetic: String,
    val definitions: List<String>,
    val note: String?,
    val status: String,
    val forms: List<String>,
    val examples: List<String>,
) {
    val statusLabel: String
        get() = LearningStatus.from(status)?.label.orEmpty()
}

internal class ReadingProjection(
    val ownerKey: String,
    val revision: String,
    val until: Instant,
    val words: Map<String, List<WordChoice>>,
) {
    fun choices(token: String, now: Instant = Instant.now()) =
        if (now.isBefore(until)) words[token].orEmpty() else emptyList()
}
