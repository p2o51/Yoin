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
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
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
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.gpo.yoin.player.PlayMode
import com.gpo.yoin.symbols.SymbolPlayMode
import com.gpo.yoin.symbols.YoinSymbols
import com.gpo.yoin.symbols.rememberPlayModeSymbolPainter
import com.gpo.yoin.ui.component.WaveProgressBar
import com.gpo.yoin.ui.component.formatTrackDurationMs
import com.gpo.yoin.ui.experience.rememberYoinHaptics
import com.gpo.yoin.ui.theme.ProvideYoinMotionRole
import com.gpo.yoin.ui.theme.YoinMotion
import com.gpo.yoin.ui.theme.YoinMotionRole
import com.gpo.yoin.ui.theme.YoinTheme
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
    playMode: PlayMode = PlayMode.RepeatAll,
    onCyclePlayMode: () -> Unit = {},
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
      // Controls always fit (断点交接 §3.3): on a narrow column the PLAY pill
      // first gives up its side padding, then every control steps 56 → 48.
      // The play-mode button is measured before the transport group, so it is
      // never the one that gets cut.
      BoxWithConstraints(modifier = modifier.fillMaxWidth()) {
        val fit = rememberPlaybackControlsFit(
            maxWidth = maxWidth,
            controlSize = controlSize,
            hasExpandToggle = onExpandLyrics != null,
        )
        val controlSize = fit.controlSize
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
            } * fit.playPaddingScale,
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

        Column(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                ButtonGroup(
                    overflowIndicator = { _ -> },
                    // Weighted, not filling: measured AFTER the fixed trailing
                    // controls, so the group gets what they leave.
                    modifier = Modifier
                        .weight(1f, fill = false)
                        .height(controlButtonSize),
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
                                    imageVector = YoinSymbols.SkipNextFilled,
                                    contentDescription = "Skip next",
                                    modifier = Modifier.size(controlIconSize),
                                )
                            }
                        },
                        menuContent = { _ -> },
                    )
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                // Tabletop adds an "expand lyrics" toggle to the right group (left of
                // the play-mode button); other layouts pass null and never render it.
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
                                YoinSymbols.UnfoldLess
                            } else {
                                YoinSymbols.UnfoldMore
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

                // Play mode: repeat all (the default) keeps the resting tertiary
                // colours; shuffle and repeat one light up like shuffle-on used to.
                val playModeLit = playMode != PlayMode.RepeatAll
                val playModeContainer by animateColorAsState(
                    targetValue = if (playModeLit) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.tertiaryContainer
                    },
                    animationSpec = YoinMotion.defaultEffectsSpec(),
                    label = "playModeContainer",
                )
                val playModeContent by animateColorAsState(
                    targetValue = if (playModeLit) {
                        MaterialTheme.colorScheme.onPrimary
                    } else {
                        MaterialTheme.colorScheme.onTertiaryContainer
                    },
                    animationSpec = YoinMotion.defaultEffectsSpec(),
                    label = "playModeContent",
                )
                val playModeState = playMode.stateDescription()
                FilledIconButton(
                    onClick = {
                        haptics.performTick()
                        onCyclePlayMode()
                    },
                    modifier = Modifier
                        .size(controlSize)
                        .semantics { stateDescription = playModeState },
                    colors = IconButtonDefaults.filledIconButtonColors(
                        containerColor = playModeContainer,
                        contentColor = playModeContent,
                    ),
                ) {
                    Icon(
                        painter = rememberPlayModeSymbolPainter(playMode.toSymbol()),
                        contentDescription = "Play mode",
                        modifier = Modifier.size(controlIconSize),
                    )
                }
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
                                imageVector = YoinSymbols.SkipPreviousFilled,
                                contentDescription = "Skip previous",
                                modifier = Modifier.size(controlIconSize),
                            )
                        }

                        if (labelsInline) {
                            PlaybackTimeLabel(
                                text = formatTrackDurationMs(positionMs),
                                modifier = Modifier
                                    // minWidth (not fixed width): fontScale>1 grows
                                    // instead of clipping; tabular figures keep the
                                    // fontScale-1 footprint identical.
                                    .defaultMinSize(minWidth = 44.dp)
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
                                    .defaultMinSize(minWidth = 52.dp)
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
        maxLines = 1,
        softWrap = false,
        modifier = modifier,
    )
}

