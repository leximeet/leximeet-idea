package com.github.weilhuang.leximeetidea.connection

import com.github.weilhuang.leximeetidea.reading.WordCard
import com.google.gson.JsonObject

/** 将已验权的 Desktop 词条转换为展示数据，词典缺失时仍保留个人内容。 */
internal fun readDesktopWordCard(
    headword: String,
    summary: JsonObject,
    entry: JsonObject?,
): WordCard {
    val senses = entry.objectsOrEmpty("senses").toList()
    val definitions =
        senses
            .asSequence()
            .mapNotNull { sense ->
                val gloss =
                    sense.textOrNull("short_gloss")?.takeIf(String::isNotBlank)
                        ?: sense.textOrNull("english_gloss")?.takeIf(String::isNotBlank)
                        ?: return@mapNotNull null
                listOfNotNull(sense.textOrNull("pos"), gloss).joinToString(" ").take(300)
            }
            .distinct()
            .take(12)
            .toList()
            .ifEmpty {
                val brief =
                    entry?.textOrNull("headword_summary_zh")?.takeIf(String::isNotBlank)
                        ?: entry
                            ?.getAsJsonObject("ecdict")
                            ?.textOrNull("zh_fallback")
                            ?.takeIf(String::isNotBlank)
                listOfNotNull(brief?.take(180))
            }
    val phonetic =
        entry
            .objectsOrEmpty("pronunciations")
            .firstOrNull { it.textOrNull("notation") == "IPA" }
            ?.textOrNull("text")
            ?: entry?.getAsJsonObject("ecdict")?.textOrNull("legacy_phonetic").orEmpty()
    val note =
        summary
            .get("personal")
            ?.takeIf { it.isJsonObject }
            ?.asJsonObject
            ?.textOrNull("note")
            ?.trim()
            ?.takeIf(String::isNotBlank)
            ?.take(300)
    // 右侧词卡复用同一次只读公开词典查询，只展示资料实际提供的扩展字段。
    val forms =
        entry
            .objectsOrEmpty("forms")
            .mapNotNull { it.textOrNull("text")?.take(80)?.takeIf(String::isNotBlank) }
            .distinct()
            .take(12)
            .toList()
    val examples =
        senses
            .asSequence()
            .flatMap { it.objectsOrEmpty("examples") }
            .mapNotNull { item ->
                val sentence =
                    item.textOrNull("text")?.takeIf(String::isNotBlank) ?: return@mapNotNull null
                listOfNotNull(sentence.take(300), item.textOrNull("translation")?.take(300))
                    .joinToString("\n")
            }
            .distinct()
            .take(3)
            .toList()
    return WordCard(
        headword = summary.textOrNull("headword") ?: headword,
        phonetic = phonetic.take(80),
        definitions = definitions,
        note = note,
        status = summary.fields("learning").string("status"),
        forms = forms,
        examples = examples,
    )
}

private fun JsonObject?.objectsOrEmpty(key: String): Sequence<JsonObject> =
    this?.get(key)
        ?.takeIf { it.isJsonArray }
        ?.asJsonArray
        ?.asSequence()
        .orEmpty()
        .filter { it.isJsonObject }
        .map { it.asJsonObject }
