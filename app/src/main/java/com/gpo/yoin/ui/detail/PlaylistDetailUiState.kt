package com.gpo.yoin.ui.detail

import com.gpo.yoin.ui.common.UiText

sealed interface PlaylistDetailUiState {
    data object Loading : PlaylistDetailUiState

    data class Content(
        val playlistId: String,
        val playlistName: String,
        val owner: String,
        val comment: String?,
        val isPublic: Boolean?,
        val songCount: Int?,
        val totalDuration: Int?,
        val coverArtUrl: String?,
        val songs: List<PlaylistSong>,
        /** Source-provided write permission; gates Rename/Delete/Remove UI. */
        val canWrite: Boolean = false,
    ) : PlaylistDetailUiState

    data class Error(val message: UiText, val diagnostics: String? = null) : PlaylistDetailUiState
}

data class PlaylistSong(
    val id: String,
    val title: String,
    val artist: String,
    val album: String,
    val duration: Int?,
    val coverArtUrl: String?,
    /**
     * Zero-based server-ordered position. Used by remove-by-index
     * (Subsonic) and remove-by-uri+positions (Spotify, duplicate-safe).
     */
    val position: Int = 0,
)

/**
 * The "Copy to…" sheet (docs/playlist-transfer.md): closed, choosing a
 * profile, copying, or the outcome. One sheet walks through them.
 */
sealed interface PlaylistCopyState {
    data object Closed : PlaylistCopyState

    data object Choosing : PlaylistCopyState

    data class Copying(val target: String, val done: Int, val total: Int) : PlaylistCopyState

    /**
     * [added] of [total] songs went into the new playlist on [target];
     * [missing] didn't, in playlist order. [stoppedEarly]: the playlist was
     * made but a later write failed.
     */
    data class Copied(
        val target: String,
        val added: Int,
        val total: Int,
        val missing: List<PlaylistCopyMissing>,
        val stoppedEarly: Boolean = false,
    ) : PlaylistCopyState

    data class Failed(val target: String) : PlaylistCopyState
}

/** A song the copy left out: [unsure] when there were close candidates, else none was found. */
data class PlaylistCopyMissing(
    val position: Int,
    val title: String,
    val artist: String,
    val unsure: Boolean,
)
