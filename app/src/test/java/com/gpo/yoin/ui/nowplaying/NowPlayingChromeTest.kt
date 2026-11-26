package com.gpo.yoin.ui.nowplaying

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import com.gpo.yoin.R
import com.gpo.yoin.data.local.SongAboutEntry
import com.gpo.yoin.data.local.SongNote
import com.gpo.yoin.data.remote.ai.AiProvider
import com.gpo.yoin.data.repository.ActivityContext
import com.gpo.yoin.player.PlayMode
import com.gpo.yoin.ui.component.LocalAiProvider
import com.gpo.yoin.ui.theme.YoinTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Now Playing's chrome, option B (owner 2026-11-26): which columns carry the expanded page's tabs in the
 * top bar, and the bottom row — the page's action filling it, Share at its end, greyed where the page has
 * nothing to share.
 */
@RunWith(RobolectricTestRunner::class)
class NowPlayingChromeTest {

    @get:Rule
    val rule = createComposeRule()

    private val resources get() = ApplicationProvider.getApplicationContext<android.content.Context>().resources

    // ── Which columns put the tabs in the top bar ─────────────────────────

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

    /** The rule as the player reads it for a phone column of [width] × [height] (bars already off). */
    private fun phoneTabsUp(width: Dp, height: Dp): Boolean {
        val budget = resolveStageColumnBudget(width, height, column())
        return nowPlayingTabsInTopBar(budget.rest.lyricFrame == LyricFrame.OneLine, enlarged = false)
    }

    @Test
    fun should_putTheTabsInTheTopBar_when_aFatPhone475x751() {
        // The owner's 16:10 phone: 751dp tall less a 32dp status bar and a 24dp gesture bar.
        assertTrue(phoneTabsUp(475.dp, 695.dp))
    }

    @Test
    fun should_keepTheTabRow_when_aRegularPhone393x852() {
        assertFalse(phoneTabsUp(393.dp, 852.dp - 54.dp - 24.dp))
    }

    @Test
    fun should_keepTheTabRow_when_aTallBigPhone412x915() {
        assertFalse(phoneTabsUp(412.dp, 915.dp - 52.dp - 24.dp))
    }

    @Test
    fun should_putTheTabsInTheTopBar_when_theEnlargedPhoneOfATablet800x1280() {
        val spec = nowPlayingEnlargedSpec(800.dp)
        val budget = resolveStageColumnBudget(800.dp, 1280.dp - 48.dp - 20.dp, column(transport = 124.dp), enlarged = spec)
        // A tablet keeps its lyric list at rest — the enlarged column alone moves the tabs up.
        assertEquals(LyricFrame.List, budget.rest.lyricFrame)
        assertTrue(nowPlayingTabsInTopBar(lyricOneLine = false, enlarged = true))
        assertTrue(nowPlayingTabsInTopBar(lyricOneLine = true, enlarged = false))
        assertFalse(nowPlayingTabsInTopBar(lyricOneLine = false, enlarged = false))
    }

    // ── What each page can share ──────────────────────────────────────────

    @Test
    fun should_greyShare_when_thePageHasNothingToShare() {
        val bare = playing(lyrics = emptyList())
        assertFalse(has(NowPlayingDetailPage.Lyrics, bare))
        assertFalse("an empty About", has(NowPlayingDetailPage.About, bare))
        assertFalse("no notes", has(NowPlayingDetailPage.Note, bare))
    }

    @Test
    fun should_lightShare_when_thePageHasSomething() {
        val song = playing(lyrics = listOf(LyricLine(startMs = 0, text = "Far away")))
        assertTrue(has(NowPlayingDetailPage.Lyrics, song))
        assertTrue(
            has(
                NowPlayingDetailPage.About,
                song,
                about = listOf(about(SongAboutEntry.KIND_CANONICAL, SongAboutEntry.CANON_REVIEW, "A slow ballad.")),
            ),
        )
        assertTrue(has(NowPlayingDetailPage.Note, song, notes = listOf(note("n1", "the outro hits"))))
    }

    // ── The bottom row ────────────────────────────────────────────────────

    @Test
    fun should_openTheShareSheetOnThePageShown_when_shareTapped() {
        val host = ShareSheetHost()
        setRow(host = host, page = NowPlayingDetailPage.Note, notes = listOf(note("n1", "the outro hits")))

        rule.onNodeWithTag(PageShareTag).assertIsEnabled().performClick()

        rule.runOnIdle { assertEquals(ShareRequest(ShareTab.Note), host.request) }
    }

    @Test
    fun should_openNothing_when_shareIsGreyed() {
        val host = ShareSheetHost()
        setRow(host = host, page = NowPlayingDetailPage.Note, notes = emptyList())

        rule.onNodeWithTag(PageShareTag).assertIsNotEnabled().performClick()

        rule.runOnIdle { assertNull(host.request) }
    }

