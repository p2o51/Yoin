package com.gpo.yoin.ui.home

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import com.gpo.yoin.data.model.MediaId
import com.gpo.yoin.ui.experience.LocalYoinWindowInfo
import com.gpo.yoin.ui.experience.ProvidePreviewWindow
import com.gpo.yoin.ui.experience.feedFrameClass
import com.gpo.yoin.ui.memories.MemoryScoreKind
import com.gpo.yoin.ui.theme.YoinTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Rediscover's cards keep the same lines across a row (device QA 2026-10-05, phone width): one
 * card's eyebrow wrapping to two lines no longer leaves its title and artist lower than its
 * neighbour's — every card reserves the row's tallest eyebrow, the copy kept whole.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w412dp-h915dp")
class RediscoverEyebrowLinesTest {

    @get:Rule
    val rule = createComposeRule()

    private var items by mutableStateOf(emptyList<HomeRediscoverItem>())

    private fun setShelf(first: List<HomeRediscoverItem>) {
        items = first
        rule.setContent {
            YoinTheme {
                ProvidePreviewWindow(widthDp = 412, heightDp = 915) {
                    val frame = rememberHomeFeedFrame(feedFrameClass(LocalYoinWindowInfo.current))
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .feedFrameWidth(frame)
                            .padding(FeedFrameSidePadding(frame)),
                    ) {
                        RediscoverSection(
                            items = items,
                            frame = frame,
                            nowMillis = NOW,
                            extractBackdropColors = false,
                            onAlbumClick = { _, _ -> },
                            onSongClick = {},
                        )
                    }
                }
            }
        }
        rule.waitForIdle()
    }

    /** A text's own line box, unclipped (the phone shelf's second card runs past the screen): top to bottom, dp. */
    private fun lines(text: String): ClosedFloatingPointRange<Float> {
        val node = rule.onNodeWithText(text, useUnmergedTree = true).fetchSemanticsNode()
        val density = rule.density.density
        val top = node.positionInRoot.y / density
        return top..(top + node.size.height / density)
    }

    private fun top(text: String): Float = lines(text).start

    private val ClosedFloatingPointRange<Float>.height: Float get() = endInclusive - start

    @Test
    fun should_lineUpEveryCardsTitle_when_oneEyebrowWrapsToTwoLines() {
        setShelf(listOf(paperLetters, glacierAvenue(score = 8.1f)))
        val oneLineArtist = top("Artist One")
        assertEquals(oneLineArtist, top("Artist Two"), .5f)

        // Device QA's card: no score, so its eyebrow says what was kept first.
        items = listOf(paperLetters, glacierAvenue(score = null, notes = 1))
        rule.waitForIdle()

        val reasonTop = top("note")
        val wrappedAway = lines("8 months away")
        // The note count sits on its own line above the away label.
        assertTrue(
            "the reason line sits above the away label: $reasonTop..${wrappedAway.endInclusive}",
            wrappedAway.endInclusive - reasonTop >= 1.5f * SingleLine,
        )
        // Both cards keep that second line: the one-line eyebrow sits on the slot's bottom, against
        // the title, so the away labels end and the artists start at the same height on both cards.
        assertEquals(wrappedAway.endInclusive, lines("7 months away").endInclusive, .5f)
        assertEquals(top("Artist Two"), top("Artist One"), .5f)
        // The one-line card took the second line too: its text sits lower than in a one-line row.
        assertTrue(top("Artist One") > oneLineArtist + 1f)
    }

    @Test
    fun should_keepOneLineEyebrows_when_noEyebrowInTheRowWraps() {
        setShelf(listOf(paperLetters, glacierAvenue(score = 8.1f)))

        val first = lines("7 months away")
        val second = lines("8 months away")
        assertTrue("one line: $first", first.height < 1.5f * SingleLine)
        assertEquals(first.height, second.height, .5f)
        assertEquals(first.start, second.start, .5f)
        assertEquals(top("Artist Two"), top("Artist One"), .5f)
    }

    private val paperLetters = album("a1", "Paper Letters", "Artist One", score = 9.2f, daysAway = 214)

    private fun glacierAvenue(score: Float?, notes: Int = 0) =
        album("a2", "Glacier Avenue", "Artist Two", score = score, daysAway = 250, notes = notes)

    private fun album(
        id: String,
        name: String,
        artist: String,
        score: Float?,
        daysAway: Long,
        notes: Int = 0,
    ) = HomeRediscoverItem(
        albumId = MediaId.subsonic(id),
        albumName = name,
        artistName = artist,
        coverArtUrl = null,
        score = score,
        scoreText = score?.let(::rediscoverScoreText),
        scoreKind = if (score == null) MemoryScoreKind.NONE else MemoryScoreKind.ALBUM_RATING,
        lastPlayedAt = NOW - daysAway * DAY,
        firstPlayedAt = NOW - (daysAway + 90) * DAY,
        playCount = 1,
        noteCount = notes,
    )

    private companion object {
        const val NOW = 1_800_000_000_000L
        const val DAY = 24L * 60 * 60 * 1000

        /** labelMedium's line height, dp. */
        const val SingleLine = 16f
    }
}
