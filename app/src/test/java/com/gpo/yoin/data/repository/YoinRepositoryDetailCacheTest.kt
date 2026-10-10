package com.gpo.yoin.data.repository

import com.gpo.yoin.data.cache.DetailCacheStore
import com.gpo.yoin.data.cache.FakeDetailCacheDao
import com.gpo.yoin.data.integration.neodb.NeoDBSyncService
import com.gpo.yoin.data.local.AlbumNoteDao
import com.gpo.yoin.data.local.AlbumRatingDao
import com.gpo.yoin.data.local.GeminiConfigDao
import com.gpo.yoin.data.local.LyricsCacheDao
import com.gpo.yoin.data.local.LyricsTranslationCacheDao
import com.gpo.yoin.data.local.MemoryCopyCacheDao
import com.gpo.yoin.data.local.SongAboutEntryDao
import com.gpo.yoin.data.local.SongNoteDao
import com.gpo.yoin.data.local.YoinDatabase
import com.gpo.yoin.data.model.Album
import com.gpo.yoin.data.model.MediaId
import com.gpo.yoin.data.model.Playlist
import com.gpo.yoin.data.model.Track
import com.gpo.yoin.data.remote.GeminiService
import com.gpo.yoin.data.source.MusicLibrary
import com.gpo.yoin.data.source.MusicSource
import com.gpo.yoin.data.source.MusicWriteActions
import com.gpo.yoin.ui.detail.prefetchAlbumDetail
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import java.io.IOException
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The detail read path: tap-time prefetch joins, and the disk copy stays off the caller's path. */
@OptIn(ExperimentalCoroutinesApi::class)
class YoinRepositoryDetailCacheTest {
    private val library = mockk<MusicLibrary>()

    // Every favorite toggle succeeds (by hand: a mocked Result return trips over its inline class).
    private val writeActions = object : MusicWriteActions by mockk(relaxed = true) {
        override suspend fun setFavorite(id: MediaId, favorite: Boolean): Result<Unit> = Result.success(Unit)
    }
    private val source = mockk<MusicSource>().also {
        every { it.library() } returns library
        every { it.writeActions() } returns writeActions
    }
    private var currentTime = 1_000_000L

    // The store's JSON encodes and decodes wait here until a test runs them.
    private val codec = HeldDispatcher()

    @Test
    fun should_returnBeforeDiskWriteCompletes_when_networkLoadSucceeds() = runTest {
        val dao = FakeDetailCacheDao().apply { upsertGate = CompletableDeferred() }
        val repository = repository(DetailCacheStore(dao, clock = { currentTime }, scope = backgroundScope))
        coEvery { library.getAlbum(ALBUM_ID) } returns album()

        // The disk write is held mid-upsert: the page still gets its album.
        val loaded = withTimeout(5_000) { repository.getAlbum(ALBUM_ID) }

        assertEquals(album(), loaded)
        dao.upsertEntered.await()
        assertTrue(dao.entityIds.isEmpty())

        dao.upsertGate!!.complete(Unit)
        dao.upsertStored.await()
        assertEquals(setOf(ALBUM_ID.toString()), dao.entityIds)
    }

    @Test
    fun should_skipBackgroundWrite_when_invalidatedBeforeItReachesTheStore() = runTest {
        val dao = FakeDetailCacheDao()
        val repository = repository(heldCodecStore(dao))
        coEvery { library.getAlbum(ALBUM_ID) } returns album()

        // The page has its album; the disk copy is still being encoded …
        assertEquals(album(), repository.getAlbum(ALBUM_ID))
        runCurrent()
        // … when a favorite toggle drops the album (and its delete runs first).
        repository.setFavorite(track(), favorite = true)
        runCurrent()
        codec.runAll()
        runCurrent()

        // The write asks its guard under the store's lock, so the pre-toggle album never lands.
        assertTrue(dao.entityIds.isEmpty())
    }

