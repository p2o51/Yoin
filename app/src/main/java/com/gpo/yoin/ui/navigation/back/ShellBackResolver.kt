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
 * child dispatcher enabled only for [ShellBackOwner.DetailPane], Now
 * Playing's through NowPlayingOverlayHost's `backYields`).
 *
 * A full-window Now Playing covers everything and always answers first; Home
 * edit mode comes next (cards are disabled while editing — the column was
 * open first, and closing it would re-tier the shell mid-edit).
 *
 * Surfaces that sit SIDE BY SIDE — the Now Playing side panel, the detail
 * column, Memories over the shell column — close in reverse order of opening
 * (owner 2026-11-12: 「返回是进入的反序」): [recency] lists them oldest first
 * as the shell saw them open, and the latest one still open answers. One
 * missing from [recency] (opened in the frame before the shell recorded it)
 * ranks oldest, ties falling back to Now Playing > detail column > Memories.
 */
fun resolveShellBackOwner(
    showNowPlaying: Boolean,
    selectedSection: YoinSection,
    homeSurface: HomeSurface,
    detailPaneOpen: Boolean = false,
    nowPlayingIsPanel: Boolean = false,
    recency: List<ShellBackOwner> = emptyList(),
): ShellBackOwner {
    if (showNowPlaying && !nowPlayingIsPanel) return ShellBackOwner.NowPlaying
    val home = selectedSection == YoinSection.HOME
    if (home && homeSurface == HomeSurface.Edit) {
        return if (showNowPlaying) ShellBackOwner.NowPlaying else ShellBackOwner.HomeEdit
    }
    val open = buildList {
        if (showNowPlaying) add(ShellBackOwner.NowPlaying)
        if (detailPaneOpen) add(ShellBackOwner.DetailPane)
        if (home && homeSurface == HomeSurface.Memories) add(ShellBackOwner.Memories)
    }
    // Stable: equal recency keeps the default order above.
    return open.maxByOrNull { recency.lastIndexOf(it) } ?: ShellBackOwner.None
}

/** The side-by-side surfaces' opening order, oldest first: [owner] opened (moves to the end) or closed (leaves). */
fun MutableList<ShellBackOwner>.noteShellSurface(owner: ShellBackOwner, open: Boolean) {
    remove(owner)
    if (open) add(owner)
}
