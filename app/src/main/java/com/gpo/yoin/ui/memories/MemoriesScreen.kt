package com.gpo.yoin.ui.memories

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.toShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalDensity
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
import com.gpo.yoin.ui.component.rememberExpressiveBackdropColors
import com.gpo.yoin.ui.experience.DeckIndicatorTransitionState
import com.gpo.yoin.ui.experience.EdgeAdvanceDirection
import com.gpo.yoin.ui.experience.MemoryScrollPosition
import com.gpo.yoin.ui.experience.MemoriesSessionState
import com.gpo.yoin.ui.experience.ReportMotionPressure
import com.gpo.yoin.ui.experience.rememberDeckIndicatorTransitionState
import com.gpo.yoin.ui.experience.rememberEdgeAdvanceState
import com.gpo.yoin.ui.experience.rememberPullToDismissState
import com.gpo.yoin.ui.navigation.back.BackMotionTokens
import com.gpo.yoin.ui.theme.ProvideYoinMotionRole
import com.gpo.yoin.ui.theme.GoogleSansFlex
import com.gpo.yoin.ui.theme.YoinMotion
import com.gpo.yoin.ui.theme.YoinMotionRole
import com.gpo.yoin.ui.theme.YoinShapeTokens
import com.gpo.yoin.ui.theme.withTabularFigures
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.abs
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.distinctUntilChanged

private val MemoriesAdjacentDeckTrigger = 72.dp
private val MemoriesDeckEnterOffset = 44.dp
private val MemoriesScoreShapeWidth = 148.dp
private val MemoriesScoreShapeHeight = 118.dp

