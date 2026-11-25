package com.gpo.yoin.ui.home

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.unit.dp
import com.gpo.yoin.data.model.Album
import com.gpo.yoin.data.model.MediaId
import com.gpo.yoin.data.model.Track
import com.gpo.yoin.ui.experience.FeedFrameClass
import com.gpo.yoin.ui.experience.LocalYoinWindowInfo
import com.gpo.yoin.ui.experience.ProvidePreviewWindow
import com.gpo.yoin.ui.experience.feedFrameClass
import com.gpo.yoin.ui.experience.feedFrameInsets
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
 * Recently Added with no albums to follow its tracks fills the feed's content width at every portrait
 * width (owner 2026-11-24, a small tablet in portrait: the grid stopped at 340 / 514dp and left the rest
 * of the row empty); with albums the grid keeps its capped width and the shelf carries on beside it.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w900dp-h1400dp")
class RecentlyAddedFillTest {

    @get:Rule
    val rule = createComposeRule()

    private val tracks = List(8) { index ->
        Track(
            id = MediaId.subsonic("t$index"),
            title = "Track $index",
            artist = "Artist",
            artistId = null,
            album = null,
            albumId = null,
            coverArt = null,
            durationSec = null,
            trackNumber = null,
            year = null,
            genre = null,
            userRating = null,
        )
    }

    private val albums = List(6) { index ->
        Album(
            id = MediaId.subsonic("a$index"),
            name = "Album $index",
            artist = "Artist",
            artistId = null,
            coverArt = null,
            songCount = null,
            durationSec = null,
            year = null,
            genre = null,
        )
    }

    private var window by mutableStateOf(412)
    private var withAlbums by mutableStateOf(false)
    private var started = false

    /** One setContent per test; later widths recompose the same tree. */
    private fun setSection(windowDp: Int, albumsFollow: Boolean) {
        window = windowDp
        withAlbums = albumsFollow
        if (started) {
            rule.waitForIdle()
            return
        }
        started = true
        rule.setContent {
            YoinTheme {
                Box {
                    ProvidePreviewWindow(widthDp = window, heightDp = 1200) {
                        val frame = rememberHomeFeedFrame(feedFrameClass(LocalYoinWindowInfo.current))
                        Column(
                            Modifier
                                .requiredWidth(window.dp)
                                .testTag(Container)
                                .feedFrameWidth(frame)
                                .padding(FeedFrameSidePadding(frame)),
                        ) {
                            RecentlyAddedSection(
                                tracks = tracks,
                                albums = if (withAlbums) albums else emptyList(),
                                extractBackdropColors = false,
                                onTrackClick = {},
                                onAlbumClick = {},
                                buildCoverArtUrl = { it },
                                frame = frame,
                            )
                        }
                    }
                }
            }
        }
        rule.waitForIdle()
    }

    /** A shown title's left edge, dp; null for a track the grid doesn't seat. */
    private fun left(text: String): Float? = rule.onAllNodesWithText(text, useUnmergedTree = true)
        .fetchSemanticsNodes()
        .firstOrNull()
        ?.let { it.positionInRoot.x / rule.density.density }

    /** The first row's titles' left edges, dp: tracks fill row by row, so the row ends where a title steps back left. */
    private fun firstRow(): List<Float> {
        val lefts = tracks.mapNotNull { left(it.title!!) }
        val row = mutableListOf(lefts.first())
        for (next in lefts.drop(1)) {
            if (next <= row.last()) break
            row += next
        }
        return row
    }

    @Test
    fun should_fillTheContentWidth_when_noAlbums() {
        for (width in listOf(412, 600, 640, 700, 760, 800)) {
            setSection(width, albumsFollow = false)
            val container = rule.onNodeWithTag(Container).fetchSemanticsNode().positionInRoot.x / rule.density.density
            val insets = feedFrameInsets(width.dp, FeedFrameClass.Capped)
            val contentStart = container + insets.start.value
            val contentEnd = container + width - insets.end.value
            val row = firstRow()
            assertTrue("W=$width row=$row", row.size >= 2)
            val pitch = row[1] - row[0]
            // A title sits 52 (cover) + 8 into its tile; tiles are 8 apart.
            val gridStart = row.first() - 60f
            val gridEnd = row.last() - 60f + pitch - 8f
            assertEquals("W=$width start", contentStart, gridStart, 1f)
            assertEquals("W=$width end", contentEnd, gridEnd, 1f)
        }
    }

    @Test
    fun should_keepTheCappedGrid_when_albumsFollow() {
        setSection(760, albumsFollow = true)
        val row = firstRow()
        // 8 tracks in 4 rows under a 3-column ceiling: 2 columns of the 166dp cell, 8 apart.
        assertEquals(2, row.size)
        assertEquals(RecentlyAddedTileWidth.value + RecentlyAddedTileGap.value, row[1] - row[0], 1f)
    }

    private companion object {
        const val Container = "recently-added-container"
    }
}
