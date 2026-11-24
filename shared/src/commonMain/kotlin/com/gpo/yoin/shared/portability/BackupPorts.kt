package com.gpo.yoin.shared.portability

import com.gpo.yoin.shared.sync.SyncBindingEntity
import com.gpo.yoin.shared.sync.SyncEnvelope
import com.gpo.yoin.shared.sync.SyncFormat
import com.gpo.yoin.shared.sync.SyncLocalStateEntity
import com.gpo.yoin.shared.sync.SyncMetaKeys
import com.gpo.yoin.shared.sync.SyncStore
import com.gpo.yoin.shared.sync.WireRecord
import com.gpo.yoin.shared.sync.engine.SyncEngine
import com.gpo.yoin.shared.sync.engine.toWire
import com.gpo.yoin.shared.sync.identity.AccountFingerprints
import com.gpo.yoin.shared.sync.identity.SyncProfile

/*
 * The small ports the backup / transfer core reads and writes through. Everything else (the zip, file
 * pickers, the databases, the UI) stays on each platform: Android implements these with its Room DAOs
 * (app/.../data/sync/backup/SharedBackupBridge.kt), iOS with the shared SyncStore + SyncDomain
 * ([StoreBackupReplica], [YoinBackup]).
 */

/**
 * The sync replica (the sync side database) as backups and transfers see it. Callers hold the sync lock
 * while they use it; a device where Cloud sync never ran passes no replica at all.
 */
interface BackupReplica {
    suspend fun allRecords(): List<WireRecord>

    suspend fun recordsInScope(scope: String): List<WireRecord>

    suspend fun recordsOfKind(kind: String): List<WireRecord>

    suspend fun record(kind: String, scope: String, key: String): WireRecord?

    suspend fun bindingFor(localProfileId: String): SyncBindingEntity?

    suspend fun bindingForScope(syncProfileId: String): SyncBindingEntity?

    suspend fun localStatesInScope(scope: String): List<SyncLocalStateEntity>

    /** sync_meta rows whose key starts with [prefix]: key -> value. */
    suspend fun metaWithPrefix(prefix: String): Map<String, String>

    suspend fun meta(key: String): String?

    /** false when nothing can be merged into the replica (no sync engine here): cloud-only imports are refused. */
    val canMergeImported: Boolean

    /** Merges a backup's cloud-only records like a peer's file; the records inserted, replaced or kept both. */
    suspend fun mergeImported(envelope: SyncEnvelope): Int
}

/** `track_album_links`: which album a song is on, as a backup carries it ([BackupKinds.TRACK_ALBUM_LINK]). */
data class BackupTrackAlbumLink(
    val provider: String,
    val trackId: String,
    val albumId: String,
    /** [SOURCE_PLAY], [SOURCE_DETAIL] or [SOURCE_IMPORT]. */
    val source: String,
    val updatedAt: Long,
) {
    companion object {
        const val SOURCE_PLAY = "play"
        const val SOURCE_DETAIL = "detail"
        const val SOURCE_IMPORT = "import"
    }
}

/** What backups read and write in the app's own data besides the sync adapters. */
interface BackupDomain {
    suspend fun trackAlbumLinks(localProfileId: String): List<BackupTrackAlbumLink>

    suspend fun trackAlbumLink(localProfileId: String, provider: String, trackId: String): BackupTrackAlbumLink?

    suspend fun putTrackAlbumLink(localProfileId: String, link: BackupTrackAlbumLink)

    /** The local profile that owns the song note with this (global) id, or null when there is none. */
    suspend fun songNoteOwner(id: String): String?

    /** The local profile that owns the album note with this (global) id, or null when there is none. */
    suspend fun albumNoteOwner(id: String): String?
}

/**
 * Which scope a local profile's data has in backups and transfers: its sync binding's scope, else the one a
 * binding would get (Subsonic fingerprint, a Spotify user id this device already resolved), else the
 * device-local [BackupFormat.LOCAL_SCOPE_PREFIX] + profile id. No network.
 */
