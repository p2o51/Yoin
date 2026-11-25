package com.gpo.yoin.data.search

import com.gpo.yoin.data.model.Album
import com.gpo.yoin.data.model.Artist
import com.gpo.yoin.data.model.MediaId
import com.gpo.yoin.data.model.Playlist
import com.gpo.yoin.data.model.SearchResults
import com.gpo.yoin.data.model.Track
import com.gpo.yoin.shared.data.model.MediaRef
import com.gpo.yoin.shared.memory.MemoryLedgerEntry
import com.gpo.yoin.shared.search.MemoryAttachment
import com.gpo.yoin.shared.search.MemoryHitField
import com.gpo.yoin.shared.search.MemorySearchHit
import com.gpo.yoin.shared.search.MemorySearchMatches
import com.gpo.yoin.shared.search.NoteSearchHit
import com.gpo.yoin.shared.search.SEARCH_ALL_MAX_ROWS
import com.gpo.yoin.shared.search.SEARCH_ALL_MAX_WIDE
import com.gpo.yoin.shared.search.SearchAlbumRow
import com.gpo.yoin.shared.search.SearchArtistRow
import com.gpo.yoin.shared.search.SearchPageTile
import com.gpo.yoin.shared.search.SearchPlaylistRow
import com.gpo.yoin.shared.search.SearchQuery
import com.gpo.yoin.shared.search.SearchScore
import com.gpo.yoin.shared.search.SearchSection
import com.gpo.yoin.shared.search.SearchServiceRows
import com.gpo.yoin.shared.search.SearchSongRow
import com.gpo.yoin.shared.search.SearchTileKind
import com.gpo.yoin.shared.search.layoutSearchPage
import com.gpo.yoin.shared.search.packSearchTiles as packTiles
import com.gpo.yoin.shared.search.memoryAttachments as sharedMemoryAttachments

/*
 * What the search's All page shows for one query (owner 2026-11-20, prototype v3.1 in #Yoin:#18), in the
 * app's own models. The rules (matching, ranking, section order, relevance folding, the best-match card,
 * packing) are :shared's (com.gpo.yoin.shared.search, layoutSearchPage), the same iOS calls through
 * YoinMemorySearch; this file hands it the service's rows by name and puts the Track / Album / Artist /
 * Playlist back on the tiles it returns.
 */

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

/**
 * Builds the All page for [query] from the service's [results], the ledger's [memories] and the memories
 * [attachments] kept on the albums returned (by album id); see [layoutSearchPage] for the rules.
 * [activeProfileId] places the ledger's hits; [columns] is the grid's width in cells (3 on a phone).
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
    val artists = results?.artists.orEmpty()
    val playlists = results?.playlists.orEmpty()
    val rows = SearchServiceRows(
        songs = tracks.map { SearchSongRow(it.id.toRef(), it.title, it.artist, it.album) },
        albums = albums.map { SearchAlbumRow(it.id.toRef(), it.name, it.artist) },
        artists = artists.map { SearchArtistRow(it.id.toRef(), it.name) },
        playlists = playlists.map { SearchPlaylistRow(it.id.toRef(), it.name, it.owner) },
    )
    val layout = layoutSearchPage(
        query = query,
        rows = rows,
        memories = memories,
        attachments = attachments.mapKeys { (id, _) -> id.toRef() },
        activeProfileId = activeProfileId,
        columns = columns,
        maxRows = maxRows,
        maxWide = maxWide,
    )
    fun tile(tile: SearchPageTile): SearchTile = when (tile.kind) {
        SearchTileKind.Song -> SearchTile.Song(tracks[tile.index], tile.note, tile.score, tile.relevant)
        SearchTileKind.Note -> SearchTile.Note(tile.note!!)
        SearchTileKind.Album -> SearchTile.AlbumRow(albums[tile.index], tile.attachment, tile.hit, tile.score, tile.relevant)
        SearchTileKind.Memory -> SearchTile.Memory(tile.hit!!)
        SearchTileKind.Artist -> SearchTile.ArtistRow(artists[tile.index], tile.score, tile.relevant)
        SearchTileKind.Playlist -> SearchTile.PlaylistRow(playlists[tile.index], tile.score, tile.relevant)
    }
    val best = layout.best?.let { best ->
        when (best.kind) {
            SearchTileKind.Song -> SearchBestMatch.Song(tracks[best.index])
            SearchTileKind.Album -> albums[best.index].let { SearchBestMatch.AlbumMatch(it, attachments[it.id]) }
            SearchTileKind.Artist -> SearchBestMatch.ArtistMatch(artists[best.index])
            SearchTileKind.Playlist -> SearchBestMatch.PlaylistMatch(playlists[best.index])
            SearchTileKind.Note, SearchTileKind.Memory -> null
        }
    }
    return SearchPage(
        sections = layout.sections.map { section ->
            SearchSectionPage(
                section = section.section,
                tiles = section.tiles.map(::tile),
                allRows = section.allRows.map { row -> SearchTileRow(row.tiles.map(::tile), row.wideAtEnd) },
                folded = section.folded,
                more = section.more,
                score = section.score,
            )
        },
        bestMatch = best,
        memories = layout.memories.map(::tile),
    )
}

/**
 * Packs [tiles] (in rank order) into rows of [columns] cells, Home's Jump Back In way ([packTiles] in
 * :shared): 1×2 rows spread between rows of singles and alternating sides, at most [maxRows] rows and
 * [maxWide] 1×2s (0 = no limit).
 */
fun packSearchTiles(tiles: List<SearchTile>, columns: Int, maxRows: Int = 0, maxWide: Int = 0): List<SearchTileRow> =
    packTiles(tiles, columns, maxRows, maxWide) { it.wide }.map { SearchTileRow(it.tiles, it.wideAtEnd) }

/**
 * The memories kept on [albums] (a search's album rows), by album id: :shared's memoryAttachments, in the
 * app's models. Only the exact album in the account in use (docs/search.md).
 */
fun memoryAttachments(
    albums: List<Album>,
    entries: List<MemoryLedgerEntry>,
    activeProfileId: String?,
    aiTitles: Map<String, String> = emptyMap(),
): Map<MediaId, MemoryAttachment> =
    sharedMemoryAttachments(albums.map { it.id.toRef() }, entries, activeProfileId, aiTitles)
        .mapKeys { (ref, _) -> ref.toMediaId() }

internal fun MediaId.toRef(): MediaRef = MediaRef(provider, rawId)

internal fun MediaRef.toMediaId(): MediaId = MediaId(provider, rawId)
