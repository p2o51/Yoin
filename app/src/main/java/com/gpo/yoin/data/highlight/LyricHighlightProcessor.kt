package com.gpo.yoin.data.highlight

import android.content.Context
import android.content.SharedPreferences
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager
import android.util.Log
import androidx.core.content.edit
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import com.gpo.yoin.data.local.AlbumRatingDao
import com.gpo.yoin.data.local.LyricHighlight
import com.gpo.yoin.data.local.LyricHighlightDao
import com.gpo.yoin.data.model.CoverRef
import com.gpo.yoin.data.model.MediaId
import com.gpo.yoin.data.model.Track
import com.gpo.yoin.data.repository.YoinRepository
import java.util.TimeZone
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Picks lyric highlights with Gemini Nano, a few songs at a time, only while Yoin is open.
 *
 * AICore refuses inference unless the app is the top foreground app (a WorkManager job or a foreground
 * service gets BACKGROUND_USE_BLOCKED), so this follows the process lifecycle: [START_DELAY_MS] after Yoin
 * comes to the foreground it works through up to [NanoBudget.PER_VISIT_LIMIT] songs, [NanoBudget.GAP_MS]
 * apart, and stops the moment Yoin leaves the foreground. The song playing now goes first (its lyrics are
 * usually loaded already), then the [LyricHighlightSelector] queue. Nothing runs while the phone is warm, on
 * battery saver, or below [MIN_BATTERY_PERCENT] and not charging; [NanoBudget] caps the day and backs off
 * when AICore says it's busy.
 *
 * Each song costs one short inference: a few hundred to ~1,500 tokens in, ~15 out.
 */
