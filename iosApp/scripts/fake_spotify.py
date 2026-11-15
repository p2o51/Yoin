#!/usr/bin/env python3
"""A fake Spotify Web API for simulator QA (DEBUG builds, `-spotifyFake http://127.0.0.1:4997`).

Serves the endpoints SpotifySource reads and accepts its writes, in memory (a restart resets
everything). No network, no covers, no audio. It keeps the February 2026 Development Mode rules
the app has to live with:

- search and artists/{id}/albums refuse limit > 10 (400);
- playlists/{id}/items answers 403 for a playlist the user neither owns nor collaborates on;
- follow / save / unfollow go through PUT / DELETE me/library?uris=...

The account: user `yoin-qa`, 350 Liked Songs (offset paging), 12 saved albums, 6 followed
artists, and playlists: "QA Mix" (owned, holds one song twice), "Road Trip" (owned),
"Shared Jam" (collaborative, someone else's) and "Editorial Picks" (followed, someone else's:
its songs are refused).

    python3 iosApp/scripts/fake_spotify.py            # port 4997
    python3 iosApp/scripts/fake_spotify.py --port 5001

Spotify Connect (the player) is in memory too, and silent: the account's devices, the active one
and what it plays, with a clock that runs on through the list a start handed it. There are no
devices until some are given, so a start answers 404 as Spotify does with nothing open:

    python3 iosApp/scripts/fake_spotify.py --device "QA Mac:Computer" --device "Pixel 11 Pro:Smartphone"
    curl -X POST 127.0.0.1:4997/_qa/devices -d '{"devices":[{"name":"QA Mac","type":"Computer"}]}'
    # a device in a private session (its row says so)
    curl -X POST 127.0.0.1:4997/_qa/devices -d '{"devices":[{"name":"QA Mac","type":"Computer","private":true}]}'
    # a Sonos: no id, refuses transfers, missing from the list ("hidden") while me/player names it
    curl -X POST 127.0.0.1:4997/_qa/devices -d '{"devices":[{"name":"Living Room","type":"Speaker","noId":true,"restricted":true,"hidden":true}]}'
    curl -X POST 127.0.0.1:4997/_qa/play -d '{"device":"Pixel 11 Pro","track":"tr0003","playing":true}'
    curl -X POST 127.0.0.1:4997/_qa/stop        # nothing active (204 from me/player)
    curl -X POST 127.0.0.1:4997/_qa/reset       # no devices, nothing active, the command log empty
    curl 127.0.0.1:4997/_qa/player              # what it is doing, and the commands it was sent

`/_qa/play` is the listener playing something in the Spotify app on that device (not a Web API
start); `"playing": false` leaves it paused there, still the active device.
"""

import argparse
import json
import re
import sys
import threading
import time
import uuid
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import parse_qs, unquote, urlencode, urlparse

ME = "yoin-qa"
OTHER = "someone-else"

FIRST = ["Silver", "Quiet", "Neon", "Paper", "Velvet", "Hollow", "Golden", "Distant", "Little", "Electric"]
SECOND = ["Harbor", "Lanterns", "Rain", "Satellite", "Garden", "Summer", "Static", "Echoes", "Tides", "Window"]
ARTISTS = [
    ("ar01", "Mina Asahi"), ("ar02", "The Paper Kites Club"), ("ar03", "Lumen Drive"),
    ("ar04", "Sora Kawa"), ("ar05", "Northern Choir"), ("ar06", "Ivy & the Lamps"),
    ("ar07", "Thiên Đường"), ("ar08", "Kaito Mori"), ("ar09", "Glass Animals Society"),
    ("ar10", "Rue Lavande"),
]


def artist_obj(aid, full=False):
    name = dict(ARTISTS)[aid]
    o = {"id": aid, "name": name, "type": "artist", "uri": f"spotify:artist:{aid}"}
    if full:
        o["images"] = []
        o["genres"] = []
    return o


ALBUMS = {}   # id -> simplified album (no tracks)
TRACKS = {}   # id -> track (with simplified album)
ALBUM_TRACKS = {}  # album id -> [track id]

