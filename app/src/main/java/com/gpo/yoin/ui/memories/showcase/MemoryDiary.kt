package com.gpo.yoin.ui.memories.showcase

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineBreak
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.isSpecified
import androidx.compose.ui.unit.sp
import com.gpo.yoin.R
import com.gpo.yoin.ui.component.MarqueeText
import com.gpo.yoin.ui.component.SeamTop
import com.gpo.yoin.ui.component.formatNotePosition
import com.gpo.yoin.ui.component.seamDissolve
import com.gpo.yoin.ui.component.seamDissolveViewport
import com.gpo.yoin.ui.component.seamFade
import com.gpo.yoin.ui.component.verticalEdgeFadeOnScroll
import com.gpo.yoin.ui.detail.AlbumNeoDbSync
import com.gpo.yoin.ui.experience.YoinHaptics
import com.gpo.yoin.ui.experience.rememberYoinHaptics
import com.gpo.yoin.ui.memories.MemoryDiaryTrack
import com.gpo.yoin.ui.memories.formatMemoryChromeDate
import com.gpo.yoin.ui.memories.MemoryEntityType
import com.gpo.yoin.ui.memories.MemoryEntry
import com.gpo.yoin.ui.memories.MemoryTrack
import com.gpo.yoin.ui.memories.MemoryWriting
import com.gpo.yoin.ui.memories.copy.MemoryDates
import com.gpo.yoin.ui.memories.copy.MemoryFooterStats
import com.gpo.yoin.ui.memories.copy.MemoryProseLanguage
import com.gpo.yoin.ui.memories.copy.MemoryScores
import com.gpo.yoin.ui.memories.copy.MemoryTitleKind
import com.gpo.yoin.ui.memories.emblem.GrooveBeat
import com.gpo.yoin.ui.memories.emblem.GrooveFallback
import com.gpo.yoin.ui.memories.emblem.GrooveHapticPlayer
import com.gpo.yoin.ui.memories.emblem.GrooveHapticRoute
import com.gpo.yoin.ui.memories.emblem.GrooveHapticTrace
import com.gpo.yoin.ui.memories.emblem.GrooveHapticTraceEvent
import com.gpo.yoin.ui.memories.emblem.GroovePrimitive
import com.gpo.yoin.ui.memories.emblem.LocalHapticTrace
import com.gpo.yoin.ui.memories.emblem.rememberGrooveHapticPlayer
import com.gpo.yoin.ui.theme.YoinContainerShapes
import com.gpo.yoin.ui.theme.YoinMotion
import com.gpo.yoin.ui.theme.YoinMotionRole
import com.gpo.yoin.ui.theme.YoinSerifTitle
import java.time.LocalDate
import java.time.ZoneId
import kotlin.math.max
import kotlin.math.roundToInt
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.withTimeoutOrNull

/*
 * The diary state of one memory (twostate4 `diary(m)`, phone two-state, v4.1). One vertical scroll, its own
 * per page, under the bar; from the top:
 *
 *  · the title row — Yoin's title (the AI title in the serif, or the motif) with the groove drawn natively
 *    at 48dp at its right end; fallback A's row is Yoin's paragraph beside the 48;
 *  · Yoin's paragraph (no review only): how you listened, then the question in the same breath;
 *  · your entry — the review, or today's blank page ([MemoryDiaryEntry]);
 *  · • • • between two real entries (the review and the liner), 40dp of air after a blank page;
 *  · the liner, option A: album notes first (a hollow bead), then only the rated or noted tracks as 48dp
 *    rows with their notes hanging under them, lyric-plain (a stamp and the words); a tap plays from there,
 *    and the note the playhead is inside lights (colour only, nothing dimmed, no auto-scroll);
 *  · the end, 56dp after the last block: the run-out groove, the two numerals (plays; days since the first
 *    play — the whole footer only with play history), Go to album, and the quiet NeoDB line (configured only).
 *
 * Every block is a fully laid-out element; the morph only moves and fades them ([MemoryPageMorph]). A pull
 * past the top of the text is the deck's nested scroll ([MemoriesDiaryDeck.connection]); the text takes no
 * input until the diary is open.
 */

/** What the diary reads and does beyond the deck: the playhead, writing, NeoDB. Reads are snapshot reads. */
@Stable
internal interface MemoriesDiaryHost {
    /** The note the playhead is inside (NP's rule), or null. */
    val litNoteId: String? get() = null

    /** The playing track's raw id, or null. */
    val playingTrackId: String? get() = null

    /** A review that was being saved for [memory] (kept after a failed save). */
    fun reviewDraft(memory: MemoryEntry): String? = null

    fun saveReview(memory: MemoryEntry, text: String) = Unit

    /** Titles can be edited here (the app's ViewModel, the harness); false: previews. */
    val titlesEditable: Boolean get() = false

    /** The user named [memory] [title] (trimmed, never blank): the card takes it at once. */
    fun saveMemoryTitle(memory: MemoryEntry, title: String) = Unit

    /** Take the user's title off [memory]: back to Yoin's own. */
    fun restoreMemoryTitle(memory: MemoryEntry) = Unit

