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
import com.gpo.yoin.ui.settings.assignAvatarShapes
import com.gpo.yoin.ui.settings.service.AccountFace
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
                            account = AccountFace(
                                title = "chen",
                                detail = "music.home.arpa",
                                avatarShape = sampleShapes.getValue("home"),
                            ),
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
                            existingProfileName = "Chen",
                            account = AccountFace(title = "Chen", avatarShape = sampleShapes.getValue("spotify")),
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

    private val sampleShapes =
        assignAvatarShapes(listOf("home" to 1L, "spotify" to 2L, "backup" to 3L, "apple" to 4L))

    private val sampleCards = listOf(
        ProfileCard(
            id = "home",
            displayName = "chen @ music.home.arpa",
            subtitle = "music.home.arpa",
            provider = ProviderKind.SUBSONIC,
            isActive = true,
            title = "chen",
            avatarShape = sampleShapes.getValue("home"),
        ),
        ProfileCard(
            id = "spotify",
            displayName = "Chen",
            subtitle = null,
            provider = ProviderKind.SPOTIFY,
            isActive = false,
            unavailableReason = "Reconnect",
            requiresReconnect = true,
            avatarShape = sampleShapes.getValue("spotify"),
        ),
        ProfileCard(
            id = "backup",
            displayName = "admin @ backup.example",
            subtitle = "backup.example",
            provider = ProviderKind.SUBSONIC,
            isActive = false,
            title = "admin",
            avatarShape = sampleShapes.getValue("backup"),
        ),
        ProfileCard(
            id = "apple",
            displayName = "Apple Music",
            subtitle = null,
            provider = ProviderKind.APPLE_MUSIC,
            isActive = false,
            avatarShape = sampleShapes.getValue("apple"),
        ),
    )
}
