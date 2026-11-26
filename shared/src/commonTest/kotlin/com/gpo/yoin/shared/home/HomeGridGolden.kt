package com.gpo.yoin.shared.home

import com.gpo.yoin.shared.home.HomeGoldenJson.bool
import com.gpo.yoin.shared.home.HomeGoldenJson.float
import com.gpo.yoin.shared.home.HomeGoldenJson.int
import com.gpo.yoin.shared.home.HomeGoldenJson.ints
import com.gpo.yoin.shared.home.HomeGoldenJson.num
import com.gpo.yoin.shared.home.HomeGoldenJson.obj
import com.gpo.yoin.shared.home.HomeGoldenJson.pairs
import com.gpo.yoin.shared.home.HomeGoldenJson.str
import com.gpo.yoin.shared.home.HomeGoldenJson.string
import com.gpo.yoin.shared.home.HomeGoldenJson.strings
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject

/**
 * The golden vectors of two-column Home's grid (shared/src/commonTest/resources/golden/home-grid.json): the
 * two-column tier, heights, columns, card packing, cell x, span info and row cell widths, and the
 * half-column shelf pager. Each vector is its inputs followed by what [YoinHomeLayout] answers ([outputs]);
 * written by `HomeGridGoldenTest` (androidHostTest), replayed by [replay] on the JVM and the iOS simulator
 * (floats within [HomeGoldenJson.TOLERANCE]). Android's app pins the same file (app HomeGridPinTest).
 */
internal object HomeGridGolden {

    const val FILE = "shared/src/commonTest/resources/golden/home-grid.json"

    val sections: List<String> = listOf(
        "twoColumn", "heights", "columns", "pack", "activitySpans", "cellX", "unitsNearest", "reseats", "spanInfo",
        "rowCellWidths", "shelfGeometry", "shelfPages", "shelfScroll", "shelfRelease", "shelfTide",
    )

    fun outputs(section: String, v: JsonObject): JsonObject = when (section) {
        "twoColumn" -> obj("twoColumn" to bool(YoinHomeLayout.twoColumn(v.float("widthDp"), v.float("heightDp"), v.bool("tabletop"))))
        "heights" -> {
            val units = v.int("units")
            obj(
                "heightDp" to num(YoinHomeLayout.gridHeightDp(units)),
                "contentDp" to num(YoinHomeLayout.gridContentDp(units)),
                "trackRows" to JsonPrimitive(YoinHomeLayout.gridTrackRows(units)),
                "trackRowDp" to num(YoinHomeLayout.gridTrackRowDp(units, HomeGridTokens.TrackRowGap)),
            )
        }
        "columns" -> {
            val w = v.float("widthDp")
            obj(
                "coverColumns" to JsonPrimitive(YoinHomeLayout.gridColumns(w, YoinHomeLayout.gridCoverDp(HomeGridTokens.Unit), HomeGridTokens.ColumnGap)),
                "coverColumnsAt120" to JsonPrimitive(YoinHomeLayout.gridColumns(w, YoinHomeLayout.gridCoverDp(120f), HomeGridTokens.ColumnGap)),
                "trackColumns" to JsonPrimitive(YoinHomeLayout.gridTrackColumns(w)),
                "activityColumns" to JsonPrimitive(YoinHomeLayout.gridActivityColumns(w, HomeGridTokens.ColumnGap)),
            )
        }
        "pack" -> obj(
            "cells" to JsonArray(
                YoinHomeLayout.gridPack(v.ints("spans"), v.int("columns"), v.int("rows")).map { ints(listOf(it.index, it.row, it.column, it.span)) },
            ),
        )
        "activitySpans" -> obj("spans" to ints(YoinHomeLayout.gridActivitySpans(v.int("count"), v.int("columns"))))
        "cellX" -> obj(
            "cell" to ints(YoinHomeLayout.gridCellX(v.int("column"), v.int("span"), v.int("columns"), v.int("widthPx"), v.int("gapPx"))),
        )
        "unitsNearest" -> obj(
            "units" to JsonPrimitive(YoinHomeLayout.unitsNearest(v.float("heightPx"), v.int("first"), v.int("last"), v.float("density"))),
        )
        "reseats" -> obj("reseats" to bool(YoinHomeLayout.gridReseats(v.int("last"), v.int("now"))))
        "spanInfo" -> obj(
            "info" to (
                YoinHomeLayout.spanInfo(v.string("id")!!, v.pairs("rows"), v.strings("enabledOrder"), v.float("fullPx"), v.float("gapPx"))
                    ?.let(::spanInfoJson) ?: JsonNull
                ),
        )
        "rowCellWidths" -> obj(
            "widths" to ints(
                YoinHomeLayout.rowCellWidths(v.strings("row"), v.int("widthPx"), v.int("gapPx"), v.string("dragged"), v.int("dragPx")),
            ),
        )
        "shelfGeometry" -> {
            val g = YoinHomeLayout.shelfPageGeometry(v.float("pagePx"), v.float("cardPx"), v.float("minGapPx"))
            obj("geometry" to geometryJson(g))
        }
        "shelfPages" -> {
            val g = geometryOf(v.obj("geometry"))
            obj(
                "pageCount" to JsonPrimitive(YoinHomeLayout.shelfPageCount(v.int("cardCount"), g.perPage, v.int("leadPages"))),
                "fillerPx" to num(YoinHomeLayout.shelfFillerPx(v.int("cardCount"), g)),
                "cardWidthDp" to num(YoinHomeLayout.shelfCardWidthDp(g.pagePx, g.perPage)),
            )
        }
        "shelfScroll" -> {
            val g = geometryOf(v.obj("geometry"))
            val scroll = YoinHomeLayout.shelfScrollPx(v.int("index"), v.int("offsetPx"), g, v.int("leadPages"))
            obj("scrollPx" to num(scroll), "offPagePx" to num(YoinHomeLayout.shelfOffPagePx(scroll, g.stridePx)))
        }
        "shelfRelease" -> obj(
            "page" to JsonPrimitive(
                YoinHomeLayout.shelfReleasePage(
                    v.float("scrollPx"),
                    v.float("velocityPx"),
                    v.float("stridePx"),
                    v.int("startPage"),
                    v.int("pageCount"),
                    v.float("flingPx"),
                ),
            ),
        )
        "shelfTide" -> obj("reveal" to num(YoinHomeLayout.shelfTideReveal(v.float("offPagePx"), v.float("revealPx"))))
        else -> error("unknown section $section")
    }

