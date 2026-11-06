package com.gpo.yoin.ui.detail

import androidx.compose.ui.geometry.Rect
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaylistSleevesTest {

    @Test
    fun should_takeFirstTwoDistinctCovers_when_heroIsThePlaylistsOwnArt() {
        val covers = playlistSleeveCovers(
            songCovers = listOf("a", "a", "b", "c"),
            heroCover = "mosaic",
            heroIsFirstTrackCover = false,
        )
        assertEquals(listOf("a", "b"), covers)
    }

    @Test
    fun should_skipCoversEqualToTheHero_when_aSongSharesTheHerosArt() {
        val covers = playlistSleeveCovers(
            songCovers = listOf("hero", "a", "hero", "b"),
            heroCover = "hero",
            heroIsFirstTrackCover = false,
        )
        assertEquals(listOf("a", "b"), covers)
    }

    @Test
    fun should_skipTheFirstSongsAlbum_when_heroIsTheFirstTrackFallback() {
        // The hero url may be spelled differently from the row's (another size):
        // the first song's album is still the hero.
        val covers = playlistSleeveCovers(
            songCovers = listOf("first", "a", "first", "b", "c"),
            heroCover = "first-full-size",
            heroIsFirstTrackCover = true,
        )
        assertEquals(listOf("a", "b"), covers)
    }

    @Test
    fun should_returnOneSleeve_when_onlyOneOtherAlbumExists() {
        val covers = playlistSleeveCovers(
            songCovers = listOf("first", "first", "a", "a"),
            heroCover = "first",
            heroIsFirstTrackCover = true,
        )
        assertEquals(listOf("a"), covers)
    }

    @Test
    fun should_returnNone_when_playlistIsEmptyOrAllOneAlbum() {
        assertEquals(emptyList<String>(), playlistSleeveCovers(emptyList(), "hero", heroIsFirstTrackCover = false))
        assertEquals(emptyList<String>(), playlistSleeveCovers(emptyList(), null, heroIsFirstTrackCover = false))
        assertEquals(
            emptyList<String>(),
            playlistSleeveCovers(listOf("first", "first"), "first", heroIsFirstTrackCover = true),
        )
    }

    @Test
    fun should_ignoreMissingCovers_when_songsHaveNoArt() {
        val covers = playlistSleeveCovers(
            songCovers = listOf(null, "", "  ", "a", null, "b"),
            heroCover = null,
            heroIsFirstTrackCover = false,
        )
        assertEquals(listOf("a", "b"), covers)
    }

    @Test
    fun should_notSkipFirstSong_when_firstTrackFallbackHasNoCover() {
        val covers = playlistSleeveCovers(
            songCovers = listOf(null, "a", "b"),
            heroCover = null,
            heroIsFirstTrackCover = true,
        )
        assertEquals(listOf("a", "b"), covers)
    }

    @Test
    fun should_useTheFullSpread_when_thereIsRoom() {
        assertEquals(PlaylistSleeveSpread, playlistSleeveSpread(coverSide = 300f, sideRoom = 200f), 1e-6f)
    }

    @Test
    fun should_shrinkTheSpread_when_theBoxIsNarrow() {
        val side = 300f
        val room = 30f
        val spread = playlistSleeveSpread(side, room)
        assertTrue(spread > 0f && spread < PlaylistSleeveSpread)
        // The fully opened, tilted sleeve's outer edge lands exactly on the room's edge.
        val overhang = (spread + PlaylistSleeveHalfExtent - 0.5f) * side
        assertEquals(room, overhang, 1e-3f)
    }

    @Test
    fun should_closeTheSpread_when_thereIsNoRoom() {
        assertEquals(0f, playlistSleeveSpread(coverSide = 300f, sideRoom = -40f), 0f)
        assertEquals(0f, playlistSleeveSpread(coverSide = 0f, sideRoom = 40f), 0f)
    }

    @Test
    fun should_keepAClosedTiltedSleeveInsideTheCover_when_spreadIsZero() {
        assertTrue(PlaylistSleeveHalfExtent < 0.5f)
    }

    @Test
    fun should_fanStartSleeveLeftAndEndSleeveRight_when_fullyOpen() {
        val cover = Rect(0f, 0f, 100f, 100f)
        val left = playlistSleevePose(cover, index = 0, open = 1f, grow = 1f, spread = 0.2f, rtl = false)
        val right = playlistSleevePose(cover, index = 1, open = 1f, grow = 1f, spread = 0.2f, rtl = false)
        assertEquals(30f, left.center.x, 1e-4f)
        assertEquals(70f, right.center.x, 1e-4f)
        assertEquals(57f, left.center.y, 1e-4f)
        assertEquals(86f, left.side, 1e-4f)
        assertEquals(-PlaylistSleeveTiltDegrees, left.rotation, 1e-4f)
        assertEquals(PlaylistSleeveTiltDegrees, right.rotation, 1e-4f)
    }

    @Test
    fun should_mirrorTheFan_when_layoutIsRtl() {
        val cover = Rect(0f, 0f, 100f, 100f)
        val start = playlistSleevePose(cover, index = 0, open = 1f, grow = 1f, spread = 0.2f, rtl = true)
        assertEquals(70f, start.center.x, 1e-4f)
        assertEquals(PlaylistSleeveTiltDegrees, start.rotation, 1e-4f)
    }

    @Test
    fun should_hideBehindTheCoverCentre_when_folded() {
        val cover = Rect(10f, 20f, 110f, 120f)
        val pose = playlistSleevePose(cover, index = 1, open = 0f, grow = 1f, spread = 0.2f, rtl = false)
        assertEquals(cover.center, pose.center)
        assertEquals(0f, pose.rotation, 0f)
        assertTrue(pose.rect.left > cover.left && pose.rect.right < cover.right)
    }

    @Test
    fun should_neverGoUnderTheMinimumGap_when_coverIsSmall() {
        assertEquals(9f, playlistSleeveGap(coverSide = 48f, minGapPx = 9f), 0f)
        assertEquals(25f, playlistSleeveGap(coverSide = 1000f, minGapPx = 9f), 1e-4f)
    }

    @Test
    fun should_matchTheStagedBeat_when_coverIsStillRising() {
        val rect = Rect(0f, 0f, 100f, 100f)
        assertEquals(rect, stagedBeatRect(rect, progress = 1f, rise = 20f, scaleFrom = 0.94f))
        val start = stagedBeatRect(rect, progress = 0f, rise = 20f, scaleFrom = 0.94f)
        assertEquals(94f, start.width, 1e-4f)
        assertEquals(70f, start.center.y, 1e-4f)
    }
}
