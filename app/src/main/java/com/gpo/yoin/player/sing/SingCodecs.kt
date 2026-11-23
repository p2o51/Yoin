package com.gpo.yoin.player.sing

import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.os.Build
import com.gpo.yoin.player.sing.dsp.getAt
import com.gpo.yoin.player.sing.dsp.putAt
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/** A decoded song: planar stereo float, [frames] per channel, at [sampleRate]. */
class DecodedPcm(val left: FloatBuffer, val right: FloatBuffer, val frames: Int, val sampleRate: Int)

/**
 * Decodes a song once to stereo float PCM with MediaExtractor + MediaCodec, into [SingWorkDir] buffers.
 *
 * - Mono plays as L = R. More than two channels fold down to stereo (front pair, centre and rear at
 *   −3 dB, normalised so the fold-down can't clip): 3 = L R C, 4 = L R Ls Rs, 5 = L R C Ls Rs,
 *   6+ = 5.1 (LFE dropped).
 * - The container's encoder delay and padding (MP3 LAME, AAC iTunSMPB) are trimmed, as Media3 trims
 *   them in playback, so the Sing stream lines up with the original sample for sample — and
 *   I = X − V is taken from these very samples, never a second decode (MP3 priming would shift it).
 */
object SingPcmDecoder {
    fun decode(file: File, work: SingWorkDir, cancelled: () -> Boolean = { false }): DecodedPcm {
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(file.absolutePath)
            val track = (0 until extractor.trackCount).firstOrNull {
                extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
            } ?: throw IOException("no audio track")
            extractor.selectTrack(track)
            val format = extractor.getTrackFormat(track)
            val mime = format.getString(MediaFormat.KEY_MIME)!!
            val delay = format.intOr(KEY_ENCODER_DELAY, 0)
            val padding = format.intOr(KEY_ENCODER_PADDING, 0)
            // Ask compressed decoders for float output. For audio/raw (WAV) the key describes the
            // *input* samples, so overriding it would make 16-bit data be read as float (half the
            // frames, NaN garbage) — keep the container's own encoding there.
            if (mime != MediaFormat.MIMETYPE_AUDIO_RAW) {
                format.setInteger(MediaFormat.KEY_PCM_ENCODING, AudioFormat.ENCODING_PCM_FLOAT)
            }

            var sampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            val expected = if (format.containsKey(MediaFormat.KEY_DURATION)) {
                (format.getLong(MediaFormat.KEY_DURATION) * sampleRate / 1_000_000L * 102 / 100 + 4096).toInt()
            } else {
                GrowingFloats.INITIAL
            }
            val left = GrowingFloats(work, expected)
            val right = GrowingFloats(work, expected)
            var channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            var floatOut = false
            var skip = delay
            // A codec buffer's samples, read in bulk (a get per sample from the codec's direct buffer
            // goes through a JNI-style call on ART).
            var floats = FloatArray(0)
            var shorts = ShortArray(0)

            runCodec(
                codec = MediaCodec.createDecoderByType(mime).apply { configure(format, null, null, 0) },
                extractor = extractor,
                cancelled = cancelled,
                onFormat = { out ->
                    sampleRate = out.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                    channels = out.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                    floatOut = out.intOr(MediaFormat.KEY_PCM_ENCODING, AudioFormat.ENCODING_PCM_16BIT) == AudioFormat.ENCODING_PCM_FLOAT
                },
                onOutput = { buffer ->
                    val bytes = if (floatOut) 4 else 2
                    val frames = buffer.remaining() / (bytes * channels)
                    val samples = frames * channels
                    if (floatOut) {
                        if (floats.size < samples) floats = FloatArray(samples)
                        buffer.asFloatBuffer().get(floats, 0, samples)
                    } else {
                        if (shorts.size < samples) shorts = ShortArray(samples)
                        buffer.asShortBuffer().get(shorts, 0, samples)
                    }
                    for (f in 0 until frames) {
                        if (skip > 0) {
                            skip--
                            continue
                        }
                        val at = f * channels
                        fun s(c: Int): Float = if (floatOut) floats[at + c] else shorts[at + c] / 32768f
                        val l: Float
                        val r: Float
                        when (channels) {
                            1 -> {
                                l = s(0)
                                r = l
                            }
                            2 -> {
                                l = s(0)
                                r = s(1)
                            }
                            else -> foldDown(channels, ::s).let { (a, b) ->
                                l = a
                                r = b
                            }
                        }
                        left.add(if (l.isFinite()) l else 0f)
                        right.add(if (r.isFinite()) r else 0f)
                    }
                },
            )
            val frames = (left.size - padding).coerceAtLeast(0)
            if (frames == 0) throw IOException("no audio decoded")
            return DecodedPcm(left.finish(), right.finish(), frames, sampleRate)
        } finally {
            extractor.release()
        }
    }

    internal fun foldDown(channels: Int, s: (Int) -> Float): Pair<Float, Float> = when (channels) {
        1 -> s(0).let { it to it }
        2 -> s(0) to s(1)
        3 -> norm(s(0) + M3 * s(2), 1f + M3) to norm(s(1) + M3 * s(2), 1f + M3)
        4 -> norm(s(0) + M3 * s(2), 1f + M3) to norm(s(1) + M3 * s(3), 1f + M3)
        5 -> norm(s(0) + M3 * s(2) + M3 * s(3), 1f + 2 * M3) to norm(s(1) + M3 * s(2) + M3 * s(4), 1f + 2 * M3)
        else -> norm(s(0) + M3 * s(2) + M3 * s(4), 1f + 2 * M3) to norm(s(1) + M3 * s(2) + M3 * s(5), 1f + 2 * M3)
    }

    private fun norm(v: Float, sum: Float) = v / sum

    private const val M3 = 0.70710677f

    // Framework keys MediaExtractor sets from LAME / iTunSMPB headers (no public constants before API 30's).
    private const val KEY_ENCODER_DELAY = "encoder-delay"
    private const val KEY_ENCODER_PADDING = "encoder-padding"
}

