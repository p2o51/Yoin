package com.gpo.yoin.player

import android.content.Context
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.Spatializer
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.annotation.RequiresApi
import androidx.core.content.edit
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/*
 * Spatial audio (owner task 2026-11-04). Yoin plays a multichannel file (5.1,
 * 7.1) as the original since #121 and hands every channel to the system.
 * Whether the listener then hears it spatialized is Android's call, not the
 * player's: the platform Spatializer takes a media track with spatialization
 * behaviour AUTO and a multichannel layout when it is switched on in system
 * settings and the current output (usually headphones) supports it. Head
 * tracking is the system's too, per headset. Elsewhere the system folds the
 * channels down to stereo.
 *
 * So Yoin's part is: ask for spatialization (or opt out, the setting below),
 * keep Media3 from narrowing the track, and say truthfully which of the two
 * the listener gets — the "Spatial" tag on Now Playing and the status line in
 * Settings › Playback quality.
 */

/** Whether Yoin lets the system spatialize multichannel songs. */
enum class SpatialAudioMode(val key: String) {
    /** Media3 SPATIALIZATION_BEHAVIOR_AUTO: spatialized wherever the system can. */
    Auto("auto"),

    /** SPATIALIZATION_BEHAVIOR_NEVER: always the system's stereo fold-down. */
    Off("off"),
    ;

    companion object {
        fun fromKey(key: String?): SpatialAudioMode? = entries.firstOrNull { it.key == key }
    }
}

/** The Media3 spatialization behaviour [mode] asks for. */
internal fun SpatialAudioMode.spatializationBehavior(): Int = when (this) {
    SpatialAudioMode.Auto -> C.SPATIALIZATION_BEHAVIOR_AUTO
    SpatialAudioMode.Off -> C.SPATIALIZATION_BEHAVIOR_NEVER
}

/** The attributes Yoin's local player plays music with, spatialized as [mode] asks. */
internal fun musicAudioAttributes(mode: SpatialAudioMode): AudioAttributes = AudioAttributes.Builder()
    .setUsage(C.USAGE_MEDIA)
    .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
    .setSpatializationBehavior(mode.spatializationBehavior())
    .build()

/**
 * What the system's Spatializer can do right now, plus Yoin's own choice.
 *
 * [supported]: the device has a spatializer at all (immersive level not NONE;
 * false before Android 13 / API 32). [enabled]: the system switch is on.
 * [available]: the current output can be spatialized. [spatializableChannels]:
 * the channel counts (3–8, the usual layouts) the system would spatialize on
 * this output with Yoin's attributes. [headTracking]: the current headset
 * can track the head (Android 13+); whether it is switched on is the system's.
 */
data class SpatialAudioState(
    val mode: SpatialAudioMode = SpatialAudioMode.Auto,
    val supported: Boolean = false,
    val enabled: Boolean = false,
    val available: Boolean = false,
    val spatializableChannels: Set<Int> = emptySet(),
    val headTracking: Boolean = false,
)

/** How a song with [channels] channels reaches the ears. */
enum class SpatialPlayback {
    /** Mono or stereo: nothing to spatialize. */
    NotMultichannel,

    /** The system spatializes the channels. */
    Spatialized,

    /** Folded down to stereo because Yoin's setting is off. */
    DownmixedByChoice,

    /** Folded down: the device has no spatializer. */
    DownmixedUnsupported,

    /** Folded down: the system's spatial audio switch is off. */
    DownmixedSystemOff,

    /** Folded down: the current output (e.g. the speaker) can't be spatialized. */
    DownmixedOutput,
}

/** How [channels] plays under [state]; [SpatialPlayback.NotMultichannel] for unknown, mono and stereo. */
fun spatialPlaybackOf(channels: Int?, state: SpatialAudioState): SpatialPlayback = when {
    channels == null || channels <= 2 -> SpatialPlayback.NotMultichannel
    state.mode == SpatialAudioMode.Off -> SpatialPlayback.DownmixedByChoice
    !state.supported -> SpatialPlayback.DownmixedUnsupported
    !state.enabled -> SpatialPlayback.DownmixedSystemOff
    channels in state.spatializableChannels -> SpatialPlayback.Spatialized
    else -> SpatialPlayback.DownmixedOutput
}

