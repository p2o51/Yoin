package com.gpo.yoin.ui.library

import com.gpo.yoin.data.model.Album
import com.gpo.yoin.data.model.Artist
import com.gpo.yoin.data.model.MediaId
import com.gpo.yoin.data.model.Playlist
import com.gpo.yoin.data.repository.LibraryRecents
import com.gpo.yoin.data.source.ServiceFeatures
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.Locale

/** How a Library view orders what it lists (Spotify's Your Library sorts). */
enum class LibrarySort {
    /**
     * Last opened or played in Yoin first ([LibraryRecents]); what has no
     * record follows in [RecentlyAdded] order.
     */
    Recents,

    /** Newest in the library first, by the provider's date; undated items last, alphabetically. */
    RecentlyAdded,

    /**
     * By name, in the fast scroller's alphabet ([LibraryIndex], applied by
     * [LibraryScrollIndexer]): letter by letter, digits and symbols last.
     */
    Alphabetical,

    /** Albums by artist, playlists by owner (an artist is its own); then by name. */
    Creator
}

/** One cell of the All view: an artist, an album or a playlist. Never a song. */
sealed interface LibraryItem {
    /** Unique across the three kinds (two kinds may share a raw id): the grid key. */
    val key: String

    data class ArtistItem(val artist: Artist) : LibraryItem {
        override val key: String get() = "artist:${artist.id}"
    }

    data class AlbumItem(val album: Album) : LibraryItem {
        override val key: String get() = "album:${album.id}"
    }

    data class PlaylistItem(val playlist: Playlist) : LibraryItem {
        override val key: String get() = "playlist:${playlist.id}"
    }
}

/**
 * The orders [view] offers on this service, the default first. Only what the
 * data can back: Recently added needs the provider's library dates
 * ([ServiceFeatures.albumsHaveLibraryDates] /
 * [ServiceFeatures.playlistsHaveLibraryDates]), and no service dates its
 * artists. Songs keeps its one order (Spotify's is Liked Songs' own) and
 * Favorites its sections, so neither has a sort row.
 */
fun librarySortOptions(view: LibraryTab, features: ServiceFeatures): List<LibrarySort> {
    val anyDates = features.albumsHaveLibraryDates || features.playlistsHaveLibraryDates
    return when (view) {
        LibraryTab.All -> listOfNotNull(
            LibrarySort.Recents,
            LibrarySort.RecentlyAdded.takeIf { anyDates },
            LibrarySort.Alphabetical,
            LibrarySort.Creator
        )
        LibraryTab.Albums -> listOfNotNull(
            LibrarySort.Recents,
            LibrarySort.RecentlyAdded.takeIf { features.albumsHaveLibraryDates },
            LibrarySort.Alphabetical,
            LibrarySort.Creator
        )
        LibraryTab.Artists -> listOf(LibrarySort.Recents, LibrarySort.Alphabetical)
        LibraryTab.Playlists -> listOfNotNull(
            LibrarySort.Recents,
            LibrarySort.RecentlyAdded.takeIf { features.playlistsHaveLibraryDates },
            LibrarySort.Alphabetical
        )
        LibraryTab.Songs, LibraryTab.Favorites -> emptyList()
    }
}

/**
 * Orders the lists one Library profile has loaded — only those: Spotify's are
 * its newest 200 of each kind, and nothing is fetched to sort. Pure and
 * thread-safe for one call; the ViewModel runs it off the main thread.
 *
 * @param nameOrder compares two sort names: the platform's ICU collator for
 *   the app's language in production ([libraryNameOrder]).
 * @param ignoredArticles leading words Alphabetical and Creator skip, matched
 *   case-insensitively and only when a word follows ([LibraryIgnoredArticles]
 *   on a service that sorts past them).
 */
