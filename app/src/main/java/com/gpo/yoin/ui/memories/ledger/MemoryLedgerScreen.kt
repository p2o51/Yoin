package com.gpo.yoin.ui.memories.ledger

import android.text.format.DateFormat
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import com.gpo.yoin.R
import com.gpo.yoin.data.memory.MemoryGridCell
import com.gpo.yoin.data.memory.MemoryGridScale
import com.gpo.yoin.data.memory.MemoryLedgerEntry
import com.gpo.yoin.data.memory.MemoryLedgerFilter
import com.gpo.yoin.data.memory.MemoryLedgerKind
import com.gpo.yoin.data.memory.MemoryLedgerRow
import com.gpo.yoin.data.memory.localDate
import com.gpo.yoin.data.memory.matches
import com.gpo.yoin.data.memory.memoryLedgerRows
import com.gpo.yoin.ui.component.ExpressiveSegmentChip
import com.gpo.yoin.ui.component.ExpressiveSegmentRow
import com.gpo.yoin.ui.component.YoinLoadingIndicator
import com.gpo.yoin.ui.component.noRippleClickable
import com.gpo.yoin.ui.component.ratingLabel
import com.gpo.yoin.ui.settings.AccountMark
import com.gpo.yoin.ui.settings.SettingsBackButton
import com.gpo.yoin.ui.settings.SettingsHeadline
import com.gpo.yoin.ui.settings.SettingsPageBackground
import com.gpo.yoin.ui.settings.handoffProgress
import com.gpo.yoin.ui.settings.serviceIdentity
import com.gpo.yoin.ui.theme.GoogleSansFlex
import com.gpo.yoin.ui.theme.ProvideYoinMotionRole
import com.gpo.yoin.ui.theme.YoinMotionRole
import java.text.NumberFormat
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.util.Locale

/*
 * All Memories (owner 2026-11-08): every memory on one page, so the listener can see what Yoin keeps for
 * them. From the top: what it adds up to, the grid (tap a cell to see just that stretch), filters by kind
 * and account, then everything by month, newest first. The pull-down Memories deck stays the way to come
 * across them at random; this is the account book.
 */

