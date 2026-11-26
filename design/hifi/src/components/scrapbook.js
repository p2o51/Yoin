/* Album page 2, the Scrapbook (ui/detail/AlbumScrapbookPage, AlbumScrapbookNotes). Everything the user left on
   the album, pasted on dot paper (16 grid, r 1.2, outlineVariant 45 % / 35 % dark), flat, no shadows; each piece
   tilts by a stable hash of album + piece (cover ±3°, review / cards ±1°, stickies ±1.5°, tickets ±2°).
   Opening piece: tilted cover (148 / 184 wide) + emblem hanging off its corner (64 / 72), the review clipping
   (primaryContainer, r 16, GSF 15 / 1.55) or a 48 dashed pen circle; album stickies (secondaryContainer, r 16, the
   bottom-end corner cut with 17dp legs and folded) — several per album, oldest first — and the 64 × 56 dashed
   "write a sticky". Then one cluster per touched track: ticket (66 % of the lane, notched at x 48, perforated
   stub "01") + notes clipping (time column 42, a 3dp ink rail) on the other side, overlapping 12. Receipt (mono,
   zig-zag foot) and Mentioned in / 被提及 backlinks. Lanes: 1 on a phone, 2 at ≥ 600 wide. */
const SCRAP = {
  harbor: {
    credits: [['Label', '厂牌', 'Harbor Records', 'Harbor Records'], ['Producer', '制作', 'Kiri Ono', 'Kiri Ono'], ['Mix', '混音', 'Kenji Sato', 'Kenji Sato']],
    about: [
      'A nocturnal chamber-pop record built around upright piano, tape echo, and close-miked brass recorded between Osaka and Kobe.',
      '围绕立式钢琴、磁带回声与近距离录制的铜管乐构筑的夜间室内流行唱片，录制于大阪与神户之间，以细腻的潮汐叙事串联全专。',
    ],
    stickies: [['Best with headphones, on the last ferry home.'], ['Side B is the morning after — start at ', { m: 'song', t: 'Slow Tide', k: 'harbor' }]],
    clusters: [
      { n: '01', t: 'Morning Static', d: '4:05', plays: 5, score: '8.6', notes: [['0:34', ['Static opening before the drums']], ['1:12', ['the ', { b: 'kick' }, ' finally lands here']]] },
      { n: '03', t: 'Slow Tide', d: '3:59', plays: 3, score: '9.1', best: true, liked: true, notes: [['0:29', ['Feels like ', { m: 'album', t: 'Glass Orchard', k: 'glass' }]], ['', [{ todo: 'replay at night' }]]] },
      { n: '05', t: 'Harbor Lights', d: '4:48', plays: 4, score: '7.8', notes: [['2:41', ['brass swells at the bridge']]], more: 2 },
    ],
    tags: [['Recorded in', 'Osaka'], ['Producer', 'Kiri Ono']],
    receipt: [['Plays in Yoin', '22'], ['Days since first play', '11'], ['Last played', 'Today'], ['Rated', '3 / 8'], ['Notes', '7'], ['Asked', '0']],
    mentions: [
      { k: 'yehang', t: '灯塔', kind: 'Note', at: '0:48', when: '8 minutes ago', text: ['灯塔 bridge pairs with ', 'Harbor Lights', ' and 林间 · slow build into the last chorus'] },
      { k: 'glass', t: 'Paper Crowns', kind: 'Note', at: '1:51', when: '14 minutes ago', text: ['Chorus lifts like ', 'Slow Tide', ' · brass swells at the bridge'] },
      { k: 'glass', t: 'Glass Orchard', kind: 'Review', when: '1 day ago', text: ['Warmer than ', 'Slow Tide'] },
      { k: 'glass', t: 'Glass Orchard', kind: 'Album note', when: '2 days ago', text: ['Late train home with ', 'Harbor Lights'] },
    ],
  },
  north: { stickies: [], clusters: [], tags: [], receipt: [['Plays in Yoin', '1'], ['Days since first play', '0'], ['Last played', 'Today'], ['Rated', '0 / 6'], ['Notes', '0'], ['Asked', '0']],
    mentions: [{ k: 'glass', t: 'Goodnight, Tokyo', kind: 'Note', when: 'Nov 20', text: ['Pairs with ', 'Northern Field Recordings', ' on a long drive'] }] },
};
function sbTilt(key, range) { let h = 7; for (const c of key) h = (h * 31 + c.charCodeAt(0)) % 9973; return ((h / 9973) * 2 - 1) * range; }
const tilt = (key, r) => `transform:rotate(${sbTilt(key, r).toFixed(2)}deg)`;
function sbSegs(segs) {
  return segs.map((s) => typeof s === 'string' ? s : s.b ? `<b>${s.b}</b>` : s.todo ? `<span class="sb-todo">${ic('check_box', 16)}<s>${s.todo}</s></span>` : mentionPill(s)).join('');
}
function sticky(segs, i, album) { return `<div class="stk" style="${tilt(album + 's' + i, 1.5)}"><p>${sbSegs(segs)}</p></div>`; }
function dashedSticky() { return `<div class="stk-new" aria-label="Write an album note">${ic('note', 22)}</div>`; }
/** The sticky being written: the sticky paper (tilt × 0.4), 3–10 line field, the format row, Cancel / Save (tonal). */
function stickyEditor(album, { text = '', popup = '' } = {}) {
  return `<div class="stk stk-ed" style="${tilt(album + 's9', .6)}"><div class="stk-field">${text || '<span class="ph">Write about the whole album</span>'}<i class="caret"></i></div>${popup}` +
    `${formatRow()}<div class="stk-act"><button class="tbtn">Cancel</button><button class="tonal">Save</button></div></div>`;
}
function ticket(c, album) {
  return `<div class="tk ${c.best ? 'best' : ''}" style="${tilt(album + c.n, 2)}">${c.liked ? `<span class="tk-heart">${ic('favorite_filled', 18)}</span>` : ''}<span class="tk-stub num">${c.n}</span>` +
    `<span class="tk-body"><b class="ell">${c.t}</b><span class="num">${c.d}<span><b>${c.plays}</b> ${loc('plays', '次')}</span></span></span><span class="tk-score num">${c.score}</span></div>`;
}
function noteClip(c, album) {
  return `<div class="nclip" style="${tilt(album + c.n + 'n', 1)}">${c.notes.map(([at, segs]) => `<div class="nc-l"><span class="num">${at}</span><p>${sbSegs(segs)}</p></div>`).join('')}${c.more ? `<b class="nc-more">${loc(`+${c.more} notes`, `+${c.more} 条笔记`)}</b>` : ''}</div>`;
}
function receipt(al, rows) {
  return `<div class="rcpt"><div class="rc-h">${al.a.t.toUpperCase()} — ${al.a.s.toUpperCase()}</div>${rows.map(([l, v]) => `<div class="rc-r"><span>${l}</span><i></i><span>${v}</span></div>`).join('')}</div>`;
}
function mentionedIn(list, album) {
  return `<section class="mi"><div class="mi-h"><span class="mi-ck">${shapeSvg('Cookie12Sided')}${ic('at', 18)}</span><b>${loc('Mentioned in', '被提及')}</b><span>${loc(`${list.length} place${list.length > 1 ? 's' : ''}`, `${list.length} 处`)}</span></div>` +
    list.map((m, i) => `<div class="mi-c" style="${tilt(album + 'm' + i, .6)}"><div class="mi-top">${cover(m.k, 'mi-th')}<span class="col"><b>${m.t}</b><span class="mi-meta"><em>${loc(m.kind, { Note: '笔记', 'Album note': '专辑笔记', Review: '乐评' }[m.kind] || m.kind)}</em>${m.at ? `<span class="num">${m.at}</span>` : ''}<span class="dim">${loc(m.when, m.when.replace(/^(\d+) minutes ago$/, '$1 分钟前').replace(/^(\d+) days? ago$/, '$1 天前'))}</span></span></span>${ic('chevron_right', 20)}</div>` +
      `<p>${m.text.map((s, j) => (j % 2 ? `<mark>${s}</mark>` : s)).join('')}</p></div>`).join('') + '</section>';
}
function scrapbookCreditsLine(sb) {
  if (!sb.credits || !sb.credits.length) return '';
  return `<p class="sb-cred">${sb.credits.map(([enL, zhL, enV, zhV]) => `${loc(enL, zhL)}\u00a0${loc(enV, zhV)}`).join('\u00a0· ')}</p>`;
}
function scrapbookAboutCard(sb, album) {
  if (!sb.about) return '';
  const text = Array.isArray(sb.about) ? loc(sb.about[0], sb.about[1]) : sb.about;
  return `<div class="sb-about" style="${tilt(album + 'ab', 1)}"><div class="sb-about-h">${ic('sparkle', 14)}<span>${loc('About', '关于')}</span></div><p>${text}</p><b>${loc('Read all', '阅读全部')}</b></div>`;
}
/** The whole page. wide = ≥ 600 (2 lanes, 184 cover, 72 emblem). editing = the sticky editor open. */
function scrapbook(key, { wide = false, editing = false, popup = '', about = true } = {}) {
  const al = ALBUMS[key], sb = SCRAP[key], cv = wide ? 184 : 148, em = wide ? 72 : 64;
  const rev = al.review ? `<div class="rclip" style="${tilt(key + 'r', 1)}"><p>${al.review[0]}</p><small>${al.review[1]}</small></div>` : `<button class="rpen" aria-label="Write a comment">${ic('edit', 20)}</button>`;
  const pile = `<div class="pile">${editing ? stickyEditor(key, { popup }) : dashedSticky()}${sb.stickies.map((s, i) => sticky(s, i, key)).join('')}</div>`;
  const cred = about ? scrapbookCreditsLine(sb) : '';
  const aboutEl = about ? scrapbookAboutCard(sb, key) : '';
  const open = `<div class="sb-open ${wide ? 'wide' : ''}"><div class="sb-cv" style="--sbc:${cv}px;--em:${em}px"><span class="sb-cvt" style="${tilt(key + 'c', 3)}">${cover(al.a.k)}</span><span class="sb-em">${albumEmblem(al, em)}</span></div>` +
    `<div class="sb-side">${cred}${rev}${wide ? pile : ''}</div>${wide ? '' : pile}</div>${aboutEl}`;
  const clusters = sb.clusters.map((c, i) => `<div class="cl ${i % 2 ? 'flip' : ''}">${ticket(c, key)}${noteClip(c, key)}</div>`);
  const lanes = wide ? `<div class="sb-lanes"><div>${clusters.filter((_, i) => i % 2 === 0).join('')}</div><div>${clusters.filter((_, i) => i % 2).join('')}</div></div>` : `<div class="sb-lanes one">${clusters.join('')}</div>`;
  const tags = sb.tags.length ? `<div class="sb-tags">${sb.tags.map(([l, v], i) => `<span class="tag" style="${tilt(key + 't' + i, 2.5)}"><small>${l}</small>${v}</span>`).join('')}</div>` : '';
  const empty = sb.clusters.length || sb.stickies.length || al.review ? '' : '<p class="sb-empty">Nothing yet</p>';
  return `<div class="sb ${about ? 'v34' : ''} ${wide ? 'wide' : ''}">${open}${lanes}${tags}${empty}${receipt(al, [...receiptDateRows(al), ...sb.receipt])}${mentionedIn(sb.mentions, key)}</div>`;
}
