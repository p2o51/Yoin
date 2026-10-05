package com.gpo.yoin.ui.nowplaying

import android.os.SystemClock
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import com.gpo.yoin.ui.experience.voteHighFrameRate
import com.gpo.yoin.ui.theme.YoinMotion
import com.gpo.yoin.ui.theme.YoinMotionRole
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin
import kotlinx.coroutines.Job
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
 *  - [lastTap] — the last transport tap, stamped with its time and kind (D4 P0).
 *    The release burst radiates from the tapped button only while the tap is
 *    fresh ([TransportTapValidityMs]) and of the matching kind, and a tap is
 *    spent once — a song change nobody tapped never reuses an old button's
 *    position. A PREVIOUS the player answered with a restart (no song change)
 *    is spent as soon as the playhead shows it ([observePlayhead]).
 *  - the lyrics column ([reportLyricsSurface]) and the outro's in-place
 *    hand-over ([publishLyricsHandover]) — read when a song change commits, to
 *    run the light along the lyric flow, or nothing at all.
 *
 * Only [playHeld] and [gatherAnchorRoot] are snapshot state (the press is
 * composed into the gather); everything else is read at fire time, so taps
 * and layout passes never recompose the screen.
 *
 * Lives in a [staticCompositionLocalOf] so the deeply-nested [PlaybackControls]
 * (shared by all three layouts) can publish without threading params through the
 * whole tree; the top-level screen creates one holder and reads it for the modifier.
 */
@Stable
class NowPlayingTransportSignal {
    var playHeld by mutableStateOf(false)
    var gatherAnchorRoot by mutableStateOf<Offset?>(null)

    internal var lastTap: TransportTap? = null
        private set
    // The reporting lyrics column, kept as plain fields: its bounds move every
    // stage-animation frame and are only needed when a change commits. Bounds
    // are kept per reporter, so a posture swap's two layouts can hand the
    // claim back and forth without losing where either one sits.
    private var lyricsOwner: Any? = null
    private val lyricsBounds = HashMap<Any, Rect>(2)
    private var lyricsPrimary = false
    private var lyricsLandingPx = 0f
    private var lyricsClipLight = false
    internal val lyricsSurface: LyricsSurface?
        get() = lyricsOwner?.let {
            LyricsSurface(it, lyricsBounds[it] ?: Rect.Zero, lyricsPrimary, lyricsLandingPx, lyricsClipLight)
        }
    internal var lyricsHandover: LyricsHandover? = null
        private set

    /**
     * The song the screen has committed to (the pulse key's), published by the
     * background after each composition. Taps are stamped with it so a
     * PREVIOUS that only restarts this song can be recognised.
     */
    internal var committedSongId: Any? = null

    /** The committed playing flag (the pulse key's), published with [committedSongId]. */
    internal var committedPlaying: Boolean = false

    /**
     * The user sought (a lyric line, a note row, the wave bar): the player may
     * now buffer at the new spot, and that dip and resume are the seek's, not
     * a play/pause ([seekPlaySettle]). Any settle already owed is kept when
     * there is nothing to settle (seeking while paused).
     */
    internal fun recordSeek() {
        seekPlaySettle(committedSongId, committedPlaying, SystemClock.uptimeMillis())?.let { playSettle = it }
    }

    /** [positionMs]: the song-scoped playhead at the tap ([UnknownTapPositionMs] if not known). */
    internal fun recordTap(centerRoot: Offset, kind: TransportTapKind, positionMs: Long = UnknownTapPositionMs) {
        lastTap = TransportTap(centerRoot, SystemClock.uptimeMillis(), kind, committedSongId, positionMs)
    }

    internal fun spendTap() {
        lastTap = null
    }

    /**
     * The resume to playing the player still owes after a skip or a restart
     * ([PlaySettle]); carried from one [resolveTransportPulse] to the next.
     */
    internal var playSettle: PlaySettle? = null

    /**
     * The transport row's playhead, every tick (song-scoped: it never reads
     * the next song's position while the screen still shows this one). A
     * PREVIOUS tap the player answered with a restart is spent here, at once
     * ([restartedInPlace]) — no song change will ever come to spend it. Its
     * seek may still be buffering, so its dip and resume are settled, not
     * breathed ([PlaySettle]; the resume within the tap's own validity).
     */
    internal fun observePlayhead(positionMs: Long) {
        val tap = lastTap ?: return
        if (tap.restartedInPlace(committedSongId, positionMs)) {
            lastTap = null
            playSettle = tap.songId?.let { songId ->
                PlaySettle.after(
                    songId = songId,
                    nowUptimeMs = SystemClock.uptimeMillis(),
                    resumeUntilUptimeMs = tap.uptimeMs + TransportTapValidityMs,
                )
            }
        }
    }

    internal fun claimLyricsSurface(owner: Any, primary: Boolean, landingFromTopPx: Float, clipLight: Boolean) {
        lyricsOwner = owner
        lyricsPrimary = primary
        lyricsLandingPx = landingFromTopPx
        lyricsClipLight = clipLight
    }

