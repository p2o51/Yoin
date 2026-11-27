package com.gpo.yoin.ui.component

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.width
import com.gpo.yoin.ui.detail.PlaylistSong
import com.gpo.yoin.ui.detail.PlaylistTrackCreditTag
import com.gpo.yoin.ui.detail.PlaylistTrackRow
import com.gpo.yoin.ui.theme.YoinTheme
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Galaxy S25 report (2026-11-27): in a playlist, a long artist name left the subtitle empty, and a
 * long artist with a long album showed the artist alone. The artist and the album now share the
 * line, each ending in an ellipsis, at phone widths, in Latin and CJK.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w412dp-h915dp")
class TrackRowSubtitleTest {

    @get:Rule
    val rule = createComposeRule()

    @Test
    fun should_showArtistAndAlbum_when_bothAreLongInAPlaylistRowAt360dp() {
        playlistRow(360.dp, LatinArtist, LatinAlbum)
        assertBothShown(PlaylistTrackCreditTag, LatinArtist, LatinAlbum)
    }

    @Test
    fun should_showArtistAndAlbum_when_bothAreLongInAPlaylistRowAt412dp() {
        playlistRow(412.dp, CjkArtist, CjkAlbum)
        assertBothShown(PlaylistTrackCreditTag, CjkArtist, CjkAlbum)
    }

    @Test
    fun should_showArtistAndAlbum_when_aLongCjkArtistMeetsALongLatinAlbumAt360dp() {
        playlistRow(360.dp, CjkArtist, LatinAlbum)
        assertBothShown(PlaylistTrackCreditTag, CjkArtist, LatinAlbum)
    }

    @Test
    fun should_showTheWholeAlbum_when_onlyTheArtistIsLong() {
        playlistRow(360.dp, LatinArtist, "Ctrl")
        assertBothShown(PlaylistTrackCreditTag, LatinArtist, "Ctrl", albumIsLong = false)
        val line = rule.onNodeWithTag(PlaylistTrackCreditTag, useUnmergedTree = true).getBoundsInRoot()
        val artist = rule.onNodeWithText(LatinArtist, useUnmergedTree = true).getBoundsInRoot()
        // The short album keeps its width and the artist takes what is left: well past 60 % of the line.
        assertTrue(artist.width > line.width * 0.6f)
    }

    @Test
    fun should_showArtistAndAlbum_when_bothAreLongInASongRowAt360dp() {
        songRow(360.dp, LatinArtist, CjkAlbum, explicit = true)
        assertBothShown(SongListItemSubtitleTag, LatinArtist, CjkAlbum)
        rule.onNodeWithContentDescription("Explicit", substring = true, useUnmergedTree = true).assertIsDisplayed()
    }

    @Test
    fun should_showArtistAndAlbum_when_bothAreLongInASongRowAt412dp() {
        songRow(412.dp, CjkArtist, LatinAlbum, explicit = false)
        assertBothShown(SongListItemSubtitleTag, CjkArtist, LatinAlbum)
    }

    @Test
    fun should_ellipsizeTheArtist_when_itIsTheOnlyGroupAndLongerThanTheLine() {
        rule.setContent {
            YoinTheme {
                Box(Modifier.width(160.dp)) {
                    MetaLine(groups = listOf(MetaGroup.Plain(LatinArtist)))
                }
            }
        }
        rule.onNodeWithText(LatinArtist, useUnmergedTree = true).assertIsDisplayed().also {
            assertTrue(it.getBoundsInRoot().width > 0.dp)
        }
    }

    private fun playlistRow(width: Dp, artist: String, album: String) = show(width) {
        PlaylistTrackRow(
            song = PlaylistSong(
                id = "subsonic:s1",
                title = "A song",
                artist = artist,
                album = album,
                duration = 245,
                coverArtUrl = null,
                rowKey = "row-1",
                isExplicit = true,
            ),
            hasNote = false,
            accent = { androidx.compose.ui.graphics.Color.Red },
            onClick = {},
        )
    }

    private fun songRow(width: Dp, artist: String, album: String, explicit: Boolean) = show(width) {
        SongListItem(
            title = "A song",
            artist = artist,
            album = album,
            durationSeconds = 245,
            coverArtUrl = null,
            onClick = {},
            extractBackdropColors = false,
            isExplicit = explicit,
            trailingContent = { Box(Modifier.width(48.dp)) },
        )
    }

    private fun show(width: Dp, row: @Composable () -> Unit) {
        rule.setContent {
            YoinTheme {
                Box(Modifier.width(width)) { row() }
            }
        }
        rule.waitForIdle()
    }

    private fun assertBothShown(lineTag: String, artist: String, album: String, albumIsLong: Boolean = true) {
        val line = rule.onNodeWithTag(lineTag, useUnmergedTree = true).assertIsDisplayed().getBoundsInRoot()
        val artistBounds = rule.onNodeWithText(artist, useUnmergedTree = true).assertIsDisplayed().getBoundsInRoot()
        val albumBounds = rule.onNodeWithText(album, useUnmergedTree = true).assertIsDisplayed().getBoundsInRoot()
        // Neither is squeezed to nothing, and both stay inside the line.
        assertTrue("artist width ${artistBounds.width}", artistBounds.width >= MetaShareMinWidth)
        assertTrue("album width ${albumBounds.width}", albumBounds.width >= if (albumIsLong) MetaShareMinWidth else 1.dp)
        assertTrue("album after artist", albumBounds.left >= artistBounds.right)
        assertTrue("album inside the line", albumBounds.right <= line.right + 0.5.dp)
    }

    private companion object {
        const val LatinArtist =
            "Kendrick Lamar, SZA, Travis Scott, Future, The Weeknd, Swae Lee & Jorja Smith"
        const val LatinAlbum = "Black Panther: The Album (Music From and Inspired By) [Deluxe Edition]"
        const val CjkArtist = "ポルノグラフィティ・ヨルシカ・米津玄師・宇多田ヒカル・椎名林檎"
        const val CjkAlbum = "千と千尋の神隠し サウンドトラック（完全生産限定盤・リマスター）"
    }
}
