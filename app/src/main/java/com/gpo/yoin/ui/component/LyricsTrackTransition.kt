package com.gpo.yoin.ui.component

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.togetherWith
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import com.gpo.yoin.ui.theme.YoinMotion
import com.gpo.yoin.ui.theme.YoinMotionRole
import com.gpo.yoin.ui.theme.YoinMotionSpeed

/**
 * Song change inside an open lyrics view reads as the lyric stream carrying
 * on: the finished song's lines keep drifting up and dissolve while the next
 * song's lines rise in from below (reversed when stepping back in the queue).
 * Both sides ride the spatial spring; alpha rides the effects spring.
 *
 * The outgoing song is frozen at its last playhead. Otherwise it would read
 * the NEW song's position during its exit, snap its active line back to the
 * top and glide mid-departure.
 *
 * @param trackKey identity of the playing track (song id). Lyrics lists alone
 *   are not a track identity: a translation or a manual lyrics swap replaces
 *   the list for the same song and must not play this transition.
 * @param data everything the lyrics view renders for this track. Each side
 *   of the transition keeps its own last snapshot, so the leaving song still
 *   shows ITS lines while the state has already moved to the next song.
 * @param queueIndex current queue position, only used for direction.
 * @param continuesInto true when the outgoing view (its data + where its
 *   playhead stopped) has already staged the incoming song on screen, so the
 *   swap should be instant rather than a second slide.
 */
@Composable
fun <T> LyricsTrackTransition(
    trackKey: Any,
    data: T,
    queueIndex: Int,
    positionMs: () -> Long,
    modifier: Modifier = Modifier,
    continuesInto: (from: T, fromPositionMs: Long, toKey: Any) -> Boolean = { _, _, _ -> false },
    content: @Composable (data: T, positionMs: () -> Long) -> Unit,
) {
    val liveTrackKey by rememberUpdatedState(trackKey)
    val latestPosition by rememberUpdatedState(positionMs)
    val latestContinuesInto by rememberUpdatedState(continuesInto)
    // Last playhead each song was drawn at, so the hand-over decision can ask
    // where the OUTGOING song stood (the live position is already the new one).
    val lastPositions = remember { HashMap<Any, Long>() }
    // Direction is decided when the key changes: remember the index the
    // outgoing song had, compare it with the incoming one.
    val lastQueueIndex = remember { intArrayOf(queueIndex) }
    val forward = remember(trackKey) {
        val previous = lastQueueIndex[0]
        lastQueueIndex[0] = queueIndex
        queueIndex >= previous
    }
    AnimatedContent(
        targetState = TrackFrame(trackKey, data),
        contentKey = { it.key },
        transitionSpec = {
            val from = initialState
            if (latestContinuesInto(from.data, lastPositions[from.key] ?: 0L, targetState.key)) {
                // The outgoing view already staged the next song exactly where
                // the incoming one starts (its "up next" block handed over at
                // the top): swap in place, no second motion on top of it.
                return@AnimatedContent EnterTransition.None togetherWith ExitTransition.None
            }
            val sign = if (forward) 1 else -1
            (
                YoinMotion.slideInVertically(role = YoinMotionRole.Expressive) { sign * it / 4 } +
                    YoinMotion.fadeIn(role = YoinMotionRole.Expressive)
                ) togetherWith (
                YoinMotion.slideOutVertically(role = YoinMotionRole.Expressive) { -sign * it / 4 } +
                    // Slow fade so the leaving lines are still readable while
                    // they drift; a default-speed fade had them gone before the
                    // movement registered.
                    YoinMotion.fadeOut(role = YoinMotionRole.Expressive, speed = YoinMotionSpeed.Slow)
                )
        },
        label = "lyricsTrack",
        modifier = modifier,
    ) { frame ->
        val key = frame.key
        // liveTrackKey is snapshot state, so the live→frozen flip invalidates
        // derivedStateOf readers of this lambda (the active-line index).
        val frozenAt = remember { longArrayOf(0L) }
        val songPosition: () -> Long = remember(key) {
            {
                if (liveTrackKey == key) {
                    latestPosition().also {
                        frozenAt[0] = it
                        lastPositions[key] = it
                    }
                } else {
                    frozenAt[0]
                }
            }
        }
        content(frame.data, songPosition)
    }
}

private data class TrackFrame<T>(val key: Any, val data: T)
