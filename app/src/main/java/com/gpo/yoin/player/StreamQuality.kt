package com.gpo.yoin.player

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioManager
import androidx.core.content.edit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * How much of a Subsonic song the player asks for: the file as it is, or a
 * transcode at no more than [maxBitRateKbps]. Settings › Playback quality,
 * one choice for Wi‑Fi and one for mobile data. [Auto] follows what the sound
 * goes out through ([forOutput]).
 */
enum class StreamQuality(val key: String, val maxBitRateKbps: Int?) {
    Auto("auto", null),
    Original("original", null),
    Kbps320("320", 320),
    Kbps192("192", 192),
    Kbps128("128", 128),
    ;

    /** The fixed choice this one means on [output]: itself unless [Auto]. */
    fun forOutput(output: AudioOutputKind): StreamQuality = if (this != Auto) this else output.autoQuality

    companion object {
        fun fromKey(key: String?): StreamQuality? = entries.firstOrNull { it.key == key }
    }
}

/**
 * What the sound goes out through, and what [StreamQuality.Auto] asks for on it
 * (owner 2026-11-04: headphones that can carry more get more). Android tells an
 * app the kind of output but not which Bluetooth codec it negotiated
 * (BluetoothA2dp's codec status is a system API), so LDAC / aptX and AAC / SBC
 * headphones get one choice between them.
 */
enum class AudioOutputKind(val autoQuality: StreamQuality) {
    /** Wired headphones, a USB DAC or headset, a dock: lossless reaches the ear. */
    Wired(StreamQuality.Original),

    /** Bluetooth (A2DP, LE Audio, hearing aids): re-encoded on the way, so 320 is past what most codecs carry. */
    Bluetooth(StreamQuality.Kbps320),

    /** The device's own speaker, a speaker dock, or anything else. */
    Speaker(StreamQuality.Kbps192),
}

/** The two choices; which one applies is the network's to say ([forNetwork]). */
data class StreamQualitySettings(
    val wifi: StreamQuality = DefaultWifi,
    val mobile: StreamQuality = DefaultMobile,
) {
    /**
     * [unmetered] is Android's NET_CAPABILITY_NOT_METERED: Wi‑Fi and Ethernet
     * usually. Every metered network is mobile data, a phone's hotspot included.
     */
    fun forNetwork(unmetered: Boolean): StreamQuality = if (unmetered) wifi else mobile

    companion object {
        // Wi‑Fi has no reason to send less than the file; mobile data follows
        // the headphones (owner 2026-11-04).
        val DefaultWifi = StreamQuality.Original
        val DefaultMobile = StreamQuality.Auto
    }
}

/** The stored [StreamQualitySettings], for this device (data use is the device's, not the account's). */
object StreamQualityPreference {
    private const val PREFS_NAME = "yoin_playback_preferences"
    private const val KEY_WIFI = "stream_quality_wifi"
    private const val KEY_MOBILE = "stream_quality_mobile"

    private val state = MutableStateFlow(StreamQualitySettings())

    @Volatile
    private var loaded = false

    /** The current choice; read by the player each time it opens a song. */
    fun current(context: Context): StreamQualitySettings {
        ensureLoaded(context)
        return state.value
    }

    fun observe(context: Context): StateFlow<StreamQualitySettings> {
        ensureLoaded(context)
        return state.asStateFlow()
    }

    fun setWifi(context: Context, quality: StreamQuality) = update(context) { it.copy(wifi = quality) }

    fun setMobile(context: Context, quality: StreamQuality) = update(context) { it.copy(mobile = quality) }

    private fun update(context: Context, change: (StreamQualitySettings) -> StreamQualitySettings) {
        ensureLoaded(context)
        val next = change(state.value)
        state.value = next
        prefs(context).edit {
            putString(KEY_WIFI, next.wifi.key)
            putString(KEY_MOBILE, next.mobile.key)
        }
    }

    private fun ensureLoaded(context: Context) {
        if (loaded) return
        synchronized(this) {
            if (loaded) return
            val prefs = prefs(context)
            val wifi = StreamQuality.fromKey(prefs.getString(KEY_WIFI, null))
            val mobile = StreamQuality.fromKey(prefs.getString(KEY_MOBILE, null))
            state.value = StreamQualitySettings(
                wifi = wifi ?: StreamQualitySettings.DefaultWifi,
                mobile = mobile ?: StreamQualitySettings.DefaultMobile,
            )
            loaded = true
        }
    }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
}

/**
 * The output media plays through now, from the connected output devices: a
 * wired or USB one wins (Android routes media to it over Bluetooth), then
 * Bluetooth, else the speaker. Needs no permission.
 */
internal fun AudioManager.currentOutputKind(): AudioOutputKind =
    outputKindOf(getDevices(AudioManager.GET_DEVICES_OUTPUTS).map { it.type })

/** [currentOutputKind] over [AudioDeviceInfo] types. */
internal fun outputKindOf(types: List<Int>): AudioOutputKind = when {
    types.any { it in WiredTypes } -> AudioOutputKind.Wired
    types.any { it in BluetoothTypes } -> AudioOutputKind.Bluetooth
    else -> AudioOutputKind.Speaker
}

private val WiredTypes = setOf(
    AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
    AudioDeviceInfo.TYPE_WIRED_HEADSET,
    AudioDeviceInfo.TYPE_USB_HEADSET,
    AudioDeviceInfo.TYPE_USB_DEVICE,
    AudioDeviceInfo.TYPE_USB_ACCESSORY,
    AudioDeviceInfo.TYPE_LINE_ANALOG,
    AudioDeviceInfo.TYPE_LINE_DIGITAL,
    AudioDeviceInfo.TYPE_AUX_LINE,
    AudioDeviceInfo.TYPE_HDMI,
)

@SuppressLint("InlinedApi") // Constants: they compare as ints on any version.
private val BluetoothTypes = setOf(
    AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
    AudioDeviceInfo.TYPE_HEARING_AID,
    AudioDeviceInfo.TYPE_BLE_HEADSET,
    AudioDeviceInfo.TYPE_BLE_SPEAKER,
    AudioDeviceInfo.TYPE_BLE_BROADCAST,
)
