/* Note writing (ui/notes/editor: NoteMarkupField, NoteEditorDisplay, NoteFormatToolbar, MentionPopup;
   ui/component/NoteContent: the NP write bar). While writing, a mention is "@Title" on a plain rectangular
   secondaryContainer background (Medium weight, no cover) — it only becomes the rounded cover pill once saved.
   List markers "• " Bold, to-dos ☐ / ☑; ** _ # markers stay visible at 38 %. */
const MENTION_SETS = {
  empty: { label: '@', sections: [['Songs', [['Goodnight, Tokyo', 'The Meridians', 'Glass Orchard', 'glass'], ['Night Ferry', 'Kiri Ono', 'Harbor Lights', 'harbor'], ['灯塔', '林间', '夜航', 'yehang'], ['Slow Tide', 'Kiri Ono', 'Harbor Lights', 'harbor']]],
    ['Albums', [['Glass Orchard', 'The Meridians', '', 'glass']]], ['Artists', [['The Meridians', '', '', 'meri', 1]]]] },
  ni: { label: '@Ni', q: 'ni', sections: [['Songs', [['Night Ferry', 'Kiri Ono', 'Harbor Lights', 'harbor'], ['Midnight Radio', 'Sora Hayama', '午後のソーダ', 'soda'], ['Morning Static', 'Kiri Ono', 'Harbor Lights', 'harbor'], ['Running Lights', 'The Meridians', 'Glass Orchard', 'glass'], ['Goodnight, Tokyo', 'The Meridians', 'Glass Orchard', 'glass']]]] },
  lj: { label: '@lj', q: '', sections: [['Artists', [['林间', '', '', 'linjian', 1]]], ['Albums', [['林间日记', '林间', '', 'baizhou']]]] },
};
function hiMatch(t, q) {
  if (!q) return t;
  const i = t.toLowerCase().indexOf(q); return i < 0 ? t : `${t.slice(0, i)}<b>${t.slice(i, i + q.length)}</b>${t.slice(i + q.length)}`;
}
/** MentionPopup: 312 wide, r 20, surfaceContainerHigh, header "Mention" + ⤢ "Search all", sections (labelMedium,
   primary), rows 52 (40 cover, r 4 / round for artists; title bodyLarge with the matched letters Bold primary;
   artist · album at 60 %, 12 apart). Row 0 is highlighted: secondaryContainer, r 16. maxList caps the list. */
function mentionPopup(set, { maxList = 300 } = {}) {
  const S = MENTION_SETS[set]; let n = 0;
  const rows = S.sections.map(([lab, list]) => `<div class="mn-sec">${lab}</div>` + list.map(([t, a, al, k, round]) =>
    `<div class="mn-row ${n++ === 0 ? 'hi' : ''}">${cover(k, 'mn-cv' + (round ? ' round' : ''))}<span class="col"><span class="mn-t ell">${hiMatch(t, S.q)}</span>${a ? `<span class="mn-s ell">${a}${al ? `<span>${al}</span>` : ''}</span>` : ''}</span></div>`).join('')).join('');
  return `<div class="mn"><div class="mn-h"><span>Mention</span><button>${ic('open_in_full', 16)}Search all</button></div><div class="mn-list" style="max-height:${maxList}px">${rows}</div></div>`;
}
/** NoteFormatToolbar: @ B I H • ☑ — 36 × 36 icon toggles, 2 apart; on = primary (no fill). */
function formatRow(on = []) {
  const b = (k, inner, label) => `<button class="fmt ${on.includes(k) ? 'on' : ''}" aria-label="${label}">${inner}</button>`;
  return `<div class="fmts">${b('at', ic('at', 20), 'Mention a song, album, or artist')}${b('b', '<b class="fb">B</b>', 'Bold')}${b('i', '<i class="fi">I</i>', 'Italic')}${b('h', '<b class="fh">H</b>', 'Heading')}${b('l', '<b class="fl">•</b>', 'List')}${b('t', ic('check_box', 20), 'To-do')}</div>`;
}
function savePill(enabled = true) { return `<button class="save ${enabled ? '' : 'off'}">${ic('send', 16)}Save</button>`; }
const echip = (t) => `<span class="ech">@${t.replace(/ /g, '&nbsp;')}</span>`;
/** The NP write bar, writing: corner 20, surfaceContainerHigh 92 %, the 36 time chip (primary 12 %; a 16 ↻ when the
   playhead moved ≥ 2 s; a 24 cover when the draft came from another song), 2–6 lines of bodyLarge, then the
   format row and the Save pill. */
