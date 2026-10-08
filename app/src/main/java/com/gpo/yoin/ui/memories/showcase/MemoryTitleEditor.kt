package com.gpo.yoin.ui.memories.showcase

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.animate
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.findRootCoordinates
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.gpo.yoin.R
import com.gpo.yoin.ui.component.JournalSavePill
import com.gpo.yoin.ui.component.noRippleClickable
import com.gpo.yoin.ui.memories.MemoryEntityType
import com.gpo.yoin.ui.memories.MemoryEntry
import com.gpo.yoin.ui.memories.canRestoreGeneratedTitle
import com.gpo.yoin.ui.memories.copy.MemoryTitleKind
import com.gpo.yoin.ui.memories.memoryTitleRestoreLabel
import com.gpo.yoin.ui.memories.yoinOwnTitle
import com.gpo.yoin.ui.theme.LocalYoinMotionRole
import com.gpo.yoin.ui.theme.YoinMotion
import com.gpo.yoin.ui.theme.YoinMotionSpeed
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

/*
 * The user's own title for a memory (owner, 2026-10-05: "the AI title should leave room for the user to change
 * it"). Tap the title — on the card face, in the diary, on the spread's left page — and it becomes an in-place
 * BasicTextField in the title's own typography (no box, no outline: the journal language of the diary
 * writer). The keyboard opens; under the field a quiet row drops in: [Restore AI title] Cancel Save, Save the
 * album-coloured JournalSavePill. Save is CONFIRM and the title morphs in place; an empty save is a restore.
 *
 * Editing is an in-page mode of the memory, not a place: while it lasts no swipe pages the deck or moves q / p
 * (the gesture router holds every drag, the pager stops), and system back cancels the edit first — the
 * TitleEdit level of MemoriesPredictiveBack, which drives [MemoryTitleEditor] (preview: the edit's row fades
 * with the gesture; commit: cancel; cancel: the row comes back on the effects spring).
 *
 * The keyboard: in the diary the scroll keeps the row above it (the writer's bringIntoView,
 * [rememberKeepAboveImeModifier]); the card and the spread's left page don't scroll, so they rise by the
 * overlap ([MemoryTitleImeLift]), frame by frame with the keyboard's own animation.
 */

/** The three places a memory's title can be edited; each edits on its own (the card and the diary coexist). */
internal enum class MemoryTitleSurface { Card, Diary, Spread }

/** What a page needs to make its title editable: the deck's one editor, the host that writes, the haptics. */
@Immutable
internal class MemoryTitleEditing(
    val editor: MemoryTitleEditor,
    val host: MemoriesDiaryHost,
    val haptics: DiaryHaptics,
)

/**
 * The deck's one title editor: which memory's title is open, on which surface, its draft, and system back's
 * preview of a cancel. Snapshot state. An edit ends when its surface leaves (a rotation into another tier, a
 * new deck) — see [MemoryTitleSlot].
 */
@Stable
class MemoryTitleEditor internal constructor(
    initialKey: String?,
    initialDraft: TextFieldValue,
    initialSeed: String,
) {
    /** "surface:stableId" of the title being edited, or null. */
    var editingKey: String? by mutableStateOf(initialKey)
        private set

    /** The words in the field. */
    var draft: TextFieldValue by mutableStateOf(initialDraft)

    /** What the field opened with: saving it unchanged writes nothing. */
    var seed: String by mutableStateOf(initialSeed)
        private set

    val isEditing: Boolean get() = editingKey != null

    /**
     * System back's preview of cancelling the edit, eased 0..1: the edit's row fades by it. Read only in
     * layers. Left where a committed back put it (the row leaves from there); reset by [begin].
     */
    var backPreview: Float by mutableFloatStateOf(0f)
        private set

    /** The spring a cancelled back preview returns on (an effects spring: it is an alpha). */
    internal var settleSpec: AnimationSpec<Float>? = null

    private var settleJob: Job? = null

    /** The open field's save: the row's Save and the keyboard's Done run the same one. */
    internal var saveAction: (() -> Unit)? = null

    internal fun isEditing(key: String): Boolean = editingKey == key

    /** The row's Save: the open field saves (nothing open, nothing happens). */
    internal fun requestSave() {
        saveAction?.invoke()
    }

    /** Open [key]'s title with [seedText] in the field, the cursor at its end. */
    internal fun begin(key: String, seedText: String) {
        stopSettle()
        backPreview = 0f
        seed = seedText
        draft = TextFieldValue(seedText, TextRange(seedText.length))
        editingKey = key
    }

    /** The edit is over: saved, cancelled or restored. */
    fun end() {
        stopSettle()
        editingKey = null
    }

    /** A back gesture frame: [progress] is already eased. Cancels a running return. */
    fun previewBack(progress: Float) {
        stopSettle()
        backPreview = progress.coerceIn(0f, 1f)
    }

    /** Back committed: the edit is cancelled (the title goes back to what it was, the keyboard closes). */
    fun commitBack() = end()

    /** Back cancelled: the row comes back, on [settleSpec] run in [scope] (an outer scope). */
    fun cancelBack(scope: CoroutineScope) {
        stopSettle()
        val spec = settleSpec
        if (spec == null) {
            backPreview = 0f
            return
        }
        settleJob = scope.launch {
            animate(initialValue = backPreview, targetValue = 0f, animationSpec = spec) { value, _ ->
                backPreview = value
            }
        }
    }

    private fun stopSettle() {
        settleJob?.cancel()
        settleJob = null
    }
}

