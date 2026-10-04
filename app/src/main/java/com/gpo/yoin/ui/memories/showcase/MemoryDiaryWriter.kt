package com.gpo.yoin.ui.memories.showcase

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.gpo.yoin.ui.component.JournalRail
import com.gpo.yoin.ui.component.JournalSavePill
import com.gpo.yoin.ui.component.noRippleClickable
import com.gpo.yoin.ui.component.seamFade
import com.gpo.yoin.ui.memories.MemoryWriting
import com.gpo.yoin.ui.memories.copy.MemoryDates
import com.gpo.yoin.ui.theme.GoogleSansFlex
import com.gpo.yoin.ui.theme.YoinMotion
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.flow.distinctUntilChanged

/*
 * Your entry in the diary (twostate4 `reviewH` / `blankH` / `openWriter` / `saveWriter`): the review, or —
 * without one — today's blank page. The page is the NP composer's language (NoteComposer): the journal rail,
 * one placeholder line, no box, no outline, no tonal pill. A tap anywhere on it starts writing (the rail
 * comes up to full ink); Cancel and Save drop in under it — Save the album-coloured pill. Saving turns the
 * same block into the review entry IN PLACE: the typed words stay where they are, the rail fades, the body
 * slides 14 → 0 onto the entry's line on the spatial spring, and the header label crossfades Today → Your
 * review. The diary's scroll keeps Save above the keyboard (bringIntoView on the IME inset, as NP's writer
 * stays above it with imePadding).
 */

/** The blank page's geometry (prototype `.ts-ebody` / `.ts-rail` / `.ts-erow`). Layout constants. */
internal object DiaryWriterTokens {
    val BodyInset = 14.dp
    val RailWidth = 2.dp
    val RailInset = 4.dp
    const val RailRest = 0.32f
    const val PlaceholderAlpha = 0.7f
    val MinHeight = 64.dp
    val RowHit = 48.dp
    val PillHeight = 36.dp

    /** Room kept under Save when the keyboard pushes the page up. */
    val ImeClearance = 24.dp
}

/** A short review (≤ 16 characters) is set large. */
internal const val ShortReviewMax = 16

/**
 * Your entry. [review] null = today's blank page; [draft] (a save that didn't land) reopens it writing.
 * [enabled]: the diary is open (the page takes no focus behind the card).
 */
