package com.gpo.yoin.player.applemusic

import androidx.annotation.StringRes
import com.apple.android.music.playback.model.MediaPlayerException
import com.gpo.yoin.R

/**
 * Apple's webPlayback `failureType` for a song the account's explicit-content restriction holds back.
 * Its `customerMessage` (jp, 2026-11-08) asks to lift the restriction on explicit content in iTunes or
 * device settings. Every track of an explicit edition failed with it, even the edition's non-explicit
 * ones, while the clean edition played; Apple's own app played explicit songs on the same account. It is
 * the Android form of MusicKit JS's CONTENT_RESTRICTED.
 */
internal const val MUSICKIT_FAILURE_CONTENT_RESTRICTED = 3082

/** SDK error categories describe the failed operation, not whether the account needs authorization. */
@StringRes
internal fun musicKitPlaybackErrorMessage(type: Int, code: Int = MediaPlayerException.ERROR_UNKNOWN): Int = when {
    type == MediaPlayerException.TYPE_UNKNOWN && code == MUSICKIT_FAILURE_CONTENT_RESTRICTED ->
        R.string.player_apple_music_content_restricted
    else -> musicKitPlaybackErrorTypeMessage(type)
}

@StringRes
private fun musicKitPlaybackErrorTypeMessage(type: Int): Int = when (type) {
    MediaPlayerException.TYPE_IO -> R.string.player_apple_music_io_failed
    MediaPlayerException.TYPE_DRM -> R.string.player_apple_music_drm_failed
    MediaPlayerException.TYPE_PLAYBACK_QUEUE -> R.string.player_apple_music_queue_failed
    MediaPlayerException.TYPE_PLAYBACK_LEASE -> R.string.player_apple_music_session_failed
    MediaPlayerException.TYPE_CHROMECAST_ERROR -> R.string.player_apple_music_cast_failed
    MediaPlayerException.TYPE_EXPLICIT_VERIFY_AGE -> R.string.player_apple_music_age_verification
    else -> R.string.player_apple_music_play_failed
}

/**
 * The short code shown after the message: the SDK's type, then its error code when it has one. For
 * type 0 (a refused song) the code is Apple's `failureType` from the song's webPlayback answer, which
 * is what says why; a screenshot carries it without a log.
 */
internal fun musicKitPlaybackErrorCode(type: Int, code: Int): String =
    if (code == MediaPlayerException.ERROR_UNKNOWN) "$type" else "$type/$code"

/** Keep numeric SDK diagnostics in release builds without logging its messages, causes or credentials. */
internal fun musicKitPlaybackErrorDiagnostic(type: Int, code: Int, component: Int): String =
    "type=$type code=$code component=$component"
