package com.gpo.yoin.ui.detail

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.toShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.gpo.yoin.R
import com.gpo.yoin.data.notes.BacklinkSource
import com.gpo.yoin.data.notes.NoteBacklink
import com.gpo.yoin.shared.detail.ScrapbookTilt
import com.gpo.yoin.shared.notes.NoteMarkup
import com.gpo.yoin.symbols.YoinSymbols
import com.gpo.yoin.ui.component.ExpressiveMediaArtwork
import com.gpo.yoin.ui.component.MetaGroup
import com.gpo.yoin.ui.component.MetaLine
import com.gpo.yoin.ui.component.NoteMarkupText
import com.gpo.yoin.ui.component.dashedOutline
import com.gpo.yoin.ui.component.formatNoteDate
import com.gpo.yoin.ui.component.formatNotePosition
import com.gpo.yoin.ui.component.handPointer
import com.gpo.yoin.ui.component.seamFade
import com.gpo.yoin.ui.experience.rememberYoinHaptics
import com.gpo.yoin.ui.notes.LocalNoteMentions
import com.gpo.yoin.ui.notes.editor.NoteEditorState
import com.gpo.yoin.ui.notes.editor.NoteFormatToolbar
import com.gpo.yoin.ui.notes.editor.NoteMarkupField
import com.gpo.yoin.ui.notes.editor.noteEditorColors
import com.gpo.yoin.ui.theme.YoinArtworkShapes
import com.gpo.yoin.ui.theme.YoinContainerShapes
import com.gpo.yoin.ui.theme.YoinMotion

/*
 * The scrapbook's album notes and 被提及 (notes revamp phase B, prototype scenarios 4 and 5).
 *
 * Stickies: every album note is a sticky under the cover and review, oldest first; a dashed sticky writes
 * the next one in place (so does a tap on bare paper). A sticky opens in place into the note editor (the
 * same field as Now Playing's write bar: @ mentions, light Markdown); clearing it and saving deletes it,
 * with Undo.
 *
 * 被提及: the page's last block. Every note, album note and review of this account that mentions the album
 * or one of its songs, as a clipping: where it was written (cover, name, kind, moment, date) and one line of
 * its words with the mention marked. A tap opens where it was written.
 */

/** A tap on a to-do's box in a scrapbook note: (note id, line, whether it's an album note). */
internal val LocalScrapChecklistToggle =
    androidx.compose.runtime.staticCompositionLocalOf<((String, Int, Boolean) -> Unit)?> { null }

/**
 * What the album's stickies are doing: shown, or one being written. [autoFocus]: an opened sticky takes
 * the focus (and the keyboard comes up); the Tips demo opens one without it.
 */
@Stable
internal class StickyEditing(val autoFocus: Boolean = true) {
    /** Null: nothing open; [NEW]: the dashed sticky became an editor; else the note id being edited. */
    var open by mutableStateOf<String?>(null)
        private set
    var editor by mutableStateOf<NoteEditorState?>(null)
        private set

    fun startNew() {
        open = NEW
        editor = NoteEditorState("")
    }

    fun startEdit(sticky: ScrapSticky) {
        open = sticky.noteId
        editor = NoteEditorState(sticky.text)
    }

    fun close() {
        open = null
        editor = null
    }

    companion object {
        const val NEW = "\u0000new"
    }
}

/**
 * Saving an open sticky, as the page's edit: a new note, new words, or a delete (blank words on an existing
 * one, with Undo); a blank new sticky writes nothing. Pure, for the tests.
 */
internal fun stickySave(open: String, text: String): ScrapEdit? {
    val words = text.trim()
    return when {
        open == StickyEditing.NEW -> if (words.isEmpty()) null else ScrapEdit.NewAlbumNote(words)
        else -> ScrapEdit.AlbumNoteText(open, words)
    }
}

/** The album's stickies and the dashed one (or an editor in place of one). */
@Composable
internal fun StickyPile(
    stickies: List<ScrapSticky>,
    editing: StickyEditing,
    colors: ScrapbookColors,
    onSave: (open: String, text: String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.animateContentSize(YoinMotion.defaultSpatialSpec()),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        stickies.forEach { sticky ->
            if (editing.open == sticky.noteId) {
                editing.editor?.let { editor ->
                    StickyEditor(
                        editor,
                        sticky.tilt,
                        colors,
                        editing.autoFocus,
                        onSave = { onSave(sticky.noteId, it) },
                        onCancel = editing::close,
                    )
                }
            } else {
                Sticky(sticky, colors, onClick = { editing.startEdit(sticky) })
            }
        }
        if (editing.open == StickyEditing.NEW) {
            editing.editor?.let { editor ->
                StickyEditor(
                    editor,
                    0.6f,
                    colors,
                    editing.autoFocus,
                    onSave = { onSave(StickyEditing.NEW, it) },
                    onCancel = editing::close,
                )
            }
        } else if (editing.open == null) {
            DashedSticky(colors, onClick = editing::startNew)
        }
    }
}