@Composable
internal fun MemoryDiaryEntry(
    review: MemoryWriting?,
    today: LocalDate,
    zone: ZoneId,
    tones: MemoryPaletteTones,
    draft: String?,
    enabled: Boolean,
    onSave: (String) -> Unit,
    onConfirm: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // A review that appears here (a save) keeps the body size it was typed at; only a review that was
    // already there when the page composed takes the large short-review size.
    val type = LocalMemoriesType.current
    val bornBlank = remember { review == null }
    val blank = review == null
    var writing by rememberSaveable { mutableStateOf(draft != null && blank) }
    var text by rememberSaveable { mutableStateOf(draft.orEmpty()) }
    var focused by remember { mutableStateOf(false) }
    val focus = remember { FocusRequester() }
    val focusManager = LocalFocusManager.current
    // a save that didn't land comes back as a blank page with its draft: reopen it, writing
    LaunchedEffect(draft, blank) {
        if (draft != null && blank) {
            text = draft
            writing = true
        }
    }

    val inset by animateDpAsState(
        targetValue = if (blank) DiaryWriterTokens.BodyInset else 0.dp,
        animationSpec = YoinMotion.defaultSpatialSpec(),
        label = "diaryEntryInset",
    )
    val railAlpha by animateFloatAsState(
        targetValue = when {
            !blank -> 0f
            writing && focused -> 1f
            else -> DiaryWriterTokens.RailRest
        },
        animationSpec = YoinMotion.defaultEffectsSpec(),
        label = "diaryEntryRail",
    )

    // keep Save above the keyboard while writing
    val requester = remember { BringIntoViewRequester() }
    var size by remember { mutableStateOf(IntSize.Zero) }
    val density = LocalDensity.current
    val ime = WindowInsets.ime
    LaunchedEffect(writing, blank) {
        if (!writing || !blank) return@LaunchedEffect
        snapshotFlow { ime.getBottom(density) }
            .distinctUntilChanged()
            .collect { bottom ->
                if (bottom > 0) {
                    val clearance = with(density) { DiaryWriterTokens.ImeClearance.toPx() }
                    requester.bringIntoView(Rect(0f, 0f, size.width.toFloat(), size.height + clearance))
                }
            }
    }

    val open = {
        if (enabled && blank) {
            writing = true
            focus.requestFocus()
        }
    }
    val interaction = remember { MutableInteractionSource() }
    Column(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = DiaryWriterTokens.MinHeight)
            .bringIntoViewRequester(requester)
            .onSizeChanged { size = it }
            .then(
                if (enabled && blank && !writing) {
                    Modifier
                        .noRippleClickable(
                            interactionSource = interaction,
                            onClickLabel = "Write a review",
                            onClick = open,
                        )
                        .semantics {
                            contentDescription = "Write a review"
                            role = Role.Button
                        }
                } else {
                    Modifier
                },
            ),
    ) {
        EntryHeader(
            date = review?.let { MemoryDates.localDate(it.writtenAt, zone) } ?: today,
            label = if (blank) "Today" else "Your review",
            tones = tones,
        )
        Box(Modifier.fillMaxWidth()) {
            if (railAlpha > 0.001f) {
                Box(Modifier.matchParentSize().padding(vertical = DiaryWriterTokens.RailInset)) {
                    JournalRail(
                        color = tones.ink.copy(alpha = railAlpha),
                        width = DiaryWriterTokens.RailWidth,
                        modifier = Modifier.fillMaxHeight(),
                    )
                }
            }
            // the spatial spring may overshoot past 0: padding never goes negative (as CSS clamps it)
            Column(Modifier.fillMaxWidth().padding(start = inset.coerceAtLeast(0.dp))) {
                if (review != null) {
                    ReviewBody(
                        text = review.text,
                        large = !bornBlank && review.text.length <= ShortReviewMax,
                        type = type,
                    )
                } else {
                    BasicTextField(
                        value = text,
                        onValueChange = { text = it },
                        enabled = enabled,
                        textStyle = diaryUserText(type.review, type.reviewLine).copy(
                            color = MaterialTheme.colorScheme.onSurface,
                        ),
                        cursorBrush = SolidColor(tones.ink),
                        modifier = Modifier
                            .fillMaxWidth()
                            .focusRequester(focus)
                            .onFocusChanged { state ->
                                focused = state.isFocused
                                if (state.isFocused) writing = true
                            }
                            .semantics { contentDescription = "Your review" }
                            .seamFade(type.review),
                        decorationBox = { inner ->
                            Box {
                                if (text.isEmpty()) {
                                    Text(
                                        text = "Write a few lines…",
                                        style = diaryUserText(type.review, type.reviewLine),
                                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(
                                            alpha = DiaryWriterTokens.PlaceholderAlpha,
                                        ),
                                    )
                                }
                                inner()
                            }
                        },
                    )
                }
            }
        }
        AnimatedVisibility(
            visible = writing && blank,
            enter = expandVertically(YoinMotion.defaultSpatialSpec()) + fadeIn(YoinMotion.fastEffectsSpec()),
            exit = shrinkVertically(YoinMotion.defaultSpatialSpec()) + fadeOut(YoinMotion.fastEffectsSpec()),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                val cancelInteraction = remember { MutableInteractionSource() }
                Box(
                    modifier = Modifier
                        .height(DiaryWriterTokens.RowHit)
                        .noRippleClickable(interactionSource = cancelInteraction, onClickLabel = "Discard the draft") {
                            text = ""
                            writing = false
                            focusManager.clearFocus()
                        }
                        .semantics { role = Role.Button }
                        .padding(horizontal = 16.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = "Cancel",
                        style = diaryUiText(14.sp, FontWeight.SemiBold, 1f),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                val saveInteraction = remember { MutableInteractionSource() }
                Box(
                    modifier = Modifier
                        .height(DiaryWriterTokens.RowHit)
                        .noRippleClickable(interactionSource = saveInteraction, onClickLabel = "Save the review") {
                            val words = text.trim()
                            if (words.isEmpty()) {
                                focus.requestFocus()
                            } else {
                                onConfirm()
                                onSave(words)
                                focusManager.clearFocus()
                            }
                        }
                        .semantics {
                            this.contentDescription = "Save"
                            role = Role.Button
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    JournalSavePill(
                        label = "Save",
                        containerColor = tones.ink,
                        contentColor = tones.onButton,
                        textStyle = diaryUiText(14.sp, FontWeight.SemiBold, 1f),
                        contentPadding = PaddingValues(horizontal = 16.dp),
                        modifier = Modifier.height(DiaryWriterTokens.PillHeight),
                    )
                }
            }
        }
    }
}

/** "Jul 26, 2026 · Your review": the date heads the entry (in the album's ink), like a diary page. */
@Composable
private fun EntryHeader(date: LocalDate, label: String, tones: MemoryPaletteTones) {
    val style = diaryUiText(13.sp, FontWeight.Medium, 1.35f)
    Row(modifier = Modifier.padding(bottom = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = buildAnnotatedString {
                withStyle(SpanStyle(fontWeight = FontWeight.SemiBold, color = tones.ink)) {
                    append(MemoryDates.dayWithYear(date))
                }
                append(" · ")
            },
            style = style,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.seamFade(style.fontSize),
        )
        Crossfade(targetState = label, animationSpec = YoinMotion.fastEffectsSpec(), label = "diaryEntryLabel") { now ->
            Text(
                text = now,
                style = style,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.seamFade(style.fontSize),
            )
        }
    }
}

/** Your review: no quotes, no signature; paragraphs .85em apart; a short one set large (26 / 30 tablet, 500). */
@Composable
private fun ReviewBody(text: String, large: Boolean, type: MemoriesTypeScale) {
    val style = if (large) {
        diaryUserText(type.reviewShort, type.reviewShortLine, FontWeight.Medium)
    } else {
        diaryUserText(type.review, type.reviewLine)
    }
    val paragraphs = text.split('\n').filter(String::isNotBlank)
    Column(Modifier.semantics(mergeDescendants = true) {}) {
        paragraphs.forEachIndexed { i, paragraph ->
            if (i > 0) Spacer(Modifier.height((type.review.value * 0.85f).dp))
            Text(
                text = paragraph,
                style = style,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.seamFade(style.fontSize),
            )
        }
    }
}

/** The user's own words, in the system face (never the serif). */
@Composable
internal fun diaryUserText(size: TextUnit, lineHeight: Float, weight: FontWeight = FontWeight.Normal) =
    diaryText(FontFamily.Default, weight, size, lineHeight)

/** UI words (labels, captions, buttons) in Google Sans Flex. */
@Composable
internal fun diaryUiText(size: TextUnit, weight: FontWeight, lineHeight: Float) =
    diaryText(GoogleSansFlex, weight, size, lineHeight)
