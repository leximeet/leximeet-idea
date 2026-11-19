package com.github.weilhuang.leximeetidea.reading

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CodeWordsTest {
    @Test
    fun identifierDirectoryAndArchiveNamesKeepUtf16Offsets() {
        val text = "词🙂 fastHTTPServer2Transaction/src/snapshot_cache/OrderResilience-1.2.jar"
        val spans = CodeWords.spans(text, 400)
        assertEquals(
            listOf(
                "fast",
                "HTTP",
                "Server",
                "Transaction",
                "src",
                "snapshot",
                "cache",
                "Order",
                "Resilience",
                "jar",
            ),
            spans.map { it.text },
        )
        for (span in spans) assertEquals(
            span.text,
            text.substring(span.start - 400, span.end - 400),
        )
        assertTrue(spans.zipWithNext().all { (before, after) -> before.end <= after.start })
    }

    @Test
    fun commentsAndStringsUseWholeWordsNotUnrelatedSubstrings() {
        val spans = CodeWords.spans("// snapshot resiliently\nval x = \"resilient\"; 234")
        assertTrue(
            spans.map { it.text }.containsAll(listOf("snapshot", "resiliently", "resilient"))
        )
        assertFalse(spans.any { it.text == "x" || it.text == "234" })
        assertEquals(1, spans.count { it.text == "resilient" })
    }

    @Test
    fun omitsWordsThatCannotFitTheProtocol() {
        assertEquals(listOf("safe"), CodeWords.spans("a".repeat(121) + " safe").map { it.text })
    }
}
