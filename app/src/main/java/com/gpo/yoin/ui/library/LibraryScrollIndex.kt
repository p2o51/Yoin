package com.gpo.yoin.ui.library

import com.gpo.yoin.data.model.Album
import com.gpo.yoin.data.model.Artist
import com.gpo.yoin.data.model.Playlist
import com.gpo.yoin.ui.component.FastScrollSection
import java.util.Locale

/**
 * The alphabet and the calendar Library's fast scroller cuts its views by
 * ([LibraryIndex]). An interface only so JVM tests, where android.icu is a
 * stub, can stand in their own.
 */
interface LibraryScrollIndex {
    /** [items] in alphabet order, a section per letter ([LibraryIndex.alphabetical]). */
    fun <T> alphabetical(items: List<T>, name: (T) -> String, ignoredArticles: List<String>): LibraryIndex.Sorted<T>

    /** Sections over a list in date order, one date per item ([LibraryIndex.timeline]). */
    fun timeline(datesMs: List<Long?>): List<FastScrollSection>

    companion object {
        /** The platform's ICU in the app's language: the process locale, as [libraryNameOrder] reads it. */
        fun icu(locale: Locale = Locale.getDefault()): LibraryScrollIndex = IcuLibraryScrollIndex(locale)
    }
}

private class IcuLibraryScrollIndex(private val locale: Locale) : LibraryScrollIndex {
    override fun <T> alphabetical(
        items: List<T>,
        name: (T) -> String,
        ignoredArticles: List<String>
    ): LibraryIndex.Sorted<T> = LibraryIndex.alphabetical(
        items = items,
        name = name,
        ignoredArticles = ignoredArticles,
        locale = locale
    )

    override fun timeline(datesMs: List<Long?>): List<FastScrollSection> = LibraryIndex.timeline(datesMs, locale)
}

/**
 * A Library view in its fast-scroller order, with the sections its sort
 * gives the handle's ticks (U2). It takes [LibrarySorter]'s output and
 * keeps it, except where the ticks are letters:
 *
 * - **Alphabetical**: the name's letter. The view takes [LibraryIndex]'s
 *   order, which buckets and orders with one collator, so every letter is
 *   one run (digits and symbols under "#" at the end; Han by pinyin on
 *   API 29+). Names that tie keep the sorter's order.
 * - **Creator**: the creator's letter (an album's artist, a playlist's
 *   owner, an artist itself), in the same way; what has no creator trails
 *   under "#", as the sorter puts it last. Letters rather than the handle
 *   alone: the order is alphabetical by creator, so the creator's initial is
 *   the one index that matches what the grid shows — "R" lands on
 *   Radiohead's albums — where the title's initial would be scattered.
 * - **Recently added**: the timeline of the dates the order follows (years,
 *   or months over a short span); none when it would say nothing (most of
 *   the list in one segment, or too much of it undated).
 * - **Recents**: none, the handle alone. When something was last opened is
 *   not a thing anyone scans for.
 *
 * Playlists show no scroller, but take the same alphabet order, so A–Z
 * reads the same in every view.
 */
internal class LibraryScrollIndexer(
    private val index: LibraryScrollIndex,
    private val ignoredArticles: List<String> = emptyList()
) {
    fun artists(sorted: List<Artist>, sort: LibrarySort): LibraryIndex.Sorted<Artist> = indexed(
        sorted = sorted,
        sort = sort,
        name = Artist::name,
        creator = Artist::name,
        // No service dates its artists.
        addedAtMs = { null }
    )

    fun albums(sorted: List<Album>, sort: LibrarySort): LibraryIndex.Sorted<Album> = indexed(
        sorted = sorted,
        sort = sort,
        name = Album::name,
        creator = Album::artist,
        addedAtMs = { parseLibraryDate(it.libraryAddedAt) }
    )

    fun playlists(sorted: List<Playlist>, sort: LibrarySort): LibraryIndex.Sorted<Playlist> = indexed(
        sorted = sorted,
        sort = sort,
        name = Playlist::name,
        creator = Playlist::owner,
        addedAtMs = { parseLibraryDate(it.libraryAddedAt) }
    )

    /**
     * All, with [albums] the list [LibrarySorter.all] was given: an artist's
     * date is its newest album's there, as the sorter dated it.
     */
    fun all(sorted: List<LibraryItem>, sort: LibrarySort, albums: List<Album>): LibraryIndex.Sorted<LibraryItem> {
        val artistAddedAt: (Artist) -> Long? = if (sort == LibrarySort.RecentlyAdded) {
            artistDatesFrom(albums)
        } else {
            { null }
        }
        return indexed(
            sorted = sorted,
            sort = sort,
            name = LibraryItem::sortName,
            creator = LibraryItem::creator,
            addedAtMs = { item ->
                when (item) {
                    is LibraryItem.ArtistItem -> artistAddedAt(item.artist)
                    is LibraryItem.AlbumItem -> parseLibraryDate(item.album.libraryAddedAt)
                    is LibraryItem.PlaylistItem -> parseLibraryDate(item.playlist.libraryAddedAt)
                }
            }
        )
    }

    private fun <T> indexed(
        sorted: List<T>,
        sort: LibrarySort,
        name: (T) -> String,
        creator: (T) -> String?,
        addedAtMs: (T) -> Long?
    ): LibraryIndex.Sorted<T> = when (sort) {
        LibrarySort.Recents -> LibraryIndex.Sorted(sorted, emptyList())
        LibrarySort.RecentlyAdded -> LibraryIndex.Sorted(sorted, index.timeline(sorted.map(addedAtMs)))
        LibrarySort.Alphabetical -> index.alphabetical(sorted, name, ignoredArticles)
        LibrarySort.Creator -> byCreator(sorted, creator)
    }

    private fun <T> byCreator(sorted: List<T>, creator: (T) -> String?): LibraryIndex.Sorted<T> {
        val (named, unnamed) = sorted.partition { !creator(it).isNullOrBlank() }
        val indexed = index.alphabetical(named, { creator(it).orEmpty() }, ignoredArticles)
        if (unnamed.isEmpty()) return indexed
        val sections = if (indexed.sections.lastOrNull()?.label == LibraryIndex.NUMBER_LABEL) {
            indexed.sections
        } else {
            indexed.sections + FastScrollSection(LibraryIndex.NUMBER_LABEL, startIndex = indexed.items.size)
        }
        return LibraryIndex.Sorted(indexed.items + unnamed, sections)
    }
}

private val LibraryItem.sortName: String
    get() = when (this) {
        is LibraryItem.ArtistItem -> artist.name
        is LibraryItem.AlbumItem -> album.name
        is LibraryItem.PlaylistItem -> playlist.name
    }

/** What [LibrarySort.Creator] orders by: an artist is its own. */
private val LibraryItem.creator: String?
    get() = when (this) {
        is LibraryItem.ArtistItem -> artist.name
        is LibraryItem.AlbumItem -> album.artist
        is LibraryItem.PlaylistItem -> playlist.owner
    }
