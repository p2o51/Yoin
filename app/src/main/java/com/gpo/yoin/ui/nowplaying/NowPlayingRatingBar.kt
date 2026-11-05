@file:Suppress("ktlint:standard:function-naming") // Composables are PascalCase

package com.gpo.yoin.ui.nowplaying

import androidx.compose.foundation.gestures.Orientation
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import com.gpo.yoin.ui.component.RatingSlider

/**
 * Now Playing's rating bar: the album rate sheet's draft-then-commit. While
 * the finger is on the bar the fill follows a local draft; the rating is
 * written once, with the value the finger lifts at ([onRatingCommit]) — not
 * on every pointer move, which sent one setRating per move event to the
 * provider and let a late answer for a value the finger passed through
 * overwrite the one it stopped at. A tap commits too (the bar brackets it
 * as a press). The draft stays on the bar until the stored [rating] catches
 * up, so the fill never flicks back to the old value in between.
 *
 * A song that was never rated (null [rating]) shows an empty bar with no
 * number; the first tap or drag gives it a score. There is no clear here:
 * only the album scrapbook takes a score back to "not rated".
 *
 * @param songId the playing song: a new one cancels the old press and drops its draft.
 */
@Composable
internal fun NowPlayingRatingBar(
    songId: String,
    rating: Float?,
    onRatingCommit: (Float) -> Unit,
    modifier: Modifier = Modifier,
    orientation: Orientation = Orientation.Vertical
) {
    var draft by remember(songId) { mutableStateOf<Float?>(null) }
    var held by remember(songId) { mutableStateOf(false) }
    val latestOnRatingCommit by rememberUpdatedState(onRatingCommit)
    // Action lights (ActionGlow.kt): a column up the bar while held, a band
    // across the room when the rating lands, a drain when it is cleared.
    val glowSignal = LocalNowPlayingTransportSignal.current
    val glowBounds = remember { GlowBounds() }
    val vertical = orientation == Orientation.Vertical
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    fun holdAt(value: Float) = GlowHold(
        kind = GlowHoldKind.Rating,
        pointRoot = ratingPoint(glowBounds.rootBounds, value, vertical, rtl),
        barRoot = glowBounds.rootBounds,
        value = value,
    )
    // The stored rating moved — the commit landed, or a write from elsewhere:
    // it is what the bar shows again, unless a finger is on it.
    LaunchedEffect(songId, rating) {
        if (!held) draft = null
    }
    RatingSlider(
        rating = draft ?: rating,
        onRatingChange = { value ->
            draft = value
            if (held && glowSignal != null && !glowBounds.rootBounds.isEmpty) glowSignal.glowHold = holdAt(value)
        },
        orientation = orientation,
        gestureKey = songId,
        onInteractionChange = { active ->
            held = active
            if (active) {
                if (glowSignal != null && !glowBounds.rootBounds.isEmpty) glowSignal.glowHold = holdAt(draft ?: rating ?: 0f)
            } else {
                glowSignal?.glowHold = null
                draft?.let { value ->
                    glowSignal?.glow(
                        ratingGlowKind(value),
                        Rect(ratingPoint(glowBounds.rootBounds, value, vertical, rtl), 1f),
                        value = value,
                    )
                    latestOnRatingCommit(value)
                }
            }
        },
        modifier = modifier.trackGlowBounds(glowBounds)
    )
    // A new song cancels the press without a lift (RatingSlider's gestureKey):
    // drop its light too, or the background would never come to rest.
    DisposableEffect(glowSignal, songId) {
        onDispose { glowSignal?.glowHold = null }
    }
}
