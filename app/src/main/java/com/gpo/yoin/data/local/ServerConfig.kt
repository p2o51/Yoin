package com.gpo.yoin.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "server_config")
data class ServerConfig(
    @PrimaryKey val id: Long = 1,
    val serverUrl: String,
    val username: String,
    val passwordHash: String,
    val isActive: Boolean = true,
)
