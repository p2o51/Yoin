package com.gpo.yoin.data.highlight

import com.gpo.yoin.data.local.SongAboutEntry
import com.gpo.yoin.shared.ondevice.AboutFollowUpPrompt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AboutFollowUpPromptTest {

    private fun entry(
        kind: String = SongAboutEntry.KIND_CANONICAL,
        key: String = SongAboutEntry.CANON_REVIEW,
        prompt: String? = null,
        title: String? = null,
        answer: String,
    ) = SongAboutEntry(
        titleKey = "wo ai ni",
        artistKey = "crowd lu",
        albumKey = "100 ways of living",
        titleDisplay = "我爱你",
        artistDisplay = "卢广仲",
        albumDisplay = "100种生活",
        kind = kind,
        entryKey = key,
        promptText = prompt,
        titleText = title,
        answerText = answer,
        createdAt = 1L,
        updatedAt = 1L,
    )

    @Test
    fun should_skipSparseAboutEntries() {
        assertNull(AboutFollowUpPrompt.prepare(emptyList(), emptyList(), "zh"))
        assertNull(
            AboutFollowUpPrompt.prepare(
                listOf(entry(key = SongAboutEntry.CANON_CREATION_TIME, answer = "2008")),
                emptyList(),
                "zh",
            ),
        )
    }

    @Test
    fun should_prepareRichAboutContext_andBuildPrompt() {
        val review = entry(
            answer = "这首歌收录于卢广仲 2008 年首张专辑《100种生活》，由钟成虎制作。副歌爆发力很强，现场版本经常加入大段即兴吉他solo。",
        )
        val prepared = AboutFollowUpPrompt.prepare(listOf(review), listOf("歌词在讲什么？"), "zh-CN")

        assertNotNull(prepared)
        val prompt = AboutFollowUpPrompt.buildPrompt(prepared!!)
        assertTrue(prompt.contains("卢广仲"))
        assertTrue(prompt.contains("钟成虎"))
        assertTrue(prompt.contains("歌词在讲什么？"))
    }

    @Test
    fun should_parseJsonArray_andFilterAlreadyAskedAndDuplicates() {
        val raw = """
            ```json
            [
              "钟成虎是怎么制作这首歌的？",
              "歌词在讲什么？",
              "现场版吉他即兴有哪些名场面",
              "钟成虎是怎么制作这首歌的？",
              "这首歌在《100种生活》里是什么位置？"
            ]
            ```
        """.trimIndent()

        val questions = AboutFollowUpPrompt.parse(raw, asked = listOf("歌词在讲什么？"), localeTag = "zh-CN")

        assertEquals(
            listOf(
                "钟成虎是怎么制作这首歌的？",
                "现场版吉他即兴有哪些名场面？",
                "这首歌在《100种生活》里是什么位置？",
            ),
            questions,
        )
    }

    @Test
    fun should_acceptBulletedEnglishFallback_andRejectMetaLines() {
        val raw = """
            Here are 3 follow-up questions:
            1. How did the string arrangement evolve?
            2. Why was it recorded live in one take?
            - What inspired the chorus melody
        """.trimIndent()

        val questions = AboutFollowUpPrompt.parse(raw, asked = emptyList(), localeTag = "en-US")

        assertEquals(
            listOf(
                "How did the string arrangement evolve?",
                "Why was it recorded live in one take?",
                "What inspired the chorus melody?",
            ),
            questions,
        )
    }

    @Test
    fun should_mergeNanoSuggestionsAheadOfFallbackWithoutDuplicates() {
        val merged = AboutFollowUpPrompt.mergeWithFallback(
            suggested = listOf("钟成虎是怎么制作这首歌的？", "歌词在讲什么？"),
            fallback = listOf("这首歌采样了谁？", "歌词在讲什么？", "是怎么写出来的？"),
            asked = listOf("这首歌采样了谁？"),
        )

        assertEquals(
            listOf("钟成虎是怎么制作这首歌的？", "歌词在讲什么？", "是怎么写出来的？"),
            merged,
        )
    }

    @Test
    fun should_preserveNestedCjkSmartQuotes_andEnglishTitlesInChineseQuestions() {
        val raw = """
            ```json
            [
              "“大风吹”如何象征世代焦虑？",
              "歌词中“香烟的味道”具体指代什么隐喻？",
              "《Forbidden Colours》人声版与原曲有何区别？"
            ]
            ```
        """.trimIndent()

        val questions = AboutFollowUpPrompt.parse(raw, asked = emptyList(), localeTag = "zh-CN")

        assertEquals(
            listOf(
                "“大风吹”如何象征世代焦虑？",
                "歌词中“香烟的味道”具体指代什么隐喻？",
                "《Forbidden Colours》人声版与原曲有何区别？",
            ),
            questions,
        )
    }
}
