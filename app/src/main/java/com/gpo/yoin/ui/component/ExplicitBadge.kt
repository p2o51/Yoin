package com.gpo.yoin.ui.component

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.AlignmentLine
import androidx.compose.ui.layout.FirstBaseline
import androidx.compose.ui.layout.LastBaseline
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.isSpecified
import androidx.compose.ui.unit.sp
import com.gpo.yoin.R
import com.gpo.yoin.ui.theme.YoinTheme
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/*
 * The "E" badge (owner 2026-11-25): a song or a release its service marks
 * explicit (Apple Music, Spotify; data/model/ContentRating) wears a small
 * rounded square with an E cut out of it, right after its title, Apple Music
 * style. Only explicit ever shows one; a clean or unknown one shows nothing and
 * lays out exactly as before. The square is the title's cap height, sits on its
 * baseline like a capital letter, and is drawn in the title's neutral secondary
 * colour (onSurfaceVariant): it says what the song is without competing with
 * its name. The E is a knockout, so the page behind shows through on any
 * surface (covers' colours, the album page's spectrum bar, Now Playing's aurora).
 */

/** Google Sans Flex's cap height (OS/2 sCapHeight 1432 / 2000 em). */
private const val CapHeightEm = 0.716f

/** The knocked-out E's own cap height, as a share of the square. */
private const val LetterShare = 0.58f

/** The square's corner, as a share of its side (Apple's badge is about a fifth). */
private const val CornerShare = 0.2f

/** The square's side for [style]: its cap height, at least [MinSide] so the E still reads on small text. */
internal fun Density.explicitBadgeSidePx(style: TextStyle, fallback: TextUnit): Float {
    val fontSize = if (style.fontSize.isSpecified) style.fontSize else fallback
    val size = if (fontSize.isSpecified) fontSize else 14.sp
    return max(size.toPx() * CapHeightEm, MinSide.toPx())
}

private val MinSide = 9.dp

/**
 * Space between the title's last glyph and the badge: [ExplicitBadgeGap] (a list row's, the same as
 * the note marker's), growing with large type (a third of the type size).
 */
internal fun Density.explicitBadgeGapPx(style: TextStyle, fallback: TextUnit): Int {
    val fontSize = if (style.fontSize.isSpecified) style.fontSize else fallback
    val size = if (fontSize.isSpecified) fontSize else 14.sp
    return max(size.toPx() * 0.33f, ExplicitBadgeGap.toPx()).roundToInt()
}

/**
 * Whether the badge may follow a title in a [maxWidth] box, leaving the title [titleRoom]: the title
 * keeps at least half the box, or all of itself if it is shorter ([naturalTitle]). On a tile too narrow
 * for that the badge is left out, so the name is never squeezed to a couple of glyphs beside it. Pure.
 */
internal fun explicitBadgeFits(maxWidth: Int, titleRoom: Int, naturalTitle: Int): Boolean =
    titleRoom >= min(naturalTitle, maxWidth / 2)

/**
 * "Explicit" for TalkBack on a title that may leave its badge out on a narrow tile
 * ([explicitBadgeFits]): said by the title itself, so it is read whether or not the badge is drawn
 * (the badge inside is drawn with `describe = false`).
 */
@Composable
internal fun Modifier.explicitTitleSemantics(): Modifier {
    val description = stringResource(R.string.cmp_explicit_badge_cd)
    return semantics { contentDescription = description }
}

/** The drawn badge's test tag (its "Explicit" may be said by the title around it instead). */
const val ExplicitBadgeTestTag = "ExplicitBadge"

/** The badge's distance from a list row's title, as the note marker's (SongListItem, AlbumTrackRow). */
val ExplicitBadgeGap = 6.dp

/**
 * The badge for a title set in [textStyle]: a cap-height rounded square in [color] with the
 * letter E cut out. Reports the bottom of the square as its first and last baseline, so a
 * baseline-aligned row sets it on the title's baseline. TalkBack reads it as "Explicit".
 */
