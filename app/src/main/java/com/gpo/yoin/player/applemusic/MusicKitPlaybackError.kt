package com.gpo.yoin.player.applemusic

import androidx.annotation.StringRes
import com.apple.android.music.playback.model.MediaPlayerException
import com.gpo.yoin.R

/** SDK error categories describe the failed operation, not whether the account needs authorization. */
@StringRes
internal fun musicKitPlaybackErrorMessage(type: Int): Int = when (type) {
    MediaPlayerException.TYPE_IO -> R.string.player_apple_music_io_failed
    MediaPlayerException.TYPE_DRM -> R.string.player_apple_music_drm_failed
    MediaPlayerException.TYPE_PLAYBACK_QUEUE -> R.string.player_apple_music_queue_failed
    MediaPlayerException.TYPE_PLAYBACK_LEASE -> R.string.player_apple_music_session_failed
    MediaPlayerException.TYPE_CHROMECAST_ERROR -> R.string.player_apple_music_cast_failed
    MediaPlayerException.TYPE_EXPLICIT_VERIFY_AGE -> R.string.player_apple_music_age_verification
    else -> R.string.player_apple_music_play_failed
}

/** Keep numeric SDK diagnostics in release builds without logging its messages, causes or credentials. */
internal fun musicKitPlaybackErrorDiagnostic(type: Int, code: Int, component: Int): String =
    "type=$type code=$code component=$component"
