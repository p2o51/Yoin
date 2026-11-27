package com.gpo.yoin.ui.nowplaying

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpRect
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.height
import androidx.compose.ui.unit.width
import androidx.test.core.app.ApplicationProvider
import com.gpo.yoin.R
import com.gpo.yoin.data.local.SongAboutEntry
import com.gpo.yoin.data.repository.ActivityContext
import com.gpo.yoin.player.PlayMode
import com.gpo.yoin.ui.experience.LocalYoinWindowInfo
import com.gpo.yoin.ui.experience.ProvidePreviewWindow
import com.gpo.yoin.ui.experience.resolveYoinWindowInfo
import com.gpo.yoin.ui.theme.YoinTheme
import java.time.Duration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowSystemClock

/**
 * PR 3 of the Now Playing review (owner 2026-11-27): a phone on its side is the two-column player in its
 * dense form at every width (no 792dp cliff, no Expanded state) — the controls always under the cover, one
 * top row with the tabs, the page and its action row on the right; the tablet keeps its two columns; the
 * Tabletop hinge is built from the same pieces; the narrow side panel's bar keeps only the album name.
 */
@RunWith(RobolectricTestRunner::class)
class NowPlayingTwoColumnTest {

    @get:Rule
    val rule = createComposeRule()

    private val res get() = ApplicationProvider.getApplicationContext<android.content.Context>().resources

    // ── Phone landscape: one rule at every width ──────────────────────────

    @Config(qualifiers = "w915dp-h412dp-land")
    @Test
    fun should_keepTheControlsAndThePageApart_when_915x412() = assertDense(915, 412)

    @Config(qualifiers = "w851dp-h393dp-land")
    @Test
    fun should_keepTheControlsAndThePageApart_when_851x393() = assertDense(851, 393)

    @Config(qualifiers = "w740dp-h360dp-land")
    @Test
    fun should_keepTheControlsAndThePageApart_when_740x360() = assertDense(740, 360)

    @Config(qualifiers = "w568dp-h320dp-land")
    @Test
    fun should_keepTheControlsAndThePageApart_when_568x320() = assertDense(568, 320)

    @Config(qualifiers = "w915dp-h412dp-land")
    @Test
    fun should_foldTheKeysAwayAndGrowTheCover_when_focusIsLeftAtEase() {
        setPlayer(915, 412, NowPlayingPresentation.Landscape, stage = NowPlayingStageMode.Compact)
        val rest = rule.onNodeWithTag(TwoColumnCoverTag).getBoundsInRoot()
        setPlayer(915, 412, NowPlayingPresentation.Landscape, stage = NowPlayingStageMode.Immersive, settle = false)
        rule.mainClock.advanceTimeBy(1_000L)
        val focus = rule.onNodeWithTag(TwoColumnCoverTag).getBoundsInRoot()
        // In focus, before the count runs out, the keys are all still there.
        assertTransportOnScreen()
        // FocusIdleMs without a touch (the count reads SystemClock, which Robolectric holds still).
        ShadowSystemClock.advanceBy(Duration.ofSeconds(10))
        rule.mainClock.advanceTimeBy(5_000L)
        val ease = rule.onNodeWithTag(TwoColumnCoverTag).getBoundsInRoot()
        assertTrue("focus ${focus.height} < rest ${rest.height}", focus.height >= rest.height - 0.5.dp)
        assertTrue("at ease ${ease.height} not over focus ${focus.height}", ease.height > focus.height + 8.dp)
        // The progress bar and the title stay; the cover never runs into them.
        val title = rule.onAllNodesWithText("Goodnight, Tokyo").fetchSemanticsNodes().map { it.boundsDp() }
        assertTrue("no title", title.isNotEmpty())
        assertTrue("cover ${ease.bottom} over the title ${title.minOf { it.top }}", ease.bottom <= title.minOf { it.top } + 0.5.dp)
    }

