package com.gpo.yoin.ui.home

import androidx.compose.animation.AnimatedContent
import kotlin.math.roundToInt
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.animation.core.snap
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.toShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.layoutId
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.gpo.yoin.data.memory.AlbumMemoryCandidate
import com.gpo.yoin.data.model.MediaId
import com.gpo.yoin.symbols.YoinSymbols
import com.gpo.yoin.ui.component.ExpressiveMediaArtwork
import com.gpo.yoin.ui.component.dashedOutline
import com.gpo.yoin.ui.component.elasticPress
import com.gpo.yoin.ui.component.minimumTouchTarget
import com.gpo.yoin.ui.component.noRippleClickable
import com.gpo.yoin.ui.component.rememberExpressiveBackdropColors
import com.gpo.yoin.ui.component.seamDissolve
import com.gpo.yoin.ui.component.seamFade
import com.gpo.yoin.ui.experience.LocalMotionProfile
import com.gpo.yoin.ui.experience.MotionProfile
import com.gpo.yoin.ui.experience.rememberYoinHaptics
import com.gpo.yoin.ui.memories.MemoryScoreKind
import com.gpo.yoin.ui.theme.ProvideYoinMotionRole
import com.gpo.yoin.ui.theme.YoinArtworkShapes
import com.gpo.yoin.ui.theme.YoinMotion
import com.gpo.yoin.ui.theme.YoinMotionRole
import com.gpo.yoin.ui.theme.YoinShapeTokens
import com.gpo.yoin.ui.theme.YoinTheme
import com.gpo.yoin.ui.theme.withTabularFigures
import java.util.Locale

// ── The header's Memories pill ─────────────────────────────────────────
//
// The bare Memories chevron grows into a pill that says what's in the attic:
// the most recently written memory's cover and score, and how many notes the
// profile has kept. It is page chrome, not a feed section (memory_teaser stays
// retired), and it keeps the chevron — and the chevron's pull hint — at its
// end. The pill's own outline is the score's provenance, the Memories seal's
// grammar told with a line: a tonal wash = your album rating, a solid line =
// the average of your track ratings, a dashed line = nothing scored yet (or
// nothing kept yet — a slot waiting to be filled).

/** Pill height; the 48dp header row (IconButtons) stays the row's height. */
private val PillHeight = 40.dp
private val PillCoverSize = 28.dp

// 6dp keeps the cover's 4dp corners inside the pill's 20dp end cap (with a
// stroke): a square at 4dp would poke through the curve.
private val PillCoverInset = 6.dp
private val PillTextLead = 14.dp
private val PillTrail = 8.dp
private val PillChevronSize = 20.dp

/** The pull hint's nudge (the bare chevron's old 4px, now density-true). */
private val PillChevronNudge = 2.dp

/** The chevron's own footprint — where the pill unrolls from. */
private val PillAnchorWidth = PillTrail + PillChevronSize + 8.dp

/** Space kept between the "Home" title and anything to its right. */
internal val HomeHeaderTitleBreathing = 24.dp

internal const val PillOutlineAlpha = 0.6f

/** Which face the pill shows. */
internal enum class MemoryPillForm {
    /** Data not resolved (or nothing to show): today's chevron, no container. */
    Unresolved,

    /** Nothing kept yet: a dashed "Memories ⌄" pill. */
    Ghost,

    /** Notes but no memory yet: dashed "Memories · 3 notes ⌄". */
    NotesOnly,

    /** The latest memory: cover, score, notes. */
    Populated,
}

internal fun resolveMemoryPillForm(
    pill: HomeMemoryPill?,
    emptyForm: PillEmptyForm,
): MemoryPillForm = when {
    pill == null -> MemoryPillForm.Unresolved
    pill.latest != null -> MemoryPillForm.Populated
    pill.noteCount > 0 -> MemoryPillForm.NotesOnly
    emptyForm == PillEmptyForm.Chevron -> MemoryPillForm.Unresolved
    else -> MemoryPillForm.Ghost
}

/**
 * The most recently WRITTEN memory: the newest rating / review / note on the
 * album. Plays and visits never count (visits inflate lastPlayedAt), they only
 * break ties for memories with no write time. `maxByOrNull` keeps the first of
 * equal keys and candidates arrive strength-ranked, so a tie goes to the
 * stronger memory.
 */
internal fun pickLatestMemory(candidates: List<AlbumMemoryCandidate>): AlbumMemoryCandidate? =
    candidates.maxByOrNull { it.lastWrittenAt ?: it.lastPlayedAt ?: it.firstPlayedAt ?: 0L }

