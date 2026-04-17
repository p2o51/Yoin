package com.gpo.yoin.ui.settings

import com.gpo.yoin.data.profile.ProfileManager
import com.gpo.yoin.data.profile.ProviderKind

sealed interface SettingsUiState {
    data object Loading : SettingsUiState

    data class Content(
        val profileCards: List<ProfileCard>,
        val activeProfileId: String?,
        val canAddProfile: Boolean,
        val maxProfiles: Int = ProfileManager.MAX_PROFILES,
        val cacheSizeBytes: Long = 0L,
        val geminiApiKey: String = "",
        val spotifyClientId: String = "",
        val spotifyClientIdUsesFallback: Boolean = false,
    ) : SettingsUiState
}

/** A single profile row rendered in the horizontal switcher. */
data class ProfileCard(
    val id: String,
    val displayName: String,
    val subtitle: String?,
    val provider: ProviderKind,
    val isActive: Boolean,
)

/** Bottom-sheet state for creating or editing a profile. */
sealed interface ProfileFormSheet {
    data object Hidden : ProfileFormSheet

    data class Visible(
        val mode: Mode,
        val provider: ProviderKind,
        val initialUrl: String = "",
        val initialUsername: String = "",
        val initialPassword: String = "",
        val isTesting: Boolean = false,
        val testResult: ConnectionResult? = null,
        val saveError: String? = null,
    ) : ProfileFormSheet {
        sealed interface Mode {
            data object Create : Mode
            data class Edit(val profileId: String) : Mode
        }
    }
}

/** Bottom-sheet state for picking a provider when creating a profile. */
data class ProviderPickerState(val visible: Boolean = false)

/** Delete-confirmation dialog state. */
sealed interface DeleteConfirmState {
    data object Hidden : DeleteConfirmState
    data class Confirming(val profileId: String, val displayName: String) : DeleteConfirmState
}

sealed interface ConnectionResult {
    data object Success : ConnectionResult
    data class Failure(val message: String) : ConnectionResult
}

/** One-shot VM → Screen events. Delivered via `MutableSharedFlow(replay = 0)`. */
sealed interface SettingsOneShotEvent {
    /** Ask the Screen to launch the Spotify OAuth `ActivityResultContract`. */
    data object LaunchSpotifyOAuth : SettingsOneShotEvent

    /** Show a transient error snackbar; used when there's no form sheet to put the error in. */
    data class ShowError(val message: String) : SettingsOneShotEvent
}
