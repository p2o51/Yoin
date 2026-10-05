package com.gpo.yoin.ui.home

import android.os.SystemClock
import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.EnterExitState
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.togetherWith
import androidx.compose.runtime.key
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.Placeable
import androidx.compose.ui.layout.layout
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.constrainHeight
import com.gpo.yoin.ui.experience.LocalMotionProfile
import com.gpo.yoin.ui.experience.MotionProfile
import com.gpo.yoin.ui.experience.feedFrameClass
import com.gpo.yoin.ui.theme.YoinMotionRole
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.toShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.structuralEqualityPolicy
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.FrameRateCategory
import androidx.compose.ui.Modifier
import androidx.compose.ui.preferredFrameRate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.gpo.yoin.data.local.ActivityEntityType
import com.gpo.yoin.data.local.ActivityEvent
import com.gpo.yoin.data.model.Album
import com.gpo.yoin.data.model.CoverRef
import com.gpo.yoin.data.model.MediaId
import com.gpo.yoin.data.model.Track
import com.gpo.yoin.symbols.YoinSymbols
import com.gpo.yoin.ui.component.ExpressiveMediaArtwork
import com.gpo.yoin.ui.component.ExpressiveSectionPanel
import com.gpo.yoin.ui.component.LocalSeamBarField
import com.gpo.yoin.ui.component.MarqueeText
import com.gpo.yoin.ui.component.SeamBackground
import com.gpo.yoin.ui.component.SeamDissolveTokens
import com.gpo.yoin.ui.component.SeamFlow
import com.gpo.yoin.ui.component.SeamTop
import com.gpo.yoin.ui.component.elasticPress
import com.gpo.yoin.ui.component.ignoreParentHorizontalPadding
import com.gpo.yoin.ui.component.horizontalEdgeFadeOnScroll
import com.gpo.yoin.ui.component.noRippleClickable
import com.gpo.yoin.ui.component.rememberExpressiveBackdropColors
import com.gpo.yoin.ui.component.rememberStagedReveal
import com.gpo.yoin.ui.component.seamDissolve
import com.gpo.yoin.ui.component.expressivePageSeamBackground
import com.gpo.yoin.ui.component.seamDissolveViewport
import com.gpo.yoin.ui.component.seamFade
import com.gpo.yoin.ui.component.seamRemainingPx
import com.gpo.yoin.ui.component.seamScrolledPx
import com.gpo.yoin.ui.component.seamTide
import com.gpo.yoin.ui.component.stagedBeat
import com.gpo.yoin.ui.experience.LayoutMode
import com.gpo.yoin.ui.experience.LocalPaneWidthInMotion
import com.gpo.yoin.ui.experience.LocalYoinWindowInfo
import com.gpo.yoin.ui.experience.RevealState
import com.gpo.yoin.ui.experience.rememberRevealState
import com.gpo.yoin.ui.experience.rememberYoinHaptics
import com.gpo.yoin.ui.home.edit.AllHiddenKey
import com.gpo.yoin.ui.home.edit.FooterEntryKey
import com.gpo.yoin.ui.home.edit.GatedPlacementSpec
import com.gpo.yoin.ui.home.edit.HomeCarryEngine
import com.gpo.yoin.ui.home.edit.HomeCarryStack
import com.gpo.yoin.ui.home.edit.HomeEditBlock
import com.gpo.yoin.ui.home.edit.HomeEditChange
import com.gpo.yoin.ui.home.edit.HomeEditClock
import com.gpo.yoin.ui.home.edit.HomeEditController
import com.gpo.yoin.ui.home.edit.HomeEditDeps
import com.gpo.yoin.ui.home.edit.HomeEditExitReason
import com.gpo.yoin.ui.home.edit.HomeEditHeaderHint
import com.gpo.yoin.ui.home.edit.HomeEditHeaderTitle
import com.gpo.yoin.ui.home.edit.HomeEditHitTester
import com.gpo.yoin.ui.home.edit.HomeEditLayer
import com.gpo.yoin.ui.home.edit.HomeEditMotion
import com.gpo.yoin.ui.home.edit.HomeEditPressState
import com.gpo.yoin.ui.home.edit.HomeEditSafeArea
import com.gpo.yoin.ui.home.edit.HomeEditTargets
import com.gpo.yoin.ui.home.edit.HomeEditTokens
import com.gpo.yoin.ui.home.edit.HomeRowsAutoScroll
import com.gpo.yoin.ui.home.edit.HomeRowsBody
import com.gpo.yoin.ui.home.edit.HomeRowsEngine
import com.gpo.yoin.ui.home.edit.HomeRowsTokens
import com.gpo.yoin.ui.home.edit.homeRowsCard
import com.gpo.yoin.ui.home.edit.homeRowsEdgeRamp
import com.gpo.yoin.ui.home.edit.LocalHomeWiggleStyle
import com.gpo.yoin.ui.home.edit.StripCover
import com.gpo.yoin.ui.home.edit.StripWarmFrames
import com.gpo.yoin.ui.home.edit.StripWarmth
import com.gpo.yoin.ui.home.edit.stripWarmth
import com.gpo.yoin.ui.home.edit.TrayFooterKey
import com.gpo.yoin.ui.home.edit.TrayTitleKey
import com.gpo.yoin.ui.home.edit.asHomeEditFeedback
import com.gpo.yoin.ui.home.edit.feedFoldWash
import com.gpo.yoin.ui.home.edit.carryItemKey
import com.gpo.yoin.ui.home.edit.homeAllHiddenItem
import com.gpo.yoin.ui.home.edit.homeEditCard
import com.gpo.yoin.ui.home.edit.homeEditExclusion
import com.gpo.yoin.ui.home.edit.homeEditFooterEntry
import com.gpo.yoin.ui.home.edit.homeEditGestures
import com.gpo.yoin.ui.home.edit.homeEditHeaderIcon
import com.gpo.yoin.ui.home.edit.homeEditInteractive
import com.gpo.yoin.ui.home.edit.homeEditTrayItems
import com.gpo.yoin.ui.home.edit.homeEditTrayRowKey
import com.gpo.yoin.ui.home.edit.plateOutsetVDp
import com.gpo.yoin.ui.home.edit.HomePlateLook
import com.gpo.yoin.ui.home.edit.HomePlateSpacing
import com.gpo.yoin.ui.home.edit.HomePlateSpacingAnchor
import com.gpo.yoin.ui.home.edit.HomePlateVariant
import com.gpo.yoin.ui.home.edit.LocalHomeEditCardScope
import com.gpo.yoin.ui.home.edit.LocalHomePlateVariant
import com.gpo.yoin.ui.home.edit.homeEditShelfClip
import com.gpo.yoin.ui.home.edit.rememberHomeEditIconsEnabled
import com.gpo.yoin.ui.home.edit.rememberHomeEditReducedMotion
import com.gpo.yoin.ui.home.edit.rememberHomeEditSpecs
import com.gpo.yoin.ui.home.edit.rememberLazyListCarryHost
import com.gpo.yoin.ui.home.edit.rememberStandaloneHomeEditController
import com.gpo.yoin.ui.home.edit.stripCover
import com.gpo.yoin.ui.theme.YoinMotion
import com.gpo.yoin.ui.theme.YoinShapeTokens
import com.gpo.yoin.ui.theme.YoinArtworkShapes
import com.gpo.yoin.ui.theme.YoinContainerShapes
import com.gpo.yoin.ui.theme.withTabularFigures
import kotlin.math.roundToInt
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch

internal sealed interface HomeEntryTarget {
    data class Album(val albumId: String, val sharedTransitionKey: String?) : HomeEntryTarget
    data class Artist(val artistId: String) : HomeEntryTarget
    data class Playlist(val playlistId: String) : HomeEntryTarget
    data class SongTarget(val song: Track) : HomeEntryTarget
}

private data class HomeMomentEntry(
    val stableId: String,
    val entityType: String,
    val title: String,
    val subtitle: String,
    // Split so the small bento card can stack them ("Playlist" / "1d ago",
    // the Figma layout); hero/wide join them with a dot.
    val typeLabel: String,
    val timeAgo: String,
    val coverArtUrl: String?,
    val target: HomeEntryTarget,
)

/**
 * The entry's entity identity (not its activity row): replaying the same album
 * keeps the same key, so the bento's stagger doesn't reshuffle on a replay.
 */
private val HomeMomentEntry.layoutKey: String
    get() = entityType + ":" + when (val t = target) {
        is HomeEntryTarget.Album -> t.albumId
        is HomeEntryTarget.Artist -> t.artistId
        is HomeEntryTarget.Playlist -> t.playlistId
        is HomeEntryTarget.SongTarget -> t.song.id.toString()
    }

private const val HomeBackdropPaletteWarmupDelayMillis = 350L

/**
 * Home's feed: the header, the user's sections in their order, then the
 * "Edit Home" footer, or the Hidden tray while editing. Home edit mode
 * happens here, in place (plan §2.1, §2.5): every section is a
 * [HomeEditBlock], one Initial-pass detector over the whole page enters and
 * drives it, and the strip stack folds over the feed while a block is
 * carried. [editController] is the shell's (null: a standalone one, for
 * previews and the debug harness); while it edits, its draft overrides
 * [sections]. [footerNewBadge] badges the footer while a new section waits
 * in the tray.
 */
