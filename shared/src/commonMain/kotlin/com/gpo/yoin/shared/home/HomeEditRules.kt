package com.gpo.yoin.shared.home

import kotlin.math.abs

// Home edit mode's pure rules besides the layout and the drag (docs/design.md §主页 › 就地编辑; Android
// PR #292), moved unchanged from Android's HomeSpanResize.kt, HomeEditList.kt, HomeEditController.kt and
// HomeViewModel.kt for parity phase 4: the resize handle's horizontal axis, the pinch into the compact
// list, the draft that follows a width change, the echo check, the write rule and the undo depth.

// ── The span handle (large-screen Home, stage 1, owner 2026-11-16) ──────
// In two columns every section's ⌟ also drags sideways — the first direction a drag takes locks it — and a
// section turns half or full width: a full row dragged left past the midpoint becomes a half and pulls the
// row after it up beside it; a left half dragged right (a right half left) past it becomes full, its
// partner on its own row after it. ← / → on a focused handle do the same. Phones keep the vertical axis.

/** Where a section sits in its row. */
enum class HomeSpan { Full, LeftHalf, RightHalf }

/** The axis a handle drag has locked to. */
enum class HomeHandleAxis { Horizontal, Vertical }

/** What a span drag or key writes. */
enum class HomeSpanCommit { None, ToHalf, ToFull }

/** Numbers of the horizontal resize (dp unless noted). */
object HomeSpanRules {
    /** The gap between the two halves of a row. */
    const val ColumnGap = 20f

    /** A drag can pull a block this much narrower than a half (a rubber band past it), a fraction. */
    const val UnderHalf = .82f
}

/** Where a section's block sits now, for a span drag: its span, width, a half's and a full row's width (px). */
data class HomeSpanInfo(
    val span: HomeSpan,
    val currentPx: Float,
    val halfPx: Float,
    val fullPx: Float,
    /** A full row has a row after it to pull up beside it. */
    val hasNext: Boolean,
)

/**
 * The axis a drag locks to once it has moved past [slopPx] (the larger
 * component wins; a tie is vertical, today's axis), or null while it hasn't.
 * Without [horizontal] (one column, or a section that can't pair) it is
 * always vertical. Pure.
 */
fun homeHandleAxis(dx: Float, dy: Float, slopPx: Float, horizontal: Boolean): HomeHandleAxis? {
    if (abs(dx) <= slopPx && abs(dy) <= slopPx) return null
    if (!horizontal) return HomeHandleAxis.Vertical
    return if (abs(dx) > abs(dy)) HomeHandleAxis.Horizontal else HomeHandleAxis.Vertical
}

/**
 * The dragged block's live width, px: [startPx] moved by the finger's [dx]
 * — a right half grows leftward, so its width follows −dx — held between a
 * little under [halfPx] ([HomeSpanRules.UnderHalf]) and [fullPx]. Pure.
 */
fun homeSpanDragWidth(from: HomeSpan, startPx: Float, dx: Float, halfPx: Float, fullPx: Float): Float {
    val raw = if (from == HomeSpan.RightHalf) startPx - dx else startPx + dx
    return raw.coerceIn(halfPx * HomeSpanRules.UnderHalf, fullPx)
}

/** The span a block of [widthPx] snaps to: half below the midpoint of [halfPx] and [fullPx], else full. Pure. */
fun homeSpanTarget(widthPx: Float, halfPx: Float, fullPx: Float): HomeSpan =
    if (widthPx < (halfPx + fullPx) / 2f) HomeSpan.LeftHalf else HomeSpan.Full

/**
 * What a release from [from] snapping to [target] writes: a full row turning
 * half needs a row after it ([hasNext]); a half turning full always can;
 * anything else is no change (the block springs back). Pure.
 */
fun homeSpanCommit(from: HomeSpan, target: HomeSpan, hasNext: Boolean): HomeSpanCommit = when {
    from == HomeSpan.Full && target != HomeSpan.Full -> if (hasNext) HomeSpanCommit.ToHalf else HomeSpanCommit.None
    from != HomeSpan.Full && target == HomeSpan.Full -> HomeSpanCommit.ToFull
    else -> HomeSpanCommit.None
}

/**
 * ← / → on a focused handle: a full row ← turns half (with a row after it), a
 * left half → and a right half ← turn full. Null for a key that does nothing. Pure.
 */
fun homeSpanKey(from: HomeSpan, left: Boolean, hasNext: Boolean): HomeSpanCommit? = when (from) {
    HomeSpan.Full -> if (left && hasNext) HomeSpanCommit.ToHalf else null
    HomeSpan.LeftHalf -> if (!left) HomeSpanCommit.ToFull else null
    HomeSpan.RightHalf -> if (left) HomeSpanCommit.ToFull else null
}

