# Artist: Android → iOS fidelity (Compact)

Android source of truth: `app/src/main/java/com/gpo/yoin/ui/` (paths below are relative to it). iOS: `iosApp/Yoin/Artist/ArtistDetailView.swift`. 1 dp = 1 pt. Type roles come from the shared `YoinTokens.Typography` through `View.yoinRole(_:)` (GSF size + static weight, tracking, and the role's line height as the line box). Meta lines reuse `LibraryMetaLine` where Android uses `MetaLine` with Plain groups.

Pass of 2026-11-04. Checked on the iPhone Air simulator, light and dark, with `-screen artist` and `-screen artist-bottom` (`iosApp/screenshots/iphone-artist{,-bottom}{,-dark}.png`).

## Kept iOS decisions

- Back and the follow star are glass circles (floating controls over scrolling content). Android: `FilledTonalIconButton` in surfaceContainer for back, a bare `IconButton` for the star.
- The discography chips are solid (they scroll with the page), not glass.
- The open ring is a Canvas arc and dot; it dissolves with `DotDissolve` (Theme/DotDissolve.swift + .metal).
- No halftone dots. The soft material band under the header stays as iOS's version of the tide line.
- Release covers follow the iOS bare-cover rule (`EntityBackdrop`: continuous corner 3 + 0.045 × size, 0.5 stroke, tinted shadow). Android uses `YoinArtworkShapes.Cover` (4).

## Header

| Element | Android value (file:line) | iOS before | iOS after |
|---|---|---|---|
| Row padding | start 8, end 4, top 4, bottom 6 under the status bar (detail/ArtistDetailScreen.kt:367) | Same | Same |
| Row height | max(48 buttons, title 32 + span 20) = 52, so the header is 62 (ArtistDetailScreen.kt:363-392) | 4 + 48 + 6 = 58; the hero started 4 too high | 4 + 52 + 6 = 62 (computed from the role line heights) |
| Back | 40 in a 48 touch target, 22 icon (detail/DetailBackButton.kt:37-49) | 40 glass in 48, 22 icon | Same |
| Back → title | Spacer 14 (ArtistDetailScreen.kt:372) | 14 | 14 |
| Name | headlineSmall 24/32 w400, cover-scheme primary, 1 line (ArtistDetailScreen.kt:376-382) | 24 Regular, no line box | headlineSmall, 32 line box |
| Year span | `DetailMetaLine` Plain, bodyMedium 14/20, onSurface (ArtistDetailScreen.kt:383-388) | 14 Regular, no line box | bodyMedium, 20 line box |
| Follow star | `IconButton` 40 (48 touch), **24** icon; on = cover primary, off = **onSurfaceVariant**; effects spring tint; tap pop: tween 90 ms to 1.25 (on) / 1.15 (off), spatial spring back (ArtistDetailScreen.kt:417-451) | 22 icon, off = adaptive `.primary`, no pop | 24 icon, off = onSurfaceVariant, effects-spring tint, keyframed pop (90 ms linear up, spring back) |
| Content under the header | The scroll column starts **below** the header, dissolving at its edge (seamDissolveViewport, ArtistDetailScreen.kt:100-109) | Scrolled up behind the header, text visible through the name and year | Masked at the header's bottom edge with a 16 fade; the material band stays above |

## Open ring hero (51, 2026-11-06; replaces the pinwheel)

| Element | Android value (file:line) | iOS before | iOS after |
|---|---|---|---|
| Portrait | min(0.52 × width, 216), circle | Same | Same |
| Band | full width × (portrait + 150), centred | Same | Same |
| Ring | outer edge 0.84 × portrait from the centre; of the room outside the portrait, gap 41 %, ring 59 %; round caps; opening centred at −45°, 0.9 rad wide; cover primary (detail/DetailBackdrops.kt `ArtistOpenRing`) | Pinwheel (whole mark) | Same as Android |
| Dot | in the opening, radius 0.45 × ring width, cover tertiary | — | Same |
| Turn | 0.06° per px scrolled | — | scroll pt × 3 × 0.06 |
| Leaving | dot dissolve over 220 dp of scroll (`dotDissolve`) | — | DotDissolve over 220 pt |
| Reduced motion | no turn, no dissolve | — | Same |
| Hero → first section | 32 | 32 | 32 |

## Section headers

| Element | Android value (file:line) | iOS before | iOS after |
|---|---|---|---|
| Padding | horizontal 8 inside the 16 page margin (ArtistDetailScreen.kt:541-547) | Same | Same |
| Title | `AlbumSectionLabel`: labelLarge 14/20 Medium, underlined, onSurface (detail/AlbumDetailComponents.kt:318-337) | 14 Medium underlined, no line box | labelLarge, 20 line box |
| Trailing count | labelMedium 12/16 Medium, 0.1 tracking, onSurfaceVariant (ArtistDetailScreen.kt:550-556) | 12 Medium, 0.1, no line box | labelMedium, 16 line box |

## Most Played

| Element | Android value (file:line) | iOS before | iOS after |
|---|---|---|---|
| Header → rows | 6 (ArtistDetailScreen.kt:467) | 6 | 6 |
| Row | padding 8 × 8, spacing 12, clip ListRow 12, clickable (ArtistDetailScreen.kt:482-492) | padding 8, spacing 12; not tappable | Button with PressScale, ListRow 12 hit shape; plays the track |
| Rank | labelLarge tabular, onSurfaceVariant, min width 16, end-aligned (ArtistDetailScreen.kt:494-502) | 14 Medium monospaced | labelLarge, 20 line box |
| Thumb | 44, `YoinArtworkShapes.Thumb` 4 (ArtistDetailScreen.kt:503-514) | 44, literal 4 | 44, `Tokens.shape.THUMB` |
| Title | titleMedium 16/24 Medium, onSurface, 1 line (ArtistDetailScreen.kt:521-527) | 16 Medium, no line box | titleMedium, 24 line box |
| Title → meta | spacedBy 2 (ArtistDetailScreen.kt:519) | 2 | 2 |
| Meta | MetaLine bodySmall tabular: album onSurface, 12 gap, duration at 60 % (ArtistDetailScreen.kt:432-442) | Hand-built, 12 Regular, no line box | `LibraryMetaLine` (bodySmall, 16 line box), monospaced digits |
| Text column → plays | weight(1f) then 12 (row spacing) (ArtistDetailScreen.kt:515-535) | `Spacer(minLength: 8)` | Column fills, 12 row spacing |
| Plays | labelLarge tabular, onSurfaceVariant (ArtistDetailScreen.kt:530-535) | 14 Medium monospaced | labelLarge, 20 line box |
| Row height | 8 + max(44, 24 + 2 + 16) + 8 = 60 | ≈ 58 | 60 |
| Section → Discography | bottom 32 (ArtistDetailScreen.kt:204) | 32 | 32 |

## Discography

| Element | Android value (file:line) | iOS before | iOS after |
|---|---|---|---|
| Header → chips | 12, only with ≥ 2 kinds (ArtistDetailScreen.kt:594-621) | 12 | 12 |
| Chip row | `ExpressiveSegmentedTabs`, gap 8, inside the 16 section padding with an edge fade (component/ExpressiveComponents.kt:438-480) | Inside the 16 margin: "Compilations" chopped mid-page | **Full bleed**: the row runs to the screen edges with 16 content margins (owner rule: no mid-page truncation) |
| Chip | min 44, `RoundedCornerShape(18)`, padding 16 × 10 (ExpressiveComponents.kt:520-532) | 44, **continuous** 18 | 44, circular 18 |
| Chip label | labelLarge 14/20 Medium (ExpressiveComponents.kt:534-539) | 14 Medium, no line box | labelLarge, 20 line box |
| Chip colours | on secondaryContainer / onSecondaryContainer, off surfaceContainerHigh / onSurfaceVariant, effects spring (ExpressiveComponents.kt:493-512) | Same | Same |
| Chip labels | All, Albums, Singles & EPs, Compilations (values/strings_detail.xml:122-125) | Same | Same |
| Chips → rows | 8 (ArtistDetailScreen.kt:622) | 8 | 8 |
| No releases | "No releases" / 无发行作品 under the header's 8 gap: bodyMedium 14/20, onSurfaceVariant, padding 8 × 8; the header still counts "0 releases", no chips (ArtistDetailScreen.kt:1046-1053; values/strings_detail.xml:127) | Blank under the header | Same as Android; fades in and out with the effects spring (`-artistDemo empty`) |
| Row | padding end 8, top/bottom 8, clip ListRow 12 (ArtistDetailScreen.kt:697-706) | Same, literal 12 | Same, `Tokens.shape.LIST_ROW` |
| Year column | 48 wide, start 8, labelLarge tabular onSurfaceVariant, only on a year's first row (ArtistDetailScreen.kt:708-721, 686) | 48 / 8, 14 Medium | labelLarge, 20 line box |
| Cover | 56 (ArtistDetailScreen.kt:722-733) | 56 `EntityBackdrop` | Same (iOS bare-cover rule) |
| Cover → text | 12 (ArtistDetailScreen.kt:734) | 12 | 12 |
| Title | titleMedium 16/24 Medium onSurface (ArtistDetailScreen.kt:741-747) | 16 Medium, no line box | titleMedium, 24 line box |
| Meta | MetaLine bodySmall: Kind "Latest" Medium primary, Kind type Medium onSurfaceVariant, Stat count Medium tabular onSurface + unit Regular 60 %; gaps 12, inner 4 (ArtistDetailScreen.kt:755-772; component/MetaLine.kt:96-135) | Same values, 12 font without line box | Same, bodySmall line box (16) |
| Row height | 8 + 56 + 8 = 72 | 72 | 72 |
| Show all | `TextButton` (12 × 8 padding, min 40) at start 48, labelLarge in the cover accent; after 8 rows (ArtistDetailScreen.kt:661-683) | Bare button text at 48, no padding | 12 × 8 padding, min 40, labelLarge, PressScale |
| Page bottom | 120 + nav bar (ArtistDetailScreen.kt:112) | contentMargins 24: the last release ended under the floating bar | 120 spacer at the end of the column |

## Not matched

- Page background: Android `ExpressivePageBackground` (accent + playback reaction); iOS keeps its cover-tinted gradient.
- Show all is only reachable with more than 8 releases; the mock artist has 5, so it was not seen on screen.
- Medium/Expanded and landscape heroes (`ArtistWideHero`, `ArtistLandscapeHero`) are out of this Compact pass.
- `-screen artist-bottom` now scrolls to the end with `ScrollPosition` after layout; `defaultScrollAnchor(.bottom)` settled short of it.
