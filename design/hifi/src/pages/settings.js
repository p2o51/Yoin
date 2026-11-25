/* Page: Settings after the v4 IA (PR #300 on main) + the Music links page (branch claude/open-in-yoin, PR 306).
   Compact and Medium: one full-width column (no max width) — the root, or a second-level page.
   Expanded (≥ 840): Activity Embedding list-detail, list = 420 / W clamped to 0.30–0.40 → 420 | 860 at 1280.
   The list pane (surfaceDim) turns account cards into segmented rows ("In use" pill on the active one) and gives
   every row a chevron; the selected row detaches as a 28-corner pill in the detail pane's colour (surfaceContainer).
   Nothing selected → a 148 Cookie12Sided outline and "No account selected". */
const SET_CTL = [
  { key: 'page', label: 'Page', options: [['root', 'Root'], ['links', 'Music links'], ['account', 'Subsonic account'], ['sync', 'Sync and backups']], def: 'root' },
  { key: 'links', label: 'Clipboard detection', options: [['on', 'On'], ['off', 'Off']], def: 'on' },
];
function renderSettings(st, size, ) {
  const dark = st.theme === 'dark', links = st.links === 'on';
  if (size.cls === 'expanded') {
    const sel = st.page === 'root' ? '' : st.page;
    const detail = st.page === 'root' ? `<div class="s-empty">${shapeSvg('Cookie12Sided')}<span>No account selected</span></div>` : settingsPage(st.page, dark, { links });
    return `<div class="set"><div class="s-pane s-list">${settingsList(dark, sel, links)}</div><div class="s-pane s-detail">${detail}</div></div>`;
  }
  return `<div class="set"><div class="s-pane" style="left:0;right:0">${st.page === 'root' ? settingsRoot(dark, { links }) : settingsPage(st.page, dark, { links })}</div></div>`;
}
Proto.init({ title: 'Settings', controls: SET_CTL, scheme: () => 'wallpaper', render: renderSettings });
