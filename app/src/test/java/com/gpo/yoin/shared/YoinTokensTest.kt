package com.gpo.yoin.shared

import androidx.compose.animation.core.SpringSpec
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MotionScheme
import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import com.gpo.yoin.shared.tokens.YoinTokens
import com.gpo.yoin.ui.component.FloatingBarButtonHeight
import com.gpo.yoin.ui.component.FloatingBarDetailPillWidth
import com.gpo.yoin.ui.component.FloatingBarItemGap
import com.gpo.yoin.ui.component.FloatingBarRowPadding
import com.gpo.yoin.ui.component.FloatingBarSplitWidth
import com.gpo.yoin.ui.experience.CenteredBarBottomMargin
import com.gpo.yoin.ui.experience.CenteredBarBottomMarginWide
import com.gpo.yoin.ui.experience.CenteredBarButtonHeight
import com.gpo.yoin.ui.experience.CenteredBarHeight
import com.gpo.yoin.ui.experience.CenteredBarHorizontalMargin
import com.gpo.yoin.ui.experience.CenteredBarMaxWidth
import com.gpo.yoin.ui.experience.FloatingBarHeight
import com.gpo.yoin.ui.experience.PortraitBarVerticalMargin
import com.gpo.yoin.ui.theme.YoinTypography
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The Android constants that now read YoinTokens still have the values they had as literals
 * (the "before" column below is the literal each replaced, from 69bfd77a0), and the tokens that
 * mirror Material values (type scale, motion schemes) match Material's own.
 */
class YoinTokensTest {

    @Test
    fun should_keep_the_bar_values_when_read_from_tokens() {
        listOf(
            "FloatingBarHeight" to (FloatingBarHeight to 68.dp),
            "CenteredBarHeight" to (CenteredBarHeight to 60.dp),
            "CenteredBarButtonHeight" to (CenteredBarButtonHeight to 44.dp),
            "PortraitBarVerticalMargin" to (PortraitBarVerticalMargin to 12.dp),
            "CenteredBarBottomMargin" to (CenteredBarBottomMargin to 24.dp),
            "CenteredBarBottomMarginWide" to (CenteredBarBottomMarginWide to 28.dp),
            "CenteredBarHorizontalMargin" to (CenteredBarHorizontalMargin to 20.dp),
            "CenteredBarMaxWidth" to (CenteredBarMaxWidth to 600.dp),
            "FloatingBarRowPadding" to (FloatingBarRowPadding to 10.dp),
            "FloatingBarButtonHeight" to (FloatingBarButtonHeight to 48.dp),
            "FloatingBarItemGap" to (FloatingBarItemGap to 8.dp),
            "FloatingBarSplitWidth" to (FloatingBarSplitWidth to 156.dp),
            "FloatingBarDetailPillWidth" to (FloatingBarDetailPillWidth to 200.dp),
            "portrait bar horizontal margin" to (YoinTokens.Bar.HORIZONTAL_MARGIN.dp to 16.dp),
        ).forEach { (name, pair) -> assertEquals(name, pair.second, pair.first) }
    }