for i in range(36):
    aid = f"al{i:02d}"
    artist = ARTISTS[i % len(ARTISTS)][0]
    n = 12 if i % 5 else 4  # every fifth is a 4-track single (an EP)
    year = 2026 - (i % 12)
    ALBUMS[aid] = {
        "id": aid, "name": f"{FIRST[i % 10]} {SECOND[(i * 3 + i // 10) % 10]}" + (" EP" if n == 4 else ""),
        "album_type": "album" if n > 4 else "single", "total_tracks": n,
        "release_date": f"{year}-0{1 + i % 9}-1{i % 9}", "images": [],
        "artists": [artist_obj(artist)], "uri": f"spotify:album:{aid}",
    }
    ids = []
    for k in range(n):
        tid = f"tr{i:02d}{k:02d}"
        TRACKS[tid] = {
            "id": tid, "name": f"{SECOND[(i + k) % 10]} {FIRST[(k * 7 + i) % 10]}" + (f" {k + 1}" if k > 9 else ""),
            "type": "track", "is_local": False, "duration_ms": 150_000 + (i * 7919 + k * 4111) % 150_000,
            "track_number": k + 1, "disc_number": 1, "explicit": False,
            "external_ids": {"isrc": f"QAYOIN{i:02d}{k:04d}"},
            "artists": [artist_obj(artist)], "album": ALBUMS[aid], "uri": f"spotify:track:{tid}",
        }
        ids.append(tid)
    ALBUM_TRACKS[aid] = ids

ALL_TRACK_IDS = list(TRACKS)
LIKED = ALL_TRACK_IDS[:350]          # newest like first
SAVED_ALBUMS = [f"al{i:02d}" for i in range(12)]
FOLLOWED = {"ar01", "ar02", "ar03", "ar04", "ar05", "ar06"}
SAVED = set(LIKED)

PLAYLISTS = {}  # id -> {name, description, owner, collaborative, public, items: [track id], snapshot, followed}


def new_snapshot():
    return uuid.uuid4().hex[:16]


def add_playlist(pid, name, owner, items, collaborative=False, description=""):
    PLAYLISTS[pid] = {
        "id": pid, "name": name, "description": description, "owner": owner,
        "collaborative": collaborative, "public": not collaborative, "items": list(items),
        "snapshot": new_snapshot(), "in_library": True,
    }


add_playlist("pl_owned", "QA Mix", ME,
             [ALL_TRACK_IDS[3], ALL_TRACK_IDS[40], ALL_TRACK_IDS[3], ALL_TRACK_IDS[77], ALL_TRACK_IDS[120],
              ALL_TRACK_IDS[200]], description="Songs for &amp; about QA")
add_playlist("pl_road", "Road Trip", ME, ALL_TRACK_IDS[60:72])
add_playlist("pl_collab", "Shared Jam", OTHER, ALL_TRACK_IDS[100:108], collaborative=True)
add_playlist("pl_followed", "Editorial Picks", OTHER, ALL_TRACK_IDS[150:175],
             description="Hand-picked by <a href=\"spotify:user:editor\">the editors</a>.")

OWNER_NAMES = {ME: "Yoin QA", OTHER: "Someone Else"}


