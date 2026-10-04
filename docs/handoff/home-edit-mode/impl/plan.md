# Yoin Home Edit Mode: P0 Implementation Plan

Baseline is HEAD `93b689bb` on `main`, with a clean tree. The plan is written for a small team of coding agents working in **one shared working tree**, in **5 sequential stages**. Within a stage, the work packages (WPs) own **disjoint files**. At the end of every stage the tree compiles and all JVM unit tests pass.

---

## 0. How to use this plan

### 0.1 Reference documents

These are archived into the repo in Stage 0.

| Tag | Document |
|---|---|
| **SPEC §x** | `docs/handoff/home-edit-mode/spec.md`. §8.0 is final. Other sections were written against 2026-10-03 code, so their line numbers are stale. |
| **PORT §x** | Prototype → Compose port sheet, at `docs/handoff/home-edit-mode/impl/port-sheet.md`. |
| **HOME §x / SHELL §x / DATA §x** | Survey reports, at `docs/handoff/home-edit-mode/impl/survey-{home,shell,data}.md`. |
| **proto p:N** | The prototype `proto.js`, at `docs/handoff/home-edit-mode/impl/proto.js`. It was copied from the session scratchpad. The owner loved its feel: "特别好，一定要保留" (keep it, port its parameters as-is). |

**Precedence:** SPEC §8.0, then proto.js behaviour, then PORT, then the rest of SPEC. All px values in proto are **dp**.

Abbreviations used below:
- `HEC` = `ui/home/HomeEditorialContent.kt`
- `HS` = `ui/home/HomeScreen.kt`
- `HWG` = `ui/home/HomeWidgetGrid.kt`
- `HVM` = `ui/home/HomeViewModel.kt`
- `NH` = `ui/navigation/YoinNavHost.kt`

All source paths are relative to `app/src/main/java/com/gpo/yoin/`. Tests live in `app/src/test/java/com/gpo/yoin/…`.

### 0.2 Global rules for every agent

**Ownership and the shared tree**

- **R1 Ownership.** Edit only the files your WP lists. If you need a change anywhere else, do not make it. Write it up in your final report as "needs: <file> <change>", and the lead schedules it.
- **R2 Line numbers drift.** Grep for symbol names, never for line numbers.
  - Other Claude sessions may be editing this tree. Before you edit a file, run `git diff -- <file>` and keep every hunk you did not write.
  - Never `git checkout`, `reset` or `stash` another agent's work.
  - `YoinRepository.kt` reads as binary to grep, so use `grep -a`.
- **R3 Gradle.** Always use `export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"`, and always wrap the call in the shared lock: `lockf -t 3600 /tmp/yoin-gradle.lock ./gradlew <tasks>`.
  - Compile errors in files you don't own are another WP's work in progress. Don't touch them; retry later.
  - Prefer targeted runs, for example `:app:testDebugUnitTest --tests 'com.gpo.yoin.ui.home.edit.*'`.

**Per-frame values and layout**

- **R4 Per-frame values live only in draw, layer, layout and placement lambdas.** This covers `P`, `fold`, `E`, `t`, `charge`, `lift`, `liftTint`, kicks, `settleY`, `holeY`, `stripOffset` and `hideS`/`hideA`.
  - Composition may read only discrete state, or `derivedStateOf { … }` booleans.
  - Never pass P as a `Float` parameter. Pass `() -> Float`.
- **R5 Banned in new Home and edit code:** `AnimatedContent`, `Crossfade`, `SubcomposeLayout`, `IntrinsicSize`, new `ColorScheme` instances, tweens, `animateDpAsState` for per-frame geometry, and raw `PredictiveBackHandler`.
  - Springs come only from `HomeEditSpecs`, `YoinMotion.stageSettleSpring()` or `YoinMotion.homeEditKickSpring()`.
- **R6 Lookahead safety** (a `SharedTransitionLayout` wraps the shell):
  - never write snapshot state from `measure`;
  - every computed width gets `.coerceAtLeast(0.dp)`;
  - Text in slots that shrink uses `maxLines = 1, softWrap = false`.
- **R7 Thresholds on springs.** Every spring that drives an edit value carries its own `visibilityThreshold` via `withThreshold(...)` (PORT §1.3). The `Animatable`'s threshold does **not** apply to a spring passed into it. This is the critical rule for `fold` (0.001).
- **R8 Role trap.** Spatial specs differ between the Expressive and Standard roles.
  - Home-layer specs are resolved once, inside HEC's composition (which runs under Expressive), into a remembered `HomeEditSpecs`.
  - Shell-level code uses only `stageSettleSpring()` and the reduced fast-effects spring, both of which are role-free.

**Conventions**

- **R9 Haptics** go only through `HomeEditFeedback`, which keeps unit tests free of `View`. The Pixel Tablet has **no vibration motor**, so every haptic moment also needs a visual twin (SPEC §2.8).
- **R10 Strings** are hard-coded English, exactly as listed in §2.8 of this plan.
- **R11 Tests:**
  - names follow `should_expectedBehavior_when_condition`;
  - pure logic goes in JVM tests;
  - SharedPreferences and Compose tests use Robolectric, which is already on the classpath (as in `NowPlayingControlsFitTest`);
  - `Animatable` tests use the frame-clock helper from WP1-C.
- **R12** Every new composable gets a `@Preview` (AGENTS.md). KDoc is short English, matching the repo's style.

### 0.3 Stage gate (run by the lead after every stage)

```sh
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
lockf -t 3600 /tmp/yoin-gradle.lock ./gradlew :app:compileDebugKotlin :app:compileDebugUnitTestKotlin :app:compileDebugAndroidTestKotlin
lockf -t 3600 /tmp/yoin-gradle.lock ./gradlew :app:testDebugUnitTest
```

After the build, the lead reviews the diff against the R4–R7 checklist. Useful greps:
- `rg -n '\.value\b' app/src/main/java/com/gpo/yoin/ui/home/edit`, checking that each hit is inside a draw, layer, layout or event lambda;
- `rg -n 'PredictiveBackHandler|AnimatedContent|tween\(' ui/home`.

If commits are authorized for this run, the lead makes **one local commit per stage** and does not push. The post-commit hook builds the debug-signed release, which is expected.

---

## 1. P0 scope

### 1.1 In scope

Each item is SPEC §7 P0 as adjusted by §8.0.

1. **P0-1 Data hardening**
   - Versioned, per-entry lenient decode of `home_layout`.
   - Unknown ids are retained.
   - `appendEnabled` and `newSections` (Q6a(a)).
   - Writes happen only on a real change, and a layout equal to Default clears the row.
   - `HomeEditHintStore`: edit-session count and section-seen ids.
   - Orphan rows are cleaned up when a profile is deleted.
2. **P0-2 Tokens**
   - `homeEditKickSpring`, `PanelAnimated`, four new haptics, and the `Undo` symbol.
   - `HomeEditTokens`, including a debug-only wiggle mode/target override.
3. **P0-3 Session and back**
   - `HomeSurface.Edit`, plus P in the session store.
   - `HomeEditController` hoisted in `YoinNavHost`.
   - `ShellBackOwner.HomeEdit` with priority **NowPlaying > HomeEdit > DetailPane > Memories**.
   - Discrete back = Done with no haptic (**Q8: P0 discrete only**).
   - All exit triggers (SHELL §4).
4. **P0-4 Gestures**
   - An Initial-pass detector over the whole page, margins included.
   - Charge pre-show from T/2 to T.
   - **Q2(a):** long-press on a card enters edit and lifts its section, with the plate growing from the press point.
   - Blank long-press enters edit without a lift.
   - Edit-mode handle, body-hold and blank-tap semantics.
   - Exclusion zones, and the mouse secondary-button entry.
   - Hovering a block tints its drag handle.
   - Cards are disabled while editing, and the JBI tap haptic is removed.
5. **P0-5 Edit visuals**
   - Block plates with the entry ripple, and the growth from the press point.
   - Hide button and drag handle.
   - Header cross-fade, plus the **"Drag to reorder" hint for the first 2 edit sessions**.
   - **Q1(b) IdleSettle, card-level wiggle**: kicks, handle pulse, a dead band at each screen edge, and the 6-second settle.
   - Placeholders for empty sections, the "Hidden" tray with Show / New / Reset, the persistent **footer "Edit Home"** (Q6b(b)), and the all-hidden card.
   - The VM content freeze.
   - Memories pull-down and the memory bubble are disabled while editing.
6. **P0-6 Strips (Q3a)**: fold, reorder, settle, commit, anchor and unfold, **ported exactly** from proto, including re-grab during settle and the deferred exit.
7. **P0-7 Bar pose (Q4a)**: `[Undo|Add] [Done]` in the nav pose, `[Undo|Add][Done][Play▾][Shuffle]` in the merged pose, icons only in navOnly, and the EdgeSplit equivalent. The pill is folded in every pose.
8. **P0-8 Accessibility and cleanup**
   - TalkBack: card long-click "Edit Home"; block actions Move up / Move down / Hide; a "Section j of N" state; tray rows with Show.
   - **Q7: delete `HomeLayoutEditor.kt`** in the same stage.
   - Update the docs.
9. **P0-9 Rediscover (Q9a):** a new section with id `rediscover`, plus its data path (DATA §2–3).

### 1.2 Explicitly out of scope

- Q1 Kick-only and Continuous as product modes (they remain debug toggles), and whole-block wiggle for content sections. Block wiggle is used only for placeholders and the Activities empty card.
- Q2(b) card menu.
- Q3(b)/(c) full-size drag and autoscroll, and strip-stack self-scroll (SPEC ⑦).
- Q5 cell-level editing: resize, remove, pin.
- From Q6b:
  - the JBI hint tile;
  - the dashed "+" slot;
  - the idle nudge;
  - long-press on the bar's Home button (P1-2).

  Also not done: the `home_edit_hint_lines`, `home_idle_nudges` and `home_hint_tile_dismissed` keys.
- Q8 predictive-back scrub (P1-1) and the AGENTS.md / SKILL.md exception it requires.
- Q10 abstraction refactor (P1).
- Q11 play glyph and new-content dot.
- All P1 and P2 components.
- The P1-0 UiState refactor.
- The RA / Apple data fixes.
- A Rediscover-only seed pass (DATA F2, P1).
- The "Hidden" divider inside the strip stack.
- Keyboard ↑/↓ and Escape.
- The latent `visibilityThreshold` bug at `HomeFeedFrame.kt:73`.

### 1.3 Defaults chosen for points SPEC leaves open

Each is a one-line revert. List them in the QA report so the owner can check.

| # | Decision | Default | Where |
|---|---|---|---|
| D1 | Default position of Rediscover | Appended **last** in the enum, so existing users' Default order is unchanged. The prototype put it second. | WP1-A |
| D2 | Undo or Reset back to Default | **Delete the row** (back to "never customized"). SPEC said "Reset writes once". | WP1-A |
| D3 | Widths in the merged pose | Unchanged aspects: Undo is 1.5h, Done 1.0h. | WP2-A |
| D4 | Pill fade curve | Use the shipped curve `1 − idleLike/0.5`. | WP2-A |
| D5 | Footer "New" badge | Shown while a newly appended section is unseen. | WP4-A |
| D6 | Rubber band reaching into the seam bands | Keep proto behaviour (no clip). Check on the tablet. | WP3-B |
| D7 | Shelf vertical breathing room (`SHELF_V`) | Not added. The Compose scroll clip allows about 15dp on the cross axis. Verify in QA. | — |
| D8 | Card amplitude for tall cards | `A_c = clamp(1.1°·100dp / max(w, h/2), 0.35°, 1.1°)`. This is identical to proto for every card except the JBI TallSignal. | WP1-C |
| D9 | Slot cross-fade alphas in the bar | Owned by the bar (`animateFloatAsState`, fastEffects), not the controller. | WP2-A |

---

## 2. Architecture

### 2.1 Layers

```
YoinNavHost (Standard role) ── shell layer ───────────────────────────────────────────────
  ExperienceSessionStore: homeSurface {Feed, Memories, Edit}, homeEditProgress (P)
  HomeEditController   ← single writer of surface (Edit) / P / draft / undo / echo-hold
     ├─ calls HomeViewModel: setHomeLayout, setEditing (freeze), onEditSessionStarted
     ├─ BarEditPose → YoinChromeGroup → YoinButtonGroup / YoinEdgeSplitGroup
     ├─ shell BackHandler(owner == HomeEdit) → commitAndExit(Back)
     └─ layer: HomeEditLayer?  (registered by Home while composed; synchronous callbacks)

HomeEditorialContent (Expressive role) ── home layer ─────────────────────────────────────
  HomeEditSpecs (resolved here)  HomeEditMotion (E, t, kicks, pulse, hide, tray, latch)
  HomeEditPressState (charge)    HomeCarryEngine (lift, fold, strips)   HomeEditTargets
  Root Box: watchMemoryBubbleTouches → homeEditGestures → voteHighFrameRate → seamTide
     ├─ LazyColumn: header · HomeEditBlock(section)… · tray | footer
     ├─ HomeCarryStack overlay (only while a carry session exists)
     └─ MemoryBubbleOverlay (hidden while editing)
```

### 2.2 New files

| File | Stage / WP | Responsibility |
|---|---|---|
| `ui/experience/MotionMath.kt` | 1 / C | `smoothstep(e0, e1, x)`, shared by the bar and Home |
| `ui/experience/HomeEditProgress.kt` | 1 / C | P holder: synchronous snap; animate that keeps velocity |
| `ui/component/BarEditPose.kt` | 1 / C | `BarEditLeftSlot`, `resolveEditLeftSlot`, `BarEditPose` |
| `ui/home/edit/HomeEditTypes.kt` | 1 / C | Exit reason, change model, `HomeEditFeedback` with its `YoinHaptics` adapter |
| `ui/home/edit/HomeEditTokens.kt` | 1 / C | All constants, `HomeEditSpecs`, `withThreshold`, reduced-motion detection, wiggle style local |
| `ui/home/edit/HomeEditGeometry.kt` | 1 / C | Pure functions (PORT §7 list) and `HomeEditVelocity` |
| `ui/home/HomeEditHintStore.kt` | 1 / A | Hint store (device-level) and `HomeEditSessionHints` |
| `data/memory/RediscoverSelection.kt` | 1 / B | Pure Rediscover selection |
| `ui/home/edit/HomeEditController.kt` | 2 / B | Shell-level controller, `HomeEditLayer`, the remember functions |
| `ui/home/RediscoverSection.kt` | 2 / C | Rediscover shelf, card and copy helpers |
| `ui/home/edit/HomeEditMotion.kt` | 2 / D | Motion state, press state, targets registry, safe area |
| `ui/home/edit/HomeEditWiggle.kt` | 2 / D | Clock loop, card and block wiggle modifier nodes, `LocalHomeEditCardScope` |
| `ui/home/edit/HomeCarryEngine.kt` | 2 / D (skeleton), 3 / B (strips) | Lift and the strip engine |
| `ui/home/edit/HomeEditGestures.kt` | 3 / A | Pure gesture machine, pointer modifier, hit tester |
| `ui/home/edit/HomeCarryStack.kt` | 3 / B | Strip overlay and `LazyListCarryHost` (anchor) |
| `ui/home/edit/HomeEditBlock.kt` | 3 / C | Section wrapper: plate, scale layers, badges, placeholder, TalkBack |
| `ui/home/edit/HomeEditTray.kt` | 3 / C | Tray, Reset, footer entry, all-hidden card |
| `ui/home/edit/HomeEditHeader.kt` | 3 / C | Header title overlay, hint, icon fade |

### 2.3 Single-writer table

Every edit value has exactly one writer.

