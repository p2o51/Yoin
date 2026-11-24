#!/usr/bin/env node
/* Exports one prototype state for the website (or anywhere else that needs a picture of Yoin's UI).
   Renders dist/<page>.html?<state> in a headless Chromium of your own (never a personal browser profile) and writes
     <out>/<name>.webp   the screen at --scale (default 2×), lossy WebP (--format png for PNG)
     <out>/<name>.html   a self-contained static page of the same frame: inline CSS + SVG, no scripts besides a
                         4-line fit-to-width, no requests except the Google Sans Flex stylesheet; for an <iframe>
   --frame picks the device: phone (412 × 915, Compact), tablet (800 × 1280, Medium), landscape (1280 × 800, Expanded).
   --bezel clean wraps the screen in a plain dark bezel on a transparent background; --bezel none (the default) is
   the bare screen, square-cornered, with its status and gesture bars, for a page that draws its own frame (the
   site's changelog does). Prototype-only notes (the keyboard's "not Yoin" label) are hidden.
   --catalog demo (default) uses the prototypes' SVG demo catalogue (src/base/covers.js CAT); --catalog real uses
   the owner's library with its real covers (src/base/catalog-real.js), the one yoin-site's figures show, read from
   --covers <dir> (default: the sibling yoin-site checkout's public/img/music). --lang picks each catalogue's
   English or Chinese demo content as well as the UI language.

   node design/hifi/export.mjs --page album --state "page=scrapbook&at=receipt" --frame phone --theme light \
        [--lang zh] [--catalog demo|real] [--covers dir] [--name album-receipt] [--out dir] [--format webp|png|html|all]
        [--scale 2] [--bezel none|clean]
   node design/hifi/export.mjs --batch design/hifi/site/0.6.3-32.json [--out dir]
        a list of exports in one go (see README, "Export for the website"): every entry × its themes × its langs, named
        <name>-<theme>[-zh].<ext>; the file's "out" is relative to the file, --out overrides it.

   Needs playwright-core (`npm i playwright-core`, anywhere on NODE_PATH) and a Chromium: CHROME=/path, or
   Playwright's cached chrome-headless-shell. Builds dist/ first. */
import { createRequire } from 'node:module';
import { existsSync, readdirSync, mkdirSync, writeFileSync, readFileSync } from 'node:fs';
import { join, dirname, resolve } from 'node:path';
import { homedir } from 'node:os';
import { spawnSync } from 'node:child_process';
import { fileURLToPath, pathToFileURL } from 'node:url';

const ROOT = dirname(fileURLToPath(import.meta.url));
const SIZES = { phone: [412, 915], tablet: [800, 1280], landscape: [1280, 800] };
const FONT = 'https://fonts.googleapis.com/css2?family=Google+Sans+Flex:opsz,wdth,wght,ROND@6..144,25..151,100..1000,0..100&display=swap';

const args = {};
for (let i = 2; i < process.argv.length; i++) {
  const a = process.argv[i];
  if (!a.startsWith('--')) continue;
  const [k, v] = a.includes('=') ? [a.slice(2, a.indexOf('=')), a.slice(a.indexOf('=') + 1)] : [a.slice(2), process.argv[i + 1] && !process.argv[i + 1].startsWith('--') ? process.argv[++i] : '1'];
  args[k] = v;
}
const USAGE = 'usage: node export.mjs --page <name> [--state "k=v&…"] [--frame phone|tablet|landscape] [--theme light|dark] [--lang en|zh]\n' +
  '                     [--name file] [--out dir] [--format webp|png|html|all] [--scale 2] [--bezel none|clean]\n' +
  '       node export.mjs --batch <list.json> [--out dir]';
