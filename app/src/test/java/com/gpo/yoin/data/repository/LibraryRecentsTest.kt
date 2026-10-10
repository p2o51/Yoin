package com.gpo.yoin.data.repository

import com.gpo.yoin.data.local.ActivityEntityLastSeen
import com.gpo.yoin.data.local.ActivityEventDao
import com.gpo.yoin.data.local.AlbumLastPlayed
import com.gpo.yoin.data.local.PlayHistoryDao
import com.gpo.yoin.data.model.MediaId
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

/** Library's Recents come from Yoin's own visits and plays, one latest time per entity. */
class LibraryRecentsTest {

    @Test
    fun should_keepLatestTimePerEntity_when_visitsPlaysAndHistoryOverlap() {
        val recents = libraryRecentsOf(
            provider = MediaId.PROVIDER_SPOTIFY,
            seen = listOf(
                ActivityEntityLastSeen("ALBUM", "album1", lastAt = 100L),
                // A legacy row stored the whole MediaId: the same album.
                ActivityEntityLastSeen("ALBUM", "spotify:album1", lastAt = 150L),
                ActivityEntityLastSeen("ARTIST", "artist1", lastAt = 300L),
                ActivityEntityLastSeen("PLAYLIST", "spotify:list1", lastAt = 250L),
                // Songs are no Library cell.
                ActivityEntityLastSeen("SONG", "song1", lastAt = 999L)
            ),
            played = listOf(
                AlbumLastPlayed("album1", lastPlayedAt = 120L),
                AlbumLastPlayed("album2", lastPlayedAt = 50L)
            )
        )

        assertEquals(mapOf("album1" to 150L, "album2" to 50L), recents.albums)
        assertEquals(mapOf("artist1" to 300L), recents.artists)
        assertEquals(mapOf("list1" to 250L), recents.playlists)
    }

    @Test
    fun should_keepAppleLibraryIdsWhole_when_rawIdHoldsAColon() {
        val recents = libraryRecentsOf(
            provider = MediaId.PROVIDER_APPLE_MUSIC,
            seen = listOf(ActivityEntityLastSeen("ALBUM", "library:l.abc", lastAt = 10L)),
            played = emptyList()
        )

        assertEquals(mapOf("library:l.abc" to 10L), recents.albums)
    }

    @Test
    fun should_readOneProfileAndProvider_when_observingRoom() = runTest {
        val events = mockk<ActivityEventDao> {
            every { observeEntityLastSeen("profile", MediaId.PROVIDER_SUBSONIC) } returns
                flowOf(listOf(ActivityEntityLastSeen("ARTIST", "a", lastAt = 7L)))
        }
        val history = mockk<PlayHistoryDao> {
            every { observeAlbumLastPlayed("profile", MediaId.PROVIDER_SUBSONIC) } returns
                flowOf(listOf(AlbumLastPlayed("b", lastPlayedAt = 9L)))
        }

        val recents = RoomLibraryRecentsSource(events, history)
            .observe("profile", MediaId.PROVIDER_SUBSONIC)
            .first()

        assertEquals(LibraryRecents(albums = mapOf("b" to 9L), artists = mapOf("a" to 7L)), recents)
        verify(exactly = 1) { events.observeEntityLastSeen("profile", MediaId.PROVIDER_SUBSONIC) }
        verify(exactly = 1) { history.observeAlbumLastPlayed("profile", MediaId.PROVIDER_SUBSONIC) }
    }
}
