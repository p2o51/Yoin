package com.gpo.yoin.player.sing

import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.util.Log
import java.io.IOException
import java.nio.ByteOrder
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred

/**
 * Decodes the original song for on-device Sing as it is needed, into a [SingProgressivePcm] — X as
 * Media3 plays it (encoder delay and padding trimmed; more than two channels folded down, mono doubled;
 * see [SingPcmDecoder]) — straight from [reader], with no copy of the file first.
 *
 * - **Seekable** (lossless with sample-exact positions: FLAC — which the platform extractor hands out
 *   as raw PCM — and WAV): decoding starts where the separation needs it ([wantFrame], from the
 *   listener's place), goes on to the end, then fills in the start; a jump of [wantFrame] into frames
 *   not decoded (a seek) starts a new segment there. A segment's first frame is its first output
 *   buffer's timestamp, which these extractors take from the sample number; where segments meet the
 *   samples are compared ([SingProgressivePcm.Misplaced] if they differ — then the caller decodes
 *   again from the start, [sequentialOnly]).
 * - **Otherwise** (MP3, AAC, …: a lossy decoder's first frames after a seek differ, and MP3 seeks are
 *   estimates): one pass from the start, as before, only streamed — the separation starts as soon as
 *   the frames it needs are in, not when the whole file is.
 *
 * [format] completes once the decoder's output format is known and the buffers are there.
 * [run] blocks (an IO thread) until the whole song is decoded or [run]'s `cancelled` says stop.
 */
