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
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.ContentScale
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
import kotlin.math.min
import kotlin.math.roundToInt
import kotlinx.coroutines.withTimeoutOrNull

class PlayWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = PlayWidget()
}

/**
 * Play — pick up recent listening. Neutral (wallpaper dynamic) colours; the asset shape tells what an item is
 * (covers rounded rect 4dp, artists circle); MaterialShapes are only containers / backdrops.
 *
 * Layout follows the real size (SizeMode.Exact): one row → Bun-lead strip (cover + play, title from 4 cells);
 * square → the 2×2 stack; wide and tall → hero + shelf as connected segments.
 */
class PlayWidget : GlanceAppWidget() {
    override val sizeMode: SizeMode = SizeMode.Exact

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val items = withTimeoutOrNull(WIDGET_DATA_TIMEOUT_MS) { WidgetData.playItems(context, limit = 6) }.orEmpty()
        val bitmaps = WidgetBitmaps(context)
        val sources = WidgetArt.loadAll(context, items.map { it.coverUrl }, bitmaps.px(112f))
        val palettes = sources.map { WidgetArt.palette(it) }
        provideContent {
            GlanceTheme {
                PlayContent(items, sources, palettes, bitmaps)
            }
        }
    }
}

internal const val WIDGET_DATA_TIMEOUT_MS = 10_000L

private const val STRIP_MAX_HEIGHT = 130
private const val SQUARE_ASPECT = 1.45f

@Composable
private fun PlayContent(items: List<PlayItem>, sources: List<Bitmap?>, palettes: List<WidgetPalette>, bitmaps: WidgetBitmaps) {
    val size = LocalSize.current
    if (items.isEmpty()) {
        WidgetEmpty(R.string.widget_play_empty)
        return
    }
    when {
        size.height.value < STRIP_MAX_HEIGHT -> PlayStrip(size, items.first(), sources.first(), bitmaps)
        size.width.value < size.height.value * SQUARE_ASPECT -> PlayStack(size, items, sources, palettes, bitmaps)
        else -> PlayShelf(size, items, sources, bitmaps)
    }
}

