package com.gpo.yoin.ui.library

import com.gpo.yoin.data.model.Album
import com.gpo.yoin.data.model.Artist
import com.gpo.yoin.data.model.MediaId
import com.gpo.yoin.data.model.Playlist
import com.gpo.yoin.data.model.SearchResults
import com.gpo.yoin.data.model.Starred
import com.gpo.yoin.data.model.Track
import com.gpo.yoin.ui.common.UiText

sealed interface LibraryUiState {
    data object Loading : LibraryUiState

    data class Content(
        val selectedTab: LibraryTab,
        /**
         * Per-tab payloads. `null` = not loaded yet (the tab shows a loading
         * indicator); empty = loaded and genuinely empty (the tab shows its
         * empty state). Same semantics as the long-standing
         * `favorites: Starred?` — without the distinction a freshly selected
         * tab flashed a factually-wrong "No X found" while its fetch ran.
         */
        val artists: List<Artist>?,
        val albums: List<Album>?,
        val songs: List<Track>?,
        val playlists: List<Playlist>?,
        val favorites: Starred?,
        val searchQuery: String,
        val searchResults: SearchResults?,
        val isSearching: Boolean,
        /**
         * Human-readable failure for the most recent search attempt; `null`
         * while idle, in flight, or after a success. Distinguishes "the
         * search failed" from "the search genuinely matched nothing".
         */
        val searchError: UiText? = null,
        val searchScope: LibrarySearchScope = LibrarySearchScope.CurrentLibrary,
        val canSearchSpotifyCatalog: Boolean = false,
        val canSearchAppleMusicCatalog: Boolean = false,
        val searchFocusRequestId: Long = 0L,
        /**
         * The result type the search surface lists. Back to All whenever the
         * search is cleared or reopened, and normalised against
         * [availableSearchFilters] on every scope or capability change.
         */
        val searchFilter: LibrarySearchFilter = LibrarySearchFilter.All,
        /** Type chips for the active provider and [searchScope] ([searchFiltersFor]). */
        val availableSearchFilters: List<LibrarySearchFilter> = LibrarySearchFilter.entries,
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
        /** Library-only providers show saved songs without the random-mix header. */
        val canReshuffleSongs: Boolean = true,
        val canAddToLibrary: Boolean = false,
        /** Visible inside full-screen search, above the shell's snackbar layer. */
        val libraryActionFeedback: Map<MediaId, LibraryActionFeedback> = emptyMap(),
    ) : LibraryUiState

    data class Error(val message: UiText) : LibraryUiState
}

enum class LibraryTab { Artists, Albums, Songs, Playlists, Favorites }

enum class LibrarySearchScope { CurrentLibrary, SpotifyGlobal, AppleMusicGlobal }

data class LibraryActionFeedback(val message: String, val isError: Boolean = false)
