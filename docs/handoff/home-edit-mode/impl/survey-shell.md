# Shell map for Home edit mode (HEAD, read-only)

Paths are relative to `app/src/main/java/com/gpo/yoin/` unless noted. The spec's bar line refs (YoinButtonGroup `:304/:318-337/:341/:470/:557/:573/:588/:589`, EdgeSplit `:137-151`) still match HEAD. Its NavHost refs are off by about 1–8 lines; corrected numbers are used below.

## 1. Back ownership

**Current state**
- The enum is at `ui/navigation/back/ShellBackResolver.kt:6-12`: `ShellBackOwner { None, Memories, DetailPane, NowPlaying }`.
- The resolve order is at `:28-36`:
  1. `showNowPlaying` → NowPlaying
  2. `detailPaneOpen` → DetailPane
  3. `selectedSection == HOME && homeSurface == Memories` → Memories
  4. otherwise None
- It is computed once per shell composition at `YoinNavHost.kt:441-446`. Inputs: `selectedSection` :326, `homeSurface` :327, `showNowPlaying` :328, `paneOpen` :392 (`paneHasEntries && !paneState.closing`).

| Owner | Where its handler is registered | Gating |
|---|---|---|
| NowPlaying | `ui/nowplaying/NowPlayingOverlayHost.kt:263-278` (3 BackHandlers) and `:286-336` (3 PredictiveBackHandlers). The host is composed at `YoinNavHost.kt:1091-1107` on the root dispatcher for the whole shell lifetime. | `expanded` (= showNowPlaying), with mutually exclusive levels. Not gated by `shellBackOwner`, but equivalent because NP is first in the resolver. |
| DetailPane | Child dispatcher `paneBackOwner = rememberNavigationEventDispatcherOwner(enabled = owner == DetailPane)` at :452-454. It is provided to the column content (:1050-1055, NavDisplay stacked pop) and to `DetailPaneCloseHandler` (:1082-1088, `enabled = owner == DetailPane && paneStack.size == 1`; the PredictiveBackHandler is at `ui/navigation/pane/DetailPaneHost.kt:248`). | Owner-gated |
| Memories | `YoinNavHost.kt:882` `BackHandler(enabled = owner == Memories) { closeMemories() }`. It sits inside `if (memoriesMounted)` (:881) in the HOME branch; `memoriesMounted` is defined at :411. | Owner-gated, and conditionally mounted |
| Legacy editor | `ui/home/HomeScreen.kt:94`, gated by `suppressBackHandling = owner == NowPlaying \|\| owner == DetailPane` (`YoinNavHost.kt:842-843`) | — |

Two leftovers in `YoinNavHost.kt`: the import of `PredictiveBackHandler` at :5 is unused, and `val memoriesActive` at :506 is unused.

**Resolver change** (the signature does not change, so the call site at :441 needs no edit; update the KDoc at :14-22):
```kotlin
enum class ShellBackOwner { None, Memories, DetailPane, HomeEdit, NowPlaying }
= when {
    showNowPlaying -> ShellBackOwner.NowPlaying
    selectedSection == YoinSection.HOME && homeSurface == HomeSurface.Edit -> ShellBackOwner.HomeEdit
    detailPaneOpen -> ShellBackOwner.DetailPane
    selectedSection == YoinSection.HOME && homeSurface == HomeSurface.Memories -> ShellBackOwner.Memories
    else -> ShellBackOwner.None
}
```
`paneBackOwner` (:452) then disables itself while HomeEdit owns back, so the column's pop and close handlers go inert automatically.

**Where the HomeEdit BackHandler goes**

`BackHandler(enabled = shellBackOwner == ShellBackOwner.HomeEdit) { homeEdit.commitAndExit() }`

- **Recommended: shell level.** Place it after the `CompositionLocalProvider(... paneBackOwner) { DetailPaneCloseHandler }` block (ends :1088) and before `NowPlayingOverlayHost(` (:1091), inside the outer Box (:763). That spot is outside DetailColumnsLayout's `shell={}`/`pane={}` lambdas, outside `AnimatedContent(selectedSection)`, outside `if (memoriesMounted)`, and on the root dispatcher.
- **Spec's option, also valid:** `YoinNavHost.kt:880`, right after `HomeScreen(...)` closes (:879) and before `if (memoriesMounted)` (:881). It works because HomeEdit requires HOME, but the handler re-registers each time AnimatedContent rebuilds the HOME branch.
- **Never** put it inside :881-961 or inside the :1082 provider. Inside the provider it would land on the child dispatcher, which is disabled while editing.
- Priority comes from gating, not registration order.

