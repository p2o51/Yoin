"""Playback & music content."""
import math

from geom import *
from knock import around, band, cut, fill_region
from registry import F, FS, S, icon
from shapely.geometry import box
from shapely.ops import unary_union

# ---------------------------------------------------------------- transport

PLAY = [(8.35, 5.15), (19.25, 12.0), (8.35, 18.85)]


def play_path():
    return rounded_poly(PLAY, [0.85, 0.95, 0.85])


@icon("play_arrow", "music", "播放", replaces=["Icons.Rounded.PlayArrow"])
def _():
    return [S(play_path())]


# Filled transport uses plain polygons (tight 0.75 corners from the round join) so that
# play <-> pause can morph vertex-for-vertex: the triangle is two halves, pause is two bars.
PLAY_SPLIT_X = 13.8


def _edge_y(x, a, b):
    return a[1] + (x - a[0]) / (b[0] - a[0]) * (b[1] - a[1])


def play_halves():
    a, tip, c = PLAY
    xm = PLAY_SPLIT_X
    yt, yb = _edge_y(xm, a, tip), _edge_y(xm, c, tip)
    left = [a, (xm, yt), (xm, yb), c]
    right = [(xm, yt), tip, tip, (xm, yb)]
    return left, right


def pause_quads(w=3.1, h=13.0, gap=3.9):
    x1 = 12 - gap / 2 - w
    x2 = 12 + gap / 2
    y0, y1 = 12 - h / 2, 12 + h / 2
    return [(x1, y0), (x1 + w, y0), (x1 + w, y1), (x1, y1)], [(x2, y0), (x2 + w, y0), (x2 + w, y1), (x2, y1)]


@icon("play_filled", "music", "播放（实心）", replaces=["Icons.Filled.PlayArrow"])
def _():
    l, r = play_halves()
    return [FS(polyline(l, closed=True), id="half-l"), FS(polyline(r, closed=True), id="half-r")]


def pause_bars(w=3.3, h=13.0, gap=4.4, r=1.55):
    x1 = 12 - gap / 2 - w
    x2 = 12 + gap / 2
    y = 12 - h / 2
    return rrect(x1, y, w, h, r), rrect(x2, y, w, h, r)


@icon("pause", "music", "暂停")
def _():
    a, b = pause_bars()
    return [S(a), S(b)]


@icon("pause_filled", "music", "暂停（实心）")
def _():
    a, b = pause_quads()
    return [FS(polyline(a, closed=True), id="half-l"), FS(polyline(b, closed=True), id="half-r")]


SKIP_TRI = [(6.0, 6.3), (15.2, 12.0), (6.0, 17.7)]


def skip_next_parts():
    return rounded_poly(SKIP_TRI, [0.8, 0.9, 0.8]), line(18.4, 6.3, 18.4, 17.7)


@icon("skip_next", "music", "下一首", replaces=["Icons.Rounded.SkipNext"])
def _():
    t, b = skip_next_parts()
    return [S(t), S(b)]


@icon("skip_previous", "music", "上一首", replaces=["Icons.Rounded.SkipPrevious"])
def _():
    t, b = skip_next_parts()
    return [S(mirror_x(t)), S(mirror_x(b))]


@icon("skip_next_filled", "music", "下一首（实心）")
def _():
    _, b = skip_next_parts()
    return [FS(polyline(SKIP_TRI, closed=True), id="triangle"), S(b, id="bar")]


@icon("skip_previous_filled", "music", "上一首（实心）")
def _():
    _, b = skip_next_parts()
    return [FS(mirror_x(polyline(SKIP_TRI, closed=True)), id="triangle"), S(mirror_x(b), id="bar")]


def _head(ex, ey, dirx, diry, size=3.0, spread=45):
    l = math.hypot(dirx, diry)
    dx, dy = -dirx / l, -diry / l
    pts = []
    for s in (spread, -spread):
        a = math.radians(s)
        pts.append((ex + (dx * math.cos(a) - dy * math.sin(a)) * size, ey + (dx * math.sin(a) + dy * math.cos(a)) * size))
    return polyline([pts[0], (ex, ey), pts[1]])


