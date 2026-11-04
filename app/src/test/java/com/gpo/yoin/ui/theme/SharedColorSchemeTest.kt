package com.gpo.yoin.ui.theme

import androidx.compose.material3.darkColorScheme
import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertSame
import org.junit.Test

/**
 * A new palette lands in one cut, and only a real change hands the window a
 * new instance: each instance recomposes the whole window (static locals).
 */
class SharedColorSchemeTest {

    @Test
    fun should_keepTheShownInstance_when_theTargetIsValueEqual() {
        val shared = SharedColorScheme()
        val first = darkColorScheme(primary = Color(0xFF336699))
        val rebuilt = darkColorScheme(primary = Color(0xFF336699))

        assertSame(first, shared.resolve(first))
        assertSame(first, shared.resolve(rebuilt))
    }

    @Test
    fun should_cutToTheTarget_when_thePaletteChanges() {
        val shared = SharedColorScheme()
        shared.resolve(darkColorScheme(primary = Color(0xFF336699)))
        val next = darkColorScheme(primary = Color(0xFFCC3344))

        assertSame(next, shared.resolve(next))
    }
}