    internal fun moveLyricsSurface(owner: Any, boundsRoot: Rect) {
        lyricsBounds[owner] = boundsRoot
    }

    internal fun releaseLyricsSurface(owner: Any) {
        lyricsBounds.remove(owner)
        if (lyricsOwner === owner) {
            lyricsOwner = null
            lyricsPrimary = false
        }
    }

    /**
     * The lyrics page for [fromSongId] says whether it has staged [toSongId]
     * in place. Clearing only touches its own entry: the incoming song's page
     * composes in the same frame the change commits and must not wipe the
     * outgoing page's word before the pulse reads it.
     */
    internal fun publishLyricsHandover(fromSongId: Any, toSongId: Any?, staged: Boolean) {
        if (staged && toSongId != null) {
            lyricsHandover = LyricsHandover(fromSongId, toSongId)
        } else if (lyricsHandover?.fromSongId == fromSongId) {
            lyricsHandover = null
        }
    }
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
 *         converges and tightens at [gatherFocalRoot], anticipating the action.
 *      2. *Release* — a committed change of [pulseTrigger] is resolved by
 *         [resolveTransportPulse]. A play/pause bursts from the tapped PLAY
 *         button. **Play and pause have distinct personalities** keyed off
 *         [isPlaying]: a *play* commit ripples outward in soft rings and drifts up
 *         in [playColor] (warm, "comes alive"); a *pause* commit collapses a single
 *         bloom inward and sinks down in [pauseColor] (cool, "held breath"). Both
 *         linger ~2s and fade, never an instant decay. A song change while the
 *         cover leads rings from a freshly tapped NEXT / PREVIOUS only.
 *  - **Flow light** — a song change while the lyrics lead (D4 §1 B): a soft
 *    horizontal ellipse of [playColor] travels with the lyric stream (next: up
 *    from below the column, previous: down from above) onto the new title card,
 *    ~0.6s — the quick action's quick hint.
 *
 * Performance: the drift/breath loops only run while the aurora is visible; the
 * gather only animates while pressed; the burst only animates for ~2s after a tap,
 * the light for ~0.6s after a song change — and votes a high frame rate for just
 * that sweep ([FlowLightRun.moving]).
 * Idle steady-state playback schedules no frame callbacks. All animated values are
 * read inside [drawBehind], so an active gesture invalidates the draw phase only.
 *
 * @param pulseTrigger the committed state (song id + playing flag + queue position,
 *   never the playhead), null when not playing. Only changes between two non-null
 *   values pulse — entering/leaving playback and the initial composition are
 *   swallowed so opening Now Playing never flashes.
 * @param isPlaying the playing flag *after* the toggle commits; picks the gather's
 *   colour (it anticipates the opposite action).
 * @param pressActive the transport button is held; drives the anticipation gather.
 * @param gatherFocalRoot the PLAY button centre in root coordinates; the gather
 *   converges here. Null → a fallback point just above the controls.
 * @param burstFocalRoot where a play/pause breath with no fresh tap behind it
 *   (headset, notification, audio focus) rises from. Null → the same fallback point.
 * @param transportSignal the taps, lyrics column and outro hand-over read when a
 *   change commits. Null → every change is an untapped one with the cover leading.
 */
@Composable
fun Modifier.nowPlayingAuroraBackground(
    baseTop: Color,
    baseBottom: Color,
    auroraColors: List<Color>,
    auroraActive: Boolean,
    playColor: Color,
    pauseColor: Color,
    pulseTrigger: TransportPulseKey?,
    isPlaying: Boolean,
    pressActive: Boolean,
    gatherFocalRoot: Offset?,
    burstFocalRoot: Offset?,
    transportSignal: NowPlayingTransportSignal? = null,
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

    // One monotonic 0→1 sweep per burst drives the whole release: rings travel out
    // (play) or a bloom collapses in (pause) as it advances, while sin(π·burst)
    // fades the whole thing in and back out — a lingering breath, not a
    // snap-and-decay. [burstIsPlay] freezes the personality at fire time.
    val burst = remember { Animatable(0f) }
    var burstIsPlay by remember { mutableStateOf(true) }
    // Latch the focal WITH the commit. Skip-next/prev change songId only after the
    // player round-trips (hundreds of ms; worse on Spotify App Remote), so the burst
    // fires long after the tap; freezing the tap's centre the moment the change
    // commits keeps the ripple anchored to the tapped control.
    var burstFocalFrozen by remember { mutableStateOf<Offset?>(null) }

    // The flow light: travel 0 → 1 (+ the spring's overshoot) from beyond the
    // column edge onto the new title; alpha in fast, out slow from 85% travel.
    // Geometry frozen at fire time (local px), like the burst focal.
    val light = remember { FlowLightRun() }
    // Flips twice per light (derivedStateOf over Animatable.isRunning), never
    // per frame: the high frame-rate vote below spans exactly the sweep.
    val lightMoving by remember(light) { derivedStateOf { light.moving } }
    var lightColumn by remember { mutableStateOf(Rect.Zero) }
    var lightLandingY by remember { mutableStateOf(0f) }
    var lightForward by remember { mutableStateOf(true) }
    var lightClipped by remember { mutableStateOf(false) }
    val lightTravelSpec = YoinMotion.slowSpatialSpec<Float>(role = YoinMotionRole.Expressive)
    val lightInSpec = YoinMotion.fastEffectsSpec<Float>(role = YoinMotionRole.Expressive)
    val lightOutSpec = YoinMotion.slowEffectsSpec<Float>(role = YoinMotionRole.Expressive)

    // The modifier's own origin in root space, so a root-space focal (the button
    // centre, the lyrics column) converts into this background's local coordinates.
    var originRoot by remember { mutableStateOf(Offset.Zero) }

    // Pulses run in this scope, NOT inside the keyed effect below: the next
    // commit restarting that effect must not cancel a burst mid-sweep and
    // leave it frozen on screen. A new pulse restarts its own Animatables.
    val pulseScope = rememberCoroutineScope()
    val lightJob = remember { arrayOfNulls<Job>(1) }
    var lastTrigger by remember { mutableStateOf(pulseTrigger) }
    // Taps are stamped with the committed song (see observePlayhead); a seek
    // settles only what it can dip (see recordSeek).
    SideEffect {
        transportSignal?.committedSongId = pulseTrigger?.songId
        transportSignal?.committedPlaying = pulseTrigger?.isPlaying == true
    }
    LaunchedEffect(pulseTrigger) {
        val previous = lastTrigger
        lastTrigger = pulseTrigger
        val surface = transportSignal?.lyricsSurface
        val decision = resolveTransportPulse(
            previous = previous,
            current = pulseTrigger,
            tap = transportSignal?.lastTap,
            nowUptimeMs = SystemClock.uptimeMillis(),
            lyricsPrimary = surface?.primary == true && surface.boundsRoot.height > 0f,
            handover = transportSignal?.lyricsHandover,
            settle = transportSignal?.playSettle,
        )
        if (decision.tapUsed) transportSignal?.spendTap()
        transportSignal?.playSettle = decision.settle
        when (val pulse = decision.pulse) {
            TransportPulse.None -> Unit
            is TransportPulse.Burst -> {
                val playing = pulse.isPlay
                burstIsPlay = playing
                burstFocalFrozen = pulse.focalRoot ?: burstFocalRoot
                pulseScope.launch {
                    burst.snapTo(0f)
                    burst.animateTo(
                        targetValue = 1f,
                        animationSpec = tween(
                            durationMillis = if (playing) PLAY_BURST_MS else PAUSE_BURST_MS,
                            easing = FastOutSlowInEasing,
                        ),
                    )
                }
            }
            is TransportPulse.FlowLight -> {
                // FlowLight is only decided with a measured column on screen.
                val lyricsColumn = surface ?: return@LaunchedEffect
                val column = lyricsColumn.boundsRoot.translate(-originRoot)
                lightColumn = column
                lightLandingY = column.top + lyricsColumn.landingFromTopPx
                lightForward = pulse.forward
                lightClipped = lyricsColumn.clipLight
                lightJob[0]?.cancel()
                lightJob[0] = pulseScope.launch {
                    light.play(lightTravelSpec, lightInSpec, lightOutSpec)
                }
            }
        }
    }

    return this
        // A short no-touch motion (the finger, if any, has lifted): vote High
        // for exactly the sweep, so an adaptive-refresh panel doesn't pace it
        // at 60Hz. Read here, twice per light — like playHeld for the gather.
        .voteHighFrameRate(lightMoving)
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
            //  • burst  → the freshly tapped button that fired this burst
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

            // Flow light: a soft horizontal band riding the lyric stream onto
            // the new title (D4 §1 B). An ellipse = a radial circle scaled wide
            // about its centre; the two-pane column clips it to itself.
            val la = light.alpha.value
            val column = lightColumn
            if (la > 0.001f && column.height > 0f) {
                val frame = flowLightFrame(column, lightLandingY, lightForward, light.travel.value)
                if (frame.radiusY > 0f) {
                    val a = burstAlphaCap * la.coerceIn(0f, 1f)
                    withTransform(
                        {
                            if (lightClipped) clipRect(left = column.left, top = 0f, right = column.right, bottom = h)
                            scale(scaleX = frame.radiusX / frame.radiusY, scaleY = 1f, pivot = frame.center)
                        },
                    ) {
                        drawCircle(
                            brush = Brush.radialGradient(
                                0.0f to playColor.copy(alpha = a),
                                0.5f to playColor.copy(alpha = a * 0.5f),
                                1.0f to playColor.copy(alpha = 0f),
                                center = frame.center,
                                radius = frame.radiusY,
                            ),
                            radius = frame.radiusY,
                            center = frame.center,
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
