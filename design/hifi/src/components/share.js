/* Share images — PROTOTYPE for two new styles next to the lyrics share (ui/nowplaying/LyricsShareCard.kt):
   "About" (the song's About: AI-written facts and a background paragraph) and "Note" (the listener's own note).
   Not in the app. Drawn at the share image's logical size: Card 360 wide (exported at 4× = 1440 px, height follows
   the content), Story 360 × 640 (exported at 3× = 1080 × 1920).
   Owner rules (2026-11-25): every exported image is a full rectangle — no rounded or transparent corners; what the
   listener wrote and what is AI / objective use two different colour roles: About = tertiary (as the app's Ask /
   About index cards), Note = primary (as the album review clipping). The four tone slots stay, read from the
   style's own role: Soft = container (default), Deep = Soft inverted, Vivid = the role itself, Paper = a neutral
   surface with the role as ink (replaces Mist, which would borrow the other role). Lyrics keeps today's four.
   Family with the lyrics card: 24 padding, 44 cover (Thumb 4) + title / artist, the Yoin mark at the foot,
   GSF rounded SemiBold for the hero text. Variants: About A receipt / B quote / C liner notes;
   Note A lyric twin / B scrapbook clipping / C timeline moment. */
const SHARE_SONGS = {
  zh: {
    t: '他夏了夏天', s: '苏打绿', k: 'soda', album: '夏/狂热', year: 2009, dur: 305,
    credits: [['作词', '吴青峰'], ['作曲', '吴青峰'], ['制作人', '林暐哲'], ['专辑', '《夏/狂热》'], ['发行', '2009']],
    about: '收录于 2009 年的《夏/狂热》，是苏打绿「韦瓦第计划」四季专辑里的「夏」。吴青峰写一个普通上班族的一天：从按下闹钟开始，到梦里再按下闹钟结束，整首歌绕成一个循环。副歌里的「甜蜜的负荷」，借自吴晟的诗《负荷》。',
    // the listener's note: anchored at 3:46 (帶著笑容的睡意化成了彩虹), one **bold**, one @mention, two paragraphs
    note: { at: 226, md: [['最后一段鼓整个打开，', { b: '一口气走上了星空' }, '。每次下班走出地铁，刚好听到这里。'], ['夏天的歌单，接在 ', { m: 'album', t: '南北寻光', k: 'tuno' }, ' 后面放。']], when: '2026年7月18日' },
  },
  en: {
    t: 'A Lot’s Gonna Change', s: 'Weyes Blood', k: 'weyes', album: 'Titanic Rising', year: 2019, dur: 261,
    credits: [['Lyricist', 'Natalie Mering'], ['Composer', 'Natalie Mering'], ['Producer', 'Jonathan Rado'], ['Album', 'Titanic Rising'], ['Released', '2019']],
    about: 'The opening track of Titanic Rising, Weyes Blood’s first album for Sub Pop. Natalie Mering wrote it as a wish to go back to a time before everything changed, a slow piano ballad that swells into strings, close to the 1970s soft rock she grew up on.',
    note: { at: 149, md: [['The strings come in and the whole room tilts. ', { b: 'Headphones on, lights off.' }], ['Plays like the other half of ', { m: 'song', t: 'Andromeda', k: 'weyes' }, '.']], when: 'Jul 18, 2026' },
  },
  // the lyrics card, for comparison (today's card, drawn square-cornered)
  // lyric picks: short excerpts (≤ 3 lines) with their LRC times; the English one carries a Chinese translation
  // (written for the prototype). poster = the single line the Poster layout shows.
  lyrics: {
    zh: { lines: [[226.8, '帶著笑容的睡意化成了彩虹'], [230.7, '在他夢中一口氣走上了星空'], [234.4, '喧囂地亮起整個夏天渴望的揮霍']], poster: 1 },
    en: { lines: [[13.2, 'If I could go back to a time before now', '如果能回到此刻以前'], [17.9, 'Before I ever fell down', '回到我还没跌倒的时候']], poster: 0 },
  },
};
const shareSong = () => SHARE_SONGS[Proto.state.lang === 'zh' ? 'zh' : 'en'];

