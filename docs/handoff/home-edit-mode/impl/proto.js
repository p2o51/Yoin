/*
 * Yoin · Home edit-mode interactive prototype
 * Spec: wf3/final-spec.md §2–§4 (long-press edit with charge plate pre-show, ripple enter, Q1 wiggle
 * form × target, strip carry with real covers, tray, undo, predictive back, §2.8 haptics + visual twins,
 * §4 discoverability: JBI hint tile + feed-end entry, Q11(b) play glyph).
 *
 * Self-contained: finds <div id="yoin-proto">, injects a scoped <style>, builds the phone + control panel.
 * No libraries, no network, no storage. One rAF loop drives every spring and stops when nothing moves
 * (the only exception: the Q1(b)/(c) sway while its envelope E > 0 in edit mode, and edge auto-scroll).
 *
 * Single-writer map (mirrors spec §2.0):
 *   P (edit progress) ............ edit controller: enterEdit / exitEdit / scrubBack / settleBack
 *   charge ....................... long-press detector (scale + plate pre-show, §2.1.3)
 *   plateFrom (chargeLatch) ...... edit controller, latched at the threshold, cleared at P ≥ 0.6 (§2.1.4-4)
 *   lift / liftTint .............. drag engine (liftBlock / dropLift)
 *   fold / holeY / settleY ....... strip engine (Q3a)
 *   per[id].strip ................ strip engine (neighbour make-way)
 *   per[id].shift / carry.carryY . full-size drag engine (Q3c)
 *   per[id].off .................. layout engine (FLIP after commits)
 *   per[id].kick, card.kick ...... impulse() / impulseCard() only (normalised: peak = a, × Θ or A_c at draw)
 *   per[id].hideS / hideA ........ layout engine (hide / show)
 *   wigEnv (E) ................... wiggle envelope controller only: setEnvelope() + the 6 s idle check
 *   simTime (t) .................. the single frame loop (one global sway clock)
 */
