package com.gpo.yoin.player.applemusic

import android.app.ActivityManager
import android.content.Context
import android.os.Bundle
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.bytedeco.javacpp.Pointer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AppleMusicSdkSmokeTest {
    @Test
    fun should_initializeAndReleaseNativeController_when_notYetAuthorized() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.runOnMainSync {
            val player = AppleMusicMedia3Player(instrumentation.targetContext, "not-authorized", "not-authorized") {}
            try {
                assertFalse(player.isPlaying)
                assertNull(player.currentMediaItem)
                val memoryInfo = ActivityManager.MemoryInfo()
                (instrumentation.targetContext.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager)
                    .getMemoryInfo(memoryInfo)
                val heapBytes = Runtime.getRuntime().maxMemory()
                val physicalBudget = AppleMusicNativeMemoryPolicy.physicalMemoryBudget(heapBytes, memoryInfo.totalMem)
                assertEquals(physicalBudget, Pointer.maxPhysicalBytes())
                assertEquals(heapBytes, Pointer.maxBytes())
                assertTrue(Pointer.maxPhysicalBytes() > 0)
                instrumentation.sendStatus(0, Bundle().apply {
                    putLong("appleMusicNativePhysicalMemoryBudget", Pointer.maxPhysicalBytes())
                    putLong("appleMusicNativeTrackedMemoryBudget", Pointer.maxBytes())
                })
            } finally {
                player.release()
            }
        }
    }
}
