package com.gpo.yoin.ui.spotoolfy

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.pager.VerticalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.MotionDurationScale
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.Placeable
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import com.gpo.yoin.R
import com.gpo.yoin.data.migration.spotoolfy.RecapKeyword
import com.gpo.yoin.data.migration.spotoolfy.RecapLyric
import com.gpo.yoin.data.migration.spotoolfy.RecapNote
import com.gpo.yoin.data.migration.spotoolfy.SpotoolfyRecap
import com.gpo.yoin.symbols.YoinSymbols
import com.gpo.yoin.ui.experience.YoinHaptics
import com.gpo.yoin.ui.experience.rememberYoinHaptics
import com.gpo.yoin.ui.experience.voteHighFrameRate
import com.gpo.yoin.ui.landing.guide.SpotoolfyIconTeal
import com.gpo.yoin.ui.theme.YoinMotion
import com.gpo.yoin.ui.theme.YoinMotionRole
import com.gpo.yoin.ui.theme.YoinMotionSpeed
import com.gpo.yoin.ui.theme.YoinTheme
import java.text.DateFormat
import java.text.NumberFormat
import java.util.Date
import kotlin.math.roundToInt
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch

// The recap's two pages (docs/spotoolfy-migration.md §6.2): the cover wall with the note count, then the keyword
// burst with up to two lyric snippets and the card that leads on (join Yoin from the landing, Done from Settings).
// Every move is a spring; per-frame values are read only in graphicsLayer. The burst plays when its page settles,
// and again each time it comes back.

@Composable
internal fun SpotoolfyRecapScreen(
    state: RecapUiState,
    fromSettings: Boolean,
    onClose: () -> Unit,
    onJoin: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val scheme = MaterialTheme.colorScheme
    val pager = rememberPagerState(pageCount = { RECAP_PAGES })
    Box(modifier = modifier.background(scheme.surface)) {
        when (state) {
            RecapUiState.Loading -> Unit
            RecapUiState.Missing -> Text(
                text = stringResource(R.string.spotoolfy_recap_missing),
                style = MaterialTheme.typography.bodyLarge,
                color = scheme.onSurfaceVariant,
                modifier = Modifier
                    .align(Alignment.Center)
                    .padding(24.dp),
            )
            is RecapUiState.Ready -> {
                val reduced = rememberReducedMotion()
                val haptics = rememberYoinHaptics()
                VerticalPager(state = pager, modifier = Modifier.fillMaxSize()) { page ->
                    val active = pager.settledPage == page
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .windowInsetsPadding(WindowInsets.statusBars)
                            .windowInsetsPadding(WindowInsets.navigationBars)
                            .padding(top = TopBarHeight),
                    ) {
                        when (page) {
                            0 -> IntroPage(state.recap, active, reduced, haptics)
                            else -> MinePage(state.recap, active, reduced, haptics, fromSettings, onJoin, onClose)
                        }
                    }
                }
                PageDots(
                    current = pager.currentPage,
                    modifier = Modifier
                        .align(Alignment.CenterEnd)
                        .padding(end = 6.dp),
                )
            }
        }
        RecapTopBar(
            page = pager.currentPage + 1,
            showPage = state is RecapUiState.Ready,
            onClose = onClose,
        )
    }
}

