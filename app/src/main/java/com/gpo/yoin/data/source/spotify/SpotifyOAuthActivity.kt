package com.gpo.yoin.data.source.spotify

import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.browser.customtabs.CustomTabsIntent
import androidx.core.content.edit
import androidx.lifecycle.lifecycleScope
import com.gpo.yoin.YoinApplication
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient

/**
 * Drives the Spotify PKCE OAuth flow.
 *
 * Lifecycle quirks to defend against:
 * - Redirect can arrive via [onNewIntent] (existing task) OR [onCreate] with
 *   a new `Intent` (fresh process after background kill, some OEMs). Both
 *   paths funnel through [handleIntent].
 * - Initiator state (PKCE verifier, state nonce) is persisted to
 *   [SharedPreferences] keyed on the `state` nonce. Intent extras are lost
 *   on process death; a file is the only reliable survivor.
 */
class SpotifyOAuthActivity : ComponentActivity() {
    private val tag = "SpotifyOAuthActivity"

    private val prefs: SharedPreferences by lazy {
        applicationContext.getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
    }
    private var launchedAuthorization = false
    private var leftForBrowser = false
    private var terminalResultSent = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        handleIntent(intent, isFreshLaunch = savedInstanceState == null)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent, isFreshLaunch = false)
    }

    override fun onResume() {
        super.onResume()
        if (launchedAuthorization && leftForBrowser && !terminalResultSent && !isSpotifyCallback(intent?.data)) {
            finishCancelled()
        }
    }

    override fun onStop() {
        super.onStop()
        if (launchedAuthorization && !terminalResultSent) {
            leftForBrowser = true
        }
    }

    private fun handleIntent(intent: Intent?, isFreshLaunch: Boolean) {
        val data = intent?.data
        when {
            isSpotifyCallback(data) -> processRedirect(data!!)
            isFreshLaunch -> startAuthorization()
            // Config change / activity recreate: initiator state is in prefs,
            // nothing to do.
            else -> Unit
        }
    }

    private fun startAuthorization() {
        val clientId = resolveClientId()
        val targetProfileId = intent?.getStringExtra(SpotifyOAuthContract.EXTRA_TARGET_PROFILE_ID)
        Log.d(
            tag,
            "startAuthorization: targetProfileId=$targetProfileId clientIdConfigured=${clientId.isNotBlank()}",
        )
        if (clientId.isBlank()) {
            finishWithFailure(
                "Spotify client id not set. Open Settings → Spotify to enter one.",
            )
            return
        }
        val pkce = SpotifyAuthService.generatePkce()
        val state = SpotifyAuthService.generateState()
        prefs.edit {
            putString(KEY_VERIFIER, pkce.verifier)
            putString(KEY_STATE, state)
            putString(KEY_CLIENT_ID, clientId)
            putString(KEY_TARGET_PROFILE_ID, targetProfileId)
        }
        launchedAuthorization = true
        val authUrl = SpotifyAuthService(OkHttpClient())
            .buildAuthUrl(
                codeChallenge = pkce.challenge,
                state = state,
                clientId = clientId,
            )
            .toString()
        val tabs = CustomTabsIntent.Builder().build()
        tabs.launchUrl(this, Uri.parse(authUrl))
    }

    private fun resolveClientId(): String =
        (application as? YoinApplication)?.container?.spotifyClientIdFlow?.value.orEmpty()

    private fun processRedirect(data: Uri) {
        val hasCode = !data.getQueryParameter("code").isNullOrBlank()
        val hasError = data.getQueryParameter("error") != null
        Log.d(tag, "processRedirect: hasCode=$hasCode hasError=$hasError")
        val error = data.getQueryParameter("error")
        if (error != null) {
            finishWithFailure("Spotify auth error: $error")
            return
        }
        val code = data.getQueryParameter("code")
        val returnedState = data.getQueryParameter("state")
        val storedState = prefs.getString(KEY_STATE, null)
        val storedVerifier = prefs.getString(KEY_VERIFIER, null)
        val storedClientId = prefs.getString(KEY_CLIENT_ID, null)

        if (code.isNullOrBlank() ||
            returnedState == null ||
            storedState == null ||
            storedVerifier == null ||
            storedClientId.isNullOrBlank()
        ) {
            finishWithFailure("Spotify redirect missing required parameters")
            return
        }
        if (returnedState != storedState) {
            finishWithFailure("Spotify state nonce mismatch — possible replay")
            return
        }

        lifecycleScope.launch {
            try {
                val authService = SpotifyAuthService(buildHttpClient())
                val token = withContext(Dispatchers.IO) {
                    authService.exchangeCode(
                        code = code,
                        verifier = storedVerifier,
                        clientId = storedClientId,
                    )
                }
                val me = withContext(Dispatchers.IO) {
                    authService.fetchMe(token.accessToken)
                }
                Log.d(
                    tag,
                    "processRedirect: token exchange success scopes=${token.scope.orEmpty()} userId=${me.id}",
                )
                finishWithSuccess(
                    accessToken = token.accessToken,
                    refreshToken = token.refreshToken
                        ?: return@launch finishWithFailure("Spotify did not return a refresh token"),
                    expiresAtEpochMs = System.currentTimeMillis() + token.expiresInSec * 1_000,
                    scopes = token.scope
                        ?.split(' ')
                        ?.filter { it.isNotBlank() }
                        .orEmpty(),
                    displayName = me.displayName.orEmpty(),
                    userId = me.id,
                )
            } catch (t: Throwable) {
                Log.w(tag, "processRedirect: failed", t)
                finishWithFailure(t.message ?: "Spotify auth failed")
            }
        }
    }

    private fun clearStoredInitiatorState() {
        prefs.edit {
            remove(KEY_VERIFIER)
            remove(KEY_STATE)
            remove(KEY_CLIENT_ID)
            remove(KEY_TARGET_PROFILE_ID)
        }
    }

    private fun currentTargetProfileId(): String? =
        prefs.getString(KEY_TARGET_PROFILE_ID, null)
            ?: intent?.getStringExtra(SpotifyOAuthContract.EXTRA_TARGET_PROFILE_ID)

    private fun isSpotifyCallback(data: Uri?): Boolean =
        data?.scheme == SpotifyAuthConfig.REDIRECT_SCHEME &&
            data.host == SpotifyAuthConfig.REDIRECT_HOST

    private fun finishWithSuccess(
        accessToken: String,
        refreshToken: String,
        expiresAtEpochMs: Long,
        scopes: List<String>,
        displayName: String,
        userId: String,
    ) {
        val targetProfileId = currentTargetProfileId()
        val result = Intent().apply {
            putExtra(SpotifyOAuthContract.EXTRA_ACCESS_TOKEN, accessToken)
            putExtra(SpotifyOAuthContract.EXTRA_REFRESH_TOKEN, refreshToken)
            putExtra(SpotifyOAuthContract.EXTRA_EXPIRES_AT, expiresAtEpochMs)
            putExtra(SpotifyOAuthContract.EXTRA_SCOPES, scopes.toTypedArray())
            putExtra(SpotifyOAuthContract.EXTRA_DISPLAY_NAME, displayName)
            putExtra(SpotifyOAuthContract.EXTRA_USER_ID, userId)
            putExtra(SpotifyOAuthContract.EXTRA_TARGET_PROFILE_ID, targetProfileId)
        }
        Log.d(
            tag,
            "finishWithSuccess: targetProfileId=$targetProfileId scopes=${scopes.joinToString(",")}",
        )
        clearStoredInitiatorState()
        terminalResultSent = true
        setResult(RESULT_OK, result)
        finish()
    }

    private fun finishWithFailure(message: String) {
        val targetProfileId = currentTargetProfileId()
        val result = Intent().apply {
            putExtra(SpotifyOAuthContract.EXTRA_FAILURE_MESSAGE, message)
            putExtra(SpotifyOAuthContract.EXTRA_TARGET_PROFILE_ID, targetProfileId)
        }
        Log.w(
            tag,
            "finishWithFailure: targetProfileId=$targetProfileId message=$message",
        )
        clearStoredInitiatorState()
        terminalResultSent = true
        setResult(RESULT_OK, result)
        finish()
    }

    private fun finishCancelled() {
        val targetProfileId = currentTargetProfileId()
        Log.d(
            tag,
            "finishCancelled: targetProfileId=$targetProfileId",
        )
        clearStoredInitiatorState()
        terminalResultSent = true
        setResult(RESULT_CANCELED)
        finish()
    }

    private fun buildHttpClient(): OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    companion object {
        private const val PREFS_NAME = "yoin_spotify_oauth"
        private const val KEY_VERIFIER = "pkce_verifier"
        private const val KEY_STATE = "state_nonce"
        private const val KEY_CLIENT_ID = "client_id_snapshot"
        private const val KEY_TARGET_PROFILE_ID = "target_profile_id"
    }
}
