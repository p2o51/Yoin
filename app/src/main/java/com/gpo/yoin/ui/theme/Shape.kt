package com.gpo.yoin.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.ui.unit.dp
import com.gpo.yoin.shared.tokens.YoinTokens

/**
 * MD3 Expressive 10-level shape system.
 * Maps to Material3 Shapes where possible; extras are available as standalone tokens.
 */
val YoinShapes = Shapes(
    extraSmall = RoundedCornerShape(4.dp),
    small = RoundedCornerShape(8.dp),
    medium = RoundedCornerShape(12.dp),
    large = RoundedCornerShape(16.dp),
    extraLarge = RoundedCornerShape(28.dp),
)

// Additional MD3 Expressive shape tokens not covered by Material3 Shapes
object YoinShapeTokens {
    val None = RoundedCornerShape(0.dp)
    val ExtraSmall = RoundedCornerShape(4.dp)
    val Small = RoundedCornerShape(8.dp)
    val Medium = RoundedCornerShape(12.dp)
    val Large = RoundedCornerShape(16.dp)
    val LargeIncreased = RoundedCornerShape(20.dp)
    val ExtraLarge = RoundedCornerShape(28.dp)
    val ExtraLargeIncreased = RoundedCornerShape(32.dp)
    val ExtraExtraLarge = RoundedCornerShape(48.dp)
    val Full = RoundedCornerShape(50)
}

/**
 * Semantic shapes for content imagery. Content stays close to square
 * (Spotify-style restraint) while interactive controls keep their pills; the
 * static variants use continuous (iOS-style) corner curvature.
 *
 * The *Animated twins carry the same radii as plain circular corners for
 * elements whose clip is resized or interpolated per frame (shared-element
 * flights, carousel masks, reshape morphs) — those must stay on the cheap
 * Outline.Rounded path, and morph overlays lerp raw circular radii anyway.
 */
object YoinArtworkShapes {
    /** 34–60dp list/queue/sheet thumbnails. */
    val Thumb = ContinuousRoundedCornerShape(YoinTokens.Shape.THUMB.dp)

    /** Thumb radius for morph/shared-element endpoints (bar mini art, NP docked cover). */
    val ThumbAnimated = RoundedCornerShape(YoinTokens.Shape.THUMB.dp)

    /** ~100–200dp shelf/grid covers. */
    val Cover = ContinuousRoundedCornerShape(YoinTokens.Shape.COVER.dp)

    /** 170–300dp hero covers. */
    val Hero = ContinuousRoundedCornerShape(YoinTokens.Shape.HERO.dp)

    /** Hero radius for per-frame-resized clips (carousel masks). */
    val HeroAnimated = RoundedCornerShape(YoinTokens.Shape.HERO.dp)

    /**
     * Now Playing main cover — deliberately rounder than Hero so it sits in
     * the same roundness family as the rating capsule beside it. Paired with
     * [NowPlayingCoverDocked] as the CoverTransitionOverlay's flight
     * endpoints; the overlay lerps the same continuous curvature per frame,
     * so all three must stay in lockstep.
     */
    val NowPlayingCover = ContinuousRoundedCornerShape(YoinTokens.Shape.NOW_PLAYING_COVER.dp)

    /** NP docked (lyrics-stage) 44dp cover; the overlay's landing endpoint. */
    val NowPlayingCoverDocked = ContinuousRoundedCornerShape(YoinTokens.Shape.NOW_PLAYING_COVER_DOCKED.dp)
}

/**
 * Semantic shapes for containers, decoupled from the raw MD3 size ramp so
 * containers and content can move independently.
 */
object YoinContainerShapes {
    /** Filled cards (bento tiles, profile tiles, note cards). */
    val Card = ContinuousRoundedCornerShape(YoinTokens.Shape.CARD.dp)

    /** Large section panels (empty states, section wrappers). */
    val Panel = ContinuousRoundedCornerShape(YoinTokens.Shape.PANEL.dp)

    /** Circular twin of [Panel] for clips resized per frame (the Home edit plate growing from the press point). */
    val PanelAnimated = RoundedCornerShape(YoinTokens.Shape.PANEL.dp)

    /**
     * Press/ripple bounds of full-width list rows. Circular on purpose: it is
     * mostly invisible (transparent rows) and rows are the most numerous
     * clipped elements on screen.
     */
    val ListRow = RoundedCornerShape(YoinTokens.Shape.LIST_ROW.dp)
}
