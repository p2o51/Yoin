/* Lyrics (LyricsViewport, LyricsFullscreenPane, LyricsActionBar, FullscreenTabGroup, SingControls).
   Demo LRC per demo track (fictional, from the demo account). cur = index of the playing line.
   Line-by-line highlight only (no word-level QRC, by owner decision). */
const LYRICS = {
  glass: { cur: 5, lines: ['Hold on to the afterglow', 'Paper lanterns, carry me home', 'I folded the evening into paper lanterns', 'Let them drift where the harbor meets the sky', 'Every word a place where you and I', 'Every light a word I never said', 'Could stand a little longer in the glow', 'Before the tide decides what we can keep', 'Oh, the city hums a song I used to know', 'Paper lanterns, carry me to sleep', 'We were static on a radio at midnight', 'Two voices looking for the same old frequency', 'You said the stars are only late reflections', 'Of a fire that burned before we came to be', 'So let it burn, let it fade, let it go', 'Somewhere a ferry blinks across the bay'] },
  yehang: { cur: 3, lines: ['雾从海面升起来', '灯塔数着每一艘船', '你说远方只是另一种近', '我把名字留在潮水里', '夜航的人不说再见', '只把灯光留给岸边', '风翻过旧船票的背面', '那一行字还没有褪色', '北方的信还在路上', '我们在回声里等待', '灯亮了，又暗了', '像一句没说完的话'] },
  harbor: { cur: 4, lines: ['The ferry hums below the bridge', 'Count the lights along the pier', 'Every wave a postcard home', 'I wrote your name in harbor rain', 'Let the engine keep the time', 'Slow tide, carry what we said', 'Night is just the long way round', 'Hold the rail and watch it fade', 'Harbor lights, don’t let me go', 'We were small against the sea', 'Static on the radio', 'Morning finds us on the shore'] },
};
/** CompactTextTabs: Lyrics About Note, 12 apart; the selected one is onSurface and a touch larger. */
const NP_TABS = () => [['lyrics', loc('Lyrics', '歌词')], ['about', loc('About', '关于')], ['note', loc('Note', '笔记')]];
function textTabs(active) {
  return `<div class="np-ttabs">${NP_TABS().map(([k, t]) => `<span class="${k === active ? 'on' : ''}">${t}</span>`).join('')}</div>`;
}
/** FullscreenTabGroup: three equal pills (the lyrics / note pages). */
function tabGroup(active) {
  return `<div class="np-tgroup">${NP_TABS().map(([k, t]) => `<span class="${k === active ? 'on' : ''}">${t}</span>`).join('')}</div>`;
}
/** A scrolling lyric list; the page's after() hook seats the current line at --anchor of the window. */
function lyricList(key, { mode = 'compact', heading = null } = {}) {
  const L = LYRICS[key];
  const head = heading ? `<div class="ly-head"><b>${heading[0]}</b><span>${heading[1]}</span></div>` : '';
  return `<div class="ly ly-${mode}"><div class="ly-in">${head}${L.lines.map((l, i) => `<p class="${i === L.cur ? 'cur rond' : i < L.cur ? 'past' : ''}">${l}</p>`).join('')}</div></div>`;
}
/** LyricsActionBar: search · translate · select (✓) · recenter (⇣⇡, "Return to current line" — disabled while the
   list follows the playhead, enabled once you scroll away), then the 5th key, Sing (sing branch).
   Keys = FilledTonalIconButton 52, corner 0.31 × size, primaryContainer / onPrimaryContainer (LyricsActionBar.kt).
   Sing off = the same key with the mic; on = the 124 short capsule: surfaceVariant track + primary flat-edged
   fill, the detent notch at max(34, 124 × 0.342) = 42.4 (the default gain, SingGainCurve), mic on the left = tap
   to exit (onSurfaceVariant, onPrimary where the fill covers it), no % text. */
