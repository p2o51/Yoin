package com.gpo.yoin.data.source.spotify

import android.app.Activity
import android.content.Context
import android.content.Intent
import androidx.activity.result.contract.ActivityResultContract
import com.gpo.yoin.data.profile.ProfileCredentials

/**
 * [ActivityResultContract] front-end for Spotify OAuth. Callers launch the
 * contract with `Unit`; the underlying [SpotifyOAuthActivity] handles PKCE +
 * token exchange + `/me` lookup and returns one of the [SpotifyOAuthResult]
 * variants.
 *
 * Failure shapes are distinguished so UI can pick the right affordance:
 * - [SpotifyOAuthResult.Cancelled] — user tapped back in the browser tab. No
 *   toast, just dismiss the picker.
 * - [SpotifyOAuthResult.Failure] — something network- or protocol-level went
 *   wrong. Surface the message.
 */
class SpotifyOAuthContract : ActivityResultContract<String?, SpotifyOAuthResult>() {

    override fun createIntent(context: Context, input: String?): Intent =
        Intent(context, SpotifyOAuthActivity::class.java).apply {
            putExtra(EXTRA_TARGET_PROFILE_ID, input)
        }

    override fun parseResult(resultCode: Int, intent: Intent?): SpotifyOAuthResult {
        val targetProfileId = intent?.getStringExtra(EXTRA_TARGET_PROFILE_ID)
        if (resultCode == Activity.RESULT_CANCELED) {
            return SpotifyOAuthResult.Cancelled
        }
        intent ?: return SpotifyOAuthResult.Failure(
            message = "Spotify returned no result intent",
            targetProfileId = targetProfileId,
        )

        val failureMessage = intent.getStringExtra(EXTRA_FAILURE_MESSAGE)
        if (failureMessage != null) {
            return SpotifyOAuthResult.Failure(
                message = failureMessage,
                targetProfileId = targetProfileId,
            )
        }

        val accessToken = intent.getStringExtra(EXTRA_ACCESS_TOKEN)
        val refreshToken = intent.getStringExtra(EXTRA_REFRESH_TOKEN)
        val expiresAt = intent.getLongExtra(EXTRA_EXPIRES_AT, -1L)
        val scopes = intent.getStringArrayExtra(EXTRA_SCOPES)?.toList().orEmpty()
        val displayName = intent.getStringExtra(EXTRA_DISPLAY_NAME).orEmpty()
        val userId = intent.getStringExtra(EXTRA_USER_ID).orEmpty()

        if (accessToken.isNullOrBlank() || refreshToken.isNullOrBlank() || expiresAt < 0L) {
            return SpotifyOAuthResult.Failure(
                message = "Spotify returned incomplete credentials",
                targetProfileId = targetProfileId,
            )
        }
        return SpotifyOAuthResult.Success(
            credentials = ProfileCredentials.Spotify(
                accessToken = accessToken,
                refreshToken = refreshToken,
                expiresAtEpochMs = expiresAt,
                scopes = scopes,
            ),
            displayName = displayName.ifBlank { userId },
            userId = userId,
            targetProfileId = targetProfileId,
        )
    }

    companion object {
        internal const val EXTRA_ACCESS_TOKEN = "yoin.spotify.access_token"
        internal const val EXTRA_REFRESH_TOKEN = "yoin.spotify.refresh_token"
        internal const val EXTRA_EXPIRES_AT = "yoin.spotify.expires_at"
        internal const val EXTRA_SCOPES = "yoin.spotify.scopes"
        internal const val EXTRA_DISPLAY_NAME = "yoin.spotify.display_name"
        internal const val EXTRA_USER_ID = "yoin.spotify.user_id"
        internal const val EXTRA_FAILURE_MESSAGE = "yoin.spotify.failure"
        internal const val EXTRA_TARGET_PROFILE_ID = "yoin.spotify.target_profile_id"
    }
}

sealed interface SpotifyOAuthResult {
    data class Success(
        val credentials: ProfileCredentials.Spotify,
        val displayName: String,
        val userId: String,
        val targetProfileId: String?,
    ) : SpotifyOAuthResult

    data class Failure(
        val message: String,
        val targetProfileId: String?,
    ) : SpotifyOAuthResult

    data object Cancelled : SpotifyOAuthResult
}
