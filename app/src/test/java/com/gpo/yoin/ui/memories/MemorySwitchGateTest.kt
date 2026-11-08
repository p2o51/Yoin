package com.gpo.yoin.ui.memories

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MemorySwitchGateTest {
    private val owner = MemoryOtherProfile(id = "p2", name = "demo")

    @Test
    fun firstSwitchWaitsForTheNoticeThenMarksItSeen() {
        var seen = false
        var switched = 0
        val gate = MemorySwitchGate(seen = { seen }, markSeen = { seen = true })

        gate.request(owner, "Amber Motel", opensAlbum = true) { switched++ }
        assertEquals(0, switched)
        assertNotNull(gate.pending)

        gate.confirm()
        assertEquals(1, switched)
        assertNull(gate.pending)
        assertTrue(seen)

        gate.request(owner, "Amber Motel", opensAlbum = true) { switched++ }
        assertEquals(2, switched)
        assertNull(gate.pending)
    }

    @Test
    fun dismissDoesNotSwitchAndAsksAgainNextTime() {
        var seen = false
        var switched = 0
        val gate = MemorySwitchGate(seen = { seen }, markSeen = { seen = true })

        gate.request(owner, "Song", opensAlbum = false) { switched++ }
        gate.dismiss()
        assertEquals(0, switched)
        assertNull(gate.pending)

        gate.request(owner, "Song", opensAlbum = false) { switched++ }
        assertNotNull(gate.pending)
    }
}
