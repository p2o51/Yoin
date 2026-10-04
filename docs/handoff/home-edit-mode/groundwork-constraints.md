# Yoin: design constraints and owner rulings for new Home components and a launcher-style long-press "wiggle" edit mode

This is a read-only report; no files were changed. Repo paths are relative to ``. Memory files live in owner 的 Claude 记忆目录（不在仓库里） (shown as `mem/…`). Memory line numbers are file lines, front-matter included.

> **Doc precedence.** `.claude/skills/predictive-back/SKILL.md:47-51` says AGENTS.md's back mechanics are out of date, and that the skill and the code win where they disagree. `docs/adaptive-principles.md:5-6` says that file beats design.md on adaptive rules. Several design.md Home bullets are stale:
> - The Mix / visualizer / Memory-teaser bullets at `docs/design.md:185-187` describe sections that were retired (`mem/project_home_memories_widgets.md:10`).
> - The visualizer is not wired up at all (`docs/design.md:394`).

---

## 1. Hard constraints

### 1.1 Motion
| Rule | Source |
|---|---|
| Every meaningful transition animates; no hard cuts. Prefer springs: **spatial** springs for movement and size (offset, rotation, scale), **effects** springs for colour and alpha. No tween by default. | `AGENTS.md:31-40`; `docs/design.md:13,50-55` |
| Colour changes animate (Effects Spring). | `AGENTS.md:60`; `docs/design.md:68` |
| Use `YoinMotion` / `MotionScheme` buckets, never raw `spring`/`tween`. Roles: `Expressive` for hero and scene changes, `Standard` for utilitarian or repeated interactions. `AdaptiveReduced` keeps the role but lightens the bucket. | `docs/motion-audit-matrix.md:3-7,32-37`; `ui/theme/Motion.kt:29-32,50-57,86-87,129-235`; `docs/handoff/responsive-breakpoints.md` §13 row "动效" ("不写 tween 时长") |
| Home: hero/editorial content is Expressive. Repeated rows get **no per-item entrance on scroll**. "Repeated list scrolling does not trigger fresh performative entrance animations." | `docs/motion-audit-matrix.md:14-15,36` |
| **One settle owner per value.** A second `animateTo` on the same Animatable races it and flashes. The page keeps one primary displacement state. | SKILL.md:400-403; `mem/project_np_stage_settle_owner.md`; `docs/motion-audit-matrix.md:38`; `AGENTS.md:256` |
| Values that change every frame (gesture progress, spring values) are read only in layout or draw (`graphicsLayer`, layout modifiers). They must not recompose the shell. | SKILL.md:419-424; `docs/adaptive-principles.md:115-117` |
| Reduced motion: under power-save and system "remove animations", the seam afterglow, stretch and disorder turn off, and symbols freeze. The profile is `MotionProfile.AdaptiveReduced`. | `docs/design.md:82,117`; `ui/experience/MotionRuntime.kt:14-19,26-34` |
| Settles that run after the finger lifts get no touch boost, so they must vote high frame rate (`voteHighFrameRate`). This must **not** be left on globally (owner ruling). | SKILL.md:430-432; `mem/reference_variable_font_and_refresh_rate.md` |

### 1.2 Back surfaces: Home is a RootSection
- **Letter of the law:** RootSection must not intercept root back for local UI, must keep system back-to-home, and must not be wrapped in a local predictive-back surface (`AGENTS.md:157-164,222`; SKILL.md:60). Never write a raw `PredictiveBackHandler` in a feature screen, and add no back magic numbers (`AGENTS.md:173,218-220`; SKILL.md:433-438).
- **Allowance for edit mode:** the skill's class "In-page state machine — not a place; back may step the state; its surface's own controller, never a new route" (SKILL.md:66,144-148) covers it. `docs/adaptive-principles.md:126-128` explicitly says *"Home 的编辑态也按 `ShellBackOwner` 门控"*: back priority is expressed by **gating, not mount order**.
- **Platform contract:** while a callback is enabled, it suppresses the system back-to-home animation (SKILL.md:78-82,441-442). The handler must therefore be enabled **only** while editing. Handler priority is LIFO by registration (SKILL.md:92-97,429).

