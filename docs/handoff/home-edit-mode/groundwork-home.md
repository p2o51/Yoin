# Yoin home screen: current implementation map (groundwork for new components and a long-press edit mode)

Source root: `app/src/main/java/com/gpo/yoin/`. Line numbers are against the working tree as of 2026-10-03; other sessions are editing it.

Main files:
- `ui/home/HomeScreen.kt` (436 lines)
- `ui/home/HomeEditorialContent.kt` (1681)
- `ui/home/HomeWidgetGrid.kt` (467)
- `ui/home/HomeLayoutEditor.kt` (328)
- `ui/home/HomeSection.kt` (104)
- `ui/home/HomeUiState.kt` (76)
- `ui/home/HomeViewModel.kt` (756)
- `data/home/HomeLayoutStore.kt` (65)
- `data/local/HomeLayoutPreference.kt` (20)
- `data/local/HomeLayoutDao.kt` (19)

Tests:
- `app/src/test/java/com/gpo/yoin/ui/home/{HomeLayoutTest,HomeWidgetGridPackTest,HomeViewModelTest,HomeEditorialContentTest}.kt`
- `app/src/androidTest/java/com/gpo/yoin/ui/home/HomeSeamPreviewTest.kt`
- Debug screenshot harness: `app/src/debug/java/com/gpo/yoin/debug/MemoriesScreenshotActivity.kt:52`, which renders `HomeEditorialContent`.

---

## 0. Composition chain (outermost to innermost)

1. `ui/navigation/YoinNavHost.kt:817-818`: the shell section `AnimatedContent<YoinSection>`, then `ExpressivePageBackground`, then `HomeScreen(...)`.
   - `suppressBackHandling` is true when the shell's back owner is NowPlaying or DetailPane (`:825-826`).
   - `edgeContentPadding` comes in via the modifier (`:858-860`).
   - Home reads the shell column's window info through `LocalYoinWindowInfo provides columnWindowInfos.shell` (`:787`).
2. `HomeScreen.kt:61-121`:
   - Collects `uiState` and `homeLayout`.
   - Owns `isEditMode` (`rememberSaveable`, `:86`), a profile-switch closer (`:91-93`) and a `BackHandler` (`:94`).
3. `HomeContent` (`HomeScreen.kt:125-286`):
   - `ReportMotionPressure("home")` (`:150`).
   - `ProvideYoinMotionRole(Expressive)` (`:155`), so every `YoinMotion.*Spring()` below resolves to the Expressive scheme.
   - A second `ExpressivePageBackground` (`:159`), duplicating the NavHost one.
   - Loading uses a delayed spinner (180 ms, `:56`, `:165-173`). Error shows Retry/Settings (`:202-224`).
   - Content runs a one-time entrance: alpha plus a 16 dp rise (`:57`, `:164-188`, `:227-233`). It then shows `AnimatedContent(targetState = isEditMode)` (`:235-280`), which switches between `HomeLayoutEditor` and `HomeEditorialContent`.
4. `HomeEditorialContent` (`HomeEditorialContent.kt:133-499`):
   - `Box.seamTide`, then a `LazyColumn`.
   - Pull-to-Memories `NestedScrollConnection` (`:168-206`).
   - Palette warm-up gate (`:207-216`, `:256`).
   - Staged reveal `rememberStagedReveal("home-feed")` (`:264-268`).

---

## 1. Element inventory (render order)

**LazyColumn container** (`HomeEditorialContent.kt:306-342`):
- Width is capped by `yoinPageContentWidth()` (720 dp) except on Wide desktop or landscape phone (`:313`).
- Side padding is 16 / 32 (Wide desktop) / 24 (landscape phone) (`:280-284`).
- `contentPadding` top is 4 dp; bottom is 108 dp + nav bar, or 16 dp + nav bar on landscape phone (`:334-341`).
- Item spacing is 18 dp (10 dp on landscape phone) (`:342`).
- Every section item uses `animateItem(fadeIn=effectsSpring, placement=spatialSpring, fadeOut=effectsSpring)` plus `stagedBeat`.

