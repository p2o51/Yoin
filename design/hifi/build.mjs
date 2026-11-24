#!/usr/bin/env node
// Builds every page in src/pages/<name>.json into dist/<name>.html: one self-contained file (inline CSS + JS,
// SVG covers, no images), minified, ≤ 200 KiB (Notion's HTML-embed limit). Zero dependencies: `node build.mjs`.
// A page = src/pages/<name>.{json,css,js}; the json lists the components it uses (src/components/<c>.{css,js}).
// Base modules (src/base) are always included, in BASE order. Icons come from the vendored SVGs in icons/ (Yoin Symbols)
// and icons/drawn/, and are tree-shaken to the names the page uses. Run from anywhere: `node design/hifi/build.mjs`.
import { readFileSync, writeFileSync, existsSync, readdirSync, mkdirSync } from 'node:fs';
import { join, dirname } from 'node:path';
import { fileURLToPath } from 'node:url';

const ROOT = dirname(fileURLToPath(import.meta.url));
const SRC = join(ROOT, 'src'), DIST = join(ROOT, 'dist');
const LIMIT = 200 * 1024;
const BASE = ['base/base', 'base/palette', 'base/icons', 'base/shapes', 'base/covers', 'base/catalog-real', 'base/proto'];
const FONT = 'https://fonts.googleapis.com/css2?family=Google+Sans+Flex:opsz,wdth,wght,ROND@6..144,25..151,100..1000,0..100&display=swap';

const read = (p) => (existsSync(p) ? readFileSync(p, 'utf8') : '');
const minCss = (s) => s.replace(/\/\*[\s\S]*?\*\//g, '').replace(/\s+/g, ' ').replace(/\s*([{};,>])\s*/g, "$1").replace(/:\s+/g, ":").replace(/;}/g, '}').trim();
// Conservative: block comments, whole-line // comments, indentation and blank lines. (Sources avoid "/*" in strings.)
const minJs = (s) => s.replace(/\/\*[\s\S]*?\*\//g, '').split('\n').map((l) => l.trim()).filter((l) => l && !l.startsWith('//')).join('\n');

// icons/<name>.svg → { name: [svg body without ids, stroke width] }
function loadIcons() {
  const all = {};
  for (const dir of [join(ROOT, 'icons'), join(ROOT, 'icons', 'drawn')]) {
    for (const f of readdirSync(dir).filter((x) => x.endsWith('.svg'))) {
      const svg = readFileSync(join(dir, f), 'utf8').trim();
      const body = svg.replace(/^<svg[^>]*>/, '').replace(/<\/svg>$/, '').replace(/ id="[^"]*"/g, '');
      all[f.slice(0, -4)] = [body, +((svg.match(/^<svg[^>]*stroke-width="([\d.]+)"/) || [0, 1.5])[1])];
    }
  }
  return all;
}
const ALL_ICONS = loadIcons();

function treeShakeIcons(iconsSrc, pageJs) {
  const m = iconsSrc.match(/const ICONS=(\{.*?\});\n/s);
  if (!m) throw new Error('src/base/icons.js must declare `const ICONS={};` on its own line');
  const all = ALL_ICONS, used = {};
  for (const k of Object.keys(all)) if (new RegExp(`['"\`]${k}['"\`]`).test(pageJs)) used[k] = all[k];
  return iconsSrc.replace(m[0], `const ICONS=${JSON.stringify(used)};\n`);
}

mkdirSync(DIST, { recursive: true });
let failed = false;
for (const f of readdirSync(join(SRC, 'pages')).filter((x) => x.endsWith('.json'))) {
  const name = f.slice(0, -5), man = JSON.parse(read(join(SRC, 'pages', f)));
  const mods = [...BASE, ...(man.components || []).map((c) => 'components/' + c), 'pages/' + name];
  const css = mods.map((m) => read(join(SRC, m + '.css'))).join('\n');
  const rest = mods.filter((m) => m !== 'base/icons').map((m) => read(join(SRC, m + '.js'))).join('\n');
  const js = mods.map((m) => (m === 'base/icons' ? treeShakeIcons(read(join(SRC, m + '.js')), rest) : read(join(SRC, m + '.js')))).join('\n');
  const html = `<!doctype html><html lang="en"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">` +
    `<title>Yoin Hi-Fi · ${man.title}</title><link rel="preconnect" href="https://fonts.googleapis.com"><link rel="preconnect" href="https://fonts.gstatic.com" crossorigin><link rel="stylesheet" href="${FONT}">` +
    `<style>${minCss(css)}</style></head><body><script>(()=>{"use strict";\n${minJs(js)}\n})();</script></body></html>\n`;
  const out = join(DIST, name + '.html');
  writeFileSync(out, html);
  const n = Buffer.byteLength(html), ok = n <= LIMIT;
  console.log(`${ok ? 'ok  ' : 'FAIL'} dist/${name}.html  ${(n / 1024).toFixed(1)} KiB${ok ? '' : `  (> ${LIMIT / 1024} KiB)`}`);
  if (!ok) failed = true;
}
if (failed) process.exit(1);
