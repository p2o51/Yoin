package com.gpo.yoin.data.sync.auth

import android.accounts.Account
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.Signature
import android.os.Build
import com.gpo.yoin.data.sync.GoogleAuthAvailability
import com.gpo.yoin.data.sync.GoogleAuthResult
import com.gpo.yoin.data.sync.GoogleSyncAuthorizer
import com.gpo.yoin.data.sync.SyncTokenProvider
import com.gpo.yoin.data.sync.SyncTransportException
import com.google.android.gms.auth.api.identity.AuthorizationClient
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.AuthorizationResult
import com.google.android.gms.auth.api.identity.ClearTokenRequest
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.auth.api.identity.RevokeAccessRequest
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.GoogleApiAvailability
import com.google.android.gms.common.Scopes
import com.google.android.gms.common.api.ApiException
import com.google.android.gms.common.api.CommonStatusCodes
import com.google.android.gms.common.api.Scope
import com.google.android.gms.common.api.UnsupportedApiCallException
import com.google.android.gms.tasks.Task
import java.security.MessageDigest
import java.util.concurrent.Executor
import kotlin.coroutines.cancellation.CancellationException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine

/**
 * [GoogleSyncAuthorizer] over Play services AuthorizationClient, scope drive.appdata only.
 * Uses the Context overload so the silent path works without an Activity; resolutions
 * are returned to the caller, never launched here.
 */
class PlayServicesSyncAuthorizer(context: Context) : GoogleSyncAuthorizer {
    private val appContext = context.applicationContext
    private val client: AuthorizationClient by lazy { Identity.getAuthorizationClient(appContext) }

    override fun availability(): GoogleAuthAvailability =
        when (GoogleApiAvailability.getInstance().isGooglePlayServicesAvailable(appContext)) {
            ConnectionResult.SUCCESS -> GoogleAuthAvailability.Available
            ConnectionResult.SERVICE_VERSION_UPDATE_REQUIRED -> GoogleAuthAvailability.ServicesUpdateRequired
            else -> GoogleAuthAvailability.ServicesMissing
        }

    override suspend fun authorizeSilently(accountEmail: String?): GoogleAuthResult {
        val email = accountEmail?.trim()?.takeIf { it.isNotEmpty() }
            ?: return guarded { authorize(driveRequest()) }
        val pinned = guarded { authorize(driveRequest { setAccount(Account(email, GOOGLE_ACCOUNT_TYPE)) }) }
        if (!SilentAuthPolicy.shouldRetryUnpinned(pinned)) return pinned
        // The device account name can differ from Drive's email (renamed, googlemail.com...).
        // Fall back to the app's default account; the caller's permissionId check guards identity.
        val fallback = guarded { authorize(driveRequest()) }
        return SilentAuthPolicy.choose(pinned, fallback)
    }

    override suspend fun authorizeInteractively(): GoogleAuthResult {
        val prompted = try {
            guarded(rethrowUnsupported = true) {
                authorize(driveRequest { setPrompt(AuthorizationRequest.Prompt.SELECT_ACCOUNT) })
            }
        } catch (_: UnsupportedApiCallException) {
            null
        }
        val promptSupported = prompted != null &&
            !(prompted is GoogleAuthResult.Failure && prompted.statusCode == CommonStatusCodes.API_NOT_CONNECTED)
        if (promptSupported && prompted != null) return prompted
        // Older Play services without the account-chooser prompt: plain request.
        return guarded { authorize(driveRequest()) }
    }

    override fun resultFromIntent(data: Intent?): GoogleAuthResult {
        // No data: the sheet was dismissed without a result.
        if (data == null) return GoogleAuthResult.Canceled
        return try {
            client.getAuthorizationResultFromIntent(data).toAuthResult()
        } catch (error: ApiException) {
            error.toAuthResult()
        } catch (error: RuntimeException) {
            GoogleAuthResult.Failure(null, error.message ?: error.javaClass.simpleName)
        }
    }

    override suspend fun clearToken(accessToken: String) {
        bestEffort { client.clearToken(ClearTokenRequest.builder().setToken(accessToken).build()).await() }
    }

    override suspend fun revoke(accountEmail: String?) {
        val email = accountEmail?.trim()?.takeIf { it.isNotEmpty() } ?: return
        bestEffort {
            client.revokeAccess(
                RevokeAccessRequest.builder()
                    .setAccount(Account(email, GOOGLE_ACCOUNT_TYPE))
                    .setScopes(listOf(Scope(Scopes.DRIVE_APPFOLDER)))
                    .build(),
            ).await()
        }
    }

