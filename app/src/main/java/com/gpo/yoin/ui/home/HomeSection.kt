package com.gpo.yoin.ui.home

import androidx.annotation.StringRes
import com.gpo.yoin.R
import com.gpo.yoin.data.home.HomeSectionPref
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * The catalog of home-screen sections a user can show / hide / reorder.
 *
 * Each entry has a stable [id] persisted verbatim — never rename one, that id is
 * the migration key for saved layouts. [defaultEnabled] applies when a profile
 * hasn't customized; [appendEnabled] applies when a newly-shipped section
 * appears in an already-customized layout. The enum declaration order is the
 * *default* render order; a persisted [HomeLayout] overrides both order and
 * visibility.
 *
 * Adding a section later is just: append a constant here + teach
 * `HomeEditorialContent` how to render it. Existing saved layouts pick it up via
 * [HomeLayout.reconcile] at [appendEnabled] (off for new sections, so they land
 * in the edit tray badged "New") — no migration needed. Removing one is the
 * mirror image: delete the constant and add its id to the retired set
 * (`memory_teaser`, `memories` so far); any other unknown id is retained.
 */
enum class HomeSection(
    val id: String,
    val title: String,
    /** One line for the tray rows and TalkBack (not on the feed). */
    val supportingText: String,
    val defaultEnabled: Boolean,
    /** Visibility when appended to an already-customized layout (Q6a). Legacy sections = true. */
    val appendEnabled: Boolean = false,
) {
    Activities(
        id = "activities",
        title = "Activities",
        supportingText = "Your recent plays and visits",
        defaultEnabled = true,
        appendEnabled = true,
    ),
    JumpBackIn(
        id = "jump_back_in",
        title = "Jump Back In",
        supportingText = "Albums, songs and playlists",
        defaultEnabled = true,
        appendEnabled = true,
    ),
    RecentlyAdded(
        id = "recently_added",
        title = "Recently Added",
        supportingText = "Added to your library this month",
        defaultEnabled = true,
        appendEnabled = true,
    ),

    // Last, so existing users' default order is unchanged.
    Rediscover(
        id = "rediscover",
        title = "Rediscover",
        supportingText = "Albums and songs you rated or wrote about, not played in a while",
        defaultEnabled = true,
        appendEnabled = false,
    ),

    // Owner 2026-10-05 (F2): what the account played lately, from the
    // provider's own history — a new device has it before Yoin logs anything.
    RecentlyPlayed(
        id = "recently_played",
        title = "Recently Played",
        supportingText = "Albums you played lately, on any device",
        defaultEnabled = true,
        appendEnabled = true,
    ),

    // Owner 2026-10-05: more Home sections, like Spotify's "Your playlists".
    // Shown for customized layouts too (asked for by name).
    YourPlaylists(
        id = "your_playlists",
        title = "Your Playlists",
        supportingText = "Playlists in your library",
        defaultEnabled = true,
        appendEnabled = true,
    ),

    // Owner 2026-11-04 (「在你的其他资料里」): what the other accounts play
    // most; a tap switches account and plays. Empty with one account, so
    // shown by default and to customized layouts too.
    OtherProfiles(
        id = "other_profiles",
        title = "On Your Other Accounts",
        supportingText = "Albums and playlists your other accounts play most",
        defaultEnabled = true,
        appendEnabled = true,
    ),

    // Owner 2026-11-08 (「歌词一句」): one lyric line Gemini Nano picked on this
    // device (data/highlight/). Only phones with Nano ever have content, so it
    // is on by default and for customized layouts too; elsewhere it never shows.
    LyricHighlight(
        id = "lyric_highlight",
        title = "Lyric Cards",
        supportingText = "Lyrics picked from your songs, tap to play from that line",
        defaultEnabled = true,
        appendEnabled = true,
    ),

    // Owner 2026-11-08 (B3): every memory on a calendar grid whose cells
    // grow when you write less; a tap opens All Memories. Hidden while
    // there's nothing, so shown by default and to customized layouts too.
    MemoryGrid(
        id = "memory_grid",
        title = "All Memories",
        supportingText = "Every note and score, on a grid that grows with you",
        defaultEnabled = true,
        appendEnabled = true,
    ),

    // Owner 2026-11-15 (「为你推荐」): the playlists the service makes for the
    // account — Spotify's daylist, Discover Weekly, Daily Mixes…, Apple Music's
    // personal mixes (HomeMadeForYou.kt). Hidden for services that have none,
    // so on by default and for customized layouts too.
    MadeForYou(
        id = "made_for_you",
        title = "Made For You",
        supportingText = "Playlists Spotify and Apple Music make for you",
        defaultEnabled = true,
        appendEnabled = true,
    ),
    ;

    /**
     * Takes a row preset (D1, owner 2026-10-05: Activities and Jump Back In in
     * v1). Recently Added's grid must stay one album card tall and
     * Rediscover's phone shelf has no rows, so neither does.
     */
    val supportsRows: Boolean
        get() = this == Activities || this == JumpBackIn

    /**
     * Stores a [HomeRowPreset]: the [supportsRows] sections, plus 「歌词一句」, whose preset is how many
     * cards it shows (owner 2026-11-08: one to four) and which picks it with its own dots, not the row resize.
     */
    val takesPresets: Boolean
        get() = supportsRows || this == LyricHighlight

    companion object {
        private val byId: Map<String, HomeSection> = entries.associateBy { it.id }

        fun fromId(id: String): HomeSection? = byId[id]
    }
}

