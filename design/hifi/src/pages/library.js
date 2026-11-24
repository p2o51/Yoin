/* Page: Library (ui/library/LibraryScreen) + its search surface + the Expanded list-detail.
   All (no chip) = artists, albums and playlists mixed, sorted Recents. Compact 3 columns; Medium adaptive ≥ 96
   (6 columns at 800); Expanded 11 columns. With an album open on Expanded the shell column is 600 (Medium header,
   5 columns), the 24 gutter holds the drag handle, the album fills 656 in the Medium album layout.
   Search (long-press Library or the pill): full-screen M3 search, type chips All · Songs · Albums · Memories ·
   Artists (Subsonic: no scope pair, no Playlists), the "All" page laid out as Jump Back In tiles. */
const LIB_CTL = [
  { key: 'view', label: 'View', options: [['all', 'All'], ['playlists', 'Playlists'], ['artists', 'Artists'], ['albums', 'Albums'], ['songs', 'Songs'], ['favorites', 'Favorites']], def: 'all' },
  { key: 'sort', label: 'Sort menu', options: [['closed', 'Closed'], ['open', 'Open']], def: 'closed' },
  { key: 'scroller', label: 'Fast scroller', options: [['off', 'Hidden'], ['rest', 'Shown'], ['held', 'Dragging']], def: 'off' },
  { key: 'search', label: 'Search', options: [['off', 'Off'], ['on', '"Harbor"']], def: 'off' },
  { key: 'detail', label: 'Open album', options: [['none', 'None'], ['harbor', 'Harbor Lights']], def: 'none' },
];
const LIB_SONGS = [['Night Ferry', 'Kiri Ono', 'Harbor Lights', 'harbor', '3:31'], ['Morning Static', 'Kiri Ono', 'Harbor Lights', 'harbor', '4:05'], ['Slow Tide', 'Kiri Ono', 'Harbor Lights', 'harbor', '3:59'],
  ['灯塔', '林间', '夜航', 'yehang', '4:07'], ['回声', '林间', '夜航', 'yehang', '3:36'], ['Goodnight, Tokyo', 'The Meridians', 'Glass Orchard', 'glass', '3:44'], ['Satellites', 'The Meridians', 'Glass Orchard', 'glass', '4:20'],
  ['IV. Snowfield', 'Ada Vale', 'Northern Field Recordings', 'north', '4:37'], ['V. Aurora', 'Ada Vale', 'Northern Field Recordings', 'north', '4:29'], ['Undertow', 'Kiri Ono', 'Blue Hour', 'under', '3:12'],
  ['Midnight Radio', 'Sora Hayama', '午後のソーダ', 'soda', '3:58'], ['Slow Dance in a Fast Car', 'The Meridians', 'Velvet Hours', 'slow', '3:27']];
