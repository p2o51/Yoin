package com.gpo.yoin.ui.home

import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.gpo.yoin.shared.home.HomeFeedRow
import com.gpo.yoin.ui.experience.resolveYoinWindowInfo
import com.gpo.yoin.ui.home.HomeGoldenPin.bool
import com.gpo.yoin.ui.home.HomeGoldenPin.float
import com.gpo.yoin.ui.home.HomeGoldenPin.int
import com.gpo.yoin.ui.home.HomeGoldenPin.ints
import com.gpo.yoin.ui.home.HomeGoldenPin.name
import com.gpo.yoin.ui.home.HomeGoldenPin.num
import com.gpo.yoin.ui.home.HomeGoldenPin.obj
import com.gpo.yoin.ui.home.HomeGoldenPin.pairs
import com.gpo.yoin.ui.home.HomeGoldenPin.section
import com.gpo.yoin.ui.home.HomeGoldenPin.string
import com.gpo.yoin.ui.home.HomeGoldenPin.strings
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Android answers every home-grid golden vector (shared/src/commonTest/resources/golden/home-grid.json) through
 * the app's own paths: the two-column tier as the feed decides it from a [com.gpo.yoin.ui.experience.YoinWindowInfo]
 * (`homeTwoColumn(info)`, WindowSizeClass breakpoints and the tabletop posture, not :shared's plain function),
 * and the grid, row and shelf pager arithmetic under the app's names (Compose Dp / Density where the app uses them).
 */
class HomeGridPinTest {

    private val golden = HomeGoldenPin.load("home-grid.json")

    private fun vectors(name: String) = with(HomeGoldenPin) { golden.section(name) }

    @Test
    fun should_pickTheTwoColumnTierAsPinned_fromTheWindowInfo() {
        vectors("twoColumn").forEach { v ->
            val info = resolveYoinWindowInfo(v.float("widthDp").dp, v.float("heightDp").dp, v.bool("tabletop"), hingeBounds = null)
            assertEquals(v.name, v.bool("twoColumn"), homeTwoColumn(info))
        }
    }

    @Test
    fun should_measureTheGridAsPinned() {
        vectors("heights").forEach { v ->
            val units = v.int("units")
            close(v.name, v["heightDp"], num(homeGridHeightDp(units)))
            close(v.name, v["contentDp"], num(homeGridContentDp(units)))
            assertEquals(v.name, v.int("trackRows"), homeGridTrackRows(units))
            close(v.name, v["trackRowDp"], num(homeGridTrackRowDp(units)))
            // The app's Dp tokens draw the same box.
            close(v.name, v["heightDp"], num(Density(1f).homeGridHeightPx(units)))
        }
        vectors("columns").forEach { v ->
            val w = v.float("widthDp")
            assertEquals(v.name, v.int("coverColumns"), homeGridColumns(w))
            assertEquals(v.name, v.int("coverColumnsAt120"), homeGridColumns(w, homeGridCoverDp(120f)))
            assertEquals(v.name, v.int("trackColumns"), homeGridTrackColumns(w))
            assertEquals(v.name, v.int("activityColumns"), homeGridActivityColumns(w))
        }
        vectors("pack").forEach { v ->
            val cells = homeGridPack(v.ints("spans"), v.int("columns"), v.int("rows"))
            assertEquals(v.name, v.pairs("cells").map { it.map(String::toInt) }, cells.map { listOf(it.index, it.row, it.column, it.span) })
        }
        vectors("activitySpans").forEach { v -> assertEquals(v.name, v.ints("spans"), homeGridActivitySpans(v.int("count"), v.int("columns"))) }
        vectors("cellX").forEach { v ->
            val (x, w) = homeGridCellX(v.int("column"), v.int("span"), v.int("columns"), v.int("widthPx"), v.int("gapPx"))
            assertEquals(v.name, v.ints("cell"), listOf(x, w))
        }
        vectors("unitsNearest").forEach { v ->
            val density = Density(v.float("density"))
            assertEquals(v.name, v.int("units"), density.homeUnitsNearest(v.float("heightPx"), v.int("first")..v.int("last")))
        }
        vectors("reseats").forEach { v -> assertEquals(v.name, v.bool("reseats"), homeGridReseats(v.int("last"), v.int("now"))) }
    }

    @Test
    fun should_laySpansAndRowsAsPinned() {
        vectors("spanInfo").forEach { v ->
            val info = homeSpanInfoOf(section(v.string("id")!!), v.pairs("rows").map(::rowOf), HomeGoldenPin.sections(v.strings("enabledOrder")), v.float("fullPx"), v.float("gapPx"))
            val expected = v["info"]
            if (expected is JsonNull) {
                assertEquals(v.name, null, info)
            } else {
                val e = expected as JsonObject
                assertEquals(v.name, e.string("span"), info!!.span.name)
                close(v.name, e["currentPx"], num(info.currentPx))
                close(v.name, e["halfPx"], num(info.halfPx))
                close(v.name, e["fullPx"], num(info.fullPx))
                assertEquals(v.name, e.bool("hasNext"), info.hasNext)
            }
        }
        vectors("rowCellWidths").forEach { v ->
            val widths = homeRowCellWidths(rowOf(v.strings("row")), v.int("widthPx"), v.int("gapPx"), v.string("dragged")?.let(HomeSection::fromId), v.int("dragPx"))
            assertEquals(v.name, v.ints("widths"), widths)
        }
    }

    @Test
    fun should_pageShelvesAsPinned() {
        vectors("shelfGeometry").forEach { v ->
            val g = shelfPageGeometry(v.float("pagePx"), v.float("cardPx"), v.float("minGapPx"))
            close(v.name, v["geometry"], geometryJson(g))
        }
        vectors("shelfPages").forEach { v ->
            val g = geometryOf(v.obj("geometry"))
            assertEquals(v.name, v.int("pageCount"), shelfPageCount(v.int("cardCount"), g.perPage, v.int("leadPages")))
            close(v.name, v["fillerPx"], num(shelfFillerPx(v.int("cardCount"), g)))
            close(v.name, v["cardWidthDp"], num(homeShelfCardWidth(g.pagePx.dp, g.perPage).value))
        }
        vectors("shelfScroll").forEach { v ->
            val g = geometryOf(v.obj("geometry"))
            val scroll = shelfScrollPx(v.int("index"), v.int("offsetPx"), g, v.int("leadPages"))
            close(v.name, v["scrollPx"], num(scroll))
            close(v.name, v["offPagePx"], num(shelfOffPagePx(scroll, g.stridePx)))
        }
        vectors("shelfRelease").forEach { v ->
            val page = shelfReleasePage(v.float("scrollPx"), v.float("velocityPx"), v.float("stridePx"), v.int("startPage"), v.int("pageCount"), v.float("flingPx"))
            assertEquals(v.name, v.int("page"), page)
        }
        vectors("shelfTide").forEach { v -> close(v.name, v["reveal"], num(shelfTideReveal(v.float("offPagePx"), v.float("revealPx")))) }
    }

    private fun close(name: String, expected: kotlinx.serialization.json.JsonElement?, actual: kotlinx.serialization.json.JsonElement) {
        assertTrue("$name: expected $expected but was $actual", HomeGoldenPin.close(expected, actual))
    }

    private fun rowOf(ids: List<String>): HomeFeedRow =
        if (ids.size == 1) HomeFeedRow.Full(section(ids[0])) else HomeFeedRow.Halves(section(ids[0]), section(ids[1]))

    private fun geometryJson(g: ShelfPageGeometry) = JsonObject(
        linkedMapOf(
            "pagePx" to num(g.pagePx),
            "cardPx" to num(g.cardPx),
            "perPage" to JsonPrimitive(g.perPage),
            "gapPx" to num(g.gapPx),
            "stridePx" to num(g.stridePx),
        ),
    )

    private fun geometryOf(o: JsonObject) = ShelfPageGeometry(o.float("pagePx"), o.float("cardPx"), o.int("perPage"), o.float("gapPx"))
}
