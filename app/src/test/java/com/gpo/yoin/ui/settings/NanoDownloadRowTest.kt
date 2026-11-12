package com.gpo.yoin.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import com.gpo.yoin.R
import com.gpo.yoin.ui.theme.YoinTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Gemini Nano diagnostics › Download model. The note under it ("a few GB, so Wi-Fi is recommended") is what the row
 * is there to say, so a phone must show all of it.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class NanoDownloadRowTest {
    @get:Rule
    val rule = createComposeRule()

    private val context get() = ApplicationProvider.getApplicationContext<android.content.Context>()

    @Config(qualifiers = "w360dp-h800dp")
    @Test
    fun should_showTheWholeNote_when_thePhoneIsNarrow() {
        show { NanoDownloadRow(downloading = false, onDownload = {}) }
        assertFalse(noteLayout().hasVisualOverflow)
    }

    @Config(qualifiers = "zh-rCN-w360dp-h800dp")
    @Test
    fun should_showTheWholeNote_when_thePhoneIsNarrowAndInChinese() {
        show { NanoDownloadRow(downloading = false, onDownload = {}) }
        assertFalse(noteLayout().hasVisualOverflow)
    }

    @Config(qualifiers = "w360dp-h800dp")
    @Test
    fun should_startTheDownloadOnce_when_theRowIsTappedAndThenShowsItRunning() {
        var downloads = 0
        val downloading = mutableStateOf(false)
        show { NanoDownloadRow(downloading = downloading.value, onDownload = { downloads++ }) }
        val title = context.getString(R.string.labs_nano_download)

        rule.onNodeWithText(title, useUnmergedTree = true).performClick()
        rule.runOnIdle { assertEquals(1, downloads) }

        // The download is running: the row shows it and a second tap starts nothing.
        downloading.value = true
        rule.waitForIdle()
        rule.onNodeWithText(title, useUnmergedTree = true).performClick()
        rule.runOnIdle { assertEquals(1, downloads) }
    }

    private fun show(row: @Composable () -> Unit) {
        rule.setContent {
            YoinTheme {
                SettingsPageBackground(modifier = Modifier.fillMaxSize()) {
                    // The page's own side padding (SettingsFeatureScreen).
                    Column(modifier = Modifier.padding(horizontal = 16.dp)) {
                        SettingsGroup(title = "Try") { item { row() } }
                    }
                }
            }
        }
        rule.waitForIdle()
    }

    private fun noteLayout(): TextLayoutResult {
        val note = context.getString(R.string.labs_nano_download_note)
        val node = rule.onNodeWithText(note, useUnmergedTree = true).fetchSemanticsNode()
        val results = mutableListOf<TextLayoutResult>()
        node.config[SemanticsActions.GetTextLayoutResult].action?.invoke(results)
        return results.single()
    }
}
