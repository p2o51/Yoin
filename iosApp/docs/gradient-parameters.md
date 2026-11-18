# Background gradient parameters (World preset), for the Android alignment

Reference only; no code changed. All numbers were read from the Swift sources
on branch `claude/project-thread-2amedi` (2026-11-04). Paths are relative to
`iosApp/`; `GL` = `Yoin/Theme/GlassLab.swift`, `NPP` =
`Yoin/NowPlaying/NowPlayingPresentation.swift`, `NPV` =
`Yoin/NowPlaying/NowPlayingView.swift`.

There is no `MeshGradient` in any page background (the only one is the logo
shape in `Yoin/Theme/Shapes.swift:43`). The "gradient" is: opaque base +
blurred blobs + a frosted-glass tint on top (NP, mini pill), or a plain
3-colour vertical `LinearGradient` (Home, Library, detail pages).

Conventions: "light / dark" alpha is the value in the source; dark tint
multiplier (below) is applied only where noted. Roles are `CoverScheme`
roles (see the last section). "scheme" = the scheme of the cover being
discussed, built in the current system light/dark mode.

## 0. Where the numbers come from

* World is `GlassLab.applyWorld` (GL:328-398). It first calls `apply(.gemini)`
  (GL:283-314) and then overrides. So every World value not set in
  `applyWorld` is Gemini's, and Gemini starts from Balanced (data in Kotlin
  `YoinTokens.Glass.balanced / balancedExtras`,
  `shared/src/commonMain/kotlin/com/gpo/yoin/shared/tokens/YoinTokens.kt:547-580`).
* World is the default preset (GL:150, 165, 170). Surface defaults and the
  per-surface extras are listed at GL:103-144.
* Dark tint multiplier (World 1.12): `GL:414-417`
  `tintOpacity(id, dark:) = min(1, tintOpacity * darkTintMultiplier)`.
  Applied by `labGlass` (GL:505) to glass/material/fill tints. It is NOT
  applied by the phone Now Playing morph's glass (`morphGlass`, NPP:445-465
  reads the raw `tintOpacity`), and not applied to the ambient blobs.

## 1. Now Playing room (the one with the neighbour colours)

Phone path: `NowPlayingMorph.surface(...)` (NPP:294-308) draws the ambient and
the glass; the page content scales inside (NPP:312-331). iPad/other path:
`NowPlayingView.base` (NPV:339-348, ambient + `labGlass("npBackground")`);
the Glass Lab "panel" path (`panel` true) instead uses `pageGradient` (NPV:333-335),
the Android gradient `[surfaceContainer, background]` top to bottom, no ambient.

### 1.1 Layers, bottom to top (`NowPlayingAmbient`, NPP:523-556)

Order is exact: ZStack order = paint order. No blend modes anywhere; plain
source-over. The whole ambient is clipped to the morph shape (NPP:299) /
`baseShape` (NPV:341).

| # | Layer | Colour role | Alpha light | Alpha dark | Blur | Geometry / stops | Animation |
|---|---|---|---|---|---|---|---|
| 1 | Base fill | `background` of this cover's scheme | 1 | 1 | none | fills the shape (`NPP:543`) | none of its own; colour follows the scheme (track change = scheme change, not springed here) |
| 2 | Previous-album blob (top edge) | `primaryContainer` of the previous album's scheme (same light/dark mode) | `neighbourStrength` = 1.0 | `darkNeighbourStrength` = 0.42 | 50 pt (literal) | `Ellipse`, height 300, width = container width, first child of a `VStack` (top), `.offset(y: -90)` (NPP:545-547) | none |
| 3 | Next-album blob (bottom edge) | `primaryContainer` of the next album's scheme | 1.0 | 0.42 | 50 pt (literal) | `Ellipse`, height 320, last child of the `VStack` (bottom), `.offset(y: +110)`; a `Spacer()` between the two blobs (NPP:548-551) | none |
| 4 | Aura (this album) | `primary` of this cover's scheme | `ambientStrength` = 0.68 | `darkAmbientStrength` = 0.88 | `ambientBlur` = 95 pt | `Circle`, `.frame(width: 360)`, centred in the ZStack (NPP:539-540). Drawn above the neighbour blobs because `auraOnTop` = 1 | none |
| 5 | Glass | `background` of this cover's scheme (tint), `Glass.regular`, non-interactive | 0.46 | 0.46 on the phone morph (raw); 0.46 x 1.12 = 0.515 on the iPad `base` path (NPV:343 -> `labGlass`) | system glass | whole room shape, corner radius 44 (`npBackground.cornerRadius`, NPV:98, NPP:407) | one tint, mixed from the pill's tint by `q` (below) |

Notes that matter when copying:

