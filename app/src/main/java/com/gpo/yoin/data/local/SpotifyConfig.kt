package com.gpo.yoin.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Single-row table (`id = 1`) holding app-level Spotify configuration.
 * Currently just the OAuth `clientId` — users enter it in Settings so they
 * can bring their own Spotify developer app without rebuilding the APK.
 * BuildConfig.SPOTIFY_CLIENT_ID is used as a fallback when this row is
 * absent or blank.
 */
@Entity(tableName = "spotify_config")
data class SpotifyConfig(
    @PrimaryKey val id: Int = 1,
    val clientId: String,
)
