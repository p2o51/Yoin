package com.gpo.yoin.ui.home

import com.gpo.yoin.data.model.MediaId
import com.gpo.yoin.data.model.Track
import com.gpo.yoin.ui.memories.MemoryScoreKind
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Rediscover's card copy: the away text, the eyebrow and the footnote. */
class RediscoverCopyTest {

    private val now = 1_800_000_000_000L
    private val day = 24L * 60 * 60 * 1000

    private fun away(days: Long): String = rediscoverAwayText(lastPlayedAt = now - days * day, nowMillis = now)

    @Test
    fun should_countMonths_when_awayUnderAYear() {
        assertEquals("3 months", away(90))
        assertEquals("7 months", away(214))
        assertEquals("12 months", away(364))
    }

    @Test
    fun should_countYears_when_awayAYearOrMore() {
        assertEquals("1 year", away(365))
        assertEquals("1 year", away(729))
        assertEquals("2 years", away(730))
    }

    @Test
    fun should_countWholeDays_when_lastPlayIsPartWayThroughADay() {
        // 89 days and 23 hours is still 89 whole days: 2 months, not 3.
        assertEquals("2 months", rediscoverAwayText(now - 90 * day + 60 * 60 * 1000, now))
    }

    @Test
    fun should_formatScoreWithDot_when_defaultLocaleUsesComma() {
        val original = Locale.getDefault()
        Locale.setDefault(Locale.GERMANY)
        try {
            val item = item(score = 9f, lastPlayedAt = now - 214 * day)

            assertEquals("9.0", item.scoreText)
            assertEquals("Your rating 9.0", rediscoverBadgeDescription(item.scoreText!!, item.scoreKind))
        } finally {
            Locale.setDefault(original)
        }
    }

    @Test
    fun should_leaveScoreToBadge_when_itemHasScore() {
        val item = item(score = 8.4f, lastPlayedAt = now - 214 * day).copy(hasReview = true, noteCount = 3)

        assertNull(rediscoverReason(item))
        assertEquals("7 months away", rediscoverEyebrow(item, now))
    }

    @Test
    fun should_leadWithTheMemory_when_itemHasNoScore() {
        val unscored = item(score = null, lastPlayedAt = now - 214 * day)

        val reviewed = unscored.copy(hasReview = true, noteCount = 3, ratedTrackCount = 2)
        assertEquals("Reviewed", rediscoverReason(reviewed))
        assertNull(rediscoverNoteCount(reviewed))
        assertEquals("7 months away", rediscoverEyebrow(reviewed, now))

        val notes = unscored.copy(noteCount = 3)
        assertEquals("3 notes", rediscoverReason(notes))
        assertEquals(3, rediscoverNoteCount(notes))
        assertEquals("7 months away", rediscoverEyebrow(notes, now))

        val oneNote = unscored.copy(noteCount = 1)
        assertEquals("1 note", rediscoverReason(oneNote))
        assertEquals(1, rediscoverNoteCount(oneNote))
        assertEquals("7 months away", rediscoverEyebrow(oneNote, now))

        assertEquals("2 tracks rated", rediscoverReason(unscored.copy(ratedTrackCount = 2)))
        assertEquals("7 months away", rediscoverEyebrow(unscored.copy(ratedTrackCount = 2), now))
        assertEquals("1 track rated", rediscoverReason(unscored.copy(ratedTrackCount = 1)))
        assertEquals("7 months away", rediscoverEyebrow(unscored.copy(ratedTrackCount = 1), now))
        assertNull(rediscoverReason(unscored))
        assertEquals("7 months away", rediscoverEyebrow(unscored, now))
    }

    @Test
    fun should_nameTheScoreKind_when_describingBadge() {
        assertEquals("Your rating 9.0", rediscoverBadgeDescription("9.0", MemoryScoreKind.ALBUM_RATING))
        assertEquals("Track average 8.4", rediscoverBadgeDescription("8.4", MemoryScoreKind.AVERAGE_TRACK_RATING))
    }

