package com.gpo.yoin.player

import android.content.Context
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Timeline
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import com.gpo.yoin.data.model.ReplayGain
import java.nio.ByteBuffer
import java.util.Locale
import kotlin.math.roundToInt

/*
 * Volume leveling in the audio sink (owner task 2026-11-04). Media3 1.11.1
 * reads ReplayGain nowhere (Mp3InfoReplayGain is only metadata), so Yoin adds
 * one processor to DefaultAudioSink's chain. It does not touch player.volume:
 * in gapless playback the end of one song is still in the buffers when the
 * player moves to the next, so a volume change there lands hundreds of
 * milliseconds early or late.
 *
 * The sink tells its processors which song the samples belong to: when the
 * decoder moves on to the next song, the renderer reconfigures the sink with
 * that song's period, the sink plays out what it holds of the last one
 * (through this processor, at the last one's gain) and flushes its processors
 * with the new song's timeline and period uid (AudioProcessor.StreamMetadata).
 * The gain changes in that flush, so it changes on the first sample of the
 * new song. A seek flushes the same way.
 *
 * Out of reach, and left as they are: a DLNA speaker plays the url itself,
 * and passthrough (E-AC-3 or AC-3 sent undecoded over HDMI or to a receiver)
 * never reaches the processors as PCM.
 */

/**
 * Multiplies every sample by the gain of the song it belongs to. [gainFor]
 * names a song's [ReplayGain] (null: untagged), [settings] the listener's
 * choice; both are read on the playback thread, [settings] for every buffer,
 * so a change in Settings applies at once.
 */
@OptIn(UnstableApi::class)
internal class ReplayGainAudioProcessor(
    private val settings: () -> ReplayGainSettings,
    private val gainFor: (MediaItem) -> ReplayGain?,
) : BaseAudioProcessor() {
    private var mediaId: String? = null
    private var replayGain: ReplayGain? = null
    private var applied: AppliedReplayGain = AppliedReplayGain.Unity

    /** Frames through this processor since the player made it, and since the current song began. */
    private var totalFrames = 0L
    private var songFrames = 0L

    private val period = Timeline.Period()
    private val window = Timeline.Window()

    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat =
        when (inputAudioFormat.encoding) {
            // The sink turns other PCM into one of these before the chain.
            C.ENCODING_PCM_16BIT, C.ENCODING_PCM_FLOAT -> inputAudioFormat
            else -> AudioProcessor.AudioFormat.NOT_SET
        }

    override fun onFlush(streamMetadata: AudioProcessor.StreamMetadata) {
        val item = mediaItemOf(streamMetadata)
        val previous = mediaId
        val previousFrames = songFrames
        mediaId = item?.mediaId
        replayGain = item?.let(gainFor)
        songFrames = 0
        applied = appliedReplayGain(replayGain, settings())
        Log.d(
            TAG,
            "song=${mediaId ?: "?"} gain=${applied.describe()} from output frame $totalFrames " +
                "(stream offset ${streamMetadata.positionOffsetUs} µs; " +
                "before: song=${previous ?: "-"} after $previousFrames frames)",
        )
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val position = inputBuffer.position()
        val limit = inputBuffer.limit()
        val size = limit - position
        if (size == 0) return
        val now = appliedReplayGain(replayGain, settings())
        if (now != applied) {
            applied = now
            Log.d(TAG, "song=${mediaId ?: "?"} gain=${now.describe()} from output frame $totalFrames (setting changed)")
        }
        val frames = size / inputAudioFormat.bytesPerFrame
        totalFrames += frames
        songFrames += frames
        val output = replaceOutputBuffer(size)
        val gain = now.linear
        if (gain == 1f) {
            output.put(inputBuffer)
        } else if (inputAudioFormat.encoding == C.ENCODING_PCM_16BIT) {
            var i = position
            while (i < limit) {
                val scaled = (inputBuffer.getShort(i) * gain).roundToInt()
                output.putShort(scaled.coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort())
                i += 2
            }
            inputBuffer.position(limit)
        } else {
            var i = position
            while (i < limit) {
                output.putFloat((inputBuffer.getFloat(i) * gain).coerceIn(-1f, 1f))
                i += 4
            }
            inputBuffer.position(limit)
        }
        output.flip()
    }

    override fun onReset() {
        mediaId = null
        replayGain = null
        applied = AppliedReplayGain.Unity
        totalFrames = 0
        songFrames = 0
    }

    private fun mediaItemOf(metadata: AudioProcessor.StreamMetadata): MediaItem? {
        val uid = metadata.periodUid ?: return null
        val timeline = metadata.timeline
        if (timeline.isEmpty) return null
        if (timeline.getIndexOfPeriod(uid) == C.INDEX_UNSET) return null
        timeline.getPeriodByUid(uid, period)
        return timeline.getWindow(period.windowIndex, window).mediaItem
    }

    private fun AppliedReplayGain.describe(): String = when (source) {
        ReplayGainSource.None -> "none (untagged or off)"
        else -> String.format(
            Locale.ROOT,
            "%+.2f dB ×%.4f (%s%s)",
            db,
            linear,
            source.name,
            if (peakLimited) ", peak-limited" else "",
        )
    }

    companion object {
        const val TAG = "YoinReplayGain"
    }
}

/**
 * Media3's renderers with [processor] in the audio sink's chain; the sink is
 * otherwise built as DefaultRenderersFactory builds it.
 */
@OptIn(UnstableApi::class)
internal fun replayGainRenderersFactory(context: Context, processor: AudioProcessor): DefaultRenderersFactory =
    object : DefaultRenderersFactory(context) {
        override fun buildAudioSink(
            context: Context,
            enableFloatOutput: Boolean,
            enableAudioOutputPlaybackParams: Boolean,
        ): AudioSink = DefaultAudioSink.Builder(context)
            .setEnableFloatOutput(enableFloatOutput)
            .setEnableAudioOutputPlaybackParameters(enableAudioOutputPlaybackParams)
            .setAudioProcessors(arrayOf(processor))
            .build()
    }
