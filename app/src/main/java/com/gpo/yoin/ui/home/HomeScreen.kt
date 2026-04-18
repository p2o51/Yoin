package com.gpo.yoin.ui.home

import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.gpo.yoin.R
import com.gpo.yoin.data.local.ActivityActionType
import com.gpo.yoin.data.local.ActivityEntityType
import com.gpo.yoin.data.local.ActivityEvent
import com.gpo.yoin.data.local.PlayHistory
import com.gpo.yoin.data.model.Album
import com.gpo.yoin.data.model.Artist
import com.gpo.yoin.data.model.CoverRef
import com.gpo.yoin.data.model.MediaId
import com.gpo.yoin.data.model.Track
// VisualizerData intentionally removed: HomeScreen consumes a pre-smoothed
// playbackSignal from AudioVisualizerManager instead.
import com.gpo.yoin.ui.component.AlbumCard
import com.gpo.yoin.ui.component.AudioVisualizer
import com.gpo.yoin.ui.component.VisualizerStyle
import com.gpo.yoin.ui.component.YoinLoadingIndicator
import com.gpo.yoin.ui.experience.ReportMotionPressure
import com.gpo.yoin.ui.experience.RevealState
import com.gpo.yoin.ui.experience.rememberRevealState
import com.gpo.yoin.ui.theme.ProvideYoinMotionRole
import com.gpo.yoin.ui.theme.YoinMotion
import com.gpo.yoin.ui.theme.YoinMotionRole
import com.gpo.yoin.ui.theme.YoinShapeTokens
import com.gpo.yoin.ui.theme.YoinTheme
import kotlinx.coroutines.delay

private val FloatingBottomGroupClearance = 132.dp
private const val HomeLoadingIndicatorDelayMillis = 180L
private val HomeInitialEntranceOffset = 16.dp

@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
fun HomeScreen(
    viewModel: HomeViewModel,
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
    sharedTransitionScope: SharedTransitionScope? = null,
    animatedVisibilityScope: AnimatedVisibilityScope? = null,
    modifier: Modifier = Modifier,
) {
    val uiState by viewModel.uiState.collectAsState()

    HomeContent(
        uiState = uiState,
        isPlaying = isPlaying,
        playbackSignal = playbackSignal,
        activeSongId = activeSongId,
        onNavigateToSettings = onNavigateToSettings,
        onNavigateToMemories = onNavigateToMemories,
        memoriesRevealState = memoriesRevealState,
        onCommitMemoriesReveal = onCommitMemoriesReveal,
        onAlbumClick = onAlbumClick,
        onArtistClick = onArtistClick,
        onPlaylistClick = onPlaylistClick,
        onSongClick = onSongClick,
        onRetry = viewModel::refresh,
        onLoadMoreJumpBackIn = viewModel::loadMoreJumpBackIn,
        buildCoverArtUrl = viewModel::buildCoverArtUrl,
        sharedTransitionScope = sharedTransitionScope,
        animatedVisibilityScope = animatedVisibilityScope,
        modifier = modifier,
    )
}

