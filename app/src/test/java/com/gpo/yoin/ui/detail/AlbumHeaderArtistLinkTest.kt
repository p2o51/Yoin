package com.gpo.yoin.ui.detail

import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.performClick
import com.gpo.yoin.ui.experience.ProvidePreviewWindow
import com.gpo.yoin.ui.theme.YoinTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Regression for the owner's 2026-11-27 report ("tapping the artist name under the album title doesn't open the
 * artist page", an Apple Music album): the header's artist name is a link to the page(s) in
 * [AlbumDetailUiState.Content.artistLinks], on the phone and the tablet layouts alike.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AlbumHeaderArtistLinkTest {

    @get:Rule
    val rule = createComposeRule()

    private fun content(artist: String, links: List<AlbumArtistLink>) = AlbumDetailUiState.Content(
        albumId = "applemusic:1440873498",
        albumName = "The College Dropout",
        artistName = artist,
        artistId = links.firstOrNull()?.id,
        artistLinks = links,
        coverArtId = null,
        coverArtUrl = null,
        year = 2004,
        songCount = 2,
        totalDuration = 76 * 60,
        songs = listOf(
            AlbumSong("1", "Intro", artist, 1, 19, false),
            AlbumSong("2", "We Don't Care", artist, 2, 239, false),
        ),
    )

    private fun show(content: AlbumDetailUiState.Content, widthDp: Int, heightDp: Int, opened: MutableList<String>) {
        // The page fades in through its enter intro: let it settle on the test clock.
        rule.mainClock.autoAdvance = false
        rule.setContent {
            YoinTheme {
                ProvidePreviewWindow(widthDp = widthDp, heightDp = heightDp) {
                    AlbumDetailScreen(
                        uiState = content,
                        onBackClick = {},
                        onSongClick = {},
                        onToggleStar = {},
                        onRetry = {},
                        onOpenArtistPage = { opened += it },
                    )
                }
            }
        }
        settle()
    }

    private fun settle() = rule.mainClock.advanceTimeBy(4_000L)

    @Config(qualifiers = "w393dp-h852dp")
    @Test
    fun should_openTheArtistPage_when_theHeadersArtistNameIsTappedOnAPhone() {
        val opened = mutableListOf<String>()
        show(content("Kanye West", listOf(AlbumArtistLink("Kanye West", "applemusic:2715720"))), 393, 852, opened)

        rule.onNode(hasText("Kanye West") and hasClickAction(), useUnmergedTree = true).performClick()

        assertEquals(listOf("applemusic:2715720"), opened)
    }

    @Config(qualifiers = "w800dp-h1280dp")
    @Test
    fun should_openTheArtistPage_when_theHeadersArtistNameIsTappedOnATablet() {
        val opened = mutableListOf<String>()
        show(content("Kanye West", listOf(AlbumArtistLink("Kanye West", "applemusic:library:r.4GZQ1aq"))), 800, 1280, opened)

        rule.onNode(hasText("Kanye West") and hasClickAction(), useUnmergedTree = true).performClick()

        assertEquals(listOf("applemusic:library:r.4GZQ1aq"), opened)
    }

    @Config(qualifiers = "w393dp-h852dp")
    @Test
    fun should_offerEachArtist_when_theAlbumIsCreditedToSeveral() {
        val opened = mutableListOf<String>()
        val links = listOf(
            AlbumArtistLink("JAY-Z", "spotify:3nFkdlSjzX9mRTtwJOzDYB"),
            AlbumArtistLink("Kanye West", "spotify:5K4W6rqBFWDnAN6FQUkS6x"),
        )
        show(content("JAY-Z", links), 393, 852, opened)

        rule.onNode(hasText("JAY-Z") and hasClickAction(), useUnmergedTree = true).performClick()
        settle()
        // Nothing opens until one is picked.
        assertEquals(emptyList<String>(), opened)
        // A menu row merges its text into its click target.
        rule.onNode(hasText("Kanye West") and hasClickAction()).performClick()

        assertEquals(listOf("spotify:5K4W6rqBFWDnAN6FQUkS6x"), opened)
    }

    @Config(qualifiers = "w393dp-h852dp")
    @Test
    fun should_leaveTheNamePlain_when_noArtistPageIsKnown() {
        val opened = mutableListOf<String>()
        show(content("Various Artists", emptyList()), 393, 852, opened)

        rule.onNode(hasText("Various Artists"), useUnmergedTree = true).assertExists()
        rule.onNode(hasText("Various Artists") and hasClickAction(), useUnmergedTree = true).assertDoesNotExist()
    }
}
