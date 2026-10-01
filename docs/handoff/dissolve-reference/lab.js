// Yoin 溶解实验台的渲染代码（Canvas 2D 原型，2026-10-01 第二版：加入静整动乱）。只作参考：数学和绘制顺序以它为准，结构不要照搬。
// 定稿组合：curve C · bottom P3 · tailStart 20 · density 40 · fadeTail on · status T3 · title fade · libTide off · order settle（回退：order always）。
(() => {
  'use strict';

  // ───────────────────────── math ─────────────────────────
  const TAU = Math.PI * 2;
  const clamp = (v, a, b) => (v < a ? a : v > b ? b : v);
  const smooth = (x) => x * x * (3 - 2 * x);
  const mix = (a, b, t) => a + (b - a) * t;
  const hash = (x, y) => { const s = Math.sin(x * 127.1 + y * 311.7) * 43758.547; return s - Math.floor(s); };
  const EASE = {
    io: (t) => (t < 0.5 ? 4 * t * t * t : 1 - Math.pow(-2 * t + 2, 3) / 2),
    out: (t) => 1 - Math.pow(1 - t, 3),
    lin: (t) => t,
  };
  const hexRgb = (h) => { const n = parseInt(h.slice(1), 16); return [(n >> 16) & 255, (n >> 8) & 255, n & 255]; };
  const rgbHex = (c) => '#' + c.map((v) => Math.round(clamp(v, 0, 255)).toString(16).padStart(2, '0')).join('');
  const mixHex = (a, b, t) => { const A = hexRgb(a), B = hexRgb(b); return rgbHex([mix(A[0], B[0], t), mix(A[1], B[1], t), mix(A[2], B[2], t)]); };
  const rgba = (h, a) => { const [r, g, b] = hexRgb(h); return `rgba(${r},${g},${b},${a})`; };
  // CIELAB L* (0–100) of an sRGB colour: the tone axis M3's palettes are built on.
  const lin8 = (c) => { c /= 255; return c <= 0.04045 ? c / 12.92 : Math.pow((c + 0.055) / 1.055, 2.4); };
  const lstar = (r, g, b) => {
    const Y = 0.2126 * lin8(r) + 0.7152 * lin8(g) + 0.0722 * lin8(b);
    return Y > 0.008856 ? 116 * Math.cbrt(Y) - 16 : 903.3 * Y;
  };
  const lstarHex = (h) => { const [r, g, b] = hexRgb(h); return lstar(r, g, b); };

  // ───────────────────── screen geometry (dp) ─────────────────────
  // A 412 × 916dp phone: the width Library's 118dp grid covers come from.
  const W = 412, H = 916, SB = 48, NAV = 24;
  const PITCH = 6;              // same screen frequency as the cover-swap ArtworkHalftone
  const CULL = 1 / 2.75;        // a dot under one device pixel is dropped, as on the phone
  const RC = 0.64;              // new curve: whole-print radius, just over the lattice's covering radius
  const COVER_R = 0.5774;       // covering radius of the staggered lattice, in cells
  const REVEAL = 40;            // SeamDissolveTokens.RevealDistance
  const OMEGA = Math.sqrt(90);  // YoinMotion.SeamFlowSettleStiffness
  const BAR = { x: 16, y: H - NAV - 12 - 68, w: W - 32, h: 68, r: 28 };
  BAR.cy = BAR.y + BAR.h / 2;
  const LIB = { seam: 168 };    // chips bottom (164) + the 4dp gap = the grid viewport's top edge

  // ───────────────────── curves ─────────────────────
  const TUNINGS = {
    v1: { curve: 'orig', band: 36 },
    A: { curve: 'orig', band: 20 },
    B: { curve: 'new', G: 16, F: 4, gamma: 0.8, T: 12 },
    C: { curve: 'new', G: 12, F: 3, gamma: 0.8, T: 10, Gm: 34, Fm: 8, Tm: 26 },
  };
  function tuningAt(key, stretch) {
    const t = TUNINGS[key];
    if (t.curve === 'orig' || !t.Gm) return t;
    return { curve: 'new', G: mix(t.G, t.Gm, stretch), F: mix(t.F, t.Fm, stretch), gamma: t.gamma, T: mix(t.T, t.Tm, stretch) };
  }
  const COVER_CELL = 118.667 / Math.round(118.667 / PITCH);
  const textAlpha = (x) => (x <= 0.05 ? 0 : 1 - Math.pow(1 - (x - 0.05) / 0.95, 2));
  function metrics(t) {
    const cell = COVER_CELL;
    if (t.curve === 'orig') {
      const b = t.band, full = Math.hypot(cell, cell * 0.866) * 0.53;
      const pc = 0.1 + 0.9 * Math.pow((COVER_R * cell) / full, 1 / 1.1);
      const pv = 0.1 + 0.9 * Math.pow(CULL / full, 1 / 1.1);
      return { holes: pc * b, wobble: 0.22 * b, vanish: pv * b, solid: 1.34 * b, text: 0.8 * b, half: 0.481 * b };
    }
    const full = RC * cell;
    return {
      holes: t.G * Math.pow((COVER_R * cell) / full, 1 / t.gamma), wobble: t.F,
      vanish: t.G * Math.pow(CULL / full, 1 / t.gamma), solid: t.G + t.F,
      text: t.T || 0, half: (t.T || 0) * (0.05 + 0.95 * 0.2929),
    };
  }

  // ───────────────────── seams ─────────────────────
  function flatSeam(type, y, t, reveal) {
    if (t.curve === 'orig') { const band = t.band * reveal; return { type, y, curve: 'orig', band, solid: band * 1.34, soft: band >= 0.05 }; }
    const G = t.G * reveal, F = t.F * reveal;
    return { type, y, curve: 'new', G, F, gamma: t.gamma, solid: G + F, soft: G >= 0.05 };
  }
  // Bottom: content does not stop at the bar. From `tailStart` above it, content
  // opens up into a halftone (the approach) and runs on under the bar to the
  // screen's bottom edge as a calm field of dots. Most of the approach happens
  // behind the opaque bar (HIDE), where it costs no screen: above the bar only
  // its first few dp show, below the bar the field is already pure dots.
  const covToK = (c) => Math.sqrt((c * 0.866) / Math.PI);   // coverage of the staggered lattice -> radius in cells
  // How each bottom option keeps the bar apart from the field: a fixed ring
  // (moat) with dots shrinking outside it (taper), or yielding by lightness.
  const ISO = {
    P0: { moat: 0, taper: 0, adapt: false },
    P1: { moat: 8, taper: 10, adapt: false },   // the ring from the last round
    P2: { moat: 2, taper: 0, adapt: false },    // a 2dp keyline, drawn as a mat so the cut is crisp
    P3: { moat: 0, taper: 0, adapt: true },     // dots give way only when their lightness is close to the bar's
  };
  const YIELD = 7;                              // P3: similar dots shrink over this distance from the bar's edge
  const HIDE = 28;                              // this much of the approach sits behind the bar's top edge
  function tailSeam(cfg, rb) {
    const iso = ISO[cfg.bottom] || ISO.P0, moat = iso.moat, P = PAL[cfg.theme];
    const yb = BAR.y + HIDE;                                   // the field is reached behind the bar
    const len = (cfg.tailStart + HIDE + 20 * cfg.stretch) * rb;
    const k0 = covToK(cfg.density / 100);
    return {
      type: 'tail', soft: true, solid: 0, y: yb - len, yb, len, end: H, k0, k1: k0 * 0.9, rb,
      moat, taper: iso.taper, adapt: iso.adapt, fade: cfg.fadeTail,
      guard: BAR.y - Math.max(moat + iso.taper, iso.adapt ? YIELD + 1.5 : 0) - 1,
      lBar: lstarHex(P.bar), lBg: lstarHex(bgAt(cfg, P, BAR.y)),
      cx: W / 2, cy: BAR.cy, hw: BAR.w / 2, hh: BAR.h / 2, r: BAR.r,
    };
  }
  // The 退色 overlay's strength at a height (same stops as fadeTail).
  function fadeAt(s, Y) {
    if (Y <= s.y) return 0;
    if (Y < BAR.y) return 0.16 * (Y - s.y) / Math.max(1, BAR.y - s.y);
    return 0.16 + 0.16 * clamp((Y - BAR.y) / (H + 20 - BAR.y), 0, 1);
  }
  // Signed distance to the bar's rounded rectangle (negative inside).
  function sdBar(s, X, Y) {
    const qx = Math.abs(X - s.cx) - (s.hw - s.r), qy = Math.abs(Y - s.cy) - (s.hh - s.r);
    return Math.hypot(Math.max(qx, 0), Math.max(qy, 0)) + Math.min(Math.max(qx, qy), 0) - s.r;
  }
  function textSeam(type, y, t, reveal) {
    return { type, y, curve: t.curve, reveal, len: (t.curve === 'orig' ? t.band * 0.8 : t.T) * reveal };
  }
  // New curves: a line fades over at least 0.75 × its own size, so a 32sp
  // title fades instead of looking sliced, while body text keeps the short band.
  const fadeLen = (s, fs) => (s.curve === 'orig' ? s.len : Math.max(s.len, 0.75 * fs * s.reveal));
  const seamDist = (s, X, Y) => (s.type === 'top' ? Y - s.y : s.y - Y);
  const R_SOLID = 0, R_MIXED = 1, R_GONE = 2;
  function rowClass(s, Y, rh) {
    if (s.type === 'top' || s.type === 'bottom') {
      const d = s.type === 'top' ? Y - s.y : s.y - Y;
      return d >= s.solid + rh ? R_SOLID : d < -rh ? R_GONE : R_MIXED;
    }
    // Tail: every row below its start is dots. Rows next to the bar are always evaluated too, so the ring
    // (P1/P2) and the lightness yield (P3) also reach content that has not started to break up yet.
    return Y < Math.min(s.y, s.guard) - rh - 4 ? R_SOLID : R_MIXED;
  }

  const bendAt = (u, seed) => 0.10 * Math.sin((u * 0.9 + seed * 0.37) * TAU) + 0.05 * Math.sin((u * 2.3 - seed * 0.21 + 0.3) * TAU);
  const jitterAt = (col, row, seed) => (hash(col + seed * 31, row + seed * 7) - 0.5) * 0.14;

  const lattices = new Map();
  function latticeFor(w) {
    let L = lattices.get(w);
    if (!L) {
      const cols = Math.max(4, Math.round(w / PITCH)), cw = w / cols, rh = cw * 0.866;
      L = { cols, cw, rh, full: Math.hypot(cw, rh) * 0.53 };
      lattices.set(w, L);
    }
    return L;
  }

  // One dot against one seam. Returns true when this seam leaves the dot whole;
  // otherwise writes radius and swirl offset into `dot`. The 'orig' branch is
  // SeamHalftone.dot() line for line; 'new' is the late-onset curve.
  const dot = { r: 0, dx: 0, dy: 0, lum: NaN };
  function evalDot(s, d, bj, u, v, lag, L) {
    if (s.curve === 'orig') {
      const band = s.band;
      if (d >= band * 1.34) return true;
      const near = smooth(clamp(d / (band * 0.35), 0, 1));
      const p = d / band + bj * near;
      const local = clamp((p - 0.1) / 0.9, 0, 1);
      if (local <= 0) { dot.r = 0; return false; }
      const env = Math.sin(local * Math.PI), flow = p + lag / band;
      dot.r = L.full * Math.pow(local, 1.1);
      dot.dx = L.cw * 0.42 * Math.sin((v * 1.4 + u * 0.45 - flow * 0.8) * TAU) * env;
      dot.dy = L.rh * 0.38 * Math.sin((v * 0.7 - u * 1.3 + flow * 0.65) * TAU) * env;
      return false;
    }
    const G = s.G;
    if (d >= s.solid) return true;
    // Disorder (0 at rest, toward 1 while scrolling) scales everything that is not a function of
    // position alone: the front's bend and per-dot jitter, and the swirl. At rest a dot sits on its
    // lattice site with a size set by its distance only.
    const D = s.dis;
    const near = smooth(clamp(d / (G * 0.35), 0, 1));
    const dd = d + (bj / 0.22) * s.F * near * D;
    const t = clamp(dd / G, 0, 1);
    if (t <= 0) { dot.r = 0; return false; }
    const env = Math.sin(t * Math.PI) * Math.sqrt(1 - t) * D, flow = dd / G + lag / G;
    dot.r = RC * L.cw * Math.pow(t, s.gamma);
    dot.dx = L.cw * 0.42 * Math.sin((v * 1.4 + u * 0.45 - flow * 0.8) * TAU) * env;
    dot.dy = L.rh * 0.38 * Math.sin((v * 0.7 - u * 1.3 + flow * 0.65) * TAU) * env;
    return false;
  }

  // One dot against the tail. Above the ring's top edge the approach opens the
  // content from solid to the field size, with the same swirl as a seam; below
  // it the field is a still lattice that thins a little toward the bottom edge.
  // Nothing is drawn inside the ring; P1 also shrinks dots over 10dp outside it.
  function evalTail(s, X, Y, bj, u, v, lag, L) {
    const d0 = Y - s.y;
    const whole = d0 <= 0;                 // not broken up yet: a full dot, unless the bar asks it to give way
    if (whole && Y < s.guard) return true;
    let k = 0, a = 1, env = 0;
    if (whole) a = 0;
    else if (Y < s.yb && s.len > 0.5) {
      // Same disorder as a seam: the ±3dp front and the swirl only exist while scrolling, so at rest
      // the approach is the same regular lattice as the field under the bar.
      a = clamp((d0 + (bj / 0.22) * 3 * smooth(clamp(d0 / 10, 0, 1)) * s.dis) / s.len, 0, 1);
      k = mix(0.62, s.k0, smooth(a));
      env = Math.sin(a * Math.PI) * s.dis;
    } else k = mix(s.k0, s.k1, clamp((Y - s.yb) / (s.end - s.yb), 0, 1));
    let r = whole ? L.full : k * L.cw;
    const r0 = r;
    const db = sdBar(s, X, Y);
    if (db <= s.moat) {
      // Inside the ring nothing is drawn; with no ring, dots wholly under the (opaque) bar are skipped too.
      if (s.moat > 0 || db < -r - 0.5) { dot.r = 0; return false; }
    } else if (s.taper > 0) r *= smooth(clamp((db - s.moat) / s.taper, 0, 1));
    if (s.adapt && db < YIELD + 1.5) {
      // P3: only a dot whose lightness (after 退色) is within reach of the bar's gives way, and only
      // right next to it; contrasting dots run up to the edge. ΔL* ≤ 6 yields fully, ≥ 18 not at all.
      const l = dot.lum;
      if (l === l) {
        const le = s.fade ? mix(l, s.lBg, fadeAt(s, Y)) : l;
        const need = 1 - smooth(clamp((Math.abs(le - s.lBar) - 6) / 12, 0, 1));
        if (need > 0) r *= mix(1, smooth(clamp((db - 1.5) / YIELD, 0, 1)), need);
      }
    }
    if (whole && r === r0) return true;
    const flow = a * 2 + lag / 24;
    dot.r = r;
    dot.dx = L.cw * 0.42 * Math.sin((v * 1.4 + u * 0.45 - flow * 0.8) * TAU) * env;
    dot.dy = L.rh * 0.38 * Math.sin((v * 0.7 - u * 1.3 + flow * 0.65) * TAU) * env;
    return false;
  }

  const lumCache = new Map();
  function lumGrid(it) {
    const bm = bitmapFor(it, curK), key = it.id + '@' + bm.kq;
    let g = lumCache.get(key);
    if (g) return g;
    const L = latticeFor(it.w), rows = Math.ceil(it.h / L.rh), W2 = L.cols + 2;
    g = { W2, data: new Float32Array(W2 * (rows + 2)).fill(NaN) };
    lumCache.set(key, g);
    let d, cw, ch;
    try {
      const im = bm.cv.getContext('2d').getImageData(0, 0, bm.cv.width, bm.cv.height);
      d = im.data; cw = im.width; ch = im.height;
    } catch (e) { return g; }
    for (let row = -1; row <= rows; row++) {
      const ly = (row + 0.5) * L.rh, stagger = row & 1 ? 0.5 : 0;
      for (let col = -1; col <= L.cols; col++) {
        const lx = (col + 0.5 + stagger) * L.cw;
        const px = Math.round((lx + bm.pad) * bm.kq), py = Math.round((ly + bm.pad) * bm.kq);
        let sr = 0, sg = 0, sb = 0, n = 0;
        for (let yy = py - 1; yy <= py + 1; yy++) for (let xx = px - 1; xx <= px + 1; xx++) {
          if (xx < 0 || yy < 0 || xx >= cw || yy >= ch) continue;
          const o = (yy * cw + xx) * 4;
          if (d[o + 3] < 128) continue;
          sr += d[o]; sg += d[o + 1]; sb += d[o + 2]; n++;
        }
        if (n) g.data[(row + 1) * W2 + (col + 1)] = lstar(sr / n, sg / n, sb / n);
      }
    }
    return g;
  }

  function clipHard(ctx, s) {
    ctx.beginPath();
    if (s.type === 'top') ctx.rect(-20, s.y, W + 40, 4000);
    else ctx.rect(-20, -4000, W + 40, s.y + 4000);
    ctx.clip();
  }

  // A graphic item: solid rows merge into rects, rows inside a band become
  // dots; the union clips the item's own drawing, so every dot is the
  // artwork's own pixels. Mirrors the Path fallback in SeamDissolve.kt.
  function drawGraphic(ctx, it, sx, sy, seams, lag) {
    const w = it.w, h = it.h;
    let near = false;
    for (let i = 0; i < seams.length; i++) {
      const s = seams[i];
      let dmin, dmax;
      if (s.type === 'top') { dmin = sy - s.y; dmax = sy + h - s.y; }
      else if (s.type === 'bottom') { dmin = s.y - (sy + h); dmax = s.y - sy; }
      else { dmin = s.y - (sy + h); dmax = Infinity; }   // tail
      if (dmax < -8) return;
      if (dmin < (s.soft ? s.solid : 0) + 8) near = true;
    }
    if (!near) { blit(ctx, it, sx, sy); return; }
    const soft = [];
    ctx.save();
    for (const s of seams) { if (s.soft) soft.push(s); else clipHard(ctx, s); }
    if (!soft.length) { blit(ctx, it, sx, sy); ctx.restore(); return; }
    const L = latticeFor(w);
    const rows = Math.ceil(h / L.rh);
    let lg = null;
    for (const s of soft) if (s.adapt) { lg = lumGrid(it); break; }
    ctx.beginPath();
    let run = null;
    const closeRun = (end) => {
      if (run === null) return;
      ctx.rect(sx - 1, sy + run * L.rh - 0.3, w + 2, (end - run) * L.rh + 0.6);
      run = null;
    };
    for (let row = -1; row <= rows; row++) {
      const ly = (row + 0.5) * L.rh, Y = sy + ly;
      let cls = R_SOLID;
      for (let i = 0; i < soft.length; i++) { const c = rowClass(soft[i], Y, L.rh); if (c > cls) cls = c; if (cls === R_GONE) break; }
      if (cls === R_SOLID) { if (run === null) run = row; continue; }
      closeRun(row);
      if (cls === R_GONE) continue;
      const stagger = row & 1 ? 0.5 : 0;
      const v = ly / h;
      for (let col = -1; col <= L.cols; col++) {
        const lx = (col + 0.5 + stagger) * L.cw, X = sx + lx, u = lx / w;
        const bj = bendAt(u, it.seed) + jitterAt(col, row, it.seed);
        dot.lum = lg ? lg.data[(row + 1) * lg.W2 + (col + 1)] : NaN;
        let r = L.full, dx = 0, dy = 0, whole = true;
        for (let i = 0; i < soft.length; i++) {
          const s = soft[i];
          if (s.type === 'tail' ? evalTail(s, X, Y, bj, u, v, lag, L) : evalDot(s, seamDist(s, X, Y), bj, u, v, lag, L)) continue;
          if (whole || dot.r < r) { r = dot.r; dx = dot.dx; dy = dot.dy; whole = false; }
        }
        if (r < CULL) continue;
        const cx = X + dx, cy = Y + dy;
        ctx.moveTo(cx + r, cy);
        ctx.arc(cx, cy, r, 0, TAU);
      }
    }
    closeRun(rows + 1);
    fillWithItem(ctx, it, sx, sy);
    ctx.restore();
  }

  // T1 line screen: inside the status bar the item is cut into horizontal
  // lines anchored to the item; each line thins toward the top edge.
  function drawLinesItem(ctx, it, sx, sy, s) {
    const w = it.w, h = it.h, p = s.pitch;
    ctx.save();
    ctx.beginPath();
    const solidTop = Math.max(sy, s.y1);
    if (sy + h > solidTop) ctx.rect(sx - 1, solidTop - 0.3, w + 2, sy + h - solidTop + 1.3);
    const k1 = Math.min(Math.ceil(h / p), Math.ceil((s.y1 - sy) / p));
    for (let kk = Math.max(0, Math.floor((s.y0 - 2 - sy) / p)); kk <= k1; kk++) {
      const yc = sy + (kk + 0.5) * p;
      if (yc - p / 2 >= s.y1) break;
      const t = clamp((yc - s.y0) / (s.y1 - s.y0), 0, 1);
      const th = p * Math.pow(1 - (1 - t) * s.reveal, 0.9);
      if (th < CULL) continue;
      ctx.rect(sx - 1, yc - th / 2, w + 2, th);
    }
    fillWithItem(ctx, it, sx, sy);
    ctx.restore();
  }

  // Text: one DstIn gradient per seam over a text-only layer, as SeamFadeNode.
  // The strip each text seam may touch; text outside every strip skips the layer.
  function textZone(s) {
    const len = fadeLen(s, 32);
    return s.type === 'top' ? [s.y - 70, s.y + len + 2] : [s.y - len - 2, s.y + 220];
  }
  function maskLine(tctx, s, fs, a, b) {
    tctx.globalCompositeOperation = 'destination-in';
    if (s.len < 0.05) {
      tctx.fillStyle = '#000';
      if (s.type === 'top') tctx.fillRect(-20, Math.max(a, s.y), W + 40, Math.max(0, b - Math.max(a, s.y)));
      else tctx.fillRect(-20, a, W + 40, Math.max(0, Math.min(b, s.y) - a));
    } else {
      const len = fadeLen(s, fs);
      const g = tctx.createLinearGradient(0, s.y, 0, s.type === 'top' ? s.y + len : s.y - len);
      if (s.curve === 'orig') {
        g.addColorStop(0, 'rgba(0,0,0,0)'); g.addColorStop(0.25, 'rgba(0,0,0,0.06)'); g.addColorStop(1, 'rgba(0,0,0,1)');
      } else {
        for (const x of [0, 0.05, 0.12, 0.2, 0.3, 0.42, 0.56, 0.72, 0.86, 1]) g.addColorStop(x, `rgba(0,0,0,${textAlpha(x).toFixed(3)})`);
      }
      tctx.fillStyle = g;
      tctx.fillRect(-20, a, W + 40, b - a);
    }
    tctx.globalCompositeOperation = 'source-over';
  }

  // ───────────────────── palettes ─────────────────────
  const PAL = {
    light: {
      bg: '#fff9ee', bgTop: '#f3edda', bgLow: '#f9f3e4',
      scHigh: '#ede7d7', scHighest: '#e6e0cf',
      on: '#1e1c12', onVar: '#4b4739',
      primary: '#675e10', primaryC: '#f2e285', onPrimaryC: '#201c00',
      secC: '#e9e0b6', onSecC: '#211e0c',
      bar: '#f2e9b8', pill: '#f6e58c', wash: '#ebd45c', btn: '#e7deac',
      protect: '#ebe1bd', handle: '#1e1c12', shadow: 'rgba(70,58,10,0.26)',
      mat: '#ffffff',
      cardMix: 0.5, lobeMix: 0.12, ruleHalo: 'rgba(255,249,238,0.9)',
    },
    dark: {
      bg: '#121318', bgTop: '#1e1f26', bgLow: '#1a1b21',
      scHigh: '#282a30', scHighest: '#33353b',
      on: '#e3e2e9', onVar: '#c5c6d0',
      primary: '#b1c5ff', primaryC: '#2f4578', onPrimaryC: '#dae2ff',
      secC: '#3e4759', onSecC: '#dae2f9',
      bar: '#22242b', pill: '#2c3753', wash: '#3a4b78', btn: '#30333b',
      protect: '#262b39', handle: '#e3e2e9', shadow: 'rgba(0,0,0,0.55)',
      mat: '#08090c',
      cardMix: 0.72, lobeMix: 0.38, ruleHalo: 'rgba(18,19,24,0.9)',
    },
  };

  // ───────────────────── cover art (generated, no real albums) ─────────────────────
  const COVER_PALS = [
    ['#20324f', '#f3c14b', '#ef7d57', '#fbe9c9'], ['#2f3e46', '#84a98c', '#cad2c5', '#e9c46a'],
    ['#3d405b', '#e07a5f', '#f2cc8f', '#81b29a'], ['#0b2545', '#3e6d9c', '#8da9c4', '#eef4ed'],
    ['#6b2737', '#e08e45', '#f8f4a6', '#9ad1a8'], ['#264653', '#2a9d8f', '#e9c46a', '#f4a261'],
    ['#582f0e', '#936639', '#c2c5aa', '#a4ac86'], ['#240046', '#7b2cbf', '#e0aaff', '#ffd6a5'],
    ['#1b4332', '#52b788', '#d8f3dc', '#ffb703'], ['#9d0208', '#dc2f02', '#faa307', '#ffe8d6'],
    ['#355070', '#6d597a', '#b56576', '#eaac8b'], ['#003049', '#669bbc', '#fdf0d5', '#c1121f'],
    ['#344e41', '#a3b18a', '#dad7cd', '#e76f51'], ['#22223b', '#4a4e69', '#c9ada7', '#f2e9e4'],
  ];
  const STYLES = ['sun', 'rings', 'waves', 'arch', 'venn', 'hills', 'petals', 'split', 'bars', 'blob', 'orbit', 'bauhaus'];
  const COVERS = Array.from({ length: 72 }, (_, i) => ({
    style: STYLES[(i * 5 + 3) % STYLES.length],
    pal: (i * 7 + 2) % COVER_PALS.length,
    q: [hash(i, 1.3), hash(i, 2.7), hash(i, 4.1)],
  }));

  function lin(c, x0, y0, x1, y1, stops) { const g = c.createLinearGradient(x0, y0, x1, y1); for (const [o, col] of stops) g.addColorStop(o, col); c.fillStyle = g; }
  const ART = {
    sun(c, a, b, cc, d, q) {
      lin(c, 0, 0, 0, 1, [[0, a], [0.72, mixHex(a, cc, 0.7)], [1, cc]]); c.fillRect(0, 0, 1, 1);
      const x = 0.3 + q[0] * 0.4;
      c.fillStyle = b; c.beginPath(); c.arc(x, 0.46, 0.19, 0, TAU); c.fill();
      c.fillStyle = d; c.fillRect(0, 0.7, 1, 0.3);
      c.fillStyle = b; for (let i = 0; i < 5; i++) { const w = 0.34 - i * 0.055; c.fillRect(x - w / 2, 0.74 + i * 0.045, w, 0.017); }
    },
    rings(c, a, b, cc, d, q) {
      c.fillStyle = a; c.fillRect(0, 0, 1, 1);
      const cols = [b, cc, d, b, a], cx = 0.5 + (q[0] - 0.5) * 0.24, cy = 0.5 + (q[1] - 0.5) * 0.24;
      for (let i = 0; i < 5; i++) { c.fillStyle = cols[i]; c.beginPath(); c.arc(cx, cy, 0.46 - i * 0.085, 0, TAU); c.fill(); }
    },
    waves(c, a, b, cc, d, q) {
      c.fillStyle = a; c.fillRect(0, 0, 1, 1);
      const cols = [b, cc, d, cc, b];
      for (let i = 0; i < 5; i++) {
        c.fillStyle = cols[i]; c.beginPath(); const base = 0.16 + i * 0.17;
        c.moveTo(0, 1.02); c.lineTo(0, base);
        for (let x = 0; x <= 1.001; x += 0.05) c.lineTo(x, base + 0.05 * Math.sin((x * 1.6 + q[0] + i * 0.23) * TAU));
        c.lineTo(1, 1.02); c.closePath(); c.fill();
      }
    },
    arch(c, a, b, cc, d, q) {
      lin(c, 0, 0, 1, 1, [[0, a], [1, b]]); c.fillRect(0, 0, 1, 1);
      c.fillStyle = cc; c.beginPath(); c.moveTo(0.3, 1.02); c.lineTo(0.3, 0.42); c.arc(0.5, 0.42, 0.2, Math.PI, 0); c.lineTo(0.7, 1.02); c.closePath(); c.fill();
      c.fillStyle = rgba(d, 0.9); c.beginPath(); c.arc(0.56 + q[0] * 0.12, 0.62, 0.17, 0, TAU); c.fill();
    },
    venn(c, a, b, cc, d) {
      c.fillStyle = d; c.fillRect(0, 0, 1, 1);
      c.globalCompositeOperation = 'multiply';
      for (const [x, y, col] of [[0.38, 0.4, a], [0.62, 0.4, b], [0.5, 0.62, cc]]) { c.fillStyle = rgba(col, 0.86); c.beginPath(); c.arc(x, y, 0.25, 0, TAU); c.fill(); }
      c.globalCompositeOperation = 'source-over';
    },
    hills(c, a, b, cc, d, q) {
      lin(c, 0, 0, 0, 1, [[0, d], [0.75, cc]]); c.fillRect(0, 0, 1, 1);
      c.fillStyle = b; c.beginPath(); c.arc(0.68, 0.27, 0.09, 0, TAU); c.fill();
      [[0.56, mixHex(a, cc, 0.55)], [0.7, mixHex(a, cc, 0.28)], [0.84, a]].forEach(([base, col], i) => {
        c.fillStyle = col; c.beginPath(); c.moveTo(0, 1.02);
        for (let x = 0; x <= 1.001; x += 0.04) c.lineTo(x, base - 0.13 * Math.abs(Math.sin((x * (1.1 + i * 0.45) + q[i]) * Math.PI)));
        c.lineTo(1, 1.02); c.closePath(); c.fill();
      });
    },
    petals(c, a, b, cc, d, q) {
      c.fillStyle = a; c.fillRect(0, 0, 1, 1);
      c.save(); c.translate(0.5, 0.5); c.rotate(q[0] * TAU);
      c.fillStyle = b; for (let i = 0; i < 8; i++) { c.rotate(TAU / 8); c.beginPath(); c.ellipse(0.21, 0, 0.17, 0.075, 0, 0, TAU); c.fill(); }
      c.restore();
      c.fillStyle = cc; c.beginPath(); c.arc(0.5, 0.5, 0.11, 0, TAU); c.fill();
      c.fillStyle = d; c.beginPath(); c.arc(0.5, 0.5, 0.045, 0, TAU); c.fill();
    },
    split(c, a, b, cc, d, q) {
      c.fillStyle = a; c.fillRect(0, 0, 1, 1);
      c.fillStyle = b; c.beginPath(); c.moveTo(0, 1); c.lineTo(1, 0); c.lineTo(1, 1); c.closePath(); c.fill();
      c.fillStyle = cc; c.beginPath(); c.arc(0.5, 0.5, 0.27, 0, TAU); c.fill();
      c.fillStyle = d; c.fillRect(0.12 + q[0] * 0.1, 0.12, 0.16, 0.16);
    },
    bars(c, a, b, cc, d, q) {
      lin(c, 0, 0, 0, 1, [[0, a], [1, mixHex(a, '#000000', 0.3)]]); c.fillRect(0, 0, 1, 1);
      const cols = [b, cc, d];
      for (let i = 0; i < 9; i++) { const hh = 0.18 + 0.62 * hash(i, q[0] * 10); c.fillStyle = cols[i % 3]; c.fillRect(0.08 + i * 0.095, 0.9 - hh, 0.06, hh); }
    },
    blob(c, a, b, cc, d) {
      const g = c.createRadialGradient(0.3, 0.3, 0, 0.3, 0.3, 0.95); g.addColorStop(0, b); g.addColorStop(1, a); c.fillStyle = g; c.fillRect(0, 0, 1, 1);
      c.fillStyle = cc; c.beginPath(); c.moveTo(0.5, 0.18);
      c.bezierCurveTo(0.85, 0.15, 0.9, 0.55, 0.7, 0.78); c.bezierCurveTo(0.5, 0.98, 0.15, 0.85, 0.18, 0.55); c.bezierCurveTo(0.2, 0.3, 0.3, 0.2, 0.5, 0.18); c.fill();
      c.fillStyle = rgba(d, 0.85); c.beginPath(); c.ellipse(0.42, 0.4, 0.1, 0.06, -0.6, 0, TAU); c.fill();
    },
    orbit(c, a, b, cc, d, q) {
      c.fillStyle = a; c.fillRect(0, 0, 1, 1);
      c.strokeStyle = rgba(cc, 0.85); c.lineWidth = 0.014;
      for (let i = 0; i < 4; i++) { c.beginPath(); c.ellipse(0.5, 0.52, 0.14 + i * 0.09, 0.06 + i * 0.04, -0.35, 0, TAU); c.stroke(); }
      c.fillStyle = b; c.beginPath(); c.arc(0.5, 0.52, 0.1, 0, TAU); c.fill();
      c.fillStyle = d; c.beginPath(); c.arc(0.5 + 0.3 * Math.cos(q[0] * TAU), 0.52 + 0.14 * Math.sin(q[0] * TAU), 0.036, 0, TAU); c.fill();
    },
    bauhaus(c, a, b, cc, d) {
      c.fillStyle = a; c.fillRect(0, 0, 0.5, 0.5); c.fillStyle = b; c.fillRect(0.5, 0, 0.5, 0.5);
      c.fillStyle = cc; c.fillRect(0, 0.5, 0.5, 0.5); c.fillStyle = d; c.fillRect(0.5, 0.5, 0.5, 0.5);
      c.fillStyle = d; c.beginPath(); c.moveTo(0, 0.5); c.arc(0, 0.5, 0.5, -Math.PI / 2, 0); c.closePath(); c.fill();
      c.fillStyle = a; c.beginPath(); c.moveTo(1, 0.5); c.arc(1, 0.5, 0.5, Math.PI / 2, Math.PI); c.closePath(); c.fill();
      c.fillStyle = b; c.beginPath(); c.arc(0.75, 0.25, 0.15, 0, TAU); c.fill();
    },
  };

  const grainCanvas = (() => {
    const cv = document.createElement('canvas'); cv.width = cv.height = 96;
    const g = cv.getContext('2d'), im = g.createImageData(96, 96);
    for (let i = 0; i < im.data.length; i += 4) { const v = 70 + Math.random() * 115; im.data[i] = im.data[i + 1] = im.data[i + 2] = v; im.data[i + 3] = 255; }
    g.putImageData(im, 0, 0); return cv;
  })();
  let curGrain = null;
  let curK = 1;

  // Each graphic item paints once into a bitmap per scale (grain baked in);
  // frames only blit it, inside the dot clip when it is near a seam.
  const bitmaps = new Map();
  function bitmapFor(it, k) {
    const kq = Math.round(k * 100) / 100, key = it.id + '@' + kq;
    let bm = bitmaps.get(key);
    if (bm) return bm;
    const pad = 1, cv = document.createElement('canvas');
    cv.width = Math.ceil((it.w + pad * 2) * kq); cv.height = Math.ceil((it.h + pad * 2) * kq);
    const c = cv.getContext('2d');
    c.setTransform(kq, 0, 0, kq, pad * kq, pad * kq);
    const saved = curGrain;
    curGrain = c.createPattern(grainCanvas, 'repeat');
    if (curGrain && curGrain.setTransform) curGrain.setTransform(new DOMMatrix([1 / kq, 0, 0, 1 / kq, 0, 0]));
    it.draw(c, 0, 0);
    curGrain = saved;
    bm = { cv, pad, kq, w: cv.width / kq, h: cv.height / kq, pats: new Map() };
    bitmaps.set(key, bm);
    if (bitmaps.size > 360) { let n = 0; for (const key2 of bitmaps.keys()) { bitmaps.delete(key2); if (++n >= 60) break; } }
    return bm;
  }
  function blit(ctx, it, sx, sy) {
    const bm = bitmapFor(it, curK);
    ctx.drawImage(bm.cv, sx - bm.pad, sy - bm.pad, bm.w, bm.h);
  }
  // Fill the current path with the item's own pixels (no clip mask needed).
  function fillWithItem(ctx, it, sx, sy) {
    const bm = bitmapFor(it, curK);
    let pat = bm.pats.get(ctx);
    if (!pat) { pat = ctx.createPattern(bm.cv, 'no-repeat'); bm.pats.set(ctx, pat); }
    if (!pat || !pat.setTransform) { ctx.save(); ctx.clip(); blit(ctx, it, sx, sy); ctx.restore(); return; }
    pat.setTransform(new DOMMatrix([1 / bm.kq, 0, 0, 1 / bm.kq, sx - bm.pad, sy - bm.pad]));
    ctx.fillStyle = pat;
    ctx.fill();
  }

  function rr(c, x, y, w, h, r) {
    r = Math.min(r, w / 2, h / 2);
    c.moveTo(x + r, y);
    c.arcTo(x + w, y, x + w, y + h, r); c.arcTo(x + w, y + h, x, y + h, r);
    c.arcTo(x, y + h, x, y, r); c.arcTo(x, y, x + w, y, r); c.closePath();
  }
  function fillRR(c, x, y, w, h, r, col) { c.fillStyle = col; c.beginPath(); rr(c, x, y, w, h, r); c.fill(); }

  function art(c, x, y, s, spec, radius, circle) {
    const [a, b, cc, d] = COVER_PALS[spec.pal];
    c.save();
    c.translate(x, y);
    c.beginPath();
    if (circle) c.arc(s / 2, s / 2, s / 2, 0, TAU); else rr(c, 0, 0, s, s, radius);
    c.clip();
    c.scale(s, s);
    ART[spec.style](c, a, b, cc, d, spec.q);
    c.scale(1 / s, 1 / s);
    if (curGrain) { c.globalAlpha = 0.16; c.globalCompositeOperation = 'overlay'; c.fillStyle = curGrain; c.fillRect(0, 0, s, s); }
    c.restore();
  }
  // Yoin's "Bun" backdrop behind a cover: two lobes on the left, a rounded body.
  function bun(c, x, y, w, h, col) {
    const r = h * 0.27;
    c.fillStyle = col; c.beginPath();
    c.moveTo(x + 2 * r, y + r); c.arc(x + r, y + r, r, 0, TAU);
    c.moveTo(x + 2 * r, y + h - r); c.arc(x + r, y + h - r, r, 0, TAU);
    rr(c, x + r, y, w - r, h, r * 1.2);
    c.fill();
  }

  // ───────────────────── icons (Material, filled) ─────────────────────
  const ICON = {
    home: new Path2D('M10 20v-6h4v6h5v-8h3L12 3 2 12h3v8z'),
    library: new Path2D('M20 2H8c-1.1 0-2 .9-2 2v12c0 1.1.9 2 2 2h12c1.1 0 2-.9 2-2V4c0-1.1-.9-2-2-2zm-2 5h-3v5.5c0 1.38-1.12 2.5-2.5 2.5S10 13.88 10 12.5s1.12-2.5 2.5-2.5c.57 0 1.08.19 1.5.51V5h4v2zM4 6H2v14c0 1.1.9 2 2 2h14v-2H4V6z'),
    gear: new Path2D('M19.14 12.94c.04-.3.06-.61.06-.94 0-.32-.02-.64-.07-.94l2.03-1.58c.18-.14.23-.41.12-.61l-1.92-3.32c-.12-.22-.37-.29-.59-.22l-2.39.96c-.5-.38-1.03-.7-1.62-.94l-.36-2.54c-.04-.24-.24-.41-.48-.41h-3.84c-.24 0-.43.17-.47.41l-.36 2.54c-.59.24-1.13.57-1.62.94l-2.39-.96c-.22-.08-.47 0-.59.22L2.74 8.87c-.12.21-.08.47.12.61l2.03 1.58c-.05.3-.09.63-.09.94s.02.64.07.94l-2.03 1.58c-.18.14-.23.41-.12.61l1.92 3.32c.12.22.37.29.59.22l2.39-.96c.5.38 1.03.7 1.62.94l.36 2.54c.05.24.24.41.48.41h3.84c.24 0 .44-.17.47-.41l.36-2.54c.59-.24 1.13-.56 1.62-.94l2.39.96c.22.08.47 0 .59-.22l1.92-3.32c.12-.22.07-.47-.12-.61l-2.01-1.58zM12 15.6c-1.98 0-3.6-1.62-3.6-3.6s1.62-3.6 3.6-3.6 3.6 1.62 3.6 3.6-1.62 3.6-3.6 3.6z'),
    chevron: new Path2D('M7.41 8.59 12 13.17l4.59-4.58L18 10l-6 6-6-6 1.41-1.41z'),
    search: new Path2D('M15.5 14h-.79l-.28-.27C15.41 12.59 16 11.11 16 9.5 16 5.91 13.09 3 9.5 3S3 5.91 3 9.5 5.91 16 9.5 16c1.61 0 3.09-.59 4.23-1.57l.27.28v.79l5 4.99L20.49 19l-4.99-5zm-6 0C7.01 14 5 11.99 5 9.5S7.01 5 9.5 5 14 7.01 14 9.5 11.99 14 9.5 14z'),
    wifi: new Path2D('M12 21 23.6 7C23.15 6.66 18.67 3 12 3S.85 6.66.4 7L12 21z'),
    signal: new Path2D('M2 22h20V2z'),
    battery: new Path2D('M15.67 4H14V2h-4v2H8.33C7.6 4 7 4.6 7 5.33v15.33C7 21.4 7.6 22 8.33 22h7.33c.74 0 1.34-.6 1.34-1.33V5.33C17 4.6 16.4 4 15.67 4z'),
    person: new Path2D('M12 12c2.21 0 4-1.79 4-4s-1.79-4-4-4-4 1.79-4 4 1.79 4 4 4zm0 2c-2.67 0-8 1.34-8 4v2h16v-2c0-2.66-5.33-4-8-4z'),
  };
  function icon(c, name, cx, cy, size, col) {
    const s = size / 24;
    c.save(); c.translate(cx - 12 * s, cy - 12 * s); c.scale(s, s); c.fillStyle = col; c.fill(ICON[name]); c.restore();
  }

  // ───────────────────── type ─────────────────────
  const FF = '"Google Sans Flex", "PingFang SC", "Hiragino Sans GB", "Noto Sans SC", system-ui, sans-serif';
  const SERIF = '"Songti SC", "Noto Serif SC", "Source Han Serif SC", serif';
  const F = {
    display: [`400 32px ${FF}`, 32], title22: [`600 22px ${FF}`, 22], head: [`600 22px ${FF}`, 22], head20: [`600 20px ${FF}`, 20],
    titleS: [`500 14px ${FF}`, 14], bodyS: [`400 12px ${FF}`, 12], bodyM: [`400 14px ${FF}`, 14], labelM: [`500 12px ${FF}`, 12],
    labelL: [`500 14px ${FF}`, 14], bodyL: [`400 16px ${FF}`, 16], strip: [`600 15px ${FF}`, 15], score: [`600 26px ${FF}`, 26],
    serif: [`600 16px ${SERIF}`, 16], time: [`500 15px ${FF}`, 15],
    bodySM: [`500 12px ${FF}`, 12], labelS: [`500 11px ${FF}`, 11],
  };
  const T = (text, x, y, f, color, maxW, align) => ({ kind: 't', text, x, y, font: f[0], fs: f[1], color, maxW, align: align || 'left' });
  const IC = (name, x, y, color, size) => ({ kind: 't', icon: name, x, y, color, size: size || 24, fs: 12 });
  let seedN = 0, gid = 0;
  const G = (x, y, w, h, draw) => ({ kind: 'g', id: ++gid, x, y, w, h, draw, seed: ((seedN++ * 7.31) % 10) });

  const ALBUMS = [
    ['Harbor Lights', 'Nao Mori'], ['海の見える部屋', '海野'], ['Slow Bloom', 'Ena'], ['春日迟迟', '白日梦乐队'],
    ['Paper Moon', 'Quiet Lines'], ['Blue Hour', 'Kota'], ['余白', 'Yuna'], ['Night Ferry', 'The Lanterns'],
    ['薄荷夏天', '林间'], ['Glass Garden', 'Mimi Sato'], ['雨后的街', '白日梦乐队'], ['Salt Window', 'Haru'],
    ['Soft Radio', 'Paper Satellites'], ['第七个夏天', '林间'], ['Low Orbit', 'Kota'], ['花火の音', '海野'],
    ['Copper Bell', 'The Lanterns'], ['Tin Sky', 'Quiet Lines'], ['North Pier', 'Nao Mori'], ['Last Tram', 'Haru'],
    ['晚风信', 'Yuna'], ['Far Field', 'Ena'], ['Lantern Walk', 'The Lanterns'], ['Open Water', 'Mimi Sato'],
    ['Static Channel', 'Paper Satellites'], ['月の裏側', '海野'], ['Folded Maps', 'Kota'], ['一万次日落', '白日梦乐队'],
    ['Morning Rope', 'Haru'], ['Gull Count', 'Nao Mori'], ['Idle Fans', 'Quiet Lines'], ['Warm Circuit', 'Ena'],
    ['Roof Orbit', 'Mimi Sato'], ['潮汐时间', '林间'], ['Signal Fire', 'The Lanterns'], ['Tide Keeps Time', 'Yuna'],
    ['Rain Wings', 'Kota'], ['Streetlight Edge', 'Paper Satellites'], ['白い呼吸', '海野'], ['Long Evening', 'Haru'],
    ['Relay Click', 'Quiet Lines'], ['Home Boats', 'Nao Mori'], ['橘子海', '白日梦乐队'], ['Far Lights', 'Ena'],
    ['Standby Light', 'Mimi Sato'], ['Soft Focus', 'Nao Mori'], ['Undertow', 'The Lanterns'], ['慢慢来', '林间'],
  ];
  const NP_COVER = COVERS[45];
  const NP_ITEM = { id: 'np', w: 36, h: 36, draw: (c, x, y) => art(c, x, y, 36, NP_COVER, 4) };

  // ───────────────────── pages ─────────────────────
  function buildLibrary() {
    seedN = 0;
    const items = [];
    const tile = (W - 32 - 24) / 3, pitch = tile + 5 + 20 + 16 + 16;
    ALBUMS.forEach((al, i0) => {
      const i = i0 === 30 ? 45 : i0 === 45 ? 30 : i0;          // the playing album (45) sits in row 10
      const [title, artist] = ALBUMS[i];
      const r = Math.floor(i0 / 3), c = i0 % 3;
      const x = 16 + c * (tile + 12), y = LIB.seam + 8 + r * pitch;
      const spec = COVERS[i];
      items.push(G(x, y, tile, tile, (ctx, sx, sy) => art(ctx, sx, sy, tile, spec, 4)));
      items.push(T(title, x, y + tile + 5 + 15, F.titleS, 'on', tile));
      items.push(T(artist, x, y + tile + 5 + 20 + 12, F.bodyS, 'onVar', tile));
    });
    const rows = Math.ceil(ALBUMS.length / 3);
    const end = LIB.seam + 8 + rows * pitch - 16 + 108 + NAV;
    const stress = LIB.seam + 8 + 10 * pitch + 95 - BAR.y;   // row 10 holds the playing album; its pale lower band meets the bar
    return { items, max: end - H, viewportTop: LIB.seam, stress };
  }

  function buildHome(P) {
    seedN = 0;
    const items = [];
    const tone = (spec, i) => COVER_PALS[spec.pal][i];
    let y = 4 + SB + 8;                                   // header row: statusBarsPadding + 8dp
    items.push(Object.assign(T('Home', 16, y + 35, F.display, 'on'), { title: 'text' }));
    items.push(Object.assign(G(12, y + 2, 118, 46, (ctx, sx, sy) => { ctx.font = F.display[0]; ctx.textAlign = 'left'; ctx.fillStyle = P.on; ctx.fillText('Home', sx + 4, sy + 33); }), { title: 'graphic' }));
    items.push(IC('chevron', 322, y + 24, 'onVar'));
    items.push(IC('gear', 372, y + 24, 'onVar'));
    y += 48 + 18;
    items.push(T('Activities', 16, y + 21, F.title22, 'on'));
    y += 28 + 12;
    // hero
    {
      const s = COVERS[5], box = mixHex(tone(s, 1), P.bg, P.cardMix), lobe = mixHex(tone(s, 0), P.bg, P.lobeMix);
      items.push(G(16, y, 380, 120, (ctx, sx, sy) => { fillRR(ctx, sx, sy, 380, 120, 16, box); bun(ctx, sx + 14, sy + 14, 100, 92, lobe); art(ctx, sx + 40, sy + 30, 76, s, 4); }));
      items.push(T('Album · 4h ago', 152, y + 34, F.labelM, 'onVar'));
      items.push(T('Blue Hour', 152, y + 64, F.head, 'on', 226));
      items.push(T('Kota', 152, y + 88, F.bodyM, 'onVar', 226));
      items.push(T('2026 · 9 songs · 32 min', 152, y + 108, F.bodyM, 'onVar', 226));
      y += 120 + 12;
    }
    // artist + album
    {
      const s = COVERS[1], box = mixHex(P.primaryC, P.bg, 0.5);
      items.push(G(16, y, 124, 100, (ctx, sx, sy) => { fillRR(ctx, sx, sy, 124, 100, 16, box); art(ctx, sx + 12, sy + 14, 40, s, 0, true); }));
      items.push(T('Artist', 78, y + 31, F.bodyM, 'onVar'));
      items.push(T('4h ago', 78, y + 49, F.bodyM, 'onVar', 58));
      items.push(T('海野', 28, y + 86, F.strip, 'on', 100));
      const a = COVERS[10], abox = mixHex(tone(a, 1), P.bg, P.cardMix), alobe = mixHex(tone(a, 0), P.bg, P.lobeMix);
      items.push(G(152, y, 244, 100, (ctx, sx, sy) => { fillRR(ctx, sx, sy, 244, 100, 16, abox); bun(ctx, sx + 12, sy + 12, 84, 76, alobe); art(ctx, sx + 32, sy + 24, 62, a, 4); }));
      items.push(T('Album · 5h ago', 262, y + 34, F.labelM, 'onVar'));
      items.push(T('Night Ferry', 262, y + 60, F.head20, 'on', 124));
      items.push(T('The Lanterns', 262, y + 82, F.bodyM, 'onVar', 124));
      y += 100 + 12;
    }
    // strip
    {
      const s = COVERS[3], box = mixHex(tone(s, 2), P.bg, P.cardMix + 0.12);
      items.push(G(16, y, 380, 44, (ctx, sx, sy) => fillRR(ctx, sx, sy, 380, 44, 22, box)));
      items.push(T('春日迟迟 · 白日梦乐队', 36, y + 28, F.strip, 'on', 220));
      items.push(T('Album · 5h ago', 376, y + 27, F.labelM, 'onVar', 110, 'right'));
      y += 44 + 18;
    }
    // Jump Back In
    items.push(T('Jump Back In', 16, y + 21, F.title22, 'on'));
    y += 28 + 14;
    const MEM = [['6.0', 'Sep 3', '潮水退去以后，', '留下的盐'], ['9.0', 'Sep 2', '一整个夏天的', '慢速回放'], ['7.5', 'Aug 30', '凌晨两点的', '那段贝斯'], ['8.2', 'Aug 27', '雨停之前，', '把副歌听完']];
    MEM.forEach((m, k) => {
      const x = 16 + (k % 2) * 198, cy = y + Math.floor(k / 2) * 250, s = COVERS[30 + k * 3];
      const lobe = mixHex(tone(s, 1), P.bg, P.lobeMix + 0.25);
      items.push(G(x, cy, 182, 140, (ctx, sx, sy) => { bun(ctx, sx, sy + 4, 150, 132, lobe); art(ctx, sx + 46, sy + 22, 118, s, 4); }));
      items.push(T(m[0], x, cy + 176, F.score, 'primary'));
      items.push(T(m[1], x + 50, cy + 174, F.labelM, 'onVar'));
      items.push(T(m[2], x, cy + 202, F.serif, 'on', 182));
      items.push(T(m[3], x, cy + 225, F.serif, 'on', 182));
    });
    y += 2 * 250 + 4;
    // Recently Added — the last section, as in Yoin's default layout. ONE full-bleed shelf: the
    // 2×2 track grid is its first card, album cards on their Bun backdrops follow, all panning
    // together; 16dp content padding, cut hard at the screen edges (no edge fade).
    items.push(T('Recently Added', 16, y + 21, F.title22, 'on'));
    y += 28 + 12;
    const shelfTop = y;
    const GRID_W = (W - 32 - 14) * (2.6 / 3.6), TILE_W = (GRID_W - 8) / 2, AC = 82;
    const sh = (it) => Object.assign(it, { shelf: true });
    for (let k = 0; k < 4; k++) {
      const x = 16 + (k % 2) * (TILE_W + 8), ty = y + Math.floor(k / 2) * (52 + 14), s = COVERS[12 + k], al = ALBUMS[12 + k];
      items.push(sh(G(x, ty, 52, 52, (ctx, sx, sy) => art(ctx, sx, sy, 52, s, 4))));
      items.push(sh(T(al[0], x + 60, ty + 22, F.bodySM, 'on', TILE_W - 60)));
      items.push(sh(T(al[1], x + 60, ty + 39, F.labelS, 'onVar', TILE_W - 60)));
    }
    // The playing album is the second card: at rest it peeks past the right margin, beside the bar,
    // on a backdrop taken from the same cover as the bar's colour.
    const ALB = [20, 45, 21, 22, 23, 24, 25, 26];
    ALB.forEach((ai, i) => {
      const x = 16 + GRID_W + 14 + i * (AC + 14), s = ai === 45 ? NP_COVER : COVERS[ai], al = ALBUMS[ai];
      const lobe = ai === 45 ? mixHex(P.bar, P.pill, 0.3) : mixHex(tone(s, 1), P.bg, P.lobeMix + 0.25);
      items.push(sh(G(x, y, AC, AC, (ctx, sx, sy) => { bun(ctx, sx, sy + 6, 70, 64, lobe); art(ctx, sx + 22, sy + 10, 60, s, 4); })));
      items.push(sh(T(al[0], x, y + AC + 6 + 12, F.bodySM, 'on', AC)));
      items.push(sh(T(al[1], x, y + AC + 6 + 12 + 15, F.labelS, 'onVar', AC)));
    });
    const shelfW = 16 + GRID_W + 14 + ALB.length * (AC + 14) - 14 + 16;
    y += AC + 6 + 12 + 15 + 6;
    const stress = shelfTop - (BAR.y - 30);            // the covers cross the bar's top edge
    return { items, max: y + 108 + NAV - H, viewportTop: 0, stress, shelf: { top: shelfTop, bottom: y, max: Math.max(0, Math.ceil(shelfW - W)) } };
  }

  // ───────────────────── chrome ─────────────────────
  function drawBackground(c, cfg, P) {
    if (cfg.page === 'library') { lin(c, 0, 0, 0, H, [[0, P.bgTop], [0.5, P.bg], [1, P.bgLow]]); }
    else c.fillStyle = P.bg;
    c.fillRect(-20, -20, W + 40, H + 40);
  }
  function drawLibraryHeader(c, P) {
    fillRR(c, 16, 56, 328, 56, 28, P.scHighest);
    icon(c, 'search', 44, 84, 24, P.onVar);
    c.font = F.bodyL[0]; c.textAlign = 'left'; c.fillStyle = P.onVar; c.fillText('Search your library', 68, 89.5);
    icon(c, 'gear', 376, 84, 24, P.onVar);
    let x = 16;
    c.font = F.labelL[0];
    ['Artists', 'Albums', 'Songs', 'Playlists', 'Favorites'].forEach((name, i) => {
      const w = c.measureText(name).width + 32;
      fillRR(c, x, 120, w, 44, 18, i === 1 ? P.secC : P.scHigh);
      c.fillStyle = i === 1 ? P.onSecC : P.onVar; c.fillText(name, x + 16, 147);
      x += w + 8;
    });
    const edge = mixHex(P.bgTop, P.bg, 0.3);
    lin(c, W - 40, 0, W, 0, [[0, rgba(edge, 0)], [1, edge]]);
    c.fillRect(W - 40, 118, 40, 48);
  }
  function drawBottomBar(c, cfg, P, k) {
    const shadow = cfg.bottom === 'now';
    c.save();
    if (shadow) { c.shadowColor = P.shadow; c.shadowBlur = 14 * k; c.shadowOffsetY = 5 * k; }
    fillRR(c, BAR.x, BAR.y, BAR.w, BAR.h, BAR.r, P.bar);
    c.restore();
    const lib = cfg.page === 'library';
    const x0 = BAR.x + 10, y0 = BAR.y + 10, bh = 48;
    const homeW = lib ? 48 : 72, libW = lib ? 72 : 48;
    fillRR(c, x0, y0, homeW, bh, 24, lib ? P.btn : P.primaryC);
    icon(c, 'home', x0 + homeW / 2, y0 + 24, 24, lib ? P.onVar : P.onPrimaryC);
    const px = x0 + homeW + 8, pw = BAR.w - 20 - homeW - libW - 16;
    c.save(); c.beginPath(); rr(c, px, y0, pw, bh, 24); c.clip();
    c.fillStyle = P.pill; c.fillRect(px, y0, pw, bh);
    const pr = px + pw * 0.42;
    c.beginPath(); c.moveTo(px, y0); c.lineTo(pr, y0);
    for (let t = 0; t <= 1.0001; t += 0.05) c.lineTo(pr + 3 * Math.sin(t * TAU * 1.5), y0 + t * bh);
    c.lineTo(px, y0 + bh); c.closePath(); c.fillStyle = P.wash; c.fill();
    c.restore();
    blit(c, NP_ITEM, px + 6, y0 + 6);
    c.textAlign = 'left';
    c.font = F.titleS[0]; c.fillStyle = P.on; c.fillText('Soft Focus', px + 52, y0 + 21);
    c.font = F.bodyS[0]; c.fillStyle = P.onVar; c.fillText('Nao Mori', px + 52, y0 + 38);
    const lx = px + pw + 8;
    fillRR(c, lx, y0, libW, bh, 24, lib ? P.primaryC : P.btn);
    icon(c, 'library', lx + libW / 2, y0 + 24, 24, lib ? P.onPrimaryC : P.onVar);
  }
  function drawStatusBar(c, P) {
    c.font = F.time[0]; c.textAlign = 'left'; c.fillStyle = P.on; c.fillText('20:47', 26, 31);
    icon(c, 'wifi', 336, 25, 17, P.on);
    icon(c, 'signal', 357, 25, 16, P.on);
    icon(c, 'battery', 381, 25, 18, P.on);
  }
  function drawNavHandle(c, P) { fillRR(c, W / 2 - 54, H - 14, 108, 4.5, 2.25, rgba(P.handle, 0.5)); }

  // ───────────────────── bottom field extras ─────────────────────
  // 退色: the field eases toward the page colour with depth (never a new colour).
  function bgAt(cfg, P, y) {
    if (cfg.page !== 'library') return P.bg;
    return y < H / 2 ? mixHex(P.bgTop, P.bg, clamp(y / (H / 2), 0, 1)) : mixHex(P.bg, P.bgLow, clamp((y - H / 2) / (H / 2), 0, 1));
  }
  function fadeTail(ctx, cfg, P, s) {
    const y0 = s.y, y2 = H + 20;
    const g = ctx.createLinearGradient(0, y0, 0, y2);
    g.addColorStop(0, rgba(bgAt(cfg, P, y0), 0));
    g.addColorStop(clamp((BAR.y - y0) / (y2 - y0), 0.01, 0.99), rgba(bgAt(cfg, P, BAR.y), 0.16));
    g.addColorStop(1, rgba(bgAt(cfg, P, H), 0.32));
    ctx.fillStyle = g;
    ctx.fillRect(-20, y0, W + 40, y2 - y0);
  }

  // ───────────────────── status bar screens ─────────────────────
  function layer(tg, name, w, h) {
    let z = tg[name];
    if (!z || z.cv.width !== w || z.cv.height !== h) {
      const cv = document.createElement('canvas'); cv.width = w; cv.height = h;
      z = { cv, ctx: cv.getContext('2d'), sig: '' };
      tg[name] = z;
    }
    return z;
  }

  // T2 grain: a stochastic (FM) screen. The noise is anchored to the content, so
  // grains ride with it; each grain survives while the noise under it is below
  // the density at its height, with a soft threshold so nothing pops.
  // Interleaved gradient noise: spreads grains evenly (no clumps), one line in AGSL.
  const ign = (x, y) => { const f = 0.06711056 * x + 0.00583715 * y; const g = 52.9829189 * (f - Math.floor(f)); return g - Math.floor(g); };
  const GRAIN = 0.8;
  function grainZone(tg, cfg, P, rt) {
    const k = tg.k, Z = SB + 2;
    const py0 = Math.max(0, Math.floor(-tg.oy * k)), py1 = Math.min(tg.canvas.height, Math.ceil((Z - tg.oy) * k));
    if (py1 <= py0) return;
    const w = tg.canvas.width, h = py1 - py0;
    const zc = layer(tg, 'gz', w, h), zm = layer(tg, 'gm', w, h);
    const sig = `${cfg.scroll.toFixed(2)}|${rt.toFixed(4)}|${k}|${tg.ox}|${tg.oy}`;
    if (zm.sig !== sig) {
      if (!zm.img || zm.img.width !== w || zm.img.height !== h) zm.img = zm.ctx.createImageData(w, h);
      const data = zm.img.data, gx = new Float64Array(w);
      for (let px = 0; px < w; px++) gx[px] = Math.floor((tg.ox + (px + 0.5) / k) / GRAIN);
      for (let py = 0; py < h; py++) {
        const ydp = tg.oy + (py0 + py + 0.5) / k;
        const dens = 1 - (1 - Math.pow(clamp(ydp / Z, 0, 1), 1.8)) * rt;
        const gy = Math.floor((ydp + cfg.scroll) / GRAIN);   // anchored to the content
        for (let px = 0, o = py * w * 4 + 3; px < w; px++, o += 4) {
          const a = (dens - ign(gx[px], gy)) * 14 + 0.5;
          data[o] = a <= 0 ? 0 : a >= 1 ? 255 : a * 255;
        }
      }
      zm.ctx.putImageData(zm.img, 0, 0);
      zm.sig = sig;
    }
    const c = zc.ctx;
    c.globalCompositeOperation = 'copy';
    c.drawImage(tg.canvas, 0, py0, w, h, 0, 0, w, h);
    c.globalCompositeOperation = 'destination-in';
    c.drawImage(zm.cv, 0, 0);
    c.globalCompositeOperation = 'source-over';
    const ctx = tg.ctx;
    ctx.save();
    ctx.setTransform(1, 0, 0, 1, 0, 0);
    ctx.fillStyle = P.bg; ctx.fillRect(0, py0, w, h);
    ctx.drawImage(zc.cv, 0, py0);
    ctx.restore();
  }

  // T3 tide: no screen at all. Two waves of page colour wash down from above
  // (the back one half clear); their phase follows scroll + afterglow and their
  // height follows scroll speed, so at rest the line holds still. On Home the
  // line sits under the status bar; on Library, used as an app bar edge, under
  // the chips row. The fill is the page's own background, so over bare page it
  // is invisible.
  function drawTide(ctx, cfg, P, rt) {
    const lib = cfg.page === 'library';
    const A = 2.4 + 3.2 * cfg.stretch;
    const rest = lib ? LIB.seam + 2 : SB + 2, hidden = lib ? LIB.seam - 8 : -8;
    const base = mix(hidden, rest, rt);
    const ph = (cfg.scroll + cfg.lag) / 88;
    const paint = lib ? (() => { const g = ctx.createLinearGradient(0, 0, 0, H); g.addColorStop(0, P.bgTop); g.addColorStop(0.5, P.bg); g.addColorStop(1, P.bgLow); return g; })() : P.bg;
    const wave = (off, amp, lam, phase, alpha) => {
      ctx.beginPath(); ctx.moveTo(-20, -20);
      for (let x = -20; x <= W + 20; x += 3) {
        ctx.lineTo(x, base + off + amp * (Math.sin((x / lam + phase) * TAU) + 0.18 * Math.sin((x / (lam * 0.5) - phase * 1.7 + 0.3) * TAU)));
      }
      ctx.lineTo(W + 20, -20); ctx.closePath();
      ctx.globalAlpha = alpha; ctx.fillStyle = paint; ctx.fill(); ctx.globalAlpha = 1;
    };
    wave(6, A * 1.1, 116, 0.37 - ph * 0.7, 0.55);
    wave(0, A, 72, ph, 1);
  }

  // T4 flowing colour: the status bar holds no content, only its colour. One
  // row just under the edge is read back (graphics only) and blurred sideways;
  // above the edge that colour comes up as grains of the same even noise as T2,
  // thinning and easing toward the page as they rise, anchored to the content
  // so they drift up while scrolling and stay put at rest. A 6dp fringe under
  // the edge melts the cut content into its own colour.
  const FLOW_FRINGE = 6;
  function boxBlur(src, dst, w, R) {
    const n = 2 * R + 1;
    for (let ch = 0; ch < 3; ch++) {
      let acc = 0;
      for (let i = -R; i <= R; i++) acc += src[clamp(i, 0, w - 1) * 3 + ch];
      for (let x = 0; x < w; x++) {
        dst[x * 3 + ch] = acc / n;
        acc += src[Math.min(w - 1, x + R + 1) * 3 + ch] - src[Math.max(0, x - R) * 3 + ch];
      }
    }
  }
  function sampleFlow(tg, cfg, P, rt) {
    const k = tg.k, w = tg.canvas.width;
    const Zt = SB + FLOW_FRINGE;
    const py0 = Math.max(0, Math.floor(-tg.oy * k)), py1 = Math.min(tg.canvas.height, Math.ceil((Zt - tg.oy) * k));
    const pyS = Math.round((SB + 3 - tg.oy) * k);
    const h = py1 - py0;
    const zf = layer(tg, 'fl', w, Math.max(1, h));
    zf.on = h > 0 && pyS >= 0 && pyS < tg.canvas.height;
    if (!zf.on) return;
    zf.py0 = py0;
    const sig = `${cfg.scroll.toFixed(2)}|${rt.toFixed(4)}|${cfg.theme}|${cfg.title}|${k}|${tg.ox}|${tg.oy}`;
    if (zf.sig === sig) return;
    zf.sig = sig;
    const rowCv = layer(tg, 'flr', w, 1);
    if (!rowCv.rctx) rowCv.rctx = rowCv.cv.getContext('2d', { willReadFrequently: true });
    rowCv.rctx.clearRect(0, 0, w, 1);
    rowCv.rctx.drawImage(tg.canvas, 0, pyS, w, 1, 0, 0, w, 1);
    const src = rowCv.rctx.getImageData(0, 0, w, 1).data;
    if (!zf.img || zf.img.width !== w || zf.img.height !== h) {
      zf.img = zf.ctx.createImageData(w, h);
      zf.a = new Float32Array(w * 3); zf.b = new Float32Array(w * 3); zf.c = new Float32Array(w * 3);
    }
    const base = zf.a;
    for (let x = 0; x < w; x++) { base[x * 3] = src[x * 4]; base[x * 3 + 1] = src[x * 4 + 1]; base[x * 3 + 2] = src[x * 4 + 2]; }
    const R = Math.max(1, Math.round((20 * k) / 1.7));      // wide: a mist of the edge's colours, not columns
    boxBlur(base, zf.b, w, R); boxBlur(zf.b, zf.c, w, R); boxBlur(zf.c, zf.b, w, R);
    const col = zf.b, out = zf.img.data, bg = hexRgb(P.bg);
    const gx = new Float64Array(w);
    for (let px = 0; px < w; px++) gx[px] = Math.floor((tg.ox + (px + 0.5) / k) / GRAIN);
    for (let py = 0; py < h; py++) {
      const ydp = tg.oy + (py0 + py + 0.5) / k;
      const inZone = ydp < SB;
      const t = clamp(ydp / SB, 0, 1);
      const dens = rt * Math.pow(t, 1.3);                     // grains present: all at the edge, none at the top
      const toBg = 0.55 * Math.pow(1 - t, 1.2);               // and they ease toward the page as they rise
      const fringe = rt * (1 - smooth(clamp((ydp - SB) / FLOW_FRINGE, 0, 1)));
      const gy = Math.floor((ydp + cfg.scroll) / GRAIN);
      for (let px = 0, o = py * w * 4; px < w; px++, o += 4) {
        let a;
        if (inZone) { a = (dens - ign(gx[px], gy)) * 14 + 0.5; a = a <= 0 ? 0 : a >= 1 ? 1 : a; }
        else a = fringe;
        out[o] = mix(col[px * 3], bg[0], toBg); out[o + 1] = mix(col[px * 3 + 1], bg[1], toBg); out[o + 2] = mix(col[px * 3 + 2], bg[2], toBg);
        out[o + 3] = a * 255;
      }
    }
    zf.ctx.putImageData(zf.img, 0, 0);
  }
  function drawFlow(tg) {
    const zf = tg.fl;
    if (!zf || !zf.on) return;
    const ctx = tg.ctx;
    ctx.save();
    ctx.setTransform(1, 0, 0, 1, 0, 0);
    ctx.drawImage(zf.cv, 0, zf.py0);
    ctx.restore();
  }

  // ───────────────────── rulers ─────────────────────
  const RULE = { seam: '#0a9fd0', holes: '#d6336c', text: '#e0a100', solid: '#8a9097' };
  // px: dp per CSS pixel of the target, so labels stay the same size in the phone and the loupes.
  function drawRulers(c, rulers, P, right, onlyTop, px) {
    c.save();
    c.font = `500 ${10.5 * px}px "Google Sans Code", ui-monospace, Menlo, monospace`;
    c.textAlign = 'right';
    const lineAt = (y, col, dash, text, lx) => {
      c.strokeStyle = col; c.lineWidth = 1.2 * px; c.setLineDash(dash.map((d) => d * px));
      c.beginPath(); c.moveTo(-20, y); c.lineTo(W + 20, y); c.stroke();
      if (!text) return;
      const w = c.measureText(text).width + 9 * px, h = 15 * px;
      c.setLineDash([]); c.fillStyle = P.ruleHalo; c.beginPath(); rr(c, lx - w, y - h / 2, w, h, h / 2); c.fill();
      c.fillStyle = col; c.fillText(text, lx - 4.5 * px, y + 3.6 * px);
    };
    const f = (v) => (Math.abs(v) < 0.95 ? '0' : Math.round(v).toString());
    for (const r of rulers) {
      if (r.tail) {
        if (onlyTop) continue;
        const t = r.tail;
        lineAt(t.y, RULE.seam, [], `起点 ${f(BAR.y - t.y)}`, right);
        lineAt(t.yb, RULE.solid, [1.5, 2.5], `纯网点 栏后 ${HIDE}`, right - 70 * px);
        if (t.moat > 0) {
          c.strokeStyle = RULE.holes; c.lineWidth = 1.2 * px; c.setLineDash([4 * px, 3 * px]);
          c.beginPath(); rr(c, BAR.x - t.moat, BAR.y - t.moat, BAR.w + 2 * t.moat, BAR.h + 2 * t.moat, BAR.r + t.moat); c.stroke();
        }
        continue;
      }
      if (onlyTop && r.dir < 0) continue;
      const s = r.dir;
      if (r.contour) {
        c.strokeStyle = RULE.seam; c.lineWidth = 1.2 * px; c.setLineDash([]);
        c.beginPath(); rr(c, BAR.x - 6, BAR.y - 6, BAR.w + 12, BAR.h + 12, BAR.r + 6); c.stroke();
      } else lineAt(r.y, RULE.seam, [], '交界', right);
      lineAt(r.y + s * r.m.solid, RULE.solid, [1.5, 2.5], `实色 ${f(r.m.solid)}`, right - 62 * px);
      lineAt(r.y + s * r.m.holes, RULE.holes, [4, 3], `出缝 ${f(r.m.holes)}`, right - 124 * px);
      if (r.tm.text > 0.5) lineAt(r.textY + s * r.tm.text, RULE.text, [4, 3], `文字 ${f(r.tm.text)}`, right - 186 * px);
    }
    c.restore();
  }

  // ───────────────────── scene ─────────────────────
  let PAGES = null;
  const TOP_SCREENS = new Set(['T1', 'T2', 'T3', 'T4']);
  function buildSeams(cfg, page) {
    const tn = tuningAt(cfg.curve, cfg.stretch);
    const rt = clamp(cfg.scroll / REVEAL, 0, 1);
    const rb = clamp((page.max - cfg.scroll) / REVEAL, 0, 1);
    const g = [], t = [], rulers = [];
    let clipTop = 0, zone = null, lines = null, tail = null;
    const S3 = { curve: 'new', G: SB, F: 6, gamma: 0.8, T: 0 };
    if (cfg.page === 'library' && cfg.libTide) {
      // The tide as an app bar edge: content sinks under the chips row instead of breaking into dots.
      t.push(textSeam('top', LIB.seam + 2, tn, rt));
      zone = 'T3';
    } else if (cfg.page === 'library') {
      clipTop = LIB.seam;
      g.push(flatSeam('top', LIB.seam, tn, rt)); t.push(textSeam('top', LIB.seam, tn, rt));
      rulers.push({ dir: 1, y: LIB.seam, m: metrics(tn), textY: LIB.seam, tm: metrics(tn) });
    } else if (cfg.status === 'S3') {
      g.push(flatSeam('top', 0, S3, rt)); t.push(textSeam('top', SB, tn, rt));
      rulers.push({ dir: 1, y: 0, m: metrics(S3), textY: SB, tm: metrics(tn) });
    } else if (TOP_SCREENS.has(cfg.status)) {
      // Text always fades at the status bar's lower edge; graphics meet the screen chosen for the status bar.
      t.push(textSeam('top', cfg.status === 'T3' ? SB + 2 : SB, tn, rt));
      zone = cfg.status;
      if (zone === 'T1') lines = { y0: 0, y1: SB, pitch: 3.5, reveal: rt };
      if (zone === 'T4') clipTop = SB;
    }
    if (cfg.bottom !== 'now') {
      // Text never fades at the bottom: text colours keep their contrast against every surface tone,
      // the bar's included, so text simply passes under the bar.
      tail = tailSeam(cfg, rb);
      g.push(tail);
      rulers.push({ tail });
    }
    const D = Number.isFinite(cfg.disorder) ? cfg.disorder : 1;
    for (const s of g) s.dis = D;
    return { g, t, clipTop, rulers, rt, rb, zone, lines, tail };
  }

  const fontEpoch = { n: 0 };
  function label(c, it) {
    if (it.maxW == null) return it.text;
    if (it._e === fontEpoch.n) return it._s;
    c.font = it.font;
    let s = it.text;
    if (c.measureText(s).width > it.maxW) { while (s.length > 1 && c.measureText(s + '…').width > it.maxW) s = s.slice(0, -1); s += '…'; }
    it._s = s; it._e = fontEpoch.n;
    return s;
  }

  function render(tg, cfg) {
    const { ctx, tctx } = tg;
    const P = PAL[cfg.theme];
    const page = PAGES[cfg.page];
    const sm = buildSeams(cfg, page);
    const k = tg.k;
    const setT = (c) => c.setTransform(k, 0, 0, k, -tg.ox * k, -tg.oy * k);
    curK = k;
    setT(ctx);
    drawBackground(ctx, cfg, P);

    const top = tg.oy - 50, bottom = tg.oy + tg.vh + 50;
    const left = tg.ox - 20, right = tg.ox + tg.vw + 20;
    const xOf = (it) => (it.shelf ? it.x - (cfg.shelfX || 0) : it.x);   // the Recently Added shelf pans sideways
    ctx.save();
    ctx.beginPath(); ctx.rect(-20, sm.clipTop, W + 40, H - sm.clipTop + 20); ctx.clip();
    for (const it of page.items) {
      if (it.kind !== 'g' || (it.title && it.title !== (cfg.title === 'dissolve' ? 'graphic' : 'none'))) continue;
      const sy = it.y - cfg.scroll, sx = xOf(it);
      if (sy > bottom || sy + it.h < top || sx > right || sx + it.w < left) continue;
      if (sm.lines && sm.lines.reveal > 0 && sy < sm.lines.y1) drawLinesItem(ctx, it, sx, sy, sm.lines);
      else drawGraphic(ctx, it, sx, sy, sm.g, cfg.lag);
    }
    ctx.restore();
    if (sm.zone === 'T4' && sm.rt > 0) sampleFlow(tg, cfg, P, sm.rt);   // before text: only graphics feed the colour
    if (sm.tail && cfg.fadeTail) fadeTail(ctx, cfg, P, sm.tail);        // before text: 退色 touches only the dots

    // Text: lines clear of every fade strip draw straight onto the screen; a
    // line inside a strip is drawn alone on the layer, masked (SeamFadeNode's
    // DstIn gradient) and copied back.
    const zones = sm.t.map(textZone);
    const layered = [];
    const paint = (c, it, sy) => {
      if (it.icon) { icon(c, it.icon, it.x, sy, it.size, P[it.color]); return; }
      c.font = it.font; c.textAlign = it.align; c.fillStyle = P[it.color];
      c.fillText(label(c, it), xOf(it), sy);
    };
    ctx.save();
    ctx.beginPath(); ctx.rect(-20, sm.clipTop, W + 40, H - sm.clipTop + 20); ctx.clip();
    for (const it of page.items) {
      if (it.kind !== 't' || (it.title && cfg.title === 'dissolve')) continue;
      const sy = it.y - cfg.scroll;
      const y0 = it.icon ? sy - it.size / 2 : sy - it.fs * 1.05, y1 = it.icon ? sy + it.size / 2 : sy + it.fs * 0.4;
      if (y0 - 4 > bottom || y1 + 4 < top || y1 < sm.clipTop) continue;
      if (it.shelf) { const tx = xOf(it); if (tx > right || tx + (it.maxW || 200) < left) continue; }
      let inZone = false;
      for (const [z0, z1] of zones) if (y1 > z0 && y0 < z1) { inZone = true; break; }
      if (inZone) layered.push([it, sy, y0 - 1, y1 + 1]); else paint(ctx, it, sy);
    }
    ctx.restore();
    if (layered.length) {
      // All strip lines go onto the layer together; each line then gets its own
      // (size-scaled) mask over its own band, and the layer is copied back once.
      tctx.setTransform(1, 0, 0, 1, 0, 0);
      tctx.clearRect(0, 0, tg.tcanvas.width, tg.tcanvas.height);
      setT(tctx);
      tctx.save();
      tctx.beginPath(); tctx.rect(-20, sm.clipTop, W + 40, H - sm.clipTop + 20); tctx.clip();
      for (const [it, sy] of layered) paint(tctx, it, sy);
      tctx.restore();
      layered.sort((p, q) => p[2] - q[2]);
      let prev = -Infinity, lo = Infinity, hi = -Infinity;
      for (const [it, , a0, b] of layered) {
        const a = Math.max(a0, prev);
        prev = Math.max(prev, b);
        if (b <= a) continue;
        lo = Math.min(lo, a); hi = Math.max(hi, b);
        tctx.save();
        tctx.beginPath(); tctx.rect(-20, a, W + 40, b - a); tctx.clip();
        for (let i = 0; i < sm.t.length; i++) {
          const [z0, z1] = zones[i];
          if (b > z0 && a < z1) maskLine(tctx, sm.t[i], it.icon ? 14 : it.fs, a, b);
        }
        tctx.restore();
      }
      if (hi > lo) {
        const cw = tg.tcanvas.width, chh = tg.tcanvas.height;
        const py = clamp(Math.floor((lo - tg.oy) * k) - 1, 0, chh), ph = clamp(Math.ceil((hi - lo) * k) + 3, 0, chh - py);
        if (ph > 0) { ctx.setTransform(1, 0, 0, 1, 0, 0); ctx.drawImage(tg.tcanvas, 0, py, cw, ph, 0, py, cw, ph); setT(ctx); }
      }
    }

    if (cfg.page === 'library' && sm.zone === 'T3') drawTide(ctx, cfg, P, sm.rt);
    if (cfg.page === 'home' && sm.rt > 0) {
      if (sm.zone === 'T2') grainZone(tg, cfg, P, sm.rt);
      else if (sm.zone === 'T3') drawTide(ctx, cfg, P, sm.rt);
      else if (sm.zone === 'T4') drawFlow(tg);
      if (cfg.status === 'S3' || sm.zone === 'T1' || sm.zone === 'T2') {
        // Whatever rises into the status bar eases toward the page colour; over bare page it changes nothing.
        lin(ctx, 0, 0, 0, SB, [[0, rgba(P.bg, 0.9 * sm.rt)], [0.5, rgba(P.bg, 0.5 * sm.rt)], [1, rgba(P.bg, 0)]]);
        ctx.fillRect(-20, -20, W + 40, SB + 20);
      }
    }
    if (sm.tail && cfg.bottom === 'P2') {
      const m = sm.tail.moat;
      fillRR(ctx, BAR.x - m, BAR.y - m, BAR.w + 2 * m, BAR.h + 2 * m, BAR.r + m, P.mat);
    }
    if (cfg.page === 'library') drawLibraryHeader(ctx, P);
    drawBottomBar(ctx, cfg, P, k);
    drawStatusBar(ctx, P);
    drawNavHandle(ctx, P);
    if (cfg.ruler) drawRulers(ctx, sm.rulers, P, tg.ox + tg.vw - 6 * tg.px, cfg.rulerTopOnly, tg.px);
    return sm;
  }

  // ───────────────────── targets ─────────────────────
  function makeTarget(canvas) {
    const tcanvas = document.createElement('canvas');
    return { canvas, ctx: canvas.getContext('2d'), tcanvas, tctx: tcanvas.getContext('2d'), k: 1, ox: 0, oy: 0, vw: W, vh: H, px: 1, grain: null };
  }
  function sizeTarget(tg, ox, oy, vw) {
    const rect = tg.canvas.getBoundingClientRect();
    if (!rect.width) return false;
    const dpr = Math.min(window.devicePixelRatio || 1, 2);
    const pw = Math.max(1, Math.round(rect.width * dpr)), ph = Math.max(1, Math.round(rect.height * dpr));
    if (tg.canvas.width !== pw || tg.canvas.height !== ph) {
      tg.canvas.width = pw; tg.canvas.height = ph; tg.tcanvas.width = pw; tg.tcanvas.height = ph;
    }
    tg.k = pw / vw; tg.ox = ox; tg.oy = oy; tg.vw = vw; tg.vh = ph / tg.k; tg.px = vw / rect.width;
    tg.grain = tg.ctx.createPattern(grainCanvas, 'repeat');
    if (tg.grain && tg.grain.setTransform) tg.grain.setTransform(new DOMMatrix([1 / tg.k, 0, 0, 1 / tg.k, 0, 0]));
    return true;
  }

  // ───────────────────── state ─────────────────────
  const $ = (id) => document.getElementById(id);
  const screenEl = $('screen'), scroller = $('scroller'), spacer = $('spacer');
  const phone = makeTarget($('phoneCanvas'));
  const reduceMQ = window.matchMedia ? window.matchMedia('(prefers-reduced-motion: reduce)') : null;
  const KEY = 'yoin-dissolve-lab/v4', OLD_KEY = 'yoin-dissolve-lab/v3';
  const CHOICES = {
    page: ['library', 'home'], curve: ['v1', 'A', 'B', 'C'], bottom: ['now', 'P0', 'P1', 'P2', 'P3'],
    status: ['now', 'S3', 'T1', 'T2', 'T3', 'T4'], title: ['fade', 'dissolve'], theme: ['light', 'dark'],
    side: ['left', 'right'], order: ['settle', 'always'],
  };
  const TUNE = { tailStart: [0, 48, 20], density: [15, 75, 40] };   // 51's pick: start 20dp above the bar, 40% coverage
  const st = {
    page: 'library', curve: 'C', bottom: 'P3', status: 'T3', title: 'fade', theme: 'light', ruler: false, side: 'left', order: 'settle',
    tailStart: TUNE.tailStart[2], density: TUNE.density[2], fadeTail: true, libTide: false, shelfX: 0,
    // Library opens with a row under the bar; Home with the hero card under the status bar.
    scroll: { library: 60, home: 150 }, lag: 0, pend: 0, pendAbs: 0, speed: 0, last: 0, raf: 0, demo: null, expect: null,
    slow: false, slowTail: false,   // 慢放: a demo and the settle after it run at a quarter speed
  };
  try {
    let saved = JSON.parse(localStorage.getItem(KEY) || 'null');
    if (!saved) { saved = JSON.parse(localStorage.getItem(OLD_KEY) || 'null'); if (saved) delete saved.tailStart; }
    if (saved && typeof saved === 'object') {
      for (const k of Object.keys(CHOICES)) if (CHOICES[k].includes(saved[k])) st[k] = saved[k];
      for (const k of Object.keys(TUNE)) if (Number.isFinite(saved[k])) st[k] = clamp(Math.round(saved[k]), TUNE[k][0], TUNE[k][1]);
      if (typeof saved.ruler === 'boolean') st.ruler = saved.ruler;
      if (typeof saved.fadeTail === 'boolean') st.fadeTail = saved.fadeTail;
      if (typeof saved.libTide === 'boolean') st.libTide = saved.libTide;
      if (Number.isFinite(saved.shelfX)) st.shelfX = Math.max(0, saved.shelfX);
      if (typeof saved.notes === 'string') $('notes').value = saved.notes;
    }
  } catch (e) { /* storage unavailable: defaults */ }
  function persist() {
    try {
      const o = { ruler: st.ruler, fadeTail: st.fadeTail, libTide: st.libTide, shelfX: st.shelfX, notes: $('notes').value };
      for (const k of Object.keys(CHOICES)) o[k] = st[k];
      for (const k of Object.keys(TUNE)) o[k] = st[k];
      localStorage.setItem(KEY, JSON.stringify(o));
    } catch (e) { /* ignore */ }
  }
  const reduced = () => !!(reduceMQ && reduceMQ.matches);
  const stretchAt = (speed) => (reduced() ? 0 : smooth(clamp((speed - 150) / 1300, 0, 1)));
  const stretchNow = () => stretchAt(st.speed);
  // Round 5: how far the dots are from their lattice. It reads the same speed follower as the
  // stretch (so it settles on the afterglow's beat) but saturates much earlier: a slow drag already
  // loosens the dots, and near rest it is linear in speed, so it fades exactly as e^(−ωt).
  const DISORDER_SPEED = 200;   // dp/s: D = 1 − e^(−speed / 200)
  const disorderAt = (order, speed) => (order === 'always' ? 1 : reduced() ? 0 : 1 - Math.exp(-speed / DISORDER_SPEED));
  function cfgFor(overrides) {
    const o = overrides || {};
    // A loupe of a page you are not scrolling shows that page at rest.
    const speed = o.still ? 0 : st.speed;
    const c = Object.assign({
      page: st.page, curve: st.curve, bottom: st.bottom, status: st.status, title: st.title, theme: st.theme, order: st.order,
      scroll: st.scroll[st.page], lag: o.still ? 0 : st.lag, stretch: stretchAt(speed), ruler: st.ruler,
      tailStart: st.tailStart, density: st.density, fadeTail: st.fadeTail, libTide: st.libTide, shelfX: st.shelfX,
    }, o);
    if (!Number.isFinite(c.disorder)) c.disorder = disorderAt(c.order, speed);
    return c;
  }
  const cssPerDp = () => screenEl.clientWidth / W;
  function syncSpacer() {
    const page = PAGES[st.page];
    spacer.style.height = `${scroller.clientHeight + page.max * cssPerDp()}px`;
  }
  function restoreScroll() {
    const y = st.scroll[st.page];
    st.expect = y;
    scroller.scrollTop = y * cssPerDp();
  }

  scroller.addEventListener('scroll', () => {
    const page = PAGES[st.page];
    const y = clamp(scroller.scrollTop / cssPerDp(), 0, page.max);
    const d = y - st.scroll[st.page];
    st.scroll[st.page] = y;
    if (st.expect !== null && Math.abs(y - st.expect) < 1) { st.expect = null; kick(); return; }
    st.expect = null;
    st.pend += d; st.pendAbs += Math.abs(d);
    kick();
  }, { passive: true });
  // Your own scrolling always runs in real time: it stops a demo and ends any slow motion.
  const interrupt = () => { stopDemo(); st.slowTail = false; };
  for (const ev of ['wheel', 'touchstart', 'pointerdown', 'keydown']) scroller.addEventListener(ev, interrupt, { passive: true });

  function kick() { if (!st.raf) st.raf = requestAnimationFrame(tick); }
  function tick(now) {
    st.raf = 0;
    const dtReal = st.last ? Math.min(0.05, Math.max(0.001, (now - st.last) / 1000)) : 1 / 60;
    st.last = now;
    // 慢放: a demo, and the settle that follows it, run on a quarter-speed clock (followers included).
    const dt = dtReal * (st.slow && (st.demo || st.slowTail) ? 0.25 : 1);
    if (st.demo) runDemo(dt);
    if (reduced()) { st.lag = 0; st.speed = 0; }
    else {
      const decay = Math.exp(-OMEGA * dt);
      const tn = tuningAt(st.curve, stretchNow());
      const cap = 0.5 * (tn.curve === 'orig' ? tn.band : tn.G);
      let next = clamp((st.lag + st.pend) * decay, -cap, cap);
      if (st.pend === 0 && Math.abs(next) < 0.02) next = 0;   // far below a pixel of dot travel
      st.lag = next;
      st.speed += (st.pendAbs / dt - st.speed) * (1 - decay);
      if (st.pendAbs === 0 && st.speed < 2) st.speed = 0;
    }
    st.pend = 0; st.pendAbs = 0;
    renderAll();
    if (!Number.isFinite(st.lag)) st.lag = 0;
    if (!Number.isFinite(st.speed)) st.speed = 0;
    if (!st.demo && st.lag === 0 && st.speed === 0) st.slowTail = false;
    if (st.lag !== 0 || st.speed > 0 || st.demo) st.raf = requestAnimationFrame(tick);
    else st.last = 0;
  }

  // ───────────────────── loupes ─────────────────────
  // Three groups, one per decision; a group renders only while it is on screen.
  const GROUPS = {
    // Round 5: the same moment twice, settling to the lattice at rest (new) or keeping the swirl (last round).
    order: { page: () => st.page, ox: () => (st.side === 'right' ? W - 150 : 0), oy: H - 164, vw: 150, over: (key) => ({ order: key, ruler: false, bottom: st.bottom === 'now' ? 'P3' : st.bottom }) },
    orderTop: { page: () => 'library', ox: 6, oy: LIB.seam - 22, vw: 150, over: (key) => ({ order: key, ruler: false, bottom: 'now', libTide: false }) },
    bottom: { page: () => st.page, ox: () => (st.side === 'right' ? W - 150 : 0), oy: H - 164, vw: 150, over: (key) => ({ bottom: key, ruler: false }) },
    top: { page: () => 'home', ox: 0, oy: 0, vw: 206, over: (key) => ({ status: key, ruler: false }) },
    curve: { page: () => 'library', ox: 6, oy: LIB.seam - 22, vw: 150, over: (key) => ({ curve: key, ruler: true, rulerTopOnly: true, bottom: 'now', libTide: false }) },
  };
  const loupes = [];
  for (const [name, g] of Object.entries(GROUPS)) {
    g.el = document.querySelector(`.loupes[data-group="${name}"]`);
    g.on = true;
    g.loupes = Array.from(g.el.querySelectorAll('canvas[data-key]')).map((cv) => Object.assign(makeTarget(cv), { key: cv.dataset.key }));
    loupes.push(...g.loupes);
  }
  if (window.IntersectionObserver) {
    const io = new IntersectionObserver((entries) => {
      for (const e of entries) {
        const g = GROUPS[e.target.dataset.group];
        if (!g) continue;
        const was = g.on;
        g.on = e.isIntersecting;
        if (g.on && !was) kick();
      }
    });
    for (const g of Object.values(GROUPS)) io.observe(g.el);
  }
  let sizedFor = '';
  function renderAll() {
    const sig = `${screenEl.clientWidth}|${st.side}`;
    if (sig !== sizedFor) {
      sizeTarget(phone, 0, 0, W);
      for (const g of Object.values(GROUPS)) for (const lp of g.loupes) sizeTarget(lp, typeof g.ox === 'function' ? g.ox() : g.ox, g.oy, g.vw);
      sizedFor = sig;
    }
    const cfg = cfgFor();
    const sm = render(phone, cfg);
    for (const g of Object.values(GROUPS)) {
      if (!g.on) continue;
      const page = g.page();
      for (const lp of g.loupes) render(lp, cfgFor(Object.assign({ page, scroll: st.scroll[page], still: page !== st.page }, g.over(lp.key))));
    }
    const tn = tuningAt(st.curve, cfg.stretch);
    const band = tn.curve === 'orig' ? tn.band : tn.G;
    $('readout').textContent = `滚动 ${Math.round(cfg.scroll)}dp · 速度 ${Math.round(st.speed)}dp/s · 余韵 ${st.lag.toFixed(1)}dp · 溶解带 ${band.toFixed(0)}dp · 无序 ${cfg.disorder.toFixed(2)}`;
    return sm;
  }

  // ───────────────────── demo ─────────────────────
  const DEMOS = {
    library: (m) => [[0, 0.25], [180, 1.5, 'io'], [180, 1.0], [760, 0.5, 'out'], [760, 1.2], [1380, 1.0, 'io'], [1380, 0.9], [m, 1.3, 'io'], [m, 1.0], [0, 1.9, 'io']],
    home: (m) => [[0, 0.25], [150, 1.4, 'io'], [150, 1.1], [Math.round(m * 0.55), 0.5, 'out'], [Math.round(m * 0.55), 1.2], [Math.round(m * 0.85), 1.0, 'io'], [Math.round(m * 0.85), 0.9], [m, 1.2, 'io'], [m, 1.0], [0, 1.9, 'io']],
  };
  function startDemo() {
    const steps = DEMOS[st.page](PAGES[st.page].max);
    if (reduced()) {
      const stops = [steps[1][0], steps[3][0], steps[7][0], 0];
      let i = 0;
      const next = () => { scroller.scrollTop = stops[i] * cssPerDp(); i++; if (i < stops.length) st.demoTimer = setTimeout(next, 1400); };
      next();
      return;
    }
    let t = 0;
    const segs = [];
    let from = st.scroll[st.page];
    for (const [to, dur, ease] of steps) { segs.push({ from, to, t0: t, t1: t + dur, ease: EASE[ease || 'lin'] }); t += dur; from = to; }
    st.demo = { segs, t: 0, total: t, main: true };
    $('demo').textContent = '停止演示';
    kick();
  }
  // Two short gestures for watching the dots settle: a fling that slows down by itself, and a drag
  // that stops dead (the finger stays on the glass), which shows the settle on its own.
  function startQuick(kind) {
    stopDemo();
    st.slowTail = false;
    const max = PAGES[st.page].max, y0 = st.scroll[st.page];
    const dist = kind === 'fling' ? 340 : 260;
    const dir = y0 + dist <= max ? 1 : -1;
    const to = clamp(y0 + dir * dist, 0, max);
    if (reduced()) { scroller.scrollTop = to * cssPerDp(); return; }
    const seg = kind === 'fling' ? { from: y0, to, t0: 0, t1: 1.1, ease: EASE.out } : { from: y0, to, t0: 0, t1: 0.5, ease: EASE.lin };
    st.demo = { segs: [seg], t: 0, total: seg.t1 };
    kick();
  }
  function stopDemo() {
    if (st.demoTimer) { clearTimeout(st.demoTimer); st.demoTimer = 0; }
    if (!st.demo) return;
    st.demo = null;
    $('demo').textContent = '自动演示';
  }
  function runDemo(dt) {
    const d = st.demo;
    d.t += dt;
    const t = d.t;
    if (t >= d.total) { scroller.scrollTop = d.segs[d.segs.length - 1].to * cssPerDp(); stopDemo(); st.slowTail = st.slow; return; }
    const s = d.segs.find((x) => t < x.t1) || d.segs[d.segs.length - 1];
    const p = s.t1 > s.t0 ? clamp((t - s.t0) / (s.t1 - s.t0), 0, 1) : 1;
    scroller.scrollTop = mix(s.from, s.to, s.ease(p)) * cssPerDp();
  }
  $('demo').addEventListener('click', () => { if (st.demo && st.demo.main) stopDemo(); else { stopDemo(); startDemo(); } });
  $('fling').addEventListener('click', () => startQuick('fling'));
  $('dragStop').addEventListener('click', () => startQuick('drag'));
  $('slow').checked = st.slow;
  $('slow').addEventListener('change', (e) => { st.slow = e.target.checked; if (!st.slow) st.slowTail = false; });
  $('toTop').addEventListener('click', () => { stopDemo(); scroller.scrollTo({ top: 0, behavior: reduced() ? 'auto' : 'smooth' }); });

  // ───────────────────── controls ─────────────────────
  const LABELS = {
    curve: { v1: 'v1 现状', A: 'A 只缩带高', B: 'B 迟发收尾', C: 'C 静紧动松' },
    bottom: { now: 'B0 现状（阴影分层）', P0: 'P0 直接铺到栏边', P1: 'P1 留白圈（上一版）', P2: 'P2 细白边', P3: 'P3 按亮度让位' },
    status: { now: 'S0 现状（不处理）', S3: 'S3 网点', T1: 'T1 线网', T2: 'T2 颗粒', T3: 'T3 潮线', T4: 'T4 流色' },
    title: { fade: '按文字淡出', dissolve: '按图形溶解' },
    order: { settle: '静整动乱（静止时回到规则点阵）', always: '一直不规整（上一版）' },
  };
  const f0 = (v) => (v < 0.95 ? '0' : Math.round(v).toString());
  function metricsLine(key) {
    const t = TUNINGS[key], m = metrics(t);
    const base = `<span><i>出缝</i> ${f0(m.holes)}dp</span><span><i>末端空白</i> ${f0(m.vanish)}dp</span><span><i>文字起淡</i> ${f0(m.text)}dp</span>`;
    if (key !== 'C') return base;
    const mm = metrics(tuningAt('C', 1));
    return `${base}<span><i>滚动时</i> 出缝 ${f0(mm.holes)} · 文字 ${f0(mm.text)}dp</span>`;
  }
  document.querySelectorAll('[data-metrics]').forEach((el) => { el.innerHTML = metricsLine(el.dataset.metrics); });
  document.querySelectorAll('[data-loupe-note]').forEach((el) => {
    const m = metrics(TUNINGS[el.dataset.loupeNote]);
    el.textContent = `出缝 ${f0(m.holes)}dp · 文字 ${f0(m.text)}dp`;
  });

  // Plain-language numbers for the chosen bottom tuning.
  function tailNumbers() {
    const moat = (ISO[st.bottom] || ISO.P0).moat;
    return { moat, len: st.tailStart + HIDE, k: covToK(st.density / 100) };
  }
  const startText = () => (st.tailStart === 0 ? '贴着栏上沿' : `栏上方 ${st.tailStart}dp`);
  function tuneLine() {
    if (st.bottom === 'now') return '';
    return `（起点 ${startText()} · 网点覆盖 ${st.density}% · 退色${st.fadeTail ? '开' : '关'}）`;
  }
  function implBottom() {
    const n = tailNumbers();
    const field = `底部不再有“交界线”：列表的 contentPadding 只用来保证最后一项能滚到栏上方，内容本身一直画到屏幕底边（窗口的 bottom inset 以内也画）。着色器（或 Path 回退）只作用于图形：从栏上沿上方 ${st.tailStart}dp 开始，在 ${n.len}dp 的过渡段里把点半径从 0.62 cell 用 smoothstep 缩到 ${n.k.toFixed(2)} cell（点阵覆盖率 ${st.density}%）；过渡段的后 ${HIDE}dp 在栏的后面完成，栏不透明，这段不占屏幕，所以静止时栏上方只看到刚开始出缝的那 ${st.tailStart}dp。前沿起伏 ±3dp，保留和交界一样的流动（包络 sin(πa)，相位 = 2a + 余韵/24dp${st.order === 'settle' ? '；起伏和流动都乘无序度 D，静止时为 0，过渡段和栏下面一样规整' : ''}）；过渡段以下是静止的规则点阵，到屏幕底边缓慢缩到 0.9 倍，不流动。过渡段随滚动速度最多再往上伸 20dp（和 C 共用速度跟随者，停下后收回），列表最后 40dp 滚动里过渡段收成 0。栏两侧也是同一片网点场：满宽的横向 shelf（Recently Added）越过 16dp 页边距时，栏左右两侧的内容同样按高度碎成点、跟着卡片横向移动；着色器的几何要用屏幕坐标，而不是 LazyColumn 内边距里的坐标，否则页边距以外的部分会漏掉不碎。文字不淡出、不变成点，照常从栏下穿过：M3 的文字色（onSurface / onSurfaceVariant）本来就是配 surface 这一组色调用的，栏的容器色也在这一组里，对比度照样够，文字和栏总是分得开。`;
    const fade = st.fadeTail ? '退色：网点叠一层向下加深的底色（画在图形之后、文字之前），起点 0、栏上沿 16%、屏幕底边 32%。' : '不退色：网点保持内容原色。';
    const ring = {
      P0: '不做隔离：点一直铺到栏的轮廓，栏靠自己的颜色和形状分开。',
      P1: '隔离圈：栏的圆角矩形外扩 8dp 以内不画点，圈外 10dp 内点半径按 smoothstep 从 0 长到正常（着色器里用到栏圆角矩形的有符号距离场；栏的位置和尺寸由 FloatingBottomBar 的布局坐标传给 seamDissolveViewport）。',
      P2: '细白边：栏外套一道 2dp 的实色边（浅色主题纯白，深色主题比 background 更暗一档），画在内容和文字之上、栏之下，属于栏这一层；边以内不画点，点在边上被硬切开，像贴纸的模切边。栏的圆角矩形由 FloatingBottomBar 的布局坐标传给 seamDissolveViewport。',
      P3: `按亮度让位：着色器在每个点的格子中心再采样一次内容，算它的 L*（叠加退色之后），和栏容器色的 L* 比：ΔL* ≤ 6 的点完全让位，≥ 18 的不让，中间 smoothstep。让位的点在离栏的圆角矩形 1.5dp 以内消失，1.5 + ${YIELD}dp 以外恢复原大小（用到栏圆角矩形的有符号距离场，栏的位置和尺寸由 FloatingBottomBar 的布局坐标传给 seamDissolveViewport；栏色随动态取色变化时一起传入）。对比足够的点一直铺到栏边，所以大多数时候看不到任何圈。`,
    }[st.bottom];
    return `${field}${ring}${fade}栏去掉阴影和 BottomBarShadow 的跨窗口交接；栏展开成全屏 Now Playing 时网点区域跟着栏上沿走或先淡出。`;
  }
  const IMPL = {
    curve: {
      v1: 'SeamDissolveTokens 保持现状（Band 36dp、SolidFrom 1.34、TextFadeFraction 0.8）。',
      A: 'Band 36dp → 20dp，其余不变：出缝约 17dp，末端空白约 4dp，文字 16dp 起淡。',
      B: '换曲线：半径 = 0.64 × cell × t^0.8，t = (d + 前沿起伏) / 16dp，前沿起伏 ±4dp，d ≥ 20dp 直接画实色；去掉现在的 0.1 偏移，点一直缩到交界线；文字在最后 12dp 内按 1 − (1 − x)² 淡出（前 5% 为 0），淡出长度取 max(12dp, 0.75 × 字号)，大字不会像被切开。',
      C: '同 B 的曲线，带高 G / 前沿起伏 / 文字带随滚动速度插值：静止 12 / 3 / 10dp，速度 ≥ 1450dp/s 时 34 / 8 / 26dp（150dp/s 以下不伸缩，中间 smoothstep）。速度跟随者和余韵共用 ω = √90，停下后约 0.5 秒收回；文字淡出长度取 max(文字带, 0.75 × 字号)；省电和“移除动画”下固定为静止值。',
    },
    status: {
      now: 'Home 顶部不处理。',
      S3: 'Home 的 LazyColumn 在屏幕上沿挂一条顶部交界，带高 = 状态栏高度（新曲线，前沿起伏 ±6dp），状态栏以下的内容完整；点的颜色向 background 靠拢：mix(content, background, 0.9 × (1 − y / 状态栏高) × reveal)。文字的交界在状态栏下沿。',
      T1: '状态栏区域（0 到状态栏高）里的图形 item 切成横线：线距 3.5dp，线锚定在 item 上随内容走；每条线的粗细 = 线距 × (1 − (1 − y / 状态栏高) × reveal)^0.9，状态栏以下完整。颜色向 background 靠拢同 S3。文字在状态栏下沿淡出。',
      T2: '状态栏区域用调频网（随机颗粒）：颗粒 0.8dp；噪声用交错梯度噪声 IGN(x, y) = fract(52.9829189 × fract(0.06711056x + 0.00583715y))，x、y 是颗粒坐标，y 加上滚动量让颗粒跟着内容走；保留条件 IGN < 1 − (1 − (y / 状态栏高)^1.8) × reveal，软阈值宽 0.07 避免闪烁。颜色向 background 靠拢同 S3。文字在状态栏下沿淡出。',
      T3: '状态栏区域盖两层 background 色的波浪：前层实色，下沿 = (状态栏高 + 2dp) × reveal − 8dp × (1 − reveal) + A × (sin(2π(x / 72dp + φ)) + 0.18 sin(2π(x / 36dp − 1.7φ + 0.3)))；后层 55% 不透明，下移 6dp，波长 116dp，相位反向。A 静止 2.4dp，随滚动速度增到 5.6dp（和 C 共用速度跟随者）；φ = (滚动 + 余韵) / 88dp。文字在状态栏下沿下 2dp 淡完。',
      T4: 'Home 的内容在状态栏下沿硬裁（clipRect），状态栏里画一层流色：取下沿下 3dp 那一行内容的颜色（只取图形层，文字不参与），横向高斯模糊（σ ≈ 12dp），得到一条沿 x 缓慢变化的颜色；状态栏内用和 T2 同一种颗粒网屏（IGN，颗粒 0.8dp，锚定在内容上）画这条颜色，保留条件 IGN < reveal × (y / 状态栏高)^1.3，颜色再向 background 混 0.55 × (1 − y / 状态栏高)^1.2；下沿以下 6dp 里这层颜色从不透明淡到 0，把裁切边化进去。AGSL 里只在状态栏区域执行：采样点的 y 固定在下沿那一行，x 方向在 ±24dp 内做 16 次高斯加权采样；只在滚动时更新，静止时缓存。',
    },
    title: {
      fade: '大标题按文字处理：挂 seamFade（淡出长度按字号，32sp 约 24dp）。',
      dissolve: '大标题按图形处理：挂 seamDissolve（整行标题当一张图）。',
    },
    order: {
      settle: '网点静止时回到规则点阵：凡是不只由位置决定的量（前沿起伏 = 每个 item 的弯曲 + 每个点的抖动，以及打旋位移）都乘上无序度 D = 1 − e^(−速度 / 200dp/s)。D 读的是和溶解带伸缩、余韵同一个速度跟随者（ω = √90），慢慢拖也会乱一点；停下后和余韵同一个节拍归位，约 0.2 秒过半，0.45 秒内回到格点、前沿拉直。静止时每个点都在自己的格点上，大小只由离交界（或栏）的距离决定，和栏下面的点阵是同一种规整。作用于所有交界的新曲线和底部过渡段；栏下方的点阵本来就不动，不变。省电和“移除动画”下 D 固定为 0（一直规整）。不新增动效参数，只多两个常量：YoinMotion.SeamDisorderSpeed = 200dp/s，和回退开关 YoinMotion.SeamSettleAtRest（默认 true；false 时 D 恒为 1，回到上一版），等真机上看过再定。',
      always: '静止时也保留打旋和前沿起伏（上一版）：位移和起伏只随位置变，不随速度变。',
    },
  };
  const RULE_MOTION = {
    settle: '溶解程度只由位置、滚动速度和余韵决定。滚动时网点会乱（打旋、前沿起伏），停下后在余韵的 0.5 秒里回到规则点阵；静止时所有网点都在格点上，完全不动。栏下方的点阵始终不流动，播放控件旁边保持安静。省电和“移除动画”下关掉余韵、随速度伸缩和无序，网点一直是规整的。',
    always: '溶解程度只由位置、滚动速度和余韵决定，静止时完全不动。栏周围的网点是规则点阵，只有上方的过渡段会流动，播放控件旁边保持安静。省电和“移除动画”下关掉余韵和随速度伸缩。',
  };
  function summary() {
    const lines = [
      `Yoin 溶解定稿（${new Date().toISOString().slice(0, 10)}）`,
      '',
      `1. 底部 Now Playing 栏：${LABELS.bottom[st.bottom]}${tuneLine()}`,
      `   ${st.bottom === 'now' ? '保持现状：内容从栏后穿过，保留 12dp 阴影和 BottomBarShadow 的跨窗口交接。' : implBottom()}`,
      `2. Home 状态栏：${LABELS.status[st.status]}`,
      `   ${IMPL.status[st.status]}`,
      ...(st.libTide ? ['   Library 顶栏也用潮线：胶囊行下沿不再挂网点交界，改成同一对波浪（下沿静止在胶囊行下 2dp，前 40dp 滚动里从胶囊行下沿以上 8dp 降下来），填色用页面自己的背景渐变；文字在同一位置淡出。'] : []),
      `3. 大标题：${LABELS.title[st.title]}`,
      `   ${IMPL.title[st.title]}`,
      `4. 曲线：${LABELS.curve[st.curve]}`,
      `   ${IMPL.curve[st.curve]}`,
      `5. 静止时的点：${LABELS.order[st.order]}`,
      `   ${IMPL.order[st.order]}`,
      '',
      `通用：点阵锚定在 item 上；点只用内容自己的像素，只向底色靠拢；静止时不动；只有碰到交界或在栏周围的 item 重绘。Library 胶囊下${st.libTide ? '用潮线' : '用网点'}，底栏用网点，状态栏用上面选的效果。`,
    ];
    const notes = $('notes').value.trim();
    if (notes) lines.push('', `补充：${notes}`);
    return lines.join('\n');
  }
  const RULE_TOP = {
    now: '状态栏暂不处理：内容直接滑进去。',
    S3: '状态栏用和 app 里一样的网点，进入状态栏后越往上越小、越淡。',
    T1: '状态栏用线网：进入状态栏的内容切成横线，越往上越细。和网点同属“网屏”，但一眼能看出不是 app 的边。',
    T2: '状态栏用调频颗粒：内容碎成细密的随机颗粒，越往上越稀。没有点阵的规律，最安静。',
    T3: '状态栏用潮线：不用网屏，内容沉进一道缓慢起伏的底色水线，状态栏这条带子像一个极简的 App Bar，永远干净。',
    T4: '状态栏用流色：状态栏里不放内容，只放内容在下沿处的颜色，化成细颗粒往上飘，越往上越稀、越靠近底色。相当于一层颜色取自内容的系统栏保护层。',
  };
  function syncText() {
    const bk = st.bottom === 'now' ? 'B0' : st.bottom, sk = st.status === 'now' ? 'S0' : st.status;
    $('combo').innerHTML = `底部 <b>${bk}</b> · 状态栏 <b>${sk}</b> · 标题<b>${st.title === 'fade' ? '淡出' : '溶解'}</b> · 曲线 <b>${st.curve}</b> · 静止<b>${st.order === 'settle' ? '规整' : '不规整'}</b>`;
    $('summary').value = summary();
    const m = metrics(TUNINGS[st.curve]), n = tailNumbers();
    $('rule-bottom').textContent = st.bottom === 'now'
      ? '底部保持现状：内容从栏后穿过，靠阴影分层。'
      : `图形不在栏前停下：${st.tailStart === 0 ? '到栏上沿' : `到栏上方 ${st.tailStart}dp`}才开始碎，其余的过渡藏在栏后面完成，栏下面已经是纯网点，一直铺到屏幕底边；横向列表越过页边距时，栏左右两侧的内容也一样。栏去掉阴影，${{ P0: '网点直接铺到栏的轮廓', P1: '外面留 8dp 的空白圈，圈外 10dp 内点先缩小', P2: '外面一道 2dp 的白边（深色主题更暗），把点切开', P3: '只有亮度和栏接近的点靠近栏时才缩小让开，其他点一直铺到栏边' }[st.bottom]}。文字不淡出，照常从栏下穿过。`;
    $('rule-top').textContent = RULE_TOP[st.status];
    $('rule-motion').textContent = RULE_MOTION[st.order];
    $('rule-size').textContent = `顶部交界静止时离交界 ${f0(m.holes)}dp 以内才出缝，文字只在最后 ${f0(m.text)}dp 淡出。${st.bottom === 'now' ? '' : `底部静止时${st.tailStart === 0 ? '贴着栏上沿' : `只在栏上方 ${st.tailStart}dp`}开始碎，其余 ${HIDE}dp 的过渡藏在不透明的栏后面，不占屏幕；滚动时随速度最多再往上伸 20dp。`}`;
    syncTune();
  }
  function syncTune() {
    $('tailStartOut').textContent = startText();
    $('densityOut').textContent = `${st.density}%`;
    const off = st.bottom === 'now';
    for (const id of ['tailStart', 'density', 'fadeTail']) $(id).disabled = off;
  }
  function setRadio(name, value) { const el = document.querySelector(`input[name="${name}"][value="${value}"]`); if (el) el.checked = true; }
  function switchPage(page) {
    if (st.page === page) return;
    stopDemo();
    st.page = page; st.lag = 0; st.speed = 0;
    setRadio('page', page);
    syncSpacer(); restoreScroll();
    syncText();
  }
  for (const name of ['curve', 'bottom', 'status', 'title', 'page', 'theme', 'side', 'order']) {
    setRadio(name, st[name]);
    document.querySelectorAll(`input[name="${name}"]`).forEach((el) => el.addEventListener('change', () => {
      if (!el.checked) return;
      if (name === 'page') { switchPage(el.value); }
      else if (name === 'theme') { st.theme = el.value; PAGES = buildPages(); }
      else if (name === 'side') { st.side = el.value; sizedFor = ''; }
      else {
        st[name] = el.value;
        if (name === 'status' || name === 'title') {
          if (st.page !== 'home') switchPage('home');
          const want = name === 'title' ? [40, 160, 86] : [48, 99999, 150];
          if (st.scroll.home < want[0] || st.scroll.home > want[1]) { st.scroll.home = want[2]; restoreScroll(); }
        }
      }
      syncText(); persist(); kick();
    }));
  }
  for (const id of Object.keys(TUNE)) {
    const el = $(id);
    el.min = TUNE[id][0]; el.max = TUNE[id][1]; el.value = st[id];
    el.addEventListener('input', () => { st[id] = clamp(Math.round(+el.value), TUNE[id][0], TUNE[id][1]); syncText(); persist(); kick(); });
  }
  // The Recently Added shelf pans sideways: slider, trackpad swipe or drag over it on the phone.
  function shelfMax() { return PAGES.home.shelf.max; }
  function syncShelf() {
    st.shelfX = clamp(st.shelfX, 0, shelfMax());
    $('shelfX').max = shelfMax(); $('shelfX').value = Math.round(st.shelfX);
    $('shelfXOut').textContent = st.shelfX < 0.5 ? '静止位置' : `左滑 ${Math.round(st.shelfX)}dp`;
  }
  function overShelf(clientY) {
    if (st.page !== 'home') return false;
    const r = screenEl.getBoundingClientRect(), y = (clientY - r.top) / cssPerDp() + st.scroll.home, sh = PAGES.home.shelf;
    return y > sh.top - 6 && y < sh.bottom + 6;
  }
  $('shelfX').addEventListener('input', (e) => { st.shelfX = +e.target.value; syncShelf(); persist(); kick(); });
  scroller.addEventListener('wheel', (e) => {
    if (Math.abs(e.deltaX) <= Math.abs(e.deltaY) || !overShelf(e.clientY)) return;
    e.preventDefault();
    st.shelfX += e.deltaX / cssPerDp();
    syncShelf(); persist(); kick();
  }, { passive: false });
  let drag = null;
  scroller.addEventListener('pointerdown', (e) => {
    if (!overShelf(e.clientY)) return;
    drag = { id: e.pointerId, x0: e.clientX, y0: e.clientY, s0: st.shelfX, on: false };
  });
  scroller.addEventListener('pointermove', (e) => {
    if (!drag || e.pointerId !== drag.id) return;
    const dx = e.clientX - drag.x0, dy = e.clientY - drag.y0;
    if (!drag.on) {
      if (Math.abs(dx) > 6 && Math.abs(dx) > Math.abs(dy)) { drag.on = true; try { scroller.setPointerCapture(e.pointerId); } catch (err) { /* ignore */ } }
      else if (Math.abs(dy) > 6) { drag = null; return; }
      else return;
    }
    st.shelfX = drag.s0 - dx / cssPerDp();
    syncShelf(); kick();
  });
  const endDrag = (e) => { if (drag && e.pointerId === drag.id) { if (drag.on) persist(); drag = null; } };
  scroller.addEventListener('pointerup', endDrag);
  scroller.addEventListener('pointercancel', endDrag);

  $('libTide').checked = st.libTide;
  $('libTide').addEventListener('change', (e) => {
    st.libTide = e.target.checked;
    if (st.libTide && st.page !== 'library') switchPage('library');
    syncText(); persist(); kick();
  });
  $('fadeTail').checked = st.fadeTail;
  $('fadeTail').addEventListener('change', (e) => { st.fadeTail = e.target.checked; syncText(); persist(); kick(); });
  $('stress').addEventListener('click', () => {
    stopDemo();
    st.scroll[st.page] = clamp(PAGES[st.page].stress, 0, PAGES[st.page].max);
    if (st.page === 'home') {
      // The playing album peeks past the right margin at rest: look at the bar's right end.
      st.shelfX = 0; syncShelf();
      if (st.side !== 'right') { st.side = 'right'; setRadio('side', 'right'); sizedFor = ''; }
      persist();
    }
    st.lag = 0; st.speed = 0;
    restoreScroll(); kick();
  });
  $('ruler').checked = st.ruler;
  $('ruler').addEventListener('change', (e) => { st.ruler = e.target.checked; persist(); kick(); });
  $('notes').addEventListener('input', () => { $('summary').value = summary(); persist(); });
  $('copy').addEventListener('click', () => {
    const text = summary();
    const done = (msg) => { $('copyState').textContent = msg; };
    const fallback = () => { const ta = $('summary'); ta.focus(); ta.select(); done('已选中全文，按 ⌘C 复制'); };
    try {
      if (navigator.clipboard && navigator.clipboard.writeText) navigator.clipboard.writeText(text).then(() => done('已复制'), fallback);
      else fallback();
    } catch (e) { fallback(); }
  });

  // A figure-only scene for the design book: two flat cards at the bar, the same shape and flatness,
  // one in the bar's own colour and one dark, so lightness is the only difference.
  function buildDemo(P) {
    seedN = 0;
    const items = [], y = BAR.y - 64;
    items.push(G(16, y, 186, 186, (ctx, sx, sy) => fillRR(ctx, sx, sy, 186, 186, 22, mixHex(P.bar, P.pill, 0.3))));
    items.push(G(210, y, 186, 186, (ctx, sx, sy) => fillRR(ctx, sx, sy, 186, 186, 22, '#3d405b')));
    return { items, max: 400, viewportTop: 0, stress: 0, shelf: { top: 0, bottom: 0, max: 0 } };
  }
  function buildPages() { lattices.clear(); bitmaps.clear(); lumCache.clear(); return { library: buildLibrary(), home: buildHome(PAL[st.theme]), demo: buildDemo(PAL[st.theme]) }; }
  PAGES = buildPages();
  syncShelf();

  const ro = new ResizeObserver(() => { sizedFor = ''; syncSpacer(); restoreScroll(); kick(); });
  ro.observe(screenEl);
  for (const lp of loupes) ro.observe(lp.canvas);
  if (document.fonts && document.fonts.ready) document.fonts.ready.then(() => { fontEpoch.n++; kick(); });
  if (document.fonts && document.fonts.load) Promise.all([
    document.fonts.load(`400 32px "Google Sans Flex"`), document.fonts.load(`500 14px "Google Sans Flex"`), document.fonts.load(`600 22px "Google Sans Flex"`),
  ]).then(() => { fontEpoch.n++; kick(); }, () => {});
  syncText();
  syncSpacer();
  restoreScroll();
  kick();
  window.__lab = { st, renderAll, render, phone, loupes, GROUPS, cfgFor, makeTarget, sizeTarget, PAGES: () => PAGES, setTheme: (t) => { st.theme = t; PAGES = buildPages(); } }; // test hook; stripped from the published build
})();