class Player:
    """Spotify Connect in memory: devices, the active one, the list it plays and where it is in it."""

    def __init__(self):
        self.lock = threading.Lock()
        self.devices = []      # [{"id", "name", "type"}]
        self.active = None     # device id
        self.playing = False
        self.tracks = []       # track ids, as a start handed them
        self.index = 0
        self.base_ms = 0       # the position when the clock last started or stopped
        self.since = time.monotonic()
        self.shuffle = False
        self.repeat = "off"
        self.commands = []     # "PUT me/player/play device_id=…", newest last

    def device(self, key):
        return next((d for d in self.devices if key in (d["id"], d["name"])), None)

    def set_devices(self, specs):
        self.devices = []
        for spec in specs:
            name, kind = spec.get("name", "Device"), spec.get("type", "Computer")
            did = spec.get("id") or re.sub(r"[^a-z0-9]", "", name.lower())[:12] or uuid.uuid4().hex[:8]
            self.devices.append({"id": did, "name": name, "type": kind, "no_id": bool(spec.get("noId")),
                                 "restricted": bool(spec.get("restricted")), "hidden": bool(spec.get("hidden")),
                                 "private": bool(spec.get("private"))})
        if self.active and not self.device(self.active):
            self.active, self.playing = None, False

    @staticmethod
    def wire(d, active):
        """A device as Spotify answers it: a speaker on the local network (Sonos) may come with no id
        ("noId") and refuse Web API transfers ("restricted"); a device may be in a private session ("private")."""
        return {"id": None if d["no_id"] else d["id"], "name": d["name"], "type": d["type"], "is_active": active,
                "is_private_session": d.get("private", False), "is_restricted": d["restricted"],
                "volume_percent": 60, "supports_volume": True}

    def settle(self):
        """Moves the clock on: through the handed list, stopping at 0 when it runs out."""
        if not self.playing or not self.tracks:
            return
        now = time.monotonic()
        pos = self.base_ms + int((now - self.since) * 1000)
        while True:
            length = TRACKS[self.tracks[self.index]]["duration_ms"]
            if pos < length:
                break
            if self.repeat == "track":
                pos -= length
            elif self.index + 1 < len(self.tracks):
                self.index, pos = self.index + 1, pos - length
            elif self.repeat == "context":
                self.index, pos = 0, pos - length
            else:
                self.playing, pos = False, 0
                break
        self.base_ms, self.since = pos, now

    def hold(self, playing):
        self.settle()
        self.playing, self.since = playing, time.monotonic()

    def state(self):
        self.settle()
        d = self.device(self.active) if self.active else None
        if not d:
            return None
        item = TRACKS[self.tracks[self.index]] if self.tracks else None
        return {"device": Player.wire(d, True),
                "is_playing": self.playing and item is not None, "progress_ms": self.base_ms if item else 0,
                "item": item, "currently_playing_type": "track", "shuffle_state": self.shuffle,
                "repeat_state": self.repeat, "timestamp": int(time.time() * 1000), "context": None}


PLAYER = Player()


def owner_obj(uid):
    return {"id": uid, "display_name": OWNER_NAMES.get(uid, uid), "type": "user"}


def readable(p):
    return p["owner"] == ME or p["collaborative"]


def playlist_obj(p, base, with_items):
    o = {
        "id": p["id"], "name": p["name"], "description": p["description"], "images": [],
        "owner": owner_obj(p["owner"]), "public": p["public"], "collaborative": p["collaborative"],
        "snapshot_id": p["snapshot"], "type": "playlist", "uri": f"spotify:playlist:{p['id']}",
    }
    stub = {"href": f"{base}/playlists/{p['id']}/items", "total": len(p["items"])}
    if with_items and readable(p):
        o["items"] = page([item_obj(t) for t in p["items"]], 0, 100, f"{base}/playlists/{p['id']}/items", {})
    else:
        o["items"] = stub
    o["tracks"] = stub  # the deprecated name, still sent
    return o


def item_obj(tid):
    t = TRACKS[tid]
    return {"added_at": "2026-10-01T12:00:00Z", "is_local": False, "item": t, "track": t}


def page(items, offset, limit, href, extra):
    total = len(items)
    chunk = items[offset:offset + limit]
    nxt = None
    if offset + limit < total:
        q = urlencode(sorted(extra.items()))
        nxt = f"{href}?offset={offset + limit}&limit={limit}" + (f"&{q}" if q else "")
    return {"href": href, "items": chunk, "limit": limit, "offset": offset, "total": total, "next": nxt,
            "previous": None}


def fold(s):
    import unicodedata
    s = unicodedata.normalize("NFKD", s.replace("Đ", "D").replace("đ", "d"))
    return "".join(c for c in s if not unicodedata.combining(c)).lower()