/** Tone → the image's colours as CSS vars. role: 'ai' (tertiary), 'me' (primary), 'lyrics' (today's mapping). */
function shareTone(role, tone) {
  const R = role === 'ai' ? 'tertiary' : 'primary', Rc = R[0].toUpperCase() + R.slice(1);
  const v = (card, ink, back, mark) => `--c-card:var(--${card});--c-ink:var(--${ink});--c-back:var(--${back});--c-mark:var(--${mark || ink})`;
  if (role === 'lyrics') return ({ soft: v('primaryContainer', 'onPrimaryContainer', 'primary'), deep: v('onPrimaryContainer', 'primaryContainer', 'primaryContainer'),
    vivid: v('tertiary', 'onTertiary', 'tertiaryContainer'), paper: v('tertiaryContainer', 'onTertiaryContainer', 'tertiary') })[tone];
  return ({
    soft: v(`${R}Container`, `on${Rc}Container`, R),
    deep: v(`on${Rc}Container`, `${R}Container`, `${R}Container`),
    vivid: v(R, `on${Rc}`, `${R}Container`),
    paper: v('surfaceContainerLowest', 'onSurface', `${R}Container`, R),
  })[tone];
}

const scHead = (S, cls = '') => `<div class="sc-head ${cls}">${cover(S.k, 'sc-cv')}<div class="sc-ht"><b class="ell">${S.t}</b><small class="ell">${S.s}</small></div></div>`;
/** The foot: the Yoin mark + "Yoin"; the end slot says who wrote the image's words. */
const scFoot = (end) => `<div class="sc-foot">${yoinMark(22)}<b>Yoin</b><span class="sp"></span>${end || ''}</div>`;
const aiSign = () => `<span class="sc-sign">${ic('sparkle', 14)}${loc('By Gemini', '由 Gemini 整理')}</span>`;
const meSign = (S) => `<span class="sc-sign">${loc(`Written ${S.note.when}`, `写于 ${S.note.when}`)}</span>`;
/** A note's Markdown run: strings, { b } bold, { m } mention pill (cover + name on a 12 % pill of the ink). */
function scMd(segs) {
  return segs.map((s) => typeof s === 'string' ? s : s.b ? `<b>${s.b}</b>` : `<span class="sc-mp">${cover(s.k, 'sc-mp-th' + (s.m === 'artist' ? ' round' : ''))}<b>${s.t}</b></span>`).join('');
}
const scTime = (S, { total = false } = {}) => `<span class="sc-time num">${ic('play_filled', 12)}${mmss(S.note.at)}${total ? `<i>/ ${mmss(S.dur)}</i>` : ''}</span>`;

