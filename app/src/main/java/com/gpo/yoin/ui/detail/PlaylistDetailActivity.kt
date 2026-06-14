package com.gpo.yoin.ui.detail

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.gpo.yoin.YoinActivityRoot
import com.gpo.yoin.YoinApplication
import com.gpo.yoin.enableYoinEdgeToEdge
import com.gpo.yoin.data.model.MediaId
import com.gpo.yoin.data.repository.ActivityContext
import com.gpo.yoin.data.source.Capability
import com.gpo.yoin.ui.component.AddToPlaylistSheet
import com.gpo.yoin.ui.navigation.trackCoverArtId
import com.gpo.yoin.ui.nowplaying.NowPlayingViewModel

/**
 * Standalone Activity for a playlist detail page. Unlike the shell-hosted
 * version, it owns its OWN [NowPlayingViewModel] + AddToPlaylist sheet +
 * snackbar (the shell's instances live in another window now). Delete leaves
 * via finish() instead of a back-stack pop.
 */
class PlaylistDetailActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableYoinEdgeToEdge()
        val playlistId = intent.getStringExtra(EXTRA_PLAYLIST_ID)
        if (playlistId.isNullOrBlank()) {
            finish()
            return
        }
        setContent {
            YoinActivityRoot {
                val context = LocalContext.current
                val app = context.applicationContext as YoinApplication
                val viewModel: PlaylistDetailViewModel = viewModel(
                    factory = PlaylistDetailViewModel.Factory(playlistId, app.container),
                )
                // Own NowPlayingViewModel just for the AddToPlaylist affordance.
                val nowPlayingViewModel: NowPlayingViewModel = viewModel(
                    factory = NowPlayingViewModel.Factory(app.container),
                )
                val uiState by viewModel.uiState.collectAsState()
                val notedSongIds by viewModel.notedSongIds.collectAsState()
                val snackbarHostState = remember { SnackbarHostState() }

                // Rename/delete/remove outcomes + AddToPlaylist messages.
                LaunchedEffect(viewModel) {
                    viewModel.messages.collect { message ->
                        snackbarHostState.showSnackbar(message, duration = SnackbarDuration.Short)
                    }
                }
                LaunchedEffect(nowPlayingViewModel) {
                    nowPlayingViewModel.addToPlaylistMessages.collect { message ->
                        snackbarHostState.showSnackbar(message, duration = SnackbarDuration.Short)
                    }
                }
                // Leave on successful delete.
                LaunchedEffect(viewModel) {
                    viewModel.deleted.collect { finish() }
                }

                Box(modifier = Modifier.fillMaxSize()) {
                    PlaylistDetailScreen(
                        uiState = uiState,
                        onBackClick = { finish() },
                        onPlayAllClick = {
                            val songs = viewModel.getPlaylistSongs()
                            if (songs.isNotEmpty()) {
                                val activityContext = (uiState as? PlaylistDetailUiState.Content)?.let { content ->
                                    ActivityContext.Playlist(
                                        playlistId = playlistId,
                                        playlistName = content.playlistName,
                                        owner = content.owner.takeIf { it.isNotBlank() },
                                        coverArtId = songs.firstNotNullOfOrNull(::trackCoverArtId),
                                    )
                                } ?: ActivityContext.None
                                app.container.profileManager.activeSource.value?.let { source ->
                                    app.container.playbackManager.play(
                                        tracks = songs,
                                        startIndex = 0,
                                        source = source,
                                        activityContext = activityContext,
                                    )
                                }
                            }
                        },
                        onSongClick = { songId ->
                            val songs = viewModel.getPlaylistSongs()
                            val index = songs.indexOfFirst { it.id.toString() == songId }
                                .coerceAtLeast(0)
                            val activityContext = (uiState as? PlaylistDetailUiState.Content)?.let { content ->
                                ActivityContext.Playlist(
                                    playlistId = playlistId,
                                    playlistName = content.playlistName,
                                    owner = content.owner.takeIf { it.isNotBlank() },
                                    coverArtId = songs.firstNotNullOfOrNull(::trackCoverArtId),
                                )
                            } ?: ActivityContext.None
                            app.container.profileManager.activeSource.value?.let { source ->
                                app.container.playbackManager.play(
                                    tracks = songs,
                                    startIndex = index,
                                    source = source,
                                    activityContext = activityContext,
                                )
                            }
                        },
                        onRetry = viewModel::retry,
                        onAddSongToPlaylist = { songId ->
                            nowPlayingViewModel.requestAddTracksToPlaylist(
                                listOf(MediaId.parse(songId)),
                            )
                        },
                        onRename = viewModel::rename,
                        onDelete = viewModel::delete,
                        onRemoveTrack = viewModel::removeTrack,
                        notedSongIds = notedSongIds,
                        sharedTransitionKey = null,
                        sharedTransitionScope = null,
                        animatedVisibilityScope = null,
                        modifier = Modifier.fillMaxSize(),
                    )

                    val addTargets by nowPlayingViewModel.addToPlaylistTarget.collectAsState()
                    if (addTargets != null) {
                        val writablePlaylists by nowPlayingViewModel.writablePlaylists.collectAsState()
                        val canCreate = Capability.PLAYLISTS_WRITE in
                            app.container.repository.currentCapabilities()
                        AddToPlaylistSheet(
                            writablePlaylists = writablePlaylists,
                            onCreateAndAdd = nowPlayingViewModel::createPlaylistAndAddTargets
                                .takeIf { canCreate },
                            onAddToExisting = nowPlayingViewModel::addTargetsToExistingPlaylist,
                            onDismiss = nowPlayingViewModel::dismissAddToPlaylistSheet,
                        )
                    }

                    SnackbarHost(
                        hostState = snackbarHostState,
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .padding(bottom = 24.dp, start = 12.dp, end = 12.dp),
                    ) { data ->
                        Snackbar(snackbarData = data)
                    }
                }
            }
        }
    }

    companion object {
        private const val EXTRA_PLAYLIST_ID = "playlistId"

        fun intent(context: Context, playlistId: String): Intent =
            Intent(context, PlaylistDetailActivity::class.java)
                .putExtra(EXTRA_PLAYLIST_ID, playlistId)
    }
}
