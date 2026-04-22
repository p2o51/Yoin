package com.gpo.yoin.ui.memories

import com.gpo.yoin.data.model.Track

sealed interface MemoriesUiState {
    data object Loading : MemoriesUiState

    data object Empty : MemoriesUiState

    data class Error(val message: String) : MemoriesUiState

    data class Content(
        val memories: List<MemoryEntry>,
        val deckRevision: Int = 0,
        val deckDirection: MemoryDeckDirection = MemoryDeckDirection.Forward,
        val isLoadingAdjacentDeck: Boolean = false,
    ) : MemoriesUiState
}

enum class MemoryDeckDirection {
    Backward,
    Forward,
}

enum class MemoryEntityType {
    SONG,
    ALBUM,
    PLAYLIST,
}

data class MemoryEntry(
    val stableId: String,
    val sourceActivityId: Long,
    val entityType: MemoryEntityType,
    val entityId: String,
    val title: String,
    val supportingText: String,
    val metaText: String?,
    val coverArtUrl: String?,
    val timestamp: Long,
    val scoreText: String,
    val scoreSupportingText: String?,
    val footerText: String?,
    /**
     * 「余音 Gemini 文案」—— 专辑卡的感性短评。未开 BYOK 或生成失败时为
     * null；UI 层判 null 决定是否渲染该行，不显示占位。
     */
    val narrativeCopy: String? = null,
    val playbackSongs: List<Track>,
    val tracks: List<MemoryTrack>,
)

data class MemoryTrack(
    val stableId: String,
    val title: String,
    val artist: String,
    val durationSeconds: Int?,
    val rating: Float?,
)
