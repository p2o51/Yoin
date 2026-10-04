package com.gpo.yoin.data.local

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.cash.turbine.test
import com.gpo.yoin.data.model.MediaId
import com.gpo.yoin.testutil.MainDispatcherRule
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class PlayHistoryDaoRediscoverTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private lateinit var database: YoinDatabase
    private lateinit var dao: PlayHistoryDao

    @Before
    fun setUp() {
        database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            YoinDatabase::class.java,
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
    fun should_aggregateOnlyRequestedAlbums_when_idsGiven() = runTest {
        dao.insert(play(albumId = "album-1", playedAt = 100L))
        dao.insert(play(albumId = "album-1", playedAt = 300L))
        dao.insert(play(albumId = "album-2", playedAt = 200L))
        dao.insert(play(albumId = "album-3", playedAt = 50L))

        val rows = dao.getAlbumAggregatesFor(
            profileId = "profile-a",
            provider = MediaId.PROVIDER_SUBSONIC,
            albumIds = listOf("album-1", "album-3", "album-missing"),
        ).sortedBy(AlbumPlayHistoryAggregate::albumId)

        assertEquals(listOf("album-1", "album-3"), rows.map(AlbumPlayHistoryAggregate::albumId))
        val first = rows.first()
        assertEquals(2, first.playCount)
        assertEquals(100L, first.firstPlayedAt)
        assertEquals(300L, first.lastPlayedAt)
        assertEquals("Album album-1", first.albumName)
        assertEquals(1, rows.last().playCount)
    }

    @Test
    fun should_scopeAggregatesByProfileAndProvider() = runTest {
        dao.insert(play(albumId = "album-1", playedAt = 100L))
        dao.insert(play(albumId = "album-1", playedAt = 900L, profileId = "profile-b"))
        dao.insert(play(albumId = "album-1", playedAt = 800L, provider = MediaId.PROVIDER_SPOTIFY))

        val row = dao.getAlbumAggregatesFor(
            profileId = "profile-a",
            provider = MediaId.PROVIDER_SUBSONIC,
            albumIds = listOf("album-1"),
        ).single()

        assertEquals(1, row.playCount)
        assertEquals(100L, row.lastPlayedAt)
        assertEquals(MediaId.PROVIDER_SUBSONIC, row.provider)
    }

    @Test
    fun should_emitNewestRow_when_playInserted() = runTest {
        dao.observeMostRecent("profile-a", MediaId.PROVIDER_SUBSONIC).test {
            assertNull(awaitItem())

            dao.insert(play(albumId = "album-1", playedAt = 100L))
            assertEquals(100L, awaitItem()?.playedAt)

            dao.insert(play(albumId = "album-2", playedAt = 300L))
            val newest = awaitItem()
            assertEquals(300L, newest?.playedAt)
            assertEquals("album-2", newest?.albumId)

            cancelAndIgnoreRemainingEvents()
        }
    }

    private fun play(
        albumId: String,
        playedAt: Long,
        profileId: String = "profile-a",
        provider: String = MediaId.PROVIDER_SUBSONIC,
    ): PlayHistory = PlayHistory(
        songId = "$albumId-track",
        profileId = profileId,
        provider = provider,
        title = "Track",
        artist = "Artist",
        album = "Album $albumId",
        albumId = albumId,
        coverArtId = null,
        playedAt = playedAt,
        durationMs = 180_000L,
        completedPercent = 1f,
    )
}
