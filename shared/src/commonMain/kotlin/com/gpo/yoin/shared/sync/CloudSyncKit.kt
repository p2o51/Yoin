package com.gpo.yoin.shared.sync

import com.gpo.yoin.shared.sync.adapters.SharedSyncAdapters
import com.gpo.yoin.shared.sync.adapters.SyncDomain
import com.gpo.yoin.shared.sync.drive.DriveAppDataClient
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.suspendCancellableCoroutine

/**
 * Supplies Google access tokens (scope drive.appdata) from the platform's
 * sign-in SDK. Callback-shaped so Swift can implement it (GoogleSignIn's
 * `refreshTokensIfNeeded`). [completion] gets (token, null) or (null, error message).
 * [forceRefresh] = the previous token was rejected with a 401.
 */
interface SyncTokenSource {
    fun fetchToken(forceRefresh: Boolean, completion: (String?, String?) -> Unit)
}

/** Where the sync store's JSON document lives (iOS: Application Support, excluded from backup). */
interface SyncPersistence {
    fun load(): String?

    /** true once [text] is durably on disk; false keeps the store dirty so the next operation writes again. */
    fun save(text: String): Boolean
}

/** Error category reported by a platform-native [PlatformSyncTransport] (e.g. CloudKit on Apple platforms). */
enum class PlatformTransportErrorKind {
    UNAUTHORIZED,
    MISCONFIGURED,
    STORAGE_FULL,
    RATE_LIMITED,
    NETWORK,
    ALREADY_EXISTS,
    SERVER,
}

/** Typed failure returned through [PlatformSyncTransport] callbacks. */
class PlatformTransportError(
    val kind: PlatformTransportErrorKind,
    val message: String,
    val code: Int = 500,
    val retryAfterMs: Long? = null,
) {
    internal fun toException(): SyncTransportException = when (kind) {
        PlatformTransportErrorKind.UNAUTHORIZED -> SyncTransportException.Unauthorized(message)
        PlatformTransportErrorKind.MISCONFIGURED -> SyncTransportException.Misconfigured(message)
        PlatformTransportErrorKind.STORAGE_FULL -> SyncTransportException.StorageFull(message)
        PlatformTransportErrorKind.RATE_LIMITED -> SyncTransportException.RateLimited(message, retryAfterMs)
        PlatformTransportErrorKind.NETWORK -> SyncTransportException.Network(message)
        PlatformTransportErrorKind.ALREADY_EXISTS -> SyncTransportException.AlreadyExists(message)
        PlatformTransportErrorKind.SERVER -> SyncTransportException.Server(code, message)
    }
}

/**
 * Callback-shaped [SyncTransport] so Swift can implement CloudKit Private Database (`CKRecord` + `CKAsset`)
 * cleanly without Kotlin/Native suspend-interface interop issues.
 */
interface PlatformSyncTransport {
    fun list(completion: (List<RemoteFile>?, PlatformTransportError?) -> Unit)

    /** Returns (bytes, null) if present, (null, null) if not found (404 / unknownItem), or (null, error). */
    fun download(fileId: String, completion: (ByteArray?, PlatformTransportError?) -> Unit)

    fun create(
        fileId: String,
        name: String,
        appProperties: Map<String, String>,
        mimeType: String,
        bytes: ByteArray,
        completion: (RemoteFile?, PlatformTransportError?) -> Unit,
    )

    /** Returns (file, null) on update, (null, null) if absent (404 / unknownItem), or (null, error). */
    fun update(fileId: String, bytes: ByteArray, completion: (RemoteFile?, PlatformTransportError?) -> Unit)

    fun delete(fileId: String, completion: (Boolean, PlatformTransportError?) -> Unit)

    fun generateIds(count: Int, completion: (List<String>?, PlatformTransportError?) -> Unit)

    fun about(completion: (GoogleAccountInfo?, PlatformTransportError?) -> Unit)
}

internal class PlatformTransportBridge(private val platform: PlatformSyncTransport) : SyncTransport {
    override suspend fun list(): List<RemoteFile> = suspendCancellableCoroutine { cont ->
        platform.list { files, error ->
            if (!cont.isActive) return@list
            if (error != null) cont.resumeWith(Result.failure(error.toException()))
            else cont.resumeWith(Result.success(files.orEmpty()))
        }
    }

    override suspend fun download(fileId: String): ByteArray? = suspendCancellableCoroutine { cont ->
        platform.download(fileId) { bytes, error ->
            if (!cont.isActive) return@download
            if (error != null) cont.resumeWith(Result.failure(error.toException()))
            else cont.resumeWith(Result.success(bytes))
        }
    }

