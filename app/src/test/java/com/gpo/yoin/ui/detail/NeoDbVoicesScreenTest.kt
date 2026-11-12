package com.gpo.yoin.ui.detail

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.width
import com.gpo.yoin.data.integration.neodb.NeoDbAlbumVoices
import com.gpo.yoin.data.integration.neodb.NeoDbVoice
import com.gpo.yoin.ui.component.YoinPageWidths
import com.gpo.yoin.ui.theme.YoinTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The "别人怎么说" page as it is laid out and tapped: the spread's labels, the page's column on a tablet, the cards. */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w412dp-h900dp")
class NeoDbVoicesScreenTest {

    @get:Rule
    val rule = createComposeRule()

    private val opened = mutableListOf<String>()
    private var retries = 0

    private fun show(state: NeoDbVoicesUiState) {
        rule.setContent {
            YoinTheme {
                NeoDbVoicesScreen(
                    uiState = state,
                    albumName = "OK Computer",
                    artistName = "Radiohead",
                    onBackClick = {},
                    onRetry = { retries++ },
                    onLoadMore = {},
                    onOpenUrl = { opened += it },
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
        rule.waitForIdle()
    }

    @Test
    fun should_keepEveryColumnsLabelsOnOneLine_when_oneBarIsTheTallest() {
        show(content())

        val labels = listOf("3%", "5%", "12%", "30%", "50%").map { percent ->
            rule.onNodeWithText(percent, useUnmergedTree = true).getUnclippedBoundsInRoot()
        }

        // The tallest bar (50%) doesn't push its own labels down or squeeze them.
        assertEquals(1, labels.map { it.top }.distinct().size)
        assertEquals(1, labels.map { it.bottom }.distinct().size)
    }

    @Test
    @Config(qualifiers = "w1280dp-h800dp")
    fun should_keepThePageColumn_when_theWindowIsATabletsWidth() {
        show(content(voices = listOf(voice(id = "long", text = LONG_WORDS))))

        assertTrue(rule.onRoot().getUnclippedBoundsInRoot().width > YoinPageWidths.Feed)
        val card = rule.onNodeWithText(LONG_WORDS).getUnclippedBoundsInRoot()
        val wayOut = rule.onNodeWithText("See all on NeoDB").getUnclippedBoundsInRoot()
        // Both sit in the page's column, inside its 16dp gutters, not across the window.
        val column = (YoinPageWidths.Feed - 32.dp).value
        assertEquals(column, card.width.value, 0.5f)
        assertEquals(column, wayOut.width.value, 0.5f)
        assertEquals(card.left.value, wayOut.left.value, 0.5f)
    }

    @Test
    fun should_unfoldThenOpen_when_aFoldedVoiceIsTappedTwice() {
        show(
            content(
                voices = listOf(
                    voice(id = "folded", text = "The last track", folded = true, foldNote = "Spoilers", url = POST_URL),
                ),
            ),
        )
        rule.onNodeWithText("Spoilers").assertIsDisplayed()
        rule.onNodeWithText("The last track").assertDoesNotExist()

        rule.onNodeWithText("Spoilers").performClick()
        rule.waitForIdle()

        rule.onNodeWithText("The last track").assertIsDisplayed()
        assertTrue(opened.isEmpty())

        rule.onNodeWithText("The last track").performClick()
        rule.waitForIdle()

        assertEquals(listOf(POST_URL), opened)
    }

    @Test
    fun should_retry_when_neoDbCouldNotBeReached() {
        show(NeoDbVoicesUiState.Failed)

        rule.onNodeWithText("Couldn't reach NeoDB.").assertIsDisplayed()
        rule.onNodeWithText("Retry").performClick()
        rule.waitForIdle()

        assertEquals(1, retries)
    }

    @Test
    fun should_offerTheSearch_when_neoDbHasNoSuchAlbum() {
        show(NeoDbVoicesUiState.NotFound(searchUrl = SEARCH_URL))

        rule.onNodeWithText("NeoDB doesn't have this album.").assertIsDisplayed()
        // No item: nothing to "see all" of, only the search and the other catalogues.
        rule.onNodeWithText("See all on NeoDB").assertDoesNotExist()
        rule.onNodeWithText("Search NeoDB").performClick()
        rule.waitForIdle()

        assertEquals(listOf(SEARCH_URL), opened)
    }

    private fun content(voices: List<NeoDbVoice> = emptyList()) = NeoDbVoicesUiState.Content(
        album = NeoDbAlbumVoices(
            itemUuid = "main",
            itemUrl = "https://neodb.social/album/main",
            title = "OK Computer",
            rating = 9.1,
            ratingCount = 510,
            distribution = listOf(3, 5, 12, 30, 50),
            doubanUrl = null,
            discogsUrl = null,
            musicBrainzUrl = null,
        ),
        voices = voices,
        count = voices.size,
        references = emptyList(),
        endReached = true,
    )

    private fun voice(
        id: String,
        text: String,
        folded: Boolean = false,
        foldNote: String? = null,
        url: String? = null,
    ) = NeoDbVoice(
        id = id,
        author = "Listener",
        handle = "listener",
        avatarUrl = null,
        rating = 9,
        text = text,
        reviewTitle = null,
        folded = folded,
        foldNote = foldNote,
        url = url,
        createdAtMillis = null,
    )

    private companion object {
        const val POST_URL = "https://neodb.social/@listener/posts/1/"
        const val SEARCH_URL = "https://neodb.social/search?c=music&q=OK+Computer+Radiohead"
        const val LONG_WORDS = "Twenty-five years on and it still sounds like the future arriving a little too early."
    }
}
