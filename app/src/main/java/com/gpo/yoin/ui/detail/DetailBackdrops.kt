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
 *
 * The Playlist page draws no mark: its own album covers fan out behind its
 * cover as sleeves (PlaylistSleeves.kt).
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