# shuffle: two S-strands that cross, the lower one passes underneath
SHUF_A = [M(3.8, 17.2), C(10.6, 17.2, 13.2, 6.8, 20.0, 6.8)]
SHUF_B = [M(3.8, 6.8), C(10.6, 6.8, 13.2, 17.2, 20.0, 17.2)]


@icon("shuffle", "music", "随机播放", replaces=["Icons.Rounded.Shuffle", "Icons.Filled.Shuffle"])
def _():
    gap = around(SHUF_A, 0.75 + 1.05)
    b = cut(SHUF_B, gap)
    return [
        S(SHUF_A, id="strand-over"),
        S(b, id="strand-under"),
        S(_head(20.0, 6.8, 1, 0, size=3.3), id="head-over"),
        S(_head(20.0, 17.2, 1, 0, size=3.3), id="head-under"),
    ]


def _repeat_loop():
    loop = squircle(4.4, 6.75, 15.2, 10.5, r=3.2, s=0.6)
    region = unary_union([box(17.2, -5, 30, 11.4), box(-5, 12.6, 6.8, 30)])
    return cut(loop, region)


@icon("repeat", "music", "列表循环")
def _():
    return [S(_repeat_loop()), S(_head(17.9, 6.75, 1, 0, size=3.1)), S(_head(6.1, 17.25, -1, 0, size=3.1))]


@icon("repeat_one", "music", "单曲循环")
def _():
    return [
        S(_repeat_loop()),
        S(_head(17.9, 6.75, 1, 0, size=3.1)),
        S(_head(6.1, 17.25, -1, 0, size=3.1)),
        S(polyline([(10.95, 10.55), (12.35, 9.8), (12.35, 14.35)]), id="one"),
    ]


# ---------------------------------------------------------------- lists

def _lines(right=15.4, short=10.2, x=4.0, ys=(6.25, 10.75, 15.25)):
    return [line(x, ys[0], right, ys[0]), line(x, ys[1], right, ys[1]), line(x, ys[2], short, ys[2])]


def small_note(hx, hy, hr=2.25, stem=8.8, flag=2.7):
    sx = hx + hr
    head = circle(hx, hy, hr)
    st = rounded_polyline([(sx, hy), (sx, hy - stem), (sx + flag, hy - stem)], [0, 0.95, 0])
    return head, st


@icon("playlist", "music", "歌单", replaces=["Icons.AutoMirrored.Filled.QueueMusic"])
def _():
    head, st = small_note(15.45, 17.5)
    return [S(x) for x in _lines()] + [S(head), S(st)]


@icon("queue", "music", "播放队列", replaces=["Icons.AutoMirrored.Rounded.QueueMusic"])
def _():
    tri = rounded_poly([(14.4, 13.5), (20.5, 17.0), (14.4, 20.5)], [0.7, 0.8, 0.7])
    return [S(x) for x in _lines(right=17.6, short=10.6)] + [S(tri)]


def eighth_note():
    hx, hy, hr = 9.35, 16.85, 3.05
    sx = hx + hr
    head = circle(hx, hy, hr)
    stem = [M(sx, hy), L(sx, 4.2), C(14.9, 4.2, 17.55, 5.75, 17.55, 8.75)]
    return head, stem


@icon("music_note", "music", "单曲", replaces=["Icons.Filled.MusicNote", "Icons.Rounded.MusicNote"])
def _():
    head, stem = eighth_note()
    return [S(head), S(stem)]


@icon("album", "music", "专辑", replaces=["Icons.Filled.Album"])
def _():
    return [S(circle(12, 12, 8.5)), S(circle(12, 12, 2.3)), S(arc(12, 12, 5.35, 198, 252))]


