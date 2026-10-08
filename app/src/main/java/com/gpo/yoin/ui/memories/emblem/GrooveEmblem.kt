package com.gpo.yoin.ui.memories.emblem

import android.content.res.Configuration
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Matrix
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.PathMeasure
import androidx.compose.ui.graphics.PointMode
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asComposePath
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.graphics.shapes.toPath
import com.gpo.yoin.R
import com.gpo.yoin.ui.memories.showcase.MemoryPalette
import com.gpo.yoin.ui.memories.showcase.MemoryPaletteSamples
import com.gpo.yoin.ui.theme.YoinTheme
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/*
 * 唱片刻纹 · Groove — the Memory emblem (owner, 2026-10-03; v4 2026-10-04: flat Material, nothing glossy).
 * One ring per track, outer = track 1; only rated tracks are cut (solid, flat palette colour); unrated tracks
 * stay a dotted pre-cut lattice. The centre label carries the score: album = filled Cookie12Sided, average =
 * outlined circle on a paler disc, unrated = a dashed Cookie12Sided "empty mould" on neutral tokens.
 * Tilt is read as a flat colour shift of the outer ring lines; no gloss, sheen, glow, shadow or note dots.
 * It is an exhibit: not clickable.
 */

/** Tilt colour: the outer ring lines are split into 15° flat segments. */
private const val TintSegments = 24
private const val TintStep = 360f / TintSegments

/** Weights of the tilt colour: the rim answers most, then the two outer cut rings. */
private const val RimTintWeight = 1f
private const val HairlineTintWeight = 0.6f
private val RingTintWeights = floatArrayOf(0.85f, 0.55f)

/**
 * The groove emblem of one memory, drawn natively at [size].
 *
 * @param tilt the device tilt in −1…1 screen axes (see [rememberGrooveTilt]); read only in draw.
 * @param award a running award's channels (see [rememberGrooveAwardState]); null draws the resting emblem.
 *   The unrated mould is static: its 4.8 s ripple loop is gone (owner, 2026-10-05).
 * @param captionAlpha the caption's own alpha (the card ⇄ diary morph fades it first, so it never shrinks
 *   into noise); read only in draw.
 */
@Composable
fun GrooveEmblem(
    model: GrooveModel,
    size: Dp,
    surface: GrooveSurface,
    modifier: Modifier = Modifier,
    tilt: () -> Offset = { Offset.Zero },
    award: GrooveAwardChannels? = null,
    captionAlpha: () -> Float = { 1f },
) {
    val neutrals = GrooveNeutrals.current
    val geometry = remember(model.trackRated, model.kind, size, surface) {
        grooveGeometry(model.trackRated, size.value.toDouble(), model.kind, surface)
    }
    val colors = remember(model.palette, model.kind, neutrals, surface) {
        grooveColors(model.palette, model.kind, neutrals.dark, surface, neutrals)
    }
    val unratedWord = stringResource(R.string.mem_emblem_unrated)
    val description = grooveContentDescription(model)
    val textMeasurer = rememberTextMeasurer(cacheSize = 4)
    val layer = if (award != null) {
        Modifier.graphicsLayer {
            val s = award.emblemScale
            scaleX = s
            scaleY = s
            alpha = award.emblemAlpha
        }
    } else {
        Modifier
    }
    Spacer(
        modifier = modifier
            .size(size)
            .semantics {
                contentDescription = description
                role = Role.Image
            }
            .then(layer)
            .drawWithCache {
                val art = GrooveArt(this.size, density, geometry, colors, model, textMeasurer, unratedWord)
                onDrawBehind { art.draw(this, tilt(), award, captionAlpha()) }
            },
    )
}

@Composable
private fun grooveContentDescription(model: GrooveModel): String = when {
    model.kind == GrooveKind.Unrated || model.score == null -> stringResource(R.string.mem_cd_emblem_unrated)
    model.kind == GrooveKind.Album -> stringResource(R.string.mem_cd_emblem_album, model.scoreText)
    else -> stringResource(R.string.mem_cd_emblem_average, model.scoreText)
}

