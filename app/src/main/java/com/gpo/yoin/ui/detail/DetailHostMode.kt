package com.gpo.yoin.ui.detail

import androidx.compose.runtime.staticCompositionLocalOf

/**
 * Where a detail page is composed (adaptive principle 3: a split is columns
 * of one window). The SAME screen composables serve both hosts; only the
 * infrastructure they call branches on this.
 */
enum class DetailHostMode {
    /**
     * Its own Activity window (Compact / Medium / short windows): the page
     * draws the persistent bottom bar, plays the in-window predictive-back
     * replica (Pattern B) and the 96dp enter slide.
     */
    Window,

    /**
     * A column of the shell window (Wide + tall, `hasDetailPane`): no bar —
     * the shell's one bar spans both columns and carries the page's Play
     * actions — no window back replica (the pane's NavDisplay pops entries
     * and the shell closes the column), and the page mounts at once because
     * the pane itself animates.
     */
    Pane,
}

val LocalDetailHostMode = staticCompositionLocalOf { DetailHostMode.Window }
