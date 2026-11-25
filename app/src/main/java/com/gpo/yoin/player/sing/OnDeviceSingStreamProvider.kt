package com.gpo.yoin.player.sing

import android.content.Context
import androidx.core.content.edit
import androidx.core.net.toUri
import android.os.PowerManager
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import com.gpo.yoin.BuildConfig
import com.gpo.yoin.data.offline.OfflineDownloads
import com.gpo.yoin.player.OfflineFirstDataSource
import com.gpo.yoin.player.mediaCacheDataSourceFactory
import com.gpo.yoin.player.sing.dsp.IntervalSet
import com.gpo.yoin.player.sing.dsp.LiveVocalSeparator
import com.gpo.yoin.player.sing.dsp.SeparationModel
import java.io.File
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.math.floor
import okhttp3.OkHttpClient

/**
 * Lyra-Sing-Lite on this device, for Subsonic songs (streamed or downloaded), live: the original keeps
 * playing and the mixer takes the vocal out of it ([SingMixerAudioProcessor]) as soon as the vocal
 * stem V covers the next few seconds.
 *
 * A request decodes the song (X, as Media3 plays it: encoder delay and padding trimmed) as it's needed
 * ([SingStreamDecoder], straight from the player's data source chain — the bytes ExoPlayer already
 * cached aren't fetched again): a FLAC or WAV from where the listener is, then on to the end, then the
 * start; other formats in one streamed pass from the start. The model, the gate and the LiteRT session
 * ([SingSessionPool.prewarm]) get ready alongside. Each chunk runs as soon as the PCM it reads is in
 * ([LiveVocalSeparator.inputRange]; nothing near the end before the song's true length is known, as a
 * chunk there depends on it), X's mono reference going into the [SingVocalTrack] as it decodes, the
 * listener's place first. Chunk by chunk ([LiveVocalSeparator]) from where the listener is —
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
 * A song left unfinished (Sing off, another song, the app killed) keeps its partial track in the cache
 * ([SingCache.PARTIAL_SUFFIX], LRU like the rest); asked again, it is carried on — Ready at once if V
 * already covers the playhead, and only the missing blocks separated ([LiveVocalSeparator.markFinal]).
 *
 * Memory, per second of 44.1 kHz audio, all in memory-mapped files rather than the Java heap (they
 * count in the app's PSS, but are file pages the system can write back and drop): X, the decoded mix,
 * 2 × 4 B × 44 100 ≈ 353 KB; the 22.05 kHz mix and V, ≈ 353 KB; the vocal track, 6 B × 44 100
 * ≈ 265 KB — ≈ 970 KB/s, ~190 MB for a 3:16 song once separated whole. Plus the LiteRT session (the
 * model and its activations, native or GPU memory, freed when the session closes — see
 * [LiteRtSeparationModel]) and its two 2 MB tensor buffers.
 *
 * One song at a time; a request for another song cancels the one in progress (the listener moved on).
 * The first request runs the device gate ([SingDeviceGate]) once.
 */
