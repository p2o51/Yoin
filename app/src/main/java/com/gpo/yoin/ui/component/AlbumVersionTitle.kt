package com.gpo.yoin.ui.component

import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.gpo.yoin.data.model.albumNameWithoutVersion
import com.gpo.yoin.ui.theme.YoinTheme
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

/*
 * An album's version ("Deluxe", "全景声") as a tag after its name, wherever
 * the name is shown (owner 2026-11-06: not only the album page). The value is
 * the server's, never translated; the name drops the " (version)" the server
 * appended, so it isn't said twice.
 */

/**
 * [name] as [title] draws it, then [version] as a tag right after the name's
 * last character. Without a version it is [title] alone, the name untouched.
 * [title] gets the name to draw and a modifier to put on it; [titleStyle] is
 * the style it draws in, to measure the name with (a MarqueeText always fills
 * its width, so the name is given just the width it needs and the tag sits
 * after it, not at the far end). [nameEllipsizes]: the name is cut with an
 * ellipsis when it doesn't fit, so the tag follows the ellipsis; false for a
 * title that scrolls (MarqueeText) and so fills the width it is given.
 */
@Composable
fun AlbumVersionTitle(
    name: String,
    version: String?,
    titleStyle: TextStyle,
    modifier: Modifier = Modifier,
    tagStyle: TextStyle = MaterialTheme.typography.labelSmall,
    nameEllipsizes: Boolean = true,
    title: @Composable (text: String, modifier: Modifier) -> Unit,
) {
    val tag = version?.trim()?.takeIf(String::isNotEmpty)
    if (tag == null) {
        title(name, modifier)
        return
    }
    val shown = albumNameWithoutVersion(name, tag)
    val measurer = rememberTextMeasurer()
    Layout(
        modifier = modifier,
        content = {
            title(shown, Modifier)
            AlbumVersionTag(version = tag, style = tagStyle)
        },
    ) { measurables, constraints ->
        val gap = TagGap.roundToPx()
        // +1: the measured width is rounded down; a pixel less would ellipsize or scroll.
        val natural = measurer.measure(shown, titleStyle, maxLines = 1, softWrap = false).size.width + 1
        val tagMax = if (constraints.hasBoundedWidth) {
            versionTagMaxWidth(
                maxWidth = constraints.maxWidth,
                nameWidth = natural,
                tagWidth = measurables[1].maxIntrinsicWidth(constraints.maxHeight),
                gap = gap,
            )
        } else {
            Constraints.Infinity
        }
        val tagPlaceable = measurables[1].measure(Constraints(maxWidth = tagMax, maxHeight = constraints.maxHeight))
        val available = if (constraints.hasBoundedWidth) {
            (constraints.maxWidth - tagPlaceable.width - gap).coerceAtLeast(0)
        } else {
            natural
        }
        val titleWidth = min(natural, available)
        val titlePlaceable = measurables[0].measure(
            Constraints(minWidth = 0, maxWidth = titleWidth, minHeight = 0, maxHeight = constraints.maxHeight),
        )
        // A cut name ends on its ellipsis, short of the box it was given (a CJK glyph is wide): the tag
        // follows the ellipsis, not the box, or it floats off on its own (owner 2026-11-24, small tablet).
        val nameEnd = if (nameEllipsizes && titleWidth < natural) {
            val cut = measurer.measure(
                shown,
                titleStyle,
                overflow = TextOverflow.Ellipsis,
                maxLines = 1,
                softWrap = false,
                constraints = Constraints(maxWidth = titleWidth),
            )
            ceil(cut.getLineRight(0)).toInt().coerceIn(0, titlePlaceable.width)
        } else {
            titlePlaceable.width
        }
        val width = (nameEnd + gap + tagPlaceable.width).coerceIn(constraints.minWidth, constraints.maxWidth)
        val height = max(titlePlaceable.height, tagPlaceable.height).coerceAtLeast(constraints.minHeight)
        layout(width, height) {
            titlePlaceable.place(0, (height - titlePlaceable.height) / 2)
            tagPlaceable.place(nameEnd + gap, (height - tagPlaceable.height) / 2)
        }
    }
}

/**
 * How wide the version tag may be in a [maxWidth] row whose name is [nameWidth] wide and whose tag is
 * [tagWidth] whole. The name comes first: a long version ("Deluxe Edition") on a narrow card may take at
 * most what the name leaves, at least half the width, and is cut short itself rather than hide the name.
 * A short version ("全景声", "Live") that fits whole beside a third of the row stays whole — on an 82dp
 * shelf card the half rule cut both to one glyph ("你们… 全…", owner 2026-11-24), saying neither. Pure.
 */
internal fun versionTagMaxWidth(maxWidth: Int, nameWidth: Int, tagWidth: Int, gap: Int): Int {
    val wholeRoom = maxWidth - min(nameWidth, maxWidth / 3) - gap
    if (tagWidth <= wholeRoom) return tagWidth.coerceAtLeast(0)
    val nameRoom = min(nameWidth, maxWidth / 2)
    return (maxWidth - nameRoom - gap).coerceAtLeast(0)
}

private val TagGap = 6.dp

/**
 * The version as a small tonal block, like the format tags. [onBar] draws it
 * in white, for over the album page's spectrum bar.
 */
@Composable
fun AlbumVersionTag(
    version: String,
    modifier: Modifier = Modifier,
    style: TextStyle = MaterialTheme.typography.labelSmall,
    onBar: Boolean = false,
) {
    Surface(
        shape = RoundedCornerShape(6.dp),
        color = if (onBar) Color.White.copy(alpha = 0.18f) else MaterialTheme.colorScheme.secondaryContainer,
        contentColor = if (onBar) Color.White else MaterialTheme.colorScheme.onSecondaryContainer,
        modifier = modifier,
    ) {
        Text(
            text = version,
            style = style,
            maxLines = 1,
            softWrap = false,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 1.dp),
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun AlbumVersionTitlePreview() {
    YoinTheme {
        AlbumVersionTitle(
            name = "再生 (全景声)",
            version = "全景声",
            titleStyle = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(8.dp),
        ) { text, mod ->
            Text(text = text, style = MaterialTheme.typography.titleMedium, modifier = mod)
        }
    }
}
