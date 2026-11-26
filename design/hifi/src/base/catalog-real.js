/* The real catalogue (?catalog=real): the owner's own library, the albums yoin-site's figures have always shown,
   with their real covers, so the website's exported frames match its older changelog entries. The covers are
   NOT in this repo: they are yoin-site's public/img/music/<key>-512.webp, read from ?covers=<url> (export.mjs
   passes the sibling yoin-site checkout; --format html inlines them). The default demo catalogue (SVG covers,
   fictional artists) stays the default, for Notion and the 200 KiB limit.
   Metadata comes from yoin-site (src/yoin-mock.src.js: titles, artists, years, the one lyric excerpt the site
   shows per song, the owner's sample notes and ratings) and, for Titanic Rising's running order and lengths,
   NetEase / QQ Music / LRCLIB (2026-11-25); released April 5, 2019. Each language gets its own demo content:
   English on ?lang=en (Weyes Blood), Chinese on ?lang=zh (伊能静 playing; the album page stays Titanic Rising,
   the one album whose tracks are on record, with the notes written in Chinese). The receipt stays English. */
const REAL = {
  // key = yoin-site's cover file name. h / h2 / c: OKLCH hues and chroma scale from yoin-site; colors: its swatches
  albums: {
    weyes: { t: 'Titanic Rising', s: 'Weyes Blood', year: 2019, h: 257, h2: 60, c: .085, colors: ['#2B4974', '#261F20', '#7D6B67', '#D4BFAC'] },
    annie: { t: '关不住', s: '伊能静', year: 2001, h: 195, h2: 15, c: .045, colors: ['#E9EDEC', '#2A171A', '#B4B8B7', '#7E7573'] },
    soda: { t: '夏 / 狂熱', s: '苏打绿', year: 2009, h: 30, h2: 55, c: .1, colors: ['#020101', '#9F5350', '#D0865F', '#4F3334'] },
    tuno: { t: '南北寻光', s: 'Tuno桐音', year: 2017, h: 190, h2: 45, c: .065, colors: ['#6B4F47', '#8B7B67', '#BAB18C', '#BEDFDF'] },
    cocteau: { t: 'Heaven or Las Vegas', s: 'Cocteau Twins', year: 1990, h: 31, h2: 299, c: .11, colors: ['#E13E28', '#4D4361', '#7F3934', '#CE6A59'] },
    sophie: { t: 'OIL OF EVERY PEARL’S UN-INSIDES', s: 'SOPHIE', year: 2018, h: 340, h2: 300, c: .075, colors: ['#735B64', '#947C8F', '#3C2F32', '#C2B6C1'] },
    ciacia: { t: '她的。發光搖擺', s: '何欣穗', year: 2002, h: 46, h2: 30, c: .11, colors: ['#A25139', '#E6804E', '#FEFDFC', '#352522'] },
  },
  // Titanic Rising, in order, with lengths (s)
  titanic: [['A Lot’s Gonna Change', 261], ['Andromeda', 280], ['Everyday', 307], ['Something to Believe', 286], ['Titanic Rising', 96],
    ['Movies', 354], ['Mirror Forever', 306], ['Wild Time', 369], ['Picture Me Better', 222], ['Nearer to Thee', 66]],
};
const realM = (m, t, k) => ({ m, t, k });
const REAL_LANG = {
  en: {
    np: { title: 'A Lot’s Gonna Change', k: 'weyes', rating: 9.2, fmt: 'FLAC', pos: 49, dur: 261 },
    lyrics: { cur: 1, lines: ['If I could go back to a time before now', 'Before I ever fell down', 'Go back to a time when I was just a girl', 'When I had the whole world'] },
    notes: [
      { at: 8, segs: ['Lights off for the piano intro'] },
      { at: 44, segs: ['Plays like the other half of ', realM('song', 'Andromeda', 'weyes')], sub: [{ b: 'strings' }, ' come in right after'] },
      { at: 102, segs: ['The chorus I wait for'] },
      { at: 148, segs: ['Strings swell, headphones on — same night as ', realM('album', 'Heaven or Las Vegas', 'cocteau')] },
      { at: null, segs: ['Pairs with ', realM('album', 'OIL OF EVERY PEARL’S UN-INSIDES', 'sophie'), ' on a long drive'] },
    ],
    playing: { t: 'A Lot’s Gonna Change', s: 'Weyes Blood', k: 'weyes' }, cur: 0,
    last: ['Today', '02:44'], review: ['Headphones on, volume up', 'Yesterday'],
    stickies: [['Best with headphones, late at night.'], ['Side B starts at ', realM('song', 'Movies', 'weyes'), ' — the slow half']],
    clusterNotes: [[['0:08', ['Lights off for the piano intro']]], [['0:40', ['the ', { b: 'harp' }, ' under the second verse']]], [['0:12', ['Feels like a sunny afternoon']], ['1:01', ['Turn it up here']], ['1:26', ['My favourite line on the album']]]],
    mentions: [
      { k: 'cocteau', t: 'Heaven or Las Vegas', kind: 'Note', at: '0:42', when: '8 minutes ago', text: ['Same late-night feeling as ', 'Titanic Rising'] },
      { k: 'sophie', t: 'Immaterial', kind: 'Note', at: '1:55', when: '2 days ago', text: ['Right after ', 'Titanic Rising', ', the whole thing'] },
      { k: 'annie', t: '关不住', kind: 'Album note', when: '3 days ago', text: ['Sunday mornings, with ', 'Titanic Rising'] },
    ],
  },
  zh: {
    np: { title: '分裂', k: 'annie', rating: 8.7, fmt: 'FLAC', pos: 36, dur: 228 },
    lyrics: { cur: 2, lines: ['我想往前也想后退所以随便', '每多说一遍抱歉', '就多抽一根烟', '我拿起你的照片'] },
    notes: [
      { at: 25, segs: ['人声离耳朵很近'] },
      { at: 33, segs: ['听完接着放 ', realM('song', '他夏了夏天', 'soda')], sub: [{ b: '副歌' }, '一样是往前又往后'] },
      { at: 47, segs: ['每次都会跟着唱这句'] },
      { at: 77, segs: ['副歌前的那一拍停顿，像 ', realM('song', '於是', 'ciacia'), ' 里的那口烟'] },
      { at: null, segs: ['长途开车的时候配 ', realM('album', '南北寻光', 'tuno')] },
    ],
    playing: { t: '分裂', s: '伊能静', k: 'annie' }, cur: -1,
    last: ['今天', '02:44'], review: ['戴上耳机，音量调大', '昨天'],
    stickies: [['深夜戴耳机听最好。'], ['B 面从 ', realM('song', 'Movies', 'weyes'), ' 开始，慢下来的那一半']],
    clusterNotes: [[['0:08', ['前奏的钢琴，适合关灯听']]], [['0:40', ['第二段主歌下面的', { b: '竖琴' }]]], [['0:12', ['像一个晴朗的午后']], ['1:01', ['这里开大声']], ['1:26', ['整张专辑最喜欢的一句']]]],
    mentions: [
      { k: 'annie', t: '分裂', kind: 'Note', at: '0:47', when: '8 minutes ago', text: ['听完 ', 'Titanic Rising', '，接着放这首'] },
      { k: 'soda', t: '他夏了夏天', kind: 'Note', at: '1:13', when: '2 days ago', text: ['和 ', 'Titanic Rising', ' 一样适合闷热的午后'] },
      { k: 'tuno', t: '南北寻光', kind: 'Album note', when: '3 days ago', text: ['长途开车：先放这张，再放 ', 'Titanic Rising'] },
    ],
  },
};
/** Swaps the demo data for the real catalogue, in place (the pages' data are consts). Called once by Proto.init. */
function useRealCatalog(lang, base) {
  const L = REAL_LANG[lang === 'zh' ? 'zh' : 'en'], A = REAL.albums;
  for (const [k, a] of Object.entries(A)) {
    COVER_IMG[k] = `${base}${k}-512.webp`;
    ART[k] = { base: a.colors[0], acc: a.colors[3], seed: k };
    SEEDS[k] = { name: a.t, h: a.h, c: Math.min(.14, a.c * 1.35), s: a.h, sc: .04, t: a.h2, tc: .08, n: Math.min(.024, a.c * .22) };
  }
  const al = (k) => ({ t: A[k].t, s: A[k].s, k, kind: 'album' });
  Object.assign(NOW_PLAYING, L.playing);
  if (typeof NP_TRACKS !== 'undefined') {
    const a = A[L.np.k];
    NP_TRACKS.glass = { title: L.np.title, album: al(L.np.k), artist: a.s, rating: L.np.rating, fmt: L.np.fmt, pos: L.np.pos, dur: L.np.dur, from: a.t };
  }
  if (typeof LYRICS !== 'undefined') { LYRICS.glass = L.lyrics; LYRIC_TR.glass = []; }
  if (typeof NOTES !== 'undefined') NOTES.splice(0, NOTES.length, ...L.notes);
  if (typeof ALBUMS !== 'undefined') {
    const tr = REAL.titanic.map(([t, s], i) => [t, `${Math.floor(s / 60)}:${String(s % 60).padStart(2, '0')}`, [0, 1, 2].includes(i) ? 'n' : '']);
    // album score 8.4; three tracks rated (A Lot’s Gonna Change 9.2, Andromeda 7.6, Everyday 8.6)
    ALBUMS.harbor = { a: al('weyes'), year: 2019, date: [2019, 4, 5], kind: 'album', score: '8.4', rated: [1, 1, 1, 0, 0, 0, 0, 0, 0, 0], last: L.last, review: L.review,
      tracks: tr, bands: [[257, .09, 3], [25, .02, 2], [35, .03, 2], [60, .05, 1], [250, .06, 2]], cur: L.cur, liked: [0] };
  }
  if (typeof SCRAP !== 'undefined') {
    const n = L.clusterNotes;
    SCRAP.harbor = {
      credits: [['Label', '厂牌', 'Sub Pop', 'Sub Pop'], ['Producer', '制作', 'Jonathan Rado, Natalie Mering', 'Jonathan Rado、Natalie Mering']],
      about: [
        'Recorded mostly analog with Jonathan Rado in Los Angeles, Titanic Rising frames Natalie Mering’s classic studio chamber pop around sweeping strings, piano ballads, and an understated eye on modern longing.',
        '由 Natalie Mering 与制作人 Jonathan Rado 主要以模拟录音室设备录制完成，《Titanic Rising》以弦乐铺陈、钢琴叙事曲与复古软摇滚音色织出宏大而克制的当代抒情专辑。',
      ],
      stickies: L.stickies,
      clusters: [
        { n: '01', t: 'A Lot’s Gonna Change', d: '4:21', plays: 6, score: '9.2', best: true, liked: true, notes: n[0] },
        { n: '02', t: 'Andromeda', d: '4:40', plays: 4, score: '7.6', notes: n[1] },
        { n: '03', t: 'Everyday', d: '5:07', plays: 14, score: '8.6', notes: n[2], more: 1 },
      ],
      tags: [],
      receipt: [['Plays in Yoin', '24'], ['Days since first play', '512'], ['Last played', 'Today'], ['Rated', '3 / 10'], ['Notes', '8'], ['Asked', '0']],
      mentions: L.mentions,
    };
  }
}
