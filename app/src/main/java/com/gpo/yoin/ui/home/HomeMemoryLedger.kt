package com.gpo.yoin.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.material3.toShape
import androidx.compose.ui.text.style.TextOverflow
import com.gpo.yoin.data.memory.MemoryGrid
import com.gpo.yoin.data.memory.MemoryGridScale
import com.gpo.yoin.data.memory.MemoryLedgerAlbum
import com.gpo.yoin.data.memory.MemoryLedgerEntry
import com.gpo.yoin.data.memory.MemoryLedgerKind
import com.gpo.yoin.data.memory.buildMemoryGrid
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.gpo.yoin.R
import com.gpo.yoin.ui.component.elasticPress
import com.gpo.yoin.ui.component.noRippleClickable
import com.gpo.yoin.ui.home.edit.homeEditInteractive
import com.gpo.yoin.ui.home.edit.homeEditCard
import com.gpo.yoin.ui.memories.ledger.MemoryGridPlacement
import com.gpo.yoin.ui.memories.ledger.MemoryGridView
import com.gpo.yoin.ui.memories.ledger.MemoryLedgerState
import com.gpo.yoin.ui.theme.GoogleSansFlex

/**
 * Home's Memories grid (owner 2026-11-08, "B3"): how much you've written, and the calendar wall of it, a
 * cell a month, week or day as you write more. A tap opens All Memories. Hidden while there's nothing.
 */
@Composable
internal fun MemoryLedgerSection(
    state: MemoryLedgerState,
    extractBackdropColors: Boolean,
    onOpen: () -> Unit,
    modifier: Modifier = Modifier,
    // Two columns: a rated album's card opens it, as everywhere.
    onAlbumClick: (MemoryLedgerAlbum) -> Unit = {},
) {
    val grid = LocalHomeGridHeight.current
    if (grid != null) {
        MemoryLedgerUnits(
            state = state,
            units = grid.units.coerceIn(HomeSection.MemoryGrid.unitRange),
            extractBackdropColors = extractBackdropColors,
            onOpen = onOpen,
            onAlbumClick = onAlbumClick,
            modifier = modifier,
        )
        return
    }
    val interactionSource = remember { MutableInteractionSource() }
    val openLabel = stringResource(R.string.memory_ledger_open)
    // Two columns: the content starts right under the shared 40dp title row.
    val titleGap = if (LocalHomeGridHeight.current != null) 0.dp else 12.dp
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(titleGap)) {
        HomeSectionTitle(text = stringResource(HomeSection.MemoryGrid.titleRes))
        Column(
            // The card spans the feed's content width like every other block (owner 2026-11-24: on a small
            // tablet in portrait it stopped at 560dp and left the rest of the row empty); past the old
            // 560dp card the wall adds columns instead of growing its cells (memoryCardWallColumns).
            modifier = Modifier
                .fillMaxWidth()
                .elasticPress(interactionSource)
                .clip(RoundedCornerShape(28.dp))
                .background(MaterialTheme.colorScheme.surfaceContainerLow)
                .noRippleClickable(
                    interactionSource = interactionSource,
                    enabled = homeEditInteractive(),
                    onClickLabel = openLabel,
                    onClick = onOpen,
                )
                .semantics { role = Role.Button }
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            MemoryLedgerCount(state)
            MemoryCardWall(state = state, extractBackdropColors = extractBackdropColors)
        }
    }
}

/**
 * The single-column card's wall: up to the old 560dp card (inner [MemoryCardWallReferenceDp]) the
 * scale's own columns, exactly as before; wider, as many columns as cells of that card's size fit, so a
 * wider card looks further back instead of blowing a month's quads up past a cover.
 */
@Composable
private fun MemoryCardWall(state: MemoryLedgerState, extractBackdropColors: Boolean) {
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val columns = memoryCardWallColumns(state.grid.scale, maxWidth.value)
        val firstDay = remember { java.time.temporal.WeekFields.of(java.util.Locale.getDefault()).firstDayOfWeek }
        val wall = remember(state.entries, state.grid, columns, state.today, state.zone, firstDay) {
            if (columns == null) {
                state.grid
            } else {
                buildMemoryGrid(
                    state.entries,
                    state.today,
                    state.zone,
                    firstDay,
                    state.grid.scale,
                    memoryCardWallPeriods(state.grid.scale, columns),
                )
            }
        }
        MemoryGridView(
            grid = wall,
            coverUrls = state.coverUrls,
            placement = MemoryGridPlacement.Home,
            extractColors = extractBackdropColors,
            columns = columns,
        )
    }
}

