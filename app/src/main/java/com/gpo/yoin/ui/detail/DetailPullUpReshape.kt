package com.gpo.yoin.ui.detail

import androidx.compose.foundation.gestures.DraggableState
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableFloatState
import androidx.compose.runtime.Stable
import androidx.compose.runtime.State
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import com.gpo.yoin.ui.experience.RevealState
import kotlinx.coroutines.CoroutineScope

/*
 * The hero ⇄ track-list pull-up shared by the Album and Playlist pages.
 *
 * One [RevealState] per page: fraction 1 = hero, 0 = track list. A durable
 * `expanded` bool is the source of truth and [DetailPullUpReconcile] is its
 * ONLY settle driver (cf. the Now Playing "one settle owner" rule). Gestures
 * do exactly two things: `dragBy` live, and on release commit the bool AND
 * `launchAnimateTo` the chosen endpoint — `launchAnimateTo` is settleJob-
 * tracked, so a fresh drag cancels it and no concurrent animator can race the
 * reconcile effect. Never call `settle()` / `animateTo()` here.
 *
 * Back is not involved: the pulled-up list is in-page state, not a back stop
 * (one system back leaves the page).
 */

/** The drag travel as a fraction of the page height (≈1:1 finger feel over the upper region). */
internal const val DetailPullUpTravelFraction = 0.55f

// Velocity-or-position settle decision, mirroring RevealState.chooseTarget but
// WITHOUT animating (the reconcile effect owns the animation). Returns true =
// expanded (track list). rawVelocity is the finger velocity in px/s; up
// (negative) expands.
internal fun chooseExpandedTarget(fraction: Float, rawVelocity: Float, travelPx: Float): Boolean {
    val velocityFraction = if (travelPx > 0f) rawVelocity / travelPx else 0f
    val target = when {
        velocityFraction <= -1.6f -> 0f
        velocityFraction >= 1.6f -> 1f
        fraction < 0.5f -> 0f
        else -> 1f
    }
    return target <= 0f
}

/** Drives the reveal fraction to match [expanded]; re-asserts after a cancelled gesture or a restore. */
@Composable
internal fun DetailPullUpReconcile(revealState: RevealState, expanded: Boolean) {
    val scope = rememberCoroutineScope()
    LaunchedEffect(expanded) {
        revealState.launchAnimateTo(scope, if (expanded) 0f else 1f)
    }
}

/**
 * Gesture wiring for one pull-up page. Hero → list: [heroDrag] on the hero
 * layer (off once expanded, so the list owns its own scroll). List → hero:
 * [listConnection] on the track list, which collapses on pull-down at the top.
 */
@Stable
internal class DetailPullUpGestures(
    private val revealState: RevealState,
    private val dragState: DraggableState,
    private val travelPx: MutableFloatState,
    private val scope: CoroutineScope,
    private val onExpandedCommit: State<(Boolean) -> Unit>,
    val listConnection: NestedScrollConnection,
) {
    fun heroDrag(enabled: Boolean): Modifier = if (enabled) {
        Modifier.draggable(
            state = dragState,
            orientation = Orientation.Vertical,
            onDragStopped = { velocity -> commit(velocity) },
        )
    } else {
        Modifier
    }

    /**
     * Commit a release: the bool AND a tracked settle to the chosen endpoint,
     * even when the committed mode is unchanged (a partial pull that snaps
     * back) — the reconcile effect only fires on a CHANGE.
     */
    fun commit(velocity: Float) {
        val target = chooseExpandedTarget(revealState.fraction, velocity, travelPx.floatValue)
        onExpandedCommit.value(target)
        revealState.launchAnimateTo(scope, if (target) 0f else 1f)
    }
}

@Composable
internal fun rememberDetailPullUpGestures(
    revealState: RevealState,
    listState: LazyListState,
    travelPx: MutableFloatState,
    onExpandedCommit: (Boolean) -> Unit,
): DetailPullUpGestures {
    val scope = rememberCoroutineScope()
    val latestCommit = rememberUpdatedState(onExpandedCommit)
    val dragState = rememberDraggableState { delta ->
        revealState.dragBy(-delta, travelPx.floatValue)
    }
    return remember(revealState, listState, scope, dragState, travelPx) {
        lateinit var gestures: DetailPullUpGestures
        val connection = object : NestedScrollConnection {
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                if (source != NestedScrollSource.UserInput) return Offset.Zero
                val atTop = listState.firstVisibleItemIndex == 0 &&
                    listState.firstVisibleItemScrollOffset == 0
                val pullingDownAtTop = available.y > 0f && atTop && revealState.fraction < 1f
                val pullingUpStillCollapsing = available.y < 0f && revealState.fraction > 0f
                if (pullingDownAtTop || pullingUpStillCollapsing) {
                    revealState.dragBy(-available.y, travelPx.floatValue)
                    return Offset(0f, available.y)
                }
                return Offset.Zero
            }

            override suspend fun onPreFling(available: Velocity): Velocity {
                if (revealState.fraction <= 0f || revealState.fraction >= 1f) {
                    return Velocity.Zero
                }
                gestures.commit(available.y)
                return available
            }
        }
        DetailPullUpGestures(
            revealState = revealState,
            dragState = dragState,
            travelPx = travelPx,
            scope = scope,
            onExpandedCommit = latestCommit,
            listConnection = connection,
        ).also { gestures = it }
    }
}

// ---- The hero ⇄ track list handoff --------------------------------------------
//
// One shared vertical axis (owner 2026-11-07): as the pull-up runs, the hero's
// dense flowing titles drift up a short way and are gone just past half way, and the
// track list comes up the same short way into its rows behind them. Before, the
// two crossfaded over the whole drag nearly in place, so the flowing titles sat
// on top of the rows. Pulling down plays the same thing backwards.

/** How far each side travels in the handoff. Small on purpose: a nudge, not a slide. */
internal val PullUpHandoffShift = 28.dp

/** The hero's opacity at [expand] (0 = hero, 1 = list): gone just past half way. */
internal fun pullUpHeroAlpha(expand: Float): Float = 1f - smoothStep(0f, 0.55f, expand)

/** The hero's rise at [expand], as a fraction of [PullUpHandoffShift] (0 → 1, upwards). */
internal fun pullUpHeroRise(expand: Float): Float = smoothStep(0f, 0.6f, expand)

/**
 * The list's opacity at [expand]: comes in as the hero thins out, overlapping it
 * faintly (each under ~15%, and 28dp apart) so the middle never reads as empty.
 */
internal fun pullUpListAlpha(expand: Float): Float = smoothStep(0.3f, 0.8f, expand)

/** How far below its rows the list still sits at [expand], as a fraction of [PullUpHandoffShift]. */
internal fun pullUpListDrop(expand: Float): Float = 1f - smoothStep(0.25f, 1f, expand)

/** The hero side of the handoff. [alpha] multiplies in the page's own fades. Draw only. */
internal fun Modifier.pullUpHeroLayer(expand: () -> Float, alpha: () -> Float = { 1f }): Modifier = graphicsLayer {
    val e = expand().coerceIn(0f, 1f)
    this.alpha = pullUpHeroAlpha(e) * alpha()
    translationY = -PullUpHandoffShift.toPx() * pullUpHeroRise(e)
}

/** The track list side of the handoff. Draw only. */
internal fun Modifier.pullUpListLayer(expand: () -> Float): Modifier = graphicsLayer {
    val e = expand().coerceIn(0f, 1f)
    alpha = pullUpListAlpha(e)
    translationY = PullUpHandoffShift.toPx() * pullUpListDrop(e)
}