**How the existing `HomeLayoutEditor` handles back, and whether that complies:**
- `ui/home/HomeScreen.kt:94`: `BackHandler(enabled = isEditMode && !suppressBackHandling) { isEditMode = false }`. This is a plain `BackHandler`, not a raw `PredictiveBackHandler`, so it is not banned.
- `ui/navigation/YoinNavHost.kt:823-826`: `suppressBackHandling = shellBackOwner == NowPlaying || shellBackOwner == DetailPane`. This is gated, as §8 requires.
- **Gaps:**
  1. `ShellBackOwner` has no `HomeEdit` entry (`ui/navigation/back/ShellBackResolver.kt:6-12,23-36`), so the resolver does not know edit mode exists.
  2. Memories is not in the gate. It wins only because its `BackHandler` registers later (`YoinNavHost.kt:864-867`). That is mount order, which `adaptive-principles.md:126-127` forbids. Memory records the same reliance: `mem/project_customizable_home.md:26`.
  3. There is no predictive preview. Back is commit-only, followed by an `AnimatedContent` fade plus `scaleIn(0.98)` (`HomeScreen.kt:235-247`). AGENTS.md's "gesture progress must directly drive UI state" is therefore not met for this in-page state.
  4. A preview would need a `PredictiveBackHandler` wrapper in back infrastructure (`ui/navigation/back/`), with tokens in `BackMotionTokens` / `YoinMotion` (SKILL.md:433-438,468-469). The model is the NP stage pattern: a uniform scale preview with `backGestureEasing`, and the real reshape on commit (SKILL.md:320-336,408-414).
  5. Edit state is `rememberSaveable` inside the section `AnimatedContent` (`HomeScreen.kt:86`; `YoinNavHost.kt:788-806`), which has no SaveableStateHolder. Switching to Library therefore very likely drops edit mode. This is inferred, not verified.
  6. A profile switch force-exits edit mode (`HomeScreen.kt:87-93`).

### 1.3 Menus
- Every overflow or long-press menu goes through `YoinDropdownMenu` / `YoinDropdownMenuItem` / `YoinDropdownMenuSection(index,count)`. These wrap `DropdownMenuPopup` + `DropdownMenuGroup(MenuDefaults.groupShapes())`. Never use raw `DropdownMenu`, and never hand-roll shapes (`mem/feedback_m3_expressive_menus.md:7-21`; `ui/component/YoinMenu.kt:53,60,81`).
- When an action is pulled out of a ▾ menu into its own button, remove it from the menu; don't show it twice (`docs/design.md:181`).

### 1.4 Truncation and edges
- Horizontal scrollers use three pieces together: `ignoreParentHorizontalPadding` + matching `contentPadding` + `horizontalEdgeFadeOnScroll`. Never clip mid-page. One 16dp left margin line per screen. Fades are smoothstep DstIn, never colour overlays. Titles stay on one line and marquee when they overflow (`MarqueeText`) (`mem/feedback_no_midpage_truncation.md:10-25`).
- Exception: the Home Recently Added shelf has **no** fade mask. Ask before removing fades anywhere else (`:23`; `mem/project_home_memories_widgets.md:16`).
- Vertical scroll containers on new pages use `yoinPageContentWidth` (Feed 720). Home Wide desktop is the exception: no clamp, 32dp gutters (`mem/project_adaptive_baseline.md` "How to apply"; `docs/design.md:351`; `HomeEditorialContent.kt:271-313`).
- The current editor has **no** width clamp and **no** seam treatment (`HomeLayoutEditor.kt:115-121`). It also hardcodes `108.dp` bottom padding instead of using `LocalShellChromeInsets` (`docs/design.md:180`).

