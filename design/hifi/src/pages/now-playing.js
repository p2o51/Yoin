/* Page: Now Playing (ui/nowplaying, NowPlayingPresentation).
   Compact  = the phone column (collapsed) · the full-screen lyrics page · the Note page.
   Medium   = the Spotify-style side panel (clamp(W × 0.5, 360, 420), left corners 28) over the album page;
              the column inside is the phone one; ⤢ goes to the enlarged phone (not drawn here).
   Expanded = dual-pane TabletNP: left stack (cover + rating column, transport, title at the bottom),
              right column Lyrics / About / Note with the tool row; queue / devices / write as top-end icons.
   Sing (toggle): rating bar → vocal slider and heart → mic-off exit where the lyric tools aren't on screen;
   the tool row's 5th key becomes the short capsule where they are. Colour = the cover's scheme. */
const NP_CTL = [
  { key: 'view', label: 'View', options: [['player', 'Player'], ['lyrics', 'Lyrics'], ['note', 'Note']], def: 'player' },
  { key: 'tab', label: 'Tab', options: [['lyrics', 'Lyrics'], ['about', 'About'], ['note', 'Note']], def: 'lyrics' },
  { key: 'sing', label: 'Sing', options: [['0', 'Off'], ['1', 'On']], def: '0' },
  { key: 'cover', label: 'Cover', options: [['glass', 'Glass Orchard'], ['yehang', '夜航'], ['harbor', 'Harbor Lights']], def: 'glass' },
  // how a saved @ mention draws: the cover pill (0.6.3 (32) on) or, for before/after figures, the coloured name it was
  { key: 'mentions', label: '@ mentions', options: [['pill', 'Cover pill'], ['text', 'Before 0.6.3 (32)']], def: 'pill' },
];

/** The phone column at width W (412 phone, 400 panel). */
function npColumn(st, W, { panel = false } = {}) {
  const key = st.cover, t = NP_TRACKS[key], sing = st.sing === '1';
  const end = panel ? `<button class="np-round" aria-label="Full screen">${ic('open_in_full', 20)}</button>` : '';
  if (st.view === 'lyrics' || st.view === 'note') {
    const lyrics = st.view === 'lyrics';
    return `<div class="npcol full">${npTopBar(t, { docked: true, end })}${tabGroup(st.view)}` +
      (lyrics ? lyricList(key, { mode: 'full' }) : notesHeader() + noteLines(t.pos)) +
      `<div class="np-foot">${npHero(t)}${lyrics ? toolRow({ sing }) : writeBar(t.pos)}</div></div>`;
  }
  const coverPx = Math.min(312, W - 48 - 68);
  const win = st.tab === 'about' ? aboutBlock(t) : st.tab === 'note' ? noteLines(t.pos, { mode: 'compact' }) : lyricList(key);
  return `<div class="npcol">${npTopBar(t, { end })}${npCoverRating(t, coverPx, { sing })}<div class="np-mid">${textTabs(st.tab)}${win}</div>` +
    `${npTransport(t, W - 48)}${npHero(t)}${npPills()}</div>`;
}

/** Medium: what the panel opened over — the album page (AlbumDetailScreen, the type-set track list). */
function albumPage(st) {
  const t = NP_TRACKS[st.cover], a = t.album;
  const tracks = st.cover === 'yehang' ? ['夜航', '灯塔', '北方的信', '慢车', '雾中的城', '回声', '晚安，海', '旧船票']
    : st.cover === 'harbor' ? ['Night Ferry', 'Harbor Lights', 'Paper Lanterns', 'Slow Tide', 'Morning Static', 'Low Tide Static', 'Postcards', 'Pier']
      : ['Neon Saints', 'Glass Orchard', 'Copper Wire', 'Paper Crowns', 'Satellites', 'Running Lights', 'Coastline', 'Wildfire Season', 'Goodnight, Tokyo'];
  return `<div class="alb"><div class="alb-head"><span class="alb-back">${ic('back', 22)}</span><div><h2>${a.t}</h2><div class="alb-sub">${a.s} <b>Album</b> 2019</div></div></div>` +
    `<div class="alb-dots"><i class="on"></i><i></i></div><div class="alb-stamp"><span class="alb-unr">Unrated</span><span><b>Warmer than <em>Slow Tide</em></b><small>Yesterday</small></span></div>` +
    `<div class="alb-meta"><span><b>${tracks.length}</b> tracks</span><span><b>35</b> min</span><b>Today 02:09</b></div>` +
    `<p class="alb-list">${tracks.map((x, i) => `<span class="${i % 2 ? 'alt' : ''} ${x === t.title ? 'now' : ''}">${x}</span>`).join(' ')}</p></div>`;
}

/** Expanded: dual-pane. */
function npDual(st, size) {
  const key = st.cover, t = NP_TRACKS[key], sing = st.sing === '1';
  const right = st.view === 'note' ? 'note' : st.view === 'lyrics' ? 'lyrics' : st.tab;
  const leftSing = sing && right !== 'lyrics'; // the capsule shows in the tool row when Lyrics is up
  const icons = `<div class="dual-icons">${['queue', 'devices', 'edit_note'].map((i) => `<span>${ic(i, 24)}</span>`).join('')}<button class="np-round dark" aria-label="Back to side panel">${ic('close_fullscreen', 20)}</button></div>`;
  const body = right === 'note' ? notesHeader() + noteLines(t.pos) : right === 'about' ? aboutBlock(t) : lyricList(key, { mode: 'wide', heading: [t.title, t.artist] });
  const foot = right === 'note' ? writeBar(t.pos) : right === 'lyrics' ? toolRow({ sing }) : '';
  return `<div class="dual">${npBackground()}<div class="dual-l">${npTopBar(t)}${npCoverRating(t, 312, { sing: leftSing })}${npTransport(t, 380)}<span class="sp"></span>${npHero(t, { small: true })}</div>` +
    `<div class="dual-r">${icons}${textTabs(right)}${body}<div class="dual-foot">${foot}</div></div></div>`;
}

function renderNp(st, size) {
  if (size.cls === 'expanded') return npDual(st, size);
  if (size.cls === 'medium') {
    const pw = Math.round(Math.min(420, Math.max(360, size.w * .5)));
    return `<div class="np-medium">${albumPage(st)}<div class="np-panel" style="width:${pw}px">${npBackground()}${npColumn(st, pw, { panel: true })}</div></div>`;
  }
  return `<div class="np-phone">${npBackground()}${npColumn(st, size.w)}</div>`;
}

Proto.init({
  title: 'Now Playing',
  controls: NP_CTL,
  scheme: (st) => ART[NP_TRACKS[st.cover].album.k].seed,
  render: renderNp,
  after: (dev) => { seatLyrics(dev); document.fonts.ready.then(() => seatLyrics(dev)); },
});