**Deletions, and one signal to keep**
- Delete `HomeScreen.kt:66-70` and `:94`.
- `suppressBackHandling` also feeds `homeCovered` (HomeScreen.kt:115 → HomeEditorialContent.kt:170 → MemoryBubbleOverlay `covered` :543). Replace it with `homeCovered = showNowPlaying || paneOpen`, computed directly.
- Do not derive `homeCovered` from the owner any more: with HomeEdit ranked above DetailPane, `owner == DetailPane` is false while editing beside an open column.

**Tests**
- `ShellBackResolverTest`:
  - `should_returnHomeEdit_when_editingOnHome`
  - `should_keepNowPlayingFirst_when_homeEditingBesideThePanel`
  - `should_giveHomeEditBack_when_theDetailPaneIsAlsoOpen`
  - `should_ignoreEditSurface_when_libraryIsSelected` (expects None, or DetailPane when `detailPaneOpen`)
- `ShellDetailOriginTest`: `should_keepBottomBarHandoff_whenHomeEditing`. No code change is needed in `ui/detail/ShellDetailOrigin.kt:8-10`.

## 2. Hoisting, HomeScreen inputs, HomeSurface

**How HomeSurface is used today**
- Defined at `ExperienceSessionStore.kt:13-16` as `{Feed, Memories}`; field :36; setter :75-77. The store is app-scoped: it survives Activity recreation but not process death.
- Readers:
  - `YoinNavHost.kt`: :406 (reveal initial value), :411 (`memoriesMounted`), :444 (resolver), :510-514 (`dismissMemoriesIfActive`), :746-755 (reveal driver)
  - `ShellDetailOrigin.kt:8-10` (`hasOverlayHidingBottomBar`, used at :581 and in `DetailBottomBar.kt:337-354`)
  - `ShellBackResolver.kt:31`
  - debug `ShadowReturnAuditActivity.kt:80`
- Writers: :512, :737 (`closeMemories`), :846/:852/:856 (open Memories), and the bar clicks at **:1253, :1263, :1273**. The bar clicks write Feed unconditionally, so they would clobber Edit. They must be unreachable while editing (§3).
- **Compile break:** the `when (homeSurface)` at :747 is the only `when` over HomeSurface. A non-exhaustive enum `when` is a compile error, so add a `HomeSurface.Feed, HomeSurface.Edit ->` branch (the reveal stays closed).

**How HomeScreen receives shell state today**

The call is at `YoinNavHost.kt:835-879`; the parameters are at `HomeScreen.kt:61-82`.
- `viewModel` (:246-248)
- `isPlaying`, `playbackSignal`, `activeSongId` (narrow projections :346-362)
- `suppressBackHandling` (→ `homeCovered`)
- navigation and Memories callbacks (:844-860)
- `memoriesRevealState = memoriesReveal` (:854). This is the precedent: a shell-hoisted state that Home reads in draw via a lambda (`HomeEditorialContent.kt:198`).
- `sharedTransitionScope` / `animatedVisibilityScope`, plus `edgeContentPadding`

HomeScreen never sees the store or HomeSurface.

