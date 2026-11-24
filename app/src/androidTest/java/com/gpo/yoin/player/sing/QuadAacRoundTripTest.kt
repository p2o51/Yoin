package com.gpo.yoin.player.sing

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.os.Build
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.nio.ByteOrder
import kotlin.math.abs
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The shared Sing format on a real device: 4-channel AAC-LC .m4a, [I_L, I_R, V_L, V_R]. Encodes
 * through this device's AAC encoder + MediaMuxer and decodes as Media3 would (all channels on API 32+),
 * then checks every channel came back where it went in.
 *
 * If the order fails here, playback still works — the engine's QuadChannelOrderProbe finds the
 * device's map at run time and the mixer reads through it — but the server (Lyra) and iOS sides
 * must then be checked for the same reordering.
 */
@RunWith(AndroidJUnit4::class)
class QuadAacRoundTripTest {
    private val dir: File = InstrumentationRegistry.getInstrumentation().targetContext.cacheDir

    @Test
    fun should_keepChannelOrder_when_tonesRoundTrip() {
        val result = QuadChannelOrderProbe.run(dir)
        Log.i(TAG, "probe on ${Build.MODEL} API ${Build.VERSION.SDK_INT}: $result")
        assertTrue("decoder folded the 4 channels: $result", result is QuadChannelOrderProbe.Result.Quad)
        assertArrayEquals(SingMixControl.IDENTITY, (result as QuadChannelOrderProbe.Result.Quad).map)
    }

    /** Distinct constant levels per channel (the owner's check), read back from the middle of the file. */
    @Test
    fun should_keepChannelOrder_when_constantLevelsRoundTrip() {
        val levels = floatArrayOf(0.1f, 0.2f, -0.3f, 0.4f)
        val rate = 48_000
        val file = File.createTempFile("quad-levels", ".m4a", dir)
        try {
            QuadAacEncoder.encode(file, rate, rate, { from, count, out ->
                repeat(count) { for (c in 0 until 4) out.putShort((levels[c] * 32767).toInt().toShort()) }
            })
            val means = decodedMeans(file)
            Log.i(TAG, "constant levels back: ${means.joinToString()}")
            assertEquals(4, means.size)
            for (c in 0 until 4) assertTrue("channel $c: ${means[c]} vs ${levels[c]}", abs(means[c] - levels[c]) < 0.05f)
        } finally {
            file.delete()
        }
    }

    private fun decodedMeans(file: File): FloatArray {
        val extractor = MediaExtractor()
        extractor.setDataSource(file.absolutePath)
        extractor.selectTrack(0)
        val format = extractor.getTrackFormat(0)
        if (Build.VERSION.SDK_INT >= 32) format.setInteger(MediaFormat.KEY_MAX_OUTPUT_CHANNEL_COUNT, 99)
        val codec = MediaCodec.createDecoderByType(format.getString(MediaFormat.KEY_MIME)!!)
        codec.configure(format, null, null, 0)
        codec.start()
        var channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
        val sums = DoubleArray(8)
        var counted = 0
        var frame = 0
        val info = MediaCodec.BufferInfo()
        var inputDone = false
        var done = false
        while (!done) {
            if (!inputDone) {
                val i = codec.dequeueInputBuffer(10_000)
                if (i >= 0) {
                    val n = extractor.readSampleData(codec.getInputBuffer(i)!!, 0)
                    if (n < 0) {
                        codec.queueInputBuffer(i, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                        inputDone = true
                    } else {
                        codec.queueInputBuffer(i, 0, n, extractor.sampleTime, 0)
                        extractor.advance()
                    }
                }
            }
            val o = codec.dequeueOutputBuffer(info, 10_000)
            if (o == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) channels = codec.outputFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            if (o >= 0) {
                val b = codec.getOutputBuffer(o)!!.order(ByteOrder.LITTLE_ENDIAN)
                val frames = info.size / (2 * channels)
                for (f in 0 until frames) {
                    // Skip the encoder's priming and the edges: the middle half second.
                    if (frame in 12_000 until 36_000) {
                        for (c in 0 until channels) sums[c] += b.getShort(info.offset + (f * channels + c) * 2) / 32768.0
                        counted++
                    }
                    frame++
                }
                codec.releaseOutputBuffer(o, false)
                if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) done = true
            }
        }
        codec.stop()
        codec.release()
        extractor.release()
        return FloatArray(channels) { (sums[it] / counted).toFloat() }
    }

    companion object {
        private const val TAG = "QuadAacRoundTrip"
    }
}
