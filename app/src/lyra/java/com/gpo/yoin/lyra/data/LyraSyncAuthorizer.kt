package com.gpo.yoin.lyra.data

import android.content.Intent
import com.gpo.yoin.data.sync.GoogleAuthAvailability
import com.gpo.yoin.data.sync.GoogleAuthResult
import com.gpo.yoin.data.sync.GoogleSyncAuthorizer

/**
 * Authorizer adapter for [com.gpo.yoin.data.sync.CloudSyncManager] in the `lyra` flavor.
 *
 * Sync authenticates directly against Lyra's `/api/v1/sync` endpoints using the active Subsonic
 * profile's `u/t/s` (`v1Auth`), so no Google Play services or OAuth consent sheet is needed.
 */
class LyraSyncAuthorizer(
    private val authProvider: suspend () -> LyraServerAuth?,
    private val missingServerMessage: String = "Connect a Lyra server first.",
) : GoogleSyncAuthorizer {
    override fun availability(): GoogleAuthAvailability = GoogleAuthAvailability.Available

    override suspend fun authorizeSilently(accountEmail: String?): GoogleAuthResult = resolve()

    override suspend fun authorizeInteractively(accountEmail: String?): GoogleAuthResult = resolve()

    override fun resultFromIntent(data: Intent?): GoogleAuthResult = GoogleAuthResult.Canceled

    override suspend fun clearToken(accessToken: String) = Unit

    override suspend fun revoke(accountEmail: String?) = Unit

    override fun signingCertSha1(): String? = null

    private suspend fun resolve(): GoogleAuthResult {
        val auth = authProvider()
        return if (auth != null) {
            GoogleAuthResult.Token(
                accessToken = "lyra-v1auth",
                grantedScopes = listOf("lyra.sync"),
            )
        } else {
            GoogleAuthResult.Failure(statusCode = null, message = missingServerMessage)
        }
    }
}
