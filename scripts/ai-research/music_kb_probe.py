#!/usr/bin/env python3
"""
Structured-data coverage probe for docs/research/ai-provider-search.md §3.

Runs the five questions in questions.json against MusicBrainz, Wikipedia and
(optionally) Discogs, the way the proposed on-device function-calling tools
would, and reports for each source: did it resolve the entity, which credits it
holds (lyricist / composer / arranger / producer / engineer / label / date),
how many HTTP requests that took, and the latency.

The request sequences here are the reference implementation of the tool
schemas proposed in the report:

  MusicBrainz  artist search -> recording search (arid:) -> recording lookup
               (inc=artist-rels+work-rels+releases) -> work lookup (inc=artist-rels)
               album questions: release-group search -> release browse -> release
               lookup (inc=artist-rels+labels)
  Wikipedia    Action API full-text search on {lang}.wikipedia.org -> REST page summary
  Discogs      database/search (needs a token) -> release lookup (extraartists)

Etiquette:
  - MusicBrainz: at most one request per second, meaningful User-Agent
    https://musicbrainz.org/doc/MusicBrainz_API/Rate_Limiting
  - Wikimedia: descriptive User-Agent with a contact URL
    https://foundation.wikimedia.org/wiki/Policy:Wikimedia_Foundation_User-Agent_Policy
  - Discogs: unique User-Agent; honour X-Discogs-Ratelimit-Remaining
    https://www.discogs.com/developers

Env (all optional):
  DISCOGS_TOKEN   a Discogs personal access token (search is auth-only); skipped if unset
  YOIN_UA_CONTACT contact URL for the User-Agent, default the repo URL
  OUT_DIR         default $TMPDIR/yoin-ai-research/kb-<ts>

Python 3.9+, stdlib only.

  python3 scripts/ai-research/music_kb_probe.py
"""
from __future__ import annotations

import json
import os
import sys
import tempfile
import time
import urllib.error
import urllib.parse
import urllib.request
from datetime import datetime, timezone

HERE = os.path.dirname(os.path.abspath(__file__))
CONTACT = os.environ.get("YOIN_UA_CONTACT", "https://github.com/p2o51/yoin")
UA = f"YoinResearch/0.1 ( {CONTACT} )"
MB = "https://musicbrainz.org/ws/2"
DISCOGS = "https://api.discogs.com"

CREDIT_TYPES = {
    "lyricist", "composer", "writer", "arranger", "producer", "engineer",
    "recording", "mix", "mastering", "instrument arranger", "orchestrator",
}


class Http:
    def __init__(self) -> None:
        self.count: dict[str, int] = {}
        self._last_mb = 0.0

    def get(self, url: str, source: str, headers: dict | None = None) -> tuple[int, dict | None, dict]:
        if source == "musicbrainz":  # hard 1 req/s ceiling, keep a margin
            wait = 1.1 - (time.monotonic() - self._last_mb)
            if wait > 0:
                time.sleep(wait)
            self._last_mb = time.monotonic()
        self.count[source] = self.count.get(source, 0) + 1
        h = {"User-Agent": UA, "Accept": "application/json"}
        h.update(headers or {})
        req = urllib.request.Request(url, headers=h)
        try:
            with urllib.request.urlopen(req, timeout=30) as r:
                return r.status, json.loads(r.read().decode()), dict(r.headers)
        except urllib.error.HTTPError as e:
            body = e.read().decode(errors="replace")
            try:
                return e.code, json.loads(body), dict(e.headers)
            except json.JSONDecodeError:
                return e.code, {"_raw": body[:300]}, dict(e.headers)
        except Exception as e:  # proxy / DNS / TLS
            return -1, {"_error": f"{type(e).__name__}: {e}"}, {}


def q(s: str) -> str:
    return urllib.parse.quote(s, safe="")


def lucene_escape(s: str) -> str:
    return s.replace("\\", "\\\\").replace('"', '\\"')


