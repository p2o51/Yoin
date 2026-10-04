# Home edit mode: prototype → Compose port sheet

**Source:** `scratchpad/impl/proto.js` (2362 lines, read in full). Citations look like `p:1395`. The mock was drawn at 1 CSS px = 1 dp, so every px number in the prototype becomes the same number of **dp**.

**Precedence:** the final decisions in spec §8.0 come first. The behaviour of `proto.js` comes next and wins over the older spec text in §2–§7. The places where they disagree are listed in §0.2.

**Verified facts:**
- Material 3 `1.5.0-alpha16` motion tokens, read from the jar. Expressive spatial is 0.8/380, fast 0.6/800, slow 0.8/200. Standard spatial is 0.9/700, 0.9/1400 and 0.9/300. Effects are the same in both roles: default 1/1600, fast 1/3800, slow 1/800.
- `Theme.kt:90` uses `MotionScheme.expressive()`.
- Home runs under `ProvideYoinMotionRole(Expressive)` (`HomeScreen.kt:158`). The shell and the bar run under Standard.
- In animation-core 1.11.0-beta02, `SpringSpec.vectorize` uses **the spring's own** `visibilityThreshold` (null means 0.01). The `Animatable`'s `visibilityThreshold` does not affect a spring you pass in. This drives §1.3.

---

## 0. Scope filter and where the prototype and spec text disagree

### 0.1 Port vs skip (per §8.0)

| Prototype feature | Lines | P0 |
|---|---|---|
| Q1 (b) IdleSettle + card-level wiggle | 1105–1118, 1878–1921, 1980–1997 | **Port** |
| Q1 (a) Kick-only form and (c) Continuous form | `opts.q1` branches | Keep as debug toggles in `HomeEditTokens` only (spec §2.2.4) |
| Q2 (a) long-press lifts the section, plate grows from the press point | 1736–1761, 1136–1141, 1956–1973 | **Port** |
| Q2 (b) card menu, `armed` state, `Q2B_EXTRA_HOLD` | 37, 1319–1349, 1742–1747, 1763–1770, 1795, 1830 | Skip |
| Q3 (a) strips | 1377–1529, 2058–2093 | **Port exactly** |
| Q3 (c) full-size carry, autoscroll | 1531–1604, 46, 1884 | Skip |
| §4 JBI hint tile (Q6b says no) | 439–441, 674–676, 984–993, 2155–2158 | Skip |
| Footer "Edit Home" plus a header hint for the first 2 sessions (Q6b) | 734, 999–1000, 2033, 2166–2171 | **Port** |
| Q11 play glyph | 438, 671 | Skip |
| Predictive-back scrub, edge strip, back indicator (Q8 moves this to P1) | 1053–1058, 1855–1874, 477–479, 2103–2109 | Skip. P0 back is `exitEdit('back')`, which is the same as Done but with no haptic (p:1017) |
| Keyboard ↑↓ move | 1269–1279, 2193–2198 | Becomes TalkBack "Move up / Move down" (P0-8) with the same motion |
| Debug panel: amplitude slider, 0.25× speed, band toggle, toast, haptic log | 773, 785–787, 922–970 | Skip. Treat `opts.amp = 1`, `opts.slow = false`, `opts.band = true` as constants |

### 0.2 Where the prototype differs from the spec text (follow the prototype)

1. **Feed ⇄ strip hand-off.** Spec §2.3 ② says "底板在签条层接手的同一帧隐藏", which hides the feed plate in one frame. The prototype replaced that with a cross-fade, after a reviewer reported the hard cut (comment at p:1952–1955). The feed section alpha is `1 − smoothstep(0, .45, fold)` (p:2019). The strip plate alpha is `a · smoothstep(0, .45, fold)` (p:2062, 2075). Both feed plates and strip plates stay drawn.
2. **Re-grab (⑪).** Spec: re-grab works while settling **or** unfolding, and "fold 弹回 1". Prototype: re-grab works **only while settling** (`phase === 'settle'`, p:1508). During the unfold, every press is ignored (`isBusy`, p:1694, 1705). Fold never springs back to 1.
3. **Kick threshold.** Spec says 0.01°. The prototype stores the kick **normalised**: the first peak equals `a`, and the draw multiplies by Θ or A_c. So the threshold is 0.01 of that normalised value (p:286, 833, 1062–1066).
4. **Velocity.** The spec feeds an androidx `VelocityTracker`. The prototype uses a 2-point slope over 100 ms, returns 0 if the last sample is older than 60 ms, and clamps to ±8000 (p:112–127). Port it as a pure class (§4.7).
5. **E fall time.** The spec says "约 300ms". The real spring is defaultSpatial 0.8/380, which reaches 0.001 after about 467 ms.
6. **Strip stack self-scroll (⑦).** Not in the prototype. With 4 sections the stack fits (minimum 4·56 − 8 = 216 dp), so defer it.
7. **Bar.** The prototype bar is a mock (p:2036–2056). Use the shipped `resolveBarGeometry` idle path with `idleLike = max(idle, edit)` (spec §2.2.7). Port only the cross-fade curves (§2.9). The shipped pill alpha is `1 − idleLike/0.5` (`YoinButtonGroup.kt:305`), not the prototype's `1 − smoothstep(0, .6, P)`.
8. **Vertical plate outset.** The prototype has a fixed 6 (p:40). Use the spec's `min(6dp, (itemSpacing − 4dp)/2)`, which is the same 6 dp at 18 dp spacing.
9. **`EDIT_LIFT_HOLD`.** The prototype has a constant 200 (p:36). Use `max(150, T/2)`.
10. **Rubber band into the seam bands.** The spec says the strip layer never enters the bands. In the prototype, the rubber band (up to 56 dp) can push the first or last strip past `safeTop` or `safeBottom`. If the overlay sits **inside** the `seamTide` Box, the tide paints over it at the top, as it does for content. At the bottom it slides under the bar without a halftone. This is open (§9).
11. **Tray row on Show.** The prototype removes it instantly with `display:none` (p:1234), with no fade and no row FLIP.

---

## 1. Spring engine and tokens

### 1.1 How the prototype's springs behave