@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
internal fun HomeEditorialContent(
    activities: List<ActivityEvent>,
    widgetGrid: List<HomeWidgetCard> = emptyList(),
    activityHeroFootnote: String? = null,
    recentlyAddedTracks: List<Track> = emptyList(),
    recentlyAddedAlbums: List<Album> = emptyList(),
    rediscover: List<HomeRediscoverItem> = emptyList(),
    // The header's Memories pill; null keeps today's bare chevron.
    memoryPill: HomeMemoryPill? = null,
    // Something above Home owns the screen (Now Playing, the detail column):
    // the Memories bubble keeps quiet.
    homeCovered: Boolean = false,
    sections: List<HomeSectionState> = HomeLayout.Default.sections,
    onNavigateToSettings: () -> Unit,
    onNavigateToMemories: () -> Unit,
    editController: HomeEditController? = null,
    footerNewBadge: Boolean = false,
    // Memory-flavoured grid cards open the deck stopped on a specific album
    // (by candidate sessionId). The chevron + pull-to-reveal stay generic via
    // onNavigateToMemories.
    onOpenMemoryFocus: (sessionId: Long) -> Unit = {},
    memoriesRevealState: RevealState = rememberRevealState(),
    onCommitMemoriesReveal: () -> Unit = {},
    onAlbumClick: (albumId: String, sharedTransitionKey: String?) -> Unit,
    onArtistClick: (artistId: String) -> Unit,
    onPlaylistClick: (playlistId: String) -> Unit,
    onSongClick: (Track) -> Unit,
    buildCoverArtUrl: (String) -> String,
    sharedTransitionScope: SharedTransitionScope? = null,
    animatedVisibilityScope: AnimatedVisibilityScope? = null,
    modifier: Modifier = Modifier,
) {
    val listState = rememberLazyListState()
    val haptics = rememberYoinHaptics()
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current

    // ── Edit mode (plan §2.1): the shell's controller; this page's specs,
    // motion, press, carry and hit targets. Specs resolve here, in the
    // Expressive role, and the motion is built for them: a reduced-motion
    // flip gives the page a fresh edit layer.
    val controller = editController ?: rememberStandaloneHomeEditController(HomeLayout(sections))
    val editReduced = rememberHomeEditReducedMotion()
    val currentEditReduced by rememberUpdatedState(editReduced)
    val specs = rememberHomeEditSpecs(editReduced)
    val editFeedback = remember(haptics) { haptics.asHomeEditFeedback() }
    val wiggleStyle = LocalHomeWiggleStyle.current
    val motion = remember(controller, specs, wiggleStyle, editFeedback) {
        HomeEditMotion(
            scope = scope,
            specs = specs,
            style = wiggleStyle,
            reduced = { currentEditReduced },
            progress = controller.progressReader,
            editing = { controller.isEditing },
            feedback = editFeedback,
            entering = { controller.isEditing && controller.progress.isAnimating },
        )
    }
    val press = remember { HomeEditPressState() }
    val engine = remember(controller, motion, density) {
        HomeCarryEngine(
            scope = scope,
            specs = specs,
            motion = motion,
            feedback = editFeedback,
            density = density,
            commitOrder = controller::commitOrder,
            finishDeferredExit = controller::finishDeferredExit,
        )
    }
    // The row resize (D1 ②): one driver per Activities / Jump Back In block,
    // written through the controller (persisted, one Undo step per change).
    val rowsEngine = remember(controller, specs, editFeedback, density, engine) {
        HomeRowsEngine(
            scope = scope,
            specs = specs,
            feedback = editFeedback,
            density = density,
            editing = { controller.isEditing },
            carrying = { engine.isCarrying || engine.liftSection != null },
            currentPreset = { section -> controller.draft?.rowsOf(section) ?: HomeRowPreset.Default },
            commit = controller::setRows,
        )
    }
    val targets = remember { HomeEditTargets() }
    val feedRefs = remember { HomeFeedRefs() }
    val editing = controller.isEditing
    val currentController by rememberUpdatedState(controller)

    var containerHeightPx by remember { mutableFloatStateOf(0f) }
    var isCommittedToMemories by remember { mutableStateOf(false) }
    // Visual hint = how far open the reveal is, capped at 1 so rubber-band
    // overshoot doesn't inflate the chevron.
    // Read in the hint's draw phase only: a pull frame must not recompose Home.
    val memoriesHintProgress: () -> Float = { (1f - memoriesRevealState.fraction).coerceIn(0f, 1f) }
    var allowBackdropPalette by remember { mutableStateOf(false) }
    val pullToMemoriesConnection = remember(listState, memoriesRevealState) {
        object : NestedScrollConnection {
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                // Editing: a pull at the top only scrolls; Memories stays shut.
                if (currentController.isEditing) return Offset.Zero
                if (source != NestedScrollSource.UserInput) {
                    return Offset.Zero
                }
                if (isCommittedToMemories) {
                    // Settle in flight — own the rest of this touch sequence
                    // so the next event doesn't fight the open animation.
                    return Offset(0f, available.y)
                }
                val pullingDownAtTop = available.y > 0f && listState.isAtTop()
                val pullingUpWhileEngaged = available.y < 0f && memoriesRevealState.fraction < 1f
                if (pullingDownAtTop || pullingUpWhileEngaged) {
                    memoriesRevealState.dragBy(available.y, containerHeightPx)
                    return Offset(0f, available.y)
                }
                return Offset.Zero
            }

            override suspend fun onPreFling(available: Velocity): Velocity {
                if (memoriesRevealState.fraction >= 1f) return Velocity.Zero
                isCommittedToMemories = true
                try {
                    val target = memoriesRevealState.settle(
                        velocityPxPerSec = available.y,
                        containerPx = containerHeightPx,
                    )
                    if (target <= 0f) {
                        haptics.performConfirm()
                        onCommitMemoriesReveal()
                    }
                } finally {
                    isCommittedToMemories = false
                }
                return available
            }
        }
    }
    LaunchedEffect(listState, allowBackdropPalette) {
        if (allowBackdropPalette) return@LaunchedEffect
        snapshotFlow { listState.isScrollInProgress }
            .collectLatest { isScrollInProgress ->
                if (!isScrollInProgress) {
                    delay(HomeBackdropPaletteWarmupDelayMillis)
                    allowBackdropPalette = true
                }
            }
    }
    val activityEntries = remember(activities, buildCoverArtUrl) {
        buildActivityEntries(
            activities = activities,
            buildCoverArtUrl = buildCoverArtUrl,
        )
    }
    // Keep a single stable dispatcher for entry clicks. Nav lambdas are held
    // via rememberUpdatedState so each call reaches the latest referenced
    // lambda without invalidating `remember`-cached entry lists.
    val onAlbumClickState = rememberUpdatedState(onAlbumClick)
    val onArtistClickState = rememberUpdatedState(onArtistClick)
    val onPlaylistClickState = rememberUpdatedState(onPlaylistClick)
    val onSongClickState = rememberUpdatedState(onSongClick)
    val onOpenMemoryFocusState = rememberUpdatedState(onOpenMemoryFocus)
    val onEntryClick = remember {
        { target: HomeEntryTarget ->
            when (target) {
                is HomeEntryTarget.Album -> onAlbumClickState.value(
                    target.albumId,
                    target.sharedTransitionKey,
                )
                is HomeEntryTarget.Artist -> onArtistClickState.value(target.artistId)
                is HomeEntryTarget.Playlist -> onPlaylistClickState.value(target.playlistId)
                is HomeEntryTarget.SongTarget -> onSongClickState.value(target.song)
            }
        }
    }
    val onWidgetCardClick = remember {
        { target: HomeWidgetTarget ->
            when (target) {
                is HomeWidgetTarget.AlbumDetail -> onAlbumClickState.value(target.albumId, null)
                is HomeWidgetTarget.PlaylistDetail -> onPlaylistClickState.value(target.playlistId)
                is HomeWidgetTarget.PlaySong -> onSongClickState.value(target.song)
                is HomeWidgetTarget.MemoryFocus -> onOpenMemoryFocusState.value(target.sessionId)
            }
        }
    }

    val shouldExtractBackdropColors = allowBackdropPalette && !listState.isScrollInProgress

    // Cold-start "启幕": one staged reveal for the above-the-fold sections
    // (hero bento → widget grid → recently added). Once per process per key —
    // rememberSaveable survives the covered-shell pause, so returning from a
    // detail page never replays it (motion audit: no repeat-visit staggers).
    // Gated on content arrival: the feed loads async, and a reveal fired
    // against the loading spinner is a reveal nobody sees.
    val firstReveal = rememberStagedReveal(
        key = "home-feed",
        // A resolved Memories pill counts too: on a profile with nothing in
        // the feed yet, it is the one thing to reveal.
        ready = activityEntries.isNotEmpty() || widgetGrid.isNotEmpty() ||
            recentlyAddedTracks.isNotEmpty() || recentlyAddedAlbums.isNotEmpty() ||
            rediscover.isNotEmpty() || memoryPill != null,
    )
    // Rediscover's "Not played in Yoin for …" is read against the moment its
    // list arrived; day-scale copy doesn't need a ticking clock.
    val rediscoverNowMillis = remember(rediscover) { System.currentTimeMillis() }
    // Jump Back In's height follows the pane width on Medium / Wide: while
    // that width moves, sections place 1:1 instead of chasing it on a spring.
    val paneWidthInMotion = LocalPaneWidthInMotion.current

    val navBarBottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    // Wide 全窗桌面态（owner A-prime 裁决 2026-07-28）：feed 不再夹 720dp
    // Feed 档，铺满画布 + 32dp 侧 gutter。Compact/Medium/Tabletop 走原路
    // （限宽 + 16dp 页边），逐字节不变。出血 shelf 的页边与 contentPadding
    // 同源，静止边继续贴住页边（no-midpage-truncation 纪律不破）。
    // 先判高度，再判宽度（断点交接 §4 / §14.6）：高 < 480 的手机横屏走单独的
    // 横屏档 —— 844 宽读成 Wide 也不能落到桌面档。
    val windowInfo = LocalYoinWindowInfo.current
    val isLandscapePhone = windowInfo.isCompactHeight
    // The page frame (Home density rules, owner 2026-10-03): the list runs the
    // container's full width and the margins are its content padding — 16dp
    // with the content capped at 688dp, 32dp on a Wide canvas, 24dp on a
    // landscape handset — so a shelf bleeds to the container's real edge.
    // Margins follow the live width in the layout pass (HomeFeedFrame).
    val feedFrame = rememberHomeFeedFrame(feedFrameClass(windowInfo))
    val itemSpacing = if (isLandscapePhone) 10.dp else 18.dp
    // The edit-mode plate (HomePlateVariant.kt): production never provides the
    // local, so this is the shipped V1; only the debug harness switches it.
    val plateVariant = LocalHomePlateVariant.current
    val spacingAnchor = remember { HomePlateSpacingAnchor() }
    val plateLook = remember(plateVariant, controller, feedFrame, spacingAnchor) {
        if (plateVariant == HomePlateVariant.V0) {
            HomePlateLook.Legacy
        } else {
            HomePlateLook(
                variant = plateVariant,
                progress = { spacingAnchor.progress(controller.progress.value) },
                pageMargin = { minOf(feedFrame.start, feedFrame.end) },
            )
        }
    }
    val editDeps = remember(controller, motion, press, engine, targets, specs, plateLook, rowsEngine) {
        HomeEditDeps(controller, motion, press, engine, targets, specs, plateLook, rowsEngine)
    }
    // V1 grows the feed's spacing with P: a block the entering long-press lifted
    // stays under the finger while it does (HomePlateSpacingAnchor), from the
    // lift until the finger lifts, a carry starts or P comes to rest.
    if (plateLook.variant == HomePlateVariant.V1) {
        LaunchedEffect(spacingAnchor, plateLook, controller, motion, listState, density, itemSpacing) {
            snapshotFlow {
                motion.heldSection?.takeIf { !motion.placeholdersAboveOpen && controller.progress.isAnimating }
            }.collectLatest { held ->
                if (held == null) return@collectLatest
                spacingAnchor.hold(
                    live = controller.progressReader,
                    spacingPx = { p -> with(density) { plateLook.spacingAt(itemSpacing, p).roundToPx() } },
                    gapsAbove = { listState.gapsAboveSection(held) },
                    scrollBy = { px -> listState.dispatchRawDelta(px) },
                )
            }
        }
    }
    // Seams (dissolve-final §1.3, §3): the status bar gets the tide line —
    // content sinks under two waves of page colour, text fades out just below
    // it — and the bottom bar gets the halftone field. Both read one set of
    // scroll followers.
    val seamFlow = remember { SeamFlow() }
    // The page runs the same gradient as Library (ExpressivePageBackground in
    // HomeScreen), so both seams ease toward those stops, not a flat colour.
    val seamBackground = expressivePageSeamBackground()
    // The tide line washes with the gradient's TOP stop, where it lives.
    val pageColor = MaterialTheme.colorScheme.surfaceContainer
    val statusBarTop = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    val statusBarPx = with(LocalDensity.current) { statusBarTop.toPx() }
    // The Memories entry (owner 2026-10-04): a safe-area arrow with a speech
    // bubble (HomeMemoryBubble.kt), or the trial's header pill / chevron.
    val memoryEntry = LocalHomeHintVariant.current.entry
    val bubbleController = if (memoryEntry == MemoryEntryStyle.Bubble) remember { MemoryBubbleController() } else null

    // ── Edit geometry, all in the page Box's px (the list sits at its origin).
    // The safe area runs from below the status tide to above the bar (port
    // sheet §3.4); one plate outset serves the hit tester, the carry and the blocks.
    val barField = LocalSeamBarField.current
    val safeGapPx = with(density) { HomeEditTokens.SafeGap.toPx() }
    val safeTopPx = with(density) { statusBarTop.toPx() + SeamDissolveTokens.TideRest.toPx() } + safeGapPx
    val safeBottomInsetPx = with(density) { navBarBottom.toPx() }
    val safeArea: () -> HomeEditSafeArea = remember(feedRefs, barField, safeTopPx, safeBottomInsetPx, safeGapPx) {
        {
            val box = feedRefs.box?.takeIf { it.isAttached }
            val barTop = barField?.takeIf { it.attached }?.bounds?.top
            val bottom = if (box != null && barTop != null) {
                barTop - box.positionInRoot().y
            } else {
                (box?.size?.height?.toFloat() ?: 0f) - safeBottomInsetPx
            }
            HomeEditSafeArea(top = safeTopPx, bottom = bottom - safeGapPx)
        }
    }
    val plateOutsetPx: () -> Size = remember(density, itemSpacing, plateLook) {
        if (plateLook.variant == HomePlateVariant.V0) {
            val outset = with(density) {
                Size(HomeEditTokens.PlateOutsetH.toPx(), plateOutsetVDp(itemSpacing.value).dp.toPx())
            }
            ({ outset })
        } else {
            // V1's (and the QA variants') outsets move with P and the page margin: read at each use.
            ({ with(density) { Size(plateLook.plateOutsetH().toPx(), plateLook.outsetV(itemSpacing).toPx()) } })
        }
    }
    val sectionTop: (HomeSection) -> Float? = remember(listState) {
        { section -> listState.sectionItemTop(section) }
    }
    val hitTester = remember(listState, targets, controller, motion, feedFrame, density, plateOutsetPx) {
        HomeEditHitTester(
            listState = listState,
            targets = targets,
            contentBounds = {
                val start = with(density) { feedFrame.start.toPx() }
                start..(start + with(density) { feedFrame.contentWidth.toPx() })
            },
            plateOutsetPx = plateOutsetPx,
            editing = { controller.isEditing },
            isHiding = { it in motion.hiding },
        )
    }
    val carryHost = rememberLazyListCarryHost(
        listState = listState,
        feedFrame = feedFrame,
        // The draft's order, never what happens to be laid out (critique 1).
        order = { controller.draft?.enabledSections.orEmpty() },
        emittedKeys = { feedRefs.keys },
        safeArea = safeArea,
        plateOutsetPx = plateOutsetPx,
    )
    val isFlingInProgress: () -> Boolean = remember(listState) { { listState.isScrollInProgress } }
    // P on the move, a carry, or a charge: vote the panel's high rate for exactly as long.
    // Read only in HomeFrameRateVote's scope: a flip (a charge, a carry starting or
    // ending, P settling) must not recompose the feed and every section in it.
    val editAnimating: () -> Boolean = remember(controller, engine, press, rowsEngine) {
        val animating = derivedStateOf {
            val p = controller.progress.value
            (p > EditAnimatingFloor && p < 1f - EditAnimatingFloor) || engine.session != null ||
                press.charge.isRunning || rowsEngine.resizing
        }
        ({ animating.value })
    }
    // Strips folding, or a carry: sections place 1:1 under the strips (port sheet §4.5).
    // While editing the feed places through one stable spec that reads this gate when
    // the list starts a move, so a fold never recomposes the sections (no composition read).
    // A block resizing its rows pushes the feed 1:1 as well (d1-rows.md §3.2).
    val editPlacement = remember(engine, specs, plateLook, rowsEngine) {
        GatedPlacementSpec(specs.placement) {
            engine.fold.value > 0f || engine.session != null || plateLook.spacingMoving() || rowsEngine.resizing
        }
    }
    // The V1 plate springs the feed's spacing with P: sections follow it 1:1
    // on the way out too, instead of chasing each frame on a spring.
    // A block still settling its rows after Done keeps the feed 1:1 under it too.
    val restPlacement = remember(specs, plateLook, rowsEngine) {
        GatedPlacementSpec(specs.placement) {
            (plateLook.variant == HomePlateVariant.V1 && plateLook.spacingMoving()) || rowsEngine.resizing
        }
    }
    val sectionPlacement: () -> FiniteAnimationSpec<IntOffset>? =
        remember(controller, feedFrame, paneWidthInMotion, specs, editPlacement, restPlacement) {
            {
                when {
                    paneWidthInMotion.value || feedFrame.isBlending -> null
                    controller.isEditing -> editPlacement
                    else -> restPlacement
                }
            }
        }

    // ── What the feed renders (plan §2.5): the controller's layout, the
    // sections with something to show (placeholders while editing), then the
    // tray or the footer. The keys go out in this order for the carry's anchor.
    val layout = controller.layoutToRender(HomeLayout(sections))
    val enabledOrder = layout.enabledSections
    // Derived, by value: a motion flag that leaves the blocks as they are (the
    // placeholders above a lifted block opening with none held back) recomposes nothing.
    val blocks by remember(layout, editing, motion, widgetGrid, recentlyAddedTracks, recentlyAddedAlbums, rediscover) {
        derivedStateOf(structuralEqualityPolicy()) {
            homeFeedBlocks(
                layout = layout,
                hiding = motion.hiding,
                editing = editing,
                held = motion.heldSection,
                placeholdersAboveOpen = motion.placeholdersAboveOpen,
                hasContent = { section ->
                    when (section) {
                        // Always there: its empty card says so.
                        HomeSection.Activities -> true
                        HomeSection.JumpBackIn -> widgetGrid.isNotEmpty()
                        HomeSection.RecentlyAdded -> recentlyAddedTracks.isNotEmpty() || recentlyAddedAlbums.isNotEmpty()
                        HomeSection.Rediscover -> rediscover.isNotEmpty()
                    }
                },
            )
        }
    }
    val trayMounted = motion.trayMounted
    val hiddenSections = layout.hiddenSections
    val allHidden = enabledOrder.isEmpty()
    val feedKeys = homeFeedKeys(blocks, trayMounted, hiddenSections, allHidden)
    SideEffect {
        val displayed = blocks.map { it.section }
        if (motion.displayed != displayed) motion.displayed = displayed
        feedRefs.keys = feedKeys
    }

    // The Home layer the controller drives (plan C4); a layer that arrives
    // while editing (an Activity recreation, a new motion) starts its session.
    val currentSafeArea by rememberUpdatedState(safeArea)
    DisposableEffect(controller, motion, engine, press, rowsEngine) {
        val layer = object : HomeEditLayer {
            override val isCarrying: Boolean get() = engine.isCarrying

            override fun onEnter(origin: HomeSection?, lifted: Boolean) = motion.onEnter(origin, lifted)

            override fun onExit(reason: HomeEditExitReason) {
                rowsEngine.onExit()
                engine.onExit(reason, press)
            }

            override fun onSnapExit() {
                rowsEngine.snapAll()
                engine.onSnapExit(press)
            }

            override fun onTouch() = motion.touch()

            override fun onLayoutChange(change: HomeEditChange) {
                motion.onLayoutChange(change) { section -> listState.sectionInSafeArea(section, currentSafeArea()) }
                // A changed preset (drop, step, Undo, Reset) springs its block to the new stop.
                rowsEngine.onLayoutChange(change.previous, change.next)
            }

            override fun deferExit(reason: HomeEditExitReason) = engine.deferExit(reason)

            override fun abortCarry() = engine.abortNow()

            override fun scrollToTray() {
                feedRefs.trayScroll?.cancel()
                feedRefs.trayScroll = scope.launch {
                    val index = feedRefs.keys.indexOf(TrayTitleKey)
                    if (index < 0) return@launch
                    val offsetPx = with(density) { HomeEditTokens.ScrollToTrayOffset.toPx() }
                    val info = listState.layoutInfo
                    val tray = info.visibleItemsInfo.firstOrNull { it.key == TrayTitleKey }
                    if (tray != null) {
                        listState.animateScrollBy(tray.offset - info.viewportStartOffset - offsetPx, specs.settlePx)
                    } else {
                        listState.animateScrollToItem(index, -offsetPx.roundToInt())
                    }
                }
            }
        }
        controller.layer = layer
        if (controller.isEditing) layer.onEnter(null, lifted = false)
        onDispose { controller.detachLayer(layer) }
    }
    DisposableEffect(engine, carryHost) {
        engine.host = carryHost
        onDispose { if (engine.host === carryHost) engine.host = null }
    }
    LaunchedEffect(motion) { motion.runEntryEffects() }
    HomeEditClock(motion)
    // The wiggle clock runs only while Home is on screen: resumed and not under Now Playing or the detail column.
    val lifecycleOwner = LocalLifecycleOwner.current
    val currentHomeCovered by rememberUpdatedState(homeCovered)
    LaunchedEffect(motion, lifecycleOwner) {
        lifecycleOwner.lifecycle.currentStateFlow
            .combine(snapshotFlow { currentHomeCovered }) { state, covered ->
                state.isAtLeast(Lifecycle.State.RESUMED) && !covered
            }
            .collect { motion.visible = it }
    }
    // The seam followers ignore the scroll the anchor makes under the strips,
    // and the one that keeps a held block under the finger (HomePlateSpacingAnchor).
    LaunchedEffect(engine, seamFlow, spacingAnchor) {
        snapshotFlow { engine.fold.value > 0f || spacingAnchor.holding }.collect { seamFlow.held = it }
    }
    // A carry folds every block into a strip: any row resize lands at rest first.
    LaunchedEffect(engine, rowsEngine) {
        snapshotFlow { engine.session != null || engine.liftSection != null }.collect { carrying ->
            if (carrying) rowsEngine.snapAll()
        }
    }
    // A held resize handle near the bar (or the status tide) scrolls the feed under it.
    DisposableEffect(rowsEngine, listState, feedRefs, density) {
        val edge = with(density) { HomeRowsTokens.AutoScrollEdge.toPx() }
        val max = with(density) { HomeRowsTokens.AutoScrollMax.toPx() }
        rowsEngine.autoScroll = object : HomeRowsAutoScroll {
            override fun speed(fingerRootY: Float): Float {
                val box = feedRefs.box?.takeIf { it.isAttached } ?: return 0f
                val y = fingerRootY - box.positionInRoot().y
                val safe = currentSafeArea()
                return when {
                    y > safe.bottom - edge -> max * homeRowsEdgeRamp((y - (safe.bottom - edge)) / edge)
                    y < safe.top + edge -> -max * homeRowsEdgeRamp(((safe.top + edge) - y) / edge)
                    else -> 0f
                }
            }

            override fun scrollBy(px: Float): Float = listState.dispatchRawDelta(px)
        }
        onDispose { rowsEngine.autoScroll = null }
    }
    // A new width class or a resize mid-drag invalidates the strips: let go where it is.
    LaunchedEffect(engine, feedFrame) {
        snapshotFlow { Pair(feedFrame.to, feedFrame.containerWidth) }
            .drop(1)
            .collect {
                if (engine.session != null) engine.release(cancelled = true, uptimeMs = SystemClock.uptimeMillis())
            }
    }
    // The strips compose once entering has settled, ahead of any carry, one
    // label a frame, and leave once an exit has: composed in a fold's first
    // frame they would stall it (HomeCarryStack).
    val stripsWarm = produceState(initialValue = 0, controller) {
        snapshotFlow { stripWarmth(editing = controller.isEditing, moving = controller.progress.isAnimating) }
            .collectLatest { warmth ->
                when (warmth) {
                    StripWarmth.Warm -> {
                        // Past the entry's own frames first.
                        repeat(StripWarmFrames) { withFrameNanos { } }
                        while (value < HomeSection.entries.size) {
                            value++
                            withFrameNanos { }
                        }
                    }
                    StripWarmth.Keep -> Unit
                    StripWarmth.Cold -> value = 0
                }
            }
    }
    val warmSections = remember(enabledOrder) { enabledOrder }
    val stripShapes = rememberHomeStripShapes()
    val stripCovers: (HomeSection) -> List<StripCover> = remember(
        activityEntries,
        widgetGrid,
        recentlyAddedAlbums,
        recentlyAddedTracks,
        rediscover,
        buildCoverArtUrl,
        stripShapes,
    ) {
        { section ->
            homeStripCovers(
                section = section,
                activityEntries = activityEntries,
                widgetGrid = widgetGrid,
                albums = recentlyAddedAlbums,
                tracks = recentlyAddedTracks,
                rediscover = rediscover,
                buildCoverArtUrl = buildCoverArtUrl,
                shapes = stripShapes,
            )
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .onPlaced { coordinates ->
                targets.attachBox(coordinates)
                feedRefs.box = coordinates
            }
            // Any press stops the bar's scroll to the tray.
            .pointerInput(feedRefs) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                    feedRefs.trayScroll?.cancel()
                }
            }
            .watchMemoryBubbleTouches(bubbleController)
            .homeEditGestures(
                controller = controller,
                motion = motion,
                press = press,
                engine = engine,
                hitTester = hitTester,
                feedback = editFeedback,
                isFlingInProgress = isFlingInProgress,
                specs = specs,
            )
            .seamTide(
                flow = seamFlow,
                color = pageColor,
                statusBarPx = statusBarPx,
            ) { listState.seamScrolledPx() },
    ) {
        // The vote's flag is read in its own scope (see editAnimating).
        HomeFrameRateVote(active = editAnimating, modifier = Modifier.fillMaxSize()) {
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .fillMaxSize()
                    .feedFoldWashOver(engine, listState, seamBackground)
                    .feedFrameWidth(feedFrame)
                    .onSizeChanged { containerHeightPx = it.height.toFloat().coerceAtLeast(1f) }
                    .seamDissolveViewport(
                        top = SeamTop.FadeText,
                        topInset = statusBarTop + SeamDissolveTokens.TideRest,
                        flow = seamFlow,
                        background = seamBackground,
                        remainingPx = { listState.seamRemainingPx() },
                    ) { listState.seamScrolledPx() }
                    .nestedScroll(pullToMemoriesConnection),
                // The landscape Button Group lives in the left cutout band, not at
                // the bottom: only the nav bar needs clearing there. No top
                // padding: the header carries it, so the carry's anchor can keep
                // the header out by item index alone.
                contentPadding = remember(feedFrame, isLandscapePhone, navBarBottom) {
                    FeedFramePadding(
                        frame = feedFrame,
                        top = 0.dp,
                        bottom = (if (isLandscapePhone) 16.dp else 108.dp) + navBarBottom,
                    )
                },
                verticalArrangement = remember(plateLook, itemSpacing) {
                    if (plateLook.variant == HomePlateVariant.V1) {
                        HomePlateSpacing(plateLook, itemSpacing)
                    } else {
                        Arrangement.spacedBy(itemSpacing)
                    }
                },
            ) {
                // The page header (title + nav icons) is pinned above the reorderable
                // sections — it's chrome, not a section.
                item(key = HomeHeaderKey) {
                    HomeContentHeader(
                        compact = isLandscapePhone,
                        bubbleController = bubbleController,
                        onNavigateToSettings = onNavigateToSettings,
                        onNavigateToMemories = onNavigateToMemories,
                        memoriesHintProgress = memoriesHintProgress,
                        memoryPill = memoryPill,
                        // The pill surfaces on the feed's last launch beat.
                        memoryPillReveal = { firstReveal.payload },
                        extractBackdropColors = shouldExtractBackdropColors,
                        onOpenMemoryFocus = { sessionId -> onOpenMemoryFocusState.value(sessionId) },
                        editProgress = controller.progressReader,
                        editTargets = targets,
                        editHint = controller.sessionHints.showHeaderHint,
                        onEnterEdit = { controller.enter(null, lifted = false) },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }

                // Data-driven feed: each section the user keeps on, in their order,
                // each one an edit block.
                for (block in blocks) {
                    val section = block.section
                    item(key = sectionItemKey(section)) {
                        HomeEditBlock(
                            section = section,
                            displayIndex = enabledOrder.indexOf(section).coerceAtLeast(0),
                            displayCount = enabledOrder.size,
                            deps = editDeps,
                            placeholder = block.placeholder,
                            // No cards to sway: the block turns as one.
                            wholeBlockWiggle = block.placeholder ||
                                (section == HomeSection.Activities && activityEntries.isEmpty()),
                            itemSpacing = itemSpacing,
                            blockTopInBox = { sectionTop(section) },
                            safeArea = safeArea,
                            modifier = Modifier.animateItem(
                                // A section edit mode shows fades in by its own alpha (critique 10).
                                fadeInSpec = if (section in motion.showing) null else specs.effectsIn,
                                placementSpec = sectionPlacement(),
                                fadeOutSpec = specs.effectsOut,
                            ),
                        ) {
                            when (section) {
                                HomeSection.Activities -> if (activityEntries.isNotEmpty()) {
                                    // Density by width (owner 2026-10-03): the bento's
                                    // recipe and item count follow the container's feed
                                    // units (HomeFeedDensity.kt), not LayoutMode. The
                                    // phone and landscape compositions are today's.
                                    val unitsRecipe = !isLandscapePhone && windowInfo.feedUnits >= 3
                                    // Deep enough for the XL preset; the hero is still
                                    // found where it always was (the first 6 / 16), so
                                    // every other preset seats exactly what it did.
                                    val bentoEntries = remember(activities, buildCoverArtUrl, unitsRecipe) {
                                        buildActivityEntries(
                                            activities = activities,
                                            buildCoverArtUrl = buildCoverArtUrl,
                                            limit = if (unitsRecipe) {
                                                ActivityBentoUnitsXlEntries
                                            } else {
                                                ActivityBentoPhoneXlEntries
                                            },
                                        )
                                    }
                                    val heroWindow =
                                        if (unitsRecipe) ActivityBentoUnitsMaxEntries else ActivityBentoPhoneEntries
                                    // Hero slot = first album/playlist; artists fill the
                                    // smaller cards in recency order.
                                    val heroEntry = remember(bentoEntries, heroWindow) {
                                        bentoEntries.take(heroWindow).firstOrNull { entry -> entry.isHeroCandidate }
                                    }
                                    // The stagger is seeded by the hero's identity: the
                                    // same feed lays out the same way every time.
                                    // Remembered, like every argument below: the feed
                                    // recomposes on edit-mode flags, and fresh lists, specs
                                    // or modifiers would recompose the whole bento with it.
                                    val bentoSpec = remember(windowInfo.feedUnits, isLandscapePhone, heroEntry) {
                                        activityBentoSpec(
                                            feedUnits = windowInfo.feedUnits,
                                            isCompactHeight = isLandscapePhone,
                                            seed = activityLayoutSeed(heroEntry?.layoutKey),
                                            hasHero = heroEntry != null,
                                        )
                                    }
                                    val candidates = remember(bentoEntries, heroEntry) {
                                        bentoEntries.filterNot { it === heroEntry }
                                    }
                                    val bentoModifier = remember(firstReveal) {
                                        Modifier
                                            .fillMaxWidth()
                                            .stagedBeat(
                                                progress = { firstReveal.hero },
                                                rise = 18.dp,
                                                scaleFrom = 0.97f,
                                            )
                                    }
                                    ActivityBento(
                                        hero = heroEntry,
                                        candidates = candidates,
                                        spec = bentoSpec,
                                        preset = layout.rowsOf(HomeSection.Activities),
                                        heroFootnoteExtra = activityHeroFootnote,
                                        extractBackdropColors = shouldExtractBackdropColors,
                                        onEntryClick = onEntryClick,
                                        modifier = bentoModifier,
                                    )
                                } else {
                                    HomeEmptyCard(
                                        title = "No recent activity yet",
                                        supporting = "Once you listen or visit albums and artists, this feed will start filling in.",
                                        modifier = Modifier.fillMaxWidth(),
                                    )
                                }

                                // The merged Jump Back In × memories widget grid.
                                // Empty, it only shows (as a placeholder) while editing.
                                HomeSection.JumpBackIn -> HomeWidgetGridSection(
                                    title = "Jump Back In",
                                    cards = widgetGrid,
                                    rows = layout.rowsOf(HomeSection.JumpBackIn),
                                    extractBackdropColors = shouldExtractBackdropColors,
                                    onCardClick = onWidgetCardClick,
                                    modifier = remember(firstReveal) {
                                        Modifier
                                            .fillMaxWidth()
                                            .stagedBeat(
                                                progress = { firstReveal.meta },
                                                rise = 16.dp,
                                            )
                                    },
                                )

                                // Only with something added this week — an empty
                                // "recently added" shelf is noise, not information.
                                HomeSection.RecentlyAdded -> RecentlyAddedSection(
                                    tracks = recentlyAddedTracks,
                                    albums = recentlyAddedAlbums,
                                    extractBackdropColors = shouldExtractBackdropColors,
                                    onTrackClick = { track -> onEntryClick(HomeEntryTarget.SongTarget(track)) },
                                    onAlbumClick = { album ->
                                        onEntryClick(HomeEntryTarget.Album(album.id.toString(), null))
                                    },
                                    buildCoverArtUrl = buildCoverArtUrl,
                                    frame = feedFrame,
                                    singleRowShelf = isLandscapePhone,
                                    shelfScrollEnabled = !editing,
                                    modifier = remember(firstReveal) {
                                        Modifier
                                            .fillMaxWidth()
                                            .stagedBeat(
                                                progress = { firstReveal.payload },
                                                rise = 16.dp,
                                            )
                                    },
                                )

                                // Only with something to bring back: an empty
                                // Rediscover is no information either.
                                HomeSection.Rediscover -> RediscoverSection(
                                    items = rediscover,
                                    frame = feedFrame,
                                    nowMillis = rediscoverNowMillis,
                                    extractBackdropColors = shouldExtractBackdropColors,
                                    scrollEnabled = !editing,
                                    onAlbumClick = { albumId, sharedTransitionKey ->
                                        onEntryClick(HomeEntryTarget.Album(albumId, sharedTransitionKey))
                                    },
                                    onSongClick = { song -> onEntryClick(HomeEntryTarget.SongTarget(song)) },
                                    modifier = remember(firstReveal) {
                                        Modifier
                                            .fillMaxWidth()
                                            .stagedBeat(
                                                progress = { firstReveal.payload },
                                                rise = 16.dp,
                                            )
                                    },
                                )
                            }
                        }
                    }
                }

                // Editing: the Hidden tray, Show and Reset. Otherwise the way in,
                // after the all-hidden card when nothing is left on.
                if (trayMounted) {
                    homeEditTrayItems(
                        hidden = hiddenSections,
                        newBadges = controller.sessionHints.newBadges,
                        canReset = controller.canReset,
                        deps = editDeps,
                        placementSpec = sectionPlacement,
                        itemSpacing = itemSpacing,
                    )
                } else {
                    if (allHidden) homeAllHiddenItem(placementSpec = sectionPlacement, alpha = { motion.footerAlpha.value })
                    homeEditFooterEntry(
                        newBadge = footerNewBadge,
                        deps = editDeps,
                        onEnter = { controller.enter(motion.displayed.lastOrNull(), lifted = false) },
                        placementSpec = sectionPlacement,
                    )
                }
            }
            // The strips fold over the feed, inside the tide (port sheet §0.2 item 10).
            HomeCarryStack(
                engine = engine,
                covers = stripCovers,
                sections = warmSections,
                warmCount = { stripsWarm.value },
            )
            if (bubbleController != null) {
                MemoryBubbleOverlay(
                    pill = memoryPill,
                    controller = bubbleController,
                    hintProgress = memoriesHintProgress,
                    // It speaks once the feed's launch reveal has landed.
                    revealProgress = { firstReveal.payload },
                    scrolledPx = { listState.seamScrolledPx() },
                    covered = homeCovered,
                    extractBackdropColors = shouldExtractBackdropColors,
                    onOpenMemoryFocus = { sessionId -> onOpenMemoryFocusState.value(sessionId) },
                    onNavigateToMemories = onNavigateToMemories,
                    modifier = Modifier.matchParentSize(),
                    editProgress = controller.progressReader,
                    editing = editing,
                )
            }
        }
    }
}

