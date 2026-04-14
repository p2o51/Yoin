package com.gpo.yoin.ui.navigation.back

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.lerp
import kotlinx.coroutines.launch

@Composable
fun YoinBackSurface(
    kind: BackSurfaceKind,
    onCommitBack: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    controller: BackSurfaceController = rememberBackSurfaceController(),
    content: @Composable (Modifier, BackSurfaceController, () -> Unit) -> Unit,
) {
    val scope = rememberCoroutineScope()
    val latestKind = rememberUpdatedState(kind)
    val latestOnCommitBack = rememberUpdatedState(onCommitBack)
    val requestBack: () -> Unit = {
        scope.launch {
            when (latestKind.value) {
                BackSurfaceKind.PushPage,
                BackSurfaceKind.RootSection,
                BackSurfaceKind.ShellOverlayDown,
                -> controller.commitImmediately(latestOnCommitBack.value)
                BackSurfaceKind.ShellOverlayUp,
                -> controller.animateCommit(latestOnCommitBack.value)
            }
        }
    }

    BackHandler(enabled = enabled, onBack = requestBack)

    content(
        modifier.then(kind.surfaceTransform(controller)),
        controller,
        requestBack,
    )
}

@Composable
private fun BackSurfaceKind.surfaceTransform(controller: BackSurfaceController): Modifier {
    val fraction = controller.fraction
    return when (this) {
        BackSurfaceKind.RootSection -> Modifier
        BackSurfaceKind.PushPage -> pushPageTransform(fraction)
        BackSurfaceKind.ShellOverlayDown -> overlayTransform(fraction, 1f)
        BackSurfaceKind.ShellOverlayUp -> overlayTransform(fraction, -1f)
    }
}

@Composable
private fun pushPageTransform(fraction: Float): Modifier {
    val layoutDirection = LocalLayoutDirection.current
    val transformOrigin = remember(layoutDirection) {
        TransformOrigin(
            pivotFractionX = if (layoutDirection == LayoutDirection.Rtl) 1f else 0f,
            pivotFractionY = 0.5f,
        )
    }
    val previewShape = remember(fraction) {
        RoundedCornerShape(
            lerp(
                start = 0.dp,
                stop = BackMotionTokens.PushPageCornerRadius,
                fraction = fraction,
            ),
        )
    }

    return Modifier.graphicsLayer {
        val scale = 1f - ((1f - BackMotionTokens.PushPageScaleTarget) * fraction)
        val edgeInsetPx = lerp(
            start = 0.dp,
            stop = BackMotionTokens.PushPageEdgeInset,
            fraction = fraction,
        ).toPx()

        scaleX = scale
        scaleY = scale
        translationX = if (layoutDirection == LayoutDirection.Rtl) -edgeInsetPx else edgeInsetPx
        shape = previewShape
        clip = fraction > 0f
        this.transformOrigin = transformOrigin
    }
}

private fun overlayTransform(
    fraction: Float,
    directionMultiplier: Float,
): Modifier = Modifier.graphicsLayer {
    translationY = directionMultiplier * fraction * size.height
}
