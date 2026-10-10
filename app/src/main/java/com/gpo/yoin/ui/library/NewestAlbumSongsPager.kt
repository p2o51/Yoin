package com.gpo.yoin.ui.library

import com.gpo.yoin.data.model.Album
import com.gpo.yoin.data.model.MediaId
import com.gpo.yoin.data.model.Track
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

/**
 * Library's Songs where the service lists no songs of its own
 * ([com.gpo.yoin.data.source.ServiceFeatures.songsFromNewestAlbums],
 * Subsonic): its albums' songs, the most recently added album first
 * (`getAlbumList2` type=newest), each album's songs in the album's own
 * order, as its page lists them (disc by disc).
 *
 * Each [next] reads one page of [albumsPerPage] albums and opens them, at
 * most [parallelism] at once, through the album page's own cache
 * ([loadAlbum]), then hands back their songs in album order. A song comes
 * once however many albums or pages hold it, and an album opens once even
 * when a page boundary shifts it into the next page too. A page whose albums
 * hold no new song reads on to the next, so a call that returns nothing has
 * reached the end.
 *
 * A read that fails or is cancelled leaves the pager where it was: the next
 * call reads the same albums again (those already opened come from the
 * cache). Its owner makes one call at a time; the pager doesn't guard that.
 */
internal class NewestAlbumSongsPager(
    private val loadAlbums: suspend (offset: Int, size: Int) -> List<Album>,
    private val loadAlbum: suspend (MediaId) -> Album?,
    private val albumsPerPage: Int = ALBUMS_PER_PAGE,
    private val parallelism: Int = ALBUMS_OPENED_AT_ONCE
) {
    private var albumOffset = 0
    private val openedAlbums = HashSet<MediaId>()
    private val listedSongs = HashSet<MediaId>()

    /** Every album has been read: [next] has nothing more to give. */
    var reachedEnd: Boolean = false
        private set

    /** The next songs in list order, none already given; empty only at the end. */
    suspend fun next(): List<Track> {
        if (reachedEnd) return emptyList()
        var offset = albumOffset
        val albums = HashSet<MediaId>()
        val songIds = HashSet<MediaId>()
        val songs = mutableListOf<Track>()
        var end = false
        while (songs.isEmpty() && !end) {
            val page = loadAlbums(offset, albumsPerPage)
            offset += page.size
            end = page.size < albumsPerPage
            val unopened = page.filter { album -> album.id !in openedAlbums && albums.add(album.id) }
            open(unopened).forEach { album ->
                album.tracks.forEach { song ->
                    if (song.id !in listedSongs && songIds.add(song.id)) songs += song
                }
            }
        }
        // Only a whole read moves the pager on.
        albumOffset = offset
        openedAlbums += albums
        listedSongs += songIds
        reachedEnd = end
        return songs
    }

    /** [albums] opened in their order; one that fails fails them all. Gone ones drop out. */
    private suspend fun open(albums: List<Album>): List<Album> = coroutineScope {
        val permits = Semaphore(parallelism)
        albums
            .map { album -> async { permits.withPermit { loadAlbum(album.id) } } }
            .awaitAll()
            .filterNotNull()
    }

    companion object {
        /** About a screen of songs or more, in one `getAlbumList2` page. */
        const val ALBUMS_PER_PAGE = 10

        /** Albums opened at once: a page doesn't flood the server. */
        const val ALBUMS_OPENED_AT_ONCE = 3
    }
}
