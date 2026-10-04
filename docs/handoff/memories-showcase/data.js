/* Sample Album Memories shared by every exploration. Real-shaped data, matching what
   MemoriesDeckCoordinator produces (MemoryEntry): album, palette, score kind, AI title, review,
   Yoin narrative (only when no review), notes (song notes carry a timestamp anchor), evidence. */
window.YS_DATA = {
  memories: [
    {
      id: "m1",
      album: "夜行列车与未寄出的信",
      artist: "椎名林檎 & 东京事变",
      year: 2019,
      palette: { base: "#3b2d8f", accent: "#e2c27a", deep: "#1b1554", soft: "#d9d2f4" },
      cover: { from: "#1c1a6b", to: "#7b4fd8", dot: "#e2c27a" },
      scoreKind: "album",          // album = user's album rating (solid seal)
      score: 9.5,
      ratedTracks: 4, totalTracks: 10,
      aiTitle: "写给自己的、不寄出的信",   // AI title: the ONLY serif text on the card
      aiTitleIsGenerated: true,
      review: "第一次听这张是在去机场的夜班巴士上，窗外的高速路灯一盏一盏往后退，耳机里的弦乐刚好起来。后来每次出远门都会把它从头放到尾，像是给自己写一封不打算寄出去的信。第三首的鼓点让我想起高中操场，第七首的和声又把我拉回现在。它不是那种一听就爱上的专辑，但它会在你需要的时候一直在那里。如果只能留一张专辑陪我坐完一整夜的车，我会选它。",
      reviewWrittenAt: "2026-07-26",
      narrative: null,
      firstPlayed: "2026-03-14", lastPlayed: "2026-10-02", plays: 37,
      neoDb: "synced",
      tracks: [
        { n: 1, title: "序曲", dur: 180, rating: 9.0 },
        { n: 2, title: "Night Bus", dur: 197, rating: 8.5 },
        { n: 3, title: "未寄出的信", dur: 214, rating: 10.0 },
        { n: 4, title: "Glass Harbour", dur: 231, rating: 7.5 },
        { n: 5, title: "看不见的潮汐", dur: 248, rating: null },
        { n: 6, title: "Lemonade", dur: 265, rating: null },
        { n: 7, title: "Satellite Hearts", dur: 282, rating: null },
        { n: 8, title: "雨のち晴れ", dur: 299, rating: null },
        { n: 9, title: "Afterglow", dur: 316, rating: null },
        { n: 10, title: "Paper Moon", dur: 333, rating: null }
      ],
      notes: [
        { track: 3, at: 72, date: "2026-08-02", text: "副歌前那一拍停顿，像信写到一半停笔。" },
        { track: 7, at: 141, date: "2026-07-19", text: "和声把我拉回现在。" }
      ]
    },
    {
      id: "m2",
      album: "Godspeed!",
      artist: "HYUKOH",
      year: 2024,
      palette: { base: "#7a5a14", accent: "#9cc7e8", deep: "#3d2e07", soft: "#efe4c4" },
      cover: { from: "#6b4a10", to: "#d6dc5a", dot: "#86b8e0" },
      scoreKind: "none",           // no album rating, no track ratings: grey / empty seal
      score: null,
      ratedTracks: 0, totalTracks: 12,
      aiTitle: "雨天里的水底吉他",
      aiTitleIsGenerated: true,
      review: null,
      narrative: "Notes around this album are starting to form a memory.",
      firstPlayed: "2026-09-21", lastPlayed: "2026-09-30", plays: 9,
      neoDb: null,
      tracks: [
        { n: 1, title: "序曲", dur: 180 }, { n: 2, title: "Night Bus", dur: 197 }, { n: 3, title: "未寄出的信", dur: 214 },
        { n: 4, title: "Glass Harbour", dur: 231 }, { n: 5, title: "看不见的潮汐", dur: 248 }, { n: 6, title: "Lemonade", dur: 265 },
        { n: 7, title: "Satellite Hearts", dur: 282 }, { n: 8, title: "雨のち晴れ", dur: 299 }, { n: 9, title: "Afterglow", dur: 316 },
        { n: 10, title: "Paper Moon", dur: 333 }, { n: 11, title: "Static", dur: 350 }, { n: 12, title: "Coda", dur: 367 }
      ],
      notes: [
        { track: 1, at: 12, date: "2026-10-01", text: "前奏的吉他像在水底。" },
        { track: 2, at: 64, date: "2026-09-30", text: "副歌那一句一直在脑子里转，走路的时候会不自觉地跟着节奏。" },
        { track: 5, at: 140, date: "2026-09-29", text: "Bridge 太好了" },
        { track: null, at: null, date: "2026-09-28", text: "整张专辑适合下雨天一个人在家听。" }   // album note: no track, no anchor
      ]
    },
    {
      id: "m3",
      album: "The Patterns Lost to the Tide (Expanded Edition)",
      artist: "Marielle V Jakobsons",
      year: 2021,
      palette: { base: "#1f4f8f", accent: "#e5a07c", deep: "#0b2448", soft: "#d3e2f6" },
      cover: { from: "#163f7a", to: "#4a76e8", dot: "#e5a07c" },
      scoreKind: "average",        // track average (outlined seal)
      score: 7.8,
      ratedTracks: 6, totalTracks: 8,
      aiTitle: null,               // no Gemini: the title slot falls back to a Yoin-derived line
      aiTitleIsGenerated: false,
      fallbackTitle: "Listened closely",
      review: null,
      narrative: "You came back to this one across three seasons, rating it track by track.",
      firstPlayed: "2026-01-05", lastPlayed: "2026-10-01", plays: 22,
      neoDb: "needs-review",
      tracks: [
        { n: 1, title: "Tidal", dur: 312, rating: 8.0 }, { n: 2, title: "Lost Pattern", dur: 268, rating: 7.0 },
        { n: 3, title: "Undertow", dur: 405, rating: 9.0 }, { n: 4, title: "Salt Light", dur: 220, rating: 6.5 },
        { n: 5, title: "Littoral", dur: 351, rating: 8.5 }, { n: 6, title: "Fathom", dur: 290, rating: 7.5 },
        { n: 7, title: "Brine (Expanded)", dur: 377, rating: null }, { n: 8, title: "Tideline (Live)", dur: 444, rating: null }
      ],
      notes: [
        { track: 3, at: 201, date: "2026-05-11", text: "合成器在这里像退潮。" }
      ]
    },
    {
      id: "m4",
      album: "MeMe",
      artist: "衛柏Neon",
      year: 2025,
      palette: { base: "#8f1d3a", accent: "#7fe0c4", deep: "#4a0b1c", soft: "#f5d4dc" },
      cover: { from: "#7a1030", to: "#e08a5a", dot: "#7fe0c4" },
      scoreKind: "album", score: 10.0,
      ratedTracks: 0, totalTracks: 6,
      aiTitle: "一句好听就够了", aiTitleIsGenerated: true,
      review: "好听。", reviewWrittenAt: "2026-09-29",
      narrative: null,
      firstPlayed: "2026-09-27", lastPlayed: "2026-09-29", plays: 5, neoDb: "ready",
      tracks: [ { n: 1, title: "MeMe", dur: 201 }, { n: 2, title: "Afterparty", dur: 188 }, { n: 3, title: "霓虹", dur: 233 }, { n: 4, title: "Static Love", dur: 210 }, { n: 5, title: "Kiss Me Neon", dur: 199 }, { n: 6, title: "Outro", dur: 120 } ],
      notes: []
    }
  ]
};

