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
    fun should_hangUnderCutout_when_bubbleHasRoomOnBothSides() {
        // 412dp phone: title at 16, Settings from 348; 150dp left of the
        // arrow, the body ending 40dp past it.
        assertEquals(
            206f to true,
            memoryArrowCenterX(206f, pageCentre = 206f, titleLeft = 16f, freeEnd = 348f, minLeft = 150f, reach = 40f),
        )
    }

    @Test
    fun should_fallBackToPageCentre_when_noCutout() {
        assertEquals(
            400f to false,
            memoryArrowCenterX(null, pageCentre = 400f, titleLeft = 24f, freeEnd = 720f, minLeft = 150f, reach = 40f),
        )
    }

    @Test
    fun should_fallBackToPageCentre_when_cornerHoleOverTheTitle() {
        // A top-left punch hole sits over the title: no room left of it.
        assertEquals(
            206f to false,
            memoryArrowCenterX(40f, pageCentre = 206f, titleLeft = 16f, freeEnd = 348f, minLeft = 150f, reach = 40f),
        )
    }

    @Test
    fun should_hangAtThePagesCentre_when_theFrameRunsStartAlignedInAWiderWindow() {
        // Device QA 2026-10-12 (HOM-28): the tap that opens a Wide detail
        // column re-tiers Home before the column takes any width; the feed's
        // frame then runs 76..764 in the 1280dp container (feedFrameInsets),
        // Settings from 714. The window's centre (640) no longer fits — the
        // arrow cut to the fallback (226) and back — the page's still does.
        assertEquals(
            420f to false,
            memoryArrowCenterX(null, pageCentre = 420f, titleLeft = 76f, freeEnd = 714f, minLeft = 150f, reach = 80f)
        )
        assertEquals(
            226f to false,
            memoryArrowCenterX(null, pageCentre = 640f, titleLeft = 76f, freeEnd = 714f, minLeft = 150f, reach = 80f)
        )
    }

    @Test
    fun should_stayInTheTail_when_anyOfTheBubbleIsStillSeen() {
        val flight = (0..1000).map { it / 1000f }
        flight.filter { memoryBubbleFlightAlpha(it) > 0f }.forEach { fly ->
            assertEquals("fly $fly", 0f, memoryArrowHomecoming(fly))
        }
        // Home as it lands: where the header puts it, in its resting ink.
        assertEquals(1f, memoryArrowHomecoming(1f))
        // On its way, never back.
        flight.zipWithNext().forEach { (a, b) -> assertTrue(memoryArrowHomecoming(b) >= memoryArrowHomecoming(a)) }
    }

    @Test
    fun should_keepTheBubblesLeftRoom_when_centreIsTooFarLeft() {
        val (x, under) = memoryArrowCenterX(
            null,
            pageCentre = 150f,
            titleLeft = 16f,
            freeEnd = 260f,
            minLeft = 150f,
            reach = 40f,
        )
        assertFalse(under)
        assertEquals(166f, x)
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
