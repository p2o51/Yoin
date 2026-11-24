package com.gpo.yoin.data.sync.backup

import com.gpo.yoin.data.local.Profile
import com.gpo.yoin.data.local.TrackAlbumLink
import com.gpo.yoin.data.local.YoinDatabase
import com.gpo.yoin.data.model.MediaId
import com.gpo.yoin.data.profile.ProfileCredentials
import com.gpo.yoin.data.sync.ApplyOutcome
import com.gpo.yoin.data.sync.SyncAdapter
import com.gpo.yoin.data.sync.SyncBindingEntity
import com.gpo.yoin.data.sync.SyncDao
import com.gpo.yoin.data.sync.SyncEnvelope
import com.gpo.yoin.data.sync.SyncLocalStateEntity
import com.gpo.yoin.data.sync.SyncMetaKeys
import com.gpo.yoin.data.sync.WireRecord
import com.gpo.yoin.data.sync.engine.SyncEngine
import com.gpo.yoin.data.sync.engine.toWire
import com.gpo.yoin.shared.portability.BackupDomain
import com.gpo.yoin.shared.portability.BackupReplica
import com.gpo.yoin.shared.portability.BackupTrackAlbumLink
import com.gpo.yoin.shared.sync.identity.SyncProfile
import kotlinx.serialization.json.JsonObject
import com.gpo.yoin.shared.sync.ApplyOutcome as SharedApplyOutcome
import com.gpo.yoin.shared.sync.ConflictPolicy as SharedConflictPolicy
import com.gpo.yoin.shared.sync.DomainRow as SharedDomainRow
import com.gpo.yoin.shared.sync.FileClass as SharedFileClass
import com.gpo.yoin.shared.sync.SkipReason as SharedSkipReason
import com.gpo.yoin.shared.sync.SyncAdapter as SharedSyncAdapter
import com.gpo.yoin.shared.sync.SyncBindingEntity as SharedBindingEntity
import com.gpo.yoin.shared.sync.SyncEnvelope as SharedEnvelope
import com.gpo.yoin.shared.sync.SyncLocalStateEntity as SharedLocalStateEntity
import com.gpo.yoin.shared.sync.WireRecord as SharedWireRecord

/*
 * Android's side of the :shared backup core (com.gpo.yoin.shared.portability): the app keeps its own sync
 * engine, adapters and Room tables, and hands them to the shared rules through these small bridges. The wire
 * types are the same JSON on both sides, so every conversion here is field for field.
 */

internal fun WireRecord.toShared(): SharedWireRecord =
    SharedWireRecord(kind, kindVersion, scope, key, ts, device, prevTs, prevDevice, deleted, payload)

internal fun SharedWireRecord.toApp(): WireRecord =
    WireRecord(kind, kindVersion, scope, key, ts, device, prevTs, prevDevice, deleted, payload)

internal fun SyncEnvelope.toShared(): SharedEnvelope = SharedEnvelope(
    format = format,
    schema = schema,
    epoch = epoch,
    deviceId = deviceId,
    deviceName = deviceName,
    fileClass = fileClass,
    shard = shard,
    writtenAt = writtenAt,
    appVersionCode = appVersionCode,
    records = records.map { it.toShared() },
    purges = purges,
)

internal fun SharedEnvelope.toApp(): SyncEnvelope = SyncEnvelope(
    format = format,
    schema = schema,
    epoch = epoch,
    deviceId = deviceId,
    deviceName = deviceName,
    fileClass = fileClass,
    shard = shard,
    writtenAt = writtenAt,
    appVersionCode = appVersionCode,
    records = records.map { it.toApp() },
    purges = purges,
)

private fun SyncBindingEntity.toShared() = SharedBindingEntity(
    localProfileId = localProfileId,
    syncProfileId = syncProfileId,
    provider = provider,
    fingerprintHash = fingerprintHash,
    state = state,
    observedFingerprintHash = observedFingerprintHash,
    boundAt = boundAt,
)

private fun SyncLocalStateEntity.toShared() = SharedLocalStateEntity(
    kind = kind,
    scope = scope,
    key = key,
    boundProfileId = boundProfileId,
    localHash = localHash,
    appliedTs = appliedTs,
    appliedDevice = appliedDevice,
    pending = pending,
    pendingPrevTs = pendingPrevTs,
    pendingPrevDevice = pendingPrevDevice,
)

private fun ApplyOutcome.toShared(): SharedApplyOutcome = when (this) {
    is ApplyOutcome.Applied -> SharedApplyOutcome.Applied(localHash)
    is ApplyOutcome.Skipped -> SharedApplyOutcome.Skipped(SharedSkipReason.valueOf(reason.name), localHash)
}

/** One of the app's sync adapters as the shared core sees it. */
internal class SharedAdapterBridge(private val adapter: SyncAdapter) : SharedSyncAdapter {
    override val kind: String get() = adapter.kind
    override val kindVersion: Int get() = adapter.kindVersion
    override val fileClass: SharedFileClass get() = SharedFileClass.valueOf(adapter.fileClass.name)
    override val perAccount: Boolean get() = adapter.perAccount
    override val propagatesDeletes: Boolean get() = adapter.propagatesDeletes
    override val conflictPolicy: SharedConflictPolicy get() = SharedConflictPolicy.valueOf(adapter.conflictPolicy.name)

    override suspend fun readAll(localProfileId: String?): List<SharedDomainRow> =
        adapter.readAll(localProfileId).map { SharedDomainRow(it.key, it.projection, it.rowTs) }

