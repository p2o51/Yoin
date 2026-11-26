package com.gpo.yoin.shared.home

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/*
 * The facade over Home's layout and edit core (docs/design.md §主页 › 就地编辑; Android PR #250 / #292,
 * parity p4): the section catalog, the stored document, the layout with its two-column pairing memory and
 * every edit, the persist rule, the edit session's rules, the two-column tier and grid, the span handle and
 * the pinch. Android, iOS (Swift, through YoinShared) and the HarmonyOS Dart port answer exactly as
 * shared/src/commonTest/resources/golden/home-layout.json and home-grid.json say.
 *
 * Swift calls it as `YoinHomeLayout.shared.…`. Plain types only: data classes of strings, booleans, ints,
 * lists and nullable JSON strings; no default arguments. A layout is a [HomeLayoutState] value; every edit
 * is `(state, args) → HomeLayoutResult`, whose `changed` is false (and `state` the one passed in) when the
 * edit changes nothing — Android's "same object" contract, which edit mode relies on to skip an Undo step.
 * Section ids this build doesn't know are ignored by the edits (they leave the layout as it is). The
 * platforms keep storage, sync, the UI, the strings and the gesture plumbing.
 */
object YoinHomeLayout {

    // ── catalog ──────────────────────────────────────────────────────

    /** Every section id, in the catalog's (the phone default's) order. */
    fun sectionIds(): List<String> = HomeSection.entries.map { it.id }

    /** Ids of sections removed from the catalog: dropped on read, never retained. */
    fun retiredIds(): List<String> = HomeSection.RetiredIds.toList()

    fun defaultEnabled(id: String): Boolean = HomeSection.fromId(id)?.defaultEnabled == true

    fun appendEnabled(id: String): Boolean = HomeSection.fromId(id)?.appendEnabled == true

    fun supportsRows(id: String): Boolean = HomeSection.fromId(id)?.supportsRows == true

    fun takesPresets(id: String): Boolean = HomeSection.fromId(id)?.takesPresets == true

    fun unitsOwnKey(id: String): Boolean = HomeSection.fromId(id)?.unitsOwnKey == true

    /** The unit counts [id] takes in two columns, as `[first, last]`; empty for an unknown id. */
    fun unitRange(id: String): List<Int> = HomeSection.fromId(id)?.unitRange?.let { listOf(it.first, it.last) }.orEmpty()

    /** The count [id] takes with nothing stored; 1 for an unknown id. */
    fun defaultUnits(id: String): Int = HomeSection.fromId(id)?.defaultUnits ?: 1

    /** The row preset key storing [units] (`s` / `m` / `l` / `xl`). */
    fun unitsPreset(units: Int): String = homeUnitsPreset(units).key

    /** [left] and [right] can be the two halves of a row (their unit ranges meet). */
    fun canPair(left: String, right: String): Boolean {
        val l = HomeSection.fromId(left) ?: return false
        val r = HomeSection.fromId(right) ?: return false
        return homeCanPair(l, r)
    }

    /** The range a row's handle drags over (`[first, last]`): [left]'s own, or the overlap with [right]. */
    fun rowUnitRange(left: String, right: String?): List<Int> {
        val l = HomeSection.fromId(left) ?: return emptyList()
        val range = homeRowUnitRange(l, right?.let(HomeSection::fromId))
        return listOf(range.first, range.last)
    }

    /** Edit mode keeps this many Undo steps. */
    val undoMax: Int = HOME_EDIT_UNDO_MAX

    // ── layouts and the stored document ──────────────────────────────

    /** The phone default ([HomeLayout.Default]): what a never-customized profile shows in one column. */
    fun defaultState(): HomeLayoutState = HomeLayout.Default.toState()

    /** The large-screen default ([HomeLayout.WideDefault]). */
    fun wideDefaultState(): HomeLayoutState = HomeLayout.WideDefault.toState()

    /**
     * The layout a stored document reads as ([HomeLayoutCodec.decode] then [HomeLayout.fromDocument]): null
     * or an unreadable text is "never customized", the phone default.
     */
    fun decode(documentJson: String?): HomeLayoutState =
        HomeLayout.fromDocument(documentJson?.let(HomeLayoutCodec::decode)).toState()