    fun render(): String {
        val header = listOf("about" to str(ABOUT), "constants" to constants())
        val body = sections.map { section ->
            section to HomeGridGoldenFixtures.inputs.getValue(section).map { input -> HomeGoldenJson.vector(input, outputs(section, input)) }
        }
        return HomeGoldenJson.render(header, body)
    }

    fun replay(text: String): List<String> {
        val root = Json.parseToJsonElement(text).jsonObject
        val mismatches = mutableListOf<String>()
        if (!HomeGoldenJson.close(root["constants"], constants())) mismatches += "constants: expected ${root["constants"]} but was ${constants()}"
        return mismatches + HomeGoldenJson.replay(root, sections, exact = false, outputs = ::outputs)
    }

    fun constants(): JsonObject = obj(
        "unit" to num(HomeGridTokens.Unit),
        "gap" to num(HomeGridTokens.Gap),
        "titleRow" to num(HomeGridTokens.TitleRow),
        "overdrag" to num(HomeGridTokens.Overdrag),
        "captionBand" to num(HomeGridTokens.CaptionBand),
        "columnGap" to num(HomeGridTokens.ColumnGap),
        "trackRowGap" to num(HomeGridTokens.TrackRowGap),
        "trackColumnMin" to num(HomeGridTokens.TrackColumnMin),
        "activityColumnMin" to num(HomeGridTokens.ActivityColumnMin),
        "twoColumnMinWidth" to num(HomeTwoColumnTokens.MinWidth),
        "twoColumnMinHeight" to num(HomeTwoColumnTokens.MinHeight),
        "twoColumnMinContent" to num(HomeTwoColumnTokens.MinContent),
        "pairGap" to num(HomeSpanRules.ColumnGap),
        "shelfMinGap" to num(HomeShelfPagerRules.MinGap),
        "shelfFlingVelocity" to num(HomeShelfPagerRules.FlingVelocity),
    )

    const val ABOUT = "Golden vectors for two-column Home's grid (com.gpo.yoin.shared.home, parity p4; Android PR #250 / " +
        "#292): the two-column tier (feed container ≥ 840 × 480dp, never tabletop), section heights (T + n·U + " +
        "(n − 1)·G), cover / track / activity columns, card packing and Activities' bento spans, whole-px cell x, " +
        "the nearest unit count, span info and row cell widths under a span drag, and the half-column shelf pager " +
        "(page geometry, page count, filler, scroll, off-page distance, release page, tide reveal). Generated from " +
        ":shared Kotlin through YoinHomeLayout by HomeGridGoldenTest (androidHostTest); do not edit by hand. " +
        "Lengths are dp unless a name says px. Floats are rounded to 1e-4; compare within 1e-4. pack cells are " +
        "[index, row, column, span]; cellX is [left, width]; rows are one id or [left, right]."

