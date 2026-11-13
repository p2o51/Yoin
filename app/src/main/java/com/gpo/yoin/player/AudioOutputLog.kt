package com.gpo.yoin.player

import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.Tracks
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DecoderReuseEvaluation
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.audio.AudioSink

/**
 * What the local player decodes with and sends to the output, in logcat
 * (tag YoinAudio): which decoder a song gets (a Dolby E-AC-3 decoder may
 * render JOC objects or only the 5.1 bed), the input format, and the output's
 * encoding and channel mask. For QA on a device, and in [PlayTrace] for the exported log.
 */
@OptIn(UnstableApi::class)
internal object AudioOutputLog : AnalyticsListener {
    private const val TAG = "YoinAudio"

    private fun line(message: String) {
        Log.d(TAG, message)
        PlayTrace.log("audio $message")
    }

    override fun onAudioDecoderInitialized(
        eventTime: AnalyticsListener.EventTime,
        decoderName: String,
        initializedTimestampMs: Long,
        initializationDurationMs: Long,
    ) {
        line("decoder=$decoderName")
    }

    override fun onAudioInputFormatChanged(
        eventTime: AnalyticsListener.EventTime,
        format: Format,
        decoderReuseEvaluation: DecoderReuseEvaluation?,
    ) {
        line(
            "input mime=${format.sampleMimeType} codecs=${format.codecs} channels=${format.channelCount} " +
                "rate=${format.sampleRate} bitrate=${format.bitrate} reuse=${decoderReuseEvaluation?.result}",
        )
    }

    override fun onAudioTrackInitialized(eventTime: AnalyticsListener.EventTime, audioTrackConfig: AudioSink.AudioTrackConfig) {
        line(
            "output encoding=${audioTrackConfig.encoding} rate=${audioTrackConfig.sampleRate} " +
                "channelMask=0x${Integer.toHexString(audioTrackConfig.channelConfig)} " +
                "channels=${Integer.bitCount(audioTrackConfig.channelConfig)} offload=${audioTrackConfig.offload}",
        )
    }

    override fun onTracksChanged(eventTime: AnalyticsListener.EventTime, tracks: Tracks) {
        tracks.groups.filter { it.type == C.TRACK_TYPE_AUDIO }.forEach { group ->
            (0 until group.length).forEach { i ->
                val format = group.getTrackFormat(i)
                line(
                    "track mime=${format.sampleMimeType} channels=${format.channelCount} " +
                        "support=${C.getFormatSupportString(group.getTrackSupport(i))} selected=${group.isTrackSelected(i)}",
                )
            }
        }
    }

    override fun onAudioUnderrun(
        eventTime: AnalyticsListener.EventTime,
        bufferSize: Int,
        bufferSizeMs: Long,
        elapsedSinceLastFeedMs: Long,
    ) {
        line("underrun buffer=${bufferSizeMs}ms sinceFeed=${elapsedSinceLastFeedMs}ms")
    }
}
