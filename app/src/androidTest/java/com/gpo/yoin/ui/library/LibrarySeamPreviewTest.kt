package com.gpo.yoin.ui.library

import androidx.activity.ComponentActivity
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import com.gpo.yoin.data.model.Album
import com.gpo.yoin.data.model.CoverRef
import com.gpo.yoin.data.model.MediaId
import com.gpo.yoin.ui.component.SeamDissolveDebug
import com.gpo.yoin.ui.component.SeamFrameHarness
import com.gpo.yoin.ui.component.SeamQa
import com.gpo.yoin.ui.component.SeamQaWindow
import com.gpo.yoin.ui.component.SeamTopPreference
import com.gpo.yoin.ui.component.SeamTopStyle
import java.io.File
import org.junit.After
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * Deterministic 60fps frames of the Library seams for visual review — the
 * top seam under the chips (`-e seamStyle tide | dots | cookie`, default
 * tide; shown, not stored) and the bottom field around the floating bar.
 * Scroll and every time-based animation (marquee titles, entrances, the
 * afterglow) advance on the same paused clock, so the frames play back
 * exactly as the device would show them — unlike scrubbed screencaps, which
 * compress time. Covers are generated; see [SeamQa] for the arguments.
 *
 *   am instrument -w -e captureLibrarySeam true -e class com.gpo.yoin.ui.library.LibrarySeamPreviewTest \
 *     com.gpo.yoin.test/androidx.test.runner.AndroidJUnitRunner
 */
class LibrarySeamPreviewTest {
    @After
    fun restore() {
        SeamDissolveDebug.forcePathFallback = false
        SeamTopPreference.preview(null)
    }

    @Test
    fun should_dissolveArtworkIntoSeams_whenScrollingAlbums() {
        val arguments = InstrumentationRegistry.getArguments()
        assumeTrue(arguments.getString("captureLibrarySeam") == "true")
        val dark = SeamQa.dark(arguments)
        val reduced = SeamQa.reduced(arguments)
        SeamDissolveDebug.forcePathFallback = SeamQa.path(arguments)
        SeamTopPreference.preview(SeamTopStyle.fromKey(arguments.getString("seamStyle")))
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val directory = File(context.getExternalFilesDir(null), "library-seam").apply { mkdirs() }
        val covers = SeamQa.covers(
            directory = File(directory, "covers"),
            count = ALBUMS.size,
            barColor = SeamQa.scheme(dark).surfaceContainerHigh,
            barTwin = 13,
            dark = 16,
        )
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
        ActivityScenario.launch(ComponentActivity::class.java).use { scenario ->
            lateinit var activity: ComponentActivity
            scenario.onActivity { activity = it }
            val h = SeamFrameHarness(activity, File(directory, arguments.getString("seamOut") ?: "frames"))
            h.setContent {
                SeamQaWindow(dark = dark, reduced = reduced) {
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
            // Decode covers and let the staggered entrance and the field's reveal settle.
            repeat(90) { h.tick(realMillis = 25) }
            val dp = h.density
            val x = h.width * .5f
            h.hold(20, "rest-top")

            // 1. Ease down to 60dp of scroll; let go with the finger still: the page
            //    at rest (§4 1, 8).
            h.down(x, h.height * .62f)
            h.moveBy(0f, -h.touchSlop - 1f)
            repeat(40) {
                h.moveBy(0f, -60f * dp / 40f)
                h.frame()
            }
            h.hold(45, "hold-60-finger-down")
            h.up()
            h.hold(45, "rest-60")

            // 2. Drag on and stop with the finger down (§4 9): the dots settle onto
            //    the lattice with the afterglow, ~0.45s.
            h.down(x, h.height * .62f)
            h.moveBy(0f, -h.touchSlop - 1f)
            repeat(50) {
                h.moveBy(0f, -3.5f * dp)
                h.frame(if (it == 49) "drag-moving" else null)
            }
            repeat(40) { h.frame(if (it == 6) "drag-stopped-100ms" else if (it == 39) "drag-settled" else null) }
            h.up()

            // 3. A quick flick (§4 5): both ends widen, then close up together.
            h.down(x, h.height * .7f)
            h.moveBy(0f, -h.touchSlop - 1f)
            repeat(8) {
                h.moveBy(0f, -24f * dp)
                h.frame(if (it == 7) "fling-fast" else null)
            }
            h.up()
            repeat(90) { h.frame(if (it == 20) "fling-coasting" else if (it == 89) "fling-settled" else null) }

            // 4. To the end of the list (§4 4): the last row rests whole above the bar.
            repeat(6) {
                h.down(x, h.height * .8f)
                h.moveBy(0f, -h.touchSlop - 1f)
                repeat(6) {
                    h.moveBy(0f, -60f * dp)
                    h.frame()
                }
                h.up()
                repeat(50) { h.frame() }
            }
            h.hold(60, "end")
            h.release()
        }
    }

    private companion object {
        private val NAMES = listOf(
            "Harbor Lights", "Paper Satellites Over the Pier", "Quiet Machines", "Salt & Static", "Low Orbit",
            "Paper Moon", "North Pier", "Glass Garden", "Night Ferry", "Soft Radio", "Blue Hour", "Signal Fire",
            "Open Water", "Last Tram", "Slow Clouds", "Copper Bell", "Far Field", "Lantern Walk", "Tin Sky",
            "Folded Maps", "Warm Circuit", "Morning Rope", "Idle Fans", "Gull Count", "Undertow", "Rain Wings",
            "Home Boats", "Static Channel", "Long Evening", "Streetlight Edge",
        )

        /** Long enough for a tablet's seven-column grid to scroll well past both seams. */
        val ALBUMS = (0 until 60).map { index ->
            val name = NAMES[index % NAMES.size] + if (index >= NAMES.size) " II" else ""
            name to "Sample Artist ${index + 1}"
        }
    }
}