function libCols(size, W) { return size.cls === 'compact' ? 3 : Math.max(3, Math.floor((W - 64 + 12) / (96 + 12))); }
function libView(st, size, W) {
  const cols = libCols(size, W), v = st.view;
  const pick = (kind) => LIB_ALL.filter((x) => x.kind === kind);
  if (v === 'songs') return LIB_SONGS.map((s, i) => libSongRow(s, { cur: i === 0, note: i === 2 || i === 3 })).join('');
  if (v === 'playlists') {
    const meta = { rain: '24 tracks  1 h 22 min', focus: '41 tracks  2 h 48 min', late: '32 tracks  1 h 57 min', sunday: '18 tracks  58 min', harbor: '27 tracks  1 h 41 min' };
    return libSort('Recents', st.sort === 'open') + pick('playlist').map((p) => `<div class="ls">${cover(p.k, 'ls-cv')}<span class="col"><span class="ls-t"><span class="ell">${p.t}</span></span><span class="ls-m">demo<span>${meta[p.k]}</span></span></span></div>`).join('');
  }
  if (v === 'favorites') {
    const art = [CAT.meridians, CAT.kiri].map((a, i) => `<div class="ls">${cover(a.k, 'ls-cv round')}<span class="col"><span class="ls-t">${a.t}</span></span><span class="lib-mpill">${['3 albums', '4 albums'][i]}</span></div>`).join('');
    return `<div class="lib-sec">Artists</div>${art}<div class="lib-sec">Albums</div>${[CAT.glassAlbum, CAT.harbor].map((a) => `<div class="ls">${cover(a.k, 'ls-cv')}<span class="col"><span class="ls-t">${a.t}</span><span class="ls-m">${a.s}</span></span></div>`).join('')}<div class="lib-sec">Songs</div>${LIB_SONGS.slice(2, 6).map((s) => libSongRow(s)).join('')}`;
  }
  const items = v === 'artists' ? pick('artist').map((a, i) => ({ ...a, sub: `${[3, 5, 2, 3, 2][i]} albums` })) : v === 'albums' ? pick('album') : LIB_ALL;
  const grid = v === 'artists' ? `<div class="lgrid" style="grid-template-columns:repeat(${cols},minmax(0,1fr))">${items.map((a) => libTile(a).replace('</div>', `<span class="lt-s">${a.sub}</span></div>`)).join('')}</div>` : libGrid(items, cols);
  return libSort(v === 'artists' ? 'Recents' : 'Recents', st.sort === 'open') + grid;
}
function libScreen(st, size, W, cls) {
  const sc = st.scroller !== 'off' && ['all', 'artists', 'albums'].includes(st.view) ? fastScroller(st.scroller === 'held', cls === 'compact' ? 330 : 300) : '';
  const fab = st.view === 'playlists' ? `<div class="lib-fab">${ic('add', 24)}New playlist</div>` : '';
  return `<div class="lib">${libHeader(cls, st.view)}<div class="lib-body">${libView(st, size, W)}</div>${sc}${fab}</div>`;
}
/** The search surface for "Harbor": best match, then sections as Jump Back In tiles (1×1 / 1×2 memory cards). */
function libSearch(size) {
  const W = size.cls === 'expanded' ? size.w : size.w, cols = size.cls === 'compact' ? 3 : Math.min(8, Math.max(3, Math.floor((W - 32 + 12) / 108)));
  const pad = size.cls === 'compact' ? 16 : 32, cell = (W - pad * 2 - 12 * (cols - 1)) / cols, px = Math.min(100, Math.round(cell));
  const o = { px }, sec = (t, body) => `<div class="srch-sec"><div class="srch-h"><b>${t}</b><span>Show all</span></div><div class="jbi" style="grid-template-columns:repeat(${cols},minmax(0,1fr));--cell:${cell}px">${body}</div></div>`;
  const best = `<div class="srch-best" style="${artVars('harbor')}">${backdrop(CAT.harbor, 104, memMark('solid'))}<span class="col"><small>Best match</small><b>Harbor Lights</b><span>Album · Kiri Ono · 2021</span></span></div>`;
  const songs = [jbiWide({ ...CAT.lanterns, t: 'Morning Static' }, '0:34', 'Your note', 'Static opening before the drums', o), jbiCell({ t: 'Night Ferry', s: 'Kiri Ono', k: 'harbor', kind: 'song' }, o), jbiCell({ t: 'Harbor Lights', s: 'Kiri Ono', k: 'harbor', kind: 'song' }, o), jbiCell(CAT.slowtide, o), jbiCell({ t: 'Paper Lanterns', s: 'Kiri Ono', k: 'harbor', kind: 'song' }, o)];
  const albums = [jbiWide(CAT.glassAlbum, '7.9', 'Review', 'Late train home with <mark>Harbor</mark> Lights', o), jbiCell({ ...CAT.harbor }, { px, mark: 'solid' }), jbiCell(CAT.harborMix, o)];
  return `<div class="srch"><div class="srch-bar">${ic('back', 24)}<span class="srch-q">Harbor<i class="caret"></i></span>${ic('close', 24)}</div><div class="srch-chips"><span class="chip on">All</span><span class="chip">Songs</span><span class="chip">Albums</span><span class="chip">Memories</span><span class="chip">Artists</span></div>` +
    `<div class="srch-body" style="padding:0 ${pad}px">${best}${sec('Songs', songs.join(''))}${sec('Albums', albums.join(''))}${sec('Artists', jbiCell(CAT.kiri, o))}</div></div>`;
}
function renderLib(st, size) {
  if (st.search === 'on') return libSearch(size);
  const open = st.detail !== 'none';
  if (open && size.cls === 'expanded') {
    return `${paneSplit(libScreen(st, size, 600, 'medium'), albumPane('harbor', { W: 656 }))}${barDissolve(size)}${detailBar(size, { mode: 'shell', playing: { k: NOW_PLAYING.k, s: NOW_PLAYING.s }, title: NOW_PLAYING.t })}`;
  }
  if (open) return `${albumPane('harbor', { W: size.w })}${barDissolve(size)}${detailBar(size, { playing: { k: NOW_PLAYING.k, s: NOW_PLAYING.s }, title: NOW_PLAYING.t })}`;
  return `${libScreen(st, size, size.w, size.cls)}${barDissolve(size)}${bottomBar({ size, active: 'library', playing: CAT.yehang, title: '灯塔' })}`;
}
Proto.init({ title: 'Library', controls: LIB_CTL, scheme: (st) => (st.detail !== 'none' ? 'harbor' : 'yehang'), render: renderLib });
