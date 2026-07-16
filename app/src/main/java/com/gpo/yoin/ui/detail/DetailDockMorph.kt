package com.gpo.yoin.ui.detail

import android.app.ActivityOptions
import android.content.Context
import android.content.Intent
import androidx.compose.animation.core.Animatable
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.lerp
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.lerp
import coil3.ImageLoader
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.request.allowHardware
import coil3.size.Size
import coil3.toBitmap
import com.gpo.yoin.AppContainer
import com.gpo.yoin.R
import com.gpo.yoin.ui.theme.YoinMotion
import kotlin.math.roundToInt
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Button Group → mini-player dock morph for shell → detail opens.
 *
 * Detail pages are separate Activities, so a real shared element can't cross
 * the window boundary. Instead the morph is a hand-off in TWO beats:
 *
 *  1. HOLD — the detail window fades in (R.anim.detail_dock_handoff_*, a
 *     stationary 240ms crossfade) while this page draws a pill at the bar's
 *     exact window bounds, in the bar's exact rendered color, carrying the
 *     playing track's cover at the bar artwork's exact spot. The real bar
 *     sits untouched beneath the opaque-growing window, so the crossfade
 *     reads as one pill whose buttons dissolve — the bar itself never hides.
 *  2. SQUISH — only after the page has settled does the pill spring down to
 *     the dock, the cover riding into the dock's artwork slot, and the dock's
 *     ring fades in as the pill lands.
 *
 * Both windows are full-screen and edge-to-edge, so window coordinates
 * transfer 1:1. Close is untouched everywhere — the system's native
 * cross-Activity predictive back stays exactly as it was.
 */
data class DockMorphSource(
    val bounds: Rect,
    val cornerPx: Float,
    // The bar pill's RENDERED color at hand-off time. The detail theme starts
    // un-washed (its cover-seeded scheme resolves a beat later), so reading
    // surfaceContainerHigh on the detail side would both mismatch the bar at
    // the crossfade and re-tint the pill mid-flight.
    val color: Color,
    // Window bounds of the bar's mini artwork — the cover's flight origin.
    val artBounds: Rect?,
)

private const val EXTRA_PREFIX = "dockMorph."
private const val EXTRA_LEFT = EXTRA_PREFIX + "left"
private const val EXTRA_TOP = EXTRA_PREFIX + "top"
private const val EXTRA_RIGHT = EXTRA_PREFIX + "right"
private const val EXTRA_BOTTOM = EXTRA_PREFIX + "bottom"
private const val EXTRA_CORNER = EXTRA_PREFIX + "corner"
private const val EXTRA_COLOR = EXTRA_PREFIX + "color"
private const val EXTRA_ART_LEFT = EXTRA_PREFIX + "artLeft"
private const val EXTRA_ART_TOP = EXTRA_PREFIX + "artTop"
private const val EXTRA_ART_RIGHT = EXTRA_PREFIX + "artRight"
private const val EXTRA_ART_BOTTOM = EXTRA_PREFIX + "artBottom"

// Beat 1: how long the pill holds at the bar bounds. Must cover the 240ms
// window crossfade (detail_dock_handoff_enter) so the squish never plays over
// two blended pages.
private const val PILL_HOLD_MS = 280L

// If the dock hasn't reached a real size by then (page still Loading, nothing
// ends up playing, load error), give up waiting and fade the pill out in
// place instead of parking a bar-shaped ghost over the page.
private const val TARGET_WAIT_TIMEOUT_MS = 900L

// The bar mini artwork's corner (YoinArtworkShapes.ThumbAnimated) — the cover's
// flight starts with this rounding and lands on the dock artwork's 24% rounding.
private val BarArtCorner = 4.dp

/**
 * Launch a detail Activity from the shell, morphing the Button Group into the
 * detail page's mini-player dock when the shell armed a hand-off (bar visible,
 * something playing). Falls back to a plain [Context.startActivity] — i.e.
 * the system's default open transition — whenever the morph doesn't apply.
 */
