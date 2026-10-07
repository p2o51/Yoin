package com.gpo.yoin.ui.component

import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ButtonColors
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
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
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.layout.layout
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.gpo.yoin.symbols.YoinSymbols
import com.gpo.yoin.ui.experience.rememberYoinHaptics
import com.gpo.yoin.ui.navigation.nowPlayingCoverSharedKey
import com.gpo.yoin.ui.navigation.rememberActiveOnlySharedContentConfig
import com.gpo.yoin.ui.theme.YoinArtworkShapes
import com.gpo.yoin.ui.theme.YoinMotion
import com.gpo.yoin.ui.theme.YoinMotionRole
import kotlin.math.sin

/**
 * The now-playing pill: mini artwork + marquee title/artist over a sine-edged
 * playback-progress wash. Extracted from the shell Button Group so the detail
 * pages' bottom bar renders the EXACT same pill — the two windows' bars must
 * be pixel twins for the shell⇄detail hand-off to read as one persistent bar.
 *
 * The shared-element hooks (cover / np_title / np_artist) only engage when the
 * caller passes both scopes — the shell does, detail Activities don't.
 *
 * [enabled] false (the pill fading out of the bar's Home edit pose) takes no
 * press, click or haptic, and looks exactly the same.
 */
@OptIn(ExperimentalFoundationApi::class, ExperimentalSharedTransitionApi::class)
@Composable
fun NowPlayingPill(
    currentTrackId: String?,
    currentTrackTitle: String?,
    currentTrackArtist: String?,
    currentTrackCoverArtUrl: String?,
    connectionErrorMessage: String?,
    playbackProgress: Float,
    isPlaying: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    sharedTransitionScope: SharedTransitionScope? = null,
    animatedVisibilityScope: AnimatedVisibilityScope? = null,
    interactionSource: MutableInteractionSource? = null,
    enabled: Boolean = true,
) {
    val haptics = rememberYoinHaptics()
    // Theme tokens already share one app-wide wash. Only animate the local
    // empty/playing state; a second color spring would lag behind a new window.
    val trackPresence by animateFloatAsState(
        targetValue = if (currentTrackTitle != null) 1f else 0f,
        animationSpec = YoinMotion.defaultEffectsSpec(),
        label = "nowPlayingPillTrackPresence",
    )
    val colors = MaterialTheme.colorScheme
    val containerColor = lerp(colors.surfaceContainerHighest, colors.primaryContainer, trackPresence)
    val contentColor = lerp(colors.onSurfaceVariant, colors.onPrimaryContainer, trackPresence)
    val progressFillColor = colors.primary.copy(alpha = 0.25f)
    val wave = rememberPlaybackWave(isPlaying)
    val waveHold = rememberCoveredWaveHold(wave)
    val sharedBoundsSpec = YoinMotion.defaultSpatialSpec<Rect>(
        role = YoinMotionRole.Standard,
        expressiveScheme = MaterialTheme.motionScheme,
    )
    val clampedProgress = playbackProgress.coerceIn(0f, 1f)

    // Track-change PUSH: every hand-off — manual skip, auto-advance, or a
    // remote device switching songs — pushes the next track's content in
    // from the RIGHT while the old one slides out left (fast linear exit,
    // Expressive spring landing). The pill renders [shown], a held copy of
    // the track props, so the old song stays visible through its exit frame.
    // Plain graphicsLayer motion, NOT AnimatedContent: this bar lives under
    // the shell's shared-transition lookahead, which chokes on
    // size-transforming containers (class KDoc of YoinButtonGroup). The
    // idle→first-track transition skips the push — the idle→pill width
    // morph is already the entrance.
    val push = remember { Animatable(0f) }
    val pushInSpec = YoinMotion.defaultSpatialSpec<Float>(role = YoinMotionRole.Expressive)
    var shown by remember {
        mutableStateOf(
            PillTrack(currentTrackId, currentTrackTitle, currentTrackArtist, currentTrackCoverArtUrl),
        )
    }
    LaunchedEffect(currentTrackId, currentTrackTitle, currentTrackArtist, currentTrackCoverArtUrl) {
        val next =
            PillTrack(currentTrackId, currentTrackTitle, currentTrackArtist, currentTrackCoverArtUrl)
        val previous = shown
        if (previous.id != null && next.id != null && previous.id != next.id) {
            // A restart mid-push (rapid skips) lands the interrupted frame
            // first so the two pushes never compound.
            if (push.value != 0f) push.snapTo(0f)
            push.animateTo(1f, tween(durationMillis = 90, easing = LinearEasing))
            shown = next
            push.snapTo(-1f)
            push.animateTo(0f, pushInSpec)
        } else {
            // Same track (metadata/cover refresh) or idle transitions: sync
            // silently.
            shown = next
        }
    }

    FilledTonalButton(
        onClick = {
            haptics.performContextClick()
            onClick()
        },
        modifier = modifier,
        enabled = enabled,
        interactionSource = interactionSource,
        shape = MaterialTheme.shapes.extraLarge,
        contentPadding = PaddingValues(0.dp),
        colors = pillButtonColors(containerColor, contentColor),
    ) {
        // Fill the whole button, not just the content: the bar forces a 48dp
        // height while the content row is ~42dp — a wrap-content Box here lets
        // the wave wash (matchParentSize below) shrink to the content and leave
        // a container-colored seam above/below it.
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.CenterStart,
        ) {
            if (currentTrackTitle != null && clampedProgress > 0f) {
                Box(
                    modifier = Modifier
                        .matchParentSize()
                        .clip(MaterialTheme.shapes.extraLarge)
                        // Its own layer: the wave's per-frame redraw re-records only this path, not the pill's
                        // cover and text.
                        .graphicsLayer()
                        .drawWithContent {
                            drawContent()
                            val width = size.width
                            val height = size.height
                            val progressX = width * clampedProgress
                            val phase = waveHold.phase()
                            val amplitude = 4.dp.toPx() * waveHold.amplitude()
                            val waveSteps = 20

                            val path = Path().apply {
                                moveTo(0f, 0f)
                                lineTo(progressX, 0f)
                                for (index in 0..waveSteps) {
                                    val fraction = index.toFloat() / waveSteps
                                    val y = fraction * height
                                    val dx = sin(
                                        phase +
                                            fraction * 2f * Math.PI.toFloat(),
                                    ) * amplitude
                                    lineTo(progressX + dx, y)
                                }
                                lineTo(0f, height)
                                close()
                            }
                            drawPath(path, progressFillColor)
                        },
                )
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    // No vertical padding: the row is centred in the pill and
                    // the 44dp centred bar has no height to spare for the two
                    // text lines at a large font scale.
                    .padding(horizontal = 10.dp)
                    .graphicsLayer {
                        // push 0→1 carries the OLD track out left; after the
                        // swap it runs -1→0, the NEW track riding in from
                        // the right.
                        val p = push.value
                        translationX = -PillPushTravel.toPx() * p
                        alpha = 1f - 0.9f * kotlin.math.abs(p)
                    },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                NowPlayingPillArtwork(
                    currentTrackId = shown.id,
                    currentTrackCoverArtUrl = shown.cover,
                    currentTrackTitle = shown.title,
                    sharedTransitionScope = sharedTransitionScope,
                    animatedVisibilityScope = animatedVisibilityScope,
                )
                Spacer(modifier = Modifier.width(10.dp))
                Column(modifier = Modifier.weight(1f).wrapContentHeight(unbounded = true)) {
                    val titleText = shown.title ?: when {
                        connectionErrorMessage != null -> "Playback unavailable"
                        else -> "Nothing playing"
                    }
                    val artistText = shown.artist ?: when {
                        connectionErrorMessage != null -> connectionErrorMessage
                        else -> "Tap to open player"
                    }

                    val titleModifier = if (
                        sharedTransitionScope != null &&
                        animatedVisibilityScope != null &&
                        shown.title != null
                    ) {
                        val sharedContentConfig =
                            rememberActiveOnlySharedContentConfig(
                                animatedVisibilityScope = animatedVisibilityScope,
                            )
                        with(sharedTransitionScope) {
                            Modifier.sharedBounds(
                                sharedContentState = rememberSharedContentState(
                                    key = "np_title",
                                    config = sharedContentConfig,
                                ),
                                animatedVisibilityScope = animatedVisibilityScope,
                                boundsTransform = { _, _ -> sharedBoundsSpec },
                            )
                        }
                    } else {
                        Modifier
                    }
                    val marqueeTitleModifier = if (shown.title != null) {
                        titleModifier.basicMarquee(
                            iterations = Int.MAX_VALUE,
                            repeatDelayMillis = 2000,
                            initialDelayMillis = 1500,
                        )
                    } else {
                        titleModifier
                    }
                    Text(
                        text = titleText,
                        style = MaterialTheme.typography.labelLarge,
                        color = contentColor,
                        maxLines = 1,
                        softWrap = false,
                        // A track title marquees; the idle / error status, which
                        // doesn't, ends in an ellipsis instead of a hard cut (a
                        // 369dp fold cover screen clipped "Nothing playir").
                        overflow = if (shown.title != null) TextOverflow.Clip else TextOverflow.Ellipsis,
                        modifier = marqueeTitleModifier,
                    )

                    val artistModifier = if (
                        sharedTransitionScope != null &&
                        animatedVisibilityScope != null &&
                        shown.artist != null
                    ) {
                        val sharedContentConfig =
                            rememberActiveOnlySharedContentConfig(
                                animatedVisibilityScope = animatedVisibilityScope,
                            )
                        with(sharedTransitionScope) {
                            Modifier.sharedBounds(
                                sharedContentState = rememberSharedContentState(
                                    key = "np_artist",
                                    config = sharedContentConfig,
                                ),
                                animatedVisibilityScope = animatedVisibilityScope,
                                boundsTransform = { _, _ -> sharedBoundsSpec },
                            )
                        }
                    } else {
                        Modifier
                    }
                    val marqueeArtistModifier = if (shown.artist != null) {
                        artistModifier.basicMarquee(
                            iterations = Int.MAX_VALUE,
                            repeatDelayMillis = 2000,
                            initialDelayMillis = 2500,
                        )
                    } else {
                        artistModifier
                    }
                    Text(
                        text = artistText,
                        style = MaterialTheme.typography.labelSmall,
                        color = contentColor.copy(alpha = 0.72f),
                        maxLines = 1,
                        softWrap = false,
                        overflow = if (shown.artist != null) TextOverflow.Clip else TextOverflow.Ellipsis,
                        modifier = marqueeArtistModifier,
                    )
                }
            }
        }
    }
}

