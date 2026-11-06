package com.gpo.yoin.ui.nowplaying

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import com.gpo.yoin.data.model.LibraryMembership
import com.gpo.yoin.player.PlayMode
import com.gpo.yoin.ui.component.TrackLibraryButton
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin

/*
 * Action lights (owner 2026-11-04, prototype v1 approved): every Now Playing
 * action that changes something lights the background from the control that
 * was pressed, in the cover's colours with Yoin's fine dots, for 0.6–1.7 s.
 * Adding something sends the light out (favourite, a rating, a note); taking
 * it away draws a dimmer, shorter light back into the control. Waits use a
 * slow sweep in the cover's colours (only Gemini is rainbow). Opening a
 * sheet, switching a tab or leaving the page lights nothing.
 *
 * Controls fire with [NowPlayingTransportSignal.glow]; the background
 * ([nowPlayingAuroraBackground]) runs and draws them behind the content.
 */

internal enum class ActionGlowKind(val durationMs: Int) {
    /** The heart filled: two beats, then a ring of dots flies out. */
    FavoriteOn(1400),

    /** The heart emptied: a dim bloom drawn back into it. */
    FavoriteOff(700),

    /** Added somewhere (long-press heart → playlist, the library button): dots pour in, then a bloom. */
    GatherInto(1300),

    /** A rating landed ([ActionGlow.value] 0.1–10): a band leaves the bar at that height; full marks spray dots. */
    RatingCommit(1300),

    /** The rating was cleared: the light drains down the bar. */
    RatingClear(800),

    /*
     * Play mode (owner 2026-11-06: the first lights were flashy and greasy).
     * Every turn of the key gets the same quiet light: one soft glow in the
     * cover's colour swells from the key and settles; no dots, no colours
     * trading places, no comet. Turning something off draws a dimmer glow
     * back in. A tap while the last one is still lit takes it over.
     */

    /** Shuffle on: the key's quiet glow. */
    ShuffleOn(PlayModeOnMs),

    /** Shuffle off: a dimmer glow drawn back into the key. */
    ShuffleOff(PlayModeOffMs),

    /** Repeat all: the key's quiet glow. */
    RepeatAll(PlayModeOnMs),

    /** Repeat one: the key's quiet glow. */
    RepeatOne(PlayModeOnMs),

    /** Repeat off: a dimmer glow drawn back into the key. */
    RepeatOff(PlayModeOffMs),

    /** The wave bar let go: a flash where the playhead landed. */
    SeekRelease(650),

    /** A note saved: ink rises from [ActionGlow.anchorRoot] to the line at [ActionGlow.targetY], which glows. */
    NoteSave(1700),

    /** A note deleted: its line's light shrinks back. */
    NoteDelete(700),

    /** New lyrics or a translation arrived: each line lights once, top to bottom, over the lyrics. */
    LyricsReveal(1300),

    /** Playback moved to another device: the light leaves downwards, then a ring comes back from the bottom. */
    DeviceHandoff(2100),

    /** Keep screen on: the whole room brightens for a moment. */
    RoomLift(900),

    /** The cover's menu opened: a ring closes in on the cover. */
    CoverHold(700),

    /** Lyrics copied or shared: a band at [ActionGlow.targetY]. */
    LineGlow(1400),
    ;

    /** A play-mode light: a newer one takes over from it instead of piling on. */
    val isPlayMode: Boolean
        get() = this == ShuffleOn || this == ShuffleOff || this == RepeatAll || this == RepeatOne || this == RepeatOff

    /** A full-marks rating sprays dots and runs longer. */
    fun durationFor(value: Float): Int = if (this == RatingCommit && value >= FullRating) 1700 else durationMs
}

/**
 * One fired light. [anchorRoot] is the control (root px); [value] the rating
 * for [ActionGlowKind.RatingCommit]; [targetY] a lyric line's height (root px)
 * where the kind lands on one; [areaRoot] the lyrics' bounds for the reveal.
 */
internal data class ActionGlow(
    val kind: ActionGlowKind,
    val anchorRoot: Rect,
    val bornUptimeMs: Long,
    val value: Float = 0f,
    val targetY: Float = Float.NaN,
    val areaRoot: Rect? = null,
    /** When a newer light of the same family took over; this one fades out over [GlowHandOverMs]. */
    val retiredUptimeMs: Long = Long.MAX_VALUE,
) {
    val durationMs: Int get() = kind.durationFor(value)

    /** Done drawing at [nowMs]: its time is up, or it has handed over. */
    fun isDone(nowMs: Long): Boolean = nowMs - bornUptimeMs > durationMs || nowMs - retiredUptimeMs > GlowHandOverMs
}