const jobs = [];
if (args.batch) {
  const file = resolve(args.batch), list = JSON.parse(readFileSync(file, 'utf8'));
  const outDir = resolve(args.out || resolve(dirname(file), list.out || '.'));
  for (const e of list.exports) {
    for (const theme of e.themes || list.themes || ['light']) for (const lang of e.langs || list.langs || ['en']) {
      jobs.push({ ...list.defaults, ...e, theme, lang, out: outDir, name: `${e.name}-${theme}${lang === 'en' ? '' : '-' + lang}` });
    }
  }
} else {
  jobs.push({ ...args, out: args.out || join(ROOT, 'exports') });
}
for (const j of jobs) {
  j.catalog ||= 'demo';
  if (!['demo', 'real'].includes(j.catalog)) { console.error(USAGE); process.exit(2); }
  if (j.catalog === 'real') {
    j.covers = resolve(j.covers || args.covers || join(ROOT, '../../../yoin-site/public/img/music'));
    if (!existsSync(join(j.covers, 'weyes-512.webp'))) { console.error(`--catalog real needs yoin-site's covers: no weyes-512.webp in ${j.covers} (pass --covers <yoin-site>/public/img/music)`); process.exit(2); }
  }
  j.frame ||= 'phone'; j.theme ||= 'light'; j.lang ||= 'en'; j.format ||= 'webp'; j.scale = +(j.scale || 2); j.bezel ||= 'none';
  j.state = (j.state || '').replace(/^\?/, '');
  if (!j.page || !SIZES[j.frame] || !['light', 'dark'].includes(j.theme) || !['en', 'zh'].includes(j.lang) || !['none', 'clean'].includes(j.bezel)) { console.error(USAGE); process.exit(2); }
  if (!existsSync(join(ROOT, 'src/pages', j.page + '.json'))) { console.error(`no page "${j.page}" in src/pages`); process.exit(2); }
  j.name ||= [j.page, j.state.replace(/[&=]/g, '-'), j.frame, j.theme, j.lang === 'en' ? '' : j.lang].filter(Boolean).join('-');
  j.out = resolve(j.out);
  mkdirSync(j.out, { recursive: true });
}

// 1. build (fast, no dependencies; fails if a page is over its size limit)
const b = spawnSync(process.execPath, [join(ROOT, 'build.mjs')], { encoding: 'utf8' });
if (b.status !== 0) { process.stderr.write(b.stdout + b.stderr); process.exit(1); }

// 2. render
const require = createRequire(import.meta.url);
let chromium;
try { ({ chromium } = require('playwright-core')); } catch { console.error('needs playwright-core: npm i playwright-core (on NODE_PATH)'); process.exit(1); }
function chromePath() {
  if (process.env.CHROME) return process.env.CHROME;
  const base = join(homedir(), 'Library/Caches/ms-playwright');
  if (!existsSync(base)) return undefined;
  const d = readdirSync(base).filter((x) => x.startsWith('chromium_headless_shell-')).sort().pop();
  if (!d) return undefined;
  const sub = readdirSync(join(base, d)).find((x) => x.startsWith('chrome-headless-shell'));
  return join(base, d, sub, 'chrome-headless-shell');
}
// the export look: no switcher bar or caption, the device at 1:1 in the corner; bezel none = a square screen
const exportCss = (W, H, pad, frame, bezel) => `.pbar,.dev-cap,.kb small{display:none!important}html,body{background:transparent!important}
.stage{padding:0!important;display:block!important}.device{transform:none!important}` +
  (bezel === 'clean'
    ? `.dev-wrap{box-sizing:content-box;width:${W}px!important;height:${H}px!important;padding:${pad}px;border-radius:${frame === 'phone' ? 46 : 40}px;background:#18181b;box-shadow:inset 0 0 0 1.5px #3a3a40}
       .device{left:${pad}px!important;top:${pad}px!important;box-shadow:none!important;border-radius:${frame === 'phone' ? 34 : 28}px!important}`
    : '.device{border-radius:0!important;box-shadow:none!important}');

const browser = await chromium.launch({ executablePath: chromePath() });
let failed = false;
try {
  for (const j of jobs) {
    try { await exportOne(j); } catch (e) { failed = true; console.error(`FAIL ${j.name}: ${e.message}`); }
  }
} finally {
  await browser.close();
}
if (failed) process.exit(1);

