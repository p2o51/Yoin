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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.gpo.yoin.R
import com.gpo.yoin.YoinActivityRoot
import com.gpo.yoin.YoinApplication
import com.gpo.yoin.data.repository.ActivityContext
import com.gpo.yoin.enableYoinEdgeToEdge
import com.gpo.yoin.ui.common.asString
import com.gpo.yoin.ui.experience.installCoveredWindowAnimationGate
import com.gpo.yoin.ui.navigation.trackCoverArtId
import com.gpo.yoin.ui.nowplaying.NowPlayingAccessories
import com.gpo.yoin.ui.nowplaying.NowPlayingOverlayHost
import com.gpo.yoin.ui.nowplaying.NowPlayingViewModel
import com.gpo.yoin.ui.nowplaying.ProvideBesidePanelWindowInfo
import com.gpo.yoin.ui.nowplaying.besideNowPlayingPanel
import com.gpo.yoin.ui.nowplaying.rememberNowPlayingFrame
import com.gpo.yoin.ui.nowplaying.rememberNowPlayingPanelInset
import com.gpo.yoin.ui.nowplaying.rememberNowPlayingPanelMotion

/**
 * Standalone Activity for a playlist detail page. Unlike the shell-hosted
 * version, it owns its OWN [NowPlayingViewModel] + AddToPlaylist sheet +
 * snackbar (the shell's instances live in another window now). Delete leaves
 * via finish() instead of a back-stack pop.
 */
class PlaylistDetailActivity : ComponentActivity() {
    private val detailLaunchGate = DetailActivityLaunchGate()

    override fun onResume() {
        super.onResume()
        detailLaunchGate.release()
    }

    private fun launchChildDetail(intent: Intent, fromNowPlaying: Boolean = false) {
        // The page's load starts at the tap, ahead of this gate (DetailPrefetch.kt).
        (application as YoinApplication).container.repository.prefetchDetail(intent)
        if (!detailLaunchGate.tryAcquire(lifecycle.currentState == Lifecycle.State.RESUMED)) return
        try {
            launchDetailFromDetail(this, intent, fromNowPlaying)
        } catch (error: RuntimeException) {
            detailLaunchGate.release()
            throw error
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableYoinEdgeToEdge()
        // A detail opened from this one covers it but keeps it alive (translucent): freeze its animations.
        installCoveredWindowAnimationGate(intent.detailWindowKey())
        applyDetailCloseTransition()
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
                val isPlaying by rememberDetailIsPlaying(app.container)
                val playbackSignal by app.container.audioVisualizerManager.playbackSignal.collectAsState()

                // Rename/delete/remove outcomes surface as snackbars.
                LaunchedEffect(viewModel) {
                    viewModel.messages.collect { message ->
                        snackbarHostState.showSnackbar(message.asString(context), duration = SnackbarDuration.Short)
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
                            shuffled = shuffle,
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
                // A Medium window opens Now Playing as a side panel: the page
                // gives up its width and reads as a handset (断点交接 §3.4).
                val nowPlayingFrame = rememberNowPlayingFrame(nowPlayingViewModel)
                val nowPlayingPanelMotion = rememberNowPlayingPanelMotion()
                val nowPlayingPanel = rememberNowPlayingPanelInset(nowPlayingFrame, nowPlayingOpen, nowPlayingPanelMotion)

                Box(modifier = Modifier.fillMaxSize()) {
                    ProvideBesidePanelWindowInfo(nowPlayingPanel) {
                    PlaylistDetailScreen(
                        uiState = uiState,
                        // Toolbar arrow routes through the dispatcher so it plays the
                        // SAME commit choreography as a system back (card collapse +
                        // bar scrub + content fade in DetailPredictiveBackCollapse's
                        // no-gesture path) — one back language per page class.
                        onBackClick = { onBackPressedDispatcher.onBackPressed() },
                        onLeavePage = {
                            // Pre-morph the covered shell bar to nav chrome so the reveal
                            // after the dissolve matches the scrubbed detail bar.
                            if (intent.getBooleanExtra(DETAIL_EXTRA_FROM_SHELL, false)) {
                                (application as YoinApplication).container.experienceSessionStore
                                    .setDetailChromeActive(false)
                            }
                            finish()
                        },
                        morphBarOnBack = intent.getBooleanExtra(DETAIL_EXTRA_FROM_SHELL, false) ||
                        intent.getBooleanExtra(DETAIL_EXTRA_BAR_MORPH, false),
                    bridgeBackToShell = intent.getBooleanExtra(DETAIL_EXTRA_FROM_SHELL, false),
                        navSection = intent.detailOriginSection(),
                        enterBarHandoff = intent.getBooleanExtra(DETAIL_EXTRA_BAR_HANDOFF, false),
                        barExitsOnBack = intent.detailBarExitsOnBack(),
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
                        onShare = {
                            val text = (uiState as? PlaylistDetailUiState.Content)
                                ?.playlistName
                                ?: context.getString(R.string.detail_share_playlist)
                            val send = Intent(Intent.ACTION_SEND).apply {
                                type = "text/plain"
                                putExtra(Intent.EXTRA_TEXT, text)
                            }
                            context.startActivity(Intent.createChooser(send, null))
                        },
                        isPlaying = isPlaying,
                        playbackSignal = if (isPlaying) playbackSignal else 0f,
                        onOpenNowPlaying = { nowPlayingOpen = true },
                        nowPlayingOpen = nowPlayingOpen,

                        miniPlayerState = miniPlayerState,
                        playbackProgress = miniPlayerProgress,
                        modifier = Modifier
                            .fillMaxSize()
                            .besideNowPlayingPanel(nowPlayingPanel),
                    )
                    }


                NowPlayingOverlayHost(

                    viewModel = nowPlayingViewModel,
                    container = app.container,
                    expanded = nowPlayingOpen,
                    onExpandedChange = { nowPlayingOpen = it },
                    onAlbumClick = { id ->
                        launchChildDetail(AlbumDetailActivity.intent(this@PlaylistDetailActivity, id), fromNowPlaying = true)
                    },
                    onArtistClick = { id ->
                        launchChildDetail(ArtistDetailActivity.intent(this@PlaylistDetailActivity, id), fromNowPlaying = true)
                    },
                    onPlaylistClick = { id ->
                        launchChildDetail(PlaylistDetailActivity.intent(this@PlaylistDetailActivity, id), fromNowPlaying = true)
                    },
                    panelMotion = nowPlayingPanelMotion,
                )
                NowPlayingAccessories(
                    viewModel = nowPlayingViewModel,
                    container = app.container,
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

    override fun onStop() {
        super.onStop()
        // Only the outer detail launched from the shell owns this backstop.
        // An inner translucent detail must not clear the shell pose while its
        // outer detail is still on screen.
        if (isFinishing && intent.getBooleanExtra(DETAIL_EXTRA_FROM_SHELL, false)) {
            (application as YoinApplication).container.experienceSessionStore
                .noteDetailWindowSettled()
        }
    }

    companion object {
        internal const val EXTRA_PLAYLIST_ID = "playlistId"

        fun intent(context: Context, playlistId: String): Intent =
            Intent(context, PlaylistDetailActivity::class.java)
                .putExtra(EXTRA_PLAYLIST_ID, playlistId)
    }
}