@icon("person", "music", "个人 / 账号", replaces=["Icons.Filled.Person"])
def _():
    head = ellipse(12, 8.05, 3.55, 3.8)
    body = [M(4.8, 19.9), C(4.8, 16.35, 7.95, 14.55, 12, 14.55), C(16.05, 14.55, 19.2, 16.35, 19.2, 19.9)]
    return [S(head), S(body)]


@icon("artist", "music", "艺人")
def _():
    head = ellipse(10.2, 8.1, 3.3, 3.55)
    body = [M(3.8, 19.9), C(3.8, 16.7, 6.6, 14.9, 10.2, 14.9), C(11.35, 14.9, 12.45, 15.1, 13.4, 15.45)]
    nh, ns = small_note(17.35, 17.9, hr=1.75, stem=7.3, flag=2.2)
    return [S(head), S(body), S(nh), S(ns)]


# ---------------------------------------------------------------- favourite / rating

def heart():
    return [
        M(12, 19.35),
        C(12, 19.35, 3.75, 14.55, 3.75, 9.15),
        C(3.75, 6.5, 5.7, 4.65, 8.05, 4.65),
        C(9.7, 4.65, 11.15, 5.5, 12, 6.95),
        C(12.85, 5.5, 14.3, 4.65, 15.95, 4.65),
        C(18.3, 4.65, 20.25, 6.5, 20.25, 9.15),
        C(20.25, 14.55, 12, 19.35, 12, 19.35),
        Z,
    ]


@icon("favorite", "music", "收藏", replaces=["Icons.Rounded.FavoriteBorder"])
def _():
    return [S(heart())]


@icon("favorite_filled", "music", "已收藏", replaces=["Icons.Rounded.Favorite"])
def _():
    return [FS(heart())]


def star(cx=12.0, cy=12.6, R=8.85, r=4.15, tip=1.35, inner=0.55):
    pts = []
    for i in range(10):
        rad = R if i % 2 == 0 else r
        a = math.radians(-90 + i * 36)
        pts.append((cx + rad * math.cos(a), cy + rad * math.sin(a)))
    return rounded_poly(pts, [tip if i % 2 == 0 else inner for i in range(10)])


@icon("star", "music", "评分", replaces=["Icons.Filled.StarBorder"])
def _():
    return [S(star())]


@icon("star_filled", "music", "已评分", replaces=["Icons.Filled.Star"])
def _():
    return [FS(star())]


# ---------------------------------------------------------------- lyrics / translate

def bubble(x=3.75, y=4.1, w=16.5, h=12.6, tail=3.4):
    b = squircle(x, y, w, h, r=3.5, s=0.6, corners=(True, True, False, True))
    # replace the square bottom-left corner with a tail
    out = []
    for c in b:
        if c[0] == "L" and abs(c[1] - x) < 1e-6 and abs(c[2] - (y + h)) < 1e-6:
            out.append(L(x + tail + 0.6, y + h))
            out.append(L(x, y + h + tail))
            out.append(L(x, y + h - 0.01))
            continue
        out.append(c)
    return out


@icon("lyrics", "music", "歌词")
def _():
    # A solid head: a ring this small closes up at 20-24 px. The note is centred in the bubble.
    hr, stem, flag = 1.6, 5.5, 2.4
    w, h = 2 * hr + flag + 1.5, stem + hr + 1.5
    hx = 12.0 - w / 2 + 0.75 + hr
    hy = 10.4 - h / 2 + 0.75 + stem
    nh, ns = small_note(hx, hy, hr=hr, stem=stem, flag=flag)
    return [S(bubble()), FS(nh), S(ns)]


# 文 and A live in their own cells so the swap animation can orbit them.
WEN_C = (8.2, 8.3)
A_C = (15.75, 15.75)


def wen_parts(cx=WEN_C[0], cy=WEN_C[1]):
    ox, oy = cx - 8.2, cy - 8.3
    dot = line(8.2 + ox, 3.55 + oy, 8.2 + ox, 4.75 + oy)
    bar = line(3.85 + ox, 6.25 + oy, 12.55 + ox, 6.25 + oy)
    pie = [M(10.75 + ox, 6.25 + oy), C(10.1 + ox, 9.55 + oy, 7.7 + ox, 11.95 + oy, 3.95 + ox, 13.15 + oy)]
    na = [M(5.65 + ox, 6.25 + oy), C(6.3 + ox, 9.55 + oy, 8.7 + ox, 11.95 + oy, 12.45 + ox, 13.15 + oy)]
    return [dot, bar, pie, na]


