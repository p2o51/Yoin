/*
 * LyricsRegistry — Yoin's lyrics providers (QQ Music, NetEase, Huawei Music,
 * LRCLIB) behind one small object. Port of the Android app's
 * data/lyrics/LyricsProviderRegistry plus the provider-facing logic of
 * YoinRepository's lyrics code.
 *
 * Using it from Swift (framework `YoinShared`):
 *
 *     let lyrics = LyricsRegistry()
 *
 *     // Automatic: QQ -> NetEase -> LRCLIB, first non-empty LRC wins.
 *     if let hit = try await lyrics.autoFetch(title: "晴天", artist: "周杰伦",
 *                                             durationMs: KotlinLong(value: 269_000)) {
 *         hit.lyrics          // parsed `Lyrics` (synced lines or plain text)
 *         hit.lrc             // the normalised LRC text, if you want to store it
 *         hit.providerName    // "qq" | "netease" | "lrclib" — store with providerSongId
 *         hit.providerSongId
 *     }
 *
 *     // Manual search: one section per provider (qq, netease, huawei, lrclib), in priority order.
 *     let sections = try await lyrics.search(query: "Lemon 米津玄師")
 *     for s in sections { s.providerName; s.candidates; s.errorMessage }
 *
 *     // The user picked a candidate:
 *     let picked = try await lyrics.fetchSelected(providerName: c.providerName, songId: c.songId)
 *     // ...or with the source's own Chinese translation (QQ, NetEase, Huawei):
 *     let both = try await lyrics.fetchSelectedWithTranslation(providerName: c.providerName, songId: c.songId)
 *     both?.translations      // [KotlinInt: String], keyed by index into both.lyrics.lines
 *
 * Notes for Swift:
 * - Nothing here throws except Task cancellation: network errors, 404s, empty
 *   bodies and parse failures all come back as nil / an empty list (a provider
 *   that could not answer a manual search shows up as a section with
 *   `errorMessage`).
 * - Kotlin default arguments aren't exported; pass every argument (`nil` for
 *   optional ones, `KotlinLong(value:)` for `durationMs`).
 * - Thread-safe: any number of calls may run at once. One registry per app is
 *   enough; it owns its HTTP clients, `close()` releases them.
 * - There is no cache: callers decide what to persist (Android stores the LRC
 *   per track and treats user-picked results as never-expiring).
 */
package com.gpo.yoin.shared.data.lyrics

import com.gpo.yoin.shared.data.model.Lyrics
import io.ktor.client.engine.HttpClientEngine
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

/** One search hit from one provider. Pass [providerName] + [songId] back to [LyricsRegistry.fetchSelected]. */
data class LyricsCandidate(
    val providerName: String,
    val songId: String,
    val title: String,
    val artist: String,
)

/** The manual-search result of one provider. A provider with no hits has an empty [candidates]; one that failed has [errorMessage]. */
data class ProviderSection(
    val providerName: String,
    val candidates: List<LyricsCandidate>,
    val errorMessage: String?,
)

/** Lyrics found for a track and who they came from. [lrc] is normalised LRC (or plain text for LRCLIB's unsynced lyrics). */
data class LyricsResult(
    val lrc: String,
    val lyrics: Lyrics,
    val providerName: String,
    val providerSongId: String,
)

/**
 * Lyrics plus the source's own translation. [translatedLrc] is null when the source has none for this song;
 * [translations] maps an index into [lyrics].lines to its translated text (lines the source left untranslated
 * are absent), empty when there is no translation.
 */
data class LyricsTranslationResult(
    val lrc: String,
    val lyrics: Lyrics,
    val translatedLrc: String?,
    val translations: Map<Int, String>,
    val providerName: String,
    val providerSongId: String,
)

class LyricsRegistry internal constructor(private val providers: List<LyricProvider>) {

    /** QQ Music -> NetEase -> Huawei Music (manual search only) -> LRCLIB, on the platform's HTTP engine. */
    constructor() : this(defaultProviders(null))

    /** Provider names in priority order, including the manual-only ones. These are the section names of [search]. */
    val providerNames: List<String>
        get() = providers.map(LyricProvider::name)

