package com.gpo.yoin.data.sync.backup

import com.gpo.yoin.data.local.Profile
import com.gpo.yoin.data.local.YoinDatabase
import com.gpo.yoin.data.profile.ProfileCredentials
import com.gpo.yoin.data.sync.CanonicalJson
import com.gpo.yoin.data.sync.DomainRow
import com.gpo.yoin.data.sync.SyncAdapter
import com.gpo.yoin.data.sync.SyncBindingEntity
import com.gpo.yoin.data.sync.SyncDatabase
import com.gpo.yoin.data.sync.SyncEnvelope
import com.gpo.yoin.data.sync.SyncFormat
import com.gpo.yoin.data.sync.SyncKinds
import com.gpo.yoin.data.sync.SyncLocalStateEntity
import com.gpo.yoin.data.sync.SyncMetaKeys
import com.gpo.yoin.data.sync.WireRecord
import com.gpo.yoin.data.sync.engine.toWire
import com.gpo.yoin.data.sync.identity.AccountDescriptorFields
import com.gpo.yoin.data.sync.identity.AccountFingerprints
import com.gpo.yoin.data.sync.identity.CloudAccountDescriptor
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** Everything one backup holds, before it is written out. */
data class BackupContent(
    /** All records, sorted by (kind, scope, key); [SyncEnvelope.fileClass] = [BackupFormat.SNAPSHOT_FILE_CLASS]. */
    val snapshot: SyncEnvelope,
    /** One per scope: this device's profiles first (oldest first), then accounts only known from Drive. */
    val accounts: List<BackupAccount>,
    /** Local profile id behind each on-device account's scope. */
    val localProfiles: Map<String, String>,
    /** Kinds whose domain table couldn't be read (their replica copy, if any, was used instead). */
    val readFailures: List<String>,
)

/**
 * Builds a backup's record set: what this device's sync engine would publish if it synced right now, plus
 * the accounts it can't sync. Lossless towards the replica — every replica record (all scopes, tombstones,
 * kinds this build doesn't know) is carried with its version — and overlaid with the live domain:
 *
 *  - a domain row whose projection equals its record keeps the record (and its version);
 *  - a row edited since it was last synced, or one the replica never had, becomes a new version from this
 *    device, decided the way capture decides it (tracked edit: after the record; untracked: last writer wins
 *    on the row's own time). Concurrent text is not merged here; the next sync still does that;
 *  - a tracked row of a delete-propagating kind (song notes, album titles) that is gone becomes a tombstone.
 *
 * With no replica (Cloud sync never turned on) the record set is just the domain's projections. Profiles
 * that aren't bound get the scope a binding would get (Subsonic / known Spotify user), else a device-local
 * scope [BackupFormat.LOCAL_SCOPE_PREFIX]. Album notes and track→album links ride along as [BackupKinds].
 * Nothing outside the sync allowlist is ever read: every value comes from an adapter projection, a replica
 * payload (already allowlisted) or the same descriptor fields sync publishes.
 */