    private fun spanInfoJson(i: HomeSpanInfo) = obj(
        "span" to str(i.span.name),
        "currentPx" to num(i.currentPx),
        "halfPx" to num(i.halfPx),
        "fullPx" to num(i.fullPx),
        "hasNext" to bool(i.hasNext),
    )

    fun geometryJson(g: ShelfPageGeometry) = obj(
        "pagePx" to num(g.pagePx),
        "cardPx" to num(g.cardPx),
        "perPage" to JsonPrimitive(g.perPage),
        "gapPx" to num(g.gapPx),
        "stridePx" to num(g.stridePx),
    )

    fun geometryOf(o: JsonObject) = ShelfPageGeometry(
        pagePx = o.float("pagePx"),
        cardPx = o.float("cardPx"),
        perPage = o.int("perPage"),
        gapPx = o.float("gapPx"),
    )
}

/** The inputs of every home-grid golden vector ([HomeGridGolden]), from HomeGridUnitsTest, HomeShelfPagerTest and the tier's boundaries. */
internal object HomeGridGoldenFixtures {

    private fun v(name: String, vararg entries: Pair<String, JsonElement>): JsonObject = JsonObject(linkedMapOf("name" to str(name), *entries))

    private fun n(value: Float) = num(value)

    private fun i(value: Int) = JsonPrimitive(value)

    private val twoColumn: List<JsonObject> = listOf(
        Triple("840 × 480: the corner", 840f to 480f, false),
        Triple("839 × 480: one dp short", 839f to 480f, false),
        Triple("840 × 479: one dp short", 840f to 479f, false),
        Triple("839.99 × 480", 839.99f to 480f, false),
        Triple("1280 × 800 tablet landscape", 1280f to 800f, false),
        Triple("800 × 1280 tablet portrait", 800f to 1280f, false),
        Triple("1280 × 800 tabletop", 1280f to 800f, true),
        Triple("915 × 412 phone landscape", 915f to 412f, false),
        Triple("412 × 915 phone portrait", 412f to 915f, false),
        Triple("673 × 841 foldable inner portrait", 673f to 841f, false),
        Triple("841 × 673 foldable inner landscape", 841f to 673f, false),
        Triple("841 × 673 foldable half open (tabletop)", 841f to 673f, true),
        Triple("1376 × 1032 iPad Pro 13 landscape", 1376f to 1032f, false),
        Triple("1032 × 1376 iPad Pro 13 portrait", 1032f to 1376f, false),
        Triple("1210 × 834 iPad Pro 11 landscape", 1210f to 834f, false),
        Triple("834 × 1210 iPad Pro 11 portrait", 834f to 1210f, false),
        Triple("1133 × 744 iPad mini landscape", 1133f to 744f, false),
        Triple("744 × 1133 iPad mini portrait", 744f to 1133f, false),
        Triple("932 × 430 iPhone Pro Max landscape", 932f to 430f, false),
        Triple("1280 × 720 HarmonyOS tablet landscape (vp)", 1280f to 720f, false),
        Triple("1100 × 760 tri-fold fully open (vp)", 1100f to 760f, false),
        Triple("760 × 780 tri-fold two panels (vp)", 760f to 780f, false),
        Triple("1440 × 900 desktop window", 1440f to 900f, false),
        Triple("1440 × 470 short desktop window", 1440f to 470f, false),
    ).map { (name, size, tabletop) -> v(name, "widthDp" to n(size.first), "heightDp" to n(size.second), "tabletop" to bool(tabletop)) }

    private val heights = (-1..5).map { v("$it units", "units" to i(it)) }

    private val columns = listOf(0f, 105f, 106f, 223f, 224f, 300f, 330f, 380f, 400f, 560f, 600f, 690f, 776f, 838f, 900f, 1200f, 1216f)
        .map { v("$it dp", "widthDp" to n(it)) }

