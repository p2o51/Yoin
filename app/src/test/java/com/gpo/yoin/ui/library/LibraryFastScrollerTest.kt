package com.gpo.yoin.ui.library

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.click
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeUp
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.height
import com.gpo.yoin.data.model.Album
import com.gpo.yoin.data.model.Artist
import com.gpo.yoin.data.model.MediaId
import com.gpo.yoin.data.model.Playlist
import com.gpo.yoin.data.model.Track
import com.gpo.yoin.ui.component.FastScrollMath
import com.gpo.yoin.ui.component.FastScrollSection
import com.gpo.yoin.ui.component.FastScrollerTags
import com.gpo.yoin.ui.experience.LayoutMode
import com.gpo.yoin.ui.experience.LocalYoinWindowInfo
import com.gpo.yoin.ui.experience.ShellChromeForm
import com.gpo.yoin.ui.experience.YoinWindowInfo
import com.gpo.yoin.ui.experience.previewYoinWindowInfo
import com.gpo.yoin.ui.theme.YoinTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Library's fast scroller (U2) in the real Library: dropped on a tick, the
 * row holding that section's first item comes to the top, in every layout
 * and in each grid that has one (All, Artists, Albums); the handle hugs the
 * page's end edge, stays whole while the views cross-fade, and keeps its
 * touch off the covers past Compact (at most the 16dp page margin's worth
 * on a phone, and only while it shows); a width change keeps the section on
 * top; Playlists and Songs have none. Device QA still owns the look and feel.
 */
@RunWith(RobolectricTestRunner::class)
class LibraryFastScrollerTest {

    @get:Rule
    val rule = createComposeRule()

    private val artists = (0 until ITEMS).map { Artist(MediaId.subsonic("ar$it"), nameOf(it), null, null) }

    private val albums = (0 until ITEMS).map { album(MediaId.subsonic("al$it"), nameOf(it)) }

    // All mixes the three kinds, one name sequence across them.
    private val allItems = (0 until ITEMS).map { i ->
        val id = MediaId.subsonic("all$i")
        when (i % 3) {
            0 -> LibraryItem.ArtistItem(Artist(id, nameOf(i), null, null))
            1 -> LibraryItem.AlbumItem(album(id, nameOf(i)))
            else -> LibraryItem.PlaylistItem(playlist(id, nameOf(i)))
        }
    }

    // Every 10 items: 100 starts mid-row at 3, 5, 6, 7 and 11 columns alike.
    private val sections = (0 until ITEMS step 10).mapIndexed { i, start -> FastScrollSection("S$i", start) }
    private val target = sections.first { it.startIndex == 100 }

    // ── Every layout ────────────────────────────────────────────────────

    @Test
    @Config(qualifiers = "w412dp-h915dp")
    fun should_putTheSectionRowOnTop_when_droppedOnItsTickOnAPhone() = assertJumpLands(previewYoinWindowInfo(412, 915))

    @Test
    @Config(qualifiers = "w800dp-h1280dp")
    fun should_putTheSectionRowOnTop_when_droppedOnItsTickOnATabletInPortrait() =
        assertJumpLands(previewYoinWindowInfo(800, 1280))

    @Test
    @Config(qualifiers = "w832dp-h900dp")
    fun should_putTheSectionRowOnTop_when_droppedOnItsTickOnAFoldableTabletop() = assertJumpLands(
        previewYoinWindowInfo(832, 900).copy(layoutMode = LayoutMode.Tabletop, chromeForm = ShellChromeForm.PortraitBar)
    )

    @Test
    @Config(qualifiers = "w1280dp-h800dp-land")
    fun should_putTheSectionRowOnTop_when_droppedOnItsTickOnATabletInLandscape() =
        assertJumpLands(previewYoinWindowInfo(1280, 800))

    @Test
    @Config(qualifiers = "w915dp-h412dp-land")
    fun should_putTheSectionRowOnTop_when_droppedOnItsTickOnAPhoneInLandscape() =
        assertJumpLands(previewYoinWindowInfo(915, 412))

    // ── Every grid that has one ─────────────────────────────────────────

    @Test
    @Config(qualifiers = "w412dp-h915dp")
    fun should_putTheSectionRowOnTop_when_droppedOnItsTickInAlbumsOnAPhone() =
        assertJumpLands(previewYoinWindowInfo(412, 915), LibraryTab.Albums)

    @Test
    @Config(qualifiers = "w800dp-h1280dp")
    fun should_putTheSectionRowOnTop_when_droppedOnItsTickInAlbumsOnATabletInPortrait() =
        assertJumpLands(previewYoinWindowInfo(800, 1280), LibraryTab.Albums)

