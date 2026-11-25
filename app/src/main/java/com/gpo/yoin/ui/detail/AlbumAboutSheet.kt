package com.gpo.yoin.ui.detail

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsBottomHeight
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.input.clearText
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.gpo.yoin.R
import com.gpo.yoin.data.album.AlbumAboutAsk
import com.gpo.yoin.symbols.YoinSymbols
import com.gpo.yoin.ui.component.ThinkingDots
import com.gpo.yoin.ui.component.YoinSnackbarHost
import com.gpo.yoin.ui.component.handPointer
import com.gpo.yoin.ui.component.markdownBoldAnnotatedString
import com.gpo.yoin.ui.component.sheetScrollEdge
import com.gpo.yoin.ui.theme.YoinMotion
import com.gpo.yoin.ui.theme.YoinMotionRole

/*
 * The album About sheet (owner + Carbon 2026-11-25), opened from the card on the scrapbook: the credits as a
 * dashed receipt (the song About's style in Now Playing), the whole paragraph with Regenerate (undoable from
 * the page's snackbar, like a song row's), the questions asked about the album, and Ask Gemini. Everything
 * here is Gemini's, in the secondary role like the card.
 */

@Composable
internal fun AlbumAboutSheet(
    albumName: String,
    about: ScrapPiece.AlbumAbout,
    credits: List<AlbumCreditItem>,
    colors: ScrapbookColors,
    onEdit: (ScrapEdit) -> Unit,
    onDismiss: () -> Unit,
    feeds: ScrapSheetFeeds = ScrapSheetFeeds.None,
    snackbar: SnackbarHostState? = null,
) {
    val gemini by feeds.gemini.collectAsState()
    ScrapSheet(onDismiss = onDismiss, padBottomInset = false) {
        AlbumAboutSheetBody(albumName, about, credits, colors, gemini, onEdit, snackbar)
    }
}

/** The sheet's content (apart from the sheet itself, so previews can draw it). */
@Composable
internal fun AlbumAboutSheetBody(
    albumName: String,
    about: ScrapPiece.AlbumAbout,
    credits: List<AlbumCreditItem>,
    colors: ScrapbookColors,
    gemini: ScrapGemini,
    onEdit: (ScrapEdit) -> Unit,
    snackbar: SnackbarHostState? = null,
) {
    Column(modifier = Modifier.fillMaxWidth().imePadding()) {
        Column(modifier = Modifier.fillMaxWidth().padding(horizontal = AboutSheetGutter)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = YoinSymbols.Sparkle,
                    contentDescription = null,
                    tint = colors.aboutMark,
                    modifier = Modifier.size(14.dp),
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    text = stringResource(R.string.detail_scrap_about),
                    style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold),
                    color = colors.aboutMark,
                )
            }
            Text(
                text = albumName,
                style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.SemiBold),
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 3,
                modifier = Modifier.padding(top = 4.dp, bottom = 8.dp),
            )
        }
        val scroll = rememberScrollState()
        Column(
            modifier = Modifier
                .weight(1f, fill = false)
                .fillMaxWidth()
                .sheetScrollEdge(scroll)
                .verticalScroll(scroll)
                .padding(start = AboutSheetGutter, end = AboutSheetGutter, bottom = 24.dp)
                .animateContentSize(YoinMotion.defaultSpatialSpec()),
        ) {
            CreditsReceipt(credits, colors)
            AboutParagraph(
                text = about.text,
                regenerating = gemini.albumAbout,
                colors = colors,
                onRegenerate = { onEdit(ScrapEdit.RegenerateAlbumAbout) },
            )
            gemini.albumAsking.forEach { question -> PendingAlbumAsk(question, colors) }
            about.asks.forEach { ask -> AlbumAskCard(ask, colors) }
            AskAlbum(colors = colors, onAsk = { question -> onEdit(ScrapEdit.AskAlbum(question)) })
            Spacer(modifier = Modifier.windowInsetsBottomHeight(WindowInsets.navigationBars))
        }
        snackbar?.let { host ->
            YoinSnackbarHost(
                hostState = host,
                modifier = Modifier
                    .fillMaxWidth()
                    .then(if (host.currentSnackbarData != null) Modifier.navigationBarsPadding() else Modifier),
            )
        }
    }
}