// ── The feed's item model (plan §2.5) ────────────────────────────────────

/** One section the feed renders; [placeholder] = empty, shown while editing (spec §2.4). */
@Immutable
internal data class HomeFeedBlock(val section: HomeSection, val placeholder: Boolean)

/**
 * The sections the feed renders, in [layout] order: the enabled ones, plus
 * those still fading out after Hide ([hiding]). Outside edit mode a section
 * with nothing to show is skipped ([hasContent]; Activities always has its
 * empty card). While [editing] it becomes a placeholder — except one above
 * the block lifted at entry ([held]) until [placeholdersAboveOpen], so the
 * lifted block isn't pushed from under the finger (spec §2.1.4-7).
 */
internal fun homeFeedBlocks(
    layout: HomeLayout,
    hiding: Set<HomeSection>,
    editing: Boolean,
    held: HomeSection?,
    placeholdersAboveOpen: Boolean,
    hasContent: (HomeSection) -> Boolean,
): List<HomeFeedBlock> {
    val heldIndex = layout.sections.indexOfFirst { it.section == held }
    return layout.sections.mapIndexedNotNull { index, state ->
        val section = state.section
        when {
            !state.enabled && section !in hiding -> null
            hasContent(section) -> HomeFeedBlock(section, placeholder = false)
            !editing -> null
            !placeholdersAboveOpen && index < heldIndex -> null
            else -> HomeFeedBlock(section, placeholder = true)
        }
    }
}

