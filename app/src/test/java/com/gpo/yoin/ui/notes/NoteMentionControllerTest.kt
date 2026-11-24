package com.gpo.yoin.ui.notes

import android.content.Context
import com.gpo.yoin.data.model.MediaId
import com.gpo.yoin.data.notes.BacklinkSource
import com.gpo.yoin.data.notes.MentionAccount
import com.gpo.yoin.data.notes.MentionResolution
import com.gpo.yoin.data.notes.MentionResolver
import com.gpo.yoin.shared.notes.MentionKind
import com.gpo.yoin.shared.notes.NoteMention
import io.mockk.mockk
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NoteMentionControllerTest {

    private val context = mockk<Context>(relaxed = true)
    private val spotifyAlbum = NoteMention(MentionKind.Album, "spotify", "sp-al", "Charm", "Clairo")
    private val ownTrack = NoteMention(MentionKind.Track, "subsonic", "t2", "Andromeda", "Weyes Blood")

    private fun controller(
        scope: TestScope,
        account: () -> MentionAccount? = { MentionAccount("p1", "subsonic") },
        resolver: MentionResolver = MentionResolver { MentionResolution.Unresolved(it) },
        opened: MutableList<NoteDestination> = mutableListOf(),
        albums: Map<MediaId, MediaId> = emptyMap(),
    ): NoteMentionController {
        // Each open comes well after the last (the double-tap guard is tested on its own).
        var clock = 0L
        return NoteMentionController(
            resolver = resolver,
            account = account,
            scope = scope,
            trackAlbum = { albums[it] },
            navigate = { _, destination -> opened += destination },
            now = { clock += 10 * NoteMentionController.OPEN_COOLDOWN_MS; clock },
        )
    }

    @Test
    fun should_resolveOwnProviderMentionsAtOnce_andOthersInTheBackground() = runTest {
        val scope = TestScope(StandardTestDispatcher(testScheduler))
        var asked = 0
        val c = controller(
            scope,
            resolver = MentionResolver { mention ->
                asked++
                MentionResolution.Resolved(mention, MediaId.subsonic("al-charm"), MentionResolution.Via.Name)
            },
        )

        assertTrue(c.isResolved(ownTrack))
        // another provider: plain until the name match lands, then drawn as a mention
        assertFalse(c.isResolved(spotifyAlbum))
        assertFalse(c.isResolved(spotifyAlbum))
        scope.advanceUntilIdle()
        assertTrue(c.isResolved(spotifyAlbum))
        assertEquals(1, asked)
    }

    @Test
    fun should_stayPlain_when_nothingAnswers_andStartOver_onAnotherAccount() = runTest {
        val scope = TestScope(StandardTestDispatcher(testScheduler))
        var account = MentionAccount("p1", "subsonic")
        var answer: (NoteMention) -> MentionResolution = { MentionResolution.Unresolved(it) }
        val c = controller(scope, account = { account }, resolver = MentionResolver { answer(it) })
        // isResolved never writes snapshot state itself (it's read in composition)

        c.isResolved(spotifyAlbum)
        scope.advanceUntilIdle()
        assertFalse(c.isResolved(spotifyAlbum))

        // the Spotify account: its own mentions resolve by id; the cache started over
        account = MentionAccount("p2", "spotify")
        assertTrue(c.isResolved(spotifyAlbum))
        answer = { MentionResolution.Resolved(it, MediaId.spotify("x"), MentionResolution.Via.Name) }
        assertFalse(c.isResolved(ownTrack))
        scope.advanceUntilIdle()
        assertTrue(c.isResolved(ownTrack))
    }

    @Test
    fun should_openAlbumsArtistsAndASongsAlbumOnThatSong() = runTest {
        val scope = TestScope(StandardTestDispatcher(testScheduler))
        val opened = mutableListOf<NoteDestination>()
        val c = controller(
            scope,
            resolver = MentionResolver { MentionResolution.Resolved(it, MediaId(it.provider, it.id), MentionResolution.Via.Id) },
            opened = opened,
            albums = mapOf(MediaId.subsonic("t2") to MediaId.subsonic("al-weyes")),
        )

        listOf(
            ownTrack,
            ownTrack.copy(kind = MentionKind.Album, id = "al-x"),
            ownTrack.copy(kind = MentionKind.Artist, id = "ar-1"),
            ownTrack.copy(id = "unknown-album"),
        ).forEach { mention ->
            c.open(context, mention)
            scope.advanceUntilIdle()
        }

        assertEquals(
            listOf(
                NoteDestination.AlbumScrapbook(MediaId.subsonic("al-weyes"), focusTrack = MediaId.subsonic("t2")),
                NoteDestination.AlbumScrapbook(MediaId.subsonic("al-x")),
                NoteDestination.Artist(MediaId.subsonic("ar-1")),
            ),
            opened,
        )
    }

    @Test
    fun should_landALateAnswerUnderTheAccountItWasAskedFor() = runTest {
        val scope = TestScope(StandardTestDispatcher(testScheduler))
        var account = MentionAccount("p1", "subsonic")
        val c = controller(
            scope,
            account = { account },
            resolver = MentionResolver { MentionResolution.Resolved(it, MediaId.subsonic("al"), MentionResolution.Via.Name) },
        )
        assertFalse(c.isResolved(spotifyAlbum))
        // the account changes before the answer lands: it's p1's, not p2's
        account = MentionAccount("p2", "subsonic")
        scope.advanceUntilIdle()
        // p2 asks for itself (its own answer, later)
        assertFalse(c.isResolved(spotifyAlbum))
        account = MentionAccount("p1", "subsonic")
        assertTrue(c.isResolved(spotifyAlbum))
    }

    @Test
    fun should_openOnce_when_tappedTwice() = runTest {
        val scope = TestScope(StandardTestDispatcher(testScheduler))
        val opened = mutableListOf<NoteDestination>()
        var clock = 10_000L
        val c = NoteMentionController(
            resolver = MentionResolver { MentionResolution.Resolved(it, MediaId(it.provider, it.id), MentionResolution.Via.Id) },
            account = { MentionAccount("p1", "subsonic") },
            scope = scope,
            trackAlbum = { null },
            navigate = { _, d -> opened += d },
            now = { clock },
        )
        val album = ownTrack.copy(kind = MentionKind.Album, id = "al-x")

        c.open(context, album)
        c.open(context, album)
        scope.advanceUntilIdle()
        c.open(context, album) // right after the page opened: the same tap
        scope.advanceUntilIdle()
        assertEquals(1, opened.size)

        clock += NoteMentionController.OPEN_COOLDOWN_MS + 1
        c.open(context, album)
        scope.advanceUntilIdle()
        assertEquals(2, opened.size)
    }

    @Test
    fun should_takeNoTap_when_aMentionDoesNotResolve() = runTest {
        val scope = TestScope(StandardTestDispatcher(testScheduler))
        val opened = mutableListOf<NoteDestination>()
        val c = controller(scope, opened = opened)

        c.open(context, spotifyAlbum)
        scope.advanceUntilIdle()

        assertTrue(opened.isEmpty())
    }

    @Test
    fun should_openWhereABacklinkWasWritten() = runTest {
        val trackAlbum: suspend (MediaId) -> MediaId? = { if (it.rawId == "t9") MediaId.subsonic("al-9") else null }
        val song = BacklinkSource.SongNote("n1", MediaId.subsonic("t1"), "Song", "A", 1_000L, MediaId.subsonic("al-1"))

        assertEquals(
            NoteDestination.AlbumScrapbook(MediaId.subsonic("al-1"), focusTrack = MediaId.subsonic("t1")),
            NoteMentionController.destinationOf(song, trackAlbum),
        )
        assertEquals(
            NoteDestination.AlbumScrapbook(MediaId.subsonic("al-9"), focusTrack = MediaId.subsonic("t9")),
            NoteMentionController.destinationOf(song.copy(trackId = MediaId.subsonic("t9"), albumId = null), trackAlbum),
        )
        assertNull(NoteMentionController.destinationOf(song.copy(albumId = null), trackAlbum))
        assertEquals(
            NoteDestination.AlbumScrapbook(MediaId.subsonic("al-2")),
            NoteMentionController.destinationOf(BacklinkSource.Review(MediaId.subsonic("al-2"), null, null), trackAlbum),
        )
        assertEquals(
            NoteDestination.AlbumScrapbook(MediaId.subsonic("al-3")),
            NoteMentionController.destinationOf(BacklinkSource.AlbumNote("an", MediaId.subsonic("al-3"), "X", "Y"), trackAlbum),
        )
    }

    @Test
    fun should_fetchAMentionsArtworkOnce_andForgetItOnAnotherAccount() = runTest {
        val scope = TestScope(StandardTestDispatcher(testScheduler))
        var account = MentionAccount("p1", "subsonic")
        var fetched = 0
        val c = NoteMentionController(
            resolver = MentionResolver { MentionResolution.Resolved(it, MediaId.subsonic(it.id), MentionResolution.Via.Id) },
            account = { account },
            scope = scope,
            trackAlbum = { null },
            navigate = { _, _ -> },
            artwork = { resolved ->
                fetched++
                "https://covers/${resolved.id.rawId}".takeIf { resolved.mention.kind != MentionKind.Artist }
            },
        )
        val artist = NoteMention(MentionKind.Artist, "subsonic", "ar1", "Weyes Blood")

        assertNull(c.artworkUrl(ownTrack))
        assertNull(c.artworkUrl(ownTrack))
        scope.advanceUntilIdle()
        assertEquals("https://covers/t2", c.artworkUrl(ownTrack))
        assertEquals(1, fetched)

        // none known: asked once, then stays without
        assertNull(c.artworkUrl(artist))
        scope.advanceUntilIdle()
        assertNull(c.artworkUrl(artist))
        assertEquals(2, fetched)

        // another account asks again, under its own key
        account = MentionAccount("p2", "subsonic")
        assertNull(c.artworkUrl(ownTrack))
        scope.advanceUntilIdle()
        assertEquals("https://covers/t2", c.artworkUrl(ownTrack))
        assertEquals(3, fetched)
    }
}
