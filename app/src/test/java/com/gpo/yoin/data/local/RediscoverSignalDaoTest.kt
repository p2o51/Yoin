package com.gpo.yoin.data.local

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.gpo.yoin.data.model.MediaId
import com.gpo.yoin.testutil.MainDispatcherRule
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Rediscover's read-only memory queries: per-album track-note and
 * track-rating aggregates (albums found through play history) and the
 * rated / noted songs not played since a cutoff. Every query is scoped by
 * profile and provider.
 */
@RunWith(RobolectricTestRunner::class)
class RediscoverSignalDaoTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private lateinit var database: YoinDatabase
    private lateinit var plays: PlayHistoryDao
    private lateinit var notes: SongNoteDao
    private lateinit var ratings: LocalRatingDao

    @Before
    fun setUp() {
        database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            YoinDatabase::class.java,
        )
            .allowMainThreadQueries()
            .build()
        plays = database.playHistoryDao()
        notes = database.songNoteDao()
        ratings = database.localRatingDao()
    }

    @After
    fun tearDown() {
        database.close()
    }

    // ── Per-album aggregates ──────────────────────────────────────────────

    @Test
    fun should_groupNonBlankNotesByPlayedAlbum_when_notedAlbumsAggregated() = runTest {
        plays.insert(play("a1", albumId = "album-1", playedAt = 10L))
        plays.insert(play("a1", albumId = "album-1", playedAt = 20L))
        plays.insert(play("a2", albumId = "album-1", playedAt = 30L))
        plays.insert(play("b1", albumId = "album-2", playedAt = 40L))
        plays.insert(play("loose", albumId = "", playedAt = 50L))
        notes.insert(note("n1", trackId = "a1", content = "first", updatedAt = 100L))
        notes.insert(note("n2", trackId = "a1", content = "   ", updatedAt = 999L))
        notes.insert(note("n3", trackId = "a2", content = "second", updatedAt = 300L))
        notes.insert(note("n4", trackId = "b1", content = "third", updatedAt = 200L))
        // No album in history, never played, another profile, another provider: none count.
        notes.insert(note("n5", trackId = "loose", content = "kept", updatedAt = 400L))
        notes.insert(note("n6", trackId = "unplayed", content = "kept", updatedAt = 500L))
        notes.insert(note("n7", trackId = "a1", content = "kept", updatedAt = 600L, profileId = "profile-b"))
        notes.insert(
            note("n8", trackId = "a1", content = "kept", updatedAt = 700L, provider = MediaId.PROVIDER_SPOTIFY),
        )

        val rows = notes.getNotedAlbumAggregates(MediaId.PROVIDER_SUBSONIC, "profile-a", limit = 10)

        assertEquals(
            listOf(
                AlbumTrackSignalAggregate(albumId = "album-1", signalCount = 2, lastWrittenAt = 300L),
                AlbumTrackSignalAggregate(albumId = "album-2", signalCount = 1, lastWrittenAt = 200L),
            ),
            rows,
        )
    }

    @Test
    fun should_countRatedTracksPerPlayedAlbum_when_ratedAlbumsAggregated() = runTest {
        plays.insert(play("a1", albumId = "album-1", playedAt = 10L))
        plays.insert(play("a1", albumId = "album-1", playedAt = 20L))
        plays.insert(play("a2", albumId = "album-1", playedAt = 30L))
        plays.insert(play("b1", albumId = "album-2", playedAt = 40L))
        ratings.upsert(rating("a1", 8f, updatedAt = 100L))
        // A cleared rating is no memory.
        ratings.upsert(rating("a2", 0f, updatedAt = 500L))
        ratings.upsert(rating("b1", 7f, updatedAt = 200L))
        ratings.upsert(rating("a2", 9f, updatedAt = 900L, profileId = "profile-b"))
        ratings.upsert(rating("unplayed", 9f, updatedAt = 900L))

        val rows = ratings.getRatedAlbumAggregates(MediaId.PROVIDER_SUBSONIC, "profile-a", limit = 10)

        assertEquals(
            listOf(
                AlbumTrackSignalAggregate(albumId = "album-2", signalCount = 1, lastWrittenAt = 200L),
                AlbumTrackSignalAggregate(albumId = "album-1", signalCount = 1, lastWrittenAt = 100L),
            ),
            rows,
        )
    }

    @Test
    fun should_orderAlbumsBySignalCount_when_limitBites() = runTest {
        plays.insert(play("a1", albumId = "album-1", playedAt = 10L))
        plays.insert(play("b1", albumId = "album-2", playedAt = 20L))
        plays.insert(play("b2", albumId = "album-2", playedAt = 30L))
        ratings.upsert(rating("a1", 8f, updatedAt = 900L))
        ratings.upsert(rating("b1", 8f, updatedAt = 100L))
        ratings.upsert(rating("b2", 8f, updatedAt = 100L))

        val rows = ratings.getRatedAlbumAggregates(MediaId.PROVIDER_SUBSONIC, "profile-a", limit = 1)

        assertEquals(listOf("album-2"), rows.map(AlbumTrackSignalAggregate::albumId))
    }

    // ── Rediscover songs ─────────────────────────────────────────────────

    @Test
    fun should_returnRatedAndNotedSongsAwaySinceCutoff_when_songsRead() = runTest {
        plays.insert(play("rated", albumId = "album-1", playedAt = NOW - 200 * DAY, title = "Rated"))
        plays.insert(play("rated", albumId = "album-1", playedAt = NOW - 100 * DAY, title = "Rated"))
        plays.insert(play("noted", albumId = "", playedAt = NOW - 300 * DAY, title = "Noted"))
        plays.insert(play("recent", albumId = "album-1", playedAt = NOW - 10 * DAY))
        plays.insert(play("recent", albumId = "album-1", playedAt = NOW - 400 * DAY))
        plays.insert(play("plain", albumId = "album-1", playedAt = NOW - 300 * DAY))
        plays.insert(play("zero", albumId = "album-1", playedAt = NOW - 300 * DAY))
        plays.insert(play("blank", albumId = "album-1", playedAt = NOW - 300 * DAY))
        plays.insert(play("elsewhere", albumId = "album-1", playedAt = NOW - 300 * DAY, profileId = "profile-b"))
        ratings.upsert(rating("rated", 9f, updatedAt = 1L))
        ratings.upsert(rating("recent", 8f, updatedAt = 1L))
        ratings.upsert(rating("zero", 0f, updatedAt = 1L))
        ratings.upsert(rating("elsewhere", 9f, updatedAt = 1L, profileId = "profile-b"))
        notes.insert(note("n1", trackId = "noted", content = "older words", updatedAt = 100L))
        notes.insert(note("n2", trackId = "noted", content = "newest words", updatedAt = 300L))
        notes.insert(note("n3", trackId = "noted", content = "  ", updatedAt = 900L))
        notes.insert(note("n4", trackId = "blank", content = "   ", updatedAt = 100L))

        val songs = plays.getRediscoverSongs(
            profileId = "profile-a",
            provider = MediaId.PROVIDER_SUBSONIC,
            playedBefore = NOW - 90 * DAY,
            limit = 10,
        )

        assertEquals(listOf("rated", "noted"), songs.map(SongMemoryAggregate::songId))
        val rated = songs[0]
        assertEquals(9f, rated.rating ?: 0f, 0f)
        assertEquals(2, rated.playCount)
        assertEquals(NOW - 200 * DAY, rated.firstPlayedAt)
        assertEquals(NOW - 100 * DAY, rated.lastPlayedAt)
        assertEquals("Rated", rated.title)
        assertEquals("album-1", rated.albumId)
        assertEquals(0, rated.noteCount)
        assertNull(rated.latestNote)
        val noted = songs[1]
        assertNull(noted.rating)
        assertEquals(2, noted.noteCount)
        assertEquals("newest words", noted.latestNote)
        assertEquals("", noted.albumId)
    }

    @Test
    fun should_orderSongsLikeRediscover_when_limitBites() = runTest {
        plays.insert(play("noted-oldest", albumId = "x", playedAt = NOW - 900 * DAY))
        plays.insert(play("seven-old", albumId = "x", playedAt = NOW - 400 * DAY))
        plays.insert(play("nine", albumId = "x", playedAt = NOW - 100 * DAY))
        plays.insert(play("seven-older", albumId = "x", playedAt = NOW - 500 * DAY))
        notes.insert(note("n1", trackId = "noted-oldest", content = "kept", updatedAt = 1L))
        ratings.upsert(rating("seven-old", 7f, updatedAt = 1L))
        ratings.upsert(rating("nine", 9f, updatedAt = 1L))
        ratings.upsert(rating("seven-older", 7f, updatedAt = 1L))

        val songs = plays.getRediscoverSongs("profile-a", MediaId.PROVIDER_SUBSONIC, NOW - 90 * DAY, limit = 3)

        // Rated first, best first, then longest away; the note-only song falls past the limit.
        assertEquals(listOf("nine", "seven-older", "seven-old"), songs.map(SongMemoryAggregate::songId))
    }

    private fun play(
        songId: String,
        albumId: String,
        playedAt: Long,
        title: String = "Song $songId",
        profileId: String = "profile-a",
        provider: String = MediaId.PROVIDER_SUBSONIC,
    ): PlayHistory = PlayHistory(
        songId = songId,
        profileId = profileId,
        provider = provider,
        title = title,
        artist = "Artist",
        album = "Album $albumId",
        albumId = albumId,
        coverArtId = null,
        playedAt = playedAt,
        durationMs = 180_000L,
        completedPercent = 1f,
    )

    private fun note(
        id: String,
        trackId: String,
        content: String,
        updatedAt: Long,
        profileId: String = "profile-a",
        provider: String = MediaId.PROVIDER_SUBSONIC,
    ): SongNote = SongNote(
        id = id,
        profileId = profileId,
        trackId = trackId,
        provider = provider,
        content = content,
        createdAt = updatedAt,
        updatedAt = updatedAt,
        title = "Song $trackId",
        artist = "Artist",
    )

    private fun rating(
        songId: String,
        value: Float,
        updatedAt: Long,
        profileId: String = "profile-a",
    ): LocalRating = LocalRating(
        profileId = profileId,
        songId = songId,
        provider = MediaId.PROVIDER_SUBSONIC,
        rating = value,
        serverRating = 0,
        updatedAt = updatedAt,
    )

    private companion object {
        const val NOW = 1_800_000_000_000L
        const val DAY = 24L * 60 * 60 * 1000
    }
}
