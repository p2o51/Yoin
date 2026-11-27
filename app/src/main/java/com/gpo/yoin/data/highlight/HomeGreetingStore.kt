package com.gpo.yoin.data.highlight

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import com.gpo.yoin.data.local.PlayHistoryDao
import com.gpo.yoin.shared.ondevice.HomeGreetingPrompt
import com.gpo.yoin.shared.ondevice.HomeGreetingSchedule
import java.time.Instant
import java.time.ZoneId
import java.util.Locale
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Persists today's pre-generated 3-slot Home greetings (morning / afternoon / evening) per account profile so
 * Home's title renders immediately on cold start with zero Nano load wait.
 *
 * Improvements from Fold 8 hardware tuning & review (2026-11-22):
 * - **Per-profile cache buckets**: switching between Apple Music / Spotify / Subsonic never overwrites another
 *   account's cached greetings.
 * - **Three distinct local cues**: morning uses the latest play, afternoon uses the week's top artist/album,
 *   and evening uses a rediscover or older favorite.
 * - **Upcoming-slot refresh**: when today's listening cues change mid-day (e.g. a new album played this morning),
 *   background generation refreshes ONLY upcoming slots (never flickering the currently displayed slot), capped
 *   at [MAX_DAILY_REFRESHES] per day.
 * - **7-day rolling phrase dedup**: tracks phrases shown over the last 7 days per language to keep copy fresh.
 *
 * The decisions (slots, when to generate, the merge, the history, the cue picks) are :shared's
 * [HomeGreetingSchedule] since parity p5 (2026-11-27), so iOS's store decides the same; this class keeps the
 * SharedPreferences and Room reads.
 */
