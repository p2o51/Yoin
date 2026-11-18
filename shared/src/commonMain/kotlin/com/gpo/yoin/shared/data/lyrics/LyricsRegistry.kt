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
import com.gpo.yoin.shared.data.subsonic.platformHttpEngine
import io.ktor.client.HttpClient
import io.ktor.client.call.HttpClientCall
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.request.HttpRequestPipeline
import io.ktor.http.Url
import io.ktor.utils.io.InternalAPI
import kotlin.concurrent.Volatile
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/**
 * Thrown when automatic lyrics lookup failed due to transient network/HTTP errors and no reachable
 * provider cleanly answered with a match or an empty result.
 */
class LyricFetchException(message: String, cause: Throwable? = null) : Exception(message, cause)

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

class LyricsRegistry internal constructor(
    private val providers: List<LyricProvider>,
    private val ownedEngine: HttpClientEngine? = null,
) {

    /** QQ Music -> NetEase -> Huawei Music (manual search only) -> LRCLIB, on the platform's HTTP engine. */
    constructor() : this(defaultProviders(null))

    private constructor(built: BuiltProviders) : this(built.providers, built.ownedEngine)

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
     *
     * Throws [LyricFetchException] when providers fail due to transient network/HTTP errors and no reachable
     * provider cleanly answered with a match or empty result.
     */
    @Throws(LyricFetchException::class, CancellationException::class)
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
    @Throws(LyricFetchException::class, CancellationException::class)
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
        var firstFailure: LyricFetchException? = null
        val firstHit = try {
            fetchAutomatic(t, a, durationMs)
        } catch (e: LyricFetchException) {
            firstFailure = e
            null
        }
        if (firstHit != null) return firstHit
        val altTitle = alternateTitle?.trim().orEmpty()
        val altArtist = alternateArtist?.trim().orEmpty()
        val shouldTryAlternate = altTitle.isNotEmpty() &&
            altArtist.isNotEmpty() &&
            (hasNonLatinLetter(t) || hasNonLatinLetter(a)) &&
            (altTitle != t || altArtist != a)
        if (!shouldTryAlternate) {
            firstFailure?.let { throw it }
            return null
        }
        return fetchAutomatic(altTitle, altArtist, durationMs)
    }

    private suspend fun fetchAutomatic(title: String, artist: String, durationMs: Long?): LyricsResult? {
        var sawCleanAnswer = false
        var lastFailure: Throwable? = null
        for (p in providers) {
            if (!p.automatic) continue
            val searchTracker = HttpAttemptTracker()
            val match = try {
                withContext(LyricAttemptContext(searchTracker)) {
                    p.search(title, artist, durationMs)
                }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                currentCoroutineContext().ensureActive()
                lastFailure = e
                continue
            }
            if (match == null) {
                if (searchTracker.sawFailure && !searchTracker.sawClean) {
                    lastFailure = lastFailure ?: LyricFetchException("${p.name} search failed")
                } else {
                    sawCleanAnswer = true
                }
                continue
            }
            val fetchTracker = HttpAttemptTracker()
            val lrc = try {
                withContext(LyricAttemptContext(fetchTracker)) {
                    p.fetchNormalizedLyricOrThrow(match.songId)
                }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                currentCoroutineContext().ensureActive()
                lastFailure = e
                continue
            }
            if (lrc != null) return resultOf(lrc, p.name, match.songId)
            if (fetchTracker.sawFailure && !fetchTracker.sawClean) {
                lastFailure = lastFailure ?: LyricFetchException("${p.name} lyric fetch failed")
            } else {
                sawCleanAnswer = true
            }
        }
        if (!sawCleanAnswer && lastFailure != null) {
            throw (lastFailure as? LyricFetchException)
                ?: LyricFetchException(lastFailure.message ?: "Failed to fetch lyrics", lastFailure)
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
    @Throws(LyricFetchException::class, CancellationException::class)
    suspend fun fetchSelected(providerName: String, songId: String): LyricsResult? {
        val provider = providerNamed(providerName) ?: return null
        if (!provider.canFetch(songId)) return null
        val lrc = runTracked(providerName) { provider.fetchNormalizedLyricOrThrow(songId) } ?: return null
        return resultOf(lrc, provider.name, songId)
    }

    /** [fetchSelected] plus the source's own translation (QQ, NetEase, Huawei; LRCLIB has none). */
    @Throws(LyricFetchException::class, CancellationException::class)
    suspend fun fetchSelectedWithTranslation(providerName: String, songId: String): LyricsTranslationResult? {
        val provider = providerNamed(providerName) ?: return null
        if (!provider.canFetch(songId)) return null
        val payload = runTracked(providerName) {
            provider.fetchNormalizedLyricWithTranslationOrThrow(songId)
        } ?: return null
        return translationResultOf(payload, provider.name, songId)
    }

    /** Searches [providerName] for [title] / [artist] (verified match, no duration), then fetches lyrics + translation. */
    @Throws(LyricFetchException::class, CancellationException::class)
    suspend fun searchAndFetchWithTranslation(
        providerName: String,
        title: String,
        artist: String,
    ): LyricsTranslationResult? {
        val provider = providerNamed(providerName) ?: return null
        val match = runTracked(providerName) { provider.search(title, artist, null) } ?: return null
        val payload = runTracked(providerName) {
            provider.fetchNormalizedLyricWithTranslationOrThrow(match.songId)
        } ?: return null
        return translationResultOf(payload, provider.name, match.songId)
    }

    /**
     * The translation step of Android's "translate lyrics" flow for one provider: by [providerSongId] when
     * it's fetchable, otherwise by searching [title] / [artist]. nil unless the source actually gave a
     * non-blank translation.
     */
    @Throws(LyricFetchException::class, CancellationException::class)
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
        ownedEngine?.close()
    }

    // ── internals ────────────────────────────────────────────────────────────

    private suspend fun <T> runTracked(providerName: String, block: suspend () -> T?): T? {
        val tracker = HttpAttemptTracker()
        val value = try {
            withContext(LyricAttemptContext(tracker)) { block() }
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            currentCoroutineContext().ensureActive()
            throw (e as? LyricFetchException)
                ?: LyricFetchException(e.message ?: "$providerName lyrics request failed", e)
        }
        if (value == null && tracker.sawFailure && !tracker.sawClean) {
            throw LyricFetchException(tracker.failureMessage ?: "$providerName lyrics request failed")
        }
        return value
    }

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

    private class BuiltProviders(
        val providers: List<LyricProvider>,
        val ownedEngine: HttpClientEngine?,
    )

    internal companion object {
        private fun defaultProviders(engine: HttpClientEngine?): BuiltProviders {
            val baseEngine = engine ?: platformHttpEngine()
            val trackedEngine = TrackingHttpClientEngine(baseEngine)
            val providers = listOf(
                QQLyricsProvider(trackedEngine),
                NetEaseLyricsProvider(trackedEngine),
                HuaweiLyricsProvider(trackedEngine),
                LrclibLyricsProvider(trackedEngine),
            )
            return BuiltProviders(
                providers = providers,
                ownedEngine = if (engine == null) baseEngine else null,
            )
        }

        /** Tests: every provider on one MockEngine. */
        fun withEngine(engine: HttpClientEngine): LyricsRegistry = LyricsRegistry(defaultProviders(engine))
    }
}

