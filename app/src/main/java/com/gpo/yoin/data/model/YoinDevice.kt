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
    ) : YoinDevice

    data class LocalPlayback(
        override val isActive: Boolean,
        override val isSelectable: Boolean = true,
        override val statusText: String? = null,
    ) : YoinDevice {
        override val id: String = "local"
        override val name: String = "This phone"
    }

    data class Chromecast(
        override val id: String,
        override val name: String,
        override val isActive: Boolean,
        override val isSelectable: Boolean = false,
        override val statusText: String? = null,
    ) : YoinDevice
}