To remove in HomeScreen: `isEditMode` :86, the profile collector :91-93, the BackHandler :94, and `AnimatedContent(isEditMode)` + `HomeLayoutEditor` :238-285 (HomeLayoutEditor's only reference is :253). The current long-press is the LazyColumn `detectTapGestures` at `HomeEditorialContent.kt:363-372`. The `seamTide` Box is :343, and the section keys are at :411/:481/:506.

**Where to put the edit state**
- **Controller:** declare it right after `val shellScope = rememberCoroutineScope()` (:412). It needs `shellScope`; `memoriesReveal` is at :405-411.
  ```kotlin
  val homeEdit = rememberHomeEditController(experienceSessionStore, shellScope, applyLayout = homeViewModel::setHomeLayout)
  val homeEditing = selectedSection == YoinSection.HOME && homeSurface == HomeSurface.Edit
  ```
  `homeEditing` can replace the unused `memoriesActive` (:506). Both must be declared before the lifecycle observer (:539) and the navigate lambdas (:588).
- **P:** put it in the store next to `shellBarChromeMorph` (:57) or `detailBackProgress` (:65). I recommend `mutableFloatStateOf(0f)` driven by a tracked Job with `animate()` (the `RevealState.launchAnimateTo` pattern, `RevealState.kt:109-123`), so `snapExit()` is synchronous: write Feed and P = 0 in the same call.
  - The exits that need a same-call snap are ON_STOP, section switch, and every snap that must happen before `armDetailChrome()`.
  - An `Animatable.snapTo` is suspend and goes through MutatorMutex, which gives a one-frame lag.
- **One settle owner:** mirror :746-755 with a reconcile `LaunchedEffect(homeSurface)` that animates P to 1 on Edit and to 0 otherwise, with the same "already at endpoint" guard.
  - `enter()` and `commitAndExit()` write only the discrete surface; `snapExit()` writes surface and P together.
  - This also heals P if the shell is disposed mid-spring (same reason as `DetailBackEntering.kt:215-219`).
  - `commitAndExit` needs an awaitable, e.g. `snapshotFlow { P }.first { it <= 0.001f }`.
  - For the P1 scrub, follow the NP stage rule: the back handler only snaps during the gesture.
- **Small state stays out of the session state:** undo depth, tray count, left-slot kind and the drag state belong in controller snapshot state, not in `ExperienceSessionState`. `collectAsState` at :325 recomposes the whole YoinShell on every store update.
- **HomeScreen gets new inputs:** `homeEdit` (or `editing: Boolean` + `editProgress: () -> Float` + `onEnterEdit(origin)`), passed down to HomeEditorialContent. Never pass P as a Float value.

## 3. The bar

**Structure**
- `FloatingBottomBar.kt:85-116` is a BoxWithConstraints, i.e. a subcomposition: `barWidth` is invoked at :87 and `content` at :112. Every per-frame pose (chrome, pane, idle) is read there through `geometry(slotInner)`.
- In YoinButtonGroup:
  - `idle = currentTrackTitle == null && connectionErrorMessage == null` (:221); `idleProgress` (:222-226)
  - `navOnlyProgress` (:180-184)
  - selection aspects at :187-196 (1.5 selected / 1.0 otherwise)
  - press deltas at :201-212 (expand 0.25, neighbour squeeze 0.12, minimum 0.7)
  - the geometry lambda at :232-246

**`resolveBarGeometry` (:557-625)**

Signature: `(slotInner, morph, pane, idle, navOnly, homeAspect, libraryAspect, buttonHeight, centered, promotedCount, mergedCount)`.

- `idleWeight = idle·(1−navOnly)·(1−pane)` (:573)
- `idleHalf = (slotInner−2gap)/2` (:576)
- `home/libraryWidth = lerp(rest, idleHalf, idleWeight)` (:577-578)
- `rightMerged = gap+156+(h+gap)·mergedCount` (:579)
- `fixedWithoutPill` (:583-587)
- `collapsePill = max(navOnly, idle·pane)` (:588)
- `pillComposed = (idle<0.995 && navOnly<0.995) || morph>0.005` (:589)
- `laidInner` (:590)
- `leftWidth = lerp(lerp(home, split, morph), home+gap+library, pane)` (:600)
- `rightWidth = lerp(lerp(gap+library, 0, morph), rightMerged, pane)` (:604)
- `surfaceInner = pillComposed ? laidInner : left+gap+extras+right` (:608)
- The pill takes the remainder, clamped at 0 (:292-293).

**How the idle pose works**
- **Nav pose:** both halves grow to `idleHalf`, so the pill remainder is exactly 0. At idle ≥ 0.995 the pill is not composed and the surface hugs the slots.
- **Pill fade:** pill alpha is `1−max(g.idle, g.navOnly)/0.5` (:305), forced to 1 when `morph>0.005` (:455).
- **Merged pose:** `idleWeight = 0` (resting widths) and `collapsePill = idle`.
- **navOnly:** icons only, pill not composed.
- **Press feedback:** press-expand has no effect in the halves, because `idleWeight = 1` overrides the aspect.

**Labels**

`idleLabelAlpha = clamp((idleWeight−0.55)/0.45)` (:304). The Home label is composed only while > 0.01 (:369-377). The nav Library passes `labelAlpha = idleLabelAlpha` (:475); the merged twin passes `0f` (:324), so it is icon-only.

**The two Library buttons**
- **Left slot (:309-408):** the merged LibraryButton (:318-339) is composed only while `mergedAlpha>0` (:300, `(pane−0.4)/0.6`). It sits at CenterEnd, *beneath* Home. The Home FilledIconButton (:340-380) is at CenterStart, `morph<0.99`.
- **Right slot (:463-529):** the nav LibraryButton (:469-490) is composed while `navLibraryAlpha>0` (:301). The merged Play split + Shuffle are at :491-528.
- Both Library buttons share `libraryInteraction` (:161). `libraryPressed` shows the **search hint after a 240ms plain press** (:167-175); the overlay is at :256-282.
- `LibraryButton` (:628-680) uses `combinedClickable(indication = null)`.

**EdgeSplit** (no merged pose)
- Own `idleProgress` at :111-116.
- Lower pill capsule: composition gate :139 `idleProgress<0.995 || morph>0.005`; hide :145. It slides out over its own edge (:148-152).
- `UpperCapsuleContent` (:207-359): Home at :253-276; Library Surface + `combinedClickable` at :283-315 (long-click → hint :297-301); no labels.

**YoinChromeGroup**
- Parameters :36-62; EdgeSplit call :92-115; ButtonGroup call :121-148.
- It is also called from `DetailBottomBar.kt:145`, the debug Motion/ShadowReturn audit activities, and `androidTest/.../DetailBarHandoffTest.kt:52`, so every new parameter must default to "off".

**Adding edit**

1. New parameters, added after `paneProgress` (:123), after YoinChromeGroup's `exitProgress` (:57), and after EdgeSplit's `playSplitActions` (:87):
   ```kotlin
   editProgress: () -> Float = { 0f }, editing: Boolean = false,
   editLeftSlot: () -> BarEditLeftSlot = { BarEditLeftSlot.UndoDisabled },
   onEditUndo: () -> Unit = {}, onEditAdd: () -> Unit = {}, onEditDone: () -> Unit = {},
   ```
   `editLeftSlot` is a lambda read inside the bar, so an undo does not recompose YoinShell.

2. **Geometry:** add an `edit` parameter, then:
   ```kotlin
   val idleLike = maxOf(idle, edit)
   val idleWeight = idleLike * (1f - navOnly) * (1f - pane)                       // :573
   val collapsePill = maxOf(navOnly, idleLike * pane)                             // :588
   val pillComposed = (idleLike < 0.995f && navOnly < 0.995f) || morph > 0.005f   // :589
   ```
   Return `BarGeometry(idle = idleLike, edit = edit, …)`; add `val edit: Float` to the class (:539-555), and pass `edit = editProgress().coerceIn(0f,1f)` in :232-246. That gives:
   - nav pose `[Undo|Add][Done]` with labels;
   - merged pose `[Undo][Done][Play▾][Shuffle]` with the pill folded;
   - navOnly: icons only.

3. **Cross-fade value:** near :304, `editSwap = smoothstep(0.35f, 0.65f, g.edit)`. There is no shared smoothstep helper (the one in `SeamCookie.kt:227` is private).

4. **Home slot (:340-380):** keep one FilledIconButton and stack two content Rows inside it.
   - Home Row: alpha `1−s`, composed only while `s<0.99`.
   - Undo/Add/disabled Row: alpha `s` × each kind's fastEffects alpha (the prototype's `slotA`, `proto.js:279`, `:1288-1294`), composed only while `s>0.01`. Labels are multiplied by `idleLabelAlpha`.
   - Click: `if (editing)` call Undo or Add (`performClick`) and do nothing for disabled; otherwise `onHomeClick()`.
   - Container: `lerp(homeContainerColor, surfaceContainerHighest, s)`.
   - Disabled Undo: either set `disabledContainer/ContentColor` to the same lerped values, or keep the button enabled with a no-op click and `semantics { disabled() }`.
   - TalkBack: give the inner icons `contentDescription = null` and set the button's description from the discrete state.

