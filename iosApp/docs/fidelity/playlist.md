# Playlist: Android → iOS fidelity (Compact portrait)

Android source of truth: `app/src/main/java/com/gpo/yoin/ui/detail/PlaylistDetailScreen.kt` (paths below are relative to `ui/detail/`). iOS: `iosApp/Yoin/Album/AlbumDetailView.swift` in playlist mode (`playlist: PlaylistInfo`), which shares the album's hero, pull-up and spectrum (see `album.md`). 1 dp = 1 pt; type roles through `View.yoinRole(_:)`.

Pass of 2026-11-04. Checked on the iPhone 17 Pro Max simulator, light and dark, with `-screen playlist`, `playlist-list`.

## Kept iOS decisions

- Back and ⋮ are glass circles; on a playlist the header glass is tinted with the playlist's own container colour (light `primaryContainer` @ 0.6 with its ink, dark `surfaceContainerHighest` @ 0.7 with `primary`). Android: a tonal back button and a plain `IconButton` ⋮.
- The ⋮ menu is a SwiftUI `Menu` (Cloud backup, Rename, Delete).
- Stacked V drawn from the real Yoin mark's arm 1.

## Header

| Element | Android value (file:line) | iOS before | iOS after |
|---|---|---|---|
| Header | Row: padding start 8, **end 4**, top 4, bottom 6; **no page-dots row** (PlaylistDetailScreen.kt:675-686, 825-826) | Album header with the dots row kept at opacity 0: **87** tall | 4 + 52 + 6 = **62**; the dots row is not laid out |
| Everything under it | Hero slot, sleeves, cover, spectrum bar height all hang off `headerHeight` (PlaylistDetailScreen.kt:261, 343-345, 377, 479) | 25 lower than Android | Follows the 62 header |
| Title | headlineSmall 24/32 (PlaylistDetailScreen.kt:698) | GSF 24, no line box | `yoinRole(headlineSmall)` |
| Meta | DetailMetaLine bodyMedium: owner, "Playlist" (Kind, Medium, `primary`) (PlaylistDetailScreen.kt:704-711) | GSF 14 | `yoinRole(bodyMedium)`, kind Medium |
| ⋮ | 48 at the 4 end padding (PlaylistDetailScreen.kt:729-741) | 40 glass in 48, 4 from the edge | Same |
| Stamp | 48, 4 before the ⋮ (end inset 16 + 40); title gives up 48 + 12 + 4 (PlaylistDetailScreen.kt:268-272, 830-831) | Same | Same |

## Hero

| Element | Android value (file:line) | iOS before | iOS after |
|---|---|---|---|
| Cover | min(0.74 W, 300), band + 56, top = header + 28; corner 8 → 4 (PlaylistDetailScreen.kt:372-391, 420-421, 1037-1042) | Same (literal radii) | Token radii (`HERO` → `COVER`) |
| Stacked V | three arm-1 layers back → front tertiary / secondary / primary; clipped above at the header only, so the front V's lip runs below the band (PlaylistDetailScreen.kt:372-393; DetailBackdrops.kt:97-116) | Canvas clipped to the band: the lip was cut flat | Canvas drawn 1.6 × band tall, masked above the header only; lip whole |
| V fade | 1 − smoothstep(0, 0.6, p) (PlaylistDetailScreen.kt:390) | Same | Same |
| Meta block | `PlaylistHeroMeta`, cover width, 8 under the slot (PlaylistDetailScreen.kt:1224-1228) | Same | Same |
| Length / Owner | labels labelLarge Medium underlined; tracks bodyLarge tabular, duration bodyMedium tabular `onSurfaceVariant`; owner bodyLarge, Public / Private bodyMedium; spacedBy 4; SpaceBetween (PlaylistDetailScreen.kt:1531-1574) | GSF 14 / 16 / 14, natural lines | `yoinRole(labelLarge / bodyLarge / bodyMedium)` |
| Description | 14 below; label, 6, bodyMedium 3 lines (PlaylistDetailScreen.kt:1576-1585) | GSF 14, natural line | bodyMedium with the 20 line (`lineSpacing`) |
| Meta → titles | 28 (PlaylistDetailScreen.kt:1229) | 28 | 28 |
| Flowing titles | headlineMedium 28/36, alternate `onSurface` / `onSurfaceVariant` (PlaylistDetailScreen.kt:1237-1240, 1598-1625) | natural line ≈ 35 | `lineSpacing` to 36 |

## Pulled-up list

| Element | Android value (file:line) | iOS before | iOS after |
|---|---|---|---|
| Count label | AlbumTrackCountLabel of the playlist's songs; start 8, top 2, bottom 10 (PlaylistDetailScreen.kt:1291-1300) | Counted the **mock album's** tracks and duration; units Medium | The playlist's entries; Stat groups, units Regular @ 60 % |
| Row | padding 8 × 8, gap 12, `ListRow` 12 (PlaylistDetailScreen.kt:1378-1386) | Same, literal 12 | `Tokens.shape.LIST_ROW` |
| Thumb | 44, `YoinArtworkShapes.Thumb` (4) (PlaylistDetailScreen.kt:1389-1400) | 44, literal 4 | `Tokens.shape.THUMB` |
| Title | titleMedium 16/24, **always `onSurface`** (PlaylistDetailScreen.kt:1408-1414) | GSF 16 Medium, tinted `primary` when playing | `yoinRole(titleMedium)`, `onSurface` |
| Credit | `PlaylistTrackCredit`: MetaLine bodySmall, artist then album muted (ink @ 60 %), 12 apart, **no separator** (PlaylistDetailScreen.kt:1341-1348) | "artist · album" in `onSurfaceVariant` | `LibraryMetaLine` (artist `onSurface`, album @ 60 %, 12 apart) |
| Title → credit | 2 (PlaylistDetailScreen.kt:1405) | 2 | 2 |
| Duration | labelLarge tabular `onSurfaceVariant` (PlaylistDetailScreen.kt:1424-1429) | GSF 14 Medium | `yoinRole(labelLarge)` |
| Liner notes | PlaylistHeroMeta again, 8 / 28 insets, fades in over the last 40 % (PlaylistDetailScreen.kt:1062-1075) | Same | Same (with the role fixes) |

## Unmatched / left as is

- Note marker after a row title (`PlaylistNoteMarker`, 12 `Note` glyph @ 0.8 accent) is not built: the mock has no song notes.
- Empty / unavailable playlist states are not built (mock data always has tracks).

## Sleeves (51, 2026-11-06; replace the stacked V)

Two of the playlist's own albums' covers fanned behind its cover (Android
PlaylistSleeves.kt): first two distinct covers that aren't the playlist's;
0.86 × side, ±0.20 × side across (less on a narrow page, 8 pt from the edge),
0.07 × side down, tilted ∓6.3°, the cover's corners; a knock-out ring of
max(3 pt, 2.5 % of the side) around the sheets in front. They open from behind
the cover on appear (spatial spring) and, on the pull-up, break up into fine
dots where they rest (DotDissolve, done by p = 0.7). Reduced motion: open from
the start, a fade.