/**
 * Encodes [frames] of 4-channel PCM into an AAC-LC .m4a (MediaCodec + MediaMuxer MPEG-4), channels in
 * the order [fill] writes them: [I_L, I_R, V_L, V_R]. ~256 kbps for the four. MediaMuxer writes the
 * moov box last; that's fine for a local file (the Lyra server's copy is faststart for streaming).
 */
object QuadAacEncoder {
    const val BIT_RATE = 256_000
    const val CHANNELS = 4

    /** AAC's highest rate; a hi-res source is brought down to 48 kHz before separation. */
    const val MAX_RATE = 48_000

    /** [fill] writes frames [from, from + count) as interleaved 16-bit samples into the buffer. */
    fun interface Source {
        fun fill(from: Int, count: Int, out: ByteBuffer)
    }

    fun encode(out: File, sampleRate: Int, frames: Int, source: Source, cancelled: () -> Boolean = { false }) {
        val format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, sampleRate, CHANNELS).apply {
            setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            setInteger(MediaFormat.KEY_BIT_RATE, BIT_RATE)
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, BLOCK_FRAMES * CHANNELS * 2)
        }
        val codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
        codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        val muxer = MediaMuxer(out.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        var track = -1
        var started = false
        try {
            codec.start()
            val info = MediaCodec.BufferInfo()
            var fed = 0
            var inputDone = false
            var outputDone = false
            while (!outputDone) {
                if (cancelled()) throw kotlinx.coroutines.CancellationException("encode cancelled")
                if (!inputDone) {
                    val index = codec.dequeueInputBuffer(TIMEOUT_US)
                    if (index >= 0) {
                        val buffer = codec.getInputBuffer(index)!!.order(ByteOrder.LITTLE_ENDIAN)
                        buffer.clear()
                        val count = minOf(BLOCK_FRAMES, frames - fed, buffer.capacity() / (CHANNELS * 2))
                        val pts = fed.toLong() * 1_000_000L / sampleRate
                        if (count <= 0) {
                            codec.queueInputBuffer(index, 0, 0, pts, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            source.fill(fed, count, buffer)
                            codec.queueInputBuffer(index, 0, count * CHANNELS * 2, pts, 0)
                            fed += count
                        }
                    }
                }
                val index = codec.dequeueOutputBuffer(info, TIMEOUT_US)
                when {
                    index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        track = muxer.addTrack(codec.outputFormat)
                        muxer.start()
                        started = true
                    }
                    index >= 0 -> {
                        val buffer = codec.getOutputBuffer(index)!!
                        val config = info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0
                        if (!config && info.size > 0 && started) {
                            buffer.position(info.offset).limit(info.offset + info.size)
                            muxer.writeSampleData(track, buffer, info)
                        }
                        codec.releaseOutputBuffer(index, false)
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outputDone = true
                    }
                }
            }
        } finally {
            runCatching { codec.stop() }
            codec.release()
            if (started) runCatching { muxer.stop() }
            muxer.release()
        }
    }

    /** I = X − V and V, as 16-bit [I_L, I_R, V_L, V_R] frames. */
    fun singSource(mix: DecodedPcm, vocalLeft: FloatBuffer, vocalRight: FloatBuffer) = Source { from, count, out ->
        for (i in from until from + count) {
            val vL = vocalLeft.get(i)
            val vR = vocalRight.get(i)
            out.putShort(pcm16(mix.left.get(i) - vL))
            out.putShort(pcm16(mix.right.get(i) - vR))
            out.putShort(pcm16(vL))
            out.putShort(pcm16(vR))
        }
    }

    private fun pcm16(v: Float): Short =
        if (v.isFinite()) (v * 32767f).roundToInt().coerceIn(-32768, 32767).toShort() else 0

    private const val BLOCK_FRAMES = 4096
    internal const val TIMEOUT_US = 10_000L
}