| Value | Only writer (API) | Holder | Spec / threshold | Read phase |
|---|---|---|---|---|
| `homeSurface == Edit` | `HomeEditController` (`enter`, `commitAndExit`, `finishDeferredExit`, `snapExit`) | `ExperienceSessionStore` | — | composition (routing) |
| P | `HomeEditController` | `store.homeEditProgress` (`HomeEditProgress`) | stageSettle, or fastEffects when reduced / 0.0005 | Home draw; bar measure and draw |
| `isEditing` mirror, draft, undo stack, echo hold, latest VM layout, session hints | controller | `HomeEditController` | — | composition |
| VM freeze | `HomeViewModel.setEditing`, called by the controller only | VM | — | — |
| charge, chargeSection, chargeOrigin | gesture machine | `HomeEditPressState` | slowSpatial up / fastSpatial down / 0.002 | layer, draw |
| E, `lastTouch`, `pointerDown`, `carryActive` | motion: `setEnvelope`, `touch`, `idleTick`. Gestures set `pointerDown`; the engine sets `carryActive`. | `HomeEditMotion` | fastSpatial up / defaultSpatial down / 0.001 | draw |
| t | the one clock loop (`HomeEditClock`) | `motion.swayT` | — | draw |
| block and card kicks | `motion.impulse`, `impulseCard`, `stopKicks` | `HomeEditMotion` | kick / 0.01 | draw |
| handle pulse | `motion.pulseHandle` | `HomeEditMotion` | fastSpatial, v = 11.3 / 0.002 | layer |
| `plateFrom`, latch, `kicked`, `rippleOrigin`, the placeholders-above gate | motion | `HomeEditMotion` | — | draw / derived |
| hideS, hideA, hiding set | `motion.onLayoutChange` | `HomeEditMotion` | out: fastSpatial / fastEffects; in: defaultSpatial / defaultEffects | layer; composition (set) |
| trayA, footA, rowA | motion (`onEnter`, `onExit`, `onSnapExit`, `onLayoutChange`) | `HomeEditMotion` | defaultEffects in, fastEffects out / 0.002 | layer; derived mount flags |
| lift, liftTint, liftSection, liftOrigin | engine (`liftBlock`, `dropLift`, commit, finish) | `HomeCarryEngine` | fast or default spatial; fastEffects / 0.002 | layer, draw |
| fold, holeY, settleY, stripOffset, session | engine | `HomeCarryEngine` | default 0.001; fast 0.3px; default 0.4px; default 0.3px | draw, placement |
| feed positions | LazyColumn `animateItem` | HEC | defaultSpatial; `null` while folding or a session exists | layout |
| bar slot alphas | bar-internal `animateFloatAsState` | `YoinButtonGroup`, `YoinEdgeSplitGroup` | fastEffects / 0.002 | bar draw |
| `SeamFlow.held` | one HEC effect (`fold > 0`) | `SeamFlow` | — | — |
| exclusion and handle bounds | `onPlaced` registrations | `HomeEditTargets` | — | read at pointer-down only |

### 2.4 Contracts

Code exactly against these signatures. Small additions are fine. Renames are not.

**C1: Layout model** (WP1-A, `ui/home/HomeSection.kt`)

```kotlin
enum class HomeSection(
    val id: String, val title: String,
    /** One line for the tray rows and TalkBack (not on the feed). */
    val supportingText: String,
    val defaultEnabled: Boolean,
    /** Visibility when appended to an already-customized layout (Q6a). Legacy sections = true (DATA F5). */
    val appendEnabled: Boolean = false,
) {
    Activities(..., appendEnabled = true), JumpBackIn(..., appendEnabled = true), RecentlyAdded(..., appendEnabled = true),
    Rediscover(id = "rediscover", title = "Rediscover",
        supportingText = "Rated high, not played in Yoin for a while",
        defaultEnabled = true, appendEnabled = false),   // LAST (D1)
}

data class HomeLayout(
    val sections: List<HomeSectionState>,
    val retained: List<HomeSectionPref> = emptyList(),   // unknown ids, verbatim, re-emitted after known ones
    val newSections: Set<HomeSection> = emptySet(),      // appended-disabled on this read (Q6a)
) {
    val enabledSections: List<HomeSection>
    val hiddenSections: List<HomeSection>                // !enabled, in layout order (tray order)
    val isDefault: Boolean                               // sections == Default.sections (ignores retained/newSections)
    fun toPrefs(): List<HomeSectionPref>                 // sections + retained
    fun sameSectionsAs(other: HomeLayout): Boolean       // sections == other.sections — use this, never data-class ==
    fun reset(): HomeLayout                              // Default.copy(retained = retained)
    fun withEnabled(section: HomeSection, enabled: Boolean): HomeLayout   // returns `this` on no-op
    fun withEnabledOrder(order: List<HomeSection>): HomeLayout            // enabled slots refilled in `order`, disabled keep
                                                                          // absolute index (proto p:854-859); `this` on no-op
                                                                          // or if `order` is not a permutation of enabledSections
    fun moved(section: HomeSection, toEnabledIndex: Int): HomeLayout      // via withEnabledOrder; clamps; `this` on no-op
    companion object { val Default: HomeLayout; fun reconcile(prefs: List<HomeSectionPref>?): HomeLayout }  // DATA §1.2
}
```

**C2: Hints and VM** (WP1-A)

```kotlin
// ui/home/HomeEditHintStore.kt
interface HomeEditHintStore {
    fun editSessionCount(): Int; fun recordEditSession()
    fun seenSectionIds(): Set<String>; fun markSectionsSeen(ids: Collection<String>)
    class InMemory : HomeEditHintStore
}
class SharedPrefsHomeEditHintStore(context: Context) : HomeEditHintStore   // file "yoin_ui_hints"; keys "home_edit_sessions", "home_sections_seen"
@Immutable data class HomeEditSessionHints(val showHeaderHint: Boolean = false, val newBadges: Set<HomeSection> = emptySet())
internal const val HomeEditHeaderHintSessions = 2

// HomeViewModel additions
fun setHomeLayout(layout: HomeLayout)            // DATA §1.3: carry retained; isDefault && retained.isEmpty() → clearLayout (D2)
fun setEditing(editing: Boolean)                 // freeze: while true, emit() queues; false publishes the latest queued state
fun onEditSessionStarted(): HomeEditSessionHints // snapshot (sessions < 2, unseen newSections), then record + mark seen. No home_layout write.
val unseenNewSections: StateFlow<Set<HomeSection>>
```

**C3: Primitives** (WP1-C)

```kotlin
// ui/experience/MotionMath.kt
internal fun smoothstep(edge0: Float, edge1: Float, x: Float): Float

// ui/experience/HomeEditProgress.kt
@Stable class HomeEditProgress {
    val value: Float                    // snapshot-backed (mutableFloatStateOf)
    val velocity: Float; val target: Float; val isAnimating: Boolean
    fun snapTo(value: Float)            // synchronous: cancel job, set value, velocity 0
    fun animateTo(scope: CoroutineScope, target: Float, spec: AnimationSpec<Float>)  // keeps CURRENT velocity (proto p:209)
    suspend fun awaitSettled()          // joins the live job, re-joining if it was superseded
}

// ui/component/BarEditPose.kt
enum class BarEditLeftSlot { Undo, Add, UndoDisabled }
fun resolveEditLeftSlot(undoDepth: Int, trayCount: Int): BarEditLeftSlot   // undo>0 → Undo; tray>0 → Add; else UndoDisabled
@Stable class BarEditPose(
    val progress: () -> Float,                 // P; read only in bar measure/draw
    val leftSlot: () -> BarEditLeftSlot,       // read in bar (sub)composition
    val onLeftSlotClick: () -> Unit,           // controller resolves Undo/Add/disabled
    val onDone: () -> Unit,
)

// ui/home/edit/HomeEditTypes.kt
enum class HomeEditExitReason { Done, Back, Blank, Programmatic }   // confirm haptic ONLY for Done
enum class HomeEditChangeKind { Hide, Show, Order, Move, Reset, Undo }
@Immutable data class HomeEditChange(val serial: Int, val kind: HomeEditChangeKind,
    val previous: HomeLayout, val next: HomeLayout, val subject: HomeSection? = null)
interface HomeEditFeedback {
    fun longPress(); fun dragStart(); fun segmentTick(); fun threshold()
    fun confirm(); fun reject(); fun toggle(on: Boolean); fun click()
    companion object { val None: HomeEditFeedback }
}
fun YoinHaptics.asHomeEditFeedback(): HomeEditFeedback
```

**C4: Shell controller** (WP2-B, `ui/home/edit/HomeEditController.kt`)

```kotlin
/** What the Home layer plugs in while composed. All calls are synchronous, on the main thread. */
interface HomeEditLayer {
    val isCarrying: Boolean                              // carry session in Drag/Settle/Unfold
    fun onEnter(origin: HomeSection?, lifted: Boolean)   // reset session visuals: kicked, rippleOrigin, E, tray in
    fun onExit(reason: HomeEditExitReason)               // animated exit started (tray out → footer in, E → 0)
    fun onSnapExit()                                     // snap every Home-layer value to rest
    fun onTouch()                                        // bar clicks: E back to 1, idle timer reset
    fun onLayoutChange(change: HomeEditChange)           // hide/show/reset/undo/move/order animations
    fun deferExit(reason: HomeEditExitReason)            // during a carry: release-as-cancelled; finish calls finishDeferredExit
    fun abortCarry()                                     // snapExit during a carry: commit a changed order NOW, tear down
    fun scrollToTray()                                   // bar "Add"
}

@Stable
class HomeEditController(
    private val store: ExperienceSessionStore,
    private val scope: CoroutineScope,                    // shellScope
    private val applyLayout: (HomeLayout) -> Unit,        // HomeViewModel::setHomeLayout
    private val onEditingChanged: (Boolean) -> Unit,      // HomeViewModel::setEditing
    private val startSession: () -> HomeEditSessionHints, // HomeViewModel::onEditSessionStarted
    private val feedback: HomeEditFeedback,
    private val reducedMotion: () -> Boolean,
) {
    val progress: HomeEditProgress                        // = store.homeEditProgress
    val progressReader: () -> Float                       // stable lambda
    var isEditing: Boolean; private set                   // snapshot mirror of surface == Edit
    var draft: HomeLayout?; private set
    val undoDepth: Int; val trayCount: Int; val canReset: Boolean
    val leftSlot: BarEditLeftSlot                         // derivedStateOf(resolveEditLeftSlot)
    var sessionHints: HomeEditSessionHints; private set
    var layer: HomeEditLayer?                             // set/cleared by HEC (DisposableEffect)

    fun layoutToRender(vmLayout: HomeLayout): HomeLayout  // draft ?: echoHold ?: vmLayout  (pure read)
    fun onVmLayout(layout: HomeLayout)                    // records latest; clears echoHold when sameSectionsAs
    fun onProfileSwitched()                               // snapExit + drop echoHold

    fun enter(origin: HomeSection?, lifted: Boolean)      // no-op unless surface == Feed and not editing
    fun commitAndExit(reason: HomeEditExitReason = HomeEditExitReason.Done)
    suspend fun commitAndExitAndAwait(reason: HomeEditExitReason = HomeEditExitReason.Programmatic)
    fun finishDeferredExit(reason: HomeEditExitReason)    // engine → after carry finish
    fun snapExit()                                        // fully synchronous (surface Feed + P 0 in the same call)
    fun touch()

    fun hide(section: HomeSection): Boolean               // toggle(false) haptic, undo push, applyLayout, layer.onLayoutChange
    fun show(section: HomeSection): Boolean               // toggle(true)
    fun reset(): Boolean                                  // reject haptic
    fun undo(): Boolean                                   // click haptic
    fun move(section: HomeSection, delta: Int): Boolean   // TalkBack; segmentTick haptic
    fun commitOrder(enabledOrder: List<HomeSection>): Boolean   // carry commit; confirm haptic iff changed
    fun barLeftSlotClick()                                // touch + (Undo → undo | Add → click + layer.scrollToTray | disabled → nothing)
    fun barDone()                                         // touch + commitAndExit(Done)
}

@Composable fun rememberHomeEditController(store: ExperienceSessionStore, viewModel: HomeViewModel): HomeEditController
/** Previews, the debug harness, androidTests: own ExperienceSessionStore + in-memory layout. */
@Composable fun rememberStandaloneHomeEditController(initial: HomeLayout = HomeLayout.Default): HomeEditController
```

Controller rules:
- **Undo stack:** at most 20 entries. It is cleared on enter, on exit and on snap.
- **Entering edit never calls `applyLayout`.**
- **Draft source:** `enter` seeds the draft from `echoHold ?: latestVmLayout`.
- **Exiting:** on every exit the draft moves to `echoHold`.
- **Construction heal:** on construction, call `progress.snapTo(if (surface == Edit) 1f else 0f)`. If surface is Edit and there is no draft, seed the draft from the first `onVmLayout`. This covers Activity recreation.
- **`commitAndExit` order:**
  1. If `layer?.isCarrying == true`, call `layer.deferExit(reason)` and return.
  2. If `reason == Done`, call `feedback.confirm()`.
  3. Set `isEditing = false` and the surface to Feed.
  4. Run `progress.animateTo(scope, 0f, homeEditStageSpec(reducedMotion()))`.
  5. Move the draft to echo hold, clear undo, call `onEditingChanged(false)`, then `layer?.onExit(reason)`.
- **`snapExit`:**
  1. `layer?.abortCarry()`.
  2. Surface to Feed, `progress.snapTo(0f)`.
  3. Draft to echo hold, clear undo.
  4. `onEditingChanged(false)`, then `layer?.onSnapExit()`.

**C5: Motion** (WP2-D, `ui/home/edit/HomeEditMotion.kt`)

```kotlin
@Immutable internal data class HomeEditSafeArea(val top: Float, val bottom: Float)          // Box px
@Immutable internal data class PlateFrom(val section: HomeSection, val rect: Rect, val latch: Float)  // block-local px

@Stable internal class HomeEditPressState {        // single writer: gesture machine
    val charge: Animatable<Float, AnimationVector1D>
    val section: HomeSection?; val origin: Offset    // block-local px
    fun start(scope: CoroutineScope, section: HomeSection, origin: Offset, specs: HomeEditSpecs)
    fun release(scope: CoroutineScope, specs: HomeEditSpecs)
    fun snapOff(scope: CoroutineScope)
}

@Stable internal class HomeEditTargets {          // read ONLY at pointer-down
    fun attachBox(coords: LayoutCoordinates)
    fun isExcluded(positionInBox: Offset): Boolean
    fun handleAt(positionInBox: Offset): HomeSection?
}
internal fun Modifier.homeEditExclusion(targets: HomeEditTargets, key: Any): Modifier  // onPlaced; checks isAttached
internal fun Modifier.homeEditHandle(targets: HomeEditTargets, section: HomeSection): Modifier

@Stable internal class HomeEditMotion(
    scope: CoroutineScope, val specs: HomeEditSpecs, val style: HomeWiggleStyle,
    reduced: () -> Boolean, progress: () -> Float, editing: () -> Boolean, feedback: HomeEditFeedback,
) {
    val swayT: MutableFloatState; val envelope: Animatable<Float, AnimationVector1D>
    var pointerDown: Boolean; var carryActive: Boolean; var visible: Boolean
    val clockShouldRun: Boolean                                   // derivedStateOf (PORT §3.6)
    fun setEnvelope(); fun touch(nowMs: Long = SystemClock.uptimeMillis()); fun idleTick(nowMs: Long)
    var displayed: List<HomeSection>                              // HEC SideEffect, in render order
    var rippleOrigin: Int
    fun ripple(section: HomeSection): Float                       // p_i  (draw only)
    fun blockKick(section: HomeSection): Float; fun cardKick(section: HomeSection, card: Int): Float
    fun impulse(section: HomeSection, a: Float); fun impulseCard(section: HomeSection, card: Int, a: Float)
    fun stopKicks(section: HomeSection)                           // snap block + its card kicks to 0 (lift)
    fun handlePulse(section: HomeSection): Float; fun pulseHandle(section: HomeSection)
    fun registerCard(section: HomeSection, index: Int, rectInBlock: Rect)   // plain map; onPlaced only
    fun cardAt(section: HomeSection, posInBlock: Offset): Int?
    var plateFrom: PlateFrom?
    fun latchPlate(section: HomeSection, pressRect: Rect, charge: Float)
    suspend fun runEntryEffects()                                 // HEC LaunchedEffect: PORT §2.5 (entry kicks, latch clear)
    val hiding: Set<HomeSection>; fun hideScale(s: HomeSection): Float; fun hideAlpha(s: HomeSection): Float
    fun onLayoutChange(change: HomeEditChange, isInViewport: (HomeSection) -> Boolean)
    val trayAlpha: Animatable<Float, AnimationVector1D>; val footerAlpha: Animatable<Float, AnimationVector1D>
    fun rowAlpha(section: HomeSection): Float
    val trayMounted: Boolean; val footerMounted: Boolean          // derivedStateOf (PORT §5.4)
    var heldSection: HomeSection?; var placeholdersAboveOpen: Boolean  // SPEC §2.1.4-7 deferral gate
    fun onEnter(origin: HomeSection?, lifted: Boolean); fun onExit(reason: HomeEditExitReason); fun onSnapExit()
}
```

**C6: Wiggle** (WP2-D, `ui/home/edit/HomeEditWiggle.kt`)

```kotlin
@Stable internal class HomeEditCardScope(
    val section: HomeSection, val motion: HomeEditMotion,
    val interactive: () -> Boolean,             // !controller.isEditing (snapshot read)
    val enterEdit: () -> Unit,                  // TalkBack long-click: controller.enter(section, lifted = false)
    val blockTopInBox: () -> Float?,            // from listState.layoutInfo (draw-time read)
    val blockCoordinates: () -> LayoutCoordinates?,
    val safeArea: () -> HomeEditSafeArea,
    val liftGain: () -> Float,                  // 1 − clamp(lift) if this section is lifted, else 1; 0 if strip-carried
)
internal val LocalHomeEditCardScope = compositionLocalOf<HomeEditCardScope?> { null }
@Composable internal fun homeEditInteractive(): Boolean       // scope?.interactive() ?: true
internal fun Modifier.homeEditCard(index: Int): Modifier       // Modifier.Node: draw rotate + onPlaced(registerCard) + semantics
internal fun Modifier.homeEditBlockWiggle(scope: HomeEditCardScope, platePx: () -> Size): Modifier  // placeholder / empty card
@Composable internal fun HomeEditClock(motion: HomeEditMotion)  // the single withFrameNanos loop
```

