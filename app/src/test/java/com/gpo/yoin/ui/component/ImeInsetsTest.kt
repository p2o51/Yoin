package com.gpo.yoin.ui.component

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Test

class ImeInsetsTest {

    // 1dp = 1px keeps the arithmetic readable.
    private val density = Density(1f)

    // Pixel Tablet portrait, measured: gesture bar 32dp, keyboard 376dp (it spans the bar).
    private val navigationBars = WindowInsets(bottom = 32.dp)
    private val keyboardUp = WindowInsets(bottom = 376.dp)
    private val keyboardDown = WindowInsets(bottom = 0.dp)

    @Test
    fun should_liftByKeyboardMinusNavBar_when_frameAlreadyClearedNavBar() {
        val lift = imeAboveNavigationBarInsets(keyboardUp, navigationBars)

        // Frame padding (32) + lift (344) = the keyboard's 376: the bar counted once.
        assertEquals(344, lift.getBottom(density))
        assertEquals(376, navigationBars.getBottom(density) + lift.getBottom(density))
    }

    @Test
    fun should_notLift_when_keyboardIsDown() {
        assertEquals(0, imeAboveNavigationBarInsets(keyboardDown, navigationBars).getBottom(density))
    }

    @Test
    fun should_neverGoNegative_when_keyboardIsShorterThanNavBar() {
        // Mid-animation the keyboard inset starts below the bar's height.
        val rising = WindowInsets(bottom = 20.dp)

        assertEquals(0, imeAboveNavigationBarInsets(rising, navigationBars).getBottom(density))
    }


    @Test
    fun should_onlyTouchBottomEdge_when_insetsHaveOtherSides() {
        val ime = WindowInsets(left = 10.dp, top = 20.dp, right = 30.dp, bottom = 376.dp)
        val bars = WindowInsets(top = 24.dp, bottom = 32.dp)

        val lift = imeAboveNavigationBarInsets(ime, bars)

        assertEquals(0, lift.getLeft(density, LayoutDirection.Ltr))
        assertEquals(0, lift.getTop(density))
        assertEquals(0, lift.getRight(density, LayoutDirection.Ltr))
    }
}