### 1.5 No cover borders
- Artwork never gets a stroke or outline. `ExpressiveMediaArtwork` keeps `border = null`. Control-state borders on buttons and progress rings are fine (`mem/feedback_no_cover_borders.md:10-14`).
- So an edit-mode "selected" ring around a cover is banned. Indicate selection through the container, lift, scale or a badge instead.

### 1.6 Typography
- `YoinTypography` uses `letterSpacing` 0sp everywhere; labelMedium and labelSmall use 0.1sp. Never inherit M3/Roboto tracking. Never use a bare `TextStyle()` (it drops GSF); always `typography.*.copy()` (`mem/project_type_tracking_gsf.md:12-16`).
- Spacing rhythm: title and subtitle sit flush; one 5dp Spacer between artwork and the text cluster; 3dp for annotations. Never a uniform `spacedBy` (`:18`).
- GSF has real 400/500/600/700 weights (`mem/project_ui_review_sweep_decisions.md:16`).
- Serif is reserved for the AI memory title only (`docs/design.md:204-210`).

### 1.7 Shape tokens
- The 10-step MD3 corner scale (`docs/design.md:31-48`).
- Semantic tokens:
  - `YoinArtworkShapes`: Thumb/Cover 4dp, Hero 8dp, continuous.
  - `YoinContainerShapes`: Card 16dp, Panel 20dp, continuous; ListRow 12dp, circular.
- `ContinuousRoundedCornerShape` is for **static sizes only**. Anything animated (morph endpoints, clips resized every frame) uses the circular `*Animated` twins (`mem/project_shape_semantic_tokens.md:12-17`).
- Backdrop shape follows entity type, never genre: Album→Bun, Song→Circle, Playlist→Ghostish, Artist→plain circle portrait (`mem/project_home_memories_widgets.md:17`; `HomeWidgetGrid.kt:416-420`).
- Press morph (backdrop → `MaterialShapes.Triangle`, `rememberPressMorphShape`) is allowed **only** on the "padded" backdrop shape (`mem/project_shape_semantic_tokens.md:19`; `ui/component/PressShapeMorph.kt:37-51`).

### 1.8 Seam dissolve
- Scrolling content that meets Yoin chrome dissolves item by item: graphics break into halftone dots, text only fades. The status bar gets the tide line. **Nothing is laid over a seam** (`docs/design.md:72-88,252-254,289-291`; `mem/project_seam_dissolve.md:11-18`).
- The Home feed already carries `seamTide` + `seamDissolveViewport(top = FadeText)` (`HomeEditorialContent.kt:299-325`). Cards carry `seamDissolve()` / `seamFade()` (e.g. `:978,1003,1130,1402`).
- Rules a wiggle would hit:
  - At rest, every dot sits exactly on the lattice; there is zero cost at rest.
  - The dot field under the bar never flows (`docs/design.md:82-83`).
  - Items that rotate or translate near the bar or the status bar break both rules. This needs on-device verification.

### 1.9 Theme wash invariant
- Every new `ColorScheme` instance recomposes the whole app tree. Wash targets are de-duplicated by value (`mem/project_theme_wash_invariant.md:10-16`).
- Edit-mode dimming or tinting must use `graphicsLayer` alpha or a local colour lerp, never a scheme override.
- Bento container colour is fixed as `lerp(surfaceContainerLow, coverPalette.baseColor, 0.30f)`. Do not use `fromSeed` (`mem/project_home_memories_widgets.md:12`).

### 1.10 Icons (Yoin Symbols)
- Only `io.github.p2o51:yoin-symbols`; no material-icons in new code. Missing symbols are added to the library's `generator/` first (`docs/design.md:92-98`).
- Line icons by default; `*Filled` for selected/on (`:102`).
- Available now (checked in `yoin-symbols 仓库/.../YoinSymbols.kt`): `Add, Check, Close, Delete, DragHandle, Edit, Refresh, Visibility, VisibilityOff, MoreHorizontal, MoreVertical, Settings`.
- **Missing:** Remove/Minus, Undo, Reset. An iOS-style "−" badge or a "Reset" icon needs a generator addition first.