/** One album note: secondaryContainer paper with a folded corner, its words drawn from the stored Markdown. */
@Composable
private fun Sticky(sticky: ScrapSticky, colors: ScrapbookColors, onClick: () -> Unit) {
    val words = remember(sticky.text) { NoteMarkup.plainText(sticky.text) }
    val description = stringResource(R.string.detail_scrap_cd_album_note, words)
    val editLabel = stringResource(R.string.notes_sticky_edit_cd, words)
    val haptics = rememberYoinHaptics()
    val toggle = LocalScrapChecklistToggle.current
    NoteMarkupText(
        text = sticky.text,
        onToggleChecklist = toggle?.let { t -> { line -> t(sticky.noteId, line, true) } },
        style = userTextStyle().copy(fontSize = 14.5.sp),
        color = colors.onSticky,
        mentionColor = colors.onSticky,
        modifier = Modifier
            .scrapLayer(sticky.tilt, depth = 0.45f)
            .seamFade()
            .clip(StickyShape)
            .background(colors.sticky)
            .drawBehind { drawPath(stickyFoldPath(size, StickyFold.toPx(), 4.dp.toPx()), colors.stickyFold) }
            .handPointer()
            .clickable(role = Role.Button, onClickLabel = editLabel) {
                haptics.performTick()
                onClick()
            }
            .semantics { contentDescription = description }
            .fillMaxWidth()
            .padding(start = 18.dp, end = 18.dp, top = 14.dp, bottom = 18.dp),
    )
}

/** "Write an album note": a dashed sticky outline with the note glyph. */
@Composable
private fun DashedSticky(colors: ScrapbookColors, onClick: () -> Unit) {
    val label = stringResource(R.string.notes_sticky_add)
    val haptics = rememberYoinHaptics()
    Box(
        modifier = Modifier
            .size(width = 64.dp, height = 56.dp)
            .clip(StickyShape)
            .dashedOutline(StickyShape, color = { colors.outline }, width = 1.5.dp, gap = 5.dp)
            .handPointer()
            .clickable(role = Role.Button, onClickLabel = label) {
                haptics.performTick()
                onClick()
            }
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        Icon(YoinSymbols.Note, contentDescription = null, tint = colors.muted, modifier = Modifier.size(22.dp))
    }
}

/** A sticky being written: the note editor on the sticky's paper, the formatting row, 取消 and 保存. */
@Composable
private fun StickyEditor(
    editor: NoteEditorState,
    tilt: Float,
    colors: ScrapbookColors,
    autoFocus: Boolean,
    onSave: (String) -> Unit,
    onCancel: () -> Unit,
) {
    val focus = remember { FocusRequester() }
    LaunchedEffect(editor) { if (autoFocus) runCatching { focus.requestFocus() } }
    Column(
        modifier = Modifier
            .scrapLayer(tilt * 0.4f, depth = 0.45f)
            .clip(StickyShape)
            .background(colors.sticky)
            .drawBehind { drawPath(stickyFoldPath(size, StickyFold.toPx(), 4.dp.toPx()), colors.stickyFold) }
            .fillMaxWidth()
            .padding(start = 14.dp, end = 14.dp, top = 12.dp, bottom = 14.dp),
    ) {
        NoteMarkupField(
            state = editor,
            modifier = Modifier.fillMaxWidth().focusRequester(focus),
            textStyle = userTextStyle().copy(fontSize = 14.5.sp),
            colors = noteEditorColors(text = colors.onSticky, chip = colors.stickyFold, chipText = colors.onSticky),
            placeholder = stringResource(R.string.notes_sticky_placeholder),
            lineLimits = TextFieldLineLimits.MultiLine(minHeightInLines = 3, maxHeightInLines = 10),
        )
        Spacer(Modifier.height(6.dp))
        // The sticky is narrow: the formatting row scrolls on a line of its own, 取消 / 保存 under it.
        NoteFormatToolbar(state = editor, modifier = Modifier.horizontalScroll(rememberScrollState()))
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            TextButton(onClick = onCancel) { Text(stringResource(R.string.notes_sticky_cancel)) }
            FilledTonalButton(onClick = { onSave(editor.text) }) { Text(stringResource(R.string.notes_sticky_save)) }
        }
    }
}

// ---------------------------------------------------------------------------
// 被提及
// ---------------------------------------------------------------------------