| Item | Prototype | Compose equivalent |
|---|---|---|
| Model | Exact closed-form damped spring, **mass 1**, `ω0 = √k`. Handles under-, critically and over-damped (p:179–199) | `spring(dampingRatio = z, stiffness = k)`. Compose is also mass 1. Same physics |
| Retarget | `animateTo(target, token)` **keeps the current velocity** unless one is passed (p:209) | `Animatable.animateTo(target, spec)`, whose default `initialVelocity = velocity`. **Do not** use `animate(initialVelocity = 0f)` (the `RevealState.launchAnimateTo` pattern) for retargetable values |
| Snap | `snapTo(v)` sets value and target, zeroes velocity, stops (p:215) | `Animatable.snapTo` |
| Stop | Keeps value and velocity, drops `onEnd` (p:216–219) | `Animatable.stop()` (zeroes velocity, which doesn't matter here). A dropped `onEnd` maps to cancelling the coroutine, so code after `animateTo` never runs |
| End | `abs(x − target) < thr && abs(v) < 20·thr`, then snap to target and fire `onEnd` (p:222–227) | The spring's own `visibilityThreshold` ends it, then it snaps. **Set the threshold on the spring** (§1.3) |
| Frame dt | `min(Δt, 1/24 s)`. The first frame after a pause is 1/60 (p:237) | Compose uses frame time for springs. The wiggle clock must copy the clamp (§3.6) |
| Order per frame | tickers (6 s idle check, entry-kick check) → spring steps → render (p:242–245) | Equivalent: `snapshotFlow` or the clock loop, then Animatables, then draw |

### 1.2 Token table (`TOKENS` p:57–65; reduced-motion map p:66, 174–177)

| Prototype token | z / k | Spatial? (reduced → fastEffects) | Compose source | Notes |
|---|---|---|---|---|
| `defaultSpatial` | 0.8 / 380 | yes | `YoinMotion.defaultSpatialSpec()` = `spatialSpring()` (Motion.kt:129, 231) **under the Expressive role** | Overshoots 1.52%. 0→1 settles to 1e-3 in about 467 ms |
| `fastSpatial` | 0.6 / 800 | yes | `fastSpatialSpec()` = `stiffSpatialSpring()` (Motion.kt:138, 247) | Overshoots **9.48%**. Settles in about 383 ms |
| `slowSpatial` | 0.8 / 200 | yes | `slowSpatialSpec()` = `slowSpatialSpring()` (Motion.kt:147, 244) | Reaches 0.875 at 200 ms from 0 |
| `defaultEffects` | 1.0 / 1600 | no | `defaultEffectsSpec()` = `effectsSpring()` (Motion.kt:156, 235) | 0→1 settles in about 233 ms |
| `fastEffects` | 1.0 / 3800 | no (it is the reduced target) | `fastEffectsSpec()` (Motion.kt:165) | Settles in about 158 ms |
| `stageSettle` | 0.85 / 700 | yes | `YoinMotion.stageSettleSpring()` (Motion.kt:258; constants :63–64). Not composable and the same in every role | Overshoots 0.63%. P crosses 0.5 at 59 ms, 0.85 at 108 ms, 0.99 at 169 ms; fully settled at about 375 ms |
| `kick` | 0.3 / 450 | yes (disabled under reduced motion anyway) | **New** `YoinMotion.homeEditKickSpring()` = `spring(0.3f, 450f, visibilityThreshold = 0.01f)` in Motion.kt | Damped frequency 3.22 Hz. First peak at 62.6 ms. Peak per unit of v is 0.031657, so **gain is 31.59** (the prototype uses 31.6). Successive peaks shrink by 0.372 each. At a = 1 it is quiet by about 792 ms |
| *(slowEffects 1.0 / 800)* | — | — | `slowEffectsSpec()` exists (Motion.kt:174) | **Not defined or used in the prototype.** Do not use it in the port |

**Role trap.** The spatial specs differ by role, so the controller and carry engine, which are hoisted in `YoinNavHost` under Standard, must not call the `@Composable` getters there. Resolve every spec once, either in `HomeEditorialContent` composition (Expressive) or through the non-composable overloads `YoinMotion.defaultSpatialSpec(YoinMotionRole.Expressive, MaterialTheme.motionScheme)` (Motion.kt:129–181). Pass the result to the controller as a remembered `HomeEditSpecs`.

**Reduced motion** (p:174–177, 1074, 1087, 1138, 1354, 1916, 260). This applies under `LocalMotionProfile == AdaptiveReduced` or `MotionDurationScale == 0`, and under battery saver per the spec:
- every spatial spec, `stageSettle` included, becomes `fastEffects` (1/3800);
- no charge and no plate latch;
- `impulse`, the handle pulse and the sway gain are all off;
- the clock is parked and E targets 0.

P, fold, lift and the other values still animate on fastEffects.

### 1.3 Thresholds: you must set them on the spring

The prototype's per-spring thresholds (p:268–289) mostly sit below Compose's default of 0.01. **`fold` is the critical one.** `lerp(B, S, fold)` covers hundreds of px, so the 0.01 default snaps 3–6 px on the last frame. `HomeFeedFrame.kt:73` tries `Animatable(..., visibilityThreshold = 0.0005f)`, but that does nothing for a passed-in spring (see the verified facts above). Same latent bug, out of scope here.

```kotlin
// HomeEditTokens.kt
internal fun FiniteAnimationSpec<Float>.withThreshold(t: Float): FiniteAnimationSpec<Float> =
    (this as? SpringSpec<Float>)?.let { spring(it.dampingRatio, it.stiffness, t) } ?: this
```

The M3 schemes return `SpringSpec` instances (verified), so the cast holds.

| Value | Threshold (prototype) |
|---|---|
| P | 0.0005 |
| charge, lift, liftTint | 0.002 |
| fold | 0.001 |
| holeY (px) | 0.3 |
| settleY (px) | 0.4 |
| E (`wigEnv`) | 0.001 |
| kick, normalised (block and card) | 0.01 |
| strip neighbour offset (px) | 0.3 |
| hideS | 0.001 |
| hideA, trayA, footA, rowA, slot alphas, pulse | 0.002 |
| FLIP `off`, trayOff, footOff (px) | 0.3. Replaced by `animateItem` |

```kotlin
@Immutable internal class HomeEditSpecs(
    val stage: FiniteAnimationSpec<Float>,        // stageSettle .withThreshold(.0005f)
    val fold: FiniteAnimationSpec<Float>,         // defaultSpatial .withThreshold(.001f)
    val settlePx: FiniteAnimationSpec<Float>,     // defaultSpatial .withThreshold(.4f)
    val neighbourPx: FiniteAnimationSpec<Float>,  // defaultSpatial .withThreshold(.3f)
    val holePx: FiniteAnimationSpec<Float>,       // fastSpatial    .withThreshold(.3f)
    val chargeUp: ..., val chargeDown: ...,       // slowSpatial / fastSpatial .002
    val liftUp: ..., val liftDown: ...,           // fastSpatial / defaultSpatial .002
    val liftTint: ...,                            // fastEffects .002 (both ways)
    val envUp: ..., val envDown: ...,             // fastSpatial / defaultSpatial .001
    val kick: FiniteAnimationSpec<Float>,         // YoinMotion.homeEditKickSpring()
    val pulse: ...,                               // fastSpatial .002
    val hideScaleOut: ..., val hideAlphaOut: ..., // fastSpatial .001 / fastEffects .002
    val showScaleIn: ..., val showAlphaIn: ...,   // defaultSpatial .001 / defaultEffects .002
    val trayIn: ..., val trayOut: ..., val slot: ...,   // defaultEffects / fastEffects / fastEffects
)
@Composable internal fun rememberHomeEditSpecs(reduced: Boolean): HomeEditSpecs
// reduced → every spatial entry = fastEffectsSpec().withThreshold(same thr)
```

---

## 2. Long press, entry, and the gestures in edit mode

### 2.1 Detector state machine (p:1638, 1696–1853)

| State | Entered on | Exits |
|---|---|---|
| `pressing` (normal mode) | down anywhere not excluded. `blockId` is the block under the finger, or null for blank (p:1712–1720) | moved more than slop → abandon, scroll proceeds (p:1794). Up before T → real tap, card `onClick` (p:1829). At T/2 → `startCharge` if `blockId` is set (p:1734). At T → `onLongPress` |
| `lifted` | T on a block (normal mode), or 200 ms hold (edit mode) | moved more than slop **from the down point** → `startCarry` (p:1804). Up → `dropLift(0.5)`, so ①′ (p:1832). Cancel → `dropLift(0)` (p:1850) |
| `holding` (edit, block body or plate outset) | down on a block. In edit mode the plate outset 8/6 counts as the block, via `plateHit` (p:1647–1655, 1713) | moved more than slop before the hold ends → abandon. Up → `blockTap` (p:1831). At `max(150, T/2)` → `DRAG_START` haptic, `liftBlock`, state becomes `lifted` (p:1772–1779) |
| `handle` (edit) | down on the handle (p:1721) | moved more than slop → `DRAG_START`, `liftBlock`, `startCarry` in the same event (p:1797–1802). Up → `blockTap` |
| `blank` (edit) | down off every plate. Records `flingStop = now − lastScrollT < 120 ms` (p:1727–1728) | Up → if not `flingStop`, `exitEdit('blank')`, no haptic (p:1834). After T → `blankHeld`, whose up does nothing (p:1730). Moved more than slop → abandon |
| `carry` | `startCarry` | §4 |
| `done` | swallow the rest of the finger | up → idle |

- **Exclusions** (p:1709): any button other than a card or the handle. That covers the header icons, the hide badge, tray rows, Reset, the footer entry, and the bar. In Compose, `homeEditExclusion` covers the header entries, the memory bubble or pill, the badges, the tray, and the footer (spec §2.1.2).
- **Only one pointer is tracked:** non-primary pointers are ignored, and a second down does nothing while state is not idle (p:1697–1699). In Compose, still **consume** it (spec ⑫).
- **Scroll under a resting finger** abandons `pressing` or `holding` (p:2224). Compose gets this for free, because the list scrolls only after slop and the detector abandons at slop.
- **Constants:** `T = viewConfiguration.longPressTimeoutMillis` (400, p:34). `slop = viewConfiguration.touchSlop` (8 dp, p:35). Body hold before lift is `max(150, T/2)`.

### 2.2 Charge from T/2 to T (block only, not blank)

- **Writer:** `charge.animateTo(1, slowSpatial)` (p:1356). On release or abandon, `animateTo(0, fastSpatial)` (p:1358). Store `chargeId` and the origin, which is the press point **in block-local content coordinates** (p:1642–1645, 1355).
- **Scale:** `s *= 1 − 0.012·charge`, with the transform origin at the press point (p:2000–2003). This goes in `graphicsLayer`.
- **R_press** (p:1121–1124), block-local, before outset:
  - `x0 = clamp(px − 48, 0, w)`, `x1 = clamp(px + 48, 0, w)`
  - `y0 = clamp(py − 48, 0, h)`, `y1 = clamp(py + 48, 0, h)`
  - outset by `o = 4dp · charge` (subtract from the low edges, add to the high edges).
  - The clamp is to the **content** rect, not the plate rect.
- **Plate pre-show** (p:1965–1968):
  - `chA = 0.4 · smoothstep(0, 1, clamp(charge, 0, 1))`;
  - if `chA > pa`, the plate alpha is `chA`, clipped to `R_press⁺`, radius 20 dp, circular (`PanelAnimated`).
- **Numbers:** charge is 0.875 at T (200 ms of slowSpatial), so the plate alpha is 0.383 and the scale 0.9895. A 250 ms tap reaches charge 0.17 and alpha 0.031.

### 2.3 The threshold frame at T (p:1736–1761). Order matters.

1. `performLongPress()`. This is the only haptic on entry.
2. `enterEdit(blockId)` (p:974–1010), which in turn:
   - sets the mode to Edit;
   - increments `editSessions`, and sets `showHint = sessions ≤ 2`;
   - clears `kicked`, `undo` and `plateFrom`;
   - removes the footer **instantly** and fades the tray in with `trayA` 0→1 on `defaultEffects`;
   - sets `i0 = max(0, indexOf(origin))`;
   - starts `P.animateTo(1, stageSettle)`;
   - sets `lastTouch = now` and calls `setEnvelope()`, so E goes to 1 on fastSpatial;
   - updates the left slot.
3. `latchPlate(blockId, local)` reads `charge` **before** it is released (p:1136–1141, 1751): `plateFrom = { rect = R_press with outset 4·cv, latch = cv }`, where `cv = clamp(charge, 0, 1)`. In Compose, read `charge.value` synchronously before launching the release.
4. `releaseCharge()` (p:1752): charge goes to 0 on fastSpatial. The charge scale fades out while the lift scale comes in. They multiply.
5. `liftBlock(blockId, local)` (p:1360–1369):
   - if another block is still lifted, snap lift and liftTint to 0 first;
   - `lift.animateTo(1, fastSpatial)` and `liftTint.animateTo(1, fastEffects)`;
   - **snap this block's kick and all its card kicks to 0.**
6. Blank long-press: `releaseCharge`, then `enterEdit(nearestBlock(y))`, where `nearestBlock` is the distance to the block rect in content coordinates and 0 when inside (p:904–912). No lift, state `done`.
7. The footer "Edit Home" button calls `enterEdit(lastDisplayed)` with no lift and no charge (p:2168).

### 2.4 Lift

- Scale: `s *= 1 + 0.02·lift`, with the origin at the press point (p:2004–2007).
- `zIndex` of the lifted block is raised (p:2016). In Compose, use `Modifier.zIndex(1f)` on the item.
- Shadow alpha is `clamp(lift, 0, 1)` (p:1972). The prototype's CSS shadow is `0 8px 22px 24% + 0 2px 6px 14%` (p:362). Use **6 dp** `shadowElevation · lift` per the spec.
- Plate tint: the plate colour becomes `lerp(surfaceContainerHigh, surfaceContainerHighest, liftTint)` (p:361, 1971).
- Drop (p:1370–1375): `lift → 0` on defaultSpatial, `liftTint → 0` on fastEffects, then `impulse(id, kickA)`. The kick is gated by `(1 − lift)`, so it shows up as lift falls.

### 2.5 Ripple, plate growth, entry kick

- **Ripple** (p:1144–1147): `p_i = clamp((P − 0.06·min(|i − i0|, 4)) / (1 − dMax), 0, 1)`, with `dMax = 0.06·min(max(N − 1, 0), 4)`. Use `pa = smoothstep(0, 1, p_i)`.
- **Plate alpha** for a normal block is `pa` (p:1956).
- **Entry-block growth** (p:1957–1964):
  - rect `gr = lerp(plateFrom.rect, fullPlate, pa)`, where `fullPlate = (−8, −6, w + 8, h + 6)` dp (p:1125);
  - alpha `max(0.4·smoothstep(latch), pa)`;
  - clip with a circular radius of 20 dp;
  - the **lift shadow follows `gr`** (p:1963, 1973).
- **Latch lifecycle:**
  - set `latch = 0` permanently once P ≥ 0.6 (p:1944), and on `exitEdit` (p:1021);
  - drop `plateFrom` when `pa ≥ 0.9999` in Edit, and the continuous `Panel` takes over (p:1958);
  - also drop it when not editing and P ≤ 0.001, or when the block is no longer displayed (p:1945).
- **Entry kick** (p:1149–1160): each frame in Edit, for each block not yet in `kicked`, once `p_i ≥ 0.85`, mark it and call `impulse(id, 1.0)`. The lifted block is marked but not kicked. A block shown later in the session is pre-marked (p:1217). In Compose, use one `snapshotFlow { P.value }` in the controller.

### 2.6 Badges (p:1974–1978)

- `local = clamp((p_i − 0.25)/0.75, 0, 1)` (uses **`p_i`, not `pa`**);
- scale `lerp(0.6, 1, smoothstep(local))`, alpha `smoothstep(local)`;
- handle scale is multiplied by `pulse`.
- Visible only while P > 0.01 (p:1927, CSS 367). Interactive only in Edit (CSS 368).
- The handle is hidden when there is a single section (p:1197).

### 2.7 Edit-mode tap on a block (p:1094–1098)

- **Card-level mode:** kick only the tapped card: `impulseCard(id, card, 0.5)`. Without a card, `impulse(id, 0.5)`.
- **Handle pulse** (p:1086–1091): `pulse.snapTo(1)`, then `pulse.animateTo(1, fastSpatial, initialVelocity = 11.3)`. One underdamped bounce from 1 to 1.199 and back. Peak at 41 ms.
- It never exits edit.

### 2.8 Header (p:2028–2034, 999–1000)

| Element | Rule |
|---|---|
| "Home" | alpha `1 − smoothstep(.2, .6, P)` (P 0.2 at 30 ms, 0.6 at 70 ms) |
| "Edit Home" | alpha `smoothstep(.4, .8, P)` |
| Icons (memory entry and gear) | alpha `1 − smoothstep(0, .5, P)`. Non-interactive at P ≥ 0.5, but **kept composed** (spec §2.2.3) |
| Hint "Drag to reorder" | alpha `smoothstep(.5, 1, P)`. Shown only if the session number is ≤ 2 **and** `editTitleWidth + 16dp + hintWidth ≤ headerWidth`, measured once at entry |

Use clamped P for these.

### 2.9 Bar (Q4 a). Port these curves only (p:2046–2055, 1287–1296)

- `s = smoothstep(.35, .65, P)`. P runs on stageSettle, so the window spans 44–76 ms.
- Home icon and Library label alpha: `1 − s`.
- Done alpha: `s`.
- Done container: `lerp(libraryContainer, primary, P)`, **linear in P** (p:2053, `bgPrimary` alpha = P). Content colour lerps to `onPrimary`.
- Left slot:
  - Undo alpha is `s · slotUndo`;
  - Add alpha is `s · slotAdd`;
  - disabled Undo alpha is `s · slotDis`, drawn at 38% `onSurface` (CSS 495);
  - the edit container `surfaceContainerHighest` has alpha `s`.
- `slotUndo`, `slotAdd`, `slotDis` are three Animatables driven by `resolveEditLeftSlot(undoDepth, trayCount)`: undo when depth > 0, else add when the tray has rows, else disabled-undo. They animate on **fastEffects**.

---

## 3. Wiggle: IdleSettle, card level

### 3.1 Amplitudes

- **Block mode** (placeholder blocks with no cards; p:868, 888, 1985): `Θ = clamp(deg(atan(2 / (0.5·hypot(w + 16, h + 12)))), 0.15°, 0.8°)`. Uses the **plate** size.
- **Card level** (p:870, 890): `A_c = clamp(1.1° × 100dp / w_card, 0.35°, 1.1°)`. `w_card` is the card's laid-out width in dp, captured from `onPlaced` or the size.
- **Parity** (p:832–833): `alt = +1` for even, `−1` for odd, by the card's index in the section's composition order:
  - Activities: hero, then cards in placement order;
  - JBI: memory card first, then the cells;
  - RA: the 4 track tiles, then the albums;
  - Rediscover: in shelf order.

### 3.2 Phase and frequency per section

- Hash (p:75–79): 32-bit FNV-1a over UTF-16 code units. `h = 0x811C9DC5`; for each char: `h ^= c.code; h *= 16777619`. Read the result as **unsigned**. Do **not** use `String.hashCode`.
- `sign = if (hv and 1 == 1) −1 else +1` (p:1060).
- `h = (((hv ushr 4) % 201) / 100) − 1`, which lies in [−1, 1].
- `f = 2.4 · (1 + 0.07h)` Hz.
- `φ = (hv % 628) / 100` rad (p:1899–1902).

| id | hv | sign | f (Hz) | φ (rad) |
|---|---|---|---|---|
| `activities` | 1775563416 | +1 | 2.2488 | 1.76 |
| `rediscover` (keep this id in P0-9) | 1086541855 | −1 | 2.2488 | 1.19 |
| `recently_added` | 4225051900 | +1 | 2.5411 | 4.08 |
| `jump_back_in` | 24610801 | −1 | 2.4386 | 1.09 |

### 3.3 Angle (p:1907–1921, 1986–1996). Read only in draw.

```
sw   = if (E ≤ 0.0005 || pa ≤ 0) 0 else sign · E · pa · sin(2π f t + φ)      // E can dip <0 on the fall: treat as 0
gain = reduced ? 0 : 1 · (lifted ? 1 − clamp(lift,0,1) : 1) · (stripCarried == id ? 0 : 1)
block mode: angle = (0.6·sw + kickBlock) · Θ · gain · band(plateTop−6dp, plateBottom+6dp)
card level: angle = ((sw + kickBlock)·alt + kickCard) · A_c · gain · band(cardTop, cardBottom)
draw only if |angle| ≥ 0.0005°
```

- The block kick is **also** applied to every card (`× alt`). The card kick comes only from taps.
- **Pivots:** the card centre (CSS `rotate` origin is 50% 50%) or the block-content centre.
- In card-level mode the plate, title and badges stay still (p:1981–1982).
- Block-mode transform order in the prototype (p:2012) is: scale about the press point, then rotate about the centre, then translate. Compose uses `graphicsLayer` for scale outside and `drawWithContent { rotate }` inside. The order is reversed, but the error is negligible below 1° at 1.02×.

### 3.4 Edge band (p:1892–1896)

`dist = min(top − safeTop, safeBottom − bottom)`, then `band = if (dist ≤ 0) 0 else clamp(dist / 48dp, 0, 1)`. Card-level mode uses the card rect, block mode the plate rect.

- Prototype values: `safeTop = statusBand + 8`, `safeBottom = barTop − 8`.
- Compose values:
  - `safeTop = statusBarPx + SeamDissolveTokens.TideRest (2dp) + 8dp`;
  - `safeBottom = barFieldTopInBox − 8dp`, where `LocalSeamBarField.current?.bounds?.top` is in root px (SeamBarField.kt:47), converted to Box coordinates. When no bar field is attached, fall back to `boxHeight − bottomContentPadding`.
  - These are the same numbers the strip safe area uses (§4.2).
- Card position = block top from `listState.layoutInfo`, read in draw, plus the card's `relTop` recorded `onPlaced`. **Do not** use `onGloballyPositioned` (spec §2.2.4).

### 3.5 Envelope E (controller is the only writer; p:1105–1118, 1878–1883)

`setEnvelope()`:
- if not editing, or reduced, or form is Kick: `E → 0` on **defaultSpatial**;
- otherwise target `t = carryActive ? 0.6 : 1`, and if it differs, `E → t` on **fastSpatial**. The rise overshoots to about 1.095. That is part of the feel; keep it.

**6 s idle rule** (checked each clock frame):

```
if (form == IdleSettle && surface == Edit && carry == null && !pointerDown && E.targetValue > 0
    && now − lastTouch > 6000 ms) E.animateTo(0, defaultSpatial)
```

`lastTouch` refreshes on:
- entry (p:1003);
- **every pointer down in the page** while editing, via `touchWiggle`, which also calls `setEnvelope` and brings E back to 1 (p:1704). This happens even for presses that are ignored or excluded. The prototype's bar is inside the screen, so **Undo, Add and Done clicks should also call `controller.touch()`**;
- **finger up or cancel** (`fingerLifted`, p:1818, 1825, 1846). That is the refinement: the 6 s window restarts on lift, and never counts while a finger is down (`g.state ≠ idle`) or a carry exists (`state.carry`, including settle and unfold);
- the end of a carry (`stripFinish → touchWiggle`, p:1498), which also brings E from 0.6 back to 1.

### 3.6 Clock (p:171, 237–240, 258–264)

There is one global `t`. It advances only while the loop runs, by `min(Δt, 1/24)`, and by 1/60 on the first frame after resuming.

Run condition: `!reduced && homeVisible && (surface == Edit || P > 0.001) && (E.value > 0.0005 || E.isRunning)`. Home is visible when it is RESUMED, not covered by Now Playing, and is the selected section.

```kotlin
// HomeEditWiggle.kt
LaunchedEffect(controller) {
  snapshotFlow { controller.clockShouldRun }            // derivedStateOf inside controller
    .collectLatest { run -> if (run) { var last = -1L; while (true) withFrameNanos { n ->
        val dt = if (last < 0) 1f / 60 else minOf((n - last) / 1e9f, 1f / 24); last = n
        controller.swayT.floatValue += dt; controller.idleTick(uptimeMillis) } } }
}
```

### 3.7 Kicks (`impulse` and `impulseCard` are the only writers; p:1067–1084)

```kotlin
fun kick(anim: Animatable<Float, *>, a: Float, sign: Int) {          // normalised: first peak = a
  val base = a * 31.6f
  val v = if (anim.isRunning && abs(anim.velocity) > 1e-3f) anim.velocity + sign(anim.velocity) * base
          else base * sign
  scope.launch { anim.animateTo(0f, specs.kick, initialVelocity = v) }   // from CURRENT value, no snap
}
```

| Trigger | a | sign |
|---|---|---|
| Entry (`p_i ≥ 0.85`) | 1.0 | block `sign` |
| Edit tap | 0.5 | card: `sign·alt` on that card. Placeholder: block |
| Drop in place (①′) | 0.5 | block |
| Strip drop, the carried block | 0.7 | block |
| Neighbours whose index changed vs the original order | 0.35 | block |
| Show, if the block is in the viewport | 0.35 | block |

No kick on Reset, Undo, scroll or hide. Skip when reduced or not yet measured.

---

## 4. Strips: fold, reorder, unfold. Port these numbers exactly.

### 4.1 Trigger (p:1620–1630, 1795–1806)

- `startCarry` runs once the lifted block's finger moves more than slop from the **down** point.
  - In normal mode this happens after the T entry; there is no extra haptic.
  - On the edit handle it happens on the first move past slop, with `DRAG_START`.
  - On the edit body it happens after the 200 ms hold, with `DRAG_START` at the hold.
- Order of operations:
  1. capture the pointer;
  2. reset the velocity tracker and add the current sample `(t, y)`;
  3. `startStrip()`;
  4. `setEnvelope()`, which sends E to 0.6.
- If edit mode was left while the finger was down, the gesture ends (p:1621).

### 4.2 Geometry at fold start (p:1391–1417). Compute once from fresh rects.

```
ids   = displayed (enabled, rendered) order; N = ids.size; k = ids.indexOf(liftId)
safe  = [safeTop, safeBottom]                       // §3.4 values
hs    = clamp(((safeBottom − safeTop) − (N−1)·8dp) / N, 48dp, 64dp)
pitch = hs + 8dp;  stackH = N·pitch − 8dp
w     = min(contentWidth, 560dp);  x = contentLeft   // feedFrame.start; content line, not plate edge
top   = clamp(fingerY − (k + 0.5)·pitch + 4dp, safeTop, max(safeTop, safeBottom − stackH))
slotY(j) = top + j·pitch                            // ⇒ strip k's centre == fingerY exactly
cover edge cs = clamp(hs − 24dp, 24dp, 36dp)
```

**Example.** Safe height 628, N = 4: `hs` = (628 − 24)/4 = 151, clamped to 64, so pitch = 72 and stackH = 280. Phone strips are 64 dp tall; the tablet's capped width is 560.

### 4.3 Start rects B_i (p:1379–1389). Recomputed after commit; see §4.9.

```
plate_i = (x = contentLeft − 8dp, y = itemTopInBox − 6dp, w = contentWidth + 16dp, h = itemHeight + 12dp)
topC = max(plate.y, safeTop);  botC = min(plate.y + plate.h, safeBottom)
visible  (botC − topC > 1px): B = (plate.x, topC, plate.w, botC − topC, a = 1)    // can be a thin sliver
offscreen:  y0 = if (plate.y + plate.h ≤ safeTop) safeTop else safeBottom − hs
            B = (x + 0.02w, y0 + 0.02hs, 0.96w, 0.96hs, a = 0)                    // invisible, at its side's edge
```

- `itemTopInBox = info.offset − layoutInfo.viewportStartOffset + listTopInBox` (spec §2.1.2).
- The prototype adds the in-flight FLIP `off`. Compose's `layoutInfo.offset` excludes the `animateItem` offset. Accept that.

### 4.4 Per-frame strip frame (p:2058–2093). Pure function; read in draw and placement.

```
fv = fold (UNCLAMPED, overshoots ±1.5%);  fa = clamp(fv,0,1)
xf = smoothstep(0, .45, fv)        // strip-plate fade-in == exact complement of the feed fade-out
la = smoothstep(.55, 1, fv)        // label fade
tw = 0.30 · smoothstep(.3, 1, fv)  // cover tint weight
sy_j = slotY(j) + (carried ? (phase == Drag ? display : settleY) : stripOffset[id])
S  = (x, sy_j, w, hs);  carried: e = 0.02·lift → S expanded about its centre: x −= w·e/2, y −= hs·e/2, w·(1+e), h·(1+e)
r  = lerp(B, S, fv)                              // x, y, w, h each
a  = lerp(B.a, 1, fa)
plate:  roundRect r, radius = min(20dp, r.h/2), alpha = a·xf
        colour = lerp(plateC, firstCoverBase, tw) if the section has a cover and tw > 0.05 else plateC
        plateC = carried ? lerp(surfaceContainerHigh, surfaceContainerHighest, liftTint) : surfaceContainerHigh
label:  offset (r.x, r.y + r.h/2 − hs/2); fixed size w × hs (NOT interpolated); alpha = la·a
shadow (carried only): rect r, radius as plate, alpha = clamp(lift)·smoothstep(.6, 1, fv)
hole:   (x, holeY, w, hs), radius 20dp, fill secondaryContainer α .35, alpha = smoothstep(.3, 1, fv), no stroke
z-order: hole < other strips (plate+label) < carried shadow < carried strip (p:1410–1412, 737)
```

- **Strip label** (CSS p:465–473): horizontal padding 16 dp, gap 12 dp, then up to 3 covers, then the title, then `DragHandle` at 24 dp in `onSurfaceVariant`.
  - Covers are `cs` square in the entity backdrop shape (album, song or artist, playlist; Yoin's entity→`MaterialShapes` map). Each cover after the first overlaps the previous by `−0.3·cs`. The first cover is drawn on top. No border.
  - Title: `titleMedium` SemiBold, one line, ellipsis.
  - Placeholder strips have no covers and keep `plateC`.
- **Covers** come from the first 3 items each section already shows (p:681–686):
  - Activities: hero, small, wide;
  - JBI: memory card plus 2 cells;
  - RA: the first 3 albums;
  - Rediscover: the first 3.

  They should all be cache hits. `firstCoverBase` uses the same palette `baseColor` as the bento. Compose `lerp(Color)` works in Oklab, while the prototype's `color-mix` is sRGB. The difference is small.
- **The strips themselves never wiggle.**

### 4.5 The feed side during carry (p:2019–2025, 1919)

- Every section item gets `graphicsLayer.alpha *= 1 − smoothstep(0, .45, fold)`. Plates are included and **stay drawn** (the cross-fade fix).
- Tray items fade the same way. **The header does not fade.** In Compose, apply the alpha per section and tray item, not on the `LazyColumn`, because the header is item 0.
- The carried block's feed copy keeps lift (scale, shadow, tint) and gets sway gain 0.
- While `fold > 0` or a carry is active, `placementSpec` is null. The prototype's `commitDom({noFlip})` (p:1469) is the same idea.

### 4.6 Drag (p:1431–1445, 1419–1429)

```
onMove:  dragOffset += y − lastY; lastY = y; velocity.add(uptime, y)
while (dragOffset >  pitch/2 && k < N−1) swap(+1)
while (dragOffset < −pitch/2 && k > 0)   swap(−1)
over    = (k == 0 && dragOffset < 0) || (k == N−1 && dragOffset > 0)
display = if (over) sign(dragOffset)·rubber(|dragOffset|) else dragOffset
overNow = over && |dragOffset| > 2dp;  if (overNow && !wasOver) performThreshold();  wasOver = overNow
swap(dir): j = k+dir; nb = ids[j]; ids[j] = carried; ids[k] = nb
           stripOffset[nb].snapTo(stripOffset[nb].value + dir·pitch); stripOffset[nb].animateTo(0, defaultSpatial)
           k = j; dragOffset −= dir·pitch; holeY.animateTo(slotY(j), fastSpatial); performSegmentTick()
rubber(x)   = D·(1 − 1/(0.55·x/D + 1)),  D = 56dp      // slope .55 at 0 → resistance starts immediately at end slots
unrubber(y) = (D/0.55)·(1/(1 − min(y/D, .98)) − 1)
```

- x is pinned. The strip follows the finger 1:1 in y only.
- The hole starts at `holeY.snapTo(slotY(k))` (p:1414). Its fastSpatial overshoot is 9.5%, about 6.8 dp on a 72 dp pitch. That is part of the feel.
- **Strip offsets** and **settleY** are px-space Animatables owned by the engine.

### 4.7 Velocity tracker (p:112–127). Pure class `HomeEditVelocity`.

```
add(t,y): push; while (size > 2 && t − first.t > 100ms) drop first
velocity(now): if (size < 2) 0; last = samples.last
  if (now − last.t > 60ms) return 0                       // finger rested before lifting → no fling replay
  i = first index with last.t − s[i].t ≤ 100ms (keep ≥2)
  dt = (last.t − s[i].t)/1000; if (dt ≤ 0.004) 0 else clamp((last.y − s[i].y)/dt, ±8000dp/s)
```

Samples are the finger y in Box coordinates. That frame is stable, because the overlay doesn't move with the finger, so no cumulative trick is needed. `now` is the up event's `uptimeMillis`. In px, the clamp is `8000.dp.toPx()`.

### 4.8 Release → settle (p:1447–1458)

```
phase = Settle; v = cancelled ? 0 : velocity(upUptime)
visY = slotY(k) + display                                  // computed BEFORE the fling swap
if (|v| > 1600dp/s) { dir = sign(v); if (k can move dir) swap(dir) }   // at most ONE extra slot (+ SEGMENT_TICK)
settleY.snapTo(visY − slotY(k)); settleY.animateTo(0, defaultSpatial, initialVelocity = v) → then commit()
```

`voteHighFrameRate(true)` from here to the end of the unfold (spec).

### 4.9 Commit and anchor (p:1460–1489)

1. `phase = Unfold`. `changed = ids != orig`.
2. If changed:
   - push undo;
   - `draft = withEnabledOrder(draft, ids)`: enabled slots are refilled in the new order, and disabled entries **keep their absolute index** (p:855–859);
   - persist;
   - update the tray, Reset and the left slot;
   - `performConfirm()`.
3. **Anchor**, in one step and only when the header is out of view (p:1475–1482):
   - prototype: if `scrollTop ≥ headerOut` (header fully under the status band), `want = droppedPlateTop_content − slotY(k)`, clamped to `[min(headerOut, maxScroll), maxScroll]`. Otherwise, **no scroll change at all**;
   - Compose: if `firstVisibleItemIndex == 0`, skip. Otherwise call `listState.requestScrollToItem(idx(dropped), scrollOffset = −(slotY(k) + 6dp − listViewportTopInBox))`, with the offset clamped so `firstVisibleItemIndex` stays ≥ 1. The clamp is `Σ_{1≤j<idx}(h_j + spacing)` from a per-section height cache written in layout (`onSizeChanged`). If a needed height is unknown, skip the anchor; the strips fly the full distance, as with the prototype's clamp.
   - Hold `SeamFlow.hold(true)` while `fold > 0` (spec only; not in the prototype).
4. Wait for the first `layoutInfo` whose visible section keys follow the committed order (`snapshotFlow`). Then recompute every `B_i` with §4.3; blocks that are now off screen get `a = 0`.
5. `launch { lift.animateTo(0, defaultSpatial) }` and `launch { liftTint.animateTo(0, fastEffects) }`, **not awaited**. Then `fold.animateTo(0, defaultSpatial)`, then `finish(changed)`.

### 4.10 Unfold

Use the same frame function as §4.4, with `fv` going from 1 to 0:
- each strip flies from `S` to its new `B`;
- the label fades out over fold 1 → 0.55;
- the strip plate fades, `xf`, as the feed fades back in;
- the hole fades;
- the carried strip shrinks as lift falls.

Near `fv ≈ 0` the strips are effectively invisible (`xf → 0`), so the −1.5% overshoot never shows.

### 4.11 Finish (p:1491–1503)

Hide the overlay; clear the session; `lift.snapTo(0)` and `liftTint.snapTo(0)` (cancelling their launches); clear `liftId`; `controller.touch()` (E back to 1 on fastSpatial, idle timer reset); kick the carried block at 0.7; if changed, kick at 0.35 every other block whose index differs from `orig`; then `runDeferredExit()`.

### 4.12 Re-grab (⑪, p:1506–1529). Only while `phase == Settle`.

- **Hit zone:** x in `[x, x + w]`, y in `[slotY(k) + settleY − 8dp, … + hs + 8dp]`.
- **On a hit:**
  - stop `settleY` (cancel the job so `commit` never runs);
  - `d = settleY.value`;
  - `over` uses the same test as §4.6;
  - `dragOffset = over ? sign(d)·unrubber(|d|) : d`;
  - `display = d`; `lastY = y`;
  - `phase = Drag`;
  - reset the velocity tracker and add the sample;
  - `holeY.animateTo(slotY(k), fastSpatial)`;
  - `performDragStart()`.
- **Any other press while in Settle or Unfold is ignored** (p:1694, 1705), but still calls `touch()` (p:1704).

### 4.13 Leaving or cancelling mid-carry (p:1012–1015, 1606–1618, 1841–1853, 2206–2218)

- **Done or back during the carry:**
  - set `exitAfterCarry = reason`;
  - if `phase == Drag`, mark the finger swallowed and run release with `cancelled = true` (v = 0);
  - then settle, commit and unfold as normal, and run `exitEdit(reason)` at `finish`;
  - if already in Settle or Unfold, just record it.
- **Pointer cancel:** release with `cancelled = true`. **A changed order is still committed.** In Compose, a system cancel shows up as an already-consumed up or as coroutine cancellation (`try/finally`), so route both into `release(cancelled = true)`.
- **Window blur:** cancel (p:2216).

### 4.14 Engine sketch (`HomeCarryStack.kt`)

```kotlin
internal enum class CarryPhase { Drag, Settle, Unfold }
internal class CarrySession(val ids: MutableList<String>, val orig: List<String>, val carried: String,
  val n: Int, var k: Int, val hs: Float, val pitch: Float, val w: Float, val x: Float, val top: Float,
  val safeTop: Float, val safeBottom: Float, var b: Map<String, StartRect>,
  var dragOffset: Float = 0f, var display: Float = 0f, var lastY: Float, var wasOver: Boolean = false,
  var phase: CarryPhase = CarryPhase.Drag)      // session fields: plain vars, mutated only by the engine;
                                               // display/k/phase as snapshot state since draw reads them
@Stable internal class HomeCarryEngine(scope, specs, haptics, controller) {
  val lift = Animatable(0f); val liftTint = Animatable(0f); var liftId by mutableStateOf<String?>(null)
  var liftOrigin: Offset                         // block-local press point (graphicsLayer transformOrigin)
  val fold = Animatable(0f); val holeY = Animatable(0f); val settleY = Animatable(0f)
  val stripOffset = mutableMapOf<String, Animatable<Float, AnimationVector1D>>()
  var session by mutableStateOf<CarrySession?>(null); private var settleJob: Job? = null
  fun liftBlock(id, origin) / dropLift(kickA) / start(fingerY, rects) / move(y, uptime) / release(cancelled, uptime)
  fun tryRegrab(pos, uptime): Boolean / forceDrop()
}
@Composable internal fun HomeCarryStack(engine, coversFor: (String) -> List<StripCover>, modifier)
// Box(matchParentSize) inside the seamTide Box, ABOVE the LazyColumn:
//  - drawBehind: hole, non-carried plates, carried plate (shadow via a sized child with graphicsLayer.shadowElevation)
//  - one label child per id: Modifier.offset { frame.labelOffset }.graphicsLayer { alpha = frame.labelAlpha }
```

---

## 5. Hide, show, reset, undo, tray, exit

### 5.1 Hide (p:1240–1248, 1165–1184)

1. `performToggle(false)`; push undo.
2. The **draft updates immediately**: tray row, Reset button, left slot.
3. The feed keeps rendering the block (Compose: a `hidingIds` set) while `hideS → 0.96` on fastSpatial and `hideA → 0` on fastEffects (about 158 ms).
4. When `hideA` finishes, remove the block from the feed.
   - A layout-generation counter guards this: a newer `applyLayout` discards the stale removal (p:1167, 1178).
   - Neighbours close up with `animateItem(placementSpec = spatialSpring())`, the prototype's FLIP on defaultSpatial (p:1201–1210).
   - Persist.
   - Use `fadeOutSpec = null` on section items, since alpha is already 0.
5. The tray row fades in: `rowA` 0→1 on defaultEffects (p:1233).

### 5.2 Show (p:1249–1257, 1211–1220)

1. `performToggle(true)`; push undo.
2. The block is inserted at its retained slot:
   - mark it kicked;
   - snap kick to 0, `hideA` to 0 and `hideS` to 0.96;
   - animate `hideA → 1` on **defaultEffects** and `hideS → 1` on **defaultSpatial**. Note the asymmetry with hide;
   - if it is in the viewport (`top + h > safeTop && top < barTop`, p:914–918), kick at 0.35 once laid out.
3. The tray row disappears instantly. Use `fadeOutSpec = null`; a placement spring for the other rows is fine.
4. Use `fadeInSpec = null` on the section item, because `hideA` owns the alpha.

### 5.3 Reset, Undo, Move

- **Reset** (p:1258–1263): only when not default. Push undo, `performReject()`, apply the default layout. Blocks that reappear fade and scale in **without a kick** (`kickShown` is false). Reordered blocks use the placement spring.
- **Undo** (p:1264–1268): `performClick()`; apply the popped layout. It may hide, show (no kick) or reorder.
- **Move up or down** (TalkBack and keyboard; p:1269–1279): push undo, `performSegmentTick()`, reorder with the placement spring, announce "Title, section j of N".
- **Undo stack:** at most 20 (p:42, 862). Cleared on enter and on exit.

### 5.4 Tray and footer

- **Entry:** the footer is removed instantly; the tray is inserted with `trayA` 0→1 on defaultEffects (p:995–997).
- **Exit** (p:1025–1031): `trayA → 0` on fastEffects (about 158 ms). **Then** the tray unmounts, the footer mounts, and `footA` goes 0→1 on defaultEffects. This is sequential.
  - Compose: `trayMounted = surface == Edit || trayA.value > 0.002`; `footerMounted = !trayMounted`.
  - Use `animateItem(fadeInSpec = null, fadeOutSpec = null)` on those items and drive alpha with `graphicsLayer`.
- **"Add" in the bar** (p:1311–1317): `performClick()`, then scroll so the tray top sits 80 dp below the viewport top, on defaultSpatial:
  - if the tray is visible, `animateScrollBy(delta, defaultSpatial)`;
  - otherwise `animateScrollToItem(trayIdx, −80dp)`.
  - Any touch cancels it.
- **Tray styling** (CSS 443–453): row height 64, radius 20, `surfaceContainerHigh`, a 40 dp `secondaryContainer` plus button. Head "Hidden". When nothing is hidden, show the empty line "Hidden sections appear here". Then the Reset `TextButton`.

### 5.5 Exit (p:1012–1051)

- **`exitEdit(reason)`:**
  - if a carry is active → §4.13;
  - otherwise disown any live finger (`charge → 0`, state `done`);
  - `performConfirm()` **only for `done`**;
  - P → 0 on stageSettle;
  - `latch = 0`;
  - if lifted, `dropLift(0)`;
  - clear undo;
  - `setEnvelope()`, which sends E to 0 on defaultSpatial;
  - run the tray and footer sequence (§5.4);
  - announce "Home".
- **Ways out:** blank tap (no haptic), Done, P0 back, Escape (= back).
- **`snapExit`** (profile switch, ON_STOP, programmatic section change; p:1038–1051): snap P, `trayA` and E to 0; clear `plateFrom`; swap the tray for the footer instantly; clear undo; snap lift and liftTint to 0.
- **Kicks already ringing** keep ringing after exit, because `kickBlock` is not multiplied by `pa`. The wiggle modifier must keep reading them until they settle.

---

## 6. Ownership table: one writer per value

| Value | Writer (API) | Compose holder | Spec / threshold | Read phase | File |
|---|---|---|---|---|---|
| P | `HomeEditController.enter / commitAndExit / snapExit` | `ExperienceSessionStore.homeEditProgress: Animatable` | stageSettle / .0005 | draw (plate, badges, header, wiggle `pa`); bar measure | `HomeEditController.kt`, `ExperienceSessionStore.kt` |
| charge, chargeId, chargeOrigin | detector `startCharge` / `releaseCharge` | `Animatable` in `HomeEditGestureState` | slowSpatial up, fastSpatial down / .002 | `graphicsLayer` (scale); draw (pre-show) | `HomeEditGestures.kt` |
| `plateFrom {rect, latch}` | controller `latchPlate` (read of charge at T), `clearLatch` | `mutableStateOf<PlateFrom?>` | — | draw | `HomeEditController.kt` |
| `i0`, `kicked`, `lastTouch`, `pointerDown` | controller | plain fields; `i0` snapshot | — | draw (`i0`) | `HomeEditController.kt` |
| lift, liftTint, liftId, liftOrigin | carry engine `liftBlock` / `dropLift` / commit / finish | `Animatable` ×2, `mutableStateOf` | fast / default, fastEffects / .002 | `graphicsLayer` (scale, `shadowElevation`); draw (tint, strips) | `HomeCarryStack.kt` |
| fold | engine `start` / `commit` | `Animatable` | defaultSpatial / **.001** | draw (strips, feed alpha); placement (labels) | `HomeCarryStack.kt` |
| `session` (k, dragOffset, display, phase, B, ids) | engine pointer path | snapshot state for draw-read fields | — | draw and placement | `HomeCarryStack.kt` |
| `stripOffset[id]` | engine `swap` | `Animatable` per id | defaultSpatial / .3px | draw and placement | `HomeCarryStack.kt` |
| holeY | engine `start` (snap), `swap`, `regrab` | `Animatable` | fastSpatial / .3px | draw | `HomeCarryStack.kt` |
| settleY | engine `release` / `regrab` | `Animatable` | defaultSpatial + v / .4px | draw and placement | `HomeCarryStack.kt` |
| E | controller `setEnvelope`, `idleTick` | `Animatable` | fastSpatial up, defaultSpatial down / .001 | draw | `HomeEditController.kt` |
| t | the one clock loop | `MutableFloatState` | — | draw only | `HomeEditWiggle.kt` |
| `kick[section]`, `kick[section, card]` | controller `impulse` / `impulseCard` | `Animatable` map (lazy) | `homeEditKickSpring` / .01 normalised | draw (`drawWithContent` rotate) | `HomeEditController.kt` |
| `pulse[section]` | controller `pulseHandle` | `Animatable(1f)` | fastSpatial, v = 11.3 / .002 | `graphicsLayer` (handle) | `HomeEditController.kt` |
| `hideS`, `hideA`, `hidingIds` | controller layout engine | `Animatable` ×2 per id, snapshot set | out: fastSpatial / fastEffects; in: defaultSpatial / defaultEffects | `graphicsLayer` | `HomeEditController.kt` |
| feed positions | `LazyColumn` | `animateItem(placementSpec = spatialSpring(), or null while fold > 0 or a session exists)` | defaultSpatial | layout | `HomeEditorialContent.kt` |
| `trayA`, `footA`, `rowA[id]` | controller | `Animatable` | defaultEffects in, fastEffects out (tray) / .002 | `graphicsLayer` | `HomeEditTray.kt` (state in controller) |
| `slotUndo`, `slotAdd`, `slotDis` | controller `updateLeftSlot` | `Animatable` ×3 | fastEffects / .002 | bar draw | `HomeEditController.kt` → bar |
| draft, undo stack | controller | `mutableStateListOf`, `ArrayDeque` (max 20) | — | composition | `HomeEditController.kt` |

---

## 7. Files in `ui/home/edit/` and the hooks outside it

| File | Contents |
|---|---|
| `HomeEditTokens.kt` | Every constant: `PressBox` 96, `PressOutset` 4, `ChargeScale` .012, `ChargePlateAlpha` .4, `LiftScale` .02, `LiftShadow` 6dp, `LatchClearP` .6, `RippleStep` .06, `RippleMaxSteps` 4, `EntryKickAt` .85, plate outset 8/6, `PlateRadius` 20, `SwayHz` 2.4, `SwayDetune` .07, `BlockSwayFactor` .6, `DragEnvelope` .6, `IdleMs` 6000, `BandFade` 48, `SafeGap` 8, Θ (2dp corner, .15–.8°), A_c (1.1°×100dp, .35–1.1°), `KickGain` 31.6, kick amplitudes 1 / .5 / .7 / .35, `PulseVelocity` 11.3, strip gap 8, h 48–64, max w 560, cover inset 24, cover 24–36, overlap .3, strip padding 16, gap 12, fade points .45 / .55 / .3 / .6, tint .30, hole α .35, off-screen scale .96, `RubberD` 56, `RubberK` .55, over-haptic 2dp, fling 1600dp/s, max velocity 8000dp/s, window 100 ms, stale 60 ms, min dt 4 ms, re-grab slop 8, undo max 20, hint sessions 2, hide scale .96, scroll-to-tray 80dp, fling-stop 120 ms. Plus `HomeEditSpecs`, `withThreshold`, and the debug `wiggleMode` / `wiggleTarget` |
| `HomeEditGeometry.kt` | Pure functions: `pressRect`, `fullPlateRect`, `rippleProgress`, `badgeLocal`, `thetaFor`, `cardAmp`, `bandAt`, `fnv1a32`, `swayParams`, `kickVelocity`, `stripMetrics`, `stripStartRect`, `stripFrame`, `rubber` / `unrubber`, `anchorOffsetClamp`, `withEnabledOrder`, `resolveEditLeftSlot`, `HomeEditVelocity` |
| `HomeEditController.kt` | P (in the store), E, kicks, pulse, hide anims, `plateFrom`, ripple and entry kicks, idle rule, draft and undo, tray, footer and slot alphas, enter / exit / snap |
| `HomeEditGestures.kt` | `Modifier.homeEditGestures`: the §2.1 state machine in `Initial` pass, charge, exclusions, and the hand-off to the engine |
| `HomeEditBlock.kt` | Section wrapper: plate (Panel; `PanelAnimated` while growing), `graphicsLayer` with charge × lift × hide scale and alpha × feedA, shadow, badges, and block-mode wiggle |
| `HomeEditWiggle.kt` | The clock loop, and `Modifier.homeEditWiggle(controller, sectionId, cardIndex)` as a draw node |
| `HomeCarryStack.kt` | Engine (§4.14) and overlay |
| `HomeEditTray.kt` | Tray rows, Reset, footer entry |

**Hooks outside the package:**
- `Motion.kt`: add `homeEditKickSpring`.
- `Shape.kt`: add `YoinContainerShapes.PanelAnimated = RoundedCornerShape(20.dp)`. It does not exist yet.
- `Haptics.kt`: add `performDragStart`, `performSegmentTick`, `performThreshold`, `performToggle`. Existing methods are at :18–50.
- `ExperienceSessionStore.kt`: add `HomeSurface.Edit` and `homeEditProgress`.
- `SeamDissolve.kt`: add `SeamFlow.hold`.
- `HomeEditorialContent.kt`:
  - remove `detectTapGestures` (:366–373);
  - change section keys to `"section-$id"` (currently :411, :481, :506);
  - put the overlay inside the `seamTide` Box (:339–347);
  - add 6 dp of vertical breathing room in the horizontal shelves so rotating cards aren't clipped (the prototype's `SHELF_V`, p:54, 408). At 1× amplitude the corner excursion is ≤ 1 dp, so 2 dp is enough;
  - `userScrollEnabled = false` on the RA and Rediscover shelves while editing.

---

## 8. Test vectors for the pure functions

| Function | Expected |
|---|---|
| `kickPeak(v = 31.6·a)` | Peak ≈ 0.9983a at t = 62.6 ms. Next opposite peak ≈ −0.372× |
| `pulse(v = 11.3)` on fastSpatial | Peak 1.1993 at 41 ms |
| `charge(200 ms)` on slowSpatial from 0 | 0.8755; plate alpha 0.3829; scale 0.98949 |
| `stageSettle` 0→1 | P crosses 0.85 at 108 ms; settled at 375 ms; peak 1.0063 |
| `fold` 0→1 (defaultSpatial) | 0.45 at 71 ms, 0.55 at 84 ms, 1.0 at 214 ms; peak 1.0152; settled at 467 ms |
| `rubber(56)` | 19.87 dp |
| `rubber(∞)` | 56 |
| `unrubber(rubber(x))` | x (for `y/D ≤ .98`) |
| `stripMetrics(safe 44..672, N 4, k 1, fingerY 300)` | hs 64, pitch 72, stackH 280, top = clamp(300 − 108 + 4, 44, 392) = 196 |
| `fnv1a32` | `"activities"` → 1775563416, `"jump_back_in"` → 24610801 (also the table in §3.2) |
| `thetaFor(360, 412)` (phone Activities plate) | ≈ 0.42° |
| `cardAmp(100)` | 1.1° |
| `cardAmp(320)` | 0.35° |
| `velocity` | last sample older than 60 ms → 0; samples 1600 dp apart over 100 ms → 8000 (clamped) |
| `withEnabledOrder([A, b̶, C, D], [D, A, C])` | `[D, b̶, A, C]` (disabled entry keeps index 1) |

---

## 9. Open points for the implementer

1. **Rubber band into the bands** (§0.2 item 10). At the top, the tide already covers the overlay, provided the overlay is inside the `seamTide` Box. At the bottom, the last strip can reach up to 48 dp past `barTop − 8`. Options are to keep it, or to clip the overlay at `safeBottom + 8dp`. Check on the Pixel Tablet.
2. **The cover tint** needs a per-section `firstCoverBase: Color?` from the same palette source as the bento. Wire it through the section `UiState`, not a new Coil request.
3. **One frame between the settle ending and the unfold starting** (waiting for the new `layoutInfo`, §4.9 step 4). Strips sit at S with `fold = 1` during that frame, so there is no visual cost.