/**
 * The feed's item keys, in the order the list emits them: the header, the
 * [blocks], then the tray while [trayMounted], else the all-hidden card (for
 * [allHidden]) and the footer entry.
 */
internal fun homeFeedKeys(
    blocks: List<HomeFeedBlock>,
    trayMounted: Boolean,
    hidden: List<HomeSection>,
    allHidden: Boolean,
): List<Any> = buildList {
    add(HomeHeaderKey)
    blocks.forEach { add(sectionItemKey(it.section)) }
    if (trayMounted) {
        add(TrayTitleKey)
        hidden.forEach { add(homeEditTrayRowKey(it)) }
        add(TrayFooterKey)
    } else {
        if (allHidden) add(AllHiddenKey)
        add(FooterEntryKey)
    }
}

/** A section's item key: edit mode reads the section back from it (`HomeSection.fromId`). */
private fun sectionItemKey(section: HomeSection): String = carryItemKey(section)

private const val HomeHeaderKey = "home-header"

// P this close to rest counts as still (port sheet §2.5).
private const val EditAnimatingFloor = .001f

/**
 * The feed fading under the folding strips (port sheet §4.5): a wash of the
 * page's own gradient ([background], over the page's height) drawn over the
 * sections and tray, from the header's bottom down; the header never fades.
 * It stands in for each block's layer alpha (the blocks only switch off once
 * it covers them, `feedFoldShown`), so a fade frame costs one gradient
 * rect, not an offscreen pass per block. Drawn only mid-fade.
 */
