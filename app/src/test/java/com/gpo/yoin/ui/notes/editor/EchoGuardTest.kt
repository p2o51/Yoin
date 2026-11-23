package com.gpo.yoin.ui.notes.editor

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EchoGuardTest {

    @Test
    fun should_consumeEchoesInOrder_andTakeAGenuineRevert() {
        val guard = EchoGuard()
        guard.sent("a")
        guard.sent("ab")
        guard.sent("abc")
        // the host catches up a step at a time: each is an echo
        assertTrue(guard.isEcho("a"))
        assertTrue(guard.isEcho("abc"))
        // later the host goes back to "a" on its own (an undo, a reload): not an echo any more
        assertFalse(guard.isEcho("a"))
    }

    @Test
    fun should_takeAnExternalChange_andForgetWhatWasPending() {
        val guard = EchoGuard()
        guard.sent("draft")
        assertFalse(guard.isEcho(""))
        assertFalse(guard.isEcho("draft"))
    }
}
