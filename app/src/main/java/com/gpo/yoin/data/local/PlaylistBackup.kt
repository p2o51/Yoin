package com.gpo.yoin.data.local

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * A playlist's cloud backup (docs/playlist-transfer.md §7, owner 2026-10-19):
 * one row per playlist, kept up to date — while the playlist is there, every
 * change to it is backed up again ([lastChangedAt]); once it is gone from its
 * service the backup stops following it ([sourceGoneAt]) but stays, to be
 * restored into any profile later.
 *
 * The columns are what Settings shows; [contentJson] is the snapshot
 * ([com.gpo.yoin.data.backup.PlaylistBackupContent]: description, tracks,
 * entries that couldn't be backed up) and [restoresJson] the restore history
 * ([com.gpo.yoin.data.backup.PlaylistRestoreRecord] list). Global, not per
 * profile (synced as [com.gpo.yoin.data.sync.SyncKinds.PLAYLIST_BACKUP]);
 * only [sourceProfileId] is this device's own.
 */
@Entity(
    tableName = "playlist_backups",
    indices = [Index(value = ["lastChangedAt"]), Index(value = ["sourceProvider", "sourcePlaylistId"])],
)
data class PlaylistBackup(
    @PrimaryKey val id: String,
    val name: String,
    /** [com.gpo.yoin.data.model.MediaId.provider] of the service it came from. */
    val sourceProvider: String,
    /** The profile's name when it was last backed up. */
    val sourceProfileLabel: String,
    /** The playlist's [com.gpo.yoin.data.model.MediaId] on that service, as a string. */
    val sourcePlaylistId: String,
    /**
     * The local profile that follows the playlist; null on devices without
     * that profile (they show the backup but can't follow it). Not synced.
     */
    val sourceProfileId: String?,
    /** When the playlist was made or joined the library, as its service says (ISO-8601); null when it doesn't. */
    val playlistCreatedAt: String?,
    /** When the backup began. */
    val firstBackedUpAt: Long,
    /** The last time the playlist was read and saved (changed or not). */
    val lastBackedUpAt: Long,
    /** The last time what the playlist holds changed. */
    val lastChangedAt: Long,
    /** When the playlist was found gone from its service; null while it is there. */
    val sourceGoneAt: Long?,
    val trackCount: Int,
    val unexportedCount: Int,
    val contentJson: String,
    /**
     * Earlier versions, newest first ([com.gpo.yoin.data.backup.BackupVersion]
     * list): what the playlist held before each change, up to
     * [com.gpo.yoin.data.backup.PlaylistSnapshots.KEEP_VERSIONS] in all with
     * the current one.
     */
    val versionsJson: String,
    val restoresJson: String,
    /** The last change to the row: a backup, the playlist found gone, or a restore added to its history. */
    val updatedAt: Long,
)