    @Test
    fun should_dropDiskRow_when_invalidatedWhileItsWriteIsMidUpsert() = runTest {
        val dao = FakeDetailCacheDao().apply { upsertGate = CompletableDeferred() }
        val repository = repository(heldCodecStore(dao))
        coEvery { library.getAlbum(ALBUM_ID) } returns album()

        repository.getAlbum(ALBUM_ID)
        runCurrent()
        codec.runAll()
        // The write is past its guard, mid-upsert …
        dao.upsertEntered.await()
        // … when a favorite toggle drops the album: its delete queues behind the write.
        repository.setFavorite(track(), favorite = true)
        runCurrent()
        dao.upsertGate!!.complete(Unit)
        runCurrent()

        // The write landed, and the delete then took it out.
        assertTrue(dao.upsertStored.isCompleted)
        assertTrue(dao.entityIds.isEmpty())
    }

    @Test
    fun should_skipRevalidatedWrite_when_invalidatedBeforeItReachesTheStore() = runTest {
        val dao = FakeDetailCacheDao()
        // A disk copy past the revalidate age (2 h), still disk-fresh (7 d).
        val written = currentTime - 3 * HOUR_MS
        DetailCacheStore(dao, clock = { written }).writeAlbum(PROFILE, ALBUM_ID.toString(), album())
        val repository = repository(heldCodecStore(dao))
        coEvery { library.getAlbum(ALBUM_ID) } returns album().copy(name = "Album (Remaster)")

        // The page is served from disk, and a background refresh fetches the album again …
        val page = async { repository.getAlbum(ALBUM_ID) }
        runCurrent()
        codec.runAll()
        runCurrent()
        assertEquals("Album", page.await()?.name)
        coVerify(exactly = 1) { library.getAlbum(ALBUM_ID) }
        // … whose disk write is still being encoded when a favorite toggle drops the album.
        repository.setFavorite(track(), favorite = true)
        runCurrent()
        codec.runAll()
        runCurrent()

        assertTrue(dao.entityIds.isEmpty())
    }

    @Test
    fun should_joinInFlightPrefetch_when_viewModelLoadsSameAlbum() = runTest {
        val repository = repository()
        val response = CompletableDeferred<Album>()
        coEvery { library.getAlbum(ALBUM_ID) } coAnswers { response.await() }

        // The tap prefetches by the id string the page is opened with …
        repository.prefetchAlbumDetail(ALBUM_ID.toString())
        runCurrent()
        coVerify(exactly = 1) { library.getAlbum(ALBUM_ID) }
        // … and the page's ViewModel parses that string and loads while the fetch is still out.
        val page = async { repository.getAlbum(MediaId.parse(ALBUM_ID.toString())) }
        runCurrent()
        response.complete(album())

        assertEquals(album(), page.await())
        coVerify(exactly = 1) { library.getAlbum(ALBUM_ID) }
    }

    @Test
    fun should_sendOneRequest_when_pageStartsTheLoadBeforeThePrefetchRuns() = runTest {
        val repository = repository()
        val response = CompletableDeferred<Album>()
        coEvery { library.getAlbum(ALBUM_ID) } coAnswers { response.await() }

        // The tap's prefetch is queued but hasn't run …
        repository.prefetchAlbumDetail(ALBUM_ID.toString())
        // … when the page's ViewModel starts the load: the prefetch then joins the page's.
        val page = async(start = CoroutineStart.UNDISPATCHED) { repository.getAlbum(ALBUM_ID) }
        runCurrent()
        response.complete(album())

        assertEquals(album(), page.await())
        coVerify(exactly = 1) { library.getAlbum(ALBUM_ID) }
    }

    @Test
    fun should_servePrefetchedPlaylist_when_pageLoadsRightAfterTheTap() = runTest {
        val repository = repository()
        coEvery { library.getPlaylist(PLAYLIST_ID) } returnsMany listOf(playlist(), playlist().copy(name = "Renamed"))

        repository.prefetchPlaylist(PLAYLIST_ID)
        runCurrent()
        // The prefetch's fetch has landed …
        coVerify(exactly = 1) { library.getPlaylist(PLAYLIST_ID) }

        // … and the page opening a moment later takes it: that fetch was this open's revalidation.
        currentTime += 1_000L
        assertEquals("Road Trip", repository.getPlaylist(PLAYLIST_ID)?.name)
        coVerify(exactly = 1) { library.getPlaylist(PLAYLIST_ID) }

        // A later open still goes back online (playlists revalidate on every open).
        currentTime += 60_000L
        assertEquals("Renamed", repository.getPlaylist(PLAYLIST_ID)?.name)
        coVerify(exactly = 2) { library.getPlaylist(PLAYLIST_ID) }
    }

