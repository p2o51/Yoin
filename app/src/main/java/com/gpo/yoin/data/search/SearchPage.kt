package com.gpo.yoin.data.search

import com.gpo.yoin.data.memory.ledgerAlbumKey
import com.gpo.yoin.data.model.Album
import com.gpo.yoin.data.model.Artist
import com.gpo.yoin.data.model.MediaId
import com.gpo.yoin.data.model.Playlist
import com.gpo.yoin.data.model.SearchResults
import com.gpo.yoin.data.model.Track
import com.gpo.yoin.shared.tokens.YoinTokens

/*
 * What the search's All page shows for one query (owner 2026-11-20, prototype v3.1 in #Yoin:#18): the
 * service's rows and the listener's memories merged into the same sections — no Memories section — as
 * Home's Jump Back In tiles. A song met through a note, an album met through its memory's words, is a
 * 1×2 tile; everything else a 1×1. Sections are ordered by how well they met the query; inside one, the
 * tiles interleave the way Home's shelf does (packSearchTiles).
 */

/** The All page's sections, in their default order. */
enum class SearchSection { Songs, Albums, Artists, Playlists }

/** One tile of a section. [wide] tiles take two cells. */
sealed interface SearchTile {
    /** Stable across reloads: the list key. */
    val key: String
    val score: SearchScore
    val wide: Boolean get() = false

    /**
     * A song the service returned. [note] = one of the listener's notes on it the query met: the tile
     * shows it beside the cover. [relevant] = the query met the song itself or a note on it.
     */
    data class Song(val track: Track, val note: NoteSearchHit?, override val score: SearchScore, val relevant: Boolean) : SearchTile {
        override val key get() = "song:${track.id}"
        override val wide get() = note != null
    }

    /** A note the query met, on a song the service didn't return (another account's, or not matched there). */
    data class Note(val note: NoteSearchHit) : SearchTile {
        override val key get() = "note:${note.entryId}"
        override val score get() = note.score
        override val wide get() = true
    }

    /**
     * An album the service returned. [memory] = the memory kept on it in the account in use (its mark);
     * [hit] = that memory's own words met the query: shown beside the cover.
     */
    data class AlbumRow(
        val album: Album,
        val memory: MemoryAttachment?,
        val hit: MemorySearchHit?,
        override val score: SearchScore,
        val relevant: Boolean,
    ) : SearchTile {
        override val key get() = "album:${album.id}"
        override val wide get() = hit != null
    }

    /** A memory the query met on an album the service didn't return (another account's, or only in the ledger). */
    data class Memory(val hit: MemorySearchHit) : SearchTile {
        override val key get() = "memory:${hit.album.key}"
        override val score get() = hit.score
        override val wide get() = hit.field != MemoryHitField.Album
    }

    data class ArtistRow(val artist: Artist, override val score: SearchScore, val relevant: Boolean) : SearchTile {
        override val key get() = "artist:${artist.id}"
    }

    data class PlaylistRow(val playlist: Playlist, override val score: SearchScore, val relevant: Boolean) : SearchTile {
        override val key get() = "playlist:${playlist.id}"
    }
}

/** The one result the All page leads with, in a large card. */
sealed interface SearchBestMatch {
    data class Song(val track: Track) : SearchBestMatch
    data class AlbumMatch(val album: Album, val memory: MemoryAttachment?) : SearchBestMatch
    data class ArtistMatch(val artist: Artist) : SearchBestMatch
    data class PlaylistMatch(val playlist: Playlist) : SearchBestMatch
}

/** One row of tiles: at most one wide tile; [wideAtEnd] puts it after the singles (rows alternate). */
data class SearchTileRow(val tiles: List<SearchTile>, val wideAtEnd: Boolean = false)

data class SearchSectionPage(
    val section: SearchSection,
    /** Every tile of the section, best first: what its own type chip lists (folded rows last). */
    val tiles: List<SearchTile>,
    /** What All shows of it, packed into rows. */
    val allRows: List<SearchTileRow>,
    /** The service's rows the query didn't meet at all, left out of All (they still list under the type). */
    val folded: Int,
    /** Tiles the All page had no room for. */
    val more: Int,
    val score: SearchScore,
)

data class SearchPage(
    val sections: List<SearchSectionPage>,
    val bestMatch: SearchBestMatch?,
    /** Every note and memory hit, best first: what the Memories type chip lists, one per row. */
    val memories: List<SearchTile>,
) {
    val isEmpty: Boolean get() = sections.isEmpty() && memories.isEmpty()

    fun section(section: SearchSection): SearchSectionPage? = sections.firstOrNull { it.section == section }

    companion object {
        val Empty = SearchPage(emptyList(), null, emptyList())
    }
}

