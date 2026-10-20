package com.gpo.yoin.player.applemusic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MusicKitEndGateTest {
    private val first = MusicKitItemIdentity(1, "song-a")
    private val second = MusicKitItemIdentity(2, "song-b")

    private fun gate(vararg items: MusicKitItemIdentity): MusicKitEndGate = MusicKitEndGate().apply {
        beginQueue(items.map { it.catalogId }, emptySet())
        assertTrue(confirmStart(items.first()))
        confirmQueue(items.toList())
    }

    private fun sample(
        gate: MusicKitEndGate,
        at: Long,
        position: Long,
        item: MusicKitItemIdentity? = first,
        playing: Boolean = true,
        buffering: Boolean = false,
        repeatOff: Boolean = true,
        finalItem: Boolean = true,
        permitted: Boolean = true,
        duration: Long = 10_000,
        stopped: Boolean = false
    ) = gate.observe(
        MusicKitEndGate.Sample(
            at, item, playing, buffering, stopped, position, duration, repeatOff, finalItem, permitted
        )
    )

    private fun tail(gate: MusicKitEndGate, item: MusicKitItemIdentity = first) {
        sample(gate, 0, 8_000, item)
        sample(gate, 1_000, 9_000, item)
    }

    private fun end(gate: MusicKitEndGate, item: MusicKitItemIdentity = first, at: Long = 2_000) {
        gate.onItemEnded(item, 10_000, at, permitted = true)
        sample(gate, at, 0, item, playing = false)
    }

    @Test
    fun should_endOnce_when_nativeEndFollowsRealTailProgressAndTerminalState() {
        val gate = gate(first)
        tail(gate)
        gate.onItemEnded(first, 10_000, 2_000, permitted = true)
        assertFalse(gate.ended)
        // A state callback can still report PLAYING before the terminal state arrives.
        sample(gate, 2_000, 9_990)
        assertFalse(gate.ended)
        sample(gate, 2_010, 0, playing = false)
        assertTrue(gate.ended)
        assertEquals(10_000L, gate.completedPositionMs)
        gate.onItemEnded(first, 10_000, 2_020, permitted = true)
        sample(gate, 2_020, 0, playing = false)
        assertTrue(gate.ended)
    }

    @Test
    fun should_keepAdvancingEvidence_when_nativeCallbacksRepeatTheSamePosition() {
        val gate = gate(first)
        tail(gate)
        sample(gate, 1_001, 9_000)
        sample(gate, 1_002, 9_000)
        end(gate)
        assertTrue(gate.ended)
    }

    @Test
    fun should_rejectEnd_when_thereIsNoNativeEndCallback() {
        val gate = gate(first)
        tail(gate)
        sample(gate, 2_000, 0, playing = false)
        assertFalse(gate.ended)
    }

    @Test
    fun should_rejectEnd_when_positionOnlyJumpedIntoTail() {
        val gate = gate(first)
        sample(gate, 0, 1_000)
        sample(gate, 100, 9_000)
        end(gate)
        assertFalse(gate.ended)
    }

    @Test
    fun should_rejectEnd_when_tailEvidenceIsStale() {
        val gate = gate(first)
        tail(gate)
        // Duplicates must not refresh real-progress freshness.
        sample(gate, 5_000, 9_000)
        end(gate, at = 5_010)
        assertFalse(gate.ended)
    }

    @Test
    fun should_rejectEnd_when_userSeeksBackFromTail() {
        val gate = gate(first)
        tail(gate)
        gate.interrupt()
        sample(gate, 1_100, 1_000)
        end(gate)
        assertFalse(gate.ended)
    }

    @Test
    fun should_rejectEnd_when_uncommandedPositionMovesBackwards() {
        val gate = gate(first)
        tail(gate)
        sample(gate, 1_100, 1_000)
        end(gate)
        assertFalse(gate.ended)
    }

    @Test
    fun should_rejectEnd_when_pauseOrStopIntentPrecedesCallback() {
        val gate = gate(first)
        tail(gate)
        gate.interrupt()
        gate.onItemEnded(first, 10_000, 1_100, permitted = false)
        sample(gate, 1_100, 0, playing = false, permitted = false)
        assertFalse(gate.ended)
    }

    @Test
    fun should_rejectEnd_when_skipIsStillWaitingForTargetItem() {
        val gate = gate(first, second)
        tail(gate, second)
        gate.expectItemChange(second, first.queueId)
        sample(gate, 1_500, 9_500, second)
        end(gate, second)
        assertFalse(gate.ended)
    }

    @Test
    fun should_rejectOldCallback_when_newQueueRepeatsTheSameCatalogSong() {
        val gate = gate(first)
        tail(gate)
        val replacement = MusicKitItemIdentity(3, first.catalogId)
        gate.beginQueue(listOf(first.catalogId), setOf(first.queueId))
        assertFalse(gate.confirmStart(first))
        assertTrue(gate.confirmStart(replacement))
        gate.confirmQueue(listOf(replacement))
        tail(gate, replacement)
        gate.onItemEnded(first, 10_000, 2_000, permitted = true)
        sample(gate, 2_000, 0, replacement, playing = false)
        assertFalse(gate.ended)
        end(gate, replacement)
        assertTrue(gate.ended)
    }

    @Test
    fun should_refuseBinding_when_sdkReusesRetiredNativeIdentity() {
        val gate = gate(first)
        gate.invalidate()
        gate.beginQueue(listOf(first.catalogId), emptySet())
        assertFalse(gate.confirmStart(first))
        gate.confirmQueue(listOf(first))
        tail(gate)
        end(gate)
        assertFalse(gate.ended)
    }

    @Test
    fun should_rejectEnd_when_nativeQueueDoesNotMatchRequestedQueue() {
        val gate = MusicKitEndGate()
        gate.beginQueue(listOf(first.catalogId), emptySet())
        assertTrue(gate.confirmStart(first))
        gate.confirmQueue(listOf(first, second))
        tail(gate)
        end(gate)
        assertFalse(gate.ended)
    }

    @Test
    fun should_rejectEnd_when_repeatAllOrRepeatOneIsActive() {
        val gate = gate(first)
        sample(gate, 0, 8_000, repeatOff = false)
        sample(gate, 1_000, 9_000, repeatOff = false)
        end(gate)
        assertFalse(gate.ended)
    }

    @Test
    fun should_rejectEnd_when_itemIsNotTheFinalNativeQueueItem() {
        val gate = gate(first, second)
        tail(gate)
        end(gate)
        assertFalse(gate.ended)
        tail(gate, second)
        end(gate, second)
        assertTrue(gate.ended)
    }

    @Test
    fun should_rejectEnd_when_durationOrNativeEndPositionIsUnknown() {
        val gate = gate(first)
        sample(gate, 0, 8_000, duration = 0)
        sample(gate, 1_000, 9_000, duration = 0)
        end(gate)
        assertFalse(gate.ended)
        tail(gate)
        gate.onItemEnded(first, -1, 2_000, permitted = true)
        sample(gate, 2_000, 0, playing = false)
        assertFalse(gate.ended)
    }

    @Test
    fun should_rejectEnd_when_terminalStateIsUnknownBufferingOrStopped() {
        for (terminal in listOf("unknown", "buffering", "stopped")) {
            val gate = gate(first)
            tail(gate)
            gate.onItemEnded(first, 10_000, 2_000, permitted = true)
            sample(
                gate,
                2_000,
                0,
                item = if (terminal == "unknown") null else first,
                playing = false,
                buffering = terminal == "buffering",
                stopped = terminal == "stopped"
            )
            assertFalse(terminal, gate.ended)
        }
    }

    @Test
    fun should_rejectEnd_when_tokenWaitOrUnconfirmedSeekBlocksProgress() {
        val gate = gate(first)
        tail(gate)
        gate.onItemEnded(first, 10_000, 2_000, permitted = false)
        sample(gate, 2_000, 0, playing = false, permitted = false)
        assertFalse(gate.ended)
    }

    @Test
    fun should_discardOldFinalItemProof_when_queueIsEdited() {
        val gate = gate(first)
        tail(gate)
        gate.queueEdited()
        // The SDK can briefly keep reporting the old tail before it applies the edit.
        gate.confirmQueue(listOf(first))
        tail(gate)
        end(gate)
        assertFalse(gate.ended)
    }
}
