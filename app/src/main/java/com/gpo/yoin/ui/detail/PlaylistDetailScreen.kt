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
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.gpo.yoin.ui.component.ExpressiveHeaderBlock
import com.gpo.yoin.ui.component.ExpressiveMediaArtwork
import com.gpo.yoin.ui.component.ExpressiveMetaPill
import com.gpo.yoin.ui.component.ExpressivePageBackground
import com.gpo.yoin.ui.component.ExpressiveSectionPanel
import com.gpo.yoin.ui.component.SongListItem
import com.gpo.yoin.ui.component.YoinLoadingIndicator
import com.gpo.yoin.ui.component.rememberExpressiveBackdropColors
import com.gpo.yoin.ui.navigation.playlistCoverSharedKey
import com.gpo.yoin.ui.theme.YoinMotion
import com.gpo.yoin.ui.theme.YoinMotionRole
import com.gpo.yoin.ui.theme.YoinShapeTokens
import com.gpo.yoin.ui.theme.YoinTheme

@OptIn(ExperimentalMaterial3Api::class, ExperimentalSharedTransitionApi::class)
@Composable
fun PlaylistDetailScreen(
    uiState: PlaylistDetailUiState,
    onBackClick: () -> Unit,
    onPlayAllClick: () -> Unit,
    onSongClick: (songId: String) -> Unit,
    onAddSongToPlaylist: (songId: String) -> Unit = {},
    onRetry: () -> Unit,
    onRename: (name: String) -> Unit = {},
    onDelete: () -> Unit = {},
    onRemoveTrack: (position: Int, trackId: String) -> Unit = { _, _ -> },
    sharedTransitionKey: String? = null,
    sharedTransitionScope: SharedTransitionScope? = null,
    animatedVisibilityScope: AnimatedVisibilityScope? = null,
    modifier: Modifier = Modifier,
) {
    // Overflow menu state + dialog states lifted here so they survive
    // child recomposition (e.g. after a rename refreshes the Content).
    var showOverflow by remember { mutableStateOf(false) }
    var showRenameDialog by remember { mutableStateOf(false) }
    var showDeleteConfirm by remember { mutableStateOf(false) }
    val content = uiState as? PlaylistDetailUiState.Content

    // See AlbumDetailScreen for why we gate on isResolvedFromPalette.
    val accentColor = (uiState as? PlaylistDetailUiState.Content)?.coverArtUrl?.let { coverArtUrl ->
        val colors = rememberExpressiveBackdropColors(
            model = coverArtUrl,
            fallbackBaseColor = MaterialTheme.colorScheme.surfaceContainer,
            fallbackAccentColor = MaterialTheme.colorScheme.secondaryContainer,
        )
        colors.accentColor.takeIf { colors.isResolvedFromPalette }
    }
    ExpressivePageBackground(
        accentColor = accentColor,
        modifier = modifier,
    ) {
        Scaffold(
            modifier = Modifier.fillMaxSize(),
            containerColor = Color.Transparent,
            contentColor = MaterialTheme.colorScheme.onSurface,
            contentWindowInsets = WindowInsets(0, 0, 0, 0),
            topBar = {
                TopAppBar(
                    title = {
                        Text(
                            text = content?.playlistName.orEmpty(),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    },
                    navigationIcon = {
                        IconButton(onClick = onBackClick) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = "Back",
                            )
                        }
                    },
                    actions = {
                        // Overflow only renders when the current profile can
                        // actually write this playlist. For Spotify followed-
                        // but-not-owned playlists, canWrite = false and the
                        // menu stays hidden entirely rather than showing
                        // disabled items.
                        if (content?.canWrite == true) {
                            Box {
                                IconButton(onClick = { showOverflow = true }) {
                                    Icon(
                                        imageVector = Icons.Filled.MoreVert,
                                        contentDescription = "More actions",
                                    )
                                }
                                DropdownMenu(
                                    expanded = showOverflow,
                                    onDismissRequest = { showOverflow = false },
                                ) {
                                    DropdownMenuItem(
                                        text = { Text("Rename") },
                                        leadingIcon = {
                                            Icon(Icons.Filled.Edit, contentDescription = null)
                                        },
                                        onClick = {
                                            showOverflow = false
                                            showRenameDialog = true
                                        },
                                    )
                                    DropdownMenuItem(
                                        text = { Text("Delete") },
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
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = Color.Transparent,
                        titleContentColor = MaterialTheme.colorScheme.onSurface,
                        navigationIconContentColor = MaterialTheme.colorScheme.onSurface,
                    ),
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
                        onPlayAllClick = onPlayAllClick,
                        onSongClick = onSongClick,
                        onAddSongToPlaylist = onAddSongToPlaylist,
                        onRemoveTrack = onRemoveTrack,
                        sharedTransitionKey = sharedTransitionKey,
                        sharedTransitionScope = sharedTransitionScope,
                        animatedVisibilityScope = animatedVisibilityScope,
                        modifier = Modifier.padding(innerPadding),
                    )
                }
            }
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
    onPlayAllClick: () -> Unit,
    onSongClick: (songId: String) -> Unit,
    onAddSongToPlaylist: (songId: String) -> Unit,
    onRemoveTrack: (position: Int, trackId: String) -> Unit,
    sharedTransitionKey: String? = null,
    sharedTransitionScope: SharedTransitionScope? = null,
    animatedVisibilityScope: AnimatedVisibilityScope? = null,
    modifier: Modifier = Modifier,
) {
    val navBottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    androidx.compose.foundation.lazy.LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            start = 16.dp,
            top = 16.dp,
            end = 16.dp,
            bottom = 24.dp + navBottom,
        ),
    ) {
        item {
            androidx.compose.foundation.layout.Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                PlaylistHeroArtwork(
                    playlistId = content.playlistId,
                    sharedTransitionKey = sharedTransitionKey,
                    coverArtUrl = content.coverArtUrl,
                    playlistName = content.playlistName,
                    sharedTransitionScope = sharedTransitionScope,
                    animatedVisibilityScope = animatedVisibilityScope,
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(1f),
                )
                androidx.compose.foundation.layout.Column(
                    modifier = Modifier.padding(top = 2.dp, bottom = 10.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    ExpressiveHeaderBlock(
                        title = content.playlistName,
                        overline = "Playlist",
                        supporting = content.comment?.takeIf { it.isNotBlank() } ?: "Hand-picked tracks ready to play through.",
                    )
                    PlaylistMetaRow(content = content)
                    if (content.songs.isNotEmpty()) {
                        Button(
                            onClick = onPlayAllClick,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Icon(
                                imageVector = Icons.Filled.PlayArrow,
                                contentDescription = null,
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Play All")
                        }
                    }
                }
            }
        }

        item {
            Text(
                text = "Tracks",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 4.dp, vertical = 10.dp),
            )
        }

        items(content.songs, key = { "${it.position}-${it.id}" }) { song ->
            SongListItem(
                title = song.title,
                artist = song.artist,
                album = song.album,
                durationSeconds = song.duration,
                coverArtUrl = song.coverArtUrl,
                onClick = { onSongClick(song.id) },
                onLongClick = { onAddSongToPlaylist(song.id) },
                trailingContent = if (content.canWrite) {
                    {
                        SongRowOverflow(
                            onRemove = { onRemoveTrack(song.position, song.id) },
                        )
                    }
                } else null,
            )
        }

        item { Spacer(modifier = Modifier.height(24.dp)) }
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
        with(sharedTransitionScope) {
            modifier
                .sharedElement(
                    sharedContentState = rememberSharedContentState(
                        key = playlistCoverSharedKey(playlistId, sharedTransitionKey),
                    ),
                    animatedVisibilityScope = animatedVisibilityScope,
                    boundsTransform = { _, _ -> artworkBoundsSpec },
                    renderInOverlayDuringTransition = false,
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
            shadowElevation = 12.dp,
            tonalElevation = 3.dp,
        )
    }
}

@Composable
private fun PlaylistMetaRow(
    content: PlaylistDetailUiState.Content,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        content.owner.takeIf { it.isNotBlank() }?.let { ExpressiveMetaPill(text = it) }
        content.songCount?.let { ExpressiveMetaPill(text = "$it tracks") }
        content.totalDuration?.takeIf { it > 0 }?.let { ExpressiveMetaPill(text = formatPlaylistDuration(it)) }
        content.isPublic?.let { ExpressiveMetaPill(text = if (it) "Public" else "Private") }
    }
}

@Composable
private fun SongRowOverflow(onRemove: () -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { expanded = true }) {
            Icon(
                imageVector = Icons.Filled.MoreVert,
                contentDescription = "Track actions",
            )
        }
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
        ) {
            DropdownMenuItem(
                text = { Text("Remove from playlist") },
                leadingIcon = {
                    Icon(Icons.Filled.Delete, contentDescription = null)
                },
                onClick = {
                    expanded = false
                    onRemove()
                },
            )
        }
    }
}

private fun formatPlaylistDuration(seconds: Int): String {
    val hours = seconds / 3600
    val minutes = (seconds % 3600) / 60
    return when {
        hours > 0 -> "${hours}h ${minutes}m"
        else -> "${minutes}m"
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