@get:StringRes
internal val HomeSection.titleRes: Int
    get() = when (this) {
        HomeSection.Activities -> R.string.home_section_activities
        HomeSection.JumpBackIn -> R.string.home_section_jump_back_in
        HomeSection.RecentlyAdded -> R.string.home_section_recently_added
        HomeSection.Rediscover -> R.string.home_section_rediscover
        HomeSection.RecentlyPlayed -> R.string.home_section_recently_played
        HomeSection.YourPlaylists -> R.string.home_section_your_playlists
        HomeSection.OtherProfiles -> R.string.home_section_other_profiles
        HomeSection.LyricHighlight -> R.string.home_section_lyric_highlight
        HomeSection.MemoryGrid -> R.string.home_section_memory_grid
        HomeSection.MadeForYou -> R.string.home_section_made_for_you
    }

@get:StringRes
internal val HomeSection.supportingRes: Int
    get() = when (this) {
        HomeSection.Activities -> R.string.home_section_activities_supporting
        HomeSection.JumpBackIn -> R.string.home_section_jump_back_in_supporting
        HomeSection.RecentlyAdded -> R.string.home_section_recently_added_supporting
        HomeSection.Rediscover -> R.string.home_section_rediscover_supporting
        HomeSection.RecentlyPlayed -> R.string.home_section_recently_played_supporting
        HomeSection.YourPlaylists -> R.string.home_section_your_playlists_supporting
        HomeSection.OtherProfiles -> R.string.home_section_other_profiles_supporting
        HomeSection.LyricHighlight -> R.string.home_section_lyric_highlight_supporting
        HomeSection.MemoryGrid -> R.string.home_section_memory_grid_supporting
        HomeSection.MadeForYou -> R.string.home_section_made_for_you_supporting
    }

@get:StringRes
internal val HomeSection.placeholderRes: Int
    get() = when (this) {
        HomeSection.Activities -> R.string.home_placeholder_activities
        HomeSection.JumpBackIn -> R.string.home_placeholder_jump_back_in
        HomeSection.RecentlyAdded -> R.string.home_placeholder_recently_added
        HomeSection.Rediscover -> R.string.home_placeholder_rediscover
        HomeSection.RecentlyPlayed -> R.string.home_placeholder_recently_played
        HomeSection.YourPlaylists -> R.string.home_placeholder_your_playlists
        HomeSection.OtherProfiles -> R.string.home_placeholder_other_profiles
        HomeSection.LyricHighlight -> R.string.home_placeholder_lyric_highlight
        HomeSection.MemoryGrid -> R.string.home_placeholder_memory_grid
        HomeSection.MadeForYou -> R.string.home_placeholder_made_for_you
    }

