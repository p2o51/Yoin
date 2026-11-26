package com.gpo.yoin.ui.nowplaying

import android.content.res.Configuration
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.request.allowHardware
import com.gpo.yoin.R
import com.gpo.yoin.shared.share.YoinShareCards
import com.gpo.yoin.symbols.YoinSymbols
import com.gpo.yoin.ui.component.LocalSoftwareBitmaps
import com.gpo.yoin.ui.component.NoteMarkupText
import com.gpo.yoin.ui.theme.GoogleSansFlex
import com.gpo.yoin.ui.theme.GoogleSansFlexRounded
import com.gpo.yoin.ui.theme.YoinArtworkShapes
import com.gpo.yoin.ui.theme.YoinContainerShapes
import com.gpo.yoin.ui.theme.YoinMotion
import com.gpo.yoin.ui.theme.YoinMotionRole
import com.gpo.yoin.ui.theme.YoinTheme
import com.gpo.yoin.ui.theme.yoinFont
import java.util.Locale
import kotlin.math.PI
import kotlin.math.sin

/*
 * The share images (owner 2026-11-25; prototypes design/hifi/src/components/share.js, measurements from
 * there). Every image is laid out at its logical size — Card 360dp wide (exported 1440px, height following
 * the content), Story 360 × 640dp (exported 1080 × 1920) — at font scale 1, and exported as a full
 * rectangle: the Card is its own colour to every edge, square-cornered; a Story's backdrop fills the
 * corners around its card, or the layout itself is the poster. Same family as the lyrics card: 24 padding,
 * the 44 cover + title / artist head, the Yoin mark at the foot, GSF rounded SemiBold for hero text.
 */

/** How a layout's body is framed: the Card format, a card on a Story's backdrop, or a whole Story poster. */
internal enum class ShareFrame { Card, StoryCard, Poster }

/**
 * One share image at its logical size in [format] and [tone]. With an [exportLayer] the content draws
 * through it at the format's fixed export size (what the sheet saves and shares); [modifier] — a
 * preview's fit or rounding — stays outside the recording.
 */
@Composable
internal fun ShareImage(
    content: ShareContent,
    layout: ShareLayout,
    format: LyricsShareFormat,
    tone: ShareTone,
    modifier: Modifier = Modifier,
    exportLayer: GraphicsLayer? = null,
) {
    val density = LocalDensity.current
    CompositionLocalProvider(
        LocalDensity provides Density(density.density, fontScale = 1f),
        // Covers (the song's, a mention's) must be software bitmaps: the image is read back from a layer.
        LocalSoftwareBitmaps provides true,
    ) {
        val logicalSize = when (format) {
            LyricsShareFormat.Card -> Modifier.width(LyricsCardDesignWidth)
            LyricsShareFormat.Story -> Modifier.size(LyricsStoryDesignSize)
        }
        Box(
            modifier = modifier
                .then(logicalSize)
                .testTag(ShareImageTag)
                .then(if (exportLayer != null) Modifier.recordForExport(exportLayer, format) else Modifier),
        ) {
            // A tone change cross-fades the whole image (colour is an effect).
            AnimatedContent(
                targetState = tone,
                transitionSpec = {
                    YoinMotion.fadeIn(role = YoinMotionRole.Standard) togetherWith
                        YoinMotion.fadeOut(role = YoinMotionRole.Standard)
                },
                label = "shareTone",
            ) { shownTone ->
                val colors = shownTone.colors(MaterialTheme.colorScheme, layout.tab.role)
                when (format) {
                    LyricsShareFormat.Card -> ShareBody(content, layout, colors, ShareFrame.Card, Modifier.fillMaxWidth())
                    LyricsShareFormat.Story -> ShareStory(content, layout, colors)
                }
            }
        }
    }
}

/** The 9:16 story: a poster edge to edge, or the card centred on the tone's backdrop, zoomed to fit. */
@Composable
private fun ShareStory(content: ShareContent, layout: ShareLayout, colors: ShareCardColors) {
    if (layout.storyIsPoster) {
        val paper = layout == ShareLayout.NoteClipping
        val ground = if (paper) MaterialTheme.colorScheme.surface else colors.card
        val dots = MaterialTheme.colorScheme.outlineVariant
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(ground)
                .then(if (paper) Modifier.dotPaper(dots) else Modifier)
                .padding(horizontal = StoryHorizontalMargin, vertical = StoryVerticalMargin),
            contentAlignment = Alignment.Center,
        ) {
            // A long note or pick zooms out rather than ever losing a line; the poster's foot sits at the bottom.
            ZoomToFit(maxZoom = YoinShareCards.storyMaxZoom(layout), fillHeight = true) {
                ShareBody(content, layout, colors, ShareFrame.Poster, Modifier)
            }
        }
    } else {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(colors.backdrop)
                .padding(horizontal = StoryHorizontalMargin, vertical = StoryVerticalMargin),
            contentAlignment = Alignment.Center,
        ) {
            ZoomToFit(maxZoom = YoinShareCards.storyMaxZoom(layout)) {
                ShareBody(content, layout, colors, ShareFrame.StoryCard, Modifier)
            }
        }
    }
}

