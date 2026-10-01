#!/usr/bin/env python3
"""Generate the interactive preview (icon library + motion lab).

    python3 make_preview.py <template.html> <out_dir>

Writes <out_dir>/preview.html (standalone document) and <out_dir>/artifact.html
(body-only, for publishing as an Artifact).
"""
import importlib
import json
import sys
from pathlib import Path

HERE = Path(__file__).resolve().parent
SRC = HERE / "src" if (HERE / "src").exists() else HERE
sys.path.insert(0, str(SRC))

from geom import to_d, polyline, circle  # noqa: E402
from registry import ORDER, REGISTRY, svg_markup  # noqa: E402

for mod in ("icons_nav", "icons_music", "icons_devices", "icons_content"):
    importlib.import_module(mod)

import icons_music as MU  # noqa: E402
import icons_nav as NV  # noqa: E402
import icons_devices as DV  # noqa: E402
import icons_content as CT  # noqa: E402

GROUP_ZH = {"nav": "导航与通用", "music": "播放与音乐", "content": "内容与笔记", "device": "设备与服务"}

# Where the Compose icons are used today (from a grep of app/src/main, 2026-09-29)
USAGE = {
    "Icons.AutoMirrored.Filled.ArrowBack": "DetailBackButton · LibraryScreen · ServiceSetupScreen · SettingsScreen",
    "Icons.AutoMirrored.Filled.QueueMusic": "HomeWidgetGrid · LibraryScreen",
    "Icons.AutoMirrored.Rounded.ArrowBack": "LyricsSheets",
    "Icons.AutoMirrored.Rounded.KeyboardArrowRight": "SettingsScreen",
    "Icons.AutoMirrored.Rounded.QueueMusic": "BottomPills · ServiceIntro",
    "Icons.AutoMirrored.Rounded.Send": "NoteContent",
    "Icons.AutoMirrored.Rounded.StickyNote2": "AlbumDetailComponents · BottomPills · SongListItem",
    "Icons.Filled.Add": "AddToPlaylistSheet · LibraryScreen",
    "Icons.Filled.Album": "ArtistDetailScreen",
    "Icons.Filled.Cast": "CastButton",
    "Icons.Filled.CastConnected": "CastButton",
    "Icons.Filled.Check": "HomeLayoutEditor",
    "Icons.Filled.Clear": "LibraryScreen",
    "Icons.Filled.Delete": "PlaylistDetailScreen",
    "Icons.Filled.DragHandle": "HomeLayoutEditor",
    "Icons.Filled.Edit": "AlbumDetailScreen · PlaylistDetailScreen",
    "Icons.Filled.ErrorOutline": "YoinNavRail",
    "Icons.Filled.Home": "YoinButtonGroup · YoinNavRail",
    "Icons.Filled.KeyboardArrowDown": "HomeEditorialContent · PlaySplitButton",
    "Icons.Filled.KeyboardArrowUp": "MemoriesScreen",
    "Icons.Filled.Launch": "ArtistDetailScreen",
    "Icons.Filled.LibraryMusic": "YoinButtonGroup · YoinNavRail · AlbumCard · LibraryScreen 等 10 处",
    "Icons.Filled.MoreVert": "PlaylistDetailScreen",
    "Icons.Filled.MusicNote": "ArtistDetailScreen · HomeWidgetGrid · NowPlayingPill · QueueSheet · SongListItem · YoinNavRail",
    "Icons.Filled.Person": "AlbumDetailScreen · ArtistDetailScreen · HomeWidgetGrid · LibraryScreen",
    "Icons.Filled.PlayArrow": "PlaySplitButton",
    "Icons.Filled.Search": "LibraryScreen · YoinButtonGroup",
    "Icons.Filled.Settings": "HomeEditorialContent · LibraryScreen",
    "Icons.Filled.Shuffle": "LibraryScreen · PlaySplitButton",
    "Icons.Filled.Star": "ArtistDetailScreen",
    "Icons.Filled.StarBorder": "ArtistDetailScreen",
    "Icons.Outlined.Insights": "AlbumDetailScreen",
    "Icons.Rounded.Add": "SettingsScreen",
    "Icons.Rounded.AutoAwesome": "SettingsScreen",
    "Icons.Rounded.Cast": "DeviceIcons · ServiceIntro",
    "Icons.Rounded.Check": "DevicesSheet · LyricsActionBar · ServiceSetupScreen",
    "Icons.Rounded.ChevronRight": "LyricsSheets",
    "Icons.Rounded.Close": "LyricsSheets",
    "Icons.Rounded.CloudQueue": "ServiceIntro · SettingsScreen",
    "Icons.Rounded.Code": "ServiceSetupScreen",
    "Icons.Rounded.Computer": "DeviceIcons",
    "Icons.Rounded.Delete": "NoteContent",
    "Icons.Rounded.DeviceHub": "DeviceIcons",
    "Icons.Rounded.Devices": "BottomPills",
    "Icons.Rounded.DirectionsCar": "DeviceIcons",
    "Icons.Rounded.EditNote": "NoteFullscreenPane · ServiceIntro",
    "Icons.Rounded.Error": "SettingsScreen",
    "Icons.Rounded.ErrorOutline": "ServiceSetupScreen",
    "Icons.Rounded.ExpandMore": "SettingsComponents",
    "Icons.Rounded.Favorite": "AlbumDetailComponents · NowPlayingScreen · ServiceIntro",
    "Icons.Rounded.FavoriteBorder": "AlbumDetailComponents · NowPlayingScreen",
    "Icons.Rounded.Folder": "SettingsScreen",
    "Icons.Rounded.GraphicEq": "AlbumDetailComponents · ServiceIntro",
    "Icons.Rounded.Headphones": "DeviceIcons · ServiceIntro · SettingsScreen",
    "Icons.Rounded.Info": "SettingsScreen",
    "Icons.Rounded.IosShare": "AlbumDetailScreen · ArtistDetailScreen",
    "Icons.Rounded.KeyboardArrowDown": "NowPlayingScreen",
    "Icons.Rounded.LibraryMusic": "ServiceIntro",
    "Icons.Rounded.MoreVert": "SettingsScreen",
    "Icons.Rounded.MusicNote": "NoteContent · ServiceIntro · SettingsScreen",
    "Icons.Rounded.PlayArrow": "NowPlayingScreen",
    "Icons.Rounded.PlayCircle": "ServiceIntro",
    "Icons.Rounded.Refresh": "DevicesSheet",
    "Icons.Rounded.Reviews": "SettingsScreen",
    "Icons.Rounded.Search": "LyricsActionBar · ServiceIntro",
    "Icons.Rounded.Shuffle": "PlaybackControls",
    "Icons.Rounded.SkipNext": "PlaybackControls",
    "Icons.Rounded.SkipPrevious": "PlaybackControls",
    "Icons.Rounded.Smartphone": "DeviceIcons",
    "Icons.Rounded.Speaker": "DeviceIcons · ServiceIntro",
    "Icons.Rounded.SportsEsports": "DeviceIcons",
    "Icons.Rounded.Storage": "SettingsScreen",
    "Icons.Rounded.Tablet": "DeviceIcons",
    "Icons.Rounded.Translate": "LyricsActionBar",
    "Icons.Rounded.Tv": "DeviceIcons",
    "Icons.Rounded.UnfoldLess": "PlaybackControls",
    "Icons.Rounded.UnfoldMore": "PlaybackControls",
    "Icons.Rounded.VerticalAlignCenter": "LyricsActionBar",
    "Icons.Rounded.Visibility": "SettingsComponents",
    "Icons.Rounded.VisibilityOff": "SettingsComponents",
}


