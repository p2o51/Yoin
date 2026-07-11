package com.gpo.yoin.ui.detail

import android.content.Context
import android.content.Intent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathMeasure
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.unit.dp
import com.gpo.yoin.AppContainer
import com.gpo.yoin.MainActivity
import com.gpo.yoin.ui.component.ExpressiveMediaArtwork
import com.gpo.yoin.ui.component.noRippleClickable
import com.gpo.yoin.ui.component.rememberExpressiveBackdropColors
import com.gpo.yoin.ui.experience.rememberYoinHaptics
import com.gpo.yoin.ui.theme.YoinMotion
import com.gpo.yoin.ui.theme.YoinMotionRole
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/**
 * Mini now-playing dock for the detail Activities — the shell's Now Playing
 * overlay can't reach these standalone Activities, so browsing an album /
 * artist / playlist while music played used to mean flying blind.
 *
 * Shape (user-specified, round 3): ONLY the square cover, the track progress
 * traced as a stroke around its rounded-square perimeter, docked to the
 * RIGHT of the floating share/play toolbar on the same row — not a bar of
 * its own below it. Tap = open the shell with Now Playing expanded; title
 * and transport controls live there, one tap away.
 */
data class DetailMiniPlayerState(
    val title: String,
    val artist: String,
    val coverArtUrl: String?,
    val isPlaying: Boolean,
)

/**
 * Narrow projection of the playback state for the mini player. Deliberately
 * NOT the raw [com.gpo.yoin.player.PlaybackState]: that carries per-tick
 * position fields, and collecting it directly would recompose the dock every
 * playback tick (the project's NP-dedup invariant). distinctUntilChanged on
 * this tiny snapshot means it recomposes only on track / play changes.
 */
@Composable
fun rememberDetailMiniPlayerState(container: AppContainer): State<DetailMiniPlayerState?> =
    remember(container) {
        container.playbackManager.playbackState
            .map { state ->
                val track = state.currentTrack ?: state.pendingTrack
                track?.let {
                    DetailMiniPlayerState(
                        title = it.title.orEmpty(),
                        artist = it.artist.orEmpty(),
                        coverArtUrl = container.repository.resolveCoverUrl(it.coverArt, size = 240),
                        isPlaying = state.isPlaying,
                    )
                }
            }
            .distinctUntilChanged()
    }.collectAsState(initial = null)

/**
 * Track progress fraction for the cover's perimeter stroke. Quantized so the
 * 250ms position ticker only emits when the ring would visibly move (~1px),
 * and meant to be READ ONLY inside a draw lambda — same deferred-read model
 * as NP's `positionMs: () -> Long` — so ticks redraw the ring without
 * recomposing anything.
 */
@Composable
fun rememberDetailMiniPlayerProgress(container: AppContainer): State<Float> =
    remember(container) {
        container.playbackManager.playbackState
            .map { state ->
                val duration = state.duration
                if (duration <= 0L) 0f
                else ((state.position.toFloat() / duration) * 480f).toInt() / 480f
            }
            .distinctUntilChanged()
    }.collectAsState(initial = 0f)

/**
 * Return to the shell Activity, optionally with Now Playing expanded. The
 * session store is process-global, so setting the flag BEFORE the intent
 * means the shell resumes already showing NP — no extras round-trip.
 * CLEAR_TOP + SINGLE_TOP folds the detail stack back into the existing
 * shell instance instead of spawning a second one.
 */
fun launchShellFromDetail(
    context: Context,
    container: AppContainer,
    expandNowPlaying: Boolean,
) {
    if (expandNowPlaying) {
        container.experienceSessionStore.setNowPlayingExpanded(true)
    }
    context.startActivity(
        Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),
    )
}

// Same height as the M3 HorizontalFloatingToolbar it sits beside, so the
// two read as peers on one row.
private val DockSize = 64.dp
private val RingStroke = 3.dp
private val DockCorner = 18.dp
private val ArtSize = 50.dp
private val ArtCorner = 12.dp

