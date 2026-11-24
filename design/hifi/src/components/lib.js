/* Library (ui/library: LibraryScreen, LibraryFilterRow, LibrarySortRow, LibrarySparseGrid, LibraryFastScroller).
   Header: Compact = the M3 AppBarWithSearch band, no title: 56 search pill (surfaceContainerHighest) + the account
   avatar (32 in a 48 target; the gear only without an account). Medium = "Library" headlineLarge · pill · avatar,
   chips on their own row, 32 from the edge. Expanded = one row: "Library" headlineMedium · 360 pill · chips · avatar.
   Chips: Playlists Artists Albums Songs (Favorites on Subsonic), min 44, r 18, 16 / 10, labelLarge, 8 apart;
   off = surfaceContainerHigh / onSurfaceVariant, on = secondaryContainer; with one on, a 44 ✕ unfolds first.
   No chip = All: artists (round), albums and playlists (square, r 4) in one grid; sort row first (Recents ▾).
   Grid: 16 padding, 12 / 16 gaps, label titleSmall + bodySmall. Compact 3 columns, otherwise adaptive ≥ 96. */
const LIB_ALL = ['glassAlbum', 'meridians', 'rain', 'harbor', 'yehang', 'kiri', 'north', 'focus', 'soda', 'linjian', 'velvet', 'blue', 'late', 'ada', 'lowtide', 'paper', 'sora', 'sunday', 'baizhou', 'tidepool', 'postcards', 'smallhours', 'harborMix'].map((k) => CAT[k]);
const LIB_CHIPS = [['playlists', 'Playlists'], ['artists', 'Artists'], ['albums', 'Albums'], ['songs', 'Songs'], ['favorites', 'Favorites']];
function libAvatar() { return `<span class="lib-av" aria-label="Account">${shapeSvg('Cookie6Sided') || ''}<span>D</span></span>`; }
function libPill(ph = 'Search library') { return `<div class="lib-pill">${ic('search', 24)}<span>${ph}</span></div>`; }
function libChips(active) {
  return `<div class="lib-chips">${active !== 'all' ? `<span class="lib-x" aria-label="Clear filter">${ic('close', 20)}</span>` : ''}${LIB_CHIPS.map(([k, l]) => `<span class="chip ${k === active ? 'on' : ''}">${l}</span>`).join('')}</div>`;
}
function libHeader(cls, active) {
  if (cls === 'compact') return `<div class="lib-head c"><div class="lib-band">${libPill()}${libAvatar()}</div>${libChips(active)}</div>`;
  if (cls === 'expanded') return `<div class="lib-head x"><h1>Library</h1>${libPill()}${libChips(active)}${libAvatar()}</div>`;
  return `<div class="lib-head m"><div class="lib-band"><h1>Library</h1>${libPill()}${libAvatar()}</div>${libChips(active)}</div>`;
}
function libSort(name = 'Recents', open = false) {
  return `<div class="lib-sort">${name}${ic('sort', 18)}${open ? `<div class="lib-menu">${['Recents', 'Recently added', 'Alphabetical', 'Creator'].map((s) => `<span>${s}${s === name ? ic('check', 20) : ''}</span>`).join('')}</div>` : ''}</div>`;
}
function libTile(it) {
  const round = it.kind === 'artist';
  const sub = round ? '' : it.kind === 'playlist' ? 'demo' : it.s;
  return `<div class="lt ${round ? 'round' : ''}">${cover(it.k, 'lt-cv')}<span class="lt-t ell">${it.t}</span>${sub ? `<span class="lt-s ell">${sub}</span>` : ''}</div>`;
}
function libGrid(items, cols) { return `<div class="lgrid" style="grid-template-columns:repeat(${cols},minmax(0,1fr))">${items.map(libTile).join('')}</div>`; }
/** Songs row: 68 tall, 48 thumb, titleSmall, artist + album @ 60 % (12 apart), duration; current = primary + EQ. */
function libSongRow([t, a, al, k, d], { cur = false, note = false } = {}) {
  return `<div class="ls ${cur ? 'cur' : ''}">${cover(k, 'ls-cv')}<span class="col"><span class="ls-t">${cur ? eqGlyph() : ''}<span class="ell">${t}</span>${note ? ic('note', 12, 'ls-n') : ''}</span><span class="ls-m ell">${a}<span>${al}</span></span></span><span class="ls-d num">${d}</span></div>`;
}
/** Fast scroller: 24 × 48 handle on the end edge (round start, r 4 end); held = primary + tick column + bubble. */
function fastScroller(held, top = 260) {
  const ticks = ['Today', 'This week', 'This month', 'Oct', 'Sep', 'Not opened'];
  return `<div class="fs ${held ? 'held' : ''}" style="top:${top}px"><span class="fs-h">${ic('chevron_up', 14)}${ic('chevron_down', 14)}</span>` +
    (held ? `<div class="fs-ticks">${ticks.map((t, i) => `<span class="${i === 2 ? 'on' : ''}">${t}</span>`).join('')}</div><span class="fs-bub">This month</span>` : '') + '</div>';
}
