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
    fun should_computeStack_when_safe44to672N4k1finger300() {
        val metrics = stripMetrics(
            count = 4,
            carriedIndex = 1,
            fingerY = 300f,
            safeTop = 44f,
            safeBottom = 672f,
            contentLeft = 16f,
            contentWidth = 332f,
            density = density,
        )
        assertEquals(64f, metrics.height, 0f)
        assertEquals(72f, metrics.pitch, 0f)
        assertEquals(280f, metrics.stackHeight, 0f)
        assertEquals(196f, metrics.top, 0f)
        assertEquals(332f, metrics.width, 0f)
        assertEquals(268f, metrics.slotY(1), 0f)
    }

    @Test
    fun should_centreCarriedStripOnFinger_when_stackFits() {
        val metrics = stripMetrics(4, 1, 300f, 44f, 672f, 16f, 900f, density)
        assertEquals(300f, metrics.slotY(1) + metrics.height / 2f, 0f)
        // Wide panes cap the strip width; the cover edge follows the strip height.
        assertEquals(560f, metrics.width, 0f)
        assertEquals(36f, metrics.coverSize, 0f)
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
    fun should_skipAnchor_when_headerVisible() {
        assertNull(anchorScrollOffset(3, 200f, listOf(300, 300), 18, headerVisible = true))
    }

    @Test
    fun should_clampAnchor_when_heightsTooShort() {
        // Items 1 and 2 give 2 × (100 + 18) = 236px of room above item 3: the header stays out.
        assertEquals(-236, anchorScrollOffset(3, 400f, listOf(100, 100), 18, headerVisible = false))
        // The first section has nothing above it but the header.
        assertEquals(0, anchorScrollOffset(1, 120f, emptyList(), 18, headerVisible = false))
    }

    @Test
    fun should_skipAnchor_when_heightUnknown() {
        assertNull(anchorScrollOffset(3, 300f, listOf(null, 100), 18, headerVisible = false))
    }

    @Test
    fun should_anchorExactly_when_roomSuffices() {
        // Only the item right above is needed, so an unknown height further up doesn't matter.
        assertEquals(-200, anchorScrollOffset(3, 200f, listOf(null, 300), 18, headerVisible = false))
        assertEquals(40, anchorScrollOffset(2, -40f, listOf(null), 18, headerVisible = false))
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

    @Test
    fun should_startInvisibleAtEdge_when_sectionOffscreen() {
        val metrics = stripMetrics(4, 1, 300f, 44f, 672f, 16f, 332f, density)
        val below = stripStart(Rect(8f, 700f, 356f, 900f), metrics, above = true)
        assertEquals(0f, below.alpha, 0f)
        assertEquals(672f - 64f + 64f * .02f, below.rect.top, 1e-4f)
        assertEquals(332f * .96f, below.rect.width, 1e-4f)
        val unplaced = stripStart(null, metrics, above = true)
        assertEquals(44f + 64f * .02f, unplaced.rect.top, 1e-4f)
        val visible = stripStart(Rect(8f, 20f, 356f, 240f), metrics, above = false)
        assertEquals(Rect(8f, 44f, 356f, 240f), visible.rect)
        assertEquals(1f, visible.alpha, 0f)
    }

    @Test
    fun should_flyFromStartToSlot_when_foldRuns() {
        val metrics = stripMetrics(4, 1, 300f, 44f, 672f, 16f, 332f, density)
        val start = stripStart(Rect(8f, 100f, 356f, 400f), metrics, above = false)
        val folded = stripFrame(start, metrics.slotY(2), metrics, fold = 0f, carried = false, lift = 0f, density)
        assertEquals(start.rect, folded.rect)
        assertEquals(0f, folded.plateAlpha, 0f)
        val open = stripFrame(start, metrics.slotY(2), metrics, fold = 1f, carried = false, lift = 0f, density)
        assertEquals(Rect(16f, 340f, 348f, 404f), open.rect)
        assertEquals(1f, open.labelAlpha, 0f)
        assertEquals(HomeEditTokens.StripTint, open.tint, 0f)
        assertEquals(20f, open.radius, 0f)
        assertEquals(0f, open.shadowAlpha, 0f)
        // The strip plates fade in exactly as the feed fades out.
        for (fold in listOf(.1f, .2f, .3f, .45f)) {
            val frame = stripFrame(start, metrics.slotY(2), metrics, fold, false, 0f, density)
            assertEquals(1f, frame.plateAlpha + feedFoldAlpha(fold), 1e-5f)
        }
        val carried = stripFrame(start, metrics.slotY(1), metrics, fold = 1f, carried = true, lift = 1f, density)
        assertEquals(332f * 1.02f, carried.rect.width, 1e-3f)
        assertEquals(metrics.slotY(1) + 32f, carried.rect.center.y, 1e-3f)
        assertEquals(1f, carried.shadowAlpha, 0f)
        assertEquals(metrics.slotY(1), carried.labelOffset.y, 1e-3f)
        assertEquals(0f, holeAlpha(.3f), 0f)
    }

    @Test
    fun should_fadeTheFeedByAWash_when_stripsFold() {
        for (fold in listOf(-.01f, 0f, .1f, .3f, .449f)) {
            // Drawn under a page wash: the same picture as the feed's own alpha.
            assertEquals(1f, feedFoldShown(fold), 0f)
            assertEquals(feedFoldAlpha(fold), feedFoldShown(fold) * (1f - feedFoldWash(fold)), 1e-6f)
        }
        // Once the wash covers it, the feed is skipped and the wash is not needed.
        for (fold in listOf(.45f, .7f, 1f, 1.015f)) {
            assertEquals(0f, feedFoldShown(fold), 0f)
            assertEquals(1f, feedFoldWash(fold), 0f)
        }
    }
}
