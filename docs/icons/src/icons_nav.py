"""Navigation & general actions."""
import math

from geom import *
from knock import around, band, cut, fill_region
from registry import F, FS, S, icon
from shapely.geometry import Polygon, box
from shapely.ops import unary_union


# ---------------------------------------------------------------- home

def _home_path():
    # one continuous line: bottom edge -> walls -> roof -> door notch
    pts = [
        (14.25, 20.0),  # door right foot
        (20.0, 20.0),
        (20.0, 10.2),
        (12.0, 3.7),
        (4.0, 10.2),
        (4.0, 20.0),
        (9.75, 20.0),  # door left foot
    ]
    radii = [0, 2.6, 1.7, 2.3, 1.7, 2.6, 0]
    p = rounded_polyline(pts, radii)
    # door arch back to the start
    p += [L(9.75, 16.4)]
    p += arc_cmds(12.0, 16.4, 2.25, 180, 360, move=False)
    p += [L(14.25, 20.0), Z]
    return p


@icon("home", "nav", "主页", replaces=["Icons.Filled.Home"])
def _():
    return [S(_home_path())]


@icon("home_filled", "nav", "主页（选中）", replaces=["Icons.Filled.Home"])
def _():
    return [FS(_home_path())]


# ---------------------------------------------------------------- library (a record sleeve in front of the stack)

FRONT = (7.75, 3.75, 12.5, 12.5)


def _library_back():
    back = squircle(3.75, 7.75, 12.5, 12.5, r=3.3, s=0.6)
    region = unary_union([box(-5, -5, 30, 9.9), box(14.1, -5, 30, 30)])
    return cut(back, region)


@icon("library", "nav", "资料库", replaces=["Icons.Filled.LibraryMusic", "Icons.Rounded.LibraryMusic"])
def _():
    x, y, w, h = FRONT
    return [
        S(squircle(x, y, w, h, r=3.3, s=0.6)),
        S(circle(x + w / 2, y + h / 2, 2.9)),
        S(_library_back()),
    ]


@icon("library_filled", "nav", "资料库（选中）", replaces=["Icons.Filled.LibraryMusic"])
def _():
    x, y, w, h = FRONT
    cx, cy = x + w / 2, y + h / 2
    body = squircle(x - 0.75, y - 0.75, w + 1.5, h + 1.5, r=4.05, s=0.6)
    return [
        F(body + circle(cx, cy, 3.65, ccw=True) + circle(cx, cy, 1.05), rule="evenodd"),
        S(_library_back()),
    ]


# ---------------------------------------------------------------- search

@icon("search", "nav", "搜索", replaces=["Icons.Rounded.Search", "Icons.Filled.Search"])
def _():
    cx = cy = 10.6
    r = 6.1
    a = pt_on(cx, cy, r, 45)
    return [S(circle(cx, cy, r)), S(line(a[0], a[1], 19.5, 19.5))]


# ---------------------------------------------------------------- settings (8 soft teeth)

def _gear(rm=7.4, amp=1.08, k=2.1, teeth=8, n=12):
    pts = []
    total = teeth * n
    for i in range(total):
        th = 2 * math.pi * i / total - math.pi / 2 + math.pi / teeth
        g = math.tanh(k * math.cos(teeth * th)) / math.tanh(k)
        r = rm + amp * g
        pts.append((12 + r * math.cos(th), 12 + r * math.sin(th)))
    return smooth_closed(pts)


@icon("settings", "nav", "设置", replaces=["Icons.Filled.Settings"])
def _():
    return [S(_gear()), S(circle(12, 12, 2.7))]


# ---------------------------------------------------------------- arrows & chevrons

@icon("back", "nav", "返回", replaces=["Icons.AutoMirrored.Filled.ArrowBack", "Icons.AutoMirrored.Rounded.ArrowBack"])
def _():
    return [S(line(19.5, 12, 4.75, 12)), S(polyline([(10.6, 6.15), (4.75, 12), (10.6, 17.85)]))]


@icon("close", "nav", "关闭 / 清除", replaces=["Icons.Rounded.Close", "Icons.Filled.Clear"])
def _():
    return [S(line(6.25, 6.25, 17.75, 17.75)), S(line(17.75, 6.25, 6.25, 17.75))]


def _chev_down():
    return polyline([(7.5, 9.9), (12, 14.4), (16.5, 9.9)])


@icon("chevron_down", "nav", "展开", replaces=["Icons.Rounded.KeyboardArrowDown", "Icons.Filled.KeyboardArrowDown", "Icons.Rounded.ExpandMore"])
def _():
    return [S(_chev_down())]


@icon("chevron_up", "nav", "收起", replaces=["Icons.Filled.KeyboardArrowUp"])
def _():
    return [S(mirror_y(_chev_down()))]