class Handler(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"

    def log_message(self, fmt, *args):
        sys.stderr.write("%s %s\n" % (self.command, self.path))

    # Plumbing

    def base(self):
        return f"http://{self.headers.get('Host', '127.0.0.1')}/v1"

    def reply(self, status, body=None):
        data = b"" if body is None else json.dumps(body).encode()
        self.send_response(status)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(data)))
        self.end_headers()
        self.wfile.write(data)

    def error(self, status, message):
        self.reply(status, {"error": {"status": status, "message": message}})

    def body(self):
        n = int(self.headers.get("Content-Length") or 0)
        if not n:
            return {}
        try:
            return json.loads(self.rfile.read(n))
        except ValueError:
            return {}

    def route(self):
        u = urlparse(self.path)
        path = re.sub(r"^/v1", "", u.path).strip("/")
        q = {k: v[0] for k, v in parse_qs(u.query, keep_blank_values=True).items()}
        return path, q

    def paging_args(self, q, default=20, cap=50):
        limit = int(q.get("limit", default))
        offset = int(q.get("offset", 0))
        if limit < 1 or limit > cap:
            self.error(400, "Invalid limit")
            return None
        return offset, limit

    def uris(self, q):
        return [unquote(x) for x in q.get("uris", "").split(",") if x]

    # Verbs

    def do_GET(self):
        path, q = self.route()
        b = self.base()
        if path == "_qa/player":  # QA's own look, no token
            with PLAYER.lock:
                state = PLAYER.state()
                return self.reply(200, {"devices": PLAYER.devices, "active": PLAYER.active, "state": state,
                                        "handed": len(PLAYER.tracks), "commands": PLAYER.commands[-40:]})
        auth = self.headers.get("Authorization", "")
        if not auth.startswith("Bearer "):
            return self.error(401, "No token provided")

        if path == "me":
            return self.reply(200, {"id": ME, "display_name": "Yoin QA", "product": "premium", "type": "user"})

        if path == "me/tracks":
            a = self.paging_args(q)
            if not a:
                return
            items = [{"added_at": "2026-10-01T12:00:00Z", "track": TRACKS[t]} for t in LIKED]
            return self.reply(200, page(items, a[0], a[1], f"{b}/me/tracks", {}))

        if path == "me/albums":
            a = self.paging_args(q)
            if not a:
                return
            items = []
            for aid in SAVED_ALBUMS:
                full = dict(ALBUMS[aid])
                full["tracks"] = page([TRACKS[t] for t in ALBUM_TRACKS[aid]], 0, 50, f"{b}/albums/{aid}/tracks", {})
                items.append({"added_at": "2026-09-01T12:00:00Z", "album": full})
            return self.reply(200, page(items, a[0], a[1], f"{b}/me/albums", {}))

        if path == "me/playlists":
            a = self.paging_args(q)
            if not a:
                return
            items = [playlist_obj(p, b, False) for p in PLAYLISTS.values() if p["in_library"]]
            return self.reply(200, page(items, a[0], a[1], f"{b}/me/playlists", {}))

        if path == "me/following":
            if q.get("type") != "artist":
                return self.error(400, "Only artist is supported")
            limit = int(q.get("limit", 20))
            ids = sorted(FOLLOWED)
            after = q.get("after")
            start = ids.index(after) + 1 if after in ids else 0
            chunk = ids[start:start + limit]
            nxt = f"{b}/me/following?type=artist&limit={limit}&after={chunk[-1]}" \
                if start + limit < len(ids) and chunk else None
            return self.reply(200, {"artists": {"items": [artist_obj(x, True) for x in chunk], "next": nxt,
                                                "total": len(ids), "limit": limit}})

        if path == "me/library/contains":
            out = []
            for uri in self.uris(q):
                parts = uri.split(":")
                kind, rid = (parts[1], parts[2]) if len(parts) == 3 else ("", "")
                out.append(rid in SAVED if kind == "track" else rid in FOLLOWED if kind == "artist"
                           else PLAYLISTS.get(rid, {}).get("in_library", False) if kind == "playlist"
                           else rid in SAVED_ALBUMS if kind == "album" else False)
            return self.reply(200, out)

        if path == "me/player/recently-played":
            items = [{"track": TRACKS[t], "played_at": f"2026-11-0{1 + i % 5}T1{i % 10}:00:00Z"}
                     for i, t in enumerate(ALL_TRACK_IDS[::7][:30])]
            return self.reply(200, {"items": items, "next": None, "cursors": None, "limit": 50})

        if path == "me/player/devices":
            with PLAYER.lock:
                # A "hidden" one is missing from the list while me/player still names it (a Sonos it plays on).
                return self.reply(200, {"devices": [
                    Player.wire(d, d["id"] == PLAYER.active) for d in PLAYER.devices if not d["hidden"]]})

        if path == "me/player":
            with PLAYER.lock:
                state = PLAYER.state()
            return self.reply(200, state) if state else self.reply(204)

        if path == "me/player/queue":
            with PLAYER.lock:
                state = PLAYER.state()
                later = [TRACKS[t] for t in PLAYER.tracks[PLAYER.index + 1:]] if state else []
            return self.reply(200, {"currently_playing": state and state["item"], "queue": later})


        m = re.fullmatch(r"playlists/([^/]+)", path)
        if m:
            p = PLAYLISTS.get(m.group(1))
            return self.reply(200, playlist_obj(p, b, True)) if p else self.error(404, "Resource not found")

        m = re.fullmatch(r"playlists/([^/]+)/items", path)
        if m:
            p = PLAYLISTS.get(m.group(1))
            if not p:
                return self.error(404, "Resource not found")
            if not readable(p):
                return self.error(403, "Forbidden")
            a = self.paging_args(q, 100, 100)
            if not a:
                return
            return self.reply(200, page([item_obj(t) for t in p["items"]], a[0], a[1],
                                        f"{b}/playlists/{p['id']}/items", {}))

        m = re.fullmatch(r"albums/([^/]+)", path)
        if m:
            al = ALBUMS.get(m.group(1))
            if not al:
                return self.error(404, "Resource not found")
            full = dict(al)
            full["tracks"] = page([TRACKS[t] for t in ALBUM_TRACKS[al["id"]]], 0, 50, f"{b}/albums/{al['id']}/tracks", {})
            return self.reply(200, full)

        m = re.fullmatch(r"albums/([^/]+)/tracks", path)
        if m and m.group(1) in ALBUMS:
            a = self.paging_args(q)
            if not a:
                return
            return self.reply(200, page([TRACKS[t] for t in ALBUM_TRACKS[m.group(1)]], a[0], a[1],
                                        f"{b}/albums/{m.group(1)}/tracks", {}))

        m = re.fullmatch(r"artists/([^/]+)", path)
        if m:
            aid = m.group(1)
            return self.reply(200, artist_obj(aid, True)) if aid in dict(ARTISTS) else self.error(404, "Resource not found")

        m = re.fullmatch(r"artists/([^/]+)/albums", path)
        if m:
            aid = m.group(1)
            if aid not in dict(ARTISTS):
                return self.error(404, "Resource not found")
            a = self.paging_args(q, 10, 10)
            if not a:
                return
            mine = [al for al in ALBUMS.values() if al["artists"][0]["id"] == aid]
            extra = {"include_groups": q["include_groups"]} if "include_groups" in q else {}
            return self.reply(200, page(mine, a[0], a[1], f"{b}/artists/{aid}/albums", extra))

        if path == "search":
            a = self.paging_args(q, 10, 10)
            if not a:
                return
            term = fold(q.get("q", ""))
            types = q.get("type", "").split(",")
            out = {}
            extra = {"q": q.get("q", ""), "type": q.get("type", "")}
            # Every query matches something, so paging always shows (the term only narrows songs a little).
            def hits(objs, name):
                found = [o for o in objs if term and term in fold(o[name])]
                return found if len(found) >= 12 else found + [o for o in objs if o not in found]
            if "track" in types:
                out["tracks"] = page(hits(list(TRACKS.values()), "name")[:45], a[0], a[1], f"{b}/search", extra)
            if "album" in types:
                out["albums"] = page(hits(list(ALBUMS.values()), "name"), a[0], a[1], f"{b}/search", extra)
            if "artist" in types:
                out["artists"] = page([artist_obj(x, True) for x, _ in ARTISTS], a[0], a[1], f"{b}/search", extra)
            if "playlist" in types:
                found = [playlist_obj(p, b, False) for p in PLAYLISTS.values()]
                found += [None]  # Spotify lists nulls among playlist results
                found += [playlist_obj({**p, "id": f"{p['id']}_x{i}", "name": f"{p['name']} {i + 2}"}, b, False)
                          for i, p in enumerate(list(PLAYLISTS.values()) * 4)]
                out["playlists"] = page(found, a[0], a[1], f"{b}/search", extra)
            return self.reply(200, out)

        self.error(404, f"Fake Spotify has no {path}")

    def do_POST(self):
        path, q = self.route()
        body = self.body()
        if path == "me/playlists":
            pid = "pl_" + uuid.uuid4().hex[:10]
            add_playlist(pid, body.get("name") or "New Playlist", ME, [], description=body.get("description", ""))
            PLAYLISTS[pid]["public"] = bool(body.get("public", True))
            return self.reply(201, playlist_obj(PLAYLISTS[pid], self.base(), True))
        m = re.fullmatch(r"playlists/([^/]+)/items", path)
        if m:
            p = PLAYLISTS.get(m.group(1))
            if not p:
                return self.error(404, "Resource not found")
            if not readable(p):
                return self.error(403, "Forbidden")
            uris = body.get("uris") or self.uris(q)
            ids = [u.split(":")[-1] for u in uris]
            if any(i not in TRACKS for i in ids):
                return self.error(400, "Invalid base62 id")
            pos = body.get("position")
            if pos is None:
                p["items"] += ids
            else:
                p["items"][pos:pos] = ids
            p["snapshot"] = new_snapshot()
            return self.reply(201, {"snapshot_id": p["snapshot"]})
        if path == "_qa/devices":
            with PLAYER.lock:
                PLAYER.set_devices(body.get("devices") or [])
                return self.reply(200, {"devices": PLAYER.devices})
        if path == "_qa/play":
            # The listener plays (or leaves paused) a song in the Spotify app on that device.
            with PLAYER.lock:
                d = PLAYER.device(body.get("device", ""))
                tid = body.get("track") or ALL_TRACK_IDS[0]
                if not d or tid not in TRACKS:
                    return self.error(404, "No such device or track")
                PLAYER.active, PLAYER.tracks, PLAYER.index = d["id"], [tid], 0
                PLAYER.base_ms, PLAYER.since = int(body.get("position_ms", 0)), time.monotonic()
                PLAYER.playing = bool(body.get("playing", True))
                return self.reply(200, PLAYER.state())
        if path == "_qa/stop":
            with PLAYER.lock:
                PLAYER.active, PLAYER.playing, PLAYER.tracks, PLAYER.index = None, False, [], 0
            return self.reply(204)
        if path == "_qa/reset":  # no devices, nothing active, the command log empty
            with PLAYER.lock:
                PLAYER.set_devices([])
                PLAYER.active, PLAYER.playing, PLAYER.tracks, PLAYER.index = None, False, [], 0
                PLAYER.shuffle, PLAYER.repeat, PLAYER.commands = False, "off", []
            return self.reply(204)
        if path in ("me/player/next", "me/player/previous"):
            return self.player_command("POST", path, q, body)
        if path == "me/player/play":
            return self.error(404, "Player command failed: No active device found")
        self.error(404, f"Fake Spotify has no POST {path}")

    def player_command(self, method, path, q, body):
        """Spotify Connect's commands: on ?device_id, or the active device (404 when there is none)."""
        with PLAYER.lock:
            p = PLAYER
            p.commands.append(f"{method} {path} " + "&".join(f"{k}={v}" for k, v in sorted(q.items())))
            if path == "me/player":  # transfer
                ids = body.get("device_ids") or []
                d = p.device(ids[0]) if ids else None
                if not d or d["no_id"]:
                    return self.error(404, "Device not found")
                if d["restricted"]:
                    return self.error(403, "Player command failed: Restriction violated")
                p.settle()
                p.active = d["id"]
                if body.get("play") and p.tracks:
                    p.hold(True)
                return self.reply(204)
            did = q.get("device_id") or p.active
            if not did:
                return self.error(404, "Player command failed: No active device found")
            if not p.device(did):
                return self.error(404, "Device not found")
            if path == "me/player/play":
                uris = body.get("uris")
                if uris is not None:
                    ids = [u.split(":")[-1] for u in uris]
                    if not ids or any(i not in TRACKS for i in ids):
                        return self.error(400, "Invalid track uri")
                    p.tracks, p.index = ids, 0
                    p.base_ms = int(body.get("position_ms", 0))
                elif not p.tracks:
                    return self.error(404, "Player command failed: No active device found")
                else:
                    p.settle()
                p.active, p.playing, p.since = did, True, time.monotonic()
                return self.reply(204)
            if did != p.active:
                return self.error(404, "Device not found")
            if path == "me/player/pause":
                p.hold(False)
            elif path == "me/player/seek":
                p.settle()
                p.base_ms, p.since = max(0, int(q.get("position_ms", 0))), time.monotonic()
            elif path == "me/player/shuffle":
                p.shuffle = q.get("state") == "true"
            elif path == "me/player/repeat":
                p.repeat = q.get("state", "off")
            elif path in ("me/player/next", "me/player/previous") and p.tracks:
                step = 1 if path.endswith("next") else -1
                p.index = max(0, min(len(p.tracks) - 1, p.index + step))
                p.base_ms, p.since = 0, time.monotonic()
            return self.reply(204)

    def do_PUT(self):
        path, q = self.route()
        body = self.body()
        if path == "me/library":
            for uri in self.uris(q):
                parts = uri.split(":")
                if len(parts) != 3:
                    return self.error(400, "Invalid uri")
                kind, rid = parts[1], parts[2]
                if kind == "track":
                    if rid not in SAVED:
                        SAVED.add(rid)
                        LIKED.insert(0, rid)
                elif kind == "artist":
                    FOLLOWED.add(rid)
                elif kind == "album" and rid not in SAVED_ALBUMS:
                    SAVED_ALBUMS.insert(0, rid)
                elif kind == "playlist" and rid in PLAYLISTS:
                    PLAYLISTS[rid]["in_library"] = True
            return self.reply(200)
        m = re.fullmatch(r"playlists/([^/]+)", path)
        if m:
            p = PLAYLISTS.get(m.group(1))
            if not p:
                return self.error(404, "Resource not found")
            if p["owner"] != ME:
                return self.error(403, "Forbidden")
            for k in ("name", "description", "public"):
                if k in body:
                    p[k] = body[k]
            return self.reply(200)
        if path in ("me/player", "me/player/play", "me/player/pause", "me/player/seek", "me/player/shuffle",
                    "me/player/repeat"):
            return self.player_command("PUT", path, q, body)
        self.error(404, f"Fake Spotify has no PUT {path}")

    def do_DELETE(self):
        path, q = self.route()
        body = self.body()
        if path == "me/library":
            for uri in self.uris(q):
                parts = uri.split(":")
                if len(parts) != 3:
                    return self.error(400, "Invalid uri")
                kind, rid = parts[1], parts[2]
                if kind == "track":
                    SAVED.discard(rid)
                    if rid in LIKED:
                        LIKED.remove(rid)
                elif kind == "artist":
                    FOLLOWED.discard(rid)
                elif kind == "album" and rid in SAVED_ALBUMS:
                    SAVED_ALBUMS.remove(rid)
                elif kind == "playlist" and rid in PLAYLISTS:
                    PLAYLISTS[rid]["in_library"] = False
            return self.reply(200)
        m = re.fullmatch(r"playlists/([^/]+)/items", path)
        if m:
            p = PLAYLISTS.get(m.group(1))
            if not p:
                return self.error(404, "Resource not found")
            if not readable(p):
                return self.error(403, "Forbidden")
            snap = body.get("snapshot_id")
            if snap and snap != p["snapshot"]:
                # Spotify applies a positional removal against the snapshot named; this fake only knows
                # the current one, so a stale snapshot is refused instead of guessed at.
                return self.error(400, "Snapshot id is stale")
            drop = set()
            for it in body.get("items") or body.get("tracks") or []:
                tid = it.get("uri", "").split(":")[-1]
                if "positions" in it:
                    for at in it["positions"]:
                        if 0 <= at < len(p["items"]) and p["items"][at] == tid:
                            drop.add(at)
                        else:
                            return self.error(400, f"No {tid} at position {at}")
                else:
                    drop.update(i for i, x in enumerate(p["items"]) if x == tid)
            p["items"] = [x for i, x in enumerate(p["items"]) if i not in drop]
            p["snapshot"] = new_snapshot()
            return self.reply(200, {"snapshot_id": p["snapshot"]})
        self.error(404, f"Fake Spotify has no DELETE {path}")


def main():
    ap = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    ap.add_argument("--port", type=int, default=4997)
    ap.add_argument("--host", default="127.0.0.1")
    ap.add_argument("--device", action="append", default=[], metavar="NAME:TYPE",
                    help="a Spotify Connect device on the account (Computer, Smartphone, Tablet, Speaker); repeatable")
    args = ap.parse_args()
    PLAYER.set_devices([{"name": n, "type": t or "Computer"} for n, _, t in (d.partition(":") for d in args.device)])
    srv = ThreadingHTTPServer((args.host, args.port), Handler)
    print(f"fake Spotify on http://{args.host}:{args.port} (launch with -spotifyFake http://{args.host}:{args.port})",
          flush=True)
    srv.serve_forever()


if __name__ == "__main__":
    main()
