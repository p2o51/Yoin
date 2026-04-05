package com.gpo.yoin.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "play_history")
data class PlayHistory(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val songId: String,
    val title: String,
    val artist: String,
    val album: String,
    val albumId: String,
    val coverArtId: String?,
    val playedAt: Long = System.currentTimeMillis(),
    val durationMs: Long,
    val completedPercent: Float,
)
