# Yoin 鲲 mascot → Rive `.riv` 构建手册

Authoring handoff for turning the two chosen web prototypes (② 摸鱼 / ⑥ 重力) into a
single `.riv` played by **rive-android** in the Yoin Compose app.

## Status / prerequisites
- `Rive.app` (Early Access) installed at `/Applications/Rive.app`, **running**, MCP at `http://127.0.0.1:9791/mcp`.
- MCP wired into Claude Code: `claude mcp add --transport http rive http://127.0.0.1:9791/mcp` → **✔ Connected** (local config, `~/.claude.json`, project `/Users/gpo/Developer/Yoin`).
- ⚠️ The `rive` tools only load **on Claude Code restart**. After restart, keep Rive.app open and say "继续做 riv".
- Rive MCP edits a **live editor session** and only commits when you type **`End Prompt`**.
- Source vectors: [`icon.svg`](icon.svg) (real Figma export). Reference motion: [`index.html`](index.html) cards ② and ⑥.

## Artboard `kun` (512 × 512, origin centered at 256,251)
Recreate / import the layers from `icon.svg`. Back → front, names + pivots (Rive origin = the hinge so fins rotate naturally):

| Rive group | from icon.svg | fill | origin (viewBox px) |
|---|---|---|---|
| `halo` | 2 rings + 2 white streaks | white | 256,251 |
| `body` | body path + wing path | grad `#819FEF→#697491`, wing `#E1F4F7` | 256,251 |
| `fin_cream` (左上手) | `fin-cream` | `#FFEADB` | **210,219** |
| `fin_tan` (右手) | `fin-tan` | `#BC977B` | **405,270** |
| `fin_cyan` (尾巴) | `fin-cyan` | `#47D6EA` @0.28 | **152,293** |

Outward radial unit vectors (centroid − center), used for tilt/stroke direction:
cream `(-0.69,-0.72)`, tan `(1.00,0.04)`, cyan `(-0.99,0.13)`.

## State machine `Mascot` — inputs (the Kotlin contract)
| input | type | range | meaning / driver |
|---|---|---|---|
| `tiltX` | number | -1..1 | ⑥ 重力: spring-smoothed device tilt (Compose feeds it) |
| `tiltY` | number | -1..1 | ⑥ 重力 |
| `petSpeed` | number | 0..2 | ② 摸鱼 stroke tempo (1 = default; bind to isPlaying/tempo) |
| `breach` | trigger | — | optional: tap squash (proto ③, parked) |

## Timelines
**`idle_pet` (loop)** — proto ②, this is the resting state:
- `body`: scale 1 ↔ 1.022, rotate ±1°, ~2.6s, ease-in-out (kun being petted, breathing).
- `fin_cyan` (tail): scale 0.90 ↔ 1.14, rotate ±6°, translate ~(-10,2)px, ~3.4s, ease-in-out (lazy swish).
- `fin_cream` + `fin_tan` (hands): stroke along the body contour, **L/R offset by half a cycle**.
  Per-hand path = oscillate ±26px along the local body tangent, plus a small (~7px) outward bow so the
  hand rides the bulge, plus ±6° wrist rotate. Tangents: cream `(0.54,-0.84)`, tan `(-0.64,0.76)`.
  Easing ≈ Rive **Elastic/ease-in-out** (the web uses a damped-spring `linear()`).
- `petSpeed` scales the timeline play rate.

**Tilt (⑥)** is *additive*, not a separate timeline — Rive only maps numbers to transforms; the spring + sensor live in Compose:
- Map `tiltX`/`tiltY` → each fin's translate (×~70px) + slight rotate, **different gain per fin**
  (cream/tan/cyan stiffness differs → trailing). Cleanest in Rive: two 1-D states (tiltX min↔max,
  tiltY min↔max) blended by the number inputs, layered over `idle_pet`.

## rive-android binding (Compose)
```kotlin
// load
RiveAnimationView(...).apply { setRiveResource(R.raw.kun, stateMachineName = "Mascot") }
// ⑥ gravity: SensorManager TYPE_GRAVITY → Compose spring() → feed numbers
view.setNumberState("Mascot", "tiltX", springX.value)   // springX/Y = animateFloatAsState over sensor x/y
view.setNumberState("Mascot", "tiltY", springY.value)
// ② tempo (optional)
view.setNumberState("Mascot", "petSpeed", if (isPlaying) 1f else 0.4f)
```
Notes: keep physics (spring damping, per-fin stiffness, sensor zeroing/`betaRef`) on the **Compose** side
(Rive springs are baked Elastic, not runtime physics). ⑥ could even ship as Compose-only; ② is the real
win for Rive (a clean looping timeline a designer can tweak). See `[[project-mascot-and-rive]]` memory.

## Gotchas (Rive MCP — learned the hard way)
- **Rotation `r` (propertyKey 15) is in RADIANS**, not degrees — ±6° = ±0.10472. Scale `sx`/`sy` (16/17) are unitless; `x`/`y` (13/14) are artboard px.
- Each SVG-imported shape's transform pivot = its **own bbox center** (shape.x = worldCenter − 256). Good enough for gentle rotate/scale; no pivot surgery needed.
- `createTransitions` will NOT create the **Entry→default** transition (it parses `to` as a number/index and no-ops or RangeErrors). Workaround: draw Entry→state by hand in the editor, OR skip the SM and play the linear animation `idle_pet` by name in rive-android.
- Rive MCP edits a **live** editor session; commit with `End Prompt`.

## Export
Rive editor → Export → **Runtime (.riv)** → drop into `app/src/main/res/raw/kun.riv`.
```
```
