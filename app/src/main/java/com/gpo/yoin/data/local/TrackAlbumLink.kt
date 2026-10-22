package com.gpo.yoin.data.local

import androidx.room.Entity

/**
 * Which album a track belongs to: a fact learned from a play, an album page, or a lookup for imported notes
 * (docs/spotoolfy-migration.md §4.6). Notes and ratings carry no album, and play_history only knows tracks played
 * in Yoin; this lets a track rated or noted but never played here still find its album for Memories and Rediscover.
 * Per profile: two Subsonic servers share a provider token but not an id space.
 */
@Entity(tableName = "track_album_links", primaryKeys = ["profileId", "provider", "trackId"])
data class TrackAlbumLink(
    val profileId: String,
    val provider: String,
    val trackId: String,
    val albumId: String,
    /** [SOURCE_PLAY], [SOURCE_DETAIL] or [SOURCE_IMPORT]. */
    val source: String,
    val updatedAt: Long,
) {
    companion object {
        const val SOURCE_PLAY = "play"
        const val SOURCE_DETAIL = "detail"
        const val SOURCE_IMPORT = "import"
    }
}
