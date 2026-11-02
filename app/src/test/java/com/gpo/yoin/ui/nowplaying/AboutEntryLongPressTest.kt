package com.gpo.yoin.ui.nowplaying

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import com.gpo.yoin.data.album.AlbumScrapbookAboutRef
import com.gpo.yoin.data.local.SongAboutEntry
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Now Playing's expanded About page: a long-pressed row offers the scrapbook and a fresh answer. */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w400dp-h800dp")
class AboutEntryLongPressTest {

    @get:Rule
    val rule = createComposeRule()

    private fun entry(kind: String, key: String, title: String?, answer: String) = SongAboutEntry(
        titleKey = "harbour lights",
        artistKey = "night qa band",
        albumKey = "tide",
        titleDisplay = "Harbour Lights",
        artistDisplay = "Night QA Band",
        albumDisplay = "Tide",
        kind = kind,
        entryKey = key,
        promptText = if (kind == SongAboutEntry.KIND_CANONICAL) null else title,
        titleText = title,
        answerText = answer,
        createdAt = 1L,
        updatedAt = 1L,
    )

    private val producer =
        entry(SongAboutEntry.KIND_CANONICAL, SongAboutEntry.CANON_PRODUCER, null, "A placeholder producer")
    private val ask = entry(SongAboutEntry.KIND_ASK, "who sampled it", "Samples", "Nobody sampled it yet.")
    private val own = entry(SongAboutEntry.KIND_OWN, "first listen", "First listen", "On the night bus.")

    private val managed = mutableListOf<String>()
    private val regenerated = mutableListOf<String>()
    private val regenerating = MutableStateFlow<Set<AlbumScrapbookAboutRef>>(emptySet())

    private fun setPane(canManage: Boolean = true) {
        val actions = AboutEntryActions(
            regenerating = regenerating,
            canManage = { canManage },
            onManage = { managed += it.entryKey },
            onRegenerate = { regenerated += it.entryKey },
        )
        rule.setContent {
            CompositionLocalProvider(LocalAboutEntryActions provides actions) {
                AboutFullscreenPane(
                    aboutUiState = AboutUiState.Ready(listOf(producer, ask, own)),
                    onRetryCanonical = {},
                )
            }
        }
    }

    @Test
    fun should_regenerateTheRow_when_chosenFromItsLongPress() {
        setPane()

        rule.onNodeWithText("A placeholder producer").performTouchInput { longClick() }
        rule.onNodeWithText("Regenerate with Gemini").performClick()
        rule.waitForIdle()

        assertEquals(listOf(SongAboutEntry.CANON_PRODUCER), regenerated)
    }

    @Test
    fun should_openTheScrapbook_when_manageChosen() {
        setPane()

        rule.onNodeWithText("Nobody sampled it yet.").performTouchInput { longClick() }
        rule.onNodeWithText("Manage in Scrapbook").performClick()
        rule.waitForIdle()

        assertEquals(listOf("who sampled it"), managed)
    }

    @Test
    fun should_showTheListenersOwnEntry_withoutRegenerate() {
        setPane()

        rule.onNodeWithText("First listen").assertIsDisplayed()
        rule.onNodeWithText("On the night bus.").performTouchInput { longClick() }

        rule.onNodeWithText("Manage in Scrapbook").assertIsDisplayed()
        rule.onNodeWithText("Regenerate with Gemini").assertDoesNotExist()
    }

    @Test
    fun should_offerOnlyRegenerate_when_theSongHasNoAlbumPage() {
        setPane(canManage = false)

        rule.onNodeWithText("Nobody sampled it yet.").performTouchInput { longClick() }

        rule.onNodeWithText("Regenerate with Gemini").assertIsDisplayed()
        rule.onNodeWithText("Manage in Scrapbook").assertDoesNotExist()
    }

    @Test
    fun should_showDotsInPlaceOfTheText_while_geminiRewritesIt() {
        regenerating.value = setOf(
            AlbumScrapbookAboutRef(
                titleKey = "harbour lights",
                artistKey = "night qa band",
                albumKey = "tide",
                kind = SongAboutEntry.KIND_ASK,
                entryKey = "who sampled it",
            ),
        )
        setPane()

        rule.onNodeWithText("Nobody sampled it yet.").assertDoesNotExist()
        rule.onNodeWithText("Samples").assertIsDisplayed()
    }
}
