package com.gpo.yoin.ui.settings.applemusic

import android.app.Application
import android.content.Intent
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.apple.android.sdk.authentication.AuthenticationFactory
import com.apple.android.sdk.authentication.TokenError
import com.gpo.yoin.YoinApplication
import com.gpo.yoin.data.remote.applemusic.AppleMusicApiClient
import com.gpo.yoin.data.remote.applemusic.AppleMusicDeveloperTokenProvider
import com.gpo.yoin.data.remote.applemusic.AppleMusicValidationAccount
import com.gpo.yoin.player.applemusic.AppleMusicValidationService
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import okhttp3.HttpUrl.Companion.toHttpUrl

class AppleMusicValidationViewModel(application: Application) : AndroidViewModel(application) {
    private val container = (application as YoinApplication).container
    private val manager = container.profileManager
    private val auth = AuthenticationFactory.createAuthenticationManager(application)
    private var profileId: String? = null
    private var initialized = false
    private var pendingEndpoint: String? = null
    private val mutableState = MutableStateFlow(AppleMusicValidationUiState(busy = true))
    val state = mutableState.asStateFlow()
    private val authIntents = Channel<Intent>(Channel.BUFFERED)
    val authorization = authIntents.receiveAsFlow()
    private val savedProfiles = Channel<String>(Channel.BUFFERED)
    val saved = savedProfiles.receiveAsFlow()

    fun initialize(targetProfileId: String?) {
        if (initialized) return
        initialized = true
        profileId = targetProfileId
        viewModelScope.launch {
            try {
                val profile = manager.profiles.first().firstOrNull { it.id == targetProfileId }
                val credentials = profile?.let(
                    manager::decodeCredentials
                ) as? com.gpo.yoin.data.profile.ProfileCredentials.AppleMusic
                mutableState.value = AppleMusicValidationUiState(
                    endpoint = credentials?.endpoint.orEmpty(), connected = credentials != null,
                    status = if (credentials != null) {
                        "Connected. Your account is available in Profiles."
                    } else {
                        "Set a developer token service to connect."
                    }
                )
            } finally {
                mutableState.update { it.copy(busy = false) }
            }
        }
    }

    fun connect(endpoint: String) = launchOperation {
        if (pendingEndpoint != null) return@launchOperation
        val url = endpoint.trim().toHttpUrl()
        require(url.isHttps && url.username.isEmpty() && url.password.isEmpty())
        val developerToken = AppleMusicDeveloperTokenProvider(url).token()
        pendingEndpoint = url.toString()
        mutableState.update { it.copy(endpoint = url.toString(), status = "Complete authorization in Apple Music.") }
        authIntents.send(
            auth.createIntentBuilder(developerToken).setHideStartScreen(false)
                .setStartScreenMessage("Connect your Apple Music library and playback to Yoin.").build()
        )
    }

    fun authorizationResult(data: Intent?) = launchOperation {
        val endpoint = pendingEndpoint ?: error("Authorization session expired")
        pendingEndpoint = null
        if (data == null) {
            mutableState.update { it.copy(status = "Authorization returned without a token. You can retry.") }
            return@launchOperation
        }
        val result = auth.handleTokenResult(data)
        if (result.isError) {
            val message = when (result.error) {
                TokenError.USER_CANCELLED ->
                    "Apple Music did not complete authorization. Try again or open " +
                        "Apple Music to check your account."
                TokenError.NO_SUBSCRIPTION, TokenError.SUBSCRIPTION_EXPIRED ->
                    "An active Apple Music subscription is required."
                else -> "Apple Music could not authorize Yoin. Retry authorization."
            }
            mutableState.update { it.copy(status = message) }
            return@launchOperation
        }
        val token = requireNotNull(result.musicUserToken?.takeIf { it.isNotBlank() })
        val candidate = AppleMusicValidationAccount(endpoint, token)
        val storefront = api(candidate).storefront()
        AppleMusicValidationService.stop(getApplication())
        val credentials = com.gpo.yoin.data.profile.ProfileCredentials.AppleMusic(endpoint, token)
        val existingId = profileId
        val savedId = if (existingId == null) {
            manager.create("Apple Music", credentials).id
        } else {
            check(manager.profiles.first().any { it.id == existingId }) { "This account was removed. Add it again." }
            if (manager.activeProfileId.value == existingId) container.playbackManager.disconnect()
            manager.update(existingId, credentials = credentials)
            existingId
        }
        profileId = savedId
        container.notifyMusicConfigurationChanged()
        mutableState.update {
            it.copy(connected = true, storefront = storefront, status = "Connected · $storefront")
        }
        savedProfiles.send(savedId)
    }

    private fun api(account: AppleMusicValidationAccount): AppleMusicApiClient {
        val provider = AppleMusicDeveloperTokenProvider(account.endpoint.toHttpUrl())
        return AppleMusicApiClient(provider::token, { account.musicUserToken })
    }

    private fun launchOperation(block: suspend () -> Unit) {
        if (state.value.busy) return
        viewModelScope.launch {
            mutableState.update { it.copy(busy = true) }
            try {
                withTimeout(30_000) { block() }
            } catch (_: TimeoutCancellationException) {
                pendingEndpoint = null
                mutableState.update { it.copy(status = "Connection timed out. Try again.") }
            } catch (
                cancelled: CancellationException
            ) {
                throw cancelled
            } catch (
                _: Exception
            ) {
                mutableState.update {
                    it.copy(status = "Could not complete this step. Check the token service or reconnect Apple Music.")
                }
            } finally {
                mutableState.update { it.copy(busy = false) }
            }
        }
    }
}

data class AppleMusicValidationUiState(
    val endpoint: String = "",
    val connected: Boolean = false,
    val storefront: String? = null,
    val busy: Boolean = false,
    val status: String = "Set a developer token service to connect."
)
