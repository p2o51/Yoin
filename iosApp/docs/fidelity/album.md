# Album: Android → iOS fidelity (Compact portrait)

Android source of truth: `app/src/main/java/com/gpo/yoin/ui/detail/` (paths below are relative to it; `component/` and `theme/` are relative to `ui/`). iOS: `iosApp/Yoin/Album/AlbumDetailView.swift` (page 1 and the pull-up), `AlbumScrapbookView.swift` (page 2), `ScoreEmblem.swift`, `AlbumSpectrum.swift`. 1 dp = 1 pt. Type roles come from the shared `YoinTokens.Typography` through `View.yoinRole(_:)` (GSF size + static weight, tracking, the role's line height as the line box). Wrapping text gets the role's line height through `YoinType.lineSpacing` (line height − `GSF_NATURAL_LINE_HEIGHT` 1.252 × size, GSF's own line; Theme/TypeRoles.swift).

Pass of 2026-11-04. Checked on the iPhone 17 Pro Max simulator, light and dark, with `-screen album`, `album-list`, `album-mid`, `album-scrapbook`, `album-scrapbook-bottom`, plus `-listScroll 600` for the liner notes.

## Kept iOS decisions

- The back button is a glass circle (Android: `FilledTonalIconButton` on `surfaceContainer`, DetailBackButton.kt:22). Over the formed spectrum it is glass tinted white @ 0.18, as Android's twin face.
- The pulled-up top bar is the Glass Lab choice (`detailTopBar`): spectrum (Android, default), Liquid Glass, or progressive blur.
- Real Yoin mark canvases (butterfly arms 0 + 2, `YoinArmsCanvas`). No Android halftone seam dots; the list and the scrapbook fade under the header with the system's soft scroll edge plus `ProgressiveEdgeBlur`.
- Page 1 ↔ 2 cover flight and parallax, the scrapbook's progressive top edge, edge-swipe-wins-over-pager (commits cad270ca5, 5a032aedc) are unchanged.
- Cover corners keep iOS continuous corners at the token radii.

## Header (both pages)

| Element | Android value (file:line) | iOS before | iOS after |
|---|---|---|---|
| Header padding | start 8, end 16, top 4, bottom 6 under the status bar (AlbumDetailScreen.kt:933) | Same | Same |
| Back → title | 40 button, 14 spacer (AlbumDetailScreen.kt:949; DetailBackButton.kt:20) | Same | Same |
| Title row height | 52 = headlineSmall 32 + bodyMedium 20 | 52 frame, text in natural line heights (30 + 17.5) | 52, text in the role line boxes (32 + 20) |
| Title | headlineSmall 24/32, cover `primary`, 1 line (AlbumDetailScreen.kt:953) | GSF 24, no line box | `yoinRole(headlineSmall)` |
| Meta line | `DetailMetaLine` bodyMedium: artist, "Album" (Kind, Medium, `primary`), year; 12 apart, no separators (AlbumDetailScreen.kt:960-966; component/MetaLine.kt) | GSF 14, `.fontWeight(.medium)` on the kind | `yoinRole(bodyMedium)`, kind `weight: .medium` |
| White twin meta | `onSurface` = white, `primary` = white @ 0.88 (AlbumDetailScreen.kt:1031-1036) | artist / year white @ 0.82 | Plain groups white (MetaLine ink = onSurface); kind white @ 0.88 |
| Page dots | 24 × 20 cells, 4 radius, 5 top, centred; active `s.secondary`, inactive `onSurfaceVariant` @ 0.3 (AlbumDetailComponents.kt:218; AlbumDetailScreen.kt:1003) | Same | Same |
| Header height | 4 + 52 + 25 + 6 = 87 | 87 | 87 |
| Stamp | 48, end inset 16, centred on the title row; title gives up 48 + 12 (AlbumSpectrumBar.kt:125-127, 196; AlbumDetailScreen.kt:899, 720) | Same | Same |

## Page 1: hero

| Element | Android value (file:line) | iOS before | iOS after |
|---|---|---|---|
| Cover | min(0.74 W, 300), centred, top = header + 28 (band 56) (AlbumSpectrumBar.kt:130-136, 213-217) | Same | Same |
| Cover corner | resting 8 → stamp 4 (AlbumDetailScreen.kt:647-648) | literal 8 → 4 | `Tokens.shape.HERO` → `Tokens.shape.COVER` |
| Hero top | slot (side + 56) + 8 (AlbumDetailScreen.kt:1124-1131, 1229) | Same | Same |
| Emblem + comment row | cover width, spacedBy 20, centred vertically (AlbumDetailScreen.kt:1385-1392) | Same | Same |
| Emblem | 64, + "X/N rated" 10 after it when an average (AlbumDetailComponents.kt:250, 271-299) | Same | Same |
| "X/N rated" | labelSmall 11/16 w500 tracking 0.1, tabular, `onSurfaceVariant` (AlbumDetailComponents.kt:306) | GSF 11 Medium, no tracking | `yoinRole(labelSmall)` tabular |
| Comment text | bodyMedium 14/20, 3 lines, `onSurface` (AlbumDetailScreen.kt:1436) | GSF 14, natural line | `yoinRole(bodyMedium)` |
| Comment date | labelMedium 12/16 w500 tracking 0.1, `onSurfaceVariant`, 4 under (AlbumDetailScreen.kt:1443-1446) | GSF 12 Medium | `yoinRole(labelMedium)` |
| No comment | 40 tonal pen, 18 icon (AlbumDetailScreen.kt:1451-1460) | Same | Same |
| Emblem → count row | 28 (AlbumDetailScreen.kt:1244) | 28 | 28 |
| Count label | `AlbumTrackCountLabel`: MetaLine labelMedium of Stats, number Medium tabular, unit **Regular** @ 60 %, 4 inside, 12 between; hours and minutes are separate groups (AlbumDetailComponents.kt:371-417; component/MetaLine.kt) | GSF 12 Medium for numbers **and** units, h and min in one group | Separate Stat groups, unit Regular @ 60 %, `yoinRole(labelMedium)` |
| Last play | "Yesterday 16:04", labelMedium Medium tabular, `onSurface`, 12 after the count (AlbumPageFlight.kt:96-108; AlbumDetailScreen.kt:1255-1258) | GSF 12 Medium, no tracking | `yoinRole(labelMedium, .medium)` tabular |
| Count → titles | 6 (AlbumDetailScreen.kt:1261) | 6 | 6 |
| Flowing titles | headlineMedium 28/36, even `onSurface`, odd `onSurfaceVariant`, glued ≤ 16 units (AlbumDetailComponents.kt:425-495; AlbumDetailScreen.kt:1278) | GSF 28, natural line ≈ 35 | `lineSpacing` to the 36 line |

## Page 1: pulled-up list

| Element | Android value (file:line) | iOS before | iOS after |
|---|---|---|---|
| List insets | page 16 + row 8; top 4; bottom 112 + nav (AlbumDetailScreen.kt:1145, 1505) | Same | Same |
| Count label | start 8, top 2, bottom 10 (AlbumDetailScreen.kt:1515-1518) | Same, units Medium | Same insets, units Regular (see above) |
| Track row | padding 8 × 10, gap 12, `ListRow` 12 clip, 56 tall without the credit (AlbumDetailComponents.kt:540-551) | Same, contentShape literal 12 | contentShape `Tokens.shape.LIST_ROW` |
| Number | labelMedium tabular, `onSurfaceVariant`, min 18, end-aligned (AlbumDetailComponents.kt:566-571) | GSF 12 Medium, no tracking | `yoinRole(labelMedium)` |
| Now playing | equalizer 16 in the number slot, title in `s.primary` (AlbumDetailComponents.kt:552-563, 586) | Same | Same |
| Title | titleMedium 16/24 w500, 1 line, weight 1 (AlbumDetailComponents.kt:584) | GSF 16 Medium + Spacer | `yoinRole(titleMedium)`, fills the row |
| Duration | labelLarge 14/20 tabular, `onSurfaceVariant` (AlbumDetailComponents.kt:612) | GSF 14 Medium | `yoinRole(labelLarge)` tabular |
| Heart | 28 circle in a 36 button; liked: `accent` fill + `onPrimary` heart; else outline @ 0.5, 1.5 ring (AlbumDetailComponents.kt:645-722) | Same | Same |
| Liner notes | `AlbumHeroMetaBlocks` default: Column spacedBy 14 {Row SpaceBetween **CenterVertically** {Last Play, emblem + "X/N rated"}, comment}; 8 / 28 insets, fade in over the last 40 % (AlbumDetailScreen.kt:1162-1180, 1293-1411) | Row aligned **top**, no rated text, **no comment** | Centred row, rated text, comment (text + date or pen) |
| Last Play block | label labelLarge underlined, 4, day bodyLarge, time bodyMedium tabular `onSurfaceVariant` 4 under (AlbumDetailScreen.kt:1311-1367) | label → day 4, day → time **8** | 4 and 4, role line boxes |

## Page 2: scrapbook

| Element | Android value (file:line) | iOS before | iOS after |
|---|---|---|---|
| Paper | 16 gutter, top 4, bottom 112 + nav, rows 28 apart; dots 16 pitch, 1.2 radius, `outlineVariant` @ 0.45 / 0.35 dark (AlbumScrapbookPage.kt:201-224, 293, 456-461, 1966) | Same | Same |
| Cover | 148, Hero 8, emblem 64 overhanging 0.3 × / 0.24 × (AlbumScrapbookPage.kt:210-218, 966-1003) | literal 8 | `Tokens.shape.HERO` |
| Cover → title | emblem 0.3 × 64 + 8; title column top 4 (AlbumScrapbookPage.kt:806-808) | Same | Same |
| Memory title | headlineSmall copy: serif SemiBold 22, line 1.25 em (27.5), tracking 0 (AlbumScrapbookPage.kt:688-695; theme/Type.kt:58) | `.system(.serif)` (did not resolve to a serif face), lineSpacing 5 | UIKit serif design (New York) 22 SemiBold, lineSpacing to 27.5 |
| Last Play | label labelLarge underlined, 4, then titleMedium tabular; 16 under the title (AlbumScrapbookPage.kt:811; AlbumPageFlight.kt:119-138) | GSF 16 Medium, no line box | `yoinRole(labelLarge)` / `yoinRole(titleMedium)` |
| Review | full width under the cover row 4 apart; `primaryContainer`, Card 16, padding 16 × 14; GSF 15 line 1.55 em; date labelMedium @ 0.6, 6 under (AlbumScrapbookPage.kt:863-865, 1008-1049) | lineSpacing **8** (≈ 26.8 line), date GSF 12 Medium | lineSpacing to 23.25, date `yoinRole(labelMedium)` |
| Album note sticky | far side, tucks 6 over the review; 72 % ≤ 280; 14.5 userTextStyle line 1.5 em; padding 18 / 18 / 14 / 18; 17 fold @ 32 % secondary (AlbumScrapbookPage.kt:866-877, 898-927, 1632-1637, 2013-2055) | lineSpacing **7** | lineSpacing to 21.75 |
| Ticket | stub 48, 7 notches, perforation 1.5 dashed 4/4 @ 0.24; min 56 mini / 64; number 16 / 18 Medium tabular @ 0.72 (AlbumScrapbookPage.kt:1229-1290, 1395, 1992-2010) | Same | Same |
| Ticket body | weight 1, padding start 13, v 10, end 12 mini / 8 full; title titleMedium SemiBold 15 / 16 line 1.25 em; meta row 3 below, fillMaxWidth, mini score at its end (AlbumScrapbookPage.kt:1305-1361) | Body hugged its text, so a mini ticket's score sat right after "plays" (≈ 28 from the edge); Spacer(8) | Body fills (`maxWidth: .infinity`), score 12 from the edge; title line 1.25 em |
| Ticket meta | `ScrapTicketMetaLine`: Stat groups in labelMedium 12, tracking 0; numbers Medium tabular, unit Regular @ 60 % (AlbumScrapbookPage.kt:1347-1356, 1507-1540) | Same weights through `.fontWeight` | Explicit GSF Medium / Regular, 16 line box |
| Score | titleMedium SemiBold 16 (22 best), tabular, ink `primary` (best: `onPrimary`); full tickets end 14 (AlbumScrapbookPage.kt:1293-1303, 1363-1365) | Same | Same |
| Cluster | ticket 66 % [200, 280]; notes cross side 78 % ≤ 330, riding 12 up; top 6 (AlbumScrapbookPage.kt:1099-1129) | Same | Same |
| Contact sheet | two tickets, gap 14, second 12 lower; one: 52 % [168, 220] (AlbumScrapbookPage.kt:1173-1226) | Same | Same |
| Notes clipping | `surfaceContainerHighest`, Card 16, rail 3 @ x 11 inset 14; padding 24 / 12 / 8; stamp column 42 labelMedium 12 Medium tabular `primary`; text userTextStyle 15 line 1.5 em; rows v 4 (AlbumScrapbookPage.kt:1543-1625, 1979-1989) | text lineSpacing **7** | lineSpacing to 22.5 |
| Receipt | ≤ 330 centred; padding 18 / 18 / 16 / 24; mono 12.5 line 1.9 em, header 11.5 Medium @ 0.7, 6 under; dotted leader; 20 circular top corners, 14 teeth 8 deep (AlbumScrapbookPage.kt:1827-1876, 2057-2082) | top corners as quad curves | circular 20 arcs (as Compose `arcTo`) |

## Unmatched / left as is

- Heart sticker on tickets: Android clips a `MaterialShapes.Heart` polygon (26) (AlbumScrapbookPage.kt:1373-1383); iOS draws the `favorite_filled` glyph at 26. Same size, offset (−9, −10) and tilt; the outline differs slightly.
- Memory title face: Android's `FontFamily.Serif` is Noto Serif; iOS uses the system serif (New York). Yoin's own motif title (`serif = false`) does not occur in the mock.
- Track row artist credit (`showArtist`, bodySmall) is not built: the mock album has no per-track artists.
- `Opening` with a review short enough to sit beside the cover (no title) is not built; the mock always has a title, so the review is full width (AlbumScrapbookPage.kt:794-795).
- Dark mode `primaryContainer` (review card) reads light: it is what `CoverScheme` gives; not changed here.

## iOS-only: the emblem is a glass lens (51, 2026-11-05)

51 asked for a glass emblem on iOS ("这个徽章也重做一个 有玻璃质感的") and picked **Lens** over Tinted and Frosted: a clear Liquid Glass disc (`glassEffect(.clear)`) with the cover colour seen through it, the solid ground at 24 %, a rim lit from the top-left and an inner highlight. The label and score stay solid; an average's open ring gets the ground under it. This supersedes "评分徽章保持实色" on iOS only; Android keeps the solid emblem. Code: `ScoreEmblem.swift` (`glassGround`, `glassLight`).
