package com.gpo.yoin.ui.memories.copy

import com.gpo.yoin.ui.memories.copy.CopyGolden.TODAY
import java.time.LocalDate
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MemoryVoiceTest {

    @Test
    fun should_match_prototype_voice_for_every_golden_case() {
        val cases = CopyGolden.load("copy-voice.json").list("cases")
        assertTrue("expected the m1–m5 golden cases", cases.size >= 40)
        cases.forEach { element ->
            val case = element.jsonObject
            val key = case.text("key")
            val label = "$key (${case.text("language")})"
            val input = CopyGolden.inputs.getValue(key)
            val voice = if (case.text("language") == "en") {
                MemoryVoice.composeIn(input, TODAY, MemoryProseLanguage.EN)
            } else {
                MemoryVoice.compose(input, TODAY)
            }
            val title = case.getValue("title").jsonObject
            assertEquals(label, CopyGolden.language(case.text("lang")), voice.language)
            assertEquals(label, CopyGolden.titleKind(title.text("kind")), voice.titleKind)
            // The prototype swapped in hand-translated AI titles whenever its prose was English;
            // the app shows the generated AI title as is.
            if (voice.titleKind != MemoryTitleKind.AI || case.text("lang") == "zh") {
                assertEquals(label, title.text("text"), voice.title)
            }
            assertEquals(
                label,
                CopyGolden.withNotesMotifDedup(
                    CopyGolden.withArticleFix(case.optText("nar")),
                    title.text("kind"),
                    title.text("text"),
                ),
                voice.narration,
            )
            assertEquals(label, CopyGolden.withArticleFix(case.optText("ask")), voice.question)
            assertEquals(
                label,
                case.list("said").map { CopyGolden.fact(it.jsonPrimitive.content) }.toSet(),
                voice.said,
            )
        }
    }

    @Test
    fun should_fall_back_to_app_language_when_kana_present() {
        val japanese = listOf("この曲が好き", "副歌太好了，整张专辑都很好听")

        assertEquals(MemoryProseLanguage.EN, MemoryVoice.writesIn(review = null, noteTexts = japanese))
        assertEquals(
            MemoryProseLanguage.ZH,
            MemoryVoice.writesIn(review = null, noteTexts = japanese, appLanguage = MemoryProseLanguage.ZH),
        )
        assertEquals(MemoryProseLanguage.EN, MemoryVoice.writesIn(review = "좋아요 정말 좋아요", noteTexts = emptyList()))
        // nothing written yet: the app language too
        assertEquals(MemoryProseLanguage.EN, MemoryVoice.writesIn(review = null, noteTexts = listOf("8.5 !!")))
    }

    @Test
    fun should_write_in_chinese_when_han_outweighs_latin() {
        // 3 Han ≈ 6 latin letters: a tie goes to Chinese
        assertEquals(MemoryProseLanguage.ZH, MemoryVoice.writesIn(review = null, noteTexts = listOf("Bridge 太好了")))
        assertEquals(
            MemoryProseLanguage.ZH,
            MemoryVoice.writesIn(review = "副歌那一句一直在脑子里转", noteTexts = listOf("Bridge", "Outro")),
        )
        assertEquals(
            MemoryProseLanguage.EN,
            MemoryVoice.writesIn(review = null, noteTexts = listOf("Bridge is great 好")),
        )
    }

    @Test
    fun should_not_repeat_fact_already_in_motif() {
        // m4 without its review and AI title: the plays motif already says the count and the span
        val m4 = CopyGolden.inputs.getValue("m4/as-is").copy(review = null, aiTitle = null)
        val voice = MemoryVoice.compose(m4, TODAY)
        assertEquals(MemoryTitleKind.MOTIF, voice.titleKind)
        assertEquals("5 plays since September", voice.title)
        assertEquals("You last played it five days ago.", voice.narration)
        assertFalse(voice.narration!!.contains("5 plays"))
        assertFalse(voice.narration!!.contains("September"))

        // m3: the seasons motif already said the range, so the narration takes the plays clause instead
        val m3 = MemoryVoice.compose(CopyGolden.inputs.getValue("m3/as-is"), TODAY)
        assertEquals("三个季节，一首一首", m3.title)
        assertFalse(m3.narration!!.contains("季节"))
        assertTrue(MemoryFact.RANGE in m3.said)

        // with an AI title nothing is said yet: the narration may carry the range
        val m5 = MemoryVoice.compose(CopyGolden.inputs.getValue("m5/no-review"), TODAY)
        assertEquals(MemoryTitleKind.AI, m5.titleKind)
        assertEquals("你跨了三个季节回来听，最高分是《Snowline》的 9.5。", m5.narration)
    }

    @Test
    fun should_not_restate_note_count_when_notes_motif_titles_the_card() {
        // m2 without its AI title: the motif says "四天，四条笔记", so narration ① only says the latest note
        val m2 = CopyGolden.inputs.getValue("m2/as-is").copy(aiTitle = null)
        val zh = MemoryVoice.compose(m2, TODAY)
        assertEquals(MemoryTitleKind.MOTIF, zh.titleKind)
        assertEquals("四天，四条笔记", zh.title)
        assertEquals("最近一条写在《序曲》。", zh.narration)
        assertFalse(zh.narration!!.contains("四条笔记"))
        assertEquals("合起来看，这张专辑你会怎么说？", zh.question)
        assertEquals(setOf(MemoryFact.NOTES), zh.said)

        val en = MemoryVoice.composeIn(m2, TODAY, MemoryProseLanguage.EN)
        assertEquals("Four days, four notes", en.title)
        assertEquals("The latest was on 序曲.", en.narration)

        // with an AI title nothing was said yet: ① keeps the count and the span
        val titled = MemoryVoice.compose(CopyGolden.inputs.getValue("m2/as-is"), TODAY)
        assertEquals("四天里记了四条笔记，最近一条写在《序曲》。", titled.narration)
    }

    @Test
    fun should_show_9_95_as_10_0_when_formatting_scores() {
        assertEquals("10.0", MemoryScores.text(9.95))
        assertEquals("10.0", MemoryScores.text(9.95f))
        assertEquals("6.1", MemoryScores.text(6.05f))
        assertEquals("9.9", MemoryScores.text(9.94f))
        assertEquals("8.5", MemoryScores.text(8.5f))
        // the average of 10 and 9.9 lands on 9.95 too
        assertEquals("10.0", MemoryScores.text(listOf(10f, 9.9f).average().toFloat()))
        assertEquals("10.0", MemoryVoice.score(9.95f))
    }

    @Test
    fun should_ask_question_that_follows_preceding_fact() {
        val en = MemoryProseLanguage.EN
        // ① notes, nothing rated: the latest note's track, then "put together"
        val notes = MemoryVoice.composeIn(CopyGolden.inputs.getValue("m2/as-is"), TODAY, en)
        assertEquals("Four notes in four days; the latest was on 序曲.", notes.narration)
        assertEquals("Put together, what would you say about the album?", notes.question)
        // ② rated track by track: the top track, then the album as a whole
        val rated = MemoryVoice.composeIn(CopyGolden.inputs.getValue("m3/as-is"), TODAY, en)
        assertTrue(rated.narration!!.contains("Undertow"))
        assertEquals("And the album as a whole?", rated.question)
        // ③ an album score: the plays, then the score the user gave
        val scored = MemoryVoice.composeIn(CopyGolden.inputs.getValue("m1/no-review"), TODAY, en)
        assertEquals("37 plays since March, the last one two days ago.", scored.narration)
        assertEquals("You gave it a 9.5. What earned it?", scored.question)
        // ④ nothing to build on: no fact, an open question
        val bare = MemoryVoice.composeIn(CopyGolden.inputs.getValue("m2/one-note"), TODAY, en)
        assertNull(bare.narration)
        assertEquals("What stays with you from this one?", bare.question)
        // a review on the card: Yoin has nothing to ask
        val reviewed = MemoryVoice.compose(CopyGolden.inputs.getValue("m1/as-is"), TODAY)
        assertNull(reviewed.narration)
        assertNull(reviewed.question)
    }

    @Test
    fun should_skip_plays_when_album_never_played_in_yoin() {
        val unplayed = CopyGolden.inputs.getValue("m4/as-is").copy(review = null, aiTitle = null, listening = null)
        val voice = MemoryVoice.compose(unplayed, TODAY)

        // no plays motif: the title falls back to the album name, never "0 plays"
        assertEquals(MemoryTitleKind.ALBUM, voice.titleKind)
        assertEquals("MeMe", voice.title)
        // ③ keeps its question but drops the listening clause
        assertNull(voice.narration)
        assertEquals("You gave it a 10.0. What earned it?", voice.question)

        // ② without plays: only the top-track clause
        val m3 = CopyGolden.inputs.getValue("m3/as-is").copy(listening = null)
        val rated = MemoryVoice.composeIn(m3, TODAY, MemoryProseLanguage.ZH)
        assertEquals(MemoryTitleKind.ALBUM, rated.titleKind)
        assertEquals("最高分是《Undertow》的 9.0。", rated.narration)
        assertEquals("那整张专辑呢？", rated.question)
    }

    @Test
    fun should_say_one_play_and_an_eight_in_english() {
        val once = CopyGolden.inputs.getValue("m4/as-is").copy(
            review = null,
            aiTitle = null,
            albumScore = 8.5f,
            listening = MemoryListening(plays = 1, firstHeard = TODAY, lastHeard = TODAY),
        )
        val voice = MemoryVoice.compose(once, TODAY)

        assertEquals("1 play since October", voice.title)
        assertEquals("You last played it today.", voice.narration)
        assertEquals("You gave it an 8.5. What earned it?", voice.question)
    }

    @Test
    fun should_brief_gemini_with_listening_facts_only() {
        val m2 = CopyGolden.inputs.getValue("m2/as-is").copy(aiTitle = null)
        val voice = MemoryVoice.compose(m2, TODAY)
        val brief = requireNotNull(MemoryVoice.narrationBrief(m2, voice, TODAY))

        assertEquals(MemoryProseLanguage.ZH, brief.language)
        assertEquals("Simplified Chinese", brief.languageName)
        assertEquals("四天，四条笔记", brief.alreadySaid)
        assertEquals(
            listOf(
                "Plays in Yoin: 9",
                "Listening since: 九月",
                "Last played: 四天前",
                "Notes written: 4, over 4 days; the latest is on the track 《序曲》",
            ),
            brief.facts,
        )
        // the user's words never reach the prompt
        m2.notes.forEach { note -> assertFalse(brief.signal.contains(note.text)) }
    }

    @Test
    fun should_not_brief_gemini_when_review_present_or_nothing_to_say() {
        val m1 = CopyGolden.inputs.getValue("m1/as-is")
        assertNull(MemoryVoice.narrationBrief(m1, MemoryVoice.compose(m1, TODAY), TODAY))

        val empty = MemoryCopyInput(
            albumName = "Quiet",
            aiTitle = null,
            review = null,
            reviewWrittenOn = null,
            notes = emptyList(),
            tracks = emptyList(),
            ratedTracks = 0,
            totalTracks = 0,
            albumScore = null,
            listening = null,
        )
        assertNull(MemoryVoice.narrationBrief(empty, MemoryVoice.compose(empty, TODAY), TODAY))
    }

    @Test
    fun should_count_with_liang_but_not_past_twelve() {
        assertEquals("两", MemoryVoice.num(2, MemoryProseLanguage.ZH))
        assertEquals("十二", MemoryVoice.num(12, MemoryProseLanguage.ZH))
        assertEquals("13", MemoryVoice.num(13, MemoryProseLanguage.ZH))
        assertEquals("Three", MemoryVoice.num(3, MemoryProseLanguage.EN, capitalize = true))
        val dates = CopyGolden.load("copy-dates.json").list("numbers")
        dates.forEach { element ->
            val o = element.jsonObject
            val n = o.integer("n")
            assertEquals(o.text("zh"), MemoryVoice.num(n, MemoryProseLanguage.ZH))
            assertEquals(o.text("en"), MemoryVoice.num(n, MemoryProseLanguage.EN))
            assertEquals(o.text("enCap"), MemoryVoice.num(n, MemoryProseLanguage.EN, capitalize = true))
        }
    }

    @Test
    fun should_match_prototype_since_ago_and_seasons() {
        CopyGolden.load("copy-dates.json").list("memories").forEach { element ->
            val o = element.jsonObject
            val input = CopyGolden.inputs.getValue(o.text("key"))
            val listening = input.listening!!
            val signals = MemoryVoice.signals(input, TODAY)
            assertEquals(o.text("sinceZh"), MemoryVoice.since(listening, TODAY, MemoryProseLanguage.ZH))
            assertEquals(o.text("sinceEn"), MemoryVoice.since(listening, TODAY, MemoryProseLanguage.EN))
            assertEquals(o.text("agoZh"), MemoryVoice.ago(listening, signals, TODAY, MemoryProseLanguage.ZH))
            assertEquals(o.text("agoEn"), MemoryVoice.ago(listening, signals, TODAY, MemoryProseLanguage.EN))
            assertEquals(o.integer("seasons"), signals.seasons)
            assertEquals(o.optInt("noteDays"), signals.noteDays)
        }
    }

    @Test
    fun should_join_chinese_paragraph_without_space() {
        assertEquals(
            "听了 22 遍。那整张专辑呢？",
            MemoryVoice.paragraph("听了 22 遍。", "那整张专辑呢？", MemoryProseLanguage.ZH),
        )
        assertEquals(
            "22 plays in. And the album as a whole?",
            MemoryVoice.paragraph("22 plays in.", "And the album as a whole?", MemoryProseLanguage.EN),
        )
        assertEquals("What stays?", MemoryVoice.paragraph(null, "What stays?", MemoryProseLanguage.EN))
        assertNull(MemoryVoice.paragraph(null, null, MemoryProseLanguage.EN))
    }

    @Test
    fun should_pick_latest_note_by_date_when_notes_arrive_out_of_order() {
        val m5 = CopyGolden.inputs.getValue("m5/as-is")
        val signals = MemoryVoice.signals(m5, TODAY)
        assertEquals(LocalDate.of(2026, 9, 20), signals.latest?.writtenOn)
        assertEquals("Morning Train", signals.latest?.track?.title)
        // the first track with the highest score, in album order
        assertEquals("Snowline", signals.top?.title)
    }
}
