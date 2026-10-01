package com.gpo.yoin.debug

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.gpo.yoin.YoinActivityRoot
import com.gpo.yoin.YoinApplication
import com.gpo.yoin.data.model.MediaId
import com.gpo.yoin.data.model.Track
import com.gpo.yoin.enableYoinEdgeToEdge
import com.gpo.yoin.player.ConnectionPhase
import com.gpo.yoin.player.PlaybackManager
import com.gpo.yoin.player.PlaybackState
import com.gpo.yoin.ui.component.YoinButtonGroup
import com.gpo.yoin.ui.detail.AlbumDetailActivity
import com.gpo.yoin.ui.detail.DetailActivityLaunchGate
import com.gpo.yoin.ui.detail.launchDetailFromShell
import com.gpo.yoin.ui.experience.HomeSurface
import com.gpo.yoin.ui.navigation.YoinSection
import com.gpo.yoin.ui.navigation.back.rememberDetailBackEnteringModifier
import com.gpo.yoin.ui.navigation.back.rememberShellBarChromeMorph
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.drop

/**
 * Account-free repro of the shell → album → back bottom-bar return (the bar
 * once carried a cross-window shadow hand-off; it is shadowless now) with
 * the SHIPPING return path: this window wires its bar exactly like
 * YoinNavHost (same chrome-morph owner, entering pose and settle backstop)
 * and opens the real AlbumDetailActivity through launchDetailFromShell, so
 * back runs the real predictive-back commit and the real close dissolve. The
 * fake album id leaves the page in its error state; the bars and pill are the
 * real ones.
 *
 *   adb shell am start -n com.gpo.yoin/.debug.ShadowReturnAuditActivity --el autoOpenMs 800
 *   (warm re-run: add -f 0x20000000)
 *   adb shell input keyevent KEYCODE_BACK        # button-back commit
 *   adb shell input swipe 2 1400 700 1400 300    # gesture commit (gesture nav)
 */
