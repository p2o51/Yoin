package com.gpo.yoin.ui.home

import com.gpo.yoin.data.home.HomeSectionPref

/**
 * The catalog of home-screen sections a user can show / hide / reorder.
 *
 * Each entry has a stable [id] persisted verbatim — never rename one, that id is
 * the migration key for saved layouts. [defaultEnabled] applies when a profile
 * hasn't customized, or when a newly-shipped section appears in an older saved
 * layout. The enum declaration order is the *default* render order; a persisted
 * [HomeLayout] overrides both order and visibility.
 *
 * Adding a section later is just: append a constant here + teach
 * `HomeEditorialContent` how to render it. Existing saved layouts pick it up at
 * its default via [HomeLayout.reconcile] — no migration needed. Removing one is
 * the mirror image: delete the constant and reconcile drops the saved id
 * (retired ids so far: `memory_teaser`, `memories`).
 */
enum class HomeSection(
    val id: String,
    val title: String,
    // One-line description shown in the layout editor row, not on the feed.
    val supportingText: String,
    val defaultEnabled: Boolean,
) {
    Activities(
        id = "activities",
        title = "Activities",
        supportingText = "Your recent plays and visits",
        defaultEnabled = true,
    ),
    JumpBackIn(
        id = "jump_back_in",
        title = "Jump Back In",
        supportingText = "Albums, songs, and playlists to pick back up — memories woven in",
        defaultEnabled = true,
    ),
    RecentlyAdded(
        id = "recently_added",
        title = "Recently Added",
        supportingText = "Added to your library this week",
        defaultEnabled = true,
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
 * render order; disabled entries are retained (so an editor can show them and
 * their order is preserved) — the feed simply skips `!enabled` rows.
 */
data class HomeLayout(val sections: List<HomeSectionState>) {

    val enabledSections: List<HomeSection>
        get() = sections.filter { it.enabled }.map { it.section }

    fun toPrefs(): List<HomeSectionPref> =
        sections.map { HomeSectionPref(id = it.section.id, enabled = it.enabled) }

    companion object {
        /** Catalog defaults: every section in enum order at its [HomeSection.defaultEnabled]. */
        val Default: HomeLayout = HomeLayout(
            HomeSection.entries.map { HomeSectionState(it, it.defaultEnabled) },
        )

        /**
         * Merge a persisted layout with the current catalog:
         *  - keep saved order + enabled flags for ids still in the catalog,
         *  - drop ids no longer in the catalog (removed sections),
         *  - append any catalog section the saved layout never knew about, at
         *    its default-enabled — so a newly shipped section lights up for
         *    users who customized before it existed.
         *
         * A null / empty saved layout yields [Default].
         */
        fun reconcile(prefs: List<HomeSectionPref>?): HomeLayout {
            if (prefs.isNullOrEmpty()) return Default
            val seen = LinkedHashSet<HomeSection>()
            val ordered = buildList {
                for (pref in prefs) {
                    val section = HomeSection.fromId(pref.id) ?: continue
                    if (seen.add(section)) add(HomeSectionState(section, pref.enabled))
                }
                for (section in HomeSection.entries) {
                    if (seen.add(section)) add(HomeSectionState(section, section.defaultEnabled))
                }
            }
            return HomeLayout(ordered)
        }
    }
}
