package com.gpo.yoin.ui.settings

import androidx.compose.runtime.saveable.SaverScope
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Settings' list-detail mark (PIP-16 [L]): a page opened over another
 * replaces it, and the replaced page's answer — which comes after the new
 * launch — leaves the new page's row marked; the mark goes once the page in
 * the pane closes.
 */
class OpenPageMarkTest {

    @Test
    fun should_keepTheNewPagesMark_when_thePageItReplacedAnswers() {
        val mark = OpenPageMark<String>()
        mark.opened("qa2")

        mark.opened("qa")
        // qa2's page, replaced in the pane, answers now.
        mark.answered()

        assertEquals("qa", mark.open)
    }

    @Test
    fun should_clearTheMark_when_thePageInThePaneCloses() {
        val mark = OpenPageMark<String>()
        mark.opened("qa2")
        mark.opened("qa")
        mark.answered()

        // Back (or Done) from qa's page.
        mark.answered()

        assertNull(mark.open)
    }

    @Test
    fun should_keepTheMark_when_theOpenRowIsTappedAgain() {
        val mark = OpenPageMark<String>()
        mark.opened("qa")

        mark.opened("qa")
        mark.answered()

        assertEquals("qa", mark.open)
    }

    @Test
    fun should_markNothingButStillCountItsPage_when_anotherKindOfPageTakesThePane() {
        val accounts = OpenPageMark<String>()
        val features = OpenPageMark<SettingsFeature>()
        accounts.opened("qa")

        // A feature page replaces qa's page.
        accounts.clear()
        features.opened(SettingsFeature.NeoDb)
        accounts.answered()
        assertNull(accounts.open)
        assertEquals(SettingsFeature.NeoDb, features.open)

        // An account page again: its mark isn't taken by the feature page's late answer.
        features.clear()
        accounts.opened("qa2")
        features.answered()
        assertEquals("qa2", accounts.open)
        assertNull(features.open)
    }

    @Test
    fun should_waitForTheSameAnswers_when_restored() {
        val mark = OpenPageMark<String>()
        mark.opened("qa2")
        mark.opened("qa")
        val saver = OpenPageMark.saver<String>()

        val restored = saver.restore(with(saver) { SaverScope { true }.save(mark) }!!)!!
        restored.answered()

        assertEquals("qa", restored.open)
        restored.answered()
        assertNull(restored.open)
    }
}
