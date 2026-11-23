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
import com.gpo.yoin.player.sing.dsp.LiveVocalSeparator
import com.gpo.yoin.player.sing.dsp.LyraSingSpec
import java.io.File
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
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
 * Lyra-Sing-Lite on this device, for Subsonic songs (streamed or downloaded), live: the original keeps
 * playing and the mixer takes the vocal out of it ([SingMixerAudioProcessor]) as soon as the vocal
 * stem V covers the next few seconds.
 *
 * A request decodes the song once (X, as Media3 plays it: encoder delay and padding trimmed), writes
 * X's mono reference into a fresh [SingVocalTrack], and separates chunk by chunk
 * ([LiveVocalSeparator]) from where the listener is — far enough ahead of a moving playhead that the
 * first [READY_AHEAD_MS] are in by the time it gets there — then on to the end, then the beginning.
 * [SingStatus.Ready] ([SingSource.Live]) comes once V covers [READY_AHEAD_MS] from the playhead; the
 * work goes on to the end, following seeks ([onPlayhead]).
 *
 * Race to idle: a song started is separated whole, the ORT session closed the moment the last chunk is
 * through, and the finished track kept in [cache]. Asked again, the song costs nothing: a cache hit is
 * the same live path with every block already in.
 *
 * One song at a time; a request for another song cancels the one in progress (the listener moved on).
 * The first request runs the device gate ([SingDeviceGate]) once.
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

    @Volatile private var gateRestored = false

    /** Milliseconds per chunk, as measured on the songs so far (the gate's benchmark to begin with). */
    @Volatile private var chunkMs: Double? = null

    private val playheads = ConcurrentHashMap<String, Playhead>()

    /** When each request started, for a playhead estimate before the first report. */
    private val requestNanos = ConcurrentHashMap<String, Long>()

    /** The last track handed out, so a repeated request for the same song maps nothing new. */
    @Volatile private var lastTrack: Pair<String, SingVocalTrack>? = null

    private class Playhead(val positionMs: Long, val atNanos: Long, val playing: Boolean)

    /** The gate's last answer (null before the first Sing). */
    @Volatile var verdict: SingDeviceGate.Verdict? = null
        private set

    override fun stream(request: SingRequest): Flow<SingStatus> = flow {
        val running = jobs[request.key]?.isActive == true
        val hit = if (running) null else cachedTrack(request)
        if (hit != null) {
            emit(SingStatus.Ready(SingSource.Live(hit)))
            return@flow
        }
        emitAll(start(request))
    }

    override fun onPlayhead(request: SingRequest, positionMs: Long, playing: Boolean) {
        playheads[request.key] = Playhead(positionMs.coerceAtLeast(0L), System.nanoTime(), playing)
    }

    /**
     * Unavailable once the device gate has failed (remembered across launches; heat re-read now).
     * Before the first gate run it's available: the gate runs on the first Sing.
     */
    override fun availability(): SingAvailability {
        if (!gateRestored) restoreGate()
        val ms = benchmarkMs ?: return SingAvailability.Available
        val thermal = SingDeviceGate.thermalStatus(context.getSystemService(PowerManager::class.java))
        return when (val v = SingDeviceGate.evaluate(ms, thermal)) {
            is SingDeviceGate.Verdict.Sufficient -> SingAvailability.Available
            is SingDeviceGate.Verdict.Insufficient -> SingAvailability.Unavailable(v.reason)
        }
    }

    override fun cancel(request: SingRequest) {
        jobs.remove(request.key)?.cancel()
        playheads.remove(request.key)
    }

    private fun start(request: SingRequest): MutableStateFlow<SingStatus> {
        val state = states.getOrPut(request.key) { MutableStateFlow(SingStatus.Preparing()) }
        val running = jobs[request.key]
        if (running?.isActive == true) return state
        // The listener moved on: stop the other song's work.
        jobs.keys.filter { it != request.key }.forEach { jobs.remove(it)?.cancel() }
        // Its status (holding its mapped track) goes too.
        states.keys.removeAll { it != request.key }
        playheads.keys.removeAll { it != request.key }
        requestNanos.keys.removeAll { it != request.key }
        requestNanos[request.key] = System.nanoTime()
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

        val key = cacheKey(request)
        cachedTrack(request)?.let { return SingStatus.Ready(SingSource.Live(it)) }
        val uri = request.streamUri ?: return SingStatus.Unavailable(SingUnavailableReason.NoSource)
        val started = System.nanoTime()

        SingWorkDir(File(context.cacheDir, WORK_DIR)).use { work ->
            state.value = SingStatus.Preparing(0.05f, SingStatus.Preparing.Stage.Reading)
            val sourceFile = work.file(".src")
            val mix = try {
                copySource(uri, sourceFile)
                currentCoroutineContextCheck()
                SingPcmDecoder.decode(sourceFile, work) { !isActiveFor(request) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "decode failed", e)
                return SingStatus.Failed(SingFailure.Decode, e.message)
            } finally {
                sourceFile.delete()
            }
            if (mix.frames > SingVocalTrack.MAX_FRAMES) return SingStatus.Failed(SingFailure.Decode, "song too long")
            val decodedMs = (System.nanoTime() - started) / 1_000_000

            val part = cache.partFile(key)
            var committed = false
            try {
                val track = SingVocalTrack.create(part, mix.sampleRate, mix.frames)
                track.writeReference(mix.left, mix.right)
                separate(request, state, model, mix, track, work)
                track.finish()
                track.file = cache.commit(key, part)
                committed = true
                lastTrack = key to track
                val seconds = mix.frames.toDouble() / mix.sampleRate
                Log.d(
                    TAG,
                    "separated ${"%.1f".format(seconds)} s in ${(System.nanoTime() - started) / 1_000_000} ms " +
                        "(decode $decodedMs ms, ${"%.0f".format(chunkMs ?: 0.0)} ms/chunk)",
                )
                return SingStatus.Ready(SingSource.Live(track))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "separation failed", e)
                return SingStatus.Failed(SingFailure.Inference, e.message)
            } finally {
                if (!committed) part.delete()
            }
        }
    }

    /**
     * Runs every chunk of the song, the listener's first, writing V into [track] as it's final, and
     * says [SingStatus.Ready] once V covers [READY_AHEAD_MS] from the playhead.
     */
    private suspend fun separate(
        request: SingRequest,
        state: MutableStateFlow<SingStatus>,
        model: File,
        mix: DecodedPcm,
        track: SingVocalTrack,
        work: SingWorkDir,
    ) {
        val rate = mix.sampleRate
        val separator = LiveVocalSeparator(mix.left, mix.right, mix.frames, rate, work::floats)
        val sink = LiveVocalSeparator.VocalSink { from, to, left, right, offset -> track.writeVocal(from, to, left, right, offset) }
        separator.start(sink)
        val readyFrames = READY_AHEAD_MS * rate / 1000
        var ready = false
        var run = 0
        val session = OrtSeparationModel.open(model, threads, modelStore.config.execution)
        try {
            while (true) {
                currentCoroutineContextCheck()
                if (!isActiveFor(request)) throw CancellationException("Sing cancelled")
                val playhead = playheadMs(request)
                val playFrame = playhead.positionMs * rate / 1000
                // Where V should start: the playhead itself when V is there (the forward sweep then
                // extends it), else as far ahead as the playhead moves while the first chunks run.
                val lead = when {
                    !playhead.playing || track.isCovered(playFrame, playFrame + 1) -> 0L
                    !ready -> leadMs(READY_AHEAD_MS)
                    else -> leadMs(0)
                }
                val chunk = separator.nextChunk(separator.frameAtSource((playhead.positionMs + lead) * rate / 1000))
                if (chunk < 0) break
                val t0 = System.nanoTime()
                separator.runChunk(chunk, session, sink)
                recordChunk((System.nanoTime() - t0) / 1e6)
                run++
                if (!ready) {
                    val now = playheadMs(request).positionMs * rate / 1000
                    if (track.isCovered(now, now + readyFrames)) {
                        ready = true
                        state.value = SingStatus.Ready(SingSource.Live(track))
                    } else {
                        val expected = READY_AHEAD_MS / STEP_AUDIO_MS + 2.0
                        state.value = SingStatus.Preparing(
                            (0.1f + 0.85f * (run / expected).toFloat()).coerceAtMost(0.95f),
                            SingStatus.Preparing.Stage.Working,
                        )
                    }
                }
            }
        } finally {
            // Race to idle: the model is done with this song.
            session.close()
        }
        check(separator.isFinished && track.isComplete) { "vocal track incomplete" }
    }

    /** How long the next chunks take to cover [aheadMs] of audio, plus the chunk before them. */
    private fun leadMs(aheadMs: Int): Long {
        val perChunk = chunkMs ?: benchmarkMs?.toDouble() ?: DEFAULT_CHUNK_MS
        val chunks = aheadMs / STEP_AUDIO_MS + 2.0
        return (chunks * perChunk).toLong().coerceIn(0L, MAX_LEAD_MS)
    }

    private fun recordChunk(ms: Double) {
        val previous = chunkMs
        chunkMs = if (previous == null) ms else previous * 0.8 + ms * 0.2
    }

    /** The listener's position now: the last report, moved on by the time since if playing. */
    private fun playheadMs(request: SingRequest): Playhead {
        val now = System.nanoTime()
        val known = playheads[request.key]
            ?: Playhead(request.positionMs, requestNanos[request.key] ?: now, playing = true)
        val position = if (known.playing) known.positionMs + (now - known.atNanos) / 1_000_000 else known.positionMs
        return Playhead(position, now, known.playing)
    }

    /** The finished track for [request] in the cache, mapped (null on a miss or a broken file). */
    private fun cachedTrack(request: SingRequest): SingVocalTrack? {
        val key = cacheKey(request)
        lastTrack?.let { (k, track) -> if (k == key && track.isComplete) return track }
        val file = cache.get(key) ?: return null
        val track = SingVocalTrack.open(file)
        if (track == null) {
            cache.remove(key)
            return null
        }
        lastTrack = key to track
        return track
    }

    /**
     * The device gate: one timed chunk — once per device build and model (kept in prefs), so a later
     * Sing, a cache hit above all, costs no compute. Heat is checked every time.
     */
    private suspend fun gate(model: File): SingDeviceGate.Verdict = gateMutex.withLock {
        val thermal = SingDeviceGate.thermalStatus(context.getSystemService(PowerManager::class.java))
        if (!gateRestored) restoreGate()
        if (benchmarkMs == null) {
            benchmarkMs = runCatching {
                OrtSeparationModel.open(model, threads, modelStore.config.execution).use { SingDeviceGate.benchmarkChunkMs(it) }
            }.onFailure { Log.w(TAG, "benchmark failed", it) }.getOrNull()
            saveGate()
        }
        SingDeviceGate.evaluate(benchmarkMs, thermal).also { Log.d(TAG, "gate: $it") }
    }

    private val gatePrefs by lazy { context.getSharedPreferences(GATE_PREFS, Context.MODE_PRIVATE) }

    private fun gateFingerprint() = "${android.os.Build.FINGERPRINT}|${modelStore.config.sha256}|$threads|${modelStore.config.execution}"

    private fun restoreGate() {
        gateRestored = true
        if (gatePrefs.getString(KEY_FINGERPRINT, null) != gateFingerprint()) return
        benchmarkMs = gatePrefs.getLong(KEY_BENCHMARK_MS, -1L).takeIf { it >= 0 }
    }

    private fun saveGate() {
        gatePrefs.edit {
            putString(KEY_FINGERPRINT, gateFingerprint())
            benchmarkMs?.let { putLong(KEY_BENCHMARK_MS, it) } ?: remove(KEY_BENCHMARK_MS)
        }
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

    private fun cacheKey(request: SingRequest) = SingCache.keyFor(request, modelStore.config.sha256)

    private fun isActiveFor(request: SingRequest): Boolean = jobs[request.key]?.isActive == true

    private suspend fun currentCoroutineContextCheck() = kotlinx.coroutines.currentCoroutineContext().ensureActive()

    companion object {
        private const val TAG = "YoinSing"
        const val WORK_DIR = "sing-work"
        const val CACHE_DIR = "sing-vocal"
        const val MODEL_DIR = "sing-model"

        /** Where the quad .m4a results of the first engine were kept; cleared on start. */
        private const val LEGACY_CACHE_DIR = "sing"
        private const val GATE_PREFS = "sing_gate"
        private const val KEY_FINGERPRINT = "fingerprint"
        private const val KEY_BENCHMARK_MS = "benchmark_ms"

        /** Sing is Ready once V covers this much from the playhead. */
        const val READY_AHEAD_MS = 8_000

        /** Audio one chunk step adds: 160 frames × hop 256 at 22.05 kHz (~1.86 s). */
        private const val STEP_AUDIO_MS = LyraSingSpec.DEFAULT_STEP_FRAMES * LyraSingSpec.HOP * 1000.0 / LyraSingSpec.CORE_RATE

        /** Until a chunk has been timed on this device. */
        private const val DEFAULT_CHUNK_MS = 1_500.0
        private const val MAX_LEAD_MS = 20_000L

        /** The engine as the app runs it: model in noBackupFilesDir, results in the cache dir. */
        fun create(context: Context, downloads: OfflineDownloads, scope: CoroutineScope): OnDeviceSingStreamProvider {
            val http = OkHttpClient()
            val cache = SingCache(File(context.cacheDir, CACHE_DIR), suffix = SingVocalTrack.SUFFIX)
            scope.launch(Dispatchers.IO) {
                File(context.cacheDir, LEGACY_CACHE_DIR).deleteRecursively()
                cache.clearParts()
            }
            return OnDeviceSingStreamProvider(
                context = context,
                modelStore = SingModelStore(File(context.noBackupFilesDir, MODEL_DIR), http),
                cache = cache,
                sourceFactory = OfflineFirstDataSource.Factory(
                    downloads = downloads.downloadCache,
                    fallback = mediaCacheDataSourceFactory(downloads.streamCache, OkHttpDataSource.Factory(http)),
                    isComplete = downloads::isComplete,
                    onOfflineOpen = { _, _ -> },
                ),
                scope = scope,
            )
        }

        /**
         * Big cores only, roughly: half the cores, 2..4. (On a Pixel Tablet that's 4 threads, ~150 % CPU
         * and ~1.6× real time; live Sing needs > 1×.)
         */
        fun defaultThreads(): Int = (Runtime.getRuntime().availableProcessors() / 2).coerceIn(2, 4)
    }
}
