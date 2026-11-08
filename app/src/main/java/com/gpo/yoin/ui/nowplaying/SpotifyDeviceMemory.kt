package com.gpo.yoin.ui.nowplaying

import android.content.Context
import androidx.core.content.edit
import com.gpo.yoin.data.model.YoinDevice
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/**
 * Spotify Connect devices Yoin has seen, per account (owner 2026-11-08).
 *
 * The Web API lists only devices online in Spotify's cloud; speakers on the
 * local network (Sonos, Cast) show up there only while Spotify plays on them
 * or soon after. Once seen, a speaker stays in the Devices sheet as
 * "recently used" for [SPOTIFY_DEVICE_MEMORY_TTL_MS], and a tap tries the
 * transfer. If it is asleep the transfer fails and the sheet says to pick it
 * once in Spotify (the sheet's "More devices" row).
 */
interface SpotifyDeviceMemory {
    fun recall(profileId: String): List<RememberedSpotifyDevice>

    fun remember(profileId: String, devices: List<RememberedSpotifyDevice>)

    class InMemory : SpotifyDeviceMemory {
        private val byProfile = HashMap<String, List<RememberedSpotifyDevice>>()

        override fun recall(profileId: String): List<RememberedSpotifyDevice> = byProfile[profileId].orEmpty()

        override fun remember(profileId: String, devices: List<RememberedSpotifyDevice>) {
            byProfile[profileId] = devices
        }
    }
}

@Serializable
data class RememberedSpotifyDevice(
    val id: String,
    val name: String,
    val type: String,
    val lastSeenMs: Long,
    /** Spotify won't take a Web API transfer to it (Sonos): a tap opens Spotify's picker. */
    val spotifyOnly: Boolean = false,
)

class SharedPrefsSpotifyDeviceMemory(context: Context) : SpotifyDeviceMemory {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true }
    private val serializer = ListSerializer(RememberedSpotifyDevice.serializer())

    override fun recall(profileId: String): List<RememberedSpotifyDevice> {
        val raw = prefs.getString(key(profileId), null) ?: return emptyList()
        return runCatching { json.decodeFromString(serializer, raw) }.getOrDefault(emptyList())
    }

    override fun remember(profileId: String, devices: List<RememberedSpotifyDevice>) {
        prefs.edit { putString(key(profileId), json.encodeToString(serializer, devices)) }
    }

    private fun key(profileId: String) = "devices:$profileId"

    private companion object {
        const val PREFS_NAME = "yoin_spotify_devices"
    }
}

internal const val SPOTIFY_DEVICE_MEMORY_TTL_MS = 30L * 24 * 60 * 60 * 1000
internal const val SPOTIFY_DEVICE_MEMORY_MAX = 12

/** Marks a sheet row Yoin remembers but Spotify didn't list this time. */
internal const val NpDeviceStatusRemembered = "status:spotify-remembered"

/**
 * [remembered] updated with what Spotify listed just now ([listed]): listed
 * devices refresh their name and time, expired ones drop, newest first.
 */
internal fun mergeRememberedSpotifyDevices(
    remembered: List<RememberedSpotifyDevice>,
    listed: List<YoinDevice.SpotifyConnect>,
    nowMs: Long,
): List<RememberedSpotifyDevice> {
    // Sonos included: Spotify lists it only while it plays, so remembering it is the point.
    val fresh = listed
        .filter { it.statusText != NpDeviceStatusRemembered }
        .map {
            RememberedSpotifyDevice(
                id = it.id,
                name = it.name,
                type = it.spotifyType,
                lastSeenMs = nowMs,
                spotifyOnly = it.spotifyOnly,
            )
        }
    val freshIds = fresh.mapTo(HashSet()) { it.id }
    val kept = remembered.filter { it.id !in freshIds && nowMs - it.lastSeenMs in 0..SPOTIFY_DEVICE_MEMORY_TTL_MS }
    return (fresh + kept).sortedByDescending { it.lastSeenMs }.take(SPOTIFY_DEVICE_MEMORY_MAX)
}

/** Sheet rows for remembered devices Spotify didn't list this time, after the listed ones. */
internal fun withRememberedSpotifyDevices(
    listed: List<YoinDevice.SpotifyConnect>,
    remembered: List<RememberedSpotifyDevice>,
): List<YoinDevice.SpotifyConnect> {
    val listedIds = listed.mapTo(HashSet()) { it.id }
    // A device can come back under a new id (a reinstall); its name is listed then.
    val listedNames = listed.mapTo(HashSet()) { it.name.trim().lowercase() }
    return listed + remembered
        .filter { it.id !in listedIds && it.name.trim().lowercase() !in listedNames }
        .map {
            YoinDevice.SpotifyConnect(
                id = it.id,
                name = it.name,
                isActive = false,
                spotifyType = it.type,
                statusText = NpDeviceStatusRemembered,
                spotifyOnly = it.spotifyOnly,
            )
        }
}
