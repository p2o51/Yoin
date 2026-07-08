package com.gpo.yoin.ui.component

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.gpo.yoin.ui.nowplaying.LyricLine
import com.gpo.yoin.ui.theme.YoinMotion
import com.gpo.yoin.ui.theme.YoinTheme
import kotlin.math.abs
import kotlinx.coroutines.flow.filter

/**
 * Synced lyrics display — a compact, fixed-height window onto the full lyric list
 * that keeps the current line anchored near the top (one "past" line above it,
 * upcoming lines below) and smoothly auto-scrolls as playback advances.
 *
 * The list data NEVER mutates — only the scroll position and per-line active state
 * change — so the top line LEAVING and the bottom line ENTERING are the *same*
 * continuous scroll at both edges (dissolved by the [edgeFade] masks), with no
 * lazy-insertion animation to misfire. End-of-song falls out naturally: the scroll
 * just stops once the last lines are all in view.
 *
 * @param lyrics list of lyric lines (may be synced or unsynced)
 * @param positionMs reader for the current playback position in milliseconds.
 *  A lambda (not a value) so the 4Hz tick is consumed inside a
 *  [derivedStateOf] here — callers don't recompose per tick, and this
 *  composable only recomposes when the ACTIVE LINE actually advances
 * @param loading true while the ViewModel's fetch is in flight; renders the
 *  expressive [YoinLoadingIndicator] instead of the "No lyrics available" text
 * @param visibleLines retained for source compatibility; the visible line count is
 *  now driven by the container height (the modifier), not this value
 * @param fixedHeight retained for source compatibility; unused
 * @param fontScale multiplies BOTH the font size and the line height, so growing
 *  the text never cramps the line spacing
 */
@Composable
fun LyricsDisplay(
    lyrics: List<LyricLine>,
    positionMs: () -> Long,
    modifier: Modifier = Modifier,
    loading: Boolean = false,
    visibleLines: Int = 5,
    fixedHeight: Dp = 160.dp,
    fontScale: Float = 1f,
) {
    if (lyrics.isEmpty()) {
        Box(
            modifier = modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp),
            contentAlignment = Alignment.CenterStart,
        ) {
            if (loading) {
                YoinLoadingIndicator(size = 32.dp)
            } else {
                Text(
                    text = "No lyrics available",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        return
    }

    // derivedStateOf absorbs the 4Hz position tick: the index recomputes per
    // tick, but readers (the LaunchedEffect key, per-line isActive) only
    // recompose when the resolved line actually changes.
    val currentPositionMs by rememberUpdatedState(positionMs)
    val currentIndex by remember(lyrics) {
        derivedStateOf { findCurrentLyricIndex(lyrics, currentPositionMs()) }
    }
    val listState = rememberLazyListState()
    // First centring is INSTANT (no animated jump) the moment the list has a real
    // viewport; resets per song so a new track re-anchors immediately.
    var hasCentered by remember(lyrics) { mutableStateOf(false) }

    // ONE settle driver owns the scroll. Anchor the active line ~22% from the top
    // so there is roughly one past line above it and the upcoming lines below.
    //
    // Re-anchor on EVERY viewport-size change, not just when the line advances:
    // this compact window is resized IN PLACE as the NP stage reshapes (Compact
    // <-> Expanded, and the hinge lyricsEmphasis lerp). The stored scroll offset is
    // in PIXELS, derived as a fraction of the viewport at anchor time — so if the
    // last anchor happened while the box was tall (expanded), that pixel offset is
    // far too large once the box shrinks back to the collapsed height and the active
    // line lands well below centre. Collecting the viewport height (as
    // LyricsFullscreenPane does) re-derives the offset for each new height, so the
    // line snaps back to the intended 22% whenever the box grows or shrinks.
    //
    // `lyrics` MUST be a key: hasCentered is remember(lyrics)-scoped, so on a
    // track change where the derived index happens to keep the same value
    // (e.g. both songs sitting on line 0) an index-only key would leave the
    // old coroutine running with the OLD list and OLD hasCentered captured —
    // the new song then never gets its instant first centring and the next
    // line advance snaps instead of gliding.
    LaunchedEffect(lyrics, currentIndex, listState) {
        if (currentIndex < 0) return@LaunchedEffect
        val target = currentIndex.coerceIn(0, lyrics.lastIndex)
        var firstAnchor = true
        // filter { it > 0 }: during an expand the height is ~0 for a frame, and a 0
        // offset would slip the anchoring by a line.
        snapshotFlow { listState.layoutInfo.viewportSize.height }
            .filter { it > 0 }
            .collect { viewportPx ->
                val offsetPx = -(viewportPx * 0.22f).toInt()
                when {
                    // Very first centre of the song: instant, no animation.
                    !hasCentered -> {
                        listState.scrollToItem(index = target, scrollOffset = offsetPx)
                        hasCentered = true
                    }
                    // First reaction to this line advance: glide.
                    firstAnchor -> listState.animateScrollToItem(index = target, scrollOffset = offsetPx)
                    // Later frames while the box is mid-resize: snap so the active
                    // line tracks the changing viewport instead of lagging behind.
                    else -> listState.scrollToItem(index = target, scrollOffset = offsetPx)
                }
                firstAnchor = false
            }
    }

    Box(
        modifier = modifier
            .fillMaxWidth()
            // Both edges dissolve: the leaving line fades out at the top, the
            // entering line fades in at the bottom — symmetric by construction.
            // Kept modest so a short (3-line) collapsed box isn't eaten by the fade.
            .edgeFade(top = 16.dp, bottom = 24.dp),
    ) {
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            userScrollEnabled = false,
            verticalArrangement = Arrangement.spacedBy(2.dp),
            contentPadding = PaddingValues(vertical = 12.dp),
        ) {
            itemsIndexed(lyrics, key = { i, _ -> i }) { index, line ->
                LyricLineItem(
                    text = line.text,
                    isActive = index == currentIndex && currentIndex >= 0,
                    distance = if (currentIndex >= 0) abs(index - currentIndex) else 99,
                    fontScale = fontScale,
                )
            }
        }
    }
}

@Composable
private fun LyricLineItem(
    text: String,
    isActive: Boolean,
    modifier: Modifier = Modifier,
    distance: Int = 0,
    fontScale: Float = 1f,
) {
    // Distance-based falloff (not just active/inactive): lines dim the further they
    // are from the current one, for the smooth Apple-style gradient.
    val targetAlpha = when {
        isActive -> 1f
        distance <= 1 -> 0.55f
        distance == 2 -> 0.40f
        else -> 0.28f
    }
    val textColor by animateColorAsState(
        targetValue = if (isActive) {
            MaterialTheme.colorScheme.primary
        } else {
            MaterialTheme.colorScheme.onSurfaceVariant
        },
        animationSpec = YoinMotion.effectsSpring(),
        label = "lyricColor",
    )
    val alpha by animateFloatAsState(
        targetValue = targetAlpha,
        animationSpec = YoinMotion.effectsSpring(),
        label = "lyricAlpha",
    )
    // The active line springs up to full scale (anchored at its left edge) while
    // neighbours sit a touch smaller — it "lands" as it becomes current.
    val scale by animateFloatAsState(
        targetValue = if (isActive) 1f else 0.94f,
        animationSpec = YoinMotion.spatialSpring(),
        label = "lyricScale",
    )

    val baseStyle = if (isActive) {
        MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Bold)
    } else {
        MaterialTheme.typography.bodyMedium
    }
    Text(
        text = text,
        // Scale fontSize AND lineHeight by the same factor — scaling only the font
        // grows glyphs into a fixed line box and cramps the spacing.
        style = baseStyle.copy(
            fontSize = baseStyle.fontSize * fontScale,
            lineHeight = baseStyle.lineHeight * fontScale,
        ),
        color = textColor,
        modifier = modifier
            .fillMaxWidth()
            .graphicsLayer {
                this.alpha = alpha
                scaleX = scale
                scaleY = scale
                transformOrigin = TransformOrigin(0f, 0.5f)
            }
            .padding(vertical = 4.dp),
    )
}

