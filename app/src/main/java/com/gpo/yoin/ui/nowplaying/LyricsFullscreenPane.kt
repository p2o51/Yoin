package com.gpo.yoin.ui.nowplaying

import androidx.compose.animation.animateColorAsState
import kotlin.math.roundToInt
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.togetherWith
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.runtime.withFrameNanos
import com.gpo.yoin.ui.component.LyricsTrackTransition
import com.gpo.yoin.ui.theme.YoinMotion
import com.gpo.yoin.ui.theme.YoinMotionRole
import kotlin.math.abs
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.interaction.DragInteraction
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
import kotlinx.coroutines.flow.filter
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import com.gpo.yoin.ui.theme.GoogleSansFlexRounded
import androidx.compose.ui.unit.dp
import com.gpo.yoin.ui.component.YoinLoadingIndicator
import com.gpo.yoin.ui.component.verticalEdgeFadeOnScroll

/**
 * Fullscreen Lyrics viewer. Unlike the compact [com.gpo.yoin.ui.component.LyricsDisplay]
 * window, this one renders every line, supports tap-to-seek, and lets the
 * parent action bar suspend / resume auto-centering.
 */
@Composable
fun LyricsFullscreenPane(
    lyrics: List<LyricLine>,
    positionMs: () -> Long,
    loading: Boolean,
    showTranslation: Boolean,
    autoScrollEnabled: Boolean,
    recenterRequestKey: Int,
    onUserScroll: () -> Unit,
    onSeekToMs: (Long) -> Unit,
    modifier: Modifier = Modifier,
    viewportGrowthPx: Int = 0,
    trackKey: Any = Unit,
    queueIndex: Int = 0,
    songTitle: String? = null,
    artist: String? = null,
    upNext: UpNextLyrics? = null,
    durationMs: Long = 0L,
) {
    LyricsTrackTransition(
        trackKey = trackKey,
        data = FullscreenLyricsFrame(
            lyrics, loading, showTranslation, songTitle, artist, upNext, durationMs, autoScrollEnabled,
        ),
        queueIndex = queueIndex,
        positionMs = positionMs,
        modifier = modifier,
        continuesInto = { from, fromPositionMs, toKey ->
            val handoffAt = upNextTimingFor(from.lyrics, from.upNext, from.durationMs)?.handoffAtMs
            from.autoScroll &&
                from.upNext?.songId == toKey &&
                handoffAt != null &&
                fromPositionMs >= handoffAt - UpNextHandoffTickSlackMs
        },
    ) { frame, songPositionMs ->
        // Loading → lyrics arriving: the lines rise into place (a short
        // continuation of the song-change drift) while the spinner dissolves.
        // Each side keeps its own snapshot; keyed on emptiness only, so a
        // translation or lyrics swap on a loaded song stays in place.
        AnimatedContent(
            targetState = frame,
            contentKey = { it.lyrics.isEmpty() },
            transitionSpec = {
                (
                    YoinMotion.slideInVertically(role = YoinMotionRole.Expressive) { it / 12 } +
                        YoinMotion.fadeIn(role = YoinMotionRole.Standard)
                    ) togetherWith YoinMotion.fadeOut(role = YoinMotionRole.Standard)
            },
            label = "lyricsLoaded",
        ) { shown ->
            LyricsFullscreenList(
                lyrics = shown.lyrics,
                positionMs = songPositionMs,
                loading = shown.loading,
                showTranslation = shown.showTranslation,
                autoScrollEnabled = autoScrollEnabled,
                recenterRequestKey = recenterRequestKey,
                onUserScroll = onUserScroll,
                onSeekToMs = onSeekToMs,
                viewportGrowthPx = viewportGrowthPx,
                songTitle = shown.songTitle,
                artist = shown.artist,
                upNext = shown.upNext,
                durationMs = shown.durationMs,
            )
        }
    }
}

private data class FullscreenLyricsFrame(
    val lyrics: List<LyricLine>,
    val loading: Boolean,
    val showTranslation: Boolean,
    val songTitle: String?,
    val artist: String?,
    val upNext: UpNextLyrics?,
    val durationMs: Long,
    val autoScroll: Boolean,
)

/**
 * The outro choreography for the next song, in playhead time:
 *  - reveal: from [revealStartMs] the next song's title card and first lines
 *    fade up (to half strength) and rise from below the last line;
 *  - hand-over at [handoffAtMs]: the list glides the next title up to the
 *    exact spot the next song's own list opens with (its first item, at the
 *    top), the next lines come to full strength and this song's lines fade
 *    out. When the song then changes, the new list is pixel-identical below
 *    that title and swaps in place.
 * Null when there is no timed next song or no outro room after the last line.
 */
