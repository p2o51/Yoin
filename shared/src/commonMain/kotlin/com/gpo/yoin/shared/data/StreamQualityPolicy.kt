package com.gpo.yoin.shared.data

/*
 * Subsonic stream quality, the rule both apps follow (Android origin/main
 * player/StreamQuality.kt and SubsonicStreamResolver.kt's StreamDelivery.plan,
 * owner 2026-11-04; iOS takes it from here, owner decision O01 2026-11-05).
 * Android keeps its own copy for now; the values here must match it.
 *
 * Settings › Playback quality holds one choice for Wi‑Fi and one for mobile
 * data. Which one applies is the network's to say: unmetered (Wi‑Fi,
 * Ethernet) or metered (cellular, a phone's hotspot, no network). Auto then
 * follows what the sound goes out through.
 */

/**
 * How much of a Subsonic song the player asks for: the file as it is, or a
 * transcode at no more than [maxBitRateKbps]. [Auto] follows the output
 * ([forOutput]). [key] is what each app stores.
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

    /** The cap in kbps, 0 for Auto / Original (no Int? across to Swift). */
    val capKbps: Int get() = maxBitRateKbps ?: 0

    /** Android's labels (strings_stream_quality.xml): "Auto", "Original", "320 kbps". */
    val label: String
        get() = when {
            this == Auto -> "Auto"
            maxBitRateKbps != null -> "$maxBitRateKbps kbps"
            else -> "Original"
        }

    companion object {
        fun fromKey(key: String?): StreamQuality? = entries.firstOrNull { it.key == key }

        /** Settings order: Auto, Original, 320, 192, 128. */
        val all: List<StreamQuality> get() = entries.toList()
    }
}

/**
 * What the sound goes out through, and what [StreamQuality.Auto] asks for on
 * it (owner 2026-11-04: headphones that can carry more get more).
 */
enum class AudioOutputKind(val autoQuality: StreamQuality) {
    /** Wired headphones, a USB DAC or headset, line out, HDMI: lossless reaches the ear. */
    Wired(StreamQuality.Original),

    /** Bluetooth (A2DP, LE Audio, hearing aids): re-encoded on the way, so 320 is past what most codecs carry. */
    Bluetooth(StreamQuality.Kbps320),

    /** The device's own speaker, a dock, a cast / AirPlay target, or anything else. */
    Speaker(StreamQuality.Kbps192),
    ;

    companion object {
        /**
         * The output media plays through when several are connected: a wired
         * one wins, then Bluetooth, else the speaker (Android `outputKindOf`).
         */
        fun strongest(kinds: List<AudioOutputKind>): AudioOutputKind = when {
            Wired in kinds -> Wired
            Bluetooth in kinds -> Bluetooth
            else -> Speaker
        }

        /** "Wired Original   Bluetooth 320 kbps   Speaker 192 kbps" (settings_stream_quality_auto_detail). */
        val autoDetail: String
            get() = "Wired ${Wired.autoQuality.label}   Bluetooth ${Bluetooth.autoQuality.label}   " +
                "Speaker ${Speaker.autoQuality.label}"
    }
}

/** The two choices, stored per device (data use is the device's, not the account's). */
data class StreamQualitySettings(
    val wifi: StreamQuality = DefaultWifi,
    val mobile: StreamQuality = DefaultMobile,
) {
    /** [unmetered]: Wi‑Fi / Ethernet. Every metered network is mobile data, a hotspot included. */
    fun forNetwork(unmetered: Boolean): StreamQuality = if (unmetered) wifi else mobile

    companion object {
        // Wi‑Fi has no reason to send less than the file; mobile data follows
        // the headphones (owner 2026-11-04).
        val DefaultWifi = StreamQuality.Original
        val DefaultMobile = StreamQuality.Auto
        val Defaults = StreamQualitySettings()

        /**
         * The two choices from their stored keys; a missing or unknown key is
         * its default. [legacyKey] is iOS's old single choice ("automatic",
         * "original", "320", …), read only when neither new key is stored: an
         * explicit quality applies to both networks, Automatic means the
         * defaults.
         */
        fun fromStored(wifiKey: String?, mobileKey: String?, legacyKey: String?): StreamQualitySettings {
            if (wifiKey == null && mobileKey == null) {
                val legacy = StreamQuality.fromKey(legacyKey)?.takeIf { it != StreamQuality.Auto }
                if (legacy != null) return StreamQualitySettings(legacy, legacy)
            }
            return StreamQualitySettings(
                wifi = StreamQuality.fromKey(wifiKey) ?: DefaultWifi,
                mobile = StreamQuality.fromKey(mobileKey) ?: DefaultMobile,
            )
        }
    }
}

/** What the player asks the server for, for one song (Android `StreamDelivery`). */
data class StreamPlan(val maxBitRateKbps: Int?, val format: String?) {
    val transcoded: Boolean get() = maxBitRateKbps != null

    /** The cap in kbps, 0 for the file as it is (no Int? across to Swift). */
    val capKbps: Int get() = maxBitRateKbps ?: 0

    companion object {
        val Original = StreamPlan(maxBitRateKbps = null, format = null)
    }
}

object StreamQualityPolicy {
    /**
     * mp3 on every server (Android `SubsonicServerTraits.FORMAT_MP3`): a
     * constant-bit-rate mp3 maps a time to a byte, so a transcode seek is one
     * `timeOffset` request and the duration is right.
     */
    const val TRANSCODE_FORMAT = "mp3"

    /** The fixed quality a song opened now plays at. */
    fun resolve(settings: StreamQualitySettings, unmetered: Boolean, output: AudioOutputKind): StreamQuality =
        settings.forNetwork(unmetered).forOutput(output)

    /**
     * The file when [quality] is Original (or Auto, unresolved), or when the
     * file is already at or under the cap ([sourceKbps], null or ≤ 0 when
     * unknown): the server would send it as it is, and the file seeks by byte.
     */
    fun plan(quality: StreamQuality, sourceKbps: Int?): StreamPlan {
        val cap = quality.maxBitRateKbps ?: return StreamPlan.Original
        val source = sourceKbps?.takeIf { it > 0 }
        if (source != null && source <= cap) return StreamPlan.Original
        return StreamPlan(cap, TRANSCODE_FORMAT)
    }

    /** [plan] with a known-or-not source rate as a plain Int (≤ 0 = unknown), for Swift. */
    fun planKbps(quality: StreamQuality, sourceKbps: Int): StreamPlan = plan(quality, sourceKbps)

    /** [resolve] then [plan]. */
    fun planFor(
        settings: StreamQualitySettings,
        unmetered: Boolean,
        output: AudioOutputKind,
        sourceKbps: Int?,
    ): StreamPlan = plan(resolve(settings, unmetered, output), sourceKbps)
}
