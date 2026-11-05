package com.gpo.yoin.ui.nowplaying

import android.content.res.Resources
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.gpo.yoin.R
import com.gpo.yoin.data.local.SongAboutEntry
import com.gpo.yoin.data.model.LyricCreditRole
import com.gpo.yoin.data.model.LyricCredits
import com.gpo.yoin.data.model.MediaId
import com.gpo.yoin.data.model.Track
import com.gpo.yoin.ui.component.codecLabel
import com.gpo.yoin.ui.component.horizontalFadeMask
import com.gpo.yoin.ui.experience.LocalMotionProfile
import com.gpo.yoin.ui.experience.MotionProfile
import com.gpo.yoin.ui.theme.YoinMotion
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/*
 * The spine (书脊, owner 2026-11-03): the band between a height-capped cover
 * and the rating bar on short windows (Fold outer screen, 16:9). It reads the
 * song like a record's spine, one fact at a time, turned a quarter round:
 * the About credits from the cache (never a Gemini request), and on Subsonic
 * the server's own facts. It rotates on its own with the one-line lyric's
 * change, very faint; a swipe across it steps, damped; a fact longer than the
 * band pans along it before the next. Never cut: the band's ends fade.
 */

/** One fact on the spine: a small label and its value. A blank label is the fallback line. */
@Immutable
internal data class SpineItem(val label: String, val value: String)

/** What a Subsonic server says about the song itself, for the spine. Null fields are absent. */
@Immutable
data class SpineSourceFacts(
    val format: String? = null,
    val bitRateKbps: Int? = null,
    val samplingRateHz: Int? = null,
    val bitDepth: Int? = null,
    /** The server's own 1–5 rating, not Yoin's. */
    val serverRating: Int? = null,
    val genre: String? = null,
    val year: Int? = null,
    val trackNumber: Int? = null,
    val discNumber: Int? = null,
)

/** The spine's Subsonic facts for [this] track; null for every other provider. */
internal fun Track.spineSourceFacts(): SpineSourceFacts? {
    if (id.provider != MediaId.PROVIDER_SUBSONIC) return null
    return SpineSourceFacts(
        format = audioFormat?.codec?.let(::codecLabel),
        bitRateKbps = audioFormat?.bitRateKbps,
        samplingRateHz = audioFormat?.sampleRateHz,
        bitDepth = audioFormat?.bitDepth,
        serverRating = userRating?.takeIf { it in 1..5 },
        genre = genre?.takeIf { it.isNotBlank() },
        year = year?.takeIf { it > 0 },
        trackNumber = trackNumber?.takeIf { it > 0 },
        discNumber = discNumber,
    )
}

/** The About credits the spine reads, in the About tab's order (the review is too long for it). */
private val SpineCreditKeys = listOf(
    SongAboutEntry.CANON_CREATION_TIME,
    SongAboutEntry.CANON_CREATION_LOCATION,
    SongAboutEntry.CANON_LYRICIST,
    SongAboutEntry.CANON_COMPOSER,
    SongAboutEntry.CANON_PRODUCER,
)

/** The labels the spine needs, so the item list stays a pure function. */
internal interface SpineLabels {
    fun about(entryKey: String): String
    val quality: String
    val serverRating: String
    val genre: String
    val year: String
    val track: String
    fun discTrack(disc: Int, track: Int): String
    fun kbps(value: Int): String
    fun khz(value: String): String
    fun bit(value: Int): String
}

/**
 * Everything the spine shows, in order: the cached About credits, then the
 * Subsonic facts; when there is neither, one line of album, artist and title.
 */
