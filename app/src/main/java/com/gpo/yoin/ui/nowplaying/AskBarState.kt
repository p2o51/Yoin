package com.gpo.yoin.ui.nowplaying

/**
 * Transient state of the Ask Gemini bar in the fullscreen About pane. Pure
 * UI concern — does NOT get persisted. While `Loading`, no placeholder row
 * is written to the database; the eventual answer is upserted atomically.
 */
sealed interface AskBarState {
    data object Idle : AskBarState
    data object Focused : AskBarState
    data object Loading : AskBarState
    data class Error(val message: String) : AskBarState
}
