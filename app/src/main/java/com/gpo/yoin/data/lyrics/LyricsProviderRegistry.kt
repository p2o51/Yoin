package com.gpo.yoin.data.lyrics

/**
 * 极简串行兜底：按 [providers] 顺序轮询，第一个返回非空 LRC 的 provider 获胜。
 * 没有并行、没有缓存、没有翻译、没有超时叠加策略（每个 provider 自己有 callTimeout）。
 *
 * 默认顺序：QQ 音乐 → 网易云 → LRCLIB。前两者覆盖中文 / 日韩流行，LRCLIB 作为
 * FOSS 兜底（西文曲库最全）。
 */
class LyricsProviderRegistry(
    private val providers: List<LyricProvider> = listOf(
        QQLyricsProvider(),
        NetEaseLyricsProvider(),
        LrclibLyricsProvider(),
    ),
) {
    suspend fun fetchLyric(title: String, artist: String): Hit? {
        for (p in providers) {
            val lrc = p.getLyric(title, artist) ?: continue
            return Hit(lrc = lrc, providerName = p.name)
        }
        return null
    }

    /** 命中的歌词 + 是哪个 provider 给的（用于缓存落表 / 日志）。 */
    data class Hit(val lrc: String, val providerName: String)
}
