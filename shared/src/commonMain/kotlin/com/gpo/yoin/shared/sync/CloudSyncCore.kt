package com.gpo.yoin.shared.sync

import com.gpo.yoin.shared.sync.engine.ApplyResult
import com.gpo.yoin.shared.sync.engine.MalformedSyncFileException
import com.gpo.yoin.shared.sync.engine.OutgoingFile
import com.gpo.yoin.shared.sync.engine.SnapshotCodec
import com.gpo.yoin.shared.sync.engine.SyncEngine
import com.gpo.yoin.shared.sync.engine.SyncSchemaTooNewException
import com.gpo.yoin.shared.sync.identity.AccountBinder
import com.gpo.yoin.shared.sync.identity.BindingSnapshot
import com.gpo.yoin.shared.sync.identity.CloudAccountDescriptor
import com.gpo.yoin.shared.sync.identity.SyncProfile
import kotlin.concurrent.Volatile
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.cancellation.CancellationException
import kotlin.random.Random
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * The platform-neutral half of Android's `CloudSyncManager`: one sync cycle
 * (reset markers and epochs, own-file bookkeeping, peer merge, capture, apply,
 * publish), "delete cloud data" with its resumable marker, and the account
 * actions. What stays per platform: Google sign-in and tokens, when cycles run
 * (foreground / debounce / background), where [store] is persisted, the
 * device's name and the UI.
 *
 * Every public call runs on [context] (the main dispatcher on iOS, so the
 * Swift stores behind [SyncDomain] are only touched on the main thread) and
 * under one [Mutex], like Android's.
 *
 * LAST_ERROR codes, meta keys, file names and appProperties are Android's, so
 * an iPhone and an Android phone share one appDataFolder.
 */
