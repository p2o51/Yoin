package com.gpo.yoin.ui.nowplaying

import android.content.res.Resources
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.onLongClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.gpo.yoin.R
import com.gpo.yoin.data.local.SongAboutEntry
import com.gpo.yoin.data.model.LyricCredit
import com.gpo.yoin.data.model.LyricCreditRole
import com.gpo.yoin.data.model.LyricCredits
import com.gpo.yoin.ui.component.ThinkingDots
import com.gpo.yoin.ui.component.markdownBoldAnnotatedString
import com.gpo.yoin.ui.theme.YoinMotion
import com.gpo.yoin.ui.theme.YoinMotionRole
import kotlinx.coroutines.delay

/*
 * Now Playing's About, reorganised (owner 2026-11-05): the short facts read like a receipt, one line each
 * with a dashed rule between, the label small on the left and the value on the right. Every fact the
 * Subsonic server gives is on it; from the lyrics' credits only the four headline roles, the rest behind
 * "more"; copyright notices small at the foot. Gemini's review, its answers and the listener's own entries
 * follow as paragraphs. Collapsed (the pager's middle page) it is a carousel of the same.
 */

/** One line of the receipt. [entry] is the Gemini row behind it (long press, regenerate); null otherwise. */
@Immutable
internal data class ReceiptRow(val label: String, val value: String, val entry: SongAboutEntry? = null)

/** One paragraph under the receipt: the review, a Gemini answer, or the listener's own entry. */
@Immutable
internal data class AboutParagraph(val label: String, val text: String, val entry: SongAboutEntry)

/** The labels the receipt needs on top of the spine's, so the rows stay a pure function. */
internal interface AboutLabels : SpineLabels {
    val arranger: String
    val duration: String
    val channels: String
    fun channelCount(count: Int): String
    val size: String
    fun megabytes(value: String): String
    val bpm: String
    val playCount: String
    val added: String
    val comment: String
    val file: String
}

/**
 * The receipt, in order: who wrote it (the lyrics' credits first, then the server's composer, then
 * Gemini), when and where (Gemini), then everything the Subsonic server says.
 */
internal fun aboutReceiptRows(
    entries: List<SongAboutEntry>,
    credits: LyricCredits?,
    facts: SpineSourceFacts?,
    labels: AboutLabels,
): List<ReceiptRow> {
    val canonical = entries.filter { it.kind == SongAboutEntry.KIND_CANONICAL }.associateBy { it.entryKey }
    val rows = mutableListOf<ReceiptRow>()
    fun gemini(key: String) {
        val row = canonical[key] ?: return
        val value = row.answerText.oneLine() ?: return
        rows += ReceiptRow(labels.about(key), value, row)
    }
    fun add(label: String, value: String?) {
        value?.oneLine()?.let { rows += ReceiptRow(label, it) }
    }
    fun credited(key: String, role: LyricCreditRole, fallback: String? = null) {
        val names = credits?.namesFor(role) ?: fallback
        if (names != null) add(labels.about(key), names) else gemini(key)
    }
    credited(SongAboutEntry.CANON_LYRICIST, LyricCreditRole.Lyricist)
    credited(SongAboutEntry.CANON_COMPOSER, LyricCreditRole.Composer, facts?.composer)
    add(labels.arranger, credits?.namesFor(LyricCreditRole.Arranger))
    credited(SongAboutEntry.CANON_PRODUCER, LyricCreditRole.Producer)
    gemini(SongAboutEntry.CANON_CREATION_TIME)
    gemini(SongAboutEntry.CANON_CREATION_LOCATION)
    if (facts != null) {
        add(labels.genre, facts.genre)
        add(labels.year, facts.year?.toString())
        facts.trackNumber?.let { track ->
            val disc = facts.discNumber
            add(labels.track, if (disc != null && disc > 1) labels.discTrack(disc, track) else track.toString())
        }
        add(labels.duration, facts.durationSec?.let(::formatDuration))
        val quality = listOfNotNull(
            facts.format,
            facts.bitDepth?.let(labels::bit),
            facts.samplingRateHz?.let { hz -> labels.khz(formatKhz(hz)) },
            facts.bitRateKbps?.let(labels::kbps),
        )
        if (quality.isNotEmpty()) add(labels.quality, quality.joinToString("  "))
        add(labels.channels, facts.channels?.let(labels::channelCount))
        add(labels.size, facts.sizeBytes?.let { labels.megabytes(formatMegabytes(it)) })
        add(labels.bpm, facts.bpm?.toString())
        add(labels.playCount, facts.playCount?.toString())
        facts.serverRating?.let { stars ->
            add(labels.serverRating, "★".repeat(stars) + "☆".repeat(5 - stars))
        }
        add(labels.added, facts.added?.take(10))
        add(labels.comment, facts.comment)
        add(labels.file, facts.path)
    }
    return rows
}

