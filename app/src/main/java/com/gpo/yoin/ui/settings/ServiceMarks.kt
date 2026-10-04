package com.gpo.yoin.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.gpo.yoin.symbols.YoinSymbolStrokeWidth
import com.gpo.yoin.ui.theme.YoinTheme

// Music services' marks redrawn in the Yoin Symbols hand (24 × 24 grid,
// 1.5 stroke, round caps and joins, circles on an r 8.5 centre line) so they
// sit with the rest of the icon set instead of pasting in brand artwork.
// Candidates for the Yoin Symbols library itself.

internal object ServiceMarks {

    /** Spotify: three arcs, widest on top, each leaning a little clockwise (no outer round). */
    val Spotify: ImageVector by lazy {
        mark("Spotify") {
            stroke("M4.16 7.76 C9.2 6 15.6 6.48 19.92 9.12")
            stroke("M5.04 12.48 C9.28 11.2 14.64 11.52 18.32 13.68")
            stroke("M5.92 17.12 C9.52 16.16 13.52 16.48 16.72 18.08")
        }
    }

    /** Apple Music: two eighth notes joined by a heavy slanted beam. */
    val AppleMusic: ImageVector by lazy {
        mark("AppleMusic") {
            // Note heads, on the same outline weight as MusicNote's.
            stroke("M7.55 14.75 C8.903 14.75 10 15.847 10 17.2 C10 18.553 8.903 19.65 7.55 19.65 C6.197 19.65 5.1 18.553 5.1 17.2 C5.1 15.847 6.197 14.75 7.55 14.75 Z")
            stroke("M16.05 12.95 C17.403 12.95 18.5 14.047 18.5 15.4 C18.5 16.753 17.403 17.85 16.05 17.85 C14.697 17.85 13.6 16.753 13.6 15.4 C13.6 14.047 14.697 12.95 16.05 12.95 Z")
            // Stems up to the beam.
            stroke("M10 17.2 L10 8.6")
            stroke("M18.5 15.4 L18.5 6.7")
            // The beam: filled, its stroke rounds the corners.
            fillStroke("M10 6.35 L18.5 4.45 L18.5 6.7 L10 8.6 Z")
        }
    }
}

private class MarkScope(private val builder: ImageVector.Builder) {
    private val ink = SolidColor(Color.Black)

    fun stroke(pathData: String) {
        builder.addPath(
            pathData = addPathNodes(pathData),
            fill = null,
            stroke = ink,
            strokeLineWidth = YoinSymbolStrokeWidth,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Round,
        )
    }

    fun fillStroke(pathData: String) {
        builder.addPath(
            pathData = addPathNodes(pathData),
            fill = ink,
            stroke = ink,
            strokeLineWidth = YoinSymbolStrokeWidth,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Round,
        )
    }
}

private fun mark(name: String, block: MarkScope.() -> Unit): ImageVector {
    val builder = ImageVector.Builder(
        name = "ServiceMarks.$name",
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f,
    )
    MarkScope(builder).block()
    return builder.build()
}

@Preview(showBackground = true)
@Composable
private fun ServiceMarksPreview() {
    YoinTheme {
        Row(modifier = Modifier.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Icon(ServiceMarks.Spotify, contentDescription = null, modifier = Modifier.size(48.dp))
            Icon(ServiceMarks.AppleMusic, contentDescription = null, modifier = Modifier.size(48.dp))
        }
    }
}
