"""Icon registry: every icon is a list of layers.

Layer = dict(d=<cmds>, mode='stroke'|'fill'|'fillstroke', rule='nonzero'|'evenodd', id=optional)
  stroke      : centre-line stroked at SW, round caps / joins (the default Luminous look)
  fill        : solid fill of the path (knockouts via evenodd)
  fillstroke  : fill + stroke of the same centre line (a filled twin that keeps the outline's outer edge)
"""
from __future__ import annotations

from dataclasses import dataclass, field
from typing import Callable, Dict, List, Optional

from geom import to_d

REGISTRY: Dict[str, "Icon"] = {}
ORDER: List[str] = []


@dataclass
class Icon:
    name: str
    group: str
    zh: str
    layers: List[dict]
    replaces: List[str] = field(default_factory=list)  # Compose Icons.* it replaces
    note: str = ""


def S(d, **kw):
    return dict(d=d, mode="stroke", **kw)


def F(d, rule="nonzero", **kw):
    return dict(d=d, mode="fill", rule=rule, **kw)


def FS(d, **kw):
    return dict(d=d, mode="fillstroke", **kw)


def icon(name, group, zh, replaces=(), note=""):
    def deco(fn: Callable[[], List[dict]]):
        layers = fn()
        REGISTRY[name] = Icon(name, group, zh, layers, list(replaces), note)
        ORDER.append(name)
        return fn

    return deco


def svg_markup(ic: Icon, size=24, extra_attrs="", sw=1.5, title=True, ids=True) -> str:
    parts = []
    cur_group = None
    for ly in ic.layers:
        g = ly.get("group")
        if g != cur_group:
            if cur_group is not None:
                parts.append("</g>")
            if g is not None:
                parts.append(f'<g id="{g}">' if ids else "<g>")
            cur_group = g
        d = to_d(ly["d"])
        idattr = f' id="{ly["id"]}"' if (ids and ly.get("id")) else ""
        if ly["mode"] == "stroke":
            parts.append(f'<path{idattr} d="{d}"/>')
        elif ly["mode"] == "fill":
            rule = ' fill-rule="evenodd"' if ly.get("rule") == "evenodd" else ""
            parts.append(f'<path{idattr} d="{d}" fill="currentColor" stroke="none"{rule}/>')
        elif ly["mode"] == "fillstroke":
            parts.append(f'<path{idattr} d="{d}" fill="currentColor"/>')
    if cur_group is not None:
        parts.append("</g>")
    t = f"<title>{ic.name}</title>" if title else ""
    return (
        f'<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 24 24" width="{size}" height="{size}" '
        f'fill="none" stroke="currentColor" stroke-width="{sw}" stroke-linecap="round" '
        f'stroke-linejoin="round"{extra_attrs}>{t}' + "".join(parts) + "</svg>"
    )
