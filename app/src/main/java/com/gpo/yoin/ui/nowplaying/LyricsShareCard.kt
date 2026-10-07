package com.gpo.yoin.ui.nowplaying

import android.content.ClipData
import android.content.ContentValues
import android.content.Context
import android.content.Intent
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
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
internal enum class LyricsShareFormat(val label: String) {
    /** The quote card on its own — spotoolfy's poster width (720 × 2 px). */
    Card("Card"),

    /** 9:16 full-bleed backdrop with the card centred, for stories / status. */
    Story("Story"),
}

/**
 * The card's colour pairings — spotoolfy's four poster styles, read from the
 * playing palette (Now Playing's theme is already the cover's).
 */
internal enum class LyricsCardTone(val label: String) {
    /** Container + on-container (spotoolfy style 2): the default. */
    Soft("Soft"),

    /** The soft pair inverted (spotoolfy style 1). */
    Deep("Deep"),

    /** Tertiary + on-tertiary (spotoolfy style 3). */
    Vivid("Vivid"),

    /** Tertiary container + its on-colour (spotoolfy style 4). */
    Mist("Mist"),
}

/** One tone's colours: the card, its text, its secondary text, and the story's backdrop. */
internal data class LyricsCardColors(
    val card: Color,
    val content: Color,
    val secondary: Color,
    val backdrop: Color,
)

