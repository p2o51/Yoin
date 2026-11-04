# Glass hierarchy: consulting Gemini 4 Argon (2026-11-04)

Asked by 51: how should Liquid Glass and Yoin's MD3 Expressive language combine
so the hierarchy reads better? Three rounds with Gemini 4 Argon (High) through
the local Antigravity CLI (`agy -p … --model gemini-4-argon-high --mode plan`,
read-only, with the worktree and the screenshots added via `--add-dir`).
Screenshots: iPhone 17 simulator, iOS 26.5, light + dark, Home / Now Playing /
album (list pulled up) / Library albums / Search results / Queue sheet.

The result is a new Glass Lab preset, **Gemini** (`GlassPreset.gemini` in
`Yoin/Theme/GlassLab.swift`). Balanced stays the default. Flip presets in Glass
Lab (long-press the Home title), or launch with `-preset gemini`.

## What the Gemini preset changes (vs Balanced)

| Surface / extra | Balanced | Gemini | Why |
|---|---|---|---|
| barUnselected | untinted | surfaceContainerHighest 0.52 | neutral floor: covers under the bar no longer dye Home / Search |
| barSelected | primary/secondary 0.85, no shadow | same + role-coloured shadow 0.12 / 10 / 4 | the one accent of the bar |
| miniPlayer | untinted | surfaceContainerHighest 0.52, wash 0.22 | same floor as the bar's other islands |
| transportPrimary (PAUSE, liked heart) | primary 0.55, contrast shadow 0.22 | primary 0.82, shadow 0.18 / 12 / 5, no contrast shadow | the one accent of Now Playing |
| transportSecondary, heart | untinted | surfaceContainerHighest 0.22 | neutral (barely visible, see critique) |
| ratingTrack | surfaceVariant 0.30 | surfaceVariant 0.38 | the track's housing reads |
| npPills (Queue / Devices / Write) | primary 0.55 | surfaceContainerHighest 0.35, on-surface ink | stop competing with PAUSE |
| npBackground | background 0.50 | background 0.54 | |
| ambientStrength / ambientBlur / neighbourStrength | 0.55 / 90 / 1.0 | 0.75 / 110 / 0.48 | this album's colour leads, neighbours soften |
| homeCards | material, tint 0.45, cover blur 26, strength 0.85 | material, tint 0.72, blur 42, strength 0.50 | scrolling content: calmer tonal wash, cover as gradient |
| darkTintMultiplier (new) | 1 | 1.15 | dark mode needs denser tints |
| sheetPresenterDim (new) | 0 | 0.14 | Now Playing dims under the Queue sheet |
| searchChromeTint (new) | 0 | 0.38 | Library / Search field + ✕ tinted surfaceContainerHighest |

Code hooks (all neutral in the other presets): `GlassLab.tintOpacity(_:dark:)`,
`neutralTint(_:)` (a surface-role tint gets on-surface ink: NP round buttons,
heart, pills), `denseAccent(_:dark:)` (dark-mode PAUSE / liked heart take
onPrimary / onTertiary ink on ≥ 0.75 glass instead of white), `labSearchGlass`.

## Critique: what fits 51's decisions, what doesn't

Fits:
- Glass stays on floating controls only; the Home cards (scrolling content)
  stay a material + tonal wash, just calmer.
- Bar selection keeps its tint (Home = primary, Library = secondary); the other
  islands are neutral, which actually sharpens decision 3.
- Rating track and NP pills stay glass (decision 4), the pills just lose the
  primary tint. Plain glass, no new shapes, no motion change, covers bare.

