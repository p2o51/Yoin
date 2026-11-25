package com.gpo.yoin.ui.nowplaying

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import com.gpo.yoin.data.repository.ActivityContext
import com.gpo.yoin.player.PlayMode
import com.gpo.yoin.ui.theme.YoinMotion
import com.gpo.yoin.ui.theme.YoinMotionRole
import com.gpo.yoin.ui.theme.YoinTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The lyric tools' tap target across their auto-hide (design.md 自动沉浸).
 * Tester report: the tools "only took taps for a moment after a scroll"; once
 * they began to fade, a tap on a key that was plainly still on screen did
 * nothing, and on the tablet they could not be hit at all.
 *
 * Two of the player's layouts in miniature, with the real [LyricsTools]:
 * the two-column player's right column (lyrics directly above a tools slot
 * that collapses when idle, band guard on the column) and the tab row (tools
 * that fade in place, guard on the tools).
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w400dp-h800dp")
class LyricToolsTapTargetTest {

    @get:Rule
    val rule = createComposeRule()

    private var searches = 0
    private var seeks = 0
    private var toolsAlpha = 1f
    private lateinit var idle: LyricToolsIdleState

    private val playing = NowPlayingUiState.Playing(
        songTitle = "Starlight",
        artist = "Muse",
        albumName = "Black Holes and Revelations",
        coverArtUrl = null,
        isPlaying = true,
        durationMs = 240_000L,
        songId = "1",
        rating = null,
        isStarred = false,
        lyrics = listOf(
            LyricLine(startMs = 1_000, text = "Far away"),
            LyricLine(startMs = 60_000, text = "This ship is taking me far away"),
        ),
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

    @androidx.compose.runtime.Composable
    private fun Tools(modifier: Modifier = Modifier) {
        LyricsTools(
            state = playing,
            selection = androidx.compose.runtime.remember { LyricsSelectionState() },
            canRecenter = false,
            onSearchClick = { searches++ },
            onTranslateClick = {},
            onRecenterClick = {},
            onEditLyricsClick = {},
            onMessage = {},
            modifier = modifier,
            idle = idle,
        )
    }

    /** WidePlayingContent's right column: lyrics, then the tools' slot (collapses when idle). */
    private fun setRightColumn() {
        idle = LyricToolsIdleState(clock = { rule.mainClock.currentTime })
        rule.setContent {
            YoinTheme {
                LyricToolsIdleEffect(idle, eligible = true)
                val alpha by rememberLyricToolsAlpha(idle, label = "testToolsAlpha")
                val alphaNow = alpha
                SideEffect { toolsAlpha = alphaNow }
                val slot by animateDpAsState(
                    targetValue = if (idle.idle) 0.dp else SlotHeight,
                    animationSpec = YoinMotion.defaultSpatialSpec(role = YoinMotionRole.Expressive),
                    label = "testToolsSlot",
                )
                Box(
                    modifier = Modifier
                        .size(400.dp, 700.dp)
                        .lyricToolsTouchTracker(idle),
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .lyricToolsWakeGuard(idle, enabled = true, band = SlotHeight),
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .weight(1f)
                                .testTag("lyrics")
                                .clickable { seeks++ },
                        )
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(slot)
                                .clipToBounds(),
                            contentAlignment = Alignment.BottomStart,
                        ) {
                            Tools(Modifier.graphicsLayer { this.alpha = alpha })
                        }
                    }
                }
            }
        }
    }

    /** The tab row: tools that fade in place, the guard on the tools themselves. */
    private fun setTabRow() {
        idle = LyricToolsIdleState(clock = { rule.mainClock.currentTime })
        rule.setContent {
            YoinTheme {
                LyricToolsIdleEffect(idle, eligible = true)
                val alpha by rememberLyricToolsAlpha(idle, label = "testToolsAlpha")
                val alphaNow = alpha
                SideEffect { toolsAlpha = alphaNow }
                Column(
                    modifier = Modifier
                        .size(400.dp, 700.dp)
                        .lyricToolsTouchTracker(idle),
                ) {
                    Row {
                        Box(
                            modifier = Modifier
                                .graphicsLayer { this.alpha = alpha }
                                .lyricToolsInPlaceWakeGuard(idle),
                        ) {
                            Tools()
                        }
                    }
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f)
                            .testTag("lyrics")
                            .clickable { seeks++ },
                    )
                }
            }
        }
    }

    /** Frame by frame up to the moment the idle count lets the tools go. */
    private fun advanceUntilTheFadeStarts() {
        rule.mainClock.autoAdvance = false
        var waited = 0L
        while (!idle.idle && waited < LyricToolsIdleMs + 1_000L) {
            rule.mainClock.advanceTimeByFrame()
            waited += 16L
        }
        assertTrue("precondition: the idle count let the tools go", idle.idle)
        // A few frames into the fade: plainly still on screen.
        var frames = 0
        while (toolsAlpha > 0.85f && frames < 30) {
            rule.mainClock.advanceTimeByFrame()
            frames++
        }
        assertTrue("precondition: mid-fade (alpha $toolsAlpha)", toolsAlpha > 0.3f && toolsAlpha < 1f)
    }

    private fun advanceUntilFullyHidden() {
        // In steps: one long jump can land past the idle flip without the frames that fade it.
        repeat(((LyricToolsIdleMs + 2_000L) / 100L).toInt()) { rule.mainClock.advanceTimeBy(100L) }
        rule.waitForIdle()
        assertTrue("precondition: tools are away", idle.idle)
        assertEquals("precondition: faded out", 0f, toolsAlpha)
    }

    private fun searchKey() = rule.onNodeWithContentDescription("Search lyrics", useUnmergedTree = true)

    // ── Fading: still visible, still the button ─────────────────────────────

    @Test
    fun should_runTheAction_when_aFadingBottomToolIsTapped() {
        setRightColumn()
        advanceUntilTheFadeStarts()

        searchKey().performTouchInput { click() }
        rule.mainClock.advanceTimeBy(100L)

        assertEquals("a key still on screen answers its tap", 1, searches)
        assertEquals("…and the tap never reaches the lyrics", 0, seeks)
        assertFalse("the touch brought the tools back", idle.idle)
    }

    @Test
    fun should_runTheAction_when_aFadingInPlaceToolIsTapped() {
        setTabRow()
        advanceUntilTheFadeStarts()

        searchKey().performTouchInput { click() }
        rule.mainClock.advanceTimeBy(100L)

        assertEquals("a key still on screen answers its tap", 1, searches)
        assertFalse(idle.idle)
    }

    // ── Fully hidden: no hit target, first tap only brings them back ───────

    @Test
    fun should_offerNoButton_when_theBottomToolsAreFullyHidden() {
        setRightColumn()
        advanceUntilFullyHidden()

        rule.onAllNodes(hasContentDescription("Search lyrics").and(hasClickAction()))
            .fetchSemanticsNodes()
            .let { assertEquals("a hidden key is neither clickable nor announced", 0, it.size) }
    }

    @Test
    fun should_offerNoButton_when_theInPlaceToolsAreFullyHidden() {
        setTabRow()
        advanceUntilFullyHidden()

        rule.onAllNodes(hasContentDescription("Search lyrics").and(hasClickAction()))
            .fetchSemanticsNodes()
            .let { assertEquals("a hidden key is neither clickable nor announced", 0, it.size) }
    }

    @Test
    fun should_revealNotSeek_when_theHiddenBottomToolSpotIsTapped() {
        setRightColumn()
        advanceUntilFullyHidden()

        // Where the search key stood: the slot's bottom-left key.
        rule.onNodeWithTag("lyrics").performTouchInput {
            click(androidx.compose.ui.geometry.Offset(26.dp.toPx(), height - 12.dp.toPx()))
        }
        rule.mainClock.advanceTimeBy(100L)

        assertEquals("the blind tap must not seek", 0, seeks)
        assertEquals(0, searches)
        assertFalse("the tap brought the tools back", idle.idle)

        rule.mainClock.advanceTimeBy(1_000L)
        searchKey().performTouchInput { click() }
        rule.mainClock.advanceTimeBy(100L)
        assertEquals("once back, the key answers", 1, searches)
        assertEquals(0, seeks)
    }

    @Test
    fun should_revealNotSearch_when_theHiddenInPlaceToolSpotIsTapped() {
        setTabRow()
        advanceUntilFullyHidden()

        rule.onNodeWithTag("lyrics").performTouchInput {
            click(androidx.compose.ui.geometry.Offset(26.dp.toPx(), -26.dp.toPx()))
        }
        rule.mainClock.advanceTimeBy(100L)

        assertEquals("the blind tap must not open search", 0, searches)
        assertEquals(0, seeks)
        assertFalse("the tap brought the tools back", idle.idle)
    }

    @Test
    fun should_stillSeek_when_aLyricLineAboveTheBandIsTappedWhileHidden() {
        // design.md: only the tools' own strip is wake-only; tap-to-seek on the
        // lines stays (and the touch brings the tools back too).
        setRightColumn()
        advanceUntilFullyHidden()

        rule.onNodeWithTag("lyrics").performTouchInput { click(center) }
        rule.mainClock.advanceTimeBy(100L)

        assertEquals(1, seeks)
        assertFalse(idle.idle)
    }

    // ── TalkBack and keyboard: the tools must stay reachable ───────────────

    @Test
    fun should_keepTheToolsShown_when_aScreenReaderExploresByTouch() {
        // TalkBack sends the app no touches, so nothing would ever bring the
        // tools back: with touch exploration on they never step away.
        val manager = androidx.test.core.app.ApplicationProvider
            .getApplicationContext<android.content.Context>()
            .getSystemService(android.view.accessibility.AccessibilityManager::class.java)
        org.robolectric.Shadows.shadowOf(manager).setTouchExplorationEnabled(true)
        setRightColumn()

        repeat(100) { rule.mainClock.advanceTimeBy(100L) }
        rule.waitForIdle()

        assertFalse("ten seconds on, the tools are still there", idle.idle)
        rule.onAllNodes(hasContentDescription("Search lyrics").and(hasClickAction()))
            .fetchSemanticsNodes()
            .let { assertEquals("TalkBack can reach the search key", 1, it.size) }
    }

    @Test
    fun should_keepTheToolsShown_when_aKeyHoldsInputFocus() {
        setRightColumn()
        rule.runOnIdle { idle.holdAwake(true) }

        repeat(((LyricToolsIdleMs * 2) / 100L).toInt()) { rule.mainClock.advanceTimeBy(100L) }
        rule.waitForIdle()
        assertFalse("a focused key keeps the tools", idle.idle)

        rule.runOnIdle { idle.holdAwake(false) }
        repeat(((LyricToolsIdleMs + 1_000L) / 100L).toInt()) { rule.mainClock.advanceTimeBy(100L) }
        rule.waitForIdle()
        assertTrue("focus gone, the count runs again", idle.idle)
    }

    private companion object {
        val SlotHeight = 68.dp
    }
}