/**
 * Adds [incoming] to the running lights: a play-mode light retires the
 * play-mode lights still running, so quick taps round the cycle read as one
 * light moving on rather than a pile.
 */
internal fun MutableList<ActionGlow>.addHandingOver(incoming: ActionGlow) {
    if (incoming.kind.isPlayMode) {
        for (i in indices) {
            val g = this[i]
            if (g.kind.isPlayMode && g.retiredUptimeMs == Long.MAX_VALUE) {
                this[i] = g.copy(retiredUptimeMs = incoming.bornUptimeMs)
            }
        }
    }
    add(incoming)
}

/** A finger held on a control: the rating bar's column, the wave bar's scrub. */
internal data class GlowHold(
    val kind: GlowHoldKind,
    /** The finger's spot (root px): the value's point on the bar. */
    val pointRoot: Offset,
    /** The control's bounds (root px). */
    val barRoot: Rect,
    /** The rating under the finger (0–10), for the column's strength. */
    val value: Float = 0f,
)

internal enum class GlowHoldKind { Rating, Scrub }

/** Controls whose bounds a light starts from when it isn't fired by the control itself. */
internal enum class GlowAnchor { Devices, Write, Cover }

/**
 * Which light a play-mode change gets, or null when nothing changed. Shuffle
 * wins when one tap changes both axes (it doesn't today).
 */
internal fun playModeGlowKind(from: PlayMode, to: PlayMode): ActionGlowKind? = when {
    from.shuffle != to.shuffle -> if (to.shuffle) ActionGlowKind.ShuffleOn else ActionGlowKind.ShuffleOff
    from.repeatMode != to.repeatMode -> when {
        to.repeatOne -> ActionGlowKind.RepeatOne
        to.repeatOff -> ActionGlowKind.RepeatOff
        else -> ActionGlowKind.RepeatAll
    }
    else -> null
}

/** Which light a rating commit gets: a cleared rating (null or 0) drains. */
internal fun ratingGlowKind(value: Float?): ActionGlowKind =
    if (value == null || value <= 0f) ActionGlowKind.RatingClear else ActionGlowKind.RatingCommit

/**
 * Where [value] (0–10) sits on a rating bar laid out at [bar]: from the bottom
 * up when vertical, from the start along when horizontal.
 */
internal fun ratingPoint(bar: Rect, value: Float, vertical: Boolean, rtl: Boolean): Offset {
    val f = (value / FullRatingScale).coerceIn(0f, 1f)
    return if (vertical) {
        Offset(bar.center.x, bar.bottom - bar.height * f)
    } else {
        val x = if (rtl) bar.right - bar.width * f else bar.left + bar.width * f
        Offset(x, bar.center.y)
    }
}

/** Reports this control's bounds as [anchor] for lights that start from it. */
@Composable
internal fun Modifier.reportGlowAnchor(anchor: GlowAnchor): Modifier {
    val signal = LocalNowPlayingTransportSignal.current ?: return this
    return onGloballyPositioned { signal.reportAnchor(anchor, it.boundsInRoot()) }
}

/** Tracks this control's bounds into [into] (root px), for a light it fires itself. */
internal fun Modifier.trackGlowBounds(into: GlowBounds): Modifier =
    onGloballyPositioned { into.rootBounds = it.boundsInRoot() }

/** A control's last bounds, kept as a plain field (read at fire time only). */
internal class GlowBounds {
    var rootBounds: Rect = Rect.Zero
}

/** The palette a light draws in: the cover's primary / secondary / tertiary, made glowy. */
internal class GlowPalette(primary: Color, secondary: Color, tertiary: Color, val dark: Boolean) {
    val p: Color = tint(primary, 1f)
    val s: Color = tint(secondary, 0.5f)
    val t: Color = tint(tertiary, 1f)

    /** The peak alpha a burst uses, as the play / pause pulse. */
    val cap: Float = if (dark) 0.42f else 0.40f

    private fun tint(color: Color, satScale: Float): Color {
        val hsl = FloatArray(3)
        argbToHsl(color.toArgb(), hsl)
        return Color.hsl(
            hue = ((hsl[0] % 360f) + 360f) % 360f,
            saturation = (if (dark) 0.75f else 0.85f) * satScale,
            lightness = if (dark) 0.62f else 0.62f,
        )
    }

