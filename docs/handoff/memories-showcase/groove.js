(function () {
  /* 唱片刻纹 · Groove — the chosen Memory emblem (owner, 2026-10-03), the 96dp award pinned to the cover.
     One ring per track (outer = track 1, merged into ≤ N rings when small). Only rated tracks are CUT (solid,
     flat palette colour); unrated tracks stay a dotted pre-cut lattice. The centre label carries the score:
     album = filled Cookie12Sided (most ceremonious), average = outlined circle on a paler disc,
     unrated = a dashed Cookie12Sided "empty mould" on a neutral lattice.

     v3 (owner review): tilt, four award tiers by score (t1 < 6 · t2 6–8 · t3 8–10 · t4 = 10.0), hapticPlan(m) as
     VibrationEffect.Composition primitives found on the same spring curves that drive the picture.

     v4 (owner review, 2026-10-04): flat Material, nothing glossy.
     · No sheen, no specular wedge, no label glint, no glow halos, no drop shadow, no gradients. Every fill and stroke
       is one flat colour.
     · Tilt is read as COLOUR, not light: the outer ring lines (the rim, and the cut runs of the two outer rings) are
       split into 15° segments with two flat overlays (a lighter tone shifted toward the accent, and a deeper tone);
       their opacity follows the cosine of the segment's angle to the tilt direction. setTilt also writes
       --ye-groove-ta (direction) and --ye-groove-tm (size, 0 at rest); the overlay pair turns to ta and fades by tm.
       At rest nothing shows; tilted, the side the device leans toward warms and lightens, the far side deepens.
       setTilt / tiltVars / tiltDriver keep their API (tiltVars returns ta / tm too).
     · Award tiers keep their springs and beats; the light effects became flat colour: rings change to a lit colour
       (t2 all cuts together at the overshoot, t3 the rated rings one by one, t4 every ring outer → inner), and the
       10.0 moment is a flat accent pulse on the rim plus one thin ring released from it (no blend modes, no halo).
     · No note beads on the disc (the owner read them as noise); notes live in the diary.
     Inline copies of this file are allowed: an equal or newer registered version wins. */
  const KEY = 'groove', VERSION = 4;
  window.YS_EMBLEM = window.YS_EMBLEM || {};
  const prev = window.YS_EMBLEM[KEY];
  if (prev && (prev.version || 0) >= VERSION) return;

  const PHI = -128;                       // resting light direction (deg, 0 = 3 o'clock, clockwise), screen space
  const DEG = Math.PI / 180;
  const WHITE = '#ffffff', BG_D = '#141218', SURF_D = '#1d1b20';
  const N_L = '#7b7581', N_D = '#958e99'; // --outline tokens (light / dark)
  const LI_REST = .8;                     // tilt intensity variable at rest (kept for setTilt's API; v4 draws nothing from it)
  const TINT_SEG = 24;                    // tilt colour: 15° flat segments on the outer ring lines
  const rgb = h => { h = h.replace('#', ''); return [0, 2, 4].map(i => parseInt(h.substr(i, 2), 16)); };
  const hex = c => '#' + c.map(v => Math.round(Math.max(0, Math.min(255, v))).toString(16).padStart(2, '0')).join('');
  const mix = (a, b, t) => { const A = rgb(a), B = rgb(b); return hex(A.map((v, i) => v + (B[i] - v) * t)); };
  const rgba = (h, a) => { const c = rgb(h); return `rgba(${c[0]},${c[1]},${c[2]},${a})`; };
  const f = v => (+v).toFixed(2);
  const kindOf = m => m.scoreKind === 'album' ? 'album' : m.scoreKind === 'average' ? 'average' : 'none';
  const scoreTxt = m => m.score == null ? '' : m.score.toFixed(1);
  const reduced = () => (window.YS && YS.reducedMotion) ? YS.reducedMotion() : matchMedia('(prefers-reduced-motion: reduce)').matches;

  const CSS = `
.ye-groove-root{position:relative;flex:none;display:block}
.ye-groove-root>svg.ye-groove-disc{position:absolute;inset:0;width:100%;height:100%;overflow:visible}
.ye-groove-label{position:absolute;display:flex;flex-direction:column;align-items:center;justify-content:center;transform-origin:50% 50%}
.ye-groove-label>svg{position:absolute;inset:0;width:100%;height:100%;overflow:visible;transform-origin:50% 50%}
.ye-groove-score{position:relative;font-family:var(--font-sans);line-height:1;letter-spacing:0;white-space:nowrap}
.ye-groove-cap{position:relative;font-family:var(--font-sans);font-weight:600;line-height:1;letter-spacing:0;white-space:nowrap}
.ye-groove-ripple{transform-box:fill-box;transform-origin:center;opacity:0;animation:ye-groove-ripple 4.8s cubic-bezier(.16,.8,.3,1) infinite}
@keyframes ye-groove-ripple{0%{opacity:0;transform:scale(1)}8%{opacity:.5}62%,100%{opacity:0;transform:scale(1.3)}}
@media (prefers-reduced-motion: reduce){.ye-groove-ripple{animation:none;opacity:0}}
.ye-groove-hx{position:relative;height:40px;min-width:0;font-family:var(--font-sans)}
.ye-groove-hx-axis{position:absolute;left:0;right:0;top:15px;height:2px;border-radius:1px;background:currentColor;opacity:.18}
.ye-groove-hx-span{position:absolute;left:0;top:15px;height:2px;border-radius:1px;background:currentColor;opacity:.42}
.ye-groove-hx-t{position:absolute;top:27px;font:500 10.5px/1 var(--font-sans);opacity:.6;transform:translateX(-50%);white-space:nowrap;font-variant-numeric:tabular-nums}
.ye-groove-hx-t:first-of-type{transform:none}
.ye-groove-hx-b{position:absolute;top:16px;width:0;height:0;line-height:0}
.ye-groove-hx-b>svg{position:absolute;overflow:visible;transform-origin:50% 50%}
.ye-groove-hx-b.on{color:var(--primary)}
.ye-groove-hx-head{position:absolute;top:3px;left:0;width:2px;height:26px;margin-left:-1px;border-radius:1px;background:var(--primary);opacity:0;pointer-events:none}
.ye-groove-hx-dot{position:absolute;right:0;top:-2px;width:9px;height:9px;border-radius:50%;background:var(--primary);opacity:0}`;
  let st = document.getElementById('ye-groove-style');
  if (!st) { st = document.createElement('style'); st.id = 'ye-groove-style'; document.head.appendChild(st); }
  st.textContent = CSS;

  /* ---------- colour: direct palette lerp, never hue-rotated; every value is one flat colour ----------
     v4: the old two-stop values (disc0/1, cutA/B, lab0/1) are folded to one flat colour each, at the mid point the
     gradient used to show, so the drawings keep their tone without any gradient. hi / lo are the tilt colours of the
     outer ring lines: hi leans toward the accent (and lighter), lo toward the deep tone. lit = a ring lit by the award. */
  function colours(m, kind, dark, on) {
    const p = m.palette, N = dark ? N_D : N_L;
    let c;
    if (kind === 'album') c = dark ? {
      disc: mix(mix(p.base, BG_D, .34), mix(p.base, p.soft, .06), .6),
      cut: mix(mix(p.soft, p.base, .12), mix(p.accent, p.soft, .15), .4), under: rgba(p.soft, .16),
      dot: rgba(p.soft, .46), rim: p.accent, rim2: rgba(p.accent, .5),
      lab: mix(mix(p.base, p.soft, .66), mix(p.base, p.soft, .42), .5), labRing: mix(p.deep, p.base, .3),
      ink: p.deep, cap: mix(p.deep, p.base, .35),
      hiT: mix(p.accent, WHITE, .45), loT: mix(p.base, BG_D, .25), lit: mix(p.accent, WHITE, .3), flare: mix(p.accent, WHITE, .15)
    } : {
      disc: mix(mix(p.soft, WHITE, .62), mix(p.soft, p.base, .06), .6),
      cut: mix(mix(p.base, p.deep, .2), mix(p.base, p.accent, .32), .4), under: rgba(p.base, .1),
      dot: rgba(p.base, .42), rim: p.base, rim2: mix(p.accent, p.base, .25),
      lab: mix(mix(p.base, WHITE, .14), mix(p.base, p.deep, .3), .5), labRing: p.accent,
      ink: mix(p.soft, WHITE, .72), cap: mix(p.soft, WHITE, .35),
      hiT: p.accent, loT: p.deep, lit: p.accent, flare: mix(p.accent, p.base, .1)
    };
    else if (kind === 'average') c = dark ? {
      disc: mix(mix(p.deep, SURF_D, .45), mix(p.deep, SURF_D, .25), .6),
      cut: mix(mix(p.base, p.soft, .45), mix(p.soft, p.accent, .4), .4), under: rgba(p.soft, .08),
      dot: rgba(p.soft, .42), rim: mix(p.base, p.soft, .5),
      labFill: mix(p.deep, SURF_D, .15), labStroke: mix(p.base, p.soft, .6),
      ink: mix(p.soft, WHITE, .5), cap: rgba(p.soft, .82),
      hiT: mix(p.accent, WHITE, .45), loT: mix(p.deep, SURF_D, .2), lit: mix(p.accent, WHITE, .3), flare: mix(p.accent, WHITE, .15)
    } : {
      disc: mix(mix(p.soft, WHITE, .82), mix(p.soft, WHITE, .6), .6),
      cut: mix(mix(p.base, p.soft, .08), mix(p.base, p.accent, .34), .4), under: rgba(p.base, .06),
      dot: rgba(p.base, .38), rim: p.base,
      labFill: mix(p.soft, WHITE, .4), labStroke: p.base,
      ink: p.deep, cap: rgba(p.deep, .74),
      hiT: p.accent, loT: p.deep, lit: p.accent, flare: mix(p.accent, p.base, .1)
    };
    else {
      // not rated: neutral tokens only — the album's colour is earned, not given. Its tilt colour is neutral too
      // (the hairline on a cover lightens / deepens a little), so the blank disc still answers the tilt.
      c = {
        disc: on === 'cover' ? 'var(--surface)' : 'none',
        dot: rgba(N, dark ? .82 : .6), slot: N, slotFill: rgba(N, dark ? .14 : .08), ground: rgba(N, dark ? .1 : .06),
        ink: 'var(--on-surface-var)', hair: rgba(N, dark ? .7 : .45), hairS: N,
        hiT: dark ? '#e6e0e9' : '#ffffff', loT: dark ? '#49454f' : '#1d1b20'
      };
    }
    // the tilt tones of one base colour: half way to the accent-ward / deep-ward target, flat
    c.tint = base => ({ hi: mix(base, c.hiT, .55), lo: mix(base, c.loT, .5) });
    return c;
  }

  /* ---------- geometry (CSS px == dp, viewBox = size) ---------- */
  function geom(m, s, kind, on) {
    const r = s / 2, tiny = s < 60, big = s >= 110;
    const rimR = r - (on === 'bar' ? .5 : 1);
    // label outer radius; the unrated mould holds a word, not a score, so it opens a little wider below 88
    const L = s * (tiny ? .345 : big ? .3 : .305) * (kind === 'none' && s >= 60 && s < 88 ? 1.07 : 1);
    // rim → lead-in (plain vinyl) → grooves → run-out → label
    const rimW = kind === 'album' ? Math.max(1.1, s * .019) : kind === 'average' ? Math.max(.9, s * .012) : 0;
    const rim2At = rimW + Math.max(.9, s * .011);                  // album's inner accent hairline (≥ 64 only)
    const leadIn = tiny ? .9 : Math.max(1.4, s * .016);
    const gOut = rimR - (kind === 'album' && !tiny ? rim2At + .4 : rimW) - leadIn;
    const gIn = L + Math.max(1.2, s * .017);
    const band = gOut - gIn;
    const cap = big ? 12 : s >= 88 ? 8 : s >= 64 ? 6 : 3;          // ring budget per size
    const minPitch = tiny ? 1.3 : 1.72;
    const n = m.tracks.length;
    const R = Math.max(1, Math.min(cap, n, Math.floor(band / minPitch)));
    const pitch = band / R;
    const rings = [];
    for (let i = 0; i < R; i++) {
      const a = Math.round(i * n / R), b = Math.round((i + 1) * n / R);
      rings.push({ i, a, b, tracks: m.tracks.slice(a, b), rad: gOut - pitch * (i + .5) });
    }
    const dw = Math.min(pitch * .58, tiny ? 1 : 1.2);              // dot diameter
    const sw = Math.min(pitch * .64, dw + .25);                    // cut line width
    return { r, s, tiny, big, rimR, L, gOut, gIn, pitch, rings, dw, sw, rimW, rim2At };
  }

  const pt = (cx, cy, rad, deg) => [cx + rad * Math.cos(deg * DEG), cy + rad * Math.sin(deg * DEG)];
  function arcPath(c, rad, a0, a1) {           // clockwise from a0 to a1 (deg)
    if (a1 - a0 >= 359.99) {
      const [x0, y0] = pt(c, c, rad, -90), [x1, y1] = pt(c, c, rad, 90);
      return `M${f(x0)} ${f(y0)}A${f(rad)} ${f(rad)} 0 1 1 ${f(x1)} ${f(y1)}A${f(rad)} ${f(rad)} 0 1 1 ${f(x0)} ${f(y0)}`;
    }
    const [x0, y0] = pt(c, c, rad, a0), [x1, y1] = pt(c, c, rad, a1);
    return `M${f(x0)} ${f(y0)}A${f(rad)} ${f(rad)} 0 ${a1 - a0 > 180 ? 1 : 0} 1 ${f(x1)} ${f(y1)}`;
  }
  /** runs of rated tracks inside one ring (each track gets an equal angular share, starting at 12 o'clock) */
  function cutRuns(ring) {
    const len = ring.tracks.length, runs = [];
    let start = -1;
    for (let j = 0; j <= len; j++) {
      const rated = j < len && ring.tracks[j].rating != null;
      if (rated && start < 0) start = j;
      if (!rated && start >= 0) { runs.push([-90 + start / len * 360, -90 + j / len * 360]); start = -1; }
    }
    return runs;
  }
  /** what the award needs to know about a rendered emblem (same order as render: k counts cuts ring by ring) */
  function layout(m, s) {
    const kind = kindOf(m), g = geom(m, s, kind, 'cover'), cuts = [];
    if (kind !== 'none') g.rings.forEach(ring => cutRuns(ring).forEach(() => cuts.push({ k: cuts.length, ring: ring.i })));
    const ratedRings = [...new Set(cuts.map(c => c.ring))].sort((a, b) => a - b);
    return { kind, s, nRings: g.rings.length, cuts, ratedRings, tiny: g.tiny };
  }

  /* tilt colour: a ring line split into 15° flat segments. The hi overlay's segments carry a fixed opacity profile
     max(0, cos θ) (pointing at 3 o'clock), the lo overlay max(0, −cos θ); the pair is turned to the tilt direction
     (--ye-groove-ta) and faded by the tilt's size (--ye-groove-tm). Plain variables only: per-path calc(max()) opacities
     made Chrome drop whole drawings while the award animated. mask: the cut runs a groove ring's tint is limited to. */
  function tintRing(c, cx, rad, width, base, w, maskId) {
    const T = c.tint(base), step = 360 / TINT_SEG;
    let hi = '', lo = '';
    for (let a = -90; a < 270 - .01; a += step) {
      const mid = (a + step / 2) * DEG, k = Math.cos(mid), d = arcPath(cx, rad, a, a + step);
      if (k > .02) hi += `<path d="${d}" stroke="${T.hi}" stroke-opacity="${f(k * w)}"/>`;
      if (k < -.02) lo += `<path d="${d}" stroke="${T.lo}" stroke-opacity="${f(-k * w)}"/>`;
    }
    const g = `<g style="transform-origin:${f(cx)}px ${f(cx)}px;transform:rotate(var(--ye-groove-ta, 0deg));opacity:var(--ye-groove-tm, 0)">${hi}${lo}</g>`;
    return `<g class="ye-groove-tint" fill="none" stroke-width="${f(width)}" stroke-linecap="butt"${maskId ? ` mask="url(#${maskId})"` : ''}>${g}</g>`;
  }

  function render(m, o) {
    const s = o.size, dark = !!o.dark, on = o.on || 'surface', uid = o.uid || ('ygr' + Math.random().toString(36).slice(2, 8));
    const kind = kindOf(m), g = geom(m, s, kind, on), c = colours(m, kind, dark, on), r = g.r;
    const none = kind === 'none';
    let svg = '';
    const defs = [];

    // 1 · ground: opaque on a cover so the silhouette separates from any artwork (flat, no shadow)
    svg += `<circle cx="${f(r)}" cy="${f(r)}" r="${f(g.rimR)}" style="fill:${none ? (c.disc !== 'none' ? c.disc : c.ground) : c.disc}"/>`;

    // 2 · disc space (rotates in the award)
    let spin = '';
    // 2a · dotted pre-cut lattice under every ring (cut lines cover it where a track is rated)
    g.rings.forEach((ring, i) => {
      const C = 2 * Math.PI * ring.rad, gapT = Math.max(g.tiny ? 2.3 : 2.6, g.dw * 2.3), cnt = Math.max(6, Math.round(C / gapT)), gap = C / cnt;
      const da = `stroke-width="${f(g.dw)}" stroke-linecap="round" stroke-dasharray="0 ${f(gap)}" stroke-dashoffset="${f(i % 2 ? gap / 2 : 0)}"`;
      spin += `<circle cx="${f(r)}" cy="${f(r)}" r="${f(ring.rad)}" fill="none" stroke="${none && g.tiny ? rgba(dark ? N_D : N_L, dark ? .95 : .8) : c.dot}" ${da}/>`;
    });
    // 2b · cut (rated) grooves, their tilt colour (outer two rings) and the award's flat "lit" layers
    if (!none) {
      let k = 0, cuts = '', under = '', tints = '', lits = '';
      g.rings.forEach(ring => {
        const runs = cutRuns(ring);
        runs.forEach(([a0, a1]) => {
          const d = arcPath(r, ring.rad, a0, a1);
          under += `<path d="${d}" fill="none" stroke="${c.under}" stroke-width="${f(g.pitch + .05)}"/>`;
          cuts += `<path class="ye-groove-cut" data-k="${k}" data-ring="${ring.i}" d="${d}" pathLength="1" fill="none" stroke="${c.cut}" stroke-width="${f(g.sw)}" stroke-linecap="butt"/>`;
          // award: the cut changes to the lit colour (same width, flat; no halo)
          if (!g.tiny) lits += `<path class="ye-groove-glow" data-k="${k}" data-ring="${ring.i}" d="${d}" opacity="0" fill="none" stroke="${c.lit}" stroke-width="${f(g.sw)}" stroke-linecap="butt"/>`;
          k++;
        });
        if (runs.length && ring.i < 2) {
          const mid = `${uid}-t${ring.i}`;
          defs.push(`<mask id="${mid}" maskUnits="userSpaceOnUse" x="0" y="0" width="${f(s)}" height="${f(s)}">${runs.map(([a0, a1]) => `<path d="${arcPath(r, ring.rad, a0, a1)}" fill="none" stroke="#fff" stroke-width="${f(g.sw + .3)}"/>`).join('')}</mask>`);
          tints += tintRing(c, r, ring.rad, g.sw, c.cut, ring.i ? .55 : .85, mid);
        }
      });
      // every ring, cut or not, can light as a whole (the 10.0 moment lights them all in sequence): a flat ring
      if (!g.tiny) g.rings.forEach(ring => { lits += `<circle class="ye-groove-rglow" data-ring="${ring.i}" cx="${f(r)}" cy="${f(r)}" r="${f(ring.rad)}" opacity="0" fill="none" stroke="${c.lit}" stroke-width="${f(g.sw)}"/>`; });
      spin = under + spin + cuts + tints + lits;
    }
    svg += `<g class="ye-groove-spin" style="transform-origin:${f(r)}px ${f(r)}px">${spin}</g>`;

    // 3 · rim: album = accent double rim, average = single palette rim, unrated = hairline only on a cover.
    //     The rim is the outermost ring line, so it answers the tilt most (w 1).
    if (kind === 'album') {
      svg += `<circle cx="${f(r)}" cy="${f(r)}" r="${f(g.rimR - g.rimW / 2)}" fill="none" stroke="${c.rim}" stroke-width="${f(g.rimW)}"/>`;
      svg += tintRing(c, r, g.rimR - g.rimW / 2, g.rimW, c.rim, 1);
      if (!g.tiny) svg += `<circle cx="${f(r)}" cy="${f(r)}" r="${f(g.rimR - g.rim2At)}" fill="none" stroke="${c.rim2}" stroke-width="${f(Math.max(.75, s * .008))}"/>`;
    } else if (kind === 'average') {
      svg += `<circle cx="${f(r)}" cy="${f(r)}" r="${f(g.rimR - g.rimW / 2)}" fill="none" stroke="${c.rim}" stroke-width="${f(g.rimW)}"/>`;
      svg += tintRing(c, r, g.rimR - g.rimW / 2, g.rimW, c.rim, 1);
    } else if (on === 'cover') {
      svg += `<circle cx="${f(r)}" cy="${f(r)}" r="${f(g.rimR - .4)}" fill="none" stroke="${c.hair}" stroke-width=".8"/>`;
      if (!g.tiny) svg += tintRing(c, r, g.rimR - .4, .8, c.hairS, .6);
    }
    // 3b · the 10.0 moment: the rim pulses in flat accent, one thin ring leaves the rim (award only, hidden at rest)
    if (!none && !g.tiny) {
      const fw = Math.max(1.6, s * .026);
      const fr = g.rimR - fw / 2;
      svg += `<circle class="ye-groove-flare" cx="${f(r)}" cy="${f(r)}" r="${f(fr)}" opacity="0" fill="none" stroke="${c.flare}" stroke-width="${f(fw)}"/>` +
        `<circle class="ye-groove-burst" cx="${f(r)}" cy="${f(r)}" r="${f(g.rimR)}" fill="none" stroke="${c.flare}" stroke-width="${f(Math.max(1, s * .014))}" opacity="0" style="transform-origin:${f(r)}px ${f(r)}px"/>`;
    }

    const disc = `<svg class="ye-groove-disc" viewBox="0 0 ${f(s)} ${f(s)}" aria-hidden="true">${defs.length ? `<defs>${defs.join('')}</defs>` : ''}${svg}</svg>`;

    // 4 · centre label (flat fills)
    const L = g.L, D = 2 * L;
    let shape = '';
    if (kind === 'album') {
      const cp = YS.cookiePath(L, L, L, 12, g.tiny ? .92 : .9, 240);
      shape = `<path d="${cp}" fill="${c.lab}"/>` +
        (g.big ? `<path d="${YS.cookiePath(L, L, L * .94, 12, .9, 240)}" fill="none" stroke="${c.labRing}" stroke-width="${f(s * .007)}" stroke-opacity=".9"/>` : '');
    } else if (kind === 'average') {
      const w = Math.max(1.2, s * .017);
      shape = `<circle cx="${f(L)}" cy="${f(L)}" r="${f(L - w / 2)}" fill="${c.labFill}" stroke="${c.labStroke}" stroke-width="${f(w)}"/>`;
    } else {
      const w = g.tiny ? 1.4 : Math.max(1.1, s * .013), cp = YS.cookiePath(L, L, L - w / 2, 12, .9, 240);
      const per = 2 * Math.PI * L * .95 / 24;      // 24 dashes around the mould → two per lobe
      shape = `<path d="${cp}" fill="${c.slotFill}" stroke="${c.slot}" stroke-width="${f(w)}" stroke-dasharray="${f(per * .55)} ${f(per * .45)}" stroke-linecap="round"/>` +
        (s >= 64 && on !== 'bar' ? `<path class="ye-groove-ripple" d="${cp}" fill="none" stroke="${c.slot}" stroke-width="${f(w * .7)}"/>` : '') +
        (s < 64 ? `<circle cx="${f(L)}" cy="${f(L)}" r="${f(Math.max(1.6, s * .045))}" fill="${c.slot}" fill-opacity=".85"/>` : '');   // spindle hole: an empty pressing
    }
    let txt = '';
    if (!none) {
      const t = scoreTxt(m), four = t.length >= 4;
      const F = g.big ? s * .27 : s >= 64 ? Math.max(s * .29, 21.5) : Math.max(15.5, s * .36);
      const showCap = s >= 88;
      const capF = Math.max(10, s * (g.big ? .1 : .105));
      const wght = kind === 'album' ? 690 : 640;
      txt = `<span class="ye-groove-score" style="font-size:${f(F)}px;font-weight:${wght};color:${c.ink};font-variation-settings:'wdth' ${four ? 76 : 92},'ROND' 50;${showCap ? `margin-top:${f(F * .06)}px` : `transform:translateY(${f(F * .02)}px)`}">${t}</span>` +
        (showCap ? `<span class="ye-groove-cap" style="font-size:${f(capF)}px;margin-top:${f(s * .012)}px;color:${c.cap}">${kind === 'album' ? 'Album' : 'Avg.'}</span>` : '');
    } else if (s >= 64) {
      txt = `<span class="ye-groove-cap" style="font-size:${f(s < 88 ? 9.5 : Math.max(10, s * .125))}px;color:${c.ink};font-variation-settings:'wdth' ${s < 88 ? 76 : 92}">Unrated</span>`;
    }
    const label = `<div class="ye-groove-label" style="left:${f(r - L)}px;top:${f(r - L)}px;width:${f(D)}px;height:${f(D)}px"><svg viewBox="0 0 ${f(D)} ${f(D)}" aria-hidden="true">${shape}</svg>${txt}</div>`;

    const aria = none ? `${m.album}: not rated` : `${m.album}: ${kind === 'album' ? 'album rating' : 'track average'} ${scoreTxt(m)}`;
    return `<div class="ye-groove-root" data-kind="${kind}" data-size="${f(s)}" data-tier="${tierOf(m)}" style="width:${f(s)}px;height:${f(s)}px" role="img" aria-label="${aria.replace(/"/g, '&quot;')}">${disc}${label}</div>`;
  }

  /* ---------- tilt light ---------- */
  /** x, y in −1 … 1: the direction the device leans toward, in screen axes (+x right, +y down). Capped to the unit disc.
   *  v4 draws only from tx / ty (the outer ring lines' flat tilt colours, see tintRing). rot and li are still written
   *  (an angle swung by up to ~±32° around PHI, and an intensity 0.6 … 1.0) so older readers of these variables keep working. */
  function tiltVars(x, y) {
    x = +x || 0; y = +y || 0;
    const mg = Math.hypot(x, y); if (mg > 1) { x /= mg; y /= mg; }
    const ux = Math.cos(PHI * DEG), uy = Math.sin(PHI * DEG), A = .53;   // asin(.53) ≈ 32° at most
    let d = Math.atan2(uy + A * y, ux + A * x) / DEG - PHI;
    d = ((d + 540) % 360) - 180;
    return { rot: d, tx: x, ty: y, li: LI_REST + .2 * (x * ux + y * uy), ta: Math.atan2(y, x) / DEG, tm: Math.min(1, Math.hypot(x, y)) };
  }
  function setTilt(el, x, y) {
    if (!el || !el.style) return;
    const v = tiltVars(x, y);
    el.style.setProperty('--ye-groove-rot', v.rot.toFixed(2) + 'deg');
    el.style.setProperty('--ye-groove-tx', v.tx.toFixed(4));
    el.style.setProperty('--ye-groove-ty', v.ty.toFixed(4));
    el.style.setProperty('--ye-groove-li', v.li.toFixed(4));
    el.style.setProperty('--ye-groove-ta', v.ta.toFixed(2) + 'deg');   // v4: the tilt colour's direction and size
    el.style.setProperty('--ye-groove-tm', v.tm.toFixed(4));
  }
  /** One controller for the tilt: pointer (hover / drag) > device orientation > auto sway > rest, smoothed by one
   *  spring (dampingRatio .9, stiffness 90). Runs only while `target` is on screen; off under reduced motion. */
  function tiltDriver(target, opt = {}) {
    const S = { x: 0, y: 0, vx: 0, vy: 0, src: 'rest', auto: !!opt.auto, ptr: null, sensor: null, sensorAt: -1e9, base: null,
      sensorOk: false, alive: true, visible: true, raf: 0, last: 0, t0: performance.now(), reduced: reduced() };
    const K = 90, C = 2 * .9 * Math.sqrt(K);
    const offs = [];
    const on = (node, ev, fn, o) => { try { node.addEventListener(ev, fn, o); offs.push(() => node.removeEventListener(ev, fn, o)); } catch (e) { /* ignore */ } };
    const emit = () => { setTilt(target, S.x, S.y); if (opt.onUpdate) { try { opt.onUpdate(S); } catch (e) { /* ignore */ } } };
    const api = { state: S, reduced: S.reduced, setAuto(b) { S.auto = !!b; kick(); }, setPointer(p) { S.ptr = p ? { x: +p.x || 0, y: +p.y || 0 } : null; kick(); }, requestSensor, destroy };
    if (S.reduced) { emit(); return api; }

    /* sensor: relative tilt around a slow baseline (holding posture becomes neutral), ±18° → ±1 */
    let sensorOn = false;
    const onOri = e => {
      if (e.beta == null || e.gamma == null) return;
      const now = performance.now(), b = e.beta, g = e.gamma;
      if (!S.base || now - S.sensorAt > 1500) S.base = { b, g };
      const dt = Math.min(.25, Math.max(0, (now - S.sensorAt) / 1000));
      const a = 1 - Math.exp(-dt / 1.6);                        // baseline low-pass, τ = 1.6 s
      S.base.b += (b - S.base.b) * a; S.base.g += (g - S.base.g) * a;
      let x = -(g - S.base.g) / 18, y = -(b - S.base.b) / 18;   // the light leans away from gravity
      let ang = 0;
      try { ang = ((screen.orientation && screen.orientation.angle) || window.orientation || 0) * DEG; } catch (e2) { ang = 0; }
      const rx = x * Math.cos(ang) - y * Math.sin(ang), ry = x * Math.sin(ang) + y * Math.cos(ang);
      S.sensor = { x: rx, y: ry }; S.sensorAt = now; S.sensorOk = true; kick();
    };
    const needsPermission = () => typeof window.DeviceOrientationEvent !== 'undefined' && typeof DeviceOrientationEvent.requestPermission === 'function';
    const sensorAvailable = () => typeof window.DeviceOrientationEvent !== 'undefined';
    let permitted = sensorAvailable() && !needsPermission();
    function sensorListen(want) {
      if (want && permitted && !sensorOn) { sensorOn = true; window.addEventListener('deviceorientation', onOri); }
      if (!want && sensorOn) { sensorOn = false; window.removeEventListener('deviceorientation', onOri); }
    }
    function requestSensor() {
      if (!sensorAvailable()) return Promise.resolve(false);
      if (!needsPermission()) { permitted = true; sensorListen(S.visible); return Promise.resolve(true); }
      try {
        return Promise.resolve(DeviceOrientationEvent.requestPermission()).then(r => { permitted = r === 'granted'; sensorListen(S.visible); return permitted; }, () => false);
      } catch (e) { return Promise.resolve(false); }
    }
    api.needsPermission = needsPermission();
    api.sensorAvailable = sensorAvailable();

    /* pointer: hover (mouse) and drag (any pointer, captured) map the pointer offset from the frame centre */
    const rel = (e, frame) => { const r = frame.getBoundingClientRect(); return { x: (e.clientX - (r.left + r.width / 2)) / Math.max(1, r.width / 2), y: (e.clientY - (r.top + r.height / 2)) / Math.max(1, r.height / 2) }; };
    (opt.hover || []).forEach(node => {
      on(node, 'pointermove', e => { if (e.pointerType !== 'mouse' || S.dragging) return; S.ptr = rel(e, opt.frame || node); kick(); });
      on(node, 'pointerleave', e => { if (e.pointerType !== 'mouse' || S.dragging) return; S.ptr = null; kick(); });
    });
    (opt.drag || []).forEach(node => {
      on(node, 'pointerdown', e => { try { node.setPointerCapture(e.pointerId); } catch (e2) { /* ignore */ } S.dragging = true; S.ptr = rel(e, node); kick(); });
      on(node, 'pointermove', e => { if (!S.dragging) return; S.ptr = rel(e, node); kick(); });
      const up = () => { if (!S.dragging) return; S.dragging = false; S.ptr = null; kick(); };
      on(node, 'pointerup', up); on(node, 'pointercancel', up); on(node, 'lostpointercapture', up);
    });

    /* visibility: sample and animate only while the target is on screen */
    let io = null;
    if ('IntersectionObserver' in window) {
      io = new IntersectionObserver(es => { S.visible = es.some(x => x.isIntersecting); sensorListen(S.visible); if (S.visible) kick(); }, { threshold: 0 });
      io.observe(target);
    } else sensorListen(true);

    const unit = p => { const mg = Math.hypot(p.x, p.y); return mg > 1 ? { x: p.x / mg, y: p.y / mg } : p; };
    function goal(now) {
      if (S.ptr) { S.src = 'pointer'; return unit(S.ptr); }
      if (S.sensor && now - S.sensorAt < 700) { S.src = 'sensor'; return unit(S.sensor); }
      if (S.auto) { S.src = 'auto'; const t = (now - S.t0) / 1000; return { x: .72 * Math.sin(t * 2 * Math.PI / 5.6), y: .42 * Math.sin(t * 2 * Math.PI / 8.3 + 1.1) }; }
      S.src = 'rest'; return { x: 0, y: 0 };
    }
    function step(now) {
      S.raf = 0;
      if (!S.alive || !S.visible) { S.last = 0; return; }
      const dt = S.last ? Math.min(.034, Math.max(.001, (now - S.last) / 1000)) : .016; S.last = now;
      const g = goal(now);
      S.vx += (K * (g.x - S.x) - C * S.vx) * dt; S.x += S.vx * dt;
      S.vy += (K * (g.y - S.y) - C * S.vy) * dt; S.y += S.vy * dt;
      if (S.src === 'rest' && Math.abs(S.x) + Math.abs(S.y) + Math.abs(S.vx) + Math.abs(S.vy) < 2e-3) { S.x = S.y = S.vx = S.vy = 0; S.last = 0; emit(); return; }
      emit();
      S.raf = requestAnimationFrame(step);
    }
    function kick() { if (!S.raf && S.alive && S.visible) S.raf = requestAnimationFrame(step); }
    function destroy() { S.alive = false; cancelAnimationFrame(S.raf); S.raf = 0; sensorListen(false); offs.forEach(fn => fn()); if (io) io.disconnect(); }
    emit(); kick();
    return api;
  }

  /* ---------- springs (Compose spring(): dampingRatio, stiffness, mass 1; optional initial velocity) ---------- */
  function spring(z, k, t, x0, x1, v0 = 0) {
    if (t <= 0) return x0;
    const w0 = Math.sqrt(k), d0 = x0 - x1;
    let d;
    if (z < 1) { const wd = w0 * Math.sqrt(1 - z * z); d = Math.exp(-z * w0 * t) * (d0 * Math.cos(wd * t) + ((v0 + z * w0 * d0) / wd) * Math.sin(wd * t)); }
    else d = Math.exp(-w0 * t) * (d0 + (v0 + w0 * d0) * t);
    return x1 + d;
  }
  const cl = v => Math.max(0, Math.min(1, v));
  const gauss = (t, c, w) => Math.exp(-Math.pow((t - c) / w, 2));
  /** an effects-spring flash: a stiff critically-damped rise, then an exponential fade (no linear tween) */
  const pulse = (t, at, hold) => t < at ? 0 : cl(spring(1, 2400, t - at, 0, 1)) * Math.exp(-Math.max(0, t - at - .04) / hold);
  const frames = (dur, fn) => { const out = [], N = Math.round(dur * 60); for (let i = 0; i <= N; i++) out.push(fn(i / N * dur, i === N)); return out; };
  const scan = (T, fn) => { const out = []; for (let t = 0; t <= T + 1e-9; t += .001) out.push([t, fn(t)]); return out; };
  const argmax = (a, b, fn) => { let best = -Infinity, bt = a; for (let t = a; t <= b; t += .001) { const v = fn(t); if (v > best) { best = v; bt = t; } } return bt; };
  const argmin = (a, b, fn) => argmax(a, b, t => -fn(t));
  const firstT = (a, b, pred) => { for (let t = a; t <= b; t += .001) if (pred(t)) return t; return null; };

  /* ---------- the four award tiers ---------- */
  const TIERS = {
    1: { range: '< 6', name: '轻落', en: 'Settle', line: '不转。盘面轻轻落定，刻纹一次刻好，标签贴稳，一拍很轻的触感。' },
    2: { range: '6 – 8', name: '一转', en: 'One turn', line: '从静止转起来近一圈，回摆到顶时刻纹一起换成亮色一下，标签轻压。两拍。' },
    3: { range: '8 – 10', name: '逐圈', en: 'Ring by ring', line: '被拨动整整一圈，标签在回摆顶点压下，然后评过分的圈从外到内逐圈换成亮色，再退回原色。' },
    4: { range: '10.0', name: '满分', en: 'Full marks', line: '拨两圈；所有圈从外到内依次换成亮色；标签重重压下；最后外圈换成 accent 实色跳一下，放出一道细环。' }
  };
  function tierOf(m) {
    if (!m || kindOf(m) === 'none' || m.score == null) return 0;
    const s = Math.round(m.score * 10) / 10;
    return s >= 10 ? 4 : s >= 8 ? 3 : s >= 6 ? 2 : 1;
  }
  /* primitive table: Android VibrationEffect.Composition id, API level, YoinHaptics fallback, rough on-time for navigator.vibrate */
  const PRIM = {
    TICK: { api: 30, yoin: 'performTick', ms: 10 }, LOW_TICK: { api: 31, yoin: 'performLightTick', ms: 14 },
    CLICK: { api: 30, yoin: 'performClick', ms: 22 }, THUD: { api: 31, yoin: 'performConfirm', ms: 46 },
    SPIN: { api: 31, yoin: 'performTick', ms: 64 }, QUICK_RISE: { api: 30, yoin: null, ms: 56 },
    SLOW_RISE: { api: 30, yoin: null, ms: 140 }, QUICK_FALL: { api: 30, yoin: null, ms: 36 }
  };

  /** The choreography of one tier as pure functions of t (s). inf = layout(m, size). */
  function script(tier, inf, kind) {
    const A = kind === 'album' ? 1 : .7;            // the user's own album score stamps harder than an average
    const S = { tier, T: 1, beats: [] };
    const beat = (at, p, scale, what, yoin) => S.beats.push({ atMs: Math.max(0, Math.round(at * 1000)), primitive: 'PRIMITIVE_' + p, p, scale: +scale.toFixed(2), yoin: yoin === undefined ? PRIM[p].yoin : yoin, what });
    const none = () => 0;
    // v4: no light channels (sheen / bright / light are gone); the lit-colour channels are cutGlow, ringGlow, flare, burst
    S.cutGlow = () => none; S.ringGlow = () => none; S.flare = none; S.burst = null;
    if (tier === 1) {
      // quiet settle: no spin. The disc eases 16° into place, every cut appears together, the label sits down.
      S.T = .62;
      S.disc = t => spring(1, 90, t, -16, 0);
      S.label = t => spring(.9, 300, t, 1 + .06 * A, 1);
      S.cut = () => t => cl(spring(1, 140, t, 0, 1));
      const settle = firstT(.05, S.T, t => S.label(t) <= 1) || .36;
      beat(settle, 'LOW_TICK', .5, '标签贴稳（落定那一帧）');
    } else if (tier === 2) {
      // one spin-up from rest; at the overshoot every cut turns the lit colour once, the label nods
      S.T = 1.0;
      S.disc = t => spring(.74, 70, t, -330, 0);
      const cross = firstT(0, S.T, t => S.disc(t) >= 0) || .43;
      const peak = argmax(cross, S.T, S.disc);
      S.label = t => t < peak ? 1 : spring(.5, 420, t - peak, 1, 1, -1.1 * A);
      S.cut = j => t => cl(spring(1, 150, t - (.05 + j * .05), 0, 1));
      S.cutGlow = () => t => .85 * gauss(t, peak + .01, .085);
      const fastest = argmax(0, cross, t => S.disc(t + .001) - S.disc(t));
      beat(fastest, 'TICK', .5, '从静止转起来，转到最快');
      beat(peak, 'CLICK', .75, '回摆到顶：刻纹换成亮色一下，标签轻压');
    } else if (tier === 3) {
      // a full flick; the label stamps at the overshoot; then the rated rings turn the lit colour one by one, outer first
      S.T = 1.3;
      S.disc = t => spring(.8, 42, t, -360, 0, 1300);
      const LAB = .5, lift = t => spring(1, 220, t, 1, 1 + .12 * A);
      S.label = t => t < LAB ? lift(t) : spring(.55, 520, t - LAB, lift(LAB), 1);
      const contact = argmin(LAB, LAB + .3, S.label);
      S.cut = j => t => cl(spring(1, 150, t - (.04 + j * .06), 0, 1));
      const at = j => .72 + j * .08;
      S.cutGlow = (j) => t => .95 * pulse(t, at(j), .2);
      beat(0, 'SPIN', .5, '拨动：盘面转起一整圈');
      beat(contact, 'CLICK', 1, '标签压到最低（回摆顶点）', 'performConfirm');
      const n = inf.ratedRings.length, stride = Math.max(1, Math.ceil(n / 4));
      for (let j = 0; j < n; j += stride) beat(argmax(at(j), at(j) + .2, t => pulse(t, at(j), .2)), 'LOW_TICK', .35, `第 ${j + 1} 道评过分的圈换成亮色`);
    } else {
      // the full moment: two turns; every ring lights outer → inner; a heavy stamp; the rim pulses in flat accent and lets a ring go
      S.T = 1.6;
      S.disc = t => spring(.82, 44, t, -720, 0, 2000);
      const LAB = .74, lift = t => spring(1, 200, t, 1, 1 + .18 * A);
      S.label = t => t < LAB ? lift(t) : spring(.5, 600, t - LAB, lift(LAB), 1);
      const contact = argmin(LAB, LAB + .3, S.label);
      S.cut = j => t => cl(spring(1, 150, t - (.04 + j * .045), 0, 1));
      const TF = 1.02;
      const at = i => .28 + i * .055;
      S.ringGlow = i => t => .9 * pulse(t, at(i), .2);
      S.cutGlow = () => t => .7 * pulse(t, TF, .26);
      S.flare = t => pulse(t, TF - .04, .3);
      S.burst = t => ({ s: 1 + .2 * cl(spring(1, 90, t - (TF - .04), 0, 1)), o: .9 * pulse(t, TF - .04, .2) });
      const flarePeak = argmax(TF - .04, TF + .2, S.flare);
      beat(0, 'SPIN', .8, '拨动：两圈');
      const n = inf.nRings, stride = Math.max(1, Math.ceil(n / 8));
      for (let i = 0; i < n; i += stride) beat(argmax(at(i), at(i) + .2, t => pulse(t, at(i), .2)), 'TICK', .45, `第 ${i + 1} 圈换成亮色`, i === 0 ? 'performLightTick' : null);
      beat(contact - .01, 'THUD', 1, '标签重重压下', 'performConfirm');
      beat(TF - .04, 'QUICK_RISE', .7, '外圈开始换成 accent', null);
      beat(flarePeak, 'CLICK', 1, '外圈 accent 最满，细环放出', 'performClick');
    }
    // two beats closer than 45 ms blur into one buzz: keep the stronger one
    S.beats.sort((a, b) => a.atMs - b.atMs);
    const kept = [];
    S.beats.forEach(b => { const last = kept[kept.length - 1]; if (last && b.atMs - last.atMs < 45) { if (b.scale > last.scale) kept[kept.length - 1] = b; } else kept.push(b); });
    S.beats = kept;
    return S;
  }

  /** The award's haptic beats for memory m (at o.size, default 96): [{atMs, primitive, scale, yoin, what}].
   *  Under reduced motion (o.reduced) only the tier's strongest beat survives, at 0 ms. */
  function hapticPlan(m, o = {}) {
    const tier = tierOf(m);
    if (!tier) return [];
    const S = script(tier, layout(m, o.size || 96), kindOf(m));
    if (!o.reduced) return S.beats.map(b => Object.assign({ tier }, b));
    const top = S.beats.reduce((a, b) => (b.scale > a.scale || (b.scale === a.scale && b.p === 'THUD') ? b : a), S.beats[0]);
    return [Object.assign({ tier }, top, { atMs: 0 })];
  }
  hapticPlan.duration = m => { const t = tierOf(m); return t ? Math.round(script(t, layout(m, 96), kindOf(m)).T * 1000) : 0; };

  /** navigator.vibrate approximation: [on, off, on, …]; the browser has no amplitude, so strength → on-time */
  function vibratePattern(plan) {
    const arr = []; let cursor = 0;
    plan.forEach(b => {
      const pr = PRIM[b.p] || PRIM.TICK, onMs = Math.max(6, Math.round(pr.ms * (.55 + .45 * b.scale)));
      const pause = Math.max(0, b.atMs - cursor);
      if (!arr.length) { if (pause > 0) arr.push(0, pause); } else arr.push(pause);
      arr.push(onMs); cursor += pause + onMs;
    });
    return arr;
  }
  function canVibrate() {
    try {
      if (typeof navigator === 'undefined' || typeof navigator.vibrate !== 'function') return false;
      if (navigator.userActivation && !navigator.userActivation.hasBeenActive) return false;   // would only log an intervention
      return true;
    } catch (e) { return false; }
  }
  /** Plays a plan: one navigator.vibrate call (if it exists and o.vibrate !== false) + o.onBeat(beat, i) at each beat. */
  function playHaptics(plan, o = {}) {
    const timers = []; let buzzing = false;
    if (o.vibrate !== false && plan.length && canVibrate()) { try { buzzing = !!navigator.vibrate(vibratePattern(plan)); } catch (e) { buzzing = false; } }
    if (o.onBeat) plan.forEach((b, i) => timers.push(setTimeout(() => { try { o.onBeat(b, i); } catch (e) { /* ignore */ } }, b.atMs)));
    return { vibrated: buzzing, cancel() { timers.forEach(clearTimeout); if (buzzing) { try { navigator.vibrate(0); } catch (e) { /* ignore */ } } } };
  }

  /* ---------- visual haptic timeline (a strip the pages can drop in; colours inherit currentColor) ---------- */
  function glyph(b) {
    const k = .72 + .45 * b.scale, P = b.p;
    const box = (w, h, inner) => `<svg width="${f(w)}" height="${f(h)}" viewBox="${f(-w / 2)} ${f(-h / 2)} ${f(w)} ${f(h)}" style="left:${f(-w / 2)}px;top:${f(-h / 2)}px" aria-hidden="true">${inner}</svg>`;
    if (P === 'TICK') return box(8 * k, 8 * k, `<circle r="${f(2.6 * k)}" fill="currentColor"/>`);
    if (P === 'LOW_TICK') return box(9 * k, 9 * k, `<circle r="${f(3 * k)}" fill="var(--bg, #fff)" stroke="currentColor" stroke-width="1.5"/>`);
    if (P === 'CLICK') return box(12 * k, 12 * k, `<circle r="${f(4.6 * k)}" fill="currentColor"/>`);
    if (P === 'THUD') return box(13 * k, 13 * k, `<rect x="${f(-5.2 * k)}" y="${f(-5.2 * k)}" width="${f(10.4 * k)}" height="${f(10.4 * k)}" rx="${f(2.2 * k)}" fill="currentColor"/>`);
    if (P === 'SPIN') return box(15 * k, 15 * k, `<path d="M${f(4.6 * k)} ${f(-2.2 * k)}A${f(5 * k)} ${f(5 * k)} 0 1 0 ${f(4.2 * k)} ${f(2.8 * k)}" fill="none" stroke="currentColor" stroke-width="1.7" stroke-linecap="round"/><path d="M${f(5.8 * k)} ${f(-5 * k)}L${f(5 * k)} ${f(-1.4 * k)}L${f(1.6 * k)} ${f(-2.6 * k)}" fill="none" stroke="currentColor" stroke-width="1.5" stroke-linecap="round" stroke-linejoin="round"/>`);
    if (P === 'QUICK_RISE') return box(15 * k, 12 * k, `<path d="M${f(-6.5 * k)} ${f(4.5 * k)}L${f(6.5 * k)} ${f(-4.5 * k)}L${f(6.5 * k)} ${f(4.5 * k)}Z" fill="currentColor"/>`);
    if (P === 'SLOW_RISE') return box(22 * k, 12 * k, `<path d="M${f(-10 * k)} ${f(4.5 * k)}L${f(10 * k)} ${f(-4.5 * k)}L${f(10 * k)} ${f(4.5 * k)}Z" fill="currentColor"/>`);
    return box(15 * k, 12 * k, `<path d="M${f(-6.5 * k)} ${f(-4.5 * k)}L${f(6.5 * k)} ${f(4.5 * k)}L${f(-6.5 * k)} ${f(4.5 * k)}Z" fill="currentColor"/>`);
  }
  const hx = {
    glyph,
    /** strip markup. opt.spanMs = axis length (default 1600, the same for every tier so lengths compare); opt.T = this award's length */
    html(plan, opt = {}) {
      const span = opt.spanMs || 1600, T = opt.T || 0;
      const pc = ms => (Math.min(span, ms) / span * 100).toFixed(2) + '%';
      const ticks = (opt.ticks || [0, 500, 1000, 1500]).filter(x => x <= span);
      return `<div class="ye-groove-hx" data-span="${span}" role="img" aria-label="${plan.length} haptic beats">` +
        `<div class="ye-groove-hx-axis"></div>${T ? `<div class="ye-groove-hx-span" style="width:${pc(T)}"></div>` : ''}` +
        ticks.map(x => `<span class="ye-groove-hx-t" style="left:${pc(x)}">${x ? (x / 1000).toFixed(1) + ' s' : '0'}</span>`).join('') +
        plan.map((b, i) => `<span class="ye-groove-hx-b" data-i="${i}" style="left:${pc(b.atMs)}" title="${b.atMs} ms · ${b.primitive} × ${b.scale} · ${b.what}">${glyph(b)}</span>`).join('') +
        `<div class="ye-groove-hx-head"><i class="ye-groove-hx-dot"></i></div></div>`;
    },
    /** run the playhead and flash each beat on time; returns { cancel } */
    play(strip, plan) {
      const el = strip && (strip.classList.contains('ye-groove-hx') ? strip : strip.querySelector('.ye-groove-hx'));
      if (!el) return { cancel() {} };
      if (el._hx) el._hx.cancel();
      const span = +el.dataset.span || 1600, head = el.querySelector('.ye-groove-hx-head'), dot = head && head.querySelector('.ye-groove-hx-dot');
      const timers = [], anims = [];
      const end = plan.length ? plan[plan.length - 1].atMs + 260 : 400;
      const pop = YS.springKeyframes(.42, 520, 36, .6);
      if (head) {
        head.style.opacity = '1';
        anims.push(head.animate([{ left: '0%' }, { left: (Math.min(span, end) / span * 100).toFixed(2) + '%' }], { duration: Math.min(span, end), easing: 'linear', fill: 'forwards' }));
      }
      plan.forEach((b, i) => timers.push(setTimeout(() => {
        const n = el.querySelector(`.ye-groove-hx-b[data-i="${i}"]`);
        if (n) {
          n.classList.add('on'); const g = n.firstElementChild;
          if (g) anims.push(g.animate(pop.map(v => ({ transform: `scale(${(2.1 - 1.1 * v).toFixed(3)})` })), { duration: 600, easing: 'linear' }));
          timers.push(setTimeout(() => n.classList.remove('on'), 420));
        }
        if (dot) anims.push(dot.animate([{ opacity: 1, transform: 'scale(1.6)' }, { opacity: 0, transform: 'scale(.6)' }], { duration: 260, easing: 'cubic-bezier(.2,.8,.2,1)' }));
      }, b.atMs)));
      timers.push(setTimeout(() => { if (head) head.style.opacity = '0'; }, end + 200));
      const h = { cancel() { timers.forEach(clearTimeout); anims.forEach(a => { try { a.cancel(); } catch (e) { /* ignore */ } }); if (head) head.style.opacity = '0'; el.querySelectorAll('.ye-groove-hx-b.on').forEach(n => n.classList.remove('on')); } };
      el._hx = h;
      return h;
    }
  };

  /* ---------- the award ---------- */
  const rootOf = el => !el ? null : (el.classList && el.classList.contains('ye-groove-root')) ? el : (el.querySelector ? el.querySelector('.ye-groove-root') : null);

  /** Play the tier's award on a rendered emblem. o.haptics: also buzz (navigator.vibrate, feature-detected) and call
   *  o.onBeat(beat, i) on each beat. Resolves when settled; resolves at once under prefers-reduced-motion or unrated. */
  function award(el, m, o = {}) {
    const root = rootOf(el);
    if (!root) return Promise.resolve();
    if (root._yeAward) { root._yeAward.cancel(); root._yeAward = null; }
    const tier = tierOf(m);
    if (!tier) return Promise.resolve();                       // nothing earned yet: no ceremony
    const size = +root.dataset.size || o.size || 96, kind = root.dataset.kind || kindOf(m);
    const RM = reduced();
    const hap = o.haptics ? playHaptics(hapticPlan(m, { size, reduced: RM }), o) : null;
    if (RM) { root._yeAward = { cancel() { if (hap) hap.cancel(); } }; return Promise.resolve(); }
    const inf = layout(m, size), S = script(tier, inf, kind);
    const q = s => root.querySelector(s), qa = s => [...root.querySelectorAll(s)];
    const spinG = q('.ye-groove-spin');
    const label = q('.ye-groove-label'), shape = label && label.querySelector('svg');
    const anims = [];
    // v4: the old shadow filter used to give each drawing its own layer; without it Chrome could drop whole SVGs
    // while many awards ran at once. The two SVGs are promoted for the award's duration only.
    const layers = [q('svg.ye-groove-disc'), shape].filter(Boolean);
    layers.forEach(n => { n.style.willChange = 'transform'; });
    const unlayer = () => layers.forEach(n => { n.style.willChange = ''; });
    const go = (node, fn) => { if (node) anims.push(node.animate(frames(S.T, fn), { duration: S.T * 1000, easing: 'linear' })); };
    const ringOrder = r => Math.max(0, inf.ratedRings.indexOf(r));

    go(spinG, (t, end) => ({ transform: `rotate(${end ? 0 : S.disc(t).toFixed(2)}deg)` }));
    if (kind === 'album') go(shape, (t, end) => ({ transform: `rotate(${end ? 0 : S.disc(t).toFixed(2)}deg)` }));
    go(label, (t, end) => ({ transform: `scale(${end ? 1 : S.label(t).toFixed(4)})` }));
    qa('.ye-groove-cut').forEach(p => { const fn = S.cut(ringOrder(+p.dataset.ring)); go(p, (t, end) => ({ strokeDasharray: '1 1', strokeDashoffset: end ? 0 : (1 - fn(t)).toFixed(4) })); });
    qa('.ye-groove-glow').forEach(p => { const fn = S.cutGlow(ringOrder(+p.dataset.ring)); if (fn(.5) || fn(S.T * .6) || fn(.85) || fn(1.05)) go(p, (t, end) => ({ opacity: end ? 0 : cl(fn(t)).toFixed(3) })); });
    if (tier === 4) qa('.ye-groove-rglow').forEach(p => { const fn = S.ringGlow(+p.dataset.ring); go(p, (t, end) => ({ opacity: end ? 0 : cl(fn(t)).toFixed(3) })); });
    if (tier === 4) {
      go(q('.ye-groove-flare'), (t, end) => ({ opacity: end ? 0 : cl(S.flare(t)).toFixed(3) }));
      go(q('.ye-groove-burst'), (t, end) => { const b = S.burst(t); return { opacity: end ? 0 : cl(b.o).toFixed(3), transform: `scale(${end ? 1 : b.s.toFixed(4)})` }; });
    }
    const handle = { anims, cancel() { anims.forEach(a => { try { a.cancel(); } catch (e) { /* ignore */ } }); if (hap) hap.cancel(); unlayer(); } };
    root._yeAward = handle;
    return Promise.all(anims.map(a => a.finished.catch(() => null))).then(() => { if (root._yeAward === handle) { root._yeAward = null; unlayer(); } });
  }

  window.YS_EMBLEM[KEY] = {
    name: '唱片刻纹', version: VERSION,
    render, award,
    tierOf, tiers: TIERS, hapticPlan, playHaptics, vibratePattern, primitives: PRIM,
    setTilt, tiltVars, tiltDriver, hx,
    /** the curves behind a tier, for keyframe strips and tables: script(tier, m, size) */
    script: (tier, m, size = 96) => script(tier, layout(m, size), kindOf(m))
  };
})();
