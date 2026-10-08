package com.gpo.yoin.ui.nowplaying

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.togetherWith
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
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.toRect
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.gpo.yoin.R
import com.gpo.yoin.ui.component.LyricsTrackTransition
import com.gpo.yoin.ui.component.YoinLoadingIndicator
import com.gpo.yoin.ui.component.ignoreParentHorizontalPadding
import com.gpo.yoin.ui.component.verticalEdgeFadeOnScroll
import com.gpo.yoin.ui.experience.LocalWindowCovered
import com.gpo.yoin.ui.experience.rememberYoinHaptics
import com.gpo.yoin.ui.theme.GoogleSansFlexRounded
import com.gpo.yoin.ui.theme.YoinMotion
import com.gpo.yoin.ui.theme.YoinMotionRole
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.filter

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
    // Select mode (the ✓ lyric tool): taps pick lines instead of seeking and
    // the page stops following the playhead.
    selecting: Boolean = false,
    selectedLines: Set<Int> = emptySet(),
    onToggleLine: (Int) -> Unit = {},
) {
    val followsPlayhead = autoScrollEnabled && !selecting
    val liveTrackKey by rememberUpdatedState(trackKey)
    LyricsTrackTransition(
        trackKey = trackKey,
        data = FullscreenLyricsFrame(
            trackKey, lyrics, loading, showTranslation, songTitle, artist, upNext, durationMs, followsPlayhead,
        ),
        queueIndex = queueIndex,
        positionMs = positionMs,
        // Rows carry their own inset so a selected line's container can reach
        // past the text into the page margin; the pane escapes that much of the
        // caller's padding, so the text itself stays on the page's edge line.
        modifier = modifier.ignoreParentHorizontalPadding(LyricsRowInset),
        continuesInto = { from, fromPositionMs, toKey -> from.handedOverTo(toKey, fromPositionMs) },
    ) { frame, songPositionMs ->
        // Loading → lyrics arriving: the lines rise into place (a short
        // continuation of the song-change drift) while the spinner dissolves.
        // Each side keeps its own snapshot; keyed on emptiness only, so a
        // translation or lyrics swap on a loaded song stays in place.
        AnimatedContent(
            targetState = frame,
            // In-place swap: the outgoing list already shows exactly what the
            // incoming one opens with. Drawn together on the swap frame, every
            // half-transparent line doubled up (a one-frame darkening), so the
            // outgoing side steps out at once. The live side only reads the key.
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
            label = "lyricsLoaded",
        ) { shown ->
            LyricsFullscreenList(
                trackKey = shown.trackKey,
                lyrics = shown.lyrics,
                positionMs = songPositionMs,
                loading = shown.loading,
                showTranslation = shown.showTranslation,
                autoScrollEnabled = followsPlayhead,
                recenterRequestKey = recenterRequestKey,
                // Browsing for lines in select mode isn't "stop following":
                // leaving the mode glides back to the playhead.
                onUserScroll = if (selecting) ({}) else onUserScroll,
                onSeekToMs = onSeekToMs,
                viewportGrowthPx = viewportGrowthPx,
                songTitle = shown.songTitle,
                artist = shown.artist,
                upNext = shown.upNext,
                durationMs = shown.durationMs,
                selecting = selecting,
                selectedLines = selectedLines,
                onToggleLine = onToggleLine,
            )
        }
    }
}

private data class FullscreenLyricsFrame(
    val trackKey: Any,
    val lyrics: List<LyricLine>,
    val loading: Boolean,
    val showTranslation: Boolean,
    val songTitle: String?,
    val artist: String?,
    val upNext: UpNextLyrics?,
    val durationMs: Long,
    val autoScroll: Boolean,
)

private fun FullscreenLyricsFrame.handedOverTo(toKey: Any, atPositionMs: Long): Boolean =
    upNextHandedOver(lyrics, upNext, durationMs, autoScroll, toKey, atPositionMs)

/**
 * Whether a list that stopped at [atPositionMs] had already staged [toKey] —
 * its up-next block handed over at the top — so the song change swaps in
 * place. Only while the list followed the playhead (the outro is
 * playhead-driven); otherwise the change is the plain slide.
 */
