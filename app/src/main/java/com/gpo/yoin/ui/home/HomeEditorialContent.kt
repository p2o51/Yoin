package com.gpo.yoin.ui.home

import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
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
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material.icons.filled.Settings
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
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.gpo.yoin.data.local.ActivityEntityType
import com.gpo.yoin.data.local.ActivityEvent
import com.gpo.yoin.data.model.Album
import com.gpo.yoin.data.model.CoverRef
import com.gpo.yoin.data.model.MediaId
import com.gpo.yoin.data.model.Track
import com.gpo.yoin.ui.component.ExpressiveMediaArtwork
import com.gpo.yoin.ui.component.ExpressiveSectionPanel
import com.gpo.yoin.ui.component.MarqueeText
import com.gpo.yoin.ui.component.elasticPress
import com.gpo.yoin.ui.component.ignoreParentHorizontalPadding
import com.gpo.yoin.ui.component.horizontalEdgeFadeOnScroll
import com.gpo.yoin.ui.component.noRippleClickable
import com.gpo.yoin.ui.component.rememberExpressiveBackdropColors
import com.gpo.yoin.ui.component.yoinPageContentWidth
import com.gpo.yoin.ui.experience.LayoutMode
import com.gpo.yoin.ui.experience.LocalYoinWindowInfo
import com.gpo.yoin.ui.experience.RevealState
import com.gpo.yoin.ui.experience.rememberRevealState
import com.gpo.yoin.ui.experience.rememberYoinHaptics
import com.gpo.yoin.ui.theme.ContinuousRoundedCornerShape
import com.gpo.yoin.ui.theme.YoinMotion
import com.gpo.yoin.ui.theme.YoinShapeTokens
import com.gpo.yoin.ui.theme.YoinArtworkShapes
import com.gpo.yoin.ui.theme.YoinContainerShapes
import com.gpo.yoin.ui.theme.withTabularFigures
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest

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

private const val HomeBackdropPaletteWarmupDelayMillis = 350L

