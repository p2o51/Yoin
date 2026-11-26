package com.gpo.yoin.shared.home

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

// Home's layout model (docs/design.md §主页 › 就地编辑; Android PR #250 / #292), moved unchanged from
// Android's ui/home/HomeSection.kt for parity phase 4: one order and one show / hide state for both widths,
// the two-column pairing memory, and every edit. Android, iOS (through YoinHomeLayout) and the HarmonyOS
// Dart port answer exactly as shared/src/commonTest/resources/golden/home-layout.json says.

/**
 * One section plus whether it currently renders, at its resolved position.
 * [rows] is its row preset; [extras] are the other keys of its persisted
 * settings bag, kept verbatim (a newer build's settings, or a rows value this
 * build can't read), so a re-encode loses nothing.
 */
data class HomeSectionState(
    val section: HomeSection,
    val enabled: Boolean,
    val rows: HomeRowPreset = HomeRowPreset.Default,
    val extras: JsonObject? = null,
    /**
     * Half of a two-column row on a large screen (`"span":"half"`). Always one
     * of a pair: two adjacent halves in the enabled order ([HomeLayout.normalizedSpans]),
     * and always a pair the two-column memory ([HomeLayout.widePairs]) holds at this
     * order. Below the two-column width it stacks like any section, the flag kept.
     */
    val half: Boolean = false,
    /**
     * [rows] was chosen, not defaulted: stored even at [HomeRowPreset.Default]. Two columns read
     * an unchosen preset as the section's own default unit count ([homeSectionUnits]).
     */
    val rowsSet: Boolean = false,
    /**
     * Two columns' unit count for a section whose row preset already means something else (「歌词一句」:
     * its preset is its card count). Stored as `"units"`; null = its default.
     */
    val units: Int? = null,
    /**
     * The persisted entry's keys besides `id`, `enabled` and `config` (a newer build's), kept verbatim
     * ([HomeSectionPref.extras]). Part of the arrangement like [extras]: a layout carrying them isn't a default.
     */
    val entryExtras: JsonObject? = null,
) {
    /** The settings bag to persist: [extras] plus `rows` when chosen (or not the default), `span` when half; null when empty. */
    fun config(): JsonObject? {
        val bag = LinkedHashMap(extras.orEmpty())
        if (rowsSet || rows != HomeRowPreset.Default) bag[RowsKey] = JsonPrimitive(rows.key)
        if (half) bag[SpanKey] = JsonPrimitive(SpanHalf)
        units?.let { bag[UnitsKey] = JsonPrimitive(it) }
        return bag.takeIf { it.isNotEmpty() }?.let(::JsonObject)
    }

    companion object {
        const val RowsKey = "rows"
        const val SpanKey = "span"
        const val SpanHalf = "half"
        const val UnitsKey = "units"

        /**
         * [pref] as a state: a readable `rows` becomes [rows], `"span":"half"` becomes [half],
         * everything else stays in [extras] (a span value this build can't read included).
         */
        fun fromPref(section: HomeSection, pref: HomeSectionPref): HomeSectionState {
            val config = pref.config
            val rows = (config?.get(RowsKey) as? JsonPrimitive)?.contentOrNull?.let(HomeRowPreset::fromKey)
            val half = (config?.get(SpanKey) as? JsonPrimitive)?.contentOrNull == SpanHalf
            val units = (config?.get(UnitsKey) as? JsonPrimitive)?.contentOrNull?.toIntOrNull()
            val extras = config?.filterKeys { key ->
                !(key == RowsKey && rows != null) && !(key == SpanKey && half) && !(key == UnitsKey && units != null)
            }
            return HomeSectionState(
                section = section,
                enabled = pref.enabled,
                rows = rows ?: HomeRowPreset.Default,
                extras = extras?.takeIf { it.isNotEmpty() }?.let(::JsonObject),
                half = half,
                rowsSet = rows != null,
                units = units,
                entryExtras = pref.extras,
            )
        }
    }
}

