"""
Yoin icon geometry kit.

Every path is built from absolute M / L / C / Z commands only, so that
  * Android VectorDrawable and Compose PathParser read it without surprises,
  * two states of an icon can be morphed when they share a command list.

Coordinates live on a 24 x 24 grid. Strokes are 1.5 wide with round caps and joins,
unless a layer says otherwise.
"""
from __future__ import annotations

import math
from typing import Iterable, List, Sequence, Tuple

Pt = Tuple[float, float]
Cmd = tuple  # ('M', x, y) | ('L', x, y) | ('C', x1, y1, x2, y2, x, y) | ('Z',)

SW = 1.5  # stroke width


# --------------------------------------------------------------------------- formatting

def fnum(v: float) -> str:
    v = round(v, 3)
    if abs(v) < 5e-4:
        v = 0.0
    s = f"{v:.3f}".rstrip("0").rstrip(".")
    return s if s not in ("", "-0") else "0"


def to_d(cmds: Sequence[Cmd]) -> str:
    out = []
    for c in cmds:
        op = c[0]
        if op == "Z":
            out.append("Z")
        else:
            out.append(op + " ".join(fnum(v) for v in c[1:]))
    return " ".join(out)


def join(*paths: Sequence[Cmd]) -> List[Cmd]:
    out: List[Cmd] = []
    for p in paths:
        out.extend(p)
    return out


# --------------------------------------------------------------------------- transforms

def transform(cmds: Sequence[Cmd], fn) -> List[Cmd]:
    out = []
    for c in cmds:
        if c[0] == "Z":
            out.append(c)
            continue
        vals = list(c[1:])
        pts = []
        for i in range(0, len(vals), 2):
            pts.extend(fn(vals[i], vals[i + 1]))
        out.append((c[0], *pts))
    return out


def translate(cmds, dx, dy):
    return transform(cmds, lambda x, y: (x + dx, y + dy))


def scale(cmds, s, cx=12.0, cy=12.0, sy=None):
    sy = s if sy is None else sy
    return transform(cmds, lambda x, y: (cx + (x - cx) * s, cy + (y - cy) * sy))


def rotate(cmds, deg, cx=12.0, cy=12.0):
    a = math.radians(deg)
    ca, sa = math.cos(a), math.sin(a)
    return transform(cmds, lambda x, y: (cx + (x - cx) * ca - (y - cy) * sa, cy + (x - cx) * sa + (y - cy) * ca))


def mirror_x(cmds, cx=12.0):
    return transform(cmds, lambda x, y: (2 * cx - x, y))


def mirror_y(cmds, cy=12.0):
    return transform(cmds, lambda x, y: (x, 2 * cy - y))


def reverse(cmds: Sequence[Cmd]) -> List[Cmd]:
    """Reverse an open path made of one M followed by L/C segments."""
    assert cmds[0][0] == "M"
    closed = cmds[-1][0] == "Z"
    body = [c for c in cmds[1:] if c[0] != "Z"]
    pts = [(cmds[0][1], cmds[0][2])]
    segs = []
    for c in body:
        if c[0] == "L":
            segs.append(("L",))
            pts.append((c[1], c[2]))
        elif c[0] == "C":
            segs.append(("C", c[1], c[2], c[3], c[4]))
            pts.append((c[5], c[6]))
    out: List[Cmd] = [("M", *pts[-1])]
    for i in range(len(segs) - 1, -1, -1):
        s = segs[i]
        x, y = pts[i]
        if s[0] == "L":
            out.append(("L", x, y))
        else:
            out.append(("C", s[3], s[4], s[1], s[2], x, y))
    if closed:
        out.append(("Z",))
    return out


# --------------------------------------------------------------------------- primitives

def M(x, y):
    return ("M", x, y)


def L(x, y):
    return ("L", x, y)


def C(x1, y1, x2, y2, x, y):
    return ("C", x1, y1, x2, y2, x, y)


