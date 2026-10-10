package com.gpo.yoin.data.cache

import com.gpo.yoin.data.model.Album
import com.gpo.yoin.data.model.MediaId
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class DetailCacheStoreTest {
    private var now = 1_000L

    @Test
    fun should_trimToLowWatermark_when_overBudget() = runTest {
        val row = rowLength()
        val dao = FakeDetailCacheDao()
        // Three rows fit under the cap; a fourth crosses it, and the trim then goes down to two.
        val store = store(dao, maxBytes = 3 * row + row / 2, trimTargetBytes = 2 * row)

        (1..4).forEach { n -> write(store, n) }

        assertEquals(setOf(id(3), id(4)), dao.entityIds)
        assertEquals(1, dao.evictionScans)

        // The room the trim left: the next write fits without another scan.
        write(store, 5)

        assertEquals(setOf(id(3), id(4), id(5)), dao.entityIds)
        assertEquals(1, dao.evictionScans)
    }

    @Test
    fun should_trimBelowCap_when_onlyMaxBytesIsSet() = runTest {
        val row = rowLength()
        val dao = FakeDetailCacheDao()
        // No trim target given: it follows the cap (5/6 of it, here just under three rows).
        val store = store(dao, maxBytes = 3 * row + row / 2)

        (1..4).forEach { n -> write(store, n) }

        assertEquals(setOf(id(3), id(4)), dao.entityIds)
    }

    @Test
    fun should_rejectTrimTarget_when_aboveCap() {
        assertThrows(IllegalArgumentException::class.java) {
            DetailCacheStore(FakeDetailCacheDao(), maxBytes = 1_000L, trimTargetBytes = 2_000L)
        }
    }

    @Test
    fun should_keepServedRow_when_trimRunsBeforeItsTouchLands() = runTest {
        val row = rowLength()
        val dao = FakeDetailCacheDao()
        val store = store(dao, maxBytes = 3 * row + row / 2, trimTargetBytes = 2 * row)
        (1..3).forEach { n -> write(store, n) }
        // Row 1, the oldest, is served; its LRU touch is still on its way …
        dao.touchGate = CompletableDeferred()
        store.readAlbum(PROFILE, id(1))!!.value()

        // … when a write crosses the cap: the trim takes the next-oldest rows instead.
        write(store, 4)

        assertEquals(setOf(id(1), id(4)), dao.entityIds)
    }

    @Test
    fun should_keepRowJustWritten_when_itAloneIsOverBudget() = runTest {
        val row = rowLength()
        val dao = FakeDetailCacheDao()
        val store = store(dao, maxBytes = row / 2, trimTargetBytes = 0)

        write(store, 1)
        assertEquals(setOf(id(1)), dao.entityIds)

        write(store, 2)
        assertEquals(setOf(id(2)), dao.entityIds)
    }

    @Test
    fun should_skipWrite_when_noLongerCurrent() = runTest {
        val dao = FakeDetailCacheDao()
        val store = store(dao)

        store.writeAlbum(PROFILE, id(1), album(1), stillCurrent = { false })

        assertTrue(dao.entityIds.isEmpty())
    }

    @Test
    fun should_serveDiskRowBeforeTouchCompletes_when_readingHit() = runTest {
        val dao = FakeDetailCacheDao()
        val store = store(dao)
        write(store, 1)
        // The LRU touch never finishes: serving the row must not wait for it.
        dao.touchGate = CompletableDeferred()

        val served = store.readAlbum(PROFILE, id(1))!!.value()

        assertEquals("Album 1", served?.name)
    }

    /** One album row's stored JSON length (every test album encodes to the same length). */
    private suspend fun TestScope.rowLength(): Long {
        val dao = FakeDetailCacheDao()
        write(store(dao), 1)
        return dao.jsonLength(PROFILE, "ALBUM", id(1))!!
    }

    /** A store on [dao]; without [trimTargetBytes] the store's own default (it follows [maxBytes]). */
    private fun TestScope.store(
        dao: FakeDetailCacheDao,
        maxBytes: Long = DetailCacheStore.DEFAULT_MAX_BYTES,
        trimTargetBytes: Long? = null
    ) = if (trimTargetBytes == null) {
        DetailCacheStore(dao = dao, clock = { now }, maxBytes = maxBytes, scope = backgroundScope)
    } else {
        DetailCacheStore(
            dao = dao,
            clock = { now },
            maxBytes = maxBytes,
            trimTargetBytes = trimTargetBytes,
            scope = backgroundScope
        )
    }

    private suspend fun write(store: DetailCacheStore, n: Int) {
        now += 1_000L
        store.writeAlbum(PROFILE, id(n), album(n))
    }

    private fun id(n: Int) = MediaId(MediaId.PROVIDER_SUBSONIC, "al-$n").toString()

    private fun album(n: Int) = Album(
        id = MediaId(MediaId.PROVIDER_SUBSONIC, "al-$n"),
        name = "Album $n",
        artist = "Artist",
        artistId = null,
        coverArt = null,
        songCount = 0,
        durationSec = 0,
        year = 2020,
        genre = null
    )

    private companion object {
        const val PROFILE = "profile-1"
    }
}
