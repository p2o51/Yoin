package com.gpo.yoin.ui.home

import androidx.compose.animation.AnimatedVisibility
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
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.gpo.yoin.data.local.ActivityEntityType
import com.gpo.yoin.data.local.ActivityEvent
import com.gpo.yoin.data.remote.Song
import com.gpo.yoin.ui.component.ExpressiveBackdrop
import com.gpo.yoin.ui.component.ExpressiveBackdropVariant
import com.gpo.yoin.ui.component.ExpressiveMediaArtwork
import com.gpo.yoin.ui.component.ExpressiveSectionPanel
import com.gpo.yoin.ui.component.YoinLoadingIndicator
import com.gpo.yoin.ui.component.elasticPress
import com.gpo.yoin.ui.component.expressiveBackdropVariantAt
import com.gpo.yoin.ui.component.horizontalFadeMask
import com.gpo.yoin.ui.component.minimumTouchTarget
import com.gpo.yoin.ui.component.noRippleClickable
import com.gpo.yoin.ui.theme.YoinMotion
import com.gpo.yoin.ui.theme.YoinShapeTokens

private data class HomeMomentEntry(
    val stableId: String,
    val title: String,
    val subtitle: String,
    val footnote: String,
    val coverArtUrl: String?,
    val variant: ExpressiveBackdropVariant,
    val onClick: () -> Unit,
)

private data class JumpBackInVisualEntry(
    val stableId: String,
    val title: String,
    val subtitle: String?,
    val metaText: String?,
    val coverArtUrl: String?,
    val variant: ExpressiveBackdropVariant,
    val shape: Shape,
    val fallbackIcon: androidx.compose.ui.graphics.vector.ImageVector,
    val onClick: () -> Unit,
)