def d(cmds):
    return to_d(cmds)


def layers_d(name, mode=None):
    return [to_d(l["d"]) for l in REGISTRY[name].layers if mode is None or l["mode"] == mode]


def geom():
    g = {}
    g["translate"] = dict(
        wen=[d(p) for p in MU.wen_parts()],
        latin=[d(p) for p in MU.a_parts()],
        wenC=list(MU.WEN_C),
        latinC=list(MU.A_C),
    )
    front, back, (cx, cy) = CT._library_front_back()
    g["libraryAdd"] = dict(front=d(front), back=d(back), c=[cx, cy], half=3.1,
                           check=[[cx - 3.05, cy + 0.1], [cx - 0.85, cy + 2.3], [cx + 3.2, cy - 1.75]])
    g["sparkle"] = dict(main=d(MU.sparkle_path(10.6, 13.4, 7.25)), mini=d(MU.sparkle_path(18.1, 5.9, 2.35)),
                        mainC=[10.6, 13.4], miniC=[18.1, 5.9])
    ox, oy = DV.CAST_O
    ox_dot = ox + 0.55
    g["cast"] = dict(screen=d(DV.cast_screen()), dot=d(circle(ox + 0.55, oy - 0.55, 1.1)),
                     w1=layers_d("cast")[2], w2=layers_d("cast")[3], panel=d(DV.cast_panel()), o=[ox, oy])
    g["search"] = dict(layers=layers_d("search"))
    g["refresh"] = dict(layers=layers_d("refresh"), c=[NV.REFRESH["cx"], NV.REFRESH["cy"]])
    g["download"] = dict(tray=d(DV.tray()), shaft=[[12, 3.9], [12, 14.4]],
                         head=[[7.9, 10.3], [12, 14.4], [16.1, 10.3]], check=[[7.35, 10.1], [10.6, 13.35], [16.85, 7.1]])
    g["eq"] = dict(xs=[4.5, 8.25, 12.0, 15.75, 19.5], hs=[4.6, 10.6, 15.8, 8.6, 4.0])
    l, r = MU.play_halves()
    pl, pr = MU.pause_quads()
    g["playPause"] = dict(play=[l, r], pause=[pl, pr])
    g["heart"] = d(MU.heart())
    g["shuffle"] = dict(
        crossA=[[3.8, 17.2], [10.6, 17.2], [13.2, 6.8], [20.0, 6.8]],
        crossB=[[3.8, 6.8], [10.6, 6.8], [13.2, 17.2], [20.0, 17.2]],
        parA=[[3.8, 17.2], [10.6, 17.2], [13.2, 17.2], [20.0, 17.2]],
        parB=[[3.8, 6.8], [10.6, 6.8], [13.2, 6.8], [20.0, 6.8]],
        head=3.3,
    )
    g["repeat"] = dict(loop=d(MU._repeat_loop()), headA=d(MU._head(17.9, 6.75, 1, 0, size=3.1)),
                       headB=d(MU._head(6.1, 17.25, -1, 0, size=3.1)),
                       one=d(polyline([(10.95, 10.55), (12.35, 9.8), (12.35, 14.35)])))
    g["chevron"] = dict(down=[[7.5, 9.9], [12, 14.4], [16.5, 9.9]], up=[[7.5, 14.1], [12, 9.6], [16.5, 14.1]])
    g["home"] = d(NV._home_path())
    g["library"] = dict(outline=layers_d("library"), filled=[
        dict(d=to_d(l["d"]), mode=l["mode"], rule=l.get("rule", "nonzero")) for l in REGISTRY["library_filled"].layers])
    g["back"] = dict(shaft=[[19.5, 12], [4.75, 12]], head=[[10.6, 6.15], [4.75, 12], [10.6, 17.85]])
    t, b = MU.skip_next_parts()
    g["skip"] = dict(tri=d(t), bar=d(b))
    g["visibility"] = dict(eye=d(NV._eye()), pupil=d(circle(12, 12, 3.1)), slash=[[4.6, 3.9], [19.4, 20.1]])
    g["recenter"] = dict(layers=layers_d("recenter"))
    g["error"] = dict(layers=layers_d("error"))
    g["send"] = dict(layers=layers_d("send"))
    g["memory"] = dict(cookie=d(CT.cookie()), heart=layers_d("memory")[1])
    g["settings"] = dict(layers=layers_d("settings"))
    # figures for the design-language section
    from geom import squircle, rrect
    from knock import around
    gap = around(MU.SHUF_A, 0.75 + 1.05)
    ring = list(gap.exterior.coords)
    gap_d = "M" + " L".join(f"{x:.2f} {y:.2f}" for x, y in ring) + " Z"
    shuf_inner = svg_markup(REGISTRY["shuffle"], title=False, ids=False)
    shuf_inner = shuf_inner[shuf_inner.index(">") + 1: -len("</svg>")]
    g["lang"] = dict(
        squircle=d(squircle(0.5, 0.5, 23, 23, r=7.0, s=0.6)),
        rrect=d(rrect(0.5, 0.5, 23, 23, 7.0)),
        squircleIcon=d(squircle(4, 4, 16, 16, r=4.0, s=0.6)),
        shuffleIcon=shuf_inner,
        shufA=d(MU.SHUF_A),
        shufB=d(MU.SHUF_B),
        shufGap=gap_d,
    )
    return g