/** The 560dp phone-wide card's inner width (less its 16dp side padding): the widest wall at the scale's own columns. */
internal const val MemoryCardWallReferenceDp = 528f

/**
 * Columns for the single-column card's wall [innerWidthDp] wide: null (the scale's own 17 day weeks,
 * 13 week columns or 6 months) up to [MemoryCardWallReferenceDp]; wider, as many columns as cells the
 * size of the reference wall's fit, so the cells stay the 560dp card's and the extra width shows more
 * of the past. Pure.
 */
internal fun memoryCardWallColumns(scale: MemoryGridScale, innerWidthDp: Float): Int? {
    val (base, gap) = when (scale) {
        MemoryGridScale.Day -> 17 to 3f
        MemoryGridScale.Week -> 13 to 5f
        MemoryGridScale.Month -> 6 to 8f
    }
    if (innerWidthDp <= MemoryCardWallReferenceDp) return null
    val cell = (MemoryCardWallReferenceDp - gap * (base - 1)) / base
    // The same 0.01dp guard as the feed's breakpoints: a float sum an ulp short keeps its column.
    val columns = kotlin.math.floor((innerWidthDp + 0.01f + gap) / (cell + gap)).toInt()
    val capped = if (scale == MemoryGridScale.Month) columns.coerceAtMost(MemoryGrid.MAX_MONTHS) else columns
    return capped.takeIf { it > base }
}

/** How many periods [buildMemoryGrid] builds for [columns] wall columns: weeks for a day, two a column for a week. */
internal fun memoryCardWallPeriods(scale: MemoryGridScale, columns: Int): Int = when (scale) {
    MemoryGridScale.Week -> columns * 2
    MemoryGridScale.Day, MemoryGridScale.Month -> columns
}

/** "12 memories written": what you wrote leads; with only scores so far, the scores. */
@Composable
private fun MemoryLedgerCount(state: MemoryLedgerState, modifier: Modifier = Modifier) {
    Row(modifier = modifier, verticalAlignment = Alignment.Bottom) {
        val wrote = state.stats.writings > 0
        val count = if (wrote) state.stats.writings else state.entries.size
        Text(
            text = count.toString(),
            style = MaterialTheme.typography.titleLarge.copy(
                fontFamily = GoogleSansFlex,
                fontWeight = FontWeight.SemiBold,
                fontSize = 24.sp,
            ),
            // The card is a plain background, not a Surface: without a colour of its own the figure
            // falls back to black and all but vanishes on a dark theme.
            color = MaterialTheme.colorScheme.onSurface,
        )
        Spacer(Modifier.width(6.dp))
        Text(
            text = pluralStringResource(
                if (wrote) R.plurals.memory_ledger_writings_label else R.plurals.memory_ledger_scores_label,
                count,
            ),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(bottom = 3.dp),
        )
    }
}

// ── Two columns (HomeGridUnits.kt) ─────────────────────────────────────────

/** How All Memories uses its units: the wall's box, and rows of rated albums beside or under it. Pure. */
internal data class MemoryUnitLayout(
    /** The wall shares the row with the albums (a full row), else it takes the first unit alone. */
    val side: Boolean,
    /** Units the wall's box is tall. */
    val wallUnits: Int,
    /** Rows of rated albums (one a unit). */
    val albumRows: Int,
)

internal fun memoryUnitLayout(half: Boolean, units: Int): MemoryUnitLayout {
    val n = units.coerceAtLeast(1)
    return if (half) MemoryUnitLayout(side = false, wallUnits = 1, albumRows = n - 1) else MemoryUnitLayout(true, n, n)
}

/** The wall fitted to a box: [columns] (months, week columns or day weeks), [periods] it spans, its cell and size. */
internal data class MemoryWallFit(
    val columns: Int,
    val periods: Int,
    val cellDp: Float,
    val widthDp: Float,
    val heightDp: Float,
)

