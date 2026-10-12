package com.gpo.yoin.player.applemusic

import android.os.Looper
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.SimpleBasePlayer
import androidx.media3.common.util.UnstableApi
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Media3's own PREVIOUS (`BasePlayer.seekToPrevious`, which the Apple Music session runs for Now
 * Playing, the notification and headsets) on a player offering [musicKitTransportCommands].
 */
@OptIn(UnstableApi::class)
@RunWith(RobolectricTestRunner::class)
class MusicKitPreviousTest {
    @Test
    fun should_restartTheFirstSong_when_musicKitHasNoItemBeforeIt() {
        // An album's first song, 10 s in, MusicKit's repeat off.
        val player = SessionPlayer(canSeek = true, canSkipToPreviousItem = false)

        player.seekToPrevious()

        assertEquals(listOf(0 to 0L), player.seeks)
    }

    @Test
    fun should_restartTheFirstSong_when_pastThreeSecondsWithRepeatAll() {
        val player = SessionPlayer(
            canSeek = true,
            canSkipToPreviousItem = true,
            repeatMode = Player.REPEAT_MODE_ALL
        )

        player.seekToPrevious()

        assertEquals(listOf(0 to 0L), player.seeks)
    }

    @Test
    fun should_dropPrevious_when_theSongNeitherSeeksNorHasAnItemBefore() {
        val player = SessionPlayer(canSeek = false, canSkipToPreviousItem = false)

        player.seekToPrevious()

        assertTrue(player.seeks.isEmpty())
    }

    @Test
    fun should_restartTheFirstSong_when_repeatIsOffAndWithinThreeSeconds() {
        val player = SessionPlayer(canSeek = true, canSkipToPreviousItem = false, positionMs = 1_000)

        player.seekToPrevious()

        assertEquals(listOf(0 to 0L), player.seeks)
    }

    @Test
    fun should_keepMedia3WrapBehavior_when_repeatAllAndAtThreeSecondBoundary() {
        val player = SessionPlayer(
            canSeek = true,
            canSkipToPreviousItem = true,
            repeatMode = Player.REPEAT_MODE_ALL,
            positionMs = 3_000
        )

        player.seekToPrevious()

        assertEquals(listOf(2 to androidx.media3.common.C.TIME_UNSET), player.seeks)
    }

    @Test
    fun should_restartTheFirstSong_when_repeatOneAndWithinThreeSeconds() {
        val player = SessionPlayer(
            canSeek = true,
            canSkipToPreviousItem = true,
            repeatMode = Player.REPEAT_MODE_ONE,
            positionMs = 1_000
        )

        player.seekToPrevious()

        assertEquals(listOf(0 to 0L), player.seeks)
    }

    /** A three-song queue on its first song, [positionMs] in, with the commands MusicKit's state allows. */
    private class SessionPlayer(
        private val canSeek: Boolean,
        private val canSkipToPreviousItem: Boolean,
        private val repeatMode: Int = Player.REPEAT_MODE_OFF,
        private val positionMs: Long = 10_000
    ) : SimpleBasePlayer(Looper.getMainLooper()) {
        val seeks = mutableListOf<Pair<Int, Long>>()

        override fun getState(): State {
            val commands = Player.Commands.Builder()
                .addAll(Player.COMMAND_PLAY_PAUSE, Player.COMMAND_GET_CURRENT_MEDIA_ITEM, Player.COMMAND_GET_TIMELINE)
                .apply {
                    musicKitTransportCommands(
                        canSeek = canSeek,
                        canSkipToPreviousItem = canSkipToPreviousItem,
                        canSkipToNextItem = true,
                        canSkipToQueueItem = true
                    ).forEach(::add)
                }.build()
            val playlist = (0 until 3).map { i ->
                MediaItemData.Builder("q$i")
                    .setMediaItem(MediaItem.Builder().setMediaId("applemusic:$i").build())
                    .setDurationUs(200_000_000)
                    .setIsSeekable(canSeek)
                    .build()
            }
            return State.Builder()
                .setAvailableCommands(commands)
                .setPlaylist(playlist)
                .setCurrentMediaItemIndex(0)
                .setPlaybackState(Player.STATE_READY)
                .setPlayWhenReady(true, Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST)
                .setRepeatMode(repeatMode)
                .setContentPositionMs(positionMs)
                .build()
        }

        override fun handleSeek(mediaItemIndex: Int, positionMs: Long, seekCommand: Int): ListenableFuture<*> {
            seeks += mediaItemIndex to positionMs
            return Futures.immediateVoidFuture()
        }
    }
}
