package com.gpo.yoin.ui.theme

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MotionScheme
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * Number of discrete steps a color wash is quantized into. Each step emits exactly one
 * new [ColorScheme] instance; animation frames between steps reuse the previous one.
 */
private const val COLOR_WASH_STEPS = 12

/**
 * Smoothly transitions **every** token in [targetColorScheme] using the theme's
 * default effects motion bucket.
 *
 * When the target changes — e.g. new album cover → new palette — all colors animate
 * in concert (one shared spring), producing a seamless global wash across the entire app.
 *
 * PERFORMANCE CONSTRAINT — every distinct [ColorScheme] instance returned here
 * recomposes the entire app: it feeds both [MaterialTheme]'s internal static
 * CompositionLocal and [LocalYoinColors], whose providers compare by identity.
 * Emit as few instances as possible: the wash is driven by a single progress
 * animation quantized into [COLOR_WASH_STEPS] steps (one new instance per step),
 * frames between steps return the same instance, and at rest the exact
 * [targetColorScheme] instance is returned so steady state does zero work.
 * Do not regress this to per-frame instance creation (e.g. per-token
 * animateColorAsState + rebuilding the scheme in the composable body).
 *
 * Both locals are static, so a new instance turns skipping off for the whole
 * tree below: every step re-runs every composable in the window. On a phone
 * that fits in a frame; in the tablet's three panes (Home | album | Now
 * Playing) one step cost ~40 ms in a release build (recompose 23–34 ms plus
 * layout and draw), so the wash became a run of back-to-back long frames and
 * the song-change motion stuttered. [ColorWashBudget] times each step's frame;
 * a window that can't afford a step lands the wash in one cut instead.
 */
@Composable
fun animateColorScheme(
    targetColorScheme: ColorScheme,
    darkTheme: Boolean,
    motionScheme: MotionScheme,
    sharedTransition: ColorSchemeTransition? = null,
): ColorScheme {
    // Same motion bucket the per-token animation used; the spring parameters are
    // identical regardless of the animated type, so driving a single 0→1 progress
    // and lerping every token by it reproduces the exact same motion feel.
    val spec = YoinMotion.defaultEffectsSpec<Float>(
        role = YoinMotionRole.Expressive,
        expressiveScheme = motionScheme,
    )

    val localScope = rememberCoroutineScope()
    val transition = sharedTransition ?: remember { ColorSchemeTransition(localScope) }
    val displayed = transition.currentOrInitial(targetColorScheme)
    SideEffect { transition.animateTo(targetColorScheme, darkTheme, spec) }
    return displayed
}

/** One displayed palette and effects spring, retained across Activity compositions. */
class ColorSchemeTransition(private val scope: CoroutineScope) {
    private var displayed by mutableStateOf<ColorScheme?>(null)
    private var target: ColorScheme? = null
    private var animation: Job? = null
    private val budget = ColorWashBudget()

    internal fun currentOrInitial(initial: ColorScheme): ColorScheme =
        displayed ?: initial.also { displayed = it; target = it }

    internal fun animateTo(
        destination: ColorScheme,
        darkTheme: Boolean,
        spec: FiniteAnimationSpec<Float>,
    ) {
        // Multiple windows observe the same target. Attaching another window
        // must neither restart the wash nor jump to its destination.
        if (target?.let { sameAnimatedTokens(it, destination) } == true) return
        target = destination
        animation?.cancel()
        val from = currentOrInitial(destination)
        if (sameAnimatedTokens(from, destination)) return
        animation = scope.launch {
            budget.forgetMark()
            if (budget.cutsOnly) {
                // This window can't afford a step: one cut, and check it still can't.
                withFrameNanos {
                    displayed = destination
                    budget.mark(System.nanoTime())
                }
                withFrameNanos { budget.measure(System.nanoTime()) }
                return@launch
            }
            var lastStep = 0
            Animatable(0f).animateTo(targetValue = 1f, animationSpec = spec) {
                if (budget.measure(System.nanoTime()) == true) {
                    // The step we just sent took the whole window too long; land now.
                    lastStep = COLOR_WASH_STEPS
                    displayed = destination
                }
                val step = (value * COLOR_WASH_STEPS).toInt().coerceIn(0, COLOR_WASH_STEPS)
                if (step > lastStep) {
                    lastStep = step
                    displayed = if (step == COLOR_WASH_STEPS) {
                        destination
                    } else {
                        lerpColorScheme(from, destination, step / COLOR_WASH_STEPS.toFloat(), darkTheme)
                    }
                    budget.mark(System.nanoTime())
                }
            }
            displayed = destination
        }
    }
}

