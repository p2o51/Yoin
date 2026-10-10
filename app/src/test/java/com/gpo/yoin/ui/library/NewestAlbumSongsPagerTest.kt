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
        // Each page after the first lists the five albums before it again...
        assertEquals(listOf(0 to 10, 5 to 15, 15 to 15), library.pageReads)
        // ...but opens each album once.
        assertEquals((1..25).map { "al$it" }, library.opened)
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
        val pager = library.pager(albumsPerPage = 2, reread = 0)
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
    fun should_readTheAlbumsAfterTheLastRead_when_albumsAreDeletedBetweenPages() = runTest {
        val library = FakeLibrary(albums = (1..30).map { album("al$it", "s$it") })
        val pager = library.pager(albumsPerPage = 10)
        pager.next()
        // Two albums the first page listed are deleted on the server: the rest shift up by two.
        library.albums = library.albums.filterNot { it.id.rawId == "al4" || it.id.rawId == "al5" }

        val second = pager.next()
        val rest = mutableListOf<Track>()
        while (!pager.reachedEnd) rest += pager.next()

        // "al11" and "al12" now sit where the first page ended; they are not skipped.
        assertEquals((11..22).map { "s$it" }, second.rawIds())
        assertEquals((23..30).map { "s$it" }, rest.rawIds())
    }

    @Test
    fun should_leaveNewAlbumsForTheTop_when_albumsAreAddedBetweenPages() = runTest {
        val library = FakeLibrary(albums = (1..20).map { album("al$it", "s$it") })
        val pager = library.pager(albumsPerPage = 10)
        val first = pager.next()
        // Seven albums added on the server: newest, so at the top, reaching into the re-read albums.
        library.albums = (1..7).map { album("new$it", "n$it") } + library.albums

        val later = mutableListOf<Track>()
        while (!pager.reachedEnd) later += pager.next()

        // The list reads on from "al11"; the new albums come with a refresh, at the top.
        assertEquals((1..10).map { "s$it" }, first.rawIds())
        assertEquals((11..20).map { "s$it" }, later.rawIds())
        assertTrue(library.opened.none { it.startsWith("new") })
    }

    @Test
    fun should_readOnToTheNextPage_when_aPageHoldsNoNewSong() = runTest {
        val library = FakeLibrary(
            albums = listOf(album("a", "s1"), album("b", "s2"), album("empty"), album("gone"), album("c", "s3")),
            missing = setOf("gone")
        )
        val pager = library.pager(albumsPerPage = 2, reread = 0)

        assertEquals(listOf("s1", "s2"), pager.next().rawIds())
        // "empty" holds no song and "gone" no longer opens: the same call reads on to "c".
        assertEquals(listOf("s3"), pager.next().rawIds())
        assertEquals(listOf(0 to 2, 2 to 2, 4 to 2), library.pageReads)
        assertTrue(pager.reachedEnd)
    }

    @Test
    fun should_readTheSamePageAgain_when_aReadFailed() = runTest {
        val library = FakeLibrary(albums = (1..4).map { album("al$it", "s$it") })
        val pager = library.pager(albumsPerPage = 2, reread = 0)
        pager.next()
        library.failing = setOf("al4")

        assertReadFails(pager)
        library.failing = emptySet()
        val retried = pager.next()

        // The whole page failed, then came whole: an album that opens on the retry is kept.
        assertEquals(listOf("s3", "s4"), retried.rawIds())
        assertEquals(listOf(0 to 2, 2 to 2, 2 to 2), library.pageReads)
    }

    @Test
    fun should_leaveAnAlbumOut_when_itFailsAgainOnTheNextRead() = runTest {
        val library = FakeLibrary(albums = (1..4).map { album("al$it", "s$it") })
        // "al3" never opens, however often it is asked for.
        library.failing = setOf("al3")
        val pager = library.pager(albumsPerPage = 2)
        assertEquals(listOf("s1", "s2"), pager.next().rawIds())

        assertReadFails(pager)
        val past = pager.next()

        // The second failure leaves "al3" out; "al4" and what follows it are no longer held back.
        assertEquals(listOf("s4"), past.rawIds())
        assertEquals(emptyList<Track>(), pager.next())
        assertTrue(pager.reachedEnd)
        // Once left out, it isn't asked for again.
        assertEquals(2, library.attempts.count { it == "al3" })
    }

    @Test
    fun should_getPastEveryBrokenAlbum_when_onePageHoldsSeveral() = runTest {
        val library = FakeLibrary(
            albums = listOf(album("al1", "s1"), album("broken1", "b1"), album("broken2", "b2"), album("al2", "s2"))
        )
        library.failing = setOf("broken1", "broken2")
        // One at a time: the first broken album fails the read before the second is asked for.
        val pager = library.pager(albumsPerPage = 4, parallelism = 1)

        // "broken1" fails the first read; left out on the second, "broken2" fails that one.
        assertReadFails(pager)
        assertReadFails(pager)
        // Both are remembered, so they don't take turns failing every read after.
        val songs = pager.next()

        assertEquals(listOf("s1", "s2"), songs.rawIds())
    }

    @Test
    fun should_stayWhereItWas_when_aReadIsCancelled() = runTest {
        val gate = CompletableDeferred<Unit>()
        val library = FakeLibrary(albums = (1..4).map { album("al$it", "s$it") })
        val pager = library.pager(albumsPerPage = 2, reread = 0)
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

    private suspend fun assertReadFails(pager: NewestAlbumSongsPager) {
        try {
            pager.next()
            fail("The read should fail with its album")
        } catch (expected: IllegalStateException) {
            // The whole page fails: none of its songs are given.
        }
    }

    private class FakeLibrary(
        var albums: List<Album>,
        private val openDelayMs: Map<String, Long> = emptyMap(),
        private val missing: Set<String> = emptySet()
    ) {
        var failing: Set<String> = emptySet()
        var gate: CompletableDeferred<Unit>? = null
        val pageReads = mutableListOf<Pair<Int, Int>>()

        /** Every album asked for, whether it opened or not. */
        val attempts = mutableListOf<String>()
        val opened = mutableListOf<String>()
        var mostOpenAtOnce = 0
        private var openNow = 0

        fun pager(
            albumsPerPage: Int = 10,
            parallelism: Int = NewestAlbumSongsPager.ALBUMS_OPENED_AT_ONCE,
            reread: Int = NewestAlbumSongsPager.ALBUMS_REREAD
        ) = NewestAlbumSongsPager(
            loadAlbums = { offset, size ->
                pageReads += offset to size
                albums.drop(offset).take(size)
            },
            loadAlbum = { id -> open(id.rawId) },
            albumsPerPage = albumsPerPage,
            parallelism = parallelism,
            reread = reread
        )

        private suspend fun open(rawId: String): Album? {
            attempts += rawId
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
