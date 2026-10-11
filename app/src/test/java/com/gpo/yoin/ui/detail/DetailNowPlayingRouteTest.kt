package com.gpo.yoin.ui.detail

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * [openFromHostedNowPlaying]: Now Playing inside a detail window opens other
 * pages as before, but the page the window already shows — its artist's
 * "Playing from" label on that artist's page — is revealed, never pushed
 * again as a copy of itself.
 */
class DetailNowPlayingRouteTest {

    private val calls = mutableListOf<String>()

    private fun open(
        requested: DetailPageKind,
        requestedId: String,
        shownIds: List<String?>,
        shown: DetailPageKind = DetailPageKind.Artist,
        panelAvailable: Boolean = false
    ) = openFromHostedNowPlaying(
        requested = requested,
        requestedId = requestedId,
        shown = shown,
        shownIds = shownIds,
        panelAvailable = panelAvailable,
        onStepBackToPanel = { calls += "panel" },
        onCollapse = { calls += "collapse" },
        onLaunch = { calls += "launch" }
    )

    @Test
    fun should_collapseOntoThePage_when_theLabelNamesTheArtistThisPageShows() {
        open(DetailPageKind.Artist, "subsonic:ar-022", shownIds = listOf("subsonic:ar-022", "subsonic:ar-022"))

        assertEquals(listOf("collapse"), calls)
    }

    @Test
    fun should_stepBackToThePanel_when_aPanelFitsBesideThePage() {
        open(
            DetailPageKind.Artist,
            "subsonic:ar-022",
            shownIds = listOf("subsonic:ar-022", null),
            panelAvailable = true
        )

        assertEquals(listOf("panel"), calls)
    }

    @Test
    fun should_launchThePage_when_itIsAnotherArtist() {
        open(DetailPageKind.Artist, "subsonic:ar-010", shownIds = listOf("subsonic:ar-022", "subsonic:ar-022"))

        assertEquals(listOf("launch"), calls)
    }

    @Test
    fun should_launchThePage_when_itIsAnotherKindOfPage() {
        // An album page's own id, asked for as an artist: never this page.
        open(
            DetailPageKind.Artist,
            "subsonic:al-081",
            shownIds = listOf("subsonic:al-081", null),
            shown = DetailPageKind.Album
        )

        assertEquals(listOf("launch"), calls)
    }

    @Test
    fun should_revealThePage_when_itsContentResolvedToTheRequestedId() {
        // An Apple Music library album the page shows as its catalog album; Now Playing names the catalog id.
        open(
            DetailPageKind.Album,
            "applemusic:1440857781",
            shownIds = listOf("applemusic:library:l.abc", "applemusic:1440857781"),
            shown = DetailPageKind.Album
        )

        assertEquals(listOf("collapse"), calls)
    }

    @Test
    fun should_launchThePage_when_theSameRawIdBelongsToAnotherProvider() {
        open(DetailPageKind.Artist, "spotify:ar-022", shownIds = listOf("subsonic:ar-022", null))

        assertEquals(listOf("launch"), calls)
    }
}
