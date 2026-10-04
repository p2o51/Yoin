package com.gpo.yoin.ui.experience

import androidx.compose.runtime.State
import androidx.compose.runtime.staticCompositionLocalOf

/**
 * True while the shell column's width is moving — the Now Playing side panel
 * or the detail column on their springs, or the split handle under the finger.
 * Content whose HEIGHT follows its width (Home's Jump Back In covers on
 * Medium / Wide) then changes size on every frame, and a feed that animates
 * item placement would chase it with a spring a frame behind; feeds read this
 * to let placement follow 1:1 while the width moves.
 *
 * Provided as a [State] so only the readers of `.value` recompose, and only
 * when the motion starts or ends (adaptive principle 7).
 */
val LocalPaneWidthInMotion = staticCompositionLocalOf<State<Boolean>> { PaneWidthAtRest }

private object PaneWidthAtRest : State<Boolean> {
    override val value: Boolean = false
}
