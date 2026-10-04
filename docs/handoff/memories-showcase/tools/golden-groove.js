// Golden-value dump for the Kotlin port of the 唱片刻纹 groove emblem (P3).
// Loads docs/handoff/memories-showcase/groove.js (v4) unmodified on disk; the only in-memory patch exposes the
// IIFE's private geom / cutRuns / layout / colours to this harness. The award-beat density rules (awardBeats),
// NOD_MS / NOD_RE_MS and the palette helpers (liftHue / pal) are extracted verbatim from twostate4.html.
'use strict';
const fs = require('fs');
const vm = require('vm');
const path = require('path');

const DOCS = '/Users/gpo/Developer/Yoin/docs/handoff/memories-showcase/';
const OUT = process.argv[2] || path.join(__dirname, 'golden');
fs.mkdirSync(OUT, { recursive: true });

// --- minimal browser stubs ---
global.window = global;
global.document = { getElementById: () => null, createElement: () => ({}), head: { appendChild() {} } };
global.matchMedia = () => ({ matches: false });
global.performance = global.performance || { now: () => Date.now() };

vm.runInThisContext(fs.readFileSync(DOCS + 'data.js', 'utf8'), { filename: 'data.js' });
let groove = fs.readFileSync(DOCS + 'groove.js', 'utf8');
const anchor = "window.YS_EMBLEM[KEY] = {\n    name: '唱片刻纹'";
if (!groove.includes(anchor)) throw new Error('export anchor not found');
groove = groove.replace(anchor, "window.YS_EMBLEM[KEY] = { __geom: geom, __cutRuns: cutRuns, __layout: layout, __colours: colours,\n    name: '唱片刻纹'");
vm.runInThisContext(groove, { filename: 'groove.js' });
const G = window.YS_EMBLEM.groove;
if (G.version !== 4) throw new Error('expected groove v4, got ' + G.version);

// --- verbatim twostate4 snippets ---
const ts = fs.readFileSync(DOCS + 'twostate4.html', 'utf8').split('\n');
const lines = (a, b) => ts.slice(a - 1, b).join('\n');
const snippet = [lines(643, 649), lines(709, 734), lines(1072, 1073), lines(1078, 1083), lines(1093, 1099), lines(1154, 1181)].join('\n');
for (const must of ['function awardBeats', 'function liftHue', 'function pal(', 'const NOD_MS', 'const NOD_RE_MS', 'function springAt', 'const strongest']) {
  if (!snippet.includes(must)) throw new Error('snippet missing ' + must);
}
const build = new Function('G', 'RM', 'KEY', snippet + '\nreturn { awardBeats, liftHue, pal, NOD_MS, NOD_RE_MS, REVEAL_MS, springAt, mix };');
const TS = build(G, false, 'twostate4');
const TS_RM = build(G, true, 'twostate4');

// --- samples ---
const MEM = window.YS_DATA.memories;
const byId = id => MEM.find(m => m.id === id);
const clone = o => JSON.parse(JSON.stringify(o));
const RAT = { 5.4: [6, 5, 5.5, 5, 5.5], 7.2: [7.5, 7, 6.5, 8, 7], 8.6: [9, 8.5, 8, 9, 8.5], 10: [10, 10, 10, 10, 10] };
function tierSample(score, kind) {
  const m = clone(byId('m1'));
  m.id = `gf${String(score).replace('.', '')}-${kind}`; m.score = score; m.scoreKind = kind;
  m.tracks.forEach((t, i) => { t.rating = i < 5 ? RAT[score][i] : null; });
  return m;
}
function custom(id, kind, score, ratedFlags, paletteFrom) {
  const m = clone(byId(paletteFrom));
  m.id = id; m.scoreKind = kind; m.score = score;
  m.tracks = ratedFlags.map((r, i) => ({ n: i + 1, title: 't' + (i + 1), dur: 200, rating: r ? 8 : null }));
  return m;
}
const samples = [
  byId('m1'), byId('m2'), byId('m3'), byId('m4'),
  ...[5.4, 7.2, 8.6, 10].flatMap(s => ['album', 'average'].map(k => tierSample(s, k))),
  custom('long23', 'average', 8.2, Array.from({ length: 23 }, (_, i) => i % 3 !== 0), 'm3'),
  custom('single', 'album', 6.5, [true], 'm4'),
  custom('scatter', 'album', 9.0, [true, false, true, true, false, false, true, false, true, true, true, false, true, false], 'm1'),
  custom('edge595', 'album', 5.95, [true, true, false, false], 'm1'),
  custom('edge799', 'average', 7.99, [true, false, true], 'm3'),
  custom('unrated6', 'none', null, [false, false, false, false, false, false], 'm2'),
];
const SIZES = [124, 96, 72, 48, 44, 40];
const ON = ['cover', 'bar'];
const kindOf = m => m.scoreKind === 'album' ? 'album' : m.scoreKind === 'average' ? 'average' : 'none';
const modelOf = m => ({ id: m.id, kind: kindOf(m), score: m.score, rated: m.tracks.map(t => t.rating != null), palette: m.palette });

