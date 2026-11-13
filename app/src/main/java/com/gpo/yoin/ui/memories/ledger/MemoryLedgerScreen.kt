package com.gpo.yoin.ui.memories.ledger

import android.text.format.DateFormat
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.lazy.staggeredgrid.LazyStaggeredGridState
import androidx.compose.foundation.lazy.staggeredgrid.LazyVerticalStaggeredGrid
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridCells
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridItemSpan
import androidx.compose.foundation.lazy.staggeredgrid.items
import androidx.compose.foundation.lazy.staggeredgrid.rememberLazyStaggeredGridState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.derivedStateOf
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
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import com.gpo.yoin.R
import com.gpo.yoin.data.memory.MemoryGridCell
import com.gpo.yoin.data.memory.MemoryGridScale
import com.gpo.yoin.data.memory.MemoryLedgerAlbum
import com.gpo.yoin.data.memory.MemoryLedgerEntry
import com.gpo.yoin.data.memory.MemoryLedgerItem
import com.gpo.yoin.data.memory.MemoryLedgerKind
import com.gpo.yoin.data.memory.MemoryLedgerSort
import com.gpo.yoin.data.memory.MemoryLedgerView
import com.gpo.yoin.data.memory.MemoryTimelineRow
import com.gpo.yoin.data.memory.localDate
import com.gpo.yoin.data.memory.memoryLedgerItems
import com.gpo.yoin.data.memory.memoryTimelineRows
import com.gpo.yoin.data.memory.sortedFor
import com.gpo.yoin.symbols.YoinSymbols
import com.gpo.yoin.ui.component.ExpressiveSegmentChip
import com.gpo.yoin.ui.component.ExpressiveSegmentRow
import com.gpo.yoin.ui.component.YoinDropdownMenu
import com.gpo.yoin.ui.component.YoinDropdownMenuItem
import com.gpo.yoin.ui.component.YoinLoadingIndicator
import com.gpo.yoin.ui.component.noRippleClickable
import com.gpo.yoin.ui.component.ratingLabel
import com.gpo.yoin.ui.component.SeamTop
import com.gpo.yoin.ui.component.seamDissolve
import com.gpo.yoin.ui.component.seamDissolveViewport
import com.gpo.yoin.ui.component.seamFade
import com.gpo.yoin.ui.component.seamScrolledPx
import com.gpo.yoin.ui.detail.AlbumBarStatusBarEffect
import com.gpo.yoin.ui.experience.LocalYoinWindowInfo
import com.gpo.yoin.ui.settings.AccountMark
import com.gpo.yoin.ui.settings.SettingsBackButton
import com.gpo.yoin.ui.settings.SettingsHeadline
import com.gpo.yoin.ui.settings.SettingsPageBackground
import com.gpo.yoin.ui.settings.handoffProgress
import com.gpo.yoin.ui.settings.serviceIdentity
import com.gpo.yoin.ui.theme.GoogleSansFlex
import com.gpo.yoin.ui.theme.ProvideYoinMotionRole
import com.gpo.yoin.ui.theme.YoinMotionRole
import com.gpo.yoin.ui.theme.YoinSerifTitle
import java.text.Collator
import java.text.NumberFormat
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.util.Locale

/*
 * All Memories (owner 2026-11-08, views 2026-11-09): every memory on one page, so the listener can see what
 * Yoin keeps for them. From the top: what it adds up to, the grid (tap a cell to see just that stretch; it
 * rises into the top bar as the page scrolls), then one button for three views: the timeline, cards two a
 * row, and a sortable list. The pull-down Memories deck stays the way to come across them at random.
 */

/** The grid's place among the page's items (headline, stats, grid): how the lift knows it has gone above. */
private const val GridItemIndex = 2

/** The top app bar's own height, under the status bar. */
private val BarHeight = 64.dp

