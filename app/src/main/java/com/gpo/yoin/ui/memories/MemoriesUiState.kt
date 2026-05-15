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

enum class MemoryScoreKind(val label: String) {
    ALBUM_RATING("Album rating"),
    AVERAGE_TRACK_RATING("Avg track rating"),
    NONE("No rating yet"),
}

data class MemoryEntry(
    val stableId: String,
    val sourceActivityId: Long,
    val entityType: MemoryEntityType,
    val entityId: String,
    val entityProvider: String,
    val title: String,
    val supportingText: String,
    val metaText: String?,
    val coverArtUrl: String?,
    val timestamp: Long,
    val scoreText: String,
    val scoreKind: MemoryScoreKind = MemoryScoreKind.NONE,
    val scoreSupportingText: String?,
    val footerText: String?,
    val hasAlbumReview: Boolean = false,
    val noteCount: Int = 0,
    val askAiCount: Int = 0,
    val ratedTrackCount: Int = 0,
    val totalTrackCount: Int = 0,
    val ratingCoverage: Float = 0f,
    val playCount: Int = 0,
    val firstPlayedAt: Long? = null,
    val lastPlayedAt: Long? = null,
    val neoDbSynced: Boolean = false,
    val reasonChips: List<String> = emptyList(),
    /**
     * 「余音 Gemini 文案」或本地 deterministic fallback 文案。Gemini 路径不上传
     * notes/review 原文；未配置 BYOK 或生成失败时由本地信号生成一条轻量旁白。
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
