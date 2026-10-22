package com.gpo.yoin.debug

import android.os.Looper
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.SimpleBasePlayer
import androidx.media3.common.util.UnstableApi
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture

internal data class MixedPlaylistProbeEntry(
    val entryId: String,
    val provider: String,
    val profileId: String,
    val profileLabel: String,
    val item: MediaItem,
    val durationMs: Long = 0
)

internal data class MixedPlaylistProbeAction(
    val name: String,
    val index: Int = C.INDEX_UNSET,
    val positionMs: Long = 0
)

/** A single logical MediaSession. Every transport command reaches the epoch/cancellation coordinator. */
@UnstableApi
internal class MixedPlaylistProbePlayer(
    private val send: (MixedPlaylistProbeAction) -> ListenableFuture<*>
) : SimpleBasePlayer(Looper.getMainLooper()) {
    var entries: List<MixedPlaylistProbeEntry> = emptyList()
    var index: Int = 0
    var backend: Player? = null
    var transitioning = false
    var wantsPlay = false
    var completed = false
    var failureCode: String? = null

    fun publish() = invalidateState()

    override fun getState(): State {
        val delegate = backend
        val active = entries.getOrNull(index)
        val sameItem = active?.item?.mediaId == delegate?.currentMediaItem?.mediaId
        val playlist = entries.mapIndexed { itemIndex, entry ->
            val duration = if (itemIndex == index && sameItem) {
                delegate?.duration?.takeIf { it > 0 } ?: entry.durationMs
            } else {
                entry.durationMs
            }
            MediaItemData.Builder(entry.entryId).setMediaItem(entry.item)
                .setDurationUs(duration.takeIf { it > 0 }?.times(1000) ?: C.TIME_UNSET)
                .setIsSeekable(true).build()
        }
        val commands = Player.Commands.Builder().addAll(
            Player.COMMAND_RELEASE,
            Player.COMMAND_GET_CURRENT_MEDIA_ITEM,
            Player.COMMAND_GET_TIMELINE,
            Player.COMMAND_GET_METADATA
        )
        if (entries.isNotEmpty()) {
            commands.addAll(
                Player.COMMAND_PLAY_PAUSE,
                Player.COMMAND_STOP,
                Player.COMMAND_PREPARE,
                Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM,
                Player.COMMAND_SEEK_TO_MEDIA_ITEM,
                Player.COMMAND_SEEK_TO_NEXT,
                Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM,
                Player.COMMAND_SEEK_TO_PREVIOUS,
                Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM
            )
        }
        return State.Builder().setAvailableCommands(commands.build()).setPlaylist(playlist)
            .setCurrentMediaItemIndex(if (entries.isEmpty()) C.INDEX_UNSET else index)
            .setPlaybackState(
                when {
                    failureCode != null -> Player.STATE_IDLE
                    transitioning -> Player.STATE_BUFFERING
                    entries.isEmpty() -> Player.STATE_IDLE
                    completed -> Player.STATE_ENDED
                    else -> delegate?.playbackState ?: Player.STATE_IDLE
                }
            )
            .setPlayWhenReady(
                wantsPlay && (transitioning || delegate?.playWhenReady == true),
                Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST
            )
            .setPlayerError(
                failureCode?.let {
                    PlaybackException("Mixed probe: $it", null, PlaybackException.ERROR_CODE_UNSPECIFIED)
                }
            )
            .setRepeatMode(Player.REPEAT_MODE_OFF)
            .setShuffleModeEnabled(false)
            .setContentPositionMs {
                if (sameItem && !transitioning) delegate?.currentPosition?.coerceAtLeast(0) ?: 0 else 0
            }
            .setContentBufferedPositionMs {
                if (sameItem && !transitioning) delegate?.bufferedPosition?.coerceAtLeast(0) ?: 0 else 0
            }.build()
    }

    override fun getPlaceholderState(suggestedState: State): State = getState()

    override fun handlePrepare(): ListenableFuture<*> = send(MixedPlaylistProbeAction("play"))

    override fun handleSetPlayWhenReady(playWhenReady: Boolean): ListenableFuture<*> =
        send(MixedPlaylistProbeAction(if (playWhenReady) "play" else "pause"))

    override fun handleStop(): ListenableFuture<*> = send(MixedPlaylistProbeAction("stop"))

    override fun handleSeek(mediaItemIndex: Int, positionMs: Long, seekCommand: Int): ListenableFuture<*> =
        when (seekCommand) {
            Player.COMMAND_SEEK_TO_NEXT, Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM ->
                send(MixedPlaylistProbeAction("next"))
            Player.COMMAND_SEEK_TO_PREVIOUS, Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM ->
                send(MixedPlaylistProbeAction("previous"))
            else -> send(
                MixedPlaylistProbeAction(
                    "seek",
                    index = mediaItemIndex,
                    positionMs = positionMs.takeUnless { it == C.TIME_UNSET }?.coerceAtLeast(0) ?: 0
                )
            )
        }

    override fun handleRelease(): ListenableFuture<*> = Futures.immediateVoidFuture()
}

/** ExoPlayer.stop() preserves playWhenReady, so pause it before waiting on the handoff barrier. */
internal fun Player.stopForMixedHandoff() {
    pause()
    stop()
}
