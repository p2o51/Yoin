/* Page: writing a note (0.6.4). Two surfaces:
   · NP write bar (ui/component/NoteContent): the Note page with the bar opened into the writing card on the keyboard.
     Compact = the phone NP; Medium = the 400 side panel over the album page; Expanded = the two-column player,
     the card in the right column.
   · Album sticky (album page 2): the sticky editor in place of the dashed sticky, format row on its own line,
     Cancel / Save.
   @ opens the MentionPopup: 312 wide, placed by mentionPopupOffset — x = caret − 16 kept inside [8, W − 320];
   above the whole field if it fits, else above the caret line, else below, else the roomier side with the list
   shrunk; always between the status bar and the keyboard top. The keyboard is a placeholder (Gboard-like):
   about 336 on a phone, 376 on a tablet (measured off device screenshots; the app reads the real IME inset). */
const NE_CTL = [
  { key: 'surface', label: 'Surface', options: [['note', 'NP write bar'], ['sticky', 'Album sticky']], def: 'note' },
  { key: 'query', label: 'Mention', options: [['off', 'None'], ['empty', '@'], ['ni', '@Ni'], ['lj', '@lj (pinyin)']], def: 'ni' },
];
const NE_KB = { compact: 336, medium: 376, expanded: 376 };
function neBody(q, { sticky = false } = {}) {
  const tail = q === 'off' ? '' : ' ' + MENTION_SETS[q].label;
  if (sticky) return `Morning record — pairs with ${echip('Glass Orchard')} on a grey day${tail}`;
  return `Chorus lifts like ${echip('Night Ferry')} from ${echip('Low Tide Static')}<br><b class="fl2">•</b> <span class="mk">**</span><b>brass swells</b><span class="mk">**</span> at the bridge${tail}`;
}
function nePopup(q) { return q === 'off' ? '' : `<div class="ne-pop">${mentionPopup(q)}</div>`; }
function noteColumn(t, q, { cardLabel = true } = {}) {
  const draft = `<div class="nl on draft"><span class="nt num">2:33</span><div class="nb"><u>Writing…</u></div></div>`;
  return `${tabGroup('note')}${notesHeader()}${noteLines(t.pos).replace(/<\/div>$/, draft + '</div>')}` +
    `<div class="ne-card">${writeCard({ time: '2:33', body: neBody(q), active: q === 'off' ? ['l'] : [] })}</div>`;
}
function renderNe(st, size) {
  const t = NP_TRACKS.glass, kb = NE_KB[size.cls], q = st.query;
  if (st.surface === 'sticky') {
    const pop = nePopup(q);
    const pane = size.cls === 'expanded'
      ? paneSplit(`<div class="lib">${libHeader('medium', 'albums')}<div class="lib-body">${libSort('Recents')}${libGrid(LIB_ALL.filter((x) => x.kind === 'album'), 5)}</div></div>`, albumPane('harbor', { page: 1, editing: true, W: 656 }))
      : albumPane('harbor', { page: 1, editing: true, W: size.w });
    return `<div class="ne-sticky">${pane.replace('<span class="ph">Write about the whole album</span>', neBody(q, { sticky: true }))}</div>${pop}${keyboard(kb, ['grey', 'great', 'grey-blue'])}`;
  }
  if (size.cls === 'expanded') {
    const icons = `<div class="dual-icons">${['queue', 'devices', 'edit_note'].map((i) => `<span>${ic(i, 24)}</span>`).join('')}<button class="np-round dark">${ic('close_fullscreen', 20)}</button></div>`;
    const pop = nePopup(q);
    return `<div class="dual">${npBackground()}<div class="dual-l">${npTopBar(t)}${npCoverRating(t, 312)}${npTransport(t, 380)}<span class="sp"></span>${npHero(t, { small: true })}</div>` +
      `<div class="dual-r ne-dr" style="bottom:${kb + 8}px">${icons}${textTabs('note')}${notesHeader()}${noteLines(t.pos)}<div class="ne-card">${writeCard({ time: '2:33', body: neBody(q) })}</div></div></div>${pop}${keyboard(kb)}`;
  }
  const PW = size.cls === 'medium' ? 400 : size.w;
  const col = `<div class="npcol full ne-col" style="width:${PW}px;bottom:${kb}px">${npTopBar({ ...t, from: t.title }, { docked: true }).replace('PLAYING FROM ALBUM', 'The Meridians')}${noteColumn(t, q)}</div>`;
  const pop = nePopup(q);
  if (size.cls === 'medium') return `<div class="ne-med">${albumPane('glass', { W: 800 })}<div class="np-panel ne-panel" style="width:400px">${npBackground()}${col}</div></div>${pop}${keyboard(kb)}`;
  return `<div class="ne-phone">${npBackground()}${col}</div>${pop}${keyboard(kb)}`;
}
Proto.init({ title: 'Notes editor', controls: NE_CTL, scheme: (st) => (st.surface === 'sticky' ? 'harbor' : 'glass'), render: renderNe,
  after: (dev, st) => { const go = () => placeMentionPopup(dev, st.surface === 'sticky' ? '.stk-field' : '.wc-text'); go(); document.fonts.ready.then(go); } });