/** A month quad's name under it, dp (labelSmall's line). */
internal const val MemoryWallMonthLabelDp = 16f

/** The full row's wall cells stay small: the albums beside it carry the row. */
internal const val MemoryWallSideCellCapDp = 22f

/**
 * The [scale]'s wall fitted to a [widthDp] × [heightDp] box: the cell follows the height (7 day rows, 2
 * week rows, one row of month quads with their names), at most [cellCapDp]; as many columns as the width
 * takes, so a wider box looks further back. Pure.
 */
internal fun memoryWallFit(scale: MemoryGridScale, widthDp: Float, heightDp: Float, cellCapDp: Float? = null): MemoryWallFit {
    val rows = when (scale) {
        MemoryGridScale.Day -> 7
        MemoryGridScale.Week -> 2
        MemoryGridScale.Month -> 1
    }
    val label = if (scale == MemoryGridScale.Month) MemoryWallMonthLabelDp else 0f
    val rowGap = when (scale) {
        MemoryGridScale.Day -> 3f
        MemoryGridScale.Week -> 5f
        MemoryGridScale.Month -> 0f
    }
    val fromHeight = (heightDp - label - (rows - 1) * rowGap) / rows
    val cell = (if (cellCapDp != null) minOf(fromHeight, cellCapDp * (if (scale == MemoryGridScale.Month) 2 else 1)) else fromHeight)
        .coerceAtLeast(4f)
    fun columnsAt(gap: Float) = maxOf(1, kotlin.math.floor((widthDp + gap) / (cell + gap)).toInt())
    var gap = when (scale) {
        MemoryGridScale.Day -> 3f
        MemoryGridScale.Week -> 5f
        MemoryGridScale.Month -> 8f
    }
    var columns = columnsAt(gap)
    // A row of four months or fewer spaces its quads 12dp apart (MemoryGridView's MonthQuads).
    if (scale == MemoryGridScale.Month && columns <= 4) {
        gap = 12f
        columns = columnsAt(gap)
    }
    return MemoryWallFit(
        columns = columns,
        periods = if (scale == MemoryGridScale.Week) columns * rows else columns,
        cellDp = cell,
        widthDp = columns * cell + (columns - 1) * gap,
        heightDp = rows * cell + (rows - 1) * rowGap + label,
    )
}

/** The account's rated albums, newest first, each once, with its latest score. Pure. */
internal fun memoryRatedAlbums(entries: List<MemoryLedgerEntry>, profileId: String?): List<Pair<MemoryLedgerAlbum, Float>> =
    entries
        .filter { it.kind == MemoryLedgerKind.AlbumRating && it.album != null && it.score != null }
        .filter { profileId == null || it.profileId == profileId }
        .sortedByDescending { it.at }
        .distinctBy { it.album!!.key }
        .map { it.album!! to it.score!! }

