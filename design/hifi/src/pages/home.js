/* Page: Home (ui/home/HomeScreen). Sections in the 0.6.3 default order; Compact = one column (16 page
   margin), Medium = one column capped at 688, Expanded = two columns with the large-screen default pairs
   (Activities | Jump Back In, Your Playlists | Rediscover; Recently Added runs full width here because Lyric
   Cards is hidden). Edit Home shows the in-place editor statically: plates, hide + drag badges, the ⌟ rows
   handle, the Hidden tray, the [Undo][Done] bar. */
const HOME_CTL = [
  { key: 'edit', label: 'Edit Home', options: [['0', 'Off'], ['1', 'On']], def: '0' },
  { key: 'bubble', label: 'Bubble', options: [['off', 'Off'], ['memory', 'Memory'], ['tip', 'Tip']], def: 'off' },
  { key: 'playing', label: 'Playing', options: [['glass', 'Glass Orchard'], ['yehang', '夜航'], ['none', 'Nothing']], def: 'glass' },
];
const PLAYING = { glass: [CAT.glassAlbum, 'Goodnight, Tokyo'], yehang: [CAT.yehang, '灯塔'] };

function homeLayout(size) {
  const W = size.w;
  if (size.cls === 'compact') return { pad: 16, cw: W - 32, two: false };
  if (size.cls === 'medium') { const cw = Math.min(688, W - 48); return { pad: (W - cw) / 2, cw, two: false }; }
  return { pad: 32, cw: W - 64, two: true, half: (W - 64 - 20) / 2 };
}

function secActivities(L, w) {
  const C = CAT;
  const hero = actHero(C.glassAlbum, '17h ago', ['<b>2019</b>', '<b>9</b>songs', '<b>35</b>min']);
  if (L.cls === 'compact') {
    return `<div class="acts">${hero}<div class="act-row">${actSmall(C.soda, 'Album', '1w ago')}${actWide(C.harbor, '18h ago')}</div>${actStrip(C.velvet, '8h ago')}</div>`;
  }
  const row1 = `<div class="act-row r1">${hero}${actWide(C.harbor, '18h ago')}</div>`;
  const row2 = `<div class="act-row">${actSmall(C.soda, 'Album', '1w ago')}${actWide(C.meridians, '1w ago', 'Artist')}${actSmall(C.rain, 'Playlist', '1w ago')}</div>`;
  const strips = L.cls === 'medium' ? `<div class="strips">${actStrip(C.velvet, '8h ago')}${actStrip(C.lowtide, '9h ago')}</div>` : '';
  return `<div class="acts">${row1}${row2}${strips}</div>`;
}

