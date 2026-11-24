package com.gpo.yoin.ui.library

import com.gpo.yoin.player.PlaybackManager
import com.gpo.yoin.shared.search.NOTE_SEEK_TIMEOUT_MS
import com.gpo.yoin.shared.search.noteSeekReady
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Play-from-a-note's second half for Library search (#Yoin:#18), as the Memories diary and the album
 * scrapbook do it: once the song asked for is current and prepared ([noteSeekReady], :shared), seek to where
 * the note was written. Gives up after [timeoutMs] (a provider that never reports it). Where a note leads is
 * :shared's [com.gpo.yoin.shared.search.noteOpenTarget].
 */
internal suspend fun PlaybackManager.seekWhenCurrent(rawTrackId: String, positionMs: Long, timeoutMs: Long = NOTE_SEEK_TIMEOUT_MS) {
    if (positionMs <= 0L) return
    val ready = withTimeoutOrNull(timeoutMs) {
        playbackState.first { state -> noteSeekReady(state.currentTrack?.id?.rawId, state.duration, rawTrackId) }
    }
    if (ready != null) seekTo(positionMs)
}
