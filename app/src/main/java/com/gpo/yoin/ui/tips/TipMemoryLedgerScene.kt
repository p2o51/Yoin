package com.gpo.yoin.ui.tips

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.gpo.yoin.data.memory.MemoryGrid
import com.gpo.yoin.data.memory.MemoryGridScale
import com.gpo.yoin.data.memory.MemoryLedgerAlbum
import com.gpo.yoin.data.memory.MemoryLedgerEntry
import com.gpo.yoin.data.memory.MemoryLedgerKind
import com.gpo.yoin.data.memory.buildMemoryGrid
import com.gpo.yoin.data.memory.memoryLedgerStats
import com.gpo.yoin.data.profile.ProviderKind
import com.gpo.yoin.ui.home.MemoryLedgerSection
import com.gpo.yoin.ui.memories.ledger.LedgerProfile
import com.gpo.yoin.ui.memories.ledger.MemoryLedgerScreen
import com.gpo.yoin.ui.memories.ledger.MemoryLedgerState
import com.gpo.yoin.ui.theme.YoinMotion
import com.gpo.yoin.ui.theme.YoinMotionRole
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.WeekFields
import java.util.Locale
import kotlinx.coroutines.delay

/**
 * All Memories (owner 2026-11-08): Home's Memories grid opens the page, and a tap on a cell narrows the
 * list to that stretch. The real Home card and the real page, over made-up memories.
 */
@Composable
internal fun MemoryLedgerDemo(playing: Boolean) {
    val covers = demoCoverUrls()
    val state = remember(covers) { demoLedgerState(covers) }
    var open by remember { mutableStateOf(false) }
    val selected = remember { mutableStateOf<Int?>(null) }
    // The newest week with something in it: the cell the demo picks.
    val pick = remember(state) { state.grid.cells.indexOfLast { !it.isEmpty && !it.future }.takeIf { it >= 0 } }
    DemoPhone(playing = playing, script = {
        open = false
        selected.value = null
        delay(900)
        moveTo("ledgerHome", 0.5f, 0.6f)
        tap { open = true }
        delay(1_100)
        if (pick != null) {
            val (fx, fy) = weekCellFraction(pick)
            moveTo("ledgerGrid", fx, fy)
            tap { selected.value = pick }
        }
        delay(2_600)
    }) {
        AnimatedContent(
            targetState = open,
            transitionSpec = {
                YoinMotion.fadeIn(role = YoinMotionRole.Standard) togetherWith
                    YoinMotion.fadeOut(role = YoinMotionRole.Standard)
            },
            label = "ledgerDemo",
            modifier = Modifier.fillMaxSize(),
        ) { page ->
            if (page) {
                MemoryLedgerScreen(
                    state = state,
                    onBackClick = { open = false },
                    onOpenAlbum = {},
                    selectedCellState = selected,
                    gridModifier = Modifier.demoTarget("ledgerGrid"),
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                Box(Modifier.fillMaxSize().statusBarsPadding().padding(horizontal = 16.dp, vertical = 24.dp)) {
                    MemoryLedgerSection(
                        state = state,
                        extractBackdropColors = true,
                        onOpen = { open = true },
                        modifier = Modifier.fillMaxWidth().demoTarget("ledgerHome"),
                    )
                }
            }
        }
    }
}

/** Where week cell [index] sits on the 13×2 wall, as fractions of the grid. */
private fun weekCellFraction(index: Int): Pair<Float, Float> {
    val column = index % WeekColumns
    val row = index / WeekColumns
    return (column + 0.5f) / WeekColumns to (row + 0.5f) / 2f
}

private const val WeekColumns = 13

/** A listener who writes now and then: a week a cell, a few albums, notes and scores. */
private fun demoLedgerState(covers: List<String>): MemoryLedgerState {
    val zone = ZoneId.systemDefault()
    val today = LocalDate.now(zone)
    val albums = DemoAlbums.mapIndexed { i, album ->
        MemoryLedgerAlbum("demo", "subsonic", "demo-$i", album.name, album.artist, coverKey = null)
    }
    // 16 days with something over the last four months: the week scale.
    val days = listOf(0, 1, 4, 9, 12, 20, 26, 33, 41, 47, 55, 62, 70, 84, 97, 110)
    val entries = days.flatMapIndexed { i, back ->
        val day = today.minusDays(back.toLong())
        val at = day.atTime(21, 0).atZone(zone).toInstant().toEpochMilli()
        val album = albums[i % albums.size]
        val title = DemoTrackTitles[i % DemoTrackTitles.size]
        buildList {
            add(
                MemoryLedgerEntry(
                    id = "demo-note-$i",
                    kind = if (i % 3 == 0) MemoryLedgerKind.AlbumNote else MemoryLedgerKind.SongNote,
                    at = at,
                    profileId = "demo",
                    album = album,
                    text = title,
                    trackTitle = if (i % 3 == 0) null else title,
                ),
            )
            if (i % 2 == 0) {
                add(
                    MemoryLedgerEntry(
                        id = "demo-score-$i",
                        kind = MemoryLedgerKind.TrackRating,
                        at = at - 60_000,
                        profileId = "demo",
                        album = albums[(i + 1) % albums.size],
                        trackTitle = DemoTrackTitles[(i + 3) % DemoTrackTitles.size],
                        score = 7f + (i % 4) * 0.5f,
                    ),
                )
            }
        }
    }.sortedByDescending { it.at }
    val firstDay: DayOfWeek = WeekFields.of(Locale.getDefault()).firstDayOfWeek
    val grid: MemoryGrid = buildMemoryGrid(entries, today, zone, firstDay, scale = MemoryGridScale.Week)
    return MemoryLedgerState(
        entries = entries,
        stats = memoryLedgerStats(entries),
        grid = grid,
        coverUrls = albums.mapIndexed { i, album -> album.key to covers[i] }.toMap(),
        profiles = listOf(LedgerProfile("demo", "Yoin", 0, ProviderKind.SUBSONIC, isActive = true)),
        activeProfileId = "demo",
        today = today,
        zone = zone,
    )
}
