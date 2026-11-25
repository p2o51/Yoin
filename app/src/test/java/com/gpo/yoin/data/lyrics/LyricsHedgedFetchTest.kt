package com.gpo.yoin.data.lyrics

import java.util.Collections
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [LyricsProviderRegistry.fetchLyric]: the sequential winner, the sequential request count
 * when the first provider answers in time, a hedge only when it is slow (perf review C, S2).
 */
class LyricsHedgedFetchTest {

    private val log: MutableList<String> = Collections.synchronizedList(mutableListOf())

    private inner class Provider(
        override val name: String,
        private val searchMs: Long,
        private val fetchMs: Long = 50,
        private val hit: Boolean = true,
    ) : LyricProvider() {
        val searches = AtomicInteger()
        val fetches = AtomicInteger()

        override suspend fun search(title: String, artist: String, durationMs: Long?): SongMatch? {
            searches.incrementAndGet()
            log += "$name.search"
            delay(searchMs)
            return if (hit) SongMatch("$name-1", title, artist) else null
        }

        override suspend fun fetchLyric(songId: String): String? {
            fetches.incrementAndGet()
            log += "$name.fetch"
            delay(fetchMs)
            return "[00:01.00]$name"
        }
    }

    /** Ported as is: QQ search ~300 ms + lyric ~200 ms; NetEase would answer its search in 150 ms. */
    @Test
    fun whenTopPriorityHits_lowerPriorityProvidersAreNotQueried() = runTest {
        val qq = Provider("qq", searchMs = 300, fetchMs = 200)
        val netease = Provider("netease", searchMs = 150, fetchMs = 150)
        val lrclib = Provider("lrclib", searchMs = 600, fetchMs = 200)
        val hit = LyricsProviderRegistry(listOf(qq, netease, lrclib)).fetchLyric("晴る", "ヨルシカ", 250_000)
        assertEquals("qq", hit?.providerName)
        assertEquals("NetEase searches when QQ already had the song", 0, netease.searches.get())
        assertEquals("NetEase lyric downloads when QQ already had the song", 0, netease.fetches.get())
        assertEquals("LRCLIB searches when QQ already had the song", 0, lrclib.searches.get())
        // The same two requests as the sequential path.
        assertEquals(listOf("qq.search", "qq.fetch"), log.toList())
    }

    @Test
    fun theHigherPriorityProviderWins_evenWhenALowerOneAnswersFirst() = runTest {
        val slowTop = Provider("qq", searchMs = 900)
        val fastLow = Provider("lrclib", searchMs = 10)
        val hit = LyricsProviderRegistry(listOf(slowTop, fastLow)).fetchLyric("Song", "Artist")
        assertEquals("qq", hit?.providerName)
        assertEquals("[00:01.00]qq", hit?.lrc)
    }

    @Test
    fun aSlowSearchHedgesTheNextSearch_butNeverItsLyric() = runTest {
        val slowTop = Provider("qq", searchMs = 900)
        val next = Provider("netease", searchMs = 10)
        val hit = LyricsProviderRegistry(listOf(slowTop, next)).fetchLyric("Song", "Artist")
        assertEquals("qq", hit?.providerName)
        assertEquals("hedged search", 1, next.searches.get())
        assertEquals("a lower provider's lyric fetched before the higher one missed", 0, next.fetches.get())
    }

    @Test
    fun aMissFallsThroughInOrder_andTheHedgedSearchIsReused() = runTest {
        val top = Provider("qq", searchMs = 900, hit = false)
        val next = Provider("netease", searchMs = 100)
        val hit = LyricsProviderRegistry(listOf(top, next)).fetchLyric("Song", "Artist")
        assertEquals("netease", hit?.providerName)
        assertEquals(1, next.searches.get())
        // Hedged at 400 ms, so its search was in by the time QQ missed (900 ms): only the lyric remains.
        assertEquals(900L + 50L, currentTime)
        assertTrue(log.indexOf("qq.search") < log.indexOf("netease.search"))
    }

    @Test
    fun aFastMissStartsTheNextProviderWithoutWaitingForTheHedge() = runTest {
        val top = Provider("qq", searchMs = 50, hit = false)
        val next = Provider("netease", searchMs = 50)
        val hit = LyricsProviderRegistry(listOf(top, next)).fetchLyric("Song", "Artist")
        assertEquals("netease", hit?.providerName)
        assertEquals(50L + 50L + 50L, currentTime)
    }

    @Test
    fun aProviderThatTimesOut_overlapsTheNextSearchWithItsWait() = runTest {
        // QQ stalls until its 15 s callTimeout, then misses. Sequentially NetEase's search would
        // only start then; hedged it ran meanwhile, so only its lyric is left after the miss.
        val stalled = Provider("qq", searchMs = 15_000, hit = false)
        val next = Provider("netease", searchMs = 300)
        val hit = LyricsProviderRegistry(listOf(stalled, next)).fetchLyric("Song", "Artist")
        assertEquals("netease", hit?.providerName)
        assertEquals(15_000L + 50L, currentTime) // sequential: 15_000 + 300 + 50
    }

    @Test
    fun noAutomaticProviders_returnsNull() = runTest {
        assertNull(LyricsProviderRegistry(emptyList()).fetchLyric("Song", "Artist"))
    }

    @Test
    fun aThrowingProviderCountsAsAMiss() = runTest {
        val throwing = object : LyricProvider() {
            override val name = "qq"
            override suspend fun search(title: String, artist: String, durationMs: Long?): SongMatch? =
                throw IllegalStateException("boom")
            override suspend fun fetchLyric(songId: String): String? = null
        }
        val next = Provider("netease", searchMs = 10)
        assertEquals("netease", LyricsProviderRegistry(listOf(throwing, next)).fetchLyric("Song", "Artist")?.providerName)
    }
}