@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
internal fun HomeEditorialContent(
    activities: List<ActivityEvent>,
    widgetGrid: List<HomeWidgetCard> = emptyList(),
    activityHeroFootnote: String? = null,
    recentlyAddedTracks: List<Track> = emptyList(),
    recentlyAddedAlbums: List<Album> = emptyList(),
    sections: List<HomeSectionState> = HomeLayout.Default.sections,
    onNavigateToSettings: () -> Unit,
    onNavigateToMemories: () -> Unit,
    // Long-press anywhere on the feed enters the home layout editor.
    onEnterEditMode: () -> Unit = {},
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
    val onEnterEditModeState = rememberUpdatedState(onEnterEditMode)
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

    val navBarBottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    LazyColumn(
        state = listState,
        modifier = modifier
            .fillMaxSize()
            // 大屏限宽:夹的是内容列本身;高度不受影响,所以下面
            // onSizeChanged 喂给 reveal settle 的 containerHeightPx 语义不变。
            .yoinPageContentWidth()
            .onSizeChanged { containerHeightPx = it.height.toFloat().coerceAtLeast(1f) }
            .nestedScroll(pullToMemoriesConnection)
            // Long-press → layout editor. Cards only consume taps
            // (noRippleClickable), so the press passes through them; any scroll
            // movement cancels it before the timeout.
            .pointerInput(Unit) {
                detectTapGestures(
                    onLongPress = {
                        haptics.performLongPress()
                        onEnterEditModeState.value()
                    },
                )
            },
        contentPadding = PaddingValues(
            start = 16.dp,
            end = 16.dp,
            top = 4.dp,
            bottom = 108.dp + navBarBottom,
        ),
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        // The page header (title + nav icons) is pinned above the reorderable
        // sections — it's chrome, not a section.
        item(key = "home-header") {
            HomeContentHeader(
                // Page-level title: sections below it are user-reorderable, so
                // the header can't borrow the first section's name anymore.
                title = "Home",
                onNavigateToSettings = onNavigateToSettings,
                onNavigateToMemories = onNavigateToMemories,
                memoriesHintProgress = memoriesHintProgress,
                modifier = Modifier.fillMaxWidth(),
            )
        }

        // Data-driven feed: render each enabled section in the user's chosen
        // order.
        for (sectionState in sections) {
            if (!sectionState.enabled) continue
            when (sectionState.section) {
                HomeSection.Activities -> item(key = "section-activities") {
                    if (activityEntries.isNotEmpty()) {
                        if (LocalYoinWindowInfo.current.layoutMode != LayoutMode.Compact) {
                            // ≥ Medium panes trade the phone bento for the
                            // Spotify-style shortcut grid (owner-approved,
                            // 2026-07-27). Same entry pipeline, longer prefix:
                            // the grid seats up to 8 where the bento shows 4.
                            val tileEntries = remember(activities, buildCoverArtUrl) {
                                buildActivityEntries(
                                    activities = activities,
                                    buildCoverArtUrl = buildCoverArtUrl,
                                    limit = ActivitiesTileGridMaxItems,
                                )
                            }
                            ActivitiesTileGrid(
                                entries = tileEntries,
                                extractBackdropColors = shouldExtractBackdropColors,
                                onEntryClick = onEntryClick,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .animateItem(
                                        fadeInSpec = YoinMotion.effectsSpring(),
                                        placementSpec = YoinMotion.spatialSpring(),
                                        fadeOutSpec = YoinMotion.effectsSpring(),
                                    ),
                            )
                        } else {
                            // Hero slot = first album/playlist; artists fill the
                            // smaller cards in recency order.
                            val heroEntry = activityEntries.firstOrNull { entry ->
                                entry.entityType == ActivityEntityType.ALBUM.name ||
                                    entry.entityType == ActivityEntityType.PLAYLIST.name
                            }
                            ActivityBento(
                                hero = heroEntry,
                                supporting = activityEntries
                                    .filterNot { it === heroEntry }
                                    .take(3),
                                heroFootnoteExtra = activityHeroFootnote,
                                extractBackdropColors = shouldExtractBackdropColors,
                                onEntryClick = onEntryClick,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .animateItem(
                                        fadeInSpec = YoinMotion.effectsSpring(),
                                        placementSpec = YoinMotion.spatialSpring(),
                                        fadeOutSpec = YoinMotion.effectsSpring(),
                                    ),
                            )
                        }
                    } else {
                        HomeEmptyCard(
                            title = "No recent activity yet",
                            supporting = "Once you listen or visit albums and artists, this feed will start filling in.",
                            modifier = Modifier
                                .fillMaxWidth()
                                .animateItem(
                                    fadeInSpec = YoinMotion.effectsSpring(),
                                    placementSpec = YoinMotion.spatialSpring(),
                                    fadeOutSpec = YoinMotion.effectsSpring(),
                                ),
                        )
                    }
                }

                // The merged Jump Back In × memories widget grid. Empty means
                // nothing resolved from any source — skip the section entirely.
                HomeSection.JumpBackIn -> if (widgetGrid.isNotEmpty()) {
                    item(key = "section-widget-grid") {
                        HomeWidgetGridSection(
                            title = "Jump Back In",
                            cards = widgetGrid,
                            extractBackdropColors = shouldExtractBackdropColors,
                            onCardClick = onWidgetCardClick,
                            modifier = Modifier
                                .fillMaxWidth()
                                .animateItem(
                                    fadeInSpec = YoinMotion.effectsSpring(),
                                    placementSpec = YoinMotion.spatialSpring(),
                                    fadeOutSpec = YoinMotion.effectsSpring(),
                                ),
                        )
                    }
                }

                // Only render when there's something added this week — an empty
                // "recently added" shelf is noise, not information.
                HomeSection.RecentlyAdded ->
                    if (recentlyAddedTracks.isNotEmpty() || recentlyAddedAlbums.isNotEmpty()) {
                        item(key = "section-recently-added") {
                            RecentlyAddedSection(
                                tracks = recentlyAddedTracks,
                                albums = recentlyAddedAlbums,
                                extractBackdropColors = shouldExtractBackdropColors,
                                onTrackClick = { track -> onEntryClick(HomeEntryTarget.SongTarget(track)) },
                                onAlbumClick = { album ->
                                    onEntryClick(HomeEntryTarget.Album(album.id.toString(), null))
                                },
                                buildCoverArtUrl = buildCoverArtUrl,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .animateItem(
                                        fadeInSpec = YoinMotion.effectsSpring(),
                                        placementSpec = YoinMotion.spatialSpring(),
                                        fadeOutSpec = YoinMotion.effectsSpring(),
                                    ),
                            )
                        }
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
    val haptics = rememberYoinHaptics()
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
                onClick = {
                    haptics.performContextClick()
                    onNavigateToMemories()
                },
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
            IconButton(
                onClick = {
                    haptics.performContextClick()
                    onNavigateToSettings()
                },
            ) {
                Icon(
                    imageVector = Icons.Filled.Settings,
                    contentDescription = "Settings",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

// ── Activities bento (Figma node 405:362) ──────────────────────────────
//
// Four recent activities in a bento of decreasing prominence: a full-width
// hero, a small square + wide card row, and a single-line strip. Each card's
// container is tonally derived from its own cover art, echoing the mockup's
// per-card colour washes.

@Composable
private fun ActivityBento(
    // The hero slot only carries an album / playlist (or nothing); the
    // supporting cards take the rest in recency order: [0] small square,
    // [1] wide, [2] strip.
    hero: HomeMomentEntry?,
    supporting: List<HomeMomentEntry>,
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
        hero?.let { entry ->
            ActivityHeroCard(
                entry = entry,
                footnoteExtra = heroFootnoteExtra,
                extractBackdropColors = extractBackdropColors,
                onClick = { onEntryClick(entry.target) },
                modifier = Modifier.fillMaxWidth(),
            )
        }
        if (supporting.isNotEmpty()) {
            // Fixed row height (Figma: 97pt), scaled with the user's font size:
            // IntrinsicSize would crash here — MarqueeTitle's BoxWithConstraints
            // is a SubcomposeLayout, which cannot answer intrinsic measurements.
            val fontScale = LocalDensity.current.fontScale.coerceAtLeast(1f)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(118.dp * fontScale),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                supporting.getOrNull(0)?.let { small ->
                    ActivitySmallCard(
                        entry = small,
                        extractBackdropColors = extractBackdropColors,
                        onClick = { onEntryClick(small.target) },
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight(),
                    )
                }
                supporting.getOrNull(1)?.let { wide ->
                    ActivityWideCard(
                        entry = wide,
                        extractBackdropColors = extractBackdropColors,
                        onClick = { onEntryClick(wide.target) },
                        modifier = Modifier
                            .weight(2f)
                            .fillMaxHeight(),
                    )
                } ?: run {
                    // Keep the lone small card at column width instead of
                    // letting its weight stretch it across the whole row.
                    Spacer(modifier = Modifier.weight(2f))
                }
            }
        }
        supporting.getOrNull(2)?.let { strip ->
            ActivityStripCard(
                entry = strip,
                extractBackdropColors = extractBackdropColors,
                onClick = { onEntryClick(strip.target) },
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

private data class ActivityCardColors(
    val container: Color,
    val content: Color,
    val contentMuted: Color,
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
private fun rememberActivityCardColors(
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
        modifier = modifier.elasticPress(interactionSource),
        shape = YoinContainerShapes.Card,
        color = colors.container,
        tonalElevation = 0.dp,
        shadowElevation = 0.dp,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .noRippleClickable(interactionSource = interactionSource, onClick = onClick)
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
                modifier = Modifier.weight(1f),
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
        modifier = modifier.elasticPress(interactionSource),
        shape = YoinContainerShapes.Card,
        color = colors.container,
        tonalElevation = 0.dp,
        shadowElevation = 0.dp,
    ) {
        Column(
            modifier = Modifier
                .noRippleClickable(interactionSource = interactionSource, onClick = onClick)
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
                Column {
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
        modifier = modifier.elasticPress(interactionSource),
        shape = YoinContainerShapes.Card,
        color = colors.container,
        tonalElevation = 0.dp,
        shadowElevation = 0.dp,
    ) {
        Row(
            modifier = Modifier
                .noRippleClickable(interactionSource = interactionSource, onClick = onClick)
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
                modifier = Modifier.weight(1f),
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
        modifier = modifier.elasticPress(interactionSource),
        shape = YoinShapeTokens.Full,
        color = colors.container,
        tonalElevation = 0.dp,
        shadowElevation = 0.dp,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .noRippleClickable(interactionSource = interactionSource, onClick = onClick)
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
                modifier = Modifier.weight(1f),
            )
            Text(
                text = "${entry.typeLabel} · ${entry.timeAgo}",
                style = MaterialTheme.typography.labelSmall,
                color = colors.contentMuted,
                maxLines = 1,
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

// ── Activities shortcut grid (≥ Medium panes) ──────────────────────────
//
// The large-window take on the section: instead of the bento's decreasing
// prominence, a Spotify-style 2-column grid of equal shortcut tiles — up to
// 2×4, fewer rows when fewer activities. Each tile keeps the bento cards'
// language (per-cover tinted container, entity-shape backdrop artwork) shrunk
// to a 64dp bar. Compact panes never see this — the bento above is untouched.

private const val ActivitiesTileGridColumns = 2
private const val ActivitiesTileGridMaxItems = 8
private val ActivityShortcutTileHeight = 64.dp
private val ActivityShortcutTileArtwork = 56.dp

@Composable
private fun ActivitiesTileGrid(
    entries: List<HomeMomentEntry>,
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
        val rows = entries.take(ActivitiesTileGridMaxItems).chunked(ActivitiesTileGridColumns)
        rows.forEach { rowEntries ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                rowEntries.forEach { entry ->
                    ActivityShortcutTile(
                        entry = entry,
                        extractBackdropColors = extractBackdropColors,
                        onClick = { onEntryClick(entry.target) },
                        modifier = Modifier.weight(1f),
                    )
                }
                // Pad an odd final row so a lone tile keeps its column width
                // instead of stretching across the whole grid.
                if (rowEntries.size < ActivitiesTileGridColumns) {
                    Spacer(modifier = Modifier.weight(1f))
                }
            }
        }
    }
}

@Composable
private fun ActivityShortcutTile(
    entry: HomeMomentEntry,
    extractBackdropColors: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val colors = rememberActivityCardColors(entry.coverArtUrl, extractBackdropColors)
    // Fixed 64dp bar per the approved spec, scaled with the user's font size
    // (same accommodation the bento's supporting row makes) so the two text
    // lines never clip at accessibility sizes.
    val fontScale = LocalDensity.current.fontScale.coerceAtLeast(1f)
    Surface(
        modifier = modifier.elasticPress(interactionSource),
        shape = ContinuousRoundedCornerShape(14.dp),
        color = colors.container,
        tonalElevation = 0.dp,
        shadowElevation = 0.dp,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(ActivityShortcutTileHeight * fontScale)
                .noRippleClickable(interactionSource = interactionSource, onClick = onClick)
                // The thumb sits near-flush (4dp) like Spotify's shortcut
                // tiles; the text keeps a fuller 12dp end inset.
                .padding(start = 4.dp, end = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            WidgetBackdropArtwork(
                model = entry.coverArtUrl,
                kind = widgetShapeKindForActivity(entry.entityType),
                contentDescription = entry.title,
                extractBackdropColors = extractBackdropColors,
                interactionSource = interactionSource,
                modifier = Modifier.size(ActivityShortcutTileArtwork),
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = entry.title,
                    style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                    color = colors.content,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                // Same metadata the small bento card shows (type + recency),
                // joined with the hero/wide cards' dot format.
                Text(
                    text = "${entry.typeLabel} · ${entry.timeAgo}",
                    style = MaterialTheme.typography.labelSmall,
                    color = colors.contentMuted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

// ── Recently Added (tracks grid + album shelf, Figma 622:777) ──────────
//
// A split shelf: on the left a compact 2×2 grid of the four most-recently
// added tracks (small cover + title / artist), on the right a horizontally
// scrolling row of recently-added albums, each nested on its Bun backdrop
// shape. Either half collapses when its list is empty, and the lone survivor
// takes the full width.

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
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        HomeSectionTitle(text = "Recently Added")
        // ONE shelf: the 2×2 track grid is the shelf's first card and the
        // albums follow it, all panning together (user call — the albums
        // scrolling alone under a pinned grid read as two disjoint widgets).
        // Full-bleed with page-margin content padding; content clips hard at
        // the screen edge — no edge-fade scrim here (2026-07-18 ruling: the
        // translucent mask read as clutter on this shelf; the seamless cut
        // wins).
        BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
            // The grid keeps its old resting share of the viewport (2.6 of
            // 3.6 weight units) so the resting frame is unchanged: grid left,
            // ~1.5 album cards peeking on the right.
            val trackGridWidth = (maxWidth - 14.dp) * (2.6f / 3.6f)
            val shelfState = rememberLazyListState()
            LazyRow(
                state = shelfState,
                modifier = Modifier
                    .ignoreParentHorizontalPadding(16.dp),
                contentPadding = PaddingValues(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(14.dp),
                // Both halves hang from the top. The track covers are sized
                // so a tight 2×2 lands at roughly the album card's height.
                verticalAlignment = Alignment.Top,
            ) {
                if (tracks.isNotEmpty()) {
                    item(key = "recently-added-tracks") {
                        RecentlyAddedTrackGrid(
                            tracks = tracks,
                            onTrackClick = onTrackClick,
                            buildCoverArtUrl = buildCoverArtUrl,
                            modifier = Modifier.width(trackGridWidth),
                        )
                    }
                }
                items(
                    items = albums,
                    key = { album -> "recently-added-album:${album.id}" },
                ) { album ->
                    RecentlyAddedAlbumCard(
                        album = album,
                        extractBackdropColors = extractBackdropColors,
                        onClick = { onAlbumClick(album) },
                        buildCoverArtUrl = buildCoverArtUrl,
                    )
                }
            }
        }
    }
}

/** The shelf's lead card: up to four tracks packed into a 2×2 grid. */
@Composable
private fun RecentlyAddedTrackGrid(
    tracks: List<Track>,
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
        tracks.take(4).chunked(2).forEach { rowTracks ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                rowTracks.forEach { track ->
                    RecentlyAddedTrackTile(
                        track = track,
                        onClick = { onTrackClick(track) },
                        buildCoverArtUrl = buildCoverArtUrl,
                        modifier = Modifier.weight(1f),
                    )
                }
                // Pad an odd final row so a lone tile keeps its column width
                // instead of stretching across the whole grid.
                if (rowTracks.size < 2) {
                    Spacer(modifier = Modifier.weight(1f))
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
    val coverArtUrl = resolveHomeCoverArtUrl(track.coverArt, buildCoverArtUrl)
        ?: track.albumId?.let { buildCoverArtUrl(it.rawId) }
    Row(
        modifier = modifier
            .noRippleClickable(interactionSource = interactionSource, onClick = onClick)
            .elasticPress(interactionSource),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ExpressiveMediaArtwork(
            model = coverArtUrl,
            contentDescription = track.title.orEmpty(),
            modifier = Modifier.size(RecentlyAddedTrackCover),
            shape = YoinArtworkShapes.Thumb,
            fallbackIcon = Icons.Filled.LibraryMusic,
            interactionSource = interactionSource,
            tonalElevation = 1.dp,
            shadowElevation = 0.dp,
        )
        Column(modifier = Modifier.weight(1f)) {
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
            .noRippleClickable(interactionSource = interactionSource, onClick = onClick)
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
        )
        album.artist?.takeIf { it.isNotBlank() }?.let { artist ->
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

@Composable
private fun HomeEmptyCard(
    title: String,
    supporting: String,
    modifier: Modifier = Modifier,
) {
    ExpressiveSectionPanel(
        modifier = modifier,
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
 */
private fun activityEntityRawId(raw: String): String =
    if (raw.contains(':')) raw.substringAfter(':') else raw

private fun homeActivityDedupKey(activity: ActivityEvent): String {
    val canonicalEntityId = when (activity.entityType) {
        ActivityEntityType.SONG.name ->
            activityEntityRawId(activity.songId ?: activity.entityId)
        else -> activityEntityRawId(activity.entityId)
    }
    return "${activity.entityType}:$canonicalEntityId"
}

private fun buildActivityEntries(
    activities: List<ActivityEvent>,
    buildCoverArtUrl: (String) -> String,
    // 6 = the bento's historical cap (hero + 3 supporting from the top 6);
    // the ≥ Medium shortcut grid asks for its own 8. The default keeps the
    // Compact pipeline byte-identical.
    limit: Int = 6,
): List<HomeMomentEntry> = selectHomeActivities(activities).take(limit).map { activity ->
    val stableId = "activity:${activity.id}:${activity.entityType}:${activity.entityId}:${activity.actionType}"
    val rawEntityId = activityEntityRawId(activity.entityId)
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