/**
 * The page. [onOpenAlbum] opens an album of the account in use; another account's entries don't open
 * (that would switch accounts: a later step).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun MemoryLedgerScreen(
    state: MemoryLedgerState?,
    onBackClick: () -> Unit,
    onOpenAlbum: (MemoryLedgerEntry) -> Unit,
    modifier: Modifier = Modifier,
    // The cell picked on the grid (its index); hoisted so the Tips demo can pick one.
    selectedCellState: MutableState<Int?> = rememberSaveable { mutableStateOf(null) },
    // Names the grid for the Tips demo's touch.
    gridModifier: Modifier = Modifier,
) {
    val title = stringResource(R.string.memory_ledger_title)
    val viewportTop = remember { mutableFloatStateOf(0f) }
    val headlineTop = remember { mutableFloatStateOf(Float.NaN) }
    val headlineHeight = remember { mutableFloatStateOf(1f) }
    var filter by rememberSaveable { mutableStateOf(MemoryLedgerFilter.All) }
    var profileFilter by rememberSaveable { mutableStateOf<String?>(null) }
    var selectedCell by selectedCellState
    ProvideYoinMotionRole(role = YoinMotionRole.Standard) {
        SettingsPageBackground(modifier = modifier) {
            Scaffold(
                modifier = Modifier.fillMaxSize(),
                containerColor = Color.Transparent,
                contentColor = MaterialTheme.colorScheme.onSurface,
                contentWindowInsets = WindowInsets(0, 0, 0, 0),
                topBar = {
                    TopAppBar(
                        title = {
                            Text(
                                text = title,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.graphicsLayer {
                                    alpha = handoffProgress(viewportTop, headlineTop, headlineHeight)
                                    translationY = (1f - alpha) * size.height * 0.5f
                                },
                            )
                        },
                        navigationIcon = { SettingsBackButton(onClick = onBackClick) },
                        colors = TopAppBarDefaults.topAppBarColors(
                            containerColor = Color.Transparent,
                            navigationIconContentColor = MaterialTheme.colorScheme.onSurface,
                        ),
                    )
                },
            ) { innerPadding ->
                val navBottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
                if (state == null) {
                    Box(Modifier.fillMaxSize().padding(innerPadding), contentAlignment = Alignment.Center) {
                        YoinLoadingIndicator()
                    }
                    return@Scaffold
                }
                // A cell from before a reload may not be there any more.
                val cell = selectedCell?.let(state.grid.cells::getOrNull)
                val profile = profileFilter?.takeIf { id -> state.profiles.any { it.id == id } }
                val rows = remember(state, filter, profile, cell) {
                    val shown = state.entries.filter { entry ->
                        entry.matches(filter) &&
                            (profile == null || entry.profileId == profile) &&
                            (cell == null || entry.localDate(state.zone) in cell.start..cell.end)
                    }
                    memoryLedgerRows(shown, state.today, state.zone, foldScores = filter != MemoryLedgerFilter.Scores)
                        // A stretch picked on the grid: just its months, no quiet ones around it.
                        .filter { cell == null || it !is MemoryLedgerRow.Quiet }
                }
                LazyColumn(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(innerPadding)
                        .onGloballyPositioned { viewportTop.floatValue = it.positionInWindow().y },
                    contentPadding = PaddingValues(start = 16.dp, top = 8.dp, end = 16.dp, bottom = 24.dp + navBottom),
                ) {
                    item("headline") {
                        SettingsHeadline(
                            title = title,
                            titleModifier = Modifier.onGloballyPositioned {
                                headlineTop.floatValue = it.positionInWindow().y
                                headlineHeight.floatValue = it.size.height.toFloat().coerceAtLeast(1f)
                            },
                        )
                    }
                    if (state.isEmpty) {
                        item("empty") { LedgerEmpty() }
                        return@LazyColumn
                    }
                    item("stats") { LedgerStats(state, Modifier.padding(top = 8.dp)) }
                    // The scale sits on the right, as on Home's card; why it changes on the left.
                    item("scale") {
                        Row(
                            modifier = Modifier
                                .widthIn(max = 640.dp)
                                .fillMaxWidth()
                                .padding(top = 24.dp, bottom = 10.dp),
                            verticalAlignment = Alignment.Bottom,
                        ) {
                            Text(
                                text = stringResource(R.string.memory_ledger_scale_hint),
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.weight(1f),
                            )
                            Text(
                                text = scaleLabel(state.grid.scale),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    item("grid") {
                        MemoryGridView(
                            grid = state.grid,
                            coverUrls = state.coverUrls,
                            placement = MemoryGridPlacement.Page,
                            selected = selectedCell,
                            onCellClick = { index -> selectedCell = if (selectedCell == index) null else index },
                            // Every mark the month holds, as the list's month header counts them.
                            monthCaption = { monthCell ->
                                if (monthCell.entries > 0) {
                                    pluralStringResource(
                                        R.plurals.memory_ledger_month_count,
                                        monthCell.entries,
                                        monthCell.entries,
                                    )
                                } else {
                                    "—"
                                }
                            },
                            modifier = Modifier.widthIn(max = 640.dp).then(gridModifier),
                        )
                    }
                    item("filters") {
                        LedgerFilters(
                            state = state,
                            filter = filter,
                            onFilter = { filter = it },
                            profile = profile,
                            onProfile = { profileFilter = it },
                            cell = cell,
                            scale = state.grid.scale,
                            onClearCell = { selectedCell = null },
                            modifier = Modifier.padding(top = 20.dp, bottom = 4.dp),
                        )
                    }
                    if (rows.isEmpty()) {
                        item("range-empty") {
                            Text(
                                text = stringResource(R.string.memory_ledger_range_empty),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(vertical = 24.dp),
                            )
                        }
                    }
                    items(rows, key = { it.key }) { row ->
                        when (row) {
                            is MemoryLedgerRow.Month -> MonthHeader(row, state.today)
                            is MemoryLedgerRow.Entry -> LedgerEntryRow(
                                entry = row.entry,
                                state = state,
                                onClick = { onOpenAlbum(row.entry) },
                            )
                            is MemoryLedgerRow.Scores -> FoldedScores(row)
                            is MemoryLedgerRow.Quiet -> QuietMonths(row, state.today)
                        }
                    }
                    item("since") {
                        state.stats.firstAt?.let { first ->
                            Text(
                                text = stringResource(R.string.memory_ledger_since, longDate(first, state)),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.outline,
                                modifier = Modifier.padding(top = 28.dp),
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
internal fun scaleLabel(scale: MemoryGridScale): String = stringResource(
    when (scale) {
        MemoryGridScale.Month -> R.string.memory_ledger_scale_month
        MemoryGridScale.Week -> R.string.memory_ledger_scale_week
        MemoryGridScale.Day -> R.string.memory_ledger_scale_day
    },
)

@Composable
private fun LedgerEmpty() {
    Column(Modifier.padding(top = 32.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = stringResource(R.string.memory_ledger_empty_title),
            style = MaterialTheme.typography.titleMedium,
        )
        Text(
            text = stringResource(R.string.memory_ledger_empty_body),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun LedgerStats(state: MemoryLedgerState, modifier: Modifier = Modifier) {
    val numbers = remember { NumberFormat.getIntegerInstance() }
    val stats = state.stats
    Row(modifier = modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        listOf(
            stats.albums to R.string.memory_ledger_stat_albums,
            stats.writings to R.string.memory_ledger_stat_writings,
            stats.characters to R.string.memory_ledger_stat_characters,
            stats.ratedTracks to R.string.memory_ledger_stat_rated,
        ).forEach { (value, label) ->
            Column(Modifier.weight(1f)) {
                Text(
                    text = numbers.format(value),
                    style = MaterialTheme.typography.titleLarge.copy(
                        fontFamily = GoogleSansFlex,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 24.sp,
                    ),
                    maxLines = 1,
                )
                Text(
                    text = stringResource(label),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                )
            }
        }
    }
}

@Composable
private fun LedgerFilters(
    state: MemoryLedgerState,
    filter: MemoryLedgerFilter,
    onFilter: (MemoryLedgerFilter) -> Unit,
    profile: String?,
    onProfile: (String?) -> Unit,
    cell: MemoryGridCell?,
    scale: MemoryGridScale,
    onClearCell: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        ExpressiveSegmentRow {
            listOf(
                MemoryLedgerFilter.All to R.string.memory_ledger_filter_all,
                MemoryLedgerFilter.Writing to R.string.memory_ledger_filter_writing,
                MemoryLedgerFilter.Scores to R.string.memory_ledger_filter_scores,
            ).forEach { (value, label) ->
                ExpressiveSegmentChip(
                    label = stringResource(label),
                    selected = filter == value,
                    onClick = { onFilter(value) },
                )
            }
        }
        // Only with memories in more than one account.
        if (state.profiles.size > 1) {
            ExpressiveSegmentRow {
                ExpressiveSegmentChip(
                    label = stringResource(R.string.memory_ledger_filter_all_profiles),
                    selected = profile == null,
                    onClick = { onProfile(null) },
                )
                state.profiles.forEach { account ->
                    ExpressiveSegmentChip(
                        label = account.name,
                        selected = profile == account.id,
                        onClick = { onProfile(if (profile == account.id) null else account.id) },
                    )
                }
            }
        }
        if (cell != null) {
            ExpressiveSegmentRow {
                ExpressiveSegmentChip(
                    label = cellLabel(cell, scale) + " ✕",
                    selected = true,
                    onClick = onClearCell,
                )
            }
        }
    }
}

private fun cellLabel(cell: MemoryGridCell, scale: MemoryGridScale): String {
    val locale = Locale.getDefault()
    return when (scale) {
        MemoryGridScale.Month -> DateTimeFormatter.ofPattern(DateFormat.getBestDateTimePattern(locale, "yMMMM"), locale)
            .format(cell.start)
        MemoryGridScale.Week -> {
            val day = DateTimeFormatter.ofPattern(DateFormat.getBestDateTimePattern(locale, "MMMd"), locale)
            "${day.format(cell.start)} – ${day.format(cell.end)}"
        }
        MemoryGridScale.Day -> DateTimeFormatter.ofPattern(DateFormat.getBestDateTimePattern(locale, "MMMd"), locale)
            .format(cell.start)
    }
}

private fun monthName(month: YearMonth, today: LocalDate): String {
    val locale = Locale.getDefault()
    val skeleton = if (month.year == today.year) "MMMM" else "yMMMM"
    return DateTimeFormatter.ofPattern(DateFormat.getBestDateTimePattern(locale, skeleton), locale).format(month)
}

private fun longDate(at: Long, state: MemoryLedgerState): String {
    val locale = Locale.getDefault()
    return DateTimeFormatter.ofPattern(DateFormat.getBestDateTimePattern(locale, "yMMMd"), locale)
        .format(Instant.ofEpochMilli(at).atZone(state.zone).toLocalDate())
}

@Composable
private fun MonthHeader(row: MemoryLedgerRow.Month, today: LocalDate) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 24.dp, bottom = 6.dp),
        verticalAlignment = Alignment.Bottom,
    ) {
        Text(
            text = monthName(row.month, today),
            style = MaterialTheme.typography.titleMedium.copy(
                fontFamily = GoogleSansFlex,
                fontWeight = FontWeight.SemiBold,
            ),
            modifier = Modifier.weight(1f),
        )
        val count = row.writings + row.scores
        Text(
            text = pluralStringResource(R.plurals.memory_ledger_month_count, count, count),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun FoldedScores(row: MemoryLedgerRow.Scores) {
    Text(
        text = pluralStringResource(R.plurals.memory_ledger_scores_folded, row.scores, row.scores),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 60.dp, top = 4.dp, bottom = 8.dp),
    )
}

@Composable
private fun QuietMonths(row: MemoryLedgerRow.Quiet, today: LocalDate) {
    val dash = MaterialTheme.colorScheme.outlineVariant
    val text = if (row.from == row.to) {
        stringResource(R.string.memory_ledger_quiet_one, monthName(row.from, today))
    } else {
        // Earlier month first: "July to August".
        stringResource(R.string.memory_ledger_quiet_range, monthName(row.to, today), monthName(row.from, today))
    }
    // The text sits on the page itself (no patch behind it), between two dashed rules.
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        DashedRule(dash, Modifier.weight(1f))
        Text(
            text = text,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        DashedRule(dash, Modifier.weight(1f))
    }
}

@Composable
private fun DashedRule(color: Color, modifier: Modifier) {
    Canvas(modifier.height(1.dp)) {
        drawLine(
            color = color,
            start = Offset(0f, size.height / 2),
            end = Offset(size.width, size.height / 2),
            strokeWidth = 1.dp.toPx(),
            pathEffect = PathEffect.dashPathEffect(floatArrayOf(6.dp.toPx(), 5.dp.toPx())),
        )
    }
}

@Composable
private fun LedgerEntryRow(entry: MemoryLedgerEntry, state: MemoryLedgerState, onClick: () -> Unit) {
    val album = entry.album
    val opens = album != null && entry.profileId == state.activeProfileId
    val interaction = remember { MutableInteractionSource() }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .noRippleClickable(interaction, enabled = opens, onClick = onClick)
            .padding(vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        LedgerThumb(entry, state)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            // The kind on the left, the day on the right: no run of fields joined by dots.
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = kindLabel(entry.kind),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text = entryDay(entry, state),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline,
                    maxLines = 1,
                )
            }
            // A song note: the song, and where in it (lighter).
            entry.trackTitle?.let { song ->
                LedgerPair(
                    first = song,
                    second = entry.positionMs?.takeIf { it > 0 }
                        ?.let { ms -> "${ms / 60_000}:${"%02d".format((ms / 1000) % 60)}" },
                    style = MaterialTheme.typography.labelMedium,
                )
            }
            when {
                entry.score != null -> Text(
                    text = ratingLabel(entry.score).orEmpty(),
                    style = MaterialTheme.typography.titleMedium.copy(
                        fontFamily = GoogleSansFlex,
                        fontWeight = FontWeight.SemiBold,
                    ),
                )
                !entry.text.isNullOrBlank() -> Text(
                    text = entry.text,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 8,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            // The album, then its artist in a lighter tone.
            LedgerPair(
                first = album?.name ?: stringResource(R.string.memory_ledger_unknown_album),
                second = album?.artist?.takeIf(String::isNotBlank),
                style = MaterialTheme.typography.bodySmall,
            )
            // Another account's memory says whose it is (with its mark), as the deck's "From …" line does.
            val owner = state.profile(entry.profileId)
            if (owner != null && state.profiles.size > 1 && !owner.isActive) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    AccountMark(shapeIndex = owner.shape, identity = owner.kind.serviceIdentity, size = 14.dp)
                    Text(
                        text = stringResource(R.string.memory_ledger_from, owner.name),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

@Composable
private fun kindLabel(kind: MemoryLedgerKind): String = stringResource(
    when (kind) {
        MemoryLedgerKind.AlbumNote -> R.string.memory_ledger_kind_album_note
        MemoryLedgerKind.SongNote -> R.string.memory_ledger_kind_song_note
        MemoryLedgerKind.Review -> R.string.memory_ledger_kind_review
        MemoryLedgerKind.Title -> R.string.memory_ledger_kind_title
        MemoryLedgerKind.AlbumRating -> R.string.memory_ledger_kind_album_rating
        MemoryLedgerKind.TrackRating -> R.string.memory_ledger_kind_track_rating
    },
)

private fun entryDay(entry: MemoryLedgerEntry, state: MemoryLedgerState): String {
    val locale = Locale.getDefault()
    return DateTimeFormatter.ofPattern(DateFormat.getBestDateTimePattern(locale, "MMMd"), locale)
        .format(entry.localDate(state.zone))
}

/** Two pieces of one line told apart by tone and a gap, not by a dot: [first] strong, [second] lighter. */
@Composable
private fun LedgerPair(first: String, second: String?, style: androidx.compose.ui.text.TextStyle) {
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = first,
            style = style,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false),
        )
        if (second != null) {
            Text(
                text = second,
                style = style,
                color = MaterialTheme.colorScheme.outline,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** The album's cover, whole (nothing on it, never cut), or its colours when there's no cover. */
@Composable
private fun LedgerThumb(entry: MemoryLedgerEntry, state: MemoryLedgerState) {
    val album = entry.album
    val url = album?.let { state.coverUrls[it.key] }
    val shape = RoundedCornerShape(8.dp)
    if (url != null) {
        AsyncImage(
            model = url,
            contentDescription = null,
            contentScale = ContentScale.Fit,
            modifier = Modifier.size(48.dp).clip(shape),
        )
    } else {
        val colors = album?.let { fallbackAlbumColors(it.key) }
        val faint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.07f)
        Canvas(Modifier.size(48.dp)) {
            if (colors == null) {
                drawRoundRect(faint, cornerRadius = CornerRadius(8.dp.toPx()))
            } else {
                drawRoundRect(colors.base, cornerRadius = CornerRadius(8.dp.toPx()))
                drawRoundRect(colors.accent, cornerRadius = CornerRadius(8.dp.toPx()), style = Stroke(2.dp.toPx()))
            }
        }
    }
}
