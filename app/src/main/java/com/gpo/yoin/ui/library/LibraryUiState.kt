package com.gpo.yoin.ui.library

import com.gpo.yoin.data.remote.Album
import com.gpo.yoin.data.remote.Artist
import com.gpo.yoin.data.remote.SearchResult
import com.gpo.yoin.data.remote.Song
import com.gpo.yoin.data.remote.StarredResponse

sealed interface LibraryUiState {
    data object Loading : LibraryUiState

    data class Content(
        val selectedTab: LibraryTab,
        val artists: List<Artist>,
        val albums: List<Album>,
        val songs: List<Song>,
        val favorites: StarredResponse?,
        val searchQuery: String,
        val searchResults: SearchResult?,
        val isSearching: Boolean,
    ) : LibraryUiState

    data class Error(val message: String) : LibraryUiState
}

enum class LibraryTab { Artists, Albums, Songs, Favorites }
