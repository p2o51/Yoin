package com.gpo.yoin.ui.nowplaying

import com.gpo.yoin.data.local.SongAboutEntry

/**
 * Projection of the `song_about_entries` rows for the current song. Used
 * by both compact (read-only preview) and fullscreen (editable) About
 * surfaces.
 */
sealed interface AboutUiState {

    /** No song yet, or song changed and observer hasn't rehydrated. */
    data object Idle : AboutUiState

    /** First-time lazy fetch is in flight. */
    data object Loading : AboutUiState

    /** Gemini key hasn't been configured in Settings. */
    data object ApiKeyMissing : AboutUiState

    /**
     * Cached entries for the current song. Order guaranteed by the DAO:
     * canonical first (by insertion order), then ask rows by `updatedAt desc`.
     * May be empty when observation races ahead of a pending lazy fetch.
     */
    data class Ready(val entries: List<SongAboutEntry>) : AboutUiState

    data class Error(val message: String) : AboutUiState
}
