Plan is mostly sound, but it needs these fixes before agents start. Ordered by severity.

**Blocking (P0 breaks end-to-end, or the strip feel deviates from proto)**

1. **Carry silently drops the new order when a placeholder above the held block is still deferred.**
   - **Problem:** the plan builds the carry's section list from what is rendered on screen. A placeholder for an empty section above the held block is not rendered yet when the carry starts, so `commitOrder(ids)` gets an incomplete list. `withEnabledOrder` then returns `this` and the reorder is lost. This happens when JBI or Rediscover is empty and enabled above the dragged block, and the user long-presses and drags in one motion.
   - **Evidence:** plan C7 (`displayedOrder(): enabled, rendered, excluding hiding`); WP3-B step 1 reads `ids` before setting `placeholdersAboveOpen = true`; C1 (`this` if not a permutation); `HomeEditorialContent.kt:480,504-505` (JBI and RA items only exist when they have data).
   - **Fix:** set `CarryHost.displayedOrder() = controller.draft!!.enabledSections`, from data and never from rendering. A section with no layout info gets the off-screen B_i, with `isAbove` taken from its index relative to the visible sections. Add `should_passFullPermutation_when_carryStartsWithDeferredPlaceholderAbove`.

2. **The surface heal closes Memories, and Memories can still open while editing.**
   - **Problem:** the heal calls `snapExit()`, which writes the surface back to Feed. Any path that sets Memories during edit therefore has Memories killed by the heal. Such paths exist today: TalkBack on the faded arrow, and the NH callbacks.
   - **Evidence:**
     - plan WP2-B step 4, and C4 `snapExit` ("Surface to Feed");
     - `YoinNavHost.kt:845-857` writes `HomeSurface.Memories` directly;
     - `HomeMemoryBubble.kt:615-626`: the arrow's semantics `onClick` stays enabled whenever `!scrolledAway`, and WP3-D only nulls `onArrowTap`.
   - **Fix:**
     - Add `HomeEditController.onSurfaceLost()`, which resets controller, VM freeze and layer state without writing the surface. Use it in the heal.
     - In NH, make `onNavigateToMemories`, `onOpenMemoryFocus` and `onCommitMemoriesReveal` no-ops while `homeEdit.isEditing`.
     - In WP3-D, pass `enabled = !scrolledAway && !editing` to `MemoryArrow`.

3. **Layout operations are not blocked during a carry.**
   - **Problem:** the bar sits outside Home's pointer Box, so a second finger, or a TalkBack action, can fire hide, show, reset, undo or move mid-carry. That corrupts the draft under the strip session.
   - **Evidence:** proto blocks all five while a carry exists (`proto.js:1241,1250,1259,1265,1270`); C4 has no such check.
   - **Fix:** in C4, `hide`, `show`, `reset`, `undo`, `move` and `barLeftSlotClick` return `false` while `layer?.isCarrying == true`, with no haptic and no undo push. Add `should_ignoreLayoutOps_when_carrying`.

4. **A gesture still in progress is not abandoned when edit ends, and a lifted block is not dropped.**
   - **Problem:** if Done or back arrives while a finger is in Holding, the `bodyHold` timer then lifts a block in normal mode.
   - **Evidence:** proto `exitEdit` calls `disownGesture()` and `dropLift(0)` (`proto.js:1016,1022`; snap path at 1039). The plan's `onExit`/`onSnapExit` only touch the latch, envelope and `heldSection`.
   - **Fix:**
     - `onExit` calls `engine.dropLift(0)`; `onSnapExit` snaps lift to rest. Both release the press.
     - When edit ends mid-gesture, the gesture modifier moves the machine to `Done` and consumes the rest.
     - The action executor drops `Lift`, `StartCarry`, `BlockTap` and `ExitBlank` when not editing.
     - Add `should_disownGesture_when_editEndsMidHold`.

5. **HomeCarryStack draws in the wrong order (must-keep animation).**
   - **Problem:** `drawBehind` paints before all children, so the carried plate ends up under its own shadow and under every label.
   - **Evidence:** WP3-B step 10; proto's order is others 1, shadow 2, carried plate and label 3 (`proto.js:1410-1412`).
   - **Fix:** the parent's `drawBehind` paints only the hole and the non-carried plates. Children then go in this order: non-carried labels (z 0), carried shadow (z 1), carried plate as its own child with its own `drawBehind` (z 2), carried label (z 3).

6. **Neighbour make-way snaps to the wrong value (must-keep).**
   - **Evidence:** `proto.js:1423` uses `s.snapTo(s.value + dir*pitch)`; WP3-B step 2 uses `snapTo(+dir·pitch)`, which jumps on a quick swap-back.
   - **Fix:** `stripOffset[nb].snapTo(stripOffset[nb].value + dir*pitch)`, then animate to 0. Add `should_keepNeighbourScreenPosition_when_swappedBackMidFlight`.

