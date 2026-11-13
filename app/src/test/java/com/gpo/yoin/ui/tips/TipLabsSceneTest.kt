package com.gpo.yoin.ui.tips

import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.height
import androidx.compose.ui.unit.width
import androidx.test.core.app.ApplicationProvider
import com.gpo.yoin.R
import com.gpo.yoin.ui.theme.YoinTheme
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The Yoin Labs tip plays on the real Labs page, and its dot taps the lyric cards' switch at the bottom. A row added
 * to the page above it (Gemini Nano diagnostics, 2026-11-12) moves the switch down the little phone's fixed screen.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w412dp-h900dp")
class TipLabsSceneTest {
    @get:Rule
    val rule = createComposeRule()

    @Test
    fun should_keepTheSwitchRowOnTheLittlePhone_when_labsShowsTheDiagnosticsRow() {
        rule.setContent { YoinTheme { LabsDemo(playing = false) } }
        rule.waitForIdle()

        val resources = ApplicationProvider.getApplicationContext<android.content.Context>().resources
        rule.onNodeWithText(resources.getString(R.string.nano_diag_title), useUnmergedTree = true).assertExists()
        val phone = rule.onRoot().getUnclippedBoundsInRoot()
        // The screen is laid out 360dp wide and drawn scaled to the phone: a node's place is scaled, its size isn't.
        val scale = phone.width.value / ScreenWidth.value
        val summary = rule
            .onNodeWithText(resources.getString(R.string.labs_lyrics_detail), useUnmergedTree = true)
            .getUnclippedBoundsInRoot()
        val rowBottom = summary.top.value + (summary.height.value + RowBottomPaddingDp) * scale
        assertTrue("the lyric cards row ends at $rowBottom of ${phone.bottom.value}dp", rowBottom <= phone.bottom.value)
    }

    private companion object {
        /** SettingsItem's own vertical padding under its text. */
        const val RowBottomPaddingDp = 14f
    }
}