private class HttpAttemptTracker {
    @Volatile
    var sawClean: Boolean = false

    @Volatile
    var sawFailure: Boolean = false

    @Volatile
    var failureMessage: String? = null

    fun recordStatus(url: Url, status: Int) {
        val clean = status in 200..299 ||
            (status == 404 && url.encodedPath.startsWith("/api/get/"))
        if (clean) {
            sawClean = true
        } else {
            sawFailure = true
            if (failureMessage == null) failureMessage = "HTTP $status"
        }
    }

    fun recordFailure(error: Throwable) {
        sawFailure = true
        if (failureMessage == null) failureMessage = error.message ?: "Network error"
    }
}

private class LyricAttemptContext(val tracker: HttpAttemptTracker) : CoroutineContext.Element {
    override val key: CoroutineContext.Key<*> get() = Key

    companion object Key : CoroutineContext.Key<LyricAttemptContext>
}

@OptIn(InternalAPI::class)
private class TrackingHttpClientEngine(
    private val delegate: HttpClientEngine,
) : HttpClientEngine by delegate {
    override fun install(client: HttpClient) {
        delegate.install(client)
        client.requestPipeline.intercept(HttpRequestPipeline.Before) {
            val tracker = currentCoroutineContext()[LyricAttemptContext]?.tracker
            try {
                proceed()
                val call = subject as? HttpClientCall
                if (call != null && tracker != null) {
                    tracker.recordStatus(call.request.url, call.response.status.value)
                }
            } catch (e: Throwable) {
                if (e is CancellationException) throw e
                currentCoroutineContext().ensureActive()
                tracker?.recordFailure(e)
                throw e
            }
        }
    }
}
