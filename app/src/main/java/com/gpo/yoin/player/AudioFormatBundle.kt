package com.gpo.yoin.player

import android.os.Bundle
import androidx.media3.common.C
import androidx.media3.common.Format
import com.gpo.yoin.data.model.AudioFormat

/** [AudioFormat] across a MediaItem's metadata extras; absent fields stay absent. */
internal fun AudioFormat.toBundle(): Bundle = Bundle().apply {
    codec?.let { putString(KEY_CODEC, it) }
    bitRateKbps?.let { putInt(KEY_BIT_RATE, it) }
    bitDepth?.let { putInt(KEY_BIT_DEPTH, it) }
    sampleRateHz?.let { putInt(KEY_SAMPLE_RATE, it) }
    channels?.let { putInt(KEY_CHANNELS, it) }
}

internal fun Bundle.toAudioFormat(): AudioFormat = AudioFormat(
    codec = getString(KEY_CODEC),
    bitRateKbps = intOrNull(KEY_BIT_RATE),
    bitDepth = intOrNull(KEY_BIT_DEPTH),
    sampleRateHz = intOrNull(KEY_SAMPLE_RATE),
    channels = intOrNull(KEY_CHANNELS),
)

private fun Bundle.intOrNull(key: String): Int? = if (containsKey(key)) getInt(key) else null

private const val KEY_CODEC = "codec"
private const val KEY_BIT_RATE = "kbps"
private const val KEY_BIT_DEPTH = "depth"
private const val KEY_SAMPLE_RATE = "hz"
private const val KEY_CHANNELS = "channels"

/** What the local player decodes for the song [mediaId] (a MediaItem's media id). */
data class PlayingFormat(val mediaId: String, val decoded: AudioFormat)

/** A decoder input [Format] as an [AudioFormat]; unknown fields are null. */
internal fun Format.toAudioFormat(): AudioFormat = AudioFormat(
    codec = AudioFormat.codecForMime(sampleMimeType),
    bitRateKbps = (if (averageBitrate != Format.NO_VALUE) averageBitrate else bitrate)
        .takeIf { it != Format.NO_VALUE && it > 0 }
        ?.let { it / 1000 },
    bitDepth = when (pcmEncoding) {
        C.ENCODING_PCM_16BIT -> 16
        C.ENCODING_PCM_24BIT -> 24
        C.ENCODING_PCM_32BIT -> 32
        else -> null
    },
    sampleRateHz = sampleRate.takeIf { it != Format.NO_VALUE && it > 0 },
    channels = channelCount.takeIf { it != Format.NO_VALUE && it > 0 },
)