    /** Does [providerName]'s own translation count as a [targetLanguage] translation? Unknown names (manual, subsonic) are false. */
    fun servesNativeTranslation(providerName: String, targetLanguage: String): Boolean =
        providerNamed(providerName)?.nativeTranslation?.serves(targetLanguage) == true

    /**
     * Providers whose whole lyrics + translation could be offered when the current one ([currentProviderName])
     * has no translation for this song: automatic, serving [targetLanguage], and ranked after the current one.
     */
    fun translationSwitchCandidates(currentProviderName: String, targetLanguage: String): List<String> {
        val current = providers.indexOfFirst { it.name == currentProviderName }
        if (current < 0) return emptyList()
        return providers.drop(current + 1)
            .filter { it.automatic && it.nativeTranslation?.serves(targetLanguage) == true }
            .map(LyricProvider::name)
    }

    /** Can [providerName] fetch [songId] directly (QQ rejects old songmid-style ids, Huawei rejects foreign ids)? */
    fun canFetch(providerName: String, songId: String): Boolean =
        providerNamed(providerName)?.canFetch(songId) == true

    // ── automatic ────────────────────────────────────────────────────────────

    /**
     * The automatic fallback chain: each automatic provider (QQ, NetEase, LRCLIB) in order searches and
     * verifies a match, the first with non-empty lyrics wins. [durationMs] (nullable) breaks ties between
     * same-titled recordings. Blank [title] or [artist] -> nil.
     */
    @Throws(CancellationException::class)
    suspend fun autoFetch(title: String, artist: String, durationMs: Long?): LyricsResult? {
        val t = title.trim()
        val a = artist.trim()
        if (t.isEmpty() || a.isEmpty()) return null
        return fetchAutomatic(t, a, durationMs)
    }

    /**
     * [autoFetch], then once more under a storefront's other names (Apple Music Japan writes Kim Petras as
     * キム・ペトラス) — only when the shown names contain non-Latin letters and the alternates differ.
     * The shown names always go first.
     */
    @Throws(CancellationException::class)
    suspend fun autoFetchWithAlternateNames(
        title: String,
        artist: String,
        alternateTitle: String?,
        alternateArtist: String?,
        durationMs: Long?,
    ): LyricsResult? {
        val t = title.trim()
        val a = artist.trim()
        if (t.isEmpty() || a.isEmpty()) return null
        fetchAutomatic(t, a, durationMs)?.let { return it }
        val altTitle = alternateTitle?.trim().orEmpty()
        val altArtist = alternateArtist?.trim().orEmpty()
        if (altTitle.isEmpty() || altArtist.isEmpty()) return null
        if (!hasNonLatinLetter(t) && !hasNonLatinLetter(a)) return null
        if (altTitle == t && altArtist == a) return null
        return fetchAutomatic(altTitle, altArtist, durationMs)
    }

    private suspend fun fetchAutomatic(title: String, artist: String, durationMs: Long?): LyricsResult? {
        for (p in providers) {
            if (!p.automatic) continue
            val match = p.search(title, artist, durationMs) ?: continue
            val lrc = p.fetchNormalizedLyric(match.songId) ?: continue
            return resultOf(lrc, p.name, match.songId)
        }
        return null
    }

    private fun hasNonLatinLetter(text: String): Boolean {
        var found = false
        forEachCodePoint(text) { cp -> if (!found && isLetterCp(cp) && !isLatinCp(cp)) found = true }
        return found
    }

    // ── manual search ────────────────────────────────────────────────────────

    /**
     * Manual search with one free-text [query] (title and artist together): every provider is asked in
     * parallel for up to 3 candidates; the result has one section per provider, in priority order, including
     * the manual-only ones and the ones that found nothing. Blank query -> empty list.
     */
    @Throws(CancellationException::class)
    suspend fun search(query: String): List<ProviderSection> {
        val q = query.trim()
        if (q.isEmpty()) return emptyList()
        return search(title = q, artist = "", limitPerProvider = 3)
    }

    /** [search] with separate [title] / [artist] and an explicit number of candidates per provider. */
    @Throws(CancellationException::class)
    suspend fun search(title: String, artist: String, limitPerProvider: Int): List<ProviderSection> =
        coroutineScope {
            providers.map { provider ->
                async {
                    val result = attempt(onError = { Result.failure(it) }) {
                        Result.success(provider.searchMultiple(title, artist, limit = limitPerProvider))
                    }
                    ProviderSection(
                        providerName = provider.name,
                        candidates = result.getOrDefault(emptyList()).map { match ->
                            LyricsCandidate(provider.name, match.songId, match.title, match.artist)
                        },
                        errorMessage = result.exceptionOrNull()?.message,
                    )
                }
            }.awaitAll()
        }

