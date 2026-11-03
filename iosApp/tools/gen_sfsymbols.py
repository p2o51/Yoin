#!/usr/bin/env python3
"""Yoin Symbols -> SF Symbols custom-symbol templates (variable, "Template v.3.0").

Prototype for a future `generator/gen_ios.py` in p2o51/yoin-symbols. It reads the same
centre-line sources as every other platform (generator/icons/*, via common.REGISTRY) and
writes, per symbol, one SVG template with the three interpolation sources Ultralight-S,
Regular-S and Black-S. Xcode interpolates the other 24 weight/scale variants from them.

    python3 gen_sfsymbols.py --repo /path/to/yoin-symbols --out ../ [names...]

Outputs (under --out):
    YoinSymbols.xcassets/yoin.<name>.symbolset/{Contents.json, yoin.<name>.svg}
    package/Sources/YoinSymbols/Resources/YoinSymbols.xcassets  (same files, for the Swift Package)
    package/Sources/YoinSymbols/YoinSymbol.swift                (generated enum)
    preview/*.svg                                               (debug sheets)

Why our own stroke expander and not shapely's buffer (which gen_font uses): SF Symbols only
interpolates when the three sources have the same number of paths and control points.
A polygon buffer gives a different point count for every width. Here every outline is
built from the centre line with a fixed recipe, so the stroke width only moves points:

  * a centre-line subpath is split into "runs" at sharp corners (tangent breaks);
  * an open run becomes one closed sausage: left offset, round end cap (2 quarter
    cubics), right offset reversed, round start cap;
  * a closed smooth run (circle, squircle) becomes a ring: outer contour + inner
    contour wound the other way;
  * a zero-length run (a dot) becomes a circle of 4 cubics;
  * round joins at sharp corners come for free: the caps of neighbouring runs overlap;
  * fills ("fill" / "fillstroke") keep their polygon, wound like the strokes.
Overlaps are resolved by the non-zero fill rule (all positive contours share a winding).
Cubic offsets: each cubic is split at t=0.5, then each half is offset by moving its end
points along the normals and scaling the handles by (1 - d*kappa), which is exact for
circular arcs (Yoin's corners are arcs or squircle bends).
"""
from __future__ import annotations

import argparse
import json
import math
import shutil
import sys
from pathlib import Path

HERE = Path(__file__).resolve().parent

# ---------------------------------------------------------------- weights and placement
# Stroke width in Yoin grid units (24 x 24 artboard). Regular keeps Yoin's own 1.5.
WEIGHTS = {"Ultralight": 0.5, "Regular": 1.5, "Black": 2.5}
# 16 grid units = the template's small cap height (70 units, Capline-S 76 -> Baseline-S 146).
SCALE = 70.0 / 16.0
CENTER_Y = 111.0  # middle of the cap height: the grid's y = 12 lands here
CENTER_X = {"Ultralight": 265.0, "Regular": 465.0, "Black": 665.0}
K = 0.5522847498  # quarter-circle cubic handle
CORNER_DEG = 8.0  # tangent break above this angle = sharp corner (round join)
SIDE_BEARING = 0.0  # extra margin (template units) outside the outline on each side


# ---------------------------------------------------------------- small vector helpers
def sub(a, b):
    return (a[0] - b[0], a[1] - b[1])


def add(a, b):
    return (a[0] + b[0], a[1] + b[1])


def mul(a, s):
    return (a[0] * s, a[1] * s)


def cross(a, b):
    return a[0] * b[1] - a[1] * b[0]


def length(a):
    return math.hypot(a[0], a[1])


def unit(a):
    n = length(a)
    return (a[0] / n, a[1] / n) if n > 1e-9 else (0.0, 0.0)


def normal(t):
    """Left normal of a unit tangent (rotate +90 in the path's own coordinate system)."""
    return (-t[1], t[0])


def lerp(a, b, t):
    return (a[0] + (b[0] - a[0]) * t, a[1] + (b[1] - a[1]) * t)


# ---------------------------------------------------------------- segments
# A segment is ("L", p0, p1) or ("C", p0, c1, c2, p1), as knock._segments yields them.
def seg_len(s):
    pts = s[1:]
    return sum(length(sub(pts[i + 1], pts[i])) for i in range(len(pts) - 1))


def tangent_in(s):
    if s[0] == "L":
        return unit(sub(s[2], s[1]))
    p0, c1, c2, p1 = s[1:]
    for q in (c1, c2, p1):
        if length(sub(q, p0)) > 1e-6:
            return unit(sub(q, p0))
    return (0.0, 0.0)


