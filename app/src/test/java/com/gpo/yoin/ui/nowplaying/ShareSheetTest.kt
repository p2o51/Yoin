package com.gpo.yoin.ui.nowplaying

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import com.gpo.yoin.ui.theme.YoinTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The share sheet (owner 2026-11-25): it opens on the tab it came from, each tab remembers the layout it
 * was last shared in (across opens), and 竖排 is only offered for CJK lines.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w412dp-h915dp")
class ShareSheetTest {

    @get:Rule
    val rule = createComposeRule()

    private var shown by mutableStateOf(true)

    private fun show(
        content: ShareContent,
        tab: ShareTab,
        store: ShareLayoutStore,
        ui: ShareSheetUi = ShareSheetUi(),
    ) {
        rule.setContent {
            YoinTheme {
                CompositionLocalProvider(LocalShareLayoutStore provides store) {
                    if (shown) {
                        ShareSheet(
                            content = content,
                            initialTab = tab,
                            onShared = {},
                            onDismiss = { shown = false },
                            onMessage = {},
                            ui = ui,
                        )
                    }
                }
            }
        }
        rule.waitForIdle()
    }

    private fun previewBoxHeight(): Float {
        val box = rule.onNodeWithTag(SharePreviewBoxTag).getBoundsInRoot()
        return (box.bottom - box.top).value
    }

    @Test
    fun should_openOnTheTabItCameFrom_when_shown() {
        show(PreviewShareContent, ShareTab.Note, ShareLayoutStore.InMemory())

        rule.onNodeWithTag("${ShareTabTagPrefix}${ShareTab.Note.name}").assertIsSelected()
        rule.onNodeWithTag("${ShareThumbTagPrefix}${ShareLayout.NoteBig.name}").assertIsSelected()
    }

    @Test
    fun should_rememberEachTabsLayout_when_oneIsPicked() {
        val store = ShareLayoutStore.InMemory()
        show(PreviewShareContent, ShareTab.About, store)

        rule.onNodeWithTag("${ShareThumbTagPrefix}${ShareLayout.AboutLiner.name}").performClick()
        rule.waitForIdle()

        rule.onNodeWithTag("${ShareThumbTagPrefix}${ShareLayout.AboutLiner.name}").assertIsSelected()
        assertEquals(ShareLayout.AboutLiner, store.layoutFor(ShareTab.About))
        // Only the tab it was picked on.
        assertNull(store.layoutFor(ShareTab.Lyrics))
    }

    @Test
    fun should_reopenOnTheRememberedLayout_when_theSheetOpensAgain() {
        val store = ShareLayoutStore.InMemory().apply { setLayout(ShareTab.Lyrics, ShareLayout.LyricsPlayer) }
        show(PreviewShareContent, ShareTab.Lyrics, store)

        rule.onNodeWithTag("${ShareThumbTagPrefix}${ShareLayout.LyricsPlayer.name}").assertIsSelected()
    }

    @Test
    fun should_notOfferVertical_when_theLinesAreNotCjk() {
        val latin = PreviewShareContent.copy(
            lyrics = LyricsSharePick(listOf(LyricLine(13_000L, "If I could go back to a time before now"))),
        )
        val store = ShareLayoutStore.InMemory().apply { setLayout(ShareTab.Lyrics, ShareLayout.LyricsVertical) }
        show(latin, ShareTab.Lyrics, store)

        rule.onNodeWithTag("${ShareThumbTagPrefix}${ShareLayout.LyricsVertical.name}").assertDoesNotExist()
        // The remembered 竖排 isn't offered here: the sheet falls back to the first layout.
        rule.onNodeWithTag("${ShareThumbTagPrefix}${ShareLayout.LyricsClassic.name}").assertIsSelected()
    }

    @Test
    fun should_persistAcrossStores_when_savedToPreferences() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        SharedPrefsShareLayoutStore(context).setLayout(ShareTab.Note, ShareLayout.NoteClipping)
        // A layout of another tab is not filed under this one.
        SharedPrefsShareLayoutStore(context).setLayout(ShareTab.About, ShareLayout.NoteTimeline)