/**
 * The page. [onOpenAlbum] opens an album of the account in use; another account's albums don't open (that
 * would switch accounts: a later step).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun MemoryLedgerScreen(
    state: MemoryLedgerState?,
    onBackClick: () -> Unit,
    onOpenAlbum: (MemoryLedgerAlbum) -> Unit,
    modifier: Modifier = Modifier,
    // The cell picked on the grid (its index); hoisted so the Tips demo can pick one.
    selectedCellState: MutableState<Int?> = rememberSaveable { mutableStateOf(null) },
    // The view in use; the page remembers it between visits ([MemoryLedgerActivity]).
    viewState: MutableState<MemoryLedgerView> = rememberSaveable { mutableStateOf(MemoryLedgerView.Timeline) },
    // Names the grid for the Tips demo's touch.
    gridModifier: Modifier = Modifier,
    // The window's status bar icons follow the bar; off where the page is a picture (the Tips demo).
    drivesStatusBar: Boolean = true,
) {
    val title = stringResource(R.string.memory_ledger_title)
    val viewportTop = remember { mutableFloatStateOf(0f) }
    val headlineTop = remember { mutableFloatStateOf(Float.NaN) }
    val headlineHeight = remember { mutableFloatStateOf(1f) }
    var selectedCell by selectedCellState
    var view by viewState
    var sort by rememberSaveable { mutableStateOf(MemoryLedgerSort.Recent) }
    val listState = rememberLazyStaggeredGridState()
    val lift = remember { MemoryGridLift() }
    val barHeight = WindowInsets.statusBars.asPaddingValues().calculateTopPadding() + BarHeight
    val pixels = remember(state?.grid) { state?.grid?.let(::liftPixels).orEmpty() }
    val albumColors = rememberLedgerAlbumColors(
        albums = remember(state?.grid) { state?.grid?.cells?.let(::albumsIn).orEmpty() },
        coverUrls = state?.coverUrls.orEmpty(),
    )
    val faint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.07f)
    val paint = remember(albumColors, faint) { CellPaint(albumColors, faint) }
    // How far the grid has risen into the bar: read while drawing (and in derived state) only.
    val screen = remember { arrayOfNulls<LayoutCoordinates>(1) }
    val liftProgress: (LayoutCoordinates?, Float) -> Float = { into, barBottom ->
        // Read for invalidation: every scroll frame redraws the lift.
        listState.firstVisibleItemScrollOffset
        val scrolledPast = listState.firstVisibleItemIndex > GridItemIndex
        val measured = into?.let { lift.progress(it, barBottom) }
        when {
            state == null || state.isEmpty || pixels.isEmpty() -> 0f
            measured != null -> measured
            scrolledPast -> 1f
            else -> 0f
        }
    }
    val density = LocalDensity.current
    val barBottomPx = with(density) { barHeight.toPx() }
    val formed by remember(pixels) {
        derivedStateOf { liftProgress(screen[0], barBottomPx) > GridLiftFormed }
    }
    if (drivesStatusBar) {
        AlbumBarStatusBarEffect(enabled = true, docked = { formed }, darkTheme = isSystemInDarkTheme())
    }
    val barContent by animateColorAsState(
        targetValue = if (formed) Color.White else MaterialTheme.colorScheme.onSurface,
        label = "ledgerBarContent",
    )
    ProvideYoinMotionRole(role = YoinMotionRole.Standard) {
        SettingsPageBackground(modifier = modifier) {
            // The page is no Surface: without this, text that sets no colour of its own is black in dark mode.
            CompositionLocalProvider(LocalContentColor provides MaterialTheme.colorScheme.onSurface) {
            Box(Modifier.fillMaxSize().onGloballyPositioned { screen[0] = it }) {
                val navBottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
                if (state == null) {
                    Box(Modifier.fillMaxSize().padding(top = barHeight), contentAlignment = Alignment.Center) {
                        YoinLoadingIndicator()
                    }
                } else {
                    LedgerBody(
                        state = state,
                        title = title,
                        view = view,
                        onView = { view = it },
                        sort = sort,
                        onSort = { sort = it },
                        selectedCell = selectedCell,
                        onSelectCell = { selectedCell = it },
                        listState = listState,
                        lift = lift,
                        albumColors = albumColors,
                        // The real grid steps aside the moment its copies take off, so no square shows twice.
                        gridAlpha = { if (liftProgress(screen[0], barBottomPx) > 0.001f) 0f else 1f },
                        onOpenAlbum = { album -> if (album.profileId == state.activeProfileId) onOpenAlbum(album) },
                        gridModifier = gridModifier,
                        headlineModifier = Modifier.onGloballyPositioned {
                            headlineTop.floatValue = it.positionInWindow().y
                            headlineHeight.floatValue = it.size.height.toFloat().coerceAtLeast(1f)
                        },
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(top = barHeight)
                            .onGloballyPositioned { viewportTop.floatValue = it.positionInWindow().y },
                        contentPadding = PaddingValues(
                            start = 16.dp,
                            top = 8.dp,
                            end = 16.dp,
                            bottom = 24.dp + navBottom,
                        ),
                    )
                    MemoryGridLiftLayer(
                        lift = lift,
                        pixels = pixels,
                        paint = paint,
                        barHeight = barHeight,
                        progress = { into, barBottom -> liftProgress(into, barBottom) },
                        modifier = Modifier.fillMaxSize(),
                    )
                }
                TopAppBar(
                    title = {
                        Text(
                            text = title,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.graphicsLayer {
                                // Once the headline's item has left the list it stops reporting where it is (its last,
                                // half-way place would hold the title half faded): past it, the title is simply in.
                                alpha = if (listState.firstVisibleItemIndex > 0) {
                                    1f
                                } else {
                                    handoffProgress(viewportTop, headlineTop, headlineHeight)
                                }
                                translationY = (1f - alpha) * size.height * 0.5f
                            },
                        )
                    },
                    navigationIcon = { SettingsBackButton(onClick = onBackClick) },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = Color.Transparent,
                        scrolledContainerColor = Color.Transparent,
                        navigationIconContentColor = barContent,
                        titleContentColor = barContent,
                    ),
                )
            }
            }
        }
    }
}

@Composable
private fun LedgerBody(
    state: MemoryLedgerState,
    title: String,
    view: MemoryLedgerView,
    onView: (MemoryLedgerView) -> Unit,
    sort: MemoryLedgerSort,
    onSort: (MemoryLedgerSort) -> Unit,
    selectedCell: Int?,
    onSelectCell: (Int?) -> Unit,
    listState: LazyStaggeredGridState,
    lift: MemoryGridLift,
    albumColors: Map<String, LedgerAlbumColors>,
    gridAlpha: () -> Float,
    onOpenAlbum: (MemoryLedgerAlbum) -> Unit,
    gridModifier: Modifier,
    headlineModifier: Modifier,
    modifier: Modifier,
    contentPadding: PaddingValues,
) {
    // A cell from before a reload may not be there any more.
    val cell = selectedCell?.let(state.grid.cells::getOrNull)
    val shown = remember(state, cell) {
        if (cell == null) {
            state.entries
        } else {
            state.entries.filter { entry -> entry.localDate(state.zone) in cell.start..cell.end }
        }
    }
    val locale = LocalConfiguration.current.locales[0]
    val timeline = remember(shown, view) {
        if (view != MemoryLedgerView.Timeline) {
            emptyList()
        } else {
            memoryTimelineRows(shown, state.today, state.zone)
                // A stretch picked on the grid: just its months, no quiet ones around it.
                .filter { cell == null || it !is MemoryTimelineRow.Quiet }
        }
    }
    val items = remember(shown, view, sort, locale) {
        when (view) {
            MemoryLedgerView.Timeline -> emptyList()
            MemoryLedgerView.Cards -> memoryLedgerItems(shown)
            MemoryLedgerView.List -> memoryLedgerItems(shown).sortedFor(sort, Collator.getInstance(locale))
        }
    }
    val full = StaggeredGridItemSpan.FullLine
    BoxWithConstraints(modifier) {
        // A large screen is used, not left as margins (owner 2026-11-12): the stats run large above a wider
        // grid, and the timeline and the cards run in as many columns as fit, so they fall into a masonry.
        val layout = ledgerLayoutFor(maxWidth, LocalYoinWindowInfo.current.isCompactHeight)
        val gridView = @Composable { gridItemModifier: Modifier ->
            MemoryGridView(
                    grid = state.grid,
                    coverUrls = state.coverUrls,
                    placement = MemoryGridPlacement.Page,
                    selected = selectedCell,
                    onCellClick = { index -> onSelectCell(if (selectedCell == index) null else index) },
                    // Every mark the month holds, as the timeline's month header counts them.
                    monthCaption = { monthCell ->
                        if (monthCell.entries > 0) {
                            val count = monthCell.entries
                            pluralStringResource(R.plurals.memory_ledger_month_count, count, count)
                        } else {
                            "—"
                        }
                    },
                albumColors = albumColors,
                lift = lift,
                // A full-line item is given the whole width: let go of it first, or the cap does nothing.
                modifier = gridItemModifier
                    // Centred on a large screen, so the cap doesn't leave one side empty.
                    .wrapContentWidth(if (layout.wide) Alignment.CenterHorizontally else Alignment.Start)
                    .widthIn(max = if (layout.wide) 760.dp else 640.dp)
                    .seamDissolve()
                    .onGloballyPositioned { lift.grid = it }
                    .graphicsLayer { alpha = gridAlpha() }
                    .then(gridModifier),
            )
        }
        LazyVerticalStaggeredGrid(
            columns = StaggeredGridCells.Fixed(layout.lanes),
            state = listState,
            modifier = Modifier
                .align(Alignment.TopCenter)
                .widthIn(max = layout.maxWidth)
                .fillMaxSize()
                .seamDissolveViewport(top = SeamTop.Chrome) { listState.seamScrolledPx() },
            contentPadding = contentPadding,
            horizontalArrangement = Arrangement.spacedBy(layout.gutter),
        ) {
            item("headline", span = full) {
                SettingsHeadline(title = title, titleModifier = headlineModifier, modifier = Modifier.seamFade())
            }
            if (state.isEmpty) {
                item("empty", span = full) { LedgerEmpty() }
                return@LazyVerticalStaggeredGrid
            }
            // On a large screen the figures run larger across the top and the grid grows wider, so nothing is
            // left beside the grid to float alone once it has risen into the bar.
            item("stats", span = full) {
                LedgerStats(
                    state,
                    large = layout.wide,
                    modifier = Modifier
                        .padding(top = 8.dp)
                        .then(if (layout.wide) Modifier.wrapContentWidth().widthIn(max = 760.dp) else Modifier),
                )
            }
            item("grid", span = full) { gridView(Modifier.padding(top = 24.dp)) }
            item("bar", span = full) {
                LedgerViewBar(
                    view = view,
                    onView = onView,
                    sort = sort,
                    onSort = onSort,
                    cell = cell,
                    scale = state.grid.scale,
                    onClearCell = { onSelectCell(null) },
                    modifier = Modifier.padding(top = 20.dp, bottom = 4.dp),
                )
            }
            if (shown.isEmpty()) {
                item("range-empty", span = full) {
                    Text(
                        text = stringResource(R.string.memory_ledger_range_empty),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(vertical = 24.dp).seamFade(),
                    )
                }
            }
            when (view) {
                MemoryLedgerView.Timeline -> items(
                    timeline,
                    key = { it.key },
                    // One column on a phone (album cards alternate sides); a masonry wider than that.
                    span = { row ->
                        if (layout.columns == 1 || row is MemoryTimelineRow.Month || row is MemoryTimelineRow.Quiet) {
                            full
                        } else {
                            StaggeredGridItemSpan.SingleLane
                        }
                    },
                ) { row ->
                    when (row) {
                        is MemoryTimelineRow.Month -> MonthHeader(row.month, row.count, state.today, layout.wide)
                        is MemoryTimelineRow.Album -> TimelineAlbumCard(row, state, onOpenAlbum, masonry = layout.columns > 1)
                        is MemoryTimelineRow.Note -> NoteCard(
                            entry = row.entry,
                            state = state,
                            onClick = { row.entry.album?.let(onOpenAlbum) },
                        )
                        is MemoryTimelineRow.Scores -> ScoresCard(row.entries, state)
                        is MemoryTimelineRow.Quiet -> QuietMonths(row.from, row.to, state.today)
                    }
                }
                MemoryLedgerView.Cards -> items(items, key = { it.key }) { item ->
                    when (item) {
                        is MemoryLedgerItem.Album -> AlbumGridCard(item, state, onOpenAlbum)
                        is MemoryLedgerItem.Song -> SongGridCard(item, state, onOpenAlbum)
                    }
                }
                MemoryLedgerView.List -> items(items, key = { it.key }, span = { full }) { item ->
                    LedgerListRow(item, state, onOpenAlbum)
                }
            }
            item("since", span = full) {
                state.stats.firstAt?.let { first ->
                    Text(
                        text = stringResource(R.string.memory_ledger_since, longDate(first, state)),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.outline,
                        modifier = Modifier.padding(top = 28.dp).seamFade(),
                    )
                }
            }
        }
    }
}

@Composable
private fun LedgerEmpty() {
    Column(Modifier.padding(top = 32.dp).seamFade(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = stringResource(R.string.memory_ledger_empty_title),
            style = MaterialTheme.typography.titleMedium,
        )
    }
}

@Composable
private fun LedgerStats(state: MemoryLedgerState, modifier: Modifier = Modifier, large: Boolean = false) {
    val numbers = remember { NumberFormat.getIntegerInstance() }
    val stats = state.stats
    val figures = listOf(
        stats.albums to R.string.memory_ledger_stat_albums,
        stats.writings to R.string.memory_ledger_stat_writings,
        stats.characters to R.string.memory_ledger_stat_characters,
        stats.ratedTracks to R.string.memory_ledger_stat_rated,
    )
    // A large screen gives the numbers room to be the headline figures they are.
    val figure = @Composable { value: Int, label: Int, figureModifier: Modifier ->
        Column(figureModifier.seamFade()) {
            Text(
                text = numbers.format(value),
                style = MaterialTheme.typography.titleLarge.copy(
                    fontFamily = GoogleSansFlex,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = if (large) 40.sp else 24.sp,
                    lineHeight = if (large) 46.sp else 30.sp,
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
    Row(modifier = modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(if (large) 24.dp else 12.dp)) {
        figures.forEach { (value, label) -> figure(value, label, Modifier.weight(1f)) }
    }
}

/** Under the grid: the picked stretch (tap to clear) on the start side; the list's order and the view on the end. */
@Composable
private fun LedgerViewBar(
    view: MemoryLedgerView,
    onView: (MemoryLedgerView) -> Unit,
    sort: MemoryLedgerSort,
    onSort: (MemoryLedgerSort) -> Unit,
    cell: MemoryGridCell?,
    scale: MemoryGridScale,
    onClearCell: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth().seamDissolve(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(Modifier.weight(1f)) {
            if (cell != null) {
                ExpressiveSegmentRow {
                    ExpressiveSegmentChip(
                        label = cellLabel(cell, scale),
                        trailingIcon = YoinSymbols.Close,
                        selected = true,
                        onClick = onClearCell,
                    )
                }
            }
        }
        if (view == MemoryLedgerView.List) {
            LedgerMenuButton(
                label = sortLabel(sort),
                leading = { tint ->
                    Icon(YoinSymbols.Sort, contentDescription = null, tint = tint, modifier = Modifier.size(18.dp))
                },
                options = MemoryLedgerSort.entries,
                selected = sort,
                optionLabel = { sortLabel(it) },
                optionIcon = null,
                onSelect = onSort,
            )
        }
        LedgerMenuButton(
            label = viewLabel(view),
            leading = { tint -> ViewGlyph(view, tint) },
            options = MemoryLedgerView.entries,
            selected = view,
            optionLabel = { viewLabel(it) },
            optionIcon = { option, tint -> ViewGlyph(option, tint) },
            onSelect = onView,
        )
    }
}

