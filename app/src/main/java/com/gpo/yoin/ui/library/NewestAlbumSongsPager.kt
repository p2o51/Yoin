package com.gpo.yoin.ui.library

import com.gpo.yoin.data.model.Album
import com.gpo.yoin.data.model.MediaId
import com.gpo.yoin.data.model.Track
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CancellationException
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
 * order, as its page lists them (disc by disc; [Track] carries no disc
 * number to sort by, and a track number alone would interleave the discs).
 *
 * Each [next] reads one page of [albumsPerPage] albums and opens them, at
 * most [parallelism] at once, through the album page's own cache
 * ([loadAlbum]), then hands back their songs in album order. A song comes
 * once however many albums or pages hold it, and an album opens once.
 *
 * The server's list can change between pages. A page starts [reread] albums
 * before where the last one ended, and its new albums are the ones after the
 * last album it holds that was already read: up to [reread] albums deleted
 * meanwhile shift the list up without the next album slipping past unread,
 * and albums added meanwhile (newest, so at the top) shift it down; those are
 * left for a refresh to show at the top rather than joining mid-list. A page
 * whose albums hold no new song reads on to the next, so a call that returns
 * nothing has reached the end. An album [loadAlbum] answers with null (the
 * service no longer has it) drops out.
 *
 * A read that fails or is cancelled leaves the pager where it was: the next
 * call reads the same albums again (those already opened come from the
 * cache). An album that fails to open fails the read once; if it fails again
 * on the next read it is left out, so one album the service can't open never
 * holds back every album after it. Its owner makes one call at a time; the
 * pager doesn't guard that.
 */
internal class NewestAlbumSongsPager(
    private val loadAlbums: suspend (offset: Int, size: Int) -> List<Album>,
    private val loadAlbum: suspend (MediaId) -> Album?,
    private val albumsPerPage: Int = ALBUMS_PER_PAGE,
    private val parallelism: Int = ALBUMS_OPENED_AT_ONCE,
    private val reread: Int = ALBUMS_REREAD
) {
    /** How far into the server's list the reads have come. */
    private var albumOffset = 0
    private val openedAlbums = HashSet<MediaId>()
    private val listedSongs = HashSet<MediaId>()

    /**
     * Albums that failed to open on the reads since the last whole one: one
     * that fails again is left out. Only [next] changes it, once its opens
     * have all finished; the opens only read it.
     */
    private val failedAlbums = HashSet<MediaId>()

    /** Every album has been read: [next] has nothing more to give. */
    var reachedEnd: Boolean = false
        private set

    /** The next songs in list order, none already given; empty only at the end. */
    suspend fun next(): List<Track> {
        if (reachedEnd) return emptyList()
        val failedNow = ConcurrentHashMap.newKeySet<MediaId>()
        val songs = try {
            read(failedNow)
        } catch (e: Exception) {
            // Kept across failed reads (a cancelled one adds nothing), so two
            // broken albums on one page don't take turns failing it.
            if (e !is CancellationException) failedAlbums += failedNow
            throw e
        }
        failedAlbums.clear()
        return songs
    }

    private suspend fun read(failedNow: MutableSet<MediaId>): List<Track> {
        var offset = albumOffset
        val albums = HashSet<MediaId>()
        val songIds = HashSet<MediaId>()
        val songs = mutableListOf<Track>()
        var end = false
        while (songs.isEmpty() && !end) {
            val from = (offset - reread).coerceAtLeast(0)
            val size = albumsPerPage + (offset - from)
            val page = loadAlbums(from, size)
            offset = from + page.size
            end = page.size < size
            // An unread album above the last one read was added since.
            val lastRead = page.indexOfLast { album -> album.id in openedAlbums || album.id in albums }
            val unopened = page.drop(lastRead + 1).filter { album -> album.id !in openedAlbums && albums.add(album.id) }
            open(unopened, failedNow).forEach { album ->
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

    /**
     * [albums] opened, in their order. One that fails fails them all and is
     * noted in [failedNow], unless it failed on the last read too: then it is
     * left out, as one the service no longer has (null) is.
     */
    private suspend fun open(albums: List<Album>, failedNow: MutableSet<MediaId>): List<Album> = coroutineScope {
        val permits = Semaphore(parallelism)
        albums
            .map { album ->
                async {
                    permits.withPermit {
                        try {
                            loadAlbum(album.id)
                        } catch (e: Exception) {
                            if (e is CancellationException) throw e
                            if (album.id in failedAlbums) return@withPermit null
                            failedNow += album.id
                            throw e
                        }
                    }
                }
            }
            .awaitAll()
            .filterNotNull()
    }

    companion object {
        /** About a screen of songs or more, in one `getAlbumList2` page. */
        const val ALBUMS_PER_PAGE = 10

        /** Albums opened at once: a page doesn't flood the server. */
        const val ALBUMS_OPENED_AT_ONCE = 3

        /** Albums a page reads again before its start, so deletions in between skip none. */
        const val ALBUMS_REREAD = 5
    }
}
