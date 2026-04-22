package com.gpo.yoin.data.local

import androidx.room.Entity
import com.gpo.yoin.data.model.MediaId

/**
 * 「余音 Gemini 文案」缓存 —— 按 (provider, entityType, entityId) 唯一。
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
    primaryKeys = ["provider", "entityType", "entityId"],
)
data class MemoryCopyCache(
    val provider: String = MediaId.PROVIDER_SUBSONIC,
    val entityType: String,
    val entityId: String,
    val copy: String,
    val promptHash: String,
    val generatedAt: Long = System.currentTimeMillis(),
) {
    companion object {
        const val ENTITY_ALBUM = "album"
    }
}