@Composable
private fun MemoryLedgerUnits(
    state: MemoryLedgerState,
    units: Int,
    extractBackdropColors: Boolean,
    onOpen: () -> Unit,
    onAlbumClick: (MemoryLedgerAlbum) -> Unit,
    modifier: Modifier = Modifier,
) {
    val half = LocalHomeColumn.current.half
    val plan = memoryUnitLayout(half, units)
    val rated = remember(state.entries, state.activeProfileId) { memoryRatedAlbums(state.entries, state.activeProfileId) }
    val interactionSource = remember { MutableInteractionSource() }
    val openLabel = stringResource(R.string.memory_ledger_open)
    val firstDay = remember { java.time.temporal.WeekFields.of(java.util.Locale.getDefault()).firstDayOfWeek }
    Column(modifier = modifier) {
        HomeSectionTitle(text = stringResource(HomeSection.MemoryGrid.titleRes))
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val width = maxWidth.value
            val sideGap = 24f
            val wallBoxWidth = if (plan.side) (width * MemoryWallSideShare) else width
            val wallBoxHeight = homeGridContentDp(plan.wallUnits)
            val fit = memoryWallFit(
                scale = state.grid.scale,
                widthDp = wallBoxWidth,
                heightDp = wallBoxHeight,
                cellCapDp = if (plan.side) MemoryWallSideCellCapDp else null,
            )
            val wall = remember(state.entries, state.grid.scale, fit.periods, state.today, state.zone, firstDay) {
                buildMemoryGrid(state.entries, state.today, state.zone, firstDay, state.grid.scale, fit.periods)
            }
            val wallView = @Composable {
                Column(
                    modifier = Modifier
                        .width(wallBoxWidth.dp)
                        .height(wallBoxHeight.dp)
                        .noRippleClickable(
                            interactionSource = interactionSource,
                            enabled = homeEditInteractive(),
                            onClickLabel = openLabel,
                            onClick = onOpen,
                        )
                        .semantics { role = Role.Button },
                ) {
                    MemoryGridView(
                        grid = wall,
                        coverUrls = state.coverUrls,
                        placement = MemoryGridPlacement.Home,
                        extractColors = extractBackdropColors,
                        columns = fit.columns,
                        modifier = Modifier.width(fit.widthDp.dp),
                    )
                    // A full row's wall leaves room under it: the count the phone's card leads with.
                    if (plan.side && wallBoxHeight - fit.heightDp >= MemoryCountMinDp) {
                        Spacer(Modifier.height(12.dp))
                        MemoryLedgerCount(state)
                    }
                }
            }
            val albumRows = @Composable { albumsWidth: Float ->
                if (plan.albumRows > 0) {
                    val columns = homeGridColumns(albumsWidth)
                    val cells = remember(rated.size, columns, plan.albumRows) {
                        homeGridPack(List(rated.size) { 1 }, columns, plan.albumRows)
                    }
                    HomeGridCells(
                        cells = cells,
                        columns = columns,
                        gap = HomeGridTokens.ColumnGap,
                        modifier = Modifier.width(albumsWidth.dp),
                    ) { cell ->
                        val (album, score) = rated[cell.index]
                        MemoryRatedAlbumCard(
                            album = album,
                            score = score,
                            coverUrl = state.coverUrls[album.key],
                            extractBackdropColors = extractBackdropColors,
                            onClick = { onAlbumClick(album) },
                            modifier = Modifier.homeEditCard(cell.index),
                        )
                    }
                }
            }
            if (plan.side) {
                Row(horizontalArrangement = Arrangement.spacedBy(sideGap.dp)) {
                    wallView()
                    albumRows(width - wallBoxWidth - sideGap)
                }
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(HomeGridTokens.Gap)) {
                    wallView()
                    albumRows(width)
                }
            }
        }
    }
}

/** A full row gives its wall this share of the width; the rated albums take the rest. */
private const val MemoryWallSideShare = .38f

/** The count line needs this much room under a full row's wall. */
private const val MemoryCountMinDp = 40f

/** A rated album: its cover (U − 44) with the score on a seal at its lower right, its name and artist under. */
@OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun MemoryRatedAlbumCard(
    album: MemoryLedgerAlbum,
    score: Float,
    coverUrl: String?,
    extractBackdropColors: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val cover = homeGridCoverDp().dp
    Column(
        modifier = modifier
            .width(cover)
            .noRippleClickable(interactionSource = interactionSource, enabled = homeEditInteractive(), onClick = onClick)
            .elasticPress(interactionSource),
    ) {
        Box(Modifier.size(cover)) {
            WidgetBackdropArtwork(
                model = coverUrl,
                kind = WidgetShapeKind.Album,
                contentDescription = album.name,
                extractBackdropColors = extractBackdropColors,
                interactionSource = interactionSource,
                modifier = Modifier.size(cover),
            )
            Box(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .size(MemorySealSize)
                    .background(MaterialTheme.colorScheme.primaryContainer, androidx.compose.material3.MaterialShapes.Cookie9Sided.toShape()),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = String.format(java.util.Locale.ROOT, "%.1f", score),
                    style = MaterialTheme.typography.labelLarge.copy(fontFamily = GoogleSansFlex, fontWeight = FontWeight.SemiBold),
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                    maxLines = 1,
                )
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(
            text = album.name,
            style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Medium),
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            text = album.artist,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}


private val MemorySealSize = 40.dp