    /** [state]'s stored document text ([HomeLayout.toDocument], every kept key written back). */
    fun encode(state: HomeLayoutState): String = HomeLayoutCodec.encode(state.toLayout().toDocument())

    /** How persisting [state] treats the stored row ([homePersistAction]): clear it, or write [HomeLayoutPersist.documentJson]. */
    fun persist(state: HomeLayoutState): HomeLayoutPersist = when (val action = homePersistAction(state.toLayout())) {
        HomePersistAction.Clear -> HomeLayoutPersist(clear = true, documentJson = null)
        is HomePersistAction.Write -> HomeLayoutPersist(clear = false, documentJson = HomeLayoutCodec.encode(action.document))
    }

    // ── reading a layout ─────────────────────────────────────────────

    fun enabledIds(state: HomeLayoutState): List<String> = state.sections.filter { it.enabled }.map { it.id }

    /** The hidden sections in layout order (the tray order). */
    fun hiddenIds(state: HomeLayoutState): List<String> = state.sections.filterNot { it.enabled }.map { it.id }

    fun isDefault(state: HomeLayoutState): Boolean = state.toLayout().isDefault

    fun isWideDefault(state: HomeLayoutState): Boolean = state.toLayout().isWideDefault

    fun isAnyDefault(state: HomeLayoutState): Boolean = state.toLayout().isAnyDefault

    /** The default Reset goes to at this width: [HomeLayout.WideDefault] in two columns. */
    fun isDefaultFor(state: HomeLayoutState, twoColumn: Boolean): Boolean = state.toLayout().isDefaultFor(twoColumn)

    /** Same order, visibility, rows, settings and the same pairing memory ([HomeLayout.sameSectionsAs]). */
    fun sameSections(a: HomeLayoutState, b: HomeLayoutState): Boolean = a.toLayout().sameSectionsAs(b.toLayout())

    /** The pairs the enabled order shows now, left → right, as `[left, right]`. */
    fun pairs(state: HomeLayoutState): List<List<String>> = state.toLayout().pairs.toIdPairs()

    /** [id]'s partner in its pair, either side; null when it is full width. */
    fun partnerOf(state: HomeLayoutState, id: String): String? =
        HomeSection.fromId(id)?.let { state.toLayout().partnerOf(it)?.id }

    /** [id]'s own unit count in two columns ([HomeLayout.unitsOf]). */
    fun unitsOf(state: HomeLayoutState, id: String): Int =
        HomeSection.fromId(id)?.let { state.toLayout().unitsOf(it) } ?: 1

    /** The unit count a feed row (one id, or a pair `[left, right]`) shows in two columns. */
    fun rowUnits(state: HomeLayoutState, row: List<String>): Int =
        rowOf(row)?.let { state.toLayout().unitsOf(it) } ?: 1

    /** [id]'s row preset key (`l` when it is not in the layout). */
    fun rowsOf(state: HomeLayoutState, id: String): String =
        HomeSection.fromId(id)?.let { state.toLayout().rowsOf(it).key } ?: HomeRowPreset.Default.key

    // ── edits: (state, args) → result ────────────────────────────────

    fun withEnabled(state: HomeLayoutState, id: String, enabled: Boolean): HomeLayoutResult =
        edit(state, id) { layout, section -> layout.withEnabled(section, enabled) }

    /** The enabled order refilled with [order] ([HomeLayout.withEnabledOrder]); [unseen]: enabled ids this device never shows. */
    fun withEnabledOrder(state: HomeLayoutState, order: List<String>, unseen: List<String>): HomeLayoutResult =
        edit(state) { it.withEnabledOrder(sectionsOf(order) ?: return@edit it, sectionsOf(unseen)?.toSet() ?: return@edit it) }

    fun moved(state: HomeLayoutState, id: String, toEnabledIndex: Int): HomeLayoutResult =
        edit(state, id) { layout, section -> layout.moved(section, toEnabledIndex) }

    fun withHalf(state: HomeLayoutState, id: String): HomeLayoutResult = edit(state, id) { layout, section -> layout.withHalf(section) }

    fun withFull(state: HomeLayoutState, id: String): HomeLayoutResult = edit(state, id) { layout, section -> layout.withFull(section) }

    fun withBesidePrevious(state: HomeLayoutState, id: String): HomeLayoutResult =
        edit(state, id) { layout, section -> layout.withBesidePrevious(section) }

