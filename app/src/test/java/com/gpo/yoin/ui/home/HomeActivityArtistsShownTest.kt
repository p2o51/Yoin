package com.gpo.yoin.ui.home

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasScrollToNodeAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeUp
import com.gpo.yoin.data.local.ActivityActionType
import com.gpo.yoin.data.local.ActivityEntityType
import com.gpo.yoin.data.local.ActivityEvent
import com.gpo.yoin.data.model.Album
import com.gpo.yoin.data.model.MediaId
import com.gpo.yoin.ui.experience.LocalMotionProfile
import com.gpo.yoin.ui.experience.MotionProfile
import com.gpo.yoin.ui.theme.YoinTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Owner Q16: the feed tells Home which artists its Activities seat — the
 * ones whose portraits are worth a request — and when its list moves.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w400dp-h640dp")
class HomeActivityArtistsShownTest {

    @get:Rule
    val rule = createComposeRule()

    private val shown = mutableListOf<List<String>>()
    private val scrolling = mutableListOf<Boolean>()

    private fun setHome() {
        rule.setContent {
            CompositionLocalProvider(LocalMotionProfile provides MotionProfile.AdaptiveReduced) {
                YoinTheme {
                    HomeEditorialContent(
                        activities = listOf(albumPlay()) + (1..6).map(::artistPlay),
                        recentlyPlayed = (1..8).map(::album),
                        onNavigateToSettings = {},
                        onNavigateToMemories = {},
                        onAlbumClick = { _, _ -> },
                        onArtistClick = {},
                        onPlaylistClick = {},
                        onSongClick = {},
                        buildCoverArtUrl = { "" },
                        onActivityArtistsShown = { shown += it },
                        onFeedScrollChanged = { scrolling += it }
                    )
                }
            }
        }
        rule.waitForIdle()
    }

    @Test
    fun should_reportTheSeatedArtists_when_theBentoComposes() {
        setHome()

        // The phone bento seats a hero (the album) and three supporting cards.
        assertEquals(listOf("spotify:artist-1", "spotify:artist-2", "spotify:artist-3"), shown.last())
    }

    @Test
    fun should_reportTheListMoving_when_theFeedIsScrolled() {
        setHome()
        assertEquals(listOf(false), scrolling)

        rule.onNode(hasScrollToNodeAction() and IS_VERTICAL).performTouchInput { swipeUp() }
        rule.waitForIdle()

        assertTrue(true in scrolling)
        assertEquals(false, scrolling.last())
    }

    private fun albumPlay() = ActivityEvent(
        entityType = ActivityEntityType.ALBUM.name,
        actionType = ActivityActionType.PLAYED.name,
        entityId = "album-0",
        provider = MediaId.PROVIDER_SPOTIFY,
        title = "Album 0",
        subtitle = "Artist",
        timestamp = 10L
    )

    private fun artistPlay(index: Int) = ActivityEvent(
        entityType = ActivityEntityType.ARTIST.name,
        actionType = ActivityActionType.PLAYED.name,
        entityId = "artist-$index",
        provider = MediaId.PROVIDER_SPOTIFY,
        title = "Artist $index",
        subtitle = "Artist",
        timestamp = 10L - index
    )

    private fun album(index: Int) = Album(
        id = MediaId.spotify("played-$index"),
        name = "Played $index",
        artist = "Artist",
        artistId = null,
        coverArt = null,
        songCount = 10,
        durationSec = null,
        year = 2024,
        genre = null
    )

    private companion object {
        val IS_VERTICAL = SemanticsMatcher.keyIsDefined(SemanticsProperties.VerticalScrollAxisRange)
    }
}