def icons_meta():
    out = []
    for name in ORDER:
        ic = REGISTRY[name]
        if ic.group == "lab":
            continue
        out.append(dict(name=name, zh=ic.zh, group=ic.group, groupZh=GROUP_ZH[ic.group], replaces=ic.replaces,
                        usage=[USAGE[r] for r in ic.replaces if r in USAGE], note=ic.note,
                        svg=svg_markup(ic, size=24, title=False, ids=False)))
    return out


def main(template, out_dir):
    tpl = Path(template).read_text(encoding="utf-8")
    data = json.dumps(dict(icons=icons_meta(), geom=geom()), ensure_ascii=False, separators=(",", ":"))
    body = tpl.replace("/*__DATA__*/null", data)
    out_dir = Path(out_dir)
    out_dir.mkdir(parents=True, exist_ok=True)
    (out_dir / "artifact.html").write_text(body.replace("<!--BODY-->\n", ""), encoding="utf-8")
    head, rest = body.split("<!--BODY-->", 1)
    full = (
        '<!doctype html>\n<html lang="zh-CN">\n<head>\n<meta charset="utf-8">\n'
        '<meta name="viewport" content="width=device-width, initial-scale=1, viewport-fit=cover">\n'
        + head + "</head>\n<body>\n" + rest + "\n</body>\n</html>\n"
    )
    (out_dir / "preview.html").write_text(full, encoding="utf-8")
    print("preview written", len(body) // 1024, "KB")


if __name__ == "__main__":
    main(sys.argv[1], sys.argv[2])