class HomeGreetingStore(
    context: Context,
    private val clock: () -> Long = System::currentTimeMillis,
    private val zoneId: () -> ZoneId = ZoneId::systemDefault,
    private val localeProvider: () -> Locale = Locale::getDefault,
) {
    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private val _greetings = MutableStateFlow(loadAllCached())

    /** Cached daily greetings keyed by profileId; read synchronously at startup, updated after generation. */
    val greetings: StateFlow<Map<String, DailyHomeGreetings>> = _greetings.asStateFlow()

    enum class GenerationOutcome { GENERATED, ALREADY_READY, UNUSABLE, BUSY, QUOTA_EXHAUSTED, NOT_FOREGROUND, FAILED }

    /**
     * Returns today's cached greeting for [slot] and [profileId], or null when absent/stale/mismatched.
     * When [isNoon] is true (11:00–13:00), drops any middle-slot greeting containing "下午"/"afternoon" so Home
     * falls back cleanly to static "中午好".
     */
    fun cachedFor(
        slot: HomeGreetingSlot,
        profileId: String?,
        isNoon: Boolean = false,
    ): String? {
        val expectedProfile = profileId.orEmpty()
        val current = _greetings.value[expectedProfile] ?: return null
        val now = clock()
        val lang = HomeGreetingPrompt.normalizeLocale(localeProvider().toLanguageTag())
        return HomeGreetingSchedule.cachedText(current, slot, dayOf(now), lang, isNoon)
    }

    /**
     * True when [profileId] either needs initial generation today or may be eligible for an upcoming-slot refresh
     * (checked against actual listening cues inside [generateIfNeeded] before calling Nano).
     */
    fun needsGeneration(profileId: String): Boolean {
        val now = clock()
        val today = dayOf(now)
        val lang = HomeGreetingPrompt.normalizeLocale(localeProvider().toLanguageTag())
        return HomeGreetingSchedule.needsGeneration(
            cached = _greetings.value[profileId],
            today = today,
            lang = lang,
            currentSlot = currentSlotAt(now),
            lastAttempt = prefs.getString(attemptedKey(profileId), null),
        )
    }

    /**
     * Generates (or refreshes upcoming slots of) today's 3 daily greetings using only local listening history.
     * Returns [GenerationOutcome.ALREADY_READY] with zero AICore calls if today's greetings are already up to date.
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
        val currentSlot = currentSlotAt(now)
        val cached = _greetings.value[profileId]
        val existing = HomeGreetingSchedule.todays(cached, today, lang)

        val cues = loadSlotCues(profileId, provider, playHistoryDao, now)
        val cuesFp = cues.fingerprint()
        val attemptVal = HomeGreetingSchedule.attemptValue(today, lang, cuesFp)

        val generate = HomeGreetingSchedule.shouldGenerate(
            cached = cached,
            today = today,
            lang = lang,
            currentSlot = currentSlot,
            cuesFp = cuesFp,
            lastAttempt = prefs.getString(attemptedKey(profileId), null),
        )
        if (!generate) return GenerationOutcome.ALREADY_READY

        val avoidRecent = loadRecentHistory(lang, today)
        val zoned = Instant.ofEpochMilli(now).atZone(zoneId())
        val dayOfWeek = zoned.dayOfWeek
        val month = zoned.month

        val typed = model.answerTyped(
            schema = HomeGreetingSchema::class,
            userPrompt = HomeGreetingPrompt.userPrompt(
                cues = cues,
                dayOfWeek = dayOfWeek,
                month = month,
                localeTag = lang,
                avoidRecent = avoidRecent,
            ),
            systemInstruction = HomeGreetingPrompt.systemInstruction(
                hasAnyCue = cues.hasAny(),
                localeTag = lang,
            ),
            maxOutputTokens = HomeGreetingPrompt.MAX_OUTPUT_TOKENS,
            temperature = TEMPERATURE,
            topK = TOP_K,
        )

        val candidate: DailyHomeGreetings? = when (typed) {
            is NanoTypedAnswer.Value -> HomeGreetingPrompt.validateSlots(
                morning = typed.value.morning,
                afternoon = typed.value.afternoon,
                evening = typed.value.evening,
                day = today,
                localeTag = lang,
                profileId = profileId,
                cues = cues,
                avoidRecent = avoidRecent,
            )
            NanoTypedAnswer.Unsupported -> {
                val prompt = HomeGreetingPrompt.buildPrompt(
                    cues = cues,
                    dayOfWeek = dayOfWeek,
                    month = month,
                    localeTag = lang,
                    avoidRecent = avoidRecent,
                )
                when (
                    val answer = model.answer(
                        prompt = prompt,
                        maxOutputTokens = HomeGreetingPrompt.MAX_OUTPUT_TOKENS,
                        temperature = TEMPERATURE,
                        topK = TOP_K,
                    )
                ) {
                    is NanoAnswer.Text -> HomeGreetingPrompt.parse(
                        answer = answer.text,
                        day = today,
                        localeTag = lang,
                        profileId = profileId,
                        cues = cues,
                        avoidRecent = avoidRecent,
                    )
                    NanoAnswer.Busy -> return GenerationOutcome.BUSY
                    NanoAnswer.QuotaExhausted -> return GenerationOutcome.QUOTA_EXHAUSTED
                    NanoAnswer.NotForeground -> return GenerationOutcome.NOT_FOREGROUND
                    is NanoAnswer.Failed -> return GenerationOutcome.FAILED
                }
            }
            NanoTypedAnswer.Busy -> return GenerationOutcome.BUSY
            NanoTypedAnswer.QuotaExhausted -> return GenerationOutcome.QUOTA_EXHAUSTED
            NanoTypedAnswer.NotForeground -> return GenerationOutcome.NOT_FOREGROUND
            is NanoTypedAnswer.Failed -> return GenerationOutcome.FAILED
        }

        prefs.edit { putString(attemptedKey(profileId), attemptVal) }
        if (candidate == null) return GenerationOutcome.UNUSABLE

        // Preserve past and currently displayed slots so Home never flickers; update upcoming slots only.
        val finalGreetings = HomeGreetingSchedule.merged(existing, candidate, currentSlot, cuesFp)
        saveProfileGreetings(finalGreetings)
        recordRecentHistory(
            lang = lang,
            today = today,
            phrases = listOfNotNull(finalGreetings.morning, finalGreetings.afternoon, finalGreetings.evening),
        )
        return GenerationOutcome.GENERATED
    }

    /** Clears all cached greetings and 7-day dedup history (used in tests or data reset). */
    fun clear() {
        prefs.edit { clear() }
        _greetings.value = emptyMap()
    }

    private suspend fun loadSlotCues(
        profileId: String,
        provider: String,
        playHistoryDao: PlayHistoryDao?,
        now: Long,
    ): HomeGreetingPrompt.SlotCues {
        if (playHistoryDao == null) return HomeGreetingPrompt.SlotCues()
        return withTimeoutOrNull(HISTORY_TIMEOUT_MS) {
            runCatching {
                val recent = playHistoryDao.getRecentHistory(profileId, provider, RECENT_HISTORY_LIMIT).first()
                    .map { row -> HomeGreetingSchedule.CuePlay(row.artist, row.album, row.playedAt) }
                // Evening cue: a rated/noted Rediscover track unplayed for 14+ days, or an older played album.
                val rediscoverCutoff = now - REDISCOVER_AGE_MS
                val rediscover = runCatching {
                    playHistoryDao.getRediscoverSongs(profileId, provider, rediscoverCutoff, REDISCOVER_LIMIT)
                }.getOrDefault(emptyList())
                    .map { song -> HomeGreetingPrompt.RecentPlay(song.artist, song.album) }
                // The album aggregates are read only when no Rediscover song gives the evening cue.
                val albums = if (HomeGreetingSchedule.pickCuesNeedsAlbums(recent, rediscover, now)) {
                    runCatching {
                        playHistoryDao.getAlbumAggregates(profileId, provider, ALBUM_AGGREGATE_LIMIT)
                    }.getOrDefault(emptyList())
                        .map { agg -> HomeGreetingSchedule.CueAlbum(agg.artistName, agg.albumName, agg.lastPlayedAt ?: 0L) }
                } else {
                    emptyList()
                }
                HomeGreetingSchedule.pickCues(recent, rediscover, albums, now)
            }.getOrNull()
        } ?: HomeGreetingPrompt.SlotCues()
    }

    private fun saveProfileGreetings(greetings: DailyHomeGreetings) {
        val id = greetings.profileId
        val prefix = profilePrefix(id)
        val knownProfiles = prefs.getStringSet(KEY_PROFILES, emptySet()).orEmpty().toMutableSet()
        knownProfiles += id
        prefs.edit {
            putStringSet(KEY_PROFILES, knownProfiles)
            putLong(prefix + SUFFIX_DAY, greetings.day)
            putString(prefix + SUFFIX_LOCALE, greetings.localeTag)
            putString(prefix + SUFFIX_MORNING, greetings.morning)
            putString(prefix + SUFFIX_AFTERNOON, greetings.afternoon)
            putString(prefix + SUFFIX_EVENING, greetings.evening)
            putString(prefix + SUFFIX_CUES_FP, greetings.cuesFingerprint)
            putInt(prefix + SUFFIX_REFRESH_COUNT, greetings.refreshCount)
        }
        _greetings.value = _greetings.value + (id to greetings)
    }

    private fun loadAllCached(): Map<String, DailyHomeGreetings> {
        val out = LinkedHashMap<String, DailyHomeGreetings>()
        val profiles = prefs.getStringSet(KEY_PROFILES, emptySet()).orEmpty()
        for (id in profiles) {
            val prefix = profilePrefix(id)
            val day = prefs.getLong(prefix + SUFFIX_DAY, -1L)
            if (day < 0L) continue
            val locale = prefs.getString(prefix + SUFFIX_LOCALE, null) ?: continue
            val morning = prefs.getString(prefix + SUFFIX_MORNING, null)?.takeIf(String::isNotBlank)
            val afternoon = prefs.getString(prefix + SUFFIX_AFTERNOON, null)?.takeIf(String::isNotBlank)
            val evening = prefs.getString(prefix + SUFFIX_EVENING, null)?.takeIf(String::isNotBlank)
            val cuesFp = prefs.getString(prefix + SUFFIX_CUES_FP, "").orEmpty()
            val refreshCount = prefs.getInt(prefix + SUFFIX_REFRESH_COUNT, 0)
            val loaded = DailyHomeGreetings(
                day = day,
                localeTag = locale,
                profileId = id,
                morning = morning,
                afternoon = afternoon,
                evening = evening,
                cuesFingerprint = cuesFp,
                refreshCount = refreshCount,
            )
            if (loaded.hasAny()) out[id] = loaded
        }
        return out
    }

    private fun loadRecentHistory(lang: String, today: Long): List<String> =
        HomeGreetingSchedule.recentHistory(prefs.getString(historyKey(lang), null).orEmpty(), today)

    private fun recordRecentHistory(lang: String, today: Long, phrases: List<String>) {
        val compacted = HomeGreetingSchedule.recordHistory(
            raw = prefs.getString(historyKey(lang), null).orEmpty(),
            today = today,
            phrases = phrases,
        ) ?: return
        prefs.edit { putString(historyKey(lang), compacted) }
    }

    private fun currentSlotAt(now: Long): HomeGreetingSlot {
        return HomeGreetingSchedule.slotAtHour(Instant.ofEpochMilli(now).atZone(zoneId()).hour)
    }

    private fun dayOf(now: Long): Long {
        val offsetMs = zoneId().rules.getOffset(Instant.ofEpochMilli(now)).totalSeconds * 1_000
        return NanoBudget.dayOf(now, offsetMs)
    }

    private fun profilePrefix(profileId: String): String = "p_${profileId.hashCode().toUInt().toString(16)}_"
    private fun attemptedKey(profileId: String): String = profilePrefix(profileId) + SUFFIX_ATTEMPTED
    private fun historyKey(lang: String): String = "recent_history_$lang"

    companion object {
        private const val PREFS = "on_device_home_greetings"
        private const val KEY_PROFILES = "cached_profiles"
        private const val SUFFIX_DAY = "day"
        private const val SUFFIX_LOCALE = "locale"
        private const val SUFFIX_MORNING = "morning"
        private const val SUFFIX_AFTERNOON = "afternoon"
        private const val SUFFIX_EVENING = "evening"
        private const val SUFFIX_CUES_FP = "cues_fp"
        private const val SUFFIX_REFRESH_COUNT = "refresh_count"
        private const val SUFFIX_ATTEMPTED = "attempted"

        private const val RECENT_HISTORY_LIMIT = HomeGreetingSchedule.RECENT_HISTORY_LIMIT
        private const val REDISCOVER_LIMIT = HomeGreetingSchedule.REDISCOVER_LIMIT
        private const val ALBUM_AGGREGATE_LIMIT = HomeGreetingSchedule.ALBUM_AGGREGATE_LIMIT
        private const val HISTORY_TIMEOUT_MS = 2_000L
        private const val REDISCOVER_AGE_MS = HomeGreetingSchedule.REDISCOVER_AGE_MS
        private const val TEMPERATURE = HomeGreetingPrompt.TEMPERATURE
        private const val TOP_K = HomeGreetingPrompt.TOP_K
    }
}
