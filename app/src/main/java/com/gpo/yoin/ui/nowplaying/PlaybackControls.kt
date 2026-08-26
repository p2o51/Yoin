package com.gpo.yoin.ui.nowplaying

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.material.icons.rounded.UnfoldLess
import androidx.compose.material.icons.rounded.UnfoldMore
import androidx.compose.material3.ButtonGroup
import androidx.compose.material3.ButtonGroupDefaults
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.isSpecified
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.gpo.yoin.ui.component.WaveProgressBar
import com.gpo.yoin.ui.component.formatTrackDurationMs
import com.gpo.yoin.ui.experience.rememberYoinHaptics
import com.gpo.yoin.ui.theme.ProvideYoinMotionRole
import com.gpo.yoin.ui.theme.YoinMotion
import com.gpo.yoin.ui.theme.YoinMotionRole
import com.gpo.yoin.ui.theme.withTabularFigures

@OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun PlaybackControls(
    isPlaying: Boolean,
    onTogglePlayPause: () -> Unit,
    onSkipNext: () -> Unit,
    onSkipPrevious: () -> Unit,
    positionMs: Long,
    durationMs: Long,
    progress: Float,
    buffered: Float,
    onSeek: (Float) -> Unit,
    playInteractionSource: MutableInteractionSource,
    nextInteractionSource: MutableInteractionSource,
    playPressed: Boolean,
    nextPressed: Boolean,
    shuffleEnabled: Boolean = false,
    onToggleShuffle: () -> Unit = {},
    controlSize: Dp = 56.dp,
    lyricsExpanded: Boolean = false,
    onExpandLyrics: (() -> Unit)? = null,
    noteAnchorsMs: List<Long> = emptyList(),
    modifier: Modifier = Modifier,
) {
    ProvideYoinMotionRole(role = YoinMotionRole.Standard) {
        val haptics = rememberYoinHaptics()
        // Publish the PLAY/PAUSE press to the reactive background so it can gather
        // light while the finger is held (the release burst fires off pulseTrigger).
        val transportSignal = LocalNowPlayingTransportSignal.current
        LaunchedEffect(transportSignal, playPressed) {
            transportSignal?.playHeld = playPressed
        }
        // Each transport button records its own centre so the background's release
        // burst can radiate from the button you actually tapped (set on click,
        // just before the playback state changes).
        var playCenter by remember { mutableStateOf(Offset.Unspecified) }
        var nextCenter by remember { mutableStateOf(Offset.Unspecified) }
        var prevCenter by remember { mutableStateOf(Offset.Unspecified) }
        val controlButtonSize = controlSize
        // Glyphs scale with the button so a bigger control (tabletop) gets a bigger
        // icon, not a small icon lost in a large circle. 56dp → 28dp (unchanged).
        val controlIconSize = controlSize * 0.5f
        val controlSpatialSpec = if (playPressed || nextPressed) {
            YoinMotion.fastSpatialSpec<Dp>()
        } else {
            YoinMotion.defaultSpatialSpec<Dp>()
        }
        val textStretchSpec = if (playPressed) {
            YoinMotion.fastSpatialSpec<Float>()
        } else {
            YoinMotion.defaultSpatialSpec<Float>()
        }
        val playHorizontalPadding by animateDpAsState(
            targetValue = when {
                playPressed -> 28.dp
                nextPressed -> 14.dp
                isPlaying -> 24.dp
                else -> 16.dp
            },
            animationSpec = controlSpatialSpec,
            label = "playHorizontalPadding",
        )
        val nextButtonWidth by animateDpAsState(
            targetValue = if (playPressed || nextPressed) 48.dp else controlButtonSize,
            animationSpec = controlSpatialSpec,
            label = "nextButtonWidth",
        )
        val textStretchScale by animateFloatAsState(
            targetValue = when {
                playPressed -> 1.10f
                isPlaying -> 1.06f
                else -> 0.97f
            },
            animationSpec = textStretchSpec,
            label = "textStretch",
        )

        Column(modifier = modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                ButtonGroup(
                    overflowIndicator = { _ -> },
                    modifier = Modifier.height(controlButtonSize),
                    expandedRatio = ButtonGroupDefaults.ExpandedRatio,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    customItem(
                        buttonGroupContent = {
                            FilledTonalButton(
                                onClick = {
                                    if (playCenter.isSpecified) {
                                        transportSignal?.burstFocalRoot = playCenter
                                    }
                                    haptics.performClick()
                                    onTogglePlayPause()
                                },
                                modifier = Modifier
                                    .fillMaxHeight()
                                    .animateWidth(playInteractionSource)
                                    .animateContentSize(
                                        animationSpec = YoinMotion.defaultSpatialSpec(),
                                    )
                                    .onGloballyPositioned {
                                        playCenter = it.boundsInRoot().center
                                        transportSignal?.gatherAnchorRoot = playCenter
                                    },
                                shape = MaterialTheme.shapes.extraLarge,
                                interactionSource = playInteractionSource,
                                contentPadding = PaddingValues(horizontal = playHorizontalPadding),
                                colors = ButtonDefaults.filledTonalButtonColors(
                                    containerColor = MaterialTheme.colorScheme.primary,
                                    contentColor = MaterialTheme.colorScheme.onPrimary,
                                ),
                            ) {
                                Text(
                                    text = if (isPlaying) "PAUSE" else "PLAY",
                                    style = MaterialTheme.typography.titleLarge.copy(
                                        fontSize = MaterialTheme.typography.titleLarge.fontSize * 0.9f,
                                        fontWeight = if (isPlaying) FontWeight.Bold else FontWeight.Medium,
                                        letterSpacing = if (isPlaying) 0.5.sp else 0.sp,
                                    ),
                                    maxLines = 1,
                                    softWrap = false,
                                    modifier = Modifier.graphicsLayer {
                                        scaleX = textStretchScale
                                        transformOrigin = TransformOrigin(0f, 0.5f)
                                    },
                                )
                            }
                        },
                        menuContent = { _ -> },
                    )

                    customItem(
                        buttonGroupContent = {
                            FilledIconButton(
                                onClick = {
                                    if (nextCenter.isSpecified) {
                                        transportSignal?.burstFocalRoot = nextCenter
                                    }
                                    haptics.performTick()
                                    onSkipNext()
                                },
                                modifier = Modifier
                                    .fillMaxHeight()
                                    .animateWidth(nextInteractionSource)
                                    .width(nextButtonWidth)
                                    .onGloballyPositioned {
                                        nextCenter = it.boundsInRoot().center
                                    },
                                interactionSource = nextInteractionSource,
                                colors = IconButtonDefaults.filledIconButtonColors(
                                    containerColor = MaterialTheme.colorScheme.primary,
                                    contentColor = MaterialTheme.colorScheme.onPrimary,
                                ),
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.SkipNext,
                                    contentDescription = "Skip next",
                                    modifier = Modifier.size(controlIconSize),
                                )
                            }
                        },
                        menuContent = { _ -> },
                    )
                }

                Spacer(modifier = Modifier.weight(1f))

                // Tabletop adds an "expand lyrics" toggle to the right group (left of
                // shuffle); other layouts pass null and never render it.
                if (onExpandLyrics != null) {
                    val expandContainer = if (lyricsExpanded) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.tertiaryContainer
                    }
                    val expandContent = if (lyricsExpanded) {
                        MaterialTheme.colorScheme.onPrimary
                    } else {
                        MaterialTheme.colorScheme.onTertiaryContainer
                    }
                    FilledIconButton(
                        onClick = {
                            haptics.performTick()
                            onExpandLyrics()
                        },
                        modifier = Modifier.size(controlSize),
                        colors = IconButtonDefaults.filledIconButtonColors(
                            containerColor = expandContainer,
                            contentColor = expandContent,
                        ),
                    ) {
                        Icon(
                            imageVector = if (lyricsExpanded) {
                                Icons.Rounded.UnfoldLess
                            } else {
                                Icons.Rounded.UnfoldMore
                            },
                            contentDescription = if (lyricsExpanded) {
                                "Collapse lyrics"
                            } else {
                                "Expand lyrics"
                            },
                            modifier = Modifier.size(controlIconSize),
                        )
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                }

                val shuffleContainer by animateColorAsState(
                    targetValue = if (shuffleEnabled) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.tertiaryContainer
                    },
                    animationSpec = YoinMotion.defaultEffectsSpec(),
                    label = "shuffleContainer",
                )
                val shuffleContent by animateColorAsState(
                    targetValue = if (shuffleEnabled) {
                        MaterialTheme.colorScheme.onPrimary
                    } else {
                        MaterialTheme.colorScheme.onTertiaryContainer
                    },
                    animationSpec = YoinMotion.defaultEffectsSpec(),
                    label = "shuffleContent",
                )
                FilledIconButton(
                    onClick = {
                        haptics.performTick()
                        onToggleShuffle()
                    },
                    modifier = Modifier.size(controlSize),
                    colors = IconButtonDefaults.filledIconButtonColors(
                        containerColor = shuffleContainer,
                        contentColor = shuffleContent,
                    ),
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Shuffle,
                        contentDescription = if (shuffleEnabled) "Disable shuffle" else "Enable shuffle",
                        modifier = Modifier.size(controlIconSize),
                    )
                }
            }

            Spacer(modifier = Modifier.height(4.dp))

            BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
                // Below ~360dp the ~96dp of inline time labels would starve the wave
                // bar to a sliver (the narrow Fold left pane at ~1:1.5). There, drop
                // the labels under the bar so it keeps full width. Compact/Tabletop
                // (full width) stay inline exactly as before. The prev button + bar
                // live in one Row in both cases — only the labels move.
                val labelsInline = maxWidth >= 360.dp
                Column(modifier = Modifier.fillMaxWidth()) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        FilledIconButton(
                            onClick = {
                                if (prevCenter.isSpecified) {
                                    transportSignal?.burstFocalRoot = prevCenter
                                }
                                haptics.performTick()
                                onSkipPrevious()
                            },
                            modifier = Modifier
                                .size(controlSize)
                                .onGloballyPositioned {
                                    prevCenter = it.boundsInRoot().center
                                },
                            colors = IconButtonDefaults.filledIconButtonColors(
                                containerColor = MaterialTheme.colorScheme.secondary,
                                contentColor = MaterialTheme.colorScheme.onSecondary,
                            ),
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.SkipPrevious,
                                contentDescription = "Skip previous",
                                modifier = Modifier.size(controlIconSize),
                            )
                        }

                        if (labelsInline) {
                            PlaybackTimeLabel(
                                text = formatTrackDurationMs(positionMs),
                                modifier = Modifier
                                    .width(44.dp)
                                    .offset(y = 6.dp),
                            )
                        }
                        WaveProgressBar(
                            progress = progress,
                            buffered = buffered,
                            durationMs = durationMs,
                            onSeek = onSeek,
                            isPlaying = isPlaying,
                            noteAnchorsMs = noteAnchorsMs,
                            modifier = Modifier.weight(1f),
                        )
                        if (labelsInline) {
                            PlaybackTimeLabel(
                                text = "-${formatTrackDurationMs((durationMs - positionMs).coerceAtLeast(0L))}",
                                modifier = Modifier
                                    .width(52.dp)
                                    .offset(y = 6.dp),
                                textAlign = androidx.compose.ui.text.style.TextAlign.End,
                            )
                        }
                    }
                    if (!labelsInline) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(start = controlSize + 8.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            PlaybackTimeLabel(text = formatTrackDurationMs(positionMs))
                            PlaybackTimeLabel(
                                text = "-${formatTrackDurationMs((durationMs - positionMs).coerceAtLeast(0L))}",
                                textAlign = androidx.compose.ui.text.style.TextAlign.End,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PlaybackTimeLabel(
    text: String,
    modifier: Modifier = Modifier,
    textAlign: androidx.compose.ui.text.style.TextAlign = androidx.compose.ui.text.style.TextAlign.Start,
) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelLarge.withTabularFigures(),
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = textAlign,
        modifier = modifier,
    )
}
