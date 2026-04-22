package com.gpo.yoin.data.integration.neodb

import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.browser.customtabs.CustomTabsIntent
import androidx.core.content.edit
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import java.net.URI
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * Drives NeoDB OAuth login for a specific instance.
 *
 * Flow:
 *  1. Register an OAuth app against the chosen NeoDB/Mastodon-compatible instance
 *  2. Open `/oauth/authorize` in a Custom Tab
 *  3. Receive the callback on `yoin://auth/neodb/callback`
 *  4. Exchange code for access token and return it to the caller
 */
class NeoDBOAuthActivity : ComponentActivity() {
    private val prefs: SharedPreferences by lazy {
        applicationContext.getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
    }
    private val api: NeoDBApi by lazy {
        NeoDBApi(
            client = OkHttpClient.Builder()
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS)
                .build(),
            json = Json { ignoreUnknownKeys = true; isLenient = true },
        )
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
        if (launchedAuthorization && leftForBrowser && !terminalResultSent && !isNeoDbCallback(intent?.data)) {
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
            isNeoDbCallback(data) -> processRedirect(data!!)
            isFreshLaunch -> startAuthorization()
            else -> Unit
        }
    }

    private fun startAuthorization() {
        val instance = normalizeInstance(
            intent?.getStringExtra(NeoDBOAuthContract.EXTRA_INSTANCE),
        ) ?: run {
            finishWithFailure("Invalid NeoDB URL. Include a valid host.")
            return
        }

        lifecycleScope.launch {
            try {
                val registration = withContext(Dispatchers.IO) {
                    api.registerOAuthApp(
                        instance = instance,
                        redirectUri = CALLBACK_URI,
                        clientName = CLIENT_NAME,
                        website = APP_WEBSITE,
                    )
                }
                val state = UUID.randomUUID().toString()
                prefs.edit {
                    putString(KEY_INSTANCE, instance)
                    putString(KEY_CLIENT_ID, registration.clientId)
                    putString(KEY_CLIENT_SECRET, registration.clientSecret)
                    putString(KEY_STATE, state)
                }
                launchedAuthorization = true
                val authUrl = Uri.parse("$instance/oauth/authorize")
                    .buildUpon()
                    .appendQueryParameter("response_type", "code")
                    .appendQueryParameter("client_id", registration.clientId)
                    .appendQueryParameter("redirect_uri", CALLBACK_URI)
                    .appendQueryParameter("scope", "read write")
                    .appendQueryParameter("state", state)
                    .build()
                CustomTabsIntent.Builder().build().launchUrl(this@NeoDBOAuthActivity, authUrl)
            } catch (t: Throwable) {
                finishWithFailure(t.message ?: "NeoDB sign-in failed", instance)
            }
        }
    }

    private fun processRedirect(data: Uri) {
        val error = data.getQueryParameter("error")
        if (error != null) {
            finishWithFailure("NeoDB auth error: $error")
            return
        }

        val code = data.getQueryParameter("code")
        val returnedState = data.getQueryParameter("state")
        val storedInstance = prefs.getString(KEY_INSTANCE, null)
        val storedClientId = prefs.getString(KEY_CLIENT_ID, null)
        val storedClientSecret = prefs.getString(KEY_CLIENT_SECRET, null)
        val storedState = prefs.getString(KEY_STATE, null)

        if (code.isNullOrBlank() ||
            returnedState.isNullOrBlank() ||
            storedInstance.isNullOrBlank() ||
            storedClientId.isNullOrBlank() ||
            storedClientSecret.isNullOrBlank() ||
            storedState.isNullOrBlank()
        ) {
            finishWithFailure("NeoDB redirect missing required parameters", storedInstance)
            return
        }
        if (returnedState != storedState) {
            finishWithFailure("NeoDB state nonce mismatch", storedInstance)
            return
        }

        lifecycleScope.launch {
            try {
                val token = withContext(Dispatchers.IO) {
                    api.exchangeOAuthCode(
                        instance = storedInstance,
                        clientId = storedClientId,
                        clientSecret = storedClientSecret,
                        code = code,
                        redirectUri = CALLBACK_URI,
                    )
                }
                finishWithSuccess(
                    instance = storedInstance,
                    accessToken = token.accessToken,
                )
            } catch (t: Throwable) {
                finishWithFailure(t.message ?: "NeoDB token exchange failed", storedInstance)
            }
        }
    }

    private fun finishWithSuccess(instance: String, accessToken: String) {
        terminalResultSent = true
        clearStoredInitiatorState()
        setResult(
            RESULT_OK,
            Intent().apply {
                putExtra(NeoDBOAuthContract.EXTRA_INSTANCE, instance)
                putExtra(NeoDBOAuthContract.EXTRA_ACCESS_TOKEN, accessToken)
            },
        )
        finish()
    }

    private fun finishWithFailure(message: String, instance: String? = null) {
        terminalResultSent = true
        clearStoredInitiatorState()
        setResult(
            RESULT_OK,
            Intent().apply {
                putExtra(NeoDBOAuthContract.EXTRA_FAILURE_MESSAGE, message)
                if (!instance.isNullOrBlank()) {
                    putExtra(NeoDBOAuthContract.EXTRA_INSTANCE, instance)
                }
            },
        )
        finish()
    }

    private fun finishCancelled() {
        terminalResultSent = true
        clearStoredInitiatorState()
        setResult(RESULT_CANCELED)
        finish()
    }

    private fun clearStoredInitiatorState() {
        prefs.edit {
            remove(KEY_INSTANCE)
            remove(KEY_CLIENT_ID)
            remove(KEY_CLIENT_SECRET)
            remove(KEY_STATE)
        }
    }

    private fun isNeoDbCallback(uri: Uri?): Boolean =
        uri?.scheme == "yoin" &&
            uri.host == "auth" &&
            uri.path?.startsWith("/neodb/callback") == true

    private fun normalizeInstance(raw: String?): String? {
        val trimmed = raw?.trim()?.trimEnd('/').orEmpty()
        if (trimmed.isBlank()) return null
        val candidate = if ("://" in trimmed) trimmed else "https://$trimmed"
        return runCatching {
            val uri = URI(candidate)
            require(!uri.scheme.isNullOrBlank() && !uri.host.isNullOrBlank())
            uri.resolve("/").toString().trimEnd('/')
        }.getOrNull()
    }

    companion object {
        private const val PREFS_NAME = "yoin.neodb.oauth"
        private const val KEY_INSTANCE = "instance"
        private const val KEY_CLIENT_ID = "client_id"
        private const val KEY_CLIENT_SECRET = "client_secret"
        private const val KEY_STATE = "state"

        private const val CLIENT_NAME = "Yoin"
        private const val APP_WEBSITE = "https://github.com/p2o51/Yoin"
        private const val CALLBACK_URI = "yoin://auth/neodb/callback"
    }
}