    /** A track row: play the track from its start. */
    fun playTrack(memory: MemoryEntry, track: MemoryTrack) = Unit

    /** A note row: play its track from the note's anchor (from the start when it has none). */
    fun playNote(memory: MemoryEntry, track: MemoryTrack, note: MemoryWriting) = Unit

    val neoDbConfigured: Boolean get() = false

    /** Where NeoDB stands for [memory]'s album (the album page's states) — the one read here that is a flow. */
    fun neoDbSync(memory: MemoryEntry): Flow<AlbumNeoDbSync> = flowOf(AlbumNeoDbSync.Unknown)

    /** Push [memory]'s album now: the line's retry (a saved review syncs on its own). */
    fun pushNeoDb(memory: MemoryEntry) = Unit
}

/** The diary's own haptics: TICK at a scale (the composition path honours it) and CONFIRM. */
@Stable
internal class DiaryHaptics(
    private val player: GrooveHapticPlayer,
    private val haptics: YoinHaptics,
    private val scope: CoroutineScope,
    private val trace: GrooveHapticTrace?,
) {
    fun tick(scale: Float, what: String) {
        player.play(listOf(GrooveBeat(0, GroovePrimitive.TICK, scale, GrooveFallback.Tick, what)), scope, "diary")
    }

    /** CLOCK_TICK: a drag crossed its commit line (and again when it goes back). */
    fun clockTick(what: String) {
        haptics.performTick()
        trace?.onBeat(
            GrooveHapticTraceEvent(
                tag = "diary",
                beat = GrooveBeat(0, GroovePrimitive.TICK, 0.5f, GrooveFallback.Tick, "CLOCK_TICK · $what"),
                firedAtMs = 0L,
                route = GrooveHapticRoute.ViewFallback,
            ),
        )
    }

    fun confirm(what: String) {
        haptics.performConfirm()
        trace?.onBeat(
            GrooveHapticTraceEvent(
                tag = "diary",
                beat = GrooveBeat(0, GroovePrimitive.THUD, 1f, GrooveFallback.Confirm, "CONFIRM · $what"),
                firedAtMs = 0L,
                route = GrooveHapticRoute.ViewFallback,
            ),
        )
    }
}

@Composable
internal fun rememberDiaryHaptics(): DiaryHaptics {
    val player = rememberGrooveHapticPlayer()
    val haptics = rememberYoinHaptics()
    val scope = rememberCoroutineScope()
    val trace = LocalHapticTrace.current
    return remember(player, haptics, scope, trace) { DiaryHaptics(player, haptics, scope, trace) }
}

/** Diary geometry (prototype `.ts-bin` and the liner). Layout constants, not motion tokens. */
internal object MemoryDiaryTokens {
    val SideInset = 24.dp
    val TopInset = 12.dp
    val BottomInset = 100.dp
    val EmblemGap = 14.dp
    val HeadGap = 28.dp
    val HeadGapWithAsk = 24.dp
    val ParagraphTop = 10.dp
    val RowMin = 48.dp
    val RowBleed = 12.dp
    val NotedGroupGap = 8.dp
    val BlankLinerGap = 40.dp
    val EndGap = 56.dp
    val RunOut = 28.dp
    val RunOutBottom = 22.dp
    const val RunOutAlpha = 0.45f
    val StatGap = 48.dp
    val GoTop = 32.dp
    val BottomFade = 40.dp

    /** A lone short review rests on the view's optical line (38% down). */
    const val OpticalLine = 0.38f
}

/**
 * One memory's diary. [interactive]: the diary is open and this page is current (input and focus only
 * then). [settled]: the page is at rest with the diary fully open (the liner's marquees only run then).
 */