**C7: Carry engine** (skeleton by WP2-D, strips by WP3-B)

```kotlin
internal enum class CarryPhase { Drag, Settle, Unfold }
internal interface CarryHost {                       // implemented by LazyListCarryHost (WP3-B)
    fun displayedOrder(): List<HomeSection>          // enabled, rendered, excluding `hiding`
    fun plateRect(section: HomeSection): Rect?       // Box px, null if not laid out
    fun isAbove(section: HomeSection): Boolean       // off-screen side for B_i
    fun safeArea(): HomeEditSafeArea; fun contentLeft(): Float; fun contentWidth(): Float
    suspend fun anchorAndAwaitLayout(dropped: HomeSection, order: List<HomeSection>, slotTop: Float)
}
@Stable internal class HomeCarryEngine(
    scope: CoroutineScope, specs: HomeEditSpecs, motion: HomeEditMotion, feedback: HomeEditFeedback, density: Density,
    private val commitOrder: (List<HomeSection>) -> Boolean,              // controller.commitOrder
    private val finishDeferredExit: (HomeEditExitReason) -> Unit,         // controller.finishDeferredExit
) {
    val lift: Animatable<Float, AnimationVector1D>; val liftTint: Animatable<Float, AnimationVector1D>
    var liftSection: HomeSection?; var liftOrigin: Offset
    fun liftBlock(section: HomeSection, origin: Offset)   // PORT §2.3 step 5 (WP2-D implements)
    fun dropLift(kickA: Float)                            // PORT §2.4 drop (WP2-D implements)
    var host: CarryHost?
    val fold: Animatable<Float, AnimationVector1D>; val holeY: Animatable<Float, AnimationVector1D>
    val settleY: Animatable<Float, AnimationVector1D>; fun stripOffset(section: HomeSection): Float
    val session: CarrySession?                            // snapshot
    val isCarrying: Boolean; val isBusy: Boolean          // isBusy = Settle or Unfold
    fun start(fingerY: Float, uptimeMs: Long)             // WP3-B
    fun move(y: Float, uptimeMs: Long)                    // WP3-B
    fun release(cancelled: Boolean, uptimeMs: Long)       // WP3-B
    fun tryRegrab(position: Offset, uptimeMs: Long): Boolean   // WP3-B
    fun deferExit(reason: HomeEditExitReason)             // WP3-B
    fun abortNow()                                        // WP3-B
    fun frameFor(section: HomeSection): StripFrame?       // WP3-B, draw-time pure read
}
```

**C8: Gestures** (WP3-A)

```kotlin
internal sealed interface HomeEditHit {
    data object Excluded : HomeEditHit
    data class Handle(val section: HomeSection, val local: Offset) : HomeEditHit
    data class Block(val section: HomeSection, val local: Offset) : HomeEditHit   // local = block-content px
    data class Blank(val nearest: HomeSection?) : HomeEditHit
}
internal class HomeEditHitTester(listState: LazyListState, targets: HomeEditTargets,
    contentBounds: () -> ClosedFloatingPointRange<Float>, plateOutsetPx: () -> Size, editing: () -> Boolean) {
    fun hitTest(positionInBox: Offset): HomeEditHit }
internal fun Modifier.homeEditGestures(
    controller: HomeEditController, motion: HomeEditMotion, press: HomeEditPressState,
    engine: HomeCarryEngine, hitTester: HomeEditHitTester, feedback: HomeEditFeedback,
    isFlingInProgress: () -> Boolean, specs: HomeEditSpecs,
): Modifier
```

**C9: Chrome** (WP3-C)

```kotlin
@Immutable internal class HomeEditDeps(val controller: HomeEditController, val motion: HomeEditMotion,
    val press: HomeEditPressState, val engine: HomeCarryEngine, val targets: HomeEditTargets, val specs: HomeEditSpecs)
@Composable internal fun HomeEditBlock(section: HomeSection, displayIndex: Int, displayCount: Int, deps: HomeEditDeps,
    placeholder: Boolean, wholeBlockWiggle: Boolean, itemSpacing: Dp, blockTopInBox: () -> Float?,
    safeArea: () -> HomeEditSafeArea, modifier: Modifier = Modifier, content: @Composable () -> Unit)
internal fun LazyListScope.homeEditTrayItems(hidden: List<HomeSection>, newBadges: Set<HomeSection>, canReset: Boolean,
    deps: HomeEditDeps, placementSpec: () -> FiniteAnimationSpec<IntOffset>?)          // keys tray-title, tray-<id>, edit-footer
internal fun LazyListScope.homeEditFooterEntry(newBadge: Boolean, deps: HomeEditDeps, onEnter: () -> Unit,
    placementSpec: () -> FiniteAnimationSpec<IntOffset>?)                               // key home-edit-entry
internal fun LazyListScope.homeAllHiddenItem(placementSpec: () -> FiniteAnimationSpec<IntOffset>?)  // key home-all-hidden
@Composable internal fun HomeEditHeaderTitle(progress: () -> Float, style: TextStyle, modifier: Modifier = Modifier)
@Composable internal fun HomeEditHeaderHint(progress: () -> Float, visible: Boolean, modifier: Modifier = Modifier)
internal fun Modifier.homeEditHeaderIcon(progress: () -> Float): Modifier
@Composable internal fun rememberHomeEditIconsEnabled(progress: () -> Float): Boolean   // derivedStateOf { P < .5 }
```

**C10: Bar** (WP2-A)

`YoinButtonGroup`, `YoinChromeGroup` and `YoinEdgeSplitGroup` each gain:

```kotlin
editPose: BarEditPose? = null, editing: Boolean = false
```

Both default to off, so every existing caller compiles unchanged.

**C11: Rediscover** (WP1-B / WP2-C)
- Data: DATA §2.2–2.5, unchanged.
- `HomeRediscoverItem`: as in DATA §3.1.
- UI:

```kotlin
@Composable internal fun RediscoverSection(items: List<HomeRediscoverItem>, frame: HomeFeedFrame,
    nowMillis: Long, scrollEnabled: Boolean = true, onAlbumClick: (String, String?) -> Unit, modifier: Modifier = Modifier)
internal const val RediscoverPlaceholderText = "Rate an album 8 or higher — when it's been a while, it comes back here"
```

### 2.5 LazyColumn item model

Owned by WP4-A, using pieces from Stage 3.

| Mode | Items, in order |
|---|---|
| Normal | `home-header` · `section-<id>` for each enabled section that has data (Activities always renders, using `HomeEmptyCard` when empty) · `home-all-hidden` when no section is enabled · `home-edit-entry` while `footerMounted` |
| Edit | `home-header` · `section-<id>` for every enabled section **plus** sections in `motion.hiding` (empty sections render a placeholder) · `tray-title` · `tray-<id>`… · `edit-footer` while `trayMounted` |

Key and animation rules:
- **Keys** are `section-${section.id}`, so `section-widget-grid` becomes `section-jump_back_in` and so on. The hit tester parses them as `HomeSection.fromId(key.removePrefix("section-"))`.
- **Placeholders above `motion.heldSection`** are not emitted until `motion.placeholdersAboveOpen`, which opens on the first finger-up or when a carry starts.
- **`animateItem`** on section items:
  - `placementSpec = null` while `paneWidthInMotion || feedFrame.isBlending || foldingOrSession`, where `foldingOrSession = derivedStateOf { engine.fold.value > 0f || engine.session != null }`; otherwise `specs.placement` (defaultSpatial);
  - `fadeInSpec = if (placeholder) defaultEffects else null`;
  - `fadeOutSpec = fastEffects`.
- **Tray and footer items** use `fadeInSpec = null` and `fadeOutSpec = null`, with alpha driven by a layer.

### 2.6 Changes to existing files, at a glance

| File | Change | WP |
|---|---|---|
| `data/home/HomeLayoutStore.kt` | Mutex, equality skip, `clearLayout`, versioned lenient DTO | 1-A |
| `ui/home/HomeSection.kt` | C1 | 1-A |
| `ui/home/HomeViewModel.kt` | C2 and freeze (1-A); Rediscover (2-C); comment fix (4-A) | 1-A, 2-C, 4-A |
| `data/profile/ProfileManager.kt`, `AppContainer.kt` | `onProfileDeleted`; hint store | 1-A |
| `PlayHistoryDao`, `AlbumMemoryCandidate(+Builder)`, `YoinRepository` | DATA §2 | 1-B |
| `ui/theme/Motion.kt`, `Shape.kt`, `ui/experience/Haptics.kt`, `ui/component/SeamDissolve.kt` | Kick spring, `PanelAnimated`, 4 haptics, `SeamFlow.held` | 1-C |
| `ExperienceSessionStore.kt`, `ShellBackResolver.kt`, `NowPlayingPresentation.kt` | `Edit`, P holder, resolver, `nowPlayingCoversHome()` | 2-B |
| `YoinNavHost.kt` | Controller, back, exits (2-B); bar and `HomeScreen` wiring (3-D) | 2-B, 3-D |
| `YoinButtonGroup.kt`, `YoinChromeGroup.kt`, `YoinEdgeSplitGroup.kt` | Edit pose | 2-A |
| `HomeUiState.kt`, `HomeFeedDensity.kt` | `rediscover`; per-tier visible count | 2-C |
| `HomeScreen.kt` | Rediscover plumbing (2-C); params (3-D); old editor removed (4-A) | 2-C, 3-D, 4-A |
| `HomeEditorialContent.kt` | Stub (1-A); Rediscover (2-C); card hooks and keys (3-D); full integration (4-A) | 1-A, 2-C, 3-D, 4-A |
| `HomeWidgetGrid.kt`, `HomeMemoryBubble.kt` | Card hooks, minus the tap haptic; edit gating | 3-D |
| `HomeLayoutEditor.kt` | **Deleted** | 4-A |
| `debug/MemoriesScreenshotActivity.kt` | Rediscover fakes (2-C); edit and wiggle flags (4-A) | 2-C, 4-A |

---

## 3. Stages and work packages

### File ownership matrix

| Stage | WP-A | WP-B | WP-C | WP-D |
|---|---|---|---|---|
| 1 | layout data, hints, VM guard and freeze | Rediscover data layer | primitives, tokens, geometry | Undo symbol and input archive |
| 2 | bar edit pose | shell controller and back | Rediscover in the feed | Home motion core |
| 3 | gestures | strips | edit chrome | shell and card wiring |
| 4 | feed integration | androidTests and docs | — | — |
| 5 | device QA, fix loop, release (serial) | | | |

### Stage 0: lead only, about 15 minutes

1. Copy `/private/tmp/claude-501/-Users-gpo-Developer-Yoin/23022169-9b21-41db-bdbe-b07ff336cf3b/scratchpad/impl/proto.js` to `docs/handoff/home-edit-mode/impl/proto.js`.
2. Write the four survey reports **verbatim** to `docs/handoff/home-edit-mode/impl/{survey-home,survey-shell,survey-data,port-sheet}.md`. Coders cite them as HOME, SHELL, DATA and PORT.
3. Run `git status`. The tree must be clean apart from these files. Record the HEAD sha in the run log.

---

### Stage 1: Foundations

There is no visible behaviour change in this stage, apart from Rediscover existing as a section that renders nothing.

#### WP1-A: Layout data, hints, VM write guard and freeze

**Goal.** P0-1 complete, the VM freeze, and the `HomeSection.Rediscover` constant.

**Files**
- Modify:
  - `data/home/HomeLayoutStore.kt`
  - `data/local/HomeLayoutPreference.kt` (KDoc only)
  - `ui/home/HomeSection.kt`
  - `ui/home/HomeViewModel.kt`
  - `ui/home/HomeEditorialContent.kt` (**only** add `HomeSection.Rediscover -> Unit` to the exhaustive `when (sectionState.section)`, with the comment "rendered in WP2-C")
  - `data/profile/ProfileManager.kt`
  - `AppContainer.kt`
- Create: `ui/home/HomeEditHintStore.kt`
- Tests:
  - create `data/home/HomeLayoutStoreTest.kt` and `ui/home/HomeEditHintStoreTest.kt`;
  - modify `ui/home/HomeLayoutTest.kt`, `ui/home/HomeViewModelTest.kt` and `data/profile/ProfileManagerPersistenceTest.kt`.

**Depends on:** nothing.

**Steps**
1. **Store.** Implement DATA §1.1 exactly:
   - a FIFO `writeMutex`;
   - read the stored row, skip the upsert when the sections are equal;
   - `clearLayout(profileId)`;
   - `SectionsDto(version = 1, sections: List<JsonElement>)` with per-entry decode;
   - the existing best-effort try/catch, rethrowing `CancellationException`;
   - keep the `Json` config as it is.

   Update the `HomeLayoutPreference` KDoc to say "unknown ids are retained".
2. **HomeSection and HomeLayout** as in C1. `reconcile` follows DATA §1.2:
   - the retired ids `memory_teaser` and `memories` are dropped;
   - for unknown ids the first occurrence is kept, in order;
   - missing catalog sections are appended at `appendEnabled`;
   - `newSections` = sections appended with `!appendEnabled`.

   `withEnabledOrder`: walk `sections`, refill the enabled slots from `order` and keep each disabled entry at its absolute index. Every operation returns `this` when it changes nothing. Rewrite the class KDoc and the `supportingText` comment.
3. **Hint store** (C2, DATA §1.4). In `SharedPrefs`, `seenSectionIds()` returns a copy, and `markSectionsSeen` skips the write when nothing is new.
4. **VM**
   - Add the constructor parameter `homeEditHintStore: HomeEditHintStore = HomeEditHintStore.InMemory()`. `Factory` passes `container.homeEditHintStore`.
   - `setHomeLayout`: DATA §1.3, including D2.
   - Freeze (DATA §2.7):
     - `private var editing`, `frozenState`;
     - `emit(s)` and `currentContent()`;
     - replace all 5 `_uiState.value =` writes with `emit` and all 4 `as? Content` reads with `currentContent()`;
     - `setEditing(false)` publishes `frozenState`, if any, then clears it.
   - `unseenNewSections` and `onEditSessionStarted()` as in C2. Hints are snapshotted **before** recording.
   - Update the KDoc on `activeProfileId`: it stays public "so the shell can exit edit mode on profile switch".
5. **ProfileManager:** add `onProfileDeleted: suspend (String) -> Unit = {}` as the **last** constructor parameter. Call it after the active-profile switch, guarded by try/catch with `CancellationException` rethrown (DATA §1.5).
6. **AppContainer:**
   - `val homeEditHintStore by lazy { SharedPrefsHomeEditHintStore(context) }`, next to `lyricHintStore`;
   - pass `onProfileDeleted = { homeLayoutStore.clearLayout(it) }`.

**Tests**
- All of DATA §1.6:
  - `HomeLayoutStoreTest`: 11 tests;
  - `HomeLayoutTest`: 3 modified and 15 new;
  - `HomeEditHintStoreTest`: 5;
  - `ProfileManagerPersistenceTest`: 3;
  - `HomeViewModelTest`: `should_notWriteLayout_when_layoutUnchanged`, `should_clearLayout_when_layoutEqualsDefault`, `should_carryRetainedIds_when_layoutArrivesWithout`.
- In addition:
  - `should_withEnabledOrderKeepDisabledSlots_when_orderPermuted` (vector `[A, b̶, C, D] + [D, A, C] → [D, b̶, A, C]`);
  - `should_returnSameInstance_when_orderIsNotAPermutation`;
  - `should_queueContent_when_editing` (turbine);
  - `should_publishLatestQueued_when_editingEnds`;
  - `should_snapshotHintsBeforeRecording_when_editSessionStarts`.

**Acceptance**
- Stage gate green.
- Grep shows that nothing else constructs `HomeLayout(` with positional extra arguments.
- The feed renders exactly as before.

#### WP1-B: Rediscover data layer

**Goal.** DATA §2.1–2.6, with no VM changes.

**Files**
- Modify: `data/local/PlayHistoryDao.kt`, `data/memory/AlbumMemoryCandidate.kt`, `data/memory/AlbumMemoryCandidateBuilder.kt`, `data/repository/YoinRepository.kt`.
- Create: `data/memory/RediscoverSelection.kt`.
- Tests: modify `data/memory/AlbumMemoryCandidateBuilderTest.kt`; create `data/memory/RediscoverSelectionTest.kt` and `data/local/PlayHistoryDaoRediscoverTest.kt` (Robolectric in-memory Room, following `SongNoteDaoTest`).

**Depends on:** nothing.

**Steps**
1. **DAO:** add `getAlbumAggregatesFor(profileId, provider, albumIds)` and `observeMostRecent(profileId, provider)` (DATA §2.3). No schema change; the database stays at v28.
2. **Candidate:**
   - add `firstPlayedFromHistoryAt`, `lastPlayedFromHistoryAt` and `playCountFromHistory`;
   - add `List<AlbumMemoryCandidate>.memoryEligible(limit)`;
   - leave `playCount` untouched (DATA F4).
