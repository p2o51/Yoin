#!/usr/bin/env python3
"""
Ask the five questions in questions.json to every search-capable AI provider
whose key is present in the environment, and write a grading sheet.

Providers (each one runs only if its key variable is set; nothing is ever
written into the repo, and keys are never printed):

  gemini      GEMINI_API_KEY        generativelanguage v1beta generateContent + google_search
                GEMINI_MODEL          default gemini-3.1-flash-lite
  zhipu       ZHIPU_API_KEY         open.bigmodel.cn chat/completions + built-in web_search tool
                ZHIPU_MODEL           default glm-4.7-flash
                ZHIPU_ENGINES         default search_std,search_pro,search_pro_sogou,search_pro_quark
                ZHIPU_BASE            default https://open.bigmodel.cn/api/paas/v4 (Z.ai: https://api.z.ai/api/paas/v4)
  qwen        DASHSCOPE_API_KEY     DashScope *native* generation endpoint (the OpenAI-compatible
                                    endpoint cannot return sources) + enable_search
                QWEN_MODEL            default qwen-plus
                QWEN_STRATEGY         default turbo   (turbo | max | agent)
                DASHSCOPE_BASE        default https://dashscope.aliyuncs.com (Singapore: https://dashscope-intl.aliyuncs.com)
  kimi        MOONSHOT_API_KEY      chat/completions with a client-side `web_search` function that
                                    calls POST /v1/tools/search (the $web_search builtin retires 2026-10-20)
                KIMI_MODEL            default kimi-k2.6
                MOONSHOT_BASE         default https://api.moonshot.cn/v1 (international: https://api.moonshot.ai/v1)
  openrouter  OPENROUTER_API_KEY    chat/completions + openrouter:web_search server tool
                OPENROUTER_MODEL      default google/gemini-3.1-flash-lite-preview (check the slug on openrouter.ai/models)
                OPENROUTER_ENGINES    default native,exa
  perplexity  PERPLEXITY_API_KEY    Agent API (Responses format) at /v1/agent with the web_search tool
                PPLX_MODEL            default perplexity/sonar

Request and response shapes for every provider except Gemini come from
documentation read on 2026-10-16 (see the report). They could not be exercised
from the environment the report was written in, so the parsers are
deliberately tolerant and every raw response is saved: if a field moved, fix
the extractor, not the grading.

Output (default $TMPDIR/yoin-ai-research/compare-<ts>/):
  raw/<provider>__<variant>__<qid>.json   every HTTP response body
  results.jsonl                           one normalized row per call
  grading.md                              table to score by hand against questions.json `expect`

Python 3.9+, stdlib only.

  GEMINI_API_KEY=... ZHIPU_API_KEY=... python3 scripts/ai-research/ai_search_compare.py
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
from typing import Callable

HERE = os.path.dirname(os.path.abspath(__file__))
SYSTEM_PROMPT = (
    "You answer questions about music. Search the web before answering. Answer in the "
    "language of the question in at most 120 words. If sources disagree or you cannot "
    "find a fact, say so instead of guessing."
)
SECRETS: list[str] = []


def env(name: str, default: str = "") -> str:
    return os.environ.get(name, default).strip()


def post(url: str, body: dict, headers: dict, timeout: int = 120) -> tuple[int, dict | None, str, float]:
    h = {"Content-Type": "application/json"}
    h.update(headers)
    req = urllib.request.Request(url, data=json.dumps(body).encode(), headers=h, method="POST")
    t0 = time.monotonic()
    try:
        with urllib.request.urlopen(req, timeout=timeout) as r:
            raw, code = r.read().decode(), r.status
    except urllib.error.HTTPError as e:
        raw, code = e.read().decode(errors="replace"), e.code
    except Exception as e:
        return -1, None, f"{type(e).__name__}: {e}", time.monotonic() - t0
    for s in SECRETS:
        raw = raw.replace(s, "<redacted>")
    try:
        return code, json.loads(raw), raw, time.monotonic() - t0
    except json.JSONDecodeError:
        return code, None, raw, time.monotonic() - t0


def err_of(code: int, data: dict | None, raw: str) -> str | None:
    if code == 200 and data is not None:
        return None
    if isinstance(data, dict) and data.get("error"):
        e = data["error"]
        return json.dumps(e, ensure_ascii=False)[:300] if isinstance(e, dict) else str(e)[:300]
    if isinstance(data, dict) and data.get("code"):
        return f"{data.get('code')}: {data.get('message')}"[:300]
    return f"http {code}: {raw[:300]}"


# --------------------------------------------------------------------------- providers
# Each returns (normalized_result, raw_text). normalized_result keys:
#   http, latency_s, answer, sources[{title,url,site}], search_calls, extra, error


def gemini(q: dict, variant: str) -> tuple[dict, str]:
    key, model = env("GEMINI_API_KEY"), env("GEMINI_MODEL", "gemini-3.1-flash-lite")
    body = {
        "system_instruction": {"parts": [{"text": SYSTEM_PROMPT}]},
        "contents": [{"role": "user", "parts": [{"text": q["question"]}]}],
        "tools": [{"google_search": {}}],
    }
    code, data, raw, dt = post(
        f"https://generativelanguage.googleapis.com/v1beta/models/{model}:generateContent",
        body, {"x-goog-api-key": key})
    res = {"http": code, "latency_s": round(dt, 2), "error": err_of(code, data, raw)}
    if not res["error"]:
        cand = (data.get("candidates") or [{}])[0]
        parts = (cand.get("content") or {}).get("parts") or []
        gm = cand.get("groundingMetadata") or {}
        res["answer"] = "".join(p.get("text", "") for p in parts if not p.get("thought"))
        res["sources"] = [{"title": (c.get("web") or {}).get("title"), "url": (c.get("web") or {}).get("uri")}
                          for c in gm.get("groundingChunks") or []]
        res["search_calls"] = len(gm.get("webSearchQueries") or [])
        res["extra"] = {"has_search_entry_point": bool((gm.get("searchEntryPoint") or {}).get("renderedContent")),
                        "supports": len(gm.get("groundingSupports") or []),
                        "usage": data.get("usageMetadata")}
    return res, raw


def zhipu(q: dict, engine: str) -> tuple[dict, str]:
    base = env("ZHIPU_BASE", "https://open.bigmodel.cn/api/paas/v4")
    body = {
        "model": env("ZHIPU_MODEL", "glm-4.7-flash"),
        "messages": [{"role": "system", "content": SYSTEM_PROMPT},
                     {"role": "user", "content": q["question"]}],
        "tools": [{"type": "web_search", "web_search": {
            "enable": True, "search_engine": engine, "search_result": True,
            # search_pro_sogou only accepts 10/20/30/40/50
            "count": 10 if engine == "search_pro_sogou" else 5,
            "content_size": "medium"}}],
        "temperature": 0.3,
    }
    if env("ZHIPU_THINKING", "disabled"):
        body["thinking"] = {"type": env("ZHIPU_THINKING", "disabled")}
    code, data, raw, dt = post(f"{base}/chat/completions", body,
                               {"Authorization": f"Bearer {env('ZHIPU_API_KEY')}"})
    res = {"http": code, "latency_s": round(dt, 2), "error": err_of(code, data, raw)}
    if not res["error"]:
        msg = ((data.get("choices") or [{}])[0].get("message") or {})
        res["answer"] = msg.get("content") or ""
        res["sources"] = [{"title": w.get("title"), "url": w.get("link"), "site": w.get("media"),
                           "refer": w.get("refer")} for w in data.get("web_search") or []]
        res["search_calls"] = 1 if data.get("web_search") else 0
        res["extra"] = {"usage": data.get("usage")}
    return res, raw


def qwen(q: dict, variant: str) -> tuple[dict, str]:
    base = env("DASHSCOPE_BASE", "https://dashscope.aliyuncs.com")
    body = {
        "model": env("QWEN_MODEL", "qwen-plus"),
        "input": {"messages": [{"role": "system", "content": SYSTEM_PROMPT},
                               {"role": "user", "content": q["question"]}]},
        "parameters": {
            "result_format": "message",
            "enable_search": True,
            "search_options": {
                "forced_search": True,
                "search_strategy": env("QWEN_STRATEGY", "turbo"),
                "enable_source": True,
                "enable_citation": True,
                "citation_format": "[<number>]",
            },
        },
    }
    code, data, raw, dt = post(f"{base}/api/v1/services/aigc/text-generation/generation", body,
                               {"Authorization": f"Bearer {env('DASHSCOPE_API_KEY')}"})
    res = {"http": code, "latency_s": round(dt, 2), "error": err_of(code, data, raw)}
    if not res["error"]:
        out = data.get("output") or {}
        msg = ((out.get("choices") or [{}])[0].get("message") or {})
        res["answer"] = msg.get("content") or out.get("text") or ""
        results = ((out.get("search_info") or {}).get("search_results")) or []
        res["sources"] = [{"title": r.get("title"), "url": r.get("url"), "site": r.get("site_name"),
                           "index": r.get("index")} for r in results]
        res["search_calls"] = 1 if results else 0
        res["extra"] = {"usage": data.get("usage")}
    return res, raw


def kimi(q: dict, variant: str) -> tuple[dict, str]:
    base, key = env("MOONSHOT_BASE", "https://api.moonshot.cn/v1"), env("MOONSHOT_API_KEY")
    auth = {"Authorization": f"Bearer {key}"}
    tool = {"type": "function", "function": {
        "name": "web_search",
        "description": "Search the web. Returns titles, URLs and summaries.",
        "parameters": {"type": "object", "properties": {
            "query": {"type": "string", "description": "search query, under 70 characters"}},
            "required": ["query"]}}}
    messages: list[dict] = [{"role": "system", "content": SYSTEM_PROMPT},
                            {"role": "user", "content": q["question"]}]
    raws, sources, calls, t0 = [], [], 0, time.monotonic()
    res: dict = {"error": None}
    for _ in range(4):  # at most 3 searches, then force an answer
        code, data, raw, _ = post(f"{base}/chat/completions", {
            "model": env("KIMI_MODEL", "kimi-k2.6"), "messages": messages,
            "tools": [tool], "temperature": 0.6}, auth)
        raws.append(raw)
        if err_of(code, data, raw):
            res = {"http": code, "error": err_of(code, data, raw)}
            break
        choice = (data.get("choices") or [{}])[0]
        msg = choice.get("message") or {}
        if not msg.get("tool_calls"):
            res = {"http": code, "answer": msg.get("content") or "", "extra": {"usage": data.get("usage")}}
            break
        messages.append(msg)  # echo the assistant turn as-is (keeps reasoning_content if any)
        for tc in msg["tool_calls"]:
            args = json.loads(tc["function"].get("arguments") or "{}")
            scode, sdata, sraw, _ = post(f"{base}/tools/search",
                                         {"text_query": args.get("query", q["question"]), "limit": 5}, auth)
            raws.append(sraw)
            calls += 1
            hits = (sdata or {}).get("search_results") or []
            sources += [{"title": h.get("title"), "url": h.get("url"), "site": h.get("site_name")} for h in hits]
            messages.append({"role": "tool", "tool_call_id": tc["id"], "name": "web_search",
                             "content": json.dumps(hits if scode == 200 else {"error": sraw[:300]},
                                                   ensure_ascii=False)})
    res.update({"latency_s": round(time.monotonic() - t0, 2), "sources": sources, "search_calls": calls})
    return res, "\n---\n".join(raws)


def openrouter(q: dict, engine: str) -> tuple[dict, str]:
    body = {
        "model": env("OPENROUTER_MODEL", "google/gemini-3.1-flash-lite-preview"),
        "messages": [{"role": "system", "content": SYSTEM_PROMPT},
                     {"role": "user", "content": q["question"]}],
        "tools": [{"type": "openrouter:web_search", "parameters": {"engine": engine, "max_results": 5}}],
    }
    code, data, raw, dt = post("https://openrouter.ai/api/v1/chat/completions", body, {
        "Authorization": f"Bearer {env('OPENROUTER_API_KEY')}",
        "HTTP-Referer": "https://github.com/p2o51/yoin", "X-Title": "Yoin research"})
    res = {"http": code, "latency_s": round(dt, 2), "error": err_of(code, data, raw)}
    if not res["error"]:
        msg = ((data.get("choices") or [{}])[0].get("message") or {})
        res["answer"] = msg.get("content") or ""
        anns = [a.get("url_citation") or a for a in msg.get("annotations") or []
                if a.get("type") == "url_citation"]
        res["sources"] = [{"title": a.get("title"), "url": a.get("url")} for a in anns]
        res["search_calls"] = None  # see usage.server_tool_use if present
        res["extra"] = {"usage": data.get("usage"),
                        "passes_google_search_entry_point": "searchEntryPoint" in raw}
    return res, raw


def perplexity(q: dict, variant: str) -> tuple[dict, str]:
    body = {"model": env("PPLX_MODEL", "perplexity/sonar"),
            "instructions": SYSTEM_PROMPT, "input": q["question"],
            "tools": [{"type": "web_search"}]}
    code, data, raw, dt = post("https://api.perplexity.ai/v1/agent", body,
                               {"Authorization": f"Bearer {env('PERPLEXITY_API_KEY')}"})
    res = {"http": code, "latency_s": round(dt, 2), "error": err_of(code, data, raw)}
    if not res["error"]:
        answer, sources = data.get("output_text") or "", []
        for item in data.get("output") or []:
            if item.get("type") == "message" and not answer:
                answer = "".join(c.get("text", "") for c in item.get("content") or [])
            if item.get("type") in ("search_results", "web_search_call"):
                for r in item.get("results") or item.get("search_results") or []:
                    sources.append({"title": r.get("title"), "url": r.get("url"), "date": r.get("date")})
        res.update({"answer": answer, "sources": sources, "search_calls": None,
                    "extra": {"usage": data.get("usage")}})
    return res, raw


PROVIDERS: list[tuple[str, str, Callable[[dict, str], tuple[dict, str]], list[str]]] = [
    ("gemini", "GEMINI_API_KEY", gemini, ["google_search"]),
    ("zhipu", "ZHIPU_API_KEY", zhipu,
     env("ZHIPU_ENGINES", "search_std,search_pro,search_pro_sogou,search_pro_quark").split(",")),
    ("qwen", "DASHSCOPE_API_KEY", qwen, [env("QWEN_STRATEGY", "turbo")]),
    ("kimi", "MOONSHOT_API_KEY", kimi, ["tools/search"]),
    ("openrouter", "OPENROUTER_API_KEY", openrouter, env("OPENROUTER_ENGINES", "native,exa").split(",")),
    ("perplexity", "PERPLEXITY_API_KEY", perplexity, ["agent/web_search"]),
]


def host(url: str | None) -> str:
    try:
        return urllib.parse.urlparse(url or "").netloc
    except ValueError:
        return "?"


def main() -> int:
    with open(os.path.join(HERE, "questions.json"), encoding="utf-8") as f:
        questions = json.load(f)["questions"]
    active = [(n, v, fn, variants) for n, v, fn, variants in PROVIDERS if env(v)]
    if not active:
        print("No provider key set. See the docstring for variable names.", file=sys.stderr)
        return 2
    SECRETS.extend(env(v) for _, v, _, _ in active)
    only = set(filter(None, env("ONLY_QUESTIONS").split(",")))

    ts = datetime.now(timezone.utc).strftime("%Y%m%dT%H%M%SZ")
    out_dir = env("OUT_DIR") or os.path.join(tempfile.gettempdir(), "yoin-ai-research", f"compare-{ts}")
    os.makedirs(os.path.join(out_dir, "raw"), exist_ok=True)
    rows = []
    with open(os.path.join(out_dir, "results.jsonl"), "w", encoding="utf-8") as jl:
        for name, _, fn, variants in active:
            for variant in [v.strip() for v in variants if v.strip()]:
                for q in questions:
                    if only and q["id"] not in only:
                        continue
                    try:
                        res, raw = fn(q, variant)
                    except Exception as e:  # keep going; a broken parser must not stop the run
                        res, raw = {"error": f"{type(e).__name__}: {e}"}, ""
                    safe_variant = variant.replace("/", "_")
                    with open(os.path.join(out_dir, "raw", f"{name}__{safe_variant}__{q['id']}.json"),
                              "w", encoding="utf-8") as f:
                        f.write(raw)
                    row = {"provider": name, "variant": variant, "qid": q["id"], **res}
                    rows.append(row)
                    jl.write(json.dumps(row, ensure_ascii=False) + "\n")
                    print(f"{name:10} {variant:18} {q['id']:20} http={row.get('http')} "
                          f"{row.get('latency_s')}s sources={len(row.get('sources') or [])} "
                          f"{'ERR ' + row['error'][:80] if row.get('error') else ''}", flush=True)
                    time.sleep(1.2)  # stays under every free-tier RPM we know of

    with open(os.path.join(out_dir, "grading.md"), "w", encoding="utf-8") as md:
        md.write("| provider | variant | q | latency | sources (hosts) | answer | facts ✓/✗ | hallucination? |\n")
        md.write("|---|---|---|---|---|---|---|---|\n")
        for r in rows:
            hosts = ", ".join(sorted({host(s.get("url")) for s in r.get("sources") or []}))[:120]
            ans = (r.get("answer") or r.get("error") or "").replace("\n", " ").replace("|", "\\|")[:300]
            md.write(f"| {r['provider']} | {r['variant']} | {r['qid']} | {r.get('latency_s')} | "
                     f"{len(r.get('sources') or [])} ({hosts}) | {ans} |  |  |\n")
    print(f"\nresults.jsonl + grading.md in {out_dir}", file=sys.stderr)
    return 0


if __name__ == "__main__":
    sys.exit(main())