7. **Re-grab snaps the hole instead of animating it (must-keep).**
   - **Evidence:** `proto.js:1525` animates `holeY` on fastSpatial; WP3-B step 6 says "Snap holeY".
   - **Fix:** `holeY.animateTo(slotY(k), specs.holePx)`.

**Should fix before Stage 4**

8. **Carried strip colour.**
   - **Evidence:** `proto.js:2078-2080`.
   - **Fix:** base colour is `lerp(surfaceContainerHigh, surfaceContainerHighest, liftTint)` for the carried strip and `surfaceContainerHigh` for the others. Then `lerp(base, coverBase, tw)` for every strip, the carried one included.

9. **Strip cover tint will mostly be null.**
   - **Evidence:** WP4-A step 7 ("otherwise null"). The cards already populate a palette cache, but it is private (`ExpressiveBackdropPalette.kt:54-62`; synchronous read at `:86`).
   - **Fix:** assign WP3-B to add `internal fun cachedBackdropColors(model: String): ExpressiveBackdropColors?` to that file, and set `StripCover.baseColor = cachedBackdropColors(url)?.baseColor`. This closes PORT §9.2.

10. **`fadeInSpec = null` brings back hard cuts.**
    - **Problem:** today every section fades in when it appears. With `null`, JBI, RA or Rediscover data arriving in normal mode would pop in.
    - **Evidence:** `HomeEditorialContent.kt:453,470,490,521` all use `effectsSpring()`; plan §2.5 sets `null` for non-placeholders.
    - **Fix:** use `null` only for sections in a motion-owned `showing` set, where `hideA` drives the fade. Everything else, normal mode included, keeps `specs.effectsIn`. Keep fade-out at the current effects spec.

11. **Bar plays two haptics in edit.**
    - **Evidence:** `YoinButtonGroup.kt:342-345`, `:326-329` and `:477-480` call `haptics.performClick()` before the handler. C4's `undo()`, Add and `barDone()` add their own click or confirm, and the disabled Undo should be silent.
    - **Fix:** in WP2-A, when `editing`, skip the bar's own `performClick()`. The controller is the only owner of edit haptics.

12. **Spring role is not guaranteed to be Expressive.**
    - **Evidence:** the debug harness calls `HomeEditorialContent` without `ProvideYoinMotionRole` (`MemoriesScreenshotActivity.kt:127`); the default role is Standard (`Motion.kt:40`, `:183-229`).
    - **Fix:** `rememberHomeEditSpecs` passes `role = YoinMotionRole.Expressive` to every getter explicitly.

13. **Some JVM unit tests can't run as specified.**
    - **Problem:** C5's `touch(nowMs = SystemClock.uptimeMillis())` returns 0 in JVM tests (`app/build.gradle.kts:111-120`, `isReturnDefaultValues`), and the frame-clock tests use virtual time. The WP2-D, WP3-B and WP2-B tests also need `HomeEditSpecs`, but only the `@Composable rememberHomeEditSpecs` exists.
    - **Fix:**
      - Inject `uptimeMs: () -> Long` into `HomeEditMotion`, `HomeCarryEngine` and the gesture executor.
      - In WP1-C, add a non-composable `HomeEditSpecs.create(MotionScheme.expressive(), reduced)`. `rememberHomeEditSpecs` delegates to it.

14. **The Undo symbol will break CI.**
    - **Evidence:** `.github/workflows/ci.yml:23-28` and `release.yml:34-39` pin `p2o51/yoin-symbols` at `ref: v0.1.0`. WP1-D never tags or bumps a version, so any `YoinSymbols.Undo` reference fails CI and release builds.
    - **Fix:** WP2-A uses the text-label fallback (or an app-local ImageVector) unless the owner authorizes a symbols release: VERSION bump, tag `v0.1.1` pushed, and both workflow `ref:` lines bumped in the same Yoin commit. Make this part of WP1-D's acceptance.

15. **The release command in §5.4 fails outright.**
    - **Evidence:** `app/build.gradle.kts:33-45` throws a `GradleException` for any Release task other than ReleaseDebugSigned when there is no keystore. It does not produce an unsigned APK.
    - **Fix:** check for `keystore.properties` first. If it is missing, run only `:app:assembleReleaseDebugSigned` and say the upload-signed APK could not be built.

16. **The header hint overlaps "Edit Home" on phones.**
    - **Problem:** the "Edit Home" overlay is zero-width and spills into the hint's slot. The plan only checks fit against the slot. At 360dp, roughly 75dp of overflow + 16dp + ~95dp of hint exceeds the ~174dp slot, so the hint "fits" while overlapping the title.
    - **Evidence:** proto checks `editTitle + 16 + hint ≤ header width` (`proto.js:1000`).
    - **Fix:** `HomeEditHeaderTitle` publishes the measured `editWidth − homeWidth`. `HomeEditHeaderHint` reserves that plus 16dp in its fit check. Extend `should_hideHint_when_itDoesNotFit` to 360dp.