def a_parts(cx=A_C[0], cy=A_C[1]):
    ox, oy = cx - 15.75, cy - 15.75
    legs = polyline([(11.75 + ox, 20.35 + oy), (15.75 + ox, 11.15 + oy), (19.75 + ox, 20.35 + oy)])
    bar = line(13.25 + ox, 17.25 + oy, 18.25 + ox, 17.25 + oy)
    return [legs, bar]


@icon("translate", "music", "翻译歌词", replaces=["Icons.Rounded.Translate"],
      note="文 / A 分组：g#wen 与 g#latin，供轨道交换动画使用")
def _():
    return [S(p, group="wen") for p in wen_parts()] + [S(p, group="latin") for p in a_parts()]


@icon("recenter", "music", "回到当前句", replaces=["Icons.Rounded.VerticalAlignCenter"])
def _():
    return [
        S(line(4.25, 12, 19.75, 12)),
        S(line(12, 3.6, 12, 8.4)),
        S(polyline([(9.6, 6.1), (12, 8.5), (14.4, 6.1)])),
        S(line(12, 20.4, 12, 15.6)),
        S(polyline([(9.6, 17.9), (12, 15.5), (14.4, 17.9)])),
    ]


@icon("equalizer", "music", "正在播放律动", replaces=["Icons.Rounded.GraphicEq"])
def _():
    hs = [4.6, 10.6, 15.8, 8.6, 4.0]
    xs = [4.5, 8.25, 12.0, 15.75, 19.5]
    return [S(line(x, 12 - h / 2, x, 12 + h / 2), id=f"bar-{i}") for i, (x, h) in enumerate(zip(xs, hs))]


def sparkle_path(cx, cy, R, pinch=0.08):
    """Four-point sparkle with concave sides (Gemini-like), rounded tips via the stroke join."""
    k = R * pinch
    pts = [(cx, cy - R), (cx + R, cy), (cx, cy + R), (cx - R, cy)]
    out = [M(*pts[0])]
    for i in range(4):
        p0 = pts[i]
        p1 = pts[(i + 1) % 4]
        # control points pulled towards the centre
        c1 = (cx + (p0[0] - cx) * 0.18 + (p1[0] - cx) * 0.0, cy + (p0[1] - cy) * 0.18)
        c1 = (cx + (p0[0] - cx) * 0.28 + (p1[0] - cx) * 0.05, cy + (p0[1] - cy) * 0.28 + (p1[1] - cy) * 0.05)
        c2 = (cx + (p1[0] - cx) * 0.28 + (p0[0] - cx) * 0.05, cy + (p1[1] - cy) * 0.28 + (p0[1] - cy) * 0.05)
        out.append(C(*c1, *c2, *p1))
    out.append(Z)
    return out


@icon("sparkle", "music", "AI / Gemini", replaces=["Icons.Rounded.AutoAwesome"])
def _():
    return [S(sparkle_path(10.6, 13.4, 7.25), id="sparkle-main"), S(sparkle_path(18.1, 5.9, 2.35), id="sparkle-mini")]


@icon("sparkle_filled", "music", "AI（进行中 / 已生成）")
def _():
    return [FS(sparkle_path(10.6, 13.4, 7.25), id="sparkle-main"), FS(sparkle_path(18.1, 5.9, 2.35), id="sparkle-mini")]


@icon("play_circle", "music", "播放（圆形）", replaces=["Icons.Rounded.PlayCircle"])
def _():
    tri = rounded_poly([(10.05, 7.95), (16.55, 12.0), (10.05, 16.05)], [0.75, 0.85, 0.75])
    return [S(circle(12, 12, 8.5)), S(tri)]
