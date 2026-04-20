package com.gpo.yoin.data.lyrics

/**
 * 极简串行兜底：按 [providers] 顺序轮询，第一个返回非空 LRC 的 provider 获胜。
 * 没有并行、没有缓存、没有翻译、没有超时叠加策略（每个 provider 自己有 callTimeout）。
 *
 * 默认顺序：QQ 音乐 → 网易云。
 */
class LyricsProviderRegistry(
    private val providers: List<LyricProvider> = listOf(
        QQLyricsProvider(),
        NetEaseLyricsProvider(),
    ),
) {
    suspend fun fetchLyric(title: String, artist: String): String? {
        for (p in providers) {
            p.getLyric(title, artist)?.let { return it }
        }
        return null
    }
}
