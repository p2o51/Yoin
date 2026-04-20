package com.gpo.yoin.data.local

import androidx.room.Entity

/**
 * 第三方 provider 拉到的原始 LRC 文本缓存，按 (source provider, raw track id)
 * 主键。命中策略：cachedAt >= now - 30 天。
 *
 * 注意：主键里的 [trackProvider] 是"曲目"所属 provider（如 "spotify"），不是
 * 歌词 provider。[lyricsProvider] 才是"qq" / "netease" / "lrclib"，只作诊断用。
 *
 * Subsonic 的歌词不走这里（走 `getLyricsBySongId.view`，服务端自己快）。
 */
@Entity(
    tableName = "lyrics_cache",
    primaryKeys = ["trackProvider", "trackRawId"],
)
data class LyricsCache(
    val trackProvider: String,
    val trackRawId: String,
    val lyricsProvider: String,
    val lrc: String,
    val cachedAt: Long,
)
