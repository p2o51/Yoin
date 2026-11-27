package com.gpo.yoin.ui.nowplaying

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpRect
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import com.gpo.yoin.R
import com.gpo.yoin.data.local.SongAboutEntry
import com.gpo.yoin.data.repository.ActivityContext
import com.gpo.yoin.player.PlayMode
import com.gpo.yoin.player.sing.SingMode
import com.gpo.yoin.player.sing.VocalLevel
import com.gpo.yoin.ui.common.UiText
import com.gpo.yoin.ui.component.ratingLabelFits
import com.gpo.yoin.ui.experience.ProvidePreviewWindow
import com.gpo.yoin.ui.theme.YoinTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The P0 layout bugs of the Now Playing review (2026-11-27) and the owner's two calls the same day:
 * lyric keys never under 44dp and never on the tabs, no Share key on the lyrics page (Select at the
 * right end), the time labels by topology, a short rating bar without its number, the landscape Ask card
 * at the page's foot, and the launching / error pages fitting a phone on its side.
 */
@RunWith(RobolectricTestRunner::class)
class NowPlayingP0LayoutTest {

    @get:Rule
    val rule = createComposeRule()

    private val res get() = ApplicationProvider.getApplicationContext<android.content.Context>().resources

    // ── The lyric keys' arithmetic ─────────────────────────────────────────

    @Test
    fun should_keepEveryLyricKeyAtLeast44dp_when_a393dpPhoneOpensTheSingCapsule() {
        // 393 less the 24dp page margins; the 124dp capsule open, no Share key any more.
        val keys = lyricsVisibleKeys(345.dp, 52.dp, singSupported = true)
        assertEquals(LyricsKey.entries.toList(), keys)
        val fit = lyricsKeyFit(345.dp, 52.dp, keys.size, singSupported = true, singExpanded = true)
        assertTrue("key ${fit.keyWidth}", fit.keyWidth >= 44.dp)
        val used = fit.keyWidth * 4 + fit.gap * 4 + fit.capsuleMax
        assertTrue("row $used over 345", used <= 345.dp + 0.5.dp)
    }

    @Test
    fun should_shrinkTheCapsuleBeforeAKey_when_theRowIsTight() {
        // Room for four 44dp keys and a capsule narrower than its 124dp.
        val fit = lyricsKeyFit(260.dp, 52.dp, 4, singSupported = true, singExpanded = true)
        assertEquals(44f, fit.keyWidth.value, 0.01f)
        assertTrue(fit.capsuleMax < 124.dp)
        assertTrue(fit.capsuleMax >= 52.dp)
    }

    @Test
    fun should_dropTheLowestPriorityKeys_when_noRowCanHoldThemAt44dp() {
        val narrow = lyricsVisibleKeys(200.dp, 52.dp, singSupported = true)
        assertEquals(listOf(LyricsKey.Search, LyricsKey.Translate, LyricsKey.Select), narrow)
        val narrower = lyricsVisibleKeys(150.dp, 52.dp, singSupported = true)
        assertEquals(listOf(LyricsKey.Search, LyricsKey.Select), narrower)
        // Select always ends the row.
        assertEquals(LyricsKey.Select, lyricsVisibleKeys(320.dp, 44.dp, singSupported = false).last())
    }

    // ── The rating bar's number ───────────────────────────────────────────

    @Test
    fun should_hideTheRatingNumberAtRest_when_theBarIsShort() {
        assertFalse(ratingLabelFits(150.dp, vertical = true))
        // 915×412 landscape's ~160dp bar: at 8.4 only ~26dp stays unfilled, under the ~31dp label.
        assertFalse(ratingLabelFits(160.dp, vertical = true))
        assertTrue(ratingLabelFits(196.dp, vertical = true))
        assertFalse(ratingLabelFits(70.dp, vertical = false))
        assertTrue(ratingLabelFits(120.dp, vertical = false))
    }

    @Test
    fun should_keepTheEnlargedRatingColumnAt56dp_when_theWindowGrows() {
        for (w in listOf(600, 690, 800, 1024)) assertEquals(56f, nowPlayingEnlargedSpec(w.dp).ratingColumn.value, 0f)
    }

    // ── Whole player, by window ───────────────────────────────────────────

    @Config(qualifiers = "w393dp-h852dp")
    @Test
    fun should_keepLyricKeysWholeAndSelectLast_when_regularPhoneLyricsWithSing() {
        setPlayer(393, 852, NowPlayingPresentation.Phone, NowPlayingStageMode.Expanded, sing = true)
        val keys = lyricKeyBounds()
        assertKeys(keys, rootWidth = 393.dp)
        assertTrue("select is the rightmost key", keys.last().left >= keys.dropLast(1).maxOf { it.right })
        // No Share key on the lyrics page (About's, one pager page over, waits unplaced or off screen).
        rule.onAllNodesWithTag(PageShareTag).fetchSemanticsNodes().forEach { node ->
            val b = node.boundsInRoot
            val left = with(rule.density) { b.left.toDp() }
            assertTrue("a Share key on the lyrics page at $left", b.isEmpty || left >= 393.dp - 0.5.dp)
        }
    }

