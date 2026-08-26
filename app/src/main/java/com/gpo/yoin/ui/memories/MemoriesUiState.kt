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
    AVERAGE_TRACK_RATING("Track average"),
    NONE("No rating yet"),
}

/**
 * NeoDB 同步的五态（rule 6：推送需要 rating + 非空 review，缺哪半提示哪半）。
 * UNAVAILABLE 整行不渲染 —— 它不是一个状态文案，是「这张卡与 NeoDB 无关」。
 */
enum class MemoryNeoDbState {
    SYNCED,
    READY,
    NEEDS_REVIEW,
    NEEDS_RATING,
    UNAVAILABLE,
}

/**
 * 用户在这张专辑上写下的一段字 —— 乐评、专辑笔记或单曲笔记。
 * 卡片正文槽与笔记卡都渲染它；serif 字面 = 用户的字（机器文案永远不是 serif）。
 */
data class MemoryWriting(
    val kind: Kind,
    val text: String,
    val writtenAt: Long,
    /** SONG_NOTE 才有：曲名 + 笔记锚定的时间点（song_notes.positionMs，v27 起）。 */
    val trackTitle: String? = null,
    val positionMs: Long? = null,
) {
    enum class Kind { REVIEW, ALBUM_NOTE, SONG_NOTE }
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
    val neoDbState: MemoryNeoDbState = MemoryNeoDbState.UNAVAILABLE,
    /**
     * AI 拟题（或 deterministic fallback）—— 印章右列的标题，同一枚复用到
     * 首页 Jump Back In 的 memory 槽位。占用者（乐评/最新笔记）在场时由
     * Gemini 从原文 + 专辑背景拟出（design.md 拟题豁免，2026-07-26）。
     */
    val memoryTitle: String? = null,
    /** 专辑乐评正文（正文槽阶梯①）。 */
    val review: MemoryWriting? = null,
    /** newest first；UI 只画 ≤3 条（1 条可能被提升进正文槽 + 笔记卡 2 条）。 */
    val writings: List<MemoryWriting> = emptyList(),
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
