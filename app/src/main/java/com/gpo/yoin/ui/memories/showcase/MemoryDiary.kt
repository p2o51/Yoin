package com.gpo.yoin.ui.memories.showcase

import androidx.compose.animation.animateColorAsState
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
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
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineBreak
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.gpo.yoin.ui.component.MarqueeText
import com.gpo.yoin.ui.component.SeamTop
import com.gpo.yoin.ui.component.formatNotePosition
import com.gpo.yoin.ui.component.seamDissolve
import com.gpo.yoin.ui.component.seamDissolveViewport
import com.gpo.yoin.ui.component.seamFade
import com.gpo.yoin.ui.component.verticalEdgeFadeOnScroll
import com.gpo.yoin.ui.experience.YoinHaptics
import com.gpo.yoin.ui.experience.rememberYoinHaptics
import com.gpo.yoin.ui.memories.MemoryDiaryTrack
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
import com.gpo.yoin.ui.theme.YoinSerifTitle
import java.time.LocalDate
import java.time.ZoneId
import kotlin.math.max
import kotlin.math.roundToInt
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.first
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

    /** A track row: play the track from its start. */
    fun playTrack(memory: MemoryEntry, track: MemoryTrack) = Unit

    /** A note row: play its track from the note's anchor (from the start when it has none). */
    fun playNote(memory: MemoryEntry, track: MemoryTrack, note: MemoryWriting) = Unit

    val neoDbConfigured: Boolean get() = false

    fun neoDbSyncing(memory: MemoryEntry): Boolean = false

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
    val LinerColumn = 40.dp
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
) {
    val draft = host.reviewDraft(memory)
    val yoinTitle = memory.memoryTitleKind != MemoryTitleKind.ALBUM && !memory.memoryTitle.isNullOrBlank()
    val paragraph = rememberHeldParagraph(memory, deck)
    val liner = memory.diaryAlbumNotes.isNotEmpty() || memory.diaryTracks.isNotEmpty()
    val review = memory.review
    val history = memory.playsInYoin?.takeIf { it > 0 }?.let { plays ->
        memory.firstHeardAt?.let { first -> MemoryDates.footer(plays, MemoryDates.localDate(first, zone), today) }
    }
    // a lone short review (and nothing after it) rests on the optical line
    val optical = review != null && review.text.length <= ShortReviewMax && !liner

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

    BoxWithConstraints(
        modifier = modifier
            .fillMaxSize()
            .graphicsLayer { with(morph) { diaryViewport() } },
    ) {
        val viewportPx = constraints.maxHeight
        var k = 0
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
                .onPlaced(morph::onDiaryInner)
                .padding(
                    start = MemoryDiaryTokens.SideInset,
                    end = MemoryDiaryTokens.SideInset,
                    top = MemoryDiaryTokens.TopInset,
                    bottom = MemoryDiaryTokens.BottomInset + navBottom,
                ),
        ) {
            val paragraphBlock = if (paragraph != null) k++ else -1
            DiaryHead(
                memory = memory,
                yoinTitle = yoinTitle,
                paragraph = paragraph,
                paragraphBlock = paragraphBlock,
                morph = morph,
                emblem = emblem,
            )
            val entryBlock = k++
            OpticalSlot(
                enabled = optical,
                viewportPx = viewportPx,
                topInsetPx = with(LocalDensity.current) { MemoryDiaryTokens.TopInset.roundToPx() },
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
                    modifier = Modifier.diaryBlock(morph, entryBlock),
                )
            }
            if (liner) {
                if (review != null) {
                    DiarySeparator(tones, Modifier.diaryBlock(morph, k++))
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
                    modifier = Modifier.diaryBlock(morph, k++),
                )
            }
            Spacer(Modifier.height(MemoryDiaryTokens.EndGap))
            DiaryRunOut(tones, Modifier.diaryBlock(morph, k++))
            DiaryFoot(
                memory = memory,
                tones = tones,
                history = history,
                interactive = interactive,
                host = host,
                onOpenAlbum = onOpenAlbum,
                modifier = Modifier.diaryBlock(morph, k++),
            )
        }
    }
}

