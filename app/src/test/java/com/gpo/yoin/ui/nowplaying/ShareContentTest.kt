package com.gpo.yoin.ui.nowplaying

import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import com.gpo.yoin.data.local.SongAboutEntry
import com.gpo.yoin.data.local.SongNote
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The share sheet's rules (owner 2026-11-25): which layouts a tab offers (竖排 for CJK lines only), what a
 * Poster draws (four lines at most, the translation as one line), the Player's excerpt (≥ 6% of the bar),
 * the role's tones (Paper replaces Mist for About and Note), what About shares (credits and one AI
 * paragraph — no file facts, no listener entries), and which note.
 */
class ShareContentTest {

    private val song = ShareSong(songId = "s", title = "T", artist = "A", coverArtUrl = null, durationMs = 300_000L)

    private fun lines(vararg text: String) = text.mapIndexed { i, t -> LyricLine(startMs = 60_000L + i * 4_000L, text = t) }

    @Test
    fun should_tellCjkFromOtherScripts_when_gatingTheVerticalLayout() {
        assertTrue(isCjkLyric("帶著笑容的睡意化成了彩虹"))
        assertTrue(isCjkLyric("夜に駆ける、2019"))
        assertTrue(isCjkLyric("사랑해요"))
        assertFalse(isCjkLyric("我爱你 baby"))
        assertFalse(isCjkLyric("If I could go back"))
        assertFalse(isCjkLyric("1, 2, 3!"))
    }

    @Test
    fun should_offerVertical_when_everyLineIsCjk() {
        val cjk = ShareContent(song, lyrics = LyricsSharePick(lines("帶著笑容", "在他夢中")))
        assertEquals(
            listOf(ShareLayout.LyricsClassic, ShareLayout.LyricsPoster, ShareLayout.LyricsPlayer, ShareLayout.LyricsVertical),
            cjk.layoutsFor(ShareTab.Lyrics),
        )
        val mixed = ShareContent(song, lyrics = LyricsSharePick(lines("帶著笑容", "Before I ever fell down")))
        assertFalse(ShareLayout.LyricsVertical in mixed.layoutsFor(ShareTab.Lyrics))
    }

    @Test
    fun should_fallBackToTheFirstLayout_when_theRememberedOneIsNotOffered() {
        val latin = ShareContent(song, lyrics = LyricsSharePick(lines("Before I ever fell down")))
        assertEquals(ShareLayout.LyricsClassic, latin.resolveLayout(ShareTab.Lyrics, ShareLayout.LyricsVertical))
        assertEquals(ShareLayout.LyricsPlayer, latin.resolveLayout(ShareTab.Lyrics, ShareLayout.LyricsPlayer))
        assertNull(latin.resolveLayout(ShareTab.Note, ShareLayout.NoteBig))
    }

    @Test
    fun should_capThePosterAtFourLines_andKeepTheTranslationToOneLine() {
        val pick = LyricsSharePick(
            lines = (1..6).map { LyricLine(startMs = it * 1_000L, text = "Line $it", translation = "第 $it 行") },
            showTranslation = true,
        )
        val poster = posterLines(pick)!!
        assertEquals("Line 1", poster.lead.text)
        assertEquals(listOf("Line 2", "Line 3", "Line 4"), poster.rest.map { it.text })
        assertEquals(PosterMaxLines, 1 + poster.rest.size)
        val translation = poster.translation!!
        assertFalse("one line: $translation", translation.contains('\n'))
        assertTrue(translation.startsWith("第 1 行") && translation.contains("第 4 行") && !translation.contains("第 5 行"))

        assertNull(posterLines(pick.copy(showTranslation = false))!!.translation)
    }

    @Test
    fun should_drawAShortExcerptAtLeastSixPercent_when_markingThePlayerBar() {
        val excerpt = playerExcerpt(LyricsSharePick(lines("one")), durationMs = 300_000L)!!
        assertEquals(60_000L, excerpt.startMs)
        assertTrue(excerpt.end - excerpt.start >= PlayerExcerptMinFraction - 1e-6f)
        // At the song's very end it stays inside the bar.
        val late = playerExcerpt(LyricsSharePick(listOf(LyricLine(299_000L, "x"))), durationMs = 300_000L)!!
        assertTrue(late.end <= 1f && late.start >= 0f)
        assertNull(playerExcerpt(LyricsSharePick(lines("one")), durationMs = 0L))
        assertNull(playerExcerpt(LyricsSharePick(listOf(LyricLine(null, "untimed"))), durationMs = 300_000L))
    }