class SnapshotBuilder(
    private val db: YoinDatabase,
    private val adapters: List<SyncAdapter>,
    private val replica: SyncDatabase?,
    private val profiles: suspend () -> List<Profile>,
    private val decodeCredentials: (Profile) -> ProfileCredentials?,
    private val deviceId: String,
    private val deviceName: String,
    private val appVersionCode: Int,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private data class Key(val kind: String, val scope: String, val key: String)

    suspend fun build(): BackupContent {
        val now = clock()
        val dao = replica?.syncDao()
        val out = LinkedHashMap<Key, WireRecord>()
        dao?.allRecords()?.forEach { out[Key(it.kind, it.scope, it.key)] = it.toWire() }
        val failures = ArrayList<String>()
        val accounts = ArrayList<BackupAccount>()
        val localProfiles = LinkedHashMap<String, String>()
        val usedScopes = HashSet<String>()

        for (profile in profiles().sortedWith(compareBy(Profile::createdAt, Profile::id))) {
            val binding = dao?.bindingFor(profile.id)
            val credentials = runCatching { decodeCredentials(profile) }.getOrNull()
            var scope = binding?.takeIf { it.state != SyncBindingEntity.STATE_PAUSED_MISMATCH }?.syncProfileId
                ?: AccountScopes.derived(profile, credentials, dao)
            if (!usedScopes.add(scope)) {
                // A second profile of one account: never merge two profiles' rows into one scope.
                scope = BackupFormat.LOCAL_SCOPE_PREFIX + profile.id
                usedScopes.add(scope)
            }
            val bound = binding?.state == SyncBindingEntity.STATE_ACTIVE && binding.syncProfileId == scope
            val states = if (bound) {
                dao!!.localStatesInScope(scope).filter { it.boundProfileId == profile.id }.groupBy { it.kind }
            } else {
                emptyMap()
            }
            for (adapter in adapters.filter { it.perAccount }) {
                val rows = readOrNull(adapter, profile.id, failures) ?: continue
                overlay(adapter, scope, rows, states[adapter.kind].orEmpty().associateBy { it.key }, out, now)
            }
            addBackupKinds(profile.id, scope, out)
            val descriptor = descriptorOf(out[Key(SyncKinds.ACCOUNT, SyncFormat.GLOBAL_SCOPE, scope)])
            val hint = (credentials as? ProfileCredentials.Subsonic)
                ?.let { AccountFingerprints.subsonicHint(it.serverUrl, it.username) }
                ?: descriptor?.hint
            if (descriptor == null || descriptor.displayName != profile.displayName) {
                out[Key(SyncKinds.ACCOUNT, SyncFormat.GLOBAL_SCOPE, scope)] =
                    descriptorRecord(profile, scope, hint, descriptor?.storefront, out, now)
            }
            accounts += BackupAccount(
                scope = scope,
                provider = profile.provider,
                displayName = profile.displayName,
                hint = hint,
                storefront = descriptor?.storefront,
                onDevice = true,
            )
            localProfiles[scope] = profile.id
        }

        val globalStates = dao?.localStatesInScope(SyncFormat.GLOBAL_SCOPE).orEmpty()
            .filter { it.boundProfileId.isEmpty() }
            .groupBy { it.kind }
        for (adapter in adapters.filter { !it.perAccount && it.kind != SyncKinds.ACCOUNT }) {
            val rows = readOrNull(adapter, null, failures) ?: continue
            overlay(adapter, SyncFormat.GLOBAL_SCOPE, rows, globalStates[adapter.kind].orEmpty().associateBy { it.key }, out, now)
        }

        // Accounts this device only knows from Drive (cloud-only), described by their descriptor if any.
        val descriptors = out.values
            .filter { it.kind == SyncKinds.ACCOUNT && it.scope == SyncFormat.GLOBAL_SCOPE && !it.deleted }
            .associateBy { it.key }
        val otherScopes = out.keys.asSequence()
            .map { it.scope }
            .filter { it != SyncFormat.GLOBAL_SCOPE && it !in usedScopes }
            .toSortedSet() + descriptors.keys.filter { it !in usedScopes }
        for (scope in otherScopes.toSortedSet()) {
            val descriptor = descriptorOf(descriptors[scope])
            accounts += BackupAccount(
                scope = scope,
                provider = descriptor?.provider ?: providerFromRecords(scope, out.values) ?: "unknown",
                displayName = descriptor?.displayName?.takeIf(String::isNotBlank) ?: scope,
                hint = descriptor?.hint,
                storefront = descriptor?.storefront,
                onDevice = false,
            )
        }

        val purges = dao?.metaWithPrefix(SyncMetaKeys.PURGED_SCOPE_PREFIX).orEmpty()
            .filter { it.key.startsWith(SyncMetaKeys.PURGED_SCOPE_PREFIX) }
            .mapNotNull { e -> e.value.toLongOrNull()?.let { e.key.removePrefix(SyncMetaKeys.PURGED_SCOPE_PREFIX) to it } }
            .toMap()
            .takeIf { it.isNotEmpty() }
        val snapshot = SyncEnvelope(
            deviceId = deviceId,
            deviceName = deviceName,
            fileClass = BackupFormat.SNAPSHOT_FILE_CLASS,
            writtenAt = now,
            appVersionCode = appVersionCode,
            records = out.values.sortedWith(compareBy<WireRecord>({ it.kind }, { it.scope }, { it.key })),
            purges = purges,
        )
        return BackupContent(snapshot, accounts, localProfiles, failures)
    }

    private suspend fun readOrNull(adapter: SyncAdapter, profileId: String?, failures: MutableList<String>): List<DomainRow>? =
        try {
            adapter.readAll(profileId)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            failures += adapter.kind
            null
        }

    private fun overlay(
        adapter: SyncAdapter,
        scope: String,
        rows: List<DomainRow>,
        states: Map<String, SyncLocalStateEntity>,
        out: MutableMap<Key, WireRecord>,
        now: Long,
    ) {
        val present = HashSet<String>()
        for (row in rows) {
            if (!present.add(row.key)) continue
            val key = Key(adapter.kind, scope, row.key)
            val record = out[key]
            if (record != null && record.kindVersion > adapter.kindVersion) continue // newer kind: carried as is
            val rowHash = CanonicalJson.hash(row.projection)
            val recordProjection = record?.takeUnless { it.deleted }?.payload?.let { safeProject(adapter, it) }
            if (recordProjection != null && CanonicalJson.hash(recordProjection) == rowHash) continue
            val tracked = states[row.key]?.localHash
            val ts = when {
                record == null -> row.rowTs ?: now
                // Edited since this device last synced it: a new version after the record.
                tracked != null -> if (rowHash != tracked) maxOf(now, record.ts + 1) else continue
                // Never synced here: the row's own time against the record's (capture's join rule).
                record.deleted && !adapter.propagatesDeletes -> maxOf(row.rowTs ?: now, record.ts + 1)
                (row.rowTs ?: 0L) > record.ts -> row.rowTs ?: now
                else -> continue
            }
            out[key] = WireRecord(
                kind = adapter.kind,
                kindVersion = adapter.kindVersion,
                scope = scope,
                key = row.key,
                ts = ts,
                device = deviceId,
                prevTs = record?.ts,
                prevDevice = record?.device,
                deleted = false,
                payload = adapter.decoratePayload(mergedPayload(adapter, record, row.projection), deviceName),
            )
        }
        if (!adapter.propagatesDeletes) return
        for ((rowKey, state) in states) {
            if (rowKey in present || state.localHash == null) continue
            val key = Key(adapter.kind, scope, rowKey)
            val record = out[key] ?: continue
            if (record.deleted || record.kindVersion > adapter.kindVersion) continue
            out[key] = WireRecord(
                kind = adapter.kind,
                kindVersion = adapter.kindVersion,
                scope = scope,
                key = rowKey,
                ts = maxOf(now, record.ts + 1),
                device = deviceId,
                prevTs = record.ts,
                prevDevice = record.device,
                deleted = true,
                payload = null,
            )
        }
    }

    private suspend fun addBackupKinds(profileId: String, scope: String, out: MutableMap<Key, WireRecord>) {
        val dao = db.syncDomainDao()
        for (note in dao.albumNotesForProfile(profileId)) {
            out[Key(BackupKinds.ALBUM_NOTE, scope, note.id)] = WireRecord(
                kind = BackupKinds.ALBUM_NOTE,
                scope = scope,
                key = note.id,
                ts = note.updatedAt,
                device = deviceId,
                payload = JsonObject(
                    mapOf(
                        BackupFields.ALBUM_ID to JsonPrimitive(note.albumId),
                        BackupFields.PROVIDER to JsonPrimitive(note.provider),
                        BackupFields.CONTENT to JsonPrimitive(note.content),
                        BackupFields.CREATED_AT to JsonPrimitive(note.createdAt),
                        BackupFields.UPDATED_AT to JsonPrimitive(note.updatedAt),
                        BackupFields.ALBUM_NAME to JsonPrimitive(note.albumName),
                        BackupFields.ARTIST to JsonPrimitive(note.artist),
                    ),
                ),
            )
        }
        for (link in dao.trackAlbumLinksForProfile(profileId)) {
            val key = SyncFormat.compositeKey(link.provider, link.trackId)
            out[Key(BackupKinds.TRACK_ALBUM_LINK, scope, key)] = WireRecord(
                kind = BackupKinds.TRACK_ALBUM_LINK,
                scope = scope,
                key = key,
                ts = link.updatedAt,
                device = deviceId,
                payload = JsonObject(
                    mapOf(
                        BackupFields.PROVIDER to JsonPrimitive(link.provider),
                        BackupFields.TRACK_ID to JsonPrimitive(link.trackId),
                        BackupFields.ALBUM_ID to JsonPrimitive(link.albumId),
                        BackupFields.SOURCE to JsonPrimitive(link.source),
                        BackupFields.UPDATED_AT to JsonPrimitive(link.updatedAt),
                    ),
                ),
            )
        }
    }

    private fun descriptorRecord(
        profile: Profile,
        scope: String,
        hint: String?,
        storefront: String?,
        out: Map<Key, WireRecord>,
        now: Long,
    ): WireRecord {
        val previous = out[Key(SyncKinds.ACCOUNT, SyncFormat.GLOBAL_SCOPE, scope)]
        val payload = JsonObject(
            mapOf(
                AccountDescriptorFields.PROVIDER to JsonPrimitive(profile.provider),
                AccountDescriptorFields.DISPLAY_NAME to JsonPrimitive(profile.displayName),
                AccountDescriptorFields.CREATED_AT to JsonPrimitive(profile.createdAt),
                AccountDescriptorFields.HINT to (hint?.let(::JsonPrimitive) ?: JsonNull),
                AccountDescriptorFields.STOREFRONT to (storefront?.let(::JsonPrimitive) ?: JsonNull),
                AccountDescriptorFields.DEVICE_NAME to JsonPrimitive(deviceName),
            ),
        )
        return WireRecord(
            kind = SyncKinds.ACCOUNT,
            scope = SyncFormat.GLOBAL_SCOPE,
            key = scope,
            ts = if (previous == null) profile.createdAt else maxOf(now, previous.ts + 1),
            device = deviceId,
            prevTs = previous?.ts,
            prevDevice = previous?.device,
            payload = payload,
        )
    }

    private fun descriptorOf(record: WireRecord?): CloudAccountDescriptor? {
        val payload = record?.takeUnless { it.deleted }?.payload ?: return null
        return CloudAccountDescriptor.fromPayload(record.key, payload)
    }

    private fun providerFromRecords(scope: String, records: Collection<WireRecord>): String? = records
        .firstOrNull { it.scope == scope && !it.deleted }
        ?.payload?.get("provider")
        ?.let { (it as? JsonPrimitive)?.takeIf(JsonPrimitive::isString)?.content }

    private fun safeProject(adapter: SyncAdapter, payload: JsonObject): JsonObject? = try {
        adapter.project(payload)
    } catch (e: RuntimeException) {
        null
    }

    /** The record's payload with [projection] written over it (unknown keys survive), as capture builds it. */
    private fun mergedPayload(adapter: SyncAdapter, base: WireRecord?, projection: JsonObject): JsonObject {
        val existing = base?.takeUnless { it.deleted }?.payload ?: return projection
        val merged = LinkedHashMap<String, JsonElement>(existing)
        safeProject(adapter, existing)?.keys?.forEach { key -> if (key !in projection) merged.remove(key) }
        merged.putAll(projection)
        return JsonObject(merged)
    }
}
