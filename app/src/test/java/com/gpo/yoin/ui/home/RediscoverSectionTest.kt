package com.gpo.yoin.ui.home

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.gpo.yoin.data.model.MediaId
import com.gpo.yoin.data.model.Track
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

/** Rediscover's shelf with an album and songs: what each card shows and where its tap goes. */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w412dp-h915dp")
class RediscoverSectionTest {

    @get:Rule
    val rule = createComposeRule()

    private val played = mutableListOf<Track>()
    private val opened = mutableListOf<String>()
    private val haptics = mutableListOf<HapticFeedbackType>()

    private fun setShelf(items: List<HomeRediscoverItem>) {
        rule.setContent {
            YoinTheme {
                CompositionLocalProvider(
                    LocalHapticFeedback provides object : HapticFeedback {
                        override fun performHapticFeedback(hapticFeedbackType: HapticFeedbackType) {
                            haptics += hapticFeedbackType
                        }
                    },
                ) {
                    ProvidePreviewWindow(widthDp = 412, heightDp = 915) {
                        // The feed's frame, measured the way the feed measures it (as the previews do).
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
                                onAlbumClick = { albumId, _ -> opened += albumId },
                                onSongClick = { song -> played += song },
                            )
                        }
                    }
                }
            }
        }
        rule.waitForIdle()
    }

    @Test
    fun should_playTheSongAlone_when_songCardTapped() {
        val song = song("s1", score = 8.5f)
        setShelf(listOf(song))

        rule.onNodeWithText("Ena", useUnmergedTree = true).performClick()
        rule.waitForIdle()

        assertEquals(listOf(song.song), played)
        assertTrue(opened.isEmpty())
        assertTrue("a song card's tap is silent", haptics.isEmpty())
    }

    @Test
    fun should_openTheAlbum_when_albumCardTapped() {
        setShelf(listOf(album(), song("s1", score = 8.5f)))

        rule.onNodeWithText("Carly Rae Jepsen", useUnmergedTree = true).performClick()
        rule.waitForIdle()

        assertEquals(listOf("subsonic:a1"), opened)
        assertTrue(played.isEmpty())
    }

    @Test
    fun should_badgeTheRating_when_songIsScored() {
        setShelf(listOf(song("s1", score = 8.5f, note = "not shown beside a score")))

        rule.onNodeWithContentDescription("Your rating 8.5", useUnmergedTree = true).assertExists()
        rule.onNodeWithText("not shown beside a score", useUnmergedTree = true).assertDoesNotExist()
    }

    @Test
    fun should_showOneLineOfTheNote_when_songHasOnlyANote() {
        setShelf(listOf(song("s1", score = null, note = "the drums come in late")))

        rule.onNodeWithText("the drums come in late", useUnmergedTree = true).assertExists()
        rule.onNodeWithText("7 months away", useUnmergedTree = true).assertExists()
    }

    @Test
    fun should_labelTheTapPlay_when_talkBackReadsASongCard() {
        setShelf(listOf(song("s1", score = 8.5f)))

        rule.onNode(
            SemanticsMatcher("onClick labelled Play") { node ->
                node.config.getOrElseNullable(SemanticsActions.OnClick) { null }?.label == "Play"
            },
        ).assertExists()
    }

    private fun album() = HomeRediscoverItem(
        albumId = MediaId.subsonic("a1"),
        albumName = "Emotion",
        artistName = "Carly Rae Jepsen",
        coverArtUrl = null,
        score = 9f,
        scoreText = rediscoverScoreText(9f),
        scoreKind = MemoryScoreKind.ALBUM_RATING,
        lastPlayedAt = NOW - 214 * DAY,
        firstPlayedAt = null,
        playCount = 4,
    )

    private fun song(id: String, score: Float?, note: String? = null) = HomeRediscoverItem(
        albumId = MediaId.subsonic("album-$id"),
        albumName = "Long Way Round",
        artistName = "Ena",
        coverArtUrl = null,
        score = score,
        scoreText = score?.let(::rediscoverScoreText),
        scoreKind = if (score == null) MemoryScoreKind.NONE else MemoryScoreKind.ALBUM_RATING,
        lastPlayedAt = NOW - 214 * DAY,
        firstPlayedAt = null,
        playCount = 3,
        noteCount = if (note != null) 1 else 0,
        song = Track(
            id = MediaId.subsonic(id),
            title = "Paper Kites",
            artist = "Ena",
            artistId = null,
            album = "Long Way Round",
            albumId = MediaId.subsonic("album-$id"),
            coverArt = null,
            durationSec = 200,
            trackNumber = null,
            year = null,
            genre = null,
            userRating = null,
        ),
        noteSnippet = note,
    )

    private companion object {
        const val NOW = 1_800_000_000_000L
        const val DAY = 24L * 60 * 60 * 1000
    }
}
