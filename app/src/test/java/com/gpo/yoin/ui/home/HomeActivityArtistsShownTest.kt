package com.gpo.yoin.ui.home

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasScrollToNodeAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeUp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.gpo.yoin.data.local.ActivityActionType
import com.gpo.yoin.data.local.ActivityEntityType
import com.gpo.yoin.data.local.ActivityEvent
import com.gpo.yoin.data.model.Album
import com.gpo.yoin.data.model.MediaId
import com.gpo.yoin.ui.experience.LayoutMode
import com.gpo.yoin.ui.experience.LocalMotionProfile
import com.gpo.yoin.ui.experience.LocalYoinWindowInfo
import com.gpo.yoin.ui.experience.MotionProfile
import com.gpo.yoin.ui.experience.RevealState
import com.gpo.yoin.ui.experience.ShellChromeForm
import com.gpo.yoin.ui.experience.YoinWindowInfo
import com.gpo.yoin.ui.experience.rememberRevealState
import com.gpo.yoin.ui.theme.YoinTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Owner Q16: the feed tells Home which artists its Activities picture — the
 * ones whose portraits are worth a request — and whether it rests: on
 * screen, nothing over it, its list still.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w400dp-h640dp")
class HomeActivityArtistsShownTest {

    @get:Rule
    val rule = createComposeRule()

    private val shown = mutableListOf<List<String>>()
    private val atRest = mutableListOf<Boolean>()

    private var homeCovered by mutableStateOf(false)
    private var feedShown by mutableStateOf(true)

    private fun setHome(
        artists: Int = 6,
        rows: HomeRowPreset = HomeRowPreset.Default,
        windowInfo: YoinWindowInfo? = null,
        memoriesRevealState: (@Composable () -> RevealState)? = null,
        lifecycleOwner: LifecycleOwner? = null
    ) {
        val sections = HomeLayout.Default.sections.map { state ->
            if (state.section == HomeSection.Activities) state.copy(rows = rows) else state
        }
        rule.setContent {
            val window = windowInfo ?: LocalYoinWindowInfo.current
            val owner = lifecycleOwner ?: LocalLifecycleOwner.current
            CompositionLocalProvider(
                LocalMotionProfile provides MotionProfile.AdaptiveReduced,
                LocalYoinWindowInfo provides window,
                LocalLifecycleOwner provides owner
            ) {
                YoinTheme {
                    if (feedShown) {
                        HomeEditorialContent(
                            activities = listOf(albumPlay()) + (1..artists).map(::artistPlay),
                            recentlyPlayed = (1..8).map(::album),
                            homeCovered = homeCovered,
                            sections = sections,
                            onNavigateToSettings = {},
                            onNavigateToMemories = {},
                            memoriesRevealState = memoriesRevealState?.invoke() ?: rememberRevealState(),
                            onAlbumClick = { _, _ -> },
                            onArtistClick = {},
                            onPlaylistClick = {},
                            onSongClick = {},
                            buildCoverArtUrl = { "" },
                            onActivityArtistsShown = { shown += it },
                            onFeedAtRestChanged = { atRest += it }
                        )
                    }
                }
            }
        }
        rule.waitForIdle()
    }

    @Test
    fun should_reportThePicturedArtists_when_thePhoneBentoComposes() {
        setHome()

        // The phone bento: the album as hero, a small and a wide card, then a
        // strip — text only, so its artist is not asked for.
        assertEquals(listOf("spotify:artist-1", "spotify:artist-2"), shown.last())
    }

    @Test
    @Config(qualifiers = "w800dp-h1280dp")
    fun should_reportTheUnitCardsButNotTheStrips_when_theTabletBentoIsXl() {
        setHome(artists = 12, rows = HomeRowPreset.XL, windowInfo = tabletWindow(feedUnits = 4))

        // N = 4 at XL: hero + wide, then small | wide | small, then wide | wide,
        // then two strips (artists 7 and 8).
        assertEquals((1..6).map { "spotify:artist-$it" }, shown.last())
    }

    @Test
    fun should_reportAtRest_when_theFeedIsStillAndUncovered() {
        setHome()

        assertEquals(listOf(true), atRest)
    }

    @Test
    fun should_reportNotAtRest_while_theFeedIsScrolled() {
        setHome()

        rule.onNode(hasScrollToNodeAction() and IS_VERTICAL).performTouchInput { swipeUp() }
        rule.waitForIdle()

        assertTrue(false in atRest)
        assertEquals(true, atRest.last())
    }

    @Test
    fun should_reportNotAtRest_when_somethingCoversHome() {
        setHome()

        homeCovered = true
        rule.waitForIdle()
        assertEquals(false, atRest.last())

        homeCovered = false
        rule.waitForIdle()
        assertEquals(true, atRest.last())
    }

    @Test
    fun should_reportNotAtRest_when_memoriesIsOverHome() {
        setHome(memoriesRevealState = { rememberRevealState(initialFraction = 0f) })

        assertEquals(listOf(false), atRest)
    }

    @Test
    fun should_reportNotAtRest_when_homeIsNotResumed() {
        val owner = TestOwner()
        owner.registry.currentState = Lifecycle.State.RESUMED
        setHome(lifecycleOwner = owner)
        assertEquals(true, atRest.last())

        // A detail Activity over the shell, or the app in the background.
        rule.runOnIdle { owner.registry.currentState = Lifecycle.State.STARTED }
        rule.waitForIdle()
        assertEquals(false, atRest.last())

        rule.runOnIdle { owner.registry.currentState = Lifecycle.State.RESUMED }
        rule.waitForIdle()
        assertEquals(true, atRest.last())
    }

    @Test
    fun should_reportNotAtRest_when_theFeedLeaves() {
        setHome()

        // Library, or a switch's Loading, takes Home's place.
        feedShown = false
        rule.waitForIdle()

        assertEquals(false, atRest.last())
    }

    private class TestOwner : LifecycleOwner {
        val registry: LifecycleRegistry = LifecycleRegistry.createUnsafe(this)

        override val lifecycle: Lifecycle get() = registry
    }

    private fun tabletWindow(feedUnits: Int) = YoinWindowInfo(
        layoutMode = LayoutMode.Medium,
        isWidthAtLeastMedium = true,
        isHeightAtLeastMedium = true,
        hingeBounds = null,
        chromeForm = ShellChromeForm.CenteredBar,
        feedUnits = feedUnits
    )

    private fun albumPlay() = ActivityEvent(
        entityType = ActivityEntityType.ALBUM.name,
        actionType = ActivityActionType.PLAYED.name,
        entityId = "album-0",
        provider = MediaId.PROVIDER_SPOTIFY,
        title = "Album 0",
        subtitle = "Artist",
        timestamp = 100L
    )

    private fun artistPlay(index: Int) = ActivityEvent(
        entityType = ActivityEntityType.ARTIST.name,
        actionType = ActivityActionType.PLAYED.name,
        entityId = "artist-$index",
        provider = MediaId.PROVIDER_SPOTIFY,
        title = "Artist $index",
        subtitle = "Artist",
        timestamp = 100L - index
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