@Composable
private fun RecapTopBar(page: Int, showPage: Boolean, onClose: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .windowInsetsPadding(WindowInsets.statusBars)
            .height(TopBarHeight)
            .padding(start = 4.dp, end = 16.dp),
    ) {
        IconButton(onClick = onClose) {
            Icon(YoinSymbols.Close, contentDescription = stringResource(R.string.spotoolfy_recap_close))
        }
        Box(
            modifier = Modifier
                .size(8.dp)
                .background(SpotoolfyIconTeal, CircleShape),
        )
        Text(
            text = stringResource(R.string.spotoolfy_recap_title),
            style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
            color = scheme.onSurface,
            modifier = Modifier
                .padding(start = 8.dp)
                .weight(1f),
        )
        if (showPage) {
            Text(
                text = stringResource(R.string.spotoolfy_recap_page, page, RECAP_PAGES),
                style = MaterialTheme.typography.labelLarge,
                color = scheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun PageDots(current: Int, modifier: Modifier = Modifier) {
    val scheme = MaterialTheme.colorScheme
    Column(verticalArrangement = Arrangement.spacedBy(6.dp), modifier = modifier.clearAndSetSemantics { }) {
        repeat(RECAP_PAGES) { page ->
            Box(
                modifier = Modifier
                    .width(6.dp)
                    .height(if (page == current) 22.dp else 6.dp)
                    .background(if (page == current) scheme.primary else scheme.outlineVariant, CircleShape),
            )
        }
    }
}

// ── Page 1: the cover wall and the count ────────────────────────────────

@Composable
private fun ColumnScope.IntroPage(recap: SpotoolfyRecap, active: Boolean, reduced: Boolean, haptics: YoinHaptics) {
    val covers = remember(recap) { introCovers(recap.coverUrls) }
    val player = remember(covers.size) { BurstPlayer(covers.size) }
    val count = remember { Animatable(0f) }
    val shownCount = if (recap.noteCount > 0) recap.noteCount else recap.ratedTrackCount
    val moveSpec = YoinMotion.defaultSpatialSpec<Float>(role = YoinMotionRole.Expressive)
    val fadeSpec = YoinMotion.defaultEffectsSpec<Float>(role = YoinMotionRole.Expressive)
    LaunchedEffect(active) {
        if (!active) return@LaunchedEffect
        if (reduced) {
            count.snapTo(shownCount.toFloat())
        } else {
            count.snapTo(0f)
            launch { count.animateTo(shownCount.toFloat(), CountSpring) }
        }
        if (covers.isNotEmpty() && !reduced) haptics.performTick()
        player.play(this, reduced, moveSpec, fadeSpec) { i -> CoverStartMs + i * CoverStaggerMs }
    }
    WideOrTall(
        visual = { mod ->
            CoverWall(covers = covers, player = player, modifier = mod.voteHighFrameRate(player.running))
        },
        text = { mod -> IntroText(recap, shownCount, count, mod) },
    )
}

/** Up to [MAX_WALL] covers; a short list repeats so the wall still fills, and none at all gives colour tiles. */
private fun introCovers(urls: List<String>): List<String?> = when {
    urls.isEmpty() -> List(MIN_WALL) { null }
    urls.size < MIN_WALL -> List(MIN_WALL) { urls[it % urls.size] }
    else -> urls.take(MAX_WALL)
}

@Composable
private fun CoverWall(covers: List<String?>, player: BurstPlayer, modifier: Modifier) {
    val context = LocalContext.current
    val density = LocalDensity.current
    val tilePx = with(density) { CoverTile.roundToPx() }
    val tones = coverTones()
    Layout(
        modifier = modifier.clearAndSetSemantics { },
        content = {
            covers.forEachIndexed { i, url ->
                Box(
                    modifier = Modifier
                        .size(CoverTile)
                        .burstItem(player, i, spinDegrees = if (i % 2 == 0) -CoverSpin else CoverSpin)
                        .clip(RoundedCornerShape(12.dp))
                        .background(tones[i % tones.size]),
                ) {
                    if (url != null) {
                        AsyncImage(
                            model = remember(url) {
                                ImageRequest.Builder(context).data(url).size(CoverDecodePx, CoverDecodePx).build()
                            },
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                }
            }
        },
    ) { measurables, constraints ->
        val width = constraints.maxWidth
        val height = constraints.maxHeight
        val placeables = measurables.map { it.measure(Constraints.fixed(tilePx, tilePx)) }
        val columns = WALL_COLUMNS
        val rows = (placeables.size + columns - 1) / columns
        val gapX = if (columns > 1) (width - tilePx).toFloat() / (columns - 1) else 0f
        val spare = (height - tilePx).coerceAtLeast(0).toFloat()
        val gapY = if (rows > 1) minOf(tilePx * WALL_ROW_PITCH, spare / (rows - 1)) else 0f
        val top = (height - (rows - 1) * gapY - tilePx) / 2f
        val maxX = (width - tilePx).coerceAtLeast(0)
        val maxY = (height - tilePx).coerceAtLeast(0)
        layout(width, height) {
            placeables.forEachIndexed { i, placeable ->
                val column = i % columns
                val row = i / columns
                // A slight hand-placed scatter, the same on every layout.
                val jitterX = ((i * 37) % 21 - 10) * WALL_JITTER
                val jitterY = ((i * 53) % 15 - 7) * WALL_JITTER
                val x = (column * gapX + jitterX).roundToInt().coerceIn(0, maxX)
                val y = (top + row * gapY + jitterY).roundToInt().coerceIn(0, maxY)
                player.target(i, centerX = width / 2f, centerY = height / 2f, x + tilePx / 2f, y + tilePx / 2f)
                player.restRotation[i] = ((i * 29) % 17 - 8).toFloat()
                placeable.place(x, y)
            }
        }
    }
}

@Composable
private fun IntroText(recap: SpotoolfyRecap, shownCount: Int, count: Animatable<Float, *>, modifier: Modifier) {
    val scheme = MaterialTheme.colorScheme
    val locale = LocalConfiguration.current.locales[0]
    val numbers = remember(locale) { NumberFormat.getIntegerInstance(locale) }
    val dates = remember(locale) { DateFormat.getDateInstance(DateFormat.MEDIUM, locale) }
    Column(modifier = modifier.padding(horizontal = 24.dp)) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .clip(RoundedCornerShape(14.dp))
                .background(SpotoolfyIconTeal.copy(alpha = 0.18f))
                .padding(horizontal = 10.dp, vertical = 4.dp),
        ) {
            Box(Modifier.size(8.dp).background(SpotoolfyIconTeal, CircleShape))
            Text(
                text = stringResource(R.string.spotoolfy_recap_from),
                style = MaterialTheme.typography.labelMedium,
                color = scheme.onSurface,
                modifier = Modifier.padding(start = 6.dp),
            )
        }
        Text(
            text = stringResource(R.string.spotoolfy_recap_you_wrote),
            style = MaterialTheme.typography.labelLarge,
            color = scheme.primary,
            modifier = Modifier.padding(top = 12.dp),
        )
        Row(verticalAlignment = Alignment.Bottom) {
            CountText(count, numbers, shownCount)
            Text(
                text = if (recap.noteCount > 0) {
                    pluralStringResource(R.plurals.spotoolfy_recap_notes_unit, shownCount)
                } else {
                    pluralStringResource(R.plurals.spotoolfy_recap_ratings_unit, shownCount)
                },
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.SemiBold,
                color = scheme.onSurface,
                modifier = Modifier.padding(start = 8.dp, bottom = 14.dp),
            )
        }
        Text(
            text = statLine(recap),
            style = MaterialTheme.typography.bodyLarge,
            color = scheme.onSurface,
        )
        if (recap.firstRecordAt > 0L) {
            Text(
                text = stringResource(
                    R.string.spotoolfy_recap_range,
                    dates.format(Date(recap.firstRecordAt)),
                    dates.format(Date(recap.lastRecordAt)),
                ),
                style = MaterialTheme.typography.bodyMedium,
                color = scheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 16.dp, bottom = 12.dp)
                .clearAndSetSemantics { },
        ) {
            Icon(
                imageVector = YoinSymbols.ChevronDown,
                contentDescription = null,
                tint = scheme.onSurfaceVariant,
                modifier = Modifier
                    .size(18.dp)
                    .graphicsLayer { rotationZ = 180f },
            )
            Text(
                text = stringResource(R.string.spotoolfy_recap_swipe_up),
                style = MaterialTheme.typography.labelMedium,
                color = scheme.onSurfaceVariant,
            )
        }
    }
}

/** The big number counting up: its own scope, so only it recomposes while it counts. */
@Composable
private fun CountText(count: Animatable<Float, *>, numbers: NumberFormat, finalCount: Int) {
    Text(
        text = numbers.format(count.value.roundToInt()),
        style = MaterialTheme.typography.displayLarge.copy(fontSize = 88.sp, lineHeight = 92.sp),
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.onSurface,
        modifier = Modifier.semantics { contentDescription = numbers.format(finalCount) },
    )
}

/** "for 32 songs by 10 artists, every one of them rated": one sentence, never a dotted list. */
@Composable
private fun statLine(recap: SpotoolfyRecap): String {
    val songs = pluralStringResource(R.plurals.spotoolfy_recap_songs, recap.trackCount, recap.trackCount)
    val base = if (recap.artistCount > 0) {
        val artists = pluralStringResource(R.plurals.spotoolfy_recap_artists, recap.artistCount, recap.artistCount)
        stringResource(R.string.spotoolfy_recap_for_songs_by, songs, artists)
    } else {
        stringResource(R.string.spotoolfy_recap_for_songs, songs)
    }
    val rated = when {
        recap.ratedTrackCount <= 0 -> ""
        recap.ratedTrackCount >= recap.trackCount -> stringResource(R.string.spotoolfy_recap_all_rated)
        else -> pluralStringResource(R.plurals.spotoolfy_recap_some_rated, recap.ratedTrackCount, recap.ratedTrackCount)
    }
    return base + rated
}

// ── Page 2: keywords, lyrics, and what comes next ──────────────────────

/** What the page's detail card shows. */
private sealed interface RecapDetail {
    data class Word(val keyword: RecapKeyword) : RecapDetail

    data class Lyric(val lyric: RecapLyric) : RecapDetail

    data class Note(val note: RecapNote) : RecapDetail
}

@Composable
private fun ColumnScope.MinePage(
    recap: SpotoolfyRecap,
    active: Boolean,
    reduced: Boolean,
    haptics: YoinHaptics,
    fromSettings: Boolean,
    onJoin: () -> Unit,
    onClose: () -> Unit,
) {
    val words = remember(recap) { recapWords(recap.keywords, recap.emoji) }
    val lyrics = recap.lyricSnippets
    val notes = recap.fallbackNotes
    val sparse = notes.isNotEmpty() || words.isEmpty()
    // Lyric cards first, then the words or the note cards: one player for the whole area.
    val itemCount = lyrics.size + if (sparse) notes.size else words.size
    val player = remember(itemCount) { BurstPlayer(itemCount) }
    val join = remember { Animatable(0f) }
    var detail by remember { mutableStateOf<RecapDetail?>(null) }
    val moveSpec = YoinMotion.defaultSpatialSpec<Float>(role = YoinMotionRole.Expressive)
    val fadeSpec = YoinMotion.defaultEffectsSpec<Float>(role = YoinMotionRole.Expressive)
    val joinSpec = YoinMotion.defaultSpatialSpec<Float>()
    val stagger = if (sparse) NoteStaggerMs else WordStaggerMs
    LaunchedEffect(active) {
        if (!active) {
            detail = null
            return@LaunchedEffect
        }
        join.snapTo(if (reduced) 1f else 0f)
        if (itemCount > 0 && !reduced) haptics.performTick()
        val burstEnd = WordStartMs + (minOf(itemCount - lyrics.size, STAGGER_CAP) * stagger * LYRICS_AFTER).toLong()
        launch {
            delay(burstEnd + lyrics.size * LyricStaggerMs + JoinAfterMs)
            join.animateTo(1f, joinSpec)
        }
        player.play(this, reduced, moveSpec, fadeSpec) { i ->
            if (i < lyrics.size) burstEnd + i * LyricStaggerMs else WordStartMs + (i - lyrics.size) * stagger
        }
    }
    val summary = mineSummary(recap, words, lyrics)
    val area: @Composable (Modifier) -> Unit = { mod ->
        BurstArea(
            words = if (sparse) emptyList() else words,
            notes = if (sparse) notes else emptyList(),
            lyrics = lyrics,
            player = player,
            onDetail = { detail = it },
            modifier = mod
                .padding(horizontal = 16.dp)
                .clearAndSetSemantics { contentDescription = summary }
                .voteHighFrameRate(player.running),
        )
    }
    val header: @Composable () -> Unit = { MineHeader(recap, sparse, lyrics.isNotEmpty(), words.isNotEmpty()) }
    val next: @Composable () -> Unit = {
        JoinCard(
            fromSettings = fromSettings,
            onJoin = onJoin,
            onDone = onClose,
            modifier = Modifier
                .padding(horizontal = 16.dp, vertical = 12.dp)
                .graphicsLayer {
                    translationY = (1f - join.value) * JoinRisePx
                    alpha = join.value.coerceIn(0f, 1f)
                },
        )
    }
    BoxWithConstraints(modifier = Modifier.weight(1f).fillMaxWidth()) {
        if (maxWidth > maxHeight && maxWidth >= WideMinWidth) {
            // Wide (§6.4): the burst on the left, the words about it on the right.
            Row(modifier = Modifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically) {
                area(Modifier.weight(1.2f).fillMaxSize().padding(vertical = 16.dp))
                Column(modifier = Modifier.weight(1f).verticalScroll(rememberScrollState())) {
                    header()
                    next()
                }
            }
        } else {
            Column(modifier = Modifier.fillMaxSize()) {
                header()
                area(Modifier.weight(1f).fillMaxWidth().padding(top = 8.dp))
                next()
            }
        }
        DetailCard(detail = detail, onDismiss = { detail = null })
    }
}

@Composable
private fun MineHeader(recap: SpotoolfyRecap, sparse: Boolean, hasLyrics: Boolean, hasWords: Boolean) {
    val scheme = MaterialTheme.colorScheme
    val eyebrow = when {
        hasLyrics && sparse -> R.string.spotoolfy_recap_eyebrow_notes_lyrics
        hasLyrics -> R.string.spotoolfy_recap_eyebrow_words_lyrics
        sparse -> R.string.spotoolfy_recap_eyebrow_notes
        else -> R.string.spotoolfy_recap_eyebrow_words
    }
    val title = when {
        hasLyrics -> R.string.spotoolfy_recap_title_with_lyrics
        sparse -> R.string.spotoolfy_recap_title_notes
        else -> R.string.spotoolfy_recap_title_words
    }
    val sub = when {
        recap.noteCount == 0 -> stringResource(R.string.spotoolfy_recap_sub_ratings)
        sparse || !hasWords ->
            pluralStringResource(R.plurals.spotoolfy_recap_sub_notes, recap.noteCount, recap.noteCount)
        else -> pluralStringResource(R.plurals.spotoolfy_recap_sub_words, recap.noteCount, recap.noteCount)
    }
    Column(modifier = Modifier.padding(start = 24.dp, end = 24.dp, bottom = 8.dp)) {
        Text(stringResource(eyebrow), style = MaterialTheme.typography.labelLarge, color = scheme.primary)
        Text(
            text = stringResource(title),
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
            color = scheme.onSurface,
            modifier = Modifier
                .padding(top = 2.dp)
                .semantics { heading() },
        )
        Text(sub, style = MaterialTheme.typography.bodyMedium, color = scheme.onSurfaceVariant)
    }
}

@Composable
private fun mineSummary(recap: SpotoolfyRecap, words: List<RecapWord>, lyrics: List<RecapLyric>): String {
    val separator = stringResource(R.string.spotoolfy_recap_list_separator)
    val parts = buildList {
        if (recap.fallbackNotes.isEmpty() && words.isNotEmpty()) {
            val top = words.take(SUMMARY_WORDS).joinToString(separator) { it.text }
            add(stringResource(R.string.spotoolfy_recap_summary_words, top))
        } else if (recap.fallbackNotes.isNotEmpty()) {
            addAll(recap.fallbackNotes.map { it.text })
        }
        if (lyrics.isNotEmpty()) {
            add(stringResource(R.string.spotoolfy_recap_summary_lyrics, lyrics.joinToString(separator) { it.lyric }))
        }
    }
    return parts.joinToString(" ")
}

/**
 * The page's area: lyric cards in the top-left and bottom-right corners, words spiralling out from the centre
 * around them ([spiralPlacements]), or note cards stacked left and right when there are too few words.
 */
@Composable
private fun BurstArea(
    words: List<RecapWord>,
    notes: List<RecapNote>,
    lyrics: List<RecapLyric>,
    player: BurstPlayer,
    onDetail: (RecapDetail) -> Unit,
    modifier: Modifier,
) {
    val density = LocalDensity.current
    val gapPx = with(density) { WordGap.toPx() }
    val stepPx = with(density) { SpiralStep.toPx() }
    val noteWidthPx = with(density) { NoteCardWidth.roundToPx() }
    Layout(
        modifier = modifier,
        content = {
            lyrics.forEachIndexed { i, lyric ->
                LyricCard(
                    lyric = lyric,
                    onClick = { onDetail(RecapDetail.Lyric(lyric)) },
                    modifier = Modifier.burstItem(player, i, spinDegrees = if (i == 0) -LyricSpin else LyricSpin),
                )
            }
            words.forEachIndexed { i, word ->
                WordChip(
                    word = word,
                    onClick = { word.keyword?.let { onDetail(RecapDetail.Word(it)) } },
                    modifier = Modifier.burstItem(player, lyrics.size + i, spinDegrees = spinFor(i)),
                )
            }
            notes.forEachIndexed { i, note ->
                NoteCard(
                    note = note,
                    onClick = { onDetail(RecapDetail.Note(note)) },
                    modifier = Modifier.burstItem(player, lyrics.size + i, spinDegrees = spinFor(i) / 3f),
                )
            }
        },
    ) { measurables, constraints ->
        val width = constraints.maxWidth
        val height = constraints.maxHeight
        val cx = width / 2f
        val cy = height / 2f
        val lyricPlaceables = measurables.take(lyrics.size).map {
            it.measure(Constraints(maxWidth = (width * LYRIC_WIDTH).roundToInt(), maxHeight = height / 2))
        }
        val lyricBoxes = lyricPlaceables.mapIndexed { i, p ->
            val x = if (i == 0) 0f else (width - p.width).toFloat()
            val y = if (i == 0) 0f else (height - p.height).toFloat()
            RecapBox(x, y, p.width.toFloat(), p.height.toFloat())
        }
        val rest = measurables.drop(lyrics.size)
        val restPlaceables: List<Placeable>
        val restBoxes: List<RecapBox?>
        if (words.isNotEmpty()) {
            restPlaceables = rest.map { it.measure(Constraints(maxWidth = width)) }
            restBoxes = spiralPlacements(
                sizes = restPlaceables.map { it.width.toFloat() to it.height.toFloat() },
                width = width.toFloat(),
                height = height.toFloat(),
                obstacles = lyricBoxes,
                gap = gapPx,
                step = stepPx,
            )
        } else {
            val cardWidth = minOf(noteWidthPx, width)
            restPlaceables = rest.map { it.measure(Constraints(minWidth = cardWidth, maxWidth = cardWidth)) }
            var y = lyricBoxes.firstOrNull()?.let { it.height + gapPx * 2 } ?: 0f
            val limit = height - (lyricBoxes.getOrNull(1)?.let { it.height + gapPx * 2 } ?: 0f)
            restBoxes = restPlaceables.mapIndexed { i, p ->
                if (y + p.height > limit) {
                    null
                } else {
                    val x = if (i % 2 == 0) 0f else (width - p.width).toFloat()
                    RecapBox(x, y, p.width.toFloat(), p.height.toFloat()).also { y += p.height * NOTE_OVERLAP + gapPx }
                }
            }
        }
        layout(width, height) {
            lyricPlaceables.forEachIndexed { i, p ->
                val box = lyricBoxes[i]
                player.target(i, cx, cy, box.x + box.width / 2f, box.y + box.height / 2f)
                player.restRotation[i] = if (i == 0) -LyricTilt else LyricTilt
                p.place(box.x.roundToInt(), box.y.roundToInt())
            }
            restPlaceables.forEachIndexed { i, p ->
                val box = restBoxes[i] ?: return@forEachIndexed
                val index = lyrics.size + i
                player.target(index, cx, cy, box.x + box.width / 2f, box.y + box.height / 2f)
                player.restRotation[index] = if (words.isEmpty()) spinFor(i) / NOTE_TILT_DIVISOR else 0f
                p.place(box.x.roundToInt(), box.y.roundToInt())
            }
        }
    }
}

private fun spinFor(index: Int): Float = ((index * 41) % 51 - 25).toFloat()

@Composable
private fun WordChip(word: RecapWord, onClick: () -> Unit, modifier: Modifier) {
    val scheme = MaterialTheme.colorScheme
    val (container, content) = when (word.tier) {
        RecapWordTier.Largest -> scheme.primary to scheme.onPrimary
        RecapWordTier.Large -> scheme.primaryContainer to scheme.onPrimaryContainer
        RecapWordTier.Medium -> scheme.tertiaryContainer to scheme.onTertiaryContainer
        RecapWordTier.Small -> scheme.secondaryContainer to scheme.onSecondaryContainer
    }
    val (size, height) = when (word.tier) {
        RecapWordTier.Largest -> 28.sp to 50.dp
        RecapWordTier.Large -> 21.sp to 40.dp
        RecapWordTier.Medium -> 17.sp to 34.dp
        RecapWordTier.Small -> 14.sp to 30.dp
    }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .height(height)
            .clip(CircleShape)
            .background(container)
            .clickable(enabled = word.keyword != null, onClick = onClick)
            .padding(horizontal = 14.dp),
    ) {
        Text(word.text, fontSize = size, fontWeight = FontWeight.SemiBold, color = content, maxLines = 1)
        Text(
            text = word.count.toString(),
            fontSize = size * 0.6f,
            fontWeight = FontWeight.SemiBold,
            color = content.copy(alpha = 0.75f),
            modifier = Modifier.padding(start = 5.dp),
        )
    }
}

@Composable
private fun LyricCard(lyric: RecapLyric, onClick: () -> Unit, modifier: Modifier) {
    val scheme = MaterialTheme.colorScheme
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(20.dp))
            .background(scheme.tertiaryContainer)
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        Text(
            text = stringResource(R.string.spotoolfy_recap_quote, lyric.lyric),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            color = scheme.onTertiaryContainer,
        )
        songLine(lyric.trackTitle, lyric.artist)?.let {
            Text(
                text = it,
                style = MaterialTheme.typography.labelMedium,
                color = scheme.onTertiaryContainer.copy(alpha = 0.75f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
    }
}

@Composable
private fun NoteCard(note: RecapNote, onClick: () -> Unit, modifier: Modifier) {
    val scheme = MaterialTheme.colorScheme
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(20.dp))
            .background(scheme.surfaceContainerHigh)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 12.dp),
    ) {
        Text(
            text = note.text,
            style = MaterialTheme.typography.bodyLarge,
            color = scheme.onSurface,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            text = writtenFor(note),
            style = MaterialTheme.typography.labelMedium,
            color = scheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 4.dp),
        )
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun JoinCard(fromSettings: Boolean, onJoin: () -> Unit, onDone: () -> Unit, modifier: Modifier) {
    val scheme = MaterialTheme.colorScheme
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(28.dp))
            .background(scheme.surfaceContainer)
            .padding(16.dp),
    ) {
        if (fromSettings) {
            Text(stringResource(R.string.spotoolfy_recap_done_body), style = MaterialTheme.typography.bodyMedium)
            FilledTonalButton(
                onClick = onDone,
                shapes = ButtonDefaults.shapes(),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 12.dp)
                    .height(56.dp),
            ) { Text(stringResource(R.string.spotoolfy_recap_done), style = MaterialTheme.typography.titleSmall) }
        } else {
            Text(
                text = stringResource(R.string.spotoolfy_recap_join_title),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
                color = scheme.onSurface,
            )
            Text(
                text = stringResource(R.string.spotoolfy_recap_join_body),
                style = MaterialTheme.typography.bodyMedium,
                color = scheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp),
            )
            Button(
                onClick = onJoin,
                shapes = ButtonDefaults.shapes(),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 12.dp)
                    .height(56.dp),
            ) { Text(stringResource(R.string.spotoolfy_recap_join), style = MaterialTheme.typography.titleSmall) }
        }
    }
}

