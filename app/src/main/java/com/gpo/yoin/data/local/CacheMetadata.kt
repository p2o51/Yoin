package com.gpo.yoin.data.local

import androidx.room.ColumnInfo
import androidx.room.Entity

import com.gpo.yoin.data.model.MediaId

@Entity(tableName = "cache_metadata", primaryKeys = ["songId", "provider"])
data class CacheMetadata(
    val songId: String,
    @ColumnInfo(defaultValue = MediaId.PROVIDER_SUBSONIC)
    val provider: String = MediaId.PROVIDER_SUBSONIC,
    val title: String,
    val artist: String,
    val album: String,
    val fileSizeBytes: Long,
    val cachedAt: Long = System.currentTimeMillis(),
    val lastAccessedAt: Long = System.currentTimeMillis(),
)
