package com.gpo.yoin.data.repository

import com.gpo.yoin.data.integration.neodb.NeoDBSyncService
import com.gpo.yoin.data.local.AlbumNoteDao
import com.gpo.yoin.data.local.AlbumRatingDao
import com.gpo.yoin.data.local.GeminiConfig
import com.gpo.yoin.data.local.GeminiConfigDao
import com.gpo.yoin.data.local.LyricsCache
import com.gpo.yoin.data.local.LyricsCacheDao
import com.gpo.yoin.data.local.LyricsTranslationCache
import com.gpo.yoin.data.local.LyricsTranslationCacheDao
import com.gpo.yoin.data.local.MemoryCopyCacheDao
import com.gpo.yoin.data.local.SongAboutEntryDao
import com.gpo.yoin.data.local.SongNoteDao
import com.gpo.yoin.data.local.YoinDatabase
import com.gpo.yoin.data.lyrics.HuaweiLrc
import com.gpo.yoin.data.lyrics.LrcParser
import com.gpo.yoin.data.lyrics.LyricPayload
import com.gpo.yoin.data.lyrics.LyricProvider
import com.gpo.yoin.data.lyrics.LyricsProviderRegistry
import com.gpo.yoin.data.lyrics.NativeTranslation
import com.gpo.yoin.data.lyrics.SongMatch
import com.gpo.yoin.data.model.MediaId
import com.gpo.yoin.data.remote.GeminiService
import com.gpo.yoin.data.repository.YoinRepository.LyricsTranslationResult
import com.gpo.yoin.data.source.MusicSource
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Provider-native translations: the current lyrics source's own translation wins;
 * otherwise a later automatic source may be offered as a whole-provider switch;
 * a manual-only source (Huawei) is used when it is the source but never offered.
 */
class YoinRepositoryLyricsTranslationTest {

    private val geminiConfigDao = mockk<GeminiConfigDao>(relaxed = true)
    private val lyricsCacheDao = mockk<LyricsCacheDao>(relaxed = true)
    private val lyricsTranslationCacheDao = mockk<LyricsTranslationCacheDao>(relaxed = true)
    private val geminiService = mockk<GeminiService>(relaxed = true)

    private val qq = StubProvider(name = "qq")
    private val netease = StubProvider(name = "netease")
    private val huawei = StubProvider(name = "huawei", automatic = false)
    private val lrclib = StubProvider(name = "lrclib", nativeTranslation = null)

    private val repository = YoinRepository(
        activeSource = MutableStateFlow(mockk<MusicSource>(relaxed = true)),
        activeProfileId = MutableStateFlow("spotify-profile"),
        database = mockk<YoinDatabase>(relaxed = true),
        geminiService = geminiService,
        songAboutEntryDao = mockk<SongAboutEntryDao>(relaxed = true),
        geminiConfigDao = geminiConfigDao,
        lyricsCacheDao = lyricsCacheDao,
        lyricsTranslationCacheDao = lyricsTranslationCacheDao,
        songNoteDao = mockk<SongNoteDao>(relaxed = true),
        albumNoteDao = mockk<AlbumNoteDao>(relaxed = true),
        albumRatingDao = mockk<AlbumRatingDao>(relaxed = true),
        memoryCopyCacheDao = mockk<MemoryCopyCacheDao>(relaxed = true),
        neoDbSyncService = mockk<NeoDBSyncService>(relaxed = true),
        lyricsProviderRegistry = LyricsProviderRegistry(providers = listOf(qq, netease, huawei, lrclib)),
        clock = { 5_000L },
    )

    init {
        target("Simplified Chinese")
        coEvery { lyricsTranslationCacheDao.get(any(), any(), any(), any(), any()) } returns null
    }

