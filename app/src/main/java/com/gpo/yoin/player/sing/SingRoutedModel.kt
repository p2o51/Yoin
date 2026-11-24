package com.gpo.yoin.player.sing

import com.gpo.yoin.player.sing.dsp.SeparationModel

/**
 * The backend router: [candidates] in order (for LiteRT: Tensor TPU, GPU, CPU — [SingLiteRtPlan]); the
 * first that opens runs. A chunk that throws, or comes back with a NaN or an infinity (fp16 overflow on a
 * GPU), closes that backend, opens the next and runs the chunk again there; with none left the failure
 * goes to the caller. One backend open at a time, so moving on frees the last one's memory first.
 *
 * Not thread-safe: one caller at a time, as [SeparationModel] (the session pool hands a session to one
 * song at a time). [open] failing at construction with every candidate throws [NoBackend].
 */
class SingRoutedModel<C : Any>(
    candidates: List<C>,
    private val open: (C) -> SeparationModel,
    /** A candidate's first good chunk: it is the one running (logs, the Tensor TPU label). */
    private val onRunning: (C) -> Unit = {},
    /** A candidate failed to open or to run (logs). */
    private val onFailure: (C, Throwable) -> Unit = { _, _ -> },
) : SeparationModel {
    class NoBackend(message: String) : Exception(message)

    private val queue = ArrayDeque(candidates)
    private var model: SeparationModel? = null
    private var confirmed = false
    private var closed = false

    /** The candidate open now (null once closed). */
    var active: C? = null
        private set

    /** Backends given up on after the first one opened: the device gate warms up again after one. */
    var switches = 0
        private set

    /** Each candidate tried and why it was left, in order (for logs and tests). */
    val failures = mutableListOf<Pair<C, Throwable>>()

    init {
        require(candidates.isNotEmpty()) { "no candidates" }
        openNext()
    }

    override fun run(input: FloatArray, output: FloatArray) {
        while (true) {
            check(!closed) { "session closed" }
            val current = checkNotNull(model)
            val failure = try {
                current.run(input, output)
                if (allFinite(output)) null else NonFinite()
            } catch (e: InterruptedException) {
                throw e
            } catch (e: Exception) {
                e
            }
            if (failure == null) {
                if (!confirmed) {
                    confirmed = true
                    active?.let(onRunning)
                }
                return
            }
            val failed = checkNotNull(active)
            record(failed, failure)
            runCatching { current.close() }
            model = null
            active = null
            switches++
            openNext()
        }
    }

    override fun close() {
        if (closed) return
        closed = true
        active = null
        model?.let { runCatching { it.close() } }
        model = null
    }

    private fun openNext() {
        while (true) {
            val next = queue.removeFirstOrNull()
                ?: throw NoBackend("no backend left: " + failures.joinToString { (c, e) -> "$c: ${e.message ?: e.javaClass.simpleName}" })
            try {
                model = open(next)
                active = next
                confirmed = false
                return
            } catch (e: InterruptedException) {
                throw e
            } catch (e: Exception) {
                record(next, e)
            } catch (e: LinkageError) {
                // A native library that wouldn't load (UnsatisfiedLinkError): this backend can't run here.
                record(next, e)
            }
        }
    }

    private fun record(candidate: C, failure: Throwable) {
        failures += candidate to failure
        onFailure(candidate, failure)
    }

    /** A chunk with a NaN or an infinity in it. */
    class NonFinite : Exception("non-finite output")

    companion object {
        internal fun allFinite(values: FloatArray): Boolean {
            for (v in values) if (!v.isFinite()) return false
            return true
        }
    }
}
