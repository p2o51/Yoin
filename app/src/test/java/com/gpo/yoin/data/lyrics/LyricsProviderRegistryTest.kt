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
}

private class FakeProvider(
    override val name: String,
    private val matches: List<SongMatch> = emptyList(),
    private val lyrics: Map<String, String> = emptyMap(),
) : LyricProvider() {

    override suspend fun search(title: String, artist: String): SongMatch? = matches.firstOrNull()

    override suspend fun searchMultiple(
        title: String,
        artist: String,
        limit: Int,
    ): List<SongMatch> = matches.take(limit)

    override suspend fun fetchLyric(songId: String): String? = lyrics[songId]
}
