package com.gpo.yoin.ui.home

import androidx.compose.foundation.layout.size
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.unit.dp
import com.gpo.yoin.ui.memories.MemoryScoreKind
import com.gpo.yoin.ui.theme.YoinTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** The cover's memory mark: drawn on album covers only, read out by TalkBack, no visible words. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w411dp-h2000dp")
class HomeAlbumMemoryMarkStickerTest {

    @get:Rule
    val rule = createComposeRule()

    @Test
    fun should_readTheScoreAndNotes_when_anAlbumCoverWearsAMark() {
        show(WidgetShapeKind.Album, HomeAlbumMemoryMark(MemoryScoreKind.ALBUM_RATING, "9.0", 2))

        rule.onNodeWithContentDescription("Your rating 9.0, 2 notes").assertExists()
    }

    @Test
    fun should_readTheNotesAlone_when_theMarkHasNoScore() {
        show(WidgetShapeKind.Album, HomeAlbumMemoryMark(MemoryScoreKind.NONE, null, 1))

        rule.onNodeWithContentDescription("1 note").assertExists()
    }

    @Test
    fun should_drawNoMark_when_theCoverIsNotAnAlbum() {
        show(WidgetShapeKind.Playlist, HomeAlbumMemoryMark(MemoryScoreKind.ALBUM_RATING, "9.0", 0))

        rule.onAllNodesWithContentDescription("Your rating", substring = true).assertCountEquals(0)
    }

    @Test
    fun should_addTheMark_when_itLandsAfterTheCover() {
        var mark by mutableStateOf<HomeAlbumMemoryMark?>(null)
        rule.setContent {
            YoinTheme {
                WidgetBackdropArtwork(
                    model = null,
                    kind = WidgetShapeKind.Album,
                    contentDescription = "Album",
                    extractBackdropColors = false,
                    modifier = Modifier.size(100.dp),
                    memoryMark = mark
                )
            }
        }
        rule.onAllNodesWithContentDescription("Track average", substring = true).assertCountEquals(0)

        mark = HomeAlbumMemoryMark(MemoryScoreKind.AVERAGE_TRACK_RATING, "7.4", 0)
        rule.waitForIdle()
        rule.onNodeWithContentDescription("Track average 7.4").assertExists()

        // Gone again (the rating was cleared): no longer read out.
        mark = null
        rule.waitForIdle()
        rule.onAllNodesWithContentDescription("Track average", substring = true).assertCountEquals(0)
    }

    private fun show(kind: WidgetShapeKind, mark: HomeAlbumMemoryMark?) {
        rule.setContent {
            YoinTheme {
                WidgetBackdropArtwork(
                    model = null,
                    kind = kind,
                    contentDescription = "Cover",
                    extractBackdropColors = false,
                    modifier = Modifier.size(100.dp),
                    memoryMark = mark
                )
            }
        }
        rule.waitForIdle()
    }
}
