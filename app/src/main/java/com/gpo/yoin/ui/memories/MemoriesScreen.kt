package com.gpo.yoin.ui.memories

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.toShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.gpo.yoin.YoinApplication
import com.gpo.yoin.symbols.YoinSymbols
import com.gpo.yoin.ui.component.ExpressiveMediaArtwork
import com.gpo.yoin.ui.component.ExpressivePageBackground
import com.gpo.yoin.ui.component.YoinLoadingIndicator
import com.gpo.yoin.ui.component.YoinPageWidths
import com.gpo.yoin.ui.component.formatTrackDuration
import com.gpo.yoin.ui.component.rememberExpressiveBackdropColors
import com.gpo.yoin.ui.component.yoinPageContentWidth
import com.gpo.yoin.ui.experience.DeckIndicatorTransitionState
import com.gpo.yoin.ui.experience.DismissRule
import com.gpo.yoin.ui.experience.EdgeAdvanceDirection
import com.gpo.yoin.ui.experience.LayoutMode
import com.gpo.yoin.ui.experience.LocalYoinWindowInfo
import com.gpo.yoin.ui.experience.YoinWindowInfo
import com.gpo.yoin.ui.experience.LocalMotionProfile
import com.gpo.yoin.ui.experience.MemoriesSessionState
import com.gpo.yoin.ui.experience.MotionProfile
import com.gpo.yoin.ui.experience.ReportMotionPressure
import com.gpo.yoin.ui.experience.RevealState
import com.gpo.yoin.ui.experience.rememberDeckIndicatorTransitionState
import com.gpo.yoin.ui.experience.rememberEdgeAdvanceState
import com.gpo.yoin.ui.experience.rememberYoinHaptics
import com.gpo.yoin.ui.experience.voteHighFrameRate
import com.gpo.yoin.ui.navigation.back.BackMotionTokens
import com.gpo.yoin.ui.navigation.back.MemoriesBackLevel
import com.gpo.yoin.ui.navigation.back.MemoriesPredictiveBack
import com.gpo.yoin.ui.navigation.back.memoriesDismissCorners
import com.gpo.yoin.ui.navigation.back.rememberMemoriesDismissRules
import com.gpo.yoin.ui.theme.ContinuousRoundedCornerShape
import com.gpo.yoin.ui.theme.ExpressiveColorSchemeFactory
import com.gpo.yoin.ui.theme.ProvideYoinMotionRole
import com.gpo.yoin.ui.theme.GoogleSansFlex
import com.gpo.yoin.ui.theme.YoinMotion
import com.gpo.yoin.ui.theme.YoinMotionRole
import com.gpo.yoin.ui.theme.YoinArtworkShapes
import com.gpo.yoin.ui.theme.YoinSerifTitle
import com.gpo.yoin.ui.theme.withTabularFigures
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlinx.coroutines.launch

private val MemoriesAdjacentDeckTrigger = 72.dp
private val MemoriesDeckEnterOffset = 44.dp

/**
 * Plain (non-snapshot) geometry the page's dismiss drag reads at gesture
 * time: written from placement and pointer callbacks, never read in
 * composition, so none of it recomposes anything.
 */
private class MemoriesDismissGeometry {
    var root: LayoutCoordinates? = null
    var heightPx = 0f

    /** Bottom of the top bar (header + the inset above it) in page px; 0 = no bar. */
    var barBottomPx = 0f

    /** Where the current lone finger went down, page px. */
    var downY = Float.MAX_VALUE
    var rule = DismissRule(commitPx = 0f, flingPxPerSec = 0f, flickBackPxPerSec = 0f)

    /** A release committed: the retreat rides out and the drag ignores fingers. */
    var committing = false

    fun onHeaderPlaced(header: LayoutCoordinates) {
        val page = root?.takeIf { it.isAttached } ?: return
        if (!header.isAttached) return
        barBottomPx = page.localPositionOf(header, Offset(0f, header.size.height.toFloat())).y
    }
}