/**
 * The deck's title editor. Not saved across a recreation on purpose: an edit belongs to the surface it was
 * opened on, and a recreated page (often another tier) may not have that surface any more.
 */
@Composable
fun rememberMemoryTitleEditor(): MemoryTitleEditor {
    val editor = remember { MemoryTitleEditor(initialKey = null, initialDraft = TextFieldValue(), initialSeed = "") }
    val spec = YoinMotion.defaultEffectsSpec<Float>()
    SideEffect { editor.settleSpec = spec }
    return editor
}

/** The key of [memory]'s title on [surface]. */
internal fun MemoryTitleSurface.keyFor(memory: MemoryEntry): String = "$name:${memory.stableId}"

// ---------------------------------------------------------------- pure rules

/** The longest title the field takes: a title, not a note (the card's slot has to hold it). */
internal const val MEMORY_TITLE_MAX_LENGTH = 80

/** What the field opens with: the title on the card, or nothing when the album name stands in for one. */
internal fun MemoryEntry.titleDraftSeed(): String =
    if (cardTitleKind() == MemoryTitleKind.ALBUM) "" else memoryTitle.orEmpty()

/** The empty field's hint: what an empty save shows — Yoin's own title, else the album name. */
internal fun MemoryEntry.titlePlaceholder(): String = yoinOwnTitle()?.text ?: title

/** The restore button's words: the AI's title, or Yoin's local motif (not an AI line, so not called one). */
internal fun MemoryEntry.restoreTitleLabel(): String = memoryTitleRestoreLabel(yoinOwnTitle()?.kind)

/** One line: a title has no line breaks (a pasted one becomes spaces), and at most [MEMORY_TITLE_MAX_LENGTH]. */
internal fun sanitizeTitleInput(value: TextFieldValue): TextFieldValue {
    val flat = value.text.replace('\n', ' ').replace('\r', ' ')
    val text = if (flat.length > MEMORY_TITLE_MAX_LENGTH) flat.take(MEMORY_TITLE_MAX_LENGTH) else flat
    if (text == value.text) return value
    return value.copy(
        text = text,
        selection = TextRange(
            value.selection.start.coerceAtMost(text.length),
            value.selection.end.coerceAtMost(text.length),
        ),
        composition = null,
    )
}

/** What Save does with the field. */
internal sealed interface TitleEditOutcome {
    /** Nothing changed: close, write nothing. */
    data object Unchanged : TitleEditOutcome

    /** Take the user's title off: back to Yoin's own (a hard delete). */
    data object Restore : TitleEditOutcome

    /** The user's new title, trimmed. */
    data class Save(val title: String) : TitleEditOutcome
}

/**
 * Save on [draft]: the same words as [seed] (trimmed) change nothing; an empty field restores Yoin's title when
 * the user's title is in use ([userTitleInUse]) and changes nothing otherwise; anything else is the new title.
 */
internal fun titleEditOutcome(draft: String, seed: String, userTitleInUse: Boolean): TitleEditOutcome {
    val words = draft.trim()
    return when {
        words == seed.trim() -> TitleEditOutcome.Unchanged
        words.isEmpty() -> if (userTitleInUse) TitleEditOutcome.Restore else TitleEditOutcome.Unchanged
        else -> TitleEditOutcome.Save(words)
    }
}