@Composable
internal fun MemoryDiary(
    memory: MemoryEntry,
    tones: MemoryPaletteTones,
    morph: MemoryPageMorph,
    deck: MemoriesDiaryDeck,
    scroll: ScrollState,
    today: LocalDate,
    zone: ZoneId,
    interactive: Boolean,
    settled: Boolean,
    host: MemoriesDiaryHost,
    haptics: DiaryHaptics,
    navBottom: Dp,
    emblem: @Composable (Modifier) -> Unit,
    onOpenAlbum: () -> Unit,
    modifier: Modifier = Modifier,
    /** Medium: the diary's column (min(640, W − 32)), centred in the full-width scroll; phone: the page. */
    column: Dp = Dp.Unspecified,
    /** The title can be tapped to edit (while [interactive]); null: read-only. */
    titleEditing: MemoryTitleEditing? = null,
) {
    val type = LocalMemoriesType.current
    val yoinTitle = memory.memoryTitleKind != MemoryTitleKind.ALBUM && !memory.memoryTitle.isNullOrBlank()
    val paragraph = rememberHeldParagraph(memory) { deck.p <= 0f }
    val side = if (type.large) type.diarySide else MemoryDiaryTokens.SideInset

    BoxWithConstraints(
        modifier = modifier
            .fillMaxSize()
            // closed (or a neighbour's): the diary lies under the card and is not read (the page's pane
            // title announces it opening and closing)
            .then(if (interactive) Modifier else Modifier.clearAndSetSemantics { })
            .graphicsLayer { with(morph) { diaryViewport() } },
    ) {
        val viewportPx = constraints.maxHeight
        Column(
            modifier = Modifier
                .fillMaxSize()
                .onPlaced(morph::onViewport)
                .imePadding()
                .nestedScroll(deck.connection)
                .seamDissolveViewport(top = SeamTop.Chrome) { scroll.value.toFloat() }
                .verticalEdgeFadeOnScroll(scroll, bottom = MemoryDiaryTokens.BottomFade)
                .verticalScroll(scroll, enabled = interactive, overscrollEffect = null)
                .graphicsLayer { with(morph) { diaryBody() } }
                .onPlaced(morph::onDiaryInner),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Column(
                modifier = Modifier
                    .then(if (column.isSpecified) Modifier.width(column) else Modifier.fillMaxWidth())
                    .padding(
                        start = side,
                        end = side,
                        top = MemoryDiaryTokens.TopInset,
                        bottom = MemoryDiaryTokens.BottomInset + navBottom,
                    ),
            ) {
                val paragraphBlock = if (paragraph != null) 0 else -1
                DiaryHead(
                    memory = memory,
                    tones = tones,
                    yoinTitle = yoinTitle,
                    paragraph = paragraph,
                    paragraphBlock = paragraphBlock,
                    morph = morph,
                    emblem = emblem,
                    titleEditing = titleEditing,
                    titleEnabled = interactive,
                )
                DiaryBlocks(
                    memory = memory,
                    tones = tones,
                    today = today,
                    zone = zone,
                    interactive = interactive,
                    settled = settled,
                    host = host,
                    haptics = haptics,
                    onOpenAlbum = onOpenAlbum,
                    firstBlock = paragraphBlock + 1,
                    block = { k -> Modifier.diaryBlock(morph, k) },
                    optical = DiaryOptical(viewportPx = viewportPx, topInset = MemoryDiaryTokens.TopInset),
                )
            }
        }
    }
}

/** The optical line's frame for a lone short review (the phone diary's viewport); null = no optical slot. */
internal class DiaryOptical(val viewportPx: Int, val topInset: Dp)

/**
 * The diary from your entry on — the review or today's blank page, • • • (or air), the liner, the run-out
 * groove and the foot. Shared by the two-state diary and the spread's right page. [block] gives the k-th
 * block its layer, k counting from [firstBlock] (the morph's staggered rise; nothing in the spread). [showGo]: the foot ends with Go to album
 * (the spread has it on the left page instead). [optical]: a lone short review rests on the view's optical
 * line (the two states; the spread centres its whole right page instead).
 */
@Composable
internal fun DiaryBlocks(
    memory: MemoryEntry,
    tones: MemoryPaletteTones,
    today: LocalDate,
    zone: ZoneId,
    interactive: Boolean,
    settled: Boolean,
    host: MemoriesDiaryHost,
    haptics: DiaryHaptics,
    onOpenAlbum: () -> Unit,
    firstBlock: Int,
    block: (Int) -> Modifier,
    optical: DiaryOptical?,
    showGo: Boolean = true,
) {
    var k = firstBlock
    val draft = host.reviewDraft(memory)
    val liner = memory.diaryAlbumNotes.isNotEmpty() || memory.diaryTracks.isNotEmpty()
    val review = memory.review
    val history = diaryFooterStats(memory.playsInYoin, memory.firstHeardAt, zone, today)
    // a lone short review (and nothing after it) rests on the optical line
    val opticalOn = optical != null && review != null && review.text.length <= ShortReviewMax && !liner

    // the note tapped: its TICK lands with the highlight (or, unanchored, when its track is current)
    var pendingLight by remember { mutableStateOf<PendingLight?>(null) }
    LaunchedEffect(pendingLight) {
        val pending = pendingLight ?: return@LaunchedEffect
        val landed = withTimeoutOrNull(LightTimeoutMs) {
            snapshotFlow { host.playingTrackId to host.litNoteId }
                .first { (track, lit) -> track == pending.trackId && (pending.noteId == null || lit == pending.noteId) }
        }
        if (landed != null) haptics.tick(NoteTickScale, "note row · highlight landed")
        pendingLight = null
    }

    val entryBlock = block(k++)
    OpticalSlot(
        enabled = opticalOn,
        viewportPx = optical?.viewportPx ?: 0,
        topInsetPx = with(LocalDensity.current) { (optical?.topInset ?: 0.dp).roundToPx() },
    ) {
        MemoryDiaryEntry(
            review = review,
            today = today,
            zone = zone,
            tones = tones,
            draft = draft,
            enabled = interactive,
            onSave = { text -> host.saveReview(memory, text) },
            onConfirm = { haptics.confirm("review saved") },
            modifier = entryBlock,
        )
    }
    if (liner) {
        if (review != null) {
            DiarySeparator(tones, block(k++))
        } else {
            Spacer(Modifier.height(MemoryDiaryTokens.BlankLinerGap))
        }
        DiaryLiner(
            memory = memory,
            tones = tones,
            interactive = interactive,
            settled = settled,
            host = host,
            onNote = { track, note ->
                pendingLight = PendingLight(track.trackId, note.noteId.takeIf { note.positionMs != null })
                host.playNote(memory, track, note)
            },
            onTrack = { track ->
                haptics.tick(TrackTickScale, "track row")
                host.playTrack(memory, track)
            },
            modifier = block(k++),
        )
    }
    Spacer(Modifier.height(MemoryDiaryTokens.EndGap))
    if (diaryShowsRunOut(history)) DiaryRunOut(tones, block(k++))
    DiaryFoot(
        memory = memory,
        tones = tones,
        history = history,
        interactive = interactive,
        host = host,
        onOpenAlbum = onOpenAlbum,
        showGo = showGo,
        today = today,
        zone = zone,
        modifier = block(k),
    )
}

