package com.gpo.yoin.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.ui.unit.dp

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
    val Thumb = ContinuousRoundedCornerShape(4.dp)

    /** Thumb radius for morph/shared-element endpoints (bar mini art, NP docked cover). */
    val ThumbAnimated = RoundedCornerShape(4.dp)

    /** ~100–200dp shelf/grid covers. */
    val Cover = ContinuousRoundedCornerShape(4.dp)

    /** 170–300dp hero covers. */
    val Hero = ContinuousRoundedCornerShape(8.dp)

    /** Hero radius for per-frame-resized clips (carousel masks, NP main cover). */
    val HeroAnimated = RoundedCornerShape(8.dp)
}

/**
 * Semantic shapes for containers, decoupled from the raw MD3 size ramp so
 * containers and content can move independently.
 */
object YoinContainerShapes {
    /** Filled cards (bento tiles, profile tiles, note cards). */
    val Card = ContinuousRoundedCornerShape(16.dp)

    /** Large section panels (empty states, section wrappers). */
    val Panel = ContinuousRoundedCornerShape(20.dp)

    /**
     * Press/ripple bounds of full-width list rows. Circular on purpose: it is
     * mostly invisible (transparent rows) and rows are the most numerous
     * clipped elements on screen.
     */
    val ListRow = RoundedCornerShape(12.dp)
}