    override fun signingCertSha1(): String? = runCatching {
        val certificate = currentSigningCertificate() ?: return null
        MessageDigest.getInstance("SHA-1").digest(certificate.toByteArray())
            .joinToString(separator = ":") { byte -> "%02X".format(byte) }
    }.getOrNull()

    @Suppress("DEPRECATION")
    private fun currentSigningCertificate(): Signature? {
        val packageManager = appContext.packageManager
        val packageName = appContext.packageName
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val signingInfo = packageManager
                .getPackageInfo(packageName, PackageManager.GET_SIGNING_CERTIFICATES)
                .signingInfo ?: return null
            if (signingInfo.hasMultipleSigners()) {
                signingInfo.apkContentsSigners?.firstOrNull()
            } else {
                // Rotation history is oldest-first; the current certificate is last.
                signingInfo.signingCertificateHistory?.lastOrNull()
            }
        } else {
            packageManager.getPackageInfo(packageName, PackageManager.GET_SIGNATURES).signatures?.firstOrNull()
        }
    }

    private suspend fun authorize(request: AuthorizationRequest): GoogleAuthResult =
        client.authorize(request).await().toAuthResult()

    /**
     * Maps every Play services failure to a [GoogleAuthResult]. With [rethrowUnsupported],
     * [UnsupportedApiCallException] propagates so the interactive path can retry without the prompt.
     */
    private inline fun guarded(rethrowUnsupported: Boolean = false, block: () -> GoogleAuthResult): GoogleAuthResult =
        try {
            block()
        } catch (error: CancellationException) {
            throw error
        } catch (error: ApiException) {
            error.toAuthResult()
        } catch (error: UnsupportedApiCallException) {
            if (rethrowUnsupported) throw error
            GoogleAuthResult.Failure(CommonStatusCodes.API_NOT_CONNECTED, error.message ?: "Unsupported Google API")
        } catch (error: RuntimeException) {
            GoogleAuthResult.Failure(null, error.message ?: error.javaClass.simpleName)
        }

    private inline fun bestEffort(block: () -> Unit) {
        try {
            block()
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            // Token cache clearing and revocation must never block a local turn-off / delete.
        }
    }

    companion object {
        private const val GOOGLE_ACCOUNT_TYPE = "com.google"

        private fun driveRequest(configure: AuthorizationRequest.Builder.() -> Unit = {}): AuthorizationRequest =
            AuthorizationRequest.builder()
                .setRequestedScopes(listOf(Scope(Scopes.DRIVE_APPFOLDER)))
                .apply(configure)
                .build()

        private fun AuthorizationResult.toAuthResult(): GoogleAuthResult {
            if (hasResolution()) {
                val pendingIntent = pendingIntent
                    ?: return GoogleAuthResult.Failure(null, "Google asked for consent without a way to show it")
                return GoogleAuthResult.NeedsResolution(pendingIntent.intentSender)
            }
            val token = accessToken?.takeIf { it.isNotBlank() }
                ?: return GoogleAuthResult.Failure(null, "Google returned no access token")
            val scopes = grantedScopes.orEmpty()
            if (GoogleSyncAuthorizer.SCOPE_DRIVE_APPDATA !in scopes) {
                return GoogleAuthResult.Failure(null, "Google Drive app data access wasn't granted")
            }
            return GoogleAuthResult.Token(token, scopes)
        }

        private fun ApiException.toAuthResult(): GoogleAuthResult {
            val message = statusMessage ?: CommonStatusCodes.getStatusCodeString(statusCode)
            return when (statusCode) {
                CommonStatusCodes.DEVELOPER_ERROR -> GoogleAuthResult.Misconfigured
                CommonStatusCodes.CANCELED -> GoogleAuthResult.Canceled
                CommonStatusCodes.SIGN_IN_REQUIRED,
                CommonStatusCodes.INVALID_ACCOUNT,
                CommonStatusCodes.RESOLUTION_REQUIRED,
                -> status.resolution?.let { GoogleAuthResult.NeedsResolution(it.intentSender) }
                    ?: GoogleAuthResult.Failure(statusCode, message)
                else -> GoogleAuthResult.Failure(statusCode, message)
            }
        }
    }
}