    @Test
    fun should_setEachLineInAColumn_andStepTheSizeDown_when_thereAreMany() {
        val few = verticalLyricsPlan(listOf("帶著笑容的睡意化成了彩虹", "在他夢中一口氣走上了星空"), 380f, 250f)
        assertEquals(26f, few.fontSizeSp)
        assertEquals(2, few.columns.size)
        val many = verticalLyricsPlan((1..12).map { "第${it}行歌词在这里" }, 380f, 250f)
        assertTrue(many.fontSizeSp < few.fontSizeSp)
        assertTrue(many.columns.size * many.fontSizeSp * VerticalColumnAdvance <= 250f)
        assertEquals('︒', verticalForm('。'))
        assertEquals('﹁', verticalForm('「'))
        assertEquals('字', verticalForm('字'))
    }

    @Test
    fun should_drawPaperFromTheRole_when_sharingAboutOrANote() {
        val scheme = lightColorScheme(
            primary = Color(0xFF000001),
            primaryContainer = Color(0xFF000002),
            onPrimaryContainer = Color(0xFF000003),
            tertiary = Color(0xFF000004),
            tertiaryContainer = Color(0xFF000006),
            onTertiaryContainer = Color(0xFF000007),
            surfaceContainerLowest = Color(0xFFFFFFFF),
        )
        assertEquals(listOf(ShareTone.Soft, ShareTone.Deep, ShareTone.Vivid, ShareTone.Mist), ShareRole.Lyrics.tones())
        assertEquals(listOf(ShareTone.Soft, ShareTone.Deep, ShareTone.Vivid, ShareTone.Paper), ShareRole.Ai.tones())
        assertEquals(listOf(ShareTone.Soft, ShareTone.Deep, ShareTone.Vivid, ShareTone.Paper), ShareRole.Own.tones())

        val aboutPaper = ShareTone.Paper.colors(scheme, ShareRole.Ai)
        assertEquals(PaperCard, aboutPaper.card)
        assertEquals(paperInk(scheme.tertiary), aboutPaper.content)
        val notePaper = ShareTone.Paper.colors(scheme, ShareRole.Own)
        assertEquals(PaperCard, notePaper.card)
        assertEquals(paperInk(scheme.primary), notePaper.content)
        // About is tertiary all through, a note primary.
        assertEquals(scheme.tertiaryContainer, ShareTone.Soft.colors(scheme, ShareRole.Ai).card)
        assertEquals(scheme.primaryContainer, ShareTone.Soft.colors(scheme, ShareRole.Own).card)
        // The lyrics keep Mist in the fourth slot, whichever it is asked by.
        assertEquals(scheme.tertiaryContainer, ShareTone.Paper.colors(scheme, ShareRole.Lyrics).card)
    }

    @Test
    fun should_keepPaperWhiteWithLightInk_when_theThemeIsDark() {
        // A dark scheme: light roles, dark surfaces (SPEC_2025-like tones).
        val dark = androidx.compose.material3.darkColorScheme(
            primary = Color(0xFFB8C4FF),
            tertiary = Color(0xFF8BD8B5),
            surfaceContainerLowest = Color(0xFF0B0C10),
        )
        for (role in listOf(ShareRole.Ai, ShareRole.Own)) {
            val paper = ShareTone.Paper.colors(dark, role)
            assertEquals("$role card", Color.White, paper.card)
            // The ink is the role at its light-theme tone: dark enough to read on white.
            assertTrue("$role ink luminance ${paper.content.luminance()}", paper.content.luminance() < 0.25f)
        }
    }

    private val labels = object : AboutLabels {
        override fun about(entryKey: String) = entryKey
        override val quality = "Quality"
        override val serverRating = "Rating"
        override val genre = "Genre"
        override val year = "Year"
        override val track = "Track"
        override fun discTrack(disc: Int, track: Int) = "$disc-$track"
        override fun kbps(value: Int) = "$value kbps"
        override fun khz(value: String) = "$value kHz"
        override fun bit(value: Int) = "$value bit"
        override val arranger = "Arranger"
        override val duration = "Length"
        override val channels = "Channels"
        override fun channelCount(count: Int) = "$count"
        override val size = "Size"
        override fun megabytes(value: String) = "$value MB"
        override val bpm = "BPM"
        override val playCount = "Plays"
        override val added = "Added"
        override val comment = "Comment"
        override val file = "File"
    }

    private fun entry(kind: String, key: String, answer: String, title: String? = null) = SongAboutEntry(
        titleKey = "t",
        artistKey = "a",
        albumKey = "al",
        titleDisplay = "T",
        artistDisplay = "A",
        albumDisplay = "Al",
        kind = kind,
        entryKey = key,
        promptText = title,
        titleText = title,
        answerText = answer,
        createdAt = 1L,
        updatedAt = 1L,
    )