    private val pack: List<JsonObject> = listOf(
        Triple(listOf(2, 1, 1, 2, 1), 4, 2),
        Triple(listOf(2, 1, 2, 1, 1, 1), 3, 2),
        Triple(listOf(1, 1, 1, 1, 1, 1, 1), 3, 2),
        Triple(listOf(5, 1), 3, 2),
        Triple(listOf(0, -1, 1), 2, 1),
        Triple(listOf(2, 2, 2), 1, 3),
        Triple(listOf(1, 1), 0, 1),
        Triple(emptyList(), 4, 2),
        Triple(listOf(1, 1, 1), 4, 0),
    ).map { (spans, columns, rows) -> v("$spans into $rows × $columns", "spans" to ints(spans), "columns" to i(columns), "rows" to i(rows)) }

    private val activitySpans = listOf(0 to 4, 1 to 4, 5 to 4, 7 to 3, 6 to 2, 4 to 1, 9 to 5, 3 to 0)
        .map { (count, columns) -> v("$count cards, $columns columns", "count" to i(count), "columns" to i(columns)) }

    private val cellX: List<JsonObject> = listOf(
        listOf(0, 1, 3, 1000, 12), listOf(1, 1, 3, 1000, 12), listOf(2, 1, 3, 1000, 12), listOf(0, 2, 3, 1000, 12),
        listOf(1, 2, 3, 1000, 12), listOf(0, 1, 4, 777, 13), listOf(3, 1, 4, 777, 13), listOf(1, 2, 4, 777, 13),
        listOf(0, 1, 1, 500, 12), listOf(0, 1, 0, 500, 12), listOf(2, 3, 4, 100, 40),
    ).map { (column, span, columns, width, gap) ->
        v("column $column span $span of $columns across $width", "column" to i(column), "span" to i(span), "columns" to i(columns), "widthPx" to i(width), "gapPx" to i(gap))
    }

    private val unitsNearest: List<JsonObject> = listOf(
        listOf(190f, 1f, 1f), listOf(270f, 1f, 1f), listOf(271f, 1f, 1f), listOf(354f, 1f, 1f), listOf(355f, 1f, 1f),
        listOf(1000f, 1f, 1f), listOf(0f, 1f, 1f), listOf(500f, 2f, 1f), listOf(1050f, 2.625f, 1f), listOf(1052f, 2.625f, 1f),
    ).flatMap { (h, density, _) ->
        listOf(1 to 4, 1 to 2, 2 to 3).map { (first, last) ->
            v("$h px at ×$density in $first..$last", "heightPx" to n(h), "first" to i(first), "last" to i(last), "density" to n(density))
        }
    }

    private val reseats = listOf(0 to 0, 0 to 3, 3 to 3, 3 to 4, 1080 to 1200).map { (last, now) -> v("$last → $now", "last" to i(last), "now" to i(now)) }

    private val wideOrder = HomeLayout.WideDefault.enabledSections.map { it.id }
    private val wideRows = YoinHomeLayout.feedRows(wideOrder, YoinHomeLayout.pairs(HomeLayout.WideDefault.toState()), true)

    private val spanInfo: List<JsonObject> = wideOrder.map { id ->
        v("wide default: $id", "id" to str(id), "rows" to pairs(wideRows), "enabledOrder" to strings(wideOrder), "fullPx" to n(2176f), "gapPx" to n(56f))
    } + listOf(
        v("last full row has no next", "id" to str("other_profiles"), "rows" to pairs(wideRows), "enabledOrder" to strings(wideOrder), "fullPx" to n(1000f), "gapPx" to n(20f)),
        v("next enabled not displayed after it", "id" to str("memory_grid"), "rows" to pairs(listOf(listOf("memory_grid"), listOf("rediscover"))), "enabledOrder" to strings(listOf("memory_grid", "made_for_you", "rediscover")), "fullPx" to n(1000f), "gapPx" to n(20f)),
        v("not laid out", "id" to str("rediscover"), "rows" to pairs(listOf(listOf("activities"))), "enabledOrder" to strings(wideOrder), "fullPx" to n(1000f), "gapPx" to n(20f)),
        v("no width", "id" to str("activities"), "rows" to pairs(wideRows), "enabledOrder" to strings(wideOrder), "fullPx" to n(0f), "gapPx" to n(20f)),
    )