class LibrarySorter(
    private val nameOrder: Comparator<String>,
    private val recents: LibraryRecents = LibraryRecents.None,
    private val ignoredArticles: List<String> = emptyList()
) {
    fun artists(artists: List<Artist>, sort: LibrarySort): List<Artist> =
        sorted(artists.map(LibraryItem::ArtistItem), sort).map { (it as LibraryItem.ArtistItem).artist }

    fun albums(albums: List<Album>, sort: LibrarySort): List<Album> =
        sorted(albums.map(LibraryItem::AlbumItem), sort).map { (it as LibraryItem.AlbumItem).album }

    fun playlists(playlists: List<Playlist>, sort: LibrarySort): List<Playlist> =
        sorted(playlists.map(LibraryItem::PlaylistItem), sort).map { (it as LibraryItem.PlaylistItem).playlist }

    /**
     * The All view: the three kinds in one order. No service dates its
     * artists, so here an artist takes the date of its newest album in
     * [albums] (by artist id, else by name): Recently added, and Recents'
     * fallback, then mix the kinds instead of trailing every artist after the
     * dated albums and playlists. [albums] is the whole collection, or
     * (Spotify) its newest 200, so an artist's newest album is in it whenever
     * any of theirs is.
     */
    fun all(
        artists: List<Artist>,
        albums: List<Album>,
        playlists: List<Playlist>,
        sort: LibrarySort
    ): List<LibraryItem> = sorted(
        artists.map(LibraryItem::ArtistItem) +
            albums.map(LibraryItem::AlbumItem) +
            playlists.map(LibraryItem::PlaylistItem),
        sort,
        artistAddedAt = artistDatesFrom(albums)
    )

    private class Entry(
        val item: LibraryItem,
        /** Provider order, the last tie-break. */
        val position: Int,
        val name: String,
        val creator: String?,
        val addedAtMs: Long?,
        val lastSeenMs: Long?
    )

    private fun sorted(
        items: List<LibraryItem>,
        sort: LibrarySort,
        artistAddedAt: (Artist) -> Long? = { null }
    ): List<LibraryItem> {
        // A provider that lists one item twice would crash the grid's keys.
        val entries = items.distinctBy(LibraryItem::key).mapIndexed { position, item ->
            entryOf(position, item, artistAddedAt)
        }
        return entries.sortedWith(comparatorFor(sort)).map(Entry::item)
    }

    private fun entryOf(position: Int, item: LibraryItem, artistAddedAt: (Artist) -> Long?): Entry = when (item) {
        is LibraryItem.ArtistItem -> Entry(
            item = item,
            position = position,
            name = sortName(item.artist.name),
            creator = sortName(item.artist.name),
            addedAtMs = artistAddedAt(item.artist),
            lastSeenMs = recents.artists[item.artist.id.rawId]
        )
        is LibraryItem.AlbumItem -> Entry(
            item = item,
            position = position,
            name = sortName(item.album.name),
            creator = item.album.artist?.takeIf(String::isNotBlank)?.let(::sortName),
            addedAtMs = parseLibraryDate(item.album.libraryAddedAt),
            lastSeenMs = recents.albums[item.album.id.rawId]
        )
        is LibraryItem.PlaylistItem -> Entry(
            item = item,
            position = position,
            name = sortName(item.playlist.name),
            creator = item.playlist.owner?.takeIf(String::isNotBlank)?.let(::sortName),
            addedAtMs = parseLibraryDate(item.playlist.libraryAddedAt),
            lastSeenMs = recents.playlists[item.playlist.id.rawId]
        )
    }

    private val alphabetical: Comparator<Entry> =
        Comparator<Entry> { a, b -> nameOrder.compare(a.name, b.name) }
            .thenBy(Entry::position)

    private val recentlyAdded: Comparator<Entry> =
        compareBy<Entry, Long?>(nullsLast(reverseOrder<Long>())) { it.addedAtMs }
            .then(alphabetical)

    private val recentsFirst: Comparator<Entry> =
        compareBy<Entry, Long?>(nullsLast(reverseOrder<Long>())) { it.lastSeenMs }
            .then(recentlyAdded)

    private val byCreator: Comparator<Entry> =
        compareBy<Entry, String?>(nullsLast(nameOrder)) { it.creator }
            .then(alphabetical)

    private fun comparatorFor(sort: LibrarySort): Comparator<Entry> = when (sort) {
        LibrarySort.Recents -> recentsFirst
        LibrarySort.RecentlyAdded -> recentlyAdded
        LibrarySort.Alphabetical -> alphabetical
        LibrarySort.Creator -> byCreator
    }

    private fun sortName(name: String): String = stripLeadingArticle(name.trim(), ignoredArticles)
}

/** Each artist's newest library date among [albums]: by the album's artist id, else by the artist's name. */
internal fun artistDatesFrom(albums: List<Album>): (Artist) -> Long? {
    val byId = HashMap<MediaId, Long>()
    val byName = HashMap<String, Long>()
    albums.forEach { album ->
        val addedAt = parseLibraryDate(album.libraryAddedAt) ?: return@forEach
        album.artistId?.let { byId.merge(it, addedAt, ::maxOf) }
        album.artist?.let(::artistNameKey)?.let { byName.merge(it, addedAt, ::maxOf) }
    }
    if (byId.isEmpty() && byName.isEmpty()) return { null }
    return { artist -> byId[artist.id] ?: artistNameKey(artist.name)?.let(byName::get) }
}

private fun artistNameKey(name: String): String? = name.trim().lowercase(Locale.ROOT).takeIf(String::isNotEmpty)

/**
 * The articles Subsonic servers skip by default (`ignoredArticles`,
 * Navidrome's "The El La Los Las Le Les Os As O A").
 */
val LibraryIgnoredArticles: List<String> = listOf("The", "El", "La", "Los", "Las", "Le", "Les", "Os", "As", "O", "A")

/** [text] without a leading [articles] word ("The Beatles" → "Beatles"); never down to nothing. */
internal fun stripLeadingArticle(text: String, articles: Collection<String>): String {
    for (article in articles) {
        if (article.isEmpty() || text.length <= article.length + 1) continue
        if (!text.regionMatches(0, article, 0, article.length, ignoreCase = true)) continue
        if (!text[article.length].isWhitespace()) continue
        val rest = text.substring(article.length + 1).trimStart()
        if (rest.isNotEmpty()) return rest
    }
    return text
}

/**
 * A provider's library date as epoch ms: an ISO-8601 instant or offset date
 * time (Spotify, Apple Music, most Subsonic servers), a zone-less date time or
 * a bare date (taken as UTC). Null when absent or unreadable.
 */
internal fun parseLibraryDate(raw: String?): Long? {
    val text = raw?.trim()?.takeIf(String::isNotEmpty) ?: return null
    return runCatching { Instant.parse(text).toEpochMilli() }.getOrNull()
        ?: runCatching { OffsetDateTime.parse(text).toInstant().toEpochMilli() }.getOrNull()
        ?: runCatching { LocalDateTime.parse(text).toInstant(ZoneOffset.UTC).toEpochMilli() }.getOrNull()
        ?: runCatching {
            LocalDate.parse(text.take(10)).atStartOfDay().toInstant(ZoneOffset.UTC).toEpochMilli()
        }.getOrNull()
}

/**
 * The platform's ICU collator for [locale] (the app's language), as a name
 * order. A collator isn't thread-safe, so each sort pass takes its own.
 */
fun libraryNameOrder(locale: Locale = Locale.getDefault()): Comparator<String> {
    val collator = android.icu.text.Collator.getInstance(locale)
    return Comparator { a, b -> collator.compare(a, b) }
}
