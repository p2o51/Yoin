/* Album detail, page 1 (ui/detail: AlbumDetailScreen, AlbumDetailComponents, AlbumSpectrumBar, DetailBottomBar).
   Header: 40 back (surfaceContainer) · 14 · title headlineSmall in the cover primary + meta (artist · "Album" ·
   year, 12 apart, no glyph) · page dots under it (24 × 20 cells, r 4; secondary / onSurfaceVariant 30 %).
   Compact: hero = 300 cover (min(0.74 W, 300), r 8) on the "butterfly" (two arms of the Yoin mark in the cover's
   primary / secondary, bleeding off both edges), emblem 64 + review or a 40 pen, count line, then ALL titles as
   one run-on headlineMedium paragraph in alternating tones. Pulled up: the header becomes the spectrum bar
   (cover colours as vertical bands, OKLab L 0.30–0.46, white text), the cover a 48 stamp at the end, tracks as rows.
   Medium (and the 656 detail column in Expanded): no butterfly, list ≤ 720 centred; hero row = 240 cover ·
   Last Play / day / time | emblem, then review or pen; rows. Scrolling the cover away forms the same bar. */
const ALBUMS = {
  harbor: { a: CAT.harbor, year: 2021, date: [2021, 4, 16], kind: 'album', score: '8.4', rated: [1, 0, 1, 0, 1, 0, 0, 0], last: ['Today', '13:09'], review: ['Warm harbor mornings; the brass sits just behind the voice.', 'Yesterday'],
    tracks: [['Morning Static', '4:05', 'n'], ['Night Ferry', '3:31'], ['Slow Tide', '3:59', 'n'], ['Paper Lanterns', '4:12'], ['Harbor Lights', '4:48'], ['Postcards', '3:22'], ['Pier', '2:57'], ['Low Tide', '5:06']],
    bands: [[300, .07, 3], [330, .09, 2], [20, .12, 3], [48, .13, 2], [290, .06, 2]], cur: 1, liked: [1, 4] },
  north: { a: CAT.north, year: 2022, date: [2022, 11], kind: 'unrated', rated: [0, 0, 0, 0, 0, 0], last: ['Today', '13:09'], review: null,
    tracks: [['I. Frost', '3:19'], ['II. Birch', '4:39'], ['III. Lake at Dawn', '3:17'], ['IV. Snowfield', '4:37'], ['V. Aurora', '4:29'], ['VI. Return', '3:48']],
    bands: [[255, .05, 3], [262, .08, 3], [250, .04, 2], [240, .03, 2]], cur: 0, liked: [] },
  glass: { a: CAT.glassAlbum, year: 2019, date: [2019, 9, 6], kind: 'avg', score: '7.9', rated: [1, 1, 0, 1, 1, 0, 0, 1, 1], last: ['Yesterday', '23:40'], review: ['Warmer than Slow Tide.', 'Nov 22'],
    tracks: [['Neon Saints', '3:48'], ['Glass Orchard', '4:02', 'n'], ['Copper Wire', '3:15'], ['Paper Crowns', '3:51', 'n'], ['Satellites', '4:20'], ['Running Lights', '3:33'], ['Coastline', '4:11'], ['Wildfire Season', '3:44'], ['Goodnight, Tokyo', '3:44', 'n']],
    bands: [[350, .1, 3], [15, .13, 3], [355, .06, 2], [55, .12, 1], [330, .05, 2]], cur: 8, liked: [8] },
};
/* Release date (0.6.3 (32); ui/detail/AlbumReleaseDateText.kt). al.date = [year, month?, day?] at the service's
   precision. Proto.state.date === 'year' draws the album as before 0.6.3 (32): the year in the header, no date line,
   no Released / Length on the receipt. */
