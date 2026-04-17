package com.gpo.yoin.data.local

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

import com.gpo.yoin.data.model.MediaId

@Entity(tableName = "activity_events")
data class ActivityEvent(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val entityType: String,
    val actionType: String,
    val entityId: String,
    @ColumnInfo(defaultValue = MediaId.PROVIDER_SUBSONIC)
    val provider: String = MediaId.PROVIDER_SUBSONIC,
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
    PLAYLIST,
}

enum class ActivityActionType {
    PLAYED,
    VISITED,
}