    @Test
    @Config(qualifiers = "w412dp-h915dp")
    fun should_putTheSectionRowOnTop_when_droppedOnItsTickInAllOnAPhone() =
        assertJumpLands(previewYoinWindowInfo(412, 915), LibraryTab.All)

    @Test
    @Config(qualifiers = "w1280dp-h800dp-land")
    fun should_putTheSectionRowOnTop_when_droppedOnItsTickInAllOnATabletInLandscape() =
        assertJumpLands(previewYoinWindowInfo(1280, 800), LibraryTab.All)

    // ── The handle's edge and touch ─────────────────────────────────────

    @Test
    @Config(qualifiers = "w800dp-h1280dp")
    fun should_keepTheHandleWhole_when_theViewsCrossFade() {
        var tab by mutableStateOf(LibraryTab.Albums)
        setLibrary(tabOf = { tab }, window = previewYoinWindowInfo(800, 1280))
        wakeTheHandle()
        val root = rule.onNodeWithTag(ROOT).fetchSemanticsNode().boundsInRoot

        // A chip tapped while the handle still shows: both views are on
        // screen for the cross-fade, and both handles still reach the page
        // edge across the gutter (the tab's box ends 16dp short of it).
        tab = LibraryTab.Artists
        Snapshot.sendApplyNotifications()
        rule.mainClock.advanceTimeBy(FRAME_MS * 3)

        val thumbs = rule.onAllNodesWithTag(FastScrollerTags.THUMB).fetchSemanticsNodes()
        assertEquals("both views mid cross-fade", 2, thumbs.size)
        thumbs.forEach { thumb ->
            assertEquals(root.right, thumb.boundsInRoot.right, 0.5f)
            assertEquals(dpToPx(THUMB_TOUCH_WIDTH_DP), thumb.boundsInRoot.width, 0.5f)
        }
    }

    @Test
    @Config(qualifiers = "w800dp-h1280dp")
    fun should_keepTheHandlesTouchOffTheCovers_when_pastCompact() {
        setLibrary(LibraryTab.Artists, previewYoinWindowInfo(800, 1280))
        wakeTheHandle()

        val thumb = rule.onNodeWithTag(FastScrollerTags.THUMB).fetchSemanticsNode().boundsInRoot
        // All of it in the 32dp page margin (16 gutter + 16 grid padding).
        cells().forEach { (name, cell) ->
            assertTrue("$name $cell under the handle $thumb", cell.right <= thumb.left + 0.5f)
        }
    }

    @Test
    @Config(qualifiers = "w412dp-h915dp")
    fun should_yieldTheCoversEdgeOnlyWhileTheHandleShows_when_onAPhone() {
        val opened = mutableListOf<String>()
        setLibrary(LibraryTab.Artists, previewYoinWindowInfo(412, 915), onArtistClick = { opened += it })
        wakeTheHandle()
        val root = rule.onNodeWithTag(ROOT).fetchSemanticsNode().boundsInRoot
        val thumb = rule.onNodeWithTag(FastScrollerTags.THUMB).fetchSemanticsNode().boundsInRoot

        // 32dp of touch at the screen edge: the 16dp page margin, then at
        // most 16dp of the last column's covers.
        assertEquals(root.right, thumb.right, 0.5f)
        assertEquals(dpToPx(THUMB_TOUCH_WIDTH_DP), thumb.width, 0.5f)
        val lastColumn = cells().filter { (_, cell) -> cell.right > thumb.left }
        assertTrue("a cover under the handle", lastColumn.isNotEmpty())
        lastColumn.forEach { (_, cell) -> assertEquals(dpToPx(16f), cell.right - thumb.left, 0.5f) }

        // The cover under the handle, at a point inside both.
        val (name, cell) = lastColumn.maxBy { (_, cell) ->
            minOf(cell.bottom, thumb.bottom) - maxOf(cell.top, thumb.top)
        }
        val point = Offset(
            thumb.left + dpToPx(4f) - root.left,
            (maxOf(cell.top, thumb.top) + minOf(cell.bottom, thumb.bottom)) / 2f - root.top
        )

        // Shown, the handle takes the tap.
        rule.onNodeWithTag(ROOT).performTouchInput { click(point) }
        rule.mainClock.advanceTimeBy(FRAME_MS * 4)
        assertEquals(emptyList<String>(), opened)

        // Still for a moment, it hides and the cover gets the tap again.
        settle()
        rule.onNodeWithTag(ROOT).performTouchInput { click(point) }
        rule.mainClock.advanceTimeBy(FRAME_MS * 4)
        assertEquals(listOf(artists.first { it.name == name }.id.toString()), opened)
    }