internal fun upNextHandedOver(
    lyrics: List<LyricLine>,
    upNext: UpNextLyrics?,
    durationMs: Long,
    followedPlayhead: Boolean,
    toKey: Any,
    atPositionMs: Long,
): Boolean {
    if (!followedPlayhead || upNext?.songId != toKey) return false
    val handoffAt = upNextTimingFor(lyrics, upNext, durationMs)?.handoffAtMs ?: return false
    return atPositionMs >= handoffAt - UpNextHandoffTickSlackMs
}

@Composable
private fun LyricsFullscreenList(
    trackKey: Any,
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
    selecting: Boolean,
    selectedLines: Set<Int>,
    onToggleLine: (Int) -> Unit,
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
                    text = stringResource(R.string.np_lyrics_none),
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
    // The outro is a playhead-following choreography: when the page isn't
    // following (the user scrolled away to read, or is picking lines in
    // select mode) the hand-over would only fade this song's lines out from
    // under them with no glide to show the next title — so it stays out, and
    // the song change falls back to the plain slide.
    val upNextTiming = remember(lyrics, upNext, durationMs, autoScrollEnabled) {
        if (!autoScrollEnabled) null else upNextTimingFor(lyrics, upNext, durationMs)
    }
    val shownUpNext = upNext.takeIf { upNextTiming != null }
    val upNextHeaderIndex = if (shownUpNext != null) headerOffset + lyrics.size + 1 else -1
    val lastIndex = if (shownUpNext != null) upNextHeaderIndex + shownUpNext.lines.size else lyrics.lastIndex + headerOffset
    // Quantized so the 4Hz tick recomposes this scope only when the reveal
    // visibly advances; the springs below smooth the steps.
    val upNextReveal by remember(upNextTiming) {
        derivedStateOf { upNextTiming?.outroProgress(currentPositionMs()) ?: 0f }
    }
    val handingOver by remember(upNextTiming) {
        derivedStateOf { upNextTiming != null && currentPositionMs() >= upNextTiming.handoffAtMs }
    }
    // I-2: the last line stretches over the same outro window the next song
    // rises in (one progress, so the two read as one gesture). Computed with
    // or without a next song staged — the held note doesn't need one — and
    // whether or not the page follows the playhead.
    val lastLineOutro = remember(lyrics, durationMs) { lastLineOutroFor(lyrics, durationMs) }
    val lastLineItem = remember(lyrics, headerOffset) {
        lyrics.indexOfLast { it.startMs != null }.let { if (it < 0) -1 else it + headerOffset }
    }
    val lastLineStretch by remember(lastLineOutro) {
        derivedStateOf { lastLineOutro?.outroProgress(currentPositionMs()) ?: 0f }
    }
    // Tell the Now Playing background when this page has staged the next song
    // in place (the same rule LyricsTrackTransition swaps on): that song change
    // is already its own transition, so no pulse rides on top of it.
    val transportSignal = LocalNowPlayingTransportSignal.current
    val handoverStaged by remember(upNextTiming) {
        derivedStateOf {
            upNextTiming != null && currentPositionMs() >= upNextTiming.handoffAtMs - UpNextHandoffTickSlackMs
        }
    }
    val stagedNow = handoverStaged
    val stagedSongId = shownUpNext?.songId
    SideEffect {
        transportSignal?.publishLyricsHandover(fromSongId = trackKey, toSongId = stagedSongId, staged = stagedNow)
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
    // The next title waits out the first half of the outro (the last line
    // stretches alone), then surfaces faintly; its lines stay hidden until the
    // hand-over — a quiet "what's next", not a second lyric sheet.
    val revealEase = FastOutSlowInEasing.transform(
        ((upNextReveal - UpNextRevealLateStart) / (1f - UpNextRevealLateStart)).coerceIn(0f, 1f),
    )
    val upNextLinesAlpha by animateFloatAsState(
        targetValue = if (shownUpNext != null && handingOver) 1f else 0f,
        animationSpec = YoinMotion.slowEffectsSpec(),
        label = "upNextLinesAlpha",
    )
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
    // The up-next block as laid out (next title's top → its last line's
    // bottom); 0 until both ends are on screen, which errs toward a longer
    // tail. Read only by the tail's layout, so it never recomposes the list.
    val upNextBlockPx by remember(listState, upNextHeaderIndex, lastIndex) {
        derivedStateOf {
            if (upNextHeaderIndex < 0) return@derivedStateOf 0
            val items = listState.layoutInfo.visibleItemsInfo
            val header = items.firstOrNull { it.index == upNextHeaderIndex }
            val last = items.firstOrNull { it.index == lastIndex }
            if (header == null || last == null) 0 else last.offset + last.size - header.offset
        }
    }
    // Select mode's caption gets its own band: the list's window (and its top
    // fade) opens below the caption instead of lines sliding under it. The
    // list itself keeps its size and place, so no line moves.
    var selectCaptionHeightPx by remember { mutableIntStateOf(0) }
    val selectCaptionBandPx by animateFloatAsState(
        targetValue = if (selecting) selectCaptionHeightPx.toFloat() else 0f,
        animationSpec = YoinMotion.defaultSpatialSpec(role = YoinMotionRole.Standard),
        label = "selectCaptionBand",
    )
    val captionBand: () -> Int = { selectCaptionBandPx.roundToInt() }
    val captionSizeSpec = YoinMotion.fastSpatialSpec<IntSize>(role = YoinMotionRole.Standard)
    // Select mode picks any lines, up to the cap (nextLyricSelection). A tap
    // that would add to a full pick is refused here — a reject haptic and the
    // caption names the cap for a moment — so the pick never silently stays put.
    val selectHaptics = rememberYoinHaptics()
    var limitNudgeTick by remember { mutableIntStateOf(0) }
    var limitNudge by remember { mutableStateOf(false) }
    LaunchedEffect(limitNudgeTick) {
        if (limitNudgeTick == 0) return@LaunchedEffect
        limitNudge = true
        delay(SelectLimitNudgeMs)
        limitNudge = false
    }
    // Any accepted tap (or leaving the mode) retires the nudge at once.
    LaunchedEffect(selectedLines, selecting) { limitNudge = false }
    val latestOnToggleLine by rememberUpdatedState(onToggleLine)
    val tapInSelectMode: (Int) -> Unit = remember(selectedLines) {
        { index ->
            if (nextLyricSelection(selectedLines, index).hitLimit) {
                selectHaptics.performReject()
                limitNudgeTick += 1
            } else {
                latestOnToggleLine(index)
            }
        }
    }
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
    // Covered by a Yoin window (its clock frozen): land the line at once, so the
    // frame kept for the reveal is current and nothing glides once it is seen.
    val windowCovered = LocalWindowCovered.current
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
                    firstAnchor && !windowCovered.value -> listState.animateScrollToItem(target, offsetPx)
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
    Box(modifier = modifier.fillMaxSize()) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .lyricsWindowBelowBand(captionBand)
                .verticalEdgeFadeOnScroll(listState, top = topEdgeFade, bottom = 64.dp)
                .clipToBounds()
                .lyricsWindowContentAboveBand(captionBand),
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
                    verticalArrangement = Arrangement.spacedBy(LyricsItemSpacing),
                    contentPadding = PaddingValues(vertical = 48.dp),
                ) {
                    val currentBlock: () -> Float = { currentBlockAlpha }
                    if (hasHeader) {
                        item(key = "header") {
                            LyricRow(
                                text = songTitle.orEmpty(),
                                secondary = artist,
                                showSecondary = !artist.isNullOrBlank(),
                                isActive = !selecting && focusItem == 0,
                                distance = if (focusItem >= 0) focusItem else 0,
                                onTap = if (isSynced && !selecting) ({ onSeekToMs(0L) }) else null,
                                blockAlpha = currentBlock,
                                selectMode = selecting,
                                // Never pickable: once a pick exists it steps back.
                                selectRole = if (selectedLines.isNotEmpty()) {
                                    LyricSelectionRole.Outside
                                } else {
                                    LyricSelectionRole.Idle
                                },
                            )
                        }
                    }
                    itemsIndexed(lyrics, key = { index, _ -> index }) { index, line ->
                        val item = index + headerOffset
                        val active = !selecting && item == focusItem
                        val selectRole = lyricSelectionRole(index, selectedLines)
                        LyricRow(
                            text = line.text,
                            secondary = line.translation,
                            showSecondary = showTranslation && !line.translation.isNullOrBlank(),
                            isActive = active,
                            // Held while it is the current line; the hand-over
                            // (focus → next title) lets it go.
                            lastLineStretch = if (item == lastLineItem) {
                                if (active) lastLineStretch else 0f
                            } else {
                                null
                            },
                            distance = if (focusItem >= 0) abs(item - focusItem) else 0,
                            onTap = if (selecting) {
                                { tapInSelectMode(index) }
                            } else {
                                line.startMs?.let { ms -> { onSeekToMs(ms) } }
                            },
                            blockAlpha = currentBlock,
                            selectMode = selecting,
                            selectRole = if (selecting) selectRole else LyricSelectionRole.Idle,
                            runTop = index - 1 !in selectedLines,
                            runBottom = index + 1 !in selectedLines,
                            // spotoolfy keeps the line being sung lit while you pick.
                            playingInSelectMode = selecting && item == focusItem,
                        )
                    }
                    if (shownUpNext != null) {
                        // Rendered exactly as the next song's own list renders its
                        // title card and lines (same row, same distance falloff,
                        // translations off as they are after a song change), so
                        // the hand-over swap is invisible below the title.
                        val upNextBlock: () -> Float = { upNextAlpha }
                        val upNextLinesBlock: () -> Float = { upNextLinesAlpha }
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
                                blockAlpha = upNextLinesBlock,
                                blockOffsetY = upNextRise,
                            )
                        }
                    }
                    if (shownUpNext != null) {
                        // Long enough that the hand-over can park the next
                        // title at the top, exactly where its own list opens.
                        item(key = "upNextTail") {
                            Spacer(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .upNextTail(blockPx = { upNextBlockPx })
                                    .fillParentMaxHeight(),
                            )
                        }
                    } else {
                        item { Spacer(modifier = Modifier.height(LyricsListTail)) }
                    }
                }
            }
        }
        // Select mode's caption sits in its own band above the list's window
        // (see selectCaptionBandPx), outside the fade layer so it stays crisp.
        AnimatedVisibility(
            visible = selecting,
            enter = YoinMotion.fadeIn(role = YoinMotionRole.Standard),
            exit = YoinMotion.fadeOut(role = YoinMotionRole.Standard),
            modifier = Modifier
                .align(Alignment.TopStart)
                .onSizeChanged { if (it.height > 0) selectCaptionHeightPx = it.height }
                .padding(horizontal = LyricsRowInset, vertical = 8.dp),
        ) {
            AnimatedContent(
                // Each side keeps its own text AND tone (the nudge reads in primary).
                targetState = lyricsSelectionLabel(
                    selectedLines.size,
                    limitNudge = limitNudge,
                    resources = LocalContext.current.resources,
                ) to limitNudge,
                transitionSpec = {
                    (
                        YoinMotion.fadeIn(role = YoinMotionRole.Standard) togetherWith
                            YoinMotion.fadeOut(role = YoinMotionRole.Standard)
                        ) using SizeTransform(clip = false) { _, _ -> captionSizeSpec }
                },
                contentAlignment = Alignment.CenterStart,
                label = "selectCaption",
            ) { (label, nudging) ->
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelLarge,
                    color = if (nudging) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
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
    // Select mode: no playhead emphasis; each run of adjacent picked lines
    // sits on one connected filled container (spotoolfy's grouped corners);
    // once the pick is full, the lines it can't take step back.
    selectMode: Boolean = false,
    selectRole: LyricSelectionRole = LyricSelectionRole.Idle,
    // Picked rows only: this row opens / closes its run (full corner there,
    // a tight one where it meets a picked neighbour).
    runTop: Boolean = true,
    runBottom: Boolean = true,
    // The line being sung, while picking: lit in primary, nothing more.
    playingInSelectMode: Boolean = false,
    // The song's last timed line only (null on every other row): its I-2
    // stretch, the quantized outro progress (0 = at rest).
    lastLineStretch: Float? = null,
) {
    val selected = selectMode && selectRole == LyricSelectionRole.Picked
    val emphasized = isActive || selected
    val textColor by animateColorAsState(
        targetValue = when {
            selected -> MaterialTheme.colorScheme.onPrimaryContainer
            isActive || playingInSelectMode -> MaterialTheme.colorScheme.primary
            else -> MaterialTheme.colorScheme.onSurfaceVariant
        },
        animationSpec = YoinMotion.effectsSpring(),
        label = "lyricColor",
    )
    val selectionContainer by animateColorAsState(
        targetValue = when {
            selected -> MaterialTheme.colorScheme.primaryContainer
            else -> MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0f)
        },
        animationSpec = YoinMotion.effectsSpring(),
        label = "lyricSelection",
    )
    // The run reads as one block: where two picked rows meet, their corners
    // tighten (a spatial change — the container's outline moves).
    val topCorner by animateDpAsState(
        targetValue = if (selected && !runTop) SelectedRunInnerCorner else SelectedLineCorner,
        animationSpec = YoinMotion.fastSpatialSpec(role = YoinMotionRole.Standard),
        label = "lyricSelectionTopCorner",
    )
    val bottomCorner by animateDpAsState(
        targetValue = if (selected && !runBottom) SelectedRunInnerCorner else SelectedLineCorner,
        animationSpec = YoinMotion.fastSpatialSpec(role = YoinMotionRole.Standard),
        label = "lyricSelectionBottomCorner",
    )
    // Gentle distance falloff (same idea as the compact window, lighter so
    // the full view stays readable ahead): the eye lands on the active line
    // and the page thins out around it.
    val alpha by animateFloatAsState(
        targetValue = when {
            emphasized -> 1f
            selectMode -> if (selectRole == LyricSelectionRole.Outside) OutsideLineAlpha else SelectModeLineAlpha
            distance <= 1 -> 0.62f
            distance == 2 -> 0.5f
            else -> 0.4f
        },
        animationSpec = YoinMotion.effectsSpring(),
        label = "lyricAlpha",
    )
    val scale by animateFloatAsState(
        targetValue = if (emphasized) 1f else 0.96f,
        animationSpec = YoinMotion.defaultSpatialSpec(),
        label = "lyricScale",
    )
    val clickableModifier = if (onTap != null) {
        Modifier.tapWithoutConsumingDrag(onTap = onTap)
    } else {
        Modifier
    }
    // Reused by the run's mixed-corner container (draw phase only).
    val selectionPath = remember { Path() }
    Column(
        modifier = modifier
            .fillMaxWidth()
            .then(clickableModifier)
            .drawBehind {
                if (selectionContainer.alpha > 0f) {
                    val top = topCorner.toPx().coerceAtLeast(0f)
                    val bottom = bottomCorner.toPx().coerceAtLeast(0f)
                    if (top == bottom) {
                        drawRoundRect(color = selectionContainer, cornerRadius = CornerRadius(top))
                    } else {
                        selectionPath.reset()
                        selectionPath.addRoundRect(
                            RoundRect(
                                rect = size.toRect(),
                                topLeft = CornerRadius(top),
                                topRight = CornerRadius(top),
                                bottomRight = CornerRadius(bottom),
                                bottomLeft = CornerRadius(bottom),
                            ),
                        )
                        drawPath(selectionPath, color = selectionContainer)
                    }
                }
            }
            .padding(horizontal = LyricsRowInset, vertical = 6.dp)
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
        val lineStyle = if (emphasized) {
            MaterialTheme.typography.headlineSmall.copy(
                fontFamily = GoogleSansFlexRounded,
                fontWeight = FontWeight.SemiBold,
            )
        } else {
            MaterialTheme.typography.headlineSmall.copy(
                fontWeight = FontWeight.Medium,
            )
        }
        if (lastLineStretch != null) {
            // Held last line (I-2): the whole line widens at draw time on the
            // slow spatial spring and lets go on the default one — no relayout,
            // so it never judders (a full row widens a little into the inset).
            HeldLastLineText(
                text = text,
                style = lineStyle,
                color = textColor,
                stretch = lastLineStretch,
            )
        } else {
            Text(
                text = text,
                style = lineStyle,
                color = textColor,
            )
        }
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
                    fontWeight = if (emphasized) FontWeight.Medium else FontWeight.Normal,
                ),
                color = textColor.copy(alpha = if (emphasized) 0.82f else 0.7f),
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

/**
 * Each lyric row's own horizontal inset. The pane escapes the same amount of
 * its caller's page padding, so text stays on the page's edge line while a
 * selected line's container reaches this far past it.
 */
internal val LyricsRowInset = 12.dp

/** Corner of a selected line's container (the run's outer corners). */
private val SelectedLineCorner = 16.dp

/**
 * Where two picked rows meet: spotoolfy's grouped-corner ratio (12 outer / 4
 * inner), so the run reads as one block split by the list's row gap.
 */
private val SelectedRunInnerCorner = 4.dp

/** Unpicked lines in select mode while the pick has room: one even strength, no playhead falloff. */
private const val SelectModeLineAlpha = 0.7f

/** Lines a tap can't add (the pick is full; the title card): stepped back, still legible. */
private const val OutsideLineAlpha = 0.42f

/** How long the caption names the cap after a refused tap. */
private const val SelectLimitNudgeMs = 1_800L

/** Reveal is a faint preview of the title only; the hand-over brings it to full. */
private const val UpNextRevealAlpha = 0.4f

/** Share of the outro window that passes before the next title starts to surface. */
private const val UpNextRevealLateStart = 0.5f

/** Distance the next song's lines rise while being revealed. */
private val UpNextRise = 28.dp

/** Breathing room between this song's last line and the next title. */
private val UpNextGap = 56.dp

/** Gap between list rows. */
private val LyricsItemSpacing = 4.dp

/** Closing space under the last row, so the last line can leave the bottom fade. */
private val LyricsListTail = 96.dp

/**
 * Closing space under the up-next block (pure geometry, px).
 *
 * The hand-over scrolls the next title to offset 0 — the top of the list's
 * content area, which is exactly where the next song's own list opens it at
 * rest. A LazyColumn can only park an item there when at least a content
 * area's worth of list follows that item's top. With next lyrics shorter than
 * the window, the scroll clamped early, the title stopped low, and the "in
 * place" swap jumped up by the shortfall (2026-10-05 QA: ~105dp on the
 * tablet). The tail makes up exactly that shortfall, and never closes the
 * list shorter than [minTailPx].
 *
 * @param contentAreaPx the list's viewport minus its vertical content padding
 *   (what `fillParentMaxHeight()` gives an item).
 * @param upNextBlockPx next title's top → its last line's bottom, spacing
 *   included; 0 when unknown, which only makes the tail longer.
 * @param itemSpacingPx the list's gap between rows (one sits before the tail).
 */
internal fun upNextTailPx(
    contentAreaPx: Int,
    upNextBlockPx: Int,
    itemSpacingPx: Int,
    minTailPx: Int,
): Int = maxOf(minTailPx, contentAreaPx - upNextBlockPx - itemSpacingPx)

/**
 * Sizes the tail by [upNextTailPx]. Chain BEFORE `fillParentMaxHeight()`,
 * whose measured height is the content area. [blockPx] is read in layout, so
 * a newly measured block only re-lays the tail out.
 */
private fun Modifier.upNextTail(blockPx: () -> Int): Modifier = layout { measurable, constraints ->
    val area = measurable.measure(constraints)
    val tail = upNextTailPx(
        contentAreaPx = area.height,
        upNextBlockPx = blockPx(),
        itemSpacingPx = LyricsItemSpacing.roundToPx(),
        minTailPx = LyricsListTail.roundToPx(),
    )
    layout(area.width, tail) { area.place(0, 0) }
}

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
