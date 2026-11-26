/* Notes as lyric lines (NoteContent.NoteLine, NoteMarkupText, NoteWriteBar).
   A note: a fixed 40dp time column (tabular), the text after it; the note at the playhead (latest anchor ≤
   playhead) is primary; a note without an anchor shows a 6dp hollow dot. 0.6.4 mentions: a small cover
   (an artist's is round) and the name in SemiBold on a faint pill of the mention colour (12 % fill), laid out
   as one unbreakable slot. Markup: **bold**, • bullets. Demo notes, fictional. */
const NOTES = [
  { at: 12, segs: ['The drum machine sounds like rain on a tin roof'] },
  { at: 48, segs: [{ m: 'song', t: '灯塔', k: 'yehang' }, ' bridge pairs with ', { m: 'album', t: 'Harbor Lights', k: 'harbor' }, ' and ', { m: 'artist', t: '林间', k: 'yehang' }], sub: [{ b: 'slow build' }, ' into the last chorus'] },
  { at: 112, segs: ['Every light a word I never said — ', { b: 'sing this one quietly' }] },
  { at: 147, segs: ['Plays like the other half of ', { m: 'song', t: 'Slow Tide', k: 'harbor' }] },
  { at: null, segs: ['Pairs with ', { m: 'album', t: 'Northern Field Recordings', k: 'north' }, ' on a long drive'] },
];
/** A saved mention. Proto.state.mentions === 'text' draws it as before 0.6.3 (32) (NoteMarkupText before the pill,
   2c61453fb^): the bare name in the mention colour (primary), SemiBold, no cover, no pill. */
function mentionPill(m) {
  if (Proto.state.mentions === 'text') return `<b class="mtext">${m.t}</b>`;
  return `<span class="mp" title="${m.m}">${cover(m.k, 'mp-th' + (m.m === 'artist' ? ' round' : ''))}<b>${m.t}</b></span>`;
}
function noteSegs(segs) { return segs.map((s) => (typeof s === 'string' ? s : s.b ? `<b>${s.b}</b>` : mentionPill(s))).join(''); }
function noteLines(pos, { mode = 'full' } = {}) {
  const anchored = NOTES.filter((n) => n.at != null && n.at <= pos), active = anchored.length ? anchored[anchored.length - 1] : null;
  return `<div class="notes notes-${mode}">${NOTES.map((n) => `<div class="nl ${n === active ? 'on' : ''} ${n.at == null ? 'free' : ''}">` +
    `<span class="nt num">${n.at == null ? '<i></i>' : mmss(n.at)}</span><div class="nb">${noteSegs(n.segs)}${n.sub ? `<div class="nsub">• ${noteSegs(n.sub)}</div>` : ''}</div></div>`).join('')}</div>`;
}
function notesHeader(n = NOTES.length) {
  return `<div class="nh"><span>${loc(`${n} notes`, `${n} 条笔记`)}</span><span class="nseg"><b>${loc('Timeline', '时间线')}</b><span>${loc('Created', '创建时间')}</span></span></div>`;
}
/** NoteWriteBar at rest: 56 capsule, the playhead chip (tap = re-anchor), "Write a note", pen. */
function writeBar(pos) { return `<div class="nwb"><span class="nwb-t num">${mmss(pos)}</span><span class="nwb-ph">${loc('Write a note', '写笔记')}</span>${ic('edit', 20)}</div>`; }
