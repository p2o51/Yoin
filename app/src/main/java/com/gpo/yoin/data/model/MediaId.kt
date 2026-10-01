package com.gpo.yoin.data.model

/**
 * Provider-namespaced identifier. All remote entities carry one of these so that
 * IDs from different sources (Subsonic, Spotify, …) never collide in Room or in
 * runtime caches.
 *
 * String form is `provider:rawId` (colon-delimited). Provider tokens must not
 * contain a colon.
 */
data class MediaId(val provider: String, val rawId: String) {

    init {
        require(provider.isNotEmpty()) { "provider must not be empty" }
        require(!provider.contains(':')) { "provider must not contain ':'" }
        require(rawId.isNotEmpty()) { "rawId must not be empty" }
    }

    override fun toString(): String = "$provider:$rawId"

    companion object {
        const val PROVIDER_SUBSONIC = "subsonic"
        const val PROVIDER_SPOTIFY = "spotify"
        const val PROVIDER_LOCAL = "local"
        const val PROVIDER_APPLE_MUSIC = "applemusic"

        fun subsonic(rawId: String): MediaId = MediaId(PROVIDER_SUBSONIC, rawId)
        fun spotify(rawId: String): MediaId = MediaId(PROVIDER_SPOTIFY, rawId)
        fun local(rawId: String): MediaId = MediaId(PROVIDER_LOCAL, rawId)

        fun parse(value: String): MediaId {
            val sep = value.indexOf(':')
            require(sep > 0 && sep < value.length - 1) {
                "Invalid MediaId string: $value"
            }
            return MediaId(value.substring(0, sep), value.substring(sep + 1))
        }

        fun parseOrNull(value: String?): MediaId? = value
            ?.takeIf { it.isNotEmpty() }
            ?.runCatching { parse(this) }
            ?.getOrNull()

        /**
         * rawId of a stored id column, which holds either the bare rawId or a
         * legacy `provider:rawId` string. Only the owning provider's prefix is
         * stripped: Apple Music library rawIds contain a colon themselves
         * (`library:l.abc`), so cutting at the first ':' would turn them into
         * catalog ids.
         */
        fun storedRawId(provider: String, stored: String): String = stored.removePrefix("$provider:")
    }
}