3. **Builder:**
   - `build(limit, includeIneligible = false)`;
   - the seed history fields;
   - `fillHistoryGaps`, which runs for **both** flags;
   - sort, then filter at the end (DATA §2.1).
4. **Repository** (use `grep -a`):
   - `getAlbumMemoryCandidates(limit = 48, includeIneligible = false)`;
   - `observeMostRecentPlay()`, which is **not** folded into `observeMemorySignalStamp`.
5. **Selection:** `RediscoverSelection.kt` exactly as in DATA §2.5, with its constants.

**Tests**
- DATA §2.6, all listed: 7 builder tests and 12 selection tests.
- DAO tests:
  - `should_aggregateOnlyRequestedAlbums_when_idsGiven`;
  - `should_scopeAggregatesByProfileAndProvider`;
  - `should_emitNewestRow_when_playInserted`.

**Acceptance**
- `should_returnIdenticalEligibleSubset_when_includeIneligible` passes with a golden albumId order.
- `MemoriesDeckCoordinatorTest` and `HomeViewModelTest` pass **unmodified**.

#### WP1-C: Motion primitives, tokens and pure geometry

**Goal.** Every constant, spring and pure function the later stages need (C3, PORT §1, §7, §8).

**Files**
- Modify:
  - `ui/theme/Motion.kt`: add `fun homeEditKickSpring(): FiniteAnimationSpec<Float> = spring(0.3f, 450f, visibilityThreshold = 0.01f)`.
  - `ui/theme/Shape.kt`: add `YoinContainerShapes.PanelAnimated = RoundedCornerShape(20.dp)`, with KDoc that it is the circular twin of `Panel`.
  - `ui/experience/Haptics.kt`: add
    - `performDragStart()` (DRAG_START on API 34+, else CONTEXT_CLICK);
    - `performSegmentTick()` (SEGMENT_TICK, else CLOCK_TICK);
    - `performThreshold()` (GESTURE_THRESHOLD_ACTIVATE, else `performLightTick()`);
    - `performToggle(on)` (TOGGLE_ON / TOGGLE_OFF, else CONTEXT_CLICK).

    Gate on `Build.VERSION_CODES.UPSIDE_DOWN_CAKE`.
  - `ui/component/SeamDissolve.kt`: add `SeamFlow.held` (`var held by mutableStateOf(false)`). While it is true, the viewport node's scroll handler consumes deltas **without** feeding lag, speed or travel, in the same early-return spot as `reducedMotion()`.
- Create: `ui/experience/MotionMath.kt`, `ui/experience/HomeEditProgress.kt`, `ui/component/BarEditPose.kt`, `ui/home/edit/HomeEditTypes.kt`, `ui/home/edit/HomeEditTokens.kt`, `ui/home/edit/HomeEditGeometry.kt`.
- Tests (all new):
  - `testutil/FrameClockTest.kt`, providing `runFrameClockTest { scope -> }`. It is `runTest`, plus `TestMonotonicFrameClock(this)` and a `SupervisorJob` scope, with `@OptIn(ExperimentalTestApi::class)`;
  - `ui/home/edit/RecordingHomeEditFeedback.kt`;
  - `ui/home/edit/HomeEditGeometryTest.kt`, `HomeEditVelocityTest.kt`, `HomeEditSpringTest.kt`;
  - `ui/experience/HomeEditProgressTest.kt`;
  - `ui/component/BarEditPoseTest.kt`.

**Depends on:** nothing.

**Steps**
1. **`HomeEditTokens`.** One `internal object` holding every constant from PORT §7, all in dp, ms or degrees. The non-obvious values are:
   - `PressBox` 96, `PressOutset` 4, `ChargeScale` 0.012, `ChargePlateAlpha` 0.4, `LiftScale` 0.02, `LiftShadow` 6dp, `LatchClearP` 0.6;
   - `RippleStep` 0.06, `RippleMaxSteps` 4, `EntryKickAt` 0.85;
   - `PlateOutsetH` 8, `PlateOutsetVMax` 6, `PlateRadius` 20;
   - `SwayHz` 2.4, `SwayDetune` 0.07, `BlockSwayFactor` 0.6, `DragEnvelope` 0.6, `IdleMs` 6000, `BandFade` 48, `SafeGap` 8;
   - `KickGain` 31.6, kick amplitudes `KickEntry` 1, `KickTap` 0.5, `KickDrop` 0.7, `KickNeighbour` 0.35, `PulseVelocity` 11.3;
   - strips: `StripGap` 8, `StripMinH` 48, `StripMaxH` 64, `StripMaxW` 560, `StripCoverInset` 24, cover size 24–36, overlap 0.3, padding 16, gap 12;
   - fade points 0.45 / 0.55 / 0.3 / 0.6, tint 0.30, hole alpha 0.35, off-screen scale 0.96;
   - `RubberD` 56, `RubberK` 0.55, `OverHaptic` 2dp, `FlingV` 1600 dp/s, `MaxV` 8000 dp/s;
   - velocity window 100 ms, stale 60 ms, min dt 4 ms, re-grab slop 8;
   - `UndoMax` 20, `HideScale` 0.96, `ScrollToTrayOffset` 80, `FlingStopMs` 120, `BodyHoldMinMs` 150, `PlaceholderHeight` 112.

   Also in this file:
   - `HomeWiggleMode`, `HomeWiggleTarget`, `HomeWiggleStyle`, and `LocalHomeWiggleStyle` (default IdleSettle + Card; the debug harness may override, following the `HomeHintVariant` pattern);
   - `withThreshold`;
   - `HomeEditSpecs`, with all fields from PORT §1.3 plus `placement: FiniteAnimationSpec<IntOffset>`, `effectsIn` and `effectsOut`;
   - `@Composable rememberHomeEditSpecs(reduced)`, built from the composable `YoinMotion.*Spec()` getters. When reduced, every spatial entry becomes `fastEffects.withThreshold(sameThreshold)`;
   - `@Composable rememberHomeEditReducedMotion()`: `LocalMotionProfile == AdaptiveReduced || MotionDurationScale == 0` (the same test as `SeamDissolve.reducedMotion()`; battery saver is already folded into `AdaptiveReduced`);
   - `homeEditStageSpec(reduced)`: `stageSettleSpring().withThreshold(.0005f)`, or `spring(1f, 3800f, .0005f)` when reduced.
2. **`HomeEditGeometry`** pure functions, px-based, with the density applied by the caller:
   - `pressRect`, `fullPlateRect`, `plateOutsetVDp(itemSpacingDp) = min(6, (s − 4)/2)`;
   - `rippleProgress(p, index, origin, count)` (PORT §2.5);
   - `badgeLocal`, `badgeScale`, `badgeAlpha` (§2.6);
   - `blockThetaDeg(plateW, plateH)` (§3.1, applied to the **plate** size);
   - `cardAmpDeg(wDp, hDp)` (D8);
   - `edgeBand(top, bottom, safeTop, safeBottom, fadePx)` (§3.4);
   - `fnv1a32(String): Long` (unsigned, UTF-16 code units);
   - `swayParams(id) → SwayParams(sign, freqHz, phaseRad)` (§3.2);
   - `swayValue(E, pa, tSec, params)`, which is 0 when `E ≤ .0005 || pa ≤ 0` and treats negative E as 0;
   - `cardAngleDeg` and `blockAngleDeg` (§3.3; draw only when |angle| ≥ 0.0005°);
   - `kickInitialVelocity(a, sign, running, currentVelocity)` (§3.7);
   - `nearestSectionIndex(y, bands)` (proto p:904-912);
   - `SectionBand(id, top, bottom, bleeds)` and `resolveSectionAt(x, y, bands, contentLeft, contentRight, outsetH, outsetV, editing)`. Bleeding shelves accept any x inside their y band; others accept x within the content range, extended by `outsetH` in edit mode. y is extended by `outsetV` in edit mode;
   - `StripMetrics`, `stripMetrics(...)` and `slotY` (§4.2);
   - `StripStart` and `stripStart(plate, safe, metrics, above)` (§4.3);
   - `StripFrame` and `stripFrame(...)` (§4.4: the unclamped `fv` used for geometry, and xf, la, tw, shadow and label offset);
   - `feedFoldAlpha(fold) = 1 − smoothstep(0, .45, fold)`, `holeAlpha(fold) = smoothstep(.3, 1, fold)`;
   - `rubber`, `unrubber` (§4.6);
   - `anchorScrollOffset(droppedIndex, desiredTopInViewport, heightsAbove: List<Int?>, spacingPx, headerVisible): Int?` (§4.9: null means skip; the offset is clamped so the first visible index stays ≥ 1);
   - `HomeEditVelocity` (§4.7, in px/s).
3. **`HomeEditProgress`:** C3. `animateTo` uses `animate(initialValue = value, targetValue, initialVelocity = velocity, spec) { v, vel -> … }`.
4. **`HomeEditTypes`** and **`BarEditPose`:** C3.

**Tests.** PORT §8 vectors exactly, plus:

| Test | Expectation |
|---|---|
| `should_peakAtAmplitude_when_kickedWithGain31_6` | `VectorizedSpringSpec.getValueFromNanos`: peak ≈ 0.9983·a at 62.6 ms ±1 ms; next peak ≈ −0.372× |
| `should_pulseTo1_1993_when_fastSpatialV11_3` | peak at 41 ms |
| `should_reach0_8755_when_chargeRuns200ms` | — |
| `should_cross085At108ms_when_stageSettle` | — |
| `should_hitFoldMilestones_when_defaultSpatial` | 0.45 at 71 ms, 0.55 at 84 ms, 1.0 at 214 ms, peak 1.0152 |
| `should_match_when_rubber56` | 19.87 |
| `should_invert_when_unrubberOfRubber` | — |
| `should_computeStack_when_safe44to672N4k1finger300` | hs 64, pitch 72, stackH 280, top 196 |
| `should_hashKnownIds` | the four ids, using PORT §3.2 values |
| `should_be0_419_when_theta360x412` | — |
| `should_clampCardAmp` | (100, 140) → 1.1; (320, 150) → 0.35; (110, 330) → 0.667 |
| `should_return0_when_lastSampleStale` | — |
| `should_clampTo8000_when_1600dpIn100ms` | — |
| `should_skipAnchor_when_headerVisible` | — |
| `should_clampAnchor_when_heightsTooShort` | — |
| `should_skipAnchor_when_heightUnknown` | — |
| `should_resolveBleedingShelf_when_xInMargin` | — |
| `should_resolveBlank_when_xInMarginOfNonBleedingSection` | — |
| `should_snapSynchronously_when_snapTo` | `HomeEditProgress` |
| `should_keepVelocity_when_retargetedMidFlight` | `HomeEditProgress` |
| `should_resolveLeftSlot` | 3 cases |

**Acceptance**
- Stage gate green.
- `Motion.kt` changes are additive only.
- `SeamFlow.held` is false by default and has no other behaviour change.

#### WP1-D: Undo symbol and input archive (cross-repo)

**Goal.** Add `YoinSymbols.Undo`, which SPEC §2.2.7 lists as a prerequisite.

**Files:** `../yoin-symbols/generator/icons/icons_nav.py`, the regenerated outputs it produces, and `../yoin-symbols/CHANGELOG.md`. Nothing in the Yoin tree.

**Steps**
1. Create a venv in the scratchpad and run `pip install -r generator/requirements.txt` (shapely, fonttools, brotli are not installed yet).
2. Add `@icon("undo", "nav", "撤销", replaces=["Icons.AutoMirrored.Rounded.Undo"])`. The drawing is a counter-clockwise hook arrow, built with the `geom.py` and `knock.py` helpers in the stroke style of `back` and `refresh`. Follow the `docs/DESIGN.md` checklist.
3. Run `python3 generator/build.py`. Confirm that `codepoints.json` appended an entry and that `android/.../YoinSymbols.kt` now contains `val Undo`. Add a CHANGELOG line under "Unreleased".
4. Prove the composite build picks it up: `lockf ... ./gradlew :app:compileDebugKotlin` must stay green. There is no app reference yet.
5. Commit in `yoin-symbols` only if commits are authorized; otherwise leave the changes staged for the owner.

**Fallback:** if the build fails, report it. WP2-A then uses a text label "Undo" (with no icon) in every pose.

**Acceptance:** `grep -n "val Undo" ../yoin-symbols/android/**/YoinSymbols.kt` finds it.

---

### Stage 2: Shell, bar, Rediscover and motion core

The edit surface exists in this stage, but nothing enters it yet.

#### WP2-A: Bar edit pose

**Goal.** SHELL §3, with C10.

**Files**
- Modify: `ui/component/YoinButtonGroup.kt`, `ui/component/YoinChromeGroup.kt`, `ui/component/YoinEdgeSplitGroup.kt`, and `ui/component/FloatingBottomBar.kt` only if required.
- Tests: modify `ui/component/BarGeometryTest.kt`.

**Depends on:** WP1-C (`BarEditPose`, `smoothstep`) and WP1-D (`Undo`).

**Steps**
1. **Parameters.** Add `editPose: BarEditPose? = null, editing: Boolean = false` to all three composables. `YoinChromeGroup` passes them through to both forms.
2. **Geometry.** Add `edit: Float` to `resolveBarGeometry`. Then:
   - `idleLike = maxOf(idle, edit)` replaces `idle` in `idleWeight`, `collapsePill`, `pillComposed` and `BarGeometry.idle`;
   - add the `val edit: Float` field;
   - at the call site, pass `edit = editPose?.progress?.invoke()?.coerceIn(0f, 1f) ?: 0f`, **read inside the existing per-frame geometry lambda only**.
3. **Cross-fade:** `editSwap = smoothstep(.35f, .65f, g.edit)`.
4. **Home slot.** Keep one `FilledIconButton` with two stacked rows:
   - the Home row has alpha `1−s` and is composed while `s < .99`;
   - the edit row has alpha `s × slotAlpha(kind)` and is composed while `s > .01`;
   - labels are multiplied by `idleLabelAlpha`;
   - `slotAlpha` comes from three bar-local `animateFloatAsState`s on `YoinMotion.fastEffectsSpec()` with threshold 0.002, keyed on `editPose.leftSlot()` (D9);
   - container `lerp(homeContainer, surfaceContainerHighest, s)`;
   - disabled Undo is drawn at 38% `onSurface`, with `semantics { disabled() }`. The button stays enabled; its click is a no-op handled by the controller;
   - click: `if (editing) editPose.onLeftSlotClick() else onHomeClick()`;
   - TalkBack: inner icons get `contentDescription = null`; the button's description comes from the discrete state: "Undo", "Show hidden sections", or "Undo" with disabled.
5. **Both Library buttons** (the nav right slot and the merged left slot):
   - stack a Library row and a Done row (`YoinSymbols.Check` + "Done");
   - container `lerp(libraryContainer, primary, P)`, **linear in raw P**; content lerps to `onPrimary`;
   - click `if (editing) editPose.onDone()`;
   - `onLongClick = if (editing) null else …`;
   - `if (libraryPressed && !editing)` for the search hint, and clear `showLibrarySearchHint` when editing starts.
6. **Gates:** the pill and the EdgeSplit lower capsule get `onClick = if (editing) {} else …`.
7. **EdgeSplit:**
   - use `idleLike` in the composition gate and in `hide`;
   - cross-fade the `UpperCapsuleContent` icons (Home ↔ Undo/Add, Library ↔ Check);
   - lerp the Library container to `primary`;
   - no long-click while editing.
8. **Banned:** `AnimatedContent`, `Crossfade` and new `ColorScheme`s. Colours are local lerps only.

**Tests:** the 10 cases in SHELL §3, with the helper gaining `edit: Float = 0f`. The four existing tests stay green.

**Acceptance**
- `DetailBottomBar`, the debug audit activities and `androidTest/DetailBarHandoffTest` compile unchanged.
- With `editPose = null`, the geometry is identical to before (covered by test 1 at e = 0).

#### WP2-B: Shell controller, session state and back

**Goal.** P0-3: C4 plus the NH wiring, but **not** the bar and **not** `HomeScreen`.

**Files**
- Modify: `ui/experience/ExperienceSessionStore.kt`, `ui/navigation/back/ShellBackResolver.kt`, `ui/nowplaying/NowPlayingPresentation.kt` (add a pure `fun nowPlayingCoversHome(p: NowPlayingPresentation): Boolean = p != Panel` with KDoc), `ui/navigation/YoinNavHost.kt`.
- Create: `ui/home/edit/HomeEditController.kt`.
- Tests:
  - modify `ui/navigation/back/ShellBackResolverTest.kt` and `ui/detail/ShellDetailOriginTest.kt`;
  - create `ui/home/edit/HomeEditControllerTest.kt` and `ui/nowplaying/NowPlayingCoversHomeTest.kt`.

**Depends on:** WP1-A (VM API, `HomeLayout`) and WP1-C (`HomeEditProgress`, types, `BarEditLeftSlot`).

**Steps**
1. **Store:**
   - `enum class HomeSurface { Feed, Memories, Edit }`;
   - `val homeEditProgress = HomeEditProgress()` next to `shellBarChromeMorph`;
   - KDoc: "only `HomeEditController` writes Edit or P".