    @Config(qualifiers = "w740dp-h360dp-land")
    @Test
    fun should_hangTheAskCardFromThePageFoot_when_landscapeAsks() {
        setPlayer(740, 360, NowPlayingPresentation.Landscape, page = NowPlayingDetailPage.About, ask = AskBarState.Focused)
        val root = rule.onRoot().getBoundsInRoot()
        val ask = rule.onNodeWithTag(PageAskTag, useUnmergedTree = true).getBoundsInRoot()
        val tabs = tabBounds()
        // Under the top row (never hanging from it over the page), inside the window, in the right column.
        assertTrue("ask top ${ask.top} over the tabs ${tabs.maxOf { it.bottom }}", ask.top >= tabs.maxOf { it.bottom } - 0.5.dp)
        assertTrue("ask bottom ${ask.bottom}", ask.bottom <= root.bottom + 0.5.dp)
        val cover = rule.onNodeWithTag(TwoColumnCoverTag).getBoundsInRoot()
        assertTrue("ask left ${ask.left} on the left column", ask.left >= cover.right)
    }

    @Config(qualifiers = "w740dp-h360dp-land")
    @Test
    fun should_endTheAboutListAboveTheAskBar_when_landscape() {
        setPlayer(740, 360, NowPlayingPresentation.Landscape, page = NowPlayingDetailPage.About)
        val ask = rule.onNodeWithTag(PageAskTag, useUnmergedTree = true).getBoundsInRoot()
        val text = rule.onAllNodesWithText("Mara Quinn").fetchSemanticsNodes().map { it.boundsDp() }
        assertTrue("no About rows", text.isNotEmpty())
        text.forEach { row -> assertTrue("row ${row.bottom} under the Ask bar ${ask.top}", row.bottom <= ask.top + 0.5.dp) }
    }

    @Config(qualifiers = "w851dp-h393dp-land")
    @Test
    fun should_keepTheErrorAndItsWayOutOnScreen_when_851Landscape() = assertPendingFits(851, 393, error = true)

    @Config(qualifiers = "w740dp-h360dp-land")
    @Test
    fun should_keepTheTitleOnScreen_when_740LandscapeLaunching() = assertPendingFits(740, 360, error = false)

    // ── Tablet landscape ──────────────────────────────────────────────────

    @Config(qualifiers = "w1280dp-h800dp-land")
    @Test
    fun should_keepTheTwoColumns_when_tabletLandscape() {
        setPlayer(1280, 800, NowPlayingPresentation.DualPane, sing = true)
        val keys = lyricKeys()
        assertKeys(keys)
        assertClear(keys, tabBounds())
        assertTransportOnScreen()
        // The top bar still carries the write icon here (only the dense row gives it up).
        assertTrue(rule.onAllNodes(hasContentDescription(res.getString(R.string.np_pill_write))).fetchSemanticsNodes().isNotEmpty())
    }

    // ── Tabletop ──────────────────────────────────────────────────────────

    @Config(qualifiers = "w841dp-h701dp")
    @Test
    fun should_buildTheHingeFromTheStandardPieces_when_tabletop() {
        setPlayer(841, 701, NowPlayingPresentation.Tabletop, page = NowPlayingDetailPage.About, hingeAt = 350)
        val hinge = 350.dp
        // Above the fold: the cover, the rating column and the tabs; About and Note one tap away.
        val cover = rule.onNodeWithTag(TwoColumnCoverTag).getBoundsInRoot()
        assertTrue("cover ${cover.bottom} past the hinge", cover.bottom <= hinge + 0.5.dp)
        val rating = rule.onNode(ratingBar).getBoundsInRoot()
        assertTrue("rating ${rating.bottom} past the hinge", rating.bottom <= hinge + 0.5.dp)
        assertTrue("rating beside the cover", rating.left >= cover.right - 0.5.dp)
        tabBounds().forEach { assertTrue("tab $it past the hinge", it.bottom <= hinge + 0.5.dp) }
        // Below it: the standard 56dp keys (not 72) and the page's actions, Share included.
        val prev = rule.onNodeWithContentDescription(res.getString(R.string.np_cd_skip_previous)).getBoundsInRoot()
        assertTrue("prev ${prev.top} above the hinge", prev.top >= hinge - 0.5.dp)
        assertEquals(56f, prev.width.value, 0.5f)
        val share = onScreen(PageShareTag)
        assertTrue("share ${share.top} above the hinge", share.top >= hinge - 0.5.dp)
        val ask = rule.onNodeWithTag(PageAskTag, useUnmergedTree = true).getBoundsInRoot()
        assertTrue("ask ${ask.top} above the hinge", ask.top >= hinge - 0.5.dp)
        assertTrue("ask ${ask.right} over the share key", ask.right <= share.left + 0.5.dp)
    }

