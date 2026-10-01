package com.gpo.yoin.ui.nowplaying

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.SearchBarDefaults
import androidx.compose.material3.SearchBarState
import androidx.compose.material3.SearchBarValue
import androidx.compose.material3.rememberSearchBarState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import com.gpo.yoin.ui.theme.YoinTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalMaterial3Api::class)
class LyricsSearchAnchorTest {
    @get:Rule val rule = createComposeRule()

    @Test
    fun should_expandFromSearchActionAndReturnToIt_whenOpenedAndClosedRepeatedly() {
        lateinit var search: SearchBarState
        lateinit var scope: CoroutineScope
        var anchorY = 0f
        var dismissals = 0
        rule.mainClock.autoAdvance = false
        rule.setContent {
            YoinTheme {
                search = rememberSearchBarState()
                scope = rememberCoroutineScope()
                var open by remember { mutableStateOf(false) }
                val density = androidx.compose.ui.platform.LocalDensity.current.density
                Box(Modifier.fillMaxSize()) {
                    LyricsActionBar(
                        actionInFlight = null, canTranslate = false, canRecenter = false,
                        onSearchClick = { open = true },
                        onTranslateClick = {}, onApplyClick = {}, onRecenterClick = {},
                        modifier = Modifier.offset(24.dp, 480.dp),
                        searchModifier = Modifier.onGloballyPositioned {
                            search.collapsedCoords = it
                            anchorY = it.positionInWindow().y / density
                        },
                    )
                    LyricsSearchSheet(
                        state = LyricsSearchState(isOpen = open),
                        searchBarState = search,
                        onQueryChange = {}, onSearch = {}, onSelect = {},
                        onDismiss = { dismissals++; open = false },
                    )
                }
            }
        }
        repeat(2) { attempt ->
            rule.onNodeWithContentDescription("Search lyrics").performClick()
            rule.mainClock.advanceTimeBy(64)
            rule.runOnIdle { scope.launch { search.snapTo(0.1f) } }
            rule.mainClock.advanceTimeBy(32)
            val back = rule.onNodeWithContentDescription("Close lyrics search")
                .getUnclippedBoundsInRoot()
            // At 10% expansion the input remains near the bottom action. An
            // unbound SearchBarState instead positions it near the top-left.
            assertTrue("Search must start at its action, not the top of the window", back.top.value > anchorY * 0.8f)
            rule.runOnIdle {
                assertTrue(search.collapsedCoords!!.isAttached)
                scope.launch { search.animateToExpanded() }
            }
            rule.mainClock.advanceTimeBy(1_500)
            // The 52 dp action must not shrink the expanded input's line box.
            val fieldBounds = rule.onNode(hasSetTextAction()).getUnclippedBoundsInRoot()
            assertEquals(
                SearchBarDefaults.InputFieldHeight.value,
                (fieldBounds.bottom - fieldBounds.top).value,
                1f,
            )
            rule.onNodeWithContentDescription("Close lyrics search").performClick()
            rule.mainClock.advanceTimeBy(1_500)
            rule.runOnIdle {
                assertEquals(SearchBarValue.Collapsed, search.currentValue)
                assertEquals(attempt + 1, dismissals)
            }
            rule.onNodeWithContentDescription("Search lyrics").assertExists()
            rule.onNodeWithContentDescription("Close lyrics search").assertDoesNotExist()
        }
    }
}