/**
 * The resolved, ordered home layout the feed renders from. [sections] is in
 * render order; disabled entries keep their slot (so "Show" returns a section
 * home) — the feed simply skips `!enabled` rows.
 *
 * One order for both widths (owner 2026-11-23: 「转了竖屏以后只保留顺序即可，回到横屏再记忆哪些在左边哪些在右边」):
 * [sections] is the order and visibility one column and two columns share. Who
 * pairs with whom in two columns is remembered apart, in [widePairs], and shown
 * wherever the order allows it: a remembered pair is a
 * row of two halves while both are on and next to each other in the order, the
 * one first in the order on the left ([homeWidePairsAt]). The `half` flags are that result at this order,
 * kept in step by every operation, so the feed and older builds read them as before.
 * Order and visibility edits leave the memory alone; two-column edits make it
 * what two columns show ([rememberingShownPairs]).
 *
 * [retained] holds ids this build doesn't know, verbatim, so a downgrade →
 * upgrade round trip loses nothing; [retainedWide] does the same for the stored
 * memory (pairs of ids this build doesn't know, keys a newer build added), and
 * [retainedDocument] for the document's own keys a newer build added.
 * [newSections] are the sections appended to a customized layout on this read.
 * None of the four is part of the user's arrangement: compare layouts with
 * [sameSectionsAs], never data-class `==`.
 *
 * Every operation returns `this` when it changes nothing, so callers can skip
 * the write by identity.
 */