2. **Resolver:** `HomeEdit` ranks between NowPlaying and DetailPane, exactly as in SHELL §1. Update the KDoc.
3. **Controller:** C4 and its rules (§2.4).
   - `rememberHomeEditController` feeds `feedback` from `rememberYoinHaptics().asHomeEditFeedback()`.
   - `reducedMotion` comes from `rememberUpdatedState(LocalMotionProfile.current == AdaptiveReduced)`.
   - `rememberStandaloneHomeEditController` owns a private `ExperienceSessionStore()`, has a no-op freeze, uses `HomeEditSessionHints(showHeaderHint = true)`, and seeds `onVmLayout(initial)`.
4. **NH wiring**, using the order in SHELL §2:
   - `val homeEdit = rememberHomeEditController(experienceSessionStore, homeViewModel)` right after `shellScope`, before the lifecycle observer and the navigate lambdas;
   - `val homeEditing = selectedSection == HOME && homeSurface == HomeSurface.Edit`, replacing the unused `memoriesActive`;
   - `when (homeSurface)`: add the `HomeSurface.Feed, HomeSurface.Edit ->` branch, with the reveal staying closed;
   - `LaunchedEffect(homeEdit) { homeViewModel.homeLayout.collect(homeEdit::onVmLayout) }`;
   - `LaunchedEffect(homeEdit) { homeViewModel.activeProfileId.drop(1).collect { homeEdit.onProfileSwitched() } }`. Do **not** use `musicConfigurationRevision` (SHELL §4);
   - consistency heal: `LaunchedEffect(homeSurface) { if (homeSurface != HomeSurface.Edit && homeEdit.isEditing) homeEdit.snapExit() }`;
   - `BackHandler(enabled = shellBackOwner == ShellBackOwner.HomeEdit) { homeEdit.commitAndExit(HomeEditExitReason.Back) }`, placed after the `DetailPaneCloseHandler` provider block and before `NowPlayingOverlayHost(`, on the root dispatcher (SHELL §1).
5. **Exit triggers** (SHELL §4):
   - in `LaunchedEffect(selectedSection)`: `if (selectedSection != HOME && homeEdit.isEditing) homeEdit.snapExit()`;
   - lifecycle `ON_STOP → homeEdit.snapExit()`;
   - `onNowPlayingClick`: if editing and `!nowPlayingCoversHome(npFrame.presentation)`, then `shellScope.launch { homeEdit.commitAndExitAndAwait(); expand }`; otherwise `snapExit()` then expand;
   - Detail-as-Activity else-branches and the tier-change relaunch: `homeEdit.snapExit()` **before** every `armDetailChrome()`;
   - `openPane(route)` while editing: `shellScope.launch { homeEdit.commitAndExitAndAwait(); openPane(route) }`;
   - `closePane` / `popPaneEntry` while editing: the same await pattern;
   - `navigateToSettingsFromShell`: `homeEdit.snapExit()`.
6. Leave the `HomeScreen(...)` call and `YoinChromeGroup(...)` untouched (WP3-D handles them).

**Tests**
- `ShellBackResolverTest`, the 4 from SHELL §1:
  - `should_returnHomeEdit_when_editingOnHome`;
  - `should_keepNowPlayingFirst_when_homeEditingBesideThePanel`;
  - `should_giveHomeEditBack_when_theDetailPaneIsAlsoOpen`;
  - `should_ignoreEditSurface_when_libraryIsSelected`.
- `ShellDetailOriginTest`: `should_keepBottomBarHandoff_whenHomeEditing`.
- `NowPlayingCoversHomeTest`: one test per enum value.
- `HomeEditControllerTest` (frame clock, `RecordingHomeEditFeedback`, a fake `HomeEditLayer`):

  | Test | Notes |
  |---|---|
  | `should_setEditSurfaceAndAnimateProgress_when_entering` | — |
  | `should_notApplyLayout_when_enteringAndExiting` | — |
  | `should_freezeVm_when_entering` | and unfreeze on exit |
  | `should_snapSurfaceAndProgressSynchronously_when_snapExit` | — |
  | `should_pushUndoPersistAndNotifyLayer_when_hiding` | — |
  | `should_returnFalseWithoutSideEffects_when_showingEnabledSection` | — |
  | `should_capUndoAtTwenty` | — |
  | `should_restorePreviousLayout_when_undo` | — |
  | `should_clearUndo_when_exiting` | — |
  | `should_deferExit_when_layerIsCarrying` | — |
  | `should_exitAfterFinish_when_finishDeferredExit` | — |
  | `should_confirmHapticOnlyForDone` | — |
  | `should_keepEchoHold_until_vmLayoutMatches` | — |
  | `should_seedDraftFromEchoHold_when_reenteringBeforeEcho` | — |
  | `should_dropEchoHold_when_profileSwitched` | — |
  | `should_resolveLeftSlot_from_undoDepthAndTray` | — |
  | `should_moveWithinEnabledOrder_when_moveCalled` | — |
  | `should_confirmOnlyWhenChanged_when_commitOrder` | — |
  | `should_healProgress_when_constructedWhileEditing` | — |
  | `should_ignoreEnter_when_surfaceIsMemories` | — |
  | `should_touchAndScrollToTray_when_barAddClicked` | — |
  | `should_onlyTouch_when_disabledUndoClicked` | — |

**Acceptance**
- Stage gate green.
- With the app installed, nothing changes, because nothing calls `enter` yet. The back owner stays None/Memories as before.

#### WP2-C: Rediscover in the feed (P0-9 UI and VM)

**Goal.** DATA §3, plus the rendering in SPEC §5 P0, in normal mode.

**Files**
- Modify: `ui/home/HomeViewModel.kt`, `ui/home/HomeUiState.kt`, `ui/home/HomeFeedDensity.kt`, `ui/home/HomeScreen.kt` (plumbing only), `ui/home/HomeEditorialContent.kt`, `app/src/debug/java/com/gpo/yoin/debug/MemoriesScreenshotActivity.kt`.
- Create: `ui/home/RediscoverSection.kt`.
- Tests: modify `ui/home/HomeViewModelTest.kt` and `ui/home/HomeFeedDensityTest.kt`; create `ui/home/RediscoverCopyTest.kt`.

**Depends on:** WP1-A (freeze/`emit`) and WP1-B (repository API, selection).

**Steps**
1. **UiState:** add the `rediscover` field and `HomeRediscoverItem` (DATA §3.1).
2. **VM** (DATA §3.2, §2.7):
   - constructor `nowMillis: () -> Long = System::currentTimeMillis`;
   - `MemorySignals.pool`;
   - `loadMemorySignals` builds **once** with `includeIneligible = true`; the pill and JBI memory use `pool.memoryEligible(48)`;
   - `rediscoverFor(...)` at publish time, with dedupe against JBI MemoryFocus, the pill latest, and albums played this session;
   - all 3 loaders and `observeMemorySignals`, extending the no-op check with `refreshedRediscover == latest.rediscover`;
   - `cachedRediscover()`;
   - `observeRediscoverRemovals()`, observing only rows since subscription (F9), going through `emit`/`currentContent` so removal queues while frozen;
   - `init` hook;
   - `homeContentCache` writes stay as they are.
3. **Density** (`HomeFeedDensity.kt`): add `rediscoverVisibleCount(units: Int, landscapePhone: Boolean): Int`.
   - Compact (N ≤ 2) → up to 6 in a scrolling shelf;
   - landscape phone or N in 4..7 → 2;
   - N ≥ 8 → 3.

   Add `rediscoverCardWidth(contentWidth, count, gap)`: Compact gives `0.86 × content`, or the full width when there is a single item; otherwise `(content − (n−1)·gap)/n`.
4. **`RediscoverSection`:**
   - Layout:
     - `HomeSectionTitle("Rediscover")`, then a LazyRow in **every** tier, so removal animates with `animateItem()`;
     - `userScrollEnabled = scrollEnabled && items > visible`;
     - the shelf bleed: `ignoreParentHorizontalPadding(start = frame.start, end = frame.end)`, `contentPadding = FeedFrameSidePadding`, and `horizontalEdgeFadeOnScroll` (the generic rule; RA keeps its owner exception);
     - keys `rediscover:${albumId}`.
   - Card:
     - `Surface(YoinContainerShapes.Card)`, height `132.dp × fontScale`, **no `IntrinsicSize`**;
     - container colours from `rememberActivityCardColors`;
     - left: `WidgetBackdropArtwork` Bun backdrop at 104dp, cover at 72%, no border;
     - right column, top to bottom:
       - eyebrow `"Rated 9.0 · Not played in Yoin for 7 months"` (labelMedium; accent colour in dark theme, base in light);
       - title `MarqueeText` (titleLarge SemiBold);
       - artist (bodyMedium);
       - footnote `"First played 2025.11 · 23 plays"` (labelSmall, tabular numbers).
   - Tap: `onAlbumClick(item.albumId.toString(), null)`. Mirror how the RA album card formats its id. **No haptic.**
   - Copy helpers:
     - `rediscoverAwayText(last, now)`: 90–364 days → "N months" with N = days/30; ≥ 365 → "1 year" / "N years" with N = days/365;
     - `rediscoverEyebrow`;
     - `rediscoverFootnote`: "1 play" / "N plays", date in local `yyyy.MM`;
     - `RediscoverPlaceholderText`.
   - Previews.
5. **HEC:**
   - make `ActivityCardColors`, `rememberActivityCardColors` and `HomeEmptyCard` `internal`;
   - replace the stub with `HomeSection.Rediscover -> if (rediscover.isNotEmpty()) item(key = "section-rediscover") { RediscoverSection(…) }`, using the same `animateItem`/`stagedBeat` pattern as RA;
   - add the parameter `rediscover: List<HomeRediscoverItem> = emptyList()`.
6. **HS:** pass `uiState.rediscover` through `HomeContent` to HEC.
7. **Debug harness:** add `--ez rediscover true`, which injects 4 fake items covering 1 item, months, years and a long title.

**Tests**
- Change every `getAlbumMemoryCandidates(any())` stub to `(any(), any())`, and the verify to `(48, true)` (DATA F3). New tests use distinct profile ids.
- Add the 11 VM tests in DATA §3.4.
- `HomeFeedDensityTest`: `should_showSixInShelf_when_compact`, `should_showTwo_when_medium`, `should_showThree_when_wide`, `should_showTwo_when_landscapePhone`.
- `RediscoverCopyTest`: away-text boundaries (90 days, 364, 365, 730), plural plays, and the eyebrow format using `Locale.US`.

**Acceptance**
- Stage gate green.
- In the debug harness with `--ez rediscover true`, the shelf renders on phone width, tablet portrait and landscape: run `adb shell am start -n com.gpo.yoin/.debug.MemoriesScreenshotActivity --ez rediscover true` and take a screenshot.

#### WP2-D: Home motion core

**Goal.** C5, C6, and the C7 skeleton. Lift and drop are implemented; the strip methods are no-op stubs documented "WP3-B".

**Files**
- Create: `ui/home/edit/HomeEditMotion.kt`, `ui/home/edit/HomeEditWiggle.kt`, `ui/home/edit/HomeCarryEngine.kt`.
- Tests: create `ui/home/edit/HomeEditMotionTest.kt` and `ui/home/edit/HomeCarryLiftTest.kt`.

**Depends on:** WP1-C.

**Steps**
1. **Envelope** (PORT §3.5):
   - `setEnvelope()`: when not editing, or reduced, or the mode is Kick, E → 0 on `envDown`. Otherwise the target is `carryActive ? .6 : 1`, and if it differs, E goes there on `envUp`. Keep the overshoot to about 1.095.
   - `touch(now)`: `lastTouch = now`, then `setEnvelope()`.
   - `idleTick(now)`: applies the 6-second rule only when IdleSettle, editing, `!carryActive`, `!pointerDown` and `E.targetValue > 0`.
   - The mode `Continuous` never idles.
2. **Clock** (PORT §3.6): `HomeEditClock` is the **one** `withFrameNanos` loop.
   - dt = `min(Δ, 1/24 s)`, and 1/60 s on the first frame after a resume.
   - It runs only while `clockShouldRun = !reduced && visible && (editing || P > .001) && (E.value > .0005 || E.isRunning)`, collected with `snapshotFlow{…}.collectLatest`.
   - Each frame calls `idleTick`.
3. **Ripple:** `ripple(section) = rippleProgress(P, displayed.indexOf(section), rippleOrigin, displayed.size)`.
4. **Kicks** (PORT §3.7):
   - lazy `Animatable` maps keyed by section and by (section, card);
   - `impulse` / `impulseCard` use `kickInitialVelocity` and `animateTo(0, specs.kick, v)` from the **current** value, with no snap;
   - skipped when reduced;
   - `stopKicks` snaps them to 0.
   - Card sign is `sign · alt`, where `alt` = +1 for even card index, −1 for odd.
5. **Pulse** (PORT §2.7): `snapTo(1)`, then `animateTo(1, specs.pulse, initialVelocity = 11.3)`.
6. **Entry effects** (`runEntryEffects`, PORT §2.5): one `snapshotFlow { P }` while editing.
   - The first time a block's `p_i ≥ .85`, mark it and impulse it at 1.0; the lifted block is marked but not kicked.
   - At `P ≥ .6`, set the latch to 0.
   - When `pa ≥ .9999`, clear `plateFrom`.
   - When not editing and `P ≤ .001`, clear `plateFrom`.
7. **Hide and show** (PORT §5.1–5.3). `onLayoutChange(change)`, guarded by `change.serial` (the generation):
   - newly disabled sections are added to `hiding`, with `hideS → .96` (`hideScaleOut`) and `hideA → 0` (`hideAlphaOut`). When `hideA` ends, remove them only if the serial is unchanged;
   - newly enabled sections: mark them kicked, snap their kick to 0, `hideA.snapTo(0)` and `hideS.snapTo(.96)`, then animate in (`showAlphaIn` / `showScaleIn`). Kick at 0.35 only when `kind == Show` and `isInViewport` is true, evaluated after the next frame;
   - tray rows: `rowA` 0 → 1 on `trayIn` for newly hidden sections; rows that disappear are removed instantly.
8. **Tray and footer** (PORT §5.4):
   - `onEnter`: `footerAlpha.snapTo(0)` and tray 0 → 1;
   - `onExit`: tray → 0 on `trayOut`, **then** footer 0 → 1 on `trayIn`;
   - `onSnapExit`: tray snaps to 0, footer to 1.
   - `trayMounted = editing || trayAlpha.value > .002` as a `derivedStateOf`; `footerMounted = !trayMounted`.
9. **`onEnter(origin, lifted)`:**
   - clear `kicked`;
   - set `rippleOrigin` to the index of `origin` in `displayed`, or 0;
   - `heldSection = if (lifted) origin else null`, `placeholdersAboveOpen = !lifted`;
   - `touch()`.

   `onExit` and `onSnapExit`: set the latch to 0, run `setEnvelope()`, clear `heldSection`, and snap where needed.
10. **Press state, targets and safe area:** C5. `HomeEditTargets` stores `LayoutCoordinates` from `onPlaced` and resolves them only at down time with `box.localBoundingBoxOf(c)`, skipping detached coordinates.
11. **Wiggle nodes** (C6):
    - `homeEditCard(index)` is a `Modifier.Node` implementing `DrawModifierNode`, `LayoutAwareModifierNode` (`onPlaced` → `motion.registerCard(section, index, rectInBlock)`, computed through `scope.blockCoordinates()?.localBoundingBoxOf(coords)`) and `SemanticsModifierNode` (normal mode: `onLongClick(label = "Edit Home") { scope.enterEdit(); true }`), and `CompositionLocalConsumerModifierNode`;
    - draw (PORT §3.3, card-level): angle from `cardAngleDeg(swayValue(E, ripple, t, params), blockKick, cardKick, alt, cardAmpDeg(w, h), gain = scope.liftGain() × (reduced ? 0 : 1), edgeBand(cardTop, cardBottom, safe))`, where `cardTop = blockTopInBox() + rect.top`; then `rotate(angle, pivot = center)`;
    - no-op when the local is null or `|angle| < .0005°`;
    - `homeEditBlockWiggle`: the same, using `blockAngleDeg` and the plate band (top − 6dp, bottom + 6dp);
    - `homeEditInteractive()`.
12. **Engine skeleton:**
    - `liftBlock`: if another section is still lifted, snap its lift and tint to 0; then `lift → 1` (`liftUp`), `liftTint → 1` (`liftTint`), `motion.stopKicks(section)`;
    - `dropLift(kickA)`: `lift → 0` (`liftDown`), `liftTint → 0`, then `motion.impulse(section, kickA)`. The kick is gated by `1 − lift` in draw;
    - every strip API is a no-op stub returning false/null, with KDoc pointing to PORT §4.

**Tests**
- Envelope:
  - `should_dropEnvelope_when_6sPassWithoutTouch`;
  - `should_holdEnvelope_when_pointerDown`;
  - `should_holdEnvelope_when_carryActive`;
  - `should_restoreEnvelope_when_touched`;
  - `should_targetSixTenths_when_carryActive`;
  - `should_neverRunClock_when_reduced`.