@Composable
private fun ShareBody(
    content: ShareContent,
    layout: ShareLayout,
    colors: ShareCardColors,
    frame: ShareFrame,
    modifier: Modifier,
) {
    when (layout) {
        ShareLayout.LyricsClassic -> {
            val pick = content.lyrics ?: return
            LyricsShareCard(
                lines = pick.lines,
                passageStarts = pick.passageStarts,
                showTranslation = pick.showTranslation,
                songTitle = content.song.title,
                artist = content.song.artist,
                coverArtUrl = content.song.coverArtUrl,
                colors = colors,
                shape = if (frame == ShareFrame.StoryCard) YoinContainerShapes.Panel else RectangleShape,
                modifier = modifier,
            )
        }
        ShareLayout.LyricsPoster -> LyricsPosterBody(content, colors, frame, modifier)
        ShareLayout.LyricsPlayer -> LyricsPlayerBody(content, colors, frame, modifier)
        ShareLayout.LyricsVertical -> LyricsVerticalBody(content, colors, frame, modifier)
        ShareLayout.AboutReceipt -> AboutReceiptBody(content, colors, frame, modifier)
        ShareLayout.AboutQuote -> AboutQuoteBody(content, colors, frame, modifier)
        ShareLayout.AboutLiner -> AboutLinerBody(content, colors, frame, modifier)
        ShareLayout.NoteBig -> NoteBigBody(content, colors, frame, modifier)
        ShareLayout.NoteClipping -> NoteClippingBody(content, colors, frame, modifier)
        ShareLayout.NoteTimeline -> NoteTimelineBody(content, colors, frame, modifier)
    }
}

/** The body's own ground: the card colour edge to edge (Card), a Panel-rounded card (Story), none (poster). */
private fun Modifier.frameGround(frame: ShareFrame, card: Color, padding: Dp = CardPadding): Modifier = when (frame) {
    ShareFrame.Card -> background(card).padding(padding)
    ShareFrame.StoryCard -> clip(YoinContainerShapes.Panel).background(card).padding(padding)
    ShareFrame.Poster -> this
}

// ---------------------------------------------------------------------------
// Shared pieces.
// ---------------------------------------------------------------------------

/** A cover as a software bitmap (the image is read back from a layer); a quiet tile without one. */
@Composable
private fun ShareCover(url: String?, size: Dp, tint: Color, modifier: Modifier = Modifier, shape: Shape = YoinArtworkShapes.Thumb) {
    val context = LocalContext.current
    if (url == null) {
        Box(modifier.size(size).clip(shape).background(tint.copy(alpha = 0.16f)))
        return
    }
    AsyncImage(
        model = remember(url) { ImageRequest.Builder(context).data(url).allowHardware(false).build() },
        contentDescription = null,
        contentScale = ContentScale.Crop,
        modifier = modifier.size(size).clip(shape),
    )
}

/** Cover + title + artist, as on the lyrics card. */
@Composable
private fun ShareHead(song: ShareSong, colors: ShareCardColors, coverSize: Dp = 44.dp, trailing: String? = null) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        ShareCover(song.coverArtUrl, coverSize, colors.content)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = song.title,
                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                color = colors.content,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            val by = listOfNotNull(song.artist.takeIf(String::isNotBlank), trailing).joinToString(" · ")
            if (by.isNotEmpty()) {
                Text(
                    text = by,
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.secondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/** The app's monochrome mark, scaled to fill its slot (see [MonochromeGlyphScale]). */
@Composable
private fun ShareMark(tint: Color, size: Dp = 22.dp) {
    Icon(
        painter = painterResource(R.drawable.ic_yoin_launcher_monochrome),
        contentDescription = null,
        tint = tint,
        modifier = Modifier
            .size(size)
            .graphicsLayer {
                scaleX = MonochromeGlyphScale
                scaleY = MonochromeGlyphScale
            },
    )
}

/** The mark + "Yoin", then, at the end, who wrote the words ("由 Gemini 整理", "写于 …"). */
@Composable
private fun ShareFoot(colors: ShareCardColors, sign: (@Composable () -> Unit)? = null, ink: Color = colors.content) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        ShareMark(ink)
        Spacer(Modifier.width(6.dp))
        Text(
            text = stringResource(R.string.np_share_brand),
            style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold),
            color = ink,
        )
        if (sign == null) {
            Spacer(Modifier.weight(1f))
        } else {
            // The sign keeps its distance from the mark and wraps (right-aligned) rather than ever being cut:
            // a story zoomed in lays the card out narrower.
            Spacer(Modifier.width(SignGap))
            Box(Modifier.weight(1f).testTag(ShareSignTag), contentAlignment = Alignment.CenterEnd) { sign() }
        }
    }
}

/** About's sign when the image carries AI words; none when it is the service's credits alone. */
private fun aiSignOrNull(content: ShareContent, colors: ShareCardColors): (@Composable () -> Unit)? =
    if (content.about?.aiContent == true) ({ AiSign(content, colors) }) else null

/** "✦ 由 Gemini 整理": the active AI service signs About's words. */
@Composable
private fun AiSign(content: ShareContent, colors: ShareCardColors) {
    val name = stringResource(shareAiProviderName(content.aiProvider))
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(YoinSymbols.Sparkle, contentDescription = null, tint = colors.secondary, modifier = Modifier.size(14.dp))
        Spacer(Modifier.width(4.dp))
        Text(
            text = stringResource(R.string.np_share_ai_sign, name),
            style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Normal),
            color = colors.secondary,
            textAlign = TextAlign.End,
        )
    }
}

/** "写于 2026年7月18日": the note's date signs the listener's words. */
@Composable
private fun NoteSign(note: NoteSharePick, color: Color) {
    val locale = LocalConfiguration.current.locales[0]
    Text(
        text = stringResource(R.string.np_share_note_written, shareNoteDate(note.createdAt, locale)),
        style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Normal),
        color = color,
        textAlign = TextAlign.End,
    )
}

