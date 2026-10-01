package com.gpo.yoin.ui.settings.service

data class ServiceSetupUiState(
    val service: SetupService,
    /** Adding a new account vs. managing (edit / reconnect) an existing one. */
    val isManaging: Boolean,
    val existingProfileName: String? = null,
    val canAddProfile: Boolean = true,
    val subsonic: SubsonicFormState = SubsonicFormState(),
    val spotify: SpotifySetupState = SpotifySetupState(),
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
    data class Failed(val message: String) : SubsonicStatus
}

data class SpotifySetupState(
    val clientId: String = "",
    val usesBuildFallback: Boolean = false,
    /** Runtime blocker worth one line (Premium / app missing / auth). Null = nothing to say. */
    val accountIssue: String? = null,
    val needsReconnect: Boolean = false,
)

sealed interface ServiceSetupEvent {
    data class LaunchSpotifyOAuth(val targetProfileId: String?) : ServiceSetupEvent
    data class ShowError(val message: String) : ServiceSetupEvent
    data class Done(val activateProfileId: String?) : ServiceSetupEvent
}
