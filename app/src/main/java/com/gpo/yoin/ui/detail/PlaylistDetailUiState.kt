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
        /** The service refused the entries: [songs] is empty, the playlist isn't. */
        val tracksUnavailable: Boolean = false,
        /**
         * Up to two album covers fanned behind the hero cover, start sleeve
         * first: rows' [PlaylistSong.coverArtUrl]s, chosen by [playlistSleeveCovers].
         */
        val sleeveCoverUrls: List<String> = emptyList(),
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
