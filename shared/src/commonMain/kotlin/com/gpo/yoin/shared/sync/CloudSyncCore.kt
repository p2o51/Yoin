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

    private val transport: SyncTransport by lazy { transportFactory(IdentityCheckedTokens(tokens)) }

    private fun clientFor(accessToken: String): SyncTransport = transportFactory(
        object : SyncTokenProvider {
            override suspend fun token(): String = accessToken

            override suspend fun invalidate(token: String) = Unit
        },
    )

    /**
     * Each token string is checked once against the stored Google identity before any request uses it,
     * so a cycle can never read from or write into another account's Drive.
     */
    private inner class IdentityCheckedTokens(private val inner: SyncTokenProvider) : SyncTokenProvider {
        @Volatile private var verified: String? = null

        override suspend fun token(): String {
            val token = inner.token()
            if (token == verified) return token
            val expected = dao.meta(SyncMetaKeys.GOOGLE_PERMISSION_ID)
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

    // ------------------------------------------------------------------ public API

    @Throws(Throwable::class)
    suspend fun initialize() = run {
        ensureInitialized()
        if (dao.meta(SyncMetaKeys.PENDING_RESET) != null) resumePendingReset()
    }

    @Throws(Throwable::class)
    suspend fun isEnabledNow(): Boolean = run { isEnabled() }

    /** The Google account this device syncs with (or last synced with), for re-authorizing it silently. */
    @Throws(Throwable::class)
    suspend fun googleEmail(): String? = run { dao.meta(SyncMetaKeys.GOOGLE_EMAIL) }

    /**
     * Turn on (or reconnect) with a fresh access token for the account the user just chose.
     * Returns null when sync started, or a user-facing message.
     */
    @Throws(Throwable::class)
    suspend fun enable(accessToken: String): String? = run {
        ensureInitialized()
        val about = try {
            clientFor(accessToken).about()
        } catch (error: SyncTransportException.Misconfigured) {
            return@run "Cloud sync isn't available in this build of Yoin."
        } catch (error: SyncTransportException) {
            return@run describe(error)
        }
        mutex.withLock {
            val previousOwner = dao.meta(SyncMetaKeys.GOOGLE_PERMISSION_ID)
            val replicaOwner = dao.meta(SyncMetaKeys.REPLICA_PERMISSION_ID)
            val otherDrive = (previousOwner != null && previousOwner != about.permissionId) ||
                (replicaOwner != null && replicaOwner != about.permissionId)
            if (otherDrive) {
                // Different Google account: the replica describes another Drive. Domain data stays.
                engine().clearReplica()
                resetPublishBookkeeping()
                dao.deleteMeta(SyncMetaKeys.PARTICIPATED)
                dao.deleteMeta(SyncMetaKeys.LAST_SUMMARY)
            }
            // The reset epoch belongs to one Drive; the first cycle adopts this Drive's.
            if (previousOwner != about.permissionId) {
                dao.deleteMeta(SyncMetaKeys.EPOCH)
                // A new Drive: pull it read-only (and back it up) before the first write again.
                dao.deleteMeta(META_TWO_WAY)
            }
            putMeta(SyncMetaKeys.GOOGLE_PERMISSION_ID, about.permissionId)
            putMeta(SyncMetaKeys.REPLICA_PERMISSION_ID, about.permissionId)
            about.email?.let { putMeta(SyncMetaKeys.GOOGLE_EMAIL, it) } ?: dao.deleteMeta(SyncMetaKeys.GOOGLE_EMAIL)
            about.displayName?.let { putMeta(SyncMetaKeys.GOOGLE_DISPLAY_NAME, it) }
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
        val result = runCatching {
            mutex.withLock {
                // Never act on whatever account a token happens to come from.
                val about = transport.about()
                val owner = dao.meta(SyncMetaKeys.GOOGLE_PERMISSION_ID)
                check(owner != null && owner == about.permissionId) { "Reconnect Google before deleting cloud data." }
                val files = transport.list()
                // Never delete without a local copy of what is there.
                if (backupSink != null && dao.meta(SyncMetaKeys.PENDING_RESET) == null) {
                    checkNotNull(backupAll(files)) { "Couldn't back up your Drive data first; nothing was deleted." }
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
                    // A retry of an interrupted delete: the marker is already there.
                }
                // Local side first: from here on this device is off and in the new epoch.
                engine().clearReplica()
                resetPublishBookkeeping()
                dao.deleteMeta(SyncMetaKeys.PARTICIPATED)
                dao.deleteMeta(SyncMetaKeys.LAST_SUMMARY)
                dao.deleteMeta(SyncMetaKeys.LAST_ERROR)
                dao.deleteMeta(SyncMetaKeys.REPLICA_PERMISSION_ID)
                putMeta(SyncMetaKeys.EPOCH, epoch.toString())
                putMeta(SyncMetaKeys.ENABLED, "false")
                persist()
                deleteYoinFilesExcept(files, markerId)
                dao.deleteMeta(SyncMetaKeys.PENDING_RESET)
            }
            // No token revoke: other devices need the grant to read the marker and turn themselves off.
            Unit
        }
        refreshState()
        result.exceptionOrNull()?.let { error ->
            if (error is CancellationException) throw error
            (error as? SyncTransportException)?.let(::describe) ?: (error.message ?: "Couldn't delete cloud data.")
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

    @Throws(Throwable::class)
    suspend fun startFreshAccount(localProfileId: String) = run {
        ensureInitialized()
        // Publish anything still pending for the old account first, so nothing of it is lost.
        if (engine().hasPendingChanges()) runCycle(force = true)
        mutex.withLock {
            val plan = binder.prepareStartFresh(localProfileId) ?: return@withLock
            engine().purgeScopeLocally(plan.oldScope, localProfileId)
            binder.completeStartFresh(plan)
        }
        runCycle(force = true)
    }

    /** Delete another device's files from Drive (it re-uploads if it is still alive). */
    @Throws(Throwable::class)
    suspend fun removeDevice(deviceId: String) = run {
        mutex.withLock {
            runCatching {
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
            persist()
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
            val outcome = try {
                cycle()
            } catch (error: CancellationException) {
                throw error
            } catch (error: SyncTransportException) {
                detail = error.toString()
                handleTransportError(error)
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

    /** One network cycle. Returns a LAST_ERROR code, or null when everything went through. */
    private suspend fun cycle(): String? {
        val engine = engine()
        val me = deviceId()

        val about = transport.about()
        val owner = dao.meta(SyncMetaKeys.GOOGLE_PERMISSION_ID)
        if (owner != null && owner != about.permissionId) return CODE_ACCOUNT_MISMATCH

        val files = transport.list()

        // A newer "delete cloud data" from any device wins over everything.
        val resets = files.filter { it.appProperties[SyncFileProps.KIND] == SyncFileProps.KIND_RESET }
        val newest = resets.maxByOrNull { it.appProperties[SyncFileProps.EPOCH]?.toLongOrNull() ?: 0L }
        val newestEpoch = newest?.appProperties?.get(SyncFileProps.EPOCH)?.toLongOrNull() ?: 0L
        var myEpoch = dao.meta(SyncMetaKeys.EPOCH)?.toLongOrNull() ?: 0L
        if (newestEpoch < myEpoch) {
            // The epoch belongs to the Drive: a lower one means a different (or wiped) Drive.
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
        val listedIds = files.map { it.id }.toSet()
        for (file in peerFiles) {
            val seen = dao.remoteFile(file.id)
            if (seen != null && seen.version == file.version) continue
            val bytes = transport.download(file.id) ?: continue
            val envelope = try {
                SnapshotCodec.decode(bytes)
            } catch (error: SyncSchemaTooNewException) {
                updateRequiredFrom = error.deviceName ?: error.deviceId ?: file.appProperties[SyncFileProps.DEVICE_ID]
                continue
            } catch (error: MalformedSyncFileException) {
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
                // skip
            }
        }
        // Forget files that are gone (removed devices, deleted duplicates).
        dao.remoteFiles().filter { it.fileId !in listedIds && !it.own }.forEach { dao.deleteRemoteFile(it.fileId) }

        val bindings = refreshBindings()
        engine.capture(bindings.active)
        val applied = engine.apply(bindings.active)
        summarize(applied)
        persist()

        if (!twoWay) {
            // Read-only first pull: map only. Back the whole folder up; two-way from the next cycle
            // only when the pull mapped cleanly and the backup is on disk.
            val mapped = applied.failures.isEmpty() && updateRequiredFrom == null
            val backup = backupAll(files)
            if (mapped && backup != null) putMeta(META_TWO_WAY, "true")
            putMeta(SyncMetaKeys.LAST_SYNC_AT, clock().toString())
            updateRequiredFrom?.let { return CODE_UPDATE_PREFIX + it }
            return if (backup == null) ERROR_PREFIX + "Couldn't back up your Drive data on this device." else null
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
            val uploaded = upload(existing, file, me) ?: continue
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

    private suspend fun upload(existing: RemoteFile?, file: OutgoingFile, me: String): RemoteFile? {
        if (existing != null) {
            transport.update(existing.id, file.bytes)?.let { return it }
        }
        val pendingKey = SyncMetaKeys.PENDING_FILE_ID_PREFIX + "${file.fileKind}:${file.shard}"
        val id = dao.meta(pendingKey) ?: transport.generateIds(1).first().also {
            putMeta(pendingKey, it)
            persist()
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

    /** Finishes a "delete cloud data" that was interrupted after its marker was written. */
    private suspend fun resumePendingReset() {
        runCatching {
            mutex.withLock {
                val (_, markerId) = dao.meta(SyncMetaKeys.PENDING_RESET)?.let(::parsePendingReset) ?: return@withLock
                val about = transport.about()
                if (about.permissionId != dao.meta(SyncMetaKeys.GOOGLE_PERMISSION_ID)) return@withLock
                deleteYoinFilesExcept(transport.list(), markerId)
                dao.deleteMeta(SyncMetaKeys.PENDING_RESET)
            }
        }
        refreshState()
    }

    private suspend fun deleteYoinFilesExcept(files: List<RemoteFile>, keepId: String) {
        files.filter { it.isYoinFile() && it.id != keepId }.forEach { file ->
            check(transport.delete(file.id)) { "Couldn't delete ${file.name}" }
        }
    }

    /** Downloads every file in [files] and hands them to [backupSink]; the folder name, or null. */
    private suspend fun backupAll(files: List<RemoteFile>): String? {
        val sink = backupSink ?: return "none"
        val downloaded = files.map { file ->
            SyncBackupFile(file.id, file.name, file.version, file.modifiedTime, file.size, file.appProperties,
                transport.download(file.id))
        }
        val manifest = kotlinx.serialization.json.JsonObject(
            mapOf(
                "takenAt" to kotlinx.serialization.json.JsonPrimitive(clock()),
                "deviceId" to kotlinx.serialization.json.JsonPrimitive(deviceId()),
                "googlePermissionId" to kotlinx.serialization.json.JsonPrimitive(dao.meta(SyncMetaKeys.GOOGLE_PERMISSION_ID)),
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

    /** Backs up the whole appDataFolder now (Settings › Export backup); the folder name, or null. */
    @Throws(Throwable::class)
    suspend fun backupNow(): String? = run {
        mutex.withLock { backupAll(transport.list()) }.also { refreshState() }
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
        val lastError = dao.meta(SyncMetaKeys.LAST_ERROR)
        val phase = when {
            !enabled -> offPhase(lastError)
            mutex.isLocked && _state.value.phase == CloudSyncPhase.Syncing -> CloudSyncPhase.Syncing
            else -> onPhase(lastError)
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
        publish(
            CloudSyncState(
                phase = phase,
                account = dao.meta(SyncMetaKeys.GOOGLE_PERMISSION_ID)?.let {
                    GoogleAccountUi(dao.meta(SyncMetaKeys.GOOGLE_EMAIL), dao.meta(SyncMetaKeys.GOOGLE_DISPLAY_NAME))
                },
                lastSyncAt = thisDevice.lastSyncAt,
                pendingChanges = enabled && dao.pendingCount() > 0,
                accounts = bindings?.rows.orEmpty(),
                cloudOnlyAccounts = bindings?.cloudOnly.orEmpty(),
                devices = if (enabled || peers.isNotEmpty()) listOf(thisDevice) + peers else emptyList(),
                lastSummary = dao.meta(SyncMetaKeys.LAST_SUMMARY),
                readOnly = readOnlyFirstPull && enabled && dao.meta(META_TWO_WAY) != "true",
                lastBackup = dao.meta(META_LAST_BACKUP),
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

    private suspend fun onPhase(lastError: String?): CloudSyncPhase = when {
        lastError == null -> CloudSyncPhase.UpToDate
        lastError == CODE_REAUTH || lastError == CODE_ACCOUNT_MISMATCH -> CloudSyncPhase.NeedsReauth
        lastError == CODE_OFFLINE -> CloudSyncPhase.Offline
        lastError == CODE_STORAGE -> CloudSyncPhase.StorageFull
        lastError == CODE_CLOUD_COPY_REMOVED -> CloudSyncPhase.CloudCopyRemoved
        lastError == CODE_MISCONFIGURED -> CloudSyncPhase.Error("Google Drive isn't set up for this build of Yoin.")
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
        lastError.startsWith(ERROR_PREFIX) -> CloudSyncPhase.Error(lastError.removePrefix(ERROR_PREFIX))
        else -> CloudSyncPhase.Error(lastError)
    }

    private fun handleTransportError(error: SyncTransportException): String = when (error) {
        is SyncTransportException.Unauthorized -> CODE_REAUTH
        is SyncTransportException.Misconfigured -> CODE_MISCONFIGURED
        is SyncTransportException.StorageFull -> CODE_STORAGE
        is SyncTransportException.RateLimited, is SyncTransportException.Network -> CODE_OFFLINE
        is SyncTransportException.AlreadyExists, is SyncTransportException.Server -> ERROR_PREFIX + describe(error)
    }

    private fun describe(error: SyncTransportException): String = when (error) {
        is SyncTransportException.Network -> "Couldn't reach Google. Check your connection."
        is SyncTransportException.StorageFull -> "Your Google storage is full."
        is SyncTransportException.Misconfigured -> "Cloud sync isn't available in this build of Yoin."
        is SyncTransportException.Unauthorized -> "Google needs your permission again."
        is SyncTransportException.RateLimited -> "Google asked Yoin to slow down. Trying again later."
        is SyncTransportException.Server -> "Google Drive error (${error.code})."
        is SyncTransportException.AlreadyExists -> "Google Drive conflict."
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
            SyncFileProps.KIND_STATE,
            SyncFileProps.KIND_ARTIFACTS,
            SyncFileProps.KIND_RESET,
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
        const val ERROR_PREFIX = "error:"
    }
}