| # | Element | file:line | Size / shape | Shows | Tap | Long-press | Data |
|---|---|---|---|---|---|---|---|
| 0 | Header item `key="home-header"` | `HomeEditorialContent.kt:346-357`, `HomeContentHeader` `:501-564` | Row, `statusBarsPadding` + 8 dp top; title `headlineLarge` (`headlineMedium` 28sp on landscape phone, `:519`) with `seamFade(fontSize)` | Fixed text "Home" (`:350`) | none | **triggers editor** (not clickable) | fixed |
| 0a | Memories chevron `IconButton` | `:533-549` | M3 IconButton, `YoinSymbols.ChevronDown`; per-frame `graphicsLayer` hint (translationY = hint × 4, alpha 0.62 + 0.38 × hint) | — | `performContextClick` → `onNavigateToMemories` | swallowed | fixed |
| 0b | Settings entry `IconButton` | `:550-561` | `YoinSymbols.Settings` | — | `performContextClick` → `onNavigateToSettings` (`navigateToSettingsFromShell`) | swallowed | fixed |
| 1 | **Activities** item `key="section-activities"` | `:364-439` | always emitted when enabled; `HomeEmptyCard` if empty (`:427-437`) | — | — | — | data-driven; order and visibility from `HomeLayout` |
| 1.t | `HomeSectionTitle("Activities")` | `:621-624`; impl `HomeWidgetGrid.kt:84-99` | titleLarge SemiBold, GoogleSansFlex 18sp, `seamFade` | — | none | triggers | fixed |
| 1.h | Hero card `ActivityHeroCard` | `:963-1038` | `Surface(YoinContainerShapes.Card` = continuous 16 dp), tinted container `lerp(surfaceContainerLow, palette.base, 0.30)` (`:937-957`), padding 14, 96 dp `WidgetBackdropArtwork`, title `titleLarge` SemiBold `MarqueeText`, label "Type · time", subtitle, optional footnote "2024 · 12 songs · 44 min" | first album or playlist only (`:400-403`; `selectHomeHeroActivity` `:1536`) | `onEntryClick` → album (with `sharedTransitionKey` = stableId) / playlist / artist | swallowed | data |
| 1.s | Small square `ActivitySmallCard` | `:1040-1114` | Card shape, padding 10, 48 dp cover, type/time stacked, title titleSmall 17sp leading, maxLines 2 | — | same | swallowed | data |
| 1.w | Wide card `ActivityWideCard` | `:1116-1180` | Card shape, padding 12, 80 dp cover, label + titleMedium marquee + subtitle | — | same | swallowed | data |
| 1.p | Pill strip `ActivityStripCard` | `:1182-1241` | `YoinShapeTokens.Full` (pill), padding 16/12, text only "**title**・artist" + "Type · time" | — | same | swallowed | data |

**Activities tiers** (`ActivityBentoTier`, `:578-590`; caps `:592-596`; tier choice `:371-378`):
- **Phone**, Compact (`:711-787`): hero full width, then a row at 118 dp × fontScale with small (weight 1) and wide (weight 2), then one strip.
- **Dense**, Medium and Tabletop (`:681-777`), 6 items:
  - Row at 124 dp × fontScale: hero ½ + `supporting[0]` wide ½.
  - Row: `[1]` small + `[2]` wide.
  - Two strips side by side.
- **Desktop**, Wide (`ActivityBentoDesktopRows`, `:802-921`), 10 items:
  - Row at 128 dp: hero 3 : wide 2 : small 1.
  - Row at 118 dp: small 1 + wide 2 + small 1 + wide 2.
  - Three strips.
- **Landscape**, phone height < 480 (`:629-668`): one row at 124 dp, hero 2 : small 1 : wide 1.4.
- Row heights are fixed and multiplied by `fontScale` (`:625-628`). `IntrinsicSize` would crash on `MarqueeText`'s SubcomposeLayout.
- Bento entries come from `buildActivityEntries` (`:1570-1603`): deduplicated, songs filtered out (`selectHomeActivities` `:1526`), capped at 6/6/10.
- Entity shape comes from `widgetShapeKindForActivity` (`:1243`). Artists render as a plain circle (`HomeWidgetGrid.kt:396-408`).

**Jump Back In** (item `key="section-widget-grid"`, `:441-463`): skipped entirely when `widgetGrid` is empty. Implemented by `HomeWidgetGridSection` (`HomeWidgetGrid.kt:110-173`).

