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
    ;

    /**
     * Takes a row preset (D1, owner 2026-10-05: Activities and Jump Back In in
     * v1). Recently Added's grid must stay one album card tall and
     * Rediscover's phone shelf has no rows, so neither does.
     */
    val supportsRows: Boolean
        get() = this == Activities || this == JumpBackIn

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
) {
    /** The settings bag to persist: [extras] plus `rows` unless it is the default; null when empty. */
    fun config(): JsonObject? {
        val bag = LinkedHashMap(extras.orEmpty())
        if (rows != HomeRowPreset.Default) bag[RowsKey] = JsonPrimitive(rows.key)
        return bag.takeIf { it.isNotEmpty() }?.let(::JsonObject)
    }

    companion object {
        internal const val RowsKey = "rows"

        /** [pref] as a state: a readable `rows` becomes [rows], everything else stays in [extras]. */
        internal fun fromPref(section: HomeSection, pref: HomeSectionPref): HomeSectionState {
            val config = pref.config
            val rows = (config?.get(RowsKey) as? JsonPrimitive)?.contentOrNull?.let(HomeRowPreset::fromKey)
            val extras = if (rows != null && config != null) config.filterKeys { it != RowsKey } else config
            return HomeSectionState(
                section = section,
                enabled = pref.enabled,
                rows = rows ?: HomeRowPreset.Default,
                extras = extras?.takeIf { it.isNotEmpty() }?.let(::JsonObject),
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

    /** Known sections in order (with their settings bags), then the [retained] ids. */
    fun toPrefs(): List<HomeSectionPref> =
        sections.map { HomeSectionPref(id = it.section.id, enabled = it.enabled, config = it.config()) } + retained

    /** Same order, visibility and rows (and settings bags). */
    fun sameSectionsAs(other: HomeLayout): Boolean = sections == other.sections

    /** The catalog arrangement — rows back to their defaults too — keeping [retained]. */
    fun reset(): HomeLayout = if (isDefault) this else Default.copy(retained = retained)

    /** [section]'s row preset ([HomeRowPreset.Default] when it is not in this layout). */
    fun rowsOf(section: HomeSection): HomeRowPreset =
        sections.firstOrNull { it.section == section }?.rows ?: HomeRowPreset.Default

    /** [section] at [rows]. Returns `this` for a section without presets, absent, or already there. */
    fun withRows(section: HomeSection, rows: HomeRowPreset): HomeLayout {
        if (!section.supportsRows || sections.none { it.section == section && it.rows != rows }) return this
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
                if (state.section == section) state.copy(enabled = enabled) else state
            },
        )
    }

    /**
     * Refill the enabled slots in [order]; disabled entries keep their absolute
     * index (proto.js withEnabledOrder). Returns `this` when [order] is the
     * current one or not a permutation of [enabledSections].
     */
    fun withEnabledOrder(order: List<HomeSection>): HomeLayout {
        val enabled = enabledSections
        if (order == enabled) return this
        val distinct = order.toSet()
        if (order.size != enabled.size || distinct.size != order.size || distinct != enabled.toSet()) {
            return this
        }
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
        /** Catalog defaults: every section in enum order at its [HomeSection.defaultEnabled]. */
        val Default: HomeLayout = HomeLayout(
            HomeSection.entries.map { HomeSectionState(it, it.defaultEnabled) },
        )

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
            )
        }
    }
}