/** ▶ 3:46 (and an optional end, quieter) on a 12% pill of the ink. */
@Composable
private fun ShareTimeChip(text: String, colors: ShareCardColors, modifier: Modifier = Modifier, end: String? = null) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .height(26.dp)
            .clip(RoundedCornerShape(13.dp))
            .background(colors.content.copy(alpha = 0.12f))
            .padding(start = 8.dp, end = 10.dp),
    ) {
        Icon(YoinSymbols.PlayFilled, contentDescription = null, tint = colors.content, modifier = Modifier.size(12.dp))
        Spacer(Modifier.width(4.dp))
        Text(
            text = text,
            style = TextStyle(fontFamily = GoogleSansFlex, fontSize = 13.sp, fontWeight = FontWeight.SemiBold),
            color = colors.content,
            maxLines = 1,
            softWrap = false,
        )
        if (end != null) {
            Text(
                text = " – $end", // i18n-allow: a range separator, not words
                style = TextStyle(fontFamily = GoogleSansFlex, fontSize = 13.sp, fontWeight = FontWeight.Medium),
                color = colors.content.copy(alpha = 0.6f),
                maxLines = 1,
                softWrap = false,
            )
        }
    }
}

/** The note's Markdown in [style]: mentions as pills of the ink with their covers, no touch. */
@Composable
private fun ShareNoteText(note: NoteSharePick, style: TextStyle, color: Color, modifier: Modifier = Modifier) {
    NoteMarkupText(
        text = note.content,
        style = style,
        color = color,
        mentionColor = color,
        interactive = false,
        modifier = modifier,
    )
}

private fun lyricHeroStyle(size: Float, lineHeight: Float = 1.3f) = TextStyle(
    fontFamily = GoogleSansFlexRounded,
    fontWeight = FontWeight.SemiBold,
    fontSize = size.sp,
    lineHeight = lineHeight.em,
)

// ---------------------------------------------------------------------------
// Lyrics: Poster, Player, Vertical (Classic is LyricsShareCard).
// ---------------------------------------------------------------------------

@Composable
private fun LyricsPosterBody(content: ShareContent, colors: ShareCardColors, frame: ShareFrame, modifier: Modifier) {
    val pick = content.lyrics ?: return
    val poster = posterLines(pick) ?: return
    val story = frame == ShareFrame.Poster
    val lead = posterLeadSizeSp(poster.lead.text) + if (story) YoinShareCards.posterStoryLeadBoostSp else 0f
    Column(
        verticalArrangement = Arrangement.SpaceBetween,
        modifier = modifier
            .frameGround(frame, colors.card, PaddingValuesPoster)
            .then(if (frame == ShareFrame.Card) Modifier.heightIn(min = PosterCardMinHeight) else Modifier),
    ) {
        Column {
            Text(
                text = "“",
                style = TextStyle(fontFamily = FontFamily.Serif, fontSize = 72.sp, lineHeight = 56.sp, fontWeight = FontWeight.Bold),
                color = colors.content.copy(alpha = 0.35f),
                modifier = Modifier.height(44.dp),
            )
            Text(
                text = poster.lead.text,
                style = lyricHeroStyle(lead, 1.18f),
                color = colors.content,
                modifier = Modifier.padding(top = 8.dp),
            )
            poster.rest.forEachIndexed { i, line ->
                Text(
                    text = line.text,
                    style = lyricHeroStyle(if (story) 20f else 18f, 1.35f).copy(fontWeight = FontWeight.Medium),
                    color = colors.content.copy(alpha = 0.82f),
                    modifier = Modifier.padding(top = if (i == 0) 16.dp else 4.dp),
                )
            }
        }
        Column(modifier = Modifier.padding(top = 28.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                ShareCover(content.song.coverArtUrl, 44.dp, colors.content)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        text = content.song.title,
                        style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                        color = colors.content,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    val at = poster.lead.startMs?.let(::shareTime)
                    Text(
                        text = listOfNotNull(content.song.artist.takeIf(String::isNotBlank), at).joinToString(" · "),
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.secondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                ShareMark(colors.content)
            }
            // The translation: one small, quiet line at the very bottom (owner 2026-11-25).
            poster.translation?.let { translation ->
                Text(
                    text = translation,
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.content.copy(alpha = QuietAlpha),
                    modifier = Modifier.padding(top = 14.dp),
                )
            }
        }
    }
}

@Composable
private fun LyricsPlayerBody(content: ShareContent, colors: ShareCardColors, frame: ShareFrame, modifier: Modifier) {
    val pick = content.lyrics ?: return
    val excerpt = playerExcerpt(pick, content.song.durationMs)
    val lineStyle = lyricsCardLineStyle(pick.lines)
    Column(modifier.frameGround(frame, colors.card)) {
        ShareHead(content.song, colors)
        Spacer(Modifier.height(20.dp))
        // The lines alone — the per-line times are gone (owner 2026-11-25); the bar says where they are.
        pick.lines.forEachIndexed { i, line ->
            Column(Modifier.padding(top = if (i == 0) 0.dp else 6.dp)) {
                Text(text = line.text, style = lineStyle, color = colors.content)
                val translation = line.translation
                if (pick.showTranslation && !translation.isNullOrBlank()) {
                    Text(
                        text = translation,
                        style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium),
                        color = colors.content.copy(alpha = CardTranslationAlpha),
                        modifier = Modifier.padding(top = 2.dp),
                    )
                }
            }
        }
        if (excerpt != null) {
            Spacer(Modifier.height(18.dp))
            PlayerBar(excerpt, content.song.durationMs, colors)
        }
        Spacer(Modifier.height(24.dp))
        ShareFoot(colors)
    }
}

