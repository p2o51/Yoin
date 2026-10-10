package com.gpo.yoin.data.repository

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.gpo.yoin.data.local.ActivityActionType
import com.gpo.yoin.data.local.ActivityEntityType
import com.gpo.yoin.data.local.ActivityEvent
import com.gpo.yoin.data.local.PlayHistory
import com.gpo.yoin.data.local.YoinDatabase
import com.gpo.yoin.data.model.MediaId
import com.gpo.yoin.testutil.MainDispatcherRule
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** The Recents queries against a real (in-memory) Room: what counts, and whose. */
@RunWith(RobolectricTestRunner::class)
class LibraryRecentsRoomTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private lateinit var database: YoinDatabase
    private lateinit var source: RoomLibraryRecentsSource

    @Before
    fun setUp() {
        database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            YoinDatabase::class.java
        )
            .allowMainThreadQueries()
            .build()
        source = RoomLibraryRecentsSource(database.activityEventDao(), database.playHistoryDao())
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun should_countVisitsAndPlaysOfThisProfileOnly_when_readingRecents() = runTest {
        val events = database.activityEventDao()
        events.insert(event(ActivityEntityType.ARTIST, ActivityActionType.VISITED, "artist", at = 10L))
        events.insert(event(ActivityEntityType.ARTIST, ActivityActionType.PLAYED, "artist", at = 40L))
        events.insert(event(ActivityEntityType.ALBUM, ActivityActionType.VISITED, "album", at = 20L))
        events.insert(event(ActivityEntityType.PLAYLIST, ActivityActionType.PLAYED, "list", at = 30L))
        events.insert(event(ActivityEntityType.SONG, ActivityActionType.PLAYED, "song", at = 99L))
        // Another profile, another provider: not this Library's.
        events.insert(
            event(ActivityEntityType.ALBUM, ActivityActionType.VISITED, "album", at = 500L, profileId = "other")
        )
        events.insert(
            event(
                ActivityEntityType.ALBUM,
                ActivityActionType.VISITED,
                "album",
                at = 600L,
                provider = MediaId.PROVIDER_SPOTIFY
            )
        )
        val history = database.playHistoryDao()
        history.insert(play(albumId = "album", at = 25L))
        history.insert(play(albumId = "played-only", at = 5L))
        history.insert(play(albumId = "", at = 700L))

        val recents = source.observe(PROFILE, MediaId.PROVIDER_SUBSONIC).first()

        assertEquals(mapOf("album" to 25L, "played-only" to 5L), recents.albums)
        assertEquals(mapOf("artist" to 40L), recents.artists)
        assertEquals(mapOf("list" to 30L), recents.playlists)
    }

    @Test
    fun should_readNothing_when_newDeviceHasNoRecords() = runTest {
        assertEquals(LibraryRecents.None, source.observe(PROFILE, MediaId.PROVIDER_SUBSONIC).first())
    }

    private fun event(
        type: ActivityEntityType,
        action: ActivityActionType,
        id: String,
        at: Long,
        profileId: String = PROFILE,
        provider: String = MediaId.PROVIDER_SUBSONIC
    ) = ActivityEvent(
        entityType = type.name,
        actionType = action.name,
        entityId = id,
        profileId = profileId,
        provider = provider,
        title = id,
        subtitle = "",
        timestamp = at
    )

    private fun play(albumId: String, at: Long) = PlayHistory(
        songId = "song-$at",
        profileId = PROFILE,
        provider = MediaId.PROVIDER_SUBSONIC,
        title = "Song",
        artist = "Artist",
        album = "Album",
        albumId = albumId,
        coverArtId = null,
        playedAt = at,
        durationMs = 1_000L,
        completedPercent = 1f
    )

    private companion object {
        const val PROFILE = "profile"
    }
}
