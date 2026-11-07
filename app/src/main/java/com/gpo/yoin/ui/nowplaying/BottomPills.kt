package com.gpo.yoin.ui.nowplaying

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ButtonGroup
import androidx.compose.material3.ButtonGroupDefaults
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ButtonGroupScope
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.gpo.yoin.R
import com.gpo.yoin.player.CastState
import com.gpo.yoin.symbols.YoinSymbols
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
    showWrite: Boolean = true,
    supportsYoinCast: Boolean = true,
    pillHeight: Dp = 44.dp,
    forceCapsule: Boolean = false,
    /** The other device Spotify plays on: the Devices pill carries its name instead of "Devices". */
    playingOn: String? = null,
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

        // Measure first, never drop (断点交接 §3.3): M3 ButtonGroup silently
        // overflows whatever doesn't fit, which lost Write on a 277dp column.
        // The whole group folds to icon-only pills when the labels (plus the
        // Cast control) don't fit the real width; a pressed pill still shows
        // its label when there is room for it. All three are always there.
        val queueLabel = stringResource(R.string.np_pill_queue)
        val devicesLabel = playingOn ?: stringResource(R.string.np_pill_devices)
        val writeLabel = stringResource(R.string.np_pill_write)
        BoxWithConstraints(modifier = modifier.fillMaxWidth()) {
            val layout = rememberBottomPillsLayout(
                maxWidth = maxWidth,
                pillHeight = pillHeight,
                castState = if (supportsYoinCast) castState else CastState.NotAvailable,
                labels = listOfNotNull(queueLabel, devicesLabel, writeLabel.takeIf { showWrite }),
            )
            fun labelVisible(pressed: Boolean, label: String): Boolean = when {
                layout.labelled -> !anyPressed || pressed
                pressed -> layout.fitsPressedLabel(label)
                else -> false
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                CastButton(
                    castState = if (supportsYoinCast) castState else CastState.NotAvailable,
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
                                icon = YoinSymbols.Queue,
                                label = queueLabel,
                                showLabel = labelVisible(queuePressed, queueLabel),
                                pressWiden = layout.labelled,
                                interactionSource = queueInteraction,
                                shape = YoinShapeTokens.Full,
                                pillHeight = pillHeight,
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
                                icon = YoinSymbols.Devices,
                                label = devicesLabel,
                                showLabel = labelVisible(devicesPressed, devicesLabel),
                                pressWiden = layout.labelled,
                                interactionSource = devicesInteraction,
                                modifier = Modifier.reportGlowAnchor(GlowAnchor.Devices),
                                shape = if (forceCapsule) {
                                    YoinShapeTokens.Full
                                } else {
                                    RoundedCornerShape(20.dp)
                                },
                                pillHeight = pillHeight,
                            )
                        },
                        menuContent = { _ -> },
                    )
                    if (showWrite) {
                        customItem(
                            buttonGroupContent = {
                                PillButton(
                                    onClick = {
                                        haptics.performContextClick()
                                        onWriteClick()
                                    },
                                    icon = YoinSymbols.Note,
                                    label = writeLabel,
                                    showLabel = labelVisible(writePressed, writeLabel),
                                    pressWiden = layout.labelled,
                                    interactionSource = writeInteraction,
                                    modifier = Modifier.reportGlowAnchor(GlowAnchor.Write),
                                    shape = YoinShapeTokens.Full,
                                    pillHeight = pillHeight,
                                )
                            },
                            menuContent = { _ -> },
                        )
                    }
                }
            }
        }
    }
}

/** Whether the pills can carry their labels in [maxWidth]; see [rememberBottomPillsLayout]. */
internal class BottomPillsLayout(
    val labelled: Boolean,
    private val iconOnlyWidth: Dp,
    private val labelWidths: Map<String, Dp>,
    private val maxWidth: Dp,
) {
    /** In icon-only mode a pressed pill may still open its label if that alone fits. */
    fun fitsPressedLabel(label: String): Boolean =
        iconOnlyWidth + PillLabelGap + (labelWidths[label] ?: 0.dp) <= maxWidth - PillsSafetyMargin
}

/**
 * The queue / devices / write actions as plain icons, for the end of a top
 * bar: the landscape and two-pane players set them there (owner 2026-11-06
 * 「三个按钮应该在右上角」, prototype v10's three icons) — the pills would
 * crowd out the "playing from" line.
 */
