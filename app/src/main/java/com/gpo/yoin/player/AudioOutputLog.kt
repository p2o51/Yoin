package com.gpo.yoin.player

import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.Format
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DecoderReuseEvaluation
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.audio.AudioSink

/**
 * What the local player decodes with and sends to the output, in logcat
 * (tag YoinAudio): which decoder a song gets (a Dolby E-AC-3 decoder may
 * render JOC objects or only the 5.1 bed), the input format, and the output's
 * encoding and channel mask. For QA on a device; nothing reads it.
 */
@OptIn(UnstableApi::class)
internal object AudioOutputLog : AnalyticsListener {
    private const val TAG = "YoinAudio"

    override fun onAudioDecoderInitialized(
        eventTime: AnalyticsListener.EventTime,
        decoderName: String,
        initializedTimestampMs: Long,
        initializationDurationMs: Long,
    ) {
        Log.d(TAG, "decoder=$decoderName")
    }

    override fun onAudioInputFormatChanged(
        eventTime: AnalyticsListener.EventTime,
        format: Format,
        decoderReuseEvaluation: DecoderReuseEvaluation?,
    ) {
        Log.d(
            TAG,
            "input mime=${format.sampleMimeType} codecs=${format.codecs} channels=${format.channelCount} " +
                "rate=${format.sampleRate} bitrate=${format.bitrate} reuse=${decoderReuseEvaluation?.result}",
        )
    }

    override fun onAudioTrackInitialized(eventTime: AnalyticsListener.EventTime, audioTrackConfig: AudioSink.AudioTrackConfig) {
        Log.d(
            TAG,
            "output encoding=${audioTrackConfig.encoding} rate=${audioTrackConfig.sampleRate} " +
                "channelMask=0x${Integer.toHexString(audioTrackConfig.channelConfig)} " +
                "channels=${Integer.bitCount(audioTrackConfig.channelConfig)} offload=${audioTrackConfig.offload}",
        )
    }

    override fun onAudioUnderrun(
        eventTime: AnalyticsListener.EventTime,
        bufferSize: Int,
        bufferSizeMs: Long,
        elapsedSinceLastFeedMs: Long,
    ) {
        Log.d(TAG, "underrun buffer=${bufferSizeMs}ms sinceFeed=${elapsedSinceLastFeedMs}ms")
    }
}
