package com.gpo.yoin.ui.navigation.back

import com.gpo.yoin.ui.experience.HomeSurface
import com.gpo.yoin.ui.navigation.YoinSection

enum class ShellBackOwner {
    None,
    Memories,
    /** The detail column beside the shell on a Wide window (its stack pops first, then it closes). */
    DetailPane,

    /** Home's edit mode: back is a discrete Done (no haptic) in P0. */
    HomeEdit,
    NowPlaying,
}

/**
 * Who answers system back in the shell window. Handler priority is
 * REGISTRATION order (the latest-registered enabled handler wins), not tree
 * position: Now Playing's handlers register when the shell starts, while the
 * detail column's NavDisplay and Memories' BackHandler register later, when
 * they mount. So priority is expressed only by gating — each owner's handlers
 * are enabled solely while this returns that owner (the column's live on a
 * child dispatcher enabled only for [ShellBackOwner.DetailPane]).
 *
 * Order: Now Playing > Home edit > detail column > Memories. Home edit
 * outranks the column because cards are disabled while editing — the column
 * was open first, so back leaves the newer edit mode before it closes the
 * column (closing it first would re-tier the shell mid-edit).
 */
fun resolveShellBackOwner(
    showNowPlaying: Boolean,
    selectedSection: YoinSection,
    homeSurface: HomeSurface,
    detailPaneOpen: Boolean = false,
): ShellBackOwner = when {
    showNowPlaying -> ShellBackOwner.NowPlaying
    selectedSection == YoinSection.HOME && homeSurface == HomeSurface.Edit -> ShellBackOwner.HomeEdit
    detailPaneOpen -> ShellBackOwner.DetailPane
    selectedSection == YoinSection.HOME && homeSurface == HomeSurface.Memories -> {
        ShellBackOwner.Memories
    }

    else -> ShellBackOwner.None
}
