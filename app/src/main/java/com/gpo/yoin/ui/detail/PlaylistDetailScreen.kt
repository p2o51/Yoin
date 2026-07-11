package com.gpo.yoin.ui.detail
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import com.gpo.yoin.ui.component.YoinDropdownMenu
import com.gpo.yoin.ui.component.YoinDropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.MediumFlexibleTopAppBar
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.animation.animateColorAsState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.gpo.yoin.ui.component.ExpressiveMediaArtwork
import com.gpo.yoin.ui.component.ExpressivePageBackground
import com.gpo.yoin.ui.component.ExpressiveSectionPanel
import com.gpo.yoin.ui.component.YoinLoadingIndicator
import com.gpo.yoin.ui.experience.rememberYoinHaptics
import com.gpo.yoin.ui.navigation.playlistCoverSharedKey
import com.gpo.yoin.ui.navigation.rememberActiveOnlySharedContentConfig
import com.gpo.yoin.ui.theme.YoinMotion
import com.gpo.yoin.ui.theme.YoinMotionRole
import com.gpo.yoin.ui.theme.YoinShapeTokens
import com.gpo.yoin.ui.theme.YoinTheme
import com.gpo.yoin.ui.theme.rememberCoverColorScheme

@OptIn(
    ExperimentalMaterial3Api::class,
    ExperimentalMaterial3ExpressiveApi::class,
    ExperimentalSharedTransitionApi::class,
)
@Composable
fun PlaylistDetailScreen(
    uiState: PlaylistDetailUiState,
    onBackClick: () -> Unit,
    onPlayAllClick: () -> Unit,
    onShufflePlay: () -> Unit = {},
    onSongClick: (songId: String) -> Unit,
    onRetry: () -> Unit,
    onRename: (name: String) -> Unit = {},
    onDelete: () -> Unit = {},
    sharedTransitionKey: String? = null,
    sharedTransitionScope: SharedTransitionScope? = null,
    animatedVisibilityScope: AnimatedVisibilityScope? = null,
    isPlaying: Boolean = false,
    playbackSignal: Float = 0f,
    // Extra bottom clearance for the docked mini-player, if visible.
    bottomOverlayInset: Dp = 0.dp,
    modifier: Modifier = Modifier,
) {
    val haptics = rememberYoinHaptics()
    // Overflow menu state + dialog states lifted here so they survive
    // child recomposition (e.g. after a rename refreshes the Content).

    var showOverflow by remember { mutableStateOf(false) }
    var showRenameDialog by remember { mutableStateOf(false) }
    var showDeleteConfirm by remember { mutableStateOf(false) }
    val content = uiState as? PlaylistDetailUiState.Content

    // Header title/subtitle colors seeded from the cover (same MCU path as the
    // Album & Artist pages); animated so the resolve doesn't pop on load.
    val coverScheme = rememberCoverColorScheme(content?.coverArtUrl)
    val headerScheme = coverScheme ?: MaterialTheme.colorScheme
    val titleColor by animateColorAsState(headerScheme.primary, YoinMotion.effectsSpring(), label = "playlistTitleColor")
    val accentText = headerScheme.secondary
    // Bottom-toolbar palette, identical recipe to the Album / Artist pages: the
    // Play button rides the cover-seeded primary; the toolbar bar tints halfway
    // toward the cover's secondary container.
    val toolbarTint = rememberDetailToolbarTint(headerScheme)
    val playContent = headerScheme.onPrimary

    // Medium-flexible bar: large on arrival, collapses to a small bar on scroll —
    // same height/behaviour as the Artist page's top bar.
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()

    val accentColor = rememberDetailPageAccent(content?.coverArtUrl)
    ExpressivePageBackground(
        accentColor = accentColor,
        isPlaying = isPlaying,
        playbackSignal = playbackSignal,
        modifier = modifier,
    ) {
        Scaffold(
            modifier = Modifier
                .fillMaxSize()
                .nestedScroll(scrollBehavior.nestedScrollConnection),
            containerColor = Color.Transparent,
            contentColor = MaterialTheme.colorScheme.onSurface,
            contentWindowInsets = WindowInsets(0, 0, 0, 0),
            topBar = {
                MediumFlexibleTopAppBar(
                    title = {
                        Text(
                            text = content?.playlistName.orEmpty(),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            color = titleColor,
                        )
                    },
                    subtitle = {
                        // Mirrors the Album credit ("Artist  ·  Album 2025"), but
                        // the playlist owner is parenthesised: "(gpo)  ·  Playlist".
                        Text(
                            text = buildString {
                                content?.owner?.takeIf { it.isNotBlank() }?.let { append("($it)  ·  ") }
                                append("Playlist")
                            },
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            color = accentText,
                        )
                    },
                    navigationIcon = {
                        DetailBackButton(onClick = onBackClick)
                    },
                    actions = {
                        // Overflow only renders when the current profile can
                        // actually write this playlist. For Spotify followed-
                        // but-not-owned playlists, canWrite = false and the
                        // menu stays hidden entirely rather than showing
                        // disabled items.
                        if (content?.canWrite == true) {
                            Box {
                                IconButton(
                                    onClick = {
                                        haptics.performTick()
                                        showOverflow = true
                                    },
                                ) {
                                    Icon(
                                        imageVector = Icons.Filled.MoreVert,
                                        contentDescription = "More actions",
                                    )
                                }
                                YoinDropdownMenu(
                                    expanded = showOverflow,
                                    onDismissRequest = { showOverflow = false },
                                ) {
                                    YoinDropdownMenuItem(
                                        text = "Rename",
                                        leadingIcon = {
                                            Icon(Icons.Filled.Edit, contentDescription = null)
                                        },
                                        onClick = {
                                            showOverflow = false
                                            showRenameDialog = true
                                        },
                                    )
                                    YoinDropdownMenuItem(
                                        text = "Delete",
                                        leadingIcon = {
                                            Icon(Icons.Filled.Delete, contentDescription = null)
                                        },
                                        onClick = {
                                            showOverflow = false
                                            showDeleteConfirm = true
                                        },
                                    )
                                }
                            }
                        }
                    },
                    // Transparent both ends so the bar blends with the gradient
                    // page background (no surface band, no collapse colour flash).
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = Color.Transparent,
                        scrolledContainerColor = Color.Transparent,
                        titleContentColor = titleColor,
                        navigationIconContentColor = MaterialTheme.colorScheme.onSurface,
                    ),
                    scrollBehavior = scrollBehavior,
                )
            },
        ) { innerPadding ->
            when (uiState) {
                is PlaylistDetailUiState.Loading -> {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(innerPadding)
                            .navigationBarsPadding(),
                        contentAlignment = Alignment.Center,
                    ) {
                        YoinLoadingIndicator()
                    }
                }

                is PlaylistDetailUiState.Error -> {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(innerPadding)
                            .navigationBarsPadding()
                            .padding(16.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        ExpressiveSectionPanel(
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            androidx.compose.foundation.layout.Column(
                                modifier = Modifier.padding(20.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(10.dp),
                            ) {
                                Text(
                                    text = uiState.message,
                                    style = MaterialTheme.typography.bodyLarge,
                                    color = MaterialTheme.colorScheme.error,
                                )
                                TextButton(onClick = onRetry) {
                                    Text("Retry")
                                }
                            }
                        }
                    }
                }

                is PlaylistDetailUiState.Content -> {
                    PlaylistDetailContent(
                        content = uiState,
                        onSongClick = onSongClick,
                        sharedTransitionKey = sharedTransitionKey,
                        sharedTransitionScope = sharedTransitionScope,
                        animatedVisibilityScope = animatedVisibilityScope,
                        modifier = Modifier.padding(innerPadding),
                    )
                }
            }
        }

        // Pinned bottom toolbar — shuffle sits on the outside, Play rides the
        // cover-seeded primary (same floating bar as the Album / Artist pages).
        if (content != null && content.songs.isNotEmpty()) {
            PlaylistBottomToolbar(
                playContainer = titleColor,
                playContent = playContent,
                toolbarContainer = toolbarTint,
                onPlay = onPlayAllClick,
                onShuffle = onShufflePlay,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .navigationBarsPadding()
                    // Lifts above the detail mini-player when one is docked.
                    .padding(bottom = 12.dp + bottomOverlayInset),
            )
        }
    }

    if (showRenameDialog && content != null) {
        RenamePlaylistDialog(
            initialName = content.playlistName,
            onDismiss = { showRenameDialog = false },
            onConfirm = { newName ->
                showRenameDialog = false
                onRename(newName)
            },
        )
    }

    if (showDeleteConfirm && content != null) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            title = { Text("Delete playlist?") },
            text = {
                // Spotify implements delete as unfollow-own, but the user-
                // visible effect is the same: the playlist disappears. The
                // message stays product-neutral.
                Text("\"${content.playlistName}\" will be removed from your library.")
            },
            confirmButton = {
                TextButton(onClick = {
                    haptics.performReject()
                    showDeleteConfirm = false
                    onDelete()
                }) { Text("Delete") }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirm = false }) { Text("Cancel") }
            },
        )
    }
}

