package com.gpo.yoin.data.local

import androidx.room.Entity

@Entity(
    tableName = "spotify_library_sync_meta",
    primaryKeys = ["profileId"],
)
data class SpotifyLibrarySyncMeta(
    val profileId: String,
    val cachedAt: Long = 0L,
    val backoffUntilMs: Long = 0L,
    val lastSyncError: String? = null,
)
