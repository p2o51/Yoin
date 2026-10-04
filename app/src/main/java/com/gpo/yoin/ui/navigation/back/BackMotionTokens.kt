package com.gpo.yoin.ui.navigation.back

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.ui.unit.dp

object BackMotionTokens {
    /**
     * Scale the popped page shrinks to on back, matching the AOSP
     * cross-activity predictive back animation (CrossActivityBackAnimation
     * MAX_SCALE = 0.9).
     */
    const val PopPageScaleTarget = 0.9f

    /**
     * Corner radius the popped page clips to while shrinking; stand-in for
     * the device window corner radius the system animation uses.
     */
    val PopPageCornerRadius = 28.dp

    /**
     * Memories' retreat to Home from the card body (and from the end of the
     * diary): a release past this many dp of upward travel commits. It is ALSO
     * the system-back preview's full travel — progress 1 parks the page here,
     * the same pose a finger needs to commit — and the distance at which the
     * page's bottom corners reach [PopPageCornerRadius]. Was a hint distance
     * only; it has been the commit threshold since the showcase rebuild (P2).
     */
    val MemoriesDismissTrigger = 112.dp

    /** The same commit from the Memories top bar, which is only ~96dp tall. */
    val MemoriesBarDismissTrigger = 56.dp

    /**
     * Upward release speed, in dp PER SECOND, that commits from the card body
     * below [MemoriesDismissTrigger]. The speeds below are typed as Dp so call
     * sites convert them with `toPx()` (px/s).
     */
    val MemoriesDismissFling = 600.dp

    /** Upward release speed (dp/s) that commits from the top bar below [MemoriesBarDismissTrigger]. */
    val MemoriesBarDismissFling = 450.dp

    /**
     * A flick back the other way at this speed (dp/s) returns, even past a
     * commit threshold — Memories' dismiss (down) and the diary pull (either way).
     */
    val MemoriesFlickBack = 350.dp

    /** Travel of the card ⇄ diary morph: p = travel / this (prototype `d.D`). */
    val MemoriesDiaryMorphDistance = 320.dp

    /**
     * The first dp of a diary pull past the top of its text move the morph at
     * half speed: a felt boundary between reading and collapsing. A release
     * still inside its first half (a fling "to the top") stays in the diary.
     */
    val MemoriesDiaryPullBand = 24.dp

    /**
     * AOSP `Interpolators.EMPHASIZED` — the platform's curve for the
     * cross-activity open/close rides. Single definition shared by the
     * forward enter slide (DetailEnterIntro) and the shell's entering-side
     * pose (DetailBackEntering) so the mirrored trajectories can't drift.
     */
    val EmphasizedEasing = CubicBezierEasing(0.05f, 0.7f, 0.1f, 1f)

    /**
     * AOSP `cross_activity_back_entering_start_offset`: where an entering
     * page starts (push) and where the revealed page waits (back). Shared by
     * the detail column's push/pop and the window pages' mirrors.
     */
    val EnteringStartOffset = 96.dp

    /** AOSP `DefaultCrossActivityBackAnimation.POST_COMMIT_DURATION`, the EMPHASIZED ride's length. */
    const val PostCommitDurationMs = 450
}
