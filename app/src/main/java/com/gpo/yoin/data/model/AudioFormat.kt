package com.gpo.yoin.data.model

/**
 * How a song's audio is encoded, as far as the service or the decoder says.
 * Every field is optional: a plain Subsonic server sends only the suffix and
 * the bit rate, OpenSubsonic adds depth, rate and channels. Only Subsonic
 * fills it on a [Track]; Spotify and Apple Music never say.
 *
 * [codec] is a lower-case name ("flac", "mp3", "opus", "aac", "alac",
 * "eac3-joc" for Dolby Atmos in E-AC-3).
 */
data class AudioFormat(
    val codec: String?,
    val bitRateKbps: Int? = null,
    val bitDepth: Int? = null,
    val sampleRateHz: Int? = null,
    val channels: Int? = null,
) {
    val isLossless: Boolean get() = codec in LosslessCodecs

    /**
     * E-AC-3 with joint object coding: Dolby Atmos. Its channel count is the
     * base layer's (or whatever the decoder renders to), not the content's
     * layout, so it gets no channel tag.
     */
    val isDolbyAtmos: Boolean get() = codec == CODEC_EAC3_JOC

    companion object {
        const val CODEC_EAC3_JOC = "eac3-joc"

        private val LosslessCodecs = setOf("flac", "alac", "wav", "aiff", "ape", "wavpack", "dsd")

        /**
         * The codec a file's [suffix] holds; [bitDepth] tells an ALAC `.m4a`
         * from an AAC one (only lossless files have a depth). Null when the
         * suffix names no audio codec Yoin knows.
         */
        fun codecForSuffix(suffix: String?, bitDepth: Int? = null): String? =
            when (suffix?.trim()?.lowercase()) {
                null, "" -> null
                "flac", "fla" -> "flac"
                "mp3" -> "mp3"
                "opus" -> "opus"
                "ogg", "oga" -> "vorbis"
                "aac", "adts" -> "aac"
                "m4a", "mp4", "m4b" -> if ((bitDepth ?: 0) > 0) "alac" else "aac"
                "alac" -> "alac"
                "wav", "wave" -> "wav"
                "aif", "aiff", "aifc" -> "aiff"
                "ape" -> "ape"
                "wv" -> "wavpack"
                "dsf", "dff" -> "dsd"
                "wma" -> "wma"
                else -> suffix.trim().lowercase()
            }

        /** Whether [suffix] is an MP4 container, which can hold AAC, ALAC or E-AC-3 alike. */
        fun isMp4Suffix(suffix: String?): Boolean = suffix?.trim()?.lowercase() in Mp4Suffixes

        private val Mp4Suffixes = setOf("m4a", "mp4", "m4b")

        /** The codec a decoder's MIME type names, e.g. "audio/flac" → "flac". */
        fun codecForMime(mime: String?): String? = when (mime?.lowercase()) {
            null, "" -> null
            "audio/flac" -> "flac"
            "audio/mpeg", "audio/mpeg-l2", "audio/mpeg-l1" -> "mp3"
            "audio/opus" -> "opus"
            "audio/vorbis" -> "vorbis"
            "audio/mp4a-latm", "audio/aac" -> "aac"
            "audio/alac" -> "alac"
            "audio/raw", "audio/wav" -> "wav"
            "audio/ac3" -> "ac3"
            "audio/eac3" -> "eac3"
            "audio/eac3-joc" -> CODEC_EAC3_JOC
            // AC-4 may carry Atmos too, but the MIME type doesn't say: plain AC-4.
            "audio/ac4" -> "ac4"
            else -> null
        }
    }
}
