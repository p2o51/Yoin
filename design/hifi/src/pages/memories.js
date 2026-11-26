/* Page: Memories. Card = the deck (pull down from Home); Diary = the card opened (MemoryDiary); on Expanded both are
   the two-page spread (left: the card's exhibit + Yoin's paragraph, right: the diary). All Memories = the ledger
   Activity (Timeline / Cards / List; month grid; 1 / 2 / 3 lanes). Rediscover = Home's shelf (RediscoverSection):
   it lives on Home, not in Memories, and opens the album / plays the song, never the deck. */
const MEM_CTL = [
  { key: 'view', label: 'View', options: [['card', 'Card'], ['diary', 'Diary'], ['ledger', 'All Memories'], ['rediscover', 'Rediscover (Home)']], def: 'card' },
  { key: 'ledger', label: 'All Memories', options: [['timeline', 'Timeline'], ['cards', 'Cards'], ['list', 'List']], def: 'timeline' },
  { key: 'card', label: 'Memory', options: [['0', 'Harbor Lights'], ['1', 'Glass Orchard'], ['2', '夜航 (unrated)']], def: '0' },
];
function memRediscover(size) {
  const C = CAT, compact = size.cls === 'compact', pad = compact ? 16 : size.cls === 'medium' ? 56 : 32, w = size.w - pad * 2;
  const cw = compact ? Math.round(w * .86) : size.cls === 'medium' ? Math.round((w - 12) / 2) : Math.round((w - 24) / 3);
  const cards = [
    rediscoverCard(C.north, { eyebrow: '7 months away', foot: '2026.04 · 23 plays', score: '8.9' }),
    rediscoverCard({ ...C.huisheng, album: '夜航' }, { eyebrow: '3 notes<br>5 months away', note: 'the hum under the second verse' }),
    rediscoverCard(C.blue, { eyebrow: '4 months away', foot: '2026.07 · 41 plays', score: '7.6', solid: false }),
    rediscoverCard(C.paper, { eyebrow: 'Reviewed<br>6 months away', foot: '2026.06 · 12 plays' }),
  ];
  return `<div class="m-home-x" style="--pad:${pad}px"><div class="mh-head"><span>Good evening</span><b>Home</b></div><div class="mh-sec"><h2>Rediscover</h2><p>Albums and songs you rated or wrote about, not played in a while</p></div>` +
    `<div class="shelf redis-shelf" style="--rw:${cw}px">${cards.join('')}</div></div>`;
}
function renderMem(st, size) {
  const m = MEM_DECK[+st.card], idx = +st.card;
  if (st.view === 'rediscover') return memRediscover(size);
  if (st.view === 'ledger') return ledger(st.ledger, size.cls);
  const inset = size.cls === 'compact' ? 16 : size.cls === 'medium' ? 24 : 32;
  if (size.cls === 'expanded') return `<div class="mem" style="${artVars(m.al.k)}">${memAurora()}${memTopBar(m, { inset, idx })}${memSpread(m, size.w)}</div>`;
  const diary = st.view === 'diary', med = size.cls === 'medium';
  const body = diary ? `<div class="m-scroll ${med ? 'med' : ''}">${memDiary(m, { large: med })}</div>` : memCard(m, { medium: med });
  return `<div class="mem" style="${artVars(m.al.k)}">${memAurora()}${memTopBar(m, { diary, inset, idx })}${body}</div>`;
}
Proto.init({ title: 'Memories', controls: MEM_CTL, scheme: (st) => (st.view === 'card' || st.view === 'diary' ? 'wallpaper' : 'harbor'), render: renderMem });
