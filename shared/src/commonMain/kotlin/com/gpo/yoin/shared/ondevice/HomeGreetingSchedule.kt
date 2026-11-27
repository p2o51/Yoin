package com.gpo.yoin.shared.ondevice

/**
 * When Home's on-device greetings are generated, kept and shown (pure; the state lives in each app's store:
 * Android `HomeGreetingStore` on SharedPreferences, iOS `HomeGreetingStore` on UserDefaults). Moved out of
 * Android's store on 2026-11-27 (parity p5) unchanged, so both apps decide the same:
 *
 * - three slots a day: morning 5–11, afternoon 11–18 (noon 11–13 shows no greeting that says "afternoon"),
 *   evening the rest;
 * - one generation a day per account and language, retried only when the listening cues changed; mid-day, new
 *   cues refresh only the slots still to come, at most [MAX_DAILY_REFRESHES] times, never in the evening;
 * - a 7-day phrase history per language so the model is told what not to repeat;
 * - the cues: the latest play (morning), the week's most-played other artist (afternoon), a rated / noted song
 *   not heard for 14+ days or else an album not played for 3+ days (evening).
 */
object HomeGreetingSchedule {

    const val MAX_DAILY_REFRESHES = 2

    /** How many recent plays the cues read (Android `getRecentHistory` limit). */
    const val RECENT_HISTORY_LIMIT = 60

    /** How many Rediscover songs the evening cue reads. */
    const val REDISCOVER_LIMIT = 8

    /** How many albums (newest last play first) the evening cue falls back to. */
    const val ALBUM_AGGREGATE_LIMIT = 15

    const val DAY_MS = 86_400_000L
    const val SEVEN_DAYS_MS = 7L * DAY_MS

    /** A Rediscover song: rated or noted, and not played for this long. */
    const val REDISCOVER_AGE_MS = 14L * DAY_MS
    const val OLDER_ALBUM_AGE_MS = 3L * DAY_MS
    const val HISTORY_DAYS = 7L
    const val MAX_HISTORY_ITEMS = 21

    /** One play the cues read: who, which album, when (epoch ms). */
    data class CuePlay(val artist: String, val album: String, val playedAt: Long)

    /** One album's last play (epoch ms; 0 when unknown). */
    data class CueAlbum(val artist: String, val album: String, val lastPlayedAt: Long)

    /** The slot shown at [hour] (0–23, local). */
    fun slotAtHour(hour: Int): HomeGreetingSlot = when (hour) {
        in 5 until 11 -> HomeGreetingSlot.MORNING
        in 11 until 18 -> HomeGreetingSlot.AFTERNOON
        else -> HomeGreetingSlot.EVENING
    }

    /** 11–13: Home says noon, so an afternoon greeting that says "afternoon" waits ([cachedText]). */
    fun isNoonHour(hour: Int): Boolean = hour in 11 until 13

    /** Local calendar day number of [epochMs] at [utcOffsetMs] (Android `NanoBudget.dayOf`). */
    fun dayOf(epochMs: Long, utcOffsetMs: Long): Long = (epochMs + utcOffsetMs).floorDiv(DAY_MS)

    /** What the store keeps as "tried today with these cues". */
    fun attemptValue(day: Long, lang: String, cuesFp: String): String = "$day|$lang|$cuesFp"

    /** The greeting [cached] (the account's row) holds for [slot] today in [lang], or null for the static one. */
    fun cachedText(
        cached: DailyHomeGreetings?,
        slot: HomeGreetingSlot,
        today: Long,
        lang: String,
        isNoon: Boolean,
    ): String? {
        cached ?: return null
        if (cached.day != today || cached.localeTag != lang) return null
        val text = cached.textFor(slot) ?: return null
        if (isNoon && HomeGreetingPrompt.conflictsWithNoon(text)) return null
        return text
    }

    /**
     * Whether a visit should look at the greetings at all (before reading the cues): nothing for today yet and
     * not tried today, or an upcoming slot may still be refreshed. [lastAttempt] is the stored attempt value.
     */
    fun needsGeneration(
        cached: DailyHomeGreetings?,
        today: Long,
        lang: String,
        currentSlot: HomeGreetingSlot,
        lastAttempt: String?,
    ): Boolean {
        val hasTodayCache = cached != null && cached.day == today && cached.localeTag == lang && cached.hasAny()
        if (!hasTodayCache) {
            return lastAttempt != attemptValue(today, lang, "")
        }
        return currentSlot != HomeGreetingSlot.EVENING && cached!!.refreshCount < MAX_DAILY_REFRESHES
    }

    /** With the cues read ([cuesFp]): whether the model should be asked now. */
    fun shouldGenerate(
        cached: DailyHomeGreetings?,
        today: Long,
        lang: String,
        currentSlot: HomeGreetingSlot,
        cuesFp: String,
        lastAttempt: String?,
    ): Boolean {
        val existing = todays(cached, today, lang)
        val attemptVal = attemptValue(today, lang, cuesFp)
        if (existing != null) {
            return currentSlot != HomeGreetingSlot.EVENING &&
                existing.refreshCount < MAX_DAILY_REFRESHES &&
                existing.cuesFingerprint != cuesFp &&
                lastAttempt != attemptVal
        }
        return lastAttempt != attemptVal
    }

