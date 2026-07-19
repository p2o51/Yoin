package com.gpo.yoin.ui.detail

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.collectAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import com.gpo.yoin.ui.nowplaying.NowPlayingAccessories
import com.gpo.yoin.ui.nowplaying.NowPlayingOverlayHost
import com.gpo.yoin.ui.nowplaying.NowPlayingViewModel
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.viewmodel.compose.viewModel
import com.gpo.yoin.YoinActivityRoot
import com.gpo.yoin.YoinApplication
import com.gpo.yoin.data.repository.ActivityContext
import com.gpo.yoin.enableYoinEdgeToEdge
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/**
 * Standalone Activity for an album detail page. Rendered as its own Activity
 * (not a NavDisplay route) so back navigation plays the device-native
 * cross-Activity predictive back animation — we register NO consuming back
 * callback and never override the CLOSE transition, so the system draws it.
 * (The shell opens us with a delayed fade while its bar morphs nav→split;
 * that only styles the OPEN — see DetailBottomBar / launchDetailFromShell.)
 */
class AlbumDetailActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableYoinEdgeToEdge()
        applyDetailCloseTransition()
        val albumId = intent.getStringExtra(EXTRA_ALBUM_ID)
        if (albumId.isNullOrBlank()) {
            finish()
            return
        }
        setContent {
            YoinActivityRoot {
                val context = LocalContext.current
                val app = context.applicationContext as YoinApplication
                val viewModel: AlbumDetailViewModel = viewModel(
                    factory = AlbumDetailViewModel.Factory(albumId, app.container),
                )
                val uiState by viewModel.uiState.collectAsState()
                val notedSongIds by viewModel.notedSongIds.collectAsState()
                val expandedSongId by viewModel.expandedSongId.collectAsState()
                val expandedNoteBundle by viewModel.expandedNoteBundle.collectAsState()
                val playbackState by app.container.playbackManager.playbackState.collectAsState()
                val playbackSignal by app.container.audioVisualizerManager.playbackSignal.collectAsState()
                // Narrow id-only projection for the track list's now-playing
                // indicator, deduped so per-tick position/buffer updates never
                // reach it (same invariant as the DetailMiniPlayer projections).
                // Seeded from the live value so an already-playing track is
                // marked on the window's first frame.
                val currentTrackIdSeed = remember(app) {
                    app.container.playbackManager.playbackState.value.currentTrack?.id?.toString()
                }
                val currentTrackId by remember(app) {
                    app.container.playbackManager.playbackState
                        .map { state -> state.currentTrack?.id?.toString() }
                        .distinctUntilChanged()
                }.collectAsState(initial = currentTrackIdSeed)

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

                // Now Playing is hosted IN THIS window: the pill opens it in
                // place and back collapses it back onto this page — no shell
                // relaunch, no home cameo, and the back stack stays truthful.
                val nowPlayingViewModel: NowPlayingViewModel = viewModel(
                    factory = NowPlayingViewModel.Factory(app.container),
                )
                var nowPlayingOpen by rememberSaveable { mutableStateOf(false) }
                val miniPlayerState by rememberDetailMiniPlayerState(app.container)
                val miniPlayerProgress by rememberDetailMiniPlayerProgress(app.container)

                Box(modifier = Modifier.fillMaxSize()) {
                AlbumDetailScreen(
                    uiState = uiState,
                    onBackClick = {
                        // Pre-morph the covered shell bar to nav chrome so the reveal
                        // after the dissolve matches the scrubbed detail bar.
                        (application as YoinApplication).container.experienceSessionStore
                            .setDetailChromeActive(false)
                        finish()
                    },
                    morphBarOnBack = intent.getBooleanExtra(DETAIL_EXTRA_FROM_SHELL, false),
                    navSection = intent.detailOriginSection(),
                    enterBarHandoff = intent.getBooleanExtra(DETAIL_EXTRA_BAR_HANDOFF, false),
                    onSongClick = { songId ->
                        val index = viewModel.getAlbumSongs()
                            .indexOfFirst { it.id.toString() == songId }
                            .coerceAtLeast(0)
                        playFrom(startIndex = index, shuffle = false)
                    },
                    onToggleStar = viewModel::toggleStar,
                    onRetry = viewModel::retry,
                    notedSongIds = notedSongIds,
                    currentTrackId = currentTrackId,
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
                    onOpenNowPlaying = { nowPlayingOpen = true },
                    nowPlayingOpen = nowPlayingOpen,
                    miniPlayerState = miniPlayerState,
                    playbackProgress = miniPlayerProgress,
                    modifier = Modifier.fillMaxSize(),
                )

                NowPlayingOverlayHost(
                    viewModel = nowPlayingViewModel,
                    container = app.container,
                    expanded = nowPlayingOpen,
                    onExpandedChange = { nowPlayingOpen = it },
                    onAlbumClick = { id ->
                        context.startActivity(AlbumDetailActivity.intent(context, id))
                    },
                    onArtistClick = { id ->
                        context.startActivity(ArtistDetailActivity.intent(context, id))
                    },
                    onPlaylistClick = { id ->
                        context.startActivity(PlaylistDetailActivity.intent(context, id))
                    },
                )
                NowPlayingAccessories(
                    viewModel = nowPlayingViewModel,
                    container = app.container,
                )
                }
            }
        }
    }

    override fun onStop() {
        super.onStop()
        // The system defers onStop past the exit animation, so this is the
        // moment this window is truly off screen — the shell's chrome restore
        // (bar reverse morph) waits for it.
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
