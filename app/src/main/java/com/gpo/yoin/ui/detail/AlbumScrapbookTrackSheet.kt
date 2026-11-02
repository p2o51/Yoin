package com.gpo.yoin.ui.detail

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.clearText
import androidx.compose.foundation.text.input.placeCursorAtEnd
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringArrayResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.gpo.yoin.R
import com.gpo.yoin.data.album.AlbumScrapbookAboutRef
import com.gpo.yoin.data.album.AlbumScrapbookAsk
import com.gpo.yoin.data.local.SongAboutEntry
import com.gpo.yoin.symbols.YoinSymbols
import com.gpo.yoin.ui.component.RatingSlider
import com.gpo.yoin.ui.component.YoinDropdownMenu
import com.gpo.yoin.ui.component.YoinDropdownMenuItem
import com.gpo.yoin.ui.component.YoinSnackbarHost
import com.gpo.yoin.ui.component.YoinTabGroup
import com.gpo.yoin.ui.component.formatNotePosition
import com.gpo.yoin.ui.component.markdownBoldAnnotatedString
import com.gpo.yoin.ui.experience.LocalMotionProfile
import com.gpo.yoin.ui.experience.MotionProfile
import com.gpo.yoin.ui.theme.YoinMotion
import com.gpo.yoin.ui.theme.YoinMotionRole
import com.gpo.yoin.ui.theme.YoinShapeTokens
import kotlinx.coroutines.flow.distinctUntilChanged

/*
 * Page 2's track sheet, v2 (owner-approved prototype v3, 2026-11-02). One sheet per track: the number, the
 * title and the score as a pill (tap it for the bar), then Now Playing's button group — About | Note · N.
 *
 * About: every fact and question, each with a pencil; a long press opens the editor too. The editor has
 * Delete, Regenerate with Gemini (not on the listener's own entries) and Done; a delete or a regenerate can be
 * undone from the snackbar. Under them, a new entry: a title first, then Ask Gemini or Write it yourself;
 * while the empty title has the keyboard, quick questions not asked yet sit under it.
 *
 * Note: each note with the three lyric lines above and below it (passages that touch merge, "· · ·" between
 * the rest); a note offers Edit / Play from here, a lyric line New note / Play from here; "All lyrics" puts
 * the notes into the whole text. An emptied note is deleted, with Undo.
 *
 * Opened by long-pressing a ticket, tapping the track's question / About card or a fact tag ("+N notes"
 * opens it on Note), or from Now Playing's About ("Manage in Scrapbook", on that row's editor).
 */

/** The track a sheet is about: its Ask / About (null: none yet) and its notes in timeline order. */
@Immutable
internal data class ScrapTrackSheetTarget(
    val track: ScrapTrack,
    val about: ScrapAboutSheet?,
    val notes: List<ScrapNoteLine> = emptyList(),
)

internal enum class ScrapSheetTab { About, Notes }

/** Which sheet is open, on which tab, and the About row it opened editing (Now Playing's Manage). */
@Immutable
internal data class ScrapSheetOpen(
    val songId: String,
    val tab: ScrapSheetTab = ScrapSheetTab.About,
    val editing: AlbumScrapbookAboutRef? = null,
)

/** A notice the page shows for the sheet (it outlives the sheet): [undo] is written back on Undo. */
@Immutable
internal data class ScrapNotice(val message: String, val icon: ImageVector? = null, val undo: ScrapEdit? = null)

/** The first piece on the page holding [songId]'s ticket; null once it has left the page. */
internal fun AlbumScrapbook.trackSheetFor(songId: String): ScrapTrackSheetTarget? {
    for (piece in pieces) {
        when (piece) {
            is ScrapPiece.TrackCluster ->
                if (piece.track.songId == songId) return ScrapTrackSheetTarget(piece.track, piece.sheet, piece.notes)
            is ScrapPiece.ContactSheet ->
                piece.tracks.firstOrNull { it.songId == songId }?.let { return ScrapTrackSheetTarget(it, null) }
            else -> Unit
        }
    }
    return null
}

