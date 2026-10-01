"""Output devices, cast, services & storage."""
import math

from geom import *
from knock import around, band, cut, fill_region
from registry import F, FS, S, icon
from shapely.geometry import Point, box
from shapely.ops import unary_union

# ---------------------------------------------------------------- cast

CAST = dict(x=3.75, y=4.75, w=16.5, h=14.5, r=3.2)
CAST_O = (CAST["x"], CAST["y"] + CAST["h"])  # arc origin = bottom-left corner
CAST_R = (4.35, 8.3)


def cast_screen():
    s = squircle(CAST["x"], CAST["y"], CAST["w"], CAST["h"], r=CAST["r"], s=0.6)
    ox, oy = CAST_O
    region = box(-5, oy - CAST_R[1] - 1.55, ox + CAST_R[1] + 1.55, 30)
    return cut(s, region)


def cast_waves():
    ox, oy = CAST_O
    return [
        F(circle(ox + 0.55, oy - 0.55, 1.1), id="wave-0"),
        S(arc(ox, oy, CAST_R[0], -90, 0), id="wave-1"),
        S(arc(ox, oy, CAST_R[1], -90, 0), id="wave-2"),
    ]


@icon("cast", "device", "投屏", replaces=["Icons.Filled.Cast", "Icons.Rounded.Cast"])
def _():
    return [S(cast_screen(), id="screen")] + cast_waves()


def cast_panel():
    # inner panel, bitten by the broadcast arcs
    x0, y0, x1, y1 = 6.35, 7.35, 17.65, 16.65
    ox, oy = CAST_O
    rc = CAST_R[1] + 2.35
    yl = oy - math.sqrt(rc ** 2 - (x0 - ox) ** 2)
    xb = ox + math.sqrt(rc ** 2 - (oy - y1) ** 2)
    a_b = math.degrees(math.atan2(y1 - oy, xb - ox))
    a_l = math.degrees(math.atan2(yl - oy, x0 - ox))
    p = rounded_polyline([(x0, yl), (x0, y0), (x1, y0), (x1, y1), (xb, y1)], [0, 1.25, 1.25, 1.25, 0])
    p += arc_cmds(ox, oy, rc, a_b, a_l, move=False)
    p += [Z]
    return p


@icon("cast_connected", "device", "已投屏", replaces=["Icons.Filled.CastConnected"])
def _():
    return [S(cast_screen(), id="screen")] + cast_waves() + [F(cast_panel(), id="panel")]


# ---------------------------------------------------------------- device family

@icon("smartphone", "device", "手机", replaces=["Icons.Rounded.Smartphone"])
def _():
    return [S(squircle(6.5, 2.75, 11, 18.5, r=3.1, s=0.6)), S(line(10.8, 17.85, 13.2, 17.85))]


@icon("tablet", "device", "平板", replaces=["Icons.Rounded.Tablet"])
def _():
    return [S(squircle(3.0, 5.0, 18, 14, r=3.1, s=0.6)), S(line(10.8, 15.6, 13.2, 15.6))]


@icon("computer", "device", "电脑", replaces=["Icons.Rounded.Computer"])
def _():
    return [S(squircle(4.75, 5.0, 14.5, 10.75, r=2.4, s=0.6)), S(line(2.75, 18.9, 21.25, 18.9))]


@icon("tv", "device", "电视", replaces=["Icons.Rounded.Tv"])
def _():
    return [S(squircle(2.75, 4.5, 18.5, 12.75, r=3.1, s=0.6)), S(line(8.4, 20.25, 15.6, 20.25))]


@icon("speaker", "device", "音箱", replaces=["Icons.Rounded.Speaker"])
def _():
    return [S(squircle(5.75, 2.75, 12.5, 18.5, r=3.5, s=0.6)), S(circle(12, 14.35, 3.3)), F(circle(12, 7.15, 1.05))]


@icon("headphones", "device", "耳机 / 本机", replaces=["Icons.Rounded.Headphones"])
def _():
    band_ = [M(3.75, 17.0), L(3.75, 12.5)] + arc_cmds(12, 12.5, 8.25, 180, 360, move=False) + [L(20.25, 17.0)]
    return [
        S(band_),
        S(squircle(3.75, 12.9, 4.6, 7.35, r=2.0, s=0.6)),
        S(squircle(15.65, 12.9, 4.6, 7.35, r=2.0, s=0.6)),
    ]


@icon("devices", "device", "设备", replaces=["Icons.Rounded.Devices"])
def _():
    phone = squircle(14.9, 8.4, 6.35, 11.35, r=1.9, s=0.6)
    mon = squircle(2.75, 4.6, 14.9, 10.6, r=2.7, s=0.6)
    gap = fill_region(phone).buffer(0.75 + 1.1)
    mon = cut(mon, gap)
    base = cut(line(2.75, 18.75, 12.4, 18.75), gap)
    return [S(mon), S(base), S(phone)]


@icon("car", "device", "车载", replaces=["Icons.Rounded.DirectionsCar"])
def _():
    body = squircle(3.4, 10.9, 17.2, 6.55, r=2.4, s=0.6)
    roof = rounded_polyline([(5.85, 10.9), (7.4, 6.4), (16.6, 6.4), (18.15, 10.9)], [0, 1.25, 1.25, 0])
    return [
        S(body),
        S(roof),
        S(line(6.95, 17.45, 6.95, 19.4)),
        S(line(17.05, 17.45, 17.05, 19.4)),
        F(circle(7.15, 14.15, 1.05)),
        F(circle(16.85, 14.15, 1.05)),
    ]