def tangent_out(s):
    if s[0] == "L":
        return unit(sub(s[2], s[1]))
    p0, c1, c2, p1 = s[1:]
    for q in (c2, c1, p0):
        if length(sub(p1, q)) > 1e-6:
            return unit(sub(p1, q))
    return (0.0, 0.0)


def split_cubic(s, t=0.5):
    p0, c1, c2, p1 = s[1:]
    a, b, c = lerp(p0, c1, t), lerp(c1, c2, t), lerp(c2, p1, t)
    d, e = lerp(a, b, t), lerp(b, c, t)
    f = lerp(d, e, t)
    return ("C", p0, a, d, f), ("C", f, e, c, p1)


def offset_seg(s, d):
    """Offset of one segment by signed distance d (left positive). Same structure for every d."""
    if s[0] == "L":
        n = normal(tangent_in(s))
        return [("L", add(s[1], mul(n, d)), add(s[2], mul(n, d)))]
    out = []
    for h in split_cubic(s):
        p0, c1, c2, p1 = h[1:]
        n0, n1 = normal(tangent_in(h)), normal(tangent_out(h))
        q0, q3 = add(p0, mul(n0, d)), add(p1, mul(n1, d))
        a, b, c = sub(c1, p0), sub(c2, c1), sub(p1, c2)
        k0 = (2.0 / 3.0) * cross(a, b) / length(a) ** 3 if length(a) > 1e-6 else 0.0
        k1 = (2.0 / 3.0) * cross(b, c) / length(c) ** 3 if length(c) > 1e-6 else 0.0
        q1 = add(q0, mul(a, 1.0 - d * k0))
        q2 = sub(q3, mul(c, 1.0 - d * k1))
        out.append(("C", q0, q1, q2, q3))
    return out


def reverse_seg(s):
    if s[0] == "L":
        return ("L", s[2], s[1])
    return ("C", s[4], s[3], s[2], s[1])


def arc_quarters(center, start_dir, r, turns, sign):
    """`turns` quarter-circle cubics around center, starting at center + r*start_dir.
    sign=+1 rotates towards the left normal, -1 towards the right."""
    segs = []
    u = start_dir
    for _ in range(turns):
        v = mul(normal(u), sign)
        p0, p1 = add(center, mul(u, r)), add(center, mul(v, r))
        segs.append(("C", p0, add(p0, mul(v, K * r)), add(p1, mul(u, K * r)), p1))
        u = v
    return segs


# ---------------------------------------------------------------- outlines
def runs_of(subpath):
    """Drop zero-length segments, then split at sharp corners. Returns (runs, closed)."""
    segs = [s for s in subpath if seg_len(s) > 1e-6]
    if not segs:
        return [[("L", subpath[0][1], subpath[0][1])]], False  # a dot
    closed = length(sub(segs[0][1], segs[-1][-1])) < 1e-6
    cos_lim = math.cos(math.radians(CORNER_DEG))

    def smooth(a, b):
        ta, tb = tangent_out(a), tangent_in(b)
        return ta[0] * tb[0] + ta[1] * tb[1] > cos_lim

    runs, cur = [], [segs[0]]
    for s in segs[1:]:
        if smooth(cur[-1], s):
            cur.append(s)
        else:
            runs.append(cur)
            cur = [s]
    runs.append(cur)
    if closed:
        if len(runs) == 1 and smooth(segs[-1], segs[0]):
            return runs, True  # one smooth loop: ring
        if len(runs) > 1 and smooth(segs[-1], segs[0]):
            runs[0] = runs[-1] + runs[0]  # the start point is not a corner: merge
            runs.pop()
    return runs, False


def polyline_area(segs):
    pts = []
    for s in segs:
        if s[0] == "L":
            pts.append(s[1])
        else:
            pts.extend(lerp_pts(s, 8)[:-1])
    a = 0.0
    for i in range(len(pts)):
        x0, y0 = pts[i]
        x1, y1 = pts[(i + 1) % len(pts)]
        a += x0 * y1 - x1 * y0
    return a / 2.0


def lerp_pts(s, n):
    p0, c1, c2, p1 = s[1:]
    out = []
    for i in range(n + 1):
        t = i / n
        a, b, c = lerp(p0, c1, t), lerp(c1, c2, t), lerp(c2, p1, t)
        out.append(lerp(lerp(a, b, t), lerp(b, c, t), t))
    return out