@icon("chevron_right", "nav", "进入", replaces=["Icons.Rounded.ChevronRight", "Icons.AutoMirrored.Rounded.KeyboardArrowRight"])
def _():
    return [S(rotate(_chev_down(), -90))]


@icon("chevron_left", "nav", "后退一级")
def _():
    return [S(rotate(_chev_down(), 90))]


@icon("unfold_more", "nav", "展开控件", replaces=["Icons.Rounded.UnfoldMore"])
def _():
    return [S(polyline([(8, 9.1), (12, 5.1), (16, 9.1)])), S(polyline([(8, 14.9), (12, 18.9), (16, 14.9)]))]


@icon("unfold_less", "nav", "收起控件", replaces=["Icons.Rounded.UnfoldLess"])
def _():
    return [S(polyline([(8, 4.9), (12, 8.9), (16, 4.9)])), S(polyline([(8, 19.1), (12, 15.1), (16, 19.1)]))]


@icon("more_vertical", "nav", "更多（竖）", replaces=["Icons.Rounded.MoreVert", "Icons.Filled.MoreVert"])
def _():
    return [F(join(circle(12, 6, 1.4), circle(12, 12, 1.4), circle(12, 18, 1.4)))]


@icon("more_horizontal", "nav", "更多（横）")
def _():
    return [F(join(circle(6, 12, 1.4), circle(12, 12, 1.4), circle(18, 12, 1.4)))]


@icon("add", "nav", "添加", replaces=["Icons.Rounded.Add", "Icons.Filled.Add"])
def _():
    return [S(line(12, 5, 12, 19)), S(line(5, 12, 19, 12))]


@icon("check", "nav", "确认 / 已选", replaces=["Icons.Rounded.Check", "Icons.Filled.Check"])
def _():
    return [S(polyline([(5.75, 12.4), (9.55, 16.2), (18.25, 7.5)]))]


@icon("drag_handle", "nav", "拖动手柄", replaces=["Icons.Filled.DragHandle"])
def _():
    return [S(line(5, 9.5, 19, 9.5)), S(line(5, 14.5, 19, 14.5))]


# ---------------------------------------------------------------- edit / delete / share / refresh / launch

def _pencil(tip=(4.4, 19.6), length=18.5, width=4.3, cone=4.1, eraser=3.4):
    ux, uy = 1 / math.sqrt(2), -1 / math.sqrt(2)  # along the pencil, towards top-right
    nx, ny = 1 / math.sqrt(2), 1 / math.sqrt(2)   # across the pencil
    tx, ty = tip
    bx, by = tx + ux * cone, ty + uy * cone
    ex, ey = tx + ux * (length - width / 2), ty + uy * (length - width / 2)
    hw = width / 2
    b1 = (bx + nx * hw, by + ny * hw)
    b2 = (bx - nx * hw, by - ny * hw)
    e1 = (ex + nx * hw, ey + ny * hw)
    e2 = (ex - nx * hw, ey - ny * hw)
    ang = math.degrees(math.atan2(ny, nx))  # direction of +n
    body = rounded_polyline([(tx, ty), b1, e1], [0, 0.9, 0])
    # rounded end cap from e1 around to e2
    body += arc_cmds(ex, ey, hw, ang, ang - 180, move=False)
    tail = rounded_polyline([e2, b2, (tx, ty)], [0, 0.9, 0])
    body += tail[1:]
    body += [Z]
    # soften the very tip with a tiny fillet by pulling the join (stroke join already rounds it)
    k = length - width / 2 - eraser
    kx, ky = tx + ux * k, ty + uy * k
    er = line(kx + nx * hw, ky + ny * hw, kx - nx * hw, ky - ny * hw)
    return body, er


@icon("edit", "nav", "编辑", replaces=["Icons.Filled.Edit"])
def _():
    body, er = _pencil()
    return [S(body), S(er)]


@icon("delete", "nav", "删除", replaces=["Icons.Rounded.Delete", "Icons.Filled.Delete"])
def _():
    lid = line(4.5, 6.6, 19.5, 6.6)
    handle = rounded_polyline([(9.35, 6.6), (9.35, 4.0), (14.65, 4.0), (14.65, 6.6)], [0, 1.1, 1.1, 0])
    body = rounded_polyline([(6.1, 6.6), (6.95, 20.0), (17.05, 20.0), (17.9, 6.6)], [0, 2.4, 2.4, 0])
    return [S(lid), S(handle), S(body), S(line(10.1, 10.4, 10.1, 16.2)), S(line(13.9, 10.4, 13.9, 16.2))]


