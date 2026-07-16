package com.gpo.yoin.ui.detail

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.gpo.yoin.YoinActivityRoot
import com.gpo.yoin.YoinApplication
import com.gpo.yoin.data.model.MediaId
import com.gpo.yoin.data.repository.ActivityContext
import com.gpo.yoin.enableYoinEdgeToEdge
import kotlinx.coroutines.launch

/**
 * Standalone Activity for an artist detail page. Opening an album from here
 * launches [AlbumDetailActivity] as another Activity, so each hop gets the
 * device-native cross-Activity predictive back.
 */
class ArtistDetailActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableYoinEdgeToEdge()
        val artistId = intent.getStringExtra(EXTRA_ARTIST_ID)
        if (artistId.isNullOrBlank()) {
            finish()
            return
        }
        // Entrance-only: a recreated Activity (rotation, process restore)
        // must not replay the Button-Group → dock morph.
        val dockMorphSource = if (savedInstanceState == null) readDockMorphHandoff(intent) else null
        setContent {
            YoinActivityRoot {
                val context = LocalContext.current
                val app = context.applicationContext as YoinApplication
                val scope = rememberCoroutineScope()
                val viewModel: ArtistDetailViewModel = viewModel(
                    factory = ArtistDetailViewModel.Factory(artistId, app.container),
                )
                val uiState by viewModel.uiState.collectAsState()
                val playbackState by app.container.playbackManager.playbackState.collectAsState()
                val playbackSignal by app.container.audioVisualizerManager.playbackSignal.collectAsState()

                fun playArtist(shuffle: Boolean) {
                    scope.launch {
                        val tracks = viewModel.getAllTracks()
                        if (tracks.isEmpty()) return@launch
                        val ordered = if (shuffle) tracks.shuffled() else tracks
                        app.container.profileManager.activeSource.value?.let { source ->
                            app.container.playbackManager.play(
                                tracks = ordered,
                                startIndex = 0,
                                source = source,
                                activityContext = ActivityContext.None,
                            )
                        }
                    }
                }

                fun openInSpotify() {
                    // Saved/liked tracks aren't mirrored in-app — bounce to the
                    // Spotify app (or web) on the artist, where they live.
                    val rawId = MediaId.parseOrNull(artistId)?.rawId ?: return
                    val intent = Intent(
                        Intent.ACTION_VIEW,
                        Uri.parse("https://open.spotify.com/artist/$rawId"),
                    )
                    runCatching { context.startActivity(intent) }
                }

                fun playTopTracks(startIndex: Int) {
                    scope.launch {
                        // Awaits the in-flight top-tracks load, so an early tap still
                        // plays Popular instead of dumping the whole discography.
                        val tracks = viewModel.getTopTracks()
                        if (tracks.isEmpty()) {
                            // Genuinely none (Subsonic / artist has none) → discography.
                            playArtist(shuffle = false)
                            return@launch
                        }
                        app.container.profileManager.activeSource.value?.let { source ->
                            app.container.playbackManager.play(
                                tracks = tracks,
                                startIndex = startIndex,
                                source = source,
                                activityContext = ActivityContext.None,
                            )
                        }
                    }
                }

                val miniPlayerState by rememberDetailMiniPlayerState(app.container)
                val miniPlayerProgress = rememberDetailMiniPlayerProgress(app.container)
                val dockMorph = rememberDetailDockMorph(
                    source = dockMorphSource,
                    coverArtUrl = miniPlayerState?.coverArtUrl,
                )
                val dockBloom = rememberDockBloom(miniPlayerState?.coverArtUrl)

                Box(modifier = Modifier.fillMaxSize().dockBloomOverlay(dockBloom)) {
                ArtistDetailScreen(
                    uiState = uiState,
                    onBackClick = { finish() },
                    onAlbumClick = { albumId ->
                        context.startActivity(AlbumDetailActivity.intent(context, albumId))
                    },
                    onRetry = viewModel::retry,
                    onToggleFollow = viewModel::toggleFollow,
                    onPlay = { playTopTracks(0) },
                    onShuffle = { playArtist(shuffle = true) },
                    onOpenInSpotify = { openInSpotify() },
                    onTopTrackClick = { index -> playTopTracks(index) },
                    onShare = {
                        val text = (uiState as? ArtistDetailUiState.Content)?.artistName
                            ?: "Check out this artist"
                        val send = Intent(Intent.ACTION_SEND).apply {
                            type = "text/plain"
                            putExtra(Intent.EXTRA_TEXT, text)
                        }
                        context.startActivity(Intent.createChooser(send, null))
                    },
                    isPlaying = playbackState.isPlaying,
                    playbackSignal = if (playbackState.isPlaying) playbackSignal else 0f,
                    miniPlayer = {
                        DetailMiniPlayer(
                            state = miniPlayerState,
                            progress = { miniPlayerProgress.value },
                            onOpenNowPlaying = {
                                scope.launch {
                                    dockBloom.bloomIntoNowPlaying(context, app.container)
                                }
                            },
                            bloom = dockBloom,
                            dockMorph = dockMorph,
                        )
                    },
                    dockMorph = dockMorph,
                    modifier = Modifier.fillMaxSize(),
                )

                }
            }
        }
    }

    override fun onStop() {
        super.onStop()
        // The system defers onStop past the exit animation, so this is the
        // moment this window is truly off screen — the shell's pending NP
        // expansion (dock tap) waits for it before playing the bar→NP rise.
        (application as YoinApplication).container.experienceSessionStore
            .noteDetailWindowSettled()
    }

    companion object {
        private const val EXTRA_ARTIST_ID = "artistId"

        fun intent(context: Context, artistId: String): Intent =
            Intent(context, ArtistDetailActivity::class.java)
                .putExtra(EXTRA_ARTIST_ID, artistId)
    }
}
