package com.gpo.yoin.data.local

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.gpo.yoin.data.model.MediaId
import com.gpo.yoin.testutil.MainDispatcherRule
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class PlayHistoryDaoArtistTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private lateinit var database: YoinDatabase
    private lateinit var dao: PlayHistoryDao

    @Before
    fun setUp() {
        database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            YoinDatabase::class.java
        )
            .allowMainThreadQueries()
            .build()
        dao = database.playHistoryDao()
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun should_countReleasePlaysAndExactNamePlays_when_readingArtistPlayStats() = runTest {
        dao.insert(play(songId = "s1", albumId = "album-1"))
        dao.insert(play(songId = "s1", albumId = "album-1"))
        // A feature outside the fetched discography: matched by the exact name.
        dao.insert(play(songId = "s2", albumId = "other-album", artist = "Hazel Arden"))
        dao.insert(play(songId = "s3", albumId = "other-album", artist = "Someone Else"))
        dao.insert(play(songId = "s1", albumId = "album-1", profileId = "profile-b"))
        dao.insert(play(songId = "s1", albumId = "album-1", provider = MediaId.PROVIDER_SPOTIFY))

        val stats = dao.getArtistPlayStats(
            profileId = "profile-a",
            provider = MediaId.PROVIDER_SUBSONIC,
            albumIds = listOf("album-1"),
            artistName = "Hazel Arden"
        )

        assertEquals(3, stats.playCount)
    }

    @Test
    fun should_reportZeroPlays_when_artistNeverPlayed() = runTest {
        dao.insert(play(songId = "s3", albumId = "other-album", artist = "Someone Else"))

        val stats = dao.getArtistPlayStats(
            profileId = "profile-a",
            provider = MediaId.PROVIDER_SUBSONIC,
            albumIds = listOf("album-1"),
            artistName = "Hazel Arden"
        )

        assertEquals(0, stats.playCount)
    }

    private fun play(
        songId: String,
        albumId: String,
        artist: String = "Hazel Arden & Friend",
        profileId: String = "profile-a",
        provider: String = MediaId.PROVIDER_SUBSONIC
    ): PlayHistory = PlayHistory(
        songId = songId,
        profileId = profileId,
        provider = provider,
        title = "Track $songId",
        artist = artist,
        album = "Album $albumId",
        albumId = albumId,
        coverArtId = null,
        playedAt = 100L,
        durationMs = 180_000L,
        completedPercent = 1f
    )
}