def sausage(run, h):
    """Closed outline of an open run stroked at half-width h, round caps."""
    if len(run) == 1 and seg_len(run[0]) < 1e-6:  # dot
        c = run[0][1]
        return arc_quarters(c, (1.0, 0.0), h, 4, +1)
    left = [o for s in run for o in offset_seg(s, +h)]
    right = [reverse_seg(o) for s in reversed(run) for o in reversed(offset_seg(s, -h))]
    t_end, t_start = tangent_out(run[-1]), tangent_in(run[0])
    end = run[-1][-1]
    start = run[0][1]
    # left side -> around the end (through +tangent) -> right side: rotate towards -left
    end_cap = arc_quarters(end, normal(t_end), h, 2, -1)
    start_cap = arc_quarters(start, mul(normal(t_start), -1), h, 2, -1)
    return left + end_cap + right + start_cap


def contour_sign(contour):
    return 1 if polyline_area(contour) > 0 else -1


def icon_contours(icon, sw):
    """All closed contours of one icon at stroke width sw, every filled one wound like a sausage."""
    from knock import _segments  # noqa: E402  (repo module, on sys.path via --repo)

    h = sw / 2.0
    ref = contour_sign(sausage([("L", (0.0, 0.0), (10.0, 0.0))], 1.0))  # positive winding
    contours = []
    for layer in icon.layers:
        subs = _segments(layer["d"])
        mode = layer["mode"]
        if mode in ("fill", "fillstroke"):
            fills = []
            for sp in subs:
                segs = [s for s in sp if seg_len(s) > 1e-6]
                if len(segs) < 2:
                    continue
                if length(sub(segs[0][1], segs[-1][-1])) > 1e-6:
                    segs.append(("L", segs[-1][-1], segs[0][1]))
                fills.append(segs)
            if layer.get("rule") == "evenodd":
                # first subpath positive, later ones (holes) negative. Good enough for
                # Yoin's filled twins (outer + inner); verify new symbols in preview/.
                for i, segs in enumerate(fills):
                    want = ref if i == 0 else -ref
                    contours.append(segs if contour_sign(segs) == want else [reverse_seg(s) for s in reversed(segs)])
            else:
                for segs in fills:
                    contours.append(segs if contour_sign(segs) == ref else [reverse_seg(s) for s in reversed(segs)])
        if mode in ("stroke", "fillstroke"):
            for sp in subs:
                runs, is_ring = runs_of(sp)
                if is_ring:
                    run = runs[0]
                    if contour_sign(run) != ref:  # orient the centre line positively
                        run = [reverse_seg(s) for s in reversed(run)]
                    # which side is outside does not depend on the width: decide at a tiny h
                    left_t = [o for s in run for o in offset_seg(s, +0.01)]
                    right_t = [o for s in run for o in offset_seg(s, -0.01)]
                    left_out = abs(polyline_area(left_t)) > abs(polyline_area(right_t))
                    a = [o for s in run for o in offset_seg(s, +h if left_out else -h)]
                    b = [o for s in run for o in offset_seg(s, -h if left_out else +h)]
                    contours += [a, [reverse_seg(o) for o in reversed(b)]]
                else:
                    contours += [sausage(run, h) for run in runs]
    return contours


# ---------------------------------------------------------------- SVG output
def fmt(v):
    s = f"{v:.4f}".rstrip("0").rstrip(".")
    return "0" if s in ("-0", "") else s


def to_d(contours, tf):
    out = []
    for c in contours:
        p = tf(c[0][1])
        out.append(f"M{fmt(p[0])},{fmt(p[1])}")
        for s in c:
            if s[0] == "L":
                q = tf(s[2])
                out.append(f"L{fmt(q[0])},{fmt(q[1])}")
            else:
                a, b, q = tf(s[2]), tf(s[3]), tf(s[4])
                out.append(f"C{fmt(a[0])},{fmt(a[1])} {fmt(b[0])},{fmt(b[1])} {fmt(q[0])},{fmt(q[1])}")
        out.append("Z")
    return " ".join(out)


def signature(contours):
    return [[s[0] for s in c] for c in contours]


def bounds(contours, tf):
    xs = []
    for c in contours:
        for s in c:
            for p in s[1:]:
                xs.append(tf(p)[0])
    return min(xs), max(xs)


# The guides and reference glyph follow SwiftDraw's template (swhitty/SwiftDraw,
# Renderer.SFSymbol.swift), whose output imports into Xcode and the SF Symbols app.
H_REFERENCE = (
    "M85,145.755 L87.685,145.755 L113.369,79.287 L114.052,79.287 L114.052,76 L112.148,76 L85,145.755 Z "
    "M95.693,121.536 L130.996,121.536 L130.263,119.313 L96.474,119.313 L95.693,121.536 Z "
    "M139.15,145.755 L141.787,145.755 L114.638,76 L113.466,76 L113.466,79.287 L139.15,145.755 Z"
)


