package com.gpo.yoin.shared.sync.adapters

import com.gpo.yoin.shared.backup.PlaylistBackupCodec
import com.gpo.yoin.shared.backup.PlaylistBackupRow
import com.gpo.yoin.shared.sync.ApplyOutcome
import com.gpo.yoin.shared.sync.ConflictPolicy
import com.gpo.yoin.shared.sync.DomainRow
import com.gpo.yoin.shared.sync.FileClass
import com.gpo.yoin.shared.sync.SkipReason
import com.gpo.yoin.shared.sync.SyncAdapter
import com.gpo.yoin.shared.sync.SyncKinds
import kotlinx.serialization.json.JsonObject

/**
 * Port of Android's PlaylistBackupSyncAdapter. `playlist_backups` <-> [SyncKinds.PLAYLIST_BACKUP]
 * (docs/playlist-transfer.md §7): one record per backup, keyed by its id (a UUID).
 *
 * Global, not per account: a backup is restored into any account, and Apple Music accounts have no sync
 * id to scope one by. Artifacts, not state: a backup is large (every track's metadata).
 *
 * `content`, `versions` and `restores` travel as the exact JSON text the row holds, so a newer device's
 * extra fields survive a round trip through an older one and the projection hashes alike everywhere;
 * apply checks they still decode. `updatedAt` is a synced field and is written back as received.
 *
 * Deleting a backup is a user action, so deletes propagate as tombstones. `sourceProfileId` is this
 * device's own: it never travels, and apply keeps the local row's.
 *
 * Conflicts: [ConflictPolicy.LWW_LIVE_WINS]: the later change wins; a delete that raced a restore
 * elsewhere loses to it, because a backup can't be made again once its playlist is gone.
 */
class PlaylistBackupSyncAdapter(private val domain: SyncDomain) : SyncAdapter {
    override val kind = SyncKinds.PLAYLIST_BACKUP
    override val kindVersion = 1
    override val fileClass = FileClass.ARTIFACTS
    override val perAccount = false
    override val propagatesDeletes = true
    override val conflictPolicy = ConflictPolicy.LWW_LIVE_WINS

    override suspend fun readAll(localProfileId: String?): List<DomainRow> = domain.playlistBackups().map { row ->
        DomainRow(key = row.id, projection = projection(row), rowTs = row.updatedAt)
    }

    override fun project(payload: JsonObject): JsonObject? = rowOf(KEY_UNKNOWN, payload)?.let(::projection)

    override suspend fun apply(
        localProfileId: String?,
        key: String,
        payload: JsonObject,
        versionTs: Long,
        expectedLocalHash: String?,
    ): ApplyOutcome {
        val current = domain.playlistBackup(key)
        val currentHash = current?.let(::projection).hashOrNull()
        val row = rowOf(key, payload, keepProfileId = current?.sourceProfileId)
            ?: return ApplyOutcome.Skipped(SkipReason.UNSUPPORTED, currentHash)
        if (currentHash != expectedLocalHash) return ApplyOutcome.Skipped(SkipReason.CHANGED_LOCALLY, currentHash)
        domain.putPlaylistBackup(row).orFailWrite("putPlaylistBackup")
        return ApplyOutcome.Applied(domain.playlistBackup(key)?.let(::projection).hashOrNull())
    }

    override suspend fun delete(localProfileId: String?, key: String, expectedLocalHash: String?): ApplyOutcome {
        val current = domain.playlistBackup(key) ?: return ApplyOutcome.Applied(null)
        val currentHash = projection(current).hashOrNull()
        if (currentHash != expectedLocalHash) return ApplyOutcome.Skipped(SkipReason.CHANGED_LOCALLY, currentHash)
        domain.deletePlaylistBackup(key).orFailWrite("deletePlaylistBackup")
        return ApplyOutcome.Applied(null)
    }

