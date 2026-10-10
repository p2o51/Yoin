package com.gpo.yoin.ui.home

import com.gpo.yoin.data.source.spotify.SpotifyActivityArtistArtwork
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Owner Q16: Home asks Spotify only for the artists of Activities cards that
 * draw a picture — never the hero (an album or playlist) nor a strip (text
 * only), and never more than one pass may ask for.
 */
class HomeActivityPicturedEntriesTest {

    @Test
    fun should_leaveOutTheStrip_when_thePhoneBentoIsAtItsDefault() {
        val phone = activityBentoSpec(feedUnits = 2, isCompactHeight = false, seed = 0)

        // Hero, then small | wide, then the strip (entry 2).
        assertEquals(listOf(0, 1), activityPresetPicturedEntries(phone, hasHero = true, preset = HomeRowPreset.L))
        val xl = activityPresetPicturedEntries(phone, hasHero = true, preset = HomeRowPreset.XL)
        assertEquals(listOf(0, 1, 2, 3), xl)
    }

    @Test
    fun should_pictureEveryCard_when_theLandscapeRowHasNoStrip() {
        val landscape = activityBentoSpec(feedUnits = 4, isCompactHeight = true, seed = 0)

        assertEquals(
            listOf(0, 1, 2, 3, 4),
            activityPresetPicturedEntries(landscape, hasHero = true, preset = HomeRowPreset.XL)
        )
    }

    @Test
    fun should_leaveOutTheStrips_when_theTabletBentoIsXl() {
        val tablet = activityBentoSpec(feedUnits = 4, isCompactHeight = false, seed = 0)

        // Hero + wide, small | wide | small, wide | wide, then two strips (6, 7).
        assertEquals(
            listOf(0, 1, 2, 3, 4, 5),
            activityPresetPicturedEntries(tablet, hasHero = true, preset = HomeRowPreset.XL)
        )
    }

    @Test
    fun should_neverPictureAStripOrPassAPassesCap_when_anyBentoIsLaidOut() {
        for (units in 1..8) {
            for (compactHeight in listOf(false, true)) {
                for (hasHero in listOf(true, false)) {
                    for (seed in 0 until 12) {
                        val spec = activityBentoSpec(units, compactHeight, seed, hasHero)
                        for (preset in HomeRowPreset.entries) {
                            val pictured = activityPresetPicturedEntries(spec, hasHero, preset)
                            val strips = stripEntries(spec, hasHero, preset)
                            val seated = activityPresetSupportingCount(spec, hasHero, preset)
                            val case = "units=$units compact=$compactHeight hero=$hasHero seed=$seed $preset"
                            assertTrue(case, pictured.none { it in strips })
                            assertEquals(case, (0 until seated).filterNot { it in strips }, pictured)
                            assertTrue(case, pictured.size <= SpotifyActivityArtistArtwork.MAX_FETCHES_PER_PASS)
                        }
                    }
                }
            }
        }
    }

    private fun stripEntries(spec: ActivityBentoSpec, hasHero: Boolean, preset: HomeRowPreset): Set<Int> =
        if (spec.recipe == BentoRecipe.Units) {
            activityUnitSlots(spec, hasHero, supportingCount = Int.MAX_VALUE, preset = preset)
                .filter { it.kind == SlotKind.Strip }
                .mapTo(HashSet()) { it.entryIndex }
        } else {
            activityCodeRows(spec.recipe, preset, hasHero, supportingCount = Int.MAX_VALUE)
                .filter { it.shape == ActivityRowShape.Strip }
                .flatMap { row -> row.slots }
                .mapTo(HashSet()) { it.entryIndex }
        }
}
