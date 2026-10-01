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
        val geminiTargetLanguage: String = "",
        val neoDbInstance: String = "",
        val neoDbAccessToken: String = "",
    ) : SettingsUiState
}

/** One account card in the horizontal switcher. */
data class ProfileCard(
    val id: String,
    val displayName: String,
    val subtitle: String?,
    val provider: ProviderKind,
    val isActive: Boolean,
    /**
     * When non-null, this profile is configured but can't currently be used
     * (e.g. Spotify profile without a Client ID). UI renders it desaturated
     * with the reason as a badge so the user understands why switching to
     * it would fail. Tapping opens the service page to recover.
     */
    val unavailableReason: String? = null,
    val requiresReconnect: Boolean = false,
    /**
     * Subsonic post-restore / missing-file recovery flag — Room row exists
     * on the `store:v1` marker but the encrypted credentials file isn't
     * on disk (most commonly: the user restored the app from a Google
     * backup, which doesn't carry secrets). Tap opens the Subsonic setup
     * page in manage mode so the user can re-enter credentials against
     * the same profile id.
     */
    val requiresCredentialsReentry: Boolean = false,
)

/** "Add an account" sheet visibility. */
data class ProviderPickerState(val visible: Boolean = false)

/** Delete-confirmation dialog state. */
sealed interface DeleteConfirmState {
    data object Hidden : DeleteConfirmState
    data class Confirming(val profileId: String, val displayName: String) : DeleteConfirmState
}

/** One-shot VM → Screen events. Delivered via `MutableSharedFlow(replay = 0)`. */
sealed interface SettingsOneShotEvent {
    /** Ask the Screen to launch the NeoDB OAuth `ActivityResultContract`. */
    data class LaunchNeoDbOAuth(val instance: String) : SettingsOneShotEvent

    /** Show a transient error snackbar; used when there's no form sheet to put the error in. */
    data class ShowError(val message: String) : SettingsOneShotEvent
}
