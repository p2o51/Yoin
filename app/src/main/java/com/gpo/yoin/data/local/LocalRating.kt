package com.gpo.yoin.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "local_ratings")
data class LocalRating(
    @PrimaryKey val songId: String,
    val rating: Float,
    val serverRating: Int,
    val needsSync: Boolean = false,
    val updatedAt: Long = System.currentTimeMillis(),
)
