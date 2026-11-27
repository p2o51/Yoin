package com.gpo.yoin.shared.ondevice

/*
 * The facade over the on-device AI words and rules (parity p5, 2026-11-27): Home's three daily greetings and
 * Now Playing About's suggested follow-up questions, as Android asks Gemini Nano and iOS asks Apple Intelligence
 * (FoundationModels). Android, and iOS through YoinShared, answer exactly as
 * shared/src/commonTest/resources/golden/ondevice-ai.json says.
 *
 * Swift calls it as `YoinOnDeviceAi.shared.…`. Plain types only and every argument passed (Swift sees no Kotlin
 * defaults): a day is ISO 1–7 (Monday = 1), a month 1–12 and 0 for none, times epoch ms. The apps keep the model
 * session, the cache's storage, the history reads and the switches.
 */
object YoinOnDeviceAi {

    // ── greetings ───────────────────────────────────────────────────────────────────────────────────────

    const val greetingMaxOutputTokens = HomeGreetingPrompt.MAX_OUTPUT_TOKENS
    const val greetingTemperature = HomeGreetingPrompt.TEMPERATURE
    const val greetingTopK = HomeGreetingPrompt.TOP_K
    const val maxDailyRefreshes = HomeGreetingSchedule.MAX_DAILY_REFRESHES
    const val recentHistoryLimit = HomeGreetingSchedule.RECENT_HISTORY_LIMIT
    const val rediscoverLimit = HomeGreetingSchedule.REDISCOVER_LIMIT
    const val albumAggregateLimit = HomeGreetingSchedule.ALBUM_AGGREGATE_LIMIT
    const val rediscoverAgeMs = HomeGreetingSchedule.REDISCOVER_AGE_MS

    /** "zh" or "en": the prompt language for an app language tag. */
    fun normalizeLocale(languageTag: String): String = HomeGreetingPrompt.normalizeLocale(languageTag)

    fun recentPlay(artist: String, album: String): HomeGreetingPrompt.RecentPlay =
        HomeGreetingPrompt.RecentPlay(artist, album)

    fun slotCues(
        morning: HomeGreetingPrompt.RecentPlay?,
        afternoon: HomeGreetingPrompt.RecentPlay?,
        evening: HomeGreetingPrompt.RecentPlay?,
    ): HomeGreetingPrompt.SlotCues = HomeGreetingPrompt.SlotCues(morning, afternoon, evening)

    /** The instructions for the structured (guided) call. */
    fun greetingInstructions(hasAnyCue: Boolean, localeTag: String): String =
        HomeGreetingPrompt.systemInstruction(hasAnyCue, localeTag)

    /** The input paired with [greetingInstructions]. */
    fun greetingUserPrompt(
        cues: HomeGreetingPrompt.SlotCues,
        dayOfWeek: Int,
        month: Int,
        localeTag: String,
        avoidRecent: List<String>,
    ): String = HomeGreetingPrompt.userPrompt(cues, dayOfWeek, month.takeIf { it in 1..12 }, localeTag, avoidRecent)

    /** The one-string prompt for a plain-text call (when the structured one can't be decoded). */
    fun greetingPlainPrompt(
        cues: HomeGreetingPrompt.SlotCues,
        dayOfWeek: Int,
        month: Int,
        localeTag: String,
        avoidRecent: List<String>,
    ): String = HomeGreetingPrompt.buildPrompt(cues, dayOfWeek, month.takeIf { it in 1..12 }, localeTag, avoidRecent)

    /** The structured answer's three fields checked; null when fewer than two survive. */
    fun validateGreetings(
        morning: String?,
        afternoon: String?,
        evening: String?,
        day: Long,
        localeTag: String,
        profileId: String,
        cues: HomeGreetingPrompt.SlotCues,
        avoidRecent: List<String>,
    ): DailyHomeGreetings? =
        HomeGreetingPrompt.validateSlots(morning, afternoon, evening, day, localeTag, profileId, cues, avoidRecent)

    /** A plain-text answer read and checked as [validateGreetings]. */
    fun parseGreetings(
        answer: String?,
        day: Long,
        localeTag: String,
        profileId: String,
        cues: HomeGreetingPrompt.SlotCues,
        avoidRecent: List<String>,
    ): DailyHomeGreetings? = HomeGreetingPrompt.parse(answer, day, localeTag, profileId, cues, avoidRecent)

    fun greetings(
        day: Long,
        localeTag: String,
        profileId: String,
        morning: String?,
        afternoon: String?,
        evening: String?,
        cuesFingerprint: String,
        refreshCount: Int,
    ): DailyHomeGreetings =
        DailyHomeGreetings(day, localeTag, profileId, morning, afternoon, evening, cuesFingerprint, refreshCount)

    fun conflictsWithNoon(text: String): Boolean = HomeGreetingPrompt.conflictsWithNoon(text)

    // ── greeting schedule ───────────────────────────────────────────────────────────────────────────────

    fun slotAtHour(hour: Int): HomeGreetingSlot = HomeGreetingSchedule.slotAtHour(hour)