* The aura is drawn last in BOTH light and dark in World: `auraOnTop` = 1 is
  not dark-gated (NPP:541, 553). Only the strengths differ by mode. (The
  older text in glass-layers.md round 2 says "dark-only"; the code is not.)
* `darkAmbientStrength / darkNeighbourStrength` apply only when > 0 and the
  mode is dark (NPP:535-538). Light always uses 0.68 / 1.0.
* Neighbour colours: the previous and next albums are currently hard-coded to
  `albums[11]` and `albums[1]` of the (mock) library (NPP:532-533), not the
  real queue neighbours. The roles are what to copy: `primaryContainer` of the
  neighbour's scheme at the top / bottom edges, `primary` of this scheme in the
  centre.
* Neighbour blobs enter only at the vertical edges: the top blob is centred
  ~90 pt above the top edge's natural position (its centre is at -90 + 150 =
  +60 pt from the top of the VStack), the bottom blob ~110 pt below its
  natural position (centre at +110 - 160 = -50 pt from the bottom). With blur
  50 their tails reach in roughly 60-110 pt from each edge; the aura (360 pt
  circle, blur 95 pt) covers the middle.
* `Circle().frame(width: 360)` takes `min(360, available height)` as its
  diameter, so inside the pill it shrinks to the pill height (see 2).

### 1.2 Morph (pill <-> room)

