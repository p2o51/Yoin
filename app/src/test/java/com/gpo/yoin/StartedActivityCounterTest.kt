package com.gpo.yoin

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
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

class StartedHostsTest {

    @Test
    fun should_moveToTheNextActivity_when_theHostFinishes() {
        // A widget's OpenNowPlayingActivity starts MainActivity and finishes.
        val hosts = StartedHosts<String>()
        hosts.onStarted("OpenNowPlaying")
        hosts.onStarted("Main")
        hosts.onStopped("OpenNowPlaying")
        assertEquals("Main", hosts.current)
    }

    @Test
    fun should_goBackToTheActivityBelow_when_theTopOneStops() {
        val hosts = StartedHosts<String>()
        hosts.onStarted("Main")
        hosts.onStarted("AlbumDetail")
        assertEquals("AlbumDetail", hosts.current)
        hosts.onStopped("AlbumDetail")
        assertEquals("Main", hosts.current)
        hosts.onStopped("Main")
        assertNull(hosts.current)
    }
}
