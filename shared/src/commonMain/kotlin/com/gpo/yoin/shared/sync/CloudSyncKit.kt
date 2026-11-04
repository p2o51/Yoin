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

    fun save(text: String)
}

/** The main thread on iOS (the Swift stores are main-actor); the caller's context elsewhere. */
internal expect fun syncMainContext(): CoroutineContext

/**
 * Everything cloud sync needs, built from what Swift can provide: the local
 * stores ([SyncDomain]), a token source and a file. Swift:
 *
 *     let kit = CloudSyncKit(domain: domain, tokenSource: tokens, persistence: file,
 *                            deviceName: "iPhone", versionCode: 1, apiBase: nil, uploadBase: nil)
 *     try await kit.core.initialize()
 *     let error = try await kit.core.enable(accessToken: token)   // nil = started
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
    )

    /** Writes the store when it changed. */
    fun flush() {
        if (!store.isDirty) return
        try {
            persistence.save(store.snapshotJson())
            store.markClean()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Kept dirty: the next operation retries the write.
        }
    }
}