/* ── About ── */
function aboutA(S) {
  const rows = S.credits.map(([l, v], i) => `${i ? '<i class="sc-dash"></i>' : ''}<div class="sc-rr"><span>${l}</span><b>${v}</b></div>`).join('');
  return `${scHead(S)}<div class="sc-rcpt">${rows}</div><div class="sc-para"><h4>${loc('About', '关于')}</h4><p>${S.about}</p></div>${scFoot(aiSign())}`;
}
function aboutB(S) {
  const chips = S.credits.slice(0, 3).map(([l, v]) => `<span class="sc-chip"><small>${l}</small>${v}</span>`).join('');
  return `<div class="sc-tag">${ic('sparkle', 14)}${loc('About this song', '关于这首歌')}</div><p class="sc-quote rond">${S.about}</p>` +
    `<div class="sc-chips">${chips}</div><div class="sc-rule"></div>${scHead(S, 'sm')}${scFoot(aiSign())}`;
}
function aboutC(S) {
  const cells = S.credits.slice(0, 4).map(([l, v]) => `<div class="sc-cell"><small>${l}</small><b>${v}</b></div>`).join('');
  return `<div class="sc-ctop"><span class="sc-over">${loc('About', '关于')}</span>${cover(S.k, 'sc-cv-c')}</div>` +
    `<h2 class="sc-big rond">${S.t}</h2><div class="sc-by">${S.s} · ${S.year}</div><div class="sc-grid">${cells}</div>` +
    `<p class="sc-body">${S.about}</p>${scFoot(aiSign())}`;
}
/* ── Note ── */
function noteA(S) {
  return `${scHead(S)}<div class="sc-ntop">${scTime(S)}</div><div class="sc-note rond">${S.note.md.map((p) => `<p>${scMd(p)}</p>`).join('')}</div>${scFoot(meSign(S))}`;
}
function noteB(S, story) {
  return `<div class="sc-dots"></div><div class="sc-nb">${story ? '<span class="sp"></span>' : ''}<div class="sc-nb-top"><span class="sc-nb-cv">${cover(S.k)}</span>` +
    `<span class="sc-stub"><b>${S.t}</b><small>${S.s}</small><span class="sc-stub-t num">${ic('play_filled', 10)}${mmss(S.note.at)}</span></span></div>` +
    `<div class="sc-clip"><div class="sc-note-b">${S.note.md.map((p) => `<p>${scMd(p)}</p>`).join('')}</div><small>${S.note.when}</small></div>` +
    `${story ? '<span class="sp"></span>' : ''}${scFoot()}</div>`;
}
function noteC(S) {
  const W = 312, f = S.note.at / S.dur, x = Math.round(W * f), amp = 3.2, wl = 16;
  let d = 'M0 16';
  for (let i = 1; i <= x - 6; i++) d += `L${i} ${(16 + amp * Math.sin((i / wl) * Math.PI * 2)).toFixed(2)}`;
  const wave = `<svg class="sc-wave" width="100%" height="32" viewBox="0 0 ${W} 32" preserveAspectRatio="none"><path d="${d}" class="pl"/><path d="M${x + 6} 16H${W}" class="tr"/><rect x="${x - 2}" y="4" width="4" height="24" rx="2" class="hd"/></svg>`;
  const tip = Math.max(18, Math.min(W - 18, x)), tipPct = (tip / W * 100).toFixed(1);
  return `${scHead(S)}<div class="sc-tl"><div class="sc-tl-t" style="left:${tipPct}%">${scTime(S)}</div>${wave}<div class="sc-tl-ends num"><span>0:00</span><span>${mmss(S.dur)}</span></div></div>` +
    `<div class="sc-bub" style="--tip:${tipPct}%"><div class="sc-note-c">${S.note.md.map((p) => `<p>${scMd(p)}</p>`).join('')}</div></div>${scFoot(meSign(S))}`;
}
/* ── Lyrics (today's card, for comparison) ── */
const shareLyrics = () => SHARE_SONGS.lyrics[Proto.state.lang === 'zh' ? 'zh' : 'en'];
/** Lyrics A, Classic: today's card (LyricsShareCard), only square-cornered. Translation under each line when on. */
function lyricsCard(S) {
  const L = shareLyrics();
  return `${scHead(S)}<div class="sc-lines rond">${L.lines.slice(0, 2).map(([, l, tr]) => `<p>${l}${tr ? `<small>${tr}</small>` : ''}</p>`).join('')}</div>${scFoot()}`;
}
/** Lyrics B, Poster: ONE line at display size, its translation under it, the song small at the foot with the cover.
   For a pick of one or two short lines; longer picks step the size down (as lyricsCardLineSize does). */