@Composable
fun MemoriesScreen(
    viewModel: MemoriesViewModel,
    dismissFraction: Float,
    onDismissGestureProgress: (Float) -> Unit,
    onDismissGestureCommit: suspend () -> Unit,
    onDismissGestureCancel: suspend () -> Unit,
    onPlayMemoryTrack: (MemoryEntry, Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val sessionState by viewModel.sessionState.collectAsStateWithLifecycle()

    LaunchedEffect(viewModel) {
        viewModel.ensureLoaded()
    }

    ReportMotionPressure(
        tag = "memories",
        isHighPressure = uiState is MemoriesUiState.Loading ||
            (uiState as? MemoriesUiState.Content)?.isLoadingAdjacentDeck == true,
    )

    ProvideYoinMotionRole(role = YoinMotionRole.Expressive) {
        ExpressivePageBackground(modifier = modifier) {
            when (val state = uiState) {
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
                        dismissFraction = dismissFraction,
                        onDismissGestureProgress = onDismissGestureProgress,
                        onDismissGestureCommit = onDismissGestureCommit,
                        onDismissGestureCancel = onDismissGestureCancel,
                        onPlayMemoryTrack = onPlayMemoryTrack,
                        onAdvanceDeck = viewModel::advanceDeck,
                        onCurrentPageChange = viewModel::setCurrentPage,
                        onMemoryScrollChange = viewModel::setMemoryScroll,
                    )
                }
            }
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
    dismissFraction: Float,
    onDismissGestureProgress: (Float) -> Unit,
    onDismissGestureCommit: suspend () -> Unit,
    onDismissGestureCancel: suspend () -> Unit,
    onPlayMemoryTrack: (MemoryEntry, Int) -> Unit,
    onAdvanceDeck: (MemoryDeckDirection) -> Unit,
    onCurrentPageChange: (Int) -> Unit,
    onMemoryScrollChange: (Long, MemoryScrollPosition) -> Unit,
) {
    val density = LocalDensity.current
    val dismissTriggerPx = with(density) { BackMotionTokens.MemoriesDismissTrigger.toPx() }
    val adjacentDeckTriggerPx = with(density) { MemoriesAdjacentDeckTrigger.toPx() }
    val deckEnterOffsetPx = with(density) { MemoriesDeckEnterOffset.toPx() }
    val deckEntranceProgress = remember(contentState.deckRevision) {
        Animatable(if (contentState.deckRevision <= 1) 1f else 0f)
    }
    val dismissState = rememberPullToDismissState(triggerPx = dismissTriggerPx)
    val edgeAdvanceState = rememberEdgeAdvanceState(triggerPx = adjacentDeckTriggerPx)
    val deckEntranceSpec = YoinMotion.defaultSpatialSpec<Float>(role = YoinMotionRole.Expressive)

    LaunchedEffect(contentState.deckRevision) {
        dismissState.reset()
        onDismissGestureProgress(0f)
        edgeAdvanceState.reset()
        if (contentState.deckRevision <= 1) return@LaunchedEffect
        deckEntranceProgress.animateTo(
            targetValue = 1f,
            animationSpec = deckEntranceSpec,
        )
    }

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val containerHeightPx = with(density) { maxHeight.toPx().coerceAtLeast(1f) }
        val dismissPreviewFraction = ((dismissFraction * containerHeightPx) / dismissTriggerPx)
            .coerceIn(0f, 1f)

        key(contentState.deckRevision) {
            val memories = contentState.memories
            val pagerState = rememberPagerState(
                initialPage = sessionState.currentPage.coerceIn(0, memories.lastIndex),
                pageCount = { memories.size },
            )
            val coroutineScope = rememberCoroutineScope()
            var isDismissingToHome by remember(contentState.deckRevision) { mutableStateOf(false) }
            var latestContainerHeightPx by remember { mutableFloatStateOf(containerHeightPx) }
            val selectedIndex = pagerState.currentPage.coerceIn(0, memories.lastIndex)
            val selectedMemory = memories[selectedIndex]
            val deckTransitionDirection = contentState.deckDirection
            val adjacentDeckDirection = edgeAdvanceState.direction?.toMemoryDeckDirection()

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
                contentState.isLoadingAdjacentDeck,
                onAdvanceDeck,
            ) {
                object : NestedScrollConnection {
                    override fun onPostScroll(
                        consumed: Offset,
                        available: Offset,
                        source: NestedScrollSource,
                    ): Offset {
                        if (source != NestedScrollSource.UserInput || contentState.isLoadingAdjacentDeck) {
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
                    .graphicsLayer {
                        val directionMultiplier = when (deckTransitionDirection) {
                            MemoryDeckDirection.Backward -> -1f
                            MemoryDeckDirection.Forward -> 1f
                        }
                        translationX = (1f - deckEntranceProgress.value) * directionMultiplier * deckEnterOffsetPx
                        alpha = 0.8f + deckEntranceProgress.value * 0.2f
                    }
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
                    deckTransitionProgress = deckEntranceProgress.value,
                    deckTransitionDirection = deckTransitionDirection,
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
                    val storedScrollPosition = sessionState.perMemoryScrollOffsets[memory.sourceActivityId]
                        ?: MemoryScrollPosition()
                    val listState = remember(memory.sourceActivityId, contentState.deckRevision) {
                        LazyListState(
                            firstVisibleItemIndex = storedScrollPosition.firstVisibleItemIndex,
                            firstVisibleItemScrollOffset = storedScrollPosition.firstVisibleItemScrollOffset,
                        )
                    }
                    LaunchedEffect(listState, memory.sourceActivityId) {
                        snapshotFlow {
                            MemoryScrollPosition(
                                firstVisibleItemIndex = listState.firstVisibleItemIndex,
                                firstVisibleItemScrollOffset = listState.firstVisibleItemScrollOffset,
                            )
                        }
                            .distinctUntilChanged()
                            .collect { position ->
                                onMemoryScrollChange(memory.sourceActivityId, position)
                            }
                    }
                    val dismissConnection = remember(
                        listState,
                        onDismissGestureCommit,
                        onDismissGestureCancel,
                    ) {
                        object : NestedScrollConnection {
                            override fun onPreScroll(
                                available: Offset,
                                source: NestedScrollSource,
                            ): Offset {
                                if (source != NestedScrollSource.UserInput || isDismissingToHome) {
                                    return Offset.Zero
                                }
                                if (available.y < 0f && listState.isAtTop()) {
                                    val triggered = dismissState.registerPull(deltaPx = -available.y)
                                    onDismissGestureProgress(
                                        dismissState.pullPx
                                            .div(latestContainerHeightPx)
                                            .coerceIn(0f, 1f),
                                    )
                                    if (triggered) {
                                        isDismissingToHome = true
                                        coroutineScope.launch {
                                            onDismissGestureCommit()
                                            dismissState.reset()
                                            isDismissingToHome = false
                                        }
                                    }
                                    return Offset(0f, available.y)
                                }
                                if (available.y > 0f && dismissState.pullPx > 0f) {
                                    dismissState.release(available.y)
                                    onDismissGestureProgress(
                                        dismissState.pullPx
                                            .div(latestContainerHeightPx)
                                            .coerceIn(0f, 1f),
                                    )
                                    return Offset(0f, available.y)
                                }
                                return Offset.Zero
                            }

                            override suspend fun onPreFling(available: Velocity): Velocity {
                                return when {
                                    isDismissingToHome -> available
                                    dismissState.pullPx > 0f -> {
                                        onDismissGestureCancel()
                                        dismissState.reset()
                                        onDismissGestureProgress(0f)
                                        available
                                    }
                                    else -> Velocity.Zero
                                }
                            }
                        }
                    }
                    val navBottom = WindowInsets.navigationBars.asPaddingValues()
                        .calculateBottomPadding()
                    LazyColumn(
                        state = listState,
                        modifier = Modifier
                            .fillMaxSize()
                            .nestedScroll(dismissConnection),
                        contentPadding = PaddingValues(
                            top = 20.dp,
                            start = 20.dp,
                            end = 20.dp,
                            bottom = 132.dp + navBottom,
                        ),
                        verticalArrangement = Arrangement.spacedBy(20.dp),
                    ) {
                        item {
                            MemoriesHero(
                                memory = memory,
                                seedColor = pageColors.baseColor,
                            )
                        }

                        if (memory.tracks.isNotEmpty()) {
                            itemsIndexed(
                                items = memory.tracks,
                                key = { _, track -> track.stableId },
                            ) { index, track ->
                                MemoryTrackRow(
                                    index = index + 1,
                                    track = track,
                                    accentColor = pageColors.baseColor,
                                    onClick = { onPlayMemoryTrack(memory, index) },
                                )
                            }
                        }
                    }
                }
            }
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
                    translationY = -(dismissFraction * containerHeightPx) * 0.3f
                    alpha = 0.4f + dismissPreviewFraction * 0.6f
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
    deckTransitionProgress: Float,
    deckTransitionDirection: MemoryDeckDirection,
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
            deckTransitionProgress = deckTransitionProgress,
            deckTransitionDirection = deckTransitionDirection,
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
    deckTransitionProgress: Float,
    deckTransitionDirection: MemoryDeckDirection,
    adjacentDeckProgress: Float,
    adjacentDeckDirection: MemoryDeckDirection?,
    onSelect: (Int) -> Unit,
) {
    val continuousPosition = selectedIndex + currentPageOffsetFraction
    val indicatorTransitionState: DeckIndicatorTransitionState = rememberDeckIndicatorTransitionState(
        deckTransitionProgress = deckTransitionProgress,
        deckTransitionDirection = deckTransitionDirection.toEdgeAdvanceDirection(),
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

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun MemoriesHero(
    memory: MemoryEntry,
    seedColor: Color,
) {
    val scoreContainerColor = lerp(
        start = seedColor,
        stop = MaterialTheme.colorScheme.surface,
        fraction = 0.18f,
    )
    val scoreContentColor = if (scoreContainerColor.luminance() > 0.56f) {
        Color(0xFF111318)
    } else {
        Color.White
    }
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = memory.title,
                style = MaterialTheme.typography.headlineMedium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = memory.supportingText,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            memory.metaText?.let { meta ->
                Text(
                    text = meta,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.secondary,
                )
            }
            Column(
                modifier = Modifier.padding(top = 2.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Surface(
                    modifier = Modifier.size(
                        width = MemoriesScoreShapeWidth,
                        height = MemoriesScoreShapeHeight,
                    ),
                    shape = MaterialShapes.Bun.toShape(),
                    color = scoreContainerColor,
                    tonalElevation = 0.dp,
                    shadowElevation = 0.dp,
                ) {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = memory.scoreText,
                            style = MaterialTheme.typography.headlineMedium.withTabularFigures(),
                            color = scoreContentColor,
                            textAlign = TextAlign.Center,
                            maxLines = 1,
                        )
                    }
                }
                memory.scoreSupportingText?.let { supporting ->
                    Text(
                        text = supporting,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                    )
                }
            }
        }

        Column(
            modifier = Modifier.weight(0.9f),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            ExpressiveMediaArtwork(
                model = memory.coverArtUrl,
                contentDescription = memory.title,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(172.dp),
                shape = YoinShapeTokens.ExtraLarge,
                fallbackIcon = Icons.Filled.LibraryMusic,
                tonalElevation = 0.dp,
                shadowElevation = 0.dp,
            )
            memory.footerText?.let { footer ->
                Text(
                    text = footer,
                    style = MaterialTheme.typography.labelLarge.withTabularFigures(),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}

@Composable
private fun MemoryTrackRow(
    index: Int,
    track: MemoryTrack,
    accentColor: Color,
    onClick: () -> Unit,
) {
    Surface(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        color = Color.Transparent,
        tonalElevation = 0.dp,
        shadowElevation = 0.dp,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = index.toString(),
                style = MaterialTheme.typography.labelLarge.withTabularFigures(),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(20.dp),
                textAlign = TextAlign.Center,
            )
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(
                    text = track.title,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = track.artist,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            track.durationSeconds?.let { duration ->
                Text(
                    text = duration.toCompactDuration(),
                    style = MaterialTheme.typography.labelLarge.withTabularFigures(),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                text = track.rating?.formatMemoryTrackRating() ?: "--",
                style = MaterialTheme.typography.labelLarge.withTabularFigures(),
                color = if ((track.rating ?: 0f) > 0f) {
                    accentColor
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.56f)
                },
                modifier = Modifier.width(36.dp),
                textAlign = TextAlign.End,
            )
        }
    }
}

private fun Long.toShortMemoryDate(): String =
    Instant.ofEpochMilli(this)
        .atZone(ZoneId.systemDefault())
        .format(DateTimeFormatter.ofPattern("M.d", Locale.getDefault()))

private fun Int.toCompactDuration(): String {
    val minutes = this / 60
    val seconds = this % 60
    return "%d:%02d".format(minutes, seconds)
}

private fun Float.formatMemoryTrackRating(): String =
    String.format(Locale.US, "%.1f", this)

private fun LazyListState.isAtTop(): Boolean =
    firstVisibleItemIndex == 0 && firstVisibleItemScrollOffset == 0

private fun EdgeAdvanceDirection.toMemoryDeckDirection(): MemoryDeckDirection = when (this) {
    EdgeAdvanceDirection.Backward -> MemoryDeckDirection.Backward
    EdgeAdvanceDirection.Forward -> MemoryDeckDirection.Forward
}

private fun MemoryDeckDirection.toEdgeAdvanceDirection(): EdgeAdvanceDirection = when (this) {
    MemoryDeckDirection.Backward -> EdgeAdvanceDirection.Backward
    MemoryDeckDirection.Forward -> EdgeAdvanceDirection.Forward
}
