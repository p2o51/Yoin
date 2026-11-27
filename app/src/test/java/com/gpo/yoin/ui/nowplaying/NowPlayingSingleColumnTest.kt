package com.gpo.yoin.ui.nowplaying

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeUp
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpRect
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import com.gpo.yoin.R
import com.gpo.yoin.data.local.SongAboutEntry
import com.gpo.yoin.data.repository.ActivityContext
import com.gpo.yoin.player.PlayMode
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
import org.robolectric.annotation.GraphicsMode

/**
 * PR 2 of the Now Playing review (owner 2026-11-27): the single column's rules. The tabs ride the top bar by
 * height alone (short windows) and the tablet's enlarged column gets the big tab row back; the collapsed About
 * scrolls under the finger and a tap still expands it; the spine's fact stands clear of its end fades; the
 * one-line column keeps About and Note one tap away.
 */
@RunWith(RobolectricTestRunner::class)
// Real text measurement: the spine's fit depends on how long its fact really is.
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class NowPlayingSingleColumnTest {

    @get:Rule
    val rule = createComposeRule()

    private val res get() = ApplicationProvider.getApplicationContext<android.content.Context>().resources

    // ── Tab placement: height alone ───────────────────────────────────────

    // Font scale 1.0 column metrics, as NowPlayingBudgetTest measures them.
    private fun column(transport: Dp = 116.dp) = StageColumnMetrics(
        tabText = 20.dp,
        transport = transport,
        hero = 70.dp,
        heroOneLine = 44.dp,
        pills = 44.dp,
        oneLineContent = 32.dp,
        lyric = LyricWindowMetrics(activeLine = 32.dp, inactiveLine = 28.dp),
        compactTransport = 96.dp,
    )

    /** The rule as the player reads it: [width] × [height] less a [top] status bar and a 24dp gesture bar. */
    private fun tabsUp(width: Int, height: Int, top: Int = 32, enlarged: Boolean = false): Boolean {
        val spec = if (enlarged) nowPlayingEnlargedSpec(width.dp) else null
        val budget = resolveStageColumnBudget(
            width.dp,
            (height - top - 24).dp,
            column(transport = if (enlarged) 124.dp else 116.dp),
            enlarged = spec,
        )
        return nowPlayingTabsInTopBar(budget.rest.lyricFrame == LyricFrame.OneLine)
    }

    @Test
    fun should_placeTheTabsByHeightAlone_when_phonesAndTheTablet() {
        assertFalse("393×852 keeps the big tab row", tabsUp(393, 852))
        assertFalse("412×915 keeps the big tab row", tabsUp(412, 915))
        assertTrue("475×751 (16:10) puts them in the top bar", tabsUp(475, 751))
        assertTrue("360×640 (16:9) puts them in the top bar", tabsUp(360, 640))
        assertFalse("the tablet's 800×1280 enlarged column keeps the big tab row", tabsUp(800, 1280, top = 24, enlarged = true))
    }

    @Config(qualifiers = "w800dp-h1280dp")
    @Test
    fun should_showTheBigTabRowUnderTheTopBar_when_tabletPortraitExpands() {
        setPlayer(800, 1280, NowPlayingStageMode.Expanded, enlarged = true)
        val note = tabBounds(R.string.np_tab_note)
        assertTrue("no Note tab", note.isNotEmpty())
        // Under the 56dp top bar (from the 24dp inset), not riding it.
        note.forEach { assertTrue("Note tab at ${it.top}", it.top >= 24.dp + 56.dp - 0.5.dp) }
    }

    @Config(qualifiers = "w475dp-h751dp")
    @Test
    fun should_putTheTabsInTheTopBar_when_a475x751PhoneExpands() {
        setPlayer(475, 751, NowPlayingStageMode.Expanded)
        val note = tabBounds(R.string.np_text_tab_note)
        assertEquals(1, note.size)
        assertTrue("Note tab at ${note.single().top}", note.single().bottom <= 32.dp + 56.dp + 0.5.dp)
    }

    // ── The one-line column's way to About and Note ───────────────────────

    @Config(qualifiers = "w475dp-h751dp")
    @Test
    fun should_offerAboutAndNoteInTheTopBar_when_a475x751PhoneRests() = assertOneLineEntries(475, 751)

    @Config(qualifiers = "w360dp-h640dp")
    @Test
    fun should_offerAboutAndNoteInTheTopBar_when_a360x640PhoneRests() = assertOneLineEntries(360, 640)

    private fun assertOneLineEntries(width: Int, height: Int) {
        val calls = mutableListOf<Any>()
        setPlayer(
            width,
            height,
            NowPlayingStageMode.Compact,
            onStageModeChange = { calls += it },
            onDetailPageChange = { calls += it },
        )
        for (id in listOf(R.string.np_text_tab_about, R.string.np_text_tab_note)) {
            val tab = tabBounds(id)
            assertEquals("${res.getString(id)} at rest", 1, tab.size)
            val b = tab.single()
            assertTrue("${res.getString(id)} at ${b.top}: not in the top bar", b.bottom <= 32.dp + 56.dp + 0.5.dp)
            assertTrue("${res.getString(id)} off the window", b.right <= width.dp + 0.5.dp)
        }
        rule.onNodeWithText(res.getString(R.string.np_text_tab_about)).performClick()
        rule.mainClock.advanceTimeBy(500L)
        assertEquals(listOf<Any>(NowPlayingDetailPage.About, NowPlayingStageMode.Expanded), calls)
    }

    // ── The collapsed About scrolls ───────────────────────────────────────

    @Config(qualifiers = "w393dp-h852dp")
    @Test
    fun should_scrollTheCollapsedAboutAndExpandOnATap_when_aRegularPhoneRestsOnAbout() {
        val stages = mutableListOf<NowPlayingStageMode>()
        setPlayer(
            393,
            852,
            NowPlayingStageMode.Compact,
            page = NowPlayingDetailPage.About,
            about = longAbout(),
            onStageModeChange = { stages += it },
        )
        val about = rule.onNodeWithTag(CollapsedAboutTag)
        val range = about.fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange]
        assertTrue("nothing to scroll: ${range.maxValue()}", range.maxValue() > 0f)
        assertEquals(0f, range.value(), 0f)

        about.performTouchInput { swipeUp(startY = bottom - 8f, endY = top + 8f, durationMillis = 400L) }
        rule.mainClock.advanceTimeBy(2_000L)
        val scrolled = about.fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange].value()
        assertTrue("the finger did not scroll it ($scrolled)", scrolled > 0f)
        // A drag is a read, not a tap: the page stays collapsed.
        assertTrue("a scroll expanded About: $stages", stages.isEmpty())

        about.performClick()
        rule.mainClock.advanceTimeBy(500L)
        assertEquals(listOf(NowPlayingStageMode.Expanded), stages)
    }

    @Config(qualifiers = "w475dp-h751dp")
    @Test
    fun should_keepTheHiddenCollapsedAboutStill_when_theOneLineColumnRests() {
        setPlayer(475, 751, NowPlayingStageMode.Compact, page = NowPlayingDetailPage.About, about = longAbout())
        // Under the one-line lyric the About page is not on screen: it must not take a drag.
        rule.onAllNodesWithTag(CollapsedAboutTag).fetchSemanticsNodes().forEach { node ->
            assertFalse(
                "a hidden About scrolls",
                node.config.contains(SemanticsProperties.VerticalScrollAxisRange) &&
                    node.config.contains(androidx.compose.ui.semantics.SemanticsActions.ScrollBy),
            )
        }
    }

    // ── The spine ─────────────────────────────────────────────────────────

    @Test
    fun should_wrapALongFactBeforePanningIt() {
        assertEquals(SpineFit.OneLine, spineFit(200f, 272f, twoLineRoom = true, twoLinesFit = true))
        assertEquals(SpineFit.TwoLines, spineFit(330f, 272f, twoLineRoom = true, twoLinesFit = true))
        assertEquals(SpineFit.Pan, spineFit(330f, 272f, twoLineRoom = false, twoLinesFit = false))
        assertEquals(SpineFit.Pan, spineFit(700f, 272f, twoLineRoom = true, twoLinesFit = false))
        // The label over the value first, where each fits its line; never on a band too thin for two.
        assertEquals(SpineFit.LabelOverValue, spineFit(330f, 272f, twoLineRoom = true, twoLinesFit = true, labelOverValueFits = true))
        assertEquals(SpineFit.Pan, spineFit(330f, 272f, twoLineRoom = false, twoLinesFit = true, labelOverValueFits = true))
    }

    @Config(qualifiers = "w475dp-h751dp")
    @Test
    fun should_keepTheSpineFactClearOfItsFades_when_a475x751PhoneRests() = assertSpineClear(475, 751, expectSpine = true)

    @Config(qualifiers = "zh-rCN-w475dp-h751dp")
    @Test
    fun should_keepTheSpineFactClearOfItsFades_when_a475x751PhoneRestsInChinese() =
        assertSpineClear(475, 751, expectSpine = true)

    @Config(qualifiers = "w360dp-h640dp")
    @Test
    fun should_keepTheSpineFactClearOfItsFades_when_a360x640PhoneRests() = assertSpineClear(360, 640, expectSpine = false)

    private fun assertSpineClear(width: Int, height: Int, expectSpine: Boolean) {
        setPlayer(width, height, NowPlayingStageMode.Compact, about = listOf(creationTime()))
        // Long past the moment a panning line used to slide its head into the bottom fade.
        rule.mainClock.advanceTimeBy(6_000L)
        val spine = rule.onAllNodesWithTag(SpineTextTag, useUnmergedTree = true).fetchSemanticsNodes()
        if (!expectSpine) {
            // A cover that takes the column's width leaves no band: nothing to clip.
            assertTrue("a spine at $width×$height", spine.isEmpty())
            return
        }
        assertEquals(1, spine.size)
        val text = rule.onNodeWithTag(SpineTextTag, useUnmergedTree = true).getBoundsInRoot()
        val cover = rule.onNodeWithTag(StageCoverTag, useUnmergedTree = true).getBoundsInRoot()
        // The fact reads along the cover's side, inside the 20dp fades at both ends …
        assertTrue("head ${text.bottom} in the bottom fade (cover ends ${cover.bottom})", text.bottom <= cover.bottom - 20.dp + 0.5.dp)
        assertTrue("tail ${text.top} in the top fade (cover starts ${cover.top})", text.top >= cover.top + 20.dp - 0.5.dp)
        // … and within the band beside it.
        assertTrue("spine ${text.left} on the cover ${cover.right}", text.left >= cover.right - 0.5.dp)
    }

    // ── helpers ───────────────────────────────────────────────────────────

    private fun tabBounds(id: Int): List<DpRect> =
        rule.onAllNodesWithText(res.getString(id)).fetchSemanticsNodes().map { node ->
            with(rule.density) {
                val b = node.boundsInRoot
                DpRect(b.left.toDp(), b.top.toDp(), b.right.toDp(), b.bottom.toDp())
            }
        }

    private fun setPlayer(
        width: Int,
        height: Int,
        stage: NowPlayingStageMode,
        page: NowPlayingDetailPage = NowPlayingDetailPage.Lyrics,
        enlarged: Boolean = false,
        about: List<SongAboutEntry> = listOf(creationTime()),
        onStageModeChange: (NowPlayingStageMode) -> Unit = {},
        onDetailPageChange: (NowPlayingDetailPage) -> Unit = {},
    ) {
        rule.mainClock.autoAdvance = false
        rule.setContent {
            Frame(width, height, top = if (enlarged) 24.dp else 32.dp, bottom = 24.dp) {
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
                    aboutUiState = AboutUiState.Ready(about),
                    stageMode = stage,
                    detailPage = page,
                    onStageModeChange = onStageModeChange,
                    onDetailPageChange = onDetailPageChange,
                    presentation = if (enlarged) NowPlayingPresentation.Enlarged else NowPlayingPresentation.Phone,
                    enlarged = if (enlarged) nowPlayingEnlargedSpec(width.dp) else null,
                )
            }
        }
        rule.mainClock.advanceTimeBy(3_000L)
    }

    @Composable
    private fun Frame(width: Int, height: Int, top: Dp, bottom: Dp, content: @Composable () -> Unit) {
        YoinTheme {
            ProvidePreviewWindow(widthDp = width, heightDp = height) {
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

    /** The fact that panned its head into the fade on a 475 phone (review 2026-11-27). */
    private fun creationTime() = canon(
        SongAboutEntry.CANON_CREATION_TIME,
        if (res.configuration.locales[0].language == "zh") {
            "2018 年末录音；写于乐队首次日本巡演"
        } else {
            "Late 2018 (recording); written on the band's first Japan tour"
        },
    )

    private fun longAbout(): List<SongAboutEntry> = listOf(
        canon(SongAboutEntry.CANON_LYRICIST, "Mara Quinn"),
        canon(SongAboutEntry.CANON_COMPOSER, "Mara Quinn / Theo Hart"),
        canon(SongAboutEntry.CANON_PRODUCER, "Theo Hart"),
        creationTime(),
        canon(
            SongAboutEntry.CANON_REVIEW,
            "The closing track of Glass Orchard, written in a hotel room on the last night of The Meridians' " +
                "first Japan tour. A slow synth-pop ballad built on a borrowed drum machine, it turns a goodbye to " +
                "the city into a thank-you note; the final chorus drops to a single voice over the hum of the " +
                "street outside.",
        ),
    )

    private fun canon(key: String, answer: String) = SongAboutEntry(
        titleKey = "goodnight tokyo",
        artistKey = "the meridians",
        albumKey = "glass orchard",
        titleDisplay = "Goodnight, Tokyo",
        artistDisplay = "The Meridians",
        albumDisplay = "Glass Orchard",
        kind = SongAboutEntry.KIND_CANONICAL,
        entryKey = key,
        promptText = null,
        titleText = null,
        answerText = answer,
        createdAt = 1L,
        updatedAt = 1L,
    )
}
