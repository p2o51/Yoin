package com.gpo.yoin.ui.experience

import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.animate
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * Home edit mode's progress P (0 = feed, 1 = editing). The shell controller
 * is its only writer; Home and the bar read [value] in draw and layout only.
 *
 * Unlike an `Animatable`, [snapTo] is synchronous (a snap exit lands in the
 * same frame as the surface change), and a retarget keeps the velocity it
 * had mid-flight, as the prototype's springs do.
 */
@Stable
class HomeEditProgress {
    private var current by mutableFloatStateOf(0f)

    /** Bumped by every snap or retarget; a superseded animation stops writing. */
    private var generation = 0
    private var job: Job? = null

    /** Snapshot-backed: read it in draw, layer or layout lambdas, never in composition. */
    val value: Float get() = current

    /** Units per second, from the last frame of the running animation. */
    var velocity: Float = 0f
        private set

    var target by mutableFloatStateOf(0f)
        private set

    var isAnimating by mutableStateOf(false)
        private set

    /** Cancels any animation and lands on [value] now, at rest. */
    fun snapTo(value: Float) {
        generation++
        job?.cancel()
        job = null
        current = value
        velocity = 0f
        target = value
        isAnimating = false
    }

    /** Springs to [target] from the current value, keeping the current velocity. */
    fun animateTo(scope: CoroutineScope, target: Float, spec: AnimationSpec<Float>) {
        val run = ++generation
        job?.cancel()
        this.target = target
        isAnimating = true
        val from = current
        val fromVelocity = velocity
        val next = scope.launch {
            animate(
                initialValue = from,
                targetValue = target,
                initialVelocity = fromVelocity,
                animationSpec = spec,
            ) { value, velocity ->
                if (generation == run) {
                    current = value
                    this@HomeEditProgress.velocity = velocity
                }
            }
        }
        job = next
        next.invokeOnCompletion {
            if (generation == run) {
                job = null
                velocity = 0f
                isAnimating = false
            }
        }
    }

    /** Suspends until no animation is running, following any retarget that supersedes the one it joined. */
    suspend fun awaitSettled() {
        while (true) {
            val live = job ?: return
            live.join()
            if (job === live) return
        }
    }
}
