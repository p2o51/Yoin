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
        Index(value = ["profileId", "trackId", "provider"]),
    ],
)
data class SongNote(
    @PrimaryKey val id: String,
    @ColumnInfo(defaultValue = "")
    val profileId: String = "",
    val trackId: String,
    @ColumnInfo(defaultValue = MediaId.PROVIDER_SUBSONIC)
    val provider: String = MediaId.PROVIDER_SUBSONIC,
    val content: String,
    val createdAt: Long,
    val updatedAt: Long,
    val title: String,
    val artist: String,
    /**
     * Playhead position (ms into the track) captured when the note was
     * written — anchors the note on the song's timeline for timeline sort
     * and lyrics-style current-note highlight. Null for legacy notes and
     * notes the user chose not to anchor.
     */
    val positionMs: Long? = null,
)

data class SongNoteKey(
    val profileId: String,
    val trackId: String,
    val provider: String,
)