    @Test
    fun should_shareCreditsAndOneAiParagraph_when_buildingAbout() {
        val entries = listOf(
            entry(SongAboutEntry.KIND_CANONICAL, SongAboutEntry.CANON_LYRICIST, "Wu Qingfeng"),
            entry(SongAboutEntry.KIND_CANONICAL, SongAboutEntry.CANON_REVIEW, "The review."),
            entry(SongAboutEntry.KIND_ASK, "ask-1", "The answer.", title = "Who sampled it"),
            entry(SongAboutEntry.KIND_OWN, "own-1", "My own words.", title = "Mine"),
        )
        val facts = SpineSourceFacts(
            format = "FLAC",
            bitRateKbps = 900,
            year = 2009,
            composer = "Wu Qingfeng",
            playCount = 14,
            added = "2024-01-01",
            path = "/music/a.flac",
        )
        val pick = aboutSharePick(entries, credits = null, facts = facts, labels = labels, reviewLabel = "About")!!
        val shown = pick.credits.map { it.label to it.value }
        assertTrue(SongAboutEntry.CANON_LYRICIST to "Wu Qingfeng" in shown)
        // The AI's lyricist is marked as the AI's; the server's composer and the year are not.
        assertTrue(pick.credits.first { it.label == SongAboutEntry.CANON_LYRICIST }.ai)
        assertFalse(pick.credits.first { it.label == SongAboutEntry.CANON_COMPOSER }.ai)
        assertTrue(pick.credits.first { it.label == "Year" }.fact)
        assertTrue(pick.aiContent)
        assertTrue(SongAboutEntry.CANON_COMPOSER to "Wu Qingfeng" in shown)
        assertTrue("Year" to "2009" in shown)
        // None of the server's file facts.
        val leaked = pick.credits.map { it.label }.filter { it in setOf("Quality", "Plays", "Added", "File", "Size") }
        assertTrue("leaked: $leaked", leaked.isEmpty())
        assertEquals("About", pick.heading)
        assertEquals("The review.", pick.paragraph)

        // A long-pressed answer is the paragraph; the listener's own entry never is.
        val asked = aboutSharePick(entries, null, facts, labels, "About", focus = entries[2])!!
        assertEquals("Who sampled it", asked.heading)
        assertEquals("The answer.", asked.paragraph)
        val own = aboutSharePick(entries, null, facts, labels, "About", focus = entries[3])!!
        assertEquals("The review.", own.paragraph)
    }

    @Test
    fun should_shareNothing_when_aboutHasNeitherCreditsNorParagraph() {
        val ownOnly = listOf(entry(SongAboutEntry.KIND_OWN, "own-1", "My own words.", title = "Mine"))
        assertNull(aboutSharePick(ownOnly, null, null, labels, "About"))
        // The year alone is not an About either.
        assertNull(aboutSharePick(ownOnly, null, SpineSourceFacts(year = 2009, path = "/a.flac"), labels, "About"))
    }

    @Test
    fun should_signNothing_when_noneOfTheImageCameFromTheAi() {
        // No About entries (no AI key): only the server's composer and the year.
        val pick = aboutSharePick(
            entries = emptyList(),
            credits = null,
            facts = SpineSourceFacts(composer = "Wu Qingfeng", year = 2009),
            labels = labels,
            reviewLabel = "About",
        )!!
        assertFalse(pick.aiContent)
        assertNull(pick.paragraph)
        assertTrue(ShareContent(song, about = pick).has(ShareTab.About))
    }

    private fun note(id: String, at: Long?, created: Long) = SongNote(
        id = id,
        trackId = "t",
        content = "note $id",
        createdAt = created,
        updatedAt = created,
        title = "T",
        artist = "A",
        positionMs = at,
    )

    @Test
    fun should_pickTheAskedNote_elseThePlayheadsElseTheNewest() {
        val notes = listOf(note("a", 10_000L, 1L), note("b", 50_000L, 2L), note("c", null, 3L))
        assertEquals("a", noteSharePick(notes, noteId = "a", positionMs = 60_000L)!!.noteId)
        assertEquals("b", noteSharePick(notes, noteId = null, positionMs = 60_000L)!!.noteId)
        assertEquals("c", noteSharePick(notes, noteId = null, positionMs = 1_000L)!!.noteId)
        assertNull(noteSharePick(emptyList(), null, 0L))
    }

    @Test
    fun should_offerTheTimeline_only_when_theNoteHasAMoment() {
        val anchored = ShareContent(song, note = NoteSharePick("n", "hi", positionMs = 30_000L, createdAt = 0L))
        assertTrue(ShareLayout.NoteTimeline in anchored.layoutsFor(ShareTab.Note))
        val loose = anchored.copy(note = anchored.note!!.copy(positionMs = null))
        assertFalse(ShareLayout.NoteTimeline in loose.layoutsFor(ShareTab.Note))
        assertEquals(listOf(ShareTab.Note), loose.tabs)
    }

    @Test
    fun should_shareTheCurrentLine_when_nothingIsPicked() {
        val lyrics = lines("one", "two", "three")
        assertEquals("two", currentLyricsPick(lyrics, positionMs = 65_000L, showTranslation = false)!!.lines.single().text)
        assertEquals("one", currentLyricsPick(lyrics, positionMs = 0L, showTranslation = false)!!.lines.single().text)
        assertNull(currentLyricsPick(emptyList(), 0L, false))
    }
}
