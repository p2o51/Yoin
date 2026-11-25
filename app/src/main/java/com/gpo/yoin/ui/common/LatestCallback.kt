package com.gpo.yoin.ui.common

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState

/**
 * A callback that keeps one identity for the life of the composition and
 * always runs the latest [callback] it was given.
 *
 * For callbacks that close over state which flips at busy moments: the shell's
 * navigation callbacks close over whether Now Playing is open, so every open
 * and close used to hand Home, Library and Now Playing new callbacks, and each
 * of them recomposed top to bottom in the frame the player started to leave.
 * Only call the result from events (taps, effects), never during composition.
 */
@Composable
fun <A> rememberLatestCallback(callback: (A) -> Unit): (A) -> Unit {
    val latest = rememberUpdatedState(callback)
    return remember { { a: A -> latest.value(a) } }
}

/** [rememberLatestCallback] for two arguments. */
@Composable
fun <A, B> rememberLatestCallback(callback: (A, B) -> Unit): (A, B) -> Unit {
    val latest = rememberUpdatedState(callback)
    return remember { { a: A, b: B -> latest.value(a, b) } }
}
