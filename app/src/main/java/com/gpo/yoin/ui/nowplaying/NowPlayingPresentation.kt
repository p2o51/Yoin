package com.gpo.yoin.ui.nowplaying

import android.content.Context
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.material3.adaptive.ExperimentalMaterial3AdaptiveApi
import androidx.compose.material3.adaptive.currentWindowDpSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.layout
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.content.edit
import com.gpo.yoin.ui.experience.LayoutMode
import com.gpo.yoin.ui.experience.LocalWindowChromeInfo
import com.gpo.yoin.ui.experience.LocalYoinWindowInfo
import com.gpo.yoin.ui.experience.forPaneWidth
import com.gpo.yoin.ui.theme.YoinMotion
import com.gpo.yoin.ui.theme.YoinMotionRole
import java.time.LocalDate
import kotlin.math.roundToInt

/**
 * How Now Playing lays itself out in the current window (断点交接 §3.4 / §14.1).
 * Orthogonal to [NowPlayingStageMode] like LayoutMode is — this only picks the
 * frame the one stage machine renders into.
 *
 * ONE state chain for every window size: Closed → [Panel] → Full, where "Full"
 * is the window-wide player of the tier — [Phone] on Compact, [Enlarged] on
 * Medium, [DualPane] on Wide. The panel's corner toggle switches Panel ⇄ Full
 * and back steps Full → Panel → Closed, on Medium and Wide alike.
 */
enum class NowPlayingPresentation {
    /** Handsets, short windows, panes < 600: the single-column player, full window. */
    Phone,

    /**
     * Medium or Wide full window (fold inner screen, tablet portrait or
     * landscape, a wide split-screen half): the phone page in a phone-width
     * panel sliding in from the right, the host content still usable beside
     * it. The first thing a pill tap opens wherever a panel fits.
     */
    Panel,

    /**
     * Medium, Full state: the phone column enlarged and centred. Reached from
     * [Panel] with its corner toggle — or directly where no panel fits (a
     * 600–680 split half, which has no toggle).
     */
    Enlarged,

    /**
     * Wide (≥ 840) and tall, Full state: the two-column TabletNP. Reached from
     * [Panel] with its corner toggle, or directly when no panel fits.
     */
    DualPane,

    /**
     * A short, wide window — a landscape handset (LandscapeNP): cover left,
     * the phone's column on the right.
     */
    Landscape,

    /** Horizontal-hinge half fold. */
    Tabletop,
}

/**
 * Whether Now Playing opening in [presentation] covers Home. Only the side
 * panel leaves Home usable beside it; every other frame hides it.
 */
fun nowPlayingCoversHome(presentation: NowPlayingPresentation): Boolean = presentation != NowPlayingPresentation.Panel

/** Panel width: half the window, clamped to phone widths (fold 690 → 360, tablet 800 → 400). */
fun nowPlayingPanelWidth(windowWidth: Dp): Dp = (windowWidth * 0.5f).coerceIn(PanelMinWidth, PanelMaxWidth)

/** A short window at least this wide gets the two-column landscape player. */
private val LandscapeMinWidth = 560.dp

/**
 * The content beside the panel must keep at least a phone column. A host
 * that lays out more than one column beside the panel (the Wide shell with
 * its detail column open) passes its own, larger floor.
 */
val NowPlayingPanelMinContentWidth = 320.dp
private val PanelMinWidth = 360.dp
private val PanelMaxWidth = 420.dp

/**
 * Whether a side panel can open at all here: a tall Medium or Wide window that
 * keeps at least [minContentWidth] beside the panel (a phone column; two
 * phone columns + gutter while the Wide shell shows its detail column). Else
 * the tier goes straight to its Full state ([NowPlayingPresentation.Enlarged]
 * / [NowPlayingPresentation.DualPane]).
 */
fun canOpenNowPlayingPanel(
    layoutMode: LayoutMode,
    heightAtLeastMedium: Boolean,
    windowWidth: Dp,
    minContentWidth: Dp = NowPlayingPanelMinContentWidth,
): Boolean = (layoutMode == LayoutMode.Medium || layoutMode == LayoutMode.Wide) &&
    heightAtLeastMedium &&
    windowWidth - nowPlayingPanelWidth(windowWidth) >= maxOf(minContentWidth, NowPlayingPanelMinContentWidth)