/** The Memories seal's rule: your album rating, else your track average, else none. */
internal fun memoryScoreKindOf(candidate: AlbumMemoryCandidate): MemoryScoreKind = when {
    candidate.albumRating != null -> MemoryScoreKind.ALBUM_RATING
    (candidate.averageSongRating ?: 0f) > 0f -> MemoryScoreKind.AVERAGE_TRACK_RATING
    else -> MemoryScoreKind.NONE
}

/** The seal's own "%.1f" — a 10 reads "10.0" there too. */
private fun formatPillScore(rating: Float?): String? =
    rating?.takeIf { it > 0f }?.let { String.format(Locale.US, "%.1f", it) }

internal fun buildHomeMemoryPill(
    candidates: List<AlbumMemoryCandidate>,
    noteCount: Int,
    scope: String = "",
): HomeMemoryPill {
    val latestCandidate = pickLatestMemory(candidates)
    val latest = latestCandidate?.let { candidate ->
        val kind = memoryScoreKindOf(candidate)
        HomeMemoryPill.Latest(
            sessionId = candidate.sessionId,
            albumId = MediaId(
                candidate.provider,
                MediaId.storedRawId(candidate.provider, candidate.albumId),
            ),
            albumName = candidate.albumName,
            artistName = candidate.artistName?.takeIf { it.isNotBlank() },
            coverArtUrl = candidate.coverArtUrl,
            scoreKind = kind,
            scoreText = when (kind) {
                MemoryScoreKind.ALBUM_RATING -> formatPillScore(candidate.albumRating)
                MemoryScoreKind.AVERAGE_TRACK_RATING -> formatPillScore(candidate.averageSongRating)
                MemoryScoreKind.NONE -> null
            },
        )
    }
    val notes = noteCount.coerceAtLeast(0)
    return HomeMemoryPill(
        latest = latest,
        noteCount = notes,
        scope = scope,
        newsKey = memoryNewsKey(latestCandidate, notes),
    )
}

/**
 * What "something new" is keyed on: the newest write on any memory (the
 * latest memory's write time — a fresh rating, review or note moves it) and
 * the note count, as "time#count". Plays and visits never change it; the
 * bubble compares the two parts as numbers (isMemoryNews), so a deletion that
 * moves them back is not news.
 */
internal fun memoryNewsKey(latest: AlbumMemoryCandidate?, noteCount: Int): String =
    "${latest?.lastWrittenAt ?: 0L}#$noteCount"

internal fun formatNoteCount(count: Int): String = when {
    count > 999 -> "999+ notes"
    count == 1 -> "1 note"
    else -> "$count notes"
}

internal fun formatNoteCountShort(count: Int): String = if (count > 999) "999+" else count.toString()

internal enum class NotesForm { Full, Short, None }

/**
 * The pill's fit rule, pure so it can be pinned by tests. [base] is the pill
 * without any notes; [fullExtra] / [shortExtra] are what each notes form adds
 * (its width plus its gap). The roomiest form that fits wins.
 */
internal fun chooseNotesForm(
    available: Int,
    base: Int,
    fullExtra: Int,
    shortExtra: Int,
): NotesForm = when {
    base + fullExtra <= available -> NotesForm.Full
    base + shortExtra <= available -> NotesForm.Short
    else -> NotesForm.None
}

internal fun memoryPillContentDescription(pill: HomeMemoryPill?, form: MemoryPillForm): String {
    if (pill == null || form == MemoryPillForm.Unresolved) return "Memories"
    val notes = pill.noteCount.takeIf { it > 0 }?.let(::formatNoteCount)
    val latest = pill.latest
    return when (form) {
        MemoryPillForm.Ghost -> "Memories, nothing kept yet"
        MemoryPillForm.NotesOnly -> "Memories, $notes"
        else -> buildString {
            append("Memories. Latest: ")
            append(latest?.albumName.orEmpty())
            latest?.artistName?.let { append(" by ").append(it) }
            append(". ")
            append(
                when (latest?.scoreKind) {
                    MemoryScoreKind.ALBUM_RATING -> "Your album rating ${latest.scoreText}"
                    MemoryScoreKind.AVERAGE_TRACK_RATING -> "Track average ${latest.scoreText}"
                    else -> "Not rated yet"
                },
            )
            append(".")
            notes?.let { append(" ").append(it).append(".") }
        }
    }
}

