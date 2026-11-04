# iOS QA log

Append-only. One row per finding. Script and method: `docs/qa-script.md`. Status values: open / fixing / fixed (commit) / wontfix / question.
Evidence paths starting with `qa-audit/` are under the 11-04 audit scratchpad (`/private/tmp/claude-501/-Users-gpo-Developer-Yoin/f43a50d2-67e0-4cc5-98e1-185c0f1e0f7c/scratchpad/qa-audit/`). Rerun `scripts/qa-record.sh` to regenerate them.

## 2026-11-04 audit (build cceca3e74, before the fix agent's working-tree edits)

### Clear defects

| Pri | Screen | Step (launch args) | What's wrong | Evidence | Status |
|---|---|---|---|---|---|
| P0 | NP ⇄ shell | `-screen home -npAutoCycle YES -animationSpeed 0.5`; `-npScriptedDrag YES` | Close and drag-dismiss: the NP card shrinks over solid black for 4–6 frames, then the blurred shell pops in. Open: the shell vanishes to black for the last ~10 frames (R3) | qa-audit/ev-np-close-frames.png, ev-np-drag-dismiss-frames.png, ev-np-open-frames.png | open |
| P0 | NP ⇄ shell | same | Open ends with a one-frame jump from ~0.95 scale (black rim) to full-bleed. Close: ~220 ms render stall, then in one frame the shell scale, the pill height (tall slab → bar), and the status-bar band (`if path.isEmpty && !npExpanded`, no transition) all change | qa-audit/ev-close-blank-slab.png (4→5), ev-np-close-frames.png | open |
| P1 | NP morph | `-npAutoCycle YES` | The page fades out at rise < 0.3–0.75, so mid-fold it is a blank frosted slab (R5) | qa-audit/ev-close-blank-slab.png | open (see question 4) |
| P1 | Mini player | `-npAutoCycle YES` (light) | The pill lands light, then flips to dark bar glass a few frames after the motion ends | qa-audit/ev-np-close-frames.png row 2 | open |
| P1 | Search | `-screen search-longpress` | The focused field appears mid-screen (y≈405), hops under the chips, then reaches the header; chip rows double-exposed; avatar/✕ overlap | qa-audit/ev-search-ghost-field.png | open |
| P1 | NP scrub bubble | code | `WavyProgress.swift:364` uses `.subheadline` (system font), not GSF | code | open |
| P1 | Settings | `-screen settings`, open Playback quality / AI features | SF Symbols `chevron.down`, `checkmark`, `chevron.up.chevron.down` + `.font(.system)` (ServerSettingsView:154, GeminiSettingsSection:75/119/132) | code | open |
| P1 | Queue sheet | `-screen np-queue` | Empty queue = header plus an empty sheet; NP controls read through the sheet glass (World `sheetPresenterDim` 0); grey band at the bottom edge | qa-audit/ev-queue-empty.png | open |
| P1 | NP rating | srv: `-serverPlay album:al-field -serverOpenNowPlaying YES`, dark | Unrated track almost invisible, no label | qa-audit/ev-dark-np-sheets.png (6th) | open |
| P1 | iPad NP | iPad Pro 13 portrait, `-screen np -npTab about` | Phone column stretched: ~200 pt void between tabs and transport; empty form-sheet queue | qa-audit/ev-ipad-np.png | open |
| P2 | NP focus | `-screen np -npFocusDemo YES` | Active lyric drawn twice while the focus transition runs | qa-audit/ev-np-focus-in-frames.png | open |
| P2 | NP compact | `-screen np` | Lyric line still visible between PAUSE row and progress row | run1/light/np.png | open |
| P2 | Album pull-up | `-screen album-mid` | ← glyph fades out on its glass circle around p≈0.4 | qa-audit/ev-album-pullup-mid.png | open |
| P2 | Library scroller | `-screen library-scroller` | Section rail glass over the third column; titles show through under the labels | qa-audit/ev-library-scroller-search.png | open |
| P2 | Library → Search | `-screen library` vs `-screen search` | Field height and y differ (56 @ 93 vs 48 @ 86) | same | open |
| P2 | Settings | `-screen settings` dark | Disabled Sign in nearly invisible; empty fields lack placeholders | qa-audit/ev-dark-detail-settings.png | open |
| P2 | Skeletons | code | `LoadStates.swift:39` easeInOut loop (comment says effects spring) | code | open |
| P2 | Landing | `-screen landing` | "Developer Dashboard (placeholder)" visible | run1/light/landing.png | open |
| P2 | Library Favorites | `-screen library-favorites` | Stand-in "Random mix" of all songs | qa-audit/ev-library-scroller-search.png | open |
| P2 | Sheets | `-screen song-menu` | Grey band at the sheet bottom; Share tile shows Home through it | run1/z-menu.png | open |

