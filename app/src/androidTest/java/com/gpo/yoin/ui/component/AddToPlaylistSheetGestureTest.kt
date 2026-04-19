package com.gpo.yoin.ui.component

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performSemanticsAction
import com.gpo.yoin.data.model.MediaId
import com.gpo.yoin.ui.samplePlaylist
import com.gpo.yoin.ui.theme.YoinTheme
import org.junit.Rule
import org.junit.Test

class AddToPlaylistSheetGestureTest {

    @get:Rule
    val rule = createComposeRule()

    @Test
    fun long_press_on_song_row_opens_add_to_playlist_sheet() {
        rule.setContent {
            var showSheet by mutableStateOf(false)
            YoinTheme {
                SongListItem(
                    title = "Gesture Song",
                    artist = "Gesture Artist",
                    album = "Gesture Album",
                    durationSeconds = 180,
                    coverArtUrl = null,
                    onClick = {},
                    onLongClick = { showSheet = true },
                )
                if (showSheet) {
                    AddToPlaylistSheet(
                        writablePlaylists = listOf(
                            AddToPlaylistRow(
                                id = MediaId.spotify("playlist-1"),
                                name = samplePlaylist("playlist-1").name,
                                songCount = 2,
                                coverArtUrl = null,
                            ),
                        ),
                        onDismiss = { showSheet = false },
                        onAddToExisting = {},
                        onCreateAndAdd = {},
                    )
                }
            }
        }

        rule.onNodeWithText("Gesture Song").performSemanticsAction(SemanticsActions.OnLongClick)
        rule.onNodeWithText("Add to playlist").assertIsDisplayed()
        rule.onNodeWithText("Create new playlist…").assertIsDisplayed()
    }
}
