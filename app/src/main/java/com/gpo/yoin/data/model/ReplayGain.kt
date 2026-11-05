package com.gpo.yoin.data.model

/**
 * A song's ReplayGain as the service reports it (OpenSubsonic `replayGain`):
 * gains in dB, peaks as linear sample values (1.0 = full scale). Every field is
 * optional; only Subsonic fills it on a [Track]. Spotify and Apple Music level
 * the volume themselves.
 *
 * [baseGainDb] (an Opus header's output gain) is applied by the decoder
 * already, and [fallbackGainDb] is the server's guess for untagged songs:
 * both are kept as reported but never applied (an untagged song plays as it
 * is, owner 2026-11-04).
 */
data class ReplayGain(
    val trackGainDb: Float? = null,
    val albumGainDb: Float? = null,
    val trackPeak: Float? = null,
    val albumPeak: Float? = null,
    val baseGainDb: Float? = null,
    val fallbackGainDb: Float? = null,
) {
    /** Whether the song carries a track or album gain at all. */
    val isTagged: Boolean get() = trackGainDb != null || albumGainDb != null

    companion object {
        /**
         * The server's values with "not tagged" read as absent: a server that
         * always sends the object writes 0 for a missing gain and 0 for its peak
         * (a real peak of 0 is silence), so a 0 dB gain with no peak above 0
         * is no tag. Null when nothing is left.
         */
        fun of(
            trackGainDb: Float?,
            albumGainDb: Float?,
            trackPeak: Float?,
            albumPeak: Float?,
            baseGainDb: Float? = null,
            fallbackGainDb: Float? = null,
        ): ReplayGain? {
            val trackPeakOrNull = trackPeak?.takeIf { it > 0f && it.isFinite() }
            val albumPeakOrNull = albumPeak?.takeIf { it > 0f && it.isFinite() }
            val gain = ReplayGain(
                trackGainDb = trackGainDb?.takeIf { it.isFinite() && (it != 0f || trackPeakOrNull != null) },
                albumGainDb = albumGainDb?.takeIf { it.isFinite() && (it != 0f || albumPeakOrNull != null) },
                trackPeak = trackPeakOrNull,
                albumPeak = albumPeakOrNull,
                baseGainDb = baseGainDb?.takeIf { it.isFinite() && it != 0f },
                fallbackGainDb = fallbackGainDb?.takeIf { it.isFinite() && it != 0f },
            )
            return gain.takeIf { it != ReplayGain() }
        }
    }
}