function lyricsPoster(S) {
  const L = shareLyrics(), [t, l, tr] = L.lines[L.poster];
  return `<div class="sc-pq">“</div><p class="sc-pl rond">${l}</p>${tr ? `<p class="sc-ptr">${tr}</p>` : ''}<span class="sp"></span>` +
    `<div class="sc-pfoot">${cover(S.k, 'sc-cv')}<div class="sc-ht"><b class="ell">${S.t}</b><small class="ell">${S.s} · <span class="num">${mmss(t)}</span></small></div><span class="sp"></span>${yoinMark(22)}</div>`;
}
/** Lyrics C, Timeline: the lines with their times in a 44dp column (like the Note page), and where the pick sits in
   the song: the track line with the pick's span as a solid bar (at least 20dp, so a short pick still shows) and
   its time range in a chip above — Note C's language for lyrics. */
function lyricsTimeline(S) {
  const L = shareLyrics(), a = L.lines[0][0], b = L.lines[L.lines.length - 1][0] + 4;
  const p0 = a / S.dur * 100, p1 = Math.max(b / S.dur * 100, p0 + 6);
  const range = `<span class="sc-time num">${ic('play_filled', 12)}${mmss(a)}<i>– ${mmss(b)}</i></span>`;
  return `${scHead(S)}<div class="sc-lt">${L.lines.map(([t, l, tr]) => `<div class="sc-ltr"><span class="num">${mmss(t)}</span><p class="rond">${l}${tr ? `<small>${tr}</small>` : ''}</p></div>`).join('')}</div>` +
    `<div class="sc-ltw"><div class="sc-ltw-chip" style="left:${(p0 + p1) / 2}%">${range}</div><div class="sc-ltbar"><i style="left:${p0}%;width:${p1 - p0}%"></i></div><div class="sc-tl-ends num"><span>0:00</span><span>${mmss(S.dur)}</span></div></div>${scFoot()}`;
}
/** Lyrics D, Vertical (CJK only): the lines set top to bottom, right to left, like a poem; title and artist as a
   vertical column on the left, the cover and the mark at the foot. Offered only when every picked line is CJK. */
function lyricsVertical(S, story) {
  const L = shareLyrics();
  return `<div class="sc-vt"><div class="sc-vside"><b>${S.t}</b><small>${S.s}</small></div><div class="sc-vlines rond">${L.lines.slice(0, 2).map(([, l]) => `<p>${l.replace(/ /g, '　')}</p>`).join('')}</div></div>` +
    `<div class="sc-vfoot">${cover(S.k, 'sc-cv')}<span class="sp"></span>${yoinMark(22)}<b>Yoin</b></div>`;
}
const SHARE_DRAW = { about: { A: aboutA, B: aboutB, C: aboutC }, note: { A: noteA, B: noteB, C: noteC }, lyrics: { A: lyricsCard, B: lyricsPoster, C: lyricsTimeline, D: lyricsVertical } };
/** Each style's layouts and their names in the layout row. */
const SHARE_LAYOUTS = {
  lyrics: () => [['A', loc('Classic', '经典')], ['B', loc('Poster', '海报')], ['C', loc('Timeline', '时间线')], ...(Proto.state.lang === 'zh' ? [['D', loc('Vertical', '竖排')]] : [])],
  about: () => [['A', loc('Receipt', '小票')], ['B', loc('Quote', '引文')], ['C', loc('Liner notes', '内页')]],
  note: () => [['A', loc('Big type', '大字')], ['B', loc('Clipping', '剪贴')], ['C', loc('Timeline', '时间线')]],
};

/**
 * One share image at its logical size. Card: the card IS the image, square-cornered, its colour to every edge.
 * Story: the backdrop to every edge, the card centred in the band stories leave clear (28 / 72, as today) — except
 * About C and Note B, whose story is the whole poster (no card inside).
 */