    private val rowCellWidths: List<JsonObject> = listOf(
        listOf("activities") to (null to 0), listOf("activities") to ("activities" to 600), listOf("activities") to ("activities" to 5000),
        listOf("activities", "jump_back_in") to (null to 0), listOf("activities", "jump_back_in") to ("activities" to 800),
        listOf("activities", "jump_back_in") to ("jump_back_in" to 800), listOf("activities", "jump_back_in") to ("activities" to 1900),
        listOf("activities", "jump_back_in") to ("jump_back_in" to 0), listOf("activities", "jump_back_in") to ("rediscover" to 300),
    ).map { (row, drag) ->
        v("${row.joinToString(" | ")} dragging ${drag.first}", "row" to strings(row), "widthPx" to i(2001), "gapPx" to i(56), "dragged" to str(drag.first), "dragPx" to i(drag.second))
    }

    private val shelfGeometry: List<JsonObject> = listOf(
        listOf(800f, 265f, 37f), listOf(800f, 400f, 37f), listOf(800f, 801f, 37f), listOf(1000f, 265f, 37f), listOf(0f, 100f, 37f),
        listOf(530f, 265f, 0f), listOf(531f, 265f, 0f), listOf(1200f, 106f, 14f), listOf(300f, 0f, 14f),
    ).map { (page, card, gap) -> v("$card cards across $page, gap ≥ $gap", "pagePx" to n(page), "cardPx" to n(card), "minGapPx" to n(gap)) }

    private val geometries = listOf(
        shelfPageGeometry(800f, 265f, 37f),
        shelfPageGeometry(800f, 801f, 37f),
        shelfPageGeometry(1200f, 106f, 14f),
    )

    private val shelfPages: List<JsonObject> = geometries.flatMap { g ->
        listOf(0 to 0, 1 to 0, 3 to 0, 4 to 0, 7 to 1, 10 to 2).map { (count, lead) ->
            v("$count cards, $lead lead, ${g.perPage} a page", "geometry" to HomeGridGolden.geometryJson(g), "cardCount" to i(count), "leadPages" to i(lead))
        }
    }

    private val shelfScroll: List<JsonObject> = geometries.flatMap { g ->
        listOf(Triple(0, 0, 0), Triple(1, 10, 0), Triple(3, 0, 0), Triple(0, 50, 1), Triple(1, 0, 1), Triple(4, 33, 1), Triple(2, 0, 2)).map { (index, offset, lead) ->
            v("item $index +$offset, $lead lead, ${g.perPage} a page", "geometry" to HomeGridGolden.geometryJson(g), "index" to i(index), "offsetPx" to i(offset), "leadPages" to i(lead))
        }
    }

    private val shelfRelease: List<JsonObject> = listOf(
        listOf(0f, 0f, 900f, 0f, 4f), listOf(400f, 0f, 900f, 0f, 4f), listOf(460f, 0f, 900f, 0f, 4f), listOf(100f, 1500f, 900f, 0f, 4f),
        listOf(100f, -1500f, 900f, 0f, 4f), listOf(1700f, 1500f, 900f, 1f, 4f), listOf(2000f, 0f, 900f, 1f, 4f), listOf(3000f, 3000f, 900f, 3f, 4f),
        listOf(-200f, -3000f, 900f, 0f, 4f), listOf(500f, 0f, 900f, 0f, 1f), listOf(500f, 0f, 0f, 0f, 3f), listOf(899f, 399f, 900f, 0f, 3f),
    ).map { (scroll, velocity, stride, start, count) ->
        v(
            "scroll $scroll at $velocity/s from page ${start.toInt()} of ${count.toInt()}",
            "scrollPx" to n(scroll),
            "velocityPx" to n(velocity),
            "stridePx" to n(stride),
            "startPage" to i(start.toInt()),
            "pageCount" to i(count.toInt()),
            "flingPx" to n(1050f),
        )
    }

    private val shelfTide = listOf(0f to 40f, 10f to 40f, 40f to 40f, 90f to 40f, 5f to 0f, -3f to 40f)
        .map { (off, reveal) -> v("$off of $reveal", "offPagePx" to n(off), "revealPx" to n(reveal)) }

    val inputs: Map<String, List<JsonObject>> = mapOf(
        "twoColumn" to twoColumn,
        "heights" to heights,
        "columns" to columns,
        "pack" to pack,
        "activitySpans" to activitySpans,
        "cellX" to cellX,
        "unitsNearest" to unitsNearest,
        "reseats" to reseats,
        "spanInfo" to spanInfo,
        "rowCellWidths" to rowCellWidths,
        "shelfGeometry" to shelfGeometry,
        "shelfPages" to shelfPages,
        "shelfScroll" to shelfScroll,
        "shelfRelease" to shelfRelease,
        "shelfTide" to shelfTide,
    )
}
