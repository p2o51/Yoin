package com.gpo.yoin.ui.memories

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.gpo.yoin.ui.component.ExpressivePageBackground
import com.gpo.yoin.ui.component.YoinLoadingIndicator
import com.gpo.yoin.ui.component.YoinPageWidths
import com.gpo.yoin.ui.component.yoinPageContentWidth
import com.gpo.yoin.ui.experience.EdgeAdvanceDirection
import com.gpo.yoin.ui.experience.MemoriesSessionState
import com.gpo.yoin.ui.experience.ReportMotionPressure
import com.gpo.yoin.ui.experience.RevealState
import com.gpo.yoin.ui.experience.rememberDeckIndicatorTransitionState
import com.gpo.yoin.ui.experience.rememberEdgeAdvanceState
import com.gpo.yoin.ui.experience.rememberYoinHaptics
import com.gpo.yoin.ui.experience.voteHighFrameRate
import com.gpo.yoin.ui.memories.award.MemoriesAwardLifecycle
import com.gpo.yoin.ui.memories.award.rememberMemoriesAwardLifecycle
import com.gpo.yoin.ui.memories.emblem.rememberGrooveReducedMotion
import com.gpo.yoin.ui.memories.showcase.MemoriesDiaryHost
import com.gpo.yoin.ui.memories.showcase.MemoriesDiaryState
import com.gpo.yoin.ui.memories.showcase.MemoriesGestureRouter
import com.gpo.yoin.ui.memories.showcase.MemoriesShowcase
import com.gpo.yoin.ui.memories.showcase.memoriesGestures
import com.gpo.yoin.ui.memories.showcase.rememberMemoriesDiaryState
import com.gpo.yoin.ui.memories.showcase.rememberMemoriesGestureRouter
import com.gpo.yoin.ui.navigation.back.MemoriesBackLevel
import com.gpo.yoin.ui.navigation.back.MemoriesPredictiveBack
import com.gpo.yoin.ui.navigation.back.memoriesDismissCorners
import com.gpo.yoin.ui.navigation.back.rememberMemoriesDismissRules
import com.gpo.yoin.ui.theme.ProvideYoinMotionRole
import com.gpo.yoin.ui.theme.YoinMotion
import com.gpo.yoin.ui.theme.YoinMotionRole
import kotlin.math.abs
import kotlin.math.roundToInt

private val MemoriesAdjacentDeckTrigger = 72.dp
private val MemoriesDeckEnterOffset = 44.dp

