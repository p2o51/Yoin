package com.gpo.yoin.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "activity_events")
data class ActivityEvent(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val entityType: String,
    val actionType: String,
    val entityId: String,
    val title: String,
    val subtitle: String,
    val coverArtId: String? = null,
    val songId: String? = null,
    val albumId: String? = null,
    val artistId: String? = null,
    val timestamp: Long = System.currentTimeMillis(),
)

enum class ActivityEntityType {
    SONG,
    ALBUM,
    ARTIST,
}

enum class ActivityActionType {
    PLAYED,
    VISITED,
}
