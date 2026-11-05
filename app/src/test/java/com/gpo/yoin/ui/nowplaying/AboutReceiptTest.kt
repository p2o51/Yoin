package com.gpo.yoin.ui.nowplaying

import com.gpo.yoin.data.local.SongAboutEntry
import com.gpo.yoin.data.model.LyricCredit
import com.gpo.yoin.data.model.LyricCreditRole
import com.gpo.yoin.data.model.LyricCredits
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

class AboutReceiptTest {

    private val labels = object : AboutLabels {
        override fun about(entryKey: String) = entryKey
        override val quality = "Quality"
        override val serverRating = "Server rating"
        override val genre = "Genre"
        override val year = "Year"
        override val track = "Track"
        override fun discTrack(disc: Int, track: Int) = "Disc $disc, track $track"
        override fun kbps(value: Int) = "$value kbps"
        override fun khz(value: String) = "$value kHz"
        override fun bit(value: Int) = "$value-bit"
        override val arranger = "arranger"
        override val duration = "Length"
        override val channels = "Channels"
        override fun channelCount(count: Int) = if (count == 2) "Stereo" else "$count ch"
        override val size = "Size"
        override fun megabytes(value: String) = "$value MB"
        override val bpm = "BPM"
        override val playCount = "Plays"
        override val added = "Added"
        override val comment = "Comment"
        override val file = "File"
    }

    private fun row(kind: String, key: String, answer: String, title: String? = null) = SongAboutEntry(
        titleKey = "t", artistKey = "a", albumKey = "al",
        titleDisplay = "T", artistDisplay = "A", albumDisplay = "AL",
        kind = kind, entryKey = key, promptText = "q-$key", titleText = title,
        answerText = answer, createdAt = 0L, updatedAt = 0L,
    )

    @Test
    fun should_putCreditsThenGeminiThenEveryServerFact_when_allArePresent() {
        val lyricist = row(SongAboutEntry.KIND_CANONICAL, SongAboutEntry.CANON_LYRICIST, "Gemini guess")
        val time = row(SongAboutEntry.KIND_CANONICAL, SongAboutEntry.CANON_CREATION_TIME, "2019")
        val entries = listOf(
            lyricist,
            time,
            row(SongAboutEntry.KIND_CANONICAL, SongAboutEntry.CANON_PRODUCER, "P\n"),
            row(SongAboutEntry.KIND_CANONICAL, SongAboutEntry.CANON_REVIEW, "a long review"),
        )
        val credits = LyricCredits(
            roles = listOf(
                LyricCredit("作词", "唐恬", LyricCreditRole.Lyricist),
                LyricCredit("编曲", "钱雷", LyricCreditRole.Arranger),
                LyricCredit("吉他", "高飞"),
            ),
        )
        val facts = SpineSourceFacts(
            format = "FLAC", bitRateKbps = 1000, samplingRateHz = 44_100, bitDepth = 16,
            serverRating = 4, genre = "Pop", year = 2019, trackNumber = 3, discNumber = 1,
            durationSec = 245, channels = 2, playCount = 12, bpm = 120, composer = "Server composer",
            comment = "Remaster", added = "2024-01-02T03:04:05Z", path = "Pop/song.flac",
        )

        val rows = aboutReceiptRows(entries, credits, facts, labels)

        assertEquals(
            listOf(
                "lyricist" to "唐恬",
                "composer" to "Server composer",
                "arranger" to "钱雷",
                "producer" to "P",
                "creation_time" to "2019",
                "Genre" to "Pop",
                "Year" to "2019",
                "Track" to "3",
                "Length" to "4:05",
                "Quality" to "FLAC  16-bit  44.1 kHz  1000 kbps",
                "Channels" to "Stereo",
                "BPM" to "120",
                "Plays" to "12",
                "Server rating" to "★★★★☆",
                "Added" to "2024-01-02",
                "Comment" to "Remaster",
                "File" to "Pop/song.flac",
            ),
            rows.map { it.label to it.value },
        )
        // Rows from the lyrics or the server have no Gemini row behind them; Gemini's keep theirs.
        assertNull(rows.first().entry)
        assertSame(time, rows.first { it.value == "2019" && it.label == "creation_time" }.entry)
    }

    @Test
    fun should_fallBackToGemini_when_noSourceNamesTheRole() {
        val composer = row(SongAboutEntry.KIND_CANONICAL, SongAboutEntry.CANON_COMPOSER, "Gemini composer")

        val rows = aboutReceiptRows(listOf(composer), credits = null, facts = null, labels = labels)

        assertEquals(listOf("composer" to "Gemini composer"), rows.map { it.label to it.value })
        assertSame(composer, rows.single().entry)
    }

    @Test
    fun should_listTheReviewFirstThenAnswers_when_buildingParagraphs() {
        val ask = row(SongAboutEntry.KIND_ASK, "a1", "Answer", title = "Title")
        val own = row(SongAboutEntry.KIND_OWN, "o1", "Mine")
        val review = row(SongAboutEntry.KIND_CANONICAL, SongAboutEntry.CANON_REVIEW, "Review")
        val entries = listOf(ask, own, review, row(SongAboutEntry.KIND_ASK, "blank", " "))

        assertEquals(
            listOf("About" to "Review", "Title" to "Answer", "q-o1" to "Mine"),
            aboutParagraphs(entries, reviewLabel = "About").map { it.label to it.text },
        )
    }

    @Test
    fun should_putThreeLinesOnAPageThenOneParagraphEach_when_paging() {
        val rows = (1..7).map { ReceiptRow("l$it", "v$it") }
        val paragraph = AboutParagraph("About", "Review", row(SongAboutEntry.KIND_CANONICAL, "review", "Review"))

        val pages = aboutCarouselPages(rows, listOf(paragraph))

        assertEquals(listOf(3, 3, 1), pages.filterIsInstance<AboutCarouselPage.Lines>().map { it.rows.size })
        assertEquals(AboutCarouselPage.Paragraph(paragraph), pages.last())
    }

    @Test
    fun should_formatLengths_when_overAnHour() {
        assertEquals("1:02:03", formatDuration(3723))
        assertEquals("0:09", formatDuration(9))
    }
}
