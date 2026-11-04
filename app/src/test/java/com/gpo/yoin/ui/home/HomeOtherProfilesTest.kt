package com.gpo.yoin.ui.home

import com.gpo.yoin.data.local.OtherProfilePlayAggregate
import com.gpo.yoin.data.model.MediaId
import com.gpo.yoin.data.profile.ProviderKind
import com.gpo.yoin.ui.settings.ProfileCard
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** On Your Other Accounts' shelf, from the play log's most-played rows. */
class HomeOtherProfilesTest {

    private val spotify = ProfileCard(
        id = "p-spotify",
        displayName = "Spotify",
        subtitle = null,
        provider = ProviderKind.SPOTIFY,
        isActive = false,
        title = "51 on Spotify",
    )
    private val navidrome = ProfileCard(
        id = "p-navi",
        displayName = "Navidrome",
        subtitle = "music.example",
        provider = ProviderKind.SUBSONIC,
        isActive = false,
        title = "fiftyone",
    )
    private val active = ProfileCard(
        id = "p-apple",
        displayName = "Apple Music",
        subtitle = null,
        provider = ProviderKind.APPLE_MUSIC,
        isActive = true,
    )

    private fun row(
        profileId: String,
        id: String,
        type: String = "ALBUM",
        provider: String = if (profileId == "p-navi") MediaId.PROVIDER_SUBSONIC else MediaId.PROVIDER_SPOTIFY,
        plays: Int = 1,
        cover: String? = null,
    ) = OtherProfilePlayAggregate(
        profileId = profileId,
        provider = provider,
        entityType = type,
        entityId = id,
        title = "Title $id",
        subtitle = "Artist $id",
        coverArtId = cover,
        playCount = plays,
        lastAt = 0L,
    )

    @Test
    fun should_takeTurnsBetweenAccounts_when_bothHavePlays() {
        val rows = listOf(
            row("p-spotify", "s1", plays = 30),
            row("p-spotify", "s2", plays = 20),
            row("p-navi", "n1", plays = 10),
            row("p-spotify", "s3", plays = 5),
        )

        val items = buildOtherProfileItems(rows, listOf(active, spotify, navidrome), coverUrl = { _, _ -> null })

        assertEquals(listOf("s1", "n1", "s2", "s3"), items.map { it.rawId })
        assertEquals("51 on Spotify", items.first().profileName)
        assertEquals("fiftyone", items[1].profileName)
    }

    @Test
    fun should_leaveOutAccountsThatCantBeUsed_when_building() {
        val revoked = spotify.copy(requiresReconnect = true)
        val rows = listOf(row("p-spotify", "s1"), row("p-navi", "n1"), row("p-gone", "g1"))

        val items = buildOtherProfileItems(rows, listOf(revoked, navidrome), coverUrl = { _, _ -> null })

        assertEquals(listOf("n1"), items.map { it.rawId })
    }

    @Test
    fun should_capEachAccountAndTheShelf_when_manyRows() {
        val rows = (1..10).map { row("p-spotify", "s$it", plays = 100 - it) } +
            (1..10).map { row("p-navi", "n$it", plays = 50 - it) }

        val items = buildOtherProfileItems(
            rows,
            listOf(spotify, navidrome),
            coverUrl = { _, _ -> null },
            perProfile = 3,
            total = 5,
        )

        assertEquals(listOf("s1", "n1", "s2", "n2", "s3"), items.map { it.rawId })
    }

    @Test
    fun should_readKindsAndStoredIds_when_building() {
        val rows = listOf(
            row("p-spotify", "spotify:pl1", type = "PLAYLIST", cover = "https://i.scdn.co/a"),
            row("p-spotify", "song1", type = "SONG"),
            row("p-navi", "al-9", cover = "al-9"),
        )

        val items = buildOtherProfileItems(
            rows,
            listOf(spotify, navidrome),
            coverUrl = { profileId, key -> if (key.startsWith("http")) key else "cover:$profileId:$key" },
        )

        assertEquals(2, items.size)
        val playlist = items.first { it.profileId == "p-spotify" }
        assertEquals(OtherProfileItem.Kind.Playlist, playlist.kind)
        assertEquals("pl1", playlist.rawId)
        assertEquals(MediaId(MediaId.PROVIDER_SPOTIFY, "pl1"), playlist.mediaId)
        assertEquals("https://i.scdn.co/a", playlist.coverUrl)
        val album = items.first { it.profileId == "p-navi" }
        assertEquals(OtherProfileItem.Kind.Album, album.kind)
        assertEquals("cover:p-navi:al-9", album.coverUrl)
    }

    @Test
    fun should_beEmpty_when_onlyTheActiveAccountPlayed() {
        val items = buildOtherProfileItems(
            listOf(row("p-apple", "a1", provider = MediaId.PROVIDER_APPLE_MUSIC)),
            listOf(active),
            coverUrl = { _, _ -> null },
        )

        assertTrue(items.isEmpty())
        assertNull(items.firstOrNull())
    }
}