private fun Modifier.feedFoldWashOver(
    engine: HomeCarryEngine,
    listState: LazyListState,
    background: SeamBackground,
): Modifier = drawWithCache {
    val brush = Brush.verticalGradient(background.colors, startY = 0f, endY = size.height)
    onDrawWithContent {
        drawContent()
        val wash = feedFoldWash(engine.fold.value)
        if (wash <= 0f || wash >= 1f) return@onDrawWithContent
        val top = listState.headerBottomPx().coerceIn(0f, size.height)
        drawRect(brush, topLeft = Offset(0f, top), size = Size(size.width, size.height - top), alpha = wash)
    }
}

/** The header's bottom in the list's px, 0 once it has scrolled away (the list sits at the Box origin). */
private fun LazyListState.headerBottomPx(): Float {
    val info = layoutInfo
    val header = info.visibleItemsInfo.firstOrNull { it.key == HomeHeaderKey } ?: return 0f
    return (header.offset - info.viewportStartOffset + header.size).toFloat()
}

/**
 * The page's content under a high frame-rate vote while [active]. The flag
 * is read here, in this scope only, so a flip never recomposes [content].
 * The vote stays in the chain and only its category changes: adding or
 * dropping it adds or drops a layer over the whole feed, which repaints it
 * and resends its semantics (a long content-capture pass) right as a carry
 * starts or ends.
 */
@Composable
private fun HomeFrameRateVote(
    active: () -> Boolean,
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit,
) {
    val category = if (active()) FrameRateCategory.High else FrameRateCategory.Default
    Box(modifier.preferredFrameRate(category), content = content)
}

/** Plain references the edit layer reads at events and in draw; never state, never composed. */
private class HomeFeedRefs {
    /** The page Box, the space every edit geometry lives in. */
    var box: LayoutCoordinates? = null

    /** The item keys the list emits, in order (published after each composition). */
    var keys: List<Any> = emptyList()

    /** The bar's Add scrolling to the tray; any press stops it. */
    var trayScroll: Job? = null
}

/** [section]'s item top in the page Box, null when not laid out (the list sits at the Box origin). */
private fun LazyListState.sectionItemTop(section: HomeSection): Float? {
    val info = layoutInfo
    val key = sectionItemKey(section)
    return info.visibleItemsInfo.firstOrNull { it.key == key }?.let { (it.offset - info.viewportStartOffset).toFloat() }
}

/**
 * The item gaps between the feed's first visible item (the one the list holds
 * in place) and [section]'s block; 0 when the block isn't laid out.
 */
private fun LazyListState.gapsAboveSection(section: HomeSection): Int {
    val key = sectionItemKey(section)
    val item = layoutInfo.visibleItemsInfo.firstOrNull { it.key == key } ?: return 0
    return (item.index - firstVisibleItemIndex).coerceAtLeast(0)
}

/** [section]'s block shows between the status tide and the bar (proto inViewport). */
private fun LazyListState.sectionInSafeArea(section: HomeSection, safe: HomeEditSafeArea): Boolean {
    val info = layoutInfo
    val key = sectionItemKey(section)
    val item = info.visibleItemsInfo.firstOrNull { it.key == key } ?: return false
    val top = (item.offset - info.viewportStartOffset).toFloat()
    return top + item.size > safe.top && top < safe.bottom
}

private val HomeMomentEntry.isHeroCandidate: Boolean
    get() = entityType == ActivityEntityType.ALBUM.name || entityType == ActivityEntityType.PLAYLIST.name

