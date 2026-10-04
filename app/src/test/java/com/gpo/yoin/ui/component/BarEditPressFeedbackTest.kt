package com.gpo.yoin.ui.component

import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.CornerBasedShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.IconButtonShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.gpo.yoin.ui.theme.YoinTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The bar's edit pose press feedback: the fading pill takes no tap, and the
 * edit buttons' press shape morph (the tablet's visual twin of the edit
 * haptics) never touches a normal-mode press.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@RunWith(RobolectricTestRunner::class)
class BarEditPressFeedbackTest {

    @get:Rule
    val rule = createComposeRule()

    @Test
    fun should_skipClick_when_pillDisabled() {
        var clicks = 0
        var enabled by mutableStateOf(false)
        rule.setContent {
            YoinTheme {
                NowPlayingPill(
                    currentTrackId = "1",
                    currentTrackTitle = "Pang",
                    currentTrackArtist = "Caroline Polachek",
                    currentTrackCoverArtUrl = null,
                    connectionErrorMessage = null,
                    playbackProgress = 0.3f,
                    isPlaying = false,
                    onClick = { clicks++ },
                    enabled = enabled,
                    modifier = Modifier
                        .size(width = 240.dp, height = 48.dp)
                        .testTag(PillTag),
                )
            }
        }

        rule.onNodeWithTag(PillTag).performClick()
        rule.runOnIdle { assertEquals(0, clicks) }

        enabled = true
        rule.onNodeWithTag(PillTag).performClick()
        rule.runOnIdle { assertEquals(1, clicks) }
    }

    @Test
    fun should_keepTheRestingShape_when_pressedOutsideEdit() {
        val rest = RoundedCornerShape(28.dp)
        val source = MutableInteractionSource()
        lateinit var shape: Shape
        rule.setContent {
            YoinTheme {
                shape = rememberBarEditPressShape(rest = rest, interactionSource = source, active = false)
            }
        }

        val press = PressInteraction.Press(Offset.Zero)
        rule.runOnIdle { source.tryEmit(press) }
        rule.runOnIdle { assertSame(rest, shape) }
        rule.runOnIdle { source.tryEmit(PressInteraction.Release(press)) }
        rule.runOnIdle { assertSame(rest, shape) }
    }

    @Test
    fun should_squareOffTheCorners_when_pressedWhileEditing() {
        val rest = RoundedCornerShape(28.dp)
        val source = MutableInteractionSource()
        lateinit var shape: Shape
        lateinit var pressed: CornerBasedShape
        rule.setContent {
            YoinTheme {
                pressed = MaterialTheme.shapes.small
                shape = rememberBarEditPressShape(rest = rest, interactionSource = source, active = true)
            }
        }
        val pressedRadius = pressed.topStart.toPx(ButtonSize, UnitDensity)

        // At rest it draws exactly the resting outline (28dp capped at the 24dp round end).
        rule.runOnIdle {
            assertNotSame(rest, shape)
            assertEquals(ButtonSize.height / 2f, cornerRadius(shape), 0.01f)
        }
        val press = PressInteraction.Press(Offset.Zero)
        rule.runOnIdle { source.tryEmit(press) }
        rule.runOnIdle { assertEquals(pressedRadius, cornerRadius(shape), 0.01f) }
        rule.runOnIdle { source.tryEmit(PressInteraction.Release(press)) }
        rule.runOnIdle { assertEquals(ButtonSize.height / 2f, cornerRadius(shape), 0.01f) }
    }

    @Test
    fun should_morphOnlyWhileEditing_when_resolvingLeftSlotShapes() {
        lateinit var normal: IconButtonShapes
        lateinit var editing: IconButtonShapes
        lateinit var small: CornerBasedShape
        rule.setContent {
            YoinTheme {
                small = MaterialTheme.shapes.small
                normal = barEditIconButtonShapes(CircleShape, pressMorph = false)
                editing = barEditIconButtonShapes(CircleShape, pressMorph = true)
            }
        }

        rule.runOnIdle {
            assertEquals(CircleShape, normal.shape)
            assertEquals(CircleShape, normal.pressedShape)
            assertEquals(CircleShape, editing.shape)
            assertEquals(small, editing.pressedShape)
        }
    }

    private fun cornerRadius(shape: Shape): Float {
        val outline = shape.createOutline(ButtonSize, LayoutDirection.Ltr, UnitDensity) as Outline.Rounded
        return outline.roundRect.topLeftCornerRadius.x
    }

    private companion object {
        const val PillTag = "pill"
        val ButtonSize = Size(120f, 48f)
        val UnitDensity = Density(1f)
    }
}
