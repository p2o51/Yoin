package com.gpo.yoin.data.sync.backup

import android.content.Context
import com.gpo.yoin.data.local.Profile
import com.gpo.yoin.data.local.YoinDatabase
import com.gpo.yoin.data.profile.ProfileCredentials
import com.gpo.yoin.data.profile.ProfileManager
import com.gpo.yoin.data.sync.CloudSyncManager
import com.gpo.yoin.data.sync.SyncDao
import com.gpo.yoin.data.sync.SyncDatabase
import com.gpo.yoin.data.sync.SyncFormat
import com.gpo.yoin.data.sync.SyncKinds
import com.gpo.yoin.data.sync.adapters.SharedPrefsSeamStyleGateway
import com.gpo.yoin.data.sync.adapters.YoinSyncAdapters
import com.gpo.yoin.data.sync.engine.toWire
import com.gpo.yoin.data.sync.identity.AccountFingerprints
import com.gpo.yoin.data.sync.identity.CloudAccountDescriptor
import com.gpo.yoin.data.transfer.memories.InMemoryTransferLedger
import com.gpo.yoin.data.transfer.memories.MemoryCatalog
import com.gpo.yoin.data.transfer.memories.MemoryItems
import com.gpo.yoin.data.transfer.memories.MemorySource
import com.gpo.yoin.data.transfer.memories.MemoryTransfer
import com.gpo.yoin.data.transfer.memories.SyncMetaTransferLedger
import com.gpo.yoin.data.transfer.memories.TransferAccount
import com.gpo.yoin.data.transfer.memories.TransferPlan
import com.gpo.yoin.data.transfer.memories.TransferResult
import java.io.InputStream
import java.io.OutputStream
import java.time.ZoneId
import java.util.Locale
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * What the 设置 › 同步与备份 UI calls for backups and Memories transfers (docs/cloud-sync.md). Every
 * operation that touches the sync replica runs under the sync lock ([CloudSyncManager.withReplica]); writes
 * land in the domain and are published by Cloud sync like any local edit.
 */
