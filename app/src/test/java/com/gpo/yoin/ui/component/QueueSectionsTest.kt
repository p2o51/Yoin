package com.gpo.yoin.ui.component

import com.gpo.yoin.data.repository.ActivityContext
import com.gpo.yoin.ui.nowplaying.QueueItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class QueueSectionsTest {

    private fun item(n: Int, queued: Boolean = false) =
        QueueItem(songId = "s$n", title = "Song $n", artist = "A", coverArtUrl = null, entryId = "e$n", userQueued = queued)

    @Test
    fun should_splitWhatTheUserAddedFromTheRest_when_bothAreUpNext() {
        // played 0, now 1, then two the user queued, then the album
        val queue = listOf(item(0), item(1), item(2, queued = true), item(3, queued = true), item(4), item(5))

        val sections = queueSections(queue, currentIndex = 1, upcoming = listOf(2, 3, 4, 5))

        assertEquals(1, sections.now?.index)
        assertEquals(listOf(2, 3), sections.queued.map { it.index })
        assertEquals(listOf(4, 5), sections.next.map { it.index })
    }

    @Test
    fun should_followThePlayOrder_when_shuffleReordersIt() {
        val queue = (0..4).map { item(it) }

        val sections = queueSections(queue, currentIndex = 0, upcoming = listOf(3, 1, 4, 2))

        assertEquals(listOf(3, 1, 4, 2), sections.next.map { it.index })
    }

    @Test
    fun should_fallBackToListOrder_when_theBackendGivesNoOrder() {
        val sections = queueSections((0..3).map { item(it) }, currentIndex = 1, upcoming = emptyList())

        assertEquals(listOf(2, 3), sections.next.map { it.index })
    }

    @Test
    fun should_haveNothingNow_when_theQueueIsEmpty() {
        val sections = queueSections(emptyList(), currentIndex = -1, upcoming = emptyList())

        assertNull(sections.now)
        assertEquals(emptyList<QueueRow>(), sections.next)
    }

    @Test
    fun should_nameTheAlbumOrPlaylist_when_labellingWhatComesNext() {
        assertEquals(
            "Next from: Lantern Letters",
            queueNextFromLabel(ActivityContext.Album(albumId = "a", albumName = "Lantern Letters", artistName = null, artistId = null, coverArtId = null)),
        )
        assertEquals("Next up", queueNextFromLabel(ActivityContext.None))
    }
}