### 1.11 Adaptive principles (`docs/adaptive-principles.md`)
- **§1** Read only `LocalYoinWindowInfo`. Containers that yield width (NP panel, detail column) re-provide `forPaneWidth`. The provider stays resident in the tree (`:15-27,85-88`).
- **§2** One window, one bar, owned by the window. Poses are a dp-lerp of the same slots, driven by the host; the component never animates itself (`:29-43`). A new pose is first reduced to the nav endpoints, then the morph is applied on top (`mem/project_bar_idle_state.md:19`).
- **§5/§7** Tier changes never happen mid-spring (`:91-98,108-117`).
- **§8** New `== Wide` checks go into pure functions with unit tests. Multiple back owners are prioritised by gating (`:119-130`).
- **Home tiers:**
  - Bento: `Phone` / `Dense` (Medium, Tabletop) / `Desktop` (Wide, 10 items) / `Landscape` (`HomeEditorialContent.kt:365-379`).
  - JBI columns: 3 / 4 / 6 (`HomeWidgetGrid.kt:62-64,126-130`).
  - The `packWidgetRows` 3-column golden invariant is unit-tested (`:183-187`).
- **Bento crash:** the bento is a fixed `118.dp×fontScale` height because **`IntrinsicSize.Min` crashes on MarqueeTitle** (`mem/project_home_memories_widgets.md:12`).

### 1.12 Shell lookahead hazard (inside `SharedTransitionLayout`)
- Shell chrome must use plain `Row`/`Box` plus hand-written dp interpolation. Banned there: M3 `ButtonGroup` (negative-width crash on a real foldable), `AnimatedContent(SizeTransform)`, and `SplitButtonLayout` (hang, then the "start timeout" kill) (`mem/project_m3_buttongroup_foldable_lookahead_crash.md:10-20`; `mem/project_detail_bottom_bar.md` "lookahead 禁区"; `mem/project_bar_idle_state.md:15`; `ui/component/PlaySplitButton.kt:49-56`).
- Don't rewrite the bar as `FloatingToolbar` "for nativeness" (`docs/handoff/responsive-breakpoints.md` §13, row "组件").

### 1.13 Data and scope
- **YAGNI:** only MVP or explicitly requested features (`AGENTS.md:50-54`; `docs/design.md:15,338-340`). Don't invent features from mockups (sort, counts, keycaps were stripped) (`mem/project_adaptive_baseline.md`, Wide desktop paragraph).
- **Layout model:** the home layout is per profile (`home_layout` PK `profileId`, `sectionsJson`) (`data/local/HomeLayoutPreference.kt:16-20`). The model is `HomeLayout(sections: [section id + enabled])`. Disabled sections are kept. `reconcile()` appends new sections at their defaults (`ui/home/HomeSection.kt:20-103`).
- **Catalog:** exactly Activities, JumpBackIn, RecentlyAdded.
- **Section ids:** stable, never renamed.
- **New sections:** ship as an enum entry plus a render branch.
- **DB changes:** the DB is at v28 (`data/local/YoinDatabase.kt:35`). A bump must extend every builder in `YoinDatabaseMigrationTest` (`mem/reference_room_migration_test_chain.md`).
- **Capability gating:** new remote-backed components gate on `source.capabilities` (`AGENTS.md:107`).
- **Profile separation:** profiles are never cross-sourced (`mem/project_spotify_activities_recently_played.md`).
- **Public Composables** get a `@Preview` (`AGENTS.md:65`).

---

## 2. Soft preferences and taste signals
- **Drag-reveal pattern** (`mem/feedback_drag_reveal_pattern.md:9-17`):
  - one state, with 1:1 finger mapping;
  - an asymmetric rubber band (soft past the closed end, hard clamp at the open end);
  - a velocity-aware settle (past 50%, or at least 1.6 fraction/s);
  - adjacent UI coupled continuously through `graphicsLayer`, never a binary `AnimatedVisibility`.

  The owner called the rewrite "完美".