/**
 * When a silent request pinned to the stored email may fall back to the app's default account.
 *
 * Only an account-level refusal (account not on the device, consent missing for it) is worth an
 * unpinned retry. A transient or unknown failure must NOT fall back: the sync manager verifies the
 * Google identity once per cycle while every Drive request asks for a token again, so a fallback
 * triggered by a passing hiccup could hand out another account's token mid-cycle and write this
 * replica into that account's Drive.
 */
internal object SilentAuthPolicy {
    /** Play services status codes that clear up on their own: the device is offline or Play services is busy. */
    val TRANSIENT_CODES: Set<Int> = setOf(
        CommonStatusCodes.NETWORK_ERROR,
        CommonStatusCodes.INTERNAL_ERROR,
        CommonStatusCodes.INTERRUPTED,
        CommonStatusCodes.TIMEOUT,
        CommonStatusCodes.CONNECTION_SUSPENDED_DURING_CALL,
        CommonStatusCodes.RECONNECTION_TIMED_OUT_DURING_UPDATE,
        CommonStatusCodes.RECONNECTION_TIMED_OUT,
    )

    /** Failures that are about the pinned account itself (when Play services offered no resolution). */
    private val ACCOUNT_CODES: Set<Int> = setOf(
        CommonStatusCodes.SIGN_IN_REQUIRED,
        CommonStatusCodes.INVALID_ACCOUNT,
        CommonStatusCodes.RESOLUTION_REQUIRED,
    )

    fun shouldRetryUnpinned(pinned: GoogleAuthResult): Boolean = when (pinned) {
        is GoogleAuthResult.NeedsResolution -> true
        is GoogleAuthResult.Failure -> pinned.statusCode in ACCOUNT_CODES
        else -> false
    }

    /** A failed fallback never hides that the pinned account definitely needs the user. */
    fun choose(pinned: GoogleAuthResult, fallback: GoogleAuthResult): GoogleAuthResult =
        if (fallback is GoogleAuthResult.Failure && pinned is GoogleAuthResult.NeedsResolution) pinned else fallback
}

/**
 * [SyncTokenProvider] backed by silent authorization. Tokens are not cached here:
 * Play services caches them, and a local cache could outlive an account switch.
 */
class AuthorizerTokenProvider(
    private val authorizer: GoogleSyncAuthorizer,
    private val emailProvider: suspend () -> String?,
) : SyncTokenProvider {
    /** Last token handed out, remembered only so [invalidateCached] can clear it on turn-off. */
    @Volatile
    private var lastIssued: String? = null

    override suspend fun token(): String = when (val result = authorizer.authorizeSilently(emailProvider())) {
        is GoogleAuthResult.Token -> result.accessToken.also { lastIssued = it }
        is GoogleAuthResult.NeedsResolution -> throw SyncTransportException.Unauthorized("Google needs consent again")
        GoogleAuthResult.Canceled -> throw SyncTransportException.Unauthorized("Google authorization was canceled")
        GoogleAuthResult.Misconfigured ->
            throw SyncTransportException.Misconfigured("Google authorization isn't set up for this build")
        is GoogleAuthResult.Failure ->
            if (result.statusCode in SilentAuthPolicy.TRANSIENT_CODES) {
                throw SyncTransportException.Network("Couldn't reach Google: ${result.message}")
            } else {
                throw SyncTransportException.Unauthorized("Google needs consent again: ${result.message}")
            }
    }

    override suspend fun invalidate(token: String) {
        if (lastIssued == token) lastIssued = null
        authorizer.clearToken(token)
    }

    /** Turn off: drop the last token Yoin used from Play services' cache (best effort, no network). */
    suspend fun invalidateCached() {
        val token = lastIssued ?: return
        lastIssued = null
        authorizer.clearToken(token)
    }
}

/** Play services Task as a cancellable suspend call (kept local: no coroutines-play-services API dependency). */
private suspend fun <T> Task<T>.await(): T = suspendCancellableCoroutine { continuation ->
    // Complete on the binder thread instead of hopping through the main looper.
    addOnCompleteListener(Executor(Runnable::run)) { task ->
        val error = task.exception
        when {
            error != null -> continuation.resumeWithException(error)
            task.isCanceled -> continuation.resumeWithException(IllegalStateException("Google Play services task was canceled"))
            else -> continuation.resume(task.result)
        }
    }
}