class ShadowReturnAuditActivity : ComponentActivity() {
    private val gate = DetailActivityLaunchGate()
    private val handler = Handler(Looper.getMainLooper())
    private var autoOpenMs = -1L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableYoinEdgeToEdge()
        autoOpenMs = intent.getLongExtra(EXTRA_AUTO_OPEN_MS, -1L)
        val timecode = intent.getBooleanExtra(EXTRA_TIMECODE, false)
        val container = (application as YoinApplication).container
        val store = container.experienceSessionStore
        store.setNowPlayingExpanded(false)
        store.setSelectedSection(YoinSection.HOME)
        store.setHomeSurface(HomeSurface.Feed)
        // Debug-only seam (same as MotionAuditActivity): a silent track so both
        // windows' pills show real content without an account.
        val track = Track(
            id = MediaId.subsonic("shadow-audit-track"),
            title = "Shadow audit", artist = "Yoin", artistId = MediaId.subsonic("shadow-audit-artist"),
            album = "Return to Home", albumId = MediaId.subsonic("shadow-audit-album"),
            coverArt = null, durationSec = 240, trackNumber = 1, year = 2026,
            genre = null, userRating = null,
        )
        val field = PlaybackManager::class.java.getDeclaredField("_playbackState")
            .apply { isAccessible = true }
        @Suppress("UNCHECKED_CAST")
        (field.get(container.playbackManager) as MutableStateFlow<PlaybackState>).value = PlaybackState(
            currentTrack = track, queue = listOf(track), currentIndex = 0,
            duration = 240_000, position = 80_000, connectionPhase = ConnectionPhase.Ready,
        )
        setContent {
            YoinActivityRoot {
                val session by store.state.collectAsState()
                val lifecycleOwner = LocalLifecycleOwner.current
                // YoinNavHost's reverse-morph backstop, verbatim.
                LaunchedEffect(lifecycleOwner) {
                    lifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                        store.detailWindowSettledTick
                            .drop(1)
                            .collect { store.setDetailChromeActive(false) }
                    }
                }
                Box(Modifier.fillMaxSize()) {
                    Box(
                        Modifier
                            .fillMaxSize()
                            .then(rememberDetailBackEnteringModifier(store, session.detailChromeActive)),
                    ) {
                        FakeHome()
                    }
                    if (timecode) FrameTimecode(Modifier.align(Alignment.TopEnd))
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .graphicsLayer { },
                        contentAlignment = Alignment.BottomCenter,
                    ) {
                        YoinButtonGroup(
                            selectedSection = YoinSection.HOME,
                            chromeProgress = rememberShellBarChromeMorph(store, session.detailChromeActive),
                            currentTrackId = track.id.toString(),
                            currentTrackTitle = track.title,
                            currentTrackArtist = track.artist,
                            currentTrackCoverArtUrl = null,
                            isPlaybackReady = true,
                            connectionErrorMessage = null,
                            playbackProgress = 1f / 3f,
                            onHomeClick = ::openAlbum,
                            onNowPlayingClick = {},
                            onLibraryClick = ::openAlbum,
                        )
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // onResume follows and schedules it.
        autoOpenMs = intent.getLongExtra(EXTRA_AUTO_OPEN_MS, -1L)
    }

    override fun onResume() {
        super.onResume()
        gate.release()
        if (autoOpenMs >= 0) {
            handler.postDelayed(::openAlbum, autoOpenMs)
            autoOpenMs = -1L
        }
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacksAndMessages(null)
    }

    private fun openAlbum() {
        if (!gate.tryAcquire(lifecycle.currentState == Lifecycle.State.RESUMED)) return
        val store = (application as YoinApplication).container.experienceSessionStore
        // The same arm YoinNavHost performs before a compact shell launch.
        store.prepareDetailEnterSlide()
        store.setDetailChromeActive(true)
        try {
            launchDetailFromShell(this, AlbumDetailActivity.intent(this, "shadow-audit-album"))
        } catch (error: RuntimeException) {
            gate.release()
            store.setDetailChromeActive(false)
            throw error
        }
    }

    private companion object {
        const val EXTRA_AUTO_OPEN_MS = "autoOpenMs"
        const val EXTRA_TIMECODE = "timecode"
    }
}

/**
 * `--ez timecode true`: 12 squares encoding this window's draw uptime
 * (ms mod 4096, MSB first), redrawn every frame, so a screen recording's
 * frames can be matched to logged per-window draw times.
 */
@androidx.compose.runtime.Composable
private fun FrameTimecode(modifier: Modifier) {
    val tick = androidx.compose.runtime.remember { androidx.compose.runtime.mutableLongStateOf(0L) }
    LaunchedEffect(Unit) {
        while (true) androidx.compose.runtime.withFrameNanos { tick.longValue = it }
    }
    androidx.compose.foundation.Canvas(
        modifier
            .statusBarsPadding()
            .padding(8.dp)
            .size(width = 240.dp, height = 20.dp),
    ) {
        tick.longValue
        val code = android.os.SystemClock.uptimeMillis() % 4096
        android.util.Log.d("ShadowReturnAudit", "timecode up=${android.os.SystemClock.uptimeMillis()} code=$code")
        val cell = size.width / 12f
        for (bit in 0 until 12) {
            val on = (code shr (11 - bit)) and 1L == 1L
            drawRect(
                color = if (on) androidx.compose.ui.graphics.Color.Black else androidx.compose.ui.graphics.Color.White,
                topLeft = androidx.compose.ui.geometry.Offset(bit * cell, 0f),
                size = androidx.compose.ui.geometry.Size(cell, size.height),
            )
        }
    }
}

@androidx.compose.runtime.Composable
private fun FakeHome() {
    Box(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
    ) {
        Column(
            Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .padding(horizontal = 16.dp, vertical = 24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text("Home", style = MaterialTheme.typography.displaySmall)
            repeat(2) { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    repeat(2) { col ->
                        Box(
                            Modifier
                                .weight(1f)
                                .aspectRatio(1f)
                                .background(
                                    if ((row + col) % 2 == 0) {
                                        MaterialTheme.colorScheme.primaryContainer
                                    } else {
                                        MaterialTheme.colorScheme.tertiaryContainer
                                    },
                                    RoundedCornerShape(16.dp),
                                ),
                        )
                    }
                }
            }
        }
    }
}
