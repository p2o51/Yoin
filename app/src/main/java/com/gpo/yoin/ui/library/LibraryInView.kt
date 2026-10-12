package com.gpo.yoin.ui.library

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import com.gpo.yoin.ui.experience.LocalPaneWidthInMotion

/**
 * Calls [onShown] each time Library comes into view, which is when Recents
 * take in what was opened and played since ([LibraryViewModel.onLibraryShown]):
 * - Library composes (its tab chosen) and its window resumes: back from a
 *   detail page (an Activity on Compact) or from the background;
 * - its column comes to rest wider than it last rested: the detail column or
 *   Now Playing's panel beside it closed (or the split handle gave it room).
 *
 * Returns the modifier that measures the column; put it on Library's root.
 */
@Composable
internal fun rememberLibraryInView(onShown: () -> Unit): Modifier {
    val currentOnShown by rememberUpdatedState(onShown)
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { currentOnShown() }
    val widthInMotion = LocalPaneWidthInMotion.current
    val width = remember { mutableIntStateOf(0) }
    LaunchedEffect(widthInMotion) {
        var rested = 0
        // A width only once it rests: none while the panes beside it move.
        snapshotFlow { width.intValue.takeUnless { widthInMotion.value } }
            .collect { resting ->
                if (resting == null || resting <= 0) return@collect
                if (rested in 1 until resting) currentOnShown()
                rested = resting
            }
    }
    return remember { Modifier.onSizeChanged { width.intValue = it.width } }
}
