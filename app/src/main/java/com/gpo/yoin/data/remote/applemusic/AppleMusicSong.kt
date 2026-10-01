package com.gpo.yoin.data.remote.applemusic

import com.gpo.yoin.data.model.CoverRef
import com.gpo.yoin.data.model.MediaId
import com.gpo.yoin.data.model.Track
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** Keeps library identity separate; imported songs may have no catalog counterpart. */
data class AppleMusicSong(
    val resourceId: String,
    val libraryId: String?,
    val catalogId: String?,
    val title: String?,
    val artist: String?,
    val album: String?,
    val durationMs: Int?,
    val artworkTemplate: String?,
    val trackNumber: Int?,
    val albumId: MediaId? = null,
    val artistId: MediaId? = null
) {
    fun toTrack(): Track = Track(
        id = MediaId(MediaId.PROVIDER_APPLE_MUSIC, catalogId ?: "library:$resourceId"),
        title = title,
        artist = artist,
        artistId = artistId,
        album = album,
        albumId = albumId,
        coverArt = artworkTemplate?.replace("{w}", "600")?.replace("{h}", "600")
            ?.let(CoverRef::Url),
        durationSec = durationMs?.div(1000),
        trackNumber = trackNumber,
        year = null,
        genre = null,
        userRating = null,
        // Library membership is deliberately not represented as favorite/heart.
        extras = buildMap {
            libraryId?.let { put("appleMusicLibraryId", it) }
            catalogId?.let { put("appleMusicCatalogId", it) }
        }
    )

    companion object {
        fun fromJson(resource: JsonObject): AppleMusicSong {
            val id = requireNotNull(resource.string("id"))
            val type = resource.string("type")
            require(type == "songs" || type == "library-songs") { "Expected an Apple Music song" }
            val attributes = resource["attributes"]?.jsonObject
            val catalog = resource["relationships"]?.jsonObject?.get("catalog")?.jsonObject
                ?.get("data")?.jsonArray?.firstOrNull()?.jsonObject
            return AppleMusicSong(
                resourceId = id,
                libraryId = id.takeIf { type == "library-songs" },
                catalogId = if (type == "songs") {
                    id
                } else {
                    catalog?.string("id")
                        ?: attributes?.get("playParams")?.jsonObject?.string("catalogId")
                },
                title = attributes?.string("name"),
                artist = attributes?.string("artistName"),
                album = attributes?.string("albumName"),
                durationMs = attributes?.get("durationInMillis")?.jsonPrimitive?.intOrNull,
                artworkTemplate = attributes?.get("artwork")?.jsonObject?.string("url"),
                trackNumber = attributes?.get("trackNumber")?.jsonPrimitive?.intOrNull,
                albumId = relatedId(resource, "albums") ?: catalog?.let { relatedId(it, "albums") },
                artistId = relatedId(resource, "artists") ?: catalog?.let { relatedId(it, "artists") }
            )
        }

        private fun relatedId(resource: JsonObject, relation: String): MediaId? {
            val value = resource["relationships"]?.jsonObject?.get(relation)?.jsonObject
                ?.get("data")?.jsonArray?.firstOrNull()?.jsonObject ?: return null
            val id = value.string("id") ?: return null
            val prefix = if (value.string("type")?.startsWith("library-") == true) "library:" else ""
            return MediaId(MediaId.PROVIDER_APPLE_MUSIC, prefix + id)
        }

        private fun JsonObject.string(key: String): String? = get(key)?.jsonPrimitive?.contentOrNull
    }
}