    @Config(qualifiers = "w841dp-h701dp")
    @Test
    fun should_keepTheLyricKeysBelowTheHinge_when_tabletopSings() {
        setPlayer(841, 701, NowPlayingPresentation.Tabletop, sing = true, hingeAt = 350)
        val keys = lyricKeys()
        assertKeys(keys)
        keys.forEach { assertTrue("key ${it.top} above the hinge", it.top >= 350.dp - 0.5.dp) }
    }

    // ── The narrow side panel's bar ───────────────────────────────────────

    @Config(qualifiers = "w360dp-h640dp")
    @Test
    fun should_keepOnlyTheAlbumName_when_theOneLineBarAlsoCarriesTheToggle() {
        setPlayer(360, 640, NowPlayingPresentation.Panel, toggle = true, top = 24.dp, bottom = 24.dp)
        assertEquals(0, rule.onAllNodesWithText(res.getString(R.string.np_from_album)).fetchSemanticsNodes().size)
        assertTrue(rule.onAllNodesWithText("Glass Orchard").fetchSemanticsNodes().isNotEmpty())
        // Its tabs are still there, beside the name.
        assertTrue(tabBounds().size >= 3)
    }

    @Config(qualifiers = "w360dp-h640dp")
    @Test
    fun should_keepThePlayingFromLabel_when_theOneLineBarHasNoToggle() {
        setPlayer(360, 640, NowPlayingPresentation.Phone, top = 32.dp, bottom = 24.dp)
        assertTrue(rule.onAllNodesWithText(res.getString(R.string.np_from_album)).fetchSemanticsNodes().isNotEmpty())
    }

    // ── helpers ───────────────────────────────────────────────────────────

    private fun assertDense(width: Int, height: Int) {
        for (page in NowPlayingDetailPage.entries) {
            setPlayer(width, height, NowPlayingPresentation.Landscape, page = page)
            val root = rule.onRoot().getBoundsInRoot()
            val cover = rule.onNodeWithTag(TwoColumnCoverTag).getBoundsInRoot()
            // The controls never leave, whatever the page.
            val transport = assertTransportOnScreen()
            transport.forEach { key ->
                assertTrue("$width $page: key ${key.top} into the cover ${cover.bottom}", key.top >= cover.bottom - 0.5.dp)
                assertTrue("$width $page: key ${key.bottom} off the window", key.bottom <= root.bottom + 0.5.dp)
            }
            val leftEdge = transport.maxOf { it.right }
            // One top row on the right: the tabs, clear of the back arrow and the icons.
            val tabs = tabBounds()
            assertEquals("$width $page: tabs", 3, tabs.size)
            val icons = listOf(R.string.np_pill_queue, R.string.np_pill_devices, R.string.np_cd_wide_close)
                .map { rule.onNodeWithContentDescription(res.getString(it)).getBoundsInRoot() }
            assertClear(icons, tabs)
            (tabs + icons).forEach { item ->
                assertTrue("$width $page: $item over the left column ($leftEdge)", item.left >= leftEdge - 0.5.dp)
                assertTrue("$width $page: $item off the window", item.right <= root.right + 0.5.dp)
            }
            // The page's actions at the right column's foot.
            when (page) {
                NowPlayingDetailPage.Lyrics -> {
                    val keys = lyricKeys(optional = true)
                    assertKeys(keys)
                    assertClear(keys, tabs)
                    keys.forEach { assertTrue("$width: lyric key over the left column", it.left >= leftEdge - 0.5.dp) }
                }
                NowPlayingDetailPage.About, NowPlayingDetailPage.Note -> {
                    val share = onScreen(PageShareTag)
                    assertTrue("$width $page: share ${share.width}", share.width >= 44.dp - 0.5.dp)
                    assertTrue("$width $page: share off the window", share.bottom <= root.bottom + 0.5.dp)
                    assertTrue("$width $page: share over the left column", share.left >= leftEdge - 0.5.dp)
                }
            }
        }
    }