- **Motion taste** (`mem/feedback_motion_taste.md:12-20`):
  - Touch drags are 1:1, with springs only on release.
  - System back is a full-range **eased** scrub (`backGestureEasing`) followed by a fast settle.
  - "灵动感" comes from a preview with limited commitment, with physics finishing the rest.
  - The mini-player ↔ NP gesture is the owner's gold standard. Don't touch it.
- **Existing editor drag**, validated (`mem/project_customizable_home.md:20-26`; `HomeLayoutEditor.kt:56-65,97-112,184-262`):
  - Rows track the finger 1:1, siblings spring aside at the half-step, and the release spring is seeded with velocity.
  - Invariants: one drag at a time, catch the row in place, `pointerInput` keyed on the section id (not the index).
- **Haptics** (`docs/haptic-feedback.md:12-16,52-67`; `ui/experience/Haptics.kt:16-57`):
  - Use them sparingly, only on state change, confirmation or a boundary. Mapping:

    | Action | Haptic |
    |---|---|
    | Long-press | `LONG_PRESS` |
    | Pick up | `performContextClick` |
    | Each swap | `performTick` |
    | Drop | `performConfirm` |
    | Destructive or failure | `performReject` |
  - The doc says card taps get no haptic, but widget cards do fire `performContextClick` (`HomeWidgetGrid.kt:234-236`).
- **Seams:** put the effect inside each item, never in an overlay. Transition bands stay short ("动画的意义就是省掉那段距离"). The owner rejected "静止时常驻微动" (idle motion at rest) in favour of the afterglow that settles to still (`mem/project_seam_dissolve.md:15-16`).
- **Ambient loops** run only while visible, with no infinite frame callbacks when idle (`mem/project_np_reactive_background.md`, "Why/how"). The Memories 60s auto-rotation is itself up for removal (`mem/project_memories_audit_2026_10.md:87`).
- **Bar poses** are pure Row/Box with `width(dp)` interpolation. Track changes use manual `graphicsLayer` pushes (`mem/project_bar_idle_state.md:10-19`).
- **Visual restraint** (`docs/design.md:16`):
  - "Spotify 参照只取思路": take the idea from references, never copy them (`HomeEditorialContent.kt:369-371`; `mem/project_adaptive_baseline.md`).
  - "要神似，不能一样": a family resemblance, never identical (`mem/project_artist_detail_redesign.md`).
  - Mobbin research is an accepted input (artist page R3).
- **Official M3E components are welcome where safe:** the owner approved real SplitButton and FloatingToolbar in detail Activities (`mem/project_m3_buttongroup_foldable_lookahead_crash.md:22`). Expressive must not be silently swapped for standard M3 (`AGENTS.md:46-48`).
- **Screen-state convention:** `AnimatedContent(contentKey = it::class)` with the YoinMotion fade pair (`mem/project_ui_review_sweep_decisions.md:18`).

---