def template_svg(name, variants):
    """variants: {weight: (d, left_x, right_x)}"""
    g = []
    for w in ("Ultralight", "Regular", "Black"):
        _, lx, rx = variants[w]
        g.append(f'        <path id="left-margin-{w}-S" d="M{fmt(lx)},56 l0,110"/>')
        g.append(f'        <path id="right-margin-{w}-S" d="M{fmt(rx)},56 l0,110"/>')
    sym = []
    for w in ("Ultralight", "Regular", "Black"):
        d = variants[w][0]
        sym.append(f'        <g id="{w}-S">\n            <path d="{d}"/>\n        </g>')
    return f"""<?xml version="1.0" encoding="UTF-8"?>
<!-- Yoin Symbols · {name} · generated by gen_sfsymbols.py from the centre-line source. Do not edit. -->
<svg width="800" height="600" version="1.1" xmlns="http://www.w3.org/2000/svg" xmlns:xlink="http://www.w3.org/1999/xlink">
    <g id="Notes" font-family="'LucidaGrande', 'Lucida Grande', sans-serif" font-weight="500" font-size="13">
        <rect fill="white" height="600" width="800" x="0" y="0"/>
        <g font-size="13">
            <text x="18" y="176">Small</text>
            <text x="18" y="376">Medium</text>
            <text x="18" y="576">Large</text>
        </g>
        <g font-size="9">
            <text x="250" y="30">Ultralight</text>
            <text x="450" y="30">Regular</text>
            <text x="650" y="30">Black</text>
            <text id="template-version" fill="#505050" text-anchor="end" x="785" y="575">Template v.3.0</text>
            <text fill="#505050" text-anchor="end" x="785" y="590">Yoin Symbols · {name}</text>
        </g>
    </g>
    <g id="Guides" stroke="rgb(39,170,225)" stroke-width="0.5">
        <path id="Capline-S" d="M18,76 l800,0"/>
        <path id="H-reference" d="{H_REFERENCE}" stroke="none"/>
        <path id="Baseline-S" d="M18,146 l800,0"/>
{chr(10).join(g)}
        <path id="Capline-M" d="M18,276 l800,0"/>
        <path id="Baseline-M" d="M18,346 l800,0"/>
        <path id="Capline-L" d="M18,476 l800,0"/>
        <path id="Baseline-L" d="M18,546 l800,0"/>
    </g>
    <g id="Symbols">
{chr(10).join(sym)}
    </g>
</svg>
"""


def build_symbol(icon):
    variants, sigs, contour_sets = {}, {}, {}
    for w, sw in WEIGHTS.items():
        cs = icon_contours(icon, sw)
        cx = CENTER_X[w]

        def tf(p, cx=cx):
            return (cx + (p[0] - 12.0) * SCALE, CENTER_Y + (p[1] - 12.0) * SCALE)

        lx, rx = bounds(cs, tf)
        variants[w] = (to_d(cs, tf), lx - SIDE_BEARING, rx + SIDE_BEARING)
        sigs[w] = signature(cs)
        contour_sets[w] = cs
    if not (sigs["Ultralight"] == sigs["Regular"] == sigs["Black"]):
        raise SystemExit(f"{icon.name}: the three sources are not interpolation-compatible")
    return variants, contour_sets


def asset_name(name):
    return f"yoin.{name}"


def write_catalog(root: Path, built):
    if root.exists():
        shutil.rmtree(root)
    root.mkdir(parents=True)
    (root / "Contents.json").write_text(json.dumps({"info": {"author": "xcode", "version": 1}}, indent=2) + "\n")
    for name, (variants, _) in built.items():
        a = asset_name(name)
        d = root / f"{a}.symbolset"
        d.mkdir()
        (d / f"{a}.svg").write_text(template_svg(name, variants))
        (d / "Contents.json").write_text(
            json.dumps(
                {
                    "info": {"author": "xcode", "version": 1},
                    "symbols": [{"filename": f"{a}.svg", "idiom": "universal"}],
                },
                indent=2,
            )
            + "\n"
        )


def camel(name):
    p = "".join(x[:1].upper() + x[1:] for x in name.split("_"))
    return p[:1].lower() + p[1:]


