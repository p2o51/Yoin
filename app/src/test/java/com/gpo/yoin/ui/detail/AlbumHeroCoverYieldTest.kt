package com.gpo.yoin.ui.detail

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The album hero drops its cover only when the Now Playing side panel beside it
 * is already showing the same album's cover (two-pane duplicate, owner
 * 2026-11-03). Every other state keeps the page exactly as it was.
 */
class AlbumHeroCoverYieldTest {

    @Test
    fun should_yieldCover_when_panelBesidePlaysThisAlbum() {
        assertTrue(
            albumHeroYieldsCover(
                layout = AlbumBarLayout.Compact,
                panelBeside = true,
                playingThisAlbum = true,
            ),
        )
    }

    @Test
    fun should_keepCover_when_panelPlaysAnotherAlbum() {
        assertFalse(
            albumHeroYieldsCover(
                layout = AlbumBarLayout.Compact,
                panelBeside = true,
                playingThisAlbum = false,
            ),
        )
    }

    @Test
    fun should_keepCover_when_noPanelBeside() {
        // Closed, or a full-window player over the page: nothing sits beside it.
        assertFalse(
            albumHeroYieldsCover(
                layout = AlbumBarLayout.Compact,
                panelBeside = false,
                playingThisAlbum = true,
            ),
        )
    }

    @Test
    fun should_keepCover_when_layoutIsNotThePortraitHero() {
        for (layout in listOf(AlbumBarLayout.Short, AlbumBarLayout.Landscape, AlbumBarLayout.Medium, null)) {
            assertFalse(
                "layout $layout keeps its cover",
                albumHeroYieldsCover(layout = layout, panelBeside = true, playingThisAlbum = true),
            )
        }
    }
}
