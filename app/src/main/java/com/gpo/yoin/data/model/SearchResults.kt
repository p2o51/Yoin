package com.gpo.yoin.data.model

/** One kind of row a search returns. */
enum class SearchResultType { Tracks, Albums, Artists, Playlists }

/**
 * [more] holds the types the service has further results of past these, read with
 * [com.gpo.yoin.data.source.MusicLibrary.searchMore]: Spotify answers 10 a type at a time
 * (Gemini review 2026-10-20, 1.3). Empty where a search returns all it ever will.
 */
data class SearchResults(
    val tracks: List<Track> = emptyList(),
    val albums: List<Album> = emptyList(),
    val artists: List<Artist> = emptyList(),
    val playlists: List<Playlist> = emptyList(),
    val more: Set<SearchResultType> = emptySet(),
) {
    val isEmpty: Boolean
        get() = tracks.isEmpty() && albums.isEmpty() && artists.isEmpty() && playlists.isEmpty()

    fun countOf(type: SearchResultType): Int = when (type) {
        SearchResultType.Tracks -> tracks.size
        SearchResultType.Albums -> albums.size
        SearchResultType.Artists -> artists.size
        SearchResultType.Playlists -> playlists.size
    }

    /**
     * These results with [page]'s rows of [type] after them, minus any already here (a service's
     * pages can shift while they are read). Whether more follow is [page]'s word, and none do when
     * it added nothing.
     */
    fun appending(type: SearchResultType, page: SearchResults): SearchResults {
        val grown = when (type) {
            SearchResultType.Tracks -> copy(tracks = (tracks + page.tracks).distinctBy(Track::id))
            SearchResultType.Albums -> copy(albums = (albums + page.albums).distinctBy(Album::id))
            SearchResultType.Artists -> copy(artists = (artists + page.artists).distinctBy(Artist::id))
            SearchResultType.Playlists -> copy(playlists = (playlists + page.playlists).distinctBy(Playlist::id))
        }
        val hasMore = type in page.more && grown.countOf(type) > countOf(type)
        return grown.copy(more = if (hasMore) more + type else more - type)
    }
}

data class Starred(
    val tracks: List<Track> = emptyList(),
    val albums: List<Album> = emptyList(),
    val artists: List<Artist> = emptyList(),
)