@Composable
fun ExplicitBadge(
    textStyle: TextStyle,
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.onSurfaceVariant,
    /**
     * The badge says "Explicit" itself. False where the title around it says it instead
     * ([explicitTitleSemantics]), so it is read whether or not the badge is drawn.
     */
    describe: Boolean = true,
) {
    val density = LocalDensity.current
    val fallbackSize = LocalTextStyle.current.fontSize
    val sidePx = with(density) { explicitBadgeSidePx(textStyle, fallbackSize) }
    val measurer = rememberTextMeasurer(cacheSize = 2)
    val letterStyle = remember(textStyle, sidePx) {
        TextStyle(
            fontFamily = textStyle.fontFamily,
            fontWeight = FontWeight.Bold,
            fontSize = with(density) { (sidePx * LetterShare / CapHeightEm).toSp() },
            letterSpacing = 0.sp,
        )
    }
    val description = stringResource(R.string.cmp_explicit_badge_cd)
    Canvas(
        modifier = modifier
            .testTag(ExplicitBadgeTestTag)
            .then(if (describe) Modifier.semantics { contentDescription = description } else Modifier)
            .layout { measurable, _ ->
                val px = ceil(sidePx).toInt()
                val placeable = measurable.measure(Constraints.fixed(px, px))
                layout(px, px, alignmentLines = mapOf(FirstBaseline to px, LastBaseline to px)) {
                    placeable.place(0, 0)
                }
            }
            // Its own layer, so the E clears the square and not what lies under it.
            .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen },
    ) {
        val s = size.minDimension
        drawRoundRect(color = color, cornerRadius = CornerRadius(s * CornerShare))
        val letter = measurer.measure("E", letterStyle)
        val capHeight = letterStyle.fontSize.toPx() * CapHeightEm
        // Centre the E's cap box in the square; its advance (with side bearings) centres horizontally.
        val top = (s + capHeight) / 2f - letter.firstBaseline
        val left = (s - letter.size.width) / 2f
        drawText(
            textLayoutResult = letter,
            color = Color.Black,
            topLeft = Offset(left, top),
            blendMode = BlendMode.DstOut,
        )
    }
}

/**
 * [title] with the [ExplicitBadge] right after its last glyph when [explicit]; without it, [title]
 * alone with [modifier], laid out exactly as before. [style] is what [title] draws in, measured to
 * find where its text ends: a cut title ends on its ellipsis and the badge follows it, so the badge
 * never falls off the end. [maxLines] as [title] sets it; on more than one line the badge follows
 * the last. [fillsWidth]: [title] takes all the width it is given (a marquee), so it is given just
 * its text's width and the badge follows that.
 */