class CloudSyncCore(
    private val store: SyncStore,
    adapters: (SyncStore) -> List<SyncAdapter>,
    private val profiles: suspend () -> List<SyncProfile>,
    /** The platform's token source for the signed-in Google account. */
    private val tokens: SyncTokenProvider,
    /** Builds a Drive client for a token source (DriveAppDataClient; tests pass a fake). */
    private val transportFactory: (SyncTokenProvider) -> SyncTransport,
    private val deviceName: () -> String,
    private val versionCode: Int,
    private val clock: () -> Long = ::syncNowMillis,
    private val context: CoroutineContext = EmptyCoroutineContext,
    /** Called after every public operation that may have changed [store] (iOS writes its file). */
    private val persist: () -> Unit = {},
    /** "%d\nSynced from other devices" — the platform's localized summary line. */
    private val summaryText: (Int) -> String = { "$it\nSynced from other devices" },
    /**
     * Safety gate for a new platform (iOS): until one cycle has pulled every peer file, mapped it into
     * the local stores and backed the whole appDataFolder up through [backupSink], nothing is written
     * to Drive (no uploads, no duplicate clean-up). The next cycle is two-way, with Android's rules.
     */
    private val readOnlyFirstPull: Boolean = false,
    private val backupSink: SyncBackupSink? = null,
    /** Native iCloud (CloudKit Private Database) transport on Apple platforms; null on non-Apple builds. */
    private val icloudTransport: SyncTransport? = null,
) {
    private val mutex = Mutex()
    private val dao: SyncStore get() = store
    private val adapterList: List<SyncAdapter> by lazy { adapters(store) }
    private val binder by lazy { AccountBinder(store, profiles, clock = clock) }

    private val _state = MutableStateFlow(CloudSyncState())
    val state: StateFlow<CloudSyncState> = _state.asStateFlow()

    /** Swift: called with every new state (on [context]). */
    var onStateChange: ((CloudSyncState) -> Unit)? = null

    fun currentState(): CloudSyncState = _state.value

    private val driveTransport: SyncTransport by lazy { transportFactory(IdentityCheckedTokens(tokens)) }

    private suspend fun activeLocation(): String =
        dao.meta(SyncMetaKeys.SYNC_LOCATION)?.takeIf { it.isNotEmpty() } ?: SyncLocations.GOOGLE_DRIVE

    private suspend fun activeTransport(location: String? = null): SyncTransport =
        when (location ?: activeLocation()) {
            SyncLocations.ICLOUD -> icloudTransport
                ?: throw SyncTransportException.Misconfigured("iCloud sync isn't available in this build of Yoin.")
            else -> driveTransport
        }

    private fun clientFor(accessToken: String): SyncTransport = transportFactory(
        object : SyncTokenProvider {
            override suspend fun token(): String = accessToken

            override suspend fun invalidate(token: String) = Unit
        },
    )

    private fun transportFor(location: String, accessToken: String?): SyncTransport = when (location) {
        SyncLocations.ICLOUD -> icloudTransport
            ?: throw SyncTransportException.Misconfigured("iCloud sync isn't available in this build of Yoin.")
        else -> if (accessToken != null) clientFor(accessToken) else driveTransport
    }

    private suspend fun ownerPermissionId(location: String): String? = when (location) {
        SyncLocations.ICLOUD -> dao.meta(SyncMetaKeys.ICLOUD_PERMISSION_ID)
        else -> dao.meta(SyncMetaKeys.DRIVE_PERMISSION_ID)
            ?: dao.meta(SyncMetaKeys.GOOGLE_PERMISSION_ID)?.takeUnless { it.startsWith("icloud:") }
    }

    private suspend fun putOwnerPermissionId(location: String, permissionId: String) {
        when (location) {
            SyncLocations.ICLOUD -> putMeta(SyncMetaKeys.ICLOUD_PERMISSION_ID, permissionId)
            else -> {
                putMeta(SyncMetaKeys.DRIVE_PERMISSION_ID, permissionId)
                putMeta(SyncMetaKeys.GOOGLE_PERMISSION_ID, permissionId)
            }
        }
    }

    /**
     * Each token string is checked once against the stored Google identity before any request uses it,
     * so a cycle can never read from or write into another account's Drive.
     */
    private inner class IdentityCheckedTokens(private val inner: SyncTokenProvider) : SyncTokenProvider {
        @Volatile private var verified: String? = null

        override suspend fun token(): String {
            val token = inner.token()
            if (token == verified) return token
            val expected = ownerPermissionId(SyncLocations.GOOGLE_DRIVE)
            if (expected != null && clientFor(token).about().permissionId != expected) {
                throw SyncTransportException.Unauthorized("Token belongs to a different Google account")
            }
            verified = token
            return token
        }

        override suspend fun invalidate(token: String) {
            if (verified == token) verified = null
            inner.invalidate(token)
        }
    }

    private var engineInstance: SyncEngine? = null

    private suspend fun engine(): SyncEngine = engineInstance ?: SyncEngine(
        db = store,
        adapters = adapterList,
        deviceId = deviceId(),
        deviceName = deviceName,
        clock = clock,
    ).also { engineInstance = it }

    private var initialized = false
    private var lastCycleStartedAt = 0L
    private var lastBindings: BindingSnapshot? = null
    private var updateRequiredFrom: String? = null
    @Volatile private var inTransaction = false

    private fun safePersist() {
        if (!inTransaction) persist()
    }

    // ------------------------------------------------------------------ public API

    @Throws(Throwable::class)
    suspend fun initialize() = run {
        ensureInitialized()
        if (dao.meta(SyncMetaKeys.PENDING_RESET) != null) resumePendingReset()
        if (dao.meta(SyncMetaKeys.PENDING_MIGRATION) != null) resumePendingMigration()
    }

    @Throws(Throwable::class)
    suspend fun isEnabledNow(): Boolean = run { isEnabled() }

    /** The Google account this device syncs with (or last synced with), for re-authorizing it silently. */
    @Throws(Throwable::class)
    suspend fun googleEmail(): String? = run { dao.meta(SyncMetaKeys.GOOGLE_EMAIL) }

    /**
     * Turn on (or reconnect) Google Drive with a fresh access token for the account the user just chose.
     * Returns null when sync started, or a user-facing message.
     */
    @Throws(Throwable::class)
    suspend fun enable(accessToken: String): String? = enableLocation(SyncLocations.GOOGLE_DRIVE, accessToken)

    /**
     * Turn on (or reconnect) iCloud (CloudKit Private Database) without OAuth sign-in.
     * Returns null when sync started, or a user-facing message.
     */
    @Throws(Throwable::class)
    suspend fun enableICloud(): String? = enableLocation(SyncLocations.ICLOUD, accessToken = null)

    /**
     * Turn on (or reconnect) cloud sync for [location] ([SyncLocations.GOOGLE_DRIVE] or [SyncLocations.ICLOUD]).
     * Returns null when sync started, or a user-facing message.
     */
    @Throws(Throwable::class)
    suspend fun enableLocation(location: String, accessToken: String? = null): String? = run {
        ensureInitialized()
        val about = try {
            transportFor(location, accessToken).about()
        } catch (error: SyncTransportException.Misconfigured) {
            return@run "Cloud sync isn't available in this build of Yoin."
        } catch (error: SyncTransportException) {
            return@run describe(error, location)
        }
        mutex.withLock {
            val previousLocation = activeLocation()
            val previousOwner = ownerPermissionId(previousLocation)
            val replicaOwner = dao.meta(SyncMetaKeys.REPLICA_PERMISSION_ID)
            val otherStore = previousLocation != location ||
                (previousOwner != null && previousOwner != about.permissionId) ||
                (replicaOwner != null && replicaOwner != about.permissionId)
            if (otherStore) {
                // Different backend or account: the replica describes another cloud copy. Domain data stays.
                engine().clearReplica()
                resetPublishBookkeeping()
                dao.deleteMeta(SyncMetaKeys.PARTICIPATED)
                dao.deleteMeta(SyncMetaKeys.LAST_SUMMARY)
                dao.deleteMeta(SyncMetaKeys.ACK_MIGRATED_EPOCH)
            }
            // The reset epoch belongs to one cloud store; the first cycle adopts this store's.
            if (previousLocation != location || previousOwner != about.permissionId) {
                dao.deleteMeta(SyncMetaKeys.EPOCH)
                // A new cloud store: pull it read-only (and back it up) before the first write again.
                dao.deleteMeta(META_TWO_WAY)
            }
            putMeta(SyncMetaKeys.SYNC_LOCATION, location)
            putOwnerPermissionId(location, about.permissionId)
            putMeta(SyncMetaKeys.REPLICA_PERMISSION_ID, about.permissionId)
            if (location == SyncLocations.GOOGLE_DRIVE) {
                about.email?.let { putMeta(SyncMetaKeys.GOOGLE_EMAIL, it) } ?: dao.deleteMeta(SyncMetaKeys.GOOGLE_EMAIL)
                about.displayName?.let { putMeta(SyncMetaKeys.GOOGLE_DISPLAY_NAME, it) }
                    ?: dao.deleteMeta(SyncMetaKeys.GOOGLE_DISPLAY_NAME)
            }
            dao.deleteMeta(SyncMetaKeys.MISCONFIGURED_FOR)
            dao.deleteMeta(SyncMetaKeys.LAST_ERROR)
            putMeta(SyncMetaKeys.ENABLED, "true")
        }
        persist()
        runCycle(force = true)
        null
    }

    /** One full cycle now (Settings › Sync now). */
    @Throws(Throwable::class)
    suspend fun syncNow() = run {
        ensureInitialized()
        if (isEnabled()) runCycle(force = true)
    }

    /** App came to the foreground: a cycle, at most once a minute. */
    @Throws(Throwable::class)
    suspend fun onForeground() = run {
        ensureInitialized()
        if (!isEnabled()) return@run
        if (clock() - lastCycleStartedAt < FOREGROUND_THROTTLE_MS) return@run
        runCycle()
    }

    /** App went to the background: upload what is pending. */
    @Throws(Throwable::class)
    suspend fun onBackground() = run {
        if (!initialized || !isEnabled()) return@run
        if (engine().hasPendingChanges()) runCycle()
    }

    /** Local data changed (the platform debounces, ~8 s like Android): capture, then upload if anything is new. */
    @Throws(Throwable::class)
    suspend fun localChanged() = run {
        ensureInitialized()
        if (!isEnabled()) return@run
        runLocalCapture()
        if (engine().hasPendingChanges()) runCycle()
    }

    /** This device only: stop syncing, keep local data, the replica and the Drive copy. */
    @Throws(Throwable::class)
    suspend fun turnOff() = run {
        mutex.withLock {
            putMeta(SyncMetaKeys.ENABLED, "false")
            dao.deleteMeta(SyncMetaKeys.LAST_ERROR)
        }
        refreshState()
    }

    /** All devices: write a reset marker, turn off here, delete every Yoin file. Null on success, else a message. */
    @Throws(Throwable::class)
    suspend fun deleteCloudData(): String? = run {
        var location = SyncLocations.GOOGLE_DRIVE
        val result = runCatching {
            mutex.withLock {
                location = activeLocation()
                val transport = activeTransport(location)
                // Never act on whatever account a token happens to come from.
                val about = transport.about()
                val owner = ownerPermissionId(location)
                check(owner != null && owner == about.permissionId) {
                    if (location == SyncLocations.ICLOUD) {
                        "Sign in to the same iCloud account before deleting cloud data."
                    } else {
                        "Reconnect Google before deleting cloud data."
                    }
                }
                val files = transport.list()
                // Never delete without a local copy of what is there.
                if (backupSink != null && dao.meta(SyncMetaKeys.PENDING_RESET) == null) {
                    checkNotNull(backupAll(files, transport)) {
                        "Couldn't back up your cloud data first; nothing was deleted."
                    }
                }
                val pending = dao.meta(SyncMetaKeys.PENDING_RESET)?.let(::parsePendingReset)
                // Strictly newer than every marker any device has seen, whatever the local clock says.
                val epoch = pending?.first ?: maxOf(
                    clock(),
                    (files.maxOfOrNull { it.resetEpoch() ?: 0L } ?: 0L) + 1,
                    (dao.meta(SyncMetaKeys.EPOCH)?.toLongOrNull() ?: 0L) + 1,
                )
                val markerId = pending?.second ?: transport.generateIds(1).first()
                putMeta(SyncMetaKeys.PENDING_RESET, "$epoch|$markerId")
                persist()
                finishReset(epoch, markerId, files, transport)
            }
            // No token revoke: other devices need the grant to read the marker and turn themselves off.
            Unit
        }
        refreshState()
        result.exceptionOrNull()?.let { error ->
            if (error is CancellationException) throw error
            (error as? SyncTransportException)?.let { describe(it, location) }
                ?: (error.message ?: "Couldn't delete cloud data.")
        }
    }

    /**
     * Remove one music account ([syncProfileId]) from the active cloud location (Drive or iCloud) across all
     * devices by writing a [ScopePurgeMarker] and purging the local replica. Local domain data stays.
     * Returns null on success, or a user-facing error message.
     */
    @Throws(Throwable::class)
    suspend fun removeAccountFromCloud(syncProfileId: String): String? = run {
        ensureInitialized()
        var location = SyncLocations.GOOGLE_DRIVE
        val result = runCatching {
            require(syncProfileId.isNotEmpty()) { "No account to remove." }
            mutex.withLock {
                check(isEnabled()) { "Turn on Cloud sync to remove an account from the cloud." }
                check(!readOnlyFirstPull || dao.meta(META_TWO_WAY) == "true") {
                    "Finish the initial sync before removing an account from the cloud."
                }
                location = activeLocation()
                val transport = activeTransport(location)
                val about = transport.about()
                val owner = ownerPermissionId(location)
                check(owner != null && owner == about.permissionId) {
                    if (location == SyncLocations.ICLOUD) {
                        "Sign in to the same iCloud account before removing an account."
                    } else {
                        "Reconnect Google before removing an account."
                    }
                }
                val files = transport.list()
                val resetEpoch = dao.meta(SyncMetaKeys.EPOCH)?.toLongOrNull() ?: 0L
                val engine = engine()
                val newestRecord = maxOf(
                    dao.recordsInScope(syncProfileId).maxOfOrNull { it.ts } ?: 0L,
                    dao.getRecord(SyncKinds.ACCOUNT, SyncFormat.GLOBAL_SCOPE, syncProfileId)?.ts ?: 0L,
                )
                val previousMarker = files
                    .filter {
                        it.appProperties[SyncFileProps.KIND] == SyncFileProps.KIND_PURGE &&
                            it.appProperties[SyncFileProps.SCOPE] == syncProfileId
                    }
                    .maxOfOrNull { it.appProperties[SyncFileProps.EPOCH]?.toLongOrNull() ?: 0L } ?: 0L
                val epoch = maxOf(
                    clock(),
                    newestRecord,
                    previousMarker + 1,
                    (engine.purgedScopes()[syncProfileId] ?: 0L) + 1,
                    resetEpoch,
                )
                val markerId = transport.generateIds(1).first()
                transport.create(
                    fileId = markerId,
                    name = SyncFileProps.purgeFileName(epoch, deviceId()),
                    appProperties = mapOf(
                        SyncFileProps.KIND to SyncFileProps.KIND_PURGE,
                        SyncFileProps.EPOCH to epoch.toString(),
                        SyncFileProps.SCOPE to syncProfileId,
                        SyncFileProps.DEVICE_ID to deviceId(),
                    ),
                    mimeType = "application/json",
                    bytes = SnapshotCodec.encodePurge(
                        ScopePurgeMarker(
                            scope = syncProfileId,
                            epoch = epoch,
                            at = clock(),
                            deviceId = deviceId(),
                            deviceName = deviceName(),
                        ),
                    ),
                )
                engine.applyScopePurge(syncProfileId, epoch)
                binder.markPurged(syncProfileId)
                refreshBindings()
                persist()
            }
        }
        if (result.isSuccess) {
            runCycle(force = true)
            return@run null
        }
        refreshState()
        result.exceptionOrNull()?.let { error ->
            if (error is CancellationException) throw error
            (error as? SyncTransportException)?.let { describe(it, location) }
                ?: (error.message ?: "Couldn't remove account from cloud.")
        }
    }

    /**
     * Dismisses a [CloudSyncPhase.MigratedElsewhere] banner and continues syncing with the current location.
     * Records strictly the newest remote marker epoch (never local [clock], avoiding clock-skew suppression).
     */
    @Throws(Throwable::class)
    suspend fun dismissMigrationNotice() = run {
        ensureInitialized()
        val listed = mutex.withLock {
            val location = activeLocation()
            val files = runCatching { activeTransport(location).list() }.getOrNull() ?: return@withLock false
            val newestEpoch = files
                .filter { it.appProperties[SyncFileProps.KIND] == SyncFileProps.KIND_MIGRATED }
                .maxOfOrNull { it.migratedEpoch() ?: 0L }
            if (newestEpoch != null) {
                val existingAck = dao.meta(SyncMetaKeys.ACK_MIGRATED_EPOCH)?.toLongOrNull() ?: 0L
                putMeta(SyncMetaKeys.ACK_MIGRATED_EPOCH, maxOf(existingAck, newestEpoch).toString())
            }
            dao.deleteMeta(SyncMetaKeys.LAST_ERROR)
            true
        }
        if (listed) runCycle(force = true) else refreshState()
    }

    /** Drops an unsendable pending migration notice (e.g. if the user signed out of the old backend). */
    @Throws(Throwable::class)
    suspend fun dismissPendingMigrationNotice() = run {
        ensureInitialized()
        mutex.withLock {
            dao.deleteMeta(SyncMetaKeys.PENDING_MIGRATION)
        }
        refreshState()
    }

    /**
     * Four-step guided sync location switch (docs/cloud-sync.md):
     * 1. Sync the current location cleanly (pull peers + purges, verify healthy two-way state), then back up.
     * 2. Connect [targetLocation], back up existing target files, merge target purges & peers inside an atomic
     *    [SyncStore.transaction], and upload full state + artifacts.
     * 3. Re-list and download back EVERY uploaded file to verify byte/SHA-256 identity and decoded per-account
     *    live record counts. Any failure rolls back the [SyncStore] transaction, deletes newly created orphan files
     *    on [targetLocation], and leaves local domain stores & the old location untouched.
     * 4. Commit switch, apply merged target records locally, persist durable [SyncMetaKeys.PENDING_MIGRATION]
     *    (retrying automatically if writing the marker fails), and optionally clean old data files while
     *    preserving migration, purge, and latest reset markers.
     */
    @Throws(Throwable::class)
    suspend fun switchLocation(
        targetLocation: String,
        targetAccessToken: String? = null,
        clearOldCopy: Boolean = false,
    ): LocationSwitchResult = run {
        ensureInitialized()
        val outcome = mutex.withLock {
            val oldLocation = activeLocation()
            if (oldLocation == targetLocation) {
                return@withLock LocationSwitchResult(
                    succeeded = false,
                    fromLocation = oldLocation,
                    toLocation = targetLocation,
                    error = "Already syncing with this location.",
                )
            }
            val engine = engine()
            val me = deviceId()
            val now = clock()
            val createdTargetIds = mutableListOf<String>()
            var targetTransportRef: SyncTransport? = null
            var errorLocation = oldLocation
            try {
                // Safety gates: must be enabled, two-way, and healthy on the current location.
                check(isEnabled()) { "Turn on Cloud sync before switching sync locations." }
                check(!readOnlyFirstPull || dao.meta(META_TWO_WAY) == "true") {
                    "Finish the initial sync before switching sync locations."
                }
                // Follow-the-leader switch (e.g. tapping "Switch to iCloud" on the MigratedElsewhere banner):
                // allow pulling peers/purges from the old location when its marker already points to targetLocation.
                val preSyncError = cycle(oldLocation, ignoreMigrationTo = targetLocation)
                if (preSyncError != null) {
                    putMeta(SyncMetaKeys.LAST_ERROR, preSyncError)
                    return@withLock LocationSwitchResult(
                        succeeded = false,
                        fromLocation = oldLocation,
                        toLocation = targetLocation,
                        error = describePreSwitchBlocker(preSyncError, oldLocation),
                    )
                }
                check(isEnabled()) { "Cloud sync was turned off during sync; switch cancelled." }
                check(engine.heldReview() == null) { "Resolve the pending review before switching sync locations." }
                check(updateRequiredFrom == null) { "Update Yoin before switching sync locations." }

                // Step 1: List the current location after full sync and require a verified backup when backupSink exists.
                val oldTransport = activeTransport(oldLocation)
                val oldFiles = oldTransport.list()
                val backupFolder = if (backupSink != null && oldFiles.isNotEmpty()) {
                    checkNotNull(backupAll(oldFiles, oldTransport, locationOverride = oldLocation)) {
                        "Couldn't back up your current cloud data first; sync location wasn't changed."
                    }
                } else {
                    null
                }

                // Step 2: Connect target location and back up any existing files there before writing.
                errorLocation = targetLocation
                val targetTransport = transportFor(targetLocation, targetAccessToken)
                targetTransportRef = targetTransport
                val targetAbout = targetTransport.about()
                val targetFiles = targetTransport.list()
                if (backupSink != null && targetFiles.isNotEmpty()) {
                    checkNotNull(backupAll(targetFiles, targetTransport, locationOverride = targetLocation)) {
                        "Couldn't back up existing data in the new location first; sync location wasn't changed."
                    }
                }
                val targetEpoch = targetFiles
                    .filter { it.appProperties[SyncFileProps.KIND] == SyncFileProps.KIND_RESET }
                    .maxOfOrNull { it.resetEpoch() ?: 0L } ?: 0L
                val targetMaxMigratedEpoch = targetFiles
                    .filter { it.appProperties[SyncFileProps.KIND] == SyncFileProps.KIND_MIGRATED }
                    .maxOfOrNull { it.migratedEpoch() ?: 0L } ?: 0L

                val markerEpoch = maxOf(
                    now,
                    (oldFiles.maxOfOrNull { it.resetEpoch() ?: it.migratedEpoch() ?: 0L } ?: 0L) + 1,
                )
                // If allocating a remote id fails here, leave it blank so retryPendingMigrationLocked allocates
                // a valid backend-native id later (never pass a random hex id to Drive).
                val markerId = runCatching { oldTransport.generateIds(1).first() }.getOrDefault("")

                // Run target merge + upload + Step 3 read-back verification + Step 4 store commit atomically:
                // any throw rolls back every SyncStore write (bindings, purges, records, meta, pending IDs),
                // and inTransaction suppresses disk flushes so a mid-switch kill never leaves mixed state.
                lateinit var bindingsAfter: BindingSnapshot
                lateinit var verifiedCounts: List<LocationSwitchAccountCount>
                var verifiedTotalLive = 0
                lateinit var snapshotSha256: String

                dao.transaction {
                    inTransaction = true
                    try {
                        applyPurgeMarkers(targetFiles, targetEpoch, targetTransport)
                        val targetDataFiles = targetFiles.filter {
                            val kind = it.appProperties[SyncFileProps.KIND]
                            kind == SyncFileProps.KIND_STATE || kind == SyncFileProps.KIND_ARTIFACTS
                        }
                        val (targetOwn, targetPeers) = targetDataFiles.partition {
                            it.appProperties[SyncFileProps.DEVICE_ID] == me
                        }
                        for (peer in targetPeers) {
                            val bytes = checkNotNull(targetTransport.download(peer.id)) {
                                "Couldn't read ${peer.name} in the new location."
                            }
                            val envelope = SnapshotCodec.decode(bytes)
                            if (envelope.epoch < targetEpoch) continue
                            engine.mergeRemote(peer, envelope)
                        }

                        bindingsAfter = refreshBindings()
                        val captured = engine.capture(bindingsAfter.active)
                        check(captured.failures.isEmpty()) { "Couldn't read local data cleanly before switching." }

                        val keptTargetOwn = targetOwn.groupBy { it.fileKey() }.mapValues { (_, group) ->
                            group.maxBy { it.version }
                        }
                        val outgoing = engine.outgoingFiles(epoch = targetEpoch, writtenAt = now, appVersionCode = versionCode)
                        val uploadedByKey = LinkedHashMap<String, RemoteFile>()
                        val expectedByFileId = LinkedHashMap<String, OutgoingFile>()
                        for (file in outgoing) {
                            val key = "${file.fileKind}:${file.shard}"
                            val existing = keptTargetOwn[key]
                            if (existing == null && file.recordCount == 0 && file.fileKind != SyncFileProps.KIND_STATE) continue
                            val uploaded = uploadTo(targetTransport, targetLocation, existing, file, me)
                                ?: error("Failed to upload ${file.fileKind} to $targetLocation")
                            if (existing == null) createdTargetIds += uploaded.id
                            uploadedByKey[key] = uploaded
                            expectedByFileId[uploaded.id] = file
                        }

                        // Step 3: Re-list AND read back every uploaded file to verify bytes, SHA-256, and decoded counts.
                        val verifiedListing = targetTransport.list().associateBy { it.id }
                        val stateUploaded = uploadedByKey[STATE_FILE_KEY]
                        check(stateUploaded != null && uploadedByKey.values.all { verifiedListing.containsKey(it.id) }) {
                            "Verification failed: uploaded files were not found in the new location."
                        }
                        val decodedCountsByScope = LinkedHashMap<String, Int>()
                        var decodedTotalLive = 0
                        val expectedFileHashes = ArrayList<String>()
                        for ((key, uploaded) in uploadedByKey.entries.sortedBy { it.key }) {
                            val expectedFile = expectedByFileId.getValue(uploaded.id)
                            val readBack = checkNotNull(targetTransport.download(uploaded.id)) {
                                "Verification failed: couldn't read back ${uploaded.name} from the new location."
                            }
                            val expectedHash = CanonicalJson.sha256Hex(expectedFile.bytes)
                            val actualHash = CanonicalJson.sha256Hex(readBack)
                            check(readBack.contentEquals(expectedFile.bytes) && actualHash == expectedHash) {
                                "Verification failed: checksum mismatch for ${uploaded.name}."
                            }
                            expectedFileHashes += "$key:$actualHash"
                            val envelope = SnapshotCodec.decode(readBack)
                            for (record in envelope.records) {
                                if (!record.deleted && record.kind != SyncKinds.ACCOUNT) {
                                    decodedTotalLive++
                                    if (record.scope != SyncFormat.GLOBAL_SCOPE) {
                                        decodedCountsByScope[record.scope] = (decodedCountsByScope[record.scope] ?: 0) + 1
                                    }
                                }
                            }
                        }
                        val expectedLive = dao.allRecords().count { !it.deleted && it.kind != SyncKinds.ACCOUNT }
                        check(decodedTotalLive == expectedLive) {
                            "Verification failed: uploaded record count ($decodedTotalLive) did not match expected ($expectedLive)."
                        }
                        val expectedByScope = dao.allRecords()
                            .filter { !it.deleted && it.scope != SyncFormat.GLOBAL_SCOPE && it.kind != SyncKinds.ACCOUNT }
                            .groupingBy { it.scope }
                            .eachCount()
                        check(decodedCountsByScope == expectedByScope) {
                            "Verification failed: per-account record counts did not match."
                        }
                        snapshotSha256 = CanonicalJson.sha256Hex(expectedFileHashes.joinToString("|"))
                        verifiedTotalLive = decodedTotalLive
                        verifiedCounts = computeAccountCounts(bindingsAfter, decodedCountsByScope)

                        // Step 4 (store commit): switch active location & bookkeeping atomically.
                        resetPublishBookkeeping()
                        dao.clearRemoteFiles()
                        for ((_, uploaded) in uploadedByKey) {
                            val file = expectedByFileId.getValue(uploaded.id)
                            engine.markUploaded(file)
                            dao.upsertRemoteFile(
                                SyncRemoteFileEntity(
                                    fileId = uploaded.id,
                                    deviceId = me,
                                    fileKind = file.fileKind,
                                    shard = file.shard,
                                    version = uploaded.version,
                                    modifiedTime = uploaded.modifiedTime,
                                    deviceName = deviceName(),
                                    writtenAt = now,
                                    own = true,
                                ),
                            )
                        }
                        putMeta(SyncMetaKeys.SYNC_LOCATION, targetLocation)
                        putOwnerPermissionId(targetLocation, targetAbout.permissionId)
                        putMeta(SyncMetaKeys.REPLICA_PERMISSION_ID, targetAbout.permissionId)
                        if (targetLocation == SyncLocations.GOOGLE_DRIVE) {
                            targetAbout.email?.let { putMeta(SyncMetaKeys.GOOGLE_EMAIL, it) }
                                ?: dao.deleteMeta(SyncMetaKeys.GOOGLE_EMAIL)
                            targetAbout.displayName?.let { putMeta(SyncMetaKeys.GOOGLE_DISPLAY_NAME, it) }
                                ?: dao.deleteMeta(SyncMetaKeys.GOOGLE_DISPLAY_NAME)
                        }
                        putMeta(SyncMetaKeys.EPOCH, targetEpoch.toString())
                        putMeta(SyncMetaKeys.ACK_MIGRATED_EPOCH, targetMaxMigratedEpoch.toString())
                        putMeta(SyncMetaKeys.ENABLED, "true")
                        putMeta(SyncMetaKeys.PARTICIPATED, "true")
                        putMeta(SyncMetaKeys.HAS_PUBLISHED, "true")
                        putMeta(META_TWO_WAY, "true")
                        putMeta(SyncMetaKeys.LAST_SYNC_AT, now.toString())
                        dao.deleteMeta(SyncMetaKeys.LAST_ERROR)
                        putMeta(SyncMetaKeys.PENDING_MIGRATION, "$oldLocation|$targetLocation|$markerEpoch|$markerId")
                    } finally {
                        inTransaction = false
                    }
                }

                // Store transaction committed: NEVER delete these committed target files on any later error/cancel!
                createdTargetIds.clear()
                safePersist()

                // Apply merged target records locally (best-effort after commit; any skipped record applies next cycle).
                runCatching { engine.apply(bindingsAfter.active) }
                safePersist()

                // Write migration marker to the old location using the authenticated Step 1 transport.
                // If this fails, PENDING_MIGRATION stays persisted so subsequent cycles retry automatically.
                var markerWritten = false
                var oldCleared = false
                val markerResult = runCatching {
                    val resolvedMarkerId = markerId.ifEmpty { oldTransport.generateIds(1).first() }
                    writeMigrationMarker(
                        transport = oldTransport,
                        markerId = resolvedMarkerId,
                        markerEpoch = markerEpoch,
                        targetLocation = targetLocation,
                        at = now,
                    )
                    dao.deleteMeta(SyncMetaKeys.PENDING_MIGRATION)
                    safePersist()
                    markerWritten = true
                    if (clearOldCopy) {
                        deleteOldLocationDataFiles(oldFiles, keepMigrationId = resolvedMarkerId, transport = oldTransport)
                        oldCleared = true
                    }
                }
                if (markerResult.isFailure && markerResult.exceptionOrNull() is CancellationException) {
                    throw markerResult.exceptionOrNull()!!
                }

                LocationSwitchResult(
                    succeeded = true,
                    fromLocation = oldLocation,
                    toLocation = targetLocation,
                    snapshotSha256 = snapshotSha256,
                    backupFolder = backupFolder,
                    totalLiveRecords = verifiedTotalLive,
                    accountCounts = verifiedCounts,
                    countsVerified = true,
                    migrationMarkerWritten = markerWritten,
                    oldCopyCleared = oldCleared,
                )
            } catch (cancelled: CancellationException) {
                cleanupAbortedTargetUploads(targetTransportRef, createdTargetIds)
                runCatching { refreshBindings() }
                throw cancelled
            } catch (error: SyncTransportException) {
                cleanupAbortedTargetUploads(targetTransportRef, createdTargetIds)
                runCatching { refreshBindings() }
                LocationSwitchResult(
                    succeeded = false,
                    fromLocation = oldLocation,
                    toLocation = targetLocation,
                    error = describe(error, errorLocation),
                )
            } catch (error: Exception) {
                cleanupAbortedTargetUploads(targetTransportRef, createdTargetIds)
                runCatching { refreshBindings() }
                LocationSwitchResult(
                    succeeded = false,
                    fromLocation = oldLocation,
                    toLocation = targetLocation,
                    error = error.message ?: "Couldn't switch sync location.",
                )
            }
        }
        refreshState()
        outcome
    }

    /**
     * Optional Step 4 cleanup after a switch: verifies account ownership, backs up [oldLocation] first, then
     * deletes old state/artifact files while preserving migration markers, all scope purge markers, and the
     * newest reset marker.
     */
    @Throws(Throwable::class)
    suspend fun clearOldLocationCopy(oldLocation: String, accessToken: String? = null): String? = run {
        val result = runCatching {
            mutex.withLock {
                check(oldLocation != activeLocation()) { "Cannot clear the active sync location." }
                val oldTransport = transportFor(oldLocation, accessToken)
                val about = oldTransport.about()
                val owner = ownerPermissionId(oldLocation)
                check(owner != null && owner == about.permissionId) {
                    if (oldLocation == SyncLocations.ICLOUD) {
                        "Sign in to the same iCloud account before removing the old copy."
                    } else {
                        "Sign in to the same Google account before removing the old Drive copy."
                    }
                }
                val files = oldTransport.list()
                if (backupSink != null && files.isNotEmpty()) {
                    checkNotNull(backupAll(files, oldTransport, locationOverride = oldLocation)) {
                        "Couldn't back up your old cloud copy first; nothing was deleted."
                    }
                }
                deleteOldLocationDataFiles(files, keepMigrationId = null, transport = oldTransport)
            }
        }
        result.exceptionOrNull()?.let { error ->
            if (error is CancellationException) throw error
            (error as? SyncTransportException)?.let { describe(it, oldLocation) }
                ?: (error.message ?: "Couldn't clear old sync copy.")
        }
    }

    private suspend fun cleanupAbortedTargetUploads(transport: SyncTransport?, ids: List<String>) {
        if (transport == null || ids.isEmpty()) return
        withContext(NonCancellable) {
            for (id in ids) {
                runCatching { transport.delete(id) }
            }
        }
    }

    private suspend fun writeMigrationMarker(
        transport: SyncTransport,
        markerId: String,
        markerEpoch: Long,
        targetLocation: String,
        at: Long,
    ) {
        val me = deviceId()
        try {
            transport.create(
                fileId = markerId,
                name = SyncFileProps.migratedFileName(markerEpoch, me),
                appProperties = mapOf(
                    SyncFileProps.KIND to SyncFileProps.KIND_MIGRATED,
                    SyncFileProps.EPOCH to markerEpoch.toString(),
                    SyncFileProps.DEVICE_ID to me,
                    SyncFileProps.TARGET_LOCATION to targetLocation,
                ),
                mimeType = "application/json",
                bytes = SnapshotCodec.encodeMigration(
                    MigrationMarker(
                        epoch = markerEpoch,
                        at = at,
                        deviceId = me,
                        deviceName = deviceName(),
                        targetLocation = targetLocation,
                    ),
                ),
            )
        } catch (_: SyncTransportException.AlreadyExists) {
            // Already written by an earlier attempt.
        }
    }

    private suspend fun deleteOldLocationDataFiles(
        files: List<RemoteFile>,
        keepMigrationId: String?,
        transport: SyncTransport,
    ) {
        val newestResetId = files
            .filter { it.appProperties[SyncFileProps.KIND] == SyncFileProps.KIND_RESET }
            .maxByOrNull { it.resetEpoch() ?: 0L }
            ?.id
        files.filter { file ->
            if (!file.isYoinFile()) return@filter false
            val kind = file.appProperties[SyncFileProps.KIND]
            when {
                kind == SyncFileProps.KIND_MIGRATED -> false
                kind == SyncFileProps.KIND_PURGE -> false
                file.id == keepMigrationId -> false
                file.id == newestResetId -> false
                else -> true
            }
        }.forEach { file ->
            check(transport.delete(file.id)) { "Couldn't delete ${file.name}" }
        }
    }

    private fun describePreSwitchBlocker(code: String, location: String): String {
        val name = if (location == SyncLocations.ICLOUD) "iCloud" else "Google Drive"
        return when {
            code == CODE_OFFLINE -> "Couldn't finish syncing $name before switching. Check your connection and try again."
            code == CODE_REAUTH || code == CODE_ACCOUNT_MISMATCH ->
                if (location == SyncLocations.ICLOUD) {
                    "Sign in to iCloud before switching sync locations."
                } else {
                    "Reconnect Google before switching sync locations."
                }
            code == CODE_STORAGE -> "Your $name storage is full."
            code == CODE_REVIEW -> "Resolve the pending review before switching sync locations."
            code == CODE_CLOUD_COPY_REMOVED -> "Your $name copy was removed. Resolve that before switching."
            code.startsWith(CODE_UPDATE_PREFIX) -> "Update Yoin before switching sync locations."
            code.startsWith(CODE_MIGRATED_PREFIX) -> "Cloud sync was already moved on another device."
            code.startsWith(CODE_RESET_PREFIX) -> "Cloud sync was turned off on another device."
            code.startsWith(ERROR_PREFIX) -> code.removePrefix(ERROR_PREFIX)
            else -> "Couldn't finish syncing $name before switching."
        }
    }

    private suspend fun computeAccountCounts(
        bindings: BindingSnapshot,
        byScope: Map<String, Int>,
    ): List<LocationSwitchAccountCount> {
        val names = LinkedHashMap<String, String>()
        for (row in bindings.rows) {
            row.syncProfileId?.let { names[it] = row.displayName }
        }
        for (cloud in bindings.cloudOnly) {
            if (cloud.syncProfileId !in names) {
                names[cloud.syncProfileId] = cloud.displayName.ifEmpty { cloud.provider }
            }
        }
        return (names.keys + byScope.keys).distinct().map { scope ->
            LocationSwitchAccountCount(
                scope = scope,
                displayName = names[scope] ?: scope,
                liveRecords = byScope[scope] ?: 0,
            )
        }
    }

    /** Held mass-delete review: true = restore the items here, false = delete them on all devices. */
    @Throws(Throwable::class)
    suspend fun resolveReview(restore: Boolean) = run {
        mutex.withLock {
            engine().resolveHeldReview(restore)
            dao.deleteMeta(SyncMetaKeys.LAST_ERROR)
        }
        runCycle(force = true)
    }

    /** Our Drive copy vanished: re-upload from this device. */
    @Throws(Throwable::class)
    suspend fun uploadAgain() = run {
        mutex.withLock {
            resetPublishBookkeeping()
            dao.deleteMeta(SyncMetaKeys.LAST_ERROR)
        }
        runCycle(force = true)
    }

    @Throws(Throwable::class)
    suspend fun startSyncingAccount(localProfileId: String) = accountAction { binder.startSyncing(localProfileId) }

    @Throws(Throwable::class)
    suspend fun linkAccount(localProfileId: String, syncProfileId: String) =
        accountAction { binder.link(localProfileId, syncProfileId) }

    @Throws(Throwable::class)
    suspend fun keepAccountAsBefore(localProfileId: String) = accountAction { binder.keepAsBefore(localProfileId) }

    /** An account removed from the cloud (a scope purge marker) syncs again from this device. */
    @Throws(Throwable::class)
    suspend fun resumeAccountSync(localProfileId: String) = accountAction { binder.resumeSyncing(localProfileId) }

    /**
     * "Start fresh as this account" for a paused account. Returns how many changes of the old account
     * are still not uploaded: above zero nothing was done (the account stays paused, its rows stay on
     * this device) and the user asks again once they are up. 0 = done, or there was nothing to do.
     */
    @Throws(Throwable::class)
    suspend fun startFreshAccount(localProfileId: String): Int = run {
        ensureInitialized()
        // Publish anything still pending for the old account first, so nothing of it is lost.
        if (engine().hasPendingChanges()) runCycle(force = true)
        val notUploaded = mutex.withLock {
            val plan = binder.prepareStartFresh(localProfileId) ?: return@withLock 0
            // The cycle above didn't get them up (offline, a Drive error). The purge deletes every row
            // that matches its last captured state, uploaded or not, and then forgets that state, so
            // completeStartFresh's own pending check could no longer stop it: leave the account as it is.
            if (plan.pendingChanges > 0) return@withLock plan.pendingChanges
            engine().purgeScopeLocally(plan.oldScope, localProfileId)
            binder.completeStartFresh(plan)
            0
        }
        runCycle(force = true)
        notUploaded
    }

    /** Delete another device's files from the active cloud store (it re-uploads if it is still alive). */
    @Throws(Throwable::class)
    suspend fun removeDevice(deviceId: String) = run {
        mutex.withLock {
            runCatching {
                val transport = activeTransport()
                dao.remoteFiles().filter { it.deviceId == deviceId && !it.own }.forEach { file ->
                    if (transport.delete(file.fileId)) dao.deleteRemoteFile(file.fileId)
                }
            }
        }
        refreshState()
    }

    private suspend fun accountAction(block: suspend () -> Unit) = run {
        ensureInitialized()
        mutex.withLock { block() }
        runLocalCapture()
        runCycle(force = true)
    }

    /** Runs [block] on [context] and persists the store afterwards, whatever happened. */
    private suspend fun <T> run(block: suspend () -> T): T = withContext(context) {
        try {
            block()
        } finally {
            safePersist()
        }
    }

    // ------------------------------------------------------------------ cycles

    private suspend fun runCycle(force: Boolean = false) {
        if (!force && mutex.isLocked) return
        mutex.withLock {
            if (!isEnabled()) return
            lastCycleStartedAt = clock()
            setPhase(CloudSyncPhase.Syncing)
            var detail: String? = null
            val location = activeLocation()
            val outcome = try {
                if (dao.meta(SyncMetaKeys.PENDING_MIGRATION) != null) {
                    runCatching { retryPendingMigrationLocked() }
                }
                cycle(location)
            } catch (error: CancellationException) {
                throw error
            } catch (error: SyncTransportException) {
                detail = error.toString()
                handleTransportError(error, location)
            } catch (error: Exception) {
                detail = error.toString()
                ERROR_PREFIX + (error.message ?: error::class.simpleName)
            }
            // Diagnostics only (never shown as UI copy): what the last failed cycle threw.
            if (detail != null) putMeta(META_LAST_ERROR_DETAIL, detail.take(500)) else dao.deleteMeta(META_LAST_ERROR_DETAIL)
            withContext(NonCancellable) {
                if (outcome == null) dao.deleteMeta(SyncMetaKeys.LAST_ERROR) else putMeta(SyncMetaKeys.LAST_ERROR, outcome)
            }
        }
        persist()
        refreshState()
    }

    /**
     * Applies every scope purge marker ("remove this account from the cloud") this device hasn't applied yet,
     * newest per scope, before any peer file is merged (docs/cloud-sync.md).
     */
    private suspend fun applyPurgeMarkers(files: List<RemoteFile>, resetEpoch: Long, transport: SyncTransport) {
        val markers = files.filter { it.appProperties[SyncFileProps.KIND] == SyncFileProps.KIND_PURGE }
        if (markers.isEmpty()) return
        val engine = engine()
        val applied = engine.purgedScopes()
        val newest = HashMap<String, Long>()
        for (file in markers) {
            var scope = file.appProperties[SyncFileProps.SCOPE]?.takeIf(String::isNotEmpty)
            var epoch = file.appProperties[SyncFileProps.EPOCH]?.toLongOrNull()
            if (scope == null || epoch == null) {
                val marker = runCatching { transport.download(file.id)?.let(SnapshotCodec::decodePurge) }.getOrNull()
                    ?: continue
                scope = marker.scope
                epoch = marker.epoch
            }
            if (scope == SyncFormat.GLOBAL_SCOPE || epoch < resetEpoch) continue
            if (epoch > (newest[scope] ?: Long.MIN_VALUE)) newest[scope] = epoch
        }
        for ((scope, epoch) in newest) {
            if ((applied[scope] ?: Long.MIN_VALUE) >= epoch) continue
            engine.applyScopePurge(scope, epoch)
            binder.markPurged(scope)
        }
    }

    /** One network cycle. Returns a LAST_ERROR code, or null when everything went through. */
    private suspend fun cycle(location: String, ignoreMigrationTo: String? = null): String? {
        val transport = activeTransport(location)
        val engine = engine()
        val me = deviceId()

        val about = transport.about()
        val owner = ownerPermissionId(location)
        if (owner != null && owner != about.permissionId) return CODE_ACCOUNT_MISMATCH

        val files = transport.list()

        // A newer "delete cloud data" from any device wins over everything.
        val resets = files.filter { it.appProperties[SyncFileProps.KIND] == SyncFileProps.KIND_RESET }
        val newest = resets.maxByOrNull { it.appProperties[SyncFileProps.EPOCH]?.toLongOrNull() ?: 0L }
        val newestEpoch = newest?.appProperties?.get(SyncFileProps.EPOCH)?.toLongOrNull() ?: 0L
        var myEpoch = dao.meta(SyncMetaKeys.EPOCH)?.toLongOrNull() ?: 0L
        if (newestEpoch < myEpoch) {
            // The epoch belongs to the store: a lower one means a different (or wiped) store.
            putMeta(SyncMetaKeys.EPOCH, newestEpoch.toString())
            myEpoch = newestEpoch
        }
        if (newestEpoch > myEpoch) {
            val ownMarker = newest?.appProperties?.get(SyncFileProps.DEVICE_ID) == me
            val participated = dao.meta(SyncMetaKeys.PARTICIPATED) == "true" ||
                dao.meta(SyncMetaKeys.HAS_PUBLISHED) == "true"
            if (participated || ownMarker) {
                // We hold data from before that reset (or it is our own unfinished delete): stop.
                val by = if (ownMarker) {
                    null
                } else {
                    newest?.let { marker ->
                        runCatching { transport.download(marker.id)?.let(SnapshotCodec::decodeReset) }.getOrNull()
                    }
                }
                engine.clearReplica()
                resetPublishBookkeeping()
                dao.deleteMeta(SyncMetaKeys.PARTICIPATED)
                dao.deleteMeta(SyncMetaKeys.LAST_SUMMARY)
                putMeta(SyncMetaKeys.EPOCH, newestEpoch.toString())
                putMeta(SyncMetaKeys.ENABLED, "false")
                return if (ownMarker) CODE_DELETED_HERE else CODE_RESET_PREFIX + (by?.deviceName ?: "")
            }
            // Joining after an old reset: nothing of ours predates it. Start clean in the new epoch.
            engine.clearReplica()
            resetPublishBookkeeping()
            putMeta(SyncMetaKeys.EPOCH, newestEpoch.toString())
            myEpoch = newestEpoch
        }

        // Another device migrated this cloud copy to a new location: pause publishing here until the user decides.
        // If this device has never participated in this location yet (joining after an older migration), acknowledge
        // the pre-existing marker so switching back or joining fresh never bounces back to the previous location.
        val ackMigratedEpoch = dao.meta(SyncMetaKeys.ACK_MIGRATED_EPOCH)?.toLongOrNull() ?: 0L
        val newestMigration = files
            .filter {
                it.appProperties[SyncFileProps.KIND] == SyncFileProps.KIND_MIGRATED &&
                    it.appProperties[SyncFileProps.DEVICE_ID] != me
            }
            .maxByOrNull { it.migratedEpoch() ?: 0L }
        val newestMigrationEpoch = newestMigration?.migratedEpoch() ?: 0L
        if (newestMigration != null && newestMigrationEpoch > maxOf(myEpoch, ackMigratedEpoch)) {
            val participated = dao.meta(SyncMetaKeys.PARTICIPATED) == "true" ||
                dao.meta(SyncMetaKeys.HAS_PUBLISHED) == "true"
            if (!participated) {
                putMeta(SyncMetaKeys.ACK_MIGRATED_EPOCH, newestMigrationEpoch.toString())
            } else {
                val decoded = runCatching { transport.download(newestMigration.id)?.let(SnapshotCodec::decodeMigration) }
                    .getOrNull()
                val target = decoded?.targetLocation
                    ?: newestMigration.appProperties[SyncFileProps.TARGET_LOCATION].orEmpty()
                if (target.isNotEmpty() && target != location && target != ignoreMigrationTo) {
                    val by = decoded?.deviceName.orEmpty()
                    return "$CODE_MIGRATED_PREFIX$target|$by"
                }
            }
        }

        applyPurgeMarkers(files, myEpoch, transport)

        val dataFiles = files.filter {
            val kind = it.appProperties[SyncFileProps.KIND]
            kind == SyncFileProps.KIND_STATE || kind == SyncFileProps.KIND_ARTIFACTS
        }
        val (ownFiles, peerFiles) = dataFiles.partition { it.appProperties[SyncFileProps.DEVICE_ID] == me }

        val twoWay = !readOnlyFirstPull || dao.meta(META_TWO_WAY) == "true"
        // Own files: keep the newest per (kind, shard); a crashed create may have left a duplicate.
        var keptOwn = ownFiles.groupBy { it.fileKey() }.mapValues { (_, group) ->
            val sorted = group.sortedByDescending { it.version }
            if (twoWay) sorted.drop(1).forEach { runCatching { transport.delete(it.id) } }
            sorted.first()
        }
        val hasPublished = dao.meta(SyncMetaKeys.HAS_PUBLISHED) == "true"
        if (hasPublished && keptOwn[STATE_FILE_KEY] == null) {
            val othersStillThere = peerFiles.any { it.appProperties[SyncFileProps.KIND] == SyncFileProps.KIND_STATE }
            // Only our files are gone: another device removed us from sync. Re-publish. All gone: ask.
            if (!othersStillThere) return CODE_CLOUD_COPY_REMOVED
            resetPublishBookkeeping()
            keptOwn = emptyMap()
        }

        // Merge peers that changed since we last merged them.
        updateRequiredFrom = null
        // Peer files this cycle could not account for (gone mid-download, unreadable, unmergeable). The
        // read-only first pull only opens two-way sync once every surviving peer file mapped.
        val unaccounted = ArrayList<String>()
        val listedIds = files.map { it.id }.toSet()
        for (file in peerFiles) {
            val seen = dao.remoteFile(file.id)
            if (seen != null && seen.version == file.version) continue
            val bytes = transport.download(file.id)
            if (bytes == null) {
                unaccounted += file.name
                continue
            }
            val envelope = try {
                SnapshotCodec.decode(bytes)
            } catch (error: SyncSchemaTooNewException) {
                updateRequiredFrom = error.deviceName ?: error.deviceId ?: file.appProperties[SyncFileProps.DEVICE_ID]
                continue
            } catch (error: MalformedSyncFileException) {
                unaccounted += file.name
                continue
            }
            // Written before the newest "delete cloud data": ignore, and remember the version.
            if (envelope.epoch < myEpoch) {
                dao.upsertRemoteFile(
                    SyncRemoteFileEntity(
                        fileId = file.id,
                        deviceId = envelope.deviceId,
                        fileKind = file.appProperties[SyncFileProps.KIND].orEmpty(),
                        shard = file.appProperties[SyncFileProps.SHARD]?.toIntOrNull() ?: 0,
                        version = file.version,
                        modifiedTime = file.modifiedTime,
                        deviceName = envelope.deviceName,
                        writtenAt = envelope.writtenAt,
                        own = false,
                    ),
                )
                continue
            }
            try {
                engine.mergeRemote(file, envelope)
                putMeta(SyncMetaKeys.PARTICIPATED, "true")
            } catch (error: SyncSchemaTooNewException) {
                updateRequiredFrom = error.deviceName ?: envelope.deviceName
            } catch (error: MalformedSyncFileException) {
                unaccounted += file.name
            }
        }
        // Forget files that are gone (removed devices, deleted duplicates).
        dao.remoteFiles().filter { it.fileId !in listedIds && !it.own }.forEach { dao.deleteRemoteFile(it.fileId) }

        val bindings = refreshBindings()
        val captured = engine.capture(bindings.active)
        val applied = engine.apply(bindings.active)
        summarize(applied)
        persist()

        if (!twoWay) {
            // Read-only first pull: map only. Back the whole folder up; two-way from the next cycle
            // only when the pull mapped cleanly (every peer file merged, every local table read, every
            // record applied) and the backup is on disk. Anything unaccounted for keeps it read-only and
            // is tried again next cycle (a peer file stays unmerged until it reads).
            val mapped = applied.failures.isEmpty() && captured.failures.isEmpty() && unaccounted.isEmpty() &&
                updateRequiredFrom == null
            val backup = backupAll(files, transport)
            if (mapped && backup != null) putMeta(META_TWO_WAY, "true")
            putMeta(SyncMetaKeys.LAST_SYNC_AT, clock().toString())
            updateRequiredFrom?.let { return CODE_UPDATE_PREFIX + it }
            val storeName = if (location == SyncLocations.ICLOUD) "iCloud" else "Drive"
            if (backup == null) return ERROR_PREFIX + "Couldn't back up your $storeName data on this device."
            if (unaccounted.isNotEmpty()) {
                return ERROR_PREFIX + "Couldn't read ${unaccounted.size} sync file(s) in $storeName yet; this device stays read-only."
            }
            return null
        }

        // Publish what changed.
        val now = clock()
        val outgoing = engine.outgoingFiles(epoch = myEpoch, writtenAt = now, appVersionCode = versionCode)
        for (file in outgoing) {
            val key = "${file.fileKind}:${file.shard}"
            val existing = keptOwn[key]
            if (existing != null && engine.lastUploadedHash(file.fileKind, file.shard) == file.contentHash) continue
            // Don't create empty artifact shards; the state file always exists once we publish.
            if (existing == null && file.recordCount == 0 && file.fileKind != SyncFileProps.KIND_STATE) continue
            val uploaded = uploadTo(transport, location, existing, file, me) ?: continue
            putMeta(SyncMetaKeys.PARTICIPATED, "true")
            if (file.fileKind == SyncFileProps.KIND_STATE) putMeta(SyncMetaKeys.HAS_PUBLISHED, "true")
            engine.markUploaded(file)
            dao.upsertRemoteFile(
                SyncRemoteFileEntity(
                    fileId = uploaded.id,
                    deviceId = me,
                    fileKind = file.fileKind,
                    shard = file.shard,
                    version = uploaded.version,
                    modifiedTime = uploaded.modifiedTime,
                    deviceName = deviceName(),
                    writtenAt = now,
                    own = true,
                ),
            )
            persist()
        }
        putMeta(SyncMetaKeys.HAS_PUBLISHED, "true")
        putMeta(SyncMetaKeys.LAST_SYNC_AT, now.toString())

        engine.heldReview()?.let { return CODE_REVIEW }
        updateRequiredFrom?.let { return CODE_UPDATE_PREFIX + it }
        return null
    }

    private suspend fun uploadTo(
        transport: SyncTransport,
        location: String,
        existing: RemoteFile?,
        file: OutgoingFile,
        me: String,
    ): RemoteFile? {
        if (existing != null) {
            transport.update(existing.id, file.bytes)?.let { return it }
        }
        val pendingKey = SyncMetaKeys.PENDING_FILE_ID_PREFIX + "$location:${file.fileKind}:${file.shard}"
        val id = dao.meta(pendingKey) ?: transport.generateIds(1).first().also {
            putMeta(pendingKey, it)
            safePersist()
        }
        val name = if (file.fileKind == SyncFileProps.KIND_STATE) {
            SyncFileProps.stateFileName(me)
        } else {
            SyncFileProps.artifactFileName(me, file.shard)
        }
        val created = try {
            transport.create(
                fileId = id,
                name = name,
                appProperties = mapOf(
                    SyncFileProps.KIND to file.fileKind,
                    SyncFileProps.DEVICE_ID to me,
                    SyncFileProps.SHARD to file.shard.toString(),
                    SyncFileProps.SCHEMA to SyncFormat.SCHEMA.toString(),
                ),
                mimeType = "application/gzip",
                bytes = file.bytes,
            )
        } catch (_: SyncTransportException.AlreadyExists) {
            transport.update(id, file.bytes)
        } catch (error: SyncTransportException.Server) {
            // Drive refused our pre-allocated id (e.g. fileIdNotUsable): allocate a new one next time.
            if (error.code == 400) dao.deleteMeta(pendingKey)
            throw error
        }
        if (created != null) dao.deleteMeta(pendingKey)
        return created
    }

    /** Local-only: bind newly connected accounts and capture/apply without the network. */
    private suspend fun runLocalCapture() {
        mutex.withLock {
            if (!isEnabled()) return
            runCatching {
                val bindings = refreshBindings()
                val engine = engine()
                engine.capture(bindings.active)
                val applied = engine.apply(bindings.active)
                summarize(applied)
            }
        }
        persist()
        refreshState()
    }

    private suspend fun refreshBindings(): BindingSnapshot {
        val cloudAccounts = dao.recordsOfKind(SyncKinds.ACCOUNT)
            .filter { !it.deleted }
            .mapNotNull { CloudAccountDescriptor.fromRecord(it.toRecord()) }
        return binder.refresh(cloudAccounts).also { lastBindings = it }
    }

    private suspend fun summarize(applied: ApplyResult) {
        dao.deleteMeta(SyncMetaKeys.LAST_SUMMARY)
        fun count(kind: String) = applied.appliedLive.values.sumOf { it[kind] ?: 0 }
        val restored = count(SyncKinds.SONG_NOTE) + count(SyncKinds.TRACK_RATING) + count(SyncKinds.ALBUM_RATING)
        if (restored == 0) return
        putMeta(SyncMetaKeys.LAST_SUMMARY, summaryText(restored))
    }

    /**
     * Finishes a "delete cloud data" that was interrupted anywhere after PENDING_RESET was saved: before its
     * marker was created, before this device went off in the new epoch, or part-way through deleting files.
     * The same steps as the first attempt, each one idempotent; nothing is deleted until the marker exists.
     */
    private suspend fun resumePendingReset() {
        runCatching {
            mutex.withLock {
                val (epoch, markerId) = dao.meta(SyncMetaKeys.PENDING_RESET)?.let(::parsePendingReset)
                    ?: return@withLock
                val location = activeLocation()
                val transport = activeTransport(location)
                val about = transport.about()
                if (about.permissionId != ownerPermissionId(location)) return@withLock
                finishReset(epoch, markerId, transport.list(), transport)
            }
        }
        refreshState()
    }

    private suspend fun resumePendingMigration() {
        runCatching {
            mutex.withLock { retryPendingMigrationLocked() }
        }
        refreshState()
    }

    private suspend fun retryPendingMigrationLocked() {
        val raw = dao.meta(SyncMetaKeys.PENDING_MIGRATION) ?: return
        val parts = raw.split('|')
        if (parts.size != 4) {
            dao.deleteMeta(SyncMetaKeys.PENDING_MIGRATION)
            return
        }
        val oldLocation = parts[0]
        val targetLocation = parts[1]
        val markerEpoch = parts[2].toLongOrNull()
        if (markerEpoch == null) {
            dao.deleteMeta(SyncMetaKeys.PENDING_MIGRATION)
            return
        }
        val markerId = parts[3]
        if (oldLocation == activeLocation()) {
            dao.deleteMeta(SyncMetaKeys.PENDING_MIGRATION)
            return
        }
        val oldTransport = activeTransport(oldLocation)
        val resolvedId = markerId.ifEmpty {
            oldTransport.generateIds(1).first().also { fresh ->
                putMeta(SyncMetaKeys.PENDING_MIGRATION, "$oldLocation|$targetLocation|$markerEpoch|$fresh")
                safePersist()
            }
        }
        try {
            writeMigrationMarker(
                transport = oldTransport,
                markerId = resolvedId,
                markerEpoch = markerEpoch,
                targetLocation = targetLocation,
                at = clock(),
            )
        } catch (error: SyncTransportException.Server) {
            // Backend rejected a pre-allocated id (e.g. Drive 400): clear id so next retry allocates a fresh one.
            if (error.code == 400 && markerId.isNotEmpty()) {
                putMeta(SyncMetaKeys.PENDING_MIGRATION, "$oldLocation|$targetLocation|$markerEpoch|")
                safePersist()
            }
            throw error
        }
        dao.deleteMeta(SyncMetaKeys.PENDING_MIGRATION)
        safePersist()
    }

    /**
     * The reset state machine after PENDING_RESET = "[epoch]|[markerId]" is on disk: (1) make sure the marker
     * exists in the store (create it, or find it already there), (2) turn this device off in the new epoch with
     * an empty replica, (3) delete every other Yoin file, (4) clear PENDING_RESET. Safe to run again from
     * any step; files are only deleted once peers can see the marker that explains why.
     */
    private suspend fun finishReset(
        epoch: Long,
        markerId: String,
        files: List<RemoteFile>,
        transport: SyncTransport,
    ) {
        if (files.none { it.id == markerId }) {
            try {
                transport.create(
                    fileId = markerId,
                    name = SyncFileProps.resetFileName(epoch, deviceId()),
                    appProperties = mapOf(
                        SyncFileProps.KIND to SyncFileProps.KIND_RESET,
                        SyncFileProps.EPOCH to epoch.toString(),
                        SyncFileProps.DEVICE_ID to deviceId(),
                    ),
                    mimeType = "application/json",
                    bytes = SnapshotCodec.encodeReset(
                        ResetMarker(epoch = epoch, at = clock(), deviceId = deviceId(), deviceName = deviceName()),
                    ),
                )
            } catch (_: SyncTransportException.AlreadyExists) {
                // Created by the interrupted attempt after the list was taken.
            }
        }
        // Local side before any deletion: from here on this device is off and in the new epoch.
        if (dao.meta(SyncMetaKeys.EPOCH)?.toLongOrNull() != epoch || isEnabled()) {
            engine().clearReplica()
            resetPublishBookkeeping()
            dao.deleteMeta(SyncMetaKeys.PARTICIPATED)
            dao.deleteMeta(SyncMetaKeys.LAST_SUMMARY)
            dao.deleteMeta(SyncMetaKeys.LAST_ERROR)
            dao.deleteMeta(SyncMetaKeys.REPLICA_PERMISSION_ID)
            putMeta(SyncMetaKeys.EPOCH, epoch.toString())
            putMeta(SyncMetaKeys.ENABLED, "false")
            persist()
        }
        deleteYoinFilesExcept(files, markerId, transport)
        dao.deleteMeta(SyncMetaKeys.PENDING_RESET)
    }

    private suspend fun deleteYoinFilesExcept(files: List<RemoteFile>, keepId: String, transport: SyncTransport) {
        files.filter { it.isYoinFile() && it.id != keepId }.forEach { file ->
            check(transport.delete(file.id)) { "Couldn't delete ${file.name}" }
        }
    }

    /** Downloads every file in [files] and hands them to [backupSink]; the folder name, or null. */
    private suspend fun backupAll(
        files: List<RemoteFile>,
        transport: SyncTransport,
        locationOverride: String? = null,
    ): String? {
        val sink = backupSink ?: return "none"
        val loc = locationOverride ?: activeLocation()
        val downloaded = files.map { file ->
            SyncBackupFile(file.id, file.name, file.version, file.modifiedTime, file.size, file.appProperties,
                transport.download(file.id))
        }
        val manifest = kotlinx.serialization.json.JsonObject(
            mapOf(
                "takenAt" to kotlinx.serialization.json.JsonPrimitive(clock()),
                "deviceId" to kotlinx.serialization.json.JsonPrimitive(deviceId()),
                "location" to kotlinx.serialization.json.JsonPrimitive(loc),
                "googlePermissionId" to kotlinx.serialization.json.JsonPrimitive(ownerPermissionId(loc)),
                "files" to kotlinx.serialization.json.JsonArray(
                    downloaded.map { f ->
                        kotlinx.serialization.json.JsonObject(
                            mapOf(
                                "id" to kotlinx.serialization.json.JsonPrimitive(f.id),
                                "name" to kotlinx.serialization.json.JsonPrimitive(f.name),
                                "version" to kotlinx.serialization.json.JsonPrimitive(f.version),
                                "modifiedTime" to kotlinx.serialization.json.JsonPrimitive(f.modifiedTime),
                                "size" to kotlinx.serialization.json.JsonPrimitive(f.bytes?.size),
                                "appProperties" to kotlinx.serialization.json.JsonObject(
                                    f.appProperties.mapValues { kotlinx.serialization.json.JsonPrimitive(it.value) },
                                ),
                            ),
                        )
                    },
                ),
            ),
        )
        val folder = sink.save(downloaded, manifest.toString()) ?: return null
        putMeta(META_LAST_BACKUP, folder)
        return folder
    }

    /** Backs up the whole active cloud store now (Settings › Export backup); the folder name, or null. */
    @Throws(Throwable::class)
    suspend fun backupNow(): String? = run {
        mutex.withLock {
            val transport = activeTransport()
            backupAll(transport.list(), transport)
        }.also { refreshState() }
    }

    private fun parsePendingReset(value: String): Pair<Long, String>? {
        val epoch = value.substringBefore('|').toLongOrNull() ?: return null
        val id = value.substringAfter('|', "").takeIf { it.isNotEmpty() } ?: return null
        return epoch to id
    }

    // ------------------------------------------------------------------ state

    private suspend fun ensureInitialized() {
        if (initialized) return
        mutex.withLock {
            if (initialized) return
            deviceId()
            initialized = true
            // Account rows need no network: show them even when the first cycle can't reach Google.
            if (isEnabled()) runCatching { refreshBindings() }
        }
        refreshState()
    }

    private suspend fun refreshState() {
        val enabled = isEnabled()
        val location = activeLocation()
        val lastError = dao.meta(SyncMetaKeys.LAST_ERROR)
        val phase = when {
            !enabled -> offPhase(lastError)
            mutex.isLocked && _state.value.phase == CloudSyncPhase.Syncing -> CloudSyncPhase.Syncing
            else -> onPhase(lastError, location)
        }
        val me = deviceId()
        val thisDevice = SyncDeviceRow(
            deviceId = me,
            name = deviceName(),
            isThisDevice = true,
            lastSyncAt = dao.meta(SyncMetaKeys.LAST_SYNC_AT)?.toLongOrNull(),
        )
        val peers = dao.remoteFiles()
            .filter { !it.own && it.deviceId != me && it.fileKind == SyncFileProps.KIND_STATE }
            .map { file ->
                SyncDeviceRow(
                    deviceId = file.deviceId,
                    name = file.deviceName ?: "Unknown device",
                    isThisDevice = false,
                    lastSyncAt = file.writtenAt ?: file.modifiedTime,
                )
            }
            .sortedByDescending { it.lastSyncAt ?: 0L }
        val bindings = lastBindings
        val accountUi = if (location == SyncLocations.ICLOUD) {
            ownerPermissionId(SyncLocations.ICLOUD)?.let { GoogleAccountUi(null, "iCloud") }
        } else {
            ownerPermissionId(SyncLocations.GOOGLE_DRIVE)?.let {
                GoogleAccountUi(dao.meta(SyncMetaKeys.GOOGLE_EMAIL), dao.meta(SyncMetaKeys.GOOGLE_DISPLAY_NAME))
            }
        }
        publish(
            CloudSyncState(
                phase = phase,
                account = accountUi,
                lastSyncAt = thisDevice.lastSyncAt,
                pendingChanges = enabled && dao.pendingCount() > 0,
                accounts = bindings?.rows.orEmpty(),
                cloudOnlyAccounts = bindings?.cloudOnly.orEmpty(),
                devices = if (enabled || peers.isNotEmpty()) listOf(thisDevice) + peers else emptyList(),
                lastSummary = dao.meta(SyncMetaKeys.LAST_SUMMARY),
                readOnly = readOnlyFirstPull && enabled && dao.meta(META_TWO_WAY) != "true",
                lastBackup = dao.meta(META_LAST_BACKUP),
                location = location,
                pendingMigrationNotice = dao.meta(SyncMetaKeys.PENDING_MIGRATION) != null,
            ),
        )
    }

    private fun publish(state: CloudSyncState) {
        _state.value = state
        onStateChange?.invoke(state)
    }

    private fun setPhase(phase: CloudSyncPhase) = publish(_state.value.copy(phase = phase))

    private fun offPhase(lastError: String?): CloudSyncPhase {
        if (lastError == CODE_DELETED_HERE) return CloudSyncPhase.Off
        if (lastError?.startsWith(CODE_RESET_PREFIX) == true) {
            return CloudSyncPhase.ResetElsewhere(lastError.removePrefix(CODE_RESET_PREFIX).ifBlank { null })
        }
        return CloudSyncPhase.Off
    }

    private suspend fun onPhase(lastError: String?, location: String): CloudSyncPhase = when {
        lastError == null -> CloudSyncPhase.UpToDate
        lastError == CODE_REAUTH || lastError == CODE_ACCOUNT_MISMATCH -> CloudSyncPhase.NeedsReauth
        lastError == CODE_OFFLINE -> CloudSyncPhase.Offline
        lastError == CODE_STORAGE -> CloudSyncPhase.StorageFull
        lastError == CODE_CLOUD_COPY_REMOVED -> CloudSyncPhase.CloudCopyRemoved
        lastError == CODE_MISCONFIGURED -> CloudSyncPhase.Error(
            if (location == SyncLocations.ICLOUD) {
                "iCloud isn't set up for this build of Yoin."
            } else {
                "Google Drive isn't set up for this build of Yoin."
            },
        )
        lastError == CODE_REVIEW -> {
            val held = engine().heldReview()
            if (held == null) {
                CloudSyncPhase.UpToDate
            } else {
                val name = lastBindings?.rows?.firstOrNull { row ->
                    lastBindings?.active?.any { binding ->
                        binding.syncProfileId == held.scope && binding.localProfileId == row.localProfileId
                    } == true
                }?.displayName ?: "an account"
                CloudSyncPhase.NeedsReview(name, held.count)
            }
        }
        lastError.startsWith(CODE_UPDATE_PREFIX) ->
            CloudSyncPhase.UpdateRequired(lastError.removePrefix(CODE_UPDATE_PREFIX).ifBlank { null })
        lastError.startsWith(CODE_MIGRATED_PREFIX) -> {
            val body = lastError.removePrefix(CODE_MIGRATED_PREFIX)
            val target = body.substringBefore('|')
            val device = body.substringAfter('|', "").ifBlank { null }
            CloudSyncPhase.MigratedElsewhere(target, device)
        }
        lastError.startsWith(ERROR_PREFIX) -> CloudSyncPhase.Error(lastError.removePrefix(ERROR_PREFIX))
        else -> CloudSyncPhase.Error(lastError)
    }

    private fun handleTransportError(error: SyncTransportException, location: String): String = when (error) {
        is SyncTransportException.Unauthorized -> CODE_REAUTH
        is SyncTransportException.Misconfigured -> CODE_MISCONFIGURED
        is SyncTransportException.StorageFull -> CODE_STORAGE
        is SyncTransportException.RateLimited, is SyncTransportException.Network -> CODE_OFFLINE
        is SyncTransportException.AlreadyExists, is SyncTransportException.Server ->
            ERROR_PREFIX + describe(error, location)
    }

    private fun describe(error: SyncTransportException, location: String = SyncLocations.GOOGLE_DRIVE): String {
        val isICloud = location == SyncLocations.ICLOUD
        return when (error) {
            is SyncTransportException.Network -> if (isICloud) {
                "Couldn't reach iCloud. Check your connection."
            } else {
                "Couldn't reach Google. Check your connection."
            }
            is SyncTransportException.StorageFull -> if (isICloud) {
                "Your iCloud storage is full."
            } else {
                "Your Google storage is full."
            }
            is SyncTransportException.Misconfigured -> "Cloud sync isn't available in this build of Yoin."
            is SyncTransportException.Unauthorized -> if (isICloud) {
                "Sign in to iCloud in Settings to use iCloud sync."
            } else {
                "Google needs your permission again."
            }
            is SyncTransportException.RateLimited -> if (isICloud) {
                "iCloud asked Yoin to slow down. Trying again later."
            } else {
                "Google asked Yoin to slow down. Trying again later."
            }
            is SyncTransportException.Server -> if (isICloud) {
                "iCloud error (${error.code})."
            } else {
                "Google Drive error (${error.code})."
            }
            is SyncTransportException.AlreadyExists -> if (isICloud) "iCloud conflict." else "Google Drive conflict."
        }
    }

    // ---- meta helpers

    private suspend fun isEnabled(): Boolean = dao.meta(SyncMetaKeys.ENABLED) == "true"

    private suspend fun putMeta(key: String, value: String) = dao.putMeta(SyncMetaEntity(key, value))

    private suspend fun resetPublishBookkeeping() {
        dao.deleteMeta(SyncMetaKeys.HAS_PUBLISHED)
        dao.metaWithPrefix(SyncMetaKeys.LAST_UPLOADED_HASH_PREFIX).forEach { dao.deleteMeta(it.key) }
        dao.metaWithPrefix(SyncMetaKeys.PENDING_FILE_ID_PREFIX).forEach { dao.deleteMeta(it.key) }
        dao.remoteFiles().filter { it.own }.forEach { dao.deleteRemoteFile(it.fileId) }
    }

    private var cachedDeviceId: String? = null

    /** 16 lowercase hex chars, like Android's UUID-derived id. */
    private suspend fun deviceId(): String {
        cachedDeviceId?.let { return it }
        val stored = dao.meta(SyncMetaKeys.DEVICE_ID)
        val id = stored ?: Random.nextBytes(8).joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }
            .also { dao.putMeta(SyncMetaEntity(SyncMetaKeys.DEVICE_ID, it)) }
        cachedDeviceId = id
        return id
    }

    private fun RemoteFile.isYoinFile(): Boolean = appProperties[SyncFileProps.KIND] in YOIN_FILE_KINDS

    private fun RemoteFile.resetEpoch(): Long? = appProperties[SyncFileProps.EPOCH]
        ?.takeIf { appProperties[SyncFileProps.KIND] == SyncFileProps.KIND_RESET }
        ?.toLongOrNull()

    private fun RemoteFile.migratedEpoch(): Long? = appProperties[SyncFileProps.EPOCH]
        ?.takeIf { appProperties[SyncFileProps.KIND] == SyncFileProps.KIND_MIGRATED }
        ?.toLongOrNull()

    private fun RemoteFile.fileKey(): String =
        "${appProperties[SyncFileProps.KIND]}:${appProperties[SyncFileProps.SHARD] ?: "0"}"

    companion object {
        const val FOREGROUND_THROTTLE_MS = 60_000L

        /** iOS-only meta: "true" once the read-only first pull has succeeded (writes allowed). */
        const val META_TWO_WAY = "ios_two_way"
        const val META_LAST_BACKUP = "ios_last_backup"
        const val META_LAST_ERROR_DETAIL = "last_error_detail"

        /** Android's LOCAL_DEBOUNCE_MS: platforms wait this long after a local change before [localChanged]. */
        const val LOCAL_DEBOUNCE_MS = 8_000L

        private val STATE_FILE_KEY = "${SyncFileProps.KIND_STATE}:0"

        private val YOIN_FILE_KINDS = setOf(
            SyncFileProps.KIND_PURGE,
            SyncFileProps.KIND_STATE,
            SyncFileProps.KIND_ARTIFACTS,
            SyncFileProps.KIND_RESET,
            SyncFileProps.KIND_MIGRATED,
        )

        // LAST_ERROR codes (Android's).
        const val CODE_REAUTH = "reauth"
        const val CODE_ACCOUNT_MISMATCH = "account_mismatch"
        const val CODE_OFFLINE = "offline"
        const val CODE_STORAGE = "storage"
        const val CODE_MISCONFIGURED = "misconfigured"
        const val CODE_REVIEW = "review"
        const val CODE_CLOUD_COPY_REMOVED = "cloud_copy_removed"
        const val CODE_DELETED_HERE = "deleted_here"
        const val CODE_RESET_PREFIX = "reset:"
        const val CODE_UPDATE_PREFIX = "update:"
        const val CODE_MIGRATED_PREFIX = "migrated:"
        const val ERROR_PREFIX = "error:"
    }
}
