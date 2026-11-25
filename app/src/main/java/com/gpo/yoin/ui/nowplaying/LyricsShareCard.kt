package com.gpo.yoin.ui.nowplaying

import com.gpo.yoin.ui.component.handPointer
import android.content.ClipData
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.res.Resources
import android.graphics.Bitmap
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.annotation.RequiresApi
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ButtonGroup
import androidx.compose.material3.ButtonGroupDefaults
import androidx.compose.material3.ButtonGroupScope
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.constrainHeight
import androidx.compose.ui.unit.constrainWidth
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.request.allowHardware
import com.gpo.yoin.R
import com.gpo.yoin.symbols.YoinSymbols
import com.gpo.yoin.ui.experience.rememberYoinHaptics
import com.gpo.yoin.ui.theme.GoogleSansFlexRounded
import com.gpo.yoin.ui.theme.ProvideYoinMotionRole
import com.gpo.yoin.ui.theme.YoinArtworkShapes
import com.gpo.yoin.ui.theme.YoinContainerShapes
import com.gpo.yoin.ui.theme.YoinMotion
import com.gpo.yoin.ui.theme.YoinMotionRole
import com.gpo.yoin.ui.theme.YoinShapeTokens
import com.gpo.yoin.ui.theme.YoinTheme
import java.io.File
import java.io.FileOutputStream
import kotlin.math.min
import kotlin.math.roundToInt
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The shapes a pick can be shared as (owner L6, 2026-10-05: spotoolfy's poster
 * plus Spotify's story size).
 */
internal enum class LyricsShareFormat {
    /** The quote card on its own — spotoolfy's poster width (720 × 2 px). */
    Card,

    /** 9:16 full-bleed backdrop with the card centred, for stories / status. */
    Story,
}

/**
 * Whose words an image carries, which decides its colour role (owner 2026-11-25): the lyrics keep today's
 * pairings; About is AI / objective text and reads tertiary (as the app's Ask / About cards); a Note is the
 * listener's own and reads primary (as their album review).
 */
internal enum class ShareRole { Lyrics, Ai, Own }

/**
 * The image's colour pairings — spotoolfy's four poster styles for the lyrics, read from the playing palette
 * (Now Playing's theme is already the cover's). About and Note draw the same slots from their own role, with
 * [Paper] in the fourth: [Mist] is tertiary, which would put a note in the AI's colour.
 */
internal enum class ShareTone {
    /** Container + on-container (spotoolfy style 2): the default. */
    Soft,

    /** The soft pair inverted (spotoolfy style 1). */
    Deep,

    /** The role colour + its on-colour (spotoolfy style 3: tertiary for the lyrics). */
    Vivid,

    /** Tertiary container + its on-colour (spotoolfy style 4) — the lyrics' fourth slot. */
    Mist,

    /** The lowest surface (white in the light theme) with the role colour as ink — About and Note's fourth slot. */
    Paper,
}

/** The four tones a role offers, in swatch order. */
internal fun ShareRole.tones(): List<ShareTone> = when (this) {
    ShareRole.Lyrics -> listOf(ShareTone.Soft, ShareTone.Deep, ShareTone.Vivid, ShareTone.Mist)
    ShareRole.Ai, ShareRole.Own -> listOf(ShareTone.Soft, ShareTone.Deep, ShareTone.Vivid, ShareTone.Paper)
}

/** One tone's colours: the card, its text, its secondary text, and the story's backdrop. */
internal data class ShareCardColors(
    val card: Color,
    val content: Color,
    val secondary: Color,
    val backdrop: Color,
)

/**
 * [this] tone's colours for [role]. The fourth slot follows the role: a lyrics card asked for [ShareTone.Paper]
 * draws [ShareTone.Mist] and the other roles the other way round, so a swatch kept across tabs stays valid.
 */
internal fun ShareTone.colors(scheme: ColorScheme, role: ShareRole = ShareRole.Lyrics): ShareCardColors {
    fun pair(card: Color, content: Color, backdrop: Color) =
        ShareCardColors(card, content, content.copy(alpha = CardSecondaryAlpha), backdrop)
    if (role == ShareRole.Lyrics) {
        return when (this) {
            ShareTone.Soft -> pair(scheme.primaryContainer, scheme.onPrimaryContainer, scheme.primary)
            ShareTone.Deep -> pair(scheme.onPrimaryContainer, scheme.primaryContainer, scheme.primaryContainer)
            ShareTone.Vivid -> pair(scheme.tertiary, scheme.onTertiary, scheme.tertiaryContainer)
            ShareTone.Mist, ShareTone.Paper -> pair(scheme.tertiaryContainer, scheme.onTertiaryContainer, scheme.tertiary)
        }
    }
    val ai = role == ShareRole.Ai
    val main = if (ai) scheme.tertiary else scheme.primary
    val onMain = if (ai) scheme.onTertiary else scheme.onPrimary
    val container = if (ai) scheme.tertiaryContainer else scheme.primaryContainer
    val onContainer = if (ai) scheme.onTertiaryContainer else scheme.onPrimaryContainer
    return when (this) {
        ShareTone.Soft -> pair(container, onContainer, main)
        ShareTone.Deep -> pair(onContainer, container, container)
        ShareTone.Vivid -> pair(main, onMain, container)
        ShareTone.Mist, ShareTone.Paper -> pair(scheme.surfaceContainerLowest, main, container)
    }
}

/** The card's logical width; exported at [LyricsCardExportWidthPx]. */
internal val LyricsCardDesignWidth = 360.dp

/** The story's logical size; exported at [LyricsStoryExportWidthPx] × [LyricsStoryExportHeightPx]. */
internal val LyricsStoryDesignSize = DpSize(360.dp, 640.dp)

/** spotoolfy's poster: 720 logical px at 2×. */
internal const val LyricsCardExportWidthPx = 1440

/** The common 9:16 story canvas. */
internal const val LyricsStoryExportWidthPx = 1080
internal const val LyricsStoryExportHeightPx = 1920

/**
 * The PNG's pixel size for content laid out at [laidOutWidthPx] ×
 * [laidOutHeightPx]: a fixed width per format (so the image doesn't depend
 * on the phone's density), the card's height following its content.
 */
internal fun lyricsShareExportSize(
    format: LyricsShareFormat,
    laidOutWidthPx: Float,
    laidOutHeightPx: Float,
): IntSize = when (format) {
    LyricsShareFormat.Story -> IntSize(LyricsStoryExportWidthPx, LyricsStoryExportHeightPx)
    LyricsShareFormat.Card -> IntSize(
        LyricsCardExportWidthPx,
        (laidOutHeightPx * LyricsCardExportWidthPx / laidOutWidthPx).roundToInt().coerceAtLeast(1),
    )
}

/**
 * Uniform scale that fits a [childWidth] × [childHeight] layout into
 * [maxWidth] × [maxHeight] (px; [Constraints.Infinity] = unbounded) without
 * ever enlarging it.
 */
internal fun lyricsShareFitScale(
    childWidth: Int,
    childHeight: Int,
    maxWidth: Int,
    maxHeight: Int,
    // A preview on a wide sheet may draw larger than the image's logical size (it is not the PNG).
    maxScale: Float = 1f,
): Float {
    if (childWidth <= 0 || childHeight <= 0) return 1f
    return min(maxScale, min(maxWidth.toFloat() / childWidth, maxHeight.toFloat() / childHeight))
}

/**
 * Gallery file name (spotoolfy: `lyrics_poster_<title>_<ms>`): "Yoin - <title> - <ms>.png" for every style
 * (lyrics, About, Note), the song title with the characters file systems refuse dropped, capped.
 */
internal fun shareFileName(
    songTitle: String?,
    nowMs: Long,
    resources: Resources? = null,
): String {
    val title = songTitle.orEmpty()
        .replace(Regex("""[\\/:*?"<>|\p{Cntrl}]"""), " ")
        .replace(Regex("""\s+"""), " ")
        .trim()
        .take(FileNameTitleMaxChars)
        .trim()
    val stamp = nowMs.toString()
    return if (title.isEmpty()) {
        if (resources == null) {
            "Yoin $nowMs.png" // i18n-allow: LyricsShareCardTest asserts this name
        } else {
            resources.getString(R.string.np_share_file_untitled, stamp)
        }
    } else if (resources == null) {
        "Yoin - $title - $nowMs.png" // i18n-allow: LyricsShareCardTest asserts this name
    } else {
        resources.getString(R.string.np_share_file_named, title, stamp)
    }
}

/**
 * The lyrics' Classic image ([ShareLayout.LyricsClassic]) at its logical size, in the chosen [format] and
 * [tone] — [ShareImage] with a lyric pick, kept for the callers and tests that only know lines.
 */
@Composable
internal fun LyricsShareImage(
    format: LyricsShareFormat,
    tone: ShareTone,
    lines: List<LyricLine>,
    passageStarts: Set<Int> = emptySet(),
    showTranslation: Boolean,
    songTitle: String,
    artist: String,
    coverArtUrl: String?,
    modifier: Modifier = Modifier,
    exportLayer: GraphicsLayer? = null,
    layout: ShareLayout = ShareLayout.LyricsClassic,
) {
    ShareImage(
        content = ShareContent(
            song = ShareSong(songId = "", title = songTitle, artist = artist, coverArtUrl = coverArtUrl),
            lyrics = LyricsSharePick(lines, passageStarts, showTranslation),
        ),
        layout = layout,
        format = format,
        tone = tone,
        modifier = modifier,
        exportLayer = exportLayer,
    )
}

/**
 * The shareable card: the tone's container, cover + title + artist, the
 * picked lines in the rounded GSF cut the current lyric line uses, and the
 * Yoin mark. [shape]: rounded where it sits on the story's backdrop; the Card
 * format's PNG takes it square ([LyricsShareImage]).
 */
@Composable
internal fun LyricsShareCard(
    lines: List<LyricLine>,
    passageStarts: Set<Int> = emptySet(),
    showTranslation: Boolean,
    songTitle: String,
    artist: String,
    coverArtUrl: String?,
    modifier: Modifier = Modifier,
    colors: ShareCardColors = ShareTone.Soft.colors(MaterialTheme.colorScheme),
    shape: Shape = YoinContainerShapes.Panel,
) {
    val context = LocalContext.current
    val lineStyle = lyricsCardLineStyle(lines)
    Column(
        modifier = modifier
            .testTag(LyricsShareCardTag)
            .clip(shape)
            .background(colors.card)
            .padding(24.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (coverArtUrl != null) {
                AsyncImage(
                    // Software bitmap: the card is exported through a graphics
                    // layer readback.
                    model = remember(coverArtUrl) {
                        ImageRequest.Builder(context)
                            .data(coverArtUrl)
                            .allowHardware(false)
                            .build()
                    },
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .size(44.dp)
                        .clip(YoinArtworkShapes.Thumb),
                )
                Spacer(modifier = Modifier.width(12.dp))
            }
            // Title + artist sit flush: one text cluster.
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = songTitle,
                    style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                    color = colors.content,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (artist.isNotBlank()) {
                    Text(
                        text = artist,
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.secondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
        Spacer(modifier = Modifier.height(20.dp))
        Column {
            lines.forEachIndexed { i, line ->
                // A skipped stretch of the song reads as a stanza break.
                val gap = when {
                    i == 0 -> 0.dp
                    i in passageStarts -> CardPassageGap
                    else -> CardLineGap
                }
                Column(modifier = Modifier.padding(top = gap)) {
                    Text(text = line.text, style = lineStyle, color = colors.content)
                    val translation = line.translation
                    if (showTranslation && !translation.isNullOrBlank()) {
                        Text(
                            text = translation,
                            style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium),
                            color = colors.content.copy(alpha = CardTranslationAlpha),
                            modifier = Modifier.padding(top = 2.dp),
                        )
                    }
                }
            }
        }
        Spacer(modifier = Modifier.height(24.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            // The app's monochrome (themed) icon — the line drawing, not the
            // solid mark (owner 2026-10-05). Its glyph sits in the adaptive
            // icon's safe zone, so it is scaled up to fill the 22dp slot.
            Icon(
                painter = painterResource(R.drawable.ic_yoin_launcher_monochrome),
                contentDescription = null,
                tint = colors.content,
                modifier = Modifier
                    .size(22.dp)
                    .graphicsLayer {
                        scaleX = MonochromeGlyphScale
                        scaleY = MonochromeGlyphScale
                    },
            )
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                text = stringResource(R.string.np_share_brand),
                style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold),
                color = colors.content,
            )
        }
    }
}

/** Fewer, shorter lines read large; a long pick steps the size down. */
@Composable
internal fun lyricsCardLineStyle(lines: List<LyricLine>): TextStyle =
    MaterialTheme.typography.titleLarge.copy(
        fontFamily = GoogleSansFlexRounded,
        fontWeight = FontWeight.SemiBold,
        fontSize = lyricsCardLineSize(lines),
        lineHeight = 1.3.em,
    )

/**
 * The picked lines' size on the 360dp card: a calm step down from the old
 * headline sizes (owner 2026-10-05: 28sp read "too big, odd" — about a dozen
 * Latin letters a line). Short picks 22sp, medium 19sp, long 17sp.
 */
internal fun lyricsCardLineSize(lines: List<LyricLine>): TextUnit {
    val chars = lines.sumOf { it.text.length }
    return when {
        chars <= 90 && lines.size <= 4 -> 22.sp
        chars <= 200 -> 19.sp
        else -> 17.sp
    }
}

/** Card / Story, as the NP tab group's pills. */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun ShareFormatToggle(
    selected: LyricsShareFormat,
    onSelect: (LyricsShareFormat) -> Unit,
    modifier: Modifier = Modifier,
) {
    val cardLabel = stringResource(R.string.np_share_format_card)
    val storyLabel = stringResource(R.string.np_share_format_story)
    val interactions = remember { LyricsShareFormat.entries.associateWith { MutableInteractionSource() } }
    ProvideYoinMotionRole(role = YoinMotionRole.Standard) {
        ButtonGroup(
            overflowIndicator = { _ -> },
            modifier = modifier
                .fillMaxWidth()
                .height(44.dp),
            expandedRatio = ButtonGroupDefaults.ExpandedRatio,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            LyricsShareFormat.entries.forEach { format ->
                customItem(
                    buttonGroupContent = {
                        LyricsShareFormatButton(
                            label = when (format) {
                                LyricsShareFormat.Card -> cardLabel
                                LyricsShareFormat.Story -> storyLabel
                            },
                            isSelected = format == selected,
                            interactionSource = interactions.getValue(format),
                            onClick = { onSelect(format) },
                            modifier = Modifier.weight(1f),
                        )
                    },
                    menuContent = { _ -> },
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun ButtonGroupScope.LyricsShareFormatButton(
    label: String,
    isSelected: Boolean,
    interactionSource: MutableInteractionSource,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val container by animateColorAsState(
        targetValue = if (isSelected) {
            MaterialTheme.colorScheme.primary
        } else {
            MaterialTheme.colorScheme.primaryContainer
        },
        animationSpec = YoinMotion.defaultEffectsSpec(),
        label = "shareFormatContainer",
    )
    val content by animateColorAsState(
        targetValue = if (isSelected) {
            MaterialTheme.colorScheme.onPrimary
        } else {
            MaterialTheme.colorScheme.onPrimaryContainer
        },
        animationSpec = YoinMotion.defaultEffectsSpec(),
        label = "shareFormatContent",
    )
    FilledTonalButton(
        onClick = onClick,
        modifier = modifier
            .fillMaxHeight()
            .animateWidth(interactionSource)
            .semantics { selected = isSelected }
            .handPointer(),
        interactionSource = interactionSource,
        shape = YoinShapeTokens.Full,
        colors = ButtonDefaults.filledTonalButtonColors(
            containerColor = container,
            contentColor = content,
        ),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge.copy(
                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
            ),
        )
    }
}

/** [role]'s four tones as swatches of the card colour; the picked one squares off and wears a check. */
@Composable
internal fun ShareToneSwatches(
    role: ShareRole,
    selected: ShareTone,
    onSelect: (ShareTone) -> Unit,
    modifier: Modifier = Modifier,
) {
    val softLabel = stringResource(R.string.np_cd_tone_soft)
    val deepLabel = stringResource(R.string.np_cd_tone_deep)
    val vividLabel = stringResource(R.string.np_cd_tone_vivid)
    val mistLabel = stringResource(R.string.np_cd_tone_mist)
    val paperLabel = stringResource(R.string.np_cd_tone_paper)
    val scheme = MaterialTheme.colorScheme
    Row(
        modifier = modifier.selectableGroup(),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        role.tones().forEach { tone ->
            LyricsCardToneSwatch(
                colors = tone.colors(scheme, role),
                label = when (tone) {
                    ShareTone.Soft -> softLabel
                    ShareTone.Deep -> deepLabel
                    ShareTone.Vivid -> vividLabel
                    ShareTone.Mist -> mistLabel
                    ShareTone.Paper -> paperLabel
                },
                // Paper is the lowest surface: a ring of its ink keeps it from vanishing into the sheet.
                ring = tone == ShareTone.Paper,
                selected = tone == selected,
                onClick = { onSelect(tone) },
            )
        }
    }
}

@Composable
private fun LyricsCardToneSwatch(
    colors: ShareCardColors,
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    ring: Boolean = false,
) {
    val corner by animateDpAsState(
        targetValue = if (selected) SwatchSelectedCorner else SwatchSize / 2,
        animationSpec = YoinMotion.defaultSpatialSpec(role = YoinMotionRole.Expressive),
        label = "toneSwatchCorner",
    )
    Box(
        modifier = Modifier
            .size(SwatchTouchSize)
            .handPointer()
            .selectable(
                selected = selected,
                interactionSource = null,
                indication = ripple(bounded = false, radius = SwatchTouchSize / 2),
                role = Role.RadioButton,
                onClick = onClick,
            )
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .size(SwatchSize)
                .drawBehind {
                    val radius = CornerRadius(corner.toPx().coerceAtLeast(0f))
                    drawRoundRect(color = colors.card, cornerRadius = radius)
                    if (ring) {
                        val stroke = SwatchRingWidth.toPx()
                        drawRoundRect(
                            color = colors.content,
                            topLeft = Offset(stroke / 2f, stroke / 2f),
                            size = Size(size.width - stroke, size.height - stroke),
                            cornerRadius = CornerRadius((radius.x - stroke / 2f).coerceAtLeast(0f)),
                            style = Stroke(width = stroke),
                        )
                    }
                },
        )
        AnimatedVisibility(
            visible = selected,
            enter = YoinMotion.fadeIn(role = YoinMotionRole.Standard) +
                YoinMotion.scaleIn(role = YoinMotionRole.Expressive, initialScale = 0.6f),
            exit = YoinMotion.fadeOut(role = YoinMotionRole.Standard) +
                YoinMotion.scaleOut(role = YoinMotionRole.Standard, targetScale = 0.6f),
        ) {
            Icon(
                imageVector = YoinSymbols.Check,
                contentDescription = null,
                tint = colors.content,
                modifier = Modifier.size(20.dp),
            )
        }
    }
}

/**
 * Lays the content out at its own size and scales it down — never up — to
 * fit the incoming bounds and [maxHeight]: a preview of exactly what will be
 * exported. Static content only (a placement-layer scale).
 */
internal fun Modifier.scaleDownToFit(
    maxHeight: Dp = Dp.Infinity,
    maxScale: Float = 1f,
): Modifier = layout { measurable, constraints ->
    val placeable = measurable.measure(Constraints())
    val heightCap = if (maxHeight == Dp.Infinity) {
        constraints.maxHeight
    } else {
        min(constraints.maxHeight, maxHeight.roundToPx())
    }
    val scale = lyricsShareFitScale(placeable.width, placeable.height, constraints.maxWidth, heightCap, maxScale)
    val width = constraints.constrainWidth((placeable.width * scale).roundToInt())
    val height = constraints.constrainHeight((placeable.height * scale).roundToInt())
    layout(width, height) {
        placeable.placeWithLayer(0, 0) {
            scaleX = scale
            scaleY = scale
            transformOrigin = TransformOrigin(0f, 0f)
        }
    }
}

/**
 * Fills the incoming width and fits the incoming height by ZOOMING: the content is laid out at width / zoom
 * (wider when zooming out, so lines wrap less; narrower when zooming in) and drawn back at `zoom`, the
 * largest zoom up to [maxZoom] whose height fits ([lyricsStoryZoom], probed through intrinsic heights). A
 * plain shrink would narrow the card to a sliver for a long pick; this keeps it the frame's width. A story
 * lets short content grow up to [StoryMaxZoom] (owner 2026-11-25). [fillHeight] stretches the content to the
 * frame's height at that zoom (a poster's foot sits at the bottom).
 */
internal fun Modifier.zoomToFit(maxZoom: Float = 1f, fillHeight: Boolean = false): Modifier = layout { measurable, constraints ->
    if (!constraints.hasBoundedWidth || !constraints.hasBoundedHeight) {
        val placeable = measurable.measure(constraints)
        return@layout layout(placeable.width, placeable.height) { placeable.place(0, 0) }
    }
    val frameWidth = constraints.maxWidth
    val frameHeight = constraints.maxHeight
    val zoom = lyricsStoryZoom(maxZoom) { candidate ->
        val logicalWidth = (frameWidth / candidate).roundToInt()
        measurable.minIntrinsicHeight(logicalWidth) * candidate <= frameHeight
    }
    val logicalWidth = (frameWidth / zoom).roundToInt()
    val minHeight = if (fillHeight) (frameHeight / zoom).toInt() else 0
    val placeable = measurable.measure(
        Constraints(minWidth = logicalWidth, maxWidth = logicalWidth, minHeight = minHeight),
    )
    // Even the furthest zoom can fall short (a pathological pick): shrink the rest.
    val scale = min(zoom, frameHeight.toFloat() / placeable.height)
    layout(
        constraints.constrainWidth((placeable.width * scale).roundToInt()),
        constraints.constrainHeight((placeable.height * scale).roundToInt()),
    ) {
        placeable.placeWithLayer(0, 0) {
            scaleX = scale
            scaleY = scale
            transformOrigin = TransformOrigin(0f, 0f)
        }
    }
}

/**
 * The largest zoom in [[LyricsStoryMinZoom], [maxZoom]] for which [fits] holds (fits is monotonic: a
 * smaller zoom is a wider, shorter layout drawn smaller). [maxZoom] when even that fits;
 * [LyricsStoryMinZoom] when nothing does.
 */
internal fun lyricsStoryZoom(maxZoom: Float = 1f, fits: (zoom: Float) -> Boolean): Float {
    if (fits(maxZoom)) return maxZoom
    var low: Float
    var high: Float
    if (maxZoom > 1f && fits(1f)) {
        low = 1f
        high = maxZoom
    } else {
        low = LyricsStoryMinZoom
        high = minOf(1f, maxZoom)
        if (fits(high)) return high
    }
    repeat(StoryZoomSearchSteps) {
        val mid = (low + high) / 2f
        if (fits(mid)) low = mid else high = mid
    }
    return low
}

/** Furthest a story zooms out: below this the type is too small to read on a phone. */
internal const val LyricsStoryMinZoom = 0.4f

/** Furthest a story zooms IN on short content (owner 2026-11-25: Story may enlarge it). */
internal const val StoryMaxZoom = 1.4f
private const val StoryZoomSearchSteps = 8

/**
 * Records the content into [layer] at the format's export size (content
 * scaled up to it), then draws that same recording scaled back down — so the
 * preview and the PNG can't differ. Each axis is scaled to fill the export
 * exactly: the Card's height is rounded to whole pixels, and one uniform
 * scale left up to half a pixel of it undrawn — a see-through last row in
 * the PNG. The two scales differ by under a pixel over the image.
 */
internal fun Modifier.recordForExport(layer: GraphicsLayer, format: LyricsShareFormat): Modifier =
    drawWithContent {
        if (size.width <= 0f || size.height <= 0f) {
            drawContent()
            return@drawWithContent
        }
        val export = lyricsShareExportSize(format, size.width, size.height)
        val scaleX = export.width / size.width
        val scaleY = export.height / size.height
        layer.record(size = export) {
            scale(scaleX, scaleY, pivot = Offset.Zero) { this@drawWithContent.drawContent() }
        }
        scale(1f / scaleX, 1f / scaleY, pivot = Offset.Zero) { drawLayer(layer) }
    }

/** The layer's current recording as a bitmap; null before it has drawn once. */
internal suspend fun GraphicsLayer.exportImage(): ImageBitmap? = try {
    if (size.width <= 0 || size.height <= 0) null else toImageBitmap()
} catch (e: CancellationException) {
    throw e
} catch (_: Exception) {
    null
}

private fun ImageBitmap.toSoftwareBitmap(): Bitmap = asAndroidBitmap().let { raw ->
    if (raw.config == Bitmap.Config.HARDWARE) raw.copy(Bitmap.Config.ARGB_8888, false) else raw
}

/**
 * Writes [image] to the app cache and opens the system share sheet on it
 * (through the app's FileProvider — authority `<package>.fileprovider`,
 * paths in `res/xml/file_paths.xml`), with [caption] as the text for apps
 * that take one. False when anything fails.
 */
internal suspend fun shareImageFile(context: Context, image: ImageBitmap, caption: String?): Boolean = try {
    val uri = withContext(Dispatchers.IO) {
        val dir = File(context.cacheDir, ShareCacheDir).apply { mkdirs() }
        dir.listFiles { file -> file.name.startsWith(ShareFilePrefix) }?.forEach(File::delete)
        val file = File(dir, "$ShareFilePrefix${System.currentTimeMillis()}.png")
        FileOutputStream(file).use { out -> image.toSoftwareBitmap().compress(Bitmap.CompressFormat.PNG, 100, out) }
        FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
    }
    val send = Intent(Intent.ACTION_SEND).apply {
        type = "image/png"
        putExtra(Intent.EXTRA_STREAM, uri)
        if (caption != null) putExtra(Intent.EXTRA_TEXT, caption)
        // ClipData lets the chooser preview the image and carries the grant.
        clipData = ClipData.newRawUri(null, uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    context.startActivity(Intent.createChooser(send, null))
    true
} catch (e: CancellationException) {
    throw e
} catch (_: Exception) {
    false
}

/**
 * Saves [image] as a PNG in Pictures/Yoin through MediaStore — the app's own
 * contribution, so no storage permission (API 29+; below that the Save action
 * isn't offered). False when anything fails; a half-written entry is removed.
 */
@RequiresApi(Build.VERSION_CODES.Q)
internal suspend fun saveImageToGallery(context: Context, image: ImageBitmap, displayName: String): Boolean = try {
    withContext(Dispatchers.IO) {
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, displayName)
            put(MediaStore.Images.Media.MIME_TYPE, "image/png")
            put(MediaStore.Images.Media.RELATIVE_PATH, "${Environment.DIRECTORY_PICTURES}/$GalleryFolder")
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
        val collection = MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val uri = resolver.insert(collection, values) ?: return@withContext false
        try {
            val written = resolver.openOutputStream(uri)?.use { out ->
                image.toSoftwareBitmap().compress(Bitmap.CompressFormat.PNG, 100, out)
            } == true
            if (written) {
                values.clear()
                values.put(MediaStore.Images.Media.IS_PENDING, 0)
                resolver.update(uri, values, null, null)
            } else {
                resolver.delete(uri, null, null)
            }
            written
        } catch (e: Exception) {
            resolver.delete(uri, null, null)
            throw e
        }
    }
} catch (e: CancellationException) {
    throw e
} catch (_: Exception) {
    false
}

internal const val LyricsShareCardTag = "lyricsShareCard"

private const val ShareCacheDir = "shared"
private const val ShareFilePrefix = "yoin-share-"
private const val GalleryFolder = "Yoin"
private const val FileNameTitleMaxChars = 48

/** Artist line on the card. */
internal const val CardSecondaryAlpha = 0.72f

/** Translation lines on the card. */
internal const val CardTranslationAlpha = 0.76f

/** Between picked lines; a new passage (the pick skipped lines) opens a stanza-sized gap. */
private val CardLineGap = 6.dp
private val CardPassageGap = 20.dp

/** The story's card sits clear of the top / bottom chrome stories draw over the image. */
internal val StoryHorizontalMargin = 28.dp
internal val StoryVerticalMargin = 72.dp

private val SwatchSize = 36.dp
private val SwatchTouchSize = 48.dp
private val SwatchSelectedCorner = 10.dp
private val SwatchRingWidth = 2.dp

private val PreviewLines = listOf(
    LyricLine(startMs = 1_000L, text = "A placeholder lyric line", translation = "一行占位歌词"),
    LyricLine(startMs = 4_000L, text = "And a second, slightly longer one"),
)

@Preview
@Composable
private fun LyricsShareCardPreview() {
    YoinTheme {
        LyricsShareCard(
            lines = PreviewLines,
            showTranslation = false,
            songTitle = "That's So True",
            artist = "Gracie Abrams",
            coverArtUrl = null,
        )
    }
}

@Preview(widthDp = 360, heightDp = 640)
@Composable
private fun LyricsShareStoryPreview() {
    YoinTheme {
        LyricsShareImage(
            format = LyricsShareFormat.Story,
            tone = ShareTone.Vivid,
            lines = PreviewLines,
            showTranslation = true,
            songTitle = "That's So True",
            artist = "Gracie Abrams",
            coverArtUrl = null,
        )
    }
}

/** The themed icon's glyph spans ~58% of its 108dp canvas: this scale fills the mark's slot with it. */
internal const val MonochromeGlyphScale = 1.7f
