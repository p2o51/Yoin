package com.gpo.yoin.ui.navigation.back

import androidx.activity.compose.PredictiveBackHandler
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import com.gpo.yoin.ui.experience.DismissRule
import com.gpo.yoin.ui.experience.RevealState
import com.gpo.yoin.ui.memories.showcase.MemoriesDiaryState
import com.gpo.yoin.ui.memories.showcase.MemoryTitleEditor
import com.gpo.yoin.ui.nowplaying.StageBackPreview
import com.gpo.yoin.ui.theme.YoinMotion
import com.gpo.yoin.ui.theme.YoinMotionRole
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/**
 * Memories' back infrastructure (ShellOverlayUp, Pattern C). Two levels, two
 * controllers, one handler — and above them, while a title is being edited,
 * the edit itself:
 *
 * - [MemoriesBackLevel.Card]: back = retreat to Home. The outer q
 *   ([RevealState.fraction]) is scrubbed from wherever it is (q0) toward the
 *   [BackMotionTokens.MemoriesDismissTrigger] pose through
 *   [YoinMotion.backGestureEasing] — the preview's full travel IS the
 *   finger's commit distance. Commit → [onDismiss] (the host's surface
 *   effect springs q to 1); cancel → q springs to 0. Both on RevealState's
 *   settle spring.
 * - [MemoriesBackLevel.Diary]: back = diary → card. The inner p is scrubbed
 *   p0·(1 − ease(progress)) over the full range — no cap, no chase — from
 *   where a running spring was caught. Commit → 0, cancel → 1, on the same
 *   morph spring.
 * - [MemoriesBackLevel.TitleEdit]: a memory's title is being edited (an
 *   in-page mode, [MemoryTitleEditor]); back cancels the edit before anything
 *   else. The preview fades the edit's row by ease(progress) — the title
 *   itself is never scrubbed. Commit → the edit is cancelled (the field closes
 *   on the title it had, the keyboard goes); cancel → the row comes back on
 *   the effects spring (it is an alpha).
 *
 * Three-button / a11y back sends no progress events: the empty flow commits
 * directly (invariant 8). Settles run on an outer scope; the handler's
 * CancellationException is rethrown (invariant 7). Mount it where the shell's
 * Memories BackHandler used to be and gate [enabled] on
 * `shellBackOwner == ShellBackOwner.Memories` — ShellBackResolver's
 * priority (NowPlaying > HomeEdit > DetailPane > Memories) is expressed only
 * by that gate (invariant 9).
 */
enum class MemoriesBackLevel { Card, Diary, TitleEdit }

/**
 * Memories' system back, both levels. [containerHeightPx] is read when a gesture starts.
 * [onCardBackStarted] runs once per card-level gesture, before the first
 * preview frame (the page uses it to key its corner rule to the back driver);
 * [onCardBackFinished] runs once it is over — at the commit, or when the
 * cancel spring has landed (or a finger caught it). Between the two the page
 * holds its award (prototype `busyQ`): the preview parks q under the award's
 * open gate. [diary] may stay null until the diary exists;
 * [MemoriesBackLevel.Diary] without it falls back to the card level, and
 * [MemoriesBackLevel.TitleEdit] without [titleEditor] (or with nothing open)
 * to the diary or card level.
 */
