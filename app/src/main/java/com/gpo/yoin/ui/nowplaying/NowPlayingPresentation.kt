package com.gpo.yoin.ui.nowplaying

import android.content.Context
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.adaptive.ExperimentalMaterial3AdaptiveApi
import androidx.compose.material3.adaptive.currentWindowDpSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.content.edit
import com.gpo.yoin.ui.experience.LayoutMode
import com.gpo.yoin.ui.experience.LocalYoinWindowInfo
import com.gpo.yoin.ui.experience.asCompactPane
import com.gpo.yoin.ui.experience.rememberIsActivityEmbedded
import com.gpo.yoin.ui.theme.YoinMotion
import java.time.LocalDate

/**
 * How Now Playing lays itself out in the current window (断点交接 §3.4 / §14.1).
 * Orthogonal to [NowPlayingStageMode] like LayoutMode is — this only picks the
 * frame the one stage machine renders into.
 */
enum class NowPlayingPresentation {
    /** Handsets, short windows, panes < 600: the single-column player, full window. */
    Phone,

    /**
     * Medium full window (fold inner screen, tablet portrait, a wide split-screen
     * half): the phone page in a phone-width panel sliding in from the right,
     * the host content still usable beside it. The first thing a pill tap opens.
     */
    Panel,

    /**
     * Medium, full screen: the phone column enlarged and centred. Reached from
     * [Panel] with its corner button — or directly where no panel fits (a
     * 600–680 split half) or inside an embedded pane (which never opens a
     * panel and has no toggle).
     */
    Enlarged,

    /** Wide (≥ 840 window or pane) and tall: the two-column TabletNP. */
    DualPane,

    /**
     * A short, wide window — a landscape handset (LandscapeNP): cover left,
     * the phone's column on the right.
     */
    Landscape,

    /** Horizontal-hinge half fold. */
    Tabletop,
}

/** Panel width: half the window, clamped to phone widths (fold 690 → 360, tablet 800 → 400). */
fun nowPlayingPanelWidth(windowWidth: Dp): Dp = (windowWidth * 0.5f).coerceIn(PanelMinWidth, PanelMaxWidth)

/** A short window at least this wide gets the two-column landscape player. */
private val LandscapeMinWidth = 560.dp

/** The content beside the panel must keep at least a phone column. */
internal val PanelMinContentWidth = 320.dp
private val PanelMinWidth = 360.dp
private val PanelMaxWidth = 420.dp

/** Whether a side panel can open at all here (else Medium goes straight to [NowPlayingPresentation.Enlarged]). */
fun canOpenNowPlayingPanel(
    layoutMode: LayoutMode,
    heightAtLeastMedium: Boolean,
    embedded: Boolean,
    windowWidth: Dp,
): Boolean = layoutMode == LayoutMode.Medium &&
    heightAtLeastMedium &&
    !embedded &&
    windowWidth - nowPlayingPanelWidth(windowWidth) >= PanelMinContentWidth

fun resolveNowPlayingPresentation(
    layoutMode: LayoutMode,
    heightAtLeastMedium: Boolean,
    embedded: Boolean,
    windowWidth: Dp,
    fullscreen: Boolean,
): NowPlayingPresentation = when {
    layoutMode == LayoutMode.Tabletop -> NowPlayingPresentation.Tabletop
    !heightAtLeastMedium && windowWidth >= LandscapeMinWidth -> NowPlayingPresentation.Landscape
    !heightAtLeastMedium -> NowPlayingPresentation.Phone
    layoutMode == LayoutMode.Wide -> NowPlayingPresentation.DualPane
    layoutMode == LayoutMode.Medium -> {
        val panel = canOpenNowPlayingPanel(layoutMode, heightAtLeastMedium, embedded, windowWidth)
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
 * must never disagree.
 */
@Immutable
data class NowPlayingFrame(
    val presentation: NowPlayingPresentation,
    /** A Medium window where the panel ⇄ enlarged pair exists (and its corner toggle). */
    val panelAvailable: Boolean,
    val windowWidth: Dp,
) {
    val panelWidth: Dp get() = nowPlayingPanelWidth(windowWidth)
}

@Composable
fun rememberNowPlayingFrame(viewModel: NowPlayingViewModel): NowPlayingFrame {
    val windowInfo = LocalYoinWindowInfo.current
    val embedded = rememberIsActivityEmbedded()
    val windowWidth = rememberWindowWidthDp()
    val fullscreen by viewModel.mediumFullscreen.collectAsState()
    return NowPlayingFrame(
        presentation = resolveNowPlayingPresentation(
            layoutMode = windowInfo.layoutMode,
            heightAtLeastMedium = windowInfo.isHeightAtLeastMedium,
            embedded = embedded,
            windowWidth = windowWidth,
            fullscreen = fullscreen,
        ),
        panelAvailable = canOpenNowPlayingPanel(
            layoutMode = windowInfo.layoutMode,
            heightAtLeastMedium = windowInfo.isHeightAtLeastMedium,
            embedded = embedded,
            windowWidth = windowWidth,
        ),
        windowWidth = windowWidth,
    )
}

/**
 * The host side of the side panel (断点交接 §3.4 / §14.3): while it is open the
 * host's own content gives up the panel's width — on the SAME spatial spring
 * the panel slides with — and reads as a handset. [panelOpen] flips at the
 * start so the content re-lays as Compact while it narrows.
 */
@Immutable
data class NowPlayingPanelInset(
    val panelOpen: Boolean,
    val inset: Dp,
)

@Composable
fun rememberNowPlayingPanelInset(frame: NowPlayingFrame, expanded: Boolean): NowPlayingPanelInset {
    val panelOpen = expanded && frame.presentation == NowPlayingPresentation.Panel
    val inset by animateDpAsState(
        targetValue = if (panelOpen) frame.panelWidth else 0.dp,
        animationSpec = YoinMotion.defaultSpatialSpec(),
        label = "nowPlayingPanelInset",
    )
    return NowPlayingPanelInset(panelOpen = panelOpen, inset = inset)
}

/** Host content beside the panel: narrowed by the (animated) panel width. */
fun Modifier.besideNowPlayingPanel(panel: NowPlayingPanelInset): Modifier =
    if (panel.inset > 0.dp) padding(end = panel.inset) else this

/**
 * Host content beside the open panel reads its own (Compact) width, not the
 * window's. The provider is ALWAYS in the tree: toggling a wrapper in and out
 * would re-create the page — resetting its scroll and re-registering its back
 * callback above Now Playing's (LIFO), which then stole the panel's back.
 */
@Composable
fun ProvideBesidePanelWindowInfo(panel: NowPlayingPanelInset, content: @Composable () -> Unit) {
    val windowInfo = LocalYoinWindowInfo.current
    CompositionLocalProvider(
        LocalYoinWindowInfo provides if (panel.panelOpen) windowInfo.asCompactPane() else windowInfo,
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
 * Size switches while Now Playing is open (断点交接 §3.4): unfolding a phone
 * (Compact → Medium) lands in the enlarged phone; any other arrival in Medium
 * — or a fresh open — starts as the panel.
 */
fun fullscreenAfterLayoutChange(
    previous: LayoutMode?,
    current: LayoutMode,
    expanded: Boolean,
    wasFullscreen: Boolean,
): Boolean = when {
    current != LayoutMode.Medium -> wasFullscreen
    previous == LayoutMode.Compact && expanded -> true
    previous == LayoutMode.Medium -> wasFullscreen
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
