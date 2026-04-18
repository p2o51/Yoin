package com.gpo.yoin.data.model

/**
 * A provider-opaque reference to cover art. UI calls
 * [com.gpo.yoin.data.source.MusicSource.resolveCoverUrl] to turn it into a URL
 * it can load — callers should never construct URLs themselves.
 */
sealed interface CoverRef {
    /** Absolute URL (e.g. Spotify image URL). */
    data class Url(val url: String) : CoverRef

    /**
     * Source-relative opaque id. The owning [MusicSource] knows how to turn it
     * into a URL (e.g. Subsonic coverArt id + server credentials).
     */
    data class SourceRelative(val coverArtId: String) : CoverRef

    companion object {
        /**
         * Flatten a [CoverRef] into the single string persisted by Room-backed
         * activity / history rows. Readers reverse the mapping with
         * [fromStorageKey]. The two are lossless for the variants we use: URL
         * strings always parse back to [Url], raw ids to [SourceRelative].
         */
        fun toStorageKey(ref: CoverRef?): String? = when (ref) {
            is Url -> ref.url
            is SourceRelative -> ref.coverArtId
            null -> null
        }

        /**
         * Inverse of [toStorageKey]. Treats `http(s)://…` strings as direct
         * URLs (Spotify) and everything else as a source-relative id
         * (Subsonic). Existing plaintext-id rows continue to resolve through
         * the Subsonic path, so no Room migration is required.
         */
        fun fromStorageKey(key: String?): CoverRef? = when {
            key.isNullOrBlank() -> null
            key.startsWith("http://", ignoreCase = true) ||
                key.startsWith("https://", ignoreCase = true) -> Url(key)
            else -> SourceRelative(key)
        }
    }
}
