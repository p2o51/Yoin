package com.gpo.yoin.ui.detail

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.outlined.Insights
import androidx.compose.material.icons.rounded.IosShare
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.BiasAlignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.lerp
import com.gpo.yoin.ui.component.ExpressiveMediaArtwork
import com.gpo.yoin.ui.component.ExpressivePageBackground
import com.gpo.yoin.ui.component.YoinDropdownMenu
import com.gpo.yoin.ui.component.YoinDropdownMenuItem
import com.gpo.yoin.ui.component.YoinLoadingIndicator
import com.gpo.yoin.ui.component.minimumTouchTarget
import com.gpo.yoin.ui.experience.RevealState
import com.gpo.yoin.ui.experience.rememberRevealState
import com.gpo.yoin.ui.theme.ProvideYoinMotionRole
import com.gpo.yoin.ui.theme.YoinMotion
import com.gpo.yoin.ui.theme.rememberCoverColorScheme
import com.gpo.yoin.ui.theme.YoinMotionRole
import com.gpo.yoin.ui.theme.YoinShapeTokens
import com.gpo.yoin.ui.theme.YoinTheme

// At or below this track count the cover docks to a big rounded "capsule"; above
// it, to the thin full-bleed wavy band (a long list needs the band's vertical room).
private const val AlbumManyTracksThreshold = 5

// Velocity-or-position settle decision for the hero<->tracklist reshape,
// mirroring RevealState.chooseTarget but WITHOUT animating (the single
// reconcile effect owns the animation). Returns true = expanded (track list).
// rawVelocity is the finger velocity in px/s; up (negative) expands.
private fun chooseExpandedTarget(fraction: Float, rawVelocity: Float, travelPx: Float): Boolean {
    val velocityFraction = if (travelPx > 0f) rawVelocity / travelPx else 0f
    val target = when {
        velocityFraction <= -1.6f -> 0f
        velocityFraction >= 1.6f -> 1f
        fraction < 0.5f -> 0f
        else -> 1f
    }
    return target <= 0f
}

