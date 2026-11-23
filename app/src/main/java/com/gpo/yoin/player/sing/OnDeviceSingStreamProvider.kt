package com.gpo.yoin.player.sing

import android.content.Context
import androidx.core.content.edit
import androidx.core.net.toUri
import android.os.PowerManager
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.okhttp.OkHttpDataSource
import com.gpo.yoin.data.offline.OfflineDownloads
import com.gpo.yoin.player.OfflineFirstDataSource
import com.gpo.yoin.player.mediaCacheDataSourceFactory
import com.gpo.yoin.player.sing.dsp.FullBandSeparator
import com.gpo.yoin.player.sing.dsp.PolyphaseResampler
import com.gpo.yoin.player.sing.dsp.VocalSeparator
import java.io.File
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.OkHttpClient

/**
 * Lyra-Sing-Lite on this device, for Subsonic songs (streamed or downloaded).
 *
 * Race to idle: a song asked for once is separated whole — the chunk under the play position first,
 * then the rest at several times real time — written as a 4-channel .m4a into [cache], and the ORT
 * session is closed the moment the last chunk is through. Asked again, the song costs nothing: a
 * cache hit is [SingStatus.Ready] at once.
 *
 * One song at a time; a request for another song cancels the one in progress (the listener moved on).
 * The first request runs the device gate ([SingDeviceGate] + [QuadChannelOrderProbe]) once.
 */
