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
import com.gpo.yoin.data.remote.GeminiService
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
 * A friend's Spotify app, set to Japanese, plays Hebe Tien's 《摩天輪》 as "Ferries wheel" by ヒビ・ティエン, and
 * lyrics stayed empty. Spotify names the artist 田馥甄 when asked in Chinese, but keeps "Ferries wheel" in every
 * language, so the title comes from the AI, and only for the song on screen.
 */
@RunWith(RobolectricTestRunner::class)
class YoinRepositoryLyricsNativeNamesTest {

    private lateinit var database: YoinDatabase
    private val provider = NamedProvider()
    private val metadata = mockk<MusicMetadata>(relaxed = true)
    private val geminiConfigDao = mockk<GeminiConfigDao>(relaxed = true)
    private val geminiService = mockk<GeminiService>(relaxed = true)
    private val ferriesWheel = MediaId.spotify("0q96DdFCFTYX2brsBFUbDO")
    private val durationMs = 244_800L
    private val releasedByIsrc = mutableMapOf<String, TrackNames>()
    private val isrcLookups = mutableListOf<String>()

    @Before
    fun setUp() {
        database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            YoinDatabase::class.java,
        ).allowMainThreadQueries().build()
        coEvery { metadata.alternateNames(any()) } returns null
        coEvery { metadata.isrc(any()) } returns null
        coEvery { metadata.nativeNames(ferriesWheel) } returns listOf(TrackNames("Ferries wheel", "田馥甄"))
        coEvery { geminiConfigDao.getConfig() } returns
            flowOf(GeminiConfig(apiKey = "key", targetLanguage = "Simplified Chinese"))
        coEvery {
            geminiService.resolveReleaseNames(any(), "Ferries wheel", "ヒビ・ティエン", any(), any(), any())
        } returns ("摩天輪" to "田馥甄")
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun should_findLyrics_when_aiNamesTheReleasedTitle() = runTest {
        provider.lyricsFor("摩天輪", "田馥甄")

        val loaded = repository().getLoadedLyrics(
            ferriesWheel,
            "Ferries wheel",
            "ヒビ・ティエン",
            durationMs,
            album = "Reaching For The Stars",
            askAi = true,
        )

        assertEquals("named", loaded?.providerName)
        assertEquals(
            listOf(
                Query("Ferries wheel", "ヒビ・ティエン"),
                Query("Ferries wheel", "田馥甄"),
                Query("摩天輪", "田馥甄"),
            ),
            provider.queries,
        )
        coVerify {
            geminiService.resolveReleaseNames(
                any(),
                "Ferries wheel",
                "ヒビ・ティエン",
                "Reaching For The Stars",
                durationMs,
                listOf("Ferries wheel" to "田馥甄"),
            )
        }
    }

    @Test
    fun should_findLyricsUnderIsrcNames_withoutAi() = runTest {
        coEvery { metadata.isrc(ferriesWheel) } returns "TWD950519106"
        releasedByIsrc["TWD950519106"] = TrackNames("摩天輪", "田馥甄")
        provider.lyricsFor("摩天輪", "田馥甄")

        val loaded = repository().getLoadedLyrics(ferriesWheel, "Ferries wheel", "ヒビ・ティエン", durationMs)

        assertEquals("named", loaded?.providerName)
        assertEquals(Query("摩天輪", "田馥甄"), provider.queries.last())
        coVerify(exactly = 0) { geminiService.resolveReleaseNames(any(), any(), any(), any(), any(), any()) }
    }

    @Test
    fun should_notLookUpIsrc_when_namesShowNoSignOfLocalizing() = runTest {
        val song = MediaId.spotify("4uLU6hMCjMI75M1A2tKUQC")
        coEvery { metadata.nativeNames(song) } returns emptyList()
        coEvery { metadata.isrc(song) } returns "USRC17607839"

        repository().getLoadedLyrics(song, "Some Song", "Some Artist", askAi = true)

        assertEquals(emptyList<String>(), isrcLookups)
    }

    @Test
    fun should_notAskAi_when_nativeNamesFindLyrics() = runTest {
        provider.lyricsFor("Ferries wheel", "田馥甄")

        val loaded = repository().getLoadedLyrics(ferriesWheel, "Ferries wheel", "ヒビ・ティエン", durationMs, askAi = true)

        assertEquals("named", loaded?.providerName)
        coVerify(exactly = 0) { geminiService.resolveReleaseNames(any(), any(), any(), any(), any(), any()) }
    }

