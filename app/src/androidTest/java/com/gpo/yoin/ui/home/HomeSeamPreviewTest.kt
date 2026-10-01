package com.gpo.yoin.ui.home

import androidx.activity.ComponentActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import com.gpo.yoin.data.local.ActivityActionType
import com.gpo.yoin.data.local.ActivityEntityType
import com.gpo.yoin.data.local.ActivityEvent
import com.gpo.yoin.data.model.Album
import com.gpo.yoin.data.model.CoverRef
import com.gpo.yoin.data.model.MediaId
import com.gpo.yoin.data.model.Track
import com.gpo.yoin.ui.component.SeamBarField
import com.gpo.yoin.ui.component.SeamDissolveDebug
import com.gpo.yoin.ui.component.SeamFrameHarness
import com.gpo.yoin.ui.component.SeamQa
import com.gpo.yoin.ui.component.SeamQaWindow
import com.gpo.yoin.ui.memories.MemoryEntityType
import java.io.File
import org.junit.After
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * Deterministic 60fps frames of Home's seams: the tide line under the status
 * bar, the large title's fade, and the bottom field around the bar — with
 * Recently Added (full-bleed, the page's last section) resting across the
 * bar, its second album card painted in the bar's own colour.
 *
 *   am instrument -w -e captureHomeSeam true -e class com.gpo.yoin.ui.home.HomeSeamPreviewTest \
 *     com.gpo.yoin.test/androidx.test.runner.AndroidJUnitRunner
 */
class HomeSeamPreviewTest {
    @After
    fun restore() {
        SeamDissolveDebug.forcePathFallback = false
    }

    @Test
    fun should_sinkUnderTheTideAndBreakAtTheBar_whenScrollingHome() {
        val arguments = InstrumentationRegistry.getArguments()
        assumeTrue(arguments.getString("captureHomeSeam") == "true")
        val dark = SeamQa.dark(arguments)
        val reduced = SeamQa.reduced(arguments)
        SeamDissolveDebug.forcePathFallback = SeamQa.path(arguments)
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val directory = File(context.getExternalFilesDir(null), "home-seam").apply { mkdirs() }
        val covers = SeamQa.covers(
            directory = File(directory, "covers"),
            count = 24,
            barColor = SeamQa.scheme(dark).surfaceContainerHigh,
            barTwin = 17,
            dark = 18,
        )
        val coverUrl: (String) -> String = { id ->
            covers[id.removePrefix("cov-").toInt() % covers.size].toURI().toString()
        }
        val now = System.currentTimeMillis()
        val activities = listOf(
            activity(1, ActivityEntityType.ALBUM, "Blue Hour", "Kota", "cov-0", now - 3_600_000L),
            activity(2, ActivityEntityType.ARTIST, "海野", "Artist", "cov-1", now - 7_200_000L),
            activity(3, ActivityEntityType.ALBUM, "Night Ferry", "The Lanterns", "cov-2", now - 9_000_000L),
            activity(4, ActivityEntityType.PLAYLIST, "春日迟迟", "白日梦乐队", "cov-3", now - 18_000_000L),
        )
        val grid = listOf(
            card("g0", MemoryEntityType.ALBUM, "潮水退去以后", "Album · Nao Mori", 4, expanded = true),
            card("g1", MemoryEntityType.SONG, "Slow Bloom", "Single · Ena", 5),
            card("g2", MemoryEntityType.ALBUM, "Paper Moon", "Album · Quiet Lines", 6),
            card("g3", MemoryEntityType.PLAYLIST, "Glass Garden", "Playlist · 51", 7),
            card("g4", MemoryEntityType.ALBUM, "余白", "Album · Yuna", 8, expanded = true),
            card("g5", MemoryEntityType.SONG, "Salt Window", "Single · Haru", 9),
        ).map { it.copy(coverArtUrl = it.coverArtUrl?.let(coverUrl)) }
        val tracks = (0 until 4).map { index ->
            Track(
                id = MediaId.subsonic("t$index"),
                title = listOf("Soft Radio", "第七个夏天", "Low Orbit", "花火の音")[index],
                artist = listOf("Paper Satellites", "林间", "Kota", "海野")[index],
                artistId = null,
                album = null,
                albumId = null,
                coverArt = CoverRef.SourceRelative("cov-${12 + index}"),
                durationSec = null,
                trackNumber = null,
                year = null,
                genre = null,
                userRating = null,
            )
        }
        val albums = listOf(16, 17, 18, 19, 20, 21, 22, 23).mapIndexed { index, cover ->
            Album(
                id = MediaId.subsonic("ra$index"),
                name = ALBUM_NAMES[index],
                artist = ALBUM_ARTISTS[index],
                artistId = null,
                coverArt = CoverRef.SourceRelative("cov-$cover"),
                songCount = 9,
                durationSec = null,
                year = null,
                genre = null,
            )
        }
        var field: SeamBarField? = null
        ActivityScenario.launch(ComponentActivity::class.java).use { scenario ->
            lateinit var activity: ComponentActivity
            scenario.onActivity { activity = it }
            val h = SeamFrameHarness(activity, File(directory, arguments.getString("seamOut") ?: "frames"))
            h.setContent {
                SeamQaWindow(dark = dark, reduced = reduced, onField = { field = it }) {
                    HomeContent(
                        uiState = HomeUiState.Content(
                            activities = activities,
                            widgetGrid = grid,
                            recentlyAddedTracks = tracks,
                            recentlyAddedAlbums = albums,
                        ),
                        isPlaying = false,
                        playbackSignal = 0f,
                        onNavigateToSettings = {},
                        onNavigateToMemories = {},
                        onAlbumClick = { _, _ -> },
                        onArtistClick = {},
                        onPlaylistClick = {},
                        onSongClick = {},
                        onRetry = {},
                        buildCoverArtUrl = coverUrl,
                    )
                }
            }
            repeat(120) { h.tick(realMillis = 25) }
            val dp = h.density
            val x = h.width * .25f
            h.hold(20, "rest-top")

            fun dragBy(dy: Float, steps: Int = 40, key: String? = null) {
                h.down(x, h.height * .55f)
                h.moveBy(0f, if (dy < 0) -h.touchSlop - 1f else h.touchSlop + 1f)
                repeat(steps) {
                    h.moveBy(0f, dy / steps)
                    h.frame()
                }
                // Finger held still, then lifted: no fling.
                h.hold(30)
                h.up()
                h.hold(45, key)
            }

            // 1. About 300dp down (§4 6): content sinks under the tide; the large
            //    title fades over ~24dp instead of looking sliced.
            dragBy(-300f * dp, key = "scroll-300")

            // 2. The end of the page (§4 4): the last section rests whole above the bar.
            repeat(3) { dragBy(-400f * dp, steps = 20) }
            h.hold(30, "end")

            // 3. Back up until Recently Added's covers cross the bar (§4 2): at the
            //    end the shelf's bottom sits 108dp above the nav bar; its top is
            //    ~118dp above that. The bar-coloured card gives way beside the bar.
            val bar = field?.bounds
            val insets = ViewCompat.getRootWindowInsets(h.view)
                ?.getInsets(WindowInsetsCompat.Type.navigationBars())?.bottom ?: 0
            val shelfBottom = h.height - insets - 108f * dp
            val barTop = bar?.top ?: (h.height - insets - 80f * dp)
            val target = barTop - 30f * dp + 118f * dp
            dragBy(target - shelfBottom, key = "shelf-at-bar")

            // 4. Pan the shelf 140dp left (§4 3): both sides of the bar are field,
            //    and the dots ride with the cards.
            h.down(h.width * .2f, barTop - 12f * dp)
            h.moveBy(-h.touchSlop - 1f, 0f)
            repeat(40) {
                h.moveBy(-140f * dp / 40f, 0f)
                h.frame()
            }
            h.hold(20)
            h.up()
            h.hold(30, "shelf-panned")

            // 5. A flick and stop (§4 5): the tide's swell rises with speed and
            //    falls back with the afterglow; the field's start lifts and returns.
            h.down(x, h.height * .3f)
            h.moveBy(0f, h.touchSlop + 1f)
            repeat(6) {
                h.moveBy(0f, 20f * dp)
                h.frame(if (it == 5) "flick-fast" else null)
            }
            h.up()
            repeat(70) { h.frame(if (it == 12) "flick-coasting" else if (it == 69) "flick-settled" else null) }
            h.release()
        }
    }

    private fun activity(
        id: Long,
        type: ActivityEntityType,
        title: String,
        subtitle: String,
        cover: String,
        timestamp: Long,
    ) = ActivityEvent(
        id = id,
        entityType = type.name,
        actionType = if (type == ActivityEntityType.ARTIST) {
            ActivityActionType.VISITED.name
        } else {
            ActivityActionType.PLAYED.name
        },
        entityId = "e$id",
        title = title,
        subtitle = subtitle,
        coverArtId = cover,
        albumId = if (type == ActivityEntityType.ALBUM) "a$id" else null,
        artistId = if (type == ActivityEntityType.ARTIST) "ar$id" else null,
        timestamp = timestamp,
    )

    private fun card(
        id: String,
        type: MemoryEntityType,
        title: String,
        subtitle: String,
        cover: Int,
        expanded: Boolean = false,
    ) = HomeWidgetCard(
        stableId = "grid:$id",
        entityType = type,
        title = title,
        subtitle = subtitle,
        coverArtUrl = "cov-$cover",
        ratingText = if (expanded) "8.2" else null,
        ratingBasis = if (expanded) "Sep 3" else null,
        comment = if (expanded) "雨停之前，把副歌听完" else null,
        expanded = expanded,
        target = HomeWidgetTarget.AlbumDetail("a-$id"),
    )

    private companion object {
        val ALBUM_NAMES = listOf(
            "Copper Bell", "Soft Focus", "Undertow", "Tin Sky", "North Pier", "Last Tram", "慢慢来", "Far Lights",
        )
        val ALBUM_ARTISTS = listOf(
            "The Lanterns", "Nao Mori", "The Lanterns", "Quiet Lines", "Nao Mori", "Haru", "林间", "Ena",
        )
    }
}
