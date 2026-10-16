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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
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

    private suspend fun note(trackId: String, at: Long, title: String = "Unknown track", artist: String = "") =
        database.songNoteDao().insert(
            SongNote(
                id = "note-$trackId",
                profileId = "profile-a",
                trackId = trackId,
                provider = MediaId.PROVIDER_SPOTIFY,
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

    @Test
    fun should_doNothing_when_theAccountInUseIsNotSpotify() = runTest {
        note("t1", at = 1L)
        every { source.id } returns MediaId.PROVIDER_SUBSONIC

        assertEquals(0, enricher().runPass())
    }
}
