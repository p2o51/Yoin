package com.gpo.yoin.ui.library

import com.gpo.yoin.data.model.Album
import com.gpo.yoin.data.model.Artist
import com.gpo.yoin.data.model.Playlist
import com.gpo.yoin.data.model.SearchResults
import com.gpo.yoin.data.model.Starred
import com.gpo.yoin.data.model.Track

sealed interface LibraryUiState {
    data object Loading : LibraryUiState

    data class Content(
        val selectedTab: LibraryTab,
        val artists: List<Artist>,
        val albums: List<Album>,
        val songs: List<Track>,
        val playlists: List<Playlist>,
        val favorites: Starred?,
        val searchQuery: String,
        val searchResults: SearchResults?,
        val isSearching: Boolean,
    ) : LibraryUiState

    data class Error(val message: String) : LibraryUiState
}

enum class LibraryTab { Artists, Albums, Songs, Playlists, Favorites }