    // ── Width changes, views without a scroller, handle only ────────────

    @Test
    @Config(qualifiers = "w800dp-h1280dp")
    fun should_keepTheSectionOnTop_when_theColumnsChangeAfterAJump() {
        var width by mutableStateOf<Dp?>(800.dp)
        setLibrary(LibraryTab.Artists, previewYoinWindowInfo(800, 1280), width = { width })
        val perLine = firstRowSize()
        assertEquals(6, perLine)
        grabAfterAScroll()
        dragTo(FastScrollMath.tickFraction(target.startIndex, ITEMS, perLine))
        rule.onNodeWithTag(FastScrollerTags.THUMB).performTouchInput { up() }
        settle()
        assertTopRowHolds(target.startIndex)

        // The split opens: 6 columns become 5 and every row breaks anew.
        width = 640.dp
        Snapshot.sendApplyNotifications()
        settle()

        assertEquals(5, firstRowSize())
        assertTopRowHolds(target.startIndex)
    }

    @Test
    @Config(qualifiers = "w800dp-h1280dp")
    fun should_hugTheLeftEdge_when_theLayoutIsRightToLeft() {
        setLibrary(LibraryTab.Artists, previewYoinWindowInfo(800, 1280), direction = LayoutDirection.Rtl)
        val perLine = firstRowSize()
        grabAfterAScroll()

        val root = rule.onNodeWithTag(ROOT).getBoundsInRoot()
        val thumb = rule.onNodeWithTag(FastScrollerTags.THUMB).getBoundsInRoot()
        assertEquals(root.left.value, thumb.left.value, 0.5f)
        dragTo(FastScrollMath.tickFraction(target.startIndex, ITEMS, perLine))
        assertTopRowHolds(target.startIndex)
        rule.onNodeWithTag(FastScrollerTags.THUMB).performTouchInput { up() }
    }

    @Test
    @Config(qualifiers = "w412dp-h915dp")
    fun should_showOnlyTheHandle_when_theViewHasNoSections() {
        setLibrary(LibraryTab.Artists, previewYoinWindowInfo(412, 915), sections = emptyList())
        grabAfterAScroll()
        dragBy(200f)

        rule.onNodeWithTag(FastScrollerTags.TICKS).assertDoesNotExist()
        rule.onNodeWithTag(FastScrollerTags.BUBBLE).assertDoesNotExist()
        rule.onNodeWithTag(FastScrollerTags.THUMB).performTouchInput { up() }
    }

    @Test
    @Config(qualifiers = "w412dp-h915dp")
    fun should_showNoScroller_when_playlistsOrSongsAreOn() {
        var tab by mutableStateOf(LibraryTab.Playlists)
        setLibrary(tabOf = { tab }, window = previewYoinWindowInfo(412, 915))
        rule.waitForIdle()
        rule.onNodeWithTag(LIBRARY_FAST_SCROLLER_TAG).assertDoesNotExist()
        rule.onNodeWithTag(FastScrollerTags.THUMB).assertDoesNotExist()

        tab = LibraryTab.Songs
        rule.waitForIdle()
        rule.onNodeWithTag(LIBRARY_FAST_SCROLLER_TAG).assertDoesNotExist()
        rule.onNodeWithTag(FastScrollerTags.THUMB).assertDoesNotExist()

        // The same library's Artists has one.
        tab = LibraryTab.Artists
        rule.waitForIdle()
        rule.onNodeWithTag(FastScrollerTags.THUMB).assertExists()
    }

    // ── The width-change anchor a jump leaves (pure) ────────────────────

    @Test
    fun should_anchorOnTheSectionStartingOnTheRow_when_jumping() {
        val starts = listOf(FastScrollSection("A", 0), FastScrollSection("B", 4), FastScrollSection("C", 5))
        // Row [3, 5] under one sort row: B and C start on it; the bubble names C, the last.
        assertEquals(1 + 5, libraryJumpAnchor(jumped = 1 + 3, leadingItems = 1, itemsPerLine = 3, sections = starts))
        // Row [6, 8]: no section starts there, the row itself.
        assertEquals(1 + 6, libraryJumpAnchor(jumped = 1 + 6, leadingItems = 1, itemsPerLine = 3, sections = starts))
        // The very top keeps the sort row.
        assertEquals(0, libraryJumpAnchor(jumped = 0, leadingItems = 1, itemsPerLine = 3, sections = starts))
        // No sort row, no sections.
        assertEquals(9, libraryJumpAnchor(jumped = 9, leadingItems = 0, itemsPerLine = 3, sections = emptyList()))
    }