private data class PendingLight(val trackId: String?, val noteId: String?)

/** The foot's two numerals: only for an album played in Yoin (a visit alone has no plays and no first play). */
internal fun diaryFooterStats(
    playsInYoin: Int?,
    firstHeardAt: Long?,
    zone: ZoneId,
    today: LocalDate,
): MemoryFooterStats? {
    val plays = playsInYoin?.takeIf { it > 0 } ?: return null
    val first = firstHeardAt ?: return null
    return MemoryDates.footer(plays, MemoryDates.localDate(first, zone), today)
}

/**
 * The run-out groove belongs to the numerals it sits over: without them (an album only visited) it would stand
 * alone over Go to album, so the foot follows the end gap directly.
 */
internal fun diaryShowsRunOut(history: MemoryFooterStats?): Boolean = history != null

/**
 * Yoin's paragraph (narration + question), held while it is being read: a review saved on the blank page
 * drops it from the refreshed card, but the open page keeps it until [released] (the diary closed, or the
 * spread's page left), so the new entry stays where it was written.
 */
@Composable
internal fun rememberHeldParagraph(memory: MemoryEntry, released: () -> Boolean): YoinParagraph? {
    val live = YoinParagraph.of(memory)
    var held by remember(memory.stableId) { mutableStateOf(live) }
    val currentReleased by rememberUpdatedState(released)
    LaunchedEffect(memory.stableId, live) {
        if (live != null) {
            held = live
        } else {
            snapshotFlow { currentReleased() }.first { it }
            held = null
        }
    }
    return held
}

@Immutable
internal data class YoinParagraph(val narration: String?, val question: String?, val language: MemoryProseLanguage) {
    companion object {
        fun of(memory: MemoryEntry): YoinParagraph? {
            val n = memory.yoinNarration?.takeIf(String::isNotBlank)
            val q = memory.yoinQuestion?.takeIf(String::isNotBlank)
            if (n == null && q == null) return null
            return YoinParagraph(n, q, memory.proseLanguage)
        }
    }
}

@Composable
private fun DiaryHead(
    memory: MemoryEntry,
    tones: MemoryPaletteTones,
    yoinTitle: Boolean,
    paragraph: YoinParagraph?,
    paragraphBlock: Int,
    morph: MemoryPageMorph,
    emblem: @Composable (Modifier) -> Unit,
    titleEditing: MemoryTitleEditing?,
    titleEnabled: Boolean,
) {
    val emblemModifier = Modifier
        .size(DiaryEmblemSize)
        .onPlaced(morph::onDem)
        .graphicsLayer { with(morph) { diaryEmblem() } }
        .seamDissolve()
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .onPlaced(morph::onHead)
            .padding(bottom = if (paragraph != null) MemoryDiaryTokens.HeadGapWithAsk else MemoryDiaryTokens.HeadGap),
    ) {
        if (yoinTitle) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                val style = diaryTitleStyle(memory.memoryTitleKind)
                MemoryTitleSlot(
                    memory = memory,
                    surface = MemoryTitleSurface.Diary,
                    editing = titleEditing,
                    tones = tones,
                    style = { kind -> diaryTitleStyle(kind) },
                    enabled = titleEnabled,
                    centred = false,
                    modifier = Modifier
                        .weight(1f, fill = false)
                        .padding(end = MemoryDiaryTokens.EmblemGap)
                        .onPlaced { morph.onDiaryTitle(it) }
                        .graphicsLayer { with(morph) { diaryTitle() } }
                        .seamFade(style.fontSize),
                ) { text, kind, tap ->
                    Text(
                        text = text,
                        style = diaryTitleStyle(kind),
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = tap.semantics { heading() },
                    )
                }
                emblem(emblemModifier)
            }
            // the title's edit row, under the whole title row; the diary's scroll keeps it above the keyboard
            MemoryTitleEditRow(
                memory = memory,
                surface = MemoryTitleSurface.Diary,
                editing = titleEditing,
                tones = tones,
                centred = false,
                keepAboveIme = true,
            )
            if (paragraph != null) {
                YoinParagraphText(
                    paragraph = paragraph,
                    modifier = Modifier
                        .padding(top = MemoryDiaryTokens.ParagraphTop)
                        .diaryBlock(morph, paragraphBlock),
                )
            }
        } else {
            // fallback A: the album name already flew to the bar; Yoin's paragraph heads the row, the 48 beside it
            LaunchedEffect(Unit) { morph.onDiaryTitle(null) }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Top,
            ) {
                if (paragraph != null) {
                    YoinParagraphText(
                        paragraph = paragraph,
                        modifier = Modifier
                            .weight(1f)
                            .padding(end = MemoryDiaryTokens.EmblemGap)
                            .diaryBlock(morph, paragraphBlock),
                    )
                } else {
                    Spacer(Modifier.weight(1f))
                }
                emblem(emblemModifier)
            }
        }
    }
}