fun resolveNowPlayingPresentation(
    layoutMode: LayoutMode,
    heightAtLeastMedium: Boolean,
    windowWidth: Dp,
    fullscreen: Boolean,
    minContentWidth: Dp = NowPlayingPanelMinContentWidth,
): NowPlayingPresentation = when {
    layoutMode == LayoutMode.Tabletop -> NowPlayingPresentation.Tabletop
    !heightAtLeastMedium && windowWidth >= LandscapeMinWidth -> NowPlayingPresentation.Landscape
    !heightAtLeastMedium -> NowPlayingPresentation.Phone
    layoutMode == LayoutMode.Wide -> {
        val panel = canOpenNowPlayingPanel(layoutMode, heightAtLeastMedium, windowWidth, minContentWidth)
        if (panel && !fullscreen) NowPlayingPresentation.Panel else NowPlayingPresentation.DualPane
    }
    layoutMode == LayoutMode.Medium -> {
        val panel = canOpenNowPlayingPanel(layoutMode, heightAtLeastMedium, windowWidth, minContentWidth)
        if (panel && !fullscreen) NowPlayingPresentation.Panel else NowPlayingPresentation.Enlarged
    }
    else -> NowPlayingPresentation.Phone
}

/** The live window width in dp (never `LocalConfiguration.screenWidthDp`). */
@OptIn(ExperimentalMaterial3AdaptiveApi::class)
@Composable
fun rememberWindowWidthDp(): Dp = currentWindowDpSize().width

/**
 * Now Playing's frame in this window, from the one set of inputs every
 * party reads — the overlay host and the host content beside a side panel
 * must never disagree (both pass the same [minContentWidth]).
 */
@Immutable
data class NowPlayingFrame(
    val presentation: NowPlayingPresentation,
    /** A Medium or Wide window where the panel ⇄ Full pair exists (and its corner toggle). */
    val panelAvailable: Boolean,
    val windowWidth: Dp,
) {
    val panelWidth: Dp get() = nowPlayingPanelWidth(windowWidth)
}

@Composable
fun rememberNowPlayingFrame(
    viewModel: NowPlayingViewModel,
    minContentWidth: Dp = NowPlayingPanelMinContentWidth,
): NowPlayingFrame {
    val windowInfo = LocalYoinWindowInfo.current
    val windowWidth = rememberWindowWidthDp()
    val fullscreen by viewModel.mediumFullscreen.collectAsState()
    return NowPlayingFrame(
        presentation = resolveNowPlayingPresentation(
            layoutMode = windowInfo.layoutMode,
            heightAtLeastMedium = windowInfo.isHeightAtLeastMedium,
            windowWidth = windowWidth,
            fullscreen = fullscreen,
            minContentWidth = minContentWidth,
        ),
        panelAvailable = canOpenNowPlayingPanel(
            layoutMode = windowInfo.layoutMode,
            heightAtLeastMedium = windowInfo.isHeightAtLeastMedium,
            windowWidth = windowWidth,
            minContentWidth = minContentWidth,
        ),
        windowWidth = windowWidth,
    )
}

/**
 * The panel's live horizontal travel (px, ≥ 0) while a back gesture or a
 * drag carries it toward the right edge. [NowPlayingOverlayHost] installs a
 * READER once (it captures the host's gesture states); the content beside
 * the panel calls it only in its layout pass, so a drag frame re-lays the
 * column without recomposing the host or the shell. One per window; create
 * it with [rememberNowPlayingPanelMotion] and hand it to both.
 */
@Stable
class NowPlayingPanelMotion {
    internal var travelReader: () -> Float by mutableStateOf(NoTravel)

    /** This frame's travel in px. Call from layout / draw only. */
    fun travelPx(): Float = travelReader()
}

private val NoTravel: () -> Float = { 0f }

@Composable
fun rememberNowPlayingPanelMotion(): NowPlayingPanelMotion = remember { NowPlayingPanelMotion() }

/**
 * The host side of the side panel (断点交接 §3.4 / §14.3): while it is open the
 * host's own content gives up the panel's width — on the SAME spatial spring
 * the panel slides with — and reads its own remaining width. [panelOpen] flips
 * at the start so the content re-lays for the narrower column while it
 * narrows; [panelWidth] is the resting width it gives up.
 *
 * The live inset (spring + gesture travel) is resolved in the layout phase
 * only ([insetPx], [besideNowPlayingPanel]): the instance itself changes only
 * when [panelOpen] / [panelWidth] do, so callers recompose on those alone.
 */
