package com.gpo.yoin.ui.detail

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DetailActivityLaunchGateTest {
    @Test
    fun should_allowOnlyOneLaunch_until_ownerResumesAgain() {
        val gate = DetailActivityLaunchGate()

        assertTrue(gate.tryAcquire(ownerResumed = true))
        assertFalse(gate.tryAcquire(ownerResumed = true))

        gate.release()

        assertTrue(gate.tryAcquire(ownerResumed = true))
    }

    @Test
    fun should_rejectResidualLaunch_when_ownerIsNotResumed() {
        val gate = DetailActivityLaunchGate()

        assertFalse(gate.tryAcquire(ownerResumed = false))
    }
}
