package com.gpo.yoin.data.lyrics

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeoutOrNull

/**
 * 歌词 provider 编排。[providers] 的顺序就是优先级：
 * - 自动兜底只轮询 [LyricProvider.automatic] 的 provider，第一个返回非空 LRC 的获胜（慢的那家带对冲，见 [fetchLyric]）；
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
    /** 自动兜底的对冲延迟，见 [fetchLyric]。 */
    private val hedgeDelayMs: Long = HEDGE_DELAY_MS,
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

    /**
     * 自动兜底：按优先级逐家试，第一家给出 LRC 的获胜（和顺序轮询同一个赢家）。
     *
     * 带对冲（hedge）：某家的 search 超过 [hedgeDelayMs] 还没回来，就先把下一家的 search
     * 发出去，免得一家卡住（callTimeout 15 s）拖住整首歌的歌词；它 miss 了再轮到下一家时，
     * 下一家的 search 往往已经回来了。只对冲 search：下一家的 fetchLyric 一定等前面每家都
     * miss 之后才发，前面的一命中，后面还在跑的 search 当场取消。常见情况（首家
     * [hedgeDelayMs] 内答复）请求数和顺序轮询完全一样。
     */
    suspend fun fetchLyric(title: String, artist: String, durationMs: Long? = null): Hit? {
        val automatic = providers.filter(LyricProvider::automatic)
        if (automatic.isEmpty()) return null
        return coroutineScope {
            val searches = ArrayList<Deferred<SongMatch?>>(automatic.size)
            fun searchOf(index: Int): Deferred<SongMatch?> {
                while (searches.size <= index) {
                    val provider = automatic[searches.size]
                    searches += async { missOnFailure { provider.search(title, artist, durationMs) } }
                }
                return searches[index]
            }
            try {
                for ((index, provider) in automatic.withIndex()) {
                    val search = searchOf(index)
                    val hasNext = index + 1 < automatic.size
                    val match = if (hasNext && !search.isCompleted) {
                        // Wrapped, so a miss (null) is told apart from the hedge timing out.
                        withTimeoutOrNull(hedgeDelayMs) { Answer(search.await()) }?.match
                            ?: run {
                                if (!search.isCompleted) searchOf(index + 1)
                                search.await()
                            }
                    } else {
                        search.await()
                    } ?: continue
                    val lrc = missOnFailure { provider.fetchNormalizedLyric(match.songId) } ?: continue
                    return@coroutineScope Hit(lrc = lrc, providerName = provider.name, providerSongId = match.songId)
                }
                null
            } finally {
                searches.forEach { it.cancel() }
            }
        }
    }

    private class Answer(val match: SongMatch?)

    /** A provider that throws counts as a miss (they return null on failure already); cancellation still cancels. */
    private suspend fun <T> missOnFailure(block: suspend () -> T?): T? = try {
        block()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        null
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

    companion object {
        /**
         * 比一次正常的 search（几百毫秒）长一点：常见情况不多发请求，一家卡住时
         * 只多等这么久就开始问下一家。
         */
        const val HEDGE_DELAY_MS = 400L
    }

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