/* ------- helpers shared by fragments (all optional) ------- */
window.YS = {
  /** MaterialShapes.Cookie12Sided approximation as an SVG path centred at (cx,cy), outer radius r.
      lobes=12, depth = inner/outer radius ratio (Material: 0.8). */
  cookiePath(cx, cy, r, lobes = 12, depth = 0.88, steps = 480) {
    // Rounded bumps with pinched valleys, like MaterialShapes.Cookie12Sided:
    // r(t) = R * (1 - k * (1 - |cos(lobes/2 * t)|)^1.25), k = 1 - depth (inner/outer radius ratio).
    let d = "";
    const k = 1 - depth;
    for (let i = 0; i <= steps; i++) {
      const t = (i / steps) * Math.PI * 2;
      const v = 1 - Math.abs(Math.cos((lobes / 2) * t));
      const rr = r * (1 - k * Math.pow(v, 1.25));
      const x = cx + rr * Math.cos(t - Math.PI / 2), y = cy + rr * Math.sin(t - Math.PI / 2);
      d += (i ? "L" : "M") + x.toFixed(2) + " " + y.toFixed(2);
    }
    return d + "Z";
  },
  /** Damped spring sampler -> array of values 0..1 (overshoot allowed), for WAAPI keyframes.
      dampingRatio/stiffness follow Compose spring() semantics (stiffness in 1/s^2 units, mass 1). */
  springKeyframes(dampingRatio = 0.6, stiffness = 800, frames = 60, duration = 0.6) {
    const w0 = Math.sqrt(stiffness), z = dampingRatio, out = [];
    for (let i = 0; i <= frames; i++) {
      const t = (i / frames) * duration;
      let x;
      if (z < 1) { const wd = w0 * Math.sqrt(1 - z * z); x = 1 - Math.exp(-z * w0 * t) * (Math.cos(wd * t) + (z * w0 / wd) * Math.sin(wd * t)); }
      else { x = 1 - Math.exp(-w0 * t) * (1 + w0 * t); }
      out.push(x);
    }
    out[out.length - 1] = 1;
    return out;
  },
  /** Album cover placeholder as an SVG data string (bare art, NO border — app rule). */
  coverSvg(m, size = 200) {
    const c = m.cover;
    return `<svg viewBox="0 0 100 100" width="${size}" height="${size}" xmlns="http://www.w3.org/2000/svg" aria-label="${m.album} cover"><defs><linearGradient id="g${m.id}" x1="0" y1="0" x2="0" y2="1"><stop offset="0" stop-color="${c.from}"/><stop offset="1" stop-color="${c.to}"/></linearGradient></defs><rect width="100" height="100" fill="url(#g${m.id})"/><circle cx="50" cy="50" r="26" fill="${c.dot}"/></svg>`;
  },
  fmtTime(sec) { const m = Math.floor(sec / 60), s = Math.round(sec % 60); return `${m}:${String(s).padStart(2, "0")}`; },
  reducedMotion() { return matchMedia("(prefers-reduced-motion: reduce)").matches; }
};