/**
 * Yoin's paragraph: how you listened (GSF ROND 60, on-surface-variant), the question in the same breath
 * (on-surface 500). [size] defaults to the tier's; [centred] sets it as the spread's citation (balanced).
 */
@Composable
internal fun YoinParagraphText(
    paragraph: YoinParagraph,
    modifier: Modifier = Modifier,
    size: TextUnit = LocalMemoriesType.current.paragraph,
    centred: Boolean = false,
) {
    val base = diaryText(
        family = ShowcaseType.rounded(400),
        weight = FontWeight.Normal,
        size = size,
        lineHeight = 1.6f,
        heading = centred,
        paragraph = !centred,
    )
        .let { if (centred) it.copy(textAlign = TextAlign.Center) else it }
    Text(
        text = yoinParagraphString(paragraph, yoinQuestionSpan(MaterialTheme.colorScheme.onSurface)),
        style = base,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier.seamFade(base.fontSize),
    )
}

/**
 * Places its one child after a top margin that puts the child's centre on the viewport's optical line
 * (prototype `.ts-opt`); with [enabled] false it is a plain wrapper.
 */
@Composable
private fun OpticalSlot(enabled: Boolean, viewportPx: Int, topInsetPx: Int, content: @Composable () -> Unit) {
    if (!enabled) {
        content()
        return
    }
    var top by remember { mutableStateOf(0) }
    Layout(
        content = content,
        modifier = Modifier.onPlaced { c ->
            // the slot's own top in the column, from the scroll content's origin
            top = c.parentLayoutCoordinates
                ?.let { parent -> parent.localPositionOf(c, Offset.Zero).y.roundToInt() }
                ?: 0
        },
    ) { measurables, constraints ->
        val placeable = measurables.first().measure(constraints.copy(minHeight = 0))
        val centre = topInsetPx + top + placeable.height / 2
        val margin = max(0, (viewportPx * MemoryDiaryTokens.OpticalLine).roundToInt() - centre)
        layout(placeable.width, placeable.height + margin) { placeable.place(0, margin) }
    }
}

@Composable
private fun DiarySeparator(tones: MemoryPaletteTones, modifier: Modifier = Modifier) {
    val type = LocalMemoriesType.current
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(top = type.separatorTop, bottom = type.separatorBottom)
            .clearAndSetSemantics { },
        horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterHorizontally),
    ) {
        repeat(3) {
            Box(
                Modifier
                    .size(4.dp)
                    .background(tones.ink.copy(alpha = MemoryDiaryTokens.RunOutAlpha), CircleShape),
            )
        }
    }
}

@Composable
private fun DiaryLiner(
    memory: MemoryEntry,
    tones: MemoryPaletteTones,
    interactive: Boolean,
    settled: Boolean,
    host: MemoriesDiaryHost,
    onNote: (MemoryTrack, MemoryWriting) -> Unit,
    onTrack: (MemoryTrack) -> Unit,
    modifier: Modifier = Modifier,
) {
    // on a wide column the score stops at 520, not across the page (prototype .ts-lg .ts-liner)
    Column(modifier = modifier.widthIn(max = LocalMemoriesType.current.linerMaxWidth).fillMaxWidth()) {
        memory.diaryAlbumNotes.forEach { note -> AlbumNoteRow(note) }
        var previousNoted = false
        memory.diaryTracks.forEach { group ->
            if (previousNoted) Spacer(Modifier.height(MemoryDiaryTokens.NotedGroupGap))
            TrackGroup(
                group = group,
                tones = tones,
                interactive = interactive,
                settled = settled,
                host = host,
                onNote = onNote,
                onTrack = onTrack,
            )
            previousNoted = group.notes.isNotEmpty()
        }
    }
}

/** The album's own note leads, not a button: a hollow 6dp bead in the number column. */
@Composable
private fun AlbumNoteRow(note: MemoryWriting) {
    val type = LocalMemoriesType.current
    val lineHeight = type.noteLine
    val description = stringResource(R.string.mem_cd_album_note, note.text)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = MemoryDiaryTokens.RowMin)
            .semantics(mergeDescendants = true) { contentDescription = description }
            .padding(top = 8.dp, bottom = 10.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Box(Modifier.width(type.linerColumn)) {
            Box(
                Modifier
                    .padding(top = (lineHeight - 6.dp) / 2)
                    .size(6.dp)
                    .graphicsLayer { alpha = 0.6f }
                    .border(1.5.dp, MaterialTheme.colorScheme.onSurfaceVariant, CircleShape),
            )
        }
        Text(
            text = note.text,
            style = noteTextStyle(type),
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f).seamFade(type.note),
        )
    }
}

