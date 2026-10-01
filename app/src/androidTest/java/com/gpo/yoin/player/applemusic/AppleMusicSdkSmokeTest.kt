package com.gpo.yoin.player.applemusic

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AppleMusicSdkSmokeTest {
    @Test
    fun should_initializeAndReleaseNativeController_when_notYetAuthorized() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.runOnMainSync {
            val player = AppleMusicMedia3Player(instrumentation.targetContext, "not-authorized", "not-authorized") {}
            assertFalse(player.isPlaying)
            assertNull(player.currentMediaItem)
            player.release()
        }
    }
}
