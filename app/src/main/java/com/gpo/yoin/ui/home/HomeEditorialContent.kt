package com.gpo.yoin.ui.home

import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.unit.Velocity
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.setValue
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.gpo.yoin.data.local.ActivityEntityType
import com.gpo.yoin.data.local.ActivityEvent
import com.gpo.yoin.data.model.Artist
import com.gpo.yoin.data.model.CoverRef
import com.gpo.yoin.data.model.MediaId
import com.gpo.yoin.data.model.Track
import com.gpo.yoin.ui.component.ExpressiveBackdrop
import com.gpo.yoin.ui.component.ExpressiveBackdropVariant
import com.gpo.yoin.ui.component.ExpressiveMediaArtwork
import com.gpo.yoin.ui.component.ExpressiveSectionPanel
import com.gpo.yoin.ui.component.YoinLoadingIndicator
import com.gpo.yoin.ui.component.elasticPress
import com.gpo.yoin.ui.component.expressiveEntrance
import com.gpo.yoin.ui.component.rememberExpressiveEntranceProgress
import com.gpo.yoin.ui.component.ExpressiveBackdropArtworkScale
import com.gpo.yoin.ui.component.horizontalFadeMask
import com.gpo.yoin.ui.component.minimumTouchTarget
import com.gpo.yoin.ui.component.noRippleClickable
import com.gpo.yoin.ui.component.rememberExpressiveBackdropColors
import com.gpo.yoin.ui.experience.RevealState
import com.gpo.yoin.ui.experience.rememberRevealState
import com.gpo.yoin.ui.navigation.albumCoverSharedKey
import com.gpo.yoin.ui.theme.YoinMotion
import com.gpo.yoin.ui.theme.YoinMotionRole
import com.gpo.yoin.ui.theme.YoinShapeTokens
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter

internal sealed interface HomeEntryTarget {
    data class Album(val albumId: String, val sharedTransitionKey: String?) : HomeEntryTarget
    data class Artist(val artistId: String) : HomeEntryTarget
    data class Playlist(val playlistId: String) : HomeEntryTarget
    data class SongTarget(val song: Track) : HomeEntryTarget
}

private data class HomeMomentEntry(
    val stableId: String,
    val title: String,
    val subtitle: String,
    val footnote: String,
    val coverArtUrl: String?,
    val sharedAlbumId: String?,
    val sharedSourceKey: String?,
    val songId: String?,
    val variant: ExpressiveBackdropVariant,
    val shape: Shape,
    val fallbackIcon: androidx.compose.ui.graphics.vector.ImageVector,
    val target: HomeEntryTarget,
)

private data class JumpBackInVisualEntry(
    val stableId: String,
    val title: String,
    val subtitle: String?,
    val metaText: String?,
    val coverArtUrl: String?,
    val sharedAlbumId: String?,
    val sharedSourceKey: String?,
    val songId: String?,
    val variant: ExpressiveBackdropVariant,
    val shape: Shape,
    val fallbackIcon: androidx.compose.ui.graphics.vector.ImageVector,
    val target: HomeEntryTarget,
)

private const val HomeBackdropPaletteWarmupDelayMillis = 350L

