package com.gpo.yoin.ui.nowplaying

import android.graphics.Bitmap
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import com.gpo.yoin.ui.theme.YoinTheme
import java.io.File
import kotlin.math.abs
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class LyricsMotionTest {
    @get:Rule val rule = createComposeRule()

    private val songA = List(12) { i ->
        LyricLine(startMs = 2_000L + i * 3_000L, text = "Harbor line $i", translation = "港口第 $i 行")
    }
    private val songB = List(8) { i ->
        LyricLine(startMs = 4_000L + i * 3_000L, text = "Paper line $i", translation = "纸飞机第 $i 行")
    }

    // Mid-song, so the focus line can sit at its 38% anchor with rows above it.
    private val midSongMs = 2_000L + 5 * 3_000L + 500L

    @Test
    fun should_keepFocusLineInPlace_when_translationToggles() {
        var showTranslation by mutableStateOf(false)
        var density = 1f
        rule.mainClock.autoAdvance = false
        rule.setContent {
            density = LocalDensity.current.density
            Host {
                LyricsFullscreenPane(
                    lyrics = songA,
                    positionMs = { midSongMs },
                    loading = false,
                    showTranslation = showTranslation,
                    autoScrollEnabled = true,
                    recenterRequestKey = 0,
                    onUserScroll = {},
                    onSeekToMs = {},
                    trackKey = "a",
                    songTitle = "Harbor Lights",
                    artist = "Sample",
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
        rule.mainClock.advanceTimeBy(2_000)
        val restY = focusTop("Harbor line 5")

        showTranslation = true
        var maxDrift = 0f
        repeat(45) { frame ->
            rule.mainClock.advanceTimeByFrame()
            maxDrift = maxOf(maxDrift, abs(focusTop("Harbor line 5") - restY))
            if (frame in CaptureFrames) capture("translation_$frame")
        }
        // A pixel or two of rounding is fine; a trailing frame of growth is not.
        assertTrue("focus line drifted ${maxDrift / density}dp", maxDrift / density < 2f)
    }

    @Test
    fun should_keepOutgoingLyricsOnScreen_when_songChanges() {
        var song by mutableIntStateOf(0)
        rule.mainClock.autoAdvance = false
        rule.setContent {
            Host {
                val lines = if (song == 0) songA else emptyList()
                LyricsFullscreenPane(
                    lyrics = lines,
                    positionMs = { if (song == 0) midSongMs else 0L },
                    loading = song != 0,
                    showTranslation = false,
                    autoScrollEnabled = true,
                    recenterRequestKey = 0,
                    onUserScroll = {},
                    onSeekToMs = {},
                    trackKey = song,
                    queueIndex = song,
                    songTitle = if (song == 0) "Harbor Lights" else "Paper Satellites",
                    artist = "Sample",
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
        rule.mainClock.advanceTimeBy(2_000)
        song = 1
        repeat(40) { frame ->
            rule.mainClock.advanceTimeByFrame()
            if (frame == 4) {
                // The leaving song still renders its own lines mid-exit,
                // even though the state already moved to the next song.
                rule.onAllNodes(
                    androidx.compose.ui.test.hasText("Harbor line 5"),
                    useUnmergedTree = true,
                ).fetchSemanticsNodes().let { assertTrue("outgoing lines vanished", it.isNotEmpty()) }
            }
            if (frame in CaptureFrames) capture("song_$frame")
        }
    }

    @androidx.compose.runtime.Composable
    private fun Host(content: @androidx.compose.runtime.Composable () -> Unit) {
        YoinTheme {
            Surface(Modifier.width(360.dp).height(640.dp)) { content() }
        }
    }

    private fun focusTop(text: String): Float =
        rule.onAllNodes(androidx.compose.ui.test.hasText(text), useUnmergedTree = true)
            .fetchSemanticsNodes()
            .first()
            .boundsInRoot.top

    /** Frames saved for eyeballing; pull from the app's external files dir. */
    private fun capture(name: String) {
        val bitmap = rule.onRoot().captureToImage().asAndroidBitmap()
        val dir = InstrumentationRegistry.getInstrumentation().targetContext
            .getExternalFilesDir("lyrics-motion") ?: return
        File(dir, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    private companion object {
        val CaptureFrames = setOf(0, 3, 6, 9, 12, 16, 22, 30)
    }
}
