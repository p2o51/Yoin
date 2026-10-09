---
name: predictive-back
description: >
  Yoin's predictive-back and screen-transition doctrine — how every screen,
  Activity, overlay, and dismiss gesture must handle system back, and which of
  the three implemented patterns it must reuse. Use this BEFORE adding or
  modifying ANY screen, page, Activity, overlay, sheet, or close/dismiss
  behavior — even when the task never mentions "back": new surfaces must be
  classified against this doctrine first. Also use for anything touching
  predictive back, 预测性返回, 返回手势, 转场动画, back gesture, BackHandler,
  PredictiveBackHandler, onBackPressed, activity transitions, enter/exit
  animations, drag-to-dismiss, bar morph, or AOSP/Pixel-style motion. Covers:
  riding the native cross-Activity predictive back for free vs. consuming the
  gesture and replicating AOSP CrossActivityBackAnimation in-window (tokens,
  math, file map), the shell-overlay back patterns, and the invariants that
  keep them flash-free.
---

# Predictive Back & Screen Transitions — Yoin Doctrine

## The taste this encodes

Yoin's differentiator is motion quality, and its back philosophy is **borrowed
physics**: leaving a page should feel indistinguishable from the OS doing it.
The reference model is the Pixel/AOSP predictive back preview — the finger owns
the pixels 1:1 while it's down, springs only take over on release, and the
destination is genuinely previewed (the real surface behind, not a mock).
Concretely:

- **Native-first.** If the system animation can serve a surface, take it — a
  Pixel user gets Pixel's animation, a OnePlus user gets theirs. Custom code is
  only justified when a continuity illusion (the persistent bottom bar) would
  break under the system's whole-window transform.
- **When we do replicate, we replicate faithfully.** The in-window back is a
  port of `frameworks/base` WM Shell `DefaultCrossActivityBackAnimation` math,
  constant for constant (tokens table below). House style is springs, but
  platform mimicry beats house style: the replica uses AOSP's 450ms EMPHASIZED
  tweens *because the platform does*. Springs own everything the platform
  doesn't specify — cancel settles, release continuations.
