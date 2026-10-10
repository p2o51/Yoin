package com.gpo.yoin.data.cache

import com.gpo.yoin.data.model.Album
import com.gpo.yoin.data.model.MediaId
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
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

    private fun TestScope.store(
        dao: FakeDetailCacheDao,
        maxBytes: Long = DetailCacheStore.DEFAULT_MAX_BYTES,
        trimTargetBytes: Long = DetailCacheStore.DEFAULT_TRIM_TARGET_BYTES
    ) = DetailCacheStore(
        dao = dao,
        clock = { now },
        maxBytes = maxBytes,
        trimTargetBytes = trimTargetBytes,
        scope = backgroundScope
    )

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
