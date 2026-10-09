package com.gpo.yoin.widget

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.os.Build
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialShapes
import androidx.compose.ui.graphics.toArgb
import androidx.core.graphics.ColorUtils
import androidx.core.graphics.createBitmap
import androidx.graphics.shapes.RoundedPolygon
import androidx.graphics.shapes.toPath
import coil3.SingletonImageLoader
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.request.allowHardware
import coil3.size.Size
import coil3.toBitmap
import com.gpo.yoin.ui.memories.showcase.MemoryPalette
import com.gpo.yoin.ui.memories.showcase.memoryCoverColors
import com.gpo.yoin.ui.theme.CoverSeedExtractor
import kotlin.math.max
import kotlin.math.roundToInt
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/** The shapes the widgets draw with — the app's own MaterialShapes, never hand-drawn look-alikes. */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
internal object WidgetShapes {
    val Bun: RoundedPolygon get() = MaterialShapes.Bun
    val Circle: RoundedPolygon get() = MaterialShapes.Circle
    val Ghostish: RoundedPolygon get() = MaterialShapes.Ghostish
    val Clover4Leaf: RoundedPolygon get() = MaterialShapes.Clover4Leaf
    val Cookie12: RoundedPolygon get() = MaterialShapes.Cookie12Sided

    /** Home's entity → backdrop mapping (HomeWidgetGrid): album Bun, playlist Ghostish, artist Circle. */
    fun backdropFor(entity: WidgetEntity): RoundedPolygon = when (entity) {
        WidgetEntity.ALBUM -> Bun
        WidgetEntity.PLAYLIST -> Ghostish
        WidgetEntity.ARTIST -> Circle
    }

    /**
     * Where a lead silhouette's body (the rounded rect) joins the shape, as a fraction of the shape's width:
     * the x of the shape's top / bottom extreme on its right half, so the seam is tangent (Figma v3).
     */
    fun leadJoin(shape: RoundedPolygon): Float = if (shape === Clover4Leaf) 0.70f else 0.5f
}

/** A cover's palette as plain ARGB ints (RemoteViews / Canvas), from the same extractor the deck uses. */
internal data class WidgetPalette(val base: Int, val accent: Int, val deep: Int, val soft: Int) {
    /** Light: the page tint. Dark: deep pulled toward black (Figma `cover/bg`). */
    val bgLight: Int get() = soft
    val bgDark: Int get() = ColorUtils.blendARGB(deep, Color.BLACK, 0.38f)
    val inkLight: Int get() = deep
    val inkDark: Int get() = soft
    val subLight: Int get() = ColorUtils.blendARGB(deep, soft, 0.3f)
    val subDark: Int get() = ColorUtils.blendARGB(soft, deep, 0.32f)

    companion object {
        /** Neutral fallback when a cover can't be read. */
        val Fallback = WidgetPalette(
            base = 0xFF6B5B95.toInt(),
            accent = 0xFFF1D9A7.toInt(),
            deep = 0xFF2C2540.toInt(),
            soft = 0xFFE9E2F2.toInt(),
        )
    }
}

/**
 * Bitmaps for one widget update, shared across every size the launcher asked for (SizeMode.Exact renders a layout
 * per size): RemoteViews dedups by instance, so the same art drawn once keeps the update under the binder /
 * widget bitmap budget.
 */
internal class WidgetBitmaps(context: Context) {
    /** Art never needs more than 2× — beyond that it only inflates the RemoteViews parcel. */
    val scale: Float = minOf(context.resources.displayMetrics.density, 2f)
    private val cache = HashMap<String, Bitmap>()

    fun px(dp: Float): Int = (dp * scale).roundToInt().coerceAtLeast(1)

    fun of(key: String, make: () -> Bitmap): Bitmap = cache.getOrPut(key, make)
}

internal object WidgetArt {

    suspend fun load(context: Context, url: String?, px: Int): Bitmap? {
        url ?: return null
        return try {
            withContext(Dispatchers.IO) {
                val request = ImageRequest.Builder(context)
                    .data(url)
                    .size(Size(px, px))
                    .allowHardware(false)
                    .build()
                (SingletonImageLoader.get(context).execute(request) as? SuccessResult)?.image?.toBitmap()
            }
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Exception) {
            null
        }
    }

    /** All covers at once, each given [perImageMs] — one slow server must not stall the widget. */
    suspend fun loadAll(context: Context, urls: List<String?>, px: Int, perImageMs: Long = 4_000L): List<Bitmap?> =
        coroutineScope {
            urls.map { url -> async { withTimeoutOrNull(perImageMs) { load(context, url, px) } } }.awaitAll()
        }