    @Config(qualifiers = "w690dp-h840dp")
    @Test
    fun should_keepToolsOffTheTabRow_when_foldableEnlargedRestsWithSing() {
        setPlayer(690, 840, NowPlayingPresentation.Enlarged, NowPlayingStageMode.Compact, sing = true)
        // At rest the tools are only on the expanded page: nothing collides with the tabs.
        rule.onNodeWithContentDescription(res.getString(R.string.np_cd_search_lyrics)).assertDoesNotExist()
        rule.onNodeWithContentDescription(res.getString(R.string.np_cd_select_lyrics)).assertDoesNotExist()
        rule.onNodeWithText(res.getString(R.string.np_text_tab_note)).assertExists()
    }

    @Config(qualifiers = "w690dp-h840dp")
    @Test
    fun should_keepLyricKeysWholeAndClearOfTheTabs_when_foldableEnlargedExpandsWithSing() {
        setPlayer(690, 840, NowPlayingPresentation.Enlarged, NowPlayingStageMode.Expanded, sing = true)
        val keys = lyricKeyBounds()
        assertKeys(keys, rootWidth = 690.dp)
        assertClearOfTabs(keys)
    }

    @Config(qualifiers = "w915dp-h412dp-land")
    @Test
    fun should_keepLyricKeysWholeAndClearOfTheTabs_when_landscapeLyricsWithSing() {
        setPlayer(915, 412, NowPlayingPresentation.Landscape, NowPlayingStageMode.Expanded, sing = true)
        val keys = lyricKeyBounds()
        assertKeys(keys, rootWidth = 915.dp)
        assertClearOfTabs(keys)
    }

    @Config(qualifiers = "w915dp-h412dp-land")
    @Test
    fun should_anchorTheAskCardInsideThePage_when_landscapeAsks() {
        setPlayer(
            915,
            412,
            NowPlayingPresentation.Landscape,
            NowPlayingStageMode.Expanded,
            page = NowPlayingDetailPage.About,
            ask = AskBarState.Focused,
        )
        val root = rule.onRoot().getBoundsInRoot()
        val ask = rule.onNodeWithTag(PageAskTag, useUnmergedTree = true).getBoundsInRoot()
        // On screen, under the right column's top row (back, album, tabs, icons; PR 3), at the page's foot
        // (the card used to hang from the header over the page) …
        assertTrue("ask top ${ask.top}", ask.top >= tabsBottom() - 0.5.dp)
        assertTrue("ask bottom ${ask.bottom} over ${root.bottom}", ask.bottom <= root.bottom + 0.5.dp)
        // … and inside the right column, its width the column's.
        assertTrue("ask right ${ask.right}", ask.right <= root.right + 0.5.dp)
        assertTrue("ask width ${ask.right - ask.left}", ask.right - ask.left > 300.dp)
    }

    @Config(qualifiers = "w568dp-h320dp-land")
    @Test
    fun should_anchorTheAskCardInsideThePage_when_shortLandscapeAsks() {
        setPlayer(
            568,
            320,
            NowPlayingPresentation.Landscape,
            NowPlayingStageMode.Expanded,
            page = NowPlayingDetailPage.About,
            ask = AskBarState.Focused,
        )
        val root = rule.onRoot().getBoundsInRoot()
        val ask = rule.onNodeWithTag(PageAskTag, useUnmergedTree = true).getBoundsInRoot()
        // The 568 row drops its tabs under the back arrow and the icons: the card stays under both.
        assertTrue("ask top ${ask.top}", ask.top >= tabsBottom() - 0.5.dp)
        assertTrue("ask bottom ${ask.bottom} over ${root.bottom}", ask.bottom <= root.bottom + 0.5.dp)
        assertTrue("ask right ${ask.right}", ask.right <= root.right + 0.5.dp)
    }

    @Config(qualifiers = "w568dp-h320dp-land")
    @Test
    fun should_keepKeysWholeAndTheRatingABar_when_shortLandscapeOpensLyrics() {
        setPlayer(568, 320, NowPlayingPresentation.Landscape, NowPlayingStageMode.Expanded)
        val keys = lyricKeyBounds()
        assertKeys(keys, rootWidth = 568.dp)
        assertClearOfTabs(keys)
        // PR 3: no rating bar lying under the cover any more — it stands at the cover's side as everywhere
        // (taller than wide), clear of the lyric keys.
        val rating = rule.onNode(ratingBar).getBoundsInRoot()
        assertTrue("rating ${rating.right - rating.left} × ${rating.bottom - rating.top}", rating.bottom - rating.top > rating.right - rating.left)
        keys.forEach { key -> assertTrue("rating under key $key", rating.right <= key.left) }
    }

