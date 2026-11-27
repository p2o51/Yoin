/*
 * TEST-ONLY REFERENCE: the pure decisions HomeGreetingStore made before they moved to :shared
 * (git show 086c768bb:app/src/main/java/com/gpo/yoin/data/highlight/HomeGreetingStore.kt): the slot of an hour,
 * the upcoming-slot merge, the two "should Nano run" checks, the 7-day phrase history and the cue picks of
 * loadSlotCues. The bodies are the store's, lifted out of SharedPreferences / Room unchanged apart from taking
 * their inputs as parameters, so OnDeviceAiPinTest can prove the shared schedule answers the same.
 */
package com.gpo.yoin.shared.reference

object RefHomeGreetingSchedule {
    private const val SEVEN_DAYS_MS = 7L * 86_400_000L
    private const val OLDER_ALBUM_AGE_MS = 3L * 86_400_000L
    private const val HISTORY_DAYS = 7L
    private const val MAX_HISTORY_ITEMS = 21
    const val MAX_DAILY_REFRESHES = 2

    data class Row(val artist: String, val album: String, val playedAt: Long)
    data class Agg(val artistName: String, val albumName: String, val lastPlayedAt: Long?)

    fun currentSlotAt(hour: Int): RefHomeGreetingSlot = when (hour) {
        in 5 until 11 -> RefHomeGreetingSlot.MORNING
        in 11 until 18 -> RefHomeGreetingSlot.AFTERNOON
        else -> RefHomeGreetingSlot.EVENING
    }

    fun attemptValue(day: Long, lang: String, cuesFp: String): String = "$day|$lang|$cuesFp"

    /** HomeGreetingStore.cachedFor after the profile lookup. */
    fun cachedFor(
        current: RefDailyHomeGreetings?,
        slot: RefHomeGreetingSlot,
        today: Long,
        lang: String,
        isNoon: Boolean,
    ): String? {
        current ?: return null
        if (current.day != today || current.localeTag != lang) return null
        val text = current.textFor(slot) ?: return null
        if (isNoon && RefHomeGreetingPrompt.conflictsWithNoon(text)) return null
        return text
    }

    /** HomeGreetingStore.needsGeneration with the cached row and the attempted key's value passed in. */
    fun needsGeneration(
        cached: RefDailyHomeGreetings?,
        today: Long,
        lang: String,
        currentSlot: RefHomeGreetingSlot,
        lastAttempt: String?,
    ): Boolean {
        val hasTodayCache = cached != null && cached.day == today && cached.localeTag == lang && cached.hasAny()
        if (!hasTodayCache) {
            return lastAttempt != attemptValue(today, lang, "")
        }
        return currentSlot != RefHomeGreetingSlot.EVENING && cached!!.refreshCount < MAX_DAILY_REFRESHES
    }

    /** The early return of HomeGreetingStore.generateIfNeeded: true when Nano should be asked. */
    fun shouldGenerate(
        cached: RefDailyHomeGreetings?,
        today: Long,
        lang: String,
        currentSlot: RefHomeGreetingSlot,
        cuesFp: String,
        lastAttempt: String?,
    ): Boolean {
        val existing = cached?.takeIf { it.day == today && it.localeTag == lang && it.hasAny() }
        val attemptVal = attemptValue(today, lang, cuesFp)
        if (existing != null) {
            val canRefreshUpcoming = currentSlot != RefHomeGreetingSlot.EVENING &&
                existing.refreshCount < MAX_DAILY_REFRESHES &&
                existing.cuesFingerprint != cuesFp &&
                lastAttempt != attemptVal
            if (!canRefreshUpcoming) return false
        } else if (lastAttempt == attemptVal) {
            return false
        }
        return true
    }

    fun mergeUpcomingSlots(
        existing: RefDailyHomeGreetings,
        fresh: RefDailyHomeGreetings,
        currentSlot: RefHomeGreetingSlot,
        cuesFp: String,
    ): RefDailyHomeGreetings = when (currentSlot) {
        RefHomeGreetingSlot.MORNING -> existing.copy(
            afternoon = fresh.afternoon ?: existing.afternoon,
            evening = fresh.evening ?: existing.evening,
            cuesFingerprint = cuesFp,
            refreshCount = existing.refreshCount + 1,
        )
        RefHomeGreetingSlot.AFTERNOON -> existing.copy(
            evening = fresh.evening ?: existing.evening,
            cuesFingerprint = cuesFp,
            refreshCount = existing.refreshCount + 1,
        )
        RefHomeGreetingSlot.EVENING -> existing.copy(
            cuesFingerprint = cuesFp,
            refreshCount = existing.refreshCount + 1,
        )
    }

    /** HomeGreetingStore.loadRecentHistory over the stored string. */
    fun loadRecentHistory(raw: String, today: Long): List<String> {
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

    /** HomeGreetingStore.recordRecentHistory: the string it stores (null = it writes nothing). */
    fun recordRecentHistory(raw: String, today: Long, phrases: List<String>): String? {
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
            .distinctBy { (_, p) -> RefHomeGreetingPrompt.normalizePhrase(p) }
            .takeLast(MAX_HISTORY_ITEMS)
            .joinToString("\n") { (d, p) -> "$d\t$p" }
    }

    /** The picks of HomeGreetingStore.loadSlotCues over rows already read. */
    fun loadSlotCues(
        recentRows: List<Row>,
        rediscoverSongs: List<Pair<String, String>>,
        albumAggregates: List<Agg>,
        now: Long,
    ): RefHomeGreetingPrompt.SlotCues {
        val morning = recentRows.firstNotNullOfOrNull { row ->
            RefHomeGreetingPrompt.RecentPlay(row.artist, row.album).clean()
        }
        val usedArtists = HashSet<String>()
        morning?.let { usedArtists += it.artist.lowercase() }

        val weekCutoff = now - SEVEN_DAYS_MS
        val weeklyRows = recentRows.filter { it.playedAt >= weekCutoff }
        val afternoon = weeklyRows
            .mapNotNull { row -> RefHomeGreetingPrompt.RecentPlay(row.artist, row.album).clean() }
            .filter { it.artist.lowercase() !in usedArtists }
            .groupBy { it.artist.lowercase() }
            .values
            .maxByOrNull { it.size }
            ?.firstOrNull()
            ?: recentRows
                .mapNotNull { row -> RefHomeGreetingPrompt.RecentPlay(row.artist, row.album).clean() }
                .firstOrNull { it.artist.lowercase() !in usedArtists }
        afternoon?.let { usedArtists += it.artist.lowercase() }

        val rediscover = rediscoverSongs
            .mapNotNull { (artist, album) -> RefHomeGreetingPrompt.RecentPlay(artist, album).clean() }
            .firstOrNull { it.artist.lowercase() !in usedArtists }

        val evening = rediscover ?: albumAggregates
            .filter { (it.lastPlayedAt ?: 0L) <= now - OLDER_ALBUM_AGE_MS }
            .mapNotNull { agg -> RefHomeGreetingPrompt.RecentPlay(agg.artistName, agg.albumName).clean() }
            .firstOrNull { it.artist.lowercase() !in usedArtists }

        return RefHomeGreetingPrompt.SlotCues(morning = morning, afternoon = afternoon, evening = evening)
    }
}
