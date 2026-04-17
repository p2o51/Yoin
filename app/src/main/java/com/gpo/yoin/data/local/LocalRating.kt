package com.gpo.yoin.data.local

import androidx.room.ColumnInfo
import androidx.room.Entity

import com.gpo.yoin.data.model.MediaId

@Entity(tableName = "local_ratings", primaryKeys = ["songId", "provider"])
data class LocalRating(
    val songId: String,
    @ColumnInfo(defaultValue = MediaId.PROVIDER_SUBSONIC)
    val provider: String = MediaId.PROVIDER_SUBSONIC,
    val rating: Float,
    val serverRating: Int,
    val needsSync: Boolean = false,
    val updatedAt: Long = System.currentTimeMillis(),
)