## 3. Rejections a new proposal must not repeat
| Rejected | Source |
|---|---|
| Moving Home customization into Settings (the inline edit mode was chosen) | `mem/project_customizable_home.md:10` |
| Bringing back the `memory_teaser` / `memories` sections ("永远删掉/合并") | `mem/project_home_memories_widgets.md:10`; `HomeSection.kt:17-18` |
| Raw `DropdownMenu`; hand-rolled menu shapes | `mem/feedback_m3_expressive_menus.md:13,17` |
| M3 `ButtonGroup` in the shell nav; `pressSqueeze` scale-down press ("动画不对"); `AnimatedContent` in the bar | `mem/project_m3_buttongroup_foldable_lookahead_crash.md:10,18`; `mem/project_bar_idle_state.md:15` |
| Rewriting the bar as `FloatingToolbar` | `docs/handoff/responsive-breakpoints.md` §13 |
| Borders or outlines on covers ("真的很丑") | `mem/feedback_no_cover_borders.md:10` |
| Mid-page clipping; linear fades ("生硬/像白雾") | `mem/feedback_no_midpage_truncation.md:10,25` |
| Overlays at seams: gradient, progressive blur, colour band (8 variants, A–H); idle motion at rest | `mem/project_seam_dissolve.md:15-16` |
| Raw linear back progress; capped preview plus a chase coroutine; heavy scale-in pushes ("太厚重"); cover shared-element flights | `mem/feedback_motion_taste.md:12-13,18` |
| The `PullToDismissState` bridge with 0.45 resistance and binary visibility | `mem/feedback_drag_reveal_pattern.md:15-17` |
| Press morph on artist portraits, Album Avg Bun, Follow Bun, AskGemini seal; seal press morph | `mem/project_shape_semantic_tokens.md:19`; `mem/project_memories_audit_2026_10.md:85` |
| `fromSeed` bento colours; bento with no background ("还是要有底的") | `mem/project_home_memories_widgets.md:12` |
| Recently Added fill/weight rows or a hollow centre ("DON'T re-litigate") | `mem/project_home_memories_widgets.md:16` |
| Uniform tiles for Medium density (Spotify-style) | `mem/project_adaptive_baseline.md`, step 4 ② |
| Inventing features from mockups | `mem/project_adaptive_baseline.md`, Wide paragraph |
| Listing-style, information-dumping UI | `mem/project_settings_service_setup.md` |
| `OutlinedTextField` / stock-form look | `mem/project_note_moment_anchors.md` |
| Uniform `spacedBy` in artwork and text stacks; M3 default tracking | `mem/project_type_tracking_gsf.md:12,18` |
| Global high refresh rate | `mem/reference_variable_font_and_refresh_rate.md` |
| Detail pages back in the single Activity; Activity Embedding for shell ↔ detail | `mem/project_detail_bottom_bar.md`; `docs/adaptive-principles.md:146-153` |
| The "Nothing Playing" entry (retired) | `mem/project_bar_idle_state.md:11` |

---

## 4. Open tensions an edit mode must resolve
1. **Root back vs. exiting edit mode.**
   - A gated `BackHandler` is sanctioned (§1.2), but the edit state is invisible to `ShellBackResolver`.
   - Decide whether to add a `ShellBackOwner.HomeEdit` (ranked NP > DetailPane > Memories? > HomeEdit) or keep the `suppressBackHandling` gate and add Memories to it.
   - Decide whether exit gets a predictive preview: an eased scale or wiggle-amplitude scrub through a back-infra wrapper. That touches AGENTS.md:164 ("no local predictive surface on root") against SKILL.md:66.
   - While editing, system back-to-home is necessarily suppressed.
2. **Now Playing priority.**
   - NP's handlers must win (`YoinNavHost.kt:823-826`), and the bar stays visible during edit.
   - Tapping the pill during edit may open NP, or the Medium/Wide side panel, which narrows Home. Home must re-tier through the resident `forPaneWidth` provider, and only at rest.
   - Decide whether the pill is disabled, or edit exits on NP open.
3. **Long-press vs. card clickables vs. scroll.**
   - Entry is `detectTapGestures(onLongPress)` on the `LazyColumn` (`HomeEditorialContent.kt:322-332`). Its comment says the press passes through cards.
   - Memory says long-press **is swallowed by card `clickable`s** (`mem/project_home_memories_widgets.md:14`). That fits Compose: `clickable` consumes the down in the Main pass before the ancestor sees it. Today long-press therefore works only on gaps and headers.
   - Card-level `combinedClickable(onLongClick)` would collide with:
     - the backdrop press morph to Triangle, which plays during the hold;
     - the Memories pull-down nested scroll at the top (`:166-205`), which must be disabled in edit mode;
     - horizontal shelves (Recently Added `LazyRow`);
     - LazyColumn scroll. The pattern for claiming a child drag is in `mem/project_np_dismiss_draggable_steals_child_drags.md`.
   - The Library tab long-press (catalog search) is a bar-level precedent (`docs/design.md:250`; `YoinButtonGroup.kt:330-334`). The Home tab has no long-press (`:341-345`).
