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
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.gpo.yoin.data.model.albumNameWithoutVersion
import com.gpo.yoin.ui.theme.YoinTheme
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
 * after it, not at the far end).
 */
@Composable
fun AlbumVersionTitle(
    name: String,
    version: String?,
    titleStyle: TextStyle,
    modifier: Modifier = Modifier,
    tagStyle: TextStyle = MaterialTheme.typography.labelSmall,
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
        val tagPlaceable = measurables[1].measure(constraints.copy(minWidth = 0, minHeight = 0))
        // +1: the measured width is rounded down; a pixel less would ellipsize or scroll.
        val natural = measurer.measure(shown, titleStyle, maxLines = 1, softWrap = false).size.width + 1
        val available = if (constraints.hasBoundedWidth) {
            (constraints.maxWidth - tagPlaceable.width - gap).coerceAtLeast(0)
        } else {
            natural
        }
        val titlePlaceable = measurables[0].measure(
            Constraints(minWidth = 0, maxWidth = min(natural, available), minHeight = 0, maxHeight = constraints.maxHeight),
        )
        val width = (titlePlaceable.width + gap + tagPlaceable.width).coerceIn(constraints.minWidth, constraints.maxWidth)
        val height = max(titlePlaceable.height, tagPlaceable.height).coerceAtLeast(constraints.minHeight)
        layout(width, height) {
            titlePlaceable.place(0, (height - titlePlaceable.height) / 2)
            tagPlaceable.place(titlePlaceable.width + gap, (height - tagPlaceable.height) / 2)
        }
    }
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
