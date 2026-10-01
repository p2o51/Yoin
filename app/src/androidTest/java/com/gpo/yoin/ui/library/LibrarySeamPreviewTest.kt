package com.gpo.yoin.ui.library

import android.graphics.Bitmap
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performTouchInput
import androidx.test.platform.app.InstrumentationRegistry
import com.gpo.yoin.data.model.Album
import com.gpo.yoin.data.model.CoverRef
import com.gpo.yoin.data.model.MediaId
import com.gpo.yoin.ui.theme.YoinTheme
import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test

/**
 * Deterministic 60fps frames of the Library seam dissolve for visual review.
 * Scroll and every time-based animation (marquee titles, entrances) advance on
 * the same paused clock, so the frames play back exactly as the device would
 * show them — unlike scrubbed screencaps, which compress time.
 */
class LibrarySeamPreviewTest {
    @get:Rule val rule = createComposeRule()

    @Test
    fun should_dissolveArtworkIntoSeam_whenScrollingAlbums() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("captureLibrarySeam") == "true")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val directory = File(context.getExternalFilesDir(null), "library-seam").apply { mkdirs() }
        val covers = (0 until ALBUMS.size).map { File(directory, "cov-a$it.png") }
        assertTrue("Supply the local QA covers before recording", covers.all { it.exists() })
        val albums = ALBUMS.mapIndexed { index, (name, artist) ->
            Album(
                id = MediaId.subsonic("al-$index"),
                name = name,
                artist = artist,
                artistId = null,
                coverArt = CoverRef.Url(covers[index].toURI().toString()),
                songCount = 4,
                durationSec = null,
                year = null,
                genre = null,
            )
        }
        val frames = File(directory, "frames").apply {
            deleteRecursively()
            mkdirs()
        }
        rule.mainClock.autoAdvance = false
        rule.setContent {
            // Fixed dark theme: frames must not depend on the device's night mode.
            YoinTheme(darkTheme = true) {
                LibraryContent(
                    uiState = LibraryUiState.Content(
                        selectedTab = LibraryTab.Albums,
                        artists = emptyList(),
                        albums = albums,
                        songs = emptyList(),
                        playlists = emptyList(),
                        favorites = null,
                        searchQuery = "",
                        searchResults = null,
                        isSearching = false,
                    ),
                    onTabSelected = {},
                    onSearchQueryChanged = {},
                    onClearSearch = {},
                    onNavigateToSettings = {},
                    onArtistClick = {},
                    onAlbumClick = {},
                    onPlaylistClick = {},
                    onSongClick = {},
                    onRetry = {},
                    coverArtUrlBuilder = null,
                )
            }
        }
        // Decode covers and let the staggered entrance settle.
        repeat(20) {
            rule.mainClock.advanceTimeBy(160)
            rule.waitForIdle()
        }
        var index = 0
        fun frame() {
            rule.mainClock.advanceTimeByFrame()
            val bitmap = rule.onRoot().captureToImage().asAndroidBitmap()
            File(frames, "%04d.png".format(index++)).outputStream().use {
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
            }
        }
        val root = rule.onRoot()
        repeat(30) { frame() }
        root.performTouchInput {
            down(Offset(centerX, height * .8f))
            // Past touch slop in one step so the drag owns the grid from here.
            moveBy(Offset(0f, -viewConfiguration.touchSlop - 1f))
        }
        // Slow drag, stop and hold (the afterglow coasts, then the print is still),
        // then a quicker drag and hold, then back down to rest.
        fun drag(steps: Int, px: Float) = repeat(steps) {
            root.performTouchInput { moveBy(Offset(0f, px)) }
            frame()
        }
        drag(80, -3f)
        repeat(HOLD) { frame() }
        drag(20, -10f)
        repeat(HOLD) { frame() }
        drag(88, 5f)
        root.performTouchInput { up() }
        repeat(30) { frame() }
    }

    private companion object {
        const val HOLD = 45

        val ALBUMS = listOf(
            "Harbor Lights" to "Sample Artist", "Paper Satellites Over the Pier" to "Another Sample",
            "Quiet Machines" to "Third Sample", "Salt & Static" to "Fourth Sample",
            "Low Orbit" to "Sample Artist 5", "Paper Moon" to "Sample Artist 6",
            "North Pier" to "Sample Artist 7", "Glass Garden" to "Sample Artist 8",
            "Night Ferry" to "Sample Artist 9", "Soft Radio" to "Sample Artist 10",
            "Blue Hour" to "Sample Artist 11", "Signal Fire" to "Sample Artist 12",
            "Open Water" to "Sample Artist 13", "Last Tram" to "Sample Artist 14",
            "Slow Clouds" to "Sample Artist 15", "Copper Bell" to "Sample Artist 16",
            "Far Field" to "Sample Artist 17", "Lantern Walk" to "Sample Artist 18",
        )
    }
}
