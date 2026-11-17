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
        /** Source-provided write permission; gates Rename/Delete UI. */
        val canWrite: Boolean = false,
        /**
         * A song's sheet offers "Remove from playlist": [canWrite] on a service
         * that writes playlists ([com.gpo.yoin.data.source.Capability.PLAYLISTS_WRITE]).
         */
        val canRemoveSongs: Boolean = false,
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
     * The row's own key: it stays this entry's across reads and removals, and
     * a song in the playlist twice has one per entry. The list keys its rows
     * by it (so a removed row leaves and the rest move up) and
     * [PlaylistDetailViewModel.removeTrack] takes it.
     */
    val rowKey: String,
)