@Stable
class NowPlayingPanelInset internal constructor(
    val panelOpen: Boolean,
    val panelWidth: Dp,
    private val base: State<Dp>,
    private val motion: NowPlayingPanelMotion?,
    private val frozenTravelFraction: FloatArray,
    private val baseTarget: Dp = 0.dp,
) {
    /** The open/close spring is running (for a High frame-rate vote; snapshot read). */
    val isMoving: Boolean get() = base.value != baseTarget

    /**
     * A gesture is carrying the open panel (back preview, swipe-to-close), so
     * the content beside it is widening frame by frame. Snapshot read.
     */
    val isCarried: Boolean get() = panelOpen && (motion?.travelPx() ?: 0f) > 0f
    /**
     * The end inset this frame, px. While the panel is carried right (back
     * preview, swipe-to-close) the content takes the vacated width back 1:1
     * — the panel's resting width minus its travel. On commit the last
     * travel is frozen and scaled down with the base spring, so the column
     * widens monotonically instead of snapping back as the two springs cross.
     * Layout / draw phase only.
     */
    fun insetPx(density: Density): Float = with(density) {
        val basePx = base.value.toPx()
        if (panelOpen) {
            val travel = (motion?.travelPx() ?: 0f).coerceAtLeast(0f)
            val widthPx = panelWidth.toPx()
            if (widthPx > 0f) frozenTravelFraction[0] = (travel / widthPx).coerceIn(0f, 1f)
            (basePx - travel).coerceAtLeast(0f)
        } else {
            basePx * (1f - frozenTravelFraction[0])
        }
    }
}

@Composable
fun rememberNowPlayingPanelInset(
    frame: NowPlayingFrame,
    expanded: Boolean,
    motion: NowPlayingPanelMotion? = null,
): NowPlayingPanelInset {
    val panelOpen = expanded && frame.presentation == NowPlayingPresentation.Panel
    // Pinned to the Standard role: the panel slides on exactly this spring
    // (OverlayPlayerVisibility), and an ambient role must never split them.
    val base = animateDpAsState(
        targetValue = if (panelOpen) frame.panelWidth else 0.dp,
        animationSpec = YoinMotion.defaultSpatialSpec(role = YoinMotionRole.Standard),
        label = "nowPlayingPanelInset",
    )
    val frozen = remember { FloatArray(1) }
    return remember(panelOpen, frame.panelWidth, base, motion, frozen) {
        NowPlayingPanelInset(
            panelOpen = panelOpen,
            panelWidth = frame.panelWidth,
            base = base,
            motion = motion,
            frozenTravelFraction = frozen,
            baseTarget = if (panelOpen) frame.panelWidth else 0.dp,
        )
    }
}

/**
 * Host content beside the panel: narrowed by the live panel inset, read in
 * the layout pass (an end inset, like `padding(end = …)`).
 */
fun Modifier.besideNowPlayingPanel(panel: NowPlayingPanelInset): Modifier = layout { measurable, constraints ->
    if (!constraints.hasBoundedWidth) {
        val placeable = measurable.measure(constraints)
        return@layout layout(placeable.width, placeable.height) { placeable.placeRelative(0, 0) }
    }
    val inset = panel.insetPx(this).roundToInt().coerceIn(0, constraints.maxWidth)
    val placeable = measurable.measure(
        constraints.copy(
            minWidth = (constraints.minWidth - inset).coerceAtLeast(0),
            maxWidth = constraints.maxWidth - inset,
        ),
    )
    layout(placeable.width + inset, placeable.height) { placeable.placeRelative(0, 0) }
}

/**
 * Host content beside the open panel reads its OWN remaining width (window −
 * panel: Compact beside a tablet-portrait panel, 800 − 400; still Wide beside
 * a tablet-landscape one, 1280 − 420 = 860), not the window's. Chrome that
 * belongs to the window (the bar) keeps reading the window
 * ([LocalWindowChromeInfo]). The provider is ALWAYS in the tree: toggling a
 * wrapper in and out would re-create the page — resetting its scroll and
 * re-registering its back callback above Now Playing's (LIFO), which then
 * stole the panel's back.
 */
@Composable
fun ProvideBesidePanelWindowInfo(panel: NowPlayingPanelInset, content: @Composable () -> Unit) {
    val windowInfo = LocalYoinWindowInfo.current
    val windowWidth = rememberWindowWidthDp()
    // Remembered so an unchanged column keeps the SAME instance: a static
    // local re-provided with a fresh copy would recompose every reader.
    val besideInfo = remember(windowInfo, windowWidth, panel.panelOpen, panel.panelWidth) {
        if (panel.panelOpen) windowInfo.forPaneWidth(windowWidth - panel.panelWidth) else windowInfo
    }
    CompositionLocalProvider(
        LocalYoinWindowInfo provides besideInfo,
        // The page's bar still belongs to the window (principle 2).
        LocalWindowChromeInfo provides (LocalWindowChromeInfo.current ?: windowInfo),
        content = content,
    )
}

