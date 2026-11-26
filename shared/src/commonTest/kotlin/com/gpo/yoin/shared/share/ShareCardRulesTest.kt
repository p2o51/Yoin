package com.gpo.yoin.shared.share

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The share sheet's rules, as Android's ShareContentTest / LyricsShareCardTest state them (owner 2026-11-25): the
 * layouts a tab offers (竖排 for CJK lines only), a Poster's four lines, the Player's ≥ 6% excerpt, the role's
 * tones, what About shares, which note, the Story zoom, the export sizes and the file name.
 */
class ShareCardRulesTest {

    private val song = ShareSong("s", "T", "A", null, "", 300_000L)

    private fun lines(vararg text: String) = text.mapIndexed { i, t -> ShareLyricLine(60_000L + i * 4_000L, t, null) }

    private fun content(lyrics: ShareLyrics? = null, about: ShareAbout? = null, note: ShareNote? = null) =
        ShareCardContent(song, lyrics, about, note)

    @Test
    fun should_tellCjkFromOtherScripts_when_gatingTheVerticalLayout() {
        assertTrue(YoinShareCards.isCjk("帶著笑容的睡意化成了彩虹"))
        assertTrue(YoinShareCards.isCjk("夜に駆ける、2019"))
        assertTrue(YoinShareCards.isCjk("사랑해요"))
        assertTrue(YoinShareCards.isCjk("𠀀𠀁"))
        assertFalse(YoinShareCards.isCjk("我爱你 baby"))
        assertFalse(YoinShareCards.isCjk("If I could go back"))
        assertFalse(YoinShareCards.isCjk("1, 2, 3!"))
        // ー, ｰ and 〆 are Common-script letters only Japanese writes: they count.
        assertTrue(YoinShareCards.isCjk("ラーメン"))
        assertTrue(YoinShareCards.isCjk("ﾗｰﾒﾝ"))
        assertTrue(YoinShareCards.isCjk("締〆"))
        assertFalse(YoinShareCards.isCjk("ラーメン ramen"))
    }

    @Test
    fun should_offerVertical_when_everyLineIsCjk() {
        val cjk = content(ShareLyrics(lines("帶著笑容", "在他夢中")))
        assertEquals(
            listOf(ShareLayout.LyricsClassic, ShareLayout.LyricsPoster, ShareLayout.LyricsPlayer, ShareLayout.LyricsVertical),
            YoinShareCards.layouts(cjk, ShareTab.Lyrics),
        )
        val mixed = content(ShareLyrics(lines("帶著笑容", "Before I ever fell down")))
        assertFalse(ShareLayout.LyricsVertical in YoinShareCards.layouts(mixed, ShareTab.Lyrics))
    }

    @Test
    fun should_fallBackToTheFirstLayout_when_theRememberedOneIsNotOffered() {
        val latin = content(ShareLyrics(lines("Before I ever fell down")))
        assertEquals(ShareLayout.LyricsClassic, YoinShareCards.resolveLayout(latin, ShareTab.Lyrics, ShareLayout.LyricsVertical))
        assertEquals(ShareLayout.LyricsPlayer, YoinShareCards.resolveLayout(latin, ShareTab.Lyrics, ShareLayout.LyricsPlayer))
        assertNull(YoinShareCards.resolveLayout(latin, ShareTab.Note, ShareLayout.NoteBig))
        assertEquals(ShareLayout.NoteBig, YoinShareCards.layoutForStoreValue(ShareTab.Note, "NoteBig"))
        assertNull(YoinShareCards.layoutForStoreValue(ShareTab.Lyrics, "NoteBig"))
    }

    @Test
    fun should_capThePosterAtFourLines_andKeepTheTranslationToOneLine() {
        val pick = ShareLyrics((1..6).map { ShareLyricLine(it * 1_000L, "Line $it", "第 $it 行") }, emptyList(), true)
        val poster = YoinShareCards.poster(pick)!!
        assertEquals("Line 1", poster.lead.text)
        assertEquals(listOf("Line 2", "Line 3", "Line 4"), poster.rest.map { it.text })
        assertEquals("第 1 行  /  第 2 行  /  第 3 行  /  第 4 行", poster.translation)
        assertEquals("0:01", poster.leadTime)
        assertNull(YoinShareCards.poster(pick.copy(showTranslation = false))!!.translation)
    }

