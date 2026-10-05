package com.gpo.yoin.ui.nowplaying

import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The model follows the SETTLED pager page, round trips included. */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w400dp-h800dp")
class DetailPagerSyncTest {

    @get:Rule
    val rule = createComposeRule()

    @Test
    fun should_reportLyricsAgain_when_swipingAboutAndBack() {
        var modelPage by mutableStateOf(NowPlayingDetailPage.Lyrics)
        lateinit var pager: PagerState
        lateinit var scope: CoroutineScope
        rule.setContent {
            scope = rememberCoroutineScope()
            pager = rememberPagerState(initialPage = modelPage.ordinal, pageCount = { 3 })
            SyncDetailPageWithPager(
                pagerState = pager,
                detailPage = modelPage,
                onDetailPageChange = { modelPage = it },
                onAboutOpened = {},
            )
            HorizontalPager(state = pager, modifier = Modifier.fillMaxSize()) {
                Box(Modifier.fillMaxSize())
            }
        }

        rule.runOnIdle { scope.launch { pager.scrollToPage(NowPlayingDetailPage.About.ordinal) } }
        rule.waitForIdle()
        assertEquals(NowPlayingDetailPage.About, modelPage)

        // The bug: this return trip compared against the page captured at
        // launch (Lyrics) and was never reported — the model stayed on About.
        rule.runOnIdle { scope.launch { pager.scrollToPage(NowPlayingDetailPage.Lyrics.ordinal) } }
        rule.waitForIdle()
        assertEquals(NowPlayingDetailPage.Lyrics, modelPage)
    }

    @Test
    fun should_moveThePager_when_theModelPageChanges() {
        var modelPage by mutableStateOf(NowPlayingDetailPage.Lyrics)
        lateinit var pager: PagerState
        rule.setContent {
            pager = rememberPagerState(initialPage = modelPage.ordinal, pageCount = { 3 })
            SyncDetailPageWithPager(
                pagerState = pager,
                detailPage = modelPage,
                onDetailPageChange = { modelPage = it },
                onAboutOpened = {},
            )
            HorizontalPager(state = pager, modifier = Modifier.fillMaxSize()) {
                Box(Modifier.fillMaxSize())
            }
        }

        rule.runOnIdle { modelPage = NowPlayingDetailPage.Note }
        rule.waitForIdle()
        assertEquals(NowPlayingDetailPage.Note.ordinal, pager.settledPage)
        assertEquals(NowPlayingDetailPage.Note, modelPage)
    }

    @Test
    fun should_releaseComposerFocus_when_swipedFromNoteToLyrics() {
        val harness = NoteComposerPager(startOn = NowPlayingDetailPage.Note)
        harness.compose()
        harness.focusComposer()

        rule.runOnIdle { harness.scope.launch { harness.pager.scrollToPage(NowPlayingDetailPage.Lyrics.ordinal) } }
        rule.waitForIdle()
        assertEquals(NowPlayingDetailPage.Lyrics, harness.modelPage)

        // The pager pins a focused page: without the release the composer kept
        // focus (and the IME) off screen.
        assertFalse(harness.composerFocused)
    }

    @Test
    fun should_releaseComposerFocus_when_tabJumpsFromNoteToAbout() {
        val harness = NoteComposerPager(startOn = NowPlayingDetailPage.Note)
        harness.compose()
        harness.focusComposer()

        rule.runOnIdle { harness.modelPage = NowPlayingDetailPage.About }
        rule.waitForIdle()

        assertEquals(NowPlayingDetailPage.About.ordinal, harness.pager.settledPage)
        assertFalse(harness.composerFocused)
    }

    @Test
    fun should_keepComposerFocus_when_pagerStaysOnNote() {
        val harness = NoteComposerPager(startOn = NowPlayingDetailPage.Lyrics)
        harness.compose()
        rule.runOnIdle { harness.modelPage = NowPlayingDetailPage.Note }
        rule.waitForIdle()
        harness.focusComposer()

        // A no-op settle on Note (re-tapping its tab) must not drop the keyboard.
        rule.runOnIdle { harness.scope.launch { harness.pager.scrollToPage(NowPlayingDetailPage.Note.ordinal) } }
        rule.waitForIdle()

        assertTrue(harness.composerFocused)
    }

    @Test
    fun should_releaseFocusOnlyOnLeavingNote_when_pagesSettle() {
        assertTrue(leftNotePage(NowPlayingDetailPage.Note, NowPlayingDetailPage.Lyrics))
        assertTrue(leftNotePage(NowPlayingDetailPage.Note, NowPlayingDetailPage.About))
        assertFalse(leftNotePage(NowPlayingDetailPage.Note, NowPlayingDetailPage.Note))
        assertFalse(leftNotePage(NowPlayingDetailPage.Lyrics, NowPlayingDetailPage.About))
        assertFalse(leftNotePage(NowPlayingDetailPage.About, NowPlayingDetailPage.Lyrics))
        assertFalse(leftNotePage(null, NowPlayingDetailPage.Lyrics))
    }

    /** The real pager sync over a pager whose Note page holds a focusable composer. */
    private inner class NoteComposerPager(startOn: NowPlayingDetailPage) {
        var modelPage by mutableStateOf(startOn)
        var composerFocused = false
        lateinit var pager: PagerState
        lateinit var scope: CoroutineScope
        private val composer = FocusRequester()

        fun compose() {
            rule.setContent {
                scope = rememberCoroutineScope()
                pager = rememberPagerState(initialPage = modelPage.ordinal, pageCount = { 3 })
                SyncDetailPageWithPager(
                    pagerState = pager,
                    detailPage = modelPage,
                    onDetailPageChange = { modelPage = it },
                    onAboutOpened = {},
                )
                Column(Modifier.fillMaxSize()) {
                    // Now Playing's chrome above the pager (top bar buttons).
                    // Robolectric runs out of touch mode, where Android hands
                    // focus back to the first focusable after a clear — as
                    // with a hardware keyboard on device.
                    Box(Modifier.size(48.dp).focusable())
                    HorizontalPager(state = pager, modifier = Modifier.weight(1f)) { page ->
                        Box(Modifier.fillMaxSize()) {
                            if (page == NowPlayingDetailPage.Note.ordinal) {
                                var text by remember { mutableStateOf("draft") }
                                BasicTextField(
                                    value = text,
                                    onValueChange = { text = it },
                                    modifier = Modifier
                                        .focusRequester(composer)
                                        .onFocusChanged { composerFocused = it.isFocused },
                                )
                            }
                        }
                    }
                }
            }
        }

        fun focusComposer() {
            rule.runOnIdle { composer.requestFocus() }
            rule.waitForIdle()
            assertTrue(composerFocused)
        }
    }
}
