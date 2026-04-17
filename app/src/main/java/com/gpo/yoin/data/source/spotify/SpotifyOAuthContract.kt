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
class SpotifyOAuthContract : ActivityResultContract<Unit, SpotifyOAuthResult>() {

    override fun createIntent(context: Context, input: Unit): Intent =
        Intent(context, SpotifyOAuthActivity::class.java)

    override fun parseResult(resultCode: Int, intent: Intent?): SpotifyOAuthResult {
        if (resultCode == Activity.RESULT_CANCELED) {
            return SpotifyOAuthResult.Cancelled
        }
        intent ?: return SpotifyOAuthResult.Failure("Spotify returned no result intent")

        val failureMessage = intent.getStringExtra(EXTRA_FAILURE_MESSAGE)
        if (failureMessage != null) {
            return SpotifyOAuthResult.Failure(failureMessage)
        }

        val accessToken = intent.getStringExtra(EXTRA_ACCESS_TOKEN)
        val refreshToken = intent.getStringExtra(EXTRA_REFRESH_TOKEN)
        val expiresAt = intent.getLongExtra(EXTRA_EXPIRES_AT, -1L)
        val scopes = intent.getStringArrayExtra(EXTRA_SCOPES)?.toList().orEmpty()
        val displayName = intent.getStringExtra(EXTRA_DISPLAY_NAME).orEmpty()
        val userId = intent.getStringExtra(EXTRA_USER_ID).orEmpty()

        if (accessToken.isNullOrBlank() || refreshToken.isNullOrBlank() || expiresAt < 0L) {
            return SpotifyOAuthResult.Failure("Spotify returned incomplete credentials")
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
    }
}

sealed interface SpotifyOAuthResult {
    data class Success(
        val credentials: ProfileCredentials.Spotify,
        val displayName: String,
        val userId: String,
    ) : SpotifyOAuthResult

    data class Failure(val message: String) : SpotifyOAuthResult

    data object Cancelled : SpotifyOAuthResult
}
