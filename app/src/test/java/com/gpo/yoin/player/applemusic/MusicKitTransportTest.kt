package com.gpo.yoin.player.applemusic

import androidx.media3.common.Player
import com.apple.android.music.playback.controller.MediaPlayerController
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MusicKitTransportTest {
    @Test
    fun should_offerSeekToPrevious_when_firstItemIsSeekable() {
        // MusicKit with repeat off: nothing before a queue's first song, but the song seeks.
        val commands = musicKitTransportCommands(
            canSeek = true,
            canSkipToPreviousItem = false,
            canSkipToNextItem = true,
            canSkipToQueueItem = true
        )

        assertTrue(Player.COMMAND_SEEK_TO_PREVIOUS in commands)
        assertFalse(Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM in commands)
    }

    @Test
    fun should_offerBothPreviousCommands_when_anItemComesBefore() {
        val commands = musicKitTransportCommands(
            canSeek = true,
            canSkipToPreviousItem = true,
            canSkipToNextItem = true,
            canSkipToQueueItem = true
        )

        assertTrue(Player.COMMAND_SEEK_TO_PREVIOUS in commands)
        assertTrue(Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM in commands)
    }

    @Test
    fun should_offerNoPrevious_when_songNeitherSeeksNorHasAnItemBefore() {
        // A queue still being read: there is nothing PREVIOUS could do.
        val commands = musicKitTransportCommands(
            canSeek = false,
            canSkipToPreviousItem = false,
            canSkipToNextItem = false,
            canSkipToQueueItem = true
        )

        assertFalse(Player.COMMAND_SEEK_TO_PREVIOUS in commands)
        assertFalse(Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM in commands)
        assertFalse(Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM in commands)
    }

    @Test
    fun should_offerNext_when_musicKitHasAnItemAfter() {
        val commands = musicKitTransportCommands(
            canSeek = true,
            canSkipToPreviousItem = false,
            canSkipToNextItem = true,
            canSkipToQueueItem = false
        )

        assertTrue(Player.COMMAND_SEEK_TO_NEXT in commands)
        assertTrue(Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM in commands)
        assertTrue(Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM in commands)
        assertFalse(Player.COMMAND_SEEK_TO_MEDIA_ITEM in commands)
    }

    @Test
    fun should_writeRepeatAgain_when_musicKitDroppedItBeforeItsFirstQueue() {
        val musicKit = FakeMusicKit()
        val modes = MusicKitPlayModes(musicKit.controller)

        // The first prepare's batch: MusicKit has no current item yet and drops the write.
        modes.setRepeatMode(Player.REPEAT_MODE_ALL)
        modes.reapply()
        assertEquals(Player.REPEAT_MODE_OFF, musicKit.repeat)

        musicKit.hasQueue = true
        modes.reapply()

        assertEquals(Player.REPEAT_MODE_ALL, musicKit.repeat)
    }

    @Test
    fun should_writeShuffleAgain_when_musicKitDroppedItBeforeItsFirstQueue() {
        val musicKit = FakeMusicKit()
        val modes = MusicKitPlayModes(musicKit.controller)

        modes.setShuffleMode(1)
        modes.reapply()
        assertEquals(0, musicKit.shuffle)

        musicKit.hasQueue = true
        modes.reapply()

        assertEquals(1, musicKit.shuffle)
    }

    @Test
    fun should_writeOnce_when_musicKitTookTheModeAtOnce() {
        val musicKit = FakeMusicKit().apply { hasQueue = true }
        val modes = MusicKitPlayModes(musicKit.controller)

        modes.setRepeatMode(Player.REPEAT_MODE_ONE)
        modes.setShuffleMode(0)
        repeat(3) { modes.reapply() }

        assertEquals(listOf(Player.REPEAT_MODE_ONE), musicKit.repeatWrites)
        assertEquals(listOf(0), musicKit.shuffleWrites)
    }

    @Test
    fun should_writeNothing_when_yoinNeverSetAMode() {
        val musicKit = FakeMusicKit().apply { hasQueue = true }
        val modes = MusicKitPlayModes(musicKit.controller)

        modes.reapply()

        assertTrue(musicKit.repeatWrites.isEmpty())
        assertTrue(musicKit.shuffleWrites.isEmpty())
    }

    @Test
    fun should_reapplyLatestModes_when_userChangesModeBeforeTheQueueIsReady() {
        val musicKit = FakeMusicKit()
        val modes = MusicKitPlayModes(musicKit.controller)
        modes.setRepeatMode(Player.REPEAT_MODE_ALL)
        modes.setShuffleMode(1)
        modes.setRepeatMode(Player.REPEAT_MODE_ONE)
        modes.setShuffleMode(0)

        musicKit.hasQueue = true
        modes.reapply()

        assertEquals(Player.REPEAT_MODE_ONE, musicKit.repeat)
        assertEquals(0, musicKit.shuffle)
    }

    @Test
    fun should_offerPreviousItem_when_currentItemCannotSeekButHasAPredecessor() {
        val commands = musicKitTransportCommands(
            canSeek = false,
            canSkipToPreviousItem = true,
            canSkipToNextItem = false,
            canSkipToQueueItem = false
        )

        assertTrue(Player.COMMAND_SEEK_TO_PREVIOUS in commands)
        assertTrue(Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM in commands)
        assertFalse(Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM in commands)
    }

    /** MusicKit 1.1.1's play-mode writes: kept only while its queue has a current item. */
    private class FakeMusicKit {
        var hasQueue = false
        var repeat = Player.REPEAT_MODE_OFF
        var shuffle = 0
        val repeatWrites = mutableListOf<Int>()
        val shuffleWrites = mutableListOf<Int>()
        val controller: MediaPlayerController = mockk(relaxed = true)

        init {
            every { controller.repeatMode } answers { repeat }
            every { controller.shuffleMode } answers { shuffle }
            every { controller.canSetRepeatMode() } answers { hasQueue }
            every { controller.canSetShuffleMode() } answers { hasQueue }
            every { controller.setRepeatMode(any()) } answers {
                val mode = firstArg<Int>()
                repeatWrites += mode
                if (hasQueue) repeat = mode
            }
            every { controller.setShuffleMode(any()) } answers {
                val mode = firstArg<Int>()
                shuffleWrites += mode
                if (hasQueue) shuffle = mode
            }
        }
    }
}
