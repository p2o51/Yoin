package com.gpo.yoin.ui.nowplaying

import android.graphics.Bitmap
import android.os.SystemClock
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.EaseOutCubic
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import com.gpo.yoin.ui.experience.voteHighFrameRate
import com.gpo.yoin.ui.theme.YoinMotion
import com.gpo.yoin.ui.theme.YoinMotionRole
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.drop
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
 *    flow the new colours with the lyric stream, or not at all.
 *  - [focusCoverRoot] — where the cover sits while it is focused; the focus
 *    field opens out from it.
 *  - the last finger-down ([recordPointerDown]) and lyric-line taps
 *    ([recordLineTap]) — a tapped line lights a faint band at its height.
 *
 * Only [playHeld], [gatherAnchorRoot] and [focusCoverRoot] are snapshot state
 * (the first two are composed into the gather, the last is read in draw);
 * everything else is read at fire time, so taps and layout passes never
 * recompose the screen.
 *
 * Lives in a [staticCompositionLocalOf] so the deeply-nested [PlaybackControls]
 * (shared by all three layouts) can publish without threading params through the
 * whole tree; the top-level screen creates one holder and reads it for the modifier.
 */
@Stable
class NowPlayingTransportSignal {
    var playHeld by mutableStateOf(false)
    var gatherAnchorRoot by mutableStateOf<Offset?>(null)

    /** The cover's bounds in root px, as laid out now (read in draw only). */
    var focusCoverRoot by mutableStateOf<Rect?>(null)

    private var pointerDownLocal: Offset? = null
    private var pointerDownUptimeMs = 0L

    /** Bumped on every lyric-line tap; observed with snapshotFlow, never composed. */
    internal var lineTaps by mutableIntStateOf(0)
        private set

    /** The tapped line's height in the background's px, for the [lineTaps] that just fired. */
    internal var lineTapY = 0f
        private set

    /** A finger went down at [local] (the background's own px). */
    internal fun recordPointerDown(local: Offset) {
        pointerDownLocal = local
        pointerDownUptimeMs = SystemClock.uptimeMillis()
    }

