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
            libraryId?.let { put(EXTRA_LIBRARY_ID, it) }
            catalogId?.let { put(EXTRA_CATALOG_ID, it) }
        }
    )

    companion object {
        const val EXTRA_LIBRARY_ID = "appleMusicLibraryId"
        const val EXTRA_CATALOG_ID = "appleMusicCatalogId"

        /** Present when the album load resolved the user's library, so a missing library id means "not added". */
        const val EXTRA_LIBRARY_CHECKED = "appleMusicLibraryChecked"

        fun fromJson(resource: JsonObject): AppleMusicSong {
            val id = requireNotNull(resource.string("id"))
            val type = resource.string("type")
            require(type == "songs" || type == "library-songs") { "Expected an Apple Music song" }
            val attributes = resource["attributes"]?.jsonObject
            val catalog = resource["relationships"]?.jsonObject?.get("catalog")?.jsonObject
                ?.get("data")?.jsonArray?.firstOrNull()?.jsonObject
            val library = resource["relationships"]?.jsonObject?.get("library")?.jsonObject
                ?.get("data")?.jsonArray?.firstOrNull()?.jsonObject
            return AppleMusicSong(
                resourceId = id,
                libraryId = if (type == "library-songs") id else {
                    library?.takeIf { it.string("type") == "library-songs" }?.string("id")
                },
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