    @Test
    fun should_useHuaweiTranslation_when_huaweiIsTheCurrentSource() = runTest {
        val songId = "hw1|14584432|Lemon+Artist"
        huawei.payloads[songId] = HuaweiLrc.split(HUAWEI_FILE, setOf("translate", "scrolling"))!!
        val cachedLyrics = slot<LyricsCache>()
        val cachedTranslation = slot<LyricsTranslationCache>()
        coEvery { lyricsCacheDao.upsert(capture(cachedLyrics)) } returns Unit
        coEvery { lyricsTranslationCacheDao.upsert(capture(cachedTranslation)) } returns Unit

        val result = translate(currentProvider = "huawei", currentSongId = songId)

        result as LyricsTranslationResult.Success
        assertEquals("huawei", result.providerName)
        assertEquals(songId, result.providerSongId)
        // Title and credit lines share 00:00.00 with the first sung line; only that line is translated.
        assertEquals(mapOf(2 to "开场白", 3 to "第二行"), result.translations)
        assertEquals(LrcParser.parse(cachedLyrics.captured.lrc), result.lyrics)
        assertEquals("huawei", cachedLyrics.captured.lyricsProvider)
        assertEquals(songId, cachedLyrics.captured.lyricsProviderSongId)
        assertTrue('^' !in cachedLyrics.captured.lrc)
        assertEquals("provider:huawei", cachedTranslation.captured.model)
        assertEquals("""["","","开场白","第二行"]""", cachedTranslation.captured.translationsJson)
        assertEquals(listOf(songId), huawei.fetchedIds)
        assertEquals(0, qq.searches + netease.searches)
    }

    @Test
    fun should_useQqTranslation_when_qqIsTheCurrentSource() = runTest {
        qq.payloads["97773"] = LyricPayload("[00:01.00]Hello\n[00:02.00]World", "[00:01.00]你好\n[00:02.00]世界")

        val result = translate(currentProvider = "qq", currentSongId = "97773")

        result as LyricsTranslationResult.Success
        assertEquals("qq", result.providerName)
        assertEquals(mapOf(0 to "你好", 1 to "世界"), result.translations)
        assertEquals(0, netease.searches)
    }

    @Test
    fun should_offerWholeNetEaseLyrics_when_qqHasNoTranslation() = runTest {
        qq.payloads["97773"] = LyricPayload("[00:01.00]Hello\n[00:02.00]World")
        netease.searchHit = SongMatch("n1", "Track", "Artist")
        netease.payloads["n1"] = LyricPayload(
            "[00:01.10]Hello there\n[00:02.20]World\n[00:03.30]Again",
            "[00:01.10]你好\n[00:03.30]再一次",
        )

        val result = translate(currentProvider = "qq", currentSongId = "97773")

        val offer = (result as LyricsTranslationResult.ProviderSwitchAvailable).offer
        assertEquals("netease", offer.providerName)
        assertEquals("n1", offer.providerSongId)
        // The offer carries NetEase's own lines; its translations index into them, not into QQ's.
        assertEquals(LrcParser.parse(offer.rawLrc), offer.lyrics)
        assertEquals(mapOf(0 to "你好", 2 to "再一次"), offer.translations)
        // Nothing replaces the cached lyrics until the user accepts the switch.
        coVerify(exactly = 0) { lyricsCacheDao.upsert(any()) }
        assertEquals(0, huawei.searches)
    }

    @Test
    fun should_neverOfferManualOnlyProvider_when_switching() = runTest {
        qq.payloads["97773"] = LyricPayload("[00:01.00]Hello")
        huawei.searchHit = SongMatch("hw1|1|Hello", "Hello", "Artist")
        huawei.payloads["hw1|1|Hello"] = LyricPayload("[00:01.00]Hello", "[00:01.00]你好")

        val result = translate(currentProvider = "qq", currentSongId = "97773")

        assertEquals(LyricsTranslationResult.ApiKeyMissing, result)
        assertEquals(1, netease.searches)
        assertEquals(0, huawei.searches)
    }

    @Test
    fun should_notOfferEarlierProvider_when_netEaseHasNoTranslation() = runTest {
        // Unchanged behaviour: NetEase without a translation falls through to Gemini.
        netease.payloads["n1"] = LyricPayload("[00:01.00]Hello")
        qq.searchHit = SongMatch("97773", "Hello", "Artist")
        qq.payloads["97773"] = LyricPayload("[00:01.00]Hello", "[00:01.00]你好")

        val result = translate(currentProvider = "netease", currentSongId = "n1")

        assertEquals(LyricsTranslationResult.ApiKeyMissing, result)
        assertEquals(0, qq.searches)
    }