private fun PlayMode.toSymbol(): SymbolPlayMode = when (this) {
    PlayMode.RepeatAll -> SymbolPlayMode.RepeatAll
    PlayMode.Shuffle -> SymbolPlayMode.Shuffle
    PlayMode.RepeatOne -> SymbolPlayMode.RepeatOne
}

/** TalkBack reads the button as "Play mode, <state>, button". */
private fun PlayMode.stateDescription(): String = when (this) {
    PlayMode.RepeatAll -> "Repeat all"
    PlayMode.Shuffle -> "Shuffle"
    PlayMode.RepeatOne -> "Repeat one"
}

/** How the transport rows fit a column; see [rememberPlaybackControlsFit]. */
internal data class PlaybackControlsFit(
    val controlSize: Dp,
    val playPaddingScale: Float,
)

/**
 * Pure fit rule (unit-tested): the natural row is
 * [PLAY/PAUSE + padding][Next] … [expand?][Play mode]. Too wide → halve PLAY's
 * padding; still too wide → 48dp controls. Play mode is never shrunk away.
 */
internal fun fitPlaybackControls(
    maxWidth: Dp,
    controlSize: Dp,
    playTextWidth: Dp,
    hasExpandToggle: Boolean,
): PlaybackControlsFit {
    fun needs(size: Dp, paddingScale: Float): Dp {
        val play = playTextWidth * PlayTextStretchMax + PlayRestPadding * 2 * paddingScale
        val trailing = size + if (hasExpandToggle) size + TransportGap else 0.dp
        return play + TransportGap + size + TransportGap + trailing
    }
    return when {
        needs(controlSize, 1f) <= maxWidth -> PlaybackControlsFit(controlSize, 1f)
        needs(controlSize, 0.5f) <= maxWidth -> PlaybackControlsFit(controlSize, 0.5f)
        else -> PlaybackControlsFit(minOf(controlSize, CompactControlSize), 0.5f)
    }
}

@Composable
private fun rememberPlaybackControlsFit(
    maxWidth: Dp,
    controlSize: Dp,
    hasExpandToggle: Boolean,
): PlaybackControlsFit {
    val textMeasurer = rememberTextMeasurer()
    val style = MaterialTheme.typography.titleLarge.let { base ->
        base.copy(
            fontSize = base.fontSize * 0.9f,
            fontWeight = FontWeight.Bold,
            letterSpacing = 0.5.sp,
        )
    }
    val density = LocalDensity.current
    return remember(maxWidth, controlSize, hasExpandToggle, style, density) {
        val playTextWidth = with(density) {
            textMeasurer.measure("PAUSE", style, maxLines = 1, softWrap = false).size.width.toDp()
        }
        fitPlaybackControls(maxWidth, controlSize, playTextWidth, hasExpandToggle)
    }
}

private val PlayRestPadding = 24.dp
private val TransportGap = 8.dp
private val CompactControlSize = 48.dp

/** PLAY's text stretch peaks at 1.10 while pressed. */
private const val PlayTextStretchMax = 1.1f

// ── Previews: one per play mode ─────────────────────────────────────────

@Composable
private fun PlayModePreviewControls(playMode: PlayMode) {
    YoinTheme {
        PlaybackControls(
            isPlaying = true,
            onTogglePlayPause = {},
            onSkipNext = {},
            onSkipPrevious = {},
            positionMs = 96_000L,
            durationMs = 240_000L,
            progress = 0.4f,
            buffered = 0.7f,
            onSeek = {},
            playInteractionSource = remember { MutableInteractionSource() },
            nextInteractionSource = remember { MutableInteractionSource() },
            playPressed = false,
            nextPressed = false,
            playMode = playMode,
        )
    }
}

@Preview(name = "Play mode · repeat all", showBackground = true, backgroundColor = 0xFF1C1B1F)
@Composable
private fun PlaybackControlsRepeatAllPreview() {
    PlayModePreviewControls(PlayMode.RepeatAll)
}

@Preview(name = "Play mode · shuffle", showBackground = true, backgroundColor = 0xFF1C1B1F)
@Composable
private fun PlaybackControlsShufflePreview() {
    PlayModePreviewControls(PlayMode.Shuffle)
}

@Preview(name = "Play mode · repeat one", showBackground = true, backgroundColor = 0xFF1C1B1F)
@Composable
private fun PlaybackControlsRepeatOnePreview() {
    PlayModePreviewControls(PlayMode.RepeatOne)
}