/** The track line, the excerpt as a solid bar (≥ 6% of it), its time range in a chip above, 0:00 / length. */
@Composable
private fun PlayerBar(excerpt: PlayerExcerpt, durationMs: Long, colors: ShareCardColors) {
    Column(Modifier.fillMaxWidth()) {
        Box(Modifier.fillMaxWidth().height(34.dp)) {
            ShareTimeChip(
                text = shareTime(excerpt.startMs),
                end = shareTime(excerpt.endMs),
                colors = colors,
                modifier = Modifier.centredAt((excerpt.start + excerpt.end) / 2f),
            )
        }
        Canvas(Modifier.fillMaxWidth().height(8.dp)) {
            val track = 4.dp.toPx()
            drawRoundRect(
                color = colors.content.copy(alpha = 0.24f),
                topLeft = Offset(0f, (size.height - track) / 2f),
                size = Size(size.width, track),
                cornerRadius = CornerRadius(track / 2f),
            )
            drawRoundRect(
                color = colors.content,
                topLeft = Offset(size.width * excerpt.start, 0f),
                size = Size(size.width * (excerpt.end - excerpt.start), size.height),
                cornerRadius = CornerRadius(size.height / 2f),
            )
        }
        Row(Modifier.fillMaxWidth().padding(top = 6.dp)) {
            Text(shareTime(0L), style = EndsStyle, color = colors.content.copy(alpha = 0.6f))
            Spacer(Modifier.weight(1f))
            Text(shareTime(durationMs), style = EndsStyle, color = colors.content.copy(alpha = 0.6f))
        }
    }
}

/** Places a child so its centre sits at [fraction] of the parent's width, kept inside it. */
private fun Modifier.centredAt(fraction: Float): Modifier = layout { measurable, constraints ->
    val placeable = measurable.measure(constraints.copy(minWidth = 0))
    val width = constraints.maxWidth
    layout(width, placeable.height) {
        val x = (width * fraction - placeable.width / 2f).toInt().coerceIn(0, (width - placeable.width).coerceAtLeast(0))
        placeable.place(x, 0)
    }
}

@Composable
private fun LyricsVerticalBody(content: ShareContent, colors: ShareCardColors, frame: ShareFrame, modifier: Modifier) {
    val pick = content.lyrics ?: return
    val shared = remember(pick) { pick.toShared() }
    val translations = YoinShareCards.verticalGist(shared)
    val plan = remember(shared) { YoinShareCards.verticalLyricsPlan(shared) }
    Column(
        verticalArrangement = Arrangement.SpaceBetween,
        modifier = modifier
            .frameGround(frame, colors.card, PaddingValuesPoster)
            .then(if (frame == ShareFrame.Card) Modifier.heightIn(min = VerticalCardMinHeight) else Modifier),
    ) {
        Row(Modifier.fillMaxWidth()) {
            // 「歌词大意：……」 — the translation as a footnote, the far left column, small and quiet.
            if (translations.isNotEmpty()) {
                val gist = translations.joinToString(YoinShareCards.verticalGistSeparator)
                val note = stringResource(R.string.np_share_lyrics_gist, gist)
                VerticalText(
                    text = note,
                    sizeSp = 11f,
                    color = colors.content.copy(alpha = QuietAlpha),
                    columnHeightDp = VerticalColumnHeight,
                    weight = FontWeight.Normal,
                )
                Spacer(Modifier.width(10.dp))
            }
            // Read right to left: the title, then the artist to its left.
            if (content.song.artist.isNotBlank()) {
                VerticalText(
                    text = content.song.artist,
                    sizeSp = 12f,
                    color = colors.secondary,
                    columnHeightDp = VerticalColumnHeight,
                    weight = FontWeight.Normal,
                )
                Spacer(Modifier.width(4.dp))
            }
            VerticalText(
                text = content.song.title,
                sizeSp = 14f,
                color = colors.content,
                columnHeightDp = VerticalColumnHeight,
                weight = FontWeight.SemiBold,
            )
            Spacer(Modifier.weight(1f))
            // Right to left: the first line is the rightmost column.
            Row(horizontalArrangement = Arrangement.spacedBy((plan.fontSizeSp * (VerticalColumnAdvance - 1f)).dp)) {
                plan.columns.asReversed().forEach { column ->
                    VerticalColumn(column, plan.fontSizeSp, colors.content, FontWeight.SemiBold, rounded = true)
                }
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 20.dp)) {
            ShareCover(content.song.coverArtUrl, 36.dp, colors.content)
            Spacer(Modifier.weight(1f))
            ShareMark(colors.content)
            Spacer(Modifier.width(6.dp))
            Text(
                text = stringResource(R.string.np_share_brand),
                style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold),
                color = colors.content,
            )
        }
    }
}

/** Short text set vertically: CJK stacked character by character, anything else turned on its side. */
@Composable
private fun VerticalText(text: String, sizeSp: Float, color: Color, columnHeightDp: Float, weight: FontWeight) {
    val columns = YoinShareCards.verticalTextColumns(text, sizeSp, columnHeightDp)
    if (columns != null) {
        Row(horizontalArrangement = Arrangement.spacedBy((sizeSp * 0.3f).dp)) {
            columns.asReversed().forEach { column ->
                VerticalColumn(column, sizeSp, color, weight, rounded = false)
            }
        }
    } else {
        Text(
            text = text,
            style = TextStyle(fontFamily = GoogleSansFlex, fontSize = sizeSp.sp, fontWeight = weight),
            color = color,
            modifier = Modifier.sideways(columnHeightDp.dp),
        )
    }
}

