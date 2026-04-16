package com.gpo.yoin.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "song_info")
data class SongInfo(
    @PrimaryKey val songId: String,
    val creationTime: String?,
    val creationLocation: String?,
    val lyricist: String?,
    val composer: String?,
    val producer: String?,
    val review: String?,
    val cachedAt: Long = System.currentTimeMillis(),
)
