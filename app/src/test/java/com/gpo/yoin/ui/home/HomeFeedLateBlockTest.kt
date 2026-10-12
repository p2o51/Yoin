package com.gpo.yoin.ui.home

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import com.gpo.yoin.data.model.Album
import com.gpo.yoin.data.model.MediaId
import com.gpo.yoin.data.model.Playlist
import com.gpo.yoin.ui.experience.LocalMotionProfile
import com.gpo.yoin.ui.experience.MotionProfile
import com.gpo.yoin.ui.theme.YoinTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Home's tiers (P2 PR2): a block that splices in on its own, after the rest
 * of the feed is up, shows — and one that a reload reads empty leaves.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w400dp-h1600dp")
class HomeFeedLateBlockTest {

    @get:Rule
    val rule = createComposeRule()

    private var playlists by mutableStateOf(emptyList<Playlist>())
    private var recentlyPlayed by mutableStateOf(emptyList<Album>())

    private fun setHome() {
        rule.setContent {
            CompositionLocalProvider(LocalMotionProfile provides MotionProfile.AdaptiveReduced) {
                YoinTheme {
                    HomeEditorialContent(
                        activities = emptyList(),
                        playlists = playlists,
                        recentlyPlayed = recentlyPlayed,
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

    @Test
    fun should_showYourPlaylists_when_playlistsSpliceInLast() {
        setHome()
        rule.onNodeWithText(YOUR_PLAYLISTS, useUnmergedTree = true).assertDoesNotExist()

        rule.runOnIdle { playlists = listOf(playlist("late")) }
        rule.waitForIdle()

        rule.onNodeWithText(YOUR_PLAYLISTS, useUnmergedTree = true).assertExists()
        rule.onNodeWithText("Playlist late", useUnmergedTree = true).assertExists()
    }

    @Test
    fun should_showRecentlyPlayed_when_itSplicesInLast() {
        setHome()
        rule.onNodeWithText(RECENTLY_PLAYED, useUnmergedTree = true).assertDoesNotExist()

        rule.runOnIdle { recentlyPlayed = listOf(album("late")) }
        rule.waitForIdle()

        rule.onNodeWithText(RECENTLY_PLAYED, useUnmergedTree = true).assertExists()
    }

    @Test
    fun should_dropYourPlaylists_when_aReloadReadsThemEmpty() {
        playlists = listOf(playlist("gone"))
        setHome()
        rule.onNodeWithText(YOUR_PLAYLISTS, useUnmergedTree = true).assertExists()

        rule.runOnIdle { playlists = emptyList() }
        rule.waitForIdle()

        rule.onNodeWithText(YOUR_PLAYLISTS, useUnmergedTree = true).assertDoesNotExist()
    }

    private fun playlist(rawId: String) = Playlist(
        id = MediaId.subsonic(rawId),
        name = "Playlist $rawId",
        owner = "owner",
        coverArt = null,
        songCount = 5,
        durationSec = 600
    )

    private fun album(rawId: String) = Album(
        id = MediaId.subsonic(rawId),
        name = "Album $rawId",
        artist = "Artist",
        artistId = null,
        coverArt = null,
        songCount = 10,
        durationSec = null,
        year = 2024,
        genre = null
    )

    private companion object {
        const val YOUR_PLAYLISTS = "Your Playlists"
        const val RECENTLY_PLAYED = "Recently Played"
    }
}
