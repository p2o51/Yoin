# Glass layers: which controls are glass, and why (2026-11-04)

51's question after the Gemini preset: *which* operation buttons should be glass?
Glass or not, the hierarchy must be distinct and should feel like being inside a
constructed world. Apple Music treats the mini player as an object that expands
into a page with no glass inside. We don't copy that (51: drop the Apple-Music
"Object" direction). Yoin's own answer is **World**, the new Glass Lab default
(`GlassPreset.world`, `GlassLab.applyWorld`). Balanced and Gemini stay selectable
in Glass Lab for comparison (`-preset balanced|gemini|world`).

Colours: outside Now Playing, World uses Gemini's colours (51: they're better
there). Inside Now Playing it restores the cover colour to Balanced's strength or
higher (51: under Gemini the cover colour was too faint on the NP background and
on PAUSE).

## Rules (R1–R7)

Drafted with the coordinator, then critiqued and extended by Gemini 4 Argon
(two rounds, transcript below). Every element on the map cites one of them.

- **R1 Relative motion.** A surface is Liquid Glass only if it stays pinned to the
  viewport or room frame while content scrolls, drags or morphs underneath it.
  Controls that are part of the content flow or of the static stage use the tonal
  material of the surface they sit on. A static slider (rating, progress) is
  **lifted** to interactive glass only while it is being held or scrubbed, then
  settles back.
- **R2 One accent per layer.** Each layer has at most one accent-tinted control:
  its "you are here" item or its primary action (Root: the selected bar tab;
  album / playlist / artist: the Play split; Now Playing: PAUSE; expanded Now
  Playing: the selected page tab). Every other glass control on that layer gets a
  neutral `surfaceContainerHighest` floor.