@Composable
private fun <T> LedgerMenuButton(
    label: String,
    leading: @Composable (Color) -> Unit,
    options: List<T>,
    selected: T,
    optionLabel: @Composable (T) -> String,
    optionIcon: (@Composable (T, Color) -> Unit)?,
    onSelect: (T) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val tint = MaterialTheme.colorScheme.onSurfaceVariant
    Box {
        Row(
            modifier = Modifier
                .height(36.dp)
                .clip(RoundedCornerShape(18.dp))
                .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                .clickable(role = Role.DropdownList) { expanded = true }
                .padding(start = 10.dp, end = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            leading(tint)
            Text(text = label, style = MaterialTheme.typography.labelLarge)
        }
        YoinDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            options.forEach { option ->
                YoinDropdownMenuItem(
                    text = optionLabel(option),
                    onClick = {
                        expanded = false
                        if (option != selected) onSelect(option)
                    },
                    leadingIcon = if (optionIcon != null) {
                        { optionIcon(option, tint) }
                    } else {
                        null
                    },
                    trailingIcon = if (option == selected) {
                        { Icon(YoinSymbols.Check, contentDescription = null) }
                    } else {
                        null
                    },
                )
            }
        }
    }
}

@Composable
private fun viewLabel(view: MemoryLedgerView): String = stringResource(
    when (view) {
        MemoryLedgerView.Timeline -> R.string.memory_ledger_view_timeline
        MemoryLedgerView.Cards -> R.string.memory_ledger_view_cards
        MemoryLedgerView.List -> R.string.memory_ledger_view_list
    },
)

