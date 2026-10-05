package com.gpo.yoin.data.local

import androidx.room.Entity

/**
 * 第三方 provider 拉到的原始 LRC 文本缓存，按 (source provider, raw track id)
 * 主键。
 *
 * 注意：主键里的 [trackProvider] 是"曲目"所属 provider（如 "spotify"），不是
 * 歌词 provider。[lyricsProvider] 才是 "qq" / "netease" / "huawei" / "lrclib" /
 * "manual"，翻译时按它找自带译文。
 *
 * 寿命规则见 [com.gpo.yoin.data.lyrics.LyricsCachePolicy]：自动行 30 天过期；用户选的行
 * （manual，或 [lyricsProviderSongId] 带 `user|` 标记）不按天数过期，[cachedAt] 对它们是
 * "最近一次用到"；整张表按总大小封顶。Subsonic 平时用服务端歌词（`getLyricsBySongId.view`），
 * 只有用户选的行会压过它。
 */
@Entity(
    tableName = "lyrics_cache",
    primaryKeys = ["trackProvider", "trackRawId"],
)
data class LyricsCache(
    val trackProvider: String,
    val trackRawId: String,
    val lyricsProvider: String,
    val lyricsProviderSongId: String?,
    val lrc: String,
    val cachedAt: Long,
)
