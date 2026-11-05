package com.gpo.yoin.ui.component

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.EaseOutCubic
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.runtime.SideEffect
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
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.gpo.yoin.R
import com.gpo.yoin.ui.experience.LocalWindowCovered
import com.gpo.yoin.ui.nowplaying.HeldLineMotion
import com.gpo.yoin.ui.nowplaying.LocalNowPlayingTransportSignal
import com.gpo.yoin.ui.nowplaying.LyricDotsBreakMs
import com.gpo.yoin.ui.nowplaying.LyricDotsDevelopFrom
import com.gpo.yoin.ui.nowplaying.LyricDotsDevelopMs
import com.gpo.yoin.ui.nowplaying.LyricDotsSolid
import com.gpo.yoin.ui.nowplaying.LyricLine
import com.gpo.yoin.ui.nowplaying.UpNextHandoffTickSlackMs
import com.gpo.yoin.ui.nowplaying.UpNextLyrics
import com.gpo.yoin.ui.nowplaying.lastLineOutroFor
import com.gpo.yoin.ui.nowplaying.lastLineStretchScale
import com.gpo.yoin.ui.nowplaying.lyricDotRadius
import com.gpo.yoin.ui.nowplaying.LyricDotsReadableLines
import com.gpo.yoin.ui.nowplaying.lyricDotsRest
import com.gpo.yoin.ui.nowplaying.lyricHalftone
import com.gpo.yoin.ui.nowplaying.outroProgress
import com.gpo.yoin.ui.nowplaying.upNextHandedOver
import com.gpo.yoin.ui.nowplaying.upNextTimingFor
import com.gpo.yoin.ui.theme.GoogleSansFlexRounded
import com.gpo.yoin.ui.theme.YoinMotion
import com.gpo.yoin.ui.theme.YoinMotionRole
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
    trackKey: Any = Unit,
    queueIndex: Int = 0,
    // Focus (tap the cover), 0..1, read in draw: the lines print through
    // fine dots and the song's last line is held wide over the outro
    // (owner 2026-11-03). 0 = the resting page, solid.
    focus: () -> Float = { 0f },
    // The song's length, for the outro (last-line hold, up next). 0 = none.
    durationMs: Long = 0L,
    // Focus only: the next song's first line rises under the last line and
    // takes its place at the hand-over (UpNextTiming), so the song change
    // swaps in place.
    upNext: UpNextLyrics? = null,
    // Extra room past the end edge the held last line may draw into (the
    // caller widened this box by as much); the lines still wrap without it.
    endOverflow: Dp = 0.dp,
    // How far the current line's anchor has moved to the top (0 = the
    // resting 22%, 1 = pinned). Follows [focus] unless the caller pins it: a
    // list that only shows in focus opens pinned, with no line above it.
    anchorFocus: () -> Float = focus,
) {
    val liveTrackKey by rememberUpdatedState(trackKey)
    // Song change = the stream carries on (see LyricsTrackTransition);
    // loading → loaded dissolves instead of popping.
    LyricsTrackTransition(
        trackKey = trackKey,
        data = CompactLyricsFrame(trackKey, lyrics, loading, upNext, durationMs),
        queueIndex = queueIndex,
        positionMs = positionMs,
        modifier = modifier,
        continuesInto = { from, fromPositionMs, toKey -> from.handedOverTo(toKey, fromPositionMs) },
    ) { frame, songPositionMs ->
        AnimatedContent(
            targetState = frame,
            // In-place swap: the outgoing list already shows what the incoming
            // one opens with; it steps out at once instead of doubling up.
            modifier = Modifier.graphicsLayer {
                alpha = if (frame.trackKey != liveTrackKey && frame.handedOverTo(liveTrackKey, songPositionMs())) {
                    0f
                } else {
                    1f
                }
            },
            contentKey = { it.lyrics.isEmpty() },
            transitionSpec = {
                (
                    YoinMotion.slideInVertically(role = YoinMotionRole.Expressive) { it / 12 } +
                        YoinMotion.fadeIn(role = YoinMotionRole.Standard)
                    ) togetherWith YoinMotion.fadeOut(role = YoinMotionRole.Standard)
            },
            label = "compactLyricsLoaded",
        ) { shown ->
            LyricsDisplayContent(
                trackKey = shown.trackKey,
                lyrics = shown.lyrics,
                positionMs = songPositionMs,
                loading = shown.loading,
                fontScale = fontScale,
                focus = focus,
                anchorFocus = anchorFocus,
                durationMs = shown.durationMs,
                upNext = shown.upNext,
                endOverflow = endOverflow,
            )
        }
    }
}