@Composable
fun MemoriesScreen(
    viewModel: MemoriesViewModel,
    revealState: RevealState,
    onDismissed: () -> Unit,
    onPlayMemoryTrack: (MemoryEntry, Int) -> Unit,
    onOpenAlbum: (MemoryEntry) -> Unit,
    onNavigateToNeoDbSettings: () -> Unit = {},
    modifier: Modifier = Modifier,
    // The shell grants back while Memories owns it (ShellBackResolver). Off
    // by default so a test or harness host never intercepts back.
    backEnabled: Boolean = false,
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val sessionState by viewModel.sessionState.collectAsStateWithLifecycle()
    val syncingIds by viewModel.syncingEntityIds.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val haptics = rememberYoinHaptics()

    // ── Retreat to Home (the outer q, RevealState) ──
    // One vertical drag on the whole page, judged in dp: from the top bar
    // 56dp / 450dp/s, from anywhere else 112dp / 600dp/s; a 350dp/s flick
    // back returns even past the threshold. System back scrubs the same q.
    val dismissRules = rememberMemoriesDismissRules()
    val dismissGeometry = remember { MemoriesDismissGeometry() }
    // The commit distance of whatever drives q now; the bottom corners are
    // full exactly there. Read only inside the corner layer.
    var cornerThresholdPx by remember(dismissRules) { mutableFloatStateOf(dismissRules.body.commitPx) }
    val dismissDragState = rememberDraggableState { delta ->
        if (!dismissGeometry.committing) {
            revealState.dragBy(delta, dismissGeometry.heightPx)
        }
    }
    // Derived: flips twice per motion, never per frame (invariant 10).
    val dismissMoving by remember(revealState) {
        derivedStateOf { revealState.fraction > 0.001f && revealState.fraction < 0.999f }
    }

    MemoriesPredictiveBack(
        enabled = backEnabled,
        level = MemoriesBackLevel.Card,
        reveal = revealState,
        containerHeightPx = { dismissGeometry.heightPx },
        onDismiss = onDismissed,
        onCardBackStarted = { cornerThresholdPx = dismissRules.body.commitPx },
    )

    LaunchedEffect(viewModel) {
        viewModel.ensureLoaded()
    }

    // One-shot NeoDB 同步事件 → snackbar。未登录事件带一个 "Sign in" action，
    // 点击后通过 [onNavigateToNeoDbSettings] 退出 Memory 层、跳 Settings。
    LaunchedEffect(viewModel) {
        viewModel.events.collect { event ->
            when (event) {
                MemoriesOneShotEvent.NeoDBNotConfigured -> {
                    val result = snackbarHostState.showSnackbar(
                        message = "Sign in to NeoDB first to push ratings and reviews.",
                        actionLabel = "Sign in",
                        duration = SnackbarDuration.Long,
                    )
                    if (result == SnackbarResult.ActionPerformed) {
                        onNavigateToNeoDbSettings()
                    }
                }

                MemoriesOneShotEvent.NeoDBNothingToSync -> {
                    snackbarHostState.showSnackbar(
                        message = "Rate the album and write a review first.",
                        duration = SnackbarDuration.Short,
                    )
                }

                is MemoriesOneShotEvent.NeoDBSyncResult -> {
                    snackbarHostState.showSnackbar(
                        message = event.message,
                        duration = SnackbarDuration.Short,
                    )
                }
            }
        }
    }

    ReportMotionPressure(
        tag = "memories",
        isHighPressure = uiState is MemoriesUiState.Loading ||
            (uiState as? MemoriesUiState.Content)?.isLoadingAdjacentDeck == true,
    )

    ProvideYoinMotionRole(role = YoinMotionRole.Expressive) {
        ExpressivePageBackground(
            modifier = modifier
                .memoriesDismissCorners(revealState) { cornerThresholdPx }
                .voteHighFrameRate(dismissMoving)
                .onPlaced { coordinates ->
                    dismissGeometry.root = coordinates
                    dismissGeometry.heightPx = coordinates.size.height.toFloat()
                }
                // Hit-test shield. Without any pointer node on the root, a tap
                // on blank page (the header band) falls through to Home's
                // settings gear underneath. Merely being a pointer node makes
                // the page the hit target, so siblings below are never hit
                // tested; nothing is consumed, so the deck's own gestures are
                // untouched. It rides the host's translation, so the strip of
                // Home a half-open reveal uncovers stays tappable. It also
                // notes where a lone finger went down: that picks the dismiss
                // rule (bar or body) once the drag passes slop.
                .pointerInput(Unit) {
                    awaitPointerEventScope {
                        while (true) {
                            val event = awaitPointerEvent(PointerEventPass.Initial)
                            if (event.type == PointerEventType.Press) {
                                val down = event.changes.singleOrNull { it.pressed }
                                if (down != null) dismissGeometry.downY = down.position.y
                            }
                        }
                    }
                }
                // The page's one vertical drag. The pager takes the other
                // axis; nothing in the deck scrolls vertically.
                .draggable(
                    state = dismissDragState,
                    orientation = Orientation.Vertical,
                    onDragStarted = {
                        val fromBar = dismissGeometry.downY < dismissGeometry.barBottomPx
                        dismissGeometry.rule = if (fromBar) dismissRules.bar else dismissRules.body
                        cornerThresholdPx = dismissGeometry.rule.commitPx
                    },
                    onDragStopped = { velocity ->
                        if (revealState.fraction > 0f) {
                            try {
                                val target = revealState.settleDismiss(
                                    velocityPxPerSec = velocity,
                                    containerPx = dismissGeometry.heightPx,
                                    rule = dismissGeometry.rule,
                                    // A committed retreat rides out untouched.
                                    onCommit = { dismissGeometry.committing = true },
                                )
                                if (target >= 1f) {
                                    haptics.performConfirm()
                                    onDismissed()
                                }
                            } finally {
                                dismissGeometry.committing = false
                            }
                        }
                    },
                ),
        ) {
            AnimatedContent(
                targetState = uiState,
                transitionSpec = {
                    YoinMotion.fadeIn(role = YoinMotionRole.Standard) togetherWith
                        YoinMotion.fadeOut(role = YoinMotionRole.Standard)
                },
                // Keyed on the state CLASS: Content-to-Content data updates
                // (deck advance, sync flags) must not re-run the fade.
                contentKey = { it::class },
                label = "memoriesState",
                modifier = Modifier.fillMaxSize(),
            ) { state ->
                when (state) {
                    MemoriesUiState.Loading -> {
                        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            YoinLoadingIndicator()
                        }
                    }

                    MemoriesUiState.Empty -> {
                        MemoriesEmptyState()
                    }

                    is MemoriesUiState.Error -> {
                        MemoriesErrorState(
                            message = state.message,
                            onRetry = viewModel::refresh,
                        )
                    }

                    is MemoriesUiState.Content -> {
                        MemoriesContent(
                            contentState = state,
                            sessionState = sessionState,
                            revealState = revealState,
                            dismissGeometry = dismissGeometry,
                            onPlayMemoryTrack = onPlayMemoryTrack,
                            onOpenAlbum = onOpenAlbum,
                            onAdvanceDeck = viewModel::advanceDeck,
                            onCurrentPageChange = viewModel::setCurrentPage,
                            syncingEntityIds = syncingIds,
                            onSyncToNeoDb = viewModel::pushToNeoDb,
                        )
                    }
                }
            }
            SnackbarHost(
                hostState = snackbarHostState,
                modifier = Modifier
                    .fillMaxWidth()
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 96.dp),
            )
        }
    }
}

