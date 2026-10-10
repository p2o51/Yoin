package com.gpo.yoin.ui.library

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.runtime.Stable
import com.gpo.yoin.data.model.SearchResults
import com.gpo.yoin.data.source.Capability

/** What the search surface lists: every type ([All]), or one type alone. */
enum class LibrarySearchFilter { All, Artists, Albums, Songs, Playlists }

/**
 * Most rows of each type the All list shows. The remote searches return
 * fewer (Spotify 10, Subsonic 20, Apple Music 25 a type); only Spotify's
 * saved-library search, which filters the synced snapshot, can match more,
 * and a type filter shows all of its type.
 */
internal const val SEARCH_ALL_ROWS_PER_TYPE = 40

/**
 * The type chips the search surface offers in [scope]. Capability alone
 * decides, never whether the current results hold that type, so the row
 * doesn't reshuffle as results land. Playlists need
 * [Capability.SEARCH_PLAYLISTS] (Spotify, Apple Music; not Subsonic, whose
 * search3 has no playlists), and inside the saved library the library must
 * hold playlists ([Capability.PLAYLISTS_READ]).
 */
internal fun searchFiltersFor(capabilities: Set<Capability>, scope: LibrarySearchScope): List<LibrarySearchFilter> =
    LibrarySearchFilter.entries.filter { filter ->
        when (filter) {
            LibrarySearchFilter.Playlists ->
                Capability.SEARCH_PLAYLISTS in capabilities &&
                    (scope != LibrarySearchScope.CurrentLibrary || Capability.PLAYLISTS_READ in capabilities)
            else -> true
        }
    }

/** This filter when [available] offers it, otherwise [LibrarySearchFilter.All]. */
internal fun LibrarySearchFilter.normalisedTo(available: List<LibrarySearchFilter>): LibrarySearchFilter =
    takeIf { it in available } ?: LibrarySearchFilter.All

/**
 * The part of these results [filter] lists. Nothing is fetched for a type:
 * it shows what the mixed search already returned. All keeps at most
 * [SEARCH_ALL_ROWS_PER_TYPE] of each type.
 */
internal fun SearchResults.shownFor(filter: LibrarySearchFilter): SearchResults = when (filter) {
    LibrarySearchFilter.All -> SearchResults(
        tracks = tracks.take(SEARCH_ALL_ROWS_PER_TYPE),
        albums = albums.take(SEARCH_ALL_ROWS_PER_TYPE),
        artists = artists.take(SEARCH_ALL_ROWS_PER_TYPE),
        playlists = playlists.take(SEARCH_ALL_ROWS_PER_TYPE)
    )
    LibrarySearchFilter.Artists -> SearchResults(artists = artists)
    LibrarySearchFilter.Albums -> SearchResults(albums = albums)
    LibrarySearchFilter.Songs -> SearchResults(tracks = tracks)
    LibrarySearchFilter.Playlists -> SearchResults(playlists = playlists)
}

/**
 * One scroll position per (query, scope, filter) on the search surface, so
 * All → Songs → All lands where All was left, and a scope switched away from
 * and back keeps its place. Positions belong to the results of one query:
 * the first read for another query forgets them all.
 */
@Stable
internal class LibrarySearchScrollMemory {
    private var query: String? = null
    private val lists = HashMap<Pair<LibrarySearchScope, LibrarySearchFilter>, LazyListState>()
    private val grids = HashMap<Pair<LibrarySearchScope, LibrarySearchFilter>, LazyGridState>()

    fun listState(query: String, scope: LibrarySearchScope, filter: LibrarySearchFilter): LazyListState {
        forgetOtherQueries(query)
        return lists.getOrPut(scope to filter) { LazyListState() }
    }

    fun gridState(query: String, scope: LibrarySearchScope, filter: LibrarySearchFilter): LazyGridState {
        forgetOtherQueries(query)
        return grids.getOrPut(scope to filter) { LazyGridState() }
    }

    private fun forgetOtherQueries(query: String) {
        if (this.query == query) return
        this.query = query
        lists.clear()
        grids.clear()
    }
}