/** The title as a face shows it: its words and the kind that picks its typography. */
@Immutable
internal sealed interface TitleFace {
    data class Shown(val text: String, val kind: MemoryTitleKind) : TitleFace

    /** The field is up, set in [kind]'s typography (the words are the editor's draft). */
    data class Editing(val kind: MemoryTitleKind) : TitleFace
}

/** AI and the user's own titles share the serif; the rest have a face each. */
internal fun MemoryTitleKind.typeGroup(): MemoryTitleKind =
    if (this == MemoryTitleKind.USER) MemoryTitleKind.AI else this

/**
 * Whether going from [from] to [to] needs no morph at all: the field closing on the very words it held, in
 * the same typography (a save, or a cancel of an untouched edit) — the field simply is the title. A crossfade
 * of identical glyphs would only dip their ink.
 */
internal fun titleFaceSeamless(from: TitleFace, to: TitleFace, draft: String): Boolean = when {
    from is TitleFace.Editing && to is TitleFace.Shown ->
        to.text == draft.trim() && from.kind.typeGroup() == to.kind.typeGroup()
    from is TitleFace.Shown && to is TitleFace.Editing ->
        from.kind.typeGroup() == to.kind.typeGroup()
    else -> from == to
}

// ---------------------------------------------------------------- the title slot

/**
 * A memory's title on [surface]: [display] at rest, the field while it is being edited, and a morph between
 * them (a crossfade with the size on the spatial spring; none when the field closes on its own words in the
 * same face). A title changed from elsewhere (the album page, sync) morphs the same way. [style] gives the
 * typography of a kind; [enabled]: this page is current and the surface takes input (an edit whose surface
 * stops taking input — the page swiped away, the diary opened over the card — ends, unsaved).
 *
 * [modifier] is the slot's: the morph's anchor and layer go there. [display] gets the modifier that makes the
 * resting title tappable ("Edit title" for TalkBack). Without [editing] the title is plain [display].
 */
@Composable
internal fun MemoryTitleSlot(
    memory: MemoryEntry,
    surface: MemoryTitleSurface,
    editing: MemoryTitleEditing?,
    tones: MemoryPaletteTones,
    style: @Composable (MemoryTitleKind) -> TextStyle,
    enabled: Boolean,
    centred: Boolean,
    modifier: Modifier = Modifier,
    fieldModifier: Modifier = Modifier,
    display: @Composable (text: String, kind: MemoryTitleKind, modifier: Modifier) -> Unit,
) {
    val editable = editing != null && editing.host.titlesEditable && memory.entityType == MemoryEntityType.ALBUM
    val editor = editing?.editor
    val key = surface.keyFor(memory)
    val open = editable && editor?.isEditing(key) == true
    if (editor != null) {
        LaunchedEffect(open, enabled) { if (open && !enabled) editor.end() }
        // the surface left with its title open (another tier, a new deck): the edit goes with it, unsaved
        DisposableEffect(editor, key, open) {
            onDispose { if (open && editor.isEditing(key)) editor.end() }
        }
    }
    // a save shows its words at once, before the card's refresh lands (cleared when the card's title moves)
    var held by remember(memory.stableId) { mutableStateOf<TitleFace.Shown?>(null) }
    LaunchedEffect(memory.memoryTitle, memory.memoryTitleKind) { held = null }
    val kind = memory.cardTitleKind()
    val shown = held ?: TitleFace.Shown(memory.memoryTitle?.takeIf(String::isNotBlank) ?: memory.title, kind)
    // the field keeps the face it opened in, whatever refreshes the card meanwhile
    val editKind = remember(open) { kind }
    val face: TitleFace = if (open) TitleFace.Editing(editKind) else shown

    val motionRole = LocalYoinMotionRole.current
    val motionScheme = MaterialTheme.motionScheme
    val sizeSpec = YoinMotion.defaultSpatialSpec<IntSize>()
    val interaction = remember { MutableInteractionSource() }
    val editTitle = stringResource(R.string.mem_cd_edit_title)
    AnimatedContent(
        targetState = face,
        transitionSpec = {
            if (titleFaceSeamless(initialState, targetState, editor?.draft?.text.orEmpty())) {
                EnterTransition.None togetherWith ExitTransition.None using SizeTransform(clip = false) { _, _ ->
                    sizeSpec
                }
            } else {
                YoinMotion.fadeIn(motionRole, YoinMotionSpeed.Fast, motionScheme) togetherWith
                    YoinMotion.fadeOut(motionRole, YoinMotionSpeed.Fast, motionScheme) using
                    SizeTransform(clip = false) { _, _ -> sizeSpec }
            }
        },
        contentAlignment = if (centred) Alignment.TopCenter else Alignment.TopStart,
        modifier = modifier,
        label = "memoryTitle",
    ) { now ->
        when (now) {
            is TitleFace.Shown -> display(
                now.text,
                now.kind,
                if (editable && enabled && editor != null) {
                    Modifier.noRippleClickable(interactionSource = interaction, onClickLabel = editTitle) {
                        editor.begin(key, memory.titleDraftSeed())
                    }
                } else {
                    Modifier
                },
            )
            is TitleFace.Editing -> if (editor != null && editing != null) {
                MemoryTitleField(
                    memory = memory,
                    editor = editor,
                    style = style(now.kind),
                    tones = tones,
                    onSave = {
                        commitTitleEdit(memory, editor, editing) { committed -> held = committed }
                    },
                    modifier = fieldModifier,
                )
            }
        }
    }
}