@Composable
private fun sortLabel(sort: MemoryLedgerSort): String = stringResource(
    when (sort) {
        MemoryLedgerSort.Recent -> R.string.memory_ledger_sort_recent
        MemoryLedgerSort.Score -> R.string.memory_ledger_sort_score
        MemoryLedgerSort.Name -> R.string.memory_ledger_sort_name
    },
)

/** A small drawn mark per view (Yoin Symbols has none): a spine with stops, four tiles, three lines. */
@Composable
private fun ViewGlyph(view: MemoryLedgerView, tint: Color) {
    Canvas(Modifier.size(18.dp)) {
        val s = size.width
        val stroke = 1.8.dp.toPx()
        when (view) {
            MemoryLedgerView.Timeline -> {
                drawLine(tint, Offset(s * 0.25f, s * 0.1f), Offset(s * 0.25f, s * 0.9f), stroke)
                drawCircle(tint, s * 0.11f, Offset(s * 0.25f, s * 0.3f))
                drawCircle(tint, s * 0.11f, Offset(s * 0.25f, s * 0.72f))
                drawLine(tint, Offset(s * 0.48f, s * 0.3f), Offset(s * 0.92f, s * 0.3f), stroke)
                drawLine(tint, Offset(s * 0.48f, s * 0.72f), Offset(s * 0.78f, s * 0.72f), stroke)
            }
            MemoryLedgerView.Cards -> {
                val gap = s * 0.12f
                val w = (s - gap) / 2f
                val r = CornerRadius(s * 0.1f)
                drawRoundRect(tint, Offset(0f, 0f), Size(w, s * 0.55f), r, style = Stroke(stroke))
                drawRoundRect(tint, Offset(w + gap, 0f), Size(w, s * 0.35f), r, style = Stroke(stroke))
                drawRoundRect(tint, Offset(0f, s * 0.55f + gap), Size(w, s * 0.45f - gap), r, style = Stroke(stroke))
                drawRoundRect(
                    tint,
                    Offset(w + gap, s * 0.35f + gap),
                    Size(w, s * 0.65f - gap),
                    r,
                    style = Stroke(stroke),
                )
            }
            MemoryLedgerView.List -> {
                listOf(0.22f, 0.5f, 0.78f).forEach { y ->
                    drawLine(tint, Offset(s * 0.08f, s * y), Offset(s * 0.92f, s * y), stroke)
                }
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

/** A day as the cards and rows show it: "Nov 8", with the year when it isn't this one. */
private fun shortDay(at: Long, state: MemoryLedgerState): String {
    val locale = Locale.getDefault()
    val date = Instant.ofEpochMilli(at).atZone(state.zone).toLocalDate()
    val skeleton = if (date.year == state.today.year) "MMMd" else "yMMMd"
    return DateTimeFormatter.ofPattern(DateFormat.getBestDateTimePattern(locale, skeleton), locale).format(date)
}

@Composable
private fun MonthHeader(month: YearMonth, count: Int, today: LocalDate, wide: Boolean) {
    // The month is the page's landmark: large and in the serif, so the eye finds where it is while scrolling.
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = if (wide) 48.dp else 36.dp, bottom = if (wide) 12.dp else 8.dp)
            .seamFade(fontSize = if (wide) 40.sp else 30.sp),
        verticalAlignment = Alignment.Bottom,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            text = monthName(month, today),
            style = MaterialTheme.typography.headlineLarge.copy(
                fontFamily = YoinSerifTitle,
                fontWeight = FontWeight.SemiBold,
                fontSize = if (wide) 40.sp else 30.sp,
                lineHeight = if (wide) 46.sp else 36.sp,
            ),
        )
        Text(
            text = pluralStringResource(R.plurals.memory_ledger_month_count, count, count),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(bottom = if (wide) 8.dp else 6.dp),
        )
    }
}

@Composable
private fun QuietMonths(from: YearMonth, to: YearMonth, today: LocalDate) {
    val dash = MaterialTheme.colorScheme.outlineVariant
    val text = if (from == to) {
        stringResource(R.string.memory_ledger_quiet_one, monthName(from, today))
    } else {
        // Earlier month first: "July to August".
        stringResource(R.string.memory_ledger_quiet_range, monthName(to, today), monthName(from, today))
    }
    // The text sits on the page itself (no patch behind it), between two dashed rules.
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 14.dp)
            .seamFade(),
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
            // The receipt's dash (About), so the two read as one family.
            pathEffect = PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 3.dp.toPx())),
        )
    }
}

