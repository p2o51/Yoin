package com.gpo.yoin.ui.detail

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.gpo.yoin.R
import com.gpo.yoin.YoinActivityRoot
import com.gpo.yoin.YoinApplication
import com.gpo.yoin.data.model.MediaId
import com.gpo.yoin.data.repository.ActivityContext
import com.gpo.yoin.data.source.WebLinkKind
import com.gpo.yoin.enableYoinEdgeToEdge
import com.gpo.yoin.perf.YoinPerf
import com.gpo.yoin.ui.common.asString
import com.gpo.yoin.ui.experience.installCoveredWindowAnimationGate
import com.gpo.yoin.ui.nowplaying.NowPlayingAccessories
import com.gpo.yoin.ui.nowplaying.NowPlayingOverlayHost
import com.gpo.yoin.ui.nowplaying.NowPlayingViewModel
import com.gpo.yoin.ui.nowplaying.ProvideBesidePanelWindowInfo
import com.gpo.yoin.ui.nowplaying.besideNowPlayingPanel
import com.gpo.yoin.ui.nowplaying.rememberNowPlayingFrame
import com.gpo.yoin.ui.nowplaying.rememberNowPlayingPanelInset
import com.gpo.yoin.ui.nowplaying.rememberNowPlayingPanelMotion
import com.gpo.yoin.ui.settings.SettingsActivity
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
        // A detail opened from this one covers it but keeps it alive (translucent): freeze its animations.
        installCoveredWindowAnimationGate(intent.detailWindowKey())
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
                val scrapbook by viewModel.scrapbook.collectAsState()
                val neoDb by viewModel.neoDb.collectAsState()
                val isPlaying by rememberDetailIsPlaying(app.container)
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
                            shuffled = shuffle,
                        )
                    }
                }

                fun playSong(songId: String) {
                    val index = viewModel.getAlbumSongs()
                        .indexOfFirst { it.id.toString() == songId }
                        .coerceAtLeast(0)
                    playFrom(startIndex = index, shuffle = false)
                }

                // Now Playing is hosted IN THIS window: the pill opens it in
                // place and back collapses it back onto this page — no shell
                // relaunch, no home cameo, and the back stack stays truthful.
                val nowPlayingViewModel: NowPlayingViewModel = viewModel(
                    factory = NowPlayingViewModel.Factory(app.container),
                )
                // The page's one-line notices (a failed NeoDB sync) ride this window's snackbar.
                val messageContext = LocalContext.current
                LaunchedEffect(viewModel) {
                    viewModel.messages.collect { message ->
                        nowPlayingViewModel.postMessage(message.asString(messageContext))
                    }
                }
                var nowPlayingOpen by rememberSaveable { mutableStateOf(false) }

                val miniPlayerState by rememberDetailMiniPlayerState(app.container)
                val miniPlayerProgress by rememberDetailMiniPlayerProgress(app.container)
                // A Medium window opens Now Playing as a side panel: the page
                // gives up its width and reads as a handset (断点交接 §3.4).
                val nowPlayingFrame = rememberNowPlayingFrame(nowPlayingViewModel)
                val nowPlayingPanelMotion = rememberNowPlayingPanelMotion()
                val nowPlayingPanel = rememberNowPlayingPanelInset(nowPlayingFrame, nowPlayingOpen, nowPlayingPanelMotion)

                // ▾: Play next, Add to queue, Add to playlist, Open in …; Share carries the link.
                val webLink = rememberDetailWebLink(app.container, WebLinkKind.Album, albumId)
                val menu = rememberDetailMenu(
                    container = app.container,
                    link = webLink,
                    provider = MediaId.parseOrNull(albumId)?.provider,
                    tracks = { viewModel.getAlbumSongs() },
                    onMessage = nowPlayingViewModel::postMessage,
                    onAddToPlaylist = nowPlayingViewModel::requestAddTracksToPlaylist,
                )

                Box(modifier = Modifier.fillMaxSize()) {
                ProvideBesidePanelWindowInfo(nowPlayingPanel) {
                AlbumDetailScreen(
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
                    onSongClick = { songId -> playSong(songId) },
                    scrapbook = scrapbook,
                    onNoteMomentClick = { songId, positionMs ->
                        // Seek in place when that song is already current;
                        // otherwise play it and seek once it is ready.
                        val inPlace = positionMs != null && viewModel.requestNoteSeek(songId, positionMs)
                        if (!inPlace) playSong(songId)
                    },
                    onToggleStar = viewModel::toggleStar,
                    onRetry = viewModel::retry,
                    onRenameMemoryTitle = viewModel::renameMemoryTitle,
                    onRestoreMemoryTitle = viewModel::restoreMemoryTitle,
                    notedSongIds = notedSongIds,
                    currentTrackId = currentTrackId,
                    expandedSongId = expandedSongId,
                    expandedNoteBundle = expandedNoteBundle,
                    onToggleExpandedSong = viewModel::toggleExpandedSong,
                    onRatingCommit = viewModel::setUserRating,
                    onReviewDraftChange = viewModel::onReviewDraftChange,
                    neoDb = neoDb,
                    onRateSheetOpened = viewModel::onRateSheetOpened,
                    onRateSheetClosed = viewModel::onRateSheetClosed,
                    onNeoDbRetry = viewModel::retryNeoDbSync,
                    onNeoDbSignIn = { startActivity(SettingsActivity.intent(this@AlbumDetailActivity, "neodb")) },
                    onPlayAlbum = { playFrom(startIndex = 0, shuffle = false) },
                    onShufflePlay = { playFrom(startIndex = 0, shuffle = true) },
                    onShare = {
                        val content = uiState as? AlbumDetailUiState.Content
                        val title = if (content != null) {
                            context.getString(
                                R.string.detail_share_album_title,
                                content.albumName,
                                content.artistName,
                            )
                        } else {
                            context.getString(R.string.detail_share_album)
                        }
                        val text = detailShareText(title, webLink)
                        val send = Intent(Intent.ACTION_SEND).apply {
                            type = "text/plain"
                            putExtra(Intent.EXTRA_TEXT, text)
                        }
                        context.startActivity(Intent.createChooser(send, null))
                    },
                    menu = menu,
                    onOpenArtist = (uiState as? AlbumDetailUiState.Content)?.artistId?.let { artistId ->
                        {
                            YoinPerf.detailClick("artist", artistId, via = "push")
                            launchChildDetail(
                                ArtistDetailActivity.intent(this@AlbumDetailActivity, artistId),
                            )
                        }
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
                        launchChildDetail(AlbumDetailActivity.intent(this@AlbumDetailActivity, id), fromNowPlaying = true)
                    },
                    onArtistClick = { id ->
                        launchChildDetail(ArtistDetailActivity.intent(this@AlbumDetailActivity, id), fromNowPlaying = true)
                    },
                    onPlaylistClick = { id ->
                        launchChildDetail(PlaylistDetailActivity.intent(this@AlbumDetailActivity, id), fromNowPlaying = true)
                    },
                    panelMotion = nowPlayingPanelMotion,
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
        // With translucent detail windows, MainActivity stays STARTED; letting
        // an inner detail's onStop publish the tick would clear the shell pose
        // while its outer detail is still on screen.
        if (isFinishing && intent.getBooleanExtra(DETAIL_EXTRA_FROM_SHELL, false)) {
            (application as YoinApplication).container.experienceSessionStore
                .noteDetailWindowSettled()
        }
    }

    companion object {
        private const val EXTRA_ALBUM_ID = "albumId"

        fun intent(context: Context, albumId: String): Intent =
            Intent(context, AlbumDetailActivity::class.java)
                .putExtra(EXTRA_ALBUM_ID, albumId)
    }
}
