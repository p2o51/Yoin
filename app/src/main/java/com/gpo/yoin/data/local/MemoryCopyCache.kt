package com.gpo.yoin.data.local

import androidx.room.Entity
import com.gpo.yoin.data.model.MediaId

/**
 * 「余音 Gemini 文案」缓存 —— 按 (profileId, provider, entityType, entityId)
 * 唯一。profileId 必须进 PK：两个 Subsonic profile（不同服务器）共享
 * provider="subsonic"，raw id 又在同一命名空间，少了 profileId 会串号。
 *
 * [copy] 是已渲染好的短句，展示在 Memory 卡片的情绪提示位；
 * [promptHash] 是生成时输入信号的稳定 hash（例如 title+artist+avgScore+
 * ratedCount），用于决定是否需要重新请求 Gemini。
 *
 * 只缓存专辑级（`entityType = "album"`），留出 entityType 字段给以后
 * 的歌单 / 歌手扩展；0.3 内实现只读 album 一类。
 */
@Entity(
    tableName = "memory_copy_cache",
    primaryKeys = ["profileId", "provider", "entityType", "entityId"],
)
data class MemoryCopyCache(
    val profileId: String,
    val provider: String = MediaId.PROVIDER_SUBSONIC,
    val entityType: String,
    val entityId: String,
    val copy: String,
    val promptHash: String,
    val generatedAt: Long = System.currentTimeMillis(),
    /**
     * AI 拟题（Memory 卡印章右列标题 + 首页 Jump Back In memory 槽位共用）。
     * 与 [copy] 同 key 同 TTL；[titlePromptHash] 含占用者（乐评/最新笔记）
     * 原文的 hash，占用者一变即失效重生成。NULL = 未拟过，走 deterministic
     * fallback。
     */
    val title: String? = null,
    val titlePromptHash: String? = null,
) {
    companion object {
        const val ENTITY_ALBUM = "album"
    }
}