@Composable
internal fun TopBarActionIcons(
    onQueueClick: () -> Unit,
    onDevicesClick: () -> Unit,
    onWriteClick: () -> Unit,
    modifier: Modifier = Modifier,
    castState: CastState = CastState.NotAvailable,
    onCastClick: () -> Unit = {},
    supportsYoinCast: Boolean = true,
    size: Dp = 40.dp,
    // Spotify plays on another device: the devices button names it (owner
    // 2026-11-06, as the Devices pill does).
    playingOn: String? = null,
) {
    val haptics = rememberYoinHaptics()
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CastButton(
            castState = if (supportsYoinCast) castState else CastState.NotAvailable,
            onClick = onCastClick,
        )
        listOf(
            Triple(YoinSymbols.Queue, R.string.np_pill_queue, onQueueClick),
            Triple(YoinSymbols.Devices, R.string.np_pill_devices, onDevicesClick),
            Triple(YoinSymbols.Note, R.string.np_pill_write, onWriteClick),
        ).forEach { (icon, label, onClick) ->
            if (icon == YoinSymbols.Devices && playingOn != null) {
                Surface(
                    onClick = {
                        haptics.performContextClick()
                        onClick()
                    },
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.secondaryContainer,
                    contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                    modifier = Modifier.height(size),
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 10.dp),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(imageVector = icon, contentDescription = null)
                        Text(
                            text = playingOn,
                            style = MaterialTheme.typography.labelLarge,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.widthIn(max = TopBarDeviceNameMax),
                        )
                    }
                }
                return@forEach
            }
            IconButton(
                onClick = {
                    haptics.performContextClick()
                    onClick()
                },
                modifier = Modifier.size(size),
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = stringResource(label),
                    tint = MaterialTheme.colorScheme.onSurface,
                )
            }
        }
    }
}

private val TopBarDeviceNameMax = 96.dp

@Composable
private fun rememberBottomPillsLayout(
    maxWidth: Dp,
    pillHeight: Dp,
    castState: CastState,
    labels: List<String>,
): BottomPillsLayout {
    val textMeasurer = rememberTextMeasurer()
    val labelStyle = MaterialTheme.typography.labelMedium
    val castStyle = MaterialTheme.typography.labelLarge
    val density = LocalDensity.current
    return remember(maxWidth, pillHeight, castState, labelStyle, castStyle, density, labels) {
        with(density) {
            val labelWidths = labels.associateWith { label ->
                textMeasurer.measure(label, labelStyle, maxLines = 1, softWrap = false).size.width.toDp()
            }
            val castWidth = when (castState) {
                is CastState.Available -> CastIconWidth + PillsRowGap
                is CastState.Connected -> textMeasurer
                    .measure(castState.deviceName, castStyle, maxLines = 1, softWrap = false)
                    .size.width.toDp() + CastConnectedChrome + PillsRowGap
                else -> 0.dp
            }
            // A FilledTonalButton never measures narrower than M3's 58dp
            // (ButtonDefaults.MinWidth): modelling the icon pill at 44 let
            // ButtonGroup silently drop Write in a 170dp column.
            val bare = maxOf(pillBareWidth(pillHeight), PillMinWidth)
            val gaps = PillGroupGap * (labels.size - 1)
            val iconOnly = castWidth + bare * labels.size + gaps
            val labelled = castWidth + labels.sumOf { label ->
                (pillBareWidth(pillHeight) + PillLabelGap + labelWidths.getValue(label)).value.toDouble()
            }.toFloat().dp + gaps
            BottomPillsLayout(
                labelled = labelled <= maxWidth - PillsSafetyMargin,
                iconOnlyWidth = iconOnly,
                labelWidths = labelWidths,
                maxWidth = maxWidth,
            )
        }
    }
}

/** Icon + the pill's horizontal padding (0.3 × height each side). */
private fun pillBareWidth(pillHeight: Dp): Dp = pillHeight * 0.36f + pillHeight * 0.6f

private val PillLabelGap = 4.dp
private val PillGroupGap = 2.dp
private val PillsRowGap = 6.dp
private val PillMinWidth = ButtonDefaults.MinWidth
private val CastIconWidth = 48.dp

/** A tonal button's 24dp side padding + 18dp icon + 6dp gap. */
private val CastConnectedChrome = 72.dp

/** Headroom for the press bulge (ButtonGroup expandedRatio) and rounding. */
private val PillsSafetyMargin = 12.dp

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun ButtonGroupScope.PillButton(
    onClick: () -> Unit,
    icon: ImageVector,
    label: String,
    showLabel: Boolean,
    interactionSource: MutableInteractionSource,
    shape: androidx.compose.ui.graphics.Shape,
    pillHeight: Dp = 44.dp,
    // Icon-only groups don't widen a pressed label past its natural width.
    pressWiden: Boolean = true,
    modifier: Modifier = Modifier,
) {
    val pressed by interactionSource.collectIsPressedAsState()
    val labelFraction by animateFloatAsState(
        targetValue = if (showLabel) 1f else 0f,
        animationSpec = YoinMotion.fastEffectsSpec(),
        label = "labelFraction",
    )
    val labelWidthMultiplier by animateFloatAsState(
        targetValue = if (pressed && pressWiden) 1.8f else 1f,
        animationSpec = YoinMotion.fastSpatialSpec<Float>(role = YoinMotionRole.Standard),
        label = "labelWidthMultiplier",
    )

    FilledTonalButton(
        onClick = onClick,
        modifier = modifier
            .height(pillHeight)
            .minimumTouchTarget()
            .animateWidth(interactionSource),
        interactionSource = interactionSource,
        shape = shape,
        contentPadding = PaddingValues(horizontal = (pillHeight.value * 0.3f).dp),
        colors = ButtonDefaults.filledTonalButtonColors(
            containerColor = MaterialTheme.colorScheme.primary,
            contentColor = MaterialTheme.colorScheme.onPrimary,
        ),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = label,
            modifier = Modifier.size((pillHeight.value * 0.36f).dp),
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