/** The page's last block: the writings elsewhere that mention this album or its songs. */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun BacklinksSection(
    backlinks: List<NoteBacklink>,
    albumSeed: String,
    colors: ScrapbookColors,
    modifier: Modifier = Modifier,
) {
    AnimatedVisibility(
        visible = backlinks.isNotEmpty(),
        modifier = modifier,
        enter = YoinMotion.fadeIn(role = com.gpo.yoin.ui.theme.YoinMotionRole.Expressive) +
            androidx.compose.animation.expandVertically(YoinMotion.defaultSpatialSpec()),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Box(
                    modifier = Modifier.size(30.dp).background(colors.ink, MaterialShapes.Cookie12Sided.toShape()),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(YoinSymbols.At, contentDescription = null, tint = colors.onInk, modifier = Modifier.size(18.dp))
                }
                Text(
                    text = stringResource(R.string.notes_backlinks_title),
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = pluralStringResource(R.plurals.notes_backlinks_count, backlinks.size, backlinks.size),
                    style = MaterialTheme.typography.labelLarge,
                    color = colors.muted,
                )
            }
            backlinks.forEach { link -> BacklinkClipping(link, albumSeed, colors) }
        }
    }
}

@Composable
private fun BacklinkClipping(link: NoteBacklink, albumSeed: String, colors: ScrapbookColors) {
    val controller = LocalNoteMentions.current
    val context = LocalContext.current
    val resources = LocalResources.current
    val haptics = rememberYoinHaptics()
    val source = link.source
    val name = when (source) {
        is BacklinkSource.SongNote -> source.trackTitle
        is BacklinkSource.AlbumNote -> source.albumName
        is BacklinkSource.Review -> source.albumName.orEmpty()
    }
    val kind = stringResource(
        when (source) {
            is BacklinkSource.SongNote -> R.string.notes_backlink_kind_note
            is BacklinkSource.AlbumNote -> R.string.notes_backlink_kind_album_note
            is BacklinkSource.Review -> R.string.notes_backlink_kind_review
        },
    )
    val moment = (source as? BacklinkSource.SongNote)?.positionMs?.let(::formatNotePosition)
    val date = formatNoteDate(link.at, resources = resources)
    // The row is a MetaLine (kind, moment, date muted); TalkBack reads it joined.
    val metaGroups = listOfNotNull(
        MetaGroup.Kind(kind),
        moment?.let { MetaGroup.Plain(it) },
        MetaGroup.Plain(date, muted = true),
    )
    val meta = listOfNotNull(kind, moment, date).joinToString(" · ")
    val description = stringResource(R.string.notes_backlink_cd, name, meta, link.snippet)
    val tilt = ScrapbookTilt.tilt(albumSeed, "bl/${link.source}", ScrapbookTilt.CARD) * 0.6f
    Column(
        modifier = Modifier
            .scrapLayer(tilt, depth = 0.3f)
            .seamFade()
            .fillMaxWidth()
            .clip(YoinContainerShapes.Card)
            .background(colors.receipt)
            .handPointer()
            .clickable(role = Role.Button) {
                haptics.performTick()
                controller?.openSource(context, source)
            }
            .clearAndSetSemantics {
                contentDescription = description
                role = Role.Button
                onClick {
                    controller?.openSource(context, source)
                    true
                }
            }
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            ExpressiveMediaArtwork(
                model = controller?.coverUrl(source.coverKey),
                contentDescription = null,
                shape = YoinArtworkShapes.Thumb,
                fallbackIcon = if (source is BacklinkSource.SongNote) YoinSymbols.MusicNote else YoinSymbols.Album,
                modifier = Modifier.size(34.dp),
                tonalElevation = 0.dp,
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = name,
                    style = MaterialTheme.typography.titleSmall,
                    color = colors.onReceipt,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                MetaLine(groups = metaGroups, style = MaterialTheme.typography.labelMedium, color = colors.onReceipt)
            }
            Icon(YoinSymbols.ChevronRight, contentDescription = null, tint = colors.muted, modifier = Modifier.size(20.dp))
        }
        Text(
            // The marker: the tertiary container at half strength, as a highlighter over paper.
            text = highlightMentions(link.snippet, link.marks, colors.ask.copy(alpha = 0.55f)),
            style = userTextStyle(),
            color = colors.onReceipt,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis,
        )
    }
    Spacer(Modifier.width(0.dp))
}

/**
 * [snippet] with [marks] (where the mentions that point here landed, NoteBacklink.marks) as by a
 * highlighter — only those words, never another that reads the same.
 */
internal fun highlightMentions(snippet: String, marks: List<IntRange>, marker: Color): AnnotatedString =
    buildAnnotatedString {
        append(snippet)
        marks.forEach { range ->
            val start = range.first.coerceIn(0, snippet.length)
            val end = (range.last + 1).coerceIn(start, snippet.length)
            if (end > start) addStyle(SpanStyle(background = marker, fontWeight = FontWeight.SemiBold), start, end)
        }
    }
