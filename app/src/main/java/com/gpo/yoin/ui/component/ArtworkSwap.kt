package com.gpo.yoin.ui.component

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import coil3.compose.AsyncImage
import coil3.compose.AsyncImagePainter
import coil3.decode.DataSource
import coil3.request.ImageRequest
import coil3.request.crossfade
import com.gpo.yoin.ui.theme.YoinMotion

internal enum class ArtworkReveal { Crossfade, DotDissolve }

/** Keep the decoded painter alive; a remembered URL cannot survive cache eviction. */
@Composable
internal fun ArtworkSwap(
    model: String,
    contentDescription: String?,
    contentScale: ContentScale,
    filterQuality: FilterQuality,
    requestSizePx: Int?,
    reveal: ArtworkReveal,
    direction: Int,
    onError: (Throwable) -> Unit,
    // A retry after a failure: the first image reveals over the fallback icon
    // instead of snapping in, and [onRevealed] reports when it covers it.
    revealFirstLoad: Boolean = false,
    onRevealed: () -> Unit = {}
) {
    val context = LocalContext.current
    var settled by remember { mutableStateOf<Painter?>(null) }
    val effectSpec = if (reveal == ArtworkReveal.DotDissolve) {
        YoinMotion.artworkDissolveSpring()
    } else {
        YoinMotion.effectsSpring<Float>()
    }
    val primary = MaterialTheme.colorScheme.primary
    val tertiary = MaterialTheme.colorScheme.tertiary

    Box(Modifier.fillMaxSize()) {
        key(model, requestSizePx) {
            // A later skip can change direction while its image is still loading.
            // Keep the visible transition's direction attached to its own cover.
            val revealDirection = remember { direction }
            val currentPrimary by rememberUpdatedState(primary)
            val currentTertiary by rememberUpdatedState(tertiary)
            val currentOnRevealed by rememberUpdatedState(onRevealed)
            var loaded by remember { mutableStateOf<AsyncImagePainter.State.Success?>(null) }
            val progress = remember { Animatable(0f) }
            val request = remember(context) {
                ImageRequest.Builder(context).data(model).crossfade(false).apply {
                    if (requestSizePx != null) {
                        size(requestSizePx, requestSizePx)
                        memoryCacheKey("$model#$requestSizePx")
                    }
                }.build()
            }
            LaunchedEffect(loaded) {
                val success = loaded ?: return@LaunchedEffect
                val cachedThumbnail = reveal == ArtworkReveal.Crossfade &&
                    success.result.dataSource == DataSource.MEMORY_CACHE
                if (!revealFirstLoad && (settled == null || cachedThumbnail)) {
                    progress.snapTo(1f)
                } else {
                    progress.animateTo(1f, effectSpec)
                }
                settled = success.painter
                currentOnRevealed()
            }
            // Freeze a partially revealed cover if another skip interrupts it.
            // This retains actual painters and the mask, never another URL request.
            DisposableEffect(Unit) {
                onDispose {
                    val painter = loaded?.painter
                    val under = settled
                    if (painter != null && under != null && progress.value < 1f) {
                        settled = InterruptedArtworkPainter(
                            under, painter, progress.value, reveal, revealDirection, currentPrimary, currentTertiary
                        )
                    }
                }
            }
            val halftone = remember { ArtworkHalftone() }
            val layer = rememberGraphicsLayer()
            settled?.let { painter ->
                Image(
                    painter = painter,
                    contentDescription = null,
                    contentScale = contentScale,
                    modifier = Modifier.fillMaxSize().drawWithContent {
                        // Returning to the same URL can still require a fresh
                        // asynchronous request. Retire the backing only after
                        // this foreground is actually displaying that painter.
                        if (loaded?.painter !== painter || progress.value < 1f) drawContent()
                    }
                )
            }
            AsyncImage(
                model = request,
                contentDescription = contentDescription,
                contentScale = contentScale,
                filterQuality = filterQuality,
                onSuccess = { loaded = it },
                onError = { onError(it.result.throwable) },
                modifier = Modifier.fillMaxSize().drawWithContent {
                    val success = loaded
                    val amount = when {
                        success == null -> 0f
                        revealFirstLoad -> progress.value.coerceIn(0f, 1f)
                        settled == null -> 1f
                        reveal == ArtworkReveal.Crossfade && success.result.dataSource == DataSource.MEMORY_CACHE -> 1f
                        else -> progress.value.coerceIn(0f, 1f)
                    }
                    when {
                        amount >= 1f -> drawContent()
                        amount <= 0f -> Unit
                        reveal == ArtworkReveal.DotDissolve -> {
                            layer.record { this@drawWithContent.drawContent() }
                            layer.alpha = ArtworkHalftone.opacity(amount)
                            halftone.draw(this, amount, revealDirection, primary, tertiary) {
                                drawLayer(layer)
                            }
                        }
                        else -> {
                            layer.record { this@drawWithContent.drawContent() }
                            layer.alpha = amount
                            drawLayer(layer)
                        }
                    }
                }
            )
        }
    }
}

private class InterruptedArtworkPainter(
    val under: Painter,
    val over: Painter,
    val progress: Float,
    val reveal: ArtworkReveal,
    val direction: Int,
    val primary: Color,
    val tertiary: Color
) : Painter() {
    override val intrinsicSize: Size get() = over.intrinsicSize
    private val halftone = ArtworkHalftone()

    override fun DrawScope.onDraw() {
        with(under) { draw(size) }
        if (reveal == ArtworkReveal.DotDissolve) {
            halftone.draw(this, progress, direction, primary, tertiary) {
                with(over) { draw(size, alpha = ArtworkHalftone.opacity(progress)) }
            }
        } else {
            with(over) { draw(size, alpha = progress) }
        }
    }
}
