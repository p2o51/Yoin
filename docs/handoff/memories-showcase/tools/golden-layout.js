// Golden harness for Memories P6 (large screens): runs the approved prototype's own container budget
// (twostate4.html `clamp`, `medCov`, `layoutFor`, `sealFor`) under node and writes the expected tiers and
// sizes the Kotlin port (ui/memories/showcase/MemoriesLayout.kt) is compared against.
//
// usage: node golden-layout.js <repo root> <out dir>
//   e.g. node docs/handoff/memories-showcase/tools/golden-layout.js . app/src/test/resources/memories/golden
//
// The snippets are located by their first line (not by line number) and run unmodified. Owner choice
// tablet-portrait is 'A' (README), so the stack tier never appears. Phone landscape (H < 480) is not in
// the prototype; those rows are dumped too, but only as the prototype's own answer — the port deviates
// there on purpose (MemoriesLayout.kt, PLAN §7 Q2) and the test skips them.
'use strict';
const fs = require('fs');
const path = require('path');

const repo = process.argv[2] || '.';
const out = process.argv[3] || path.join(repo, 'app/src/test/resources/memories/golden');
const html = fs.readFileSync(path.join(repo, 'docs/handoff/memories-showcase/twostate4.html'), 'utf8').split('\n');

// [first line, number of lines]; each must be found exactly once
function grab(head, count) {
  const at = html.map((l, i) => (l.includes(head) ? i : -1)).filter(i => i >= 0);
  if (at.length !== 1) throw new Error(`"${head}" found ${at.length} times`);
  return html.slice(at[0], at[0] + count).join('\n');
}
const src = [
  grab('const clamp = (x, a, b) =>', 1),
  grab('const medCov = H =>', 1),
  grab('function layoutFor(W, H, tp) {', 18),
  grab('const sealFor = (Y, c) =>', 1),
].join('\n');
for (const must of ['return { tier: \'spread\'', 'dcol: Math.min(640, W - 32)', 'minCov: 200']) {
  if (!src.includes(must)) throw new Error('snippet missing ' + must);
}
const P = new Function(src + '\nreturn { clamp, medCov, layoutFor, sealFor };')();

const row = (W, H) => {
  const y = P.layoutFor(W, H, 'A');
  return {
    w: W, h: H, tier: y.tier, short: !!y.S, cover: y.cov, seal: y.seal, pl: y.pl, dr: y.dr, barh: y.barh,
    lp: y.lp ?? null, measure: y.measure ?? null, dcol: y.dcol ?? null, air1: y.air1 ?? null, minCov: y.minCov ?? null,
  };
};

// PLAN §5 P6's table, then the devices and windows the QA pass uses
const table = [[412, 915], [375, 667], [600, 728], [690, 840], [800, 1280], [860, 800], [900, 1100], [1000, 700], [1280, 800]];
const extra = [[411, 914], [411, 731], [599, 900], [600, 900], [768, 1024], [840, 1200], [899, 1000], [900, 1000],
  [1024, 768], [1100, 900], [1366, 1024], [1600, 1000], [914, 411], [800, 360]];
// a sweep across both seams (600 and 900) at every height the budget distinguishes
const sweep = [];
for (let H = 480; H <= 1400; H += 20) for (const W of [599, 600, 899, 900]) sweep.push([W, H]);
// sealFor along the spread's height ladder (the cover stepping down under the budget's offer)
const ladder = [];
for (const [W, H] of [[1000, 700], [1280, 800], [1280, 1000]]) {
  const y = P.layoutFor(W, H, 'A');
  for (let c = y.cov; c >= y.minCov; c -= 13) ladder.push({ w: W, h: H, cover: c, seal: P.sealFor(y, c) });
}

fs.mkdirSync(out, { recursive: true });
fs.writeFileSync(path.join(out, 'layout-table.json'), JSON.stringify({
  source: 'twostate4.html layoutFor(W, H, "A") / medCov / sealFor',
  table: table.map(([w, h]) => row(w, h)),
  extra: extra.map(([w, h]) => row(w, h)),
}, null, 1));
fs.writeFileSync(path.join(out, 'layout-sweep.json'), JSON.stringify({
  source: 'twostate4.html layoutFor(W, H, "A"): W = 599 / 600 / 899 / 900, H = 480..1400 step 20',
  rows: sweep.map(([w, h]) => row(w, h)),
}, null, 1));
fs.writeFileSync(path.join(out, 'layout-seal.json'), JSON.stringify({
  source: 'twostate4.html sealFor(layoutFor(W, H, "A"), cover) down the spread height ladder',
  rows: ladder,
}, null, 1));
console.log('wrote layout-table.json, layout-sweep.json, layout-seal.json to', out);
console.table(table.map(([w, h]) => row(w, h)));
