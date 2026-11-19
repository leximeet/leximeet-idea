package com.github.weilhuang.leximeetidea.reading

import com.google.gson.JsonObject
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EncounterResultsTest {
    @Test
    fun groupsMatchedSpellingAndCountsEachPositionOnce() {
        val wander = choice("wander")
        val snapshot =
            projection(
                "Wander" to listOf(wander),
                "wander" to listOf(wander),
                "subtle" to listOf(choice("subtle")),
                "archive" to listOf(choice("archive")),
            )

        val words =
            groupMatches(
                sequenceOf(
                    "subtle" to "注释",
                    "unknown" to "未命中",
                    "Wander" to "活动编辑器",
                    "wander" to "项目树",
                    "wander" to "活动编辑器",
                    "archive" to "JAR",
                ),
                snapshot,
            )

        // 活动编辑器的位置排在项目树前，同一位置只出现一次。
        assertEquals(listOf("wander", "archive", "subtle"), words.map { it.key.word })
        assertEquals(listOf("活动编辑器", "项目树"), words.first().locations)
    }

    @Test
    fun keepsDifferentWordReferencesSeparateAndIgnoresChoiceOrder() {
        val first = choice("entry-one")
        val second = choice("entry-two")
        val snapshot =
            projection(
                "Entry" to listOf(first, second),
                "entry" to listOf(second, first),
                "ENTRY" to listOf(second),
            )
        val words = groupMatches(sequenceOf("Entry" to 13, "entry" to 22, "ENTRY" to 35), snapshot)

        // 同一拼写只有在桌面端返回的词条身份集合相同时才合并导航位置。
        assertEquals(listOf(listOf(13, 22), listOf(35)), words.map { it.locations })
        assertEquals(
            listOf(setOf(first.ref, second.ref), setOf(second.ref)),
            words.map { it.key.refs },
        )
    }

    @Test
    fun mergesActualTagsAndStatusesAcrossMatchingChoices() {
        val target = choice("first", status = "new")
        val collected = choice("second", status = "review", inTarget = false, collected = true)
        val snapshot = projection("entry" to listOf(target, collected))
        val result = groupMatches(sequenceOf("entry" to "Example.java:8:12"), snapshot).single()

        assertEquals(setOf(WordTag.TARGET, WordTag.COLLECTED), result.tags)
        assertEquals(setOf(LearningStatus.NEW, LearningStatus.REVIEW), result.statuses)
        assertEquals("未学习 · 待复习", result.statusText)
        assertEquals("学习目标 · 已收藏", result.tagText)
    }

    @Test
    fun combinesSearchAcrossLocationsWithTagAndLearningStatus() {
        val word = choice("wander", status = "learning", collected = true)
        val snapshot = projection("Wander" to listOf(word))
        val result =
            groupMatches(
                    sequenceOf("Wander" to "Main.java:2:4", "Wander" to "notes.md:5:2"),
                    snapshot,
                )
                .single()

        // 搜索命中次要出处，开启状态和标签后仍使用同一轮结果。
        assertTrue(
            WordFilters("WANDER NOTES.MD", LearningStatus.LEARNING, WordTag.COLLECTED).includes(
                result
            ) {
                it
            }
        )
        assertTrue(WordFilters("学习中 已收藏", null, null).includes(result) { it })
        assertFalse(
            WordFilters("wander", LearningStatus.MASTERED, WordTag.COLLECTED).includes(result) {
                it
            }
        )
        assertFalse(WordFilters("missing.java", null, null).includes(result) { it })
    }

    private fun choice(
        id: String,
        status: String = "",
        inTarget: Boolean = true,
        collected: Boolean = false,
    ) =
        WordChoice(
            JsonObject().apply { addProperty("id", id) },
            id,
            inTarget = inTarget,
            collected = collected,
            status = status,
        )

    private fun projection(vararg entries: Pair<String, List<WordChoice>>) =
        ReadingProjection("test-owner", "1", Instant.MAX, mapOf(*entries))
}