- Entry:
  - `should_kickEachBlockOnce_when_rippleCrosses085`;
  - `should_notKickLiftedBlock_when_entering`;
  - `should_clearLatch_when_progressReaches06`.
- Hide and show:
  - `should_removeHidingBlock_onlyIfSerialUnchanged`;
  - `should_kickShown_onlyForShowKindInViewport`;
  - `should_notKick_when_resetOrUndoShows`.
- Tray: `should_sequenceTrayThenFooter_when_exiting`, `should_snapTrayAndFooter_when_snapExit`.
- Lift:
  - `should_stopKicks_when_lifting`;
  - `should_snapPreviousLift_when_liftingAnotherSection`;
  - `should_kickAfterDrop_when_dropLift`.

**Acceptance:** stage gate green. The new files are not referenced by production code yet, apart from compile.

---

### Stage 3: Gestures, strips, chrome, and wiring with inert defaults

#### WP3-A: Gestures

**Goal.** P0-4: C8, the PORT §2.1 state machine, and SPEC §2.1.

**Files**
- Create: `ui/home/edit/HomeEditGestures.kt`.
- Tests: create `ui/home/edit/HomeEditGestureMachineTest.kt` and `ui/home/edit/HomeEditHitTesterTest.kt` (pure band resolution through `resolveSectionAt`).

**Depends on:** WP2-B (controller) and WP2-D (motion, press, targets, engine stubs).

