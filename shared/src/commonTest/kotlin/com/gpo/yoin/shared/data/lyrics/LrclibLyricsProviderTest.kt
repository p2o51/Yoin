package com.gpo.yoin.shared.data.lyrics

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

class LrclibLyricsProviderTest {

    private val web = FakeWeb()
    private val provider = LrclibLyricsProvider(engine = web.engine, baseUrl = "https://lrclib.test")

    @Test
    fun should_pickSameLength_when_firstResultIsAnotherCut() = runTest {
        web.enqueue(200, """
                [
                  {"id":1,"trackName":"Pop Sound","artistName":"Kim Petras","duration":150.0},
                  {"id":2,"trackName":"Pop Sound","artistName":"Kim Petras","duration":171.0}
                ]
                """.trimIndent())

        assertEquals("2", provider.search("Pop Sound", "Kim Petras", durationMs = 171_172)?.songId)
    }

    @Test
    fun should_keepFirstResult_when_noResultHasTheLength() = runTest {
        web.enqueue(200, """
                [
                  {"id":1,"trackName":"Pop Sound","artistName":"Kim Petras","duration":150.0},
                  {"id":2,"trackName":"Pop Sound","artistName":"Kim Petras","duration":140.0}
                ]
                """.trimIndent())

        assertEquals("1", provider.search("Pop Sound", "Kim Petras", durationMs = 171_172)?.songId)
    }

    @Test
    fun should_keepTheOriginal_when_aLaterSameLengthResultIsInstrumental() = runTest {
        web.enqueue(200, """
                [
                  {"id":1,"trackName":"Pop Sound","artistName":"Kim Petras","duration":150.0},
                  {"id":2,"trackName":"Pop Sound","artistName":"Kim Petras","duration":171.0,"instrumental":true},
                  {"id":3,"trackName":"Pop Sound (Instrumental)","artistName":"Kim Petras","duration":171.0}
                ]
                """.trimIndent())

        assertEquals("1", provider.search("Pop Sound", "Kim Petras", durationMs = 171_172)?.songId)
    }

    @Test
    fun should_keepTheOriginal_when_aLaterSameLengthResultIsAnotherArtistsSong() = runTest {
        web.enqueue(200, """
                [
                  {"id":1,"trackName":"Pop Sound","artistName":"Kim Petras","duration":150.0},
                  {"id":2,"trackName":"Pop Sound","artistName":"Losing N3mo","duration":171.0}
                ]
                """.trimIndent())

        assertEquals("1", provider.search("Pop Sound", "Kim Petras", durationMs = 171_172)?.songId)
    }

    @Test
    fun should_matchOneArtistOfACredit_when_lrclibJoinsThem() = runTest {
        web.enqueue(200, """
                [
                  {"id":1,"trackName":"Unholy","artistName":"Sam Smith, Kim Petras","duration":120.0},
                  {"id":2,"trackName":"Unholy","artistName":"Sam Smith, Kim Petras","duration":156.0}
                ]
                """.trimIndent())

        assertEquals("2", provider.search("Unholy", "Kim Petras", durationMs = 156_000)?.songId)
    }

    @Test
    fun search_returns_first_match() = runTest {
        web.enqueue(200, """
                [
                  {"id":123,"trackName":"Paranoid","artistName":"Black Sabbath"},
                  {"id":456,"trackName":"Paranoid (Remix)","artistName":"Black Sabbath"}
                ]
                """.trimIndent())

        val match = provider.search("Paranoid", "Black Sabbath", null)

        assertNotNull(match)
        assertEquals("123", match?.songId)
        assertEquals("Paranoid", match?.title)
        val recorded = web.seen.single()
        assertTrue(recorded.path.contains("/api/search"))
        assertTrue(recorded.path.contains("track_name=Paranoid"))
        assertEquals("Black Sabbath", recorded.queryParameter("artist_name"))
        assertEquals("Paranoid", recorded.queryParameter("track_name"))
        assertEquals("application/json", recorded.header("accept"))
        assertTrue(recorded.header("user-agent")!!.startsWith("Yoin/"))
    }

    @Test
    fun search_returns_null_on_empty_result() = runTest {
        web.enqueue(200, "[]")

        assertNull(provider.search("nope", "nobody", null))
    }

    @Test
    fun fetch_prefers_synced_over_plain() = runTest {
        web.enqueue(200, """
                {
                  "id": 42,
                  "syncedLyrics": "[00:01.00]synced line",
                  "plainLyrics": "plain line"
                }
                """.trimIndent())

        val lrc = provider.fetchLyric("42")

        assertEquals("[00:01.00]synced line", lrc)
        assertTrue(web.seen.single().path.endsWith("/api/get/42"))
    }

    @Test
    fun fetch_falls_back_to_plain_when_no_synced() = runTest {
        web.enqueue(200, """{"id":42,"syncedLyrics":null,"plainLyrics":"just plain"}""")

        assertEquals("just plain", provider.fetchLyric("42"))
    }

    @Test
    fun fetch_returns_null_on_404() = runTest {
        web.enqueue(404, """{"code":404}""")

        assertNull(provider.fetchLyric("missing"))
    }

    @Test
    fun getLyric_chains_search_and_fetch() = runTest {
        web.enqueue(200, """[{"id":7,"trackName":"x","artistName":"y"}]""")
        web.enqueue(200, """{"syncedLyrics":"[00:00.00]hi"}""")

        val lrc = provider.getLyric("x", "y")
        assertEquals("[00:00.00]hi", lrc)
    }
}
