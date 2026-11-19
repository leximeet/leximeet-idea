package com.github.weilhuang.leximeetidea.connection

import com.google.gson.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DesktopWordCardTest {
    @Test
    fun readsPublicDetailsAndPersonalNote() {
        val summary =
            json(
                """{"headword":"wander","personal":{"note":"  在日志中遇见  "},"learning":{"status":"review"}}"""
            )
        val entry =
            json(
                """
            {
              "senses": [
                {"pos":"verb","short_gloss":"漫游",
                 "examples":[{"text":"We wander.", "translation":"我们四处漫游。"}]},
                {"pos":"noun","english_gloss":"A slow walk"}
              ],
              "pronunciations": [{"notation":"IPA","text":"/ˈwɒndə/"}],
              "forms": [{"text":"wanders"},{"text":"wandered"},{"text":"wanders"}]
            }
            """
            )

        val card = readDesktopWordCard("Wander", summary, entry)

        assertEquals("wander", card.headword)
        assertEquals("/ˈwɒndə/", card.phonetic)
        assertEquals(listOf("verb 漫游", "noun A slow walk"), card.definitions)
        assertEquals(listOf("wanders", "wandered"), card.forms)
        assertEquals(listOf("We wander.\n我们四处漫游。"), card.examples)
        assertEquals("在日志中遇见", card.note)
        assertEquals("待复习", card.statusLabel)
    }

    @Test
    fun supportsPersonalEntriesAndDictionaryFallback() {
        val summary = json("""{"personal":{"note":"自定义笔记"},"learning":{"status":"new"}}""")
        val personal = readDesktopWordCard("Local term", summary, null)

        // 个人词可能没有公开词典资料，仍显示名称、学习状态与保存的笔记。
        assertEquals("Local term", personal.headword)
        assertEquals("自定义笔记", personal.note)
        assertEquals("未学习", personal.statusLabel)
        assertTrue(
            personal.definitions.isEmpty() &&
                personal.forms.isEmpty() &&
                personal.examples.isEmpty()
        )

        val entry =
            json(
                """{"ecdict":{"zh_fallback":"回退释义","legacy_phonetic":"/fallback/"},"forms":null}"""
            )
        val fallback = readDesktopWordCard("Fallback", summary, entry)
        assertEquals(listOf("回退释义"), fallback.definitions)
        assertEquals("/fallback/", fallback.phonetic)
        assertTrue(fallback.forms.isEmpty())
    }

    private fun json(value: String) = JsonParser.parseString(value).asJsonObject
}