| Element | file:line | Size / shape | Shows | Tap | Long-press | Data |
|---|---|---|---|---|---|---|
| Title "Jump Back In" | `HomeWidgetGrid.kt:136`; text passed at `HomeEditorialContent.kt:446` | `HomeSectionTitle` | — | none | triggers | fixed |
| Rows | `:137-171` | Row spacedBy 12, `Alignment.Top`; 1×1 weight 1, 1×2 weight 2; short rows padded with weighted Spacers (`:167-169`); column spacing 16 | — | — | gaps and Spacers trigger | — |
| **1×1** `WidgetCoverBlock` | `:314-371` | `WidgetBackdropArtwork` fixed **100 dp** (`WidgetCoverSize` `:54`); backdrop polygon by entity (Album→`Bun`, Song→`Circle`, Playlist→`Ghostish`, `:415-421`); art at 72% (`:55`), bottom-end, `YoinArtworkShapes.Thumb` (continuous 4 dp); press morphs the backdrop to `MaterialShapes.Triangle` (`:424-428`); 5 dp gap; title titleMedium SemiBold 16/22sp; subtitle 12sp | album, song or playlist | `performContextClick` + target (`:324-328`) | swallowed (whole column) | data |
| **1×2** `WidgetCard12` | `:217-307` | Row: cover block (100 dp) + 16 dp + column with rating (headlineSmall Bold tabular, palette accent in dark, base in light; `:260-264`), basis labelSmall 85% alpha, comment (serif `YoinSerifTitle` 17/24 when `commentIsHeadline`, otherwise bodyMedium; maxLines 3) | memory album (AI title) or noted track | `performContextClick` + target (`:233-237`) | swallowed (whole row) | data; `expanded` flag decides 1×2 |
| `HomeWidgetTarget` | `HomeUiState.kt:38-47`; dispatch `HomeEditorialContent.kt:245-254` | `AlbumDetail(albumId)` → `onAlbumClick(id, null)` (no shared key); `PlaylistDetail` → `onPlaylistClick`; `PlaySong(Track)` → `playbackManager.playSingle` (`YoinNavHost.kt:842-849`); `MemoryFocus(sessionId)` → `requestMemoriesFocus` + Memories surface (`:831-836`) | — | — | — | — |

**`packWidgetRows`** (`HomeWidgetGrid.kt:187-210`):
- All 1×2 cards are taken first.
- Each 1×2 shares its row with the next `columns − 2` compact cards, alternating left/right (`pairIndex % 2`).
- Leftover compact cards fill rows via `chunked(columns)`.
- The 3-column output is pinned as a **golden invariant** by `HomeWidgetGridPackTest`; `threeCol_widesLeadRegardlessOfInputPosition` (test `:69`) confirms that input order is *not* honoured for wide cards.

**Column count** (`:62-64`, `:124-130`): Compact 3, Wide 6, otherwise 4. The 12-cell budget divides evenly by 3, 4 and 6.

**Grid composition** (`HomeViewModel.kt:417-447`):
- 1×2 cards: memory album (`:455-500`) and noted track (`:507-544`).
- Compact cards: albums, tracks and playlists interleaved round-robin (`:591-605`); primary mix 3 / 2 / 3, topped up to `GRID_TOTAL_CELLS = 12` (`:680`).
- Pools are persisted in `home_grid_pool_cache` with a 6 h TTL (`:356-364`, `:692`).
- The two signal cards stay live (`observeMemorySignals` `:303-316`). `refreshWidgetGridSignalCards` (`:323-344`) **prepends** the wide cards again, so slot positions are not stable.

**Recently Added** (item `key="section-recently-added"`, `:465-494`): skipped when both lists are empty. Implemented by `RecentlyAddedSection` (`:1267-1339`).

| Element | file:line | Size / shape | Tap | Long-press |
|---|---|---|---|---|
| Title | `:1287` | `HomeSectionTitle` | — | triggers |
| Shelf (single `LazyRow`) | `:1295-1337` | full bleed via `ignoreParentHorizontalPadding(pageHorizontalPadding)` + matching `contentPadding`; spacedBy 14; deliberately **no** edge fade (`:1288-1294`; `horizontalEdgeFadeOnScroll` is imported but unused, `:83`) | — | gaps trigger; horizontal drag cancels |
| 2×2 track grid (first item, `key="recently-added-tracks"`) | `:1316-1323`, `RecentlyAddedTrackGrid` `:1342-1378` | width `(maxWidth − 14) × 2.6/3.6`, or on landscape phone `min(45%, 340dp)` (`:1299-1303`); rows spacedBy 14, columns spacedBy 8; at most 4 tracks | — | gaps trigger |
| Track tile | `RecentlyAddedTrackTile` `:1380-1437` | 52 dp cover (`:1263`), `Thumb` shape; title 13sp Medium; artist labelSmall | `HomeEntryTarget.SongTarget` → play (`:474`) | swallowed |
| Album card (`key="recently-added-album:${album.id}"`) | `:1325-1335`, `RecentlyAddedAlbumCard` `:1439-1483` | width 82 dp, Bun backdrop (`:1265`), title bodySmall Medium, artist | `HomeEntryTarget.Album(id, null)` (`:475-477`) | swallowed |
| `HomeEmptyCard` (Activities only) | `:1485-1515` | `ExpressiveSectionPanel` (Panel shape, continuous 20 dp) | — | triggers (non-clickable Surface) |

**Data loading** (`HomeViewModel.kt`):
- Window: 7 days, at most 4 tracks and 12 albums (`:697-699`); loader `loadRecentlyAdded` `:165-179`.
- The Spotify cached pre-paint path has no Recently Added data (`:190-208`).

---

## 2. Current editor

### Entry: long-press (`HomeEditorialContent.kt:323-333`)