internal fun spineItems(
    entries: List<SongAboutEntry>,
    facts: SpineSourceFacts?,
    albumName: String,
    artist: String,
    title: String,
    labels: SpineLabels,
    /** The lyrics source's credits: they answer lyricist / composer / producer before Gemini does. */
    credits: LyricCredits? = null,
): List<SpineItem> {
    val items = mutableListOf<SpineItem>()
    SpineCreditKeys.forEach { key ->
        (
            key.lyricCreditRole()?.let { credits?.namesFor(it) }
                ?: entries
                    .firstOrNull { it.kind == SongAboutEntry.KIND_CANONICAL && it.entryKey == key }
                    ?.answerText
            )
            ?.replace(Regex("\\s+"), " ")
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?.let { items += SpineItem(labels.about(key), it) }
    }
    if (facts != null) {
        val quality = listOfNotNull(
            facts.format,
            facts.bitDepth?.let(labels::bit),
            facts.samplingRateHz?.let { hz -> labels.khz(formatKhz(hz)) },
            facts.bitRateKbps?.let(labels::kbps),
        )
        if (quality.isNotEmpty()) items += SpineItem(labels.quality, quality.joinToString("  "))
        facts.serverRating?.let { stars ->
            items += SpineItem(labels.serverRating, "★".repeat(stars) + "☆".repeat(5 - stars))
        }
        facts.genre?.let { items += SpineItem(labels.genre, it) }
        facts.year?.let { items += SpineItem(labels.year, it.toString()) }
        facts.trackNumber?.let { track ->
            val disc = facts.discNumber
            items += SpineItem(
                labels.track,
                if (disc != null && disc > 1) labels.discTrack(disc, track) else track.toString(),
            )
        }
    }
    if (items.isEmpty()) {
        val line = listOf(albumName, artist, title).filter(String::isNotBlank).joinToString("   ")
        if (line.isNotEmpty()) items += SpineItem("", line)
    }
    return items
}

private fun String.lyricCreditRole(): LyricCreditRole? = when (this) {
    SongAboutEntry.CANON_LYRICIST -> LyricCreditRole.Lyricist
    SongAboutEntry.CANON_COMPOSER -> LyricCreditRole.Composer
    SongAboutEntry.CANON_PRODUCER -> LyricCreditRole.Producer
    else -> null
}

/** 44100 → "44.1", 96000 → "96". */
internal fun formatKhz(hz: Int): String {
    val tenths = (hz / 100.0).roundToInt()
    return if (tenths % 10 == 0) (tenths / 10).toString() else "${tenths / 10}.${tenths % 10}"
}

internal fun resourceSpineLabels(resources: Resources): SpineLabels = object : SpineLabels {
    override fun about(entryKey: String) = labelFor(entryKey, resources)
    override val quality = resources.getString(R.string.np_spine_quality)
    override val serverRating = resources.getString(R.string.np_spine_server_rating)
    override val genre = resources.getString(R.string.np_spine_genre)
    override val year = resources.getString(R.string.np_spine_year)
    override val track = resources.getString(R.string.np_spine_track)
    override fun discTrack(disc: Int, track: Int) = resources.getString(R.string.np_spine_disc_track, disc, track)
    override fun kbps(value: Int) = resources.getString(R.string.np_spine_kbps, value)
    override fun khz(value: String) = resources.getString(R.string.np_spine_khz, value)
    override fun bit(value: Int) = resources.getString(R.string.np_spine_bit, value)
}

/** How long a fact rests before the next one comes in. */
private const val SpineDwellMs = 9_000L

/** A fact longer than the band waits this long, then pans along it at [SpinePanDpPerSecond]. */
private const val SpinePanDelayMs = 1_200L
private const val SpinePanDpPerSecond = 18f

/**
 * A swipe moves the fact at this share of the finger, never more than [SpineSwipeReach]
 * (the band is narrow), and steps past [SpineSwipeStep] of finger travel.
 */
private const val SpineSwipeDamping = 0.15f
private val SpineSwipeReach = 14.dp
private val SpineSwipeStep = 32.dp

/** The band's ends fade over this much; nothing is cut. */
private val SpineFadeEdge = 20.dp

/**
 * The spine itself: [items] one at a time, laid out [length] long (the cover's
 * side) and turned a quarter round so they read bottom to top. [songKey]
 * restarts it at the first fact on a new song.
 */
