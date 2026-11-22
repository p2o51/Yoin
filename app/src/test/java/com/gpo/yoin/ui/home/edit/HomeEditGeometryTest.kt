package com.gpo.yoin.ui.home.edit

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.unit.Density
import com.gpo.yoin.ui.home.HomeSection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

/** Port sheet §8 vectors at 1px = 1dp, the prototype's own scale. */
class HomeEditGeometryTest {

    private val density = Density(1f)

    @Test
    fun should_match_when_rubber56() {
        assertEquals(19.87f, rubber(56f, density), .01f)
        assertEquals(56f, rubber(1e9f, density), .01f)
        assertEquals(0f, rubber(0f, density), 0f)
    }

    @Test
    fun should_invert_when_unrubberOfRubber() {
        for (x in listOf(0f, 3f, 28f, 56f, 120f, 300f)) {
            assertEquals(x, unrubber(rubber(x, density), density), .01f)
        }
    }

    @Test
    fun should_hashKnownIds() {
        assertEquals(1775563416L, fnv1a32("activities"))
        assertEquals(1086541855L, fnv1a32("rediscover"))
        assertEquals(4225051900L, fnv1a32("recently_added"))
        assertEquals(24610801L, fnv1a32("jump_back_in"))

        val table = mapOf(
            "activities" to Triple(1, 2.2488f, 1.76f),
            "rediscover" to Triple(-1, 2.2488f, 1.19f),
            "recently_added" to Triple(1, 2.5411f, 4.08f),
            "jump_back_in" to Triple(-1, 2.4386f, 1.09f),
        )
        table.forEach { (id, expected) ->
            val params = swayParams(id)
            assertEquals(id, expected.first, params.sign)
            assertEquals(id, expected.first, swaySign(id))
            assertEquals(id, expected.second, params.freqHz, .0001f)
            assertEquals(id, expected.third, params.phaseRad, .0001f)
        }
    }

    @Test
    fun should_be0_419_when_theta360x412() {
        assertEquals(.419f, blockThetaDeg(360f, 412f, density), .001f)
        assertEquals(HomeEditTokens.ThetaMaxDeg, blockThetaDeg(100f, 100f, density), 0f)
        assertEquals(HomeEditTokens.ThetaMinDeg, blockThetaDeg(2000f, 2000f, density), 0f)
    }

    @Test
    fun should_clampCardAmp() {
        assertEquals(1.1f, cardAmpDeg(100f, 140f), .001f)
        assertEquals(.35f, cardAmpDeg(320f, 150f), .001f)
        assertEquals(.667f, cardAmpDeg(110f, 330f), .001f)
    }

    @Test
    fun should_resolveBleedingShelf_when_xInMargin() {
        val bands = listOf(SectionBand(HomeSection.RecentlyAdded, top = 100f, bottom = 300f, bleeds = true))
        val hit = resolveSectionAt(4f, 200f, bands, contentLeft = 16f, contentRight = 344f, 8f, 6f, editing = false)
        assertSame(bands[0], hit)
    }

    @Test
    fun should_resolveBlank_when_xInMarginOfNonBleedingSection() {
        val bands = listOf(SectionBand(HomeSection.Activities, top = 100f, bottom = 300f, bleeds = false))
        assertNull(resolveSectionAt(4f, 200f, bands, 16f, 344f, outsetH = 8f, outsetV = 6f, editing = true))
        // The plate outset counts as the block in edit mode only.
        assertSame(bands[0], resolveSectionAt(10f, 96f, bands, 16f, 344f, 8f, 6f, editing = true))
        assertNull(resolveSectionAt(10f, 96f, bands, 16f, 344f, 8f, 6f, editing = false))
    }

    @Test
    fun should_pickNearestBand_when_yBetweenSections() {
        val bands = listOf(
            SectionBand(HomeSection.Activities, 100f, 300f, bleeds = false),
            SectionBand(HomeSection.JumpBackIn, 318f, 500f, bleeds = false),
        )
        assertEquals(0, nearestSectionIndex(305f, bands))
        assertEquals(1, nearestSectionIndex(315f, bands))
        assertEquals(1, nearestSectionIndex(900f, bands))
        assertEquals(-1, nearestSectionIndex(0f, emptyList()))
    }