Z = ("Z",)


def line(x1, y1, x2, y2) -> List[Cmd]:
    return [M(x1, y1), L(x2, y2)]


def polyline(pts: Sequence[Pt], closed=False) -> List[Cmd]:
    out = [M(*pts[0])] + [L(*p) for p in pts[1:]]
    if closed:
        out.append(Z)
    return out


def dot_path(cx, cy, r):
    return circle(cx, cy, r)


def _arc_segment(cx, cy, rx, ry, a0, a1) -> Cmd:
    """One cubic for an elliptical arc a0 -> a1 (radians, |a1-a0| <= 90deg)."""
    da = a1 - a0
    k = 4.0 / 3.0 * math.tan(da / 4.0)
    x0, y0 = cx + rx * math.cos(a0), cy + ry * math.sin(a0)
    x3, y3 = cx + rx * math.cos(a1), cy + ry * math.sin(a1)
    x1 = x0 - k * rx * math.sin(a0)
    y1 = y0 + k * ry * math.cos(a0)
    x2 = x3 + k * rx * math.sin(a1)
    y2 = y3 - k * ry * math.cos(a1)
    return C(x1, y1, x2, y2, x3, y3)


def arc_cmds(cx, cy, r, deg0, deg1, ry=None, move=True, max_seg=90.0) -> List[Cmd]:
    """Arc from deg0 to deg1 (screen degrees: 0 = +x, 90 = +y / down). Direction follows the sign."""
    ry = r if ry is None else ry
    a0, a1 = math.radians(deg0), math.radians(deg1)
    n = max(1, int(math.ceil(abs(deg1 - deg0) / max_seg - 1e-9)))
    out: List[Cmd] = []
    if move:
        out.append(M(cx + r * math.cos(a0), cy + ry * math.sin(a0)))
    for i in range(n):
        s = a0 + (a1 - a0) * i / n
        e = a0 + (a1 - a0) * (i + 1) / n
        out.append(_arc_segment(cx, cy, r, ry, s, e))
    return out


def arc(cx, cy, r, deg0, deg1, ry=None):
    return arc_cmds(cx, cy, r, deg0, deg1, ry=ry, move=True)


def circle(cx, cy, r, start=-90.0, ccw=False) -> List[Cmd]:
    end = start - 360 if ccw else start + 360
    return arc_cmds(cx, cy, r, start, end) + [Z]


def ellipse(cx, cy, rx, ry, start=-90.0) -> List[Cmd]:
    return arc_cmds(cx, cy, rx, start, start + 360, ry=ry) + [Z]


def pt_on(cx, cy, r, deg) -> Pt:
    a = math.radians(deg)
    return cx + r * math.cos(a), cy + r * math.sin(a)


# --------------------------------------------------------------------------- endpoint arcs (SVG 'A')

