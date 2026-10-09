package com.gpo.yoin.ui.landing

import androidx.annotation.StringRes
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.gpo.yoin.R
import com.gpo.yoin.symbols.YoinSymbols
import com.gpo.yoin.ui.component.FloatingBarRowPadding
import com.gpo.yoin.ui.component.floatingBarButtonHeight
import com.gpo.yoin.ui.theme.YoinMotion
import com.gpo.yoin.ui.theme.YoinMotionRole
import com.gpo.yoin.ui.theme.YoinMotionSpeed
import com.gpo.yoin.ui.theme.YoinTheme

/** The pill's main button: its label, its weight, and whether it carries an arrow. */
@Immutable
internal data class LandingPrimary(
    @param:StringRes val label: Int,
    val style: Style,
    val arrow: Boolean = false,
) {
    enum class Style { Filled, Tonal, Unavailable }
}

/**
 * The landing's floating navigation — a twin of the shell's bar (same surface, height and corners), so the
 * hand-off can turn it into the bar. [hello]: the whole pill is the primary button. Otherwise back, the step
 * dots and the primary button.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun LandingPill(
    hello: Boolean,
    primary: LandingPrimary,
    dotCount: Int,
    dotIndex: Int,
    centered: Boolean,
    onBack: () -> Unit,
    onPrimary: () -> Unit,
    contentAlpha: () -> Float,
    modifier: Modifier = Modifier,
) {
    val buttonHeight = floatingBarButtonHeight(centered)
    Surface(
        modifier = modifier.fillMaxSize(),
        shape = MaterialTheme.shapes.extraLarge,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
    ) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer { alpha = contentAlpha() }
                .padding(horizontal = FloatingBarRowPadding),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            AnimatedVisibility(
                visible = !hello,
                enter = YoinMotion.fadeIn(role = YoinMotionRole.Standard) + YoinMotion.scaleIn(role = YoinMotionRole.Standard, speed = YoinMotionSpeed.Fast, initialScale = 0f) +
                    YoinMotion.expandHorizontally(role = YoinMotionRole.Standard),
                exit = YoinMotion.fadeOut(role = YoinMotionRole.Standard, speed = YoinMotionSpeed.Fast) + YoinMotion.scaleOut(role = YoinMotionRole.Standard, speed = YoinMotionSpeed.Fast, targetScale = 0f) +
                    YoinMotion.shrinkHorizontally(role = YoinMotionRole.Standard),
            ) {
                FilledTonalIconButton(
                    onClick = onBack,
                    modifier = Modifier.size(buttonHeight),
                    colors = IconButtonDefaults.filledTonalIconButtonColors(
                        containerColor = MaterialTheme.colorScheme.surfaceBright,
                        contentColor = MaterialTheme.colorScheme.onSurface,
                    ),
                ) {
                    Icon(YoinSymbols.Back, contentDescription = stringResource(R.string.landing_back), modifier = Modifier.size(22.dp))
                }
            }
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight(),
                contentAlignment = Alignment.Center,
            ) {
                androidx.compose.animation.AnimatedVisibility(
                    visible = !hello && dotCount > 1,
                    enter = YoinMotion.fadeIn(role = YoinMotionRole.Standard),
                    exit = YoinMotion.fadeOut(role = YoinMotionRole.Standard, speed = YoinMotionSpeed.Fast),
                ) {
                    LandingDots(count = dotCount, index = dotIndex)
                }
            }
            PrimaryButton(
                primary = primary,
                fill = hello,
                height = buttonHeight,
                onClick = onPrimary,
            )
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun PrimaryButton(
    primary: LandingPrimary,
    fill: Boolean,
    height: Dp,
    onClick: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    val labelIn = YoinMotion.fadeIn(role = YoinMotionRole.Standard) + YoinMotion.slideInVertically(role = YoinMotionRole.Standard, speed = YoinMotionSpeed.Fast) { it / 3 }
    val labelOut = YoinMotion.fadeOut(role = YoinMotionRole.Standard, speed = YoinMotionSpeed.Fast) + YoinMotion.slideOutVertically(role = YoinMotionRole.Standard, speed = YoinMotionSpeed.Fast) { -it / 3 }
    val container by animateColorAsState(
        targetValue = when (primary.style) {
            LandingPrimary.Style.Filled -> scheme.primary
            LandingPrimary.Style.Tonal -> scheme.secondaryContainer
            LandingPrimary.Style.Unavailable -> scheme.surfaceContainerHighest
        },
        animationSpec = YoinMotion.defaultEffectsSpec(),
        label = "landingPrimaryContainer",
    )
    val content by animateColorAsState(
        targetValue = when (primary.style) {
            LandingPrimary.Style.Filled -> scheme.onPrimary
            LandingPrimary.Style.Tonal -> scheme.onSecondaryContainer
            LandingPrimary.Style.Unavailable -> scheme.onSurfaceVariant
        },
        animationSpec = YoinMotion.defaultEffectsSpec(),
        label = "landingPrimaryContent",
    )
    Button(
        onClick = onClick,
        shapes = ButtonDefaults.shapes(),
        colors = ButtonDefaults.buttonColors(containerColor = container, contentColor = content),
        contentPadding = PaddingValues(horizontal = 22.dp),
        modifier = Modifier
            .height(height)
            .animateContentSize(YoinMotion.defaultSpatialSpec())
            .then(if (fill) Modifier.fillMaxWidth() else Modifier),
    ) {
        AnimatedContent(
            targetState = primary.label,
            transitionSpec = { labelIn.togetherWith(labelOut) },
            label = "landingPrimaryLabel",
        ) { label ->
            Text(text = stringResource(label), style = MaterialTheme.typography.labelLarge.copy(fontSize = MaterialTheme.typography.titleSmall.fontSize))
        }
        AnimatedVisibility(
            visible = primary.arrow,
            enter = YoinMotion.fadeIn(role = YoinMotionRole.Standard) + YoinMotion.expandHorizontally(role = YoinMotionRole.Standard),
            exit = YoinMotion.fadeOut(role = YoinMotionRole.Standard, speed = YoinMotionSpeed.Fast) + YoinMotion.shrinkHorizontally(role = YoinMotionRole.Standard),
        ) {
            Row {
                Spacer(Modifier.width(8.dp))
                Icon(YoinSymbols.ChevronRight, contentDescription = null, modifier = Modifier.size(20.dp))
            }
        }
    }
}

/** The step dots: the current one long, the done ones half strength. */
@Composable
private fun LandingDots(count: Int, index: Int) {
    val scheme = MaterialTheme.colorScheme
    val label = "${index + 1} / $count"
    Row(
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.semantics { contentDescription = label },
    ) {
        repeat(count) { i ->
            val width by animateDpAsState(if (i == index) 22.dp else 8.dp, YoinMotion.defaultSpatialSpec(), label = "dotWidth")
            val color by animateColorAsState(
                targetValue = when {
                    i == index -> scheme.primary
                    i < index -> scheme.primary.copy(alpha = 0.55f)
                    else -> scheme.outlineVariant
                },
                animationSpec = YoinMotion.defaultEffectsSpec(),
                label = "dotColor",
            )
            Box(
                Modifier
                    .size(width = width, height = 8.dp)
                    .background(color, CircleShape),
            )
        }
    }
}

@Preview
@Composable
private fun LandingPillPreview() {
    YoinTheme {
        Box(Modifier.size(width = 360.dp, height = 68.dp)) {
            LandingPill(
                hello = false,
                primary = LandingPrimary(R.string.landing_next, LandingPrimary.Style.Filled),
                dotCount = 6,
                dotIndex = 2,
                centered = false,
                onBack = {},
                onPrimary = {},
                contentAlpha = { 1f },
            )
        }
    }
}