def rel_credits(relations: list[dict] | None) -> list[str]:
    out = []
    for r in relations or []:
        t = r.get("type", "")
        if t in CREDIT_TYPES and r.get("artist"):
            attrs = ",".join(r.get("attributes") or [])
            out.append(f"{t}{'[' + attrs + ']' if attrs else ''}: {r['artist'].get('name')}")
        elif t in ("recorded at", "mixed at") and r.get("place"):
            out.append(f"{t}: {r['place'].get('name')}")
    return out


def musicbrainz(http: Http, item: dict) -> dict:
    t0 = time.monotonic()
    res: dict = {"resolved": False, "credits": []}
    code, data, _ = http.get(
        f"{MB}/artist?query={q(item['artist'])}&limit=3&fmt=json", "musicbrainz")
    if code != 200 or not (data or {}).get("artists"):
        res["error"] = f"artist search http={code} {str(data)[:160]}"
        return res
    artist = data["artists"][0]
    res["artist"] = f"{artist.get('name')} ({artist.get('id')}, score {artist.get('score')})"
    arid = artist["id"]

    if item["title"] != item["album"]:  # track question
        query = f'recording:"{lucene_escape(item["title"])}" AND arid:{arid}'
        code, data, _ = http.get(f"{MB}/recording?query={q(query)}&limit=5&fmt=json", "musicbrainz")
        recs = (data or {}).get("recordings") or []
        if code != 200 or not recs:
            res["error"] = f"recording search http={code} hits=0"
            return res
        rec_id = recs[0]["id"]
        code, rec, _ = http.get(
            f"{MB}/recording/{rec_id}?inc=artist-rels+work-rels+releases+place-rels&fmt=json",
            "musicbrainz")
        res["resolved"] = code == 200
        res["recording"] = rec_id
        res["first_release_date"] = (rec or {}).get("first-release-date")
        res["releases"] = [r.get("title") for r in (rec or {}).get("releases", [])][:5]
        res["credits"] += rel_credits((rec or {}).get("relations"))
        for rel in (rec or {}).get("relations") or []:
            if rel.get("type") == "performance" and rel.get("work"):
                code, work, _ = http.get(
                    f"{MB}/work/{rel['work']['id']}?inc=artist-rels&fmt=json", "musicbrainz")
                res["credits"] += [f"work {c}" for c in rel_credits((work or {}).get("relations"))]
                break
    else:  # album question
        query = f'releasegroup:"{lucene_escape(item["album"])}" AND arid:{arid}'
        code, data, _ = http.get(
            f"{MB}/release-group?query={q(query)}&limit=3&fmt=json", "musicbrainz")
        rgs = (data or {}).get("release-groups") or []
        if code != 200 or not rgs:
            res["error"] = f"release-group search http={code} hits=0"
            return res
        rg = rgs[0]
        res["release_group"] = f"{rg.get('title')} ({rg.get('id')}) first={rg.get('first-release-date')}"
        code, data, _ = http.get(
            f"{MB}/release?release-group={rg['id']}&limit=5&fmt=json", "musicbrainz")
        rels = sorted((data or {}).get("releases") or [], key=lambda r: r.get("date") or "9999")
        if rels:
            code, rel, _ = http.get(
                f"{MB}/release/{rels[0]['id']}?inc=artist-rels+labels+recording-level-rels&fmt=json",
                "musicbrainz")
            res["resolved"] = code == 200
            res["release"] = f"{(rel or {}).get('title')} {(rel or {}).get('date')}"
            res["labels"] = [
                f"{(li.get('label') or {}).get('name')} {li.get('catalog-number')}"
                for li in (rel or {}).get("label-info") or []
            ]
            res["credits"] += rel_credits((rel or {}).get("relations"))
    res["latency_s"] = round(time.monotonic() - t0, 2)
    return res


