package com.gpo.yoin.ui.navigation

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.gpo.yoin.ui.component.ExpressiveMediaArtwork
import com.gpo.yoin.ui.experience.rememberYoinHaptics
import com.gpo.yoin.ui.theme.ProvideYoinMotionRole
import com.gpo.yoin.ui.theme.YoinMotion
import com.gpo.yoin.ui.theme.YoinMotionRole

/**
 * Hand-rolled navigation rail — the Medium+ full-window shell's vertical
 * sibling of [com.gpo.yoin.ui.component.YoinButtonGroup]. It reuses the bar's
 * DECISIONS, never its geometry (the bar is a continuous-axis width-lerp Row
 * and inherits nothing vertically):
 *
 *  - Colors: selected pill = primaryContainer/onPrimaryContainer, unselected =
 *    surfaceContainerHighest/onSurfaceVariant, animated on the effects spring.
 *  - Haptics: destinations click like the bar's nav buttons (performClick);
 *    the playback slot clicks like the pill (performContextClick).
 *  - Idle: nothing playing and no connection error → NO playback slot (the
 *    bar's idle ruling that retired the "Nothing playing" entry); a
 *    connection error keeps the surface alive as an error badge.
 *
 * The playback slot is bottom-anchored: cover thumb inside a circular
 * progress ring; a track change answers with a graphicsLayer scale PULSE
 * (owner ruling: pulse, NOT the pill's horizontal push). Tap opens Now
 * Playing.
 *
 * Hand-rolled on plain Column/Box on purpose — no M3 NavigationRail /
 * NavigationSuiteScaffold containers: the shell lives inside a
 * SharedTransitionLayout whose lookahead pass crashes exotic multi-child
 * measure policies on foldables (see YoinButtonGroup's class KDoc).
 *
 * Pure-parameter surface: no ViewModel coupling in here — the shell feeds it
 * from the narrow playback projections it already collects.
 */
@Composable
fun YoinNavRail(
    selectedSection: YoinSection,
    onSelectHome: () -> Unit,
    onSelectLibrary: () -> Unit,
    playbackTrackId: String?,
    playbackCoverUrl: String?,
    playbackProgress: Float,
    isPlaying: Boolean,
    connectionErrorMessage: String?,
    onOpenNowPlaying: () -> Unit,
    modifier: Modifier = Modifier,
) {
    ProvideYoinMotionRole(role = YoinMotionRole.Standard) {
        Column(
            modifier = modifier
                .width(YoinNavRailWidth)
                .fillMaxHeight()
                .windowInsetsPadding(
                    WindowInsets.systemBars.only(WindowInsetsSides.Vertical),
                )
                .padding(vertical = RailRhythm),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(RailRhythm),
        ) {
            RailDestination(
                selected = selectedSection == YoinSection.HOME,
                icon = Icons.Filled.Home,
                label = "Home",
                onClick = onSelectHome,
            )
            RailDestination(
                selected = selectedSection == YoinSection.LIBRARY,
                icon = Icons.Filled.LibraryMusic,
                label = "Library",
                onClick = onSelectLibrary,
            )
            Spacer(modifier = Modifier.weight(1f))
            when {
                // The bar's idle predicate, verbatim: an error keeps the
                // playback surface reachable even with no track loaded.
                connectionErrorMessage != null -> RailPlaybackErrorBadge(
                    message = connectionErrorMessage,
                    onClick = onOpenNowPlaying,
                )

                playbackTrackId != null -> RailPlaybackSlot(
                    trackId = playbackTrackId,
                    coverUrl = playbackCoverUrl,
                    progress = playbackProgress,
                    isPlaying = isPlaying,
                    onClick = onOpenNowPlaying,
                )

                // Idle: slot not rendered — same retirement as the bar's
                // labeled two-half pose.
            }
        }
    }
}

@Composable
private fun RailDestination(
    selected: Boolean,
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
) {
    val haptics = rememberYoinHaptics()
    val containerColor by animateColorAsState(
        targetValue = if (selected) {
            MaterialTheme.colorScheme.primaryContainer
        } else {
            MaterialTheme.colorScheme.surfaceContainerHighest
        },
        animationSpec = YoinMotion.defaultEffectsSpec(),
        label = "navRailDestinationContainer",
    )
    val contentColor by animateColorAsState(
        targetValue = if (selected) {
            MaterialTheme.colorScheme.onPrimaryContainer
        } else {
            MaterialTheme.colorScheme.onSurfaceVariant
        },
        animationSpec = YoinMotion.defaultEffectsSpec(),
        label = "navRailDestinationContent",
    )
    Column(
        modifier = Modifier.clickable(
            interactionSource = remember { MutableInteractionSource() },
            indication = null,
        ) {
            haptics.performClick()
            onClick()
        },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(RailLabelSpacing),
    ) {
        Surface(
            shape = MaterialTheme.shapes.extraLarge,
            color = containerColor,
            contentColor = contentColor,
        ) {
            Box(
                modifier = Modifier
                    .width(RailIndicatorWidth)
                    .height(RailIndicatorHeight),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = icon,
                    // The visible label below carries the semantics.
                    contentDescription = null,
                    modifier = Modifier.size(RailIconSize),
                )
            }
        }
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = contentColor,
            maxLines = 1,
            softWrap = false,
        )
    }
}