4. **Wide and two-column layouts.**
   - The movable unit and its geometry differ by tier: JBI 3/4/6 columns; bento Phone/Dense/Desktop/Landscape. Wide Home is unclamped with 32dp gutters.
   - When a detail column is open, the shell column is 600 wide and reads Medium, and the bar is in the merged pose. An edit toolbar must be another **pose of the one bar** (§1.11), not a second floating bar. It also cannot use FloatingToolbar or ButtonGroup inside the shell's lookahead (§1.12).
   - The editor today is unclamped on tablets (`HomeLayoutEditor.kt:115-121`).
5. **Perpetual wiggle vs. house taste.**
   - An endless iOS jiggle is an idle loop with frame callbacks. It clashes with:
     - "静止时完全不动" (design.md:82);
     - the rejection of idle motion at rest (`mem/project_seam_dissolve.md:16`);
     - visible-only loops;
     - no global high refresh;
     - seam lattice stillness near the bar.
   - A looped spring has no native form, and `infiniteRepeatable(tween)` breaks AGENTS.md:40.
   - It must stop under `AdaptiveReduced` and "remove animations".
   - Needs owner taste input: a one-shot settling shiver, or wiggle only on the picked item, versus a continuous jiggle.
6. **What can actually move.**
   - Only **sections** are persisted and user-orderable. The page header is pinned chrome (`HomeEditorialContent.kt:349-361`).
   - Bento entries come from play history or Spotify recently-played. JBI cells are generated, persisted pools (deterministic, 6h rotation).
   - Moving or removing individual cards would need a new model. That is a YAGNI and owner question.
   - "Remove" today means hide, since disabled sections are kept. "Add" would re-enable hidden sections. "Reset" means `HomeLayout.Default`.
7. **Empty sections.** JBI and Recently Added are hidden when empty (`HomeEditorialContent.kt:418,442-444`), so an enabled-but-empty section has no tile to grab in edit mode.
8. **List editor vs. in-place mode.**
   - Phase 2 shipped a separate list editor: `AnimatedContent` swap, scroll position reset (accepted), Switch rows.
   - A launcher-style in-place mode would replace it, so confirm with the owner.
   - Smaller issues in the current editor:
     - It has no seam tide or dissolve.
     - It uses ellipsis where the single-line marquee rule applies (`HomeLayoutEditor.kt:270-283`).
     - It uses a hardcoded 108dp inset.

---

## 5. Material3 version and available Expressive components
- **Effective version: `androidx.compose.material3` 1.5.0-alpha16.**
  - `gradle/libs.versions.toml:9` sets `material3Expressive = "1.5.0-alpha16"` on the same artifact that BOM `2026.03.01` (`:8`) supplies, and Gradle resolves to the higher version. This is inferred.
  - It is the only version in the gradle cache.
  - `docs/design.md:137,150` still says "1.4.x stable + 1.5 alpha opt-in".
- **Other dependencies:**
  - graphics-shapes 1.1.0 (`libs.versions.toml`, `graphicsShapes`; design.md:140 says 1.0.x).
  - material3-adaptive 1.2.0.
- **Theme:** `MaterialTheme(motionScheme = MotionScheme.expressive(), shapes = YoinShapes, typography = YoinTypography)` (`ui/theme/Theme.kt:90-106`).
- **Opt-in:** `@OptIn(ExperimentalMaterial3ExpressiveApi::class)` in 17 files.

I confirmed the components below with `javap` on the alpha16 `classes.jar`.