def wikipedia(http: Http, item: dict) -> dict:
    t0 = time.monotonic()
    lang = item.get("lang", "en")
    host = f"https://{lang}.wikipedia.org"
    term = item["album"] if item["title"] == item["album"] else item["title"]
    query = f"{term} {item['artist']}"
    code, data, _ = http.get(
        f"{host}/w/api.php?action=query&list=search&srsearch={q(query)}&srlimit=3"
        "&format=json&formatversion=2", "wikipedia")
    hits = ((data or {}).get("query") or {}).get("search") or []
    res: dict = {"lang": lang, "resolved": False, "hits": [h.get("title") for h in hits]}
    if code != 200 or not hits:
        res["error"] = f"search http={code} {str(data)[:160]}"
        return res
    title = hits[0]["title"]
    headers = {"Accept-Language": "zh-cn"} if lang == "zh" else None  # simplified-script variant
    code, summ, _ = http.get(
        f"{host}/api/rest_v1/page/summary/{q(title.replace(' ', '_'))}", "wikipedia", headers)
    res["resolved"] = code == 200
    res["title"] = title
    res["extract"] = ((summ or {}).get("extract") or "")[:240]
    res["wikibase_item"] = (summ or {}).get("wikibase_item")
    res["page_url"] = (((summ or {}).get("content_urls") or {}).get("mobile") or {}).get("page")
    res["latency_s"] = round(time.monotonic() - t0, 2)
    return res


def discogs(http: Http, item: dict, token: str | None) -> dict:
    t0 = time.monotonic()
    headers = {"Authorization": f"Discogs token={token}"} if token else None
    params = {"type": "release", "per_page": "3", "artist": item["artist"],
              "release_title": item["album"]}
    code, data, hdrs = http.get(
        f"{DISCOGS}/database/search?{urllib.parse.urlencode(params)}", "discogs", headers)
    res: dict = {"resolved": False, "auth": bool(token),
                 "ratelimit_remaining": hdrs.get("X-Discogs-Ratelimit-Remaining")}
    results = (data or {}).get("results") or []
    if code != 200 or not results:
        res["error"] = f"search http={code} {str(data)[:160]}"
        return res
    rid = results[0]["id"]
    code, rel, hdrs = http.get(f"{DISCOGS}/releases/{rid}", "discogs", headers)
    res["resolved"] = code == 200
    res["release"] = f"{(rel or {}).get('title')} {(rel or {}).get('released')} ({rid})"
    res["labels"] = [f"{l.get('name')} {l.get('catno')}" for l in (rel or {}).get("labels") or []]
    credits = [f"{e.get('role')}: {e.get('name')}" for e in (rel or {}).get("extraartists") or []]
    for tr in (rel or {}).get("tracklist") or []:
        if tr.get("title") == item["title"]:
            credits += [f"track {e.get('role')}: {e.get('name')}" for e in tr.get("extraartists") or []]
    res["credits"] = credits[:20]
    res["ratelimit_remaining"] = hdrs.get("X-Discogs-Ratelimit-Remaining")
    res["latency_s"] = round(time.monotonic() - t0, 2)
    return res


def main() -> int:
    with open(os.path.join(HERE, "questions.json"), encoding="utf-8") as f:
        items = json.load(f)["questions"]
    token = os.environ.get("DISCOGS_TOKEN", "").strip() or None
    ts = datetime.now(timezone.utc).strftime("%Y%m%dT%H%M%SZ")
    out_dir = os.environ.get("OUT_DIR") or os.path.join(
        tempfile.gettempdir(), "yoin-ai-research", f"kb-{ts}")
    os.makedirs(out_dir, exist_ok=True)

    http = Http()
    rows = []
    for item in items:
        row = {
            "id": item["id"],
            "musicbrainz": musicbrainz(http, item),
            "wikipedia": wikipedia(http, item),
            "discogs": discogs(http, item, token),
        }
        rows.append(row)
        print(json.dumps(row, ensure_ascii=False), flush=True)

    with open(os.path.join(out_dir, "kb_summary.json"), "w", encoding="utf-8") as f:
        json.dump({"requests": http.count, "rows": rows}, f, ensure_ascii=False, indent=2)
    print(f"\nrequests per source: {http.count}\nsummary in {out_dir}", file=sys.stderr)
    return 0


if __name__ == "__main__":
    sys.exit(main())