private data class PendingLight(val trackId: String?, val noteId: String?)

/**
 * Yoin's paragraph (narration + question), held while the diary is open: a review saved on the blank page
 * drops it from the refreshed card, but the open page keeps it until the diary closes, so the new entry
 * stays where it was written.
 */
@Composable
private fun rememberHeldParagraph(memory: MemoryEntry, deck: MemoriesDiaryDeck): YoinParagraph? {
    val live = YoinParagraph.of(memory)
    var held by remember(memory.stableId) { mutableStateOf(live) }
    LaunchedEffect(memory.stableId, live) {
        if (live != null) {
            held = live
        } else {
            // wait until the diary is closed to let it go
            snapshotFlow { deck.p <= 0f }.first { it }
            held = null
        }
    }
    return held
}

@Immutable
private data class YoinParagraph(val narration: String?, val question: String?, val language: MemoryProseLanguage) {
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
    yoinTitle: Boolean,
    paragraph: YoinParagraph?,
    paragraphBlock: Int,
    morph: MemoryPageMorph,
    emblem: @Composable (Modifier) -> Unit,
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
                Text(
                    text = memory.memoryTitle.orEmpty(),
                    style = style,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier
                        .weight(1f, fill = false)
                        .padding(end = MemoryDiaryTokens.EmblemGap)
                        .onPlaced { morph.onDiaryTitle(it) }
                        .graphicsLayer { with(morph) { diaryTitle() } }
                        .seamFade(style.fontSize),
                )
                emblem(emblemModifier)
            }
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

