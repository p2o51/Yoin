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
}
