// Golden harness for Memories P4 copy: runs the approved prototype's own functions
// (docs/handoff/memories-showcase/twostate4.html + data.js) under node and writes
// the expected strings the Kotlin port is compared against.
//
// usage: node golden.js <repo root> <out dir>
'use strict';
const fs = require('fs');
const path = require('path');
const vm = require('vm');

const repo = process.argv[2];
const out = process.argv[3];
const dir = path.join(repo, 'docs/handoff/memories-showcase');
const html = fs.readFileSync(path.join(dir, 'twostate4.html'), 'utf8').split('\n');
const dataJs = fs.readFileSync(path.join(dir, 'data.js'), 'utf8');

// 1-based inclusive line ranges, each checked against its expected first line
const RANGES = [
  [600, 638, 'const M5 = {'],
  [652, 670, "const TODAY = '2026-10-04'"],
  [672, 672, 'const scoreTxt'],
  [676, 676, 'const esc ='],
  [773, 776, 'const trackOf ='],
  [778, 793, 'function sentences'],
  [802, 897, 'const AI_EN'],
  [902, 910, 'function stats(m)'],
  [915, 936, 'const wlen'],
  [966, 968, 'const entryH'],
];
let src = '';
for (const [a, b, head] of RANGES) {
  if (!html[a - 1].includes(head)) throw new Error(`line ${a} is not "${head}": ${html[a - 1]}`);
  src += html.slice(a - 1, b).join('\n') + '\n';
}
src += `globalThis.__p = { M5, TODAY, THIS_YEAR, day, dayZh, dayY, daysBetween, num, ZH_MON, sentences, joinS,
  writesIn, langs, signals, sinceTxt, agoTxt, motif, narrative, voice, stats, diaryLabel, wlen, excerptCands, entryH, noteBy };`;

const ctx = { console, Math, Date, Array, String, Set, Object, Number, JSON };
ctx.window = ctx;
vm.createContext(ctx);
vm.runInContext(dataJs, ctx);
ctx.root = { dataset: {} };
vm.runInContext(src, ctx);
const P = ctx.__p;

const clone = o => JSON.parse(JSON.stringify(o));
const base = ctx.YS_DATA.memories.concat([P.M5]).map(clone);
const byId = id => clone(base.find(m => m.id === id));

// what the Kotlin side needs to rebuild each memory
const inputOf = m => ({
  id: m.id, album: m.album, aiTitle: m.aiTitle || null, review: m.review || null,
  reviewWrittenAt: m.reviewWrittenAt || null, scoreKind: m.scoreKind, score: m.score == null ? null : m.score,
  ratedTracks: m.ratedTracks, totalTracks: m.totalTracks, plays: m.plays,
  firstPlayed: m.firstPlayed, lastPlayed: m.lastPlayed,
  tracks: m.tracks.map(t => ({ n: t.n, title: t.title, rating: t.rating == null ? null : t.rating })),
  notes: m.notes.map(n => ({ track: n.track, at: n.at, date: n.date, text: n.text })),
});

const VARIANTS = {
  'as-is': m => m,
  'no-ai': m => Object.assign(m, { aiTitle: null }),
  'no-review': m => Object.assign(m, { review: null }),
  'no-review-no-ai': m => Object.assign(m, { review: null, aiTitle: null }),
};
// extra shapes the five samples don't reach on their own (template 4, the plays motif, one note)
const EXTRA = [
  ['m3', 'sparse-ratings', m => {
    m.tracks.forEach((t, i) => { if (i > 1) t.rating = null; });
    m.ratedTracks = 2; m.aiTitle = null; m.review = null; return m;
  }],
  ['m2', 'one-note', m => { m.notes = m.notes.slice(0, 1); m.aiTitle = null; return m; }],
  ['m5', 'last-year-only', m => {
    m.firstPlayed = '2025-02-03'; m.lastPlayed = '2025-02-20'; m.notes = []; m.review = null; m.aiTitle = null;
    m.tracks.forEach(t => { t.rating = null; }); m.ratedTracks = 0; return m;
  }],
  ['m4', 'yesterday', m => { m.lastPlayed = '2026-10-03'; m.review = null; return m; }],
];