/** Everything that depends only on size and model, built once per cache (paths, dot lattices, type). */
private class GrooveArt(
    drawSize: Size,
    density: Float,
    private val g: GrooveGeometry,
    private val c: GrooveColors,
    model: GrooveModel,
    textMeasurer: TextMeasurer,
    unratedWord: String,
) {
    /** px per dp, from the actual drawing size (so it matches layout exactly). */
    private val k: Float
    private val center: Offset

    /** The caption's alpha this frame (set by [draw]). */
    private var captionAlpha = 1f
    private val kind = model.kind
    private val dots: List<List<Offset>>
    private val labelPath: Path?
    private val labelRingPath: Path?
    private val mouldPath: Path?
    private val mouldDash: PathEffect?
    private val scoreText: TextLayoutResult?
    private val captionText: TextLayoutResult?
    private val unratedText: TextLayoutResult?
    private val cutTint = if (kind != GrooveKind.Unrated) c.tint(c.cut) else null
    private val rimTint = if (kind != GrooveKind.Unrated) c.tint(c.rim) else c.tint(c.hairlineStroke)

    init {
        k = if (drawSize.width > 0f) drawSize.width / g.size.toFloat() else density
        center = Offset(g.radius.toFloat() * k, g.radius.toFloat() * k)
        dots = g.rings.map { ring ->
            val n = g.dotCount(ring)
            val phase = if (ring.index % 2 == 1) 0.5 else 0.0
            val r = ring.radius.toFloat() * k
            List(n) { i ->
                val a = (i + phase) * 2 * PI / n
                Offset(center.x + r * cos(a).toFloat(), center.y + r * sin(a).toFloat())
            }
        }
        val l = g.labelRadius.toFloat()
        labelPath = if (kind == GrooveKind.Album) cookie(l) else null
        labelRingPath = if (kind == GrooveKind.Album && g.big) cookie(l * 0.94f) else null
        if (kind == GrooveKind.Unrated) {
            val w = g.unratedLabelStroke.toFloat()
            val path = cookie(l - w / 2)
            mouldPath = path
            // 24 dashes around the mould (two per lobe), the first starting on the top lobe's peak
            val measure = PathMeasure().apply { setPath(path, false) }
            val per = measure.length / 24f
            mouldDash = PathEffect.dashPathEffect(floatArrayOf(per * 0.55f, per * 0.45f), dashPhase(measure, per))
        } else {
            mouldPath = null
            mouldDash = null
        }
        val unitDensity = Density(k, 1f)
        fun measure(text: String, family: Pair<FontFamily, FontWeight>, sizeDp: Double): TextLayoutResult =
            textMeasurer.measure(
                text = text,
                style = TextStyle(
                    fontFamily = family.first,
                    fontWeight = family.second,
                    fontSize = sizeDp.sp,
                    letterSpacing = 0.sp,
                    platformStyle = PlatformTextStyle(includeFontPadding = false),
                ),
                softWrap = false,
                maxLines = 1,
                density = unitDensity,
            )
        if (kind != GrooveKind.Unrated) {
            val text = model.scoreText
            scoreText = measure(text, GrooveType.score(kind, text.length >= 4, g.scoreFontSize), g.scoreFontSize)
            // only a track average says what it is ("Avg."); an album score stands alone (owner, 2026-10-05)
            captionText = if (g.showsCaption) {
                measure("Avg.", GrooveType.caption(g.captionFontSize), g.captionFontSize)
            } else {
                null
            }
            unratedText = null
        } else {
            scoreText = null
            captionText = null
            unratedText = if (g.showsUnratedWord) {
                measure(unratedWord, GrooveType.unratedWord(g.unratedWidthAxis, g.unratedFontSize), g.unratedFontSize)
            } else {
                null
            }
        }
    }

    /** MaterialShapes.Cookie12Sided scaled so its outer (lobe) radius is [radiusDp], centred. */
    private fun cookie(radiusDp: Float): Path {
        val unit = CookieUnit.path
        val scale = radiusDp * k / CookieUnit.outerRadius
        val m = Matrix()
        m.translate(center.x - CookieUnit.center.x * scale, center.y - CookieUnit.center.y * scale)
        m.scale(scale, scale)
        return Path().apply {
            addPath(unit)
            transform(m)
        }
    }

    /** Dash phase that puts a dash start on the lobe peak nearest the path's start. */
    private fun dashPhase(measure: PathMeasure, per: Float): Float {
        var bestD = 0f
        var bestR = -1f
        val steps = 64
        for (i in 0..steps) {
            val d = per * 2f * i / steps // the first lobe lies within the first two dash periods
            val p = measure.getPosition(d)
            val r = hypot(p.x - center.x, p.y - center.y)
            if (r > bestR) {
                bestR = r
                bestD = d
            }
        }
        return (per - bestD % per) % per
    }

    fun draw(
        scope: DrawScope,
        tiltNow: Offset,
        award: GrooveAwardChannels?,
        captionAlpha: Float = 1f,
    ) = with(scope) {
        this@GrooveArt.captionAlpha = captionAlpha.coerceIn(0f, 1f)
        val disc = award?.discDegrees ?: 0f
        val tv = grooveTiltVars(tiltNow.x.toDouble(), tiltNow.y.toDouble())
        val tintAlpha = award?.tintAlpha ?: 1f
        val tm = (tv.tm * tintAlpha).toFloat()
        val ta = tv.ta.toFloat()
        val unrated = kind == GrooveKind.Unrated

        // 1 · ground: opaque on a cover so the silhouette separates from any artwork (flat, no shadow)
        drawCircle(c.ground, radius = g.rimRadius.toFloat() * k, center = center)

        // 2 · disc space (turns in the award)
        rotate(disc, pivot = center) {
            if (!unrated) {
                val underStroke = Stroke(width = g.underWidth.toFloat() * k)
                g.rings.forEach { ring ->
                    ring.runs.forEach { run ->
                        arc(
                            c.under,
                            ring.radius,
                            run.startDegrees,
                            run.sweepDegrees,
                            underStroke,
                        )
                    }
                }
            }
            val dotColor = if (unrated && g.tiny) c.tinyDot else c.dot
            dots.forEach {
                drawPoints(
                    it,
                    PointMode.Points,
                    dotColor,
                    strokeWidth = g.dotDiameter.toFloat() * k,
                    cap = StrokeCap.Round,
                )
            }
            if (!unrated) {
                val cutStroke = Stroke(width = g.cutWidth.toFloat() * k, cap = StrokeCap.Butt)
                g.rings.forEach { ring ->
                    if (ring.runs.isEmpty()) return@forEach
                    val order = g.ringOrder(ring.index)
                    val progress = award?.cutProgress(order) ?: 1f
                    val alpha = award?.cutAlpha(order) ?: 1f
                    if (progress <= 0f || alpha <= 0f) return@forEach
                    ring.runs.forEach { run ->
                        arc(
                            c.cut,
                            ring.radius,
                            run.startDegrees,
                            run.sweepDegrees * progress,
                            cutStroke,
                            alpha,
                        )
                    }
                    if (ring.index < RingTintWeights.size && tm > 0.001f && cutTint != null) {
                        val revealed = ring.runs.map {
                            it.startDegrees.toFloat() to (it.startDegrees + it.sweepDegrees * progress).toFloat()
                        }
                        tintRing(ring.radius, cutStroke, cutTint, RingTintWeights[ring.index] * alpha, ta, tm, revealed)
                    }
                }
                if (g.hasLitLayers && award != null) {
                    g.rings.forEach { ring ->
                        val glow = award.cutGlow(g.ringOrder(ring.index))
                        if (glow > 0.002f) {
                            ring.runs.forEach { run ->
                                arc(
                                    c.lit,
                                    ring.radius,
                                    run.startDegrees,
                                    run.sweepDegrees,
                                    cutStroke,
                                    glow,
                                )
                            }
                        }
                        val whole = award.ringGlow(ring.index)
                        if (whole > 0.002f) {
                            drawCircle(
                                c.lit,
                                ring.radius.toFloat() * k,
                                center,
                                alpha = whole.coerceAtMost(1f),
                                style = cutStroke,
                            )
                        }
                    }
                }
            }
        }

        // 3 · rim: album = accent double rim, average = single palette rim, unrated = hairline only on a cover
        val rimR = g.rimRadius.toFloat()
        when (kind) {
            GrooveKind.Album, GrooveKind.Average -> {
                val w = g.rimWidth.toFloat()
                val stroke = Stroke(width = w * k)
                drawCircle(c.rim, (rimR - w / 2) * k, center, style = stroke)
                if (tm > 0.001f) tintRing((rimR - w / 2).toDouble(), stroke, rimTint, RimTintWeight, ta, tm, null)
                if (kind == GrooveKind.Album && !g.tiny) {
                    drawCircle(
                        c.rim2,
                        (rimR - g.rim2At.toFloat()) * k,
                        center,
                        style = Stroke(width = g.rim2Width.toFloat() * k),
                    )
                }
            }
            GrooveKind.Unrated -> if (g.surface == GrooveSurface.Cover) {
                val stroke = Stroke(width = 0.8f * k)
                drawCircle(c.hairline, (rimR - 0.4f) * k, center, style = stroke)
                if (!g.tiny && tm > 0.001f) {
                    tintRing(
                        (rimR - 0.4f).toDouble(),
                        stroke,
                        rimTint,
                        HairlineTintWeight,
                        ta,
                        tm,
                        null,
                    )
                }
            }
        }

        // 3b · the 10.0 moment: the rim pulses in flat accent and lets one thin ring go
        if (g.hasLitLayers && award != null) {
            val flare = award.flare
            if (flare > 0.002f) {
                val fw = g.flareWidth.toFloat()
                drawCircle(
                    c.flare,
                    (rimR - fw / 2) * k,
                    center,
                    alpha = flare.coerceAtMost(1f),
                    style = Stroke(width = fw * k),
                )
            }
            val burst = award.burstAlpha
            if (burst > 0.002f) {
                scale(award.burstScale, pivot = center) {
                    drawCircle(
                        c.flare,
                        rimR * k,
                        center,
                        alpha = burst.coerceAtMost(1f),
                        style = Stroke(width = g.burstWidth.toFloat() * k),
                    )
                }
            }
        }

        // 4 · centre label (flat fills)
        val labelAlpha = (award?.labelAlpha ?: 1f).coerceIn(0f, 1f)
        if (labelAlpha > 0f) {
            scale(award?.labelScale ?: 1f, pivot = center) {
                drawLabel(disc, labelAlpha)
            }
        }
    }

    private fun DrawScope.drawLabel(disc: Float, alpha: Float) {
        val l = g.labelRadius.toFloat()
        when (kind) {
            GrooveKind.Album -> rotate(disc, pivot = center) {
                labelPath?.let { drawPath(it, c.label, alpha = alpha) }
                labelRingPath?.let {
                    drawPath(
                        it,
                        c.labelRing,
                        alpha = 0.9f * alpha,
                        style = Stroke(width = g.albumLabelRingStroke.toFloat() * k),
                    )
                }
            }
            GrooveKind.Average -> {
                val w = g.averageLabelStroke.toFloat()
                drawCircle(c.labelFill, (l - w / 2) * k, center, alpha = alpha)
                drawCircle(c.labelStroke, (l - w / 2) * k, center, alpha = alpha, style = Stroke(width = w * k))
            }
            GrooveKind.Unrated -> {
                val w = g.unratedLabelStroke.toFloat()
                mouldPath?.let { path ->
                    drawPath(path, c.slotFill, alpha = alpha)
                    drawPath(
                        path,
                        c.slot,
                        alpha = alpha,
                        style = Stroke(width = w * k, cap = StrokeCap.Round, pathEffect = mouldDash),
                    )
                }
                // spindle hole: an empty pressing
                if (g.showsSpindle) drawCircle(c.slot, g.spindleRadius.toFloat() * k, center, alpha = 0.85f * alpha)
            }
        }
        drawType(alpha)
    }

    private fun DrawScope.drawType(alpha: Float) {
        val s = g.size.toFloat()
        val r = g.radius.toFloat()
        val l = g.labelRadius.toFloat()
        val score = scoreText
        if (score != null) {
            val f = g.scoreFontSize.toFloat()
            val cap = captionText
            val capF = g.captionFontSize.toFloat()
            val scoreMargin = if (cap != null) f * 0.06f else 0f
            val total = scoreMargin + f + if (cap != null) s * 0.012f + capF else 0f
            val top = r - l + (2 * l - total) / 2
            val scoreTop = top + scoreMargin + if (cap == null) f * 0.02f else 0f
            drawText(
                score,
                color = c.ink,
                topLeft = Offset(center.x - score.size.width / 2f, lineBox(score, scoreTop, f)),
                alpha = alpha,
            )
            if (cap != null && captionAlpha > 0f) {
                val capTop = top + scoreMargin + f + s * 0.012f
                drawText(
                    cap,
                    color = c.caption,
                    topLeft = Offset(center.x - cap.size.width / 2f, lineBox(cap, capTop, capF)),
                    alpha = alpha * captionAlpha,
                )
            }
        }
        val word = unratedText
        if (word != null) {
            val f = g.unratedFontSize.toFloat()
            drawText(
                word,
                color = c.ink,
                topLeft = Offset(center.x - word.size.width / 2f, lineBox(word, r - f / 2, f)),
                alpha = alpha,
            )
        }
    }

    /**
     * CSS `line-height: 1`: a line box [sizeDp] tall at [lineTopDp], with the font's content area (ascent +
     * descent, wider than 1em for Google Sans Flex) centred in it — the glyphs sit where the browser puts them.
     */
    private fun lineBox(text: TextLayoutResult, lineTopDp: Float, sizeDp: Float): Float {
        val content = text.getLineBottom(0) - text.getLineTop(0)
        return lineTopDp * k + (sizeDp * k - content) / 2f - text.getLineTop(0)
    }

    private fun DrawScope.arc(
        color: Color,
        radiusDp: Double,
        start: Double,
        sweep: Double,
        stroke: Stroke,
        alpha: Float = 1f,
    ) {
        if (sweep <= 0.0) return
        val r = radiusDp.toFloat() * k
        drawArc(
            color = color,
            startAngle = start.toFloat(),
            sweepAngle = sweep.toFloat(),
            useCenter = false,
            topLeft = Offset(center.x - r, center.y - r),
            size = Size(2 * r, 2 * r),
            alpha = alpha.coerceIn(0f, 1f),
            style = stroke,
        )
    }

    /**
     * Prototype `tintRing`: 24 flat 15° segments; the high tone's opacity follows max(0, cos θ), the low tone's
     * max(0, −cos θ), turned to the tilt direction [ta] and faded by its size [tm]. [clip] limits a groove
     * ring's tint to its cut (and revealed) angles — computed as angle intersections, no mask.
     */
    private fun DrawScope.tintRing(
        radiusDp: Double,
        stroke: Stroke,
        tint: GrooveTint,
        weight: Float,
        ta: Float,
        tm: Float,
        clip: List<Pair<Float, Float>>?,
    ) {
        val plain = Stroke(width = stroke.width, cap = StrokeCap.Butt)
        for (n in 0 until TintSegments) {
            val a = -90f + n * TintStep
            val kk = cos((a + TintStep / 2) * PI / 180).toFloat()
            val (color, amount) = when {
                kk > 0.02f -> tint.high to kk * weight
                kk < -0.02f -> tint.low to -kk * weight
                else -> continue
            }
            val alpha = amount * tm
            val s0 = a + ta
            val s1 = s0 + TintStep
            if (clip == null) {
                arc(color, radiusDp, s0.toDouble(), TintStep.toDouble(), plain, alpha)
            } else {
                clip.forEach { (r0, r1) ->
                    for (shift in Shifts) {
                        val lo = max(s0 + shift, r0)
                        val hi = min(s1 + shift, r1)
                        if (hi > lo) arc(color, radiusDp, lo.toDouble(), (hi - lo).toDouble(), plain, alpha)
                    }
                }
            }
        }
    }

    private companion object {
        val Shifts = floatArrayOf(-360f, 0f, 360f)
    }
}

