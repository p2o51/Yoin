package com.gpo.yoin.shared.search

import com.gpo.yoin.shared.data.model.MediaRef
import com.gpo.yoin.shared.memory.ledgerAlbumKey
import com.gpo.yoin.shared.tokens.YoinTokens

/*
 * What the search's All page shows for one query (owner 2026-11-20, prototype v3.1 in #Yoin:#18): the
 * service's rows and the listener's memories merged into the same sections — no Memories section — as
 * Home's Jump Back In tiles. A song met through a note, an album met through its memory's words, is a
 * 1×2 tile; everything else a 1×1. Sections are ordered by how well they met the query; inside one, the
 * tiles interleave the way Home's shelf does ([packSearchTiles]).
 *
 * The service's rows go in as names only ([SearchServiceRows]) and come back as indexes into those lists, so
 * each platform keeps its own Track / Album / Artist / Playlist (Android wraps this in data/search/SearchPage.kt).
 */

/** The All page's sections, in their default order. */
enum class SearchSection { Songs, Albums, Artists, Playlists }

/** A song the service returned, as search reads it. */
data class SearchSongRow(val ref: MediaRef, val title: String?, val artist: String?, val album: String?)

data class SearchAlbumRow(val ref: MediaRef, val name: String, val artist: String?)

data class SearchArtistRow(val ref: MediaRef, val name: String)

data class SearchPlaylistRow(val ref: MediaRef, val name: String, val owner: String?)

/** What the service answered, in its order. */
data class SearchServiceRows(
    val songs: List<SearchSongRow> = emptyList(),
    val albums: List<SearchAlbumRow> = emptyList(),
    val artists: List<SearchArtistRow> = emptyList(),
    val playlists: List<SearchPlaylistRow> = emptyList(),
)

/** What a tile is. [Note] and [Memory] come from the ledger alone; the rest are service rows. */
enum class SearchTileKind { Song, Note, Album, Memory, Artist, Playlist }

/**
 * One tile of a section. [index] points into [SearchServiceRows]' list of its kind (songs for [SearchTileKind.Song],
 * …); -1 for a ledger tile. [wide] tiles take two cells.
 */
data class SearchPageTile(
    val kind: SearchTileKind,
    /** Stable across reloads: the list key (`song:provider:id`, `note:entryId`, `album:…`, `memory:albumKey`, `artist:…`, `playlist:…`). */
    val key: String,
    val index: Int,
    val score: SearchScore,
    /** The query met the row itself, or a note / memory joined to it. Ledger tiles always are. */
    val relevant: Boolean,
    val wide: Boolean,
    /** Song: one of this account's notes on it the query met, shown beside the cover. Note: the note. */
    val note: NoteSearchHit?,
    /** Album: the memory's own words met the query, shown beside the cover. Memory: the hit. */
    val hit: MemorySearchHit?,
    /** Album: the memory kept on it in the account in use (its mark). */
    val attachment: MemoryAttachment?,
)

/** One row of packed tiles: at most one wide tile; [wideAtEnd] puts it after the singles (rows alternate). */
data class PackedTileRow<T>(val tiles: List<T>, val wideAtEnd: Boolean = false)

data class SearchPageSection(
    val section: SearchSection,
    /** Every tile of the section, best first: what its own type chip lists (folded rows last). */
    val tiles: List<SearchPageTile>,
    /** What All shows of it, packed into rows. */
    val allRows: List<PackedTileRow<SearchPageTile>>,
    /** The service's rows the query didn't meet at all, left out of All (they still list under the type). */
    val folded: Int,
    /** Tiles the All page had no room for. */
    val more: Int,
    val score: SearchScore,
)

/** The one result the All page leads with, in a large card: [index] into the service's list of [kind]. */
data class SearchPageBest(val kind: SearchTileKind, val index: Int)

data class SearchPageLayout(
    val sections: List<SearchPageSection>,
    val best: SearchPageBest?,
    /** Every note and memory hit, best first: what the Memories type chip lists, one per row. */
    val memories: List<SearchPageTile>,
) {
    val isEmpty: Boolean get() = sections.isEmpty() && memories.isEmpty()

    companion object {
        val Empty = SearchPageLayout(emptyList(), null, emptyList())
    }
}

/** How many rows of a section All shows, and how many 1×2s they may hold (YoinTokens.Search). */
const val SEARCH_ALL_MAX_ROWS = YoinTokens.Search.ALL_MAX_ROWS
const val SEARCH_ALL_MAX_WIDE = YoinTokens.Search.ALL_MAX_WIDE

/** How well [query] met one song: its title, else its artist or album. */
fun SearchQuery.scoreOf(song: SearchSongRow): SearchScore = maxOf(
    score(song.title, MatchTier.Primary),
    score(song.artist, MatchTier.Secondary),
    score(song.album, MatchTier.Secondary),
)

fun SearchQuery.scoreOf(album: SearchAlbumRow): SearchScore =
    maxOf(score(album.name, MatchTier.Primary), score(album.artist, MatchTier.Secondary))