    // ── Helpers ─────────────────────────────────────────────────────────

    private fun assertJumpLands(window: YoinWindowInfo, tab: LibraryTab = LibraryTab.Artists) {
        setLibrary(tab, window)
        val perLine = firstRowSize()

        grabAfterAScroll()
        // The handle hugs the page's end edge, across the page gutter past Compact.
        val root = rule.onNodeWithTag(ROOT).getBoundsInRoot()
        val thumb = rule.onNodeWithTag(FastScrollerTags.THUMB).getBoundsInRoot()
        assertEquals(root.right.value, thumb.right.value, 0.5f)
        // Its track clears the floating bar: it ends above the grid's bottom padding.
        val track = rule.onNodeWithTag(LIBRARY_FAST_SCROLLER_TAG).getBoundsInRoot()
        assertTrue("track $track inside $root", track.bottom < root.bottom && track.top > root.top)

        dragTo(FastScrollMath.tickFraction(target.startIndex, ITEMS, perLine))
        rule.onNodeWithTag(FastScrollerTags.TICKS).assertExists()
        rule.onNodeWithTag(FastScrollerTags.BUBBLE).assertExists()
        assertTopRowHolds(target.startIndex)
        rule.onNodeWithTag(FastScrollerTags.THUMB).performTouchInput { up() }
    }

    private fun setLibrary(
        tab: LibraryTab,
        window: YoinWindowInfo,
        width: () -> Dp? = { null },
        sections: List<FastScrollSection> = this.sections,
        direction: LayoutDirection = LayoutDirection.Ltr,
        onArtistClick: (String) -> Unit = {}
    ) = setLibrary(
        tabOf = { tab },
        window = window,
        width = width,
        sections = sections,
        direction = direction,
        onArtistClick = onArtistClick
    )

    private fun setLibrary(
        tabOf: () -> LibraryTab,
        window: YoinWindowInfo,
        width: () -> Dp? = { null },
        sections: List<FastScrollSection> = this.sections,
        direction: LayoutDirection = LayoutDirection.Ltr,
        onArtistClick: (String) -> Unit = {}
    ) {
        val playlists = (0 until ITEMS).map { playlist(MediaId.subsonic("pl$it"), nameOf(it)) }
        val gridOrders = listOf(LibraryTab.All, LibraryTab.Artists, LibraryTab.Albums, LibraryTab.Playlists)
        val songs = (0 until ITEMS).map { song(it) }
        rule.setContent {
            YoinTheme {
                CompositionLocalProvider(
                    LocalYoinWindowInfo provides window,
                    LocalLayoutDirection provides direction
                ) {
                    val size = width()?.let { Modifier.width(it).fillMaxHeight() } ?: Modifier.fillMaxSize()
                    Box(size.testTag(ROOT)) {
                        LibraryContent(
                            uiState = LibraryUiState.Content(
                                selectedTab = tabOf(),
                                artists = artists,
                                albums = albums,
                                songs = songs,
                                playlists = playlists,
                                favorites = null,
                                searchQuery = "",
                                searchResults = null,
                                isSearching = false,
                                availableTabs = LibraryTab.Chips,
                                allItems = allItems,
                                // A sort row over each: the scroller counts past it.
                                sorts = gridOrders.associateWith { LibrarySort.Alphabetical },
                                sortOptions = gridOrders.associateWith {
                                    listOf(LibrarySort.Recents, LibrarySort.Alphabetical)
                                },
                                // Playlists' and Songs' would be ignored: neither has a scroller.
                                scrollSections = LibraryTab.entries.associateWith { sections }
                            ),
                            onTabSelected = {},
                            onSearchQueryChanged = {},
                            onClearSearch = {},
                            onNavigateToSettings = {},
                            onArtistClick = onArtistClick,
                            onAlbumClick = {},
                            onPlaylistClick = {},
                            onSongClick = {},
                            onRetry = {},
                            coverArtUrlBuilder = null
                        )
                    }
                }
            }
        }
        rule.waitForIdle()
    }

    /** Scrolls the grid a little (the handle wakes with a scroll) and holds the handle mid-fling. */
    private fun grabAfterAScroll() {
        rule.mainClock.autoAdvance = false
        rule.onNodeWithTag(LIBRARY_FAST_SCROLLER_TAG).performTouchInput {
            swipeUp(startY = centerY, endY = centerY - 120f, durationMillis = 200)
        }
        rule.mainClock.advanceTimeBy(FRAME_MS * 4)
        rule.onNodeWithTag(FastScrollerTags.THUMB).performTouchInput { down(center) }
        rule.mainClock.advanceTimeBy(FRAME_MS * 4)
    }