    /** A two-column drag's drop: [order] with exactly [pairs] (`[left, right]`, adjacent in [order]) as halves. */
    fun withArrangement(
        state: HomeLayoutState,
        order: List<String>,
        pairs: List<List<String>>,
        unseen: List<String>,
    ): HomeLayoutResult = edit(state) {
        it.withArrangement(
            sectionsOf(order) ?: return@edit it,
            pairsOf(pairs) ?: return@edit it,
            sectionsOf(unseen)?.toSet() ?: return@edit it,
        )
    }

    /** [id]'s row (and its partner) at [units] in two columns ([HomeLayout.withUnits], the pairs shown now). */
    fun withUnits(state: HomeLayoutState, id: String, units: Int): HomeLayoutResult =
        edit(state, id) { layout, section -> layout.withUnits(section, units) }

    /** [id] at the row preset [rowsKey] (`s` / `m` / `l` / `xl`); an unknown key changes nothing. */
    fun withRows(state: HomeLayoutState, id: String, rowsKey: String): HomeLayoutResult =
        edit(state, id) { layout, section -> HomeRowPreset.fromKey(rowsKey)?.let { layout.withRows(section, it) } ?: layout }

    fun reset(state: HomeLayoutState, twoColumn: Boolean): HomeLayoutResult = edit(state) { it.reset(twoColumn) }

    fun forTwoColumn(state: HomeLayoutState, twoColumn: Boolean): HomeLayoutResult = edit(state) { it.forTwoColumn(twoColumn) }

    fun shownAt(state: HomeLayoutState, twoColumn: Boolean): HomeLayoutResult = edit(state) { it.shownAt(twoColumn) }

    fun rememberingShownPairs(state: HomeLayoutState): HomeLayoutResult = edit(state) { it.rememberingShownPairs() }

    fun withRememberedPairs(state: HomeLayoutState): HomeLayoutResult = edit(state) { it.withRememberedPairs() }

    fun normalizedSpans(state: HomeLayoutState): HomeLayoutResult = edit(state) { it.normalizedSpans() }

    // ── the edit session ─────────────────────────────────────────────

    /** The draft after a width change in edit mode ([homeDraftAtWidth]). */
    fun draftAtWidth(draft: HomeLayoutState, edited: Boolean, base: HomeLayoutState, twoColumn: Boolean): HomeLayoutState =
        homeDraftAtWidth(draft.toLayout(), edited, base.toLayout(), twoColumn).toState()

    /**
     * One edit's step ([homeEditStep]): unchanged stays unchanged (no Undo step); a change in [twoColumn]
     * also makes the memory what two columns show.
     */
    fun editStep(result: HomeLayoutResult, twoColumn: Boolean): HomeLayoutResult {
        if (!result.changed || !twoColumn) return result
        return HomeLayoutResult(result.state.toLayout().rememberingShownPairs().toState(), changed = true)
    }

    /** The stored layout stands for the one last shown in edit mode ([homeLayoutEchoes]). */
    fun echoes(stored: HomeLayoutState, shown: HomeLayoutState): Boolean = homeLayoutEchoes(stored.toLayout(), shown.toLayout())

    // ── pairs and rows ───────────────────────────────────────────────

    /** The remembered pairs ([memory]) that show at [order], left → right as the order has them ([homeWidePairsAt]). */
    fun widePairsAt(order: List<String>, memory: List<List<String>>): List<List<String>> =
        homeWidePairsAt(sectionsOf(order).orEmpty(), pairsOf(memory).orEmpty()).toIdPairs()

    /** [a] and [b] hold the same pairs, sides aside. */
    fun samePairs(a: List<List<String>>, b: List<List<String>>): Boolean = homeSamePairs(pairsOf(a).orEmpty(), pairsOf(b).orEmpty())

    /** The pairs of an enabled [order] whose [halves] flags are given index by index ([homeSpanPairs]). */
    fun spanPairs(order: List<String>, halves: List<Boolean>): List<List<String>> =
        homeSpanPairs(order.mapIndexedNotNull { i, id -> HomeSection.fromId(id)?.let { it to (halves.getOrNull(i) == true) } }).toIdPairs()