private class UpNextTiming(val revealStartMs: Long, val handoffAtMs: Long)

private fun upNextTimingFor(lyrics: List<LyricLine>, upNext: UpNextLyrics?, durationMs: Long): UpNextTiming? {
    if (upNext == null || durationMs <= 0L) return null
    val lastStart = lyrics.lastOrNull { it.startMs != null }?.startMs ?: return null
    val handoffAt = durationMs - UpNextHandoffLeadMs
    // The last line still being sung at the hand-over would be cut off.
    if (handoffAt < lastStart + UpNextMinLastLineMs) return null
    val revealStart = maxOf(durationMs - UpNextRevealLeadMs, lastStart + UpNextMinLastLineMs)
        .coerceAtMost(handoffAt - UpNextMinRevealMs)
        .coerceAtLeast(lastStart)
    return UpNextTiming(revealStart, handoffAt)
}

@Composable
private fun LyricsFullscreenList(
    lyrics: List<LyricLine>,
    positionMs: () -> Long,
    loading: Boolean,
    showTranslation: Boolean,
    autoScrollEnabled: Boolean,
    recenterRequestKey: Int,
    onUserScroll: () -> Unit,
    onSeekToMs: (Long) -> Unit,
    viewportGrowthPx: Int,
    songTitle: String?,
    artist: String?,
    upNext: UpNextLyrics?,
    durationMs: Long,
    modifier: Modifier = Modifier,
) {
    if (lyrics.isEmpty()) {
        Box(
            modifier = modifier.fillMaxSize(),
            contentAlignment = Alignment.Center,
        ) {
            if (loading) {
                YoinLoadingIndicator(size = 36.dp)
            } else {
                Text(
                    text = "No lyrics available",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        return
    }

    // derivedStateOf absorbs the 4Hz position tick: the index recomputes per
    // tick, but readers (the LaunchedEffect key, per-row isActive) only
    // recompose when the resolved line actually advances.
    val currentPositionMs by rememberUpdatedState(positionMs)
    val currentIndex by remember(lyrics) {
        derivedStateOf { findCurrentLyricIndex(lyrics, currentPositionMs()) }
    }
    val isSynced = remember(lyrics) { lyrics.any { it.startMs != null } }
    // The song's title card is list item 0 and owns the focus during the
    // intro (before the first timed line), so a new song opens on its name
    // instead of an empty gap. Lyric line i lives at item i + 1.
    val hasHeader = !songTitle.isNullOrBlank()
    val headerOffset = if (hasHeader) 1 else 0
    // Up-next block (outro only): a spacer, the next song's title card, then
    // its lines, appended after this song's last line.
    val upNextTiming = remember(lyrics, upNext, durationMs) { upNextTimingFor(lyrics, upNext, durationMs) }
    val shownUpNext = upNext.takeIf { upNextTiming != null }
    val upNextHeaderIndex = if (shownUpNext != null) headerOffset + lyrics.size + 1 else -1
    val lastIndex = if (shownUpNext != null) upNextHeaderIndex + shownUpNext.lines.size else lyrics.lastIndex + headerOffset
    // Quantized so the 4Hz tick recomposes this scope only when the reveal
    // visibly advances; the springs below smooth the steps.
    val upNextReveal by remember(upNextTiming) {
        derivedStateOf {
            val timing = upNextTiming ?: return@derivedStateOf 0f
            val span = (timing.handoffAtMs - timing.revealStartMs).coerceAtLeast(1L)
            val p = ((currentPositionMs() - timing.revealStartMs).toFloat() / span).coerceIn(0f, 1f)
            (p * UpNextRevealSteps).roundToInt() / UpNextRevealSteps.toFloat()
        }
    }
    val handingOver by remember(upNextTiming) {
        derivedStateOf { upNextTiming != null && currentPositionMs() >= upNextTiming.handoffAtMs }
    }
    val focusItem by remember(lyrics, hasHeader, upNextHeaderIndex) {
        derivedStateOf {
            when {
                handingOver && upNextHeaderIndex >= 0 -> upNextHeaderIndex
                currentIndex >= 0 -> currentIndex + headerOffset
                isSynced && hasHeader -> 0
                else -> -1
            }
        }
    }
    val revealEase = FastOutSlowInEasing.transform(upNextReveal)
    val upNextAlpha by animateFloatAsState(
        targetValue = when {
            shownUpNext == null -> 0f
            handingOver -> 1f
            else -> UpNextRevealAlpha * revealEase
        },
        animationSpec = YoinMotion.slowEffectsSpec(),
        label = "upNextAlpha",
    )
    val upNextRisePx by animateFloatAsState(
        targetValue = if (handingOver) 0f else (1f - revealEase) * with(LocalDensity.current) { UpNextRise.toPx() },
        animationSpec = YoinMotion.slowSpatialSpec(),
        label = "upNextRise",
    )
    // This song steps back as the next one takes the stage.
    val currentBlockAlpha by animateFloatAsState(
        targetValue = if (handingOver) 0f else 1f,
        animationSpec = YoinMotion.slowEffectsSpec(),
        label = "currentLyricsAlpha",
    )
    // The next song's own list opens with no top fade (nothing above its
    // title); lift this list's fade out of the way during the hand-over so the
    // title reads identically on both sides of the swap.
    val topEdgeFade by animateDpAsState(
        targetValue = if (handingOver) 0.dp else 64.dp,
        animationSpec = YoinMotion.slowEffectsSpec(),
        label = "lyricsTopFade",
    )
    val listState = rememberLazyListState()
    // First centring is INSTANT (no animated jump) and happens the moment the
    // pane has a real viewport — so when the stage finishes expanding the active
    // line is already centred, instead of the "page expands, then lyrics jump"
    // beat. Resets per song so a new track re-centres instantly.
    var hasCentered by remember(lyrics) { mutableStateOf(false) }

    // Offset by ~38% of the viewport so the active line reads as the centre of
    // attention rather than a literal midpoint. KEYED on currentIndex / showTranslation
    // so the effect RESTARTS and re-reads them when the line advances or the
    // translation layer toggles — a captured `val` read inside snapshotFlow never
    // re-emits (records no snapshot state), which is why the expanded pane stopped
    // following. The compact LyricsDisplay keys on currentIndex for the same reason.
    // The compact stage measures this list at its final viewport size during
    // expansion. This flow therefore reacts only to genuine viewport changes
    // (e.g. a window resize), not every stage animation frame.
    // `lyrics` MUST be a key (same rule as LyricsDisplay): hasCentered is
    // remember(lyrics)-scoped, so a track change that leaves the derived index
    // numerically equal would otherwise keep the old coroutine alive with the
    // OLD list and OLD hasCentered captured, skipping the per-song instant
    // recentre and turning the next advance into a snap instead of a glide.
    LaunchedEffect(lyrics, listState, autoScrollEnabled, recenterRequestKey, focusItem) {
        if (!autoScrollEnabled || focusItem < 0) return@LaunchedEffect
        val target = focusItem.coerceIn(0, lastIndex)
        var firstAnchor = true
        snapshotFlow { listState.layoutInfo.viewportSize.height }
            .filter { it > 0 }
            .collect { vp ->
                // The next song's title goes where its own list will open it
                // (item 0, at rest), not to the 38% focus line.
                val offsetPx = if (target == upNextHeaderIndex) 0 else -(vp * LyricsFocusFraction).toInt()
                when {
                    // Very first centre of the song: instant, no animation.
                    !hasCentered -> {
                        listState.scrollToItem(target, offsetPx)
                        hasCentered = true
                    }
                    // First reaction to this advance / translation toggle / recenter: glide.
                    firstAnchor -> listState.animateScrollToItem(target, offsetPx)
                    // Actual window/keyboard resizes still keep the line anchored.
                    else -> listState.scrollToItem(target, offsetPx)
                }
                firstAnchor = false
            }
    }

    // Translation toggle: every row grows (or shrinks) by its translation
    // line on the spatial spring. Re-pin the focus line every frame while the
    // rows are still changing size, so the lines open outward around it
    // instead of the active line being shoved down by the rows above it.
    // requestScrollToItem lands in THIS frame's remeasure (frame callbacks
    // run before layout), so the pin never trails the growth by a frame.
    val lastShowTranslation = remember { booleanArrayOf(showTranslation) }
    LaunchedEffect(showTranslation) {
        if (lastShowTranslation[0] == showTranslation) return@LaunchedEffect
        lastShowTranslation[0] = showTranslation
        if (!autoScrollEnabled || focusItem < 0) return@LaunchedEffect
        var lastSignature = -1L
        var stableFrames = 0
        while (stableFrames < TranslationSettleFrames) {
            val signature = withFrameNanos {
                val info = listState.layoutInfo
                val vp = info.viewportSize.height
                if (vp > 0) {
                    listState.requestScrollToItem(focusItem, -(vp * LyricsFocusFraction).toInt())
                }
                info.visibleItemsInfo.fold(0L) { acc, item -> acc * 31 + item.size }
            }
            stableFrames = if (signature == lastSignature) stableFrames + 1 else 0
            lastSignature = signature
        }
    }

    val latestOnUserScroll by rememberUpdatedState(onUserScroll)
    LaunchedEffect(listState) {
        listState.interactionSource.interactions.collect { interaction ->
            if (interaction is DragInteraction.Start) {
                latestOnUserScroll()
            }
        }
    }

    // Scroll-aware: the fades release at the list's ends, so the first and
    // last lyric lines read crisp instead of sitting permanently half-faded
    // above the tab group / Ask Gemini bar.
    Box(
        modifier = modifier
            .fillMaxSize()
            .verticalEdgeFadeOnScroll(listState, top = topEdgeFade, bottom = 64.dp),
    ) {
        // Keep the changing layout modifier on a separate node: attaching it
        // directly to LazyColumn invalidates the list's own measurement too.
        Box(
            Modifier.fillMaxSize().lyricsViewport(
                growthPx = viewportGrowthPx,
                // Untimed lyrics / user-scrolled content keep their top anchor.
                focusFraction = if (autoScrollEnabled && currentIndex >= 0) LyricsFocusFraction else 0f,
            ),
        ) {
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(4.dp),
                contentPadding = PaddingValues(vertical = 48.dp),
            ) {
                val currentBlock: () -> Float = { currentBlockAlpha }
                if (hasHeader) {
                    item(key = "header") {
                        LyricRow(
                            text = songTitle.orEmpty(),
                            secondary = artist,
                            showSecondary = !artist.isNullOrBlank(),
                            isActive = focusItem == 0,
                            distance = if (focusItem >= 0) focusItem else 0,
                            onTap = if (isSynced) ({ onSeekToMs(0L) }) else null,
                            blockAlpha = currentBlock,
                        )
                    }
                }
                itemsIndexed(lyrics, key = { index, _ -> index }) { index, line ->
                    val item = index + headerOffset
                    LyricRow(
                        text = line.text,
                        secondary = line.translation,
                        showSecondary = showTranslation && !line.translation.isNullOrBlank(),
                        isActive = item == focusItem,
                        distance = if (focusItem >= 0) abs(item - focusItem) else 0,
                        onTap = line.startMs?.let { ms -> { onSeekToMs(ms) } },
                        blockAlpha = currentBlock,
                    )
                }
                if (shownUpNext != null) {
                    // Rendered exactly as the next song's own list renders its
                    // title card and lines (same row, same distance falloff,
                    // translations off as they are after a song change), so
                    // the hand-over swap is invisible below the title.
                    val upNextBlock: () -> Float = { upNextAlpha }
                    val upNextRise: () -> Float = { upNextRisePx }
                    item(key = "upNextGap") { Spacer(modifier = Modifier.height(UpNextGap)) }
                    item(key = "upNextHeader") {
                        LyricRow(
                            text = shownUpNext.title,
                            secondary = shownUpNext.artist,
                            showSecondary = shownUpNext.artist.isNotBlank(),
                            isActive = focusItem == upNextHeaderIndex,
                            distance = if (focusItem >= 0) abs(upNextHeaderIndex - focusItem) else 0,
                            onTap = null,
                            blockAlpha = upNextBlock,
                            blockOffsetY = upNextRise,
                        )
                    }
                    itemsIndexed(shownUpNext.lines, key = { index, _ -> "upNext$index" }) { index, line ->
                        val item = upNextHeaderIndex + 1 + index
                        LyricRow(
                            text = line.text,
                            secondary = line.translation,
                            showSecondary = false,
                            isActive = item == focusItem,
                            distance = if (focusItem >= 0) abs(item - focusItem) else 0,
                            onTap = null,
                            blockAlpha = upNextBlock,
                            blockOffsetY = upNextRise,
                        )
                    }
                }
                item { Spacer(modifier = Modifier.height(96.dp)) }
            }
        }
    }
}

@Composable
private fun LyricRow(
    text: String,
    secondary: String?,
    showSecondary: Boolean,
    isActive: Boolean,
    distance: Int,
    onTap: (() -> Unit)?,
    modifier: Modifier = Modifier,
    blockAlpha: () -> Float = { 1f },
    blockOffsetY: () -> Float = { 0f },
) {
    val textColor by animateColorAsState(
        targetValue = if (isActive) {
            MaterialTheme.colorScheme.primary
        } else {
            MaterialTheme.colorScheme.onSurfaceVariant
        },
        animationSpec = YoinMotion.effectsSpring(),
        label = "lyricColor",
    )
    // Gentle distance falloff (same idea as the compact window, lighter so
    // the full view stays readable ahead): the eye lands on the active line
    // and the page thins out around it.
    val alpha by animateFloatAsState(
        targetValue = when {
            isActive -> 1f
            distance <= 1 -> 0.62f
            distance == 2 -> 0.5f
            else -> 0.4f
        },
        animationSpec = YoinMotion.effectsSpring(),
        label = "lyricAlpha",
    )
    val scale by animateFloatAsState(
        targetValue = if (isActive) 1f else 0.96f,
        animationSpec = YoinMotion.defaultSpatialSpec(),
        label = "lyricScale",
    )
    val clickableModifier = if (onTap != null) {
        Modifier.tapWithoutConsumingDrag(onTap = onTap)
    } else {
        Modifier
    }
    Column(
        modifier = modifier
            .fillMaxWidth()
            .then(clickableModifier)
            .padding(vertical = 6.dp)
            .graphicsLayer {
                this.alpha = alpha * blockAlpha()
                translationY = blockOffsetY()
                scaleX = scale
                scaleY = scale
                transformOrigin = androidx.compose.ui.graphics.TransformOrigin(0f, 0.5f)
            },
    ) {
        // Current line = the OLD non-current weight (SemiBold) in the rounded
        // variable cut (ROND 100); non-current drops one more step to Medium.
        // The previous ExtraBold had no real 800 file anyway — it faux-bolded
        // off the 700.
        Text(
            text = text,
            style = if (isActive) {
                MaterialTheme.typography.headlineSmall.copy(
                    fontFamily = GoogleSansFlexRounded,
                    fontWeight = FontWeight.SemiBold,
                )
            } else {
                MaterialTheme.typography.headlineSmall.copy(
                    fontWeight = FontWeight.Medium,
                )
            },
            color = textColor,
        )
        // Translation (or the artist, on the title card). The row's height
        // grows on the spatial spring from the top edge, so the line box
        // opens under the lyric instead of the text popping in and pushing
        // everything below by a full line in one frame. The gap lives INSIDE
        // the animated block, so a collapsed row is exactly one line tall.
        AnimatedVisibility(
            visible = showSecondary,
            enter = expandVertically(
                animationSpec = YoinMotion.spatialSpring(),
                expandFrom = Alignment.Top,
            ) + YoinMotion.fadeIn(role = YoinMotionRole.Standard),
            exit = shrinkVertically(
                animationSpec = YoinMotion.spatialSpring(),
                shrinkTowards = Alignment.Top,
            ) + YoinMotion.fadeOut(role = YoinMotionRole.Standard),
        ) {
            Text(
                text = secondary.orEmpty(),
                style = MaterialTheme.typography.bodyLarge.copy(
                    fontWeight = if (isActive) FontWeight.Medium else FontWeight.Normal,
                ),
                color = textColor.copy(alpha = if (isActive) 0.82f else 0.7f),
                modifier = Modifier.padding(top = 4.dp),
            )
        }
    }
}

private fun Modifier.tapWithoutConsumingDrag(onTap: () -> Unit): Modifier =
    pointerInput(onTap) {
        awaitEachGesture {
            awaitFirstDown(requireUnconsumed = false)
            val up = waitForUpOrCancellation()
            if (up != null) {
                onTap()
            }
        }
    }

/** Outro: how long before the end the next song starts to show. */
private const val UpNextRevealLeadMs = 10_000L

/** Outro: how long before the end the next title glides up and takes over. */
private const val UpNextHandoffLeadMs = 1_400L

/** The last line keeps the stage at least this long before anything moves. */
private const val UpNextMinLastLineMs = 2_000L

/** Shortest reveal worth playing before the hand-over. */
private const val UpNextMinRevealMs = 1_200L

/** Position ticks arrive every 250ms; tolerate one when judging the hand-over. */
private const val UpNextHandoffTickSlackMs = 300L

/** Reveal is a half-strength preview; the hand-over brings it to full. */
private const val UpNextRevealAlpha = 0.75f

/** Recomposition granularity of the reveal (springs smooth between steps). */
private const val UpNextRevealSteps = 24

/** Distance the next song's lines rise while being revealed. */
private val UpNextRise = 28.dp

/** Breathing room between this song's last line and the next title. */
private val UpNextGap = 56.dp

/** Frames of unchanged row sizes before the translation re-pin stops. */
private const val TranslationSettleFrames = 3

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
