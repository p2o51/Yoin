package com.gpo.yoin.data.source.spotify

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The coroutine edges of [SingleFlightValue]: a cancelled leader, and an
 * invalidate while a load is out — dropping it, or keeping a recent one.
 */
class SpotifySourceCachesTest {

    @Test
    fun should_loadAgainForWaiter_when_leaderCancelled() = runTest {
        val cache = SingleFlightValue<String>()
        var loads = 0
        val leaderGate = CompletableDeferred<Unit>()
        val leader = launch {
            cache.get {
                loads++
                leaderGate.await()
                "leader"
            }
        }
        runCurrent()
        val waiter = async {
            cache.get {
                loads++
                "waiter"
            }
        }
        runCurrent()

        leader.cancel()

        // The waiter isn't failed with the leader's cancellation; it loads once itself.
        assertEquals("waiter", waiter.await())
        assertEquals(2, loads)
        assertEquals("waiter", cache.get { error("served from the cache") })
    }

    @Test
    fun should_loadAgain_when_invalidatedWhileLoading() = runTest {
        val cache = SingleFlightValue<String>()
        val gate = CompletableDeferred<Unit>()
        val stale = async {
            cache.get {
                gate.await()
                "stale"
            }
        }
        runCurrent()

        cache.invalidate()
        gate.complete(Unit)

        // The load still answers its own caller, but isn't kept.
        assertEquals("stale", stale.await())
        var loads = 0
        val fresh = cache.get {
            loads++
            "fresh"
        }
        assertEquals("fresh", fresh)
        assertEquals(1, loads)
    }

    @Test
    fun should_keepNewerValue_when_invalidatedLoadEndsLast() = runTest {
        val cache = SingleFlightValue<String>()
        val gate = CompletableDeferred<Unit>()
        val stale = async {
            cache.get {
                gate.await()
                "stale"
            }
        }
        runCurrent()

        cache.invalidate()
        // A read after the invalidate doesn't join the load already out.
        assertEquals("fresh", cache.get { "fresh" })
        gate.complete(Unit)

        assertEquals("stale", stale.await())
        assertEquals("fresh", cache.get { error("served from the cache") })
    }

    @Test
    fun should_joinLoadInFlight_when_invalidateKeepsARecentLoad() = runTest {
        var now = 0L
        val cache = SingleFlightValue<String>(clock = { now })
        var loads = 0
        val gate = CompletableDeferred<Unit>()
        val first = async {
            cache.get {
                loads++
                gate.await()
                "first"
            }
        }
        runCurrent()
        now += 2_000L

        cache.invalidate(keepLoadStartedWithinMs = 10_000L)
        // The refresh rides the load already out rather than reading again.
        val refresh = async { cache.get { error("joins the load in flight") } }
        runCurrent()
        gate.complete(Unit)

        assertEquals("first", first.await())
        assertEquals("first", refresh.await())
        assertEquals(1, loads)
        // And that load's value is kept.
        assertEquals("first", cache.get { error("served from the cache") })
    }

    @Test
    fun should_loadAgain_when_keptLoadStartedTooLongAgo() = runTest {
        var now = 0L
        val cache = SingleFlightValue<String>(clock = { now })
        val gate = CompletableDeferred<Unit>()
        val old = async {
            cache.get {
                gate.await()
                "old"
            }
        }
        runCurrent()
        now += 10_001L

        cache.invalidate(keepLoadStartedWithinMs = 10_000L)

        assertEquals("fresh", cache.get { "fresh" })
        gate.complete(Unit)
        assertEquals("old", old.await())
        assertEquals("fresh", cache.get { error("served from the cache") })
    }

    @Test
    fun should_loadAgain_when_keepingRecentLoadsFindsNoneInFlight() = runTest {
        val cache = SingleFlightValue<String>()
        assertEquals("cached", cache.get { "cached" })

        cache.invalidate(keepLoadStartedWithinMs = 10_000L)

        // Nothing in flight to ride: the loaded value is still dropped.
        assertEquals("fresh", cache.get { "fresh" })
    }
}