    @Config(qualifiers = "w915dp-h412dp-land")
    @Test
    fun should_keepTheErrorAndItsWayOutOnScreen_when_landscapeConnectError() = assertPendingFits(915, 412, error = true)

    @Config(qualifiers = "w568dp-h320dp-land")
    @Test
    fun should_keepTheErrorAndItsWayOutOnScreen_when_shortLandscapeConnectError() = assertPendingFits(568, 320, error = true)

    @Config(qualifiers = "w915dp-h412dp-land")
    @Test
    fun should_keepTheTitleOnScreen_when_landscapeLaunching() = assertPendingFits(915, 412, error = false)

    @Config(qualifiers = "w568dp-h320dp-land")
    @Test
    fun should_keepTheTitleOnScreen_when_shortLandscapeLaunching() = assertPendingFits(568, 320, error = false)

    // ── Time labels ───────────────────────────────────────────────────────

    @Config(qualifiers = "w420dp-h800dp")
    @Test
    fun should_putTheTimesUnderTheWave_when_singleColumnAt393Or412() {
        val width = mutableStateOf(393.dp)
        setControls(width, beside = false)
        for (w in listOf(393, 412)) {
            rule.runOnIdle { width.value = w.dp }
            rule.mainClock.advanceTimeBy(500L)
            val prev = rule.onNodeWithContentDescription(res.getString(R.string.np_cd_skip_previous)).getBoundsInRoot()
            val time = rule.onNodeWithText("2:27").getBoundsInRoot()
            assertTrue("$w: time ${time.top} under the wave row ${prev.bottom}", time.top >= prev.bottom - 0.5.dp)
        }
    }

    @Config(qualifiers = "w420dp-h800dp")
    @Test
    fun should_putTheTimesBesideTheWave_when_twoColumns() {
        setControls(mutableStateOf(312.dp), beside = true)
        val prev = rule.onNodeWithContentDescription(res.getString(R.string.np_cd_skip_previous)).getBoundsInRoot()
        val time = rule.onNodeWithText("2:27").getBoundsInRoot()
        assertTrue("time ${time.top} beside the wave (row ends ${prev.bottom})", time.top < prev.bottom)
        assertTrue(time.left > prev.right)
    }

    // ── helpers ───────────────────────────────────────────────────────────

    private val ratingBar = SemanticsMatcher("the rating bar") { node ->
        val info = if (SemanticsProperties.ProgressBarRangeInfo in node.config) {
            node.config[SemanticsProperties.ProgressBarRangeInfo]
        } else {
            null
        }
        info?.range?.endInclusive == 10f
    }

    private fun lyricKeyBounds(): List<DpRect> = listOf(
        R.string.np_cd_search_lyrics,
        R.string.np_cd_translate_lyrics,
        R.string.np_cd_return_current_line,
        R.string.np_cd_select_lyrics,
    ).map { rule.onNodeWithContentDescription(res.getString(it)).getBoundsInRoot() }

    private fun assertKeys(keys: List<DpRect>, rootWidth: Dp) {
        keys.forEach { key ->
            assertTrue("key width ${key.right - key.left}", key.right - key.left >= 44.dp - 0.5.dp)
            assertTrue("key height ${key.bottom - key.top}", key.bottom - key.top >= 44.dp - 0.5.dp)
            assertTrue("key ${key.right} off the window $rootWidth", key.right <= rootWidth + 0.5.dp)
        }
        keys.zipWithNext().forEach { (a, b) ->
            assertTrue("keys overlap: ${a.right} > ${b.left}", a.right <= b.left + 0.5.dp)
        }
    }

    private fun tabsBottom(): Dp = rule.onAllNodesWithTag(TextTabTag).fetchSemanticsNodes().maxOf { node ->
        with(rule.density) { node.boundsInRoot.bottom.toDp() }
    }

    private fun assertClearOfTabs(keys: List<DpRect>) {
        val tabs = listOf(R.string.np_text_tab_lyrics, R.string.np_text_tab_about, R.string.np_text_tab_note)
            .flatMap { id ->
                rule.onAllNodesWithText(res.getString(id)).fetchSemanticsNodes().map { node ->
                    with(rule.density) {
                        val b = node.boundsInRoot
                        DpRect(b.left.toDp(), b.top.toDp(), b.right.toDp(), b.bottom.toDp())
                    }
                }
            }
        assertTrue("no tab found", tabs.isNotEmpty())
        for (key in keys) {
            for (tab in tabs) {
                val overlaps = key.left < tab.right && tab.left < key.right && key.top < tab.bottom && tab.top < key.bottom
                assertFalse("key $key on tab $tab", overlaps)
            }
        }
    }

