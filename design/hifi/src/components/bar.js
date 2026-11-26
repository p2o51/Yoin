/* The floating bottom bar (FloatingBottomBar / ShellChromeForm): one Button Group, the app's only navigation.
   compact  = portrait bar: 68 tall (48 buttons + 10 × 2), 16 from the sides, [Home][now-playing pill][Library]
   medium / expanded = centred bar: 60 tall (44 + 8 × 2), max 600, 24 / 28 above the window bottom,
   buttons carry labels; the pill is a fixed 200.
   edit = Edit Home pose: [Undo | Add] [Done]; the pill folds away.
   No shadow: the bar is separated from content by the dot dissolve, not elevation. */
function bottomBar({ size, edit = false, playing = CAT.glassAlbum, title = 'Goodnight, Tokyo', active = 'home' }) {
  const centred = size.cls !== 'compact';
  const lab = (t) => (centred ? `<span>${t}</span>` : '');
  if (edit) {
    return `<nav class="bbar ${centred ? 'centred' : ''} edit"><button class="bb-btn bb-undo">${ic('undo', 20)}<span>Undo</span></button>` +
      `<button class="bb-btn bb-done">${ic('check', 20)}<span>Done</span></button></nav>`;
  }
  return `<nav class="bbar ${centred ? 'centred' : ''} ${playing ? '' : 'idle'}">` +
    `<button class="bb-btn bb-nav ${active === 'home' ? 'on' : ''}" aria-label="Home">${ic(active === 'home' ? 'home_filled' : 'home', 22)}${lab('Home')}</button>` +
    (playing ? `<button class="bb-pill" aria-label="Now playing">${cover(playing.k, 'bb-art')}<span class="bb-pt"><b class="ell">${title}</b><span class="ell">${playing.s}</span></span></button>` : '') +
    `<button class="bb-btn bb-nav bb-lib ${active === 'library' ? 'on' : ''}" aria-label="Library">${ic(active === 'library' ? 'library_filled' : 'library', 22)}${lab('Library')}</button></nav>`;
}
/** The dot field under the bar: content breaks into a halftone where it meets the chrome (SeamHalftone). */
function barDissolve(size) { return `<div class="bdis ${size.cls !== 'compact' ? 'centred' : ''}"></div>`; }