def svg_arc_to_cubics(x1, y1, rx, ry, phi_deg, fa, fs, x2, y2) -> List[Cmd]:
    """Convert an SVG endpoint arc to cubic segments (no leading M)."""
    if rx == 0 or ry == 0:
        return [L(x2, y2)]
    phi = math.radians(phi_deg)
    cp, sp = math.cos(phi), math.sin(phi)
    dx, dy = (x1 - x2) / 2.0, (y1 - y2) / 2.0
    x1p = cp * dx + sp * dy
    y1p = -sp * dx + cp * dy
    rx, ry = abs(rx), abs(ry)
    lam = (x1p ** 2) / (rx ** 2) + (y1p ** 2) / (ry ** 2)
    if lam > 1:
        s = math.sqrt(lam)
        rx *= s
        ry *= s
    num = rx * rx * ry * ry - rx * rx * y1p * y1p - ry * ry * x1p * x1p
    den = rx * rx * y1p * y1p + ry * ry * x1p * x1p
    coef = math.sqrt(max(0.0, num / den)) if den else 0.0
    if fa == fs:
        coef = -coef
    cxp = coef * (rx * y1p / ry)
    cyp = coef * (-ry * x1p / rx)
    cx = cp * cxp - sp * cyp + (x1 + x2) / 2.0
    cy = sp * cxp + cp * cyp + (y1 + y2) / 2.0

    def ang(ux, uy, vx, vy):
        a = math.atan2(ux * vy - uy * vx, ux * vx + uy * vy)
        return a

    t1 = ang(1, 0, (x1p - cxp) / rx, (y1p - cyp) / ry)
    dt = ang((x1p - cxp) / rx, (y1p - cyp) / ry, (-x1p - cxp) / rx, (-y1p - cyp) / ry)
    if not fs and dt > 0:
        dt -= 2 * math.pi
    elif fs and dt < 0:
        dt += 2 * math.pi
    n = max(1, int(math.ceil(abs(dt) / (math.pi / 2) - 1e-9)))
    out = []
    for i in range(n):
        a0 = t1 + dt * i / n
        a1 = t1 + dt * (i + 1) / n
        seg = _arc_segment(0, 0, rx, ry, a0, a1)
        pts = seg[1:]
        rp = []
        for j in range(0, 6, 2):
            px, py = pts[j], pts[j + 1]
            rp.extend((cp * px - sp * py + cx, sp * px + cp * py + cy))
        out.append(C(*rp))
    return out


# --------------------------------------------------------------------------- rounded shapes

def rrect(x, y, w, h, r, start="tl") -> List[Cmd]:
    """Plain circular-corner rounded rectangle, clockwise, starting on the top edge."""
    r = min(r, w / 2, h / 2)
    k = 0.5522847498 * r
    x2, y2 = x + w, y + h
    out = [M(x + r, y), L(x2 - r, y), C(x2 - r + k, y, x2, y + r - k, x2, y + r), L(x2, y2 - r),
           C(x2, y2 - r + k, x2 - r + k, y2, x2 - r, y2), L(x + r, y2),
           C(x + r - k, y2, x, y2 - r + k, x, y2 - r), L(x, y + r),
           C(x, y + r - k, x + r - k, y, x + r, y), Z]
    return out


def _smooth_corner_params(r, s):
    p = (1 + s) * r
    arc_measure = 90.0 * (1 - s)
    arc_len = math.sin(math.radians(arc_measure / 2)) * r * math.sqrt(2)
    alpha = (90.0 - arc_measure) / 2
    p34 = r * math.tan(math.radians(alpha / 2))
    beta = 45.0 * s
    c = p34 * math.cos(math.radians(beta))
    d = c * math.tan(math.radians(beta))
    b = (p - arc_len - c - d) / 3
    a = 2 * b
    return a, b, c, d, p, arc_len


