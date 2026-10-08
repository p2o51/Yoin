package com.gpo.yoin.ui.memories.showcase

import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.unit.Density
import com.gpo.yoin.ui.navigation.back.BackMotionTokens
import com.gpo.yoin.ui.theme.YoinTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * QA B3: Memories open, the display size changed (wm density 420, then reset), Diary tapped — the bar showed only
 * the pill and dots. p's controller was rebuilt for the new px, and the bar's derived flags still read the first
 * one, so slot B (the bar cover, the album, ⌄) never composed on that page.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w411dp-h914dp")
class MemoriesDensityChangeTest {

    @get:Rule
    val rule = createComposeRule()

    @Test
    fun should_show_diary_slot_when_host_hands_in_new_progress_reader() {
        // the controller the bar first saw stays at the card; the host's new one opens the diary
        val first = mutableFloatStateOf(0f)
        val second = mutableFloatStateOf(0f)
        var progress by mutableStateOf<() -> Float>({ first.floatValue })
        rule.setContent {
            YoinTheme {
                MemoryPageBarSlots(
                    album = "Blue Hour Sessions",
                    artist = "Mira Kade",
                    year = "2021",
                    lastHeard = "Last heard Sep 28",
                    dotCount = 6,
                    relative = { 0f },
                    diaryProgress = progress,
                    onCloseDiary = {},
                    cover = { m -> Box(m) },
                )
            }
        }

        rule.runOnIdle { progress = { second.floatValue } }
        rule.runOnIdle { second.floatValue = 1f }

        rule.onNodeWithContentDescription("Blue Hour Sessions, Mira Kade").assertExists()
    }

    @Test
    fun should_keep_diary_state_and_rescale_its_px_when_density_changes() {
        var density by mutableStateOf(Density(2f))
        val seen = mutableListOf<MemoriesDiaryState>()
        rule.setContent {
            CompositionLocalProvider(LocalDensity provides density) {
                YoinTheme {
                    val state = rememberMemoriesDiaryState(initialFraction = 1f)
                    SideEffect { seen += state }
                }
            }
        }

        // wm density 420 on this tablet, then reset
        rule.runOnIdle { density = Density(2.625f) }
        rule.runOnIdle { density = Density(2f) }
        rule.runOnIdle { density = Density(3f) }
        rule.waitForIdle()

        val states = seen.distinct()
        assertEquals("one controller across the density changes", 1, states.size)
        val state = states.single()
        assertEquals("an open diary stays open", 1f, state.fraction, 0f)
        // the pull math runs on the new px: half the morph travel at density 3 is p .5
        val travelPx = BackMotionTokens.MemoriesDiaryMorphDistance.value * 3f
        rule.runOnIdle {
            state.startPull(banded = false, fromScrolled = false)
            state.pullBy(travelPx / 2f)
        }
        assertEquals(0.5f, state.fraction, 1e-3f)
    }
}
