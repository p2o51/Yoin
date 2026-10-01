package com.gpo.yoin.debug

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import com.gpo.yoin.YoinActivityRoot
import com.gpo.yoin.data.local.GeminiConfig
import com.gpo.yoin.data.profile.ProfileManager
import com.gpo.yoin.data.profile.ProviderKind
import com.gpo.yoin.enableYoinEdgeToEdge
import com.gpo.yoin.ui.settings.DeleteConfirmState
import com.gpo.yoin.ui.settings.ProfileCard
import com.gpo.yoin.ui.settings.SettingsContent
import com.gpo.yoin.ui.settings.SettingsUiState
import com.gpo.yoin.ui.settings.service.ServiceSetupContent
import com.gpo.yoin.ui.settings.service.ServiceSetupUiState
import com.gpo.yoin.ui.settings.service.SetupService
import com.gpo.yoin.ui.settings.service.SpotifySetupState
import com.gpo.yoin.ui.settings.service.SubsonicFormState

/**
 * Account-free QA for Settings with existing accounts (sample data, no network).
 *
 *   adb shell am start -n com.gpo.yoin/.debug.SettingsPreviewActivity --es variant accounts
 *
 * variant: accounts (default) | picker | addSpotify | manageSubsonic | manageSpotify
 */
class SettingsPreviewActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableYoinEdgeToEdge()
        val variant = intent.getStringExtra("variant") ?: "accounts"
        setContent {
            YoinActivityRoot {
                when (variant) {
                    "manageSubsonic" -> ServiceSetupContent(
                        state = ServiceSetupUiState(
                            service = SetupService.Subsonic,
                            isManaging = true,
                            existingProfileName = "chen @ music.home.arpa",
                            subsonic = SubsonicFormState(
                                initialUrl = "https://music.home.arpa",
                                initialUsername = "chen",
                                initialPassword = "sample-password",
                            ),
                        ),
                        onBackClick = ::finish,
                        modifier = Modifier.fillMaxSize(),
                    )
                    "addSpotify" -> ServiceSetupContent(
                        state = ServiceSetupUiState(service = SetupService.Spotify, isManaging = false),
                        onBackClick = ::finish,
                        modifier = Modifier.fillMaxSize(),
                    )
                    "manageSpotify" -> ServiceSetupContent(
                        state = ServiceSetupUiState(
                            service = SetupService.Spotify,
                            isManaging = true,
                            existingProfileName = "Chen's Spotify",
                            spotify = SpotifySetupState(clientId = "sample", needsReconnect = true),
                        ),
                        onBackClick = ::finish,
                        modifier = Modifier.fillMaxSize(),
                    )
                    else -> SettingsContent(
                        uiState = SettingsUiState.Content(
                            profileCards = sampleCards,
                            activeProfileId = "home",
                            canAddProfile = true,
                            cacheSizeBytes = 312_000_000L,
                            geminiApiKey = "sample",
                            geminiTargetLanguage = GeminiConfig.DEFAULT_TARGET_LANGUAGE,
                            neoDbAccessToken = "sample",
                        ),
                        switchingState = ProfileManager.SwitchState.Idle,
                        providerPickerVisible = variant == "picker",
                        deleteConfirmState = DeleteConfirmState.Hidden,
                        onBackClick = ::finish,
                        onSwitchToProfile = {},
                        onOpenService = {},
                        onRequestDeleteProfile = {},
                        onShowProviderPicker = {},
                        onHideProviderPicker = {},
                        onDismissSwitchError = {},
                        onDismissDeleteConfirm = {},
                        onConfirmDeleteProfile = {},
                        onClearCache = {},
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }
        }
    }

    private val sampleCards = listOf(
        ProfileCard(
            id = "home",
            displayName = "chen @ music.home.arpa",
            subtitle = "music.home.arpa",
            provider = ProviderKind.SUBSONIC,
            isActive = true,
        ),
        ProfileCard(
            id = "spotify",
            displayName = "Chen's Spotify",
            subtitle = "Spotify account",
            provider = ProviderKind.SPOTIFY,
            isActive = false,
            unavailableReason = "Reconnect",
            requiresReconnect = true,
        ),
        ProfileCard(
            id = "backup",
            displayName = "Backup server",
            subtitle = "backup.example",
            provider = ProviderKind.SUBSONIC,
            isActive = false,
        ),
    )
}
