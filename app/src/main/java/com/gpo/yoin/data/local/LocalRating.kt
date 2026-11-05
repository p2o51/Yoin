package com.gpo.yoin.data.local

import androidx.room.ColumnInfo
import androidx.room.Entity

import com.gpo.yoin.data.model.MediaId

@Entity(tableName = "local_ratings", primaryKeys = ["profileId", "songId", "provider"])
data class LocalRating(
    @ColumnInfo(defaultValue = "")
    val profileId: String = "",
    val songId: String,
    @ColumnInfo(defaultValue = MediaId.PROVIDER_SUBSONIC)
    val provider: String = MediaId.PROVIDER_SUBSONIC,
    /**
     * 0.0–10.0, or null when the track is not rated: cleared from the album
     * scrapbook's track sheet (the only clear in the app). A row is kept for
     * a clear rather than deleted, so the clear syncs like any other edit and
     * the provider still gets its push. 0.0 is a real score, not "unrated".
     */
    val rating: Float?,
    val serverRating: Int,
    val needsSync: Boolean = false,
    val updatedAt: Long = System.currentTimeMillis(),
)
