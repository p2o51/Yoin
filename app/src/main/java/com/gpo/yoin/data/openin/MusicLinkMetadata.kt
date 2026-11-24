package com.gpo.yoin.data.openin

import com.gpo.yoin.data.remote.applemusic.AppleMusicSong
import com.gpo.yoin.shared.openin.YoinMusicLinkKind
import com.gpo.yoin.shared.openin.YoinSpotifyPageMeta
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * What a music link names, read without the user's login (docs/open-in-yoin.md): the card's cover and lines,
 * and what a found link searches for. [artist] is a playlist's curator. Any field may be missing.
 */
data class MusicLinkMetadata(
    val kind: YoinMusicLinkKind,
    val title: String?,
    val artist: String?,
    val album: String? = null,
    val isrc: String? = null,
    val durationSec: Int? = null,
    val artworkUrl: String? = null,
) {
    val isEmpty: Boolean get() = title.isNullOrBlank()

    companion object {
        /** A Spotify page's meta ([YoinSpotifyPageMeta]); a playlist's owner stands where the artist would. */
        fun fromSpotifyPage(kind: YoinMusicLinkKind, page: YoinSpotifyPageMeta): MusicLinkMetadata = MusicLinkMetadata(
            kind = kind,
            title = page.title,
            artist = if (kind == YoinMusicLinkKind.Playlist) page.owner else page.artist,
            album = page.album,
            durationSec = page.durationSec,
            artworkUrl = page.imageUrl,
        )

        /**
         * An Apple Music catalog resource (`GET /v1/catalog/{storefront}/{songs|albums|artists|playlists}/{id}`'s
         * `data[0]`), read with the developer token alone.
         */
        fun fromAppleMusicResource(kind: YoinMusicLinkKind, resource: JsonObject): MusicLinkMetadata? {
            val attributes = resource["attributes"] as? JsonObject ?: return null
            val artwork = (attributes["artwork"] as? JsonObject)?.string("url")
                ?.replace("{w}", ARTWORK_SIZE)?.replace("{h}", ARTWORK_SIZE)
                ?.replace("{f}", "jpg")
            if (kind == YoinMusicLinkKind.Track && resource.string("type") == "songs") {
                val song = runCatching { AppleMusicSong.fromJson(resource) }.getOrNull() ?: return null
                return MusicLinkMetadata(
                    kind = kind,
                    title = song.title,
                    artist = song.artist,
                    album = song.album,
                    isrc = song.isrc,
                    durationSec = song.durationMs?.div(1000),
                    artworkUrl = artwork,
                )
            }
            return MusicLinkMetadata(
                kind = kind,
                title = attributes.string("name"),
                artist = attributes.string("artistName") ?: attributes.string("curatorName"),
                artworkUrl = artwork,
            )
        }

        private const val ARTWORK_SIZE = "600"

        private fun JsonObject.string(key: String): String? =
            (get(key) as? JsonPrimitive)?.contentOrNull?.takeIf(String::isNotBlank)
    }
}