data class HomeLayout(
    val sections: List<HomeSectionState>,
    val retained: List<HomeSectionPref> = emptyList(),
    val newSections: Set<HomeSection> = emptySet(),
    /**
     * The two-column memory, left → right; pairs are disjoint. Defaults to the
     * pairs [sections] show, so a layout built from flags remembers what it shows.
     */
    val widePairs: Map<HomeSection, HomeSection> = homeSpanPairs(
        sections.filter { it.enabled }.map { it.section to it.half },
    ),
    /** The stored memory's parts this build can't read, written back verbatim (see [toDocument]). */
    val retainedWide: JsonObject? = null,
    /** The stored document's keys this build doesn't read ([HomeLayoutDocument.extras]), written back verbatim. */
    val retainedDocument: JsonObject? = null,
) {

    val enabledSections: List<HomeSection>
        get() = sections.filter { it.enabled }.map { it.section }

    /** Disabled sections in layout order (the tray order). */
    val hiddenSections: List<HomeSection>
        get() = sections.filterNot { it.enabled }.map { it.section }

    /** The catalog arrangement, every section at its default rows, the default memory; ignores [retained] and [newSections]. */
    val isDefault: Boolean
        get() = sameSectionsAs(Default)

    /** The large-screen default ([WideDefault]): what a never-customized profile shows in two columns. */
    val isWideDefault: Boolean
        get() = sameSectionsAs(WideDefault)

    /** Either default: a write of this clears the row back to "never customized". */
    val isAnyDefault: Boolean
        get() = isDefault || isWideDefault

    /**
     * What the feed shows at its width: in two columns a never-customized layout
     * ([isDefault]) is [WideDefault]; anything else is shown as stored.
     */
    fun forTwoColumn(twoColumn: Boolean): HomeLayout =
        if (twoColumn && isDefault) WideDefault.carrying(this) else this

    /**
     * [forTwoColumn] both ways, for a draft that changes width: a default becomes
     * the other width's default (each is "never customized", so writing either clears
     * the row); anything else is the same layout, its pairs already at this order.
     */
    fun shownAt(twoColumn: Boolean): HomeLayout = when {
        twoColumn && isDefault -> WideDefault.carrying(this)
        !twoColumn && isWideDefault -> Default.carrying(this)
        else -> this
    }

    // [this] default with [from]'s parts outside the arrangement (retained ids, new sections, the document's kept keys).
    private fun carrying(from: HomeLayout): HomeLayout =
        copy(retained = from.retained, newSections = from.newSections, retainedDocument = from.retainedDocument)

    /** The default this width resets to. */
    fun isDefaultFor(twoColumn: Boolean): Boolean = if (twoColumn) isWideDefault else isDefault

    /**
     * The two-column memory made what two columns show now ([pairs]): a pair
     * remembered but not shown here (split up or hidden in one column) is forgotten.
     * Every two-column edit ends with it. `this` when they already agree.
     */
    fun rememberingShownPairs(): HomeLayout {
        val shown = pairs
        return if (homeSamePairs(shown, widePairs)) this else copy(widePairs = shown)
    }

    /**
     * The `half` flags set from the memory ([widePairs]) at this order: the pairs
     * [homeWidePairsAt] finds are halves, everything else full. Order and visibility
     * edits end with it; also for a caller that reorders [sections] itself (the landing).
     */
    fun withRememberedPairs(): HomeLayout {
        val shown = homeWidePairsAt(enabledSections, widePairs)
        val halves = shown.keys + shown.values
        if (sections.all { it.half == (it.enabled && it.section in halves) }) return this
        return copy(
            sections = sections.map { state ->
                val half = state.enabled && state.section in halves
                if (state.half == half) state else state.copy(half = half)
            },
        )
    }

    /** The pairs of the enabled order, left → right (see [homeSpanPairs]). */
    val pairs: Map<HomeSection, HomeSection>
        get() = homeSpanPairs(sections.filter { it.enabled }.map { it.section to it.half })

    /** [section]'s partner in its pair, either side; null when it is full width. */
    fun partnerOf(section: HomeSection): HomeSection? {
        val pairs = pairs
        return pairs[section] ?: pairs.entries.firstOrNull { it.value == section }?.key
    }

    /**
     * Every half flag that isn't one of a pair cleared: disabled sections are
     * full, and in the enabled order halves pair two by two ([homeSpanPairs]);
     * a half left over is full. `this` when nothing changes.
     */
    fun normalizedSpans(): HomeLayout {
        val paired = pairs.flatMap { listOf(it.key, it.value) }.toSet()
        if (sections.none { it.half && it.section !in paired }) return this
        return copy(sections = sections.map { if (it.half && it.section !in paired) it.copy(half = false) else it })
    }

    /**
     * [section] (full width) becomes the left half of a pair with the row after
     * it: a full section joins it, or a pair gives up its first member (the
     * other one becomes full). `this` when [section] is already half, hidden,
     * or last.
     */
    fun withHalf(section: HomeSection): HomeLayout {
        val enabled = enabledSections
        val index = enabled.indexOf(section)
        if (index < 0 || partnerOf(section) != null) return this
        val next = enabled.getOrNull(index + 1) ?: return this
        // Two halves share one height ([homeRowUnits]): sections whose height ranges never meet don't pair.
        if (!homeCanPair(section, next)) return this
        val nextPartner = pairs[next]
        val halves = setOf(section, next)
        return copy(
            sections = sections.map { state ->
                when (state.section) {
                    in halves -> state.copy(half = true)
                    nextPartner -> state.copy(half = false)
                    else -> state
                }
            },
        ).normalizedSpans().rememberingShownPairs()
    }

    /**
     * [section] (one of a pair) becomes full width, and so does its partner,
     * which takes its own row right after it: a right half moves ahead of its
     * old left partner. `this` when [section] is already full.
     */
    fun withFull(section: HomeSection): HomeLayout {
        val partner = partnerOf(section) ?: return this
        val unpaired = copy(
            sections = sections.map { if (it.section == section || it.section == partner) it.copy(half = false) else it },
        )
        val isRight = pairs[partner] == section
        if (!isRight) return unpaired.rememberingShownPairs()
        val order = enabledSections.toMutableList()
        val left = order.indexOf(partner)
        order[left] = section
        order[left + 1] = partner
        return unpaired.withFullEnabledOrder(order).normalizedSpans().rememberingShownPairs()
    }

    /** Known sections in order (with their settings bags), then the [retained] ids. */
    fun toPrefs(): List<HomeSectionPref> =
        sections.map { HomeSectionPref(id = it.section.id, enabled = it.enabled, config = it.config(), extras = it.entryExtras) } + retained

    /**
     * The two-column memory as stored (`{"pairs":[["left","right"], …]}`), [retainedWide]
     * merged back in. Always written, empty included: an absent memory means an older
     * writer, and is derived again on read ([reconcile]).
     */
    fun wideDocument(): JsonObject {
        val known = widePairs.map { (left, right) -> JsonArray(listOf(JsonPrimitive(left.id), JsonPrimitive(right.id))) }
        val kept = (retainedWide?.get(WidePairsKey) as? JsonArray).orEmpty()
        return JsonObject(retainedWide.orEmpty() + (WidePairsKey to JsonArray(known + kept)))
    }

    /** What the store writes: [toPrefs], [wideDocument] and the document's kept keys ([retainedDocument]). */
    fun toDocument(): HomeLayoutDocument =
        HomeLayoutDocument(sections = toPrefs(), wide = wideDocument(), extras = retainedDocument)

    /**
     * [section] (full width) becomes the right half beside the enabled section
     * before it, which must be full width too (TalkBack's "Place beside
     * previous"). `this` when either is already half, or [section] is first or hidden.
     */
    fun withBesidePrevious(section: HomeSection): HomeLayout {
        val enabled = enabledSections
        val index = enabled.indexOf(section)
        if (index < 1 || partnerOf(section) != null) return this
        val previous = enabled[index - 1]
        if (partnerOf(previous) != null || !homeCanPair(previous, section)) return this
        return copy(
            sections = sections.map { state ->
                if (state.section == section || state.section == previous) state.copy(half = true) else state
            },
        ).normalizedSpans().rememberingShownPairs()
    }

    /**
     * A two-column carry's drop: the enabled order [order] (as
     * [withEnabledOrder] takes it) with exactly [pairs] (left → right, each
     * pair adjacent in [order]) as halves, every other section full. Sections
     * in [unseen], and their stored partners, keep their flags. `this` when
     * nothing changes.
     */
    fun withArrangement(
        order: List<HomeSection>,
        pairs: Map<HomeSection, HomeSection>,
        unseen: Set<HomeSection> = emptySet(),
    ): HomeLayout {
        // A pair whose height ranges never meet can't share a row height ([homeCanPair]): both stay full.
        val paired = pairs.filter { (left, right) -> homeCanPair(left, right) }
        val halves = paired.keys + paired.values
        val keep = unseen + unseen.mapNotNull(::partnerOf)
        val spanned = copy(
            sections = sections.map { state ->
                if (!state.enabled || state.section in keep) state else state.copy(half = state.section in halves)
            },
        )
        val result = spanned.reorderedEnabled(order, unseen).normalizedSpans()
        // Nothing two columns show changed: no step, the memory as it was.
        return if (result.sections == sections) this else result.rememberingShownPairs()
    }

    /** [section]'s own unit count in two columns ([homeSectionUnits]); its default when it is not in this layout. */
    fun unitsOf(section: HomeSection): Int =
        sections.firstOrNull { it.section == section }?.let(::homeSectionUnits) ?: section.defaultUnits

    /** The unit count [row] shows in two columns: a pair takes its left half's, held in both ranges. */
    fun unitsOf(row: HomeFeedRow): Int {
        val byId = sections.associateBy { it.section }
        return when (row) {
            is HomeFeedRow.Full -> byId[row.section]?.let(::homeSectionUnits) ?: row.section.defaultUnits
            is HomeFeedRow.Halves -> {
                val left = byId[row.left] ?: return row.left.defaultUnits
                homeRowUnits(left, byId[row.right])
            }
        }
    }

    /**
     * [section]'s row at [units] in two columns: the section and, when it is one
     * of a pair ([pairs]), its partner, held in the overlap of both ranges. Any
     * section takes it, not only those with phone presets; stored as the row
     * preset (S–XL = 1–4). `this` when nothing changes.
     */
    fun withUnits(section: HomeSection, units: Int, pairs: Map<HomeSection, HomeSection> = this.pairs): HomeLayout {
        val partner = pairs[section] ?: pairs.entries.firstOrNull { it.value == section }?.key
        val range = homeRowUnitRange(section, partner)
        val preset = homeUnitsPreset(units.coerceIn(range))
        val row = setOfNotNull(section, partner)
        val count = preset.units
        fun HomeSectionState.withCount(): HomeSectionState =
            if (this.section.unitsOwnKey) copy(units = count) else copy(rows = preset, rowsSet = true)
        if (sections.none { it.section in row && it.withCount() != it }) return this
        return copy(
            sections = sections.map { state -> if (state.section in row) state.withCount() else state },
        )
    }

    /**
     * Same order, visibility and rows (and settings bags), and the same two-column memory: the same
     * pairs, whichever side each was remembered on (the order puts them left and right, [homeWidePairsAt]).
     */
    fun sameSectionsAs(other: HomeLayout): Boolean = sections == other.sections && homeSamePairs(widePairs, other.widePairs)

    /**
     * The catalog arrangement — rows and the two-column memory back to their defaults too — keeping [retained]
     * (and [retainedDocument]: like retained ids, what this build can't read isn't its to reset).
     */
    fun reset(): HomeLayout = if (isDefault) this else Default.copy(retained = retained, retainedDocument = retainedDocument)

    /** [reset] to this width's default: [WideDefault] in two columns. */
    fun reset(twoColumn: Boolean): HomeLayout = when {
        !twoColumn -> reset()
        isWideDefault -> this
        else -> WideDefault.copy(retained = retained, retainedDocument = retainedDocument)
    }

    /** [section]'s row preset ([HomeRowPreset.Default] when it is not in this layout). */
    fun rowsOf(section: HomeSection): HomeRowPreset =
        sections.firstOrNull { it.section == section }?.rows ?: HomeRowPreset.Default

    /** [section] at [rows]. Returns `this` for a section without presets, absent, or already there. */
    fun withRows(section: HomeSection, rows: HomeRowPreset): HomeLayout {
        if (!section.takesPresets || sections.none { it.section == section && it.rows != rows }) return this
        return copy(
            sections = sections.map { state ->
                // The phone's resize back to L clears it, as before; two columns choose through [withUnits].
                if (state.section == section) state.copy(rows = rows, rowsSet = rows != HomeRowPreset.Default) else state
            },
        )
    }

    fun withEnabled(section: HomeSection, enabled: Boolean): HomeLayout {
        if (sections.none { it.section == section && it.enabled != enabled }) return this
        return copy(
            sections = sections.map { state -> if (state.section == section) state.copy(enabled = enabled) else state },
        ).withRememberedPairs()
    }

    /**
     * Refill the enabled slots in [order]; disabled entries keep their absolute
     * index (proto.js withEnabledOrder). Returns `this` when [order] is the
     * current one or not a permutation of [enabledSections].
     */
    fun withEnabledOrder(order: List<HomeSection>, unseen: Set<HomeSection> = emptySet()): HomeLayout {
        // The memory stays; the pairs it holds show wherever the new order allows.
        val reordered = reorderedEnabled(order, unseen)
        return if (reordered === this) this else reordered.withRememberedPairs()
    }

    /** [withEnabledOrder] without touching the `half` flags. */
    private fun reorderedEnabled(order: List<HomeSection>, unseen: Set<HomeSection>): HomeLayout {
        val enabled = enabledSections
        val distinct = order.toSet()
        if (distinct.size != order.size || !enabled.containsAll(distinct)) return this
        // [unseen]: enabled sections the feed never shows on this device (「歌词一句」 without Gemini Nano), so an
        // edit-mode carry can't include them. They keep their place in the enabled order; the rest fill around.
        val missing = enabled.filterNot { it in distinct }
        if (!unseen.containsAll(missing)) return this
        val full = if (missing.isEmpty()) order else completeEnabledOrder(enabled, order) ?: return this
        if (full == enabled) return this
        return withFullEnabledOrder(full)
    }

    private fun completeEnabledOrder(enabled: List<HomeSection>, order: List<HomeSection>): List<HomeSection>? {
        val shown = order.toSet()
        if (shown.isEmpty()) return null
        val next = order.iterator()
        return enabled.map { section -> if (section in shown) next.next() else section }
    }

    private fun withFullEnabledOrder(order: List<HomeSection>): HomeLayout {
        // Each moved section takes its own state (rows, settings) with it.
        val byStateSection = sections.filter { it.enabled }.associateBy { it.section }
        val next = order.iterator()
        return copy(
            sections = sections.map { state ->
                if (state.enabled) byStateSection.getValue(next.next()) else state
            },
        )
    }

    /** Move [section] to [toEnabledIndex] in the enabled order (clamped). */
    fun moved(section: HomeSection, toEnabledIndex: Int): HomeLayout {
        val enabled = enabledSections
        val from = enabled.indexOf(section)
        if (from < 0) return this
        val to = toEnabledIndex.coerceIn(0, enabled.lastIndex)
        if (to == from) return this
        return withEnabledOrder(
            enabled.toMutableList().apply {
                removeAt(from)
                add(to, section)
            },
        )
    }

    companion object {
        const val WidePairsKey = "pairs"

        /**
         * The large-screen default (owner 2026-11-16): Activities | Jump Back In,
         * All Memories, Made For You, Lyric Cards | Recently Added, Recently
         * Played, Your Playlists | Rediscover, then the other accounts. Shown in
         * two columns to a profile that never customized ([forTwoColumn]).
         */
        val WideDefault: HomeLayout = run {
            val halves = setOf(
                HomeSection.Activities,
                HomeSection.JumpBackIn,
                HomeSection.LyricHighlight,
                HomeSection.RecentlyAdded,
                HomeSection.YourPlaylists,
                HomeSection.Rediscover,
            )
            val order = listOf(
                HomeSection.Activities,
                HomeSection.JumpBackIn,
                HomeSection.MemoryGrid,
                HomeSection.MadeForYou,
                HomeSection.LyricHighlight,
                HomeSection.RecentlyAdded,
                HomeSection.RecentlyPlayed,
                HomeSection.YourPlaylists,
                HomeSection.Rediscover,
                HomeSection.OtherProfiles,
            )
            val ordered = order + HomeSection.entries.filterNot { it in order }
            HomeLayout(ordered.map { HomeSectionState(it, it.defaultEnabled, half = it in halves) })
        }

        /**
         * Catalog defaults: every section in enum order at its [HomeSection.defaultEnabled].
         * Its two-column memory is [WideDefault]'s pairs, so a layout first edited in one
         * column shows them in two wherever its order allows (Activities | Jump Back In here).
         */
        val Default: HomeLayout = HomeLayout(
            HomeSection.entries.map { HomeSectionState(it, it.defaultEnabled) },
            widePairs = WideDefault.widePairs,
        ).withRememberedPairs()

        /** [reconcile] for a stored document (null = never customized), its kept keys carried along. */
        fun fromDocument(document: HomeLayoutDocument?): HomeLayout {
            val read = reconcile(document?.sections, document?.wide)
            val extras = document?.extras ?: return read
            return read.copy(retainedDocument = extras)
        }

        // Ids of sections that were removed from the catalog: dropped, never retained.
        private val RetiredSectionIds = HomeSection.RetiredIds

        /**
         * Merge a persisted layout with the current catalog:
         *  - keep saved order, enabled flags and settings (rows) for ids still
         *    in the catalog (first occurrence wins),
         *  - drop retired ids, retain every other unknown id (first occurrence,
         *    in order),
         *  - append any catalog section the saved layout never knew about at
         *    its [HomeSection.appendEnabled]; the ones appended hidden become
         *    [newSections].
         *
         * A null / empty saved layout ("never customized") yields [Default].
         *
         * The two-column memory comes from [wide] when there is one. Without it (an
         * older build or iOS wrote last: they drop it) it is the pairs the
         * `half` flags show, or [WideDefault]'s when they show none. A pair
         * [WideDefault] holds for a section this read appended joins it when both
         * sections are free. The flags are then set from the memory.
         */
        fun reconcile(prefs: List<HomeSectionPref>?, wide: JsonObject? = null): HomeLayout {
            if (prefs.isNullOrEmpty()) return Default
            val seen = LinkedHashSet<HomeSection>()
            val retainedIds = HashSet<String>()
            val ordered = mutableListOf<HomeSectionState>()
            val retained = mutableListOf<HomeSectionPref>()
            for (pref in prefs) {
                val section = HomeSection.fromId(pref.id)
                when {
                    section != null -> if (seen.add(section)) ordered += HomeSectionState.fromPref(section, pref)
                    pref.id.isBlank() || pref.id in RetiredSectionIds -> Unit
                    retainedIds.add(pref.id) -> retained += pref
                }
            }
            val appended = HomeSection.entries.filter { seen.add(it) }
            ordered += appended.map { HomeSectionState(it, it.appendEnabled) }
            val read = HomeLayout(
                sections = ordered,
                retained = retained,
                newSections = appended.filterNot { it.appendEnabled }.toSet(),
            )
            val stored = wide?.let(::readWide)
            val memory = LinkedHashMap(stored?.first ?: read.pairs.ifEmpty { WideDefault.widePairs })
            val used = (memory.keys + memory.values).toMutableSet()
            for ((left, right) in WideDefault.widePairs) {
                if ((left in appended || right in appended) && left !in used && right !in used) {
                    memory[left] = right
                    used += left
                    used += right
                }
            }
            return read.copy(widePairs = memory, retainedWide = stored?.second).withRememberedPairs()
        }

        /**
         * A stored memory: its known pairs (first one a section is in wins), and what
         * this build keeps without reading (pairs naming an id it doesn't know, other
         * keys). Malformed pairs and pairs naming a retired section drop.
         */
        private fun readWide(wide: JsonObject): Pair<Map<HomeSection, HomeSection>, JsonObject?> {
            val memory = LinkedHashMap<HomeSection, HomeSection>()
            val kept = mutableListOf<JsonElement>()
            for (entry in (wide[WidePairsKey] as? JsonArray).orEmpty()) {
                val ids = (entry as? JsonArray)?.map { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content }
                if (ids == null || ids.size != 2 || ids.any { it.isNullOrBlank() } || ids[0] == ids[1]) continue
                val left = HomeSection.fromId(ids[0]!!)
                val right = HomeSection.fromId(ids[1]!!)
                val used = memory.keys + memory.values
                when {
                    left != null && right != null -> if (left !in used && right !in used) memory[left] = right
                    ids.any { it in RetiredSectionIds } -> Unit
                    else -> kept.add(entry)
                }
            }
            val rest = wide - WidePairsKey
            val retainedWide = if (kept.isEmpty()) rest else rest + (WidePairsKey to JsonArray(kept))
            return memory to retainedWide.takeIf { it.isNotEmpty() }?.let(::JsonObject)
        }
    }
}