    /** Previous, play / pause and next, each whole and on screen. */
    private fun assertTransportOnScreen(): List<DpRect> {
        val keys = listOf(R.string.np_cd_skip_previous, R.string.np_cd_skip_next)
            .map { rule.onNodeWithContentDescription(res.getString(it)).getBoundsInRoot() }
        keys.forEach { key ->
            assertTrue("transport key ${key.width}", key.width >= 44.dp - 0.5.dp)
            assertTrue("transport key ${key.height}", key.height >= 44.dp - 0.5.dp)
        }
        return keys
    }

    private val ratingBar = SemanticsMatcher("the rating bar") { node ->
        val info = if (SemanticsProperties.ProgressBarRangeInfo in node.config) {
            node.config[SemanticsProperties.ProgressBarRangeInfo]
        } else {
            null
        }
        info?.range?.endInclusive == 10f
    }

    private fun lyricKeys(optional: Boolean = false): List<DpRect> = listOf(
        R.string.np_cd_search_lyrics,
        R.string.np_cd_translate_lyrics,
        R.string.np_cd_return_current_line,
        R.string.np_cd_select_lyrics,
    ).mapNotNull { id ->
        val nodes = rule.onAllNodes(hasContentDescription(res.getString(id)))
            .fetchSemanticsNodes()
        if (nodes.isEmpty() && optional) null else nodes.first().boundsDp()
    }

    private fun assertKeys(keys: List<DpRect>) {
        assertTrue("no lyric keys", keys.size >= 2)
        keys.forEach { key ->
            assertTrue("key width ${key.width}", key.width >= 44.dp - 0.5.dp)
            assertTrue("key height ${key.height}", key.height >= 44.dp - 0.5.dp)
        }
        keys.sortedBy { it.left }.zipWithNext().forEach { (a, b) ->
            assertTrue("keys overlap: ${a.right} > ${b.left}", a.right <= b.left + 0.5.dp)
        }
    }

    /** The one node with [tag] inside the window (the pager keeps its neighbour pages composed off screen). */
    private fun onScreen(tag: String): DpRect {
        val root = rule.onRoot().getBoundsInRoot()
        return rule.onAllNodesWithTag(tag).fetchSemanticsNodes().map { it.boundsDp() }
            // Off-screen neighbours report bounds clipped to nothing at the pager's edge.
            .single { it.width > 1.dp && it.left >= root.left - 0.5.dp && it.right <= root.right + 0.5.dp }
    }

    private fun tabBounds(): List<DpRect> =
        rule.onAllNodesWithTag(TextTabTag).fetchSemanticsNodes().map { it.boundsDp() }

    private fun assertClear(items: List<DpRect>, others: List<DpRect>) {
        for (a in items) {
            for (b in others) {
                val overlaps = a.left < b.right && b.left < a.right && a.top < b.bottom && b.top < a.bottom
                assertFalse("$a on $b", overlaps)
            }
        }
    }