/** MaterialShapes.Cookie12Sided as a unit path, with its measured centre and outer (lobe) radius. */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
private object CookieUnit {
    val path: Path = MaterialShapes.Cookie12Sided.toPath().asComposePath()
    val center: Offset
    val outerRadius: Float

    init {
        val measure = PathMeasure().apply { setPath(path, false) }
        val samples = 720
        val points = List(samples) { measure.getPosition(measure.length * it / samples) }
        val minX = points.minOf { it.x }
        val maxX = points.maxOf { it.x }
        val minY = points.minOf { it.y }
        val maxY = points.maxOf { it.y }
        center = Offset((minX + maxX) / 2, (minY + maxY) / 2)
        outerRadius = points.maxOf { hypot(it.x - center.x, it.y - center.y) }
    }
}

// ---------------------------------------------------------------- samples & previews

/** The handoff's sample memories (data.js m1–m4 and groove-final's tier samples), for previews and the harness. */
internal object GrooveSamples {
    /** m1: album 9.5, tracks 1–4 of 10 rated. */
    val M1 = GrooveModel(GrooveKind.Album, 9.5, List(10) { it < 4 }, MemoryPaletteSamples.M1)

    /** m2: unrated, 12 tracks. */
    val M2 = GrooveModel(GrooveKind.Unrated, null, List(12) { false }, MemoryPaletteSamples.M2)

