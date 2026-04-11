package com.gpo.yoin.ui.experience

import androidx.compose.animation.core.animate
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.material3.MotionScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import com.gpo.yoin.ui.theme.YoinMotion
import kotlin.math.pow

private const val PredictiveBackPreviewCap = 0.8f
private const val PredictiveBackPreviewCurveExponent = 1.2f
private val PullSettleMotionScheme = MotionScheme.standard()

private fun pullSettleSpec(): FiniteAnimationSpec<Float> =
    YoinMotion.defaultSpatialSpec(
        role = com.gpo.yoin.ui.theme.YoinMotionRole.Standard,
        expressiveScheme = PullSettleMotionScheme,
        standardScheme = PullSettleMotionScheme,
    )

enum class EdgeAdvanceDirection {
    Backward,
    Forward,
}

@Stable
class EdgeAdvanceState internal constructor(
    private val triggerPx: Float,
    private val maxPullPx: Float,
    private val resistance: Float,
) {
    var pullPx by mutableFloatStateOf(0f)
        private set

    var direction by mutableStateOf<EdgeAdvanceDirection?>(null)
        private set

    val progress: Float
        get() = (pullPx / triggerPx).coerceIn(0f, 1f)

    fun registerPull(
        direction: EdgeAdvanceDirection,
        deltaPx: Float,
        onTriggered: (EdgeAdvanceDirection) -> Unit,
    ) {
        if (this.direction != direction) {
            pullPx = 0f
        }
        this.direction = direction
        pullPx = (pullPx + deltaPx * resistance).coerceAtMost(maxPullPx)
        if (pullPx >= triggerPx) {
            reset()
            onTriggered(direction)
        }
    }

    fun reset() {
        pullPx = 0f
        direction = null
    }
}

@Composable
fun rememberEdgeAdvanceState(
    triggerPx: Float,
    maxPullPx: Float = triggerPx * 1.4f,
    resistance: Float = 0.52f,
): EdgeAdvanceState = remember(triggerPx, maxPullPx, resistance) {
    EdgeAdvanceState(
        triggerPx = triggerPx,
        maxPullPx = maxPullPx,
        resistance = resistance,
    )
}

@Stable
class PullToDismissState internal constructor(
    private val triggerPx: Float,
    private val maxPullPx: Float,
    private val resistance: Float,
) {
    var pullPx by mutableFloatStateOf(0f)
        private set

    val progress: Float
        get() = (pullPx / triggerPx).coerceIn(0f, 1f)

    fun registerPull(deltaPx: Float): Boolean {
        pullPx = (pullPx + deltaPx * resistance).coerceAtMost(maxPullPx)
        return pullPx >= triggerPx
    }

    fun release(deltaPx: Float) {
        pullPx = (pullPx - deltaPx).coerceAtLeast(0f)
    }

    suspend fun animateToTrigger() {
        animate(
            initialValue = pullPx,
            targetValue = triggerPx.coerceAtMost(maxPullPx),
            animationSpec = pullSettleSpec(),
        ) { value, _ ->
            pullPx = value
        }
    }

    suspend fun animateReset() {
        animate(
            initialValue = pullPx,
            targetValue = 0f,
            animationSpec = pullSettleSpec(),
        ) { value, _ ->
            pullPx = value
        }
    }

    fun reset() {
        pullPx = 0f
    }
}

@Composable
fun rememberPullToDismissState(
    triggerPx: Float,
    maxPullPx: Float = triggerPx * 1.3f,
    resistance: Float = 0.45f,
): PullToDismissState = remember(triggerPx, maxPullPx, resistance) {
    PullToDismissState(
        triggerPx = triggerPx,
        maxPullPx = maxPullPx,
        resistance = resistance,
    )
}

@Stable
class PredictiveBackPreviewState internal constructor() {
    var progress by mutableFloatStateOf(0f)
        private set

    val previewFraction: Float
        get() = (progress / PredictiveBackPreviewCap).coerceIn(0f, 1f)

    fun update(rawProgress: Float) {
        val curvedProgress = rawProgress
            .coerceIn(0f, 1f)
            .pow(PredictiveBackPreviewCurveExponent)
        progress = (curvedProgress * PredictiveBackPreviewCap)
            .coerceIn(0f, PredictiveBackPreviewCap)
    }

    suspend fun animateReset() {
        animate(
            initialValue = progress,
            targetValue = 0f,
            animationSpec = YoinMotion.predictiveBackSettleSpring(),
        ) { value, _ ->
            progress = value
        }
    }

    fun snapToHidden() {
        progress = 0f
    }
}

@Composable
fun rememberPredictiveBackPreviewState(): PredictiveBackPreviewState = remember {
    PredictiveBackPreviewState()
}

data class DeckIndicatorTransitionState(
    val translationXPx: Float,
    val scale: Float,
    val alpha: Float,
)

@Composable
fun rememberDeckIndicatorTransitionState(
    deckTransitionProgress: Float,
    deckTransitionDirection: EdgeAdvanceDirection,
    adjacentProgress: Float,
    adjacentDirection: EdgeAdvanceDirection?,
): DeckIndicatorTransitionState {
    val density = LocalDensity.current
    val motionProfile = LocalMotionProfile.current
    val deckShiftPx = with(density) {
        if (motionProfile == MotionProfile.Full) {
            14.dp.toPx()
        } else {
            8.dp.toPx()
        }
    }
    val adjacentShiftPx = with(density) {
        if (motionProfile == MotionProfile.Full) {
            10.dp.toPx()
        } else {
            5.dp.toPx()
        }
    }
    val scaleFloor = if (motionProfile == MotionProfile.Full) 0.94f else 0.97f
    val alphaFloor = if (motionProfile == MotionProfile.Full) 0.76f else 0.86f
    val deckDirectionOffset = when (deckTransitionDirection) {
        EdgeAdvanceDirection.Backward -> -1f
        EdgeAdvanceDirection.Forward -> 1f
    }
    val adjacentDirectionOffset = when (adjacentDirection) {
        EdgeAdvanceDirection.Backward -> adjacentProgress
        EdgeAdvanceDirection.Forward -> -adjacentProgress
        null -> 0f
    }

    return remember(
        motionProfile,
        deckShiftPx,
        adjacentShiftPx,
        scaleFloor,
        alphaFloor,
        deckTransitionProgress,
        deckDirectionOffset,
        adjacentProgress,
        adjacentDirectionOffset,
    ) {
        DeckIndicatorTransitionState(
            translationXPx = ((1f - deckTransitionProgress) * deckDirectionOffset * deckShiftPx) +
                (adjacentDirectionOffset * adjacentShiftPx),
            scale = scaleFloor + deckTransitionProgress * (1f - scaleFloor),
            alpha = alphaFloor + deckTransitionProgress * (1f - alphaFloor),
        )
    }
}
