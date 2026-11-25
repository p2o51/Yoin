/* Home's card vocabulary (ui/home: HomeEditorialContent, HomeWidgetGrid, RediscoverSection, HomeAlbumMemoryMark).
   Every cover sits on its entity's backdrop shape (WidgetBackdropArtwork): the MaterialShape fills the box in
   the cover's base colour and the cover sits bottom-end at 72 %. Filled cards wash 30 % of that base into
   surfaceContainerLow (CARD_WASH_FRACTION). Ink (scores, eyebrows) = accent in dark, base in light. */
function backdrop(item, px, extra = '') {
  const a = artVars(item.k);
  if (item.kind === 'artist') return `<span class="bd bd-artist" style="--bd:${px}px;${a}">${cover(item.k, 'bd-round')}${extra}</span>`;
  return `<span class="bd" style="--bd:${px}px;${a}">${shapeSvg(KIND_SHAPE[item.kind] || 'Bun', 'bd-shape')}${cover(item.k, 'bd-in')}${extra}</span>`;
}
/** Album memory mark (T4): a 12-sided cookie on a page-colour disc at the cover's top-end corner.
   solid = album score, ring = track average, dashed = notes only. No digits. */
function memMark(type = 'solid') { return `<span class="mmark ${type}">${shapeSvg('Cookie12Sided')}</span>`; }
function card(cls, item, inner) { return `<div class="hc ${cls}" style="${artVars(item.k)}">${inner}</div>`; }
const col = (...rows) => `<span class="col">${rows.join('')}</span>`;

/* Activities (ActivitiesBento) */
function actHero(item, when, foot) {
  return card('act-hero', item, backdrop(item, 96) + col(`<span class="lab">${when}</span>`, `<span class="h-title ell">${item.t}</span>`, `<span class="sub ell">${item.s}</span>`,
    `<span class="fn">${foot.map((f) => `<span>${f}</span>`).join('')}</span>`));
}
function actSmall(item, kindLabel, when) {
  return card('act-small', item, `<span class="as-top">${backdrop(item, 48)}${col(`<span class="lab">${kindLabel}</span>`, `<span class="lab">${when}</span>`)}</span><span class="s-title">${item.t}</span>`);
}
function actWide(item, when, sub) {
  return card('act-wide', item, backdrop(item, item.kind === 'artist' ? 80 : 80) + col(`<span class="lab">${when}</span>`, `<span class="w-title ell">${item.t}</span>`, `<span class="sub ell">${sub || item.s}</span>`));
}
function actStrip(item, when) { return card('act-strip', item, `<span class="ell"><b>${item.t}</b><span class="dim">${item.s}</span></span><span class="lab">${when}</span>`); }

/* Jump Back In (HomeWidgetGrid): 1×1 = backdrop + title / subtitle; 1×2 = that plus the signal column
   (score or N/A in the cover's ink, the date, and the latest note). */
function jbiCell(item, opts = {}) {
  return `<div class="jc" style="${artVars(item.k)}">${backdrop(item, opts.px || 100, opts.mark ? memMark(opts.mark) : '')}<span class="jt ell">${item.t}</span><span class="js ell">${item.s}</span></div>`;
}
function jbiWide(item, score, date, note, opts = {}) {
  return `<div class="jw" style="${artVars(item.k)}">${jbiCell(item, opts)}<span class="col jw-r"><span class="rate"><b class="num">${score}</b><span>${date}</span></span>${note ? `<span class="jnote">${note}</span>` : ''}</span></div>`;
}

/* Recently Added: track tiles (52 cover) and album cards (82 backdrop) */
function raTile(item) { return `<div class="ra-tile">${cover(item.k, 'c52')}<span class="col"><span class="tt ell">${item.t}</span><span class="lab ell">${item.s}</span></span></div>`; }
function shelfCard(item, sub, mark) { return `<div class="shelf-card" style="${artVars(item.k)}">${backdrop(item, 82, mark ? memMark(mark) : '')}<span class="at ell">${item.t}</span><span class="lab ell">${sub ?? item.s}</span></div>`; }

/* Rediscover (RediscoverSection): eyebrow (why it's back), title, artist, footnote; a score badge hangs off
   the backdrop's corner (solid = album score, outlined = track average). Song cards: Circle 76, a note excerpt
   with the 2dp track-note rule. */
function rediscoverCard(item, { eyebrow, foot, score, solid = true, note }) {
  const badge = score ? `<span class="rbadge ${solid ? '' : 'avg'} num">${score}</span>` : '';
  const song = item.kind === 'song';
  return card('redis' + (song ? ' redis-song' : ''), item, backdrop(item, song ? 76 : 104, badge) +
    col(`<span class="eyebrow">${eyebrow}</span>`, `<span class="r-title ell">${item.t}</span>`, `<span class="sub ell">${item.s}${song ? ' · ' + (item.album || '') : ''}</span>`,
      note ? `<span class="rnote ell">${note}</span>` : `<span class="fnote ell">${foot}</span>`));
}