@Composable
fun MemoriesPredictiveBack(
    enabled: Boolean,
    level: MemoriesBackLevel,
    reveal: RevealState,
    containerHeightPx: () -> Float,
    onDismiss: () -> Unit,
    diary: MemoriesDiaryState? = null,
    onCardBackStarted: () -> Unit = {},
    onCardBackFinished: () -> Unit = {},
    titleEditor: MemoryTitleEditor? = null,
    // Diary level's preview: the AOSP pose of the whole page (see below). Null = none drawn.
    diaryPreview: StageBackPreview? = null,
) {
    val scope = rememberCoroutineScope()
    val previewSpec = YoinMotion.defaultSpatialSpec<Float>(role = YoinMotionRole.Standard)
    val triggerPx = with(LocalDensity.current) { BackMotionTokens.MemoriesDismissTrigger.toPx() }
    val currentOnDismiss by rememberUpdatedState(onDismiss)
    val currentOnCardBackStarted by rememberUpdatedState(onCardBackStarted)
    val currentOnCardBackFinished by rememberUpdatedState(onCardBackFinished)
    PredictiveBackHandler(enabled = enabled) { events ->
        // Captured once per gesture: a level never changes under a live back.
        val editor = titleEditor?.takeIf { level == MemoriesBackLevel.TitleEdit && it.isEditing }
        val diaryState = diary?.takeIf { state ->
            level == MemoriesBackLevel.Diary ||
                (level == MemoriesBackLevel.TitleEdit && editor == null && state.isDiaryLevel)
        }
        if (editor != null) {
            try {
                events.collect { event -> editor.previewBack(MemoriesBackMath.titleEditPreview(event.progress)) }
                // Commit (also the button path with no events): the edit is cancelled.
                editor.commitBack()
            } catch (e: CancellationException) {
                editor.cancelBack(scope)
                throw e
            }
        } else if (diaryState != null) {
            // Catch a running open/close spring where it is — never a jump to 1.
            val p0 = diaryState.stop().coerceIn(0f, 1f)
            // The preview is the AOSP pose of the complete page — scale toward 0.9,
            // shift toward the swipe's edge, follow the finger vertically — snapped
            // per event; the diary → card morph is NOT scrubbed (owner 2026-10-09:
            // scrubbing p, the front-loaded ease folded most of the diary away in
            // the first stretch of the swipe, as if back had taken the whole
            // travel). Commit runs the morph on its own spring; both ends spring
            // the pose home. Same model as the Now Playing stage (StageBackPreview).
            var startY = Float.NaN
            try {
                events.collect { event ->
                    if (diaryPreview == null) return@collect
                    if (startY.isNaN()) startY = event.touchY
                    diaryPreview.swipeEdge = event.swipeEdge
                    diaryPreview.touchYDelta = event.touchY - startY
                    diaryPreview.progress.snapTo(MemoriesBackMath.diaryPreview(event.progress))
                }
                diaryState.launchAnimateTo(scope, 0f)
            } catch (e: CancellationException) {
                if (p0 < 1f) diaryState.launchAnimateTo(scope, 1f)
                throw e
            } finally {
                diaryPreview?.let { preview -> scope.launch { preview.progress.animateTo(0f, previewSpec) } }
            }
        } else {
            // snapTo(current) stops any settle in flight (open spring, return
            // spring, a released drag) so the scrub starts from the page on
            // screen; the finger then owns q 1:1 (invariant 2).
            val q0 = reveal.fraction.coerceIn(0f, 1f)
            reveal.snapTo(q0)
            currentOnCardBackStarted()
            val heightPx = containerHeightPx()
            val triggerFraction = if (heightPx > 0f) triggerPx / heightPx else 0f
            try {
                events.collect { event ->
                    reveal.snapTo(MemoriesBackMath.cardFraction(q0, triggerFraction, event.progress))
                }
                // Commit (also the button path with no events): the host's
                // surface effect is the one owner of the close spring.
                currentOnDismiss()
                currentOnCardBackFinished()
            } catch (e: CancellationException) {
                // The same settle owner as launchAnimateTo (animateTo registers its
                // job, a finger cancels it); suspending here only so the page
                // learns when the preview is really over.
                scope.launch {
                    try {
                        reveal.animateTo(0f)
                    } finally {
                        currentOnCardBackFinished()
                    }
                }
                throw e
            }
        }
    }
}