    @Test
    fun should_keep_the_now_playing_values_when_read_from_tokens() {
        val n = YoinTokens.NowPlaying
        listOf(
            n.COLUMN_PADDING to 24.0, n.COLUMN_BOTTOM_FLOOR to 16.0, n.RATING_GAP to 12.0,
            n.PHONE_RATING_COLUMN to 56.0, n.PHONE_COVER_MAX to 312.0, n.PHONE_FOCUS_COVER_MAX to 420.0,
            n.COVER_FLOOR to 96.0, n.ENLARGED_COVER_COMFORT to 168.0, n.TOP_BAR_NOMINAL to 56.0, n.TOP_BAR_MIN to 48.0,
            n.COVER_GAP_NOMINAL to 16.0, n.COVER_GAP_MIN to 8.0, n.TAB_ROW_NOMINAL to 30.0, n.TAB_ROW_AIR to 10.0,
            n.ENLARGED_TOOL_TAB_ROW to 44.0, n.TAB_GAP_NOMINAL to 4.0, n.CONTROLS_SLOT_NOMINAL to 148.0,
            n.CONTROLS_AIR_MIN to 8.0, n.HERO_SLOT_NOMINAL to 86.0, n.HERO_AIR_MIN to 4.0, n.HERO_ONE_LINE_AIR to 12.0,
            n.ACCESSORY_SLOT_NOMINAL to 68.0, n.ACCESSORY_AIR_MIN to 8.0, n.ONE_LINE_ROW_NOMINAL to 44.0,
            n.ONE_LINE_AIR_MIN to 4.0, n.LYRIC_FRAME_HYSTERESIS to 8.0, n.COVER_FOCUS_MIN_GROWTH to 40.0,
            n.LYRICS_LIST_PADDING to 12.0, n.LYRICS_LIST_LINE_GAP to 2.0, n.LYRICS_FADE_BOTTOM to 24.0,
            n.CONTROL_SIZE to 56.0, n.PILL_HEIGHT to 44.0, n.PILL_ROW_WITH_CAST to 48.0, n.HERO_LINE_GAP to 2.0,
            n.WIDE_TITLE_LINE_GAP to 2.0, n.LYRIC_LINE_PADS to 8.0, n.RATING_TO_HEART_GAP to 8.0,
            n.HEART_BOTTOM_GAP to 6.0, n.DUAL_PANE_STACK_WIDTH to 312.0, n.DUAL_PANE_COVER_MIN to 168.0,
            n.DUAL_PANE_COVER_FLOOR to 140.0, n.WIDE_RATING_ROW to 48.0, n.WIDE_FRAME_NOMINAL to 16.0,
            n.WIDE_FRAME_MIN to 8.0, n.WIDE_BAR_GAP_NOMINAL to 8.0, n.WIDE_BAR_GAP_MIN to 4.0,
            n.WIDE_COVER_TO_RATING_NOMINAL to 12.0, n.WIDE_COVER_TO_RATING_MIN to 8.0,
            n.WIDE_RATING_TO_TRANSPORT_NOMINAL to 24.0, n.WIDE_RATING_TO_TRANSPORT_MIN to 12.0,
            n.WIDE_TRANSPORT_TO_TITLE_NOMINAL to 16.0, n.WIDE_TRANSPORT_TO_TITLE_MIN to 8.0, n.WIDE_COLUMN_GAP to 24.0,
            n.WIDE_RIGHT_COLUMN_MIN to 320.0, n.LANDSCAPE_RATING_ROW to 48.0, n.LANDSCAPE_RATING_GAP to 12.0,
            n.LANDSCAPE_COVER_FLOOR to 120.0, n.LANDSCAPE_COLUMN_GAP to 28.0, n.LANDSCAPE_FOCUS_RIGHT_MIN to 360.0,
        ).forEachIndexed { i, (token, before) -> assertEquals("dp token #$i", before.toFloat().dp, token.dp) }
        // Float literals the tokens replaced.
        assertEquals(1.5f, n.COVER_FOCUS_MAX_SCALE.toFloat())
        assertEquals(0.12f, n.COVER_FOCUS_MIN_GROWTH_FRACTION.toFloat())
        assertEquals(0.22f, n.LYRICS_ANCHOR_FRACTION.toFloat())
        assertEquals(0.42f, n.LANDSCAPE_COVER_WIDTH_SHARE.toFloat())
        assertEquals(1.25f, n.LINE_HEIGHT_FALLBACK.toFloat())
        assertEquals(2, n.PHONE_CLEAN_LINES)
        assertEquals(3, n.ENLARGED_CLEAN_LINES)
    }

    @Test
    fun should_keep_shape_and_detail_values_when_read_from_tokens() {
        val s = YoinTokens.Shape
        assertEquals(listOf(4.0, 4.0, 8.0, 16.0, 8.0, 16.0, 20.0, 12.0),
            listOf(s.THUMB, s.COVER, s.HERO, s.NOW_PLAYING_COVER, s.NOW_PLAYING_COVER_DOCKED, s.CARD, s.PANEL, s.LIST_ROW))
        assertEquals(0.74f, YoinTokens.Detail.ALBUM_COVER_WIDTH_FRACTION.toFloat())
        assertEquals(300.dp, YoinTokens.Detail.ALBUM_COVER_MAX.dp)
        assertEquals(28, YoinTokens.Detail.SPECTRUM_STRIPS)
    }

