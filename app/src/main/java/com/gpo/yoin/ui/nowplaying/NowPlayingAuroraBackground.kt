package com.gpo.yoin.ui.nowplaying

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import com.gpo.yoin.ui.theme.YoinMotion
import com.gpo.yoin.ui.theme.YoinMotionRole
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin
import kotlinx.coroutines.launch

/**
 * Shared signal the transport button publishes to the [nowPlayingAuroraBackground]
 * so the reactive background can answer the *finger*, not just the committed state.
 *
 *  - [playHeld] — the PLAY/PAUSE button is currently pressed (finger down, not yet
 *    released). Drives the anticipation "gather": light converging toward the
 *    button while you hold it, so the background starts moving the instant you
 *    touch the control. Released-without-commit relaxes it back.
 *  - [gatherAnchorRoot] — the PLAY button's centre in root coordinates (reported
 *    continuously via [androidx.compose.ui.layout.onGloballyPositioned]); the
 *    gather converges here.
 *  - [burstFocalRoot] — the centre of whichever transport button actually fired
 *    the *last* action (set in the button's onClick, just before the state change).
 *    The release burst radiates from *there*, so play/pause comes out of PLAY,
 *    skip-next out of the NEXT button, skip-previous out of PREVIOUS — instead of
 *    every burst sharing one point. Null until the first action; falls back to a
 *    point just above the controls.
 *
 * Lives in a [staticCompositionLocalOf] so the deeply-nested [PlaybackControls]
 * (shared by all three layouts) can publish without threading params through the
 * whole tree; the top-level screen creates one holder and reads it for the modifier.
 */
@Stable
class NowPlayingTransportSignal {
    var playHeld by mutableStateOf(false)
    var gatherAnchorRoot by mutableStateOf<Offset?>(null)
    var burstFocalRoot by mutableStateOf<Offset?>(null)
}

val LocalNowPlayingTransportSignal = staticCompositionLocalOf<NowPlayingTransportSignal?> { null }

/**
 * The reactive Now Playing background. Replaces the static vertical wash with one
 * that responds to state and — for the transport — to the finger itself.
 *
 *  - **Base wash** — [baseTop] → [baseBottom] vertical gradient. Both tokens are
 *    palette-animated by [com.gpo.yoin.ui.theme.YoinTheme], so a song change (skip)
 *    crossfades the whole background for free.
 *  - **Gemini aurora** — while [auroraActive] (Ask Gemini is *thinking*, a long
 *    wait) a set of [auroraColors] radial blooms drift and breathe over the base.
 *    Soft transparent-falloff radials stand in for a blur so it works to minSdk;
 *    `Screen` blending gives the luminous colour-mixing on dark surfaces. Eases in,
 *    lives while the request is in flight, eases out — the long-duration treatment.
 *  - **Transport gesture (gather → release)** — the play/pause animation has a life
 *    cycle instead of a single flash:
 *      1. *Gather* — while [pressActive] (finger on the button) a soft core
 *         converges and tightens at [focalRoot], anticipating the action.
 *      2. *Release* — every change of [pulseTrigger] (the committed toggle / skip)
 *         fires a burst from the same focal. **Play and pause have distinct
 *         personalities** keyed off [isPlaying]: a *play* commit ripples outward in
 *         soft rings and drifts up in [playColor] (warm, "comes alive"); a *pause*
 *         commit collapses a single bloom inward and sinks down in [pauseColor]
 *         (cool, "held breath"). Both linger ~2s and fade, never an instant decay.
 *
 * Performance: the drift/breath loops only run while the aurora is visible; the
 * gather only animates while pressed; the burst only animates for ~2s after a tap.
 * Idle steady-state playback schedules no frame callbacks. All animated values are
 * read inside [drawBehind], so an active gesture invalidates the draw phase only.
 *
 * @param pulseTrigger any value whose change should fire a release burst; pass a key
 *   built only from the playing flag + song id so position ticks don't fire, and
 *   null when not playing. Only changes between two non-null values burst — entering/
 *   leaving playback and the initial composition are swallowed so opening Now Playing
 *   never flashes.
 * @param isPlaying the playing flag *after* the toggle commits; picks the burst
 *   personality (true → play ripple, false → pause sink). Also chosen on skip
 *   (stays true → a light ripple).
 * @param pressActive the transport button is held; drives the anticipation gather.
 * @param gatherFocalRoot the PLAY button centre in root coordinates; the gather
 *   converges here. Null → a fallback point just above the controls.
 * @param burstFocalRoot the centre of the button that fired the last action; the
 *   release burst radiates from here. Null → the same fallback point.
 */