@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
fun HomeContent(
    uiState: HomeUiState,
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
    onRetry: () -> Unit,
    onLoadMoreJumpBackIn: () -> Unit,
    buildCoverArtUrl: (String) -> String,
    sharedTransitionScope: SharedTransitionScope? = null,
    animatedVisibilityScope: AnimatedVisibilityScope? = null,
    modifier: Modifier = Modifier,
) {
    ReportMotionPressure(
        tag = "home",
        isHighPressure = uiState is HomeUiState.Loading ||
            (uiState as? HomeUiState.Content)?.isLoadingMoreJumpBackIn == true,
    )

    ProvideYoinMotionRole(role = YoinMotionRole.Expressive) {
        Surface(
            modifier = modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.background,
        ) {
            val isLoading = uiState is HomeUiState.Loading
            val isContent = uiState is HomeUiState.Content
            val contentEntranceOffsetPx = with(LocalDensity.current) { HomeInitialEntranceOffset.toPx() }
            var showDelayedLoading by remember { mutableStateOf(false) }
            var hasPlayedInitialContentEntrance by rememberSaveable { mutableStateOf(false) }
            LaunchedEffect(isLoading) {
                if (!isLoading) {
                    showDelayedLoading = false
                    return@LaunchedEffect
                }
                showDelayedLoading = false
                delay(HomeLoadingIndicatorDelayMillis)
                showDelayedLoading = true
            }
            LaunchedEffect(isContent) {
                if (isContent && !hasPlayedInitialContentEntrance) {
                    hasPlayedInitialContentEntrance = true
                }
            }
            val contentAlpha by animateFloatAsState(
                targetValue = if (isContent && hasPlayedInitialContentEntrance) 1f else 0f,
                animationSpec = YoinMotion.defaultEffectsSpec(),
                label = "homeInitialContentAlpha",
            )
            val contentOffsetProgress by animateFloatAsState(
                targetValue = if (isContent && hasPlayedInitialContentEntrance) 1f else 0f,
                animationSpec = YoinMotion.defaultSpatialSpec(),
                label = "homeInitialContentOffset",
            )

            when (uiState) {
                is HomeUiState.Loading -> {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center,
                    ) {
                        if (showDelayedLoading) {
                            YoinLoadingIndicator()
                        }
                    }
                }

                is HomeUiState.Error -> {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center,
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(
                                text = uiState.message,
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.error,
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                TextButton(onClick = onRetry) {
                                    Text("Retry")
                                }
                                TextButton(onClick = onNavigateToSettings) {
                                    Text("Settings")
                                }
                            }
                        }
                    }
                }

                is HomeUiState.Content -> {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .graphicsLayer {
                                alpha = contentAlpha
                                translationY = (1f - contentOffsetProgress) * contentEntranceOffsetPx
                            },
                    ) {
                        HomeEditorialContent(
                            activities = uiState.activities,
                            jumpBackInItems = uiState.jumpBackInItems,
                            isLoadingMoreJumpBackIn = uiState.isLoadingMoreJumpBackIn,
                            isPlaying = isPlaying,
                            playbackSignal = playbackSignal,
                            activeSongId = activeSongId,
                            onNavigateToSettings = onNavigateToSettings,
                            onNavigateToMemories = onNavigateToMemories,
                            memoriesRevealState = memoriesRevealState,
                            onCommitMemoriesReveal = onCommitMemoriesReveal,
                            onAlbumClick = onAlbumClick,
                            onArtistClick = onArtistClick,
                            onPlaylistClick = onPlaylistClick,
                            onSongClick = onSongClick,
                            onLoadMoreJumpBackIn = onLoadMoreJumpBackIn,
                            buildCoverArtUrl = buildCoverArtUrl,
                            sharedTransitionScope = sharedTransitionScope,
                            animatedVisibilityScope = animatedVisibilityScope,
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun HomeContentSections(
    activities: List<PlayHistory>,
    recentlyAdded: List<Album>,
    mixForYou: List<Album>,
    mostPlayed: List<Album>,
    quickPlaySongs: List<Track>,
    quickPlayAlbums: List<Album>,
    onAlbumClick: (albumId: String) -> Unit,
    onSongClick: (Track) -> Unit,
    buildCoverArtUrl: (String) -> String,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier) {
        if (activities.isNotEmpty()) {
            SectionHeader(title = "Activities")
            ActivitiesRow(
                activities = activities,
                onSongClick = onSongClick,
                buildCoverArtUrl = buildCoverArtUrl,
            )
            Spacer(modifier = Modifier.height(24.dp))
        }

        if (quickPlaySongs.isNotEmpty() || quickPlayAlbums.isNotEmpty()) {
            SectionHeader(title = "Quick Play")
            QuickPlayRow(
                songs = quickPlaySongs,
                albums = quickPlayAlbums,
                onSongClick = onSongClick,
                onAlbumClick = onAlbumClick,
                buildCoverArtUrl = buildCoverArtUrl,
            )
            Spacer(modifier = Modifier.height(24.dp))
        }

        if (recentlyAdded.isNotEmpty()) {
            SectionHeader(title = "Recently Added")
            AlbumsRow(
                albums = recentlyAdded,
                onAlbumClick = onAlbumClick,
                buildCoverArtUrl = buildCoverArtUrl,
            )
            Spacer(modifier = Modifier.height(24.dp))
        }

        if (mixForYou.isNotEmpty()) {
            SectionHeader(title = "Mix For You")
            AlbumsRow(
                albums = mixForYou,
                onAlbumClick = onAlbumClick,
                buildCoverArtUrl = buildCoverArtUrl,
            )
            Spacer(modifier = Modifier.height(24.dp))
        }

        if (mostPlayed.isNotEmpty()) {
            SectionHeader(title = "Most Played")
            AlbumsRow(
                albums = mostPlayed,
                onAlbumClick = onAlbumClick,
                buildCoverArtUrl = buildCoverArtUrl,
            )
            Spacer(modifier = Modifier.height(24.dp))
        }

        Spacer(modifier = Modifier.height(FloatingBottomGroupClearance))
    }
}

@Composable
private fun SectionHeader(
    title: String,
    modifier: Modifier = Modifier,
) {
    Text(
        text = title,
        style = MaterialTheme.typography.titleLarge,
        color = MaterialTheme.colorScheme.onBackground,
        modifier = modifier.padding(horizontal = 16.dp, vertical = 8.dp),
    )
}

@Composable
private fun QuickPlayRow(
    songs: List<Track>,
    albums: List<Album>,
    onSongClick: (Track) -> Unit,
    onAlbumClick: (albumId: String) -> Unit,
    buildCoverArtUrl: (String) -> String,
    modifier: Modifier = Modifier,
) {
    LazyRow(
        modifier = modifier,
        contentPadding = PaddingValues(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // Albums first — larger cards for visual variety
        itemsIndexed(
            items = albums,
            key = { _, album -> "qp_album_${album.id}" },
        ) { _, album ->
            val coverUrl = coverArtUrlFor(album.coverArt, buildCoverArtUrl)
                ?: buildCoverArtUrl(album.id.rawId)

            QuickPlayAlbumCard(
                album = album,
                coverArtUrl = coverUrl,
                onClick = { onAlbumClick(album.id.toString()) },
            )
        }

        // Songs after albums
        itemsIndexed(
            items = songs,
            key = { _, song -> "qp_song_${song.id}" },
        ) { _, song ->
            val coverUrl = coverArtUrlFor(song.coverArt, buildCoverArtUrl)

            QuickPlayCard(
                song = song,
                coverArtUrl = coverUrl,
                onClick = { onSongClick(song) },
            )
        }
    }
}

@Composable
private fun QuickPlayAlbumCard(
    album: Album,
    coverArtUrl: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.width(200.dp),
        onClick = onClick,
        shape = YoinShapeTokens.Large,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        tonalElevation = 2.dp,
    ) {
        Column {
            if (LocalInspectionMode.current) {
                androidx.compose.foundation.Image(
                    painter = painterResource(id = R.drawable.ic_launcher_foreground),
                    contentDescription = album.name,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(200.dp)
                        .clip(YoinShapeTokens.Large),
                )
            } else {
                AsyncImage(
                    model = coverArtUrl,
                    contentDescription = album.name,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(200.dp)
                        .clip(YoinShapeTokens.Large),
                )
            }
            Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
                Text(
                    text = album.name,
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                album.artist?.let { artist ->
                    Text(
                        text = artist,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

@Composable
private fun QuickPlayCard(
    song: Track,
    coverArtUrl: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier
            .width(180.dp),
        onClick = onClick,
        shape = YoinShapeTokens.Medium,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        tonalElevation = 1.dp,
    ) {
        Column {
            if (LocalInspectionMode.current) {
                androidx.compose.foundation.Image(
                    painter = painterResource(id = R.drawable.ic_launcher_foreground),
                    contentDescription = song.title,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(180.dp)
                        .clip(YoinShapeTokens.Medium),
                )
            } else {
                AsyncImage(
                    model = coverArtUrl,
                    contentDescription = song.title,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(180.dp)
                        .clip(YoinShapeTokens.Medium),
                )
            }
            Column(modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp)) {
                Text(
                    text = song.title.orEmpty(),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                song.artist?.let { artist ->
                    Text(
                        text = artist,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

@Composable
private fun AlbumsRow(
    albums: List<Album>,
    onAlbumClick: (albumId: String) -> Unit,
    buildCoverArtUrl: (String) -> String,
    modifier: Modifier = Modifier,
) {
    LazyRow(
        modifier = modifier,
        contentPadding = PaddingValues(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        itemsIndexed(
            items = albums,
            key = { _, album -> album.id.toString() },
        ) { _, album ->
            val coverUrl = coverArtUrlFor(album.coverArt, buildCoverArtUrl)
                ?: buildCoverArtUrl(album.id.rawId)

            AlbumCard(
                coverArtUrl = coverUrl,
                title = album.name,
                subtitle = album.artist.orEmpty(),
                onClick = { onAlbumClick(album.id.toString()) },
            )
        }
    }
}

@Composable
private fun ActivitiesRow(
    activities: List<PlayHistory>,
    onSongClick: (Track) -> Unit,
    buildCoverArtUrl: (String) -> String,
    modifier: Modifier = Modifier,
) {
    LazyRow(
        modifier = modifier,
        contentPadding = PaddingValues(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        itemsIndexed(
            items = activities,
            key = { _, history -> history.id },
        ) { _, history ->
            ActivityCard(
                history = history,
                coverArtUrl = history.coverArtId?.let { buildCoverArtUrl(it) },
                onClick = {
                    onSongClick(
                        Track(
                            id = MediaId(history.provider, history.songId),
                            title = history.title,
                            artist = history.artist,
                            album = history.album,
                            albumId = history.albumId.takeIf { it.isNotBlank() }?.let {
                                MediaId(history.provider, it)
                            },
                            artistId = null,
                            coverArt = history.coverArtId?.let { CoverRef.SourceRelative(it) },
                            durationSec = history.durationMs.takeIf { it > 0L }?.let { (it / 1000L).toInt() },
                            trackNumber = null,
                            year = null,
                            genre = null,
                            userRating = null,
                        ),
                    )
                },
            )
        }
    }
}

@Composable
fun ActivityCard(
    history: PlayHistory,
    coverArtUrl: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .width(150.dp)
            .clip(YoinShapeTokens.Medium)
            .clickable(onClick = onClick),
    ) {
        if (LocalInspectionMode.current) {
            androidx.compose.foundation.Image(
                painter = painterResource(id = R.drawable.ic_launcher_foreground),
                contentDescription = history.title,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .size(150.dp)
                    .clip(YoinShapeTokens.Medium),
            )
        } else {
            AsyncImage(
                model = coverArtUrl,
                contentDescription = history.title,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .size(150.dp)
                    .clip(YoinShapeTokens.Medium),
            )
        }
        Spacer(modifier = Modifier.height(6.dp))
        Text(
            text = history.title,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            text = history.artist,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            text = formatTimeAgo(history.playedAt),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
            maxLines = 1,
        )
    }
}

private fun formatTimeAgo(playedAtMillis: Long): String {
    val diff = System.currentTimeMillis() - playedAtMillis
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

private fun coverArtUrlFor(
    ref: CoverRef?,
    buildCoverArtUrl: (String) -> String,
): String? = when (ref) {
    null -> null
    is CoverRef.Url -> ref.url
    is CoverRef.SourceRelative -> buildCoverArtUrl(ref.coverArtId)
}

// Previews

@Preview(showBackground = true, backgroundColor = 0xFF1C1B1F)
@Composable
private fun HomeContentLoadingPreview() {
    YoinTheme {
        HomeContent(
            uiState = HomeUiState.Loading,
            isPlaying = false,
            playbackSignal = 0f,
            activeSongId = null,
            onNavigateToSettings = {},
            onNavigateToMemories = {},
            onAlbumClick = { _, _ -> },
            onArtistClick = {},
            onPlaylistClick = {},
            onSongClick = { _ -> },
            onRetry = {},
            onLoadMoreJumpBackIn = {},
            buildCoverArtUrl = { "" },
        )
    }
}

@Preview(showBackground = true, backgroundColor = 0xFF1C1B1F)
@Composable
private fun HomeContentErrorPreview() {
    YoinTheme {
        HomeContent(
            uiState = HomeUiState.Error("Failed to connect to server"),
            isPlaying = false,
            playbackSignal = 0f,
            activeSongId = null,
            onNavigateToSettings = {},
            onNavigateToMemories = {},
            onAlbumClick = { _, _ -> },
            onArtistClick = {},
            onPlaylistClick = {},
            onSongClick = { _ -> },
            onRetry = {},
            onLoadMoreJumpBackIn = {},
            buildCoverArtUrl = { "" },
        )
    }
}

@Preview(showBackground = true, backgroundColor = 0xFF1C1B1F)
@Composable
private fun HomeContentPreview() {
    YoinTheme {
        HomeContent(
            uiState = HomeUiState.Content(
                activities = listOf(
                    ActivityEvent(
                        id = 1,
                        entityType = ActivityEntityType.ALBUM.name,
                        actionType = ActivityActionType.PLAYED.name,
                        entityId = "a1",
                        title = "Black Holes and Revelations",
                        subtitle = "Muse",
                        coverArtId = "c1",
                        albumId = "a1",
                        songId = "s1",
                        artistId = "artist-1",
                        timestamp = System.currentTimeMillis() - 3_600_000L,
                    ),
                    ActivityEvent(
                        id = 2,
                        entityType = ActivityEntityType.ARTIST.name,
                        actionType = ActivityActionType.VISITED.name,
                        entityId = "artist-2",
                        title = "Daft Punk",
                        subtitle = "Artist",
                        coverArtId = "c2",
                        artistId = "artist-2",
                        timestamp = System.currentTimeMillis() - 86_400_000L,
                    ),
                    ActivityEvent(
                        id = 3,
                        entityType = ActivityEntityType.SONG.name,
                        actionType = ActivityActionType.PLAYED.name,
                        entityId = "s3",
                        title = "Starlight",
                        subtitle = "Muse",
                        coverArtId = "c1",
                        albumId = "a2",
                        songId = "s3",
                        artistId = "artist-1",
                        timestamp = System.currentTimeMillis() - 172_800_000L,
                    ),
                ),
                jumpBackInItems = listOf(
                    HomeJumpBackInItem.AlbumItem(
                        Album(
                            id = MediaId.subsonic("ja1"),
                            name = "Describe",
                            artist = "Hannah Jadagu",
                            artistId = null,
                            coverArt = CoverRef.SourceRelative("cover-ja1"),
                            songCount = 12,
                            durationSec = null,
                            year = null,
                            genre = null,
                        ),
                    ),
                    HomeJumpBackInItem.SongItem(
                        Track(
                            id = MediaId.subsonic("js1"),
                            title = "Little House",
                            artist = "Rachel Chinouriri",
                            album = "Little House",
                            artistId = null,
                            albumId = MediaId.subsonic("album-js1"),
                            coverArt = CoverRef.SourceRelative("cover-js1"),
                            durationSec = null,
                            trackNumber = null,
                            year = null,
                            genre = null,
                            userRating = null,
                        ),
                    ),
                    HomeJumpBackInItem.ArtistItem(
                        Artist(
                            id = MediaId.subsonic("artist-ja1"),
                            name = "Daft Punk",
                            coverArt = CoverRef.SourceRelative("artist-cover-1"),
                            albumCount = 4,
                        ),
                    ),
                ),
            ),
            isPlaying = true,
            playbackSignal = 0.35f,
            activeSongId = "js1",
            onNavigateToSettings = {},
            onNavigateToMemories = {},
            onAlbumClick = { _, _ -> },
            onArtistClick = {},
            onPlaylistClick = {},
            onSongClick = { _ -> },
            onRetry = {},
            onLoadMoreJumpBackIn = {},
            buildCoverArtUrl = { "" },
        )
    }
}

@Preview(showBackground = true, backgroundColor = 0xFF1C1B1F)
@Composable
private fun ActivityCardPreview() {
    YoinTheme {
        ActivityCard(
            history = PlayHistory(
                id = 1,
                songId = "s1",
                title = "Starlight",
                artist = "Muse",
                album = "Black Holes",
                albumId = "a1",
                coverArtId = "c1",
                playedAt = System.currentTimeMillis() - 3_600_000L,
                durationMs = 240_000L,
                completedPercent = 0.95f,
            ),
            coverArtUrl = null,
            onClick = {},
        )
    }
}
