package com.gpo.yoin.widget

import android.content.Context
import android.graphics.Bitmap
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.ColorFilter
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.LocalContext
import androidx.glance.LocalSize
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxHeight
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.layout.width
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import com.gpo.yoin.R
import java.util.Locale
import kotlin.math.min
import kotlin.math.roundToInt
import kotlinx.coroutines.withTimeoutOrNull

class MemoryWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = MemoryWidget()
}

/**
 * Memory — your album memories. Score and time since you last heard it lead every size; album + artist join
 * from four cells. One memory wears its album's palette; the timeline (wide sizes) sits on the neutral surface,
 * every cover the same size, a Cookie12 score chip only when the album is rated.
 */
class MemoryWidget : GlanceAppWidget() {
    override val sizeMode: SizeMode = SizeMode.Exact

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val items = withTimeoutOrNull(WIDGET_DATA_TIMEOUT_MS) { WidgetData.memoryItems(context, limit = 12) }.orEmpty()
        val bitmaps = WidgetBitmaps(context)
        val sources = WidgetArt.loadAll(context, items.map { it.coverUrl }, bitmaps.px(128f))
        val palettes = sources.map { WidgetArt.palette(it) }
        val now = System.currentTimeMillis()
        val lastHeard = items.map { context.lastHeardText(it.lastHeardAt, now) }
        provideContent {
            GlanceTheme {
                MemoryContent(items, sources, palettes, lastHeard, bitmaps)
            }
        }
    }
}

private const val STRIP_MAX_HEIGHT = 130
private const val SQUARE_ASPECT = 1.45f
private const val TWO_ROWS_MIN_HEIGHT = 290

@Composable
private fun MemoryContent(items: List<MemoryItem>, sources: List<Bitmap?>, palettes: List<WidgetPalette>, lastHeard: List<String?>, bitmaps: WidgetBitmaps) {
    val size = LocalSize.current
    if (items.isEmpty()) {
        WidgetEmpty(R.string.widget_memory_empty)
        return
    }
    when {
        size.height.value < STRIP_MAX_HEIGHT -> MemoryStrip(size, items.first(), sources.first(), palettes.first(), lastHeard.first(), bitmaps)
        size.width.value < size.height.value * SQUARE_ASPECT -> MemoryAnchor(size, items.first(), sources.first(), palettes.first(), lastHeard.first(), bitmaps)
        else -> MemoryTimeline(size, items, sources, palettes, lastHeard, bitmaps)
    }
}

