package com.gpo.yoin.data.lyrics

/**
 * 第三方歌词源的统一契约：按 title + artist 搜索到一个平台内 id，再按 id 拉 LRC 原文。
 * 端到端入口是 [getLyric]，失败（网络错、对方 404、空内容）一律返回 null，留给上层
 * 决定是否 fall through 到下一个 provider。
 *
 * 对应 Spotoolfy 的 `lib/services/lyrics/lyric_provider.dart`，但是：
 * - 不带缓存（`LyricCacheData` 不 port，上层 [LyricsProviderRegistry] 也不缓存）
 */
abstract class LyricProvider {

    /** 提供者 id，用于日志 / 诊断，目前不落库。 */
    abstract val name: String

    /** 平台内搜索，返回第一条匹配；找不到返回 null。 */
    abstract suspend fun search(title: String, artist: String): SongMatch?

    /** 平台内搜索，返回多条候选；默认退化为第一条匹配。 */
    open suspend fun searchMultiple(
        title: String,
        artist: String,
        limit: Int = 3,
    ): List<SongMatch> = search(title, artist)?.let(::listOf).orEmpty().take(limit)

    /** 按平台内 id 拉 LRC 原文；拿不到返回 null。 */
    abstract suspend fun fetchLyric(songId: String): String?

    /** 按平台内 id 拉原文 + Provider 自带翻译；默认退化为只拉原文。 */
    open suspend fun fetchLyricWithTranslation(songId: String): LyricPayload? {
        val raw = fetchLyric(songId) ?: return null
        val lyric = normalizeLyric(raw).takeIf { it.isNotEmpty() } ?: return null
        return LyricPayload(lyric = lyric)
    }

    /**
     * 规范化 LRC：HTML 实体 unescape + 折叠空行 + trim。仅处理 Spotoolfy 见过的
     * 少数实体，避免引入 `org.jsoup` 这类大包。
     */
    fun normalizeLyric(rawLyric: String): String {
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
            fetchNormalizedLyric(match.songId)
        } catch (_: Exception) {
            null
        }
    }

    /** 拉取指定 provider 内 song id 的歌词并规范化。 */
    suspend fun fetchNormalizedLyric(songId: String): String? {
        return try {
            val raw = fetchLyric(songId) ?: return null
            normalizeLyric(raw).takeIf { it.isNotEmpty() }
        } catch (_: Exception) {
            null
        }
    }

    /** 拉取指定 provider 内 song id 的歌词和翻译并规范化。 */
    suspend fun fetchNormalizedLyricWithTranslation(songId: String): LyricPayload? {
        return try {
            fetchLyricWithTranslation(songId)?.normalizedBy(this)
        } catch (_: Exception) {
            null
        }
    }

    private fun LyricPayload.normalizedBy(provider: LyricProvider): LyricPayload? {
        val normalizedLyric = provider.normalizeLyric(lyric).takeIf { it.isNotEmpty() }
            ?: return null
        val normalizedTranslation = translatedLyric
            ?.let(provider::normalizeLyric)
            ?.takeIf { it.isNotEmpty() }
        return copy(lyric = normalizedLyric, translatedLyric = normalizedTranslation)
    }
}

/** 搜索命中。Provider 内部用这个中转 title/artist → 平台 id。 */
data class SongMatch(
    val songId: String,
    val title: String,
    val artist: String,
)

/** Provider 原始歌词 payload。翻译歌词同样应是 LRC 形态。 */
data class LyricPayload(
    val lyric: String,
    val translatedLyric: String? = null,
)