@Composable
internal fun HomeEditorialContent(
    activities: List<ActivityEvent>,
    jumpBackInItems: List<HomeJumpBackInItem>,
    jumpBackInRevision: Int,
    isLoadingMoreJumpBackIn: Boolean,
    isPlaying: Boolean,
    visualizerData: com.gpo.yoin.player.VisualizerData,
    onAlbumClick: (albumId: String) -> Unit,
    onArtistClick: (artistId: String) -> Unit,
    onSongClick: (Song) -> Unit,
    onRefreshJumpBackIn: () -> Unit,
    onLoadMoreJumpBackIn: () -> Unit,
    buildCoverArtUrl: (String) -> String,
    modifier: Modifier = Modifier,
) {
    val activityEntries = buildActivityEntries(
        activities = activities,
        buildCoverArtUrl = buildCoverArtUrl,
        onAlbumClick = onAlbumClick,
        onArtistClick = onArtistClick,
        onSongClick = onSongClick,
    )
    val jumpEntries = jumpBackInItems.mapIndexed { index, item ->
        buildJumpBackInEntry(
            item = item,
            variant = expressiveBackdropVariantAt(index),
            buildCoverArtUrl = buildCoverArtUrl,
            onAlbumClick = onAlbumClick,
            onArtistClick = onArtistClick,
            onSongClick = onSongClick,
        )
    }
    val jumpRows = jumpEntries.chunked(3)

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 132.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        item {
            AnimatedVisibility(visible = activityEntries.isNotEmpty()) {
                ActivityGrid(
                    entries = activityEntries.take(6),
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
                refreshTick = jumpBackInRevision,
                onRefreshClick = onRefreshJumpBackIn,
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
                    modifier = Modifier.fillMaxWidth(),
                )
                if (index >= jumpRows.lastIndex - 1 && !isLoadingMoreJumpBackIn) {
                    LaunchedEffect(jumpEntries.size, index) {
                        onLoadMoreJumpBackIn()
                    }
                }
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
private fun ActivityGrid(
    entries: List<HomeMomentEntry>,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        entries.chunked(2).forEach { rowEntries ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                rowEntries.forEach { entry ->
                    ActivityCard(
                        entry = entry,
                        modifier = Modifier.weight(1f),
                    )
                }
                if (rowEntries.size == 1) {
                    Spacer(modifier = Modifier.weight(1f))
                }
            }
        }
    }
}

@Composable
private fun ActivityCard(
    entry: HomeMomentEntry,
    modifier: Modifier = Modifier,
) {
    val interactionSource = remember { MutableInteractionSource() }

    Surface(
        modifier = modifier,
        shape = YoinShapeTokens.Large,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        tonalElevation = 2.dp,
        shadowElevation = 4.dp,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .noRippleClickable(interactionSource = interactionSource, onClick = entry.onClick)
                .padding(horizontal = 8.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ExpressiveArtwork(
                model = entry.coverArtUrl,
                contentDescription = entry.title,
                interactionSource = interactionSource,
                backdropVariant = entry.variant,
                modifier = Modifier.size(58.dp),
                fillFraction = 0.82f,
                offsetX = 4.dp,
                offsetY = 5.dp,
                shape = YoinShapeTokens.Small,
                fallbackIcon = Icons.Filled.LibraryMusic,
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
    refreshTick: Int,
    onRefreshClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val refreshRotation by animateFloatAsState(
        targetValue = refreshTick * 360f,
        animationSpec = YoinMotion.spatialSpring(),
        label = "jumpBackInRefreshRotation",
    )

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
        IconButton(
            onClick = onRefreshClick,
            modifier = Modifier.minimumTouchTarget(),
        ) {
            Icon(
                imageVector = Icons.Filled.Refresh,
                contentDescription = "Refresh Jump Back In",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.graphicsLayer {
                    rotationZ = refreshRotation
                },
            )
        }
    }
}

@Composable
private fun JumpBackInRow(
    entries: List<JumpBackInVisualEntry>,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        verticalAlignment = Alignment.Top,
    ) {
        entries.forEach { entry ->
            JumpBackInTile(
                entry = entry,
                modifier = Modifier.weight(1f),
            )
        }
        repeat(3 - entries.size) {
            Spacer(modifier = Modifier.weight(1f))
        }
    }
}

@Composable
private fun JumpBackInTile(
    entry: JumpBackInVisualEntry,
    modifier: Modifier = Modifier,
) {
    val interactionSource = remember { MutableInteractionSource() }
    Column(
        modifier = modifier
            .noRippleClickable(interactionSource = interactionSource, onClick = entry.onClick),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        ExpressiveArtwork(
            model = entry.coverArtUrl,
            contentDescription = entry.title,
            interactionSource = interactionSource,
            backdropVariant = entry.variant,
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(1f),
            fillFraction = 0.78f,
            offsetX = 6.dp,
            offsetY = 8.dp,
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

@Composable
private fun ExpressiveArtwork(
    model: String?,
    contentDescription: String,
    backdropVariant: ExpressiveBackdropVariant,
    modifier: Modifier = Modifier,
    interactionSource: MutableInteractionSource? = null,
    fillFraction: Float = 0.78f,
    offsetX: Dp = 8.dp,
    offsetY: Dp = 10.dp,
    shape: Shape = YoinShapeTokens.ExtraLarge,
    fallbackIcon: androidx.compose.ui.graphics.vector.ImageVector = Icons.Filled.LibraryMusic,
) {
    Box(modifier = modifier) {
        ExpressiveBackdrop(
            baseColor = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.72f),
            accentColor = MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.48f),
            variant = backdropVariant,
            modifier = Modifier.matchParentSize(),
        )

        val coverModifier = Modifier
            .fillMaxSize(fillFraction)
            .align(Alignment.Center)
            .offset(x = offsetX, y = offsetY)
            .shadow(14.dp, shape = shape, clip = false)
            .then(
                if (interactionSource != null) {
                    Modifier.elasticPress(interactionSource)
                } else {
                    Modifier
                },
            )

        Box(modifier = coverModifier) {
            ExpressiveMediaArtwork(
                model = model,
                contentDescription = contentDescription,
                modifier = Modifier.fillMaxSize(),
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
        tonalElevation = 2.dp,
        shadowElevation = 8.dp,
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

private fun buildActivityEntries(
    activities: List<ActivityEvent>,
    buildCoverArtUrl: (String) -> String,
    onAlbumClick: (albumId: String) -> Unit,
    onArtistClick: (artistId: String) -> Unit,
    onSongClick: (Song) -> Unit,
): List<HomeMomentEntry> = activities.take(6).mapIndexed { index, activity ->
    HomeMomentEntry(
        stableId = "activity:${activity.entityType}:${activity.entityId}:${activity.actionType}",
        title = activity.title,
        subtitle = activity.subtitle.ifBlank {
            when (activity.entityType) {
                ActivityEntityType.ARTIST.name -> "Artist"
                else -> "Recently active"
            }
        },
        footnote = buildActivityFootnote(activity),
        coverArtUrl = buildActivityCoverArtUrl(activity, buildCoverArtUrl),
        variant = expressiveBackdropVariantAt(index),
        onClick = {
            when (activity.entityType) {
                ActivityEntityType.ALBUM.name -> onAlbumClick(activity.entityId)
                ActivityEntityType.ARTIST.name -> onArtistClick(activity.entityId)
                else -> onSongClick(activity.asSong())
            }
        },
    )
}

private fun buildJumpBackInEntry(
    item: HomeJumpBackInItem,
    variant: ExpressiveBackdropVariant,
    buildCoverArtUrl: (String) -> String,
    onAlbumClick: (albumId: String) -> Unit,
    onArtistClick: (artistId: String) -> Unit,
    onSongClick: (Song) -> Unit,
): JumpBackInVisualEntry = when (item) {
    is HomeJumpBackInItem.AlbumItem -> JumpBackInVisualEntry(
        stableId = item.stableId,
        title = item.album.name,
        subtitle = item.album.artist,
        metaText = item.album.songCount?.let { "$it tracks" },
        coverArtUrl = item.album.coverArt?.let(buildCoverArtUrl) ?: buildCoverArtUrl(item.album.id),
        variant = variant,
        shape = YoinShapeTokens.Large,
        fallbackIcon = Icons.Filled.LibraryMusic,
        onClick = { onAlbumClick(item.album.id) },
    )

    is HomeJumpBackInItem.SongItem -> JumpBackInVisualEntry(
        stableId = item.stableId,
        title = item.song.title.orEmpty(),
        subtitle = item.song.artist,
        metaText = "Single",
        coverArtUrl = item.song.coverArt?.let(buildCoverArtUrl)
            ?: item.song.albumId?.let(buildCoverArtUrl),
        variant = variant,
        shape = YoinShapeTokens.Large,
        fallbackIcon = Icons.Filled.LibraryMusic,
        onClick = { onSongClick(item.song) },
    )

    is HomeJumpBackInItem.ArtistItem -> JumpBackInVisualEntry(
        stableId = item.stableId,
        title = item.artist.name,
        subtitle = "Artist",
        metaText = null,
        coverArtUrl = item.artist.coverArt?.let(buildCoverArtUrl),
        variant = variant,
        shape = YoinShapeTokens.Full,
        fallbackIcon = Icons.Filled.Person,
        onClick = { onArtistClick(item.artist.id) },
    )
}

private fun ActivityEvent.asSong(): Song = Song(
    id = songId ?: entityId,
    title = title,
    artist = subtitle,
    albumId = albumId.takeIf { !it.isNullOrBlank() },
    coverArt = coverArtId,
    artistId = artistId,
)

private fun buildActivityCoverArtUrl(
    activity: ActivityEvent,
    buildCoverArtUrl: (String) -> String,
): String? = when {
    activity.coverArtId != null -> buildCoverArtUrl(activity.coverArtId)
    activity.entityType == ActivityEntityType.ALBUM.name -> buildCoverArtUrl(activity.entityId)
    !activity.albumId.isNullOrBlank() -> buildCoverArtUrl(activity.albumId)
    else -> null
}

private fun buildActivityFootnote(activity: ActivityEvent): String {
    val label = when (activity.entityType) {
        ActivityEntityType.ALBUM.name -> "Album"
        ActivityEntityType.ARTIST.name -> "Artist"
        else -> "Track"
    }
    return "$label · ${formatTimeAgo(activity.timestamp)}"
}

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
