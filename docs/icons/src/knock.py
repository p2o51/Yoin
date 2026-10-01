"""Knockouts for stroked paths: remove the parts of a centre-line path that fall inside a region.

Used where one stroke passes "under" another (shuffle crossing, slashes through
cloud / eye, a phone sitting in front of a monitor). The result keeps true cubic
curves: segments are split with de Casteljau at the region boundary.
"""
from __future__ import annotations

import math
from typing import List, Sequence

from shapely.geometry import LineString, Point, Polygon
from shapely.prepared import prep

from geom import C, L, M, Cmd


def _segments(cmds: Sequence[Cmd]):
    """Yield subpaths as lists of segments; each segment = ('L', p0, p1) or ('C', p0, c1, c2, p1)."""
    subs = []
    cur = []
    start = None
    pos = None
    for c in cmds:
        op = c[0]
        if op == "M":
            if cur:
                subs.append(cur)
            cur = []
            start = pos = (c[1], c[2])
        elif op == "L":
            cur.append(("L", pos, (c[1], c[2])))
            pos = (c[1], c[2])
        elif op == "C":
            cur.append(("C", pos, (c[1], c[2]), (c[3], c[4]), (c[5], c[6])))
            pos = (c[5], c[6])
        elif op == "Z":
            if pos != start and start is not None:
                cur.append(("L", pos, start))
            pos = start
    if cur:
        subs.append(cur)
    return subs


def _lerp(a, b, t):
    return (a[0] + (b[0] - a[0]) * t, a[1] + (b[1] - a[1]) * t)


def _point(seg, t):
    if seg[0] == "L":
        return _lerp(seg[1], seg[2], t)
    p0, c1, c2, p1 = seg[1:]
    a = _lerp(p0, c1, t)
    b = _lerp(c1, c2, t)
    c = _lerp(c2, p1, t)
    d = _lerp(a, b, t)
    e = _lerp(b, c, t)
    return _lerp(d, e, t)


def _sub(seg, t0, t1):
    """Portion of a segment between t0 and t1."""
    if seg[0] == "L":
        return ("L", _point(seg, t0), _point(seg, t1))
    p0, c1, c2, p1 = seg[1:]

    def split(p0, c1, c2, p1, t):
        a = _lerp(p0, c1, t)
        b = _lerp(c1, c2, t)
        c = _lerp(c2, p1, t)
        d = _lerp(a, b, t)
        e = _lerp(b, c, t)
        f = _lerp(d, e, t)
        return (p0, a, d, f), (f, e, c, p1)

    _, right = split(p0, c1, c2, p1, t0)
    if t1 >= 1.0:
        q = right
    else:
        tt = (t1 - t0) / (1 - t0) if t0 < 1 else 0
        q, _ = split(*right, tt)
    return ("C", *q)


def cut(cmds: Sequence[Cmd], region: Polygon, samples=240) -> List[Cmd]:
    """Remove the parts of `cmds` inside `region`. Returns open subpaths (M/L/C)."""
    pr = prep(region)

    def inside(p):
        return pr.contains(Point(p))

    pieces = []  # list of lists of segments (continuous runs)
    for sub in _segments(cmds):
        run = []
        for seg in sub:
            ts = [i / samples for i in range(samples + 1)]
            flags = [inside(_point(seg, t)) for t in ts]
            cuts = [0.0]
            for i in range(samples):
                if flags[i] != flags[i + 1]:
                    lo, hi = ts[i], ts[i + 1]
                    for _ in range(40):
                        mid = (lo + hi) / 2
                        if inside(_point(seg, mid)) == flags[i]:
                            lo = mid
                        else:
                            hi = mid
                    cuts.append((lo + hi) / 2)
            cuts.append(1.0)
            for j in range(len(cuts) - 1):
                t0, t1 = cuts[j], cuts[j + 1]
                if t1 - t0 < 1e-6:
                    continue
                keep = not inside(_point(seg, (t0 + t1) / 2))
                if keep:
                    run.append(_sub(seg, t0, t1))
                else:
                    if run:
                        pieces.append(run)
                        run = []
        if run:
            pieces.append(run)
    # merge a closed subpath whose first and last runs touch
    out: List[Cmd] = []
    merged = []
    for run in pieces:
        if merged and _close(merged[-1][-1], run[0]) and False:
            merged[-1].extend(run)
        else:
            merged.append(run)
    # if first run starts where last run of the same original subpath ends, join them
    if len(merged) >= 2 and _close(merged[-1][-1], merged[0][0]):
        merged[0] = merged[-1] + merged[0]
        merged.pop()
    for run in merged:
        p0 = run[0][1]
        out.append(M(*p0))
        for seg in run:
            if seg[0] == "L":
                out.append(L(*seg[2]))
            else:
                out.append(C(*seg[2], *seg[3], *seg[4]))
    return out


def _close(seg_a, seg_b, eps=1e-4):
    end = seg_a[-1]
    start = seg_b[1]
    return math.hypot(end[0] - start[0], end[1] - start[1]) < eps


def band(x1, y1, x2, y2, half_width, extend=2.0) -> Polygon:
    """Region around a straight segment (for slash / crossing gaps)."""
    dx, dy = x2 - x1, y2 - y1
    l = math.hypot(dx, dy)
    ux, uy = dx / l, dy / l
    x1 -= ux * extend
    y1 -= uy * extend
    x2 += ux * extend
    y2 += uy * extend
    return LineString([(x1, y1), (x2, y2)]).buffer(half_width, cap_style=2, join_style=2)


def around(cmds: Sequence[Cmd], half_width, samples=60) -> Polygon:
    """Region around an arbitrary centre-line path (round caps)."""
    pts = []
    polys = []
    for sub in _segments(cmds):
        line_pts = [sub[0][1]]
        for seg in sub:
            for i in range(1, samples + 1):
                line_pts.append(_point(seg, i / samples))
        polys.append(LineString(line_pts).buffer(half_width, cap_style=1, join_style=1, quad_segs=24))
    from shapely.ops import unary_union

    return unary_union(polys)


def fill_region(cmds: Sequence[Cmd], samples=60) -> Polygon:
    """Polygon enclosed by a closed centre-line path."""
    polys = []
    for sub in _segments(cmds):
        pts = [sub[0][1]]
        for seg in sub:
            for i in range(1, samples + 1):
                pts.append(_point(seg, i / samples))
        polys.append(Polygon(pts))
    from shapely.ops import unary_union

    return unary_union(polys)
