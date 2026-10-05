package com.gpo.yoin.data.repository

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.gpo.yoin.data.integration.neodb.NeoDBSyncService
import com.gpo.yoin.data.local.GeminiConfig
import com.gpo.yoin.data.local.GeminiConfigDao
import com.gpo.yoin.data.local.LyricsCache
import com.gpo.yoin.data.local.YoinDatabase
import com.gpo.yoin.data.lyrics.LrcParser
import com.gpo.yoin.data.lyrics.LyricPayload
import com.gpo.yoin.data.lyrics.LyricProvider
import com.gpo.yoin.data.lyrics.LyricsCachePolicy
import com.gpo.yoin.data.lyrics.LyricsProviderRegistry
import com.gpo.yoin.data.lyrics.NativeTranslation
import com.gpo.yoin.data.lyrics.SongMatch
import com.gpo.yoin.data.model.Lyrics
import com.gpo.yoin.data.model.MediaId
import com.gpo.yoin.data.repository.YoinRepository.LyricsTranslationProviderSwitchOffer
import com.gpo.yoin.data.repository.YoinRepository.LyricsTranslationResult
import com.gpo.yoin.data.source.MusicMetadata
import com.gpo.yoin.data.source.MusicSource
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Lyrics cache lifetime through the repository (owner ruling: user-chosen lyrics are bounded by
 * size, not days). Automatic rows keep the 30-day TTL; user-chosen rows (typed, applied from the
 * search sheet, accepted switches) never expire and win over Subsonic server lyrics on reload.
 */
@RunWith(RobolectricTestRunner::class)
class YoinRepositoryLyricsCacheTest {

    private lateinit var database: YoinDatabase
    private var now = 1_000L * DAY

    private val qq = StubProvider("qq")
    private val netease = StubProvider("netease")
    private val serverMetadata = mockk<MusicMetadata>()
    private val geminiConfigDao = mockk<GeminiConfigDao>(relaxed = true)

    private val spotifyTrack = MediaId.spotify("track-1")
    private val subsonicTrack = MediaId.subsonic("song-1")

