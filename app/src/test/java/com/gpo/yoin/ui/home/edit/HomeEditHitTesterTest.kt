package com.gpo.yoin.ui.home.edit

import androidx.compose.foundation.lazy.LazyListItemInfo
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import com.gpo.yoin.ui.home.HomeSection
import org.junit.Assert.assertEquals
import org.junit.Test

/** Band resolution for the edit-mode hit tester, through [resolveSectionAt] (spec §2.1.2). */
class HomeEditHitTesterTest {

    // A 360px-wide page with 16px margins: Activities, then a gap, then a bleeding Recently Added shelf.
    private val bands = listOf(
        SectionBand(HomeSection.Activities, top = 100f, bottom = 400f, bleeds = false),
        SectionBand(HomeSection.RecentlyAdded, top = 418f, bottom = 700f, bleeds = true),
    )
    private val outset = Size(8f, 6f)

    private fun hit(x: Float, y: Float, editing: Boolean = false, handle: HomeSection? = null) =
        resolveHomeEditHit(Offset(x, y), bands, ContentLeft, ContentRight, outset, editing, handle)

    @Test
    fun should_returnBlock_when_inSectionBand() {
        assertEquals(
            HomeEditHit.Block(HomeSection.Activities, local = Offset(184f, 150f), size = Size(328f, 300f)),
            hit(200f, 250f),
        )
    }

    @Test
    fun should_returnBlankWithNearest_when_inGap() {
        assertEquals(HomeEditHit.Blank(HomeSection.Activities), hit(200f, 405f))
        assertEquals(HomeEditHit.Blank(HomeSection.RecentlyAdded), hit(200f, 415f))
        // Above the first section (the header) and with nothing laid out.
        assertEquals(HomeEditHit.Blank(HomeSection.Activities), hit(200f, 20f))
        assertEquals(
            HomeEditHit.Blank(null),
            resolveHomeEditHit(Offset(200f, 20f), emptyList(), ContentLeft, ContentRight, outset, editing = false),
        )
    }

    @Test
    fun should_returnBlock_when_marginOverBleedingShelf() {
        assertEquals(
            HomeEditHit.Block(HomeSection.RecentlyAdded, local = Offset(-12f, 82f), size = Size(328f, 282f)),
            hit(4f, 500f),
        )
    }

    @Test
    fun should_returnBlank_when_marginOverNonBleedingSection() {
        assertEquals(HomeEditHit.Blank(HomeSection.Activities), hit(4f, 250f))
        assertEquals(HomeEditHit.Blank(HomeSection.Activities), hit(352f, 250f))
    }

    @Test
    fun should_includePlateOutset_when_editing() {
        // 6px above the band and 8px into the margin are the plate.
        assertEquals(HomeSection.Activities, (hit(10f, 95f, editing = true) as HomeEditHit.Block).section)
        assertEquals(HomeEditHit.Blank(HomeSection.Activities), hit(10f, 95f, editing = false))
        assertEquals(HomeEditHit.Blank(HomeSection.Activities), hit(10f, 93f, editing = true))
    }

    @Test
    fun should_returnHandle_when_editingOverHandle() {
        assertEquals(
            HomeEditHit.Handle(HomeSection.RecentlyAdded, local = Offset(304f, 12f), size = Size(328f, 282f)),
            hit(320f, 430f, editing = true, handle = HomeSection.RecentlyAdded),
        )
    }

    @Test
    fun should_buildBandsFromSectionKeysOnly_when_readingLayoutInfo() {
        val items = listOf(
            Item(0, "home-header", offset = -4, size = 96),
            Item(1, "section-activities", offset = 110, size = 300),
            Item(2, "section-recently_added", offset = 428, size = 282),
            Item(3, "section-retired_section", offset = 728, size = 100),
            Item(4, "tray-title", offset = 846, size = 40),
        )

        // viewportStartOffset = −beforeContentPadding (4px): band tops sit 4px lower in the Box.
        assertEquals(
            listOf(
                SectionBand(HomeSection.Activities, top = 114f, bottom = 414f, bleeds = false),
                SectionBand(HomeSection.RecentlyAdded, top = 432f, bottom = 714f, bleeds = true),
            ),
            homeEditSectionBands(items, viewportStartOffset = -4),
        )
    }

    @Test
    fun should_returnBlank_when_sectionFadingOut() {
        val items = listOf(
            Item(1, "section-activities", offset = 100, size = 300),
            Item(2, "section-jump_back_in", offset = 418, size = 400),
        )
        val bands = homeEditSectionBands(items, viewportStartOffset = 0) { it == HomeSection.Activities }

        val hit = resolveHomeEditHit(Offset(200f, 250f), bands, ContentLeft, ContentRight, outset, editing = true)

        assertEquals(HomeEditHit.Blank(HomeSection.JumpBackIn), hit)
    }

    private data class Item(
        override val index: Int,
        override val key: Any,
        override val offset: Int,
        override val size: Int,
    ) : LazyListItemInfo

    private companion object {
        const val ContentLeft = 16f
        const val ContentRight = 344f
    }
}
