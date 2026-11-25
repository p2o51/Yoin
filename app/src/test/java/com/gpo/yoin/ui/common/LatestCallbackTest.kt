package com.gpo.yoin.ui.common

import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.v2.createComposeRule
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The stall when Now Playing closes: the shell's navigation callbacks closed
 * over whether the player was open, so the close handed Home, Library and
 * the player new callbacks and each recomposed top to bottom in the frame the
 * exit should have started. [rememberLatestCallback] keeps one identity, so
 * what takes the callback skips, and still runs the latest closure.
 */
@RunWith(RobolectricTestRunner::class)
class LatestCallbackTest {

    @get:Rule
    val rule = createComposeRule()

    @Test
    fun should_notRecomposeTheConsumer_when_theCallbackClosesOverTheOpenFlagAndItFlips() {
        var open by mutableStateOf(true)
        val compositions = IntArray(1)
        val latest = arrayOfNulls<(String) -> Unit>(1)
        val calls = mutableListOf<String>()
        rule.setContent {
            // Like the shell's openPane: the flag read in composition, closed
            // over by value, so a fresh closure whenever it flips.
            val isOpen = open
            val navigate: (String) -> Unit = { id -> calls += "open=$isOpen id=$id" }
            CallbackConsumer(onClick = rememberLatestCallback(navigate), compositions = compositions) {
                latest[0] = it
            }
        }
        rule.runOnIdle { assertEquals(1, compositions[0]) }

        open = false

        rule.runOnIdle {
            assertEquals("the consumer must skip the flip", 1, compositions[0])
            latest[0]!!("album")
            assertEquals(listOf("open=false id=album"), calls)
        }
    }

    @Test
    fun should_runTheLatestClosure_when_twoArgumentCallbackClosesOverAFlippedFlag() {
        var open by mutableStateOf(true)
        val compositions = IntArray(1)
        val latest = arrayOfNulls<(String, String?) -> Unit>(1)
        val calls = mutableListOf<String>()
        rule.setContent {
            val isOpen = open
            val navigate: (String, String?) -> Unit = { id, key -> calls += "open=$isOpen id=$id key=$key" }
            TwoArgumentConsumer(onClick = rememberLatestCallback(navigate), compositions = compositions) {
                latest[0] = it
            }
        }

        open = false

        rule.runOnIdle {
            assertEquals("the consumer must skip the flip", 1, compositions[0])
            latest[0]!!("album", null)
            assertEquals(listOf("open=false id=album key=null"), calls)
        }
    }

    // What the shell did before: the fresh closure handed down as it is
    // recomposes whatever takes it. Guards that the probe above can see a
    // recomposition at all.
    @Test
    fun should_recomposeTheConsumer_when_aFreshClosureIsHandedDownDirectly() {
        var open by mutableStateOf(true)
        val compositions = IntArray(1)
        rule.setContent {
            val isOpen = open
            val navigate: (String) -> Unit = { id -> check(isOpen || id.isNotEmpty()) }
            CallbackConsumer(onClick = navigate, compositions = compositions) {}
        }

        open = false

        rule.runOnIdle { assertEquals(2, compositions[0]) }
    }
}

@Composable
private fun CallbackConsumer(
    onClick: (String) -> Unit,
    compositions: IntArray,
    publish: ((String) -> Unit) -> Unit,
) {
    SideEffect {
        compositions[0]++
        publish(onClick)
    }
}

@Composable
private fun TwoArgumentConsumer(
    onClick: (String, String?) -> Unit,
    compositions: IntArray,
    publish: ((String, String?) -> Unit) -> Unit,
) {
    SideEffect {
        compositions[0]++
        publish(onClick)
    }
}
