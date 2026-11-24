/* Demo covers + catalogue. The app's demo account (fictional artists: The Meridians, Kiri Ono, Ada Vale,
   林间, Sora Hayama) ships generative covers; these are SVG re-drawings of them (no raster), one <symbol>
   each in a hidden sprite, used via cover(key). base / acc are the backdrop colours the app reads off each
   cover (ExpressiveBackdropPalette: base = backdrop shape & card wash, acc = dark-theme ink); seed picks
   the palette.js scheme when that cover is playing. */
const ART = {
  glass: { base: '#c8423f', acc: '#f3a092', seed: 'glass' },
  harbor: { base: '#e07c47', acc: '#f7b585', seed: 'harbor' },
  soda: { base: '#c48aa4', acc: '#f0b8cc', seed: 'glass' },
  north: { base: '#3f5079', acc: '#aebbe0', seed: 'north' },
  blue: { base: '#4f80b0', acc: '#a2c8ec', seed: 'wallpaper' },
  under: { base: '#3f78a8', acc: '#9ccaee', seed: 'wallpaper' },
  yehang: { base: '#3f7d74', acc: '#93d3c4', seed: 'yehang' },
  slow: { base: '#b45585', acc: '#f2a3ca', seed: 'glass' },
  paper: { base: '#d6a63c', acc: '#f3d07c', seed: 'yehang' },
  meri: { base: '#c8423f', acc: '#f3a092', seed: 'glass' },
  rain: { base: '#4a86ad', acc: '#a0d2ee', seed: 'wallpaper' },
  focus: { base: '#4a7aa4', acc: '#a6c8ea', seed: 'wallpaper' },
  late: { base: '#c8423f', acc: '#f3a092', seed: 'glass' },
  sunday: { base: '#d6a63c', acc: '#f3d07c', seed: 'yehang' },
  baizhou: { base: '#c98d3c', acc: '#f2c98a', seed: 'yehang' },
  tidepool: { base: '#3f8a8f', acc: '#9fd8d6', seed: 'yehang' },
  postcards: { base: '#c9643f', acc: '#f4ad8c', seed: 'glass' },
  smallhours: { base: '#3d4a86', acc: '#aab6ee', seed: 'north' },
  kiri: { base: '#e07c47', acc: '#f7b585', seed: 'harbor' },
  ada: { base: '#4f6f9e', acc: '#a9c1e8', seed: 'north' },
  linjian: { base: '#3f7d74', acc: '#93d3c4', seed: 'yehang' },
  sora: { base: '#c48aa4', acc: '#f0b8cc', seed: 'glass' },
};
function coverSprite() {
  // Glass Orchard: 6×6 dots and tiles (read off the real cover)
  const G = ['rc dc ds Cs dc Os', 'rc rc rc Cc Oc rc', 'dc rc ds rs rs ds', 'dc ds ds ds dc ds', 'Os ds rc ds ds Os', 'ds dc Cc ds ds rc'];
  const fill = { r: '#d8404a', d: '#5b1a2d', C: '#f6dcc8', O: '#f0923a' };
  let glass = '<rect width="100" height="100" fill="#2a0e1d"/>';
  G.forEach((row, y) => row.split(' ').forEach((c, x) => {
    const cx = 2 + x * 16.2, cy = 2 + y * 16.2, f = fill[c[0]];
    glass += c[1] === 'c' ? `<circle cx="${cx + 7.6}" cy="${cy + 7.6}" r="7.4" fill="${f}"/>` : `<rect x="${cx}" y="${cy}" width="15.2" height="15.2" fill="${f}"/>`;
  }));
  const stripes = (y0, y1, c) => { let s = ''; for (let y = y0; y < y1; y += 5) s += `<rect y="${y}" width="100" height="1.8" fill="${c}"/>`; return s; };
  const waves = (cols, y0, amp) => cols.map((c, i) => { const y = y0 + i * 13; return `<path d="M0 ${y}C18 ${y - amp} 32 ${y + amp} 50 ${y}S82 ${y - amp} 100 ${y}V100H0Z" fill="${c}"/>`; }).join('');
  const arcs = [56, 44, 32, 20].map((r, i) => `<circle cx="50" cy="104" r="${r}" fill="none" stroke="${i % 2 ? '#a3417a' : '#e2689f'}" stroke-width="7"/>`).join('');
  const sym = (id, body) => `<symbol id="cv-${id}" viewBox="0 0 100 100" preserveAspectRatio="none">${body}</symbol>`;
  const quad = (a, b, c, d) => `<rect width="100" height="100" fill="#fff"/><use href="#cv-${a}" width="50" height="50"/><use href="#cv-${b}" x="50" width="50" height="50"/><use href="#cv-${c}" y="50" width="50" height="50"/><use href="#cv-${d}" x="50" y="50" width="50" height="50"/>`;
  return `<svg class="sprite" aria-hidden="true" width="0" height="0" style="position:absolute;width:0;height:0;overflow:hidden"><defs>
<linearGradient id="g-harbor" x2="0" y2="1"><stop offset="0" stop-color="#2d2250"/><stop offset="1" stop-color="#5f3d6d"/></linearGradient>
<radialGradient id="g-sun" cx=".5" cy=".38" r=".6"><stop offset="0" stop-color="#ffd4a4"/><stop offset="1" stop-color="#ef8d5c"/></radialGradient>
<linearGradient id="g-soda" x2="0" y2="1"><stop offset="0" stop-color="#76b3a5"/><stop offset="1" stop-color="#b3dccb"/></linearGradient>
<radialGradient id="g-glow"><stop offset="0" stop-color="#ffb39c"/><stop offset=".42" stop-color="#f79a92" stop-opacity=".85"/><stop offset="1" stop-color="#f79a92" stop-opacity="0"/></radialGradient>
<linearGradient id="g-north" x2="0" y2="1"><stop offset="0" stop-color="#dfe6ef"/><stop offset="1" stop-color="#a3b5d1"/></linearGradient>
<linearGradient id="g-blue" x2="0" y2="1"><stop offset="0" stop-color="#a9cbe8"/><stop offset="1" stop-color="#6d9ccb"/></linearGradient>
<linearGradient id="g-yehang" x2="0" y2="1"><stop offset="0" stop-color="#1f4a40"/><stop offset=".55" stop-color="#4f9480"/><stop offset="1" stop-color="#9fd2b2"/></linearGradient>
<radialGradient id="g-lamp"><stop offset="0" stop-color="#fff6cf" stop-opacity=".9"/><stop offset="1" stop-color="#fff6cf" stop-opacity="0"/></radialGradient>
<radialGradient id="g-meri" cx=".4" cy=".35"><stop offset="0" stop-color="#ee6a5e"/><stop offset="1" stop-color="#9c2a34"/></radialGradient>
</defs>
${sym('glass', glass)}
${sym('harbor', `<rect width="100" height="100" fill="url(#g-harbor)"/><circle cx="50" cy="55" r="23" fill="url(#g-sun)"/>${stripes(61, 99, '#5a3a6a')}`)}
${sym('soda', `<rect width="100" height="100" fill="url(#g-soda)"/><circle cx="50" cy="50" r="36" fill="url(#g-glow)"/><circle cx="50" cy="50" r="15" fill="#f8a48e"/><rect y="78" width="100" height="22" fill="#c4e6d6" opacity=".55"/>`)}
${sym('north', `<rect width="100" height="100" fill="url(#g-north)"/>${waves(['#8296bd', '#5c71a0', '#3f5383', '#2d3d69'], 44, 7)}`)}
${sym('blue', `<rect width="100" height="100" fill="url(#g-blue)"/><circle cx="80" cy="20" r="6" fill="#f3a57c"/>${waves(['#5b8fc4', '#4479b0', '#346399', '#284f80'], 40, 6)}`)}
${sym('under', `<rect width="100" height="100" fill="#cfe3f1"/><circle cx="68" cy="26" r="9" fill="#f3b08c"/>${waves(['#7fb2d9', '#4f8fc2', '#3b74a8', '#2b5b8b', '#21466f'], 34, 8)}`)}
${sym('yehang', `<rect width="100" height="100" fill="url(#g-yehang)"/><circle cx="50" cy="28" r="20" fill="url(#g-lamp)"/><rect x="48.4" y="30" width="3.2" height="46" rx="1.6" fill="#f2ead0"/><circle cx="50" cy="27" r="4.6" fill="#fff4c6"/>`)}
${sym('slow', `<rect width="100" height="100" fill="#2d1236"/>${arcs}<circle cx="24" cy="22" r="2.6" fill="#f6c66a"/>`)}
${sym('paper', `<rect width="100" height="100" fill="#f4e7d0"/><rect x="14" y="28" width="72" height="9" rx="4.5" fill="#e9774e"/><rect x="14" y="45" width="72" height="9" rx="4.5" fill="#e6b03b"/><rect x="14" y="62" width="72" height="9" rx="4.5" fill="#3b5c8c"/><circle cx="80" cy="76" r="4" fill="#e9774e"/>`)}
${sym('meri', `<rect width="100" height="100" fill="url(#g-meri)"/><circle cx="50" cy="40" r="15" fill="#3b0f1c"/><ellipse cx="50" cy="96" rx="31" ry="27" fill="#3b0f1c"/>`)}
${sym('baizhou', `<rect width="100" height="100" fill="#f1e2c2"/><circle cx="66" cy="38" r="17" fill="#e9a04a"/><rect x="12" y="62" width="76" height="7" rx="3.5" fill="#3f7d74"/><rect x="12" y="75" width="52" height="7" rx="3.5" fill="#c9643f"/>`)}
${sym('tidepool', `<rect width="100" height="100" fill="#cfe7e2"/><circle cx="34" cy="62" r="24" fill="#3f8a8f"/><circle cx="68" cy="40" r="18" fill="#7cc0bb"/><circle cx="62" cy="74" r="10" fill="#e9a77c"/>`)}
${sym('postcards', `<rect width="100" height="100" fill="#f3d9c4"/><rect x="18" y="22" width="52" height="36" rx="3" fill="#c9643f" transform="rotate(-8 44 40)"/><rect x="30" y="44" width="52" height="36" rx="3" fill="#3b5c8c" transform="rotate(6 56 62)"/><circle cx="72" cy="56" r="4" fill="#f3d9c4"/>`)}
${sym('smallhours', `<rect width="100" height="100" fill="#1c2346"/><circle cx="68" cy="32" r="13" fill="#f3e3b0"/><circle cx="74" cy="28" r="11" fill="#1c2346"/>${[[20, 20], [34, 46], [14, 60], [48, 18], [86, 62]].map(([x, y]) => `<circle cx="${x}" cy="${y}" r="1.4" fill="#cfd6ff"/>`).join('')}<path d="M0 82C30 70 60 92 100 76V100H0Z" fill="#3d4a86"/>`)}
${['kiri:#f7b585:#7a3a24', 'ada:#c9d8ef:#2f456f', 'linjian:#bfe3d8:#24524b', 'sora:#f4cfdd:#7b3e5a'].map((d) => { const [id, bg, fg] = d.split(':'); return sym(id, `<rect width="100" height="100" fill="${bg}"/><circle cx="50" cy="40" r="15" fill="${fg}"/><ellipse cx="50" cy="96" rx="31" ry="27" fill="${fg}"/>`); }).join('')}
${sym('rain', quad('yehang', 'harbor', 'north', 'blue'))}
${sym('focus', quad('north', 'blue', 'under', 'north'))}
${sym('late', quad('slow', 'harbor', 'glass', 'soda'))}
${sym('sunday', quad('soda', 'paper', 'harbor', 'yehang'))}
</svg>`;
}
/** A cover as a square block; size/shape come from the caller's CSS (.cv is 100% of its box). */
function cover(key, cls = '') { return `<span class="cv ${cls}"><svg viewBox="0 0 100 100" preserveAspectRatio="none"><use href="#cv-${key}"/></svg></span>`; }
/** Inline palette vars for a card / backdrop: --pal (base), --acc, and the theme ink (--ink). */
function artVars(key) { const a = ART[key] || ART.glass; return `--pal:${a.base};--acc:${a.acc}`; }

