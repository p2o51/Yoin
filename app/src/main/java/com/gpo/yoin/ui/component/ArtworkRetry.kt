package com.gpo.yoin.ui.component

import coil3.network.HttpException
import java.io.IOException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withTimeoutOrNull

/**
 * The app-wide "ask failed artwork again" signal: a generation that moves when
 * the default network becomes available and when the app returns to the
 * foreground (both driven from YoinApplication). Only artwork whose load has
 * failed collects it ([ExpressiveMediaArtwork]); healthy artwork never does.
 */
internal object ArtworkRetrySignal {
    private val mutableGeneration = MutableStateFlow(0L)
    val generation: StateFlow<Long> = mutableGeneration.asStateFlow()

    fun bump() {
        mutableGeneration.update { it + 1 }
    }
}

internal enum class ArtworkFailureKind {
    /**
     * IOException or HTTP 5xx — 504 included, which is what Coil's
     * only-if-cached request answers while the device is offline. Retried on a
     * backoff while the artwork stays composed, and on every signal.
     */
    Transient,

    /** 404/410 and everything else (other 4xx, undecodable bytes): only a signal retries it. */
    AwaitSignal
}

internal object ArtworkRetryPolicy {
    /** The automatic retries of a [ArtworkFailureKind.Transient] failure, in order; then signals only. */
    val BackoffMillis: List<Long> = listOf(3_000L, 15_000L, 60_000L)

    fun classify(error: Throwable): ArtworkFailureKind {
        val causes = generateSequence(error) { it.cause }
        val http = causes.filterIsInstance<HttpException>().firstOrNull()
        return when {
            http != null ->
                if (http.response.code in 500..599) ArtworkFailureKind.Transient else ArtworkFailureKind.AwaitSignal
            causes.any { it is IOException } -> ArtworkFailureKind.Transient
            else -> ArtworkFailureKind.AwaitSignal
        }
    }
}

/**
 * One artwork's load-and-retry state, per model. [failure] is null while a
 * request is out (or has succeeded). [fallbackUnder] holds the type icon from
 * the first failure until a retried image has fully revealed over it, so a
 * recovery never cuts from icon to cover.
 */
internal data class ArtworkRetryState(
    /** [ArtworkRetrySignal]'s generation when the current request went out. */
    val requestSignal: Long,
    val failure: ArtworkFailureKind? = null,
    val backoffRetries: Int = 0,
    val fallbackUnder: Boolean = false
) {
    val requesting: Boolean get() = failure == null

    fun failed(error: Throwable): ArtworkRetryState =
        copy(failure = ArtworkRetryPolicy.classify(error), fallbackUnder = true)

    /** How long until this failure retries on its own; null when only a signal can retry it. */
    fun backoffMillis(): Long? = when (failure) {
        ArtworkFailureKind.Transient -> ArtworkRetryPolicy.BackoffMillis.getOrNull(backoffRetries)
        else -> null
    }

    /** A signal that moved after the request went out counts, even if it moved before the failure landed. */
    fun signalledBy(generation: Long): Boolean = generation != requestSignal

    fun retried(signal: Long, byBackoff: Boolean): ArtworkRetryState = copy(
        requestSignal = signal,
        failure = null,
        backoffRetries = if (byBackoff) backoffRetries + 1 else backoffRetries
    )

    fun revealed(): ArtworkRetryState = copy(fallbackUnder = false)
}

/**
 * Suspends a failed [state] until its next retry — the backoff elapsing or
 * [signal] moving, whichever is first — and returns the state that retry
 * starts from. Runs only while the failed artwork is composed.
 */
internal suspend fun awaitArtworkRetry(state: ArtworkRetryState, signal: StateFlow<Long>): ArtworkRetryState {
    val backoff = state.backoffMillis()
    val signalled = if (backoff == null) {
        signal.first { state.signalledBy(it) }
        true
    } else {
        withTimeoutOrNull(backoff) { signal.first { state.signalledBy(it) } } != null
    }
    return state.retried(signal = signal.value, byBackoff = !signalled)
}