5. **Both Library buttons (:319 and :470):** extend `LibraryButton` with `editSwap` and a nullable `onLongClick: (() -> Unit)?`.
   - Container: `lerp(libraryContainerColor, colors.primary, s)`. The prototype tints the background with raw P (`proto.js:2052`).
   - Content colour: lerp to `onPrimary`. Stack a Library Row and a Done Row (`YoinSymbols.Check` + "Done").
   - Click: `if (editing) { performConfirm(); onEditDone() } else {...}`; `onLongClick = if (editing) null else {...}`.

6. **Gates:**
   - Change :167 to `if (libraryPressed && !editing)` and clear `showLibrarySearchHint` when editing starts.
   - Pill (:447): `onClick = if (editing) ({}) else onNowPlayingClick`. Between P 0.5 and 0.995 the pill is invisible but still composed and tappable.

7. **EdgeSplit:**
   - Use `idleLike = maxOf(idleProgress, edit)` in the gate (:139) and in the hide (:145).
   - In `UpperCapsuleContent`, cross-fade the icons (Home ↔ Undo/Add at :253-276, Library ↔ Check at :283-315), lerp the Library container to `primary`, and set `onLongClick = null` at :297 while editing.
   - Gate the lower pill's `onClick` (:163) the same way.

8. **Shell wiring (:1223-1292):** pass `editProgress = homeEdit.progress` (a stable remembered reader), `editing = homeEditing`, `editLeftSlot = homeEdit::leftSlot`, `onEditUndo`, `onEditAdd` (a scroll-to-tray request token that Home collects, since `listState` lives in HomeEditorialContent), and `onEditDone = { homeEdit.commitAndExit() }`. Leave `onHomeClick`/`onLibraryClick`/`onLibraryLongClick` (:1251-1274) untouched; the bar routes away from them while editing.