    fun palette(bitmap: Bitmap?): WidgetPalette {
        bitmap ?: return WidgetPalette.Fallback
        val colors = runCatching { memoryCoverColors(CoverSeedExtractor.palette(bitmap)) }.getOrNull()
            ?: return WidgetPalette.Fallback
        val p = MemoryPalette.fromBackdrop(colors.seed, colors.accent)
        return WidgetPalette(p.base.toArgb(), p.accent.toArgb(), p.deep.toArgb(), p.soft.toArgb())
    }

    /** Centre-cropped square image with a fixed corner radius (covers: Cover 4dp / Hero 8dp). */
    fun rounded(src: Bitmap?, sizePx: Int, radiusPx: Float, placeholder: Int): Bitmap {
        val out = createBitmap(sizePx, sizePx)
        val canvas = Canvas(out)
        val rect = RectF(0f, 0f, sizePx.toFloat(), sizePx.toFloat())
        canvas.drawRoundRect(rect, radiusPx, radiusPx, imagePaint(src, sizePx, placeholder))
        return out
    }

    /** Artists are always circles. */
    fun circle(src: Bitmap?, sizePx: Int, placeholder: Int): Bitmap {
        val out = createBitmap(sizePx, sizePx)
        val r = sizePx / 2f
        Canvas(out).drawCircle(r, r, r, imagePaint(src, sizePx, placeholder))
        return out
    }

    fun entityImage(src: Bitmap?, entity: WidgetEntity, sizePx: Int, radiusPx: Float, placeholder: Int): Bitmap =
        if (entity == WidgetEntity.ARTIST) circle(src, sizePx, placeholder) else rounded(src, sizePx, radiusPx, placeholder)

    private fun imagePaint(src: Bitmap?, sizePx: Int, placeholder: Int): Paint {
        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        if (src == null) {
            paint.color = placeholder
            return paint
        }
        val scale = max(sizePx / src.width.toFloat(), sizePx / src.height.toFloat())
        val dx = (sizePx - src.width * scale) / 2f
        val dy = (sizePx - src.height * scale) / 2f
        paint.shader = BitmapShader(src, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP).apply {
            setLocalMatrix(Matrix().apply { setScale(scale, scale); postTranslate(dx, dy) })
        }
        return paint
    }

    /** [shape] fitted (aspect kept, centred) into [rect] as an android Path. */
    fun path(shape: RoundedPolygon, rect: RectF): Path {
        val bounds = shape.calculateBounds()
        val w = bounds[2] - bounds[0]
        val h = bounds[3] - bounds[1]
        val s = minOf(rect.width() / w, rect.height() / h)
        val matrix = Matrix().apply {
            setTranslate(-bounds[0], -bounds[1])
            postScale(s, s)
            postTranslate(rect.left + (rect.width() - w * s) / 2f, rect.top + (rect.height() - h * s) / 2f)
        }
        return shape.toPath().apply { transform(matrix) }
    }

    /**
     * White mask of a lead silhouette's head: the shape at full height plus the body's start, so a rounded body
     * laid behind from [WidgetShapes.leadJoin] reads as one outline (the body's own left corners stay hidden).
     * Tinted at render time so it follows the theme.
     */
    fun leadMask(shape: RoundedPolygon, heightPx: Int): Bitmap {
        val probe = path(shape, RectF(0f, 0f, heightPx.toFloat(), heightPx.toFloat()))
        val b = RectF().also { probe.computeBounds(it, true) }
        // Re-fit so the shape's own height is the full widget height.
        val scale = heightPx / b.height()
        val w = (b.width() * scale).roundToInt()
        val out = createBitmap(w, heightPx, Bitmap.Config.ALPHA_8)
        val canvas = Canvas(out)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
        canvas.drawPath(path(shape, RectF(0f, 0f, w.toFloat(), heightPx.toFloat())), paint)
        val join = w * WidgetShapes.leadJoin(shape)
        canvas.drawRect(join, 0f, w.toFloat(), heightPx.toFloat(), paint)
        return out
    }

    /** White mask of any shape at [sizePx], for tinted dots / chips. */
    fun mask(shape: RoundedPolygon, sizePx: Int): Bitmap {
        val out = createBitmap(sizePx, sizePx, Bitmap.Config.ALPHA_8)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
        Canvas(out).drawPath(path(shape, RectF(0f, 0f, sizePx.toFloat(), sizePx.toFloat())), paint)
        return out
    }

