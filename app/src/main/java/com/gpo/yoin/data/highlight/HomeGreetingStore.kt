package com.gpo.yoin.data.highlight

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import com.gpo.yoin.data.local.PlayHistoryDao
import java.time.Instant
import java.time.ZoneId
import java.util.Locale
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Persists today's pre-generated 3-slot Home greetings (morning / afternoon / evening) so Home's title renders
 * immediately on cold start with zero Nano load wait.
 *
 * Generation runs asynchronously during a foreground visit (driven by [LyricHighlightProcessor]) once per
 * local day, language, and account. Any missing or unready slot falls back cleanly to static string resources.
 */
class HomeGreetingStore(
    context: Context,
    private val clock: () -> Long = System::currentTimeMillis,
    private val zoneId: () -> ZoneId = ZoneId::systemDefault,
    private val localeProvider: () -> Locale = Locale::getDefault,
) {
    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private val _greetings = MutableStateFlow(loadCached())

    /** The latest cached 3-slot greeting set; read synchronously at startup, updated after background generation. */
    val greetings: StateFlow<DailyHomeGreetings?> = _greetings.asStateFlow()

    enum class GenerationOutcome { GENERATED, ALREADY_READY, UNUSABLE, BUSY, QUOTA_EXHAUSTED, NOT_FOREGROUND, FAILED }

    /** Returns today's cached greeting for [slot] if it matches today, current language, and [profileId]. */
    fun cachedFor(slot: HomeGreetingSlot, profileId: String?): String? {
        val current = _greetings.value ?: return null
        val now = clock()
        val today = dayOf(now)
        val lang = HomeGreetingPrompt.normalizeLocale(localeProvider().toLanguageTag())
        val expectedProfile = profileId.orEmpty()
        if (current.day != today || current.localeTag != lang) return null
        if (expectedProfile.isNotEmpty() && current.profileId.isNotEmpty() && current.profileId != expectedProfile) {
            return null
        }
        return current.textFor(slot)
    }

    /** True when today's 3-slot set has neither been cached nor already attempted for this day/locale/account. */
    fun needsGeneration(profileId: String): Boolean {
        val now = clock()
        val key = attemptKey(dayOf(now), HomeGreetingPrompt.normalizeLocale(localeProvider().toLanguageTag()), profileId)
        val cached = _greetings.value
        if (cached != null && attemptKey(cached.day, cached.localeTag, cached.profileId) == key && cached.hasAny()) {
            return false
        }
        return prefs.getString(KEY_ATTEMPTED, null) != key
    }

    /**
     * Generates all 3 daily greetings in one Nano inference call using only local listening recents (no network).
     * Marks the attempt key on usable or unusable replies so a failed day doesn't spin AICore repeatedly.
     */
    suspend fun generateIfNeeded(
        profileId: String,
        provider: String,
        playHistoryDao: PlayHistoryDao?,
        model: LyricHighlightModel,
    ): GenerationOutcome {
        val now = clock()
        val today = dayOf(now)
        val lang = HomeGreetingPrompt.normalizeLocale(localeProvider().toLanguageTag())
        val key = attemptKey(today, lang, profileId)
        val cached = _greetings.value
        if (cached != null && attemptKey(cached.day, cached.localeTag, cached.profileId) == key && cached.hasAny()) {
            return GenerationOutcome.ALREADY_READY
        }
        if (prefs.getString(KEY_ATTEMPTED, null) == key) {
            return GenerationOutcome.ALREADY_READY
        }

        val recentArtists = withTimeoutOrNull(HISTORY_TIMEOUT_MS) {
            runCatching {
                playHistoryDao?.getRecentHistory(profileId, provider, RECENT_HISTORY_LIMIT)
                    ?.first()
                    .orEmpty()
                    .map { it.artist.trim() }
                    .filter(String::isNotEmpty)
                    .distinct()
                    .take(2)
            }.getOrNull()
        }.orEmpty()

        val dayOfWeek = Instant.ofEpochMilli(now).atZone(zoneId()).dayOfWeek
        val prompt = HomeGreetingPrompt.buildPrompt(
            recentArtists = recentArtists,
            dayOfWeek = dayOfWeek,
            localeTag = lang,
        )

        return when (
            val answer = model.answer(
                prompt = prompt,
                maxOutputTokens = HomeGreetingPrompt.MAX_OUTPUT_TOKENS,
                temperature = TEMPERATURE,
                topK = TOP_K,
            )
        ) {
            is NanoAnswer.Text -> {
                val parsed = HomeGreetingPrompt.parse(answer.text, today, lang, profileId)
                prefs.edit { putString(KEY_ATTEMPTED, key) }
                if (parsed != null) {
                    saveCached(parsed)
                    GenerationOutcome.GENERATED
                } else {
                    GenerationOutcome.UNUSABLE
                }
            }
            NanoAnswer.Busy -> GenerationOutcome.BUSY
            NanoAnswer.QuotaExhausted -> GenerationOutcome.QUOTA_EXHAUSTED
            NanoAnswer.NotForeground -> GenerationOutcome.NOT_FOREGROUND
            is NanoAnswer.Failed -> GenerationOutcome.FAILED
        }
    }

    /** Clears cached greetings (e.g., if a user clears data or wants a fresh set in tests). */
    fun clear() {
        prefs.edit { clear() }
        _greetings.value = null
    }

    private fun saveCached(greetings: DailyHomeGreetings) {
        prefs.edit {
            putLong(KEY_DAY, greetings.day)
            putString(KEY_LOCALE, greetings.localeTag)
            putString(KEY_PROFILE, greetings.profileId)
            putString(KEY_MORNING, greetings.morning)
            putString(KEY_AFTERNOON, greetings.afternoon)
            putString(KEY_EVENING, greetings.evening)
        }
        _greetings.value = greetings
    }

    private fun loadCached(): DailyHomeGreetings? {
        val day = prefs.getLong(KEY_DAY, -1L)
        if (day < 0L) return null
        val locale = prefs.getString(KEY_LOCALE, null) ?: return null
        val profile = prefs.getString(KEY_PROFILE, "").orEmpty()
        val morning = prefs.getString(KEY_MORNING, null)?.takeIf(String::isNotBlank)
        val afternoon = prefs.getString(KEY_AFTERNOON, null)?.takeIf(String::isNotBlank)
        val evening = prefs.getString(KEY_EVENING, null)?.takeIf(String::isNotBlank)
        val loaded = DailyHomeGreetings(day, locale, profile, morning, afternoon, evening)
        return loaded.takeIf { it.hasAny() }
    }

    private fun dayOf(now: Long): Long {
        val offsetMs = zoneId().rules.getOffset(Instant.ofEpochMilli(now)).totalSeconds * 1_000
        return NanoBudget.dayOf(now, offsetMs)
    }

    private fun attemptKey(day: Long, lang: String, profileId: String): String = "$day|$lang|$profileId"

    companion object {
        private const val PREFS = "on_device_home_greetings"
        private const val KEY_DAY = "day"
        private const val KEY_LOCALE = "locale"
        private const val KEY_PROFILE = "profile"
        private const val KEY_MORNING = "morning"
        private const val KEY_AFTERNOON = "afternoon"
        private const val KEY_EVENING = "evening"
        private const val KEY_ATTEMPTED = "attempted_key"

        private const val RECENT_HISTORY_LIMIT = 12
        private const val HISTORY_TIMEOUT_MS = 2_000L
        private const val TEMPERATURE = 0.65f
        private const val TOP_K = 32
    }
}
