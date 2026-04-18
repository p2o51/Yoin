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
    }
}
