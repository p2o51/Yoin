package com.gpo.yoin.player.applemusic

import android.content.Context
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.util.UnstableApi
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith

/** Native SDK/session plumbing only. This intentionally does not claim subscription playback proof. */
@UnstableApi
@RunWith(AndroidJUnit4::class)
class AppleMusicSessionSmokeTest {
    @Test
    fun should_preserveQueueIdentityAndClear_when_media3CommandsArriveBeforePreparation() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val context = ApplicationProvider.getApplicationContext<Context>()
            val player = AppleMusicMedia3Player(context, "test-developer", "test-user", onObservation = {})
            try {
                val first = MediaItem.Builder().setMediaId("applemusic:123")
                    .setMediaMetadata(MediaMetadata.Builder().setTitle("First").build()).build()
                val second = MediaItem.Builder().setMediaId("applemusic:456")
                    .setMediaMetadata(MediaMetadata.Builder().setTitle("Second").build()).build()
                player.setMediaItems(listOf(first, second), 1, 0)
                assertEquals(2, player.mediaItemCount)
                assertEquals("applemusic:456", player.currentMediaItem?.mediaId)
                assertEquals("Second", player.currentMediaItem?.mediaMetadata?.title)
                assertFalse(player.isPlaying)
                player.addMediaItem(first)
                assertEquals(3, player.mediaItemCount)
                player.clearMediaItems()
                assertEquals(0, player.mediaItemCount)
                assertFalse(player.isPlaying)
            } finally {
                player.release()
            }
        }
    }
}