function toolRow({ sing = false, noSing = false, level = SING_DEFAULT, translate = false, recenter = false } = {}) {
  const k = (i, cls = '', label = '') => `<button class="lk ${cls}" ${label ? `aria-label="${label}"` : ''}>${ic(i, 24)}</button>`;
  const notch = Math.max(34, 124 * SING_DEFAULT), lit = level * 124 >= notch;
  const singKey = sing
    ? `<button class="lk sing on" aria-label="Vocal volume"><i class="sfill" style="width:${(level * 100).toFixed(1)}%"></i><i class="snotch ${lit ? 'lit' : ''}" style="left:${notch.toFixed(1)}px"></i><span class="smic">${ic('mic', 18)}</span><span class="smic onf" style="clip-path:inset(0 ${Math.max(0, 100 - (level * 124 - 8) / 26 * 100).toFixed(1)}% 0 0)">${ic('mic', 18)}</span></button>`
    : k('mic', 'sing', 'Sing');
  return `<div class="np-tools">${k('search', '', 'Search lyrics')}${k('translate', translate ? 'tr-on' : '', 'Translate lyrics')}${k('check', '', 'Select lyrics')}${k('recenter', recenter ? '' : 'off', 'Return to current line')}${noSing ? '' : singKey}</div>`;
}
/** Lyrics page "About" receipt, compact. */
function aboutBlock(t) {
  return `<div class="np-about"><p><b>${t.from}</b> · ${t.album.s} · 2019</p><p>${t.fmt.replace(' ', ' · ')} kHz · Navidrome (demo)</p><p>Written by ${t.artist}</p><p>Played 14 times · first on Oct 2</p></div>`;
}
/* Full-screen lyrics (LyricsFullscreenPane): headlineSmall 24 / 32 left-aligned rows (12 inset, 6 vertical, 4 apart);
   the playing line SemiBold ROND in primary, the others Medium onSurfaceVariant at 0.96 scale, alpha by distance
   (±1 .62, ±2 .5, else .4); the first row is a title card (title + artist). Translation ON puts the line's
   translation inside the same row: bodyLarge 16 / 24, 4 above, the row's colour at .82 (playing) / .7. */
const LYRIC_TR = {
  glass: ['紧握这片余晖', '纸灯笼，带我回家', '我把黄昏折成纸灯笼', '让它们漂向海港与天空相接的地方', '每个字都是你我', '每一盏灯都是我没说出口的话', '能在光里多站一会儿的地方', '在潮水决定我们能留下什么之前', '哦，这城市哼着我曾熟悉的歌', '纸灯笼，带我入睡', '我们是午夜收音机里的杂音', '两个声音寻找同一个旧频率', '你说星星只是迟到的倒影', '是我们出现之前就燃尽的火', '那就让它燃烧，让它消逝，让它走', '远处一艘渡轮在海湾闪烁'],
  yehang: ['Fog rises from the sea', 'The lighthouse counts every boat', 'You said far is just another kind of near', 'I left my name in the tide', 'Night sailors never say goodbye', 'They leave the light to the shore', 'Wind turns the back of an old ticket', 'That line has not faded yet', 'The letter from the north is still on its way', 'We wait inside the echo', 'The light comes on, and goes out', 'Like a sentence left unfinished'],
};
function lyricListX(key, t, { translate = false, selected = [] } = {}) {
  const L = LYRICS[key], tr = LYRIC_TR[key] || [];
  const row = (txt, sub, d, i) => {
    const a = d === 0 ? 1 : Math.abs(d) === 1 ? .62 : Math.abs(d) === 2 ? .5 : .4;
    const sel = selected.includes(i) ? `sel ${selected.includes(i - 1) ? '' : 'sf'} ${selected.includes(i + 1) ? '' : 'sl'}` : '';
    return `<div class="lx ${d === 0 ? 'cur' : ''} ${sel}" style="--a:${a}"><p class="${d === 0 ? 'rond' : ''}">${txt}</p>${sub ? `<small>${sub}</small>` : ''}</div>`;
  };
  return `<div class="ly lyx ${selected.length ? 'selmode' : ''}"><div class="ly-in">${row(t.title, t.artist, -9, -1).replace('class="lx ', 'class="lx card ')}${L.lines.map((l, i) => row(l, translate ? tr[i] : '', i - L.cur, i)).join('')}</div></div>`;
}
/* Select mode (design.md §Now Playing): adjacent selected rows join into one primaryContainer block (outer corners
   16, inner 4, reaching 12 past the text); unselected rows all at 0.7. */
/** The tool row in select mode: × (primary) · copy · share · edit. */
function selectRow() {
  return `<div class="np-tools"><button class="lk emph" aria-label="Done selecting lyrics">${ic('close', 24)}</button><button class="lk" aria-label="Copy selected lyrics">${ic('copy', 24)}</button><button class="lk" aria-label="Share selected lyrics as an image">${ic('share', 24)}</button><button class="lk" aria-label="Edit lyrics">${ic('edit', 24)}</button></div>`;
}

/** Seat the playing lyric line at the list's --anchor (LyricsFocusFraction / LYRICS_ANCHOR_FRACTION). */
function seatLyrics(root) {
  for (const ly of root.querySelectorAll('.ly')) {
    const cur = ly.querySelector('.cur'), inner = ly.querySelector('.ly-in'); if (!cur) continue;
    const a = parseFloat(getComputedStyle(ly).getPropertyValue('--anchor')) || .3, off = parseFloat(ly.dataset.off || 0);
    const y = cur.offsetTop + cur.offsetHeight / 2 - a * ly.clientHeight + off;
    inner.style.transform = `translateY(${-Math.max(0, Math.min(y, inner.offsetHeight - ly.clientHeight * .55))}px)`;
  }
}

