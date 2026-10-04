package com.gpo.yoin.ui.memories.showcase

import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Constraints
import com.gpo.yoin.ui.component.MarqueeText

/** An album name's two lines when it needs more than two: the first line as it wraps, the rest in one line. */
internal data class TwoLineMarqueeSplit(val first: String, val rest: String)

/**
 * Where a name that wrapped into [lineCount] lines breaks for [TwoLineMarqueeText]: null when two lines hold
 * it; otherwise the first line (ending at [firstLineEnd], the layout's end of line 0) and everything after it.
 */
internal fun twoLineMarqueeSplit(text: String, lineCount: Int, firstLineEnd: Int): TwoLineMarqueeSplit? {
    if (lineCount <= 2) return null
    val cut = firstLineEnd.coerceIn(0, text.length)
    val first = text.substring(0, cut).trimEnd()
    val rest = text.substring(cut).trimStart()
    if (first.isEmpty() || rest.isEmpty()) return null
    return TwoLineMarqueeSplit(first, rest)
}

/**
 * An album name in at most two lines, never an ellipsis (owner, 2026-10-05): a name that fits two lines wraps
 * as usual; a longer one keeps its first line and runs the rest as a marquee on the second ([MarqueeText]:
 * it waits at rest, then scrolls with faded edges). [running] holds the marquee at its start (a neighbour
 * page mid-swipe). Centred, like every line on the card and the spread's left page. Reads as one name.
 */
@Composable
internal fun TwoLineMarqueeText(
    text: String,
    style: TextStyle,
    color: Color,
    modifier: Modifier = Modifier,
    running: Boolean = true,
) {
    BoxWithConstraints(modifier = modifier, contentAlignment = Alignment.TopCenter) {
        val measurer = rememberTextMeasurer(cacheSize = 4)
        val maxWidth = constraints.maxWidth
        val split = remember(text, style, maxWidth) {
            if (maxWidth == Constraints.Infinity || maxWidth <= 0) {
                null
            } else {
                val layout = measurer.measure(text, style, constraints = Constraints(maxWidth = maxWidth))
                twoLineMarqueeSplit(text, layout.lineCount, layout.getLineEnd(0))
            }
        }
        if (split == null) {
            Text(text = text, style = style, color = color, maxLines = 2)
        } else {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.clearAndSetSemantics { contentDescription = text },
            ) {
                Text(text = split.first, style = style, color = color, maxLines = 1, softWrap = false)
                MarqueeText(text = split.rest, style = style, color = color, running = running)
            }
        }
    }
}
