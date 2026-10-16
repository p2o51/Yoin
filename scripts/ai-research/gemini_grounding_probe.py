#!/usr/bin/env python3
"""
Gemini "Grounding with Google Search" probe for docs/research/ai-provider-search.md.

What it does
------------
For every (key, model) pair it sends the same CJK music question four ways and
records how the API reacts:

  plain        no tools                                   (control: is the key/model usable at all?)
  search       tools=[{google_search:{}}]                 (the call Yoin's About/Ask makes today)
  search+fn    google_search + one functionDeclaration    (Gemini 3 "built-in tool + function calling" combo,
               + toolConfig.includeServerSideToolInvocations   needed if MusicBrainz tools ride the same request)
  legacy       tools=[{google_search_retrieval:{...}}]    (old 1.5-era tool; expect a rejection on new models)

For each call it writes the raw response to the output directory and prints a
summary: HTTP status, google.rpc status / reason / quota violations, latency,
token usage, and the shape of groundingMetadata (query count, chunk hosts,
support count, whether segment offsets are UTF-8 byte offsets, and what the
Search Suggestions HTML contains). renderedContent is also saved as .html so
it can be eyeballed in a browser in light and dark mode.

Keys are read from the environment only and never printed:

  GEMINI_FREE_KEY   an AI Studio key on a project WITHOUT billing (free tier)
  GEMINI_PAID_KEY   an AI Studio key on a project WITH billing enabled (Tier 1+)

Optional:
  GEMINI_MODELS     comma list, default "gemini-3.1-flash-lite,gemini-2.5-flash-lite"
  GEMINI_CASES      comma list of cases to run, default "plain,search,search+fn,legacy"
  OUT_DIR           where raw JSON/HTML goes, default $TMPDIR/yoin-ai-research/gemini-<ts>

Each run costs at most 4 grounded prompts per paid model. Python 3.9+, stdlib only.

  GEMINI_FREE_KEY=... GEMINI_PAID_KEY=... python3 scripts/ai-research/gemini_grounding_probe.py
"""
from __future__ import annotations

import json
import os
import re
import sys
import tempfile
import time
import urllib.error
import urllib.parse
import urllib.request
from datetime import datetime, timezone

BASE = "https://generativelanguage.googleapis.com/v1beta/models"
QUESTION = (
    "周杰伦《晴天》的作词、作曲分别是谁？收录在哪张专辑，哪一年发行？"
    "请用中文回答，两三句话即可。"
)
FUNCTION_DECL = {
    "name": "musicbrainz_search_recording",
    "description": "Search MusicBrainz for a recording and return its credits.",
    "parameters": {
        "type": "object",
        "properties": {
            "title": {"type": "string", "description": "Track title"},
            "artist": {"type": "string", "description": "Artist name"},
        },
        "required": ["title"],
    },
}


def body_for(case: str) -> dict:
    contents = [{"role": "user", "parts": [{"text": QUESTION}]}]
    if case == "plain":
        return {"contents": contents}
    if case == "search":
        return {"contents": contents, "tools": [{"google_search": {}}]}
    if case == "search+fn":
        return {
            "contents": contents,
            "tools": [{"google_search": {}}, {"function_declarations": [FUNCTION_DECL]}],
            "tool_config": {"include_server_side_tool_invocations": True},
        }
    if case == "legacy":
        return {
            "contents": contents,
            "tools": [{
                "google_search_retrieval": {
                    "dynamic_retrieval_config": {"mode": "MODE_DYNAMIC", "dynamic_threshold": 0.3},
                },
            }],
        }
    raise ValueError(case)


def call(key: str, model: str, body: dict) -> tuple[int, dict | None, str, float]:
    url = f"{BASE}/{urllib.parse.quote(model)}:generateContent"
    req = urllib.request.Request(
        url,
        data=json.dumps(body).encode(),
        method="POST",
        # Header instead of ?key= so the key never lands in URLs or proxy logs.
        headers={"Content-Type": "application/json", "x-goog-api-key": key},
    )
    t0 = time.monotonic()
    try:
        with urllib.request.urlopen(req, timeout=90) as resp:
            raw = resp.read().decode()
            code = resp.status
    except urllib.error.HTTPError as e:
        raw = e.read().decode(errors="replace")
        code = e.code
    except Exception as e:  # network / proxy / TLS
        return -1, None, f"{type(e).__name__}: {e}", time.monotonic() - t0
    dt = time.monotonic() - t0
    try:
        return code, json.loads(raw), raw, dt
    except json.JSONDecodeError:
        return code, None, raw, dt


def summarize_error(err: dict) -> dict:
    out = {"code": err.get("code"), "status": err.get("status"), "message": err.get("message")}
    for d in err.get("details", []) or []:
        t = d.get("@type", "")
        if t.endswith("ErrorInfo"):
            out["reason"] = d.get("reason")
        elif t.endswith("QuotaFailure"):
            out["quota_violations"] = [
                {k: v.get(k) for k in ("quotaMetric", "quotaId", "quotaValue")}
                for v in d.get("violations", [])
            ]
        elif t.endswith("RetryInfo"):
            out["retry_delay"] = d.get("retryDelay")
    return out


def host(uri: str) -> str:
    try:
        return urllib.parse.urlparse(uri).netloc
    except ValueError:
        return "?"