internal fun LyricsCardTone.colors(scheme: ColorScheme): LyricsCardColors = when (this) {
    LyricsCardTone.Soft -> LyricsCardColors(
        card = scheme.primaryContainer,
        content = scheme.onPrimaryContainer,
        secondary = scheme.onPrimaryContainer.copy(alpha = CardSecondaryAlpha),
        backdrop = scheme.primary,
    )
    LyricsCardTone.Deep -> LyricsCardColors(
        card = scheme.onPrimaryContainer,
        content = scheme.primaryContainer,
        secondary = scheme.primaryContainer.copy(alpha = CardSecondaryAlpha),
        backdrop = scheme.primaryContainer,
    )
    LyricsCardTone.Vivid -> LyricsCardColors(
        card = scheme.tertiary,
        content = scheme.onTertiary,
        secondary = scheme.onTertiary.copy(alpha = CardSecondaryAlpha),
        backdrop = scheme.tertiaryContainer,
    )
    LyricsCardTone.Mist -> LyricsCardColors(
        card = scheme.tertiaryContainer,
        content = scheme.onTertiaryContainer,
        secondary = scheme.onTertiaryContainer.copy(alpha = CardSecondaryAlpha),
        backdrop = scheme.tertiary,
    )
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
internal fun lyricsShareFitScale(childWidth: Int, childHeight: Int, maxWidth: Int, maxHeight: Int): Float {
    if (childWidth <= 0 || childHeight <= 0) return 1f
    return min(1f, min(maxWidth.toFloat() / childWidth, maxHeight.toFloat() / childHeight))
}

/**
 * Gallery file name (spotoolfy: `lyrics_poster_<title>_<ms>`): the song title
 * with the characters file systems refuse dropped, capped, plus a timestamp.
 */
internal fun lyricsShareFileName(songTitle: String?, nowMs: Long): String {
    val title = songTitle.orEmpty()
        .replace(Regex("""[\\/:*?"<>|\p{Cntrl}]"""), " ")
        .replace(Regex("""\s+"""), " ")
        .trim()
        .take(FileNameTitleMaxChars)
        .trim()
    return if (title.isEmpty()) "Yoin lyrics $nowMs.png" else "Yoin lyrics - $title - $nowMs.png"
}

private enum class SaveStatus { Idle, Saving, Saved, Failed }

/**
 * Share-as-image for the lines picked in select mode (spotoolfy's poster
 * preview, Spotify's story size): the preview, Card / Story, four tones, then
 * Save (to Pictures/Yoin) and Share. What the sheet shows IS what gets saved
 * or shared — each format draws through a recorded graphics layer that is
 * exported as the PNG at a fixed pixel size.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun LyricsShareSheet(
    lines: List<LyricLine>,
    // Positions in [lines] that start a new passage: the pick skipped part of the song there.
    passageStarts: Set<Int> = emptySet(),
    showTranslation: Boolean,
    songTitle: String,
    artist: String,
    coverArtUrl: String?,
    onShared: () -> Unit,
    onDismiss: () -> Unit,
    onMessage: (String) -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val haptics = rememberYoinHaptics()
    // One layer per format: during the Card ⇄ Story cross-fade both draw, and
    // neither may overwrite the other's recording.
    val cardLayer = rememberGraphicsLayer()
    val storyLayer = rememberGraphicsLayer()
    var format by rememberSaveable { mutableStateOf(LyricsShareFormat.Card) }
    var tone by rememberSaveable { mutableStateOf(LyricsCardTone.Soft) }
    var sharing by remember { mutableStateOf(false) }
    var saveStatus by remember { mutableStateOf(SaveStatus.Idle) }
    val canSave = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
    val windowHeight = with(LocalDensity.current) { LocalWindowInfo.current.containerSize.height.toDp() }
    val previewMaxHeight = windowHeight * PreviewMaxHeightFraction
    val previewSizeSpec = YoinMotion.defaultSpatialSpec<IntSize>(role = YoinMotionRole.Expressive)
    val labelSizeSpec = YoinMotion.fastSpatialSpec<IntSize>(role = YoinMotionRole.Standard)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surfaceContainer,
        contentWindowInsets = {
            BottomSheetDefaults.modalWindowInsets.only(
                WindowInsetsSides.Top + WindowInsetsSides.Horizontal,
            )
        },
    ) {
        val navBottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp)
                .padding(bottom = 16.dp + navBottom),
        ) {
            Text(
                text = "Share lyrics",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(modifier = Modifier.height(16.dp))
            AnimatedContent(
                targetState = format,
                transitionSpec = {
                    (
                        YoinMotion.fadeIn(role = YoinMotionRole.Standard) togetherWith
                            YoinMotion.fadeOut(role = YoinMotionRole.Standard)
                        ) using SizeTransform(clip = false) { _, _ -> previewSizeSpec }
                },
                contentAlignment = Alignment.TopCenter,
                label = "lyricsShareFormat",
                modifier = Modifier.fillMaxWidth(),
            ) { shownFormat ->
                Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
                    LyricsShareImage(
                        format = shownFormat,
                        tone = tone,
                        lines = lines,
                        passageStarts = passageStarts,
                        showTranslation = showTranslation,
                        songTitle = songTitle,
                        artist = artist,
                        coverArtUrl = coverArtUrl,
                        exportLayer = if (shownFormat == LyricsShareFormat.Card) cardLayer else storyLayer,
                        // The story previews as a screen (rounded); the PNG stays full-bleed.
                        modifier = Modifier
                            .then(
                                if (shownFormat == LyricsShareFormat.Story) {
                                    Modifier.clip(YoinContainerShapes.Card)
                                } else {
                                    Modifier
                                },
                            )
                            .scaleDownToFit(maxHeight = previewMaxHeight),
                    )
                }
            }
            Spacer(modifier = Modifier.height(20.dp))
            LyricsShareFormatToggle(
                selected = format,
                onSelect = { picked ->
                    if (picked != format) {
                        haptics.performSegmentTick()
                        format = picked
                        saveStatus = SaveStatus.Idle
                    }
                },
            )
            Spacer(modifier = Modifier.height(12.dp))
            LyricsCardToneSwatches(
                selected = tone,
                onSelect = { picked ->
                    if (picked != tone) {
                        haptics.performSegmentTick()
                        tone = picked
                        saveStatus = SaveStatus.Idle
                    }
                },
            )
            Spacer(modifier = Modifier.height(20.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                if (canSave) {
                    FilledTonalButton(
                        onClick = {
                            if (saveStatus == SaveStatus.Saving || saveStatus == SaveStatus.Saved) {
                                return@FilledTonalButton
                            }
                            saveStatus = SaveStatus.Saving
                            val layer = if (format == LyricsShareFormat.Card) cardLayer else storyLayer
                            val name = lyricsShareFileName(songTitle, System.currentTimeMillis())
                            scope.launch {
                                val saved = layer.exportImage()?.let { saveLyricsImage(context, it, name) } == true
                                saveStatus = if (saved) SaveStatus.Saved else SaveStatus.Failed
                                if (saved) haptics.performConfirm() else haptics.performReject()
                            }
                        },
                        enabled = lines.isNotEmpty() && saveStatus != SaveStatus.Saving,
                        shape = YoinShapeTokens.Full,
                        modifier = Modifier
                            .weight(1f)
                            .height(56.dp),
                    ) {
                        AnimatedContent(
                            targetState = saveStatus,
                            transitionSpec = {
                                (
                                    YoinMotion.fadeIn(role = YoinMotionRole.Standard) togetherWith
                                        YoinMotion.fadeOut(role = YoinMotionRole.Standard)
                                    ) using SizeTransform(clip = false) { _, _ -> labelSizeSpec }
                            },
                            contentAlignment = Alignment.Center,
                            label = "lyricsShareSave",
                        ) { status ->
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    imageVector = if (status == SaveStatus.Saved) {
                                        YoinSymbols.DownloadDone
                                    } else {
                                        YoinSymbols.Download
                                    },
                                    contentDescription = null,
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = when (status) {
                                        SaveStatus.Idle -> "Save"
                                        SaveStatus.Saving -> "Saving…"
                                        SaveStatus.Saved -> "Saved"
                                        SaveStatus.Failed -> "Try again"
                                    },
                                    style = MaterialTheme.typography.labelLarge,
                                )
                            }
                        }
                    }
                }
                Button(
                    onClick = {
                        if (sharing) return@Button
                        sharing = true
                        haptics.performConfirm()
                        val layer = if (format == LyricsShareFormat.Card) cardLayer else storyLayer
                        scope.launch {
                            val shared = layer.exportImage()?.let { image ->
                                shareLyricsImage(context, image, caption = lyricsCredit(songTitle, artist))
                            } == true
                            sharing = false
                            if (shared) onShared() else onMessage("Couldn't share the image")
                        }
                    },
                    enabled = !sharing && lines.isNotEmpty(),
                    shape = YoinShapeTokens.Full,
                    modifier = Modifier
                        .weight(1f)
                        .height(56.dp),
                ) {
                    Icon(imageVector = YoinSymbols.Share, contentDescription = null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(text = "Share", style = MaterialTheme.typography.labelLarge)
                }
            }
        }
    }
}

/**
 * The exported image at its logical size, in the chosen [format] and [tone].
 * Text is laid out at font scale 1 (the image must not depend on the reader's
 * font size). With an [exportLayer], the content draws through it at the
 * format's fixed export size; [modifier] (e.g. a preview's fit) stays outside
 * the recording.
 */
@Composable
internal fun LyricsShareImage(
    format: LyricsShareFormat,
    tone: LyricsCardTone,
    lines: List<LyricLine>,
    passageStarts: Set<Int> = emptySet(),
    showTranslation: Boolean,
    songTitle: String,
    artist: String,
    coverArtUrl: String?,
    modifier: Modifier = Modifier,
    exportLayer: GraphicsLayer? = null,
) {
    val density = LocalDensity.current
    CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale = 1f)) {
        val logicalSize = when (format) {
            LyricsShareFormat.Card -> Modifier.width(LyricsCardDesignWidth)
            LyricsShareFormat.Story -> Modifier.size(LyricsStoryDesignSize)
        }
        Box(
            modifier = modifier
                .then(logicalSize)
                .then(if (exportLayer != null) Modifier.recordForExport(exportLayer, format) else Modifier),
        ) {
            // A tone change cross-fades the whole image (colour is an effect).
            AnimatedContent(
                targetState = tone,
                transitionSpec = {
                    YoinMotion.fadeIn(role = YoinMotionRole.Standard) togetherWith
                        YoinMotion.fadeOut(role = YoinMotionRole.Standard)
                },
                label = "lyricsShareTone",
            ) { shownTone ->
                val colors = shownTone.colors(MaterialTheme.colorScheme)
                when (format) {
                    LyricsShareFormat.Card -> LyricsShareCard(
                        lines = lines,
                        passageStarts = passageStarts,
                        showTranslation = showTranslation,
                        songTitle = songTitle,
                        artist = artist,
                        coverArtUrl = coverArtUrl,
                        colors = colors,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    LyricsShareFormat.Story -> LyricsShareStory(
                        lines = lines,
                        passageStarts = passageStarts,
                        showTranslation = showTranslation,
                        songTitle = songTitle,
                        artist = artist,
                        coverArtUrl = coverArtUrl,
                        colors = colors,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }
        }
    }
}

/**
 * The shareable card: the tone's container, cover + title + artist, the
 * picked lines in the rounded GSF cut the current lyric line uses, and the
 * Yoin mark.
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
    colors: LyricsCardColors = LyricsCardTone.Soft.colors(MaterialTheme.colorScheme),
) {
    val context = LocalContext.current
    val lineStyle = lyricsCardLineStyle(lines)
    Column(
        modifier = modifier
            .testTag(LyricsShareCardTag)
            .clip(YoinContainerShapes.Panel)
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
                        translationY = -MonochromeGlyphLift.toPx()
                    },
            )
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                text = "Yoin",
                style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold),
                color = colors.content,
            )
        }
    }
}

/**
 * The 9:16 story: the tone's backdrop, full bleed, with the card centred in
 * the band stories leave clear of their own top and bottom chrome. A long
 * pick zooms the card out (see [zoomOutToFit]) rather than ever cutting a line.
 */
@Composable
internal fun LyricsShareStory(
    lines: List<LyricLine>,
    passageStarts: Set<Int> = emptySet(),
    showTranslation: Boolean,
    songTitle: String,
    artist: String,
    coverArtUrl: String?,
    colors: LyricsCardColors,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .background(colors.backdrop)
            .padding(horizontal = StoryHorizontalMargin, vertical = StoryVerticalMargin),
        contentAlignment = Alignment.Center,
    ) {
        LyricsShareCard(
            lines = lines,
            passageStarts = passageStarts,
            showTranslation = showTranslation,
            songTitle = songTitle,
            artist = artist,
            coverArtUrl = coverArtUrl,
            colors = colors,
            modifier = Modifier.zoomOutToFit(),
        )
    }
}

/** Fewer, shorter lines read large; a long pick steps the size down. */
@Composable
private fun lyricsCardLineStyle(lines: List<LyricLine>): TextStyle =
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
private fun LyricsShareFormatToggle(
    selected: LyricsShareFormat,
    onSelect: (LyricsShareFormat) -> Unit,
    modifier: Modifier = Modifier,
) {
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
                            label = format.label,
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
            .semantics { selected = isSelected },
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

/** The four tones as swatches of the card colour; the picked one squares off and wears a check. */
@Composable
private fun LyricsCardToneSwatches(
    selected: LyricsCardTone,
    onSelect: (LyricsCardTone) -> Unit,
    modifier: Modifier = Modifier,
) {
    val scheme = MaterialTheme.colorScheme
    Row(
        modifier = modifier.selectableGroup(),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        LyricsCardTone.entries.forEach { tone ->
            LyricsCardToneSwatch(
                colors = tone.colors(scheme),
                label = "${tone.label} colours",
                selected = tone == selected,
                onClick = { onSelect(tone) },
            )
        }
    }
}

@Composable
private fun LyricsCardToneSwatch(
    colors: LyricsCardColors,
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val corner by animateDpAsState(
        targetValue = if (selected) SwatchSelectedCorner else SwatchSize / 2,
        animationSpec = YoinMotion.defaultSpatialSpec(role = YoinMotionRole.Expressive),
        label = "toneSwatchCorner",
    )
    Box(
        modifier = Modifier
            .size(SwatchTouchSize)
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
                    drawRoundRect(
                        color = colors.card,
                        cornerRadius = CornerRadius(corner.toPx().coerceAtLeast(0f)),
                    )
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
): Modifier = layout { measurable, constraints ->
    val placeable = measurable.measure(Constraints())
    val heightCap = if (maxHeight == Dp.Infinity) {
        constraints.maxHeight
    } else {
        min(constraints.maxHeight, maxHeight.roundToPx())
    }
    val scale = lyricsShareFitScale(placeable.width, placeable.height, constraints.maxWidth, heightCap)
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
 * Fills the incoming width and fits the incoming height by ZOOMING OUT: the
 * content is laid out wider (width / zoom, so lines wrap less) and drawn back
 * at `zoom`, the largest zoom whose height fits ([lyricsStoryZoom], probed
 * through intrinsic heights). A plain shrink would narrow the card to a
 * sliver for a long pick; this keeps it the frame's width, only smaller type.
 */
private fun Modifier.zoomOutToFit(): Modifier = layout { measurable, constraints ->
    if (!constraints.hasBoundedWidth || !constraints.hasBoundedHeight) {
        val placeable = measurable.measure(constraints)
        return@layout layout(placeable.width, placeable.height) { placeable.place(0, 0) }
    }
    val frameWidth = constraints.maxWidth
    val frameHeight = constraints.maxHeight
    val zoom = lyricsStoryZoom { candidate ->
        val logicalWidth = (frameWidth / candidate).roundToInt()
        measurable.minIntrinsicHeight(logicalWidth) * candidate <= frameHeight
    }
    val logicalWidth = (frameWidth / zoom).roundToInt()
    val placeable = measurable.measure(Constraints(minWidth = logicalWidth, maxWidth = logicalWidth))
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
 * The largest zoom in [[LyricsStoryMinZoom], 1] for which [fits] holds
 * (fits is monotonic: a smaller zoom is a wider, shorter layout drawn
 * smaller). 1 when the content fits as is; [LyricsStoryMinZoom] when nothing does.
 */
internal fun lyricsStoryZoom(fits: (zoom: Float) -> Boolean): Float {
    if (fits(1f)) return 1f
    var low = LyricsStoryMinZoom
    var high = 1f
    repeat(StoryZoomSearchSteps) {
        val mid = (low + high) / 2f
        if (fits(mid)) low = mid else high = mid
    }
    return low
}

/** Furthest a story zooms out: below this the type is too small to read on a phone. */
internal const val LyricsStoryMinZoom = 0.4f
private const val StoryZoomSearchSteps = 8

/**
 * Records the content into [layer] at the format's export size (content
 * scaled up to it), then draws that same recording scaled back down — so the
 * preview and the PNG can't differ.
 */
private fun Modifier.recordForExport(layer: GraphicsLayer, format: LyricsShareFormat): Modifier =
    drawWithContent {
        if (size.width <= 0f || size.height <= 0f) {
            drawContent()
            return@drawWithContent
        }
        val export = lyricsShareExportSize(format, size.width, size.height)
        val exportScale = export.width / size.width
        layer.record(size = export) {
            scale(exportScale, pivot = Offset.Zero) { this@drawWithContent.drawContent() }
        }
        scale(1f / exportScale, pivot = Offset.Zero) { drawLayer(layer) }
    }

/** The layer's current recording as a bitmap; null before it has drawn once. */
private suspend fun GraphicsLayer.exportImage(): ImageBitmap? = try {
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
private suspend fun shareLyricsImage(context: Context, image: ImageBitmap, caption: String?): Boolean = try {
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
private suspend fun saveLyricsImage(context: Context, image: ImageBitmap, displayName: String): Boolean = try {
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
private const val ShareFilePrefix = "yoin-lyrics-"
private const val GalleryFolder = "Yoin"
private const val FileNameTitleMaxChars = 48

/** Artist line on the card. */
private const val CardSecondaryAlpha = 0.72f

/** Translation lines on the card. */
private const val CardTranslationAlpha = 0.76f

/** Between picked lines; a new passage (the pick skipped lines) opens a stanza-sized gap. */
private val CardLineGap = 6.dp
private val CardPassageGap = 20.dp

/** The story's card sits clear of the top / bottom chrome stories draw over the image. */
private val StoryHorizontalMargin = 28.dp
private val StoryVerticalMargin = 72.dp

/** Previews never take more than this share of the window, so the actions stay in reach. */
private const val PreviewMaxHeightFraction = 0.5f

private val SwatchSize = 36.dp
private val SwatchTouchSize = 48.dp
private val SwatchSelectedCorner = 10.dp

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
            tone = LyricsCardTone.Vivid,
            lines = PreviewLines,
            showTranslation = true,
            songTitle = "That's So True",
            artist = "Gracie Abrams",
            coverArtUrl = null,
        )
    }
}

/** The themed icon's glyph spans ~58% of its 108dp canvas: this scale fills the mark's slot with it. */
private const val MonochromeGlyphScale = 1.7f

/** Its glyph sits a little below the canvas centre: lift it back level with the "Yoin" label. */
private val MonochromeGlyphLift = 1.5.dp