def _gamepad():
    return [
        M(7.6, 6.75),
        L(16.4, 6.75),
        C(19.1, 6.75, 20.55, 8.75, 20.95, 11.3),
        L(21.55, 15.35),
        C(21.85, 17.25, 20.5, 18.95, 18.7, 18.95),
        C(17.75, 18.95, 16.95, 18.45, 16.45, 17.65),
        L(15.55, 16.15),
        C(15.25, 15.65, 14.75, 15.4, 14.2, 15.4),
        L(9.8, 15.4),
        C(9.25, 15.4, 8.75, 15.65, 8.45, 16.15),
        L(7.55, 17.65),
        C(7.05, 18.45, 6.25, 18.95, 5.3, 18.95),
        C(3.5, 18.95, 2.15, 17.25, 2.45, 15.35),
        L(3.05, 11.3),
        C(3.45, 8.75, 4.9, 6.75, 7.6, 6.75),
        Z,
    ]


@icon("gamepad", "device", "游戏机", replaces=["Icons.Rounded.SportsEsports"])
def _():
    return [
        S(_gamepad()),
        S(line(8.1, 9.6, 8.1, 12.8)),
        S(line(6.5, 11.2, 9.7, 11.2)),
        F(circle(15.1, 10.2, 0.95)),
        F(circle(17.0, 12.25, 0.95)),
    ]


@icon("device_other", "device", "其他设备", replaces=["Icons.Rounded.DeviceHub"])
def _():
    top = squircle(9.25, 3.25, 5.5, 5.5, r=1.6, s=0.6)
    return [
        S(top),
        S(line(12, 8.75, 12, 12.6)),
        S(rounded_polyline([(6.1, 15.4), (6.1, 12.6), (17.9, 12.6), (17.9, 15.4)], [0, 1.6, 1.6, 0])),
        S(circle(6.1, 17.75, 2.35)),
        S(circle(17.9, 17.75, 2.35)),
    ]


# ---------------------------------------------------------------- services & storage

def cloud():
    return [
        M(7.2, 18.9),
        L(17.35, 18.9),
        C(19.55, 18.9, 21.25, 17.2, 21.25, 15.05),
        C(21.25, 12.95, 19.65, 11.3, 17.6, 11.2),
        C(17.1, 8.1, 14.75, 5.6, 11.65, 5.6),
        C(9.05, 5.6, 6.95, 7.2, 6.2, 9.55),
        C(4.15, 10.05, 2.75, 11.85, 2.75, 14.3),
        C(2.75, 16.85, 4.7, 18.9, 7.2, 18.9),
        Z,
    ]


@icon("cloud", "device", "服务器 / 云端", replaces=["Icons.Rounded.CloudQueue"])
def _():
    return [S(cloud())]


@icon("cloud_offline", "device", "离线")
def _():
    slash = (4.3, 3.6, 19.7, 20.4)
    c = cut(cloud(), band(*slash, 0.75 + 1.1, extend=0))
    return [S(c), S(line(*slash))]


@icon("storage", "device", "缓存 / 存储", replaces=["Icons.Rounded.Storage"])
def _():
    return [
        S(squircle(3.75, 4.25, 16.5, 6.6, r=2.3, s=0.6)),
        S(squircle(3.75, 13.15, 16.5, 6.6, r=2.3, s=0.6)),
        F(circle(7.35, 7.55, 1.0)),
        F(circle(7.35, 16.45, 1.0)),
    ]


def folder_path():
    return [
        M(3.75, 16.2),
        L(3.75, 7.6),
        C(3.75, 6.1, 4.85, 5.0, 6.35, 5.0),
        L(8.85, 5.0),
        C(9.55, 5.0, 10.15, 5.3, 10.6, 5.8),
        L(11.45, 6.8),
        C(11.9, 7.3, 12.5, 7.6, 13.2, 7.6),
        L(17.65, 7.6),
        C(19.15, 7.6, 20.25, 8.7, 20.25, 10.2),
        L(20.25, 16.2),
        C(20.25, 17.9, 18.95, 19.0, 17.35, 19.0),
        L(6.65, 19.0),
        C(5.05, 19.0, 3.75, 17.9, 3.75, 16.2),
        Z,
    ]


@icon("folder", "device", "文件夹", replaces=["Icons.Rounded.Folder"])
def _():
    return [S(folder_path())]


def tray():
    return rounded_polyline([(4.6, 14.9), (4.6, 20.1), (19.4, 20.1), (19.4, 14.9)], [0, 2.9, 2.9, 0])


@icon("download", "device", "下载 / 离线保存")
def _():
    return [S(tray(), id="tray"), S(line(12, 3.9, 12, 14.4), id="shaft"), S(polyline([(7.9, 10.3), (12, 14.4), (16.1, 10.3)]), id="head")]


@icon("download_done", "device", "已下载")
def _():
    return [S(tray(), id="tray"), S(polyline([(7.35, 10.1), (10.6, 13.35), (16.85, 7.1)]), id="check")]


@icon("volume_up", "device", "音量")
def _():
    spk = rounded_poly([(3.9, 9.1), (7.4, 9.1), (12.3, 4.9), (12.3, 19.1), (7.4, 14.9), (3.9, 14.9)], [1.1, 0.7, 1.1, 1.1, 0.7, 1.1])
    return [S(spk), S(arc(12.3, 12, 3.4, -52, 52)), S(arc(12.3, 12, 7.3, -50, 50))]


@icon("volume_mute", "device", "静音")
def _():
    spk = rounded_poly([(3.9, 9.1), (7.4, 9.1), (12.3, 4.9), (12.3, 19.1), (7.4, 14.9), (3.9, 14.9)], [1.1, 0.7, 1.1, 1.1, 0.7, 1.1])
    return [S(spk), S(line(15.9, 9.6, 20.7, 14.4)), S(line(20.7, 9.6, 15.9, 14.4))]
