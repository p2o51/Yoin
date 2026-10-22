package com.gpo.yoin.player.dlna

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DlnaQueueEndTest {
    @Test
    fun should_advanceOnlyNearKnownEnd_when_rendererTransitionsFromPlayingToStopped() {
        assertTrue(DlnaQueueEnd.reached("PLAYING", "STOPPED", 198000, 0, 200000, true))
        assertFalse(DlnaQueueEnd.reached("PLAYING", "STOPPED", 40000, 40000, 200000, true))
        assertFalse(DlnaQueueEnd.reached("PLAYING", "PAUSED_PLAYBACK", 200000, 200000, 200000, true))
        assertFalse(DlnaQueueEnd.reached("STOPPED", "STOPPED", 200000, 200000, 200000, true))
        assertFalse(DlnaQueueEnd.reached("PLAYING", "STOPPED", 200000, 0, 0, true))
        assertFalse(DlnaQueueEnd.reached("PLAYING", "STOPPED", 200000, 200000, 200000, false))
    }
}
