package com.gpo.yoin.data.remote

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GeminiServiceTest {

    @Test
    fun should_parseLineTranslations_when_taggedResponseReturned() {
        val raw = """
            [L0]第一行[/L0]
            [L1]第二行[/L1]
        """.trimIndent()

        val parsed = GeminiService.parseLineTranslations(raw, lineCount = 2)

        assertEquals("第一行", parsed[0])
        assertEquals("第二行", parsed[1])
    }

    @Test
    fun should_returnEmptyLineTranslations_when_plainFallbackLineCountDiffers() {
        val parsed = GeminiService.parseLineTranslations(
            rawText = "第一行\n第二行\n第三行",
            lineCount = 2,
        )

        assertTrue(parsed.isEmpty())
    }
}
