# Home: layout parity (phone, Compact)

Android source of truth: `app/src/main/java/com/gpo/yoin/ui/` (paths below are relative to it). iOS: `iosApp/Yoin/Home/HomeView.swift`. 1 dp = 1 pt. The earlier element spec is in `../fidelity-home-np.md`; this pass checks it against the code again.

Kept on purpose (owner decisions, not discrepancies): bare covers with no backdrop shape, and covers grown into the room the shape took (hero 104, small 52, wide 88, Rediscover 104); album-item titles in GSF SemiBold ROND 100; Home cards as `labGlass("homeCards")` glass; no halftone or seam dots; the Liquid Glass bar.

## Line boxes

Compose gives every text line the role's `lineHeight` (no `LineHeightStyle` is set, so nothing is trimmed). iOS sets Google Sans Flex at its natural line height, (hhea ascent 1932 + descent 572) / 2000 = 1.252 × size. So every iOS line was shorter than Android's: labelSmall 13.8 instead of 16, bodyMedium 17.5 instead of 20, titleLarge 27.5 instead of 28. Stacks drifted closer together and the strip came out 41.5 tall instead of 44. The `homeType(_:)` / `homeLine(_:_:)` helpers (now in Theme/TypeRoles.swift, reading `YoinTokens.Typography.GSF_NATURAL_LINE_HEIGHT`) set a YoinTokens.Typography role: size, weight, tracking (0, or +0.1 on labelMedium/labelSmall), and the extra leading split above and below each line.

## Table