- **Forward and back are one trajectory.** Push = content slides in from 96dp
  (AOSP's entering offset); predictive back runs the same geometry in reverse.
  No hero/cover flights across window boundaries.
- **Chrome continuity over window boundaries.** The bottom bar reads as ONE
  fixed element across shell ⇄ detail. Everything else (translucent themes,
  content-only transforms, alpha-only window anims) exists to protect that.

> **Doc precedence:** `AGENTS.md`'s back sections and the `AndroidManifest.xml`
> detail-activity comment predate the current architecture (they still say
> "pushed NavHost route", "no consuming back callback", `YoinBackSurface`).
> The taxonomy and intent there still hold; where mechanics disagree, THIS
> skill and the code win. Detail pages left the NavHost — they are Activities.

## Step 0 — classify the surface

Never start a screen from its UI. First pick its back class; the class picks
the pattern. One surface = one class, never both route and overlay.

| Class | Examples | Back semantics | Pattern |
| --- | --- | --- | --- |
| RootSection | Home, Library tabs | System back-to-home. Never intercept. | none — leave back alone |
| Full-screen destination, no shared chrome | Settings | System cross-Activity predictive back | **A — native** |
| Full-screen destination sharing the persistent bar | Album / Artist / Playlist detail | In-window AOSP replica, bar stays pixel-locked | **B — consumed replica** |
| Destination in the shell's detail COLUMN | Album / Artist / Playlist detail on a Wide + tall window (`hasDetailPane`) | Stacked entries pop inside the column; the last one closes the column and the shell widens | **D — column stack** (Nav3 predictive pop + `DetailPaneCloseHandler`) |
| ShellOverlayDown | Now Playing | Collapse toward host/anchor | **C — NP layered back** |
| ShellOverlayUp | Memories | Retreat upward to home (an open diary steps back to its card first) | **C — RevealState q + diary p** (`MemoriesPredictiveBack`) |
| In-page state machine | NP stage (Expanded⇄Compact), album hero⇄tracklist pull-up, Memories card⇄diary | Not a place — back may step the state (NP's stage and the Memories diary do; the pull-up never traps back) | its surface's own controller — never a new Activity or route |
| Stepped flow (shell-owned, full screen) | The first-run landing (`ui/landing/`) | Back = previous step; the FIRST step of a first run is never consumed (system back-to-home); a re-run's first step closes the flow | **E — stepped flow** (`LandingPredictiveBack`) |

Five questions before implementing (unchanged from AGENTS.md, still law):
which class is it; where does back land; does it need an explicit back
affordance; do gesture-dismiss and system back share ONE controller; does it
fully reuse the existing tokens/wrappers?

## Platform contract (what AOSP requires of us)

- `android:enableOnBackInvokedCallback="true"` is set app-wide in the
  manifest; targetSdk is 36. Never override `onBackPressed`/intercept
  `KEYCODE_BACK` — only Compose `BackHandler` / `PredictiveBackHandler`.
- **An enabled callback suppresses the system animation for that press.** The
  gesture is delivered to the app instead. This is both the trap (a stray
  `BackHandler(enabled = true)` silently kills the native preview on a Path-A
  screen) and the mechanism (Path B *deliberately* consumes to substitute its
  own). Audit `enabled` conditions precisely.
- `PredictiveBackHandler { events -> ... }` semantics: `events` is a
  `Flow<BackEventCompat>` (progress 0..1, touchX/touchY, swipeEdge). Normal
  flow completion = **commit**; `CancellationException` thrown into the
  collector = **cancel** — clean up and **rethrow it**. Settle animations must
  run on an outer `rememberCoroutineScope()` because the handler coroutine is
  already dying at cancel time.
- **3-button nav / a11y back emits NO progress events**: the handler body runs
  with an empty flow, then commits. Every handler needs the "never saw a
  gesture" fast path (see `sawGesture` in `DetailPredictiveBackCollapse`).
- Priority is the `OnBackPressedDispatcher`'s LIFO: the latest-registered
  enabled callback wins → **composition order is back priority**. The NP
  overlay mounts last in every window (shell AND each detail Activity), so its
  handlers win while `expanded`; they are all gated on `expanded` so a closed
  overlay never blocks the host's back.

## Pattern A — native cross-Activity predictive back (prefer this)

The zero-cost path, and the default for any new full-screen destination:
make it its own Activity and **register nothing**.

- Manifest: plain `<activity>` entry, `exported="false"`, inherits
  `Theme.Yoin`. Launch with a plain `startActivity(intent)` — no
  `ActivityOptions`, no `overrideActivityTransition`.
- Result: OEM-native open, close, AND predictive-back preview, including
  cross-task polish we could never fake per-device.
- Living example: `SettingsActivity` (`ui/settings/SettingsActivity.kt`,
  manifest entry). It has no back code at all. That is the point.
- Cost: near-zero customization. The system transforms the whole window —
  any chrome inside scales/slides with it. That is exactly why detail pages
  outgrew this path (their bar must stay pixel-locked while content departs).
- In-page state still layers normally: enabled `BackHandler`s for transient
  UI (sheet, search) are fine, but remember each one suppresses the native
  preview *while enabled* — gate them tightly.

Decision rule: **stay on Path A unless a concrete continuity illusion breaks.**
"I could customize it" is not a reason; "the bar visibly jumps" is.

**Wide windows host details as a COLUMN, not a pane of another window
(2026-10-02, adaptive principle 3 — see Pattern D below).** The Activity
Embedding split for shell ↔ detail (and its Pattern A branch, the
`DETAIL_EXTRA_EMBEDDED` / `applyDetailWindowBackMode` runtime switch, the
platform divider) is gone: two Activity windows can never share the one bar
the product wants spanning both columns. Settings keeps Activity Embedding
for its list-detail — those pages are plain Pattern A destinations.

## When cross-Activity is the WRONG shape

Pattern A is for *destinations*. The shapes below must not become Activities —
each is a decision already made, some the hard way. Don't re-litigate without
new evidence.

- **Overlays over live context.** Now Playing rises from the pill IN PLACE
  over whatever window is current — shell or any detail page — and back lands
  on that same live surface beneath. An Activity boundary stops and snapshots
  the host. The earlier relaunch design (detail → shell relaunch with an
  expand-NP extra, `launchShellFromDetail`) showed a home-shell cameo on the
  way back and was deleted: `NowPlayingOverlayHost` is now mounted by the
  shell AND every detail Activity (with `NowPlayingAccessories` beside it),
  so NP opens over the page and back returns to it with a real back stack.
  NP's back is layered state (Expanded → Compact → closed), not a destination
  pop. The same logic keeps Memories a shell surface.
- **In-page state machines are not places.** The NP stage, the album
  hero⇄tracklist pull-up and the Memories card⇄diary are back-steppable
  STATE inside a surface.
  Promoting state to a navigation boundary turns one leave-gesture into two
  and forks the settle owner. (Law: one surface is never both route and
  overlay.)
- **Pixel-locked shared chrome.** The system's cross-Activity preview
  transforms the whole window surface and offers NO partial exemption — a
  native preview and "the bar doesn't move" are physically incompatible.
  That incompatibility is Pattern B's reason to exist. Two middle-grounds
  were tried and are DEAD — do not retry:
  1. OBSERVER-priority `OnBackInvokedCallback` (watch the gesture, let the
     system animate): real devices deliver only `onBackInvoked` to observers
     — never `onBackStarted`/`onBackProgressed` — so there is nothing to
     drive the bar morph with.
  2. Driving the revealed shell off lifecycle signals (`onRestart`): it
     flickered, and a cancelled gesture broke the NEXT one — after cancel
     the shell is never stopped again, so the signal doesn't re-fire.
- **An Activity boundary is a data boundary.** A destination must be
  launchable from an intent-serializable contract (ids + origin extras) and
  paint instantly from cache — the detail split is only viable because
  `DetailCacheStore` (in-memory LRU + Room disk JSON + prefetch) renders a
  cold detail window without a visible load. A surface that needs the host's
  live in-memory state — playback tick lambdas, shared-transition scopes,
  the current aurora palette — belongs in the host's composition, not behind
  an Intent.
- **The split is settled per WINDOW SIZE** (user rulings 2026-07, re-cut
  2026-10-02). Compact / Medium / short windows push detail Activities (B);
  a Wide + tall window hosts the SAME detail composables as a column of the
  shell window (D). Do not promote shell sections, NP, or Memories out of
  the shell Activity, and do not add a third way to show a detail.

## Pattern B — consumed gesture + in-window AOSP replica (detail pages)

Album/Artist/Playlist need the persistent-bar illusion, so the gesture is
fully consumed and AOSP's cross-activity math is re-applied to the page
CONTENT only, while the real window beneath (shell or previous detail) plays
the entering side. Activities stay Activities; only the animation is ours.

Anatomy (all infra, never re-implemented in feature screens):

1. **Manifest + theme**: `Theme.Yoin.DetailTranslucent`
   (`windowIsTranslucent=true`) so the live window beneath can show through
   during gestures and enter/exit fades.
2. **onCreate**: `enableYoinEdgeToEdge()` + `applyDetailCloseTransition()`
   (`ui/detail/DetailBottomBar.kt`) — API 34+ `overrideActivityTransition`
   makes CLOSE an in-place 220ms dissolve (`res/anim/detail_bar_close_*.xml`):
   no translate/scale, so the window's bar dies pixel-aligned over the bar
   beneath.
3. **Launch**: `launchDetailFromShell(context, intent)` — stamps
   `DETAIL_EXTRA_FROM_SHELL` / `ORIGIN_SECTION` / `BAR_HANDOFF` and uses
   `makeCustomAnimation(detail_bar_handoff_enter, _exit)`: incoming window
   holds transparent 200ms (user watches the shell bar morph nav→split live),
   then fades in 180ms over its own pixel-identical bar; shell holds opaque
   380ms beneath. NP-origin launches stamp `FROM_NOW_PLAYING` instead of
   FROM_SHELL — the reveal is NP, which has no bar, so the back scrub must
   NOT morph the bar; it rides the gesture DOWN off-screen instead
   (`DetailBottomBar.backExitProgress`, driven 1:1 by the collapse progress:
   cancel springs it back, commit finishes the ride). The origin decides
   the bar's back behavior once, at launch time, via these extras.
4. **Page side**: `rememberDetailBackCollapse(onBack)` +
   `rememberDetailEnterIntro(barHandoff)`
   (`ui/detail/DetailPredictiveBackCollapse.kt`, `DetailEnterIntro.kt`).
   Apply `.detailBackCollapseTransform(...)` and
   `.detailEnterIntroTransform(...)` to the CONTENT container only;
   `DetailBottomBar` is a sibling that scrubs its own split⇄nav morph off
   `state.progress`. Add `rememberDetailMotionFrameRateModifier` for the
   120Hz vote.
5. **Window beneath** (already wired in `YoinNavHost` — nothing to do for a
   new detail page): `rememberDetailBackEnteringModifier` poses the shell
   CONTENT at −96dp (resident while covered, so the first gesture frame is
   already in pose), scales it in sync, and settles to identity on commit;
   `rememberShellBarChromeMorph` is the ONE owner of the shell bar's nav⇄split
   pose. Bridge = `ExperienceSessionStore.detailBackPhase/`
   `detailBackProgress/detailBackTouchYDelta` — snapshot floats written per
   gesture frame, read ONLY inside `graphicsLayer` lambdas: zero
   recomposition at gesture Hz in either window.
6. **Translucency dance** (all inside the infra): gesture start →
   `setTranslucent(true)` + phase `Gesture`; commit → phase `Committed`
   (content exit fade ≈90ms, activity finishes into the close dissolve;
   beneath, pose settles 450ms EMPHASIZED and bar morph rides the SAME spec
   the dying bar used, so the crossfade is one trajectory — this is the fix
   for the historical "pill flash"); cancel → settle spring to 0, restore
   opaque only after a 250ms STATIC frame (flipping the surface mid-motion
   reads as a flash). The detail then STAYS translucent for its whole life
   (converting it opaque let WM discard the window beneath, and the first back
   frame showed a black ring). So the window beneath is never stopped — only
   PAUSED — and Compose pauses a frame clock on ON_STOP only:
   `CoveredWindowAnimationGate` (`ui/experience/`, installed in MainActivity
   and every detail Activity) pauses the covered window's Recomposer frame
   clock 2.5s after another gated Yoin window covers it (never under a system
   sheet or dialog), thaws it the moment the detail directly above starts a
   back gesture or commit (`ExperienceSessionStore.windowBeneathRevealed`
   carries the revealed window's key — every detail is stamped with its own
   key and the key beneath at launch, so a nested back never wakes the shell
   two windows down), on ON_START / ON_STOP / ON_RESUME, and thaws a frozen
   window that keeps drawing for a short settle. State another
   window drives per frame (the shared pill wave, the 4Hz progress, the
   visualizer signal) is held while `LocalWindowCovered` is true. Measured
   2026-10-06: the hidden shell had been redrawing every vsync under any open
   detail page while music played.
   **The window must be translucent — and the conversion landed — BEFORE
   `finish()` on EVERY commit path.** An opaque finish makes the system
   animate the whole revealed window in from the right (our alpha-hold close
   override does not replace it on a translucent-themed activity) — that was
   the "content jumps in right-to-left" on button-backs and on gestures
   whose setTranslucent silently failed. Button-backs therefore convert
   first, play the SAME commit motion from rest (chased→1 + 140ms exit
   fade), and wait `BUTTON_BACK_REVEAL_GRACE_MS` (90ms) so the just-woken
   window beneath has a first frame up — finishing instantly revealed a
   floating bar over a black void.

QA harnesses: `debug/BarMorphPreviewActivity` exercises the bar pose
trajectories without playback; `BackMotionTokensTest` pins the AOSP constants.

### Accepted compromises (intentional — do not "fix")

- Deep detail stacks: an intermediate page's chrome restores only on a
  shell-visible lifecycle tick, so multi-level back can show a slight bar
  discontinuity. The gating exists because the process-wide frame clock
  would otherwise play the reverse morph invisibly under the covering
  window and desync the eventual reveal.
- OEMs that replace window animations degrade the open hand-off to a
  crossfade over the morph. Acceptable — the bar illusion survives.
- Pre-API-30 has no `setTranslucent`: detail windows stay translucent for
  their whole life (one extra layer of overdraw). Accepted.
- Pre-API-34 has no `overrideActivityTransition`: the system close animation
  plays and the bar rides the window. Accepted on legacy.
- The two-column NP (Wide + tall only) has no Expanded substate — back
  from it steps to the side panel, then closes.

### Pattern B on a Medium window: the in-window bar morph

A plain push over a VISIBLE centred bar (`DetailLaunchMode.PlainPush`,
`DETAIL_EXTRA_BAR_MORPH`) keeps the whole bar story inside the detail
window: its `DetailBottomBar` starts at chromeProgress 0 — the nav pose,
a pixel twin of the shell bar beneath (same tab via `navSection`, same pill)
— and `rememberDetailBarEnterProgress(inWindowMorph = true)` springs it to
the detail pose the moment the page is revealed (`intro.pageVisible`, the
same beat as the 96dp slide). Back scrubs it home through
`backMorphProgress` and the commit finishes at nav before `finish()`, so
the close dissolve lands on the identical shell bar. A back gesture (or a
button back's commit) arriving while the enter spring is still running
FREEZES it where it is (`localMorph.stop()`), so the scrub starts from the
pose on screen — one driver at a time, never a snap to the full detail
pose. Nothing is bridged to
the shell (`bridgeBackToShell = false`): the shell bar never moves, no
store phase is written. Over Now Playing / Memories the push stamps
`FROM_NOW_PLAYING` / `FROM_MEMORIES` instead and the bar rides the gesture
away (`barExitsOnBack`).

## Pattern D — the detail column (Wide + tall windows)

`ui/navigation/pane/DetailPaneHost.kt` + `YoinNavHost`'s column layout. On a
window with `hasDetailPane` the shell opens Album / Artist / Playlist as a
Navigation 3 stack in a right column (gutter 24dp with the M3
`VerticalDragHandle`); the window's one bar spans both columns in its merged
pose and carries the top entry's Play split. Back has two levels, both on a
CHILD back dispatcher (`rememberNavigationEventDispatcherOwner(enabled =
shellBackOwner == DetailPane)`, provided through
`LocalNavigationEventDispatcherOwner` to the column's NavDisplay and to
`DetailPaneCloseHandler`). Handler priority is registration order, and the
column's NavDisplay registers when the column first opens — AFTER Now
Playing's handlers — so mount order cannot express "Now Playing wins";
the dispatcher gate does:

1. **Stacked entry → previous**: NavDisplay's own predictive pop
   (`predictivePopTransitionSpec`): the leaving page scales toward
   `PopPageScaleTarget` and dissolves while the previous page rides in from
   `EnteringStartOffset` on `PostCommitDurationMs` EMPHASIZED — the AOSP
   shape, seeked by the finger, never a layout scrub. Pushes are the mirror
   (incoming from +96dp, beneath recedes −96dp).
2. **Last entry → column closed** (`DetailPaneCloseHandler`): the page
   scales toward 0.9 + 28dp corner under the finger 1:1 (`backGestureEasing`);
   commit hands the close to the shell's open/close spring
   (`DetailPaneState.openFraction`, the single owner of the column width —
   the column slides out past the window edge while the shell widens and
   the bar lerps back to its nav pose on the same spring); cancel springs the
   scale home. `ShellBackResolver` ranks it NowPlaying > DetailPane >
   Memories so Memories' handler is disabled while a column is open.

Pages composed in the column read `LocalDetailHostMode == Pane`:
`DetailBottomBar` returns early (no bar), `rememberDetailBackCollapse`
registers nothing, `rememberDetailEnterIntro` mounts at once (the column
itself slides). Leaving the Wide tier with a column open (tablet rotated to
portrait) relaunches its top page as the pushed Activity that tier uses.

## Pattern C — shell overlays

### Now Playing (ShellOverlayDown) — `ui/nowplaying/NowPlayingOverlayHost.kt`

Layered back, all gated on `expanded` (+ layout mode):

- **Expanded → Compact**: `PredictiveBackHandler` SNAPS the eased progress
  into `StageBackPreview` on every back event (no chase — the old spring
  restarted per event kept the stage trailing the finger, owner 2026-10-07
  "不跟手") and draws the detail pages' AOSP pose on the stage: scale 1 → 0.9,
  shift toward the swipe edge, decelerated vertical follow, 8dp margin
  (`backPreviewTransform`, draw phase only). Only the release springs it
  home. The layout reshape is **not**
  scrubbed — `detail` stays 1 under a gesture-gated reconcile. Why: a partial
  layout reshape freezes a half-built, truncated stage; a uniform scale of the
  complete layout cannot truncate. COMMIT calls `stepBackStage()` and the real
  reshape (detail 1→0) runs through the reconcile effect — the single settle
  owner — while the scale springs home. CANCEL just springs the scale back.
- **Compact (or Wide) → dismiss**: progress drives a capped slide-down —
  target = `progress × 1200px` through a spatial `animateFloatAsState` (the
  "cap + chase" preview: partial travel, spring chasing the finger). It
  shares one visual channel with drag-to-dismiss via
  `dismissFraction = max(dragProgress, backProgress)` — one controller, per
  doctrine.
- Matching plain `BackHandler`s carry the same two-step semantics for
  button-back.

**Side panel ⇄ full state (2026-09-30 breakpoints; Wide joined 2026-10-02,
adaptive principle 4).** On a Medium OR Wide window NP opens as a phone-width
side panel sliding in from the right (content beside it stays usable), with a
corner button to the full state — the enlarged phone on Medium, the
two-column player on Wide. Classification is unchanged — still ShellOverlayDown /
Pattern C, the SAME controller; the panel only adds a rightward retreat
direction. The layered chain gains one level, and the three `enabled` flags
are mutually exclusive (`NowPlayingOverlayHost`):

1. Expanded stage → Compact (scale preview, unchanged)
2. enlarged → panel: the same uniform SCALE preview of the complete stage;
   the container's width change runs only on commit, on its own spring
   (never scrub the container width with back progress — invariant 3)
3. close: phone / enlarged / two-column slide down, the panel slides RIGHT;
   drag-to-dismiss runs on the same `dismissFraction` channel (vertical drag
   on the phone / enlarged phone, horizontal on the panel; none in the
   two-column player)

The ▾ top-left always closes outright. Where the panel cannot fit (window −
panel < 320dp, e.g. a 640dp split half) the full state opens directly and
level 2 never exists. Host content beside the panel reads its OWN remaining
width (`forPaneWidth`) through a provider that is ALWAYS in the tree
(`ProvideBesidePanelWindowInfo` / the shell's `rememberColumnWindowInfos`):
swapping a wrapper in and out re-created the detail page, re-registered its
back callback ABOVE NP's (LIFO) and stole the panel's back — mount order is
back priority.

### Memories (ShellOverlayUp) — `ui/navigation/back/MemoriesBackHandler.kt` + `RevealState` + `MemoriesDiaryState`

The showcase v4 (2026-10-04; spec `docs/handoff/memories-showcase/`) has two
controllers with clean borders, fed by one gesture router and one back handler:

- **q** = `RevealState.fraction`, the retreat to Home (0 open, 1 gone). The
  ONLY displacement: the host's `rememberMemoriesHostPose` translates the page
  by −q·H, and Home behind rides the same q (scale 0.94 → 1, alpha 0.5 → 1,
  layer only). Hard clamp at open. State-driven moves go through ONE effect —
  the host's `LaunchedEffect(homeSurface)` calling `launchAnimateTo` (never
  `settle()`), or two drivers race the fraction. Home's pull-down open still
  releases on `settle()` (fraction rule); Memories' own drags release on
  `settleDismiss` (dp rule, below).
- **p** = `MemoriesDiaryState.fraction`, card (0) ⇄ diary (1); below 0 is the
  card's own pull-down rubber band (0.3×, floored at −90dp of morph travel).
  An in-page state machine, not a route and not a second overlay. Every p
  move — Diary button, bar cover / ⌄ tap, drag release, back commit, back
  cancel — rides ONE morph spring (`defaultSpatialSpec`, Expressive;
  `slowEffectsSpec` under reduced motion). Any new input cancels a running
  spring and catches p where it is.
- **One router** (`ui/memories/showcase/MemoriesGestures.kt`,
  `Modifier.memoriesGestures` on the Memories root) locks an axis at slop and
  hands a vertical drag to exactly ONE controller: bar up → q (bar rule); bar
  down with the diary open → p 1:1, scroll frozen; card body up → q (body
  rule); card body down → p's rubber band, capped at the card (it never opens
  the diary); diary text → its own scroll, and only the user-input overflow
  past the top reaches p (first `MemoriesDiaryPullBand` 24dp at half speed).
  Thresholds count finger travel from touch-down; the page follows from the
  lock, so slop is never applied as a jump.
- **Pull-past-end.** A NEW upward drag that starts with the diary already
  resting at its end goes to q on the body rule. A fling that reaches the end
  (or the top) only stops there — flings never cross into q or p.

**Dismiss rules are dp, not fractions** (`RevealState.settleDismiss` + the pure
`chooseDismissTarget`; px built by `rememberMemoriesDismissRules`):

| Drag source | Commit past | or faster than | Flick back returns at |
| --- | --- | --- | --- |
| Card body, diary end (pull-past-end), spread left page / right page end | `MemoriesDismissTrigger` 112dp | `MemoriesDismissFling` 600dp/s | `MemoriesFlickBack` 350dp/s |
| Top bar (card and diary) | `MemoriesBarDismissTrigger` 56dp | `MemoriesBarDismissFling` 450dp/s | 350dp/s |

Order: a flick back ≥ 350dp/s returns even past the line; else past the line
or above the speed commits; else returns. A commit fires CONFIRM and the
retreat rides out untouched (drags ignored until it lands). Crossing the line
ticks (CLOCK_TICK), and again on the way back. The bottom corners
(`Modifier.memoriesDismissCorners`) are `PopPageCornerRadius` ·
smoothstep(0, threshold / H, q), threshold = the rule driving q (56 bar, 112
body and back) — full exactly where a release would commit. Flat, no shadow;
no corners under reduced motion (the host fades the page in place, alpha
1 − q). A diary pull releases by `chooseDiaryReleaseTarget`: rubber band →
card; a pull that started in scrolled text and is still inside the half-speed
band stays; else a 350dp/s flick decides; else p > 0.5.

**System back: two levels, one handler** — `MemoriesPredictiveBack(enabled,
level, reveal, containerHeightPx, onDismiss, diary)`, the one
`PredictiveBackHandler`, living in back infra (invariant 11). Mounted where
the shell's `BackHandler` used to be, `enabled = shellBackOwner ==
ShellBackOwner.Memories` (Memories ranks last in `ShellBackResolver`). The
level is captured once per gesture:

1. **Diary level** (`isDiaryLevel`, p ≥ 0.5): `p0 = diary.stop()` catches a
   running open/close spring; p is then NOT scrubbed. Each event snaps the
   page's AOSP pose instead — `StageBackPreview` on the whole showcase
   (`Modifier.backPreviewTransform`): scale toward 0.9, shift toward the
   swipe's edge, decelerated vertical follow — exactly the Now Playing stage
   preview. Commit → `launchAnimateTo(0)` (the morph runs on its spring);
   cancel → `launchAnimateTo(1)` if a spring was caught; both springs the pose
   home. It lands on the card; the next back is level 2. (Until 2026-10-09 this
   scrubbed p = p0 · (1 − ease(progress)) over the full range; the ease is
   front-loaded, so most of the diary folded away in the first stretch of the
   swipe — owner: back "took the whole travel". Scrub p never again.)
2. **Card level** (p < 0.5): `snapTo(q0)` stops any settle in flight; each
   event snaps q = q0 + (max(Δ, q0) − q0) · `backGestureEasing`(progress),
   Δ = `MemoriesDismissTrigger` / H. Progress 1 parks the page exactly on the
   finger's 112dp commit line — the preview's full travel IS the commit
   distance (no longer a hint); a back that catches q already past Δ holds it.
   Commit → `onDismiss` (the host effect springs q to 1); cancel →
   `reveal.animateTo(0)`; both on RevealState's `predictiveBackSettleSpring`.
   The award ceremony holds while the preview runs
   (`onCardBackStarted` / `onCardBackFinished`).
3. **The spread** (Expanded container: exhibit left, diary right) has no
   diary state, so back has ONE level: card level → Home. p keeps its value
   (a diary open in portrait is still open after rotating back); it is just
   not read.
4. **A title being edited** (2026-10-05; tap the title on the card, the
   diary or the spread's left page — `MemoryTitleEditor`,
   `ui/memories/showcase/MemoryTitleEditor.kt`) sits above every other
   level: `MemoriesBackLevel.TitleEdit`. Preview = the edit's Cancel / Save
   row fades by `backGestureEasing`(progress), 1:1 (the title is never
   scrubbed); commit cancels the edit (the field closes on the title it had,
   the keyboard goes); cancel brings the row back on the effects spring.
   While it lasts the router routes every vertical drag to `Held` (neither q
   nor p moves), the pager stops and the diary's pull past its top doesn't
   reach p. An IME that is up takes the first back itself (it closes); the
   next one cancels the edit.

Empty flow (3-button / a11y back) commits directly at either level
(invariant 8); settles run on the composable's outer scope and
`CancellationException` is rethrown (invariant 7); `voteHighFrameRate` while
q or p moves (invariant 10). Pure math is in `MemoriesBackMath`
(`MemoriesBackMathTest`).

**Invariant 3 here.** Back progress DOES scrub the card ⇄ diary morph, and
that is allowed: the card layer and the diary layer are always fully laid out,
and `MemoriesMorph.kt` only transforms them (translation, scale, alpha, a
per-frame corner) — nothing is resized or cut while p moves. That is the
"transform preview of complete layouts" invariant 3 asks for. The one
layout-phase value p drives is chrome: the Home pill narrowing 88 → 36, read
in `Modifier.layout` (invariant 6), with its label already gone by fp 0.4.

## Pattern E — the stepped flow (first-run landing, 2026-10-09)

`ui/landing/LandingScreen.kt` + `ui/navigation/back/LandingBackHandler.kt`.
The landing is mounted by MainActivity ABOVE the shell in the same window
(so its pill can become the shell's bar at the hand-off). While it shows it
covers everything, so its one handler is the only enabled one (it registers
after the shell's); NP and Memories cannot be open under it on a first run.

- **One level.** `enabled = !finishing && (index > 0 || mode == Rerun)`. On a
  first run's first step it is disabled: root back is never consumed and the
  system's back-to-home plays.
- **The step change is never scrubbed** (the Memories diary lesson): each
  event snaps `StageBackPreview.progress` = `backGestureEasing(progress)`,
  and the stage applies the AOSP pose (scale → 0.9, edge shift, vertical
  follow) to the window + mascot + bubble as ONE page about the window's
  centre — the pill is chrome and stays. Commit → the ViewModel steps back
  and the step transition runs on its spatial spring while the pose springs
  home on `predictiveBackSettleSpring`; cancel springs the pose home. Empty
  flow (3-button) commits at once.
- The pill's back button calls the same `vm.back()`; the step transition is
  the one owner of the stage (`LandingMotion.t`).
- The Spotify guide (`SpotifyGuideActivity`) is a picture-in-picture window
  over the browser in its own task: back there belongs to the browser.

## AOSP replica tokens — the source of truth

Everything below is ported from `frameworks/base` WM Shell
(`CrossActivityBackAnimation` / `DefaultCrossActivityBackAnimation`,
`BackProgressAnimator`, `Interpolators`). Do not invent new values; if a new
surface needs one of these, import the token.

| Behavior | AOSP source | Value | Yoin home |
| --- | --- | --- | --- |
| Gesture progress easing | `BackGestureInterpolator` | cubic-bezier(0.1, 0.1, 0, 1) | `YoinMotion.backGestureEasing` |
| Closing page min scale | `MAX_SCALE` | 0.9 | `BackMotionTokens.PopPageScaleTarget` |
| Screen-edge margin | `displayBoundsMargin` | 8dp | private consts in the two back files |
| Horizontal anchor asymmetry | closing-rect targeting | left-edge swipe parks the scaled content's right edge 8dp from the screen edge; right-edge swipe stays centered | `detailBackCollapseTransform` |
| Vertical follow | `getYOffset` | decelerate(min(&#124;Δy&#124;, h/2) / (h/2)) × slack-to-margin, signed | both back files |
| Entering start offset | `cross_activity_back_entering_start_offset` | 96dp | `DetailBackEntering` + `DetailEnterIntro` (forward mirror) |
| Post-commit settle | `POST_COMMIT_DURATION` + `Interpolators.EMPHASIZED` | 450ms, cubic-bezier(0.05, 0.7, 0.1, 1) | both back files |
| Closing content fade | post-commit alpha | max(1 − 5t, 0) — gone in the first fifth | `detailBackCollapseTransform` + 140ms exit tween |
| Corner radius while shrinking | device window corner stand-in | 28dp | `BackMotionTokens.PopPageCornerRadius` |
| Cancel settle | (ours — platform doesn't specify) | spring, low-bouncy, MediumLow × 1.3 | `YoinMotion.predictiveBackSettleSpring` |

`BackMotionTokensTest` pins scale + corner radius — extend it when adding a
token.

## Invariants (each one is a shipped bug's tombstone)

1. **One value, one settle owner.** The NP reshape settles ONLY via its
   reconcile effect; the bar chrome pose is ONE lone Animatable; RevealState
   effects use `launchAnimateTo` only. A handler that also `animateTo`s the
   same value races it and flashes.
2. **The finger owns the gesture 1:1.** During events: `snapTo(eased)` — no
   smoothing between finger and pixels (platform behavior). Springs run only
   on release. (Exception: overlay previews like NP's may chase, because they
   preview partial travel, not a window transform.)
3. **Never scrub a layout reshape with back progress.** Preview with a uniform
   scale of the complete layout; run the real reshape on commit. Sole
   exception: host content BESIDE the Now Playing side panel takes the vacated
   width back 1:1 with the panel's back/drag travel (`NowPlayingPanelMotion`
   → `Modifier.besideNowPlayingPanel`, layout phase only, tier unchanged).
   The surface being dismissed is never reshaped. "Reshape" means measure and
   size: a transform preview of FULLY laid-out layers is fine at any range —
   the Memories card ⇄ diary morph scrubs p over 0..1 because both layers stay
   complete and only `graphicsLayer` moves, scales and fades them.
4. **Transform content only.** Persistent chrome (bars) are siblings of the
   transformed container — in the page, in the shell, and in window-level
   anims (alpha-only, never translate/scale).
5. **Both sides of a commit ride the same spec.** The dying window's bar scrub
   finishes on the same spring the revealed bar settles with.
6. **Per-frame values are read only in layout or draw** — snapshot floats
   read inside `graphicsLayer` (the `ExperienceSessionStore` bridge), layout
   modifiers (`besideNowPlayingPanel`), measure policies
   (`DetailColumnsLayout`), or reader lambdas installed once
   (`NowPlayingPanelMotion.travelReader`). Gesture and spring frames must not
   recompose either window; tiers change only at rest (adaptive principle 5).
7. **Rethrow `CancellationException`; settle on an outer scope.**
8. **Handle the empty-flow button-back path** (no progress events → skip
   straight to the commit motion).
9. **Mount order = back priority (LIFO).** Gate every handler's `enabled`
   precisely; a closed overlay must never intercept.
10. **Vote high frame rate for post-release settles**
    (`voteHighFrameRate` / `rememberDetailMotionFrameRateModifier`) — no
    touch boost means ARR paces them at 60Hz on a 120Hz panel otherwise.
11. **No raw `PredictiveBackHandler` and no new magic numbers in feature
    screens.** Back infra lives in `ui/navigation/back/`,
    `ui/navigation/pane/DetailPaneHost.kt` (`DetailPaneCloseHandler`, the
    column NavDisplay's pop specs — on the gated child dispatcher, see
    Pattern D), `ui/detail/` (collapse/intro), `ui/nowplaying/NowPlayingOverlayHost`,
    `ui/experience/RevealState`. Feature screens consume wrappers and tokens.
12. **Flip window translucency only on static frames** (250ms after settle)
    — a surface swap mid-motion reads as a flash. Detail windows stay
    translucent; a covered window's animations are frozen instead
    (`CoveredWindowAnimationGate`), and anything that reveals a window beneath
    must set `windowBeneathRevealed` first.
13. **On a Path-A screen, audit every `BackHandler(enabled=…)`** — while
    enabled it suppresses the native preview.
14. **Nothing cross-window may ride the system close dissolve.** The dissolve
    runs on WM Shell's clock in another process; an app-side fade can't
    phase-lock to it (the old bar-shadow return fade drifted 1–3 frames:
    doubled shadow, then the "white flash"). Hand state over while both
    windows are still ours, BEFORE `finish()` — the bar shadow does it via
    `BottomBarShadowRegistry.handBack` (one `SurfaceSyncGroup` transaction on
    API 34+, spring crossfade below) — so the dissolve only fades a bare bar
    over its identical twin. Two same-process windows drawn in one pass can
    still present a vsync apart; only a synced commit is frame-exact.
15. **A sheet opened from a detail page is a `YoinModalBottomSheet`
    (`ui/component/`), never a raw M3 `ModalBottomSheet`.** The sheet is its
    own dialog window; in the Wide column its composition inherits the
    column's child dispatcher, so M3 registers its back THERE while system
    back goes to the sheet's window — back and the gesture did nothing (album
    column sheets, device QA 2026-10-05). The wrapper gives the sheet its own
    dispatcher and forwards that window's back phases into it
    (`ui/navigation/back/SheetWindowBack.kt`); M3's predictive shrink stays.

## Pre-ship checklist for any back surface

- Class declared; back destination explicit; explicit affordance present
  (detail pages: the bar's close; overlays: dismiss control).
- Gesture-dismiss and system back share one controller/progress family.
- On device (gesture nav): slow drag from BOTH edges, hold, cancel; fling
  commit; long-drag commit (the historical pill-flash case); 3-button back;
  back during the enter hand-off; rotation mid-session (intro must not replay
  — `rememberSaveable`); Wide layout (NP Expanded doesn't exist there);
  Medium / Wide full window: panel → full screen → back → panel → back →
  closed, and a detail page beside the open panel must not take NP's back;
  Wide: open a detail column, push a second page, back twice (pop, then
  close), drag the handle, rotate to portrait with the column open; a sheet
  in the column: back / gesture closes the sheet only (cancel keeps it);
  back on a detail opened OVER Now Playing (bar rides the gesture down —
  no split→nav morph); 120Hz panel — settles must not pace at 60Hz.
  Memories: back from the diary (→ card) and from the card (→ Home; the page
  parks on the 112dp line, corners full); a slow 112dp push commits and 100dp
  doesn't, the bar commits at 56dp, a fast flick back returns; pull-past-end
  from the diary's end vs a fling that only reaches it; a spread (one level).
- No new constants outside `BackMotionTokens`/`YoinMotion`; tokens test
  extended if one was added.

## File map

| Concern | File |
| --- | --- |
| Gesture easing, settle springs, nav specs | `app/src/main/java/com/gpo/yoin/ui/theme/Motion.kt` |
| AOSP scale/corner tokens (+ test) | `ui/navigation/back/BackMotionTokens.kt`, `app/src/test/.../BackMotionTokensTest.kt` |
| Detail page collapse (top window) | `ui/detail/DetailPredictiveBackCollapse.kt` |
| Forward enter slide (96dp mirror) | `ui/detail/DetailEnterIntro.kt` |
| Entering window + shell bar morph owner | `ui/navigation/back/DetailBackEntering.kt` |
| Launch helpers, extras, close dissolve | `ui/detail/DetailBottomBar.kt` |
| Window anims (handoff/close) | `app/src/main/res/anim/detail_bar_*.xml` |
| Cross-window pose bridge, phase enum | `ui/experience/ExperienceSessionStore.kt` |
| Shell back ownership (NowPlaying > DetailPane > Memories) | `ui/navigation/back/ShellBackResolver.kt` |
| NP layered back | `ui/nowplaying/NowPlayingOverlayHost.kt` |
| Sheet back from a detail page (any host) | `ui/component/YoinModalBottomSheet.kt`, `ui/navigation/back/SheetWindowBack.kt` |
| Predictive back inside a Compose Dialog's own window (Home's account switcher) | `ui/navigation/back/DialogWindowBack.kt` |
| Detail column: stack, close handler, divider, layout, tiers, entries, routes, host mode | `ui/navigation/pane/DetailPaneHost.kt`, `PaneSplit.kt`, `DetailPaneEntries.kt`, `DetailPaneRoute.kt`, `ui/detail/DetailHostMode.kt` |
| Adaptive principles (window / column / bar / NP chain) | `docs/adaptive-principles.md` |
| Memories / pull-up controller q (+ dp rule `settleDismiss` / `chooseDismissTarget`) | `ui/experience/RevealState.kt` |
| Memories back (two levels), dismiss rules, retreat corners (+ `MemoriesBackMathTest`) | `ui/navigation/back/MemoriesBackHandler.kt` |
| Memories diary controller p, gesture router, card ⇄ diary morph | `ui/memories/showcase/MemoriesDiaryState.kt`, `MemoriesGestures.kt`, `MemoriesMorph.kt` |
| Manifest opt-in, themes | `app/src/main/AndroidManifest.xml`, `res/values/themes.xml` |
| Bar-morph QA (no playback needed) | `app/src/debug/.../debug/BarMorphPreviewActivity.kt` |
| First-run landing back (stepped flow, Pattern E) | `ui/navigation/back/LandingBackHandler.kt`, `ui/landing/LandingStage.kt` (preview pose) |
