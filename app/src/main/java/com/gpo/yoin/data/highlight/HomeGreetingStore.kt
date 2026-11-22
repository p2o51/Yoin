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
        val today = dayOf(now)
        val lang = HomeGreetingPrompt.normalizeLocale(localeProvider().toLanguageTag())
        if (current.day != today || current.localeTag != lang) return null
        val text = current.textFor(slot) ?: return null
        if (isNoon && HomeGreetingPrompt.conflictsWithNoon(text)) return null
        return text
    }

    /**
     * True when [profileId] either needs initial generation today or may be eligible for an upcoming-slot refresh
     * (checked against actual listening cues inside [generateIfNeeded] before calling Nano).
     */
    fun needsGeneration(profileId: String): Boolean {
        val now = clock()
        val today = dayOf(now)
        val lang = HomeGreetingPrompt.normalizeLocale(localeProvider().toLanguageTag())
        val cached = _greetings.value[profileId]
        val hasTodayCache = cached != null && cached.day == today && cached.localeTag == lang && cached.hasAny()
        if (!hasTodayCache) {
            return prefs.getString(attemptedKey(profileId), null) != attemptValue(today, lang, "")
        }
        val currentSlot = currentSlotAt(now)
        return currentSlot != HomeGreetingSlot.EVENING && cached!!.refreshCount < MAX_DAILY_REFRESHES
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
        val existing = _greetings.value[profileId]
            ?.takeIf { it.day == today && it.localeTag == lang && it.hasAny() }

        val cues = loadSlotCues(profileId, provider, playHistoryDao, now)
        val cuesFp = cues.fingerprint()
        val attemptVal = attemptValue(today, lang, cuesFp)

        if (existing != null) {
            val canRefreshUpcoming = currentSlot != HomeGreetingSlot.EVENING &&
                existing.refreshCount < MAX_DAILY_REFRESHES &&
                existing.cuesFingerprint != cuesFp &&
                prefs.getString(attemptedKey(profileId), null) != attemptVal
            if (!canRefreshUpcoming) return GenerationOutcome.ALREADY_READY
        } else if (prefs.getString(attemptedKey(profileId), null) == attemptVal) {
            return GenerationOutcome.ALREADY_READY
        }

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

        val finalGreetings = if (existing != null) {
            // Preserve past and currently displayed slots so Home never flickers; update upcoming slots only.
            mergeUpcomingSlots(existing, candidate, currentSlot, cuesFp)
        } else {
            candidate.copy(cuesFingerprint = cuesFp, refreshCount = 0)
        }
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

    private fun mergeUpcomingSlots(
        existing: DailyHomeGreetings,
        fresh: DailyHomeGreetings,
        currentSlot: HomeGreetingSlot,
        cuesFp: String,
    ): DailyHomeGreetings = when (currentSlot) {
        HomeGreetingSlot.MORNING -> existing.copy(
            afternoon = fresh.afternoon ?: existing.afternoon,
            evening = fresh.evening ?: existing.evening,
            cuesFingerprint = cuesFp,
            refreshCount = existing.refreshCount + 1,
        )
        HomeGreetingSlot.AFTERNOON -> existing.copy(
            evening = fresh.evening ?: existing.evening,
            cuesFingerprint = cuesFp,
            refreshCount = existing.refreshCount + 1,
        )
        HomeGreetingSlot.EVENING -> existing.copy(
            cuesFingerprint = cuesFp,
            refreshCount = existing.refreshCount + 1,
        )
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
                val recentRows = playHistoryDao.getRecentHistory(profileId, provider, RECENT_HISTORY_LIMIT).first()
                val morning = recentRows.firstNotNullOfOrNull { row ->
                    HomeGreetingPrompt.RecentPlay(row.artist, row.album).clean()
                }
                val usedArtists = HashSet<String>()
                morning?.let { usedArtists += it.artist.lowercase() }

                // Afternoon cue: most-played artist/album over the last 7 days, distinct from morning.
                val weekCutoff = now - SEVEN_DAYS_MS
                val weeklyRows = recentRows.filter { it.playedAt >= weekCutoff }
                val afternoon = weeklyRows
                    .mapNotNull { row -> HomeGreetingPrompt.RecentPlay(row.artist, row.album).clean() }
                    .filter { it.artist.lowercase() !in usedArtists }
                    .groupBy { it.artist.lowercase() }
                    .values
                    .maxByOrNull { it.size }
                    ?.firstOrNull()
                    ?: recentRows
                        .mapNotNull { row -> HomeGreetingPrompt.RecentPlay(row.artist, row.album).clean() }
                        .firstOrNull { it.artist.lowercase() !in usedArtists }
                afternoon?.let { usedArtists += it.artist.lowercase() }

                // Evening cue: a rated/noted Rediscover track unplayed for 14+ days, or an older played album.
                val rediscoverCutoff = now - REDISCOVER_AGE_MS
                val rediscover = runCatching {
                    playHistoryDao.getRediscoverSongs(profileId, provider, rediscoverCutoff, REDISCOVER_LIMIT)
                }.getOrDefault(emptyList())
                    .mapNotNull { song -> HomeGreetingPrompt.RecentPlay(song.artist, song.album).clean() }
                    .firstOrNull { it.artist.lowercase() !in usedArtists }

                val evening = rediscover ?: runCatching {
                    playHistoryDao.getAlbumAggregates(profileId, provider, ALBUM_AGGREGATE_LIMIT)
                }.getOrDefault(emptyList())
                    .filter { (it.lastPlayedAt ?: 0L) <= now - OLDER_ALBUM_AGE_MS }
                    .mapNotNull { agg -> HomeGreetingPrompt.RecentPlay(agg.artistName, agg.albumName).clean() }
                    .firstOrNull { it.artist.lowercase() !in usedArtists }

                HomeGreetingPrompt.SlotCues(morning = morning, afternoon = afternoon, evening = evening)
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

    private fun loadRecentHistory(lang: String, today: Long): List<String> {
        val raw = prefs.getString(historyKey(lang), null).orEmpty()
        if (raw.isEmpty()) return emptyList()
        return raw.lineSequence()
            .mapNotNull { line ->
                val sep = line.indexOf('\t')
                if (sep <= 0) return@mapNotNull null
                val day = line.substring(0, sep).toLongOrNull() ?: return@mapNotNull null
                val phrase = line.substring(sep + 1).trim()
                if (phrase.isEmpty() || today - day >= HISTORY_DAYS || day >= today) null else phrase
            }
            .distinct()
            .toList()
    }

    private fun recordRecentHistory(lang: String, today: Long, phrases: List<String>) {
        if (phrases.isEmpty()) return
        val existing = prefs.getString(historyKey(lang), null).orEmpty()
            .lineSequence()
            .mapNotNull { line ->
                val sep = line.indexOf('\t')
                if (sep <= 0) return@mapNotNull null
                val day = line.substring(0, sep).toLongOrNull() ?: return@mapNotNull null
                val phrase = line.substring(sep + 1).trim()
                if (phrase.isEmpty() || today - day >= HISTORY_DAYS) null else day to phrase
            }
            .toMutableList()
        for (phrase in phrases) {
            existing += today to phrase
        }
        val compacted = existing
            .distinctBy { (_, p) -> HomeGreetingPrompt.normalizePhrase(p) }
            .takeLast(MAX_HISTORY_ITEMS)
            .joinToString("\n") { (d, p) -> "$d\t$p" }
        prefs.edit { putString(historyKey(lang), compacted) }
    }

    private fun currentSlotAt(now: Long): HomeGreetingSlot {
        val hour = Instant.ofEpochMilli(now).atZone(zoneId()).hour
        return when (hour) {
            in 5 until 11 -> HomeGreetingSlot.MORNING
            in 11 until 18 -> HomeGreetingSlot.AFTERNOON
            else -> HomeGreetingSlot.EVENING
        }
    }

    private fun dayOf(now: Long): Long {
        val offsetMs = zoneId().rules.getOffset(Instant.ofEpochMilli(now)).totalSeconds * 1_000
        return NanoBudget.dayOf(now, offsetMs)
    }

    private fun profilePrefix(profileId: String): String = "p_${profileId.hashCode().toUInt().toString(16)}_"
    private fun attemptedKey(profileId: String): String = profilePrefix(profileId) + SUFFIX_ATTEMPTED
    private fun attemptValue(day: Long, lang: String, cuesFp: String): String = "$day|$lang|$cuesFp"
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

        private const val RECENT_HISTORY_LIMIT = 60
        private const val REDISCOVER_LIMIT = 8
        private const val ALBUM_AGGREGATE_LIMIT = 15
        private const val HISTORY_TIMEOUT_MS = 2_000L
        private const val SEVEN_DAYS_MS = 7L * 86_400_000L
        private const val REDISCOVER_AGE_MS = 14L * 86_400_000L
        private const val OLDER_ALBUM_AGE_MS = 3L * 86_400_000L
        private const val HISTORY_DAYS = 7L
        private const val MAX_HISTORY_ITEMS = 21
        private const val MAX_DAILY_REFRESHES = 2
        private const val TEMPERATURE = 0.55f
        private const val TOP_K = 24
    }
}