/** Gemini's review first, then its answers and the listener's own entries, newest first. */
internal fun aboutParagraphs(entries: List<SongAboutEntry>, reviewLabel: String): List<AboutParagraph> {
    val review = entries.firstOrNull {
        it.kind == SongAboutEntry.KIND_CANONICAL && it.entryKey == SongAboutEntry.CANON_REVIEW &&
            it.answerText.isNotBlank()
    }
    val asks = entries.filter {
        (it.kind == SongAboutEntry.KIND_ASK || it.kind == SongAboutEntry.KIND_OWN) && it.answerText.isNotBlank()
    }
    return buildList {
        review?.let { add(AboutParagraph(reviewLabel, it.answerText.trim(), it)) }
        asks.forEach { row ->
            // Rows written before titles existed fall back to the question so history stays readable.
            val heading = row.titleText?.takeIf { it.isNotBlank() } ?: row.promptText.orEmpty()
            add(AboutParagraph(heading, row.answerText.trim(), row))
        }
    }
}

private fun String.oneLine(): String? = replace(Regex("\\s+"), " ").trim().takeIf { it.isNotEmpty() }

/** 245 → "4:05". */
internal fun formatDuration(seconds: Int): String {
    val h = seconds / 3600
    val m = seconds % 3600 / 60
    val s = seconds % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}

/** 9_437_184 → "9.0". */
internal fun formatMegabytes(bytes: Long): String = "%.1f".format(bytes / 1_048_576.0)

internal fun resourceAboutLabels(resources: Resources): AboutLabels {
    val spine = resourceSpineLabels(resources)
    return object : AboutLabels, SpineLabels by spine {
        override val arranger = resources.getString(R.string.np_about_arranger)
        override val duration = resources.getString(R.string.np_about_duration)
        override val channels = resources.getString(R.string.np_about_channels)
        override fun channelCount(count: Int) = when (count) {
            1 -> resources.getString(R.string.np_about_mono)
            2 -> resources.getString(R.string.np_about_stereo)
            else -> resources.getString(R.string.np_about_channel_count, count)
        }
        override val size = resources.getString(R.string.np_about_size)
        override fun megabytes(value: String) = resources.getString(R.string.np_about_megabytes, value)
        override val bpm = resources.getString(R.string.np_about_bpm)
        override val playCount = resources.getString(R.string.np_about_play_count)
        override val added = resources.getString(R.string.np_about_added)
        override val comment = resources.getString(R.string.np_about_comment)
        override val file = resources.getString(R.string.np_about_file)
    }
}

// ---------------------------------------------------------------------------
// The receipt.
// ---------------------------------------------------------------------------

/**
 * The receipt card. [credits] give the "more" roles and the notices; [thinking] marks a Gemini row being
 * written again; [onLongPress] opens a Gemini row's sheet (null: rows don't respond).
 */
