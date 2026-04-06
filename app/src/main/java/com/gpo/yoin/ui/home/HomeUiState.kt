package com.gpo.yoin.ui.home

import com.gpo.yoin.data.local.PlayHistory
import com.gpo.yoin.data.remote.Album

sealed interface HomeUiState {
    data object Loading : HomeUiState

    data class Content(
        val activities: List<PlayHistory>,
        val recentlyAdded: List<Album>,
        val mixForYou: List<Album>,
        val mostPlayed: List<Album>,
        val quickPlaySongs: List<com.gpo.yoin.data.remote.Song>,
        val quickPlayAlbums: List<Album> = emptyList(),
    ) : HomeUiState

    data class Error(val message: String) : HomeUiState
}
