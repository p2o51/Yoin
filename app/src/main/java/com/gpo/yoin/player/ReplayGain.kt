package com.gpo.yoin.player

import android.content.Context
import android.os.Bundle
import androidx.core.content.edit
import com.gpo.yoin.data.model.ReplayGain
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.math.log10
import kotlin.math.min
import kotlin.math.pow

/**
 * Settings › Playback › Volume leveling (owner task 2026-11-04): which of a
 * song's ReplayGain values the local player applies. Subsonic only
 * (Capability.REPLAY_GAIN); a song without tags always plays as it is.
 */
enum class ReplayGainMode(val key: String) {
    Off("off"),
    Track("track"),

    /**
     * The album gain, or the track gain where the song has none: an album
     * mixed from different sources (《再生（全景声）》) is tagged per track on
     * purpose, and levels out this way.
     */
    Album("album"),
    ;

    companion object {
        fun fromKey(key: String?): ReplayGainMode? = entries.firstOrNull { it.key == key }
    }
}

data class ReplayGainSettings(
    val mode: ReplayGainMode = DefaultMode,
    /** Added to every tagged song: −18 LUFS (the tags' reference) is quiet on a phone. */
    val preampDb: Float = DefaultPreampDb,
) {
    companion object {
        // By album (owner, 2026-11-04): keeps each album's own dynamics, and an album
        // without album gain (《再生（全景声）》) falls back to track gain.
        val DefaultMode = ReplayGainMode.Album

        // About −14 LUFS, where streaming services sit; the peak guard keeps it from clipping.
        const val DefaultPreampDb = 4f
    }
}

/** Which value a gain came from, for the debug log. */
enum class ReplayGainSource { None, Track, Album, AlbumFromTrack }

/** The gain the player applies to a song: [linear] multiplies every sample. */
data class AppliedReplayGain(
    val db: Float,
    val linear: Float,
    val source: ReplayGainSource,
    /** The gain was lowered so the peak stays at full scale. */
    val peakLimited: Boolean,
) {
    companion object {
        val Unity = AppliedReplayGain(db = 0f, linear = 1f, source = ReplayGainSource.None, peakLimited = false)
    }
}

/**
 * The gain for [gain] under [settings]: the chosen value plus the pre-amp,
 * lowered where the song's peak would go past full scale. [AppliedReplayGain.Unity]
 * when leveling is off or the song has no tag for it: an untagged song is
 * never guessed at, and gets no pre-amp either.
 */
fun appliedReplayGain(gain: ReplayGain?, settings: ReplayGainSettings): AppliedReplayGain {
    if (gain == null) return AppliedReplayGain.Unity
    val (db, source) = when (settings.mode) {
        ReplayGainMode.Off -> return AppliedReplayGain.Unity
        ReplayGainMode.Track -> gain.trackGainDb?.let { it to ReplayGainSource.Track }
        ReplayGainMode.Album -> gain.albumGainDb?.let { it to ReplayGainSource.Album }
            ?: gain.trackGainDb?.let { it to ReplayGainSource.AlbumFromTrack }
    } ?: return AppliedReplayGain.Unity
    // This song's own peak is what clips; the album's (≥ it) only where the track's is missing.
    val peak = gain.trackPeak ?: gain.albumPeak
    val wanted = dbToLinear(db + settings.preampDb)
    val ceiling = peak?.takeIf { it > 0f }?.let { 1f / it }
    val linear = if (ceiling != null) min(wanted, ceiling) else wanted
    val limited = ceiling != null && wanted > ceiling
    return AppliedReplayGain(
        db = if (limited) linearToDb(linear) else db + settings.preampDb,
        linear = linear,
        source = source,
        peakLimited = limited,
    )
}

internal fun dbToLinear(db: Float): Float = 10f.pow(db / 20f)

internal fun linearToDb(linear: Float): Float = 20f * log10(linear)

/** The stored [ReplayGainSettings], for this device (like the streaming quality). */
object ReplayGainPreference {
    private const val PREFS_NAME = "yoin_playback_preferences"
    private const val KEY_MODE = "replay_gain_mode"

    private val state = MutableStateFlow(ReplayGainSettings())

    @Volatile
    private var loaded = false

    /** The current choice; the player reads it for every buffer, so a change applies at once. */
    fun current(context: Context): ReplayGainSettings {
        ensureLoaded(context)
        return state.value
    }

    fun observe(context: Context): StateFlow<ReplayGainSettings> {
        ensureLoaded(context)
        return state.asStateFlow()
    }

    fun setMode(context: Context, mode: ReplayGainMode) {
        ensureLoaded(context)
        state.value = state.value.copy(mode = mode)
        prefs(context).edit { putString(KEY_MODE, mode.key) }
    }

    private fun ensureLoaded(context: Context) {
        if (loaded) return
        synchronized(this) {
            if (loaded) return
            val mode = ReplayGainMode.fromKey(prefs(context).getString(KEY_MODE, null))
            state.value = ReplayGainSettings(mode = mode ?: ReplayGainSettings.DefaultMode)
            loaded = true
        }
    }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
}

/** [ReplayGain] across a MediaItem's metadata extras; absent fields stay absent. */
internal fun ReplayGain.toBundle(): Bundle = Bundle().apply {
    trackGainDb?.let { putFloat(KEY_TRACK_GAIN, it) }
    albumGainDb?.let { putFloat(KEY_ALBUM_GAIN, it) }
    trackPeak?.let { putFloat(KEY_TRACK_PEAK, it) }
    albumPeak?.let { putFloat(KEY_ALBUM_PEAK, it) }
    baseGainDb?.let { putFloat(KEY_BASE_GAIN, it) }
    fallbackGainDb?.let { putFloat(KEY_FALLBACK_GAIN, it) }
}

internal fun Bundle.toReplayGain(): ReplayGain = ReplayGain(
    trackGainDb = floatOrNull(KEY_TRACK_GAIN),
    albumGainDb = floatOrNull(KEY_ALBUM_GAIN),
    trackPeak = floatOrNull(KEY_TRACK_PEAK),
    albumPeak = floatOrNull(KEY_ALBUM_PEAK),
    baseGainDb = floatOrNull(KEY_BASE_GAIN),
    fallbackGainDb = floatOrNull(KEY_FALLBACK_GAIN),
)

private fun Bundle.floatOrNull(key: String): Float? = if (containsKey(key)) getFloat(key) else null

/** A MediaItem's metadata extras key for its [ReplayGain] bundle. */
internal const val EXTRA_REPLAY_GAIN = "replay_gain"

private const val KEY_TRACK_GAIN = "track_gain"
private const val KEY_ALBUM_GAIN = "album_gain"
private const val KEY_TRACK_PEAK = "track_peak"
private const val KEY_ALBUM_PEAK = "album_peak"
private const val KEY_BASE_GAIN = "base_gain"
private const val KEY_FALLBACK_GAIN = "fallback_gain"
