package com.gpo.yoin.ui.home

import com.gpo.yoin.data.model.MediaId
import com.gpo.yoin.ui.memories.MemoryScoreKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HomeBubbleSayTest {

    private fun latest(id: Long) = HomeMemoryPill.Latest(
        sessionId = id,
        albumId = MediaId.subsonic("a$id"),
        albumName = "Album $id",
        artistName = "Artist",
        coverArtUrl = null,
        scoreKind = MemoryScoreKind.ALBUM_RATING,
        scoreText = "8.0",
    )

    private val pill = HomeMemoryPill(
        latest = latest(1),
        noteCount = 0,
        scope = "subsonic|p1",
        newsKey = "1#0",
        recall = listOf(latest(2), latest(3)),
    )

    @Test
    fun should_sayTipsOnly_when_thereAreNoMemories() {
        val say = idleBubbleSay(pill = null, sayMemory = true, memoryTurn = 0, tipTurn = 0)
        assertEquals(BubbleSay.Tip(HomeBubbleTip.entries.first()), say)
    }

    @Test
    fun should_recallAMemory_when_itIsAMemoryTurn() {
        val say = idleBubbleSay(pill, sayMemory = true, memoryTurn = 1, tipTurn = 0)
        assertEquals(BubbleSay.Memory(latest(3)), say)
    }

    @Test
    fun should_goThroughEveryTipBeforeRepeating_when_tipTurnsAdvance() {
        val tips = HomeBubbleTip.entries.indices.map { turn ->
            (idleBubbleSay(pill, sayMemory = false, memoryTurn = 0, tipTurn = turn) as BubbleSay.Tip).tip
        }
        assertEquals(HomeBubbleTip.entries.toList(), tips)
        val again = idleBubbleSay(pill, sayMemory = false, memoryTurn = 0, tipTurn = tips.size)
        assertEquals(BubbleSay.Tip(HomeBubbleTip.entries.first()), again)
    }

    @Test
    fun should_countTipTurnsUp_when_theStoreIsAsked() {
        val store = InMemoryMemoryBubbleSeenStore()
        assertEquals(listOf(0, 1, 2), List(3) { store.nextTipTurn() })
    }

    @Test
    fun should_keepTheChainOccasional_when_chattering() {
        assertTrue(MemoryBubbleChainChance in 0.2f..0.5f)
    }
}