        val reopened = SharedPrefsShareLayoutStore(context)
        assertEquals(ShareLayout.NoteClipping, reopened.layoutFor(ShareTab.Note))
        assertNull(reopened.layoutFor(ShareTab.About))
    }

    @Test
    fun should_keepTheSheetsHeight_when_switchingTabLayoutAndFormat() {
        show(PreviewShareContent, ShareTab.Lyrics, ShareLayoutStore.InMemory())
        val first = previewBoxHeight()

        val steps = listOf(
            "${ShareThumbTagPrefix}${ShareLayout.LyricsVertical.name}",
            "${ShareTabTagPrefix}${ShareTab.About.name}",
            "${ShareThumbTagPrefix}${ShareLayout.AboutLiner.name}",
            "${ShareTabTagPrefix}${ShareTab.Note.name}",
        )
        for (tag in steps) {
            rule.onNodeWithTag(tag).performClick()
            rule.waitForIdle()
            assertEquals("after $tag", first, previewBoxHeight(), 0.5f)
        }
        rule.onNodeWithText("Story").performClick()
        rule.waitForIdle()
        assertEquals("after Story", first, previewBoxHeight(), 0.5f)
    }

    @Test
    fun should_offerEveryFamily_and_carryThePick_when_switchingTabs() {
        // Owner 2026-11-26 (「放开」): About opens on its tertiary tone but offers the primary ones and Paper too.
        val ui = ShareSheetUi()
        show(PreviewShareContent, ShareTab.About, ShareLayoutStore.InMemory(), ui)
        rule.onNodeWithContentDescription("Mist colours").assertIsSelected()
        rule.onNodeWithContentDescription("Paper colours").assertExists()
        rule.onNodeWithContentDescription("Soft colours").performClick()
        rule.waitForIdle()
        assertEquals(ShareTone.Soft, ui.tone)
        rule.onNodeWithContentDescription("Soft colours").assertIsSelected()

        // The pick carries to the note; Paper, which the lyrics don't have, gives them their default.
        rule.onNodeWithTag("${ShareTabTagPrefix}${ShareTab.Note.name}").performClick()
        rule.onNodeWithContentDescription("Soft colours").assertIsSelected()
        rule.onNodeWithContentDescription("Paper colours").performClick()
        rule.onNodeWithTag("${ShareTabTagPrefix}${ShareTab.Lyrics.name}").performClick()
        rule.waitForIdle()
        rule.onNodeWithContentDescription("Soft colours").assertIsSelected()
        rule.onNodeWithContentDescription("Paper colours").assertDoesNotExist()
    }

    @Test
    fun should_openEachTabOnItsOwnColour_when_nothingIsPicked() {
        show(PreviewShareContent, ShareTab.Note, ShareLayoutStore.InMemory())
        rule.onNodeWithContentDescription("Soft colours").assertIsSelected()
        rule.onNodeWithTag("${ShareTabTagPrefix}${ShareTab.About.name}").performClick()
        rule.waitForIdle()
        rule.onNodeWithContentDescription("Mist colours").assertIsSelected()
    }

    @Test
    fun should_keepItsTabAndFormat_when_shownAgainAfterARotation() {
        // A rotation recreates the sheet from the retained ui state (ShareRetained, held by the ViewModel).
        val ui = ShareSheetUi()
        show(PreviewShareContent, ShareTab.Lyrics, ShareLayoutStore.InMemory(), ui)
        rule.onNodeWithTag("${ShareTabTagPrefix}${ShareTab.Note.name}").performClick()
        rule.onNodeWithText("Story").performClick()
        rule.waitForIdle()

        rule.runOnIdle { shown = false }
        rule.waitForIdle()
        rule.runOnIdle { shown = true }
        rule.waitForIdle()

        rule.onNodeWithTag("${ShareTabTagPrefix}${ShareTab.Note.name}").assertIsSelected()
        assertEquals(LyricsShareFormat.Story, ui.format)
    }

    @Test
    fun should_keepThePick_when_theSameSongsLyricsAskAgain() {
        val retained = ShareRetained()
        val lyrics = listOf(LyricLine(0L, "one"), LyricLine(4_000L, "two"))
        val pick = retained.selectionFor("s", lyrics).apply {
            enter()
            toggle(1)
        }

        assertSame(pick, retained.selectionFor("s", lyrics.toList()))
        assertEquals(setOf(1), retained.selectionFor("s", lyrics).selected)
        // Another song (or new lyrics) starts a fresh pick.
        assertNotSame(pick, retained.selectionFor("t", lyrics))
    }
}