/**
 * The same pill turned upright for the edge-split capsule
 * (ShellChromeForm.EdgeSplit, 断点交接 §2.2): 52 × (capsule − 12), 26dp
 * corners. The progress wash rises from the bottom behind the same sine edge
 * (turned 90°), the 34dp cover sits at the bottom (9dp inset) and title /
 * artist read bottom-to-top above it, ellipsized when they don't fit.
 *
 * Only the cover keeps its shared element: sideways text has no bounds worth
 * morphing, and the cover is the anchor Now Playing rises from and settles to.
 * [enabled] as on [NowPlayingPill].
 */
@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
fun NowPlayingPillVertical(
    currentTrackId: String?,
    currentTrackTitle: String?,
    currentTrackArtist: String?,
    currentTrackCoverArtUrl: String?,
    connectionErrorMessage: String?,
    playbackProgress: Float,
    isPlaying: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    sharedTransitionScope: SharedTransitionScope? = null,
    animatedVisibilityScope: AnimatedVisibilityScope? = null,
    enabled: Boolean = true,
) {
    val haptics = rememberYoinHaptics()
    val trackPresence by animateFloatAsState(
        targetValue = if (currentTrackTitle != null) 1f else 0f,
        animationSpec = YoinMotion.defaultEffectsSpec(),
        label = "verticalPillTrackPresence",
    )
    val colors = MaterialTheme.colorScheme
    val containerColor = lerp(colors.surfaceContainerHighest, colors.primaryContainer, trackPresence)
    val contentColor = lerp(colors.onSurfaceVariant, colors.onPrimaryContainer, trackPresence)
    val progressFillColor = colors.primary.copy(alpha = 0.25f)
    val wave = rememberPlaybackWave(isPlaying)
    val waveHold = rememberCoveredWaveHold(wave)
    val clampedProgress = playbackProgress.coerceIn(0f, 1f)
    val shape = RoundedCornerShape(VerticalPillCornerRadius)

    // Track-change push, upright: the old song leaves upward, the next rides
    // in from below — the same held-copy scheme as the horizontal pill.
    val push = remember { Animatable(0f) }
    val pushInSpec = YoinMotion.defaultSpatialSpec<Float>(role = YoinMotionRole.Expressive)
    var shown by remember {
        mutableStateOf(
            PillTrack(currentTrackId, currentTrackTitle, currentTrackArtist, currentTrackCoverArtUrl),
        )
    }
    LaunchedEffect(currentTrackId, currentTrackTitle, currentTrackArtist, currentTrackCoverArtUrl) {
        val next =
            PillTrack(currentTrackId, currentTrackTitle, currentTrackArtist, currentTrackCoverArtUrl)
        val previous = shown
        if (previous.id != null && next.id != null && previous.id != next.id) {
            if (push.value != 0f) push.snapTo(0f)
            push.animateTo(1f, tween(durationMillis = 90, easing = LinearEasing))
            shown = next
            push.snapTo(-1f)
            push.animateTo(0f, pushInSpec)
        } else {
            shown = next
        }
    }

    FilledTonalButton(
        onClick = {
            haptics.performContextClick()
            onClick()
        },
        modifier = modifier,
        enabled = enabled,
        shape = shape,
        contentPadding = PaddingValues(0.dp),
        colors = pillButtonColors(containerColor, contentColor),
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            if (currentTrackTitle != null && clampedProgress > 0f) {
                Box(
                    modifier = Modifier
                        .matchParentSize()
                        .clip(shape)
                        .graphicsLayer()
                        .drawWithContent {
                            drawContent()
                            val width = size.width
                            val height = size.height
                            val progressY = height * (1f - clampedProgress)
                            val phase = waveHold.phase()
                            val amplitude = 4.dp.toPx() * waveHold.amplitude()
                            val waveSteps = 12
                            val path = Path().apply {
                                moveTo(0f, height)
                                for (index in 0..waveSteps) {
                                    val fraction = index.toFloat() / waveSteps
                                    val dy = sin(
                                        phase + fraction * 2f * Math.PI.toFloat(),
                                    ) * amplitude
                                    lineTo(fraction * width, progressY + dy)
                                }
                                lineTo(width, height)
                                close()
                            }
                            drawPath(path, progressFillColor)
                        },
                )
            }

            val pushModifier = Modifier.graphicsLayer {
                val p = push.value
                translationY = -PillPushTravel.toPx() * p
                alpha = 1f - 0.9f * kotlin.math.abs(p)
            }
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(top = 10.dp, bottom = VerticalPillTextBottom)
                    .then(pushModifier),
                contentAlignment = Alignment.BottomCenter,
            ) {
                Column(modifier = Modifier.rotateBottomToTop()) {
                    Text(
                        text = shown.title ?: when {
                            connectionErrorMessage != null -> "Playback unavailable"
                            else -> "Nothing playing"
                        },
                        style = MaterialTheme.typography.labelLarge.copy(
                            fontSize = 13.sp,
                            lineHeight = 17.sp,
                            fontWeight = FontWeight.SemiBold,
                        ),
                        color = contentColor,
                        maxLines = 1,
                        softWrap = false,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = shown.artist ?: connectionErrorMessage.orEmpty(),
                        style = MaterialTheme.typography.labelSmall.copy(
                            fontSize = 11.sp,
                            lineHeight = 15.sp,
                        ),
                        color = contentColor.copy(alpha = 0.8f),
                        maxLines = 1,
                        softWrap = false,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            NowPlayingPillArtwork(
                currentTrackId = shown.id,
                currentTrackCoverArtUrl = shown.cover,
                currentTrackTitle = shown.title,
                sharedTransitionScope = sharedTransitionScope,
                animatedVisibilityScope = animatedVisibilityScope,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 9.dp)
                    .then(pushModifier),
            )
        }
    }
}

