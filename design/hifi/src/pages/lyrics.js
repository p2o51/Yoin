/* Page: full-screen Lyrics (ui/nowplaying: LyricsFullscreenPane, LyricsActionBar, SingControls on the sing branch).
   Compact = the phone's expanded lyrics page: ‹ + 44 cover docked in the top bar, the 52 tab group, the lyric list,
   the hero (artist + format, title), the 68 tool slot. Medium = the Enlarged full state: the same page in a
   640 column (stage 688, centred). Expanded = the two-column player — there is no separate full-window lyrics
   mode; its right column IS the lyrics page (title card first, the tool row bottom-left), the left column the
   resting stack (cover + rating, transport, title at the bottom).
   States: translation on / off; follow (recenter disabled) / scrolled away (recenter lit) / select mode; Sing. */
const LY_CTL = [
  { key: 'translate', label: 'Translation', options: [['0', 'Off'], ['1', 'On']], def: '0' },
  { key: 'state', label: 'List', options: [['follow', 'Following'], ['away', 'Scrolled away'], ['select', 'Select']], def: 'follow' },
  // 'none' = the tool row before Sing (0.6.3 (31) and earlier: four keys), for before/after figures
  { key: 'sing', label: 'Sing', options: [['0', 'Off'], ['1', 'On'], ['none', 'Before 0.6.3 (32)']], def: '0' },
  { key: 'cover', label: 'Cover', options: [['glass', 'Glass Orchard'], ['yehang', '夜航']], def: 'glass' },
];
function lyTools(st) {
  if (st.state === 'select') return selectRow();
  return toolRow({ sing: st.sing === '1', noSing: st.sing === 'none', translate: st.translate === '1', recenter: st.state === 'away' });
}
function lyList(st, t) {
  const L = LYRICS[st.cover];
  const html = lyricListX(st.cover, t, { translate: st.translate === '1', selected: st.state === 'select' ? [L.cur, L.cur + 1] : [] });
  return st.state === 'away' ? html.replace('class="ly lyx', 'data-off="-300" class="ly lyx') : html;
}
function renderLyrics(st, size) {
  const t = NP_TRACKS[st.cover], sing = st.sing === '1';
  if (size.cls === 'expanded') {
    const icons = `<div class="dual-icons">${['queue', 'devices', 'edit_note'].map((i) => `<span>${ic(i, 24)}</span>`).join('')}<button class="np-round dark" aria-label="Back to side panel">${ic('close_fullscreen', 20)}</button></div>`;
    return `<div class="dual">${npBackground()}<div class="dual-l">${npTopBar(t)}${npCoverRating(t, 312, { sing: false })}${npTransport(t, 380)}<span class="sp"></span>${npHero(t, { small: true })}</div>` +
      `<div class="dual-r">${icons}${textTabs('lyrics')}${lyList(st, t).replace('class="ly lyx', 'class="ly lyx wide')}<div class="dual-foot">${lyTools(st)}</div></div></div>`;
  }
  const W = size.cls === 'medium' ? 688 : size.w;
  return `<div class="ly-page">${npBackground()}<div class="npcol full ly-col" style="width:${W}px">${npTopBar(t, { docked: true })}${tabGroup('lyrics')}${lyList(st, t)}<div class="np-foot">${npHero(t)}${lyTools(st)}</div></div></div>`;
}
Proto.init({ title: 'Lyrics', controls: LY_CTL, scheme: (st) => ART[NP_TRACKS[st.cover].album.k].seed, render: renderLyrics,
  after: (dev) => { seatLyrics(dev); document.fonts.ready.then(() => seatLyrics(dev)); } });