| Element | Android value + file:line | iOS before | iOS after |
|---|---|---|---|
| Section order | home/HomeSection.kt; HomeEditorialContent.kt:1000 | Header, Activities, Jump Back In, Recently Added, Rediscover | Same (no change) |
| Page padding, section gap | experience/FeedUnits.kt:158; HomeEditorialContent.kt:532 | 16 sides, 18 between sections | Same (no change) |
| Header | HomeEditorialContent.kt:1547 (top 12, min 48); HomeGreetingTitle.kt:190-191 (titleMedium / headlineMedium, Rounded Medium); HomeMemoryPill.kt:121 (24 title breathing) | Matched | Same (no change). Phone's Memories entry is the Bubble style (HomeHintVariant.kt:48), so at rest nothing sits between the title and the account button |
| Section title | HomeWidgetGrid.kt:130-136: titleLarge SemiBold, 18 sp, line 28, tracking 0 | `.gsf(18, .semibold)` in a 28 frame | `homeType(titleLarge, .semibold, size: 18)` (same box, through the shared role) |
| Activities title → bento | HomeEditorialContent.kt:1682-1686: spacedBy 10, title bottom 6 | 10 + 6 | Same (no change) |
| Bento rows | HomeRowPresets.kt:139-140, 196: row 118, gap 10, small : wide = 1 : 2 | (C − 10)/3 small, 118 row | Same (no change) |
| Hero card text | HomeEditorialContent.kt:2062-2104: gap 3; labelSmall 11/16 +0.1; titleLarge SemiBold 22/28; bodySmall 12/16; MetaLine labelSmall | Natural line heights, no tracking | Android line boxes and tracking |
| MetaLine | component/MetaLine.kt:51-52 (12 / 4 gaps), :103 (number Medium, tabular), :111 (unit **Normal** at 60 %) | Unit in Medium | Unit in Regular; Plain groups take the line's own style |
| Small card row | HomeEditorialContent.kt:2149: cover and type/time **centered** vertically | `.top` | `.center` |
| Small card type/time | HomeEditorialContent.kt:2160-2172: labelSmall 11/16 ×2 | 11 Medium, natural lines | labelSmall line boxes (+0.1 tracking) |
| Small card title | HomeEditorialContent.kt:2181-2184: titleSmall SemiBold 14, line **17**, 2 lines | 14 SemiBold, natural (17.5) | 14 SemiBold, line 17 (GSF's natural line is already 17.5, so it can't go tighter) |
| Wide card text | HomeEditorialContent.kt:2237-2259: gap 2; labelSmall; titleMedium SemiBold 16/24; bodySmall 12/16 | Natural lines | Android line boxes |
| Strip | HomeEditorialContent.kt:2290-2330: padding 16×12; bodyMedium SemiBold 14/20 + 12 + bodyMedium at 60 %; labelSmall time; clickable | 41.5 tall, not tappable | 44 tall; a button that opens the album, with PressScale |
| JBI title → grid | HomeWidgetGrid.kt:207: 16 | 16 | Same (no change) |
| JBI row widths | HomeWidgetGrid.kt:473-511: Row weights 2 : 1 with gap 12, so a 1×2 row splits (C − 12) by thirds and a three-cover row splits (C − 24) | Every row on the (C − 24)/3 grid: wide = 2col + 12 | 1×2 rows: (C − 12) × 2/3 and (C − 12)/3. Three-cover rows unchanged. The lone 1×1 moves 4 pt, as on Android |
| JBI 1×1 caption | HomeWidgetGrid.kt:807 (5 gap), :845-861 (titleMedium SemiBold 16/**22**; bodySmall 12/16) | 6 gap, natural lines | 5 gap, 16/22 and 12/16 |
| 1×2 score row | HomeWidgetGrid.kt:716-733: Row `Alignment.Bottom`, gap 6; headlineSmall Bold 24/32 tabular; basis labelSmall at 85 %, bottom 3 | `.lastTextBaseline` + bottom 3 (lifted the date 3 pt off the baseline), natural lines | `.bottom` alignment, 24/32 and 11/16 line boxes |
| 1×2 comment | HomeWidgetGrid.kt:741-752: rated = titleMedium SemiBold 17/**24**; note = bodyMedium 14/20; 3 lines | Natural lines (21.3 / 17.5) | 17/24 and 14/20 per line |
| Score / basis colour | HomeWidgetGrid.kt:710: dark theme → backdrop **accent**, light → base | Base in both modes (sank into the dark page) | `cardInk(palette, dark:)`: accent in dark mode |
| Memory mark ink | HomeAlbumMemoryMark.kt:230: dark → accent, light → base | Base in both modes | `cardInk` |
| Recently Added title → shelf | HomeEditorialContent.kt:2398: 12; shelf gap 14 | 12, 14 | Same (no change) |
| Track grid | HomeFeedDensity.kt:467-475, 387: clamp((C − 14) × 2.6/3.6, 226.8, 340); tile gap 8; row gap 14 | Same | Same (no change) |
| Track tile text | HomeEditorialContent.kt:2557-2575: bodyMedium 13 sp Medium (line 20); labelSmall 11/16 | Natural lines (16.3 / 13.8); artist without tracking | 13/20 and 11/16 line boxes, +0.1 on the artist |
| Album card | HomeEditorialContent.kt:2356 (cover **82**), :2612 (gap 6), :2615 (bodySmall Medium 12/16), :2620 (labelSmall 11/16) | Cover 100; title 14, artist 10 | Cover 82 (the size Android's whole Bun shape takes); title 12/16 (still the iOS Rounded SemiBold cut); artist labelSmall 11/16. The 2×2 grid (118) and the album card (82 + 6 + 32 = 120) are about the same height again, as Android intends (HomeEditorialContent.kt:2350) |
| Rediscover title → shelf | RediscoverSection.kt:173: 12; card gap 12 (:106) | 12 / 12 | Same (no change) |
| Rediscover card | RediscoverSection.kt:103-105: 132 tall, padding 14, 0.86 C wide | Same | Same (no change) |
| Rediscover eyebrow | RediscoverSection.kt:525-540: labelMedium 12/16 +0.1, ink | 12 Medium, natural lines, ink = base | labelMedium line boxes; ink = accent in dark mode (HomeEditorialContent.kt:2009) |
| Rediscover title / artist | RediscoverSection.kt:321-336: titleLarge SemiBold 20/**26**, top 2; bodyMedium 14/20 | Natural lines | 20/26 and 14/20 |
| Rediscover footnote | RediscoverSection.kt:564-568: MetaLine labelSmall, top 4 | Unit in Medium | Unit in Regular (MetaLine fix) |
| Score badge | RediscoverSection.kt:121-123, 630: 24 tall, padding 8, hangs 6; labelLarge **Bold** 14/20 tabular | 14 Bold | labelLarge Bold through the role (same look) |

## Not matched (and why)

- **Bottom clearance at the end of the feed.** Android pads the list bottom by 108 + nav bar (HomeEditorialContent.kt:959), so the last card clears the 68 dp bar. On iOS the shell's `.safeAreaBar(edge: .bottom)` (Shell/Shells.swift) doesn't add the bar's height to Home's scroll inset, so the last Rediscover card ends under the bar at full scroll (`-screen home-bottom`; the shot from before this pass shows it too). The fix belongs in Shell, not Home. Padding Home by the bar height would inset twice once Shell is fixed.
- **Dynamic Type.** The `.gsf` fonts scale with Dynamic Type, but the line boxes are fixed points (Android scales them with fontScale). At the default size they match.
- **Hero / small / wide / Rediscover cover sizes** stay at the iOS sizes (owner decision, see above).
- **Account avatar contents.** The "5" monogram on a Cookie6Sided stands in for the account glyph and its badge (as before).
