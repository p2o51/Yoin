package com.gpo.yoin.data.model

sealed interface YoinDevice {
    val id: String
    val name: String
    val isActive: Boolean
    val isSelectable: Boolean
    val statusText: String?

    data class SpotifyConnect(
        override val id: String,
        override val name: String,
        override val isActive: Boolean,
        val spotifyType: String,
        override val isSelectable: Boolean = true,
        override val statusText: String? = null,
        /**
         * Spotify won't move playback here through the Web API (no id, or restricted: Sonos).
         * A tap opens Spotify's own picker instead.
         */
        val spotifyOnly: Boolean = false,
    ) : YoinDevice {
        companion object {
            /** Row key for a device Spotify names without an id (a Sonos it plays on). */
            fun noIdKey(name: String): String = "spotify-no-id:${name.trim()}"
        }
    }

    data class LocalPlayback(
        override val isActive: Boolean,
        override val isSelectable: Boolean = true,
        override val statusText: String? = null,
    ) : YoinDevice {
        override val id: String = "local"
        // The devices sheet shows its localized label instead.
        override val name: String = "This device"
    }

    data class Chromecast(
        override val id: String,
        override val name: String,
        override val isActive: Boolean,
        override val isSelectable: Boolean = false,
        override val statusText: String? = null,
    ) : YoinDevice

    data class Dlna(
        override val id: String,
        override val name: String,
        override val isActive: Boolean,
        override val isSelectable: Boolean = true,
        override val statusText: String? = null,
    ) : YoinDevice
}
