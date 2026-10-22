package com.gpo.yoin.debug

import android.os.Looper
import android.os.SystemClock
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.SimpleBasePlayer
import androidx.media3.common.util.UnstableApi
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.spotify.protocol.types.PlayerState

internal data class SpotifyProbeAction(
    val name: String,
    val uri: String = "",
    val position: Long = 0,
    val mode: Int = Player.REPEAT_MODE_OFF
)

/** Debug session mirror. It never renders audio, requests focus or predicts a command's success. */
@UnstableApi
internal class SpotifyForegroundProbePlayer(
    private val send: (SpotifyProbeAction) -> ListenableFuture<*>
) : SimpleBasePlayer(Looper.getMainLooper()) {
    private var connected = false
    private var observation: PlayerState? = null
    private var observedAtMs = 0L

    fun connectionChanged(value: Boolean) {
        connected = value
        if (!value) observation = null
        invalidateState()
    }

    fun observe(value: PlayerState) {
        observation = value
        observedAtMs = SystemClock.elapsedRealtime()
        invalidateState()
    }

    override fun getState(): State {
        val sample = observation.takeIf { connected }
        val track = sample?.track
        val uri = track?.uri
        val item = uri?.let {
            MediaItem.Builder().setMediaId(it)
                .setMediaMetadata(
                    MediaMetadata.Builder().setTitle(track.name)
                        .setArtist(track.artists?.joinToString { artist -> artist.name.orEmpty() })
                        .build()
                ).build()
        }
        val playlist = item?.let {
            listOf(
                MediaItemData.Builder(it.mediaId).setMediaItem(it)
                    .setDurationUs(track.duration.takeIf { duration -> duration > 0 }?.times(1000) ?: C.TIME_UNSET)
                    .setIsSeekable(true).build()
            )
        }.orEmpty()
        val commands = Player.Commands.Builder().addAll(
            Player.COMMAND_RELEASE,
            Player.COMMAND_GET_CURRENT_MEDIA_ITEM,
            Player.COMMAND_GET_TIMELINE,
            Player.COMMAND_GET_METADATA
        )
        if (connected) {
            commands.addAll(
                Player.COMMAND_PLAY_PAUSE,
                Player.COMMAND_STOP,
                Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM,
                Player.COMMAND_SEEK_TO_NEXT,
                Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM,
                Player.COMMAND_SEEK_TO_PREVIOUS,
                Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM,
                Player.COMMAND_SET_REPEAT_MODE
            )
        }
        val wantsPlay = sample != null && item != null && !sample.isPaused
        return State.Builder().setAvailableCommands(commands.build())
            .setPlaylist(playlist)
            .setCurrentMediaItemIndex(if (playlist.isEmpty()) C.INDEX_UNSET else 0)
            .setPlaybackState(
                when {
                    sample == null || item == null -> Player.STATE_IDLE
                    wantsPlay && sample.playbackSpeed <= 0f -> Player.STATE_BUFFERING
                    else -> Player.STATE_READY
                }
            )
            .setPlayWhenReady(wantsPlay, Player.PLAY_WHEN_READY_CHANGE_REASON_REMOTE)
            .setPlaybackParameters(PlaybackParameters(sample?.playbackSpeed?.takeIf { it > 0f } ?: 1f))
            // Projection is for the system progress bar only. Raw logs retain the exact SDK sample.
            .setContentPositionMs {
                val position = sample?.playbackPosition?.coerceAtLeast(0) ?: 0
                val elapsed = if (wantsPlay) {
                    ((SystemClock.elapsedRealtime() - observedAtMs).coerceAtLeast(0) * sample!!.playbackSpeed).toLong()
                } else {
                    0
                }
                (position + elapsed).coerceIn(0, track?.duration?.takeIf { it > 0 } ?: Long.MAX_VALUE)
            }
            .setRepeatMode(sample?.playbackOptions?.repeatMode ?: Player.REPEAT_MODE_OFF)
            .setShuffleModeEnabled(sample?.playbackOptions?.isShuffling == true)
            .build()
    }

    // SimpleBasePlayer normally makes optimistic placeholders while commands are pending.
    // This experiment may enter PLAYING only after an actual Spotify observation.
    override fun getPlaceholderState(suggestedState: State): State = getState()

    override fun handleSetPlayWhenReady(playWhenReady: Boolean): ListenableFuture<*> =
        send(SpotifyProbeAction(if (playWhenReady) "resume" else "pause"))

    override fun handleStop(): ListenableFuture<*> = send(SpotifyProbeAction("pause"))

    override fun handleSetRepeatMode(repeatMode: Int): ListenableFuture<*> =
        send(SpotifyProbeAction("repeat", mode = repeatMode))

    override fun handleSeek(mediaItemIndex: Int, positionMs: Long, seekCommand: Int): ListenableFuture<*> =
        when (seekCommand) {
            Player.COMMAND_SEEK_TO_NEXT, Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM -> send(SpotifyProbeAction("next"))
            Player.COMMAND_SEEK_TO_PREVIOUS, Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM ->
                send(SpotifyProbeAction("previous"))
            else -> send(SpotifyProbeAction("seek", position = positionMs.coerceAtLeast(0)))
        }

    override fun handleRelease(): ListenableFuture<*> = Futures.immediateVoidFuture()
}
