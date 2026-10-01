package com.gpo.yoin.ui.detail

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.unit.Dp
import com.gpo.yoin.ui.component.YoinMarkHub
import com.gpo.yoin.ui.component.YoinMarkViewBox
import com.gpo.yoin.ui.component.drawYoinArm

/*
 * Detail-page backdrops. Each page type reads a different part of the Yoin
 * mark, so the family is recognisable without repeating itself:
 *
 *   Album    — the two lower arms, enlarged into edge-bleeding colour blocks
 *              (AlbumArrowBackground).
 *   Artist   — the WHOLE mark as a pinwheel, the portrait sitting on its hub.
 *   Playlist — the upper arm's V repeated as a layered stack (a stack of
 *              records / a run of tracks) behind the cover.
 *
 * Colours are the page's cover-seeded roles; the white centre lines stay as a
 * translucent surface-coloured line, as on the album blocks.
 */

// ---------------------------------------------------------------------------
// Artist — pinwheel.
// ---------------------------------------------------------------------------

/** Mark size as a multiple of the portrait: the arms reach well past the circle. */
internal const val ArtistPinwheelScale = 2.1f

/** Resting turn of the pinwheel. At 0° the lower arms would sit where the album's blocks do. */
internal const val ArtistPinwheelRestDegrees = 30f

/**
 * The whole three-arm mark, centred on the band so its hub sits under the
 * portrait. [rotationDegrees] is read at draw time (scroll-linked turn), so
 * a scrolling page never recomposes this. The Canvas does not clip, so the
 * arms may reach past this element's bounds (the page edges clip them).
 *
 * @param colors arm colours in mark order: lower-left, upper, lower-right.
 */
@Composable
internal fun ArtistPinwheelBackground(
    colors: List<Color>,
    lineColor: Color,
    portraitSize: Dp,
    rotationDegrees: () -> Float,
    modifier: Modifier = Modifier,
    markScale: Float = ArtistPinwheelScale,
) {
    Canvas(modifier) {
        val scale = portraitSize.toPx() * markScale / YoinMarkViewBox
        val hub = YoinMarkHub
        withTransform({
            translate(size.width / 2f, size.height / 2f)
            rotate(rotationDegrees(), pivot = Offset.Zero)
            scale(scale, scale, pivot = Offset.Zero)
            translate(-hub.x, -hub.y)
        }) {
            for (arm in 0..2) drawYoinArm(arm, colors[arm], lineColor)
        }
    }
}

// ---------------------------------------------------------------------------
// Playlist — stacked V.
// ---------------------------------------------------------------------------

// The upper arm's own box in view space: x 169–842 (centre ≈505), and the
// visual middle of the V's wings sits around y≈350.
private val UpperArmCentre = Offset(505f, 350f)
private const val UpperArmWidth = 674f

/** Each layer spans this multiple of the band width, so both wings bleed off the edges. */
private const val PlaylistStackWidthFactor = 1.3f

/**
 * First layer's offset from the band centre, and the step between layers — ×
 * cover side. Sized so the back layer's wing tips stay inside the hero band
 * (they never reach up into the header).
 */
private const val PlaylistStackFirstOffset = -0.06f
private const val PlaylistStackStep = 0.14f

/**
 * The mark's upper arm, repeated as a layered stack behind the cover. The
 * layers step down the band; the last one drawn (front) is the lowest, so its
 * V dips below the cover like the lip of a stack.
 *
 * @param colors layer colours, back to front.
 */
@Composable
internal fun PlaylistStackBackground(
    colors: List<Color>,
    lineColor: Color,
    coverSide: Dp,
    modifier: Modifier = Modifier,
) {
    Canvas(modifier) {
        val scale = size.width * PlaylistStackWidthFactor / UpperArmWidth
        val side = coverSide.toPx()
        colors.forEachIndexed { index, color ->
            val dy = side * (PlaylistStackFirstOffset + index * PlaylistStackStep)
            withTransform({
                translate(size.width / 2f, size.height / 2f + dy)
                scale(scale, scale, pivot = Offset.Zero)
                translate(-UpperArmCentre.x, -UpperArmCentre.y)
            }) {
                drawYoinArm(arm = 1, color = color, lineColor = lineColor)
            }
        }
    }
}