/**
 * The remembered pairs ([memory], disjoint) that show at [order] (the enabled
 * order), left → right as the order has them: the two right next to each other,
 * both on, and able to share a row height ([homeCanPair]). A pair the order turned
 * around (the remembered right one now first) still shows, swapped: the one first
 * in the order on the left (owner 2026-11-23). A pair split up or with one side
 * hidden shows as two full rows and stays remembered. Pure.
 */
fun homeWidePairsAt(
    order: List<HomeSection>,
    memory: Map<HomeSection, HomeSection>,
    canPair: (HomeSection, HomeSection) -> Boolean = ::homeCanPair,
): Map<HomeSection, HomeSection> {
    val shown = LinkedHashMap<HomeSection, HomeSection>()
    for (i in 0 until order.size - 1) {
        val left = order[i]
        val right = order[i + 1]
        val remembered = memory[left] == right || memory[right] == left
        if (remembered && canPair(left, right)) shown[left] = right
    }
    return shown
}

/** [a] and [b] hold the same pairs, sides aside: a remembered pair shows in whichever order has it ([homeWidePairsAt]). Pure. */
fun homeSamePairs(a: Map<HomeSection, HomeSection>, b: Map<HomeSection, HomeSection>): Boolean =
    a.size == b.size && a.all { (left, right) -> b[left] == right || b[right] == left }