    /** Dots read a touch darker on a light page, lighter on a dark one. */
    fun dotColor(c: Color, alpha: Float): Color {
        val hsl = FloatArray(3)
        argbToHsl(c.toArgb(), hsl)
        val l = if (dark) min(0.8f, hsl[2] + 0.1f) else max(0.4f, hsl[2] - 0.2f)
        return Color.hsl(hsl[0], hsl[1], l, alpha.coerceIn(0f, 1f))
    }
}

/**
 * Draws [glow] [tMs] into its run, in the background's own px ([origin] is
 * the background's root position).
 */
internal fun DrawScope.drawActionGlow(
    glow: ActionGlow,
    tMs: Float,
    origin: Offset,
    pal: GlowPalette,
    blend: BlendMode,
) {
    val d = glow.durationMs.toFloat()
    if (tMs < 0f || tMs > d) return
    val u = tMs / d
    val dp = density
    // A light that handed over fades out quickly under the one that took over.
    val since = glow.bornUptimeMs + tMs.toLong() - glow.retiredUptimeMs
    val k = pal.cap * if (since > 0) 1f - (since.toFloat() / GlowHandOverMs).coerceIn(0f, 1f) else 1f
    val a = glow.anchorRoot.translate(-origin)
    val c = a.center
    val w = size.width
    val h = size.height
    // Leans toward the middle of the screen, away from the edge the control sits on.
    val inward = if (c.x > w / 2f) -1f else 1f
    when (glow.kind) {
        ActionGlowKind.FavoriteOn -> {
            val beat = max(bell(tMs / 260f), 0.75f * bell((tMs - 300f) / 300f))
            glowBloom(c, (70f + 50f * u) * dp, pal.t, k * 1.5f * beat + k * 0.5f * bell(u), blend)
            glowBloom(
                Offset(c.x + inward * 40f * dp, c.y - 30f * dp),
                170f * dp * easeOut(min(1f, tMs / 700f)),
                pal.p,
                k * 0.6f * bell(u),
                blend,
            )
            if (tMs > 120f) {
                val r = (30f + 110f * easeOut(min(1f, (tMs - 120f) / 900f))) * dp
                for (i in 0 until 14) {
                    val ang = i / 14f * TAU + 0.2f
                    glowDot(
                        Offset(c.x + cos(ang) * r, c.y + sin(ang) * r * 0.9f),
                        (2.2f * (1f - u) + 0.4f) * dp,
                        pal.dotColor(if (i % 3 == 0) pal.p else pal.t, (1f - u) * 0.9f),
                    )
                }
            }
        }
        ActionGlowKind.FavoriteOff ->
            glowBloom(c, (110f * (1f - easeOut(u)) + 10f) * dp, pal.s, k * 0.8f * bell(u), blend)
        ActionGlowKind.GatherInto -> {
            val g = min(1f, tMs / 700f)
            if (g < 1f) {
                val r = (140f * (1f - easeOut(g)) + 6f) * dp
                for (i in 0 until 18) {
                    val ang = i / 18f * TAU
                    glowDot(
                        Offset(c.x + cos(ang) * r, c.y + sin(ang) * r),
                        1.8f * dp,
                        pal.dotColor(if (i % 2 == 0) pal.p else pal.t, 0.85f),
                    )
                }
            }
            if (tMs > 650f) {
                val v = (tMs - 650f) / 650f
                glowBloom(c, (60f + 60f * easeOut(v)) * dp, pal.p, k * 1.4f * bell(v), blend)
            }
        }
        ActionGlowKind.RatingCommit -> {
            val str = 0.35f + 0.65f * (glow.value / FullRatingScale).coerceIn(0f, 1f)
            // A band leaves the bar at the rating's height and crosses the room.
            glowEllipse(
                Offset(c.x + inward * 160f * dp * easeOut(u), c.y),
                rx = (140f + 260f * easeOut(u)) * dp,
                ry = (60f + 40f * u) * dp,
                color = pal.p,
                alpha = k * 1.6f * str * bell(u),
                blend = blend,
            )
            glowBloom(c, (50f + 40f * u) * dp, pal.t, k * 1.2f * str * bell(u * 1.4f), blend)
            if (glow.value >= FullRating) {
                for (i in 0 until 26) {
                    val sx = c.x + inward * (i / 26f) * 300f * dp * easeOut(u)
                    val sy = c.y + sin(i * 1.7f + tMs * 0.006f) * 18f * dp * u
                    glowDot(
                        Offset(sx, sy),
                        (1.8f * (1f - u) + 0.3f) * dp,
                        pal.dotColor(if (i % 2 == 0) pal.p else pal.t, 1f - u),
                    )
                }
            }
        }
        ActionGlowKind.RatingClear -> {
            val bottom = if (a.height > a.width) a.bottom else c.y
            val y = c.y + (bottom - c.y) * easeOut(u)
            glowEllipse(
                Offset(c.x, y),
                rx = 70f * dp,
                ry = 40f * dp,
                color = pal.s,
                alpha = k * 0.9f * (1f - u),
                blend = blend,
            )
        }
        ActionGlowKind.ShuffleOn, ActionGlowKind.RepeatAll, ActionGlowKind.RepeatOne ->
            glowBloom(c, (56f + 34f * easeOut(u)) * dp, pal.p, k * 0.75f * swell(u), blend)
        ActionGlowKind.ShuffleOff, ActionGlowKind.RepeatOff ->
            glowBloom(c, (72f - 28f * easeOut(u)) * dp, pal.s, k * 0.45f * swell(u), blend)
        ActionGlowKind.SeekRelease -> {
            glowBloom(c, (40f + 50f * u) * dp, pal.p, k * 1.3f * bell(u), blend)
            glowEllipse(
                c,
                rx = (120f + 120f * u) * dp,
                ry = 30f * dp,
                color = pal.p,
                alpha = k * 0.6f * bell(u),
                blend = blend,
            )
        }
        ActionGlowKind.NoteSave -> {
            val ly = if (glow.targetY.isNaN()) h * 0.55f else glow.targetY - origin.y
            val q = easeOut(min(1f, tMs / 650f))
            val toX = w * 0.2f
            if (tMs < 800f) {
                val fade = if (tMs < 650f) 1f else 1f - (tMs - 650f) / 150f
                glowBloom(Offset(c.x + (toX - c.x) * q, c.y + (ly - c.y) * q), 50f * dp, pal.t, k * 1.4f * fade, blend)
            }
            if (tMs > 500f) {
                val v = (tMs - 500f) / 1200f
                glowEllipse(
                    Offset(w / 2f, ly),
                    rx = w * 0.9f,
                    ry = 36f * dp,
                    color = pal.t,
                    alpha = k * 1.3f * bell(v),
                    blend = blend,
                )
                glowDotRow(ly + 18f * dp, w * 0.06f, w * 0.94f, pal.dotColor(pal.t, 0.7f * bell(v)), tMs, dp)
            }
        }
        ActionGlowKind.NoteDelete -> {
            val ly = if (glow.targetY.isNaN()) h * 0.55f else glow.targetY - origin.y
            glowEllipse(
                Offset(w * 0.17f + w * 0.33f * u, ly),
                rx = (w * 0.55f * (1f - u) + 20f * dp),
                ry = 28f * dp,
                color = pal.s,
                alpha = k * 0.8f * (1f - u),
                blend = blend,
            )
        }
        ActionGlowKind.LyricsReveal -> {
            val area = glow.areaRoot?.translate(-origin) ?: Rect(0f, h * 0.45f, w, h * 0.75f)
            val step = 34f * dp
            var i = 0
            var y = area.top + step * 0.6f
            while (y < area.bottom && i < 12) {
                val v = (tMs - i * 90f) / 700f
                if (v in 0f..1f) {
                    glowEllipse(
                        Offset(area.center.x, y),
                        rx = area.width * 0.95f,
                        ry = 24f * dp,
                        color = pal.p,
                        alpha = k * 0.9f * bell(v),
                        blend = blend,
                    )
                }
                y += step
                i++
            }
        }
        ActionGlowKind.DeviceHandoff -> {
            if (tMs < 1100f) {
                val v = tMs / 1100f
                val y = c.y + 140f * dp * easeOut(v)
                glowBloom(Offset(c.x, y), (90f - 30f * v) * dp, pal.p, k * 1.4f * bell(v), blend)
                glowDotRow(y + 10f * dp, c.x - 50f * dp, c.x + 50f * dp, pal.dotColor(pal.p, 0.7f * (1f - v)), tMs, dp)
            }
            if (tMs > 900f) {
                val v = (tMs - 900f) / 1200f
                glowRing(
                    Offset(w / 2f, h + 20f * dp),
                    (120f + 520f * easeOut(v)) * dp,
                    pal.p,
                    k * 1.1f * bell(v),
                    blend,
                    width = 0.1f,
                )
            }
        }
        ActionGlowKind.RoomLift -> {
            val color = if (pal.dark) pal.p.copy(alpha = 0.14f * bell(u)) else Color.White.copy(alpha = 0.5f * bell(u))
            drawRect(color, blendMode = blend)
        }
        ActionGlowKind.CoverHold ->
            glowRing(
                c,
                max(a.width, a.height) * 0.72f - 40f * dp * easeOut(u),
                pal.p,
                k * 1.1f * bell(u),
                blend,
                width = 0.1f,
            )
        ActionGlowKind.LineGlow -> {
            val ly = if (glow.targetY.isNaN()) c.y else glow.targetY - origin.y
            glowEllipse(
                Offset(w / 2f, ly),
                rx = w,
                ry = 40f * dp,
                color = pal.p,
                alpha = k * 1.1f * bell(u),
                blend = blend,
            )
        }
    }
}