### Design questions for 51

| # | Question | Evidence | Status |
|---|---|---|---|
| Q1 | The bar turns to dark glass in light mode over covers, and the selected Library tab reads as two different greens. Keep the system adaptivity or pin it light? | qa-audit/ev-bar-over-covers-light.png | question |
| Q2 | Library accent is olive (cover secondary) while a violet cover plays. Intended? | qa-audit/ev-library-views-light.png | question |
| Q3 | Unrated on NP: nothing (iOS now), "–", or "Unrated" like the album stamp? Android always shows a number | qa-audit/ev-real-data-np-album-light.png | question |
| Q4 | Mid-morph: may the NP page fade out before the card reaches pill size (blank slab ~⅓ of the fold)? | qa-audit/ev-close-blank-slab.png | question |
| Q5 | Behind a receding layer: black ("the room in front", HEAD) or the app surface (fix WIP)? | qa-audit/ev-dark-np-sheets.png | question |
| Q6 | PAUSE ink in dark: white (fidelity doc) or the current dark ink on lavender? | qa-audit/ev-dark-np-sheets.png | question |
| Q7 | Detail page with nothing playing: Play split alone, centred, no Home affordance. OK? | run1/light/srv-album.png | question |

## 2026-11-04 fix agent (iPhone 17 Pro sim, demo Subsonic, light + dark)

Clips and frame strips: `/private/tmp/claude-501/-Users-gpo-Developer-Yoin/f43a50d2-67e0-4cc5-98e1-185c0f1e0f7c/scratchpad/fix4/`.

