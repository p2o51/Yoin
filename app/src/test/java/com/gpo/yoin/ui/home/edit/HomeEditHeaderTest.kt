package com.gpo.yoin.ui.home.edit

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.gpo.yoin.symbols.YoinSymbols
import com.gpo.yoin.ui.theme.YoinTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** The header through P: constant height, no width for "Edit Home", the hint only where it fits beside it. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w800dp-h800dp")
class HomeEditHeaderTest {

    @get:Rule
    val rule = createComposeRule()

    private var progress by mutableFloatStateOf(0f)
    private val p: () -> Float = { progress }

    // Home's header row (HomeContentHeader): title + 24dp breathing, the free
    // span, 2dp, then the 48dp Settings button.
    private fun setHeader(width: Dp, hint: Boolean = true) {
        rule.setContent {
            YoinTheme {
                val style = MaterialTheme.typography.headlineLarge
                val iconsEnabled = rememberHomeEditIconsEnabled(p)
                Row(
                    modifier = Modifier
                        .width(width)
                        .testTag(Header),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    HomeEditHeaderTitle(
                        progress = p,
                        style = style,
                        modifier = Modifier
                            .testTag(Title)
                            .padding(end = 24.dp),
                    )
                    Box(Modifier.weight(1f), contentAlignment = Alignment.CenterEnd) {
                        HomeEditHeaderHint(progress = p, visible = hint, titleStyle = style)
                    }
                    Spacer(Modifier.width(2.dp))
                    IconButton(onClick = {}, enabled = iconsEnabled, modifier = Modifier.homeEditHeaderIcon(p)) {
                        Icon(YoinSymbols.Settings, contentDescription = "Settings")
                    }
                }
            }
        }
    }

    private fun sweep(block: (Float) -> Unit) {
        for (step in 0..10) {
            val value = step / 10f
            rule.runOnIdle { progress = value }
            rule.waitForIdle()
            block(value)
        }
    }

    private fun placedNodes(text: String): List<SemanticsNode> =
        rule.onAllNodesWithText(text, useUnmergedTree = true).fetchSemanticsNodes().filter { it.layoutInfo.isPlaced }

    private fun headerHeight(): Dp = rule.onNodeWithTag(Header).getBoundsInRoot().let { it.bottom - it.top }

    private fun titleWidth(): Dp = rule.onNodeWithTag(Title).getBoundsInRoot().let { it.right - it.left }

    private fun hintShown(): Boolean = placedNodes(Hint).any { it.boundsInRoot.width > 0f }

    @Test
    fun should_keepHeaderHeightConstant_when_progressSweeps0To1() {
        setHeader(width = 600.dp)
        val rest = headerHeight()
        assertEquals(48.dp, rest)
        sweep { value ->
            assertEquals("header height at P $value", rest, headerHeight())
        }
    }

    @Test
    fun should_notChangeWidth_when_editTitleOverlayShown() {
        setHeader(width = 600.dp)
        val rest = titleWidth()
        assertTrue(placedNodes(EditTitle).isEmpty())

        sweep { value ->
            assertEquals("title width at P $value", rest, titleWidth())
        }
        // "Edit Home" is up, overhanging "Home" instead of widening it.
        val edit = placedNodes(EditTitle).single()
        val title = rule.onNodeWithTag(Title).getBoundsInRoot()
        assertTrue(edit.boundsInRoot.right > rule.density.run { (title.right - 24.dp).toPx() })
    }

    @Test
    fun should_hideHint_when_itDoesNotFit() {
        // Narrower than any phone's header: "Edit Home" leaves no room for the hint.
        setHeader(width = 280.dp)
        rule.runOnIdle { progress = 1f }
        rule.waitForIdle()
        assertFalse(hintShown())
    }

    @Test
    fun should_showHintClearOfEditTitle_when_phoneHeader() {
        // A 360dp phone's header (16dp margins): the prototype shows the hint here.
        setHeader(width = 328.dp)
        rule.runOnIdle { progress = 1f }
        rule.waitForIdle()
        assertTrue(hintShown())
        val hint = placedNodes(Hint).single().boundsInRoot
        val edit = placedNodes(EditTitle).single().boundsInRoot
        assertTrue(hint.left >= edit.right + rule.density.run { 16.dp.toPx() })
    }

    @Test
    fun should_showHintClearOfEditTitle_when_itFits() {
        setHeader(width = 600.dp)
        rule.runOnIdle { progress = 1f }
        rule.waitForIdle()
        assertTrue(hintShown())
        val hint = placedNodes(Hint).single().boundsInRoot
        val edit = placedNodes(EditTitle).single().boundsInRoot
        assertTrue(hint.left >= edit.right + rule.density.run { 16.dp.toPx() })
    }

    @Test
    fun should_reserveEditTitleOverhangPastTitlePadding_when_fittingHint() {
        // Fits on its own, not once 16dp and the part of "Edit Home"'s overhang past the
        // title's 24dp end padding are reserved (critique 16, the prototype's fit check).
        var hintPx = 0
        var overhangPx = 0
        var width by mutableFloatStateOf(0f)
        rule.setContent {
            YoinTheme {
                val style = MaterialTheme.typography.headlineLarge
                val measurer = rememberTextMeasurer()
                val label = MaterialTheme.typography.labelMedium
                hintPx = measurer.measure(Hint, label, maxLines = 1, softWrap = false).size.width
                overhangPx = measurer.measure(EditTitle, style, maxLines = 1, softWrap = false).size.width -
                    measurer.measure("Home", style, maxLines = 1, softWrap = false).size.width
                if (width > 0f) {
                    Box(Modifier.width(rule.density.run { width.toDp() })) {
                        HomeEditHeaderHint(progress = p, visible = true, titleStyle = style)
                    }
                }
            }
        }
        rule.runOnIdle { progress = 1f }
        val gap = rule.density.run { 16.dp.roundToPx() }
        val reserved = overhangPx - rule.density.run { 24.dp.roundToPx() }
        assertTrue(reserved > 0)

        rule.runOnIdle { width = (hintPx + gap + reserved - 1).toFloat() }
        rule.waitForIdle()
        assertFalse(hintShown())

        rule.runOnIdle { width = (hintPx + gap + reserved + 1).toFloat() }
        rule.waitForIdle()
        assertTrue(hintShown())
    }

    @Test
    fun should_composeNothing_when_hintNotVisible() {
        setHeader(width = 600.dp, hint = false)
        rule.runOnIdle { progress = 1f }
        rule.waitForIdle()
        assertTrue(placedNodes(Hint).isEmpty())
    }

    private companion object {
        const val Header = "header"
        const val Title = "title"
        const val EditTitle = "Edit Home"
        const val Hint = "Drag to reorder"
    }
}
