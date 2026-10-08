package com.gpo.yoin.data.sync

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.os.Build
import android.provider.Settings
import android.util.Log
import com.gpo.yoin.R
import com.gpo.yoin.data.local.YoinDatabase
import com.gpo.yoin.data.profile.ProfileManager
import com.gpo.yoin.data.sync.adapters.SharedPrefsSeamStyleGateway
import com.gpo.yoin.data.sync.adapters.YoinSyncAdapters
import com.gpo.yoin.data.sync.auth.AuthorizerTokenProvider
import com.gpo.yoin.data.sync.auth.DebugSyncAuthorizer
import com.gpo.yoin.data.sync.auth.DebugSyncOverrides
import com.gpo.yoin.data.sync.auth.PlayServicesSyncAuthorizer
import com.gpo.yoin.data.sync.drive.DriveAppDataClient
import com.gpo.yoin.data.sync.engine.ApplyResult
import com.gpo.yoin.data.sync.engine.MalformedSyncFileException
import com.gpo.yoin.data.sync.engine.OutgoingFile
import com.gpo.yoin.data.sync.engine.SnapshotCodec
import com.gpo.yoin.data.sync.engine.SyncEngine
import com.gpo.yoin.data.sync.engine.SyncSchemaTooNewException
import com.gpo.yoin.data.sync.identity.AccountBinder
import com.gpo.yoin.data.sync.identity.BindingSnapshot
import com.gpo.yoin.data.sync.identity.CloudAccountDescriptor
import com.gpo.yoin.data.sync.identity.SpotifyIdentityResolver
import java.util.UUID
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient

/**
 * App-scoped owner of cloud sync: the only place that runs sync cycles.
 *
 * Construction does no I/O; everything starts from [onAppForeground] (the
 * whole-app started-activity counter in YoinApplication) or a UI action.
 * Every cycle — network or local capture — runs under one [Mutex], on a
 * scope whose failures become a status instead of crashing the app.
 */
