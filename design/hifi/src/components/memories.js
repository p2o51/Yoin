/* Memories (ui/memories). The deck (showcase: MemoriesShowcase, MemoryCardFace, MemoriesTopBar, MemoryDiary,
   MemoriesSpread) and All Memories (ledger: MemoryLedgerScreen, MemoryGridView).
   Colour: each card's colours are straight sRGB lerps of the album's palette (base / accent / deep / soft) — never
   fromSeed, never the global tokens. Light: ink = button = dot = base, onButton white. Dark: ink = button = dot =
   lift = mix(base, soft, .6), onButton = deep. Background = the page colour + an "aurora" of 3 blooms in base /
   accent (α .20 light, .26 dark). Flat: no shadows, no borders. */
const MEM_DECK = [
  { k: 'harbor', al: CAT.harbor, year: 2021, kind: 'avg', score: '8.4', rated: [1, 0, 1, 0, 1, 0, 0, 0], title: 'Leaving harbour at first light', excerpt: 'Sounds like leaving at dawn', attr: ['Your note', 'Morning Static 0:34'], notes: 6, last: 'Oct 16' },
  { k: 'glass', al: CAT.glassAlbum, year: 2019, kind: 'album', score: '8.6', rated: [1, 1, 1, 1, 0, 1, 1, 0, 1], title: null, excerpt: 'Warmer than Slow Tide', attr: ['Your review', 'Nov 22'], notes: 3, last: 'Nov 23' },
  { k: 'yehang', al: CAT.yehang, year: 2023, kind: 'unrated', rated: [0, 0, 0, 0, 0, 0, 0, 0], title: '灯塔数着每一艘船', excerpt: 'the hum under the second verse', attr: ['Your note', '回声 1:12'], notes: 3, last: 'Sep 30' },
];
function memVars(k) { return artVars(k); }
function memAurora() { return '<div class="m-aur"><i></i><i></i><i></i></div>'; }
/** MemoriesTopBar: 64 tall. Card state: Home pill (88 × 36) · "Memories" / "Last heard …" · page dots.
   Diary state: the pill shrinks to 36 (⌃ only), a 40 cover, album / artist · year, ⌄. */
function memTopBar(m, { diary = false, inset = 16, idx = 0 } = {}) {
  const dots = `<span class="m-dots">${MEM_DECK.map((d, i) => `<i class="${i === idx ? 'on' : ''}" style="${memVars(d.k)}"></i>`).join('')}</span>`;
  const left = diary ? `<span class="m-home sm">${ic('chevron_up', 18)}</span>${cover(m.al.k, 'm-tcv')}<span class="m-slot"><b class="ell">${m.al.t}</b><span>${m.al.s}<i>${m.year}</i></span></span>${ic('chevron_down', 18, 'm-chev')}`
    : `<span class="m-home">${ic('chevron_up', 18)}Home</span><span class="m-slot a"><b>Memories</b><span>Last heard ${m.last}</span></span>`;
  return `<div class="m-bar" style="padding:0 ${inset - 4}px 0 ${inset}px">${left}<span class="sp"></span>${dots}</div>`;
}
function memEmblem(m, px) { return grooveEmblem(px, { kind: m.kind, score: m.score, rated: m.rated, art: m.al.k }); }
function memCoverBlock(m, cv, em) {
  return `<div class="m-cvb" style="--cv:${cv}px;--em:${em}px">${cover(m.al.k, 'm-cv')}<span class="m-em">${memEmblem(m, em)}</span></div>`;
}
function memTitle(m, big) {
  return m.title ? `<h2 class="m-title serif ${big ? 'big' : ''}">${m.title}</h2><div class="m-alrow"><b>${m.al.t}</b><span>${m.al.s}<i>${m.year}</i></span></div>`
    : `<h2 class="m-title ${big ? 'big' : ''}">${m.al.t}</h2><div class="m-alrow solo"><span>${m.al.s}<i>${m.year}</i></span></div>`;
}
/** Card face: exhibit (cover 256 / 360 Medium + emblem 96 / 124 overhanging .3 / .24) · title · album row;
   teaser at the bottom: the excerpt in quotes (≤ 16 chars → 22 / 1.4) + attribution, [Diary n] [Go to album]. */
function memCard(m, { medium = false } = {}) {
  const cv = medium ? 360 : 256, em = medium ? 124 : 96;
  return `<div class="m-card ${medium ? 'med' : ''}" style="${memVars(m.al.k)}">${memCoverBlock(m, cv, em)}${memTitle(m, medium)}<span class="sp"></span>` +
    `<div class="m-teaser"><p class="m-ex ${m.excerpt.length <= 16 ? 'short' : ''}">“${m.excerpt}”</p><div class="m-attr"><span>${m.attr[0]}</span><span class="num">${m.attr[1]}</span></div>` +
    `<div class="m-btns"><button class="m-diary">${ic('edit_note', 18)}Diary<span class="m-badge">${m.notes}</span></button><button class="m-go">Go to album</button></div></div></div>`;
}
/** Diary (MemoryDiary): title row (serif + 48 emblem), entry header (date in ink · "Your review"), the review,
   • • •, the liner (track rows + timed notes, mention pills), the run-out mark, two stats, Go to album. */
