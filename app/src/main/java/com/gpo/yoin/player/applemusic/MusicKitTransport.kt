package com.gpo.yoin.player.applemusic

import androidx.media3.common.Player
import com.apple.android.music.playback.controller.MediaPlayerController

/**
 * The seek and skip commands Yoin's Apple Music session offers, read off MusicKit's controller.
 *
 * PREVIOUS ([Player.COMMAND_SEEK_TO_PREVIOUS]) can restart the current song as well as skip to an
 * earlier item. Unlike ExoPlayer's non-live transport, MusicKit can refuse seeks while preparing, so
 * advertise it when either backend operation is available. MusicKit (1.1.1, `queue.b.d(int, int)`) has
 * no item before the first song with repeat off; gating PREVIOUS only on that rejected even a restart
 * of a seekable song. Stepping back an item ([Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM]) still needs one.
 */
internal fun musicKitTransportCommands(
    canSeek: Boolean,
    canSkipToPreviousItem: Boolean,
    canSkipToNextItem: Boolean,
    canSkipToQueueItem: Boolean
): List<Int> = buildList {
    if (canSeek) add(Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM)
    if (canSkipToNextItem) {
        add(Player.COMMAND_SEEK_TO_NEXT)
        add(Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM)
    }
    if (canSeek || canSkipToPreviousItem) add(Player.COMMAND_SEEK_TO_PREVIOUS)
    if (canSkipToPreviousItem) add(Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM)
    if (canSkipToQueueItem) add(Player.COMMAND_SEEK_TO_MEDIA_ITEM)
}

/**
 * Repeat and shuffle as Yoin last set them, written to MusicKit again until it holds them.
 *
 * MusicKit (1.1.1, `c.b.c(int)` → `queue.b.o(int)`; shuffle through `c.b.d(int)`) drops a write that
 * arrives while its queue has no current item, with no callback. Yoin writes its selected mode in the
 * same batch as prepare, so the write can precede the first loaded queue. [reapply] on each published
 * state retries only when the observed mode still differs and MusicKit allows the operation. A
 * restored mode is left alone until Yoin explicitly requests a mode.
 */
internal class MusicKitPlayModes(private val controller: MediaPlayerController) {
    private var repeatMode: Int? = null
    private var shuffleMode: Int? = null

    fun setRepeatMode(mode: Int) {
        repeatMode = mode
        controller.setRepeatMode(mode)
    }

    fun setShuffleMode(mode: Int) {
        shuffleMode = mode
        controller.setShuffleMode(mode)
    }

    fun reapply() {
        repeatMode?.let { mode ->
            if (controller.repeatMode != mode && controller.canSetRepeatMode()) controller.setRepeatMode(mode)
        }
        shuffleMode?.let { mode ->
            if (controller.shuffleMode != mode && controller.canSetShuffleMode()) controller.setShuffleMode(mode)
        }
    }
}
