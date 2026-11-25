package com.gpo.yoin.data.album

import com.gpo.yoin.data.local.AlbumAboutEntry
import com.gpo.yoin.data.model.Album
import com.gpo.yoin.data.model.MediaId
import com.gpo.yoin.data.model.ReleaseDate
import com.gpo.yoin.data.model.ReleaseType
import com.gpo.yoin.data.model.Track
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AlbumAboutContentTest {

    private fun row(kind: String, key: String, answer: String, createdAt: Long = 1L, prompt: String? = null) =
        AlbumAboutEntry(
            provider = "subsonic",
            albumId = "al-1",
            albumDisplay = "A",
            artistDisplay = "B",
            kind = kind,
            entryKey = key,
            promptText = prompt,
            titleText = null,
            answerText = answer,
            createdAt = createdAt,
            updatedAt = createdAt,
        )

    @Test
    fun should_roundTripTheCredits_when_storedAsJson() {
        val credits = AlbumAiCredits(producers = listOf("A"), recordedTime = "1975", source = "Akira")

        assertEquals(credits, AlbumAiCredits.decode(credits.encode()))
        assertEquals(AlbumAiCredits.None, AlbumAiCredits.decode("not json"))
        assertEquals(AlbumAiCredits.None, AlbumAiCredits.decode(""))
    }

    @Test
    fun should_readTheRowsAsThePageShowsThem() {
        val content = AlbumAboutContent.from(
            listOf(
                row(AlbumAboutEntry.KIND_CANONICAL, AlbumAboutEntry.CANON_CREDITS, AlbumAiCredits(producers = listOf("P")).encode()),
                row(AlbumAboutEntry.KIND_CANONICAL, AlbumAboutEntry.CANON_BLURB, "  The paragraph.  "),
                row(AlbumAboutEntry.KIND_ASK, "old", "Old answer", createdAt = 1L, prompt = "Old?"),
                row(AlbumAboutEntry.KIND_ASK, "new", "New answer", createdAt = 5L, prompt = "New?"),
                row(AlbumAboutEntry.KIND_ASK, "gone", " ", createdAt = 9L, prompt = "Gone?"),
            ),
        )

        assertEquals(listOf("P"), content.credits.producers)
        assertEquals("The paragraph.", content.blurb)
        assertEquals(listOf("New?", "Old?"), content.asks.map { it.question })
        assertTrue(content.generated)
    }

    @Test
    fun should_showNoParagraph_when_theAiWroteNothing() {
        val content = AlbumAboutContent.from(listOf(row(AlbumAboutEntry.KIND_CANONICAL, AlbumAboutEntry.CANON_BLURB, "")))

        assertNull(content.blurb)
        assertTrue(content.generated)
        assertFalse(AlbumAboutContent.from(emptyList()).generated)
    }

    @Test
    fun should_groundInTheReleasesOwnFacts() {
        val album = Album(
            id = MediaId.subsonic("al-1"),
            name = "Tide Tables (Deluxe)",
            artist = "Morikawa",
            artistId = null,
            coverArt = null,
            songCount = 2,
            durationSec = null,
            year = 2024,
            genre = "Folk",
            tracks = listOf(track("Dawn", 200), track(" ", 100), track("Glass", 300)),
            releaseType = ReleaseType.Album,
            version = "Deluxe",
            releaseDate = ReleaseDate(2024, 3, 8),
            recordLabels = listOf("NLR"),
            originalReleaseDate = ReleaseDate(2014, 5),
        )

        val grounding = album.toAboutGrounding()

        assertEquals("2024-03-08", grounding.releaseDate)
        assertEquals("2014-05", grounding.originalReleaseDate)
        assertEquals(listOf("NLR"), grounding.labels)
        assertEquals(listOf("Dawn", "Glass"), grounding.tracks)
        assertEquals(600, grounding.totalDurationSec)
        assertEquals("Album", grounding.releaseType)
    }

    private fun track(title: String, seconds: Int) = Track(
        id = MediaId.subsonic(title),
        title = title,
        artist = "Morikawa",
        artistId = null,
        album = "Tide Tables",
        albumId = null,
        coverArt = null,
        durationSec = seconds,
        trackNumber = null,
        year = null,
        genre = null,
        userRating = null,
    )
}
