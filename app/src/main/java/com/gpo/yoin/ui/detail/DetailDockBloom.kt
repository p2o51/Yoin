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
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.lerp
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.util.lerp
import coil3.ImageLoader
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.request.allowHardware
import coil3.toBitmap
import com.gpo.yoin.AppContainer
import com.gpo.yoin.MainActivity
import com.gpo.yoin.R
import com.gpo.yoin.ui.theme.YoinMotion
import kotlin.math.roundToInt
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Dock → fullscreen Now Playing bloom.
 *
 * The old open choreography detoured through home: the detail window
 * dissolved, the shell's Button Group appeared, and only THEN did NP rise
 * from the bar — reading as "go home first, then take off" (the user's
 * words: 特别好笑). This is the direct cut instead:
 *
 *  1. BLOOM — on dock tap, the playing cover swells from the dock's rect to
 *     a full-window center-crop inside THIS window, swallowing the page.
 *  2. REVEAL — mid-bloom the shell is started with NP pre-expanded via
 *     [MainActivity.EXTRA_EXPAND_NOW_PLAYING_INSTANT]; being stopped, its
 *     stage transition completes invisibly, so the window crossfade
 *     (np_bloom_*) dissolves the fullscreen cover straight into a settled
 *     player. Home is never seen.
 */
@Stable
class DockBloomState internal constructor(internal val scrimColor: Color) {
    internal val progress = Animatable(0f)

    /** Window bounds of the dock — the bloom's launch pad. */
    var dockBounds: Rect? = null

    internal var overlayOrigin by mutableStateOf(Offset.Zero)
    internal var active by mutableStateOf(false)
    internal var coverBitmap by mutableStateOf<ImageBitmap?>(null)
}

@Composable
fun rememberDockBloom(coverArtUrl: String?): DockBloomState {
    val scrim = MaterialTheme.colorScheme.surfaceContainerHighest
    val state = remember { DockBloomState(scrim) }
    val context = LocalContext.current
    // Prefetch at bloom resolution whenever the playing cover changes — the
    // tap must not wait on a network round-trip (disk cache makes this a
    // no-op re-decode in practice).
    LaunchedEffect(coverArtUrl) {
        state.coverBitmap = null
        if (coverArtUrl == null) return@LaunchedEffect
        val result = ImageLoader(context).execute(
            ImageRequest.Builder(context)
                .data(coverArtUrl)
                .allowHardware(false)
                .size(coil3.size.Size(1024, 1024))
                .build(),
        )
        if (result is SuccessResult) {
            state.coverBitmap = result.image.toBitmap().asImageBitmap()
        }
    }
    return state
}

/**
 * Play the bloom and open the shell revealing a settled Now Playing. Fired
 * from the dock tap; the shell start happens mid-bloom so the window
 * crossfade begins right as the cover fills the screen.
 */
suspend fun DockBloomState.bloomIntoNowPlaying(
    context: Context,
    container: AppContainer,
) = coroutineScope {
    if (active) return@coroutineScope
    active = true
    launch {
        snapshotFlow { progress.value }.first { it >= 0.7f }
        // Pre-set (not request/stagger): the shell must compose with NP
        // ALREADY settled under the dissolving cover — its transition
        // finishing invisibly while stopped is the point here.
        val intent = Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            .putExtra(MainActivity.EXTRA_EXPAND_NOW_PLAYING_INSTANT, true)
        val options = ActivityOptions.makeCustomAnimation(
            context,
            R.anim.np_bloom_enter,
            R.anim.np_bloom_exit,
        )
        context.startActivity(intent, options.toBundle())
    }
    progress.animateTo(1f, YoinMotion.stageSettleSpring())
}

/** Marks the dock as the bloom's launch pad (reports its window bounds). */
fun Modifier.dockBloomSource(state: DockBloomState?): Modifier =
    if (state == null) {
        this
    } else {
        onGloballyPositioned { state.dockBounds = it.boundsInWindow() }
    }

/**
 * Draws the blooming cover over the page. Attach to the Activity's full-size
 * root Box — draw modifiers paint after children, so the bloom covers the
 * whole page including the toolbar row and the dock itself.
 */
fun Modifier.dockBloomOverlay(state: DockBloomState?): Modifier {
    if (state == null) return this
    return this
        .onGloballyPositioned { state.overlayOrigin = it.boundsInWindow().topLeft }
        .drawWithContent {
            drawContent()
            if (!state.active) return@drawWithContent
            val from = state.dockBounds ?: return@drawWithContent
            val p = state.progress.value
            // Full-window center-crop square: the cover ZOOMS to fill, no
            // letterboxing frame appearing mid-flight.
            val side = maxOf(size.width, size.height)
            val target = Rect(
                Offset((size.width - side) / 2f, (size.height - side) / 2f),
                Size(side, side),
            )
            val fromLocal = from.translate(-state.overlayOrigin.x, -state.overlayOrigin.y)
            val rect = lerp(fromLocal, target, p)
            val corner = lerp(fromLocal.minDimension * DetailDockCornerRatio, 0f, p)
            val cover = state.coverBitmap
            if (cover != null) {
                val clip = Path().apply {
                    addRoundRect(RoundRect(rect, CornerRadius(corner)))
                }
                clipPath(clip) {
                    drawImage(
                        image = cover,
                        dstOffset = IntOffset(rect.left.roundToInt(), rect.top.roundToInt()),
                        dstSize = IntSize(
                            rect.width.roundToInt().coerceAtLeast(1),
                            rect.height.roundToInt().coerceAtLeast(1),
                        ),
                    )
                }
            } else {
                drawRoundRect(
                    color = state.scrimColor,
                    topLeft = rect.topLeft,
                    size = rect.size,
                    cornerRadius = CornerRadius(corner),
                )
            }
        }
}