@Composable
internal fun ScrapTrackSheet(
    target: ScrapTrackSheetTarget,
    colors: ScrapbookColors,
    onEdit: (ScrapEdit) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    feeds: ScrapSheetFeeds = ScrapSheetFeeds.None,
    onPlayFrom: (songId: String, positionMs: Long?) -> Unit = { _, _ -> },
    onNotice: (ScrapNotice) -> Unit = {},
    snackbar: SnackbarHostState? = null,
    initialTab: ScrapSheetTab = ScrapSheetTab.About,
    initialEditing: AlbumScrapbookAboutRef? = null,
) {
    val track = target.track
    var tab by remember(track.songId) { mutableStateOf(initialTab) }
    // Lyrics are read once the Note tab has been opened: About alone never fetches them.
    var notesSeen by remember(track.songId) { mutableStateOf(initialTab == ScrapSheetTab.Notes) }
    val lyrics by produceState(ScrapLyrics.Loading, track.songId, notesSeen, feeds) {
        if (notesSeen) feeds.lyrics(track.songId).collect { value = it }
    }
    val gemini by feeds.gemini.collectAsState()

    ScrapSheet(onDismiss = onDismiss) {
        Box(modifier = modifier) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .imePadding()
                    .verticalScroll(rememberScrollState())
                    .navigationBarsPadding()
                    .padding(start = SheetGutter, end = SheetGutter, bottom = 32.dp)
                    // A removed row folds away instead of the sheet snapping shorter.
                    .animateContentSize(YoinMotion.defaultSpatialSpec()),
            ) {
                ScoreHeader(track = track, colors = colors, onEdit = onEdit)
                YoinTabGroup(
                    labels = listOf(
                        stringResource(R.string.detail_scrap_tab_about),
                        if (target.notes.isEmpty()) {
                            stringResource(R.string.detail_scrap_tab_notes_none)
                        } else {
                            stringResource(R.string.detail_scrap_tab_notes, target.notes.size)
                        },
                    ),
                    selectedIndex = tab.ordinal,
                    onSelect = { index ->
                        tab = ScrapSheetTab.entries[index]
                        if (tab == ScrapSheetTab.Notes) notesSeen = true
                    },
                    selectedContainer = colors.ink,
                    selectedContent = colors.onInk,
                    container = colors.review,
                    content = colors.onReview,
                    modifier = Modifier.padding(horizontal = InnerGutter, vertical = 14.dp),
                )
                AnimatedContent(
                    targetState = tab,
                    transitionSpec = {
                        YoinMotion.fadeIn(role = YoinMotionRole.Standard) togetherWith
                            YoinMotion.fadeOut(role = YoinMotionRole.Standard)
                    },
                    label = "scrapSheetTab",
                ) { shown ->
                    when (shown) {
                        ScrapSheetTab.About -> AboutTab(
                            target = target,
                            colors = colors,
                            gemini = gemini,
                            initialEditing = initialEditing,
                            onEdit = onEdit,
                            onNotice = onNotice,
                        )
                        ScrapSheetTab.Notes -> NotesTab(
                            target = target,
                            lyrics = lyrics,
                            colors = colors,
                            onEdit = onEdit,
                            onNotice = onNotice,
                            onPlayFrom = onPlayFrom,
                        )
                    }
                }
            }
            // Over the sheet's foot while it is open; the page shows the same host once it closes.
            snackbar?.let { host ->
                YoinSnackbarHost(
                    hostState = host,
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .navigationBarsPadding(),
                )
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Header: number, title, the score pill and its bar.
// ---------------------------------------------------------------------------

@Composable
private fun ScoreHeader(track: ScrapTrack, colors: ScrapbookColors, onEdit: (ScrapEdit) -> Unit) {
    // The bar's own value; saved when the finger lifts, like the album's rate sheet.
    var live by remember(track.songId) { mutableFloatStateOf(track.score ?: 0f) }
    var saved by remember(track.songId) { mutableFloatStateOf(track.score ?: 0f) }
    var open by remember(track.songId) { mutableStateOf(false) }
    fun commit(score: Float) {
        if (score == saved) return
        saved = score
        onEdit(ScrapEdit.Score(track.songId, score))
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = InnerGutter, end = InnerGutter, top = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = track.number.toString().padStart(2, '0'),
            style = MaterialTheme.typography.labelLarge.copy(fontFeatureSettings = "tnum", letterSpacing = 0.5.sp),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.width(12.dp))
        Text(
            text = track.title,
            style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.SemiBold),
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Spacer(Modifier.width(12.dp))
        val scoreText = if (live > 0f) scrapScoreText(live) else null
        val description = scoreText?.let { stringResource(R.string.detail_scrap_cd_score, it) }
            ?: stringResource(R.string.detail_scrap_not_rated)
        val state = stringResource(
            if (open) R.string.detail_scrap_cd_score_open else R.string.detail_scrap_cd_score_closed,
        )
        Surface(
            onClick = { open = !open },
            shape = YoinShapeTokens.Full,
            color = colors.review,
            contentColor = colors.onReview,
            modifier = Modifier
                .heightIn(min = 40.dp)
                .widthIn(min = 60.dp)
                .semantics {
                    contentDescription = description
                    stateDescription = state
                },
        ) {
            Box(contentAlignment = Alignment.Center, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                Text(
                    text = scoreText ?: "—", // i18n-allow: the unrated pill's dash
                    style = MaterialTheme.typography.titleMedium.copy(
                        fontWeight = FontWeight.SemiBold,
                        fontFeatureSettings = "tnum",
                    ),
                )
            }
        }
    }
    AnimatedVisibility(
        visible = open,
        enter = YoinMotion.expandVertically(YoinMotionRole.Expressive) + YoinMotion.fadeIn(YoinMotionRole.Standard),
        exit = YoinMotion.shrinkVertically(YoinMotionRole.Standard) + YoinMotion.fadeOut(YoinMotionRole.Standard),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = InnerGutter, end = InnerGutter, top = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            RatingSlider(
                rating = live,
                onRatingChange = { live = it },
                orientation = Orientation.Horizontal,
                // The sheet scrolls and drags away: a swipe across the bar is the sheet's, not a score.
                claimOnDown = false,
                onInteractionChange = { active -> if (!active) commit(live) },
                gestureKey = track.songId,
                modifier = Modifier.weight(1f),
            )
            // The button's width, held when it fades out: clearing a score must not shift the bar.
            Box(modifier = Modifier.widthIn(min = 72.dp), contentAlignment = Alignment.CenterEnd) {
                AnimatedVisibility(
                    visible = live > 0f,
                    enter = YoinMotion.fadeIn(role = YoinMotionRole.Standard),
                    exit = YoinMotion.fadeOut(role = YoinMotionRole.Standard),
                ) {
                    TextButton(
                        onClick = {
                            live = 0f
                            commit(0f)
                        },
                    ) {
                        Text(stringResource(R.string.detail_scrap_clear))
                    }
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------
// About.
// ---------------------------------------------------------------------------

@Composable
private fun AboutTab(
    target: ScrapTrackSheetTarget,
    colors: ScrapbookColors,
    gemini: ScrapGemini,
    initialEditing: AlbumScrapbookAboutRef?,
    onEdit: (ScrapEdit) -> Unit,
    onNotice: (ScrapNotice) -> Unit,
) {
    val songId = target.track.songId
    // One row at a time is open; one opened from Now Playing is brought into view without the keyboard.
    var editing by remember(songId) { mutableStateOf(initialEditing) }
    var focusEditor by remember(songId) { mutableStateOf(initialEditing == null) }
    fun startEdit(ref: AlbumScrapbookAboutRef?) {
        if (ref == null) return
        focusEditor = true
        editing = ref
    }
    val deleted = stringResource(R.string.detail_scrap_deleted)
    val deletedNamed = stringResource(R.string.detail_scrap_deleted_named)
    fun remove(ref: AlbumScrapbookAboutRef, wasTitle: String?, wasAnswer: String, name: String?) {
        editing = null
        onEdit(ScrapEdit.About(ref, null, ""))
        onNotice(
            ScrapNotice(
                message = name?.let { deletedNamed.format(it) } ?: deleted,
                icon = YoinSymbols.Delete,
                undo = ScrapEdit.About(ref, wasTitle, wasAnswer),
            ),
        )
    }
    fun done(
        ref: AlbumScrapbookAboutRef,
        wasTitle: String?,
        wasAnswer: String,
        title: String?,
        answer: String,
        name: String?,
    ) {
        if (answer.isBlank()) return remove(ref, wasTitle, wasAnswer, name)
        editing = null
        val titleChanged = title != null && title.trim() != wasTitle.orEmpty()
        if (answer.trim() != wasAnswer || titleChanged) onEdit(ScrapEdit.About(ref, title, answer))
    }
    fun regenerate(ref: AlbumScrapbookAboutRef) {
        editing = null
        onEdit(ScrapEdit.Regenerate(ref))
    }

    val about = target.about
    val facts = about?.facts.orEmpty()
    val (paragraphs, shortFacts) = facts.partition { it.isParagraph }
    // Oldest first here, so a new answer lands just above the entry it came from.
    val asks = about?.asks.orEmpty().asReversed()
    val pending = gemini.asking.filter { it.songId == songId }
    val pendingKeys = pending.mapTo(HashSet()) { SongAboutEntry.normalize(it.question) }
    val shownKeys = asks.filterNot { it.own }.mapTo(HashSet()) { SongAboutEntry.normalize(it.question) }

    Column(modifier = Modifier.fillMaxWidth()) {
        shortFacts.forEachIndexed { i, fact ->
            key(fact.ref ?: fact.label) {
                val open = fact.ref != null && fact.ref == editing
                val label = scrapFactLabel(fact.label)
                if (i > 0 && !open && shortFacts[i - 1].ref != editing) {
                    HorizontalDivider(
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f),
                        modifier = Modifier.padding(horizontal = InnerGutter),
                    )
                }
                FactEntry(
                    fact = fact,
                    label = label,
                    colors = colors,
                    paragraph = false,
                    editing = open,
                    focusEditor = focusEditor,
                    thinking = fact.ref in gemini.regenerating,
                    onStartEdit = { startEdit(fact.ref) },
                    onDelete = { fact.ref?.let { remove(it, null, fact.value, label) } },
                    onRegenerate = { fact.ref?.let(::regenerate) },
                    onDone = { answer -> fact.ref?.let { done(it, null, fact.value, null, answer, label) } },
                )
            }
        }
        paragraphs.forEach { fact ->
            key(fact.ref ?: fact.label) {
                val label = scrapFactLabel(fact.label)
                FactEntry(
                    fact = fact,
                    label = label,
                    colors = colors,
                    paragraph = true,
                    editing = fact.ref != null && fact.ref == editing,
                    focusEditor = focusEditor,
                    thinking = fact.ref in gemini.regenerating,
                    onStartEdit = { startEdit(fact.ref) },
                    onDelete = { fact.ref?.let { remove(it, null, fact.value, label) } },
                    onRegenerate = { fact.ref?.let(::regenerate) },
                    onDone = { answer -> fact.ref?.let { done(it, null, fact.value, null, answer, label) } },
                    modifier = Modifier.padding(top = 12.dp),
                )
            }
        }
        asks.forEach { ask ->
            key(ask.ref ?: ask.question) {
                val asking = !ask.own && SongAboutEntry.normalize(ask.question) in pendingKeys
                // What Undo writes back: an own entry's heading is its question; Gemini's title otherwise.
                val wasTitle = if (ask.own) ask.question else ask.title
                AskEntry(
                    ask = ask,
                    colors = colors,
                    editing = ask.ref != null && ask.ref == editing,
                    focusEditor = focusEditor,
                    thinking = asking || ask.ref in gemini.regenerating,
                    onStartEdit = { startEdit(ask.ref) },
                    onDelete = { ask.ref?.let { remove(it, wasTitle, ask.answer, null) } },
                    onRegenerate = { ask.ref?.let(::regenerate) },
                    onDone = { title, answer -> ask.ref?.let { done(it, wasTitle, ask.answer, title, answer, null) } },
                )
            }
        }
        pending.filter { SongAboutEntry.normalize(it.question) !in shownKeys }.forEach { asking ->
            key("asking:${asking.question}") { PendingAsk(question = asking.question, colors = colors) }
        }
        val asked = remember(about) { about?.asks.orEmpty().filterNot { it.own }.map { it.question } }
        NewEntry(
            colors = colors,
            prompts = remainingQuickPrompts(
                prompts = stringArrayResource(R.array.cmp_quick_prompts).toList(),
                asked = asked + pending.map { it.question },
            ),
            onAsk = { question -> onEdit(ScrapEdit.Ask(songId, question)) },
            onWrite = { heading, text -> onEdit(ScrapEdit.Write(songId, heading, text)) },
            modifier = Modifier.padding(top = 22.dp),
        )
    }
}

/** The long About paragraph (the "review" field), shown under the short facts with its label above it. */
private val ScrapTag.isParagraph: Boolean
    get() = ref?.entryKey == SongAboutEntry.CANON_REVIEW || label == factLabel(SongAboutEntry.CANON_REVIEW)

/** One About fact: label and value with a pencil (a long press edits too), or its editor. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun FactEntry(
    fact: ScrapTag,
    label: String,
    colors: ScrapbookColors,
    paragraph: Boolean,
    editing: Boolean,
    focusEditor: Boolean,
    thinking: Boolean,
    onStartEdit: () -> Unit,
    onDelete: () -> Unit,
    onRegenerate: () -> Unit,
    onDone: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val editLabel = stringResource(R.string.detail_scrap_cd_edit)
    AnimatedContent(
        targetState = editing,
        transitionSpec = {
            YoinMotion.fadeIn(role = YoinMotionRole.Standard) togetherWith
                YoinMotion.fadeOut(role = YoinMotionRole.Standard)
        },
        label = "scrapFactEdit",
        modifier = modifier,
    ) { open ->
        if (open) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(20.dp))
                    .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                    .padding(12.dp),
            ) {
                Text(text = label, style = FactLabelStyle(), color = colors.ink)
                AboutEditor(
                    title = null,
                    text = fact.value,
                    canRegenerate = true,
                    autoFocus = focusEditor,
                    fieldColor = MaterialTheme.colorScheme.surface,
                    onDelete = onDelete,
                    onRegenerate = onRegenerate,
                    onDone = { _, answer -> onDone(answer) },
                )
            }
        } else {
            val value: @Composable (Modifier) -> Unit = { m ->
                if (thinking) {
                    ThinkingDots(color = colors.ink, modifier = m.padding(vertical = 8.dp))
                } else {
                    Text(
                        text = markdownBoldAnnotatedString(fact.value),
                        style = MaterialTheme.typography.bodyLarge.copy(lineHeight = if (paragraph) 1.6.em else 1.5.em),
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = m,
                    )
                }
            }
            val row = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .combinedClickable(
                    enabled = fact.ref != null && !thinking,
                    onClick = {},
                    onLongClickLabel = editLabel,
                    onLongClick = onStartEdit,
                )
                .padding(start = InnerGutter, top = 8.dp, bottom = 8.dp)
            if (paragraph) {
                Row(modifier = row, verticalAlignment = Alignment.Top) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(text = label, style = FactLabelStyle(), color = colors.ink)
                        value(Modifier.padding(top = 4.dp))
                    }
                    EditButton(visible = fact.ref != null && !thinking, onClick = onStartEdit)
                }
            } else {
                Row(modifier = row, verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = label,
                        style = FactLabelStyle(),
                        color = colors.ink,
                        modifier = Modifier.width(FactLabelWidth),
                    )
                    Spacer(Modifier.width(10.dp))
                    Box(modifier = Modifier.weight(1f)) { value(Modifier) }
                    EditButton(visible = fact.ref != null && !thinking, onClick = onStartEdit)
                }
            }
        }
    }
}

/** A question in full (the listener's words, Gemini's title and answer), or one of the listener's own entries. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun AskEntry(
    ask: AlbumScrapbookAsk,
    colors: ScrapbookColors,
    editing: Boolean,
    focusEditor: Boolean,
    thinking: Boolean,
    onStartEdit: () -> Unit,
    onDelete: () -> Unit,
    onRegenerate: () -> Unit,
    onDone: (title: String?, answer: String) -> Unit,
) {
    val container = if (ask.own) colors.review else colors.ask
    val content = if (ask.own) colors.onReview else colors.onAsk
    val mark = if (ask.own) colors.ink else colors.askMark
    val editLabel = stringResource(R.string.detail_scrap_cd_edit)
    Column(
        modifier = Modifier
            .padding(top = 14.dp)
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(container),
    ) {
        AnimatedContent(
            targetState = editing,
            transitionSpec = {
                YoinMotion.fadeIn(role = YoinMotionRole.Standard) togetherWith
                    YoinMotion.fadeOut(role = YoinMotionRole.Standard)
            },
            label = "scrapAskEdit",
        ) { open ->
            if (open) {
                Column(modifier = Modifier.padding(16.dp)) {
                    if (!ask.own) {
                        Text(
                            text = stringResource(R.string.detail_scrap_asked, ask.question),
                            style = userTextStyle().copy(fontSize = 13.5.sp),
                            color = content.copy(alpha = 0.75f),
                        )
                    }
                    AboutEditor(
                        // An own entry's heading is its "question"; Gemini's title on an asked one.
                        title = if (ask.own) ask.question else ask.title.orEmpty(),
                        text = ask.answer,
                        canRegenerate = !ask.own,
                        autoFocus = focusEditor,
                        fieldColor = MaterialTheme.colorScheme.surface,
                        onDelete = onDelete,
                        onRegenerate = onRegenerate,
                        onDone = onDone,
                    )
                }
            } else {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .combinedClickable(
                            enabled = ask.ref != null && !thinking,
                            onClick = {},
                            onLongClickLabel = editLabel,
                            onLongClick = onStartEdit,
                        ),
                ) {
                    Column(modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 14.dp)) {
                        if (!ask.own) {
                            Text(
                                text = ask.question,
                                style = userTextStyle().copy(fontSize = 13.5.sp),
                                color = content.copy(alpha = 0.75f),
                                modifier = Modifier.padding(end = 32.dp),
                            )
                        }
                        val heading = if (ask.own) ask.question else ask.title
                        heading?.let {
                            Text(
                                text = it,
                                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                                color = content,
                                modifier = Modifier.padding(
                                    top = if (ask.own) 0.dp else 6.dp,
                                    end = if (ask.own) 32.dp else 0.dp,
                                ),
                            )
                        }
                        if (thinking) {
                            ThinkingDots(color = mark, modifier = Modifier.padding(top = 10.dp, bottom = 4.dp))
                        } else {
                            Text(
                                text = markdownBoldAnnotatedString(ask.answer),
                                style = MaterialTheme.typography.bodyLarge.copy(lineHeight = 1.55.em),
                                color = content,
                                modifier = Modifier.padding(top = 4.dp),
                            )
                        }
                        Byline(own = ask.own, color = mark, modifier = Modifier.padding(top = 8.dp))
                    }
                    Box(modifier = Modifier.align(Alignment.TopEnd).padding(4.dp)) {
                        EditButton(visible = ask.ref != null && !thinking, onClick = onStartEdit, tint = mark)
                    }
                }
            }
        }
    }
}

/** A question asked from the sheet that Gemini has not answered yet. */
@Composable
private fun PendingAsk(question: String, colors: ScrapbookColors) {
    Column(
        modifier = Modifier
            .padding(top = 14.dp)
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(colors.ask)
            .padding(16.dp),
    ) {
        Text(
            text = question,
            style = userTextStyle().copy(fontSize = 13.5.sp),
            color = colors.onAsk.copy(alpha = 0.75f),
        )
        ThinkingDots(color = colors.askMark, modifier = Modifier.padding(top = 12.dp, bottom = 4.dp))
        Byline(own = false, color = colors.askMark, modifier = Modifier.padding(top = 8.dp))
    }
}

@Composable
private fun Byline(own: Boolean, color: Color, modifier: Modifier = Modifier) {
    Row(modifier = modifier, verticalAlignment = Alignment.CenterVertically) {
        if (!own) {
            Icon(
                imageVector = YoinSymbols.Sparkle,
                contentDescription = null,
                tint = color,
                modifier = Modifier.size(14.dp),
            )
            Spacer(Modifier.width(4.dp))
        }
        Text(
            text = stringResource(if (own) R.string.detail_scrap_by_you else R.string.detail_scrap_by_gemini),
            style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold, letterSpacing = 0.2.sp),
            color = color,
        )
    }
}

/**
 * The editor for one row: [title] (null: no title field) and the text. Delete removes the row; Regenerate asks
 * Gemini again (both undoable from the snackbar); Done keeps what is in the fields, and an emptied text is a
 * delete.
 */
@Composable
private fun AboutEditor(
    title: String?,
    text: String,
    canRegenerate: Boolean,
    autoFocus: Boolean,
    fieldColor: Color,
    onDelete: () -> Unit,
    onRegenerate: () -> Unit,
    onDone: (title: String?, answer: String) -> Unit,
) {
    val titleField = rememberTextFieldState(title.orEmpty())
    val textField = rememberTextFieldState(text)
    val focus = remember { FocusRequester() }
    val actions = remember { BringIntoViewRequester() }
    LaunchedEffect(Unit) {
        textField.edit { placeCursorAtEnd() }
        if (autoFocus) focus.requestFocus() else actions.bringIntoView()
    }
    // Delete / Regenerate / Done stay above the keyboard while it rises (a landscape tablet's keyboard covered
    // them: focusing the field only brings the field itself into view).
    KeepAboveKeyboard(actions)
    Column(
        modifier = Modifier.padding(top = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (title != null) {
            EditField(
                state = titleField,
                hint = stringResource(R.string.detail_scrap_edit_title_hint),
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                color = fieldColor,
                singleLine = true,
            )
        }
        EditField(
            state = textField,
            hint = stringResource(R.string.detail_scrap_edit_text_hint),
            style = MaterialTheme.typography.bodyLarge.copy(lineHeight = 1.5.em),
            color = fieldColor,
            modifier = Modifier.focusRequester(focus),
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .bringIntoViewRequester(actions),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            EditorButton(
                label = stringResource(R.string.detail_scrap_delete),
                icon = YoinSymbols.Delete,
                container = fieldColor,
                content = MaterialTheme.colorScheme.error,
                onClick = onDelete,
            )
            if (canRegenerate) {
                EditorButton(
                    label = stringResource(R.string.detail_scrap_regenerate),
                    icon = YoinSymbols.Sparkle,
                    container = fieldColor,
                    content = MaterialTheme.colorScheme.tertiary,
                    onClick = onRegenerate,
                )
            }
            Spacer(Modifier.weight(1f))
            Button(
                onClick = { onDone(titleField.text.toString().takeIf { title != null }, textField.text.toString()) },
                contentPadding = EditorButtonPadding,
            ) {
                Text(stringResource(R.string.detail_scrap_done))
            }
        }
    }
}

@Composable
private fun EditorButton(label: String, icon: ImageVector, container: Color, content: Color, onClick: () -> Unit) {
    FilledTonalButton(
        onClick = onClick,
        colors = ButtonDefaults.filledTonalButtonColors(containerColor = container, contentColor = content),
        contentPadding = EditorButtonPadding,
    ) {
        Icon(imageVector = icon, contentDescription = null, modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(6.dp))
        Text(label, maxLines = 1)
    }
}

/**
 * A new entry: its title first, then Ask Gemini, or Write to type it yourself (the button becomes Save). While
 * the empty title has the keyboard, the quick questions not asked yet sit under it; one fills the title.
 */
@Composable
private fun NewEntry(
    colors: ScrapbookColors,
    prompts: List<String>,
    onAsk: (String) -> Unit,
    onWrite: (heading: String, text: String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val title = rememberTextFieldState()
    val body = rememberTextFieldState()
    var typing by remember { mutableStateOf(false) }
    var titleFocused by remember { mutableStateOf(false) }
    val titleFocus = remember { FocusRequester() }
    val bodyFocus = remember { FocusRequester() }
    val focusManager = LocalFocusManager.current
    val card = remember { BringIntoViewRequester() }
    KeepAboveKeyboard(card, active = titleFocused || typing)
    val hasTitle = title.text.isNotBlank()
    val hasBody = body.text.isNotBlank()
    fun reset() {
        title.clearText()
        body.clearText()
        typing = false
        focusManager.clearFocus()
    }
    LaunchedEffect(typing) { if (typing) bodyFocus.requestFocus() }
    Column(
        modifier = modifier
            .fillMaxWidth()
            .bringIntoViewRequester(card)
            .clip(RoundedCornerShape(24.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        EditField(
            state = title,
            hint = stringResource(R.string.detail_scrap_new_title_hint),
            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
            color = MaterialTheme.colorScheme.surface,
            singleLine = true,
            modifier = Modifier
                .focusRequester(titleFocus)
                .onFocusChanged { titleFocused = it.isFocused },
        )
        AnimatedVisibility(
            visible = titleFocused && !hasTitle && prompts.isNotEmpty(),
            enter = YoinMotion.expandVertically(YoinMotionRole.Expressive) + YoinMotion.fadeIn(YoinMotionRole.Standard),
            exit = YoinMotion.shrinkVertically(YoinMotionRole.Standard) + YoinMotion.fadeOut(YoinMotionRole.Standard),
        ) {
            QuickPrompts(
                prompts = prompts,
                container = colors.ask,
                content = colors.onAsk,
                onPick = { prompt ->
                    title.setTextAndPlaceCursorAtEnd(prompt)
                    titleFocus.requestFocus()
                },
            )
        }
        AnimatedVisibility(
            visible = typing,
            enter = YoinMotion.expandVertically(YoinMotionRole.Expressive) + YoinMotion.fadeIn(YoinMotionRole.Standard),
            exit = YoinMotion.shrinkVertically(YoinMotionRole.Standard) + YoinMotion.fadeOut(YoinMotionRole.Standard),
        ) {
            EditField(
                state = body,
                hint = stringResource(R.string.detail_scrap_new_text_hint),
                style = MaterialTheme.typography.bodyLarge.copy(lineHeight = 1.5.em),
                color = MaterialTheme.colorScheme.surface,
                minHeight = 84.dp,
                modifier = Modifier.focusRequester(bodyFocus),
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilledTonalButton(
                onClick = {
                    onAsk(title.text.toString().trim())
                    reset()
                },
                enabled = hasTitle,
                colors = ButtonDefaults.filledTonalButtonColors(
                    containerColor = colors.ask,
                    contentColor = colors.askMark,
                ),
                modifier = Modifier
                    .weight(1f)
                    .heightIn(min = 44.dp),
            ) {
                Icon(imageVector = YoinSymbols.Sparkle, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text(stringResource(R.string.detail_scrap_ask_gemini), maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Button(
                onClick = {
                    if (!typing) {
                        typing = true
                    } else {
                        onWrite(title.text.toString().trim(), body.text.toString().trim())
                        reset()
                    }
                },
                enabled = hasTitle && (!typing || hasBody),
                colors = ButtonDefaults.buttonColors(containerColor = colors.ink, contentColor = colors.onInk),
                modifier = Modifier
                    .weight(1f)
                    .heightIn(min = 44.dp),
            ) {
                if (!typing) {
                    Icon(imageVector = YoinSymbols.Edit, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                }
                Text(
                    text = stringResource(if (typing) R.string.detail_scrap_save else R.string.detail_scrap_write),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/** The quick questions as a row of pills (Now Playing's Ask bar has the same row). */
@Composable
internal fun QuickPrompts(
    prompts: List<String>,
    container: Color,
    content: Color,
    onPick: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val label = stringResource(R.string.cmp_quick_prompts_label)
    Row(
        modifier = modifier
            .fillMaxWidth()
            .semantics { contentDescription = label }
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        prompts.forEach { prompt ->
            Surface(
                onClick = { onPick(prompt) },
                shape = YoinShapeTokens.Full,
                color = container,
                contentColor = content,
            ) {
                Text(
                    text = prompt,
                    style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Medium),
                    maxLines = 1,
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 9.dp),
                )
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Note.
// ---------------------------------------------------------------------------

@Composable
private fun NotesTab(
    target: ScrapTrackSheetTarget,
    lyrics: ScrapLyrics,
    colors: ScrapbookColors,
    onEdit: (ScrapEdit) -> Unit,
    onNotice: (ScrapNotice) -> Unit,
    onPlayFrom: (songId: String, positionMs: Long?) -> Unit,
) {
    val songId = target.track.songId
    val notes = target.notes
    var expanded by remember(songId) { mutableStateOf(false) }
    var editingNote by remember(songId) { mutableStateOf<String?>(null) }
    var draft by remember(songId) { mutableStateOf<ScrapNoteRow.Draft?>(null) }
    var picked by remember(songId) { mutableStateOf<String?>(null) }
    val rows = remember(lyrics.lines, notes, expanded, draft) { scrapNoteRows(lyrics.lines, notes, expanded, draft) }
    val noteDeleted = stringResource(R.string.detail_scrap_note_deleted)
    val playingFrom = stringResource(R.string.detail_scrap_playing_from)
    fun play(positionMs: Long?) {
        onPlayFrom(songId, positionMs)
        positionMs?.let { onNotice(ScrapNotice(playingFrom.format(formatNotePosition(it)), YoinSymbols.PlayArrow)) }
    }
    fun deleteNote(id: String) {
        editingNote = null
        onEdit(ScrapEdit.NoteText(id, ""))
        onNotice(ScrapNotice(noteDeleted, YoinSymbols.Delete, ScrapEdit.RestoreNote(id)))
    }
    val timed = lyrics.lines.isNotEmpty()

    Column(modifier = Modifier.fillMaxWidth()) {
        if (timed) {
            Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                FilledTonalButton(
                    onClick = {
                        draft = null
                        picked = null
                        expanded = !expanded
                    },
                    colors = ButtonDefaults.filledTonalButtonColors(
                        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                        contentColor = MaterialTheme.colorScheme.onSurface,
                    ),
                ) {
                    Text(
                        stringResource(
                            if (expanded) R.string.detail_scrap_collapse_lyrics else R.string.detail_scrap_expand_lyrics,
                        ),
                    )
                    Spacer(Modifier.width(6.dp))
                    Icon(
                        imageVector = if (expanded) YoinSymbols.ChevronUp else YoinSymbols.ChevronDown,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                }
            }
            if (notes.isNotEmpty() && !expanded) {
                SheetHint(stringResource(R.string.detail_scrap_notes_hint), Modifier.padding(top = 6.dp, bottom = 8.dp))
            }
        } else if (lyrics.loading && notes.isEmpty()) {
            SheetHint(stringResource(R.string.detail_scrap_lyrics_loading), Modifier.padding(vertical = 8.dp))
        }
        rows.forEach { row ->
            key(row.key) {
                when (row) {
                    is ScrapNoteRow.Lyric -> LyricRow(
                        row = row,
                        menuOpen = picked == row.key,
                        onTap = { picked = row.key },
                        onDismissMenu = { picked = null },
                        onNewNote = {
                            picked = null
                            editingNote = null
                            draft = ScrapNoteRow.Draft(row.line.startMs)
                        },
                        onPlay = {
                            picked = null
                            play(row.line.startMs)
                        },
                    )
                    is ScrapNoteRow.Note -> {
                        val note = row.note
                        if (editingNote == note.noteId) {
                            NoteEditor(
                                positionMs = note.positionMs,
                                text = note.text,
                                colors = colors,
                                canDelete = true,
                                onDelete = { deleteNote(note.noteId) },
                                onDone = { text ->
                                    if (text.isBlank()) {
                                        deleteNote(note.noteId)
                                    } else {
                                        editingNote = null
                                        if (text.trim() != note.text) onEdit(ScrapEdit.NoteText(note.noteId, text))
                                    }
                                },
                            )
                        } else {
                            NoteCard(
                                note = note,
                                colors = colors,
                                menuOpen = picked == row.key,
                                onTap = { picked = row.key },
                                onDismissMenu = { picked = null },
                                onEditNote = {
                                    picked = null
                                    draft = null
                                    editingNote = note.noteId
                                },
                                onPlay = {
                                    picked = null
                                    play(note.positionMs)
                                },
                            )
                        }
                    }
                    is ScrapNoteRow.Draft -> NoteEditor(
                        positionMs = row.positionMs,
                        text = "",
                        colors = colors,
                        canDelete = false,
                        onDelete = { draft = null },
                        onDone = { text ->
                            draft = null
                            if (text.isNotBlank()) onEdit(ScrapEdit.NewNote(songId, row.positionMs, text))
                        },
                    )
                    is ScrapNoteRow.Gap -> Text(
                        text = "·  ·  ·", // i18n-allow: the gap between two passages
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.outline,
                        textAlign = TextAlign.Center,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 10.dp),
                    )
                }
            }
        }
        if (notes.isEmpty() && draft == null && !lyrics.loading) {
            SheetHint(
                stringResource(
                    if (timed) R.string.detail_scrap_notes_empty else R.string.detail_scrap_notes_empty_untimed,
                ),
                Modifier.padding(top = 12.dp),
            )
        }
        // Without timed lyrics there is no line to start from: a note without a moment.
        if (!timed && !lyrics.loading && draft == null && editingNote == null) {
            Box(modifier = Modifier.fillMaxWidth().padding(top = 12.dp), contentAlignment = Alignment.Center) {
                FilledTonalButton(
                    onClick = { draft = ScrapNoteRow.Draft(null) },
                    colors = ButtonDefaults.filledTonalButtonColors(
                        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                        contentColor = MaterialTheme.colorScheme.onSurface,
                    ),
                ) {
                    Icon(imageVector = YoinSymbols.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(stringResource(R.string.detail_scrap_new_note))
                }
            }
        }
    }
}

@Composable
private fun LyricRow(
    row: ScrapNoteRow.Lyric,
    menuOpen: Boolean,
    onTap: () -> Unit,
    onDismissMenu: () -> Unit,
    onNewNote: () -> Unit,
    onPlay: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    Box {
        Text(
            text = row.line.text,
            style = MaterialTheme.typography.bodyMedium.copy(
                fontSize = 13.sp,
                lineHeight = 1.75.em,
                fontWeight = if (row.noted) FontWeight.Medium else FontWeight.Normal,
            ),
            color = if (row.noted) scheme.onSurfaceVariant else scheme.outline,
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(8.dp))
                .background(if (menuOpen) scheme.surfaceContainerHigh else Color.Transparent)
                .clickable(onClick = onTap)
                .padding(horizontal = InnerGutter),
        )
        YoinDropdownMenu(expanded = menuOpen, onDismissRequest = onDismissMenu) {
            YoinDropdownMenuItem(
                text = stringResource(R.string.detail_scrap_new_note),
                onClick = onNewNote,
                leadingIcon = { Icon(YoinSymbols.Add, contentDescription = null) },
            )
            YoinDropdownMenuItem(
                text = stringResource(R.string.detail_scrap_play_from, formatNotePosition(row.line.startMs)),
                onClick = onPlay,
                leadingIcon = { Icon(YoinSymbols.PlayArrow, contentDescription = null) },
            )
        }
    }
}

@Composable
private fun NoteCard(
    note: ScrapNoteLine,
    colors: ScrapbookColors,
    menuOpen: Boolean,
    onTap: () -> Unit,
    onDismissMenu: () -> Unit,
    onEditNote: () -> Unit,
    onPlay: () -> Unit,
) {
    val at = note.positionMs?.let(::formatNotePosition)
    Box(modifier = Modifier.padding(vertical = 6.dp)) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(16.dp))
                .background(if (menuOpen) colors.note.copy(alpha = 0.8f) else colors.note)
                .journalRail(colors.ink)
                .clickable(onClick = onTap)
                .padding(start = 24.dp, end = 14.dp, top = 10.dp, bottom = 12.dp),
        ) {
            if (at != null) NoteStamp(at, colors)
            Text(
                text = note.text,
                style = userTextStyle(),
                color = colors.onNote,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
        YoinDropdownMenu(expanded = menuOpen, onDismissRequest = onDismissMenu) {
            YoinDropdownMenuItem(
                text = stringResource(R.string.detail_scrap_edit_note),
                onClick = onEditNote,
                leadingIcon = { Icon(YoinSymbols.Edit, contentDescription = null) },
            )
            YoinDropdownMenuItem(
                text = at?.let { stringResource(R.string.detail_scrap_play_from, it) }
                    ?: stringResource(R.string.detail_scrap_cd_play),
                onClick = onPlay,
                leadingIcon = { Icon(YoinSymbols.PlayArrow, contentDescription = null) },
            )
        }
    }
}

/** A note being written or changed in place: an emptied existing note is deleted (with Undo). */
@Composable
private fun NoteEditor(
    positionMs: Long?,
    text: String,
    colors: ScrapbookColors,
    canDelete: Boolean,
    onDelete: () -> Unit,
    onDone: (String) -> Unit,
) {
    val field = rememberTextFieldState(text)
    val focus = remember { FocusRequester() }
    val actions = remember { BringIntoViewRequester() }
    LaunchedEffect(Unit) {
        field.edit { placeCursorAtEnd() }
        focus.requestFocus()
    }
    KeepAboveKeyboard(actions)
    val at = positionMs?.let(::formatNotePosition)
    Column(
        modifier = Modifier
            .padding(vertical = 6.dp)
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(colors.note)
            .journalRail(colors.ink)
            .padding(start = 24.dp, end = 14.dp, top = 10.dp, bottom = 12.dp),
    ) {
        if (at != null) NoteStamp(at, colors)
        BasicTextField(
            state = field,
            textStyle = userTextStyle().copy(color = colors.onNote),
            cursorBrush = SolidColor(colors.ink),
            lineLimits = TextFieldLineLimits.MultiLine(),
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 2.dp)
                .focusRequester(focus),
            decorator = { inner ->
                Column {
                    Box {
                        if (field.text.isEmpty()) {
                            Text(
                                text = at?.let { stringResource(R.string.detail_scrap_note_hint, it) }
                                    ?: stringResource(R.string.detail_scrap_note_hint_plain),
                                style = userTextStyle(),
                                color = colors.onNote.copy(alpha = 0.5f),
                            )
                        }
                        inner()
                    }
                    Box(
                        modifier = Modifier
                            .padding(top = 4.dp)
                            .fillMaxWidth()
                            .height(2.dp)
                            .background(colors.ink.copy(alpha = 0.6f)),
                    )
                }
            },
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 10.dp)
                .bringIntoViewRequester(actions),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (canDelete) {
                EditorButton(
                    label = stringResource(R.string.detail_scrap_delete),
                    icon = YoinSymbols.Delete,
                    container = MaterialTheme.colorScheme.surface.copy(alpha = 0.7f),
                    content = MaterialTheme.colorScheme.error,
                    onClick = onDelete,
                )
            } else {
                TextButton(onClick = onDelete, contentPadding = EditorButtonPadding) {
                    Text(stringResource(R.string.detail_scrap_cancel))
                }
            }
            Spacer(Modifier.weight(1f))
            Button(
                onClick = { onDone(field.text.toString()) },
                colors = ButtonDefaults.buttonColors(containerColor = colors.ink, contentColor = colors.onInk),
                contentPadding = EditorButtonPadding,
            ) {
                Text(stringResource(R.string.detail_scrap_done))
            }
        }
    }
}

@Composable
private fun NoteStamp(at: String, colors: ScrapbookColors) {
    Text(
        text = at,
        style = MaterialTheme.typography.labelMedium.copy(
            fontFeatureSettings = "tnum",
            fontWeight = FontWeight.SemiBold,
            fontSize = 12.sp,
            letterSpacing = 0.3.sp,
        ),
        color = colors.ink,
    )
}

// ---------------------------------------------------------------------------
// Shared bits.
// ---------------------------------------------------------------------------

/** Keeps [requester]'s item in view while the keyboard moves (only while [active]). */
@Composable
private fun KeepAboveKeyboard(requester: BringIntoViewRequester, active: Boolean = true) {
    val ime = WindowInsets.ime
    val density = LocalDensity.current
    LaunchedEffect(active) {
        if (!active) return@LaunchedEffect
        snapshotFlow { ime.getBottom(density) }
            .distinctUntilChanged()
            .collect { requester.bringIntoView() }
    }
}

/** Keeps its slot while hidden, so the row beside it never jumps when an editor opens. */
@Composable
private fun EditButton(
    visible: Boolean,
    onClick: () -> Unit,
    tint: Color = MaterialTheme.colorScheme.onSurfaceVariant,
) {
    Box(modifier = Modifier.size(40.dp), contentAlignment = Alignment.Center) {
        AnimatedVisibility(
            visible = visible,
            enter = YoinMotion.fadeIn(role = YoinMotionRole.Standard),
            exit = YoinMotion.fadeOut(role = YoinMotionRole.Standard),
        ) {
            IconButton(onClick = onClick, modifier = Modifier.size(40.dp)) {
                Icon(
                    imageVector = YoinSymbols.Edit,
                    contentDescription = stringResource(R.string.detail_scrap_cd_edit),
                    tint = tint,
                    modifier = Modifier.size(18.dp),
                )
            }
        }
    }
}

/** Three dots blinking in turn while Gemini writes (still under reduced motion). */
@Composable
private fun ThinkingDots(color: Color, modifier: Modifier = Modifier) {
    val reduced = LocalMotionProfile.current == MotionProfile.AdaptiveReduced
    val description = stringResource(R.string.detail_scrap_cd_thinking)
    val transition = rememberInfiniteTransition(label = "scrapThinking")
    Row(
        modifier = modifier.semantics { contentDescription = description },
        horizontalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        repeat(3) { i ->
            val alpha = if (reduced) {
                0.6f
            } else {
                transition.animateFloat(
                    initialValue = 1f,
                    targetValue = 0.25f,
                    animationSpec = infiniteRepeatable(
                        animation = tween(durationMillis = 500, delayMillis = i * 150),
                        repeatMode = RepeatMode.Reverse,
                    ),
                    label = "scrapThinkingDot$i",
                ).value
            }
            Box(
                modifier = Modifier
                    .size(6.dp)
                    .alpha(alpha)
                    .clip(CircleShape)
                    .background(color),
            )
        }
    }
}

@Composable
private fun SheetHint(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.outline,
        textAlign = TextAlign.Center,
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = InnerGutter),
    )
}

@Composable
private fun EditField(
    state: TextFieldState,
    hint: String,
    style: TextStyle,
    color: Color,
    modifier: Modifier = Modifier,
    singleLine: Boolean = false,
    minHeight: androidx.compose.ui.unit.Dp = 0.dp,
) {
    val scheme = MaterialTheme.colorScheme
    BasicTextField(
        state = state,
        textStyle = style.copy(color = scheme.onSurface),
        cursorBrush = SolidColor(scheme.primary),
        lineLimits = if (singleLine) TextFieldLineLimits.SingleLine else TextFieldLineLimits.MultiLine(),
        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
        modifier = modifier.fillMaxWidth(),
        decorator = { field ->
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(16.dp))
                    .background(color)
                    .heightIn(min = minHeight)
                    .padding(horizontal = 14.dp, vertical = 12.dp),
            ) {
                if (state.text.isEmpty()) {
                    Text(text = hint, style = style, color = scheme.onSurfaceVariant)
                }
                field()
            }
        },
    )
}

@Composable
private fun FactLabelStyle(): TextStyle =
    MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold, letterSpacing = 0.2.sp)

/** The sheet's side margin; rows add [InnerGutter] inside it so a pressed row's fill reaches past the text. */
private val SheetGutter = 16.dp
private val InnerGutter = 8.dp
private val FactLabelWidth = 76.dp
private val EditorButtonPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp)