    @Test
    fun should_pluralizePlays_when_formattingFootnote() {
        val firstPlayed = LocalDate.of(2025, 11, 20).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()

        val many = rediscoverFootnote(firstPlayed, 23, ZoneOffset.UTC)
        assertEquals("2025.11", many?.month)
        assertEquals(23, many?.playCount)
        assertEquals("plays", rediscoverPlayUnit(23))
        val one = rediscoverFootnote(firstPlayed, 1, ZoneOffset.UTC)
        assertEquals("2025.11", one?.month)
        assertEquals(1, one?.playCount)
        assertEquals("play", rediscoverPlayUnit(1))
    }

    @Test
    fun should_useLocalMonth_when_firstPlayCrossesMidnight() {
        // 2025-11-30 23:30 UTC is already December in Tokyo.
        val firstPlayed = LocalDate.of(2025, 11, 30).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli() +
            (23 * 60 + 30) * 60 * 1000L

        val footnote = rediscoverFootnote(firstPlayed, 4, ZoneId.of("Asia/Tokyo"))
        assertEquals("2025.12", footnote?.month)
        assertEquals(4, footnote?.playCount)
    }

    @Test
    fun should_dropMissingHalves_when_historyIsPartial() {
        val playsOnly = rediscoverFootnote(null, 5)
        assertNull(playsOnly?.month)
        assertEquals(5, playsOnly?.playCount)
        assertEquals("plays", rediscoverPlayUnit(5))
        assertNull(rediscoverFootnote(null, 0))
    }

    @Test
    fun should_leaveTheMemoryToTheSnippet_when_itemIsASong() {
        val noted = song(score = null).copy(noteCount = 3, noteSnippet = "kept")

        assertNull(rediscoverReason(noted))
        assertEquals("7 months away", rediscoverEyebrow(noted, now))
    }

    @Test
    fun should_joinArtistAndAlbum_when_formattingSongSubtitle() {
        assertEquals(RediscoverSongSubtitle("Ena", "Long Way Round"), rediscoverSongSubtitle(song(score = 8f)))
        assertEquals(RediscoverSongSubtitle("Ena", null), rediscoverSongSubtitle(song(score = 8f).copy(albumName = "")))
        assertEquals(
            RediscoverSongSubtitle(null, "Long Way Round"),
            rediscoverSongSubtitle(song(score = 8f).copy(artistName = null)),
        )
        assertNull(rediscoverSongSubtitle(song(score = 8f).copy(artistName = " ", albumName = "")))
    }

    @Test
    fun should_titleAndKeyBySong_when_itemIsASong() {
        val song = song(score = 8f)
        val album = item(score = 8f, lastPlayedAt = now)

        assertEquals("Paper Kites", song.title)
        assertEquals("Emotion", album.title)
        assertEquals("rediscover-song:subsonic:s1", song.shelfKey)
        assertEquals("rediscover:subsonic:a1", album.shelfKey)
    }

    @Test
    fun should_mentionSongs_when_rediscoverPlaceholderShows() {
        assertEquals(
            "Nothing to rediscover yet",
            RediscoverPlaceholderText,
        )
    }

    private fun song(score: Float?) = HomeRediscoverItem(
        albumId = MediaId.subsonic("album-s1"),
        albumName = "Long Way Round",
        artistName = "Ena",
        coverArtUrl = null,
        score = score,
        scoreText = score?.let(::rediscoverScoreText),
        scoreKind = if (score == null) MemoryScoreKind.NONE else MemoryScoreKind.ALBUM_RATING,
        lastPlayedAt = now - 214 * day,
        firstPlayedAt = null,
        playCount = 0,
        song = Track(
            id = MediaId.subsonic("s1"),
            title = "Paper Kites",
            artist = "Ena",
            artistId = null,
            album = "Long Way Round",
            albumId = MediaId.subsonic("album-s1"),
            coverArt = null,
            durationSec = 200,
            trackNumber = null,
            year = null,
            genre = null,
            userRating = null,
        ),
    )

    private fun item(score: Float?, lastPlayedAt: Long) = HomeRediscoverItem(
        albumId = MediaId.subsonic("a1"),
        albumName = "Emotion",
        artistName = "Carly Rae Jepsen",
        coverArtUrl = null,
        score = score,
        scoreText = score?.let(::rediscoverScoreText),
        scoreKind = if (score == null) MemoryScoreKind.NONE else MemoryScoreKind.ALBUM_RATING,
        lastPlayedAt = lastPlayedAt,
        firstPlayedAt = null,
        playCount = 0,
    )
}
