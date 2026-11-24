package com.gpo.yoin.ui.notes.editor

import androidx.compose.runtime.Stable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.input.pointer.PointerEvent
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.changedToDownIgnoreConsumed
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.node.GlobalPositionAwareModifierNode
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.node.PointerInputModifierNode
import androidx.compose.ui.unit.IntSize

/**
 * Every finger that comes down in one window of the app, in window coordinates, for whoever is listening (the @
 * popup, 2026-11-24 tablet QA: a tap on blank background, the note list or an empty toolbar must end mention
 * mode, though none of them takes focus). Only this window's touches arrive: the soft keyboard and the popup
 * are windows of their own, so typing on the keyboard or tapping a row never counts as a tap elsewhere. Each
 * Yoin window (activity root, bottom sheet) has one ([windowTapSource]); nothing is consumed.
 */
@Stable
class WindowTaps {
    private val listeners = mutableListOf<(Offset) -> Unit>()

    /** Hears every tap until the returned call stops it. */
    fun listen(listener: (Offset) -> Unit): () -> Unit {
        listeners += listener
        return { listeners -= listener }
    }

    fun tap(windowPosition: Offset) {
        listeners.toList().forEach { it(windowPosition) }
    }
}

/** This window's taps; null outside a Yoin window (previews, tests that don't provide one). */
val LocalWindowTaps = staticCompositionLocalOf<WindowTaps?> { null }

/** Reports each finger down on this node (a window's root) to [taps], before anything under it sees it. */
fun Modifier.windowTapSource(taps: WindowTaps): Modifier = this then WindowTapElement(taps)

/** A tap outside the field being written in ([field], window coordinates) ends its mention mode. */
internal fun tapEndsMention(field: Rect?, tap: Offset): Boolean = field != null && !field.contains(tap)

private data class WindowTapElement(val taps: WindowTaps) : ModifierNodeElement<WindowTapNode>() {
    override fun create() = WindowTapNode(taps)

    override fun update(node: WindowTapNode) {
        node.taps = taps
    }
}

private class WindowTapNode(var taps: WindowTaps) :
    Modifier.Node(),
    PointerInputModifierNode,
    GlobalPositionAwareModifierNode {
    private var coordinates: LayoutCoordinates? = null

    override fun onGloballyPositioned(coordinates: LayoutCoordinates) {
        this.coordinates = coordinates
    }

    override fun onPointerEvent(pointerEvent: PointerEvent, pass: PointerEventPass, bounds: IntSize) {
        if (pass != PointerEventPass.Initial || pointerEvent.type != PointerEventType.Press) return
        val at = coordinates?.takeIf { it.isAttached } ?: return
        pointerEvent.changes
            .filter { it.changedToDownIgnoreConsumed() }
            .forEach { taps.tap(at.localToWindow(it.position)) }
    }

    override fun onCancelPointerInput() = Unit
}
