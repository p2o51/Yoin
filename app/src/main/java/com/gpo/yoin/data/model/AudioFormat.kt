package com.gpo.yoin.data.model

/**
 * How a song's audio is encoded, as far as the service or the decoder says.
 * Every field is optional: a plain Subsonic server sends only the suffix and
 * the bit rate, OpenSubsonic adds depth, rate and channels. Only Subsonic
 * fills it on a [Track]; Spotify and Apple Music never say.
 *
 * [codec] is a lower-case name ("flac", "mp3", "opus", "aac", "alac").
 */
data class AudioFormat(
    val codec: String?,
    val bitRateKbps: Int? = null,
    val bitDepth: Int? = null,
    val sampleRateHz: Int? = null,
    val channels: Int? = null,
) {
    val isLossless: Boolean get() = codec in LosslessCodecs

    companion object {
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
            else -> null
        }
    }
}