class LyricHighlightProcessor(
    private val context: Context,
    private val repository: YoinRepository,
    private val dao: LyricHighlightDao,
    private val albumRatingDao: AlbumRatingDao,
    private val model: LyricHighlightModel,
    private val currentTrack: () -> Track?,
    private val profileStartupDone: StateFlow<Boolean>,
    private val clock: () -> Long = System::currentTimeMillis,
    /**
     * The Gemini Nano test copy (BuildConfig.NANO_TEST): a short start delay, more songs per visit, no daily
     * cap (AICore's own BUSY and quota still apply), and every step written to [events] for Home to show.
     */
    private val testMode: Boolean = false,
) {
    private val scope = CoroutineScope(
        SupervisorJob() + Dispatchers.IO + CoroutineExceptionHandler { _, error ->
            Log.w(TAG, "Lyric highlight visit stopped", error)
        },
    )
    private val prefs: SharedPreferences by lazy { context.getSharedPreferences(PREFS, Context.MODE_PRIVATE) }
    private var visit: Job? = null

    private val _status = MutableStateFlow(cachedStatus())

    /**
     * Nano on this device: the last answer (kept across launches, so Home knows before this launch's check),
     * null before the first check ever. Display surfaces show only when AVAILABLE.
     */
    val status: StateFlow<NanoStatus?> = _status.asStateFlow()

    private fun cachedStatus(): NanoStatus? =
        prefs.getString(KEY_STATUS, null)?.let { name -> NanoStatus.entries.firstOrNull { it.name == name } }

    private val _events = MutableStateFlow<List<String>>(emptyList())

    /** The latest steps, newest first (test mode only; always empty otherwise). */
    val events: StateFlow<List<String>> = _events.asStateFlow()

    private fun note(message: String) {
        if (!testMode) return
        Log.i(TAG, message)
        _events.value = (listOf(message) + _events.value).take(EVENT_LIMIT)
    }

    /** Bumped after each new highlight, so a display can refresh. */
    private val _revision = MutableStateFlow(0L)
    val revision: StateFlow<Long> = _revision.asStateFlow()

    fun start() {
        ProcessLifecycleOwner.get().lifecycle.addObserver(
            object : DefaultLifecycleObserver {
                override fun onStart(owner: LifecycleOwner) {
                    if (visit?.isActive == true) return
                    visit = scope.launch { runVisit() }
                }

                override fun onStop(owner: LifecycleOwner) {
                    // Leaving the foreground: AICore would refuse the next call anyway. A pick in flight is
                    // dropped, never half-written.
                    visit?.cancel()
                    visit = null
                }
            },
        )
    }

    private suspend fun runVisit() {
        profileStartupDone.first { it }
        var nano = model.status()
        publishStatus(nano)
        note("Nano: $nano")
        if (nano == NanoStatus.DOWNLOADABLE && onUnmeteredNetwork()) {
            // A supported phone that hasn't fetched Nano yet: ask AICore for it (Wi-Fi only), then look again.
            note("Downloading the model…")
            model.download()
            nano = model.status()
            publishStatus(nano)
            note("Nano after download: $nano")
        }
        if (nano != NanoStatus.AVAILABLE) return
        delay(if (testMode) TEST_START_DELAY_MS else START_DELAY_MS)
        var done = 0
        val queue = buildQueue()
        note("Queue: ${queue.size} songs")
        val perVisit = if (testMode) TEST_PER_VISIT_LIMIT else NanoBudget.PER_VISIT_LIMIT
        for (entry in queue) {
            if (done >= perVisit) break
            if (!deviceIsRelaxed()) {
                note("Paused: phone warm, battery saver or low battery")
                break
            }
            val now = clock()
            if (!testMode && !budget().allows(now, today(now))) break
            // Switched accounts mid-visit: the rest of the queue belongs to the old one.
            if (!stillActive(entry)) break
            val started = clock()
            val outcome = process(entry)
            note("${entry.title}: ${outcome.name.lowercase()} (${clock() - started} ms)")
            if (outcome == Outcome.STOP) break
            if (outcome == Outcome.PICKED || outcome == Outcome.UNUSABLE) done++
            delay(if (testMode) TEST_GAP_MS else NanoBudget.GAP_MS)
        }
        note("Visit done: $done")
    }

    /** A song to work on, with what's needed to show it later without the network. */
    private data class Entry(
        val profileId: String,
        val provider: String,
        val songId: String,
        val title: String,
        val artist: String,
        val album: String,
        val albumId: String,
        val coverArtId: String?,
        val durationMs: Long,
    )

    private enum class Outcome { PICKED, UNUSABLE, SKIPPED, STOP }

    private suspend fun buildQueue(): List<Entry> {
        val profileId = repository.currentProfileId() ?: return emptyList()
        val provider = repository.currentProviderId() ?: return emptyList()
        val now = clock()
        val settled = dao.settledSongIds(profileId, provider, retryAfter = now - RETRY_UNUSABLE_AFTER_MS).toHashSet()
        val out = ArrayList<Entry>()

        currentTrack()?.takeIf { it.id.provider == provider && it.id.rawId !in settled }?.let { track ->
            out += track.toEntry(profileId)
        }

        val history = LyricHighlightSignals.load(dao, profileId, provider, now)
        val byId = history.rows
        val fresh = freshTracks(profileId, provider, played = byId.keys, settled = settled)
        val pools = LyricHighlightSelector.pools(history.signals.values.toList(), fresh.map { it.first }, now)
        val queue = LyricHighlightSelector.processingQueue(
            pools,
            skip = settled + out.map(Entry::songId),
            limit = NanoBudget.PER_VISIT_LIMIT * 2,
        )
        val freshById = fresh.associate { it.first.songId to it.second }
        for (candidate in queue) {
            val row = byId[candidate.songId]
            out += when {
                row != null -> Entry(
                    profileId, provider, row.songId, row.title, row.artist, row.album, row.albumId,
                    row.coverArtId, row.durationMs,
                )
                else -> freshById[candidate.songId] ?: continue
            }
        }
        return out
    }

    /**
     * Unplayed tracks from the best-rated albums (7+), a few albums per visit; the album fetch goes through
     * the repository's detail cache, so a repeat visit is cheap.
     */
    private suspend fun freshTracks(
        profileId: String,
        provider: String,
        played: Set<String>,
        settled: Set<String>,
    ): List<Pair<LyricHighlightSelector.FreshTrack, Entry>> {
        val albums = albumRatingDao.getAllForProfile(provider, profileId)
            .filter { it.rating >= FRESH_MIN_ALBUM_RATING }
            .sortedByDescending { it.rating }
            .take(FRESH_ALBUMS_PER_VISIT)
        val out = ArrayList<Pair<LyricHighlightSelector.FreshTrack, Entry>>()
        for (rating in albums) {
            val album = withTimeoutOrNull(ALBUM_TIMEOUT_MS) {
                runCatching { repository.getAlbum(MediaId(provider, rating.albumId)) }.getOrNull()
            } ?: continue
            album.tracks
                .filter { it.id.provider == provider && it.id.rawId !in played && it.id.rawId !in settled }
                .take(FRESH_TRACKS_PER_ALBUM)
                .forEach { track ->
                    out += LyricHighlightSelector.FreshTrack(track.id.rawId, rating.rating) to
                        track.toEntry(profileId, albumName = album.name, albumCover = CoverRef.toStorageKey(album.coverArt))
                }
        }
        return out
    }

    private suspend fun process(entry: Entry): Outcome {
        // Outer null: timed out or threw (server down, offline). Not the song's fault, so nothing is recorded.
        val fetched = withTimeoutOrNull(LYRICS_TIMEOUT_MS) {
            runCatching {
                repository.getLoadedLyrics(
                    MediaId(entry.provider, entry.songId),
                    title = entry.title,
                    artist = entry.artist,
                    durationMs = entry.durationMs.takeIf { it > 0 },
                )
            }
        }
        // A timeout most likely means the server is unreachable: end the visit rather than wait on each song.
        if (fetched == null) return Outcome.STOP
        if (fetched.isFailure) return Outcome.SKIPPED
        // The lyrics came from the account active now; drop them if that isn't the entry's.
        if (!stillActive(entry)) return Outcome.STOP
        val lyrics = fetched.getOrNull()
        val prepared = lyrics?.lyrics?.let(LyricHighlightPrompt::prepare)
        if (prepared == null) {
            // Lyric providers swallow their own network errors into "none": only offline is told apart.
            if (lyrics == null && !online()) return Outcome.STOP
            // No lyrics found or too little sung: remember it, retry in a month.
            save(entry, pick = null, fingerprint = null, model = null)
            return Outcome.UNUSABLE
        }
        val existing = dao.get(entry.profileId, entry.provider, entry.songId)
        // The user's own pick stands; Nano never second-guesses it.
        if (existing?.model == USER_PICK) return Outcome.SKIPPED
        if (existing?.text != null && existing.lyricsFingerprint == prepared.fingerprint) return Outcome.SKIPPED

        val prompt = LyricHighlightPrompt.SYSTEM_INSTRUCTION + "\n\n" +
            LyricHighlightPrompt.userPrompt(entry.title, entry.artist, prepared)
        val now = clock()
        return when (val answer = model.answer(prompt)) {
            is NanoAnswer.Text -> {
                writeBudget(budget().afterSuccess(today(now)))
                if (!stillActive(entry)) return Outcome.STOP
                val pick = LyricHighlightPrompt.parse(answer.text, prepared)
                save(entry, pick, prepared.fingerprint, model.baseModelName())
                if (pick != null) {
                    _revision.value = _revision.value + 1
                    Outcome.PICKED
                } else {
                    Log.i(TAG, "Unusable answer for ${entry.songId}: ${answer.text.take(80)}")
                    note("Odd answer: ${answer.text.take(40)}")
                    Outcome.UNUSABLE
                }
            }
            NanoAnswer.Busy -> {
                note("AICore busy: backing off")
                writeBudget(budget().afterBusy(now, today(now)))
                Outcome.STOP
            }
            NanoAnswer.QuotaExhausted -> {
                note("Battery quota used up for today")
                writeBudget(budget().afterQuotaExhausted(today(now)))
                Outcome.STOP
            }
            NanoAnswer.NotForeground -> Outcome.STOP
            is NanoAnswer.Failed -> {
                note("Nano failed: code ${answer.code}")
                Outcome.SKIPPED
            }
        }
    }

    private suspend fun save(entry: Entry, pick: LyricHighlightPrompt.Pick?, fingerprint: String?, model: String?) {
        val previous = dao.get(entry.profileId, entry.provider, entry.songId)
        // A failed try never replaces a line already picked (a lyric source down, an odd answer), and nothing
        // replaces one the user picked themselves.
        if (pick == null && previous?.text != null) return
        if (previous?.model == USER_PICK) return
        dao.upsert(
            LyricHighlight(
                profileId = entry.profileId,
                provider = entry.provider,
                songId = entry.songId,
                title = entry.title,
                artist = entry.artist,
                album = entry.album,
                albumId = entry.albumId,
                coverArtId = entry.coverArtId,
                durationMs = entry.durationMs,
                text = pick?.text,
                startMs = pick?.startMs,
                lyricsFingerprint = fingerprint,
                model = model,
                attemptedAt = clock(),
                lastShownAt = previous?.lastShownAt,
            ),
        )
    }

    /**
     * Lines the user shared or saved from the lyrics page (owner 2026-11-08: they liked them, so they are
     * candidates too). Kept as this song's highlight, marked [USER_PICK], over whatever Nano picked; the first
     * [MAX_USER_PICK_LINES] lines, from the first one's time. Only for the song playing now, which is where
     * the lyrics page's pick comes from.
     */
    fun keepUserPick(songId: String, lines: List<String>, startMs: Long?) {
        val text = lines.map(String::trim).filter(String::isNotEmpty).take(MAX_USER_PICK_LINES).joinToString("\n")
        if (text.isEmpty()) return
        scope.launch {
            val track = currentTrack()?.takeIf { it.id.toString() == songId || it.id.rawId == songId } ?: return@launch
            val profileId = repository.currentProfileId() ?: return@launch
            if (repository.currentProviderId() != track.id.provider) return@launch
            val entry = track.toEntry(profileId)
            val previous = dao.get(entry.profileId, entry.provider, entry.songId)
            dao.upsert(
                LyricHighlight(
                    profileId = entry.profileId,
                    provider = entry.provider,
                    songId = entry.songId,
                    title = entry.title,
                    artist = entry.artist,
                    album = entry.album,
                    albumId = entry.albumId,
                    coverArtId = entry.coverArtId ?: previous?.coverArtId,
                    durationMs = entry.durationMs,
                    text = text,
                    startMs = startMs,
                    lyricsFingerprint = null,
                    model = USER_PICK,
                    attemptedAt = clock(),
                    lastShownAt = previous?.lastShownAt,
                ),
            )
            note("Kept your pick: ${entry.title}")
            _revision.value = _revision.value + 1
        }
    }

    private fun publishStatus(nano: NanoStatus) {
        if (_status.value == nano) return
        _status.value = nano
        prefs.edit { putString(KEY_STATUS, nano.name) }
    }

    private fun stillActive(entry: Entry): Boolean =
        repository.currentProfileId() == entry.profileId && repository.currentProviderId() == entry.provider

    private fun network(): NetworkCapabilities? {
        val connectivity = context.getSystemService(ConnectivityManager::class.java) ?: return null
        return connectivity.getNetworkCapabilities(connectivity.activeNetwork)
    }

    private fun online(): Boolean = network()?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true

    private fun onUnmeteredNetwork(): Boolean =
        network()?.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED) == true

    /** No inference on a warm phone, on battery saver, or on a low battery that isn't charging. */
    private fun deviceIsRelaxed(): Boolean {
        val power = context.getSystemService(PowerManager::class.java)
        if (power != null) {
            if (power.isPowerSaveMode) return false
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
                power.currentThermalStatus >= PowerManager.THERMAL_STATUS_MODERATE
            ) {
                return false
            }
        }
        val battery = context.getSystemService(BatteryManager::class.java) ?: return true
        if (battery.isCharging) return true
        val percent = battery.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        return percent <= 0 || percent >= MIN_BATTERY_PERCENT
    }

    private fun today(now: Long): Long = NanoBudget.dayOf(now, TimeZone.getDefault().getOffset(now))

    private fun budget(): NanoBudget = NanoBudget(
        day = prefs.getLong(KEY_DAY, 0L),
        usedToday = prefs.getInt(KEY_USED, 0),
        pausedUntil = prefs.getLong(KEY_PAUSED_UNTIL, 0L),
        busyStreak = prefs.getInt(KEY_BUSY_STREAK, 0),
        quotaExhaustedDay = prefs.getLong(KEY_QUOTA_DAY, -1L),
    )

    private fun writeBudget(budget: NanoBudget) {
        prefs.edit {
            putLong(KEY_DAY, budget.day)
            putInt(KEY_USED, budget.usedToday)
            putLong(KEY_PAUSED_UNTIL, budget.pausedUntil)
            putInt(KEY_BUSY_STREAK, budget.busyStreak)
            putLong(KEY_QUOTA_DAY, budget.quotaExhaustedDay)
        }
    }

    private fun Track.toEntry(profileId: String, albumName: String? = null, albumCover: String? = null) = Entry(
        profileId = profileId,
        provider = id.provider,
        songId = id.rawId,
        title = title.orEmpty(),
        artist = artist.orEmpty(),
        album = album ?: albumName.orEmpty(),
        albumId = albumId?.rawId.orEmpty(),
        coverArtId = CoverRef.toStorageKey(coverArt) ?: albumCover,
        durationMs = (durationSec ?: 0) * 1000L,
    )

    companion object {
        private const val TAG = "LyricHighlights"
        private const val PREFS = "lyric_highlights"
        private const val KEY_STATUS = "nano_status"
        private const val KEY_DAY = "budget_day"
        private const val KEY_USED = "budget_used"
        private const val KEY_PAUSED_UNTIL = "budget_paused_until"
        private const val KEY_BUSY_STREAK = "budget_busy_streak"
        private const val KEY_QUOTA_DAY = "budget_quota_day"

        /** After coming to the foreground: launch, Home and the feed go first. */
        /** [LyricHighlight.model] of a line the user picked themselves. */
        const val USER_PICK = "user"
        const val MAX_USER_PICK_LINES = 2
        const val START_DELAY_MS = 10_000L
        private const val TEST_START_DELAY_MS = 2_000L
        private const val TEST_PER_VISIT_LIMIT = 15
        private const val TEST_GAP_MS = 2_000L
        private const val EVENT_LIMIT = 8
        const val MIN_BATTERY_PERCENT = 30

        /** A song that gave nothing usable is tried again after this (lyrics sources add songs). */
        const val RETRY_UNUSABLE_AFTER_MS = 30L * LyricHighlightSelector.DAY_MS

        const val FRESH_MIN_ALBUM_RATING = 7f
        const val FRESH_ALBUMS_PER_VISIT = 3
        const val FRESH_TRACKS_PER_ALBUM = 4
        const val ALBUM_TIMEOUT_MS = 8_000L
        const val LYRICS_TIMEOUT_MS = 10_000L
    }
}