def summarize_grounding(resp: dict) -> dict:
    cand = (resp.get("candidates") or [{}])[0]
    parts = (cand.get("content") or {}).get("parts") or []
    text_parts = [p.get("text", "") for p in parts if "text" in p and not p.get("thought")]
    text = "".join(text_parts)
    gm = cand.get("groundingMetadata") or {}
    chunks = gm.get("groundingChunks") or []
    supports = gm.get("groundingSupports") or []
    sep = gm.get("searchEntryPoint") or {}
    html = sep.get("renderedContent") or ""

    # Are segment offsets UTF-8 byte offsets (per the v1beta discovery doc) or
    # UTF-16/char offsets? Check against the first text part.
    offset_unit = "n/a"
    if supports and text_parts:
        first = text_parts[0]
        b = first.encode()
        byte_hits = char_hits = 0
        for s in supports:
            seg = s.get("segment") or {}
            if seg.get("partIndex", 0) != 0 or "text" not in seg:
                continue
            st, en = seg.get("startIndex", 0), seg.get("endIndex", 0)
            if b[st:en].decode(errors="replace") == seg["text"]:
                byte_hits += 1
            if first[st:en] == seg["text"]:
                char_hits += 1
        offset_unit = f"utf8-byte matches={byte_hits}, char matches={char_hits}, supports={len(supports)}"

    return {
        "finishReason": cand.get("finishReason"),
        "parts": [sorted(p.keys()) for p in parts],
        "answer_preview": text[:160].replace("\n", " "),
        "webSearchQueries": gm.get("webSearchQueries"),
        "groundingChunks": len(chunks),
        "chunk_uri_hosts": sorted({host((c.get("web") or {}).get("uri", "")) for c in chunks}),
        "chunk_titles": [(c.get("web") or {}).get("title") for c in chunks][:8],
        "groundingSupports": len(supports),
        "segment_offsets": offset_unit,
        "searchEntryPoint": {
            "has_renderedContent": bool(html),
            "html_bytes": len(html.encode()),
            "has_style": "<style" in html,
            # Decides whether the WebView can keep JavaScript off / block network loads.
            "has_script": "<script" in html.lower(),
            "external_resources": sorted({host(u) for u in re.findall(r'(?:src|url\()\s*=?\s*["\']?(https?://[^"\')\s>]+)', html)}),
            "has_dark_media_query": "prefers-color-scheme" in html,
            "chip_count": len(re.findall(r"<a\b", html)),
            "chip_hosts": sorted({host(h) for h in re.findall(r'href="([^"]+)"', html)}),
            "has_sdkBlob": bool(sep.get("sdkBlob")),
        },
        "retrievalMetadata": gm.get("retrievalMetadata"),
        "urlContextMetadata": bool(cand.get("urlContextMetadata")),
        "usage": resp.get("usageMetadata"),
    }


def main() -> int:
    keys = {
        label: os.environ.get(var, "").strip()
        for label, var in (("free", "GEMINI_FREE_KEY"), ("paid", "GEMINI_PAID_KEY"))
    }
    keys = {k: v for k, v in keys.items() if v}
    if not keys:
        print("Set GEMINI_FREE_KEY and/or GEMINI_PAID_KEY (never commit them).", file=sys.stderr)
        return 2
    models = [m.strip() for m in os.environ.get(
        "GEMINI_MODELS", "gemini-3.1-flash-lite,gemini-2.5-flash-lite").split(",") if m.strip()]
    cases = [c.strip() for c in os.environ.get(
        "GEMINI_CASES", "plain,search,search+fn,legacy").split(",") if c.strip()]
    ts = datetime.now(timezone.utc).strftime("%Y%m%dT%H%M%SZ")
    out_dir = os.environ.get("OUT_DIR") or os.path.join(
        tempfile.gettempdir(), "yoin-ai-research", f"gemini-{ts}")
    os.makedirs(out_dir, exist_ok=True)

    summary = []
    for label, key in keys.items():
        for model in models:
            for case in cases:
                code, data, raw, dt = call(key, model, body_for(case))
                for secret in keys.values():  # belt and braces: never persist a key
                    raw = raw.replace(secret, "<redacted>")
                stem = f"{label}__{model}__{case.replace('+', '_')}"
                with open(os.path.join(out_dir, stem + ".json"), "w", encoding="utf-8") as f:
                    f.write(raw)
                row = {"key": label, "model": model, "case": case, "http": code,
                       "latency_s": round(dt, 2)}
                if data and "error" in data:
                    row["error"] = summarize_error(data["error"])
                elif data:
                    row["result"] = summarize_grounding(data)
                    html = ((data.get("candidates") or [{}])[0].get("groundingMetadata") or {}) \
                        .get("searchEntryPoint", {}).get("renderedContent")
                    if html:
                        with open(os.path.join(out_dir, stem + ".searchEntryPoint.html"), "w",
                                  encoding="utf-8") as f:
                            f.write(html)
                else:
                    row["raw"] = raw[:300]
                summary.append(row)
                print(json.dumps(row, ensure_ascii=False), flush=True)
                time.sleep(1.0)

    with open(os.path.join(out_dir, "summary.json"), "w", encoding="utf-8") as f:
        json.dump(summary, f, ensure_ascii=False, indent=2)
    print(f"\nRaw responses + summary.json in {out_dir}", file=sys.stderr)
    return 0


if __name__ == "__main__":
    sys.exit(main())