/** One column of characters, top to bottom, CJK punctuation in its vertical form. */
@Composable
private fun VerticalColumn(text: String, sizeSp: Float, color: Color, weight: FontWeight, rounded: Boolean) {
    val style = TextStyle(
        fontFamily = if (rounded) GoogleSansFlexRounded else GoogleSansFlex,
        fontWeight = weight,
        fontSize = sizeSp.sp,
        lineHeight = (sizeSp * VerticalCharAdvance).sp,
        textAlign = TextAlign.Center,
    )
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.width((sizeSp * 1.1f).dp)) {
        text.forEach { c ->
            Text(text = verticalForm(c).toString(), style = style, color = color, maxLines = 1, softWrap = false)
        }
    }
}

/** Lays a line of text on its side (reading top to bottom), [maxLength] long at most. */
private fun Modifier.sideways(maxLength: Dp): Modifier = layout { measurable, _ ->
    val placeable = measurable.measure(Constraints(maxWidth = maxLength.roundToPx()))
    layout(placeable.height, placeable.width) {
        placeable.placeWithLayer(
            x = -(placeable.width - placeable.height) / 2,
            y = (placeable.width - placeable.height) / 2,
        ) { rotationZ = 90f }
    }
}

// ---------------------------------------------------------------------------
// About: Receipt, Quote, Liner notes (tertiary).
// ---------------------------------------------------------------------------