Conflicts / not adopted:
- Round 1 asked to switch the album page's top bar to glass (detailTopBar 1)
  and the Library chips to the glass track. Both are Android identity 51 kept;
  not adopted. Gemini's later fallback (a 12 pt fade under the spectrum, a
  clearer back circle over it) touches Album/*, which other agents are editing:
  noted, not done.
- Pill typography (13 pt semibold, 6 pt gaps), PAUSE's stretch anchor
  (.leading → .center) and the Search page canvas (surfaceContainerHigh →
  surface) are layout / Android-parity changes, not preset values; left out.
- 0.52 neutral floors on the bar make the islands read closer to tonal
  buttons than to clear glass. That was Gemini's round-3 fix for 0.24 being
  invisible; tune it live in Glass Lab if it feels too solid.
- Round 2's ratingFill 0.85 dulled the dark-mode rating bar (Gemini agreed in
  round 3), so Gemini keeps Balanced's 1.0.
- Dark PAUSE with white ink on 0.94 lavender glass was unreadable after round
  2; fixed with the `denseAccent` ink rule.
- transportSecondary / heart floors at 0.22 are visually indistinguishable
  from untinted (Gemini's own round-3 check). Kept for ink consistency.

---

## Transcript

### Round 1 — prompt

You are consulting (read-only — do NOT edit any files) on the visual hierarchy of "Yoin", a music app. It began as an Android app built on Material 3 Expressive (MD3E) and has been ported to native iOS 26 SwiftUI, where we use Apple's Liquid Glass. I want your concrete design advice on how Liquid Glass and Yoin's MD3 Expressive language should combine so the overall hierarchy reads better.

Files you can read:
- Screenshots (iPhone 17 simulator, current state, light + dark): the folder named `before/` — files before-{light,dark}-{home,np,album-list,library-albums,search-results,np-queue}.png. np = Now Playing (fullscreen), album-list = album detail with the track list pulled up, np-queue = the Queue sheet over Now Playing (the mock queue is empty, so the sheet only shows its header).
- Design doc (Chinese): docs/design.md (MD3E shape system, motion physics, colour system, dissolve seam, per-page specs). Repository rules: AGENTS.md.
- iOS code: iosApp/Yoin/Theme/GlassLab.swift (all glass knobs per surface and three presets: Current, Balanced = shipped default, Apple HIG), iosApp/Yoin/Theme/*.swift, iosApp/Yoin/Shell/*, iosApp/Yoin/NowPlaying/*, iosApp/Yoin/Home/*. Shared token values: shared/src/commonMain/kotlin/com/gpo/yoin/shared/tokens/YoinTokens.kt (object Glass = the Balanced preset).

Yoin design language: MD3 Expressive; colour comes from the album cover via Material Color Utilities SchemeExpressive (every screen is tinted by the current cover's scheme: primary/secondary/tertiary + containers + surface containers); Google Sans Flex typography with MD3 type roles; MD3E shape tokens (Thumb/Cover 4dp, Hero 8dp, Card/Panel continuous curves, MaterialShapes for backdrops); spring motion. No Apple SF Symbols (own icon set). Covers are bare (no borders).

Settled decisions by the product owner (do not argue these away; work within them):
1. Only controls floating over scrolling content are glass; scrolling content itself is not glass (Apple HIG).
2. Balanced preset stays the default; your ideas go into a new, switchable preset.
3. Bottom bar: the selected button is tinted glass (Home = primary, Library = secondary).
4. Now Playing: rating track and Queue/Devices/Write pills are glass.
5. Plain glass, not "gummy" (no exaggerated bulgy/jelly look). iOS-native springs.
6. Bare covers.

Question (round 1): Look at the screenshots and the GlassLab surfaces. Give me a layered hierarchy model for this app — e.g. L0 background, L1 content, L2 floating controls, L3 sheets/overlays — and for EACH layer say: glass vs MD3 tonal surface vs plain; which cover-scheme colour role; tint opacity (numbers, 0–1); shadow (opacity/radius/y) or none; corner shape. Then point at the 5 most visible hierarchy problems in these exact screenshots (name the screen and element) and say what you would change. Be specific and numeric; skip generic Liquid Glass explanations. Answer in English, under ~900 words.

### Round 1 — Gemini

### 1. Four-Layer Hierarchy Model (Proposed GlassLab Preset)

To make **MD3 Expressive (chromatic emotion)** and **iOS 26 Liquid Glass (spatial elevation)** work as one system instead of competing, assign **colour saturation to MD3 tonal surfaces** and **refraction + specular edge to elevation**:

| Layer | Role & Surfaces | Material Type | CoverScheme Role | Tint Opacity (Light / Dark) | Shadow (`opacity` / `radius` / `y`) | Corner Shape (Continuous) |
| :--- | :--- | :--- | :--- | :--- | :--- | :--- |
| **L0 · Canvas** | Root pages ([`HomeView`](iosApp/Yoin/Home/HomeView.swift#L43-L45), [`LibraryView`](iosApp/Yoin/Library/LibraryView.swift#L198-L200), [`AlbumDetailView`](iosApp/Yoin/Album/AlbumDetailView.swift#L260-L265)), [`SearchView`](iosApp/Yoin/Search/SearchView.swift#L50) | **Plain / MD3 Tonal Gradient** (no glass) | Root: `surfaceContainer` → `background` → `surfaceContainerLow`<br>Search: `surface` (not `surfaceContainerHigh`) | `1.0` (solid fill) | None (`0`) | Screen (`0`) |
| **L1 · Scrolling Content** | Home bento cards ([`homeCards`](iosApp/Yoin/Theme/GlassLab.swift#L128-L130)), bare covers, track rows, section headers | **MD3 Tonal Surface** (`variant: .none` or `.material` wash; never `.regular` glass) | Cards: `auto` ([`cardWash`](iosApp/Yoin/Home/HomeView.swift#L295-L297) = `lerp(surfaceContainerLow, backdrop.base, 0.30)`) over `surfaceContainerLow` | `0.78` Light / `0.72` Dark (`coverBlur: 44`, `coverStrength: 0.45`) | None (`0`) | Thumb `4pt`, Hero `8pt`, Card `16pt`, Strip `Capsule` |
| **L2a · Floating Chrome (Neutral)** | [`ShellBar`](iosApp/Yoin/Shell/ShellBar.swift#L19) (`barUnselected`, `miniPlayer`), Sticky Search/Filter bars, Album back circle, [`LibraryFastScroller`](iosApp/Yoin/Library/LibraryView.swift#L171) | **Liquid Glass** (`.regular`, `interactive: true`) with subtle neutral-tonal floor | `surfaceContainerHigh` (`miniPlayer` inner wash: `primary` at `washOpacity: 0.22`) | `0.28` Light / `0.34` Dark (prevents underlying text/covers from muddying glyphs) | `0.08 / 10 / 4` (neutral black) | `Capsule` / `Circle` (`height: 60pt` bar, `48pt` search) |
| **L2b · Floating Chrome (Active)** | [`ShellBar`](iosApp/Yoin/Shell/ShellBar.swift#L109) `barSelected`, Detail ` Play \| ▾ ` split button | **Tinted Liquid Glass** (`.regular`, `interactive: true`) | `Home`: `primary`<br>`Library`: `secondary`<br>Detail Split: album `primary` | `0.82` Light / `0.78` Dark (`contrastShadow: 0.14`) | `0.16 / 12 / 5` (role-tinted) | `Capsule`; Split inner `4pt`, outer `30pt` |
| **L3 · Now Playing Overlay** | [`NowPlayingMorph`](iosApp/Yoin/NowPlaying/NowPlayingPresentation.swift#L220) (`npBackground`) + controls (`transportPrimary`, `transportSecondary`, `heart`, `ratingTrack`, `npPills`) | **Glass Stage + Tiered Glass Controls** (`.regular`) | Stage (`npBackground`): `background`<br>`transportPrimary` (`PAUSE`): `primary`<br>Liked `heart`: `tertiary`<br>`transportSecondary` & unliked `heart`: `surfaceContainerHighest`<br>`ratingTrack`: `surfaceVariant` (`ratingFill` = `primary`)<br>`npPills`: `surfaceContainerHigh` | `npBackground`: `0.68` L / `0.74` D<br>`transportPrimary`: `0.82`<br>Liked `heart`: `0.75`<br>`transportSecondary`: `0.22`<br>`ratingTrack`: `0.38` (`ratingFill: 0.88`)<br>`npPills`: `0.36` | Stage: None<br>`transportPrimary`: `0.20 / 14 / 6`<br>Liked `heart`: `0.14 / 10 / 4`<br>Others: None (`0`) | Stage `44pt`; Cover `16pt` (`8pt` docked); Controls `Capsule` / `Circle`; `Devices` pill `20pt` |
| **L4 · Modal Sheets** | [`QueueSheet`](iosApp/Yoin/Playback/QueueSheet.swift#L12), [`TrackMenuSheet`](iosApp/Yoin/Library/TrackMenuSheet.swift#L67) | **System Sheet Glass + Tonal Base** (`.regularMaterial` + scheme tint) | Sheet base: `surfaceContainer`<br>Quick-action/segment rows: `surfaceContainerHigh` | Sheet tint: `0.62` L / `0.68` D<br>Row fill: `0.65` L / `0.55` D | System sheet shadow | Sheet `38pt` top; Quick row `38pt` outer / `8pt` inner; Segments `20pt` / `4pt` |

---

### 2. Five Most Visible Hierarchy Problems in the Screenshots

#### 1. `before-{light,dark}-np.png` — Bottom Utility Pills (`Queue` / `Devices` / `Write`) Compete 1:1 with `PAUSE`
- **Problem:** In [`YoinTokens.Glass.balanced`](shared/src/commonMain/kotlin/com/gpo/yoin/shared/tokens/YoinTokens.kt#L553-L558), both `transportPrimary` (`PAUSE`) and `npPills` (`Queue / Devices / Write`) use `role: .primary` at `tintOpacity: 0.55` with the exact same label colour (`onPrimary` in light, `white` in dark). Visually, the three bottom pills form a wider, heavier purple band than `PAUSE` itself, pulling focus to the bottom edge past the song title (`余韻`).
- **Fix:** Keep `npPills` as `.regular` glass (Decision #4), but demote its role in [`GlassLab`](iosApp/Yoin/Theme/GlassLab.swift#L119-L121) to `surfaceContainerHigh` at `tintOpacity: 0.36` (`shadowOpacity: 0`) with `scheme.onSurface` ink (`contrastShadow: 0`). Simultaneously raise `transportPrimary` (`PAUSE`) to `tintOpacity: 0.82`, `shadowOpacity: 0.20`, `shadowRadius: 14`, `shadowY: 6`.

#### 2. `before-{light,dark}-home.png`, `library-albums.png`, `album-list.png` — Untinted `ShellBar` Islands Bleed Content & Split Colour Identity
- **Problem:** Because `barUnselected` and `miniPlayer` have `tintOn: false, tintOpacity: 0` ([`YoinTokens.kt`](shared/src/commonMain/kotlin/com/gpo/yoin/shared/tokens/YoinTokens.kt#L549-L552)), high-contrast content directly underneath reads right through the controls:
  - In `before-light-home.png`, the text `"like"`, `"e"`, and `"Lichen"` bleed through the `Library` and `Search` buttons and `miniPlayer`.
  - In `before-light-library-albums.png`, the fourth row of album covers dyes `Home` dark blue and `Search` olive-green while `Library` (`barSelected`, `secondary` @ `0.85`) is pale green—making the bar look like three unrelated coloured buttons.
  - In `before-light-album-list.png`, track 11 (`"11 Undertow"`) and `"ain"` slice directly across the `Play | ▾` split button and `miniPlayer`.
- **Fix:** Give `barUnselected` and `miniPlayer` a subtle `surfaceContainerHigh` tint (`tintOn: true, role: .surfaceContainerHigh, tintOpacity: 0.28` Light / `0.34` Dark, `shadowOpacity: 0.08, shadowRadius: 10, shadowY: 4`) and set `washOpacity: 0.22`. This preserves glass refraction while unifying the bar's neutral islands and masking text collisions underneath.

#### 3. `before-{light,dark}-library-albums.png` & `search-results.png` — Top Chrome Material Split (Glass Search Row vs Flat Opaque Filter Chips)
- **Problem:** In [`LibraryView.header`](iosApp/Yoin/Library/LibraryView.swift#L216-L247) and [`SearchView.header`](iosApp/Yoin/Search/SearchView.swift#L63-L106), Row 1 (Search field + Close button) is glossy `.regular` Liquid Glass (stark white in light mode), while 4–8pt below it in the same sticky `.safeAreaBar(edge: .top)`, [`SegmentChipRow`](iosApp/Yoin/Library/LibraryView.swift#L501) defaults to `libraryFilterStyle = 1` (`.android`: flat, opaque `surfaceContainerHigh` and high-chroma lime-green `secondaryContainer` fills). Two adjacent sticky rows over the same scroll edge use incompatible material systems.
- **Fix:** Switch `libraryFilterStyle` default to `0` ([`.glassTrack`](iosApp/Yoin/Library/LibraryView.swift#L485), where the filter row sits inside a `.regular` glass capsule track and the active chip is a sliding tonal pill), and soften the active pill from raw `secondaryContainer` (`1.0`) to `secondaryContainer.opacity(0.75)`.

#### 4. `before-{light,dark}-np.png` — `NowPlayingAmbient` Neighbour Bleed & Over-Bright Rating Pillar
- **Problem:** In [`NowPlayingAmbient`](iosApp/Yoin/NowPlaying/NowPlayingPresentation.swift#L523-L545), `neighbourStrength: 1.0` paints the previous/next albums' `primaryContainer` (cyan-green) at the top and bottom of the screen stronger than the current purple-pink album (`ambientStrength: 0.55`), making the top header in `before-light-np.png` look detached from the cover. Meanwhile, in `before-dark-np.png`, `ratingFill: 1.0` makes the `48×196pt` lavender rating bar brighter and more dominant than `PAUSE` (`0.55`), while `ratingTrack` (`surfaceVariant` @ `0.30`) nearly vanishes around `"6.3"`.
- **Fix:** Set `npBackground` `tintOpacity: 0.68` (Light) / `0.74` (Dark), raise `ambientStrength: 0.75`, and cut `neighbourStrength: 0.28` (`ambientBlur: 110`). On `ratingTrack`, increase `tintOpacity: 0.38` and lower `ratingFill: 0.85` so the track housing reads clearly and `PAUSE` (`0.82`) wins the hierarchy.

#### 5. `before-{light,dark}-np-queue.png` & `album-list.png` — Under-Damped Queue Sheet Backdrop & Hard-Cut Album Spectrum Header
- **Problem:** In `before-{light,dark}-np-queue.png`, [`QueueSheet`](iosApp/Yoin/Playback/QueueSheet.swift#L22-L53) uses bare system sheet glass over `NowPlayingView`; the purple `PAUSE` button, `余韻` title, and bottom pills bleed through the empty sheet body as blotches while the upper cover/rating column stays undimmed. In `before-{light,dark}-album-list.png`, `detailTopBar = 0` (Spectrum) creates a flat, un-rounded rectangular block with a hard horizontal cut against the track list and clashes with the glass back button inside it.
- **Fix:** On `QueueSheet`, apply `.presentationBackground` with `.regularMaterial` + `scheme.surfaceContainer.opacity(0.62)` and dim the underlying `NowPlayingView` by `0.25` when `queueSheetOpen == true`. On `AlbumDetailView`, set `detailTopBar = 1` ([`glassBar`](iosApp/Yoin/Album/AlbumDetailView.swift#L436-L445): `.regular` glass with `28pt` bottom corners tinted with `s.surfaceContainer.opacity(0.45)`).

### Round 2 — prompt

Round 2 (still read-only; do not edit files). Your round-1 answer is in glass-gemini/agy/round1-out.md. Thanks — useful. Pushback and follow-ups from me (the implementer):

A. Constraints you missed:
 - The album page's "Spectrum" pulled-up top bar (detailTopBar = 0) is Yoin's Android signature (the cover's colour spectrum band, see docs/design.md 专辑详情 and 溶解 sections) and was chosen by the owner; I will not switch it to glass by default. Library filter chips "Android" style (libraryFilterStyle = 1) is likewise the Android look the owner kept. You may propose tweaks that keep their identity (e.g. how the chip row and the glass search field can read as ONE sticky chrome group), but not replace them.
 - The Queue sheet deliberately keeps the system iOS 26 sheet glass (same as the AirPlay Devices sheet). I can add a dim on the presenter or a light presentationBackground tint; tell me which ONE you would pick and exact numbers.
 - SurfaceStyle has a single tintOpacity (no separate light/dark). I can add at most a "dark-mode tint multiplier" extra. Is it worth it? Give one value.

B. Be concrete about the things you didn't cover:
 1. Cover colour → glass: should the floating bar's NEUTRAL islands (Search circle, unselected Home/Library, mini player) carry a faint cover-scheme tint, or stay pure system glass so only the selected button carries colour? Which role and opacity? Does tinting them weaken decision 3 (only the selected button is coloured)?
 2. Ink on glass: label/icon colour roles on (a) primary-tinted glass, (b) neutral-tinted glass, light and dark; do we need the contrast shadow (currently 0.22 on PAUSE) at all with your numbers?
 3. Shadows: one rule for the whole app (which levels get a shadow, colour = tint vs black, numbers).
 4. Typography/spacing on glass: label sizes/weights for the pills (currently 12pt medium, 0.1 tracking, 44pt min height, 13pt h-padding) and the PAUSE label; min padding from glass edge.
 5. The Home cover-colour cards (homeCards: material variant, 0.45 tint, cover blur 26, coverStrength 0.85) — they are scrolling content; are they the right level of colour vs the floating bar? Exact numbers.

C. Finally, give me the complete "Gemini" preset as a diff against Balanced, using ONLY these knobs per surface (variant regular/clear/material/none, tintOn, role from: auto primary primaryContainer secondary secondaryContainer tertiary tertiaryContainer surface surfaceContainer surfaceContainerHighest surfaceVariant background, tintOpacity, interactive, shadowOpacity, shadowRadius, shadowY, contrastShadow) for surfaces barUnselected, barSelected, miniPlayer, transportPrimary, transportSecondary, heart, ratingTrack, npPills, npBackground, homeCards; plus extras (washOpacity, washAmplitude, lyricsUnder, ratingFill, ambientStrength, ambientBlur, neighbourStrength, coverBlur, coverStrength, barSelectionPill) and at most 3 NEW extras you consider essential (name, meaning, value). Note surfaceContainerHigh is not in the role list — choose from the list. Output the preset as a compact table, then ≤150 words of rationale. English.

### Round 2 — Gemini

### A. Pushback & Constraints

#### 1. Keeping `detailTopBar = 0` (Spectrum) and `libraryFilterStyle = 1` (`.android` Chips)

- **Album Page (`detailTopBar = 0`, Spectrum):**
  Keep the 28-strip OKLab tone-locked (`L' ∈ [0.30, 0.46]`) spectrum bar as default. To fix the visual clash in `before-{light,dark}-album-list.png` without replacing the Spectrum bar:
  1. **Restore the top seam fade on the pulled-up track list:** In [`AlbumDetailView.swift`](iosApp/Yoin/Album/AlbumDetailView.swift#L633-L635), `.scrollEdgeEffectHidden(topBar != 2, for: .top)` leaves the track list with a raw unmasked clip under the Spectrum bar when `topBar == 0` (whereas Android runs `seamDissolveViewport` / a `10dp` text fade under the spectrum seam per [`docs/design.md`](docs/design.md#L426)). Add a **`12pt` top alpha-fade mask** (`LinearGradient` from `.clear` at `0pt` to `.black` at `12pt`) on `trackList`'s scroll viewport below `headerContent` once scrolled (`offset > 1pt`). This keeps the spectrum strips 100% sharp and unblurred (`要对抗模糊感`) while preventing track rows from being guillotined at the seam.
  2. **De-milk the Back button over the Spectrum:** In [`AlbumDetailView.swift`](iosApp/Yoin/Album/AlbumDetailView.swift#L275), when `white == 1` (pulled up), `.regular.tint(.white.opacity(0.18))` turns the `40×40pt` back circle into a milky grey disc over the rich spectrum strips. Switch the pulled-up state (`white > 0.5`) to **`.clear.tint(.black.opacity(0.14)).interactive()`** with `.white` ink so the underlying spectrum colour passes cleanly through the specular rim.

- **Library & Search Filter Chips (`libraryFilterStyle = 1`, `.android`):**
  Keep the solid `44pt` `ExpressiveSegmentChip` row (`18pt` corner, `surfaceContainerHigh` off / `secondaryContainer` on). Make the glass search row and the `.android` chip row read as **one sticky chrome group** via three targeted tweaks:
  1. **Tint the Search capsule & Close circle into the same tonal family:** In [`LibraryView.swift`](iosApp/Yoin/Library/LibraryView.swift#L230) and [`SearchView.swift`](iosApp/Yoin/Search/SearchView.swift#L86-L97), replace bare `.glassEffect(.regular.interactive())` with **`.glassEffect(.regular.tint(scheme.surfaceContainerHighest.opacity(0.38)).interactive())`**, and set the search icon/placeholder ink to **`scheme.onSurfaceVariant`** (instead of system `.secondary` / `Yoin.inkVariant`).
  2. **Fix `SearchView`'s canvas collision:** In [`SearchView.swift`](iosApp/Yoin/Search/SearchView.swift#L50), the page background is `scheme.surfaceContainerHigh`—the **exact same token** as unselected `.android` chips ([`LibraryView.swift`](iosApp/Yoin/Library/LibraryView.swift#L688)), causing unselected chips to vanish into the canvas. Change `SearchView`'s background to **`scheme.surface`** (or the root `surfaceContainer → background → surfaceContainerLow` gradient).
  3. **Tighten vertical coupling:** Keep `LibraryView`'s `4pt` visual gap (`VStack(spacing: -4)` compensating for the chip row's `4pt` vertical padding) and tighten [`SearchView.swift`](iosApp/Yoin/Search/SearchView.swift#L65) `VStack` spacing from `8pt` to **`4pt`** so the field and chips lock together as one header unit above the `.soft` scroll edge.

#### 2. Queue Sheet (`QueueSheet.swift`) — Pick ONE + Exact Numbers

- **Pick:** **Dim on the presenter (`NowPlayingView`)** — leave `QueueSheet` with zero `.presentationBackground` overrides so its native iOS 26 sheet glass stays 100% identical to the AirPlay Devices sheet.
- **Exact numbers:** Overlay **`Color.black.opacity(model.queueSheetOpen ? 0.26 : 0.0)`** (animated with `Yoin.effects`, `.allowsHitTesting(false)`, `.ignoresSafeArea()`) on `NowPlayingView.page`. A `0.26` presenter dim damps the purple `PAUSE` capsule, `余韻` hero title, and bottom pills enough that they stop bleeding through the `.medium` sheet as blotches, while simultaneously receding the exposed upper artwork/rating column.

#### 3. Dark-Mode Tint Multiplier Extra — Worth It? One Value

- **Yes, it is worth it.** In iOS 26 `.regular` glass, dark mode has two structural differences from light mode: (1) bright underlying album covers punch through dark translucent glass with much higher local contrast, and (2) MCU `SchemeExpressive` dark `primary` (`L* ≈ 80`) needs slightly higher density over a dark backdrop so dark `onPrimary` (`L* ≈ 20`) doesn't sink into mid-tone glass.
- **One value:** **`darkTintMultiplier = 1.15`** (applied as `min(1.0, st.tintOpacity * 1.15)` when `colorScheme == .dark`).

---

### B. Concrete Specifications (B.1 – B.5)

#### 1. Cover Colour → Glass on the Floating Bar (`ShellBar`)

- **Should neutral islands carry a faint cover-scheme tint?**
  **Yes — using a near-neutral surface container role (`surfaceContainerHighest`), NOT an accent role.**
- **Role & Opacity:**
  - `barUnselected` (unselected Home/Library, Search circle): **`role: .surfaceContainerHighest`**, **`tintOpacity: 0.24`** (`0.28` effective in dark mode via `×1.15`).
  - `miniPlayer`: **`role: .surfaceContainerHighest`**, **`tintOpacity: 0.28`** (`0.32` effective in dark mode), with `washOpacity: 0.22` (`scheme.primary`) carrying the playback progress fill inside.
  - *Implementation note:* In [`ShellBar.swift`](iosApp/Yoin/Shell/ShellBar.swift#L159-L175), `barUnselected` passes `auto: accent(s)` / `scheme.tertiary` and `miniPlayer` passes `auto: scheme.primaryContainer`. Explicitly setting `role: .surfaceContainerHighest` in the preset overrides `auto` so they never pick up chromatic `primary`/`secondary`/`tertiary`.
- **Does this weaken Decision 3 (only the selected button is coloured)?**
  **No — it enforces Decision 3.** When `barUnselected` has `tintOpacity = 0`, scrolling over the album grid (`before-light-library-albums.png`) dyes the unselected `Home` and `Search` buttons bright blue and olive-green from the covers directly underneath, making all three islands look randomly coloured. A `0.24` `surfaceContainerHighest` veil (`C ≤ 8`, near-neutral) acts as a frosted luminance floor that blocks underlying text/covers from dyeing the unselected islands, while keeping chroma near zero so **only `barSelected` (`role: .auto`, `tintOpacity: 0.85`) reads as coloured**.

#### 2. Ink on Glass & `contrastShadow`

| Glass Surface Class | Surfaces | Light Mode Ink | Dark Mode Ink |
| :--- | :--- | :--- | :--- |
| **(a) Primary / Accent-Tinted Glass** (`tintOpacity ≥ 0.78`) | `barSelected` (`0.85`), `transportPrimary` (`PAUSE`, `0.82`),Liked `heart` (`0.82`), Detail `Play \| ▾` (`0.85`) | **`scheme.onPrimary`** (or `onSecondary` / `onTertiary` per role; `L* ≈ 98`) | **`scheme.onPrimary`** (or `onSecondary` / `onTertiary`; `L* ≈ 20` on `L* ≈ 75` pastel glass) |
| **(b) Neutral-Tinted Glass — Primary Ink** (`surfaceContainerHighest` @ `0.22–0.38`) | `barUnselected` icons, `miniPlayer` title, `transportSecondary` (`Next`/`Prev`/`Repeat`), `npPills` (`Queue`/`Devices`/`Write`) | **`scheme.onSurface`** (`L* ≈ 10`) | **`scheme.onSurface`** (`L* ≈ 90`) |
| **(b) Neutral-Tinted Glass — Secondary Ink** | `miniPlayer` artist, `⌄` collapse button, unliked `heart`, progress times, `ratingTrack` (`< 80%` fill) | **`scheme.onSurfaceVariant`** (`L* ≈ 30`) | **`scheme.onSurfaceVariant`** (`L* ≈ 80`) |

- **Why `scheme.onPrimary` works in Dark Mode at `0.82` (and why it sank at `0.55`):**
  In [`ShellBar.swift`](iosApp/Yoin/Shell/ShellBar.swift#L118) and [`playSplit`](iosApp/Yoin/Shell/ShellBar.swift#L232), `barSelected` and `Play | ▾` already use `scheme.onPrimary` in dark mode at `tintOpacity = 0.85` with `contrastShadow = 0` and read crisply. `PAUSE` and `npPills` only sank in dark mode because `tintOpacity` was `0.55` (`L* 80` pastel at `55%` over dark glass drops to `L* ≈ 42`). At `0.82` (`0.94` effective in dark mode with `×1.15`), `scheme.onPrimary` works in both modes.
- **Two ink guards needed in [`NowPlayingView.swift`](iosApp/Yoin/NowPlaying/NowPlayingView.swift):**
  1. In [`roundButton`](iosApp/Yoin/NowPlaying/NowPlayingView.swift#L1050-L1053), `let tinted = st.tintOn && st.role == .auto` (currently `st.tintOn` alone switches `Next` to white `onPrimary` ink even when tinted with a neutral surface role).
  2. In [`pill`](iosApp/Yoin/NowPlaying/NowPlayingView.swift#L1147-L1149), use `scheme.onSurface` when `npPills` uses a surface role (`surfaceContainerHighest`) and `.labContrast("npPills")`.
- **Do we need `contrastShadow` (`0.22` on `PAUSE`) at all?**
  **No — set `contrastShadow = 0.0` on every surface.** With `0.82` primary tint on `PAUSE` and `scheme.onSurface` on `0.35` neutral `npPills`, native contrast is `≥ 5.5:1` in both modes, and removing the black glyph shadow eliminates the muddy halo around dark ink.

#### 3. Shadows — One Rule for the Whole App

- **The Rule:** `.glassEffect(.regular)` already renders iOS 26's native physical perimeter shadow and specular rim. Custom SwiftUI `.shadow(...)` (`tint.opacity(shadowOpacity)` in [`labGlass`](iosApp/Yoin/Theme/GlassLab.swift#L301)) is reserved **exclusively for high-chroma active CTAs (`tintOpacity ≥ 0.78`: `transportPrimary` and `barSelected`)** as a role-coloured elevation glow. Every neutral glass control, secondary button, track, stage background, and scrolling card has **`shadowOpacity = 0.0`**.
- **Shadow Colour:** **Role-tinted (`tint.opacity(shadowOpacity)`)**, never black (already how `labGlass` is wired).
- **Exact Numbers:**
  - `transportPrimary` (`PAUSE`): **`shadowOpacity = 0.18`**, **`shadowRadius = 12.0`**, **`shadowY = 5.0`**
  - `barSelected` (active tab): **`shadowOpacity = 0.12`**, **`shadowRadius = 10.0`**, **`shadowY = 4.0`**
  - All other 8 surfaces (`barUnselected`, `miniPlayer`, `transportSecondary`, `heart`, `ratingTrack`, `npPills`, `npBackground`, `homeCards`): **`shadowOpacity = 0.0`**.

#### 4. Typography & Spacing on Glass

- **Bottom Utility Pills (`Queue` / `Devices` / `Write`, [`NowPlayingView.swift`](iosApp/Yoin/NowPlaying/NowPlayingView.swift#L1123-L1155)):**
  - **Font & Weight:** **`13pt .semibold` (`wght 600`)**, **`tracking(0.1)`** (up from `12pt .medium`, whose thin stems shimmer against glass refraction).
  - **Icon & Inner Spacing:** Icon **`16pt`** (up from `15.8pt`), icon-to-text gap **`5pt`** (up from `4pt`).
  - **Geometry & Padding:** Min height **`44pt`**, horizontal padding **`14pt`** (up from `13.2pt`), inter-pill `HStack` spacing **`6pt`** (up from `2pt`).
- **`PAUSE` / `PLAY` Label ([`NowPlayingView.swift`](iosApp/Yoin/NowPlaying/NowPlayingView.swift#L950-L960)):**
  - **Font & Weight:** Keep **`19.8pt .bold` (`wght 700`)**, **`tracking(0.5)`**, height **`56pt`**, horizontal padding **`24pt`**.
  - **Alignment fix:** Change `.scaleEffect(x: 1.06, y: 1, anchor: .leading)` to **`anchor: .center`**. Anchoring the `1.06×` stretch at `.leading` pushes the trailing `"E"` `~4pt` into the right padding (`24pt` left vs `~20pt` right); `.center` keeps both glass margins symmetrical at `22pt`.
- **Minimum Padding from Glass Edge (App-Wide):**
  Because iOS 26 `.regular` glass draws a `~2pt` inner specular rim bevel:
  - **Text inside `44pt` capsules (`npPills`):** min **`14pt` horizontal**, **`10pt` vertical**.
  - **Text inside `56pt` primary capsules (`PAUSE`):** min **`22pt` horizontal**, **`16pt` vertical**.
  - **Thumb + text inside `60pt` `miniPlayer`:** **`9pt` leading/top/bottom** around the `42×42pt` cover (concentric with the `30pt` capsule radius), **`14pt` trailing** for text (up from `12pt`).
  - **Glyphs inside circular glass (`40–56pt`):** glyph size **≤ `50%` of diameter** (min **`10pt` inset** on `40pt` circles, **`14pt` inset** on `56pt` circles).

#### 5. Home Cover-Colour Cards (`homeCards`) vs Floating Bar (`ShellBar`)

- **Current issue:** At `variant: .material`, `tintOpacity: 0.45`, `coverBlur: 26`, `coverStrength: 0.85`, the scaled `CoverArt` (`×1.6`) is under-blurred (`26pt` leaves hard colour blocks visible inside `HeroCard`/`RediscoverCard`) and over-strong (`0.85`), while the MD3 `cardWash` (`lerp(surfaceContainerLow, base, 0.30)`) at `0.45` is too thin—making `homeCards` look like blotchy translucent glass that competes with `ShellBar`.
- **Exact numbers for `homeCards`:**
  - `variant`: **`material`**
  - `tintOn`: **`true`**, `role`: **`auto`** (`cardWash`)
  - `tintOpacity`: **`0.72`** (up from `0.45` — makes the calm MD3 tonal wash dominant and locks in text contrast)
  - `coverBlur`: **`42.0`** (up from `26.0` — melts cover geometry into a smooth ambient gradient)
  - `coverStrength`: **`0.50`** (down from `0.85` — subtle directional warmth, never a second competing artwork)
  - Resulting chroma hierarchy: **`barUnselected` (`0.24` neutral glass) < `homeCards` (`0.72` matte tonal wash) < `barSelected` (`0.85` saturated accent glass)**.

---

### C. Complete "Gemini" Preset (Diff Against `Balanced`)

#### 1. Surfaces (`Balanced` → `Gemini`; bold = changed)

| Surface | Variant | `tintOn` | Role | `tintOpacity` (`Bal` → `Gem`) | `interactive` | `shadowOpacity` (`Bal` → `Gem`) | `shadowRadius` (`Bal` → `Gem`) | `shadowY` (`Bal` → `Gem`) | `contrastShadow` (`Bal` → `Gem`) |
| :--- | :--- | :--- | :--- | :--- | :--- | :--- | :--- | :--- | :--- |
| `barUnselected` | `regular` | `false` → **`true`** | `auto` → **`surfaceContainerHighest`** | `0.00` → **`0.24`** | `true` | `0.00` | `12.0` | `6.0` | `0.00` |
| `barSelected` | `regular` | `true` | `auto` | `0.85` | `true` | `0.00` → **`0.12`** | `12.0` → **`10.0`** | `6.0` → **`4.0`** | `0.00` |
| `miniPlayer` | `regular` | `false` → **`true`** | `auto` → **`surfaceContainerHighest`** | `0.00` → **`0.28`** | `true` | `0.00` | `12.0` | `6.0` | `0.00` |
| `transportPrimary` | `regular` | `true` | `auto` | `0.55` → **`0.82`** | `true` | `0.18` | `12.0` | `6.0` → **`5.0`** | `0.22` → **`0.00`** |
| `transportSecondary` | `regular` | `false` → **`true`** | `auto` → **`surfaceContainerHighest`** | `0.00` → **`0.22`** | `true` | `0.00` | `12.0` | `6.0` | `0.00` |
| `heart` | `regular` | `false` → **`true`** | `auto` → **`surfaceContainerHighest`** | `0.00` → **`0.22`** | `true` | `0.00` | `12.0` | `6.0` | `0.00` |
| `ratingTrack` | `regular` | `true` | `surfaceVariant` | `0.30` → **`0.38`** | `false` | `0.00` | `12.0` | `6.0` | `0.00` |
| `npPills` | `regular` | `true` | `primary` → **`surfaceContainerHighest`** | `0.55` → **`0.35`** | `true` | `0.00` | `12.0` | `6.0` | `0.00` |
| `npBackground` | `regular` | `true` | `background` | `0.50` → **`0.68`** | `false` | `0.00` | `12.0` | `6.0` | `0.00` |
| `homeCards` | `material` | `true` | `auto` | `0.45` → **`0.72`** | `false` | `0.00` | `12.0` | `6.0` | `0.00` |

*(Corner radii stay unchanged: `npBackground = 44.0`, `homeCards = 16.0`, others `nil`.)*

#### 2. Extras & 3 Essential New Extras (`Balanced` → `Gemini`; bold = changed)

| Extra | Kind | `Balanced` | `Gemini` | Meaning / Note |
| :--- | :--- | :--- | :--- | :--- |
| `washOpacity` | Existing | `0.25` | **`0.22`** | `miniPlayer` progress wave fill opacity (`scheme.primary`) |
| `washAmplitude` | Existing | `4.0` | `4.0` | Sine wave amplitude in pt |
| `lyricsUnder` | Existing | `0.0` | **`0.22`** | Soft lyric continuation under top transport row |
| `ratingFill` | Existing | `1.0` | **`0.85`** | Rating bar `primary` fill opacity (stops overpowering `PAUSE` in dark) |
| `ambientStrength` | Existing | `0.55` | **`0.75`** | Current album `primary` blob strength in `NowPlayingAmbient` |
| `ambientBlur` | Existing | `90.0` | **`110.0`** | Blur radius of current album's ambient circle |
| `neighbourStrength` | Existing | `1.0` | **`0.28`** | Prev/next album `primaryContainer` edge bleed strength |
| `coverBlur` | Existing | `26.0` | **`42.0`** | `homeCards` background cover blur radius |
| `coverStrength` | Existing | `0.85` | **`0.50`** | `homeCards` background cover opacity |
| `barSelectionPill` | Existing | `0.55` | `0.55` | Inner pill strength when `barSelectedStyle == 0` |
| **`darkTintMultiplier`** | **NEW #1** | — | **`1.15`** | Multiplies `tintOpacity` in dark mode (`min(1.0, tintOpacity × 1.15)`) |
| **`sheetPresenterDim`** | **NEW #2** | — | **`0.26`** | Black scrim opacity on `NowPlayingView` while `QueueSheet` is presented |
| **`searchChromeTint`** | **NEW #3** | — | **`0.38`** | `surfaceContainerHighest` tint opacity on Library/Search glass search field & close circle |

#### 3. Rationale (136 words)

**Gemini** separates **spatial elevation (Liquid Glass refraction)** from **focal hierarchy (MD3 Expressive chroma)**:

1. **One primary accent per plane:** In the shell bar, only `barSelected` (`0.85`) is chromatic; in Now Playing, only `transportPrimary` (`PAUSE`, `0.82`) and liked `heart` carry full accent tint and a role-coloured glow. Demoting `npPills` from `primary` (`0.55`) to `surfaceContainerHighest` (`0.35`) and `ratingFill` to `0.85` stops the bottom edge and rating pillar from out-shouting `PAUSE`.
2. **Neutral density floors:** Giving `barUnselected` (`0.24`), `miniPlayer` (`0.28`), `transportSecondary` (`0.22`), and `heart` (`0.22`) a `surfaceContainerHighest` veil prevents scrolling text and album covers from bleeding through or falsely colouring unselected buttons.
3. **Calm stages:** Grounding `homeCards` (`0.72` wash, `42pt` blur) and damping `neighbourStrength` (`0.28`) under `npBackground` (`0.68`) keeps content legible without `contrastShadow`.

### Round 3 — prompt

Round 3 (read-only; do not edit files). I implemented your round-2 "Gemini" preset (see iosApp/Yoin/Theme/GlassLab.swift `case .gemini` in the worktree, plus darkTintMultiplier / sheetPresenterDim / searchChromeTint extras, neutral-floor ink guards in NowPlayingView, and: on dense accent glass (≥0.75 effective) PAUSE and the liked heart use onPrimary/onTertiary ink in dark mode instead of white). Your earlier answers: glass-gemini/agy/round1-out.md, round2-out.md.

Screenshots now in glass-gemini/: after/after-{light,dark}-{home,np,album-list,library-albums,search-results,np-queue}.png (Gemini preset) next to before/ (Balanced). Side-by-side sheets: compare-light.png and compare-dark.png (top row = Balanced, bottom row = Gemini; columns home, np, album-list, library-albums, search-results, np-queue).

Look at the actual pixels and tell me:
1. Where the Gemini preset clearly improved hierarchy, and where it did NOT change anything visible (be honest — some floors at 0.22–0.28 may be invisible).
2. Any regressions (contrast, muddiness, loss of Yoin's colour identity, the bar losing character, dark-mode PAUSE).
3. At most 5 final numeric adjustments to the preset (surface/extra, old → new, one-line reason). Only knobs that already exist. Keep within the owner's settled rules (glass only on floating controls; tinted bar selection; glass rating track + NP pills; plain not gummy; bare covers).
Under 450 words, English.

### Round 3 — Gemini

### 1. Where Gemini Improved Hierarchy vs. Where Nothing Visibly Changed

**Clear Improvements:**
- **Now Playing bottom edge (`after-{light,dark}-np.png`):** Demoting [`npPills`](iosApp/Yoin/Theme/GlassLab.swift#L272) (`Queue · Devices · Write`) from `primary` (`0.55`) to `surfaceContainerHighest` (`0.35`) with `onSurface` ink is the single biggest win. The three utility pills no longer form a purple band competing with `PAUSE` and `余韻`.
- **Primary CTAs (`PAUSE` & liked `heart`):** Raising [`transportPrimary`](iosApp/Yoin/Theme/GlassLab.swift#L268) to `0.82` (`0.94` effective in dark mode) gives `PAUSE` and the olive-green liked `heart` unmistakable focal weight over the rest of the transport row.
- **Home Bento Cards (`after-{light,dark}-home.png`):** Raising [`homeCards`](iosApp/Yoin/Theme/GlassLab.swift#L276) `tintOpacity` (`0.45 → 0.72`) and `coverBlur` (`26 → 42`) eliminated the harsh diagonal pink/cyan cover blotches inside `Afterglow Letters` and `Tidal Rooms`, especially in dark mode.
- **Light Search Chrome (`after-light-search-results.png`):** Unselected `.android` filter chips (`Apple Music`, `Artists`) now separate cleanly from the page canvas instead of vanishing into it.

**No Visible Change (Honest Pixel Check):**
- **`ShellBar` neutral floors (`barUnselected = 0.24`, `miniPlayer = 0.28`):** Visually ineffective over saturated content. In `after-{light,dark}-library-albums.png`, the bottom row of covers still dyes `Home` blue, `miniPlayer` reddish-brown, and `Search` olive-green. In `after-light-home.png` and `after-light-album-list.png`, `"like"`, `"Lichen"`, and `"11 Undertow"` still bleed through the bar.
- **`transportSecondary = 0.22` (`Next`, `Prev`, `Repeat`):** Indistinguishable from `0.0` (Balanced) in both modes because these circles already sit over the calm `npBackground` stage.
- **`searchChromeTint = 0.38` in Library & Dark Search:** Barely perceptible on the top search capsule; in `after-dark-search-results.png`, unselected chips still nearly disappear into the dark background.

---

### 2. Regressions

- **Light-mode Now Playing looks chalky and over-bleached (`after-light-np.png`):** Combining `npBackground = 0.68`, `neighbourStrength = 0.28`, and neutral `npPills = 0.35` stripped too much chromatic atmosphere from the lower half of the screen. Without colour underneath to refract, the neutral glass circles and bottom pills look flat and milky against a washed-out lilac sheet.
- **Light-mode Queue Sheet dim is muddy (`after-light-np-queue.png`):** `sheetPresenterDim = 0.26` casts a heavy grey-brown veil over the pastel light-mode header, cover, and `6.3` rating bar, killing Yoin's light-mode clarity.
- **Dark-mode `PAUSE` ink & optical centering (`after-dark-np.png`):** Dark `onPrimary` ink on the `0.94` lavender capsule is legible, but reads slightly dusty and lower-contrast than the crisp white `Next`/`Prev` glyphs beside it (and `scaleEffect(x: 1.06, anchor: .leading)` still pushes `"PAUSE"` noticeably closer to the right capsule edge). Meanwhile, `ratingFill = 0.85` dulled the dark-mode `6.3` bar into a lifeless grey-lilac.

---

### 3. Five Final Numeric Adjustments (`GlassLab.swift` `case .gemini`)

1. **`barUnselected.tintOpacity`: `0.24` → `0.52`** — `0.24` is invisible over album grids; `0.52` establishes a real neutral floor that blocks underlying cover colours in Library.
2. **`miniPlayer.tintOpacity`: `0.28` → `0.52`** — masks scrolling covers and track labels (`"Lichen"`, `"ain"`) from bleeding through the mini-player text.
3. **`npBackground.tintOpacity`: `0.68` → `0.54`** — lets the ambient colour field breathe through the stage so Light Mode Now Playing stops looking chalky and bleached.
4. **`neighbourStrength`: `0.28` → `0.48`** — restores enough secondary hue gradient at the top/bottom of Now Playing to give neutral glass controls rich colour to refract.
5. **`sheetPresenterDim`: `0.26` → `0.14`** — damps `PAUSE` blotches behind `QueueSheet` without turning Light Mode's upper Now Playing stage muddy grey.
