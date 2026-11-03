package com.gpo.yoin.player

import android.content.Context
import androidx.core.content.edit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * How much of a Subsonic song the player asks for: the file as it is, or a
 * transcode at no more than [maxBitRateKbps]. Settings › Playback quality,
 * one choice for Wi‑Fi and one for mobile data.
 */
enum class StreamQuality(val key: String, val maxBitRateKbps: Int?) {
    Original("original", null),
    Kbps320("320", 320),
    Kbps192("192", 192),
    Kbps128("128", 128),
    ;

    companion object {
        fun fromKey(key: String?): StreamQuality? = entries.firstOrNull { it.key == key }
    }
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
        // Both Original until the owner picks defaults: today's behaviour.
        val DefaultWifi = StreamQuality.Original
        val DefaultMobile = StreamQuality.Original
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