def write_swift_enum(path: Path, names, mirrored):
    cases = "\n".join(f'    case {camel(n)} = "{asset_name(n)}"' for n in names)
    mir = ", ".join(f".{camel(n)}" for n in names if n in mirrored) or ""
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(
        f"""// Generated by gen_sfsymbols.py. Do not edit.
import SwiftUI

/// Every Yoin Symbol, as a custom SF Symbol in this package's asset catalog.
/// Works like a system symbol: follows `.font`, `.fontWeight`, `.imageScale`,
/// Dynamic Type, `.foregroundStyle` and `.symbolEffect`.
public enum YoinSymbol: String, CaseIterable, Sendable {{
{cases}

    /// Directional symbols that flip in right-to-left layouts (Android autoMirror).
    public static let mirrored: Set<YoinSymbol> = [{mir}]
}}

public extension Image {{
    /// `Image(yoin: .playFilled)`
    init(yoin symbol: YoinSymbol) {{
        self.init(symbol.rawValue, bundle: .module)
    }}
}}

public extension Label where Title == Text, Icon == Image {{
    /// `Label("Play", yoin: .playFilled)`
    init(_ title: LocalizedStringKey, yoin symbol: YoinSymbol) {{
        self.init {{ Text(title) }} icon: {{ Image(yoin: symbol) }}
    }}
}}

#if canImport(UIKit)
import UIKit

public extension UIImage {{
    /// `UIImage(yoin: .home, configuration: UIImage.SymbolConfiguration(textStyle: .body))`
    convenience init?(yoin symbol: YoinSymbol, configuration: UIImage.SymbolConfiguration? = nil) {{
        self.init(named: symbol.rawValue, in: .module, with: configuration)
    }}
}}
#endif
"""
    )


def write_preview(path: Path, built):
    """A debug sheet: each symbol at the three sources plus two interpolated weights."""
    rows = []
    y = 0
    for name, (_, sets) in built.items():
        cols = []
        mids = [("Ultralight", None), ("UL-R 50%", ("Ultralight", "Regular", 0.5)), ("Regular", None),
                ("R-B 50%", ("Regular", "Black", 0.5)), ("Black", None)]
        for i, (label, mix) in enumerate(mids):
            if mix is None:
                cs = sets[label]
            else:
                cs = interpolate(sets[mix[0]], sets[mix[1]], mix[2])
            ox = 20 + i * 120

            def tf(p, ox=ox, y=y):
                return (ox + p[0] * 4, 20 + y + p[1] * 4)

            cols.append(f'<path d="{to_d(cs, tf)}" fill="#1c1b1f" fill-rule="nonzero"/>')
            cols.append(f'<text x="{ox}" y="{y + 130}" font-size="11" font-family="sans-serif">{name} · {label}</text>')
        rows.append("".join(cols))
        y += 150
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(
        f'<svg xmlns="http://www.w3.org/2000/svg" width="640" height="{y + 10}" viewBox="0 0 640 {y + 10}">'
        f'<rect width="100%" height="100%" fill="#fff"/>{"".join(rows)}</svg>\n'
    )


def interpolate(a, b, t):
    out = []
    for ca, cb in zip(a, b):
        c = []
        for sa, sb in zip(ca, cb):
            c.append((sa[0],) + tuple(lerp(pa, pb, t) for pa, pb in zip(sa[1:], sb[1:])))
        out.append(c)
    return out


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--repo", required=True, help="path to a yoin-symbols checkout")
    ap.add_argument("--out", default=str(HERE.parent))
    ap.add_argument("names", nargs="*")
    args = ap.parse_args()
    repo = Path(args.repo).resolve()
    sys.path.insert(0, str(repo / "generator"))
    sys.path.insert(0, str(repo / "generator" / "icons"))
    from common import MIRRORED, REGISTRY, names  # noqa: E402

    wanted = args.names or names()
    built = {n: build_symbol(REGISTRY[n]) for n in wanted}
    out = Path(args.out)
    write_catalog(out / "YoinSymbols.xcassets", built)
    pkg = out / "package" / "Sources" / "YoinSymbols"
    write_catalog(pkg / "Resources" / "YoinSymbols.xcassets", built)
    write_swift_enum(pkg / "YoinSymbol.swift", wanted, MIRRORED)
    write_preview(out / "preview" / "weights.svg", built)
    for n, (v, sets) in built.items():
        pts = sum(len(c) for c in sets["Regular"])
        print(f"  {asset_name(n):24s} {len(sets['Regular'])} contours, {pts} segments, compatible")


if __name__ == "__main__":
    main()