@Composable
fun ExplicitTitle(
    text: String,
    explicit: Boolean,
    style: TextStyle,
    modifier: Modifier = Modifier,
    maxLines: Int = 1,
    fillsWidth: Boolean = false,
    badgeColor: Color = MaterialTheme.colorScheme.onSurfaceVariant,
    title: @Composable (modifier: Modifier) -> Unit,
) {
    if (!explicit) {
        title(modifier)
        return
    }
    val measurer = rememberTextMeasurer()
    val fallbackSize = LocalTextStyle.current.fontSize
    val layoutDirection = LocalLayoutDirection.current
    Layout(
        modifier = modifier.explicitTitleSemantics(),
        content = {
            title(Modifier)
            ExplicitBadge(textStyle = style, color = badgeColor, describe = false)
        },
    ) { measurables, constraints ->
        val gap = explicitBadgeGapPx(style, fallbackSize)
        val bounded = constraints.hasBoundedWidth
        // +1: the measured width is rounded down; a pixel less would cut or scroll.
        val natural = measurer.measure(text, style, maxLines = 1, softWrap = false).size.width + 1
        val badgeWidth = measurables[1].maxIntrinsicWidth(constraints.maxHeight)
        // The title wins: on a narrow tile the badge goes rather than squeeze the name to a few glyphs.
        val showBadge = !bounded ||
            explicitBadgeFits(constraints.maxWidth, constraints.maxWidth - badgeWidth - gap, natural)
        val badge = if (showBadge) measurables[1].measure(Constraints()) else null
        val room = if (badge != null) badge.width + gap else 0
        val available = if (bounded) (constraints.maxWidth - room).coerceAtLeast(0) else Int.MAX_VALUE
        val titleMax = if (fillsWidth) min(natural, available) else available
        val titlePlaceable = measurables[0].measure(
            Constraints(
                minWidth = 0,
                maxWidth = if (titleMax == Int.MAX_VALUE) Constraints.Infinity else titleMax,
                minHeight = 0,
                maxHeight = constraints.maxHeight,
            ),
        )
        if (badge == null) {
            return@Layout layout(
                titlePlaceable.width.coerceIn(constraints.minWidth, constraints.maxWidth),
                titlePlaceable.height.coerceAtLeast(constraints.minHeight),
            ) { titlePlaceable.placeRelative(0, 0) }
        }
        val end: Int
        val lastBaseline: Int
        if (fillsWidth) {
            end = titlePlaceable.width
            lastBaseline = titlePlaceable[FirstBaseline]
        } else {
            val laid = measurer.measure(
                text,
                style,
                overflow = TextOverflow.Ellipsis,
                maxLines = maxLines,
                constraints = Constraints(maxWidth = titlePlaceable.width.coerceAtLeast(1)),
            )
            val last = laid.lineCount - 1
            val lineEnd = if (layoutDirection == LayoutDirection.Ltr) {
                laid.getLineRight(last)
            } else {
                laid.size.width - laid.getLineLeft(last)
            }
            end = ceil(lineEnd).toInt().coerceIn(0, titlePlaceable.width)
            lastBaseline = titlePlaceable[LastBaseline]
        }
        val badgeX = end + gap
        val baseline = if (lastBaseline == AlignmentLine.Unspecified) {
            (titlePlaceable.height + badge.height) / 2
        } else {
            lastBaseline
        }
        // The square's bottom on the last line's baseline, like a capital letter's.
        val badgeY = baseline - badge.height
        val width = max(titlePlaceable.width, badgeX + badge.width)
            .coerceIn(constraints.minWidth, if (bounded) constraints.maxWidth else Int.MAX_VALUE)
        val height = max(titlePlaceable.height, badgeY + badge.height).coerceAtLeast(constraints.minHeight)
        val firstBaseline = titlePlaceable[FirstBaseline]
        val lines = buildMap<AlignmentLine, Int> {
            if (firstBaseline != AlignmentLine.Unspecified) put(FirstBaseline, firstBaseline)
            if (lastBaseline != AlignmentLine.Unspecified) put(LastBaseline, lastBaseline)
        }
        layout(width, height, alignmentLines = lines) {
            titlePlaceable.placeRelative(0, 0)
            badge.placeRelative(badgeX, badgeY.coerceAtLeast(0))
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun ExplicitTitlePreview() {
    YoinTheme {
        Column(modifier = Modifier.padding(16.dp)) {
            listOf(
                MaterialTheme.typography.titleSmall,
                MaterialTheme.typography.titleMedium,
                MaterialTheme.typography.headlineSmall,
            ).forEach { style ->
                ExplicitTitle(text = "HUMBLE.", explicit = true, style = style) { m ->
                    Text("HUMBLE.", style = style, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = m)
                }
            }
            Row(modifier = Modifier.width(200.dp), verticalAlignment = Alignment.CenterVertically) {
                val style = MaterialTheme.typography.titleMedium
                ExplicitTitle(
                    text = "A title far too long to fit in the row",
                    explicit = true,
                    style = style,
                ) { m ->
                    Text(
                        "A title far too long to fit in the row",
                        style = style,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = m,
                    )
                }
            }
        }
    }
}
