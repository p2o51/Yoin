package com.gpo.yoin.ui.component

import com.gpo.yoin.data.model.AudioFormat
import kotlin.math.roundToInt

/*
 * The format tags (owner task 2026-11-03): short labels, each its own tag,
 * never joined with "·" — "FLAC 24/96", "MP3 320", "5.1". Codec names and
 * numbers read the same in every language, so the tags are not translated.
 */

/**
 * How a codec is written on a tag: "flac" → "FLAC", "opus" → "Opus".
 * E-AC-3 JOC is "Dolby Atmos", in words only: the logo needs a licence, and
 * the trademark reads in English in every language.
 */
fun codecLabel(codec: String): String = when (codec.lowercase()) {
    "opus" -> "Opus"
    "vorbis" -> "Vorbis"
    "wavpack" -> "WavPack"
    "ac3" -> "AC-3"
    "eac3" -> "E-AC-3"
    "ac4" -> "AC-4"
    AudioFormat.CODEC_EAC3_JOC -> "Dolby Atmos"
    else -> codec.uppercase()
}

/** The channel layout a count usually is: 6 → "5.1". Null for mono and stereo. */
fun channelLayoutLabel(channels: Int?): String? = when (channels) {
    null, 0, 1, 2 -> null
    3 -> "2.1"
    4 -> "4.0"
    5 -> "5.0"
    6 -> "5.1"
    7 -> "6.1"
    8 -> "7.1"
    else -> "${channels}ch"
}

/**
 * The codec tag of [format]: lossless as depth/kHz ("FLAC 24/96", "FLAC 44.1"
 * without a depth), lossy as its bit rate ("MP3 320"), else the codec alone.
 * Null when [format] names no codec.
 */
fun codecTag(format: AudioFormat): String? {
    val codec = format.codec?.let(::codecLabel) ?: return null
    if (format.isDolbyAtmos) return codec
    if (format.isLossless) {
        val khz = format.sampleRateHz?.let(::khzLabel)
        return when {
            format.bitDepth != null && khz != null -> "$codec ${format.bitDepth}/$khz"
            khz != null -> "$codec $khz"
            else -> codec
        }
    }
    return format.bitRateKbps?.let { "$codec $it" } ?: codec
}

/** Every tag of [format], codec first: ["FLAC 16/44.1", "5.1"]; Dolby Atmos alone. */
fun audioFormatTags(format: AudioFormat?): List<String> {
    format ?: return emptyList()
    val layout = if (format.isDolbyAtmos) null else channelLayoutLabel(format.channels)
    return listOfNotNull(codecTag(format), layout)
}

/** 44100 → "44.1", 96000 → "96". */
fun khzLabel(hz: Int): String {
    val tenths = (hz / 100.0).roundToInt()
    return if (tenths % 10 == 0) (tenths / 10).toString() else "${tenths / 10}.${tenths % 10}"
}

/**
 * The tags of what is playing. [source] is the file as the server describes
 * it, [decoded] what the player's decoder reports (null until it does, and on
 * a speaker that plays the url itself), [transcodeKbps] and
 * [transcodeFormat] the transcode the player asked for (null for the file).
 *
 * A transcode shows what arrives ("Opus 192"); the file's own tags are the
 * caller's to show beside it ([transcodedFrom]). Otherwise the server's
 * description, with the decoder's channel count, which is what actually
 * plays; where the decoder names another codec than the server, the decoder
 * wins (the server transcoded on its own).
 */
fun playingFormatTags(
    source: AudioFormat?,
    decoded: AudioFormat?,
    transcodeFormat: String?,
    transcodeKbps: Int?,
): List<String> {
    if (transcodeKbps != null) {
        val arriving = AudioFormat(
            codec = decoded?.codec ?: transcodeFormat,
            bitRateKbps = transcodeKbps,
            channels = decoded?.channels,
        )
        return audioFormatTags(arriving)
    }
    if (source == null) return audioFormatTags(decoded)
    if (decoded == null) return audioFormatTags(source)
    if (decoded.codec != null && source.codec != null && decoded.codec != source.codec) {
        return audioFormatTags(decoded)
    }
    return audioFormatTags(
        source.copy(
            codec = source.codec ?: decoded.codec,
            bitDepth = source.bitDepth ?: decoded.bitDepth,
            sampleRateHz = source.sampleRateHz ?: decoded.sampleRateHz,
            channels = decoded.channels ?: source.channels,
        ),
    )
}

/** The file's codec tag when [playingFormatTags] shows a transcode of it; else null. */
fun transcodedFrom(source: AudioFormat?, transcodeKbps: Int?): String? =
    if (transcodeKbps == null || source == null) null else codecTag(source)