/** A word's notes, a lyric with its note, or a note in full; tap outside to put it away (no back callback). */
@Composable
private fun DetailCard(detail: RecapDetail?, onDismiss: () -> Unit) {
    // What the card shows while it slides away: the last detail, kept outside snapshot state.
    val last = remember { arrayOfNulls<RecapDetail>(1) }
    if (detail != null) last[0] = detail
    val shown = last[0]
    val scheme = MaterialTheme.colorScheme
    AnimatedVisibility(
        visible = detail != null,
        enter = YoinMotion.fadeIn(role = YoinMotionRole.Standard),
        exit = YoinMotion.fadeOut(role = YoinMotionRole.Standard, speed = YoinMotionSpeed.Fast),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(scheme.scrim.copy(alpha = 0.32f))
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = onDismiss,
                ),
        )
    }
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
        AnimatedVisibility(
            visible = detail != null,
            enter = YoinMotion.slideInVertically(role = YoinMotionRole.Expressive) { it / 2 } +
                YoinMotion.fadeIn(role = YoinMotionRole.Standard),
            exit = YoinMotion.slideOutVertically(YoinMotionRole.Standard, YoinMotionSpeed.Fast) { it / 2 } +
                YoinMotion.fadeOut(role = YoinMotionRole.Standard, speed = YoinMotionSpeed.Fast),
        ) {
            Column(
                modifier = Modifier
                    .padding(12.dp)
                    .widthIn(max = 560.dp)
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(28.dp))
                    .background(scheme.surfaceContainerHigh)
                    .verticalScroll(rememberScrollState())
                    .padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                when (val current = shown) {
                    is RecapDetail.Word -> {
                        val count = current.keyword.count
                        DetailTitle(
                            current.keyword.text,
                            pluralStringResource(R.plurals.spotoolfy_recap_word_count, count, count),
                        )
                        current.keyword.notes.forEach { note -> Snippet(note.text, writtenFor(note)) }
                    }
                    is RecapDetail.Lyric -> {
                        val lyric = current.lyric
                        DetailTitle(lyric.trackTitle ?: unknownTitle(), lyric.artist)
                        Snippet(
                            text = stringResource(R.string.spotoolfy_recap_quote, lyric.lyric),
                            source = stringResource(R.string.spotoolfy_recap_kept_on, formatDate(lyric.capturedAt)),
                        )
                        Snippet(lyric.note, stringResource(R.string.spotoolfy_recap_note_with_it))
                    }
                    is RecapDetail.Note -> {
                        val note = current.note
                        DetailTitle(note.trackTitle ?: unknownTitle(), note.artist)
                        Snippet(note.text, formatDate(note.writtenAt))
                    }
                    null -> Unit
                }
            }
        }
    }
}

