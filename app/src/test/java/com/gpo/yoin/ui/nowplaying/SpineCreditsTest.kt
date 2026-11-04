package com.gpo.yoin.ui.nowplaying

import com.gpo.yoin.data.local.SongAboutEntry
import org.junit.Assert.assertEquals
import org.junit.Test

class SpineCreditsTest {

    private val labels = object : SpineLabels {
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
    }

    private fun row(kind: String, key: String, answer: String) = SongAboutEntry(
        titleKey = "t", artistKey = "a", albumKey = "al",
        titleDisplay = "T", artistDisplay = "A", albumDisplay = "AL",
        kind = kind, entryKey = key, promptText = null, titleText = null,
        answerText = answer, createdAt = 0L, updatedAt = 0L,
    )

    private fun items(entries: List<SongAboutEntry>, facts: SpineSourceFacts? = null) =
        spineItems(entries, facts, albumName = "Album", artist = "Artist", title = "Title", labels = labels)

    @Test
    fun should_listCanonicalCreditsInAboutOrder_when_cached() {
        val entries = listOf(
            row(SongAboutEntry.KIND_CANONICAL, SongAboutEntry.CANON_PRODUCER, "P"),
            row(SongAboutEntry.KIND_CANONICAL, SongAboutEntry.CANON_LYRICIST, "L\nL2"),
            row(SongAboutEntry.KIND_CANONICAL, SongAboutEntry.CANON_REVIEW, "a long review"),
            row(SongAboutEntry.KIND_ASK, SongAboutEntry.CANON_COMPOSER, "not a credit"),
        )
        assertEquals(
            listOf(SpineItem("lyricist", "L L2"), SpineItem("producer", "P")),
            items(entries),
        )
    }

    @Test
    fun should_addTheServerFacts_when_theTrackIsSubsonic() {
        val facts = SpineSourceFacts(
            format = "FLAC", bitRateKbps = 2304, samplingRateHz = 96_000, bitDepth = 24,
            serverRating = 4, genre = "K-Pop", year = 2026, trackNumber = 3, discNumber = 2,
        )
        assertEquals(
            listOf(
                SpineItem("Quality", "FLAC  24-bit  96 kHz  2304 kbps"),
                SpineItem("Server rating", "★★★★☆"),
                SpineItem("Genre", "K-Pop"),
                SpineItem("Year", "2026"),
                SpineItem("Track", "Disc 2, track 3"),
            ),
            items(emptyList(), facts),
        )
    }

    @Test
    fun should_fallBackToAlbumArtistTitle_when_thereIsNothingElse() {
        val entries = listOf(row(SongAboutEntry.KIND_CANONICAL, SongAboutEntry.CANON_COMPOSER, "  "))
        assertEquals(listOf(SpineItem("", "Album   Artist   Title")), items(entries, SpineSourceFacts()))
    }

    @Test
    fun should_formatSamplingRates_when_givenHertz() {
        assertEquals("44.1", formatKhz(44_100))
        assertEquals("96", formatKhz(96_000))
        assertEquals("48", formatKhz(48_000))
    }
}