    private fun androidx.compose.ui.semantics.SemanticsNode.boundsDp(): DpRect = with(rule.density) {
        val b = boundsInRoot
        DpRect(b.left.toDp(), b.top.toDp(), b.right.toDp(), b.bottom.toDp())
    }

    private fun assertPendingFits(width: Int, height: Int, error: Boolean) {
        rule.mainClock.autoAdvance = false
        rule.setContent {
            Frame(width, height, top = 24.dp, bottom = 16.dp, hingeAt = null) {
                NowPlayingScreen(
                    uiState = if (error) {
                        NowPlayingUiState.ConnectError(
                            songTitle = "Goodnight, Tokyo",
                            artist = "The Meridians",
                            coverArtUrl = null,
                            message = com.gpo.yoin.ui.common.UiText.Res(R.string.np_msg_couldnt_start),
                            opensSpotify = true,
                        )
                    } else {
                        NowPlayingUiState.Launching(
                            songTitle = "Goodnight, Tokyo",
                            artist = "The Meridians",
                            albumName = "Glass Orchard",
                            coverArtUrl = null,
                            durationMs = 240_000L,
                            hint = com.gpo.yoin.ui.common.UiText.Res(R.string.np_msg_connecting_spotify),
                        )
                    },
                    positionMs = { 0L },
                    bufferedMs = { 0L },
                    hasAudioSpectrum = false,
                    onTogglePlayPause = {},
                    onSkipNext = {},
                    onSkipPrevious = {},
                    onSeek = {},
                    onRatingChange = {},
                    onToggleFavorite = {},
                    onAddCurrentToPlaylist = {},
                    onSkipToQueueItem = {},
                    presentation = NowPlayingPresentation.Landscape,
                )
            }
        }
        rule.mainClock.advanceTimeBy(1_000L)
        val root = rule.onRoot().getBoundsInRoot()
        val back = rule.onNodeWithTag(PendingBackTag).getBoundsInRoot()
        assertTrue("back at ${back.left},${back.top}", back.left < 72.dp && back.top < 40.dp)
        val title = rule.onAllNodesWithText("Goodnight, Tokyo").fetchSemanticsNodes().first().boundsDp()
        assertTrue("title $title outside $root", title.bottom <= root.bottom + 0.5.dp && title.right <= root.right + 0.5.dp)
        if (error) {
            val open = rule.onNodeWithTag(PendingOpenSpotifyTag)
            open.performScrollTo()
            val b = open.getBoundsInRoot()
            assertTrue("open Spotify $b outside $root", b.bottom <= root.bottom + 0.5.dp && b.right <= root.right + 0.5.dp)
        }
    }

    private fun setPlayer(
        width: Int,
        height: Int,
        presentation: NowPlayingPresentation,
        stage: NowPlayingStageMode = NowPlayingStageMode.Compact,
        page: NowPlayingDetailPage = NowPlayingDetailPage.Lyrics,
        ask: AskBarState = AskBarState.Idle,
        sing: Boolean = false,
        hingeAt: Int? = null,
        toggle: Boolean = false,
        top: Dp = 24.dp,
        bottom: Dp = 16.dp,
        settle: Boolean = true,
    ) {
        rule.mainClock.autoAdvance = false
        content.value = {
            Frame(width, height, top = top, bottom = bottom, hingeAt = hingeAt) {
                val singUi = if (sing) {
                    NowPlayingSingUi(
                        mode = com.gpo.yoin.player.sing.SingMode.Ready,
                        vocalLevel = com.gpo.yoin.player.sing.VocalLevel(0.12f),
                        isSupported = true,
                        onEnter = {},
                        onExit = {},
                        onSetVocalLevel = {},
                    )
                } else {
                    null
                }
                CompositionLocalProvider(LocalNowPlayingSing provides singUi) {
                    androidx.compose.runtime.key(presentation, stage, page, ask, sing) {
                        NowPlayingScreen(
                            uiState = playing(),
                            positionMs = { 147_000L },
                            bufferedMs = { 180_000L },
                            hasAudioSpectrum = false,
                            onTogglePlayPause = {},
                            onSkipNext = {},
                            onSkipPrevious = {},
                            onSeek = {},
                            onRatingChange = {},
                            onToggleFavorite = {},
                            onAddCurrentToPlaylist = {},
                            onSkipToQueueItem = {},
                            aboutUiState = AboutUiState.Ready(listOf(about())),
                            askState = ask,
                            stageMode = stage,
                            detailPage = page,
                            presentation = presentation,
                            topBarAction = if (toggle) {
                                { PanelToggleButton(fullscreen = false, onClick = {}) }
                            } else {
                                null
                            },
                        )
                    }
                }
            }
        }
        if (!contentSet) {
            contentSet = true
            rule.setContent { content.value() }
        }
        if (settle) rule.mainClock.advanceTimeBy(3_000L) else rule.mainClock.advanceTimeBy(16L)
    }