/** The channel mask Android plays [channels] with; null outside the usual 3–8 layouts. */
internal fun multichannelMaskOf(channels: Int): Int? = when (channels) {
    3 -> AudioFormat.CHANNEL_OUT_STEREO or AudioFormat.CHANNEL_OUT_FRONT_CENTER
    4 -> AudioFormat.CHANNEL_OUT_QUAD
    5 -> AudioFormat.CHANNEL_OUT_QUAD or AudioFormat.CHANNEL_OUT_FRONT_CENTER
    6 -> AudioFormat.CHANNEL_OUT_5POINT1
    7 -> AudioFormat.CHANNEL_OUT_5POINT1 or AudioFormat.CHANNEL_OUT_BACK_CENTER
    8 -> AudioFormat.CHANNEL_OUT_7POINT1_SURROUND
    else -> null
}

/**
 * Watches the system Spatializer, the output route and Yoin's own setting,
 * one per process ([AppContainer.spatialAudio]). The player reads [state]'s
 * mode for its attributes; Now Playing and Settings show what it says.
 */
class SpatialAudioMonitor(context: Context) {
    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val audioManager = appContext.getSystemService(AudioManager::class.java)
    private val mainHandler = Handler(Looper.getMainLooper())

    private val _state = MutableStateFlow(
        SpatialAudioState(mode = SpatialAudioMode.fromKey(prefs.getString(KEY_MODE, null)) ?: SpatialAudioMode.Auto),
    )
    val state: StateFlow<SpatialAudioState> = _state.asStateFlow()

    init {
        if (Build.VERSION.SDK_INT >= 32 && audioManager != null) {
            Api32.watch(audioManager, mainHandler, ::refresh)
        }
        refresh()
    }

    fun setMode(mode: SpatialAudioMode) {
        if (_state.value.mode == mode) return
        prefs.edit { putString(KEY_MODE, mode.key) }
        _state.update { it.copy(mode = mode) }
    }

    private fun refresh() {
        val platform = if (Build.VERSION.SDK_INT >= 32 && audioManager != null) {
            Api32.read(audioManager)
        } else {
            SpatialAudioState()
        }
        _state.update { platform.copy(mode = it.mode) }
    }

    @RequiresApi(32)
    private object Api32 {
        fun read(audioManager: AudioManager): SpatialAudioState {
            val spatializer = audioManager.spatializer
            val supported = spatializer.immersiveAudioLevel != Spatializer.SPATIALIZER_IMMERSIVE_LEVEL_NONE
            val enabled = supported && spatializer.isEnabled
            val available = enabled && spatializer.isAvailable
            val attributes = musicAudioAttributes(SpatialAudioMode.Auto).platformAudioAttributes
            val channels = if (!available) {
                emptySet()
            } else {
                (3..8).filterTo(mutableSetOf()) { count ->
                    val mask = multichannelMaskOf(count) ?: return@filterTo false
                    val format = AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setChannelMask(mask)
                        .build()
                    runCatching { spatializer.canBeSpatialized(attributes, format) }.getOrDefault(false)
                }
            }
            val headTracking = available && Build.VERSION.SDK_INT >= 33 && Api33.headTracker(spatializer)
            return SpatialAudioState(
                supported = supported,
                enabled = enabled,
                available = available,
                spatializableChannels = channels,
                headTracking = headTracking,
            )
        }

        fun watch(audioManager: AudioManager, handler: Handler, onChange: () -> Unit) {
            val spatializer = audioManager.spatializer
            val executor = java.util.concurrent.Executor { handler.post(it) }
            spatializer.addOnSpatializerStateChangedListener(
                executor,
                object : Spatializer.OnSpatializerStateChangedListener {
                    override fun onSpatializerEnabledChanged(spatializer: Spatializer, enabled: Boolean) = onChange()

                    override fun onSpatializerAvailableChanged(spatializer: Spatializer, available: Boolean) =
                        onChange()
                },
            )
            if (Build.VERSION.SDK_INT >= 33) Api33.watchHeadTracker(spatializer, executor, onChange)
            // What can be spatialized depends on the output: a headset coming or going.
            audioManager.registerAudioDeviceCallback(
                object : AudioDeviceCallback() {
                    override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>?) = onChange()

                    override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>?) = onChange()
                },
                handler,
            )
        }
    }

    @RequiresApi(33)
    private object Api33 {
        fun headTracker(spatializer: Spatializer): Boolean = spatializer.isHeadTrackerAvailable

        fun watchHeadTracker(spatializer: Spatializer, executor: java.util.concurrent.Executor, onChange: () -> Unit) {
            spatializer.addOnHeadTrackerAvailableListener(executor) { _, _ -> onChange() }
        }
    }

    private companion object {
        // The player's preferences file, shared with StreamQualityPreference.
        const val PREFS_NAME = "yoin_playback_preferences"
        const val KEY_MODE = "spatial_audio_mode"
    }
}