fun SearchQuery.scoreOf(artist: SearchArtistRow): SearchScore = score(artist.name, MatchTier.Primary)

fun SearchQuery.scoreOf(playlist: SearchPlaylistRow): SearchScore =
    maxOf(score(playlist.name, MatchTier.Primary), score(playlist.owner, MatchTier.Secondary))

/**
 * Lays out the All page for [query] from the service's [rows], the ledger's [memories] and the memories
 * [attachments] kept on the albums returned (by album). [activeProfileId] places the ledger's hits: a note on
 * a returned song joins that song's tile only when it's this account's. [columns] is the grid's width in
 * cells (3 on a phone).
 *
 * Relevance: a service can answer with rows the query doesn't appear in at all (Spotify often does). When
 * any of a section's service rows does meet the query, the ones that don't leave All (they still list
 * under the section's own chip, after the rest). When none does — a romanised query, say — the service
 * is trusted and its rows stay.
 */
fun layoutSearchPage(
    query: SearchQuery,
    rows: SearchServiceRows,
    memories: MemorySearchMatches,
    attachments: Map<MediaRef, MemoryAttachment>,
    activeProfileId: String?,
    columns: Int = 3,
    maxRows: Int = SEARCH_ALL_MAX_ROWS,
    maxWide: Int = SEARCH_ALL_MAX_WIDE,
): SearchPageLayout {
    if (query.isBlank) return SearchPageLayout.Empty

    // Songs: notes on returned songs (this account's) join their tile; the rest stand alone.
    val notesBySong = memories.notes
        .filter { it.profileId == activeProfileId && it.provider != null && it.songId != null }
        .groupBy { MediaRef(it.provider!!, it.songId!!) }
    val songTiles = rows.songs.mapIndexed { index, song ->
        val note = notesBySong[song.ref]?.maxWithOrNull(compareBy<NoteSearchHit> { it.score }.thenBy { it.at })
        val own = query.scoreOf(song)
        SearchPageTile(
            kind = SearchTileKind.Song,
            key = "song:${song.ref.key}",
            index = index,
            score = maxOf(own, note?.score ?: SearchScore.None),
            relevant = own.isHit || note != null,
            wide = note != null,
            note = note,
            hit = null,
            attachment = null,
        )
    }
    val joinedNotes = songTiles.mapNotNullTo(HashSet()) { it.note?.entryId }
    val returnedSongs = rows.songs.mapTo(HashSet()) { it.ref }
    val looseNotes = memories.notes
        .filter { it.entryId !in joinedNotes }
        // One tile per song: its best note (the hits are best first).
        .distinctBy { if (it.provider != null && it.songId != null) "${it.profileId}|${it.provider}|${it.songId}" else it.entryId }
        .filterNot { it.profileId == activeProfileId && it.provider != null && it.songId != null && MediaRef(it.provider, it.songId) in returnedSongs }
        .map(::noteTile)

    // Albums: a memory hit on a returned album (this account's) joins its tile; the rest stand alone.
    val hitsByAlbum = memories.memories.associateBy { it.album.key }
    val albumTiles = rows.albums.mapIndexed { index, album ->
        val key = activeProfileId?.let { ledgerAlbumKey(it, album.ref.provider, album.ref.rawId) }
        val hit = key?.let(hitsByAlbum::get)?.takeIf { it.field != MemoryHitField.Album }
        val own = query.scoreOf(album)
        SearchPageTile(
            kind = SearchTileKind.Album,
            key = "album:${album.ref.key}",
            index = index,
            score = maxOf(own, hit?.score ?: SearchScore.None),
            relevant = own.isHit || hit != null,
            wide = hit != null,
            note = null,
            hit = hit,
            attachment = attachments[album.ref],
        )
    }
    val joinedAlbums = rows.albums.mapNotNullTo(HashSet()) { album ->
        activeProfileId?.let { ledgerAlbumKey(it, album.ref.provider, album.ref.rawId) }
    }
    val looseMemories = memories.memories.filter { it.album.key !in joinedAlbums }.map(::memoryTile)

    val artistTiles = rows.artists.mapIndexed { index, artist ->
        val score = query.scoreOf(artist)
        serviceTile(SearchTileKind.Artist, "artist:${artist.ref.key}", index, score)
    }
    val playlistTiles = rows.playlists.mapIndexed { index, playlist ->
        val score = query.scoreOf(playlist)
        serviceTile(SearchTileKind.Playlist, "playlist:${playlist.ref.key}", index, score)
    }

    val sections = listOf(
        section(SearchSection.Songs, songTiles, looseNotes, columns, maxRows, maxWide),
        section(SearchSection.Albums, albumTiles, looseMemories, columns, maxRows, maxWide),
        section(SearchSection.Artists, artistTiles, emptyList(), columns, maxRows, maxWide),
        section(SearchSection.Playlists, playlistTiles, emptyList(), columns, maxRows, maxWide),
    ).filterNotNull().sortedWith(compareByDescending<SearchPageSection> { it.score }.thenBy { it.section.ordinal })

    val memoryTiles = (
        songTiles.filter { it.note != null } + looseNotes + albumTiles.filter { it.hit != null } +
            looseMemories.filter { it.wide }
        )
        .sortedWith(compareByDescending<SearchPageTile> { it.score }.thenBy { it.key })

    return SearchPageLayout(sections, bestMatch(songTiles, albumTiles, artistTiles, playlistTiles), memoryTiles)
}