17. **Sections that are fading out after Hide are handled inconsistently.**
    - **Problem:** §2.5 still emits `section-<id>` items for them, and the WP3-A hit tester accepts every `section-` key, yet C7 excludes them from the carry order. Lifting one therefore gives `k = -1`. The anchor index `1 + order.indexOf(dropped)` is also wrong while one of them sits above the dropped block.
    - **Fix:** the hit tester treats them as Blank. The anchor takes the item index from the key list HEC actually emits (published in a `SideEffect`).

18. **Re-enabling a section mid-fade pops.**
    - **Evidence:** proto (`proto.js:1221-1223`) animates a still-displayed block back from its current values; WP2-D step 7 snaps every newly enabled section to `hideA = 0`, `hideS = .96`.
    - **Fix:** if the section is still fading out, remove it from the hiding set and animate `hideA` and `hideS` to 1 from where they are, with no snap and no kick.

19. **Presses during Settle and Unfold are not consumed.**
    - **Evidence:** spec §2.3 ⑪ and `proto.js:1705`. "Otherwise ignore" lets the LazyColumn scroll, which invalidates B_i and the anchor.
    - **Fix:** when the carry is busy and `TryRegrab` fails, consume the whole gesture in the Initial pass until the finger lifts.

20. **The card wiggle node does per-frame work in normal mode.**
    - **Problem:** C6's draw calls `blockTopInBox()`, which reads `listState.layoutInfo`. Every card would redraw on every scroll frame even when nothing wiggles.
    - **Fix:**
      - Return early with `drawContent()` when there is no scope, or when E ≤ .0005 and the block kick and card kick are both 0.
      - Read `layoutInfo` and the safe area only after a non-zero raw angle.
      - Toggle the long-click semantics through `ObserverModifierNode` + `invalidateSemantics()`, or set them at compose time.

21. **Rediscover density leaves N = 3 undefined.**
    - **Evidence:** `FeedUnits.kt` `feedUnitsFor`: non-Wide N is 1–4, Wide is 5–8. The plan's rule skips N = 3, and the spec says Wide → 3.
    - **Fix:** N ≤ 2 → shelf of up to 6; landscape phone or N in 3–4 → 2; N ≥ 5 → 3. If 5–7 → 2 is intended, record it as D10. Add `should_showTwo_when_threeUnits`.

**Low priority**

22. The `controller.layer` dispose guard should be `if (controller.layer === layer) controller.layer = null`. The section `AnimatedContent` can overlap two HEC instances (`YoinNavHost.kt:806-811`).

23. `enter()` should also require `selectedSection == HOME`. HEC stays composed during the section cross-fade.

24. `commitAndExitAndAwait()` returns early when the exit is deferred by a carry. Expose an exit-completed `Deferred` and await it.

25. The shadow child must be sized in the layout phase (`Modifier.layout { … frameFor(..) }`), never with `Modifier.size(dp)` from composition.

26. R10 points at "§2.8 of this plan", which doesn't exist. Add the strings table: Edit Home, Drag to reorder, Hidden, Hidden sections appear here, Reset Home, Hide/Show <title>, Undo, Add, Done, Show hidden sections, Move up, Move down, Hide, Section %d of %d, New, the all-hidden copy and the three placeholder lines.

27. Emit `OpenPlaceholders` on a Lifted cancel and on any finger-up in edit mode. Otherwise placeholders above the held block stay hidden after a cancel.

28. The tray title and the Reset footer also need the fold fade (`feedFoldAlpha`), as in proto (`proto.js:2021`).

29. TalkBack: the "Edit Home" custom action on each section title is missing (spec lines 256-259).

30. The kick-peak test expects the wrong value. For ζ .3, k 450, v = 31.6·a, the first peak is about 1.0005·a at 62.5ms, not 0.9983·a. Use a tolerance of at least .003.

31. On exit, if the draft has the same sections as the latest VM layout, clear the echo hold right away. No write means no echo will ever arrive.

32. `unseenNewSections` must re-emit after `markSectionsSeen`, through an internal trigger flow. SharedPreferences is not observable.

33. The WP3-B `HomeCarryStack` signature has `titles`, but the WP4-A call omits it. Drop the parameter (use `section.title`) or pass it.

34. Anchor skip condition: proto treats the header as gone once it is fully under the status bar (`proto.js:1476-1481`). The plan skips whenever `firstVisibleItemIndex == 0`, which also covers the header's status-bar padding (`HomeEditorialContent.kt:573`). Use "header item bottom ≤ status-bar top" instead.

35. `SeamFlow.held` does nothing for `requestScrollToItem`: the seam flow is only fed by the nested-scroll `onPostScroll` (`SeamDissolve.kt:450-455,563-570`). Keep it if you like, but drop it from risk #3's mitigation.

36. Acceptance wording: Stage 3 is not "unchanged" (the JBI tap haptic is removed), and in Stages 1–3 the old editor shows a Rediscover row. Note both in the stage acceptance and the QA report.