    @Test
    fun should_clipPressRectToContent_when_pressNearEdge() {
        val rect = pressRect(Offset(10f, 200f), Size(300f, 220f), charge = 1f, density)
        assertEquals(Rect(-4f, 148f, 62f, 224f), rect)
        assertEquals(Rect(0f, 152f, 58f, 220f), pressRect(Offset(10f, 200f), Size(300f, 220f), 0f, density))
    }

    @Test
    fun should_staggerRipple_when_blocksFarFromOrigin() {
        // Four blocks, origin 1: δ_max = 3 × .06.
        assertEquals(.5f / .82f, rippleProgress(.5f, index = 1, origin = 1, count = 4), 1e-5f)
        assertEquals(.38f / .82f, rippleProgress(.5f, index = 3, origin = 1, count = 4), 1e-5f)
        assertEquals(1f, rippleProgress(1f, index = 3, origin = 1, count = 4), 0f)
        assertEquals(0f, rippleProgress(.05f, index = 0, origin = 3, count = 4), 0f)
    }

    @Test
    fun should_growBadges_when_rippleProgresses() {
        assertEquals(0f, badgeAlpha(badgeLocal(.25f)), 0f)
        assertEquals(.6f, badgeScale(badgeLocal(.1f)), 0f)
        assertEquals(1f, badgeScale(badgeLocal(1f)), 0f)
        assertEquals(.5f, badgeAlpha(badgeLocal(.625f)), 1e-5f)
    }

    @Test
    fun should_keep6dpOutset_when_spacing18() {
        assertEquals(6f, plateOutsetVDp(18f), 0f)
        assertEquals(3f, plateOutsetVDp(10f), 0f)
        assertEquals(0f, plateOutsetVDp(2f), 0f)
    }

    @Test
    fun should_fadeBand_when_nearSafeEdges() {
        assertEquals(0f, edgeBand(40f, 200f, safeTop = 44f, safeBottom = 672f, fadePx = 48f), 0f)
        assertEquals(.5f, edgeBand(68f, 200f, 44f, 672f, 48f), 1e-5f)
        assertEquals(1f, edgeBand(300f, 400f, 44f, 672f, 48f), 0f)
        assertEquals(.25f, edgeBand(300f, 660f, 44f, 672f, 48f), 1e-5f)
    }

    @Test
    fun should_addAlongMotion_when_kickedWhileRinging() {
        assertEquals(-15.8f, kickInitialVelocity(.5f, sign = -1, running = false, currentVelocity = 0f), 1e-4f)
        assertEquals(-25.8f, kickInitialVelocity(.5f, sign = 1, running = true, currentVelocity = -10f), 1e-4f)
        // A spring at rest takes the section's own sign.
        assertEquals(31.6f, kickInitialVelocity(1f, sign = 1, running = true, currentVelocity = 0f), 1e-4f)
    }

    @Test
    fun should_notSway_when_envelopeDipsBelowZero() {
        val params = swayParams("activities")
        assertEquals(0f, swayValue(-.01f, 1f, .3f, params), 0f)
        assertEquals(0f, swayValue(1f, 0f, .3f, params), 0f)
        val expected = kotlin.math.sin(2 * Math.PI * params.freqHz * .3 + params.phaseRad).toFloat() * .5f
        assertEquals(expected, swayValue(.5f, 1f, .3f, params), 1e-5f)
    }

    @Test
    fun should_dropTinyAngles_when_belowDrawThreshold() {
        assertEquals(0f, blockAngleDeg(sway = .001f, blockKick = 0f, thetaDeg = .4f, gain = 1f, band = 1f), 0f)
        assertEquals(.4f * .6f, blockAngleDeg(1f, 0f, .4f, 1f, 1f), 1e-6f)
        // The block kick reaches every card along its parity; the card kick is the card's own.
        val card = cardAngleDeg(.5f, .2f, .1f, alt = -1, ampDeg = 1f, gain = 1f, band = 1f)
        assertEquals(-(.5f + .2f) + .1f, card, 1e-6f)
        assertEquals(0f, cardAngleDeg(1f, 0f, 0f, cardAlt(2), 1f, gain = 0f, band = 1f), 0f)
        assertEquals(.5f, wiggleGain(reduced = false, lifted = true, lift = .5f, stripCarried = false), 0f)
        assertEquals(0f, wiggleGain(reduced = false, lifted = true, lift = 1f, stripCarried = true), 0f)
    }

}