| Screen | Step | What's wrong | Fix commit |
|---|---|---|---|
| Shell → song sheet | long-press the mini player | One frame snaps the shell to full recede (dim + scale) ~6 frames into the sheet's rise, with black at the screen edges/corners (`PhoneShell` background black under World; recede on its own spring keyed on `sheet`). Before: before_menu_light.mp4 f283 | 95bb4dcbe: backdrop = app surface, dim over plane and rim; depth read per frame off the sheet's presentation layer (`SheetRise`), incl. drag + dismiss. after_menu_light/dark.mp4 |
| Song sheet | long-press the mini player | Opening looked like two moves: presented at a 420 pt guess, then re-sized to the measured height mid-rise | 95bb4dcbe: detent from an exact height estimate (382 = measured 382) |
| NP transport | tap PAUSE / PLAY | No morph: word swapped, pill width jumped, NEXT jumped; PLAY cross-faded over PAUSE | 95bb4dcbe: Android PlaybackControls port (fast spatial word box + 16⇄24 padding, 0.97/1.06/1.10 stretch, 15 % group press), keyed on state |
| NP progress row | tap PAUSE then PLAY | Times and bar were the scrubber glass's content: the whole row vanished for 1 frame on pause and ~50 frames (0.8 s) on resume. before_playpause_light.mp4 f1688–1737 | 95bb4dcbe: glass is a layer behind the row; row drawn on top. 0 gone frames light + dark |
| NP background | any queue | Previous / next edge colours were mock albums [11] / [1] | 95bb4dcbe: real play-order neighbours, repeat-all wrap, own-scheme fallback, Yoin.effects on change (`NowPlayingNeighboursTests`) |
| NP ⇄ shell | open / close / drag | Black behind the card and black corners | 95bb4dcbe: surface backdrop, 0 dark-corner frames in np_openclose_light.mp4. STILL OPEN: the shell itself is not drawn for the first ~3 frames of a close (also with the recede modifier removed, so not the recede), then a ~80 ms stall; needs Instruments on device |
| NP fold | close | Page faded out from rise 0.75: empty frosted slab for ~⅓ of the fold | 95bb4dcbe: page stays until rise 0.2 (≈ pill size) |
| Status band | close NP | Band inserted in one frame at the end of the close | 95bb4dcbe: opacity follows the morph progress |
| Queue sheet | `-screen np-queue` | Blank under the header when empty; grey band at the bottom edge | 95bb4dcbe: "Queue is empty" / "Nothing up next" label; bottom scroll-edge effect hidden (song sheet too). NP recedes (sheet dim 0.14) behind the Queue via SheetRise |
| Scrub bubble | scrub the bar | System font | 95bb4dcbe: labelLarge GSF, tabular |
| NP rating, dark | unrated song (al-field) | Empty track nearly invisible | 95bb4dcbe: tonal fill + outline while unrated, no number |
| Mini player | after a close | Pill colour flips a few frames after landing (morph glass vs bar's adaptive glass) | open (system glass adaptation; 51 keeps adaptive glass) |
| NP open | end of open | Card holds ~0.95 then jumps to full | open (no black rim any more; the jump itself not fixed) |
| NP lyrics | focus / compact | Active line drawn twice; line between PAUSE row and progress row | open (not touched) |

## 2026-11-04 QA fix agent, outside Now Playing (iPhone 17 sim, iOS 26.5, light + dark; demo Subsonic for Favorites)

Clips and stills: `/private/tmp/claude-501/-Users-gpo-Developer-Yoin/f43a50d2-67e0-4cc5-98e1-185c0f1e0f7c/scratchpad/qafix/` (`run9/` = search clips with frame sheets, `final/` = stills, `srv-fav.png`). New QA cases: `search-from-bar` (`-screen home-search`), `search-bar-close` (`-screen home-search -searchAutoClose YES`).

| Screen | Step | What's wrong | Evidence | Status / fix commit |
|---|---|---|---|---|
| Search | `-screen search-longpress`, `-screen home-search` | A second focused field flew in mid-screen, then went below the chips, then reached the header; chip rows double-exposed; avatar and ✕ overlapped. Causes: (1) the matched source was the bar's circle, removed but still a source through its fade while the keyboard and the slide-away moved the bar; (2) focusing at once stalled the main thread (first keyboard load) mid-morph; (3) the whole overlay cross-faded over the page | qa-audit/ev-search-ghost-field.png → run9/light/search-from-bar-sheet.png, run9/dark/search-longpress-sheet.png | fixed 3827df9e8 + 65d277c4d (ShellBar) + Shells transition (went in with 95bb4dcbe). The circle stand-in morphs into the field in one path, keyboard after it lands; from Library the pill is the field. Close folds back. Residual: one ~200 ms main-thread hitch mid-flight in the sim (field holds, then catches up on the same path) |
| Search / Library | `-screen library` vs `-screen search` | Field 56 @ y 93 vs 48 @ y 86 | final/s3.png | fixed 3827df9e8: both 56 (Android InputFieldHeight), 4 top, icon 24, GSF 16: same rect |
| Settings | `-screen settings`, Playback quality / AI features | SF Symbols + system font | code | fixed 3827df9e8: Yoin `chevron_down`, `unfold_more` (new, gen_sfsymbols.py), `check`. App-wide grep: no `systemName`/`systemImage` left; remaining `.system(` fonts are YoinIcon sizing, the monospace receipt/copy box (Android uses FontFamily.Monospace there too), the serif Memory title, and NP's WavyProgress (other agent) |
| Settings | `-screen settings` dark | Disabled Sign in near invisible; empty fields blank | final/s1.png | fixed 3827df9e8: MD3 disabled (onSurface 38 % on 12 %), also Save key; Android placeholders https://music.example.com / Your username / Password |
| Library scroller | `-screen library-scroller` | Rail glass over the third column; titles read through | final/s2.png | fixed 3827df9e8: tick column surfaceContainerHigh, bubble primaryContainer, both opaque (Android YoinFastScroller) |
| Album pull-up | `-screen album-mid` | ← fades out around p≈0.4 | final/s3.png | fixed 3827df9e8: one ← over both header twins; glass tint follows the pull, ink flips to white on the effects spring past half |
| Skeletons | code | easeInOut loop | code | fixed 3827df9e8: repeating critically damped effects spring, still under Reduce Motion |
| Landing | `-screen landing` | "Developer Dashboard (placeholder)" | final/s1.png | fixed 3827df9e8: the guide loads developer.spotify.com/dashboard (Android SpotifyDashboardUrl) |
| Library Favorites | `-screen library-favorites` (+ demo server) | "Random mix" stand-in | final/s2.png, srv-fav.png | fixed 3827df9e8: starred songs (getStarred2; mock: starred tracks) under "Songs", hearts taken off drop out, re-read after any heart change, "No favorites yet" panel. Android also lists starred artists and albums: not done |
| iPad NP | iPad Pro 13 portrait | Phone column stretched, empty form-sheet queue | qa-audit/ev-ipad-np.png | open, skipped here (NP files owned by the NP fix agent) |
| Shell (all pages) | any `withAnimation` in the shell under World | `WorldRecede` (95bb4dcbe) ends with `.transaction { $0.animation = nil }` on the whole shell layer: the bar's slide-away, Library chips and other shell animations land in one frame (seen while verifying Search: the bar vanished in 1 frame) | run2/light/search-from-bar.mp4 | open, for the NP/shell agent (verified Search in a worktree without it) |

## 2026-11-05 Codex G4 fix agent (Album / Artist), iPhone 17 sim (iOS 26.5), light + dark, demo Subsonic copy on :5000

Verdicts: `docs/codex-verdicts/G4.md`. Stills: `/private/tmp/claude-501/-Users-gpo-Developer-Yoin/f43a50d2-67e0-4cc5-98e1-185c0f1e0f7c/scratchpad/g4qa/`.

| Screen | Step | What was wrong | Evidence | Status / fix commit |
|---|---|---|---|---|
| Album list | `-serverRoute album:al-harbor -screen album-list` | Repeated track ids collided; no disc headings / performer; no note marker | harbor-list-light.png | fixed 620e376bf (A04, A16, A17) |
| Album hero / scrapbook | `-serverRoute album:al-harbor` (+ `-screen album-scrapbook`, `-screen album-rate`) | Synced review missing on page 1; pen/emblem dead; title read-only | harbor-review-dark.png, harbor-scrap-light.png, harbor-rate-dark.png | fixed 620e376bf (A05, A19 part) |
| Artist | `-serverRoute artist:ar-kiri`, server slow / failing | Empty page + live star offline; Most Played from server counts | artist-loading-light.png, artist-failed-dark.png, artist-dark.png | fixed 620e376bf (A06, A18) |
| Playlist | `-serverRoute playlist:pl-drive`, server slow / failing | Loading looked like an empty playlist | playlist-loading-dark.png, playlist-failed-light.png | fixed 620e376bf (A42) |
| Album (Apple Music) | code | Heart looked live, did nothing | code | fixed 620e376bf (A15): + / ✓ library button; not run (no MusicKit in sim) |

## 2026-11-05 Codex G1 fix agent (Now Playing + animation), iPhone 17 Pro sim (iOS 26.5), demo Subsonic :4997

Verdicts: `docs/codex-verdicts/G1.md`. Clips / strips: `/private/tmp/claude-501/-Users-gpo-Developer-Yoin/f43a50d2-67e0-4cc5-98e1-185c0f1e0f7c/scratchpad/codex-g1/`.

| Screen | Step | What was wrong | Evidence | Status / fix commit |
|---|---|---|---|---|
| NP morph | `-screen np-morph-close`, tap the folding card | Reversal restarted the spring at rest (speed jump) | b01-reverse.mp4 | fixed 3b7c806c3 (B01) |
| NP lyrics | demo server, Next | Song change replaced lyrics in one frame; loading slot blank | b06-next.mp4 / -strip.png | fixed 3b7c806c3 (B06, A28) |
| NP focus | `-screen np -npFocusDemo YES -animationSpeed 0.25` | Active line drawn twice at two anchors | b07-focus-in.png | fixed 3b7c806c3 (B07) |
| NP lyrics | long lines | Truncated to one line | a37-idle-strip.png | fixed 3b7c806c3 (A27, B08) |
| NP sheets / iPad | lyric search / edit / share; iPad `-screen np-queue` | No World recede under them; none on iPad | b05-ipad-np-queue.png | fixed 3b7c806c3 (B04), 7146fc589 (B05) |
| NP expanded Lyrics | `-npTab lyrics-full`, wait 3 s | Tools never stepped away | a37-idle.mp4 | fixed e5e4abe41 (A37) |
| Mini player | auto-advance with NP closed | Old song replaced in one frame | a32-pill.mp4 | fixed e5e4abe41 (A32) |
| NP transport | long-press play mode | No split, repeat off unreachable | a31-split.mp4 | fixed f90e5ffa3 (A31) |
| NP | skip while writing a note / editing lyrics / holding the rating | Note, lyrics or rating went to the new song | code | fixed 3b7c806c3 (C12–C14) |