private data class CompactLyricsFrame(
    val trackKey: Any,
    val lyrics: List<LyricLine>,
    val loading: Boolean,
    val upNext: UpNextLyrics?,
    val durationMs: Long,
)

private fun CompactLyricsFrame.handedOverTo(toKey: Any, atPositionMs: Long): Boolean =
    upNextHandedOver(lyrics, upNext, durationMs, followedPlayhead = true, toKey = toKey, atPositionMs = atPositionMs)

@Composable
private fun LyricsDisplayContent(
    trackKey: Any,
    lyrics: List<LyricLine>,
    positionMs: () -> Long,
    loading: Boolean,
    fontScale: Float,
    focus: () -> Float,
    anchorFocus: () -> Float,
    durationMs: Long,
    upNext: UpNextLyrics?,
    endOverflow: Dp,
    modifier: Modifier = Modifier.fillMaxSize(),
) {
    if (lyrics.isEmpty()) {
        Box(
            modifier = modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp)
                .padding(end = endOverflow),
            contentAlignment = Alignment.CenterStart,
        ) {
            if (loading) {
                YoinLoadingIndicator(size = 32.dp)
            } else {
                Text(
                    text = stringResource(R.string.cmp_lyrics_empty),
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
    // Unsynced lyrics have no timestamps to anchor to (currentIndex stays -1
    // and the auto-scroll effect never engages) — hand the scroll to the user
    // instead of freezing the list. The LazyColumn consumes these vertical
    // drags, which is exactly what keeps them out of the NP host's
    // drag-to-dismiss draggable.
    val isUnsynced = remember(lyrics) { lyrics.none { it.startMs != null } }
    val listState = rememberLazyListState()
    // First centring is INSTANT (no animated jump) the moment the list has a real
    // viewport; resets per song so a new track re-anchors immediately.
    var hasCentered by remember(lyrics) { mutableStateOf(false) }

    // The outro (focus): the last line held wide (I-2) and, with a next song
    // known, its first line rising under it with a small label, taking the
    // stage at the hand-over (UpNextTiming).
    val lastLineOutro = remember(lyrics, durationMs) { lastLineOutroFor(lyrics, durationMs) }
    val lastLineIndex = remember(lyrics) { lyrics.indexOfLast { it.startMs != null } }
    val lastLineStretch by remember(lastLineOutro) {
        derivedStateOf { lastLineOutro?.outroProgress(currentPositionMs()) ?: 0f }
    }
    val upNextTiming = remember(lyrics, upNext, durationMs) { upNextTimingFor(lyrics, upNext, durationMs) }
    val upNextLine = upNext?.lines?.firstOrNull { it.startMs != null }
    val shownUpNext = upNext.takeIf { upNextTiming != null && upNextLine != null }
    val upNextLabelIndex = if (shownUpNext != null) lyrics.size else -1
    val upNextLineIndex = if (shownUpNext != null) lyrics.size + 1 else -1
    val upNextReveal by remember(upNextTiming) {
        derivedStateOf { upNextTiming?.outroProgress(currentPositionMs()) ?: 0f }
    }
    val handingOver by remember(upNextTiming) {
        derivedStateOf { upNextTiming != null && currentPositionMs() >= upNextTiming.handoffAtMs }
    }
    // No background flow on top of a song change already staged in place.
    val transportSignal = LocalNowPlayingTransportSignal.current
    val handoverStaged by remember(upNextTiming) {
        derivedStateOf {
            upNextTiming != null && currentPositionMs() >= upNextTiming.handoffAtMs - UpNextHandoffTickSlackMs
        }
    }
    val stagedNow = handoverStaged && shownUpNext != null
    val stagedSongId = shownUpNext?.songId
    // Only this window's own word: the expanded page (composed alongside it)
    // speaks for itself, so an unstaged window never clears its hand-over.
    val publishedStaged = remember { booleanArrayOf(false) }
    SideEffect {
        if (stagedNow || publishedStaged[0]) {
            transportSignal?.publishLyricsHandover(fromSongId = trackKey, toSongId = stagedSongId, staged = stagedNow)
            publishedStaged[0] = stagedNow
        }
    }
    // The next line waits out the first half of the outro (the last line is
    // held alone), then surfaces faintly; at the hand-over it settles to how
    // the next song's own list opens (an inactive line at the top).
    val revealEase = FastOutSlowInEasing.transform(((upNextReveal - 0.5f) / 0.5f).coerceIn(0f, 1f))
    val upNextAlpha by animateFloatAsState(
        targetValue = when {
            shownUpNext == null -> 0f
            handingOver -> 1f
            else -> UpNextRevealAlpha * revealEase
        },
        animationSpec = YoinMotion.slowEffectsSpec(),
        label = "compactUpNextAlpha",
    )
    val upNextRisePx by animateFloatAsState(
        targetValue = if (handingOver) 0f else (1f - revealEase) * with(LocalDensity.current) { UpNextRise.toPx() },
        animationSpec = YoinMotion.slowSpatialSpec(),
        label = "compactUpNextRise",
    )
    // This song (and the label) step back as the next one takes the stage.
    val currentBlockAlpha by animateFloatAsState(
        targetValue = if (handingOver && shownUpNext != null) 0f else 1f,
        animationSpec = YoinMotion.slowEffectsSpec(),
        label = "compactCurrentLyricsAlpha",
    )

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
    //
    // The hand-over glides the next song's line to the very top, where the
    // next song's own list opens (scroll 0), so the swap is in place.
    val windowCovered = LocalWindowCovered.current
    val handOverTarget = handingOver && upNextLineIndex >= 0
    // Focus: the line just sung, whole, above the current one (owner
    // 2026-11-05 「歌词应该上面也漏出来一行」). Until that line has been laid
    // out its height is estimated from one inactive line.
    val density = LocalDensity.current
    val inactiveStyle = MaterialTheme.typography.bodyMedium
    val lineAbovePxEstimate = with(density) {
        (inactiveStyle.lineHeight * fontScale).toPx() + LyricItemPadV.toPx() * 2 + LyricItemGap.toPx()
    }
    val lineGapPx = with(density) { LyricItemGap.toPx() }
    LaunchedEffect(lyrics, currentIndex, listState, handOverTarget) {
        if (currentIndex < 0 && !handOverTarget) return@LaunchedEffect
        val target = if (handOverTarget) upNextLineIndex else currentIndex.coerceIn(0, lyrics.lastIndex)
        var firstAnchor = true
        // filter { it > 0 }: during an expand the height is ~0 for a frame, and a 0
        // offset would slip the anchoring by a line.
        // Focus pins the current line right under the line just sung (the
        // budget's FocusLyricsAnchorFraction = 0, FocusLinesAbove = 1): no
        // half-cut line above it (owner 2026-11-04 / 11-05).
        snapshotFlow {
            val above = listState.layoutInfo.visibleItemsInfo.firstOrNull { it.index == target - 1 }?.size ?: -1
            Triple(listState.layoutInfo.viewportSize.height, anchorFocus().coerceIn(0f, 1f), above)
        }
            .filter { it.first > 0 }
            .collect { (viewportPx, focusNow, abovePx) ->
                val anchor = 0.22f * (1f - focusNow)
                val lineAbovePx = when {
                    target <= 0 -> 0f
                    abovePx >= 0 -> abovePx + lineGapPx
                    else -> lineAbovePxEstimate
                }
                val offsetPx = if (handOverTarget) {
                    0
                } else {
                    -(viewportPx * anchor + lineAbovePx * focusNow).toInt()
                }
                when {
                    // Very first centre of the song: instant, no animation.
                    !hasCentered -> {
                        listState.scrollToItem(index = target, scrollOffset = offsetPx)
                        hasCentered = true
                    }
                    // First reaction to this line advance: glide.
                    // (Covered by a Yoin window, its clock frozen: land it at once instead.)
                    firstAnchor && !windowCovered.value ->
                        listState.animateScrollToItem(index = target, scrollOffset = offsetPx)
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
            // Synced lyrics are playhead-driven (ONE scroll owner: the anchor
            // effect above); untimed lyrics scroll by hand.
            userScrollEnabled = isUnsynced,
            verticalArrangement = Arrangement.spacedBy(LyricItemGap),
            contentPadding = PaddingValues(top = 12.dp, bottom = 12.dp, end = endOverflow),
        ) {
            itemsIndexed(lyrics, key = { i, _ -> i }) { index, line ->
                val active = index == currentIndex && currentIndex >= 0 && !handingOver
                LyricLineItem(
                    text = line.text,
                    isActive = active,
                    distance = if (currentIndex >= 0) abs(index - currentIndex) else 99,
                    upcoming = currentIndex >= 0 && index > currentIndex &&
                        index - currentIndex <= LyricDotsReadableLines,
                    fontScale = fontScale,
                    focus = focus,
                    // Held while it is the current line; the hand-over lets go.
                    stretch = if (index == lastLineIndex && active) lastLineStretch else 0f,
                    blockAlpha = { currentBlockAlpha },
                )
            }
            if (shownUpNext != null && upNextLine != null) {
                val rise: () -> Float = { upNextRisePx }
                item(key = "upNextLabel") {
                    Text(
                        text = stringResource(R.string.np_lyric_up_next, shownUpNext.title),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = UpNextLabelGap, bottom = 2.dp)
                            .graphicsLayer {
                                alpha = upNextAlpha * currentBlockAlpha
                                translationY = rise()
                            },
                    )
                }
                item(key = "upNextLine") {
                    // As the next song's own list shows it before its first
                    // line: inactive, far from any current line.
                    LyricLineItem(
                        text = upNextLine.text,
                        isActive = false,
                        distance = 99,
                        fontScale = fontScale,
                        focus = focus,
                        stretch = 0f,
                        blockAlpha = { upNextAlpha },
                        blockOffsetY = rise,
                    )
                }
                // Long enough that the hand-over can park the next line at the top.
                item(key = "upNextTail") {
                    Spacer(
                        modifier = Modifier
                            .fillMaxWidth()
                            .fillParentMaxHeight(),
                    )
                }
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
    // One of the next LyricDotsReadableLines: in focus its dots stay full
    // enough to read (owner 2026-11-05).
    upcoming: Boolean = false,
    fontScale: Float = 1f,
    focus: () -> Float = { 0f },
    stretch: Float = 0f,
    blockAlpha: () -> Float = { 1f },
    blockOffsetY: () -> Float = { 0f },
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
    // Focus's halftone: the line's own dot radius. The current line develops
    // from dots to solid, a sung line breaks back into dots, the rest stay
    // fine dots. Lines that are current when they first show start solid.
    val restDots = lyricDotsRest(upcoming)
    val dots = remember { Animatable(if (isActive) LyricDotsSolid else restDots) }
    val shownBefore = remember { booleanArrayOf(false) }
    LaunchedEffect(isActive, restDots) {
        if (!shownBefore[0]) {
            shownBefore[0] = true
            return@LaunchedEffect
        }
        if (isActive) {
            dots.snapTo(minOf(dots.value, LyricDotsDevelopFrom))
            dots.animateTo(LyricDotsSolid, tween(LyricDotsDevelopMs, easing = EaseOutCubic))
        } else if (dots.value != restDots) {
            dots.animateTo(restDots, tween(LyricDotsBreakMs, easing = EaseOutCubic))
        }
    }
    // I-2 in focus: the song's last line held wide from its left edge. A
    // draw-time scaleX of the whole line: it never re-wraps, and past the
    // row's end it simply runs on toward (and off) the screen edge.
    val held = remember { HeldLineMotion() }
    val widen = YoinMotion.slowSpatialSpec<Float>(role = YoinMotionRole.Expressive)
    val release = YoinMotion.defaultSpatialSpec<Float>(role = YoinMotionRole.Expressive)
    LaunchedEffect(held, stretch) {
        held.follow(stretch, widen, release)
    }

    // Active line: SemiBold (down from the old Bold — the preview sizes don't
    // need that much ink) in the rounded variable cut; color + scale carry
    // the rest of the emphasis.
    val baseStyle = if (isActive) {
        MaterialTheme.typography.bodyLarge.copy(
            fontFamily = GoogleSansFlexRounded,
            fontWeight = FontWeight.SemiBold,
        )
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
                this.alpha = alpha * blockAlpha()
                translationY = blockOffsetY()
                scaleX = scale * lastLineStretchScale(held.progress.value * focus().coerceIn(0f, 1f))
                scaleY = scale
                transformOrigin = TransformOrigin(0f, 0.5f)
            }
            .lyricHalftone { lyricDotRadius(dots.value, focus()) }
            .padding(vertical = LyricItemPadV),
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

/** The next line surfaces to this strength during the outro (full at the hand-over). */
private const val UpNextRevealAlpha = 0.4f

/** The next line rises this far into place. */
private val UpNextRise = 24.dp

/** Air between the song's last line and the up-next label. */
private val UpNextLabelGap = 20.dp

/** Gap between lyric lines (NowPlayingBudget's LyricsListLineGap). */
private val LyricItemGap = 2.dp

/** Each line's own vertical pad (NowPlayingBudget's LyricLinePads, halved). */
private val LyricItemPadV = 4.dp
