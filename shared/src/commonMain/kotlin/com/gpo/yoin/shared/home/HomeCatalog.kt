package com.gpo.yoin.shared.home

// Home's section catalog (docs/design.md §主页 › 就地编辑; Android PR #250 / #292), moved unchanged from
// Android's ui/home/HomeSection.kt and HomeGridUnits.kt for parity phase 4. The ids are persisted verbatim
// in every platform's layout document and synced as is: never rename one.

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
 * Adding a section later is just: append a constant here + teach each platform
 * how to render it. Existing saved layouts pick it up via [HomeLayout.reconcile]
 * at [appendEnabled] (off for new sections, so they land in the edit tray badged
 * "New") — no migration needed. Removing one is the mirror image: delete the
 * constant and add its id to [RetiredIds]; any other unknown id is retained.
 *
 * [title] and [supportingText] are English fallbacks; the platforms show their own strings.
 */
enum class HomeSection(
    val id: String,
    val title: String,
    /** One line for the tray rows and screen readers (not on the feed). */
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
    // personal mixes. Hidden for services that have none, so on by default and
    // for customized layouts too.
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

    /** The unit counts this section can take in two columns. */
    val unitRange: IntRange
        get() = when (this) {
            Activities -> 1..3
            JumpBackIn -> 1..4
            LyricHighlight -> 1..2
            RecentlyAdded -> 1..2
            MemoryGrid -> 1..3
            RecentlyPlayed,
            YourPlaylists,
            MadeForYou,
            Rediscover,
            OtherProfiles,
            -> 1..2
        }

    /** The section keeps its unit count apart from its row preset, which means something else on a phone. */
    val unitsOwnKey: Boolean
        get() = this == LyricHighlight

    /** The count a section with nothing stored takes: 2 for the two big default rows' sections, 1 for the rest. */
    val defaultUnits: Int
        get() = when (this) {
            Activities,
            JumpBackIn,
            LyricHighlight,
            RecentlyAdded,
            -> 2
            else -> 1
        }

    companion object {
        private val byId: Map<String, HomeSection> = entries.associateBy { it.id }

        fun fromId(id: String): HomeSection? = byId[id]

        /** Ids of sections that were removed from the catalog: dropped on read, never retained. */
        val RetiredIds: Set<String> = setOf("memory_teaser", "memories")
    }
}

/**
 * How many rows a section shows (D1). Presets, not numbers: each screen turns
 * one into its own row count, so the choice travels with the profile across
 * devices. [L] is the composition from before presets existed — a section
 * nobody resized stores nothing and renders as it did. Two columns read
 * S / M / L / XL as 1 / 2 / 3 / 4 grid units ([units]).
 */
enum class HomeRowPreset(val key: String) {
    S("s"),
    M("m"),
    L("l"),
    XL("xl"),
    ;

    /** A stored row preset as units: S 1, M 2, L 3, XL 4. */
    val units: Int
        get() = when (this) {
            S -> 1
            M -> 2
            L -> 3
            XL -> 4
        }

    companion object {
        val Default: HomeRowPreset = L

        /** The preset stored under [key]; null for a key this build doesn't know. */
        fun fromKey(key: String?): HomeRowPreset? = entries.firstOrNull { it.key == key }
    }
}

/** [units] as the preset that stores it (counts past 4 store as XL). */
fun homeUnitsPreset(units: Int): HomeRowPreset = when {
    units <= 1 -> HomeRowPreset.S
    units == 2 -> HomeRowPreset.M
    units == 3 -> HomeRowPreset.L
    else -> HomeRowPreset.XL
}

/** Two ranges' overlap; the first range's lower end alone when they don't meet. */
fun IntRange.intersectUnits(other: IntRange): IntRange {
    val lo = maxOf(first, other.first)
    val hi = minOf(last, other.last)
    return if (lo <= hi) lo..hi else lo..lo
}

/** Two ranges share a count: a pair of sections with them can be one row height. */
fun IntRange.meetsUnits(other: IntRange): Boolean = maxOf(first, other.first) <= minOf(last, other.last)

/**
 * [left] and [right] can be the two halves of a row (owner 2026-11-23: 「左右两边高度一致」): a pair
 * shares one unit count, at most the smaller of the two maxima, so their ranges must meet.
 */
fun homeCanPair(left: HomeSection, right: HomeSection): Boolean = left.unitRange.meetsUnits(right.unitRange)

/** The range a row's handle can drag over: the section's own, or the overlap with its partner. */
fun homeRowUnitRange(left: HomeSection, right: HomeSection?): IntRange =
    if (right == null) left.unitRange else left.unitRange.intersectUnits(right.unitRange)