@Composable
private fun DetailTitle(title: String, sub: String?) {
    Column {
        Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
        sub?.let {
            Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun Snippet(text: String, source: String) {
    val scheme = MaterialTheme.colorScheme
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(scheme.surfaceBright)
            .padding(14.dp),
    ) {
        Text(text, style = MaterialTheme.typography.bodyLarge, color = scheme.onSurface)
        Text(
            text = source,
            style = MaterialTheme.typography.labelMedium,
            color = scheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 6.dp),
        )
    }
}

@Composable
private fun songLine(title: String?, artist: String?): String? = when {
    title != null && artist != null -> stringResource(R.string.spotoolfy_recap_song_by, title, artist)
    title != null -> stringResource(R.string.spotoolfy_recap_quote, title)
    else -> artist
}

@Composable
private fun writtenFor(note: RecapNote): String =
    stringResource(
        R.string.spotoolfy_recap_written_for,
        formatDate(note.writtenAt),
        note.trackTitle ?: unknownTitle(),
    )

@Composable
private fun unknownTitle(): String = stringResource(R.string.landing_spotoolfy_unknown_title)

@Composable
private fun formatDate(at: Long): String {
    val locale = LocalConfiguration.current.locales[0]
    return remember(locale, at) { DateFormat.getDateInstance(DateFormat.MEDIUM, locale).format(Date(at)) }
}

// ── Shared pieces ──────────────────────────────────────────────────────

/** Page 1's halves: the wall above the text on a phone, beside it on a wide window, wall on the left (§6.4). */
@Composable
private fun ColumnScope.WideOrTall(
    visual: @Composable (Modifier) -> Unit,
    text: @Composable (Modifier) -> Unit,
) {
    BoxWithConstraints(modifier = Modifier.weight(1f).fillMaxWidth()) {
        if (maxWidth > maxHeight && maxWidth >= WideMinWidth) {
            Row(modifier = Modifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically) {
                visual(Modifier.weight(1.2f).fillMaxSize().padding(vertical = 16.dp))
                text(Modifier.weight(1f).verticalScroll(rememberScrollState()))
            }
        } else {
            Column(modifier = Modifier.fillMaxSize()) {
                visual(Modifier.weight(1f).fillMaxWidth().padding(horizontal = 20.dp, vertical = 16.dp))
                text(Modifier)
            }
        }
    }
}

/**
 * Springs for a burst: each item flies out from the area's centre to where the layout put it ([target]), spinning
 * from a tilt to its rest angle, while it fades in on an effects spring. Values are read only in [burstItem]'s
 * graphicsLayer.
 */
@Stable
private class BurstPlayer(count: Int) {
    val move = List(count) { Animatable(0f) }
    val fade = List(count) { Animatable(0f) }
    val dx = FloatArray(count)
    val dy = FloatArray(count)
    val restRotation = FloatArray(count)
    var running by mutableStateOf(false)
        private set

    fun target(index: Int, centerX: Float, centerY: Float, itemCenterX: Float, itemCenterY: Float) {
        if (index !in dx.indices) return
        dx[index] = centerX - itemCenterX
        dy[index] = centerY - itemCenterY
    }

    suspend fun play(
        scope: CoroutineScope,
        reduced: Boolean,
        moveSpec: AnimationSpec<Float>,
        fadeSpec: AnimationSpec<Float>,
        delayFor: (Int) -> Long,
    ) {
        running = true
        // Reduced motion: no flight, the final layout fades in.
        val jobs: List<Job> = move.indices.map { i ->
            scope.launch {
                if (reduced) {
                    move[i].snapTo(1f)
                    fade[i].snapTo(0f)
                    fade[i].animateTo(1f, fadeSpec)
                } else {
                    move[i].snapTo(0f)
                    fade[i].snapTo(0f)
                    delay(delayFor(i))
                    launch { fade[i].animateTo(1f, fadeSpec) }
                    move[i].animateTo(1f, moveSpec)
                }
            }
        }
        jobs.joinAll()
        running = false
    }
}

private fun Modifier.burstItem(player: BurstPlayer, index: Int, spinDegrees: Float): Modifier = graphicsLayer {
    if (index !in player.move.indices) return@graphicsLayer
    val p = player.move[index].value
    val rest = 1f - p
    translationX = player.dx[index] * rest
    translationY = player.dy[index] * rest
    val scale = BurstStartScale + (1f - BurstStartScale) * p
    scaleX = scale
    scaleY = scale
    rotationZ = player.restRotation[index] + spinDegrees * rest
    alpha = player.fade[index].value.coerceIn(0f, 1f)
}

@Composable
private fun coverTones(): List<Color> {
    val scheme = MaterialTheme.colorScheme
    return listOf(
        scheme.primaryContainer,
        scheme.tertiaryContainer,
        scheme.secondaryContainer,
        SpotoolfyIconTeal.copy(alpha = 0.55f),
        scheme.surfaceContainerHighest,
    )
}

@Composable
private fun rememberReducedMotion(): Boolean {
    val scale = rememberCoroutineScope().coroutineContext[MotionDurationScale]?.scaleFactor
    return scale == 0f
}

private const val RECAP_PAGES = 2
private val TopBarHeight = 56.dp
private val WideMinWidth = 600.dp

// Cover wall: tiles, columns, how far apart rows sit (in tiles), the hand-placed scatter (px per step).
private val CoverTile = 58.dp
private const val CoverDecodePx = 128
private const val WALL_COLUMNS = 5
private const val WALL_ROW_PITCH = 1.6f
private const val WALL_JITTER = 1.2f
private const val MIN_WALL = 20
private const val MAX_WALL = 24
private const val CoverSpin = 30f

// Keyword burst: chip spacing, spiral pitch per radian, how wide a lyric card may get, note cards.
private val WordGap = 6.dp
private val SpiralStep = 4.2.dp
private const val LYRIC_WIDTH = 0.8f
private val NoteCardWidth = 230.dp
private const val NOTE_OVERLAP = 0.8f
private const val NOTE_TILT_DIVISOR = 8f
private const val LyricSpin = 18f
private const val LyricTilt = 2f
private const val BurstStartScale = 0.3f
private const val SUMMARY_WORDS = 8

// The burst's rhythm (ms): when the first item leaves, the gap between items, when the lyric cards follow the
// words, and when the join card rises after them. Capped so a full burst never makes the card wait.
private const val CoverStartMs = 140L
private const val CoverStaggerMs = 22L
private const val WordStartMs = 180L
private const val WordStaggerMs = 26L
private const val NoteStaggerMs = 70L
private const val LyricStaggerMs = 140L
private const val JoinAfterMs = 160L
private const val STAGGER_CAP = 30
private const val LYRICS_AFTER = 0.6f
private const val JoinRisePx = 120f

/** The count-up: critically damped and unhurried, so the number lands as the covers do. */
private val CountSpring = spring<Float>(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = Spring.StiffnessVeryLow)

@Preview(widthDp = 400, heightDp = 860)
@Composable
private fun SpotoolfyRecapScreenPreview() {
    YoinTheme {
        SpotoolfyRecapScreen(
            state = RecapUiState.Ready(
                SpotoolfyRecap(
                    noteCount = 188,
                    trackCount = 32,
                    artistCount = 10,
                    ratedTrackCount = 32,
                    firstRecordAt = 1_730_000_000_000L,
                    lastRecordAt = 1_784_000_000_000L,
                    coverUrls = emptyList(),
                    keywords = List(12) { RecapKeyword("word$it", 30 - it, emptyList()) },
                    emoji = emptyList(),
                    lyricSnippets = emptyList(),
                    fallbackNotes = emptyList(),
                ),
            ),
            fromSettings = false,
            onClose = {},
            onJoin = {},
            modifier = Modifier.fillMaxSize(),
        )
    }
}