@Composable
private fun MemoriesEmptyState(
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(WindowInsets.systemBars.asPaddingValues())
            .yoinPageContentWidth(YoinPageWidths.Card)
            .padding(horizontal = 24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = "No memories yet",
            style = MaterialTheme.typography.headlineSmall,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = "Listen a little more and this page will start surfacing older plays.",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun MemoriesErrorState(
    message: String,
    onRetry: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(WindowInsets.systemBars.asPaddingValues())
            .yoinPageContentWidth(YoinPageWidths.Card)
            .padding(horizontal = 24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = message,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.error,
            textAlign = TextAlign.Center,
        )
        Spacer(modifier = Modifier.height(12.dp))
        Text(
            text = "Tap to try again",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.clickable(onClick = onRetry),
        )
    }
}

@Composable
private fun MemoriesContent(
    contentState: MemoriesUiState.Content,
    sessionState: MemoriesSessionState,
    revealState: RevealState,
    dismissGeometry: MemoriesDismissGeometry,
    onPlayMemoryTrack: (MemoryEntry, Int) -> Unit,
    onOpenAlbum: (MemoryEntry) -> Unit,
    onAdvanceDeck: (MemoryDeckDirection) -> Unit,
    onCurrentPageChange: (Int) -> Unit,
    syncingEntityIds: Set<String> = emptySet(),
    onSyncToNeoDb: (MemoryEntry) -> Unit = {},
) {
    // Derived: the deck's pull frames flip this once, not per frame.
    val auroraVisible by remember(revealState) { derivedStateOf { revealState.fraction < 0.999f } }
    val density = LocalDensity.current
    val haptics = rememberYoinHaptics()
    val dismissHintPx = with(density) { BackMotionTokens.MemoriesDismissTrigger.toPx() }
    val adjacentDeckTriggerPx = with(density) { MemoriesAdjacentDeckTrigger.toPx() }
    val deckEnterOffsetPx = with(density) { MemoriesDeckEnterOffset.toPx() }
    val edgeAdvanceState = rememberEdgeAdvanceState(triggerPx = adjacentDeckTriggerPx)

    LaunchedEffect(contentState.deckRevision) {
        edgeAdvanceState.reset()
    }
    // Only the content state has a top bar (the header); without it the whole
    // page is card body.
    DisposableEffect(dismissGeometry) {
        onDispose { dismissGeometry.barBottomPx = 0f }
    }

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        // Breakpoints (断点交接 §6), height first: a landscape handset splits
        // the one card into two columns; a Wide full window opens it as a
        // spread; a 16:9 screen (the card face needs ~765dp without its
        // flexible air) tightens the seal row so nothing overflows. Medium and
        // split panes keep the 480 card as it is.
        val cardLayout = memoryCardLayoutFor(LocalYoinWindowInfo.current, maxHeight)

        // Deck switches animate as ONE AnimatedContent transition (slide + fade
        // in, symmetric slide + fade out) — it is the sole owner of the pane's
        // offset/alpha. Keyed on the revision so Content-to-Content updates
        // within a deck (e.g. isLoadingAdjacentDeck) just recompose in place.
        // targetState is the whole Content so the EXITING pane keeps rendering
        // its own memories snapshot instead of the new deck's.
        AnimatedContent(
            targetState = contentState,
            contentKey = { it.deckRevision },
            transitionSpec = {
                // The new deck enters from the pulled edge; the old one
                // retreats out the opposite side along the same axis.
                val enterFrom = when (targetState.deckDirection) {
                    MemoryDeckDirection.Backward -> -1
                    MemoryDeckDirection.Forward -> 1
                }
                val enter = YoinMotion.slideInHorizontally(role = YoinMotionRole.Expressive) {
                    enterFrom * deckEnterOffsetPx.roundToInt()
                } + YoinMotion.fadeIn(role = YoinMotionRole.Expressive)
                val exit = YoinMotion.slideOutHorizontally(role = YoinMotionRole.Expressive) {
                    -enterFrom * deckEnterOffsetPx.roundToInt()
                } + YoinMotion.fadeOut(role = YoinMotionRole.Expressive)
                enter togetherWith exit
            },
            label = "memoriesDeck",
            modifier = Modifier.fillMaxSize(),
        ) { deckState ->
            val memories = deckState.memories
            val pagerState = rememberPagerState(
                initialPage = sessionState.currentPage.coerceIn(0, memories.lastIndex),
                pageCount = { memories.size },
            )
            val coroutineScope = rememberCoroutineScope()
            val selectedIndex = pagerState.currentPage.coerceIn(0, memories.lastIndex)
            val selectedMemory = memories[selectedIndex]
            val adjacentDeckDirection = edgeAdvanceState.direction?.toMemoryDeckDirection()

            // Ambient moving-gradient wash in the CURRENT memory's palette —
            // swiping re-tints the whole atmosphere (the palette's own 380ms
            // hand-off animates the transition). Loops run only while the
            // deck is actually on screen.
            val auroraColors = rememberExpressiveBackdropColors(
                model = selectedMemory.coverArtUrl,
                fallbackBaseColor = MaterialTheme.colorScheme.primaryContainer,
                fallbackAccentColor = MaterialTheme.colorScheme.tertiaryContainer,
            )

            LaunchedEffect(pagerState, memories) {
                snapshotFlow { pagerState.currentPage to pagerState.currentPageOffsetFraction }
                    .collect { (page, offsetFraction) ->
                        if (offsetFraction == 0f) {
                            onCurrentPageChange(page)
                        }
                    }
            }
            val pagerEdgeConnection = remember(
                pagerState,
                memories,
                deckState.isLoadingAdjacentDeck,
                onAdvanceDeck,
            ) {
                object : NestedScrollConnection {
                    override fun onPostScroll(
                        consumed: Offset,
                        available: Offset,
                        source: NestedScrollSource,
                    ): Offset {
                        if (source != NestedScrollSource.UserInput || deckState.isLoadingAdjacentDeck) {
                            return Offset.Zero
                        }
                        val direction = when {
                            available.x > 0f && pagerState.currentPage == 0 -> EdgeAdvanceDirection.Backward
                            available.x < 0f && pagerState.currentPage == memories.lastIndex -> EdgeAdvanceDirection.Forward
                            else -> null
                        } ?: return Offset.Zero

                        edgeAdvanceState.registerPull(
                            direction = direction,
                            deltaPx = abs(available.x),
                            onTriggered = { triggeredDirection ->
                                haptics.performTick()
                                onAdvanceDeck(triggeredDirection.toMemoryDeckDirection())
                            },
                        )
                        return Offset(available.x, 0f)
                    }

                    override suspend fun onPostFling(
                        consumed: Velocity,
                        available: Velocity,
                    ): Velocity {
                        edgeAdvanceState.reset()
                        return Velocity.Zero
                    }
                }
            }

            Column(
                modifier = Modifier
                    .fillMaxSize()
                    // The wash lives on the pane itself (AnimatedContent's
                    // lambda is not a BoxScope) so it rides the deck
                    // transition together with the content.
                    .memoriesAuroraBackground(
                        baseColor = auroraColors.baseColor,
                        accentColor = auroraColors.accentColor,
                        visible = auroraVisible,
                    )
                    .padding(top = WindowInsets.systemBars.asPaddingValues().calculateTopPadding() + 12.dp)
                    // Landscape: the Button Group hides here, so the card only
                    // clears the cutout band (§6).
                    .windowInsetsPadding(WindowInsets.displayCutout.only(WindowInsetsSides.Horizontal)),
            ) {
                MemoriesHeader(
                    compact = cardLayout == MemoryCardLayout.Landscape,
                    memories = memories,
                    selectedIndex = selectedIndex,
                    currentPageOffsetFraction = pagerState.currentPageOffsetFraction,
                    onSelect = { targetIndex ->
                        coroutineScope.launch {
                            pagerState.animateScrollToPage(targetIndex)
                        }
                    },
                    selectedMemory = selectedMemory,
                    adjacentDeckProgress = edgeAdvanceState.progress,
                    adjacentDeckDirection = adjacentDeckDirection,
                    modifier = Modifier
                        .fillMaxWidth()
                        // The header band (with the inset above it) is the
                        // top bar: a dismiss that starts here uses the bar rule.
                        .onPlaced(dismissGeometry::onHeaderPlaced)
                        .padding(horizontal = 20.dp),
                )

                // Baseline for the seal-stamp: the first deck read marks the
                // current newest memory as already-seen, so only memories born
                // LATER stamp. Without this nothing would ever read as new.
                val sessionStore = (LocalContext.current.applicationContext as YoinApplication)
                    .container.experienceSessionStore
                LaunchedEffect(memories) {
                    if (sessionStore.memoriesStampedTimestamp == Long.MAX_VALUE &&
                        memories.isNotEmpty()
                    ) {
                        sessionStore.memoriesStampedTimestamp = memories.maxOf { it.timestamp }
                    }
                }

                HorizontalPager(
                    state = pagerState,
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .nestedScroll(pagerEdgeConnection),
                ) { page ->
                    val memory = memories[page]
                    val pageColors = rememberExpressiveBackdropColors(
                        model = memory.coverArtUrl,
                        fallbackBaseColor = MaterialTheme.colorScheme.outlineVariant,
                        fallbackAccentColor = MaterialTheme.colorScheme.primary,
                    )
                    // 单视口固定栈：卡内没有任何竖向滚动，竖向手势整段归
                    // 页面根上的 dismiss draggable，与 pager 的横向手势各占一轴。
                    MemorySealCard(
                        memory = memory,
                        layout = cardLayout,
                        seedColor = pageColors.baseColor,
                        isSyncingToNeoDb = "${memory.entityProvider}:${memory.entityId}" in syncingEntityIds,
                        onSyncToNeoDb = { onSyncToNeoDb(memory) },
                        onPlayCover = {
                            haptics.performClick()
                            onPlayMemoryTrack(memory, 0)
                        },
                        onOpenAlbum = {
                            haptics.performClick()
                            onOpenAlbum(memory)
                        },
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }
        }

        // Edge-pull deck fetch can take a beat or two — float a small quiet
        // indicator over the deck so the wait isn't dead air.
        AnimatedVisibility(
            visible = contentState.isLoadingAdjacentDeck,
            enter = YoinMotion.fadeIn(role = YoinMotionRole.Standard),
            exit = YoinMotion.fadeOut(role = YoinMotionRole.Standard),
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 72.dp),
        ) {
            YoinLoadingIndicator(size = 28.dp)
        }

        // Return-to-home hint arrow
        Icon(
            imageVector = YoinSymbols.ChevronUp,
            contentDescription = "Back to Home",
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 28.dp)
                .graphicsLayer {
                    val f = revealState.fraction.coerceIn(0f, 1f)
                    translationY = -(f * dismissHintPx) * 0.3f
                    alpha = 0.4f + f * 0.6f
                }
                .size(28.dp),
        )
    }
}