    /** The feed's rows for [displayed]: one id, or `[left, right]` for a pair side by side ([homeFeedRows]). */
    fun feedRows(displayed: List<String>, pairs: List<List<String>>, twoColumn: Boolean): List<List<String>> =
        homeFeedRows(sectionsOf(displayed).orEmpty(), pairsOf(pairs).orEmpty(), twoColumn).map(::rowIds)

    // ── the two-column tier and grid (dp unless px) ──────────────────

    /** The feed lays out in two columns: container ≥ 840 × 480dp and not tabletop ([homeTwoColumn]). */
    fun twoColumn(containerWidthDp: Float, containerHeightDp: Float, tabletop: Boolean): Boolean =
        homeTwoColumn(containerWidthDp, containerHeightDp, tabletop)

    fun gridHeightDp(units: Int): Float = homeGridHeightDp(units)

    fun gridContentDp(units: Int): Float = homeGridContentDp(units)

    fun gridCoverDp(unitDp: Float): Float = homeGridCoverDp(unitDp)

    fun gridColumns(widthDp: Float, coverDp: Float, gapDp: Float): Int = homeGridColumns(widthDp, coverDp, gapDp)

    fun gridTrackRows(units: Int): Int = homeGridTrackRows(units)

    fun gridTrackColumns(widthDp: Float): Int = homeGridTrackColumns(widthDp)

    fun gridTrackRowDp(units: Int, gapDp: Float): Float = homeGridTrackRowDp(units, gapDp)

    /** The count in `[first, last]` whose height (× [density] px) is nearest [heightPx]. */
    fun unitsNearest(heightPx: Float, first: Int, last: Int, density: Float): Int = homeUnitsNearest(heightPx, first..last, density)

    fun gridPack(spans: List<Int>, columns: Int, rows: Int): List<HomeGridCell> = homeGridPack(spans, columns, rows)

    fun gridActivitySpans(count: Int, columns: Int): List<Int> = homeGridActivitySpans(count, columns)

    fun gridActivityColumns(widthDp: Float, gapDp: Float): Int = homeGridActivityColumns(widthDp, gapDp)

    /** A grid cell's `[left, width]` in whole px. */
    fun gridCellX(column: Int, span: Int, columns: Int, widthPx: Int, gapPx: Int): List<Int> =
        homeGridCellX(column, span, columns, widthPx, gapPx).let { listOf(it.first, it.second) }

    fun gridReseats(last: Int, now: Int): Boolean = homeGridReseats(last, now)

    /** Where [id] sits in [rows] (each one id or a pair) for a span drag; null when it isn't laid out. */
    fun spanInfo(id: String, rows: List<List<String>>, enabledOrder: List<String>, fullPx: Float, gapPx: Float): HomeSpanInfo? {
        val section = HomeSection.fromId(id) ?: return null
        return homeSpanInfoOf(section, rows.mapNotNull(::rowOf), sectionsOf(enabledOrder).orEmpty(), fullPx, gapPx)
    }

    /** Widths (px) of a row's cells, under a span drag of [dragged] at [dragPx] when it is set. */
    fun rowCellWidths(row: List<String>, widthPx: Int, gapPx: Int, dragged: String?, dragPx: Int): List<Int> {
        val feedRow = rowOf(row) ?: return emptyList()
        return homeRowCellWidths(feedRow, widthPx, gapPx, dragged?.let(HomeSection::fromId), dragPx)
    }

    fun shelfPageGeometry(pagePx: Float, cardPx: Float, minGapPx: Float): ShelfPageGeometry =
        com.gpo.yoin.shared.home.shelfPageGeometry(pagePx, cardPx, minGapPx)

    fun shelfCardWidthDp(pageWidthDp: Float, perPage: Int): Float = homeShelfCardWidthDp(pageWidthDp, perPage)

    fun shelfPageCount(cardCount: Int, perPage: Int, leadPages: Int): Int =
        com.gpo.yoin.shared.home.shelfPageCount(cardCount, perPage, leadPages)

    fun shelfFillerPx(cardCount: Int, geometry: ShelfPageGeometry): Float = com.gpo.yoin.shared.home.shelfFillerPx(cardCount, geometry)

    fun shelfScrollPx(index: Int, offsetPx: Int, geometry: ShelfPageGeometry, leadPages: Int): Float =
        com.gpo.yoin.shared.home.shelfScrollPx(index, offsetPx, geometry, leadPages)