    /**
     * A lyric line (or note row) was tapped to seek. The line is where the
     * finger last went down, if that was just now; a seek with no fresh
     * finger (keyboard, accessibility) lights nothing.
     */
    internal fun recordLineTap() {
        val at = pointerDownLocal ?: return
        if (SystemClock.uptimeMillis() - pointerDownUptimeMs > LineTapFingerValidityMs) return
        lineTapY = at.y
        lineTaps++
    }

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

/** The playing cover read by position ([PlaybackThemeState][com.gpo.yoin.ui.theme.PlaybackThemeState]). */
val LocalCoverField = compositionLocalOf<CoverField?> { null }

/**
 * Reports where this cover sits on screen, for the focus field to open out
 * from. Place it after the cover's own transforms (the bounds include them).
 */
@Composable
fun Modifier.reportFocusCover(): Modifier {
    val signal = LocalNowPlayingTransportSignal.current ?: return this
    return onGloballyPositioned { coords ->
        val size = coords.size
        signal.focusCoverRoot = Rect(
            coords.localToRoot(Offset.Zero),
            coords.localToRoot(Offset(size.width.toFloat(), size.height.toFloat())),
        )
    }
}

/**
 * The reactive Now Playing background (gradient lab, owner 2026-11-03). At
 * rest it is still; it only moves to answer something the user did or is
 * waiting on.
 *
 *  - **Base + rest wash** — [baseTop] → [baseBottom], with a quiet wash of the
 *    cover's own colours across the top (four soft control points, static).
 *  - **Song change** — the new song's wash flows in: from the tapped side
 *    while the cover leads (NEXT from the end, PREVIOUS from the start), with
 *    the lyric stream while the lyrics lead (next from below). It waits for
 *    the new cover's colours (up to [FlowWaitMs]). Untapped changes cross
 *    over in place.
 *  - **Lyric tap** — a wide, faint band of the cover's colours swells and
 *    settles at the tapped line's height (~1.4 s).
 *  - **Play / pause** — gather while the finger holds PLAY; on commit, rings
 *    out (play) or a bloom sinks (pause) from the tapped button.
 *  - **Ask Gemini thinking** — a pastel wash from the top in Gemini's colour
 *    run (blue and the rainbow, 80%) blended with this palette's
 *    primary / secondary / tertiary, two corner blooms, and fine dots where
 *    it fades out (as on the Pixel's Gemini overlay).
 *  - **Focus** (the cover tapped) — the whole room in the cover's colours: a
 *    16-point field opening out from the cover ([focusFieldPoints]), drifting
 *    slowly, with fine dots around the edges.
 *
 * Cost: still at rest (no frame callbacks). Song change, lyric band and the
 * bursts animate for ~1–2 s and vote a high frame rate for exactly that. The
 * Gemini wash runs per frame while thinking; the focus field is computed on
 * a ~50-cell-wide grid at ≤30 fps and upscaled with bilinear filtering. All
 * animated values are read in [drawBehind] (draw-phase invalidation only).
 *
 * @param accentColors the palette's primary, secondary and tertiary.
 * @param pulseTrigger the committed state (song id + playing flag + queue
 *   position, never the playhead), null when not playing; see [resolveTransportPulse].
 * @param focusProgress the focus (immersive) stage progress, read in draw.
 */
@Composable
fun Modifier.nowPlayingAuroraBackground(
    baseTop: Color,
    baseBottom: Color,
    accentColors: List<Color>,
    auroraActive: Boolean,
    pulseTrigger: TransportPulseKey?,
    isPlaying: Boolean,
    pressActive: Boolean,
    gatherFocalRoot: Offset?,
    burstFocalRoot: Offset?,
    transportSignal: NowPlayingTransportSignal? = null,
    coverField: CoverField? = null,
    focusProgress: () -> Float = { 0f },
): Modifier {
    val isDark = baseBottom.luminance() < 0.5f
    val primary = accentColors.getOrElse(0) { baseTop }
    val secondary = accentColors.getOrElse(1) { primary }
    val tertiary = accentColors.getOrElse(2) { secondary }
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl

    // Gemini envelope: in while thinking, out when the answer lands.
    val activeFraction by animateFloatAsState(
        targetValue = if (auroraActive) 1f else 0f,
        animationSpec = YoinMotion.slowEffectsSpec(role = YoinMotionRole.Expressive),
        label = "auroraActiveFraction",
    )
    val auroraVisible = auroraActive || activeFraction > 0.01f

    val latestFocus by rememberUpdatedState(focusProgress)
    val latestField by rememberUpdatedState(coverField)
    // Flips at the ends of the focus motion only, never per frame.
    val focusShown by remember { derivedStateOf { latestField != null && latestFocus() > 0.001f } }

    // One clock for the two things that drift: per frame while Gemini
    // thinks, ≤30 fps for the slow focus field. Continuous across restarts.
    val clock = remember { mutableFloatStateOf(0f) }
    val clockLive = auroraVisible || focusShown
    LaunchedEffect(clockLive, auroraVisible) {
        if (!clockLive) return@LaunchedEffect
        val base = clock.floatValue
        val start = withFrameMillis { it }
        var last = start
        while (true) {
            withFrameMillis { now ->
                if (auroraVisible || now - last >= FocusFrameMs) {
                    last = now
                    clock.floatValue = base + (now - start)
                }
            }
        }
    }

    val gather by animateFloatAsState(
        targetValue = if (pressActive) 1f else 0f,
        animationSpec = YoinMotion.defaultEffectsSpec(role = YoinMotionRole.Standard),
        label = "transportGather",
    )
    val burst = remember { Animatable(0f) }
    var burstIsPlay by remember { mutableStateOf(true) }
    // Latched with the commit: the player round-trips long after the tap.
    var burstFocalFrozen by remember { mutableStateOf<Offset?>(null) }

    val washTarget = remember(coverField, isDark) {
        restWashColors(coverField, isDark, fallback = listOf(primary, tertiary, secondary))
    }
    val wash = remember { RestWash(washTarget) }
    val band = remember { Animatable(0f) }
    var bandY by remember { mutableStateOf(0f) }
    // The focus field crosses over to a new cover's colours instead of
    // cutting (a song change while focused): [fieldFrom] fades out over
    // [fieldFade] while the geometry carries on.
    var fieldShown by remember { mutableStateOf(coverField) }
    var fieldFrom by remember { mutableStateOf<CoverField?>(null) }
    val fieldFade = remember { Animatable(1f) }
    // Room-sized colour change: as long as the song-change flow, not the
    // ~0.35 s slow effects spring (on device that read as a quick blink).
    val fieldFadeSpec = tween<Float>(durationMillis = FIELD_CROSS_MS, easing = FastOutSlowInEasing)
    LaunchedEffect(coverField) {
        if (coverField == fieldShown) return@LaunchedEffect
        val outgoing = fieldShown
        fieldShown = coverField
        // Out of focus nothing shows the field: just take the new one.
        if (outgoing == null || coverField == null || !focusShown) {
            fieldFrom = null
            return@LaunchedEffect
        }
        fieldFrom = outgoing
        fieldFade.snapTo(0f)
        fieldFade.animateTo(1f, fieldFadeSpec)
        fieldFrom = null
    }
    val effectMoving by remember {
        derivedStateOf {
            wash.progress.isRunning || band.isRunning || burst.isRunning || fieldFade.isRunning
        }
    }

    var originRoot by remember { mutableStateOf(Offset.Zero) }
    val focusCanvas = remember { FocusCanvas() }
    val dots = remember { DotBatch() }

    // Pulses run in this scope, not the keyed effects: the next commit must
    // not cancel one mid-sweep and leave it frozen on screen.
    val pulseScope = rememberCoroutineScope()
    var lastTrigger by remember { mutableStateOf(pulseTrigger) }
    SideEffect {
        transportSignal?.committedSongId = pulseTrigger?.songId
        transportSignal?.committedPlaying = pulseTrigger?.isPlaying == true
    }

    // The cover's colours changed: flow them in the way a pending skip asked,
    // or cross over in place.
    LaunchedEffect(washTarget) {
        if (washTarget == wash.shown) return@LaunchedEffect
        val now = SystemClock.uptimeMillis()
        val dir = wash.pendingDir?.takeIf { now - wash.pendingAtMs <= PendingFlowValidMs }
        wash.pendingDir = null
        wash.timeout?.cancel()
        // Unclaimed: a commit landing just after may still run it as its flow.
        wash.lastChangeFrom = if (dir == null) wash.shown else null
        wash.lastChangeAtMs = now
        wash.flow(pulseScope, from = wash.shown, to = washTarget, dir = dir ?: Offset.Zero)
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
        val flowDir = when (val pulse = decision.pulse) {
            TransportPulse.None -> null
            is TransportPulse.Burst -> {
                burstIsPlay = pulse.isPlay
                burstFocalFrozen = pulse.focalRoot ?: burstFocalRoot
                pulseScope.launch {
                    burst.snapTo(0f)
                    burst.animateTo(
                        targetValue = 1f,
                        animationSpec = tween(
                            durationMillis = if (pulse.isPlay) PLAY_BURST_MS else PAUSE_BURST_MS,
                            easing = FastOutSlowInEasing,
                        ),
                    )
                }
                null
            }
            is TransportPulse.SideFlow -> Offset(if (pulse.fromEnd != rtl) 1f else -1f, 0f)
            is TransportPulse.FlowLight -> Offset(0f, if (pulse.forward) 1f else -1f)
        }
        if (flowDir != null) {
            val now = SystemClock.uptimeMillis()
            val from = wash.lastChangeFrom
            if (from != null && now - wash.lastChangeAtMs <= ColoursFirstClaimMs) {
                // The colours beat the commit here: re-run that change as this flow.
                wash.lastChangeFrom = null
                wash.flow(pulseScope, from = from, to = wash.shown, dir = flowDir)
            } else {
                // Wait for the new cover's colours; flow anyway (same album) if none come.
                wash.pendingDir = flowDir
                wash.pendingAtMs = now
                wash.timeout?.cancel()
                wash.timeout = pulseScope.launch {
                    delay(FlowWaitMs)
                    if (wash.pendingDir == flowDir) {
                        wash.pendingDir = null
                        wash.flow(pulseScope, from = wash.shown, to = wash.shown, dir = flowDir)
                    }
                }
            }
        }
    }

    // A tapped lyric line: the band swells and settles at its height.
    LaunchedEffect(transportSignal) {
        val signal = transportSignal ?: return@LaunchedEffect
        snapshotFlow { signal.lineTaps }.drop(1).collect {
            bandY = signal.lineTapY
            pulseScope.launch {
                band.snapTo(0f)
                band.animateTo(1f, tween(durationMillis = LINE_BAND_MS, easing = LinearEasing))
                band.snapTo(0f)
            }
        }
    }

    val pointerModifier = if (transportSignal != null) {
        Modifier.pointerInput(transportSignal) {
            awaitPointerEventScope {
                while (true) {
                    val event = awaitPointerEvent(PointerEventPass.Initial)
                    if (event.type == PointerEventType.Press) {
                        event.changes.firstOrNull()?.let { transportSignal.recordPointerDown(it.position) }
                    }
                }
            }
        }
    } else {
        Modifier
    }

    return this
        // Short no-touch motions (the finger has lifted): vote High for
        // exactly their run, so an adaptive panel doesn't pace them at 60Hz.
        .voteHighFrameRate(effectMoving)
        .onGloballyPositioned { originRoot = it.positionInRoot() }
        .then(pointerModifier)
        .drawBehind {
            val w = size.width
            val h = size.height
            if (w <= 0f || h <= 0f) return@drawBehind
            val blend = if (isDark) BlendMode.Screen else BlendMode.SrcOver
            val t = clock.floatValue

            drawRect(Brush.verticalGradient(listOf(baseTop, baseBottom)))

            // Rest wash, or a song change flowing in.
            val e = wash.progress.value
            val shown = wash.shown
            val previousWash = wash.previous
            if (e >= 1f || previousWash == null) {
                drawRestWash(shown, 0f, 0f, 1f, isDark, blend)
            } else if (wash.dir == Offset.Zero) {
                drawRestWash(previousWash, 0f, 0f, 1f - e, isDark, blend)
                drawRestWash(shown, 0f, 0f, e, isDark, blend)
            } else {
                val travel = (if (wash.dir.x != 0f) w else h) * 0.9f
                drawRestWash(previousWash, -wash.dir.x * travel * e, -wash.dir.y * travel * e, 1f - e, isDark, blend)
                drawRestWash(
                    shown,
                    wash.dir.x * travel * (1f - e),
                    wash.dir.y * travel * (1f - e),
                    min(1f, e * 1.4f),
                    isDark,
                    blend,
                )
            }

            // Focus: the whole room in the cover's colours.
            val field = fieldShown
            val f = latestFocus().coerceIn(0f, 1f)
            if (field != null && f > 0.001f) {
                val cover = transportSignal?.focusCoverRoot?.translate(-originRoot)
                    ?: Rect(center = Offset(w * 0.5f, h * 0.33f), radius = w * 0.3f)
                fun pointsOf(source: CoverField) = focusFieldPoints(
                    field = source,
                    dark = isDark,
                    width = w,
                    height = h,
                    coverCenterX = cover.center.x,
                    coverCenterY = cover.center.y,
                    coverSize = min(cover.width, cover.height).coerceAtLeast(w * 0.3f),
                    spread = smoothstep(f),
                    timeMs = t,
                )
                val incoming = pointsOf(field)
                val outgoing = fieldFrom
                val fade = fieldFade.value
                val points = if (outgoing != null && fade < 1f) {
                    crossFieldColors(pointsOf(outgoing), incoming, fade)
                } else {
                    incoming
                }
                val image = focusCanvas.render(points, baseBottom.toArgb(), w, h)
                drawImage(
                    image = image,
                    srcSize = IntSize(image.width, image.height),
                    dstSize = IntSize(w.roundToInt(), h.roundToInt()),
                    alpha = min(1f, f * 1.25f),
                    filterQuality = FilterQuality.Low,
                )
                if (f > 0.4f) drawEdgeDots(dots, points.wandererColor(), isDark, t, min(1f, (f - 0.4f) / 0.5f))
            }

            // Ask Gemini thinking.
            val frac = activeFraction
            if (frac > 0.001f) drawGeminiWash(dots, t, frac, primary, secondary, tertiary, isDark, blend)

            // A tapped lyric line.
            val b = band.value
            if (b > 0.001f) drawLineBand(shown, bandY, b, isDark, blend)

            // Play / pause: gather while held, then ring out or sink.
            val cap = if (isDark) PULSE_ALPHA_DARK else PULSE_ALPHA_LIGHT
            val playTint = Color.hsl(hueOf(primary), if (isDark) 0.75f else 0.85f, if (isDark) 0.6f else 0.62f)
            val pauseTint = Color.hsl(hueOf(tertiary), if (isDark) 0.6f else 0.7f, if (isDark) 0.6f else 0.62f)
            val fallback = Offset(w * 0.5f, h * 0.62f)
            val g = gather
            if (g > 0.001f) {
                val focal = gatherFocalRoot?.minus(originRoot) ?: fallback
                drawBloom(focal, w * (0.55f - 0.25f * g), if (isPlaying) pauseTint else playTint, cap * 1.1f * g, blend)
            }
            val sweep = burst.value
            if (sweep > 0.001f) {
                val focal = burstFocalFrozen?.minus(originRoot) ?: fallback
                val env = sin(PI.toFloat() * sweep)
                if (burstIsPlay) {
                    val center = Offset(focal.x, focal.y - h * 0.05f * sweep)
                    for (r in 0 until 2) {
                        val radius = h * (0.1f + 0.5f * sweep + 0.13f * r)
                        drawCircle(
                            brush = Brush.radialGradient(
                                0f to Color.Transparent,
                                0.72f to Color.Transparent,
                                0.88f to playTint.copy(alpha = cap * 1.2f * env * (1f - 0.35f * r)),
                                1f to Color.Transparent,
                                center = center,
                                radius = radius,
                            ),
                            radius = radius,
                            center = center,
                            blendMode = blend,
                        )
                    }
                } else {
                    drawBloom(
                        Offset(focal.x, focal.y + h * 0.05f * sweep),
                        h * (0.42f - 0.22f * sweep),
                        pauseTint,
                        cap * 1.1f * env,
                        blend,
                    )
                }
            }
        }
}

/** The rest wash's three colours, lifted toward the page (k1, k2, k3 in the lab). */
private data class WashColors(val k1: Color, val k2: Color, val k3: Color)

private fun restWashColors(field: CoverField?, dark: Boolean, fallback: List<Color>): WashColors {
    val (a, b, c) = if (field != null) {
        Triple(Color(field.palette[0]), Color(field.palette[1]), Color(field.palette[2]))
    } else {
        Triple(fallback[0], fallback[1], fallback[2])
    }
    return WashColors(lift(a, dark), lift(b, dark), lift(c, dark))
}

private fun lift(color: Color, dark: Boolean): Color = if (dark) {
    Color(
        red = min(1f, color.red * 1.15f + 25f / 255f),
        green = min(1f, color.green * 1.15f + 25f / 255f),
        blue = min(1f, color.blue * 1.15f + 25f / 255f),
    )
} else {
    Color(
        red = color.red * 0.55f + 0.45f,
        green = color.green * 0.55f + 0.45f,
        blue = color.blue * 0.55f + 0.45f,
    )
}

/** The rest wash and its song-change flow. */
@Stable
private class RestWash(initial: WashColors) {
    var shown by mutableStateOf(initial)
        private set
    var previous by mutableStateOf<WashColors?>(null)
        private set
    var dir by mutableStateOf(Offset.Zero)
        private set
    val progress = Animatable(1f)