/** A held finger's light, at [strength] (0–1, its fade in and out). */
internal fun DrawScope.drawGlowHold(
    hold: GlowHold,
    origin: Offset,
    strength: Float,
    pal: GlowPalette,
    blend: BlendMode,
) {
    if (strength <= 0.001f) return
    val dp = density
    val k = pal.cap * strength
    val p = hold.pointRoot - origin
    when (hold.kind) {
        GlowHoldKind.Rating -> {
            // A column of light up the bar to the finger, brighter the higher the score.
            val str = 0.3f + 0.7f * (hold.value / FullRatingScale).coerceIn(0f, 1f)
            val bar = hold.barRoot.translate(-origin)
            glowBloom(p, 70f * dp, pal.p, k * 1.3f * str, blend)
            if (bar.height > bar.width) {
                glowEllipse(
                    Offset(p.x, (p.y + bar.bottom) / 2f),
                    rx = 45f * dp,
                    ry = (bar.bottom - p.y) / 2f + 30f * dp,
                    color = pal.p,
                    alpha = k * 0.7f * str,
                    blend = blend,
                )
            }
        }
        GlowHoldKind.Scrub -> glowBloom(p, 46f * dp, pal.p, k * 1.1f, blend)
    }
}

/** A slow sweep in the cover's colours across the lyrics while something is fetched for them. */
internal fun DrawScope.drawGlowWait(
    areaRoot: Rect?,
    origin: Offset,
    tMs: Float,
    strength: Float,
    pal: GlowPalette,
    blend: BlendMode,
) {
    if (strength <= 0.001f) return
    val dp = density
    val w = size.width
    val area = areaRoot?.translate(-origin)?.takeIf { !it.isEmpty }
        ?: Rect(0f, size.height * 0.45f, w, size.height * 0.75f)
    val y = area.top + 40f * dp
    val x = area.left - 80f * dp + ((tMs / 1400f) % 1f) * (area.width + 160f * dp)
    glowEllipse(
        Offset(x, y),
        rx = 160f * dp,
        ry = 70f * dp,
        color = pal.p,
        alpha = pal.cap * 0.9f * strength,
        blend = blend,
    )
    glowDotRow(
        y + 60f * dp,
        max(area.left, x - 60f * dp),
        min(area.right, x + 60f * dp),
        pal.dotColor(pal.p, 0.55f * strength),
        tMs,
        dp,
    )
}