    fun shelfOffPagePx(scrollPx: Float, stridePx: Float): Float = com.gpo.yoin.shared.home.shelfOffPagePx(scrollPx, stridePx)

    fun shelfReleasePage(scrollPx: Float, velocityPx: Float, stridePx: Float, startPage: Int, pageCount: Int, flingPx: Float): Int =
        com.gpo.yoin.shared.home.shelfReleasePage(scrollPx, velocityPx, stridePx, startPage, pageCount, flingPx)

    fun shelfTideReveal(offPagePx: Float, revealPx: Float): Float = com.gpo.yoin.shared.home.shelfTideReveal(offPagePx, revealPx)

    // ── the span handle and the pinch ────────────────────────────────

    fun handleAxis(dx: Float, dy: Float, slopPx: Float, horizontal: Boolean): HomeHandleAxis? = homeHandleAxis(dx, dy, slopPx, horizontal)

    fun spanDragWidth(from: HomeSpan, startPx: Float, dx: Float, halfPx: Float, fullPx: Float): Float =
        homeSpanDragWidth(from, startPx, dx, halfPx, fullPx)

    fun spanTarget(widthPx: Float, halfPx: Float, fullPx: Float): HomeSpan = homeSpanTarget(widthPx, halfPx, fullPx)

    fun spanCommit(from: HomeSpan, target: HomeSpan, hasNext: Boolean): HomeSpanCommit = homeSpanCommit(from, target, hasNext)

    fun spanKey(from: HomeSpan, left: Boolean, hasNext: Boolean): HomeSpanCommit? = homeSpanKey(from, left, hasNext)

    fun listPinchProgress(fromOn: Boolean, scale: Float): Float = homeListPinchProgress(fromOn, scale)

    fun listSettlesOn(fromOn: Boolean, progress: Float): Boolean = homeListSettlesOn(fromOn, progress)

    // ── conversions ──────────────────────────────────────────────────

    private inline fun edit(state: HomeLayoutState, transform: (HomeLayout) -> HomeLayout): HomeLayoutResult {
        val layout = state.toLayout()
        val next = transform(layout)
        return if (next === layout) HomeLayoutResult(state, changed = false) else HomeLayoutResult(next.toState(), changed = true)
    }

    private inline fun edit(state: HomeLayoutState, id: String, transform: (HomeLayout, HomeSection) -> HomeLayout): HomeLayoutResult {
        val section = HomeSection.fromId(id) ?: return HomeLayoutResult(state, changed = false)
        return edit(state) { transform(it, section) }
    }

    // Null when any id is unknown: an edit given one changes nothing.
    private fun sectionsOf(ids: List<String>): List<HomeSection>? = ids.map { HomeSection.fromId(it) ?: return null }

    private fun pairsOf(pairs: List<List<String>>): Map<HomeSection, HomeSection>? {
        val out = LinkedHashMap<HomeSection, HomeSection>()
        for (pair in pairs) {
            if (pair.size != 2) return null
            out[HomeSection.fromId(pair[0]) ?: return null] = HomeSection.fromId(pair[1]) ?: return null
        }
        return out
    }

    private fun rowOf(ids: List<String>): HomeFeedRow? = when (ids.size) {
        1 -> HomeSection.fromId(ids[0])?.let { HomeFeedRow.Full(it) }
        2 -> {
            val left = HomeSection.fromId(ids[0])
            val right = HomeSection.fromId(ids[1])
            if (left != null && right != null) HomeFeedRow.Halves(left, right) else null
        }
        else -> null
    }

    private fun rowIds(row: HomeFeedRow): List<String> = when (row) {
        is HomeFeedRow.Full -> listOf(row.section.id)
        is HomeFeedRow.Halves -> listOf(row.left.id, row.right.id)
    }

    private fun Map<HomeSection, HomeSection>.toIdPairs(): List<List<String>> = map { (l, r) -> listOf(l.id, r.id) }
}

/**
 * One section of a [HomeLayoutState], at its position: its id, whether it renders, its row preset key
 * ([rows], `s` / `m` / `l` / `xl`) and whether it was chosen ([rowsSet]), half of a two-column pair
 * ([half]), its own two-column unit count when it keeps one apart ([units], Lyric Cards), and the JSON
 * objects this build keeps without reading: [configExtrasJson] (its `config` keys besides rows / span /
 * units) and [entryExtrasJson] (its entry's keys besides id / enabled / config).
 */
