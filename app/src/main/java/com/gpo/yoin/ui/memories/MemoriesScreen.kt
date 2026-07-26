package com.gpo.yoin.ui.memories

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.LibraryMusic
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
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
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.gpo.yoin.ui.component.ExpressiveMediaArtwork
import com.gpo.yoin.ui.component.ExpressivePageBackground
import com.gpo.yoin.ui.component.YoinLoadingIndicator
import com.gpo.yoin.ui.component.formatTrackDuration
import com.gpo.yoin.ui.component.rememberExpressiveBackdropColors
import com.gpo.yoin.ui.experience.DeckIndicatorTransitionState
import com.gpo.yoin.ui.experience.EdgeAdvanceDirection
import com.gpo.yoin.ui.experience.LocalMotionProfile
import com.gpo.yoin.ui.experience.MemoriesSessionState
import com.gpo.yoin.ui.experience.MotionProfile
import com.gpo.yoin.ui.experience.ReportMotionPressure
import com.gpo.yoin.ui.experience.RevealState
import com.gpo.yoin.ui.experience.rememberDeckIndicatorTransitionState
import com.gpo.yoin.ui.experience.rememberEdgeAdvanceState
import com.gpo.yoin.ui.experience.rememberYoinHaptics
import com.gpo.yoin.ui.navigation.back.BackMotionTokens
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

@Composable
fun MemoriesScreen(
    viewModel: MemoriesViewModel,
    revealState: RevealState,
    onDismissed: () -> Unit,
    onPlayMemoryTrack: (MemoryEntry, Int) -> Unit,
    onOpenAlbum: (MemoryEntry) -> Unit,
    onNavigateToNeoDbSettings: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val sessionState by viewModel.sessionState.collectAsStateWithLifecycle()
    val syncingIds by viewModel.syncingEntityIds.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }

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
        ExpressivePageBackground(modifier = modifier) {
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
                            onDismissed = onDismissed,
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
    onDismissed: () -> Unit,
    onPlayMemoryTrack: (MemoryEntry, Int) -> Unit,
    onOpenAlbum: (MemoryEntry) -> Unit,
    onAdvanceDeck: (MemoryDeckDirection) -> Unit,
    onCurrentPageChange: (Int) -> Unit,
    syncingEntityIds: Set<String> = emptySet(),
    onSyncToNeoDb: (MemoryEntry) -> Unit = {},
) {
    val density = LocalDensity.current
    val haptics = rememberYoinHaptics()
    val dismissHintPx = with(density) { BackMotionTokens.MemoriesDismissTrigger.toPx() }
    val adjacentDeckTriggerPx = with(density) { MemoriesAdjacentDeckTrigger.toPx() }
    val deckEnterOffsetPx = with(density) { MemoriesDeckEnterOffset.toPx() }
    val edgeAdvanceState = rememberEdgeAdvanceState(triggerPx = adjacentDeckTriggerPx)

    LaunchedEffect(contentState.deckRevision) {
        edgeAdvanceState.reset()
    }

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val containerHeightPx = with(density) { maxHeight.toPx().coerceAtLeast(1f) }

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
            var isCommittedToDismiss by remember(deckState.deckRevision) { mutableStateOf(false) }
            var latestContainerHeightPx by remember { mutableFloatStateOf(containerHeightPx) }
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

            LaunchedEffect(containerHeightPx) {
                latestContainerHeightPx = containerHeightPx
            }

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
                        visible = revealState.fraction < 0.999f,
                    )
                    .padding(top = WindowInsets.systemBars.asPaddingValues().calculateTopPadding() + 12.dp),
            ) {
                MemoriesHeader(
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
                        .padding(horizontal = 20.dp),
                )

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
                    // dismiss。draggable 与 pager 的横向手势各占一轴。
                    val dismissDragState = rememberDraggableState { delta ->
                        if (!isCommittedToDismiss) {
                            revealState.dragBy(delta, latestContainerHeightPx)
                        }
                    }
                    MemorySealCard(
                        memory = memory,
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
                        modifier = Modifier
                            .fillMaxSize()
                            .draggable(
                                state = dismissDragState,
                                orientation = Orientation.Vertical,
                                onDragStopped = { velocity ->
                                    if (revealState.fraction > 0f) {
                                        isCommittedToDismiss = true
                                        try {
                                            val target = revealState.settle(
                                                velocityPxPerSec = velocity,
                                                containerPx = latestContainerHeightPx,
                                            )
                                            if (target >= 1f) {
                                                haptics.performConfirm()
                                                onDismissed()
                                            }
                                        } finally {
                                            isCommittedToDismiss = false
                                        }
                                    }
                                },
                            ),
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
            imageVector = Icons.Filled.KeyboardArrowUp,
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
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
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

    Column(
        modifier = modifier.padding(start = 20.dp, end = 20.dp, top = 20.dp),
    ) {
        // ── 标题区：专辑名 + 艺人·年份，72dp 裸封面（点按即播） ──
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
                    style = MaterialTheme.typography.headlineLarge.copy(
                        fontWeight = FontWeight.SemiBold,
                    ),
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
                fallbackIcon = Icons.Filled.LibraryMusic,
                tonalElevation = 0.dp,
                shadowElevation = 0.dp,
            )
        }

        Spacer(modifier = Modifier.height(24.dp))

        // ── 印章行：148dp 曲奇印章 × AI 拟题 + 正文 ──
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            MemorySeal(
                memory = memory,
                scheme = memoryColorScheme,
            )
            Column(
                modifier = Modifier
                    .weight(1f)
                    .height(MemorySealSize),
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
                            fontSize = 22.sp,
                            lineHeight = 30.sp,
                        ),
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(24.dp))

        // ── 全宽乐评区（方案 B）：长评终于有配得上它的面积。硬截断，
        //    点击开 sheet 读全文；笔记不再占卡面（收进底部按钮）。 ──
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
                maxLines = 8,
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
                Spacer(modifier = Modifier.height(16.dp))
            }
            Text(
                text = "How did this album land for you?",
                style = MaterialTheme.typography.bodySmall.copy(
                    fontStyle = FontStyle.Italic,
                ),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
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

        // ── 弹性呼吸：notes 与锚底 footnotes 之间 ──
        Spacer(modifier = Modifier.weight(1f))

        // ── footnotes：证据句 + NeoDB 五态（锚底，四卡同位） ──
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

        Spacer(modifier = Modifier.height(12.dp))

        // ── 底部按钮排：笔记入口（带条数）在左，前往专辑在右。
        //    笔记卡从卡面退场后，这颗按钮就是它们唯一的家。 ──
        Row(
            modifier = Modifier.align(Alignment.CenterHorizontally),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (memory.writings.isNotEmpty()) {
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

        Spacer(modifier = Modifier.height(navBottom + 44.dp))
    }

    if (showAllNotes) {
        ModalBottomSheet(
            onDismissRequest = { showAllNotes = false },
        ) {
            LazyColumn(
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

    Box(
        modifier = Modifier.size(MemorySealSize),
        contentAlignment = Alignment.Center,
    ) {
        // aurora halo 的近似：印章中心的一圈同调色光晕。
        Box(
            modifier = Modifier
                .requiredSize(MemorySealSize * 1.7f)
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
                        fontSize = 44.sp,
                        lineHeight = 48.sp,
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