fun launchDetailFromShell(
    context: Context,
    container: AppContainer,
    intent: Intent,
) {
    val store = container.experienceSessionStore
    val pill = store.navPill
    if (!store.consumeDockHandoff() || pill == null || pill.bounds.width <= 0f) {
        context.startActivity(intent)
        return
    }
    val cornerPx = 28f * context.resources.displayMetrics.density
    intent
        .putExtra(EXTRA_LEFT, pill.bounds.left)
        .putExtra(EXTRA_TOP, pill.bounds.top)
        .putExtra(EXTRA_RIGHT, pill.bounds.right)
        .putExtra(EXTRA_BOTTOM, pill.bounds.bottom)
        .putExtra(EXTRA_CORNER, cornerPx)
        .putExtra(EXTRA_COLOR, pill.color.toArgb())
    store.navPillArtBounds?.let { art ->
        intent
            .putExtra(EXTRA_ART_LEFT, art.left)
            .putExtra(EXTRA_ART_TOP, art.top)
            .putExtra(EXTRA_ART_RIGHT, art.right)
            .putExtra(EXTRA_ART_BOTTOM, art.bottom)
    }
    val options = ActivityOptions.makeCustomAnimation(
        context,
        R.anim.detail_dock_handoff_enter,
        R.anim.detail_dock_handoff_exit,
    )
    context.startActivity(intent, options.toBundle())
}

/**
 * Read a hand-off out of a detail Activity's launch intent. Call with
 * `savedInstanceState == null` only — a recreated Activity must not replay
 * the entrance morph.
 */
fun readDockMorphHandoff(intent: Intent): DockMorphSource? {
    if (!intent.hasExtra(EXTRA_LEFT)) return null
    return DockMorphSource(
        bounds = Rect(
            intent.getFloatExtra(EXTRA_LEFT, 0f),
            intent.getFloatExtra(EXTRA_TOP, 0f),
            intent.getFloatExtra(EXTRA_RIGHT, 0f),
            intent.getFloatExtra(EXTRA_BOTTOM, 0f),
        ),
        cornerPx = intent.getFloatExtra(EXTRA_CORNER, 0f),
        color = Color(intent.getIntExtra(EXTRA_COLOR, 0)),
        artBounds = if (intent.hasExtra(EXTRA_ART_LEFT)) {
            Rect(
                intent.getFloatExtra(EXTRA_ART_LEFT, 0f),
                intent.getFloatExtra(EXTRA_ART_TOP, 0f),
                intent.getFloatExtra(EXTRA_ART_RIGHT, 0f),
                intent.getFloatExtra(EXTRA_ART_BOTTOM, 0f),
            )
        } else {
            null
        },
    )
}

/**
 * Entrance-morph state for one detail Activity composition. [progress] is the
 * single driver: the pill overlay reads it in the draw phase and the dock
 * content reads it in the layer phase, so animation frames never recompose.
 */
@Stable
class DetailDockMorphState internal constructor(
    internal val source: DockMorphSource,
) {
    internal val progress = Animatable(0f)

    /** Live window bounds of the dock slot; the pill's target. */
    internal var dockBounds by mutableStateOf<Rect?>(null)

    /** Window origin of the overlay host, to convert window → local coords. */
    internal var overlayOrigin by mutableStateOf(Offset.Zero)

    /**
     * Set when the dock never materialized in time. The pill then ignores any
     * late-arriving dock bounds and fades out on the spot — morphing onto a
     * target that appears mid-fade would read as a direction change.
     */
    internal var aborted by mutableStateOf(false)

    /** The playing track's cover, riding inside the pill during the flight. */
    internal var coverArt by mutableStateOf<ImageBitmap?>(null)
}

@Composable
fun rememberDetailDockMorph(
    source: DockMorphSource?,
    coverArtUrl: String?,
): DetailDockMorphState? {
    if (source == null) return null
    val state = remember { DetailDockMorphState(source) }
    val context = LocalContext.current
    // Entrance-scoped: the URL present at first composition is the track the
    // bar was showing; a mid-morph track change should not swap the bitmap.
    val entranceCoverUrl = remember { coverArtUrl }
    LaunchedEffect(state) {
        // Fetch the cover during the hold beat (memory/disk cache hit — the
        // dock is loading the same image). Loaded lazily off-main by Coil.
        if (source.artBounds != null && entranceCoverUrl != null) {
            launch {
                val result = ImageLoader(context).execute(
                    ImageRequest.Builder(context)
                        .data(entranceCoverUrl)
                        .allowHardware(false)
                        .size(Size(240, 240))
                        .build(),
                )
                if (result is SuccessResult) {
                    state.coverArt = result.image.toBitmap().asImageBitmap()
                }
            }
        }
        // Beat 1 (hold) runs concurrently with waiting for the dock to have
        // real bounds; beat 2 (squish) starts only when both are done.
        val hold = launch { delay(PILL_HOLD_MS) }
        val target = withTimeoutOrNull(TARGET_WAIT_TIMEOUT_MS) {
            snapshotFlow { state.dockBounds }
                .filterNotNull()
                .first { it.width > 1f && it.height > 1f }
        }
        if (target == null) {
            // No dock to land on — same spring, but the pill's target pins to
            // its own source rect, so it dissolves in place (the alpha window
            // near progress 1 does the fading) instead of hard-cutting.
            state.aborted = true
        }
        hold.join()
        // Same fast, near-critical spring as the NP stage reshape: a
        // container transform should settle without overshoot.
        state.progress.animateTo(1f, YoinMotion.stageSettleSpring())
    }
    return state
}

