package com.gpo.yoin.data.repository

import com.gpo.yoin.data.local.YoinDatabase
import com.gpo.yoin.data.local.AlbumNoteDao
import com.gpo.yoin.data.local.AlbumRatingDao
import com.gpo.yoin.data.local.GeminiConfigDao
import com.gpo.yoin.data.local.LocalRatingDao
import com.gpo.yoin.data.local.LyricsCacheDao
import com.gpo.yoin.data.local.MemoryCopyCacheDao
import com.gpo.yoin.data.local.GeminiConfig
import com.gpo.yoin.data.local.SongAboutEntry
import com.gpo.yoin.data.local.SongAboutEntryDao
import com.gpo.yoin.data.local.SongNoteDao
import com.gpo.yoin.data.integration.neodb.NeoDBSyncService
import com.gpo.yoin.data.model.MediaId
import com.gpo.yoin.data.model.Playlist
import com.gpo.yoin.data.model.PlaylistItemRef
import com.gpo.yoin.data.remote.GeminiService
import com.gpo.yoin.data.source.MusicLibrary
import com.gpo.yoin.data.source.MusicMetadata
import com.gpo.yoin.data.source.MusicPlayback
import com.gpo.yoin.data.source.MusicSource
import com.gpo.yoin.data.source.MusicWriteActions
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.coEvery
import io.mockk.slot
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class YoinRepositoryTest {
    private val library = mockk<MusicLibrary>()
    private val writeActions = FakeWriteActions()
    private val source = mockk<MusicSource>()
    private val database = mockk<YoinDatabase>(relaxed = true)
    private val localRatingDao = mockk<LocalRatingDao>(relaxed = true)
    private val geminiService = mockk<GeminiService>(relaxed = true)
    private val songAboutEntryDao = mockk<SongAboutEntryDao>(relaxed = true)
    private val geminiConfigDao = mockk<GeminiConfigDao>(relaxed = true)
    private val lyricsCacheDao = mockk<LyricsCacheDao>(relaxed = true)
    private val songNoteDao = mockk<SongNoteDao>(relaxed = true)
    private val albumNoteDao = mockk<AlbumNoteDao>(relaxed = true)
    private val albumRatingDao = mockk<AlbumRatingDao>(relaxed = true)
    private val memoryCopyCacheDao = mockk<MemoryCopyCacheDao>(relaxed = true)
    private val neoDbSyncService = mockk<NeoDBSyncService>(relaxed = true)

    private var currentTime: Long = 1_000L

    private val repository = YoinRepository(
        activeSource = MutableStateFlow(source),
        activeProfileId = MutableStateFlow("spotify-profile"),
        database = database,
        geminiService = geminiService,
        songAboutEntryDao = songAboutEntryDao,
        geminiConfigDao = geminiConfigDao,
        lyricsCacheDao = lyricsCacheDao,
        songNoteDao = songNoteDao,
        albumNoteDao = albumNoteDao,
        albumRatingDao = albumRatingDao,
        memoryCopyCacheDao = memoryCopyCacheDao,
        neoDbSyncService = neoDbSyncService,
        clock = { currentTime },
    )

    init {
        every { source.library() } returns library
        every { source.metadata() } returns mockk<MusicMetadata>()
        every { source.writeActions() } returns writeActions
        every { source.playback() } returns mockk<MusicPlayback>()
        every { database.localRatingDao() } returns localRatingDao
    }

    @Test
    fun should_returnTrue_when_pingSucceeds() = runTest {
        io.mockk.coEvery { library.ping() } returns true

        assertTrue(repository.testConnection())
    }

    @Test
    fun should_throwSubsonicException_when_pingReturnsAuthError() = runTest {
        io.mockk.coEvery { library.ping() } throws SubsonicException(
            code = 40,
            message = "Wrong username or password",
        )

        val error = try {
            repository.testConnection()
            null
        } catch (e: SubsonicException) {
            e
        }

        val subsonicError = requireNotNull(error)
        assertEquals(40, subsonicError.code)
        assertEquals("Wrong username or password", subsonicError.message)
    }

    @Test
    fun should_delegate_createPlaylist_to_writeActions_returning_new_playlist() = runTest {
        val created = Playlist(
            id = MediaId.spotify("pl1"),
            name = "Road Trip",
            owner = "alice",
            coverArt = null,
            songCount = 0,
            durationSec = null,
            canWrite = true,
        )
        writeActions.createPlaylistResult = Result.success(created)

        val result = repository.createPlaylist(name = "Road Trip")
        assertTrue(result.isSuccess)
        assertEquals(created, result.getOrNull())
        assertTrue(created.canWrite)
    }

    @Test
    fun should_delegate_addTracks_and_propagate_snapshotId() = runTest {
        val playlistId = MediaId.spotify("pl1")
        val trackIds = listOf(MediaId.spotify("t1"), MediaId.spotify("t2"))
        writeActions.addTracksResult = Result.success("snap-after")

        val result = repository.addTracksToPlaylist(playlistId, trackIds)
        assertTrue(result.isSuccess)
        assertEquals("snap-after", result.getOrNull())
        assertEquals(playlistId, writeActions.lastAddPlaylistId)
        assertEquals(trackIds, writeActions.lastAddedTracks)
    }

    @Test
    fun should_thread_snapshotId_into_removeTracks() = runTest {
        val playlistId = MediaId.spotify("pl1")
        val items = listOf(PlaylistItemRef(trackId = MediaId.spotify("t1"), position = 0))
        writeActions.removeTracksResult = Result.success("snap-after")

        val result = repository.removeTracksFromPlaylist(
            playlistId = playlistId,
            items = items,
            snapshotId = "snap-before",
        )
        assertTrue(result.isSuccess)
        assertEquals("snap-after", result.getOrNull())
        assertEquals(playlistId, writeActions.lastRemovePlaylistId)
        assertEquals(items, writeActions.lastRemovedItems)
        assertEquals("snap-before", writeActions.lastSnapshotId)
    }

    @Test
    fun subsonic_paths_return_null_snapshotId() = runTest {
        val subsonicPlaylist = MediaId.subsonic("42")
        val subsonicTracks = listOf(MediaId.subsonic("s1"))
        writeActions.addTracksResult = Result.success(null)

        val result = repository.addTracksToPlaylist(subsonicPlaylist, subsonicTracks)
        assertTrue(result.isSuccess)
        assertNull(result.getOrNull())
        assertEquals(subsonicPlaylist, writeActions.lastAddPlaylistId)
        assertEquals(subsonicTracks, writeActions.lastAddedTracks)
    }

    @Test
    fun should_map_ten_point_track_rating_back_to_subsonic_five_point_api() = runTest {
        val trackId = MediaId.subsonic("song-1")
        writeActions.setRatingResult = Result.success(Unit)
        every { source.id } returns MediaId.PROVIDER_SUBSONIC

        repository.setRating(trackId, 7.4f)

        assertEquals(trackId, writeActions.lastRatedTrackId)
        assertEquals(4, writeActions.lastServerRating)
        coVerify(exactly = 2) { localRatingDao.upsert(any()) }
    }

    @Test
    fun ensureCanonicalAbout_skips_gemini_when_canonical_rows_already_cached() = runTest {
        val existing = listOf(canonicalRow(SongAboutEntry.CANON_CREATION_TIME, "2024"))
        coEvery {
            songAboutEntryDao.getCanonical("fake love", "drake", "certified lover boy")
        } returns existing

        val result = repository.ensureCanonicalAbout(
            title = "Fake Love",
            artist = "Drake",
            album = "Certified Lover Boy",
        )

        assertTrue(result is YoinRepository.AboutLoadResult.Success)
        coVerify(exactly = 0) {
            geminiService.generateCanonicalAbout(any(), any(), any(), any())
        }
    }

    @Test
    fun ensureCanonicalAbout_fetches_and_persists_when_no_cache() = runTest {
        coEvery {
            songAboutEntryDao.getCanonical(any(), any(), any())
        } returns emptyList()
        coEvery { geminiConfigDao.getConfig() } returns flowOf(GeminiConfig(apiKey = "key"))
        coEvery {
            geminiService.generateCanonicalAbout("key", "Fake Love", "Drake", "Certified Lover Boy")
        } returns listOf(
            GeminiService.CanonicalAboutValue(SongAboutEntry.CANON_CREATION_TIME, "2024"),
            GeminiService.CanonicalAboutValue(SongAboutEntry.CANON_LYRICIST, "Aubrey Graham"),
        )
        val captured = slot<List<SongAboutEntry>>()
        coEvery { songAboutEntryDao.upsertAll(capture(captured)) } returns Unit

        currentTime = 5_000L
        val result = repository.ensureCanonicalAbout(
            title = "Fake Love",
            artist = "Drake",
            album = "Certified Lover Boy",
        )

        assertTrue(result is YoinRepository.AboutLoadResult.Success)
        assertEquals(2, captured.captured.size)
        val first = captured.captured.first()
        assertEquals("fake love", first.titleKey)
        assertEquals("drake", first.artistKey)
        assertEquals("certified lover boy", first.albumKey)
        assertEquals("Fake Love", first.titleDisplay)
        assertEquals(SongAboutEntry.KIND_CANONICAL, first.kind)
        assertEquals(5_000L, first.createdAt)
        assertEquals(5_000L, first.updatedAt)
    }

    @Test
    fun ensureCanonicalAbout_returns_ApiKeyMissing_when_key_blank() = runTest {
        coEvery { songAboutEntryDao.getCanonical(any(), any(), any()) } returns emptyList()
        coEvery { geminiConfigDao.getConfig() } returns flowOf(GeminiConfig(apiKey = "  "))

        val result = repository.ensureCanonicalAbout(
            title = "Fake Love",
            artist = "Drake",
            album = "Certified Lover Boy",
        )

        assertEquals(YoinRepository.AboutLoadResult.ApiKeyMissing, result)
    }

    @Test
    fun askAboutSong_normalizes_keys_and_persists_new_ask_row() = runTest {
        coEvery { geminiConfigDao.getConfig() } returns flowOf(GeminiConfig(apiKey = "key"))
        coEvery {
            geminiService.askAboutSong("key", "Fake Love", "Drake", "CLB", "What does the chorus mean?")
        } returns GeminiService.AskAnswer(
            title = "Chorus meaning",
            answer = "It's about betrayal.",
        )
        coEvery {
            songAboutEntryDao.getAsk("fake love", "drake", "clb", "what does the chorus mean?")
        } returns null
        val captured = slot<SongAboutEntry>()
        coEvery { songAboutEntryDao.upsert(capture(captured)) } returns Unit

        currentTime = 9_000L
        val result = repository.askAboutSong(
            title = "Fake Love",
            artist = "Drake",
            album = "CLB",
            question = "  What does the chorus mean?  ",
        )

        assertTrue(result is YoinRepository.AskAboutResult.Success)
        assertEquals("It's about betrayal.", (result as YoinRepository.AskAboutResult.Success).answer)
        val saved = captured.captured
        assertEquals("fake love", saved.titleKey)
        assertEquals("what does the chorus mean?", saved.entryKey)
        assertEquals("What does the chorus mean?", saved.promptText)
        assertEquals("Chorus meaning", saved.titleText)
        assertEquals(SongAboutEntry.KIND_ASK, saved.kind)
        assertEquals(9_000L, saved.createdAt)
        assertEquals(9_000L, saved.updatedAt)
    }

    @Test
    fun askAboutSong_reask_preserves_createdAt_but_refreshes_updatedAt() = runTest {
        coEvery { geminiConfigDao.getConfig() } returns flowOf(GeminiConfig(apiKey = "key"))
        coEvery {
            geminiService.askAboutSong(any(), any(), any(), any(), any())
        } returns GeminiService.AskAnswer(
            title = "Refined title",
            answer = "Refined answer.",
        )
        coEvery {
            songAboutEntryDao.getAsk("fake love", "drake", "clb", "what is this song aiming for?")
        } returns askRow(
            question = "What is this song aiming for?",
            answer = "Old answer.",
            createdAt = 1_000L,
            updatedAt = 1_500L,
        )
        val captured = slot<SongAboutEntry>()
        coEvery { songAboutEntryDao.upsert(capture(captured)) } returns Unit

        currentTime = 9_000L
        repository.askAboutSong(
            title = "Fake Love",
            artist = "Drake",
            album = "CLB",
            question = "What is this song aiming for?",
        )

        val saved = captured.captured
        assertEquals(1_000L, saved.createdAt)
        assertEquals(9_000L, saved.updatedAt)
        assertEquals("Refined answer.", saved.answerText)
    }

    @Test
    fun askAboutSong_rejects_blank_question_without_calling_gemini() = runTest {
        val result = repository.askAboutSong(
            title = "Fake Love",
            artist = "Drake",
            album = "CLB",
            question = "   ",
        )

        assertTrue(result is YoinRepository.AskAboutResult.Error)
        coVerify(exactly = 0) { geminiService.askAboutSong(any(), any(), any(), any(), any()) }
    }

    private fun canonicalRow(entryKey: String, answer: String) = SongAboutEntry(
        titleKey = "fake love",
        artistKey = "drake",
        albumKey = "certified lover boy",
        titleDisplay = "Fake Love",
        artistDisplay = "Drake",
        albumDisplay = "Certified Lover Boy",
        kind = SongAboutEntry.KIND_CANONICAL,
        entryKey = entryKey,
        promptText = null,
        titleText = null,
        answerText = answer,
        createdAt = 1L,
        updatedAt = 1L,
    )

    private fun askRow(
        question: String,
        answer: String,
        createdAt: Long,
        updatedAt: Long,
    ) = SongAboutEntry(
        titleKey = "fake love",
        artistKey = "drake",
        albumKey = "clb",
        titleDisplay = "Fake Love",
        artistDisplay = "Drake",
        albumDisplay = "CLB",
        kind = SongAboutEntry.KIND_ASK,
        entryKey = SongAboutEntry.normalize(question),
        promptText = question,
        titleText = null,
        answerText = answer,
        createdAt = createdAt,
        updatedAt = updatedAt,
    )

    private class FakeWriteActions : MusicWriteActions {
        var setRatingResult: Result<Unit> =
            Result.failure(UnsupportedOperationException("setRating not configured"))
        var createPlaylistResult: Result<Playlist> =
            Result.failure(UnsupportedOperationException("createPlaylist not configured"))
        var addTracksResult: Result<String?> =
            Result.failure(UnsupportedOperationException("addTracksToPlaylist not configured"))
        var removeTracksResult: Result<String?> =
            Result.failure(UnsupportedOperationException("removeTracksFromPlaylist not configured"))

        var lastAddPlaylistId: MediaId? = null
        var lastAddedTracks: List<MediaId>? = null
        var lastRemovePlaylistId: MediaId? = null
        var lastRemovedItems: List<PlaylistItemRef>? = null
        var lastSnapshotId: String? = null
        var lastRatedTrackId: MediaId? = null
        var lastServerRating: Int? = null

        override suspend fun setFavorite(id: MediaId, favorite: Boolean): Result<Unit> =
            Result.failure(UnsupportedOperationException())

        override suspend fun setRating(trackId: MediaId, rating: Int): Result<Unit> {
            lastRatedTrackId = trackId
            lastServerRating = rating
            return setRatingResult
        }

        override suspend fun createPlaylist(name: String, description: String?): Result<Playlist> =
            createPlaylistResult

        override suspend fun renamePlaylist(
            id: MediaId,
            name: String,
            description: String?,
        ): Result<Unit> = Result.failure(UnsupportedOperationException())

        override suspend fun deletePlaylist(id: MediaId): Result<Unit> =
            Result.failure(UnsupportedOperationException())

        override suspend fun addTracksToPlaylist(
            playlistId: MediaId,
            tracks: List<MediaId>,
        ): Result<String?> {
            lastAddPlaylistId = playlistId
            lastAddedTracks = tracks
            return addTracksResult
        }

        override suspend fun removeTracksFromPlaylist(
            playlistId: MediaId,
            items: List<PlaylistItemRef>,
            snapshotId: String?,
        ): Result<String?> {
            lastRemovePlaylistId = playlistId
            lastRemovedItems = items
            lastSnapshotId = snapshotId
            return removeTracksResult
        }
    }
}
