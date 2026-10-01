package com.gpo.yoin.ui.nowplaying

import android.graphics.Bitmap
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionLayout
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import com.gpo.yoin.data.repository.ActivityContext
import com.gpo.yoin.player.PlayMode
import com.gpo.yoin.ui.navigation.back.OverlayChromeVisibility
import com.gpo.yoin.ui.navigation.back.OverlayPlayerVisibility
import com.gpo.yoin.ui.theme.YoinMotion
import com.gpo.yoin.ui.theme.YoinMotionRole
import com.gpo.yoin.ui.theme.YoinTheme
import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalSharedTransitionApi::class)
class NowPlayingLyricsInterruptionTest {
    @get:Rule val rule = createComposeRule()

    @Test
    fun should_shrinkCoverWithoutGhost_whenLyricsInterruptsOpening() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val artwork = File(context.cacheDir, "lyrics-interruption-art.png")
        artwork.outputStream().use { stream ->
            Bitmap.createBitmap(128, 128, Bitmap.Config.ARGB_8888).apply {
                eraseColor(android.graphics.Color.MAGENTA)
            }.compress(Bitmap.CompressFormat.PNG, 100, stream)
        }
        val state = NowPlayingUiState.Playing(
            songTitle = "Interruption", artist = "Motion test", albumName = "Album",
            songId = "interruption", coverArtUrl = artwork.toURI().toString(),
            isPlaying = false, durationMs = 240_000, rating = 0f, isStarred = false,
            lyrics = listOf(LyricLine(0, "Lyrics")), showLyricsTranslation = false,
            lyricsActionInFlight = null, lyricsLoading = false, queue = emptyList(),
            currentQueueIndex = 0, playMode = PlayMode.RepeatAll, albumId = null, artistId = null,
            activityContext = ActivityContext.None,
        )
        var open by mutableStateOf(false)
        var mode by mutableStateOf(NowPlayingStageMode.Compact)
        var density = 1f
        var entranceRunning = false
        lateinit var stage: NowPlayingStageProgress
        rule.mainClock.autoAdvance = false
        rule.setContent {
            YoinTheme(colorSchemeOverride = androidx.compose.material3.lightColorScheme()) {
                density = LocalDensity.current.density
                stage = rememberNowPlayingStageProgress(NowPlayingStageMode.Compact)
                LaunchedEffect(mode) {
                    stage.animateDetailTo(
                        if (mode == NowPlayingStageMode.Expanded) 1f else 0f,
                        YoinMotion.stageSettleSpring(),
                    )
                }
                SharedTransitionLayout {
                    Box(Modifier.fillMaxSize()) {
                        OverlayChromeVisibility(
                            expanded = open,
                            modifier = Modifier.align(Alignment.BottomCenter),
                            enter = YoinMotion.fadeIn(role = YoinMotionRole.Standard),
                            exit = YoinMotion.fadeOut(role = YoinMotionRole.Standard) +
                                YoinMotion.slideOutVertically(role = YoinMotionRole.Standard) { it },
                        ) {
                            AlbumCover(
                                songId = state.songId, coverArtUrl = state.coverArtUrl,
                                sharedTransitionScope = this@SharedTransitionLayout,
                                animatedVisibilityScope = this, modifier = Modifier.size(34.dp),
                            )
                        }
                        OverlayPlayerVisibility(open, Modifier.fillMaxSize()) {
                            entranceRunning = transition.currentState != transition.targetState
                            NowPlayingScreen(
                                uiState = state, positionMs = { 0 }, bufferedMs = { 0 },
                                hasAudioSpectrum = false, onTogglePlayPause = {}, onSkipNext = {},
                                onSkipPrevious = {}, onSeek = {}, onRatingChange = {},
                                onToggleFavorite = {}, onAddCurrentToPlaylist = {}, onSkipToQueueItem = {},
                                stageMode = mode, stageProgress = stage, onStageModeChange = { mode = it },
                                sharedTransitionScope = this@SharedTransitionLayout,
                                animatedVisibilityScope = this,
                            )
                        }
                    }
                }
            }
        }
        // Warm the local image before opening; no network timing in this regression.
        rule.waitUntil(5_000) { artworkHeightPx() > 0 }
        for (delay in listOf(96L, 240L, 3_200L)) {
            rule.runOnIdle { open = true }
            rule.mainClock.advanceTimeBy(delay)
            if (delay < 1_000) rule.runOnIdle { assertTrue("Must interrupt the entrance", entranceRunning) }
            rule.runOnIdle { mode = NowPlayingStageMode.Expanded }
            repeat(14) { frame ->
                rule.mainClock.advanceTimeBy(48)
                val progress = stage.detail
                val heightDp = artworkHeightPx("lyrics-$delay-$frame") / density
                // The cover must visibly shrink with the lyrics stage, even while
                // the outer shared transition is still settling. A lingering hero
                // is ~280dp; the docked artwork is only 44dp.
                if (progress > 0.9f) {
                    assertTrue("delay=$delay frame=$frame progress=$progress cover=${heightDp}dp", heightDp < 90f)
                    assertTrue("Cover disappeared during handoff", heightDp > 20f)
                }
            }
            rule.runOnIdle { mode = NowPlayingStageMode.Compact }
            rule.mainClock.advanceTimeBy(3_200)
            assertTrue("Hero must recover after returning from lyrics", artworkHeightPx() / density > 150f)
            rule.runOnIdle { open = false }
            rule.mainClock.advanceTimeBy(3_200)
        }
    }

    private fun artworkHeightPx(name: String? = null): Int {
        val image = rule.onRoot().captureToImage()
        if (name != null) {
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            File(context.getExternalFilesDir(null), "$name.png").outputStream().use {
                image.asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it)
            }
        }
        val pixels = image.toPixelMap()
        var top = pixels.height
        var bottom = -1
        for (y in 0 until pixels.height) for (x in 0 until pixels.width) {
            val color = pixels[x, y]
            if (color.red > 0.7f && color.blue > 0.7f && color.green < 0.3f) {
                top = minOf(top, y)
                bottom = maxOf(bottom, y)
            }
        }
        return (bottom - top + 1).coerceAtLeast(0)
    }
}
