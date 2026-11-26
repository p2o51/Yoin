package com.gpo.yoin.ui.component

import android.app.Dialog
import androidx.compose.material3.ExpandedFullScreenSearchBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.SearchBarValue
import androidx.compose.material3.Text
import androidx.compose.material3.rememberSearchBarState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.core.view.WindowCompat
import com.gpo.yoin.ui.nowplaying.LyricsSearchSheet
import com.gpo.yoin.ui.nowplaying.LyricsSearchState
import com.gpo.yoin.ui.theme.YoinDarkColorScheme
import com.gpo.yoin.ui.theme.YoinLightColorScheme
import com.gpo.yoin.ui.theme.YoinTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.shadows.ShadowDialog

/**
 * S25 report: on the Library search and the lyrics match search the status bar icons were dark on
 * the dark search surface in dark theme. M3's full-screen search is a dialog window that takes its
 * bar icons from LocalContentColor at the call site (the default black outside a Surface).
 */
@OptIn(ExperimentalMaterial3Api::class)
@RunWith(RobolectricTestRunner::class)
class FullScreenSearchBarIconsTest {

    @get:Rule
    val rule = createComposeRule()

    @Test
    fun should_useLightIcons_when_theLyricsMatchSearchOpensInDarkTheme() {
        showLyricsSearch(dark = true)

        assertEquals(LightIcons, searchWindowBars())
    }

    @Test
    fun should_useDarkIcons_when_theLyricsMatchSearchOpensInLightTheme() {
        showLyricsSearch(dark = false)

        assertEquals(DarkIcons, searchWindowBars())
    }

    @Test
    fun should_followTheTheme_when_lightDarkSwitchesWhileTheLyricsMatchSearchIsOpen() {
        var dark by mutableStateOf(false)
        rule.setContent {
            YoinTheme(colorSchemeOverride = if (dark) YoinDarkColorScheme else YoinLightColorScheme, darkTheme = dark) {
                LyricsSearchSheet(
                    state = LyricsSearchState(isOpen = true, query = "cheesecake"),
                    onQueryChange = {},
                    onSearch = {},
                    onSelect = {},
                    onDismiss = {},
                )
            }
        }
        rule.waitForIdle()
        assertEquals(DarkIcons, searchWindowBars())

        dark = true
        rule.waitForIdle()
        assertEquals(LightIcons, searchWindowBars())

        dark = false
        rule.waitForIdle()
        assertEquals(DarkIcons, searchWindowBars())
    }

    @Test
    fun should_useLightIcons_when_aWrappedFullScreenSearchOpensInDarkTheme() {
        // The Library search's shape: the expanded search with no Surface around the call site.
        rule.setContent {
            YoinTheme(colorSchemeOverride = YoinDarkColorScheme, darkTheme = true) {
                val state = rememberSearchBarState(initialValue = SearchBarValue.Expanded)
                ProvideFullScreenSearchBarIcons {
                    ExpandedFullScreenSearchBar(state = state, inputField = { Text("123") }) {
                        Text("results")
                    }
                }
            }
        }
        rule.waitForIdle()

        assertEquals(LightIcons, searchWindowBars())
    }

    private fun showLyricsSearch(dark: Boolean) {
        rule.setContent {
            YoinTheme(colorSchemeOverride = if (dark) YoinDarkColorScheme else YoinLightColorScheme, darkTheme = dark) {
                LyricsSearchSheet(
                    state = LyricsSearchState(isOpen = true, query = "cheesecake"),
                    onQueryChange = {},
                    onSearch = {},
                    onSelect = {},
                    onDismiss = {},
                )
            }
        }
        rule.waitForIdle()
    }

    /** (light status bars, light navigation bars) of the search's own window. */
    private fun searchWindowBars(): Pair<Boolean, Boolean> {
        val dialog: Dialog? = ShadowDialog.getLatestDialog()
        assertNotNull("the expanded search opens a window of its own", dialog)
        val window = dialog!!.window!!
        val controller = WindowCompat.getInsetsController(window, window.decorView)
        return controller.isAppearanceLightStatusBars to controller.isAppearanceLightNavigationBars
    }

    private companion object {
        /** Light icons: for a dark background. */
        val LightIcons = false to false

        /** Dark icons: for a light background. */
        val DarkIcons = true to true
    }
}