    @Test
    fun should_drawAShortExcerptAtLeastSixPercent_when_markingThePlayerBar() {
        val excerpt = YoinShareCards.playerExcerpt(ShareLyrics(lines("one")), 300_000L)!!
        assertEquals(60_000L, excerpt.startMs)
        assertTrue(excerpt.end - excerpt.start >= YoinShareCards.playerExcerptMinFraction - 1e-6f)
        val late = YoinShareCards.playerExcerpt(ShareLyrics(listOf(ShareLyricLine(299_000L, "x"))), 300_000L)!!
        assertTrue(late.end <= 1f && late.start >= 0f)
        assertNull(YoinShareCards.playerExcerpt(ShareLyrics(lines("one")), 0L))
        assertNull(YoinShareCards.playerExcerpt(ShareLyrics(listOf(ShareLyricLine(null, "untimed"))), 300_000L))
    }

    @Test
    fun should_setEachLineInAColumn_andStepTheSizeDown_when_thereAreMany() {
        val few = YoinShareCards.verticalPlan(listOf("帶著笑容的睡意化成了彩虹", "在他夢中一口氣走上了星空"), 380f, 250f)
        assertEquals(26f, few.fontSizeSp)
        assertEquals(2, few.columns.size)
        val many = YoinShareCards.verticalPlan((1..12).map { "第${it}行歌词在这里" }, 380f, 250f)
        assertTrue(many.fontSizeSp < few.fontSizeSp)
        assertTrue(many.columns.size * many.fontSizeSp * YoinShareCards.verticalColumnAdvance <= 250f)
        assertEquals("︒﹁字﹂︱", YoinShareCards.verticalText("。「字」ー"))
    }

    @Test
    fun should_drawPaperFromTheRole_when_sharingAboutOrANote() {
        assertEquals(listOf(ShareTone.Soft, ShareTone.Deep, ShareTone.Vivid, ShareTone.Mist), ShareRole.Lyrics.tones())
        assertEquals(listOf(ShareTone.Soft, ShareTone.Deep, ShareTone.Vivid, ShareTone.Paper), ShareRole.Ai.tones())
        val aboutPaper = YoinShareCards.toneColors(ShareTone.Paper, ShareRole.Ai)
        assertEquals(ShareInk(ShareInkSource.White, null), aboutPaper.card)
        assertEquals(ShareInk(ShareInkSource.Tertiary, 40), aboutPaper.content)
        assertEquals(ShareInk(ShareInkSource.Tertiary, 90), aboutPaper.backdrop)
        assertEquals(ShareInk(ShareInkSource.Primary, 40), YoinShareCards.toneColors(ShareTone.Paper, ShareRole.Own).content)
        assertEquals(ShareInk(ShareInkSource.TertiaryContainer, null), YoinShareCards.toneColors(ShareTone.Paper, ShareRole.Lyrics).card)
        assertEquals(ShareInk(ShareInkSource.PrimaryContainer, null), YoinShareCards.toneColors(ShareTone.Soft, ShareRole.Own).card)
    }

    private val labels = ShareAboutLabels("lyricist", "composer", "Arranger", "producer", "creation_time", "creation_location", "Year", "About")

    private fun entry(kind: String, key: String, answer: String, title: String? = null) = ShareAboutEntry(kind, key, title, title, answer)

    @Test
    fun should_shareCreditsAndOneAiParagraph_when_buildingAbout() {
        val entries = listOf(
            entry(YoinShareCards.KIND_CANONICAL, YoinShareCards.CANON_LYRICIST, "Wu Qingfeng"),
            entry(YoinShareCards.KIND_CANONICAL, YoinShareCards.CANON_REVIEW, "The review."),
            entry(YoinShareCards.KIND_ASK, "ask-1", "The answer.", title = "Who sampled it"),
            entry(YoinShareCards.KIND_OWN, "own-1", "My own words.", title = "Mine"),
        )
        val sources = ShareAboutSources(null, null, null, null, "Wu Qingfeng", 2009)
        val pick = YoinShareCards.aboutPick(entries, sources, labels, null, null)!!
        assertEquals(
            listOf(
                ShareCredit("lyricist", "Wu Qingfeng", ai = true),
                ShareCredit("composer", "Wu Qingfeng"),
                ShareCredit("Year", "2009", fact = true),
            ),
            pick.credits,
        )
        assertTrue(pick.aiContent)
        assertEquals("About", pick.heading)
        assertEquals("The review.", pick.paragraph)
        val asked = YoinShareCards.aboutPick(entries, sources, labels, "ask", "ask-1")!!
        assertEquals("Who sampled it", asked.heading)
        assertEquals("The answer.", asked.paragraph)
        assertEquals("The review.", YoinShareCards.aboutPick(entries, sources, labels, "own", "own-1")!!.paragraph)
    }