    private fun typeface(bold: Boolean): Typeface {
        // Pixel ships Google Sans Flex as a named system family; elsewhere this falls back to sans-serif.
        val family = Typeface.create("google-sans-flex", Typeface.NORMAL)
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            Typeface.create(family, if (bold) 700 else 600, false)
        } else {
            Typeface.create(family, Typeface.BOLD)
        }
    }

    /**
     * The Cookie12 score badge: [center] text in the middle, and an optional tiny [ring] of text running round
     * inside the cookie (Memory 2×2). Colours are baked — the label accent reads the same in light and dark.
     */
    fun cookieBadge(
        sizePx: Int,
        fill: Int,
        ink: Int,
        center: String,
        centerSizePx: Float,
        ring: String? = null,
        ringSizePx: Float = 0f,
    ): Bitmap {
        val out = createBitmap(sizePx, sizePx)
        val canvas = Canvas(out)
        val s = sizePx.toFloat()
        canvas.drawPath(
            path(WidgetShapes.Cookie12, RectF(0f, 0f, s, s)),
            Paint(Paint.ANTI_ALIAS_FLAG).apply { color = fill },
        )
        val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = ink
            typeface = typeface(bold = true)
            textSize = centerSizePx
            textAlign = Paint.Align.CENTER
        }
        val lines = center.split('\n')
        val lh = centerSizePx * 1.05f
        val top = s / 2f - (lines.size - 1) * lh / 2f - (text.ascent() + text.descent()) / 2f
        lines.forEachIndexed { i, line -> canvas.drawText(line, s / 2f, top + i * lh, text) }
        if (!ring.isNullOrBlank()) {
            val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = ColorUtils.setAlphaComponent(ink, 205)
                typeface = typeface(bold = false)
                textSize = ringSizePx
                letterSpacing = 0.08f
            }
            val r = s * 0.345f
            val circle = Path().apply { addCircle(s / 2f, s / 2f, r, Path.Direction.CW) }
            // Start at 12 o'clock.
            canvas.save()
            canvas.rotate(-90f, s / 2f, s / 2f)
            canvas.drawTextOnPath(ring, circle, 0f, 0f, ringPaint)
            canvas.restore()
        }
        return out
    }

    /** One item of the Play 2×2 stack: its entity backdrop with the image on it (Home's cover stack grammar). */
    class StackItem(val entity: WidgetEntity, val image: Bitmap?, val backdrop: Int)

    /**
     * Play 2×2 art: the resumable item big and in front (bottom-left), the next two smaller behind it, mostly
     * visible (owner 2026-10-09: the first draft hid them too much). A transparent keyline is cut round each
     * front piece so the container colour (theme-tinted, drawn by the widget) separates the overlaps, and the
     * bottom-right corner stays clear for the play button.
     */
    fun playStack(sizePx: Int, items: List<StackItem>, placeholder: Int): Bitmap {
        val out = createBitmap(sizePx, sizePx)
        val canvas = Canvas(out)
        val u = sizePx / 184f
        // (x, y, box) in the 184-unit design grid; front last.
        val slots = listOf(
            Triple(10f, 8f, 76f),   // behind, top-left
            Triple(92f, 6f, 82f),   // behind, top-right
            Triple(14f, 78f, 98f),  // front: the resumable item
        )
        val order = listOf(items.getOrNull(1), items.getOrNull(2), items.getOrNull(0))
        val clear = Paint(Paint.ANTI_ALIAS_FLAG).apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.CLEAR) }
        order.forEachIndexed { i, item ->
            item ?: return@forEachIndexed
            val (x, y, box) = slots[i]
            val rect = RectF(x * u, y * u, (x + box) * u, (y + box) * u)
            val shape = WidgetShapes.backdropFor(item.entity)
            if (i > 0) {
                // Keyline: punch the outline (grown by 5 units) out of whatever is already drawn.
                val grow = 5f * u
                canvas.drawPath(path(shape, RectF(rect.left - grow, rect.top - grow, rect.right + grow, rect.bottom + grow)), clear)
            }
            canvas.drawPath(path(shape, rect), Paint(Paint.ANTI_ALIAS_FLAG).apply { color = item.backdrop })
            val imgPx = (box * 0.72f * u).roundToInt()
            val img = entityImage(item.image, item.entity, imgPx, 4f * u, placeholder)
            val (ix, iy) = if (item.entity == WidgetEntity.ARTIST) {
                rect.centerX() - imgPx / 2f to rect.centerY() - imgPx / 2f
            } else {
                rect.right - imgPx to rect.bottom - imgPx
            }
            canvas.drawBitmap(img, ix, iy, null)
        }
        // Room for the play button (52 + 5 keyline) in the bottom-right corner.
        val bx = 122f * u
        canvas.drawRoundRect(RectF(bx - 5 * u, bx - 5 * u, bx + 57 * u, bx + 57 * u), 25 * u, 25 * u, clear)
        return out
    }
}