```kotlin
.pointerInput(Unit) {
    detectTapGestures(onLongPress = { haptics.performLongPress(); onEnterEditModeState.value() })
}
```

- It is attached to the **LazyColumn's own modifier**, after the 720 dp width cap (`:313`). On an 800 dp tablet the margins outside the column are dead zones for both long-press and scroll, because the pointer input sits inside the `widthIn` cap.
- **Mechanics, verified against the shipped foundation 1.11.0-beta02 bytecode:**
  - `ClickableNode.handleDownEvent` calls `PointerInputChange.consume()` on the down during `PointerEventPass.Main`.
  - `detectTapGestures` → `processTapGesture` calls `awaitFirstDown$default` with mask 3, which means `requireUnconsumed = true` on `Main`.
  - In the Main pass, children run before parents, so **any down that lands on a `noRippleClickable` / `clickable` never starts the parent gesture**.
  - The code comment "Cards only consume taps … so the press passes through them" (`:323-325`) is **wrong**.
- **Where long-press triggers:**
  - Header title and status-bar area.
  - The three section titles.
  - Inter-section gaps (18 dp) and bento gaps (10 dp).
  - Grid row and column gaps (16 / 12 dp), padding Spacers, and the space under a 1×1 that sits beside a taller 1×2.
  - Recently Added gaps (8 / 14 dp).
  - `HomeEmptyCard`.
- **Where it is swallowed:** every Activities card (the clickable fills the whole Surface: hero `:987`, small `:1062`, wide `:1138`, strip `:1217`), every JBI 1×1 / 1×2 (`HomeWidgetGrid.kt:234`, `:325`), Recently Added tiles and albums (`:1392`, `:1452`), and both header IconButtons. That is most of the visible surface.
- **Worse:** plain `clickable` has no long-press handling. Holding a card shows the press state (`elasticPress` 0.97, plus the Bun/Circle/Ghostish → Triangle morph), and **releasing then fires `onClick`**. A user who tries "long-press to edit" on a song widget therefore starts playback, and on an album opens the detail page.
- Scrolling cancels the long-press correctly: the inner `scrollable` consumes moves past slop, and `waitForUpOrCancellation` bails on consumed changes.

### The swap (`HomeScreen.kt:235-280`)

- `AnimatedContent(isEditMode)`.
- Enter: `fadeIn(Expressive)` + `scaleIn(Expressive, 0.98)`. Exit: `fadeOut(Expressive)`. The same transition is used in both directions.
- No `contentKey`, default `SizeTransform`.
- Exiting removes `HomeEditorialContent` from composition (see §3).

### Editor UI (`HomeLayoutEditor.kt:66-328`)

- Root: `Column.verticalScroll`, padding 16 / 16 / 4 plus bottom 108 dp + nav bar (`:115-121`). No width cap, no seam viewport.
- `EditorHeader` (`:303-328`): "Edit Home" in headlineLarge and a `Check` IconButton tinted primary. Hint text at `:130-135`.
- One row per catalog section (`:137-299`):
  - `Surface(YoinContainerShapes.Card, surfaceContainerLow)`, tonal elevation 2 dp (6 dp while active), fixed height `EditorRowHeight = 72dp`, spacing `10dp` (`:53-54`).
  - Contents: `DragHandle` icon with `minimumTouchTarget()` (44 dp); title (titleMedium) and supporting text (bodySmall), both at alpha 0.55 when disabled; an M3 `Switch` (`:285-295`).

### Drag reorder (`:73-112`, `:175-264`)

- **State**
  - `draft = remember { sections.toMutableStateList() }` (`:82`) is seeded once and is deliberately *not* re-seeded from Room echoes.
  - Per-id `Animatable` offsets (`rowOffsets`, `:85-87`), plus `activeId`, `settlingId`, `dragIndex` and `dragOffset` (`:89-93`).
  - `stepPx = (72 + 10).dp` (`:75`) assumes **uniform row height**.
  - `settleSpec = YoinMotion.defaultSpatialSpec()`, which resolves to Expressive (`:76`).
- **Rendering**
  - `graphicsLayer` reads `dragOffset` or `offsetAnim.value` in the draw phase (`:162-166`), so drag frames do not recompose.
  - z-index is 2 for the active row, 1 for the settling row (`:155-161`). Lift scale is 1.02 (`:142-146`).