    @Test
    fun should_searchUnderNativeNames_when_shownNamesAreLatin() = runTest {
        // An English Spotify app writes 周杰倫 as Jay Chou; asked in Taiwanese Chinese, Spotify says 周杰倫.
        val qingTian = MediaId.spotify("0RhDhTcMvpyhCxFkCKUgXR")
        coEvery { metadata.nativeNames(qingTian) } returns listOf(TrackNames("晴天", "周杰倫"))
        provider.lyricsFor("晴天", "周杰倫")

        val loaded = repository().getLoadedLyrics(qingTian, "晴天", "Jay Chou")

        assertEquals("named", loaded?.providerName)
        assertEquals(listOf(Query("晴天", "Jay Chou"), Query("晴天", "周杰倫")), provider.queries)
    }

    @Test
    fun should_notAskAi_when_notForTheSongOnScreen() = runTest {
        provider.lyricsFor("摩天輪", "田馥甄")

        val loaded = repository().getLoadedLyrics(ferriesWheel, "Ferries wheel", "ヒビ・ティエン", durationMs)

        assertNull(loaded)
        coVerify(exactly = 0) { geminiService.resolveReleaseNames(any(), any(), any(), any(), any(), any()) }
    }

    @Test
    fun should_notAskAi_when_namesShowNoSignOfLocalizing() = runTest {
        // Latin names, and Spotify names the song the same in every language: nothing says it's localized.
        val song = MediaId.spotify("4uLU6hMCjMI75M1A2tKUQC")
        coEvery { metadata.nativeNames(song) } returns emptyList()

        val loaded = repository().getLoadedLyrics(song, "Some Song", "Some Artist", askAi = true)

        assertNull(loaded)
        coVerify(exactly = 0) { geminiService.resolveReleaseNames(any(), any(), any(), any(), any(), any()) }
    }

    @Test
    fun should_askAiOncePerSong_when_itsAnswerFindsNothing() = runTest {
        val repository = repository()

        repeat(2) {
            repository.getLoadedLyrics(ferriesWheel, "Ferries wheel", "ヒビ・ティエン", durationMs, askAi = true)
        }

        coVerify(exactly = 1) { geminiService.resolveReleaseNames(any(), any(), any(), any(), any(), any()) }
    }

    @Test
    fun should_stillWork_when_aiIsNotSetUp() = runTest {
        coEvery { geminiConfigDao.getConfig() } returns flowOf(GeminiConfig(apiKey = "", targetLanguage = "English"))

        val loaded = repository().getLoadedLyrics(ferriesWheel, "Ferries wheel", "ヒビ・ティエン", durationMs, askAi = true)

        assertNull(loaded)
        coVerify(exactly = 0) { geminiService.resolveReleaseNames(any(), any(), any(), any(), any(), any()) }
    }

    private fun repository(): YoinRepository {
        val source = mockk<MusicSource>(relaxed = true)
        every { source.id } returns MediaId.PROVIDER_SPOTIFY
        every { source.metadata() } returns metadata
        return YoinRepository(
            activeSource = MutableStateFlow(source),
            activeProfileId = MutableStateFlow("profile"),
            database = database,
            geminiService = geminiService,
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
            isrcReleaseNames = { isrc, _ ->
                isrcLookups += isrc
                releasedByIsrc[isrc]
            },
        )
    }

    private data class Query(val title: String, val artist: String)

    /** Answers only the exact title / artist it was given lyrics for, and records every search. */
    private class NamedProvider : LyricProvider() {
        override val name: String = "named"
        private val known = mutableSetOf<Pair<String, String>>()
        val queries = mutableListOf<Query>()

        fun lyricsFor(title: String, artist: String) {
            known += title to artist
        }

        override suspend fun search(title: String, artist: String, durationMs: Long?): SongMatch? {
            queries += Query(title, artist)
            return if (title to artist in known) SongMatch("$title|$artist", title, artist) else null
        }

        override suspend fun fetchLyric(songId: String): String = "[00:01.00]$songId"
    }
}