    fun isNoonHour(hour: Int): Boolean = HomeGreetingSchedule.isNoonHour(hour)

    fun dayOf(epochMs: Long, utcOffsetMs: Long): Long = HomeGreetingSchedule.dayOf(epochMs, utcOffsetMs)

    fun attemptValue(day: Long, lang: String, cuesFingerprint: String): String =
        HomeGreetingSchedule.attemptValue(day, lang, cuesFingerprint)

    fun cachedText(
        cached: DailyHomeGreetings?,
        slot: HomeGreetingSlot,
        today: Long,
        lang: String,
        isNoon: Boolean,
    ): String? = HomeGreetingSchedule.cachedText(cached, slot, today, lang, isNoon)

    fun needsGeneration(
        cached: DailyHomeGreetings?,
        today: Long,
        lang: String,
        currentSlot: HomeGreetingSlot,
        lastAttempt: String?,
    ): Boolean = HomeGreetingSchedule.needsGeneration(cached, today, lang, currentSlot, lastAttempt)

    fun shouldGenerate(
        cached: DailyHomeGreetings?,
        today: Long,
        lang: String,
        currentSlot: HomeGreetingSlot,
        cuesFingerprint: String,
        lastAttempt: String?,
    ): Boolean = HomeGreetingSchedule.shouldGenerate(cached, today, lang, currentSlot, cuesFingerprint, lastAttempt)

    fun todays(cached: DailyHomeGreetings?, today: Long, lang: String): DailyHomeGreetings? =
        HomeGreetingSchedule.todays(cached, today, lang)

    fun merged(
        existing: DailyHomeGreetings?,
        fresh: DailyHomeGreetings,
        currentSlot: HomeGreetingSlot,
        cuesFingerprint: String,
    ): DailyHomeGreetings = HomeGreetingSchedule.merged(existing, fresh, currentSlot, cuesFingerprint)

    fun recentHistory(raw: String, today: Long): List<String> = HomeGreetingSchedule.recentHistory(raw, today)

    fun recordHistory(raw: String, today: Long, phrases: List<String>): String? =
        HomeGreetingSchedule.recordHistory(raw, today, phrases)

    fun cuePlay(artist: String, album: String, playedAt: Long): HomeGreetingSchedule.CuePlay =
        HomeGreetingSchedule.CuePlay(artist, album, playedAt)

    fun cueAlbum(artist: String, album: String, lastPlayedAt: Long): HomeGreetingSchedule.CueAlbum =
        HomeGreetingSchedule.CueAlbum(artist, album, lastPlayedAt)

    fun pickCues(
        recent: List<HomeGreetingSchedule.CuePlay>,
        rediscover: List<HomeGreetingPrompt.RecentPlay>,
        albums: List<HomeGreetingSchedule.CueAlbum>,
        now: Long,
    ): HomeGreetingPrompt.SlotCues = HomeGreetingSchedule.pickCues(recent, rediscover, albums, now)

    // ── About follow-ups ────────────────────────────────────────────────────────────────────────────────

    const val followUpMaxOutputTokens = AboutFollowUpPrompt.MAX_OUTPUT_TOKENS
    const val followUpTemperature = AboutFollowUpPrompt.TEMPERATURE
    const val followUpTopK = AboutFollowUpPrompt.TOP_K
    const val followUpMaxQuestions = AboutFollowUpPrompt.MAX_QUESTIONS

    fun aboutEntry(
        kind: String,
        entryKey: String,
        titleDisplay: String,
        artistDisplay: String,
        titleText: String?,
        promptText: String?,
        answerText: String,
    ): AboutFollowUpEntry =
        AboutFollowUpEntry(kind, entryKey, titleDisplay, artistDisplay, titleText, promptText, answerText)

    /** The About text the model reads, or null when there is too little to ground a question. */
    fun prepareFollowUps(
        entries: List<AboutFollowUpEntry>,
        asked: List<String>,
        localeTag: String,
    ): AboutFollowUpPrompt.Prepared? = AboutFollowUpPrompt.prepare(entries, asked, localeTag)

    fun followUpInstructions(localeTag: String): String = AboutFollowUpPrompt.systemInstruction(localeTag)

    fun followUpUserPrompt(prepared: AboutFollowUpPrompt.Prepared): String = AboutFollowUpPrompt.userPrompt(prepared)

    fun followUpPlainPrompt(prepared: AboutFollowUpPrompt.Prepared): String = AboutFollowUpPrompt.buildPrompt(prepared)

    fun validateFollowUps(questions: List<String>, asked: List<String>, localeTag: String): List<String> =
        AboutFollowUpPrompt.validateQuestions(questions, asked, localeTag)

    fun parseFollowUps(answer: String?, asked: List<String>, localeTag: String): List<String> =
        AboutFollowUpPrompt.parse(answer, asked, localeTag)

    /** The model's questions ahead of the static quick prompts, less the asked ones and repeats. */
    fun mergeFollowUps(suggested: List<String>, fallback: List<String>, asked: List<String>): List<String> =
        AboutFollowUpPrompt.mergeWithFallback(suggested, fallback, asked)
}