async function exportOne({ page, state, frame, theme, lang, catalog, covers, name, out, format, scale, bezel }) {
  const [W, H] = SIZES[frame], pad = bezel === 'clean' ? (frame === 'phone' ? 12 : 16) : 0;
  const EXPORT_CSS = exportCss(W, H, pad, frame, bezel);
  const ctx = await browser.newContext({ viewport: { width: W + 2 * pad, height: H + 2 * pad }, deviceScaleFactor: scale });
  const p = await ctx.newPage();
  const errors = [];
  p.on('pageerror', (e) => errors.push(e.message));
  const real = catalog === 'real' ? `&catalog=real&covers=${encodeURIComponent(pathToFileURL(covers).href + '/')}` : '';
  const url = pathToFileURL(join(ROOT, 'dist', page + '.html')).href + `?w=${frame}&theme=${theme}&lang=${lang}${real}${state ? '&' + state : ''}`;
  await p.goto(url, { waitUntil: 'networkidle' }).catch(() => p.goto(url));
  await p.addStyleTag({ content: EXPORT_CSS });
  await p.evaluate(() => document.fonts.ready);
  await p.evaluate(() => Promise.all([...document.images].map((i) => i.decode().catch(() => {}))));
  const broken = await p.evaluate(() => [...document.images].filter((i) => !i.naturalWidth).map((i) => i.getAttribute('src')));
  if (broken.length) errors.push('missing covers: ' + [...new Set(broken)].join(', '));
  await p.evaluate(() => window.dispatchEvent(new Event('resize')));
  await p.waitForTimeout(300);
  if (errors.length) { await ctx.close(); throw new Error('page error: ' + errors.join('; ')); }
  // every k=v must be a control of the page (the switcher bar lists them); an unknown one would silently draw the default
  const bad = await p.evaluate((kvs) => kvs.filter(([k, v]) => k !== 'tall' && !document.querySelector(`.pgrp[data-k="${k}"] button[data-v="${v}"]`)).map((x) => x.join('=')),
    state.split('&').filter(Boolean).map((kv) => kv.split('=')));
  if (bad.length) { await ctx.close(); throw new Error(`not a state of ${page}: ${bad.join(', ')}`); }
  const target = p.locator(bezel === 'clean' ? '.dev-wrap' : '.device');
  const written = [];
  if (format === 'png' || format === 'webp' || format === 'all') {
    const png = await target.screenshot({ omitBackground: true, animations: 'disabled' });
    if (format === 'png' || format === 'all') { writeFileSync(join(out, name + '.png'), png); written.push(name + '.png'); }
    if (format === 'webp' || format === 'all') {
      // encode in the same Chromium: no cwebp needed
      const enc = await ctx.newPage();
      const b64 = await enc.evaluate(async (src) => {
        const img = new Image(); img.src = src; await img.decode();
        const c = document.createElement('canvas'); c.width = img.naturalWidth; c.height = img.naturalHeight;
        c.getContext('2d').drawImage(img, 0, 0);
        return c.toDataURL('image/webp', 0.86).split(',')[1];
      }, 'data:image/png;base64,' + png.toString('base64'));
      writeFileSync(join(out, name + '.webp'), Buffer.from(b64, 'base64'));
      written.push(name + '.webp');
      await enc.close();
    }
  }
  if (format === 'html' || format === 'all') {
    const html = await p.evaluate(({ W, H, FONT, EXPORT_CSS, lang }) => {
      const dev = document.querySelector('.device');
      const used = new Set([...dev.querySelectorAll('use')].map((u) => (u.getAttribute('href') || u.getAttribute('xlink:href') || '').slice(1)));
      // the cover sprite, only the symbols this frame uses (and the gradients they point at)
      const sprite = document.querySelector('body > svg');
      let spriteHtml = '';
      if (sprite) {
        const s = sprite.cloneNode(true);
        for (const sym of [...s.querySelectorAll('symbol')]) if (!used.has(sym.id)) sym.remove();
        spriteHtml = s.outerHTML;
      }
      const css = [...document.querySelectorAll('style')].map((x) => x.textContent).join('\n');
      const fit = `<script>(()=>{const d=document.querySelector('.dev-wrap'),f=()=>{const k=Math.min(1,innerWidth/d.offsetWidth);d.style.transform='scale('+k+')';};addEventListener('resize',f);f();})()</script>`;
      return `<!doctype html><html lang="${lang === 'zh' ? 'zh-Hans' : 'en'}"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">` +
        `<title>Yoin</title><link rel="stylesheet" href="${FONT}"><style>${css}\n${EXPORT_CSS}\n.dev-wrap{transform-origin:0 0}</style></head>` +
        `<body class="${document.body.className}">${spriteHtml}<div class="stage"><div class="dev-wrap" style="width:${W}px;height:${H}px">${dev.outerHTML}</div></div>${fit}</body></html>\n`;
    }, { W, H, FONT, EXPORT_CSS, lang });
    // the real covers are files next to yoin-site: inline them, so the frame needs nothing but the font
    const inlined = html.replace(/src="(file:[^"]+?\.webp)"/g, (_, u) => `src="data:image/webp;base64,${readFileSync(fileURLToPath(u)).toString('base64')}"`);
    writeFileSync(join(out, name + '.html'), inlined);
    written.push(name + '.html');
  }
  for (const f of written) console.log(`${join(out, f)}  ${(readFileSync(join(out, f)).length / 1024).toFixed(1)} KiB`);
  await ctx.close();
}