@Composable
private fun YoinParagraphText(paragraph: YoinParagraph, modifier: Modifier = Modifier) {
    val base = diaryText(ShowcaseType.rounded(400), FontWeight.Normal, 16.sp, 1.6f, paragraph = true)
    Text(
        text = buildAnnotatedString {
            paragraph.narration?.let { append(it) }
            val spaced = paragraph.language != MemoryProseLanguage.ZH
            if (paragraph.narration != null && paragraph.question != null && spaced) {
                append(' ')
            }
            paragraph.question?.let { q ->
                withStyle(
                    SpanStyle(
                        fontFamily = ShowcaseType.rounded(500),
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.onSurface,
                    ),
                ) { append(q) }
            }
        },
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
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(top = 30.dp, bottom = 24.dp)
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
    Column(modifier = modifier.fillMaxWidth()) {
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
    val lineHeight = NoteLineHeight
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = MemoryDiaryTokens.RowMin)
            .padding(top = 8.dp, bottom = 10.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Box(Modifier.width(MemoryDiaryTokens.LinerColumn)) {
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
            style = diaryUserText(15.5.sp, NoteLineHeightRatio(15.5f)),
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f).seamFade(15.5.sp),
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
    val track = group.track
    val playing = track.trackId != null && host.playingTrackId == track.trackId
    val rated = track.rating != null
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
                .then(
                    if (interactive) {
                        Modifier.clickable(
                            role = Role.Button,
                            onClickLabel = "Play ${track.title}" +
                                (track.rating?.let { ", rated ${MemoryScores.text(it)}" } ?: ""),
                        ) { onTrack(track) }
                    } else {
                        Modifier
                    },
                )
                .padding(horizontal = MemoryDiaryTokens.RowBleed),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = (track.number ?: 0).toString(),
                style = diaryUiText(13.sp, FontWeight.Medium, 1f).tabular(),
                color = numberColor,
                modifier = Modifier.width(MemoryDiaryTokens.LinerColumn).seamFade(13.sp),
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
            NoteRow(
                note = note,
                lit = note.noteId != null && host.litNoteId == note.noteId,
                tones = tones,
                interactive = interactive,
                onClick = { onNote(track, note) },
                label = note.positionMs
                    ?.let { "Play ${track.title} from ${formatNotePosition(it)}" }
                    ?: "Play ${track.title}",
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
    onClick: () -> Unit,
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
    Row(
        modifier = Modifier
            .bleed(MemoryDiaryTokens.RowBleed)
            .heightIn(min = MemoryDiaryTokens.RowMin)
            .clip(YoinContainerShapes.ListRow)
            .then(
                if (interactive) {
                    Modifier.clickable(role = Role.Button, onClickLabel = label, onClick = onClick)
                } else {
                    Modifier
                },
            )
            .padding(start = MemoryDiaryTokens.RowBleed, end = MemoryDiaryTokens.RowBleed, top = 8.dp, bottom = 10.dp),
    ) {
        Text(
            text = note.positionMs?.let(::formatNotePosition).orEmpty(),
            style = diaryUiText(12.5.sp, FontWeight.Medium, NoteLineHeightRatio(12.5f))
                .tabular()
                .copy(letterSpacing = 0.1.sp),
            color = stamp,
            modifier = Modifier.width(MemoryDiaryTokens.LinerColumn).alignByBaseline().seamFade(12.5.sp),
        )
        Text(
            text = note.text,
            style = diaryUserText(15.5.sp, NoteLineHeightRatio(15.5f)),
            color = words,
            modifier = Modifier.weight(1f).alignByBaseline().seamFade(15.5.sp),
        )
    }
}

/** The end mark: the record's run-out groove, three hairline rings, only at the very end. */
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
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        if (history != null) {
            Row(horizontalArrangement = Arrangement.spacedBy(MemoryDiaryTokens.StatGap, Alignment.CenterHorizontally)) {
                Stat(history.plays.toString(), history.playsCaption)
                Stat(history.days.toString(), history.daysCaption)
            }
        }
        if (memory.entityType == MemoryEntityType.ALBUM) {
            Box(
                modifier = Modifier
                    .padding(top = if (history != null) MemoryDiaryTokens.GoTop else 0.dp)
                    .height(48.dp)
                    .clip(RoundedCornerShape(percent = 50))
                    .background(tones.button)
                    .then(if (interactive) Modifier.clickable(role = Role.Button, onClick = onOpenAlbum) else Modifier)
                    .padding(horizontal = 24.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = "Go to album",
                    style = diaryUiText(15.sp, FontWeight.SemiBold, 1f),
                    color = tones.onButton,
                    maxLines = 1,
                    softWrap = false,
                )
            }
            if (host.neoDbConfigured) {
                val syncing = host.neoDbSyncing(memory)
                Box(
                    modifier = Modifier
                        .padding(top = 8.dp)
                        .height(48.dp)
                        .clip(RoundedCornerShape(percent = 50))
                        .then(
                            if (interactive && !syncing) {
                                Modifier.clickable(role = Role.Button) { host.pushNeoDb(memory) }
                            } else {
                                Modifier
                            },
                        )
                        .padding(horizontal = 16.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = if (syncing) "Pushing to NeoDB…" else "Push to NeoDB",
                        style = diaryUiText(13.sp, FontWeight.Medium, 1.3f),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun Stat(numeral: String, caption: String) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.semantics(mergeDescendants = true) { contentDescription = "$numeral $caption" },
    ) {
        Text(
            text = numeral,
            style = diaryText(ShowcaseType.rounded(500, rond = 40f), FontWeight.Medium, 34.sp, 1f).tabular(),
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.seamFade(34.sp),
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

/** The diary title: the AI title in the serif (22), the motif in GSF ROND 60 (21). */
@Composable
internal fun diaryTitleStyle(kind: MemoryTitleKind): TextStyle = when (kind) {
    MemoryTitleKind.AI -> diaryText(YoinSerifTitle, FontWeight.SemiBold, DiaryTitleSizeAi, 1.4f, heading = true)
    else -> diaryText(ShowcaseType.rounded(600), FontWeight.SemiBold, DiaryTitleSizeMotif, 1.3f, heading = true)
}

internal val DiaryTitleSizeAi: TextUnit = 22.sp
internal val DiaryTitleSizeMotif: TextUnit = 21.sp

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

/** Note rows share one 25.6dp line box (stamp and words), so their first baselines meet. */
private val NoteLineHeight = 25.6.dp

private fun NoteLineHeightRatio(sizeSp: Float): Float = 25.6f / sizeSp

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