    override fun project(payload: JsonObject): JsonObject? = adapter.project(payload)

    override suspend fun apply(
        localProfileId: String?,
        key: String,
        payload: JsonObject,
        versionTs: Long,
        expectedLocalHash: String?,
    ): SharedApplyOutcome = adapter.apply(localProfileId, key, payload, versionTs, expectedLocalHash).toShared()

    override suspend fun delete(localProfileId: String?, key: String, expectedLocalHash: String?): SharedApplyOutcome =
        adapter.delete(localProfileId, key, expectedLocalHash).toShared()

    override fun mergeConcurrent(winner: JsonObject, loser: JsonObject, loserLabel: String): JsonObject? =
        adapter.mergeConcurrent(winner, loser, loserLabel)

    override fun decoratePayload(payload: JsonObject, deviceName: String): JsonObject = adapter.decoratePayload(payload, deviceName)
}

internal fun List<SyncAdapter>.toShared(): List<SharedSyncAdapter> = map(::SharedAdapterBridge)

/** The Room sync replica ([SyncDao]) as a [BackupReplica]; cloud-only imports merge through [engine]. */
internal class RoomBackupReplica(private val dao: SyncDao, private val engine: SyncEngine?) : BackupReplica {
    override suspend fun allRecords(): List<SharedWireRecord> = dao.allRecords().map { it.toWire().toShared() }

    override suspend fun recordsInScope(scope: String): List<SharedWireRecord> = dao.recordsInScope(scope).map { it.toWire().toShared() }

    override suspend fun recordsOfKind(kind: String): List<SharedWireRecord> = dao.recordsOfKind(kind).map { it.toWire().toShared() }

    override suspend fun record(kind: String, scope: String, key: String): SharedWireRecord? =
        dao.getRecord(kind, scope, key)?.toWire()?.toShared()

    override suspend fun bindingFor(localProfileId: String): SharedBindingEntity? = dao.bindingFor(localProfileId)?.toShared()

    override suspend fun bindingForScope(syncProfileId: String): SharedBindingEntity? = dao.bindingForScope(syncProfileId)?.toShared()

    override suspend fun localStatesInScope(scope: String): List<SharedLocalStateEntity> = dao.localStatesInScope(scope).map { it.toShared() }

    override suspend fun metaWithPrefix(prefix: String): Map<String, String> =
        dao.metaWithPrefix(prefix).associateTo(LinkedHashMap()) { it.key to it.value }

    override suspend fun meta(key: String): String? = dao.meta(key)

    override val canMergeImported: Boolean get() = engine != null

    override suspend fun mergeImported(envelope: SharedEnvelope): Int {
        val result = requireNotNull(engine) { "No sync engine" }.mergeImported(envelope.toApp())
        return result.inserted + result.replaced + result.keptBoth
    }
}

/** Track→album links and note owners from Room. */
internal class RoomBackupDomain(private val db: YoinDatabase) : BackupDomain {
    override suspend fun trackAlbumLinks(localProfileId: String): List<BackupTrackAlbumLink> =
        db.syncDomainDao().trackAlbumLinksForProfile(localProfileId).map { it.toShared() }

    override suspend fun trackAlbumLink(localProfileId: String, provider: String, trackId: String): BackupTrackAlbumLink? =
        db.syncDomainDao().trackAlbumLink(localProfileId, provider, trackId)?.toShared()

    override suspend fun putTrackAlbumLink(localProfileId: String, link: BackupTrackAlbumLink) {
        db.syncDomainDao().upsertTrackAlbumLink(
            TrackAlbumLink(
                profileId = localProfileId,
                provider = link.provider,
                trackId = link.trackId,
                albumId = link.albumId,
                source = link.source,
                updatedAt = link.updatedAt,
            ),
        )
    }

    override suspend fun songNoteOwner(id: String): String? = db.syncDomainDao().noteById(id)?.profileId

    override suspend fun albumNoteOwner(id: String): String? = db.syncDomainDao().albumNoteById(id)?.profileId

    private fun TrackAlbumLink.toShared() = BackupTrackAlbumLink(provider, trackId, albumId, source, updatedAt)
}

/**
 * A Room profile as the shared core sees an account: no secrets, only what scopes and hints are made of.
 * [dao] is the sync replica; without one this device knows no Spotify user id (as before the move).
 */
internal suspend fun syncProfileOf(profile: Profile, credentials: ProfileCredentials?, dao: SyncDao?): SyncProfile {
    val subsonic = credentials as? ProfileCredentials.Subsonic
    val spotifyUserId = if (profile.provider == MediaId.PROVIDER_SPOTIFY) {
        dao?.meta(SyncMetaKeys.SPOTIFY_UID_PREFIX + profile.id)?.substringBefore('|')
    } else {
        null
    }
    return SyncProfile(
        id = profile.id,
        provider = profile.provider,
        displayName = profile.displayName,
        createdAt = profile.createdAt,
        subsonicServerUrl = subsonic?.serverUrl,
        subsonicUsername = subsonic?.username,
        spotifyUserId = spotifyUserId,
    )
}

internal suspend fun syncProfilesOf(
    profiles: List<Profile>,
    decodeCredentials: (Profile) -> ProfileCredentials?,
    dao: SyncDao?,
): List<SyncProfile> = profiles.map { syncProfileOf(it, runCatching { decodeCredentials(it) }.getOrNull(), dao) }
