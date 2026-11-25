package com.gpo.yoin.ui.detail

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.gpo.yoin.R
import com.gpo.yoin.symbols.YoinSymbols
import com.gpo.yoin.ui.component.ThinkingDots
import com.gpo.yoin.ui.component.dashedOutline
import com.gpo.yoin.ui.component.handPointer
import com.gpo.yoin.ui.component.seamFade
import com.gpo.yoin.ui.theme.YoinContainerShapes

/*
 * The album About on the scrapbook paper (owner + Carbon 2026-11-25): the credits line under the title and
 * the editorial card after the opening. The sheet behind the card is AlbumAboutSheet.kt.
 */

/**
 * The album About card's roles: tertiary, like the Ask cards (51, 2026-11-25: what the listener writes and
 * what is objective or generated are two colours; stickies stay secondary, the review primary).
 */
@Immutable
internal data class AlbumAboutRoles(val container: Color, val content: Color, val mark: Color)

/**
 * The listener's own papers (review, album stickies, track notes): primary (51, 2026-11-25). [AlbumAboutRoles.mark]
 * is their ink, onPrimaryContainer, for time stamps, rails and mention pills on them.
 */
internal fun listenerPaperRoles(scheme: ColorScheme): AlbumAboutRoles =
    AlbumAboutRoles(container = scheme.primaryContainer, content = scheme.onPrimaryContainer, mark = scheme.onPrimaryContainer)

internal fun albumAboutRoles(scheme: ColorScheme): AlbumAboutRoles =
    AlbumAboutRoles(container = scheme.tertiaryContainer, content = scheme.onTertiaryContainer, mark = scheme.tertiary)

/*
 * On the card itself every mark (sparkle, label, Read all, dots) is in the card's own ink, onTertiaryContainer:
 * the tertiary mark is too faint on a cover's tertiaryContainer (device check 2026-11-25, dark theme), the
 * same reason the track sheet's Ask cards ink their pen and byline in their own content colour.
 */

/** Lines of the paragraph the paper shows; the rest is a tap away (owner: 3–4, so the page doesn't grow). */
internal const val AlbumAboutCardLines = 4

/** The credits line ([albumCreditsLine]); nothing at all when no item is known. */
@Composable
internal fun AlbumCreditsLine(items: List<AlbumCreditItem>, colors: ScrapbookColors, modifier: Modifier = Modifier) {
    val resources = LocalContext.current.resources
    val configuration = LocalConfiguration.current
    val line = remember(items, configuration) { albumCreditsLine(items, resourceAlbumCreditLabels(resources)) }
        ?: return
    Text(
        text = line,
        style = MaterialTheme.typography.bodyMedium.copy(fontSize = 13.5.sp, lineHeight = 1.5.em, letterSpacing = 0.sp),
        color = colors.muted,
        modifier = modifier.widthIn(max = AlbumAboutCardMaxWidth).seamFade(),
    )
}

/**
 * The paragraph on a secondary-role card, at most [AlbumAboutCardLines] lines with "Read all" when it runs
 * on; Gemini's dots while it is first written. A tap opens [AlbumAboutSheet].
 */
@Composable
internal fun AlbumAboutCard(
    piece: ScrapPiece.AlbumAbout,
    albumName: String,
    colors: ScrapbookColors,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val text = piece.text
    if (text == null && !piece.pending) {
        EmptyAboutMark(colors = colors, onClick = onClick, modifier = modifier)
        return
    }
    val description = stringResource(R.string.detail_scrap_cd_about, albumName, text.orEmpty())
    val readAll = stringResource(R.string.detail_scrap_cd_read_all)
    var overflowing by remember(text) { mutableStateOf(false) }
    Column(
        modifier = modifier
            // The masonry hands a full-line piece the whole row: let go of that minimum first.
            .wrapContentWidth(Alignment.Start)
            .widthIn(max = AlbumAboutCardMaxWidth)
            .scrapLayer(piece.tilt, depth = 0.35f)
            .seamFade()
            .clip(YoinContainerShapes.Card)
            .background(colors.about)
            .semantics(mergeDescendants = true) { contentDescription = description }
            .handPointer(enabled = text != null)
            .clickable(enabled = text != null, role = Role.Button, onClickLabel = readAll, onClick = onClick)
            .padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 14.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            // The sparkle marks generated text; Apple's own notes go without it.
            if (!piece.fromService) {
                Icon(
                    imageVector = YoinSymbols.Sparkle,
                    contentDescription = null,
                    tint = colors.onAbout,
                    modifier = Modifier.size(14.dp),
                )
                Spacer(Modifier.width(6.dp))
            }
            Text(
                text = stringResource(R.string.detail_scrap_about),
                style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold, letterSpacing = 0.2.sp),
                color = colors.onAbout,
            )
            if (piece.fromService) {
                // Apple Music's own notes say whose they are, on the paper too.
                Spacer(Modifier.width(10.dp))
                Text(
                    text = stringResource(R.string.settings_service_name_apple),
                    style = MaterialTheme.typography.labelMedium.copy(letterSpacing = 0.2.sp),
                    color = colors.onAbout.copy(alpha = 0.8f),
                )
            }
        }
        if (text == null) {
            ThinkingDots(color = colors.onAbout, modifier = Modifier.padding(top = 12.dp, bottom = 4.dp))
        } else {
            Text(
                text = text,
                style = MaterialTheme.typography.bodyMedium.copy(fontSize = 14.5.sp, lineHeight = 1.55.em),
                color = colors.onAbout,
                maxLines = AlbumAboutCardLines,
                overflow = TextOverflow.Ellipsis,
                onTextLayout = { overflowing = it.hasVisualOverflow },
                modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
            )
            if (overflowing || piece.asks.isNotEmpty()) {
                Text(
                    text = stringResource(R.string.detail_scrap_read_all),
                    style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold, letterSpacing = 0.sp),
                    color = colors.onAbout,
                    modifier = Modifier.padding(top = 6.dp),
                )
            }
        }
    }
}

/**
 * The album's About was asked for and came back empty: no card, only a 48dp dashed circle with the ✦ (the
 * blank review's pattern, in the generated text's tertiary). It opens the About sheet, where the AI can be
 * asked again or asked a question. No words of its own: TalkBack reads "About".
 */
@Composable
private fun EmptyAboutMark(colors: ScrapbookColors, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val about = stringResource(R.string.detail_scrap_about)
    Box(modifier = modifier, contentAlignment = Alignment.CenterStart) {
        Box(
            modifier = Modifier
                .seamFade()
                .size(48.dp)
                .clip(CircleShape)
                .dashedOutline(CircleShape, color = { colors.aboutMark }, width = 1.5.dp, gap = 5.dp)
                .handPointer()
                .clickable(role = Role.Button, onClick = onClick)
                .semantics { contentDescription = about },
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = YoinSymbols.Sparkle,
                contentDescription = null,
                tint = colors.aboutMark,
                modifier = Modifier.size(20.dp),
            )
        }
    }
}

/** A card as wide as a reading column, never the whole tablet page. */
private val AlbumAboutCardMaxWidth = 560.dp