/** One row: Bun lead holding the cover, title from four cells, play on the right. */
@Composable
private fun PlayStrip(size: DpSize, item: PlayItem, source: Bitmap?, bitmaps: WidgetBitmaps) {
    val context = LocalContext.current
    val h = min(size.height.value, 112f).dp
    val cover = (h.value * 0.64f).coerceIn(44f, 72f).dp
    val button = (h.value * 0.72f).coerceIn(48f, 76f).dp
    val lead = bitmaps.of("lead:bun:${h.value}") { WidgetArt.leadMask(WidgetShapes.Bun, bitmaps.px(h.value)) }
    val leadW = (lead.width / bitmaps.scale).dp
    val placeholder = android.graphics.Color.GRAY
    val showText = size.width.value >= 240f
    Box(
        modifier = GlanceModifier.fillMaxSize().clickable(actionStartActivity(WidgetIntents.openDetail(context, item.entity, item.id))),
        contentAlignment = Alignment.CenterStart,
    ) {
        Row(modifier = GlanceModifier.fillMaxWidth().height(h)) {
            Spacer(modifier = GlanceModifier.width(leadW * WidgetShapes.leadJoin(WidgetShapes.Bun)))
            Box(modifier = GlanceModifier.defaultWeight().fillMaxHeight().roundedBackground(GlanceTheme.colors.widgetBackground, 24.dp)) {}
        }
        Image(
            provider = ImageProvider(lead),
            contentDescription = null,
            colorFilter = ColorFilter.tint(GlanceTheme.colors.widgetBackground),
            modifier = GlanceModifier.size(leadW, h),
        )
        Row(
            modifier = GlanceModifier.fillMaxWidth().height(h).padding(start = 16.dp, end = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Image(
                provider = ImageProvider(bitmaps.of("img:${item.id}:${cover.value}") { WidgetArt.entityImage(source, item.entity, bitmaps.px(cover.value), bitmaps.px(4f).toFloat(), placeholder) }),
                contentDescription = item.title,
                modifier = GlanceModifier.size(cover),
            )
            if (showText) {
                Spacer(modifier = GlanceModifier.width(14.dp))
                Column(modifier = GlanceModifier.defaultWeight()) {
                    Text(item.title, maxLines = 1, style = widgetText(GlanceTheme.colors.onSurface, 16.sp, FontWeight.Medium))
                    if (item.subtitle.isNotBlank()) {
                        Text(item.subtitle, maxLines = 1, style = widgetText(GlanceTheme.colors.onSurfaceVariant, 13.sp))
                    }
                }
            } else {
                Spacer(modifier = GlanceModifier.defaultWeight())
            }
            WidgetPlayButton(
                size = button,
                color = GlanceTheme.colors.primary,
                iconColor = GlanceTheme.colors.onPrimary,
                modifier = GlanceModifier.clickable(actionStartActivity(WidgetIntents.play(context, item))),
            )
        }
    }
}

/** 2×2: entity backdrops stacked, the resumable item in front; play in the cleared corner. */
@Composable
private fun PlayStack(size: DpSize, items: List<PlayItem>, sources: List<Bitmap?>, palettes: List<WidgetPalette>, bitmaps: WidgetBitmaps) {
    val context = LocalContext.current
    val s = min(size.width.value, size.height.value).dp
    val u = s.value / 184f
    val front = items.first()
    val art = bitmaps.of("stack:${s.value}") {
        WidgetArt.playStack(
            sizePx = bitmaps.px(s.value),
            items = items.take(3).mapIndexed { i, it -> WidgetArt.StackItem(it.entity, sources[i], palettes[i].base) },
            placeholder = android.graphics.Color.GRAY,
        )
    }
    Box(
        modifier = GlanceModifier.fillMaxSize()
            .roundedBackground(GlanceTheme.colors.widgetBackground, 36.dp)
            .clickable(actionStartActivity(WidgetIntents.openDetail(context, front.entity, front.id))),
        contentAlignment = Alignment.Center,
    ) {
        Box(modifier = GlanceModifier.size(s)) {
            Image(
                provider = ImageProvider(art),
                contentDescription = front.title,
                contentScale = ContentScale.Fit,
                modifier = GlanceModifier.fillMaxSize(),
            )
            Box(modifier = GlanceModifier.fillMaxSize().padding(start = (122 * u).dp, top = (122 * u).dp)) {
                WidgetPlayButton(
                    size = (52 * u).dp,
                    color = GlanceTheme.colors.primary,
                    iconColor = GlanceTheme.colors.onPrimary,
                    modifier = GlanceModifier.clickable(actionStartActivity(WidgetIntents.play(context, front))),
                )
            }
        }
    }
}

/** Wide and tall: hero (latest + play) over a shelf of the next ones — connected segments, seam radius 16. */
@Composable
private fun PlayShelf(size: DpSize, items: List<PlayItem>, sources: List<Bitmap?>, bitmaps: WidgetBitmaps) {
    val context = LocalContext.current
    val placeholder = android.graphics.Color.GRAY
    val heroH = (size.height.value * 0.54f).dp
    val heroCover = (heroH.value - 32f).coerceIn(56f, 96f).dp
    val shelfCover = ((size.height.value - heroH.value) - 28f).coerceIn(44f, 80f).dp
    val front = items.first()
    val shelfCount = ((size.width.value - 32f + 12f) / (shelfCover.value + 12f)).toInt().coerceIn(3, 7)
    val shelf = items.drop(1).take(shelfCount)
    Column(modifier = GlanceModifier.fillMaxSize()) {
        Row(
            modifier = GlanceModifier.fillMaxWidth().height(heroH)
                .shapeBackground(R.drawable.widget_segment_top, GlanceTheme.colors.widgetBackground)
                .padding(16.dp)
                .clickable(actionStartActivity(WidgetIntents.openDetail(context, front.entity, front.id))),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Image(
                provider = ImageProvider(bitmaps.of("img:${front.id}:${heroCover.value}") { WidgetArt.entityImage(sources.first(), front.entity, bitmaps.px(heroCover.value), bitmaps.px(4f).toFloat(), placeholder) }),
                contentDescription = front.title,
                modifier = GlanceModifier.size(heroCover),
            )
            Spacer(modifier = GlanceModifier.width(12.dp))
            Column(modifier = GlanceModifier.defaultWeight()) {
                Text(front.title, maxLines = 2, style = widgetText(GlanceTheme.colors.onSurface, 18.sp, FontWeight.Medium))
                if (front.subtitle.isNotBlank()) {
                    Text(front.subtitle, maxLines = 1, style = widgetText(GlanceTheme.colors.onSurfaceVariant, 13.sp))
                }
            }
            WidgetPlayButton(
                size = heroCover.coerceAtMost(72.dp),
                color = GlanceTheme.colors.primary,
                iconColor = GlanceTheme.colors.onPrimary,
                modifier = GlanceModifier.clickable(actionStartActivity(WidgetIntents.play(context, front))),
            )
        }
        Row(
            modifier = GlanceModifier.fillMaxWidth().defaultWeight()
                .shapeBackground(R.drawable.widget_segment_bottom, GlanceTheme.colors.secondaryContainer)
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            shelf.forEachIndexed { i, item ->
                if (i > 0) Spacer(modifier = GlanceModifier.defaultWeight())
                Image(
                    provider = ImageProvider(bitmaps.of("img:${item.id}:${shelfCover.value}") { WidgetArt.entityImage(sources[i + 1], item.entity, bitmaps.px(shelfCover.value), bitmaps.px(4f).toFloat(), placeholder) }),
                    contentDescription = item.title,
                    modifier = GlanceModifier.size(shelfCover)
                        .clickable(actionStartActivity(WidgetIntents.openDetail(context, item.entity, item.id))),
                )
            }
        }
    }
}

/** Nothing to show yet: the container and one label; tap opens the app. */
@Composable
internal fun WidgetEmpty(text: Int) {
    val context = LocalContext.current
    Box(
        modifier = GlanceModifier.fillMaxSize()
            .roundedBackground(GlanceTheme.colors.widgetBackground, 32.dp)
            .clickable(actionStartActivity(WidgetIntents.openApp(context))),
        contentAlignment = Alignment.Center,
    ) {
        Text(context.getString(text), style = widgetText(GlanceTheme.colors.onSurfaceVariant, 14.sp, FontWeight.Medium))
    }
}