const albumDated = (al) => al.date && Proto.state.date !== 'year';
const MONTHS = ['January', 'February', 'March', 'April', 'May', 'June', 'July', 'August', 'September', 'October', 'November', 'December'];
/** Header meta: "21.4.16" (two-digit year, no zero padding, dots), "22.11", or the full year. Same in every language. */
function albumHeaderDate(al) {
  if (!albumDated(al)) return String(al.year);
  const [y, m, d] = al.date, yy = String(y % 100).padStart(2, '0');
  return !m ? String(y) : !d ? `${yy}.${m}` : `${yy}.${m}.${d}`;
}
/** The line under the last track (AlbumReleaseDateLine): the locale's own long date, no label. */
function albumDateText(al) {
  const [y, m, d] = al.date;
  if (!m) return String(y);
  return loc(d ? `${MONTHS[m - 1]} ${d}, ${y}` : `${MONTHS[m - 1]} ${y}`, d ? `${y}年${m}月${d}日` : `${y}年${m}月`);
}
function albumDateLine(al) { return albumDated(al) ? `<div class="ad-date num">${albumDateText(al)}</div>` : ''; }
/** The receipt's own English: Released "Apr 16, 2021" / "Nov 2022" / "2019", Length "33 min" / "1 hr 12 min". */
function receiptDateRows(al) {
  if (!albumDated(al)) return [];
  const [y, m, d] = al.date, mon = m ? MONTHS[m - 1].slice(0, 3) : '';
  const sec = al.tracks.reduce((s, t) => s + +t[1].split(':')[0] * 60 + +t[1].split(':')[1], 0), min = Math.round(sec / 60), h = Math.floor(min / 60), r = min % 60;
  return [['Released', !m ? String(y) : !d ? `${mon} ${y}` : `${mon} ${d}, ${y}`], ['Length', h === 0 ? `${min} min` : r === 0 ? `${h} hr` : `${h} hr ${r} min`]];
}
const albumMinutes = (al) => Math.round(al.tracks.reduce((s, t) => s + +t[1].split(':')[0] * 60 + +t[1].split(':')[1], 0) / 60);
function albumDots(page) { return `<div class="ad-dots"><i class="${page === 0 ? 'on' : ''}"></i><i class="${page === 1 ? 'on' : ''}"></i></div>`; }
/** The header; spectrum = the collapsed colour bar (white text, a 48 stamp at the end). */
function albumHeader(al, { page = 0, spectrum = false, stamp = 48 } = {}) {
  const bands = spectrum ? `<div class="ad-spec">${al.bands.map(([h, c, w]) => `<i style="flex:${w};background:oklch(${(38 + (h % 7)).toFixed(0)}% ${c} ${h})"></i>`).join('')}</div>` : '';
  return `<header class="ad-head ${spectrum ? 'spec' : ''}">${bands}<div class="ad-row"><span class="ad-back">${ic('back', 22)}</span><div class="ad-tt"><h2 class="ell">${al.a.t}</h2>` +
    `<div class="ad-meta"><span>${al.a.s}</span><b>${loc('Album', '专辑')}</b><span class="num">${albumHeaderDate(al)}</span></div></div>${spectrum ? cover(al.a.k, 'ad-stamp') : ''}</div>${albumDots(page)}</header>`;
}
function albumEmblem(al, px = 64) { return grooveEmblem(px, { kind: al.kind, score: al.score, rated: al.rated, art: al.a.k }); }
function albumReview(al, { pen = 40 } = {}) {
  return al.review ? `<div class="ad-rev"><p>${al.review[0]}</p><small>${al.review[1]}</small></div>` : `<button class="ad-pen" style="--pen:${pen}px" aria-label="Write a comment">${ic('edit', 18)}</button>`;
}
function albumCount(al, last = true) {
  const m = albumMinutes(al);
  return `<div class="ad-count"><span><b>${al.tracks.length}</b> ${loc('tracks', '首')}</span><span><b>${m}</b> ${loc('min', '分钟')}</span>${last ? `<b class="ad-last">${al.last.join(' ')}</b>` : ''}</div>`;
}
function runOnTitles(al) { return `<p class="ad-run">${al.tracks.map((t, i) => `<span class="${i % 2 ? 'alt' : ''}">${t[0]}</span>`).join(' ')}</p>`; }
function eqGlyph() { return '<span class="ad-eq"><i></i><i></i><i></i></span>'; }
function trackRows(al) {
  return `<div class="ad-rows">${al.tracks.map((t, i) => `<div class="ad-tr ${i === al.cur ? 'cur' : ''}"><span class="ad-n num">${i === al.cur ? eqGlyph() : i + 1}</span>` +
    `<span class="ad-ti ell">${t[0]}${t[2] ? ic('note', 12, 'ad-note') : ''}</span><span class="ad-du num">${t[1]}</span>` +
    `<span class="ad-heart ${al.liked.includes(i) ? 'on' : ''}">${ic(al.liked.includes(i) ? 'favorite_filled' : 'favorite', 18)}</span></div>`).join('')}${albumDateLine(al)}</div>`;
}
/** The butterfly: two arms of the Yoin mark behind the cover — round-ended bars in the cover's primary and
   secondary, each with an inner line in surface @ 65 %. Compact bleeds them off both edges (× 1.35 wide). */
