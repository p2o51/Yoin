package com.gpo.yoin.data.album

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.gpo.yoin.data.local.SongNote
import com.gpo.yoin.data.local.YoinDatabase
import com.gpo.yoin.data.model.MediaId
import com.gpo.yoin.data.model.Track
import com.gpo.yoin.data.source.MusicLibrary
import com.gpo.yoin.data.source.MusicSource
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import java.io.IOException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class TrackAlbumLinkEnricherTest {

    private lateinit var database: YoinDatabase
    private val library = mockk<MusicLibrary>()
    private val source = mockk<MusicSource>().also {
        every { it.id } returns MediaId.PROVIDER_SPOTIFY
        every { it.library() } returns library
    }
    private val activeSource = MutableStateFlow<MusicSource?>(source)
    private val activeProfile = MutableStateFlow<String?>("profile-a")

    @Before
    fun setUp() {
        database = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), YoinDatabase::class.java)
            .allowMainThreadQueries()
            .build()
    }

    @After
    fun tearDown() {
        database.close()
    }

    private fun enricher() = TrackAlbumLinkEnricher(
        links = database.trackAlbumLinkDao(),
        notes = database.songNoteDao(),
        activeSource = activeSource,
        activeProfileId = activeProfile,
        scope = TestScope(),
        clock = { 7L },
        spacingMs = 0L,
    )

    private suspend fun note(
        trackId: String,
        at: Long,
        title: String = "Unknown track",
        artist: String = "",
        provider: String = MediaId.PROVIDER_SPOTIFY,
    ) =
        database.songNoteDao().insert(
            SongNote(
                id = "note-$trackId",
                profileId = "profile-a",
                trackId = trackId,
                provider = provider,
                content = "kept",
                createdAt = at,
                updatedAt = at,
                title = title,
                artist = artist,
            ),
        )

    private fun track(id: String, albumId: String) = Track(
        id = MediaId.spotify(id),
        title = "Song $id",
        artist = "Artist $id",
        artistId = null,
        album = "Album $albumId",
        albumId = MediaId.spotify(albumId),
        coverArt = null,
        durationSec = null,
        trackNumber = null,
        year = null,
        genre = null,
        userRating = null,
        isStarred = false,
    )

    @Test
    fun should_linkEachTrackToItsAlbum_when_notesHaveNone() = runTest {
        note("t1", at = 1L)
        note("t2", at = 2L, title = "Known", artist = "Someone")
        note("gone", at = 3L)
        coEvery { library.getTrack(MediaId.spotify("t1")) } returns track("t1", "a1")
        coEvery { library.getTrack(MediaId.spotify("t2")) } returns track("t2", "a2")
        coEvery { library.getTrack(MediaId.spotify("gone")) } returns null

        assertEquals(3, enricher().runPass())

        val links = database.trackAlbumLinkDao()
        assertEquals("a1", links.get("profile-a", MediaId.PROVIDER_SPOTIFY, "t1")?.albumId)
        assertEquals("a2", links.get("profile-a", MediaId.PROVIDER_SPOTIFY, "t2")?.albumId)
        // A track that is gone is asked once: a blank album keeps it out of the queue.
        assertEquals("", links.get("profile-a", MediaId.PROVIDER_SPOTIFY, "gone")?.albumId)
        assertEquals(0, enricher().runPass())
        // The note imported without a song row is named now; one that had its name keeps it.
        val notes = database.songNoteDao().getForTracks(listOf("t1", "t2"), MediaId.PROVIDER_SPOTIFY, "profile-a")
        assertEquals("Song t1", notes.single { it.trackId == "t1" }.title)
        assertEquals("Known", notes.single { it.trackId == "t2" }.title)
    }

    @Test
    fun should_stopAndResumeLater_when_aLookupFails() = runTest {
        note("t1", at = 1L)
        note("t2", at = 2L)
        coEvery { library.getTrack(MediaId.spotify("t1")) } returns track("t1", "a1")
        coEvery { library.getTrack(MediaId.spotify("t2")) } throws IOException("offline")

        assertEquals(1, enricher().runPass())
        assertEquals(null, database.trackAlbumLinkDao().get("profile-a", MediaId.PROVIDER_SPOTIFY, "t2"))

        coEvery { library.getTrack(MediaId.spotify("t2")) } returns track("t2", "a2")
        assertEquals(1, enricher().runPass())
        assertEquals("a2", database.trackAlbumLinkDao().get("profile-a", MediaId.PROVIDER_SPOTIFY, "t2")?.albumId)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun should_holdAWaiterBack_until_thePassIsOver() = runTest {
        // The memory ledger asks the same service about the same songs: it waits for the pass to end.
        note("t1", at = 1L)
        val answer = CompletableDeferred<Track?>()
        coEvery { library.getTrack(MediaId.spotify("t1")) } coAnswers { answer.await() }
        val enricher = TrackAlbumLinkEnricher(
            links = database.trackAlbumLinkDao(),
            notes = database.songNoteDao(),
            activeSource = activeSource,
            activeProfileId = activeProfile,
            scope = backgroundScope,
            clock = { 7L },
            spacingMs = 0L,
        )
        // No pass running: nothing to wait for.
        enricher.awaitIdle()

        enricher.kick()
        val waiter = async { enricher.awaitIdle() }
        runCurrent()
        assertTrue(waiter.isActive)

        answer.complete(track("t1", "a1"))
        waiter.await()
        // The waiter went on only after the pass stored what it learned.
        assertEquals("a1", database.trackAlbumLinkDao().get("profile-a", MediaId.PROVIDER_SPOTIFY, "t1")?.albumId)
    }

    @Test
    fun should_doNothing_when_theAccountInUseHasNoSingleTrackLookup() = runTest {
        note("t1", at = 1L, provider = MediaId.PROVIDER_APPLE_MUSIC)
        every { source.id } returns MediaId.PROVIDER_APPLE_MUSIC

        assertEquals(0, enricher().runPass())
        assertEquals(null, database.trackAlbumLinkDao().get("profile-a", MediaId.PROVIDER_APPLE_MUSIC, "t1"))
    }

    @Test
    fun should_linkSubsonicSongs_andPassOneTheServerNoLongerHas() = runTest {
        // A score pulled from the server for a song never played here; one of them was deleted since.
        note("gone", at = 1L, provider = MediaId.PROVIDER_SUBSONIC)
        note("s2", at = 2L, provider = MediaId.PROVIDER_SUBSONIC)
        every { source.id } returns MediaId.PROVIDER_SUBSONIC
        coEvery { library.getTrack(MediaId(MediaId.PROVIDER_SUBSONIC, "gone")) } returns null
        coEvery { library.getTrack(MediaId(MediaId.PROVIDER_SUBSONIC, "s2")) } returns
            track("s2", "al2").copy(albumId = MediaId(MediaId.PROVIDER_SUBSONIC, "al2"))

        assertEquals(2, enricher().runPass())

        val links = database.trackAlbumLinkDao()
        // The missing song is asked once and doesn't hold up the ones after it.
        assertEquals("", links.get("profile-a", MediaId.PROVIDER_SUBSONIC, "gone")?.albumId)
        assertEquals("al2", links.get("profile-a", MediaId.PROVIDER_SUBSONIC, "s2")?.albumId)
        assertEquals(0, enricher().runPass())
    }
}
