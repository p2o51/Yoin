package com.gpo.yoin.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "gemini_config")
data class GeminiConfig(
    @PrimaryKey val id: Int = 1,
    val apiKey: String,
)