function butterfly(W, top, cv) {
  const x0 = (W - cv) / 2, y = (f) => (top + f * cv).toFixed(0), x = (f) => (x0 + f * cv).toFixed(0);
  return `<svg class="ad-bfly" width="${W}" height="${top + cv + 120}" viewBox="0 0 ${W} ${top + cv + 120}">` +
    `<line x1="${x(-.9)}" y1="${y(.42)}" x2="${x(.12)}" y2="${y(.74)}" class="bf-a" stroke-width="${(cv * .66).toFixed(0)}"/>` +
    `<path d="M${x(-.4)} ${y(.36)}L${x(.05)} ${y(.47)}" class="bf-l"/>` +
    `<line x1="${x(1.6)}" y1="${y(.02)}" x2="${x(.88)}" y2="${y(.86)}" class="bf-b" stroke-width="${(cv * .52).toFixed(0)}"/>` +
    `<path d="M${x(1.6)} ${y(.3)}Q${x(1.1)} ${y(.36)} ${x(1)} ${y(.4)}M${x(.8)} ${y(1.04)}l${(cv * .04).toFixed(0)} -${(cv * .08).toFixed(0)}" class="bf-l"/></svg>`;
}
/** Page 1. mode: compact | medium; pulled: the collapsed state (spectrum bar + rows). */
function albumOverview(al, { mode = 'compact', pulled = false, W = 412 } = {}) {
  if (mode === 'compact' && !pulled) {
    const cv = Math.min(300, Math.round(W * .74));
    return `<div class="ad ad-c">${albumHeader(al)}<div class="ad-hero" style="--cv:${cv}px">${butterfly(W, 0, cv)}${cover(al.a.k, 'ad-cover')}</div>` +
      `<div class="ad-below" style="--cv:${cv}px"><div class="ad-erow">${albumEmblem(al)}${albumReview(al)}</div>${albumCount(al)}${runOnTitles(al)}</div></div>`;
  }
  if (mode === 'compact') return `<div class="ad ad-c pulled">${albumHeader(al, { spectrum: true })}<div class="ad-list">${albumCount(al, false)}${trackRows(al)}</div></div>`;
  const hero = `<div class="ad-mhero">${cover(al.a.k, 'ad-mcover')}<div class="ad-mmeta"><div class="ad-mtop"><div class="ad-lp"><u>Last Play</u><span>${al.last[0]}</span><small class="num">${al.last[1]}</small></div>${albumEmblem(al)}</div>${albumReview(al)}</div></div>`;
  return `<div class="ad ad-m ${pulled ? 'pulled' : ''}">${albumHeader(al, { spectrum: pulled })}<div class="ad-mlist">${pulled ? '' : hero}${albumCount(al, false)}${trackRows(al)}</div></div>`;
}
/** Bottom bars that carry Play (DetailBottomBar). Play = an M3 split button: ▶ Play | ▾ (2 apart, inner corners 4),
   cover primary / onPrimary. compact: [Play ▾ 156][pill]; medium: centred, [Play ▾][Shuffle][Artist][Share][pill 200];
   shell: the Expanded merged bar (≤ 720) [Home][Library][pill][Play ▾ 156][Shuffle]. */
function playSplit(fixed) { return `<span class="pl-split ${fixed ? 'fixed' : ''}"><button class="pl-a">${ic('play_filled', 20)}<span>${loc('Play', '播放')}</span></button><button class="pl-b" aria-label="More play options">${ic('chevron_down', 20)}</button></span>`; }
function detailBar(size, { mode = size.cls === 'compact' ? 'compact' : 'medium', playing = null, title = '' } = {}) {
  const pill = playing ? `<button class="bb-pill">${cover(playing.k, 'bb-art')}<span class="bb-pt"><b class="ell">${title}</b><span class="ell">${playing.s}</span></span></button>`
    : `<button class="bb-pill none">${ic('music_note', 22)}<span>Nothing playing</span></button>`;
  const round = (i, l) => `<button class="bb-btn bb-r" aria-label="${l}">${ic(i, 22)}</button>`;
  if (mode === 'compact') return `<nav class="bbar dbar">${playSplit(true)}${pill}</nav>`;
  if (mode === 'shell') return `<nav class="bbar centred dbar shell"><button class="bb-btn bb-nav">${ic('home', 22)}<span>Home</span></button><button class="bb-btn bb-nav on">${ic('library_filled', 22)}<span>Library</span></button>${pill}${playSplit(true)}${round('shuffle', 'Shuffle play')}</nav>`;
  return `<nav class="bbar centred dbar">${playSplit(false)}${round('shuffle', 'Shuffle play')}${round('artist', 'Go to artist')}${round('share', 'Share')}${pill}</nav>`;
}
/** The album page in a box W wide: page 0 overview / 1 scrapbook (needs the scrapbook component). */
function albumPane(key, { page = 0, pulled = false, editing = false, W = 412, popup = '' } = {}) {
  const al = ALBUMS[key], mode = W >= 600 ? 'medium' : 'compact';
  if (page === 0) return albumOverview(al, { mode, pulled, W });
  return `<div class="ad ad-sb">${albumHeader(al, { page: 1 })}<div class="ad-scroll">${scrapbook(key, { wide: W >= 600, editing, popup })}</div></div>`;
}
/** Expanded list-detail (PaneSplit): shell 600 | 24 gutter with the drag handle | detail; one page gradient, no cards. */
function paneSplit(shell, detail) {
  return `<div class="split"><div class="split-l">${shell}</div><div class="split-g"><i></i></div><div class="split-r">${detail}</div></div>`;
}