    /** m3: track average 7.8, tracks 1–6 of 8 rated. */
    val M3 = GrooveModel(GrooveKind.Average, 7.8, List(8) { it < 6 }, MemoryPaletteSamples.M3)

    /** m4: album 10.0, no track rated. */
    val M4 = GrooveModel(GrooveKind.Album, 10.0, List(6) { false }, MemoryPaletteSamples.M4)

    /** groove-final's tier samples: m1's palette, the first 5 of 10 tracks rated, only the score changes. */
    val TierScores = listOf(5.4, 7.2, 8.6, 10.0)

    fun tier(score: Double, kind: GrooveKind, palette: MemoryPalette = MemoryPaletteSamples.M1) =
        GrooveModel(kind, score, List(10) { it < 5 }, palette)
}

@Composable
private fun GrooveEmblemPreviewGrid() {
    Column(
        modifier = Modifier.padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        listOf(GrooveSamples.M1, GrooveSamples.M3, GrooveSamples.M2).forEach { model ->
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
                GrooveEmblem(model, 96.dp, GrooveSurface.Cover)
                GrooveEmblem(model, 72.dp, GrooveSurface.Cover)
                GrooveEmblem(model, 48.dp, GrooveSurface.Bar)
            }
        }
    }
}

@Preview(name = "Groove emblem · light · 96 / 72 / 48 × album / average / unrated")
@Composable
private fun GrooveEmblemLightPreview() {
    YoinTheme(darkTheme = false) {
        Surface { GrooveEmblemPreviewGrid() }
    }
}

@Preview(
    name = "Groove emblem · dark · 96 / 72 / 48 × album / average / unrated",
    uiMode = Configuration.UI_MODE_NIGHT_YES,
)
@Composable
private fun GrooveEmblemDarkPreview() {
    YoinTheme(darkTheme = true) {
        Surface { GrooveEmblemPreviewGrid() }
    }
}
