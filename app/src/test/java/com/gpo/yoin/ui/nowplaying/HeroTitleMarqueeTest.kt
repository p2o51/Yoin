package com.gpo.yoin.ui.nowplaying

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import com.gpo.yoin.data.repository.ActivityContext
import com.gpo.yoin.player.PlayMode
import com.gpo.yoin.ui.theme.YoinTheme
import kotlin.math.ceil
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class HeroTitleMarqueeTest {

    @get:Rule
    val rule = createComposeRule()

    @Test
    fun should_enableMarqueeAndFillContainer_when_stretchedTitleOverflowsAvailableWidth() {
        // Robolectric measures stub glyphs at ~1px/char; use enough characters so 3% stretch
        // exceeds +1px cleanly while still fitting unstretched inside tightWidthPx.
        val sample = "TAKE MY BREATH AWAY INTO THE TWILIGHT NIGHT SKY AND BEYOND THE HORIZON LINE"
        var unscaledWidthPx = 0
        var marqueeContainerWidthPx = 0
        var staticContainerWidthPx = 0

        rule.setContent {
            YoinTheme {
                val style = MaterialTheme.typography.displaySmall
                val measurer = rememberTextMeasurer()
                unscaledWidthPx = measurer.measure(
                    text = AnnotatedString(sample),
                    style = style,
                    maxLines = 1,
                    softWrap = false,
                    constraints = Constraints(maxWidth = Constraints.Infinity),
                ).size.width

                // Pick a width strictly larger than unscaledWidthPx (fits at 1.0f),
                // yet strictly smaller than ceil(unscaledWidthPx * 1.03f) (overflows while playing).
                val tightWidthPx = unscaledWidthPx + 1
                val stretchedWidthPx = ceil(unscaledWidthPx * 1.03f).toInt()
                assertTrue(
                    "Expected stretched width ($stretchedWidthPx) > tight width ($tightWidthPx)",
                    stretchedWidthPx > tightWidthPx,
                )
                val tightWidthDp = with(LocalDensity.current) { tightWidthPx.toDp() }

                Box(modifier = Modifier.width(tightWidthDp)) {
                    NowPlayingMarqueeTitle(
                        text = sample,
                        style = style,
                        color = Color.Black,
                        stretchScale = 1.03f,
                        overflowStretchScale = 1.03f,
                        modifier = Modifier.onGloballyPositioned {
                            marqueeContainerWidthPx = it.size.width
                        },
                    )
                }

                Box(modifier = Modifier.width(tightWidthDp)) {
                    NowPlayingMarqueeTitle(
                        text = sample,
                        style = style,
                        color = Color.Black,
                        stretchScale = 1.0f,
                        overflowStretchScale = 1.0f,
                        modifier = Modifier.onGloballyPositioned {
                            staticContainerWidthPx = it.size.width
                        },
                    )
                }
            }
        }

        rule.waitForIdle()
        // Overflowing stretched title fills the tight box (enabling marquee + fade mask),
        // while the unstretched title wraps tightly at its unscaled text width.
        assertEquals(unscaledWidthPx + 1, marqueeContainerWidthPx)
        assertEquals(unscaledWidthPx, staticContainerWidthPx)
    }

    @Test
    fun should_shiftArtistAndStayUnmarqueed_when_oneLineHeroFitsStretched() {
        var stretch by mutableFloatStateOf(0.97f)
        var artistXBefore = 0f
        var artistXAfter = 0f
        val state = NowPlayingUiState.Playing(
            songTitle = "TAKE MY BREATH",
            artist = "XG",
            albumName = "THE CORE",
            coverArtUrl = null,
            isPlaying = true,
            durationMs = 203_000L,
            songId = "xg-1",
            rating = 7.2f,
            isStarred = true,
            lyrics = emptyList(),
            showLyricsTranslation = false,
            lyricsActionInFlight = null,
            lyricsLoading = false,
            queue = emptyList(),
            currentQueueIndex = 0,
            playMode = PlayMode.RepeatAll,
            albumId = null,
            artistId = null,
            activityContext = ActivityContext.None,
        )

        rule.setContent {
            YoinTheme {
                Box(modifier = Modifier.width(426.dp)) {
                    CompactBottomHero(
                        state = state,
                        heroBoundsSpec = androidx.compose.animation.core.spring(),
                        titleStretchScale = stretch,
                        artistStretchScale = 1f,
                        titleRouteInteraction = null,
                        artistRouteInteraction = null,
                        sharedTransitionScope = null,
                        animatedVisibilityScope = null,
                        height = 56.dp,
                        alpha = 1f,
                        oneLine = true,
                        stackable = true,
                        stacking = 0f,
                    )
                }
            }
        }

        rule.waitForIdle()
        artistXBefore = rule.onNodeWithText("XG").fetchSemanticsNode().positionInRoot.x

        stretch = 1.03f
        rule.waitForIdle()
        artistXAfter = rule.onNodeWithText("XG").fetchSemanticsNode().positionInRoot.x

        assertTrue(
            "Expected artist XG to ride right when title stretches from 0.97 to 1.03 " +
                "(before=$artistXBefore, after=$artistXAfter)",
            artistXAfter > artistXBefore,
        )
    }

    @Test
    fun should_keepArtistBaselineAligned_when_oneLineHeroTitleMarquees() {
        val state = NowPlayingUiState.Playing(
            songTitle = "TAKE MY BREATH AWAY INTO THE ENDLESS TWILIGHT",
            artist = "XG",
            albumName = "THE CORE",
            coverArtUrl = null,
            isPlaying = true,
            durationMs = 203_000L,
            songId = "xg-2",
            rating = 7.2f,
            isStarred = true,
            lyrics = emptyList(),
            showLyricsTranslation = false,
            lyricsActionInFlight = null,
            lyricsLoading = false,
            queue = emptyList(),
            currentQueueIndex = 0,
            playMode = PlayMode.RepeatAll,
            albumId = null,
            artistId = null,
            activityContext = ActivityContext.None,
        )

        rule.setContent {
            YoinTheme {
                Box(modifier = Modifier.width(320.dp)) {
                    CompactBottomHero(
                        state = state,
                        heroBoundsSpec = androidx.compose.animation.core.spring(),
                        titleStretchScale = 1.03f,
                        artistStretchScale = 1f,
                        titleRouteInteraction = null,
                        artistRouteInteraction = null,
                        sharedTransitionScope = null,
                        animatedVisibilityScope = null,
                        height = 56.dp,
                        alpha = 1f,
                        oneLine = true,
                        stackable = true,
                        stacking = 0f,
                    )
                }
            }
        }

        rule.waitForIdle()
        val titleBounds = rule.onNodeWithText(state.songTitle).fetchSemanticsNode().boundsInRoot
        val artistBounds = rule.onNodeWithText("XG").fetchSemanticsNode().boundsInRoot
        assertTrue(
            "Expected artist XG to sit to the right of the marquee title " +
                "(title=$titleBounds, artist=$artistBounds)",
            artistBounds.left > titleBounds.left,
        )
        assertTrue(
            "Expected artist XG baseline to sit inside the title's vertical band " +
                "(title=$titleBounds, artist=$artistBounds)",
            artistBounds.bottom <= titleBounds.bottom + 4f && artistBounds.top >= titleBounds.top,
        )
    }
}