/**
 * Memories (ShellOverlayUp). The host translates this page by its reveal q; here live the page's two
 * controllers — q ([RevealState], the retreat to Home) and p ([MemoriesDiaryState], card ⇄ diary) — the one
 * gesture router that feeds them ([memoriesGestures]), the predictive back over both, and the state
 * switching (loading / empty / error / the deck). The deck itself is [MemoriesShowcase].
 */
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
    val snackbarHostState = remember { SnackbarHostState() }
    val haptics = rememberYoinHaptics()

    // ── The two controllers and the router that feeds them ──
    // q: one vertical drag on the whole page, judged in dp — from the top bar
    // 56dp / 450dp/s, from the card body 112dp / 600dp/s; a 350dp/s flick back
    // returns even past the threshold. System back scrubs the same q.
    // p: card ⇄ diary — the Diary button, the diary's pull past its top, the
    // bar as its handle, the bar cover / ⌄, and back at the diary level.
    val dismissRules = rememberMemoriesDismissRules()
    val reducedMotion = rememberGrooveReducedMotion()
    val diaryState = rememberMemoriesDiaryState(reducedMotion = reducedMotion)
    // One award lifecycle per open: Memories unmounts when it closes.
    val awards = rememberMemoriesAwardLifecycle()
    val router = rememberMemoriesGestureRouter(revealState, diaryState, dismissRules)
    SideEffect {
        router.awards = awards
        router.onDismissed = onDismissed
        router.onCommitted = haptics::performConfirm
    }
    // Derived: each flips twice per motion, never per frame (invariant 10).
    val dismissMoving by remember(revealState) {
        derivedStateOf { revealState.fraction > 0.001f && revealState.fraction < 0.999f }
    }
    val diaryMoving by remember(diaryState) { derivedStateOf { abs(diaryState.fraction) > 0.001f } }
    val diaryLevel by remember(diaryState) { derivedStateOf { diaryState.isDiaryLevel } }

    MemoriesPredictiveBack(
        enabled = backEnabled,
        level = if (diaryLevel) MemoriesBackLevel.Diary else MemoriesBackLevel.Card,
        reveal = revealState,
        containerHeightPx = { router.heightPx },
        onDismiss = {
            awards.onDismissCommitted()
            onDismissed()
        },
        diary = diaryState,
        onCardBackStarted = router::onBackStarted,
        onCardBackFinished = router::onBackFinished,
    )

    // The diary's window on the ViewModel: the playhead (narrowed, distinct), drafts, NeoDB.
    val litNoteId = viewModel.litNoteId.collectAsStateWithLifecycle()
    val playingTrackId = viewModel.playingTrackId.collectAsStateWithLifecycle()
    val reviewDrafts = viewModel.reviewDrafts.collectAsStateWithLifecycle()
    val neoDbConfigured = viewModel.neoDbConfigured.collectAsStateWithLifecycle()
    val syncingIds = viewModel.syncingEntityIds.collectAsStateWithLifecycle()
    val playMemoryTrack by rememberUpdatedState(onPlayMemoryTrack)
    val diaryHost = remember(viewModel) {
        object : MemoriesDiaryHost {
            override val litNoteId: String? get() = litNoteId.value
            override val playingTrackId: String? get() = playingTrackId.value

            override fun reviewDraft(memory: MemoryEntry): String? = reviewDrafts.value[memory.stableId]

            override fun saveReview(memory: MemoryEntry, text: String) = viewModel.saveReview(memory, text)

            override fun playTrack(memory: MemoryEntry, track: MemoryTrack) {
                track.playbackIndex?.let { index -> playMemoryTrack(memory, index) }
            }

            override fun playNote(memory: MemoryEntry, track: MemoryTrack, note: MemoryWriting) {
                val trackId = track.trackId ?: return
                val at = note.positionMs ?: 0L
                // already playing this song: seek there; otherwise start it, then seek once it is current
                if (playingTrackId.value != trackId) {
                    val index = track.playbackIndex ?: return
                    playMemoryTrack(memory, index)
                }
                viewModel.requestSeek(trackId, at)
            }

            override val neoDbConfigured: Boolean get() = neoDbConfigured.value

            override fun neoDbSyncing(memory: MemoryEntry): Boolean =
                "${memory.entityProvider}:${memory.entityId}" in syncingIds.value

            override fun pushNeoDb(memory: MemoryEntry) = viewModel.pushToNeoDb(memory)
        }
    }

    LaunchedEffect(viewModel) {
        viewModel.ensureLoaded()
    }

    // One-shot NeoDB 同步事件 → snackbar。未登录事件带一个 "Sign in" action，
    // 点击后通过 [onNavigateToNeoDbSettings] 退出 Memory 层、跳 Settings。
    // The push entry is the quiet line at the diary's end; a diary review that
    // didn't save reports here too (its draft is kept).
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

                is MemoriesOneShotEvent.ReviewSaveFailed -> {
                    snackbarHostState.showSnackbar(
                        message = event.message,
                        duration = SnackbarDuration.Long,
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
                .memoriesDismissCorners(revealState) { router.cornerThresholdPx }
                .voteHighFrameRate(dismissMoving || diaryMoving)
                .onPlaced(router::onRootPlaced)
                // The page's one gesture input. Being a pointer node on the
                // root also makes Memories the hit target, so a tap on blank
                // page (the bar band) never falls through to Home's settings
                // gear underneath; taps are never consumed. It rides the
                // host's translation, so the strip of Home a half-open reveal
                // uncovers stays tappable.
                .memoriesGestures(router),
        ) {
            AnimatedContent(
                targetState = uiState,
                transitionSpec = {
                    YoinMotion.fadeIn(role = YoinMotionRole.Standard) togetherWith
                        YoinMotion.fadeOut(role = YoinMotionRole.Standard)
                },
                // Keyed on the state CLASS: Content-to-Content data updates
                // (deck advance, refreshes) must not re-run the fade.
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
                            diaryState = diaryState,
                            awards = awards,
                            router = router,
                            onHome = {
                                haptics.performClick()
                                awards.onDismissCommitted()
                                onDismissed()
                            },
                            onOpenAlbum = onOpenAlbum,
                            onAdvanceDeck = viewModel::advanceDeck,
                            onCurrentPageChange = viewModel::setCurrentPage,
                            diaryHost = diaryHost,
                            reducedMotion = reducedMotion,
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
private fun MemoriesEmptyState() {
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
    diaryState: MemoriesDiaryState,
    awards: MemoriesAwardLifecycle,
    router: MemoriesGestureRouter,
    onHome: () -> Unit,
    onOpenAlbum: (MemoryEntry) -> Unit,
    onAdvanceDeck: (MemoryDeckDirection) -> Unit,
    onCurrentPageChange: (Int) -> Unit,
    diaryHost: MemoriesDiaryHost,
    reducedMotion: Boolean,
) {
    // Derived: the deck's pull frames flip this once, not per frame.
    val auroraVisible by remember(revealState) { derivedStateOf { revealState.fraction < 0.999f } }
    val density = LocalDensity.current
    val haptics = rememberYoinHaptics()
    val adjacentDeckTriggerPx = with(density) { MemoriesAdjacentDeckTrigger.toPx() }
    val deckEnterOffsetPx = with(density) { MemoriesDeckEnterOffset.toPx() }
    val edgeAdvanceState = rememberEdgeAdvanceState(triggerPx = adjacentDeckTriggerPx)

    LaunchedEffect(contentState.deckRevision) {
        edgeAdvanceState.reset()
    }
    // Only the content state has a top bar and a card face; without them the
    // whole page is card body and a pull-down has nothing to rubber-band.
    DisposableEffect(router) {
        router.cardPresent = true
        onDispose { router.onContentGone() }
    }

    Box(modifier = Modifier.fillMaxSize()) {
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
                initialPage = sessionState.currentPage.coerceIn(0, memories.lastIndex.coerceAtLeast(0)),
                pageCount = { memories.size },
            )
            val adjacentDeckDirection = edgeAdvanceState.direction?.toMemoryDeckDirection()

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
            // Deck-switch motion is owned by the AnimatedContent pane (the dots
            // ride it); the indicator only adds the live edge-pull hint, so the
            // deck transition inputs are pinned to their resting values.
            val edgeHint = rememberDeckIndicatorTransitionState(
                deckTransitionProgress = 1f,
                deckTransitionDirection = EdgeAdvanceDirection.Forward,
                adjacentProgress = edgeAdvanceState.progress,
                adjacentDirection = adjacentDeckDirection?.toEdgeAdvanceDirection(),
            )

            MemoriesShowcase(
                memories = memories,
                pagerState = pagerState,
                reveal = revealState,
                diary = diaryState,
                awards = awards,
                onHome = onHome,
                onOpenAlbum = onOpenAlbum,
                onBarPlaced = router::onBarPlaced,
                pagerConnection = pagerEdgeConnection,
                edgeHint = edgeHint,
                auroraVisible = auroraVisible,
                reducedMotion = reducedMotion,
                diaryHost = diaryHost,
                router = router,
                awardBlocked = { router.backBusy },
            )
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
