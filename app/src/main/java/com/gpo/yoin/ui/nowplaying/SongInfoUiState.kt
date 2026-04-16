package com.gpo.yoin.ui.nowplaying

sealed interface SongInfoUiState {

    data object Idle : SongInfoUiState

    data object Loading : SongInfoUiState

    data object ApiKeyMissing : SongInfoUiState

    data class Success(
        val creationTime: String?,
        val creationLocation: String?,
        val lyricist: String?,
        val composer: String?,
        val producer: String?,
        val review: String?,
    ) : SongInfoUiState

    data class Error(val message: String) : SongInfoUiState
}
