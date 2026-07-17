package com.gpo.yoin.ui.detail

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.lifecycle.Lifecycle
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import com.gpo.yoin.ui.nowplaying.NowPlayingAccessories
import com.gpo.yoin.ui.nowplaying.NowPlayingOverlayHost
import com.gpo.yoin.ui.nowplaying.NowPlayingViewModel
import androidx.compose.runtime.CompositionLocalProvider
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
            // Hide MY bar only while a back preview is in flight AND this is
            // the top (still-RESUMED) window — the revealed window beneath
            // keeps its bar as the static twin.
            val experienceSession by (application as YoinApplication)
                .container.experienceSessionStore.state.collectAsState()
            val lifecycleState by lifecycle.currentStateFlow.collectAsState()
            val barHiddenDuringBack = remember {
                derivedStateOf {
                    experienceSession.detailBackPreviewActive &&
                        lifecycleState == Lifecycle.State.RESUMED
                }
            }
            CompositionLocalProvider(LocalDetailBarHiddenDuringBack provides barHiddenDuringBack) {
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
    }

    // Predictive-back preview signal: a STOPPED activity re-starting while
    // detail chrome is up means the window ABOVE it is being scaled by a held
    // back gesture (normal foregrounding re-starts then RESUMES before any
    // frame, so the blip never renders). The top window's bar hides on it.
    override fun onRestart() {
        super.onRestart()
        val store = (application as YoinApplication).container.experienceSessionStore
        if (store.state.value.detailChromeActive) {
            store.setDetailBackPreviewActive(true)
        }
    }

    override fun onResume() {
        super.onResume()
        (application as YoinApplication).container.experienceSessionStore
            .setDetailBackPreviewActive(false)
    }

    override fun onStop() {
        super.onStop()
        val store = (application as YoinApplication).container.experienceSessionStore
        // Cancelled preview: this window re-stops without ever resuming.
        store.setDetailBackPreviewActive(false)
        // The system defers onStop past the exit animation, so this is the
        // moment this window is truly off screen — the shell's chrome restore
        // (bar reverse morph) waits for it.
        store.noteDetailWindowSettled()
    }

    companion object {
        private const val EXTRA_ALBUM_ID = "albumId"

        fun intent(context: Context, albumId: String): Intent =
            Intent(context, AlbumDetailActivity::class.java)
                .putExtra(EXTRA_ALBUM_ID, albumId)
    }
}
