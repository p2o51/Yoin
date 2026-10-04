package com.gpo.yoin.player.applemusic

import org.junit.Assert.assertEquals
import org.junit.Test

class AppleMusicNativeMemoryPolicyTest {
    @Test
    fun should_allowWholeProcessHeadroom_when_tabletHasEightGiBAnd256MiBHeap() {
        assertEquals(GiB, AppleMusicNativeMemoryPolicy.physicalMemoryBudget(256 * MiB, 8 * GiB))
    }

    @Test
    fun should_preserveFiniteVendorBudget_when_deviceHasLimitedOrUnknownRam() {
        assertEquals(512 * MiB, AppleMusicNativeMemoryPolicy.physicalMemoryBudget(256 * MiB, 2 * GiB))
        assertEquals(512 * MiB, AppleMusicNativeMemoryPolicy.physicalMemoryBudget(256 * MiB, 0))
    }

    @Test
    fun should_boundIncreaseByDeviceRam_when_quarterRamIsBelowOneGiB() {
        assertEquals(768 * MiB, AppleMusicNativeMemoryPolicy.physicalMemoryBudget(256 * MiB, 3 * GiB))
    }

    @Test
    fun should_preserveLargerVendorDefault_when_heapAlreadyNeedsMoreHeadroom() {
        assertEquals(2 * GiB, AppleMusicNativeMemoryPolicy.physicalMemoryBudget(GiB, 8 * GiB))
    }

    @Test
    fun should_avoidOverflow_when_heapSizeExceedsMultiplicationRange() {
        assertEquals(Long.MAX_VALUE, AppleMusicNativeMemoryPolicy.physicalMemoryBudget(Long.MAX_VALUE, Long.MAX_VALUE))
    }

    companion object {
        private const val MiB = 1024L * 1024
        private const val GiB = 1024L * MiB
    }
}