function memDiary(m, { large = false, spread = false } = {}) {
  const sb = SCRAP[m.k] || SCRAP.harbor, al = ALBUMS[m.k] || ALBUMS.harbor;
  const head = spread ? '' : `<div class="d-title">${m.title ? `<h3 class="serif">${m.title}</h3>` : '<span class="sp"></span>'}${memEmblem(m, 48)}</div>`;
  const review = al.review ? `<div class="d-entry"><div class="d-eh"><b>Nov 23, 26</b><span>Your review</span></div><p class="d-rev">${al.review[0]}</p></div>`
    : `<p class="d-yoin">You played it twice this week and haven't said a word. <b>What stayed with you?</b></p><div class="d-blank"><span>Write</span></div>`;
  const liner = sb.clusters.map((c) => `<div class="d-tr"><span class="num">${+c.n}</span><b>${c.t}</b><span class="d-sc num">${c.score}</span></div>` +
    c.notes.map(([at, segs], i) => `<div class="d-note ${c.n === '03' && i === 0 ? 'lit' : ''}"><span class="num">${at}</span><p>${sbSegs(segs)}</p></div>`).join('')).join('');
  const stk = (sb.stickies || []).slice(0, 1).map((s) => `<div class="d-note"><span><i class="bead"></i></span><p>${sbSegs(s)}</p></div>`).join('');
  return `<div class="m-diaryp ${large ? 'large' : ''}" style="${memVars(m.al.k)}">${head}${review}<div class="d-sep"><i></i><i></i><i></i></div><div class="d-liner">${stk}${liner}</div>` +
    `<div class="d-end"><svg class="d-run" viewBox="0 0 28 28"><circle cx="14" cy="14" r="13.5"/><circle cx="14" cy="14" r="9.5"/><circle cx="14" cy="14" r="5.5"/></svg>` +
    `<div class="d-stats"><span><b class="rond">22</b>plays</span><span><b class="rond">11</b>days since Nov 13</span></div>${spread ? '' : '<button class="m-go">Go to album</button>'}<span class="d-others">What others say</span></div></div>`;
}
/** Expanded spread: left page (cover, title, Yoin's paragraph, album, Go to album) | the diary on the right page. */
function memSpread(m, W) {
  const lp = Math.round(Math.min(600, Math.max(400, W * .46)));
  return `<div class="m-spread" style="${memVars(m.al.k)};--lp:${lp}px"><div class="m-left">${memCoverBlock(m, 300, 120)}<h2 class="m-title serif big">${m.title || m.al.t}</h2>` +
    `<p class="d-yoin c">The brass came back three times this month, always after midnight. <b>Was it the same ferry?</b></p><div class="m-alrow c"><b>${m.al.t}</b><span>${m.al.s}</span></div><button class="m-go">Go to album</button></div>` +
    `<div class="m-right">${memDiary(m, { large: true, spread: true })}</div></div>`;
}
/* ── All Memories (the ledger) ── */
const LEDGER = {
  months: [['Jun', []], ['Jul', []], ['Aug', []], ['Sep', []], ['Oct', [['s', 'meri']]], ['Nov', [['w', 'glass'], ['w', 'harbor'], ['w', 'yehang']]]],
  entries: [
    { type: 'song', date: 'Nov 24', segs: [{ m: 'song', t: '灯塔', k: 'yehang' }, ' bridge pairs with ', { m: 'album', t: 'Harbor Lights', k: 'harbor' }, ' and ', { m: 'artist', t: '林间', k: 'linjian' }], sub: [{ b: 'slow build' }, ' into the last chorus'], song: ['灯塔', '0:48'], al: CAT.yehang },
    { type: 'album', m: 0 },
    { type: 'song', date: 'Nov 24', segs: ['Chorus lifts like ', { m: 'song', t: 'Night Ferry', k: 'harbor' }, ' from ', { m: 'album', t: 'Low Tide Static', k: 'blue' }], sub: [{ b: 'brass swells' }, ' at the bridge'], song: ['Paper Crowns', '1:51'], al: CAT.glassAlbum },
    { type: 'album', m: 1 },
    { type: 'song', date: 'Nov 23', segs: [{ b: 'Intro' }, ' — loud drums like ', { m: 'album', t: 'Glass Orchard', k: 'glass' }], song: ['Glass Orchard', '0:16'], al: CAT.glassAlbum },
    { type: 'album', m: 2 },
  ],
};
function ledgerCell(c) {
  if (!c) return '<i class="lg-q"></i>';
  const a = ART[c[1]];
  return c[0] === 'w' ? `<i class="lg-q" style="background:linear-gradient(135deg, ${a.base} 50%, ${a.acc} 50%)"></i>` : `<i class="lg-q" style="background:${a.base}"></i>`;
}
function ledgerTop(cls) {
  const stats = [['4', 'Albums'], ['10', 'Written'], ['1,095', 'Characters'], ['3', 'Songs scored']];
  const months = LEDGER.months.map(([n, cells]) => `<div class="lg-m"><div class="lg-quad">${[0, 1, 2, 3].map((i) => ledgerCell(cells[i])).join('')}</div><span>${n}</span><small>${cells.length ? `${cells.length === 1 ? '1 entry' : cells.length * 4 - 1 + ' entries'}` : '—'}</small></div>`).join('');
  return `<div class="lg-top"><span class="ad-back">${ic('back', 22)}</span><h1>All Memories</h1><div class="lg-stats">${stats.map(([n, l]) => `<span><b>${n}</b><small>${l}</small></span>`).join('')}</div><div class="lg-grid">${months}</div></div>`;
}
function ledgerSong(e) {
  return `<div class="lg-song"><div class="lg-sh"><span>Song note</span><span>${e.date}</span></div><p class="serif">${noteSegs(e.segs)}${e.sub ? `<br>• ${noteSegs(e.sub)}` : ''}</p>` +
    `<div class="lg-st"><b>${e.song[0]}</b><span class="num">${e.song[1]}</span></div><div class="lg-foot">${cover(e.al.k, 'lg-fcv')}<span>${e.al.t}<i>${e.al.s}</i></span></div></div>`;
}
function ledgerAlbum(m, side) {
  const al = ALBUMS[m.k] || { review: null };
  return `<div class="lg-album ${side}" style="${memVars(m.al.k)}">${cover(m.al.k, 'lg-acv')}${m.title ? `<h4 class="serif">${m.title}</h4>` : ''}<div class="lg-pair">${m.al.t}<span>${m.al.s}</span></div>` +
    (m.score ? `<div class="lg-score"><b class="num">${m.score}</b>${m.kind === 'avg' ? 'Track average' : 'Album score'}</div>` : '') + (al.review ? `<p>${al.review[0]}</p>` : '') + `<small>Nov 23, 2026</small></div>`;
}
function ledgerBody(view, cls) {
  const lanes = view === 'cards' ? (cls === 'expanded' ? 3 : 2) : cls === 'compact' ? 1 : cls === 'medium' ? 2 : 3;
  if (view === 'list') {
    return `<div class="lg-list">${LEDGER.entries.map((e) => e.type === 'album' ? (() => { const m = MEM_DECK[e.m]; return `<div class="lg-row">${cover(m.al.k, 'lg-rcv')}<span class="col"><b class="serif">${m.title || m.al.t}</b><span>${m.al.t}</span><small>Nov 23</small></span><b class="lg-rs num">${m.score || '—'}</b></div>`; })()
      : `<div class="lg-row s">${cover(e.al.k, 'lg-rcv s')}<span class="col"><b>${e.song[0]}</b><small>${e.date}</small></span><b class="lg-rs num">—</b></div>`).join('')}</div>`;
  }
  const items = LEDGER.entries.map((e, i) => {
    if (view === 'cards') {
      if (e.type === 'album') { const m = MEM_DECK[e.m]; return `<div class="lg-card" style="${memVars(m.al.k)}">${cover(m.al.k, 'lg-ccv')}<b class="serif">${m.title || m.al.t}</b><span class="lg-cal">${m.al.t}</span><span class="lg-car">${m.al.s}</span><div class="lg-cf"><b class="num">${m.score || '—'}</b><small>Nov 23</small></div></div>`; }
      return `<div class="lg-card s"><div class="lg-cs">${cover(e.al.k, 'lg-scv')}<span class="col"><b>${e.song[0]}</b><span>${e.al.s}</span></span></div><p>${noteSegs(e.segs)}</p><div class="lg-cf"><b>—</b><small>${e.date}</small></div></div>`;
    }
    return e.type === 'album' ? ledgerAlbum(MEM_DECK[e.m], i % 4 === 1 ? 'end' : 'start') : ledgerSong(e);
  });
  const cols = Array.from({ length: lanes }, () => []); items.forEach((h, i) => cols[i % lanes].push(h));
  return `<div class="lg-month"><h2 class="serif">November</h2><span>11 entries</span></div><div class="lg-lanes ${view}" style="grid-template-columns:repeat(${lanes},minmax(0,1fr))">${cols.map((c) => `<div>${c.join('')}</div>`).join('')}</div>` +
    `<div class="lg-quiet"><i></i>Nothing written in July<i></i></div>`;
}
function ledger(view, cls) {
  const names = { timeline: 'Timeline', cards: 'Cards', list: 'List' };
  const icons = { timeline: 'view_timeline', cards: 'view_cards', list: 'view_list' };
  return `<div class="lg ${cls}">${ledgerTop(cls)}<div class="lg-vbar">${view === 'list' ? `<span class="lg-pillv">${ic('sort', 18)}Recent</span>` : ''}<span class="sp"></span><span class="lg-pillv">${ic(icons[view], 18)}${names[view]}</span></div>${ledgerBody(view, cls)}<p class="lg-kept">Kept since Nov 24, 2026. Every account counts as you.</p></div>`;
}
