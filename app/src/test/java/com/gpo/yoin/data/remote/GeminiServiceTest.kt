package com.gpo.yoin.data.remote

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GeminiServiceTest {

    @Test
    fun should_useGemini31FlashLiteGaModel() {
        assertEquals("gemini-3.1-flash-lite", GeminiService.MODEL)
    }

    @Test
    fun should_cleanAskTitle_when_quotedTitleReturned() {
        val title = GeminiService.cleanAskTitle(
            rawText = "[title]\"Chorus meaning?\"[/title]",
            fallbackQuestion = "What does the chorus mean?",
        )

        assertEquals("Chorus meaning", title)
    }

    @Test
    fun should_fallbackAskTitle_when_emptyTitleReturned() {
        val title = GeminiService.cleanAskTitle(
            rawText = "   ",
            fallbackQuestion = "What does the bridge add?",
        )

        assertEquals("What does the bridge add?", title)
    }

    @Test
    fun should_parseLineTranslations_when_taggedResponseReturned() {
        val raw = """
            [L0][1] 第一行[/L0]
            [L1]【2】第二行[/L1]
        """.trimIndent()

        val parsed = GeminiService.parseLineTranslations(raw, lineCount = 2)

        assertEquals("第一行", parsed[0])
        assertEquals("第二行", parsed[1])
    }

    @Test
    fun should_stripLeadingLineMarkers_when_plainNumberedTranslationsReturned() {
        val raw = """
            [1] 第一行
            (2) 第二行
            3. 第三行
        """.trimIndent()

        val parsed = GeminiService.parseLineTranslations(raw, lineCount = 3)

        assertEquals("第一行", parsed[0])
        assertEquals("第二行", parsed[1])
        assertEquals("第三行", parsed[2])
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