class CloudSyncManager(
    context: Context,
    private val database: YoinDatabase,
    private val profileManager: ProfileManager,
    private val versionName: String,
    private val versionCode: Int,
    private val spotifyHttpClient: () -> OkHttpClient,
    private val clock: () -> Long = System::currentTimeMillis,
    private val hooks: CloudSyncTestHooks? = null,
) : CloudSyncController {
    private val appContext = context.applicationContext

    private val scope = CoroutineScope(
        SupervisorJob() + Dispatchers.IO + CoroutineExceptionHandler { _, error ->
            Log.w(TAG, "Cloud sync job failed", error)
            _state.update { it.copy(phase = CloudSyncPhase.Error(error.message ?: error.javaClass.simpleName)) }
        },
    )
    private val mutex = Mutex()
    private val stateMutex = Mutex()

    private val _state = MutableStateFlow(CloudSyncState())
    override val state: StateFlow<CloudSyncState> = _state.asStateFlow()

    private val syncDb: SyncDatabase by lazy { hooks?.syncDb ?: SyncDatabase.build(appContext) }
    private val dao: SyncDao get() = syncDb.syncDao()

    private val debugConfig by lazy { DebugSyncOverrides.load(appContext) }

    private val authorizer: GoogleSyncAuthorizer by lazy {
        hooks?.authorizer ?: debugConfig?.let { DebugSyncAuthorizer(it) } ?: PlayServicesSyncAuthorizer(appContext)
    }

    private val tokenProvider by lazy {
        AuthorizerTokenProvider(authorizer) { dao.meta(SyncMetaKeys.GOOGLE_EMAIL) }
    }

    private val driveHttp by lazy { DriveAppDataClient.defaultHttpClient(versionName) }

    private val transport: SyncTransport by lazy {
        hooks?.transport ?: driveClient(IdentityCheckedTokens(tokenProvider))
    }

    private fun driveClient(tokens: SyncTokenProvider): SyncTransport {
        val base = debugConfig?.baseUrl
        return if (base != null) {
            DriveAppDataClient(
                http = driveHttp,
                tokens = tokens,
                apiBase = base.trimEnd('/') + "/drive/v3/",
                uploadBase = base.trimEnd('/') + "/upload/drive/v3/",
            )
        } else {
            DriveAppDataClient(http = driveHttp, tokens = tokens)
        }
    }

    /** A one-off client bound to exactly [accessToken] (identity checks; never falls back to another account). */
    private fun clientFor(accessToken: String): SyncTransport = hooks?.transport ?: driveClient(
        object : SyncTokenProvider {
            override suspend fun token(): String = accessToken

            override suspend fun invalidate(token: String) = Unit
        },
    )

    /**
     * Every Drive request asks Play services for a token, and a token for the pinned account can
     * silently fall back to the device's default account. Each token string is therefore checked
     * once against the stored Google identity before any request uses it, so a cycle can never
     * read from or write into another account's Drive.
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

    private val adapters: List<SyncAdapter> by lazy {
        YoinSyncAdapters.create(
            db = database,
            syncDb = syncDb,
            seam = hooks?.seam ?: SharedPrefsSeamStyleGateway(appContext),
            decodeCredentials = profileManager::decodeCredentials,
            profiles = { database.profileDao().getAll() },
            clock = clock,
        )
    }

    private val binder: AccountBinder by lazy {
        AccountBinder(
            syncDb = syncDb,
            profiles = { database.profileDao().getAll() },
            decodeCredentials = profileManager::decodeCredentials,
            spotify = SpotifyIdentityResolver(syncDb, spotifyHttpClient(), clock = clock),
            clock = clock,
        )
    }

    @Volatile private var engineInstance: SyncEngine? = null

    private suspend fun engine(): SyncEngine = engineInstance ?: SyncEngine(
        db = syncDb,
        adapters = adapters,
        deviceId = deviceId(),
        deviceName = ::deviceName,
        clock = clock,
    ).also { engineInstance = it }

    @Volatile private var initialized = false

    @Volatile private var lastCycleStartedAt = 0L

    @Volatile private var lastBindings: BindingSnapshot? = null

    @Volatile private var updateRequiredFrom: String? = null

    private var observerJob: Job? = null

    /** Strong reference: SharedPreferences keeps listeners weakly. */
    private val seamListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == SEAM_PREF_KEY) localChanges.tryEmit(Unit)
    }
    private val localChanges = MutableSharedFlow<Unit>(extraBufferCapacity = 1)

    // ---- lifecycle hooks (YoinApplication)

    init {
        // The Settings row / page subscribing is what first opens the sync DB; no I/O at construction.
        scope.launch {
            _state.subscriptionCount.first { it > 0 }
            ensureInitialized()
        }
    }

    fun onAppForeground() {
        scope.launch {
            // Never turned on: don't even create the sync DB.
            if (hooks == null && !syncDbFile().exists()) return@launch
            ensureInitialized()
            if (!isEnabled()) return@launch
            if (clock() - lastCycleStartedAt < FOREGROUND_THROTTLE_MS) return@launch
            runCycle()
        }
    }

    fun onAppBackground() {
        scope.launch {
            if (!initialized || (hooks == null && !syncDbFile().exists()) || !isEnabled()) return@launch
            if (engine().hasPendingChanges()) runCycle()
        }
    }

    // ---- CloudSyncController

    override suspend fun beginTurnOn(): TurnOnStep = withIo {
        ensureInitialized()
        if (debugConfig == null) {
            when (authorizer.availability()) {
                GoogleAuthAvailability.Available -> Unit
                GoogleAuthAvailability.ServicesMissing -> {
                    setPhase(CloudSyncPhase.Unavailable(updateRequired = false))
                    return@withIo TurnOnStep.Failed("Cloud sync needs Google Play services.")
                }
                GoogleAuthAvailability.ServicesUpdateRequired -> {
                    setPhase(CloudSyncPhase.Unavailable(updateRequired = true))
                    return@withIo TurnOnStep.Failed("Update Google Play services to use Cloud sync.")
                }
            }
        }
        handleAuthResult(authorizer.authorizeInteractively())
    }

    override suspend fun completeTurnOn(resultCode: Int, data: Intent?): TurnOnStep = withIo {
        if (resultCode != Activity.RESULT_OK && data == null) return@withIo TurnOnStep.Canceled
        handleAuthResult(authorizer.resultFromIntent(data))
    }

    private suspend fun handleAuthResult(result: GoogleAuthResult): TurnOnStep = when (result) {
        is GoogleAuthResult.Token -> completeEnable(result.accessToken)
        is GoogleAuthResult.NeedsResolution -> TurnOnStep.NeedsConsent(result.intentSender)
        GoogleAuthResult.Canceled -> TurnOnStep.Canceled
        GoogleAuthResult.Misconfigured -> {
            markMisconfigured()
            TurnOnStep.Failed("Cloud sync isn't available in this build of Yoin.")
        }
        is GoogleAuthResult.Failure -> TurnOnStep.Failed(result.message)
    }

    private suspend fun completeEnable(accessToken: String): TurnOnStep {
        // Identify the account just chosen with ITS token: the shared provider may still be pinned
        // to a previously used account.
        val about = try {
            clientFor(accessToken).about()
        } catch (error: SyncTransportException.Misconfigured) {
            markMisconfigured()
            return TurnOnStep.Failed("Cloud sync isn't available in this build of Yoin.")
        } catch (error: SyncTransportException) {
            return TurnOnStep.Failed(describe(error))
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
            if (previousOwner != about.permissionId) dao.deleteMeta(SyncMetaKeys.EPOCH)
            putMeta(SyncMetaKeys.GOOGLE_PERMISSION_ID, about.permissionId)
            putMeta(SyncMetaKeys.REPLICA_PERMISSION_ID, about.permissionId)
            about.email?.let { putMeta(SyncMetaKeys.GOOGLE_EMAIL, it) } ?: dao.deleteMeta(SyncMetaKeys.GOOGLE_EMAIL)
            about.displayName?.let { putMeta(SyncMetaKeys.GOOGLE_DISPLAY_NAME, it) }
            dao.deleteMeta(SyncMetaKeys.MISCONFIGURED_FOR)
            dao.deleteMeta(SyncMetaKeys.LAST_ERROR)
            putMeta(SyncMetaKeys.ENABLED, "true")
        }
        startObservers()
        refreshState()
        scope.launch { runCycle(force = true) }
        return TurnOnStep.Started
    }

    override fun syncNow() {
        scope.launch {
            ensureInitialized()
            if (isEnabled()) runCycle(force = true)
        }
    }

    override fun turnOff() {
        scope.launch {
            mutex.withLock {
                putMeta(SyncMetaKeys.ENABLED, "false")
                dao.deleteMeta(SyncMetaKeys.LAST_ERROR)
            }
            stopObservers()
            runCatching { tokenProvider.invalidateCached() }
            refreshState()
        }
    }

    override suspend fun deleteCloudData(): Result<Unit> = withIo {
        val result = runCatching {
            mutex.withLock {
                // Never act on whatever account a token happens to come from.
                val about = transport.about()
                val owner = dao.meta(SyncMetaKeys.GOOGLE_PERMISSION_ID)
                check(owner != null && owner == about.permissionId) { "Reconnect Google before deleting cloud data." }
                val files = transport.list()
                val pending = dao.meta(SyncMetaKeys.PENDING_RESET)?.let(::parsePendingReset)
                // Strictly newer than every marker any device has seen, whatever the local clock says.
                val epoch = pending?.first ?: maxOf(
                    clock(),
                    (files.maxOfOrNull { it.resetEpoch() ?: 0L } ?: 0L) + 1,
                    (dao.meta(SyncMetaKeys.EPOCH)?.toLongOrNull() ?: 0L) + 1,
                )
                val markerId = pending?.second ?: transport.generateIds(1).first()
                putMeta(SyncMetaKeys.PENDING_RESET, "$epoch|$markerId")
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
                // Local side first: from here on this device is off and in the new epoch, so an
                // interrupted delete can neither re-upload old data nor mistake its own marker.
                engine().clearReplica()
                resetPublishBookkeeping()
                dao.deleteMeta(SyncMetaKeys.PARTICIPATED)
                dao.deleteMeta(SyncMetaKeys.LAST_SUMMARY)
                dao.deleteMeta(SyncMetaKeys.LAST_ERROR)
                dao.deleteMeta(SyncMetaKeys.REPLICA_PERMISSION_ID)
                putMeta(SyncMetaKeys.EPOCH, epoch.toString())
                putMeta(SyncMetaKeys.ENABLED, "false")
                deleteYoinFilesExcept(files, markerId)
                dao.deleteMeta(SyncMetaKeys.PENDING_RESET)
            }
            // No revokeAccess: other devices need the grant to read the marker and turn themselves off.
            runCatching { tokenProvider.invalidateCached() }
            Unit
        }
        // Even a half-finished delete has already turned this device off.
        if (!isEnabled()) stopObservers()
        refreshState()
        result
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
        }.onFailure { Log.w(TAG, "Resuming cloud data deletion failed", it) }
    }

    private suspend fun deleteYoinFilesExcept(files: List<RemoteFile>, keepId: String) {
        files.filter { it.isYoinFile() && it.id != keepId }.forEach { file ->
            check(transport.delete(file.id)) { "Couldn't delete ${file.name}" }
        }
    }

    private fun parsePendingReset(value: String): Pair<Long, String>? {
        val epoch = value.substringBefore('|').toLongOrNull() ?: return null
        val id = value.substringAfter('|', "").takeIf { it.isNotEmpty() } ?: return null
        return epoch to id
    }

    override fun resolveReview(restore: Boolean) {
        scope.launch {
            mutex.withLock {
                engine().resolveHeldReview(restore)
                dao.deleteMeta(SyncMetaKeys.LAST_ERROR)
            }
            runCycle(force = true)
        }
    }

    override fun uploadAgain() {
        scope.launch {
            mutex.withLock {
                resetPublishBookkeeping()
                dao.deleteMeta(SyncMetaKeys.LAST_ERROR)
            }
            runCycle(force = true)
        }
    }

    override fun startSyncingAccount(localProfileId: String) = accountAction { binder.startSyncing(localProfileId) }

    override fun linkAccount(localProfileId: String, syncProfileId: String) =
        accountAction { binder.link(localProfileId, syncProfileId) }

    override fun keepAccountAsBefore(localProfileId: String) = accountAction { binder.keepAsBefore(localProfileId) }

    override fun startFreshAccount(localProfileId: String) {
        scope.launch {
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
    }

    override fun removeDevice(deviceId: String) {
        scope.launch {
            mutex.withLock {
                runCatching {
                    dao.remoteFiles().filter { it.deviceId == deviceId && !it.own }.forEach { file ->
                        if (transport.delete(file.fileId)) dao.deleteRemoteFile(file.fileId)
                    }
                }.onFailure { Log.w(TAG, "Remove device failed", it) }
            }
            refreshState()
        }
    }

    private fun accountAction(block: suspend () -> Unit) {
        scope.launch {
            ensureInitialized()
            mutex.withLock { block() }
            runLocalCapture()
            runCycle(force = true)
        }
    }

    // ---- cycles

    private suspend fun runCycle(force: Boolean = false) {
        if (!force && mutex.isLocked) return
        mutex.withLock {
            if (!isEnabled()) return
            lastCycleStartedAt = clock()
            setPhase(CloudSyncPhase.Syncing)
            val outcome = try {
                cycle()
            } catch (error: CancellationException) {
                throw error
            } catch (error: SyncTransportException) {
                handleTransportError(error)
            } catch (error: Exception) {
                Log.w(TAG, "Cloud sync cycle failed", error)
                ERROR_PREFIX + (error.message ?: error.javaClass.simpleName)
            }
            withContext(NonCancellable) {
                if (outcome == null) {
                    dao.deleteMeta(SyncMetaKeys.LAST_ERROR)
                } else {
                    putMeta(SyncMetaKeys.LAST_ERROR, outcome)
                }
            }
        }
        refreshState()
        // Last: this may cancel the observer coroutine that is running this very cycle.
        if (!isEnabled()) stopObservers()
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
            // The epoch belongs to the Drive: a lower one means a different (or wiped) Drive, which
            // has no pre-reset files to guard against. Markers are only ever replaced by newer ones.
            putMeta(SyncMetaKeys.EPOCH, newestEpoch.toString())
            myEpoch = newestEpoch
        }
        if (newestEpoch > myEpoch) {
            val ownMarker = newest?.appProperties?.get(SyncFileProps.DEVICE_ID) == me
            val participated = dao.meta(SyncMetaKeys.PARTICIPATED) == "true" ||
                dao.meta(SyncMetaKeys.HAS_PUBLISHED) == "true"
            if (participated || ownMarker) {
                // We hold data from before that reset (or it is our own unfinished delete): stop
                // instead of uploading it back.
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

        // Own files: keep the newest per (kind, shard); a crashed create may have left a duplicate.
        var keptOwn = ownFiles.groupBy { it.fileKey() }.mapValues { (_, group) ->
            val sorted = group.sortedByDescending { it.version }
            sorted.drop(1).forEach { runCatching { transport.delete(it.id) } }
            sorted.first()
        }
        val hasPublished = dao.meta(SyncMetaKeys.HAS_PUBLISHED) == "true"
        if (hasPublished && keptOwn[stateFileKey()] == null) {
            val othersStillThere = peerFiles.any { it.appProperties[SyncFileProps.KIND] == SyncFileProps.KIND_STATE }
            // Only our files are gone: another device removed us from sync. Re-publish (we may be
            // the only copy of some data). With every device's files gone, ask instead.
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
                Log.w(TAG, "Skipping unreadable sync file ${file.id}", error)
                continue
            }
            // Written before the newest "delete cloud data" (a device that raced the reset): ignore,
            // and remember the version so it isn't downloaded again every cycle.
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
                Log.w(TAG, "Skipping malformed sync file ${file.id}", error)
            }
        }
        // Forget files that are gone (removed devices, deleted duplicates).
        dao.remoteFiles().filter { it.fileId !in listedIds && !it.own }.forEach { dao.deleteRemoteFile(it.fileId) }

        val bindings = refreshBindings()
        engine.capture(bindings.active)
        val applied = engine.apply(bindings.active)
        summarize(applied, bindings)

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
        val id = dao.meta(pendingKey) ?: transport.generateIds(1).first().also { putMeta(pendingKey, it) }
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
                summarize(applied, bindings)
            }.onFailure { Log.w(TAG, "Local capture failed", it) }
        }
        refreshState()
    }

    private suspend fun refreshBindings(): BindingSnapshot {
        val cloudAccounts = dao.recordsOfKind(SyncKinds.ACCOUNT)
            .filter { !it.deleted }
            .mapNotNull { CloudAccountDescriptor.fromRecord(it.toRecord()) }
        return binder.refresh(cloudAccounts).also { lastBindings = it }
    }

    private suspend fun summarize(applied: ApplyResult, bindings: BindingSnapshot) {
        dao.deleteMeta(SyncMetaKeys.LAST_SUMMARY)
        fun count(kind: String) = applied.appliedLive.values.sumOf { it[kind] ?: 0 }
        val restored = count(SyncKinds.SONG_NOTE) +
            count(SyncKinds.TRACK_RATING) +
            count(SyncKinds.ALBUM_RATING)
        if (restored == 0) return
        putMeta(
            SyncMetaKeys.LAST_SUMMARY,
            appContext.getString(R.string.settings_sync_synced_other_devices_count, restored),
        )
    }

    // ---- observers

    @OptIn(FlowPreview::class)
    private fun startObservers() {
        if (observerJob?.isActive == true) return
        appContext.getSharedPreferences(SEAM_PREFS, Context.MODE_PRIVATE)
            .registerOnSharedPreferenceChangeListener(seamListener)
        observerJob = scope.launch {
            val tables = database.invalidationTracker.createFlow(*OBSERVED_TABLES, emitInitialState = false)
            merge(tables, localChanges)
                .debounce(LOCAL_DEBOUNCE_MS)
                .collect {
                    runLocalCapture()
                    if (engine().hasPendingChanges()) runCycle()
                }
        }
    }

    private fun stopObservers() {
        observerJob?.cancel()
        observerJob = null
        appContext.getSharedPreferences(SEAM_PREFS, Context.MODE_PRIVATE)
            .unregisterOnSharedPreferenceChangeListener(seamListener)
    }

    // ---- state

    private suspend fun ensureInitialized() {
        if (initialized) return
        mutex.withLock {
            if (initialized) return
            deviceId()
            initialized = true
            // Account rows need no network: show them even when the first cycle can't reach Google.
            if (isEnabled()) runCatching { refreshBindings() }.onFailure { Log.w(TAG, "Binding refresh failed", it) }
        }
        if (isEnabled()) startObservers()
        refreshState()
        if (dao.meta(SyncMetaKeys.PENDING_RESET) != null) scope.launch { resumePendingReset() }
    }

    /**
     * Compute and publish as one step: two refreshes racing (the cycle a turn-on launches and the
     * next one) must not publish out of order and leave a stale "Syncing" on screen.
     */
    private suspend fun refreshState() = stateMutex.withLock { publishState() }

    private suspend fun publishState() {
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
        _state.value = CloudSyncState(
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
        )
    }

    private suspend fun offPhase(lastError: String?): CloudSyncPhase {
        if (lastError == CODE_DELETED_HERE) return CloudSyncPhase.Off
        if (lastError?.startsWith(CODE_RESET_PREFIX) == true) {
            return CloudSyncPhase.ResetElsewhere(lastError.removePrefix(CODE_RESET_PREFIX).ifBlank { null })
        }
        if (dao.meta(SyncMetaKeys.MISCONFIGURED_FOR) == misconfiguredKey()) {
            return CloudSyncPhase.Misconfigured(appContext.packageName, authorizer.signingCertSha1())
        }
        if (debugConfig == null) {
            when (authorizer.availability()) {
                GoogleAuthAvailability.ServicesMissing -> return CloudSyncPhase.Unavailable(updateRequired = false)
                GoogleAuthAvailability.ServicesUpdateRequired ->
                    return CloudSyncPhase.Unavailable(updateRequired = true)
                GoogleAuthAvailability.Available -> Unit
            }
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

    private suspend fun handleTransportError(error: SyncTransportException): String = when (error) {
        is SyncTransportException.Unauthorized -> CODE_REAUTH
        // Can be transient (API just enabled, client just created): keep sync on and retry later.
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

    private suspend fun markMisconfigured() {
        putMeta(SyncMetaKeys.MISCONFIGURED_FOR, misconfiguredKey())
        setPhase(CloudSyncPhase.Misconfigured(appContext.packageName, authorizer.signingCertSha1()))
    }

    /** Re-offered after a reinstall/update: every build shares one versionCode. */
    private fun misconfiguredKey(): String {
        val installed = runCatching {
            appContext.packageManager.getPackageInfo(appContext.packageName, 0).lastUpdateTime
        }.getOrDefault(0L)
        return "$versionCode|${authorizer.signingCertSha1().orEmpty()}|$installed"
    }

    private fun setPhase(phase: CloudSyncPhase) {
        _state.update { it.copy(phase = phase) }
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

    @Volatile private var cachedDeviceId: String? = null

    private suspend fun deviceId(): String {
        cachedDeviceId?.let { return it }
        val stored = dao.meta(SyncMetaKeys.DEVICE_ID)
        val id = stored ?: UUID.randomUUID().toString().replace("-", "").take(16).also {
            dao.putMeta(SyncMetaEntity(SyncMetaKeys.DEVICE_ID, it))
        }
        cachedDeviceId = id
        return id
    }

    private fun deviceName(): String {
        hooks?.deviceName?.let { return it }
        val userSet = runCatching {
            Settings.Global.getString(appContext.contentResolver, Settings.Global.DEVICE_NAME)
        }.getOrNull()
        return (userSet?.takeIf { it.isNotBlank() } ?: Build.MODEL ?: "Android device").take(40)
    }

    private fun RemoteFile.isYoinFile(): Boolean = appProperties[SyncFileProps.KIND] in YOIN_FILE_KINDS

    private fun RemoteFile.resetEpoch(): Long? = appProperties[SyncFileProps.EPOCH]
        ?.takeIf { appProperties[SyncFileProps.KIND] == SyncFileProps.KIND_RESET }
        ?.toLongOrNull()

    private fun RemoteFile.fileKey(): String =
        "${appProperties[SyncFileProps.KIND]}:${appProperties[SyncFileProps.SHARD] ?: "0"}"

    private fun stateFileKey() = "${SyncFileProps.KIND_STATE}:0"

    private suspend fun <T> withIo(block: suspend () -> T): T = withContext(Dispatchers.IO) { block() }

    private fun syncDbFile() = java.io.File(appContext.noBackupFilesDir, SyncDatabase.FILE_NAME)

    /** Test-only: runs one full cycle and returns when it is done. */
    @androidx.annotation.VisibleForTesting
    internal suspend fun syncNowAndWait() {
        ensureInitialized()
        runCycle(force = true)
    }

    companion object {
        private const val TAG = "CloudSync"
        private const val FOREGROUND_THROTTLE_MS = 60_000L
        private const val LOCAL_DEBOUNCE_MS = 8_000L
        private const val SEAM_PREFS = "yoin_ui_preferences"
        private const val SEAM_PREF_KEY = "seam_top_style"

        private val OBSERVED_TABLES = arrayOf(
            "song_notes",
            "local_ratings",
            "album_ratings",
            "home_layout",
            "gemini_config",
            "spotify_config",
            "lyrics_translation_cache",
            "memory_copy_cache",
            "song_about_entries",
            "album_memory_titles",
            "profiles",
        )

        private val YOIN_FILE_KINDS = setOf(
            SyncFileProps.KIND_STATE,
            SyncFileProps.KIND_ARTIFACTS,
            SyncFileProps.KIND_RESET,
        )

        // LAST_ERROR codes
        private const val CODE_REAUTH = "reauth"
        private const val CODE_ACCOUNT_MISMATCH = "account_mismatch"
        private const val CODE_OFFLINE = "offline"
        private const val CODE_STORAGE = "storage"
        private const val CODE_MISCONFIGURED = "misconfigured"
        private const val CODE_REVIEW = "review"
        private const val CODE_CLOUD_COPY_REMOVED = "cloud_copy_removed"
        private const val CODE_DELETED_HERE = "deleted_here"
        private const val CODE_RESET_PREFIX = "reset:"
        private const val CODE_UPDATE_PREFIX = "update:"
        private const val ERROR_PREFIX = "error:"
    }
}

/** Test seams for [CloudSyncManager]; production passes none. */
@androidx.annotation.VisibleForTesting
class CloudSyncTestHooks(
    val syncDb: SyncDatabase,
    val transport: SyncTransport,
    val authorizer: GoogleSyncAuthorizer,
    val deviceName: String,
    val seam: com.gpo.yoin.data.sync.adapters.SeamStyleGateway,
)