/** Save: CONFIRM, the outcome written through the host, the face held on its result until the card catches up. */
private fun commitTitleEdit(
    memory: MemoryEntry,
    editor: MemoryTitleEditor,
    editing: MemoryTitleEditing,
    hold: (TitleFace.Shown) -> Unit,
) {
    val outcome = titleEditOutcome(
        draft = editor.draft.text,
        seed = editor.seed,
        userTitleInUse = memory.memoryTitleKind == MemoryTitleKind.USER,
    )
    editing.haptics.confirm("title saved")
    when (outcome) {
        TitleEditOutcome.Unchanged -> Unit
        TitleEditOutcome.Restore -> {
            hold(memory.restoredTitleFace())
            editing.host.restoreMemoryTitle(memory)
        }
        is TitleEditOutcome.Save -> {
            hold(TitleFace.Shown(outcome.title, MemoryTitleKind.USER))
            editing.host.saveMemoryTitle(memory, outcome.title)
        }
    }
    editor.end()
}

/** The face a restore lands on: Yoin's own title, else the album name. */
private fun MemoryEntry.restoredTitleFace(): TitleFace.Shown =
    yoinOwnTitle()?.let { yoin -> TitleFace.Shown(yoin.text, yoin.kind) }
        ?: TitleFace.Shown(title, MemoryTitleKind.ALBUM)

/**
 * The field: the title's own typography and colour, the album ink's cursor, no box. One line of input (it
 * wraps like the title), Done saves. Focus (and so the keyboard) comes with it; it takes the keyboard away
 * when it leaves (a back commit ends the edit from outside the composition).
 */