| Item | Value | Ref |
|---|---|---|
| Ambient opacity vs morph progress `q` | `max(pillAmbient 0.38, smoothstep(0.04, 0.55, q))` | NPP:301 |
| Glass tint | `mix(miniPlayer tint colour, npBackground tint colour, q)` at alpha `lerp(0.48, 0.46, q)`; both are role `background`, so the colour is constant | NPP:445-465 |
| Shape | rounded rect pill rect -> full screen; radius `lerp(pill.h/2, 44, q)`; height uses `rise(q) = 1-(1-q)^2` | NPP:243-250, 117 |
| Spring (open/close) | one frame-by-frame spring on `progress`: response 0.42, damping ratio 0.78 opening, 1.0 closing (shared-motion option: Android's expressiveDefaultSpatial) | NPP:144-153 |
| Drag | 1:1 on `rise`, rubber band past ends, release hands velocity to the same spring | NPP:100-130 |
| Page content opacity | `smoothstep(0.3, 0.75, rise(q))` | NPP:326 |
| What recedes behind | shell: scale 1 - 0.06 d, black dim 0.22 d, blur 10 d (see 5) | Shells.swift:133-155 |

## 2. Mini-player pill (the folded room)

Pill = `ShellBar` mini player (`Yoin/Shell/ShellBar.swift:190-228`).

| Layer (bottom to top) | Role | Alpha light / dark | Blur | Geometry | Animation |
|---|---|---|---|---|---|
| Ambient (`NowPlayingAmbient`, same view as 1.1) | as 1.1 (background, neighbour primaryContainer, aura primary) | whole ambient x `pillAmbient` 0.38 (inner alphas as 1.1: aura 0.68 / 0.88, neighbours 1.0 / 0.42) | as 1.1 (blobs 50, aura 95) | `Color.clear.overlay { ambient }.clipShape(.capsule)`; the ambient lays out inside the pill's own frame; hidden when idle (`!model.isIdle`) (ShellBar:205-212) | none; fades into `max(0.38, ...)` through the morph |
| Progress wash | `primary` | 0.28 (`washOpacity`) both modes | none | wave amplitude 4 pt (`washAmplitude`), clipped to capsule (ShellBar:347-356) | `playing` drives the wave; progress = position/duration |
| Glass | `background`, `Glass.regular`, interactive | 0.48 light; 0.48 x 1.12 = 0.5376 dark (through `labGlass`, GL:505) | system glass | capsule | `glassEffectID("np", in: glassNS)` shared with the morph |
| Shadow | black, straight down | 0.14, radius 10, y 5 | | | |

Balanced contrast: no tint (`tintOn` false), no ambient (`pillAmbient` 0),
wash 0.25.

## 3. Home page and Library page (page gradient + cards)

Page background (`Yoin/Home/HomeView.swift:42-46`; Library identical,
`Yoin/Library/LibraryView.swift:201-205`):

| Layer | Colour roles | Alpha | Blur | Stops / geometry | Animation |
|---|---|---|---|---|---|
| Page gradient | `surfaceContainer` -> `background` -> `surfaceContainerLow` of the "accent scheme" | 1 (opaque) | none | `LinearGradient(colors:[...], .top -> .bottom)`, three colours with no explicit `location`s, so evenly spaced at 0, 0.5, 1; `ignoresSafeArea` | none on the gradient; the scheme changes with the playing album |

The accent scheme is `CoverScheme(seed: model.accent)` (HomeView.swift:14),
`accent` = violet (`Yoin.violet`) when idle, else the playing album's
`palette.tint` (= `palette.a`) (`App/AppModel.swift:236`, `Model/Models.swift:38`).
So the Home/Library page tint follows the playing album, not a wallpaper.
Same in light and dark; only the scheme's mode flips.

Under Now Playing / a sheet the shell is drawn over a black base
(`Shells.swift:95-96`: black when `recedeScale > 0`, else `Yoin.surface`).
A status-bar soft edge sits on top of the roots: `ultraThinMaterial` band,
height safe-top + 22, alpha mask stops `0 -> black, 0.45 -> black, 1 -> clear`
(Shells.swift:97-112).

Home cards (Activities, Jump Back In, Recently Added, Rediscover): the "cover
wash" cards, `homeGlassCard` (HomeView.swift:751-770), card scheme = the
card album's own `CoverScheme(album:)`.

| Layer (bottom to top) | Colour | Alpha | Blur | Geometry | World vs Balanced |
|---|---|---|---|---|---|
| Backdrop cover art | the card album's cover (`CoverArt`, seed 1), clipped to the card | `coverStrength`: World 0.5, Balanced 0.85 | `coverBlur`: World 42, Balanced 26 | `scaleEffect(1.6)` | see columns |
| Material | `.regularMaterial` | 1 | system | card shape (variant `.material`) | World/Gemini: material. Balanced: material too |
| Tint over it | `cardWash(scheme, palette)` = `surfaceContainerLow` mixed with the palette base colour (`BackdropColors(palette).base`) by `CARD_WASH_FRACTION` 0.30, perceptual (HomeView.swift:295-297, token YoinTokens.kt:321) | World 0.72 light; 0.72 x 1.12 = 0.8064 dark; Balanced 0.45 light, 0.45 dark | | card shape as passed (`Tokens.shape.CARD`, or a capsule); the surface's own 16 radius is not used | World denser and calmer |
| Shadow | none | | | | |

Card animation: pressed state is `PressScale`; no gradient animation.

## 4. Album / Artist / Playlist pages

Background (`Yoin/Album/AlbumDetailView.swift:275-281`, Artist
`Yoin/Artist/ArtistDetailView.swift:108-113`; playlist = the album page with
`playlist.asAlbum`, AlbumDetailView.swift:48):

| Layer | Colour roles | Alpha | Blur | Stops / geometry | Animation |
|---|---|---|---|---|---|
| Page gradient | stop 1: `lerp(app.surfaceContainer, s.primary, 0.18, perceptual)`; stop 2: `app.background`; stop 3: `app.surfaceContainerLow` | 1 | none | `LinearGradient`, `.top -> .bottom`, 3 colours, evenly spaced (0, 0.5, 1) | none |

`s` = the PAGE cover's scheme (`CoverScheme(album: hero)`); `app` = the PLAYING
album's scheme, or the brand violet scheme when idle
(AlbumDetailView.swift:113-117, ArtistDetailView.swift:30-34). So only stop 1's
18 % accent comes from the viewed cover; the other roles are the app's. This
mirrors Android's `ExpressivePageBackground`. Light/dark: same formula, modes
differ through the schemes.

Other cover-coloured layers on the album page (not page backgrounds):

| Layer | Detail | Ref |
|---|---|---|
| Pulled-up top band (default `detailTopBar` 0) | 28-strip cover spectrum (colours from shared Kotlin `computeAlbumSpectrum`); soft lower edge: mask stops `black.opacity(1 - ease*k)` at 9 points over `bandFade` = 28 pt, ease = smoothstep, `k = bandSoft = smooth(p, 0.7, 1)`; the list under it blurred with radius 10 x k | AlbumDetailView.swift:101-107, 845-925; `AlbumBandBlur.metal` |
| Glass bar variant (`detailTopBar` 1) | `Glass.regular` tint `s.surfaceContainer` at 0.35, bottom corners 28, opacity `smooth(p, 0.3, 0.85)` | AlbumDetailView.swift:450-462 |
| Playlist header glass | tint `s.primaryContainer` 0.6 (light) / `s.surfaceContainerHighest` 0.7 (dark) | AlbumDetailView.swift:288-298 |

`p` = the pull-up progress (0 hero, 1 track list); driven by one axis-locked
pan, settles on spring (`Yoin.spatial`: response 0.42, damping 0.78).

## 5. Sheets and depth cue

Queue, song menu, Add to playlist are system sheet glass (no custom
gradient). What is behind them recedes (`labRecede`, GL:560-577, driven by
`WorldRecede`, Shells.swift:133-155):

| Layer behind | Scale | Dim (black overlay) | Blur | When | Animation |
|---|---|---|---|---|---|
| Shell under Now Playing | 1 - 0.06 d | 0.22 d | 10 d | `d = np.progress` clamped 0...1 | none of its own: the morph's frames drive it 1:1 |
| Shell under a sheet | 1 - 0.04 d | 0.14 d | 3.5 d | `d` = 1 when a sheet is up | `Yoin.spatial` (response 0.42, damping 0.78) on the sheet flag |
| Now Playing under a sheet | 1 - 0.04 d | 0.14 d | 3.5 d | sheet while NP is expanded | `Yoin.spatial` |

Blur is applied inside the corner mask (44 pt rounded rect while d > 0.001);
the shell base turns black so the dim reads as depth.

## 6. Colour roles used (`Yoin/Theme/CoverScheme.swift`)

`CoverScheme(album:)` -> `CoverSeed.seedArgb(for:)` (Palette vibrant ->
dominant -> muted) -> shared Kotlin `ExpressiveRoles.argbRoles(seed, isDark,
contrastLevel 0.0)`, i.e. MCU `SchemeExpressive`, SPEC_2025, phone, contrast 0
(CoverScheme.swift:6-12, 63-126). Every role in the gradients above:

| Role | Used by |
|---|---|
| `primary` | NP aura, pill progress wash, album page stop-1 accent (page cover's) |
| `primaryContainer` | NP previous / next blobs (of the neighbour's scheme) |
| `background` | NP base fill, NP/pill glass tint, Home/Library stop 2, detail stop 2 |
| `surfaceContainer` | Home/Library stop 1, detail stop 1 (before the 18 % mix), iPad panel gradient top |
| `surfaceContainerLow` | Home/Library and detail stop 3, Home card wash base |
| `surfaceContainerHighest` | neutral glass floors (bar, Next/Prev, search chrome) |

## 7. World vs Balanced

| Parameter | Balanced | World | Ref |
|---|---|---|---|
| `npBackground` tint (role `background`) | 0.50 | 0.46 | YoinTokens.kt:558, GL:347 |
| `ambientStrength` (aura alpha) light | 0.55 | 0.68 | YoinTokens.kt:570, GL:348 |
| `darkAmbientStrength` | 0 (= as light, 0.55) | 0.88 | GL:370 |
| `ambientBlur` | 90 | 95 | GL:349 |
| `neighbourStrength` light | 1.0 | 1.0 | |
| `darkNeighbourStrength` | 0 (= as light, 1.0) | 0.42 | GL:371 |
| `auraOnTop` | 0 (aura under the neighbour blobs) | 1 (over them, light and dark) | GL:372, NPP:541 |
| Neighbour blob blur / geometry | 50; 300 and 320 high; offsets -90 / +110 | same (hard-coded, not tunable) | NPP:545-551 |
| `miniPlayer` | no tint | `background` 0.48 | GL:341 |
| `pillAmbient` | 0 | 0.38 | GL:343 |
| `washOpacity` | 0.25 | 0.28 | |
| `homeCards` | material, tint 0.45, role auto | material, tint 0.72, role auto | GL:305 |
| `coverBlur` / `coverStrength` | 26 / 0.85 | 42 / 0.5 | GL:310-311 |
| `darkTintMultiplier` | 1.0 | 1.12 | GL:383 |
| Recede behind NP | none | 0.06 / 0.22 / 10 | GL:387-389 |
| Recede behind sheets | none | 0.04 / 0.14 / 3.5 | GL:390-392 |
| Page gradients (Home, Library, detail) | same | same (no preset knob) | |

Gemini (World's base for everything not overridden above) differs from World
in NP only: `npBackground` 0.54, `ambientStrength` 0.75, `ambientBlur` 110,
`neighbourStrength` 0.48 (all dark values = light), `miniPlayer` neutral
floor 0.52.

## 8. Quick Android mapping checklist

1. NP/pill background = opaque `background` + two `primaryContainer` blobs
   (prev top, next bottom: 300 / 320 high ellipses, offsets -90 / +110, blur
   50) + `primary` 360 pt circle blurred 95 on top; alphas aura 0.68 light /
   0.88 dark, blobs 1.0 / 0.42.
2. Over it a frosted layer in `background` at 0.46 (pill 0.48), x1.12 dark
   if you want iOS's iPad/list path (the phone morph does not scale it).
3. Pill shows the same ambient at 0.38; room fades it up to 1 between
   morph progress 0.04 and 0.55.
4. Home/Library: `[surfaceContainer, background, surfaceContainerLow]` top to
   bottom, evenly spaced, from the playing album's scheme.
5. Album/Artist/Playlist: `[lerp(surfaceContainer, pageCover.primary, 0.18),
   background, surfaceContainerLow]` of the playing album's scheme, evenly
   spaced.