    /** [cached] when it is today's in [lang] and holds a greeting; what a refresh merges into. */
    fun todays(cached: DailyHomeGreetings?, today: Long, lang: String): DailyHomeGreetings? =
        cached?.takeIf { it.day == today && it.localeTag == lang && it.hasAny() }

    /**
     * What to store after a generation: [fresh] as it is the first time today, else [existing] with only the
     * slots after [currentSlot] taken from [fresh] (the one on screen never changes under the listener).
     */
    fun merged(
        existing: DailyHomeGreetings?,
        fresh: DailyHomeGreetings,
        currentSlot: HomeGreetingSlot,
        cuesFp: String,
    ): DailyHomeGreetings = if (existing != null) {
        mergeUpcomingSlots(existing, fresh, currentSlot, cuesFp)
    } else {
        fresh.copy(cuesFingerprint = cuesFp, refreshCount = 0)
    }

    fun mergeUpcomingSlots(
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

    /** The phrases of the last 7 days (before [today]) in the stored history [raw] ("day\tphrase" lines). */
    fun recentHistory(raw: String, today: Long): List<String> {
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

    /** [raw] with today's [phrases] added, older than 7 days dropped; null when there is nothing to write. */
    fun recordHistory(raw: String, today: Long, phrases: List<String>): String? {
        if (phrases.isEmpty()) return null
        val existing = raw
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
        return existing
            .distinctBy { (_, p) -> HomeGreetingPrompt.normalizePhrase(p) }
            .takeLast(MAX_HISTORY_ITEMS)
            .joinToString("\n") { (d, p) -> "$d\t$p" }
    }

    /**
     * The three cues from local history: [recent] newest first (up to [RECENT_HISTORY_LIMIT]), [rediscover] the
     * rated / noted songs unplayed for [REDISCOVER_AGE_MS] (best rated first, then longest unplayed), [albums]
     * newest last play first. [albums] is read only when no Rediscover song gives an evening cue, so a caller
     * may pass the query lazily ([pickCuesNeedsAlbums]).
     */
    fun pickCues(
        recent: List<CuePlay>,
        rediscover: List<HomeGreetingPrompt.RecentPlay>,
        albums: List<CueAlbum>,
        now: Long,
    ): HomeGreetingPrompt.SlotCues {
        val morning = recent.firstNotNullOfOrNull { row ->
            HomeGreetingPrompt.RecentPlay(row.artist, row.album).clean()
        }
        val usedArtists = HashSet<String>()
        morning?.let { usedArtists += it.artist.lowercase() }

        // Afternoon cue: most-played artist/album over the last 7 days, distinct from morning.
        val weekCutoff = now - SEVEN_DAYS_MS
        val afternoon = recent.filter { it.playedAt >= weekCutoff }
            .mapNotNull { row -> HomeGreetingPrompt.RecentPlay(row.artist, row.album).clean() }
            .filter { it.artist.lowercase() !in usedArtists }
            .groupBy { it.artist.lowercase() }
            .values
            .maxByOrNull { it.size }
            ?.firstOrNull()
            ?: recent
                .mapNotNull { row -> HomeGreetingPrompt.RecentPlay(row.artist, row.album).clean() }
                .firstOrNull { it.artist.lowercase() !in usedArtists }
        afternoon?.let { usedArtists += it.artist.lowercase() }

        // Evening cue: a rated/noted Rediscover track unplayed for 14+ days, or an older played album.
        val evening = rediscoverCue(rediscover, usedArtists) ?: albums
            .filter { it.lastPlayedAt <= now - OLDER_ALBUM_AGE_MS }
            .mapNotNull { agg -> HomeGreetingPrompt.RecentPlay(agg.artist, agg.album).clean() }
            .firstOrNull { it.artist.lowercase() !in usedArtists }

        return HomeGreetingPrompt.SlotCues(morning = morning, afternoon = afternoon, evening = evening)
    }

    /** True when [pickCues] would read its albums (no Rediscover song gives the evening cue). */
    fun pickCuesNeedsAlbums(recent: List<CuePlay>, rediscover: List<HomeGreetingPrompt.RecentPlay>, now: Long): Boolean =
        pickCues(recent, rediscover, emptyList(), now).evening == null

    private fun rediscoverCue(
        rediscover: List<HomeGreetingPrompt.RecentPlay>,
        usedArtists: Set<String>,
    ): HomeGreetingPrompt.RecentPlay? = rediscover
        .mapNotNull(HomeGreetingPrompt.RecentPlay::clean)
        .firstOrNull { it.artist.lowercase() !in usedArtists }
}
