package com.gpo.yoin.data.lyrics

/**
 * 第三方歌词源的统一契约：按 title + artist 搜索到一个平台内 id，再按 id 拉 LRC 原文。
 * 端到端入口是 [getLyric]，失败（网络错、对方 404、空内容）一律返回 null，留给上层
 * 决定是否 fall through 到下一个 provider。
 *
 * 对应 Spotoolfy 的 `lib/services/lyrics/lyric_provider.dart`，但是：
 * - 不带翻译（`LyricResult` / `fetchLyricWithTranslation` 一律不 port）
 * - 不带缓存（`LyricCacheData` 不 port，上层 [LyricsProviderRegistry] 也不缓存）
 */
abstract class LyricProvider {

    /** 提供者 id，用于日志 / 诊断，目前不落库。 */
    abstract val name: String

    /** 平台内搜索，返回第一条匹配；找不到返回 null。 */
    abstract suspend fun search(title: String, artist: String): SongMatch?

    /** 按平台内 id 拉 LRC 原文；拿不到返回 null。 */
    abstract suspend fun fetchLyric(songId: String): String?

    /**
     * 规范化 LRC：HTML 实体 unescape + 折叠空行 + trim。仅处理 Spotoolfy 见过的
     * 少数实体，避免引入 `org.jsoup` 这类大包。
     */
    protected fun normalizeLyric(rawLyric: String): String {
        val unescaped = rawLyric
            .replace("&amp;", "&")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&quot;", "\"")
            .replace("&#39;", "'")
            .replace("&apos;", "'")
        return unescaped.replace(Regex("\\r?\\n+"), "\n").trim()
    }

    /**
     * 端到端：搜索 → 取词 → 规范化。任一步异常 / 空 → null。
     * 对应 dart 的 `getLyric(title, artist)`。
     */
    suspend fun getLyric(title: String, artist: String): String? {
        return try {
            val match = search(title, artist) ?: return null
            val raw = fetchLyric(match.songId) ?: return null
            normalizeLyric(raw).takeIf { it.isNotEmpty() }
        } catch (_: Exception) {
            null
        }
    }
}

/** 搜索命中。Provider 内部用这个中转 title/artist → 平台 id。 */
data class SongMatch(
    val songId: String,
    val title: String,
    val artist: String,
)
