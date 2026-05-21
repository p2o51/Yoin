package com.gpo.yoin.ui.nowplaying

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.QueueMusic
import androidx.compose.material.icons.automirrored.rounded.StickyNote2
import androidx.compose.material.icons.rounded.Devices
import androidx.compose.material3.ButtonGroup
import androidx.compose.material3.ButtonGroupDefaults
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ButtonGroupScope
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.layout
import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.dp
import com.gpo.yoin.player.CastState
import com.gpo.yoin.ui.component.CastButton
import com.gpo.yoin.ui.component.minimumTouchTarget
import com.gpo.yoin.ui.experience.rememberYoinHaptics
import com.gpo.yoin.ui.theme.ProvideYoinMotionRole
import com.gpo.yoin.ui.theme.YoinMotion
import com.gpo.yoin.ui.theme.YoinMotionRole
import com.gpo.yoin.ui.theme.YoinShapeTokens
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun BottomPills(
    onQueueClick: () -> Unit,
    onDevicesClick: () -> Unit,
    onWriteClick: () -> Unit,
    castState: CastState = CastState.NotAvailable,
    onCastClick: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    ProvideYoinMotionRole(role = YoinMotionRole.Standard) {
        val haptics = rememberYoinHaptics()
        val queueInteraction = remember { MutableInteractionSource() }
        val devicesInteraction = remember { MutableInteractionSource() }
        val writeInteraction = remember { MutableInteractionSource() }

        val queuePressed by queueInteraction.collectIsPressedAsState()
        val devicesPressed by devicesInteraction.collectIsPressedAsState()
        val writePressed by writeInteraction.collectIsPressedAsState()
        val anyPressed = queuePressed || devicesPressed || writePressed

        // Pressing a pill should widen the active target and let the
        // other two collapse, instead of making the touched pill narrow
        // under the finger.

        Row(
            modifier = modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CastButton(
                castState = castState,
                onClick = onCastClick,
            )

            ButtonGroup(
                overflowIndicator = { _ -> },
                expandedRatio = ButtonGroupDefaults.ExpandedRatio,
                horizontalArrangement = Arrangement.spacedBy(2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                customItem(
                    buttonGroupContent = {
                        PillButton(
                            onClick = {
                                haptics.performContextClick()
                                onQueueClick()
                            },
                            icon = Icons.AutoMirrored.Rounded.QueueMusic,
                            label = "Queue",
                            showLabel = !anyPressed || queuePressed,
                            interactionSource = queueInteraction,
                            shape = YoinShapeTokens.Full,
                        )
                    },
                    menuContent = { _ -> },
                )
                customItem(
                    buttonGroupContent = {
                        PillButton(
                            onClick = {
                                haptics.performContextClick()
                                onDevicesClick()
                            },
                            icon = Icons.Rounded.Devices,
                            label = "Devices",
                            showLabel = !anyPressed || devicesPressed,
                            interactionSource = devicesInteraction,
                            shape = RoundedCornerShape(20.dp),
                        )
                    },
                    menuContent = { _ -> },
                )
                customItem(
                    buttonGroupContent = {
                        PillButton(
                            onClick = {
                                haptics.performContextClick()
                                onWriteClick()
                            },
                            icon = Icons.AutoMirrored.Rounded.StickyNote2,
                            label = "Write",
                            showLabel = !anyPressed || writePressed,
                            interactionSource = writeInteraction,
                            shape = YoinShapeTokens.Full,
                        )
                    },
                    menuContent = { _ -> },
                )
            }
        }
    }
}


@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun ButtonGroupScope.PillButton(
    onClick: () -> Unit,
    icon: ImageVector,
    label: String,
    showLabel: Boolean,
    interactionSource: MutableInteractionSource,
    shape: androidx.compose.ui.graphics.Shape,
) {
    val pressed by interactionSource.collectIsPressedAsState()
    val labelFraction by animateFloatAsState(
        targetValue = if (showLabel) 1f else 0f,
        animationSpec = YoinMotion.fastEffectsSpec(),
        label = "labelFraction",
    )
    val labelWidthMultiplier by animateFloatAsState(
        targetValue = if (pressed) 1.8f else 1f,
        animationSpec = YoinMotion.fastSpatialSpec<Float>(role = YoinMotionRole.Standard),
        label = "labelWidthMultiplier",
    )

    FilledTonalButton(
        onClick = onClick,
        modifier = Modifier
            .height(44.dp)
            .minimumTouchTarget()
            .animateWidth(interactionSource),
        interactionSource = interactionSource,
        shape = shape,
        contentPadding = PaddingValues(horizontal = 10.dp),
        colors = ButtonDefaults.filledTonalButtonColors(
            containerColor = MaterialTheme.colorScheme.primary,
            contentColor = MaterialTheme.colorScheme.onPrimary,
        ),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = label,
            modifier = Modifier.size(16.dp),
        )
        // Keep text always in composition — animate width to 0 via layout
        // so there's no sudden jump when content is removed
        Row(
            modifier = Modifier
                .graphicsLayer { alpha = labelFraction }
                .clipToBounds()
                .layout { measurable, constraints ->
                    val placeable = measurable.measure(constraints)
                    val w = (placeable.width * labelFraction * labelWidthMultiplier).roundToInt()
                    layout(w, placeable.height) {
                        placeable.placeRelative(0, 0)
                    }
                },
        ) {
            Spacer(modifier = Modifier.width(4.dp))
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                maxLines = 1,
                softWrap = false,
            )
        }
    }
}

@Composable
internal fun rememberNowPlayingButtonGroupInteractionSource() =
    remember { MutableInteractionSource() }

/** Format milliseconds as m:ss (e.g. "3:45", "0:00"). */
internal fun formatTime(ms: Long): String {
    val totalSeconds = (ms / 1000).coerceAtLeast(0)
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return "$minutes:${seconds.toString().padStart(2, '0')}"
}
