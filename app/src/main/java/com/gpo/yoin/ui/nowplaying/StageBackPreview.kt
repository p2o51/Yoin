package com.gpo.yoin.ui.nowplaying

import androidx.activity.BackEventCompat
import androidx.compose.animation.core.Animatable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.graphicsLayer
import com.gpo.yoin.ui.navigation.back.BackMotionTokens
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * The Now Playing stage's predictive-back preview (Expanded → Compact, Full → panel): the AOSP cross-activity
 * pose the detail pages use, applied to the stage. [progress] is the EASED gesture progress, snapped on every
 * back event (the finger drives it directly — no chase); only the release springs it home. It scales the stage
 * toward [BackMotionTokens.PopPageScaleTarget], shifts it toward the side the swipe came from and follows the
 * finger vertically (AOSP `getYOffset`), each within the 8dp display margin. Read in the draw phase only.
 */
@Stable
class StageBackPreview {
    internal val progress = Animatable(0f)
    internal var swipeEdge by mutableIntStateOf(BackEventCompat.EDGE_LEFT)

    /** The finger's vertical travel since the gesture began, px. */
    internal var touchYDelta by mutableFloatStateOf(0f)

    companion object {
        /** No preview (previews, the layouts that never take one). */
        val Rest = StageBackPreview()
    }
}

/**
 * Applies [preview] as a DRAW transform. As a graphicsLayer scale/translation it would be a positional property:
 * every gesture frame re-mapped the bounds of every node under the stage, scheduled a layout pass and re-ran
 * every onGloballyPositioned below it. Drawn, the layout never moves: each frame re-records only the outer layer
 * (a transform and the inner layer), and the inner layer keeps the content's display list. Hit testing stays
 * unmoved for the small preview, which no finger can reach mid-gesture.
 */
internal fun Modifier.backPreviewTransform(preview: StageBackPreview): Modifier = this
    .graphicsLayer()
    .drawWithContent {
        val p = preview.progress.value
        if (p <= 0f) {
            drawContent()
            return@drawWithContent
        }
        val scale = 1f - (1f - BackMotionTokens.PopPageScaleTarget) * p
        val margin = DisplayBoundsMarginDp * density
        // Left-edge swipes carry the stage right until its right edge sits 8dp from the edge; right-edge swipes
        // stay centred (AOSP).
        val dx = if (preview.swipeEdge == BackEventCompat.EDGE_LEFT) {
            max(0f, size.width * (1f - scale) / 2f - margin) * p
        } else {
            0f
        }
        // Vertical follow: a decelerated share of the finger's travel (capped at half the stage), scaled to the
        // room the shrunken stage has before the margin.
        val rawDy = preview.touchYDelta
        val dy = if (rawDy != 0f) {
            val half = size.height / 2f
            val ratio = min(half, abs(rawDy)) / half
            val decelerated = 1f - (1f - ratio) * (1f - ratio)
            val room = max(0f, size.height * (1f - scale) / 2f - margin)
            room * decelerated * (if (rawDy < 0f) -1f else 1f)
        } else {
            0f
        }
        withTransform({
            translate(dx, dy)
            scale(scale, scale, pivot = Offset(size.width / 2f, size.height / 2f))
        }) { this@drawWithContent.drawContent() }
    }
    .graphicsLayer()

/** AOSP `displayBoundsMargin` (8dp): the preview never passes it. */
private const val DisplayBoundsMarginDp = 8f