/** How many rows of a section All shows, and how many 1×2s they may hold (shared with iOS). */
const val SEARCH_ALL_MAX_ROWS = YoinTokens.Search.ALL_MAX_ROWS
const val SEARCH_ALL_MAX_WIDE = YoinTokens.Search.ALL_MAX_WIDE

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
 * Builds the All page for [query] from the service's [results], the ledger's [memories] and the memories
 * [attachments] kept on the albums returned (by album id). [activeProfileId] places the ledger's hits:
 * a note on a returned song joins that song's tile only when it's this account's. [columns] is the
 * grid's width in cells (3 on a phone).
 *
 * Relevance: a service can answer with rows the query doesn't appear in at all (Spotify often does). When
 * any of a section's service rows does meet the query, the ones that don't leave All (they still list
 * under the section's own chip, after the rest). When none does — a romanised query, say — the service
 * is trusted and its rows stay.
 */
fun buildSearchPage(
    query: SearchQuery,
    results: SearchResults?,
    memories: MemorySearchMatches,
    attachments: Map<MediaId, MemoryAttachment>,
    activeProfileId: String?,
    columns: Int = 3,
    maxRows: Int = SEARCH_ALL_MAX_ROWS,
    maxWide: Int = SEARCH_ALL_MAX_WIDE,
): SearchPage {
    if (query.isBlank) return SearchPage.Empty
    val tracks = results?.tracks.orEmpty()
    val albums = results?.albums.orEmpty()

    // Songs: notes on returned songs (this account's) join their tile; the rest stand alone.
    val notesBySong = memories.notes
        .filter { it.profileId == activeProfileId && it.provider != null && it.songId != null }
        .groupBy { MediaId(it.provider!!, it.songId!!) }
    val songTiles = tracks.map { track ->
        val note = notesBySong[track.id]?.maxWithOrNull(compareBy<NoteSearchHit> { it.score }.thenBy { it.at })
        val own = query.scoreOf(track)
        SearchTile.Song(track, note, maxOf(own, note?.score ?: SearchScore.None), own.isHit || note != null)
    }
    val joinedNotes = songTiles.mapNotNullTo(HashSet()) { it.note?.entryId }
    val returnedSongs = tracks.mapTo(HashSet()) { it.id }
    val looseNotes = memories.notes
        .filter { it.entryId !in joinedNotes }
        // One tile per song: its best note (the hits are best first).
        .distinctBy { if (it.provider != null && it.songId != null) "${it.profileId}|${it.provider}|${it.songId}" else it.entryId }
        .filterNot { it.profileId == activeProfileId && it.provider != null && it.songId != null && MediaId(it.provider, it.songId) in returnedSongs }
        .map(SearchTile::Note)

    // Albums: a memory hit on a returned album (this account's) joins its tile; the rest stand alone.
    val hitsByAlbum = memories.memories.associateBy { it.album.key }
    val albumTiles = albums.map { album ->
        val key = activeProfileId?.let { ledgerAlbumKey(it, album.id.provider, album.id.rawId) }
        val hit = key?.let(hitsByAlbum::get)?.takeIf { it.field != MemoryHitField.Album }
        val own = query.scoreOf(album)
        SearchTile.AlbumRow(album, attachments[album.id], hit, maxOf(own, hit?.score ?: SearchScore.None), own.isHit || hit != null)
    }
    val joinedAlbums = albums.mapNotNullTo(HashSet()) { album ->
        activeProfileId?.let { ledgerAlbumKey(it, album.id.provider, album.id.rawId) }
    }
    val looseMemories = memories.memories.filter { it.album.key !in joinedAlbums }.map(SearchTile::Memory)

    val artistTiles = results?.artists.orEmpty().map { artist ->
        val score = query.scoreOf(artist)
        SearchTile.ArtistRow(artist, score, score.isHit)
    }
    val playlistTiles = results?.playlists.orEmpty().map { playlist ->
        val score = query.scoreOf(playlist)
        SearchTile.PlaylistRow(playlist, score, score.isHit)
    }

    val sections = listOf(
        section(SearchSection.Songs, songTiles, songTiles.map { it.relevant }, looseNotes, columns, maxRows, maxWide),
        section(SearchSection.Albums, albumTiles, albumTiles.map { it.relevant }, looseMemories, columns, maxRows, maxWide),
        section(SearchSection.Artists, artistTiles, artistTiles.map { it.relevant }, emptyList(), columns, maxRows, maxWide),
        section(SearchSection.Playlists, playlistTiles, playlistTiles.map { it.relevant }, emptyList(), columns, maxRows, maxWide),
    ).filterNotNull().sortedWith(compareByDescending<SearchSectionPage> { it.score }.thenBy { it.section.ordinal })

    val memoryTiles = (songTiles.filter { it.note != null } + looseNotes + albumTiles.filter { it.hit != null } +
        looseMemories.filter { it.wide })
        .sortedWith(compareByDescending<SearchTile> { it.score }.thenBy { it.key })

    return SearchPage(sections, bestMatch(songTiles, albumTiles, artistTiles, playlistTiles), memoryTiles)
}

private fun section(
    section: SearchSection,
    service: List<SearchTile>,
    relevant: List<Boolean>,
    ledger: List<SearchTile>,
    columns: Int,
    maxRows: Int,
    maxWide: Int,
): SearchSectionPage? {
    if (service.isEmpty() && ledger.isEmpty()) return null
    val fold = relevant.any { it } && relevant.any { !it }
    val kept = service.filterIndexed { i, _ -> !fold || relevant[i] }
    val folded = service.filterIndexed { i, _ -> fold && !relevant[i] }
    // The service's order stands inside its rows (it ranks by more than names); the ledger's hits follow.
    val tiles = kept + ledger + folded
    val shown = kept + ledger
    val rows = packSearchTiles(shown, columns, maxRows, maxWide)
    val score = (kept + ledger).maxOfOrNull { it.score } ?: SearchScore.None
    return SearchSectionPage(section, tiles, rows, folded.size, shown.size - rows.sumOf { it.tiles.size }, score)
}

private fun bestMatch(
    songs: List<SearchTile.Song>,
    albums: List<SearchTile.AlbumRow>,
    artists: List<SearchTile.ArtistRow>,
    playlists: List<SearchTile.PlaylistRow>,
): SearchBestMatch? {
    var best: Pair<SearchScore, SearchBestMatch>? = null
    fun offer(score: SearchScore, match: () -> SearchBestMatch) {
        if (score.isStrongName && (best == null || score > best!!.first)) best = score to match()
    }
    // Only the thing's own name earns the card, never a note or memory on it.
    songs.forEach { offer(it.score.takeIf { _ -> it.note == null } ?: SearchScore.None) { SearchBestMatch.Song(it.track) } }
    albums.forEach { offer(it.score.takeIf { _ -> it.hit == null } ?: SearchScore.None) { SearchBestMatch.AlbumMatch(it.album, it.memory) } }
    artists.forEach { offer(it.score) { SearchBestMatch.ArtistMatch(it.artist) } }
    playlists.forEach { offer(it.score) { SearchBestMatch.PlaylistMatch(it.playlist) } }
    return best?.second
}

/**
 * Packs [tiles] (in rank order) into rows of [columns] cells, Home's Jump Back In way: a row holding a
 * 1×2 is that 1×2 and `columns - 2` singles; the 1×2 rows are spread between rows of singles rather than
 * stacked, and alternate sides. A full row of singles leads when there is one (names rank above notes);
 * a short one goes last, so no row in the middle has a hole. At most [maxRows] rows and [maxWide] 1×2s
 * (0 = no limit); a 1×2 over the limit is left out, as is what doesn't fit.
 */
fun packSearchTiles(tiles: List<SearchTile>, columns: Int, maxRows: Int = 0, maxWide: Int = 0): List<SearchTileRow> {
    if (tiles.isEmpty()) return emptyList()
    val cols = columns.coerceAtLeast(2)
    val wides = tiles.filter { it.wide }.let { if (maxWide > 0) it.take(maxWide) else it }
    val singles = tiles.filterNot { it.wide }
    val perWide = cols - 2
    val pairN = minOf(wides.size * perWide, singles.size)
    val restN = singles.size - pairN
    var singleRows = (restN + cols - 1) / cols
    val leadSingle = restN >= cols
    // Leading full rows take the best singles; otherwise the best ride beside the 1×2s.
    val rest = ArrayDeque(if (leadSingle) singles.take(restN) else singles.drop(pairN))
    val pairs = ArrayDeque(if (leadSingle) singles.drop(restN) else singles.take(pairN))
    val plan = ArrayList<Boolean>() // true = a 1×2 row
    var w = wides.size
    if (leadSingle && singleRows > 0) {
        plan += false
        singleRows--
    }
    while (w > 0 || singleRows > 0) {
        if (w > 0) {
            plan += true
            w--
        }
        if (singleRows > 0 && (restN % cols == 0 || singleRows > 1 || w == 0)) {
            plan += false
            singleRows--
        }
    }
    val rows = ArrayList<SearchTileRow>()
    var nextWide = 0
    var side = 0
    for (isWide in plan) {
        if (maxRows > 0 && rows.size >= maxRows) break
        if (isWide) {
            val row = buildList {
                add(wides[nextWide++])
                repeat(perWide) { pairs.removeFirstOrNull()?.let(::add) }
            }
            rows += SearchTileRow(row, wideAtEnd = side % 2 == 1)
            side++
        } else {
            rows += SearchTileRow(buildList { repeat(cols) { rest.removeFirstOrNull()?.let(::add) } })
        }
    }
    return rows
}