/** The entity backdrop shapes a strip's covers sit in (the cards' own: Bun, Circle, Ghostish). */
@Immutable
private class HomeStripShapes(val album: Shape, val song: Shape, val playlist: Shape) {
    fun of(kind: WidgetShapeKind): Shape = when (kind) {
        WidgetShapeKind.Album -> album
        WidgetShapeKind.Song -> song
        WidgetShapeKind.Playlist -> playlist
        WidgetShapeKind.Artist -> CircleShape
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun rememberHomeStripShapes(): HomeStripShapes {
    val album = MaterialShapes.Bun.toShape()
    val song = MaterialShapes.Circle.toShape()
    val playlist = MaterialShapes.Ghostish.toShape()
    return remember(album, song, playlist) { HomeStripShapes(album, song, playlist) }
}

/**
 * A section's strip covers (port sheet §4.4): the first three it shows.
 * Activities: the hero, then the next two entries; Jump Back In: the memory
 * card, then the next two; Recently Added: its first albums, else its
 * tracks (Thumb); Rediscover: its first three.
 */
private fun homeStripCovers(
    section: HomeSection,
    activityEntries: List<HomeMomentEntry>,
    widgetGrid: List<HomeWidgetCard>,
    albums: List<Album>,
    tracks: List<Track>,
    rediscover: List<HomeRediscoverItem>,
    buildCoverArtUrl: (String) -> String,
    shapes: HomeStripShapes,
): List<StripCover> {
    val count = HomeEditTokens.StripCoverCount
    return when (section) {
        HomeSection.Activities -> {
            val hero = activityEntries.firstOrNull { it.isHeroCandidate }
            (listOfNotNull(hero) + activityEntries.filterNot { it === hero }).take(count).map { entry ->
                stripCover(entry.coverArtUrl, shapes.of(widgetShapeKindForActivity(entry.entityType)))
            }
        }
        HomeSection.JumpBackIn -> {
            val memory = widgetGrid.firstOrNull { it.target is HomeWidgetTarget.MemoryFocus }
            (listOfNotNull(memory) + widgetGrid.filterNot { it === memory }).take(count).map { card ->
                stripCover(card.coverArtUrl, shapes.of(card.entityType.toWidgetShapeKind()))
            }
        }
        HomeSection.RecentlyAdded -> if (albums.isNotEmpty()) {
            albums.take(count).map { stripCover(resolveHomeCoverArtUrl(it.coverArt, buildCoverArtUrl), shapes.album) }
        } else {
            tracks.take(count).map { track ->
                stripCover(recentlyAddedTrackCoverUrl(track, buildCoverArtUrl), YoinArtworkShapes.Thumb)
            }
        }
        HomeSection.Rediscover -> rediscover.take(count).map { item ->
            stripCover(item.coverArtUrl, if (item.song != null) shapes.song else shapes.album)
        }
    }
}

@Composable
private fun HomeContentHeader(
    onNavigateToSettings: () -> Unit,
    onNavigateToMemories: () -> Unit,
    memoriesHintProgress: () -> Float,
    // Edit mode's P (read in draw and layout only), its hit targets, and the
    // TalkBack way in from the title.
    editProgress: () -> Float,
    editTargets: HomeEditTargets,
    onEnterEdit: () -> Unit,
    modifier: Modifier = Modifier,
    // The first edit sessions: "Drag to reorder" in the free span.
    editHint: Boolean = false,
    // Non-null = the Memories entry is the safe-area bubble overlay: the
    // header only reports the span it leaves between the title and Settings.
    bubbleController: MemoryBubbleController? = null,
    memoryPill: HomeMemoryPill? = null,
    memoryPillReveal: () -> Float = { 1f },
    extractBackdropColors: Boolean = false,
    onOpenMemoryFocus: (sessionId: Long) -> Unit = {},
    // Landscape handset: a 28sp title (LandscapeHome).
    compact: Boolean = false,
) {
    val haptics = rememberYoinHaptics()
    // Edit mode fades the icons out over the first half of P; they stay
    // composed (the row keeps its 48dp) and stop taking input and focus.
    val iconsEnabled = rememberHomeEditIconsEnabled(editProgress)
    val iconSemantics = if (iconsEnabled) Modifier else Modifier.clearAndSetSemantics {}
    Row(
        modifier = modifier
            .statusBarsPadding()
            // 8dp, plus the 4dp the list's top padding used to add.
            .padding(top = 12.dp)
            .heightIn(min = 48.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val titleStyle = MaterialTheme.typography.let { if (compact) it.headlineMedium else it.headlineLarge }
        // "Home", and "Edit Home" over it through P. Display type fades over
        // 0.75 × its size (≈24dp at 32sp) instead of looking sliced by the
        // short text band.
        HomeEditHeaderTitle(
            progress = editProgress,
            style = titleStyle,
            modifier = Modifier.padding(end = HomeHeaderTitleBreathing),
            onEnterEdit = onEnterEdit,
        )
        if (bubbleController != null) {
            // The bubble overlay hangs inside this span; the edit hint ends it.
            Box(
                modifier = Modifier
                    .weight(1f)
                    .memoryBubbleFreeSpan(bubbleController),
                contentAlignment = Alignment.CenterEnd,
            ) {
                HomeEditHeaderHint(progress = editProgress, visible = editHint, titleStyle = titleStyle)
            }
        } else {
            // The Memories entry takes whatever the title and Settings leave, and
            // picks the pill form that fits it (HomeMemoryPill's fit rule).
            Box(
                modifier = Modifier.weight(1f),
                contentAlignment = Alignment.CenterEnd,
            ) {
                HomeMemoryEntry(
                    pill = memoryPill,
                    hintProgress = memoriesHintProgress,
                    revealProgress = memoryPillReveal,
                    extractBackdropColors = extractBackdropColors,
                    onOpenMemoryFocus = onOpenMemoryFocus,
                    onNavigateToMemories = onNavigateToMemories,
                    modifier = Modifier
                        .homeEditExclusion(editTargets, MemoriesEntryTarget) { iconsEnabled }
                        .homeEditHeaderIcon(editProgress)
                        .then(iconSemantics),
                )
                HomeEditHeaderHint(progress = editProgress, visible = editHint, titleStyle = titleStyle)
            }
        }
        Spacer(modifier = Modifier.width(2.dp))
        IconButton(
            onClick = {
                haptics.performContextClick()
                onNavigateToSettings()
            },
            enabled = iconsEnabled,
            modifier = Modifier
                .homeEditExclusion(editTargets, SettingsTarget) { iconsEnabled }
                .homeEditHeaderIcon(editProgress)
                .seamFade()
                .then(iconSemantics),
        ) {
            Icon(
                imageVector = YoinSymbols.Settings,
                contentDescription = "Settings",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

// The header's controls, as edit-mode exclusions: presses on them stay theirs.
private const val SettingsTarget = "gear"
private const val MemoriesEntryTarget = "memories"

// ── Activities bento (Figma node 405:362) ──────────────────────────────
//
// Four recent activities in a bento of decreasing prominence: a full-width
// hero, a small square + wide card row, and a single-line strip. Each card's
// container is tonally derived from its own cover art, echoing the mockup's
// per-card colour washes.

/** One bento composition with the entries it seats — the outgoing layer keeps its own while it fades. */
private data class BentoContent(
    val spec: ActivityBentoSpec,
    val hero: HomeMomentEntry?,
    val candidates: List<HomeMomentEntry>,
    // The row preset (D1). Not part of the crossfade's key: a preset change
    // updates in place (edit mode's resize interpolates it card by card).
    val preset: HomeRowPreset,
)

/** Enough recent entries to fill the widest bento (13 slots) and still find an album / playlist hero. */
private const val ActivityBentoUnitsMaxEntries = 16

/** The widest XL bento (a fourth row of up to four more) — the hero is still found in the first [ActivityBentoUnitsMaxEntries]. */
private const val ActivityBentoUnitsXlEntries = 20

/** The phone pipeline's cap (hero + 3 supporting from the top 6), and XL's (hero + 5 from the top 8). */
private const val ActivityBentoPhoneEntries = 6
private const val ActivityBentoPhoneXlEntries = 8

@Composable
private fun ActivityBento(
    // The hero slot only carries an album / playlist (or nothing); the
    // supporting cards take the rest in recency order. [candidates] is every
    // supporting entry on offer — each composition takes the share its own
    // [spec] and [preset] seat, so an outgoing composition keeps its cards
    // while it fades. Phone: [0] small square, [1] wide, [2] strip at L. Units
    // (feed units ≥ 3, owner 2026-10-03): the count follows the width and the
    // rows stagger — see HomeFeedDensity.kt. Row presets: HomeRowPresets.kt.
    hero: HomeMomentEntry?,
    candidates: List<HomeMomentEntry>,
    spec: ActivityBentoSpec,
    preset: HomeRowPreset,
    heroFootnoteExtra: String?,
    extractBackdropColors: Boolean,
    onEntryClick: (HomeEntryTarget) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        HomeSectionTitle(
            text = "Activities",
            modifier = Modifier.padding(bottom = 6.dp),
        )
        // Fixed row heights, scaled with the user's font size: IntrinsicSize
        // would crash here — MarqueeTitle's BoxWithConstraints is a
        // SubcomposeLayout, which cannot answer intrinsic measurements.
        val fontScale = LocalDensity.current.fontScale.coerceAtLeast(1f)
        val reduced = LocalMotionProfile.current == MotionProfile.AdaptiveReduced
        val heightSpec = YoinMotion.spatialSpring<Float>()
        val cardScope = LocalHomeEditCardScope.current
        // A width class (or the stagger seed) changes the composition: the old
        // one fades out — with its OWN entries — while the new fades in, and
        // the section's height springs between them — never a hard cut. Data
        // changes inside one composition update in place (contentKey), and so
        // does a row preset.
        AnimatedContent(
            targetState = BentoContent(spec, hero, candidates, preset),
            contentKey = { it.spec },
            transitionSpec = {
                if (reduced) {
                    ContentTransform(EnterTransition.None, ExitTransition.None, sizeTransform = null)
                } else {
                    // No SizeTransform: its animated WIDTH lags a container
                    // that is shrinking under a column spring, and the parent
                    // centres the overflow. springHeight eases the height.
                    YoinMotion.fadeIn(role = YoinMotionRole.Standard) togetherWith
                        YoinMotion.fadeOut(role = YoinMotionRole.Standard) using null
                }
            },
            label = "activityBento",
            modifier = Modifier.springHeight(spec = heightSpec, key = spec, enabled = !reduced),
        ) { state ->
            val s = state.spec
            val hero = state.hero
            val ladder = remember(s, hero != null, state.candidates.size) {
                activityRowLadder(s, hasHero = hero != null, supportingCount = state.candidates.size)
            }
            Box(Modifier.heightOfIncomingOnly { transition.targetState == EnterExitState.PostExit }) {
                HomeRowsBody(
                    track = cardScope?.rows,
                    engine = cardScope?.rowsEngine,
                    ladder = ladder,
                    preset = state.preset,
                    reduced = reduced,
                    owner = transition.targetState != EnterExitState.PostExit,
                ) { stop ->
                    val composition = stop.payload
                    // Without a hero, row 1's lead slot is a supporting entry too.
                    val supporting = state.candidates.take(composition.seated)
                    if (s.recipe == BentoRecipe.Units) {
                        ActivityUnitGrid(
                            spec = s,
                            slots = composition.slots,
                            hero = hero,
                            supporting = supporting,
                            heroFootnoteExtra = heroFootnoteExtra,
                            extractBackdropColors = extractBackdropColors,
                            onEntryClick = onEntryClick,
                            fontScale = fontScale,
                        )
                    } else {
                        ActivityBentoRows(
                            rows = composition.rows,
                            hero = hero,
                            supporting = supporting,
                            heroFootnoteExtra = heroFootnoteExtra,
                            extractBackdropColors = extractBackdropColors,
                            onEntryClick = onEntryClick,
                            fontScale = fontScale,
                        )
                    }
                }
            }
        }
    }
}

/**
 * The code-built bento (feed units ≤ 2 and the landscape phone) from its
 * [rows] (activityCodeRows): Phone — a full-width hero, a small square + wide
 * row, one strip; PhoneNarrow (feed units 1 — a ~330dp column beside the Now
 * Playing panel on a foldable) splits the card row into two equal smalls so
 * the small never shrinks to ~96dp; Landscape — one row, so the first screen
 * keeps Recently Added in view. At the default preset each is today's
 * composition. A slot past the supply is a spacer of its weight. Edit-mode
 * card order: the hero 0, then each supporting entry at its index + 1.
 */
@Composable
private fun ActivityBentoRows(
    rows: List<ActivityCodeRow>,
    hero: HomeMomentEntry?,
    supporting: List<HomeMomentEntry>,
    heroFootnoteExtra: String?,
    extractBackdropColors: Boolean,
    onEntryClick: (HomeEntryTarget) -> Unit,
    fontScale: Float,
) {
    @Composable
    fun Card(entry: HomeMomentEntry, kind: SlotKind, editIndex: Int, modifier: Modifier) {
        val cardModifier = Modifier
            .homeRowsCard(entry.stableId, kind)
            .homeEditCard(editIndex)
            .then(modifier)
        val onClick = { onEntryClick(entry.target) }
        when (kind) {
            SlotKind.Hero -> ActivityHeroCard(
                entry = entry,
                footnoteExtra = heroFootnoteExtra,
                extractBackdropColors = extractBackdropColors,
                onClick = onClick,
                modifier = cardModifier,
            )
            SlotKind.Wide -> ActivityWideCard(
                entry = entry,
                extractBackdropColors = extractBackdropColors,
                onClick = onClick,
                modifier = cardModifier,
            )
            SlotKind.Small -> ActivitySmallCard(
                entry = entry,
                extractBackdropColors = extractBackdropColors,
                onClick = onClick,
                modifier = cardModifier,
            )
            SlotKind.Strip -> ActivityStripCard(
                entry = entry,
                extractBackdropColors = extractBackdropColors,
                onClick = onClick,
                modifier = cardModifier,
            )
        }
    }

    fun entryOf(slot: ActivityCodeSlot): HomeMomentEntry? =
        if (slot.entryIndex < 0) hero else supporting.getOrNull(slot.entryIndex)

    Column(verticalArrangement = Arrangement.spacedBy(ActivityBentoGap)) {
        rows.forEach { row ->
            when (row.shape) {
                ActivityRowShape.Hero, ActivityRowShape.Strip -> {
                    val slot = row.slots.first()
                    entryOf(slot)?.let { entry ->
                        Card(entry, slot.kind, slot.entryIndex + 1, Modifier.fillMaxWidth())
                    }
                }
                ActivityRowShape.Cards -> Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(row.height * fontScale),
                    horizontalArrangement = Arrangement.spacedBy(row.gap),
                ) {
                    row.slots.forEach { slot ->
                        val entry = entryOf(slot)
                        if (entry != null) {
                            Card(entry, slot.kind, slot.entryIndex + 1, Modifier.weight(slot.weight).fillMaxHeight())
                        } else {
                            // Keep the cards beside it at their width instead of stretching across the gap.
                            Spacer(modifier = Modifier.weight(slot.weight))
                        }
                    }
                }
            }
        }
    }
}

/**
 * The bento on true feed units (feed units ≥ 3): each card sits on the unit
 * grid ([slots], activityUnitSlots at the row preset) — a small on one unit,
 * a wide (or the hero) on two or three — at fixed row heights (row 1
 * [ActivityBentoSpec.row1Height], the others 118dp, × fontScale), strips
 * sharing the last row equally. One layout node per card; widths come from the
 * live width in the measure pass only, so a column or side-panel spring never
 * recomposes the bento.
 */
@Composable
private fun ActivityUnitGrid(
    spec: ActivityBentoSpec,
    slots: List<ActivitySlot>,
    hero: HomeMomentEntry?,
    supporting: List<HomeMomentEntry>,
    heroFootnoteExtra: String?,
    extractBackdropColors: Boolean,
    onEntryClick: (HomeEntryTarget) -> Unit,
    fontScale: Float,
) {
    val placed = slots.mapNotNull { slot ->
        val entry = if (slot.kind == SlotKind.Hero) hero else supporting.getOrNull(slot.entryIndex)
        entry?.let { slot to it }
    }
    Layout(
        content = {
            placed.forEachIndexed { index, (slot, entry) ->
                key(entry.stableId) {
                    val onClick = { onEntryClick(entry.target) }
                    // Edit-mode card order = placement order.
                    val editCard = Modifier
                        .homeRowsCard(entry.stableId, slot.kind)
                        .homeEditCard(index)
                    when (slot.kind) {
                        SlotKind.Hero -> ActivityHeroCard(
                            entry = entry,
                            footnoteExtra = heroFootnoteExtra,
                            extractBackdropColors = extractBackdropColors,
                            onClick = onClick,
                            modifier = editCard,
                        )
                        SlotKind.Wide -> ActivityWideCard(
                            entry = entry,
                            extractBackdropColors = extractBackdropColors,
                            onClick = onClick,
                            modifier = editCard,
                        )
                        SlotKind.Small -> ActivitySmallCard(
                            entry = entry,
                            extractBackdropColors = extractBackdropColors,
                            onClick = onClick,
                            modifier = editCard,
                        )
                        SlotKind.Strip -> ActivityStripCard(
                            entry = entry,
                            extractBackdropColors = extractBackdropColors,
                            onClick = onClick,
                            modifier = editCard,
                        )
                    }
                }
            }
        },
    ) { measurables, constraints ->
        val width = if (constraints.hasBoundedWidth) constraints.maxWidth else constraints.minWidth
        val gapPx = ActivityBentoGap.toPx()
        val gap = ActivityBentoGap.roundToPx()
        val pitch = jbiColumnPitch(width.toFloat(), spec.units, gapPx)
        val row1Height = (spec.row1Height * fontScale).roundToPx()
        val unitRowHeight = (ActivityPhoneRowHeight * fontScale).roundToPx()
        // Strips share the last row on the same snapped lines as the units,
        // so the row ends exactly on the unit rows' edge.
        val strips = spec.strips.coerceAtLeast(1)
        val stripPitch = jbiColumnPitch(width.toFloat(), strips, gapPx)
        val placeables = arrayOfNulls<Placeable>(measurables.size)
        val xs = IntArray(measurables.size)
        val ys = IntArray(measurables.size)
        var y = 0
        var index = 0
        val lastRow = placed.maxOfOrNull { it.first.row } ?: -1
        for (row in 0..lastRow) {
            val inRow = placed.indices.filter { placed[it].first.row == row }
            if (inRow.isEmpty()) continue
            if (index > 0) y += gap
            var rowHeight = 0
            inRow.forEach { i ->
                val slot = placed[i].first
                val placeable = if (slot.kind != SlotKind.Strip) {
                    val cell = JbiCellPlacement(startColumn = slot.startUnit, span = slot.span)
                    xs[i] = cell.cellLeft(pitch)
                    val w = cell.cellWidth(pitch, gapPx).coerceAtLeast(0)
                    measurables[i].measure(Constraints.fixed(w, if (row == 0) row1Height else unitRowHeight))
                } else {
                    val cell = JbiCellPlacement(startColumn = slot.startUnit, span = 1)
                    xs[i] = cell.cellLeft(stripPitch)
                    val w = cell.cellWidth(stripPitch, gapPx).coerceAtLeast(0)
                    measurables[i].measure(Constraints(minWidth = w, maxWidth = w))
                }
                placeables[i] = placeable
                ys[i] = y
                rowHeight = maxOf(rowHeight, placeable.height)
            }
            y += rowHeight
            index += inRow.size
        }
        layout(width, constraints.constrainHeight(y)) {
            placeables.forEachIndexed { i, placeable -> placeable?.placeRelative(xs[i], ys[i]) }
        }
    }
}

private val ActivityBentoGap = 10.dp

internal data class ActivityCardColors(
    val container: Color,
    val content: Color,
    val contentMuted: Color,
    // The cover's own tone for an accent line (the JBI score rule): the
    // palette base sinks on a dark surface, so dark mode takes the accent.
    val ink: Color,
)

/**
 * Container wash lerped straight from this card's own cover palette — NOT an
 * `ExpressiveColorSchemeFactory.fromSeed` scheme, whose M3-Expressive hue
 * rotation turns a green cover into a peach card. The direct lerp keeps each
 * card hue-faithful to its artwork (the Figma look) and skips building a
 * ColorScheme per palette-animation frame. Text stays on the theme's
 * on-surface roles, which hold contrast on the soft wash in both modes.
 */
@Composable
internal fun rememberActivityCardColors(
    coverArtUrl: String?,
    extractBackdropColors: Boolean,
): ActivityCardColors {
    val backdrop = rememberExpressiveBackdropColors(
        model = coverArtUrl,
        fallbackBaseColor = MaterialTheme.colorScheme.secondaryContainer,
        fallbackAccentColor = MaterialTheme.colorScheme.tertiaryContainer,
        enabled = extractBackdropColors,
    )
    return ActivityCardColors(
        container = lerp(
            MaterialTheme.colorScheme.surfaceContainerLow,
            backdrop.baseColor,
            0.30f,
        ),
        content = MaterialTheme.colorScheme.onSurface,
        contentMuted = MaterialTheme.colorScheme.onSurfaceVariant,
        ink = if (isSystemInDarkTheme()) backdrop.accentColor else backdrop.baseColor,
    )
}

// Design decision (settled after trying all-none): every activities card gets
// the same tinted container — all-or-none, and all won. The wash comes from
// each card's own cover palette, so the bento reads like the Figma's colour
// blocks while the entity shape still carries the identity inside.
@Composable
private fun ActivityHeroCard(
    entry: HomeMomentEntry,
    footnoteExtra: String?,
    extractBackdropColors: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val colors = rememberActivityCardColors(entry.coverArtUrl, extractBackdropColors)
    Surface(
        // The tinted card and its cover break up as one print; the text on it
        // is lifted out and passes under the bar whole.
        modifier = modifier
            .elasticPress(interactionSource)
            .seamDissolve(),
        shape = YoinContainerShapes.Card,
        color = colors.container,
        tonalElevation = 0.dp,
        shadowElevation = 0.dp,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .noRippleClickable(
                    interactionSource = interactionSource,
                    enabled = homeEditInteractive(),
                    onClick = onClick,
                )
                .padding(14.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            WidgetBackdropArtwork(
                model = entry.coverArtUrl,
                kind = widgetShapeKindForActivity(entry.entityType),
                contentDescription = entry.title,
                extractBackdropColors = extractBackdropColors,
                interactionSource = interactionSource,
                modifier = Modifier.size(96.dp),
            )
            Column(
                modifier = Modifier
                    .weight(1f)
                    .seamFade(),
                verticalArrangement = Arrangement.spacedBy(3.dp),
            ) {
                Text(
                    text = "${entry.typeLabel} · ${entry.timeAgo}",
                    style = MaterialTheme.typography.labelSmall,
                    color = colors.contentMuted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                MarqueeText(
                    text = entry.title,
                    style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.SemiBold),
                    color = colors.content,
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    text = entry.subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.contentMuted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                footnoteExtra?.let { extra ->
                    Text(
                        text = extra,
                        style = MaterialTheme.typography.labelSmall.withTabularFigures(),
                        color = colors.contentMuted,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

@Composable
private fun ActivitySmallCard(
    entry: HomeMomentEntry,
    extractBackdropColors: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val colors = rememberActivityCardColors(entry.coverArtUrl, extractBackdropColors)
    Surface(
        // The tinted card and its cover break up as one print; the text on it
        // is lifted out and passes under the bar whole.
        modifier = modifier
            .elasticPress(interactionSource)
            .seamDissolve(),
        shape = YoinContainerShapes.Card,
        color = colors.container,
        tonalElevation = 0.dp,
        shadowElevation = 0.dp,
    ) {
        Column(
            modifier = Modifier
                .noRippleClickable(
                    interactionSource = interactionSource,
                    enabled = homeEditInteractive(),
                    onClick = onClick,
                )
                .padding(10.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            // The entity shape+cover anchors the slot (same language as every
            // other card), with the type + time stacked to its right — two
            // plain lines, no separator dot.
            Row(
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                WidgetBackdropArtwork(
                    model = entry.coverArtUrl,
                    kind = widgetShapeKindForActivity(entry.entityType),
                    contentDescription = entry.title,
                    extractBackdropColors = extractBackdropColors,
                    interactionSource = interactionSource,
                    modifier = Modifier.size(48.dp),
                )
                Column(modifier = Modifier.seamFade()) {
                    Text(
                        text = entry.typeLabel,
                        style = MaterialTheme.typography.labelSmall,
                        color = colors.contentMuted,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = entry.timeAgo,
                        style = MaterialTheme.typography.labelSmall,
                        color = colors.contentMuted,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            Spacer(modifier = Modifier.weight(1f))
            Text(
                text = entry.title,
                // titleSmall's stock 20sp leading reads as two separate rows
                // when this wraps; tightened so a 2-line title is one block.
                style = MaterialTheme.typography.titleSmall.copy(
                    fontWeight = FontWeight.SemiBold,
                    lineHeight = 17.sp,
                ),
                color = colors.content,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.seamFade(),
            )
        }
    }
}

@Composable
private fun ActivityWideCard(
    entry: HomeMomentEntry,
    extractBackdropColors: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val colors = rememberActivityCardColors(entry.coverArtUrl, extractBackdropColors)
    Surface(
        // The tinted card and its cover break up as one print; the text on it
        // is lifted out and passes under the bar whole.
        modifier = modifier
            .elasticPress(interactionSource)
            .seamDissolve(),
        shape = YoinContainerShapes.Card,
        color = colors.container,
        tonalElevation = 0.dp,
        shadowElevation = 0.dp,
    ) {
        Row(
            modifier = Modifier
                .noRippleClickable(
                    interactionSource = interactionSource,
                    enabled = homeEditInteractive(),
                    onClick = onClick,
                )
                .padding(12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            WidgetBackdropArtwork(
                model = entry.coverArtUrl,
                kind = widgetShapeKindForActivity(entry.entityType),
                contentDescription = entry.title,
                extractBackdropColors = extractBackdropColors,
                interactionSource = interactionSource,
                modifier = Modifier.size(80.dp),
            )
            Column(
                modifier = Modifier
                    .weight(1f)
                    .seamFade(),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(
                    text = "${entry.typeLabel} · ${entry.timeAgo}",
                    style = MaterialTheme.typography.labelSmall,
                    color = colors.contentMuted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                MarqueeText(
                    text = entry.title,
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                    color = colors.content,
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    text = entry.subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.contentMuted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun ActivityStripCard(
    entry: HomeMomentEntry,
    extractBackdropColors: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val interactionSource = remember { MutableInteractionSource() }
    // Text-only, per the design — no cover chip. The TITLE is bold (Figma),
    // the ・artist tail stays regular so the pair reads as one line without
    // flattening into a single weight.
    val stripTitle = remember(entry) {
        buildAnnotatedString {
            withStyle(SpanStyle(fontWeight = FontWeight.SemiBold)) {
                append(entry.title)
            }
            if (entry.subtitle.isNotBlank()) {
                append("・")
                append(entry.subtitle)
            }
        }
    }
    val colors = rememberActivityCardColors(entry.coverArtUrl, extractBackdropColors)
    Surface(
        modifier = modifier
            .elasticPress(interactionSource)
            .seamDissolve(),
        shape = YoinShapeTokens.Full,
        color = colors.container,
        tonalElevation = 0.dp,
        shadowElevation = 0.dp,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .noRippleClickable(
                    interactionSource = interactionSource,
                    enabled = homeEditInteractive(),
                    onClick = onClick,
                )
                .padding(horizontal = 16.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stripTitle,
                style = MaterialTheme.typography.bodyMedium,
                color = colors.content,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .weight(1f)
                    .seamFade(),
            )
            Text(
                text = "${entry.typeLabel} · ${entry.timeAgo}",
                style = MaterialTheme.typography.labelSmall,
                color = colors.contentMuted,
                maxLines = 1,
                modifier = Modifier.seamFade(),
            )
        }
    }
}

private fun widgetShapeKindForActivity(entityType: String): WidgetShapeKind = when (entityType) {
    ActivityEntityType.SONG.name -> WidgetShapeKind.Song
    ActivityEntityType.PLAYLIST.name -> WidgetShapeKind.Playlist
    ActivityEntityType.ARTIST.name -> WidgetShapeKind.Artist
    else -> WidgetShapeKind.Album
}

// ── Recently Added (tracks grid + album shelf, Figma 622:777) ──────────
//
// A split shelf: on the left a compact 2×2 grid of the four most-recently
// added tracks (small cover + title / artist), on the right a horizontally
// scrolling row of recently-added albums, each nested on its Bun backdrop
// shape. Either half collapses when its list is empty, and the lone survivor
// takes the full width. Wider feeds seat more of both (HomeFeedDensity:
// recentlyAddedTrackColumns) — up to 4 columns × 2 rows of the very same
// tile, and a deeper album shelf; the phone's 2×2 is untouched.

// Track cover sized so a tight 2×2 (two rows + one 14dp gap) lands near the
// album card's height (album cover + its two label lines) without a hollow
// middle. Kept modest so the title/artist column beside it stays wide (the
// covers and the album shrink together to hold the height match). Still clearly
// smaller than the album cover, matching the mock ratio.
private val RecentlyAddedTrackCover = 52.dp
private val RecentlyAddedAlbumCover = 82.dp

@Composable
private fun RecentlyAddedSection(
    tracks: List<Track>,
    albums: List<Album>,
    extractBackdropColors: Boolean,
    onTrackClick: (Track) -> Unit,
    onAlbumClick: (Album) -> Unit,
    buildCoverArtUrl: (String) -> String,
    // The feed's frame supplies the live margins: the shelf bleeds past them
    // to the container's edge and pads its content by the same amounts, so
    // resting items stay on the page margin (no-midpage-truncation).
    frame: HomeFeedFrame,
    // Landscape handset: the track grid gives up width so the album covers
    // read as one row across the page (LandscapeHome).
    singleRowShelf: Boolean = false,
    // Off while Home is being edited: a drag on the shelf is edit mode's.
    shelfScrollEnabled: Boolean = true,
    modifier: Modifier = Modifier,
) {
    // How many of each this container seats: columns from the resting width
    // (a column spring re-lays, never re-counts), rows always two.
    val windowInfo = LocalYoinWindowInfo.current
    val maxColumns = recentlyAddedTrackColumns(windowInfo.feedUnits, windowInfo.feedCoverColumns, singleRowShelf)
    val shownTracks = remember(tracks, maxColumns) { tracks.take(maxColumns * RecentlyAddedTrackRows) }
    val gridColumns = recentlyAddedGridColumns(shownTracks.size, maxColumns)
    val shownAlbums = remember(albums, maxColumns) { albums.take(recentlyAddedAlbumLimit(maxColumns)) }
    val editCardScope = LocalHomeEditCardScope.current
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        HomeSectionTitle(text = "Recently Added")
        // ONE shelf: the 2×2 track grid is the shelf's first card and the
        // albums follow it, all panning together (user call — the albums
        // scrolling alone under a pinned grid read as two disjoint widgets).
        // Full-bleed to the container's edge with page-margin content
        // padding; content clips hard at the screen edge — no edge-fade scrim
        // here (2026-07-18 ruling: the translucent mask read as clutter on this
        // shelf; the seamless cut wins).
        val shelfState = rememberLazyListState()
        val sidePadding = remember(frame) { FeedFrameSidePadding(frame) }
        LazyRow(
            state = shelfState,
            modifier = Modifier
                .fillMaxWidth()
                .ignoreParentHorizontalPadding(start = { frame.start }, end = { frame.end })
                // Plates V1 (shipped) / V2 only: clipped to the edit plate while editing.
                .homeEditShelfClip(editCardScope, escapeStart = { frame.start }, escapeEnd = { frame.end }),
            contentPadding = sidePadding,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            // Both halves hang from the top. The track covers are sized
            // so a tight 2×2 lands at roughly the album card's height.
            verticalAlignment = Alignment.Top,
            userScrollEnabled = shelfScrollEnabled,
        ) {
            if (shownTracks.isNotEmpty()) {
                item(key = "recently-added-tracks") {
                    RecentlyAddedTrackGrid(
                        tracks = shownTracks,
                        columns = gridColumns,
                        onTrackClick = onTrackClick,
                        buildCoverArtUrl = buildCoverArtUrl,
                        // The grid's width follows the feed's content width,
                        // capped (HomeFeedDensity: recentlyAddedGridWidth) —
                        // read in the layout pass only, so a column spring
                        // never re-subcomposes the shelf.
                        modifier = Modifier.layout { measurable, constraints ->
                            val width = recentlyAddedGridWidth(
                                // Less the plate's content inset (V2; else 0).
                                contentWidth = frame.contentWidth - (editCardScope?.contentInset() ?: 0.dp) * 2,
                                isCompactHeight = singleRowShelf,
                                columns = gridColumns,
                            ).roundToPx()
                            val placeable = measurable.measure(
                                constraints.copy(minWidth = width, maxWidth = width),
                            )
                            layout(placeable.width, placeable.height) { placeable.placeRelative(0, 0) }
                        },
                    )
                }
            }
            // Edit-mode card order: the grid's track slots, then the albums.
            itemsIndexed(
                items = shownAlbums,
                key = { _, album -> "recently-added-album:${album.id}" },
            ) { index, album ->
                RecentlyAddedAlbumCard(
                    album = album,
                    extractBackdropColors = extractBackdropColors,
                    onClick = { onAlbumClick(album) },
                    buildCoverArtUrl = buildCoverArtUrl,
                    modifier = Modifier.homeEditCard(maxColumns * RecentlyAddedTrackRows + index),
                )
            }
        }
    }
}

/** The shelf's lead card: the tracks packed [columns] to a row, two rows (2×2 on a phone). */
@Composable
private fun RecentlyAddedTrackGrid(
    tracks: List<Track>,
    columns: Int,
    onTrackClick: (Track) -> Unit,
    buildCoverArtUrl: (String) -> String,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier,
        // Tight, even gap between the two rows — the covers (not the gap) carry
        // the height, so the pair reads as one block instead of two stranded
        // rows with a hollow middle.
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        tracks.take(columns * RecentlyAddedTrackRows).chunked(columns).forEachIndexed { row, rowTracks ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(RecentlyAddedTileGap),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                rowTracks.forEachIndexed { column, track ->
                    RecentlyAddedTrackTile(
                        track = track,
                        onClick = { onTrackClick(track) },
                        buildCoverArtUrl = buildCoverArtUrl,
                        modifier = Modifier
                            .homeEditCard(row * columns + column)
                            .weight(1f),
                    )
                }
                // Pad a short final row so its tiles keep their column width
                // instead of stretching across the whole grid.
                if (rowTracks.size < columns) {
                    Spacer(modifier = Modifier.weight((columns - rowTracks.size).toFloat()))
                }
            }
        }
    }
}

@Composable
private fun RecentlyAddedTrackTile(
    track: Track,
    onClick: () -> Unit,
    buildCoverArtUrl: (String) -> String,
    modifier: Modifier = Modifier,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val coverArtUrl = recentlyAddedTrackCoverUrl(track, buildCoverArtUrl)
    Row(
        modifier = modifier
            .noRippleClickable(
                interactionSource = interactionSource,
                enabled = homeEditInteractive(),
                onClick = onClick,
            )
            .elasticPress(interactionSource),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ExpressiveMediaArtwork(
            model = coverArtUrl,
            contentDescription = track.title.orEmpty(),
            modifier = Modifier
                .size(RecentlyAddedTrackCover)
                .seamDissolve(),
            shape = YoinArtworkShapes.Thumb,
            fallbackIcon = YoinSymbols.Album,
            interactionSource = interactionSource,
            tonalElevation = 1.dp,
            shadowElevation = 0.dp,
        )
        Column(
            modifier = Modifier
                .weight(1f)
                .seamFade(),
        ) {
            Text(
                text = track.title.orEmpty(),
                // 13sp (vs bodyMedium's 14) so short titles like "Describe" fit
                // the narrow two-column cell instead of truncating.
                style = MaterialTheme.typography.bodyMedium.copy(
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                ),
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            track.artist?.takeIf { it.isNotBlank() }?.let { artist ->
                Text(
                    text = artist,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun RecentlyAddedAlbumCard(
    album: Album,
    extractBackdropColors: Boolean,
    onClick: () -> Unit,
    buildCoverArtUrl: (String) -> String,
    modifier: Modifier = Modifier,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val coverArtUrl = resolveHomeCoverArtUrl(album.coverArt, buildCoverArtUrl)
    Column(
        modifier = modifier
            .width(RecentlyAddedAlbumCover)
            .noRippleClickable(
                interactionSource = interactionSource,
                enabled = homeEditInteractive(),
                onClick = onClick,
            )
            .elasticPress(interactionSource),
    ) {
        WidgetBackdropArtwork(
            model = coverArtUrl,
            kind = WidgetShapeKind.Album,
            contentDescription = album.name,
            extractBackdropColors = extractBackdropColors,
            interactionSource = interactionSource,
            modifier = Modifier.size(RecentlyAddedAlbumCover),
        )
        Spacer(modifier = Modifier.height(6.dp))
        Text(
            text = album.name,
            style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Medium),
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.seamFade(),
        )
        album.artist?.takeIf { it.isNotBlank() }?.let { artist ->
            Text(
                text = artist,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.seamFade(),
            )
        }
    }
}

@Composable
internal fun HomeEmptyCard(
    title: String,
    supporting: String,
    modifier: Modifier = Modifier,
) {
    ExpressiveSectionPanel(
        modifier = modifier.seamDissolve(),
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        tonalElevation = 1.dp,
        shadowElevation = 0.dp,
    ) {
        Column(
            modifier = Modifier
                .padding(18.dp)
                .seamFade(),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = supporting,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

internal fun dedupeActivitiesForHome(
    activities: List<ActivityEvent>,
): List<ActivityEvent> = activities.distinctBy(::homeActivityDedupKey)

/**
 * What the Activities bento actually shows: deduped, and tracks dropped —
 * the bento opens album / playlist / artist pages only, single plays don't
 * earn a card.
 */
internal fun selectHomeActivities(
    activities: List<ActivityEvent>,
): List<ActivityEvent> = dedupeActivitiesForHome(activities)
    .filterNot { it.entityType == ActivityEntityType.SONG.name }

/**
 * The hero (topmost, biggest) bento slot only ever shows an album or a
 * playlist — artists keep to the smaller cards. Shared with the ViewModel so
 * the hero footnote is resolved for the same entry the UI crowns.
 */
internal fun selectHomeHeroActivity(
    activities: List<ActivityEvent>,
): ActivityEvent? = selectHomeActivities(activities).firstOrNull {
    it.entityType == ActivityEntityType.ALBUM.name ||
        it.entityType == ActivityEntityType.PLAYLIST.name
}

/**
 * `ActivityEvent.entityId` / `songId` 历史上存过两种形态：
 *   • 裸 rawId — 当前所有写入路径（`YoinRepository.recordAlbumVisit` /
 *     `recordArtistVisit` 等）统一写入这个形态
 *   • 带 provider 前缀的 MediaId 字符串（形如 `"spotify:xxxxxx"`）— 来自
 *     老版本或某些 Subsonic 路径的遗留
 *
 * Home 聚合（去重 + MediaId 构造）必须先 normalize 到纯 rawId，否则:
 *   1. 两种格式的同一实体会被 `distinctBy` 当成不同 key，导致同一张专辑
 *      在 Activities 列表里出现两次
 *   2. 拼 `"${activity.provider}:$entityId"` 时如果 entityId 已经含前缀，
 *      就会得到 `"spotify:spotify:xxx"` 被 Spotify API 当成 rawId 塞进
 *      `/v1/albums/...` 返回 400
 * 只剥本 provider 的前缀：Apple Music 资料库 rawId 自带冒号（`library:l.xxx`）。
 */
private fun activityEntityRawId(activity: ActivityEvent, raw: String): String =
    MediaId.storedRawId(activity.provider, raw)

private fun homeActivityDedupKey(activity: ActivityEvent): String {
    val canonicalEntityId = when (activity.entityType) {
        ActivityEntityType.SONG.name ->
            activityEntityRawId(activity, activity.songId ?: activity.entityId)
        else -> activityEntityRawId(activity, activity.entityId)
    }
    return "${activity.entityType}:$canonicalEntityId"
}

private fun buildActivityEntries(
    activities: List<ActivityEvent>,
    buildCoverArtUrl: (String) -> String,
    // 6 = the phone bento's historical cap (hero + 3 supporting from the top
    // 6); the unit bento (feed units ≥ 3) asks for [ActivityBentoUnitsMaxEntries].
    // The default keeps the phone pipeline byte-identical.
    limit: Int = 6,
): List<HomeMomentEntry> = selectHomeActivities(activities).take(limit).map { activity ->
    val stableId = "activity:${activity.id}:${activity.entityType}:${activity.entityId}:${activity.actionType}"
    val rawEntityId = activityEntityRawId(activity, activity.entityId)
    val entityMediaId = "${activity.provider}:$rawEntityId"
    val target: HomeEntryTarget = when (activity.entityType) {
        ActivityEntityType.ALBUM.name -> HomeEntryTarget.Album(entityMediaId, stableId)
        ActivityEntityType.ARTIST.name -> HomeEntryTarget.Artist(entityMediaId)
        ActivityEntityType.PLAYLIST.name -> HomeEntryTarget.Playlist(entityMediaId)
        else -> HomeEntryTarget.SongTarget(activity.asSong())
    }
    HomeMomentEntry(
        stableId = stableId,
        entityType = activity.entityType,
        title = activity.title,
        subtitle = activity.subtitle.ifBlank {
            when (activity.entityType) {
                ActivityEntityType.ARTIST.name -> "Artist"
                else -> "Recently active"
            }
        },
        typeLabel = activityTypeLabel(activity.entityType),
        timeAgo = formatTimeAgo(activity.timestamp),
        coverArtUrl = buildActivityCoverArtUrl(activity, buildCoverArtUrl),
        target = target,
    )
}

private fun ActivityEvent.asSong(): Track = Track(
    id = MediaId(provider, songId ?: entityId),
    title = title,
    artist = subtitle,
    artistId = artistId?.takeIf { !it.isNullOrBlank() }?.let { MediaId(provider, it) },
    album = null,
    albumId = albumId.takeIf { !it.isNullOrBlank() }?.let { MediaId(provider, it) },
    // Reconstitute the stored key into the right CoverRef variant. URLs round-
    // trip as Url (Spotify), everything else as SourceRelative (Subsonic).
    coverArt = CoverRef.fromStorageKey(coverArtId),
    durationSec = null,
    trackNumber = null,
    year = null,
    genre = null,
    userRating = null,
)

/**
 * Stored `coverArtId` is a storage-key string: either a direct URL
 * (Spotify) or a Subsonic raw id. Direct URLs bypass the Subsonic resolver.
 * The fallback cascade (coverArtId → album entityId → albumId) only makes
 * sense on Subsonic; Spotify provider rows without a storage key have no
 * useful id to hand to `buildCoverArtUrl`.
 */
private fun buildActivityCoverArtUrl(
    activity: ActivityEvent,
    buildCoverArtUrl: (String) -> String,
): String? {
    val key = activity.coverArtId
        ?: activity.entityId.takeIf {
            activity.entityType == ActivityEntityType.ALBUM.name &&
                activity.provider == MediaId.PROVIDER_SUBSONIC
        }
        ?: activity.albumId?.takeIf {
            it.isNotBlank() && activity.provider == MediaId.PROVIDER_SUBSONIC
        }
        ?: return null

    return when (val ref = CoverRef.fromStorageKey(key)) {
        is CoverRef.Url -> ref.url
        is CoverRef.SourceRelative -> buildCoverArtUrl(ref.coverArtId).takeIf { it.isNotBlank() }
        null -> null
    }
}

private fun resolveHomeCoverArtUrl(
    ref: CoverRef?,
    buildCoverArtUrl: (String) -> String,
): String? = when (ref) {
    null -> null
    is CoverRef.Url -> ref.url
    is CoverRef.SourceRelative -> buildCoverArtUrl(ref.coverArtId)
}

/** A recently added track's cover: its own, else its album's. */
private fun recentlyAddedTrackCoverUrl(track: Track, buildCoverArtUrl: (String) -> String): String? =
    resolveHomeCoverArtUrl(track.coverArt, buildCoverArtUrl) ?: track.albumId?.let { buildCoverArtUrl(it.rawId) }

private fun activityTypeLabel(entityType: String): String = when (entityType) {
    ActivityEntityType.ALBUM.name -> "Album"
    ActivityEntityType.ARTIST.name -> "Artist"
    ActivityEntityType.PLAYLIST.name -> "Playlist"
    else -> "Track"
}

private fun LazyListState.isAtTop(): Boolean =
    firstVisibleItemIndex == 0 && firstVisibleItemScrollOffset == 0

private fun formatTimeAgo(timestampMillis: Long): String {
    val diff = System.currentTimeMillis() - timestampMillis
    val minutes = diff / 60_000L
    val hours = minutes / 60L
    val days = hours / 24L
    return when {
        minutes < 1L -> "just now"
        minutes < 60L -> "${minutes}m ago"
        hours < 24L -> "${hours}h ago"
        days < 7L -> "${days}d ago"
        else -> "${days / 7}w ago"
    }
}