@Composable
private fun AboutReceiptBody(content: ShareContent, colors: ShareCardColors, frame: ShareFrame, modifier: Modifier) {
    val about = content.about ?: return
    Column(modifier.frameGround(frame, colors.card)) {
        ShareHead(content.song, colors)
        if (about.credits.isNotEmpty()) {
            Spacer(Modifier.height(12.dp))
            about.credits.forEachIndexed { i, credit ->
                if (i > 0) DashedRule(color = colors.content.copy(alpha = 0.3f))
                Row(verticalAlignment = Alignment.Top, modifier = Modifier.padding(vertical = 8.dp)) {
                    Text(
                        text = credit.label,
                        style = MaterialTheme.typography.labelMedium,
                        color = colors.secondary,
                        modifier = Modifier.width(88.dp).padding(top = 2.dp, end = 12.dp),
                    )
                    Text(
                        text = credit.value,
                        style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium),
                        color = colors.content,
                        textAlign = TextAlign.End,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
        about.paragraph?.let { paragraph ->
            Text(
                text = about.heading,
                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                color = colors.content,
                modifier = Modifier.padding(top = 16.dp),
            )
            Text(
                text = paragraph,
                style = MaterialTheme.typography.bodyMedium.copy(lineHeight = 22.sp),
                color = colors.content,
                modifier = Modifier.padding(top = 6.dp),
            )
        }
        Spacer(Modifier.height(24.dp))
        ShareFoot(colors, sign = aiSignOrNull(content, colors))
    }
}

@Composable
private fun AboutQuoteBody(content: ShareContent, colors: ShareCardColors, frame: ShareFrame, modifier: Modifier) {
    val about = content.about ?: return
    Column(modifier.frameGround(frame, colors.card)) {
        // The tag: ✦ + the paragraph's heading, inverted.
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .height(28.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(colors.content)
                .padding(start = 10.dp, end = 12.dp),
        ) {
            Icon(YoinSymbols.Sparkle, contentDescription = null, tint = colors.card, modifier = Modifier.size(14.dp))
            Spacer(Modifier.width(5.dp))
            Text(
                text = about.heading,
                style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold),
                color = colors.card,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        about.paragraph?.let { paragraph ->
            Text(
                text = paragraph,
                style = lyricHeroStyle(19f, 1.5f),
                color = colors.content,
                modifier = Modifier.padding(top = 18.dp),
            )
        }
        if (about.credits.isNotEmpty()) {
            Row(Modifier.padding(top = 18.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                about.credits.take(YoinShareCards.quoteCredits).forEach { credit ->
                    CreditChip(credit, colors, Modifier.weight(1f, fill = false))
                }
            }
        }
        Box(
            Modifier
                .padding(top = 20.dp, bottom = 16.dp)
                .fillMaxWidth()
                .height(1.dp)
                .background(colors.content.copy(alpha = 0.18f)),
        )
        ShareHead(content.song, colors, coverSize = 32.dp)
        Spacer(Modifier.height(24.dp))
        ShareFoot(colors, sign = aiSignOrNull(content, colors))
    }
}

@Composable
private fun CreditChip(credit: ShareCredit, colors: ShareCardColors, modifier: Modifier = Modifier) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .background(colors.content.copy(alpha = 0.1f))
            .padding(horizontal = 10.dp, vertical = 5.dp),
    ) {
        Text(credit.label, style = MaterialTheme.typography.labelSmall, color = colors.secondary, maxLines = 1)
        Spacer(Modifier.width(6.dp))
        Text(
            text = credit.value,
            style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold),
            color = colors.content,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun AboutLinerBody(content: ShareContent, colors: ShareCardColors, frame: ShareFrame, modifier: Modifier) {
    val about = content.about ?: return
    Column(
        verticalArrangement = Arrangement.SpaceBetween,
        modifier = modifier.frameGround(frame, colors.card),
    ) {
        Column {
            Row(verticalAlignment = Alignment.Top) {
                Text(
                    text = about.heading.uppercase(),
                    style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold, letterSpacing = 1.6.sp),
                    color = colors.secondary,
                    modifier = Modifier.weight(1f).padding(top = 2.dp),
                )
                ShareCover(content.song.coverArtUrl, 72.dp, colors.content, shape = RoundedCornerShape(8.dp))
            }
            Text(
                text = content.song.title,
                style = lyricHeroStyle(36f, 1.12f),
                color = colors.content,
                modifier = Modifier.padding(top = 8.dp),
            )
            if (content.song.artist.isNotBlank()) {
                Text(
                    text = content.song.artist,
                    style = MaterialTheme.typography.bodyMedium,
                    color = colors.secondary,
                    modifier = Modifier.padding(top = 6.dp),
                )
            }
            val cells = about.credits.take(YoinShareCards.linerCredits)
            if (cells.isNotEmpty()) {
                Column(Modifier.padding(top = 20.dp)) {
                    cells.chunked(2).forEach { pair ->
                        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                            pair.forEach { credit -> LinerCell(credit, colors, Modifier.weight(1f)) }
                            if (pair.size == 1) Spacer(Modifier.weight(1f))
                        }
                    }
                }
            }
            about.paragraph?.let { paragraph ->
                Text(
                    text = paragraph,
                    style = MaterialTheme.typography.bodyMedium.copy(lineHeight = 22.sp),
                    color = colors.content,
                    modifier = Modifier.padding(top = 12.dp),
                )
            }
        }
        Box(Modifier.padding(top = 24.dp)) { ShareFoot(colors, sign = aiSignOrNull(content, colors)) }
    }
}

@Composable
private fun LinerCell(credit: ShareCredit, colors: ShareCardColors, modifier: Modifier) {
    Column(
        modifier = modifier
            .drawBehind {
                drawLine(colors.content.copy(alpha = 0.22f), Offset.Zero, Offset(size.width, 0f), strokeWidth = 1.dp.toPx())
            }
            .padding(top = 10.dp, bottom = 12.dp),
    ) {
        Text(credit.label, style = MaterialTheme.typography.labelSmall, color = colors.secondary)
        Text(
            text = credit.value,
            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
            color = colors.content,
            modifier = Modifier.padding(top = 2.dp),
        )
    }
}

// ---------------------------------------------------------------------------
// Note: Big type, Clipping, Timeline (primary).
// ---------------------------------------------------------------------------

@Composable
private fun NoteBigBody(content: ShareContent, colors: ShareCardColors, frame: ShareFrame, modifier: Modifier) {
    val note = content.note ?: return
    Column(modifier.frameGround(frame, colors.card)) {
        ShareHead(content.song, colors)
        note.positionMs?.let { at ->
            ShareTimeChip(shareTime(at), colors, Modifier.padding(top = 20.dp))
        }
        ShareNoteText(
            note = note,
            // Lighter than a lyric line, so the note's own **bold** reads.
            style = TextStyle(fontFamily = GoogleSansFlex, fontSize = 21.sp, lineHeight = 1.38.em, fontWeight = FontWeight.Normal),
            color = colors.content,
            modifier = Modifier.padding(top = if (note.positionMs != null) 12.dp else 20.dp),
        )
        Spacer(Modifier.height(24.dp))
        ShareFoot(colors, sign = { NoteSign(note, colors.secondary) })
    }
}

@Composable
private fun NoteClippingBody(content: ShareContent, colors: ShareCardColors, frame: ShareFrame, modifier: Modifier) {
    val note = content.note ?: return
    val scheme = MaterialTheme.colorScheme
    val story = frame == ShareFrame.Poster
    val ground = when (frame) {
        // The paper is the image's ground; the tone colours the clipping.
        ShareFrame.Card -> Modifier
            .background(scheme.surface)
            .dotPaper(scheme.outlineVariant)
            .padding(start = 24.dp, end = 24.dp, top = 28.dp, bottom = 24.dp)
        ShareFrame.StoryCard -> Modifier
            .clip(YoinContainerShapes.Panel)
            .background(scheme.surface)
            .dotPaper(scheme.outlineVariant)
            .padding(24.dp)
        ShareFrame.Poster -> Modifier
    }
    Column(verticalArrangement = Arrangement.SpaceBetween, modifier = modifier.then(ground)) {
        Column {
            Row(verticalAlignment = Alignment.Bottom) {
                ShareCover(
                    url = content.song.coverArtUrl,
                    size = if (story) 136.dp else 104.dp,
                    tint = scheme.onSurface,
                    shape = RoundedCornerShape(6.dp),
                    modifier = Modifier.graphicsLayer { rotationZ = -3f },
                )
                TicketStub(content.song, note, story)
            }
            Column(
                modifier = Modifier
                    .padding(top = if (story) 18.dp else 14.dp)
                    .graphicsLayer { rotationZ = 1f }
                    .clip(RoundedCornerShape(16.dp))
                    .background(colors.card)
                    .padding(start = 18.dp, end = 18.dp, top = 16.dp, bottom = 14.dp),
            ) {
                ShareNoteText(
                    note = note,
                    style = TextStyle(
                        fontFamily = GoogleSansFlex,
                        fontSize = if (story) 18.sp else 16.sp,
                        lineHeight = 1.6.em,
                        fontWeight = FontWeight.Medium,
                    ),
                    color = colors.content,
                )
                val locale = LocalConfiguration.current.locales[0]
                Text(
                    text = shareNoteDate(note.createdAt, locale),
                    style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Normal),
                    color = colors.secondary,
                    textAlign = TextAlign.End,
                    modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                )
            }
        }
        Box(Modifier.padding(top = 24.dp)) { ShareFoot(colors, ink = scheme.onSurface) }
    }
}

/**
 * The ticket stub beside the clipping's cover: title > artist > time (owner 2026-11-25) — the title larger,
 * the time last, lighter, narrower and muted.
 */
@Composable
private fun TicketStub(song: ShareSong, note: NoteSharePick, story: Boolean) {
    val scheme = MaterialTheme.colorScheme
    Column(
        modifier = Modifier
            .offset(x = (-10).dp)
            .padding(bottom = 6.dp)
            .graphicsLayer { rotationZ = 2f }
            .widthIn(max = 190.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(scheme.surfaceContainerHigh)
            .padding(start = 16.dp, end = 14.dp, top = 9.dp, bottom = 9.dp),
    ) {
        Text(
            text = song.title,
            style = MaterialTheme.typography.titleMedium.copy(
                fontSize = if (story) 20.sp else 17.sp,
                lineHeight = if (story) 26.sp else 22.sp,
                fontWeight = FontWeight.SemiBold,
            ),
            color = scheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (song.artist.isNotBlank()) {
            Text(
                text = song.artist,
                style = MaterialTheme.typography.bodySmall.copy(fontSize = if (story) 14.sp else 13.sp, lineHeight = 18.sp),
                color = scheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        note.positionMs?.let { at ->
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 3.dp)) {
                Icon(
                    YoinSymbols.PlayFilled,
                    contentDescription = null,
                    tint = scheme.onSurfaceVariant.copy(alpha = 0.7f),
                    modifier = Modifier.size(10.dp),
                )
                Spacer(Modifier.width(3.dp))
                Text(
                    text = shareTime(at),
                    style = TextStyle(
                        fontFamily = NarrowFamily,
                        fontSize = if (story) 13.sp else 12.sp,
                        fontWeight = FontWeight.Normal,
                        letterSpacing = (-0.1).sp,
                    ),
                    color = scheme.onSurfaceVariant.copy(alpha = 0.9f),
                )
            }
        }
    }
}

/** Google Sans Flex at 80% width, Regular: the stub's quiet time. */
private val NarrowFamily = FontFamily(
    yoinFont(R.font.google_sans_flex_variable, FontWeight.Normal, mapOf("wght" to 400f, "wdth" to 80f)),
)

@Composable
private fun NoteTimelineBody(content: ShareContent, colors: ShareCardColors, frame: ShareFrame, modifier: Modifier) {
    val note = content.note ?: return
    val at = note.positionMs ?: return
    val duration = content.song.durationMs
    val fraction = YoinShareCards.timelineFraction(at, duration) ?: return
    Column(modifier.frameGround(frame, colors.card)) {
        ShareHead(content.song, colors)
        Spacer(Modifier.height(22.dp))
        Box(Modifier.fillMaxWidth().height(34.dp)) {
            ShareTimeChip(shareTime(at), colors, Modifier.centredAt(fraction))
        }
        WaveLine(fraction, colors.content)
        Row(Modifier.fillMaxWidth().padding(top = 2.dp)) {
            Text(shareTime(0L), style = EndsStyle, color = colors.content.copy(alpha = 0.6f))
            Spacer(Modifier.weight(1f))
            Text(shareTime(duration), style = EndsStyle, color = colors.content.copy(alpha = 0.6f))
        }
        // The note hangs from its moment: a bubble whose notch points at the marker.
        val bubble = colors.content.copy(alpha = 0.09f)
        Box(
            Modifier
                .padding(top = 16.dp)
                .fillMaxWidth()
                .drawBehind {
                    val notch = 8.dp.toPx()
                    val x = (size.width * fraction).coerceIn(18.dp.toPx(), size.width - 18.dp.toPx())
                    val path = Path().apply {
                        moveTo(x - notch, 0f)
                        lineTo(x, -notch)
                        lineTo(x + notch, 0f)
                        close()
                    }
                    drawPath(path, bubble)
                    drawRoundRect(bubble, cornerRadius = CornerRadius(16.dp.toPx()))
                }
                .padding(horizontal = 18.dp, vertical = 16.dp),
        ) {
            ShareNoteText(
                note = note,
                style = TextStyle(fontFamily = GoogleSansFlex, fontSize = 17.sp, lineHeight = 1.5.em, fontWeight = FontWeight.Medium),
                color = colors.content,
            )
        }
        Spacer(Modifier.height(24.dp))
        ShareFoot(colors, sign = { NoteSign(note, colors.secondary) })
    }
}

/** Yoin's wavy progress: the played part a sine, the rest a flat track, a short bar at the moment. */
@Composable
private fun WaveLine(fraction: Float, ink: Color) {
    Canvas(Modifier.fillMaxWidth().height(32.dp)) {
        val mid = size.height / 2f
        val amp = 3.2.dp.toPx()
        val wavelength = 16.dp.toPx()
        val stroke = 4.dp.toPx()
        val x = size.width * fraction
        val gap = 6.dp.toPx()
        val wave = Path().apply {
            moveTo(0f, mid)
            var px = 0f
            while (px <= x - gap) {
                lineTo(px, mid + amp * sin(px / wavelength * 2f * PI.toFloat()))
                px += 1f
            }
        }
        drawPath(wave, ink, style = Stroke(width = stroke, cap = StrokeCap.Round))
        if (x + gap < size.width) {
            drawLine(ink.copy(alpha = 0.24f), Offset(x + gap, mid), Offset(size.width, mid), strokeWidth = stroke, cap = StrokeCap.Round)
        }
        drawRoundRect(
            color = ink,
            topLeft = Offset(x - 2.dp.toPx(), mid - 12.dp.toPx()),
            size = Size(4.dp.toPx(), 24.dp.toPx()),
            cornerRadius = CornerRadius(2.dp.toPx()),
        )
    }
}

/** The scrapbook's dot paper: a 16dp grid of 1.2dp dots in outlineVariant. */
private fun Modifier.dotPaper(dot: Color): Modifier = drawBehind {
    val step = 16.dp.toPx()
    val radius = 1.2.dp.toPx()
    val color = dot.copy(alpha = 0.7f)
    var y = step / 2f
    while (y < size.height) {
        var x = step / 2f
        while (x < size.width) {
            drawCircle(color, radius, Offset(x, y))
            x += step
        }
        y += step
    }
}

internal const val ShareImageTag = "shareImage"
internal const val ShareSignTag = "shareSign"

/** At least this between the mark's "Yoin" and the sign. */
private val SignGap = 16.dp

private val CardPadding = 24.dp
private val PaddingValuesPoster = 28.dp
private val PosterCardMinHeight = 400.dp
private val VerticalCardMinHeight = 470.dp

/** 竖排's column height (the lyric columns' width and the footnote's are [YoinShareCards.verticalLyricsPlan]'s). */
private const val VerticalColumnHeight = YoinShareCards.verticalColumnHeightDp

/** The translation's footnote / bottom line: low contrast. */
private const val QuietAlpha = 0.55f

private val EndsStyle = TextStyle(fontFamily = GoogleSansFlex, fontSize = 11.sp, fontWeight = FontWeight.Medium)

// ---------------------------------------------------------------------------
// Previews: every layout at its logical size (covers draw as tiles in previews).
// ---------------------------------------------------------------------------

internal val PreviewShareContent = ShareContent(
    song = ShareSong(
        songId = "preview", // i18n-allow: preview data
        title = "他夏了夏天", // i18n-allow: preview data
        artist = "苏打绿", // i18n-allow: preview data
        coverArtUrl = null,
        albumName = "夏/狂热", // i18n-allow: preview data
        durationMs = 305_000L,
    ),
    lyrics = LyricsSharePick(
        lines = listOf(
            LyricLine(startMs = 226_800L, text = "帶著笑容的睡意化成了彩虹"), // i18n-allow: preview data
            LyricLine(startMs = 230_700L, text = "在他夢中一口氣走上了星空"), // i18n-allow: preview data
        ),
    ),
    about = AboutSharePick(
        credits = listOf(
            ShareCredit("作词", "吴青峰"), // i18n-allow: preview data
            ShareCredit("作曲", "吴青峰"), // i18n-allow: preview data
            ShareCredit("制作人", "林暐哲"), // i18n-allow: preview data
            ShareCredit("年份", "2009"), // i18n-allow: preview data
        ),
        heading = "关于", // i18n-allow: preview data
        paragraph = "一段占位的背景介绍：收录在哪张专辑、写的是什么、和谁有关。两三句话，由 AI 整理。", // i18n-allow: preview data
    ),
    note = NoteSharePick(
        noteId = "n1", // i18n-allow: preview data
        content = "最后一段鼓整个打开，**一口气走上了星空**。每次下班走出地铁，刚好听到这里。", // i18n-allow: preview data
        positionMs = 226_000L,
        createdAt = 1_784_390_400_000L,
    ),
)

/** Each image at phone width, light and dark. */
@Preview(name = "Light", widthDp = 360)
@Preview(name = "Dark", widthDp = 360, uiMode = Configuration.UI_MODE_NIGHT_YES)
private annotation class ShareImagePreviews

@Composable
private fun SharePreview(layout: ShareLayout, format: LyricsShareFormat, tone: ShareTone = ShareTone.Soft) {
    YoinTheme {
        ShareImage(content = PreviewShareContent, layout = layout, format = format, tone = tone)
    }
}

@ShareImagePreviews
@Composable
private fun ShareLyricsClassicPreview() = SharePreview(ShareLayout.LyricsClassic, LyricsShareFormat.Card)

@ShareImagePreviews
@Composable
private fun ShareLyricsPosterPreview() = SharePreview(ShareLayout.LyricsPoster, LyricsShareFormat.Card)

@ShareImagePreviews
@Composable
private fun ShareLyricsPlayerPreview() = SharePreview(ShareLayout.LyricsPlayer, LyricsShareFormat.Card)

@ShareImagePreviews
@Composable
private fun ShareLyricsVerticalPreview() = SharePreview(ShareLayout.LyricsVertical, LyricsShareFormat.Card)

@ShareImagePreviews
@Composable
private fun ShareAboutReceiptPreview() = SharePreview(ShareLayout.AboutReceipt, LyricsShareFormat.Card)

@ShareImagePreviews
@Composable
private fun ShareAboutQuotePreview() = SharePreview(ShareLayout.AboutQuote, LyricsShareFormat.Card)

@ShareImagePreviews
@Composable
private fun ShareAboutLinerPreview() = SharePreview(ShareLayout.AboutLiner, LyricsShareFormat.Card)

@ShareImagePreviews
@Composable
private fun ShareNoteBigPreview() = SharePreview(ShareLayout.NoteBig, LyricsShareFormat.Card)

@ShareImagePreviews
@Composable
private fun ShareNoteClippingPreview() = SharePreview(ShareLayout.NoteClipping, LyricsShareFormat.Card)

@ShareImagePreviews
@Composable
private fun ShareNoteTimelinePreview() = SharePreview(ShareLayout.NoteTimeline, LyricsShareFormat.Card)

@Preview(name = "Note Timeline Story", widthDp = 360, heightDp = 640)
@Composable
private fun ShareNoteTimelineStoryPreview() = SharePreview(ShareLayout.NoteTimeline, LyricsShareFormat.Story)

@Preview(name = "Note Big Story", widthDp = 360, heightDp = 640)
@Composable
private fun ShareNoteBigStoryPreview() = SharePreview(ShareLayout.NoteBig, LyricsShareFormat.Story)

/** 纸 stays a white card with the role's ink in both themes. */
@ShareImagePreviews
@Composable
private fun ShareAboutPaperPreview() = SharePreview(ShareLayout.AboutReceipt, LyricsShareFormat.Card, ShareTone.Paper)
