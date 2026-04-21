package com.gpo.yoin.data.local

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.gpo.yoin.data.model.MediaId

@Entity(
    tableName = "song_notes",
    indices = [
        Index(value = ["title", "artist"]),
        Index(value = ["trackId", "provider"]),
    ],
)
data class SongNote(
    @PrimaryKey val id: String,
    val trackId: String,
    @ColumnInfo(defaultValue = MediaId.PROVIDER_SUBSONIC)
    val provider: String = MediaId.PROVIDER_SUBSONIC,
    val content: String,
    val createdAt: Long,
    val updatedAt: Long,
    val title: String,
    val artist: String,
)

data class SongNoteKey(
    val trackId: String,
    val provider: String,
)