    @Before
    fun setUp() {
        database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            YoinDatabase::class.java,
        )
            .allowMainThreadQueries()
            .build()
        qq.searchHit = SongMatch("q1", "Track", "Artist")
        qq.payloads["q1"] = LyricPayload("[00:01.00]auto one\n[00:02.00]auto two")
        netease.payloads["n1"] = LyricPayload("[00:01.00]picked one\n[00:02.00]picked two")
        coEvery { serverMetadata.getLyrics(any()) } returns Lyrics.Unsynced("server placeholder")
        coEvery { geminiConfigDao.getConfig() } returns
            flowOf(GeminiConfig(apiKey = "", targetLanguage = "Simplified Chinese"))
    }

    @After
    fun tearDown() {
        database.close()
    }

    // ── TTL ──────────────────────────────────────────────────────────────────

    @Test
    fun should_serveAutomaticRow_when_withinTtl() = runTest {
        val repository = repository(sourceId = MediaId.PROVIDER_SPOTIFY)
        repository.getLoadedLyrics(spotifyTrack, "Track", "Artist")
        now += 29L * DAY

        val loaded = repository.getLoadedLyrics(spotifyTrack, "Track", "Artist")

        assertEquals("qq", loaded?.providerName)
        assertEquals(1, qq.searches)
    }

    @Test
    fun should_refetchAutomaticRow_when_olderThanTtl() = runTest {
        val repository = repository(sourceId = MediaId.PROVIDER_SPOTIFY)
        repository.getLoadedLyrics(spotifyTrack, "Track", "Artist")
        qq.payloads["q1"] = LyricPayload("[00:01.00]auto refreshed")
        now += 31L * DAY

        val loaded = repository.getLoadedLyrics(spotifyTrack, "Track", "Artist")

        assertEquals(LrcParser.parse("[00:01.00]auto refreshed"), loaded?.lyrics)
        assertEquals(2, qq.searches)
        assertEquals(now, stored(spotifyTrack).cachedAt)
    }

    @Test
    fun should_keepSearchSheetPick_when_olderThanTtl() = runTest {
        val repository = repository(sourceId = MediaId.PROVIDER_SPOTIFY)
        repository.applyLyricsSearchResult(spotifyTrack, providerName = "netease", songId = "n1").getOrThrow()
        now += 400L * DAY

        val loaded = repository.getLoadedLyrics(spotifyTrack, "Track", "Artist")

        assertEquals("netease", loaded?.providerName)
        assertEquals("n1", loaded?.providerSongId)
        assertEquals(LrcParser.parse(netease.payloads.getValue("n1").lyric), loaded?.lyrics)
        assertEquals(0, qq.searches)
    }

    @Test
    fun should_keepTypedLyrics_when_olderThanTtl() = runTest {
        val repository = repository(sourceId = MediaId.PROVIDER_SPOTIFY)
        repository.applyLyrics(spotifyTrack, "[00:01.00]typed placeholder").getOrThrow()
        now += 400L * DAY

        val loaded = repository.getLoadedLyrics(spotifyTrack, "Track", "Artist")

        assertEquals("manual", loaded?.providerName)
        assertEquals(LrcParser.parse("[00:01.00]typed placeholder"), loaded?.lyrics)
        assertEquals(0, qq.searches)
    }

    @Test
    fun should_serveUserPick_when_titleOrArtistIsMissing() = runTest {
        val repository = repository(sourceId = MediaId.PROVIDER_SPOTIFY)
        repository.applyLyricsSearchResult(spotifyTrack, providerName = "netease", songId = "n1").getOrThrow()

        val loaded = repository.getLoadedLyrics(spotifyTrack, title = null, artist = null)

        assertEquals("netease", loaded?.providerName)
    }

    // ── recency ──────────────────────────────────────────────────────────────

    @Test
    fun should_refreshUserRowRecency_when_readAfterADay() = runTest {
        val repository = repository(sourceId = MediaId.PROVIDER_SPOTIFY)
        repository.applyLyricsSearchResult(spotifyTrack, providerName = "netease", songId = "n1").getOrThrow()
        val pickedAt = now

        now += 2L * DAY
        repository.getLoadedLyrics(spotifyTrack, "Track", "Artist")
        val firstRead = now
        now += 60L * 60L * 1000L
        repository.getLoadedLyrics(spotifyTrack, "Track", "Artist")

        assertTrue(firstRead > pickedAt)
        // Bumped once by the first read; the second read inside the day leaves it alone.
        assertEquals(firstRead, stored(spotifyTrack).cachedAt)
    }

    @Test
    fun should_neverTouchAutomaticRowTimestamp_when_read() = runTest {
        val repository = repository(sourceId = MediaId.PROVIDER_SPOTIFY)
        repository.getLoadedLyrics(spotifyTrack, "Track", "Artist")
        val fetchedAt = now

        now += 10L * DAY
        repository.getLoadedLyrics(spotifyTrack, "Track", "Artist")

        assertEquals(fetchedAt, stored(spotifyTrack).cachedAt)
    }

    // ── Subsonic precedence ──────────────────────────────────────────────────

    @Test
    fun should_preferUserPickOverSubsonicServerLyrics_when_reloading() = runTest {
        val repository = repository(sourceId = MediaId.PROVIDER_SUBSONIC)
        repository.applyLyricsSearchResult(subsonicTrack, providerName = "netease", songId = "n1").getOrThrow()

        val loaded = repository.getLoadedLyrics(subsonicTrack, "Track", "Artist")

        assertEquals("netease", loaded?.providerName)
        assertEquals("n1", loaded?.providerSongId)
        coVerify(exactly = 0) { serverMetadata.getLyrics(any()) }
    }

    @Test
    fun should_preferTypedLyricsOverSubsonicServerLyrics_when_reloading() = runTest {
        val repository = repository(sourceId = MediaId.PROVIDER_SUBSONIC)
        repository.applyLyrics(subsonicTrack, "[00:01.00]typed placeholder").getOrThrow()

        val loaded = repository.getLoadedLyrics(subsonicTrack, "Track", "Artist")

        assertEquals("manual", loaded?.providerName)
    }

    @Test
    fun should_useSubsonicServerLyrics_when_onlyAnAutomaticRowExists() = runTest {
        val repository = repository(sourceId = MediaId.PROVIDER_SUBSONIC)
        database.lyricsCacheDao().upsert(
            LyricsCache("subsonic", "song-1", "qq", "q1", "[00:01.00]auto one", now),
        )

        val loaded = repository.getLoadedLyrics(subsonicTrack, "Track", "Artist")

        assertEquals(MediaId.PROVIDER_SUBSONIC, loaded?.providerName)
        assertEquals(Lyrics.Unsynced("server placeholder"), loaded?.lyrics)
        assertEquals(0, qq.searches)
    }

    // ── replacing / keeping the user choice ──────────────────────────────────

    @Test
    fun should_replaceUserChoice_when_applyingAnotherResult() = runTest {
        val repository = repository(sourceId = MediaId.PROVIDER_SPOTIFY)
        repository.applyLyricsSearchResult(spotifyTrack, providerName = "netease", songId = "n1").getOrThrow()
        repository.applyLyricsSearchResult(spotifyTrack, providerName = "qq", songId = "q1").getOrThrow()
        now += 400L * DAY

        val loaded = repository.getLoadedLyrics(spotifyTrack, "Track", "Artist")

        assertEquals("qq", loaded?.providerName)
        assertEquals("q1", loaded?.providerSongId)
        assertTrue(LyricsCachePolicy.isUserChosen(stored(spotifyTrack)))
        assertEquals(0, qq.searches)
    }

    @Test
    fun should_keepUserChoice_when_translationRefetchesTheCurrentProvider() = runTest {
        val repository = repository(sourceId = MediaId.PROVIDER_SPOTIFY)
        qq.payloads["q1"] = LyricPayload("[00:01.00]Hello\n[00:02.00]World", "[00:01.00]你好\n[00:02.00]世界")
        repository.applyLyricsSearchResult(spotifyTrack, providerName = "qq", songId = "q1").getOrThrow()
        val loaded = repository.getLoadedLyrics(spotifyTrack, "Track", "Artist")!!

        val result = repository.translateLyrics(
            trackId = spotifyTrack,
            title = "Track",
            artist = "Artist",
            lines = listOf("Hello", "World"),
            currentLyricsProviderName = loaded.providerName,
            currentLyricsProviderSongId = loaded.providerSongId,
        )

        assertTrue(result is LyricsTranslationResult.Success)
        // The provider sees its own id, never the cache marker.
        assertEquals(listOf("q1"), qq.fetchedIds)
        assertTrue(LyricsCachePolicy.isUserChosen(stored(spotifyTrack)))
        now += 400L * DAY
        assertEquals("qq", repository.getLoadedLyrics(spotifyTrack, "Track", "Artist")?.providerName)
        assertEquals(0, qq.searches)
    }

    @Test
    fun should_keepAutomaticRowAutomatic_when_translationRefetchesIt() = runTest {
        val repository = repository(sourceId = MediaId.PROVIDER_SPOTIFY)
        qq.payloads["q1"] = LyricPayload("[00:01.00]Hello\n[00:02.00]World", "[00:01.00]你好\n[00:02.00]世界")
        val loaded = repository.getLoadedLyrics(spotifyTrack, "Track", "Artist")!!

        repository.translateLyrics(
            trackId = spotifyTrack,
            title = "Track",
            artist = "Artist",
            lines = listOf("Hello", "World"),
            currentLyricsProviderName = loaded.providerName,
            currentLyricsProviderSongId = loaded.providerSongId,
        )

        assertFalse(LyricsCachePolicy.isUserChosen(stored(spotifyTrack)))
        // The free translation row belongs to the same track, so it follows that row in the budget.
        assertEquals(1, providerTranslationRows("track-1"))
    }

    @Test
    fun should_keepAcceptedSwitch_when_olderThanTtl() = runTest {
        val repository = repository(sourceId = MediaId.PROVIDER_SPOTIFY)
        repository.getLoadedLyrics(spotifyTrack, "Track", "Artist")
        val switched = "[00:01.00]switched one"
        repository.applyLyricsTranslationProviderSwitch(
            spotifyTrack,
            LyricsTranslationProviderSwitchOffer(
                providerName = "netease",
                providerSongId = "n1",
                lyrics = LrcParser.parse(switched),
                rawLrc = switched,
                translations = mapOf(0 to "切换"),
            ),
        )
        now += 400L * DAY

        val loaded = repository.getLoadedLyrics(spotifyTrack, "Track", "Artist")

        assertEquals("netease", loaded?.providerName)
        assertEquals("n1", loaded?.providerSongId)
        assertEquals(1, qq.searches)
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private fun repository(sourceId: String): YoinRepository {
        val source = mockk<MusicSource>(relaxed = true)
        every { source.id } returns sourceId
        every { source.metadata() } returns serverMetadata
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
            lyricsProviderRegistry = LyricsProviderRegistry(providers = listOf(qq, netease)),
            clock = { now },
        )
    }

    private suspend fun stored(trackId: MediaId): LyricsCache =
        database.lyricsCacheDao().get(trackId.provider, trackId.rawId)!!

    private fun providerTranslationRows(trackRawId: String): Int = database.query(
        "SELECT COUNT(*) FROM lyrics_translation_cache WHERE trackRawId = ? AND model LIKE 'provider:%'",
        arrayOf(trackRawId),
    ).use { cursor ->
        cursor.moveToFirst()
        cursor.getInt(0)
    }

    private class StubProvider(
        override val name: String,
        override val nativeTranslation: NativeTranslation? = NativeTranslation.SimplifiedChinese,
    ) : LyricProvider() {
        val payloads = mutableMapOf<String, LyricPayload>()
        var searchHit: SongMatch? = null
        val fetchedIds = mutableListOf<String>()
        var searches = 0
            private set

        override suspend fun search(title: String, artist: String): SongMatch? {
            searches += 1
            return searchHit
        }

        override suspend fun fetchLyric(songId: String): String? = payloads[songId]?.lyric

        override suspend fun fetchLyricWithTranslation(songId: String): LyricPayload? {
            fetchedIds += songId
            return payloads[songId]
        }
    }

    private companion object {
        const val DAY = 24L * 60L * 60L * 1000L
    }
}