class SingStreamDecoder(
    private val reader: SingSourceReader,
    private val work: SingWorkDir,
    private val sequentialOnly: Boolean = false,
) {
    val format = CompletableDeferred<SingProgressivePcm>()

    /** The frame the separation needs next (−1: the listener's place isn't known yet, start at 0). */
    @Volatile var wantFrame: Long = -1

    /** Before the rate is known: where the listener is, in ms. */
    @Volatile var wantMs: Long = 0

    /** Whether this decode seeks (set once the format is read). */
    @Volatile var seekable: Boolean = false
        private set

    /** When [run] had the container's header (System.nanoTime), 0 before. */
    @Volatile var headerNanos: Long = 0
        private set

    @Volatile var segments: Int = 0
        private set

    fun run(cancelled: () -> Boolean) {
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        try {
            extractor.setDataSource(reader.asMediaDataSource())
            reader.failure?.let { throw IOException("reading the song failed", it) }
            headerNanos = System.nanoTime()
            val track = (0 until extractor.trackCount).firstOrNull {
                extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
            } ?: throw IOException("no audio track")
            extractor.selectTrack(track)
            val input = extractor.getTrackFormat(track)
            val mime = input.getString(MediaFormat.KEY_MIME)!!
            val delay = input.intOr(KEY_ENCODER_DELAY, 0)
            val padding = input.intOr(KEY_ENCODER_PADDING, 0)
            val durationUs = if (input.containsKey(MediaFormat.KEY_DURATION)) input.getLong(MediaFormat.KEY_DURATION) else -1L
            val inputRate = input.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            seekable = !sequentialOnly && durationUs > 0 && delay == 0 && padding == 0 &&
                (mime == MediaFormat.MIMETYPE_AUDIO_RAW || mime == MediaFormat.MIMETYPE_AUDIO_FLAC) &&
                reader.size() > 0
            // As SingPcmDecoder: float out of compressed decoders; audio/raw keeps its own encoding.
            if (mime != MediaFormat.MIMETYPE_AUDIO_RAW) input.setInteger(MediaFormat.KEY_PCM_ENCODING, AudioFormat.ENCODING_PCM_FLOAT)
            Log.d(
                TAG,
                "source $mime @ $inputRate Hz, ${if (durationUs > 0) "%.1f s".format(durationUs / 1e6) else "length unknown"}, " +
                    "${reader.size() / 1024} KB; ${if (seekable) "decoding from the listener's place" else "decoding from the start"}",
            )

            val c = MediaCodec.createDecoderByType(mime).apply { configure(input, null, null, 0) }
            codec = c
            c.start()
            val info = MediaCodec.BufferInfo()
            var pcm: SingProgressivePcm? = null
            var channels = input.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            var floatOut = false
            var floats = FloatArray(0)
            var shorts = ShortArray(0)
            var outL = FloatArray(0)
            var outR = FloatArray(0)

            // Segment state.
            var inputDone = false
            var segmentStarted = false // its first output buffer placed it
            var segmentTarget = 0
            var skip = delay

            fun outputFormat(out: MediaFormat) {
                channels = out.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                floatOut = out.intOr(MediaFormat.KEY_PCM_ENCODING, AudioFormat.ENCODING_PCM_16BIT) == AudioFormat.ENCODING_PCM_FLOAT
                val rate = out.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                val current = pcm
                if (current == null) {
                    // Placed by the container's rate: an output at another rate can't be trusted to line up.
                    if (seekable && rate != inputRate) throw SingProgressivePcm.Misplaced("output at $rate Hz, container $inputRate Hz")
                    val estimate = if (durationUs > 0) {
                        (SingProgressivePcm.frameAt(durationUs, rate) - padding).coerceIn(1L, SingVocalTrack.MAX_FRAMES.toLong()).toInt()
                    } else {
                        null
                    }
                    val capacity = (
                        if (estimate != null) estimate + estimate / 20L + 10L * rate + delay else MAX_UNKNOWN_SECONDS * rate
                        ).coerceAtMost(SingVocalTrack.MAX_FRAMES.toLong()).toInt()
                    pcm = SingProgressivePcm(work.floats(capacity), work.floats(capacity), capacity, rate, estimate).also { format.complete(it) }
                } else if (rate != current.sampleRate) {
                    throw IOException("sample rate changed mid-stream: ${current.sampleRate} → $rate")
                }
            }

            /** Points the extractor (and codec) at the next segment; false when the song is all in. */
            fun nextSegment(): Boolean {
                val p = pcm ?: return true // nothing decoded yet: the first segment, from the start
                val want = wantFrame.let { if (it < 0) 0 else it.coerceAtMost(Int.MAX_VALUE.toLong()).toInt() }
                val start = p.nextSegmentStart(if (seekable) want else 0)
                if (start < 0) return false
                if (!seekable) throw IOException("decoded to the end with frames missing")
                extractor.seekTo(start * 1_000_000L / p.sampleRate, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
                c.flush()
                inputDone = false
                segmentStarted = false
                segmentTarget = start
                segments++
                return true
            }

            // The first segment: from the listener's place when seekable (the rate is the container's,
            // the same as the output for these formats), else from the start.
            if (seekable) {
                val wantStart = wantFrame.takeIf { it >= 0 } ?: (wantMs * inputRate / 1000)
                val startFrame = wantStart.coerceIn(0L, Int.MAX_VALUE.toLong()).toInt()
                if (startFrame > 0) extractor.seekTo(startFrame * 1_000_000L / inputRate, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
                segmentTarget = startFrame
            }
            segments = 1

            while (true) {
                if (cancelled()) throw CancellationException("decode cancelled")
                if (!inputDone) {
                    val index = c.dequeueInputBuffer(TIMEOUT_US)
                    if (index >= 0) {
                        val buffer = c.getInputBuffer(index)!!
                        val size = extractor.readSampleData(buffer, 0)
                        if (size < 0) {
                            c.queueInputBuffer(index, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            c.queueInputBuffer(index, 0, size, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }
                val index = c.dequeueOutputBuffer(info, TIMEOUT_US)
                if (index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    outputFormat(c.outputFormat)
                    continue
                }
                if (index < 0) continue
                var segmentOver = false
                if (info.size > 0) {
                    if (pcm == null) outputFormat(c.outputFormat)
                    val p = pcm!!
                    val buffer = c.getOutputBuffer(index)!!.order(ByteOrder.LITTLE_ENDIAN)
                    buffer.position(info.offset).limit(info.offset + info.size)
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
                    if (outL.size < frames) {
                        outL = FloatArray(frames)
                        outR = FloatArray(frames)
                    }
                    var n = 0
                    for (f in 0 until frames) {
                        if (skip > 0) {
                            skip--
                            continue
                        }
                        val at = f * channels
                        fun s(ch: Int): Float = if (floatOut) floats[at + ch] else shorts[at + ch] / 32768f
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
                            else -> SingPcmDecoder.foldDown(channels, ::s).let { (a, b) ->
                                l = a
                                r = b
                            }
                        }
                        outL[n] = if (l.isFinite()) l else 0f
                        outR[n] = if (r.isFinite()) r else 0f
                        n++
                    }
                    if (!segmentStarted) {
                        // Seekable: the buffer's timestamp is its first sample's place. Otherwise the one
                        // pass starts at frame 0 (after the delay skipped above).
                        val start = if (seekable) SingProgressivePcm.frameAt(info.presentationTimeUs, p.sampleRate).toInt() else 0
                        if (start < 0 || start > p.capacity) throw IOException("segment placed at $start")
                        p.beginSegment(start, if (seekable) segmentTarget else 0)
                        segmentStarted = true
                    }
                    if (n > 0 && !p.write(outL, outR, n)) {
                        // Joined a segment decoded before.
                        p.endSegment()
                        segmentOver = true
                    }
                }
                val eos = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                c.releaseOutputBuffer(index, false)
                val p = pcm
                if (eos && !segmentOver) {
                    // A failed read looks like the end of the file to the extractor: it isn't.
                    reader.failure?.let { throw IOException("reading the song failed", it) }
                    if (p == null) throw IOException("no audio decoded")
                    // A segment sought past the end gives nothing to place the end by.
                    if (!segmentStarted) {
                        throw if (seekable) SingProgressivePcm.Misplaced("sought past the end of the stream") else IOException("no audio decoded")
                    }
                    p.endOfStream(if (seekable) 0 else padding)
                    segmentOver = true
                }
                if (!segmentOver && seekable && p != null && segmentStarted) {
                    // The separation moved to frames not decoded, out of this segment's way: go there.
                    val want = wantFrame
                    val cursor = p.segmentCursor.toLong()
                    if (want >= 0 && (want < cursor - JUMP_BACK_SLACK || want > cursor + p.sampleRate.toLong() * JUMP_AHEAD_SECONDS) &&
                        !p.isDecoded(want.toInt(), (want + 1).toInt())
                    ) {
                        p.endSegment()
                        segmentOver = true
                    }
                }
                if (segmentOver) {
                    if (p != null && p.isComplete) break
                    if (!nextSegment()) break
                }
            }
            if (pcm?.isComplete != true) throw IOException("decode ended with frames missing")
        } catch (e: Throwable) {
            if (!format.isCompleted) format.completeExceptionally(e)
            throw e
        } finally {
            codec?.let {
                runCatching { it.stop() }
                it.release()
            }
            extractor.release()
        }
    }

    companion object {
        private const val TAG = "YoinSing"
        private const val TIMEOUT_US = 10_000L

        /** A length-unknown stream's buffer (sparse until written). */
        private const val MAX_UNKNOWN_SECONDS = 30L * 60

        /** A wanted frame this far past the decoding segment's place is sought, not decoded through. */
        private const val JUMP_AHEAD_SECONDS = 30L

        /** Frames just behind the cursor are the separation catching up with it, not a seek. */
        private const val JUMP_BACK_SLACK = 1L

        private const val KEY_ENCODER_DELAY = "encoder-delay"
        private const val KEY_ENCODER_PADDING = "encoder-padding"

        private fun MediaFormat.intOr(key: String, default: Int): Int = if (containsKey(key)) getInteger(key) else default
    }
}
