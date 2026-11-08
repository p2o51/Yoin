package com.gpo.yoin.data.highlight

import com.gpo.yoin.data.highlight.LyricHighlightSelector.Bucket
import com.gpo.yoin.data.highlight.LyricHighlightSelector.DAY_MS
import com.gpo.yoin.data.highlight.LyricHighlightSelector.FreshTrack
import com.gpo.yoin.data.highlight.LyricHighlightSelector.Shown
import com.gpo.yoin.data.highlight.LyricHighlightSelector.SongSignal
import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LyricHighlightSelectorTest {
    private val now = 1_800_000_000_000L

    private fun signal(
        id: String,
        plays: Int = 1,
        daysAgo: Long = 1,
        heat: Double = 0.0,
        rating: Float? = null,
        notes: Int = 0,
    ) = SongSignal(id, plays, now - daysAgo * DAY_MS, heat, rating, notes)

    @Test
    fun should_halveHeat_everyThreeWeeks_andIgnoreSkips() {
        assertEquals(1.0, LyricHighlightSelector.heat(now, 1f, now), 1e-9)
        assertEquals(0.5, LyricHighlightSelector.heat(now - 21 * DAY_MS, 0.9f, now), 1e-9)
        assertEquals(0.0, LyricHighlightSelector.heat(now, 0.2f, now), 1e-9)
    }

    @Test
    fun should_classifySongs_byHowTheyWereHeard() {
        assertEquals(Bucket.FRESH, LyricHighlightSelector.bucketOf(null, now))
        assertEquals(Bucket.HEAVY, LyricHighlightSelector.bucketOf(signal("a", plays = 5, heat = 3.0), now))
        assertEquals(Bucket.REDISCOVER, LyricHighlightSelector.bucketOf(signal("b", plays = 4, daysAgo = 90), now))
        assertEquals(Bucket.REDISCOVER, LyricHighlightSelector.bucketOf(signal("c", daysAgo = 70, rating = 9f), now))
        // Played once a month ago: none of them.
        assertNull(LyricHighlightSelector.bucketOf(signal("d", plays = 1, daysAgo = 30, heat = 0.4), now))
        // Loved but heard last week: not "been a while" yet.
        assertNull(LyricHighlightSelector.bucketOf(signal("e", plays = 9, daysAgo = 7, heat = 0.8), now))
    }

    @Test
    fun should_rankRediscover_byLoveTimesGap() {
        val pools = LyricHighlightSelector.pools(
            listOf(
                signal("loved-long-ago", plays = 30, daysAgo = 200, rating = 9f),
                signal("liked-recently-gone", plays = 3, daysAgo = 61),
                signal("noted", plays = 1, daysAgo = 120, notes = 1),
            ),
            fresh = emptyList(),
            now = now,
        )
        assertEquals(
            listOf("loved-long-ago", "noted", "liked-recently-gone"),
            pools.getValue(Bucket.REDISCOVER).map { it.songId },
        )
    }

    @Test
    fun should_dropPlayedTracks_fromFresh() {
        val pools = LyricHighlightSelector.pools(
            listOf(signal("played")),
            fresh = listOf(FreshTrack("played", 9f), FreshTrack("new-a", 7f), FreshTrack("new-b", 9.5f)),
            now = now,
        )
        assertEquals(listOf("new-b", "new-a"), pools.getValue(Bucket.FRESH).map { it.songId })
    }

    @Test
    fun should_interleaveQueue_fiveThreeTwo() {
        val pools = mapOf(
            Bucket.HEAVY to (1..20).map { LyricHighlightSelector.Candidate("h$it", Bucket.HEAVY, 1.0) },
            Bucket.REDISCOVER to (1..20).map { LyricHighlightSelector.Candidate("r$it", Bucket.REDISCOVER, 1.0) },
            Bucket.FRESH to (1..20).map { LyricHighlightSelector.Candidate("f$it", Bucket.FRESH, 1.0) },
        )
        val queue = LyricHighlightSelector.processingQueue(pools, skip = setOf("h1"), limit = 10)

        assertEquals(10, queue.size)
        assertEquals(5, queue.count { it.bucket == Bucket.HEAVY })
        assertEquals(3, queue.count { it.bucket == Bucket.REDISCOVER })
        assertEquals(2, queue.count { it.bucket == Bucket.FRESH })
        assertTrue(queue.none { it.songId == "h1" })
        assertEquals("h2", queue.first().songId)
    }

    @Test
    fun should_fillFromOthers_when_aKindRunsDry() {
        val pools = mapOf(
            Bucket.HEAVY to listOf(LyricHighlightSelector.Candidate("h", Bucket.HEAVY, 1.0)),
            Bucket.REDISCOVER to emptyList(),
            Bucket.FRESH to (1..5).map { LyricHighlightSelector.Candidate("f$it", Bucket.FRESH, 1.0) },
        )
        val queue = LyricHighlightSelector.processingQueue(pools, skip = emptySet(), limit = 4)
        assertEquals(listOf("h", "f1", "f2", "f3"), queue.map { it.songId })
    }

    @Test
    fun should_preferRested_andNeverShown_whenPickingNext() {
        val shown = listOf(
            Shown("just-shown", now - DAY_MS),
            Shown("never", null),
            Shown("long-ago", now - 30 * DAY_MS),
        )
        repeat(20) { seed ->
            val pick = LyricHighlightSelector.nextToShow(shown, { Bucket.HEAVY }, now, random = Random(seed))!!
            assertEquals("never", pick.songId)
        }
        // Everything shown recently: still shows something rather than nothing.
        val allRecent = listOf(Shown("a", now - DAY_MS), Shown("b", now - 2 * DAY_MS))
        assertEquals("b", LyricHighlightSelector.nextToShow(allRecent, { null }, now)!!.songId)
    }

    @Test
    fun should_skipThePlayingSong_whenPickingNext() {
        val shown = listOf(Shown("playing", null), Shown("other", now - 30 * DAY_MS))
        repeat(10) { seed ->
            val pick = LyricHighlightSelector.nextToShow(shown, { Bucket.FRESH }, now, exclude = "playing", random = Random(seed))
            assertNotEquals("playing", pick!!.songId)
        }
        assertNull(LyricHighlightSelector.nextToShow(listOf(Shown("playing", null)), { null }, now, exclude = "playing"))
    }

    @Test
    fun should_drawKindsByMix_overManyPicks() {
        val shown = listOf(Shown("h", null), Shown("r", null), Shown("f", null))
        val kinds = mapOf("h" to Bucket.HEAVY, "r" to Bucket.REDISCOVER, "f" to Bucket.FRESH)
        val random = Random(7)
        val counts = HashMap<String, Int>()
        repeat(10_000) {
            val pick = LyricHighlightSelector.nextToShow(shown, { kinds[it] }, now, random = random)!!
            counts[pick.songId] = (counts[pick.songId] ?: 0) + 1
        }
        assertEquals(0.5, counts.getValue("h") / 10_000.0, 0.03)
        assertEquals(0.3, counts.getValue("r") / 10_000.0, 0.03)
        assertEquals(0.2, counts.getValue("f") / 10_000.0, 0.03)
    }
}
