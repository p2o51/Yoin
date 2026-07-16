package com.gpo.yoin.ui.detail

import android.app.ActivityOptions
import android.content.Context
import android.content.Intent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
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
import com.gpo.yoin.R
import com.gpo.yoin.player.PlaybackState
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
 * Shape (user-specified): ONLY the square cover, the track progress traced
 * as a stroke around its rounded-square perimeter. It sits to the right of
 * the floating share/play toolbar inside ONE centered row (DetailToolbarRow)
 * and matches the toolbar pill's height exactly. Tap = open the shell with
 * Now Playing expanded (animated on arrival — see [launchShellFromDetail]);
 * title and transport controls live there, one tap away.
 */
data class DetailMiniPlayerState(
    val title: String,
    val artist: String,
    val coverArtUrl: String?,
    val isPlaying: Boolean,
)

/**
 * Narrow projection of the playback state for the mini player. Deliberately
 * NOT the raw [PlaybackState]: that carries per-tick position fields, and
 * collecting it directly would recompose the dock every playback tick (the
 * project's NP-dedup invariant). distinctUntilChanged on this tiny snapshot
 * means it recomposes only on track / play changes.
 *
 * Seeded synchronously from the StateFlow's CURRENT value (same pattern as
 * the shell's playbackProgress): with `initial = null` the first composition
 * is guaranteed dock-less, which both replays the pop-in the seeding below is
 * meant to kill and hands the entrance morph a still-expanding target.
 */
@Composable
fun rememberDetailMiniPlayerState(container: AppContainer): State<DetailMiniPlayerState?> {
    val seed = remember(container) {
        container.playbackManager.playbackState.value.toDetailMiniPlayerState(container)
    }
    return remember(container) {
        container.playbackManager.playbackState
            .map { state -> state.toDetailMiniPlayerState(container) }
            .distinctUntilChanged()
    }.collectAsState(initial = seed)
}

private fun PlaybackState.toDetailMiniPlayerState(
    container: AppContainer,
): DetailMiniPlayerState? {
    val track = currentTrack ?: pendingTrack ?: return null
    return DetailMiniPlayerState(
        title = track.title.orEmpty(),
        artist = track.artist.orEmpty(),
        coverArtUrl = container.repository.resolveCoverUrl(track.coverArt, size = 240),
        isPlaying = isPlaying,
    )
}

/**
 * Track progress fraction for the cover's perimeter stroke. Quantized so the
 * 250ms position ticker only emits when the ring would visibly move (~1px),
 * and meant to be READ ONLY inside a draw lambda — same deferred-read model
 * as NP's `positionMs: () -> Long` — so ticks redraw the ring without
 * recomposing anything.
 */
@Composable
fun rememberDetailMiniPlayerProgress(container: AppContainer): State<Float> {
    // Seeded from the live state: a 0% first frame reads as a ring blip.
    val seed = remember(container) {
        container.playbackManager.playbackState.value.toQuantizedProgress()
    }
    return remember(container) {
        container.playbackManager.playbackState
            .map { state -> state.toQuantizedProgress() }
            .distinctUntilChanged()
    }.collectAsState(initial = seed)
}

private fun PlaybackState.toQuantizedProgress(): Float =
    if (duration <= 0L) 0f
    else ((position.toFloat() / duration) * 480f).toInt() / 480f

/**
 * Return to the shell Activity, optionally with Now Playing expanded.
 *
 * The expand request travels as an Intent extra consumed in the shell's
 * onNewIntent — NOT by setting the session-store flag up front. Compose's
 * frame clock is process-wide, so a flag set while the shell is stopped lets
 * the whole NP enter transition play invisibly in the background and the
 * shell resumes at the finished state. Deferring to onNewIntent (which fires
 * just before onStart) means the expansion animates on the shell's first
 * visible frames — the same bar→NP choreography as tapping the bottom group —
 * while this detail window dissolves above it (np_handoff_* animations).
 * CLEAR_TOP + SINGLE_TOP folds the detail stack back into the existing
 * shell instance instead of spawning a second one.
 */
