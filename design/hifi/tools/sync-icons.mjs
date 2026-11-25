#!/usr/bin/env node
// Refreshes the vendored icons in design/hifi/icons/ from a yoin-symbols checkout (default: ../../../yoin-symbols,
// i.e. the sibling of the Yoin repo, the same place Gradle reads it from).
// Copies every SVG that is already vendored, plus any name the sources use with ic('name') that the library has.
// Usage: node design/hifi/tools/sync-icons.mjs [path/to/yoin-symbols]
import { readFileSync, readdirSync, existsSync, copyFileSync } from 'node:fs';
import { join, dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
const ROOT = join(dirname(fileURLToPath(import.meta.url)), '..');
const LIB = join(resolve(process.argv[2] || join(ROOT, '../../../yoin-symbols')), 'svg');
if (!existsSync(LIB)) { console.error(`no yoin-symbols svg/ at ${LIB}`); process.exit(1); }
const ICONS = join(ROOT, 'icons');
const names = new Set(readdirSync(ICONS).filter((f) => f.endsWith('.svg')).map((f) => f.slice(0, -4)));
const walk = (d) => readdirSync(d, { withFileTypes: true }).flatMap((e) => (e.isDirectory() ? walk(join(d, e.name)) : e.name.endsWith('.js') ? [join(d, e.name)] : []));
for (const f of walk(join(ROOT, 'src'))) for (const m of readFileSync(f, 'utf8').matchAll(/\bic\(\s*['"`]([a-z0-9_]+)['"`]/g)) names.add(m[1]);
let n = 0;
for (const k of [...names].sort()) {
  const src = join(LIB, k + '.svg');
  if (existsSync(src)) { copyFileSync(src, join(ICONS, k + '.svg')); n++; }
  else if (!existsSync(join(ICONS, 'drawn', k + '.svg'))) console.warn(`missing: ${k} (not in yoin-symbols, not in icons/drawn/)`);
}
copyFileSync(join(LIB, '..', 'LICENSE'), join(ICONS, 'LICENSE'));
console.log(`${n} icons copied from ${LIB}`);