@Composable
private fun TrackGroup(
    group: MemoryDiaryTrack,
    tones: MemoryPaletteTones,
    interactive: Boolean,
    settled: Boolean,
    host: MemoriesDiaryHost,
    onNote: (MemoryTrack, MemoryWriting) -> Unit,
    onTrack: (MemoryTrack) -> Unit,
) {
    val type = LocalMemoriesType.current
    val track = group.track
    val playing = track.trackId != null && host.playingTrackId == track.trackId
    val rated = track.rating != null
    val playLabel = stringResource(R.string.mem_cd_play_track, track.title)
    val playingState = stringResource(R.string.mem_cd_playing_track)
    val scoreText = track.rating?.let(MemoryScores::text)
    val number = track.number ?: 0
    val score = scoreText.orEmpty()
    val numberedRated = stringResource(R.string.mem_cd_track_numbered_rated, number, track.title, score)
    val numbered = stringResource(R.string.mem_cd_track_numbered, number, track.title)
    val ratedLine = stringResource(R.string.mem_cd_track_rated, track.title, score)
    // one TalkBack stop per row, spoken as a reading: "Track 3, Thin Ice, rated 8.5"
    val rowDescription = when {
        track.number != null && scoreText != null -> numberedRated
        track.number != null -> numbered
        scoreText != null -> ratedLine
        else -> track.title
    }
    val numberColor by animateColorAsState(
        targetValue = if (playing) tones.highlight else MaterialTheme.colorScheme.onSurfaceVariant,
        animationSpec = YoinMotion.defaultEffectsSpec(),
        label = "diaryTrackNumber",
    )
    Column(Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .bleed(MemoryDiaryTokens.RowBleed)
                .heightIn(min = MemoryDiaryTokens.RowMin)
                .clip(YoinContainerShapes.ListRow)
                .clearAndSetSemantics {
                    contentDescription = rowDescription
                    if (playing) stateDescription = playingState
                    if (interactive) {
                        role = Role.Button
                        onClick(label = playLabel) {
                            onTrack(track)
                            true
                        }
                    }
                }
                .then(if (interactive) Modifier.clickable(role = Role.Button) { onTrack(track) } else Modifier)
                .padding(horizontal = MemoryDiaryTokens.RowBleed),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = (track.number ?: 0).toString(),
                style = diaryUiText(type.trackNumber, FontWeight.Medium, 1f).tabular(),
                color = numberColor,
                modifier = Modifier.width(type.linerColumn).seamFade(type.trackNumber),
            )
            MarqueeText(
                text = track.title,
                style = diaryUiText(16.sp, if (rated) FontWeight.SemiBold else FontWeight.Medium, 1.3f),
                color = if (rated) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                running = settled,
                modifier = Modifier.weight(1f).seamFade(16.sp),
            )
            if (track.rating != null) {
                Text(
                    text = MemoryScores.text(track.rating),
                    style = diaryUiText(15.sp, FontWeight.SemiBold, 1f).tabular(),
                    color = tones.ink,
                    modifier = Modifier.padding(start = 16.dp).seamFade(15.sp),
                )
            }
        }
        group.notes.forEach { note ->
            val from = note.positionMs?.let(::formatNotePosition).orEmpty()
            val fromLabel = stringResource(R.string.mem_cd_play_from, track.title, from)
            NoteRow(
                note = note,
                lit = note.noteId != null && host.litNoteId == note.noteId,
                tones = tones,
                interactive = interactive,
                onPlay = { onNote(track, note) },
                label = if (note.positionMs != null) fromLabel else playLabel,
            )
        }
    }
}

/** A note, lyric-plain: the stamp (empty without an anchor) and the words, sharing one baseline. */
@Composable
private fun NoteRow(
    note: MemoryWriting,
    lit: Boolean,
    tones: MemoryPaletteTones,
    interactive: Boolean,
    onPlay: () -> Unit,
    label: String,
) {
    val stamp by animateColorAsState(
        targetValue = if (lit) tones.highlight else MaterialTheme.colorScheme.onSurfaceVariant,
        animationSpec = YoinMotion.defaultEffectsSpec(),
        label = "diaryNoteStamp",
    )
    val words by animateColorAsState(
        targetValue = if (lit) tones.highlight else MaterialTheme.colorScheme.onSurface,
        animationSpec = YoinMotion.defaultEffectsSpec(),
        label = "diaryNoteWords",
    )
    val type = LocalMemoriesType.current
    val at = note.positionMs?.let(::formatNotePosition)
    val description = if (at != null) {
        stringResource(R.string.mem_cd_note_at, at, note.text)
    } else {
        stringResource(R.string.mem_cd_note, note.text)
    }
    val playingState = stringResource(R.string.mem_cd_playing_note)
    Row(
        modifier = Modifier
            .bleed(MemoryDiaryTokens.RowBleed)
            .heightIn(min = MemoryDiaryTokens.RowMin)
            .clip(YoinContainerShapes.ListRow)
            // "Note at 2:05: …", lit while the playhead is inside it
            .clearAndSetSemantics {
                contentDescription = description
                if (lit) stateDescription = playingState
                if (interactive) {
                    role = Role.Button
                    onClick(label = label) {
                        onPlay()
                        true
                    }
                }
            }
            .then(if (interactive) Modifier.clickable(role = Role.Button, onClick = onPlay) else Modifier)
            .padding(start = MemoryDiaryTokens.RowBleed, end = MemoryDiaryTokens.RowBleed, top = 8.dp, bottom = 10.dp),
    ) {
        Text(
            text = at.orEmpty(),
            style = diaryUiText(type.stamp, FontWeight.Medium, type.noteLine.value / type.stamp.value)
                .tabular()
                .copy(letterSpacing = 0.1.sp),
            color = stamp,
            modifier = Modifier.width(type.linerColumn).alignByBaseline().seamFade(type.stamp),
        )
        Text(
            text = note.text,
            style = noteTextStyle(type),
            color = words,
            modifier = Modifier.weight(1f).alignByBaseline().seamFade(type.note),
        )
    }
}

