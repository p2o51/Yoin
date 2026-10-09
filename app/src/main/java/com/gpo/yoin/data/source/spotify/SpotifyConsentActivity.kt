package com.gpo.yoin.data.source.spotify

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.lifecycleScope
import com.gpo.yoin.YoinApplication
import com.spotify.sdk.android.auth.AuthorizationClient
import com.spotify.sdk.android.auth.AuthorizationRequest
import com.spotify.sdk.android.auth.AuthorizationResponse
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Grants App Remote control through Spotify's official authorization page.
 *
 * App Remote's built-in consent (`showAuthView(true)`) is shown by the Spotify app from the
 * background; on current Android / Spotify builds it never appears, and a first-time account
 * only ever gets "Explicit user authorization is required". So Yoin asks itself, from the
 * foreground: an invisible activity opens Spotify's authorization page (the spotify-auth
 * library, `app-remote-control` only — the token is just proof of consent and is dropped),
 * then retries the connection so the play that failed goes through. A refusal shows Spotify's
 * own reason in place of the connect error.
 */
class SpotifyConsentActivity : ComponentActivity() {

    private val authorize = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val response = AuthorizationClient.getResponse(result.resultCode, result.data)
        val playback = (application as YoinApplication).container.playbackManager
        Log.d(TAG, "authorization result: ${response.type} ${response.error.orEmpty()}")
        when (response.type) {
            AuthorizationResponse.Type.TOKEN, AuthorizationResponse.Type.CODE -> playback.retrySpotifyAfterConsent()
            AuthorizationResponse.Type.ERROR -> playback.reportSpotifyConsentRefused(response.error.orEmpty().ifBlank { "unknown" })
            // Backed out, or nothing came back: leave the connect error as it was.
            else -> Unit
        }
        finishQuietly()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Recreated while Spotify's page is up: the result still arrives through the registry.
        if (savedInstanceState != null) return
        lifecycleScope.launch {
            val container = (application as YoinApplication).container
            val clientId = withTimeoutOrNull(CLIENT_ID_TIMEOUT_MS) {
                container.spotifyClientIdFlow.first { it.isNotBlank() }
            }?.trim()
            if (clientId.isNullOrBlank()) {
                finishQuietly()
                return@launch
            }
            val request = AuthorizationRequest.Builder(
                clientId,
                AuthorizationResponse.Type.TOKEN,
                SpotifyAuthConfig.APP_REMOTE_REDIRECT_URI,
            )
                .setScopes(arrayOf(SpotifyAuthConfig.APP_REMOTE_CONTROL_SCOPE))
                .build()
            runCatching { authorize.launch(AuthorizationClient.createLoginActivityIntent(this@SpotifyConsentActivity, request)) }
                .onFailure { error ->
                    Log.w(TAG, "could not open Spotify's authorization page", error)
                    container.playbackManager.reportSpotifyConsentRefused(error.message ?: error.javaClass.simpleName)
                    finishQuietly()
                }
        }
    }

    private fun finishQuietly() {
        finish()
        @Suppress("DEPRECATION")
        overridePendingTransition(0, 0)
    }

    companion object {
        private const val TAG = "SpotifyConsent"
        private const val CLIENT_ID_TIMEOUT_MS = 3_000L

        fun intent(context: Context): Intent = Intent(context, SpotifyConsentActivity::class.java)
    }
}
