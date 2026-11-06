package com.gpo.yoin.ui.detail

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.unit.Dp
import com.gpo.yoin.ui.nowplaying.dotDissolve
import kotlin.math.cos
import kotlin.math.sin

/*
 * Detail-page backdrops. Each page type has its own shape behind its art, so
 * the family is recognisable without repeating itself:
 *
 *   Album    — the mark's two lower arms, enlarged into edge-bleeding colour
 *              blocks (the butterfly, AlbumSpectrumBar.kt).
 *   Artist   — an open ring round the portrait (owner 2026-11-06, picked from
 *              the shape candidates; replaces the three-arm pinwheel).
 *
 * The Playlist page draws no mark: its own album covers fan out behind its
 * cover as sleeves (PlaylistSleeves.kt).
 *
 * Every one of them leaves the same way: it breaks up into fine dots (the focus
 * mode's dot dissolve, owner 2026-11-06).
 */

// ---------------------------------------------------------------------------
// Artist — open ring.
// ---------------------------------------------------------------------------

/** How far the ring reaches from the portrait's centre, as a multiple of the portrait (phone hero). */
internal const val ArtistRingReach = 0.84f

// Of the ring's room past the portrait's edge: the gap of page colour, then the stroke.
private const val ArtistRingGapShare = 0.41f
private const val ArtistRingStrokeShare = 0.59f

// The opening, centred at the upper right (−45°), and how wide it is.
private const val ArtistRingOpeningCentreDegrees = -45f
private const val ArtistRingOpeningRadians = 0.9f

// The dot sitting in the opening, against the stroke.
private const val ArtistRingDotShare = 0.45f

/**
 * A thick, round-ended ring round the portrait, open at the upper right, with a
 * dot sitting in the opening (the mark's dot). [reach] is the ring's outer
 * radius as a multiple of [portraitSize]; it is drawn centred on this element
 * and may run past its bounds (sized for itself, so the dissolve's layer holds
 * it whole). [rotationDegrees] (scroll-linked turn) and [leaving] (0 = whole,
 * 1 = dissolved into dots) are read at draw time only.
 */
@Composable
internal fun ArtistOpenRing(
    ringColor: Color,
    dotColor: Color,
    portraitSize: Dp,
    rotationDegrees: () -> Float,
    leaving: () -> Float,
    modifier: Modifier = Modifier,
    reach: Float = ArtistRingReach,
) {
    Canvas(
        modifier
            .requiredSize(portraitSize * (2f * reach) + portraitSize * 0.1f)
            .dotDissolve(leaving),
    ) {
        val portrait = portraitSize.toPx()
        val room = portrait * (reach - 0.5f)
        val stroke = room * ArtistRingStrokeShare
        val radius = portrait * 0.5f + room * ArtistRingGapShare + stroke / 2f
        val centre = Offset(size.width / 2f, size.height / 2f)
        val openingDegrees = Math.toDegrees(ArtistRingOpeningRadians.toDouble()).toFloat()
        rotate(rotationDegrees(), pivot = centre) {
            drawArc(
                color = ringColor,
                startAngle = ArtistRingOpeningCentreDegrees + openingDegrees / 2f,
                sweepAngle = 360f - openingDegrees,
                useCenter = false,
                topLeft = Offset(centre.x - radius, centre.y - radius),
                size = Size(radius * 2f, radius * 2f),
                style = Stroke(width = stroke, cap = StrokeCap.Round),
            )
            val a = Math.toRadians(ArtistRingOpeningCentreDegrees.toDouble())
            drawCircle(
                color = dotColor,
                radius = stroke * ArtistRingDotShare,
                center = Offset(centre.x + radius * cos(a).toFloat(), centre.y + radius * sin(a).toFloat()),
            )
        }
    }
}