fun launchShellFromDetail(
    context: Context,
    container: AppContainer,
    expandNowPlaying: Boolean,
) {
    val intent = Intent(context, MainActivity::class.java)
        .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
    if (!expandNowPlaying) {
        context.startActivity(intent)
        return
    }
    intent.putExtra(MainActivity.EXTRA_EXPAND_NOW_PLAYING, true)
    val options = ActivityOptions.makeCustomAnimation(
        context,
        R.anim.np_handoff_enter,
        R.anim.np_handoff_exit,
    )
    context.startActivity(intent, options.toBundle())
}

// The dock fills the toolbar row's height (the M3 floating toolbar pill,
// 76dp with the current 60dp play button + 8dp content padding) so the two
// read as equal-height peers; this is only the floor for toolbar-less pages.
private val DockMinSize = 64.dp
private val RingStroke = 3.dp

// Gap to the toolbar pill, applied inside the show/hide animation so it
// vanishes (and animates) together with the dock.
private val DockGap = 12.dp

// Corners scale with the dock so the shape keeps its character at any row
// height (18dp at the original 64dp spec). Shared with the entrance morph
// pill, whose target corner must land exactly on the ring's.
internal const val DetailDockCornerRatio = 18f / 64f

// Artwork rounding as a fraction of the art size; the morph's riding cover
// must land on exactly this rounding for an invisible hand-off.
internal const val DetailDockArtCornerRatio = 0.24f
private const val ArtCornerPercent = 24 // = DetailDockArtCornerRatio × 100

// Inset from dock edge to the artwork: ring stroke + breathing gap. Also the
// morph's cover-flight destination inset.
internal val DetailDockArtInset = 7.dp

@Composable
fun DetailMiniPlayer(
    state: DetailMiniPlayerState?,
    progress: () -> Float,
    onOpenNowPlaying: () -> Unit,
    dockMorph: DetailDockMorphState? = null,
    bloom: DockBloomState? = null,
    modifier: Modifier = Modifier,
) {
    // Keep the last non-null state so the exit shrink animates with content.
    var lastState by remember { mutableStateOf(state) }
    if (state != null) lastState = state
    // Seeded with the state present at first composition (the state flow is
    // synchronously seeded above, so "music already playing" IS visible on
    // frame 1): a page opened mid-playback shows the dock immediately — its
    // entrance is owned by the page transition / dock morph, not by a
    // detached pop-in (the old slide-up read as an unrelated "cut"
    // animation). Expand/shrink only animate MID-visit playback starts/stops,
    // letting the centered toolbar+dock cluster re-center smoothly.
    val visibleState = remember { MutableTransitionState(initialState = state != null) }
    visibleState.targetState = state != null
    AnimatedVisibility(
        visibleState = visibleState,
        enter = YoinMotion.expandHorizontally(role = YoinMotionRole.Expressive) +
            YoinMotion.fadeIn(role = YoinMotionRole.Expressive),
        exit = YoinMotion.shrinkHorizontally(role = YoinMotionRole.Expressive) +
            YoinMotion.fadeOut(role = YoinMotionRole.Expressive),
        modifier = modifier,
    ) {
        lastState?.let { current ->
            // The toolbar↔dock gap lives INSIDE the visibility content: a gap
            // applied by the row (spacedBy / wrapper padding) would survive at
            // zero dock width and push the toolbar 6dp off-center whenever
            // nothing is playing.
            Row {
                Spacer(modifier = Modifier.width(DockGap))
                DetailMiniPlayerDock(
                    state = current,
                    progress = progress,
                    onOpenNowPlaying = onOpenNowPlaying,
                    modifier = Modifier.dockMorphTarget(dockMorph).dockBloomSource(bloom),
                )
            }
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
            // Match the toolbar pill beside it exactly: the toolbar row sizes
            // itself (IntrinsicSize.Min) and the dock fills that height.
            .heightIn(min = DockMinSize)
            .fillMaxHeight()
            .aspectRatio(1f)
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
            // matchParentSize, NOT fillMaxSize: the loaded cover's painter
            // reports the bitmap's intrinsic size, and fillMaxSize lets that
            // flow through aspectRatio into the toolbar row's
            // IntrinsicSize.Min — the dock then balloons to the cover's
            // pixel size (huge on devices whose server returns large art).
            modifier = Modifier
                .matchParentSize()
                .padding(DetailDockArtInset),
            shape = RoundedCornerShape(percent = ArtCornerPercent),
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
    val radius = (size.minDimension * DetailDockCornerRatio - inset).coerceAtLeast(1f)
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
