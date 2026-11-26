package com.gpo.yoin.ui.home

import com.gpo.yoin.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Rearrange Home tip on a device whose Home never lays out in two columns (owner 2026-11-26): a phone
 * reads iOS's iPhone line, without the large screens' "drop beside" clause.
 */
class HomeTwoColumnDeviceTest {

    @Test
    fun should_neverReachTwoColumns_when_aPhoneInEitherOrientation() {
        // 393 × 852 portrait; on its side 852 × 393 is under 480 tall.
        assertFalse(deviceCanShowTwoColumnHome(393f, 852f, foldable = false))
        assertFalse(deviceCanShowTwoColumnHome(412f, 915f, foldable = false))
        // The owner's 16:10 phone: 751 × 475 on its side, still short of 840 wide.
        assertFalse(deviceCanShowTwoColumnHome(475f, 751f, foldable = false))
    }

    @Test
    fun should_reachTwoColumns_when_aTabletOrAFoldable() {
        assertTrue(deviceCanShowTwoColumnHome(800f, 1280f, foldable = false))
        // Asked in either orientation: the largest window on its side is what counts.
        assertTrue(deviceCanShowTwoColumnHome(1280f, 800f, foldable = false))
        // Folded, its outer screen never could; unfolded, the inner one does.
        assertTrue(deviceCanShowTwoColumnHome(412f, 915f, foldable = true))
    }

    @Test
    fun should_showThePhoneLine_when_homeNeverHasTwoColumns() {
        assertEquals(R.string.home_tip_edit_home_one_column, HomeBubbleTip.EditHome.textFor(twoColumnCapable = false))
        assertEquals(R.string.home_tip_edit_home, HomeBubbleTip.EditHome.textFor(twoColumnCapable = true))
        // Tips without a one-column line read the same everywhere.
        assertEquals(R.string.home_tip_note_moments, HomeBubbleTip.NoteMoments.textFor(twoColumnCapable = false))
    }
}