@icon("share", "nav", "分享", replaces=["Icons.Rounded.IosShare"])
def _():
    tray = squircle(4.75, 9.4, 14.5, 10.85, r=3.3, s=0.6)
    # Open at the top, and the right wall stops at half height instead of wrapping over.
    tray = cut(tray, unary_union([box(8.6, 5, 15.4, 11), box(15.4, 5, 21, 14.8)]))
    return [S(tray), S(line(12, 14.6, 12, 3.75)), S(polyline([(8.4, 7.35), (12, 3.75), (15.6, 7.35)]))]


def _arrowhead(ex, ey, dirx, diry, size=3.3, spread=45):
    """Open arrowhead at (ex, ey) pointing along (dirx, diry)."""
    l = math.hypot(dirx, diry)
    dx, dy = -dirx / l, -diry / l
    out = []
    pts = []
    for s in (spread, -spread):
        a = math.radians(s)
        rx = dx * math.cos(a) - dy * math.sin(a)
        ry = dx * math.sin(a) + dy * math.cos(a)
        pts.append((ex + rx * size, ey + ry * size))
    return polyline([pts[0], (ex, ey), pts[1]])


REFRESH = dict(cx=12.0, cy=12.25, r=7.6, a0=22.0, a1=316.0)


def _refresh_arc():
    g = REFRESH
    return arc(g["cx"], g["cy"], g["r"], g["a0"], g["a1"])


def _refresh_head(size=3.5, spread=45):
    g = REFRESH
    ex, ey = pt_on(g["cx"], g["cy"], g["r"], g["a1"])
    t = math.radians(g["a1"])
    return _arrowhead(ex, ey, -math.sin(t), math.cos(t), size=size, spread=spread)


@icon("refresh", "nav", "刷新 / 重新扫描", replaces=["Icons.Rounded.Refresh"])
def _():
    return [S(_refresh_arc()), S(_refresh_head())]


@icon("launch", "nav", "在外部打开", replaces=["Icons.Filled.Launch"])
def _():
    boxp = squircle(4.5, 4.5, 15, 15, r=3.7, s=0.6)
    boxp = cut(boxp, box(10.4, 0, 30, 13.6))
    return [S(boxp), S(line(12.1, 11.9, 19.4, 4.6)), S(polyline([(13.9, 4.6), (19.4, 4.6), (19.4, 10.1)]))]


# ---------------------------------------------------------------- info / error / visibility

@icon("info", "nav", "信息", replaces=["Icons.Rounded.Info"])
def _():
    return [S(circle(12, 12, 8.5)), S(line(12, 11.2, 12, 16.2)), F(circle(12, 8.1, 0.95))]


@icon("error", "nav", "错误", replaces=["Icons.Rounded.ErrorOutline", "Icons.Filled.ErrorOutline"])
def _():
    return [S(circle(12, 12, 8.5)), S(line(12, 7.6, 12, 12.6)), F(circle(12, 15.95, 0.95))]


@icon("error_filled", "nav", "错误（实心）", replaces=["Icons.Rounded.Error"])
def _():
    d = circle(12, 12, 9.25) + capsule(12, 7.6, 12, 12.6, 0.8) + circle(12, 15.95, 1.0)
    return [F(d, rule="evenodd")]


def _eye():
    return [
        M(2.9, 12),
        C(4.9, 8.15, 8.15, 5.9, 12, 5.9),
        C(15.85, 5.9, 19.1, 8.15, 21.1, 12),
        C(19.1, 15.85, 15.85, 18.1, 12, 18.1),
        C(8.15, 18.1, 4.9, 15.85, 2.9, 12),
        Z,
    ]


@icon("visibility", "nav", "显示", replaces=["Icons.Rounded.Visibility"])
def _():
    return [S(_eye()), S(circle(12, 12, 3.1))]


@icon("visibility_off", "nav", "隐藏", replaces=["Icons.Rounded.VisibilityOff"])
def _():
    slash = (4.6, 3.9, 19.4, 20.1)
    gap = band(*slash, 0.75 + 1.1, extend=0.0)
    eye = cut(_eye(), gap)
    pupil = cut(circle(12, 12, 3.1), gap)
    return [S(eye), S(pupil), S(line(*slash))]


@icon("code", "nav", "开发者设置", replaces=["Icons.Rounded.Code"])
def _():
    return [S(polyline([(8.1, 7.6), (3.75, 12), (8.1, 16.4)])), S(polyline([(15.9, 7.6), (20.25, 12), (15.9, 16.4)])), S(line(13.4, 5.6, 10.6, 18.4))]


@icon("send", "nav", "发送", replaces=["Icons.AutoMirrored.Rounded.Send"])
def _():
    plane = rounded_poly([(4.3, 4.9), (20.6, 12.0), (4.3, 19.1), (6.95, 12.0)], [1.4, 1.3, 1.4, 0.6])
    return [S(plane), S(line(6.95, 12.0, 11.6, 12.0))]