/**
 * Decides whether this window can afford a stepped wash. Each step recomposes
 * the whole window (see [animateColorScheme]). The frame after a step (or a
 * cut) is timed: over [budgetNanos] and the window is too heavy, so the wash
 * lands at once and later washes cut in one recomposition; within budget and
 * washes step again. Phones step as before; the tablet's three panes, where a
 * step costs ~40 ms in release, cut.
 */
internal class ColorWashBudget(
    private val budgetNanos: Long = COLOR_WASH_FRAME_BUDGET_NANOS,
) {
    /** True once a step or cut ran over budget, until a cut fits again. */
    var cutsOnly = false
        private set
    private var markedAt = NONE

    /** A step or cut went out on this frame. */
    fun mark(nowNanos: Long) {
        markedAt = nowNanos
    }

    /** Drops a mark whose frame was never measured (cancelled or settled wash). */
    fun forgetMark() {
        markedAt = NONE
    }

    /**
     * Called on the next frame. Null when nothing was marked; otherwise
     * whether the marked frame ran over budget.
     */
    fun measure(nowNanos: Long): Boolean? {
        if (markedAt == NONE) return null
        val heavy = nowNanos - markedAt > budgetNanos
        markedAt = NONE
        cutsOnly = heavy
        return heavy
    }

    private companion object {
        const val NONE = Long.MIN_VALUE
    }
}

/** A step frame longer than this (nearly two 60 Hz frames) is too heavy to step. */
internal const val COLOR_WASH_FRAME_BUDGET_NANOS = 30_000_000L

/**
 * True when the 36 wash-animated tokens of [a] and [b] are identical — used to skip
 * no-op washes for value-equal target instances. The 12 Fixed-tone tokens are
 * deliberately ignored (unused app-wide, never animated).
 */
private fun sameAnimatedTokens(a: ColorScheme, b: ColorScheme): Boolean =
    a.primary == b.primary &&
        a.onPrimary == b.onPrimary &&
        a.primaryContainer == b.primaryContainer &&
        a.onPrimaryContainer == b.onPrimaryContainer &&
        a.inversePrimary == b.inversePrimary &&
        a.secondary == b.secondary &&
        a.onSecondary == b.onSecondary &&
        a.secondaryContainer == b.secondaryContainer &&
        a.onSecondaryContainer == b.onSecondaryContainer &&
        a.tertiary == b.tertiary &&
        a.onTertiary == b.onTertiary &&
        a.tertiaryContainer == b.tertiaryContainer &&
        a.onTertiaryContainer == b.onTertiaryContainer &&
        a.background == b.background &&
        a.onBackground == b.onBackground &&
        a.surface == b.surface &&
        a.onSurface == b.onSurface &&
        a.surfaceVariant == b.surfaceVariant &&
        a.onSurfaceVariant == b.onSurfaceVariant &&
        a.surfaceTint == b.surfaceTint &&
        a.inverseSurface == b.inverseSurface &&
        a.inverseOnSurface == b.inverseOnSurface &&
        a.error == b.error &&
        a.onError == b.onError &&
        a.errorContainer == b.errorContainer &&
        a.onErrorContainer == b.onErrorContainer &&
        a.outline == b.outline &&
        a.outlineVariant == b.outlineVariant &&
        a.scrim == b.scrim &&
        a.surfaceBright == b.surfaceBright &&
        a.surfaceDim == b.surfaceDim &&
        a.surfaceContainer == b.surfaceContainer &&
        a.surfaceContainerHigh == b.surfaceContainerHigh &&
        a.surfaceContainerHighest == b.surfaceContainerHighest &&
        a.surfaceContainerLow == b.surfaceContainerLow &&
        a.surfaceContainerLowest == b.surfaceContainerLowest

/**
 * Lerps the 36 tokens the wash animates and rebuilds the scheme via
 * [darkColorScheme] / [lightColorScheme]. The 12 Fixed-tone tokens are intentionally
 * left at the builders' baseline defaults — exactly what the previous per-token
 * animation did (they are unused app-wide).
 */
