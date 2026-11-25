package com.gpo.yoin.ui.nowplaying

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import com.gpo.yoin.ui.theme.YoinTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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

    private fun show(content: ShareContent, tab: ShareTab, store: ShareLayoutStore): () -> Unit {
        var open by mutableStateOf(true)
        rule.setContent {
            YoinTheme {
                CompositionLocalProvider(LocalShareLayoutStore provides store) {
                    if (open) {
                        ShareSheet(
                            content = content,
                            initialTab = tab,
                            onShared = {},
                            onDismiss = { open = false },
                            onMessage = {},
                        )
                    }
                }
            }
        }
        rule.waitForIdle()
        return { open = false }
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
}
