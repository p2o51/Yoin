package com.gpo.yoin.player

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlayTraceTest {

    @Test
    fun should_cutTokens_when_lineCarriesBearerOrOAuthValues() {
        val out = PlayTrace.redact("GET /me/player Bearer abc.def-123 refresh_token=r1&code=xyz access_token=a9")
        assertFalse(out.contains("abc.def-123"))
        assertFalse(out.contains("r1"))
        assertFalse(out.contains("xyz"))
        assertFalse(out.contains("a9"))
        assertTrue(out.contains("Bearer …"))
    }

    @Test
    fun should_cutSubsonicCredentials_when_lineCarriesAStreamUrl() {
        val out = PlayTrace.redact(
            "failed https://me:secret@music.example.com/rest/stream.view?u=alice&t=deadbeef&s=salt1&p=pw&apiKey=k&id=42",
        )
        listOf("alice", "deadbeef", "salt1", "=pw", "apiKey=k", "me:secret").forEach {
            assertFalse("$it leaked: $out", out.contains(it))
        }
        assertTrue(out.contains("id=42"))
    }

    @Test
    fun should_keepOnlyHostEndpointAndSafeParams_when_describingAStreamUrl() {
        val out = PlayTrace.describe(
            "https://music.example.com/rest/stream.view?u=alice&t=deadbeef&s=salt&v=1.16.1&c=Yoin&id=42&maxBitRate=320&format=mp3",
        )
        assertEquals("https://music.example.com/…/stream.view?id=42&maxBitRate=320&format=mp3", out)
    }

    @Test
    fun should_keepOnlyTheLastLines_when_moreThanCapacityAreAppended() {
        val ring = PlayTraceRing(capacity = 3, file = null)
        (1..5).forEach { ring.append("line $it") }
        assertEquals(listOf("line 3", "line 4", "line 5"), ring.snapshot())
    }

    @Test
    fun should_readThePreviousRunsTail_when_theFileAlreadyHasLines() {
        val file = File(Files.createTempDirectory("playtrace").toFile(), "playtrace.log")
        PlayTraceRing(capacity = 3, file = file).apply { (1..4).forEach { append("old $it") } }
        val next = PlayTraceRing(capacity = 3, file = file)
        next.append("new 1")
        assertEquals(listOf("old 3", "old 4", "new 1"), next.snapshot())
    }

    @Test
    fun should_cutTheFileBackToCapacity_when_capacityMoreLinesWereWritten() {
        val file = File(Files.createTempDirectory("playtrace").toFile(), "playtrace.log")
        val ring = PlayTraceRing(capacity = 3, file = file)
        (1..7).forEach { ring.append("line $it") }
        assertTrue(file.readLines().filter { it.isNotEmpty() }.size <= 6)
        assertEquals(listOf("line 5", "line 6", "line 7"), file.readLines().filter { it.isNotEmpty() }.takeLast(3))
    }
}