/**
 * The contents "develop" after the shell has surfaced: nothing until 45% of
 * the unroll, then a smoothstep to full — a faint outline first, then the
 * cover and numbers fill in.
 */
internal fun develop(progress: Float): Float {
    val t = ((progress - 0.45f) / 0.55f).coerceIn(0f, 1f)
    return t * t * (3f - 2f * t)
}

/**
 * The header's Memories entry. [hintProgress] is the pull-to-Memories hint
 * (0 at rest, 1 fully pulled) and [revealProgress] the feed's once-per-process
 * launch reveal; both are read in the draw phase only, so neither a pull frame
 * nor the reveal recomposes the header.
 */
@Composable
internal fun HomeMemoryEntry(
    pill: HomeMemoryPill?,
    hintProgress: () -> Float,
    revealProgress: () -> Float,
    extractBackdropColors: Boolean,
    onOpenMemoryFocus: (sessionId: Long) -> Unit,
    onNavigateToMemories: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val variant = LocalHomeHintVariant.current
    if (variant.entry == MemoryEntryStyle.HeaderChevron) {
        LegacyMemoriesChevron(
            hintProgress = hintProgress,
            onNavigateToMemories = onNavigateToMemories,
            modifier = modifier,
        )
        return
    }
    // Chrome stays calm: the feed below is Expressive, the header Standard.
    ProvideYoinMotionRole(role = YoinMotionRole.Standard) {
        MemoryPill(
            pill = pill,
            form = resolveMemoryPillForm(pill, variant.emptyForm),
            scoreMark = variant.scoreMark,
            hintProgress = hintProgress,
            revealProgress = revealProgress,
            extractBackdropColors = extractBackdropColors,
            onOpenMemoryFocus = onOpenMemoryFocus,
            onNavigateToMemories = onNavigateToMemories,
            modifier = modifier,
        )
    }
}

