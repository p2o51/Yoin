package com.gpo.yoin.data.integration.neodb

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class NeoDbRestoreGuardTest {
    @get:Rule
    val temp = TemporaryFolder()

    @Test
    fun `no restore leaves the dirty flags alone`() = runTest {
        var cleared = 0
        NeoDbRestoreGuard.clearIfRestored(temp.root) { cleared++ }
        assertEquals(0, cleared)
    }

    @Test
    fun `a restore clears the flags once`() = runTest {
        NeoDbRestoreGuard.markRestored(temp.root)
        var cleared = 0
        NeoDbRestoreGuard.clearIfRestored(temp.root) { cleared++ }
        NeoDbRestoreGuard.clearIfRestored(temp.root) { cleared++ }
        assertEquals(1, cleared)
        assertFalse(NeoDbRestoreGuard.marker(temp.root).exists())
    }

    @Test
    fun `a failed clear keeps the marker for the next launch`() = runTest {
        NeoDbRestoreGuard.markRestored(temp.root)
        runCatching { NeoDbRestoreGuard.clearIfRestored(temp.root) { error("db busy") } }
        assertTrue(NeoDbRestoreGuard.marker(temp.root).exists())
    }
}
