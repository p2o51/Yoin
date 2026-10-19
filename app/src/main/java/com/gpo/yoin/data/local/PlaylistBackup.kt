package com.gpo.yoin.data.local

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * One saved copy of a playlist (docs/playlist-transfer.md §7): what it held
 * when it was backed up, kept to be restored into any profile later. Backing
 * the same playlist up again adds another row; nothing replaces one.
 *
 * The columns are what the Settings list shows; [contentJson] is the whole
 * snapshot ([com.gpo.yoin.data.backup.PlaylistBackupContent]: description,
 * tracks, entries that couldn't be backed up) and [restoresJson] the restore
 * history ([com.gpo.yoin.data.backup.PlaylistRestoreRecord] list). Global,
 * not per profile: a backup is restored into any account, on any device
 * (synced as [com.gpo.yoin.data.sync.SyncKinds.PLAYLIST_BACKUP]).
 */
@Entity(
    tableName = "playlist_backups",
    indices = [Index(value = ["backedUpAt"]), Index(value = ["sourceProvider", "sourcePlaylistId"])],
)
data class PlaylistBackup(
    @PrimaryKey val id: String,
    val name: String,
    /** [com.gpo.yoin.data.model.MediaId.provider] of the service it came from. */
    val sourceProvider: String,
    /** The profile's name when it was backed up. */
    val sourceProfileLabel: String,
    /** The playlist's [com.gpo.yoin.data.model.MediaId] on that service, as a string. */
    val sourcePlaylistId: String,
    val backedUpAt: Long,
    val trackCount: Int,
    val unexportedCount: Int,
    val contentJson: String,
    val restoresJson: String,
    /** The last change: the backup itself, or a restore added to its history. */
    val updatedAt: Long,
)