- **Gesture:** `detectDragGestures` on the handle, `pointerInput(sectionId)` (`:184`).
  - **Single-drag policy:** `onDragStart` bails if `activeId != null` (`:192`). End and cancel only settle when `activeId == sectionId` (`:255`, `:260`). A refused second finger still `consume()`s (`:213`) so it cannot scroll the editor.
  - **Catch in place:** `dragOffset = offsetAnim.value`, then `stop()` and `snapTo(0)` (`:200-206`).
  - **Keyed on id, not index:** an index-keyed restart would cancel the drag on the first live swap (`:181-183`).
  - Velocity: a `VelocityTracker` is fed the *cumulative* drag (`:216-220`).
  - **Live swap:** while `|dragOffset| > stepPx/2`, swap in the draft, subtract the shift, and spring the displaced neighbour from `+shift` back to 0 with a `performTick()` (`:225-249`).
  - **Release:** `settleDrag(vy)` (`:97-112`) snaps to the residual offset, animates to 0 with `initialVelocity`, calls `performConfirm()` and then `commit()`.
  - Haptics: pickup is `performContextClick` (`:207`).
  - No auto-scroll at the edges, no clamping.

### Back handling

- `BackHandler(enabled = isEditMode && !suppressBackHandling)` (`HomeScreen.kt:94`).
- The suppression is needed because Now Playing's handlers register first, and the LIFO dispatcher would otherwise let Home's handler win (`:66-70`). Detail-pane suppression is added at the call site (`YoinNavHost.kt:825`).
- Edit mode is **not** an entry in `ShellBackOwner` (`ui/navigation/back/ShellBackResolver.kt:6-12`). It is a gated plain `BackHandler` (`docs/adaptive-principles.md:128`), with no gesture-progress preview.

### Persistence

- `commit()` → `onLayoutChange(HomeLayout(draft))` → `HomeViewModel.setHomeLayout` (`:83-89`). This is a no-op when there is no active profile.
- `HomeLayoutStore.setLayout` upserts `{"sections":[{"id","enabled"}…]}` into `home_layout` (PK `profileId`) (`HomeLayoutStore.kt:42-58`). Errors are swallowed.
- Writes are immediate: once per drop and once per switch toggle, never during live swaps.
- Read path: `layoutFlow(profileId).map(HomeLayout::reconcile)`, then `stateIn(Eagerly, Default)` (`HomeViewModel.kt:64-74`).

### Exit paths

- Done button (`:123-129`, `performConfirm`).
- System back (`HomeScreen.kt:94`).
- Profile switch: `activeProfileId.drop(1).collect { isEditMode = false }` (`:91-93`).
- Implicit: switching to the Library tab disposes `HomeScreen`. The shell's `AnimatedContent<YoinSection>` has no `SaveableStateHolder`, so the `rememberSaveable` edit flag is dropped.

---

## 3. Known problems and rough edges

1. **Long-press is swallowed on almost every card, and the release then navigates or plays** (details in §2). Your memory notes already record this as pre-existing, with a task spawned for it.
2. **Leaving the editor resets the feed.** `HomeEditorialContent` is disposed under `AnimatedContent` with no `SaveableStateHolder`, so:
   - `rememberLazyListState()` (`:159`) returns to the top. Your notes record this as accepted.
   - The Recently Added `LazyRow` state is lost.
   - **`rememberStagedReveal("home-feed")` replays.** Its `played` flag is a `rememberSaveable` inside the disposed subtree (`StagedReveal.kt:54`), so the hero → meta → payload stagger runs again on every editor exit. This breaks "no repeat-visit staggers" (`StagedReveal.kt:22-37`).
   - `allowBackdropPalette` resets. This is harmless: the palette `LruCache` hit avoids a colour flash (`ExpressiveBackdropPalette.kt:86-95`).
3. **The editor's geometry differs from the feed.** The editor is always full width with 16 dp gutters and 108 dp bottom padding. The feed is capped at 720 dp on Compact/Medium, uses 32 dp gutters on Wide, and 24 dp gutters with 16 dp bottom padding on landscape phone. The cross-fade therefore jumps in width on the Pixel Tablet in both orientations.
4. **There is no in-place continuity.** A 2D feed cross-fades into a three-row list with no content preview. The pull-to-Memories gesture and the header icons are gone while editing. There is no visible entry point other than long-press, and no "reset to default".
5. **Empty sections are invisible.** JumpBackIn and RecentlyAdded emit *no item* when their data is empty (`:443`, `:467-468`); only Activities has an empty card. An in-place editor cannot grab them.
6. **User order is impossible inside JBI.** `packWidgetRows` always moves wide cards first. `refreshWidgetGridSignalCards` re-prepends the wide cards. The 12-cell composition is hard-coded in the ViewModel.
7. **Haptics are inconsistent on taps.** JBI card taps fire `performContextClick`; Activities and Recently Added taps are silent. `docs/haptic-feedback.md` §D says card taps should have no haptic.
   - `combinedClickable` vibrates on long-click by default (`hapticFeedbackEnabled`, confirmed in the 1.11 bytecode). The `AlbumDetailComponents.kt:458-462` pattern adds `performLongPress()` on top, which doubles the haptic. Pass `hapticFeedbackEnabled = false` if the editor reuses that pattern.