// --- geometry ---
const r6 = v => Math.round(v * 1e6) / 1e6;
function probe(svg) {
  const dots = [...svg.matchAll(/stroke-dasharray="0 ([\d.]+)" stroke-dashoffset="([\d.]+)"/g)].map(x => ({ gap: +x[1], offset: +x[2] }));
  const sc = svg.match(/class="ye-groove-score" style="font-size:([\d.]+)px;font-weight:(\d+);[^"]*?'wdth' (\d+)/);
  const caps = [...svg.matchAll(/class="ye-groove-cap" style="font-size:([\d.]+)px/g)].map(x => +x[1]);
  const rim2 = svg.match(/r="([\d.]+)" fill="none" stroke="[^"]+" stroke-width="([\d.]+)"\/>(?=<div|<circle class="ye-groove-flare")/);
  const flare = svg.match(/class="ye-groove-flare"[^>]*stroke-width="([\d.]+)"/);
  const burst = svg.match(/class="ye-groove-burst"[^>]*stroke-width="([\d.]+)"/);
  const spindle = svg.match(/<circle cx="[\d.]+" cy="[\d.]+" r="([\d.]+)" fill="[^"]+" fill-opacity=".85"\/>/);
  const ripple = /ye-groove-ripple" d=/.test(svg);
  return {
    dots,
    score: sc ? { size: +sc[1], weight: +sc[2], wdth: +sc[3] } : null,
    caps,
    flareWidth: flare ? +flare[1] : null,
    burstWidth: burst ? +burst[1] : null,
    spindleRadius: spindle ? +spindle[1] : null,
    ripple,
    rglowCount: (svg.match(/ye-groove-rglow"/g) || []).length,
    glowCount: (svg.match(/ye-groove-glow"/g) || []).length,
    tintCount: (svg.match(/ye-groove-tint"/g) || []).length,
  };
}
const geomCases = [];
for (const m of samples) for (const s of SIZES) for (const on of ON) {
  const kind = kindOf(m), g = G.__geom(m, s, kind, on);
  geomCases.push({
    id: m.id, size: s, on, kind,
    geom: {
      r: r6(g.r), tiny: g.tiny, big: g.big, rimR: r6(g.rimR), L: r6(g.L), gOut: r6(g.gOut), gIn: r6(g.gIn), pitch: r6(g.pitch),
      dw: r6(g.dw), sw: r6(g.sw), rimW: r6(g.rimW), rim2At: r6(g.rim2At),
      rings: g.rings.map(ring => ({ i: ring.i, a: ring.a, b: ring.b, rad: r6(ring.rad), runs: G.__cutRuns(ring).map(([a0, a1]) => [r6(a0), r6(a1)]) })),
    },
    probe: probe(G.render(m, { size: s, dark: false, on, uid: 'g' })),
  });
}
const layoutCases = [];
for (const m of samples) for (const s of SIZES) {
  const L = G.__layout(m, s);
  layoutCases.push({ id: m.id, size: s, kind: L.kind, nRings: L.nRings, tiny: L.tiny, cuts: L.cuts, ratedRings: L.ratedRings });
}
fs.writeFileSync(path.join(OUT, 'groove-geom.json'), JSON.stringify({
  source: 'docs/handoff/memories-showcase/groove.js v' + G.version,
  models: samples.map(modelOf), geometry: geomCases, layout: layoutCases,
}));

// --- colours ---
const N = { light: { surface: '#fdf7fd', onSurfaceVariant: '#4a4550' }, dark: { surface: '#1d1b20', onSurfaceVariant: '#cbc4cf' } };
const colourCases = [];
for (const m of samples.filter(x => ['m1', 'm2', 'm3', 'm4'].includes(x.id) || x.id === 'unrated6')) for (const dark of [false, true]) for (const on of ON) {
  const kind = kindOf(m);
  const c = G.__colours(m, kind, dark, on);
  const flat = {};
  Object.keys(c).forEach(k => { if (typeof c[k] === 'string') flat[k] = c[k]; });
  const tints = {};
  if (kind !== 'none') { tints.cut = c.tint(c.cut); tints.rim = c.tint(c.rim); } else { tints.hairS = c.tint(c.hairS); }
  colourCases.push({ id: m.id, kind, dark, on, colours: flat, tints });
}
// a second kind on the same palettes (average on m1 / album on m3), so every branch sees every palette
for (const [id, kind] of [['m1', 'average'], ['m3', 'album'], ['m2', 'album'], ['m4', 'average']]) for (const dark of [false, true]) {
  const m = clone(byId(id)); m.scoreKind = kind; m.score = 8;
  const c = G.__colours(m, kind, dark, 'cover');
  const flat = {}; Object.keys(c).forEach(k => { if (typeof c[k] === 'string') flat[k] = c[k]; });
  colourCases.push({ id: id + '-as-' + kind, palette: m.palette, kind, dark, on: 'cover', colours: flat, tints: { cut: c.tint(c.cut), rim: c.tint(c.rim) } });
}
fs.writeFileSync(path.join(OUT, 'groove-colors.json'), JSON.stringify({
  source: 'groove.js colours() v' + G.version, neutralTokens: N,
  palettes: Object.fromEntries(MEM.map(m => [m.id, m.palette])), cases: colourCases,
}, null, 1));

// --- beats + curves ---
const SCRIPT_SIZES = [96, 72, 124];
const beatsCases = [];
const strip = b => ({ atMs: b.atMs, p: b.p, scale: b.scale, yoin: b.yoin === undefined ? null : b.yoin });
for (const m of samples) {
  const tier = G.tierOf(m);
  for (const s of SCRIPT_SIZES.concat([48])) {
    const inf = G.__layout(m, s);
    const entry = { id: m.id, size: s, kind: kindOf(m), score: m.score, tier, nRings: inf.nRings, ratedRings: inf.ratedRings };
    if (tier) {
      const S = G.script(tier, m, s);
      entry.T = S.T;
      entry.hapticPlan = G.hapticPlan(m, { size: s }).map(strip);
      entry.hapticPlanReduced = G.hapticPlan(m, { size: s, reduced: true }).map(strip);
      entry.award = {
        first: TS.awardBeats(m, 'first', s).map(strip),
        replay: TS.awardBeats(m, 'replay', s).map(strip),
        nod: TS.awardBeats(m, 'nod', s).map(strip),
        reducedFirst: TS_RM.awardBeats(m, 'first', s).map(strip),
        reducedReplay: TS_RM.awardBeats(m, 'replay', s).map(strip),
        reducedNod: TS_RM.awardBeats(m, 'nod', s).map(strip),
      };
      if (s === 96 && m.id.startsWith('gf')) {
        const ts2 = [];
        for (let i = 0; i <= Math.round(S.T * 50); i++) ts2.push(i / 50);
        [0.0005, 0.123, 0.4567, 0.7891].forEach(x => { if (x <= S.T) ts2.push(x); });
        const orders = Math.max(1, inf.ratedRings.length);
        const q = v => Math.round(v * 1e9) / 1e9;
        // one row per t: [t, disc, label, [cut(j)], [cutGlow(j)], [ringGlow(i)], flare, burstScale, burstAlpha]
        entry.curveColumns = ['t', 'disc', 'label', 'cut[j]', 'cutGlow[j]', 'ringGlow[i]', 'flare', 'burstScale', 'burstAlpha'];
        entry.curves = ts2.map(t => {
          const cut = [], glow = [], ring = [];
          for (let j = 0; j < Math.min(orders, 4); j++) { cut.push(q(S.cut(j)(t))); glow.push(q(S.cutGlow(j)(t))); }
          for (let i = 0; i < inf.nRings; i++) ring.push(q(S.ringGlow(i)(t)));
          const b = S.burst ? S.burst(t) : { s: 1, o: 0 };
          return [t, q(S.disc(t)), q(S.label(t)), cut, glow, ring, q(S.flare(t)), q(b.s), q(b.o)];
        });
      }
    }
    beatsCases.push(entry);
  }
}
fs.writeFileSync(path.join(OUT, 'groove-beats.json'), JSON.stringify({
  source: 'groove.js script()/hapticPlan() v' + G.version + ' + twostate4.html awardBeats()',
  constants: { NOD_MS: TS.NOD_MS, NOD_RE_MS: TS.NOD_RE_MS, REVEAL_MS: TS.REVEAL_MS },
  models: samples.map(modelOf),
  cases: beatsCases,
}));

// --- tilt ---
const tiltIn = [[0, 0], [0.3, -0.2], [1, 0], [0, 1], [-0.7, 0.7], [1.5, 1.5], [-2, 0.5], [0.05, 0.02], [-0.4, -0.9], [3, -4]];
fs.writeFileSync(path.join(OUT, 'groove-tilt.json'), JSON.stringify({
  source: 'groove.js tiltVars() v' + G.version,
  cases: tiltIn.map(([x, y]) => ({ x, y, ...G.tiltVars(x, y) })),
}, null, 1));

// --- palette tones ---
fs.writeFileSync(path.join(OUT, 'memory-palette.json'), JSON.stringify({
  source: 'twostate4.html pal() / liftHue()',
  cases: MEM.map(m => ({ id: m.id, palette: m.palette, light: TS.pal(m, false), dark: TS.pal(m, true), liftHue92: TS.liftHue(m.palette.base, 0.92) })),
}, null, 1));

console.log('wrote', fs.readdirSync(OUT).join(', '), 'to', OUT);
console.log('NOD_MS', TS.NOD_MS, 'NOD_RE_MS', TS.NOD_RE_MS);