@OptIn(UnstableApi::class)
class OnDeviceSingStreamProvider(
    private val context: Context,
    /** The model and what runs it: LiteRT, Tensor TPU → GPU → CPU ([SingSeparationBackend]). */
    private val backend: SingSeparationBackend,
    val cache: SingCache,
    /** Reads the original song's bytes: the downloads first, then the stream cache, then the network. */
    private val sourceFactory: DataSource.Factory,
    private val scope: CoroutineScope,
) : SingStreamProvider {
    private val states = ConcurrentHashMap<String, MutableStateFlow<SingStatus>>()
    private val jobs = ConcurrentHashMap<String, Job>()
    private val oneSongAtATime = Mutex()
    private val gateMutex = Mutex()

    /** The separation session, opened while a song decodes and kept warm between songs. */
    private val sessions = SingSessionPool(scope) { model -> backend.open(model) }

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

    /** Where the listener is: the request's position until the player's reports come in. */
    private val playheads = SingPlayheads()

    /** A seekable decode didn't line up once: this process decodes from the start from then on. */
    @Volatile private var seekUnreliable = false

    /** The last track handed out (finished or not), so a repeated request for the same song maps nothing new. */
    @Volatile private var lastTrack: Pair<String, SingVocalTrack>? = null

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
        playheads.report(request, positionMs, playing)
        if (playing) prewarmNearEnd(request, positionMs.coerceAtLeast(0L))
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
        val model = backend.readyModel ?: return
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
        playheads.forget(request)
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
        // From where the request says the listener is: the first plan, the decoder's first seek and the
        // kept-track check all come before the player's first report may have.
        playheads.begin(request)
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

    /** A seekable decode met segments that didn't line up: decode again from the start. */
    private class RetrySequential(cause: Throwable) : Exception(cause)

    private suspend fun prepare(request: SingRequest, state: MutableStateFlow<SingStatus>): SingStatus {
        val started = System.nanoTime()
        cachedTrack(request)?.let { return SingStatus.Ready(SingSource.Live(it)) }
        val uri = request.streamUri ?: return SingStatus.Unavailable(SingUnavailableReason.NoSource)
        var sequentialOnly = seekUnreliable
        var gated = false
        while (true) {
            try {
                return attempt(request, state, uri, started, sequentialOnly, gated) { gated = true }
            } catch (e: RetrySequential) {
                if (sequentialOnly) return SingStatus.Failed(SingFailure.Decode, e.cause?.message)
                Log.w(TAG, "decoding from the listener's place didn't line up (${e.cause?.message}): from the start", e.cause)
                // This process won't seek-decode again; what that decode made is not kept.
                seekUnreliable = true
                sequentialOnly = true
                dropPartial(cacheKey(request))
            }
        }
    }

    /**
     * One go at [request]: the decoder starts at once (from the listener's place when the format allows,
     * [SingStreamDecoder]) alongside the model, the gate and the session; a kept partial track is
     * carried on (Ready at once if it covers the playhead); then [separate] as the PCM comes in.
     */
    private suspend fun attempt(
        request: SingRequest,
        state: MutableStateFlow<SingStatus>,
        uri: String,
        started: Long,
        sequentialOnly: Boolean,
        gated: Boolean,
        onGated: () -> Unit,
    ): SingStatus = SingWorkDir(File(context.cacheDir, WORK_DIR)).use { work ->
        coroutineScope {
            val key = cacheKey(request)
            val reader = SingSourceReader(sourceFactory, uri.toUri())
            val decoder = SingStreamDecoder(reader, work, sequentialOnly)
            // A little before the listener: the first chunk reads ~3 s before the frames it makes.
            decoder.wantMs = (playheadMs(request).positionMs - FIRST_DECODE_BEFORE_MS).coerceAtLeast(0L)
            val decodeFailure = AtomicReference<Throwable?>(null)
            val decodeStart = System.nanoTime()
            var decodeEnd = 0L
            val decodeJob = launch(Dispatchers.IO) {
                try {
                    decoder.run { !isActive }
                    decodeEnd = System.nanoTime()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    decodeFailure.set(e)
                } finally {
                    reader.close()
                }
            }
            var track: SingVocalTrack? = null
            try {
                if (!gated) {
                    state.value = SingStatus.Preparing(stage = SingStatus.Preparing.Stage.Model)
                    val model = try {
                        backend.ensure { state.value = SingStatus.Preparing(it * 0.05f, SingStatus.Preparing.Stage.Model) }
                    } catch (e: IOException) {
                        Log.w(TAG, "model unavailable", e)
                        return@coroutineScope SingStatus.Failed(SingFailure.ModelUnavailable, e.message)
                    }
                    val gate = gate(model)
                    verdict = gate
                    if (gate is SingDeviceGate.Verdict.Insufficient) {
                        sessions.close()
                        return@coroutineScope SingStatus.Unavailable(gate.reason)
                    }
                    onGated()
                }
                val model = checkNotNull(backend.readyModel) { "model not ready" }
                // The session opens while the song is fetched and decoded: neither needs the other.
                sessions.prewarm(model)

                // A song Sing worked on before: carry on from what it kept.
                var ready = false
                track = resumeTrack(key)?.also { kept ->
                    Log.d(
                        TAG,
                        "carrying on a kept track: ${"%.0f".format(kept.coverage * 100)} % in" +
                            if (kept.droppedOnOpen > 0) " (${kept.droppedOnOpen} blocks failed their CRC)" else "",
                    )
                    if (SingReadiness.isReadyAt(kept, playheadMs(request).positionMs)) {
                        ready = true
                        Log.d(TAG, "ready ${(System.nanoTime() - started) / 1_000_000} ms after the request (kept track)")
                        state.value = SingStatus.Ready(SingSource.Live(kept))
                    }
                }
                if (!ready) state.value = SingStatus.Preparing(0.05f, SingStatus.Preparing.Stage.Reading)

                val pcm = try {
                    decoder.format.await()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: SingProgressivePcm.Misplaced) {
                    throw RetrySequential(e)
                } catch (e: Exception) {
                    return@coroutineScope decodeFailed(state, e)
                }
                Log.d(
                    TAG,
                    "first bytes ${nanosSince(started, reader.firstBytesNanos)} ms, header ${nanosSince(started, decoder.headerNanos)} ms, " +
                        "format ${(System.nanoTime() - started) / 1_000_000} ms after the request",
                )
                if (track != null && (track.sampleRate != pcm.sampleRate || track.frames > pcm.capacity)) {
                    Log.w(TAG, "kept track (${track.frames} frames @ ${track.sampleRate} Hz) doesn't fit the song as it decodes: starting over")
                    track = null
                    dropPartial(key)
                }
                val frames = track?.frames ?: pcm.estimatedFrames?.coerceAtMost(pcm.capacity) ?: run {
                    // The container doesn't say how long the song is: wait for the end.
                    while (pcm.exactFrames < 0) {
                        decodeFailure.get()?.let { throw if (it is SingProgressivePcm.Misplaced) it else DecodeFailed(it) }
                        delay(DECODE_POLL_MS)
                    }
                    pcm.exactFrames
                }
                if (frames > SingVocalTrack.MAX_FRAMES) return@coroutineScope SingStatus.Failed(SingFailure.Decode, "song too long")
                val working = track ?: SingVocalTrack.create(cache.partialFile(key), pcm.sampleRate, frames)
                track = working
                working.file?.let(cache::pin)
                lastTrack = key to working

                val waitStart = System.nanoTime()
                val lease = sessions.take(model)
                Log.d(TAG, "session in hand ${(System.nanoTime() - waitStart) / 1_000_000} ms after the format")
                val done = try {
                    separate(request, state, lease.session, pcm, decoder, decodeFailure, key, working, work, started, ready)
                } finally {
                    sessions.give(lease)
                    saveGate()
                }
                track = done
                done.finish()
                val part = checkNotNull(done.file)
                done.file = cache.commit(key, part)
                lastTrack = key to done
                val seconds = done.frames.toDouble() / done.sampleRate
                Log.d(
                    TAG,
                    "separated ${"%.1f".format(seconds)} s in ${(System.nanoTime() - started) / 1_000_000} ms " +
                        "(decode ${if (decodeEnd > 0) (decodeEnd - decodeStart) / 1_000_000 else -1} ms in ${decoder.segments} segment(s), " +
                        "${reader.bytesRead / 1024} KB read over ${reader.opens} connection(s), " +
                        "${"%.0f".format(chunkMs ?: 0.0)} ms/chunk)",
                )
                SingStatus.Ready(SingSource.Live(done))
            } catch (e: CancellationException) {
                throw e
            } catch (e: RetrySequential) {
                throw e
            } catch (e: SingProgressivePcm.Misplaced) {
                throw RetrySequential(e)
            } catch (e: DecodeFailed) {
                decodeFailed(state, e.cause ?: e)
            } catch (e: Exception) {
                Log.w(TAG, "separation failed", e)
                SingStatus.Failed(SingFailure.Inference, e.message)
            } finally {
                decodeJob.cancel()
                reader.abort()
                // Whatever isn't finished stays in the cache as a partial track, to be carried on.
                track?.file?.let { cache.unpin(it) }
                cache.trim()
            }
        }
    }

    /**
     * The song couldn't be read (the network went, say). Already Ready (a kept track covering the
     * listener, or enough separated): Sing stays on with what's there — the mixer lets the vocal
     * through where V is missing — and the next request carries on. Otherwise it failed.
     */
    private fun decodeFailed(state: MutableStateFlow<SingStatus>, e: Throwable): SingStatus {
        val shown = state.value as? SingStatus.Ready
        Log.w(TAG, if (shown != null) "decode failed; staying on with the vocal so far" else "decode failed", e)
        return shown ?: SingStatus.Failed(SingFailure.Decode, e.message)
    }

    /** The decoder gave up (not a misplaced segment): the song can't be read. */
    private class DecodeFailed(cause: Throwable) : Exception(cause)

    private fun nanosSince(started: Long, at: Long): Long = if (at == 0L) -1 else (at - started) / 1_000_000

    /**
     * [key]'s unfinished track: the one in memory (its flags as they are) or the kept file (checked
     * block by block); null when there's none.
     */
    private fun resumeTrack(key: String): SingVocalTrack? {
        lastTrack?.let { (k, track) ->
            if (k == key && !track.isComplete && track.file?.isFile == true) return track
        }
        val file = cache.getPartial(key) ?: return null
        val track = SingVocalTrack.open(file, partial = true)
        if (track == null) {
            // Not a partial track (cut short, an old version): nothing to carry on.
            file.delete()
            return null
        }
        // (All in but never stamped finished — the app died right then: it's finished below.)
        return track
    }

    private fun dropPartial(key: String) {
        lastTrack?.let { (k, track) -> if (k == key && !track.isComplete) lastTrack = null }
        cache.partialFile(key).delete()
    }

    /** A committed plan: separate from [positionMs] until the playhead overtakes it or jumps. */
    private class Target(val positionMs: Long, val seekGeneration: Int)

    /** A separator for [track]'s song over [pcm], skipping what [track] already has. */
    private fun separatorFor(pcm: SingProgressivePcm, track: SingVocalTrack, work: SingWorkDir): LiveVocalSeparator =
        LiveVocalSeparator(pcm.left, pcm.right, track.frames, track.sampleRate, work::floats).also { separator ->
            for ((from, to) in track.readyRanges()) separator.markFinal(from, to)
        }

    /**
     * Runs every chunk the song still needs, the listener's first, as their input decodes, writing V
     * (and the mix reference) into the track as it's final, and says [SingStatus.Ready] once V covers
     * [SingReadiness.READY_AHEAD_MS] from the playhead. Returns the finished track — [initial], or the
     * one remade at the song's true length if decoding found the container's estimate off.
     */
    private suspend fun separate(
        request: SingRequest,
        state: MutableStateFlow<SingStatus>,
        session: SeparationModel,
        pcm: SingProgressivePcm,
        decoder: SingStreamDecoder,
        decodeFailure: AtomicReference<Throwable?>,
        key: String,
        initial: SingVocalTrack,
        work: SingWorkDir,
        startedNanos: Long,
        readyAlready: Boolean,
    ): SingVocalTrack {
        var track = initial
        val rate = track.sampleRate
        var separator = separatorFor(pcm, track, work)
        var sink = LiveVocalSeparator.VocalSink { from, to, left, right, offset -> track.writeVocal(from, to, left, right, offset) }
        separator.start(sink)
        var references = IntervalSet()
        var seconds = track.frames.toDouble() / rate
        var ready = readyAlready
        var run = 0
        var expected = DEFAULT_READY_CHUNKS
        var target: Target? = null
        var lastChunk = -2
        var nextCoverageLog = COVERAGE_LOG_SECONDS
        var waitingFor = -1
        var firstInputLogged = false
        while (true) {
            currentCoroutineContextCheck()
            if (!isActiveFor(request)) throw CancellationException("Sing cancelled")
            decodeFailure.get()?.let { throw if (it is SingProgressivePcm.Misplaced) it else DecodeFailed(it) }
            val playhead = playheadMs(request)
            val playFrame = playhead.positionMs * rate / 1000

            // Decoding found the song's true length, and the track was sized by the container's estimate.
            val exact = pcm.exactFrames
            if (exact >= 0 && exact != track.frames) {
                if (exact > SingVocalTrack.MAX_FRAMES) throw DecodeFailed(IOException("song too long"))
                track = remake(key, track, exact)
                separator = separatorFor(pcm, track, work)
                sink = LiveVocalSeparator.VocalSink { from, to, left, right, offset -> track.writeVocal(from, to, left, right, offset) }
                separator.start(sink)
                references = IntervalSet()
                seconds = track.frames.toDouble() / rate
                target = null
                if (ready) state.value = SingStatus.Ready(SingSource.Live(track))
            }
            writeReferences(pcm, track, references, playFrame)
            if (separator.isFinished) break

            val seekGeneration = playheads.seekGeneration(request)
            // Where V should start: from the playhead's own readiness block when it's paused or V is
            // there (filling what's missing from there on, then on to the end), else where the playhead
            // will be when the chunks are through.
            var why: String? = null
            val following = !playhead.playing || track.isCovered(playFrame, playFrame + 1)
            val fromMs = if (following) {
                if (target != null || run == 0) why = if (playhead.playing) "V at the playhead" else "from its block"
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
            val chunk = if (following) {
                SingReadiness.chunkFromPlayhead(separator, track, fromMs)
            } else {
                SingReadiness.chunkForTarget(separator, track, fromMs)
            }
            if (chunk < 0) break

            // Its input decoded? Before the song's length is known, nothing near the estimated end runs:
            // what a chunk there computes depends on the length.
            val (inputFrom, inputTo) = separator.inputRange(chunk)
            val lengthKnown = pcm.exactFrames >= 0
            val tailBlocked = !lengthKnown && inputTo > track.frames - SingProgressivePcm.tailGuardFrames(track.frames, rate)
            if (tailBlocked || !pcm.isDecoded(inputFrom, inputTo)) {
                if (!tailBlocked) decoder.wantFrame = inputFrom.toLong()
                if (waitingFor != chunk) {
                    waitingFor = chunk
                    Log.d(
                        TAG,
                        "chunk $chunk waits for ${if (tailBlocked) "the song's length" else "its input"} " +
                            "(${"%.1f".format(inputFrom.toDouble() / rate)}–${"%.1f".format(inputTo.toDouble() / rate)} s)",
                    )
                }
                delay(DECODE_POLL_MS)
                continue
            }
            if (!firstInputLogged) {
                firstInputLogged = true
                Log.d(
                    TAG,
                    "decoded to the listener's place ${(System.nanoTime() - startedNanos) / 1_000_000} ms after the request " +
                        "(${"%.1f".format(inputFrom.toDouble() / rate)}–${"%.1f".format(inputTo.toDouble() / rate)} s)",
                )
            }
            waitingFor = -1
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
            if (run == 0) Log.d(TAG, "first chunk done ${(System.nanoTime() - startedNanos) / 1_000_000} ms after the request")
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
        // The mix reference for the whole song before it's kept (the decode is all in by now).
        while (!references.covers(0, track.frames)) {
            currentCoroutineContextCheck()
            decodeFailure.get()?.let { throw if (it is SingProgressivePcm.Misplaced) it else DecodeFailed(it) }
            if (!writeReferences(pcm, track, references, 0, budget = Int.MAX_VALUE)) delay(DECODE_POLL_MS)
        }
        check(separator.isFinished && track.isComplete) { "vocal track incomplete" }
        return track
    }

    /**
     * Writes the mix reference where the song is decoded and [done] doesn't have it yet, around
     * [priorityFrame] first (the aligner looks there), at most [budget] frames a call so a chunk never
     * waits long for it. True when it wrote anything.
     */
    private fun writeReferences(
        pcm: SingProgressivePcm,
        track: SingVocalTrack,
        done: IntervalSet,
        priorityFrame: Long,
        budget: Int = REFERENCE_BUDGET_SECONDS * track.sampleRate,
    ): Boolean {
        val todo = ArrayList<Pair<Int, Int>>()
        for ((from, to) in pcm.decodedRanges()) {
            val a = from.coerceIn(0, track.frames)
            val b = to.coerceIn(0, track.frames)
            if (a < b) todo += done.gaps(a, b)
        }
        if (todo.isEmpty()) return false
        val p = priorityFrame.coerceIn(0L, track.frames.toLong()).toInt() - track.sampleRate
        todo.sortBy { (from, to) -> if (to <= p) Int.MAX_VALUE - (p - to) else maxOf(0, from - p) }
        var left = budget
        for ((from, to) in todo) {
            if (left <= 0) break
            val start = if (to > p && from < p) maxOf(from, p) else from
            val end = minOf(to, start + left)
            track.writeReference(start, end, pcm.left, pcm.right)
            done.add(start, end)
            left -= end - start
        }
        return true
    }

    /**
     * [old] remade at the song's true length [frames] (the container's estimate was off), every whole
     * block it has carried over; it takes [old]'s place in the cache.
     */
    private fun remake(key: String, old: SingVocalTrack, frames: Int): SingVocalTrack {
        Log.d(TAG, "the song is $frames frames, not ${old.frames}: remaking the track")
        val file = cache.partialFile(key)
        val fresh = File(file.path + SingCache.REMAKE_SUFFIX)
        cache.pin(fresh)
        val track = SingVocalTrack.create(fresh, old.sampleRate, frames)
        track.copyReadyFrom(old)
        old.file?.let(cache::unpin)
        check(fresh.renameTo(file)) { "could not move the remade track in" }
        cache.unpin(fresh)
        track.file = file
        cache.pin(file)
        lastTrack = key to track
        return track
    }

    /**
     * For [SingReadiness.planLead]: where V would be covered (ms from the playhead at [playheadMs]) once
     * [chunks] chunks have run for a start [startMs] ahead — rounded down to the track's readiness
     * blocks, as [SingVocalTrack.isCovered] sees it.
     */
    private fun coverEndMs(separator: LiveVocalSeparator, track: SingVocalTrack, playheadMs: Long, startMs: Long, chunks: Int): Long {
        val rate = track.sampleRate
        val end = separator.finalEndAfter(SingReadiness.planFrame((playheadMs + startMs) * rate / 1000), chunks)
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

    /** The listener's position now: the last report (the request's before one), moved on if playing. */
    private fun playheadMs(request: SingRequest): SingPlayheads.Playhead = playheads.at(request)

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

    private val gatePrefs by lazy { context.getSharedPreferences(GATE_PREFS, Context.MODE_PRIVATE) }

    private fun gateFingerprint() = "${android.os.Build.FINGERPRINT}|${backend.gateTag}"

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

    private fun cacheKey(request: SingRequest) = SingCache.keyFor(request, backend.modelId)

    private fun isActiveFor(request: SingRequest): Boolean = jobs[request.key]?.isActive == true

    private suspend fun currentCoroutineContextCheck() = kotlinx.coroutines.currentCoroutineContext().ensureActive()

    /** Clears cached local model lookup after Labs downloads or deletes the model pack. */
    fun invalidateModelCache() {
        (backend as? LiteRtSingBackend)?.invalidateLocalCache()
    }

    companion object {
        private const val TAG = "YoinSing"
        const val WORK_DIR = "sing-work"
        const val CACHE_DIR = "sing-vocal"

        /** Where the quad .m4a results of the first engine were kept; cleared on start. */
        private const val LEGACY_CACHE_DIR = "sing"

        /** Where test builds kept the ONNX Runtime model (noBackupFilesDir); cleared on start. */
        private const val LEGACY_ORT_MODEL_DIR = "sing-model"
        private const val GATE_PREFS = "sing_gate"
        private const val KEY_FINGERPRINT = "fingerprint"
        private const val KEY_BENCHMARK_MS = "benchmark_ms"
        private const val KEY_LIVE_CHUNK_MS = "live_chunk_ms"

        /** Until a chunk has been timed on this device, and with no benchmark either. */
        private const val DEFAULT_CHUNK_MS = 1_500.0

        /** The Preparing progress's guess at the chunks to Ready, before a lead is planned (paused). */
        private const val DEFAULT_READY_CHUNKS = 4

        /** A plan is overtaken once the playhead passes its start by this much with V still not there. */
        private const val OVERTAKEN_SLACK_MS = 1_000L

        /** Decoding starts this far before the listener's place (a chunk's window and the resampler's reach). */
        private const val FIRST_DECODE_BEFORE_MS = 6_000L

        /** How often the separation looks again for the input a chunk waits on. */
        private const val DECODE_POLL_MS = 15L

        /** Mix reference written per chunk, at most (seconds of audio). */
        private const val REFERENCE_BUDGET_SECONDS = 20

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
                File(context.noBackupFilesDir, LEGACY_ORT_MODEL_DIR).deleteRecursively()
                cache.clearParts()
            }
            return OnDeviceSingStreamProvider(
                context = context,
                backend = createBackend(context),
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
         * LiteRT over the builds for this device: the Play AI Pack `sing_model_pack` (fetched on first Sing),
         * or in a `-PyoinSingBundledModels=true` APK the bundled ones first (Tensor TPU → GPU → CPU). With
         * neither (a sideloaded release, the pack unavailable) every request fails as
         * [SingFailure.ModelUnavailable] once the models are asked for.
         */
        private fun createBackend(context: Context): SingSeparationBackend =
            runCatching { LiteRtSingBackend(context, LiteRtSingBackend.resolver(context, BuildConfig.SING_BUNDLED_MODELS), defaultThreads()) }
                .onFailure { Log.w(TAG, "LiteRT unavailable here", it) }
                .getOrElse { NoSingBackend("LiteRT unavailable: ${it.message}") }

        /**
         * Big cores only, roughly: half the cores, 2..4 — the CPU backend's XNNPACK threads (the router's
         * last step; the NPU and GPU don't use them).
         */
        fun defaultThreads(): Int = (Runtime.getRuntime().availableProcessors() / 2).coerceIn(2, 4)
    }
}
