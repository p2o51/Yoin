package com.gpo.yoin.debug

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.SharedTransitionLayout
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.flowWithLifecycle
import com.gpo.yoin.YoinApplication
import com.gpo.yoin.enableYoinEdgeToEdge
import com.gpo.yoin.ui.component.YoinButtonGroup
import com.gpo.yoin.ui.detail.AlbumDetailActivity
import com.gpo.yoin.ui.detail.launchDetailFromShell
import com.gpo.yoin.ui.navigation.YoinSection
import com.gpo.yoin.ui.theme.YoinTheme
import kotlinx.coroutines.flow.drop

/**
 * Debug-only repro/QA harness for the shell bar's nav⇄detail morph without
 * needing a configured music profile: a fake shell with the REAL
 * YoinButtonGroup, the REAL launchDetailFromShell hand-off into the REAL
 * AlbumDetailActivity (bogus id → Error state, bar still present), and the
 * REAL lifecycle-gated chrome restore on return.
 */
class BarMorphPreviewActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableYoinEdgeToEdge()
        setContent {
            YoinTheme {
                val context = LocalContext.current
                val app = context.applicationContext as YoinApplication
                var detailChrome by remember { mutableStateOf(false) }
                val lifecycleOwner = LocalLifecycleOwner.current
                LaunchedEffect(lifecycleOwner) {
                    app.container.experienceSessionStore.detailWindowSettledTick
                        .flowWithLifecycle(lifecycleOwner.lifecycle, Lifecycle.State.STARTED)
                        .drop(1)
                        .collect { detailChrome = false }
                }
                SharedTransitionLayout {
                    Surface(
                        modifier = Modifier.fillMaxSize(),
                        color = MaterialTheme.colorScheme.background,
                    ) {
                        Box(modifier = Modifier.fillMaxSize()) {
                            Button(
                                onClick = {
                                    detailChrome = true
                                    launchDetailFromShell(
                                        context,
                                        AlbumDetailActivity.intent(context, "debug:album:missing"),
                                    )
                                },
                                modifier = Modifier.align(Alignment.Center),
                            ) {
                                Text("Open detail (morph)")
                            }
                            // Mirror the real shell: the bar lives inside an
                            // AnimatedVisibility whose scope feeds its shared
                            // elements — with a playing title the np_title /
                            // np_artist sharedBounds are ACTIVE, which drags
                            // the whole bar subtree through the
                            // SharedTransitionLayout lookahead pass.
                            AnimatedVisibility(
                                visible = true,
                                modifier = Modifier.align(Alignment.BottomCenter),
                            ) {
                                YoinButtonGroup(
                                    selectedSection = YoinSection.HOME,
                                    detailChrome = detailChrome,
                                    currentTrackId = null,
                                    currentTrackTitle = "Cherries & Cream",
                                    currentTrackArtist = "Hannah Jadagu",
                                    currentTrackCoverArtUrl = null,
                                    isPlaybackReady = true,
                                    connectionErrorMessage = null,
                                    playbackProgress = 0.37f,
                                    isPlaying = true,
                                    onHomeClick = {},
                                    onNowPlayingClick = {},
                                    onLibraryClick = {},
                                    sharedTransitionScope = this@SharedTransitionLayout,
                                    animatedVisibilityScope = this,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
