#!/usr/bin/env bash
# Flags user-visible string literals left under app/src/main/java/.../ui/.
# Exits 0 when nothing remains. Exits 1 and prints file:line hits otherwise.
#
# Skips @Preview bodies, Log calls, throw/require/check/error messages,
# lines marked i18n-allow, and ui/memories/copy/ (handwritten bilingual prose).
set -euo pipefail

root="$(cd "$(dirname "$0")/.." && pwd)"
exec python3 - "$root" <<'PY'
import re
import sys
from pathlib import Path

root = Path(sys.argv[1])
ui = root / "app/src/main/java/com/gpo/yoin/ui"
skip_dir = ui / "memories/copy"

string_re = re.compile(r'"(?:\\.|[^"\\])*"')
cjk_re = re.compile(r"[\u3400-\u9fff]")
preview_ann = re.compile(r"@Preview\b")
fun_re = re.compile(r"\bfun\s+")
log_re = re.compile(r"\bLog\.[vdiwewtf]\s*\(")
internal_re = re.compile(
    r"\b(require|requireNotNull|check|checkNotNull|error|throw|TODO|println)\s*\("
)
ui_hint = re.compile(
    r"("
    r"\bText\s*\(|\bBasicText\s*\(|\bAnnotatedString\s*\("
    r"|contentDescription\s*="
    r"|\btext\s*=|\blabel\s*=|\btitle\s*=|\bplaceholder\s*="
    r"|\bsupportingText\s*=|\bheadline\s*=|\bmessage\s*="
    r"|\bconfirmText\s*=|\bdismissText\s*=|\bactionLabel\s*="
    r"|\bhints\s*=|\bdescription\s*="
    r")"
)

def strip_comment_keep_strings(line: str) -> str:
    """Drop // comments that are not inside a string. Block comments are handled by the caller."""
    out = []
    i = 0
    in_str = False
    esc = False
    while i < len(line):
        c = line[i]
        if in_str:
            out.append(c)
            if esc:
                esc = False
            elif c == "\\":
                esc = True
            elif c == '"':
                in_str = False
            i += 1
            continue
        if c == '"':
            in_str = True
            out.append(c)
            i += 1
            continue
        if c == "/" and i + 1 < len(line) and line[i + 1] == "/":
            break
        out.append(c)
        i += 1
    return "".join(out)

hits = []
for path in sorted(ui.rglob("*.kt")):
    if skip_dir in path.parents or path.parent == skip_dir:
        continue
    text = path.read_text(encoding="utf-8")
    lines = text.splitlines()
    in_block = False
    preview_depth = None
    brace = 0
    pending_preview = False
    for n, raw in enumerate(lines, 1):
        # Block comments can span lines. Strings are not expected inside them.
        code = raw
        if in_block:
            if "*/" in code:
                code = code.split("*/", 1)[1]
                in_block = False
            else:
                continue
        while "/*" in code:
            pre, _, rest = code.partition("/*")
            if "*/" in rest:
                code = pre + rest.split("*/", 1)[1]
            else:
                code = pre
                in_block = True
                break
        stripped = strip_comment_keep_strings(code)
        if "i18n-allow" in raw:
            # Still track braces so preview ranges stay aligned.
            pass
        if preview_ann.search(stripped):
            pending_preview = True
        opens = stripped.count("{")
        closes = stripped.count("}")
        if pending_preview and fun_re.search(stripped) and "{" in stripped:
            preview_depth = brace
            pending_preview = False
        in_preview = preview_depth is not None
        brace += opens - closes
        if preview_depth is not None and brace <= preview_depth:
            preview_depth = None
        if in_preview or "i18n-allow" in raw:
            continue
        if log_re.search(stripped) or internal_re.search(stripped):
            continue
        if not ui_hint.search(stripped) and not cjk_re.search(stripped):
            continue
        for m in string_re.finditer(stripped):
            lit = m.group(0)
            body = lit[1:-1]
            if not body or body in {"·", "—", "•", "-", "–", "…"}:
                continue
            if body.startswith("@") or body.startswith("http"):
                continue
            has_cjk = cjk_re.search(body) is not None
            has_letter = any(ch.isalpha() for ch in body)
            if not has_letter and not has_cjk:
                continue
            # Format-only or a single format token is not a sentence.
            if re.fullmatch(r"%[0-9$]*[sdif]|%d:%02d|%.1f", body):
                continue
            # UI parameter on this line, or any CJK literal outside the skips above.
            if ui_hint.search(stripped) or has_cjk:
                rel = path.relative_to(root)
                hits.append(f"{rel}:{n}: {lit}")

if hits:
    print(f"{len(hits)} hardcoded UI string(s):")
    print("\n".join(hits))
    sys.exit(1)
print("0 hardcoded UI strings")
PY
