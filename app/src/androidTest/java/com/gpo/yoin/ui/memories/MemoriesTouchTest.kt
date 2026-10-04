package com.gpo.yoin.ui.memories

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.test.core.app.ApplicationProvider
import com.gpo.yoin.YoinApplication
import com.gpo.yoin.ui.experience.rememberRevealState
import com.gpo.yoin.ui.theme.YoinTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

/**
 * Memories is drawn over Home as a sibling (the shell's reveal host). Blank
 * page — the header band — must not let a tap fall through to Home's settings
 * gear underneath; once the deck is retracted, Home must be tappable again.
 */
class MemoriesTouchTest {

    @get:Rule
    val rule = createComposeRule()

    @Test
    fun should_not_deliver_tap_to_home_when_header_blank_is_tapped() {
        var homeTaps = 0
        setUpHomeUnderMemories(revealFraction = 0f, onHomeGearTap = { homeTaps++ })

        rule.onNodeWithTag(HOME_GEAR_TAG).performTouchInput { click() }

        rule.runOnIdle { assertEquals(0, homeTaps) }
    }

    @Test
    fun should_deliver_tap_to_home_when_memories_is_retracted() {
        var homeTaps = 0
        setUpHomeUnderMemories(revealFraction = 1f, onHomeGearTap = { homeTaps++ })

        rule.onNodeWithTag(HOME_GEAR_TAG).performTouchInput { click() }

        rule.runOnIdle { assertEquals(1, homeTaps) }
    }

    /** Mirrors the shell host: Home first, then Memories translated by the reveal. */
    private fun setUpHomeUnderMemories(revealFraction: Float, onHomeGearTap: () -> Unit) {
        val container = ApplicationProvider.getApplicationContext<YoinApplication>().container
        // Infinite page loops (loading indicator, aurora) must not hold the
        // test clock hostage.
        rule.mainClock.autoAdvance = false
        rule.setContent {
            YoinTheme {
                Box(modifier = Modifier.fillMaxSize()) {
                    // Stand-in for Home's top-end settings gear.
                    Box(
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .size(48.dp)
                            .testTag(HOME_GEAR_TAG)
                            .clickable(onClick = onHomeGearTap),
                    )
                    val revealState = rememberRevealState(initialFraction = revealFraction)
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .graphicsLayer {
                                translationY = -revealState.fraction * size.height
                            },
                    ) {
                        MemoriesScreen(
                            viewModel = viewModel(factory = MemoriesViewModel.Factory(container)),
                            revealState = revealState,
                            onDismissed = {},
                            onPlayMemoryTrack = { _, _ -> },
                            onOpenAlbum = {},
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                }
            }
        }
        rule.mainClock.advanceTimeBy(FRAME_SETTLE_MS)
    }

    private companion object {
        const val HOME_GEAR_TAG = "home_settings_gear"
        const val FRAME_SETTLE_MS = 64L
    }
}
