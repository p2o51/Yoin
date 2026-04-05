package com.gpo.yoin.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "cache_metadata")
data class CacheMetadata(
    @PrimaryKey val songId: String,
    val title: String,
    val artist: String,
    val album: String,
    val fileSizeBytes: Long,
    val cachedAt: Long = System.currentTimeMillis(),
    val lastAccessedAt: Long = System.currentTimeMillis(),
)
