package com.gpo.yoin.ui.component

import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.TargetBasedAnimation
import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.core.withInfiniteAnimationFrameNanos
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.MotionDurationScale
import com.gpo.yoin.ui.theme.YoinMotion
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive

internal val LocalPlaybackWaveState = staticCompositionLocalOf<PlaybackWaveState?> { null }

/** The shell and detail windows draw one wave, including its play/pause spring. */
class PlaybackWaveState {
    var phase by mutableFloatStateOf(0f)
        private set
    var amplitude by mutableFloatStateOf(0f)
        private set
    private var playing: Boolean? = null
    private var lastFrameNanos: Long? = null
    private var cycleNanos = 0L
    private var amplitudePlayTime = 0L
    private var amplitudeAnimation: TargetBasedAnimation<Float, AnimationVector1D>? = null
    private var renderers = 0

    internal fun attach() {
        if (renderers++ == 0) lastFrameNanos = null
    }

    internal fun detach() {
        renderers--
    }

    internal val needsFrames: Boolean get() = playing == true || amplitudeAnimation != null

    internal fun setPlaying(value: Boolean, spec: FiniteAnimationSpec<Float>) {
        if (playing == value) return
        val target = if (value) 1f else 0f
        if (playing == null) {
            amplitude = target
        } else {
            amplitudeAnimation = TargetBasedAnimation(
                animationSpec = spec,
                typeConverter = Float.VectorConverter,
                initialValue = amplitude,
                targetValue = target,
                initialVelocity = amplitudeAnimation?.getVelocityVectorFromNanos(amplitudePlayTime)?.value ?: 0f
            )
            amplitudePlayTime = 0L
        }
        playing = value
        lastFrameNanos = null
    }

    internal fun onFrame(frameNanos: Long, durationScale: Float) {
        val previous = lastFrameNanos
        // Several windows may present this vsync; advance only once.
        if (previous != null && frameNanos <= previous) return
        lastFrameNanos = frameNanos
        if (durationScale == 0f) {
            amplitude = if (playing == true) 1f else 0f
            amplitudeAnimation = null
            return
        }
        val elapsed = ((frameNanos - (previous ?: frameNanos)) / durationScale).toLong()
        cycleNanos = (cycleNanos + elapsed) % WAVE_PERIOD_NANOS
        phase = cycleNanos.toFloat() / WAVE_PERIOD_NANOS * (2f * Math.PI.toFloat())
        amplitudeAnimation?.let { animation ->
            amplitudePlayTime += elapsed
            amplitude = animation.getValueFromNanos(amplitudePlayTime)
            if (animation.isFinishedFromNanos(amplitudePlayTime)) amplitudeAnimation = null
        }
    }
}

@Composable
internal fun rememberPlaybackWave(isPlaying: Boolean): PlaybackWaveState {
    val shared = LocalPlaybackWaveState.current
    val wave = shared ?: remember { PlaybackWaveState() }
    val spec = YoinMotion.defaultSpatialSpec<Float>()
    DisposableEffect(wave) {
        wave.attach()
        onDispose { wave.detach() }
    }
    SideEffect { wave.setPlaying(isPlaying, spec) }
    // Each visible composition supplies vsyncs to the SAME state. The window's
    // frame clock pauses off-screen; there is no app-wide background ticker.
    LaunchedEffect(wave, isPlaying) {
        val scale = currentCoroutineContext()[MotionDurationScale]
        do {
            withInfiniteAnimationFrameNanos { wave.onFrame(it, scale?.scaleFactor ?: 1f) }
            if (scale?.scaleFactor == 0f) snapshotFlow { scale.scaleFactor }.first { it > 0f }
        } while (isActive && wave.needsFrames)
    }
    return wave
}

private const val WAVE_PERIOD_NANOS = 3_000_000_000L