function writeCard({ time = '1:51', body = '', refresh = true, fromCover = null, active = [] } = {}) {
  return `<div class="wcard"><div class="wc-top"><span class="wc-chip num">${fromCover ? cover(fromCover, 'wc-cv') : ''}${refresh ? ic('refresh', 16) : ''}${time}</span>` +
    `<div class="wc-text">${body || '<span class="ph">Write a note</span>'}<i class="caret"></i></div></div><div class="wc-foot">${formatRow(active)}${savePill(!!body)}</div></div>`;
}
/** Soft keyboard placeholder (not part of Yoin): Gboard-like, ~336 tall on a phone, ~376 on a tablet. */
function keyboard(h, sugg = ['bridge', 'bridges', 'Bridgeport']) {
  const row = (keys, cls = '') => `<div class="kb-row ${cls}">${keys.map((k) => `<span class="${k.length > 1 ? 'w' : ''}">${k}</span>`).join('')}</div>`;
  return `<div class="kb" style="height:${h}px"><div class="kb-sug">${sugg.map((s) => `<span>${s}</span>`).join('')}</div>` +
    row('q w e r t y u i o p'.split(' ')) + row('a s d f g h j k l'.split(' '), 'in') + row(['⇧', ...'z x c v b n m'.split(' '), '⌫']) + row(['?123', ',', 'English', '.', '↵'], 'last') + '<small>keyboard (system, not Yoin)</small></div>';
}
/** mentionPopupOffset, measured on the drawn page: x = caret − 16 inside [8, W − 320]; then the first that fits —
   above the whole field (8 gap), above the caret line, below the caret line — else the roomier side with the list
   shrunk (≥ 96). Bounds: the status bar and the keyboard top. Call from the page's after() hook. */
function placeMentionPopup(dev, fieldSel) {
  const pop = dev.querySelector('.ne-pop'), field = dev.querySelector(fieldSel), caret = field && field.querySelector('.caret');
  if (!pop || !caret) return;
  const D = dev.getBoundingClientRect(), k = D.width / dev.offsetWidth, R = (el) => { const r = el.getBoundingClientRect(); return { l: (r.left - D.left) / k, t: (r.top - D.top) / k, b: (r.bottom - D.top) / k }; };
  const kb = dev.querySelector('.kb'), lo = kb ? R(kb).t : dev.offsetHeight, hi = 36, f = R(field), c = R(caret), list = pop.querySelector('.mn-list');
  const W = dev.offsetWidth, x = Math.min(Math.max(8, c.l - 16), W - 320), h = pop.offsetHeight;
  pop.style.left = x + 'px';
  const fit = (top) => { pop.style.top = top + 'px'; };
  if (f.t - 8 - h >= hi) return fit(f.t - 8 - h);
  if (c.t - 8 - h >= hi) return fit(c.t - 8 - h);
  if (c.b + 8 + h <= lo) return fit(c.b + 8);
  const up = c.t - 8 - hi, down = lo - c.b - 8, room = Math.max(up, down), shrink = Math.max(96, list.offsetHeight - (h - room));
  list.style.maxHeight = shrink + 'px';
  fit(up >= down ? c.t - 8 - pop.offsetHeight : c.b + 8);
}
