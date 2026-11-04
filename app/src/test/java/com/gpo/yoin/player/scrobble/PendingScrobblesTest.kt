package com.gpo.yoin.player.scrobble

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class PendingScrobblesTest {
    @get:Rule val folder = TemporaryFolder()

    private fun play(i: Int, profile: String = "p1") = PendingScrobble(profile, "s$i", i.toLong())

    @Test
    fun `queue keeps the newest and skips duplicates`() {
        var queue = emptyList<PendingScrobble>()
        repeat(PendingScrobbles.MAX_PENDING + 5) { i -> queue = PendingScrobbles.add(queue, play(i)) }
        assertEquals(PendingScrobbles.MAX_PENDING, queue.size)
        assertEquals(play(5), queue.first())
        assertEquals(queue, PendingScrobbles.add(queue, queue.last()))
    }

    @Test
    fun `batches are per account and oldest first`() {
        val queue = listOf(play(1), play(2, "p2"), play(3), play(4))
        assertEquals(listOf(play(1), play(3)), PendingScrobbles.batchFor(queue, "p1", size = 2))
        assertEquals(listOf(play(2, "p2"), play(4)), PendingScrobbles.remove(queue, listOf(play(1), play(3))))
    }

    @Test
    fun `file store round-trips and reads garbage as empty`() {
        val file = File(folder.root, "q.json")
        val store = FilePendingScrobbleStore(file)
        assertTrue(store.load().isEmpty())
        store.save(listOf(play(1), play(2)))
        assertEquals(listOf(play(1), play(2)), FilePendingScrobbleStore(file).load())
        file.writeText("{not json")
        assertTrue(store.load().isEmpty())
        store.save(emptyList())
        assertTrue(!file.exists())
    }
}