@Composable
private fun RenamePlaylistDialog(
    initialName: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var name by remember { mutableStateOf(initialName) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Rename playlist") },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text("Name") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(name.trim()) },
                enabled = name.trim().isNotEmpty() && name.trim() != initialName,
            ) { Text("Rename") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}

@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
private fun PlaylistDetailContent(
    content: PlaylistDetailUiState.Content,
    onSongClick: (songId: String) -> Unit,
    sharedTransitionKey: String? = null,
    sharedTransitionScope: SharedTransitionScope? = null,
    animatedVisibilityScope: AnimatedVisibilityScope? = null,
    modifier: Modifier = Modifier,
) {
    val navBottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    // Match the Album hero cover footprint: centered square, capped at 300dp.
    val screenWidth = LocalConfiguration.current.screenWidthDp.dp
    val coverSide = minOf(screenWidth * 0.74f, 300.dp)
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(
                start = 16.dp,
                top = 16.dp,
                end = 16.dp,
                // Room for the flowing titles to clear the floating toolbar.
                bottom = 120.dp + navBottom,
            ),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        // Centered album-sized cover (name/owner credit now live in the
        // top app bar, like the Album & Artist pages).
        PlaylistHeroArtwork(
            playlistId = content.playlistId,
            sharedTransitionKey = sharedTransitionKey,
            coverArtUrl = content.coverArtUrl,
            playlistName = content.playlistName,
            sharedTransitionScope = sharedTransitionScope,
            animatedVisibilityScope = animatedVisibilityScope,
            modifier = Modifier.size(coverSide),
        )
        content.comment?.takeIf { it.isNotBlank() }?.let { description ->
            Text(
                text = description,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        if (content.songs.isNotEmpty()) {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                // "N tracks · 38m" — same label style as the Album page.
                AlbumTrackCountLabel(
                    count = content.songCount ?: content.songs.size,
                    totalDurationSeconds = content.totalDuration,
                    modifier = Modifier.fillMaxWidth(),
                )
                // Flowing track titles — each title is its own clickable link that
                // plays just that song. Intentionally NOT truncated: it flows down
                // behind the toolbar and past the bottom safe area (Album page parity).
                Text(
                    text = buildPlaylistTrackTitles(
                        songs = content.songs,
                        separatorColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                        onSongClick = onSongClick,
                    ),
                    style = MaterialTheme.typography.headlineMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    overflow = TextOverflow.Visible,
                    modifier = Modifier
                        .fillMaxWidth()
                        .wrapContentHeight(align = Alignment.Top, unbounded = true),
                )
            }
        }
    }
}

// Plain text normally (no link blue / underline — inherit the surrounding style);
// an underline appears while a title is pressed. Mirrors the Album hero titles.
private val PlaylistTitleLinkStyles = TextLinkStyles(
    style = SpanStyle(),
    pressedStyle = SpanStyle(textDecoration = TextDecoration.Underline),
)

private fun buildPlaylistTrackTitles(
    songs: List<PlaylistSong>,
    separatorColor: Color,
    onSongClick: ((String) -> Unit)?,
) = buildAnnotatedString {
    songs.forEachIndexed { index, song ->
        if (index > 0) {
            withStyle(SpanStyle(color = separatorColor)) { append("  •  ") }
        }
        if (onSongClick != null) {
            withLink(
                LinkAnnotation.Clickable(
                    tag = song.id,
                    styles = PlaylistTitleLinkStyles,
                    linkInteractionListener = { onSongClick(song.id) },
                ),
            ) { append(song.title) }
        } else {
            append(song.title)
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun PlaylistBottomToolbar(
    playContainer: Color,
    playContent: Color,
    toolbarContainer: Color,
    onPlay: () -> Unit,
    onShuffle: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val haptics = rememberYoinHaptics()
    DetailFloatingToolbar(
        toolbarContainer = toolbarContainer,
        modifier = modifier,
    ) {
        // Shuffle lives on the outside of the bar (not buried in a ▾ menu).
        IconButton(
            onClick = {
                haptics.performClick()
                onShuffle()
            },
            modifier = Modifier.size(52.dp),
        ) {
            Icon(
                imageVector = Icons.Filled.Shuffle,
                contentDescription = "Shuffle play",
                tint = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.size(24.dp),
            )
        }
        Spacer(modifier = Modifier.width(8.dp))
        Button(
            onClick = {
                haptics.performClick()
                onPlay()
            },
            colors = ButtonDefaults.buttonColors(
                containerColor = playContainer,
                contentColor = playContent,
            ),
            modifier = Modifier.height(60.dp),
            contentPadding = PaddingValues(horizontal = 26.dp),
        ) {
            Icon(
                imageVector = Icons.Filled.PlayArrow,
                contentDescription = null,
            )
            Spacer(modifier = Modifier.width(ButtonDefaults.IconSpacing))
            Text("Play", style = MaterialTheme.typography.titleMedium)
        }
    }
}

@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
private fun PlaylistHeroArtwork(
    playlistId: String,
    sharedTransitionKey: String?,
    coverArtUrl: String?,
    playlistName: String,
    sharedTransitionScope: SharedTransitionScope?,
    animatedVisibilityScope: AnimatedVisibilityScope?,
    modifier: Modifier = Modifier,
) {
    val shape = YoinShapeTokens.ExtraLarge
    val artworkBoundsSpec = YoinMotion.defaultSpatialSpec<Rect>(
        role = YoinMotionRole.Expressive,
        expressiveScheme = MaterialTheme.motionScheme,
    )
    val sharedArtworkModifier = if (
        sharedTransitionScope != null &&
        animatedVisibilityScope != null
    ) {
        val sharedContentConfig =
            rememberActiveOnlySharedContentConfig(
                animatedVisibilityScope = animatedVisibilityScope,
            )
        with(sharedTransitionScope) {
            modifier
                .sharedElement(
                    sharedContentState = rememberSharedContentState(
                        key = playlistCoverSharedKey(playlistId, sharedTransitionKey),
                        config = sharedContentConfig,
                    ),
                    animatedVisibilityScope = animatedVisibilityScope,
                    boundsTransform = { _, _ -> artworkBoundsSpec },
                    zIndexInOverlay = 1f,
                )
                .clip(shape)
        }
    } else {
        modifier
    }

    Box(modifier = sharedArtworkModifier) {
        ExpressiveMediaArtwork(
            model = coverArtUrl,
            contentDescription = playlistName,
            modifier = Modifier.fillMaxSize(),
            shape = shape,
            fallbackIcon = Icons.Filled.LibraryMusic,
            // No shadow / border — flat, exactly like the Album hero cover.
            border = null,
            shadowElevation = 0.dp,
            tonalElevation = 3.dp,
        )
    }
}

@Preview(showBackground = true, backgroundColor = 0xFF1C1B1F)
@Composable
private fun PlaylistDetailContentPreview() {
    YoinTheme {
        PlaylistDetailScreen(
            uiState = PlaylistDetailUiState.Content(
                playlistId = "playlist-preview",
                playlistName = "Late Night Rotation",
                owner = "gpo",
                comment = "Pulled from Navidrome",
                isPublic = false,
                songCount = 2,
                totalDuration = 768,
                coverArtUrl = null,
                songs = listOf(
                    PlaylistSong(
                        id = "1",
                        title = "Paranoid Android",
                        artist = "Radiohead",
                        album = "OK Computer",
                        duration = 386,
                        coverArtUrl = null,
                    ),
                    PlaylistSong(
                        id = "2",
                        title = "Comfortably Numb",
                        artist = "Pink Floyd",
                        album = "The Wall",
                        duration = 382,
                        coverArtUrl = null,
                    ),
                ),
            ),
            onBackClick = {},
            onPlayAllClick = {},
            onSongClick = {},
            onRetry = {},
        )
    }
}