class MemoriesPortability(
    context: Context,
    private val db: YoinDatabase,
    private val profileManager: ProfileManager,
    private val cloudSync: CloudSyncManager,
    private val versionName: String,
    private val versionCode: Int,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val appContext = context.applicationContext

    private suspend fun profiles(): List<Profile> = db.profileDao().getAll()

    private fun credentials(profile: Profile): ProfileCredentials? =
        runCatching { profileManager.decodeCredentials(profile) }.getOrNull()

    // ---- export

    /** `yoin-backup-YYYY-MM-DD.zip` for the SAF "create document" prompt. */
    fun backupFileName(now: Long = clock()): String = BackupFormat.fileName(now)

    /** Writes a full backup to [out] (the caller opens and closes the SAF stream). */
    suspend fun export(
        out: OutputStream,
        labels: ReadableLabels = ReadableLabels.forLocale(Locale.getDefault()),
        zone: ZoneId = ZoneId.systemDefault(),
    ): ExportSummary = withContext(Dispatchers.IO) {
        cloudSync.withReplica { replica ->
            val scratch = if (replica == null) SyncDatabase.inMemory(appContext) else null
            try {
                val adapters = replica?.adapters ?: YoinSyncAdapters.create(
                    db = db,
                    syncDb = scratch!!,
                    seam = SharedPrefsSeamStyleGateway(appContext),
                    decodeCredentials = ::credentials,
                    profiles = ::profiles,
                    clock = clock,
                )
                val deviceId = replica?.deviceId ?: exportDeviceId()
                val deviceName = replica?.deviceName ?: (android.os.Build.MODEL ?: "Android device")
                val content = SnapshotBuilder(
                    db = db,
                    adapters = adapters,
                    replica = replica?.db,
                    profiles = ::profiles,
                    decodeCredentials = ::credentials,
                    deviceId = deviceId,
                    deviceName = deviceName,
                    appVersionCode = versionCode,
                    clock = clock,
                ).build()
                val source = BackupSource(
                    location = when {
                        replica?.enabled != true -> BackupFormat.LOCATION_THIS_DEVICE
                        com.gpo.yoin.edition.EditionFeatures.isLyraOnly -> BackupFormat.LOCATION_LYRA
                        else -> BackupFormat.LOCATION_GOOGLE_DRIVE
                    },
                    deviceId = deviceId,
                    deviceName = deviceName,
                    appVersionName = versionName,
                    appVersionCode = versionCode,
                )
                BackupWriter(DomainReadableNames(db), labels, zone).write(content, source, out)
            } finally {
                scratch?.close()
            }
        }
    }

    // ---- import

    /** Reads and checks a backup and plans where each account goes. @throws BackupReadException */
    suspend fun readBackup(input: InputStream): ImportPlan = withContext(Dispatchers.IO) {
        val backup = BackupReader.read(input)
        cloudSync.withReplica { replica ->
            BackupImporter(db, emptyList(), ::profiles, ::credentials).plan(backup, replica?.db?.syncDao())
        }
    }

    /** Applies [plan] with the user's [choices] (scope -> target); importing the same file again changes nothing. */
    suspend fun import(plan: ImportPlan, choices: Map<String, ImportTarget> = emptyMap()): ImportResult =
        withContext(Dispatchers.IO) {
            val result = cloudSync.withReplica { replica ->
                val scratch = if (replica == null) SyncDatabase.inMemory(appContext) else null
                try {
                    importer(replica?.adapters, scratch).apply(plan, choices, replica?.db?.syncDao(), replica?.engine)
                } finally {
                    scratch?.close()
                }
            }
            if (result.changed) cloudSync.onLocalDataImported()
            result
        }

    private fun importer(adapters: List<com.gpo.yoin.data.sync.SyncAdapter>?, scratch: SyncDatabase?): BackupImporter {
        val list = adapters ?: YoinSyncAdapters.create(
            db = db,
            syncDb = requireNotNull(scratch),
            seam = SharedPrefsSeamStyleGateway(appContext),
            decodeCredentials = ::credentials,
            profiles = ::profiles,
            clock = clock,
        )
        return BackupImporter(db, list, ::profiles, ::credentials)
    }

    // ---- transfer Memories

    /** Accounts Memories can be copied from: this device's profiles, then accounts only in the cloud. */
    suspend fun transferSources(): List<Pair<MemorySource, TransferAccount>> = withContext(Dispatchers.IO) {
        cloudSync.withReplica { replica ->
            val dao = replica?.db?.syncDao()
            val local = profiles().sortedWith(compareBy(Profile::createdAt, Profile::id)).map { profile ->
                MemorySource.LocalProfile(profile.id) to accountOf(profile, dao)
            }
            val localScopes = local.map { it.second.scope }.toSet()
            val cloud = dao?.recordsOfKind(SyncKinds.ACCOUNT).orEmpty()
                .filter { !it.deleted && it.scope == SyncFormat.GLOBAL_SCOPE && it.key !in localScopes }
                .mapNotNull { CloudAccountDescriptor.fromRecord(it.toRecord()) }
                .map { MemorySource.CloudAccount(it.syncProfileId) to accountOf(it) }
            local + cloud
        }
    }

    /** Reads [source], matches what needs matching on the target through [catalog] and returns the plan. */
    suspend fun planTransfer(
        source: MemorySource,
        targetProfileId: String,
        catalog: MemoryCatalog?,
        onProgress: (done: Int, total: Int) -> Unit = { _, _ -> },
    ): TransferPlan = withContext(Dispatchers.IO) {
        // Matching may search the network: read everything (ledger included) under the lock, match outside it.
        val read = cloudSync.withReplica(createIfMissing = true) { replica ->
            val syncDb = requireNotNull(replica) { "No sync database" }.db
            val dao = syncDb.syncDao()
            val targetProfile = requireNotNull(profiles().firstOrNull { it.id == targetProfileId }) { "No such profile" }
            val (account, items) = read(source, dao)
            require(account.localProfileId != targetProfileId) { "Source and target are the same profile" }
            PlanInput(account, items, accountOf(targetProfile, dao), SyncMetaTransferLedger(syncDb).read(account.scope, targetProfileId))
        }
        // The plan never touches the ledger: [PlanInput.previous] is what it would have read.
        MemoryTransfer(db, InMemoryTransferLedger(), clock = clock)
            .plan(read.from, read.items, read.to, catalog, onProgress, previous = read.previous)
    }

    private class PlanInput(
        val from: TransferAccount,
        val items: MemoryItems,
        val to: TransferAccount,
        val previous: Map<String, String>,
    )

    /** Copies what [plan] maps (picks included) into its target profile. */
    suspend fun runTransfer(plan: TransferPlan): TransferResult = withContext(Dispatchers.IO) {
        val result = cloudSync.withReplica(createIfMissing = true) { replica ->
            MemoryTransfer(db, SyncMetaTransferLedger(requireNotNull(replica).db), clock = clock).execute(plan)
        }
        if (result.copied.total + result.updated.total > 0) cloudSync.onLocalDataImported()
        result
    }

    private suspend fun read(source: MemorySource, dao: SyncDao?): Pair<TransferAccount, MemoryItems> = when (source) {
        is MemorySource.LocalProfile -> {
            val profile = requireNotNull(profiles().firstOrNull { it.id == source.profileId }) { "No such profile" }
            accountOf(profile, dao) to MemoryItems.fromProfile(db, profile.id)
        }
        is MemorySource.CloudAccount -> {
            val replica = requireNotNull(dao) { "Cloud sync has never run on this device" }
            val descriptor = replica.getRecord(SyncKinds.ACCOUNT, SyncFormat.GLOBAL_SCOPE, source.syncProfileId)
                ?.toRecord()?.let(CloudAccountDescriptor::fromRecord)
            val account = descriptor?.let(::accountOf) ?: TransferAccount(source.syncProfileId, "unknown", source.syncProfileId, null, null)
            account to MemoryItems.fromRecords(replica.recordsInScope(source.syncProfileId).map { it.toWire() }, source.syncProfileId)
        }
        is MemorySource.BackupAccount -> {
            val listed = source.backup.manifest.accounts.firstOrNull { it.scope == source.scope }
            TransferAccount(
                scope = source.scope,
                provider = listed?.provider ?: "unknown",
                displayName = listed?.displayName ?: source.scope,
                serverKey = serverKeyOf(listed?.hint),
                localProfileId = null,
            ) to MemoryItems.fromRecords(source.backup.snapshot.records, source.scope)
        }
    }

    private suspend fun accountOf(profile: Profile, dao: SyncDao?): TransferAccount {
        val credentials = credentials(profile)
        val hint = (credentials as? ProfileCredentials.Subsonic)?.let { AccountFingerprints.subsonicHint(it.serverUrl, it.username) }
        return TransferAccount(
            scope = AccountScopes.of(profile, credentials, dao),
            provider = profile.provider,
            displayName = profile.displayName,
            serverKey = serverKeyOf(hint),
            localProfileId = profile.id,
        )
    }

    private fun accountOf(descriptor: CloudAccountDescriptor) = TransferAccount(
        scope = descriptor.syncProfileId,
        provider = descriptor.provider,
        displayName = descriptor.displayName.ifBlank { descriptor.syncProfileId },
        serverKey = serverKeyOf(descriptor.hint),
        localProfileId = null,
    )

    private fun exportDeviceId(): String {
        val prefs = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs.getString(KEY_DEVICE_ID, null)?.let { return it }
        val id = UUID.randomUUID().toString().replace("-", "").take(16)
        prefs.edit().putString(KEY_DEVICE_ID, id).apply()
        return id
    }

    companion object {
        private const val PREFS = "yoin_backup"
        private const val KEY_DEVICE_ID = "device_id"

        /** "host[:port]" of a Subsonic "user @ host[:port]" hint. */
        fun serverKeyOf(hint: String?): String? = hint?.substringAfter(" @ ", "")?.trim()?.lowercase(Locale.ROOT)?.takeIf(String::isNotEmpty)
    }
}