@Composable
private fun RailPlaybackSlot(
    trackId: String,
    coverUrl: String?,
    progress: Float,
    isPlaying: Boolean,
    onClick: () -> Unit,
) {
    val haptics = rememberYoinHaptics()
    val ringColor = MaterialTheme.colorScheme.primary
    // Paused = dimmed ring: the rail's cousin of the pill's wave losing its
    // amplitude while playback is paused.
    val ringAlpha by animateFloatAsState(
        targetValue = if (isPlaying) 1f else RailRingPausedAlpha,
        animationSpec = YoinMotion.defaultEffectsSpec(),
        label = "navRailRingAlpha",
    )
    // Track-change PULSE (owner ruling: pulse, not the pill's horizontal
    // push): scale 1 → 1.06 → 1 on spatial springs, keyed on the track id.
    // First-track appearance doesn't pulse — the slot materializing is
    // already the entrance (the same skip the pill's push makes).
    val pulse = remember { Animatable(1f) }
    val pulseUpSpec = YoinMotion.fastSpatialSpec<Float>()
    val pulseSettleSpec = YoinMotion.defaultSpatialSpec<Float>()
    var shownTrackId by remember { mutableStateOf(trackId) }
    LaunchedEffect(trackId) {
        val previous = shownTrackId
        shownTrackId = trackId
        if (previous != trackId) {
            // A restart mid-pulse (rapid skips) re-arms from rest so two
            // pulses never compound.
            if (pulse.value != 1f) pulse.snapTo(1f)
            pulse.animateTo(RailPulseScale, pulseUpSpec)
            pulse.animateTo(1f, pulseSettleSpec)
        }
    }
    val clampedProgress = progress.coerceIn(0f, 1f)
    Box(
        modifier = Modifier
            .size(RailPlaybackSlotSize)
            .graphicsLayer {
                scaleX = pulse.value
                scaleY = pulse.value
            }
            .drawBehind {
                val stroke = RailRingStroke.toPx()
                val inset = stroke / 2f
                drawArc(
                    color = ringColor,
                    startAngle = -90f,
                    sweepAngle = 360f * clampedProgress,
                    useCenter = false,
                    topLeft = Offset(inset, inset),
                    size = Size(size.width - stroke, size.height - stroke),
                    alpha = ringAlpha,
                    style = Stroke(width = stroke, cap = StrokeCap.Round),
                )
            }
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
            ) {
                haptics.performContextClick()
                onClick()
            },
        contentAlignment = Alignment.Center,
    ) {
        ExpressiveMediaArtwork(
            model = coverUrl,
            contentDescription = "Now playing",
            modifier = Modifier.size(RailPlaybackCoverSize),
            // CircleShape, not YoinArtworkShapes.Cover: a 4dp-rounded square
            // cannot sit inside a circular ring at this size — its corners
            // cross the stroke. Progress-ring circles are the sanctioned
            // exception to the naked-cover doctrine (dock-ring precedent).
            shape = CircleShape,
            fallbackIcon = Icons.Filled.MusicNote,
            tonalElevation = 1.dp,
            shadowElevation = 0.dp,
        )
    }
}

@Composable
private fun RailPlaybackErrorBadge(
    message: String,
    onClick: () -> Unit,
) {
    val haptics = rememberYoinHaptics()
    Surface(
        onClick = {
            haptics.performContextClick()
            onClick()
        },
        modifier = Modifier.size(RailPlaybackSlotSize),
        shape = CircleShape,
        color = MaterialTheme.colorScheme.errorContainer,
        contentColor = MaterialTheme.colorScheme.onErrorContainer,
    ) {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Icons.Filled.ErrorOutline,
                contentDescription = message,
                modifier = Modifier.size(RailIconSize),
            )
        }
    }
}

/** Total rail width — the shell content shifts right by exactly this. */
val YoinNavRailWidth = 80.dp

private val RailRhythm = 12.dp
private val RailIndicatorWidth = 56.dp
private val RailIndicatorHeight = 32.dp
private val RailIconSize = 24.dp
private val RailLabelSpacing = 4.dp
private val RailPlaybackSlotSize = 56.dp
private val RailPlaybackCoverSize = 46.dp
private val RailRingStroke = 3.dp
private const val RailPulseScale = 1.06f
private const val RailRingPausedAlpha = 0.45f
