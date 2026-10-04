package com.gpo.yoin.ui.home

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import com.gpo.yoin.data.memory.AlbumMemoryCandidate
import com.gpo.yoin.data.model.MediaId
import com.gpo.yoin.ui.memories.MemoryScoreKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** The Memories speech bubble's pure rules: when it speaks, where it hangs, what fits. */
class HomeMemoryBubbleTest {

    private val latest = HomeMemoryPill.Latest(
        sessionId = 7L,
        albumId = MediaId.subsonic("pang"),
        albumName = "Pang",
        artistName = "Caroline Polachek",
        coverArtUrl = null,
        scoreKind = MemoryScoreKind.ALBUM_RATING,
        scoreText = "8.4",
    )

    private fun pill(latest: HomeMemoryPill.Latest? = this.latest, notes: Int = 3, key: String = "k1") =
        HomeMemoryPill(latest = latest, noteCount = notes, scope = "subsonic|p1", newsKey = key)

    // ---- When it speaks ----

    @Test
    fun should_speakNews_when_keyDiffersFromLastSeen() {
        assertTrue(isMemoryNews(pill(), lastSeen = null))
        assertTrue(isMemoryNews(pill(key = "k2"), lastSeen = "k1"))
    }

    @Test
    fun should_stayQuiet_when_keyAlreadySeen() {
        assertFalse(isMemoryNews(pill(key = "k1"), lastSeen = "k1"))
    }

    @Test
    fun should_stayQuiet_when_nothingToSay() {
        assertFalse(isMemoryNews(null, lastSeen = null))
        assertFalse(isMemoryNews(pill(latest = null, notes = 0), lastSeen = null))
        assertFalse(pill(latest = null, notes = 0).hasSomethingToSay())
        assertTrue(pill(latest = null, notes = 2).hasSomethingToSay())
        // An unkeyed pill (previews, tests) is never news.
        assertFalse(isMemoryNews(pill(key = ""), lastSeen = null))
    }

    @Test
    fun should_changeNewsKey_when_writeOrNoteCountMoves() {
        val base = candidate(lastWrittenAt = 100L)
        val same = memoryNewsKey(base, 3)
        assertEquals("100#3", same)
        assertEquals(same, memoryNewsKey(base.copy(lastPlayedAt = 999L), 3)) // plays never count
        assertNotEquals(same, memoryNewsKey(base.copy(lastWrittenAt = 200L), 3))
        assertNotEquals(same, memoryNewsKey(base, 4))
        assertEquals("0#0", memoryNewsKey(null, 0))
        assertEquals(100L to 3, parseMemoryNewsKey(same))
        assertEquals(null, parseMemoryNewsKey("garbage"))
    }

    @Test
    fun should_speak_when_newerWriteOrMoreNotes() {
        assertTrue(isMemoryNews(pill(key = "200#3"), lastSeen = "100#3"))
        assertTrue(isMemoryNews(pill(key = "100#4"), lastSeen = "100#3"))
    }

    @Test
    fun should_stayQuiet_when_aDeletionMovesTheKeyBack() {
        // A note deleted, or the latest rating cleared: not news.
        assertFalse(isMemoryNews(pill(key = "100#2"), lastSeen = "100#3"))
        assertFalse(isMemoryNews(pill(key = "50#3"), lastSeen = "100#3"))
        assertFalse(isMemoryNews(pill(key = "100#3"), lastSeen = "100#3"))
    }

    @Test
    fun should_keyAndScopePill_when_built() {
        val built = buildHomeMemoryPill(listOf(candidate(lastWrittenAt = 5L)), noteCount = 2, scope = "subsonic|p1")
        assertEquals("subsonic|p1", built.scope)
        assertEquals(memoryNewsKey(candidate(lastWrittenAt = 5L), 2), built.newsKey)
    }

    @Test
    fun should_rememberPerScope_when_inMemoryStore() {
        val store = InMemoryMemoryBubbleSeenStore()
        store.markSeen("a|1", "k1")
        assertEquals("k1", store.lastSeen("a|1"))
        assertEquals(null, store.lastSeen("a|2"))
    }

    // ---- Where it hangs ----

    @Test
    fun should_hangUnderCutout_when_bubbleHasRoomToTheRight() {
        // 412dp phone: span 128..348, horn 30dp in, bubble at least 136dp.
        assertEquals(
            206f to true,
            memoryArrowCenterX(206f, width = 412f, freeStart = 128f, freeEnd = 348f, hornX = 30f, minWidth = 136f),
        )
    }

    @Test
    fun should_fallBackToPageCentre_when_noCutout() {
        assertEquals(
            400f to false,
            memoryArrowCenterX(null, width = 800f, freeStart = 150f, freeEnd = 720f, hornX = 30f, minWidth = 136f),
        )
    }

    @Test
    fun should_centreTheBubbleInTheSpan_when_cornerHoleAndCentreLeaveNoRoom() {
        // A top-left punch hole sits over the title; the page centre leaves
        // the bubble too little room to its right → the minimum bubble is
        // centred in the span, the arrow 30dp into it.
        val (x, under) = memoryArrowCenterX(
            40f,
            width = 412f,
            freeStart = 128f,
            freeEnd = 300f,
            hornX = 30f,
            minWidth = 136f,
        )
        assertFalse(under)
        assertEquals((128f + 300f - 136f) / 2f + 30f, x)
    }

    // ---- Who takes the tap ----

    @Test
    fun should_notClaimArrowTap_when_editing() {
        val controller = MemoryBubbleController()
        controller.arrowBounds = Rect(100f, 0f, 148f, 48f)
        val tap = {}
        val onArrow = Offset(124f, 24f)

        controller.onArrowTap = memoryArrowTap(scrolledAway = false, editing = true, tap = tap)
        assertNull(controller.tapTargetAt(onArrow))
        // Scrolled away it is not claimed either; in place and not editing it is.
        controller.onArrowTap = memoryArrowTap(scrolledAway = true, editing = false, tap = tap)
        assertNull(controller.tapTargetAt(onArrow))
        controller.onArrowTap = memoryArrowTap(scrolledAway = false, editing = false, tap = tap)
        assertSame(tap, controller.tapTargetAt(onArrow))
    }

    private fun candidate(lastWrittenAt: Long?) = AlbumMemoryCandidate(
        profileId = "p1",
        provider = MediaId.PROVIDER_SUBSONIC,
        albumId = "pang",
        albumName = "Pang",
        artistName = "Caroline Polachek",
        totalTracks = 10,
        ratedTrackCount = 0,
        ratingCoverage = 0f,
        averageSongRating = null,
        albumRating = 8.4f,
        hasAlbumReview = false,
        noteCount = 0,
        askAiCount = 0,
        firstPlayedAt = null,
        lastPlayedAt = null,
        lastWrittenAt = lastWrittenAt,
        playCount = 1,
        neoDbSynced = false,
        isMemoryEligible = true,
        year = null,
        durationSeconds = null,
        coverArtUrl = null,
    )
}