    @Test
    fun should_giveAskTheWholeRow_when_askIsOpenForTyping() {
        var ask by mutableStateOf<AskBarState>(AskBarState.Idle)
        setRow(page = NowPlayingDetailPage.About, ask = { ask })
        rule.onNodeWithTag(PageShareTag).assertExists()

        rule.runOnIdle { ask = AskBarState.Focused }

        rule.onNodeWithTag(PageShareTag).assertDoesNotExist()
    }

    @Test
    fun should_fitAskAndShare_when_a393dpPhoneAsksALongNamedAi() {
        setRow(page = NowPlayingDetailPage.About, provider = AiProvider.DeepSeek, width = 393.dp)

        val label = rule.onNodeWithText("Ask DeepSeek").getBoundsInRoot()
        val share = rule.onNodeWithTag(PageShareTag).getBoundsInRoot()
        // The label is whole and ends before Share; Share keeps its circle inside the 24dp page margin.
        assertTrue("label ${label.right} before share ${share.left}", label.right < share.left)
        assertEquals(56f, (share.right - share.left).value, 0.5f)
        assertTrue("share inside the margin (${share.right})", share.right.value <= 393f - 24f + 0.5f)
    }

    @Test
    fun should_keepTheAskLabelAndCircle_when_asking() {
        setRow(page = NowPlayingDetailPage.About, ask = { AskBarState.Loading(title = "Who sings the bridge?") })

        rule.onNodeWithText("Who sings the bridge?").assertExists()
        // Asking keeps Share beside the bar (only typing takes the whole row).
        rule.onNodeWithTag(PageShareTag).assertExists()
    }

    @Test
    fun should_drawTheLoadingIndicatorsFirstShapeAtTheRestingCircle() {
        // The indicator's box is sized so the circle it starts as is the resting circle's 28dp.
        val fraction = loadingIndicatorShapeFraction(AskMarkPolygons)
        assertTrue("a shape fraction ($fraction)", fraction in 0.5f..1f)
    }

    // ── helpers ───────────────────────────────────────────────────────────

    private fun has(
        page: NowPlayingDetailPage,
        state: NowPlayingUiState.Playing,
        about: List<SongAboutEntry> = emptyList(),
        notes: List<SongNote> = emptyList(),
    ): Boolean = pageHasShare(
        page = page,
        state = state,
        aboutEntries = about,
        notes = notes,
        aboutLabels = resourceAboutLabels(resources),
        reviewLabel = resources.getString(R.string.np_about_review),
    )

    private fun setRow(
        page: NowPlayingDetailPage,
        host: ShareSheetHost? = null,
        notes: List<SongNote> = emptyList(),
        ask: () -> AskBarState = { AskBarState.Idle },
        provider: AiProvider = AiProvider.Gemini,
        width: Dp = 412.dp,
    ) {
        val state = playing(lyrics = listOf(LyricLine(startMs = 0, text = "Far away")))
        rule.setContent {
            YoinTheme {
                CompositionLocalProvider(LocalShareSheetHost provides host, LocalAiProvider provides provider) {
                    val shares = rememberPageShares(state, AboutUiState.Idle, notes)
                    val askState = ask()
                    Box(modifier = Modifier.width(width).padding(horizontal = 24.dp)) {
                        PageActionRow(
                            share = shares(page),
                            showShare = askState !is AskBarState.Focused,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(if (askState is AskBarState.Focused) 276.dp else 68.dp),
                        ) { lead ->
                            AskGeminiBar(
                                askState = askState,
                                onSubmit = {},
                                onFocus = {},
                                onCollapseRequest = {},
                                onDismissError = {},
                                modifier = lead,
                            )
                        }
                    }
                }
            }
        }
    }

    private fun playing(lyrics: List<LyricLine>) = NowPlayingUiState.Playing(
        songTitle = "Starlight",
        artist = "Muse",
        albumName = "Black Holes and Revelations",
        coverArtUrl = null,
        isPlaying = true,
        durationMs = 240_000L,
        songId = "subsonic:1",
        rating = null,
        isStarred = false,
        lyrics = lyrics,
        showLyricsTranslation = false,
        lyricsActionInFlight = null,
        lyricsLoading = false,
        queue = emptyList(),
        currentQueueIndex = 0,
        playMode = PlayMode.RepeatAll,
        albumId = null,
        artistId = null,
        activityContext = ActivityContext.None,
    )

    private fun note(id: String, text: String) = SongNote(
        id = id,
        trackId = "1",
        content = text,
        createdAt = 1L,
        updatedAt = 1L,
        title = "Starlight",
        artist = "Muse",
        positionMs = 12_000L,
    )

    private fun about(kind: String, key: String, answer: String) = SongAboutEntry(
        titleKey = "t",
        artistKey = "a",
        albumKey = "al",
        titleDisplay = "T",
        artistDisplay = "A",
        albumDisplay = "Al",
        kind = kind,
        entryKey = key,
        promptText = null,
        titleText = null,
        answerText = answer,
        createdAt = 1L,
        updatedAt = 1L,
    )
}
