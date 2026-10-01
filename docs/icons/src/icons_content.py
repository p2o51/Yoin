"""Notes, memories, stats and list utilities."""
import math

from geom import *
from icons_music import bubble, star
from icons_nav import _pencil
from knock import around, band, cut, fill_region
from registry import F, FS, S, icon
from shapely.geometry import box
from shapely.ops import unary_union


def note_page():
    pts = [(4.25, 4.25), (19.75, 4.25), (19.75, 14.35), (14.35, 19.75), (4.25, 19.75)]
    return rounded_poly(pts, [3.0, 3.0, 0.9, 0.9, 3.0])


@icon("note", "content", "笔记 / Write", replaces=["Icons.AutoMirrored.Rounded.StickyNote2"])
def _():
    fold = rounded_polyline([(14.35, 19.75), (14.35, 14.35), (19.75, 14.35)], [0, 1.9, 0])
    return [S(note_page()), S(fold), S(line(8.1, 9.0, 15.9, 9.0)), S(line(8.1, 12.55, 12.4, 12.55))]


@icon("edit_note", "content", "编辑笔记", replaces=["Icons.Rounded.EditNote"])
def _():
    body, er = _pencil(tip=(11.95, 20.1), length=12.2, width=3.45, cone=3.0, eraser=2.55)
    return [
        S(line(4.0, 6.25, 16.0, 6.25)),
        S(line(4.0, 10.75, 13.0, 10.75)),
        S(line(4.0, 15.25, 8.6, 15.25)),
        S(body),
        S(er),
    ]


@icon("reviews", "content", "乐评 / NeoDB", replaces=["Icons.Rounded.Reviews"])
def _():
    return [S(bubble()), S(star(cx=12.0, cy=10.65, R=4.05, r=1.95, tip=0.6, inner=0.3))]


@icon("insights", "content", "统计 / 洞察", replaces=["Icons.Outlined.Insights"])
def _():
    chart = rounded_polyline([(3.75, 18.0), (8.7, 12.6), (12.7, 15.6), (20.25, 7.4)], [0, 1.3, 1.3, 0])
    from icons_music import sparkle_path

    return [S(chart), S(sparkle_path(7.1, 6.6, 2.75))]


@icon("explicit", "content", "Explicit 标记")
def _():
    return [
        S(squircle(4, 4, 16, 16, r=4.0, s=0.6)),
        S(polyline([(14.2, 8.1), (9.8, 8.1), (9.8, 15.9), (14.2, 15.9)])),
        S(line(9.8, 12.0, 13.6, 12.0)),
    ]


@icon("sort", "content", "排序")
def _():
    return [S(line(4.5, 7, 19.5, 7)), S(line(4.5, 12, 15, 12)), S(line(4.5, 17, 10.5, 17))]


@icon("filter", "content", "筛选")
def _():
    return [S(line(4.25, 7, 19.75, 7)), S(line(7.25, 12, 16.75, 12)), S(line(10.25, 17, 13.75, 17))]


def crescent(c1=(12.0, 12.0), r1=8.3, c2=(16.3, 7.7), r2=6.9):
    (x1, y1), (x2, y2) = c1, c2
    d = math.hypot(x2 - x1, y2 - y1)
    a = (r1 ** 2 - r2 ** 2 + d ** 2) / (2 * d)
    h = math.sqrt(r1 ** 2 - a ** 2)
    xm = x1 + a * (x2 - x1) / d
    ym = y1 + a * (y2 - y1) / d
    p1 = (xm + h * (y2 - y1) / d, ym - h * (x2 - x1) / d)
    p2 = (xm - h * (y2 - y1) / d, ym + h * (x2 - x1) / d)
    ang = lambda c, p: math.degrees(math.atan2(p[1] - c[1], p[0] - c[0]))
    # outer arc of circle 1 (the long way round, away from circle 2)
    a1s, a1e = ang(c1, p1), ang(c1, p2)
    # choose sweep that avoids c2 direction
    mid_dir = ang(c1, c2)
    sweep = (a1e - a1s) % 360
    if sweep == 0:
        sweep = 360
    # test whether the clockwise sweep passes the direction of c2
    def passes(start, sw, target):
        t = (target - start) % 360
        return t < sw
    if passes(a1s, sweep, mid_dir):
        sweep = sweep - 360
    out = arc(x1, y1, r1, a1s, a1s + sweep)
    # inner arc of circle 2 back to p1 (inside circle 1)
    a2s, a2e = ang(c2, p2), ang(c2, p1)
    sw2 = (a2e - a2s) % 360
    # the inner arc must pass through the point of circle 2 closest to c1
    towards = ang(c2, c1)
    if not passes(a2s, sw2, towards):
        sw2 = sw2 - 360
    out += arc_cmds(x2, y2, r2, a2s, a2s + sw2, move=False)
    out += [Z]
    return out


@icon("sleep", "content", "睡眠定时")
def _():
    return [S(crescent())]


@icon("speed", "content", "播放速度")
def _():
    cx, cy, r = 12.0, 13.2, 8.2
    return [S(arc(cx, cy, r, 148, 392)), S(line(cx, cy, 15.95, 9.25)), F(circle(cx, cy, 1.45))]


def cookie(cx=12.0, cy=12.0, rm=7.85, amp=0.62, lobes=9, n=10):
    pts = []
    for i in range(lobes * n):
        th = 2 * math.pi * i / (lobes * n) - math.pi / 2
        r = rm + amp * math.cos(lobes * th)
        pts.append((cx + r * math.cos(th), cy + r * math.sin(th)))
    return smooth_closed(pts)


@icon("memory", "content", "专辑记忆（印章）", note="提案：Memories 入口 / 生成完成的印章态")
def _():
    from icons_music import heart

    h = scale(heart(), 0.36, cx=12, cy=12.2)
    return [S(cookie()), S(h)]


def _library_front_back():
    from icons_nav import FRONT, _library_back

    x, y, w, h = FRONT
    return squircle(x, y, w, h, r=3.3, s=0.6), _library_back(), (x + w / 2, y + h / 2)


@icon("library_add", "content", "加入资料库", note="Apple Music：+ → 确认中 → ✓")
def _():
    front, back, (cx, cy) = _library_front_back()
    return [S(front, id="front"), S(back, id="back"), S(line(cx, cy - 3.1, cx, cy + 3.1), id="plus-v"), S(line(cx - 3.1, cy, cx + 3.1, cy), id="plus-h")]


@icon("library_added", "content", "已加入资料库")
def _():
    front, back, (cx, cy) = _library_front_back()
    return [S(front, id="front"), S(back, id="back"), S(polyline([(cx - 3.05, cy + 0.1), (cx - 0.85, cy + 2.3), (cx + 3.2, cy - 1.75)]), id="check")]
