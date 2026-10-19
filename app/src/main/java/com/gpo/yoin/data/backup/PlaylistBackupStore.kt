package com.gpo.yoin.data.backup

import androidx.room.withTransaction
import com.gpo.yoin.data.local.YoinDatabase
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * Room side of playlist backups (`playlist_backups`). Every write goes
 * through here, so the cloud sync capture (CloudSyncManager observes the
 * table) sees each change once.
 */
class PlaylistBackupStore(
    private val db: YoinDatabase,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val dao get() = db.playlistBackupDao()

    /** Every backup, newest first; rows this app version can't read are left out. */
    fun observeAll(): Flow<List<PlaylistBackupRecord>> =
        dao.observeAll().map { rows -> rows.mapNotNull(PlaylistBackupCodec::fromEntity) }

    fun observe(id: String): Flow<PlaylistBackupRecord?> =
        dao.observe(id).map { row -> row?.let(PlaylistBackupCodec::fromEntity) }

    suspend fun get(id: String): PlaylistBackupRecord? = dao.get(id)?.let(PlaylistBackupCodec::fromEntity)

    suspend fun getAll(): List<PlaylistBackupRecord> = dao.getAll().mapNotNull(PlaylistBackupCodec::fromEntity)

    /** The backup that follows [sourcePlaylistId] on [sourceProvider], if there is one. */
    suspend fun findBySource(sourceProvider: String, sourcePlaylistId: String): PlaylistBackupRecord? =
        dao.findBySource(sourceProvider, sourcePlaylistId).firstNotNullOfOrNull(PlaylistBackupCodec::fromEntity)

    suspend fun save(record: PlaylistBackupRecord) = dao.upsert(PlaylistBackupCodec.toEntity(record))

    suspend fun delete(id: String) = dao.delete(id)

    /** [record] added to the end of backup [id]'s restore history; false when the backup is gone. */
    suspend fun recordRestore(id: String, record: PlaylistRestoreRecord): Boolean = db.withTransaction {
        val row = dao.get(id) ?: return@withTransaction false
        val history = runCatching { PlaylistBackupCodec.decodeRestores(row.restoresJson) }.getOrDefault(emptyList())
        // updatedAt only moves forward, even under a clock that went back since the last change.
        val updatedAt = maxOf(clock(), row.updatedAt + 1)
        dao.updateRestores(id, PlaylistBackupCodec.encodeRestores(history + record), updatedAt) > 0
    }
}
