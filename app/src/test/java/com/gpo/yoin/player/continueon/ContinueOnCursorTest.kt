package com.gpo.yoin.player.continueon

import com.gpo.yoin.data.repository.ActivityContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ContinueOnCursorTest {
    private val cursor = ContinueOnCursor(
        provider = "subsonic",
        accountKey = "acct",
        trackId = "t2",
        title = "Song",
        artist = "Artist",
        album = "Album",
        cover = "al-1",
        queueIds = listOf("t1", "t2", "t3"),
        index = 1,
        positionMs = 61_000L,
        capturedAtMs = 1_000L,
        playing = true,
        repeatMode = 2,
        shuffle = true,
        context = ContinueOnCursor.Context(ContinueOnCursor.Context.Kind.Album, "subsonic:al-1", "Album"),
        deviceName = "Pixel 9",
        pause = ContinueOnCursor.PauseChannel(listOf("192.168.1.20"), 40_123, "tok"),
    )

    @Test
    fun should_readBackTheSameCursor_when_flattenedAndRead() {
        val entries = cursor.toEntries()
        assertEquals(cursor, ContinueOnCursor.fromEntries(entries::get))
    }

    @Test
    fun should_useOnlyBundleSafeValues_when_flattened() {
        cursor.toEntries().values.forEach { value ->
            assertTrue(
                value is String || value is Int || value is Long || value is Boolean || value is Array<*>,
            )
        }
        assertTrue(cursor.toEntries().keys.all { it in ContinueOnCursor.KEY_PREFIXED })
    }

    @Test
    fun should_readNothing_when_versionIsUnknown() {
        val entries = cursor.toEntries() + (ContinueOnCursor.KEY_VERSION to 99)
        assertNull(ContinueOnCursor.fromEntries(entries::get))
    }

    @Test
    fun should_readNothing_when_noCursorIsThere() {
        assertNull(ContinueOnCursor.fromEntries { null })
    }

    @Test
    fun should_fallBackToTheTrackAlone_when_queueDoesNotHoldItAtIndex() {
        val entries = cursor.toEntries() + (ContinueOnCursor.KEY_INDEX to 0)
        val read = ContinueOnCursor.fromEntries(entries::get)!!
        assertEquals(listOf("t2"), read.queueIds)
        assertEquals(0, read.index)
    }

    @Test
    fun should_dropThePauseChannel_when_itHasNoToken() {
        val entries = cursor.toEntries() - ContinueOnCursor.KEY_PAUSE_TOKEN
        assertNull(ContinueOnCursor.fromEntries(entries::get)!!.pause)
    }

    @Test
    fun should_catchUpThePosition_when_itWasPlaying() {
        assertEquals(66_000L, cursor.positionAt(nowMs = 6_000L))
        assertEquals(61_000L, cursor.copy(playing = false).positionAt(nowMs = 6_000L))
    }

    @Test
    fun should_stopShortOfTheEnd_when_catchingUpPastIt() {
        assertEquals(87_000L, cursor.positionAt(nowMs = 60_000L, durationMs = 90_000L))
    }

    @Test
    fun should_keepTheTrackAndItsSurroundings_when_queueIsLong() {
        val queue = (0 until 1_000).map { "id$it" }
        val (window, index) = ContinueOnCursor.queueWindow(queue, 500)
        assertEquals(ContinueOnCursor.MAX_QUEUE, window.size)
        assertEquals("id500", window[index])
        assertEquals(ContinueOnCursor.MAX_QUEUE / 4, index)
    }

    @Test
    fun should_takeMoreBefore_when_theTailIsShort() {
        val queue = (0 until 1_000).map { "id$it" }
        val (window, index) = ContinueOnCursor.queueWindow(queue, 998)
        assertEquals(ContinueOnCursor.MAX_QUEUE, window.size)
        assertEquals("id998", window[index])
        assertEquals("id999", window.last())
    }

    @Test
    fun should_trimTheWindow_when_idsAreTooLongForTheExtras() {
        val queue = (0 until 200).map { "x".repeat(400) + it }
        val (window, index) = ContinueOnCursor.queueWindow(queue, 10)
        assertTrue(window.sumOf { it.length * 2 + 8 } <= ContinueOnCursor.MAX_EXTRAS_BYTES / 2)
        assertEquals(queue[10], window[index])
    }

    @Test
    fun should_restoreTheContextAsDetailPagesKeepIt_when_idIsRaw() {
        val context = ContinueOnCursor.Context(ContinueOnCursor.Context.Kind.Album, "al-1", "Album")
        assertEquals(
            ActivityContext.Album(albumId = "subsonic:al-1", albumName = "Album"),
            context.toActivityContext("subsonic"),
        )
        val full = context.copy(id = "subsonic:al-1")
        assertEquals("subsonic:al-1", (full.toActivityContext("subsonic") as ActivityContext.Album).albumId)
    }
}
