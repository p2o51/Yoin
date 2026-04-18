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
        /**
         * Tabs the active source supports. When the provider lacks
         * [com.gpo.yoin.data.source.Capability.PLAYLISTS_READ] the Playlists
         * tab is dropped from the row entirely rather than showing an empty
         * state. A `selectedTab` that gets filtered out is normalised to
         * [LibraryTab.Artists] by the ViewModel.
         */
        val availableTabs: List<LibraryTab> = LibraryTab.entries,
        /**
         * Gates the "+" FAB in the Playlists tab. Follows
         * [com.gpo.yoin.data.source.Capability.PLAYLISTS_WRITE].
         */
        val canCreatePlaylists: Boolean = true,
    ) : LibraryUiState

    data class Error(val message: String) : LibraryUiState
}

enum class LibraryTab { Artists, Albums, Songs, Playlists, Favorites }
