package com.gpo.yoin.ui.home

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import com.gpo.yoin.data.model.Album
import com.gpo.yoin.data.model.MediaId
import com.gpo.yoin.ui.experience.LayoutMode
import com.gpo.yoin.ui.experience.LocalMotionProfile
import com.gpo.yoin.ui.experience.LocalYoinWindowInfo
import com.gpo.yoin.ui.experience.MotionProfile
import com.gpo.yoin.ui.experience.ShellChromeForm
import com.gpo.yoin.ui.experience.YoinWindowInfo
import com.gpo.yoin.ui.memories.MemoryEntityType
import com.gpo.yoin.ui.memories.MemoryScoreKind
import com.gpo.yoin.ui.theme.YoinTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The real feed hands each section its covers' memory marks (owner
 * 2026-10-11, T4): Jump Back In's plain album covers, Recently Added's and
 * Recently Played's album cards read theirs out with the card; a cover with
 * no mark reads none.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w412dp-h3000dp")
class HomeAlbumMemoryMarkWiringTest {

    @get:Rule
    val rule = createComposeRule()

    @Test
    fun should_readEachSectionsMarks_when_thePhoneFeedIsUp() {
        setHome(window = null)

        assertMarks()
    }

    @Test
    @Config(qualifiers = "w800dp-h3000dp")
    fun should_readEachSectionsMarks_when_theTabletFeedIsUp() {
        setHome(
            window = YoinWindowInfo(
                layoutMode = LayoutMode.Medium,
                isWidthAtLeastMedium = true,
                isHeightAtLeastMedium = true,
                hingeBounds = null,
                chromeForm = ShellChromeForm.CenteredBar
            )
        )

        assertMarks()
    }

    private fun assertMarks() {
        // Jump Back In: the marked plain cover reads its mark; its bare neighbour reads none.
        "Cover 1".withDescription("Your rating 9.0").assertExists()
        rule.onNode(hasText("Cover 2")).assertExists()
        "Cover 2".withDescription("Your rating").assertDoesNotExist()
        // Recently Added and Recently Played album cards.
        "Added album".withDescription("Your rating 8.0, 2 notes").assertExists()
        "Played album".withDescription("Track average 7.0").assertExists()
        rule.onNode(hasText("Played bare")).assertExists()
        "Played bare".withDescription("Track average").assertDoesNotExist()
    }

    private fun String.withDescription(description: String): SemanticsNodeInteraction =
        rule.onNode(hasText(this) and hasContentDescription(description, substring = true))

    private fun setHome(window: YoinWindowInfo?) {
        rule.setContent {
            CompositionLocalProvider(
                LocalMotionProfile provides MotionProfile.AdaptiveReduced,
                LocalYoinWindowInfo provides (window ?: LocalYoinWindowInfo.current)
            ) {
                YoinTheme {
                    HomeEditorialContent(
                        activities = emptyList(),
                        widgetGrid = (1..28).map(::cover),
                        recentlyAddedAlbums = listOf(album("added", "Added album")),
                        recentlyPlayed = listOf(album("played", "Played album"), album("bare", "Played bare")),
                        albumMemoryMarks = marks,
                        onNavigateToSettings = {},
                        onNavigateToMemories = {},
                        onAlbumClick = { _, _ -> },
                        onArtistClick = {},
                        onPlaylistClick = {},
                        onSongClick = {},
                        buildCoverArtUrl = { "" }
                    )
                }
            }
        }
        rule.waitForIdle()
    }

    private fun cover(i: Int): HomeWidgetCard = HomeWidgetCard(
        stableId = "grid-album:subsonic:c$i",
        entityType = MemoryEntityType.ALBUM,
        title = "Cover $i",
        subtitle = "Artist",
        coverArtUrl = null,
        target = HomeWidgetTarget.AlbumDetail(MediaId.subsonic("c$i").toString())
    )

    private fun album(rawId: String, name: String): Album = Album(
        id = MediaId.subsonic(rawId),
        name = name,
        artist = "Artist",
        artistId = null,
        coverArt = null,
        songCount = 10,
        durationSec = null,
        year = null,
        genre = null
    )

    private companion object {
        val marks = mapOf(
            "c1" to HomeAlbumMemoryMark(MemoryScoreKind.ALBUM_RATING, "9.0", 0),
            "added" to HomeAlbumMemoryMark(MemoryScoreKind.ALBUM_RATING, "8.0", 2),
            "played" to HomeAlbumMemoryMark(MemoryScoreKind.AVERAGE_TRACK_RATING, "7.0", 0)
        )
    }
}