// ── The pinch into the compact list (owner 2026-11-22) ──────────────────

/** Numbers of the list's pinch. */
object HomeListPinchRules {
    /** A pinch to this fraction of the fingers' starting span takes the feed all the way into the list. */
    const val PinchInFull = .55f

    /** A pinch out to this multiple of the starting span brings the feed all the way back. */
    const val PinchOutFull = 1.8f

    /** Let go this far of the way (or further) and the change finishes; short of it, it springs back. */
    const val PinchCommit = .35f
}

/**
 * The list's progress for a pinch at [scale] (the fingers' span over their
 * starting span) begun [fromOn] (in the list) or not: in from the feed,
 * 0 → 1 as the span closes to [HomeListPinchRules.PinchInFull]; out from the
 * list, 1 → 0 as it opens to [HomeListPinchRules.PinchOutFull]. The other
 * way does nothing. Pure.
 */
fun homeListPinchProgress(fromOn: Boolean, scale: Float): Float = with(HomeListPinchRules) {
    if (!fromOn) {
        ((1f - scale) / (1f - PinchInFull)).coerceIn(0f, 1f)
    } else {
        1f - ((scale - 1f) / (PinchOutFull - 1f)).coerceIn(0f, 1f)
    }
}

/** A pinch begun [fromOn] let go at [progress] lands in the list (true) or the feed. Pure. */
fun homeListSettlesOn(fromOn: Boolean, progress: Float): Boolean =
    if (!fromOn) progress >= HomeListPinchRules.PinchCommit else progress > 1f - HomeListPinchRules.PinchCommit

// ── The edit session ─────────────────────────────────────────────────────

/** Why Home edit mode ended. Only [Done] confirms with a haptic. */
enum class HomeEditExitReason {
    /** The bar's Done button. */
    Done,

    /** System back. */
    Back,

    /** A tap on blank page space. */
    Blank,

    /** The app left edit mode on its own (profile switch, section change, stop). */
    Programmatic,
}

/** Edit mode keeps this many Undo steps; the oldest goes first. */
const val HOME_EDIT_UNDO_MAX: Int = 20

/**
 * The draft after the feed changed width in edit mode (a rotation, a window resize): untouched (no Undo
 * step yet, [edited] false) it is what entering at that width would have made it, from [base] (the layout
 * the session started from: the echo hold, else the stored one, else [HomeLayout.Default]); edited, it keeps
 * its order and two columns pair it from its memory ([HomeLayout.shownAt]) — never portrait's single
 * column pinned in landscape. Pure.
 */
fun homeDraftAtWidth(draft: HomeLayout, edited: Boolean, base: HomeLayout, twoColumn: Boolean): HomeLayout =
    if (!edited) base.forTwoColumn(twoColumn) else draft.shownAt(twoColumn)

/**
 * One edit's result: null when [changed] is [current] itself (nothing changed: no Undo step, no write);
 * else [changed], and in [twoColumn] with its memory made what two columns show
 * ([HomeLayout.rememberingShownPairs]): two columns remember what they show, one column changes order and
 * visibility only. Pure.
 */
fun homeEditStep(current: HomeLayout, changed: HomeLayout, twoColumn: Boolean): HomeLayout? = when {
    changed === current -> null
    twoColumn -> changed.rememberingShownPairs()
    else -> changed
}

/**
 * The stored layout [stored] stands for the layout last [shown] in edit mode (the echo of its write has
 * arrived): the same arrangement, or the stored "never customized" while [shown] is the default a width
 * shows for it. Pure.
 */
fun homeLayoutEchoes(stored: HomeLayout, shown: HomeLayout): Boolean =
    stored.sameSectionsAs(shown) || (stored.isDefault && shown.isAnyDefault)

/** What persisting a layout does to the stored row. */
sealed interface HomePersistAction {
    /** Delete the row: back to "never customized", so new sections follow their defaults again. */
    data object Clear : HomePersistAction

    /** Write [document] (skipped by the store when the row already holds it). */
    data class Write(val document: HomeLayoutDocument) : HomePersistAction
}

/**
 * How [layout] is persisted: either default (the phone's, or the two-column one a large screen shows) with
 * no retained ids clears the row — the document's kept keys go with it, as Reset clears everything on every
 * platform — anything else writes its document. Pure.
 */
fun homePersistAction(layout: HomeLayout): HomePersistAction =
    if (layout.isAnyDefault && layout.retained.isEmpty()) HomePersistAction.Clear else HomePersistAction.Write(layout.toDocument())
