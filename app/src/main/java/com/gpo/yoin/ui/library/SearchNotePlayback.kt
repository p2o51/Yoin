package com.gpo.yoin.ui.library

import com.gpo.yoin.player.PlaybackManager
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Play-from-a-note's second half for Library search (#Yoin:#18), as the Memories diary and the album
 * scrapbook do it: once the song asked for is current and prepared, seek to where the note was written.
 * Gives up after [timeoutMs] (a provider that never reports it).
 */
internal suspend fun PlaybackManager.seekWhenCurrent(rawTrackId: String, positionMs: Long, timeoutMs: Long = 4_000L) {
    if (positionMs <= 0L) return
    val ready = withTimeoutOrNull(timeoutMs) {
        playbackState.first { state -> state.currentTrack?.id?.rawId == rawTrackId && state.duration > 0L }
    }
    if (ready != null) seekTo(positionMs)
}