function shareImage({ kind, v, fmt, tone }) {
  const S = shareSong(), role = kind === 'about' ? 'ai' : kind === 'note' ? 'me' : 'lyrics';
  const story = fmt === 'story', poster = (kind === 'about' && v === 'C') || (kind === 'note' && v === 'B') || (kind === 'lyrics' && (v === 'B' || v === 'D'));
  const inner = SHARE_DRAW[kind][v](S, story);
  const cls = `sc sc-${kind}-${v} ${kind === 'note' && v === 'B' ? 'paperbg' : ''}`;
  if (!story) return `<div class="si si-card" style="${shareTone(role, tone)}"><div class="${cls}">${inner}</div></div>`;
  if (poster) return `<div class="si si-story poster" style="${shareTone(role, tone)}"><div class="${cls}">${inner}</div></div>`;
  return `<div class="si si-story" style="${shareTone(role, tone)}"><div class="${cls} in-story">${inner}</div></div>`;
}

/**
 * Story: a short pick ZOOMS IN as well as out (owner 2026-11-25: Story may enlarge short content). The card is
 * laid out narrower (width / z, so lines wrap the same way they would at a larger size) and drawn back at z, the
 * largest z ≤ 1.4 whose height still fits the 72dp-clear band. Mirrors zoomOutToFit, with the cap raised above 1.
 */
function seatStoryZoom(root) {
  for (const el of root.querySelectorAll('.si-story:not(.poster) .sc.in-story')) {
    const band = el.parentElement, W = band.clientWidth - 56, H = band.clientHeight - 144;
    el.style.transform = ''; el.style.width = '';
    let z = 1;
    for (let c = 1.4; c > 1; c -= 0.02) { el.style.width = W / c + 'px'; if (el.offsetHeight * c <= H) { z = c; break; } }
    el.style.width = W / z + 'px'; el.style.flex = 'none'; el.style.transform = z > 1 ? `scale(${z.toFixed(3)})` : '';
  }
}

/* ── The share sheet, round 2 (owner 2026-11-25: keep all layouts, add a row to pick one) ──
   Top to bottom: the three styles as the NP's own text tabs (歌词 关于 笔记; opens on the tab you came from), the
   LAYOUT ROW, the preview, Card / Story pills (unchanged), four swatches from the style's role, Save / Share.
   Layout row, recommended = thumbs: each layout as a small live thumbnail of the image itself (current format and
   tone) with its name under it; the picked one gets a 2dp primary ring and its name in primary.
   Alternative = chips: M3 filter chips with the names only (secondaryContainer + ✓ when picked), so they never
   read as a second Card / Story toggle. Expanded (≥ 840): the sheet is two panes, the preview at its full height
   on the left, the controls on the right, Save / Share pinned at the bottom of the right pane. */
