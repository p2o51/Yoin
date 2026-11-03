# Search: Android → iOS fidelity (Compact)

Android has no separate search package. Search is the expanded M3 search surface of Library: `app/src/main/java/com/gpo/yoin/ui/library/LibraryScreen.kt` (`ExpandedFullScreenSearchBar`, `LibrarySearchFilterRow`, `SearchResultsContent` and the rows it uses) and `library/LibrarySearchFilter.kt`. Paths below are relative to `ui/`. iOS: `iosApp/Yoin/Search/SearchView.swift`. It reuses `LibrarySongRow`, `LibraryChipLabel`, `libraryChipGlass` and `yoinRole` from `LibraryView.swift`. 1 dp = 1 pt.

Pass of 2026-11-04. Checked on the iPhone Air simulator, light and dark, with `-screen search`, `search-results`, `search-catalog`, `search-albums`, `search-songs`.

## Kept iOS decisions

- iOS layout (Apple Music on iOS 26): a glass search field with a separate glass ✕ circle. The field grows out of the bar's search circle. Below it is a full-width glass segmented control for the scope. The type chips appear once there is a query. All of these float in `.safeAreaBar(edge: .top)`.
- The empty query shows a search glyph and the scope's placeholder. Android shows nothing there, because it has no recent searches.
- Covers follow the iOS bare-cover rule (see library.md).
- **New (owner, 2026-11-04):** the selected scope and the selected type chip carry the accent in the glass: `.regular.tint(primary.opacity(0.85)).interactive()` with `onPrimary` text. Search uses the **primary** role. This replaces the accent text on a grey pill. The scope highlight is a tinted glass capsule with a `glassEffectID`, inside the segmented control's `GlassEffectContainer`.

## Surface, field, filters

| Element | Android value (file:line) | iOS before | iOS after |
|---|---|---|---|
| Surface color | `ExpandedFullScreenSearchBar` with default `SearchBarDefaults.colors()` → surfaceContainerHigh (library/LibraryScreen.kt:1016-1024) | `Yoin.surface` (brand, ignores the playing scheme) | Playing scheme's surfaceContainerHigh |
| Field → filter row | `LibrarySearchFilterRow` padding vertical 8 (LibraryScreen.kt:1039) | 10 | 8 (field → scopes → chips) |
| Scope + types | One scrolling row: scope pair, 24 hairline (outlineVariant), type chips (LibraryScreen.kt:1111-1163) | Segmented control + separate chip row | Unchanged (iOS layout) |
| Scope label | labelLarge 14/20 w500 (component/ExpressiveComponents.kt:537) | 15, SemiBold on / Medium off | labelLarge both states |
| Scope selected | secondaryContainer chip (ExpressiveComponents.kt:498) | Primary text on a grey pill | Tinted glass, primary at 85 %, onPrimary text |
| Type chip | min height 44, corner 18, padding 16 × 10, gap 8 (ExpressiveComponents.kt:479, 522-529) | 34 high, capsule, padding 14 | 44, circular 18, 16 × 10, gap 8 |
| Type chip label | labelLarge | 14 Medium | labelLarge (20 line box) |
| Type chip selected | secondaryContainer / onSecondaryContainer | Primary text on SelectionPill | Tinted glass, primary at 85 %, onPrimary text |
| Type chip row edges | Full bleed; resting first chip on the results' 16 edge (LibraryScreen.kt:1121-1124) | Clipped to the 16 margins | Full bleed: chips scroll out under the screen edges, resting at 16 |
| Type order | All, Artists, Albums, Songs, Playlists (library/LibrarySearchFilter.kt:11) | Same | Same |

## Results

