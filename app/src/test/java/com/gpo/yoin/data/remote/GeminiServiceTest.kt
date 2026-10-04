package com.gpo.yoin.data.remote

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
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

    @Test
    fun should_buildNarrationPrompt_with_voiceRules_language_and_facts_only() {
        val prompt = GeminiService.buildMemoryNarrationPrompt(
            languageName = "Simplified Chinese",
            facts = listOf("Plays in Yoin: 9", "Last played: 四天前"),
            alreadySaid = "四天，四条笔记",
        )

        assertTrue(prompt.contains("- Write in Simplified Chinese."))
        assertTrue(prompt.contains("- Plays in Yoin: 9\n- Last played: 四天前"))
        assertTrue(
            prompt.contains("The title already on the page reads: \"四天，四条笔记\". Do not repeat any fact it states."),
        )
        assertTrue(prompt.contains("second person, past tense"))
        assertTrue(prompt.contains("one or two short sentences"))
        assertTrue(prompt.contains("Never judge, praise or describe the music"))
        assertTrue(prompt.contains("Never quote, paraphrase or allude to anything the listener wrote"))
        assertTrue(prompt.contains("\"memory\""))
        assertTrue(prompt.contains("follows directly from the last fact"))
        assertTrue(prompt.contains("[NARRATION]...[/NARRATION]\n[QUESTION]...[/QUESTION]"))
        // the old poetic brief is gone
        assertFalse(prompt.contains("poetic"))
    }

    @Test
    fun should_omitAlreadySaidLine_when_titleIsNotAMotif() {
        val prompt = GeminiService.buildMemoryNarrationPrompt(
            languageName = "English",
            facts = listOf("Plays in Yoin: 37"),
            alreadySaid = null,
        )

        assertTrue(prompt.contains("- Write in English."))
        assertFalse(prompt.contains("The title already on the page"))
    }

    @Test
    fun should_parseMemoryNarration_and_roundTripCachedForm() {
        val parsed = GeminiService.parseMemoryNarration(
            "[NARRATION] “37 plays since March,\nthe last one two days ago.” [/NARRATION]\n" +
                "[question]You gave it a 9.5. What earned it?[/question]",
        )

        assertEquals("37 plays since March, the last one two days ago.", parsed?.narration)
        assertEquals("You gave it a 9.5. What earned it?", parsed?.question)
        assertEquals(parsed, GeminiService.parseMemoryNarration(GeminiService.encodeMemoryNarration(parsed!!)))
    }

    @Test
    fun should_rejectMemoryNarration_when_partMissingOrQuestionIsNotAQuestion() {
        assertNull(GeminiService.parseMemoryNarration("[NARRATION]Only a fact.[/NARRATION]"))
        assertNull(
            GeminiService.parseMemoryNarration("[NARRATION]A fact.[/NARRATION][QUESTION]Not a question.[/QUESTION]"),
        )
        assertNull(GeminiService.parseMemoryNarration("Plain prose without tags?"))
        assertEquals(
            "那整张专辑呢？",
            GeminiService.parseMemoryNarration(
                "[NARRATION]听了 22 遍。[/NARRATION][QUESTION]那整张专辑呢？[/QUESTION]",
            )?.question,
        )
    }
}