const voiceCases = [];
const excerptCases = [];
const inputs = {};
const addCase = (id, variant, m) => {
  const key = `${id}/${variant}`;
  inputs[key] = inputOf(m);
  for (const lang of ['A', 'B']) {
    ctx.root.dataset = { yoinLanguage: lang, fallbackTitle: 'B' };
    const V = P.voice(m);
    voiceCases.push({
      key, language: lang === 'A' ? 'auto' : 'en',
      lang: V.lang, title: { kind: V.title.kind, text: V.title.text }, nar: V.nar, ask: V.ask,
      said: Array.from(V.said).sort(),
    });
  }
  ctx.root.dataset = { yoinLanguage: 'A', fallbackTitle: 'B' };
  const V = P.voice(m);
  const parse = h => {
    const q = /<blockquote[^>]*>“([\s\S]*?)”<\/blockquote>/.exec(h);
    const by = /<figcaption[^>]*>([\s\S]*?)<\/figcaption>/.exec(h);
    const un = s => s.replace(/&quot;/g, '"').replace(/&lt;/g, '<').replace(/&gt;/g, '>').replace(/&amp;/g, '&');
    return { text: q ? un(q[1]) : null, by: by ? un(by[1]) : null };
  };
  for (const cap of [false, true]) {
    excerptCases.push({
      key, medium: cap,
      candidates: P.excerptCands(m, V, cap).map(c => Object.assign(parse(c.html), { only: !!c.only })),
    });
  }
};
for (const m0 of base) {
  for (const [variant, f] of Object.entries(VARIANTS)) addCase(m0.id, variant, f(clone(m0)));
}
for (const [id, variant, f] of EXTRA) addCase(id, variant, f(byId(id)));

const ISO = ['2026-01-01', '2026-02-02', '2026-07-26', '2026-10-04', '2026-12-31', '2025-11-08', '2025-12-31', '2024-02-29', '2019-06-15'];
const dates = {
  today: P.TODAY,
  days: ISO.map(iso => ({ iso, day: P.day(iso), dayZh: P.dayZh(iso), dayY: P.dayY(iso) })),
  zhMonths: P.ZH_MON,
  numbers: [0, 1, 2, 3, 7, 10, 11, 12, 13, 64].map(n => ({ n, zh: P.num(n, 'zh'), en: P.num(n, 'en'), enCap: P.num(n, 'en', true) })),
  memories: base.map(m => {
    const S = P.signals(m);
    const statsHtml = P.stats(m);
    const cells = [...statsHtml.matchAll(/<dt>([\s\S]*?)<\/dt><dd>([\s\S]*?)<\/dd>/g)].map(x => ({ caption: x[1], value: Number(x[2]) }));
    return {
      id: m.id, key: `${m.id}/as-is`,
      lastHeard: `Last heard ${P.day(m.lastPlayed)}`,
      footer: cells,
      reviewHeader: m.review ? `${P.dayY(m.reviewWrittenAt)} · Your review` : null,
      blankHeader: `${P.dayY(P.TODAY)} · Today`,
      sinceZh: P.sinceTxt(S, true), sinceEn: P.sinceTxt(S, false),
      agoZh: P.agoTxt(m, S, true), agoEn: P.agoTxt(m, S, false),
      seasons: S.seasons, noteDays: S.noteDays == null ? null : S.noteDays,
    };
  }),
};

const SENT_SAMPLES = [
  '给 8.5，扣掉的那一点留给第十首，它不该那么短。',
  '她说：“冬天很长。”我没回答。',
  'He said “stay.” Then the bridge came in! Was it 9.5? Yes.',
  'Version 2.0 is out... really?! ok',
  '没有句号的一句',
  '「好听。」就这样。',
  '（括号里的一句。）然后呢？',
];
const sentences = {
  samples: SENT_SAMPLES.concat(base.filter(m => m.review).map(m => m.review.split('\n')[0])).map(t => {
    const s = P.sentences(t);
    return { text: t, sentences: s, joined2: P.joinS(s.slice(0, 2)), wlen: P.wlen(t) };
  }),
  wlen: ['好听。', 'Bridge 太好了', 'abc', '“引号”', 'ｆｕｌｌ', '😀'].map(t => ({ text: t, wlen: P.wlen(t) })),
};

const write = (name, data) => fs.writeFileSync(path.join(out, name), JSON.stringify(data, null, 1) + '\n');
fs.mkdirSync(out, { recursive: true });
write('copy-inputs.json', { today: P.TODAY, source: 'docs/handoff/memories-showcase/twostate4.html + data.js', inputs });
write('copy-voice.json', { today: P.TODAY, cases: voiceCases });
write('copy-excerpt.json', { today: P.TODAY, cases: excerptCases });
write('copy-dates.json', dates);
write('copy-sentences.json', sentences);
console.log(`voice ${voiceCases.length}, excerpt ${excerptCases.length}, dates ${dates.memories.length}, sentences ${sentences.samples.length}`);