/** Names for readable backups from Yoin's own plays and album notes (no network). */
class DomainReadableNames(private val db: YoinDatabase) : ReadableNames {
    private val albums = HashMap<String, Map<Pair<String, String>, ReadableAlbum>>()
    private val tracks = HashMap<String, Map<Pair<String, String>, ReadableTrack>>()

    override suspend fun album(localProfileId: String?, provider: String, albumId: String): ReadableAlbum? {
        val profileId = localProfileId ?: return null
        val known = albums.getOrPut(profileId) {
            db.syncDomainDao().playedAlbumsForProfile(profileId).associate {
                (it.provider to com.gpo.yoin.data.model.MediaId.storedRawId(it.provider, it.albumId)) to
                    ReadableAlbum(it.albumName, it.artist.takeIf(String::isNotBlank))
            }
        }
        return known[provider to albumId]
    }

    override suspend fun track(localProfileId: String?, provider: String, trackId: String): ReadableTrack? {
        val profileId = localProfileId ?: return null
        val known = tracks.getOrPut(profileId) {
            db.syncDomainDao().playedTracksForProfile(profileId).associate {
                (it.provider to it.songId) to ReadableTrack(
                    it.title,
                    it.artist.takeIf(String::isNotBlank),
                    it.albumId.takeIf(String::isNotBlank)?.let { id -> com.gpo.yoin.data.model.MediaId.storedRawId(it.provider, id) },
                )
            }
        }
        return known[provider to trackId]
    }
}
