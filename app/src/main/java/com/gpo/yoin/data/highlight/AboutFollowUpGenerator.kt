package com.gpo.yoin.data.highlight

import com.gpo.yoin.data.local.SongAboutEntry
import java.util.Locale
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * On-device follow-up question generator for Now Playing's About -> Ask Gemini bar.
 *
 * Uses the shared [LyricHighlightModel] (Gemini Nano) only when Nano is available and the Labs switch is on.
 * Results are cached in an in-memory LRU map keyed by the visible About content fingerprint so reopening Ask
 * Gemini on the same song is instant; any unsupported device, disabled switch, or AICore refusal returns an
 * empty list silently so the static quick questions continue working with zero UI noise.
 */
class AboutFollowUpGenerator(
    private val model: LyricHighlightModel,
    private val status: StateFlow<NanoStatus?>,
    private val enabled: StateFlow<Boolean>,
    private val isForeground: () -> Boolean = { true },
    private val localeProvider: () -> Locale = Locale::getDefault,
) {
    private val cacheLock = Mutex()
    private val cache = object : LinkedHashMap<String, List<String>>(CACHE_SIZE, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, List<String>>?): Boolean =
            size > CACHE_SIZE
    }

    /** Returns the cached suggestions synchronously when already generated for this About snapshot. */
    fun peekCached(
        entries: List<SongAboutEntry>,
        asked: Collection<String>,
        localeTag: String = localeProvider().toLanguageTag(),
    ): List<String>? {
        val prepared = AboutFollowUpPrompt.prepare(entries, asked, localeTag) ?: return null
        return synchronized(cache) { cache[prepared.fingerprint] }
    }

    /**
     * Generates (or returns cached) 2–3 follow-up questions grounded in [entries].
     * Never throws; returns emptyList() when Nano is unavailable, disabled, busy, or yields unusable output.
     */
    suspend fun suggest(
        entries: List<SongAboutEntry>,
        asked: Collection<String>,
        localeTag: String = localeProvider().toLanguageTag(),
    ): List<String> {
        if (!enabled.value || status.value != NanoStatus.AVAILABLE || !isForeground()) {
            return emptyList()
        }
        val prepared = AboutFollowUpPrompt.prepare(entries, asked, localeTag) ?: return emptyList()
        synchronized(cache) { cache[prepared.fingerprint] }?.let { return it }

        return cacheLock.withLock {
            synchronized(cache) { cache[prepared.fingerprint] }?.let { return@withLock it }
            if (!enabled.value || status.value != NanoStatus.AVAILABLE || !isForeground()) {
                return@withLock emptyList()
            }
            val prompt = AboutFollowUpPrompt.buildPrompt(prepared)
            val answer = model.answer(
                prompt = prompt,
                maxOutputTokens = AboutFollowUpPrompt.MAX_OUTPUT_TOKENS,
                temperature = TEMPERATURE,
                topK = TOP_K,
            )
            when (answer) {
                is NanoAnswer.Text -> {
                    val parsed = AboutFollowUpPrompt.parse(answer.text, prepared.asked, prepared.localeTag)
                    if (parsed.isNotEmpty()) {
                        synchronized(cache) { cache[prepared.fingerprint] = parsed }
                    }
                    parsed
                }
                NanoAnswer.Busy,
                NanoAnswer.QuotaExhausted,
                NanoAnswer.NotForeground,
                is NanoAnswer.Failed -> emptyList()
            }
        }
    }

    companion object {
        private const val CACHE_SIZE = 32
        private const val TEMPERATURE = 0.45f
        private const val TOP_K = 24
    }
}
