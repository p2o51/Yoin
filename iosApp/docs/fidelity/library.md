# Library: Android → iOS fidelity (Compact)

Android source of truth: `app/src/main/java/com/gpo/yoin/ui/` (paths below are relative to it). iOS: `iosApp/Yoin/Library/LibraryView.swift`, `LibraryFastScroller.swift`. 1 dp = 1 pt. Type roles come from the shared `YoinTokens.Typography` through `View.yoinRole(_:)` (GSF size + static weight, tracking, and the role's line height as the line box).

Pass of 2026-11-04. Checked on the iPhone Air simulator, light and dark, with `-screen library`, `library-albums`, `library-songs`, `library-scroller`.

## Kept iOS decisions

- The header (search pill + account) and the filter chips float in glass in `.safeAreaBar(edge: .top)`. Only the grid scrolls, as on Android.
- The fast scroller is glass.
- Liquid Glass is only for floating controls. Page content (covers, rows, the sort row) is not glass.
- Covers follow the iOS bare-cover rule (`EntityBackdrop`: continuous corner 3 + 0.045 × size, 0.5 stroke, tinted shadow). Android uses `YoinArtworkShapes.Cover` (4).
- **New (owner, 2026-11-04):** the chip that is on carries the accent in the glass: `.regular.tint(secondary.opacity(0.85)).interactive()` with `onSecondary` text. Library uses the **secondary** role. This replaces the accent text on a grey `SelectionPill`. Android fills it with `secondaryContainer` / `onSecondaryContainer`.

## Header and chips

| Element | Android value (file:line) | iOS before | iOS after |
|---|---|---|---|
| App bar | `AppBarWithSearch`, 56 search bar in a 64 band (library/LibraryScreen.kt:835) | 56 pill, 4 top / 4 bottom, account 32 in 48 | Same |
| Header → chips | Chips directly under the app bar (LibraryScreen.kt:884, Column spacedBy 4 is chips → grid) | 4 VStack gap + 4 chip padding = 8 | 0. The chips' 4 vertical padding overlaps the header (spacing −4) |
| Chips → grid | spacedBy 4 (LibraryScreen.kt:884) | 4 bar padding + 4 chip padding = 8 | 4 (the chips' bottom padding) |
| Clear ✕ | 44 circle, 16 from the edge, pinned while the chips scroll; 20 icon (library/LibraryFilterRow.kt:71, 383-401, 145) | Inside the scroll row, at 8 from the edge, scrolled with the chips | Pinned outside the scroll row, 16 from the edge; chips start 8 after it |
| Chip margins | Start 16 (8 after ✕), end 16, gap 8 (LibraryFilterRow.kt:68, 121; component/ExpressiveComponents.kt:479) | Same | Same |
| Chip | min height 44, `RoundedCornerShape(18)`, padding 16 × 10 (ExpressiveComponents.kt:522-529) | 44, continuous 18, 16 × 10 | 44, **circular** 18 (`style: .circular`, as Compose's RoundedCornerShape), 16 × 10 |
| Chip label | labelLarge 14/20 w500, letterSpacing 0 (ExpressiveComponents.kt:537; theme/Type.kt) | 14 Medium off / **SemiBold on**, no line box | labelLarge both states (Medium), 20 line box |
| Chip on | secondaryContainer / onSecondaryContainer (ExpressiveComponents.kt:498-509) | Accent text + grey SelectionPill | Tinted glass, secondary at 85 %, onSecondary text (owner request) |
| Chip off | surfaceContainerHigh / onSurfaceVariant | Plain glass, adaptive `.primary` text | Same (glass legibility; unchanged) |
| Chip order | Playlists, Artists, Albums, Songs, Favorites (library/LibraryUiState.kt:117-127) | Same | Same |

## Sort row and grid

| Element | Android value (file:line) | iOS before | iOS after |
|---|---|---|---|
| Grid columns | `GridCells.Fixed(3)` on Compact (LibraryScreen.kt:206) | 3 fixed, (W − 32 − 24) / 3 | Same |
| Grid padding / gaps | contentPadding 16 / top 8; 12 across, 16 down (LibraryScreen.kt:1663-1668) | Same | Same |
| Grid bottom | 108 + nav bar (LibraryScreen.kt:162) | contentMargins bottom 24 | Unchanged (the floating bar sits in the shell's safe area on iOS) |
| Sort row | First full-width row; labelLarge onSurface + 18 sort icon onSurfaceVariant, 6 gap, 8 press bleed (offset −8), padding v10, min 48, clip 12 (library/LibrarySortRow.kt:39, 47-81) | Same, label 14 Medium without line box | labelLarge via the shared token (20 line box) |
| Cover → title | 5 (component/AlbumCard.kt:99; LibraryScreen.kt:1412) | 5 | 5 |
| Cell title | titleSmall 14/20 w500 onSurface, 1 line (AlbumCard.kt:108; LibraryScreen.kt:1415) | 14 Medium, natural line height (≈ 17.8) | titleSmall, 20 line box |
| Cell subtitle | bodySmall 12/16 onSurfaceVariant (AlbumCard.kt:115; LibraryScreen.kt:1430) | 12 Regular, natural line height | bodySmall, 16 line box |
| Artist cell | Circle, centred text, "N album(s)" (LibraryScreen.kt:1388-1438) | Same | Same |
| Playlist cell subtitle | Owner, else "Playlist" (LibraryScreen.kt:1604-1622) | "Playlist" | Same (mock has no owner) |

## Songs view

| Element | Android value (file:line) | iOS before | iOS after |
|---|---|---|---|
| Random mix header | labelLarge onSurfaceVariant "Random mix", 20 shuffle in a 48 button, padding start 16 / end 8 (LibraryScreen.kt:1867-1899) | Missing | Added (scrolls with the list; Android pins it above the list) |
| List top | contentPadding top 8 (LibraryScreen.kt:1820) | 8 | 8, under the header |
| Row | padding 16 × 10, 48 Thumb (4), 12 gap → 68 tall (component/SongListItem.kt:102-116) | Same | Same (`LibrarySongRow`, shared with Search) |
| Title | titleSmall onSurface; primary + 16 equalizer, 6 gap when current (SongListItem.kt:153-168) | 14 Medium | titleSmall, 20 line box |
| Meta line | `MetaLine`: bodySmall; artist onSurface, **12 gap, no separator**, album onSurface at 60 % (SongListItem.kt:177-184; component/MetaLine.kt:51, 121) | "artist · album" in onSurfaceVariant | MetaLine form: two groups 12 apart, onSurface / 60 % |
| Duration | 6 gap, labelLarge tabular, onSurfaceVariant (SongListItem.kt:187-192) | 14 Medium monospaced | labelLarge, monospaced digits |

## Fast scroller

| Element | Android value (file:line) | iOS before | iOS after |
|---|---|---|---|
| Track top | The grid box (under the app bar and chips + 4) plus `LibraryScrollerTopInset` 8 (library/LibraryFastScroller.kt:29, 94) | 136 below the safe area | 64 + 44 + 4 + 8 = 120 |
| Track bottom | `floatingBottomGroupContentPadding` 108 + nav bar (LibraryScreen.kt:162; LibraryFastScroller.kt:94) | 96 | 108 above the bottom safe area |

## Left unmatched

- The chip row's edge fade is a fixed trailing gradient (94 % → 100 %), not Android's scroll-aware 24 dp fade on both ends (`horizontalEdgeFadeOnScroll`).
- Unselected chips and the ✕ keep the adaptive glass label color instead of onSurfaceVariant, so they stay legible over the grid.
- The Random mix header scrolls away with the list. On Android it stays above the list.
- By You sub-chip, Favorites content, Playlists list view, reveal-on-arrival scrolling: not ported (Favorites shows the song list as a stand-in).
- The M3 `AppBarWithSearch` internals (icon inset, placeholder offset) could not be read: the material3 sources are not in the Gradle cache. iOS keeps 16 inset, 24 icon, 12 gap, bodyLarge 16 placeholder.

## Values to move into shared YoinTokens

Moved on 2026-11-04 (iosApp/docs/shared-kmp.md › iOS parity tokens): the `Library.*` values as named here, except the song row, which is `Library.ROW_PADDING_H` / `_V`, `ROW_THUMB`, `ROW_GAP`; MetaLine is `YoinTokens.MetaLine`.

| Proposed token | Value | Android source |
|---|---|---|
| `Library.CHIP_HEIGHT` | 44 | ui/library/LibraryFilterRow.kt:71; ui/component/ExpressiveComponents.kt:522 |
| `Library.CHIP_CORNER` | 18 | ui/component/ExpressiveComponents.kt:525; ui/library/LibraryFilterRow.kt:362 |
| `Library.CHIP_PADDING_H` / `_V` | 16 / 10 | ui/component/ExpressiveComponents.kt:527-528 |
| `Library.CHIP_GAP` | 8 | ui/component/ExpressiveComponents.kt:479; ui/library/LibraryFilterRow.kt:68 |
| `Library.CLEAR_ICON` | 20 | ui/library/LibraryFilterRow.kt:399 |
| `Library.GRID_GUTTER_H` / `_V` | 12 / 16 | ui/library/LibraryScreen.kt:1667-1668 |
| `Library.GRID_TOP_PADDING` | 8 | ui/library/LibraryScreen.kt:1663 |
| `Library.COVER_TO_TITLE` | 5 | ui/component/AlbumCard.kt:99; ui/library/LibraryScreen.kt:1412 |
| `Library.BOTTOM_GROUP_PADDING` | 108 | ui/library/LibraryScreen.kt:162 |
| `Library.SCROLLER_TOP_INSET` | 8 | ui/library/LibraryFastScroller.kt:29 |
| `Library.SORT_ROW_PRESS_BLEED` | 8 | ui/library/LibrarySortRow.kt:39 |
| `ListRow.PADDING_H` / `_V`, `THUMB_SIZE`, `GAP` | 16 / 10, 48, 12 | ui/component/SongListItem.kt:102-116 |
| `MetaLine.GROUP_GAP` / `INNER_GAP` / `MUTED_ALPHA` | 12 / 4 / 0.6 | ui/component/MetaLine.kt:51-52, 121 |
