package com.gpo.yoin.ui.component

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.MotionDurationScale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.node.CompositionLocalConsumerModifierNode
import androidx.compose.ui.node.DrawModifierNode
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.node.currentValueOf
import androidx.compose.ui.platform.InspectorInfo
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.gpo.yoin.ui.experience.LocalMotionProfile
import com.gpo.yoin.ui.experience.MotionProfile
import kotlin.math.PI
import kotlin.math.sin

/*
 * 潮线 (dissolve-final §1.3): Home's status bar seam — and, since 2026-10-04,
 * the default look of every top seam under fixed chrome ([SeamTop.Chrome] in
 * [SeamTopStyle.Tide], cut by the viewport as a mask). No screen at all — two
 * waves of the page's own colour wash down from above the status bar, the
 * back one half clear, and content sinks under the water line. Over bare page
 * the fill is invisible; it only shows where content goes under. The phase
 * follows the scroll plus the afterglow and the height follows scroll speed
 * (the viewport's own [SeamFlow] — no second follower), so at rest the line
 * holds still. Same line as the Now Playing pill's wave.
 */

/**
 * Draws the tide over this element's content (put it on a full-screen
 * container above the scrolling page). [statusBarPx] is the status bar's
 * height; [scrolledPx] the page's scroll, as for the viewport's reveal.
 */
internal fun Modifier.seamTide(
    flow: SeamFlow,
    color: Color,
    statusBarPx: Float,
    scrolledPx: () -> Float,
): Modifier = this then SeamTideElement(flow, color, statusBarPx, scrolledPx)

private data class SeamTideElement(
    val flow: SeamFlow,
    val color: Color,
    val statusBarPx: Float,
    val scrolledPx: () -> Float,
) : ModifierNodeElement<SeamTideNode>() {
    override fun create() = SeamTideNode(flow, color, statusBarPx, scrolledPx)
    override fun update(node: SeamTideNode) {
        node.flow = flow
        node.color = color
        node.statusBarPx = statusBarPx
        node.scrolledPx = scrolledPx
    }
    override fun InspectorInfo.inspectableProperties() {
        name = "seamTide"
    }
}

private class SeamTideNode(
    flow: SeamFlow,
    color: Color,
    statusBarPx: Float,
    var scrolledPx: () -> Float,
) : Modifier.Node(),
    DrawModifierNode,
    CompositionLocalConsumerModifierNode {
    var flow by mutableStateOf(flow)
    var color by mutableStateOf(color)
    var statusBarPx by mutableStateOf(statusBarPx)
    private val path = Path()

    private fun reducedMotion(): Boolean =
        currentValueOf(LocalMotionProfile) == MotionProfile.AdaptiveReduced ||
            coroutineScope.coroutineContext[MotionDurationScale]?.scaleFactor == 0f

    override fun ContentDrawScope.draw() {
        drawContent()
        val reveal = seamReveal(scrolledPx(), SeamDissolveTokens.RevealDistance.toPx())
        if (reveal <= 0f) return
        val reduced = reducedMotion()
        val amplitude = tideAmplitudePx(flow, reduced)
        val phase = tidePhase(flow, reduced)
        val rest = statusBarPx + SeamDissolveTokens.TideRest.toPx()
        val base = tideBase(rest, SeamDissolveTokens.TideHidden.toPx(), reveal)
        drawTideWaves(path, base, amplitude, phase) { back ->
            drawPath(path, if (back) color.copy(alpha = color.alpha * SeamDissolveTokens.TideBackAlpha) else color)
        }
    }
}

// Shared with the chrome seam's tide ([SeamTopStyle.Tide], drawn by the
// viewport): one wave law, so the two lines are the same water.

/** The waves' height for the flow's stretch; the base height under reduced motion. */
internal fun Density.tideAmplitudePx(flow: SeamFlow, reduced: Boolean): Float {
    val stretch = if (reduced) 0f else flow.stretch
    return (SeamDissolveTokens.TideAmplitude + SeamDissolveTokens.TideAmplitudeStretched * stretch).toPx()
}