/** A note's words: the user's face, the tier's size, on the shared note line box (stamp and words meet). */
@Composable
private fun noteTextStyle(type: MemoriesTypeScale): TextStyle =
    diaryUserText(type.note, type.noteLine.value / type.note.value)

/** The end mark: the record's run-out groove, three hairline rings, only at the very end, over the numerals. */
@Composable
private fun DiaryRunOut(tones: MemoryPaletteTones, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .padding(bottom = MemoryDiaryTokens.RunOutBottom)
            .clearAndSetSemantics { },
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.size(MemoryDiaryTokens.RunOut).seamDissolve()) {
            val k = size.minDimension / 28f
            val stroke = Stroke(width = 1.dp.toPx())
            val color = tones.ink.copy(alpha = MemoryDiaryTokens.RunOutAlpha)
            listOf(13.5f, 9.5f, 5.5f).forEach { r -> drawCircle(color, radius = r * k, style = stroke) }
        }
    }
}

/** Two numerals (only with play history), Go to album, and the quiet NeoDB line (only when configured). */
@Composable
private fun DiaryFoot(
    memory: MemoryEntry,
    tones: MemoryPaletteTones,
    history: MemoryFooterStats?,
    interactive: Boolean,
    host: MemoriesDiaryHost,
    onOpenAlbum: () -> Unit,
    showGo: Boolean,
    today: LocalDate,
    zone: ZoneId,
    modifier: Modifier = Modifier,
) {
    val goAlbum = stringResource(R.string.mem_diary_go_album)
    Column(modifier = modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        if (history != null) {
            val since = formatMemoryChromeDate(
                MemoryDates.localDate(checkNotNull(memory.firstHeardAt), zone),
                today,
                LocalConfiguration.current.locales[0],
                zone,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(MemoryDiaryTokens.StatGap, Alignment.CenterHorizontally)) {
                Stat(
                    history.plays.toString(),
                    pluralStringResource(R.plurals.mem_footer_plays, history.plays),
                )
                Stat(
                    history.days.toString(),
                    pluralStringResource(R.plurals.mem_footer_days_since, history.days, since),
                )
            }
        }
        if (memory.entityType == MemoryEntityType.ALBUM) {
            if (showGo) {
                Box(
                    modifier = Modifier
                        .padding(top = if (history != null) MemoryDiaryTokens.GoTop else 0.dp)
                        .height(48.dp)
                        .clip(RoundedCornerShape(percent = 50))
                        .background(tones.button)
                        .then(
                            if (interactive) {
                                Modifier.clickable(role = Role.Button, onClick = onOpenAlbum)
                            } else {
                                Modifier
                            },
                        )
                        .padding(horizontal = 24.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = goAlbum,
                        style = diaryUiText(15.sp, FontWeight.SemiBold, 1f),
                        color = tones.onButton,
                        maxLines = 1,
                        softWrap = false,
                    )
                }
            }
            if (host.neoDbConfigured) {
                DiaryNeoDbLine(
                    memory = memory,
                    host = host,
                    interactive = interactive,
                    modifier = Modifier.padding(top = if (showGo || history == null) 8.dp else MemoryDiaryTokens.GoTop),
                )
            }
        }
    }
}

/**
 * NeoDB's state for the album, one quiet line (owner, 2026-10-06: it used to say "Push to NeoDB" after a push
 * had landed). Sync is automatic, so it only reports — "Synced to NeoDB" — and becomes the action when a change
 * waits or a push failed. Its 48dp slot is kept while configured, so the diary's end never jumps as it reads.
 */