/* Catalogue (demo account): what the pages list. kind: album / song / playlist / artist. */
const CAT = {
  glassAlbum: { t: 'Glass Orchard', s: 'The Meridians', k: 'glass', kind: 'album' },
  harbor: { t: 'Harbor Lights', s: 'Kiri Ono', k: 'harbor', kind: 'album' },
  soda: { t: '午後のソーダ', s: 'Sora Hayama', k: 'soda', kind: 'album' },
  north: { t: 'Northern Field Recordings', s: 'Ada Vale', k: 'north', kind: 'album' },
  snow: { t: 'IV. Snowfield', s: 'Ada Vale', k: 'north', kind: 'song' },
  aurora: { t: 'V. Aurora', s: 'Ada Vale', k: 'north', kind: 'song' },
  blue: { t: 'Blue Hour', s: 'Kiri Ono', k: 'blue', kind: 'album' },
  under: { t: 'Undertow', s: 'Kiri Ono', k: 'under', kind: 'song' },
  yehang: { t: '夜航', s: '林间', k: 'yehang', kind: 'album' },
  huisheng: { t: '回声', s: '林间', k: 'yehang', kind: 'song' },
  paper: { t: '纸飞机', s: '林间', k: 'paper', kind: 'album' },
  velvet: { t: 'Velvet Hours', s: 'The Meridians', k: 'slow', kind: 'album' },
  slowdance: { t: 'Slow Dance in a Fast Car', s: 'The Meridians', k: 'slow', kind: 'song' },
  lanterns: { t: 'Paper Lanterns', s: 'Kiri Ono', k: 'harbor', kind: 'song' },
  satellites: { t: 'Satellites', s: 'The Meridians', k: 'glass', kind: 'song' },
  slowtide: { t: 'Slow Tide', s: 'Kiri Ono', k: 'harbor', kind: 'song' },
  glassSong: { t: 'Glass Orchard', s: 'The Meridians', k: 'glass', kind: 'song' },
  lowtide: { t: 'Low Tide Static', s: 'Kiri Ono', k: 'blue', kind: 'album' },
  rain: { t: '雨天通勤', s: 'demo', k: 'rain', kind: 'playlist' },
  focus: { t: 'Deep Focus', s: 'demo', k: 'focus', kind: 'playlist' },
  late: { t: 'Late Night Drive', s: 'demo', k: 'late', kind: 'playlist' },
  sunday: { t: '周日早晨', s: 'demo', k: 'sunday', kind: 'playlist' },
  meridians: { t: 'The Meridians', s: 'Artist', k: 'meri', kind: 'artist' },
  kiri: { t: 'Kiri Ono', s: 'Artist', k: 'kiri', kind: 'artist' },
  ada: { t: 'Ada Vale', s: 'Artist', k: 'ada', kind: 'artist' },
  linjian: { t: '林间', s: 'Artist', k: 'linjian', kind: 'artist' },
  sora: { t: 'Sora Hayama', s: 'Artist', k: 'sora', kind: 'artist' },
  baizhou: { t: '白昼梦', s: '林间', k: 'baizhou', kind: 'album' },
  tidepool: { t: 'Tidepool', s: 'Sora Hayama', k: 'tidepool', kind: 'album' },
  postcards: { t: 'Postcards', s: 'Kiri Ono', k: 'postcards', kind: 'album' },
  smallhours: { t: 'Small Hours', s: 'Ada Vale', k: 'smallhours', kind: 'album' },
  harborMix: { t: 'Harbor Mix', s: 'demo', k: 'harbor', kind: 'playlist' },
};
/** Home's entity → backdrop shape mapping (album Bun, song Circle, playlist Ghostish). */
const KIND_SHAPE = { album: 'Bun', song: 'Circle', playlist: 'Ghostish' };
