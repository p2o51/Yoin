package com.gpo.yoin.ui.component

import androidx.compose.animation.core.Animatable
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.layout.findRootCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.toSize
import com.gpo.yoin.ui.theme.YoinMotion
import com.gpo.yoin.ui.theme.YoinMotionRole
import kotlinx.coroutines.flow.collectLatest

/**
 * Where this window's floating bottom bar sits, for the seams' bottom field
 * (dissolve-final §1.2). One per window (Activity): the bar reports its
 * rounded rectangle in root coordinates and its container colour; every
 * [seamDissolveViewport] in the same window reads them. The bar is opaque and
 * casts no shadow — content runs on under it as a still halftone, and only
 * dots whose lightness is close to the bar's give way next to it.
 *
 * [reveal] fades the whole field in and out with the bar: it shrinks to
 * nothing while Now Playing rises over the bar and grows back after it
 * collapses, so the field never outlives the chrome it belongs to.
 */
@Stable
internal class SeamBarField {
    /** The bar's rounded rectangle, root px, unclipped. */
    var bounds by mutableStateOf(Rect.Zero)
        private set
    var cornerRadius by mutableFloatStateOf(0f)
        private set
    var containerColor by mutableStateOf(Color.Unspecified)
        private set

    /** Height of the window's root: the screen's bottom edge for the field. */
    var rootHeight by mutableFloatStateOf(0f)
        private set

    /** A bar is composed and placed in this window. */
    var attached by mutableStateOf(false)
        private set

    /** The window's chrome wants the bar on screen (false while Now Playing covers it). */
    var shown by mutableStateOf(true)
        internal set

    internal val revealState = mutableFloatStateOf(0f)

    /** Field presence, 0..1, following [attached] and [shown] on an effects spring. */
    val reveal: Float get() = revealState.floatValue

    private var owner: Any? = null

    internal fun place(token: Any, bounds: Rect, cornerRadius: Float, rootHeight: Float) {
        owner = token
        if (this.bounds != bounds) this.bounds = bounds
        if (this.cornerRadius != cornerRadius) this.cornerRadius = cornerRadius
        if (this.rootHeight != rootHeight) this.rootHeight = rootHeight
        if (!attached) attached = true
    }

    internal fun paint(token: Any, color: Color) {
        if (owner != null && owner !== token) return
        if (containerColor != color) containerColor = color
    }

    internal fun release(token: Any) {
        if (owner !== token) return
        owner = null
        attached = false
    }
}

internal val LocalSeamBarField = staticCompositionLocalOf<SeamBarField?> { null }

/**
 * One field per window. Call inside the theme and the motion locals (the
 * reveal runs on the theme's effects spring).
 */
@Composable
internal fun ProvideSeamBarField(content: @Composable () -> Unit) {
    val field = remember { SeamBarField() }
    val spec = YoinMotion.defaultEffectsSpec<Float>(role = YoinMotionRole.Standard)
    LaunchedEffect(field, spec) {
        val reveal = Animatable(field.revealState.floatValue)
        snapshotFlow { field.attached && field.shown }.collectLatest { visible ->
            reveal.animateTo(if (visible) 1f else 0f, spec) { field.revealState.floatValue = value }
        }
    }
    CompositionLocalProvider(LocalSeamBarField provides field, content = content)
}

/** The bar's chrome says whether it is (going) on screen: the field follows. */
@Composable
internal fun SeamBarFieldShown(shown: Boolean) {
    val field = LocalSeamBarField.current ?: return
    SideEffect { field.shown = shown }
}

/** Reports this element (the bar's opaque Surface) as the window's bar. */
@Composable
internal fun Modifier.seamBarSource(shape: Shape, containerColor: Color): Modifier {
    val field = LocalSeamBarField.current ?: return this
    val density = LocalDensity.current
    val layoutDirection = LocalLayoutDirection.current
    val token = remember { Any() }
    DisposableEffect(field, token) {
        onDispose { field.release(token) }
    }
    SideEffect { field.paint(token, containerColor) }
    return onGloballyPositioned { coordinates ->
        val size = coordinates.size.toSize()
        val radius = when (val outline = shape.createOutline(size, layoutDirection, density)) {
            is Outline.Rounded -> outline.roundRect.topLeftCornerRadius.x
            is Outline.Rectangle -> 0f
            is Outline.Generic -> minOf(size.width, size.height) / 2f
        }
        field.place(
            token = token,
            bounds = Rect(coordinates.positionInRoot(), size),
            cornerRadius = radius,
            rootHeight = coordinates.findRootCoordinates().size.height.toFloat(),
        )
        field.paint(token, containerColor)
    }
}