**Steps**
1. **Pure `HomeEditGestureMachine`.** States are `Idle, Pressing, Lifted, Holding, HandleDown, Blank, Carry, Done`.
   - Events: `Down(hit, editing, carryBusy, flingInProgress, t)`, `Move(pos, t)`, `Up(t)`, `Cancel`, `Timer(kind)`.
   - Output: a list of actions. Constants: T = `viewConfiguration.longPressTimeoutMillis`, slop = `viewConfiguration.touchSlop`, `bodyHold = max(150, T/2)`.
   - **Normal mode, `Pressing`:**
     - slop exceeded → `Abandon` (scroll proceeds);
     - Up → `Abandon` (the card's `onClick` fires);
     - `T/2` with a block hit → `StartCharge(section, local)`;
     - T with a block hit → `[LongPressHaptic, Enter(section, lifted = true), LatchPlate, ReleaseCharge, Lift(section, local), ConsumeRest]`. **This order matters** (PORT §2.3);
     - T on blank → `[LongPressHaptic, ReleaseCharge, Enter(nearest, lifted = false), ConsumeRest]`, then `Done`.
   - **`Lifted`:**
     - moved more than slop *from the down point* → `StartCarry(y)` and the state becomes `Carry`;
     - Up → `DropLift(.5)` and `OpenPlaceholders`;
     - Cancel → `DropLift(0)`.
   - **Edit mode:**
     - every Down emits `Touch` and `PointerDown(true)`; every Up or Cancel emits `PointerDown(false)` and `Touch`;
     - `Excluded` → ignored (the button handles it);
     - if `carryBusy`: `TryRegrab(pos)` → `Carry` on success, otherwise ignore (PORT §4.12);
     - `Handle`: slop exceeded → `[DragStartHaptic, Lift, StartCarry]`; Up → `BlockTap`;
     - `Block` → `Holding`: slop before `bodyHold` → `Abandon`; Up → `BlockTap(section, local)`; at `bodyHold` → `[DragStartHaptic, Lift]` and the state becomes `Lifted`;
     - `Blank`: record `flingStop = flingInProgress` at Down; Up → `ExitBlank` unless `flingStop` or `blankHeld` (set after T); slop exceeded → `Abandon`.
   - **`Carry`:** Move → `CarryMove(y, t)`; Up → `CarryRelease(cancelled = false, t)`; Cancel → `CarryRelease(cancelled = true, t)`.
   - **`BlockTap` execution:**
     - card-level: if `motion.cardAt(...)` finds a card, `impulseCard(section, card, .5)`; otherwise `impulse(section, .5)`;
     - always `pulseHandle(section)`;
     - **it never exits.**
   - A second pointer while not `Idle` is ignored but **consumed**.
   - Mouse secondary press, normal mode → `Enter(hit section or nearest, lifted = false)`, consume, `Done`. There is no haptic. Read `currentEvent.buttons.isSecondaryPressed` and `PointerType.Mouse`.
2. **Pointer modifier** (`homeEditGestures`).
   - `pointerInput(Unit) { awaitEachGesture { … } }`, reading **only the `Initial` pass**, with `awaitFirstDown(requireUnconsumed = false, PointerEventPass.Initial)`.
   - If `down.isConsumed` (the memory bubble or arrow claimed it), return immediately.
   - Timers use `withTimeoutOrNull` around `awaitPointerEvent(Initial)`.
   - From the threshold on, consume every change in `Initial`.
   - Wrap the body in `try/finally`, and route coroutine cancellation to `Cancel`, so a system cancel releases a carry as cancelled and still commits a changed order (PORT §4.13).
   - Execute actions against `controller`, `motion`, `press` and `engine`:
     - `latchPlate` reads `press.charge.value` **synchronously before** `ReleaseCharge` launches;
     - `pressRect` uses `HomeEditTokens.PressBox/2` and `PressOutset × charge`.
   - Charge never starts when reduced.
3. **`HomeEditHitTester`:**
   - Bands come from `listState.layoutInfo.visibleItemsInfo` items whose key starts with `section-`, with `itemTop = info.offset − layoutInfo.viewportStartOffset` (the list sits at the Box origin).
   - `bleeds = section in setOf(RecentlyAdded, Rediscover)`.
   - Content x-range comes from `feedFrame.start/end`, read at down time.
   - Check order: `targets.isExcluded` → `targets.handleAt` → section band → Blank(nearest).
   - `local` = position minus (`contentLeft`, `itemTop`).

**Tests:** the machine, through its event and action API.

| Test | — |
|---|---|
| `should_abandon_when_movedPastSlopBeforeThreshold` | — |
| `should_abandonAndLetClickThrough_when_upBeforeThreshold` | — |
| `should_startChargeAtHalfT_when_pressOnBlock` | — |
| `should_notCharge_when_pressOnBlank` | — |
| `should_emitThresholdSequenceInOrder_when_longPressOnBlock` | — |
| `should_enterWithoutLift_when_longPressOnBlank` | — |
| `should_dropLiftWithHalfKick_when_liftedThenReleased` | — |
| `should_startCarry_when_liftedThenMovedPastSlopFromDown` | — |
| `should_liftAfterBodyHold_when_editMode` | — |
| `should_liftAndCarryOnFirstSlop_when_dragHandle` | — |
| `should_kickAndPulseNotExit_when_blockTappedInEdit` | — |
| `should_exit_when_blankTappedInEdit` | — |
| `should_notExit_when_blankTapStopsFling` | — |
| `should_notExit_when_blankHeldPastT` | — |
| `should_ignoreButTouch_when_excludedInEdit` | — |
| `should_regrab_when_settling` | — |
| `should_ignore_when_unfolding` | — |
| `should_releaseCancelled_when_carryCancelled` | — |
| `should_consumeSecondPointer_when_busy` | — |
| `should_enterWithoutLift_when_mouseSecondaryPress` | — |

Hit tester:
- `should_returnBlock_when_inSectionBand`;
- `should_returnBlankWithNearest_when_inGap`;
- `should_returnBlock_when_marginOverBleedingShelf`;
- `should_returnBlank_when_marginOverNonBleedingSection`;
- `should_includePlateOutset_when_editing`.

**Acceptance:** stage gate green.

#### WP3-B: Strips (fold, reorder, unfold)

**Goal.** P0-6, a **verbatim port of PORT §4**. This is the owner's must-keep animation.

**Files**
- Modify: `ui/home/edit/HomeCarryEngine.kt`.
- Create: `ui/home/edit/HomeCarryStack.kt`.
- Tests: create `ui/home/edit/HomeCarryEngineTest.kt` and `ui/home/edit/HomeCarryAnchorTest.kt`.

**Depends on:** WP2-D and WP1-C.

**Steps**
1. **`start(fingerY, t)`** (PORT §4.1–4.3):
   - `ids = host.displayedOrder()`;
   - `metrics = stripMetrics(...)` using `host.safeArea()`, `contentLeft` and `contentWidth`;
   - B_i from `host.plateRect`, or off-screen using `isAbove`;
   - `holeY.snapTo(slotY(k))`;
   - velocity reset plus a sample;
   - `fold.animateTo(1, specs.fold)`;
   - `motion.carryActive = true` (the motion re-runs `setEnvelope`);
   - `motion.placeholdersAboveOpen = true`;
   - session phase = Drag.
2. **`move`** (PORT §4.6):
   - 1:1 in y;
   - `while` loops for swapping;
   - neighbour `stripOffset` uses `snapTo(+dir·pitch)` and then `animateTo(0, specs.neighbourPx)`;
   - `holeY → slotY(j)` on `holePx`;
   - `segmentTick` on each swap;
   - rubber-banding at both ends, with `threshold()` on the first entry past 2dp;
   - the session's `display` is a snapshot float.
3. **`release(cancelled, t)`** (PORT §4.8):
   - compute `visY` **before** a possible fling swap;
   - at most one extra slot when |v| > 1600 dp/s;
   - `settleY.snapTo(visY − slotY(k))`, then `animateTo(0, specs.settlePx, v)` inside a `settleJob`;
   - then commit.
4. **Commit and anchor** (PORT §4.9):
   1. phase = Unfold;
   2. `changed = commitOrder(ids)`; the controller pushes undo, persists and confirms;
   3. `host.anchorAndAwaitLayout(...)`;
   4. recompute B;
   5. launch `lift → 0` and `liftTint → 0` without awaiting;
   6. `fold.animateTo(0, specs.fold)`;
   7. finish.
5. **Finish** (PORT §4.11):
   - clear the session; snap lift and tint (cancelling their jobs); `liftSection = null`;
   - `motion.carryActive = false`, then `motion.touch()`;
   - kick the carried section at 0.7, and every other section whose index changed at 0.35;
   - if an exit is pending, `finishDeferredExit(reason)`.
6. **`tryRegrab`** (PORT §4.12): only while `phase == Settle` and the press is inside the carried strip ±8dp.
   - Cancel `settleJob` so commit never runs.
   - Convert the display back with `unrubber`.
   - Snap `holeY`, call `dragStart()`.
7. **`deferExit(reason)`:** record the reason. If the phase is Drag, call `release(cancelled = true)`.
8. **`abortNow()`:**
   - if `ids != orig`, call `commitOrder(ids)` synchronously;
   - cancel all jobs, snap fold, lift, tint, settle and hole to 0, and clear the session;
   - `motion.carryActive = false`.
9. **`LazyListCarryHost(listState, feedFrame, safeArea, plateOutset, seamFlow, density)`** in `HomeCarryStack.kt`:
   - keeps a per-section height cache, written from `layoutInfo` whenever a section is visible;
   - `anchorAndAwaitLayout`:
     - if `firstVisibleItemIndex == 0`, skip;
     - otherwise `anchorScrollOffset(...)`, then `listState.requestScrollToItem(1 + order.indexOf(dropped), offset)`;
     - then `snapshotFlow { visible section keys }.first { follows order }`, with a 3-frame timeout fallback.
   - Expose `plateRect`, `displayedOrder` and so on from `layoutInfo` and `feedFrame`.
10. **`HomeCarryStack(engine, titles, covers: (HomeSection) -> List<StripCover>, modifier)`.** Composed only while `engine.session != null`, via `derivedStateOf`. A `Box(matchParentSize)` contains:
    - a `drawBehind` that paints, in z-order: the hole (`secondaryContainer` at α .35 × `holeAlpha`, radius 20dp, no stroke) < the non-carried plates < the carried shadow < the carried plate;
    - plate colour `lerp(surfaceContainerHigh, coverBase, tw)` when `tw > .05` and a base exists;
    - the carried plate uses `lerp(surfaceContainerHigh, surfaceContainerHighest, liftTint)`;
    - the shadow is a child sized to `r` using `graphicsLayer { shadowElevation = 6dp × clamp(lift) × smoothstep(.6, 1, fv) }`;
    - one label child per section: `Modifier.offset { frame.labelOffset }.graphicsLayer { alpha = frame.labelAlpha }`, at a fixed size of `w × hs`.

    Label layout: 16dp horizontal padding; up to 3 covers of size `cs` in the entity backdrop shape, overlapping by `−0.3·cs` with the first drawn on top and no border; a 12dp gap; the title (titleMedium SemiBold, 1 line, ellipsis); then `DragHandle` 24dp in `onSurfaceVariant`.

    `@Immutable data class StripCover(val model: Any?, val shape: Shape, val baseColor: Color?)`.

    **The strips never wiggle.**

**Tests**

| File | Tests |
|---|---|
| `HomeCarryEngineTest` (frame clock, fake host, recording feedback, fake `commitOrder`) | `should_swapAndShiftNeighbour_when_dragPastHalfPitch`, `should_tickOncePerSwap`, `should_rubberBandAndThresholdOnce_when_draggingPastEnds`, `should_flingAtMostOneExtraSlot_when_releaseVelocityAbove1600`, `should_commitIds_when_settleEndsWithChange`, `should_notConfirm_when_orderUnchanged`, `should_regrabOnlyWhileSettling`, `should_runDeferredExitAtFinish`, `should_commitChangedOrder_when_cancelled`, `should_kickCarriedAndMovedNeighboursAtFinish`, `should_commitAndTearDown_when_abortNow`, `should_centreCarriedStripOnFinger_when_started` |
| `HomeCarryAnchorTest` | `should_targetSlotTopPlusOutset`, `should_keepFirstVisibleAtLeastOne`, `should_skip_when_headerVisible` |

**Acceptance:** stage gate green. Every numeric constant comes from `HomeEditTokens`; there are no inline literals.

#### WP3-C: Edit chrome

**Goal.** P0-5 visuals: C9, SPEC §2.2.1–2.2.3, §2.4, PORT §2.2, §2.4–2.6, §2.8 and §5.

**Files**
- Create: `ui/home/edit/HomeEditBlock.kt`, `ui/home/edit/HomeEditTray.kt`, `ui/home/edit/HomeEditHeader.kt`.
- Tests: create `ui/home/edit/HomeEditHeaderTest.kt` and `ui/home/edit/HomeEditBlockTest.kt` (both Robolectric Compose).

**Depends on:** WP2-B and WP2-D.

**Steps**
1. **`HomeEditBlock`.** Root `Box(modifier.zIndex(if lifted 1f else 0f).graphicsLayer { … })`.
   - **Layer properties:**
     - scale `(1 − .012·charge if press.section == section) × (1 + .02·lift if liftSection == section) × motion.hideScale(section)`;
     - `transformOrigin` = press or lift origin as a fraction of size;
     - alpha `hideAlpha × feedFoldAlpha(fold)`.
   - **Plate child** (behind the content): `Modifier.matchParentSize()`, then a `layout {}` outset of `PlateOutsetH` and `plateOutsetVDp(itemSpacing)`, then `.seamDissolve()`, a `graphicsLayer` whose `shape` is the current plate rect and whose `shadowElevation = 6dp × lift`, and a `drawBehind` that paints:
     - normal plate: `Panel` (continuous), alpha `smoothstep(p_i)`, colour `lerp(surfaceContainerHigh, surfaceContainerHighest, liftTint)`;
     - growing plate (`plateFrom.section == section`): rect `lerp(plateFrom.rect, fullPlate, pa)`, alpha `max(.4·smoothstep(latch), pa)`, radius 20dp circular (`PanelAnimated` geometry);
     - charge pre-show: `chA = .4·smoothstep(charge)`; if it exceeds `pa`, draw `R_press⁺` at alpha `chA`.
   - **Content:**
     - `CompositionLocalProvider(LocalHomeEditCardScope provides scope)`;
     - when `placeholder`, an `ExpressiveSectionPanel` of height `112.dp × fontScale` holding `HomeSectionTitle(section.title)` and one line of copy (`"Nothing to jump back into yet"`, `"Nothing added this week"`, or `RediscoverPlaceholderText`);
     - `wholeBlockWiggle` applies `homeEditBlockWiggle`.
   - **Badges overlay** (TopEnd, vertically centred on the 28sp title row with a 48dp touch row):
     - composed only while `derivedStateOf { P > .01 }`, interactive only when editing;
     - `FilledTonalIconButton(Modifier.size(32.dp))` with `YoinSymbols.VisibilityOff` 18dp, `surfaceContainerHighest`, CD `"Hide <title>"`, `homeEditExclusion`. **No `minimumTouchTarget`**;
     - an 8dp gap;
     - handle icon `DragHandle` 24dp in a 48dp box, with `homeEditHandle(targets, section)` and scale × `handlePulse`; hidden when `displayCount == 1`;
     - badge scale and alpha from `badgeLocal(p_i)`, **not** `pa`.
   - **Hover (edit only):** pointer Enter/Exit lerps the handle tint from `onSurfaceVariant` to `onSurface` on fastEffects.
   - **TalkBack (edit only):** `stateDescription = "Section ${i+1} of $n"`, plus `customActions` for "Move up" and "Move down" (when possible, calling `controller.move`) and "Hide".
2. **Tray** (PORT §5.4, SPEC §2.4):
   - `tray-title` is `HomeSectionTitle("Hidden")`, or the empty line "Hidden sections appear here";
   - each row: `Panel`, `surfaceContainerHigh`, height `64.dp × fontScale`, 16dp padding;
   - row content: title (titleMedium SemiBold) over `supportingText` (bodySmall, `MarqueeText`, one line, **no intrinsics**);
   - a "New" chip (labelSmall, `tertiaryContainer`, `YoinShapeTokens.Full`) when the section is in `newBadges`;
   - `FilledTonalIconButton(Modifier.size(40.dp))` with `YoinSymbols.Add`, CD `"Show <title>"`. The whole row is clickable and calls `controller.show`;
   - row alpha = `trayAlpha × rowAlpha × feedFoldAlpha(fold)`;
   - `edit-footer` is a `TextButton` "Reset Home" with `YoinSymbols.Refresh`, enabled when `canReset`, calling `controller.reset()`;
   - every interactive element registers `homeEditExclusion`;
   - **tray rows never wiggle**.
3. **Footer entry** (`home-edit-entry`): a centred `TextButton` with `YoinSymbols.Edit` 18dp and labelLarge in `onSurfaceVariant`, 48dp tall, plus the "New" chip when `newBadge`. Alpha = `footerAlpha`. `onEnter` is called by HEC with the last displayed section and `lifted = false`.
4. **All-hidden item:** `HomeEmptyCard("Your Home is empty", "Press and hold anywhere, or tap Edit Home, to bring sections back")`.
5. **Header** (PORT §2.8, SPEC §2.2.3):
   - `HomeEditHeaderTitle` renders "Home" with alpha `1 − smoothstep(.2, .6, P)`, measured normally, plus a zero-width overlay "Edit Home" (`Modifier.layout { … layout(0, h) }`) with alpha `smoothstep(.4, .8, P)`;
   - both use `seamFade(fontSize)`;
   - the overlay sets `liveRegion = Polite` and is composed while `P > .01`;
   - `HomeEditHeaderHint`: "Drag to reorder" (labelMedium, `onSurfaceVariant`), alpha `smoothstep(.5, 1, P)`, end-aligned; a custom `Layout` places it only if it fits, never changing height;
   - `homeEditHeaderIcon` sets alpha `1 − smoothstep(0, .5, P)`;
   - `rememberHomeEditIconsEnabled` returns `P < .5`. Callers keep the icon composed and use `clearAndSetSemantics {}` when disabled.

**Tests**
- `HomeEditHeaderTest`: `should_keepHeaderHeightConstant_when_progressSweeps0To1` (11 steps with the gear at 48dp), `should_notChangeWidth_when_editTitleOverlayShown`, `should_hideHint_when_itDoesNotFit`.
- `HomeEditBlockTest`:
  - `should_notComposeBadges_when_progressAtRest`;
  - `should_hideHandle_when_singleSection`;
  - `should_exposeMoveAndHideActions_when_editing`;
  - `should_omitMoveUp_when_firstSection`;
  - `should_renderPlaceholderCopy_when_placeholder`.

**Acceptance:** stage gate green. Previews exist for the block (normal, edit, placeholder), the tray (rows, empty), the footer and the header (P = 0, .5, 1).

#### WP3-D: Shell and card wiring (inert until Stage 4)

**Goal.** Wire the bar and the `HomeScreen` parameters, and add card-level hooks and keys.

**Files**
- Modify: `ui/navigation/YoinNavHost.kt`, `ui/home/HomeScreen.kt`, `ui/home/HomeMemoryBubble.kt`, `ui/home/HomeEditorialContent.kt` (card hooks, keys and the shelf parameter **only**), `ui/home/HomeWidgetGrid.kt`, `ui/home/RediscoverSection.kt`.
- Tests: `ui/home/HomeMemoryBubbleTest.kt`, only if it is affected.

**Depends on:** WP2-A, WP2-B and WP2-D.

**Steps**
1. **NH:**
   - `val barEditPose = remember(homeEdit) { BarEditPose(homeEdit.progressReader, { homeEdit.leftSlot }, homeEdit::barLeftSlotClick, homeEdit::barDone) }`;
   - pass `editPose = barEditPose, editing = homeEditing` to the **shell's** `YoinChromeGroup` only. Detail bars stay untouched;
   - in the `HomeScreen(...)` call, replace `suppressBackHandling = …` with `homeCovered = showNowPlaying || paneOpen` and add `editController = homeEdit`.
2. **HS:**
   - rename the parameter `suppressBackHandling` to `homeCovered` (the old editor's `BackHandler` now uses `!homeCovered`, which has the same semantics);
   - add `editController: HomeEditController? = null` and hold it; it is passed to HEC in Stage 4;
   - nothing else changes; the old editor still works.
3. **HMB:** `MemoryBubbleOverlay` gains `editProgress: () -> Float = { 0f }` and `editing: Boolean = false`.
   - `covered = covered || editing`;
   - `onArrowTap` is null while editing (in the `SideEffect`), and `tapTargetAt` must return null for the arrow when it is null, so the touch watcher no longer consumes downs there;
   - the arrow's `graphicsLayer` alpha is multiplied by `1 − smoothstep(0, .5, editProgress())`.
4. **HEC card hooks** (HOME §2): on each card's **outermost** root modifier, before `elasticPress`, add `.homeEditCard(index)`, and set `enabled = homeEditInteractive()` on its `noRippleClickable`.
   - Activities: hero 0, small 1, wide 2, strip 3; in the units grid, the `placed` index; in the landscape row, the row index.
   - RA: track tiles 0–3, then albums at `4 + i`.
   - `HomeEmptyCard` gets **no** card hook (it uses whole-block wiggle).
   - Rename keys to `section-activities`, `section-jump_back_in` and `section-recently_added`, using `section-${section.id}`.
   - Add the parameter `shelfScrollEnabled: Boolean = true` to the RA `LazyRow` (`userScrollEnabled`).
5. **HWG:**
   - `WidgetCard12` (the whole 1×2 card) and the **standalone** `WidgetCoverBlock` get `.homeEditCard(index)` + `enabled`. Never hook a cover block nested inside a 1×2 card;
   - index = `layout.cells` order on the template path, otherwise row-major;
   - **delete both `performContextClick()` tap haptics** (SPEC §2.8).
6. **Rediscover:** `homeEditCard(i)` + `enabled` on each card.

**Tests:** `HomeMemoryBubbleTest` gains `should_notClaimArrowTap_when_editing`.

**Acceptance**
- Stage gate green.
- The installed app behaves exactly as before: no `LocalHomeEditCardScope` is provided, so the hooks are no-ops.
- `grep -n '"section-' HomeEditorialContent.kt` shows only `section-${…}`.

---

### Stage 4: Feed integration, tests and docs

#### WP4-A: Feed integration

**Goal.** Make it all work end to end, and delete the old editor (Q7).

**Files:** everything under `ui/home/**`, including `ui/home/edit/**` for integration fixes, and `app/src/debug/java/com/gpo/yoin/debug/MemoriesScreenshotActivity.kt`. `HomeLayoutEditor.kt` is **deleted**.

**Depends on:** all of Stage 3.

**Steps**
1. **HS:**
   - delete `isEditMode`, the `activeProfileId.drop(1)` collector, the `BackHandler`, the `AnimatedContent(isEditMode)` and the `HomeLayoutEditor` call, along with their now-unused imports;
   - keep the first-entrance graphicsLayer `Box`;
   - in `HomeContent`, remove `isEditMode`, `onEnterEditMode`, `onExitEditMode` and `onLayoutChange`, and add `editController: HomeEditController? = null` and `footerNewBadge: Boolean = false`;
   - collect `viewModel.unseenNewSections` and pass `isNotEmpty()` as `footerNewBadge`.
2. **HEC setup.** Remove the `onEnterEditMode` parameter and add `editController` and `footerNewBadge`. Then:

   ```kotlin
   val controller = editController ?: rememberStandaloneHomeEditController(HomeLayout(sections))
   val reduced = rememberHomeEditReducedMotion()
   val specs = rememberHomeEditSpecs(reduced)
   val feedback = rememberYoinHaptics().asHomeEditFeedback()
   val motion = remember { HomeEditMotion(scope, specs, LocalHomeWiggleStyle.current, { reduced }, controller.progressReader, { controller.isEditing }, feedback) }
   // plus remembered press, targets, engine (commitOrder = controller::commitOrder, finishDeferredExit = controller::finishDeferredExit),
   // carryHost = LazyListCarryHost(...), hitTester
   ```

   - `DisposableEffect(controller)` sets `controller.layer = object : HomeEditLayer { … }`, delegating to motion and engine; `scrollToTray` launches the tray scroll. Clear it on dispose.
   - `LaunchedEffect(motion) { motion.runEntryEffects() }`; `HomeEditClock(motion)`.
   - `motion.visible` follows lifecycle ≥ RESUMED.
   - `SideEffect { motion.displayed = … }`.
3. **Root Box chain:** `fillMaxSize → onPlaced { targets.attachBox(it); boxCoords = it } → watchMemoryBubbleTouches(...) → homeEditGestures(...) → voteHighFrameRate(active) → seamTide(...)`.
   - `active = derivedStateOf { P in .001..0.999 || engine.session != null || press.charge.isRunning }`.
   - Delete the `LazyColumn`'s `detectTapGestures` and the `detectTapGestures` import.
   - Safe area:
     - top = `statusBarTopPx + SeamDissolveTokens.TideRest + 8dp`;
     - bottom = `(LocalSeamBarField.current?.takeIf { it.attached }?.bounds?.top?.minus(boxCoords.positionInRoot().y) ?: (boxHeight − bottomPadding)) − 8dp`.
4. **Other gates:**
   - Pull to Memories: the first line of `onPreScroll` returns `Offset.Zero` when `editingState.value`, read via `rememberUpdatedState`.
   - `MemoryBubbleOverlay(editProgress = controller.progressReader, editing = controller.isEditing)`.
5. **Rendering** follows §2.5 using `controller.layoutToRender(HomeLayout(sections))`.
   - Every section is wrapped in `HomeEditBlock`, with the shared `animateItem` helper (one function replacing the 4 duplicates) and `stagedBeat` kept on the content.
   - `placeholder = editing && <section empty>`.
   - `wholeBlockWiggle` is set for placeholders and for Activities when it is empty.
   - `blockTopInBox = { listState.layoutInfo.visibleItemsInfo.firstOrNull { it.key == "section-${id}" }?.let { it.offset - layoutInfo.viewportStartOffset }?.toFloat() }`.
   - Placeholders above `motion.heldSection` wait until `placeholdersAboveOpen`.
   - RA `shelfScrollEnabled = !editing`; Rediscover `scrollEnabled = !editing`.
   - The tray, footer and all-hidden items come from WP3-C. `footer.onEnter = { controller.enter(lastDisplayed, lifted = false) }`.
6. **Header:**
   - title → `HomeEditHeaderTitle`;
   - the `weight(1f)` slot becomes `Box(Modifier.weight(1f).memoryBubbleFreeSpan(…))` containing `HomeEditHeaderHint(visible = controller.sessionHints.showHeaderHint)`. The debug pill/chevron variants keep their content and gain `homeEditExclusion` + `homeEditHeaderIcon`;
   - gear: `homeEditExclusion(targets, "gear")`, `homeEditHeaderIcon`, `enabled = rememberHomeEditIconsEnabled(...)`, and `clearAndSetSemantics {}` when disabled;
   - `Row(Modifier.heightIn(min = 48.dp))`;
   - the title gets a `customActions` "Edit Home" action in normal mode.
7. **Carry overlay** goes inside the seamTide `Box`, after the `LazyColumn` and before the bubble overlay. `HomeCarryStack(engine, covers = ::stripCoversFor)`:
   - Activities: hero, small, wide;
   - JBI: the memory card, then the first 2 other cells in render order;
   - RA: the first 3 albums, falling back to track covers with the `Thumb` shape;
   - Rediscover: the first 3.

   `baseColor` comes from the palette the card already has, if it is readily available; otherwise null, which means no tint (open point PORT §9.2).
8. **Effects:**
   - `SeamFlow.held = fold > 0` (`snapshotFlow`);
   - if `feedFrame` class or width changes while a session exists, `engine.release(cancelled = true)`;
   - scroll to tray: `animateScrollToItem(trayTitleIndex, −80dp)` if the tray is off-screen, otherwise `animateScrollBy(delta, specs.settle)`.
9. **Debug harness:**
   - `--ez edit true` starts in edit through the standalone controller, by calling `enter(null, false)` once;
   - `--es wiggle Kick|IdleSettle|Continuous`, `--es wiggleTarget Block|Card` and `--ez reduced true` provide `LocalHomeWiggleStyle` and `LocalMotionProfile`;
   - fix the KDoc that says "JBI leads", since `HomeEmptyCard` actually leads.
10. **Comments:** update `HomeSection.kt` (`supportingText` is for the tray and TalkBack), `HomeViewModel.kt` (`activeProfileId`) and the HEC KDoc.

**Tests**
- All existing tests stay green.
- Add a Robolectric `HomeEditIntegrationTest`, using the standalone controller and `AdaptiveReduced`:
  - `should_disableCardClicks_when_editing`;
  - `should_renderTrayRow_when_sectionHidden`;
  - `should_renderPlaceholder_when_jbiEmptyAndEditing`;
  - `should_renderFooterEntry_when_notEditing`;
  - `should_renderAllHiddenCard_when_everySectionDisabled`.

**Acceptance**
- Stage gate green.
- `rg -n "HomeLayoutEditor|isEditMode|onEnterEditMode" app/src` returns nothing.
- On the tablet, a long-press on a card enters edit; dragging folds into strips; Done exits.
- Every item in the R4 checklist is satisfied.

#### WP4-B: androidTests and docs

**Files**
- Create: `app/src/androidTest/java/com/gpo/yoin/ui/home/HomeEditModeTest.kt`.
- Modify: `app/src/androidTest/java/com/gpo/yoin/ui/UiTestFixtures.kt` (shared fakes; reuse the data from `HomeSeamPreviewTest`).
- Docs: `docs/design.md` (Home section), `docs/haptic-feedback.md` (around the "长按卡片 → 上下文菜单" line), `AGENTS.md`, `docs/adaptive-principles.md` (only the existing HomeEdit line).

**Depends on:** the WP4-A contract. It compiles against the final `HomeContent` signature.

**Steps**
1. The harness composes `HomeContent` inside `YoinTheme`, with `LocalMotionProfile provides AdaptiveReduced`, the standalone controller and fake content.
2. Tests, covering the P0-4 list with the corrected cases from HOME §4.4:

   | Test | Purpose |
   |---|---|
   | `should_notFireOnClick_when_cardLongPressed` | long-press ≠ click |
   | `should_openCard_when_tapped` | normal tap |
   | `should_scroll_when_draggedBeforeThreshold` | early drag scrolls |
   | `should_enterEdit_when_longPressInPageMargin` | tablet margin (`width ≥ 800dp` device config) |
   | `should_scrollShelf_when_raSwipedHorizontally` | RA still scrolls |
   | `should_notOpenMemories_when_longPressAtTop` | — |
   | `should_enterEdit_when_mouseSecondaryClick` | `injectInput { mouse }` |
   | `should_notShiftHeldBlock_when_jbiEmptyAndRaLongPressed` | **corrected from "Activities empty"** |
   | `should_foldIntoStripsAndPersistOrder_when_liftedAndDragged` | P0-6 |
   | `should_moveHideShow_viaCustomActions` | TalkBack |
   | `should_exit_when_blankTapped` | — |
   | `should_exposeEditHomeLongClick_onCards` | — |
3. Docs:
   - `design.md`: describe in-place edit mode, the footer entry, the Rediscover section and the bar edit pose;
   - `haptic-feedback.md`: "long-pressing a Home card enters edit mode"; the new haptics table (SPEC §2.8); the JBI tap haptic removed;
   - `AGENTS.md`, under RootSection: "Home edit mode is an in-page state owned by `ShellBackOwner.HomeEdit`; P0 back = discrete Done via a shell-level `BackHandler`." The P1 scrub exception is **not** added yet.
   - Also fix the stale AGENTS lines on the multi-provider status and `LegacyViewCompat`, in one short note only (SPEC §1.4).

**Acceptance:** `:app:compileDebugAndroidTestKotlin` is green. The tests run in Stage 5 on the device.

---

### Stage 5: Device QA, fix loop, release

The lead works with one QA agent. Fixes are **serialized**: one fixer at a time, owning whatever files the fix needs. Each fix is followed by a stage-gate build.

#### 5.1 Device setup (Pixel Tablet only)

```sh
export ANDROID_ADB_SERVER_PORT=5038
ADB="$HOME/Library/Android/sdk/platform-tools/adb -s adb-3408105H803AEE-Deuouc._adb-tls-connect._tcp"
$ADB get-state || echo "server/device missing — see ~/.claude/CLAUDE.md"
```

- **If the server is missing:** from a non-sandboxed shell, run `ANDROID_ADB_SERVER_PORT=5038 ADB_LOCAL_TRANSPORT_MAX_PORT=5554 adb start-server`.
- **Never** run `kill-server` on :5037, and never touch `emulator-5554` or `emulator-5556`.
- **If the device is missing:** ask the owner to re-enable wireless debugging.
- **The tablet is shared with other sessions.** Before starting, check `$ADB shell dumpsys activity activities | grep mResumedActivity`. Take a fresh screenshot before every coordinate-based tap.
- **Install:**
  1. `lockf … ./gradlew :app:assembleDebug :app:assembleDebugAndroidTest`;
  2. `$ADB install -r app/build/outputs/apk/debug/app-debug.apk`. If the signature mismatches, check what is installed first; the debug and `releaseDebugSigned` builds share a key;
  3. `$ADB install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk`.
- **androidTests:** `$ADB shell am instrument -w -e class com.gpo.yoin.ui.home.HomeEditModeTest com.gpo.yoin.test/androidx.test.runner.AndroidJUnitRunner`. Gradle `connectedAndroidTest` over :5038 is unverified, so use this direct path.

**Postures:**

| Posture | Command | Notes |
|---|---|---|
| Portrait, 800dp (Medium) | `$ADB shell cmd window user-rotation lock 0` | Verify the width in a screenshot; the mapping may be swapped. |
| Landscape, 1280dp (Expanded) | `… lock 1` | Restore with `cmd window user-rotation free` when done. |
| Phone width (Compact) | `$ADB shell wm size 1080x2400 && $ADB shell wm density 420` | Then **always** run `$ADB shell wm size reset && $ADB shell wm density reset`, even if a step fails (use a shell `trap`). Confirm with `wm size` that only "Physical" is reported. |

**Input scripting:**
- long-press: `$ADB shell input swipe X Y X Y 900`;
- long-press then drag: `input motionevent DOWN X Y`, `sleep 0.6`, several `input motionevent MOVE X Y2…`, then `input motionevent UP X Y2`;
- tap: `input tap`;
- back: `input keyevent 4`;
- home (for ON_STOP): `input keyevent 3`.

**Recording and evidence:**
- record with `$ADB shell screenrecord --time-limit 25 --bit-rate 8000000 /sdcard/he_<id>.mp4` in the background while scripting, then `$ADB pull` and `$ADB shell rm` the file;
- screenshots with `$ADB exec-out screencap -p > <id>.png`;
- deliver all evidence as **one Artifact page** (screenshots base64-embedded, videos uploaded as assets), per the owner's preference. The owner cannot see images read with the Read tool.

**Frame stats per scenario:** run `$ADB shell dumpsys gfxinfo com.gpo.yoin reset`, perform the scenario, then `dumpsys gfxinfo com.gpo.yoin` and record the janky-frame % and the 90/99th percentiles. Scenarios: entry, wiggle, fold+unfold, hide.

**Data prerequisite:** use a Subsonic profile that has Activities, JBI and RA content. Rediscover will probably have no real data; verify it with the harness: `am start -n com.gpo.yoin/.debug.MemoriesScreenshotActivity --ez rediscover true --ez edit true`.

#### 5.2 What to screen-record

Record R1–R5 in each of the 3 postures unless noted.

| ID | Scenario |
|---|---|
| R1 | Entry by long-pressing a JBI card: charge pre-show from T/2, plate growing from the press point, lift, entry ripple and kicks, badges popping in, header cross-fade and hint, bar morphing to `[Undo|Add][Done]` |
| R2 | Idle wiggle for 8 s: card-level sway, cards near the bar and the tide stay still, the 6-second settle, one tap restarts it |
| R3 | Carry: drag Activities below RA, showing swap ticks, the hole, rubber-banding at both ends, a fling one slot further, re-grab during settle, unfold with the anchor (start once with the header off-screen and once with it visible) |
| R4 | Hide → tray row → Show (kick) → bar Undo → Reset; then hide every section, Done, and check the all-hidden card and the footer entry |
| R5 | Exit paths: Done, system back, blank tap, footer "Edit Home" re-entry; then a blank long-press in the 56dp tablet margin enters edit without a lift |
| R6 | Landscape only: detail column open (merged bar `[Undo][Done][Play▾][Shuffle]`); back exits edit **before** closing the column |
| R7 | Now Playing panel open (navOnly bar shows icons only); first back closes the panel, second back exits edit |

#### 5.3 Device QA checklist (pass/fail per posture)

**Entry**
1. A tap on any card still opens it, and the JBI tap has no haptic.
2. Scrolling before 400 ms scrolls.
3. A long-press on a card enters edit and lifts its section, and the card does **not** open.
4. A long-press on a gap, the header title, a margin or the end of the feed enters edit without a lift. The ripple starts at the nearest block.
5. A long-press on the gear or the memory arrow/bubble does not enter edit.
6. A long-press at the top does not open Memories, and pull-down to Memories is dead while editing.
7. The RA shelf scrolls in normal mode and is frozen in edit mode.

**Visuals**
8. Plates are outset 8/6dp and never touch neighbouring plates.
9. Plates and badges dissolve at the tide; nothing overlays the seam. The bottom halftone stays still.
10. The header height does not jump during the P animation.
11. The hint shows only in edit sessions 1 and 2. Check sessions 1–3 across app restarts.
12. Placeholders appear for an empty JBI/RA/Rediscover, and **the held block does not move** when they appear.
13. The handle is hidden when only one section is enabled.
14. Hovering the drag handle tints it. This needs a mouse and is optional; the owner can check it.

**Wiggle**
15. Corner movement is about 1dp; the TallSignal does not look exaggerated (D8).
16. Rotating cards are not clipped in shelves or in the JBI `springHeight` (D7).
17. Under the harness flag `--ez reduced true` there is no wiggle and no charge, but P, fold and the tray still animate.

**Carry**
18. Strips match the prototype: 64dp tall (tablet width capped at 560), 3 real covers, the label fades in late.
19. The feed cross-fades and is never hard-cut.
20. After unfold, the order is persisted: force-stop and relaunch, and the order holds.
21. Rubber-band at the bottom edge: no ugly overlap with the bar (D6).

**Bar**
22. Pose correct in the nav, merged, navOnly and EdgeSplit postures. EdgeSplit = phone landscape: check it with `wm size 2400x1080` and density 420, then reset.
23. Disabled Undo is shown at 38%; Add scrolls to the tray; Done exits.
24. The pill cannot be tapped while editing; a Library long-press does nothing.

**Exits**
25. Done / back / blank exit.
26. Switching to Library exits (snap).
27. Settings exits (snap).
28. Home button (ON_STOP), then return: the page is not in edit.
29. Switching profile exits.
30. Opening a detail column while editing commits, exits, then opens.
31. Rotating during edit keeps edit mode; rotating mid-carry drops the strip in place.

**Persistence**
32. Entering and exiting without changes, then adding a fresh profile, behaves like an uncustomized profile. Covered by the unit tests; spot-check only.

**Rediscover (harness)**
33. Card layout matches spec at all 3 widths; long titles marquee; no `IntrinsicSize` crash.

**Performance**
34. No sustained jank in the gfxinfo runs. Compare with a baseline run of plain scrolling.

**Accessibility**
35. The androidTest custom-action test passes. A live TalkBack walkthrough is left to the owner, because enabling TalkBack is a system setting.

**Apple Music** (if an Apple profile is available)
36. The RA placeholder shows in edit mode, and the id caveat in DATA §2.7 is observed or logged.

#### 5.4 Release build for the owner's phone

Haptics can only be accepted on the phone; the tablet has no motor.

```sh
lockf -t 3600 /tmp/yoin-gradle.lock ./gradlew :app:assembleRelease :app:assembleReleaseDebugSigned
```

1. If `hasReleaseKeystore` is false, `assembleRelease` produces an unsigned APK. Say so in the report.
2. Smoke-test the R8 build on the tablet with the `releaseDebugSigned` APK, which uses the same debug key, so it installs over the debug build: enter edit, reorder, force-stop, relaunch. The order must persist, which proves that kotlinx-serialization `JsonElement` decoding survives R8.
3. Delivery, per the owner's established flow:
   - first delete **only** the `Yoin-*.apk` files Claude uploaded earlier from `~/Library/CloudStorage/GoogleDrive-p2o51willam@gmail.com/我的云端硬盘/Inbox/`;
   - copy `Yoin-0.5.0-release-<yyyymmdd>-<sha7>.apk` and `Yoin-0.5.0-release-debug-signed-<yyyymmdd>-<sha7>.apk`;
   - confirm sync with the `com.google.drivefs.item-id#S` xattr;
   - report which old files were removed.
4. Give the owner the SPEC §2.8 haptics checklist to run on the phone: long-press, drag start, segment tick, threshold, confirm, toggle, reject, click.

---

## 4. Integration notes

- **Stage order is a hard dependency chain:** 1 → 2 → 3 → 4 → 5. Only Stage 4 makes the feature reachable. The tree is shippable (no visible change) at the end of stages 1–3.
- **Expected integration hot spots in WP4-A:**
  - `animateItem` placement freezing during fold;
  - the item index used by the anchor (`1 + order.indexOf(dropped)`, valid because every enabled section has an item once the carry has started);
  - the timing of placeholder insertion.

  WP4-A may edit any file under `ui/home/edit/**` to fix these.
- **When the lead reviews WP4-A**, re-read PORT §0.2. Every listed item where proto wins over SPEC must hold. The two most often missed:
  - the feed and strip plates cross-fade (no hide in a single frame);
  - re-grab works only while settling.

---

## 5. Release step

See §5.4 above. It runs once, at the end of Stage 5, after the fix loop is green and the QA Artifact has been shared.

---

## 6. Risks and mitigations

| # | Risk | Mitigation |
|---|---|---|
| 1 | The Initial-pass detector conflicts with clickable cards, LazyRow, the Memories nested scroll, the bubble and buttons | The detector consumes nothing before T and abandons at slop. Exclusions use registered bounds. It bails on `down.isConsumed`. The pure machine is unit-tested, and the androidTest matrix (WP4-B) covers each case. |
| 2 | The lifted block is pushed out from under the finger by items inserted above it | The placeholder-above gate (`placeholdersAboveOpen`); the tray and footer only ever insert below; a dedicated androidTest. |
| 3 | The anchor causes a one-frame seam hard cut or flips header visibility | `anchorScrollOffset` is pure and tested; a single `requestScrollToItem`; skipped when the header is visible; first-visible index clamped ≥ 1; `SeamFlow.held` while fold > 0. |
| 4 | Per-frame values leak into composition and recompose all of Home or YoinShell | R4. Composition gates are `derivedStateOf` booleans. P is passed as a lambda. Lead-review greps, plus Layout Inspector recomposition counts during QA. |
| 5 | Lookahead double measure; negative constraints | No state writes in measure; widths `coerceAtLeast(0)`; no `SubcomposeLayout`, `AnimatedContent` or intrinsics in new code; text guards in the bar. |
| 6 | Seam callback cost from `graphicsLayer` scale (charge, lift, hide) | Accepted: one block at a time, ≤ 300 ms (SPEC §2.0). Long-running rotation uses `drawWithContent`. Checked with gfxinfo in QA. |
| 7 | `fold` snaps 3–6 px on its last frame | `withThreshold(.001)` (R7), with a unit test that the spec's threshold is 0.001. |
| 8 | Role trap: Standard springs used in Home | All Home specs are resolved in HEC composition. The controller uses only stageSettle or reduced fastEffects. |
| 9 | Accidental persistence turns a user into "customized" (breaks Q6a) | The controller never calls `applyLayout` on enter or exit, and its operations are no-ops when nothing changes. The store skips identical writes. A Default layout clears the row (D2). All covered by tests. |
| 10 | Rediscover changes the Memory pool or the pill's newsKey | One build with `includeIneligible`; `memoryEligible(48)` is identical (golden test); the pill and JBI use only the eligible subset; `playCount` is untouched. |
| 11 | Rediscover is empty on real devices | QA through the harness fakes. The copy is honest ("Rate an album 8 or higher…"). DATA F2 is documented and the seed pass is deferred to P1. |
| 12 | Apple Music album id forms differ, so played albums never leave Rediscover | Logged as a QA item (DATA §2.7 caveat); fix in P1 if it reproduces. |
| 13 | Detail chrome and edit coexist, so the pill is forced to compose or the window hand-off mismatches | `snapExit()` before every `armDetailChrome()`; `BarGeometryTest` #10; R6 recording. |
| 14 | Invisible-but-tappable targets (pill, EdgeSplit capsule, half-faded rows) | Click routing uses discrete `editing`; the pill and capsule clicks are disabled while editing. |
| 15 | The 6-second wiggle prevents Compose tests from idling | Every androidTest and Robolectric harness uses `AdaptiveReduced`. |
| 16 | Several agents and parallel sessions in one tree: Gradle lock contention, broken intermediate compiles, clobbered hunks | `lockf` around every Gradle call; disjoint ownership per stage; `git diff -- <file>` before editing; never revert others' hunks; the lead runs the stage gate. |
| 17 | The tablet is shared with other sessions; display overrides left behind | Re-screenshot before each tap; a `trap` that always runs `wm size reset` and `wm density reset`; restore rotation with `cmd window user-rotation free`. |
| 18 | The tablet has no vibration motor | Visual twins for every haptic (SPEC §2.8); acceptance on the owner's phone through the release APKs. |
| 19 | The cross-repo `Undo` symbol build fails | Text-label fallback in WP2-A; symbols committed only if authorized. |
| 20 | Deleting the old editor regresses accessibility | TalkBack actions land in the same stage (WP3-C and WP4-A) and are verified by an androidTest before release. |
| 21 | R8 strips kotlinx-serialization `JsonElement` paths | Persistence smoke test on the `releaseDebugSigned` build (§5.4 step 2). |
| 22 | Taste mismatch on the tablet (amplitude, strips) | Every number lives in `HomeEditTokens`. The harness toggles wiggle mode and target. R1–R3 recordings go to the owner before release. |

---

## 7. Errata from the adversarial review (BINDING — overrides anything above)

The numbered items below are in `critique.md` (same directory). Each WP must apply the items mapped to it:

| WP | Errata items |
|---|---|
| WP1-A | 32 |
| WP1-B | — (keep DATA §2 golden-test guarantee) |
| WP1-C | 12, 13 (non-composable `HomeEditSpecs.create(...)`, explicit Expressive role, injectable `uptimeMs`), 30 |
| WP1-D | 14 — add `Undo` to the local yoin-symbols generator; if the Yoin build resolves yoin-symbols from the local source (composite build) the app may use `YoinSymbols.Undo`, otherwise WP2-A uses the text label. Do NOT commit/tag/push yoin-symbols. |
| WP2-A | 11, 14 |
| WP2-B | 2 (`onSurfaceLost`, NH memories no-ops while editing), 3, 13 (`uptimeMs`), 22, 23, 24, 31 |
| WP2-C | 21 (N≤2 shelf ≤6; landscape phone or N 3–4 → 2; N≥5 → 3) |
| WP2-D | 4 (onExit/onSnapExit drop the lift and release the press), 13, 18, 20 |
| WP3-A | 4 (machine → Done when edit ends mid-gesture; executor drops edit-only actions when not editing), 17 (fading-out sections hit-test as Blank), 19, 27 |
| WP3-B | 1 (carry order from `controller.draft`, never from rendering), 5, 6, 7, 8, 9 (WP3-B also owns `ui/component/ExpressiveBackdropPalette.kt` for `cachedBackdropColors`), 25, 33, 34 |
| WP3-C | 16, 26 (strings table below), 28, 29 |
| WP3-D | 2 (`MemoryArrow` enabled = !scrolledAway && !editing) |
| WP4-A | 10 (fade-in null only for the motion-owned `showing` set), 17 (anchor index from the emitted key list), 33, 34 |
| Stage 5 | 15 (upload keystore lives at `~/.yoin/yoin-upload-keystore.properties`; check `hasReleaseKeystore` first) |
| all | 35 (`SeamFlow.held` is not a mitigation for `requestScrollToItem`), 36 (acceptance wording) |

**Strings (R10):** "Edit Home", "Drag to reorder", "Hidden", "Hidden sections appear here", "Reset Home", "Hide %s", "Show %s", "Undo", "Add", "Done", "Show hidden sections", "Move up", "Move down", "Hide", "Section %d of %d", "New", all-hidden card title "Your Home is empty" + body "Press and hold anywhere, or tap Edit Home, to bring sections back", placeholders: JBI "Nothing to jump back into yet", RA "Nothing added this week", Rediscover "Rate an album 8 or higher — when it's been a while, it comes back here".

**Commits:** none during implementation (the owner has not authorized commits for this work). Leave everything uncommitted; the baseline is the clean HEAD `93b689bb`, so `git diff` is exactly this work.
