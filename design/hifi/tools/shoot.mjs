#!/usr/bin/env node
// Screenshots of dist/*.html in a headless Chromium of your own (never a personal browser profile).
// Needs playwright-core (`npm i playwright-core` anywhere on NODE_PATH) and a Chromium: set CHROME=/path, or it
// uses Playwright's cached chrome-headless-shell. Usage: node tools/shoot.mjs [page ...]
//   Writes screenshots/<page>-<state>-<width>-<theme>.png for every state in SHOTS.
import { createRequire } from 'node:module';
import { existsSync, readdirSync, mkdirSync } from 'node:fs';
import { join, dirname } from 'node:path';
import { homedir } from 'node:os';
import { fileURLToPath, pathToFileURL } from 'node:url';
const require = createRequire(import.meta.url);
const { chromium } = require('playwright-core');
const ROOT = join(dirname(fileURLToPath(import.meta.url)), '..');
const OUT = join(ROOT, 'screenshots');
const WIDTHS = { phone: [412, 915], tablet: [800, 1280], landscape: [1280, 800] };
// page → [state name, extra query]; every state is shot at the three widths × dark / light
const SHOTS = {
  home: [['default', ''], ['edit', 'edit=1'], ['bubble', 'bubble=memory'], ['tip-idle', 'bubble=tip&playing=none'], ['full-feed', 'tall=1']],
  'now-playing': [['player', ''], ['player-sing', 'sing=1'], ['lyrics', 'view=lyrics'], ['lyrics-sing', 'view=lyrics&sing=1'], ['note', 'view=note'], ['yehang-note-tab', 'cover=yehang&tab=note'], ['note-before-pills', 'view=note&mentions=text']],
  library: [['all', ''], ['sort-open', 'sort=open'], ['scroller-held', 'scroller=held'], ['songs', 'view=songs'], ['playlists', 'view=playlists'], ['favorites', 'view=favorites'], ['search', 'search=on'], ['album-open', 'detail=harbor']],
  album: [['overview', ''], ['overview-pulled', 'pull=up'], ['scrapbook', 'page=scrapbook'], ['scrapbook-before-about', 'page=scrapbook&about=was'], ['scrapbook-sticky', 'page=scrapbook&sticky=edit'], ['empty-overview', 'album=north&playing=no'], ['empty-scrapbook', 'album=north&page=scrapbook'], ['glass-average', 'album=glass'], ['receipt', 'page=scrapbook&at=receipt'], ['overview-pulled-year-only', 'pull=up&date=year']],
  lyrics: [['default', ''], ['translation', 'translate=1'], ['away-sing', 'state=away&sing=1'], ['select', 'state=select'], ['yehang-translation', 'cover=yehang&translate=1'], ['before-sing', 'sing=none']],
  'notes-editor': [['at-ni', ''], ['at-empty', 'query=empty'], ['at-pinyin', 'query=lj'], ['no-popup', 'query=off'], ['sticky', 'surface=sticky&query=off'], ['sticky-at', 'surface=sticky&query=empty']],
  memories: [['card', ''], ['card-unrated', 'card=2'], ['diary', 'view=diary'], ['ledger-timeline', 'view=ledger'], ['ledger-cards', 'view=ledger&ledger=cards'], ['ledger-list', 'view=ledger&ledger=list'], ['rediscover', 'view=rediscover']],
  settings: [['root', ''], ['labs', 'page=labs'], ['labs-before', 'page=labs&labs=was'], ['tensor', 'page=tensor'], ['music-links', 'page=links'], ['music-links-off', 'page=links&links=off'], ['account', 'page=account'], ['sync', 'page=sync']],
  'share-cards': [['sheet-about', 'kind=sheet&tab=about'], ['sheet-note', 'kind=sheet&tab=note&v=B'], ['sheet-lyrics-poster', 'kind=sheet&tab=lyrics&v=B']],
};
function chromePath() {
  if (process.env.CHROME) return process.env.CHROME;
  const base = join(homedir(), 'Library/Caches/ms-playwright');
  if (!existsSync(base)) return undefined;
  const d = readdirSync(base).filter((x) => x.startsWith('chromium_headless_shell-')).sort().pop();
  if (!d) return undefined;
  const sub = readdirSync(join(base, d)).find((x) => x.startsWith('chrome-headless-shell'));
  return join(base, d, sub, 'chrome-headless-shell');
}
const pages = process.argv.slice(2).length ? process.argv.slice(2) : Object.keys(SHOTS);
mkdirSync(OUT, { recursive: true });
const browser = await chromium.launch({ executablePath: chromePath() });
for (const p of pages) {
  for (const [state, q] of SHOTS[p] || [['default', '']]) {
    for (const [w, [W, H]] of Object.entries(WIDTHS)) {
      for (const theme of ['dark', 'light']) {
        const ctx = await browser.newContext({ viewport: { width: W + 80, height: q.includes('tall=1') ? 3200 : H + 200 }, deviceScaleFactor: w === 'phone' ? 2 : 1.5 });
        const page = await ctx.newPage();
        const url = pathToFileURL(join(ROOT, 'dist', p + '.html')).href + `?w=${w}&theme=${theme}${q ? '&' + q : ''}`;
        await page.goto(url, { waitUntil: 'networkidle' }).catch(() => page.goto(url));
        await page.addStyleTag({ content: '.pbar{position:relative!important}' });
        await page.evaluate(() => document.fonts.ready);
        await page.waitForTimeout(250);
        const file = join(OUT, `${p}-${state}-${w}-${theme}.png`);
        await page.locator('.device').screenshot({ path: file });
        console.log(file.replace(ROOT + '/', ''));
        await ctx.close();
      }
    }
  }
}
await browser.close();