/** The big number and what it scores, as the album card shows it. */
@Composable
private fun ScoreLine(score: Float, label: String?, size: Int = 26) {
    Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = ratingLabel(score).orEmpty(),
            style = MaterialTheme.typography.titleLarge.copy(
                fontFamily = GoogleSansFlex,
                fontWeight = FontWeight.SemiBold,
                fontSize = size.sp,
            ),
        )
        if (label != null) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 3.dp),
            )
        }
    }
}

/**
 * An album's month on the timeline: the cover whole, then what was kept on it. No container (owner
 * 2026-11-09): the cards stand on the page. On a phone they alternate sides with song notes small between
 * them; wider, they fill a column of the masonry. The memory title is the loudest line, the score next.
 */
@Composable
private fun TimelineAlbumCard(
    row: MemoryTimelineRow.Album,
    state: MemoryLedgerState,
    onOpen: (MemoryLedgerAlbum) -> Unit,
    masonry: Boolean,
) {
    val item = row.item
    val interaction = remember { MutableInteractionSource() }
    // Owner 2026-11-12: the covers take turns starting from the left and from the right, but what's written
    // under them always runs the full width.
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = if (masonry) 12.dp else 16.dp)
            .noRippleClickable(interaction, enabled = item.album.profileId == state.activeProfileId) {
                onOpen(item.album)
            },
    ) {
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val width: Dp = if (masonry) maxWidth else (maxWidth * 0.66f).coerceAtMost(300.dp)
            LedgerCover(
                item.album,
                state,
                Modifier.align(if (row.end && !masonry) Alignment.TopEnd else Alignment.TopStart).width(width),
                corner = 16.dp,
            )
        }
        run {
            Column(
                modifier = Modifier.fillMaxWidth().padding(top = 14.dp).seamFade(),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                item.title?.let {
                    Text(
                        text = it,
                        style = MaterialTheme.typography.headlineSmall.copy(
                            fontFamily = YoinSerifTitle,
                            fontWeight = FontWeight.SemiBold,
                        ),
                    )
                }
                LedgerPair(
                    first = item.album.name,
                    second = item.album.artist.takeIf(String::isNotBlank),
                    style = MaterialTheme.typography.bodyMedium,
                )
                item.score?.let {
                    ScoreLine(it, stringResource(R.string.memory_ledger_album_score), size = 34)
                }
                item.review?.let {
                    Text(
                        text = it,
                        style = MaterialTheme.typography.bodyLarge,
                        maxLines = 6,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = 2.dp),
                    )
                }
                if (item.songs.isNotEmpty()) {
                    // A dashed rule over each line, as About's receipt does (owner 2026-11-12).
                    val rule = MaterialTheme.colorScheme.outlineVariant
                    Column(Modifier.padding(top = 6.dp)) {
                        item.songs.forEach { song ->
                            DashedRule(rule, Modifier.fillMaxWidth())
                            SongScoreRow(song.title, song.score, Modifier.padding(vertical = 8.dp))
                        }
                        DashedRule(rule, Modifier.fillMaxWidth())
                    }
                }
                Row(
                    modifier = Modifier.padding(top = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Text(
                        text = shortDay(item.at, state),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.outline,
                    )
                    FromLine(item.profileId, state)
                }
            }
        }
    }
}