def squircle(x, y, w, h, r=4.6, s=0.62, corners=(True, True, True, True)) -> List[Cmd]:
    """Continuous-curvature rounded rectangle (Figma corner smoothing), clockwise.

    corners = (top-right, bottom-right, bottom-left, top-left); False gives a square corner.
    The smoothed corner reaches (1 + s) * r along each edge, like Luminous containers.
    """
    r = min(r, w / 2 / (1 + s), h / 2 / (1 + s)) if s > 0 else min(r, w / 2, h / 2)
    a, b, c, d, p, al = _smooth_corner_params(r, s)
    X2, Y2 = x + w, y + h
    tr, br, bl, tl = corners
    out: List[Cmd] = []
    # start on the top edge right after the top-left corner
    out.append(M(x + (p if tl else 0), y))
    # top-right
    if tr:
        sx, sy = X2 - p, y
        out.append(L(sx, sy))
        out.append(C(sx + a, sy, sx + a + b, sy, sx + a + b + c, sy + d))
        ax, ay = sx + a + b + c, sy + d
        out.extend(svg_arc_to_cubics(ax, ay, r, r, 0, 0, 1, ax + al, ay + al))
        ax, ay = ax + al, ay + al
        out.append(C(ax + d, ay + c, ax + d, ay + b + c, ax + d, ay + a + b + c))
    else:
        out.append(L(X2, y))
    # right edge -> bottom-right
    if br:
        sx, sy = X2, Y2 - p
        out.append(L(sx, sy))
        out.append(C(sx, sy + a, sx, sy + a + b, sx - d, sy + a + b + c))
        ax, ay = sx - d, sy + a + b + c
        out.extend(svg_arc_to_cubics(ax, ay, r, r, 0, 0, 1, ax - al, ay + al))
        ax, ay = ax - al, ay + al
        out.append(C(ax - c, ay + d, ax - b - c, ay + d, ax - a - b - c, ay + d))
    else:
        out.append(L(X2, Y2))
    # bottom edge -> bottom-left
    if bl:
        sx, sy = x + p, Y2
        out.append(L(sx, sy))
        out.append(C(sx - a, sy, sx - a - b, sy, sx - a - b - c, sy - d))
        ax, ay = sx - a - b - c, sy - d
        out.extend(svg_arc_to_cubics(ax, ay, r, r, 0, 0, 1, ax - al, ay - al))
        ax, ay = ax - al, ay - al
        out.append(C(ax - d, ay - c, ax - d, ay - b - c, ax - d, ay - a - b - c))
    else:
        out.append(L(x, Y2))
    # left edge -> top-left
    if tl:
        sx, sy = x, y + p
        out.append(L(sx, sy))
        out.append(C(sx, sy - a, sx, sy - a - b, sx + d, sy - a - b - c))
        ax, ay = sx + d, sy - a - b - c
        out.extend(svg_arc_to_cubics(ax, ay, r, r, 0, 0, 1, ax + al, ay - al))
        ax, ay = ax + al, ay - al
        out.append(C(ax + c, ay - d, ax + b + c, ay - d, ax + a + b + c, ay - d))
    else:
        out.append(L(x, y))
    out.append(Z)
    return out


def squircle_c(cx, cy, w, h, r=4.6, s=0.62, **kw):
    return squircle(cx - w / 2, cy - h / 2, w, h, r, s, **kw)


def _unit(vx, vy):
    l = math.hypot(vx, vy)
    return vx / l, vy / l


def rounded_poly(pts: Sequence[Pt], radii, closed=True) -> List[Cmd]:
    """Polygon with a circular fillet of radius radii[i] at each vertex (closed)."""
    n = len(pts)
    if not isinstance(radii, (list, tuple)):
        radii = [radii] * n
    corners = []
    for i in range(n):
        px, py = pts[i - 1]
        vx, vy = pts[i]
        nx, ny = pts[(i + 1) % n]
        u1 = _unit(px - vx, py - vy)
        u2 = _unit(nx - vx, ny - vy)
        cosang = max(-1.0, min(1.0, u1[0] * u2[0] + u1[1] * u2[1]))
        theta = math.acos(cosang)
        r = radii[i]
        if r <= 0 or theta < 1e-6:
            corners.append(((vx, vy), (vx, vy), None))
            continue
        t = r / math.tan(theta / 2)
        p1 = (vx + u1[0] * t, vy + u1[1] * t)
        p2 = (vx + u2[0] * t, vy + u2[1] * t)
        bis = _unit(u1[0] + u2[0], u1[1] + u2[1])
        dist = r / math.sin(theta / 2)
        cc = (vx + bis[0] * dist, vy + bis[1] * dist)
        corners.append((p1, p2, (cc, r)))
    out: List[Cmd] = [M(*corners[-1][1])]
    for (p1, p2, arcinfo) in corners:
        out.append(L(*p1))
        if arcinfo:
            (cx, cy), r = arcinfo
            a0 = math.degrees(math.atan2(p1[1] - cy, p1[0] - cx))
            a1 = math.degrees(math.atan2(p2[1] - cy, p2[0] - cx))
            da = (a1 - a0 + 540) % 360 - 180
            out.extend(arc_cmds(cx, cy, r, a0, a0 + da, move=False))
    out.append(Z)
    return out


