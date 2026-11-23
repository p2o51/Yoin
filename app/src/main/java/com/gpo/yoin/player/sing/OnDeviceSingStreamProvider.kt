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
import com.gpo.yoin.player.sing.dsp.SeparationModel
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
import kotlin.math.abs
import kotlin.math.floor
import okhttp3.OkHttpClient

/**
 * Lyra-Sing-Lite on this device, for Subsonic songs (streamed or downloaded), live: the original keeps
 * playing and the mixer takes the vocal out of it ([SingMixerAudioProcessor]) as soon as the vocal
 * stem V covers the next few seconds.
 *
 * A request decodes the song once (X, as Media3 plays it: encoder delay and padding trimmed) while the
 * ORT session opens alongside ([SingSessionPool.prewarm]), writes X's mono reference into a fresh
 * [SingVocalTrack], and separates chunk by chunk ([LiveVocalSeparator]) from where the listener is —
 * far enough ahead of a moving playhead that the first [SingReadiness.READY_AHEAD_MS] are in by the
 * time it gets there ([SingReadiness.planLead], on the ms/chunk measured live) — then on to the end,
 * then the beginning. [SingStatus.Ready] ([SingSource.Live]) comes once V covers
 * [SingReadiness.READY_AHEAD_MS] from the playhead; the work goes on to the end, following seeks
 * ([onPlayhead]).
 *
 * Race to idle: a song started is separated whole and the finished track kept in [cache]; the session
 * goes back to the pool, which closes it after a minute unused (or at once on Sing off) — so the next
 * song with Sing on, its session also opened ahead as this one nears its end, doesn't wait for it.
 * Asked again, a song costs nothing: a cache hit is the same live path with every block already in.
 *
 * Memory, per second of 44.1 kHz audio, all in memory-mapped files rather than the Java heap (they
 * count in the app's PSS, but are file pages the system can write back and drop): X, the decoded mix,
 * 2 × 4 B × 44 100 ≈ 353 KB; the 22.05 kHz mix and V, ≈ 353 KB; the vocal track, 6 B × 44 100
 * ≈ 265 KB — ≈ 970 KB/s, ~190 MB for a 3:16 song once separated whole. Plus the ORT session (weights,
 * XNNPACK's packed copy, its activation arena), and 4 MB of reused tensors.
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

    /** The ORT session, opened while a song decodes and kept warm between songs. */
    private val sessions = SingSessionPool(scope) { model -> openSession(model) }

    /** The request this engine is singing (null after Sing off). */
    @Volatile private var servingKey: String? = null

    /** The request whose end the next song's session was opened for. */
    @Volatile private var prewarmedKey: String? = null

    @Volatile private var benchmarkMs: Long? = null

    @Volatile private var gateRestored = false

    /**
     * Milliseconds per chunk while the song plays, as measured (an average over the chunks so far,
     * kept across launches); null before the first: then the gate's idle benchmark × its load factor.
     */
    @Volatile private var chunkMs: Double? = null

    private val playheads = ConcurrentHashMap<String, Playhead>()

    /** Per request, bumped on every jump of the playhead (a seek): the lead is planned anew. */
    private val seeks = ConcurrentHashMap<String, Int>()

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
        servingKey = request.key
        if (hit != null) {
            Log.d(TAG, "cache hit")
            emit(SingStatus.Ready(SingSource.Live(hit)))
            return@flow
        }
        emitAll(start(request))
    }

    override fun onPlayhead(request: SingRequest, positionMs: Long, playing: Boolean) {
        val now = System.nanoTime()
        val position = positionMs.coerceAtLeast(0L)
        playheads.put(request.key, Playhead(position, now, playing))?.let { previous ->
            val expected = if (previous.playing) previous.positionMs + (now - previous.atNanos) / 1_000_000 else previous.positionMs
            if (abs(position - expected) > SEEK_JUMP_MS) seeks.merge(request.key, 1, Int::plus)
        }
        if (playing) prewarmNearEnd(request, position)
    }

    /**
     * The song is separated and nearly over, Sing still on: open the session for the next one now (it
     * is closed again after a minute if no song takes it).
     */
    private fun prewarmNearEnd(request: SingRequest, positionMs: Long) {
        if (servingKey != request.key || prewarmedKey == request.key) return
        val (key, track) = lastTrack ?: return
        if (!track.isComplete || track.sampleRate <= 0) return
        val remainingMs = track.frames * 1000L / track.sampleRate - positionMs
        if (remainingMs > PREWARM_BEFORE_END_MS || remainingMs < 0) return
        if (key != cacheKey(request)) return
        val model = modelStore.file.takeIf { it.isFile } ?: return
        prewarmedKey = request.key
        Log.d(TAG, "song ends in ${remainingMs / 1000} s: opening the session for the next one")
        sessions.prewarm(model)
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

    /** Sing off ([SingController.exit]): the song's work stops and no session stays warm. */
    override fun cancel(request: SingRequest) {
        jobs.remove(request.key)?.cancel()
        playheads.remove(request.key)
        seeks.remove(request.key)
        if (servingKey == request.key) servingKey = null
        sessions.close()
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
        seeks.keys.removeAll { it != request.key }
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
        val started = System.nanoTime()
        state.value = SingStatus.Preparing(stage = SingStatus.Preparing.Stage.Model)
        val model = try {
            modelStore.ensure { state.value = SingStatus.Preparing(it * 0.05f, SingStatus.Preparing.Stage.Model) }
        } catch (e: IOException) {
            Log.w(TAG, "model unavailable", e)
            return SingStatus.Failed(SingFailure.ModelDownload, e.message)
        }
        val gate = gate(model)
        verdict = gate
        if (gate is SingDeviceGate.Verdict.Insufficient) {
            sessions.close()
            return SingStatus.Unavailable(gate.reason)
        }

        val key = cacheKey(request)
        cachedTrack(request)?.let { return SingStatus.Ready(SingSource.Live(it)) }
        val uri = request.streamUri ?: return SingStatus.Unavailable(SingUnavailableReason.NoSource)
        // The session opens while the song is fetched and decoded: neither needs the other.
        sessions.prewarm(model)

        SingWorkDir(File(context.cacheDir, WORK_DIR)).use { work ->
            state.value = SingStatus.Preparing(0.05f, SingStatus.Preparing.Stage.Reading)
            val sourceFile = work.file(".src")
            var copyMs = 0L
            var decodeMs = 0L
            var sourceBytes = 0L
            val mix = try {
                val copyStart = System.nanoTime()
                copySource(uri, sourceFile)
                copyMs = (System.nanoTime() - copyStart) / 1_000_000
                sourceBytes = sourceFile.length()
                currentCoroutineContextCheck()
                val decodeStart = System.nanoTime()
                val decoded = SingPcmDecoder.decode(sourceFile, work) { !isActiveFor(request) }
                decodeMs = (System.nanoTime() - decodeStart) / 1_000_000
                decoded
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "decode failed", e)
                return SingStatus.Failed(SingFailure.Decode, e.message)
            } finally {
                sourceFile.delete()
            }
            if (mix.frames > SingVocalTrack.MAX_FRAMES) return SingStatus.Failed(SingFailure.Decode, "song too long")
            val seconds = mix.frames.toDouble() / mix.sampleRate
            Log.d(
                TAG,
                "source ${sourceBytes / 1024} KB in $copyMs ms; decoded ${"%.1f".format(seconds)} s " +
                    "@ ${mix.sampleRate} Hz in $decodeMs ms (X mapped: ${mix.frames.toLong() * 8 / 1_048_576} MB)",
            )

            val part = cache.partFile(key)
            var committed = false
            try {
                val track = SingVocalTrack.create(part, mix.sampleRate, mix.frames)
                track.writeReference(mix.left, mix.right)
                val waitStart = System.nanoTime()
                val lease = sessions.take(model)
                Log.d(TAG, "session in hand ${(System.nanoTime() - waitStart) / 1_000_000} ms after decode")
                try {
                    separate(request, state, lease.session, mix, track, work, started)
                } finally {
                    sessions.give(lease)
                    saveGate()
                }
                track.finish()
                track.file = cache.commit(key, part)
                committed = true
                lastTrack = key to track
                Log.d(
                    TAG,
                    "separated ${"%.1f".format(seconds)} s in ${(System.nanoTime() - started) / 1_000_000} ms " +
                        "(source $copyMs ms, decode $decodeMs ms, ${"%.0f".format(chunkMs ?: 0.0)} ms/chunk)",
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

    /** A committed plan: separate from [positionMs] until the playhead overtakes it or jumps. */
    private class Target(val positionMs: Long, val seekGeneration: Int)

    /**
     * Runs every chunk of the song, the listener's first, writing V into [track] as it's final, and
     * says [SingStatus.Ready] once V covers [SingReadiness.READY_AHEAD_MS] from the playhead.
     */
    private suspend fun separate(
        request: SingRequest,
        state: MutableStateFlow<SingStatus>,
        session: SeparationModel,
        mix: DecodedPcm,
        track: SingVocalTrack,
        work: SingWorkDir,
        startedNanos: Long,
    ) {
        val rate = mix.sampleRate
        val separator = LiveVocalSeparator(mix.left, mix.right, mix.frames, rate, work::floats)
        val sink = LiveVocalSeparator.VocalSink { from, to, left, right, offset -> track.writeVocal(from, to, left, right, offset) }
        separator.start(sink)
        val seconds = mix.frames.toDouble() / rate
        var ready = false
        var run = 0
        var expected = DEFAULT_READY_CHUNKS
        var target: Target? = null
        var lastChunk = -2
        var nextCoverageLog = COVERAGE_LOG_SECONDS
        while (true) {
            currentCoroutineContextCheck()
            if (!isActiveFor(request)) throw CancellationException("Sing cancelled")
            val playhead = playheadMs(request)
            val playFrame = playhead.positionMs * rate / 1000
            val seekGeneration = seeks[request.key] ?: 0
            // Where V should start: the playhead itself when it's paused or V is there (the forward
            // sweep then extends it), else where the playhead will be when the chunks are through.
            var why: String? = null
            val fromMs = if (!playhead.playing || track.isCovered(playFrame, playFrame + 1)) {
                target = null
                playhead.positionMs
            } else {
                val committed = target
                if (committed != null && committed.seekGeneration == seekGeneration &&
                    playhead.positionMs < committed.positionMs + OVERTAKEN_SLACK_MS
                ) {
                    committed.positionMs
                } else {
                    val perChunk = perChunkMs()
                    // Before Ready: the Ready span. After (a seek past V): enough to stay ahead.
                    val ahead = if (ready) perChunk.toLong() + SingReadiness.PLAN_MARGIN_MS else SingReadiness.READY_AHEAD_MS.toLong()
                    val lead = SingReadiness.planLead(perChunk, ahead) { startMs, chunks ->
                        coverEndMs(separator, track, playhead.positionMs, startMs, chunks)
                    }
                    if (!ready) expected = run + lead.chunks
                    why = "lead ${lead.leadMs} ms, ~${lead.chunks} chunks at ${"%.0f".format(perChunk)} ms/chunk" +
                        if (committed == null) "" else if (committed.seekGeneration != seekGeneration) ", after a seek" else ", overtaken"
                    Target(playhead.positionMs + lead.leadMs, seekGeneration).also { target = it }.positionMs
                }
            }
            val chunk = separator.nextChunk(separator.frameAtSource(fromMs * rate / 1000))
            if (chunk < 0) break
            if (why != null || (chunk != lastChunk + 1 && chunk != lastChunk - 1)) {
                Log.d(
                    TAG,
                    "start chunk $chunk/${separator.chunkCount} for ${"%.1f".format(fromMs / 1000.0)} s " +
                        "(playhead ${"%.1f".format(playhead.positionMs / 1000.0)} s${if (playhead.playing) "" else ", paused"}" +
                        (why?.let { ", $it" } ?: "") + ")",
                )
            }
            val t0 = System.nanoTime()
            separator.runChunk(chunk, session, sink)
            val ms = (System.nanoTime() - t0) / 1e6
            recordChunk(ms)
            lastChunk = chunk
            run++
            val covered = track.coverage * seconds
            if (covered >= nextCoverageLog || separator.isFinished) {
                Log.d(
                    TAG,
                    "coverage ${"%.0f".format(covered)}/${"%.0f".format(seconds)} s after $run chunks, " +
                        "${"%.0f".format(chunkMs ?: ms)} ms/chunk live (last ${"%.0f".format(ms)})",
                )
                nextCoverageLog = (floor(covered / COVERAGE_LOG_SECONDS) + 1) * COVERAGE_LOG_SECONDS
            }
            if (!ready) {
                val now = playheadMs(request).positionMs
                if (SingReadiness.isReadyAt(track, now)) {
                    ready = true
                    Log.d(
                        TAG,
                        "ready ${(System.nanoTime() - startedNanos) / 1_000_000} ms after the request, $run chunks " +
                            "(playhead ${"%.1f".format(now / 1000.0)} s)",
                    )
                    state.value = SingStatus.Ready(SingSource.Live(track))
                } else {
                    state.value = SingStatus.Preparing(
                        (0.1f + 0.85f * (run.toFloat() / maxOf(expected, run + 1))).coerceAtMost(0.95f),
                        SingStatus.Preparing.Stage.Working,
                    )
                }
            }
        }
        check(separator.isFinished && track.isComplete) { "vocal track incomplete" }
    }

    /**
     * For [SingReadiness.planLead]: where V would be covered (ms from the playhead at [playheadMs]) once
     * [chunks] chunks have run for a start [startMs] ahead — rounded down to the track's readiness
     * blocks, as [SingVocalTrack.isCovered] sees it.
     */
    private fun coverEndMs(separator: LiveVocalSeparator, track: SingVocalTrack, playheadMs: Long, startMs: Long, chunks: Int): Long {
        val rate = track.sampleRate
        val end = separator.finalEndAfter((playheadMs + startMs) * rate / 1000, chunks)
        if (end >= track.frames) return Long.MAX_VALUE / 4
        val blocks = end / SingVocalTrack.BLOCK_FRAMES * SingVocalTrack.BLOCK_FRAMES
        return blocks * 1000 / rate - playheadMs
    }

    /** The ms/chunk to plan with: as measured while playing, else the gate's benchmark × its load factor. */
    private fun perChunkMs(): Double =
        chunkMs ?: benchmarkMs?.let { SingDeviceGate.liveChunkMs(it).toDouble() } ?: DEFAULT_CHUNK_MS

    private fun recordChunk(ms: Double) {
        chunkMs = liveAverage(chunkMs, ms)
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
            benchmarkMs = try {
                // From the pool: the song's separation takes the same session, warm.
                val lease = sessions.take(model)
                try {
                    SingDeviceGate.benchmarkChunkMs(lease.session)
                } finally {
                    sessions.give(lease)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "benchmark failed", e)
                null
            }
            saveGate()
        }
        SingDeviceGate.evaluate(benchmarkMs, thermal).also {
            Log.d(TAG, "gate: $it (idle benchmark; live estimate ${benchmarkMs?.let(SingDeviceGate::liveChunkMs)} ms, measured ${chunkMs?.let { "%.0f".format(it) }} ms)")
        }
    }

    private fun openSession(model: File): SeparationModel {
        val t0 = System.nanoTime()
        return OrtSeparationModel.open(model, threads, modelStore.config.execution).also {
            Log.d(TAG, "session open ${(System.nanoTime() - t0) / 1_000_000} ms (${if (it.usesXnnpack) "XNNPACK" else "CPU"}, $threads threads)")
        }
    }

    private val gatePrefs by lazy { context.getSharedPreferences(GATE_PREFS, Context.MODE_PRIVATE) }

    private fun gateFingerprint() = "${android.os.Build.FINGERPRINT}|${modelStore.config.sha256}|$threads|${modelStore.config.execution}"

    private fun restoreGate() {
        gateRestored = true
        if (gatePrefs.getString(KEY_FINGERPRINT, null) != gateFingerprint()) return
        benchmarkMs = gatePrefs.getLong(KEY_BENCHMARK_MS, -1L).takeIf { it >= 0 }
        if (chunkMs == null) chunkMs = gatePrefs.getFloat(KEY_LIVE_CHUNK_MS, -1f).takeIf { it > 0f }?.toDouble()
    }

    private fun saveGate() {
        gatePrefs.edit {
            putString(KEY_FINGERPRINT, gateFingerprint())
            benchmarkMs?.let { putLong(KEY_BENCHMARK_MS, it) } ?: remove(KEY_BENCHMARK_MS)
            chunkMs?.let { putFloat(KEY_LIVE_CHUNK_MS, it.toFloat()) } ?: remove(KEY_LIVE_CHUNK_MS)
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
        private const val KEY_LIVE_CHUNK_MS = "live_chunk_ms"

        /** Until a chunk has been timed on this device, and with no benchmark either. */
        private const val DEFAULT_CHUNK_MS = 1_500.0

        /** The Preparing progress's guess at the chunks to Ready, before a lead is planned (paused). */
        private const val DEFAULT_READY_CHUNKS = 4

        /** A playhead report this far from where the last one said it would be is a seek. */
        private const val SEEK_JUMP_MS = 1_000L

        /** A plan is overtaken once the playhead passes its start by this much with V still not there. */
        private const val OVERTAKEN_SLACK_MS = 1_000L

        /** Coverage goes to the log every this many seconds of audio. */
        private const val COVERAGE_LOG_SECONDS = 10.0

        /** The next song's session opens when the separated song has this little left. */
        private const val PREWARM_BEFORE_END_MS = 20_000L

        /** Weight of the newest chunk in the live ms/chunk average. */
        private const val LIVE_ALPHA = 0.3

        /** The live ms/chunk average after a chunk of [ms]: the first one as it is, then a moving average. */
        internal fun liveAverage(previous: Double?, ms: Double): Double =
            if (previous == null) ms else previous * (1 - LIVE_ALPHA) + ms * LIVE_ALPHA

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