/**
 * How many rows a section shows (D1). Presets, not numbers: each screen turns
 * one into its own row count (HomeRowPresets.kt), so the choice travels with
 * the profile across devices. [L] is the composition from before presets
 * existed — a section nobody resized stores nothing and renders as it did.
 */
enum class HomeRowPreset(val key: String) {
    S("s"),
    M("m"),
    L("l"),
    XL("xl"),
    ;

    companion object {
        val Default: HomeRowPreset = L

        /** The preset stored under [key]; null for a key this build doesn't know. */
        fun fromKey(key: String?): HomeRowPreset? = entries.firstOrNull { it.key == key }
    }
}

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
     * of a pair: two adjacent halves in the enabled order ([HomeLayout.normalizedSpans]).
     * Below the two-column width it stacks like any section, the flag kept.
     */
    val half: Boolean = false,
) {
    /** The settings bag to persist: [extras] plus `rows` unless it is the default, `span` when half; null when empty. */
    fun config(): JsonObject? {
        val bag = LinkedHashMap(extras.orEmpty())
        if (rows != HomeRowPreset.Default) bag[RowsKey] = JsonPrimitive(rows.key)
        if (half) bag[SpanKey] = JsonPrimitive(SpanHalf)
        return bag.takeIf { it.isNotEmpty() }?.let(::JsonObject)
    }

    companion object {
        internal const val RowsKey = "rows"
        internal const val SpanKey = "span"
        internal const val SpanHalf = "half"

        /**
         * [pref] as a state: a readable `rows` becomes [rows], `"span":"half"` becomes [half],
         * everything else stays in [extras] (a span value this build can't read included).
         */
        internal fun fromPref(section: HomeSection, pref: HomeSectionPref): HomeSectionState {
            val config = pref.config
            val rows = (config?.get(RowsKey) as? JsonPrimitive)?.contentOrNull?.let(HomeRowPreset::fromKey)
            val half = (config?.get(SpanKey) as? JsonPrimitive)?.contentOrNull == SpanHalf
            val extras = config?.filterKeys { key ->
                !(key == RowsKey && rows != null) && !(key == SpanKey && half)
            }
            return HomeSectionState(
                section = section,
                enabled = pref.enabled,
                rows = rows ?: HomeRowPreset.Default,
                extras = extras?.takeIf { it.isNotEmpty() }?.let(::JsonObject),
                half = half,
            )
        }
    }
}

/**
 * The resolved, ordered home layout the feed renders from. [sections] is in
 * render order; disabled entries keep their slot (so "Show" returns a section
 * home) — the feed simply skips `!enabled` rows.
 *
 * [retained] holds ids this build doesn't know, verbatim, so a downgrade →
 * upgrade round trip loses nothing; [newSections] are the sections appended
 * to a customized layout on this read. Neither is part of the user's
 * arrangement: compare layouts with [sameSectionsAs], never data-class `==`.
 *
 * Every operation returns `this` when it changes nothing, so callers can skip
 * the write by identity.
 */
