package com.gpo.yoin.ui.library

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.runtime.Stable
import com.gpo.yoin.data.model.SearchResultType
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
 * The part of these results [filter] lists. Switching fetches nothing: a
 * type shows what the mixed search already returned, and reads its next
 * page only as its list nears the end ([searchMoreFor]). All keeps at most
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

/** The one type this filter lists; null for [LibrarySearchFilter.All]. */
internal val LibrarySearchFilter.resultType: SearchResultType?
    get() = when (this) {
        LibrarySearchFilter.All -> null
        LibrarySearchFilter.Artists -> SearchResultType.Artists
        LibrarySearchFilter.Albums -> SearchResultType.Albums
        LibrarySearchFilter.Songs -> SearchResultType.Tracks
        LibrarySearchFilter.Playlists -> SearchResultType.Playlists
    }

/**
 * What follows [filter]'s list of [results]: a page being read or failed ([paging]), otherwise
 * more where the service has it ([SearchResults.more]). All shows each type's first rows and
 * never reads more; a type's own list does, as it nears its end.
 */
internal fun searchMoreFor(
    results: SearchResults?,
    filter: LibrarySearchFilter,
    paging: Map<SearchResultType, LibrarySongsMore>,
): LibrarySongsMore {
    val type = filter.resultType ?: return LibrarySongsMore.None
    if (results == null) return LibrarySongsMore.None
    return paging[type] ?: if (type in results.more) LibrarySongsMore.Available else LibrarySongsMore.None
}

/**
 * One scroll position per (query, scope, filter) on the search surface, so
 * All → Songs → All lands where All was left, and a scope switched away from
 * and back keeps its place. Positions belong to the results of one query:
 * the first read for another query forgets them all. They also last only as
 * long as the search: a blank query ends it ([onSearchQuery]), so the same
 * words searched again start at the top.
 */
@Stable
internal class LibrarySearchScrollMemory {
    private var query: String? = null
    private val lists = HashMap<Pair<LibrarySearchScope, LibrarySearchFilter>, LazyListState>()
    private val grids = HashMap<Pair<LibrarySearchScope, LibrarySearchFilter>, LazyGridState>()
    private val gridAnchors = HashMap<Pair<LibrarySearchScope, LibrarySearchFilter>, LibraryGridAnchor>()

    fun listState(query: String, scope: LibrarySearchScope, filter: LibrarySearchFilter): LazyListState {
        forgetOtherQueries(query)
        return lists.getOrPut(scope to filter) { LazyListState() }
    }

    fun gridState(query: String, scope: LibrarySearchScope, filter: LibrarySearchFilter): LazyGridState {
        forgetOtherQueries(query)
        return grids.getOrPut(scope to filter) { LazyGridState() }
    }

    /**
     * Where [gridState]'s grid was left, kept beside it: the search's grids
     * pass through every width as the surface morphs to its pill and back,
     * and the surface's content leaves composition in between.
     */
    fun gridAnchor(query: String, scope: LibrarySearchScope, filter: LibrarySearchFilter): LibraryGridAnchor {
        forgetOtherQueries(query)
        return gridAnchors.getOrPut(scope to filter) { LibraryGridAnchor(0) }
    }

    /**
     * Follows the typed query. Blank means the search ended: cleared,
     * collapsed, a search shortcut or Library home. A Wide detail column
     * keeps the query on collapse, so the positions stay for the way back.
     */
    fun onSearchQuery(query: String) {
        if (query.isNotBlank()) return
        this.query = null
        lists.clear()
        grids.clear()
        gridAnchors.clear()
    }

    private fun forgetOtherQueries(query: String) {
        if (this.query == query) return
        this.query = query
        lists.clear()
        grids.clear()
        gridAnchors.clear()
    }
}
