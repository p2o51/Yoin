/* Page: Album detail (ui/detail/AlbumDetailScreen) — page 1 Overview and page 2 Scrapbook, switchable (the page
   dots). Compact = full window, the bar carries [Play ▾][pill]. Medium = full window, Medium layout, centred bar
   [Play ▾][Shuffle][Artist][Share][pill]. Expanded (1280 × 800) = the album opens in the shell's detail column
   (600 | 24 | 656, PaneSplit) and lays out as Medium there; one merged bar carries Play for both columns. */
const AL_CTL = [
  { key: 'album', label: 'Album', options: [['harbor', 'Harbor Lights'], ['north', 'Northern Field (empty)'], ['glass', 'Glass Orchard']], def: 'harbor' },
  { key: 'page', label: 'Page', options: [['overview', 'Overview'], ['scrapbook', 'Scrapbook']], def: 'overview' },
  { key: 'pull', label: 'Overview', options: [['rest', 'At rest'], ['up', 'Pulled up']], def: 'rest' },
  { key: 'sticky', label: 'Sticky', options: [['off', 'Off'], ['edit', 'Writing']], def: 'off' },
  { key: 'playing', label: 'Playing', options: [['yes', 'Something'], ['no', 'Nothing']], def: 'yes' },
  // the release date at the service's precision (0.6.3 (32)); 'year' = the album page before it, for before/after figures
  { key: 'date', label: 'Date', options: [['full', 'Release date'], ['year', 'Year only (before 0.6.3 (32))']], def: 'full' },
  // Album About, credits line & primary listener paper (0.6.3 (34)); 'was' = before 0.6.3 (34)
  { key: 'about', label: 'Album About', options: [['on', 'Album About (0.6.3 (34))'], ['was', 'Before 0.6.3 (34)']], def: 'on' },
  // the Scrapbook scrolled down to its receipt
  { key: 'at', label: 'Scrapbook at', options: [['top', 'Top'], ['receipt', 'Receipt']], def: 'top' },
];
function renderAlbum(st, size) {
  const page = st.page === 'scrapbook' ? 1 : 0, opts = { page, pulled: st.pull === 'up', editing: st.sticky === 'edit', about: st.about !== 'was' };
  const playing = st.playing === 'yes' ? { k: NOW_PLAYING.k, s: NOW_PLAYING.s } : null, title = NOW_PLAYING.t;
  if (size.cls === 'expanded') {
    const shell = `<div class="lib">${libHeader('medium', 'albums')}<div class="lib-body">${libSort('Recents')}${libGrid(LIB_ALL.filter((x) => x.kind === 'album'), 5)}</div></div>`;
    return `${paneSplit(shell, albumPane(st.album, { ...opts, W: 656 }))}${barDissolve(size)}${detailBar(size, { mode: 'shell', playing, title })}`;
  }
  return `${albumPane(st.album, { ...opts, W: size.w })}${barDissolve(size)}${detailBar(size, { playing, title })}`;
}
/** Scrapbook at=receipt: scroll the page so the receipt sits a third of the way down the window. */
function albumScroll(dev, st) {
  if (st.page !== 'scrapbook' || st.at !== 'receipt') return;
  const sc = dev.querySelector('.ad-sb .ad-scroll'), r = sc && sc.querySelector('.rcpt');
  if (!r) return;
  let y = 0; for (let e = r; e && e !== sc; e = e.offsetParent) y += e.offsetTop;
  const ty = Math.max(0, y - dev.offsetHeight * .3), head = dev.querySelector('.ad-sb .ad-head'), hb = head.offsetTop + head.offsetHeight - sc.offsetTop;
  sc.style.transform = `translateY(${-ty}px)`;
  // what scrolls up fades out under the header (the default Tide line edge), instead of running through it
  const m = `linear-gradient(transparent ${ty + hb - 8}px, #000 ${ty + hb + 28}px)`;
  sc.style.webkitMaskImage = m; sc.style.maskImage = m;
}
Proto.init({ title: 'Album', controls: AL_CTL, scheme: (st) => ART[ALBUMS[st.album].a.k].seed, render: renderAlbum,
  after: (dev, st) => { albumScroll(dev, st); document.fonts.ready.then(() => albumScroll(dev, st)); } });
