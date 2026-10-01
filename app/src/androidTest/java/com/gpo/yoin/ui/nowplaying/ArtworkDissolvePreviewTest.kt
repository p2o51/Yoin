package com.gpo.yoin.ui.nowplaying

import android.graphics.Bitmap
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.test.platform.app.InstrumentationRegistry
import com.gpo.yoin.data.repository.ActivityContext
import com.gpo.yoin.ui.theme.YoinTheme
import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test

/** Deterministic rendered frames for visual review when emulator screenrecord drops frames. */
class ArtworkDissolvePreviewTest {
    @get:Rule val rule = createComposeRule()

    @Test
    fun should_revealNextCover_whenSkippingInsideFullPlayer() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("captureArtworkPreview") == "true")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val directory = File(context.getExternalFilesDir(null), "artwork-preview").apply { mkdirs() }
        val covers = listOf("preview-first.png", "preview-next.png").map { File(directory, it) }
        assertTrue("Supply the two local QA covers before recording", covers.all { it.exists() })
        val initial = NowPlayingUiState.Playing(
            songTitle = "Harbor Lights", artist = "Yoin motion study", albumName = "Color Studies",
            songId = "preview-first", coverArtUrl = covers[0].toURI().toString(),
            isPlaying = false, durationMs = 240_000, rating = 4.2f, isStarred = false,
            lyrics = listOf(LyricLine(0, "A little color, a change of scene")), showLyricsTranslation = false,
            lyricsActionInFlight = null, lyricsLoading = false, queue = emptyList(),
            currentQueueIndex = 0, shuffleEnabled = false, albumId = null, artistId = null,
            activityContext = ActivityContext.None
        )
        var state by mutableStateOf(initial)
        var skipDirection by mutableStateOf(1)
        rule.mainClock.autoAdvance = false
        rule.setContent {
            YoinTheme(darkTheme = true) {
                NowPlayingScreen(
                    uiState = state, positionMs = { 100_000L }, bufferedMs = { 100_000L },
                    skipDirection = skipDirection,
                    hasAudioSpectrum = false, onTogglePlayPause = {},
                    onSkipNext = {
                        skipDirection = 1
                        state = initial.copy(
                            songId = "preview-next", songTitle = "Violet Hours",
                            coverArtUrl = covers[1].toURI().toString()
                        )
                    },
                    onSkipPrevious = {
                        skipDirection = -1
                        state = initial
                    }, onSeek = {}, onRatingChange = {},
                    onToggleFavorite = {}, onAddCurrentToPlaylist = {}, onSkipToQueueItem = {}
                )
            }
        }
        // Give file decoding time without consuming the upcoming transition.
        repeat(10) {
            rule.mainClock.advanceTimeBy(160)
            rule.waitForIdle()
        }
        fun capture(direction: String, index: Int) {
            val bitmap = rule.onRoot().captureToImage().asAndroidBitmap()
            File(directory, "$direction-%03d.png".format(index)).outputStream().use {
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
            }
        }
        for (direction in listOf("next", "previous")) {
            capture(direction, 0)
            rule.onNodeWithContentDescription("Skip $direction").performClick()
            repeat(96) { frame ->
                rule.mainClock.advanceTimeBy(16)
                capture(direction, frame + 1)
            }
        }
    }
}