    @Test
    fun should_match_material_type_scale_and_yoin_tracking_when_comparing_type_tokens() {
        val m3 = Typography()
        val t = YoinTokens.Typography
        listOf(
            "displayLarge" to Triple(t.displayLarge, m3.displayLarge, YoinTypography.displayLarge),
            "displayMedium" to Triple(t.displayMedium, m3.displayMedium, YoinTypography.displayMedium),
            "displaySmall" to Triple(t.displaySmall, m3.displaySmall, YoinTypography.displaySmall),
            "headlineLarge" to Triple(t.headlineLarge, m3.headlineLarge, YoinTypography.headlineLarge),
            "headlineMedium" to Triple(t.headlineMedium, m3.headlineMedium, YoinTypography.headlineMedium),
            "headlineSmall" to Triple(t.headlineSmall, m3.headlineSmall, YoinTypography.headlineSmall),
            "titleLarge" to Triple(t.titleLarge, m3.titleLarge, YoinTypography.titleLarge),
            "titleMedium" to Triple(t.titleMedium, m3.titleMedium, YoinTypography.titleMedium),
            "titleSmall" to Triple(t.titleSmall, m3.titleSmall, YoinTypography.titleSmall),
            "bodyLarge" to Triple(t.bodyLarge, m3.bodyLarge, YoinTypography.bodyLarge),
            "bodyMedium" to Triple(t.bodyMedium, m3.bodyMedium, YoinTypography.bodyMedium),
            "bodySmall" to Triple(t.bodySmall, m3.bodySmall, YoinTypography.bodySmall),
            "labelLarge" to Triple(t.labelLarge, m3.labelLarge, YoinTypography.labelLarge),
            "labelMedium" to Triple(t.labelMedium, m3.labelMedium, YoinTypography.labelMedium),
            "labelSmall" to Triple(t.labelSmall, m3.labelSmall, YoinTypography.labelSmall),
        ).forEach { (name, triple) ->
            val (token, material: TextStyle, yoin: TextStyle) = triple
            assertEquals("$name size", material.fontSize.value, token.size.toFloat())
            assertEquals("$name line height", material.lineHeight.value, token.lineHeight.toFloat())
            assertEquals("$name weight", material.fontWeight?.weight, token.weight)
            assertEquals("$name tracking", yoin.letterSpacing.value, token.letterSpacing.toFloat())
        }
        // Literals the tokens replaced: 0.sp everywhere, 0.1.sp on the two small labels.
        assertEquals(0f, YoinTypography.bodyLarge.letterSpacing.value)
        assertEquals(0.1f, YoinTypography.labelSmall.letterSpacing.value)
        // GoogleSansFlexRounded: wght 500 / 600, ROND 100; GrooveType: 690 / 640, ROND 50, wdth 76 / 92, opsz 6..144.
        assertEquals(listOf(500, 600), listOf(t.roundedMedium.weight, t.roundedSemiBold.weight))
        assertEquals(listOf(100f, 100f), listOf(t.roundedMedium.round.toFloat(), t.roundedSemiBold.round.toFloat()))
        assertEquals(listOf(690, 640, 600), listOf(t.SCORE_WEIGHT_ALBUM, t.SCORE_WEIGHT_AVERAGE, t.CAPTION_WEIGHT))
        assertEquals(listOf(50f, 76f, 92f), listOf(t.SCORE_ROUND, t.SCORE_WIDTH_FOUR_CHARACTERS, t.SCORE_WIDTH).map { it.toFloat() })
        assertEquals(listOf(6, 144), listOf(t.OPTICAL_SIZE_MIN.toInt(), t.OPTICAL_SIZE_MAX.toInt()))
    }

    @OptIn(ExperimentalMaterial3ExpressiveApi::class)
    @Test
    fun should_mirror_material_motion_schemes_and_keep_yoin_springs_when_comparing_motion_tokens() {
        fun SpringSpec<Float>.pair() = dampingRatio to stiffness
        fun YoinTokens.Motion.Spring.pair() = damping.toFloat() to stiffness.toFloat()
        val e = MotionScheme.expressive()
        val s = MotionScheme.standard()
        val m = YoinTokens.Motion
        assertEquals((e.fastSpatialSpec<Float>() as SpringSpec<Float>).pair(), m.expressiveFastSpatial.pair())
        assertEquals((e.defaultSpatialSpec<Float>() as SpringSpec<Float>).pair(), m.expressiveDefaultSpatial.pair())
        assertEquals((e.slowSpatialSpec<Float>() as SpringSpec<Float>).pair(), m.expressiveSlowSpatial.pair())
        assertEquals((e.fastEffectsSpec<Float>() as SpringSpec<Float>).pair(), m.expressiveFastEffects.pair())
        assertEquals((e.defaultEffectsSpec<Float>() as SpringSpec<Float>).pair(), m.expressiveDefaultEffects.pair())
        assertEquals((e.slowEffectsSpec<Float>() as SpringSpec<Float>).pair(), m.expressiveSlowEffects.pair())
        assertEquals((s.fastSpatialSpec<Float>() as SpringSpec<Float>).pair(), m.standardFastSpatial.pair())
        assertEquals((s.defaultSpatialSpec<Float>() as SpringSpec<Float>).pair(), m.standardDefaultSpatial.pair())
        assertEquals((s.slowSpatialSpec<Float>() as SpringSpec<Float>).pair(), m.standardSlowSpatial.pair())
        // Literals the tokens replaced in Motion.kt.
        assertEquals(0.85f to 700f, m.stageSettle.pair())
        assertEquals(0.3f to 450f, m.homeEditKick.pair())
        assertEquals(0.01f, m.HOME_EDIT_KICK_THRESHOLD.toFloat())
        assertEquals(45f, m.artworkDissolve.stiffness.toFloat())
        assertEquals(90f, m.SEAM_FLOW_SETTLE_STIFFNESS.toFloat())
        assertEquals(1.3f, m.PREDICTIVE_BACK_SPEED_MULTIPLIER.toFloat())
        assertEquals(0.62f to 340f, m.mascotLand.pair())
        assertEquals(0.5f to 420f, m.mascotGesture.pair())
        assertEquals(0.42f to 520f, m.mascotHop.pair())
        assertEquals(0.18f to 520f, m.mascotShake.pair())
    }
}