@Composable
fun AlbumDetailScreen(
    uiState: AlbumDetailUiState,
    onBackClick: () -> Unit,
    onSongClick: (songId: String) -> Unit,
    onToggleStar: (songId: String) -> Unit,
    onRetry: () -> Unit,
    notedSongIds: Set<String> = emptySet(),
    expandedSongId: String? = null,
    expandedNoteBundle: AlbumExpandedNoteBundle? = null,
    onToggleExpandedSong: (songId: String) -> Unit = {},
    onRatingCommit: (Float) -> Unit = {},
    onReviewDraftChange: (String) -> Unit = {},
    onSaveReview: () -> Unit = {},
    onPlayAlbum: () -> Unit = {},
    onShufflePlay: () -> Unit = {},
    onShare: () -> Unit = {},
    onOpenArtist: (() -> Unit)? = null,
    isPlaying: Boolean = false,
    playbackSignal: Float = 0f,
    // Slot docked bottom-end on the toolbar row (detail mini-player).
    bottomEndAccessory: (@Composable () -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val content = uiState as? AlbumDetailUiState.Content
    val pageAccent = rememberDetailPageAccent(content?.coverArtUrl)

    ProvideYoinMotionRole(role = YoinMotionRole.Expressive) {
        ExpressivePageBackground(
            accentColor = pageAccent,
            isPlaying = isPlaying,
            playbackSignal = playbackSignal,
            modifier = modifier,
        ) {
            Box(modifier = Modifier.fillMaxSize()) {
            when (uiState) {
                is AlbumDetailUiState.Loading ->
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        YoinLoadingIndicator()
                    }

                is AlbumDetailUiState.Error ->
                    AlbumErrorState(message = uiState.message, onRetry = onRetry, onBackClick = onBackClick)

                is AlbumDetailUiState.Content ->
                    AlbumDetailContent(
                        content = uiState,
                        onBackClick = onBackClick,
                        onSongClick = onSongClick,
                        onToggleStar = onToggleStar,
                        notedSongIds = notedSongIds,
                        expandedSongId = expandedSongId,
                        expandedNoteBundle = expandedNoteBundle,
                        onToggleExpandedSong = onToggleExpandedSong,
                        onRatingCommit = onRatingCommit,
                        onReviewDraftChange = onReviewDraftChange,
                        onSaveReview = onSaveReview,
                        onPlayAlbum = onPlayAlbum,
                        onShufflePlay = onShufflePlay,
                        onShare = onShare,
                        onOpenArtist = onOpenArtist,
                        bottomEndAccessory = bottomEndAccessory,
                    )
            }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun AlbumDetailContent(
    content: AlbumDetailUiState.Content,
    onBackClick: () -> Unit,
    onSongClick: (songId: String) -> Unit,
    onToggleStar: (songId: String) -> Unit,
    notedSongIds: Set<String>,
    expandedSongId: String?,
    expandedNoteBundle: AlbumExpandedNoteBundle?,
    onToggleExpandedSong: (songId: String) -> Unit,
    onRatingCommit: (Float) -> Unit,
    onReviewDraftChange: (String) -> Unit,
    onSaveReview: () -> Unit,
    onPlayAlbum: () -> Unit,
    onShufflePlay: () -> Unit,
    onShare: () -> Unit,
    onOpenArtist: (() -> Unit)?,
    bottomEndAccessory: (@Composable () -> Unit)?,
) {
    val scope = rememberCoroutineScope()

    // Material color roles seeded from the album's OWN cover (MCU
    // SchemeExpressive) — not raw Palette swatches, which read "off" used as
    // theme color. Falls back to the app theme while the cover loads / if it
    // yields no seed. Animate the block + title colors so the resolve doesn't pop.
    val coverScheme = rememberCoverColorScheme(content.coverArtUrl)
    val s = coverScheme ?: MaterialTheme.colorScheme
    val primaryBlock by animateColorAsState(s.primary, YoinMotion.effectsSpring(), label = "albumPrimaryBlock")
    val secondaryBlock by animateColorAsState(s.secondary, YoinMotion.effectsSpring(), label = "albumSecondaryBlock")
    val titleColor by animateColorAsState(s.primary, YoinMotion.effectsSpring(), label = "albumTitleColor")
    val accentText = s.secondary
    val bunContainer = s.primaryContainer
    val bunContent = s.onPrimaryContainer
    val playContent = s.onPrimary
    val toolbarTint = rememberDetailToolbarTint(s)

    // Pull-up reshape: reuse RevealState. fraction 1 = hero, 0 = track list.
    val revealState = rememberRevealState(initialFraction = 1f)
    var expanded by rememberSaveable(content.albumId) { mutableStateOf(false) }
    // SINGLE settle owner (cf. the NowPlaying "ONE settle driver" rule):
    // `expanded` is the durable source of truth; this one effect drives the
    // reveal fraction to match it — and re-asserts after a cancelled gesture or
    // a process-death/config-change restore, so the two can never wedge apart.
    // Gestures only commit the bool. launchAnimateTo is settleJob-tracked, so a
    // fresh drag (dragBy) cancels it — there is no concurrent-animator race.
    LaunchedEffect(expanded) {
        revealState.launchAnimateTo(scope, if (expanded) 0f else 1f)
    }

    // Horizontal pager: page 0 = this overview, page 1 = scores/About sample.
    val pagerState = rememberPagerState(pageCount = { 2 })

    // Back always finishes the Activity (native cross-Activity predictive back):
    // the pulled-up track list is NOT a back stop, so one back press leaves the
    // page instead of first collapsing the reshape.

    var showEditSheet by remember { mutableStateOf(false) }

    Box(modifier = Modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize()) {
            AlbumTopHeader(
                albumName = content.albumName,
                artistName = content.artistName,
                year = content.year,
                titleColor = titleColor,
                accentText = accentText,
                pageFraction = pagerState.currentPage + pagerState.currentPageOffsetFraction,
                onBackClick = onBackClick,
            )

            HorizontalPager(
                state = pagerState,
                modifier = Modifier
                    .fillMaxSize()
                    .weight(1f),
            ) { page ->
                when (page) {
                    0 -> AlbumOverviewPage(
                        content = content,
                        primaryBlock = primaryBlock,
                        secondaryBlock = secondaryBlock,
                        accent = primaryBlock,
                        bunContainer = bunContainer,
                        bunContent = bunContent,
                        revealState = revealState,
                        expanded = expanded,
                        onExpandedCommit = { expanded = it },
                        notedSongIds = notedSongIds,
                        expandedSongId = expandedSongId,
                        expandedNoteBundle = expandedNoteBundle,
                        onSongClick = onSongClick,
                        onToggleStar = onToggleStar,
                        onToggleExpandedSong = onToggleExpandedSong,
                        onEditComment = { showEditSheet = true },
                    )

                    else -> AlbumSecondaryPage()
                }
            }
        }

        // Pinned bottom toolbar — present on both pages, like the Figma frames.
        AlbumBottomToolbar(
            playContainer = primaryBlock,
            playContent = playContent,
            toolbarContainer = toolbarTint,
            onPlay = onPlayAlbum,
            onShuffle = onShufflePlay,
            onShare = onShare,
            onOpenArtist = onOpenArtist,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .navigationBarsPadding()
                .padding(bottom = 12.dp),
        )

        // Now-playing dock, same row as the toolbar (identical inset chain,
        // so the two are vertically centered on each other by construction).
        bottomEndAccessory?.let { accessory ->
            Box(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .navigationBarsPadding()
                    // 12dp baseline + 4dp: centers the 64dp dock on the
                    // 72dp-tall floating toolbar beside it.
                    .padding(end = 16.dp, bottom = 16.dp),
            ) {
                accessory()
            }
        }
    }

    if (showEditSheet) {
        AlbumRatingReviewSheet(
            userRating = content.userRating,
            userReview = content.userReview,
            reviewHasUnsavedEdits = content.reviewHasUnsavedEdits,
            onRatingCommit = onRatingCommit,
            onReviewDraftChange = onReviewDraftChange,
            onSaveReview = onSaveReview,
            onDismiss = { showEditSheet = false },
        )
    }
}

@Composable
private fun AlbumTopHeader(
    albumName: String,
    artistName: String,
    year: Int?,
    titleColor: Color,
    accentText: Color,
    pageFraction: Float,
    onBackClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .padding(start = 8.dp, end = 16.dp, top = 4.dp, bottom = 6.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            DetailBackButton(onClick = onBackClick)
            // Air between the button's touch halo and the title cluster —
            // flush against the arrow it read as one crowded blob.
            Spacer(modifier = Modifier.width(14.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = albumName,
                    style = MaterialTheme.typography.headlineSmall,
                    color = titleColor,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Row {
                    Text(
                        text = artistName,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = buildString {
                            append("  ·  Album")
                            year?.let { append(" $it") }
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = accentText,
                        maxLines = 1,
                    )
                }
            }
        }
        AlbumPageDots(
            activeFraction = pageFraction,
            activeColor = accentText,
            inactiveColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.3f),
            modifier = Modifier
                .padding(top = 6.dp)
                .align(Alignment.CenterHorizontally),
        )
    }
}

@Composable
private fun AlbumOverviewPage(
    content: AlbumDetailUiState.Content,
    primaryBlock: Color,
    secondaryBlock: Color,
    accent: Color,
    bunContainer: Color,
    bunContent: Color,
    revealState: RevealState,
    expanded: Boolean,
    onExpandedCommit: (Boolean) -> Unit,
    notedSongIds: Set<String>,
    expandedSongId: String?,
    expandedNoteBundle: AlbumExpandedNoteBundle?,
    onSongClick: (songId: String) -> Unit,
    onToggleStar: (songId: String) -> Unit,
    onToggleExpandedSong: (songId: String) -> Unit,
    onEditComment: () -> Unit,
) {
    val density = LocalDensity.current
    val reshapeScope = rememberCoroutineScope()
    val listState = rememberLazyListState()
    val isMany = content.songs.size > AlbumManyTracksThreshold
    // The reshape only traverses the upper region, not the full page height, so
    // scale the drag against a fraction of it for a closer-to-1:1 finger feel.
    // Held in state so the remembered draggable / connection read the latest.
    val travelPx = remember { mutableFloatStateOf(1f) }

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        travelPx.floatValue = with(density) { maxHeight.toPx() } * 0.55f
        val maxW = maxWidth

        // ---- Gesture wiring ----------------------------------------------
        // Hero → tracks: a vertical draggable on the page (disabled once
        // expanded, so the list owns its own scroll). Tracks → hero: a
        // nested-scroll connection that intercepts pull-down at list top.
        val dragState = rememberDraggableState { delta ->
            revealState.dragBy(-delta, travelPx.floatValue)
        }
        val collapseConnection = remember(revealState, listState, reshapeScope) {
            object : NestedScrollConnection {
                override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                    if (source != NestedScrollSource.UserInput) return Offset.Zero
                    val atTop = listState.firstVisibleItemIndex == 0 &&
                        listState.firstVisibleItemScrollOffset == 0
                    val pullingDownAtTop = available.y > 0f && atTop && revealState.fraction < 1f
                    val pullingUpStillCollapsing = available.y < 0f && revealState.fraction > 0f
                    if (pullingDownAtTop || pullingUpStillCollapsing) {
                        revealState.dragBy(-available.y, travelPx.floatValue)
                        return Offset(0f, available.y)
                    }
                    return Offset.Zero
                }

                override suspend fun onPreFling(available: Velocity): Velocity {
                    if (revealState.fraction <= 0f || revealState.fraction >= 1f) {
                        return Velocity.Zero
                    }
                    val target = chooseExpandedTarget(
                        revealState.fraction, available.y, travelPx.floatValue,
                    )
                    onExpandedCommit(target)
                    // Settle to the chosen endpoint even when the committed mode
                    // is unchanged (a partial pull that snaps back) — the reconcile
                    // effect only fires on a CHANGE, so the gesture owns this throw.
                    // launchAnimateTo is settleJob-tracked, so it stays coordinated
                    // with dragBy / the effect (no concurrent animator).
                    revealState.launchAnimateTo(reshapeScope, if (target) 0f else 1f)
                    return available
                }
            }
        }

        // Reveal fraction read HERE (not in the parent scope) so only this page —
        // not the whole screen, header, pager and toolbar — recomposes per frame
        // during the reshape settle. 1 = hero, 0 = track list; expand 0→1.
        val expand = 1f - revealState.fraction

        // Cover geometry: hero square → a thin, full-width "wavy band" docked just
        // under the page dots (replaces the old right-docked capsule). The square
        // morphs into the band via WavyBandShape; everything else lerps on `expand`.
        val heroCoverSide = minOf(maxW * 0.74f, 300.dp)
        // Many tracks → thin full-bleed wavy band (the long list needs the room).
        // Few tracks → a big, centered rounded "capsule" inset from the edges.
        val state2CoverHeight = if (isMany) 56.dp else minOf(maxHeight * 0.26f, 220.dp)
        val state2CoverWidth = if (isMany) maxW else maxW - 32.dp
        val coverHeight = lerp(heroCoverSide, state2CoverHeight, expand)
        val coverWidth = lerp(heroCoverSide, state2CoverWidth, expand)
        // Capsule corner (few-tracks only): 28dp hero → ~stadium as it docks.
        val coverCorner = lerp(28.dp, 100.dp, expand.coerceIn(0f, 1f))
        // Band behind the cover for the arrow mark; collapses as the cover docks.
        val arrowBand = lerp(56.dp, 0.dp, expand.coerceIn(0f, 1f))
        // Centered in both states — no right-dock.
        val coverBias = 0f

        Box(modifier = Modifier.fillMaxSize()) {
            // Full-bleed arrow-mark backdrop in the top cover band — edge to edge,
            // BEHIND the padded content (no left/right or top/bottom inset).
            AlbumArrowBackground(
                primaryBlock = primaryBlock,
                secondaryBlock = secondaryBlock,
                lineColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.65f),
                markHeight = coverHeight + arrowBand,
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer { alpha = (1f - expand).coerceIn(0f, 1f) },
            )
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .then(
                        if (!expanded) {
                            Modifier.draggable(
                                state = dragState,
                                orientation = Orientation.Vertical,
                                onDragStopped = { velocity ->
                                    val target = chooseExpandedTarget(
                                        revealState.fraction, velocity, travelPx.floatValue,
                                    )
                                    onExpandedCommit(target)
                                    revealState.launchAnimateTo(reshapeScope, if (target) 0f else 1f)
                                },
                            )
                        } else {
                            Modifier
                        },
                    ),
                // NOTE: no horizontal padding here — the cover/band is full-bleed;
                // the 16dp inset lives on the content Box below instead.
            ) {
                // Cover floats on top of the full-bleed arrow backdrop, edge to edge.
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(coverHeight + arrowBand),
                    contentAlignment = BiasAlignment(horizontalBias = coverBias, verticalBias = 0f),
                ) {
                    ExpressiveMediaArtwork(
                        model = content.coverArtUrl,
                        contentDescription = content.albumName,
                        modifier = Modifier
                            .width(coverWidth)
                            .height(coverHeight),
                        // Long lists dock to the wavy band; short ones to a capsule.
                        shape = if (isMany) {
                            WavyBandShape(expand = expand)
                        } else {
                            RoundedCornerShape(coverCorner)
                        },
                        fallbackIcon = Icons.Filled.LibraryMusic,
                        border = null,
                        shadowElevation = 0.dp,
                        tonalElevation = 3.dp,
                        requestSizePx = 640,
                    )
                }

                Box(
                    modifier = Modifier
                        .weight(1f)
                        .padding(horizontal = 16.dp),
                ) {
                    if (expand > 0.001f) {
                        AlbumTrackList(
                            content = content,
                            accent = accent,
                            notedSongIds = notedSongIds,
                            expandedSongId = expandedSongId,
                            expandedNoteBundle = expandedNoteBundle,
                            onSongClick = onSongClick,
                            onToggleStar = onToggleStar,
                            onToggleExpandedSong = onToggleExpandedSong,
                            listState = listState,
                            modifier = Modifier
                                .fillMaxSize()
                                .graphicsLayer { alpha = expand.coerceIn(0f, 1f) }
                                .nestedScroll(collapseConnection),
                        )
                    }
                    if (expand < 0.999f) {
                        AlbumHeroDetails(
                            content = content,
                            bunContainer = bunContainer,
                            bunContent = bunContent,
                            contentWidth = heroCoverSide,
                            // Stop interacting with the fading-out hero once the
                            // list is the dominant layer, so its (still-composed,
                            // alpha≈0) buttons can't intercept taps over the list.
                            interactive = expand < 0.5f,
                            onEditComment = onEditComment,
                            onTapBun = onEditComment,
                            onSongClick = onSongClick,
                            modifier = Modifier
                                .fillMaxSize()
                                .graphicsLayer {
                                    alpha = (1f - expand).coerceIn(0f, 1f)
                                    translationY = -expand * 40f
                                },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun AlbumHeroDetails(
    content: AlbumDetailUiState.Content,
    bunContainer: Color,
    bunContent: Color,
    contentWidth: Dp,
    interactive: Boolean,
    onEditComment: () -> Unit,
    onTapBun: () -> Unit,
    onSongClick: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val score = content.albumScore()
    val mono = FontFamily.Monospace
    Column(
        modifier = modifier.padding(top = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        // Last Play + Avg + Comment — a cover-width block; mono labels & values.
        Column(
            modifier = Modifier.width(contentWidth),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    AlbumSectionLabel(text = "Last Play")
                    val labels = content.lastPlayedAt?.let { albumLastPlayLabels(it) }
                    Text(
                        text = labels?.first ?: "—",
                        style = MaterialTheme.typography.bodyLarge.copy(fontFamily = mono),
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Text(
                        text = labels?.second ?: "Never",
                        style = MaterialTheme.typography.bodyMedium.copy(fontFamily = mono),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    AlbumSectionLabel(
                        text = if (score.kind == AlbumScoreKind.UserRating) "Rating" else "Avg.",
                    )
                    AlbumScoreBun(
                        score = score,
                        ratedCount = content.ratedTrackCount,
                        total = content.trackTotal,
                        containerColor = bunContainer,
                        contentColor = bunContent,
                        enabled = interactive,
                        onClick = onTapBun,
                    )
                }
            }

            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                AlbumSectionLabel(
                    text = "Comment",
                    trailing = {
                        IconButton(
                            onClick = onEditComment,
                            enabled = interactive,
                            modifier = Modifier
                                .size(28.dp)
                                .minimumTouchTarget(),
                        ) {
                            Icon(
                                imageVector = Icons.Filled.Edit,
                                contentDescription = "Edit comment",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(16.dp),
                            )
                        }
                    },
                )
                Text(
                    text = content.userReview.ifBlank { "无" },
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (content.userReview.isBlank()) {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    } else {
                        MaterialTheme.colorScheme.onSurface
                    },
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }

        Spacer(modifier = Modifier.height(28.dp))

        // Total track count + runtime — left-aligned with the flowing titles.
        if (content.songs.isNotEmpty()) {
            AlbumTrackCountLabel(
                count = content.trackTotal,
                totalDurationSeconds = content.totalDuration,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(modifier = Modifier.height(6.dp))
        }

        // Flowing track titles — clickable, intentionally NOT truncated: it
        // flows down behind the toolbar and past the bottom safe area (unbounded
        // height + overflow Visible), which reads better than a hard ellipsis cut.
        Text(
            text = if (content.songs.isEmpty()) {
                buildAnnotatedString { append(content.albumName) }
            } else {
                buildAlbumTrackTitles(
                    songs = content.songs,
                    separatorColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                    featColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    onSongClick = if (interactive) onSongClick else null,
                )
            },
            style = MaterialTheme.typography.headlineMedium,
            color = MaterialTheme.colorScheme.onSurface,
            overflow = TextOverflow.Visible,
            modifier = Modifier
                .fillMaxWidth()
                .wrapContentHeight(align = Alignment.Top, unbounded = true),
        )
    }
}

@Composable
private fun AlbumTrackList(
    content: AlbumDetailUiState.Content,
    accent: Color,
    notedSongIds: Set<String>,
    expandedSongId: String?,
    expandedNoteBundle: AlbumExpandedNoteBundle?,
    onSongClick: (songId: String) -> Unit,
    onToggleStar: (songId: String) -> Unit,
    onToggleExpandedSong: (songId: String) -> Unit,
    listState: androidx.compose.foundation.lazy.LazyListState,
    modifier: Modifier = Modifier,
) {
    val navBottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    LazyColumn(
        state = listState,
        modifier = modifier,
        contentPadding = PaddingValues(top = 4.dp, bottom = 112.dp + navBottom),
    ) {
        // Track count + runtime sits just below the wavy band, above row 1.
        if (content.songs.isNotEmpty()) {
            item {
                AlbumTrackCountLabel(
                    count = content.trackTotal,
                    totalDurationSeconds = content.totalDuration,
                    modifier = Modifier.padding(start = 8.dp, top = 2.dp, bottom = 10.dp),
                )
            }
        }
        itemsIndexed(content.songs, key = { _, song -> song.id }) { index, song ->
            Column {
                AlbumTrackRow(
                    index = index,
                    song = song,
                    hasNote = song.id in notedSongIds,
                    accent = accent,
                    onClick = { onSongClick(song.id) },
                    onLongClick = { onToggleExpandedSong(song.id) },
                    onToggleStar = { onToggleStar(song.id) },
                )
                AnimatedVisibility(visible = expandedSongId == song.id) {
                    AlbumSongNotes(
                        bundle = expandedNoteBundle?.takeIf { it.songId == song.id },
                        modifier = Modifier.padding(start = 38.dp, end = 14.dp, bottom = 10.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun AlbumSecondaryPage(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .padding(24.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Icon(
                imageVector = Icons.Outlined.Insights,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(40.dp),
            )
            Text(
                text = "Scores & About",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = "Per-track scores and a Gemini-written About will live here.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.fillMaxWidth(),
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            )
        }
    }
}

// Pinned bottom toolbar — present on both pages, like the Figma frames.
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun AlbumBottomToolbar(
    playContainer: Color,
    playContent: Color,
    toolbarContainer: Color,
    onPlay: () -> Unit,
    onShuffle: () -> Unit,
    onShare: () -> Unit,
    onOpenArtist: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    DetailFloatingToolbar(
        toolbarContainer = toolbarContainer,
        modifier = modifier,
    ) {
        IconButton(
            onClick = onShare,
            modifier = Modifier.size(52.dp),
        ) {
            Icon(
                imageVector = Icons.Rounded.IosShare,
                contentDescription = "Share",
                tint = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.size(24.dp),
            )
        }
        Spacer(modifier = Modifier.width(8.dp))
        DetailPlaySplitButton(
            playContainer = playContainer,
            playContent = playContent,
            onPlay = onPlay,
            onShuffle = onShuffle,
        ) { dismissMenu ->
            if (onOpenArtist != null) {
                YoinDropdownMenuItem(
                    text = "Go to artist",
                    onClick = {
                        dismissMenu()
                        onOpenArtist()
                    },
                    leadingIcon = {
                        Icon(
                            Icons.Filled.Person,
                            contentDescription = null,
                            modifier = Modifier.size(22.dp),
                        )
                    },
                    textStyle = MaterialTheme.typography.titleMedium,
                    contentPadding = PaddingValues(horizontal = 20.dp, vertical = 14.dp),
                )
            }
        }
    }
}

@Composable
private fun AlbumSongNotes(
    bundle: AlbumExpandedNoteBundle?,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = "Notes",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        val primary = bundle?.primaryNotes.orEmpty()
        if (primary.isEmpty()) {
            Text(
                text = "没有笔记",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            primary.forEach { note ->
                Text(
                    text = note.content,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
        }
        bundle?.crossProviderNotes?.forEach { note ->
            Text(
                text = "${note.providerLabel}: ${note.content}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun AlbumErrorState(
    message: String,
    onRetry: () -> Unit,
    onBackClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .statusBarsPadding(),
    ) {
        DetailBackButton(
            onClick = onBackClick,
            modifier = Modifier.padding(4.dp),
        )
        Column(
            modifier = Modifier
                .align(Alignment.Center)
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                text = message,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.error,
            )
            androidx.compose.material3.TextButton(onClick = onRetry) { Text("Retry") }
        }
    }
}

@Preview(showBackground = true, backgroundColor = 0xFF1C1B1F)
@Composable
private fun AlbumDetailScreenContentPreview() {
    YoinTheme {
        AlbumDetailScreen(
            uiState = AlbumDetailUiState.Content(
                albumId = "album-1",
                albumName = "Describe",
                artistName = "Hannah Jadagu",
                artistId = "artist-1",
                coverArtId = "cover-1",
                coverArtUrl = null,
                year = 2025,
                songCount = 8,
                totalDuration = 1680,
                songs = listOf(
                    AlbumSong("1", "Describe", "Hannah Jadagu", 1, 231, true),
                    AlbumSong("2", "Gimme Time", "Hannah Jadagu", 2, 232, true),
                    AlbumSong("3", "More", "Hannah Jadagu", 3, 201, false),
                    AlbumSong(
                        id = "4",
                        title = "Tell Me",
                        artist = "Hannah Jadagu feat. skjkhjashf",
                        trackNumber = 4,
                        duration = 172,
                        isStarred = true,
                        featArtist = "skjkhjashf",
                    ),
                ),
                averageTrackRating = null,
                ratedTrackCount = 0,
                lastPlayedAt = System.currentTimeMillis() - 86_400_000L,
                userReview = "我爱它我爱它我爱它",
            ),
            onBackClick = {},
            onSongClick = {},
            onToggleStar = {},
            onRetry = {},
        )
    }
}