@Composable
fun Modifier.nowPlayingAuroraBackground(
    baseTop: Color,
    baseBottom: Color,
    auroraColors: List<Color>,
    auroraActive: Boolean,
    playColor: Color,
    pauseColor: Color,
    pulseTrigger: Any?,
    isPlaying: Boolean,
    pressActive: Boolean,
    gatherFocalRoot: Offset?,
    burstFocalRoot: Offset?,
): Modifier {
    // Envelope for the Gemini aurora: in while thinking, out when the answer
    // lands. Slow effects spring — the motion law for alpha envelopes, with
    // the slow bucket keeping the long-duration treatment.
    val activeFraction by animateFloatAsState(
        targetValue = if (auroraActive) 1f else 0f,
        animationSpec = YoinMotion.slowEffectsSpec(role = YoinMotionRole.Expressive),
        label = "auroraActiveFraction",
    )
    val auroraVisible = auroraActive || activeFraction > 0.01f

    val flowPhase = remember { Animatable(0f) }
    val breathPhase = remember { Animatable(0f) }
    LaunchedEffect(auroraVisible) {
        if (!auroraVisible) return@LaunchedEffect
        launch {
            flowPhase.animateTo(
                targetValue = 1f,
                animationSpec = infiniteRepeatable(
                    animation = tween(durationMillis = 14000, easing = LinearEasing),
                    repeatMode = RepeatMode.Restart,
                ),
            )
        }
        launch {
            breathPhase.animateTo(
                targetValue = 1f,
                animationSpec = infiniteRepeatable(
                    animation = tween(durationMillis = 5200, easing = FastOutSlowInEasing),
                    repeatMode = RepeatMode.Reverse,
                ),
            )
        }
    }

    // Anticipation: light gathers at the button while the finger is held, relaxes
    // when released (or is absorbed by the release burst). The target it anticipates
    // is the *opposite* of the current state — holding while playing means you're
    // about to pause, so the gather is already the pause colour.
    val gather by animateFloatAsState(
        targetValue = if (pressActive) 1f else 0f,
        // Finger-coupled anticipation: a Standard effects spring tracks the
        // press crisply (the motion law for alpha changes).
        animationSpec = YoinMotion.defaultEffectsSpec(role = YoinMotionRole.Standard),
        label = "transportGather",
    )

    // One monotonic 0→1 sweep per committed toggle/skip drives the whole release:
    // rings travel out (play) or a bloom collapses in (pause) as it advances, while
    // sin(π·burst) fades the whole thing in and back out — a lingering breath, not a
    // snap-and-decay. [burstIsPlay] freezes the personality at fire time.
    val burst = remember { Animatable(0f) }
    var burstIsPlay by remember { mutableStateOf(true) }
    // Latch the focal WITH the trigger. Skip-next/prev change songId only after the
    // player round-trips (hundreds of ms; worse on Spotify App Remote), so the burst
    // fires long after the tap — reading burstFocalRoot live at draw time risks a
    // value that no longer matches the button that was tapped. Freezing it the moment
    // the trigger flips keeps the ripple anchored to the tapped control.
    var burstFocalFrozen by remember { mutableStateOf<Offset?>(null) }
    var lastTrigger by remember { mutableStateOf(pulseTrigger) }
    LaunchedEffect(pulseTrigger) {
        val previous = lastTrigger
        lastTrigger = pulseTrigger
        if (previous == null || pulseTrigger == null || previous == pulseTrigger) {
            return@LaunchedEffect
        }
        burstIsPlay = isPlaying
        burstFocalFrozen = burstFocalRoot
        burst.snapTo(0f)
        burst.animateTo(
            targetValue = 1f,
            animationSpec = tween(
                durationMillis = if (isPlaying) PLAY_BURST_MS else PAUSE_BURST_MS,
                easing = FastOutSlowInEasing,
            ),
        )
    }

    // The modifier's own origin in root space, so a root-space focal (the button
    // centre) can be converted into this background's local draw coordinates.
    var originRoot by remember { mutableStateOf(Offset.Zero) }

    return this
        .onGloballyPositioned { originRoot = it.positionInRoot() }
        .drawBehind {
            val w = size.width
            val h = size.height
            if (w <= 0f || h <= 0f) return@drawBehind

            drawRect(Brush.verticalGradient(listOf(baseTop, baseBottom)))

            val maxDim = max(w, h)
            // Screen glows on dark surfaces (the aurora look); on light ones it would
            // wash toward white, so tint with SrcOver there. Core alpha is low in
            // light mode and bounded in dark mode so additive overlap never blows out.
            val isDark = baseBottom.luminance() < 0.5f
            val blend = if (isDark) BlendMode.Screen else BlendMode.SrcOver
            val coreAlphaCap = if (isDark) AURORA_CORE_ALPHA_DARK else AURORA_CORE_ALPHA_LIGHT
            val burstAlphaCap = if (isDark) PULSE_CORE_ALPHA_DARK else PULSE_CORE_ALPHA_LIGHT

            // Gemini thinking wash: drifting, breathing blooms with layered
            // depth. The old version anchored the blooms at the CORNERS, so
            // their bright cores orbited mostly off-canvas and only the faint
            // outer falloff reached the screen — "基本上看不见". Anchors now sit
            // inside the canvas, each bloom orbits at its own speed (parallax
            // = the 3D-ish depth), and the hue itself travels around the
            // palette so colour visibly flows from bloom to bloom.
            val frac = activeFraction
            if (frac > 0.001f && auroraColors.isNotEmpty()) {
                val flow = flowPhase.value
                val breath = breathPhase.value
                val anchors = listOf(
                    Offset(w * 0.22f, h * 0.18f),
                    Offset(w * 0.80f, h * 0.32f),
                    Offset(w * 0.74f, h * 0.78f),
                    Offset(w * 0.26f, h * 0.66f),
                )
                val paletteSize = auroraColors.size
                auroraColors.forEachIndexed { i, color ->
                    val anchor = anchors[i % anchors.size]
                    // Per-bloom speed + phase: layered motion, not one rigid wheel.
                    val angle = TWO_PI * flow * (0.7f + 0.35f * i) + i * TWO_PI / paletteSize
                    val center = Offset(
                        x = anchor.x + cos(angle) * w * AURORA_ORBIT,
                        y = anchor.y + sin(angle * 1.3f) * h * AURORA_ORBIT,
                    )
                    val wobble = sin(TWO_PI * breath + i.toFloat())
                    // Depth: nearer blooms render bigger and brighter.
                    val depth = 1f - i * 0.16f
                    // The moving gradient: each bloom's hue slides toward its
                    // palette neighbour and back, phase-offset per bloom.
                    val cycled = lerp(
                        color,
                        auroraColors[(i + 1) % paletteSize],
                        0.5f + 0.5f * sin(TWO_PI * flow + i.toFloat()),
                    )
                    val radius = maxDim *
                        (AURORA_RADIUS_BASE + AURORA_RADIUS_WOBBLE * wobble) * depth
                    val coreAlpha = coreAlphaCap * frac *
                        (0.82f + 0.18f * wobble) * (0.72f + 0.28f * depth)
                    drawCircle(
                        brush = Brush.radialGradient(
                            colors = listOf(cycled.copy(alpha = coreAlpha), cycled.copy(alpha = 0f)),
                            center = center,
                            radius = radius,
                        ),
                        radius = radius,
                        center = center,
                        blendMode = blend,
                    )
                }
            }

            // Two focals, each converted from root to local space, both falling
            // back to a point just above the controls before the first pass:
            //  • gather → PLAY button (the held control)
            //  • burst  → whichever button fired the last action
            val fallback = Offset(w * 0.5f, h * 0.62f)
            val gatherFocal = gatherFocalRoot
                ?.let { Offset(it.x - originRoot.x, it.y - originRoot.y) } ?: fallback
            val burstFocal = burstFocalFrozen
                ?.let { Offset(it.x - originRoot.x, it.y - originRoot.y) } ?: fallback

            // Anticipation gather: a soft core tightening at the PLAY button while held.
            val g = gather
            if (g > 0.001f) {
                // Anticipates the *target* action: about-to-pause while playing.
                val gatherColor = if (isPlaying) pauseColor else playColor
                val radius = maxDim * (GATHER_R0 - GATHER_DR * g)
                drawCircle(
                    brush = Brush.radialGradient(
                        colors = listOf(
                            gatherColor.copy(alpha = burstAlphaCap * GATHER_ALPHA_SCALE * g),
                            gatherColor.copy(alpha = 0f),
                        ),
                        center = gatherFocal,
                        radius = radius,
                    ),
                    radius = radius,
                    center = gatherFocal,
                    blendMode = blend,
                )
            }

            // Release burst: ripple out (play) or collapse in (pause), from the
            // button that fired it.
            val b = burst.value
            if (b > 0.001f) {
                val env = sin((PI * b).toFloat()) // 0 → 1 → 0 fade across the sweep
                if (burstIsPlay) {
                    // Soft rings travelling outward, drifting up.
                    val center = Offset(burstFocal.x, burstFocal.y - h * PLAY_DRIFT * b)
                    for (r in 0 until PLAY_RING_COUNT) {
                        val radius = maxDim * (RING_R0 + RING_SPREAD * b + RING_GAP * r)
                        if (radius <= 0f) continue
                        val ringAlpha = burstAlphaCap * RING_ALPHA_SCALE * env * (1f - 0.34f * r)
                        drawCircle(
                            brush = Brush.radialGradient(
                                0.00f to Color.Transparent,
                                RING_BAND_INNER to Color.Transparent,
                                RING_BAND_PEAK to playColor.copy(alpha = ringAlpha),
                                1.00f to Color.Transparent,
                                center = center,
                                radius = radius,
                            ),
                            radius = radius,
                            center = center,
                            blendMode = blend,
                        )
                    }
                } else {
                    // A single bloom collapsing inward and sinking down.
                    val center = Offset(burstFocal.x, burstFocal.y + h * PAUSE_DRIFT * b)
                    val radius = maxDim * (PAUSE_R0 - PAUSE_SHRINK * b)
                    if (radius > 0f) {
                        drawCircle(
                            brush = Brush.radialGradient(
                                colors = listOf(
                                    pauseColor.copy(alpha = burstAlphaCap * PAUSE_ALPHA_SCALE * env),
                                    pauseColor.copy(alpha = 0f),
                                ),
                                center = center,
                                radius = radius,
                            ),
                            radius = radius,
                            center = center,
                            blendMode = blend,
                        )
                    }
                }
            }
        }
}