function secJbi(L, w) {
  const C = CAT;
  const n = { slow: 'Feels like Glass Orchard\nreplay at night', intro: 'Intro\nloud drums like Glass Orchard', sat: 'the bridge echoes\nNeon Saints again' };
  if (L.cls === 'compact') {
    const cell = (w - 24) / 3, px = Math.min(100, Math.round(cell));
    const o = { px };
    return `<div class="jbi" style="--cell:${cell}px">${[
      jbiWide(C.harbor, '8.4', 'Nov 23', '', o), jbiCell(C.north, o),
      jbiCell(C.glassAlbum, { px, mark: 'solid' }), jbiCell(C.under, o), jbiCell(C.rain, o),
      jbiCell(C.yehang, { px, mark: 'dash' }), jbiWide(C.slowtide, 'N/A', 'Nov 23', n.slow, o),
      jbiCell(C.late, o), jbiCell(C.soda, o), jbiCell(C.aurora, o)].join('')}</div>`;
  }
  // tablet templates (HomeJbiTemplate): unit columns with staggered offsets, measured off the device shots
  const cols = 5, gap = 12, cell = (w - gap * (cols - 1)) / cols, pitch = cell + gap, px = Math.round(Math.min(112, cell * .86));
  const T = L.cls === 'medium' ? [
    ['w', C.harbor, 0, 0, '8.4', ''], ['c', C.glassAlbum, 0, 168, 'solid'], ['c', C.yehang, 0, 338], ['c', C.under, 1, 168], ['c', C.late, 1, 338],
    ['c', C.rain, 2, 85], ['c', C.focus, 2, 254], ['c', C.sunday, 2, 424], ['c', C.north, 3, 42], ['c', C.snow, 4, 42],
    ['w', C.glassSong, 3, 212, 'N/A', n.intro], ['c', C.soda, 3, 382], ['c', C.aurora, 4, 382]]
    : [['w', C.harbor, 0, 0, '8.4', ''], ['w', C.glassSong, 2, 0, 'N/A', n.intro], ['w', C.satellites, 0, 164, 'N/A', n.sat], ['w', C.slowtide, 2, 164, 'N/A', n.slow], ['c', C.north, 4, 164, 'solid']];
  const H = Math.max(...T.map((t) => t[3])) + px + 48;
  return `<div class="jbi abs" style="height:${H}px;--cell:${cell}px">${T.map((t) => {
    const pos = `left:${t[2] * pitch}px;top:${t[3]}px;width:${t[0] === 'w' ? cell * 2 + gap : cell}px`;
    const html = t[0] === 'w' ? jbiWide(t[1], t[4], 'Nov 23', t[5], { px }) : jbiCell(t[1], { px, mark: t[4] });
    return html.replace(/^<div class="(j[cw])" style="/, `<div class="$1" style="${pos};`);
  }).join('')}</div>`;
}

function secRecentlyAdded(L, w) {
  // HomeFeedDensity: phone 2 × 2; the 800 portrait (Capped feed, cover columns 5) may use 3 columns × 4 rows,
  // but recentlyAddedGridColumns packs the demo's six tracks into just enough columns: 2 × 3, as on the device.
  // Albums stack two high beside a four-row grid (recentlyAddedAlbumStack). Expanded = the two-column grid.
  const C = CAT, tracks = [C.lanterns, { ...C.blue, kind: 'song' }, { ...C.yehang, kind: 'song' }, C.glassSong, C.slowdance, { ...C.soda, kind: 'song' }, C.aurora, C.huisheng];
  const cols = L.cls === 'compact' ? 2 : L.cls === 'medium' ? 2 : 4;
  const shown = L.cls === 'compact' ? 4 : L.cls === 'medium' ? 6 : 8;
  const gw = L.cls === 'compact' ? Math.round((w + 32 - 14) * 2.6 / 3.6) : L.cls === 'medium' ? cols * 166 + (cols - 1) * 8 : w;
  const grid = `<div class="ra-grid" style="width:${gw}px;grid-template-columns:repeat(${cols},minmax(0,1fr))">${tracks.slice(0, shown).map(raTile).join('')}</div>`;
  const albums = [shelfCard(C.yehang, null, 'dash'), shelfCard(C.paper), shelfCard(C.velvet, null, 'ring'), shelfCard(C.north, null, 'solid'), shelfCard(C.blue), shelfCard(C.soda)];
  const stacked = L.cls === 'medium' ? albums.reduce((a, c, i) => (i % 2 ? a[a.length - 1].push(c) : a.push([c]), a), []).map((p) => `<div class="ra-stack">${p.join('')}</div>`) : albums;
  return `<div class="shelf ra-shelf">${grid}${stacked.join('')}</div>`;
}

function secRediscover(L, w) {
  const C = CAT, cw = L.cls === 'compact' ? Math.round(w * .86) : Math.round((w - 12) / 2);
  // RediscoverSection: the eyebrow = an optional reason line over "N months away"; footnote = first-play month
  // (yyyy.MM) · plays.
  const cards = [
    rediscoverCard(C.north, { eyebrow: '7 months away', foot: '2026.04 · 23 plays', score: '8.9' }),
    rediscoverCard(C.blue, { eyebrow: '4 months away', foot: '2026.07 · 41 plays', score: '7.6', solid: false }),
    rediscoverCard({ ...C.huisheng, album: '夜航' }, { eyebrow: '3 notes<br>5 months away', note: 'the hum under the second verse' }),
    rediscoverCard(C.paper, { eyebrow: 'Reviewed<br>6 months away', foot: '2026.06 · 12 plays' }),
  ];
  return `<div class="shelf redis-shelf" style="--rw:${cw}px">${cards.join('')}</div>`;
}

function secPlaylists() {
  const C = CAT;
  return `<div class="shelf">${[[C.rain, '24 songs'], [C.focus, '41 songs'], [C.late, '32 songs'], [C.sunday, '18 songs'], [{ ...C.harbor, t: 'Harbor Mix', kind: 'playlist' }, '27 songs']].map(([it, s]) => shelfCard(it, s)).join('')}</div>`;
}

const HOME_SECTIONS = {
  activities: { title: 'Activities', build: secActivities, rows: true },
  jump_back_in: { title: 'Jump Back In', build: secJbi, rows: true },
  recently_added: { title: 'Recently Added', build: secRecentlyAdded },
  rediscover: { title: 'Rediscover', build: secRediscover },
  your_playlists: { title: 'Your Playlists', build: secPlaylists },
};
const HIDDEN_TRAY = [['Lyric Cards', 'Tap a line to jump in'], ['All Memories', 'Every note and score, on a grid that grows with you'], ['Recently Played', 'Albums you played lately, on any device']];

function block(id, L, w, edit) {
  const s = HOME_SECTIONS[id];
  const badges = edit ? `<span class="badges"><span class="bv">${ic('visibility_off', 18)}</span><span class="bh">${ic('drag_handle', 22)}</span></span>` : '';
  const corner = edit && s.rows ? '<svg class="rows-corner" viewBox="0 0 24 24"><path d="M19 5.5V12.5A6.5 6.5 0 0 1 12.5 19H5.5"/></svg>' : '';
  return `<section class="blk" data-id="${id}">${edit ? '<span class="plate"></span>' : ''}<div class="sec-head"><h2>${s.title}</h2>${badges}</div>${s.build({ cls: L.cls }, w)}${corner}</section>`;
}

function renderHome(st, size) {
  const L = homeLayout(size), edit = st.edit === '1', cls = size.cls;
  const order = L.two ? [['activities', 'jump_back_in'], ['recently_added'], ['your_playlists', 'rediscover']] : [['activities'], ['jump_back_in'], ['recently_added'], ['rediscover'], ['your_playlists']];
  const rows = order.map((r) => r.length === 2
    ? `<div class="pair">${r.map((id) => block(id, { cls }, L.half, edit)).join('')}</div>`
    : block(r[0], { cls }, L.cw, edit)).join('');
  const bubbleOn = !edit && st.bubble !== 'off';
  const greet = `<div class="hhead ${bubbleOn && st.bubble === 'memory' ? 'speaking' : ''}"><div class="htitle"><span class="greet rond">${edit ? '' : 'Good evening'}</span><h1 class="rond">${edit ? 'Edit Home' : 'Home'}${edit ? '<span class="dhint">Drag to reorder</span>' : ''}</h1></div>` +
    (edit ? `<button class="hicon" aria-label="Collapse to list">${ic('view_list', 22)}</button>` : `<button class="avatar" aria-label="Account"><span>D</span></button>`) + '</div>';
  const tray = edit ? `<div class="tray"><h3>Hidden</h3>${HIDDEN_TRAY.map(([t, s]) => `<div class="tray-row"><span class="col"><b class="ell">${t}</b><span class="ell">${s}</span></span><span class="plus">${ic('add', 22)}</span></div>`).join('')}<button class="reset">${ic('refresh', 18)}Reset Home</button></div>`
    : `<button class="edit-entry">${ic('edit', 18)}Edit Home</button>`;
  const p = PLAYING[st.playing];
  const bar = bottomBar({ size, edit, playing: p ? p[0] : null, title: p ? p[1] : '' });
  return `<div class="home ${edit ? 'editing' : ''}" style="--pad:${L.pad}px;--cw:${L.cw}px">
    <div class="hscroll"><div class="feed">${greet}<div class="blocks">${rows}</div>${tray}</div></div>
    ${edit ? '' : memoryChevron()}${bubbleOn ? memoryBubble(st.bubble) : ''}${barDissolve(size)}${bar}</div>`;
}

Proto.init({
  title: 'Home',
  controls: HOME_CTL,
  scheme: (st) => (st.playing === 'none' ? 'wallpaper' : ART[PLAYING[st.playing][0].k].seed),
  render: renderHome,
});