/** The credits one per line, label left and value right, a dashed rule between (Now Playing's receipt). */
@Composable
private fun CreditsReceipt(credits: List<AlbumCreditItem>, colors: ScrapbookColors) {
    val resources = LocalContext.current.resources
    val configuration = LocalConfiguration.current
    val rows = remember(credits, configuration) {
        val labels = resourceAlbumCreditLabels(resources)
        credits.mapNotNull { item -> albumCreditValue(item, labels)?.let { labels.label(item.kind) to it } }
    }
    if (rows.isEmpty()) return
    val rule = colors.aboutMark.copy(alpha = 0.45f)
    Column(modifier = Modifier.padding(top = 8.dp, bottom = 4.dp)) {
        rows.forEachIndexed { index, (label, value) ->
            if (index > 0) {
                Canvas(modifier = Modifier.fillMaxWidth().height(1.dp)) {
                    drawLine(
                        color = rule,
                        start = Offset(0f, size.height / 2),
                        end = Offset(size.width, size.height / 2),
                        strokeWidth = 1.dp.toPx(),
                        pathEffect = PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 4.dp.toPx())),
                    )
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.width(ReceiptLabelWidth),
                )
                Text(
                    text = value,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    textAlign = TextAlign.End,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

@Composable
private fun AboutParagraph(
    text: String?,
    regenerating: Boolean,
    colors: ScrapbookColors,
    onRegenerate: () -> Unit,
) {
    Column(
        modifier = Modifier
            .padding(top = 14.dp)
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(colors.about)
            .padding(16.dp),
    ) {
        AnimatedContent(
            targetState = regenerating || text == null,
            transitionSpec = {
                YoinMotion.fadeIn(role = YoinMotionRole.Standard) togetherWith YoinMotion.fadeOut(role = YoinMotionRole.Standard)
            },
            label = "albumAboutParagraph",
        ) { busy ->
            if (busy) {
                ThinkingDots(color = colors.aboutMark, modifier = Modifier.padding(vertical = 8.dp))
            } else {
                Text(
                    text = text.orEmpty(),
                    style = MaterialTheme.typography.bodyLarge.copy(lineHeight = 1.6.em),
                    color = colors.onAbout,
                )
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Byline(own = false, color = colors.aboutMark)
            Spacer(Modifier.weight(1f))
            FilledTonalButton(
                onClick = onRegenerate,
                enabled = !regenerating,
                colors = ButtonDefaults.filledTonalButtonColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                    contentColor = colors.aboutMark,
                ),
                modifier = Modifier.handPointer(enabled = !regenerating),
            ) {
                Icon(imageVector = YoinSymbols.Sparkle, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
                Text(stringResource(R.string.detail_scrap_regenerate), maxLines = 1)
            }
        }
    }
}

@Composable
private fun AlbumAskCard(ask: AlbumAboutAsk, colors: ScrapbookColors) {
    Column(
        modifier = Modifier
            .padding(top = 14.dp)
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(colors.about)
            .padding(16.dp),
    ) {
        Text(
            text = ask.question,
            style = userTextStyle().copy(fontSize = 13.5.sp),
            color = colors.onAbout.copy(alpha = 0.75f),
        )
        ask.title?.let { title ->
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                color = colors.onAbout,
                modifier = Modifier.padding(top = 6.dp),
            )
        }
        Text(
            text = markdownBoldAnnotatedString(ask.answer),
            style = MaterialTheme.typography.bodyLarge.copy(lineHeight = 1.55.em),
            color = colors.onAbout,
            modifier = Modifier.padding(top = 4.dp),
        )
        Byline(own = false, color = colors.aboutMark, modifier = Modifier.padding(top = 8.dp))
    }
}

@Composable
private fun PendingAlbumAsk(question: String, colors: ScrapbookColors) {
    Column(
        modifier = Modifier
            .padding(top = 14.dp)
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(colors.about)
            .padding(16.dp),
    ) {
        Text(
            text = question,
            style = userTextStyle().copy(fontSize = 13.5.sp),
            color = colors.onAbout.copy(alpha = 0.75f),
        )
        ThinkingDots(color = colors.aboutMark, modifier = Modifier.padding(top = 12.dp, bottom = 4.dp))
        Byline(own = false, color = colors.aboutMark, modifier = Modifier.padding(top = 8.dp))
    }
}

/** The question field and Ask Gemini (the song sheet's new entry, without Write: About here is Gemini's). */
@Composable
private fun AskAlbum(colors: ScrapbookColors, onAsk: (String) -> Unit) {
    val question = rememberTextFieldState()
    var focused by remember { mutableStateOf(false) }
    val card = remember { BringIntoViewRequester() }
    KeepAboveKeyboard(card, active = focused)
    val hasQuestion = question.text.isNotBlank()
    Column(
        modifier = Modifier
            .padding(top = 18.dp)
            .fillMaxWidth()
            .bringIntoViewRequester(card)
            .clip(RoundedCornerShape(24.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        EditField(
            state = question,
            hint = stringResource(R.string.np_ask_placeholder),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.surface,
            modifier = Modifier.onFocusChanged { focused = it.isFocused },
        )
        FilledTonalButton(
            onClick = {
                onAsk(question.text.toString().trim())
                question.clearText()
            },
            enabled = hasQuestion,
            colors = ButtonDefaults.filledTonalButtonColors(containerColor = colors.about, contentColor = colors.onAbout),
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 44.dp)
                .handPointer(enabled = hasQuestion),
        ) {
            Icon(imageVector = YoinSymbols.Sparkle, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
            Text(stringResource(R.string.detail_scrap_ask_gemini), maxLines = 1)
        }
    }
}

private val AboutSheetGutter = 24.dp
private val ReceiptLabelWidth = 88.dp