- **R3 Depth = blur.** When a layer opens on top (Now Playing, a sheet), the
  layer behind scales down, dims and blurs, in lockstep with the opening
  progress (Now Playing: the morph's one progress shared by ⌄, the drag and
  system back; sheets: the sheet's spring).
- **R4 One light source.** Shadows are neutral black and fall straight down
  (x = 0, y > 0). Only the top floating layer casts elevation shadows; covers
  cast a contact shadow (R6).
- **R5 Objects transform, they don't appear.** The mini player is Now Playing
  folded up: same material (`background` glass), same colour, same ambient (at
  0.38 already inside the pill). Opening is one glass shape unfolding.
- **R6 Covers are physical objects.** Solid, borderless, continuous corners, a
  contact shadow on hero-sized covers. Never glass.
- **R7 Rooms vs. action chrome** (Gemini). Room surfaces (the NP room, the mini
  player) carry a diffused multi-hue atmosphere; action controls carry one solid
  role tint. Because they read differently, a room vessel and an accent control
  can share a layer without breaking R2. This is why the cover-tinted mini player
  can sit next to the tinted selected tab.

## Layer model

| Layer | What | Material | Tint | Shadow | Enters by |
|---|---|---|---|---|---|
| **L0 World** | App surface; the cover-colour atmosphere (Home card washes, the NP room's ambient) | none / ambient blur | cover scheme (`background`, ambient blobs) | none | — |
| **L1 Content** | Everything that scrolls: rows, cards, covers, album hero, lyrics, settings rows | tonal / material (never glass) | neutral or cover roles of the content | covers: contact shadow only | scrolls |
| **L2 Shell chrome** | Pinned over L1: bottom bar + mini player, back / ⋮ circles, search field, pinned chips, Play split, album spectrum band, fast scroller | glass | neutral floor `surfaceContainerHighest` 0.52; one accent | black 0.14 / 10 / 5 | morphs in the bar's GlassEffectContainer |
| **L3 Room: Now Playing** | The mini player unfolded. Room = frosted cover glass; fixtures in the stage are tonal; chrome pinned over the lyrics / pages is glass | room: glass `background` 0.46 + ambient; chrome: glass; fixtures: tonal | PAUSE / selected tab primary; rest neutral or tonal cover containers | chrome black 0.14–0.20 / 10–12 / 5–6 | morph from the pill; L1 + L2 recede 0.94 / dim 0.22 / blur 10 |
| **L4 Sheets** | Queue, song menu, Add to playlist | system sheet glass | system | system | slides up; what's behind recedes 0.96 / 0.14 / 3.5 |

Debug-only: Glass Lab panel sits outside the world (no depth cue).

## Per-screen map

Material column = World. "Lift" = tonal at rest, glass while held.

### Shell (Home / Library roots)

| Element | Layer | Material | Why |
|---|---|---|---|
| Bar: Home / Library (unselected), Search circle | L2 | glass, neutral floor 0.52 | R1 pinned over scrolling page |
| Bar: selected tab | L2 | glass, primary (Home) / secondary (Library) 0.85 | R1 + R2 the layer's "you are here" |
| Mini player | L2 (L3 folded) | glass `background` 0.48 + NP ambient 0.38 + progress wash | R1, R5, R7 |
| Status-bar soft edge | L2 | ultraThin material band | system scroll edge |

### Home

| Element | Layer | Material | Why |
|---|---|---|---|
| Activities / Jump Back In cards | L1 | material + cover wash 0.72 (Gemini) | R1 content scrolls |
| Covers in cards | L1 | solid | R6 |
| Memories pill (page header) | L1 | tonal | scrolls with the header |

### Library + Search

| Element | Layer | Material | Why |
|---|---|---|---|
| Search field, ✕, chip track | L2 | glass, `surfaceContainerHighest` 0.48 (one pinned header) | R1 pinned over the list |
| Filter chips (pinned) | L2 | **glass track** (libraryFilterStyle 0) with a sliding selection | R1 — changes 51's earlier decision, see Q1 |
| Fast scroller thumb / bubble | L2 | glass | R1 floats over the grid |
| Rows, grid covers | L1 | none | R1 content |

### Album / Playlist / Artist (pushed pages)

| Element | Layer | Material | Why |
|---|---|---|---|
| Back, ⋮ circles | L2 | glass | R1 pinned (viewport) |
| Pulled-up top band | L2 | Android spectrum band with blurred lower edge (detailTopBar 0) | R1: it blurs the list under it = a cover-tinted canopy (see Q3) |
| Bar: Play split | L2 | glass, primary 0.85 | R2 primary action of the page |
| Bar: mini player | L2 | as on the roots | R5 |
| Hero cover | L1 | solid + contact shadow 0.22 / 10 / 6 | R6 |
| Track rows, hearts in rows | L1 | none | R1 |
| Artist header follow star | L2 | glass | R1 pinned |
| Scrapbook (page 2) | L1 | paper / thin material | content |

### Settings / Server / Cloud sync

| Element | Layer | Material | Why |
|---|---|---|---|
| Back circle | L2 | glass | R1 |
| Rows, toggles, buttons in rows | L1 | system grouped (tonal) | R1 |

### Now Playing compact (L3)

| Element | Layer | Material | Why |
|---|---|---|---|
| Room background | L3 room | glass `background` 0.46 over ambient (this cover 0.68 / dark 0.88 on top, neighbours 1.0 / dark 0.42) | R5, R7 |
| ⌄ | L3 chrome | glass, neutral floor 0.28 | R1 pinned to the room frame (the expanded page scrolls under it) |
| Cover | L3 fixture | solid + contact shadow | R6 |
| Rating track | L3 fixture | **lift**: tonal `surfaceVariant` 0.45 at rest (fill 0.78), glass 0.28 while dragged (fill 1.0) | R1 static slider |
| Heart | L3 fixture | tonal: liked solid tertiary, unliked tertiaryContainer (Android) | R1; keeps the cover's second colour visible (51) |
| Tabs (Lyrics / About / Note text) | L3 fixture | none | text |
| Lyrics | L1 of the room | none; run on under the PAUSE row (lyricsUnder 0.55), gone before the progress row | R1 |
| PAUSE | L3 chrome | glass primary 0.70 | R1 (lyrics under it) + R2 the room's accent |
| Next / Prev / play mode | L3 chrome | glass neutral floor 0.28 | R1 + R2 |
| Progress bar | L3 fixture | **lift**: bare wave; glass capsule only while the bar itself is scrubbed | R1 |
| Queue / Devices / Write | L3 fixture | tonal `primaryContainer` 0.45 | R1 static flow — changes 51's earlier decision, see Q2 |

### Now Playing expanded (lyrics / about / note)

| Element | Layer | Material | Why |
|---|---|---|---|
| ⌄ (pointing back) + docked cover | L3 chrome | glass / solid cover | R1 page scrolls under |
| Page tabs | L3 chrome | glass `npFloating`: selected primary 0.78, others neutral | R1 + R2 (PAUSE is away, the selected tab is the accent) |
| Lyrics action keys (Search / Translate / Select / Recenter) | L3 chrome | glass `npFloating`, neutral; an emphasized key primary | R1 floats over the lyrics list; R2 |
| Ask Gemini bar / field | L3 chrome | glass `npFloating`, primaryContainer | R1 floats over About |
| About's Settings / Retry buttons | L1 of the page | `npPills` (tonal) | R1 they scroll with About |
| Note write bar | L3 chrome | its own capsule (unchanged) | R1 |

### Sheets (L4)

| Element | Layer | Material | Why |
|---|---|---|---|
| Queue, song menu, Add to playlist | L4 | system sheet glass | system |
| Rows and quick actions inside | L1 of the sheet | tonal | R1 |
| Behind | — | recedes 0.96, dim 0.14, blur 3.5 (NP too, under Queue) | R3 |

## Glass Lab: what was added

- Preset **World** (default; `glassLab.v4` storage key so older saved values
  don't hide it) and surface **npFloating** (expanded tabs, lyric keys, Ask
  Gemini; in the other presets it copies npPills, so they look as before).
- Extras, neutral in the other presets: `heartLikedAccent` (1 = liked heart
  borrows PAUSE), `liftInFlow` + `liftRestOpacity` (`labLift`: tonal at rest,
  glass when held), `neutralShadow` (R4), `recedeScale / Dim / Blur` and
  `sheetRecedeScale / Dim / Blur` (`labRecede`, `WorldRecede` in Shells.swift),
  `pillAmbient` (R5), `coverContactShadow` (`labCoverShadow`),
  `darkAmbientStrength` / `darkNeighbourStrength` / `auraOnTop` (dark NP room).
- Glass Lab panel: "World extras" sliders.
- Screenshot aids: `-mockAlbum 1…12` (another mock cover in Now Playing),
  `-screen song-menu`.

## Questions for 51 (where a rule changes an earlier decision)

1. **Library pinned chips** — R1 makes them glass (the list scrolls under them),
   so World uses the glass track. You kept Android's tonal chips. Keep the glass
   track in World, or exempt the chips?
2. **Rating track and Queue / Devices / Write pills** — you decided they're glass.
   Under R1 they're stage fixtures (nothing moves under them), so World makes
   them tonal (the pills in the cover's `primaryContainer`), and the rating
   becomes glass only while you drag it. OK?
3. **Album pulled-up band and the bar's selected tint** — World keeps the
   spectrum band (it blurs the list under it, so it counts as a cover-tinted
   canopy) and keeps your tinted selected tab (R2 accent). The mini player next
   to it is now cover-tinted too (R5). Two coloured islands in one bar: fine?

## Not done

- Contact shadows on grid / row covers (Gemini suggested 0.18 / 6 / 3 and 0.14 /
  3 / 1.5): long lists, and those covers are L1.
- Gemini's spectrum band over `.ultraThinMaterial` and clear back circles on the
  album page (Album/* is under other work).
- Android: glass is iOS-only; no shared token change (World lives in Swift like
  Gemini; Balanced stays in `YoinTokens.Glass`).

---

## Gemini 4 Argon consult (agy, `--mode plan`, read-only)

Screenshots: `before/` (Balanced, Gemini) for round 1; World screenshots for
round 2. Prompts and full answers are kept with the artifact; summary here.

### Round 1 — rule set critique

Prompt: 51's feedback, the draft R1–R6, the draft layer model, Q1–Q4, asked for
holes, numbers and "the single thing that most creates a constructed world".

Gemini's main points (adopted unless noted):
- The shared `interacting` flag lifts the scrubber when *any* NP button is
  pressed; R1 should lift only the control being held. → adopted (World:
  `scrubberLifted`).
- Lyrics running under both transport rows collide with the progress wave;
  dissolve them within the PAUSE row. → lyricsUnder 0.55.
- R1 shouldn't flip ⌄ / back between tonal and glass mid-transition; test the
  plane (viewport-pinned chrome = glass). → adopted; ⌄ is always glass.
- Three accents in compact NP (PAUSE, rating fill, liked heart) → make rating
  and heart tonal fixtures, PAUSE the only tinted glass. → adopted for the
  rating; the heart stays Android's solid tertiary (51 wants the cover's second
  colour visible), as a tonal fixture, not glass.
- Expanded tabs: only the selected one tinted. → adopted (npFloating).
- `LabGlass` casts tint-coloured shadows; R4 needs black. → `neutralShadow`.
- R4 vs R6: separate elevation shadows from contact shadows. → R4 reworded.
- New R7 rooms vs action chrome. → adopted.
- Q1 glass track, Q2 tonal, Q3 keep the spectrum ("stained-glass canopy"), Q4
  keep the tinted selected tab. → adopted, asked of 51 above.
- Numbers: the surface table in `applyWorld` (bar floors 0.52 + 0.14 / 10 / 5,
  selected 0.85, mini player `background` 0.48, PAUSE 0.62, secondary floor
  0.28, rating 0.45 / 0.28, pills primaryContainer 0.58, room 0.46, ambient
  0.68 / 95, neighbours 1.0, recede 0.94 / 0.22 / 14 and 0.96 / 0.18 / 6).
- "The single thing": couple the world's camera depth to the morph progress
  and keep the NP ambient alive inside the folded pill, so opening is stepping
  into a room that was already there. → adopted (WorldRecede + pillAmbient).

### Round 2 — after seeing World

Prompt: what was adopted, World screenshots (light + dark, three covers, the
sheets), and three questions: the weakest spots, dark PAUSE ink and the murky
dark room, rule changes.

- The hierarchy reads as layers now: with rating, heart and pills matte, ⌄,
  PAUSE and the round buttons clearly float a plane above.
- Library: the glass track and ✕ used raw glass next to the tinted search
  field. → both go through `labSearchGlass`.
- `labRecede` blurred after masking, which smoked the corners. → blur inside
  the mask; NP recede blur 14 → 10; sheets 0.96 / 0.14 / 3.5.
- Pills at 0.60 read as four PAUSEs. → 0.45, no contrast shadow on tonal
  pills; resting rating fill 0.78 (1.0 while held). Proposed rule: tonal
  fixtures stay ≥ 20 % under the layer's accent.
- Dark PAUSE: 0.70 (× 1.12 = 0.78) switches to onPrimary ink. → adopted.
- Dark room murky green for every cover. → dark-only: this cover's aura 0.88
  drawn over the neighbours, neighbours 0.42 (`darkAmbientStrength`,
  `darkNeighbourStrength`, `auraOnTop`). Light keeps 0.68 / 1.0.
- Not adopted: raising the bar floors to 0.68 / mini player to 0.64 (51 prefers
  Gemini's colours outside NP); exempting the NP room from the dark multiplier.
- Proposed rule refinements recorded: an L2 luminance floor over artwork, the
  fixture ceiling under R2, blur inside the mask under R3, a centre-dominant
  aura under R7.
