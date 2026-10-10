package com.gpo.yoin

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StartedActivityCounterTest {

    @Test
    fun should_reportForeground_when_firstActivityStarts() {
        val counter = StartedActivityCounter()
        assertTrue(counter.onStarted())
        // A detail Activity on top, then back: the app never left the foreground.
        assertFalse(counter.onStarted())
        assertFalse(counter.onStopped())
    }

    @Test
    fun should_reportBackground_when_lastActivityStops() {
        val counter = StartedActivityCounter()
        counter.onStarted()
        counter.onStarted()
        assertFalse(counter.onStopped())
        assertTrue(counter.onStopped())
    }

    @Test
    fun should_reportForegroundAgain_when_appReturnsFromBackground() {
        val counter = StartedActivityCounter()
        counter.onStarted()
        counter.onStopped()
        assertTrue(counter.onStarted())
    }

    @Test
    fun should_stayForeground_when_nextActivityStartsBeforeThePreviousStops() {
        // Activity handoffs start the next host before stopping the last one.
        val counter = StartedActivityCounter()
        assertTrue(counter.onStarted())
        assertFalse(counter.onStarted())
        assertFalse(counter.onStopped())
        assertFalse(counter.onStarted())
        assertFalse(counter.onStopped())
        assertTrue(counter.onStopped())
    }
}
