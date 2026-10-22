package com.gpo.yoin.player.applemusic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MusicKitStartPositionTest {
    private val requested = MusicKitItemIdentity(2, "same-song")
    private val old = MusicKitItemIdentity(1, "same-song")
    private val guard = MusicKitStartPosition()

    private fun observe(
        at: Long,
        position: Long,
        item: MusicKitItemIdentity? = requested,
        canSeek: Boolean = true,
        epoch: Long = 1
    ) = guard.observe(epoch, item, position, canSeek, at)

    @Test
    fun should_waitForRequestedNativeIdentity_when_oldSameCatalogItemIsStillCurrent() {
        guard.begin(1, 0)
        assertNull(observe(0, 3_028, old))
        assertTrue(guard.pending)
        guard.bind(1, requested)
        assertNull(observe(1, 3_028, old))
        assertEquals(MusicKitStartPosition.Action.Seek(0), observe(2, 3_028))
    }

    @Test
    fun should_waitForSeekSupport_when_requestedItemIsStillPreparing() {
        guard.begin(1, 0, requested)
        assertNull(observe(0, 0, canSeek = false))
        assertTrue(guard.pending)
        assertEquals(MusicKitStartPosition.Action.Seek(0), observe(100, 0))
        assertFalse(guard.confirmed)
    }

    @Test
    fun should_seekEvenAtZeroAndWaitForStableObservation_when_newQueueStarts() {
        guard.begin(1, 0, requested)
        assertEquals(MusicKitStartPosition.Action.Seek(0), observe(0, 0))
        assertNull(observe(1, 0))
        assertNull(observe(100, 0))
        assertEquals(MusicKitStartPosition.Action.Confirmed, observe(101, 0))
        assertTrue(guard.confirmed)
        assertFalse(guard.pending)
    }

    @Test
    fun should_retryRequestedPosition_when_musicKitRestoresOldPosition() {
        guard.begin(1, 0, requested)
        assertEquals(MusicKitStartPosition.Action.Seek(0), observe(0, 3_028))
        assertNull(observe(1, 0))
        assertNull(observe(50, 3_028))
        assertEquals(MusicKitStartPosition.Action.Seek(0), observe(500, 3_028))
        assertNull(observe(501, 0))
        assertEquals(MusicKitStartPosition.Action.Confirmed, observe(601, 0))
    }

    @Test
    fun should_preserveExplicitResumePosition_when_newQueueRequestsNonzeroStart() {
        guard.begin(1, 15_000, requested)
        assertEquals(MusicKitStartPosition.Action.Seek(15_000), observe(0, 3_028))
        assertNull(observe(1, 15_000))
        assertEquals(MusicKitStartPosition.Action.Confirmed, observe(101, 15_000))
    }

    @Test
    fun should_replaceInitialRewind_when_userExplicitlySeeksDuringPrepare() {
        guard.begin(1, 0, requested)
        observe(0, 3_028)
        guard.begin(1, 7_000, requested)
        assertEquals(MusicKitStartPosition.Action.Seek(7_000), observe(1, 0))
        assertNull(observe(2, 7_000))
        assertEquals(MusicKitStartPosition.Action.Confirmed, observe(102, 7_000))
    }

    @Test
    fun should_resetContinuousMatch_when_seekSupportOrIdentityIsLost() {
        guard.begin(1, 0, requested)
        observe(0, 0)
        observe(1, 0)
        assertNull(observe(50, 0, canSeek = false))
        assertNull(observe(101, 0))
        assertNull(observe(150, 0, old))
        assertNull(observe(201, 0))
        assertEquals(MusicKitStartPosition.Action.Confirmed, observe(301, 0))
    }

    @Test
    fun should_ignoreOldEpochWithoutSpendingCurrentRequest_when_callbackArrivesLate() {
        guard.begin(2, 0, requested)
        guard.bind(1, old)
        assertNull(observe(0, 3_028, old, epoch = 1))
        assertEquals(MusicKitStartPosition.Action.Seek(0), observe(100, 3_028, epoch = 2))
    }

    @Test
    fun should_notSeekOrConfirm_when_pendingQueueIsCancelled() {
        guard.begin(1, 0, requested)
        guard.cancel()
        assertNull(observe(100, 0))
        assertFalse(guard.pending)
        assertFalse(guard.confirmed)
    }
}