/**
 * The pairs of an enabled order given as (section, half): halves pair two by
 * two in order, a full section in between breaks the run, and a half left
 * over pairs with nothing. Left → right. Pure.
 */
fun homeSpanPairs(order: List<Pair<HomeSection, Boolean>>): Map<HomeSection, HomeSection> {
    val pairs = LinkedHashMap<HomeSection, HomeSection>()
    var open: HomeSection? = null
    for ((section, half) in order) {
        open = when {
            !half -> null
            open == null -> section
            else -> {
                pairs[open] = section
                null
            }
        }
    }
    return pairs
}

/** One row of the feed: a full-width section, or two halves side by side. */
sealed interface HomeFeedRow {
    val first: HomeSection

    data class Full(val section: HomeSection) : HomeFeedRow {
        override val first: HomeSection get() = section
    }

    data class Halves(val left: HomeSection, val right: HomeSection) : HomeFeedRow {
        override val first: HomeSection get() = left
    }
}

/**
 * The feed's rows for the sections it [displayed], in order. In [twoColumn] a
 * left half whose partner ([pairs], left → right) is displayed right after it
 * shares its row; a half whose partner isn't shown (nothing to show, or this
 * device can't) is full width until it is back. One column: every section is
 * its own row. Pure.
 */
fun homeFeedRows(
    displayed: List<HomeSection>,
    pairs: Map<HomeSection, HomeSection>,
    twoColumn: Boolean,
): List<HomeFeedRow> {
    if (!twoColumn) return displayed.map { HomeFeedRow.Full(it) }
    val rows = ArrayList<HomeFeedRow>(displayed.size)
    var i = 0
    while (i < displayed.size) {
        val section = displayed[i]
        val right = pairs[section]
        if (right != null && displayed.getOrNull(i + 1) == right) {
            rows += HomeFeedRow.Halves(section, right)
            i += 2
        } else {
            rows += HomeFeedRow.Full(section)
            i++
        }
    }
    return rows
}

/**
 * [state]'s own unit count in two columns: its chosen row preset as units, or
 * its default when it never chose one ([HomeSectionState.rowsSet]), held in its range.
 */
fun homeSectionUnits(state: HomeSectionState): Int {
    val stored = when {
        state.section.unitsOwnKey -> state.units ?: state.section.defaultUnits
        state.rowsSet -> state.rows.units
        else -> state.section.defaultUnits
    }
    return stored.coerceIn(state.section.unitRange)
}

/**
 * The count a row of [left] (and [right], when paired) shows: a pair takes
 * the left one's count, held in the overlap of both ranges.
 */
fun homeRowUnits(left: HomeSectionState, right: HomeSectionState?): Int {
    val own = homeSectionUnits(left)
    if (right == null) return own
    return own.coerceIn(left.section.unitRange.intersectUnits(right.section.unitRange))
}