8. **Adaptive rule drift.**
   - `HomeWidgetGridSection` picks columns from `layoutMode` without checking `isCompactHeight` first (`HomeWidgetGrid.kt:124-129`). A landscape phone of 844 / 690 dp gets 6 / 4 columns, against the height-first rule cited at `HomeEditorialContent.kt:275-276`.
   - Scattered `== Wide` / `else` checks in page code (`:279`, `:371-378`, `HomeWidgetGrid.kt:125-129`) break adaptive principle 8, which wants them collected in `WindowAdaptiveRuntime` with tests.
9. **Editor drag limitations.**
   - The uniform 72 dp step will not survive variable-height sections.
   - No auto-scroll, no clamping.
   - The `pointerInput(sectionId)` closure captures `onLayoutChange`, `settleSpec` and `stepPx` from the first composition. These are stale if the callbacks change. Low risk today, because the ViewModel method reference is stable.
10. **Startup ordering.** `homeLayout` starts at `HomeLayout.Default` (`Eagerly`). Until Room emits, the feed shows the default order, and `animateItem` then animates the jump. This is rarely visible because content loads more slowly than Room.
11. **Downgrade data loss.** `HomeLayout.toPrefs()` writes only known sections. A section id written by a newer build is dropped on the next write by an older build.
12. **Orphan rows.** `HomeLayoutDao.delete(profileId)` is never called. `ProfileManager.delete` (`data/profile/ProfileManager.kt:227-244`) leaves `home_layout` rows behind.
13. **Housekeeping.**
    - `ExpressivePageBackground` is drawn twice (`YoinNavHost.kt:817`, `HomeScreen.kt:159`).
    - The bottom padding is a hard-coded 108 dp instead of `LocalShellChromeInsets`.
    - `docs/design.md:183-188` (🏠 主页) is stale: it still describes Mix, the visualizer and the Memory teaser.
    - `HomeLayoutTest` names don't follow the `should_…` convention.
    - There are no tests for `HomeLayoutStore` JSON round-trip or for `setHomeLayout`.

---

## 4. Adaptive behaviour

**How Home gets its window info.** `LayoutMode` is computed per column (`ui/experience/WindowAdaptiveRuntime.kt:50-65`, `:224-244`): Compact < 600, Medium 600–840, Wide ≥ 840, plus Tabletop. `isCompactHeight` is height < 480, which is the EdgeSplit form (`:119`). Home reads `LocalYoinWindowInfo`, which the shell column provides.

| Window | Mode Home sees | Feed width | Padding | Activities tier | JBI columns | Recently Added |
|---|---|---|---|---|---|---|
| Phone portrait 390 | Compact | cap is a no-op | 16 | Phone (≤4 cards) | 3 | track grid 72% |
| Phone landscape (h < 480) | Medium/Wide by width, but `isLandscapePhone` | full, no cap | 24 | Landscape (1 row) | **4 or 6** (not height-aware) | `singleRowShelf`, grid ≤ 340 dp |
| **Pixel Tablet portrait 800** | Medium | **capped 720, centred** (688 content) | 16 | Dense (6) | 4 (cells ≈ 163 dp, cover still 100 dp) | grid ≈ 487 dp; shelf bleeds only to the 720 column edge |
| **Pixel Tablet landscape 1280** | Wide | **full width** (`isDesktopWide`) | 32 | Desktop (10) | 6 (cells ≈ 190 dp, cover still 100 dp) | 2×2 grid ≈ 870 dp wide (very stretched tiles) |
| 1280 with detail column open | shell column 600 → Medium | ≤ 720 | 16 | Dense | 4 | — |
| 1280 with NP panel (860 left) | Wide | full | 32 | Desktop | 6 | — |
| 1280 with NP panel and detail column | shell 376 → Compact | — | 16 | Phone | 3 | — |

- There is **no two-column home** at any size.
- Wide only widens the bento and the grid; every section stays full width in a single column.
- Covers are fixed-size (100 / 96 / 82 / 52 dp), so the extra width on Medium and Wide becomes slack instead of larger art.

---

## 5. Reusable building blocks for an in-place edit mode

### Motion (`ui/theme/Motion.kt`)

- `YoinMotion.spatialSpring()` / `effectsSpring()` (`:231-235`), `fast*` / `slow*` (`:191-229`), and role-explicit overloads (`:129-181`). Home runs under the Expressive role.
- From M3 `MotionScheme.expressive()` (`AnimatedColorScheme.kt:267`). The damping / stiffness figures below are standard M3 token values, not read from this codebase:
  - Spatial: default ≈ 0.8 / 380, fast ≈ 0.6 / 800, slow ≈ 0.8 / 200.
  - Effects: critically damped.
