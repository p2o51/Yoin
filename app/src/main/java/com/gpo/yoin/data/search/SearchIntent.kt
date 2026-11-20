package com.gpo.yoin.data.search

import com.gpo.yoin.data.model.Album
import com.gpo.yoin.data.model.Artist
import com.gpo.yoin.data.model.Playlist
import com.gpo.yoin.data.model.SearchResults
import com.gpo.yoin.data.model.Track
import com.gpo.yoin.shared.tokens.YoinTokens

/*
 * The All page answers what was typed (docs/search.md, "Ordering and caps"): its sections in order of
 * how well each met the query, not a fixed list, and a best-match card only for an exact or prefix hit
 * on a name. Each service still ranks its own rows; this only orders the sections around them.
 */

/** One section of the All page, in its default order (the order of the type chips). */
enum class SearchSection {
    Songs,
    Albums,
    Memories,
    Notes,
    Artists,
    Playlists,
    ;

    /** Rows the All page shows before this section's "show all"; null where the number isn't set yet (artists). */
    val allPreview: Int?
        get() = when (this) {
            Songs -> YoinTokens.Search.ALL_SONGS_PREVIEW
            Albums -> YoinTokens.Search.ALL_ALBUMS_PREVIEW
            Memories -> YoinTokens.Search.ALL_MEMORIES_PREVIEW
            Notes -> YoinTokens.Search.ALL_NOTES_PREVIEW
            Artists -> null
            Playlists -> YoinTokens.Search.ALL_PLAYLISTS_PREVIEW
        }
}

/** The one result the All page leads with, in a large card. */
sealed interface SearchBestMatch {
    data class Song(val track: Track) : SearchBestMatch
    data class AlbumMatch(val album: Album) : SearchBestMatch
    data class ArtistMatch(val artist: Artist) : SearchBestMatch
    data class PlaylistMatch(val playlist: Playlist) : SearchBestMatch
}

/** How the All page lays out one query's results: which sections, in what order, and what leads. */
data class SearchLayout(
    /** The sections with any rows, best first. */
    val sections: List<SearchSection>,
    /** Each listed section's best hit. */
    val sectionScores: Map<SearchSection, SearchScore>,
    val bestMatch: SearchBestMatch?,
)

/** How well [query] met one song: its title, else its artist or album. */
fun SearchQuery.scoreOf(track: Track): SearchScore = maxOf(
    score(track.title, MatchTier.Primary),
    score(track.artist, MatchTier.Secondary),
    score(track.album, MatchTier.Secondary),
)

fun SearchQuery.scoreOf(album: Album): SearchScore =
    maxOf(score(album.name, MatchTier.Primary), score(album.artist, MatchTier.Secondary))

fun SearchQuery.scoreOf(artist: Artist): SearchScore = score(artist.name, MatchTier.Primary)

fun SearchQuery.scoreOf(playlist: Playlist): SearchScore =
    maxOf(score(playlist.name, MatchTier.Primary), score(playlist.owner, MatchTier.Secondary))

/**
 * Lays out [results] (the service's) and [memories] (the ledger's) for [query]. A section's place is
 * its best row's score, ties broken by the default order, so songs lead unless something else met the
 * query better: a name typed in full floats its artist or album above the songs that only mention it.
 * A service row that met nothing locally (a service matching on what Yoin doesn't see) still lists, at
 * the bottom of the order its default puts it in.
 */
fun searchLayout(query: SearchQuery, results: SearchResults?, memories: MemorySearchMatches): SearchLayout {
    val scores = LinkedHashMap<SearchSection, SearchScore>()
    var best: Pair<SearchScore, SearchBestMatch>? = null

    fun <T> section(section: SearchSection, rows: List<T>, scoreOf: (T) -> SearchScore, card: ((T) -> SearchBestMatch)?) {
        if (rows.isEmpty()) return
        var top = SearchScore.None
        rows.forEach { row ->
            val score = scoreOf(row)
            if (score > top) top = score
            if (card != null && score.isStrongName && (best == null || score > best!!.first)) {
                best = score to card(row)
            }
        }
        scores[section] = top
    }

    if (results != null) {
        section(SearchSection.Songs, results.tracks, query::scoreOf, SearchBestMatch::Song)
        section(SearchSection.Albums, results.albums, query::scoreOf, SearchBestMatch::AlbumMatch)
    }
    section(SearchSection.Memories, memories.memories, MemorySearchHit::score, null)
    section(SearchSection.Notes, memories.notes, NoteSearchHit::score, null)
    if (results != null) {
        section(SearchSection.Artists, results.artists, query::scoreOf, SearchBestMatch::ArtistMatch)
        section(SearchSection.Playlists, results.playlists, query::scoreOf, SearchBestMatch::PlaylistMatch)
    }

    val ordered = scores.keys.sortedWith(
        compareByDescending<SearchSection> { scores.getValue(it) }.thenBy { it.ordinal },
    )
    return SearchLayout(ordered, scores, best?.second)
}