/**
 * Marks the mini-player dock as the morph target: reports its window bounds
 * and unveils its content right as the pill lands on it (the pill's cover and
 * the dock's cover occupy the same rect at that moment, so the swap is
 * invisible).
 */
fun Modifier.dockMorphTarget(state: DetailDockMorphState?): Modifier {
    if (state == null) return this
    return this
        .onGloballyPositioned { state.dockBounds = it.boundsInWindow() }
        .graphicsLayer {
            alpha = if (state.aborted) 1f else smoothstep(0.80f, 1f, state.progress.value)
        }
}

/**
 * Draws the morphing pill (container + riding cover) over the page content.
 * Attach to the screen's full-size root Box. The pill wears the EXACT color
 * the shell bar rendered with at hand-off ([DockMorphSource.color]) for its
 * whole flight, so the cross-window fade reads as one continuous surface even
 * while the detail theme's cover wash is still resolving.
 */
fun Modifier.dockMorphOverlay(state: DetailDockMorphState?): Modifier {
    if (state == null) return this
    return this
        .onGloballyPositioned { state.overlayOrigin = it.boundsInWindow().topLeft }
        .drawWithContent {
            drawContent()
            val p = state.progress.value
            val pillAlpha = 1f - smoothstep(0.75f, 1f, p)
            val coverAlpha = 1f - smoothstep(0.85f, 1f, p)
            if (coverAlpha <= 0.001f) return@drawWithContent
            // Until the dock is measured (or if it never shows up), the
            // target pins to the source rect: the pill is the visual
            // continuation of the bar beneath the window crossfade, and an
            // aborted morph dissolves right there.
            val target = if (state.aborted) {
                state.source.bounds
            } else {
                state.dockBounds?.takeIf { it.width > 1f } ?: state.source.bounds
            }
            val rect = lerp(state.source.bounds, target, p)
                .translate(-state.overlayOrigin.x, -state.overlayOrigin.y)
            val corner = lerp(
                state.source.cornerPx,
                target.minDimension * DetailDockCornerRatio,
                p,
            )
            if (pillAlpha > 0.001f) {
                drawRoundRect(
                    color = state.source.color,
                    topLeft = rect.topLeft,
                    size = rect.size,
                    cornerRadius = CornerRadius(corner),
                    alpha = pillAlpha,
                )
            }

            // The cover rides from the bar artwork's rect into the dock's
            // artwork slot, its rounding easing from the bar token to the
            // dock's. It outlives the pill fill slightly, handing off to the
            // dock's own (identical) cover as the ring fades in.
            val cover = state.coverArt ?: return@drawWithContent
            val artFrom = state.source.artBounds ?: return@drawWithContent
            val artTo = if (state.aborted) {
                artFrom
            } else {
                val dockRect = state.dockBounds?.takeIf { it.width > 1f }
                dockRect?.deflate(DetailDockArtInset.toPx()) ?: artFrom
            }
            val artRect = lerp(artFrom, artTo, p)
                .translate(-state.overlayOrigin.x, -state.overlayOrigin.y)
            val artCorner = lerp(
                BarArtCorner.toPx(),
                artTo.minDimension * DetailDockArtCornerRatio,
                p,
            )
            val clip = Path().apply {
                addRoundRect(RoundRect(artRect, CornerRadius(artCorner)))
            }
            clipPath(clip) {
                drawImage(
                    image = cover,
                    dstOffset = IntOffset(
                        artRect.left.roundToInt(),
                        artRect.top.roundToInt(),
                    ),
                    dstSize = IntSize(
                        artRect.width.roundToInt().coerceAtLeast(1),
                        artRect.height.roundToInt().coerceAtLeast(1),
                    ),
                    alpha = coverAlpha,
                )
            }
        }
}

private fun smoothstep(edge0: Float, edge1: Float, x: Float): Float {
    val t = ((x - edge0) / (edge1 - edge0)).coerceIn(0f, 1f)
    return t * t * (3f - 2f * t)
}
