package com.gpo.yoin.ui.library

import com.gpo.yoin.data.model.Album
import com.gpo.yoin.data.model.MediaId
import com.gpo.yoin.data.model.Track
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** Library's Songs on Subsonic: the newest albums' songs, a page of albums at a time. */
@OptIn(ExperimentalCoroutinesApi::class)
class NewestAlbumSongsPagerTest {

    @Test
    fun should_listNewestAlbumFirstEachInTrackOrder_when_albumsOpenOutOfOrder() = runTest {
        val library = FakeLibrary(
            albums = listOf(album("newest", "n1", "n2"), album("middle", "m1"), album("oldest", "o1", "o2")),
            // The newest album answers last, the oldest first.
            openDelayMs = mapOf("newest" to 30L, "middle" to 20L, "oldest" to 10L)
        )
        val pager = library.pager(albumsPerPage = 3)

        val songs = pager.next()

        assertEquals(listOf("n1", "n2", "m1", "o1", "o2"), songs.rawIds())
    }

    @Test
    fun should_openAtMostThreeAlbumsAtOnce_when_aPageIsOpened() = runTest {
        val library = FakeLibrary(
            albums = (1..10).map { album("al$it", "s$it") },
            openDelayMs = (1..10).associate { "al$it" to 10L }
        )

        library.pager(albumsPerPage = 10).next()

        assertEquals(3, library.mostOpenAtOnce)
        assertEquals(10, library.opened.size)
    }

    @Test
    fun should_readThePageAfterTheLast_when_calledAgain() = runTest {
        val library = FakeLibrary(albums = (1..25).map { album("al$it", "s$it") })
        val pager = library.pager(albumsPerPage = 10)

        val first = pager.next()
        assertFalse(pager.reachedEnd)
        val second = pager.next()
        assertFalse(pager.reachedEnd)
        val third = pager.next()

        assertEquals((1..10).map { "s$it" }, first.rawIds())
        assertEquals((11..20).map { "s$it" }, second.rawIds())
        assertEquals((21..25).map { "s$it" }, third.rawIds())
        assertTrue(pager.reachedEnd)
        assertEquals(listOf(0 to 10, 10 to 10, 20 to 10), library.pageReads)
        // Past the end: nothing, and no request.
        assertEquals(emptyList<Track>(), pager.next())
        assertEquals(3, library.pageReads.size)
    }

    @Test
    fun should_listEachSongOnce_when_albumsOrPagesRepeatIt() = runTest {
        val library = FakeLibrary(
            albums = listOf(
                album("a", "s1", "s2"),
                // A compilation holding a song already listed.
                album("b", "s2", "s3"),
                album("c", "s4"),
                album("d", "s5")
            )
        )
        val pager = library.pager(albumsPerPage = 2)
        val first = pager.next()
        // An album added on the server between pages shifts "b" into the next page.
        library.albums = listOf(album("new", "s0")) + library.albums

        val second = pager.next()

        assertEquals(listOf("s1", "s2", "s3"), first.rawIds())
        assertEquals(listOf("s4"), second.rawIds())
        // "b" was opened once.
        assertEquals(1, library.opened.count { it == "b" })
    }

    @Test
    fun should_readOnToTheNextPage_when_aPageHoldsNoNewSong() = runTest {
        val library = FakeLibrary(
            albums = listOf(album("a", "s1"), album("b", "s2"), album("empty"), album("gone"), album("c", "s3")),
            missing = setOf("gone")
        )
        val pager = library.pager(albumsPerPage = 2)

        assertEquals(listOf("s1", "s2"), pager.next().rawIds())
        // "empty" holds no song and "gone" no longer opens: the same call reads on to "c".
        assertEquals(listOf("s3"), pager.next().rawIds())
        assertEquals(listOf(0 to 2, 2 to 2, 4 to 2), library.pageReads)
        assertTrue(pager.reachedEnd)
    }

    @Test
    fun should_readTheSamePageAgain_when_aReadFailed() = runTest {
        val library = FakeLibrary(albums = (1..4).map { album("al$it", "s$it") })
        val pager = library.pager(albumsPerPage = 2)
        pager.next()
        library.failing = setOf("al4")

        try {
            pager.next()
            fail("The page should fail with its album")
        } catch (expected: IllegalStateException) {
            // The whole page fails: none of its songs are given.
        }
        library.failing = emptySet()
        val retried = pager.next()

        assertEquals(listOf("s3", "s4"), retried.rawIds())
        assertEquals(listOf(0 to 2, 2 to 2, 2 to 2), library.pageReads)
    }

    @Test
    fun should_stayWhereItWas_when_aReadIsCancelled() = runTest {
        val gate = CompletableDeferred<Unit>()
        val library = FakeLibrary(albums = (1..4).map { album("al$it", "s$it") })
        val pager = library.pager(albumsPerPage = 2)
        pager.next()
        library.gate = gate

        val read = async { pager.next() }
        runCurrent()
        read.cancel()
        advanceUntilIdle()
        library.gate = null

        assertEquals(listOf("s3", "s4"), pager.next().rawIds())
        assertEquals(listOf(0 to 2, 2 to 2, 2 to 2), library.pageReads)
    }

    @Test
    fun should_reachTheEndAtOnce_when_theLibraryHasNoAlbums() = runTest {
        val library = FakeLibrary(albums = emptyList())
        val pager = library.pager()

        assertEquals(emptyList<Track>(), pager.next())
        assertTrue(pager.reachedEnd)
    }

    private class FakeLibrary(
        var albums: List<Album>,
        private val openDelayMs: Map<String, Long> = emptyMap(),
        private val missing: Set<String> = emptySet()
    ) {
        var failing: Set<String> = emptySet()
        var gate: CompletableDeferred<Unit>? = null
        val pageReads = mutableListOf<Pair<Int, Int>>()
        val opened = mutableListOf<String>()
        var mostOpenAtOnce = 0
        private var openNow = 0

        fun pager(albumsPerPage: Int = 10) = NewestAlbumSongsPager(
            loadAlbums = { offset, size ->
                pageReads += offset to size
                albums.drop(offset).take(size)
            },
            loadAlbum = { id -> open(id.rawId) },
            albumsPerPage = albumsPerPage
        )

        private suspend fun open(rawId: String): Album? {
            openNow += 1
            mostOpenAtOnce = maxOf(mostOpenAtOnce, openNow)
            try {
                gate?.await()
                openDelayMs[rawId]?.let { delay(it) }
                check(rawId !in failing) { "album $rawId failed" }
                opened += rawId
                return albums.firstOrNull { it.id.rawId == rawId }?.takeUnless { rawId in missing }
            } finally {
                openNow -= 1
            }
        }
    }

    private fun album(rawId: String, vararg songs: String) = Album(
        id = MediaId.subsonic(rawId),
        name = rawId,
        artist = null,
        artistId = null,
        coverArt = null,
        songCount = songs.size,
        durationSec = null,
        year = null,
        genre = null,
        tracks = songs.map(::song)
    )

    private fun song(rawId: String) = Track(
        id = MediaId.subsonic(rawId),
        title = rawId,
        artist = null,
        artistId = null,
        album = null,
        albumId = null,
        coverArt = null,
        durationSec = null,
        trackNumber = null,
        year = null,
        genre = null,
        userRating = null
    )

    private fun List<Track>.rawIds(): List<String> = map { it.id.rawId }
}