@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
internal fun HomeEditorialContent(
    activities: List<ActivityEvent>,
    jumpBackInItems: List<HomeJumpBackInItem>,
    isLoadingMoreJumpBackIn: Boolean,
    isPlaying: Boolean,
    playbackSignal: Float,
    activeSongId: String? = null,
    onNavigateToSettings: () -> Unit,
    onNavigateToMemories: () -> Unit,
    memoriesRevealState: RevealState = rememberRevealState(),
    onCommitMemoriesReveal: () -> Unit = {},
    onAlbumClick: (albumId: String, sharedTransitionKey: String?) -> Unit,
    onArtistClick: (artistId: String) -> Unit,
    onPlaylistClick: (playlistId: String) -> Unit,
    onSongClick: (Track) -> Unit,
    onLoadMoreJumpBackIn: () -> Unit,
    buildCoverArtUrl: (String) -> String,
    sharedTransitionScope: SharedTransitionScope? = null,
    animatedVisibilityScope: AnimatedVisibilityScope? = null,
    modifier: Modifier = Modifier,
) {
    val listState = rememberLazyListState()
    var containerHeightPx by remember { mutableFloatStateOf(0f) }
    var isCommittedToMemories by remember { mutableStateOf(false) }
    // Visual hint = how far open the reveal is, capped at 1 so rubber-band
    // overshoot doesn't inflate the chevron.
    val memoriesHintProgress = (1f - memoriesRevealState.fraction).coerceIn(0f, 1f)
    var allowBackdropPalette by remember { mutableStateOf(false) }
    val pullToMemoriesConnection = remember(listState, memoriesRevealState) {
        object : NestedScrollConnection {
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
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
                    if (target <= 0f) onCommitMemoriesReveal()
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
    val jumpRows = remember(jumpBackInItems, buildCoverArtUrl) {
        jumpBackInItems
            .map { item ->
                buildJumpBackInEntry(
                    item = item,
                    buildCoverArtUrl = buildCoverArtUrl,
                )
            }
            .chunked(3)
    }
    // Keep a single stable dispatcher for entry clicks. Nav lambdas are held
    // via rememberUpdatedState so each call reaches the latest referenced
    // lambda without invalidating `remember`-cached entry lists.
    val onAlbumClickState = rememberUpdatedState(onAlbumClick)
    val onArtistClickState = rememberUpdatedState(onArtistClick)
    val onPlaylistClickState = rememberUpdatedState(onPlaylistClick)
    val onSongClickState = rememberUpdatedState(onSongClick)
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

    // Pagination trigger: watch the last visible item index instead of
    // relying on an item-scoped LaunchedEffect, so a single stable observer
    // handles all fling-induced index changes without spawning per-row
    // effects. HomeViewModel.loadMoreJumpBackIn() is single-flight.
    val onLoadMoreState = rememberUpdatedState(onLoadMoreJumpBackIn)
    LaunchedEffect(listState, jumpRows.isNotEmpty()) {
        if (jumpRows.isEmpty()) return@LaunchedEffect
        snapshotFlow {
            val info = listState.layoutInfo
            val lastVisible = info.visibleItemsInfo.lastOrNull()?.index ?: -1
            val total = info.totalItemsCount
            total > 0 && lastVisible >= total - 2
        }
            .distinctUntilChanged()
            .filter { it }
            .collect { onLoadMoreState.value() }
    }
    val shouldExtractBackdropColors = allowBackdropPalette && !listState.isScrollInProgress

    val navBarBottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    LazyColumn(
        state = listState,
        modifier = modifier
            .fillMaxSize()
            .onSizeChanged { containerHeightPx = it.height.toFloat().coerceAtLeast(1f) }
            .nestedScroll(pullToMemoriesConnection),
        contentPadding = PaddingValues(
            start = 16.dp,
            end = 16.dp,
            top = 4.dp,
            bottom = 108.dp + navBarBottom,
        ),
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        item {
            HomeContentHeader(
                title = "Activities",
                onNavigateToSettings = onNavigateToSettings,
                onNavigateToMemories = onNavigateToMemories,
                memoriesHintProgress = memoriesHintProgress,
                modifier = Modifier.fillMaxWidth(),
            )
        }

        item {
            AnimatedVisibility(visible = activityEntries.isNotEmpty()) {
                ActivityGrid(
                    entries = activityEntries.take(6),
                    activeSongId = activeSongId,
                    isPlaying = isPlaying,
                    playbackSignal = playbackSignal,
                    extractBackdropColors = shouldExtractBackdropColors,
                    onEntryClick = onEntryClick,
                    sharedTransitionScope = sharedTransitionScope,
                    animatedVisibilityScope = animatedVisibilityScope,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            if (activityEntries.isEmpty()) {
                HomeEmptyCard(
                    title = "No recent activity yet",
                    supporting = "Once you listen or visit albums and artists, this feed will start filling in.",
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }

        item {
            JumpBackInHeader(
                modifier = Modifier.fillMaxWidth(),
            )
        }

        if (jumpRows.isEmpty()) {
            item {
                HomeEmptyCard(
                    title = "Jump Back In is waiting",
                    supporting = "Scroll a little and refresh when you want another batch of albums, songs, and artists.",
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        } else {
            itemsIndexed(
                items = jumpRows,
                key = { _, row -> row.joinToString(separator = "|") { it.stableId } },
            ) { index, row ->
                JumpBackInRow(
                    entries = row,
                    rowIndex = index,
                    activeSongId = activeSongId,
                    isPlaying = isPlaying,
                    playbackSignal = playbackSignal,
                    extractBackdropColors = shouldExtractBackdropColors,
                    onEntryClick = onEntryClick,
                    sharedTransitionScope = sharedTransitionScope,
                    animatedVisibilityScope = animatedVisibilityScope,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }

        if (isLoadingMoreJumpBackIn) {
            item {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 8.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    YoinLoadingIndicator()
                }
            }
        }
    }
}

@Composable
private fun HomeContentHeader(
    title: String,
    onNavigateToSettings: () -> Unit,
    onNavigateToMemories: () -> Unit,
    memoriesHintProgress: Float,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .statusBarsPadding()
            .padding(top = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.headlineLarge,
            color = MaterialTheme.colorScheme.onBackground,
        )
        Row(
            horizontalArrangement = Arrangement.spacedBy(2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(
                onClick = onNavigateToMemories,
                modifier = Modifier.graphicsLayer {
                    translationY = memoriesHintProgress * 4f
                    alpha = 0.62f + memoriesHintProgress * 0.38f
                },
            ) {
                Icon(
                    imageVector = Icons.Filled.KeyboardArrowDown,
                    contentDescription = "Memories",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            IconButton(onClick = onNavigateToSettings) {
                Icon(
                    imageVector = Icons.Filled.Settings,
                    contentDescription = "Settings",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
private fun ActivityGrid(
    entries: List<HomeMomentEntry>,
    activeSongId: String? = null,
    isPlaying: Boolean,
    playbackSignal: Float,
    extractBackdropColors: Boolean,
    onEntryClick: (HomeEntryTarget) -> Unit,
    sharedTransitionScope: SharedTransitionScope? = null,
    animatedVisibilityScope: AnimatedVisibilityScope? = null,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        entries.chunked(2).forEachIndexed { rowIndex, rowEntries ->
            key(rowEntries.joinToString(separator = "|") { it.stableId }) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    rowEntries.forEachIndexed { columnIndex, entry ->
                        key(entry.stableId) {
                            ActivityCard(
                                entry = entry,
                                activeSongId = activeSongId,
                                isPlaying = isPlaying,
                                playbackSignal = playbackSignal,
                                extractBackdropColors = extractBackdropColors,
                                delayMillis = ((rowIndex * 2) + columnIndex) * 36L,
                                onClick = { onEntryClick(entry.target) },
                                sharedTransitionScope = sharedTransitionScope,
                                animatedVisibilityScope = animatedVisibilityScope,
                                modifier = Modifier.weight(1f),
                            )
                        }
                    }
                    if (rowEntries.size == 1) {
                        Spacer(modifier = Modifier.weight(1f))
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
private fun ActivityCard(
    entry: HomeMomentEntry,
    activeSongId: String? = null,
    isPlaying: Boolean,
    playbackSignal: Float,
    extractBackdropColors: Boolean,
    onClick: () -> Unit,
    delayMillis: Long = 0L,
    sharedTransitionScope: SharedTransitionScope? = null,
    animatedVisibilityScope: AnimatedVisibilityScope? = null,
    modifier: Modifier = Modifier,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isPlaybackActive = isPlaying && entry.songId != null && entry.songId == activeSongId
    val entranceProgress = rememberExpressiveEntranceProgress(
        key = entry.stableId,
        delayMillis = delayMillis,
    )

    Surface(
        modifier = modifier.expressiveEntrance(entranceProgress),
        shape = YoinShapeTokens.Large,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        tonalElevation = 2.dp,
        shadowElevation = 0.dp,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .noRippleClickable(interactionSource = interactionSource, onClick = onClick)
                .padding(horizontal = 8.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ExpressiveArtwork(
                model = entry.coverArtUrl,
                contentDescription = entry.title,
                sharedAlbumId = entry.sharedAlbumId,
                sharedSourceKey = entry.sharedSourceKey,
                isPlaybackActive = isPlaybackActive,
                playbackSignal = playbackSignal,
                extractBackdropColors = extractBackdropColors,
                sharedTransitionScope = sharedTransitionScope,
                animatedVisibilityScope = animatedVisibilityScope,
                interactionSource = interactionSource,
                backdropVariant = entry.variant,
                modifier = Modifier.size(58.dp),
                fillFraction = 1f,
                offsetX = 2.dp,
                offsetY = 3.dp,
                shape = entry.shape,
                fallbackIcon = entry.fallbackIcon,
            )
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                MarqueeTitle(
                    text = entry.title,
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    text = entry.subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = entry.footnote,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.75f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun JumpBackInHeader(
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = "Jump Back In",
            style = MaterialTheme.typography.headlineMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
private fun JumpBackInRow(
    entries: List<JumpBackInVisualEntry>,
    rowIndex: Int,
    activeSongId: String? = null,
    isPlaying: Boolean,
    playbackSignal: Float,
    extractBackdropColors: Boolean,
    onEntryClick: (HomeEntryTarget) -> Unit,
    sharedTransitionScope: SharedTransitionScope? = null,
    animatedVisibilityScope: AnimatedVisibilityScope? = null,
    modifier: Modifier = Modifier,
) {
    // Stagger only the first two rows — these are the user's first
    // perceivable impression. Rows beyond that (including anything the
    // pagination appends) still fade/scale in, but without the cascading
    // delay so fresh pages don't feel like a heavy entrance animation.
    val staggerRow = rowIndex < 2
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        verticalAlignment = Alignment.Top,
    ) {
        entries.forEachIndexed { index, entry ->
            JumpBackInTile(
                entry = entry,
                activeSongId = activeSongId,
                isPlaying = isPlaying,
                playbackSignal = playbackSignal,
                extractBackdropColors = extractBackdropColors,
                delayMillis = if (staggerRow) index * 42L else 0L,
                onClick = { onEntryClick(entry.target) },
                sharedTransitionScope = sharedTransitionScope,
                animatedVisibilityScope = animatedVisibilityScope,
                modifier = Modifier.weight(1f),
            )
        }
        repeat(3 - entries.size) {
            Spacer(modifier = Modifier.weight(1f))
        }
    }
}

@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
private fun JumpBackInTile(
    entry: JumpBackInVisualEntry,
    activeSongId: String? = null,
    isPlaying: Boolean,
    playbackSignal: Float,
    extractBackdropColors: Boolean,
    onClick: () -> Unit,
    delayMillis: Long = 0L,
    sharedTransitionScope: SharedTransitionScope? = null,
    animatedVisibilityScope: AnimatedVisibilityScope? = null,
    modifier: Modifier = Modifier,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isPlaybackActive = isPlaying && entry.songId != null && entry.songId == activeSongId
    val entranceProgress = rememberExpressiveEntranceProgress(
        key = entry.stableId,
        delayMillis = delayMillis,
    )
    Column(
        modifier = modifier
            .expressiveEntrance(entranceProgress)
            .noRippleClickable(interactionSource = interactionSource, onClick = onClick),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        ExpressiveArtwork(
            model = entry.coverArtUrl,
            contentDescription = entry.title,
            sharedAlbumId = entry.sharedAlbumId,
            sharedSourceKey = entry.sharedSourceKey,
            isPlaybackActive = isPlaybackActive,
            playbackSignal = playbackSignal,
            extractBackdropColors = extractBackdropColors,
            sharedTransitionScope = sharedTransitionScope,
            animatedVisibilityScope = animatedVisibilityScope,
            interactionSource = interactionSource,
            backdropVariant = entry.variant,
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(1f),
            fillFraction = 1f,
            offsetX = 3.dp,
            offsetY = 5.dp,
            shape = entry.shape,
            fallbackIcon = entry.fallbackIcon,
        )
        MarqueeTitle(
            text = entry.title,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.fillMaxWidth(),
        )
        entry.subtitle?.let { subtitle ->
            val supportingText = listOfNotNull(
                subtitle.takeIf { it.isNotBlank() },
                entry.metaText?.takeIf { it.isNotBlank() },
            ).joinToString(" · ")
            Text(
                text = supportingText,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun MarqueeTitle(
    text: String,
    style: androidx.compose.ui.text.TextStyle,
    color: Color,
    modifier: Modifier = Modifier,
) {
    BoxWithConstraints(modifier = modifier) {
        val textMeasurer = rememberTextMeasurer()
        val density = LocalDensity.current
        val availableWidthPx = with(density) { maxWidth.roundToPx() }
        val shouldMarquee = remember(text, style, availableWidthPx) {
            if (availableWidthPx <= 0) {
                false
            } else {
                textMeasurer.measure(
                    text = AnnotatedString(text),
                    style = style,
                    maxLines = 1,
                    softWrap = false,
                    overflow = TextOverflow.Clip,
                    constraints = Constraints(maxWidth = Constraints.Infinity),
                ).size.width > availableWidthPx
            }
        }

        Box(
            modifier = if (shouldMarquee) {
                Modifier
                    .fillMaxWidth()
                    .clipToBounds()
                    .horizontalFadeMask(edgeWidth = 18.dp)
            } else {
                Modifier.fillMaxWidth()
            },
        ) {
            Text(
                text = text,
                style = style,
                color = color,
                maxLines = 1,
                softWrap = false,
                overflow = TextOverflow.Clip,
                modifier = if (shouldMarquee) {
                    Modifier.basicMarquee(
                        iterations = Int.MAX_VALUE,
                        repeatDelayMillis = 1800,
                        initialDelayMillis = 1200,
                    )
                } else {
                    Modifier
                },
            )
        }
    }
}

@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
private fun ExpressiveArtwork(
    model: String?,
    contentDescription: String,
    backdropVariant: ExpressiveBackdropVariant,
    extractBackdropColors: Boolean,
    sharedAlbumId: String? = null,
    sharedSourceKey: String? = null,
    isPlaybackActive: Boolean = false,
    playbackSignal: Float = 0f,
    sharedTransitionScope: SharedTransitionScope? = null,
    animatedVisibilityScope: AnimatedVisibilityScope? = null,
    modifier: Modifier = Modifier,
    interactionSource: MutableInteractionSource? = null,
    fillFraction: Float = 0.78f,
    offsetX: Dp = 8.dp,
    offsetY: Dp = 10.dp,
    shape: Shape = YoinShapeTokens.ExtraLarge,
    fallbackIcon: androidx.compose.ui.graphics.vector.ImageVector = Icons.Filled.LibraryMusic,
) {
    val backdropColors = rememberExpressiveBackdropColors(
        model = model,
        fallbackBaseColor = MaterialTheme.colorScheme.secondaryContainer,
        fallbackAccentColor = MaterialTheme.colorScheme.tertiaryContainer,
        enabled = extractBackdropColors,
    )
    val scaledFillFraction = (fillFraction * ExpressiveBackdropArtworkScale).coerceIn(0.36f, 1f)

    BoxWithConstraints(modifier = modifier) {
        val opticalShift = minOf(maxWidth, maxHeight) * 0.08f
        ExpressiveBackdrop(
            baseColor = backdropColors.baseColor,
            accentColor = backdropColors.accentColor,
            variant = backdropVariant,
            isPlaybackActive = isPlaybackActive,
            playbackSignal = playbackSignal,
            modifier = Modifier
                .fillMaxSize(0.88f)
                .align(Alignment.Center)
                .offset(x = -opticalShift, y = -opticalShift),
        )

        val coverModifier = Modifier
            .fillMaxSize(scaledFillFraction)
            .align(Alignment.Center)
            .offset(
                x = opticalShift + offsetX * 0.25f,
                y = opticalShift + offsetY * 0.25f,
            )
            .then(
                if (interactionSource != null) {
                    Modifier.elasticPress(interactionSource)
                } else {
                    Modifier
                },
            )
        val sharedArtworkBoundsSpec = YoinMotion.defaultSpatialSpec<Rect>(
            role = YoinMotionRole.Expressive,
            expressiveScheme = MaterialTheme.motionScheme,
        )

        val sharedArtworkModifier = if (
            sharedAlbumId != null &&
            sharedTransitionScope != null &&
            animatedVisibilityScope != null
        ) {
            with(sharedTransitionScope) {
                Modifier
                    .fillMaxSize()
                    .sharedElement(
                        sharedContentState = rememberSharedContentState(
                            key = albumCoverSharedKey(sharedAlbumId, sharedSourceKey),
                        ),
                        animatedVisibilityScope = animatedVisibilityScope,
                        boundsTransform = { _, _ -> sharedArtworkBoundsSpec },
                        zIndexInOverlay = 1f,
                    )
                    .clip(shape)
            }
        } else {
            Modifier.fillMaxSize()
        }

        Box(modifier = coverModifier) {
            ExpressiveMediaArtwork(
                model = model,
                contentDescription = contentDescription,
                modifier = sharedArtworkModifier,
                shape = shape,
                fallbackIcon = fallbackIcon,
                tonalElevation = 1.dp,
                shadowElevation = 0.dp,
            )
        }
    }
}

@Composable
private fun HomeEmptyCard(
    title: String,
    supporting: String,
    modifier: Modifier = Modifier,
) {
    ExpressiveSectionPanel(
        modifier = modifier,
        shape = YoinShapeTokens.ExtraLarge,
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        tonalElevation = 1.dp,
        shadowElevation = 0.dp,
    ) {
        Column(
            modifier = Modifier.padding(18.dp),
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

private fun homeActivityDedupKey(activity: ActivityEvent): String {
    val canonicalEntityId = when (activity.entityType) {
        ActivityEntityType.SONG.name -> activity.songId ?: activity.entityId
        else -> activity.entityId
    }
    return "${activity.entityType}:$canonicalEntityId"
}

private fun buildActivityEntries(
    activities: List<ActivityEvent>,
    buildCoverArtUrl: (String) -> String,
): List<HomeMomentEntry> = dedupeActivitiesForHome(activities).take(6).map { activity ->
    val stableId = "activity:${activity.id}:${activity.entityType}:${activity.entityId}:${activity.actionType}"
    val target: HomeEntryTarget = when (activity.entityType) {
        ActivityEntityType.ALBUM.name -> HomeEntryTarget.Album(activity.entityId, stableId)
        ActivityEntityType.ARTIST.name -> HomeEntryTarget.Artist(activity.entityId)
        ActivityEntityType.PLAYLIST.name -> HomeEntryTarget.Playlist(activity.entityId)
        else -> HomeEntryTarget.SongTarget(activity.asSong())
    }
    HomeMomentEntry(
        stableId = stableId,
        title = activity.title,
        subtitle = activity.subtitle.ifBlank {
            when (activity.entityType) {
                ActivityEntityType.ARTIST.name -> "Artist"
                else -> "Recently active"
            }
        },
        footnote = buildActivityFootnote(activity),
        coverArtUrl = buildActivityCoverArtUrl(activity, buildCoverArtUrl),
        sharedAlbumId = activity.entityId.takeIf { activity.entityType == ActivityEntityType.ALBUM.name },
        sharedSourceKey = stableId.takeIf { activity.entityType == ActivityEntityType.ALBUM.name },
        songId = (activity.songId ?: activity.entityId).takeIf {
            activity.entityType == ActivityEntityType.SONG.name
        },
        variant = backdropVariantForActivity(activity.entityType),
        shape = artworkShapeForEntityType(activity.entityType),
        fallbackIcon = artworkFallbackIconForEntityType(activity.entityType),
        target = target,
    )
}

private fun buildJumpBackInEntry(
    item: HomeJumpBackInItem,
    buildCoverArtUrl: (String) -> String,
): JumpBackInVisualEntry = when (item) {
    is HomeJumpBackInItem.AlbumItem -> JumpBackInVisualEntry(
        stableId = item.stableId,
        title = item.album.name,
        subtitle = item.album.artist,
        metaText = item.album.songCount?.let { "$it tracks" },
        coverArtUrl = resolveHomeCoverArtUrl(item.album.coverArt, buildCoverArtUrl)
            ?: buildCoverArtUrl(item.album.id.rawId),
        sharedAlbumId = item.album.id.toString(),
        sharedSourceKey = item.stableId,
        songId = null,
        variant = ExpressiveBackdropVariant.Bun,
        shape = YoinShapeTokens.Medium,
        fallbackIcon = Icons.Filled.LibraryMusic,
        target = HomeEntryTarget.Album(item.album.id.toString(), item.stableId),
    )

    is HomeJumpBackInItem.SongItem -> JumpBackInVisualEntry(
        stableId = item.stableId,
        title = item.song.title.orEmpty(),
        subtitle = item.song.artist,
        metaText = "Single",
        coverArtUrl = resolveHomeCoverArtUrl(item.song.coverArt, buildCoverArtUrl)
            ?: item.song.albumId?.let { buildCoverArtUrl(it.rawId) },
        sharedAlbumId = null,
        sharedSourceKey = null,
        songId = item.song.id.toString(),
        variant = ExpressiveBackdropVariant.Circle,
        shape = YoinShapeTokens.Medium,
        fallbackIcon = Icons.Filled.LibraryMusic,
        target = HomeEntryTarget.SongTarget(item.song),
    )

    is HomeJumpBackInItem.ArtistItem -> JumpBackInVisualEntry(
        stableId = item.stableId,
        title = item.artist.name,
        subtitle = "Artist",
        metaText = null,
        coverArtUrl = resolveHomeCoverArtUrl(item.artist.coverArt, buildCoverArtUrl),
        sharedAlbumId = null,
        sharedSourceKey = null,
        songId = null,
        variant = ExpressiveBackdropVariant.SoftBoom,
        shape = CircleShape,
        fallbackIcon = Icons.Filled.Person,
        target = HomeEntryTarget.Artist(item.artist.id.toString()),
    )
}

private fun backdropVariantForActivity(entityType: String): ExpressiveBackdropVariant = when (entityType) {
    ActivityEntityType.ALBUM.name -> ExpressiveBackdropVariant.Bun
    ActivityEntityType.SONG.name -> ExpressiveBackdropVariant.Circle
    ActivityEntityType.PLAYLIST.name -> ExpressiveBackdropVariant.Ghostish
    else -> ExpressiveBackdropVariant.SoftBoom
}

private fun artworkShapeForEntityType(entityType: String): Shape = when (entityType) {
    ActivityEntityType.ARTIST.name -> CircleShape
    else -> YoinShapeTokens.Small
}

private fun artworkFallbackIconForEntityType(
    entityType: String,
): androidx.compose.ui.graphics.vector.ImageVector = when (entityType) {
    ActivityEntityType.ARTIST.name -> Icons.Filled.Person
    ActivityEntityType.PLAYLIST.name -> Icons.AutoMirrored.Filled.QueueMusic
    else -> Icons.Filled.LibraryMusic
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

private fun buildActivityFootnote(activity: ActivityEvent): String {
    val label = when (activity.entityType) {
        ActivityEntityType.ALBUM.name -> "Album"
        ActivityEntityType.ARTIST.name -> "Artist"
        ActivityEntityType.PLAYLIST.name -> "Playlist"
        else -> "Track"
    }
    return "$label · ${formatTimeAgo(activity.timestamp)}"
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