| Element | Android value (file:line) | iOS before | iOS after |
|---|---|---|---|
| Results top / bottom | contentPadding top 8, bottom nav bar + 24 (LibraryScreen.kt:2547, 1196) | Top 8, no bottom | Top 8, bottom 24 |
| Section order | Artists, Albums, Playlists, Songs (LibraryScreen.kt:2513-2660) | Same | Same |
| Section header | labelLarge onSurfaceVariant, padding 16 / top 18 / bottom 6 (LibraryScreen.kt:2936-2951) | 18 SemiBold ink, sections 18 apart, 10 to content | labelLarge onSurfaceVariant, top 18, bottom 6, no other gaps |
| Artist row | padding 16 × 10, 48 circle, 12, titleSmall onSurface, 8, album-count `ExpressiveMetaPill` (LibraryScreen.kt:1441-1491) | 48, 12, 16 Medium, no count | Same as Android: 68 tall, titleSmall, count pill at the end |
| Meta pill | labelMedium 12/16 w500 +0.1, onSurfaceVariant, padding 10 × 6, full round, surfaceContainerHighest at 90 % (ExpressiveComponents.kt:545-562) | — | Same |
| Albums in All | Compact: fixed 3 columns (LibraryScreen.kt:2531), rows chunked, 12 across, **0 between rows** (LibraryScreen.kt:2586-2610) | Adaptive min 104, 104 cells, 12 / 16 | (W − 32 − 24) / 3 cells, 3 per row, 12 across, rows touching |
| Album/artist cell | AlbumCard / ArtistGridItem: cover, 5, titleSmall onSurface, bodySmall onSurfaceVariant (component/AlbumCard.kt:99-120; LibraryScreen.kt:1388-1438) | 104 cover, 6 gap, 14 Rounded SemiBold title, 12 subtitle; artists without a count | cell-width cover, 5, titleSmall, bodySmall; artists show "N album(s)" |
| Artists / Albums filter | `SearchResultsGrid`: Library's grid (Fixed 3, 16 sides, 12 / 16) (LibraryScreen.kt:2754-2790) | Adaptive 104 | Fixed 3 columns, 12 / 16 |
| Song row | `SearchSongRow` → `SongListItem`: 16 × 10, 48 Thumb (4), 12, titleSmall, MetaLine (artist onSurface, 12, album at 60 %), 6, duration labelLarge tabular (LibraryScreen.kt:2680-2700; component/SongListItem.kt:102-192) | 48 bare-cover backdrop, 16 / 14 text, "artist  album", no duration, rows 4 apart + 6 padding | `LibrarySongRow`: identical to the Library Songs row, 68 tall, no gaps |
| Playlist row | 16 × 10, 48 Thumb (4), 12, titleSmall over `PlaylistMeta` (owner; "N tracks" as a Stat: number Medium tabular, unit at 60 %; duration) (LibraryScreen.kt:2071-2120, 2322-2362) | 48 backdrop, 16 / 14 text, "Playlist · 48 songs" | Thumb 4, titleSmall, "48 tracks" as a Stat (the mock has no owner or duration) |
| No results | `EmptyState`: centered Card (16) panel, surfaceContainerHighest at 72 %, bodyLarge onSurfaceVariant, padding 20 × 18, 16 margins (LibraryScreen.kt:2967-2991) | Bare text, 16 Regular, 140 from the top | Card panel as on Android, centred in 60 % of the viewport |

## Left unmatched

- The scope pair stays a segmented control above the chips. Android puts scope | hairline | types in one row and always shows that row.
- The field is 48 tall with a separate 48 ✕ circle. Android uses the M3 `InputField` (56): a back arrow leading, and a clear ✕ trailing only when there is text, or a loading indicator.
- No loading indicator, no error/Retry state, no add-to-library button or "Added to library" feedback line (`SearchSongRow`, LibraryScreen.kt:2710-2745), and no paging foot. The mock search is synchronous.
- The album rows in All touch vertically because Android's chunked rows have no spacing. This is faithful, but tight. Flag for the owner if it reads wrong.

## Values to move into shared YoinTokens

Moved on 2026-11-04 (iosApp/docs/shared-kmp.md › iOS parity tokens): `Search.*` as named here; the meta pill and empty state are `Library.META_PILL_*` / `Library.EMPTY_STATE_*`. `FILTER_DIVIDER_HEIGHT` is not added: iOS has no divider (scope and types are separate rows).

| Proposed token | Value | Android source |
|---|---|---|
| `Search.FILTER_ROW_PADDING_V` | 8 | ui/library/LibraryScreen.kt:1039 |
| `Search.FILTER_DIVIDER_HEIGHT` | 24 | ui/library/LibraryScreen.kt:1163 |
| `Search.RESULTS_BOTTOM_RESERVE` | 24 | ui/library/LibraryScreen.kt:1196 |
| `Search.SECTION_HEADER_TOP` / `_BOTTOM` | 18 / 6 | ui/library/LibraryScreen.kt:2947 |
| `MetaPill.PADDING_H` / `_V`, `ALPHA` | 10 / 6, 0.9 | ui/component/ExpressiveComponents.kt:545-562 |
| `EmptyState.PADDING_H` / `_V`, `ALPHA` | 20 / 18, 0.72 | ui/library/LibraryScreen.kt:2980-2988 |
| Chip, grid, list-row and MetaLine values | see library.md | — |
