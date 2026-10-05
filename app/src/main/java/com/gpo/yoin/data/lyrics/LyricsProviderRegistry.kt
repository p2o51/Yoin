package com.gpo.yoin.data.lyrics

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

/**
 * 歌词 provider 编排。[providers] 的顺序就是优先级：
 * - 自动兜底只轮询 [LyricProvider.automatic] 的 provider，第一个返回非空 LRC 的获胜；
 * - 手动搜索并行查询**所有** provider，按这个顺序分区返回候选；
 * - 自带译文的切换提议也按这个顺序往后找（见 [translationSwitchCandidates]）。
 * 不缓存、不翻译、不做额外超时叠加策略（每个 provider 自己有 callTimeout）。
 *
 * 默认顺序：QQ 音乐 → 网易云 → 华为音乐（只在手动搜索里）→ LRCLIB。前几家覆盖中文 /
 * 日韩流行并自带简体译文，LRCLIB 作为 FOSS 兜底（西文曲库最全）。
 */
class LyricsProviderRegistry(
    private val providers: List<LyricProvider> = listOf(
        QQLyricsProvider(),
        NetEaseLyricsProvider(),
        HuaweiLyricsProvider(),
        LrclibLyricsProvider(),
    ),
) {
    /** 手动搜索面板的分区，按优先级排列，含只在手动搜索里出现的 provider。 */
    val providerNames: List<String>
        get() = providers.map(LyricProvider::name)

    /** [providerName] 自带的译文能不能当作 [targetLanguage] 的翻译；不认识的名字（manual、subsonic）一律 false。 */
    fun servesNativeTranslation(providerName: String, targetLanguage: String): Boolean =
        providerNamed(providerName)?.nativeTranslation?.serves(targetLanguage) == true

    /**
     * 当前歌词源（[currentProviderName]）这首歌给不出译文时，可以提议"整套换过去"的歌词源，
     * 按优先级排列：自动、自带能服务 [targetLanguage] 的译文、并且排在当前之后。
     *
     * 只往后找：排在前面的自动源，自动兜底时已经用同样的歌名、艺人试过而没匹配上，
     * 再提议它只会再失败一次；用户手动选的源，则是用户自己跳过了前面的。只在手动搜索里
     * 出现的 provider 不会被提议——用户没选过它。
     */
    fun translationSwitchCandidates(currentProviderName: String, targetLanguage: String): List<String> {
        val current = providers.indexOfFirst { it.name == currentProviderName }
        if (current < 0) return emptyList()
        return providers.drop(current + 1)
            .filter { it.automatic && it.nativeTranslation?.serves(targetLanguage) == true }
            .map(LyricProvider::name)
    }

    suspend fun fetchLyric(title: String, artist: String): Hit? {
        for (p in providers) {
            if (!p.automatic) continue
            val match = p.search(title, artist) ?: continue
            val lrc = p.fetchNormalizedLyric(match.songId) ?: continue
            return Hit(lrc = lrc, providerName = p.name, providerSongId = match.songId)
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

    fun canFetch(providerName: String, songId: String): Boolean {
        val provider = providerNamed(providerName) ?: return false
        return provider.canFetch(songId)
    }

    suspend fun fetchSelectedLyric(providerName: String, songId: String): Hit? {
        val provider = providerNamed(providerName) ?: return null
        if (!provider.canFetch(songId)) return null
        val lrc = provider.fetchNormalizedLyric(songId) ?: return null
        return Hit(lrc = lrc, providerName = provider.name, providerSongId = songId)
    }

    suspend fun fetchSelectedLyricWithTranslation(
        providerName: String,
        songId: String,
    ): TranslationHit? {
        val provider = providerNamed(providerName) ?: return null
        if (!provider.canFetch(songId)) return null
        val payload = provider.fetchNormalizedLyricWithTranslation(songId) ?: return null
        return TranslationHit(
            lrc = payload.lyric,
            translatedLrc = payload.translatedLyric,
            providerName = provider.name,
            providerSongId = songId,
        )
    }

    suspend fun searchAndFetchLyricWithTranslation(
        providerName: String,
        title: String,
        artist: String,
    ): TranslationHit? {
        val provider = providerNamed(providerName) ?: return null
        val match = provider.search(title, artist) ?: return null
        val payload = provider.fetchNormalizedLyricWithTranslation(match.songId) ?: return null
        return TranslationHit(
            lrc = payload.lyric,
            translatedLrc = payload.translatedLyric,
            providerName = provider.name,
            providerSongId = match.songId,
        )
    }

    private fun providerNamed(name: String): LyricProvider? = providers.firstOrNull { it.name == name }

    /** 命中的歌词 + 是哪个 provider 给的（用于缓存落表 / 日志）。 */
    data class Hit(
        val lrc: String,
        val providerName: String,
        val providerSongId: String?,
    )

    data class TranslationHit(
        val lrc: String,
        val translatedLrc: String?,
        val providerName: String,
        val providerSongId: String,
    )

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
