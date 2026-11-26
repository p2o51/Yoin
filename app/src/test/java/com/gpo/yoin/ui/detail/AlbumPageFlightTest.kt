package com.gpo.yoin.ui.detail

import androidx.compose.ui.geometry.Rect
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The page-0 cover flies to the scrapbook's cover only while that cover is in the page.
 * Scrolled down the scrapbook (to its receipt), the target sits above the page and the hero
 * flew up through the header and off the screen on every switch (owner 2026-11-25).
 */
class AlbumPageFlightTest {

    // A phone page under a 300px header: 1080 wide, 1800 tall.
    private val viewport = Rect(0f, 300f, 1080f, 2100f)

    private fun flight() = AlbumPageFlight(
        pageFraction = { 0.5f },
        pageWidthPx = 1080f,
        rtl = false,
        flies = { true },
        openScrapbook = {},
    )

    @Test
    fun should_land_when_scrapbookCoverIsInThePage() {
        assertTrue(albumPageFlightLands(Rect(48f, 340f, 548f, 840f), viewport))
    }

    @Test
    fun should_notLand_when_scrapbookScrolledPastItsCover() {
        // Scrolled to the receipt: the cover is far above the screen.
        assertFalse(albumPageFlightLands(Rect(48f, -1200f, 548f, -700f), viewport))
    }

    @Test
    fun should_notLand_when_coverPartlyUnderTheHeader() {
        assertFalse(albumPageFlightLands(Rect(48f, 200f, 548f, 700f), viewport))
    }

    @Test
    fun should_notLand_when_coverBelowThePage() {
        assertFalse(albumPageFlightLands(Rect(48f, 1900f, 548f, 2400f), viewport))
    }

    @Test
    fun should_land_when_offByRoundingOnly() {
        assertTrue(albumPageFlightLands(Rect(48f, 299.5f, 548f, 799.5f), viewport))
    }

    @Test
    fun should_land_when_viewportNotPlacedYet() {
        assertTrue(albumPageFlightLands(Rect(48f, -1200f, 548f, -700f), null))
    }

    @Test
    fun should_flyOnlyToACoverInThePage() {
        val flight = flight()
        flight.pageViewport = viewport
        assertFalse(flight.coverFlying()) // no target yet
        flight.coverTarget = Rect(48f, 340f, 548f, 840f)
        assertTrue(flight.coverFlying())
        flight.coverTarget = Rect(48f, -1200f, 548f, -700f)
        assertFalse(flight.coverFlying())
    }

    @Test
    fun should_notFly_when_motionReduced() {
        val flight = AlbumPageFlight(
            pageFraction = { 0.5f },
            pageWidthPx = 1080f,
            rtl = false,
            flies = { false },
            openScrapbook = {},
        )
        flight.pageViewport = viewport
        flight.coverTarget = Rect(48f, 340f, 548f, 840f)
        assertFalse(flight.coverFlying())
    }
}
