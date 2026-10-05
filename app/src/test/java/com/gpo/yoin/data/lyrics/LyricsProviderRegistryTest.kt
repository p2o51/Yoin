package com.gpo.yoin.data.lyrics

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LyricsProviderRegistryTest {

    @Test
    fun should_return_multiple_candidates_from_each_provider() = runTest {
        val registry = LyricsProviderRegistry(
            providers = listOf(
                FakeProvider(
                    name = "first",
                    matches = listOf(
                        SongMatch("a", "Track A", "Artist A"),
                        SongMatch("b", "Track B", "Artist B"),
                    ),
                ),
                FakeProvider(
                    name = "second",
                    matches = listOf(SongMatch("c", "Track C", "Artist C")),
                ),
            ),
        )

        val results = registry.search(title = "track", artist = "", limitPerProvider = 3)

        assertEquals(listOf("first", "first", "second"), results.map { it.providerName })
        assertEquals(listOf("a", "b", "c"), results.map { it.match.songId })
    }

    @Test
    fun should_keep_provider_sections_even_when_provider_has_no_results() = runTest {
        val registry = LyricsProviderRegistry(
            providers = listOf(
                FakeProvider(
                    name = "first",
                    matches = listOf(SongMatch("a", "Track A", "Artist A")),
                ),
                FakeProvider(name = "empty"),
            ),
        )

        val sections = registry.searchByProvider(title = "track", artist = "", limitPerProvider = 3)

        assertEquals(listOf("first", "empty"), sections.map { it.providerName })
        assertEquals(listOf("a"), sections.first().matches.map { it.songId })
        assertEquals(emptyList<SongMatch>(), sections[1].matches)
    }

    @Test
    fun should_fetch_normalized_lyric_for_selected_provider_result() = runTest {
        val registry = LyricsProviderRegistry(
            providers = listOf(
                FakeProvider(name = "first"),
                FakeProvider(
                    name = "second",
                    lyrics = mapOf("song-1" to "[00:01.00]&amp; line\n\n"),
                ),
            ),
        )

        val hit = registry.fetchSelectedLyric(providerName = "second", songId = "song-1")

        assertEquals("second", hit?.providerName)
        assertEquals("[00:01.00]& line", hit?.lrc)
    }

    @Test
    fun should_return_null_for_unknown_selected_provider() = runTest {
        val registry = LyricsProviderRegistry(providers = listOf(FakeProvider(name = "first")))

        assertNull(registry.fetchSelectedLyric(providerName = "missing", songId = "song-1"))
    }

    @Test
    fun should_skip_fetch_when_provider_cannot_use_cached_id() = runTest {
        val registry = LyricsProviderRegistry(
            providers = listOf(
                FakeProvider(
                    name = "qq-like",
                    lyrics = mapOf("abc123" to "[00:01.00]stale mid"),
                    fetchableIds = { songId -> songId.toLongOrNull() != null },
                ),
            ),
        )

        assertEquals(false, registry.canFetch("qq-like", "abc123"))
        assertNull(registry.fetchSelectedLyric(providerName = "qq-like", songId = "abc123"))
    }

    @Test
    fun should_skipManualOnlyProvider_when_fetchingAutomatically() = runTest {
        val manualOnly = FakeProvider(
            name = "manual-only",
            matches = listOf(SongMatch("m", "Track", "Artist")),
            lyrics = mapOf("m" to "[00:01.00]manual"),
            automatic = false,
        )
        val registry = LyricsProviderRegistry(
            providers = listOf(
                manualOnly,
                FakeProvider(
                    name = "auto",
                    matches = listOf(SongMatch("a", "Track", "Artist")),
                    lyrics = mapOf("a" to "[00:01.00]auto"),
                ),
            ),
        )

        val hit = registry.fetchLyric(title = "Track", artist = "Artist")

        assertEquals("auto", hit?.providerName)
        assertEquals(0, manualOnly.searches)
    }

    @Test
    fun should_listManualOnlyProvider_when_searchingByProvider() = runTest {
        val registry = LyricsProviderRegistry(
            providers = listOf(
                FakeProvider(name = "first", matches = listOf(SongMatch("a", "Track A", "Artist"))),
                FakeProvider(
                    name = "manual-only",
                    matches = listOf(SongMatch("m", "Track M", "Artist")),
                    automatic = false,
                ),
                FakeProvider(name = "last"),
            ),
        )

        val sections = registry.searchByProvider(title = "track", artist = "", limitPerProvider = 3)

        assertEquals(listOf("first", "manual-only", "last"), registry.providerNames)
        assertEquals(listOf("first", "manual-only", "last"), sections.map { it.providerName })
        assertEquals(listOf("m"), sections[1].matches.map { it.songId })
    }

    @Test
    fun should_fetchSelectedLyric_when_providerIsManualOnly() = runTest {
        val registry = LyricsProviderRegistry(
            providers = listOf(
                FakeProvider(name = "manual-only", lyrics = mapOf("m" to "[00:01.00]picked"), automatic = false),
            ),
        )

        assertEquals("[00:01.00]picked", registry.fetchSelectedLyric("manual-only", "m")?.lrc)
    }

    @Test
    fun should_offerOnlyLaterAutomaticTranslatingProviders_when_listingSwitchCandidates() {
        val zh = NativeTranslation.SimplifiedChinese
        val registry = LyricsProviderRegistry(
            providers = listOf(
                FakeProvider(name = "a", nativeTranslation = zh),
                FakeProvider(name = "b-manual", nativeTranslation = zh, automatic = false),
                FakeProvider(name = "c-plain"),
                FakeProvider(name = "d", nativeTranslation = zh),
            ),
        )

        assertEquals(listOf("d"), registry.translationSwitchCandidates("a", "Simplified Chinese"))
        assertEquals(listOf("d"), registry.translationSwitchCandidates("b-manual", "Simplified Chinese"))
        assertEquals(emptyList<String>(), registry.translationSwitchCandidates("d", "Simplified Chinese"))
        assertEquals(emptyList<String>(), registry.translationSwitchCandidates("manual", "Simplified Chinese"))
        assertEquals(emptyList<String>(), registry.translationSwitchCandidates("a", "English"))
    }

    @Test
    fun should_serveChineseTargetsOnly_when_providerHasSimplifiedTranslation() {
        val registry = LyricsProviderRegistry(
            providers = listOf(
                FakeProvider(name = "zh", nativeTranslation = NativeTranslation.SimplifiedChinese),
                FakeProvider(name = "plain"),
            ),
        )

        assertEquals(true, registry.servesNativeTranslation("zh", "Simplified Chinese"))
        assertEquals(true, registry.servesNativeTranslation("zh", "Traditional Chinese"))
        assertEquals(false, registry.servesNativeTranslation("zh", "English"))
        assertEquals(false, registry.servesNativeTranslation("plain", "Simplified Chinese"))
        assertEquals(false, registry.servesNativeTranslation("manual", "Simplified Chinese"))
    }

    @Test
    fun should_keepQqNetEaseSwitchAndAddManualOnlyHuawei_when_usingDefaultProviders() {
        val registry = LyricsProviderRegistry()
        val zh = "Simplified Chinese"

        assertEquals(listOf("qq", "netease", "huawei", "lrclib"), registry.providerNames)
        assertEquals(
            listOf(true, true, true, false),
            registry.providerNames.map { registry.servesNativeTranslation(it, zh) },
        )
        // Same offers as before the generalisation: only QQ → NetEase.
        assertEquals(listOf("netease"), registry.translationSwitchCandidates("qq", zh))
        assertEquals(emptyList<String>(), registry.translationSwitchCandidates("netease", zh))
        assertEquals(emptyList<String>(), registry.translationSwitchCandidates("huawei", zh))
        assertEquals(emptyList<String>(), registry.translationSwitchCandidates("lrclib", zh))
    }
}

private class FakeProvider(
    override val name: String,
    private val matches: List<SongMatch> = emptyList(),
    private val lyrics: Map<String, String> = emptyMap(),
    private val fetchableIds: (String) -> Boolean = { true },
    override val automatic: Boolean = true,
    override val nativeTranslation: NativeTranslation? = null,
) : LyricProvider() {

    var searches = 0
        private set

    override fun canFetch(songId: String): Boolean = fetchableIds(songId)

    override suspend fun search(title: String, artist: String): SongMatch? {
        searches += 1
        return matches.firstOrNull()
    }

    override suspend fun searchMultiple(
        title: String,
        artist: String,
        limit: Int,
    ): List<SongMatch> = matches.take(limit)

    override suspend fun fetchLyric(songId: String): String? = lyrics[songId]
}
