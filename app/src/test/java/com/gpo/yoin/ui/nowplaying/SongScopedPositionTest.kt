package com.gpo.yoin.ui.nowplaying

import org.junit.Assert.assertEquals
import org.junit.Test

class SongScopedPositionTest {

    @Test
    fun should_passThePlayheadThrough_when_itBelongsToTheDrawnSong() {
        val position = SongScopedPosition()

        assertEquals(1_000L, position.resolve(NowPlayingPlayhead("a", 1_000L), renderedSongId = "a"))
        assertEquals(1_250L, position.resolve(NowPlayingPlayhead("a", 1_250L), renderedSongId = "a"))
    }

    @Test
    fun should_holdTheDrawnSongsLastPosition_when_thePlayheadMovesToTheNextSongFirst() {
        val position = SongScopedPosition()
        position.resolve(NowPlayingPlayhead("a", 239_750L), renderedSongId = "a")

        // Song change: the eager playhead lands before uiState does.
        assertEquals(239_750L, position.resolve(NowPlayingPlayhead("b", 0L), renderedSongId = "a"))
        assertEquals(239_750L, position.resolve(NowPlayingPlayhead("b", 250L), renderedSongId = "a"))

        // The screen catches up: the new song reads live.
        assertEquals(250L, position.resolve(NowPlayingPlayhead("b", 250L), renderedSongId = "b"))
    }

    @Test
    fun should_startTheDrawnSongAtZero_when_theScreenIsAheadOfThePlayhead() {
        val position = SongScopedPosition()
        position.resolve(NowPlayingPlayhead("a", 120_000L), renderedSongId = "a")

        assertEquals(0L, position.resolve(NowPlayingPlayhead("a", 120_250L), renderedSongId = "b"))
    }

    @Test
    fun should_readRaw_when_nothingIsDrawnOrThePlayheadHasNoSong() {
        val position = SongScopedPosition()

        assertEquals(5_000L, position.resolve(NowPlayingPlayhead("a", 5_000L), renderedSongId = null))
        assertEquals(7_000L, position.resolve(NowPlayingPlayhead(null, 7_000L), renderedSongId = "a"))
    }
}
