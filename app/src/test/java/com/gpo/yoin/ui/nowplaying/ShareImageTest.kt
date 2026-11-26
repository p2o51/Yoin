package com.gpo.yoin.ui.nowplaying

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.unit.dp
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import com.gpo.yoin.ui.theme.YoinTheme
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The images' feet (owner device check 2026-11-25): a Note story's 「写于 …」 keeps its distance from the
 * mark and is never cut, even zoomed in; About is signed by the AI only when the AI wrote something on it.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "en-w400dp-h800dp")
class ShareImageTest {

    @get:Rule
    val rule = createComposeRule()

    private var shown by mutableStateOf<Triple<ShareContent, ShareLayout, LyricsShareFormat>?>(null)
    private var started = false

    private fun draw(content: ShareContent, layout: ShareLayout, format: LyricsShareFormat) {
        if (!started) {
            started = true
            rule.setContent {
                YoinTheme {
                    shown?.let { (c, l, f) -> ShareImage(content = c, layout = l, format = f, tone = ShareTone.Soft) }
                }
            }
        }
        rule.runOnIdle { shown = Triple(content, layout, format) }
        rule.waitForIdle()
    }

    /** The text's own (unclipped) box lies inside the image: nothing of it is cut off at the card's edge. */
    private fun assertInsideImage(text: String) {
        val image = rule.onNodeWithTag(ShareImageTag).getBoundsInRoot()
        val line = rule.onNode(hasText(text, substring = true)).getUnclippedBoundsInRoot()
        assertTrue("“$text” ends at ${line.right}, image at ${image.right}", line.right <= image.right)
        assertTrue("“$text” starts at ${line.left}", line.left >= image.left)
    }

    @Test
    fun should_spaceAndKeepTheWholeDate_when_aNoteStoryIsZoomedIn() {
        for (layout in listOf(ShareLayout.NoteBig, ShareLayout.NoteTimeline)) {
            draw(PreviewShareContent, layout, LyricsShareFormat.Story)
            val brand = rule.onNodeWithText("Yoin").getBoundsInRoot()
            val sign = rule.onNodeWithTag(ShareSignTag).getBoundsInRoot()
            // At least the 16dp gap between "Yoin" and the sign (more once the story zooms in).
            assertTrue("$layout: sign at ${sign.left}, brand ends ${brand.right}", sign.left - brand.right >= 16.dp)
            assertInsideImage("Written")
            // The sign's text stays inside its own slot (wrapping, never running under the mark).
            val written = rule.onNode(hasText("Written", substring = true)).getUnclippedBoundsInRoot()
            assertTrue("$layout: text starts ${written.left}, slot ${sign.left}", written.left >= sign.left - 0.5.dp)
        }
    }

    @Test
    fun should_signTheAi_only_when_theImageCarriesItsWords() {
        val creditsOnly = PreviewShareContent.copy(
            about = AboutSharePick(
                credits = listOf(ShareCredit("Composer", "Wu Qingfeng"), ShareCredit("Year", "2009", fact = true)),
                heading = "About",
                paragraph = null,
            ),
        )
        draw(creditsOnly, ShareLayout.AboutReceipt, LyricsShareFormat.Card)
        rule.onNode(hasText("By ", substring = true)).assertDoesNotExist()

        draw(PreviewShareContent, ShareLayout.AboutReceipt, LyricsShareFormat.Card)
        rule.onNode(hasText("By Gemini")).assertExists()
    }

    @Test
    fun should_setTheAisBoldBold_notPrintItsAsterisks_onEveryAboutLayout() {
        // iOS #352: the AI's **bold** printed as literal asterisks. Each About layout draws it as NP's About page does.
        val bold = PreviewShareContent.copy(
            about = AboutSharePick(
                credits = listOf(ShareCredit("Producer", "**Jay Chou**", ai = true)),
                heading = "About",
                paragraph = "Written for his **grandmother** in 2003.",
            ),
        )
        for (layout in listOf(ShareLayout.AboutReceipt, ShareLayout.AboutQuote, ShareLayout.AboutLiner)) {
            draw(bold, layout, LyricsShareFormat.Card)
            rule.onNode(hasText("**", substring = true)).assertDoesNotExist()
            rule.onNode(hasText("Written for his grandmother in 2003.")).assertExists()
            rule.onNode(hasText("Jay Chou")).assertExists()
        }
    }

    @Test
    fun should_drawEveryStory_when_theNoteHasAMentionChip() {
        // A mention's pill holds a SubcomposeLayout: the story's fit must not ask it for intrinsics.
        for (layout in ShareLayout.entries) {
            draw(RichShareContent, layout, LyricsShareFormat.Story)
            val image = rule.onNodeWithTag(ShareImageTag).getBoundsInRoot()
            assertTrue("$layout story is 640 tall", ((image.bottom - image.top) - 640.dp).value in -0.5f..0.5f)
        }
        draw(RichShareContent, ShareLayout.NoteBig, LyricsShareFormat.Story)
        rule.onNode(hasText("南北寻光", substring = true)).assertExists()
    }
}
