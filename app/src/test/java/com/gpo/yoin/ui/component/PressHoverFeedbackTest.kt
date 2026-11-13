package com.gpo.yoin.ui.component

import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.HoverInteraction
import androidx.compose.foundation.interaction.Interaction
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Hover feedback rides the interaction source a card's clickable already
 * shares with [elasticPress]: a mouse or trackpad pointer lifts it, a press
 * still wins, and a finger never hovers, so touch is unchanged.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w400dp-h800dp")
class PressHoverFeedbackTest {

    @get:Rule
    val rule = createComposeRule()

    @Test
    fun should_liftQuietly_when_hoveredAndNotPressed() {
        val scale = pressHoverScale(pressed = false, hovered = true, pressedScale = 0.97f)
        assertEquals(HoverLiftScale, scale)
        assertTrue(scale > 1f && scale - 1f < 1f - 0.97f)
    }

    @Test
    fun should_pressWinOverHover_when_hoveredAndPressed() {
        assertEquals(0.97f, pressHoverScale(pressed = true, hovered = true, pressedScale = 0.97f))
    }

    @Test
    fun should_rest_when_neitherHoveredNorPressed() {
        assertEquals(1f, pressHoverScale(pressed = false, hovered = false, pressedScale = 0.97f))
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun should_reportHoverToTheSharedSource_when_mouseEntersCard() {
        val interactions = mutableListOf<Interaction>()
        rule.setContent { HoverCard(interactions) }

        rule.onNodeWithTag(CardTag).performMouseInput { enter(center) }
        rule.waitForIdle()

        assertTrue(interactions.any { it is HoverInteraction.Enter })
    }

    @Test
    fun should_neverHover_when_touched() {
        val interactions = mutableListOf<Interaction>()
        rule.setContent { HoverCard(interactions) }

        rule.onNodeWithTag(CardTag).performClick()
        rule.waitForIdle()

        assertTrue(interactions.none { it is HoverInteraction })
    }

    @Composable
    private fun HoverCard(interactions: MutableList<Interaction>) {
        val source = remember { MutableInteractionSource() }
        LaunchedEffect(source) { source.interactions.collect { interactions += it } }
        Box(
            Modifier
                .size(120.dp)
                .testTag(CardTag)
                .elasticPress(source)
                .clickable(interactionSource = source, indication = null) {},
        )
    }

    private companion object {
        const val CardTag = "card"
    }
}
