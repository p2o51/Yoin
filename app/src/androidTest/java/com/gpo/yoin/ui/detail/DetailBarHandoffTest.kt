package com.gpo.yoin.ui.detail

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.test.core.app.ApplicationProvider
import com.gpo.yoin.YoinApplication
import com.gpo.yoin.ui.component.YoinButtonGroup
import com.gpo.yoin.ui.navigation.YoinSection
import com.gpo.yoin.ui.navigation.back.rememberShellBarChromeMorph
import com.gpo.yoin.ui.theme.YoinTheme
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class DetailBarHandoffTest {
    @get:Rule
    val rule = createComposeRule()

    @Test
    fun should_continueVisibleMorph_whenDetailMountsDuringShellAnimation() {
        verifyHandoff(YoinSection.HOME)
    }

    @Test
    fun should_preserveLibraryOrigin_whenDetailMountsDuringShellAnimation() {
        verifyHandoff(YoinSection.LIBRARY)
    }

    private fun verifyHandoff(section: YoinSection) {
        val store = ApplicationProvider.getApplicationContext<YoinApplication>()
            .container.experienceSessionStore
        runBlocking { store.shellBarChromeMorph.snapTo(0f) }
        store.setDetailChromeActive(false)
        var launch by mutableStateOf(false)
        var mounted by mutableStateOf(false)
        val back = DetailBackCollapseState()
        rule.mainClock.autoAdvance = false
        rule.setContent {
            YoinTheme {
                val progress = rememberShellBarChromeMorph(store, launch)
                Box(Modifier.fillMaxSize()) {
                    YoinButtonGroup(
                        selectedSection = section,
                        currentTrackId = "morph-track",
                        currentTrackTitle = "Morph track",
                        currentTrackArtist = "Artist",
                        currentTrackCoverArtUrl = null,
                        isPlaybackReady = true,
                        connectionErrorMessage = null,
                        chromeProgress = progress,
                        onHomeClick = {},
                        onNowPlayingClick = {},
                        onLibraryClick = {},
                        modifier = Modifier.align(Alignment.TopCenter),
                    )
                    if (mounted) {
                        DetailBottomBar(
                            playContainer = MaterialTheme.colorScheme.primary,
                            playContent = MaterialTheme.colorScheme.onPrimary,
                            onPlay = {},
                            onShuffle = {},
                            onOpenNowPlaying = {},
                            miniPlayer = DetailMiniPlayerState(
                                "morph-track", "Morph track", "Artist", null, false,
                            ),
                            playbackProgress = 0f,
                            navSection = section,
                            enterChromeProgress = rememberDetailBarEnterProgress(true, back),
                            backMorphProgress = { back.progress },
                            modifier = Modifier.align(Alignment.BottomCenter),
                        )
                    }
                }
            }
        }
        rule.runOnIdle {
            store.setDetailChromeActive(true)
            launch = true
        }
        rule.mainClock.advanceTimeBy(64)
        rule.runOnIdle { mounted = true }
        rule.mainClock.advanceTimeByFrame()
        val first = titlePositions()
        assertEquals("Incoming window must use the in-flight pose", first[0], first[1], 1f)
        rule.runOnIdle { assertTrue(store.shellBarChromeMorph.value in 0.01f..0.99f) }
        rule.mainClock.advanceTimeBy(64)
        val later = titlePositions()
        assertEquals(later[0], later[1], 1f)
        assertTrue("Now Playing must visibly move after the detail mounts", later[1] > first[1] + 1f)
        rule.mainClock.advanceTimeBy(1_000)
        val settled = titlePositions()[1]

        // A gesture and its cancellation remain owned by the back controller.
        rule.runOnIdle {
            back.gestureActive = true
            runBlocking { back.chased.snapTo(0.6f) }
        }
        rule.mainClock.advanceTimeByFrame()
        val scrubbed = titlePositions()[1]
        assertTrue(scrubbed < settled - 1f)
        rule.runOnIdle {
            runBlocking { back.chased.snapTo(0f) }
            back.gestureActive = false
        }
        rule.mainClock.advanceTimeByFrame()
        assertEquals(settled, titlePositions()[1], 1f)

        // During commit the shell also settles. Multiplying both progresses
        // would double-scrub the incoming bar and create a hand-off jump.
        rule.runOnIdle {
            back.committed = true
            runBlocking {
                back.chased.snapTo(0.6f)
                store.shellBarChromeMorph.snapTo(0.2f)
            }
        }
        rule.mainClock.advanceTimeByFrame()
        assertEquals(scrubbed, titlePositions()[1], 1f)
        rule.runOnIdle { store.setDetailChromeActive(false) }
    }

    private fun titlePositions(): List<Float> = rule
        .onAllNodesWithText("Morph track", useUnmergedTree = true)
        .fetchSemanticsNodes()
        .sortedBy { it.boundsInRoot.top }
        .map { it.boundsInRoot.left }
        .also { assertEquals(2, it.size) }
}