private fun noteTile(note: NoteSearchHit) = SearchPageTile(
    kind = SearchTileKind.Note,
    key = "note:${note.entryId}",
    index = -1,
    score = note.score,
    relevant = true,
    wide = true,
    note = note,
    hit = null,
    attachment = null,
)

private fun memoryTile(hit: MemorySearchHit) = SearchPageTile(
    kind = SearchTileKind.Memory,
    key = "memory:${hit.album.key}",
    index = -1,
    score = hit.score,
    relevant = true,
    wide = hit.field != MemoryHitField.Album,
    note = null,
    hit = hit,
    attachment = null,
)

private fun serviceTile(kind: SearchTileKind, key: String, index: Int, score: SearchScore) = SearchPageTile(
    kind = kind,
    key = key,
    index = index,
    score = score,
    relevant = score.isHit,
    wide = false,
    note = null,
    hit = null,
    attachment = null,
)

private fun section(
    section: SearchSection,
    service: List<SearchPageTile>,
    ledger: List<SearchPageTile>,
    columns: Int,
    maxRows: Int,
    maxWide: Int,
): SearchPageSection? {
    if (service.isEmpty() && ledger.isEmpty()) return null
    val fold = service.any { it.relevant } && service.any { !it.relevant }
    val kept = service.filter { !fold || it.relevant }
    val folded = service.filter { fold && !it.relevant }
    // The service's order stands inside its rows (it ranks by more than names); the ledger's hits follow.
    val tiles = kept + ledger + folded
    val shown = kept + ledger
    val rows = packSearchTiles(shown, columns, maxRows, maxWide) { it.wide }
    val score = (kept + ledger).maxOfOrNull { it.score } ?: SearchScore.None
    return SearchPageSection(section, tiles, rows, folded.size, shown.size - rows.sumOf { it.tiles.size }, score)
}

private fun bestMatch(
    songs: List<SearchPageTile>,
    albums: List<SearchPageTile>,
    artists: List<SearchPageTile>,
    playlists: List<SearchPageTile>,
): SearchPageBest? {
    var best: Pair<SearchScore, SearchPageBest>? = null
    fun offer(score: SearchScore, tile: SearchPageTile) {
        if (score.isStrongName && (best == null || score > best!!.first)) best = score to SearchPageBest(tile.kind, tile.index)
    }
    // Only the thing's own name earns the card, never a note or memory on it.
    songs.forEach { offer(it.score.takeIf { _ -> it.note == null } ?: SearchScore.None, it) }
    albums.forEach { offer(it.score.takeIf { _ -> it.hit == null } ?: SearchScore.None, it) }
    artists.forEach { offer(it.score, it) }
    playlists.forEach { offer(it.score, it) }
    return best?.second
}

/**
 * Packs [tiles] (in rank order) into rows of [columns] cells, Home's Jump Back In way: a row holding a
 * 1×2 ([isWide]) is that 1×2 and `columns - 2` singles; the 1×2 rows are spread between rows of singles rather
 * than stacked, and alternate sides. A full row of singles leads when there is one (names rank above notes);
 * a short one goes last, so no row in the middle has a hole. At most [maxRows] rows and [maxWide] 1×2s
 * (0 = no limit); a 1×2 over the limit is left out, as is what doesn't fit.
 */
fun <T> packSearchTiles(
    tiles: List<T>,
    columns: Int,
    maxRows: Int = 0,
    maxWide: Int = 0,
    isWide: (T) -> Boolean,
): List<PackedTileRow<T>> {
    if (tiles.isEmpty()) return emptyList()
    val cols = columns.coerceAtLeast(2)
    val wides = tiles.filter(isWide).let { if (maxWide > 0) it.take(maxWide) else it }
    val singles = tiles.filterNot(isWide)
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
    val rows = ArrayList<PackedTileRow<T>>()
    var nextWide = 0
    var side = 0
    for (wideRow in plan) {
        if (maxRows > 0 && rows.size >= maxRows) break
        if (wideRow) {
            val row = buildList {
                add(wides[nextWide++])
                repeat(perWide) { pairs.removeFirstOrNull()?.let(::add) }
            }
            rows += PackedTileRow(row, wideAtEnd = side % 2 == 1)
            side++
        } else {
            rows += PackedTileRow(buildList { repeat(cols) { rest.removeFirstOrNull()?.let(::add) } })
        }
    }
    return rows
}