private fun DrawScope.glowBloom(center: Offset, radius: Float, color: Color, alpha: Float, blend: BlendMode) {
    if (radius <= 1f || alpha <= 0.002f) return
    val a = alpha.coerceIn(0f, 1f)
    drawCircle(
        brush = Brush.radialGradient(
            0f to color.copy(alpha = a),
            0.5f to color.copy(alpha = a * 0.45f),
            1f to color.copy(alpha = 0f),
            center = center,
            radius = radius,
        ),
        radius = radius,
        center = center,
        blendMode = blend,
    )
}

/** A soft ellipse: a bloom stretched to [rx] × [ry]. */
private fun DrawScope.glowEllipse(center: Offset, rx: Float, ry: Float, color: Color, alpha: Float, blend: BlendMode) {
    if (rx <= 1f || ry <= 1f || alpha <= 0.002f) return
    val a = alpha.coerceIn(0f, 1f)
    withTransform({ scale(scaleX = rx / ry, scaleY = 1f, pivot = center) }) {
        drawCircle(
            brush = Brush.radialGradient(
                0f to color.copy(alpha = a),
                0.55f to color.copy(alpha = a * 0.35f),
                1f to color.copy(alpha = 0f),
                center = center,
                radius = ry,
            ),
            radius = ry,
            center = center,
            blendMode = blend,
        )
    }
}