    @Test
    fun should_fetchPlaylistAgain_when_lastOpenFellBackToDiskCopy() = runTest {
        val dao = FakeDetailCacheDao()
        val store = DetailCacheStore(dao, clock = { currentTime }, scope = backgroundScope)
        store.writePlaylist(PROFILE, PLAYLIST_ID.toString(), playlist())
        currentTime += 3 * HOUR_MS
        val repository = repository(store)
        val renamed = playlist().copy(name = "Renamed")
        coEvery { library.getPlaylist(PLAYLIST_ID) } throws IOException("offline") andThen renamed

        // Offline: the open falls back to the disk copy …
        assertEquals("Road Trip", repository.getPlaylist(PLAYLIST_ID)?.name)

        // … which the hand-off never takes for a fetch: the next open goes back online.
        currentTime += 1_000L
        assertEquals("Renamed", repository.getPlaylist(PLAYLIST_ID)?.name)
        coVerify(exactly = 2) { library.getPlaylist(PLAYLIST_ID) }
    }

    private fun TestScope.repository(store: DetailCacheStore? = null) = YoinRepository(
        activeSource = MutableStateFlow(source),
        activeProfileId = MutableStateFlow(PROFILE),
        database = mockk<YoinDatabase>(relaxed = true),
        geminiService = mockk<GeminiService>(relaxed = true),
        songAboutEntryDao = mockk<SongAboutEntryDao>(relaxed = true),
        geminiConfigDao = mockk<GeminiConfigDao>(relaxed = true),
        lyricsCacheDao = mockk<LyricsCacheDao>(relaxed = true),
        lyricsTranslationCacheDao = mockk<LyricsTranslationCacheDao>(relaxed = true),
        songNoteDao = mockk<SongNoteDao>(relaxed = true),
        albumNoteDao = mockk<AlbumNoteDao>(relaxed = true),
        albumRatingDao = mockk<AlbumRatingDao>(relaxed = true),
        memoryCopyCacheDao = mockk<MemoryCopyCacheDao>(relaxed = true),
        neoDbSyncService = mockk<NeoDBSyncService>(relaxed = true),
        repositoryScope = backgroundScope,
        clock = { currentTime },
        detailCacheStore = store
    )

    /** A store whose JSON work waits in [codec], so a test decides when an encode is done. */
    private fun TestScope.heldCodecStore(dao: FakeDetailCacheDao) = DetailCacheStore(
        dao,
        clock = { currentTime },
        scope = backgroundScope,
        codecDispatcher = codec
    )

    private fun album() = Album(
        id = ALBUM_ID,
        name = "Album",
        artist = "Artist",
        artistId = null,
        coverArt = null,
        songCount = 0,
        durationSec = 0,
        year = 2020,
        genre = null
    )

    private fun track() = Track(
        id = TRACK_ID,
        title = "Track",
        artist = "Artist",
        artistId = null,
        album = "Album",
        albumId = ALBUM_ID,
        coverArt = null,
        durationSec = 180,
        trackNumber = 1,
        year = 2020,
        genre = null,
        userRating = null
    )

    private fun playlist() = Playlist(
        id = PLAYLIST_ID,
        name = "Road Trip",
        owner = "alice",
        coverArt = null,
        songCount = 0,
        durationSec = null
    )

    private companion object {
        const val PROFILE = "profile-1"
        const val HOUR_MS = 60L * 60 * 1000
        val ALBUM_ID = MediaId(MediaId.PROVIDER_SUBSONIC, "al-1")
        val TRACK_ID = MediaId(MediaId.PROVIDER_SUBSONIC, "tr-1")
        val PLAYLIST_ID = MediaId(MediaId.PROVIDER_SUBSONIC, "pl-1")
    }
}

/** Holds every task dispatched to it until [runAll] runs them, on the calling thread. */
private class HeldDispatcher : CoroutineDispatcher() {
    private val tasks = ArrayDeque<Runnable>()

    override fun dispatch(context: CoroutineContext, block: Runnable) {
        synchronized(tasks) { tasks.addLast(block) }
    }

    fun runAll() {
        while (true) {
            val task = synchronized(tasks) { tasks.removeFirstOrNull() } ?: return
            task.run()
        }
    }
}