    @Test
    fun should_fallBackToGemini_when_huaweiHasNoTranslationForTheSong() = runTest {
        huawei.payloads["hw1|1|Hello"] = LyricPayload("[00:01.00]Hello")

        val result = translate(currentProvider = "huawei", currentSongId = "hw1|1|Hello")

        assertEquals(LyricsTranslationResult.ApiKeyMissing, result)
        assertEquals(listOf("hw1|1|Hello"), huawei.fetchedIds)
        assertEquals(0, qq.searches + netease.searches)
    }

    @Test
    fun should_fallBackToGemini_when_currentSourceHasNoNativeTranslation() = runTest {
        for (current in listOf("lrclib", "manual", "subsonic", null)) {
            val result = translate(currentProvider = current, currentSongId = "id")

            assertEquals(current.toString(), LyricsTranslationResult.ApiKeyMissing, result)
        }
        assertEquals(0, qq.searches + netease.searches + huawei.searches + lrclib.searches)
        assertTrue((qq.fetchedIds + netease.fetchedIds + huawei.fetchedIds + lrclib.fetchedIds).isEmpty())
    }

    @Test
    fun should_skipProviderTranslation_when_targetLanguageIsNotChinese() = runTest {
        target("English")
        huawei.payloads["hw1|1|Hello"] = LyricPayload("[00:01.00]こんにちは", "[00:01.00]你好")

        val result = translate(currentProvider = "huawei", currentSongId = "hw1|1|Hello")

        assertEquals(LyricsTranslationResult.ApiKeyMissing, result)
        assertTrue(huawei.fetchedIds.isEmpty())
    }

    // ── alignment ────────────────────────────────────────────────────────────

    @Test
    fun should_pairDuplicateTimestampsByPosition_when_groupSizesMatch() {
        val lyrics = LrcParser.parse("[00:00.00]Title - Artist\n[00:00.00]First line\n[00:04.00]Second")

        val translations = buildProviderTranslations(lyrics, "[00:00.00]//\n[00:00.00]第一行\n[00:04.00]第二")

        assertEquals(mapOf(1 to "第一行", 2 to "第二"), translations)
    }

    @Test
    fun should_shareGroupTranslation_when_groupSizesDiffer() {
        // QQ drops its "//" lines, so its groups can be smaller; that path is unchanged.
        val lyrics = LrcParser.parse("[00:00.00]Title - Artist\n[00:00.00]First line\n[00:04.00]Second")

        val translations = buildProviderTranslations(lyrics, "[00:00.00]第一行\n[00:04.00]第二")

        assertEquals(mapOf(0 to "第一行", 1 to "第一行", 2 to "第二"), translations)
    }

    @Test
    fun should_zipByIndex_when_noTimestampMatches() {
        val lyrics = LrcParser.parse("[00:01.00]One\n[00:02.00]Two")

        val translations = buildProviderTranslations(lyrics, "[00:01.50]一\n[00:02.50]//")

        assertEquals(mapOf(0 to "一"), translations)
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private fun target(language: String) {
        coEvery { geminiConfigDao.getConfig() } returns flowOf(GeminiConfig(apiKey = "", targetLanguage = language))
    }

    private suspend fun translate(currentProvider: String?, currentSongId: String?): LyricsTranslationResult =
        repository.translateLyrics(
            trackId = MediaId.spotify("track-1"),
            title = "Track",
            artist = "Artist",
            lines = listOf("Hello there", "World again"),
            currentLyricsProviderName = currentProvider,
            currentLyricsProviderSongId = currentSongId,
        )

    private class StubProvider(
        override val name: String,
        override val automatic: Boolean = true,
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
        /** Shaped like a Huawei file: title and credits share 00:00.00 with the first sung line. */
        const val HUAWEI_FILE = "[00:00.00]Track - Artist\n" +
            "[00:00.00]词：Someone\n" +
            "[00:00.00]Opening line ^开场白\n" +
            "[00:05.00]Second line^第二行\n"
    }
}
