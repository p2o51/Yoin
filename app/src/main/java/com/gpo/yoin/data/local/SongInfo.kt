package com.gpo.yoin.data.local

import androidx.room.ColumnInfo
import androidx.room.Entity

import com.gpo.yoin.data.model.MediaId

@Entity(tableName = "song_info", primaryKeys = ["songId", "provider"])
data class SongInfo(
    val songId: String,
    @ColumnInfo(defaultValue = MediaId.PROVIDER_SUBSONIC)
    val provider: String = MediaId.PROVIDER_SUBSONIC,
    val creationTime: String?,
    val creationLocation: String?,
    val lyricist: String?,
    val composer: String?,
    val producer: String?,
    val review: String?,
    val cachedAt: Long = System.currentTimeMillis(),
)