/** Today's bare chevron, kept verbatim for the Baseline variant. */
@Composable
private fun LegacyMemoriesChevron(
    hintProgress: () -> Float,
    onNavigateToMemories: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val haptics = rememberYoinHaptics()
    IconButton(
        onClick = {
            haptics.performContextClick()
            onNavigateToMemories()
        },
        modifier = modifier
            .seamFade()
            .graphicsLayer {
                val hint = hintProgress()
                translationY = hint * 4f
                alpha = 0.62f + hint * 0.38f
            },
    ) {
        Icon(
            imageVector = YoinSymbols.ChevronDown,
            contentDescription = "Memories",
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun MemoryPill(
    pill: HomeMemoryPill?,
    form: MemoryPillForm,
    scoreMark: PillScoreMark,
    hintProgress: () -> Float,
    revealProgress: () -> Float,
    extractBackdropColors: Boolean,
    onOpenMemoryFocus: (sessionId: Long) -> Unit,
    onNavigateToMemories: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val haptics = rememberYoinHaptics()
    val interactionSource = remember { MutableInteractionSource() }
    val reduced = LocalMotionProfile.current == MotionProfile.AdaptiveReduced
    val colors = MaterialTheme.colorScheme
    val latest = pill?.latest?.takeIf { form == MemoryPillForm.Populated }
    val noteCount = pill?.noteCount?.takeIf { form != MemoryPillForm.Unresolved } ?: 0

    // What each part shows while it leaves: the last value it had, so an
    // outgoing cover / score / count fades out as itself instead of blanking.
    val shownLatest = rememberLastNonNull(latest)
    val shownScore = rememberLastNonNull(latest?.scoreText)
    val shownNotes = rememberLastNonNull(noteCount.takeIf { it > 0 }) ?: 0

    // Palette from the latest memory's cover — the Activities card recipe
    // (direct lerp, never fromSeed), gated with the feed's extraction window.
    val backdrop = rememberExpressiveBackdropColors(
        model = shownLatest?.coverArtUrl,
        fallbackBaseColor = colors.secondary,
        fallbackAccentColor = colors.tertiary,
        enabled = extractBackdropColors && shownLatest != null,
    )
    // The JBI score rule: the palette's base tone sinks on a dark surface, so
    // dark mode takes the brighter accent of the same hue family.
    val ink = if (isSystemInDarkTheme()) backdrop.accentColor else backdrop.baseColor
    val wash = lerp(colors.surfaceContainerLow, backdrop.baseColor, 0.30f)
    val dashedInk = colors.outline.copy(alpha = PillOutlineAlpha)

    // Container targets: the outline ladder (or the Sticker's flat wash).
    val kind = latest?.scoreKind
    val filled = form == MemoryPillForm.Populated &&
        (scoreMark == PillScoreMark.Sticker || kind == MemoryScoreKind.ALBUM_RATING)
    val strokeTarget = when {
        form == MemoryPillForm.Unresolved -> dashedInk.copy(alpha = 0f)
        filled -> ink.copy(alpha = 0f)
        kind == MemoryScoreKind.AVERAGE_TRACK_RATING -> ink.copy(alpha = PillOutlineAlpha)
        else -> dashedInk
    }
    val solidTarget = if (filled || kind == MemoryScoreKind.AVERAGE_TRACK_RATING) 1f else 0f
    val effects = if (reduced) snap() else YoinMotion.effectsSpring<Float>()
    val colorEffects = if (reduced) snap() else YoinMotion.effectsSpring<Color>()
    val spatial = if (reduced) snap() else YoinMotion.spatialSpring<Float>()
    val fillAlpha = animateFloatAsState(if (filled) 1f else 0f, effects, label = "memoryPillFill")
    val strokeColor = animateColorAsState(strokeTarget, colorEffects, label = "memoryPillStroke")
    // Dashes close into a line ("the mould inks in") — an effect, not a move.
    val solidity = animateFloatAsState(solidTarget, effects, label = "memoryPillSolidity")
    val scoreColor by animateColorAsState(
        targetValue = if (filled) colors.onSurface else ink,
        animationSpec = colorEffects,
        label = "memoryPillScoreInk",
    )

    // Each part enters and leaves on its own: its width on a spatial spring
    // (the pill grows and shrinks from the chevron's side), its alpha on an
    // effects spring. Data landing after first paint grows the pill out of the
    // chevron the same way. Read in layout / draw only.
    val hasCover = latest != null
    val hasScore = latest?.scoreText != null
    val hasLabel = form == MemoryPillForm.Ghost || form == MemoryPillForm.NotesOnly
    val hasNotes = noteCount > 0
    val coverWidth = animateFloatAsState(if (hasCover) 1f else 0f, spatial, label = "pillCoverW")
    val scoreWidth = animateFloatAsState(if (hasScore) 1f else 0f, spatial, label = "pillScoreW")
    val labelWidth = animateFloatAsState(if (hasLabel) 1f else 0f, spatial, label = "pillLabelW")
    val notesWidth = animateFloatAsState(if (hasNotes) 1f else 0f, spatial, label = "pillNotesW")
    val coverAlpha = animateFloatAsState(if (hasCover) 1f else 0f, effects, label = "pillCoverA")
    val scoreAlpha = animateFloatAsState(if (hasScore) 1f else 0f, effects, label = "pillScoreA")
    val labelAlpha = animateFloatAsState(if (hasLabel) 1f else 0f, effects, label = "pillLabelA")
    val notesAlpha = animateFloatAsState(if (hasNotes) 1f else 0f, effects, label = "pillNotesA")

    // The launch reveal: once per process the pill unrolls out of the
    // chevron's footprint — the shell surfaces faintly, then the contents
    // develop. Afterwards (and under reduced motion) it is simply there.
    val unroll: () -> Float = { if (reduced) 1f else revealProgress() }
    val sizeSpec = YoinMotion.spatialSpring<IntSize>()

    val description = memoryPillContentDescription(pill, form)
    Box(
        modifier = modifier
            .minimumTouchTarget(48.dp)
            .clearAndSetSemantics {
                role = Role.Button
                contentDescription = description
            }
            .noRippleClickable(interactionSource = interactionSource) {
                haptics.performContextClick()
                val session = latest?.sessionId
                if (session != null) onOpenMemoryFocus(session) else onNavigateToMemories()
            },
        contentAlignment = Alignment.CenterEnd,
    ) {
        Box(
            modifier = Modifier
                .elasticPress(interactionSource)
                // The container and cover break up as one print at the seam.
                .seamDissolve()
                .graphicsLayer {
                    val p = unroll()
                    if (p < 1f) {
                        clip = true
                        shape = EndAnchoredPill(
                            visibleWidth = androidx.compose.ui.util.lerp(
                                PillAnchorWidth.toPx(),
                                size.width,
                                p,
                            ),
                        )
                    } else {
                        clip = false
                        shape = RectangleShape
                    }
                }
                .drawBehind {
                    val a = fillAlpha.value * unroll()
                    if (a > 0f) {
                        drawRoundRect(
                            color = wash,
                            alpha = a,
                            cornerRadius = CornerRadius(size.height / 2f),
                        )
                    }
                }
                .dashedOutline(
                    shape = YoinShapeTokens.Full,
                    color = {
                        val c = strokeColor.value
                        c.copy(alpha = c.alpha * unroll())
                    },
                    width = 1.dp,
                    dash = 4.dp,
                    gap = 3.dp,
                    solidity = { solidity.value },
                ),
        ) {
            val developed: () -> Float = { develop(unroll()) }
            MemoryPillLayout(
                showCover = shownLatest != null,
                showScore = shownScore != null,
                showNotes = shownNotes > 0,
                target = PillTargets(
                    cover = hasCover,
                    score = hasScore,
                    label = hasLabel,
                    notes = hasNotes,
                ),
                widthFraction = PillFractions(
                    cover = { coverWidth.value },
                    score = { scoreWidth.value },
                    label = { labelWidth.value },
                    notes = { notesWidth.value },
                ),
                stickerGap = scoreMark == PillScoreMark.Sticker,
                cover = {
                    PillCover(
                        latest = shownLatest,
                        sticker = scoreMark == PillScoreMark.Sticker,
                        ink = ink,
                        stickerBacking = wash,
                        alpha = { coverAlpha.value * developed() },
                    )
                },
                score = {
                    RollingText(
                        text = shownScore.orEmpty(),
                        reduced = reduced,
                        sizeSpec = sizeSpec,
                        modifier = Modifier
                            .graphicsLayer { alpha = scoreAlpha.value * developed() }
                            .seamFade(),
                    ) { value ->
                        Text(
                            text = value,
                            style = MaterialTheme.typography.titleSmall
                                .copy(fontWeight = FontWeight.Bold)
                                .withTabularFigures(),
                            color = scoreColor,
                            maxLines = 1,
                        )
                    }
                },
                notesFull = {
                    PillNotes(
                        text = formatNoteCount(shownNotes),
                        reduced = reduced,
                        sizeSpec = sizeSpec,
                        modifier = Modifier
                            .graphicsLayer { alpha = notesAlpha.value * developed() }
                            .seamFade(),
                    )
                },
                notesShort = {
                    PillNotes(
                        text = formatNoteCountShort(shownNotes),
                        reduced = reduced,
                        sizeSpec = sizeSpec,
                        modifier = Modifier
                            .graphicsLayer { alpha = notesAlpha.value * developed() }
                            .seamFade(),
                    )
                },
                label = {
                    Text(
                        text = "Memories",
                        style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Medium),
                        color = colors.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier
                            .graphicsLayer { alpha = labelAlpha.value * developed() }
                            .seamFade(),
                    )
                },
                chevron = {
                    // The chevron is the anchor: always fully there, still
                    // answering the pull with its nudge.
                    Icon(
                        imageVector = YoinSymbols.ChevronDown,
                        contentDescription = null,
                        tint = colors.onSurfaceVariant,
                        modifier = Modifier
                            .size(PillChevronSize)
                            .seamFade()
                            .graphicsLayer {
                                val hint = hintProgress()
                                translationY = hint * PillChevronNudge.toPx()
                                alpha = 0.62f + hint * 0.38f
                            },
                    )
                },
            )
        }
    }
}

/** Holds the last non-null [value] across recompositions (no snapshot write). */
@Composable
internal fun <T : Any> rememberLastNonNull(value: T?): T? {
    val holder = remember { arrayOfNulls<Any>(1) }
    if (value != null) holder[0] = value
    @Suppress("UNCHECKED_CAST")
    return holder[0] as T?
}

/** Which parts the data asks for right now (the fit rule works on these). */
private data class PillTargets(
    val cover: Boolean,
    val score: Boolean,
    val label: Boolean,
    val notes: Boolean,
)

/** Each part's animated width share, 0..1 — read in the layout pass only. */
private class PillFractions(
    val cover: () -> Float,
    val score: () -> Float,
    val label: () -> Float,
    val notes: () -> Float,
)

private enum class PillSlot { Cover, Score, NotesFull, NotesShort, Label, Chevron }

/**
 * Lays the pill's parts on one line and keeps it inside the width the header
 * hands it. The fit rule runs on the TARGET parts at full width — the roomiest
 * notes form that fits, then the score dropped if even that doesn't fit; the
 * "Memories" label ellipsizes before it can push the chevron out — so a
 * space-driven choice snaps. Each placed part then takes its animated share
 * of the width (clipped to it, so a leaving part wipes out instead of
 * overlapping its neighbour). Every choice is made in the layout pass: a width
 * hand-off (the Now Playing side panel) never recomposes or subcomposes.
 */
@Composable
private fun MemoryPillLayout(
    showCover: Boolean,
    showScore: Boolean,
    showNotes: Boolean,
    target: PillTargets,
    widthFraction: PillFractions,
    stickerGap: Boolean,
    cover: @Composable () -> Unit,
    score: @Composable () -> Unit,
    notesFull: @Composable () -> Unit,
    notesShort: @Composable () -> Unit,
    label: @Composable () -> Unit,
    chevron: @Composable () -> Unit,
    modifier: Modifier = Modifier,
) {
    fun fractionOf(slot: PillSlot): () -> Float = when (slot) {
        PillSlot.Cover -> widthFraction.cover
        PillSlot.Score -> widthFraction.score
        PillSlot.Label -> widthFraction.label
        PillSlot.NotesFull, PillSlot.NotesShort -> widthFraction.notes
        PillSlot.Chevron -> { { 1f } }
    }

    @Composable
    fun Slot(slot: PillSlot, content: @Composable () -> Unit) {
        val fraction = fractionOf(slot)
        Box(
            Modifier
                .layoutId(slot)
                .drawWithContent {
                    val f = fraction().coerceIn(0f, 1f)
                    if (f >= 1f) {
                        drawContent()
                    } else if (f > 0f) {
                        // Wipe from the trailing side, mirrored in RTL.
                        val keep = size.width * f
                        if (layoutDirection == LayoutDirection.Ltr) {
                            clipRect(right = keep) { this@drawWithContent.drawContent() }
                        } else {
                            clipRect(left = size.width - keep) { this@drawWithContent.drawContent() }
                        }
                    }
                },
        ) { content() }
    }

    Layout(
        modifier = modifier,
        content = {
            if (showCover) Slot(PillSlot.Cover, cover)
            if (showScore) Slot(PillSlot.Score, score)
            Slot(PillSlot.Label, label)
            if (showNotes) {
                Slot(PillSlot.NotesFull, notesFull)
                Slot(PillSlot.NotesShort, notesShort)
            }
            Slot(PillSlot.Chevron, chevron)
        },
    ) { measurables, constraints ->
        val bySlot = measurables.associateBy { it.layoutId as PillSlot }
        val loose = constraints.copy(minWidth = 0, minHeight = 0)
        val available = if (constraints.hasBoundedWidth) constraints.maxWidth else Int.MAX_VALUE
        val coverInset = PillCoverInset.roundToPx()
        val textLead = PillTextLead.roundToPx()
        val trail = PillTrail.roundToPx()
        // Trailing gaps — "tight inside a cluster, loose between clusters".
        fun gapAfter(slot: PillSlot): Int = when (slot) {
            PillSlot.Cover -> (if (stickerGap) 12.dp else 8.dp).roundToPx()
            PillSlot.Score -> 10.dp.roundToPx()
            PillSlot.Label -> 8.dp.roundToPx()
            PillSlot.NotesFull, PillSlot.NotesShort -> 6.dp.roundToPx()
            PillSlot.Chevron -> 0
        }

        val chevron = bySlot.getValue(PillSlot.Chevron).measure(loose)
        val placeables = mutableMapOf(PillSlot.Chevron to chevron)
        listOf(PillSlot.Cover, PillSlot.Score, PillSlot.NotesFull, PillSlot.NotesShort).forEach { slot ->
            bySlot[slot]?.let { placeables[slot] = it.measure(loose) }
        }
        // The label only lives in the cover-less forms: bound it by what the
        // chevron leaves, so it ellipsizes instead of pushing the chevron out.
        val labelBudget = if (available == Int.MAX_VALUE) {
            Constraints.Infinity
        } else {
            (available - textLead - trail - chevron.width - gapAfter(PillSlot.Label)).coerceAtLeast(0)
        }
        bySlot[PillSlot.Label]?.let { placeables[PillSlot.Label] = it.measure(loose.copy(maxWidth = labelBudget)) }

        fun extent(slot: PillSlot): Int = placeables[slot]?.let { it.width + gapAfter(slot) } ?: 0

        // ── Fit rule on the targets, full widths ──
        val head = buildList {
            if (target.cover && PillSlot.Cover in placeables) add(PillSlot.Cover)
            if (target.score && PillSlot.Score in placeables) add(PillSlot.Score)
            if (target.label) add(PillSlot.Label)
        }
        fun targetWidth(slots: List<PillSlot>): Int =
            (if (PillSlot.Cover in slots) coverInset else textLead) + trail + chevron.width +
                slots.sumOf(::extent)
        val notesForm = if (PillSlot.NotesFull in placeables) {
            val base = targetWidth(head)
            chooseNotesForm(
                available = available,
                base = base,
                fullExtra = extent(PillSlot.NotesFull),
                shortExtra = extent(PillSlot.NotesShort),
            )
        } else {
            NotesForm.None
        }
        val notesSlot = when (notesForm) {
            NotesForm.Full -> PillSlot.NotesFull
            NotesForm.Short -> PillSlot.NotesShort
            NotesForm.None -> null
        }
        // Last resort on a very narrow header: give up the score (snaps).
        val dropScore = targetWidth(head + listOfNotNull(notesSlot.takeIf { target.notes })) > available &&
            PillSlot.Score in head

        // ── Animated placement ──
        val line = buildList {
            if (PillSlot.Cover in placeables) add(PillSlot.Cover)
            if (PillSlot.Score in placeables && !dropScore) add(PillSlot.Score)
            add(PillSlot.Label)
            notesSlot?.let(::add)
        }
        val coverShare = if (PillSlot.Cover in placeables) fractionOf(PillSlot.Cover)().coerceIn(0f, 1f) else 0f
        val lead = (textLead + (coverInset - textLead) * coverShare).roundToInt()
        val shares = line.associateWith { fractionOf(it)().coerceIn(0f, 1f) }
        val width = (
            lead + trail + chevron.width +
                line.sumOf { slot -> (extent(slot) * shares.getValue(slot)).roundToInt() }
            ).coerceIn(constraints.minWidth, available)
        val height = PillHeight.roundToPx()
        layout(width, height) {
            var x = lead
            line.forEach { slot ->
                val placeable = placeables.getValue(slot)
                val share = shares.getValue(slot)
                if (share > 0f) placeable.placeRelative(x, (height - placeable.height) / 2)
                x += (extent(slot) * share).roundToInt()
            }
            chevron.placeRelative(x, (height - chevron.height) / 2)
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun PillCover(
    latest: HomeMemoryPill.Latest?,
    sticker: Boolean,
    ink: Color,
    stickerBacking: Color,
    alpha: () -> Float,
    modifier: Modifier = Modifier,
    size: androidx.compose.ui.unit.Dp = PillCoverSize,
) {
    Box(modifier = modifier.size(size)) {
        // Bare cover — covers never carry an outline.
        ExpressiveMediaArtwork(
            model = latest?.coverArtUrl,
            contentDescription = null,
            modifier = Modifier
                .size(size)
                .graphicsLayer { this.alpha = alpha() },
            shape = YoinArtworkShapes.Thumb,
            fallbackIcon = YoinSymbols.Album,
            tonalElevation = 1.dp,
            shadowElevation = 0.dp,
        )
        if (sticker && latest != null) {
            // The showcase's award sticker, shrunk: a cookie on the cover's
            // corner. Filled = your rating, line = track average, dashed = none.
            // Its own alpha layer — it hangs 5dp past the cover, which a
            // layer on the cover box would clip.
            val cookie = MaterialShapes.Cookie12Sided.toShape()
            Box(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .graphicsLayer {
                        translationX = 5.dp.toPx()
                        translationY = 5.dp.toPx()
                        this.alpha = alpha()
                    }
                    .size(16.dp)
                    .background(stickerBacking, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                val markModifier = Modifier.size(12.dp)
                when (latest.scoreKind) {
                    MemoryScoreKind.ALBUM_RATING -> Box(markModifier.background(ink, cookie))
                    MemoryScoreKind.AVERAGE_TRACK_RATING -> Box(markModifier.border(1.25.dp, ink, cookie))
                    MemoryScoreKind.NONE -> Box(
                        markModifier.dashedOutline(
                            shape = cookie,
                            color = { ink.copy(alpha = PillOutlineAlpha) },
                            width = 1.dp,
                            dash = 2.dp,
                            gap = 1.5.dp,
                        ),
                    )
                }
            }
        }
    }
}

@Composable
internal fun PillNotes(
    text: String,
    reduced: Boolean,
    sizeSpec: androidx.compose.animation.core.FiniteAnimationSpec<IntSize>,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = YoinSymbols.Note,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(14.dp),
        )
        RollingText(text = text, reduced = reduced, sizeSpec = sizeSpec) { value ->
            Text(
                text = value,
                style = MaterialTheme.typography.labelMedium.withTabularFigures(),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
            )
        }
    }
}

/**
 * Numbers that change while Home is on screen roll to their new value (up
 * when it grows, down when it shrinks) instead of snapping.
 */
@Composable
internal fun RollingText(
    text: String,
    reduced: Boolean,
    sizeSpec: androidx.compose.animation.core.FiniteAnimationSpec<IntSize>,
    modifier: Modifier = Modifier,
    content: @Composable (String) -> Unit,
) {
    AnimatedContent(
        targetState = text,
        modifier = modifier,
        transitionSpec = {
            if (reduced) {
                ContentTransform(EnterTransition.None, ExitTransition.None, sizeTransform = null)
            } else {
                val grows = (targetState.leadingNumber() ?: 0f) >= (initialState.leadingNumber() ?: 0f)
                val direction = if (grows) 1 else -1
                (
                    YoinMotion.fadeIn(role = YoinMotionRole.Standard) +
                        YoinMotion.slideInVertically(role = YoinMotionRole.Standard) { it / 2 * direction }
                    ) togetherWith (
                    YoinMotion.fadeOut(role = YoinMotionRole.Standard) +
                        YoinMotion.slideOutVertically(role = YoinMotionRole.Standard) { -it / 2 * direction }
                    ) using SizeTransform(clip = false) { _, _ -> sizeSpec }
            }
        },
        label = "memoryPillRoll",
    ) { value -> content(value) }
}

private fun String.leadingNumber(): Float? =
    takeWhile { it.isDigit() || it == '.' }.toFloatOrNull()

/** A full pill, anchored to the END edge (right in LTR), [visibleWidth] wide. */
private class EndAnchoredPill(private val visibleWidth: Float) : Shape {
    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline {
        val visible = visibleWidth.coerceIn(0f, size.width)
        val left = if (layoutDirection == LayoutDirection.Ltr) size.width - visible else 0f
        val radius = size.height / 2f
        return Outline.Rounded(
            RoundRect(
                left = left,
                top = 0f,
                right = left + visible,
                bottom = size.height,
                cornerRadius = CornerRadius(radius, radius),
            ),
        )
    }
}

// ── Previews ───────────────────────────────────────────────────────────

private val PreviewLatest = HomeMemoryPill.Latest(
    sessionId = 1L,
    albumId = MediaId.subsonic("describe"),
    albumName = "Describe",
    artistName = "Hannah Jadagu",
    coverArtUrl = null,
    scoreKind = MemoryScoreKind.ALBUM_RATING,
    scoreText = "8.4",
)

@Composable
private fun MemoryPillPreviewColumn(variant: HomeHintVariant) {
    androidx.compose.runtime.CompositionLocalProvider(LocalHomeHintVariant provides variant) {
        Column(
            modifier = Modifier
                .background(MaterialTheme.colorScheme.surfaceContainer)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            horizontalAlignment = Alignment.End,
        ) {
            listOf(
                null,
                HomeMemoryPill(latest = null, noteCount = 0),
                HomeMemoryPill(latest = null, noteCount = 3),
                HomeMemoryPill(
                    latest = PreviewLatest.copy(scoreKind = MemoryScoreKind.NONE, scoreText = null),
                    noteCount = 5,
                ),
                HomeMemoryPill(
                    latest = PreviewLatest.copy(scoreKind = MemoryScoreKind.AVERAGE_TRACK_RATING, scoreText = "7.6"),
                    noteCount = 12,
                ),
                HomeMemoryPill(latest = PreviewLatest, noteCount = 12),
            ).forEach { pill ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Spacer(Modifier.width(8.dp))
                    HomeMemoryEntry(
                        pill = pill,
                        hintProgress = { 0f },
                        revealProgress = { 1f },
                        extractBackdropColors = false,
                        onOpenMemoryFocus = {},
                        onNavigateToMemories = {},
                    )
                }
            }
        }
    }
}

@Preview(name = "Memory pill · ladder")
@Composable
private fun HomeMemoryPillLadderPreview() {
    YoinTheme { MemoryPillPreviewColumn(HomeHintVariant.Recommended) }
}

@Preview(name = "Memory pill · sticker", uiMode = android.content.res.Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun HomeMemoryPillStickerPreview() {
    YoinTheme { MemoryPillPreviewColumn(HomeHintVariant.Sticker) }
}