/** The waves' phase: the scroll travelled plus the afterglow; still under reduced motion. */
internal fun Density.tidePhase(flow: SeamFlow, reduced: Boolean): Float =
    if (reduced) 0f else (flow.travelPx + flow.lagPx) / SeamDissolveTokens.TidePhaseTravel.toPx()

/** The front line's base: [restPx] once revealed, [hiddenPx] above the top edge before. */
internal fun tideBase(restPx: Float, hiddenPx: Float, reveal: Float): Float =
    restPx * reveal - hiddenPx * (1f - reveal)

/** How far the front line's crests rise above [base], px. */
internal fun tideCrestPx(amplitude: Float): Float = amplitude * (1f + SeamDissolveTokens.TideHarmonic)

/** The lowest the back wave can reach below [base], px (for a layer that must hold both waves). */
internal fun Density.tideDepthPx(amplitude: Float): Float =
    SeamDissolveTokens.TideBackDrop.toPx() +
        amplitude * SeamDissolveTokens.TideBackAmplitude * (1f + SeamDissolveTokens.TideHarmonic)

/**
 * Where text under a chrome seam starts to fade, px below the seam: the
 * line's rest at the base height, following the line's reveal (the viewport's
 * rest/hidden law, less its inset) and never above the seam — so while the
 * line is still above the screen, text is not cut in open water.
 */
internal fun Density.tideTextSeamPx(reveal: Float): Float {
    val amplitude = SeamDissolveTokens.TideAmplitude.toPx()
    return tideBase(
        restPx = SeamDissolveTokens.TideRest.toPx() + tideCrestPx(amplitude),
        hiddenPx = maxOf(SeamDissolveTokens.TideHidden.toPx(), tideDepthPx(amplitude) + 1f),
        reveal = reveal,
    ).coerceAtLeast(0f)
}

/**
 * Builds the back wave into [path] and calls [drawWave] (back = true), then
 * the front line (back = false). Each wave closes from above the top edge
 * down to its line; the caller paints or masks with it.
 */
internal inline fun DrawScope.drawTideWaves(
    path: Path,
    base: Float,
    amplitude: Float,
    phase: Float,
    drawWave: (back: Boolean) -> Unit,
) {
    val margin = 20.dp.toPx()
    val step = SeamDissolveTokens.TideStep.toPx()
    // Back layer first, half clear; then the front line.
    path.setTideWave(
        width = size.width,
        base = base + SeamDissolveTokens.TideBackDrop.toPx(),
        amplitude = amplitude * SeamDissolveTokens.TideBackAmplitude,
        wavelength = SeamDissolveTokens.TideBackWavelength.toPx(),
        phase = .37f - phase * .7f,
        margin = margin,
        step = step,
    )
    drawWave(true)
    path.setTideWave(
        width = size.width,
        base = base,
        amplitude = amplitude,
        wavelength = SeamDissolveTokens.TideFrontWavelength.toPx(),
        phase = phase,
        margin = margin,
        step = step,
    )
    drawWave(false)
}

/** Closes this path from above the top edge down to the wave line. */
internal fun Path.setTideWave(
    width: Float,
    base: Float,
    amplitude: Float,
    wavelength: Float,
    phase: Float,
    margin: Float,
    step: Float,
) {
    rewind()
    moveTo(-margin, -margin)
    var x = -margin
    while (true) {
        val y = base + amplitude * (
            sin(((x / wavelength) + phase) * TideTau) +
                SeamDissolveTokens.TideHarmonic * sin(((x / (wavelength * .5f)) - phase * 1.7f + .3f) * TideTau)
            )
        lineTo(x, y)
        if (x >= width + margin) break
        x = minOf(x + step, width + margin)
    }
    lineTo(width + margin, -margin)
    close()
}

private const val TideTau = (PI * 2).toFloat()