data class HomeLayoutSectionState(
    val id: String,
    val enabled: Boolean,
    val rows: String,
    val rowsSet: Boolean,
    val half: Boolean,
    val units: Int?,
    val configExtrasJson: String?,
    val entryExtrasJson: String?,
)

/** A stored entry whose id this build doesn't know, kept verbatim ([HomeSectionPref]). */
data class HomeLayoutRetainedState(
    val id: String,
    val enabled: Boolean,
    val configJson: String?,
    val extrasJson: String?,
)

/**
 * A Home layout as plain values ([HomeLayout]): [sections] in render order (hidden ones keep their slot),
 * the [retained] entries this build doesn't know, the [newSections] appended hidden on this read (badged
 * "New"), the two-column pairing memory [widePairs] (`[left, right]`, disjoint), and the stored memory's and
 * document's parts this build keeps without reading ([retainedWideJson], [retainedDocumentJson]).
 */
data class HomeLayoutState(
    val sections: List<HomeLayoutSectionState>,
    val retained: List<HomeLayoutRetainedState>,
    val newSections: List<String>,
    val widePairs: List<List<String>>,
    val retainedWideJson: String?,
    val retainedDocumentJson: String?,
)

/** An edit's result: the layout after it, and whether it changed anything ([state] is the input when not). */
data class HomeLayoutResult(val state: HomeLayoutState, val changed: Boolean)

/** What persisting a layout does: clear the stored row, or write [documentJson]. */
data class HomeLayoutPersist(val clear: Boolean, val documentJson: String?)

// ── HomeLayout ⇄ HomeLayoutState ─────────────────────────────────────────

private val stateJson = Json

private fun JsonObject?.text(): String? = this?.let { stateJson.encodeToString(JsonElement.serializer(), it) }

private fun String?.obj(): JsonObject? = this?.let { runCatching { stateJson.parseToJsonElement(it) }.getOrNull() as? JsonObject }

/** [this] layout as plain values. */
fun HomeLayout.toState(): HomeLayoutState = HomeLayoutState(
    sections = sections.map {
        HomeLayoutSectionState(
            id = it.section.id,
            enabled = it.enabled,
            rows = it.rows.key,
            rowsSet = it.rowsSet,
            half = it.half,
            units = it.units,
            configExtrasJson = it.extras.text(),
            entryExtrasJson = it.entryExtras.text(),
        )
    },
    retained = retained.map { HomeLayoutRetainedState(it.id, it.enabled, it.config.text(), it.extras.text()) },
    newSections = HomeSection.entries.filter { it in newSections }.map { it.id },
    widePairs = widePairs.map { (l, r) -> listOf(l.id, r.id) },
    retainedWideJson = retainedWide.text(),
    retainedDocumentJson = retainedDocument.text(),
)

/** [this] state as a [HomeLayout]; sections and pairs naming an unknown id are left out. */
fun HomeLayoutState.toLayout(): HomeLayout {
    val memory = LinkedHashMap<HomeSection, HomeSection>()
    for (pair in widePairs) {
        val left = pair.getOrNull(0)?.let(HomeSection::fromId) ?: continue
        val right = pair.getOrNull(1)?.let(HomeSection::fromId) ?: continue
        if (pair.size == 2) memory[left] = right
    }
    return HomeLayout(
        sections = sections.mapNotNull { s ->
            val section = HomeSection.fromId(s.id) ?: return@mapNotNull null
            HomeSectionState(
                section = section,
                enabled = s.enabled,
                rows = HomeRowPreset.fromKey(s.rows) ?: HomeRowPreset.Default,
                extras = s.configExtrasJson.obj(),
                half = s.half,
                rowsSet = s.rowsSet,
                units = s.units,
                entryExtras = s.entryExtrasJson.obj(),
            )
        },
        retained = retained.map { HomeSectionPref(it.id, it.enabled, it.configJson.obj(), it.extrasJson.obj()) },
        newSections = newSections.mapNotNull(HomeSection::fromId).toSet(),
        widePairs = memory,
        retainedWide = retainedWideJson.obj(),
        retainedDocument = retainedDocumentJson.obj(),
    )
}
