package com.gpo.yoin.ui.home

import androidx.compose.runtime.staticCompositionLocalOf

// TEMPORARY trial switches for the Home hint pass (Notion「充实 Home 页面信息层级
// 与视觉提示」): the owner picks from screenshots, then these collapse into
// constants and this file goes away. Production never provides the local, so
// it always renders [HomeHintVariant.Recommended]; only the debug QA harness
// (MemoriesScreenshotActivity `--es variant …`) switches it.

/** How the memory pill shows what its score rests on. */
internal enum class PillScoreMark {
    /** The pill itself is the seal: tonal wash = your album rating, solid line = track average, dashed = not yet. */
    Ladder,

    /** Every populated pill gets the wash; a small cookie mark on the cover's corner carries the kind. */
    Sticker,
}

/** What the pill shows when nothing is kept yet (no memory, no notes). */
internal enum class PillEmptyForm {
    /** A dashed "Memories ⌄" pill — the slot waiting to be filled. */
    Ghost,

    /** Today's bare chevron. */
    Chevron,
}

/** Jump Back In cover size on Medium / Tabletop / Wide panes (Compact never changes). */
internal enum class JbiCoverFit {
    /** 0.84 × column (the phone's cover-to-column rhythm), clamped 100–160dp. */
    PhoneRhythm,

    /** Same rhythm, capped at 128dp so the grid never out-shouts the bento. */
    Capped128,

    /** The fixed 100dp cover — the pre-change layout. */
    Legacy100,
}

/** Where the Memories entry lives. */
internal enum class MemoryEntryStyle {
    /**
     * The chevron in the window's safe area under the camera cutout, and a
     * speech bubble hanging from it when there is something to say (owner
     * 2026-10-04: the header pill wasn't Expressive enough).
     */
    Bubble,

    /** The 2026-10-03 header pill (cover + score + notes, chevron at its end). */
    HeaderPill,

    /** The bare header chevron. */
    HeaderChevron,
}

internal enum class HomeHintVariant(
    val entry: MemoryEntryStyle,
    val scoreMark: PillScoreMark,
    val emptyForm: PillEmptyForm,
    val coverFit: JbiCoverFit,
) {
    /** Recommended default. */
    Recommended(MemoryEntryStyle.Bubble, PillScoreMark.Ladder, PillEmptyForm.Ghost, JbiCoverFit.PhoneRhythm),

    /** The header pill, for comparison. */
    HeaderPill(MemoryEntryStyle.HeaderPill, PillScoreMark.Ladder, PillEmptyForm.Ghost, JbiCoverFit.PhoneRhythm),
    Sticker(MemoryEntryStyle.HeaderPill, PillScoreMark.Sticker, PillEmptyForm.Ghost, JbiCoverFit.PhoneRhythm),

    /** A profile with nothing kept keeps today's bare chevron. */
    QuietEmpty(MemoryEntryStyle.HeaderPill, PillScoreMark.Ladder, PillEmptyForm.Chevron, JbiCoverFit.PhoneRhythm),
    HierarchyCap(MemoryEntryStyle.HeaderPill, PillScoreMark.Ladder, PillEmptyForm.Ghost, JbiCoverFit.Capped128),

    /** Today's Home, for before/after shots. */
    Baseline(MemoryEntryStyle.HeaderChevron, PillScoreMark.Ladder, PillEmptyForm.Chevron, JbiCoverFit.Legacy100),
}

internal val LocalHomeHintVariant = staticCompositionLocalOf { HomeHintVariant.Recommended }