@Composable
private fun MemoriesHeader(
    memories: List<MemoryEntry>,
    selectedIndex: Int,
    currentPageOffsetFraction: Float,
    selectedMemory: MemoryEntry,
    adjacentDeckProgress: Float,
    adjacentDeckDirection: MemoryDeckDirection?,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
    // Landscape handset: date, title and dots pressed into one row (§6).
    compact: Boolean = false,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (compact) {
            Row(
                modifier = Modifier.weight(1f),
                verticalAlignment = Alignment.Bottom,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    text = selectedMemory.timestamp.toShortMemoryDate(),
                    style = MaterialTheme.typography.headlineSmall.copy(
                        fontWeight = FontWeight.SemiBold,
                        fontFamily = GoogleSansFlex,
                    ),
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = "Memories",
                    style = MaterialTheme.typography.titleMedium.copy(
                        fontWeight = FontWeight.SemiBold,
                        fontFamily = GoogleSansFlex,
                    ),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(
                    text = selectedMemory.timestamp.toShortMemoryDate(),
                    style = MaterialTheme.typography.displaySmall.copy(
                        fontWeight = FontWeight.SemiBold,
                        fontFamily = GoogleSansFlex,
                    ),
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = "Memories",
                    style = MaterialTheme.typography.titleLarge.copy(
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 18.sp,
                        lineHeight = 22.sp,
                        fontFamily = GoogleSansFlex,
                    ),
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
        }

        MemoriesDots(
            memories = memories,
            selectedIndex = selectedIndex,
            currentPageOffsetFraction = currentPageOffsetFraction,
            adjacentDeckProgress = adjacentDeckProgress,
            adjacentDeckDirection = adjacentDeckDirection,
            onSelect = onSelect,
        )
    }
}

@Composable
private fun MemoriesDots(
    memories: List<MemoryEntry>,
    selectedIndex: Int,
    currentPageOffsetFraction: Float,
    adjacentDeckProgress: Float,
    adjacentDeckDirection: MemoryDeckDirection?,
    onSelect: (Int) -> Unit,
) {
    val continuousPosition = selectedIndex + currentPageOffsetFraction
    // Deck-switch motion is owned by the AnimatedContent pane (the dots ride
    // it); the indicator only adds the live edge-pull hint, so the deck
    // transition inputs are pinned to their resting values.
    val indicatorTransitionState: DeckIndicatorTransitionState = rememberDeckIndicatorTransitionState(
        deckTransitionProgress = 1f,
        deckTransitionDirection = EdgeAdvanceDirection.Forward,
        adjacentProgress = adjacentDeckProgress,
        adjacentDirection = adjacentDeckDirection?.toEdgeAdvanceDirection(),
    )

    Row(
        modifier = Modifier.graphicsLayer {
            translationX = indicatorTransitionState.translationXPx
            scaleX = indicatorTransitionState.scale
            scaleY = indicatorTransitionState.scale
            alpha = indicatorTransitionState.alpha
        },
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        repeat(MEMORY_DECK_SIZE) { index ->
            val memory = memories.getOrNull(index)
            val colors = rememberExpressiveBackdropColors(
                model = memory?.coverArtUrl,
                fallbackBaseColor = MaterialTheme.colorScheme.outlineVariant,
                fallbackAccentColor = MaterialTheme.colorScheme.outline,
            )
            // 0 = far away, 1 = exactly on this page
            val proximity = (1f - abs(index - continuousPosition)).coerceIn(0f, 1f)
            // Visual size: 12dp → 17dp, driven continuously by scroll position
            val scale = 0.86f + 0.36f * proximity

            Box(
                modifier = Modifier
                    .size(14.dp)
                    .graphicsLayer {
                        scaleX = scale
                        scaleY = scale
                        alpha = if (memory != null) {
                            0.55f + 0.45f * proximity
                        } else {
                            0.35f
                        }
                    }
                    .clip(CircleShape)
                    .background(
                        if (memory != null) {
                            colors.baseColor
                        } else {
                            MaterialTheme.colorScheme.outlineVariant
                        },
                    )
                    .then(
                        if (memory != null) {
                            Modifier.clickable { onSelect(index) }
                        } else {
                            Modifier
                        },
                    ),
            )
        }
    }
}

/**
 * 单视口印章卡 —— 每张 Memory 一屏放完，卡内永不竖向滚动：
 * 标题区 → 印章行（评分三态 + AI 拟题/正文）→ 笔记卡 ≤2 → 弹性呼吸 →
 * footnotes（证据句 + NeoDB 五态，锚底）→ 前往专辑（唯一导航出口）。
 * 装不下的内容硬截断，去处都是底部那颗按钮；超量笔记走 sheet（卡外展开）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MemorySealCard(
    memory: MemoryEntry,
    layout: MemoryCardLayout,
    seedColor: Color,
    isSyncingToNeoDb: Boolean,
    onSyncToNeoDb: () -> Unit,
    onPlayCover: () -> Unit,
    onOpenAlbum: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val haptics = rememberYoinHaptics()
    val darkTheme = isSystemInDarkTheme()
    val memoryColorScheme = remember(seedColor, darkTheme) {
        ExpressiveColorSchemeFactory.fromSeed(
            seedArgb = seedColor.toArgb(),
            isDark = darkTheme,
        )
    }
    var showAllNotes by remember(memory.stableId) { mutableStateOf(false) }
    var showFullReview by remember(memory.stableId) { mutableStateOf(false) }
    val navBottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()

    // Seal-stamp moment: a memory born AFTER the deck's seen-baseline gets its
    // seal stamped in once (1.35× → 1 with a −10° un-rotate + confirm haptic),
    // then the session mark advances so it never re-stamps.
    val sessionStore = (LocalContext.current.applicationContext as YoinApplication)
        .container.experienceSessionStore
    val isFreshMemory = memory.timestamp > sessionStore.memoriesStampedTimestamp
    var stamped by rememberSaveable(memory.stableId) { mutableStateOf(false) }
    val stampProgress = remember { Animatable(if (isFreshMemory && !stamped) 0f else 1f) }
    val stampSpec = YoinMotion.fastSpatialSpec<Float>(role = YoinMotionRole.Expressive)
    LaunchedEffect(Unit) {
        if (isFreshMemory && !stamped) {
            haptics.performConfirm()
            stampProgress.animateTo(1f, stampSpec)
            stamped = true
            sessionStore.memoriesStampedTimestamp =
                maxOf(sessionStore.memoriesStampedTimestamp, memory.timestamp)
        }
    }

    val short = layout == MemoryCardLayout.Short
    val sealSize = when (layout) {
        MemoryCardLayout.Portrait -> MemorySealSize
        MemoryCardLayout.Short, MemoryCardLayout.Landscape -> MemorySealSizeCompact
        MemoryCardLayout.Spread -> MemorySealSizeSpread
    }

    // ── 标题区：专辑名 + 艺人·年份，72dp 裸封面（点按即播） ──
    val titleBlock: @Composable () -> Unit = {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalAlignment = Alignment.Top,
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    text = memory.title,
                    // 16:9 / landscape: one step down (§6).
                    style = (
                        if (layout == MemoryCardLayout.Portrait || layout == MemoryCardLayout.Spread) {
                            MaterialTheme.typography.headlineLarge
                        } else {
                            MaterialTheme.typography.headlineMedium
                        }
                        ).copy(fontWeight = FontWeight.SemiBold),
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = memory.supportingText,
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            ExpressiveMediaArtwork(
                model = memory.coverArtUrl,
                contentDescription = memory.title,
                modifier = Modifier
                    .size(72.dp)
                    .clickable(onClick = onPlayCover),
                shape = YoinArtworkShapes.Cover,
                fallbackIcon = YoinSymbols.Album,
                tonalElevation = 0.dp,
                shadowElevation = 0.dp,
            )
        }
    }

    // ── 印章行：曲奇印章 × AI 拟题 ──
    val sealRow: @Composable () -> Unit = {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            Box(
                modifier = Modifier.graphicsLayer {
                    val p = stampProgress.value
                    if (p < 1f) {
                        val s = 1f + 0.35f * (1f - p)
                        scaleX = s
                        scaleY = s
                        rotationZ = -10f * (1f - p)
                        alpha = p
                    }
                },
            ) {
                MemorySeal(
                    memory = memory,
                    scheme = memoryColorScheme,
                    size = sealSize,
                )
            }
            Column(
                modifier = Modifier
                    .weight(1f)
                    .height(sealSize),
                verticalArrangement = Arrangement.Center,
            ) {
                // 右列只放拟题（方案 B）：正文升级为下方的全宽区块。
                // 拟题是标题 → 宋体（字体规范 2026-07-26）；不渲染来源 eyebrow。
                memory.memoryTitle?.let { title ->
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleLarge.copy(
                            fontWeight = FontWeight.SemiBold,
                            fontFamily = YoinSerifTitle,
                            fontSize = if (sealSize < MemorySealSize) 20.sp else 22.sp,
                            lineHeight = if (sealSize < MemorySealSize) 27.sp else 30.sp,
                        ),
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }

    // ── 全宽乐评区（方案 B）：长评终于有配得上它的面积。硬截断，
    //    点击开 sheet 读全文；笔记不再占卡面（收进底部按钮）。 ──
    val words: @Composable (reviewMaxLines: Int) -> Unit = { reviewMaxLines ->
        val reviewText = memory.review?.text
        if (reviewText != null) {
            // 正文主体 = 系统默认黑体（字体规范 2026-07-26）——衬线让位给标题。
            Text(
                text = reviewText,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable {
                        haptics.performClick()
                        showFullReview = true
                    },
                style = MaterialTheme.typography.bodyLarge.copy(
                    fontFamily = FontFamily.Default,
                    fontSize = 15.sp,
                    lineHeight = 25.sp,
                ),
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = reviewMaxLines,
                overflow = TextOverflow.Ellipsis,
            )
        } else {
            // 无长评：先呈现 Yoin 的话（必须标记——它不是你写的字），
            // 再用一行斜体小字 + 按钮引导写评价（owner 裁决 2026-07-26）。
            memory.narrativeCopy?.takeIf(String::isNotBlank)?.let { copy ->
                Text(
                    text = "Written by Yoin",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = copy,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 4.dp),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(modifier = Modifier.height(if (short) 10.dp else 16.dp))
            }
            val prompt: @Composable () -> Unit = {
                Text(
                    text = "How did this album land for you?",
                    style = MaterialTheme.typography.bodySmall.copy(
                        fontStyle = FontStyle.Italic,
                    ),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            val writeButton: @Composable () -> Unit = {
                TextButton(
                    onClick = onOpenAlbum,
                    contentPadding = PaddingValues(horizontal = 4.dp),
                ) {
                    Text(
                        text = "Write a review",
                        style = MaterialTheme.typography.labelLarge,
                        color = memoryColorScheme.primary,
                    )
                }
            }
            if (short || layout == MemoryCardLayout.Landscape) {
                // 16:9: the question and its button share one row (§6).
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(modifier = Modifier.weight(1f, fill = false)) { prompt() }
                    Spacer(modifier = Modifier.width(4.dp))
                    writeButton()
                }
            } else {
                prompt()
                writeButton()
            }
        }
    }

    // ── footnotes：证据句 + NeoDB 五态（锚底，四卡同位） ──
    val footnotes: @Composable () -> Unit = {
        Text(
            text = memory.evidenceLine(),
            style = MaterialTheme.typography.bodySmall.withTabularFigures(),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        if (memory.entityType == MemoryEntityType.ALBUM) {
            when (memory.neoDbState) {
                MemoryNeoDbState.SYNCED -> MemoryFootnote("Synced to NeoDB")
                MemoryNeoDbState.NEEDS_REVIEW -> MemoryFootnote("Write a review to push to NeoDB")
                MemoryNeoDbState.NEEDS_RATING -> MemoryFootnote("Add a rating to push to NeoDB")
                MemoryNeoDbState.READY -> TextButton(
                    onClick = {
                        haptics.performConfirm()
                        onSyncToNeoDb()
                    },
                    enabled = !isSyncingToNeoDb,
                    contentPadding = PaddingValues(horizontal = 4.dp),
                ) {
                    Text(
                        text = if (isSyncingToNeoDb) "Syncing to NeoDB…" else "Push to NeoDB",
                        style = MaterialTheme.typography.labelLarge,
                        color = memoryColorScheme.primary,
                    )
                }
                MemoryNeoDbState.UNAVAILABLE -> Unit
            }
        }
    }

    // ── 底部按钮排：笔记入口（带条数）在左，前往专辑在右。
    //    笔记卡从卡面退场后，这颗按钮就是它们唯一的家（对开页除外）。 ──
    val actions: @Composable (showNotesButton: Boolean) -> Unit = { showNotesButton ->
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (showNotesButton && memory.writings.isNotEmpty()) {
                TextButton(
                    onClick = {
                        haptics.performClick()
                        showAllNotes = true
                    },
                    modifier = Modifier.height(48.dp),
                ) {
                    Text(
                        text = "${memory.writings.size} " +
                            if (memory.writings.size == 1) "note" else "notes",
                        style = MaterialTheme.typography.labelLarge,
                        color = memoryColorScheme.primary,
                    )
                }
            }
            Button(
                onClick = onOpenAlbum,
                modifier = Modifier.height(48.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = memoryColorScheme.primary,
                    contentColor = memoryColorScheme.onPrimary,
                ),
            ) {
                Text(
                    text = "Go to album",
                    style = MaterialTheme.typography.labelLarge,
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(text = "→", style = MaterialTheme.typography.labelLarge)
            }
        }
    }

    when (layout) {
        MemoryCardLayout.Portrait,
        MemoryCardLayout.Short,
        -> Column(
            // 限宽链在来件 modifier 之后:wrapContentWidth 上报的尺寸仍被上游
            // fillMaxSize 的固定约束钳成全宽,所以 dismiss draggable 的命中区
            // 保持整面板宽度——侧边空档起手的下拉照样能关掉页面。
            modifier = modifier
                .yoinPageContentWidth(YoinPageWidths.Card)
                .padding(start = 20.dp, end = 20.dp, top = if (short) 12.dp else 20.dp),
        ) {
            titleBlock()
            Spacer(modifier = Modifier.height(if (short) 16.dp else 24.dp))
            sealRow()
            Spacer(modifier = Modifier.height(if (short) 16.dp else 24.dp))
            words(if (short) 5 else 8)
            // ── 弹性呼吸：notes 与锚底 footnotes 之间 ──
            Spacer(modifier = Modifier.weight(1f))
            footnotes()
            Spacer(modifier = Modifier.height(12.dp))
            Box(modifier = Modifier.align(Alignment.CenterHorizontally)) { actions(true) }
            Spacer(modifier = Modifier.height(navBottom + if (short) 36.dp else 44.dp))
        }

        // Landscape handset (MemLandscape): still ONE card that never scrolls,
        // split into two columns — title, cover and seal on the left; the
        // words, the evidence line and Go to album on the right.
        MemoryCardLayout.Landscape -> Row(
            modifier = modifier
                .yoinPageContentWidth(MemoryLandscapeCardWidth)
                .padding(start = 20.dp, end = 20.dp, top = 8.dp, bottom = navBottom + 12.dp),
            horizontalArrangement = Arrangement.spacedBy(32.dp),
        ) {
            Column(modifier = Modifier.weight(1f)) {
                titleBlock()
                Spacer(modifier = Modifier.height(12.dp))
                sealRow()
            }
            Column(modifier = Modifier.weight(1f)) {
                words(4)
                Spacer(modifier = Modifier.weight(1f))
                footnotes()
                Spacer(modifier = Modifier.height(8.dp))
                actions(true)
            }
        }

        // Wide full window (MemDesktop): a spread — identity and a big seal on
        // the left page, the whole review on the right, and the notes back on
        // the card face (there's room for them here).
        MemoryCardLayout.Spread -> Row(
            modifier = modifier
                .yoinPageContentWidth(MemorySpreadMaxWidth)
                .padding(start = 32.dp, end = 32.dp, top = 24.dp, bottom = navBottom + 56.dp),
            horizontalArrangement = Arrangement.spacedBy(56.dp),
        ) {
            Column(modifier = Modifier.weight(1f)) {
                titleBlock()
                Spacer(modifier = Modifier.height(32.dp))
                sealRow()
                Spacer(modifier = Modifier.weight(1f))
                footnotes()
            }
            Column(modifier = Modifier.weight(1f)) {
                words(MemorySpreadReviewLines)
                if (memory.writings.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(20.dp))
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        memory.writings.take(MemorySpreadNoteCount).forEach { writing ->
                            MemoryNoteCard(
                                writing = writing,
                                containerColor = memoryColorScheme.surfaceContainerHigh,
                            )
                        }
                    }
                }
                Spacer(modifier = Modifier.weight(1f))
                actions(memory.writings.size > MemorySpreadNoteCount)
            }
        }
    }

    if (showAllNotes) {
        ModalBottomSheet(
            onDismissRequest = { showAllNotes = false },
        ) {
            LazyColumn(
                modifier = Modifier.yoinPageContentWidth(YoinPageWidths.Prose),
                contentPadding = PaddingValues(
                    start = 20.dp,
                    end = 20.dp,
                    bottom = navBottom + 24.dp,
                ),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                itemsIndexed(
                    items = memory.writings,
                    key = { index, writing -> "${writing.writtenAt}:$index" },
                ) { _, writing ->
                    MemoryNoteCard(
                        writing = writing,
                        containerColor = memoryColorScheme.surfaceContainerHigh,
                        clampBody = false,
                    )
                }
            }
        }
    }

    if (showFullReview) {
        memory.review?.let { review ->
            ModalBottomSheet(
                onDismissRequest = { showFullReview = false },
            ) {
                LazyColumn(
                    modifier = Modifier.yoinPageContentWidth(YoinPageWidths.Prose),
                    contentPadding = PaddingValues(
                        start = 20.dp,
                        end = 20.dp,
                        bottom = navBottom + 24.dp,
                    ),
                ) {
                    item {
                        memory.memoryTitle?.let { title ->
                            Text(
                                text = title,
                                style = MaterialTheme.typography.titleLarge.copy(
                                    fontWeight = FontWeight.SemiBold,
                                    fontFamily = YoinSerifTitle,
                                ),
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                            Spacer(modifier = Modifier.height(12.dp))
                        }
                        Text(
                            text = review.text,
                            style = MaterialTheme.typography.bodyLarge.copy(
                                fontFamily = FontFamily.Default,
                                fontSize = 15.sp,
                                lineHeight = 26.sp,
                            ),
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                    }
                }
            }
        }
    }
}

private val MemorySealSize = 148.dp

/** 16:9 and landscape seal (MemShort / MemLandscape). */
private val MemorySealSizeCompact = 112.dp

/** The spread's big seal (MemDesktop). */
private val MemorySealSizeSpread = 190.dp

/** Below this the portrait card face overflows (mem2: ~765dp without its air). */
private val MemoryCardComfortHeight = 765.dp

private val MemoryLandscapeCardWidth = 760.dp
private val MemorySpreadMaxWidth = 1160.dp
private const val MemorySpreadReviewLines = 14
private const val MemorySpreadNoteCount = 3

/** How one memory card lays out in this window (断点交接 §6). */
private enum class MemoryCardLayout {
    Portrait,
    Short,
    Landscape,
    Spread,
}

private fun memoryCardLayoutFor(windowInfo: YoinWindowInfo, height: Dp): MemoryCardLayout = when {
    windowInfo.isCompactHeight -> MemoryCardLayout.Landscape
    windowInfo.layoutMode == LayoutMode.Wide -> MemoryCardLayout.Spread
    height < MemoryCardComfortHeight -> MemoryCardLayout.Short
    else -> MemoryCardLayout.Portrait
}

/**
 * One card on its own, laid out for the current window exactly as the deck
 * would — for the debug screenshot harness and previews (the deck itself
 * needs a live [MemoriesViewModel]).
 */
@Composable
internal fun MemoryCardStandalone(memory: MemoryEntry, modifier: Modifier = Modifier) {
    BoxWithConstraints(
        modifier = modifier
            .fillMaxSize()
            .padding(top = WindowInsets.systemBars.asPaddingValues().calculateTopPadding() + 12.dp)
            .windowInsetsPadding(WindowInsets.displayCutout.only(WindowInsetsSides.Horizontal)),
    ) {
        MemorySealCard(
            memory = memory,
            layout = memoryCardLayoutFor(LocalYoinWindowInfo.current, maxHeight),
            seedColor = MaterialTheme.colorScheme.primary,
            isSyncingToNeoDb = false,
            onSyncToNeoDb = {},
            onPlayCover = {},
            onOpenAlbum = {},
            modifier = Modifier.fillMaxSize(),
        )
    }
}

/**
 * 评分印章：三态同几何（rule 2 零跳变）。
 * 实心 = 用户亲手落的专辑评分（knockout 数字）；描边 = 逐曲均分（机器算的，
 * 印没盖下去）；灰描边 = 未评分（空印模）。覆盖率是数字底下的一行小字 ——
 * 它是证据不是主角。形状 60s/圈慢转（AdaptiveReduced 静止），与 aurora
 * 同属 ambient loop，不违反入场动画墓碑。
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun MemorySeal(
    memory: MemoryEntry,
    scheme: ColorScheme,
    size: Dp = MemorySealSize,
) {
    val sealShape = MaterialShapes.Cookie12Sided.toShape()
    val reduceMotion = LocalMotionProfile.current == MotionProfile.AdaptiveReduced
    val rotation = if (reduceMotion) {
        0f
    } else {
        val transition = rememberInfiniteTransition(label = "sealSpin")
        val animated by transition.animateFloat(
            initialValue = 0f,
            targetValue = 360f,
            animationSpec = infiniteRepeatable(
                animation = tween(durationMillis = 60_000, easing = LinearEasing),
            ),
            label = "sealRotation",
        )
        animated
    }

    // Numbers scale with the seal (112 on 16:9, 190 on the spread).
    val sealScale = size / MemorySealSize
    Box(
        modifier = Modifier.size(size),
        contentAlignment = Alignment.Center,
    ) {
        // aurora halo 的近似：印章中心的一圈同调色光晕。
        Box(
            modifier = Modifier
                .requiredSize(size * 1.7f)
                .background(
                    brush = Brush.radialGradient(
                        colors = listOf(
                            scheme.primary.copy(alpha = 0.28f),
                            Color.Transparent,
                        ),
                    ),
                ),
        )
        // 形状层单独转；数字不转。
        Box(
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer { rotationZ = rotation }
                .then(
                    when (memory.scoreKind) {
                        MemoryScoreKind.ALBUM_RATING ->
                            Modifier.background(color = scheme.primary, shape = sealShape)
                        MemoryScoreKind.AVERAGE_TRACK_RATING ->
                            Modifier.border(width = 2.dp, color = scheme.primary, shape = sealShape)
                        MemoryScoreKind.NONE ->
                            Modifier.border(
                                width = 2.dp,
                                color = MaterialTheme.colorScheme.outlineVariant,
                                shape = sealShape,
                            )
                    },
                ),
        )
        val onSeal = when (memory.scoreKind) {
            MemoryScoreKind.ALBUM_RATING -> scheme.onPrimary
            MemoryScoreKind.AVERAGE_TRACK_RATING -> MaterialTheme.colorScheme.onSurface
            MemoryScoreKind.NONE -> MaterialTheme.colorScheme.onSurfaceVariant
        }
        val onSealMuted = when (memory.scoreKind) {
            MemoryScoreKind.ALBUM_RATING -> scheme.onPrimary.copy(alpha = 0.85f)
            else -> MaterialTheme.colorScheme.onSurfaceVariant
        }
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            if (memory.scoreKind == MemoryScoreKind.NONE) {
                Text(
                    text = "No rating yet",
                    style = MaterialTheme.typography.titleMedium,
                    color = onSeal,
                )
            } else {
                Text(
                    text = memory.scoreText,
                    style = MaterialTheme.typography.displayLarge.copy(
                        fontSize = 44.sp * sealScale,
                        lineHeight = 48.sp * sealScale,
                        fontWeight = FontWeight.SemiBold,
                    ).withTabularFigures(),
                    color = onSeal,
                )
                Text(
                    text = memory.scoreKind.label,
                    style = MaterialTheme.typography.labelSmall,
                    color = onSealMuted,
                )
            }
            if (memory.totalTrackCount > 0) {
                Text(
                    text = "${memory.ratedTrackCount} / ${memory.totalTrackCount} rated",
                    modifier = Modifier.padding(top = 2.dp),
                    style = MaterialTheme.typography.labelSmall
                        .copy(fontSize = 10.sp, lineHeight = 14.sp)
                        .withTabularFigures(),
                    color = onSealMuted.copy(alpha = 0.8f),
                )
            }
        }
    }
}

/** 笔记卡三层：歌名头行（W600）> serif 正文 > 日期右上。 */
@Composable
private fun MemoryNoteCard(
    writing: MemoryWriting,
    containerColor: Color,
    clampBody: Boolean = true,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = ContinuousRoundedCornerShape(14.dp),
        color = containerColor,
        tonalElevation = 0.dp,
        shadowElevation = 0.dp,
    ) {
        Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // 歌名加大一档（用户裁决）；字体维持 GSF —— 宋体只属于 AI 拟题。
                Text(
                    text = writing.noteHeadline(),
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.titleMedium.copy(
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 16.sp,
                        lineHeight = 22.sp,
                    ).withTabularFigures(),
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = writing.writtenAt.toMemoryDayDate(),
                    style = MaterialTheme.typography.labelSmall.withTabularFigures(),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            // 笔记主体 = 黑体（用户正文不再用衬线）。
            Text(
                text = writing.text,
                modifier = Modifier.padding(top = 4.dp),
                style = MaterialTheme.typography.bodyMedium.copy(
                    fontFamily = FontFamily.Default,
                    fontSize = 14.sp,
                    lineHeight = 22.sp,
                ),
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = if (clampBody) 2 else Int.MAX_VALUE,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun MemoryFootnote(text: String) {
    Text(
        text = text,
        modifier = Modifier.padding(top = 8.dp),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

private fun MemoryWriting.noteHeadline(): String = when (kind) {
    MemoryWriting.Kind.SONG_NOTE -> buildString {
        append("《")
        append(trackTitle ?: "Song")
        append("》")
        positionMs?.let { position ->
            append(" · ")
            append(formatTrackDuration((position / 1000L).toInt()))
        }
    }
    MemoryWriting.Kind.ALBUM_NOTE -> "Album note"
    MemoryWriting.Kind.REVIEW -> "Your review"
}

/** 证据句：任一段缺席连分隔点一起消失；全数字 tabular。 */
private fun MemoryEntry.evidenceLine(): String {
    val parts = mutableListOf<String>()
    if (totalTrackCount > 0 && ratedTrackCount > 0) {
        parts += "Rated $ratedTrackCount / $totalTrackCount"
    }
    if (noteCount > 0) {
        parts += "$noteCount " + if (noteCount == 1) "note" else "notes"
    }
    lastPlayedAt?.let { parts += "heard ${it.toRelativeMemoryTime()}" }
    firstPlayedAt?.let { parts += "first played ${it.toMemoryMonthYear()}" }
    return parts.joinToString(" · ")
}

private fun Long.toMemoryMonthYear(): String =
    Instant.ofEpochMilli(this)
        .atZone(ZoneId.systemDefault())
        .format(DateTimeFormatter.ofPattern("MMM yyyy", Locale.getDefault()))

private fun Long.toMemoryDayDate(): String =
    Instant.ofEpochMilli(this)
        .atZone(ZoneId.systemDefault())
        .format(DateTimeFormatter.ofPattern("yyyy.M.d", Locale.getDefault()))

private fun Long.toShortMemoryDate(): String =
    Instant.ofEpochMilli(this)
        .atZone(ZoneId.systemDefault())
        .format(DateTimeFormatter.ofPattern("M.d", Locale.getDefault()))

private fun Long.toRelativeMemoryTime(): String {
    val diff = (System.currentTimeMillis() - this).coerceAtLeast(0L)
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

private fun EdgeAdvanceDirection.toMemoryDeckDirection(): MemoryDeckDirection = when (this) {
    EdgeAdvanceDirection.Backward -> MemoryDeckDirection.Backward
    EdgeAdvanceDirection.Forward -> MemoryDeckDirection.Forward
}

private fun MemoryDeckDirection.toEdgeAdvanceDirection(): EdgeAdvanceDirection = when (this) {
    MemoryDeckDirection.Backward -> EdgeAdvanceDirection.Backward
    MemoryDeckDirection.Forward -> EdgeAdvanceDirection.Forward
}