- `stageSettleSpring` (0.85 / 700, `:258-261`) and `predictiveBackSettleSpring` (`:249`).
- Transitions: `fadeIn` / `scaleIn` / `slideIn*` / `expandHorizontally` (`:263-387`).
- There is **no idle-oscillation (wiggle) token**. The existing infinite loops are `MemoriesScreen.kt:1276` (`rememberInfiniteTransition` seal spin) and the visible-gated aurora loops (`NowPlayingAuroraBackground.kt:140-149`, `MemoriesAuroraBackground.kt:57-66`).
- **Reduced-motion gate to copy:** `LocalMotionProfile == AdaptiveReduced || MotionDurationScale == 0f` (`SeamDissolve.kt:401-403`; `ui/experience/MotionRuntime.kt:14-19`).
- **Per-frame rule:** read wiggle and drag values only inside `graphicsLayer {}` (adaptive principle 7). `stagedBeat` (`StagedReveal.kt:78-92`) is the pattern for a draw-phase-only modifier.

### Press feedback (`ui/component/PressFeedback.kt`)

- `noRippleClickable(interactionSource, enabled, onClick)` (`:30-39`). Passing `enabled = !isEditMode` stops the down being consumed, so a parent drag or long-press detector can take over in edit mode.
- `elasticPress(source, pressedScale = 0.97f)` with the Standard spatial spring (`:45-59`). It is applied twice on cards (Surface and artwork), so art compounds to about 0.94.
- `minimumTouchTarget(44.dp)` (`:61-63`).
- `rememberPressMorphShape(base, source, pressed = MaterialShapes.Triangle)` (`ui/component/PressShapeMorph.kt:37-55`). It is a generic `RoundedPolygon` `Morph`, so it could serve as an edit-mode "lifted" shape or a jiggle state.

### Haptics (`ui/experience/Haptics.kt:16-62`)

- Methods and the constants they map to:
  - `performClick`: `KEYBOARD_TAP`
  - `performTick`: `CLOCK_TICK`
  - `performConfirm`: `CONFIRM` on API ≥ 30
  - `performReject`: `REJECT`
  - `performLongPress`: `LONG_PRESS`
  - `performContextClick`: `CONTEXT_CLICK`
  - `performLightTick`: `TEXT_HANDLE_MOVE`
- Current Home usage:
  - Memories commit → `Confirm` (`HomeEditorialContent.kt:197`).
  - Enter editor → `LongPress` (`:330`).
  - Header icons → `ContextClick` (`:535`, `:552`).
  - JBI taps → `ContextClick` (`HomeWidgetGrid.kt:235`, `:326`).
  - Editor: pickup `ContextClick` (`:207`), swap `Tick` (`:247`), drop `Confirm` (`:110`), switch `ContextClick` (`:288`), Done `Confirm` (`:125`).
- The helper does not expose the API 34+ constants (`DRAG_START`, `SEGMENT_TICK`, `GESTURE_THRESHOLD_ACTIVATE`, `TOGGLE_ON` / `TOGGLE_OFF`). The test tablet runs SDK 37, so these are available.

### Reorder logic worth extracting from `HomeLayoutEditor`

- `offsetFor(id)` per-id `Animatable` map.
- Single-drag guard; catch-in-place seeding; id-keyed `pointerInput`.
- `VelocityTracker` on cumulative deltas.
- Live-swap `while` loop with neighbour counter-spring.
- `settleDrag` with velocity-seeded settle, plus a z-index for the settling row.
- **What has to generalise:** replace the fixed `stepPx` with measured heights, either `LazyListState.layoutInfo.visibleItemsInfo` (offset, size, key) or `onPlaced`. Add edge auto-scroll; `LazyListState.scrollBy` does not dispatch nested scroll, so it will not trip pull-to-Memories. Still gate `pullToMemoriesConnection` (`:168`) off while editing.

### Lazy keys

- Feed: `"home-header"`, `"section-activities"`, `"section-widget-grid"`, `"section-recently-added"` (`:346`, `:364`, `:444`, `:469`). These are hard-coded strings rather than `section.id`. Every section already has `animateItem(placementSpec = spatialSpring)`, so reordering `sections` while editing animates for free; the dragged item has to opt out of placement animation.
- Shelf: `"recently-added-tracks"` and `"recently-added-album:<id>"`.
- The JBI grid is **not lazy and not keyed**: plain `forEach` inside a Row (`HomeWidgetGrid.kt:137-171`). `HomeWidgetCard.stableId` (`HomeUiState.kt:57`) is ready to use as a key. Moving cards across rows would need `key(stableId)` plus a lookahead or bounds animation, or a custom grid layout.

### Other components