9. **Do not use** AnimatedContent, Crossfade, ButtonGroup, FloatingToolbar or SplitButtonLayout (KDoc :58-66). Colours are local lerps only, never a new ColorScheme.

Three details need a decision:
- **Done is narrower than Undo in the merged pose:** Undo is 1.5h (66dp) and Done 1.0h (44dp), because Home is the selected section. If Done should be the emphasized one, override the aspect targets while editing; the spec is silent.
- **Done has no press feedback in the halves:** `indication = null` and press-expand is overridden there. The prototype has a press shape morph (`proto.js:484-485`), and the Pixel Tablet has no vibration motor.
- **The pill fade differs from the prototype:** the idle path gives `1−P/0.5`, the prototype uses `1−smoothstep(0, 0.6, P)`.

**BarGeometryTest cases to add**

The helper (:12-30) gains `edit: Float = 0f`. With the test's values: Home 66, Library 44, slot 580, `rightMerged` 216.
1. `should_matchTheIdlePose_when_editingWithATrackPlaying`: for e ∈ {0, 0.5, 1} × pane ∈ {0, 0.5, 1} × navOnly ∈ {0, 1}, `geometry(edit=e)` equals `geometry(idle=e)` on every width, `idleWeight` and `pillComposed`.
2. `should_beIdempotent_when_editingWhileIdle`.
3. `should_splitTheBarIntoTwoHalves_when_editingInTheNavPose`: halves are 282, surface 580, not composed, `idleWeight` 1.
4. `should_foldThePill_when_editingInTheMergedPose`: surface 342, `idleWeight` 0.
5. `should_showIconOnlyHalves_when_editingBesideTheNowPlayingPanel`: surface 126, `idleWeight` 0.
6. Spec matrix: edit ∈ {0, 0.5, 1} × idle × pane × navOnly. When the pill is not composed, surface == slots; otherwise `min(surfaceInner, slotInner) − slots ≥ 0`.
7. `should_neverLeaveAHole_when_editSprings`: 21 steps at pane ∈ {0, 1}. Spot checks: edit 0.5 in the nav pose gives a pill of 227; edit 0.5 merged gives `laidInner` 461 and a pill of 119.
8. `should_neverLeaveAHole_when_editingAndTheColumnSprings`: edit = 1, pane 0→1 (mirrors :43-51).
9. `should_reportRawEditAndIdleLike`.
10. `should_keepThePillComposed_when_detailChromeRunsDuringEdit`: edit = 1, morph = 0.5. This documents why the shell must exit edit before arming the detail chrome.

The existing four tests stay valid.

## 4. Exit triggers

