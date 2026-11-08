package com.gpo.yoin.ui.nowplaying

import com.gpo.yoin.data.model.YoinDevice
import org.junit.Assert.assertEquals
import org.junit.Test

class SpotifyDeviceMemoryTest {
    private val day = 24L * 60 * 60 * 1000

    private fun listed(id: String, name: String = id) = YoinDevice.SpotifyConnect(
        id = id,
        name = name,
        isActive = false,
        spotifyType = "Speaker",
    )

    private fun remembered(id: String, seen: Long, name: String = id) =
        RememberedSpotifyDevice(id = id, name = name, type = "Speaker", lastSeenMs = seen)

    @Test
    fun should_keepSonos_when_itDropsOutOfTheList() {
        val first = mergeRememberedSpotifyDevices(emptyList(), listOf(listed("fold"), listed("sonos")), nowMs = day)
        val second = mergeRememberedSpotifyDevices(first, listOf(listed("fold")), nowMs = 2 * day)

        val rows = withRememberedSpotifyDevices(listOf(listed("fold")), second)

        assertEquals(listOf("fold", "sonos"), rows.map { it.id })
        assertEquals(NpDeviceStatusRemembered, rows[1].statusText)
        assertEquals(null, rows[0].statusText)
    }

    @Test
    fun should_forgetDevice_when_unseenForLongerThanTtl() {
        val old = listOf(remembered("sonos", seen = day))
        val merged = mergeRememberedSpotifyDevices(old, emptyList(), nowMs = day + SPOTIFY_DEVICE_MEMORY_TTL_MS + 1)

        assertEquals(emptyList<RememberedSpotifyDevice>(), merged)
    }

    @Test
    fun should_rememberSonosAsSpotifyOnly_when_itHasNoId() {
        val sonos = listed(YoinDevice.SpotifyConnect.noIdKey("Living Room Back"), name = "Living Room Back")
            .copy(isActive = true, spotifyOnly = true)
        val merged = mergeRememberedSpotifyDevices(emptyList(), listOf(sonos), nowMs = day)

        val rows = withRememberedSpotifyDevices(listOf(listed("fold")), merged)

        assertEquals(listOf("fold", "spotify-no-id:Living Room Back"), rows.map { it.id })
        assertEquals(true, rows[1].spotifyOnly)
        assertEquals(false, rows[1].isActive)
    }

    @Test
    fun should_hideRememberedRow_when_sameNameIsListedUnderNewId() {
        val rows = withRememberedSpotifyDevices(
            listed = listOf(listed("new-id", name = "Roam 2")),
            remembered = listOf(remembered("old-id", seen = day, name = "Roam 2")),
        )

        assertEquals(listOf("new-id"), rows.map { it.id })
    }

    @Test
    fun should_capRememberedDevices_newestFirst() {
        val many = (1..20).map { remembered("d$it", seen = it.toLong()) }
        val merged = mergeRememberedSpotifyDevices(many, emptyList(), nowMs = 30)

        assertEquals(SPOTIFY_DEVICE_MEMORY_MAX, merged.size)
        assertEquals("d20", merged.first().id)
    }
}
