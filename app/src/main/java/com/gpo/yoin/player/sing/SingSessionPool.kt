package com.gpo.yoin.player.sing

import com.gpo.yoin.player.sing.dsp.SeparationModel
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * At most one separation session kept warm, so a song doesn't wait for the ORT session to open
 * (seconds on a Pixel Tablet) more often than it must:
 *
 * - [prewarm] starts opening one in the background — while the song is fetched and decoded, which
 *   needs no session, or as the song before nears its end — unless one is warm or on its way.
 * - [take] hands it out: warm, still opening (awaited), or opened now. The caller owns it until [give].
 * - [give] puts it back; unused for [idleMs] it is closed (race to idle, a little later).
 * - [close] (Sing off) closes the idle one now, and the one in use as soon as it comes back.
 *
 * Opening runs in [scope], not the caller's coroutine: ORT can't be interrupted mid-open, so a caller
 * cancelled meanwhile leaves the session warm for the next song instead of leaking it.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SingSessionPool(
    private val scope: CoroutineScope,
    private val idleMs: Long = IDLE_MS,
    private val open: (File) -> SeparationModel,
) {
    /** A session out of the pool; give it back with [give]. */
    class Lease internal constructor(val session: SeparationModel, internal val model: File, internal val generation: Int)

    private val lock = Any()
    private var model: File? = null
    private var warm: Deferred<SeparationModel>? = null
    private var idleClose: Job? = null
    private var generation = 0

    /** Whether a session is warm or opening (for tests and logs). */
    val isWarm: Boolean get() = synchronized(lock) { warm != null }

    fun prewarm(model: File) {
        synchronized(lock) { prewarmLocked(model) }
    }

    /** The session for [model]: the warm one, the one opening (awaited), or a new one. */
    suspend fun take(model: File): Lease {
        val (pending, gen) = synchronized(lock) {
            prewarmLocked(model)
            idleClose?.cancel()
            idleClose = null
            val d = checkNotNull(warm)
            warm = null
            this.model = null
            d to generation
        }
        try {
            return Lease(pending.await(), model, gen)
        } catch (e: CancellationException) {
            // The caller is gone, the open goes on: keep what it opens warm (or closed, after Sing off).
            pending.invokeOnCompletion { cause -> if (cause == null) give(Lease(pending.getCompleted(), model, gen)) }
            throw e
        }
    }

    fun give(lease: Lease) {
        synchronized(lock) {
            if (lease.generation != generation || warm != null) {
                closeQuietly(lease.session)
                return
            }
            model = lease.model
            warm = CompletableDeferred(lease.session)
            scheduleCloseLocked()
        }
    }

    /** Sing is off: nothing stays warm. */
    fun close() {
        synchronized(lock) {
            generation++
            discardLocked()
        }
    }

    private fun prewarmLocked(model: File) {
        val current = warm
        if (current != null && this.model == model && !current.isCancelled) {
            scheduleCloseLocked()
            return
        }
        discardLocked()
        this.model = model
        warm = scope.async { open(model) }
        scheduleCloseLocked()
    }

    private fun discardLocked() {
        idleClose?.cancel()
        idleClose = null
        val d = warm ?: return
        warm = null
        model = null
        d.invokeOnCompletion { cause -> if (cause == null) closeQuietly(d.getCompleted()) }
    }

    private fun scheduleCloseLocked() {
        idleClose?.cancel()
        val d = warm
        idleClose = scope.launch {
            delay(idleMs)
            synchronized(lock) { if (warm === d) discardLocked() }
        }
    }

    private fun closeQuietly(session: SeparationModel) {
        runCatching { session.close() }
    }

    companion object {
        /** A session nobody took for this long is closed. */
        const val IDLE_MS = 60_000L
    }
}