@Composable
internal fun NowPlayingSpine(
    items: List<SpineItem>,
    songKey: String,
    length: Dp,
    modifier: Modifier = Modifier,
) {
    if (items.isEmpty()) return
    val reduced = LocalMotionProfile.current == MotionProfile.AdaptiveReduced
    var index by remember(songKey) { mutableIntStateOf(0) }
    val current = index.mod(items.size)
    var dragging by remember { mutableStateOf(false) }
    val drag = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    val stepPx = with(density) { SpineSwipeStep.toPx() }
    val reachPx = with(density) { SpineSwipeReach.toPx() }
    val settleSpec = YoinMotion.defaultSpatialSpec<Float>()
    // Rest, pan if it is long (the item reports its own pan time), rest, next.
    var panMs by remember(songKey, current) { mutableStateOf(0L) }
    LaunchedEffect(songKey, current, items.size, dragging, panMs) {
        if (dragging || items.size < 2) return@LaunchedEffect
        delay(SpineDwellMs + panMs)
        index += 1
    }
    Box(
        modifier = modifier
            .clipToBounds()
            .pointerInput(songKey, items.size) {
                var travel = 0f
                detectHorizontalDragGestures(
                    onDragStart = {
                        travel = 0f
                        dragging = true
                    },
                    onDragEnd = {
                        dragging = false
                        if (abs(travel) >= stepPx && items.size > 1) {
                            // Read the index now, not the one this gesture block was built with.
                            index += if (travel < 0f) 1 else -1
                        }
                        scope.launch { drag.animateTo(0f, settleSpec) }
                    },
                    onDragCancel = {
                        dragging = false
                        scope.launch { drag.animateTo(0f, settleSpec) }
                    },
                ) { change, amount ->
                    change.consume()
                    travel += amount
                    scope.launch { drag.snapTo((travel * SpineSwipeDamping).coerceIn(-reachPx, reachPx)) }
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        AnimatedContent(
            targetState = SpineFrame(songKey, current, items[current]),
            contentKey = { it.songKey to it.index },
            transitionSpec = { oneLineLyricLanding() using SizeTransform(clip = false) },
            contentAlignment = Alignment.Center,
            label = "nowPlayingSpine",
            modifier = Modifier
                .requiredWidth(length)
                .graphicsLayer {
                    rotationZ = -90f
                    // The layer's translation is in screen space: the finger's axis, across the band.
                    translationX = drag.value
                }
                .horizontalFadeMask(edgeWidth = SpineFadeEdge),
        ) { frame ->
            SpineLine(
                item = frame.item,
                length = length,
                pan = !reduced,
                onPanMs = { if (frame.index == current) panMs = it },
            )
        }
    }
}

private data class SpineFrame(val songKey: String, val index: Int, val item: SpineItem)

/** One fact: the label quiet, the value in the line's ink; pans along the band when it is longer. */
@Composable
private fun SpineLine(item: SpineItem, length: Dp, pan: Boolean, onPanMs: (Long) -> Unit) {
    val density = LocalDensity.current
    val lengthPx = with(density) { (length - SpineFadeEdge * 2).toPx() }
    var overflowPx by remember(item) { mutableStateOf(0f) }
    val offset = remember(item) { Animatable(0f) }
    LaunchedEffect(item, overflowPx, pan) {
        offset.snapTo(0f)
        if (!pan || overflowPx <= 0f) {
            onPanMs(0L)
            return@LaunchedEffect
        }
        val ms = (overflowPx / with(density) { SpinePanDpPerSecond.dp.toPx() } * 1000f).toLong()
        onPanMs(SpinePanDelayMs + ms)
        delay(SpinePanDelayMs)
        offset.animateTo(-overflowPx, tween(ms.toInt(), easing = LinearEasing))
    }
    val ink = MaterialTheme.colorScheme.onSurfaceVariant
    Text(
        text = buildAnnotatedString {
            if (item.label.isNotEmpty()) {
                withStyle(SpanStyle(color = ink.copy(alpha = 0.6f), fontWeight = FontWeight.Normal)) {
                    append(item.label)
                }
                append("  ")
            }
            append(item.value)
        },
        style = MaterialTheme.typography.labelLarge,
        color = ink,
        maxLines = 1,
        softWrap = false,
        modifier = Modifier
            .requiredWidth(length)
            .wrapContentWidth(Alignment.Start, unbounded = true)
            .onSizeChanged { overflowPx = (it.width - lengthPx).coerceAtLeast(0f) }
            .graphicsLayer { translationX = with(density) { SpineFadeEdge.toPx() } + offset.value },
    )
}

/** Labels from resources, remembered per configuration. */
@Composable
internal fun rememberSpineLabels(): SpineLabels {
    val resources = LocalContext.current.resources
    return remember(resources) { resourceSpineLabels(resources) }
}