    // A flow asked for by a commit, waiting for the new cover's colours.
    var pendingDir: Offset? = null
    var pendingAtMs = 0L
    var timeout: Job? = null

    // The last colour change, which a commit landing just after may claim.
    var lastChangeFrom: WashColors? = null
    var lastChangeAtMs = 0L
    private var job: Job? = null

    fun flow(scope: kotlinx.coroutines.CoroutineScope, from: WashColors, to: WashColors, dir: Offset) {
        previous = from
        shown = to
        this.dir = dir
        job?.cancel()
        job = scope.launch {
            progress.snapTo(0f)
            progress.animateTo(1f, tween(durationMillis = WASH_FLOW_MS, easing = EaseOutCubic))
        }
    }
}

private fun DrawScope.drawBloom(center: Offset, radius: Float, color: Color, alpha: Float, blend: BlendMode) {
    if (radius <= 0f || alpha <= 0.001f) return
    drawCircle(
        brush = Brush.radialGradient(
            listOf(color.copy(alpha = alpha.coerceIn(0f, 1f)), color.copy(alpha = 0f)),
            center = center,
            radius = radius,
        ),
        radius = radius,
        center = center,
        blendMode = blend,
    )
}

private fun DrawScope.drawRestWash(
    colors: WashColors,
    dx: Float,
    dy: Float,
    alpha: Float,
    dark: Boolean,
    blend: BlendMode,
) {
    if (alpha <= 0.001f) return
    val w = size.width
    val h = size.height
    val a = (if (dark) 0.42f else 0.34f) * alpha.coerceIn(0f, 1f)
    fun point(color: Color, x: Float, y: Float, r: Float) {
        val center = Offset(x * w + dx, y * h + dy)
        val radius = r * w
        drawCircle(
            brush = Brush.radialGradient(
                0f to color.copy(alpha = a),
                0.6f to color.copy(alpha = a * 0.4f),
                1f to color.copy(alpha = 0f),
                center = center,
                radius = radius,
            ),
            radius = radius,
            center = center,
            blendMode = blend,
        )
    }
    point(colors.k1, 0.12f, 0.06f, 0.85f)
    point(colors.k3, 0.86f, 0.10f, 0.70f)
    point(colors.k2, 0.52f, 0.30f, 0.75f)
    point(colors.k1, 0.95f, 0.42f, 0.50f)
}

/** Two wide, faint ellipses of the cover's colours at the tapped line. */
private fun DrawScope.drawLineBand(colors: WashColors, y: Float, b: Float, dark: Boolean, blend: BlendMode) {
    val w = size.width
    val env = sin(PI.toFloat() * b).pow(1.5f)
    val grow = 0.85f + 0.15f * (1f - (1f - b).pow(3))
    // A touch denser than the lab's .34 / .26 (owner 2026-11-03: "可以稍微浓一点点").
    val a = (if (dark) 0.42f else 0.34f) * env
    val radius = w * 0.36f * grow
    listOf(Triple(colors.k1, -0.18f, 1f), Triple(colors.k3, 0.22f, 0.8f)).forEach { (color, offset, m) ->
        val center = Offset(w * (0.5f + offset), y)
        withTransform({ scale(scaleX = 2.6f * grow, scaleY = 1f, pivot = center) }) {
            drawCircle(
                brush = Brush.radialGradient(
                    0f to color.copy(alpha = a * m),
                    0.55f to color.copy(alpha = a * m * 0.45f),
                    1f to color.copy(alpha = 0f),
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

/**
 * Gemini thinking: a pastel wash from the top whose hue runs round
 * primary → Gemini blue → secondary → tertiary, pulled 80% toward a slow
 * rainbow; two corner blooms a step further round; fine dots where it fades.
 */
private fun DrawScope.drawGeminiWash(
    dots: DotBatch,
    t: Float,
    frac: Float,
    primary: Color,
    secondary: Color,
    tertiary: Color,
    dark: Boolean,
    blend: BlendMode,
) {
    val w = size.width
    val h = size.height
    val ring = floatArrayOf(hueOf(primary), GEMINI_BLUE_HUE, hueOf(secondary), hueOf(tertiary))
    fun ringHue(phase: Float): Float {
        val x = ((phase % 4f) + 4f) % 4f
        val i = floor(x).toInt()
        return lerpHue(ring[i], ring[(i + 1) % 4], smoothstep(x - i))
    }
    val phase = t / 2600f
    val rainbow = (t / 7000f * 360f + 200f) % 360f
    val hueA = lerpHue(ringHue(phase), rainbow, GEMINI_RAINBOW_MIX)
    val hueB = lerpHue(ringHue(phase + 1.3f), rainbow + 70f, GEMINI_RAINBOW_MIX)
    val hueC = lerpHue(ringHue(phase + 2.6f), rainbow + 150f, GEMINI_RAINBOW_MIX)
    val s = if (dark) 0.78f else 0.95f
    val l = if (dark) 0.52f else 0.82f
    val a = (if (dark) 0.62f else 0.92f) * frac.coerceIn(0f, 1f)
    fun hsl(hue: Float, alpha: Float, lightness: Float = l) =
        Color.hsl(normHue(hue), s, lightness, alpha.coerceIn(0f, 1f))

    val depth = h * (0.22f + 0.38f * smoothstep(min(1f, frac)))
    val bottom = depth + h * 0.12f
    drawRect(
        brush = Brush.verticalGradient(
            0f to hsl(hueA, a),
            0.55f to hsl(hueA, a * 0.45f),
            1f to hsl(hueA, 0f),
            startY = 0f,
            endY = bottom,
        ),
        size = Size(w, bottom),
        blendMode = blend,
    )
    val dx = sin(t / 3100f) * w * 0.08f
    drawBloom(Offset(w * 0.12f + dx, h * 0.04f), w * 0.78f, hsl(hueB, 1f), a * 0.85f, blend)
    drawBloom(Offset(w * 0.92f - dx, h * 0.10f), w * 0.70f, hsl(hueC, 1f), a * 0.75f, blend)

    // Fine dots where the wash fades out.
    val dp = density
    val grid = 6f * dp
    val y0 = depth * 0.55f
    val y1 = depth + h * 0.14f
    val rMax = 1.3f * dp
    dots.reset()
    var y = y0
    var row = 0
    while (y < y1) {
        val k = 1f - (y - y0) / (y1 - y0)
        val kk = k.pow(1.2f)
        var x = if (row and 1 == 1) grid / 2f else 0f
        while (x < w) {
            val n = 0.5f + 0.5f * sin(x / dp * 0.045f + t / 900f) * cos(y / dp * 0.05f - t / 1300f)
            val r = rMax * kk * (0.35f + 0.65f * n)
            if (r >= 0.3f * dp) dots.add(x, y, r, rMax)
            x += grid
        }
        y += grid
        row++
    }
    val dotColor = hsl(hueB, (if (dark) 0.55f else 0.45f) * frac, if (dark) 0.7f else 0.62f)
    dots.draw(drawContext.canvas.nativeCanvas, dotColor.toArgb(), rMax)
    drawBloom(Offset(w * 0.5f, h * 0.95f), w * 0.6f, hsl(hueB, 1f), a * 0.35f, blend)
}

/** Focus: fine dots around the room's edges, in the wanderer's colour. */
private fun DrawScope.drawEdgeDots(dots: DotBatch, wanderer: Int, dark: Boolean, t: Float, fade: Float) {
    val w = size.width
    val h = size.height
    val dp = density
    val grid = 6f * dp
    val band = 34f * dp
    val topShift = 40f * dp
    val rMax = 1.25f * dp
    dots.reset()
    var y = 0f
    var row = 0
    while (y < h) {
        val rowEdge = min(h - y, y + topShift)
        var x = if (row and 1 == 1) grid / 2f else 0f
        while (x < w) {
            val d = min(min(x, w - x), rowEdge)
            if (d <= band) {
                val n = 0.5f + 0.5f * sin(x / dp * 0.07f + y / dp * 0.045f + t / 1500f)
                val r = rMax * (1f - d / band).pow(1.3f) * (0.3f + 0.7f * n)
                if (r >= 0.28f * dp) dots.add(x, y, r, rMax)
            } else if (rowEdge > band && x < w - band) {
                // Skip the room's middle in one step.
                x = w - band - ((w - band - x) % grid)
            }
            x += grid
        }
        y += grid
        row++
    }
    val c = Color(wanderer)
    val dotColor = if (dark) {
        val lift = 40f / 255f
        Color(min(1f, c.red * 1.5f + lift), min(1f, c.green * 1.5f + lift), min(1f, c.blue * 1.5f + lift))
    } else {
        Color(c.red * 0.55f, c.green * 0.55f, c.blue * 0.55f)
    }
    val alpha = (if (dark) 0.5f else 0.35f) * fade
    dots.draw(drawContext.canvas.nativeCanvas, dotColor.copy(alpha = alpha).toArgb(), rMax)
}

/** Fine dots, batched by size into a few round-capped point draws. */
private class DotBatch {
    private val xy = Array(DotBuckets) { FloatArray(512) }
    private val counts = IntArray(DotBuckets)
    private val paint = android.graphics.Paint().apply {
        isAntiAlias = true
        strokeCap = android.graphics.Paint.Cap.ROUND
    }

    fun reset() = counts.fill(0)

    fun add(x: Float, y: Float, r: Float, rMax: Float) {
        val b = ((r / rMax) * DotBuckets).toInt().coerceIn(0, DotBuckets - 1)
        val n = counts[b]
        if (n * 2 + 2 > xy[b].size) xy[b] = xy[b].copyOf(xy[b].size * 2)
        xy[b][n * 2] = x
        xy[b][n * 2 + 1] = y
        counts[b] = n + 1
    }

    fun draw(canvas: android.graphics.Canvas, argb: Int, rMax: Float) {
        paint.color = argb
        for (b in 0 until DotBuckets) {
            if (counts[b] == 0) continue
            paint.strokeWidth = 2f * rMax * (b + 0.5f) / DotBuckets
            canvas.drawPoints(xy[b], 0, counts[b] * 2, paint)
        }
    }
}

/** The focus field's low-res canvas: rendered on the CPU, drawn upscaled. */
private class FocusCanvas {
    private var bitmap: Bitmap? = null
    private var image: ImageBitmap? = null
    private var pixels = IntArray(0)
    private var lastKey: List<Any>? = null

    fun render(points: List<FieldPoint>, base: Int, w: Float, h: Float): ImageBitmap {
        val gw = FOCUS_GRID_WIDTH
        val gh = (gw * h / w).roundToInt().coerceIn(8, 240)
        val current = bitmap
        val target = if (current == null || current.width != gw || current.height != gh) {
            Bitmap.createBitmap(gw, gh, Bitmap.Config.ARGB_8888).also {
                bitmap = it
                image = it.asImageBitmap()
                pixels = IntArray(gw * gh)
                lastKey = null
            }
        } else {
            current
        }
        // Redraws for other reasons (a press, a seek) reuse the last field.
        val key = listOf(points, base, w, h)
        if (key != lastKey) {
            renderFocusField(points, base, w, h, gw, gh, pixels)
            target.setPixels(pixels, 0, gw, 0, 0, gw, gh)
            lastKey = key
        }
        return image!!
    }
}

private fun hueOf(color: Color): Float {
    val hsl = FloatArray(3)
    argbToHsl(color.toArgb(), hsl)
    return hsl[0]
}

private fun lerpHue(a: Float, b: Float, t: Float): Float {
    val d = (((b - a + 540f) % 360f) + 360f) % 360f - 180f
    return a + d * t
}

private fun normHue(h: Float): Float = ((h % 360f) + 360f) % 360f

private fun smoothstep(x: Float): Float {
    val c = x.coerceIn(0f, 1f)
    return c * c * (3f - 2f * c)
}

/** A finger-down older than this isn't the line a seek came from. */
private const val LineTapFingerValidityMs = 1_500L

// Play / pause bursts: brighter than before (gradient lab), still soft.
private const val PULSE_ALPHA_DARK = 0.42f
private const val PULSE_ALPHA_LIGHT = 0.34f
private const val PLAY_BURST_MS = 1900
private const val PAUSE_BURST_MS = 2200

private const val LINE_BAND_MS = 1400
private const val WASH_FLOW_MS = 820
private const val FIELD_CROSS_MS = 900

/** How long a commit waits for the new cover's colours before flowing anyway. */
private const val FlowWaitMs = 700L

/** A commit's flow still takes the new cover's colours this long after it. */
private const val PendingFlowValidMs = 1_500L

/** Colours that changed this shortly before a commit are that commit's song change. */
private const val ColoursFirstClaimMs = 400L

private const val FocusFrameMs = 32L
private const val FOCUS_GRID_WIDTH = 50
private const val DotBuckets = 4

private const val GEMINI_BLUE_HUE = 217f
private const val GEMINI_RAINBOW_MIX = 0.8f
