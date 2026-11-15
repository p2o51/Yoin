package com.gpo.yoin.data.source.spotify

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Made For You from App Remote's content tree (ids as the Pixel Tablet listed them, 2026-11-15). */
class SpotifyMadeForYouTest {

    private fun item(id: String, title: String, subtitle: String = "") =
        SpotifyContentItem(uri = "spotify:playlist:$id", title = title, subtitle = subtitle, imageUrl = null, playable = true)

    @Test
    fun should_tellKinds_by_idPrefix() {
        assertEquals(MadeForYouKind.DiscoverWeekly, madeForYouKind("37i9dQZEVXcDKfauVfjjas"))
        assertEquals(MadeForYouKind.ReleaseRadar, madeForYouKind("37i9dQZEVXbjmhL1XgmvAy"))
        assertEquals(MadeForYouKind.Daylist, madeForYouKind("37i9dQZF1EP6YuccBxUcC1"))
        assertEquals(MadeForYouKind.OnRepeat, madeForYouKind("37i9dQZF1Epu3DEA4aBT0J"))
        assertEquals(MadeForYouKind.DailyMix, madeForYouKind("37i9dQZF1E36GY2ncdnlLE"))
        assertEquals(MadeForYouKind.Blend, madeForYouKind("37i9dQZF1EJyJ5dTbuLllN"))
        assertEquals(MadeForYouKind.Mix, madeForYouKind("37i9dQZF1EQpesGsmIyqcW"))
        assertEquals(MadeForYouKind.Other, madeForYouKind("37i9dQZF1DXcBWIGoYBM5M"))
        assertEquals(MadeForYouKind.Other, madeForYouKind("4uLU6hMCjMI75M1A2tKUQC"))
    }

    @Test
    fun should_orderHomeShelf_and_leaveOutTheLongTail() {
        val hub = listOf(
            item("37i9dQZF1EP6YuccBxUcC1", "electropop sunday afternoon"),
            item("37i9dQZF1Epu3DEA4aBT0J", "On Repeat"),
            item("37i9dQZF1EQpesGsmIyqcW", "K-Pop Mix"),
            item("37i9dQZF1E36GY2ncdnlLE", "Daily Mix 1"),
            item("37i9dQZEVXbjmhL1XgmvAy", "Release Radar"),
            item("37i9dQZEVXcDKfauVfjjas", "Discover Weekly"),
            SpotifyContentItem("spotify:album:abc", "An album", "", null, true),
            item("37i9dQZEVXcDKfauVfjjas", "Discover Weekly"),
        ).toMadeForYouPlaylists()

        assertEquals(6, hub.size)
        assertEquals(
            listOf("electropop sunday afternoon", "Discover Weekly", "Release Radar", "On Repeat", "Daily Mix 1"),
            hub.forHomeShelf().map { it.title },
        )
    }

    @Test
    fun should_readEveryPage_and_rememberTheHub() = runBlocking {
        val all = (0 until 84).map { item("37i9dQZF1E3${it.toString().padStart(11, '0')}", "Daily Mix $it") }
        val asked = mutableListOf<Int>()
        val browser = SpotifyContentBrowser { uri, limit, offset ->
            assertEquals(MADE_FOR_YOU_HUB_URI, uri)
            asked += offset
            Result.success(SpotifyContentPage(all.drop(offset).take(limit), total = all.size))
        }

        val playlists = browser.madeForYou().getOrThrow()

        assertEquals(listOf(0, 50), asked)
        assertEquals(84, playlists.size)
        assertEquals("Daily Mix 3", SpotifyContentBridge.known(playlists[3].rawId)?.title)
    }

    @Test
    fun should_buildPlaylist_from_songs_when_appRemoteReadsIt() = runBlocking {
        val rawId = "37i9dQZEVXcDKfauVfjjas"
        val browser = SpotifyContentBrowser { uri, _, offset ->
            when (uri) {
                "spotify:playlist:$rawId" -> Result.success(
                    SpotifyContentPage(
                        if (offset == 0) {
                            listOf(SpotifyContentItem("spotify:track:6eV5u0pFwIeTs6FrHaTp3b", "end scene", "DJ Suzy", "https://i.scdn.co/image/x", true))
                        } else {
                            emptyList()
                        },
                        total = 1,
                    )
                )
                else -> Result.success(SpotifyContentPage(listOf(item(rawId, "Discover Weekly", "Your shortcut")), total = 1))
            }
        }

        val playlist = browser.spotifyMadePlaylist(rawId)!!

        assertEquals("Discover Weekly", playlist.name)
        assertEquals(1, playlist.songCount)
        assertEquals("end scene", playlist.tracks.single().title)
        assertEquals("DJ Suzy", playlist.tracks.single().artist)
        assertNull(playlist.tracks.single().durationSec)
        assertTrue(!playlist.canWrite)
    }

    @Test
    fun should_giveNoPlaylist_when_appRemoteFails() = runBlocking {
        val browser = SpotifyContentBrowser { _, _, _ -> Result.failure(IllegalStateException("not connected")) }
        assertNull(browser.spotifyMadePlaylist("37i9dQZEVXcDKfauVfjjas"))
    }
}