    private fun projection(row: PlaylistBackupRow) = JsonObject(
        mapOf(
            NAME to jsonString(row.name),
            SOURCE_PROVIDER to jsonString(row.sourceProvider),
            SOURCE_PROFILE_LABEL to jsonString(row.sourceProfileLabel),
            SOURCE_PLAYLIST_ID to jsonString(row.sourcePlaylistId),
            PLAYLIST_CREATED_AT to jsonString(row.playlistCreatedAt),
            FIRST_BACKED_UP_AT to jsonLong(row.firstBackedUpAt),
            LAST_BACKED_UP_AT to jsonLong(row.lastBackedUpAt),
            LAST_CHANGED_AT to jsonLong(row.lastChangedAt),
            SOURCE_GONE_AT to jsonLong(row.sourceGoneAt),
            UPDATED_AT to jsonLong(row.updatedAt),
            CONTENT to jsonString(row.contentJson),
            VERSIONS to jsonString(row.versionsJson),
            RESTORES to jsonString(row.restoresJson),
        ),
    )

    /** The row [payload] describes, or null when a field is missing or its JSON doesn't decode. */
    private fun rowOf(key: String, payload: JsonObject, keepProfileId: String? = null): PlaylistBackupRow? {
        val contentJson = payload.requiredString(CONTENT) ?: return null
        val restoresJson = payload.requiredString(RESTORES) ?: return null
        PlaylistBackupCodec.decodeContentOrNull(contentJson) ?: return null
        PlaylistBackupCodec.decodeRestoresOrNull(restoresJson) ?: return null
        // Older payloads had no versions: none, then.
        val versionsJson = payload.optionalString(VERSIONS).orElse { return null } ?: "[]"
        PlaylistBackupCodec.decodeVersionsOrNull(versionsJson) ?: return null
        return PlaylistBackupRow(
            id = key,
            name = payload.requiredString(NAME) ?: return null,
            sourceProvider = payload.requiredString(SOURCE_PROVIDER)?.takeIf(String::isNotEmpty) ?: return null,
            sourceProfileLabel = payload.requiredString(SOURCE_PROFILE_LABEL) ?: return null,
            sourcePlaylistId = payload.requiredString(SOURCE_PLAYLIST_ID) ?: return null,
            // This device's own: kept from the local row (apply passes it in), never synced.
            sourceProfileId = keepProfileId,
            playlistCreatedAt = payload.optionalString(PLAYLIST_CREATED_AT).orElse { return null },
            firstBackedUpAt = payload.requiredLong(FIRST_BACKED_UP_AT) ?: return null,
            lastBackedUpAt = payload.requiredLong(LAST_BACKED_UP_AT) ?: return null,
            lastChangedAt = payload.requiredLong(LAST_CHANGED_AT) ?: return null,
            sourceGoneAt = payload.optionalLong(SOURCE_GONE_AT).orElse { return null },
            contentJson = contentJson,
            versionsJson = versionsJson,
            restoresJson = restoresJson,
            updatedAt = payload.requiredLong(UPDATED_AT) ?: return null,
        )
    }

    private companion object {
        const val KEY_UNKNOWN = ""
        const val NAME = "name"
        const val SOURCE_PROVIDER = "sourceProvider"
        const val SOURCE_PROFILE_LABEL = "sourceProfileLabel"
        const val SOURCE_PLAYLIST_ID = "sourcePlaylistId"
        const val PLAYLIST_CREATED_AT = "playlistCreatedAt"
        const val FIRST_BACKED_UP_AT = "firstBackedUpAt"
        const val LAST_BACKED_UP_AT = "lastBackedUpAt"
        const val LAST_CHANGED_AT = "lastChangedAt"
        const val SOURCE_GONE_AT = "sourceGoneAt"
        const val UPDATED_AT = "updatedAt"
        const val CONTENT = "content"
        const val RESTORES = "restores"
        const val VERSIONS = "versions"
    }
}
