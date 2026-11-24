# Yoin Hi-Fi prototypes

`design/hifi/` holds the app's Hi-Fi prototypes and is the only source for pictures of Yoin's UI outside the app: the website's changelog figures, docs and store art are exported from here (`export.mjs`), never drawn by hand. Keep them in step with the app: see [How to update before a release](#how-to-update-before-a-release).

These are single-file HTML prototypes of the Yoin Android app, one page per file. Android is the source of truth: the current app (Compose code on `origin/main`, plus real device screenshots) wins over `docs/design.md` wherever they disagree (owner, 2026-11-24). Sing, Music links and the album release date were drawn from their branches (`claude/sing-to-main`, `claude/open-in-yoin`, `claude/album-release-date`), all on `main` since 0.6.3 (32).

Each page has a switcher bar with three widths (Phone 412, Tablet 800 = Medium, Landscape 1280 = Expanded), Dark/Light, EN/中文 (the app's own `values-zh-rCN` words where a component wraps them in `loc()`), Demo/Real catalogue, and the page's own states.

**Catalogues.** `catalog=demo` (default) is the demo account: fictional artists, SVG covers, everything inline, so a page stays one file under Notion's 200 KiB. `catalog=real` (`src/base/catalog-real.js`) is the owner's library that yoin-site's figures have always shown, with its real covers: Weyes Blood's *Titanic Rising* (running order and lengths from NetEase / QQ Music / LRCLIB, released April 5, 2019), 伊能静《关不住》, 苏打绿, Tuno桐音, Cocteau Twins, SOPHIE, 何欣穗; each song with its own album, cover and artist. Its content follows the language: English on `lang=en` (A Lot’s Gonna Change playing), Chinese on `lang=zh` (伊能静《分裂》 playing, Chinese notes, stickies and backlinks; the album page stays *Titanic Rising*, the one album whose tracks are on record; the receipt is English in both, as in the app). The cover files are not in this repo: they are yoin-site's `public/img/music/<key>-512.webp`, read from `covers=<url>` (default: the sibling `yoin-site` checkout). Only the screens the site exports use it (Now Playing, Lyrics, Notes, Album, Scrapbook); other pages keep the demo data. The state lives in the URL, for example `dist/now-playing.html?w=landscape&theme=light&view=lyrics&sing=1`. Add `tall=1` to Home to see the whole feed.

| Page | States (URL keys) |
| --- | --- |
| `home.html` | `edit`, `bubble`, `playing`, `tall=1` |
| `now-playing.html` | `view` player / lyrics / note, `tab`, `sing`, `cover`, `mentions` (cover pill; `text` = the coloured name before 0.6.3 (32)) |
| `library.html` | `view` all / playlists / artists / albums / songs / favorites, `sort` menu, `scroller` (fast scroller shown / dragging), `search` ("Harbor", the All results page), `detail` (album open: Expanded = 600 \| 24 \| 656 list-detail) |
| `album.html` | `album` (Harbor Lights rich, Northern Field Recordings empty, Glass Orchard average), `page` overview / scrapbook, `pull` (Compact pulled-up = spectrum bar + rows; Medium = scrolled), `sticky` (sticky editor), `playing`, `date` (release date at the service's precision: header "21.4.16", the line under the last track, Released / Length on the receipt; `year` = before 0.6.3 (32)), `at=receipt` (Scrapbook scrolled to the receipt) |
| `lyrics.html` | `translate`, `state` following / scrolled away / select, `sing` (off / on; `none` = the four-key tool row before 0.6.3 (32)), `cover` |
| `notes-editor.html` | `surface` NP write bar / album sticky, `query` none / `@` / `@Ni` / `@lj` (pinyin initials) — the popup is placed by measuring the drawn page (mentionPopupOffset) |
| `memories.html` | `view` card / diary / All Memories / Rediscover (Home shelf), `ledger` timeline / cards / list, `card` |
| `settings.html` | `page` root / Music links / Subsonic account / Sync and backups, `links` (clipboard detection) |

```
build.mjs              node design/hifi/build.mjs  →  dist/<page>.html (inline + minified; fails if a page is over 200 KiB)
export.mjs             one state (or a list, --batch) → a 1.5–2× WebP / PNG and/or a static HTML frame, for the site
site/<version>.json    the export list of a version's changelog figure on yoin-site
icons/                 Yoin Symbols SVGs, vendored (MIT, icons/LICENSE); icons/drawn/ = the few the library lacks
tools/shoot.mjs        screenshots of every state into screenshots/ (headless Chromium via playwright-core)
tools/sync-icons.mjs   refresh icons/ from a yoin-symbols checkout
src/base/              shared by every page (always included, in this order)
  base.css             type stack, shape radii, switcher bar, device frame, status / gesture bars
  palette.js           SEEDS + scheme(): M3 roles from a cover seed → CSS vars (--primary, --surfaceContainer…);
                       dark primaryContainer is the bright SPEC_2025 Expressive tone; --wash keeps classic T30 / T90
  icons.js             ic(name, size); build.mjs fills ICONS from icons/*.svg + icons/drawn/*.svg, tree-shaken per page
  shapes.js            MaterialShapes paths (Bun, Circle, Ghostish, Cookie12Sided, account-avatar shapes…); shapeSvg(name)
  covers.js            demo covers as SVG <symbol>s, cover(key); ART colours; CAT demo catalogue
  proto.js             Proto.init({ title, controls, scheme(state), render(state, size), after(dev, state) }); loc(en, zh)
src/components/        one concern per pair of .js + .css
  bar                  floating bottom bar (portrait / centred / Edit Home pose) + dot dissolve
  home-cards           backdrop shapes, Activities bento, JBI cells, Recently Added, Rediscover, memory mark
  memory-bubble        the ⌄ Memories entry, memory bubble and tip capsule
  np-stage             NP background, top bar, cover + rating bar / vocal slider, heart / exit, transport, wave, hero, pills
  lyrics               text tabs, tab group, lyric lists, tool row with the Sing key / capsule, About
  notes                note lines, 0.6.4 mention pills, notes header, write bar
  groove               the Groove emblem (rings per track; album score / Avg. / Unrated mould)
  editor               writing card, editing chips, format row (@ B I H • ☑), Save pill, MentionPopup + its placement,
                       soft-keyboard placeholder
  detail               album page 1 (header, butterfly, emblem row, run-on titles, rows, spectrum bar), Play ▾ bars,
                       albumPane(), paneSplit() (Expanded list-detail)
  scrapbook            album page 2: dot paper, review clipping, stickies + dashed sticky + sticky editor, tickets,
                       notes clippings, tags, receipt, Mentioned in
  lib                  Library header / chips / sort / grid tiles / song rows / fast scroller
  memories             deck top bar, card face, diary, spread, All Memories (grid, timeline, cards, list)
  settings             service colours, account avatar / card / row, segmented rows, groups, second-level pages
src/pages/<page>.json  { "title", "components": [...] }  — what the page pulls in
src/pages/<page>.js    page entry: controls + render(); calls Proto.init at the end
src/pages/<page>.css   page layout only
```

## Edit a component
Change `src/components/<name>.{js,css}`, then run `node design/hifi/build.mjs`. Every page that lists the component picks up the change. Components are plain functions that return HTML strings and read colours only through the M3 role variables (`var(--primary)` and so on), so they work in both themes and with every cover scheme. All modules are joined into one scope, so give top-level names a component prefix (`np…`, `act…`) to avoid clashes.

## Add a page
1. Create `src/pages/<name>.json` with `{"title": "Library", "components": ["bar"]}`.
2. Create `src/pages/<name>.js`. Define your controls, write `render(state, size)` (`size.cls` is `compact`, `medium` or `expanded`, and `size.w` / `size.h` are in dp), then end the file with `Proto.init({...})`.
3. Optionally create `src/pages/<name>.css`.
4. Run `node design/hifi/build.mjs`. To screenshot the page, add its states to `SHOTS` in `tools/shoot.mjs`.

Rules: no raster images (draw covers as SVG in `covers.js`), no external requests except the Google Sans Flex stylesheet, and each page must stay under 200 KiB (the build enforces this). Use the demo catalogue (`CAT` in `covers.js`, the demo account's albums, songs, artists and lyrics) for every name and cover, so every export shows the same library. Wrap UI words in `loc('English', '中文')` with the app's own strings. A state that only exists to show how a screen looked before a release (`date=year`, `mentions=text`, `sing=none`) is labelled "Before <version>" in the switcher.

## What is approximated
- Colour: palette.js approximates MCU SchemeExpressive (SPEC_2025) from a hand-read seed per cover; tones are close, not exact. Memories uses the album palette lerps as the app does, with CSS color-mix.
- Covers, artist avatars and the keyboard are drawn placeholders (demo content only; the keyboard is not Yoin UI).
- The butterfly arms, the spectrum bands, sticky / ticket tilts and the Groove ring spacing are drawn to match screenshots, not computed from the cover (the app k-means the cover and hashes ids).
- Motion is not shown (springs, dissolves, morphs); the dot dissolve under the bar is a static halftone.
- The serif for Memory titles (YoinSerifTitle) falls back to Roboto Serif / Georgia; Google Sans Flex loads from Google Fonts when online.

## Screenshots
`node design/hifi/tools/shoot.mjs [page…]` writes `screenshots/<page>-<state>-<width>-<theme>.png` for every state in `SHOTS` (every state in `SHOTS` × 3 widths × 2 themes). It needs `playwright-core` on the module path, for example `NODE_PATH=/path/to/node_modules`. It uses a Chromium from `CHROME=…`, or Playwright's cached `chrome-headless-shell` if that isn't set. It never uses a personal browser profile.

## Export for the website
`export.mjs` renders a state in a headless Chromium of your own and writes what a page can embed. The site's changelog uses the bare screen (`--bezel none`, square corners, status and gesture bars included; the figure draws its own bezel), as 1.5× WebP, one per theme and language:

```
node design/hifi/export.mjs --page album --state "page=scrapbook&at=receipt" --frame phone --theme dark --lang zh --catalog real --out /tmp/x
node design/hifi/export.mjs --page settings --state "page=links" --bezel clean --format png      # in a plain device bezel
node design/hifi/export.mjs --page now-playing --state "sing=1" --format html                     # static HTML frame for an <iframe>
node design/hifi/export.mjs --batch design/hifi/site/0.6.3-32.json                                # a whole changelog figure
```

`--frame` phone (412 × 915) / tablet (800 × 1280) / landscape (1280 × 800); `--theme` light / dark; `--lang` en / zh; `--catalog` demo / real (the site uses real; `--covers <yoin-site>/public/img/music` if yoin-site isn't the sibling checkout, a missing cover fails the export, and the HTML frame inlines the covers); `--format` webp (default) / png / html / all; `--scale` (default 2). A key or value that isn't one of the page's controls fails the export instead of drawing the default. The HTML frame is self-contained (inline CSS, only the cover symbols it uses, a 4-line fit-to-width script; ~50 KiB) and loads nothing but the Google Sans Flex stylesheet. `--batch` takes a JSON list (`out` relative to the file, `defaults`, `themes`, `langs`, `exports: [{ name, page, state }]`) and writes `<name>-<theme>[-zh].webp`; the site's lists live in `site/`, one per version, and write straight into a sibling `yoin-site` checkout (`public/media/changelog/<version>/`). Needs `playwright-core` on `NODE_PATH` and a Chromium (`CHROME=…`, or Playwright's cached `chrome-headless-shell`).

## How to update before a release
The owner's rule (2026-11-24): the site never shows hand-drawn app UI. Every release that changes a screen:

1. **Update the prototypes of the screens that changed**, from the release branch's Compose code and device screenshots (the app wins over `docs/design.md` where they disagree). Change the component, not a copy of it; add the new state to the page's controls (and to `SHOTS` in `tools/shoot.mjs`). Where the changelog shows a before/after, keep or add a "Before <version>" state that draws the old look from the code it replaced (`git show <commit>^:…`), not from memory.
2. **Check them**: `node design/hifi/build.mjs` (size limit), `node design/hifi/tools/shoot.mjs <page>`, and look at phone / tablet / landscape, light / dark, EN / 中文 next to the device screenshots: names, covers, icons, strings (`values` and `values-zh-rCN`).
3. **Write the export list** `site/<version>.json`: the changed screens (phone unless the change is tablet-only), with a `-was` export for each before/after.
4. **Export** into the `yoin-site` worktree: `node design/hifi/export.mjs --batch design/hifi/site/<version>.json --out ../yoin-site-wt/public/media/changelog/<version>`, then point the version's figure at the screens in `public/spot.js` (`shot` / `was` / mark `box`es in % of the 412 × 915 screen; `bg` for a new part with no before) and check it in both languages and themes.
5. Commit the prototype changes and the export list in the Yoin release PR, the WebPs in the site PR.
