package com.gpo.yoin.ui.nowplaying

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ButtonGroup
import androidx.compose.material3.ButtonGroupDefaults
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.isSpecified
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.toggleableState
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.lerp
import androidx.compose.ui.unit.sp
import com.gpo.yoin.R
import com.gpo.yoin.player.PlayMode
import com.gpo.yoin.symbols.SymbolPlayMode
import com.gpo.yoin.symbols.YoinSymbols
import com.gpo.yoin.symbols.rememberPlayModeSymbolPainter
import com.gpo.yoin.symbols.rememberPlayPauseSymbolPainter
import com.gpo.yoin.ui.component.WaveProgressBar
import com.gpo.yoin.ui.component.formatTrackDurationMs
import com.gpo.yoin.ui.experience.rememberYoinHaptics
import com.gpo.yoin.ui.theme.ProvideYoinMotionRole
import com.gpo.yoin.ui.theme.YoinMotion
import com.gpo.yoin.ui.theme.YoinMotionRole
import com.gpo.yoin.ui.theme.YoinTheme
import com.gpo.yoin.ui.theme.withTabularFigures
import kotlinx.coroutines.delay

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
    playMode: PlayMode = PlayMode.RepeatAll,
    onSetPlayMode: (PlayMode) -> Unit = {},
    controlSize: Dp = 56.dp,
    lyricsExpanded: Boolean = false,
    onExpandLyrics: (() -> Unit)? = null,
    noteAnchorsMs: List<Long> = emptyList(),
    modifier: Modifier = Modifier,
    // False while the row is hidden (see WaveProgressBar's `animating`).
    live: Boolean = true,
) {
    ProvideYoinMotionRole(role = YoinMotionRole.Standard) {
        val haptics = rememberYoinHaptics()
        // Publish the PLAY/PAUSE press to the reactive background so it can gather
        // light while the finger is held (the release burst fires off pulseTrigger).
        val transportSignal = LocalNowPlayingTransportSignal.current
        LaunchedEffect(transportSignal, playPressed) {
            transportSignal?.playHeld = playPressed
        }
        // Each transport button records its own centre, and every tap is stamped
        // with its time and kind, so the background's release burst radiates from
        // the button you actually tapped — and only for the change that tap caused
        // (see resolveTransportPulse).
        var playCenter by remember { mutableStateOf(Offset.Unspecified) }
        var nextCenter by remember { mutableStateOf(Offset.Unspecified) }
        var prevCenter by remember { mutableStateOf(Offset.Unspecified) }
        // PREVIOUS is stamped with the playhead it was tapped at, and every tick
        // reports the playhead back: a PREVIOUS the player answered with a
        // restart (no song change) is spent the moment the playhead lands back
        // on this song, so it neither eats the next headset play/pause breath
        // nor lends its button to a later untapped change.
        val latestPositionMs by rememberUpdatedState(positionMs)
        SideEffect { transportSignal?.observePlayhead(positionMs) }
        // Only English spells the word; every other language (including one
        // added later) shows the symbol. Read from the locale, not a values-en
        // resource: a values-en folder makes Lint treat English as a translation.
        val wordLabel = LocalConfiguration.current.locales[0].language == "en"
        val wordMeasurer = rememberTextMeasurer()
        val word = stringResource(
            if (isPlaying) R.string.np_transport_pause_word else R.string.np_transport_play_word
        )
        val wordStyle = MaterialTheme.typography.titleLarge.let { base ->
            base.copy(
                fontSize = base.fontSize * 0.9f,
                fontWeight = if (isPlaying) FontWeight.Bold else FontWeight.Medium,
                letterSpacing = if (isPlaying) 0.5.sp else 0.sp,
            )
        }
        val wordTargetWidth = with(LocalDensity.current) {
            wordMeasurer.measure(word, wordStyle, maxLines = 1, softWrap = false).size.width.toDp()
        }
        // PLAY ⇄ PAUSE reshapes the pill with ONE spring: the word's box and the
        // side padding share it, and it is the Standard fast spatial spring the
        // play/pause symbol morphs with (SymbolMotion.control), so pill and glyph
        // land together. ButtonGroup sizes each item from its max intrinsic
        // width, and animateContentSize passes intrinsics straight through to the
        // un-animated target (ticket 4: a one-frame 20dp jump that threw NEXT
        // sideways), so the animated width has to live on the content. Presses
        // belong to the group's animateWidth alone (the pressed item grows, its
        // neighbour yields); Yoin adds no press widths of its own on top.
        val pillSpatialSpec = YoinMotion.fastSpatialSpec<Dp>()
        val wordWidth by animateDpAsState(
            targetValue = wordTargetWidth,
            animationSpec = pillSpatialSpec,
            label = "playWordWidth",
        )
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
            val textStretchSpec = if (playPressed) {
                YoinMotion.fastSpatialSpec<Float>()
            } else {
                YoinMotion.defaultSpatialSpec<Float>()
            }
            val playHorizontalPadding by animateDpAsState(
                targetValue = (if (isPlaying) 24.dp else 16.dp) * fit.playPaddingScale,
                animationSpec = pillSpatialSpec,
                label = "playHorizontalPadding",
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
                                            transportSignal?.recordTap(playCenter, TransportTapKind.PlayPause)
                                        }
                                        haptics.performClick()
                                        onTogglePlayPause()
                                    },
                                    modifier = Modifier
                                        .fillMaxHeight()
                                        .animateWidth(playInteractionSource)
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
                                    // The word's box carries the pill's width (see
                                    // pillSpatialSpec); the English word lays out at its
                                    // own width inside it, so it never re-sizes the pill.
                                    // Every other language shows the symbol in the same
                                    // box, swelling with the word's stretch.
                                    Box(
                                        modifier = Modifier.width(wordWidth),
                                        contentAlignment = Alignment.Center,
                                    ) {
                                        if (wordLabel) {
                                            Text(
                                                text = word,
                                                style = wordStyle,
                                                maxLines = 1,
                                                softWrap = false,
                                                modifier = Modifier
                                                    .wrapContentWidth(unbounded = true)
                                                    .graphicsLayer {
                                                        scaleX = textStretchScale
                                                        transformOrigin = TransformOrigin(0f, 0.5f)
                                                    },
                                            )
                                        } else {
                                            Icon(
                                                painter = rememberPlayPauseSymbolPainter(playing = isPlaying),
                                                contentDescription = if (isPlaying) {
                                                    stringResource(R.string.np_cd_transport_pause)
                                                } else {
                                                    stringResource(R.string.np_cd_transport_play)
                                                },
                                                modifier = Modifier
                                                    .size(controlIconSize)
                                                    .graphicsLayer {
                                                        scaleX = textStretchScale
                                                        scaleY = textStretchScale
                                                    },
                                            )
                                        }
                                    }
                                }
                            },
                            menuContent = { _ -> },
                        )

                        customItem(
                            buttonGroupContent = {
                                FilledIconButton(
                                    onClick = {
                                        if (nextCenter.isSpecified) {
                                            transportSignal?.recordTap(nextCenter, TransportTapKind.SkipNext)
                                        }
                                        haptics.performTick()
                                        onSkipNext()
                                    },
                                    modifier = Modifier
                                        .fillMaxHeight()
                                        .animateWidth(nextInteractionSource)
                                        .width(controlButtonSize)
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
                                        contentDescription = stringResource(R.string.np_cd_skip_next),
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
                                        stringResource(R.string.np_cd_collapse_lyrics)
                                    } else {
                                        stringResource(R.string.np_cd_expand_lyrics)
                                    },
                                    modifier = Modifier.size(controlIconSize),
                                )
                            }
                            Spacer(modifier = Modifier.width(8.dp))
                        }

                        PlayModeButton(
                            playMode = playMode,
                            onSetPlayMode = onSetPlayMode,
                            height = controlSize,
                            splitWidth = fit.playModeWidth,
                            iconSize = controlIconSize,
                        )
                    }
                }

                Spacer(modifier = Modifier.height(TransportRowGap))

                BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
                    // Below ~360dp the ~96dp of inline time labels would starve the wave
                    // bar to a sliver (the narrow Fold left pane at ~1:1.5). There, drop
                    // the labels under the bar so it keeps full width. Compact/Tabletop
                    // (full width) stay inline exactly as before. The prev button + bar
                    // live in one Row in both cases — only the labels move.
                    val labelsInline = maxWidth >= PlaybackLabelsInlineMinWidth
                    Column(modifier = Modifier.fillMaxWidth()) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            FilledIconButton(
                                onClick = {
                                    if (prevCenter.isSpecified) {
                                        transportSignal?.recordTap(
                                            prevCenter,
                                            TransportTapKind.SkipPrevious,
                                            positionMs = latestPositionMs,
                                        )
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
                                    contentDescription = stringResource(R.string.np_cd_skip_previous),
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
                                animating = live,
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

/** TalkBack reads the button as "Play mode, <state>, button". */
@Composable
private fun PlayMode.stateDescription(): String = when (this) {
    PlayMode.RepeatAll -> stringResource(R.string.np_transport_repeat_all)
    PlayMode.Shuffle -> stringResource(R.string.np_transport_shuffle)
    PlayMode.ShuffleRepeatOne -> stringResource(R.string.np_transport_shuffle_repeat_one)
    PlayMode.RepeatOne -> stringResource(R.string.np_transport_repeat_one)
}

/**
 * The play-mode button (owner 2026-10-17): a round button by default, its symbol showing the
 * mode ([PlayMode.toSymbol]); a tap steps [PlayMode.next]. A long-press splits it into a pill of
 * two segments — shuffle on the left, repeat on the right — each tap on a segment switching only
 * its own axis; left alone for [PlayModeSplitHoldMs] (or long-pressed again) it merges back into
 * the circle. Splitting, the circle's symbol becomes two copies that move apart into the segments
 * while they morph — the left into the crossing strands, the right into the loop (with its 1 for
 * repeat one); merging runs the same morph back into one symbol. Width, gap, inner corners and the
 * symbols' travel share one spatial spring; colours ride the effects spring. A segment lights up
 * (`primary`) when its axis leaves the default; the circle lights up for any mode but repeat all.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun PlayModeButton(
    playMode: PlayMode,
    onSetPlayMode: (PlayMode) -> Unit,
    height: Dp,
    splitWidth: Dp,
    iconSize: Dp,
    modifier: Modifier = Modifier,
) {
    val haptics = rememberYoinHaptics()
    var split by rememberSaveable { mutableStateOf(false) }
    // One more on every tap while split: the merge waits for a quiet spell.
    var splitTouches by remember { mutableIntStateOf(0) }
    LaunchedEffect(split, splitTouches) {
        if (split) {
            delay(PlayModeSplitHoldMs)
            split = false
        }
    }
    val f by animateFloatAsState(
        targetValue = if (split) 1f else 0f,
        animationSpec = YoinMotion.defaultSpatialSpec(),
        label = "playModeSplit",
    )
    val width = lerp(height, splitWidth, f)
    val gap = PlayModeSegmentGap * f
    val segment = (width - gap) / 2
    val outer = height / 2
    // Square inner edges at rest, so the two halves close into one circle.
    val inner = height * PlayModeInnerCornerRatio * f
    val glyphSplit = minOf(iconSize, (splitWidth - PlayModeSegmentGap) / 2 - PlayModeGlyphInset)
    val glyph = lerp(iconSize, glyphSplit, f)

    val merged = playMode.toSymbol()
    val leftLit = if (split) playMode.shuffle else playMode != PlayMode.RepeatAll
    val rightLit = if (split) playMode.repeatOne else playMode != PlayMode.RepeatAll
    val leftPainter = rememberPlayModeSymbolPainter(if (split) SymbolPlayMode.Shuffle else merged)
    val rightPainter = rememberPlayModeSymbolPainter(
        when {
            !split -> merged
            playMode.repeatOne -> SymbolPlayMode.RepeatOne
            else -> SymbolPlayMode.RepeatAll
        },
    )
    val leftColors = playModeColors(leftLit)
    val rightColors = playModeColors(rightLit)

    val label = stringResource(R.string.np_cd_play_mode)
    val state = playMode.stateDescription()
    val splitLabel = stringResource(R.string.np_cd_play_mode_split)
    val shuffleLabel = stringResource(R.string.np_transport_shuffle)
    val repeatOneLabel = stringResource(R.string.np_transport_repeat_one)
    val leftShape = RoundedCornerShape(topStart = outer, bottomStart = outer, topEnd = inner, bottomEnd = inner)
    val rightShape = RoundedCornerShape(topStart = inner, bottomStart = inner, topEnd = outer, bottomEnd = outer)
    val merge = {
        haptics.performLongPress()
        split = false
    }

    Box(
        modifier = modifier
            .width(width)
            .height(height)
            .then(
                if (split) {
                    Modifier
                } else {
                    Modifier
                        .clip(CircleShape)
                        .combinedClickable(
                            role = Role.Button,
                            onClickLabel = null,
                            onLongClickLabel = splitLabel,
                            onLongClick = {
                                haptics.performLongPress()
                                split = true
                                splitTouches = 0
                            },
                            onClick = {
                                haptics.performTick()
                                onSetPlayMode(playMode.next())
                            },
                        )
                        .semantics {
                            contentDescription = label
                            stateDescription = state
                        }
                },
            ),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(gap), modifier = Modifier.fillMaxHeight()) {
            PlayModeHalf(
                color = leftColors.first,
                shape = leftShape,
                width = segment,
                split = split,
                label = shuffleLabel,
                on = playMode.shuffle,
                onToggle = {
                    haptics.performTick()
                    splitTouches += 1
                    onSetPlayMode(playMode.withShuffle(!playMode.shuffle))
                },
                onMerge = merge,
            )
            PlayModeHalf(
                color = rightColors.first,
                shape = rightShape,
                width = segment,
                split = split,
                label = repeatOneLabel,
                on = playMode.repeatOne,
                onToggle = {
                    haptics.performTick()
                    splitTouches += 1
                    onSetPlayMode(playMode.withRepeatOne(!playMode.repeatOne))
                },
                onMerge = merge,
            )
        }
        // The symbols: one in the middle of the circle; split, two copies travel out to their
        // segments' middles (and back) while they morph.
        val density = LocalDensity.current
        val centre = width / 2
        val leftCentre = lerp(centre, segment / 2, f)
        val rightCentre = lerp(centre, width - segment / 2, f)
        Icon(
            painter = leftPainter,
            contentDescription = null,
            tint = leftColors.second,
            modifier = Modifier
                .align(Alignment.CenterStart)
                .offset { IntOffset(with(density) { (leftCentre - glyph / 2).roundToPx() }, 0) }
                .size(glyph),
        )
        if (f > PlayModeSplitShownFrom || split) {
            Icon(
                painter = rightPainter,
                contentDescription = null,
                tint = rightColors.second,
                modifier = Modifier
                    .align(Alignment.CenterStart)
                    .offset { IntOffset(with(density) { (rightCentre - glyph / 2).roundToPx() }, 0) }
                    .size(glyph),
            )
        }
    }
}

/** One half of the play-mode button; split, it is its own switch (a tap toggles, a long-press merges). */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun PlayModeHalf(
    color: Color,
    shape: Shape,
    width: Dp,
    split: Boolean,
    label: String,
    on: Boolean,
    onToggle: () -> Unit,
    onMerge: () -> Unit,
) {
    Box(
        modifier = Modifier
            .width(width)
            .fillMaxHeight()
            .background(color, shape)
            .then(
                if (split) {
                    Modifier
                        .clip(shape)
                        .combinedClickable(onLongClick = onMerge, onClick = onToggle)
                        .semantics {
                            contentDescription = label
                            role = Role.Switch
                            toggleableState = ToggleableState(on)
                        }
                } else {
                    Modifier
                },
            ),
    )
}

/** Container and symbol colours for a lit (`primary`) or resting (`tertiaryContainer`) part. */
@Composable
private fun playModeColors(lit: Boolean): Pair<Color, Color> {
    val container by animateColorAsState(
        targetValue = if (lit) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.tertiaryContainer,
        animationSpec = YoinMotion.defaultEffectsSpec(),
        label = "playModeContainer",
    )
    val content by animateColorAsState(
        targetValue = if (lit) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onTertiaryContainer,
        animationSpec = YoinMotion.defaultEffectsSpec(),
        label = "playModeContent",
    )
    return container to content
}

private fun PlayMode.toSymbol(): SymbolPlayMode = when (this) {
    PlayMode.RepeatAll -> SymbolPlayMode.RepeatAll
    PlayMode.Shuffle -> SymbolPlayMode.Shuffle
    PlayMode.ShuffleRepeatOne -> SymbolPlayMode.ShuffleRepeatOne
    PlayMode.RepeatOne -> SymbolPlayMode.RepeatOne
}

/** Split, the pill merges back into the circle after this long without a tap. */
internal const val PlayModeSplitHoldMs = 3_000L

/** Below this much split the second symbol sits exactly under the first: one is drawn. */
private const val PlayModeSplitShownFrom = 0.02f

/** How the transport rows fit a column; see [rememberPlaybackControlsFit]. */
internal data class PlaybackControlsFit(
    val controlSize: Dp,
    val playPaddingScale: Float,
    val playModeWidth: Dp = playModeNaturalWidth(controlSize),
)

/** The play-mode pill when split: two segments of 0.8 × the control size. */
internal fun playModeNaturalWidth(controlSize: Dp): Dp = controlSize * 1.6f + PlayModeSegmentGap

/** The narrowest the play-mode pill may get before it would stop reading as two segments. */
internal fun playModeMinWidth(controlSize: Dp): Dp = controlSize * 1.1f

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
    // Everything but the play-mode pill.
    fun rest(size: Dp, paddingScale: Float): Dp {
        val play = playTextWidth * PlayTextStretchMax + PlayRestPadding * 2 * paddingScale
        val expand = if (hasExpandToggle) size + TransportGap else 0.dp
        return play + TransportGap + size + TransportGap + expand
    }
    fun fits(size: Dp, paddingScale: Float) = rest(size, paddingScale) + playModeNaturalWidth(size) <= maxWidth
    return when {
        fits(controlSize, 1f) -> PlaybackControlsFit(controlSize, 1f)
        fits(controlSize, 0.5f) -> PlaybackControlsFit(controlSize, 0.5f)
        else -> {
            // Last step: 48dp controls, and the pill narrows into what is left —
            // never below playModeMinWidth, so it stays a whole control.
            val size = minOf(controlSize, CompactControlSize)
            val left = maxWidth - rest(size, 0.5f)
            PlaybackControlsFit(
                controlSize = size,
                playPaddingScale = 0.5f,
                playModeWidth = left.coerceIn(playModeMinWidth(size), playModeNaturalWidth(size)),
            )
        }
    }
}

@Composable
internal fun rememberPlaybackControlsFit(
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
    val pauseWord = stringResource(R.string.np_transport_pause_word)
    val density = LocalDensity.current
    return remember(maxWidth, controlSize, hasExpandToggle, style, density, pauseWord) {
        val playTextWidth = with(density) {
            textMeasurer.measure(pauseWord, style, maxLines = 1, softWrap = false).size.width.toDp()
        }
        fitPlaybackControls(maxWidth, controlSize, playTextWidth, hasExpandToggle)
    }
}

/**
 * The transport's height in a column [width] wide: two control rows, the
 * gap, and the time-label row when the labels drop below the wave
 * (height budgets size their slot from this, never from a guess).
 */
internal fun playbackControlsHeight(controlSize: Dp, width: Dp, labelLine: Dp): Dp =
    controlSize * 2 + TransportRowGap + if (width >= PlaybackLabelsInlineMinWidth) 0.dp else labelLine

/** Below this the inline time labels would starve the wave; they move under it. */
internal val PlaybackLabelsInlineMinWidth = 360.dp
private val TransportRowGap = 4.dp

private val PlayRestPadding = 24.dp
private val TransportGap = 8.dp
private val CompactControlSize = 48.dp

// English only (see wordLabel); other languages show the symbol in a
// pill measured from np_transport_pause_word, so the shape never changes
// with the language.

/** PLAY's text stretch peaks at 1.10 while pressed. */
private const val PlayTextStretchMax = 1.1f

/** The play-mode pill's split, its inner corners at rest, and the glyph's breathing room. */
private val PlayModeSegmentGap = 2.dp
private const val PlayModeInnerCornerRatio = 0.18f
private val PlayModeGlyphInset = 10.dp

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

@Preview(name = "Play mode · shuffle + repeat one", showBackground = true, backgroundColor = 0xFF1C1B1F)
@Composable
private fun PlaybackControlsShuffleRepeatOnePreview() {
    PlayModePreviewControls(PlayMode.ShuffleRepeatOne)
}

@Preview(name = "Play mode · repeat one", showBackground = true, backgroundColor = 0xFF1C1B1F)
@Composable
private fun PlaybackControlsRepeatOnePreview() {
    PlayModePreviewControls(PlayMode.RepeatOne)
}
