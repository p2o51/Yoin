package com.gpo.yoin.shared.data.lyrics

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

class LyricsRegistryTest {

    @Test
    fun should_return_multiple_candidates_from_each_provider() = runTest {
        val registry = LyricsRegistry(
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
            .flatMap(ProviderSection::candidates)

        assertEquals(listOf("first", "first", "second"), results.map { it.providerName })
        assertEquals(listOf("a", "b", "c"), results.map { it.songId })
        assertEquals(listOf("Track A", "Track B", "Track C"), results.map { it.title })
        assertEquals(listOf("Artist A", "Artist B", "Artist C"), results.map { it.artist })
    }

    @Test
    fun should_keep_provider_sections_even_when_provider_has_no_results() = runTest {
        val registry = LyricsRegistry(
            providers = listOf(
                FakeProvider(
                    name = "first",
                    matches = listOf(SongMatch("a", "Track A", "Artist A")),
                ),
                FakeProvider(name = "empty"),
            ),
        )

        val sections = registry.search(title = "track", artist = "", limitPerProvider = 3)

        assertEquals(listOf("first", "empty"), sections.map { it.providerName })
        assertEquals(listOf("a"), sections.first().candidates.map { it.songId })
        assertEquals(emptyList<LyricsCandidate>(), sections[1].candidates)
    }

    @Test
    fun should_fetch_normalized_lyric_for_selected_provider_result() = runTest {
        val registry = LyricsRegistry(
            providers = listOf(
                FakeProvider(name = "first"),
                FakeProvider(
                    name = "second",
                    lyrics = mapOf("song-1" to "[00:01.00]&amp; line\n\n"),
                ),
            ),
        )

        val hit = registry.fetchSelected(providerName = "second", songId = "song-1")

        assertEquals("second", hit?.providerName)
        assertEquals("[00:01.00]& line", hit?.lrc)
    }

    @Test
    fun should_return_null_for_unknown_selected_provider() = runTest {
        val registry = LyricsRegistry(providers = listOf(FakeProvider(name = "first")))

        assertNull(registry.fetchSelected(providerName = "missing", songId = "song-1"))
    }

    @Test
    fun should_skip_fetch_when_provider_cannot_use_cached_id() = runTest {
        val registry = LyricsRegistry(
            providers = listOf(
                FakeProvider(
                    name = "qq-like",
                    lyrics = mapOf("abc123" to "[00:01.00]stale mid"),
                    fetchableIds = { songId -> songId.toLongOrNull() != null },
                ),
            ),
        )

        assertEquals(false, registry.canFetch("qq-like", "abc123"))
        assertNull(registry.fetchSelected(providerName = "qq-like", songId = "abc123"))
    }

    @Test
    fun should_skipManualOnlyProvider_when_fetchingAutomatically() = runTest {
        val manualOnly = FakeProvider(
            name = "manual-only",
            matches = listOf(SongMatch("m", "Track", "Artist")),
            lyrics = mapOf("m" to "[00:01.00]manual"),
            automatic = false,
        )
        val registry = LyricsRegistry(
            providers = listOf(
                manualOnly,
                FakeProvider(
                    name = "auto",
                    matches = listOf(SongMatch("a", "Track", "Artist")),
                    lyrics = mapOf("a" to "[00:01.00]auto"),
                ),
            ),
        )

        val hit = registry.autoFetch(title = "Track", artist = "Artist", durationMs = null)

        assertEquals("auto", hit?.providerName)
        assertEquals(0, manualOnly.searches)
    }

    @Test
    fun should_listManualOnlyProvider_when_searchingByProvider() = runTest {
        val registry = LyricsRegistry(
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

        val sections = registry.search(title = "track", artist = "", limitPerProvider = 3)

        assertEquals(listOf("first", "manual-only", "last"), registry.providerNames)
        assertEquals(listOf("first", "manual-only", "last"), sections.map { it.providerName })
        assertEquals(listOf("m"), sections[1].candidates.map { it.songId })
    }

    @Test
    fun should_fetchSelectedLyric_when_providerIsManualOnly() = runTest {
        val registry = LyricsRegistry(
            providers = listOf(
                FakeProvider(name = "manual-only", lyrics = mapOf("m" to "[00:01.00]picked"), automatic = false),
            ),
        )

        assertEquals("[00:01.00]picked", registry.fetchSelected("manual-only", "m")?.lrc)
    }

    @Test
    fun should_offerOnlyLaterAutomaticTranslatingProviders_when_listingSwitchCandidates() {
        val zh = NativeTranslation.SimplifiedChinese
        val registry = LyricsRegistry(
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
        val registry = LyricsRegistry(
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
        val registry = LyricsRegistry()
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

    @Test
    fun should_returnParsedLyricsAndProviderIds_when_autoFetching() = runTest {
        val registry = LyricsRegistry(
            providers = listOf(
                FakeProvider(
                    name = "auto",
                    matches = listOf(SongMatch("a-1", "Track", "Artist")),
                    lyrics = mapOf("a-1" to "[00:01.00]one\n[00:02.50]two"),
                ),
            ),
        )

        val hit = registry.autoFetch(title = "  Track ", artist = "Artist  ", durationMs = 200_000)!!

        assertEquals("auto", hit.providerName)
        assertEquals("a-1", hit.providerSongId)
        assertEquals("[00:01.00]one\n[00:02.50]two", hit.lrc)
        assertTrue(hit.lyrics.synced)
        assertEquals(listOf(1_000L, 2_500L), hit.lyrics.lines.map { it.timeMs })
    }

    @Test
    fun should_fallThrough_when_firstAutomaticProviderHasNoLyrics() = runTest {
        val registry = LyricsRegistry(
            providers = listOf(
                FakeProvider(name = "nothing"),
                FakeProvider(name = "blank", matches = listOf(SongMatch("b", "T", "A")), lyrics = mapOf("b" to " \n ")),
                FakeProvider(name = "third", matches = listOf(SongMatch("c", "T", "A")), lyrics = mapOf("c" to "[00:01.00]x")),
            ),
        )

        assertEquals("third", registry.autoFetch("T", "A", null)?.providerName)
    }

    @Test
    fun should_returnNull_when_titleOrArtistIsBlank() = runTest {
        val provider = FakeProvider(name = "p", matches = listOf(SongMatch("a", "T", "A")), lyrics = mapOf("a" to "[00:01.00]x"))
        val registry = LyricsRegistry(providers = listOf(provider))

        assertNull(registry.autoFetch("  ", "A", null))
        assertNull(registry.autoFetch("T", "", null))
        assertEquals(0, provider.searches)
        assertEquals(emptyList<ProviderSection>(), registry.search("   "))
    }

    @Test
    fun should_searchAgainUnderAlternateNames_when_shownNamesAreNonLatinAndFindNothing() = runTest {
        val provider = FakeProvider(
            name = "p",
            matchesFor = { title, _ -> if (title == "Pop Sound") listOf(SongMatch("p1", "Pop Sound", "Kim Petras")) else emptyList() },
            lyrics = mapOf("p1" to "[00:01.00]x"),
        )
        val registry = LyricsRegistry(providers = listOf(provider))

        val hit = registry.autoFetchWithAlternateNames("ポップ・サウンド", "キム・ペトラス", "Pop Sound", "Kim Petras", null)

        assertEquals("p1", hit?.providerSongId)
        assertEquals(listOf("ポップ・サウンド", "Pop Sound"), provider.searchedTitles)
    }

    @Test
    fun should_notSearchAlternateNames_when_shownNamesAreLatinOrAlternatesAreTheSame() = runTest {
        val latin = FakeProvider(name = "p")
        LyricsRegistry(providers = listOf(latin)).autoFetchWithAlternateNames("Sunny", "Yorushika", "Haru", "Yorushika", null)
        assertEquals(listOf("Sunny"), latin.searchedTitles)

        val same = FakeProvider(name = "p")
        LyricsRegistry(providers = listOf(same)).autoFetchWithAlternateNames("晴る", "ヨルシカ", "晴る", "ヨルシカ", null)
        assertEquals(listOf("晴る"), same.searchedTitles)
    }

    @Test
    fun should_alignProviderTranslation_when_fetchingSelectedWithTranslation() = runTest {
        val registry = LyricsRegistry(
            providers = listOf(
                FakeProvider(
                    name = "zh",
                    nativeTranslation = NativeTranslation.SimplifiedChinese,
                    payloads = mapOf(
                        "s1" to LyricPayload(
                            lyric = "[00:01.00]Hello\n[00:02.00]World",
                            translatedLyric = "[00:01.00]你好\n[00:02.00]//",
                        ),
                    ),
                ),
            ),
        )

        val hit = registry.fetchSelectedWithTranslation("zh", "s1")!!

        assertEquals("[00:01.00]你好\n[00:02.00]//", hit.translatedLrc)
        assertEquals(mapOf(0 to "你好"), hit.translations)
        assertEquals(2, hit.lyrics.lines.size)
        assertEquals("s1", hit.providerSongId)
    }

    @Test
    fun should_searchWhenSongIdUnusable_and_returnNullWithoutTranslation_when_fetchingTranslation() = runTest {
        val zh = NativeTranslation.SimplifiedChinese
        val provider = FakeProvider(
            name = "zh",
            nativeTranslation = zh,
            fetchableIds = { it.toLongOrNull() != null },
            matches = listOf(SongMatch("42", "T", "A")),
            payloads = mapOf(
                "42" to LyricPayload("[00:01.00]Hello", "[00:01.00]你好"),
                "43" to LyricPayload("[00:01.00]Hello", null),
            ),
        )
        val registry = LyricsRegistry(providers = listOf(provider))

        // A stale id can't be fetched directly: the provider is searched by title / artist instead.
        assertEquals("42", registry.fetchTranslation("zh", "stale-mid", "T", "A")?.providerSongId)
        assertEquals("42", registry.fetchTranslation("zh", null, "T", "A")?.providerSongId)
        assertEquals("42", registry.fetchTranslation("zh", "42", "T", "A")?.providerSongId)
        // A source that has the song but no translation gives nothing to offer.
        assertNull(registry.fetchTranslation("zh", "43", "T", "A"))
        assertNull(registry.fetchTranslation("missing", null, "T", "A"))
    }

    @Test
    fun should_reportErrorMessage_when_aProviderCannotAnswerManualSearch() = runTest {
        val registry = LyricsRegistry(
            providers = listOf(
                FakeProvider(name = "ok", matches = listOf(SongMatch("a", "T", "A"))),
                FakeProvider(name = "down", failure = LyricsSourceUnavailableException("Down right now")),
            ),
        )

        val sections = registry.search("t")

        assertEquals(null, sections[0].errorMessage)
        assertEquals("Down right now", sections[1].errorMessage)
        assertEquals(emptyList<LyricsCandidate>(), sections[1].candidates)
    }

    @Test
    fun should_fallBackQqThenNetEaseThenLrclib_when_usingDefaultProvidersOnOneEngine() = runTest {
        val web = FakeWeb()
        web.on("/") { status(500) }
        // QQ: every host fails. NetEase: no hit. LRCLIB: answers.
        web.onExact("/api.spotoolfy.gojyuplus.com/cloudsearch") { ok("""{"result":{"songs":[]},"code":200}""") }
        web.onExact("/lrclib.net/api/search") {
            ok("""[{"id":7,"trackName":"Pop Sound","artistName":"Kim Petras","duration":171.0}]""")
        }
        web.onExact("/lrclib.net/api/get/7") { ok("""{"syncedLyrics":"[00:01.00]hi &amp; bye"}""") }
        val registry = LyricsRegistry.withEngine(web.engine)

        val hit = registry.autoFetch("Pop Sound", "Kim Petras", 171_172)!!

        assertEquals("lrclib", hit.providerName)
        assertEquals("7", hit.providerSongId)
        assertEquals("[00:01.00]hi & bye", hit.lrc)
        val hosts = web.seen.map { it.host }.distinct()
        assertEquals(listOf("c.y.qq.com", "shc.y.qq.com", "u.y.qq.com", "u6.y.qq.com", "api.spotoolfy.gojyuplus.com", "lrclib.net"), hosts)
        assertEquals(false, web.seen.any { it.host.contains("dbankcloud") }, "Huawei is manual-search only")
        registry.close()
    }
}

private class FakeProvider(
    override val name: String,
    private val matches: List<SongMatch> = emptyList(),
    private val matchesFor: ((title: String, artist: String) -> List<SongMatch>)? = null,
    private val lyrics: Map<String, String> = emptyMap(),
    private val payloads: Map<String, LyricPayload> = emptyMap(),
    private val fetchableIds: (String) -> Boolean = { true },
    override val automatic: Boolean = true,
    override val nativeTranslation: NativeTranslation? = null,
    private val failure: Exception? = null,
) : LyricProvider() {

    var searches = 0
        private set
    val searchedTitles = mutableListOf<String>()

    override fun canFetch(songId: String): Boolean = fetchableIds(songId)

    override suspend fun search(title: String, artist: String, durationMs: Long?): SongMatch? {
        searches += 1
        searchedTitles += title
        return (matchesFor?.invoke(title, artist) ?: matches).firstOrNull()
    }

    override suspend fun searchMultiple(
        title: String,
        artist: String,
        limit: Int,
    ): List<SongMatch> {
        failure?.let { throw it }
        return (matchesFor?.invoke(title, artist) ?: matches).take(limit)
    }

    override suspend fun fetchLyric(songId: String): String? = lyrics[songId] ?: payloads[songId]?.lyric

    override suspend fun fetchLyricWithTranslation(songId: String): LyricPayload? =
        payloads[songId] ?: super.fetchLyricWithTranslation(songId)
}