| Component (alpha16) | Used in Yoin? | Notes for an edit mode (Done / Add / Reset, badges, morphs) |
|---|---|---|
| `HorizontalFloatingToolbar` / `VerticalFloatingToolbar` (+ WithFab overloads), `rememberFloatingToolbarState`, `FloatingToolbarDefaults` | **Not used.** 0 hits; retired with the 2026-07-16 detail bar. | Was owner-approved in detail Activities. Lookahead behaviour inside the shell is unverified, and the handoff bans rewriting the bar with it. |
| `ButtonGroup` (+ overflow menu state), `ButtonGroupDefaults.ExpandedRatio`, `Modifier.animateWidth` | Yes, NP only: `FullscreenTabGroup.kt:41`, `PlaybackControls.kt:162`, `BottomPills.kt:105`, `LyricsActionBar.kt:52` | **Banned in shell chrome** (foldable crash). The shell bar is a hand-written `Row` with press-expand (`YoinButtonGroup.kt:63,198`). |
| `SplitButtonLayout`, `LeadingButton` / `TrailingButton`, `SplitButtonDefaults` | Leading/Trailing in `PlaySplitButton` inside a plain Row. `SplitButtonLayout` is **not** called (`PlaySplitButton.kt:49-56`). | Same lookahead rule applies. |
| `ToggleButton`, `ElevatedToggleButton`, `OutlinedToggleButton`, `TonalToggleButton`; `IconToggleButton` family; `ButtonShapes` / `IconButtonShapes` / `IconToggleButtonShapes` (press shape morph) | Not used. `PanelToggleButton` is custom (`NowPlayingOverlayHost.kt:659`). `FilledIconButton` and `IconButtonDefaults` are used. | Fits show/hide toggles. Expressive pressed-shape morph comes for free. |
| `Small/Medium/Large(Extended)FloatingActionButton`, `animateFloatingActionButton`, `FloatingActionButtonMenu` + `ToggleFloatingActionButton` | `ExtendedFloatingActionButton` only (`LibraryScreen.kt:1462`, scroll-aware "New playlist" with `YoinSymbols.Add`) | Precedent for an Add affordance in a shell section. |
| `Badge` / `BadgedBox` | Not used. `UnavailableTrackBadge` is custom (`ui/component/UnavailableTrackBadge.kt:35`). | Possible base for a remove or hide badge (needs a symbol, §1.10). |
| `MaterialShapes` + `toShape`; `Morph` (graphics-shapes) | Yes: `HomeWidgetGrid.kt:416-426`, `AlbumDetailComponents.kt:255`, `MemoriesScreen.kt:1271`, `ServiceSetupScreen.kt:337`; press morph in `PressShapeMorph.kt:37-51` | Respect the press-morph scope ruling. A new `Shape` per progress frame is the official pattern. |
| `LoadingIndicator` / `ContainedLoadingIndicator` | Yes, through the `YoinLoadingIndicator` wrapper (`YoinLoadingIndicator.kt:25`) | Use the wrapper. |
| `LinearWavyProgressIndicator` | Yes (`WaveProgressBar.kt:265`) | — |
| `VerticalDragHandle` (DragHandle API) | Yes (`navigation/pane/DetailPaneHost.kt:314`) | Possible reorder grip; the editor currently uses `YoinSymbols.DragHandle`. |
| `DropdownMenuPopup` / `DropdownMenuGroup` / `MenuDefaults.groupShapes` | Yes, only through `YoinMenu.kt:53,60,81` | Mandatory route for any edit-mode menu. |
| `MotionScheme` (expressive / standard) | Yes (`Motion.kt:86-87`, `Theme.kt:90`) | Use `YoinMotion` wrappers. |
| `ShortNavigationBar`, `WideNavigationRail`, `AppBarRow` / `AppBarColumn` (overflow) | Not used | Shell nav is custom by design; don't swap it. |
| M3 `Switch` | `HomeLayoutEditor.kt:285` only | Current show/hide control. |

**Bottom line for the toolbar:**
- A Done / Add / Reset toolbar inside the shell window is safest as a **new dp-lerped pose of `YoinButtonGroup`/`YoinChromeGroup`**, built from M3 leaf buttons (`FilledIconButton`, ToggleButton, Leading/Trailing) inside plain `Row`s. This follows `mem/project_bar_idle_state.md:19` and `docs/adaptive-principles.md:29-43`.
- Avoid using `ButtonGroup`, `SplitButtonLayout` or `FloatingToolbar` containers there unless they are first verified on a real foldable under the shell lookahead.