/**
 * The enlarged phone column's width: the window minus 80dp margins, capped at
 * 640 (tablet portrait 800 → 640, TabletPortraitNP). A short window narrows it
 * further inside the stage, where the cover is also height-limited
 * (fold 690 × 840 → ~460, FoldNPSingle).
 */
fun nowPlayingEnlargedColumnWidth(windowWidth: Dp): Dp =
    (windowWidth - EnlargedSideMargin * 2).coerceIn(PanelMinWidth, EnlargedMaxColumn)

private val EnlargedSideMargin = 80.dp
private val EnlargedMaxColumn = 640.dp

/** The phone column the enlarged one scales from. */
private val PhoneColumnWidth = 400.dp

/**
 * Sizes of the enlarged phone column: the rating column and controls grow
 * with the column (tablet 640 → rating 104, controls 60; fold ≈ rating 72).
 */
@Immutable
data class NowPlayingEnlargedSpec(
    val columnWidth: Dp,
    val ratingColumn: Dp,
    val controlSize: Dp,
)

fun nowPlayingEnlargedSpec(windowWidth: Dp): NowPlayingEnlargedSpec {
    val column = nowPlayingEnlargedColumnWidth(windowWidth)
    val scale = column / PhoneColumnWidth
    return NowPlayingEnlargedSpec(
        columnWidth = column,
        ratingColumn = (56.dp + 80.dp * (scale - 1f)).coerceIn(56.dp, 104.dp),
        controlSize = if (scale >= 1.4f) 60.dp else 56.dp,
    )
}

/**
 * Size switches while Now Playing is open (断点交接 §3.4), for the two
 * panel-capable tiers (Medium and Wide): unfolding a phone / growing a window
 * out of Compact lands in the Full state (enlarged phone / two columns);
 * moving between Medium and Wide — rotating a tablet between portrait and
 * landscape — keeps the user's Panel / Full choice; any other arrival — or a
 * fresh open — starts as the panel. Outside those tiers the flag is inert and
 * kept as it was.
 */
fun fullscreenAfterLayoutChange(
    previous: LayoutMode?,
    current: LayoutMode,
    expanded: Boolean,
    wasFullscreen: Boolean,
): Boolean = when {
    current != LayoutMode.Medium && current != LayoutMode.Wide -> wasFullscreen
    previous == LayoutMode.Compact && expanded -> true
    previous == LayoutMode.Medium || previous == LayoutMode.Wide -> wasFullscreen
    else -> false
}

// ---------------------------------------------------------------------------
// "Tap to expand" hint — at most once a day (断点交接 §3.1)
// ---------------------------------------------------------------------------

/** Remembers the local day the lyric-idle hint last showed. */
interface LyricHintStore {
    fun lastShownEpochDay(): Long?
    fun markShown(epochDay: Long)

    /** Process-local fallback for previews and tests. */
    class InMemory : LyricHintStore {
        private var day: Long? = null
        override fun lastShownEpochDay(): Long? = day
        override fun markShown(epochDay: Long) {
            day = epochDay
        }
    }
}

class SharedPrefsLyricHintStore(context: Context) : LyricHintStore {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    override fun lastShownEpochDay(): Long? =
        prefs.getLong(KEY_LYRIC_IDLE_HINT_DAY, NONE).takeIf { it != NONE }

    override fun markShown(epochDay: Long) {
        prefs.edit { putLong(KEY_LYRIC_IDLE_HINT_DAY, epochDay) }
    }

    private companion object {
        const val PREFS_NAME = "yoin_ui_hints"
        const val KEY_LYRIC_IDLE_HINT_DAY = "lyric_idle_hint_day"
        const val NONE = Long.MIN_VALUE
    }
}

/** True when the hint has not shown yet on [today] (local date). */
fun lyricIdleHintAllowed(lastShownEpochDay: Long?, today: LocalDate): Boolean =
    lastShownEpochDay != today.toEpochDay()

/** A lyric line held at least this long counts as "stopped" (intro / break / long note). */
const val LyricIdleHintDelayMs = 8_000L