    @Test
    fun should_shareNothing_when_aboutHasNeitherCreditsNorParagraph() {
        val ownOnly = listOf(entry(YoinShareCards.KIND_OWN, "own-1", "Mine."))
        val none = ShareAboutSources(null, null, null, null, null, null)
        assertNull(YoinShareCards.aboutPick(ownOnly, none, labels, null, null))
        assertNull(YoinShareCards.aboutPick(ownOnly, none.copy(year = 2009), labels, null, null))
        val serverOnly = YoinShareCards.aboutPick(emptyList(), none.copy(serverComposer = "W", year = 2009), labels, null, null)!!
        assertFalse(serverOnly.aiContent)
        assertTrue(YoinShareCards.has(content(about = serverOnly), ShareTab.About))
    }

    @Test
    fun should_pickTheAskedNote_elseThePlayheadsElseTheNewest() {
        val notes = listOf(ShareNote("a", "note a", 10_000L, 1L), ShareNote("b", "note b", 50_000L, 2L), ShareNote("c", "note c", null, 3L))
        assertEquals("a", YoinShareCards.notePick(notes, "a", 60_000L)!!.noteId)
        assertEquals("b", YoinShareCards.notePick(notes, null, 60_000L)!!.noteId)
        assertEquals("c", YoinShareCards.notePick(notes, null, 1_000L)!!.noteId)
        assertNull(YoinShareCards.notePick(emptyList(), null, 0L))
        val anchored = content(note = notes[0])
        assertTrue(ShareLayout.NoteTimeline in YoinShareCards.layouts(anchored, ShareTab.Note))
        assertFalse(ShareLayout.NoteTimeline in YoinShareCards.layouts(content(note = notes[2]), ShareTab.Note))
    }

    @Test
    fun should_shareTheCurrentLine_when_nothingIsPicked() {
        val lyrics = lines("one", "two", "three")
        assertEquals("two", YoinShareCards.currentLine(lyrics, 65_000L, false)!!.lines.single().text)
        assertEquals("one", YoinShareCards.currentLine(lyrics, 0L, false)!!.lines.single().text)
        assertNull(YoinShareCards.currentLine(emptyList(), 0L, false))
    }

    @Test
    fun should_zoomTheStory_withinItsBounds() {
        assertEquals(1f, YoinShareCards.storyZoom(1f) { true })
        assertEquals(1.4f, YoinShareCards.storyZoom(1.4f) { true })
        val zoom = YoinShareCards.storyZoom(1f) { it <= 0.62f }
        assertTrue(zoom <= 0.62f && zoom > 0.61f, "$zoom")
        assertEquals(YoinShareCards.storyMinZoom, YoinShareCards.storyZoom(1.4f) { false })
        assertEquals(1f, YoinShareCards.storyMaxZoom(ShareLayout.LyricsPoster))
        assertEquals(1.4f, YoinShareCards.storyMaxZoom(ShareLayout.LyricsClassic))
    }

    @Test
    fun should_exportAtFixedWidths_andNameTheFile() {
        assertEquals(ShareExportSize(1440, 2000), YoinShareCards.exportSize(ShareFormat.Card, 720f, 1000f))
        assertEquals(ShareExportSize(1080, 1920), YoinShareCards.exportSize(ShareFormat.Story, 720f, 1280f))
        assertEquals(0.25f, YoinShareCards.fitScale(400, 800, 1_000, 200, 1f))
        assertEquals("Yoin - AC DC Back in Black - 42.png", YoinShareCards.fileName("AC/DC: Back in Black?", 42L))
        assertEquals("Yoin - 晴天 - 7.png", YoinShareCards.fileName("  晴天 ", 7L))
        assertEquals("Yoin 9.png", YoinShareCards.fileName(" / ", 9L))
        assertEquals("Yoin 9.png", YoinShareCards.fileName(null, 9L))
        assertEquals("晴天 · 周杰倫", YoinShareCards.caption(" 晴天 ", "周杰倫"))
        assertEquals("晴天", YoinShareCards.caption("晴天", " "))
        assertEquals("由千问整理", YoinShareCards.aiSign("由 %1\$s 整理", "千问"))
        assertEquals("由 Gemini 整理", YoinShareCards.aiSign("由 %1\$s 整理", "Gemini"))
        assertEquals("1:05", YoinShareCards.time(65_000L))
        assertEquals("62:05", YoinShareCards.time(3_725_000L))
    }
}