@Composable
internal fun AboutReceipt(
    rows: List<ReceiptRow>,
    credits: LyricCredits?,
    modifier: Modifier = Modifier,
    thinking: (SongAboutEntry) -> Boolean = { false },
    onLongPress: ((ReceiptRow) -> Unit)? = null,
) {
    val others = credits?.others.orEmpty()
    val notices = credits?.notices.orEmpty()
    if (rows.isEmpty() && others.isEmpty() && notices.isEmpty()) return
    var expanded by rememberSaveable(credits) { mutableStateOf(false) }
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier
                .padding(horizontal = 16.dp, vertical = 6.dp)
                .animateContentSize(YoinMotion.defaultSpatialSpec()),
        ) {
            rows.forEachIndexed { i, row ->
                if (i > 0) DashedRule()
                ReceiptLine(
                    label = row.label,
                    value = row.value,
                    thinking = row.entry?.let(thinking) == true,
                    onLongPress = if (row.entry != null) onLongPress?.let { press -> { press(row) } } else null,
                )
            }
            if (others.isNotEmpty()) {
                if (rows.isNotEmpty()) DashedRule()
                if (expanded) {
                    others.forEach { credit ->
                        OtherCreditLine(credit)
                        DashedRule()
                    }
                }
                val noteLabel = stringResource(R.string.cmp_lyric_credits_note)
                Text(
                    text = if (expanded) {
                        stringResource(R.string.cmp_lyric_credits_less)
                    } else {
                        stringResource(
                            R.string.cmp_lyric_credits_more,
                            others.size,
                            others.take(3).joinToString("、") { it.role.ifEmpty { noteLabel } } + "…",
                        )
                    },
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(role = Role.Button) { expanded = !expanded }
                        .padding(vertical = 10.dp),
                )
            }
            if (notices.isNotEmpty()) {
                DashedRule()
                Column(
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                    modifier = Modifier.padding(vertical = 10.dp),
                ) {
                    notices.forEach { notice ->
                        Text(
                            text = notice,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ReceiptLine(
    label: String,
    value: String,
    thinking: Boolean = false,
    onLongPress: (() -> Unit)? = null,
) {
    Row(
        verticalAlignment = Alignment.Top,
        modifier = Modifier
            .fillMaxWidth()
            .longPressable(label, onLongPress)
            .padding(vertical = 8.dp),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier
                .width(ReceiptLabelWidth)
                .padding(top = 2.dp, end = 12.dp),
        )
        if (thinking) {
            Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.CenterEnd) {
                ThinkingDots(color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(vertical = 6.dp))
            }
        } else {
            Text(
                text = markdownBoldAnnotatedString(value),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
                textAlign = TextAlign.End,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun OtherCreditLine(credit: LyricCredit) {
    if (credit.role.isEmpty()) {
        Text(
            text = credit.names,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(vertical = 8.dp),
        )
    } else {
        ReceiptLine(label = credit.role, value = credit.names)
    }
}

/** The receipt's dashed rule between lines. */
@Composable
internal fun DashedRule(modifier: Modifier = Modifier, color: Color = MaterialTheme.colorScheme.outlineVariant) {
    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .height(1.dp),
    ) {
        val dash = 4.dp.toPx()
        drawLine(
            color = color,
            start = Offset(0f, size.height / 2),
            end = Offset(size.width, size.height / 2),
            strokeWidth = size.height,
            pathEffect = PathEffect.dashPathEffect(floatArrayOf(dash, dash * 0.75f)),
        )
    }
}

/** One paragraph section: a small heading and its text. */
@Composable
internal fun AboutParagraphSection(
    paragraph: AboutParagraph,
    modifier: Modifier = Modifier,
    thinking: Boolean = false,
    onLongPress: (() -> Unit)? = null,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .longPressable(paragraph.label, onLongPress)
            .padding(vertical = 14.dp),
    ) {
        Text(
            text = paragraph.label,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
        )
        if (thinking) {
            ThinkingDots(
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(top = 10.dp, bottom = 6.dp),
            )
        } else {
            Text(
                text = markdownBoldAnnotatedString(paragraph.text),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.padding(top = 6.dp),
            )
        }
    }
}

@Composable
private fun Modifier.longPressable(label: String, onLongPress: (() -> Unit)?): Modifier {
    if (onLongPress == null) return this
    val menuLabel = stringResource(R.string.np_about_cd_entry_menu)
    val current by rememberUpdatedState(onLongPress)
    return this
        .pointerInput(label) { detectTapGestures(onLongPress = { current() }) }
        .semantics {
            onLongClick(label = menuLabel) {
                current()
                true
            }
        }
}

private val ReceiptLabelWidth = 96.dp

// ---------------------------------------------------------------------------
// The collapsed carousel.
// ---------------------------------------------------------------------------

/** One page of the collapsed About: a few receipt lines, or one paragraph. */
@Immutable
internal sealed interface AboutCarouselPage {
    data class Lines(val rows: List<ReceiptRow>) : AboutCarouselPage
    data class Paragraph(val paragraph: AboutParagraph) : AboutCarouselPage
}

internal fun aboutCarouselPages(rows: List<ReceiptRow>, paragraphs: List<AboutParagraph>): List<AboutCarouselPage> =
    rows.chunked(CarouselLinesPerPage).map(AboutCarouselPage::Lines) +
        paragraphs.map(AboutCarouselPage::Paragraph)

private const val CarouselLinesPerPage = 3
private const val CarouselDwellMs = 7_000L
private const val CarouselMaxDots = 10

/**
 * The collapsed About: [pages] one at a time, turning on their own; a tap turns to the next. [songKey]
 * starts it over on a new song.
 */
@Composable
internal fun AboutCarousel(
    pages: List<AboutCarouselPage>,
    songKey: String,
    modifier: Modifier = Modifier,
) {
    if (pages.isEmpty()) return
    var index by remember(songKey) { mutableIntStateOf(0) }
    val current = index.mod(pages.size)
    LaunchedEffect(songKey, current, pages.size) {
        if (pages.size < 2) return@LaunchedEffect
        delay(CarouselDwellMs)
        index += 1
    }
    Column(
        modifier = modifier.clickable(
            interactionSource = remember { MutableInteractionSource() },
            indication = null,
            role = Role.Button,
        ) { index += 1 },
    ) {
        AnimatedContent(
            targetState = current,
            transitionSpec = {
                YoinMotion.fadeIn(role = YoinMotionRole.Standard) togetherWith
                    YoinMotion.fadeOut(role = YoinMotionRole.Standard)
            },
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
            label = "aboutCarousel",
        ) { shown ->
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.CenterStart) {
                when (val page = pages.getOrNull(shown)) {
                    is AboutCarouselPage.Lines -> Column {
                        page.rows.forEachIndexed { i, row ->
                            if (i > 0) DashedRule()
                            ReceiptLine(label = row.label, value = row.value)
                        }
                    }
                    is AboutCarouselPage.Paragraph -> Column {
                        Text(
                            text = page.paragraph.label,
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.primary,
                        )
                        Text(
                            text = markdownBoldAnnotatedString(page.paragraph.text),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 6,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(top = 6.dp),
                        )
                    }
                    null -> Unit
                }
            }
        }
        if (pages.size > CarouselMaxDots) {
            Text(
                text = "${current + 1} / ${pages.size}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 8.dp),
            )
        } else if (pages.size > 1) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier.padding(top = 8.dp),
            ) {
                pages.indices.forEach { i ->
                    Box(
                        modifier = Modifier
                            .size(if (i == current) 6.dp else 4.dp)
                            .align(Alignment.CenterVertically)
                            .clip(CircleShape)
                            .background(
                                if (i == current) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    MaterialTheme.colorScheme.outlineVariant
                                },
                            ),
                    )
                }
            }
        }
    }
}