(function () {
  'use strict';

  var host = document.getElementById('yoin-proto');
  if (!host || host.getAttribute('data-yp-ready')) return;
  host.setAttribute('data-yp-ready', '1');

  /* ════════════════════════════ 1. Constants & motion tokens ════════════════════════════ */

  var T_LONG = 400;              // ViewConfiguration.longPressTimeout
  var SLOP = 8;                  // touch slop (1 CSS px == 1 dp in the mock)
  var EDIT_LIFT_HOLD = 200;      // max(150, T/2): body hold before lift in edit mode
  var Q2B_EXTRA_HOLD = 350;      // Q2(b): keep holding this long after T → enter edit lifted
  var SECTION_GAP = 18;
  var PAGE_PAD = 16;
  var PLATE_H = 8, PLATE_V = 6;  // plate outset
  var STATUS_H = 36;
  var UNDO_MAX = 20;
  var KICK_GAIN = 31.6;          // v = a·Θ·31.6 /s → first peak = a·Θ for ζ 0.3 / k 450
  var RUBBER_D = 56;
  var FLING_V = 1600;
  var AUTOSCROLL_EDGE = 64, AUTOSCROLL_MAX = 900;
  var HINT_SESSIONS = 2;
  var WIGGLE_IDLE_MS = 6000;     // Q1(b): no pointerdown in the phone for 6 s → E springs to 0
  var SWAY_HZ = 2.4;             // Q1(b)/(c) base frequency, detuned ±7% per section id
  var SWAY_DRAG_E = 0.6;         // other blocks sway at E × 0.6 while one is being dragged
  var PRESS_BOX = 96;            // §2.1.3: R_press = 96px square around the press point ∩ block rect
  var PRESS_OUTSET = 4;          // R_press drawn outset by 4px · charge
  var BAND_FADE = 48;            // §2.2.4 edge band: linear fade within 48px of the status / bar bands
  var SHELF_V = 6;               // vertical breathing room inside horizontal shelves for rotating cards
  var JBI_COLS = 3;

  var TOKENS = {
    defaultSpatial: { z: 0.8, k: 380 },
    fastSpatial: { z: 0.6, k: 800 },
    slowSpatial: { z: 0.8, k: 200 },
    defaultEffects: { z: 1.0, k: 1600 },
    fastEffects: { z: 1.0, k: 3800 },
    stageSettle: { z: 0.85, k: 700 },
    kick: { z: 0.3, k: 450 }            // homeEditKickSpring
  };
  var SPATIAL = { defaultSpatial: 1, fastSpatial: 1, slowSpatial: 1, stageSettle: 1, kick: 1 };

  /* ════════════════════════════ 2. Small utilities ════════════════════════════ */

  function clamp(v, a, b) { return v < a ? a : v > b ? b : v; }
  function lerp(a, b, t) { return a + (b - a) * t; }
  function smoothstep(e0, e1, x) { var t = clamp((x - e0) / (e1 - e0), 0, 1); return t * t * (3 - 2 * t); }
  function f2(n) { return Math.round(n * 100) / 100; }
  function f3(n) { return Math.round(n * 1000) / 1000; }
  function hashStr(s) {
    var h = 2166136261;
    for (var i = 0; i < s.length; i++) { h ^= s.charCodeAt(i); h = Math.imul(h, 16777619); }
    return h >>> 0;
  }
  /** Cached style write: skips the DOM write when the value has not changed. */
  function setS(el, prop, val) {
    var c = el._ypS || (el._ypS = {});
    if (c[prop] !== val) { c[prop] = val; el.style[prop] = val; }
  }
  function $(sel, root) { return (root || host).querySelector(sel); }
  function $all(sel, root) { return Array.prototype.slice.call((root || host).querySelectorAll(sel)); }

  /** cubic-bezier(x1,y1,x2,y2) evaluated by bisection (robust, cheap). */
  function cubicBezier(x1, y1, x2, y2) {
    function b(t, a, c) { var u = 1 - t; return 3 * u * u * t * a + 3 * u * t * t * c + t * t * t; }
    return function (x) {
      if (x <= 0) return 0;
      if (x >= 1) return 1;
      var lo = 0, hi = 1, t = x;
      for (var i = 0; i < 24; i++) {
        t = (lo + hi) / 2;
        if (b(t, x1, x2) < x) lo = t; else hi = t;
      }
      return b(t, y1, y2);
    };
  }
  var BACK_EASE = cubicBezier(0.1, 0.1, 0, 1); // YoinMotion.backGestureEasing (approx.)

  function rubber(x) { return RUBBER_D * (1 - 1 / (x * 0.55 / RUBBER_D + 1)); }
  /** Inverse of rubber(): raw overscroll that displays as y (used when re-grabbing a settling strip). */
  function unrubber(y) { var r = Math.min(y / RUBBER_D, 0.98); return (RUBBER_D / 0.55) * (1 / (1 - r) - 1); }

  /**
   * Release velocity over the last 100ms of samples. A finger that rested before lifting has no
   * fresh samples, so it reports 0 (otherwise a pause-then-release would replay the old speed as a fling).
   */
  function velTracker() {
    var s = [];
    return {
      reset: function () { s.length = 0; },
      add: function (t, y) { s.push([t, y]); while (s.length > 2 && t - s[0][0] > 100) s.shift(); },
      v: function (now) {
        if (s.length < 2) return 0;
        var b = s[s.length - 1];
        if (typeof now === 'number' && now - b[0] > 60) return 0;
        var i = 0;
        while (i < s.length - 2 && b[0] - s[i][0] > 100) i++;
        var a = s[i], dt = (b[0] - a[0]) / 1000;
        return dt > 0.004 ? clamp((b[1] - a[1]) / dt, -8000, 8000) : 0;
      }
    };
  }

  var prefersReduced = false;
  try { prefersReduced = !!(window.matchMedia && window.matchMedia('(prefers-reduced-motion: reduce)').matches); } catch (e) { prefersReduced = false; }

  /* ════════════════════════════ 3. Options & state ════════════════════════════ */

  // Defaults = the spec's recommendations: Q1 形态 (b) IdleSettle + 谁在动 卡片级, Q2 (a), Q3 (a).
  // q1: 'a' Kick | 'b' IdleSettle | 'c' Continuous; q1t: 'card' | 'block'; q3: 'a' strips | 'c' full size.
  var opts = { q1: 'b', q1t: 'card', q2: 'a', q3: 'a', amp: 1, slow: false, reduced: prefersReduced, band: true };

  var SECTIONS = {
    activities: { title: 'Activities', support: 'What you played lately' },
    rediscover: { title: 'Rediscover', support: 'Rated high, not played in Yoin for a while' },
    recently_added: { title: 'Recently Added', support: 'New in your library this week' },
    jump_back_in: { title: 'Jump Back In', support: 'Memories, notes and a shuffle of your library' }
  };
  var DEFAULT_ORDER = ['activities', 'rediscover', 'recently_added', 'jump_back_in'];

  var state = {
    mode: 'normal',            // discrete HomeSurface: 'normal' | 'edit'
    layout: null,              // [{id, enabled}] — disabled entries keep their slot
    displayed: [],             // enabled ids currently in the DOM, in order
    undo: [],
    i0: 0,                     // ripple origin index
    editSessions: 0,
    showHint: false,
    hintTileGone: false,       // §4 JBI hint tile: gone for good after the first edit session (in memory)
    kicked: {},
    liftId: null, liftOrigin: null,
    chargeId: null, chargeOrigin: null,
    plateFrom: null,           // {id, rect, latch}: the entry block's plate grows from R_press⁺ (§2.1.4-4)
    carry: null,
    menu: null,
    exitAfterCarry: false,
    lastTouch: 0,
    lastScrollT: 0
  };

  var geo = { screenW: 364, screenH: 764, barTop: 680, barW: 332, contentW: 332, scrollTop: 0, maxScroll: 0, blocks: {}, trayTop: 0, footTop: 0 };

  /* ════════════════════════════ 4. Spring engine + single rAF loop ════════════════════════════ */

  var live = [];
  var rafId = 0, inFrame = false, lastFrame = 0, simTime = 0;
  var phoneOnScreen = true;      // IntersectionObserver on the phone screen; stays true where IO is unavailable

  function motion(name) {
    if (opts.reduced && SPATIAL[name]) return TOKENS.fastEffects; // reduced motion: quick, non-overshooting
    return TOKENS[name];
  }

  /** Closed-form damped-spring step (mass 1). Exact for any dt, so no sub-stepping is needed. */
  function springStep(s, dt) {
    var z = s.z, w0 = Math.sqrt(s.k), x0 = s.value - s.target, v0 = s.vel, x, v;
    if (z < 1) {
      var wd = w0 * Math.sqrt(1 - z * z), e = Math.exp(-z * w0 * dt), c = Math.cos(wd * dt), sn = Math.sin(wd * dt);
      var B = (v0 + z * w0 * x0) / wd;
      x = e * (x0 * c + B * sn);
      v = e * (-z * w0 * (x0 * c + B * sn) + (-x0 * wd * sn + B * wd * c));
    } else if (z === 1) {
      var e1 = Math.exp(-w0 * dt), B1 = v0 + w0 * x0;
      x = (x0 + B1 * dt) * e1;
      v = (B1 - w0 * (x0 + B1 * dt)) * e1;
    } else {
      var sq = Math.sqrt(z * z - 1), r1 = -w0 * (z - sq), r2 = -w0 * (z + sq);
      var c2 = (v0 - r1 * x0) / (r2 - r1), c1 = x0 - c2, ea = Math.exp(r1 * dt), eb = Math.exp(r2 * dt);
      x = c1 * ea + c2 * eb;
      v = c1 * r1 * ea + c2 * r2 * eb;
    }
    s.value = s.target + x;
    s.vel = v;
  }

  function Spring(value, threshold) {
    this.value = value; this.target = value; this.vel = 0;
    this.z = 1; this.k = 1600; this.thr = threshold || 0.001;
    this.active = false; this.onEnd = null;
  }
  Spring.prototype.animateTo = function (target, token, velocity, onEnd) {
    var t = motion(token);
    this.z = t.z; this.k = t.k; this.target = target;
    if (typeof velocity === 'number') this.vel = velocity;
    this.onEnd = onEnd || null;
    if (!this.active) { this.active = true; live.push(this); }
    requestLoop();
    return this;
  };
  Spring.prototype.snapTo = function (v) { this.value = v; this.target = v; this.vel = 0; this.stop(); };
  Spring.prototype.stop = function () {
    this.onEnd = null;
    if (this.active) { this.active = false; var i = live.indexOf(this); if (i >= 0) live.splice(i, 1); }
  };
  Spring.prototype.step = function (dt) {
    springStep(this, dt);
    if (Math.abs(this.value - this.target) < this.thr && Math.abs(this.vel) < this.thr * 20) {
      this.value = this.target; this.vel = 0; this.active = false;
      var i = live.indexOf(this); if (i >= 0) live.splice(i, 1);
      var cb = this.onEnd; this.onEnd = null;
      if (cb) cb();
    }
  };

  function requestLoop() {
    if (rafId || inFrame) return;
    rafId = requestAnimationFrame(frame);
  }

  function frame(now) {
    rafId = 0; inFrame = true;
    var raw = lastFrame ? Math.min((now - lastFrame) / 1000, 1 / 24) : 1 / 60;
    lastFrame = now;
    var dt = raw * (opts.slow ? 0.25 : 1);
    simTime += dt;
    try {
      tickers(dt, now);
      var list = live.slice();
      for (var i = 0; i < list.length; i++) if (list[i].active) list[i].step(dt);
      render(now);
    } catch (err) {
      if (window.console) console.error(err);
    }
    inFrame = false;
    if (live.length || continuous()) rafId = requestAnimationFrame(frame);
    else lastFrame = 0;
  }

  /**
   * Frame callbacks that are not springs: only while the sway is visible (E > 0 in edit mode — the clock
   * parks itself when E == 0, spec §2.2.4 "时钟") or edge auto-scroll runs.
   */
  function continuous() {
    if (state.carry && state.carry.mode === 'full' && state.carry.phase === 'drag') return true;
    if (opts.reduced || !phoneOnScreen) return false;   // nobody can see the sway: park the clock (springs still finish)
    var editing = state.mode === 'edit' || P.value > 0.001;
    if (!editing) return false;
    return wigEnv.value > 0.0005 || wigEnv.active;
  }

  /* ════════════════════════════ 5. Springs ════════════════════════════ */

  var P = new Spring(0, 0.0005);
  var charge = new Spring(0, 0.002);
  var lift = new Spring(0, 0.002);
  var liftTint = new Spring(0, 0.002);
  var fold = new Spring(0, 0.001);
  var holeY = new Spring(0, 0.3);
  var settleY = new Spring(0, 0.4);
  var wigEnv = new Spring(0, 0.001);
  var hintTileA = new Spring(1, 0.002);
  var trayA = new Spring(0, 0.002), footA = new Spring(1, 0.002);
  var trayOff = new Spring(0, 0.3), footOff = new Spring(0, 0.3);
  var slotA = { undo: new Spring(0, 0.002), add: new Spring(0, 0.002), dis: new Spring(1, 0.002) };
  var toastA = new Spring(0, 0.002), menuS = new Spring(0.9, 0.001), menuA = new Spring(0, 0.002);
  var scrubVis = new Spring(0, 0.002);
  var scrollSp = new Spring(0, 0.5);
  var per = {};
  DEFAULT_ORDER.forEach(function (id) {
    per[id] = {
      off: new Spring(0, 0.3), kick: new Spring(0, 0.01), hideS: new Spring(1, 0.001), hideA: new Spring(1, 0.002),
      strip: new Spring(0, 0.3), shift: new Spring(0, 0.3), pulse: new Spring(1, 0.002), rowA: new Spring(0, 0.002)
    };
  });

  /* ════════════════════════════ 6. Styles (all scoped under #yoin-proto) ════════════════════════════ */

  var LIGHT = [
    '--surface:#FFF8F6', '--surfaceContainerLowest:#FFFFFF', '--surfaceContainerLow:#FCEFEC', '--surfaceContainer:#F8E9E5',
    '--surfaceContainerHigh:#F3E3DF', '--surfaceContainerHighest:#EDDDD9', '--primary:#8F4C38', '--onPrimary:#FFFFFF',
    '--primaryContainer:#FFDBD1', '--onPrimaryContainer:#723523', '--secondaryContainer:#FFDBCF', '--onSecondaryContainer:#5D4037',
    '--tertiaryContainer:#F6E2A6', '--onTertiaryContainer:#544511', '--onSurface:#231917', '--onSurfaceVariant:#53433F',
    '--outline:#85736E', '--outlineVariant:#D8C2BC', '--inverseSurface:#392E2B', '--inverseOnSurface:#FFEDE8',
    '--shadowBase:#000000', '--pageBg:#F7EEEB', '--bezel:#1D1B1A', '--bezelEdge:#3B3533'
  ].join(';');
  var DARK = [
    '--surface:#1A1110', '--surfaceContainerLowest:#140C0B', '--surfaceContainerLow:#231917', '--surfaceContainer:#271D1B',
    '--surfaceContainerHigh:#322826', '--surfaceContainerHighest:#3D3230', '--primary:#FFB59F', '--onPrimary:#561F0F',
    '--primaryContainer:#723523', '--onPrimaryContainer:#FFDBD1', '--secondaryContainer:#5D4037', '--onSecondaryContainer:#FFDBCF',
    '--tertiaryContainer:#544511', '--onTertiaryContainer:#F6E2A6', '--onSurface:#F1DFDA', '--onSurfaceVariant:#D8C2BC',
    '--outline:#A08C87', '--outlineVariant:#53433F', '--inverseSurface:#F1DFDA', '--inverseOnSurface:#392E2B',
    '--pageBg:#120B0A', '--bezel:#0A0807', '--bezelEdge:#2E2624'
  ].join(';');
  // Artwork colours: fixed per "cover", identical in both themes.
  var ARTS = {
    rager: ['#E4572E', '#2B0F0E'], scum: ['#7A5AF8', '#12A594'], blusher: ['#F7A1B5', '#7C2D4F'], charm: ['#8EC9F2', '#23407F'],
    emotion: ['#F49CBB', '#3E64D6'], heaven: ['#F2B134', '#A7336B'], blonde: ['#9DBF8E', '#24311F'], eversince: ['#6FA8DC', '#1D2F57'],
    jiji: ['#FFC857', '#E0623A'], olivia: ['#C7B8F5', '#4E3F8F'], m2m: ['#7FCDB8', '#12574B'], loneliest: ['#F3D7A1', '#B9473A'],
    amber: ['#FFA62B', '#6B3200'], melon: ['#8CC152', '#C2414B'], run: ['#F28C6F', '#5B1E2E'], j1: ['#B5838D', '#3D2B3D'],
    j2: ['#5FB49C', '#1F3A3D'], j3: ['#E9C46A', '#264653'], j4: ['#F4A261', '#6D3B2B'], j5: ['#90BE6D', '#2F4858'],
    j6: ['#CDB4DB', '#5E548E'], j7: ['#F28482', '#3D405B'], j8: ['#A3C4F3', '#2E3A59'], j9: ['#FFB4A2', '#6D435A']
  };
  var artTokens = Object.keys(ARTS).map(function (k) { return '--art-' + k + '-a:' + ARTS[k][0] + ';--art-' + k + '-b:' + ARTS[k][1]; }).join(';');

  var R = '#yoin-proto ';
  var CSS = [
    '#yoin-proto{' + LIGHT + ';' + artTokens + ';font-family:"Google Sans Flex",system-ui,sans-serif;letter-spacing:0;color:var(--onSurface);background:var(--pageBg);padding:24px 16px 32px;box-sizing:border-box;line-height:1.35;-webkit-text-size-adjust:100%}',
    '@media (prefers-color-scheme: dark){:root:not([data-theme="light"]) #yoin-proto{' + DARK + '}}',
    ':root[data-theme="dark"] #yoin-proto{' + DARK + '}',
    R + '*,' + R + '*::before,' + R + '*::after{box-sizing:border-box}',
    R + 'button{font:inherit;letter-spacing:0;color:inherit}',
    R + ':focus{outline:none}',
    R + ':focus-visible{outline:2px solid var(--primary);outline-offset:2px}',
    R + '.yp-root{display:grid;grid-template-columns:380px minmax(0,1fr);gap:28px;max-width:1080px;margin:0 auto;align-items:start}',
    '@media (max-width:760px){' + R + '.yp-root{grid-template-columns:minmax(0,1fr)}' + R + '.yp-phone-col{justify-self:center}}',
    R + '.yp-phone-col{width:min(380px,100%);min-width:0}',
    '@media (min-width:761px) and (min-height:860px){' + R + '.yp-phone-col{position:sticky;top:16px}}',
    R + '.yp-haptic{display:flex;align-items:center;justify-content:center;gap:8px;height:22px;margin-bottom:10px;font-size:12px;letter-spacing:.1px;color:var(--onSurfaceVariant)}',
    R + '.yp-hdot{width:10px;height:10px;border-radius:50%;background:var(--primary);opacity:.3}',
    R + '.yp-hname{font-variant-numeric:tabular-nums;min-width:120px}',
    R + '.yp-phone{width:100%;aspect-ratio:390/800;border-radius:40px;background:var(--bezel);padding:8px;box-shadow:0 0 0 1px var(--bezelEdge),0 22px 48px -18px color-mix(in srgb,var(--shadowBase) 45%,transparent)}',
    R + '.yp-screen{position:relative;width:100%;height:100%;border-radius:32px;overflow:hidden;background:var(--surface);isolation:isolate;user-select:none;-webkit-user-select:none;-webkit-touch-callout:none;-webkit-tap-highlight-color:transparent;touch-action:manipulation}',
    // overflow-anchor:none — browser scroll anchoring would silently shift scrollTop when blocks are
    // re-ordered / hidden, breaking the FLIP maths and the strip-drop anchor (both assume scrollTop is ours).
    R + '.yp-scroll{position:absolute;inset:0;overflow-y:auto;overflow-x:hidden;overscroll-behavior:contain;overflow-anchor:none;scrollbar-width:none}',
    R + '.yp-scroll::-webkit-scrollbar{display:none}',
    R + '.yp-feed{position:relative;padding:' + (STATUS_H + 4) + 'px ' + PAGE_PAD + 'px 116px}',
    R + '.yp-status{position:absolute;left:0;right:0;top:0;height:' + STATUS_H + 'px;display:flex;align-items:center;justify-content:space-between;padding:0 22px 0 26px;font-size:13px;font-weight:500;background:var(--surface);z-index:3;pointer-events:auto}',
    R + '.yp-status svg{fill:currentColor;display:block}',
    R + '.yp-st-icons{display:flex;gap:5px;align-items:center}',
    // header
    R + '.yp-header{position:relative;display:flex;align-items:center;min-height:60px;padding:6px 0 12px}',
    R + '.yp-title-stack{position:relative;flex:1;min-width:0;height:44px}',
    R + '.yp-t-home,' + R + '.yp-t-edit{position:absolute;left:0;top:0;margin:0;font-size:34px;line-height:44px;font-weight:400;white-space:nowrap}',
    R + '.yp-t-edit{opacity:0}',
    R + '.yp-head-icons{display:flex;margin-right:-12px}',
    R + '.yp-icon-btn{width:48px;height:48px;border-radius:24px;border:0;background:transparent;color:var(--onSurfaceVariant);display:grid;place-items:center;cursor:pointer;padding:0}',
    R + '.yp-icon-btn:hover{background:color-mix(in srgb,var(--onSurface) 8%,transparent)}',
    R + '.yp-hint{position:absolute;right:0;top:50%;margin-top:-9px;height:18px;font-size:12px;font-weight:500;letter-spacing:.1px;color:var(--onSurfaceVariant);white-space:nowrap;pointer-events:none;opacity:0}',
    R + '.yp-ic{fill:currentColor;display:block;flex:none}',
    // blocks
    R + '.yp-sections{display:flex;flex-direction:column;gap:' + SECTION_GAP + 'px}',
    R + '.yp-block{position:relative;transform-origin:0 0;will-change:transform}',
    R + '.yp-plate,' + R + '.yp-lift-shadow{position:absolute;top:-' + PLATE_V + 'px;bottom:-' + PLATE_V + 'px;left:-' + PLATE_H + 'px;right:-' + PLATE_H + 'px;border-radius:20px;pointer-events:none;opacity:0}',
    R + '.yp-plate{background:var(--surfaceContainerHigh)}',
    R + '.yp-plate-tint{position:absolute;inset:0;border-radius:inherit;background:var(--surfaceContainerHighest);opacity:0}',
    R + '.yp-lift-shadow{box-shadow:0 8px 22px color-mix(in srgb,var(--shadowBase) 24%,transparent),0 2px 6px color-mix(in srgb,var(--shadowBase) 14%,transparent)}',
    R + '.yp-body{position:relative}',
    R + '.yp-sec-head{display:flex;align-items:center;height:24px;margin-bottom:12px}',
    R + '.yp-sec-title{flex:1;min-width:0;margin:0;font-size:18px;line-height:24px;font-weight:600;white-space:nowrap;overflow:hidden;text-overflow:ellipsis}',
    R + '.yp-badges{display:flex;gap:8px;margin-right:-12px;visibility:hidden;pointer-events:none}',
    R + '.yp-ev .yp-badges{visibility:visible}',
    R + '.yp-editing .yp-badges{pointer-events:auto}',
    R + '.yp-single .yp-handle{display:none}',
    R + '.yp-badge{width:48px;height:48px;margin:-12px 0;border:0;padding:0;background:transparent;display:grid;place-items:center;color:var(--onSurfaceVariant);cursor:pointer;border-radius:24px;opacity:0}',
    R + '.yp-bv{width:32px;height:32px;border-radius:16px;background:var(--surfaceContainerHighest);color:var(--onSurface);display:grid;place-items:center}',
    R + '.yp-handle{touch-action:none;cursor:grab}',
    // cards
    R + '.yp-card{appearance:none;-webkit-appearance:none;border:0;margin:0;padding:0;text-align:left;cursor:pointer;background:color-mix(in srgb,var(--pal) 30%,var(--surfaceContainerLow));border-radius:16px;display:flex;min-width:0;transition:transform .2s cubic-bezier(.2,0,0,1)}',
    R + '.yp-card.is-pressed{transform:scale(.97)}',
    R + '.yp-editing .yp-card{cursor:default}',
    R + '.yp-reduced .yp-card,' + R + '.yp-reduced .yp-thumb{transition:none}',
    R + '.yp-bare{background:transparent;border-radius:10px}',
    R + '.yp-col{display:flex;flex-direction:column;min-width:0;flex:1}',
    R + '.yp-col>span,' + R + '.yp-ell{white-space:nowrap;overflow:hidden;text-overflow:ellipsis}',
    R + '.yp-lab{font-size:12px;font-weight:500;letter-spacing:.1px;color:var(--onSurfaceVariant)}',
    R + '.yp-sub{font-size:14px;color:var(--onSurfaceVariant)}',
    R + '.yp-art{display:block;position:relative;overflow:hidden;border-radius:4px;background:radial-gradient(circle at 30% 26%,color-mix(in srgb,var(--a) 65%,transparent) 0,transparent 58%),linear-gradient(145deg,var(--a),var(--b))}',
    R + '.yp-art[data-v="1"]::after{content:"";position:absolute;width:46%;height:46%;right:12%;top:14%;border-radius:50%;background:color-mix(in srgb,var(--a) 50%,var(--b));opacity:.85}',
    R + '.yp-art[data-v="2"]::after{content:"";position:absolute;inset:0;background:repeating-linear-gradient(115deg,transparent 0 9px,color-mix(in srgb,var(--b) 42%,transparent) 9px 12px)}',
    R + '.yp-art[data-v="3"]::after{content:"";position:absolute;left:0;right:0;bottom:0;height:44%;background:linear-gradient(to top,color-mix(in srgb,var(--b) 85%,transparent),transparent)}',
    R + '.yp-backdrop{position:relative;display:block;flex:none;width:var(--bd);height:var(--bd)}',
    // direct child only: a descendant selector also caught the play glyph's svg (24px, palette-tinted instead of 16px onSurface)
    R + '.yp-backdrop>svg{position:absolute;inset:0;width:100%;height:100%;fill:color-mix(in srgb,var(--pal) 70%,var(--surface))}',
    R + '.yp-backdrop .yp-art-in{position:absolute;right:0;bottom:0;width:72%;height:72%}',
    // activities
    R + '.yp-acts{display:flex;flex-direction:column;gap:10px}',
    R + '.yp-hero{padding:14px;gap:14px;align-items:center}',
    R + '.yp-h-title{font-size:22px;line-height:28px;font-weight:600;margin:2px 0}',
    R + '.yp-act-row{display:flex;gap:10px;height:118px}',
    R + '.yp-small{flex:1;flex-direction:column;padding:10px;gap:3px}',
    R + '.yp-portrait{width:48px;height:48px;border-radius:50%;flex:none;margin-bottom:6px}',
    R + '.yp-s-title{font-size:17px;line-height:22px;font-weight:600}',
    R + '.yp-wide{flex:2;padding:12px;gap:12px;align-items:center}',
    R + '.yp-cover80{width:80px;height:80px;flex:none}',
    R + '.yp-w-title{font-size:16px;line-height:22px;font-weight:600}',
    R + '.yp-stripcard{border-radius:999px;padding:12px 16px;justify-content:space-between;align-items:center;gap:10px}',
    R + '.yp-stripcard b{font-weight:600}',
    R + '.yp-stripcard .yp-ell{font-size:14px;min-width:0}',
    // shelves
    // ±SHELF_V vertical padding / negative margin: layout-neutral, but gives a card rotating inside the scroller
    // (卡片级 sway, up to 1.1° × the 4× slider) room so its corners are not clipped by overflow-y:hidden
    R + '.yp-shelf{display:flex;margin:-' + SHELF_V + 'px -' + PAGE_PAD + 'px;padding:' + SHELF_V + 'px ' + PAGE_PAD + 'px;overflow-x:auto;overflow-y:hidden;scrollbar-width:none;overscroll-behavior-x:contain;scroll-padding:0 ' + PAGE_PAD + 'px}',
    R + '.yp-shelf::-webkit-scrollbar{display:none}',
    R + '.yp-editing .yp-shelf{overflow-x:hidden}',
    R + '.yp-redis-shelf{gap:12px;scroll-snap-type:x mandatory}',
    R + '.yp-shelf.fade-s{-webkit-mask-image:linear-gradient(to right,transparent 0,var(--shadowBase) 24px);mask-image:linear-gradient(to right,transparent 0,var(--shadowBase) 24px)}',
    R + '.yp-shelf.fade-e{-webkit-mask-image:linear-gradient(to left,transparent 0,var(--shadowBase) 24px);mask-image:linear-gradient(to left,transparent 0,var(--shadowBase) 24px)}',
    R + '.yp-shelf.fade-s.fade-e{-webkit-mask-image:linear-gradient(to right,transparent 0,var(--shadowBase) 24px,var(--shadowBase) calc(100% - 24px),transparent);mask-image:linear-gradient(to right,transparent 0,var(--shadowBase) 24px,var(--shadowBase) calc(100% - 24px),transparent)}',
    R + '.yp-redis{flex:none;width:86%;height:132px;padding:14px;gap:14px;align-items:center;scroll-snap-align:start}',
    R + '.yp-eyebrow{font-size:12px;line-height:16px;font-weight:500;letter-spacing:.1px;color:var(--primary);white-space:normal!important;display:-webkit-box;-webkit-line-clamp:2;-webkit-box-orient:vertical}',
    R + '.yp-r-title{font-size:20px;line-height:26px;font-weight:600;margin-top:2px}',
    R + '.yp-fnote{font-size:11px;letter-spacing:.1px;color:var(--onSurfaceVariant);font-variant-numeric:tabular-nums;margin-top:4px}',
    R + '.yp-ra-shelf{gap:14px}',
    R + '.yp-ra-grid{flex:none;width:calc((100% + ' + (2 * PAGE_PAD) + 'px - 14px) * 2.6 / 3.6);display:grid;grid-template-columns:minmax(0,1fr) minmax(0,1fr);column-gap:8px;row-gap:14px}',
    R + '.yp-tile{gap:8px;align-items:center}',
    R + '.yp-cover52{width:52px;height:52px;flex:none}',
    R + '.yp-tt{font-size:13px;line-height:18px;font-weight:500}',
    R + '.yp-ra-album{flex:none;width:82px;flex-direction:column;gap:6px}',
    R + '.yp-ra-album .yp-ell{display:block;width:82px}',
    // JBI
    R + '.yp-jbi{display:grid;grid-template-columns:repeat(' + JBI_COLS + ',minmax(0,1fr));column-gap:12px;row-gap:16px}',
    R + '.yp-w12{grid-column:span 2;gap:14px;align-items:flex-start}',
    R + '.yp-w11{flex-direction:column;gap:5px}',
    R + '.yp-w11 .yp-backdrop{width:min(100px,100%);height:auto;aspect-ratio:1}',
    R + '.yp-w11 .yp-ell{display:block}',
    R + '.yp-jt{font-size:15px;line-height:20px;font-weight:600}',
    R + '.yp-js{font-size:12px;color:var(--onSurfaceVariant)}',
    R + '.yp-rating{font-size:26px;line-height:32px;font-weight:700;font-variant-numeric:tabular-nums;color:var(--primary)}',
    R + '.yp-basis{font-size:11px;letter-spacing:.1px;color:var(--onSurfaceVariant)}',
    R + '.yp-comment{font-family:"Noto Serif SC","Songti SC",Georgia,serif;font-size:17px;line-height:24px;margin-top:4px;white-space:normal!important}',
    // Q11(b) play glyph: song cells play on tap, so their cover carries a small filled play triangle (static)
    R + '.yp-play{position:absolute;right:3px;bottom:3px;width:24px;height:24px;border-radius:12px;display:grid;place-items:center;background:color-mix(in srgb,var(--surfaceContainerLowest) 86%,transparent);color:var(--onSurface);pointer-events:none}',
    // §4 / Q6b(a) JBI hint tile: a static 1×1 cell in the grid's free slot. Card shape, no animation, no dashes
    R + '.yp-hint-tile{appearance:none;-webkit-appearance:none;border:0;margin:0;padding:10px 8px;width:min(100px,100%);aspect-ratio:1;border-radius:16px;background:var(--surfaceContainerHigh);color:var(--onSurfaceVariant);display:flex;flex-direction:column;align-items:center;justify-content:center;gap:6px;text-align:center;font-size:12px;line-height:16px;font-weight:500;letter-spacing:.1px;cursor:pointer}',
    R + '.yp-hint-tile:hover{background:color-mix(in srgb,var(--onSurface) 6%,var(--surfaceContainerHigh))}',
    // tray / foot
    R + '.yp-tray{display:none;margin-top:' + SECTION_GAP + 'px;padding-top:4px}',
    R + '.yp-tray-head{font-size:18px;line-height:24px;font-weight:600;margin:0 0 12px}',
    R + '.yp-tray-empty{margin:0 0 4px;font-size:13px;color:var(--onSurfaceVariant)}',
    R + '.yp-tray-rows{display:flex;flex-direction:column;gap:8px}',
    R + '.yp-tray-row{display:flex;align-items:center;gap:12px;height:64px;padding:0 4px 0 16px;border-radius:20px;background:var(--surfaceContainerHigh);border:0;width:100%;text-align:left;cursor:pointer}',
    R + '.yp-tray-row .t{font-size:16px;line-height:20px;font-weight:600}',
    R + '.yp-tray-row .s{font-size:12px;line-height:16px;color:var(--onSurfaceVariant)}',
    R + '.yp-plus{width:48px;height:48px;display:grid;place-items:center;flex:none}',
    R + '.yp-plus>span{width:40px;height:40px;border-radius:20px;background:var(--secondaryContainer);color:var(--onSecondaryContainer);display:grid;place-items:center}',
    R + '.yp-reset{margin:14px auto 0;display:flex;align-items:center;gap:8px;height:48px;padding:0 16px;border:0;border-radius:24px;background:transparent;color:var(--primary);font-size:14px;font-weight:500;cursor:pointer}',
    R + '.yp-reset:disabled{color:color-mix(in srgb,var(--onSurface) 38%,transparent);cursor:default}',
    R + '.yp-foot{margin-top:' + SECTION_GAP + 'px;display:flex;flex-direction:column;align-items:center;gap:12px}',
    R + '.yp-edit-entry{height:48px;padding:0 16px;display:flex;align-items:center;gap:8px;border:0;background:transparent;border-radius:24px;color:var(--onSurfaceVariant);font-size:14px;font-weight:500;cursor:pointer}',
    R + '.yp-edit-entry:hover,' + R + '.yp-reset:not(:disabled):hover{background:color-mix(in srgb,var(--onSurface) 8%,transparent)}',
    R + '.yp-empty{display:none;width:100%;border-radius:20px;background:var(--surfaceContainerHigh);padding:20px}',
    R + '.yp-empty h3{margin:0 0 6px;font-size:18px;font-weight:600}',
    R + '.yp-empty p{margin:0;font-size:14px;color:var(--onSurfaceVariant)}',
    // carry / strips
    R + '.yp-carry{position:absolute;inset:0;pointer-events:none;z-index:4;display:none}',
    R + '.yp-strip-plate{position:absolute;inset:0;background:var(--surfaceContainerHigh)}',
    R + '.yp-strip-plate.is-carried{background:var(--surfaceContainerHighest)}',
    // strip content (§2.3 ②): 3 real covers in their backdrop shapes, 30% overlap, no borders → title → handle
    R + '.yp-strip-label{position:absolute;left:0;top:0;display:flex;align-items:center;gap:12px;padding:0 16px;color:var(--onSurface);--cs:36px}',
    R + '.yp-strip-covers{display:flex;flex:none;align-items:center}',
    R + '.yp-strip-covers .yp-mini{position:relative}',
    R + '.yp-strip-covers .yp-mini+.yp-mini{margin-left:calc(var(--cs) * -0.3)}',
    R + '.yp-strip-covers .yp-mini:nth-child(1){z-index:3}',
    R + '.yp-strip-covers .yp-mini:nth-child(2){z-index:2}',
    R + '.yp-strip-label .t{flex:1;min-width:0;font-size:16px;line-height:20px;font-weight:600;white-space:nowrap;overflow:hidden;text-overflow:ellipsis}',
    R + '.yp-strip-label svg{color:var(--onSurfaceVariant)}',
    R + '.yp-mini{display:block;flex:none;width:var(--cs);height:var(--cs);background:radial-gradient(circle at 30% 26%,color-mix(in srgb,var(--a) 65%,transparent) 0,transparent 58%),linear-gradient(145deg,var(--a),var(--b));-webkit-mask-size:100% 100%;mask-size:100% 100%;-webkit-mask-repeat:no-repeat;mask-repeat:no-repeat}',
    R + '.yp-strip-shadow{position:absolute;left:0;top:0;border-radius:20px;opacity:0;box-shadow:0 10px 24px color-mix(in srgb,var(--shadowBase) 26%,transparent),0 2px 6px color-mix(in srgb,var(--shadowBase) 14%,transparent)}',
    R + '.yp-hole{position:absolute;left:0;top:0;border-radius:20px;opacity:0;background:color-mix(in srgb,var(--secondaryContainer) 35%,transparent)}',
    // edge / back indicator
    R + '.yp-edge{position:absolute;left:0;top:' + STATUS_H + 'px;bottom:100px;width:16px;z-index:4;pointer-events:none;touch-action:none}',
    R + '.yp-editing .yp-edge{pointer-events:auto;cursor:ew-resize}',
    R + '.yp-back-ind{position:absolute;left:0;top:0;width:36px;height:36px;border-radius:18px;background:var(--inverseSurface);color:var(--inverseOnSurface);display:grid;place-items:center;opacity:0;z-index:6;pointer-events:none}',
    // bar
    R + '.yp-bar{position:absolute;left:16px;right:16px;bottom:16px;height:68px;border-radius:34px;background:var(--surfaceContainer);z-index:5;box-shadow:0 6px 18px -6px color-mix(in srgb,var(--shadowBase) 30%,transparent)}',
    R + '.yp-slot,' + R + '.yp-pill{position:absolute;top:10px;left:0;height:48px;border-radius:24px;border:0;padding:0;overflow:hidden;background:transparent;cursor:pointer;width:56px}',
    R + '.yp-slot-bg{position:absolute;inset:0;border-radius:inherit;transition:border-radius .18s cubic-bezier(.2,0,0,1),transform .18s cubic-bezier(.2,0,0,1)}',
    // press shape morph (pill → rounded rect + slight squeeze): the visual twin of KEYBOARD_TAP / the Done CONFIRM
    R + '.yp-slot:active:not([aria-disabled="true"]) .yp-slot-bg{border-radius:12px;transform:scale(.96,.92)}',
    R + '.yp-reduced .yp-slot-bg{transition:none}',
    R + '.yp-bg-nav{background:var(--secondaryContainer)}',
    R + '.yp-bg-edit{background:var(--surfaceContainerHighest);opacity:0}',
    R + '.yp-bg-primary{background:var(--primary);opacity:0}',
    R + '.yp-layer{position:absolute;inset:0;display:flex;align-items:center;justify-content:center;gap:6px;font-size:14px;font-weight:500;white-space:nowrap;pointer-events:none}',
    R + '.yp-l-home{color:var(--onSecondaryContainer)}',
    R + '.yp-l-lib{color:var(--onSurfaceVariant)}',
    R + '.yp-l-done{color:var(--onPrimary);opacity:0}',
    R + '.yp-l-edit{opacity:0;color:var(--onSurface)}',
    R + '.yp-l-dis{color:color-mix(in srgb,var(--onSurface) 38%,transparent)}',
    R + '.yp-pill{display:flex;align-items:center;gap:10px;padding:0 6px;background:var(--surfaceContainerHighest);text-align:left}',
    R + '.yp-pill .yp-art{width:36px;height:36px;border-radius:8px;flex:none}',
    R + '.yp-pill-t{font-size:12px;line-height:15px;font-weight:700;letter-spacing:.4px;white-space:nowrap}',
    R + '.yp-pill-s{font-size:12px;line-height:15px;color:var(--onSurfaceVariant);white-space:nowrap}',
    // menu / toast
    R + '.yp-menu{position:absolute;left:0;top:0;z-index:7;display:none;flex-direction:column;gap:2px;width:208px;opacity:0}',
    R + '.yp-menu-group{background:var(--surfaceContainerLow);padding:4px;box-shadow:0 8px 24px -8px color-mix(in srgb,var(--shadowBase) 35%,transparent),0 1px 3px color-mix(in srgb,var(--shadowBase) 12%,transparent)}',
    R + '.yp-menu-group:first-child{border-radius:16px 16px 6px 6px}',
    R + '.yp-menu-group:last-child{border-radius:6px 6px 16px 16px}',
    R + '.yp-menu-item{display:flex;align-items:center;gap:12px;width:100%;height:44px;padding:0 12px;border:0;border-radius:12px;background:transparent;font-size:14px;font-weight:500;color:var(--onSurface);cursor:pointer;text-align:left}',
    R + '.yp-menu-item svg{color:var(--onSurfaceVariant)}',
    R + '.yp-menu-item:hover,' + R + '.yp-menu-item:focus-visible{background:var(--secondaryContainer);color:var(--onSecondaryContainer)}',
    R + '.yp-toast{position:absolute;left:50%;bottom:100px;z-index:6;max-width:calc(100% - 48px);padding:10px 16px;border-radius:12px;background:var(--inverseSurface);color:var(--inverseOnSurface);font-size:14px;white-space:nowrap;overflow:hidden;text-overflow:ellipsis;pointer-events:none;opacity:0;transform:translate(-50%,8px)}',
    R + '.yp-sr{position:absolute;width:1px;height:1px;overflow:hidden;clip:rect(0 0 0 0);white-space:nowrap}',
    // panel
    R + '.yp-panel{display:flex;flex-direction:column;gap:14px;min-width:0}',
    R + '.yp-pcard{background:var(--surfaceContainerLow);border-radius:20px;padding:16px}',
    R + '.yp-ph{margin:0 0 6px;font-size:20px;line-height:26px;font-weight:500}',
    R + '.yp-pp{margin:0;font-size:13px;color:var(--onSurfaceVariant)}',
    R + '.yp-pp kbd{font:inherit;font-size:12px;padding:0 5px;border-radius:6px;background:var(--surfaceContainerHighest);color:var(--onSurface)}',
    R + '.yp-gl{margin:0 0 4px;font-size:14px;font-weight:600}',
    R + '.yp-opt{display:block;width:100%;text-align:left;border:1px solid var(--outlineVariant);background:transparent;border-radius:14px;padding:10px 12px;margin-top:8px;cursor:pointer}',
    R + '.yp-opt:hover{background:color-mix(in srgb,var(--onSurface) 5%,transparent)}',
    R + '.yp-opt[aria-pressed="true"]{border-color:var(--primary);background:var(--secondaryContainer);color:var(--onSecondaryContainer)}',
    R + '.yp-opt .o-t{display:flex;align-items:center;flex-wrap:wrap;gap:8px;font-size:14px;font-weight:600}',
    R + '.yp-opt .o-d{display:block;margin-top:3px;font-size:12px;line-height:17px;letter-spacing:.1px;color:var(--onSurfaceVariant)}',
    R + '.yp-opt[aria-pressed="true"] .o-d{color:var(--onSecondaryContainer)}',
    R + '.yp-rec{font-size:11px;font-weight:600;letter-spacing:.1px;padding:1px 8px;border-radius:999px;background:var(--tertiaryContainer);color:var(--onTertiaryContainer)}',
    R + '.yp-slider{display:block;margin-top:14px;font-size:13px}',
    R + '.yp-slider-row{display:flex;align-items:center;gap:12px;margin-top:6px}',
    R + '.yp-slider input{flex:1;min-width:0;accent-color:var(--primary)}',
    R + '.yp-slider output{font-variant-numeric:tabular-nums;font-weight:600;min-width:40px;text-align:right}',
    R + '.yp-switch{display:flex;align-items:center;justify-content:space-between;gap:12px;width:100%;min-height:48px;border:0;background:transparent;text-align:left;padding:4px 0;cursor:pointer}',
    R + '.yp-switch .sw-l{font-size:14px;font-weight:500}',
    R + '.yp-switch .sw-d{display:block;font-size:12px;letter-spacing:.1px;color:var(--onSurfaceVariant);font-weight:400}',
    R + '.yp-track{position:relative;flex:none;width:52px;height:32px;border-radius:16px;background:var(--surfaceContainerHighest);border:2px solid var(--outline)}',
    R + '.yp-thumb{position:absolute;top:50%;left:6px;width:16px;height:16px;margin-top:-8px;border-radius:50%;background:var(--outline);transition:transform .18s cubic-bezier(.2,0,0,1),width .18s,height .18s}',
    R + '.yp-switch[aria-checked="true"] .yp-track{background:var(--primary);border-color:var(--primary)}',
    R + '.yp-switch[aria-checked="true"] .yp-thumb{background:var(--onPrimary);transform:translateX(18px);width:24px;height:24px;margin-top:-12px;left:4px}',
    R + '.yp-actions{display:flex;flex-wrap:wrap;gap:8px}',
    R + '.yp-btn{height:40px;padding:0 16px;border-radius:20px;border:1px solid var(--outline);background:transparent;font-size:14px;font-weight:500;cursor:pointer;color:var(--primary)}',
    R + '.yp-btn.filled{background:var(--primary);border-color:var(--primary);color:var(--onPrimary)}',
    R + '.yp-btn:hover{background:color-mix(in srgb,var(--primary) 8%,transparent)}',
    R + '.yp-btn.filled:hover{background:color-mix(in srgb,var(--primary) 88%,var(--onPrimary))}',
    R + '.yp-readout{display:grid;grid-template-columns:auto minmax(0,1fr);gap:4px 12px;margin:0;font-size:13px;font-variant-numeric:tabular-nums}',
    R + '.yp-readout dt{color:var(--onSurfaceVariant)}',
    R + '.yp-readout dd{margin:0;min-width:0;overflow-wrap:anywhere}',
    R + '.yp-meter{display:inline-block;vertical-align:middle;width:72px;height:6px;border-radius:3px;background:var(--surfaceContainerHighest);margin-left:8px;overflow:hidden}',
    R + '.yp-meter>i{display:block;height:100%;width:100%;background:var(--primary);transform-origin:0 0;transform:scaleX(0)}',
    R + '.yp-log{list-style:none;margin:0;padding:0;display:flex;flex-direction:column;gap:6px;min-height:40px}',
    R + '.yp-log li{display:flex;gap:10px;align-items:baseline;font-size:12px;letter-spacing:.1px;min-width:0}',
    R + '.yp-log code{flex:none;font-family:ui-monospace,SFMono-Regular,Menlo,monospace;font-size:11px;font-weight:600;padding:2px 6px;border-radius:6px;background:var(--secondaryContainer);color:var(--onSecondaryContainer)}',
    R + '.yp-log .why{color:var(--onSurfaceVariant);min-width:0;overflow-wrap:anywhere}',
    R + '.yp-log .twin{display:block;margin-top:2px;color:var(--onSurface)}',
    R + '.yp-log-note{margin:0 0 10px}',
    R + '.yp-note{margin-top:10px}',
    R + '.yp-log .tm{margin-left:auto;flex:none;color:var(--onSurfaceVariant);font-variant-numeric:tabular-nums}',
    R + '.yp-log-empty{font-size:12px;color:var(--onSurfaceVariant)}'
  ].join('\n');

  /* ════════════════════════════ 7. Icons, shapes, content ════════════════════════════ */

  var ICON = {
    chevronDown: 'M7.41 8.59 12 13.17l4.59-4.58L18 10l-6 6-6-6z',
    chevronLeft: 'M15.41 7.41 14 6l-6 6 6 6 1.41-1.41L10.83 12z',
    settings: 'M19.14 12.94c.04-.3.06-.61.06-.94 0-.32-.02-.64-.07-.94l2.03-1.58a.49.49 0 0 0 .12-.61l-1.92-3.32a.49.49 0 0 0-.59-.22l-2.39.96c-.5-.38-1.03-.7-1.62-.94l-.36-2.54a.48.48 0 0 0-.48-.41h-3.84c-.24 0-.43.17-.47.41l-.36 2.54c-.59.24-1.13.57-1.62.94l-2.39-.96c-.22-.08-.47 0-.59.22L2.74 8.87c-.12.21-.08.47.12.61l2.03 1.58c-.05.3-.09.63-.09.94s.02.64.07.94l-2.03 1.58a.49.49 0 0 0-.12.61l1.92 3.32c.12.22.37.29.59.22l2.39-.96c.5.38 1.03.7 1.62.94l.36 2.54c.05.24.24.41.48.41h3.84c.24 0 .44-.17.47-.41l.36-2.54c.59-.24 1.13-.56 1.62-.94l2.39.96c.22.08.47 0 .59-.22l1.92-3.32c.12-.22.07-.47-.12-.61l-2.01-1.58zM12 15.6c-1.98 0-3.6-1.62-3.6-3.6s1.62-3.6 3.6-3.6 3.6 1.62 3.6 3.6-1.62 3.6-3.6 3.6z',
    visibilityOff: 'M12 7c2.76 0 5 2.24 5 5 0 .65-.13 1.26-.36 1.83l2.92 2.92c1.51-1.26 2.7-2.89 3.43-4.75-1.73-4.39-6-7.5-11-7.5-1.4 0-2.74.25-3.98.7l2.16 2.16C10.74 7.13 11.35 7 12 7zM2 4.27l2.28 2.28.46.46A11.8 11.8 0 0 0 1 12c1.73 4.39 6 7.5 11 7.5 1.55 0 3.03-.3 4.38-.84l.42.42L19.73 22 21 20.73 3.27 3 2 4.27zM7.53 9.8l1.55 1.55c-.05.21-.08.43-.08.65 0 1.66 1.34 3 3 3 .22 0 .44-.03.65-.08l1.55 1.55c-.67.33-1.41.53-2.2.53-2.76 0-5-2.24-5-5 0-.79.2-1.53.53-2.2zm4.31-.78 3.15 3.15.02-.16c0-1.66-1.34-3-3-3l-.17.01z',
    drag: 'M20 9H4v2h16V9zM4 15h16v-2H4v2z',
    add: 'M19 13h-6v6h-2v-6H5v-2h6V5h2v6h6v2z',
    check: 'M9 16.17 4.83 12l-1.42 1.41L9 19 21 7l-1.41-1.41z',
    undo: 'M12.5 8c-2.65 0-5.05.99-6.9 2.6L2 7v9h9l-3.62-3.62c1.39-1.16 3.16-1.88 5.12-1.88 3.54 0 6.55 2.31 7.6 5.5l2.37-.78C21.08 11.03 17.15 8 12.5 8z',
    home: 'M10 20v-6h4v6h5v-8h3L12 3 2 12h3v8z',
    library: 'M20 2H8c-1.1 0-2 .9-2 2v12c0 1.1.9 2 2 2h12c1.1 0 2-.9 2-2V4c0-1.1-.9-2-2-2zm-2 5h-3v5.5a2.5 2.5 0 0 1-5 0 2.5 2.5 0 0 1 2.5-2.5c.57 0 1.08.19 1.5.51V5h4v2zM4 6H2v14c0 1.1.9 2 2 2h14v-2H4V6z',
    refresh: 'M17.65 6.35A7.96 7.96 0 0 0 12 4c-4.42 0-7.99 3.58-7.99 8s3.57 8 7.99 8c3.73 0 6.84-2.55 7.73-6h-2.08A5.99 5.99 0 0 1 12 18c-3.31 0-6-2.69-6-6s2.69-6 6-6c1.66 0 3.14.69 4.22 1.78L13 11h7V4l-2.35 2.35z',
    edit: 'M3 17.25V21h3.75L17.81 9.94l-3.75-3.75L3 17.25zM20.71 7.04a1 1 0 0 0 0-1.41l-2.34-2.34a1 1 0 0 0-1.41 0l-1.83 1.83 3.75 3.75 1.83-1.83z',
    play: 'M8 5.14v13.72a1 1 0 0 0 1.52.85l10.79-6.86a1 1 0 0 0 0-1.7L9.52 4.29A1 1 0 0 0 8 5.14z',
    queue: 'M15 6H3v2h12V6zm0 4H3v2h12v-2zM3 16h8v-2H3v2zM17 6v8.18c-.31-.11-.65-.18-1-.18-1.66 0-3 1.34-3 3s1.34 3 3 3 3-1.34 3-3V8h3V6h-5z',
    album: 'M12 2C6.48 2 2 6.48 2 12s4.48 10 10 10 10-4.48 10-10S17.52 2 12 2zm0 14.5c-2.49 0-4.5-2.01-4.5-4.5S9.51 7.5 12 7.5s4.5 2.01 4.5 4.5-2.01 4.5-4.5 4.5zm0-5.5c-.55 0-1 .45-1 1s.45 1 1 1 1-.45 1-1-.45-1-1-1z',
    person: 'M12 12c2.21 0 4-1.79 4-4s-1.79-4-4-4-4 1.79-4 4 1.79 4 4 4zm0 2c-2.67 0-8 1.34-8 4v2h16v-2c0-2.66-5.33-4-8-4z',
    wifi: 'M1 9l2 2c4.97-4.97 13.03-4.97 18 0l2-2C16.93 2.93 7.08 2.93 1 9zm8 8 3 3 3-3a4.24 4.24 0 0 0-6 0zm-4-4 2 2a7.07 7.07 0 0 1 10 0l2-2C15.14 9.14 8.87 9.14 5 13z',
    signal: 'M2 22h20V2z',
    battery: 'M15.67 4H14V2h-4v2H8.33C7.6 4 7 4.6 7 5.33v15.33C7 21.4 7.6 22 8.33 22h7.33c.74 0 1.34-.6 1.34-1.33V5.33C17 4.6 16.4 4 15.67 4z'
  };
  function icon(name, size) {
    return '<svg class="yp-ic" width="' + size + '" height="' + size + '" viewBox="0 0 24 24" aria-hidden="true" focusable="false"><path d="' + ICON[name] + '"/></svg>';
  }

  // MaterialShapes approximations in a 100×100 box.
  var SHAPE = {
    bun: 'M50 4C78 4 94 16 94 32C94 42 88 47 86 50C88 53 94 58 94 68C94 84 78 96 50 96C22 96 6 84 6 68C6 58 12 53 14 50C12 47 6 42 6 32C6 16 22 4 50 4Z',
    circle: 'M50 4a46 46 0 1 1 0 92a46 46 0 1 1 0-92Z',
    ghostish: 'M50 5C75 5 92 22 92 47V84C92 93 84 97 77 91C72 87 67 87 62 91C57 96 43 96 38 91C33 87 28 87 23 91C16 97 8 93 8 84V47C8 22 25 5 50 5Z'
  };

  // Backdrop shapes as alpha masks for the small strip covers (inline data: URIs, no network).
  var MASK_CSS = Object.keys(SHAPE).map(function (k) {
    var u = 'url("data:image/svg+xml,' + encodeURIComponent('<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 100 100" preserveAspectRatio="none"><path d="' + SHAPE[k] + '"/></svg>') + '")';
    return R + '.yp-mini.m-' + k + '{-webkit-mask-image:' + u + ';mask-image:' + u + '}';
  }).join('\n');
  /** A small cover masked to its entity's backdrop shape (album Bun, song / artist circle, playlist Ghostish). */
  function mini(key, shape) {
    return '<span class="yp-mini m-' + shape + '" style="--a:var(--art-' + key + '-a);--b:var(--art-' + key + '-b)"></span>';
  }

  function art(key, v, cls) {
    return '<span class="yp-art ' + (cls || '') + '" data-v="' + v + '" style="--a:var(--art-' + key + '-a);--b:var(--art-' + key + '-b)"></span>';
  }
  function backdrop(size, shape, key, v, extra) {
    return '<span class="yp-backdrop" style="--bd:' + size + 'px;--pal:var(--art-' + key + '-a)" aria-hidden="true"><svg viewBox="0 0 100 100" preserveAspectRatio="none" focusable="false"><path d="' + SHAPE[shape] + '"/></svg>' + art(key, v, 'yp-art-in') + (extra || '') + '</span>';
  }
  function card(cls, open, kind, title, inner, key) {
    return '<button type="button" class="yp-card ' + cls + '" data-open="' + open + '" data-kind="' + kind + '" data-title="' + title + '" aria-label="' + open + '" style="--pal:var(--art-' + key + '-a)">' + inner + '</button>';
  }

  function buildActivities() {
    return '<div class="yp-acts">' +
      card('yp-hero', 'Open album: rager', 'album', 'rager',
        backdrop(96, 'bun', 'rager', 1) + '<span class="yp-col"><span class="yp-lab">Album · just now</span><span class="yp-h-title">rager</span><span class="yp-sub">Blusher</span></span>', 'rager') +
      '<div class="yp-act-row">' +
      card('yp-small', 'Open artist: Blusher', 'artist', 'Blusher',
        art('blusher', 2, 'yp-portrait') + '<span class="yp-lab yp-ell">Artist · just now</span><span class="yp-s-title yp-ell">Blusher</span>', 'blusher') +
      card('yp-wide', 'Open album: star scum city', 'album', 'star scum city',
        art('scum', 3, 'yp-cover80') + '<span class="yp-col"><span class="yp-lab">Album · 9h ago</span><span class="yp-w-title">star scum city</span><span class="yp-sub">2charm</span></span>', 'scum') +
      '</div>' +
      card('yp-stripcard', 'Open artist: 2charm', 'artist', '2charm',
        '<span class="yp-ell"><b>2charm</b> · Artist</span><span class="yp-lab" style="flex:none">Artist · 9h ago</span>', 'charm') +
      '</div>';
  }

  var REDIS = [
    ['emotion', 'Emotion', 'Carly Rae Jepsen', '9.0', '7 months', '2025.11', 23],
    ['heaven', 'Heaven or Las Vegas', 'Cocteau Twins', '8.5', '5 months', '2025.06', 41],
    ['blonde', 'Blonde', 'Frank Ocean', '8.0', '4 months', '2024.12', 17]
  ];
  function buildRediscover() {
    return '<div class="yp-shelf yp-redis-shelf" data-fade="1">' + REDIS.map(function (r) {
      return card('yp-redis', 'Open album: ' + r[1], 'album', r[1],
        backdrop(104, 'bun', r[0], 1) + '<span class="yp-col"><span class="yp-eyebrow">Rated ' + r[3] + ' · Not played in Yoin for ' + r[4] + '</span><span class="yp-r-title">' + r[1] + '</span><span class="yp-sub">' + r[2] + '</span><span class="yp-fnote">First played ' + r[5] + ' · ' + r[6] + ' plays</span></span>', r[0]);
    }).join('') + '</div>';
  }

  var RA_TRACKS = [['eversince', 'Ever Since', 'Moon In June'], ['jiji', 'ready and…', 'JIJI'], ['olivia', 'Pina Colada', 'Olivia Marsh'], ['m2m', 'M2M', 'CODY JON']];
  var RA_ALBUMS = [['loneliest', 'The Loneliest Time', 'Carly Rae Jepsen'], ['amber', 'AMBER', 'Kilo…'], ['j3', 'Titanic Rising', 'Weyes Blood']];
  function buildRecentlyAdded() {
    var tiles = RA_TRACKS.map(function (t, i) {
      return card('yp-tile yp-bare', 'Play: ' + t[1], 'track', t[1],
        art(t[0], (i % 3) + 1, 'yp-cover52') + '<span class="yp-col"><span class="yp-tt">' + t[1] + '</span><span class="yp-lab">' + t[2] + '</span></span>', t[0]);
    }).join('');
    var albums = RA_ALBUMS.map(function (a) {
      return card('yp-ra-album yp-bare', 'Open album: ' + a[1], 'album', a[1],
        backdrop(82, 'bun', a[0], 2) + '<span class="yp-tt yp-ell">' + a[1] + '</span><span class="yp-lab yp-ell">' + a[2] + '</span>', a[0]);
    }).join('');
    return '<div class="yp-shelf yp-ra-shelf"><div class="yp-ra-grid">' + tiles + '</div>' + albums + '</div>';
  }

  var JBI = [
    ['album', 'j1', 'bun', 'Melodrama', 'Lorde', 1],
    ['track', 'j2', 'circle', 'Motion Sickness', 'Phoebe Bridgers', 2],
    ['playlist', 'j6', 'ghostish', 'Late Night Drive', 'Playlist · 32 songs', 3],
    ['album', 'j4', 'bun', 'Ctrl', 'SZA', 1],
    ['track', 'j5', 'circle', 'Bunny Is A Rider', 'Caroline Polachek', 3],
    ['playlist', 'j7', 'ghostish', 'Sunday Slow', 'Playlist · 18 songs', 2],
    ['album', 'j3', 'bun', 'Titanic Rising', 'Weyes Blood', 1],
    ['album', 'j8', 'bun', 'Ants From Up There', 'Black Country, New Road', 2],
    ['track', 'j9', 'circle', 'Kyoto', 'Phoebe Bridgers', 1]
  ];
  // 3-column grid, 4 rows = 12 cells: the 1×2 memory card + 9 1×1 cells fill 11, so the last row has one
  // free slot — the only situation where the §4 hint tile appears (it never pushes content out).
  function buildJumpBackIn() {
    var html = card('yp-w12 yp-bare', 'Open memory: 伪装的西瓜与失', 'album', '伪装的西瓜与失',
      backdrop(100, 'bun', 'melon', 1) + '<span class="yp-col"><span class="yp-rating">6.0</span><span class="yp-basis">Aug 21</span><span class="yp-comment">伪装的西瓜与失</span></span>', 'melon');
    html += JBI.map(function (j) {
      var song = j[0] === 'track';
      var verb = song ? 'Play: ' : j[0] === 'playlist' ? 'Open playlist: ' : 'Open album: ';
      return card('yp-w11 yp-bare', verb + j[3], j[0], j[3],
        backdrop(100, j[2], j[1], j[5], song ? '<span class="yp-play">' + icon('play', 16) + '</span>' : '') +
        '<span class="yp-jt yp-ell">' + j[3] + '</span><span class="yp-js yp-ell">' + j[4] + '</span>', j[1]);
    }).join('');
    // §4: the tile only exists when the last row really has a free slot — it never opens a new row
    var used = 2 + JBI.length;
    if (used % JBI_COLS !== 0) html += '<button type="button" class="yp-hint-tile">' + icon('drag', 24) + '<span>Press and hold to arrange</span></button>';
    return '<div class="yp-jbi">' + html + '</div>';
  }

  /** §2.3 ②: the first 3 covers each section already shows (Activities 3 entries, JBI 3 cells, RA 3 albums, Rediscover 3). */
  var STRIP_COVERS = {
    activities: [['rager', 'bun'], ['blusher', 'circle'], ['scum', 'bun']],
    rediscover: REDIS.slice(0, 3).map(function (r) { return [r[0], 'bun']; }),
    recently_added: RA_ALBUMS.slice(0, 3).map(function (a) { return [a[0], 'bun']; }),
    jump_back_in: [['melon', 'bun']].concat(JBI.slice(0, 2).map(function (j) { return [j[1], j[2]]; }))
  };
  var BUILDERS = { activities: buildActivities, rediscover: buildRediscover, recently_added: buildRecentlyAdded, jump_back_in: buildJumpBackIn };

  function blockHtml(id) {
    var s = SECTIONS[id];
    return '<section class="yp-block" data-id="' + id + '" aria-label="' + s.title + '">' +
      '<div class="yp-lift-shadow"></div><div class="yp-plate"><div class="yp-plate-tint"></div></div>' +
      '<div class="yp-body"><div class="yp-sec-head"><h2 class="yp-sec-title">' + s.title + '</h2>' +
      '<div class="yp-badges">' +
      '<button type="button" class="yp-badge yp-hide" data-id="' + id + '" aria-label="Hide ' + s.title + '"><span class="yp-bv">' + icon('visibilityOff', 18) + '</span></button>' +
      '<button type="button" class="yp-badge yp-handle" data-id="' + id + '" aria-label="Reorder ' + s.title + ' (drag, or use arrow keys)">' + icon('drag', 24) + '</button>' +
      '</div></div>' + BUILDERS[id]() + '</div></section>';
  }

  /* ════════════════════════════ 8. DOM ════════════════════════════ */

  /** An option button; the recommended one carries "（推荐）" in its title and starts pressed (defaults = recommendations). */
  function optHtml(group, v, title, desc) {
    return '<button type="button" class="yp-opt" data-group="' + group + '" data-v="' + v + '" aria-pressed="' + (opts[group] === v ? 'true' : 'false') + '"><span class="o-t">' + title +
      '</span><span class="o-d">' + desc + '</span></button>';
  }
  function stripLabelHtml(id) {
    var covers = STRIP_COVERS[id] || [];
    return '<div class="yp-strip-label" data-id="' + id + '">' +
      (covers.length ? '<span class="yp-strip-covers">' + covers.map(function (c) { return mini(c[0], c[1]); }).join('') + '</span>' : '') +
      '<span class="t">' + SECTIONS[id].title + '</span>' + icon('drag', 24) + '</div>';
  }
  function switchHtml(key, label, desc, on) {
    return '<button type="button" class="yp-switch" role="switch" data-key="' + key + '" aria-checked="' + (on ? 'true' : 'false') + '"><span><span class="sw-l">' + label + '</span><span class="sw-d">' + desc + '</span></span><span class="yp-track" aria-hidden="true"><span class="yp-thumb"></span></span></button>';
  }

  host.innerHTML = '<style>' + CSS + '\n' + MASK_CSS + '</style>' +
    '<div class="yp-root">' +
    '<div class="yp-phone-col">' +
    '<div class="yp-haptic" aria-hidden="true"><span class="yp-hdot"></span><span class="yp-hname">触感：—</span></div>' +
    '<div class="yp-phone"><div class="yp-screen" role="region" aria-label="Yoin Home 原型手机屏幕">' +
    '<div class="yp-scroll"><div class="yp-feed">' +
    '<header class="yp-header"><div class="yp-title-stack"><h1 class="yp-t-home">Home</h1><div class="yp-t-edit" aria-hidden="true">Edit Home</div></div>' +
    '<div class="yp-head-icons"><button type="button" class="yp-icon-btn yp-mem" aria-label="Open Memories">' + icon('chevronDown', 24) + '</button>' +
    '<button type="button" class="yp-icon-btn yp-gear" aria-label="Settings">' + icon('settings', 24) + '</button></div>' +
    '<div class="yp-hint" aria-hidden="true">Drag to reorder</div></header>' +
    '<div class="yp-sections">' + DEFAULT_ORDER.map(blockHtml).join('') + '</div>' +
    '<div class="yp-tray" aria-label="Hidden sections"><h2 class="yp-tray-head">Hidden</h2><p class="yp-tray-empty">Hidden sections appear here</p><div class="yp-tray-rows">' +
    DEFAULT_ORDER.map(function (id) {
      return '<button type="button" class="yp-tray-row" data-id="' + id + '" style="display:none" aria-label="Show ' + SECTIONS[id].title + '"><span class="yp-col"><span class="t">' + SECTIONS[id].title + '</span><span class="s">' + SECTIONS[id].support + '</span></span><span class="yp-plus" aria-hidden="true"><span>' + icon('add', 20) + '</span></span></button>';
    }).join('') +
    '</div><button type="button" class="yp-reset" disabled>' + icon('refresh', 18) + 'Reset Home</button></div>' +
    '<div class="yp-foot"><div class="yp-empty"><h3>Your Home is empty</h3><p>Press and hold anywhere, or tap Edit Home, to bring sections back.</p></div>' +
    '<button type="button" class="yp-edit-entry">' + icon('edit', 18) + 'Edit Home</button></div>' +
    '</div></div>' +
    '<div class="yp-status" aria-hidden="true"><span>9:41</span><span class="yp-st-icons">' + icon('wifi', 15) + icon('signal', 14) + icon('battery', 15) + '</span></div>' +
    '<div class="yp-carry" aria-hidden="true"><div class="yp-hole"></div><div class="yp-strip-shadow"></div>' +
    DEFAULT_ORDER.map(function (id) {
      return '<div class="yp-strip-plate" data-id="' + id + '"></div>' + stripLabelHtml(id);   // no supporting text on strips
    }).join('') + '</div>' +
    '<div class="yp-edge" aria-hidden="true"></div>' +
    '<div class="yp-back-ind" aria-hidden="true">' + icon('chevronLeft', 22) + '</div>' +
    '<nav class="yp-bar" aria-label="Bottom bar">' +
    '<button type="button" class="yp-slot yp-slot-l" aria-label="Home"><span class="yp-slot-bg yp-bg-nav"></span><span class="yp-slot-bg yp-bg-edit"></span>' +
    '<span class="yp-layer yp-l-home">' + icon('home', 22) + '</span>' +
    '<span class="yp-layer yp-l-edit yp-l-undo">' + icon('undo', 20) + 'Undo</span>' +
    '<span class="yp-layer yp-l-edit yp-l-add">' + icon('add', 20) + 'Add</span>' +
    '<span class="yp-layer yp-l-edit yp-l-dis">' + icon('undo', 20) + 'Undo</span></button>' +
    '<button type="button" class="yp-pill" aria-label="Now playing: Running To You by Blusher">' + art('run', 1) + '<span class="yp-col"><span class="yp-pill-t">RUNNING TO YOU</span><span class="yp-pill-s">Blusher</span></span></button>' +
    '<button type="button" class="yp-slot yp-slot-r" aria-label="Library"><span class="yp-slot-bg yp-bg-primary"></span>' +
    '<span class="yp-layer yp-l-lib">' + icon('library', 22) + '</span>' +
    '<span class="yp-layer yp-l-done">' + icon('check', 20) + 'Done</span></button>' +
    '</nav>' +
    '<div class="yp-menu" role="menu" aria-label="Card actions">' +
    '<div class="yp-menu-group"><button type="button" class="yp-menu-item" role="menuitem" data-act="next">' + icon('queue', 20) + 'Play next</button>' +
    '<button type="button" class="yp-menu-item" role="menuitem" data-act="go">' + icon('album', 20) + '<span class="yp-go-label">Go to album</span></button></div>' +
    '<div class="yp-menu-group"><button type="button" class="yp-menu-item" role="menuitem" data-act="edit">' + icon('edit', 20) + 'Edit Home</button></div></div>' +
    '<div class="yp-toast" role="status" aria-live="polite"></div>' +
    '<span class="yp-sr yp-announce" aria-live="polite"></span>' +
    '</div></div></div>' +
    // ── control panel ──
    '<aside class="yp-panel" aria-label="原型控制面板">' +
    '<section class="yp-pcard"><h2 class="yp-ph">Home 编辑态 · 可交互原型</h2><p class="yp-pp">长按任意卡片或空白 400ms 进入编辑。按住卡片 200ms 后，块会轻微下沉，并从按点预显一小块底板；过阈值时底板从这里长满整块，松手放弃则原路缩回。编辑态里拖把手、或按住块 200ms 后拖动来排序；点眼睛隐藏、点托盘行的 + 恢复；从手机左缘 16px 向右拖 = 预测性返回；<kbd>Esc</kbd> 退出。把手获得焦点时可用 <kbd>↑</kbd><kbd>↓</kbd> 移动。</p>' +
    '<p class="yp-pp yp-note">发现性（§4，Q6b (a)）：Jump Back In 末行空位里的静态提示格（点按进入编辑，首次编辑后永久消失）、feed 末尾常驻的 “Edit Home”、前 2 次编辑时 header 的 “Drag to reorder”。点“重置原型”可再看。Jump Back In 里的歌曲格封面角上有播放三角（Q11 (b)），点了直接播放。</p></section>' +
    '<section class="yp-pcard" aria-labelledby="yp-q1"><h3 class="yp-gl" id="yp-q1">Q1 形态</h3>' +
    optHtml('q1', 'a', '(a) 一颤 Kick', '入场时每块（或每张卡）带初速颤一下（kick 弹簧 ζ0.3 / k450，首峰 = a·Θ），约 0.5s 后静止；之后只在点块（0.5）、放下（0.7，被挤开的邻居 0.35）时再颤。静止时零开销。') +
    optHtml('q1', 'b', '(b) 轻摆后静默 IdleSettle（推荐）', '2.4Hz（按 section id 去谐 ±7%）的轻摆，全页一个时钟。6 秒内手机里没有任何按下，包络 E 就用 defaultSpatial 约 300ms 收到 0；任何按下用 fastSpatial 拉回 1 并重新计时。拖动时其余块 E × 0.6，被拖块为 0。kick 照样叠加在上面。') +
    optHtml('q1', 'c', '(c) 一直摆到退出 Continuous', '与 (b) 相同，只是没有 6 秒静默：iOS 式，编辑态全程都在摆，退出时随 P 淡出。') +
    '</section>' +
    '<section class="yp-pcard" aria-labelledby="yp-q1t"><h3 class="yp-gl" id="yp-q1t">Q1 谁在动</h3>' +
    optHtml('q1t', 'block', '整块', '每个 section（底板、内容、徽标一起）绕块中心转。Θ_i = clamp(atan(2px ÷ 半对角线), 0.15°, 0.8°)，(b)(c) 的轻摆振幅 = 0.6·Θ_i。') +
    optHtml('q1t', 'card', '卡片级（推荐）', '块内每张卡（封面连同它自己的文字和底色）各自绕卡中心转，A_c = clamp(1.1° × 100px ÷ 卡宽, 0.35°, 1.1°)；底板、标题、徽标不动。同一块内同频同相、正反方向按卡的序号交替；块与块按 section id 错开相位和频率。没有卡的占位块整块转。') +
    '<label class="yp-slider"><span>振幅倍率（轻摆、kick 和蓄力下沉；1× = spec 值）</span><span class="yp-slider-row"><input type="range" class="yp-amp" min="1" max="4" step="0.25" value="1" aria-label="振幅倍率"><output class="yp-amp-out">1.00×</output></span></label>' +
    '</section>' +
    '<section class="yp-pcard" aria-labelledby="yp-q2"><h3 class="yp-gl" id="yp-q2">Q2 卡片长按的语义</h3>' +
    optHtml('q2', 'a', '(a) 直接进入编辑，并拿起该 section（推荐）', '过阈值即进入编辑，并原地拿起卡片所在的 section；底板从按点长出来，交代“这张卡属于这一块”。不松手接着拖就能排序。') +
    optHtml('q2', 'b', '(b) 两段式：松手出卡片菜单', '长按到 400ms 先给一次触感；此时松手弹出卡片菜单（Play next / Go to album｜Edit Home）；按住移动、或再按住 350ms，才进入编辑并拿起。长按空白或标题仍直接进编辑。') +
    '</section>' +
    '<section class="yp-pcard" aria-labelledby="yp-q3"><h3 class="yp-gl" id="yp-q3">Q3 拖动形态</h3>' +
    optHtml('q3', 'a', '(a) 签条（推荐）', '手指一动，所有块收成签条叠在指下：左起 3 张该块的真实小封面（按实体类型取形状、压叠 30%），然后是标题和把手。短距离排序（越过半格即换位、邻居弹簧让位、落点洞跟随），首末越界有橡皮筋；松手后落定、提交、展开回原位。') +
    optHtml('q3', 'c', '(c) 原尺寸 + 自动滚动', '被拿起的块按原尺寸跟手，邻居弹簧让位；手指进入上下边缘 64px 内自动滚动（越靠边越快，最快约 900px/s）。在手机上把 Recently Added 拖过 Jump Back In 要走很远。') +
    '<p class="yp-pp yp-note">(b) “手机签条、平板原尺寸” 只在平板上和 (a) 不同，这个只有手机屏的原型演示不了。</p>' +
    '</section>' +
    '<section class="yp-pcard" aria-label="动效开关">' +
    switchHtml('slow', '慢动作 0.25×', '所有弹簧和抖动按 1/4 速度播放（长按阈值不变）', false) +
    switchHtml('reduced', '减少动效', '不抖、不蓄力；所有空间弹簧换成快速 effects 渐变（默认跟随系统设置）', opts.reduced) +
    switchHtml('band', '交界带内不抖', 'spec §2.2.4：整块模式按块矩形、卡片级按每张卡判断；压到状态栏带或底栏带时振幅为 0，离交界 48px 内线性衰减', true) +
    '</section>' +
    '<section class="yp-pcard" aria-label="操作"><div class="yp-actions">' +
    '<button type="button" class="yp-btn filled yp-act-replay">重新进入编辑</button>' +
    '<button type="button" class="yp-btn yp-act-back">系统返回</button>' +
    '<button type="button" class="yp-btn yp-act-reset">重置原型</button></div></section>' +
    '<section class="yp-pcard" aria-labelledby="yp-st"><h3 class="yp-gl" id="yp-st">状态</h3><dl class="yp-readout">' +
    '<dt>模式</dt><dd class="ro-mode">normal</dd>' +
    '<dt>P</dt><dd><span class="ro-p">0.000</span><span class="yp-meter" aria-hidden="true"><i class="ro-pbar"></i></span></dd>' +
    '<dt>蓄力 / 拿起 / 折叠</dt><dd class="ro-clf">0.00 / 0.00 / 0.00</dd>' +
    '<dt>轻摆包络 E</dt><dd class="ro-env">0.00</dd>' +
    '<dt>顺序</dt><dd class="ro-order"></dd>' +
    '<dt>撤销栈 / 托盘</dt><dd class="ro-undo">0 / 0</dd></dl></section>' +
    '<section class="yp-pcard" aria-labelledby="yp-hl"><h3 class="yp-gl" id="yp-hl">触感日志（最新在上，最多 8 条）</h3>' +
    '<p class="yp-pp yp-log-note">Pixel Tablet 没有振动马达：每个触感都配了画面上的对应动作</p><ol class="yp-log"></ol></section>' +
    '</aside></div>';

  var rootEl = $('.yp-root');
  var screenEl = $('.yp-screen');
  var scrollEl = $('.yp-scroll');
  var sectionsEl = $('.yp-sections');
  var trayEl = $('.yp-tray'), trayRowsEl = $('.yp-tray-rows'), trayEmptyEl = $('.yp-tray-empty'), resetBtn = $('.yp-reset');
  var footEl = $('.yp-foot'), emptyEl = $('.yp-empty'), editEntryBtn = $('.yp-edit-entry');
  var headerEl = $('.yp-header'), homeTitleEl = $('.yp-t-home'), editTitleEl = $('.yp-t-edit'), headIconsEl = $('.yp-head-icons'), hintEl = $('.yp-hint');
  var carryEl = $('.yp-carry'), holeEl = $('.yp-hole'), stripShadowEl = $('.yp-strip-shadow');
  var edgeEl = $('.yp-edge'), backIndEl = $('.yp-back-ind');
  var barEl = $('.yp-bar'), slotL = $('.yp-slot-l'), slotR = $('.yp-slot-r'), pillEl = $('.yp-pill');
  var bgNav = $('.yp-bg-nav'), bgEdit = $('.yp-bg-edit'), bgPrimary = $('.yp-bg-primary');
  var lHome = $('.yp-l-home'), lUndo = $('.yp-l-undo'), lAdd = $('.yp-l-add'), lDis = $('.yp-l-dis'), lLib = $('.yp-l-lib'), lDone = $('.yp-l-done');
  var menuEl = $('.yp-menu'), goLabelEl = $('.yp-go-label');
  var toastEl = $('.yp-toast'), announceEl = $('.yp-announce');
  var hDotEl = $('.yp-hdot'), hNameEl = $('.yp-hname');
  var logEl = $('.yp-log');
  var hintTileEl = $('.yp-hint-tile');
  var ro = { mode: $('.ro-mode'), p: $('.ro-p'), pbar: $('.ro-pbar'), clf: $('.ro-clf'), env: $('.ro-env'), order: $('.ro-order'), undo: $('.ro-undo') };

  var blockEl = {}, plateEl = {}, tintEl = {}, shadowEl = {}, hideBtn = {}, handleBtn = {}, rowEl = {}, strips = {};
  /**
   * Q1 卡片级: every card of a block, with its in-block position (read at layout time only, like onPlaced),
   * its own amplitude A_c and an alternating direction by in-block index parity.
   */
  var cardsOf = {};
  DEFAULT_ORDER.forEach(function (id) {
    var b = $('.yp-block[data-id="' + id + '"]');
    blockEl[id] = b;
    cardsOf[id] = $all('.yp-card', b).map(function (el, k) {
      return { el: el, alt: (k % 2) ? -1 : 1, relTop: 0, h: 0, w: 0, A: 1.1, kick: new Spring(0, 0.01) };
    });
    plateEl[id] = $('.yp-plate', b);
    tintEl[id] = $('.yp-plate-tint', b);
    shadowEl[id] = $('.yp-lift-shadow', b);
    hideBtn[id] = $('.yp-hide', b);
    handleBtn[id] = $('.yp-handle', b);
    rowEl[id] = $('.yp-tray-row[data-id="' + id + '"]');
    strips[id] = { plate: $('.yp-strip-plate[data-id="' + id + '"]'), label: $('.yp-strip-label[data-id="' + id + '"]') };
  });

  /* ════════════════════════════ 9. Layout model (in-memory HomeLayout) ════════════════════════════ */

  function defaultLayout() { return DEFAULT_ORDER.map(function (id) { return { id: id, enabled: true }; }); }
  function cloneLayout(l) { return l.map(function (x) { return { id: x.id, enabled: x.enabled }; }); }
  function isDefault(l) {
    return l.length === DEFAULT_ORDER.length && l.every(function (x, i) { return x.id === DEFAULT_ORDER[i] && x.enabled; });
  }
  function enabledOrder(l) { return l.filter(function (x) { return x.enabled; }).map(function (x) { return x.id; }); }
  function hiddenIds(l) { return l.filter(function (x) { return !x.enabled; }).map(function (x) { return x.id; }); }
  function withEnabled(l, id, en) { return l.map(function (x) { return { id: x.id, enabled: x.id === id ? en : x.enabled }; }); }
  /** Refill enabled slots with a new order; disabled entries keep their absolute slot (so "Show" returns home). */
  function withEnabledOrder(l, order) {
    var out = cloneLayout(l), j = 0;
    for (var i = 0; i < out.length; i++) if (out[i].enabled) out[i] = { id: order[j++], enabled: true };
    return out;
  }
  function pushUndo() {
    state.undo.push(cloneLayout(state.layout));
    if (state.undo.length > UNDO_MAX) state.undo.shift();
  }

  /* ════════════════════════════ 10. Geometry (read once at events, never inside the loop) ════════════════════════════ */

  /** 整块: Θ_i from a 2px corner displacement over the plate's half-diagonal, clamped to [0.15°, 0.8°]. */
  function thetaFor(w, h) { return clamp(Math.atan(2 / (0.5 * Math.hypot(w, h))) * 180 / Math.PI, 0.15, 0.8); }
  /** 卡片级: A_c = clamp(1.1° × 100px / w_card, 0.35°, 1.1°) — about 1px at the card's outer edge. */
  function cardAmp(w) { return clamp(1.1 * 100 / Math.max(w, 1), 0.35, 1.1); }
  /** Layout-box offset of el inside anc (ignores transforms, so a mid-wiggle measure is still exact). */
  function offsetWithin(el, anc) {
    var y = 0;
    while (el && el !== anc) { y += el.offsetTop; el = el.offsetParent; }
    return y;
  }

  function measureBlocks() {
    geo.screenW = screenEl.clientWidth;
    geo.screenH = screenEl.clientHeight;
    geo.barTop = barEl.offsetTop;
    geo.barW = barEl.clientWidth;
    geo.contentW = geo.screenW - 2 * PAGE_PAD;
    geo.scrollTop = scrollEl.scrollTop;
    geo.maxScroll = Math.max(0, scrollEl.scrollHeight - scrollEl.clientHeight);
    state.displayed.forEach(function (id) {
      var el = blockEl[id], w = el.offsetWidth, h = el.offsetHeight;
      geo.blocks[id] = { top: el.offsetTop, h: h, w: w, theta: thetaFor(w + 2 * PLATE_H, h + 2 * PLATE_V) };
      cardsOf[id].forEach(function (c) {
        c.relTop = offsetWithin(c.el, el); c.h = c.el.offsetHeight; c.w = c.el.offsetWidth; c.A = cardAmp(c.w);
      });
    });
    geo.trayTop = trayEl.offsetTop;
    geo.footTop = footEl.offsetTop;
  }

  function readTops() {
    var t = {};
    state.displayed.forEach(function (id) { t[id] = blockEl[id].offsetTop; });
    t.__tray = trayEl.offsetTop; t.__foot = footEl.offsetTop;
    return t;
  }

  function nearestBlock(screenY) {
    var y = screenY + geo.scrollTop, best = null, bd = Infinity;
    state.displayed.forEach(function (id) {
      var b = geo.blocks[id]; if (!b) return;
      var d = y < b.top ? b.top - y : y > b.top + b.h ? y - b.top - b.h : 0;
      if (d < bd) { bd = d; best = id; }
    });
    return best;
  }

  function inViewport(id) {
    var b = geo.blocks[id]; if (!b) return false;
    var top = b.top - geo.scrollTop;
    return top + b.h > STATUS_H && top < geo.barTop;
  }

  /* ════════════════════════════ 11. Haptics, toast, announce ════════════════════════════ */

  /** §2.8 haptic table: the visual twin each haptic moment carries (Pixel Tablet has no vibrator). */
  var TWIN = {
    LONG_PRESS: 'lift 1.02 + 底板从按点长出',
    DRAG_START: 'lift + 阴影',
    SEGMENT_TICK: '邻居让位 + 落点洞移动',
    GESTURE_THRESHOLD_ACTIVATE: '橡皮筋阻尼',
    CONFIRM: '展开 + 放下 kick / 底栏 morph',
    TOGGLE_OFF: '缩放淡出',
    TOGGLE_ON: '淡入 + 0.35Θ kick',
    REJECT: '各块回位',
    KEYBOARD_TAP: '栏按钮的按压形变'
  };
  var logRows = [];
  var t0 = performance.now();
  /** twin: optional per-call override of the §2.8 visual twin when the visible equivalent differs from the table row. */
  function haptic(name, reason, twin) {
    try {
      var ua = navigator.userActivation;
      if (typeof navigator.vibrate === 'function' && (!ua || ua.hasBeenActive)) navigator.vibrate(10);
    } catch (err) { /* vibration blocked by the host page: harmless */ }
    logRows.unshift({ name: name, reason: reason, twin: twin || TWIN[name] || '', t: (performance.now() - t0) / 1000 });
    if (logRows.length > 8) logRows.length = 8;
    renderLog();
    hNameEl.textContent = '触感：' + name;
    try {
      if (hDotEl.animate) {
        hDotEl.animate(opts.reduced
          ? [{ opacity: 1 }, { opacity: 0.3 }]
          : [{ transform: 'scale(1)', opacity: 1 }, { transform: 'scale(2.4)', opacity: 0.3 }], { duration: 480, easing: 'cubic-bezier(.2,0,0,1)' });
      }
    } catch (err) { /* WAAPI unavailable: the log still shows it */ }
  }
  function renderLog() {
    if (!logRows.length) { logEl.innerHTML = '<li class="yp-log-empty">还没有触感事件。长按手机里的任意位置试试。</li>'; return; }
    logEl.innerHTML = logRows.map(function (r) {
      return '<li><code>' + r.name + '</code><span class="why">' + r.reason +
        (r.twin ? '<span class="twin">视觉孪生：' + r.twin + '</span>' : '') +
        '</span><span class="tm">' + r.t.toFixed(1) + 's</span></li>';
    }).join('');
  }

  var toastTimer = 0;
  function toast(text) {
    toastEl.textContent = text;
    toastA.animateTo(1, 'defaultEffects');
    clearTimeout(toastTimer);
    toastTimer = setTimeout(function () { toastA.animateTo(0, 'defaultEffects'); }, 1500);
  }
  function announce(text) { announceEl.textContent = ''; setTimeout(function () { announceEl.textContent = text; }, 30); }

  /* ════════════════════════════ 12. Edit controller (sole writer of P, mode, kicks) ════════════════════════════ */

  function enterEdit(originId) {
    if (state.mode === 'edit') return false;
    closeMenu();
    state.mode = 'edit';
    state.editSessions += 1;
    state.showHint = state.editSessions <= HINT_SESSIONS;
    state.kicked = {};
    state.undo = [];
    state.plateFrom = null;                     // a long-press entry latches its own right after this call
    rootEl.classList.add('yp-editing');
    // §2.1.4-7 / §4: the JBI hint tile leaves with the first edit session and never comes back (in memory).
    // It sits in the grid's last free slot, so removing it never changes the block's height.
    if (hintTileEl && !state.hintTileGone) {
      state.hintTileGone = true;
      if (document.activeElement === hintTileEl) slotR.focus({ preventScroll: true });
      hintTileEl.style.pointerEvents = 'none';
      hintTileEl.setAttribute('tabindex', '-1');
      hintTileEl.setAttribute('aria-hidden', 'true');
      hintTileA.animateTo(0, 'fastEffects', undefined, function () { if (state.hintTileGone) hintTileEl.style.display = 'none'; });
    }
    // structure (layout): footer out, tray in — happens at the commit moment, faded with effects springs
    footEl.style.display = 'none';
    if (trayEl.style.display !== 'block') { trayEl.style.display = 'block'; trayA.snapTo(0); }
    trayA.animateTo(1, 'defaultEffects');
    measureBlocks();
    // spec §2.2.3: the hint line only shows when it fits beside "Edit Home" (read once, at enter)
    if (state.showHint) state.showHint = editTitleEl.offsetWidth + 16 + hintEl.offsetWidth <= headerEl.clientWidth;
    state.i0 = Math.max(0, state.displayed.indexOf(originId));
    P.animateTo(1, 'stageSettle');
    state.lastTouch = performance.now();
    setEnvelope();
    updateLeftSlot();
    updateBarAria();
    announce('Edit Home. Drag to reorder.');
    requestLoop();
    return true;
  }

  function exitEdit(reason) {
    if (state.mode !== 'edit') return;
    // ⑬: a back / Done / Escape that arrives mid-drag first drops at the current slot, then exits once settled
    if (state.carry) { state.exitAfterCarry = reason || 'back'; forceDrop(); return; }
    disownGesture();
    if (reason === 'done') haptic('CONFIRM', 'Done：退出编辑（改动已逐次生效）');
    state.mode = 'normal';
    rootEl.classList.remove('yp-editing');
    P.animateTo(0, 'stageSettle');
    if (state.plateFrom) state.plateFrom.latch = 0;   // a mid-growth exit retreats with P instead of holding 0.4
    if (state.liftId) dropLift(0);
    state.undo = [];
    setEnvelope();
    trayA.animateTo(0, 'fastEffects', undefined, function () {
      if (state.mode !== 'normal') return;
      trayEl.style.display = 'none';
      footEl.style.display = '';
      footA.snapTo(0); footA.animateTo(1, 'defaultEffects');
      measureBlocks();
    });
    updateLeftSlot();
    updateBarAria();
    if (host.contains(document.activeElement) && document.activeElement.closest('.yp-badges, .yp-tray')) slotR.focus({ preventScroll: true });
    announce('Home');
  }

  function snapExit() {
    disownGesture();
    state.mode = 'normal';
    rootEl.classList.remove('yp-editing');
    P.snapTo(0); trayA.snapTo(0); wigEnv.snapTo(0);
    state.plateFrom = null;
    trayEl.style.display = 'none';
    footEl.style.display = ''; footA.snapTo(1);
    state.undo = [];
    if (state.liftId) { lift.snapTo(0); liftTint.snapTo(0); state.liftId = null; }
    updateLeftSlot();
    updateBarAria();
    measureBlocks();
  }

  /** Predictive back (P1): draw-only scrub, full-range eased, no cap, no chase. */
  function scrubBack(progress) { if (state.mode !== 'edit') return; P.snapTo(1 - BACK_EASE(progress)); requestLoop(); }
  function settleBack(commit) {
    if (state.mode !== 'edit') return;           // exited by another path mid-gesture: nothing to settle
    if (commit) exitEdit('back'); else P.animateTo(1, 'stageSettle');
  }

  function stableSign(id) { return (hashStr(id) & 1) ? -1 : 1; }

  /**
   * Kick impulse — adds on top of every Q1 form. The spring is normalised: v = a·31.6 /s makes the first
   * peak = a, and the draw multiplies by Θ_i (整块) or A_c (卡片级, alternating per card). A block or card
   * still ringing gets the new impulse along its current velocity.
   */
  function kickSpring(k, a, sign) {
    var v = a * KICK_GAIN;
    if (k.active && Math.abs(k.vel) > 1e-3) v = k.vel + Math.sign(k.vel) * v;
    else v *= sign;
    k.animateTo(0, 'kick', v);
  }
  function impulse(id, a) {
    if (opts.reduced || !geo.blocks[id]) return;
    kickSpring(per[id].kick, a, stableSign(id));
  }
  /** §2.2.5 "该块（或被点的卡）": in 卡片级 a tap kicks only the card under the finger. */
  function impulseCard(id, cardEl, a) {
    if (opts.reduced || !geo.blocks[id]) return;
    var list = cardsOf[id] || [], c = null;
    for (var i = 0; i < list.length; i++) if (list[i].el === cardEl) c = list[i];
    if (!c || opts.q1t !== 'card') { impulse(id, a); return; }
    kickSpring(c.kick, a, stableSign(id) * c.alt);
  }

  function pulseHandle(id) {
    if (opts.reduced) return;
    var s = per[id].pulse;
    s.snapTo(1);
    s.animateTo(1, 'fastSpatial', 11.3);   // 1 → ~1.2 → 1 with one underdamped spring
  }

  /** Edit-mode tap on a block body: 0.5 kick on the block (or the tapped card) + handle pulse; never exits. */
  function blockTap(id, cardEl) {
    if (!id) return;
    if (cardEl) impulseCard(id, cardEl, 0.5); else impulse(id, 0.5);
    pulseHandle(id);
  }

  /**
   * Sole writer of the sway envelope E (Q1 b/c). Target 1 in edit mode, SWAY_DRAG_E while a block is carried
   * (the carried one is zeroed by its own 1 − lift), 0 outside edit / in Kick form / reduced motion.
   * Rises with fastSpatial, falls with defaultSpatial (~300ms). The 6 s idle fall lives in tickers().
   */
  function setEnvelope() {
    if (state.mode !== 'edit' || opts.reduced || opts.q1 === 'a') {
      if (wigEnv.target !== 0 || wigEnv.value !== 0) wigEnv.animateTo(0, 'defaultSpatial');
      return;
    }
    var t = state.carry ? SWAY_DRAG_E : 1;
    if (wigEnv.target !== t || (!wigEnv.active && wigEnv.value !== t)) wigEnv.animateTo(t, 'fastSpatial');
  }

  /** Any pointerdown inside the phone while editing: restart the 6 s idle timer and bring E back. */
  function touchWiggle() {
    state.lastTouch = performance.now();
    setEnvelope();
  }

  /** Block-local rect of R_press: a 96px square around the press point ∩ the block rect, outset by o (§2.1.3). */
  function pressRect(id, pt, o) {
    var b = geo.blocks[id], hb = PRESS_BOX / 2;
    return { x0: clamp(pt.x - hb, 0, b.w) - o, y0: clamp(pt.y - hb, 0, b.h) - o, x1: clamp(pt.x + hb, 0, b.w) + o, y1: clamp(pt.y + hb, 0, b.h) + o };
  }
  function fullPlateRect(id) { var b = geo.blocks[id]; return { x0: -PLATE_H, y0: -PLATE_V, x1: b.w + PLATE_H, y1: b.h + PLATE_V }; }
  function lerpRect(a, b, t) { return { x0: lerp(a.x0, b.x0, t), y0: lerp(a.y0, b.y0, t), x1: lerp(a.x1, b.x1, t), y1: lerp(a.y1, b.y1, t) }; }
  /** clip-path on the plate element (whose box is the full plate) that leaves only r visible, radius 20px. */
  function plateClip(id, r) {
    var b = geo.blocks[id];
    return 'inset(' + f2(r.y0 + PLATE_V) + 'px ' + f2(b.w + PLATE_H - r.x1) + 'px ' + f2(b.h + PLATE_V - r.y1) + 'px ' + f2(r.x0 + PLATE_H) + 'px round 20px)';
  }
  /**
   * §2.1.4-4: at the threshold the entry block's plate starts from R_press⁺ (outset 4px·charge) and keeps the
   * pre-show alpha as chargeLatch (a plain float, not a new owner; cleared once P ≥ 0.6).
   */
  function latchPlate(id, local) {
    state.plateFrom = null;
    if (opts.reduced || !id || !local || !geo.blocks[id]) return;
    var cv = clamp(charge.value, 0, 1);
    state.plateFrom = { id: id, rect: pressRect(id, local, PRESS_OUTSET * cv), latch: cv };
  }

  /** Per-block ripple progress p_i = ((P − δ_i)/(1 − δ_max)) clamped. */
  function pFor(i, Pv, dMax) {
    var d = 0.06 * Math.min(Math.abs(i - state.i0), 4);
    return clamp((Pv - d) / (1 - dMax), 0, 1);
  }

  function entryKickTick() {
    if (state.mode !== 'edit') return;
    var ids = state.displayed, dMax = 0.06 * Math.min(Math.max(ids.length - 1, 0), 4);
    for (var i = 0; i < ids.length; i++) {
      var id = ids[i];
      if (state.kicked[id]) continue;
      if (pFor(i, P.value, dMax) >= 0.85) {
        state.kicked[id] = true;
        if (id !== state.liftId) impulse(id, 1.0);
      }
    }
  }

  /* ════════════════════════════ 13. Layout engine (hide/show/reset/undo + FLIP) ════════════════════════════ */

  var layoutGen = 0;
  function applyLayout(next, o) {
    o = o || {};
    var gen = ++layoutGen;
    state.layout = cloneLayout(next);
    syncTray(); updateResetBtn(); updateLeftSlot();
    var en = enabledOrder(state.layout);
    var hiding = state.displayed.filter(function (id) { return en.indexOf(id) < 0; });
    if (hiding.length && !o.instant) {
      var pending = hiding.length;
      hiding.forEach(function (id) {
        per[id].hideS.animateTo(0.96, 'fastSpatial');
        per[id].hideA.animateTo(0, 'fastEffects', undefined, function () {
          pending -= 1;
          if (pending === 0 && gen === layoutGen) commitDom(o);
        });
      });
    } else {
      commitDom(o);
    }
  }

  function commitDom(o) {
    o = o || {};
    var flip = !o.noFlip;
    var before = flip ? readTops() : null;
    var prev = state.displayed.slice();
    var focused = document.activeElement;
    var order = state.layout.map(function (x) { return x.id; });
    var cur = Array.prototype.map.call(sectionsEl.children, function (n) { return n.getAttribute('data-id'); });
    if (cur.join() !== order.join()) order.forEach(function (id) { sectionsEl.appendChild(blockEl[id]); });
    state.layout.forEach(function (x) { blockEl[x.id].style.display = x.enabled ? '' : 'none'; });
    state.displayed = enabledOrder(state.layout);
    rootEl.classList.toggle('yp-single', state.displayed.length <= 1);
    emptyEl.style.display = state.displayed.length ? 'none' : 'block';
    measureBlocks();
    if (focused && focused !== document.activeElement && host.contains(focused) && focused.offsetParent) focused.focus({ preventScroll: true });
    if (flip) {
      state.displayed.forEach(function (id) {
        if (prev.indexOf(id) < 0 || before[id] === undefined) return;
        var d = before[id] - geo.blocks[id].top;
        if (Math.abs(d) > 0.5) { var s = per[id].off; s.snapTo(s.value + d); s.animateTo(0, 'defaultSpatial'); }
      });
      var dt = before.__tray - geo.trayTop, df = before.__foot - geo.footTop;
      if (Math.abs(dt) > 0.5 && trayEl.style.display !== 'none') { trayOff.snapTo(trayOff.value + dt); trayOff.animateTo(0, 'defaultSpatial'); }
      if (Math.abs(df) > 0.5 && footEl.style.display !== 'none') { footOff.snapTo(footOff.value + df); footOff.animateTo(0, 'defaultSpatial'); }
    }
    state.displayed.forEach(function (id) {
      var s = per[id];
      if (prev.indexOf(id) < 0) {
        // A block that (re)appears mid-session (Show / Reset / Undo of a section hidden at entry) is past the
        // entry ripple: without this, entryKickTick() saw it as "not yet kicked" and added a full 1.0 entry kick
        // on top of the 0.35 show kick (1.35Θ), and gave Reset/Undo a kick the spec says they never get.
        if (state.mode === 'edit') state.kicked[id] = true;
        s.off.snapTo(0); s.kick.snapTo(0); s.hideA.snapTo(0); s.hideS.snapTo(0.96);
        s.hideA.animateTo(1, 'defaultEffects'); s.hideS.animateTo(1, 'defaultSpatial');
        if (o.kickShown && inViewport(id)) impulse(id, 0.35);
      } else if (s.hideA.target !== 1) {
        s.hideA.animateTo(1, 'defaultEffects'); s.hideS.animateTo(1, 'defaultSpatial');
      }
    });
    requestLoop();
  }

  function syncTray() {
    var hidden = hiddenIds(state.layout);
    hidden.forEach(function (id) { trayRowsEl.appendChild(rowEl[id]); });
    DEFAULT_ORDER.forEach(function (id) {
      var show = hidden.indexOf(id) >= 0, r = rowEl[id];
      if (show && r.style.display === 'none') { r.style.display = ''; per[id].rowA.snapTo(0); per[id].rowA.animateTo(1, 'defaultEffects'); }
      else if (!show) r.style.display = 'none';
    });
    trayEmptyEl.style.display = hidden.length ? 'none' : '';
  }
  function updateResetBtn() { resetBtn.disabled = isDefault(state.layout); }

  function hideSection(id) {
    if (state.mode !== 'edit' || state.carry) return;
    var hadFocus = blockEl[id].contains(document.activeElement);
    haptic('TOGGLE_OFF', '隐藏 ' + SECTIONS[id].title);
    pushUndo();
    applyLayout(withEnabled(state.layout, id, false));
    if (hadFocus) rowEl[id].focus({ preventScroll: true });
    announce(SECTIONS[id].title + ' hidden');
  }
  function showSection(id) {
    if (state.mode !== 'edit' || state.carry) return;
    var hadFocus = rowEl[id].contains(document.activeElement);
    haptic('TOGGLE_ON', '显示 ' + SECTIONS[id].title + '，回到原来的位置');
    pushUndo();
    applyLayout(withEnabled(state.layout, id, true), { kickShown: true });
    if (hadFocus) hideBtn[id].focus({ preventScroll: true });
    announce(SECTIONS[id].title + ' shown');
  }
  function resetHome() {
    if (isDefault(state.layout) || state.carry) return;
    pushUndo();
    haptic('REJECT', 'Reset Home：恢复默认布局（可撤销，所以不弹确认）');
    applyLayout(defaultLayout());
  }
  function undoStep() {
    if (!state.undo.length || state.carry) return;
    haptic('KEYBOARD_TAP', 'Undo：撤销一步（剩 ' + (state.undo.length - 1) + ' 步）');
    applyLayout(state.undo.pop());
  }
  function moveByKey(id, dir) {
    if (state.carry) return;
    var order = state.displayed.slice(), i = order.indexOf(id), j = i + dir;
    if (i < 0 || j < 0 || j >= order.length) return;
    order[i] = order[j]; order[j] = id;
    pushUndo();
    haptic('SEGMENT_TICK', '键盘移动 ' + SECTIONS[id].title + ' 到第 ' + (j + 1) + ' 位');
    applyLayout(withEnabledOrder(state.layout, order));
    handleBtn[id].focus({ preventScroll: true });
    announce(SECTIONS[id].title + ', section ' + (j + 1) + ' of ' + order.length);
  }

  /* ════════════════════════════ 14. Bar pose (pure width / alpha lerp from P) ════════════════════════════ */

  function resolveEditLeftSlot(undoDepth, trayCount) {
    return undoDepth > 0 ? 'undo' : trayCount > 0 ? 'add' : 'undoDisabled';
  }
  var leftKind = '';
  function updateLeftSlot() {
    var kind = resolveEditLeftSlot(state.undo.length, hiddenIds(state.layout).length);
    if (kind !== leftKind) {
      leftKind = kind;
      slotA.undo.animateTo(kind === 'undo' ? 1 : 0, 'fastEffects');
      slotA.add.animateTo(kind === 'add' ? 1 : 0, 'fastEffects');
      slotA.dis.animateTo(kind === 'undoDisabled' ? 1 : 0, 'fastEffects');
    }
    updateBarAria();
  }
  function updateBarAria() {
    if (state.mode === 'edit') {
      slotL.setAttribute('aria-label', leftKind === 'undo' ? 'Undo' : leftKind === 'add' ? 'Add: show hidden sections' : 'Undo (nothing to undo)');
      if (leftKind === 'undoDisabled') slotL.setAttribute('aria-disabled', 'true'); else slotL.removeAttribute('aria-disabled');
      slotR.setAttribute('aria-label', 'Done');
      pillEl.setAttribute('tabindex', '-1'); pillEl.setAttribute('aria-hidden', 'true');
    } else {
      slotL.setAttribute('aria-label', 'Home'); slotL.removeAttribute('aria-disabled');
      slotR.setAttribute('aria-label', 'Library');
      pillEl.removeAttribute('tabindex'); pillEl.removeAttribute('aria-hidden');
    }
  }

  var scrollDriving = false;
  function scrollToTray() {
    var target = clamp(geo.trayTop - 80, 0, geo.maxScroll);
    scrollSp.snapTo(scrollEl.scrollTop);
    scrollDriving = true;
    scrollSp.animateTo(target, 'defaultSpatial');
  }
  function stopScrollDrive() { if (scrollDriving) { scrollDriving = false; scrollSp.stop(); } }

  /* ════════════════════════════ 15. Card menu (Q2b) ════════════════════════════ */

  function openMenu(cardEl, blockId) {
    if (!cardEl) return;
    var kind = cardEl.getAttribute('data-kind');
    goLabelEl.textContent = kind === 'artist' ? 'Go to artist' : kind === 'playlist' ? 'Go to playlist' : 'Go to album';
    var sr = screenEl.getBoundingClientRect(), cr = cardEl.getBoundingClientRect();
    menuEl.style.display = 'flex';
    var mw = menuEl.offsetWidth, mh = menuEl.offsetHeight;
    var left = clamp(cr.left - sr.left + 12, 12, sr.width - mw - 12);
    var top = cr.bottom - sr.top + 8, oy = 0;
    if (top + mh > geo.barTop - 8) { top = cr.top - sr.top - mh - 8; oy = mh; }
    top = clamp(top, STATUS_H + 4, geo.screenH - mh - 8);
    menuEl.style.left = left + 'px';
    menuEl.style.top = top + 'px';
    menuEl.style.transformOrigin = clamp(cr.left - sr.left + 24 - left, 0, mw) + 'px ' + oy + 'px';
    state.menu = { card: cardEl, blockId: blockId, title: cardEl.getAttribute('data-title'), open: cardEl.getAttribute('data-open') };
    menuS.snapTo(0.85); menuS.animateTo(1, 'fastSpatial');
    menuA.snapTo(0); menuA.animateTo(1, 'fastEffects');
    var first = $('.yp-menu-item', menuEl);
    if (first) first.focus({ preventScroll: true });
  }
  function closeMenu(instant) {
    if (!state.menu) return;
    var prev = state.menu;
    state.menu = null;
    if (instant) { menuA.snapTo(0); menuEl.style.display = 'none'; return; }
    menuS.animateTo(0.92, 'fastSpatial');
    menuA.animateTo(0, 'fastEffects', undefined, function () { if (!state.menu) menuEl.style.display = 'none'; });
    if (menuEl.contains(document.activeElement) && prev.card) prev.card.focus({ preventScroll: true });
  }

  /* ════════════════════════════ 16. Drag engine: lift / charge ════════════════════════════ */

  function startCharge(id, local) {
    if (opts.reduced || !id) return;
    state.chargeId = id; state.chargeOrigin = local;
    charge.animateTo(1, 'slowSpatial');
  }
  function releaseCharge() { if (charge.value > 0 || charge.active) charge.animateTo(0, 'fastSpatial'); }

  function liftBlock(id, local) {
    // lift/liftTint are one shared pair: if a different block is still settling from its drop,
    // retire it first so the new block lifts from 0 instead of inheriting a half-lifted value.
    if (state.liftId && state.liftId !== id) { lift.snapTo(0); liftTint.snapTo(0); }
    state.liftId = id; state.liftOrigin = local;
    lift.animateTo(1, 'fastSpatial');
    liftTint.animateTo(1, 'fastEffects');
    per[id].kick.snapTo(0);                                   // ① "该块的 kick 和轻摆归零" — block and card kicks alike
    cardsOf[id].forEach(function (c) { c.kick.snapTo(0); });
  }
  function dropLift(kickA) {
    var id = state.liftId; if (!id) return;
    lift.animateTo(0, 'defaultSpatial', undefined, function () { if (state.liftId === id && !state.carry) state.liftId = null; });
    liftTint.animateTo(0, 'fastEffects');
    if (kickA) impulse(id, kickA);
  }

  /* ════════════════════════════ 17. Strip carry (Q3a) ════════════════════════════ */

  function plateRect(id) {
    var b = geo.blocks[id];
    return { x: PAGE_PAD - PLATE_H, y: b.top - PLATE_V - geo.scrollTop + per[id].off.value, w: geo.contentW + 2 * PLATE_H, h: b.h + 2 * PLATE_V };
  }
  /** B_i: visible plate ∩ safe area; off-screen blocks start as an invisible strip at their side's edge (scale .96). */
  function stripStartRect(id, safe, hs, w, x) {
    var r = plateRect(id), top = Math.max(r.y, safe.top), bot = Math.min(r.y + r.h, safe.bottom);
    if (bot - top > 1) return { x: r.x, y: top, w: r.w, h: bot - top, a: 1 };
    var y = (r.y + r.h <= safe.top) ? safe.top : safe.bottom - hs;
    return { x: x + w * 0.02, y: y + hs * 0.02, w: w * 0.96, h: hs * 0.96, a: 0 };
  }

  function startStrip() {
    measureBlocks();
    var ids = state.displayed.slice(), N = ids.length, k = ids.indexOf(state.liftId);
    var safe = { top: STATUS_H + 8, bottom: geo.barTop - 8 };
    var hs = clamp(((safe.bottom - safe.top) - (N - 1) * 8) / N, 48, 64), pitch = hs + 8, stackH = N * pitch - 8;
    var w = Math.min(geo.contentW, 560), x = PAGE_PAD;
    var top = clamp(g.y - (k + 0.5) * pitch + 4, safe.top, Math.max(safe.top, safe.bottom - stackH));
    var B = {};
    ids.forEach(function (id) { B[id] = stripStartRect(id, safe, hs, w, x); });
    state.carry = { mode: 'strip', phase: 'drag', ids: ids, orig: ids.slice(), N: N, k: k, hs: hs, pitch: pitch, w: w, x: x, top: top, safe: safe, B: B, dragOffset: 0, display: 0, lastY: g.y, over: false, carried: state.liftId };
    DEFAULT_ORDER.forEach(function (id) {
      var s = strips[id], on = ids.indexOf(id) >= 0;
      s.plate.style.display = s.label.style.display = on ? '' : 'none';
      if (!on) return;
      per[id].strip.snapTo(0);
      s.label.style.width = w + 'px';
      s.label.style.height = hs + 'px';
      s.label.style.setProperty('--cs', clamp(hs - 24, 24, 36) + 'px');   // cover edge = clamp(h_s − 24, 24, 36)
      s.plate.classList.toggle('is-carried', id === state.liftId);
      s.plate.style.zIndex = s.label.style.zIndex = id === state.liftId ? '3' : '1';
    });
    stripShadowEl.style.zIndex = '2';   // size / position / radius are written per frame by renderStrips (cached setS)
    holeEl.style.width = w + 'px'; holeEl.style.height = hs + 'px';
    holeY.snapTo(top + k * pitch);
    carryEl.style.display = 'block';
    fold.animateTo(1, 'defaultSpatial');
  }

  function swapStrip(c, dir) {
    var k = c.k, j = k + dir, nb = c.ids[j];
    c.ids[j] = c.ids[k]; c.ids[k] = nb;
    var s = per[nb].strip;
    s.snapTo(s.value + dir * c.pitch);   // neighbour keeps its on-screen position, then makes way
    s.animateTo(0, 'defaultSpatial');
    c.k = j;
    c.dragOffset -= dir * c.pitch;
    holeY.animateTo(c.top + j * c.pitch, 'fastSpatial');
    haptic('SEGMENT_TICK', '签条越过半格 → 第 ' + (j + 1) + ' 位');
  }

  function stripMove() {
    var c = state.carry;
    if (c.phase !== 'drag') return;
    c.dragOffset += g.y - c.lastY;
    c.lastY = g.y;
    g.vt.add(performance.now(), g.y);
    while (c.dragOffset > c.pitch / 2 && c.k < c.N - 1) swapStrip(c, 1);
    while (c.dragOffset < -c.pitch / 2 && c.k > 0) swapStrip(c, -1);
    var over = (c.k === 0 && c.dragOffset < 0) || (c.k === c.N - 1 && c.dragOffset > 0);
    c.display = over ? Math.sign(c.dragOffset) * rubber(Math.abs(c.dragOffset)) : c.dragOffset;
    var overNow = over && Math.abs(c.dragOffset) > 2;
    if (overNow && !c.over) haptic('GESTURE_THRESHOLD_ACTIVATE', c.k === 0 ? '拖过首槽（API < 34 回退 TEXT_HANDLE_MOVE）' : '拖过末槽（API < 34 回退 TEXT_HANDLE_MOVE）');
    c.over = overNow;
    requestLoop();
  }

  function stripRelease(cancelled) {
    var c = state.carry;
    c.phase = 'settle';
    var v = cancelled ? 0 : g.vt.v(performance.now());
    var visY = c.top + c.k * c.pitch + c.display;
    if (Math.abs(v) > FLING_V) {
      var dir = v > 0 ? 1 : -1;
      if ((dir > 0 && c.k < c.N - 1) || (dir < 0 && c.k > 0)) swapStrip(c, dir);   // fling projection: at most one slot
    }
    settleY.snapTo(visY - (c.top + c.k * c.pitch));
    settleY.animateTo(0, 'defaultSpatial', v, stripCommit);
  }

  function stripCommit() {
    var c = state.carry;
    if (!c || c.phase !== 'settle') return;
    c.phase = 'unfold';
    var changed = c.ids.join() !== c.orig.join(), dropped = c.carried;
    if (changed) {
      pushUndo();
      state.layout = withEnabledOrder(state.layout, c.ids);
      syncTray(); updateResetBtn(); updateLeftSlot();
      commitDom({ noFlip: true });   // feed is invisible while folded: no placement animation
      haptic('CONFIRM', '放下：' + SECTIONS[dropped].title + ' → 第 ' + (c.k + 1) + ' 位，顺序已提交');
    }
    // anchor: put the dropped block's plate where its strip is, then read the new rects once.
    // Constraint (spec ⑨b): the header's visibility must not flip — if it is on screen we keep the
    // scroll as is; otherwise we clamp so it stays off screen. The remaining distance is flown by the strips.
    measureBlocks();
    var headerOut = sectionsEl.offsetTop - STATUS_H;
    if (geo.scrollTop >= headerOut) {
      var want = geo.blocks[dropped].top - PLATE_V - (c.top + c.k * c.pitch);
      var st = clamp(Math.round(want), Math.min(headerOut, geo.maxScroll), geo.maxScroll);
      scrollEl.scrollTop = st;
      geo.scrollTop = st;
    }
    var B = {};
    c.ids.forEach(function (id) { B[id] = stripStartRect(id, c.safe, c.hs, c.w, c.x); });
    c.B = B;
    lift.animateTo(0, 'defaultSpatial');
    liftTint.animateTo(0, 'fastEffects');
    fold.animateTo(0, 'defaultSpatial', undefined, function () { stripFinish(changed); });
  }

  function stripFinish(changed) {
    var c = state.carry;
    if (!c) return;
    carryEl.style.display = 'none';
    state.carry = null;
    lift.snapTo(0); liftTint.snapTo(0);
    state.liftId = null;
    touchWiggle();                                   // carry over: E back to 1, idle timer restarts from here
    impulse(c.carried, 0.7);
    if (changed) c.ids.forEach(function (id, i) { if (id !== c.carried && c.orig.indexOf(id) !== i) impulse(id, 0.35); });
    runDeferredExit();
    requestLoop();
  }

  /** ⑪ Re-grab: a press on the carried strip while it is still settling catches it where it is. */
  function tryCatchStrip(e) {
    var c = state.carry;
    if (!c || c.mode !== 'strip' || c.phase !== 'settle') return false;
    var sr = screenEl.getBoundingClientRect();
    var px = e.clientX - sr.left, py = e.clientY - sr.top, d = settleY.value;
    var y = c.top + c.k * c.pitch + d;
    if (px < c.x || px > c.x + c.w || py < y - 8 || py > y + c.hs + 8) return false;
    settleY.stop();                                            // also drops the pending stripCommit
    var over = (c.k === 0 && d < 0) || (c.k === c.N - 1 && d > 0);
    c.dragOffset = over ? Math.sign(d) * unrubber(Math.abs(d)) : d;
    c.display = d;
    c.lastY = py;
    c.over = over && Math.abs(c.dragOffset) > 2;
    c.phase = 'drag';
    g.sr = sr; g.pid = e.pointerId; g.x0 = g.x = px; g.y0 = g.y = py;
    g.blockId = c.carried; g.card = null; g.local = null;
    g.state = 'carry'; g.swallow = true;
    g.vt.reset(); g.vt.add(performance.now(), py);
    attachDoc(); capturePointer();
    holeY.animateTo(c.top + c.k * c.pitch, 'fastSpatial');
    haptic('DRAG_START', '中途接住正在落定的 ' + SECTIONS[c.carried].title + ' 签条');
    requestLoop();
    return true;
  }

  /* ════════════════════════════ 18. Full-size carry (Q3b) ════════════════════════════ */

  function startFull() {
    measureBlocks();
    var ids = state.displayed.slice(), id = state.liftId, tops = {}, hs = {};
    ids.forEach(function (o) { tops[o] = geo.blocks[o].top; hs[o] = geo.blocks[o].h; per[o].shift.snapTo(0); });
    state.carry = {
      mode: 'full', phase: 'drag', ids: ids, k: ids.indexOf(id), tops: tops, hs: hs,
      grab: g.y + geo.scrollTop - tops[id] - per[id].off.value, carryY: 0, target: ids.indexOf(id), want: {}, carried: id,
      safe: { top: STATUS_H + 8, bottom: geo.barTop - 8 }
    };
    ids.forEach(function (o) { state.carry.want[o] = 0; });
  }

  function fullTick(dt) {
    var c = state.carry, fy = g.y, speed = 0;
    var dTop = fy - c.safe.top, dBot = c.safe.bottom - fy;
    if (dTop < AUTOSCROLL_EDGE) speed = -AUTOSCROLL_MAX * Math.pow(clamp((AUTOSCROLL_EDGE - dTop) / AUTOSCROLL_EDGE, 0, 1), 2);
    else if (dBot < AUTOSCROLL_EDGE) speed = AUTOSCROLL_MAX * Math.pow(clamp((AUTOSCROLL_EDGE - dBot) / AUTOSCROLL_EDGE, 0, 1), 2);
    if (speed) {
      // keep the fractional position ourselves; the element only takes whole pixels, so writing
      // fractions would let geo.scrollTop drift away from the real scroll offset at low speeds
      c.scrollF = clamp((c.scrollF == null ? geo.scrollTop : c.scrollF) + speed * dt, 0, geo.maxScroll);
      var st = Math.round(c.scrollF);
      if (st !== geo.scrollTop) { geo.scrollTop = st; scrollEl.scrollTop = st; }
    } else c.scrollF = null;
    var id = c.carried, top = fy + geo.scrollTop - c.grab;
    c.carryY = top - c.tops[id] - per[id].off.value;
    var bottom = top + c.hs[id], shift = c.hs[id] + SECTION_GAP, target = c.k;
    for (var i = 0; i < c.ids.length; i++) {
      var o = c.ids[i];
      if (o === id) continue;
      var mid = c.tops[o] + c.hs[o] / 2, want = 0;
      if (i > c.k && bottom > mid) want = -shift;
      if (i < c.k && top < mid) want = shift;
      if (want !== c.want[o]) { c.want[o] = want; per[o].shift.animateTo(want, 'defaultSpatial'); }
      if (want < 0) target++;
      if (want > 0) target--;
    }
    if (target !== c.target) { c.target = target; haptic('SEGMENT_TICK', '原尺寸拖动：目标第 ' + (target + 1) + ' 位'); }
    g.vt.add(performance.now(), fy + geo.scrollTop);
  }

  function fullRelease(cancelled) {
    var c = state.carry, id = c.carried;
    var v = cancelled ? 0 : g.vt.v(performance.now());
    var visual = {};
    c.ids.forEach(function (o) { visual[o] = c.tops[o] + per[o].off.value + (o === id ? c.carryY : per[o].shift.value); });
    var order = c.ids.slice();
    order.splice(c.k, 1);
    order.splice(c.target, 0, id);
    var changed = c.target !== c.k;
    state.carry = null;
    c.ids.forEach(function (o) { per[o].shift.snapTo(0); });
    if (changed) {
      pushUndo();
      state.layout = withEnabledOrder(state.layout, order);
      syncTray(); updateResetBtn(); updateLeftSlot();
      commitDom({ noFlip: true });
      haptic('CONFIRM', '放下：' + SECTIONS[id].title + ' → 第 ' + (c.target + 1) + ' 位，顺序已提交');
    } else {
      measureBlocks();
    }
    order.forEach(function (o) {
      var off = per[o].off;
      off.snapTo(visual[o] - geo.blocks[o].top);
      off.animateTo(0, 'defaultSpatial', o === id ? v : 0);
    });
    dropLift(0);
    touchWiggle();
    impulse(id, 0.7);
    if (changed) order.forEach(function (o, i) { if (o !== id && c.ids.indexOf(o) !== i) impulse(o, 0.35); });
    runDeferredExit();
  }

  function runDeferredExit() {
    if (!state.exitAfterCarry) return;
    var reason = state.exitAfterCarry;
    state.exitAfterCarry = false;
    exitEdit(reason);
  }

  /** ⑬ Drop the active carry at its current slot right now (back / Done / Escape / blur mid-drag). */
  function forceDrop() {
    if (!state.carry || state.carry.phase !== 'drag') return;
    if (g.state === 'carry') { g.state = 'done'; g.swallow = true; }   // the finger is still down: ignore the rest of it
    releaseCarry(true);
  }

  function startCarry() {
    if (state.mode !== 'edit') { g.state = 'done'; return; }   // edit was left while the finger was down
    g.state = 'carry';
    g.swallow = true;
    capturePointer();
    g.vt.reset();
    g.vt.add(performance.now(), opts.q3 === 'a' ? g.y : g.y + geo.scrollTop);
    if (opts.q3 === 'a') startStrip(); else startFull();
    setEnvelope();                                   // the other blocks sway at E × 0.6 while this one is carried
    requestLoop();
  }
  function releaseCarry(cancelled) {
    if (!state.carry) return;
    if (state.carry.mode === 'strip') stripRelease(cancelled); else fullRelease(cancelled);
  }

  /* ════════════════════════════ 19. Gesture detector ════════════════════════════ */

  var g = { state: 'idle', pid: null, x0: 0, y0: 0, x: 0, y: 0, blockId: null, card: null, local: null, timers: [], swallow: false, flingStop: false, sr: null, vt: velTracker(), scrubP: 0 };

  function clearTimers() { g.timers.forEach(clearTimeout); g.timers.length = 0; }
  function toLocal(e) { return { x: e.clientX - g.sr.left, y: e.clientY - g.sr.top }; }
  function blockLocal(id, sx, sy) {
    var b = geo.blocks[id];
    return b ? { x: sx - PAGE_PAD, y: sy + geo.scrollTop - b.top - per[id].off.value } : null;
  }
  /** Edit mode: the plate outset (8 / 6px around a block) is part of the block, not blank (cached geometry). */
  function plateHit(p) {
    for (var i = 0; i < state.displayed.length; i++) {
      var id = state.displayed[i];
      if (!geo.blocks[id]) continue;
      var r = plateRect(id);
      if (p.x >= r.x && p.x <= r.x + r.w && p.y >= r.y && p.y <= r.y + r.h) return id;
    }
    return null;
  }
  function unpress() { if (g.card) g.card.classList.remove('is-pressed'); }

  /**
   * Once the gesture is ours (lifted / armed / carrying / scrubbing) capture the pointer on the screen so
   * up/cancel always reach us, even if the finger or mouse leaves the phone or the iframe. Not done while
   * merely 'pressing': capture would retarget the click and break an ordinary tap on the card.
   */
  function capturePointer() {
    try { if (g.pid != null && !screenEl.hasPointerCapture(g.pid)) screenEl.setPointerCapture(g.pid); } catch (err) { /* pointer already gone */ }
  }
  function releasePointer() {
    try { if (g.pid != null && screenEl.hasPointerCapture(g.pid)) screenEl.releasePointerCapture(g.pid); } catch (err) { /* ignore */ }
  }

  /** Edit mode ended under a finger that is still down: ignore the rest of that gesture (no carry in normal mode). */
  function disownGesture() {
    var s = g.state;
    if (s === 'idle' || s === 'done' || s === 'carry') return;
    clearTimers(); unpress(); releaseCharge();
    if (s === 'scrub') scrubVis.animateTo(0, 'fastEffects');
    g.state = 'done';
    g.swallow = true;
  }

  var docOn = false;
  function attachDoc() {
    if (docOn) return; docOn = true;
    document.addEventListener('pointermove', onMove, true);
    document.addEventListener('pointerup', onUp, true);
    document.addEventListener('pointercancel', onCancel, true);
  }
  function detachDoc() {
    if (!docOn) return; docOn = false;
    document.removeEventListener('pointermove', onMove, true);
    document.removeEventListener('pointerup', onUp, true);
    document.removeEventListener('pointercancel', onCancel, true);
  }

  function isBusy() { return !!(state.carry && state.carry.phase !== 'drag'); }

  function onDown(e) {
    if (!e.isPrimary) return;                                   // ⑫ extra fingers are ignored
    if (e.pointerType === 'mouse' && e.button !== 0) return;
    if (g.state !== 'idle') return;
    var t = e.target;
    g.swallow = false;
    stopScrollDrive();
    if (state.menu) { if (!t.closest('.yp-menu')) { closeMenu(); g.swallow = true; } return; }
    if (state.mode === 'edit') touchWiggle();
    if (isBusy()) { tryCatchStrip(e); return; }                 // ⑪ catch a settling strip; ignore the feed until unfolded
    g.sr = screenEl.getBoundingClientRect();
    if (t.closest('.yp-edge')) { startScrub(e); return; }
    var btn = t.closest('button');
    if (t.closest('.yp-bar, .yp-menu') || (btn && !btn.matches('.yp-card, .yp-handle'))) return;   // exclusion zones
    var p = toLocal(e);
    g.pid = e.pointerId; g.x0 = g.x = p.x; g.y0 = g.y = p.y;
    var bEl = t.closest('.yp-block');
    g.blockId = bEl ? bEl.getAttribute('data-id') : (state.mode === 'edit' ? plateHit(p) : null);
    g.card = t.closest('.yp-card');
    g.local = g.blockId ? blockLocal(g.blockId, p.x, p.y) : null;
    attachDoc();
    if (state.mode === 'normal') {
      g.state = 'pressing';
      if (g.card) g.card.classList.add('is-pressed');
      g.timers.push(setTimeout(onHalfT, T_LONG / 2), setTimeout(onLongPress, T_LONG));
    } else if (btn && btn.matches('.yp-handle')) {
      g.state = 'handle';
    } else if (g.blockId) {
      g.state = 'holding';
      g.timers.push(setTimeout(onEditHold, EDIT_LIFT_HOLD));
    } else {
      g.state = 'blank';
      g.flingStop = performance.now() - state.lastScrollT < 120;
      // spec §2.6: a long press on blank in edit mode does nothing — it must not count as a "tap blank = Done"
      g.timers.push(setTimeout(function () { if (g.state === 'blank') g.state = 'blankHeld'; }, T_LONG));
    }
  }

  function onHalfT() { if (g.state === 'pressing' && g.blockId) startCharge(g.blockId, g.local); }

  function onLongPress() {
    if (g.state !== 'pressing') return;
    g.swallow = true;
    capturePointer();
    unpress();
    var title = g.blockId ? SECTIONS[g.blockId].title : '';
    if (opts.q2 === 'b' && g.card) {
      haptic('LONG_PRESS', '长按卡片 400ms（Q2b 第一段：松手出菜单，继续按住或移动进编辑）', '块继续下沉，蓄力底板停在按点');
      g.state = 'armed';
      g.timers.push(setTimeout(function () { if (g.state === 'armed') armedToEdit('继续按住 350ms'); }, Q2B_EXTRA_HOLD));
      return;
    }
    if (g.blockId) {
      haptic('LONG_PRESS', '长按 ' + title + ' 400ms：进入编辑并原地拿起');
      enterEdit(g.blockId);
      latchPlate(g.blockId, g.local);   // reads charge before releaseCharge() moves it (springs only step in frames)
      releaseCharge();
      liftBlock(g.blockId, g.local);
      g.state = 'lifted';
    } else {
      releaseCharge();
      haptic('LONG_PRESS', '长按空白 400ms：进入编辑（不拿起）', '入场涟漪：底板从最近的块依次显现');
      enterEdit(nearestBlock(g.y));
      g.state = 'done';
    }
  }

  function armedToEdit(why) {
    enterEdit(g.blockId);
    latchPlate(g.blockId, g.local);
    releaseCharge();
    haptic('DRAG_START', 'Q2b：' + why + ' → 进入编辑并拿起 ' + SECTIONS[g.blockId].title);
    liftBlock(g.blockId, g.local);
    g.state = 'lifted';
  }

  function onEditHold() {
    if (g.state !== 'holding') return;
    g.swallow = true;
    capturePointer();
    haptic('DRAG_START', '编辑态按住 200ms：拿起 ' + SECTIONS[g.blockId].title);
    liftBlock(g.blockId, g.local);
    g.state = 'lifted';
  }

  function abandon() {
    clearTimers();
    unpress();
    releaseCharge();
    g.state = 'idle';
  }

  function onMove(e) {
    if (e.pointerId !== g.pid) return;
    var p = toLocal(e);
    g.x = p.x; g.y = p.y;
    var d = Math.hypot(p.x - g.x0, p.y - g.y0);
    switch (g.state) {
      case 'pressing': if (d > SLOP) { abandon(); g.swallow = true; } break;
      case 'armed': if (d > SLOP) { clearTimers(); armedToEdit('按住移动'); startCarry(); } break;
      case 'holding': case 'blank': case 'blankHeld': if (d > SLOP) abandon(); break;
      case 'handle':
        if (d > SLOP) {
          haptic('DRAG_START', '拖把手：拿起 ' + SECTIONS[g.blockId].title);
          liftBlock(g.blockId, g.local);
          startCarry();
        }
        break;
      case 'lifted': if (d > SLOP) startCarry(); break;
      case 'carry':
        if (state.carry && state.carry.mode === 'strip') stripMove(); else requestLoop();
        if (e.cancelable) e.preventDefault();
        break;
      case 'scrub': scrubMove(); break;
    }
  }

  /**
   * Q1(b): a finger that stays down holds the sway (tickers() skips the idle check while g.state ≠ idle), so the
   * 6 s window restarts when it lifts — otherwise a lift held > 6 s would go still the instant it is dropped,
   * while the carry path (stripFinish / fullRelease → touchWiggle) already restarted it.
   */
  function fingerLifted() { if (state.mode === 'edit') state.lastTouch = performance.now(); }

  function onUp(e) {
    if (e.pointerId !== g.pid) return;
    detachDoc();
    clearTimers();
    unpress();
    fingerLifted();
    var s = g.state;
    g.state = 'idle';
    switch (s) {
      case 'pressing': releaseCharge(); break;                 // a real tap: the card's click opens it
      case 'armed': releaseCharge(); openMenu(g.card, g.blockId); break;
      case 'holding': case 'handle': blockTap(g.blockId, g.card); break;
      case 'lifted': dropLift(0.5); break;                      // ①′ lifted and released in place
      case 'carry': releaseCarry(false); break;
      case 'blank': if (!g.flingStop) exitEdit('blank'); break; // tap on blank = Done (no haptic per spec §2.5)
      case 'scrub': scrubEnd(false); break;
    }
    // g.swallow stays armed until the click it guards arrives (or the next pointerdown clears it);
    // a timer here could expire before a slow synthetic click and let a long-press open the card.
  }

  function onCancel(e) {
    if (e.pointerId !== g.pid) return;
    detachDoc();
    clearTimers();
    unpress();
    fingerLifted();
    var s = g.state;
    g.state = 'idle';
    if (s === 'pressing' || s === 'armed') releaseCharge();
    if (s === 'lifted') dropLift(0);
    if (s === 'carry') releaseCarry(true);
    if (s === 'scrub') scrubEnd(true);
  }

  /* ── predictive back scrub on the left 16px edge ── */
  function startScrub(e) {
    if (state.mode !== 'edit' || state.carry) return;
    var p = toLocal(e);
    g.pid = e.pointerId; g.x0 = g.x = p.x; g.y0 = g.y = p.y; g.scrubP = 0;
    g.state = 'scrub';
    g.swallow = true;
    attachDoc();
    capturePointer();
    scrubVis.animateTo(1, 'fastEffects');
    if (e.cancelable) e.preventDefault();
  }
  function scrubMove() {
    g.scrubP = clamp((g.x - g.x0) / (geo.screenW * 0.7), 0, 1);
    scrubBack(g.scrubP);
  }
  function scrubEnd(cancel) {
    scrubVis.animateTo(0, 'fastEffects');
    settleBack(!cancel && g.scrubP > 0.3);
  }

  /* ════════════════════════════ 20. Ticker + render (the only DOM writer inside the loop) ════════════════════════════ */

  function tickers(dt, now) {
    // Q1(b) IdleSettle: 6 s without a pointerdown in the phone (and no finger still down) → E springs to 0.
    if (opts.q1 === 'b' && state.mode === 'edit' && !state.carry && g.state === 'idle' && wigEnv.target > 0 &&
        performance.now() - state.lastTouch > WIGGLE_IDLE_MS) {
      wigEnv.animateTo(0, 'defaultSpatial');
    }
    if (state.carry && state.carry.mode === 'full' && state.carry.phase === 'drag') fullTick(dt);
    entryKickTick();
  }

  /**
   * §2.2.4 edge band: 0 where [top, bot] (screen px) meets the status/tide band or the bottom-bar band,
   * linear fade within 48px of them. 整块 passes the plate rect, 卡片级 each card's own rect.
   */
  function bandAt(top, bot) {
    if (!opts.band) return 1;
    var dist = Math.min(top - (STATUS_H + 8), (geo.barTop - 8) - bot);
    return dist <= 0 ? 0 : clamp(dist / BAND_FADE, 0, 1);
  }

  /** Per-section sway: f = 2.4Hz × (1 + 0.07·h), h ∈ [−1, 1], phase and direction from stableHash(section.id). */
  function swayParams(id) {
    var hv = hashStr(id), h = (((hv >>> 4) % 201) / 100) - 1;
    return { f: SWAY_HZ * (1 + 0.07 * h), ph: (hv % 628) / 100, sign: stableSign(id) };
  }
  var PHASES = {};
  DEFAULT_ORDER.forEach(function (id) { PHASES[id] = swayParams(id); });

  /** Normalised sway shared by a block and all its cards: E · smoothstep(p_i) · sin(2π f t + φ), one global clock t. */
  function swayN(id, pa) {
    var E = wigEnv.value;
    if (E <= 0.0005 || pa <= 0) return 0;
    var s = PHASES[id];
    return s.sign * E * pa * Math.sin(2 * Math.PI * s.f * simTime + s.ph);
  }

  /** Gain common to everything in a block: amplitude slider × (1 − lift) for the held block; 0 under reduced motion. */
  function swayGain(id) {
    if (opts.reduced) return 0;
    var m = opts.amp;
    if (id === state.liftId) m *= 1 - clamp(lift.value, 0, 1);
    if (state.carry && state.carry.mode === 'strip' && state.carry.carried === id) m = 0;   // folded away under its strip
    return m;
  }

  var lastReadout = 0, lastEv = false;

  function render(now) {
    var Pv = P.value, Pc = clamp(Pv, 0, 1);
    var ev = Pv > 0.01;
    if (ev !== lastEv) { lastEv = ev; rootEl.classList.toggle('yp-ev', ev); }
    if (scrollDriving) { scrollEl.scrollTop = scrollSp.value; if (!scrollSp.active) scrollDriving = false; }
    renderBlocks(Pv);
    renderHeader(Pc);
    renderBar(Pc);
    renderStrips();
    renderMisc();
    if (!now || now - lastReadout > 90 || (!live.length && !continuous())) { lastReadout = now || 0; renderReadout(); }
  }

  function renderBlocks(Pv) {
    var ids = state.displayed, N = ids.length;
    var dMax = 0.06 * Math.min(Math.max(N - 1, 0), 4);
    var c = state.carry;
    var pf = state.plateFrom;
    if (pf) {
      if (Pv >= 0.6) pf.latch = 0;                                       // chargeLatch is cleared once P ≥ 0.6
      if ((state.mode !== 'edit' && Pv <= 0.001) || ids.indexOf(pf.id) < 0) pf = state.plateFrom = null;
    }
    for (var i = 0; i < N; i++) {
      var id = ids[i], el = blockEl[id], sp = per[id], b = geo.blocks[id];
      if (!b) continue;
      var p = pFor(i, Pv, dMax), pa = smoothstep(0, 1, p);
      var lifted = id === state.liftId;
      // Plate: alpha smoothstep(p_i). Feed plates stay drawn while folding: they fade out together with the
      // content (sectionsEl alpha) while the strip plates fade in on top — hiding them outright left opaque
      // strip plates covering still-visible content, a hard cut at drag start and at the end of the unfold.
      // The charge pre-show (§2.1.3) and the entry block's growth (§2.1.4-4) clip it to a smaller rect.
      var plateA = pa, clip = '', shInset = '';
      if (pf && pf.id === id) {
        if (pa >= 0.9999 && state.mode === 'edit') pf = state.plateFrom = null;   // fully grown: plain Panel again
        else {
          var gr = lerpRect(pf.rect, fullPlateRect(id), pa);                  // lerp(R_press⁺, R_plate, smoothstep(p_i))
          plateA = Math.max(0.4 * smoothstep(0, 1, pf.latch), pa);
          clip = plateClip(id, gr);
          shInset = f2(gr.y0) + 'px ' + f2(b.w - gr.x1) + 'px ' + f2(b.h - gr.y1) + 'px ' + f2(gr.x0) + 'px';
        }
      } else if (id === state.chargeId && state.chargeOrigin && charge.value > 0.0005) {
        var cv = clamp(charge.value, 0, 1), chA = 0.4 * smoothstep(0, 1, cv);
        if (chA > pa) { plateA = chA; clip = plateClip(id, pressRect(id, state.chargeOrigin, PRESS_OUTSET * cv)); }
      }
      setS(plateEl[id], 'opacity', String(f3(plateA)));
      setS(plateEl[id], 'clipPath', clip);
      setS(tintEl[id], 'opacity', String(lifted ? f3(liftTint.value) : 0));
      setS(shadowEl[id], 'opacity', String(lifted ? f3(clamp(lift.value, 0, 1)) : 0));
      setS(shadowEl[id], 'inset', shInset);                                  // the lift shadow follows the growing plate
      var local = clamp((p - 0.25) / 0.75, 0, 1), bs = lerp(0.6, 1, smoothstep(0, 1, local)), ba = smoothstep(0, 1, local);
      setS(hideBtn[id], 'opacity', String(f3(ba)));
      setS(hideBtn[id], 'transform', 'scale(' + f3(bs) + ')');
      setS(handleBtn[id], 'opacity', String(f3(ba)));
      setS(handleBtn[id], 'transform', 'scale(' + f3(bs * sp.pulse.value) + ')');

      // Q1 wiggle: sway (E · smoothstep(p_i) · sin) + kick, × slider × edge band × (1 − lift).
      // 整块 rotates the whole block about its centre (sway 0.6·Θ_i, kick Θ_i); 卡片级 rotates every card about
      // its own centre with A_c, alternating by in-block index, while plate / title / badges stay still.
      var ty = sp.off.value + sp.shift.value;
      if (c && c.mode === 'full' && c.carried === id) ty += c.carryY;
      var cards = cardsOf[id], cardMode = opts.q1t === 'card' && cards.length > 0;   // placeholder (no cards) → whole block
      var gain = swayGain(id), sw = gain ? swayN(id, pa) : 0, kn = sp.kick.value;
      var screenTop = b.top - geo.scrollTop + ty;
      var angle = 0;
      if (!cardMode && gain) angle = (sw * 0.6 + kn) * b.theta * gain * bandAt(screenTop - PLATE_V, screenTop + b.h + PLATE_V);
      for (var k = 0; k < cards.length; k++) {
        var cd = cards[k], ca = 0;
        if (cardMode && gain) {
          var ct = screenTop + cd.relTop;
          ca = ((sw + kn) * cd.alt + cd.kick.value) * cd.A * gain * bandAt(ct, ct + cd.h);
        }
        setS(cd.el, 'rotate', Math.abs(ca) < 0.0005 ? '' : ca.toFixed(4) + 'deg');
      }

      var s = sp.hideS.value, ox = b.w / 2, oy = b.h / 2;
      if (id === state.chargeId && charge.value > 0.0005) {
        s *= 1 - 0.012 * charge.value * opts.amp;
        if (state.chargeOrigin) { ox = state.chargeOrigin.x; oy = state.chargeOrigin.y; }
      }
      if (lifted) {
        s *= 1 + 0.02 * lift.value;
        if (state.liftOrigin) { ox = state.liftOrigin.x; oy = state.liftOrigin.y; }
      }
      var tf;
      if (Math.abs(angle) < 0.0005 && Math.abs(s - 1) < 0.00005) tf = 'translate3d(0,' + f2(ty) + 'px,0)';
      else {
        var cx = b.w / 2, cy = b.h / 2;
        tf = 'translate3d(0,' + f2(ty) + 'px,0) translate(' + f2(cx) + 'px,' + f2(cy) + 'px) rotate(' + angle.toFixed(4) + 'deg) translate(' + f2(-cx) + 'px,' + f2(-cy) + 'px) translate(' + f2(ox) + 'px,' + f2(oy) + 'px) scale(' + s.toFixed(5) + ') translate(' + f2(-ox) + 'px,' + f2(-oy) + 'px)';
      }
      setS(el, 'transform', tf);
      setS(el, 'opacity', String(f3(sp.hideA.value)));
      setS(el, 'zIndex', lifted ? '3' : '');
    }
    // While folded into strips the feed content fades (header stays: it is page chrome, not a block)
    var feedA = c && c.mode === 'strip' ? 1 - smoothstep(0, 0.45, fold.value) : 1;
    setS(sectionsEl, 'opacity', String(f3(feedA)));
    setS(trayEl, 'opacity', String(f3(trayA.value * feedA)));
    setS(trayEl, 'transform', 'translate3d(0,' + f2(trayOff.value) + 'px,0)');
    setS(footEl, 'opacity', String(f3(footA.value)));
    setS(footEl, 'transform', 'translate3d(0,' + f2(footOff.value) + 'px,0)');
    DEFAULT_ORDER.forEach(function (id) { setS(rowEl[id], 'opacity', String(f3(per[id].rowA.value))); });
  }

  function renderHeader(Pc) {
    setS(homeTitleEl, 'opacity', String(f3(1 - smoothstep(0.2, 0.6, Pc))));
    setS(editTitleEl, 'opacity', String(f3(smoothstep(0.4, 0.8, Pc))));
    setS(headIconsEl, 'opacity', String(f3(1 - smoothstep(0, 0.5, Pc))));
    setS(headIconsEl, 'visibility', Pc >= 0.5 ? 'hidden' : 'visible');
    setS(hintEl, 'opacity', String(state.showHint ? f3(smoothstep(0.5, 1, Pc)) : 0));
  }

  function renderBar(Pc) {
    var inner = geo.barW - 20, rest = 56, gap = 8;
    var half = (inner - gap) / 2, pillRest = Math.max(0, inner - 2 * rest - 2 * gap);
    var wL = lerp(rest, half, Pc), wP = Math.max(0, lerp(pillRest, 0, Pc)), gR = lerp(gap, 0, Pc), wR = lerp(rest, half, Pc);
    var xL = 10, xP = xL + wL + gap, xR = xP + wP + gR;
    setS(slotL, 'width', f2(wL) + 'px'); setS(slotL, 'transform', 'translate3d(' + f2(xL) + 'px,0,0)');
    setS(pillEl, 'width', f2(wP) + 'px'); setS(pillEl, 'transform', 'translate3d(' + f2(xP) + 'px,0,0)');
    setS(pillEl, 'opacity', String(f3(1 - smoothstep(0, 0.6, Pc))));
    setS(pillEl, 'visibility', Pc >= 0.995 ? 'hidden' : 'visible');
    setS(slotR, 'width', f2(wR) + 'px'); setS(slotR, 'transform', 'translate3d(' + f2(xR) + 'px,0,0)');
    var s = smoothstep(0.35, 0.65, Pc);
    setS(bgNav, 'opacity', String(f3(1 - s)));
    setS(bgEdit, 'opacity', String(f3(s)));
    setS(lHome, 'opacity', String(f3(1 - s)));
    setS(lUndo, 'opacity', String(f3(s * slotA.undo.value)));
    setS(lAdd, 'opacity', String(f3(s * slotA.add.value)));
    setS(lDis, 'opacity', String(f3(s * slotA.dis.value)));
    setS(bgPrimary, 'opacity', String(f3(Pc)));
    setS(lLib, 'opacity', String(f3(1 - s)));
    setS(lDone, 'opacity', String(f3(s)));
  }

  function renderStrips() {
    var c = state.carry;
    if (!c || c.mode !== 'strip') return;
    var fv = fold.value, W = geo.screenW, H = geo.screenH, la = smoothstep(0.55, 1, fv), fa = clamp(fv, 0, 1);
    var xf = smoothstep(0, 0.45, fv);   // strip plates cross-fade in exactly as the feed fades out (and back)
    var tw = 30 * smoothstep(0.3, 1, fv);   // strip colour = lerp(plate colour, first cover's base colour, 0.30) by fold
    for (var j = 0; j < c.ids.length; j++) {
      var id = c.ids[j], s = strips[id], B = c.B[id], carried = id === c.carried;
      var sy = c.top + j * c.pitch + (carried ? (c.phase === 'drag' ? c.display : settleY.value) : per[id].strip.value);
      var S = { x: c.x, y: sy, w: c.w, h: c.hs };
      if (carried) {
        var e = 0.02 * lift.value;
        S = { x: S.x - S.w * e / 2, y: S.y - S.h * e / 2, w: S.w * (1 + e), h: S.h * (1 + e) };
      }
      var r = { x: lerp(B.x, S.x, fv), y: lerp(B.y, S.y, fv), w: lerp(B.w, S.w, fv), h: lerp(B.h, S.h, fv) };
      var a = lerp(B.a, 1, fa), rad = Math.min(20, r.h / 2);
      setS(s.plate, 'clipPath', 'inset(' + f2(Math.max(0, r.y)) + 'px ' + f2(Math.max(0, W - r.x - r.w)) + 'px ' + f2(Math.max(0, H - r.y - r.h)) + 'px ' + f2(Math.max(0, r.x)) + 'px round ' + f2(rad) + 'px)');
      setS(s.plate, 'opacity', String(f3(a * xf)));
      // Start colour = the feed plate's colour (the carried one follows liftTint, the same effects spring as its
      // feed plate, so there is no snap at hand-off); then lerp toward the first cover's base colour by 30%.
      var plateC = carried ? 'color-mix(in srgb,var(--surfaceContainerHighest) ' + Math.round(clamp(liftTint.value, 0, 1) * 100) + '%,var(--surfaceContainerHigh))' : 'var(--surfaceContainerHigh)';
      var first = STRIP_COVERS[id] && STRIP_COVERS[id][0];   // placeholder strips (no covers) keep the plate colour
      setS(s.plate, 'background', first && tw > 0.05 ? 'color-mix(in srgb,var(--art-' + first[0] + '-a) ' + f2(tw) + '%,' + plateC + ')' : plateC);
      setS(s.label, 'transform', 'translate3d(' + f2(r.x) + 'px,' + f2(r.y + r.h / 2 - c.hs / 2) + 'px,0)');
      setS(s.label, 'opacity', String(f3(la * a)));
      if (carried) {
        setS(stripShadowEl, 'transform', 'translate3d(' + f2(r.x) + 'px,' + f2(r.y) + 'px,0)');
        setS(stripShadowEl, 'width', f2(r.w) + 'px');
        setS(stripShadowEl, 'height', f2(r.h) + 'px');
        setS(stripShadowEl, 'borderRadius', f2(rad) + 'px');
        setS(stripShadowEl, 'opacity', String(f3(clamp(lift.value, 0, 1) * smoothstep(0.6, 1, fv))));
      }
    }
    setS(holeEl, 'transform', 'translate3d(' + f2(c.x) + 'px,' + f2(holeY.value) + 'px,0)');
    setS(holeEl, 'opacity', String(f3(smoothstep(0.3, 1, fv))));
  }

  function renderMisc() {
    if (hintTileEl) setS(hintTileEl, 'opacity', String(f3(hintTileA.value)));
    // card menu
    setS(menuEl, 'opacity', String(f3(menuA.value)));
    setS(menuEl, 'transform', 'scale(' + f3(menuS.value) + ')');
    // toast
    setS(toastEl, 'opacity', String(f3(toastA.value)));
    setS(toastEl, 'transform', 'translate(-50%,' + f2((1 - toastA.value) * 8) + 'px)');
    // back indicator (follows the scrub progress; the gesture is its only writer)
    var sv = scrubVis.value;
    if (sv > 0.001) {
      var x = lerp(-28, 12, Math.min(1, g.scrubP * 1.6));
      setS(backIndEl, 'transform', 'translate3d(' + f2(x) + 'px,' + f2(clamp(g.y - 18, STATUS_H, geo.screenH - 140)) + 'px,0)');
    }
    setS(backIndEl, 'opacity', String(f3(sv * smoothstep(0, 0.12, g.scrubP))));
  }

  function renderReadout() {
    var c = state.carry;
    var mode = state.mode === 'edit' ? 'edit（HomeSurface.Edit）' : 'normal（Feed）';
    if (c) mode += c.mode === 'strip' ? ' · 签条' + (c.phase === 'drag' ? '拖动中' : c.phase === 'settle' ? '落定中' : '展开中') : ' · 原尺寸拖动中';
    else if (g.state === 'scrub') mode += ' · 返回手势 ' + Math.round(g.scrubP * 100) + '%';
    else if (state.liftId) mode += ' · 已拿起 ' + SECTIONS[state.liftId].title;
    ro.mode.textContent = mode;
    ro.p.textContent = P.value.toFixed(3);
    ro.pbar.style.transform = 'scaleX(' + f3(clamp(P.value, 0, 1)) + ')';
    ro.clf.textContent = charge.value.toFixed(2) + ' / ' + lift.value.toFixed(2) + ' / ' + fold.value.toFixed(2);
    ro.env.textContent = wigEnv.value.toFixed(2) + (opts.q1 === 'a' ? '（一颤形态不用 E）' : opts.q1 === 'b' && state.mode === 'edit' && !wigEnv.active && wigEnv.value === 0 ? '（6 秒无触摸，已静默）' : '');
    var order = c && c.mode === 'strip' ? c.ids : state.displayed;
    ro.order.textContent = order.length ? order.map(function (id) { return SECTIONS[id].title; }).join(' › ') : '（全部隐藏）';
    ro.undo.textContent = state.undo.length + ' / ' + hiddenIds(state.layout).length;
  }

  /* ════════════════════════════ 21. Events ════════════════════════════ */

  screenEl.addEventListener('pointerdown', onDown);
  // touch: once we own the gesture, stop the browser from scrolling
  screenEl.addEventListener('touchmove', function (e) {
    var s = g.state;
    if ((s === 'lifted' || s === 'carry' || s === 'armed' || s === 'handle' || s === 'scrub' || s === 'done') && e.cancelable) e.preventDefault();
  }, { passive: false });
  screenEl.addEventListener('contextmenu', function (e) { e.preventDefault(); });
  screenEl.addEventListener('selectstart', function (e) { e.preventDefault(); });
  screenEl.addEventListener('dragstart', function (e) { e.preventDefault(); });

  // A long-press (or a moved press) must never fire the card's tap on release. Only pointer-made clicks
  // are guarded (detail ≥ 1); keyboard / assistive clicks (detail 0) always go through.
  screenEl.addEventListener('click', function (e) {
    if (g.swallow && e.detail !== 0) { g.swallow = false; e.stopImmediatePropagation(); e.preventDefault(); }
  }, true);

  screenEl.addEventListener('click', function (e) {
    var t = e.target;
    var cardEl = t.closest('.yp-card');
    if (cardEl) {
      var bEl = cardEl.closest('.yp-block');
      if (state.mode === 'edit') { if (e.detail === 0 && bEl) blockTap(bEl.getAttribute('data-id'), cardEl); }
      else toast(cardEl.getAttribute('data-open'));   // normal-mode card taps: no haptic (§2.8)
      return;
    }
    if (t.closest('.yp-hint-tile')) {                  // §4: tap the static hint tile → enter edit (not a gesture entry)
      if (state.mode !== 'edit') enterEdit('jump_back_in');
      return;
    }
    var h = t.closest('.yp-handle');
    if (h) { if (e.detail === 0 && state.mode === 'edit') { blockTap(h.getAttribute('data-id')); announce('Use the up and down arrow keys to move this section'); } return; }
    var hb = t.closest('.yp-hide');
    if (hb) { hideSection(hb.getAttribute('data-id')); return; }
    var row = t.closest('.yp-tray-row');
    if (row) { showSection(row.getAttribute('data-id')); return; }
    if (t.closest('.yp-reset')) { resetHome(); return; }
    if (t.closest('.yp-edit-entry')) {
      var hadFocus = document.activeElement === editEntryBtn;
      enterEdit(state.displayed[state.displayed.length - 1] || null);
      if (hadFocus) slotR.focus({ preventScroll: true });     // the entry button is removed in edit mode
      return;
    }
    if (t.closest('.yp-mem')) { toast('Open Memories'); return; }
    if (t.closest('.yp-gear')) { toast('Open Settings'); return; }
    if (t.closest('.yp-slot-l')) {
      if (state.mode === 'edit') {
        if (leftKind === 'undo') undoStep();
        else if (leftKind === 'add') { haptic('KEYBOARD_TAP', 'Add：滚到托盘'); scrollToTray(); }
      } else toast('Home');
      return;
    }
    if (t.closest('.yp-slot-r')) { if (state.mode === 'edit') exitEdit('done'); else toast('Open Library'); return; }
    if (t.closest('.yp-pill')) { if (state.mode !== 'edit') toast('Open Now Playing'); return; }
    var mi = t.closest('.yp-menu-item');
    if (mi && state.menu) {
      var m = state.menu, act = mi.getAttribute('data-act');
      closeMenu();
      if (act === 'next') toast('Play next: ' + m.title);
      else if (act === 'go') toast(m.open);
      else if (act === 'edit') enterEdit(m.blockId);
    }
  });

  sectionsEl.addEventListener('keydown', function (e) {
    var h = e.target.closest && e.target.closest('.yp-handle');
    if (!h || state.mode !== 'edit') return;
    if (e.key === 'ArrowUp') { moveByKey(h.getAttribute('data-id'), -1); e.preventDefault(); }
    else if (e.key === 'ArrowDown') { moveByKey(h.getAttribute('data-id'), 1); e.preventDefault(); }
  });
  menuEl.addEventListener('keydown', function (e) {
    if (e.key !== 'ArrowDown' && e.key !== 'ArrowUp') return;
    var items = $all('.yp-menu-item', menuEl), i = items.indexOf(document.activeElement);
    var n = e.key === 'ArrowDown' ? (i + 1) % items.length : (i - 1 + items.length) % items.length;
    items[n].focus();
    e.preventDefault();
  });
  document.addEventListener('keydown', function (e) {
    if (e.key !== 'Escape') return;
    if (state.menu) { closeMenu(); e.preventDefault(); return; }
    if (state.mode === 'edit' && g.state !== 'scrub') { exitEdit('back'); e.preventDefault(); }   // mid-drag: drops first (⑬)
  });
  // the card menu is screen-local: a press anywhere outside the phone dismisses it too
  document.addEventListener('pointerdown', function (e) {
    if (state.menu && !screenEl.contains(e.target)) closeMenu();
  }, true);
  // window lost focus mid-gesture (alt-tab, devtools, iframe blur): treat it as pointercancel
  window.addEventListener('blur', function () {
    if (g.state !== 'idle') { releasePointer(); onCancel({ pointerId: g.pid }); }
  });

  scrollEl.addEventListener('scroll', function () {
    geo.scrollTop = scrollEl.scrollTop;
    state.lastScrollT = performance.now();
    // wheel / programmatic scroll under a resting finger cancels a pending long-press (touch pans already pointercancel)
    if (g.state === 'pressing' || g.state === 'holding') { abandon(); g.swallow = true; }
    if (state.menu) closeMenu();
  }, { passive: true });
  scrollEl.addEventListener('wheel', stopScrollDrive, { passive: true });

  // horizontalEdgeFadeOnScroll for Rediscover (RA deliberately has none)
  $all('.yp-shelf[data-fade]').forEach(function (sh) {
    function upd() {
      var max = sh.scrollWidth - sh.clientWidth;
      sh.classList.toggle('fade-s', sh.scrollLeft > 1);
      sh.classList.toggle('fade-e', sh.scrollLeft < max - 1);
    }
    sh.addEventListener('scroll', upd, { passive: true });
    upd();
  });

  // ── control panel ──
  function snapKicks() {
    DEFAULT_ORDER.forEach(function (id) {
      per[id].kick.snapTo(0);
      cardsOf[id].forEach(function (cd) { cd.kick.snapTo(0); });
    });
  }
  function setOpt(group, v) {
    opts[group] = v;
    $all('.yp-opt[data-group="' + group + '"]').forEach(function (b) { b.setAttribute('aria-pressed', b.getAttribute('data-v') === v ? 'true' : 'false'); });
    if (group === 'q1') {
      // form change: E eases to its new target (0 for Kick) through the envelope owner — no snap mid-sway
      state.lastTouch = performance.now();
      setEnvelope();
    }
    // q1t (整块 / 卡片级): render() hands the same normalised sway + block kick over on the next frame
    // (block rotation is dropped from the block transform, card `rotate` is written, or vice versa).
    if (group === 'q2') closeMenu(true);
    requestLoop();
  }
  $all('.yp-opt').forEach(function (b) {
    b.addEventListener('click', function () { setOpt(b.getAttribute('data-group'), b.getAttribute('data-v')); });
  });
  var ampIn = $('.yp-amp'), ampOut = $('.yp-amp-out');
  ampIn.addEventListener('input', function () { opts.amp = parseFloat(ampIn.value) || 1; ampOut.textContent = opts.amp.toFixed(2) + '×'; requestLoop(); });
  $all('.yp-switch').forEach(function (b) {
    b.addEventListener('click', function () {
      var key = b.getAttribute('data-key');
      opts[key] = !opts[key];
      b.setAttribute('aria-checked', opts[key] ? 'true' : 'false');
      if (key === 'reduced') {
        rootEl.classList.toggle('yp-reduced', opts.reduced);
        if (opts.reduced) { charge.snapTo(0); wigEnv.snapTo(0); snapKicks(); state.plateFrom = null; }
        else { state.lastTouch = performance.now(); setEnvelope(); }
      }
      requestLoop();
    });
  });
  $('.yp-act-replay').addEventListener('click', function () {
    if (state.carry || g.state !== 'idle') return;
    closeMenu(true);
    if (state.mode === 'edit' || P.value > 0) snapExit();
    var ids = state.displayed;
    enterEdit(ids.length ? ids[Math.floor((ids.length - 1) / 2)] : null);
  });
  $('.yp-act-back').addEventListener('click', function () {
    if (state.menu) { closeMenu(); return; }
    if (state.mode === 'edit') exitEdit('back');
    else toast('Back on Home → system goes home (root, not intercepted)');
  });
  $('.yp-act-reset').addEventListener('click', resetProto);

  function resetProto() {
    releasePointer(); clearTimers(); detachDoc(); unpress(); g.state = 'idle'; g.swallow = false; g.scrubP = 0;
    scrubVis.snapTo(0); holeY.stop(); menuS.snapTo(0.9);
    closeMenu(true);
    if (state.carry) { carryEl.style.display = 'none'; state.carry = null; }
    state.exitAfterCarry = false;
    stopScrollDrive();
    snapExit();
    state.layout = defaultLayout();
    state.undo = [];
    state.editSessions = 0;
    state.showHint = false;
    state.hintTileGone = false;                       // the §4 hint tile comes back with a fresh "device"
    hintTileA.snapTo(1);
    if (hintTileEl) {
      hintTileEl.style.display = ''; hintTileEl.style.pointerEvents = '';
      hintTileEl.removeAttribute('tabindex'); hintTileEl.removeAttribute('aria-hidden');
    }
    syncTray(); updateResetBtn();
    commitDom({ noFlip: true });
    snapKicks();
    DEFAULT_ORDER.forEach(function (id) {
      var s = per[id];
      s.off.snapTo(0); s.hideA.snapTo(1); s.hideS.snapTo(1); s.shift.snapTo(0); s.strip.snapTo(0); s.pulse.snapTo(1);
    });
    lift.snapTo(0); liftTint.snapTo(0); charge.snapTo(0); fold.snapTo(0); settleY.snapTo(0); trayOff.snapTo(0); footOff.snapTo(0);
    state.liftId = null; state.chargeId = null;
    scrollEl.scrollTop = 0; geo.scrollTop = 0;
    $all('.yp-shelf').forEach(function (sh) { sh.scrollLeft = 0; });
    logRows.length = 0; renderLog();
    hNameEl.textContent = '触感：—';
    updateLeftSlot();
    measureBlocks();
    requestLoop();
  }

  // spec §2.2.4 运行条件: the sway only runs while Home is visible — here, while the phone is on screen
  try {
    if (window.IntersectionObserver) {
      new IntersectionObserver(function (entries) {
        var vis = entries[entries.length - 1].isIntersecting;
        if (vis === phoneOnScreen) return;
        phoneOnScreen = vis;
        if (vis) requestLoop();
      }).observe(screenEl);
    }
  } catch (err) { phoneOnScreen = true; }

  var resizeTimer = 0;
  window.addEventListener('resize', function () {
    clearTimeout(resizeTimer);
    resizeTimer = setTimeout(function () { if (!state.carry) { measureBlocks(); requestLoop(); } }, 120);
  });

  /* ════════════════════════════ 22. Init ════════════════════════════ */

  state.layout = defaultLayout();
  state.displayed = enabledOrder(state.layout);
  if (opts.reduced) rootEl.classList.add('yp-reduced');
  trayEl.style.display = 'none';
  syncTray();
  updateResetBtn();
  commitDom({ noFlip: true });
  updateLeftSlot();
  renderLog();
  measureBlocks();
  requestLoop();
  if (document.fonts && document.fonts.ready) {
    document.fonts.ready.then(function () { if (!state.carry) { measureBlocks(); requestLoop(); } }).catch(function () { /* ignore */ });
  }
})();
