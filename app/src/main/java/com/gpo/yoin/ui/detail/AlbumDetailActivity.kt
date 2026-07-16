package com.gpo.yoin.ui.detail

import android.content.Context
import android.content.Intent
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
import kotlinx.coroutines.launch
import com.gpo.yoin.YoinApplication
import com.gpo.yoin.data.repository.ActivityContext
import com.gpo.yoin.enableYoinEdgeToEdge

/**
 * Standalone Activity for an album detail page. Rendered as its own Activity
 * (not a NavDisplay route) so back navigation plays the device-native
 * cross-Activity predictive back animation — we register NO consuming back
 * callback and never override the CLOSE transition, so the system draws it.
 * (The shell may open us with a custom fade + Button-Group → dock morph;
 * that only styles the OPEN — see DetailDockMorph.)
 */
class AlbumDetailActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableYoinEdgeToEdge()
        val albumId = intent.getStringExtra(EXTRA_ALBUM_ID)
        if (albumId.isNullOrBlank()) {
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
                val viewModel: AlbumDetailViewModel = viewModel(
                    factory = AlbumDetailViewModel.Factory(albumId, app.container),
                )
                val uiState by viewModel.uiState.collectAsState()
                val notedSongIds by viewModel.notedSongIds.collectAsState()
                val expandedSongId by viewModel.expandedSongId.collectAsState()
                val expandedNoteBundle by viewModel.expandedNoteBundle.collectAsState()
                val playbackState by app.container.playbackManager.playbackState.collectAsState()
                val playbackSignal by app.container.audioVisualizerManager.playbackSignal.collectAsState()

                fun playFrom(startIndex: Int, shuffle: Boolean) {
                    val ordered = viewModel.getAlbumSongs()
                    if (ordered.isEmpty()) return
                    val tracks = if (shuffle) ordered.shuffled() else ordered
                    val activityContext = (uiState as? AlbumDetailUiState.Content)?.let { content ->
                        ActivityContext.Album(
                            albumId = content.albumId,
                            albumName = content.albumName,
                            artistName = content.artistName,
                            artistId = content.artistId,
                            coverArtId = content.coverArtId,
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

                val miniPlayerState by rememberDetailMiniPlayerState(app.container)
                val miniPlayerProgress = rememberDetailMiniPlayerProgress(app.container)
                val dockMorph = rememberDetailDockMorph(
                    source = dockMorphSource,
                    coverArtUrl = miniPlayerState?.coverArtUrl,
                )
                val dockBloom = rememberDockBloom(miniPlayerState?.coverArtUrl)

                Box(modifier = Modifier.fillMaxSize().dockBloomOverlay(dockBloom)) {
                AlbumDetailScreen(
                    uiState = uiState,
                    onBackClick = { finish() },
                    onSongClick = { songId ->
                        val index = viewModel.getAlbumSongs()
                            .indexOfFirst { it.id.toString() == songId }
                            .coerceAtLeast(0)
                        playFrom(startIndex = index, shuffle = false)
                    },
                    onToggleStar = viewModel::toggleStar,
                    onRetry = viewModel::retry,
                    notedSongIds = notedSongIds,
                    expandedSongId = expandedSongId,
                    expandedNoteBundle = expandedNoteBundle,
                    onToggleExpandedSong = viewModel::toggleExpandedSong,
                    onRatingCommit = viewModel::setUserRating,
                    onReviewDraftChange = viewModel::onReviewDraftChange,
                    onSaveReview = viewModel::saveUserReview,
                    onPlayAlbum = { playFrom(startIndex = 0, shuffle = false) },
                    onShufflePlay = { playFrom(startIndex = 0, shuffle = true) },
                    onShare = {
                        val text = (uiState as? AlbumDetailUiState.Content)
                            ?.let { "${it.albumName} – ${it.artistName}" }
                            ?: "Check out this album"
                        val send = Intent(Intent.ACTION_SEND).apply {
                            type = "text/plain"
                            putExtra(Intent.EXTRA_TEXT, text)
                        }
                        context.startActivity(Intent.createChooser(send, null))
                    },
                    onOpenArtist = (uiState as? AlbumDetailUiState.Content)?.artistId?.let { artistId ->
                        { context.startActivity(ArtistDetailActivity.intent(context, artistId)) }
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
        private const val EXTRA_ALBUM_ID = "albumId"

        fun intent(context: Context, albumId: String): Intent =
            Intent(context, AlbumDetailActivity::class.java)
                .putExtra(EXTRA_ALBUM_ID, albumId)
    }
}