| Trigger | Location | Action |
|---|---|---|
| Section switch | `LaunchedEffect(selectedSection)` :757-761 | Add `if (selectedSection != HOME && homeSurface == Edit) snapExit()`. This is only a backstop: the only section writers are the bar clicks, which are re-routed while editing. |
| Profile switch | Spec: `LaunchedEffect(musicConfigurationRevision)` :422-434 | Problem: that effect re-runs on every fresh YoinShell once revision > 0 (Activity recreation for font scale or locale; the manifest at :39 only handles size, orientation, uiMode and density). It also bumps on credential edits. Both would cause spurious exits. Use `LaunchedEffect(homeViewModel) { homeViewModel.activeProfileId.drop(1).collect { snapExit() } }`, a straight port of HomeScreen.kt:91-93. |
| ON_STOP | Observer at :539-554 | Add `Lifecycle.Event.ON_STOP -> homeEdit.snapExit()`. Detail Activities are `DetailTranslucent`, so the shell never stops under them. Settings stops the shell on Compact but not in a Wide Activity Embedding split. |
| Bar NP open | `onNowPlayingClick` :1256-1259 | The bar already disables the pill while editing; this guard is a backstop. If `npFrame.presentation == Panel`: `shellScope.launch { commitAndExit(); await; setNowPlayingExpanded(true) }`. Otherwise (Phone, Landscape, Tabletop, Enlarged, DualPane all cover Home): `snapExit()`, then expand. Put that predicate in a pure function in `NowPlayingPresentation.kt` with a test (adaptive §8). |
| External NP open | none today | `setNowPlayingExpanded(true)` has only one caller (:1258), and there is no notification→Now Playing intent. |
| Detail as Activity (no column) | Else-branches at :591-602, :607-618, :623-634 | `snapExit()` **before** `armDetailChrome()` (:594/:610/:626). The cross-window bar hand-off needs a pixel-identical nav pose. While editing this is reachable only from the Now Playing panel's links. |
| Detail column (`hasDetailPane`, `WindowAdaptiveRuntime.kt:292`) | `openPane` :473-500, called at :590/:606/:622 | If `homeEditing`: `shellScope.launch { commitAndExit(); await; openPane(route) }`. `requestColumn` (:478) re-tiers the shell at once. |
| Tier-change relaunch | `LaunchedEffect(hasDetailPane)` :642-672 | `snapExit()` before `armDetailChrome()` (:658). |
| Settings | `navigateToSettingsFromShell` :515-518 (the gear, and snackbar actions :702/:717) | `snapExit()`. |
| Not dispatcher-gated: column back arrow / close (`popPaneEntry` :503-505, `closePane` :502), divider drag, Now Playing panel drag/close | — | Each resizes the shell mid-edit (`adaptive-principles.md:146-147`). Either `commitAndExit` first, or rely on Home freezing its tier during edit (`LocalPaneWidthInMotion` :788-793). |

## 5. Hazards

1. **Per-frame values stay in layout and draw.**
   - Never read P in the YoinShell body. The bar reads it only inside its BoxWithConstraints subcomposition, which the adaptive rules sanction (`adaptive-principles.md:132-134`); the cross-fade alphas go in `graphicsLayer`.
   - Home reads P in draw. Routing and composition use the discrete `homeEditing`.
   - Any composition gate on P outside the bar needs `derivedStateOf`.
2. **SharedTransitionLayout lookahead** (`YoinNavHost.kt:173` wraps the whole shell).
   - No exotic measure policies in the bar. Every new width needs `coerceAtLeast(0.dp)`; negative constraints crashed M3 ButtonGroup on a real foldable.
   - Text in shrinking slots: `maxLines = 1`, `softWrap = false`, inside slots that already `clipToBounds`.
   - The pill's shared bounds (`NowPlayingPill.kt:239/279/527`) are disposed while `pillComposed` is false. That is the same path idle already uses. `sharedTransitionScope` is null when `!npSharesCover` (:1282-1291).
   - On the Home side, lookahead measures every layout twice: do not write snapshot state from measure, and avoid intrinsics around MarqueeText (known crash; the spec's tray rows use MarqueeText).
3. **Invisible but tappable targets:** the pill, the EdgeSplit lower capsule and the composed-but-invisible content rows. Disable their clicks while editing. Visuals lag the routing by design (entry looks like Home while already routing to Undo for P < 0.35).
4. **Detail chrome and edit must never coexist:** `morph` forces the pill to compose, and the window hand-off needs identical bars. Always `snapExit()` before every `armDetailChrome()`.
5. **Root-back rule:** the P0 BackHandler is shell-level and enabled only for HomeEdit, so back-to-home is preserved otherwise. The P1 scrub needs the AGENTS.md / SKILL.md exception and must use `YoinMotion.backGestureEasing` (Motion.kt:100) with `stageSettleSpring` (Motion.kt:258), not `BackMotionTokens.PopPageScaleTarget`.
6. **Edit beside an open Now Playing panel:** the owner is NowPlaying, so back closes the panel first and the next back exits edit. The bar shows navOnly + edit, i.e. icons only.
7. **Missing Undo icon:** `../yoin-symbols` has no Undo; it has Add, Check, DragHandle and Refresh.