    override suspend fun create(
        fileId: String,
        name: String,
        appProperties: Map<String, String>,
        mimeType: String,
        bytes: ByteArray,
    ): RemoteFile = suspendCancellableCoroutine { cont ->
        platform.create(fileId, name, appProperties, mimeType, bytes) { file, error ->
            if (!cont.isActive) return@create
            if (error != null) {
                cont.resumeWith(Result.failure(error.toException()))
            } else if (file != null) {
                cont.resumeWith(Result.success(file))
            } else {
                cont.resumeWith(Result.failure(SyncTransportException.Server(500, "Empty create response")))
            }
        }
    }

    override suspend fun update(fileId: String, bytes: ByteArray): RemoteFile? = suspendCancellableCoroutine { cont ->
        platform.update(fileId, bytes) { file, error ->
            if (!cont.isActive) return@update
            if (error != null) cont.resumeWith(Result.failure(error.toException()))
            else cont.resumeWith(Result.success(file))
        }
    }

    override suspend fun delete(fileId: String): Boolean = suspendCancellableCoroutine { cont ->
        platform.delete(fileId) { deleted, error ->
            if (!cont.isActive) return@delete
            if (error != null) cont.resumeWith(Result.failure(error.toException()))
            else cont.resumeWith(Result.success(deleted))
        }
    }

    override suspend fun generateIds(count: Int): List<String> = suspendCancellableCoroutine { cont ->
        platform.generateIds(count) { ids, error ->
            if (!cont.isActive) return@generateIds
            if (error != null) cont.resumeWith(Result.failure(error.toException()))
            else cont.resumeWith(Result.success(ids.orEmpty()))
        }
    }

    override suspend fun about(): GoogleAccountInfo = suspendCancellableCoroutine { cont ->
        platform.about { info, error ->
            if (!cont.isActive) return@about
            if (error != null) {
                cont.resumeWith(Result.failure(error.toException()))
            } else if (info != null) {
                cont.resumeWith(Result.success(info))
            } else {
                cont.resumeWith(Result.failure(SyncTransportException.Unauthorized("Cloud account unavailable")))
            }
        }
    }
}

/** The main thread on iOS (the Swift stores are main-actor); the caller's context elsewhere. */
internal expect fun syncMainContext(): CoroutineContext

/**
 * Everything cloud sync needs, built from what Swift can provide: the local
 * stores ([SyncDomain]), a token source, an optional native iCloud transport, and a file.
 */
class CloudSyncKit(
    domain: SyncDomain,
    tokenSource: SyncTokenSource,
    private val persistence: SyncPersistence,
    deviceName: String,
    versionCode: Int,
    /** Drive API base (default Google); the DEBUG fake Drive passes "<base>/drive/v3/". */
    apiBase: String?,
    uploadBase: String?,
    backupSink: SyncBackupSink?,
    /** Native iCloud (CloudKit) transport supplied by Swift on Apple platforms. */
    icloudTransport: PlatformSyncTransport? = null,
) {
    val store = InMemorySyncStore().also { store -> persistence.load()?.let(store::restore) }

    private val tokens = object : SyncTokenProvider {
        private var rejected: String? = null

        override suspend fun token(): String {
            val force = rejected != null
            rejected = null
            return suspendCancellableCoroutine { continuation ->
                tokenSource.fetchToken(force) { token, error ->
                    if (!continuation.isActive) return@fetchToken
                    if (token != null && token.isNotEmpty()) {
                        continuation.resumeWith(Result.success(token))
                    } else {
                        continuation.resumeWith(
                            Result.failure(SyncTransportException.Unauthorized(error ?: "No Google access token")),
                        )
                    }
                }
            }
        }

        override suspend fun invalidate(token: String) {
            rejected = token
        }
    }

    val core = CloudSyncCore(
        store = store,
        adapters = { syncStore -> SharedSyncAdapters.create(domain, syncStore) },
        profiles = { domain.profiles() },
        tokens = tokens,
        transportFactory = { provider ->
            DriveAppDataClient(
                tokens = provider,
                apiBase = apiBase ?: DriveAppDataClient.DEFAULT_API_BASE,
                uploadBase = uploadBase ?: DriveAppDataClient.DEFAULT_UPLOAD_BASE,
                userAgent = "Yoin-iOS/$versionCode (gzip)",
            )
        },
        deviceName = { deviceName },
        versionCode = versionCode,
        context = syncMainContext(),
        persist = ::flush,
        readOnlyFirstPull = true,
        backupSink = backupSink,
        icloudTransport = icloudTransport?.let(::PlatformTransportBridge),
    )

    /** Writes the store when it changed. */
    fun flush() {
        if (!store.isDirty) return
        try {
            // Clean only once the document is on disk: a failed write must not drop the bookkeeping.
            if (persistence.save(store.snapshotJson())) store.markClean()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Kept dirty: the next operation retries the write.
        }
    }
}
