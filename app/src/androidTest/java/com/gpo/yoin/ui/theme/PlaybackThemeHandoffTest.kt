package com.gpo.yoin.ui.theme

import android.graphics.Bitmap
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import com.gpo.yoin.ui.component.NowPlayingPill
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class PlaybackThemeHandoffTest {
    @get:Rule val rule = createComposeRule()

    private lateinit var theme: PlaybackThemeState
    private var showDetail by mutableStateOf(false)
    private var showShell by mutableStateOf(true)
    private var shellColor: Color? = null
    private var detailColor: Color? = null
    private var firstDetailColor: Color? = null
    private var loadCount = 0

    @Test
    fun should_keepPlaybackColorOnFirstFrame_whenDetailAttachesToSettledShell() {
        setContent()
        loadArtwork("red", android.graphics.Color.RED)
        rule.mainClock.advanceTimeBy(2000)
        val settled = shellColor
        rule.runOnIdle {
            // The incoming Activity requests the same cover. It must reuse it.
            theme.updateArtwork("red", false) { loadCount++; error("Duplicate cover load") }
            showDetail = true
        }
        rule.mainClock.advanceTimeBy(32)
        rule.runOnIdle {
            assertEquals(1, loadCount)
            assertEquals(settled, firstDetailColor)
            assertEquals(shellColor, detailColor)
        }
        assertPillColorsMatch()
    }

    @Test
    fun should_shareInFlightWashAndFinish_whenDetailAttachesAndShellLeaves() {
        setContent()
        loadArtwork("red", android.graphics.Color.RED)
        rule.mainClock.advanceTimeBy(2000)
        val from = shellColor
        loadArtwork("blue", android.graphics.Color.BLUE)
        rule.mainClock.advanceTimeBy(64)
        val destination = theme.colorScheme(false)!!.primaryContainer
        rule.runOnIdle {
            assertNotEquals(from, shellColor)
            assertNotEquals(destination, shellColor)
            showDetail = true
        }
        rule.mainClock.advanceTimeByFrame()
        rule.runOnIdle {
            assertEquals(shellColor, firstDetailColor)
            assertNotEquals(destination, firstDetailColor)
        }
        repeat(3) {
            rule.mainClock.advanceTimeBy(32)
            rule.runOnIdle { assertEquals(shellColor, detailColor) }
            assertPillColorsMatch()
        }
        rule.runOnIdle { showShell = false }
        rule.mainClock.advanceTimeBy(2000)
        rule.runOnIdle { assertEquals(destination, detailColor) }
    }

    private fun setContent() {
        rule.mainClock.autoAdvance = false
        rule.setContent {
            val scope = rememberCoroutineScope()
            theme = remember { PlaybackThemeState(scope) }
            Column {
                if (showShell) WindowPill("shell") { shellColor = it }
                if (showDetail) WindowPill("detail") {
                    if (firstDetailColor == null) firstDetailColor = it
                    detailColor = it
                }
            }
        }
    }

    @Composable
    private fun WindowPill(tag: String, onColor: (Color) -> Unit) {
        YoinTheme(playbackThemeState = theme, darkTheme = false) {
            val color = MaterialTheme.colorScheme.primaryContainer
            SideEffect { onColor(color) }
            NowPlayingPill(
                currentTrackId = "track", currentTrackTitle = "", currentTrackArtist = "",
                currentTrackCoverArtUrl = null, connectionErrorMessage = null,
                playbackProgress = 0f, isPlaying = false, onClick = {},
                modifier = Modifier.width(220.dp).height(48.dp).testTag(tag),
            )
        }
    }

    private fun loadArtwork(key: String, color: Int) {
        val previous = theme.colorScheme(false)
        rule.runOnIdle {
            theme.updateArtwork(key, false) {
                loadCount++
                Bitmap.createBitmap(16, 16, Bitmap.Config.ARGB_8888).apply { eraseColor(color) }
            }
        }
        rule.waitUntil(5000) {
            rule.mainClock.advanceTimeByFrame()
            theme.colorScheme(false) !== previous
        }
        rule.mainClock.advanceTimeByFrame()
    }

    private fun assertPillColorsMatch() {
        fun pixel(tag: String): Int {
            val bitmap = rule.onNodeWithTag(tag).captureToImage().toPixelMap()
            return bitmap[(bitmap.width * 0.8f).toInt(), bitmap.height / 2].toArgb()
        }
        val shell = pixel("shell")
        assertEquals("Both windows must render identical NP colors", shell, pixel("detail"))
        assertTrue("Pill must use the current wash without a second spring", shell == shellColor!!.toArgb())
    }
}