function shareLayoutRow(st, kind, role) {
  const L = SHARE_LAYOUTS[kind]();
  if (st.picker === 'chips') {
    return `<div class="sh-chips">${L.map(([v, name]) => `<span class="sh-chip ${v === st.v ? 'on' : ''}">${v === st.v ? ic('check', 18) : ''}${name}</span>`).join('')}</div>`;
  }
  return `<div class="sh-thumbs">${L.map(([v, name]) => `<span class="sh-th ${v === st.v ? 'on' : ''}"><span class="sh-th-box ${st.fmt}"><span class="sh-th-in">${shareImage({ kind, v, fmt: st.fmt, tone: st.tone })}</span></span><small>${name}</small></span>`).join('')}</div>`;
}
function shareSheet(st, size) {
  const S = shareSong(), kind = st.tab, role = kind === 'about' ? 'ai' : kind === 'note' ? 'me' : 'lyrics';
  const v = SHARE_LAYOUTS[kind]().some(([x]) => x === st.v) ? st.v : 'A', s2 = { ...st, v };
  const tabs = [['lyrics', loc('Lyrics', '歌词')], ['about', loc('About', '关于')], ['note', loc('Note', '笔记')]];
  const toneNames = role === 'lyrics' ? ['soft', 'deep', 'vivid', 'mist'] : ['soft', 'deep', 'vivid', 'paper'];
  const sw = ['soft', 'deep', 'vivid', 'paper'].map((t, i) => `<span class="sh-sw ${toneNames[i]} ${t === st.tone ? 'on' : ''}" style="${shareTone(role, t)}">${t === st.tone ? ic('check', 20) : ''}</span>`).join('');
  const head = `<div class="np-ttabs sh-tabs">${tabs.map(([k, t]) => `<span class="${k === kind ? 'on' : ''}">${t}</span>`).join('')}</div>`;
  const prev = `<div class="sh-prev"><div class="sh-prev-in">${shareImage({ kind, v, fmt: st.fmt, tone: st.tone })}</div></div>`;
  const ctl = `<div class="sh-lbl">${loc('Layout', '版式')}</div>${shareLayoutRow(s2, kind, role)}`;
  const fmt = `<div class="np-tgroup sh-fmt"><span class="${st.fmt === 'card' ? 'on' : ''}">${loc('Card', '卡片')}</span><span class="${st.fmt === 'story' ? 'on' : ''}">Story</span></div>`;
  const act = `<div class="sh-act"><button class="sh-save">${loc('Save', '保存')}</button><button class="sh-share">${ic('share', 20)}${loc('Share', '分享')}</button></div>`;
  const bg = size.cls === 'expanded'
    ? `<div class="sh-bg">${npBackground()}<div class="sh-np wide">${npTopBar({ album: { k: S.k }, from: S.album })}</div><div class="sh-scrim"></div></div>`
    : `<div class="sh-bg">${npBackground()}<div class="sh-np">${npTopBar({ album: { k: S.k }, from: S.album }, { docked: true })}</div><div class="sh-scrim"></div></div>`;
  if (size.cls === 'expanded') {
    return `${bg}<div class="sh wide"><i class="sh-handle"></i><div class="sh-panes"><div class="sh-left">${prev}</div><div class="sh-right">${head}${ctl}<div class="sh-lbl">${loc('Format and colours', '格式与配色')}</div>${fmt}<div class="sh-sws">${sw}</div><span class="sp"></span>${act}</div></div></div>`;
  }
  return `${bg}<div class="sh"><i class="sh-handle"></i>${head}${ctl}${prev}${fmt}<div class="sh-sws">${sw}</div>${act}</div>`;
}
/** Thumbnails: the real image, scaled to the thumb's width (card: cropped at the top; story: whole, centred). */
function seatShareThumbs(dev) {
  for (const box of dev.querySelectorAll('.sh-th-box')) {
    const inr = box.querySelector('.sh-th-in'), img = inr.firstElementChild, k = box.classList.contains('story') ? box.clientHeight / 640 : box.clientWidth / 360;
    inr.style.transform = `scale(${k})`; inr.style.left = `${(box.clientWidth - 360 * k) / 2}px`;
    if (img) img.style.pointerEvents = 'none';
  }
}
/** Fit the preview: phone = never enlarged, whatever height the sheet leaves under the status bar; wide = the left pane, up to 1.5×. */
function seatSharePreview(dev) {
  const box = dev.querySelector('.sh-prev'), img = dev.querySelector('.sh-prev-in'), sheet = dev.querySelector('.sh');
  if (!box || !img) return;
  const w = img.offsetWidth, h = img.offsetHeight;
  let avail;
  if (sheet.classList.contains('wide')) avail = box.parentElement.clientHeight;
  else { box.style.height = '0px'; avail = dev.offsetHeight - 36 - 24 - sheet.offsetHeight; }
  // the preview is not the PNG: on a wide sheet it may draw larger than the logical size to use the pane
  const k = Math.min(sheet.classList.contains('wide') ? 1.5 : 1, box.clientWidth / w, avail / h);
  img.style.transform = `scale(${k})`; box.style.height = `${Math.ceil(h * k)}px`; img.style.left = `${(box.clientWidth - w * k) / 2}px`;
}