/** Memories' two dismiss release rules in px and px/s, from [BackMotionTokens]. */
@Immutable
class MemoriesDismissRules(
    /** The card body (and the diary's end): 112dp / 600dp/s, 350dp/s flick back. */
    val body: DismissRule,
    /** The top bar: 56dp / 450dp/s, 350dp/s flick back. */
    val bar: DismissRule,
)

@Composable
fun rememberMemoriesDismissRules(): MemoriesDismissRules {
    val density = LocalDensity.current
    return remember(density) {
        with(density) {
            val flickBack = BackMotionTokens.MemoriesFlickBack.toPx()
            MemoriesDismissRules(
                body = DismissRule(
                    commitPx = BackMotionTokens.MemoriesDismissTrigger.toPx(),
                    flingPxPerSec = BackMotionTokens.MemoriesDismissFling.toPx(),
                    flickBackPxPerSec = flickBack,
                ),
                bar = DismissRule(
                    commitPx = BackMotionTokens.MemoriesBarDismissTrigger.toPx(),
                    flingPxPerSec = BackMotionTokens.MemoriesBarDismissFling.toPx(),
                    flickBackPxPerSec = flickBack,
                ),
            )
        }
    }
}

/**
 * The retreating page's bottom corners: [BackMotionTokens.PopPageCornerRadius]
 * · smoothstep(0, threshold / H, q), where threshold is the commit distance of
 * whatever drives q (56dp bar, 112dp body and back) — full exactly when a
 * release would commit. Flat, no shadow. Reads q and the threshold only here,
 * inside the layer (invariant 6); clips only while a corner exists.
 */
fun Modifier.memoriesDismissCorners(reveal: RevealState, cornerThresholdPx: () -> Float): Modifier = graphicsLayer {
    val radius = MemoriesBackMath.cornerRadius(
        fraction = reveal.fraction,
        thresholdPx = cornerThresholdPx(),
        heightPx = size.height,
        maxRadiusPx = BackMotionTokens.PopPageCornerRadius.toPx(),
    )
    if (radius > 0f) {
        shape = RoundedCornerShape(topStart = 0f, topEnd = 0f, bottomEnd = radius, bottomStart = radius)
        clip = true
    } else {
        shape = RectangleShape
        clip = false
    }
}

/** Pure back-preview math (MemoriesBackMathTest). Progress is the raw system 0..1. */
internal object MemoriesBackMath {
    /**
     * Card level: q = q0 + (max(Δ, q0) − q0) · ease(progress), Δ = trigger / H.
     * From rest that is the plan's q0 + (Δ − q0)·ease — progress 1 parks the
     * page at the commit distance. A back that catches the page already past
     * Δ (an open or close spring mid-flight) holds it there rather than
     * pulling it back down under the preview.
     */
    fun cardFraction(q0: Float, triggerFraction: Float, progress: Float): Float {
        val target = maxOf(triggerFraction, q0)
        return q0 + (target - q0) * YoinMotion.backGestureEasing.transform(progress.coerceIn(0f, 1f))
    }

    /** Diary level: how far the page's AOSP preview pose has gone, ease(progress); p itself is not scrubbed. */
    fun diaryPreview(progress: Float): Float = YoinMotion.backGestureEasing.transform(progress.coerceIn(0f, 1f))

    /** Title-edit level: how far the edit's row has faded, ease(progress) — the finger owns it 1:1. */
    fun titleEditPreview(progress: Float): Float = YoinMotion.backGestureEasing.transform(progress.coerceIn(0f, 1f))

    /** See [memoriesDismissCorners]. */
    fun cornerRadius(fraction: Float, thresholdPx: Float, heightPx: Float, maxRadiusPx: Float): Float {
        if (fraction <= 0f) return 0f
        if (heightPx <= 0f || thresholdPx <= 0f) return maxRadiusPx
        val t = (fraction / (thresholdPx / heightPx)).coerceIn(0f, 1f)
        return maxRadiusPx * t * t * (3f - 2f * t)
    }
}