/** Both pills' colours, the same when disabled: a disabled pill is only fading out. */
@Composable
private fun pillButtonColors(container: Color, content: Color): ButtonColors =
    ButtonDefaults.filledTonalButtonColors(
        containerColor = container,
        contentColor = content,
        disabledContainerColor = container,
        disabledContentColor = content,
    )

/**
 * Lays its content out sideways, reading bottom-to-top: measured against the
 * parent's HEIGHT, placed rotated −90° about its centre.
 */
private fun Modifier.rotateBottomToTop(): Modifier = layout { measurable, constraints ->
    val placeable = measurable.measure(
        Constraints(
            minWidth = 0,
            maxWidth = constraints.maxHeight,
            minHeight = 0,
            maxHeight = constraints.maxWidth,
        ),
    )
    layout(placeable.height, placeable.width) {
        placeable.placeWithLayer(
            x = (placeable.height - placeable.width) / 2,
            y = (placeable.width - placeable.height) / 2,
        ) {
            rotationZ = -90f
        }
    }
}

private val VerticalPillCornerRadius = 26.dp

/** Text stops above the cover: 9 inset + 34 cover + 10 air. */
private val VerticalPillTextBottom = 53.dp

@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
private fun NowPlayingPillArtwork(
    currentTrackId: String?,
    currentTrackCoverArtUrl: String?,
    currentTrackTitle: String?,
    sharedTransitionScope: SharedTransitionScope? = null,
    animatedVisibilityScope: AnimatedVisibilityScope? = null,
    modifier: Modifier = Modifier,
) {
    val baseModifier = modifier.size(34.dp)
    val sharedBoundsSpec = YoinMotion.defaultSpatialSpec<Rect>(
        role = YoinMotionRole.Standard,
        expressiveScheme = MaterialTheme.motionScheme,
    )
    val finalModifier = if (
        sharedTransitionScope != null &&
        animatedVisibilityScope != null &&
        currentTrackCoverArtUrl != null
    ) {
        val sharedContentConfig =
            rememberActiveOnlySharedContentConfig(animatedVisibilityScope = animatedVisibilityScope)
        with(sharedTransitionScope) {
            baseModifier.sharedBounds(
                sharedContentState = rememberSharedContentState(
                    key = nowPlayingCoverSharedKey(currentTrackId),
                    config = sharedContentConfig,
                ),
                animatedVisibilityScope = animatedVisibilityScope,
                boundsTransform = { _, _ -> sharedBoundsSpec },
            )
        }
    } else {
        baseModifier
    }

    // Fixed request size: Coil then answers a memory hit synchronously, so a
    // detail window's twin pill (same URL, same size → same cache key) paints
    // its cover on the first frame of the hand-off instead of blanking until a
    // layout-sized load returns. Larger than the 34dp slot because the pill
    // art is scaled up by the Now Playing shared-bounds rise.
    val requestSizePx = with(LocalDensity.current) { PillArtworkRequestSize.roundToPx() }
    ExpressiveMediaArtwork(
        model = currentTrackCoverArtUrl,
        contentDescription = currentTrackTitle ?: "Current track",
        modifier = finalModifier,
        shape = YoinArtworkShapes.ThumbAnimated,
        fallbackIcon = YoinSymbols.MusicNote,
        tonalElevation = 1.dp,
        shadowElevation = 0.dp,
        requestSizePx = requestSizePx,
    )
}

/** The pill's held copy of the track props — swapped mid-push. */
private data class PillTrack(
    val id: String?,
    val title: String?,
    val artist: String?,
    val cover: String?,
)

/** Decode size of the pill cover; shared by the shell and detail twins. */
private val PillArtworkRequestSize = 96.dp

/** Horizontal travel of the track-change push. */
private val PillPushTravel = 30.dp

@androidx.compose.ui.tooling.preview.Preview(name = "Upright pill", widthDp = 64, heightDp = 160, showBackground = true)
@Composable
private fun NowPlayingPillVerticalPreview() {
    com.gpo.yoin.ui.theme.YoinTheme {
        NowPlayingPillVertical(
            currentTrackId = "1",
            currentTrackTitle = "RUNNING TO YOU",
            currentTrackArtist = "Blusher",
            currentTrackCoverArtUrl = null,
            connectionErrorMessage = null,
            playbackProgress = 0.4f,
            isPlaying = true,
            onClick = {},
            modifier = Modifier
                .padding(6.dp)
                .size(width = 52.dp, height = 142.dp),
        )
    }
}