@Composable
private fun DiaryNeoDbLine(
    memory: MemoryEntry,
    host: MemoriesDiaryHost,
    interactive: Boolean,
    modifier: Modifier = Modifier,
) {
    val state by remember(memory.stableId, host) { host.neoDbSync(memory) }
        .collectAsState(initial = AlbumNeoDbSync.Unknown)
    val action = state == AlbumNeoDbSync.Pending || state == AlbumNeoDbSync.Failed
    Box(
        modifier = modifier
            .height(48.dp)
            .clip(RoundedCornerShape(percent = 50))
            .then(
                if (interactive && action) {
                    Modifier.clickable(role = Role.Button) { host.pushNeoDb(memory) }
                } else {
                    Modifier
                },
            )
            .padding(horizontal = 16.dp),
        contentAlignment = Alignment.Center,
    ) {
        AnimatedContent(
            targetState = state,
            transitionSpec = {
                YoinMotion.fadeIn(role = YoinMotionRole.Standard) togetherWith
                    YoinMotion.fadeOut(role = YoinMotionRole.Standard)
            },
            label = "diaryNeoDb",
        ) { shown ->
            Text(
                text = diaryNeoDbText(shown),
                style = diaryUiText(13.sp, FontWeight.Medium, 1.3f),
                color = if (shown == AlbumNeoDbSync.Failed || shown == AlbumNeoDbSync.Pending) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
        }
    }
}

/** The diary's NeoDB words; blank where there is nothing to say (signed out, nothing written yet). */
@Composable
private fun diaryNeoDbText(state: AlbumNeoDbSync): String = when (state) {
    AlbumNeoDbSync.Unknown, AlbumNeoDbSync.SignedOut, AlbumNeoDbSync.Idle -> ""
    AlbumNeoDbSync.Pending -> stringResource(R.string.mem_neodb_sync)
    AlbumNeoDbSync.Syncing -> stringResource(R.string.mem_neodb_syncing)
    AlbumNeoDbSync.Synced -> stringResource(R.string.mem_neodb_synced)
    AlbumNeoDbSync.Failed -> stringResource(R.string.mem_neodb_failed)
}

@Composable
private fun Stat(numeral: String, caption: String) {
    val size = LocalMemoriesType.current.stat
    val description = stringResource(R.string.mem_cd_stat, numeral, caption)
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.semantics(mergeDescendants = true) { contentDescription = description },
    ) {
        Text(
            text = numeral,
            style = diaryText(ShowcaseType.rounded(500, rond = 40f), FontWeight.Medium, size, 1f).tabular(),
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.seamFade(size),
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = caption,
            style = diaryUiText(12.sp, FontWeight.Medium, 1.3f).copy(letterSpacing = 0.1.sp),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            softWrap = false,
            modifier = Modifier.seamFade(12.sp),
        )
    }
}

/**
 * The diary title: a written title — the AI's or the user's own — in the serif (22, Medium 24), the motif in
 * GSF ROND 60 (21, Medium 23).
 */
@Composable
internal fun diaryTitleStyle(kind: MemoryTitleKind): TextStyle {
    val type = LocalMemoriesType.current
    return when (kind) {
        MemoryTitleKind.AI, MemoryTitleKind.USER ->
            diaryText(YoinSerifTitle, FontWeight.SemiBold, type.diaryTitleAi, 1.4f, heading = true)
        else -> diaryText(ShowcaseType.rounded(600), FontWeight.SemiBold, type.diaryTitleMotif, 1.3f, heading = true)
    }
}

/** The diary title's size for a kind (the morph scales the card title to it). */
internal fun diaryTitleSize(kind: MemoryTitleKind, type: MemoriesTypeScale): TextUnit =
    if (kind == MemoryTitleKind.AI || kind == MemoryTitleKind.USER) type.diaryTitleAi else type.diaryTitleMotif

/** A start-aligned diary text style with a CSS line box (half-leading, no font padding). */
@Composable
internal fun diaryText(
    family: FontFamily,
    weight: FontWeight,
    size: TextUnit,
    lineHeight: Float,
    heading: Boolean = false,
    paragraph: Boolean = false,
): TextStyle = MaterialTheme.typography.bodyLarge.copy(
    fontFamily = family,
    fontWeight = weight,
    fontSize = size,
    lineHeight = size * lineHeight,
    letterSpacing = 0.sp,
    lineHeightStyle = LineHeightStyle(LineHeightStyle.Alignment.Center, LineHeightStyle.Trim.None),
    platformStyle = PlatformTextStyle(includeFontPadding = false),
    lineBreak = when {
        heading -> LineBreak.Heading
        paragraph -> LineBreak.Paragraph
        else -> LineBreak.Simple
    },
)

private fun TextStyle.tabular(): TextStyle = copy(fontFeatureSettings = "tnum")

/** A row whose press ground reaches [amount] past the column on both sides (the content stays aligned). */
private fun Modifier.bleed(amount: Dp): Modifier = layout { measurable, constraints ->
    if (!constraints.hasBoundedWidth) {
        val placeable = measurable.measure(constraints)
        return@layout layout(placeable.width, placeable.height) { placeable.place(0, 0) }
    }
    val wide = constraints.maxWidth + (amount * 2).roundToPx()
    val placeable = measurable.measure(constraints.copy(minWidth = wide, maxWidth = wide))
    layout(constraints.maxWidth, placeable.height) { placeable.place(-amount.roundToPx(), 0) }
}

/** A diary block's rise and fade ([MemoryPageMorph.diaryBlock]). */
private fun Modifier.diaryBlock(morph: MemoryPageMorph, k: Int): Modifier =
    graphicsLayer { with(morph) { diaryBlock(k) } }

private const val NoteTickScale = 0.5f
private const val TrackTickScale = 0.4f
private const val LightTimeoutMs = 4_500L