/**
 * Which decoded channel carries which encoded one, through this device's AAC encoder and decoder,
 * configured as Media3 configures its decoder. AAC has no plain 4.0 "quad" layout (channel
 * configuration 4 is C, L/R, rear centre), so an encoder–decoder pair may hand the channels back
 * reordered — or folded to stereo. Four tones (one per channel) go through, and each decoded
 * channel is matched to the tone it carries.
 */
object QuadChannelOrderProbe {
    val TONES_HZ = doubleArrayOf(440.0, 1_000.0, 2_200.0, 3_700.0)

    sealed interface Result {
        /** [map]: decoded channel index of I_L, I_R, V_L, V_R — [SingMixControl.channelMap]. */
        data class Quad(val map: IntArray) : Result {
            override fun equals(other: Any?) = other is Quad && other.map.contentEquals(map)

            override fun hashCode() = map.contentHashCode()
        }

        /** The decoder gave back [channels] channels (folded down), so Sing can't play on this device. */
        data class NotQuad(val channels: Int) : Result
    }

    fun run(dir: File, sampleRate: Int = 44_100): Result {
        val file = File.createTempFile("sing-probe", ".m4a", dir)
        try {
            val frames = sampleRate / 2
            QuadAacEncoder.encode(file, sampleRate, frames, { from, count, out ->
                for (i in from until from + count) {
                    for (c in 0 until 4) {
                        val v = 0.3 * sin(2.0 * PI * TONES_HZ[c] * i / sampleRate)
                        out.putShort((v * 32767).roundToInt().toShort())
                    }
                }
            })
            val decoded = decodeRaw(file)
            if (decoded.size != 4) return Result.NotQuad(decoded.size)
            return Result.Quad(matchTones(decoded, sampleRate))
        } finally {
            file.delete()
        }
    }

    /** For each tone, the decoded channel where it's strongest. */
    fun matchTones(channels: List<FloatArray>, sampleRate: Int): IntArray = IntArray(TONES_HZ.size) { tone ->
        channels.indices.maxBy { goertzelPower(channels[it], TONES_HZ[tone], sampleRate) }
    }

    fun goertzelPower(x: FloatArray, hz: Double, sampleRate: Int): Double {
        val w = 2.0 * PI * hz / sampleRate
        val coeff = 2.0 * cos(w)
        var s1 = 0.0
        var s2 = 0.0
        for (v in x) {
            val s0 = v + coeff * s1 - s2
            s2 = s1
            s1 = s0
        }
        return sqrt(s1 * s1 + s2 * s2 - coeff * s1 * s2)
    }

