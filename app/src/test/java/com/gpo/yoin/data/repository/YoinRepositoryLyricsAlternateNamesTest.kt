package com.gpo.yoin.data.repository

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.gpo.yoin.data.integration.neodb.NeoDBSyncService
import com.gpo.yoin.data.local.GeminiConfig
import com.gpo.yoin.data.local.GeminiConfigDao
import com.gpo.yoin.data.local.YoinDatabase
import com.gpo.yoin.data.lyrics.LyricProvider
import com.gpo.yoin.data.lyrics.LyricsProviderRegistry
import com.gpo.yoin.data.lyrics.SongMatch
import com.gpo.yoin.data.model.MediaId
import com.gpo.yoin.data.source.MusicMetadata
import com.gpo.yoin.data.source.MusicSource
import com.gpo.yoin.data.source.TrackNames
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Apple Music's Japan storefront names Kim Petras キム・ペトラス, and no lyrics source finds the song under it.
 * The repository searches once more under the service's English names, and only when the shown names found
 * nothing and aren't Latin already: a Japanese song's English names (晴る → Sunny) are what sources lack.
 */
@RunWith(RobolectricTestRunner::class)
class YoinRepositoryLyricsAlternateNamesTest {

    private lateinit var database: YoinDatabase
    private val provider = NamedProvider()
    private val metadata = mockk<MusicMetadata>(relaxed = true)
    private val geminiConfigDao = mockk<GeminiConfigDao>(relaxed = true)
    private val popSound = MediaId(MediaId.PROVIDER_APPLE_MUSIC, "6805970194")

    @Before
    fun setUp() {
        database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            YoinDatabase::class.java,
        ).allowMainThreadQueries().build()
        coEvery { metadata.alternateNames(popSound) } returns TrackNames("Pop Sound", "Kim Petras")
        coEvery { geminiConfigDao.getConfig() } returns
            flowOf(GeminiConfig(apiKey = "", targetLanguage = "Simplified Chinese"))
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun should_searchUnderEnglishNames_when_shownNamesFindNothing() = runTest {
        provider.lyricsFor("Pop Sound", "Kim Petras")

        val loaded = repository().getLoadedLyrics(popSound, "Pop Sound", "キム・ペトラス", durationMs = 171_172)

        assertEquals("named", loaded?.providerName)
        assertEquals(
            listOf(
                Query("Pop Sound", "キム・ペトラス", 171_172),
                Query("Pop Sound", "Kim Petras", 171_172),
            ),
            provider.queries,
        )
    }

    @Test
    fun should_keepShownNames_when_theyFindLyrics() = runTest {
        provider.lyricsFor("晴る", "ヨルシカ")
        val haru = MediaId(MediaId.PROVIDER_APPLE_MUSIC, "1721450223")

        val loaded = repository().getLoadedLyrics(haru, "晴る", "ヨルシカ")

        assertEquals("named", loaded?.providerName)
        coVerify(exactly = 0) { metadata.alternateNames(any()) }
    }

    @Test
    fun should_notAskForEnglishNames_when_shownNamesAreLatin() = runTest {
        val loaded = repository().getLoadedLyrics(popSound, "Pop Sound", "Kim Petras")

        assertNull(loaded)
        coVerify(exactly = 0) { metadata.alternateNames(any()) }
        assertEquals(1, provider.queries.size)
    }

    @Test
    fun should_searchOnce_when_serviceHasNoEnglishNames() = runTest {
        coEvery { metadata.alternateNames(popSound) } returns null

        val loaded = repository().getLoadedLyrics(popSound, "Pop Sound", "キム・ペトラス")

        assertNull(loaded)
        assertEquals(1, provider.queries.size)
    }

    private fun repository(): YoinRepository {
        val source = mockk<MusicSource>(relaxed = true)
        every { source.id } returns MediaId.PROVIDER_APPLE_MUSIC
        every { source.metadata() } returns metadata
        return YoinRepository(
            activeSource = MutableStateFlow(source),
            activeProfileId = MutableStateFlow("profile"),
            database = database,
            geminiService = mockk(relaxed = true),
            songAboutEntryDao = mockk(relaxed = true),
            geminiConfigDao = geminiConfigDao,
            lyricsCacheDao = database.lyricsCacheDao(),
            lyricsTranslationCacheDao = database.lyricsTranslationCacheDao(),
            songNoteDao = mockk(relaxed = true),
            albumNoteDao = mockk(relaxed = true),
            albumRatingDao = mockk(relaxed = true),
            memoryCopyCacheDao = mockk(relaxed = true),
            neoDbSyncService = mockk<NeoDBSyncService>(relaxed = true),
            lyricsProviderRegistry = LyricsProviderRegistry(providers = listOf(provider)),
            clock = { 1_000L },
        )
    }

    private data class Query(val title: String, val artist: String, val durationMs: Long?)

    /** Answers only the exact title / artist it was given lyrics for, and records every search. */
    private class NamedProvider : LyricProvider() {
        override val name: String = "named"
        private val known = mutableSetOf<Pair<String, String>>()
        val queries = mutableListOf<Query>()

        fun lyricsFor(title: String, artist: String) {
            known += title to artist
        }

        override suspend fun search(title: String, artist: String, durationMs: Long?): SongMatch? {
            queries += Query(title, artist, durationMs)
            return if (title to artist in known) SongMatch("$title|$artist", title, artist) else null
        }

        override suspend fun fetchLyric(songId: String): String = "[00:01.00]$songId"
    }
}