- `YoinDropdownMenu` / `Section` / `Item` (`ui/component/YoinMenu.kt:42-115`). This is mandatory for per-section overflow such as hide, size or reset.
- `ExpressiveSectionPanel` (`ExpressiveComponents.kt:175`) for placeholders of hidden or empty sections.
- `YoinSymbols` icons already used in the app: `DragHandle`, `Visibility`, `VisibilityOff`, `Close`, `Add`, `Delete`, `Check`, `Edit`, `MoreVertical`, `UnfoldMore`, `UnfoldLess`.
- Seams: cards carry `seamDissolve` / `seamFade`.
  - A wiggle `graphicsLayer` outside them is fine. However, `SeamNode.onGloballyPositioned` (`SeamDissolve.kt:564-586`) may redraw items near a seam on every frame while they rotate; profile this.
  - Your notes on seam dissolve say not to add new overlays at the boundary with the bottom bar, which argues for morphing the bar into an edit toolbar instead.

---

## 6. Data model gap analysis

**Current model**
- `HomeSectionPref(id: String, enabled: Boolean)`, `@Serializable` (`HomeLayoutStore.kt:17-21`).
- Stored as `SectionsDto(sections)` with `Json { ignoreUnknownKeys = true; encodeDefaults = true }` (`:33-36`, `:63-64`).
- **A decode failure returns `null`, which becomes `Default`** (`:60-61`). Any incompatible change to the shape therefore silently wipes user layouts.
- Room table `home_layout(profileId PK, sectionsJson, updatedAt)` was added by `MIGRATION_23_24` (`AppContainer.kt:527-543`); the DB is now at v28.
- UI side:
  - `HomeSection` enum: stable `id`, `title`, `supportingText`, `defaultEnabled` (`HomeSection.kt:20-52`).
  - `HomeSectionState(section, enabled)` (`:55-58`).
  - `reconcile` keeps saved order and flags, drops unknown ids, de-duplicates, and appends new sections **at the end** at their default (`:89-102`).

**(a) Reorder and hide in place**
- Order and enabled flag are already enough; this is a UI-only change.
- Recommended additions:
  - Edit-mode placeholders for empty sections (UI only).
  - Preserve unknown pref entries through `toPrefs()` so downgrades don't lose them.
  - Wrap the JSON in an optional `version: Int = 1` on `SectionsDto`.
  - Decode leniently, per entry, so one bad entry doesn't reset the whole layout.
- No DB bump is needed.

**(b) Per-widget order and size inside Jump Back In**
- Content rotates every 6 h and the two signal cards are recomputed, so persisting order **by content id is meaningless**.
- Persist a **slot spec** instead: an ordered list of `{slotId, kind: memory_album | noted_track | album | track | playlist | pinned(MediaId), span: 1x1 | 1x2 …}`. The ViewModel fills slots from the pools.
- Where to store it: an optional `config` on the `jump_back_in` pref (a new field with a default, which old builds ignore), or a separate table.
  - A separate table must carry `provider` if it pins remote ids (AGENTS.md), and needs a v28 → v29 bump plus the chained `YoinDatabaseMigrationTest`.
- Required code changes:
  - Replace the fixed 2 + 2 + 3 + 3 + 2 composition and the `GRID_TOTAL_CELLS` arithmetic in `buildWidgetGrid` / `refreshWidgetGridSignalCards` (`HomeViewModel.kt:323-344`, `:417-447`) so slot positions survive signal refreshes.
  - Add an **order-preserving packer**, keeping `packWidgetRows` for "auto" so the golden 3-column test holds.
  - Persist **linear order plus span**, not x/y, because the column count is 3, 4 or 6 depending on width.
  - A user-forced 1×2 on a card with no rating or comment needs fallback content for the right-hand column; `WidgetCard12` currently renders it empty.

**(c) New section types**
- The simple case works today: add an enum constant and a render branch, and `reconcile` appends it at the default. There is no way to insert it at a chosen position; consider an `insertAfter` hint.
- Gaps:
  - `HomeUiState.Content` is a flat bag of per-section fields (`HomeUiState.kt:11-32`), and the ViewModel loads every section even when it is disabled (`HomeViewModel.kt:134-155`, `:210-226`). Move to a per-section payload map or sealed type, and load only enabled sections, re-loading when a section is toggled on.
  - No capability gating: add `requiredCapabilities` on `HomeSection` and filter at render time, not in persistence (AGENTS.md Phase C).
  - **Multiple instances of one type** (for example two playlist shelves) cannot be expressed: `reconcile` keys and de-duplicates by enum. Add `type` alongside the instance `id`; old prefs infer `type = id`, so this stays backward compatible. Derive LazyColumn keys from the pref id.
- All of the above are JSON-shape changes inside `sectionsJson`. Without a new table they need no Room migration, provided ids are never renamed, every new field has a default, and `ignoreUnknownKeys` stays on.