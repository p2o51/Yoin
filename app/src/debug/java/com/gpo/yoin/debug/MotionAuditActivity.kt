package com.gpo.yoin.debug

import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.fadeOut
import androidx.compose.animation.fadeIn
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionLayout
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.gpo.yoin.R
import com.gpo.yoin.YoinActivityRoot
import com.gpo.yoin.YoinApplication
import com.gpo.yoin.data.model.CoverRef
import com.gpo.yoin.data.model.MediaId
import com.gpo.yoin.data.model.Track
import com.gpo.yoin.enableYoinEdgeToEdge
import com.gpo.yoin.player.ConnectionPhase
import com.gpo.yoin.player.PlaybackManager
import com.gpo.yoin.player.PlaybackState
import com.gpo.yoin.ui.component.YoinButtonGroup
import com.gpo.yoin.ui.detail.AlbumDetailActivity
import com.gpo.yoin.ui.detail.ArtistDetailActivity
import com.gpo.yoin.ui.detail.DetailActivityLaunchGate
import com.gpo.yoin.ui.detail.PlaylistDetailActivity
import com.gpo.yoin.ui.detail.launchDetailFromShell
import com.gpo.yoin.ui.navigation.YoinSection
import com.gpo.yoin.ui.navigation.back.OverlayChromeVisibility
import com.gpo.yoin.ui.nowplaying.NowPlayingOverlayHost
import com.gpo.yoin.ui.nowplaying.NowPlayingUiState
import com.gpo.yoin.ui.nowplaying.NowPlayingViewModel
import com.gpo.yoin.ui.theme.YoinMotion
import com.gpo.yoin.ui.theme.YoinMotionRole
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

/** Real player/Activity choreography with a silent, in-memory fixture; no account required. */
@OptIn(ExperimentalSharedTransitionApi::class)
class MotionAuditActivity : ComponentActivity() {
    private val gate = DetailActivityLaunchGate()

    override fun onResume() {
        super.onResume()
        gate.release()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableYoinEdgeToEdge()
        val container = (application as YoinApplication).container
        // Debug-only seam: do not add a fake playback API to the shipped player.
        val track = Track(
            id = MediaId.subsonic("motion-audit-track"),
            title = "Motion study", artist = "Yoin", artistId = MediaId.subsonic("motion-artist"),
            album = "Return to the player", albumId = MediaId.subsonic("motion-album"),
            coverArt = CoverRef.Url("android.resource://$packageName/${R.drawable.ic_yoin_launcher_foreground}"), durationSec = 240, trackNumber = 1, year = 2026,
            genre = null, userRating = null,
        )
        val field = PlaybackManager::class.java.getDeclaredField("_playbackState").apply { isAccessible = true }
        @Suppress("UNCHECKED_CAST")
        val playback = field.get(container.playbackManager) as MutableStateFlow<PlaybackState>
        playback.value = PlaybackState(
            currentTrack = track, queue = listOf(track), currentIndex = 0,
            duration = 240_000, position = 80_000, connectionPhase = ConnectionPhase.Ready,
        )
        setContent {
            YoinActivityRoot {
                SharedTransitionLayout {
                    val player: NowPlayingViewModel = viewModel(factory = NowPlayingViewModel.Factory(container))
                    remember(player) {
                        // With no account, repository cover resolution returns null.
                        // Decorate only this QA view-model's read-only projection.
                        val cover = (track.coverArt as CoverRef.Url).url
                        val illustrated = player.uiState.map { state ->
                            if (state is NowPlayingUiState.Playing) state.copy(coverArtUrl = cover) else state
                        }.stateIn(player.viewModelScope, SharingStarted.Eagerly, NowPlayingUiState.Idle)
                        NowPlayingViewModel::class.java.getDeclaredField("uiState")
                            .apply { isAccessible = true }.set(player, illustrated)
                        true
                    }
                    var expanded by rememberSaveable { mutableStateOf(true) }

                    val navBarBottomPx = WindowInsets.navigationBars.getBottom(LocalDensity.current)
                    SideEffect { container.experienceSessionStore.setNowPlayingExpanded(expanded) }
                    fun openDetail(intent: android.content.Intent) {
                        if (!gate.tryAcquire(lifecycle.currentState == Lifecycle.State.RESUMED)) return
                        try {
                            launchDetailFromShell(this@MotionAuditActivity, intent)
                        } catch (error: RuntimeException) {
                            gate.release()
                            throw error
                        }
                    }
                    Box(Modifier.fillMaxSize()) {
                        Button(onClick = { expanded = true }, modifier = Modifier.align(Alignment.Center)) {
                            Text("Open player")
                        }
                        OverlayChromeVisibility(
                            expanded = expanded,
                            enter = YoinMotion.fadeIn(role = YoinMotionRole.Standard) +
                                YoinMotion.slideInVertically(role = YoinMotionRole.Standard) { it + navBarBottomPx },
                            exit = YoinMotion.fadeOut(role = YoinMotionRole.Standard) +
                                YoinMotion.slideOutVertically(role = YoinMotionRole.Standard) { it + navBarBottomPx },
                            modifier = Modifier.align(Alignment.BottomCenter),
                        ) {
                            YoinButtonGroup(
                                selectedSection = YoinSection.HOME,
                                currentTrackId = track.id.toString(), currentTrackTitle = track.title,
                                currentTrackArtist = track.artist,
                                currentTrackCoverArtUrl = (track.coverArt as CoverRef.Url).url,
                                isPlaybackReady = true, connectionErrorMessage = null,
                                onHomeClick = {}, onLibraryClick = {}, onNowPlayingClick = { expanded = true },
                                sharedTransitionScope = this@SharedTransitionLayout, animatedVisibilityScope = this,
                            )
                        }
                        NowPlayingOverlayHost(

                            sharedTransitionScope = this@SharedTransitionLayout,
                            viewModel = player, container = container, expanded = expanded,
                            onExpandedChange = { expanded = it },
                            onAlbumClick = { openDetail(AlbumDetailActivity.intent(this@MotionAuditActivity, it)) },
                            onArtistClick = { openDetail(ArtistDetailActivity.intent(this@MotionAuditActivity, it)) },
                            onPlaylistClick = { openDetail(PlaylistDetailActivity.intent(this@MotionAuditActivity, it)) },
                        )
                    }
                }
            }
        }
    }
}