    // ── fetching a chosen candidate ──────────────────────────────────────────

    /** The lyrics of a candidate the user picked. nil: unknown provider, [canFetch] false, or no lyrics. */
    @Throws(CancellationException::class)
    suspend fun fetchSelected(providerName: String, songId: String): LyricsResult? {
        val provider = providerNamed(providerName) ?: return null
        if (!provider.canFetch(songId)) return null
        val lrc = provider.fetchNormalizedLyric(songId) ?: return null
        return resultOf(lrc, provider.name, songId)
    }

    /** [fetchSelected] plus the source's own translation (QQ, NetEase, Huawei; LRCLIB has none). */
    @Throws(CancellationException::class)
    suspend fun fetchSelectedWithTranslation(providerName: String, songId: String): LyricsTranslationResult? {
        val provider = providerNamed(providerName) ?: return null
        if (!provider.canFetch(songId)) return null
        val payload = provider.fetchNormalizedLyricWithTranslation(songId) ?: return null
        return translationResultOf(payload, provider.name, songId)
    }

    /** Searches [providerName] for [title] / [artist] (verified match, no duration), then fetches lyrics + translation. */
    @Throws(CancellationException::class)
    suspend fun searchAndFetchWithTranslation(
        providerName: String,
        title: String,
        artist: String,
    ): LyricsTranslationResult? {
        val provider = providerNamed(providerName) ?: return null
        val match = provider.search(title, artist, null) ?: return null
        val payload = provider.fetchNormalizedLyricWithTranslation(match.songId) ?: return null
        return translationResultOf(payload, provider.name, match.songId)
    }

    /**
     * The translation step of Android's "translate lyrics" flow for one provider: by [providerSongId] when
     * it's fetchable, otherwise by searching [title] / [artist]. nil unless the source actually gave a
     * non-blank translation.
     */
    @Throws(CancellationException::class)
    suspend fun fetchTranslation(
        providerName: String,
        providerSongId: String?,
        title: String,
        artist: String,
    ): LyricsTranslationResult? {
        val hit = if (providerSongId != null && canFetch(providerName, providerSongId)) {
            fetchSelectedWithTranslation(providerName, providerSongId)
        } else {
            searchAndFetchWithTranslation(providerName, title, artist)
        } ?: return null
        return hit.takeIf { it.translatedLrc?.isNotBlank() == true && it.translations.isNotEmpty() }
    }

    /** Releases the providers' HTTP clients. */
    fun close() {
        providers.forEach(LyricProvider::close)
    }

    // ── internals ────────────────────────────────────────────────────────────

    private fun providerNamed(name: String): LyricProvider? = providers.firstOrNull { it.name == name }

    private fun resultOf(lrc: String, providerName: String, songId: String) =
        LyricsResult(lrc = lrc, lyrics = LrcParser.parse(lrc), providerName = providerName, providerSongId = songId)

    private fun translationResultOf(payload: LyricPayload, providerName: String, songId: String): LyricsTranslationResult {
        val lyrics = LrcParser.parse(payload.lyric)
        val translated = payload.translatedLyric
        return LyricsTranslationResult(
            lrc = payload.lyric,
            lyrics = lyrics,
            translatedLrc = translated,
            translations = translated?.takeIf { it.isNotBlank() }
                ?.let { LyricTranslations.align(lyrics, it) }
                .orEmpty(),
            providerName = providerName,
            providerSongId = songId,
        )
    }

    internal companion object {
        fun defaultProviders(engine: HttpClientEngine?): List<LyricProvider> = listOf(
            QQLyricsProvider(engine),
            NetEaseLyricsProvider(engine),
            HuaweiLyricsProvider(engine),
            LrclibLyricsProvider(engine),
        )

        /** Tests: every provider on one MockEngine. */
        fun withEngine(engine: HttpClientEngine): LyricsRegistry = LyricsRegistry(defaultProviders(engine))
    }
}