    /** Scrolls the grid a little and lets go: the handle shows, for a moment. */
    private fun wakeTheHandle() {
        rule.mainClock.autoAdvance = false
        rule.onNodeWithTag(LIBRARY_FAST_SCROLLER_TAG).performTouchInput {
            swipeUp(startY = centerY, endY = centerY - 120f, durationMillis = 200)
        }
        rule.mainClock.advanceTimeBy(FRAME_MS * 20)
    }

    /** Drags the held handle so its fraction of the track becomes [fraction]. */
    private fun dragTo(fraction: Float) {
        val track = rule.onNodeWithTag(LIBRARY_FAST_SCROLLER_TAG).getBoundsInRoot()
        val thumb = rule.onNodeWithTag(FastScrollerTags.THUMB).getBoundsInRoot()
        val extent = (track.height - thumb.height).value
        val delta = fraction * extent - (thumb.top - track.top).value
        dragBy(with(rule.density) { delta.dp.toPx() })
    }

    private fun dragBy(px: Float) {
        rule.onNodeWithTag(FastScrollerTags.THUMB).performTouchInput { moveBy(Offset(0f, px)) }
        rule.mainClock.advanceTimeBy(FRAME_MS * 6)
    }

    private fun settle() {
        rule.mainClock.advanceTimeBy(SETTLE_MS)
    }

    /** The names on screen, top-most row first, with their tops. */
    private fun shownNames(): List<Pair<String, Float>> = rule.onAllNodes(hasText(NAME_PREFIX, substring = true))
        .fetchSemanticsNodes()
        .mapNotNull { node ->
            val text = node.config.getOrNull(SemanticsProperties.Text)?.firstOrNull()?.text ?: return@mapNotNull null
            text to node.boundsInRoot.top
        }
        .filter { (text, _) -> text.startsWith(NAME_PREFIX) }

    /** The grid's cells (each a clickable cover and its name), by name, in root pixels. */
    private fun cells(): List<Pair<String, Rect>> =
        rule.onAllNodes(hasText(NAME_PREFIX, substring = true) and hasClickAction())
            .fetchSemanticsNodes()
            .mapNotNull { node ->
                val text = node.config.getOrNull(SemanticsProperties.Text)?.firstOrNull()?.text
                    ?: return@mapNotNull null
                (text to node.boundsInRoot).takeIf { text.startsWith(NAME_PREFIX) && !node.boundsInRoot.isEmpty }
            }

    private fun dpToPx(dp: Float): Float = with(rule.density) { dp.dp.toPx() }

    /** How many items share the top row. */
    private fun firstRowSize(): Int = topRow().size

    /**
     * The grid's first visible row: the top-most names whose row starts in
     * the content area (the track starts there too). The row above it is
     * still placed, out of sight under the chips, and reads clipped.
     */
    private fun topRow(): List<String> {
        val contentTop = rule.onNodeWithTag(LIBRARY_FAST_SCROLLER_TAG).getBoundsInRoot().top.value
        val names = shownNames().filter { it.second >= contentTop - 1f }
        val top = names.minOf { it.second }
        return names.filter { kotlin.math.abs(it.second - top) < 1f }.map { it.first }
    }

    private fun assertTopRowHolds(index: Int) {
        val row = topRow()
        assertTrue("top row $row should hold ${nameOf(index)}", nameOf(index) in row)
    }

    private fun nameOf(index: Int) = NAME_PREFIX + index.toString().padStart(3, '0')

    private fun album(id: MediaId, name: String) = Album(
        id = id,
        name = name,
        artist = "Someone",
        artistId = null,
        coverArt = null,
        songCount = null,
        durationSec = null,
        year = null,
        genre = null
    )

    private fun playlist(id: MediaId, name: String) = Playlist(
        id = id,
        name = name,
        owner = "me",
        coverArt = null,
        songCount = null,
        durationSec = null
    )

    private fun song(index: Int) = Track(
        id = MediaId.subsonic("so$index"),
        title = "Song $index",
        artist = null,
        artistId = null,
        album = null,
        albumId = null,
        coverArt = null,
        durationSec = 200,
        trackNumber = null,
        year = null,
        genre = null,
        userRating = null
    )

    private companion object {
        const val ROOT = "root"
        const val NAME_PREFIX = "Name "
        const val ITEMS = 280
        const val FRAME_MS = 16L
        const val SETTLE_MS = 2_000L

        // The handle's touch box (YoinFastScroller's ThumbTouchWidth).
        const val THUMB_TOUCH_WIDTH_DP = 32f
    }
}