@Composable
fun DetailMiniPlayer(
    state: DetailMiniPlayerState?,
    progress: () -> Float,
    onOpenNowPlaying: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // Keep the last non-null state so the exit slide animates with content.
    var lastState by remember { mutableStateOf(state) }
    if (state != null) lastState = state
    AnimatedVisibility(
        visible = state != null,
        enter = YoinMotion.slideInVertically(role = YoinMotionRole.Expressive) { it } +
            YoinMotion.fadeIn(role = YoinMotionRole.Expressive),
        exit = YoinMotion.slideOutVertically(role = YoinMotionRole.Expressive) { it } +
            YoinMotion.fadeOut(role = YoinMotionRole.Expressive),
        modifier = modifier,
    ) {
        lastState?.let { current ->
            DetailMiniPlayerDock(
                state = current,
                progress = progress,
                onOpenNowPlaying = onOpenNowPlaying,
            )
        }
    }
}

@Composable
private fun DetailMiniPlayerDock(
    state: DetailMiniPlayerState,
    progress: () -> Float,
    onOpenNowPlaying: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val haptics = rememberYoinHaptics()
    val backdrop = rememberExpressiveBackdropColors(
        model = state.coverArtUrl,
        fallbackBaseColor = MaterialTheme.colorScheme.secondaryContainer,
        fallbackAccentColor = MaterialTheme.colorScheme.tertiaryContainer,
    )
    val ringTrackColor = lerp(
        MaterialTheme.colorScheme.surfaceContainerHigh,
        backdrop.baseColor,
        0.4f,
    )
    // Pale extracted accents disappear as a 3dp stroke — fall back to the
    // theme primary until the palette resolves something with presence.
    val ringColor = if (backdrop.isResolvedFromPalette) {
        lerp(backdrop.accentColor, MaterialTheme.colorScheme.onSurface, 0.25f)
    } else {
        MaterialTheme.colorScheme.primary
    }
    val interaction = remember { MutableInteractionSource() }

    Box(
        modifier = modifier
            .size(DockSize)
            .coverProgressRing(
                progress = progress,
                trackColor = ringTrackColor,
                ringColor = ringColor,
            )
            .noRippleClickable(interactionSource = interaction) {
                haptics.performContextClick()
                onOpenNowPlaying()
            },
        contentAlignment = Alignment.Center,
    ) {
        ExpressiveMediaArtwork(
            model = state.coverArtUrl,
            contentDescription = state.title,
            modifier = Modifier.size(ArtSize),
            shape = RoundedCornerShape(ArtCorner),
            fallbackIcon = Icons.Rounded.MusicNote,
            tonalElevation = 0.dp,
            shadowElevation = 0.dp,
        )
    }
}

/**
 * Traces track progress as a stroke around a rounded-square perimeter,
 * clockwise from top-center. The path is hand-built (not addRoundRect) so
 * the start point is deterministic. [progress] is only read inside the draw
 * lambda — position ticks invalidate the draw phase, never composition.
 */
private fun Modifier.coverProgressRing(
    progress: () -> Float,
    trackColor: Color,
    ringColor: Color,
): Modifier = drawWithCache {
    val strokePx = RingStroke.toPx()
    val inset = strokePx / 2f
    val radius = (DockCorner.toPx() - inset).coerceAtLeast(1f)
    val w = size.width
    val h = size.height
    val left = inset
    val top = inset
    val right = w - inset
    val bottom = h - inset
    val path = Path().apply {
        moveTo(w / 2f, top)
        lineTo(right - radius, top)
        arcTo(Rect(right - 2 * radius, top, right, top + 2 * radius), -90f, 90f, false)
        lineTo(right, bottom - radius)
        arcTo(Rect(right - 2 * radius, bottom - 2 * radius, right, bottom), 0f, 90f, false)
        lineTo(left + radius, bottom)
        arcTo(Rect(left, bottom - 2 * radius, left + 2 * radius, bottom), 90f, 90f, false)
        lineTo(left, top + radius)
        arcTo(Rect(left, top, left + 2 * radius, top + 2 * radius), 180f, 90f, false)
        lineTo(w / 2f, top)
    }
    val measure = PathMeasure().apply { setPath(path, false) }
    val totalLength = measure.length
    val trackStyle = Stroke(width = strokePx, cap = StrokeCap.Round)
    onDrawBehind {
        drawPath(path, trackColor, style = trackStyle)
        val fraction = progress().coerceIn(0f, 1f)
        if (fraction > 0.001f) {
            val segment = Path()
            measure.getSegment(0f, totalLength * fraction, segment, true)
            drawPath(segment, ringColor, style = trackStyle)
        }
    }
}
