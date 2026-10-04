package com.gpo.yoin.ui.home

import com.gpo.yoin.data.home.HomeSectionPref

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
        supportingText = "Albums, songs, and playlists to pick back up — memories woven in",
        defaultEnabled = true,
        appendEnabled = true,
    ),
    RecentlyAdded(
        id = "recently_added",
        title = "Recently Added",
        supportingText = "Added to your library this week",
        defaultEnabled = true,
        appendEnabled = true,
    ),

    // Last, so existing users' default order is unchanged.
    Rediscover(
        id = "rediscover",
        title = "Rediscover",
        supportingText = "Rated high, not played in Yoin for a while",
        defaultEnabled = true,
        appendEnabled = false,
    ),
    ;

    companion object {
        private val byId: Map<String, HomeSection> = entries.associateBy { it.id }

        fun fromId(id: String): HomeSection? = byId[id]
    }
}

/** One section plus whether it currently renders, at its resolved position. */
data class HomeSectionState(
    val section: HomeSection,
    val enabled: Boolean,
)

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

    /** The catalog arrangement; ignores [retained] and [newSections]. */
    val isDefault: Boolean
        get() = sections == Default.sections

    /** Known sections in order, then the [retained] ids. */
    fun toPrefs(): List<HomeSectionPref> =
        sections.map { HomeSectionPref(id = it.section.id, enabled = it.enabled) } + retained

    fun sameSectionsAs(other: HomeLayout): Boolean = sections == other.sections

    /** The catalog arrangement, keeping [retained]. */
    fun reset(): HomeLayout = if (isDefault) this else Default.copy(retained = retained)

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
        val next = order.iterator()
        return copy(
            sections = sections.map { state ->
                if (state.enabled) HomeSectionState(next.next(), enabled = true) else state
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
         *  - keep saved order + enabled flags for ids still in the catalog
         *    (first occurrence wins),
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
                    section != null -> if (seen.add(section)) ordered += HomeSectionState(section, pref.enabled)
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
