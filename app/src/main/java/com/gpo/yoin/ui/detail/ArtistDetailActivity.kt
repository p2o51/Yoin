package com.gpo.yoin.ui.detail

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.compose.LifecycleEventEffect
import com.gpo.yoin.YoinActivityRoot
import com.gpo.yoin.YoinApplication
import com.gpo.yoin.data.model.MediaId
import com.gpo.yoin.data.repository.ActivityContext
import com.gpo.yoin.enableYoinEdgeToEdge
import com.gpo.yoin.ui.nowplaying.NowPlayingAccessories
import com.gpo.yoin.ui.nowplaying.NowPlayingOverlayHost
import com.gpo.yoin.ui.nowplaying.NowPlayingViewModel
import com.gpo.yoin.ui.nowplaying.ProvideBesidePanelWindowInfo
import com.gpo.yoin.ui.nowplaying.besideNowPlayingPanel
import com.gpo.yoin.ui.nowplaying.rememberNowPlayingFrame
import com.gpo.yoin.ui.nowplaying.rememberNowPlayingPanelInset
import kotlinx.coroutines.launch

/**
 * Standalone Activity for an artist detail page. Opening an album from here
 * launches [AlbumDetailActivity] as another Activity, so each hop gets the
 * device-native cross-Activity predictive back.
 */
class ArtistDetailActivity : ComponentActivity() {
    private val detailLaunchGate = DetailActivityLaunchGate()

    override fun onResume() {
        super.onResume()
        detailLaunchGate.release()
    }

    private fun launchChildDetail(intent: Intent, fromNowPlaying: Boolean = false) {
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
        applyDetailCloseTransition()
        val artistId = intent.getStringExtra(EXTRA_ARTIST_ID)
        if (artistId.isNullOrBlank()) {
            finish()
            return
        }
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

                // A "Most Played" row plays the user's own most-played songs
                // from that row on (the list the page shows, in its order).
                fun playMostPlayed(startIndex: Int) {
                    val tracks = viewModel.getMostPlayedTracks()
                    if (tracks.isEmpty()) return
                    app.container.profileManager.activeSource.value?.let { source ->
                        app.container.playbackManager.play(
                            tracks = tracks,
                            startIndex = startIndex.coerceIn(0, tracks.lastIndex),
                            source = source,
                            activityContext = ActivityContext.None,
                        )
                    }
                }

                // Ratings given on an album page (or plays made) while this page
                // was covered show up when the user comes back to it.
                LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { viewModel.refreshPersonal() }

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
                val nowPlayingPanel = rememberNowPlayingPanelInset(nowPlayingFrame, nowPlayingOpen)

                Box(modifier = Modifier.fillMaxSize()) {
                ProvideBesidePanelWindowInfo(nowPlayingPanel) {
                ArtistDetailScreen(
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
                    morphBarOnBack = intent.getBooleanExtra(DETAIL_EXTRA_FROM_SHELL, false),
                    navSection = intent.detailOriginSection(),
                    enterBarHandoff = intent.getBooleanExtra(DETAIL_EXTRA_BAR_HANDOFF, false),
                    barExitsOnBack = intent.detailBarExitsOnBack(),
                    onAlbumClick = { albumId ->
                        launchChildDetail(
                            AlbumDetailActivity.intent(this@ArtistDetailActivity, albumId),
                        )
                    },
                    onRetry = viewModel::retry,
                    onToggleFollow = viewModel::toggleFollow,
                    onPlay = { playArtist(shuffle = false) },
                    onShuffle = { playArtist(shuffle = true) },
                    onOpenInSpotify = { openInSpotify() },
                    onMostPlayedClick = { index -> playMostPlayed(index) },
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
                        launchChildDetail(AlbumDetailActivity.intent(this@ArtistDetailActivity, id), fromNowPlaying = true)
                    },
                    onArtistClick = { id ->
                        launchChildDetail(ArtistDetailActivity.intent(this@ArtistDetailActivity, id), fromNowPlaying = true)
                    },
                    onPlaylistClick = { id ->
                        launchChildDetail(PlaylistDetailActivity.intent(this@ArtistDetailActivity, id), fromNowPlaying = true)
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
        // Only the outer detail launched from the shell owns this backstop.
        // An inner translucent detail must not clear the shell pose while its
        // outer detail is still on screen.
        if (isFinishing && intent.getBooleanExtra(DETAIL_EXTRA_FROM_SHELL, false)) {
            (application as YoinApplication).container.experienceSessionStore
                .noteDetailWindowSettled()
        }
    }

    companion object {
        private const val EXTRA_ARTIST_ID = "artistId"

        fun intent(context: Context, artistId: String): Intent =
            Intent(context, ArtistDetailActivity::class.java)
                .putExtra(EXTRA_ARTIST_ID, artistId)
    }
}
