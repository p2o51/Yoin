package com.gpo.yoin.ui.settings

sealed interface SettingsUiState {
    data object Loading : SettingsUiState

    data class Content(
        val serverUrl: String,
        val username: String,
        val isConnected: Boolean,
        val cacheSizeBytes: Long = 0L,
    ) : SettingsUiState

    data object Connecting : SettingsUiState

    data class Error(val message: String) : SettingsUiState
}