// Raised after design review ("Ask Gemini 的渐变基本看不见"): light mode needs
// real tint strength under SrcOver; dark mode can glow harder under Screen.
private const val AURORA_CORE_ALPHA_DARK = 0.5f
private const val AURORA_CORE_ALPHA_LIGHT = 0.3f
private const val PULSE_CORE_ALPHA_DARK = 0.22f
private const val PULSE_CORE_ALPHA_LIGHT = 0.16f
private const val AURORA_RADIUS_BASE = 0.52f
private const val AURORA_RADIUS_WOBBLE = 0.14f
private const val AURORA_ORBIT = 0.26f

// Release-burst durations: pause lingers a touch longer / calmer than play.
private const val PLAY_BURST_MS = 1900
private const val PAUSE_BURST_MS = 2200

// Anticipation gather.
private const val GATHER_R0 = 0.30f
private const val GATHER_DR = 0.12f // tightens as it builds
private const val GATHER_ALPHA_SCALE = 0.85f

// Play ripple: soft rings travelling outward.
private const val PLAY_RING_COUNT = 2
private const val RING_R0 = 0.12f
private const val RING_SPREAD = 0.46f
private const val RING_GAP = 0.15f
private const val RING_ALPHA_SCALE = 1.05f
private const val RING_BAND_INNER = 0.74f
private const val RING_BAND_PEAK = 0.90f
private const val PLAY_DRIFT = 0.05f // fraction of height the rings float up

// Pause sink: a single bloom collapsing inward.
private const val PAUSE_R0 = 0.40f
private const val PAUSE_SHRINK = 0.22f
private const val PAUSE_ALPHA_SCALE = 1.0f
private const val PAUSE_DRIFT = 0.045f // fraction of height the bloom sinks down

private val TWO_PI = (2.0 * PI).toFloat()
