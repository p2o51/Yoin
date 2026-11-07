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
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.unit.Velocity
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