@Composable
private fun MemoryTitleField(
    memory: MemoryEntry,
    editor: MemoryTitleEditor,
    style: TextStyle,
    tones: MemoryPaletteTones,
    onSave: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val focus = remember { FocusRequester() }
    val titleField = stringResource(R.string.mem_cd_title_field)
    val keyboard = LocalSoftwareKeyboardController.current
    val currentOnSave by rememberUpdatedState(onSave)
    LaunchedEffect(Unit) { focus.requestFocus() }
    DisposableEffect(editor) {
        val save = { currentOnSave() }
        editor.saveAction = save
        onDispose {
            if (editor.saveAction === save) editor.saveAction = null
            keyboard?.hide()
        }
    }
    val placeholder = memory.titlePlaceholder()
    BasicTextField(
        value = editor.draft,
        onValueChange = { value -> editor.draft = sanitizeTitleInput(value) },
        textStyle = style.copy(color = MaterialTheme.colorScheme.onSurface),
        cursorBrush = SolidColor(tones.ink),
        keyboardOptions = KeyboardOptions(
            capitalization = KeyboardCapitalization.Sentences,
            imeAction = ImeAction.Done,
        ),
        keyboardActions = KeyboardActions(onDone = { onSave() }),
        modifier = modifier
            .fillMaxWidth()
            .focusRequester(focus)
            .semantics { contentDescription = titleField },
        decorationBox = { inner ->
            // the inner field takes the whole width, so a centred title stays centred (it would wrap its text
            // and sit at the start otherwise)
            Box(modifier = Modifier.fillMaxWidth(), propagateMinConstraints = true) {
                if (editor.draft.text.isEmpty()) {
                    Text(
                        text = placeholder,
                        style = style,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(
                            alpha = DiaryWriterTokens.PlaceholderAlpha,
                        ),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                inner()
            }
        },
    )
}

/**
 * The quiet row under the field while [memory]'s title is open on [surface]: [Restore AI title] (only over a
 * user title with Yoin's own under it), Cancel, Save. It drops in and out like the diary writer's and fades
 * with a system back's preview. [keepAboveIme]: the host scrolls (the diary) and keeps the row above the
 * keyboard the writer's way; the card and the spread rise instead ([MemoryTitleImeLift]).
 */
@Composable
internal fun MemoryTitleEditRow(
    memory: MemoryEntry,
    surface: MemoryTitleSurface,
    editing: MemoryTitleEditing?,
    tones: MemoryPaletteTones,
    centred: Boolean,
    keepAboveIme: Boolean,
    modifier: Modifier = Modifier,
) {
    val restoreAi = stringResource(R.string.mem_title_restore_ai)
    val restoreMotif = stringResource(R.string.mem_title_restore_motif)
    val cancel = stringResource(R.string.mem_title_cancel)
    val discardTitle = stringResource(R.string.mem_cd_discard_title)
    val saveTitle = stringResource(R.string.mem_cd_save_title)
    val saveDescription = stringResource(R.string.mem_cd_title_save)
    val save = stringResource(R.string.mem_title_save)
    val restoreLabel = if (memory.yoinOwnTitle()?.kind == MemoryTitleKind.MOTIF) restoreMotif else restoreAi
    val editor = editing?.editor ?: return
    val open = editor.isEditing(surface.keyFor(memory))
    val focusManager = LocalFocusManager.current
    val motionRole = LocalYoinMotionRole.current
    val motionScheme = MaterialTheme.motionScheme
    AnimatedVisibility(
        visible = open,
        enter = expandVertically(YoinMotion.defaultSpatialSpec()) +
            YoinMotion.fadeIn(role = motionRole, speed = YoinMotionSpeed.Fast, expressiveScheme = motionScheme),
        exit = shrinkVertically(YoinMotion.defaultSpatialSpec()) +
            YoinMotion.fadeOut(role = motionRole, speed = YoinMotionSpeed.Fast, expressiveScheme = motionScheme),
        modifier = modifier,
    ) {
        val keep = if (keepAboveIme) rememberKeepAboveImeModifier(active = open) else Modifier
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .then(keep)
                .padding(top = 6.dp)
                .graphicsLayer { alpha = 1f - editor.backPreview },
            horizontalArrangement = if (centred) Arrangement.Center else Arrangement.End,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val labelStyle = diaryUiText(14.sp, FontWeight.SemiBold, 1f)
            if (memory.canRestoreGeneratedTitle()) {
                QuietTextButton(
                    label = restoreLabel,
                    style = labelStyle,
                    onClick = {
                        editing.haptics.confirm("title restored")
                        editing.host.restoreMemoryTitle(memory)
                        editor.end()
                        focusManager.clearFocus()
                    },
                )
            }
            QuietTextButton(
                label = cancel,
                style = labelStyle,
                clickLabel = discardTitle,
                onClick = {
                    editor.end()
                    focusManager.clearFocus()
                },
            )
            val saveInteraction = remember { MutableInteractionSource() }
            Box(
                modifier = Modifier
                    .height(DiaryWriterTokens.RowHit)
                    .noRippleClickable(interactionSource = saveInteraction, onClickLabel = saveTitle) {
                        // the same save as the keyboard's Done
                        editor.requestSave()
                    }
                    .semantics {
                        contentDescription = saveDescription
                        role = Role.Button
                    },
                contentAlignment = Alignment.Center,
            ) {
                JournalSavePill(
                    label = save,
                    containerColor = tones.ink,
                    contentColor = tones.onButton,
                    textStyle = labelStyle,
                    contentPadding = PaddingValues(horizontal = 16.dp),
                    modifier = Modifier.height(DiaryWriterTokens.PillHeight),
                )
            }
        }
    }
}

@Composable
private fun QuietTextButton(label: String, style: TextStyle, onClick: () -> Unit, clickLabel: String? = null) {
    val interaction = remember { MutableInteractionSource() }
    Box(
        modifier = Modifier
            .height(DiaryWriterTokens.RowHit)
            .noRippleClickable(interactionSource = interaction, onClickLabel = clickLabel ?: label, onClick = onClick)
            .semantics { role = Role.Button }
            .padding(horizontal = 16.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text = label, style = style, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
    }
}

// ---------------------------------------------------------------- the keyboard

/**
 * The diary writer's keyboard handling, shared: while [active], every move of the keyboard asks the scroll
 * this sits in to bring the element (and [DiaryWriterTokens.ImeClearance] under it) into view, so it rides
 * above the keyboard as it opens.
 */
@Composable
internal fun rememberKeepAboveImeModifier(active: Boolean): Modifier {
    val requester = remember { BringIntoViewRequester() }
    var size by remember { mutableStateOf(IntSize.Zero) }
    val density = LocalDensity.current
    val ime = WindowInsets.ime
    LaunchedEffect(active) {
        if (!active) return@LaunchedEffect
        snapshotFlow { ime.getBottom(density) }
            .distinctUntilChanged()
            .collect { bottom ->
                if (bottom > 0) {
                    val clearance = with(density) { DiaryWriterTokens.ImeClearance.toPx() }
                    requester.bringIntoView(Rect(0f, 0f, size.width.toFloat(), size.height + clearance))
                }
            }
    }
    return remember(requester) { Modifier.bringIntoViewRequester(requester).onSizeChanged { size = it } }
}

/**
 * The keyboard lift of a surface that doesn't scroll (the card, the spread's left page): while its title is
 * being edited — and until the keyboard is down again — the surface rises by however much the keyboard would
 * cover the title block (with [DiaryWriterTokens.ImeClearance] under it). Read in the surface's layer, so it
 * follows the keyboard's own animation frame by frame with no recomposition. The host is measured outside
 * its lift (outer) and the title inside it (inner), so the lift never feeds back into what it measures.
 */
@Stable
internal class MemoryTitleImeLift {
    private var hostOuter: LayoutCoordinates? = null
    private var hostInner: LayoutCoordinates? = null
    private var target: LayoutCoordinates? = null

    // engaged from an edit's start until the keyboard is down after it (plain: written and read in the layer)
    private var engaged = false

    fun onHostOuter(c: LayoutCoordinates) {
        hostOuter = c
    }

    fun onHostInner(c: LayoutCoordinates) {
        hostInner = c
    }

    fun onTarget(c: LayoutCoordinates) {
        target = c
    }

    /** The rise in px for a keyboard [imeBottomPx] tall; [editing]: the host's title is open. */
    fun liftPx(imeBottomPx: Int, editing: Boolean, density: Density): Float {
        if (editing) engaged = true
        if (!engaged) return 0f
        if (imeBottomPx <= 0) {
            if (!editing) engaged = false
            return 0f
        }
        val outer = hostOuter?.takeIf { it.isAttached } ?: return 0f
        val inner = hostInner?.takeIf { it.isAttached } ?: return 0f
        val block = target?.takeIf { it.isAttached } ?: return 0f
        val root = outer.findRootCoordinates()
        return memoryTitleLiftPx(
            blockBottomInRootPx = root.localPositionOf(outer, Offset.Zero).y +
                inner.localPositionOf(block, Offset(0f, block.size.height.toFloat())).y,
            rootHeightPx = root.size.height.toFloat(),
            imeBottomPx = imeBottomPx.toFloat(),
            clearancePx = with(density) { DiaryWriterTokens.ImeClearance.toPx() },
        )
    }
}

/** How far a block whose bottom sits at [blockBottomInRootPx] must rise to clear a keyboard (pure). */
internal fun memoryTitleLiftPx(
    blockBottomInRootPx: Float,
    rootHeightPx: Float,
    imeBottomPx: Float,
    clearancePx: Float,
): Float {
    if (imeBottomPx <= 0f) return 0f
    val visibleBottom = rootHeightPx - imeBottomPx
    return (blockBottomInRootPx + clearancePx - visibleBottom).coerceAtLeast(0f)
}
