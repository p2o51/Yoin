package com.gpo.yoin.data.sync

import android.content.Intent
import android.content.IntentSender

/** Whether Google sign-in for Drive can work on this device/build at all. */
sealed interface GoogleAuthAvailability {
    data object Available : GoogleAuthAvailability

    /** Google Play services missing, disabled or invalid (AOSP / some regional devices). */
    data object ServicesMissing : GoogleAuthAvailability

    /** Google Play services present but too old. */
    data object ServicesUpdateRequired : GoogleAuthAvailability
}

sealed interface GoogleAuthResult {
    data class Token(val accessToken: String, val grantedScopes: List<String>) : GoogleAuthResult

    /** User interaction required; only launchable from a foreground Activity. */
    data class NeedsResolution(val intentSender: IntentSender) : GoogleAuthResult

    /** User closed the consent / account picker. */
    data object Canceled : GoogleAuthResult

    /** The OAuth client is not registered for this package + signing certificate (ApiException 10). */
    data object Misconfigured : GoogleAuthResult

    data class Failure(val statusCode: Int?, val message: String) : GoogleAuthResult
}

/**
 * Thin wrapper over Play services AuthorizationClient (scope drive.appdata only).
 * Implementations never launch UI themselves; [GoogleAuthResult.NeedsResolution]
 * is handed to the foreground Activity.
 */
interface GoogleSyncAuthorizer {
    fun availability(): GoogleAuthAvailability

    /**
     * Token without UI when consent already exists. [accountEmail] pins the account when known
     * (fall back to the app's default account if the pinned one is not on the device).
     */
    suspend fun authorizeSilently(accountEmail: String?): GoogleAuthResult

    /** Starts the interactive flow with the account chooser (turn on / reconnect). */
    suspend fun authorizeInteractively(): GoogleAuthResult

    /** Result of the IntentSender launched for [GoogleAuthResult.NeedsResolution]. */
    fun resultFromIntent(data: Intent?): GoogleAuthResult

    suspend fun clearToken(accessToken: String)

    /** Revokes Yoin's Drive grant for the account (all devices). Best effort. */
    suspend fun revoke(accountEmail: String?)

    /** Hex SHA-1 of this APK's signing certificate, for the "not set up for this build" details row. */
    fun signingCertSha1(): String?

    companion object {
        const val SCOPE_DRIVE_APPDATA = "https://www.googleapis.com/auth/drive.appdata"
    }
}