def rounded_polyline(pts: Sequence[Pt], radii) -> List[Cmd]:
    """Open polyline with fillets at interior vertices."""
    n = len(pts)
    if not isinstance(radii, (list, tuple)):
        radii = [radii] * n
    out: List[Cmd] = [M(*pts[0])]
    for i in range(1, n - 1):
        px, py = pts[i - 1]
        vx, vy = pts[i]
        nx, ny = pts[i + 1]
        u1 = _unit(px - vx, py - vy)
        u2 = _unit(nx - vx, ny - vy)
        theta = math.acos(max(-1.0, min(1.0, u1[0] * u2[0] + u1[1] * u2[1])))
        r = radii[i]
        if r <= 0 or theta < 1e-6 or abs(theta - math.pi) < 1e-6:
            out.append(L(vx, vy))
            continue
        t = r / math.tan(theta / 2)
        p1 = (vx + u1[0] * t, vy + u1[1] * t)
        p2 = (vx + u2[0] * t, vy + u2[1] * t)
        bis = _unit(u1[0] + u2[0], u1[1] + u2[1])
        dist = r / math.sin(theta / 2)
        cx, cy = vx + bis[0] * dist, vy + bis[1] * dist
        out.append(L(*p1))
        a0 = math.degrees(math.atan2(p1[1] - cy, p1[0] - cx))
        a1 = math.degrees(math.atan2(p2[1] - cy, p2[0] - cx))
        da = (a1 - a0 + 540) % 360 - 180
        out.extend(arc_cmds(cx, cy, r, a0, a0 + da, move=False))
    out.append(L(*pts[-1]))
    return out


def capsule(x1, y1, x2, y2, r) -> List[Cmd]:
    """Closed stadium around segment (x1,y1)-(x2,y2) with radius r (for knockouts)."""
    ang = math.degrees(math.atan2(y2 - y1, x2 - x1))
    out = arc_cmds(x2, y2, r, ang - 90, ang + 90)
    out += [L(*pt_on(x1, y1, r, ang + 90))]
    out += arc_cmds(x1, y1, r, ang + 90, ang + 270, move=False)
    out.append(Z)
    return out


def smooth_closed(pts: Sequence[Pt], tension=1.0) -> List[Cmd]:
    """Closed Catmull-Rom spline through pts, as cubics."""
    n = len(pts)
    out: List[Cmd] = [M(*pts[0])]
    for i in range(n):
        p0 = pts[i - 1]
        p1 = pts[i]
        p2 = pts[(i + 1) % n]
        p3 = pts[(i + 2) % n]
        c1 = (p1[0] + (p2[0] - p0[0]) / 6 * tension, p1[1] + (p2[1] - p0[1]) / 6 * tension)
        c2 = (p2[0] - (p3[0] - p1[0]) / 6 * tension, p2[1] - (p3[1] - p1[1]) / 6 * tension)
        out.append(C(*c1, *c2, *p2))
    out.append(Z)
    return out


def smooth_open(pts: Sequence[Pt], tension=1.0) -> List[Cmd]:
    n = len(pts)
    out: List[Cmd] = [M(*pts[0])]
    for i in range(n - 1):
        p0 = pts[i - 1] if i > 0 else pts[i]
        p1 = pts[i]
        p2 = pts[i + 1]
        p3 = pts[i + 2] if i + 2 < n else pts[i + 1]
        c1 = (p1[0] + (p2[0] - p0[0]) / 6 * tension, p1[1] + (p2[1] - p0[1]) / 6 * tension)
        c2 = (p2[0] - (p3[0] - p1[0]) / 6 * tension, p2[1] - (p3[1] - p1[1]) / 6 * tension)
        out.append(C(*c1, *c2, *p2))
    return out
