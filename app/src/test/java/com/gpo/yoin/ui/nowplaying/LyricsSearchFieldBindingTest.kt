package com.gpo.yoin.ui.nowplaying

import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.createComposeRule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The lyrics search field is the source of truth while typing: the model's
 * query trails it, and a stale echo must never be written back.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w400dp-h800dp")
class LyricsSearchFieldBindingTest {

    @get:Rule
    val rule = createComposeRule()

    @Test
    fun should_keepTypedText_when_modelEchoesAnOlderQuery() {
        val harness = Harness(initialQuery = "")
        harness.compose()

        harness.type("V")
        harness.type("Vi")
        harness.type("Viv")
        // The model catches up one keystroke late — "V" arrives while the field says "Viv".
        harness.modelEchoes("V")
        harness.modelEchoes("Vi")

        assertEquals("Viv", harness.field.text.toString())
        harness.modelEchoes("Viv")
        assertEquals("Viv", harness.field.text.toString())
        assertEquals(listOf("", "V", "Vi", "Viv"), harness.sent)
    }

    @Test
    fun should_adoptQuery_when_itChangesFromOutside() {
        val harness = Harness(initialQuery = "")
        harness.compose()
        harness.type("old search")
        harness.modelEchoes("old search")

        // Opening the search again seeds the current song; a song change resets it.
        harness.modelEchoes("Viva La Vida Coldplay")
        assertEquals("Viva La Vida Coldplay", harness.field.text.toString())

        harness.modelEchoes("")
        assertEquals("", harness.field.text.toString())
    }

    @Test
    fun should_adoptOnlyOutsideChanges_when_echoesArriveOutOfStep() {
        val echoes = SearchFieldEchoes()
        echoes.sent("V")
        echoes.sent("Vi")
        echoes.sent("Viv")

        assertFalse(echoes.isOutsideChange(query = "V", fieldText = "Viv"))
        // A skipped echo ("Vi") is retired with the later one.
        assertFalse(echoes.isOutsideChange(query = "Viv", fieldText = "Viv"))
        assertTrue(echoes.isOutsideChange(query = "Vi", fieldText = "Viv"))
        assertFalse(echoes.isOutsideChange(query = "Viv", fieldText = "Viv"))
    }

    private inner class Harness(initialQuery: String) {
        var modelQuery by mutableStateOf(initialQuery)
        val sent = mutableListOf<String>()
        lateinit var field: TextFieldState

        fun compose() {
            rule.setContent {
                field = rememberTextFieldState(modelQuery)
                // Unlike the real VM, the model here only moves when the test
                // says so — that's the lag fast typing produces.
                BindSearchFieldToQuery(field, modelQuery, onQueryChange = { sent += it })
                BasicTextField(state = field)
            }
            rule.waitForIdle()
        }

        fun type(text: String) {
            rule.runOnIdle { field.setTextAndPlaceCursorAtEnd(text) }
            rule.waitForIdle()
        }

        fun modelEchoes(query: String) {
            rule.runOnIdle { modelQuery = query }
            rule.waitForIdle()
        }
    }
}