object BackupScopes {
    suspend fun of(profile: SyncProfile, replica: BackupReplica?): String {
        val binding = replica?.bindingFor(profile.id)
        if (binding != null && binding.state != SyncBindingEntity.STATE_PAUSED_MISMATCH) return binding.syncProfileId
        return derived(profile)
    }

    /**
     * The scope a binding would give [profile]. [SyncProfile.spotifyUserId] is the id this device resolved
     * before (Android: sync_meta `spotify_uid:<profile>`), never looked up here.
     */
    fun derived(profile: SyncProfile): String {
        subsonicFingerprint(profile)?.let { return AccountFingerprints.deterministicId(it) }
        if (profile.provider == PROVIDER_SPOTIFY) {
            val userId = profile.spotifyUserId
            if (!userId.isNullOrEmpty()) return AccountFingerprints.deterministicId(AccountFingerprints.spotify(userId))
        }
        return BackupFormat.LOCAL_SCOPE_PREFIX + profile.id
    }

    /** "user @ host[:port]" of a Subsonic profile, else null. */
    fun subsonicHint(profile: SyncProfile): String? {
        val url = profile.subsonicServerUrl ?: return null
        val user = profile.subsonicUsername ?: return null
        return AccountFingerprints.subsonicHint(url, user)
    }

    /** "host[:port]" of a Subsonic "user @ host[:port]" hint, lowercased; null for anything else. */
    fun serverKeyOf(hint: String?): String? =
        hint?.substringAfter(" @ ", "")?.trim()?.lowercase()?.takeIf(String::isNotEmpty)

    private fun subsonicFingerprint(profile: SyncProfile): String? {
        val url = profile.subsonicServerUrl ?: return null
        val user = profile.subsonicUsername ?: return null
        return AccountFingerprints.subsonic(url, user)
    }

    private const val PROVIDER_SPOTIFY = "spotify"
}

/** [BackupReplica] over the shared [SyncStore] (iOS's replica), merging through [engine] when given. */
class StoreBackupReplica(
    private val store: SyncStore,
    private val engine: SyncEngine?,
) : BackupReplica {
    override suspend fun allRecords(): List<WireRecord> = store.allRecords().map { it.toWire() }

    override suspend fun recordsInScope(scope: String): List<WireRecord> = store.recordsInScope(scope).map { it.toWire() }

    override suspend fun recordsOfKind(kind: String): List<WireRecord> = store.recordsOfKind(kind).map { it.toWire() }

    override suspend fun record(kind: String, scope: String, key: String): WireRecord? = store.getRecord(kind, scope, key)?.toWire()

    override suspend fun bindingFor(localProfileId: String): SyncBindingEntity? = store.bindingFor(localProfileId)

    override suspend fun bindingForScope(syncProfileId: String): SyncBindingEntity? = store.bindingForScope(syncProfileId)

    override suspend fun localStatesInScope(scope: String): List<SyncLocalStateEntity> = store.localStatesInScope(scope)

    override suspend fun metaWithPrefix(prefix: String): Map<String, String> =
        store.metaWithPrefix(prefix).filter { it.key.startsWith(prefix) }.associate { it.key to it.value }

    override suspend fun meta(key: String): String? = store.meta(key)

    override val canMergeImported: Boolean get() = engine != null

    override suspend fun mergeImported(envelope: SyncEnvelope): Int {
        val result = requireNotNull(engine) { "No sync engine" }.mergeImported(envelope)
        return result.inserted + result.replaced + result.keptBoth
    }
}

/** Scope purges this device applied: scope -> epoch (sync_meta `purged_scope:<scope>`). */
internal suspend fun BackupReplica.purges(): Map<String, Long> = metaWithPrefix(SyncMetaKeys.PURGED_SCOPE_PREFIX)
    .filter { it.key.startsWith(SyncMetaKeys.PURGED_SCOPE_PREFIX) }
    .mapNotNull { (key, value) -> value.toLongOrNull()?.let { key.removePrefix(SyncMetaKeys.PURGED_SCOPE_PREFIX) to it } }
    .toMap()

internal fun isGlobal(scope: String): Boolean = scope == SyncFormat.GLOBAL_SCOPE
