package com.gpo.yoin.ui.home

import com.gpo.yoin.data.local.PlayHistory
import com.gpo.yoin.data.remote.Album

sealed interface HomeUiState {
    data object Loading : HomeUiState

    data class Content(
        val activities: List<PlayHistory>,
        val mixAlbums: List<Album>,
        val memories: List<Album>,
    ) : HomeUiState

    data class Error(val message: String) : HomeUiState
}