/**
 * Find the index of the lyric line active at [positionMs] — the LAST
 * timestamped line whose startMs is at or before the playhead. Untimed
 * lines (null startMs) are skipped; an all-untimed list yields -1. Runs
 * once per 250ms tick, so break as soon as a timestamped line passes the
 * playhead (provider lines arrive in ascending startMs order) instead of
 * scanning the whole list every call.
 */
private fun findCurrentLyricIndex(lyrics: List<LyricLine>, positionMs: Long): Int {
    var result = -1
    for (i in lyrics.indices) {
        val start = lyrics[i].startMs ?: continue
        if (start > positionMs) break
        result = i
    }
    return result
}

// ── Previews ────────────────────────────────────────────────────────────

@Preview(showBackground = true, backgroundColor = 0xFF1C1B1F)
@Composable
private fun LyricsDisplaySyncedPreview() {
    YoinTheme {
        LyricsDisplay(
            lyrics = listOf(
                LyricLine(startMs = 0, text = "First line of the song"),
                LyricLine(startMs = 5000, text = "Second line of the song"),
                LyricLine(startMs = 10000, text = "Third line — currently playing"),
                LyricLine(startMs = 15000, text = "Fourth line upcoming"),
                LyricLine(startMs = 20000, text = "Fifth line upcoming"),
                LyricLine(startMs = 25000, text = "Sixth line upcoming"),
            ),
            positionMs = { 12000L },
            modifier = Modifier.height(180.dp),
        )
    }
}

@Preview(showBackground = true, backgroundColor = 0xFF1C1B1F)
@Composable
private fun LyricsDisplayEndOfSongPreview() {
    YoinTheme {
        LyricsDisplay(
            lyrics = listOf(
                LyricLine(startMs = 0, text = "First line"),
                LyricLine(startMs = 5000, text = "Second line"),
                LyricLine(startMs = 10000, text = "Third line"),
                LyricLine(startMs = 15000, text = "Last line of the song"),
            ),
            positionMs = { 16000L },
            modifier = Modifier.height(180.dp),
        )
    }
}

@Preview(showBackground = true, backgroundColor = 0xFF1C1B1F)
@Composable
private fun LyricsDisplayEmptyPreview() {
    YoinTheme {
        LyricsDisplay(
            lyrics = emptyList(),
            positionMs = { 0L },
        )
    }
}

@Preview(showBackground = true, backgroundColor = 0xFF1C1B1F)
@Composable
private fun LyricsDisplayLoadingPreview() {
    YoinTheme {
        LyricsDisplay(
            lyrics = emptyList(),
            positionMs = { 0L },
            loading = true,
        )
    }
}