data class HomeLayout(
    val sections: List<HomeSectionState>,
    val retained: List<HomeSectionPref> = emptyList(),
    val newSections: Set<HomeSection> = emptySet(),
) {

    val enabledSections: List<HomeSection>
        get() = sections.filter { it.enabled }.map { it.section }

    /** Disabled sections in layout order (the tray order). */
    val hiddenSections: List<HomeSection>
        get() = sections.filterNot { it.enabled }.map { it.section }

    /** The catalog arrangement, every section at its default rows; ignores [retained] and [newSections]. */
    val isDefault: Boolean
        get() = sections == Default.sections

    /** The large-screen default ([WideDefault]): what a never-customized profile shows in two columns. */
    val isWideDefault: Boolean
        get() = sections == WideDefault.sections

    /** Either default: a write of this clears the row back to "never customized". */
    val isAnyDefault: Boolean
        get() = isDefault || isWideDefault

    /**
     * What the feed shows at its width: in two columns a never-customized layout
     * ([isDefault]) is [WideDefault]; anything else is shown as stored.
     */
    fun forTwoColumn(twoColumn: Boolean): HomeLayout =
        if (twoColumn && isDefault) WideDefault.copy(retained = retained, newSections = newSections) else this

    /** The default this width resets to. */
    fun isDefaultFor(twoColumn: Boolean): Boolean = if (twoColumn) isWideDefault else isDefault

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
        ).normalizedSpans()
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
        if (!isRight) return unpaired
        val order = enabledSections.toMutableList()
        val left = order.indexOf(partner)
        order[left] = section
        order[left + 1] = partner
        return unpaired.withFullEnabledOrder(order)
    }

    /** Known sections in order (with their settings bags), then the [retained] ids. */
    fun toPrefs(): List<HomeSectionPref> =
        sections.map { HomeSectionPref(id = it.section.id, enabled = it.enabled, config = it.config()) } + retained

    /** Same order, visibility and rows (and settings bags). */
    fun sameSectionsAs(other: HomeLayout): Boolean = sections == other.sections

    /** The catalog arrangement — rows back to their defaults too — keeping [retained]. */
    fun reset(): HomeLayout = if (isDefault) this else Default.copy(retained = retained)

    /** [reset] to this width's default: [WideDefault] in two columns. */
    fun reset(twoColumn: Boolean): HomeLayout = when {
        !twoColumn -> reset()
        isWideDefault -> this
        else -> WideDefault.copy(retained = retained)
    }

    /** [section]'s row preset ([HomeRowPreset.Default] when it is not in this layout). */
    fun rowsOf(section: HomeSection): HomeRowPreset =
        sections.firstOrNull { it.section == section }?.rows ?: HomeRowPreset.Default

    /** [section] at [rows]. Returns `this` for a section without presets, absent, or already there. */
    fun withRows(section: HomeSection, rows: HomeRowPreset): HomeLayout {
        if (!section.takesPresets || sections.none { it.section == section && it.rows != rows }) return this
        return copy(
            sections = sections.map { state ->
                if (state.section == section) state.copy(rows = rows) else state
            },
        )
    }

    fun withEnabled(section: HomeSection, enabled: Boolean): HomeLayout {
        if (sections.none { it.section == section && it.enabled != enabled }) return this
        return copy(
            sections = sections.map { state ->
                // A hidden section leaves its pair: it comes back full width.
                if (state.section == section) state.copy(enabled = enabled, half = false) else state
            },
        ).normalizedSpans()
    }

    /**
     * Refill the enabled slots in [order]; disabled entries keep their absolute
     * index (proto.js withEnabledOrder). Returns `this` when [order] is the
     * current one or not a permutation of [enabledSections].
     */
    fun withEnabledOrder(order: List<HomeSection>, unseen: Set<HomeSection> = emptySet()): HomeLayout {
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
        ).normalizedSpans()
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
        /** Catalog defaults: every section in enum order at its [HomeSection.defaultEnabled]. */
        val Default: HomeLayout = HomeLayout(
            HomeSection.entries.map { HomeSectionState(it, it.defaultEnabled) },
        )

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

        // Ids of sections that were removed from the catalog: dropped, never retained.
        private val RetiredSectionIds = setOf("memory_teaser", "memories")

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
         */
        fun reconcile(prefs: List<HomeSectionPref>?): HomeLayout {
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
            return HomeLayout(
                sections = ordered,
                retained = retained,
                newSections = appended.filterNot { it.appendEnabled }.toSet(),
            ).normalizedSpans()
        }
    }
}

/**
 * The pairs of an enabled order given as (section, half): halves pair two by
 * two in order, a full section in between breaks the run, and a half left
 * over pairs with nothing. Left → right. Pure.
 */
internal fun homeSpanPairs(order: List<Pair<HomeSection, Boolean>>): Map<HomeSection, HomeSection> {
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
@androidx.compose.runtime.Immutable
internal sealed interface HomeFeedRow {
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
internal fun homeFeedRows(
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
