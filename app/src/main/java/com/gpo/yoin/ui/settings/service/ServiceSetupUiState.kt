package com.gpo.yoin.ui.settings.service

import com.gpo.yoin.ui.common.UiText

data class ServiceSetupUiState(
    val service: SetupService,
    /** Adding a new account vs. managing (edit / reconnect) an existing one. */
    val isManaging: Boolean,
    val existingProfileName: String? = null,
    /** Manage mode: the account's face, matching its Settings card. Null while adding. */
    val account: AccountFace? = null,
    val canAddProfile: Boolean = true,
    val subsonic: SubsonicFormState = SubsonicFormState(),
    val spotify: SpotifySetupState = SpotifySetupState(),
)

/** How an account presents itself: the card title, where it lives, its avatar shape. */
data class AccountFace(
    val title: String,
    val detail: String? = null,
    val avatarShape: Int = 0,
    /** The account's picture on its service (Spotify profile photo), when known. */
    val photoUrl: String? = null,
)

data class SubsonicFormState(
    val initialUrl: String = "",
    val initialUsername: String = "",
    val initialPassword: String = "",
    /** Profile row exists but its encrypted credentials file doesn't (e.g. after a restore). */
    val credentialsMissing: Boolean = false,
    /** False while an edit form is still reading the stored credentials. */
    val loaded: Boolean = true,
    val isBusy: Boolean = false,
    val status: SubsonicStatus = SubsonicStatus.Idle,
)

sealed interface SubsonicStatus {
    data object Idle : SubsonicStatus
    data object Testing : SubsonicStatus
    data object Saving : SubsonicStatus
    data object Reachable : SubsonicStatus
    data class Failed(val message: UiText) : SubsonicStatus
}

data class SpotifySetupState(
    val clientId: String = "",
    val usesBuildFallback: Boolean = false,
    /** Runtime blocker worth one line (Premium / app missing / auth). Null = nothing to say. */
    val accountIssue: UiText? = null,
    val needsReconnect: Boolean = false,
)

sealed interface ServiceSetupEvent {
    data class LaunchSpotifyOAuth(val targetProfileId: String?) : ServiceSetupEvent
    data class ShowError(val message: UiText) : ServiceSetupEvent
    data class Done(val activateProfileId: String?) : ServiceSetupEvent
}