    /** Every decoded channel as its own array, no fold-down. */
    private fun decodeRaw(file: File): List<FloatArray> {
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(file.absolutePath)
            extractor.selectTrack(0)
            val format = extractor.getTrackFormat(0)
            // As MediaCodecAudioRenderer asks (Media3 1.11): all channels from API 32.
            if (Build.VERSION.SDK_INT >= 32) format.setInteger(MediaFormat.KEY_MAX_OUTPUT_CHANNEL_COUNT, 99)
            var channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            var floatOut = false
            val samples = ArrayList<FloatArray>()
            runCodec(
                codec = MediaCodec.createDecoderByType(format.getString(MediaFormat.KEY_MIME)!!).apply { configure(format, null, null, 0) },
                extractor = extractor,
                cancelled = { false },
                onFormat = { out ->
                    channels = out.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                    floatOut = out.intOr(MediaFormat.KEY_PCM_ENCODING, AudioFormat.ENCODING_PCM_16BIT) == AudioFormat.ENCODING_PCM_FLOAT
                },
                onOutput = { buffer ->
                    val bytes = if (floatOut) 4 else 2
                    val frames = buffer.remaining() / (bytes * channels)
                    val base = buffer.position()
                    for (f in 0 until frames) {
                        samples += FloatArray(channels) { c ->
                            val at = base + (f * channels + c) * bytes
                            if (floatOut) buffer.getFloat(at) else buffer.getShort(at) / 32768f
                        }
                    }
                },
            )
            return List(channels) { c -> FloatArray(samples.size) { samples[it].getOrElse(c) { 0f } } }
        } finally {
            extractor.release()
        }
    }
}

/** Feeds [extractor]'s selected track through [codec] (configured, not started) and hands out PCM. */
private fun runCodec(
    codec: MediaCodec,
    extractor: MediaExtractor,
    cancelled: () -> Boolean,
    onFormat: (MediaFormat) -> Unit,
    onOutput: (ByteBuffer) -> Unit,
) {
    try {
        codec.start()
        val info = MediaCodec.BufferInfo()
        var inputDone = false
        var outputDone = false
        while (!outputDone) {
            if (cancelled()) throw kotlinx.coroutines.CancellationException("decode cancelled")
            if (!inputDone) {
                val index = codec.dequeueInputBuffer(QuadAacEncoder.TIMEOUT_US)
                if (index >= 0) {
                    val buffer = codec.getInputBuffer(index)!!
                    val size = extractor.readSampleData(buffer, 0)
                    if (size < 0) {
                        codec.queueInputBuffer(index, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                        inputDone = true
                    } else {
                        codec.queueInputBuffer(index, 0, size, extractor.sampleTime, 0)
                        extractor.advance()
                    }
                }
            }
            val index = codec.dequeueOutputBuffer(info, QuadAacEncoder.TIMEOUT_US)
            when {
                index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> onFormat(codec.outputFormat)
                index >= 0 -> {
                    if (info.size > 0) {
                        val buffer = codec.getOutputBuffer(index)!!.order(ByteOrder.LITTLE_ENDIAN)
                        buffer.position(info.offset).limit(info.offset + info.size)
                        onOutput(buffer)
                    }
                    codec.releaseOutputBuffer(index, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outputDone = true
                }
            }
        }
    } finally {
        runCatching { codec.stop() }
        codec.release()
    }
}

private fun MediaFormat.intOr(key: String, default: Int): Int = if (containsKey(key)) getInteger(key) else default

/**
 * Appends floats to mapped buffers, doubling (and copying) as it fills. Values gather in a heap array
 * and go into the mapping a block at a time.
 */
private class GrowingFloats(private val work: SingWorkDir, initial: Int) {
    private var buffer: FloatBuffer = work.floats(initial.coerceAtLeast(4096))
    private val pending = FloatArray(STAGE)
    private var pendingCount = 0

    /** Values added so far (flushed or not). */
    var size = 0
        private set

    fun add(v: Float) {
        pending[pendingCount++] = v
        size++
        if (pendingCount == STAGE) flush()
    }

    fun finish(): FloatBuffer {
        flush()
        return buffer
    }

    private fun flush() {
        val flushed = size - pendingCount
        if (size > buffer.capacity()) {
            var capacity = buffer.capacity()
            while (capacity < size) capacity *= 2
            val bigger = work.floats(capacity)
            val copy = FloatArray(STAGE)
            var at = 0
            while (at < flushed) {
                val count = minOf(STAGE, flushed - at)
                buffer.getAt(at, copy, 0, count)
                bigger.putAt(at, copy, 0, count)
                at += count
            }
            work.release(buffer)
            buffer = bigger
        }
        buffer.putAt(flushed, pending, 0, pendingCount)
        pendingCount = 0
    }

    companion object {
        /** A minute of 44.1 kHz audio. */
        const val INITIAL = 44_100 * 60

        /** Values gathered before they go into the mapping. */
        private const val STAGE = 4096
    }
}
