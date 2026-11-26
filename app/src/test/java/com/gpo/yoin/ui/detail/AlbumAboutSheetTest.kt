package com.gpo.yoin.ui.detail

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.gpo.yoin.shared.albumabout.AlbumAboutAsk
import com.gpo.yoin.ui.theme.YoinTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w400dp-h900dp")
class AlbumAboutSheetTest {
    @get:Rule
    val rule = createComposeRule()

    private val edits = mutableListOf<ScrapEdit>()

    private fun sheet(about: ScrapPiece.AlbumAbout, notice: String? = null) {
        rule.setContent {
            YoinTheme(darkTheme = false) {
                AlbumAboutSheetBody(
                    albumName = "Harbor Lights",
                    about = about,
                    credits = emptyList(),
                    colors = rememberScrapbookColors(MaterialTheme.colorScheme),
                    gemini = ScrapGemini(),
                    onEdit = { edits += it },
                    notice = notice,
                )
            }
        }
    }

    @Test
    fun should_offerToGenerate_andSayWhenNothingCameBack() {
        sheet(ScrapPiece.AlbumAbout(text = null, pending = false, tilt = 0f), notice = "Gemini had nothing new")

        rule.onNodeWithText("Generate with Gemini").performClick()
        rule.onNodeWithText("Gemini had nothing new").assertIsDisplayed()
        assertEquals(listOf<ScrapEdit>(ScrapEdit.RegenerateAlbumAbout), edits)
    }

    @Test
    fun should_showTheAlbumsQuestion_onThePaper_whenThereIsNoParagraph() {
        val piece = ScrapPiece.AlbumAbout(
            text = null,
            pending = false,
            tilt = 0f,
            asks = listOf(AlbumAboutAsk("Who played the organ?", "The organ", "A borrowed pump organ.")),
        )
        var opened = 0
        rule.setContent {
            YoinTheme(darkTheme = false) {
                AlbumAboutCard(
                    piece = piece,
                    albumName = "Harbor Lights",
                    colors = rememberScrapbookColors(MaterialTheme.colorScheme),
                    onClick = { opened++ },
                )
            }
        }

        // The Ask index card songs get, not the dashed circle.
        rule.onNodeWithContentDescription("Asked about Harbor Lights: The organ").assertIsDisplayed().performClick()
        assertEquals(1, opened)
    }

    @Test
    fun should_keepTheDashedCircle_whenThereIsNeitherParagraphNorQuestion() {
        var opened = 0
        rule.setContent {
            YoinTheme(darkTheme = false) {
                AlbumAboutCard(
                    piece = ScrapPiece.AlbumAbout(text = null, pending = false, tilt = 0f),
                    albumName = "Harbor Lights",
                    colors = rememberScrapbookColors(MaterialTheme.colorScheme),
                    onClick = { opened++ },
                )
            }
        }

        rule.onNodeWithContentDescription("About").performClick()
        assertEquals(1, opened)
    }
}
