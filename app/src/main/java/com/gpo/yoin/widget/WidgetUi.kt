package com.gpo.yoin.widget

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.annotation.DrawableRes
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.glance.ColorFilter
import androidx.glance.GlanceModifier
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.appwidget.cornerRadius
import androidx.glance.background
import androidx.glance.color.ColorProvider
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.size
import androidx.glance.text.FontFamily
import androidx.glance.text.FontWeight
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import com.gpo.yoin.MainActivity
import com.gpo.yoin.R
import com.gpo.yoin.data.model.MediaId
import com.gpo.yoin.symbols.R as SymbolsR

/** Google Sans Flex where the system ships it (Pixel); sans-serif elsewhere. */
internal val WidgetFont = FontFamily("google-sans-flex")

internal fun widgetText(
    color: ColorProvider,
    size: TextUnit,
    weight: FontWeight = FontWeight.Normal,
): TextStyle = TextStyle(color = color, fontSize = size, fontWeight = weight, fontFamily = WidgetFont)

/** A palette-derived colour that switches with the system theme. */
internal fun dayNight(day: Int, night: Int): ColorProvider = ColorProvider(day = Color(day), night = Color(night))

/**
 * A filled rounded background. API 31+ clips natively; older launchers get a tinted shape drawable with the same
 * radius (the four sizes the widgets use).
 */
internal fun GlanceModifier.roundedBackground(color: ColorProvider, radius: Dp): GlanceModifier =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        background(color).cornerRadius(radius)
    } else {
        background(ImageProvider(roundedDrawable(radius)), colorFilter = ColorFilter.tint(color))
    }

/** A tinted shape drawable (for the connected segments, whose corners differ). */
internal fun GlanceModifier.shapeBackground(@DrawableRes shape: Int, color: ColorProvider): GlanceModifier =
    background(ImageProvider(shape), colorFilter = ColorFilter.tint(color))

@DrawableRes
private fun roundedDrawable(radius: Dp): Int = when {
    radius <= 24.dp -> R.drawable.widget_round_24
    radius <= 32.dp -> R.drawable.widget_round_32
    else -> R.drawable.widget_round_36
}

/** The Play button: a rounded square (corner = 24/64 of its size) in primary, the filled play symbol on it. */
@Composable
internal fun WidgetPlayButton(size: Dp, color: ColorProvider, iconColor: ColorProvider, modifier: GlanceModifier = GlanceModifier) {
    Box(
        modifier = modifier.size(size).shapeBackground(
            if (size >= 60.dp) R.drawable.widget_play_square else R.drawable.widget_play_square_small,
            color,
        ),
        contentAlignment = Alignment.Center,
    ) {
        Image(
            provider = ImageProvider(SymbolsR.drawable.ic_yoin_play_filled),
            contentDescription = null,
            colorFilter = ColorFilter.tint(iconColor),
            modifier = GlanceModifier.size(size * 0.44f),
        )
    }
}

/** Intents the widgets send. Every one carries a unique data URI so PendingIntents never collapse. */
internal object WidgetIntents {
    const val ACTION_OPEN_DETAIL = "com.gpo.yoin.widget.OPEN_DETAIL"
    const val ACTION_OPEN_MEMORY = "com.gpo.yoin.widget.OPEN_MEMORY"
    const val ACTION_OPEN_APP = "com.gpo.yoin.widget.OPEN_APP"
    const val EXTRA_ENTITY = "widget_entity"
    const val EXTRA_ID = "widget_id"
    const val EXTRA_SESSION = "widget_memory_session"

    fun openDetail(context: Context, entity: WidgetEntity, id: MediaId): Intent =
        Intent(ACTION_OPEN_DETAIL, Uri.parse("yoin-widget://detail/${entity.name}/${Uri.encode(id.toString())}"))
            .setComponent(ComponentName(context, MainActivity::class.java))
            .putExtra(EXTRA_ENTITY, entity.name)
            .putExtra(EXTRA_ID, id.toString())
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)

    fun openMemory(context: Context, sessionId: Long): Intent =
        Intent(ACTION_OPEN_MEMORY, Uri.parse("yoin-widget://memory/$sessionId"))
            .setComponent(ComponentName(context, MainActivity::class.java))
            .putExtra(EXTRA_SESSION, sessionId)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)

    fun openApp(context: Context): Intent =
        Intent(ACTION_OPEN_APP, Uri.parse("yoin-widget://app"))
            .setComponent(ComponentName(context, MainActivity::class.java))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    fun play(context: Context, item: PlayItem): Intent =
        Intent(context, WidgetPlayActivity::class.java)
            .setData(Uri.parse("yoin-widget://play/${item.entity.name}/${Uri.encode(item.id.toString())}"))
            .putExtra(EXTRA_ENTITY, item.entity.name)
            .putExtra(EXTRA_ID, item.id.toString())
            .putExtra(WidgetPlayActivity.EXTRA_TITLE, item.title)
            .putExtra(WidgetPlayActivity.EXTRA_SUBTITLE, item.subtitle)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION)
}