    private fun assertPendingFits(width: Int, height: Int, error: Boolean) {
        rule.mainClock.autoAdvance = false
        rule.setContent {
            Frame(width, height, top = LandscapeTop, bottom = 16.dp) {
                NowPlayingScreen(
                    uiState = if (error) {
                        NowPlayingUiState.ConnectError(
                            songTitle = "Goodnight, Tokyo",
                            artist = "The Meridians",
                            coverArtUrl = null,
                            message = UiText.Res(R.string.np_msg_couldnt_start),
                            opensSpotify = true,
                        )
                    } else {
                        NowPlayingUiState.Launching(
                            songTitle = "Goodnight, Tokyo",
                            artist = "The Meridians",
                            albumName = "Glass Orchard",
                            coverArtUrl = null,
                            durationMs = 240_000L,
                            hint = UiText.Res(R.string.np_msg_connecting_spotify),
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
        // The back arrow sits in the top bar at the start, not floating mid-page.
        val back = rule.onNodeWithTag(PendingBackTag).getBoundsInRoot()
        assertTrue("back at ${back.left},${back.top}", back.left < 72.dp && back.top < LandscapeTop + 16.dp)
        val title = rule.onNodeWithText("Goodnight, Tokyo").getBoundsInRoot()
        assertInside("title", title, root)
        if (error) {
            val message = rule.onNodeWithText(res.getString(R.string.np_msg_couldnt_start))
            message.performScrollTo()
            assertInside("message", message.getBoundsInRoot(), root)
            val open = rule.onNodeWithTag(PendingOpenSpotifyTag)
            open.performScrollTo()
            assertInside("open Spotify", open.getBoundsInRoot(), root)
        }
    }

    private fun assertInside(what: String, b: DpRect, root: DpRect) {
        assertTrue("$what $b outside $root", b.top >= root.top - 0.5.dp && b.bottom <= root.bottom + 0.5.dp)
        assertTrue("$what $b outside $root", b.left >= root.left - 0.5.dp && b.right <= root.right + 0.5.dp)
    }

    private fun setControls(width: MutableState<Dp>, beside: Boolean) {
        rule.mainClock.autoAdvance = false
        rule.setContent {
            YoinTheme {
                Box(modifier = Modifier.width(width.value).padding(horizontal = 24.dp)) {
                    PlaybackControls(
                        isPlaying = false,
                        onTogglePlayPause = {},
                        onSkipNext = {},
                        onSkipPrevious = {},
                        positionMs = 147_000L,
                        durationMs = 240_000L,
                        progress = 0.6f,
                        buffered = 0.8f,
                        onSeek = {},
                        playInteractionSource = remember { MutableInteractionSource() },
                        nextInteractionSource = remember { MutableInteractionSource() },
                        playPressed = false,
                        live = false,
                        labelsBesideWave = beside,
                    )
                }
            }
        }
        rule.mainClock.advanceTimeBy(1_000L)
    }

    private fun setPlayer(
        width: Int,
        height: Int,
        presentation: NowPlayingPresentation,
        stage: NowPlayingStageMode,
        page: NowPlayingDetailPage = NowPlayingDetailPage.Lyrics,
        ask: AskBarState = AskBarState.Idle,
        sing: Boolean = false,
    ) {
        val landscape = presentation == NowPlayingPresentation.Landscape
        rule.mainClock.autoAdvance = false
        rule.setContent {
            Frame(width, height, top = if (landscape) LandscapeTop else 32.dp, bottom = if (landscape) 16.dp else 24.dp) {
                val singUi = if (sing) {
                    NowPlayingSingUi(
                        mode = SingMode.Ready,
                        vocalLevel = VocalLevel(0.12f),
                        isSupported = true,
                        onEnter = {},
                        onExit = {},
                        onSetVocalLevel = {},
                    )
                } else {
                    null
                }
                CompositionLocalProvider(LocalNowPlayingSing provides singUi) {
                    val enlarged = presentation == NowPlayingPresentation.Enlarged
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
                        enlarged = if (enlarged) nowPlayingEnlargedSpec(width.dp) else null,
                    )
                }
            }
        }
        rule.mainClock.advanceTimeBy(3_000L)
    }

    @androidx.compose.runtime.Composable
    private fun Frame(width: Int, height: Int, top: Dp, bottom: Dp, content: @androidx.compose.runtime.Composable () -> Unit) {
        YoinTheme {
            ProvidePreviewWindow(widthDp = width, heightDp = height) {
                // The system bars a phone keeps over the player (Robolectric draws none).
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(MaterialTheme.colorScheme.background)
                        .padding(top = top, bottom = bottom),
                ) { content() }
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
        activityContext = ActivityContext.None,
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

    private companion object {
        val LandscapeTop = 24.dp
    }
}
