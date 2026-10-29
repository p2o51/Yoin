package com.gpo.yoin.ui.detail

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.toSize
import com.gpo.yoin.R
import com.gpo.yoin.ui.theme.withTabularFigures
import androidx.compose.ui.util.lerp
import kotlin.math.roundToInt

/**
 * The album page's page-0 → page-1 hand-off (owner 2026-10-29): the hero cover
 * and the Last Play time travel with the pager drag to their places on the
 * scrapbook page, instead of sliding away with page 0 and arriving again.
 *
 * Both ends report their resting rects here (page offset taken out, so the
 * values hold still while the pager moves); the flight is read in layout/draw
 * only, from [pageFraction]. Rects are in the album content box's coordinates.
 */
@Stable
internal class AlbumPageFlight(
    val pageFraction: () -> Float,
    private val pageWidthPx: Float,
    private val rtl: Boolean,
    // Compact/short hero showing (not pulled up into the bar): only then does
    // anything fly; otherwise page 0 slides away as before.
    val heroResting: () -> Boolean,
    // Tapping the hero's Last Play: to the scrapbook page, where it reads in full.
    val openScrapbook: () -> Unit,
) {
    var origin by mutableStateOf(Offset.Zero)
    var coverTarget by mutableStateOf<Rect?>(null)
    var coverTargetTilt by mutableFloatStateOf(0f)
    var lastPlaySource by mutableStateOf<Rect?>(null)
    var lastPlayTarget by mutableStateOf<Rect?>(null)

    /** Eased 0..1 page progress (0 = page 0 at rest). */
    fun t(): Float = pageFraction().coerceIn(0f, 1f)

    fun coverFlying(): Boolean = heroResting() && coverTarget != null
    fun lastPlayFlying(): Boolean = heroResting() && lastPlaySource != null && lastPlayTarget != null

    /** [coordinates] of a page-[page] node, as its rect at rest in content-box coordinates. */
    fun restRect(coordinates: LayoutCoordinates, page: Int): Rect {
        // Not boundsInRoot: that clips to the screen, and mid-swipe both pages are part off it.
        val bounds = Rect(coordinates.positionInRoot(), coordinates.size.toSize())
        val dir = if (rtl) -1f else 1f
        // Page 0 sits f pages to the start; page 1 (1 − f) pages to the end.
        val dx = if (page == 0) t() * pageWidthPx * dir else -(1f - t()) * pageWidthPx * dir
        return Rect(
            (bounds.left + dx - origin.x).roundToInt().toFloat(),
            (bounds.top - origin.y).roundToInt().toFloat(),
            (bounds.right + dx - origin.x).roundToInt().toFloat(),
            (bounds.bottom - origin.y).roundToInt().toFloat(),
        )
    }
}

/**
 * Page 0's hero text while its cover flies: gone a third of the way in, so it never
 * slides across the cover on its way to page 1. 1 when nothing flies.
 */
internal fun albumHeroPageFade(flight: AlbumPageFlight?): Float =
    if (flight == null || !flight.coverFlying()) 1f else (1f - flight.t() * 3.5f).coerceIn(0f, 1f)

internal val LocalAlbumPageFlight = staticCompositionLocalOf<AlbumPageFlight?> { null }

internal fun lerpRect(a: Rect, b: Rect, t: Float): Rect =
    Rect(lerp(a.left, b.left, t), lerp(a.top, b.top, t), lerp(a.right, b.right, t), lerp(a.bottom, b.bottom, t))

/** Records the content box's root offset, the frame every flight rect is in. */
internal fun Modifier.albumPageFlightOrigin(flight: AlbumPageFlight?): Modifier =
    if (flight == null) this else onGloballyPositioned { flight.origin = it.positionInRoot() }

/**
 * The flying "Yesterday 12:02": drawn over both pages while the pager moves,
 * from the phone hero's track-count line to its place under page 1's title,
 * growing from the line's size to the page-1 size on the way.
 */
@Composable
internal fun AlbumLastPlayFlight(
    flight: AlbumPageFlight,
    text: String,
    modifier: Modifier = Modifier,
) {
    Box(modifier) {
        Text(
            text = text,
            style = AlbumHeroLastPlayStyle(),
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            modifier = Modifier.graphicsLayer {
                val from = flight.lastPlaySource
                val to = flight.lastPlayTarget
                val t = flight.t()
                if (from == null || to == null || !flight.heroResting() || t <= 0.001f || t >= 0.999f) {
                    alpha = 0f
                    return@graphicsLayer
                }
                val r = lerpRect(from, to, t)
                val baseHeight = size.height.coerceAtLeast(1f)
                val scale = r.height / baseHeight
                transformOrigin = TransformOrigin(0f, 0f)
                translationX = r.left
                translationY = r.top
                scaleX = scale
                scaleY = scale
            },
        )
    }
}

@Composable
private fun AlbumHeroLastPlayStyle(): TextStyle =
    MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Medium).withTabularFigures()

/** "Yesterday 12:02" on the phone hero's track-count line: caption-less, day and time both in the line's ink. */
@Composable
internal fun AlbumHeroLastPlay(epochMillis: Long, interactive: Boolean, modifier: Modifier = Modifier) {
    val flight = LocalAlbumPageFlight.current
    val (day, time) = albumLastPlayLabels(epochMillis, LocalContext.current.resources)
    Text(
        text = "$day $time",
        style = AlbumHeroLastPlayStyle(),
        color = MaterialTheme.colorScheme.onSurface,
        maxLines = 1,
        softWrap = false,
        modifier = modifier
            .then(
                if (flight == null) {
                    Modifier
                } else {
                    Modifier
                        .onGloballyPositioned { flight.lastPlaySource = flight.restRect(it, page = 0) }
                        .graphicsLayer {
                            // Handed to the flight as soon as the pager moves.
                            alpha = if (flight.lastPlayFlying() && flight.t() > 0.001f) 0f else 1f
                        }
                        .clickable(enabled = interactive, role = Role.Button, onClick = flight.openScrapbook)
                },
            ),
    )
}

/** Last Play in full on the scrapbook page, under the Memory title: where the hero's time lands. */
@Composable
internal fun AlbumScrapLastPlay(epochMillis: Long?, modifier: Modifier = Modifier) {
    val flight = LocalAlbumPageFlight.current
    Column(modifier = modifier) {
        AlbumSectionLabel(
            text = stringResource(R.string.detail_album_last_play),
            modifier = Modifier.padding(bottom = 4.dp),
        )
        val text = epochMillis?.let { at ->
            val (day, time) = albumLastPlayLabels(at, LocalContext.current.resources)
            "$day $time"
        } ?: stringResource(R.string.detail_album_never)
        Text(
            text = text,
            style = MaterialTheme.typography.titleMedium.withTabularFigures(),
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            softWrap = false,
            modifier = if (flight == null || epochMillis == null) {
                Modifier
            } else {
                Modifier
                    .onGloballyPositioned { flight.lastPlayTarget = flight.restRect(it, page = 1) }
                    .graphicsLayer {
                        // Hidden while the flight carries it in.
                        alpha = if (flight.lastPlayFlying() && flight.t() < 0.999f) 0f else 1f
                    }
            },
        )
    }
}