/** One row: Clover lead holding the cover; score + time; album + artist from four cells. */
@Composable
private fun MemoryStrip(size: DpSize, item: MemoryItem, source: Bitmap?, palette: WidgetPalette, lastHeard: String?, bitmaps: WidgetBitmaps) {
    val context = LocalContext.current
    val h = min(size.height.value, 112f).dp
    val cover = (h.value * 0.6f).coerceIn(44f, 68f).dp
    val shape = WidgetShapes.Clover4Leaf
    val lead = bitmaps.of("lead:clover:${h.value}") { WidgetArt.leadMask(shape, bitmaps.px(h.value)) }
    val leadW = (lead.width / bitmaps.scale).dp
    val bg = dayNight(palette.bgLight, palette.bgDark)
    val accentInk = dayNight(palette.base, palette.base)
    val ink = dayNight(palette.inkLight, palette.inkDark)
    val sub = dayNight(palette.subLight, palette.subDark)
    val showAlbum = size.width.value >= 320f
    Box(
        modifier = GlanceModifier.fillMaxSize().clickable(actionStartActivity(WidgetIntents.openMemory(context, item.sessionId))),
        contentAlignment = Alignment.CenterStart,
    ) {
        Row(modifier = GlanceModifier.fillMaxWidth().height(h)) {
            Spacer(modifier = GlanceModifier.width(leadW * WidgetShapes.leadJoin(shape)))
            Box(modifier = GlanceModifier.defaultWeight().fillMaxHeight().roundedBackground(bg, 24.dp)) {}
        }
        Image(
            provider = ImageProvider(lead),
            contentDescription = null,
            colorFilter = ColorFilter.tint(bg),
            modifier = GlanceModifier.size(leadW, h),
        )
        Row(
            modifier = GlanceModifier.fillMaxWidth().height(h).padding(start = ((leadW.value - cover.value) / 2f).coerceAtLeast(12f).dp, end = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Image(
                provider = ImageProvider(bitmaps.of("cover:${item.sessionId}:${cover.value}") { WidgetArt.rounded(source, bitmaps.px(cover.value), bitmaps.px(4f).toFloat(), palette.base) }),
                contentDescription = item.albumName,
                modifier = GlanceModifier.size(cover),
            )
            Spacer(modifier = GlanceModifier.width(14.dp))
            Column(modifier = if (showAlbum) GlanceModifier else GlanceModifier.defaultWeight()) {
                val score = item.score
                if (score != null) {
                    Text(score.widgetScoreText(), maxLines = 1, style = widgetText(accentInk, 28.sp, FontWeight.Bold))
                    if (lastHeard != null) Text(lastHeard, maxLines = 1, style = widgetText(accentInk, 12.sp, FontWeight.Medium))
                } else {
                    Text(lastHeard ?: item.albumName, maxLines = 2, style = widgetText(accentInk, 16.sp, FontWeight.Bold))
                }
            }
            if (showAlbum) {
                Spacer(modifier = GlanceModifier.width(18.dp))
                Column(modifier = GlanceModifier.defaultWeight()) {
                    Text(item.albumName, maxLines = 1, style = widgetText(ink, 15.sp, FontWeight.Medium))
                    item.artistName?.takeIf { it.isNotBlank() }?.let {
                        Text(it, maxLines = 1, style = widgetText(sub, 12.sp))
                    }
                }
            }
        }
    }
}

/** 2×2 anchor: a big cover bottom-left, the Cookie12 score badge top-right biting its corner. Transparent. */
@Composable
private fun MemoryAnchor(size: DpSize, item: MemoryItem, source: Bitmap?, palette: WidgetPalette, lastHeard: String?, bitmaps: WidgetBitmaps) {
    val context = LocalContext.current
    val s = min(size.width.value, size.height.value).dp
    val u = s.value / 184f
    val coverDp = (120 * u).dp
    val badgeDp = (110 * u).dp
    val title = item.generatedTitle ?: item.albumName
    val score = item.score
    val badge = bitmaps.of("badge:${item.sessionId}:${badgeDp.value}") { WidgetArt.cookieBadge(
        sizePx = bitmaps.px(badgeDp.value),
        fill = palette.accent,
        ink = palette.deep,
        center = score?.widgetScoreText() ?: (lastHeard ?: "").uppercase(Locale.getDefault()).replace(' ', '\n').take(24),
        centerSizePx = (if (score != null) 30f else 12f) * u * bitmaps.scale,
        ring = listOfNotNull(if (score != null) lastHeard else null, title)
            .joinToString(separator = "  •  ", postfix = "  •  ")
            .uppercase(Locale.getDefault()),
        ringSizePx = 8f * u * bitmaps.scale,
    ) }
    Box(
        modifier = GlanceModifier.fillMaxSize().clickable(actionStartActivity(WidgetIntents.openMemory(context, item.sessionId))),
        contentAlignment = Alignment.Center,
    ) {
        Box(modifier = GlanceModifier.size(s)) {
            Box(modifier = GlanceModifier.fillMaxSize().padding(top = (64 * u).dp)) {
                Image(
                    provider = ImageProvider(bitmaps.of("cover:${item.sessionId}:${coverDp.value}") { WidgetArt.rounded(source, bitmaps.px(coverDp.value), bitmaps.px(4f).toFloat(), palette.base) }),
                    contentDescription = item.albumName,
                    modifier = GlanceModifier.size(coverDp),
                )
            }
            Box(modifier = GlanceModifier.fillMaxSize().padding(start = (74 * u).dp)) {
                Image(
                    provider = ImageProvider(badge),
                    contentDescription = listOfNotNull(score?.widgetScoreText(), lastHeard, title).joinToString(", "),
                    modifier = GlanceModifier.size(badgeDp),
                )
            }
        }
    }
}

/** Wide: memories on a time axis, newest first; two straight rows when tall (both left → right). */
@Composable
private fun MemoryTimeline(size: DpSize, items: List<MemoryItem>, sources: List<Bitmap?>, palettes: List<WidgetPalette>, lastHeard: List<String?>, bitmaps: WidgetBitmaps) {
    val context = LocalContext.current
    val cover = 64.dp
    val itemW = cover + 14.dp
    val perRow = ((size.width.value - 32f + 12f) / (itemW.value + 12f)).toInt().coerceIn(2, 7)
    val rows = if (size.height.value >= TWO_ROWS_MIN_HEIGHT) 2 else 1
    val dot = bitmaps.of("dot") { WidgetArt.mask(WidgetShapes.Circle, bitmaps.px(8f)) }
    Column(
        modifier = GlanceModifier.fillMaxSize().roundedBackground(GlanceTheme.colors.widgetBackground, 32.dp).padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        for (r in 0 until rows) {
            val slice = items.drop(r * perRow).take(perRow)
            if (slice.isEmpty()) break
            if (r > 0) Spacer(modifier = GlanceModifier.height(20.dp))
            Box(modifier = GlanceModifier.fillMaxWidth()) {
                // The axis, through the ticks.
                Column(modifier = GlanceModifier.fillMaxWidth()) {
                    Spacer(modifier = GlanceModifier.height(cover + 12.dp + 8.dp + 3.dp))
                    Box(modifier = GlanceModifier.fillMaxWidth().height(2.dp).background(GlanceTheme.colors.outline)) {}
                }
                Row(modifier = GlanceModifier.fillMaxWidth()) {
                    slice.forEachIndexed { i, item ->
                        val index = r * perRow + i
                        if (i > 0) Spacer(modifier = GlanceModifier.defaultWeight())
                        TimelineItem(item, sources[index], palettes[index], lastHeard[index], cover, itemW, dot, bitmaps)
                    }
                    if (slice.size < perRow) Spacer(modifier = GlanceModifier.defaultWeight())
                }
            }
        }
    }
}

@Composable
private fun TimelineItem(item: MemoryItem, source: Bitmap?, palette: WidgetPalette, lastHeard: String?, cover: Dp, itemW: Dp, dot: Bitmap, bitmaps: WidgetBitmaps) {
    val context = LocalContext.current
    val chip = 34.dp
    Column(
        modifier = GlanceModifier.width(itemW).clickable(actionStartActivity(WidgetIntents.openMemory(context, item.sessionId))),
    ) {
        Box(modifier = GlanceModifier.size(itemW, cover + 12.dp)) {
            Box(modifier = GlanceModifier.fillMaxSize().padding(top = 12.dp)) {
                Image(
                    provider = ImageProvider(bitmaps.of("cover:${item.sessionId}:${cover.value}") { WidgetArt.rounded(source, bitmaps.px(cover.value), bitmaps.px(4f).toFloat(), palette.base) }),
                    contentDescription = item.albumName,
                    modifier = GlanceModifier.size(cover),
                )
            }
            val score = item.score
            if (score != null) {
                Box(modifier = GlanceModifier.fillMaxSize().padding(start = itemW - chip)) {
                    Image(
                        provider = ImageProvider(
                            bitmaps.of("chip:${item.sessionId}") {
                                WidgetArt.cookieBadge(bitmaps.px(chip.value), palette.accent, palette.deep, score.widgetScoreText(), 13 * bitmaps.scale)
                            },
                        ),
                        contentDescription = score.widgetScoreText(),
                        modifier = GlanceModifier.size(chip),
                    )
                }
            }
        }
        Spacer(modifier = GlanceModifier.height(8.dp))
        Image(
            provider = ImageProvider(dot),
            contentDescription = null,
            colorFilter = ColorFilter.tint(GlanceTheme.colors.onSurface),
            modifier = GlanceModifier.size(8.dp),
        )
        Spacer(modifier = GlanceModifier.height(8.dp))
        Text(lastHeard ?: "", maxLines = 2, style = widgetText(GlanceTheme.colors.onSurfaceVariant, 12.sp, FontWeight.Medium))
    }
}
