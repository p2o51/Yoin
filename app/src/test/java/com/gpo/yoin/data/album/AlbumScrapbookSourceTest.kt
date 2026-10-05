package com.gpo.yoin.data.album

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.cash.turbine.test
import com.gpo.yoin.data.local.AlbumNote
import com.gpo.yoin.data.local.LocalRating
import com.gpo.yoin.data.local.MemoryCopyCache
import com.gpo.yoin.data.local.PlayHistory
import com.gpo.yoin.data.local.SongAboutEntry
import com.gpo.yoin.data.local.SongNote
import com.gpo.yoin.data.local.YoinDatabase
import com.gpo.yoin.data.model.MediaId
import com.gpo.yoin.testutil.MainDispatcherRule
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class AlbumScrapbookSourceTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private lateinit var database: YoinDatabase
    private val profileId = MutableStateFlow<String?>("profile-a")
    private val sub = MediaId.PROVIDER_SUBSONIC

    @Before
    fun setUp() {
        database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            YoinDatabase::class.java,
        )
            .allowMainThreadQueries()
            .build()
    }

    @After
    fun tearDown() {
        database.close()
    }

    // ── DAO queries ────────────────────────────────────────────────────

    @Test
    fun should_returnOnlyThisProfileAndProvider_when_observingRatings() = runTest {
        val dao = database.localRatingDao()
        dao.upsert(rating("t1", 8f))
        dao.upsert(rating("t2", 6f))
        dao.upsert(rating("t1", 3f, profile = "profile-b"))
        dao.upsert(rating("t1", 5f, provider = MediaId.PROVIDER_SPOTIFY))
        dao.upsert(rating("t9", 9f))

        val rows = dao.observeRatings(listOf("t1", "t2"), sub, "profile-a").first()

        assertEquals(mapOf("t1" to 8f, "t2" to 6f), rows.associate { it.songId to it.rating })
    }

    @Test
    fun should_returnOnlyThisProfileAndProvider_when_observingNotesForTracks() = runTest {
        val dao = database.songNoteDao()
        dao.insert(note("n1", "t1"))
        dao.insert(note("n2", "t2"))
        dao.insert(note("n3", "t1", profile = "profile-b"))
        dao.insert(note("n4", "t1", provider = MediaId.PROVIDER_SPOTIFY))
        dao.insert(note("n5", "t3"))

        val rows = dao.observeForTracks(listOf("t1", "t2"), sub, "profile-a").first()

        assertEquals(setOf("n1", "n2"), rows.map(SongNote::id).toSet())
    }

    @Test
    fun should_countPlaysPerSong_when_observingSongPlayCounts() = runTest {
        val dao = database.playHistoryDao()
        repeat(3) { dao.insert(play("t1", playedAt = 100L + it)) }
        dao.insert(play("t2", playedAt = 50L))
        dao.insert(play("t1", playedAt = 70L, profile = "profile-b"))
        dao.insert(play("t1", playedAt = 70L, provider = MediaId.PROVIDER_SPOTIFY))

        val rows = dao.observeSongPlayCounts(listOf("t1", "t2", "t3"), sub, "profile-a").first()

        assertEquals(mapOf("t1" to 3, "t2" to 1), rows.associate { it.songId to it.playCount })
    }

    @Test
    fun should_aggregateAlbumPlays_when_observingAlbumPlayStats() = runTest {
        val dao = database.playHistoryDao()
        dao.insert(play("t1", playedAt = 300L))
        dao.insert(play("t2", playedAt = 100L))
        dao.insert(play("t1", playedAt = 999L, albumId = "other"))
        dao.insert(play("t1", playedAt = 10L, profile = "profile-b"))

        val stats = dao.observeAlbumPlayStats("al-1", sub, "profile-a").first()
        val none = dao.observeAlbumPlayStats("al-none", sub, "profile-a").first()

        assertEquals(2, stats.playCount)
        assertEquals(100L, stats.firstPlayedAt)
        assertEquals(300L, stats.lastPlayedAt)
        assertEquals(0, none.playCount)
        assertNull(none.lastPlayedAt)
    }

    @Test
    fun should_returnEveryRowOfTheAlbumKey_when_observingAboutByAlbum() = runTest {
        val dao = database.songAboutEntryDao()
        dao.upsert(about("song one", "artist", "album", SongAboutEntry.KIND_ASK, "why", updatedAt = 1L))
        dao.upsert(about("song two", "artist", "album", SongAboutEntry.KIND_CANONICAL, SongAboutEntry.CANON_PRODUCER, updatedAt = 2L))
        dao.upsert(about("song one", "artist", "another album", SongAboutEntry.KIND_ASK, "why", updatedAt = 3L))

        val rows = dao.observeByAlbum("album").first()

        assertEquals(listOf("song two", "song one"), rows.map(SongAboutEntry::titleKey))
    }

    // ── Source ─────────────────────────────────────────────────────────

    @Test
    fun should_assembleTheAlbumsData_when_observed() = runTest {
        database.localRatingDao().upsert(rating("t1", 8.5f))
        database.localRatingDao().upsert(rating("t2", 0f)) // stored 0 = not rated
        database.songNoteDao().insert(note("late", "t1", positionMs = 90_000L, createdAt = 1L))
        database.songNoteDao().insert(note("early", "t1", positionMs = 10_000L, createdAt = 2L))
        database.songNoteDao().insert(note("loose", "t1", positionMs = null, createdAt = 0L))
        database.songNoteDao().insert(note("blank", "t2", content = "   "))
        database.playHistoryDao().insert(play("t1", playedAt = 500L))
        database.songAboutEntryDao().upsert(
            about("song 2", "artist", "album", SongAboutEntry.KIND_ASK, "who sings", answer = "She does."),
        )
        database.memoryCopyCacheDao().upsert(
            MemoryCopyCache(
                profileId = "profile-a",
                provider = sub,
                entityType = MemoryCopyCache.ENTITY_ALBUM,
                entityId = "al-1",
                copy = "",
                promptHash = "",
                title = "A title",
            ),
        )

        val data = source().observe(query()).first()

        assertEquals(mapOf(id("t1") to 8.5f), data.ratings)
        assertEquals(listOf("early", "late", "loose"), data.notes[id("t1")]?.map(AlbumScrapbookNote::id))
        assertTrue(id("t2") !in data.notes)
        assertEquals(mapOf(id("t1") to 1), data.playCounts)
        assertEquals(AlbumScrapbookPlays(count = 1, firstPlayedAt = 500L, lastPlayedAt = 500L), data.plays)
        assertEquals("She does.", data.about[id("t2")]?.asks?.single()?.answer)
        assertEquals("A title", data.memoryTitle)
        assertNull(data.albumNote)
    }

    @Test
    fun should_readOnlyTheLatestAlbumNote_when_albumHasSeveral() = runTest {
        val dao = database.albumNoteDao()
        dao.insert(albumNote("first", createdAt = 1L, updatedAt = 1L))
        dao.insert(albumNote("latest", content = "  The latest words  ", createdAt = 2L, updatedAt = 30L))
        dao.insert(albumNote("middle", createdAt = 3L, updatedAt = 20L))
        // newer rows the page must not pick: blank, another profile, another provider, another album
        dao.insert(albumNote("blank", content = "   ", createdAt = 4L, updatedAt = 90L))
        dao.insert(albumNote("other-profile", profile = "profile-b", createdAt = 5L, updatedAt = 91L))
        dao.insert(albumNote("other-provider", provider = MediaId.PROVIDER_SPOTIFY, createdAt = 6L, updatedAt = 92L))
        dao.insert(albumNote("other-album", albumId = "al-2", createdAt = 7L, updatedAt = 93L))

        val note = source().observe(query()).first().albumNote

        assertEquals(AlbumScrapbookAlbumNote(id = "latest", content = "The latest words", updatedAt = 30L), note)
    }

    @Test
    fun should_reemit_when_anAlbumNoteChanges() = runTest {
        database.albumNoteDao().insert(albumNote("a", content = "Before", updatedAt = 1L))

        source().observe(query()).test {
            assertEquals("Before", awaitItem().albumNote?.content)
            database.albumNoteDao().update(albumNote("a", content = "After", updatedAt = 2L))
            assertEquals("After", awaitItem().albumNote?.content)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun should_keepTheEarlierCreatedNote_when_latestAlbumNotesTie() {
        // rows arrive in the DAO's createdAt order, as the Memories diary reads them
        val rows = listOf(
            albumNote("older", createdAt = 1L, updatedAt = 5L),
            albumNote("newer", createdAt = 2L, updatedAt = 5L),
        )

        assertEquals("older", latestAlbumNote(rows)?.id)
        assertNull(latestAlbumNote(listOf(albumNote("blank", content = " "))))
        assertNull(latestAlbumNote(emptyList()))
    }

    @Test
    fun should_reemit_when_aScoreChangesElsewhere() = runTest {
        val source = source()
        source.observe(query()).test {
            assertEquals(emptyMap<MediaId, Float>(), awaitItem().ratings)
            database.localRatingDao().upsert(rating("t2", 9f))
            assertEquals(mapOf(id("t2") to 9f), awaitItem().ratings)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun should_emitEmpty_when_noProfileIsActive() = runTest {
        database.localRatingDao().upsert(rating("t1", 8f))
        profileId.value = null

        assertEquals(AlbumScrapbookData.Empty, source().observe(query()).first())
    }

    @Test
    fun should_matchAboutRowsByTitleAndArtist_when_mappingToTracks() {
        val tracks = listOf(
            AlbumScrapbookTrackKey(id("t1"), "Fake Love (feat. X)", "BTS"),
            AlbumScrapbookTrackKey(id("t2"), "Euphoria", "BTS"),
        )
        val rows = listOf(
            about("fake love", "bts", "album", SongAboutEntry.KIND_ASK, "q1", answer = "a1"),
            // artist spelt differently: falls back to the only track with this title
            about("euphoria", "jungkook", "album", SongAboutEntry.KIND_CANONICAL, SongAboutEntry.CANON_LYRICIST, answer = "RM"),
            // blank canonical rows are not facts
            about("euphoria", "bts", "album", SongAboutEntry.KIND_CANONICAL, SongAboutEntry.CANON_COMPOSER, answer = " "),
            // another album's song with the same album name
            about("ghost", "someone", "album", SongAboutEntry.KIND_ASK, "q2", answer = "a2"),
        )

        val matched = matchAboutToTracks(rows, tracks)

        assertEquals(setOf(id("t1"), id("t2")), matched.keys)
        assertEquals("a1", matched[id("t1")]?.asks?.single()?.answer)
        assertEquals(listOf(AlbumScrapbookFact(SongAboutEntry.CANON_LYRICIST, "RM")), matched[id("t2")]?.facts)
    }

    @Test
    fun should_orderNotesAlongTheSong_when_sortingTimeline() {
        val notes = listOf(
            AlbumScrapbookNote("c", "c", null, createdAt = 1L),
            AlbumScrapbookNote("b", "b", 20L, createdAt = 9L),
            AlbumScrapbookNote("a", "a", 10L, createdAt = 5L),
            AlbumScrapbookNote("d", "d", null, createdAt = 0L),
        )

        assertEquals(listOf("a", "b", "d", "c"), notes.timelineOrder().map { it.id })
    }

    private fun source() = AlbumScrapbookSource.from(database, profileId)

    private fun query() = AlbumScrapbookQuery(
        albumId = MediaId(sub, "al-1"),
        albumName = "Album",
        tracks = listOf(
            AlbumScrapbookTrackKey(id("t1"), "Song 1", "Artist"),
            AlbumScrapbookTrackKey(id("t2"), "Song 2", "Artist"),
        ),
    )

    private fun id(raw: String) = MediaId(sub, raw)

    private fun rating(
        songId: String,
        value: Float,
        profile: String = "profile-a",
        provider: String = sub,
    ) = LocalRating(profileId = profile, songId = songId, provider = provider, rating = value, serverRating = 0)

    private fun note(
        id: String,
        trackId: String,
        profile: String = "profile-a",
        provider: String = sub,
        content: String = "note $id",
        positionMs: Long? = null,
        createdAt: Long = 1L,
    ) = SongNote(
        id = id,
        profileId = profile,
        trackId = trackId,
        provider = provider,
        content = content,
        createdAt = createdAt,
        updatedAt = createdAt,
        title = "Song",
        artist = "Artist",
        positionMs = positionMs,
    )

    private fun play(
        songId: String,
        playedAt: Long,
        albumId: String = "al-1",
        profile: String = "profile-a",
        provider: String = sub,
    ) = PlayHistory(
        songId = songId,
        profileId = profile,
        provider = provider,
        title = "Song",
        artist = "Artist",
        album = "Album",
        albumId = albumId,
        coverArtId = null,
        playedAt = playedAt,
        durationMs = 200_000L,
        completedPercent = 1f,
    )

    private fun albumNote(
        id: String,
        content: String = "album note $id",
        albumId: String = "al-1",
        profile: String = "profile-a",
        provider: String = sub,
        createdAt: Long = 1L,
        updatedAt: Long = createdAt,
    ) = AlbumNote(
        id = id,
        profileId = profile,
        albumId = albumId,
        provider = provider,
        content = content,
        createdAt = createdAt,
        updatedAt = updatedAt,
        albumName = "Album",
        artist = "Artist",
    )

    private fun about(
        title: String,
        artist: String,
        album: String,
        kind: String,
        entryKey: String,
        answer: String = "answer",
        updatedAt: Long = 1L,
    ) = SongAboutEntry(
        titleKey = SongAboutEntry.normalize(title),
        artistKey = SongAboutEntry.normalize(artist),
        albumKey = SongAboutEntry.normalize(album),
        titleDisplay = title,
        artistDisplay = artist,
        albumDisplay = album,
        kind = kind,
        entryKey = entryKey,
        promptText = if (kind == SongAboutEntry.KIND_ASK) entryKey else null,
        titleText = null,
        answerText = answer,
        createdAt = updatedAt,
        updatedAt = updatedAt,
    )
}
