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
import com.gpo.yoin.data.remote.GeminiService
import com.gpo.yoin.data.source.MusicLibrary
import com.gpo.yoin.data.source.MusicSource
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The detail read path: the disk copy stays off the caller's path. */
@OptIn(ExperimentalCoroutinesApi::class)
class YoinRepositoryDetailCacheTest {
    private val library = mockk<MusicLibrary>()
    private val source = mockk<MusicSource>().also { every { it.library() } returns library }
    private var currentTime = 1_000_000L

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

    private fun TestScope.repository(store: DetailCacheStore? = null) = YoinRepository(
        activeSource = MutableStateFlow(source),
        activeProfileId = MutableStateFlow("profile-1"),
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

    private companion object {
        val ALBUM_ID = MediaId(MediaId.PROVIDER_SUBSONIC, "al-1")
    }
}
