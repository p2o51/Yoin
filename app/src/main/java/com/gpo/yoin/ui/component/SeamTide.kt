package com.gpo.yoin.ui.component

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.MotionDurationScale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.node.CompositionLocalConsumerModifierNode
import androidx.compose.ui.node.DrawModifierNode
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.node.currentValueOf
import androidx.compose.ui.platform.InspectorInfo
import androidx.compose.ui.unit.dp
import com.gpo.yoin.ui.experience.LocalMotionProfile
import com.gpo.yoin.ui.experience.MotionProfile
import kotlin.math.PI
import kotlin.math.sin

/*
 * 潮线 (dissolve-final §1.3): Home's status bar seam. No screen at all — two
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
        val stretch = if (reduced) 0f else flow.stretch
        val amplitude = (SeamDissolveTokens.TideAmplitude + SeamDissolveTokens.TideAmplitudeStretched * stretch).toPx()
        val phase = if (reduced) 0f else (flow.travelPx + flow.lagPx) / SeamDissolveTokens.TidePhaseTravel.toPx()
        val rest = statusBarPx + SeamDissolveTokens.TideRest.toPx()
        val base = rest * reveal - SeamDissolveTokens.TideHidden.toPx() * (1f - reveal)
        // Back layer first, half clear; then the front line.
        wave(
            base = base + SeamDissolveTokens.TideBackDrop.toPx(),
            amplitude = amplitude * SeamDissolveTokens.TideBackAmplitude,
            wavelength = SeamDissolveTokens.TideBackWavelength.toPx(),
            phase = .37f - phase * .7f,
        )
        drawPath(path, color.copy(alpha = color.alpha * SeamDissolveTokens.TideBackAlpha))
        wave(
            base = base,
            amplitude = amplitude,
            wavelength = SeamDissolveTokens.TideFrontWavelength.toPx(),
            phase = phase,
        )
        drawPath(path, color)
    }

    /** Closes [path] from above the screen's top edge down to the wave line. */
    private fun ContentDrawScope.wave(base: Float, amplitude: Float, wavelength: Float, phase: Float) {
        val margin = 20.dp.toPx()
        val step = SeamDissolveTokens.TideStep.toPx()
        path.rewind()
        path.moveTo(-margin, -margin)
        var x = -margin
        while (true) {
            val y = base + amplitude * (
                sin(((x / wavelength) + phase) * TAU) +
                    SeamDissolveTokens.TideHarmonic * sin(((x / (wavelength * .5f)) - phase * 1.7f + .3f) * TAU)
                )
            path.lineTo(x, y)
            if (x >= size.width + margin) break
            x = minOf(x + step, size.width + margin)
        }
        path.lineTo(size.width + margin, -margin)
        path.close()
    }

    private companion object {
        const val TAU = (PI * 2).toFloat()
    }
}