@Composable
private fun SongScoreRow(title: String?, score: Float, modifier: Modifier = Modifier) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = title ?: stringResource(R.string.memory_ledger_unknown_song),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = ratingLabel(score).orEmpty(),
            style = MaterialTheme.typography.labelLarge.copy(
                fontFamily = GoogleSansFlex,
                fontWeight = FontWeight.SemiBold,
            ),
        )
    }
}

/** Another account's memory says whose it is (with its mark), as the deck's "From …" line does. */
@Composable
private fun FromLine(profileId: String, state: MemoryLedgerState) {
    val owner = state.profile(profileId)
    if (owner == null || state.profiles.size < 2 || owner.isActive) return
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
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

/** Cards, two a row: an album (its cover whole, its title, its score). */
@Composable
private fun AlbumGridCard(item: MemoryLedgerItem.Album, state: MemoryLedgerState, onOpen: (MemoryLedgerAlbum) -> Unit) {
    GridCard(enabled = item.album.profileId == state.activeProfileId, onClick = { onOpen(item.album) }) {
        LedgerCover(item.album, state, Modifier.fillMaxWidth(), corner = 12.dp)
        Spacer(Modifier.height(6.dp))
        item.title?.let {
            Text(
                text = it,
                style = MaterialTheme.typography.titleMedium.copy(
                    fontFamily = YoinSerifTitle,
                    fontWeight = FontWeight.SemiBold,
                ),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Text(
            text = item.album.name,
            style = MaterialTheme.typography.labelLarge,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (item.album.artist.isNotBlank()) {
            Text(
                text = item.album.artist,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        CardFoot(item.score, item.at, state)
    }
}

/** Cards, two a row: a song (a small cover, its name, its newest note, its score). */
@Composable
private fun SongGridCard(item: MemoryLedgerItem.Song, state: MemoryLedgerState, onOpen: (MemoryLedgerAlbum) -> Unit) {
    val album = item.album
    GridCard(enabled = album != null && album.profileId == state.activeProfileId, onClick = { album?.let(onOpen) }) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            LedgerCover(album, state, Modifier.size(44.dp), corner = 8.dp)
            Column(Modifier.weight(1f)) {
                Text(
                    text = item.title ?: stringResource(R.string.memory_ledger_unknown_song),
                    style = MaterialTheme.typography.labelLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = album?.artist?.takeIf(String::isNotBlank)
                        ?: album?.name
                        ?: stringResource(R.string.memory_ledger_unknown_album),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        item.note?.text?.takeIf(String::isNotBlank)?.let {
            Text(
                text = it,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 5,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 6.dp),
            )
        }
        CardFoot(item.score, item.at, state)
    }
}

@Composable
private fun GridCard(enabled: Boolean, onClick: () -> Unit, content: @Composable () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    Column(
        modifier = Modifier
            .padding(bottom = 12.dp)
            .fillMaxWidth()
            .seamDissolve()
            .clip(RoundedCornerShape(20.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerLow)
            .noRippleClickable(interaction, enabled = enabled, onClick = onClick)
            .padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        content()
    }
}

/** A card's foot: the score (when there is one) on the start side, the day on the end. */
@Composable
private fun CardFoot(score: Float?, at: Long, state: MemoryLedgerState) {
    Row(Modifier.fillMaxWidth().padding(top = 4.dp), verticalAlignment = Alignment.Bottom) {
        Box(Modifier.weight(1f)) { score?.let { ScoreLine(it, null, size = 20) } }
        Text(
            text = shortDay(at, state),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.outline,
            modifier = Modifier.padding(bottom = 3.dp),
        )
    }
}

/**
 * The list: a line each. An album's line stands taller (its cover larger, and its memory title under its
 * name); a song's is shorter (owner 2026-11-09: told apart by height, not by shape).
 */
@Composable
private fun LedgerListRow(item: MemoryLedgerItem, state: MemoryLedgerState, onOpen: (MemoryLedgerAlbum) -> Unit) {
    val album = item.album
    val interaction = remember { MutableInteractionSource() }
    val isAlbum = item is MemoryLedgerItem.Album
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .noRippleClickable(interaction, enabled = album != null && album.profileId == state.activeProfileId) {
                album?.let(onOpen)
            }
            .padding(vertical = if (isAlbum) 10.dp else 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        LedgerCover(album, state, Modifier.size(if (isAlbum) 56.dp else 40.dp), corner = 8.dp)
        Column(Modifier.weight(1f).seamFade(), verticalArrangement = Arrangement.spacedBy(1.dp)) {
            Text(
                text = item.name.ifBlank { stringResource(R.string.memory_ledger_unknown_song) },
                style = MaterialTheme.typography.labelLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (item is MemoryLedgerItem.Album && item.title != null) {
                Text(
                    text = item.title,
                    style = MaterialTheme.typography.bodyMedium.copy(fontFamily = YoinSerifTitle),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            val under = when (item) {
                is MemoryLedgerItem.Album -> item.album.artist
                is MemoryLedgerItem.Song -> album?.artist?.takeIf(String::isNotBlank) ?: album?.name.orEmpty()
            }
            if (under.isNotBlank()) {
                Text(
                    text = under,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Text(
            text = shortDay(item.at, state),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.outline,
            modifier = Modifier.seamFade(),
        )
        Text(
            text = item.score?.let { ratingLabel(it) } ?: "—",
            style = MaterialTheme.typography.titleSmall.copy(
                fontFamily = GoogleSansFlex,
                fontWeight = FontWeight.SemiBold,
            ),
            color = if (item.score == null) MaterialTheme.colorScheme.outline else MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.widthIn(min = 32.dp).seamFade(),
        )
    }
}

/**
 * A song note on the timeline: what was written leads, large, in the serif (it's the listener's own voice);
 * the song and where in it under it, then its album small beside a small cover. On a soft patch, so the
 * notes read apart from the album cards that stand bare on the page.
 */
@Composable
private fun NoteCard(entry: MemoryLedgerEntry, state: MemoryLedgerState, onClick: () -> Unit) {
    val album = entry.album
    val opens = album != null && entry.profileId == state.activeProfileId
    val interaction = remember { MutableInteractionSource() }
    Column(
        modifier = Modifier
            .padding(vertical = 8.dp)
            .fillMaxWidth()
            .seamDissolve()
            .clip(RoundedCornerShape(24.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerLow)
            .noRippleClickable(interaction, enabled = opens, onClick = onClick)
            .padding(horizontal = 18.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = kindLabel(entry.kind),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = shortDay(entry.at, state),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.outline,
                maxLines = 1,
            )
        }
        when {
            !entry.text.isNullOrBlank() -> Text(
                text = entry.text,
                style = MaterialTheme.typography.titleMedium.copy(
                    fontFamily = YoinSerifTitle,
                    fontSize = 19.sp,
                    lineHeight = 28.sp,
                ),
                maxLines = 10,
                overflow = TextOverflow.Ellipsis,
            )
            entry.score != null -> ScoreLine(entry.score, null, size = 30)
        }
        // A song note: the song, and where in it (lighter).
        entry.trackTitle?.let { song ->
            LedgerPair(
                first = song,
                second = entry.positionMs?.takeIf { it > 0 }
                    ?.let { ms -> "${ms / 60_000}:${"%02d".format((ms / 1000) % 60)}" },
                style = MaterialTheme.typography.labelLarge,
            )
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            LedgerCover(album, state, Modifier.size(32.dp), corner = 6.dp)
            Column(Modifier.weight(1f)) {
                LedgerPair(
                    first = album?.name ?: stringResource(R.string.memory_ledger_unknown_album),
                    second = album?.artist?.takeIf(String::isNotBlank),
                    style = MaterialTheme.typography.bodySmall,
                )
                FromLine(entry.profileId, state)
            }
        }
    }
}

/**
 * A month's song scores whose album nobody could name, on one card: each song and its score, best first,
 * instead of a run of identical rows.
 */
@Composable
private fun ScoresCard(entries: List<MemoryLedgerEntry>, state: MemoryLedgerState) {
    val sorted = remember(entries) { entries.sortedByDescending { it.score ?: 0f } }
    Column(
        modifier = Modifier
            .padding(vertical = 8.dp)
            .fillMaxWidth()
            .seamDissolve()
            .clip(RoundedCornerShape(24.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerLow)
            .padding(horizontal = 18.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = kindLabel(MemoryLedgerKind.TrackRating),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = shortDay(entries.first().at, state),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.outline,
            )
        }
        val rule = MaterialTheme.colorScheme.outlineVariant
        sorted.forEachIndexed { i, entry ->
            if (i > 0) DashedRule(rule, Modifier.fillMaxWidth())
            Row(Modifier.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = entry.trackTitle ?: stringResource(R.string.memory_ledger_unknown_song),
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text = ratingLabel(entry.score ?: 0f).orEmpty(),
                    style = MaterialTheme.typography.titleMedium.copy(
                        fontFamily = GoogleSansFlex,
                        fontWeight = FontWeight.SemiBold,
                    ),
                )
            }
        }
        entries.map { it.profileId }.distinct().forEach { FromLine(it, state) }
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

/**
 * An album's cover, whole (nothing on it, never cut), square at [modifier]'s width; or its colours when
 * there's no cover; or a faint square when the album isn't known.
 */
@Composable
private fun LedgerCover(album: MemoryLedgerAlbum?, state: MemoryLedgerState, modifier: Modifier, corner: Dp) {
    val url = album?.let { state.coverUrls[it.key] }
    val shape = RoundedCornerShape(corner)
    if (url != null) {
        AsyncImage(
            model = url,
            contentDescription = null,
            contentScale = ContentScale.Fit,
            modifier = modifier.aspectRatio(1f).seamDissolve().clip(shape),
        )
    } else {
        val colors = album?.let { fallbackAlbumColors(it.key) }
        val faint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.07f)
        Canvas(modifier.aspectRatio(1f).seamDissolve()) {
            val r = CornerRadius(corner.toPx())
            if (colors == null) {
                drawRoundRect(faint, cornerRadius = r)
            } else {
                drawRoundRect(colors.base, cornerRadius = r)
                drawRoundRect(colors.accent, cornerRadius = r, style = Stroke(2.dp.toPx()))
            }
        }
    }
}

/** How the page lays out at a width: how many lanes, how wide it may grow, whether the top runs large. */
internal data class LedgerLayout(
    /** Larger stats over a wider, centred grid, and larger month headers. */
    val wide: Boolean,
    /** The timeline's columns: 1 on a phone (everything full width), more as a masonry. */
    val columns: Int,
    /** The lazy grid's lanes (the cards view uses them all). */
    val lanes: Int,
    val maxWidth: Dp,
    val gutter: Dp,
)

internal fun ledgerLayoutFor(width: Dp, compactHeight: Boolean): LedgerLayout = when {
    // A short window (a landscape phone) is the phone layout whatever its width: height is judged first.
    compactHeight -> LedgerLayout(wide = false, columns = 1, lanes = 2, maxWidth = 720.dp, gutter = 12.dp)
    width >= 1100.dp -> LedgerLayout(wide = true, columns = 3, lanes = 3, maxWidth = 1240.dp, gutter = 28.dp)
    width >= 840.dp -> LedgerLayout(wide = true, columns = 2, lanes = 2, maxWidth = 1040.dp, gutter = 28.dp)
    width >= 600.dp -> LedgerLayout(wide = false, columns = 2, lanes = 2, maxWidth = 840.dp, gutter = 20.dp)
    else -> LedgerLayout(wide = false, columns = 1, lanes = 2, maxWidth = 720.dp, gutter = 12.dp)
}
