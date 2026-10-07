package com.gpo.yoin.ui.library

import org.junit.Assert.assertEquals
import org.junit.Test

class LibrarySearchFieldSyncTest {

    @Test
    fun should_ignoreTheViewModel_when_itEchoesAnEarlierKeystroke() {
        // Typing "yo": the VM publishes "y" after the field already holds "yo".
        assertEquals(SearchFieldSync.LateEcho, searchFieldSync("y", "yo", setOf("y", "yo")))
    }

    @Test
    fun should_writeIntoTheField_when_theQueryIsResetFromOutside() {
        assertEquals(SearchFieldSync.ExternalReset, searchFieldSync("", "radio", setOf("r", "ra", "radio")))
    }

    @Test
    fun should_doNothing_when_fieldAndViewModelAgree() {
        assertEquals(SearchFieldSync.InSync, searchFieldSync("radio", "radio", setOf("radio")))
    }
}
