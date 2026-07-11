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
import com.gpo.yoin.data.repository.ActivityContext
import com.gpo.yoin.ui.navigation.trackCoverArtId

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
                val uiState by viewModel.uiState.collectAsState()
                val snackbarHostState = remember { SnackbarHostState() }
                val playbackState by app.container.playbackManager.playbackState.collectAsState()
                val playbackSignal by app.container.audioVisualizerManager.playbackSignal.collectAsState()

                // Rename/delete/remove outcomes surface as snackbars.
                LaunchedEffect(viewModel) {
                    viewModel.messages.collect { message ->
                        snackbarHostState.showSnackbar(message, duration = SnackbarDuration.Short)
                    }
                }
                // Leave on successful delete.
                LaunchedEffect(viewModel) {
                    viewModel.deleted.collect { finish() }
                }

                // Shared play path: optional shuffle, with a stable playlist
                // ActivityContext. The cover is the playlist's OWN art —
                // a track cover only when the playlist has none — so the Home
                // activity card wears the playlist's face, not the first song's.
                fun playFrom(startIndex: Int, shuffle: Boolean) {
                    val ordered = viewModel.getPlaylistSongs()
                    if (ordered.isEmpty()) return
                    val tracks = if (shuffle) ordered.shuffled() else ordered
                    val activityContext = (uiState as? PlaylistDetailUiState.Content)?.let { content ->
                        ActivityContext.Playlist(
                            playlistId = playlistId,
                            playlistName = content.playlistName,
                            owner = content.owner.takeIf { it.isNotBlank() },
                            coverArtId = viewModel.getPlaylistCoverArtKey()
                                ?: ordered.firstNotNullOfOrNull(::trackCoverArtId),
                        )
                    } ?: ActivityContext.None
                    app.container.profileManager.activeSource.value?.let { source ->
                        app.container.playbackManager.play(
                            tracks = tracks,
                            startIndex = startIndex.coerceIn(0, tracks.lastIndex),
                            source = source,
                            activityContext = activityContext,
                        )
                    }
                }

                Box(modifier = Modifier.fillMaxSize()) {
                    PlaylistDetailScreen(
                        uiState = uiState,
                        onBackClick = { finish() },
                        onPlayAllClick = { playFrom(startIndex = 0, shuffle = false) },
                        onShufflePlay = { playFrom(startIndex = 0, shuffle = true) },
                        onSongClick = { songId ->
                            val index = viewModel.getPlaylistSongs()
                                .indexOfFirst { it.id.toString() == songId }
                                .coerceAtLeast(0)
                            playFrom(startIndex = index, shuffle = false)
                        },
                        onRetry = viewModel::retry,
                        onRename = viewModel::rename,
                        onDelete = viewModel::delete,
                        isPlaying = playbackState.isPlaying,
                        playbackSignal = if (playbackState.isPlaying) playbackSignal else 0f,
                        sharedTransitionKey = null,
                        sharedTransitionScope = null,
                        animatedVisibilityScope = null,
                        modifier = Modifier.fillMaxSize(),
                    )

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