private fun DrawScope.glowRing(
    center: Offset,
    radius: Float,
    color: Color,
    alpha: Float,
    blend: BlendMode,
    width: Float = 0.16f,
) {
    if (radius <= 1f || alpha <= 0.002f) return
    val a = alpha.coerceIn(0f, 1f)
    drawCircle(
        brush = Brush.radialGradient(
            0f to Color.Transparent,
            max(0f, 1f - width * 2f) to color.copy(alpha = 0f),
            1f - width to color.copy(alpha = a),
            1f to color.copy(alpha = 0f),
            center = center,
            radius = radius,
        ),
        radius = radius,
        center = center,
        blendMode = blend,
    )
}

private fun DrawScope.glowDot(center: Offset, radius: Float, color: Color) {
    if (radius <= 0.15f || color.alpha <= 0.01f) return
    drawCircle(color = color, radius = radius, center = center)
}

/** A row of fine dots whose sizes ripple over time, Yoin's halftone. */
private fun DrawScope.glowDotRow(y: Float, x0: Float, x1: Float, color: Color, tMs: Float, dp: Float) {
    if (color.alpha <= 0.01f) return
    var x = x0
    while (x <= x1) {
        val n = 0.5f + 0.5f * sin(x / dp * 0.05f + tMs * 0.004f)
        glowDot(Offset(x, y), 1.3f * dp * (0.4f + 0.6f * n), color)
        x += 6f * dp
    }
}

private fun bell(t: Float): Float = sin(PI.toFloat() * t.coerceIn(0f, 1f))

/** Up quickly, then a long settle: a light that arrives and lets go without a bounce. */
private fun swell(t: Float): Float {
    val u = t.coerceIn(0f, 1f)
    if (u < SwellPeak) return sin(PI.toFloat() / 2f * u / SwellPeak)
    val v = (u - SwellPeak) / (1f - SwellPeak)
    return 1f - v * v * (3f - 2f * v)
}

private fun easeOut(t: Float): Float = 1f - (1f - t.coerceIn(0f, 1f)).pow(3)

private const val TAU = (2.0 * PI).toFloat()
private const val SwellPeak = 0.28f
private const val PlayModeOnMs = 800
private const val PlayModeOffMs = 550

/** How long a light that was taken over takes to fade out. */
internal const val GlowHandOverMs = 160L
private const val FullRatingScale = 10f

/** A rating this high counts as full marks for the spray. */
private const val FullRating = 9.5f

/** The library button on Now Playing: adding the song pours a light into it. */
@Composable
internal fun NowPlayingLibraryButton(
    membership: LibraryMembership,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    isWorking: Boolean = false,
) {
    val signal = LocalNowPlayingTransportSignal.current
    val bounds = remember { GlowBounds() }
    TrackLibraryButton(
        membership = membership,
        onClick = {
            signal?.glow(ActionGlowKind.GatherInto, bounds.rootBounds)
            onClick()
        },
        modifier = modifier.trackGlowBounds(bounds),
        isWorking = isWorking,
    )
}

/**
 * The wave bar's lights: [scrub] a finger on it (the fraction under it, null
 * when it lifts), [release] the seek it committed.
 */
internal class WaveGlow(private val signal: NowPlayingTransportSignal?) {
    val bounds = GlowBounds()

    fun scrub(fraction: Float?) {
        val signal = signal ?: return
        val b = bounds.rootBounds
        if (fraction == null || b.isEmpty) {
            if (signal.glowHold?.kind == GlowHoldKind.Scrub) signal.glowHold = null
            return
        }
        signal.glowHold = GlowHold(GlowHoldKind.Scrub, pointAt(b, fraction), b)
    }

    fun release(fraction: Float) {
        val b = bounds.rootBounds
        if (b.isEmpty) return
        signal?.glow(ActionGlowKind.SeekRelease, Rect(pointAt(b, fraction), 1f))
    }

    // The bar reads its fraction left to right in every layout direction.
    private fun pointAt(b: Rect, fraction: Float) = Offset(b.left + b.width * fraction.coerceIn(0f, 1f), b.center.y)
}
