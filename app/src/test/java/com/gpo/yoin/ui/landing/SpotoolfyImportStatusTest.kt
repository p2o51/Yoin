package com.gpo.yoin.ui.landing

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SpotoolfyImportStatusTest {

    @Test
    fun should_hideStatus_when_usingWithoutSpotoolfyAndNothingRead() {
        // Nothing to show: the pill carries "pick the file". Expanding here was the 6dp hop.
        assertFalse(
            spotoolfyShowsImportStatus(
                SpotoolfyStepState(choice = SpotoolfyChoice.Using, installed = false),
            ),
        )
    }

    @Test
    fun should_showStatus_when_usingWithSpotoolfyInstalled() {
        assertTrue(
            spotoolfyShowsImportStatus(
                SpotoolfyStepState(choice = SpotoolfyChoice.Using, installed = true),
            ),
        )
    }

    @Test
    fun should_showStatus_when_fileIsBeingReadWithoutSpotoolfy() {
        assertTrue(
            spotoolfyShowsImportStatus(
                SpotoolfyStepState(
                    choice = SpotoolfyChoice.Using,
                    import = SpotoolfyImport.Reading,
                    installed = false,
                ),
            ),
        )
    }

    @Test
    fun should_hideStatus_when_notUsingOrUnanswered() {
        assertFalse(spotoolfyShowsImportStatus(SpotoolfyStepState(choice = SpotoolfyChoice.NotUsing, installed = true)))
        assertFalse(spotoolfyShowsImportStatus(SpotoolfyStepState(choice = null, installed = true)))
    }
}
