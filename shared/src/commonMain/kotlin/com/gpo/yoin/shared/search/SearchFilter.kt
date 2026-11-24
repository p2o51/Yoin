package com.gpo.yoin.shared.search

/*
 * The search surface's type chips (#Yoin:#18): which it offers, and what each lists of the service's rows.
 * Memories lists the ledger's hits ([SearchPageLayout.memories]), never the service's rows.
 */

/**
 * What the search surface lists: every type ([All]), one type alone, or [Memories]: the listener's own
 * notes and memories the query met. Declared in chip order.
 */
enum class SearchFilter { All, Songs, Albums, Memories, Artists, Playlists }

/**
 * Most rows of each type the All list shows. The remote searches return fewer (Spotify 10, Subsonic 20,
 * Apple Music 25 a type); only Spotify's saved-library search, which filters the synced snapshot, can match
 * more, and a type filter shows all of its type.
 */
const val SEARCH_ALL_ROWS_PER_TYPE = 40

/**
 * The type chips offered. Capability alone decides, never whether the current results hold that type, so
 * the row doesn't reshuffle as results land. Playlists need [canSearchPlaylists] (Spotify, Apple Music; not
 * Subsonic, whose search3 has no playlists), and inside the saved library ([savedLibrary]) the library must
 * hold playlists ([canReadPlaylists]).
 */
fun searchFilters(canSearchPlaylists: Boolean, canReadPlaylists: Boolean, savedLibrary: Boolean): List<SearchFilter> =
    SearchFilter.entries.filter { filter ->
        when (filter) {
            SearchFilter.Playlists -> canSearchPlaylists && (!savedLibrary || canReadPlaylists)
            else -> true
        }
    }

/** The one section this filter lists; null for [SearchFilter.All] and [SearchFilter.Memories]. */
val SearchFilter.section: SearchSection?
    get() = when (this) {
        SearchFilter.All, SearchFilter.Memories -> null
        SearchFilter.Songs -> SearchSection.Songs
        SearchFilter.Albums -> SearchSection.Albums
        SearchFilter.Artists -> SearchSection.Artists
        SearchFilter.Playlists -> SearchSection.Playlists
    }

/**
 * How many of the service's [available] rows of [section] this filter lists, from the top. Switching
 * fetches nothing: a type shows what the mixed search already returned. All keeps at most
 * [SEARCH_ALL_ROWS_PER_TYPE] of each type; Memories lists none of them.
 */
fun SearchFilter.shownRows(section: SearchSection, available: Int): Int = when (this) {
    SearchFilter.All -> minOf(available, SEARCH_ALL_ROWS_PER_TYPE)
    SearchFilter.Memories -> 0
    else -> if (this.section == section) available else 0
}
