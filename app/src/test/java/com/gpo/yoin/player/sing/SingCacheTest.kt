package com.gpo.yoin.player.sing

import com.gpo.yoin.data.model.MediaId
import java.io.File
import java.nio.file.Files
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SingCacheTest {
    private val dir: File = Files.createTempDirectory("sing-cache").toFile()
    private var now = 1_000_000_000_000L
    private val cache = SingCache(dir, maxBytes = 3_000, clock = { now.also { now += 1_000 } })

    @After
    fun tearDown() {
        dir.deleteRecursively()
    }

    private fun put(key: String, bytes: Int): File {
        val part = cache.partFile(key)
        part.writeBytes(ByteArray(bytes))
        return cache.commit(key, part)
    }

    @Test
    fun should_returnFile_when_committed() {
        assertNull(cache.get("a"))
        put("a", 100)
        assertNotNull(cache.get("a"))
        assertEquals(100L, cache.sizeBytes())
    }

    @Test
    fun should_evictLeastRecentlyUsed_when_overCap() {
        put("a", 1_000)
        put("b", 1_000)
        put("c", 1_000)
        cache.get("a") // a is now the most recent; b is the oldest
        put("d", 1_000)
        assertNotNull(cache.get("a"))
        assertNull(cache.get("b"))
        assertNotNull(cache.get("c"))
        assertNotNull(cache.get("d"))
        assertTrue(cache.sizeBytes() <= 3_000)
    }

    @Test
    fun should_keepNewest_when_itAloneExceedsCap() {
        put("a", 1_000)
        put("big", 5_000)
        assertNull(cache.get("a"))
        assertNotNull(cache.get("big"))
    }

    @Test
    fun should_trimToNewCap_when_capLowered() {
        put("a", 1_000)
        put("b", 1_000)
        cache.maxBytes = 1_500
        cache.trim()
        assertNull(cache.get("a"))
        assertNotNull(cache.get("b"))
    }

    @Test
    fun should_ignorePartFiles_when_sizing() {
        cache.partFile("x").writeBytes(ByteArray(10_000))
        put("a", 1_000)
        assertEquals(1_000L, cache.sizeBytes())
        assertNotNull(cache.get("a"))
    }

    @Test
    fun should_keySongAndModelApart_when_keyed() {
        val a = SingRequest(MediaId.subsonic("1"), "p1")
        val b = SingRequest(MediaId.subsonic("1"), "p2")
        assertNotEquals(SingCache.keyFor(a, "m1"), SingCache.keyFor(b, "m1"))
        assertNotEquals(SingCache.keyFor(a, "m1"), SingCache.keyFor(a, "m2"))
        assertEquals(SingCache.keyFor(a, "m1"), SingCache.keyFor(a.copy(positionMs = 9_000), "m1"))
        assertEquals(1L shl 30, SingCache(dir).maxBytes)
        assertFalse(cache.fileFor("k").name.contains("|"))
    }

    @Test
    fun should_countAndEvictPartialTracks_when_overCap() {
        put("a", 1_000)
        cache.partialFile("p").writeBytes(ByteArray(1_000))
        assertNotNull(cache.getPartial("p"))
        assertEquals(2_000L, cache.sizeBytes())
        put("b", 1_000)
        put("c", 1_000) // over 3 000: the oldest (a) goes
        assertNull(cache.get("a"))
        assertTrue(cache.partialFile("p").isFile) // (looked at without marking it used)
        put("d", 1_000) // then the partial one
        assertNull(cache.getPartial("p"))
        assertNotNull(cache.get("b"))
    }

    @Test
    fun should_neverEvictPinned_when_beingWritten() {
        val partial = cache.partialFile("w")
        partial.writeBytes(ByteArray(2_000))
        cache.pin(partial)
        put("a", 1_000)
        put("b", 1_000)
        assertTrue(partial.isFile)
        // Finished: moved in as the song's track.
        val done = cache.commit("w", partial)
        assertTrue(done.isFile && !partial.isFile)
        assertNotNull(cache.get("w"))
        cache.unpin(partial)
    }

    @Test
    fun should_keepPartialTracks_when_clearingScratch() {
        cache.partialFile("p").writeBytes(ByteArray(10))
        cache.partFile("x").writeBytes(ByteArray(10))
        File(cache.partialFile("q").path + SingCache.REMAKE_SUFFIX).writeBytes(ByteArray(10))
        cache.clearParts()
        assertNotNull(cache.getPartial("p"))
        assertEquals(1, dir.listFiles()!!.size)
        cache.remove("p")
        assertNull(cache.getPartial("p"))
    }
}