    private val content = androidx.compose.runtime.mutableStateOf<@Composable () -> Unit>({})
    private var contentSet = false

    @Composable
    private fun Frame(width: Int, height: Int, top: Dp, bottom: Dp, hingeAt: Int?, body: @Composable () -> Unit) {
        YoinTheme {
            ProvidePreviewWindow(widthDp = width, heightDp = height) {
                val density = LocalDensity.current
                val window = LocalYoinWindowInfo.current
                val info = if (hingeAt == null) {
                    window
                } else {
                    // Half folded on a horizontal hinge hingeAt dp down the window.
                    val y = with(density) { hingeAt.dp.toPx() }
                    resolveYoinWindowInfo(
                        width = width.dp,
                        height = height.dp,
                        isTabletop = true,
                        hingeBounds = Rect(0f, y, with(density) { width.dp.toPx() }, y),
                    )
                }
                CompositionLocalProvider(LocalYoinWindowInfo provides info) {
                    // The system bars a phone keeps over the player (Robolectric draws none).
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(MaterialTheme.colorScheme.background)
                            .padding(top = top, bottom = bottom),
                    ) { body() }
                }
            }
        }
    }

    private fun playing() = NowPlayingUiState.Playing(
        songTitle = "Goodnight, Tokyo",
        artist = "The Meridians",
        albumName = "Glass Orchard",
        coverArtUrl = null,
        isPlaying = true,
        durationMs = 240_000L,
        songId = "subsonic:1",
        rating = 8.4f,
        isStarred = true,
        lyrics = listOf(
            "Hold on to the afterglow", "Paper lanterns, carry me home", "I folded the evening into paper lanterns",
            "Let them drift where the harbor meets the sky", "Every word a place where you and I",
            "Every light a word I never said", "Could stand a little longer in the glow",
        ).mapIndexed { i, text -> LyricLine(startMs = i * 18_000L + 30_000L, text = text) },
        showLyricsTranslation = false,
        lyricsActionInFlight = null,
        lyricsLoading = false,
        queue = listOf(QueueItem("1", "Goodnight, Tokyo", "The Meridians", null)),
        currentQueueIndex = 0,
        playMode = PlayMode.RepeatAll,
        albumId = "a1",
        artistId = "ar1",
        activityContext = ActivityContext.Album(albumId = "a1", albumName = "Glass Orchard"),
    )

    private fun about() = SongAboutEntry(
        titleKey = "goodnight tokyo",
        artistKey = "the meridians",
        albumKey = "glass orchard",
        titleDisplay = "Goodnight, Tokyo",
        artistDisplay = "The Meridians",
        albumDisplay = "Glass Orchard",
        kind = SongAboutEntry.KIND_CANONICAL,
        entryKey = SongAboutEntry.CANON_LYRICIST,
        promptText = null,
        titleText = null,
        answerText = "Mara Quinn",
        createdAt = 1L,
        updatedAt = 1L,
    )
}
