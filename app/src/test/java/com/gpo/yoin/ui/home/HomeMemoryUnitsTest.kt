package com.gpo.yoin.ui.home

import com.gpo.yoin.data.memory.MemoryGridScale
import com.gpo.yoin.shared.memory.MemoryLedgerAlbum
import com.gpo.yoin.shared.memory.MemoryLedgerEntry
import com.gpo.yoin.shared.memory.MemoryLedgerKind
import com.gpo.yoin.data.memory.buildMemoryGrid
import java.time.LocalDate
import java.time.ZoneOffset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** All Memories on the two-column grid: the wall fitted to its box, the layout per units, the rated albums. */
class HomeMemoryUnitsTest {

    @Test
    fun should_fitDayCellsToTheUnitHeight_and_weeksToTheWidth() {
        // 7 rows and 6 gaps of 3 in 150: cells of 132 / 7.
        val half = memoryWallFit(MemoryGridScale.Day, widthDp = 598f, heightDp = 150f)
        assertEquals(132f / 7f, half.cellDp, .01f)
        assertEquals(27, half.columns)
        assertEquals(27, half.periods)
        assertEquals(150f, half.heightDp, .01f)
        assertTrue(half.widthDp <= 598f)
        // A wider box looks further back at the same cell size.
        val wider = memoryWallFit(MemoryGridScale.Day, widthDp = 900f, heightDp = 150f)
        assertEquals(half.cellDp, wider.cellDp, .01f)
        assertTrue(wider.periods > half.periods)
    }

    @Test
    fun should_fitWeeksInTwoRows_and_monthsInOneWithNames() {
        val week = memoryWallFit(MemoryGridScale.Week, widthDp = 598f, heightDp = 150f)
        assertEquals(72.5f, week.cellDp, .01f)
        assertEquals(week.columns * 2, week.periods)
        val month = memoryWallFit(MemoryGridScale.Month, widthDp = 598f, heightDp = 150f)
        assertEquals(150f - MemoryWallMonthLabelDp, month.cellDp, .01f)
        // Four months or fewer space their quads 12dp apart.
        assertEquals(4, month.columns)
        assertEquals(4 * month.cellDp + 3 * 12f, month.widthDp, .01f)
    }

    @Test
    fun should_capCells_besideTheAlbumsInAFullRow() {
        val fit = memoryWallFit(MemoryGridScale.Week, widthDp = 460f, heightDp = 314f, cellCapDp = MemoryWallSideCellCapDp)
        assertEquals(MemoryWallSideCellCapDp, fit.cellDp, .01f)
        assertEquals(2 * 22f + 5f, fit.heightDp, .01f)
    }

    @Test
    fun should_putTheWallFirst_and_albumRowsUnder_inAHalf() {
        assertEquals(MemoryUnitLayout(side = false, wallUnits = 1, albumRows = 0), memoryUnitLayout(half = true, units = 1))
        assertEquals(MemoryUnitLayout(side = false, wallUnits = 1, albumRows = 2), memoryUnitLayout(half = true, units = 3))
        assertEquals(MemoryUnitLayout(side = true, wallUnits = 2, albumRows = 2), memoryUnitLayout(half = false, units = 2))
    }

    @Test
    fun should_listRatedAlbumsNewestFirst_onceEach_inTheAccount() {
        val a = album("a")
        val b = album("b")
        val entries = listOf(
            entry(MemoryLedgerKind.AlbumRating, a, at = 1, score = 7f),
            entry(MemoryLedgerKind.AlbumRating, b, at = 3, score = 9f),
            entry(MemoryLedgerKind.AlbumRating, a, at = 5, score = 8.5f),
            entry(MemoryLedgerKind.AlbumNote, b, at = 6, score = null),
            entry(MemoryLedgerKind.AlbumRating, album("c", profile = "other"), at = 7, score = 6f, profile = "other"),
        )
        assertEquals(listOf(a to 8.5f, b to 9f), memoryRatedAlbums(entries, profileId = "p"))
    }

    @Test
    fun should_buildAsManyPeriodsAsAsked() {
        val today = LocalDate.of(2026, 11, 16)
        val day = buildMemoryGrid(emptyList(), today, ZoneOffset.UTC, scale = MemoryGridScale.Day, periods = 28)
        assertEquals(28 * 7, day.cells.size)
        val week = buildMemoryGrid(emptyList(), today, ZoneOffset.UTC, scale = MemoryGridScale.Week, periods = 14)
        assertEquals(14, week.cells.size)
        val month = buildMemoryGrid(emptyList(), today, ZoneOffset.UTC, scale = MemoryGridScale.Month, periods = 4)
        assertEquals(4, month.cells.size)
        // Without a count, the scale's own.
        assertEquals(26, buildMemoryGrid(emptyList(), today, ZoneOffset.UTC, scale = MemoryGridScale.Week).cells.size)
    }

    private fun album(id: String, profile: String = "p") =
        MemoryLedgerAlbum(profileId = profile, provider = "subsonic", albumId = id, name = id, artist = "x", coverKey = null)

    private fun entry(kind: MemoryLedgerKind, album: MemoryLedgerAlbum, at: Long, score: Float?, profile: String = "p") =
        MemoryLedgerEntry(id = "$kind-$at", kind = kind, at = at, profileId = profile, album = album, score = score)
}