@OptIn(UnstableApi::class)
class OnDeviceSingStreamProvider(
    private val context: Context,
    private val modelStore: SingModelStore,
    val cache: SingCache,
    /** Reads the original song's bytes: the downloads first, then the stream cache, then the network. */
    private val sourceFactory: DataSource.Factory,
    private val scope: CoroutineScope,
    private val threads: Int = defaultThreads(),
) : SingStreamProvider {
    private val states = ConcurrentHashMap<String, MutableStateFlow<SingStatus>>()
    private val jobs = ConcurrentHashMap<String, Job>()
    private val oneSongAtATime = Mutex()
    private val gateMutex = Mutex()

    @Volatile private var benchmarkMs: Long? = null

    @Volatile private var probe: QuadChannelOrderProbe.Result? = null

    /** The gate's last answer (null before the first Sing). */
    @Volatile var verdict: SingDeviceGate.Verdict? = null
        private set

    override fun stream(request: SingRequest): Flow<SingStatus> = flow {
        val hit = cache.get(cacheKey(request))
        val map = knownChannelMap()
        if (hit != null && map != null) {
            emit(SingStatus.Ready(SingSource.LocalFile(hit, map)))
            return@flow
        }
        emitAll(start(request))
    }

    /**
     * Unavailable once the device gate has failed (remembered across launches; heat re-read now).
     * Before the first gate run it's available: the gate runs on the first Sing.
     */
    override fun availability(): SingAvailability {
        if (probe == null) restoreGate()
        val known = probe ?: return SingAvailability.Available
        val thermal = SingDeviceGate.thermalStatus(context.getSystemService(PowerManager::class.java))
        val quad = known is QuadChannelOrderProbe.Result.Quad
        if (quad && benchmarkMs == null) return SingAvailability.Available
        return when (val v = SingDeviceGate.evaluate(benchmarkMs, thermal, quad)) {
            is SingDeviceGate.Verdict.Sufficient -> SingAvailability.Available
            is SingDeviceGate.Verdict.Insufficient -> SingAvailability.Unavailable(v.reason)
        }
    }

    override fun cancel(request: SingRequest) {
        jobs.remove(request.key)?.cancel()
    }

    private fun start(request: SingRequest): MutableStateFlow<SingStatus> {
        val state = states.getOrPut(request.key) { MutableStateFlow(SingStatus.Preparing()) }
        val running = jobs[request.key]
        if (running?.isActive == true) return state
        // The listener moved on: stop the other song's work.
        jobs.keys.filter { it != request.key }.forEach { jobs.remove(it)?.cancel() }
        state.value = SingStatus.Preparing()
        val job = scope.launch(start = CoroutineStart.LAZY) {
            try {
                state.value = oneSongAtATime.withLock { prepare(request, state) }
            } catch (e: CancellationException) {
                state.value = SingStatus.Preparing()
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Sing failed", e)
                state.value = SingStatus.Failed(SingFailure.Inference, e.message)
            } finally {
                jobs.remove(request.key, coroutineContext[Job])
            }
        }
        jobs[request.key] = job
        job.start()
        return state
    }

    private suspend fun prepare(request: SingRequest, state: MutableStateFlow<SingStatus>): SingStatus {
        state.value = SingStatus.Preparing(stage = SingStatus.Preparing.Stage.Model)
        val model = try {
            modelStore.ensure { state.value = SingStatus.Preparing(it * 0.05f, SingStatus.Preparing.Stage.Model) }
        } catch (e: IOException) {
            Log.w(TAG, "model unavailable", e)
            return SingStatus.Failed(SingFailure.ModelDownload, e.message)
        }
        val gate = gate(model)
        verdict = gate
        if (gate is SingDeviceGate.Verdict.Insufficient) return SingStatus.Unavailable(gate.reason)
        val map = (probe as QuadChannelOrderProbe.Result.Quad).map

        val key = cacheKey(request)
        cache.get(key)?.let { return SingStatus.Ready(SingSource.LocalFile(it, map)) }
        val uri = request.streamUri ?: return SingStatus.Unavailable(SingUnavailableReason.NoSource)

        SingWorkDir(File(context.cacheDir, WORK_DIR)).use { work ->
            state.value = SingStatus.Preparing(0.05f, SingStatus.Preparing.Stage.Reading)
            val sourceFile = work.file(".src")
            val mix = try {
                copySource(uri, sourceFile)
                currentCoroutineContextCheck()
                fitForAac(SingPcmDecoder.decode(sourceFile, work) { !isActiveFor(request) }, work)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "decode failed", e)
                return SingStatus.Failed(SingFailure.Decode, e.message)
            } finally {
                sourceFile.delete()
            }

            state.value = SingStatus.Preparing(0.1f, SingStatus.Preparing.Stage.Working)
            val started = System.nanoTime()
            val separated = try {
                val session = OrtSeparationModel.open(model, threads, modelStore.config.execution)
                var closed = false
                try {
                    FullBandSeparator(VocalSeparator(), work::floats).separate(
                        mix.left, mix.right, mix.frames, mix.sampleRate, session,
                        priorityPositionMs = request.positionMs,
                        progress = { done, total ->
                            if (!isActiveFor(request)) throw CancellationException("Sing cancelled")
                            state.value = SingStatus.Preparing(0.1f + 0.8f * done / total, SingStatus.Preparing.Stage.Working)
                        },
                        afterModel = {
                            session.close()
                            closed = true
                        },
                    )
                } finally {
                    if (!closed) session.close()
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "separation failed", e)
                return SingStatus.Failed(SingFailure.Inference, e.message)
            }
            val seconds = mix.frames.toDouble() / mix.sampleRate
            Log.d(TAG, "separated ${"%.1f".format(seconds)} s in ${(System.nanoTime() - started) / 1_000_000} ms (${separated.chunks} chunks)")

            state.value = SingStatus.Preparing(0.9f, SingStatus.Preparing.Stage.Saving)
            val part = cache.partFile(key)
            try {
                QuadAacEncoder.encode(
                    part, mix.sampleRate, mix.frames,
                    QuadAacEncoder.singSource(mix, separated.vocalLeft, separated.vocalRight),
                ) { !isActiveFor(request) }
                return SingStatus.Ready(SingSource.LocalFile(cache.commit(key, part), map))
            } catch (e: CancellationException) {
                part.delete()
                throw e
            } catch (e: Exception) {
                part.delete()
                Log.w(TAG, "encode failed", e)
                return SingStatus.Failed(SingFailure.Encode, e.message)
            }
        }
    }

    /**
     * The device gate: the 4-channel round trip, then one timed chunk — once per device build and
     * model (kept in prefs), so a later Sing, a cache hit above all, costs no compute. Heat is checked
     * every time.
     */
    private suspend fun gate(model: File): SingDeviceGate.Verdict = gateMutex.withLock {
        val thermal = SingDeviceGate.thermalStatus(context.getSystemService(PowerManager::class.java))
        if (probe == null) restoreGate()
        if (probe == null) {
            probe = runCatching { QuadChannelOrderProbe.run(context.cacheDir) }
                .onFailure { Log.w(TAG, "quad probe failed", it) }
                .getOrElse { QuadChannelOrderProbe.Result.NotQuad(0) }
        }
        val quad = probe is QuadChannelOrderProbe.Result.Quad
        if (benchmarkMs == null && quad) {
            benchmarkMs = runCatching {
                OrtSeparationModel.open(model, threads, modelStore.config.execution).use { SingDeviceGate.benchmarkChunkMs(it) }
            }.onFailure { Log.w(TAG, "benchmark failed", it) }.getOrNull()
        }
        saveGate()
        SingDeviceGate.evaluate(benchmarkMs, thermal, quad).also { Log.d(TAG, "gate: $it (probe $probe)") }
    }

    private val gatePrefs by lazy { context.getSharedPreferences(GATE_PREFS, Context.MODE_PRIVATE) }

    private fun gateFingerprint() = "${android.os.Build.FINGERPRINT}|${modelStore.config.sha256}|$threads|${modelStore.config.execution}"

    private fun restoreGate() {
        if (gatePrefs.getString(KEY_FINGERPRINT, null) != gateFingerprint()) return
        val map = gatePrefs.getString(KEY_MAP, null)
        probe = when {
            map != null -> QuadChannelOrderProbe.Result.Quad(map.split(',').map(String::toInt).toIntArray())
            gatePrefs.contains(KEY_NOT_QUAD) -> QuadChannelOrderProbe.Result.NotQuad(gatePrefs.getInt(KEY_NOT_QUAD, 0))
            else -> null
        }
        benchmarkMs = gatePrefs.getLong(KEY_BENCHMARK_MS, -1L).takeIf { it >= 0 }
    }

    private fun saveGate() {
        val result = probe ?: return
        gatePrefs.edit {
            putString(KEY_FINGERPRINT, gateFingerprint())
            when (result) {
                is QuadChannelOrderProbe.Result.Quad -> putString(KEY_MAP, result.map.joinToString(",")).remove(KEY_NOT_QUAD)
                is QuadChannelOrderProbe.Result.NotQuad -> putInt(KEY_NOT_QUAD, result.channels).remove(KEY_MAP)
            }
            benchmarkMs?.let { putLong(KEY_BENCHMARK_MS, it) } ?: remove(KEY_BENCHMARK_MS)
        }
    }

    /** Restores the remembered channel map (if any), so a cache hit needs no gate run at all. */
    private fun knownChannelMap(): IntArray? {
        if (probe == null) restoreGate()
        return (probe as? QuadChannelOrderProbe.Result.Quad)?.map
    }

    private fun copySource(uri: String, into: File) {
        val source = sourceFactory.createDataSource()
        try {
            source.open(DataSpec(uri.toUri()))
            into.outputStream().use { out ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    val n = source.read(buffer, 0, buffer.size)
                    if (n < 0) break
                    out.write(buffer, 0, n)
                }
            }
        } finally {
            source.close()
        }
    }

    /** AAC tops out at 48 kHz here; a hi-res source is separated and stored at 48 kHz. */
    private fun fitForAac(pcm: DecodedPcm, work: SingWorkDir): DecodedPcm {
        if (pcm.sampleRate <= QuadAacEncoder.MAX_RATE) return pcm
        val down = PolyphaseResampler(pcm.sampleRate, QuadAacEncoder.MAX_RATE)
        val frames = down.outputLength(pcm.frames)
        val left = work.floats(frames)
        val right = work.floats(frames)
        down.process(pcm.left, pcm.frames, left)
        down.process(pcm.right, pcm.frames, right)
        return DecodedPcm(left, right, frames, QuadAacEncoder.MAX_RATE)
    }

    private fun cacheKey(request: SingRequest) = SingCache.keyFor(request, modelStore.config.sha256)

    private fun isActiveFor(request: SingRequest): Boolean = jobs[request.key]?.isActive == true

    private suspend fun currentCoroutineContextCheck() = kotlinx.coroutines.currentCoroutineContext().ensureActive()

    companion object {
        private const val TAG = "YoinSing"
        const val WORK_DIR = "sing-work"
        const val CACHE_DIR = "sing"
        const val MODEL_DIR = "sing-model"
        private const val GATE_PREFS = "sing_gate"
        private const val KEY_FINGERPRINT = "fingerprint"
        private const val KEY_MAP = "channel_map"
        private const val KEY_NOT_QUAD = "not_quad_channels"
        private const val KEY_BENCHMARK_MS = "benchmark_ms"

        /** The engine as the app runs it: model in noBackupFilesDir, results in the cache dir. */
        fun create(context: Context, downloads: OfflineDownloads, scope: CoroutineScope): OnDeviceSingStreamProvider {
            val http = OkHttpClient()
            return OnDeviceSingStreamProvider(
                context = context,
                modelStore = SingModelStore(File(context.noBackupFilesDir, MODEL_DIR), http),
                cache = SingCache(File(context.cacheDir, CACHE_DIR)),
                sourceFactory = OfflineFirstDataSource.Factory(
                    downloads = downloads.downloadCache,
                    fallback = mediaCacheDataSourceFactory(downloads.streamCache, OkHttpDataSource.Factory(http)),
                    isComplete = downloads::isComplete,
                    onOfflineOpen = { _, _ -> },
                ),
                scope = scope,
            )
        }

        /** Big cores only, roughly: half the cores, 2..4. */
        fun defaultThreads(): Int = (Runtime.getRuntime().availableProcessors() / 2).coerceIn(2, 4)
    }
}