private fun lerpColorScheme(
    from: ColorScheme,
    to: ColorScheme,
    fraction: Float,
    darkTheme: Boolean,
): ColorScheme {
    fun token(select: (ColorScheme) -> Color): Color =
        lerp(select(from), select(to), fraction)

    return if (darkTheme) {
        darkColorScheme(
            primary = token { it.primary },
            onPrimary = token { it.onPrimary },
            primaryContainer = token { it.primaryContainer },
            onPrimaryContainer = token { it.onPrimaryContainer },
            inversePrimary = token { it.inversePrimary },
            secondary = token { it.secondary },
            onSecondary = token { it.onSecondary },
            secondaryContainer = token { it.secondaryContainer },
            onSecondaryContainer = token { it.onSecondaryContainer },
            tertiary = token { it.tertiary },
            onTertiary = token { it.onTertiary },
            tertiaryContainer = token { it.tertiaryContainer },
            onTertiaryContainer = token { it.onTertiaryContainer },
            background = token { it.background },
            onBackground = token { it.onBackground },
            surface = token { it.surface },
            onSurface = token { it.onSurface },
            surfaceVariant = token { it.surfaceVariant },
            onSurfaceVariant = token { it.onSurfaceVariant },
            surfaceTint = token { it.surfaceTint },
            inverseSurface = token { it.inverseSurface },
            inverseOnSurface = token { it.inverseOnSurface },
            error = token { it.error },
            onError = token { it.onError },
            errorContainer = token { it.errorContainer },
            onErrorContainer = token { it.onErrorContainer },
            outline = token { it.outline },
            outlineVariant = token { it.outlineVariant },
            scrim = token { it.scrim },
            surfaceBright = token { it.surfaceBright },
            surfaceDim = token { it.surfaceDim },
            surfaceContainer = token { it.surfaceContainer },
            surfaceContainerHigh = token { it.surfaceContainerHigh },
            surfaceContainerHighest = token { it.surfaceContainerHighest },
            surfaceContainerLow = token { it.surfaceContainerLow },
            surfaceContainerLowest = token { it.surfaceContainerLowest },
        )
    } else {
        lightColorScheme(
            primary = token { it.primary },
            onPrimary = token { it.onPrimary },
            primaryContainer = token { it.primaryContainer },
            onPrimaryContainer = token { it.onPrimaryContainer },
            inversePrimary = token { it.inversePrimary },
            secondary = token { it.secondary },
            onSecondary = token { it.onSecondary },
            secondaryContainer = token { it.secondaryContainer },
            onSecondaryContainer = token { it.onSecondaryContainer },
            tertiary = token { it.tertiary },
            onTertiary = token { it.onTertiary },
            tertiaryContainer = token { it.tertiaryContainer },
            onTertiaryContainer = token { it.onTertiaryContainer },
            background = token { it.background },
            onBackground = token { it.onBackground },
            surface = token { it.surface },
            onSurface = token { it.onSurface },
            surfaceVariant = token { it.surfaceVariant },
            onSurfaceVariant = token { it.onSurfaceVariant },
            surfaceTint = token { it.surfaceTint },
            inverseSurface = token { it.inverseSurface },
            inverseOnSurface = token { it.inverseOnSurface },
            error = token { it.error },
            onError = token { it.onError },
            errorContainer = token { it.errorContainer },
            onErrorContainer = token { it.onErrorContainer },
            outline = token { it.outline },
            outlineVariant = token { it.outlineVariant },
            scrim = token { it.scrim },
            surfaceBright = token { it.surfaceBright },
            surfaceDim = token { it.surfaceDim },
            surfaceContainer = token { it.surfaceContainer },
            surfaceContainerHigh = token { it.surfaceContainerHigh },
            surfaceContainerHighest = token { it.surfaceContainerHighest },
            surfaceContainerLow = token { it.surfaceContainerLow },
            surfaceContainerLowest = token { it.surfaceContainerLowest },
        )
    }
}

// ── Preview ──────────────────────────────────────────────────────────────

@OptIn(ExperimentalLayoutApi::class)
@Preview(showBackground = true, backgroundColor = 0xFF1C1B1F)
@Composable
private fun AnimatedColorSchemePreview() {
    val animated = animateColorScheme(
        targetColorScheme = YoinDarkColorScheme,
        darkTheme = true,
        motionScheme = MotionScheme.expressive(),
    )
    MaterialTheme(colorScheme = animated) {
        val cs = MaterialTheme.colorScheme
        FlowRow(
            modifier = Modifier.padding(8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            listOf(
                "Primary" to cs.primary,
                "Secondary" to cs.secondary,
                "Surface" to cs.surface,
                "Background" to cs.background,
            ).forEach { (label, color) ->
                Box(
                    modifier = Modifier
                        .size(48.dp)
                        .background(color, shape = YoinShapes.medium),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = label.take(3),
                        style = MaterialTheme.typography.labelSmall,
                        color = cs.onSurface,
                    )
                }
            }
        }
    }
}
