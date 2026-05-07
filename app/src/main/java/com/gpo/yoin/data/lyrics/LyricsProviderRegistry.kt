package com.gpo.yoin.data.lyrics

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

/**
 * 歌词 provider 编排：自动兜底按 [providers] 顺序轮询，第一个返回非空 LRC 的
 * provider 获胜；手动搜索会并行查询所有 provider 并返回候选列表。
 * 不缓存、不翻译、不做额外超时叠加策略（每个 provider 自己有 callTimeout）。
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
    val providerNames: List<String>
        get() = providers.map(LyricProvider::name)

    suspend fun fetchLyric(title: String, artist: String): Hit? {
        for (p in providers) {
            val lrc = p.getLyric(title, artist) ?: continue
            return Hit(lrc = lrc, providerName = p.name)
        }
        return null
    }

    suspend fun search(
        title: String,
        artist: String,
        limitPerProvider: Int = 3,
    ): List<SearchResult> = searchByProvider(
        title = title,
        artist = artist,
        limitPerProvider = limitPerProvider,
    ).flatMap { providerResult ->
        providerResult.matches.map { match ->
            SearchResult(providerName = providerResult.providerName, match = match)
        }
    }

    suspend fun searchByProvider(
        title: String,
        artist: String,
        limitPerProvider: Int = 3,
    ): List<ProviderSearchResult> = coroutineScope {
        providers.map { provider ->
            async {
                val result = runCatching {
                    provider.searchMultiple(title, artist, limit = limitPerProvider)
                }
                ProviderSearchResult(
                    providerName = provider.name,
                    matches = result.getOrDefault(emptyList()),
                    errorMessage = result.exceptionOrNull()?.message,
                )
            }
        }.awaitAll()
    }

    suspend fun fetchSelectedLyric(providerName: String, songId: String): Hit? {
        val provider = providers.firstOrNull { it.name == providerName } ?: return null
        val lrc = provider.fetchNormalizedLyric(songId) ?: return null
        return Hit(lrc = lrc, providerName = provider.name)
    }

    /** 命中的歌词 + 是哪个 provider 给的（用于缓存落表 / 日志）。 */
    data class Hit(val lrc: String, val providerName: String)

    data class SearchResult(
        val providerName: String,
        val match: SongMatch,
    )

    data class ProviderSearchResult(
        val providerName: String,
        val matches: List<SongMatch>,
        val errorMessage: String?,
    )
}
