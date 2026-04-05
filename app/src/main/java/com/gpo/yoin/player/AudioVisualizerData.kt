package com.gpo.yoin.player

import android.media.audiofx.Visualizer
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Normalized visualization data captured from the audio session.
 *
 * @param waveform PCM waveform values normalized to 0..1
 * @param fft      FFT magnitude values normalized to 0..1
 */
data class VisualizerData(
    val waveform: FloatArray = FloatArray(0),
    val fft: FloatArray = FloatArray(0),
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is VisualizerData) return false
        return waveform.contentEquals(other.waveform) && fft.contentEquals(other.fft)
    }

    override fun hashCode(): Int {
        var result = waveform.contentHashCode()
        result = 31 * result + fft.contentHashCode()
        return result
    }

    companion object {
        val Empty = VisualizerData()
    }
}

/**
 * Captures FFT and waveform data from the current media session's audio
 * session ID using [android.media.audiofx.Visualizer].
 *
 * Falls back to simulated ambient data when the real Visualizer API is
 * unavailable (e.g. missing permissions or unsupported device).
 */
class AudioVisualizerManager {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var visualizer: Visualizer? = null
    private var currentSessionId: Int = -1
    private var simulationJob: Job? = null

    private val _visualizerData = MutableStateFlow(VisualizerData.Empty)
    val visualizerData: StateFlow<VisualizerData> = _visualizerData.asStateFlow()

    fun start(audioSessionId: Int) {
        if (audioSessionId == currentSessionId && visualizer != null) return
        stop()
        currentSessionId = audioSessionId

        try {
            val viz = Visualizer(audioSessionId).apply {
                captureSize = Visualizer.getCaptureSizeRange()[1].coerceAtMost(CAPTURE_SIZE)
                setDataCaptureListener(
                    captureListener,
                    Visualizer.getMaxCaptureRate() / 2,
                    true,
                    true,
                )
                enabled = true
            }
            visualizer = viz
        } catch (e: Exception) {
            Log.w(TAG, "Visualizer unavailable, using simulated data", e)
            startSimulation()
        }
    }

    fun stop() {
        simulationJob?.cancel()
        simulationJob = null
        try {
            visualizer?.enabled = false
            visualizer?.release()
        } catch (_: Exception) { }
        visualizer = null
        currentSessionId = -1
        _visualizerData.value = VisualizerData.Empty
    }

    // ── Real capture listener ───────────────────────────────────────────

    private val captureListener = object : Visualizer.OnDataCaptureListener {
        override fun onWaveFormDataCapture(
            visualizer: Visualizer?,
            waveform: ByteArray?,
            samplingRate: Int,
        ) {
            waveform?.let { raw ->
                val normalized = FloatArray(raw.size) { i ->
                    (raw[i].toInt() and 0xFF) / 255f
                }
                _visualizerData.value = _visualizerData.value.copy(waveform = normalized)
            }
        }

        override fun onFftDataCapture(
            visualizer: Visualizer?,
            fft: ByteArray?,
            samplingRate: Int,
        ) {
            fft?.let { raw ->
                val magnitudes = FloatArray(raw.size / 2) { i ->
                    val real = raw[i * 2].toFloat()
                    val imaginary = raw[i * 2 + 1].toFloat()
                    val magnitude = kotlin.math.sqrt(real * real + imaginary * imaginary)
                    (magnitude / FFT_NORMALIZE).coerceIn(0f, 1f)
                }
                _visualizerData.value = _visualizerData.value.copy(fft = magnitudes)
            }
        }
    }

    // ── Simulated ambient fallback ──────────────────────────────────────

    private fun startSimulation() {
        simulationJob?.cancel()
        simulationJob = scope.launch {
            var phase = 0f
            while (isActive) {
                phase += SIMULATION_PHASE_STEP
                val fft = FloatArray(SIMULATION_BAR_COUNT) { i ->
                    val t = i.toFloat() / SIMULATION_BAR_COUNT
                    val base = kotlin.math.sin(
                        (t * Math.PI * 2 + phase).toDouble(),
                    ).toFloat() * 0.3f + 0.3f
                    val secondary = kotlin.math.sin(
                        (t * Math.PI * 4 + phase * 1.5f).toDouble(),
                    ).toFloat() * 0.15f
                    (base + secondary).coerceIn(0f, 1f)
                }
                _visualizerData.value = VisualizerData(waveform = FloatArray(0), fft = fft)
                delay(FRAME_DELAY_MS)
            }
        }
    }

    companion object {
        private const val TAG = "AudioVisualizerManager"
        private const val CAPTURE_SIZE = 512
        private const val FFT_NORMALIZE = 128f
        private const val SIMULATION_BAR_COUNT = 32
        private const val SIMULATION_PHASE_STEP = 0.15f
        private const val FRAME_DELAY_MS = 33L
    }
}
