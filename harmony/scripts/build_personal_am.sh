#!/usr/bin/env bash
# Personal HarmonyOS build with Apple Music playback (docs/applemusic.md, "Personal builds").
#
# The repository never holds Apple's libraries, the native player that links them, or its ArkTS
# module. They live in a local stash outside git; this script copies them into the tree for the
# length of ONE release build with --dart-define=YOIN_AM_PLAYBACK=true, keeps the HAP outside the
# repository, and puts the tree back so `git status` is clean again. The HAP it makes is for the
# owner's own devices only: never upload, share or publish it.
#
# Usage (from anywhere):
#   harmony/scripts/build_personal_am.sh populate   # fill the stash from the spike worktree (once)
#   harmony/scripts/build_personal_am.sh build      # the personal build (default command)
#   harmony/scripts/build_personal_am.sh check-hap <file.hap>   # does a HAP carry personal-only libs?
#
# Settings (environment):
#   YOIN_AM_HOME     the stash                    (default ~/.yoin/harmony-am)
#   YOIN_AM_SPIKE    the spike worktree populate reads, read-only (default ~/Developer/Yoin-wt-pc-am)
#   YOIN_AM_SIGNING  a build-profile.json5 whose signingConfigs are machine-local; its block is
#                    injected for the build and never printed (default $YOIN_AM_HOME/signing/build-profile.json5;
#                    absent = the build is signed however the tree already is)
#
# The stash:
#   libs/arm64-v8a/*.so          Apple's patched pair, libam.so, libam_bridge.so, the NAPI module, libc++_shared.so
#   ets/*.ets                    the player module, copied to entry/src/main/ets/yoin/personal/
#   PersonalPlayers.ets          replaces entry/src/main/ets/yoin/playback/PersonalPlayers.ets (the stub)
#   types/libmusickit_probe_napi the NAPI module's types package (entry/src/main/cpp/types/)
#   native-src/                  the spike's C++ (bridge, NAPI, shim, CMake), to rebuild the .so files
#   signing/build-profile.json5  machine-local signing (mode 600)
#   out/                         the personal HAPs this script made
set -euo pipefail

HARMONY="$(cd "$(dirname "$0")/.." && pwd)"
REPO="$(git -C "$HARMONY" rev-parse --show-toplevel)"
OHOS="$HARMONY/ohos"
ENTRY="$OHOS/entry"
AM_HOME="${YOIN_AM_HOME:-$HOME/.yoin/harmony-am}"
SPIKE="${YOIN_AM_SPIKE:-$HOME/Developer/Yoin-wt-pc-am}"
SIGNING="${YOIN_AM_SIGNING:-$AM_HOME/signing/build-profile.json5}"

# What the build copies in, and where (relative to the repository root, for git).
LIBS=(libam.so libam_bridge.so libappleMusicSDK.so libc++_ndk1.so libc++_shared.so libmusickit_probe_napi.so)
PERSONAL_ETS_DIR="$ENTRY/src/main/ets/yoin/personal"
TYPES_DIR="$ENTRY/src/main/cpp/types/libmusickit_probe_napi"
STUB="$ENTRY/src/main/ets/yoin/playback/PersonalPlayers.ets"
# Tracked files the build changes for a moment; each must be unchanged before it starts.
TOUCHED=("$STUB" "$ENTRY/oh-package.json5" "$ENTRY/build-profile.json5" "$OHOS/build-profile.json5")

die() { echo "build_personal_am: $*" >&2; exit 1; }
say() { echo "build_personal_am: $*"; }

# ---- populate -------------------------------------------------------------------------------------

populate() {
  [ -d "$SPIKE/harmony" ] || die "no spike worktree at $SPIKE (set YOIN_AM_SPIKE)"
  umask 077
  mkdir -p "$AM_HOME"/{libs/arm64-v8a,ets,types,native-src,signing,out}
  chmod 700 "$AM_HOME"
  local src="$SPIKE/harmony/ohos/entry/libs/arm64-v8a"
  for lib in "${LIBS[@]}"; do
    [ -f "$src/$lib" ] || die "the spike worktree has no $lib in entry/libs/arm64-v8a"
    cp -p "$src/$lib" "$AM_HOME/libs/arm64-v8a/$lib"
  done
  say "libraries: ${#LIBS[@]} copied"
  rm -rf "$AM_HOME/types/libmusickit_probe_napi"
  cp -R "$SPIKE/harmony/ohos/entry/src/main/cpp/types/libmusickit_probe_napi" "$AM_HOME/types/"
  rsync -a --delete --exclude build/ --exclude out/ --exclude '*.so' \
    "$SPIKE/harmony/spikes/applemusic/" "$AM_HOME/native-src/"
  say "types package and native sources copied"
  # The ArkTS module is the stash's own copy (adapted for main); only a missing one comes from the spike.
  for f in AmPlayer.ets AmPhase1.ets; do
    if [ ! -f "$AM_HOME/ets/$f" ]; then
      cp "$SPIKE/harmony/ohos/entry/src/main/ets/yoin/spike/$f" "$AM_HOME/ets/$f"
      say "ets/$f copied from the spike: adapt it as docs/applemusic.md \"Personal builds\" says"
    fi
  done
  [ -f "$AM_HOME/PersonalPlayers.ets" ] || say "no PersonalPlayers.ets in the stash: write it (docs/applemusic.md)"
  # Signing: the spike worktree's machine-local build-profile.json5 (never printed).
  local profile="$SPIKE/harmony/ohos/build-profile.json5"
  if [ ! -f "$AM_HOME/signing/build-profile.json5" ] && has_signing "$profile"; then
    cp "$profile" "$AM_HOME/signing/build-profile.json5"
    chmod 600 "$AM_HOME/signing/build-profile.json5"
    say "signing: copied from the spike worktree (contents not shown)"
  fi
  say "stash ready at $AM_HOME"
}

# ---- JSON5 edits (text level, string-aware; nothing is printed) ---------------------------------

has_signing() {
  [ -f "$1" ] || return 1
  python3 -I - "$1" <<'PY'
import re, sys
text = open(sys.argv[1], encoding='utf-8').read()
m = re.search(r'["\']?signingConfigs["\']?\s*:\s*\[\s*\]', text)
sys.exit(1 if m or 'signingConfigs' not in text else 0)
PY
}

inject_signing() { # <local profile> <repo profile>
  python3 -I - "$1" "$2" <<'PY'
import re, sys

def array_span(text):
    m = re.search(r'["\']?signingConfigs["\']?\s*:\s*\[', text)
    if not m:
        raise SystemExit('signingConfigs not found')
    i, depth, quote = m.end() - 1, 0, None
    while i < len(text):
        c = text[i]
        if quote:
            if c == '\\':
                i += 1
            elif c == quote:
                quote = None
        elif c in '"\'':
            quote = c
        elif c == '[':
            depth += 1
        elif c == ']':
            depth -= 1
            if depth == 0:
                return m.end() - 1, i + 1
        i += 1
    raise SystemExit('signingConfigs is not closed')

local = open(sys.argv[1], encoding='utf-8').read()
repo = open(sys.argv[2], encoding='utf-8').read()
ls, le = array_span(local)
rs, re_ = array_span(repo)
open(sys.argv[2], 'w', encoding='utf-8').write(repo[:rs] + local[ls:le] + repo[re_:])
PY
}

add_types_dependency() { # entry/oh-package.json5
  python3 -I - "$1" <<'PY'
import re, sys
p = sys.argv[1]
text = open(p, encoding='utf-8').read()
m = re.search(r'"dependencies"\s*:\s*\{', text)
if not m:
    raise SystemExit('no dependencies block in entry/oh-package.json5')
dep = '"libmusickit_probe_napi.so": "file:./src/main/cpp/types/libmusickit_probe_napi",'
open(p, 'w', encoding='utf-8').write(text[:m.end()] + '\n    ' + dep + text[m.end():])
PY
}

keep_native_symbols() { # entry/build-profile.json5: pack the patched Apple pair byte-identical (no llvm-strip)
  python3 -I - "$1" <<'PY'
import re, sys
p = sys.argv[1]
text = open(p, encoding='utf-8').read()
m = re.search(r'"buildOption"\s*:\s*\{', text)
if not m:
    raise SystemExit('no buildOption block in entry/build-profile.json5')
block = '\n    "nativeLib": { "debugSymbol": { "strip": false } },'
open(p, 'w', encoding='utf-8').write(text[:m.end()] + block + text[m.end():])
PY
}

# ---- check-hap ------------------------------------------------------------------------------------

personal_libs_in() { # prints the personal-only libraries a HAP carries
  unzip -Z1 "$1" | grep -E '^libs/[^/]+/(libappleMusicSDK|libc\+\+_ndk1|libam|libam_bridge|libmusickit_probe_napi)\.so$' || true
}

check_hap() {
  [ -f "${1:-}" ] || die "usage: build_personal_am.sh check-hap <file.hap>"
  local found
  found="$(personal_libs_in "$1")"
  if [ -n "$found" ]; then
    echo "PERSONAL (Apple Music playback; never distribute):"; echo "$found"
  else
    echo "clean: no personal-only libraries"
  fi
}

# ---- build ----------------------------------------------------------------------------------------

WORK=""

restore() {
  local status=$?
  set +e
  if [ -n "$WORK" ] && [ -d "$WORK/backup" ]; then
    local i=0
    for f in "${TOUCHED[@]}"; do
      [ -f "$WORK/backup/$i" ] && cp "$WORK/backup/$i" "$f"
      i=$((i + 1))
    done
  fi
  rm -rf "$PERSONAL_ETS_DIR" "$TYPES_DIR"
  for lib in "${LIBS[@]}"; do rm -f "$ENTRY/libs/arm64-v8a/$lib"; done
  # Flutter's libapp.so of this build carries the switch: the next build writes its own.
  rm -f "$ENTRY/libs/arm64-v8a/libapp.so"
  rmdir "$ENTRY/libs/arm64-v8a" "$ENTRY/libs" 2>/dev/null
  # hvigor's intermediates and the default output hold the personal libraries; the HAP was copied out.
  rm -rf "$ENTRY/build" "$HARMONY/build/ohos/hap"
  [ -n "$WORK" ] && rm -rf "$WORK"
  if ! git -C "$REPO" diff --quiet -- "${TOUCHED[@]}"; then
    echo "build_personal_am: WARNING: a tracked file was not restored; check git status" >&2
    status=1
  fi
  local dirty
  dirty="$(git -C "$REPO" status --porcelain -- "$HARMONY")"
  if [ -n "$dirty" ]; then
    echo "build_personal_am: note: harmony/ has other changes (not from this script):" >&2
    echo "$dirty" >&2
  else
    say "tree restored: git status is clean"
  fi
  exit $status
}

build() {
  local stash_libs="$AM_HOME/libs/arm64-v8a"
  for lib in "${LIBS[@]}"; do
    [ -f "$stash_libs/$lib" ] || die "the stash has no $lib (run: $0 populate)"
  done
  [ -f "$AM_HOME/PersonalPlayers.ets" ] || die "the stash has no PersonalPlayers.ets"
  ls "$AM_HOME"/ets/*.ets >/dev/null 2>&1 || die "the stash has no ets/*.ets"
  [ -f "$AM_HOME/types/libmusickit_probe_napi/index.d.ts" ] || die "the stash has no types package"
  git -C "$REPO" diff --quiet HEAD -- "${TOUCHED[@]}" ||
    die "these must be unchanged before a personal build: ${TOUCHED[*]#$REPO/}"
  for lib in "${LIBS[@]}"; do
    [ ! -e "$ENTRY/libs/arm64-v8a/$lib" ] || die "entry/libs already holds $lib: remove it first"
  done
  [ ! -e "$PERSONAL_ETS_DIR" ] || die "$PERSONAL_ETS_DIR already exists: remove it first"

  WORK="$(mktemp -d "${TMPDIR:-/tmp}/yoin-am-build.XXXXXX")"
  trap restore EXIT
  trap 'exit 130' INT TERM
  mkdir -p "$WORK/backup"
  local i=0
  for f in "${TOUCHED[@]}"; do cp "$f" "$WORK/backup/$i"; i=$((i + 1)); done

  # The overlay.
  mkdir -p "$ENTRY/libs/arm64-v8a" "$PERSONAL_ETS_DIR" "$(dirname "$TYPES_DIR")"
  for lib in "${LIBS[@]}"; do cp -p "$stash_libs/$lib" "$ENTRY/libs/arm64-v8a/$lib"; done
  cp "$AM_HOME"/ets/*.ets "$PERSONAL_ETS_DIR/"
  cp "$AM_HOME/PersonalPlayers.ets" "$STUB"
  cp -R "$AM_HOME/types/libmusickit_probe_napi" "$TYPES_DIR"
  add_types_dependency "$ENTRY/oh-package.json5"
  keep_native_symbols "$ENTRY/build-profile.json5"
  if [ -f "$SIGNING" ]; then
    has_signing "$SIGNING" || die "$SIGNING has no signingConfigs"
    inject_signing "$SIGNING" "$OHOS/build-profile.json5"
    say "signing: local signingConfigs applied (contents not shown)"
  else
    say "signing: none in $SIGNING; the tree's own signing applies"
  fi

  # shellcheck disable=SC1091
  source "$HOME/Developer/flutter_ohos/ohos-env.sh"
  cd "$HARMONY"
  # Cloud sync's Google Desktop client (harmony/docs/cloud-sync.md), kept beside the stash, never in git.
  local oauth_args=()
  if [ -f "$AM_HOME/google_oauth.local.json" ]; then
    oauth_args=(--dart-define-from-file="$AM_HOME/google_oauth.local.json")
    say "cloud sync: Google client from $AM_HOME/google_oauth.local.json (contents not shown)"
  else
    say "cloud sync: no $AM_HOME/google_oauth.local.json; this build says cloud sync isn't available"
  fi
  say "flutter build hap --release --dart-define=YOIN_AM_PLAYBACK=true"
  YOIN_PERSONAL_AM_BUILD=1 flutter build hap --release --dart-define=YOIN_AM_PLAYBACK=true "${oauth_args[@]}"

  local hap
  hap="$(ls -t "$HARMONY"/build/ohos/hap/*.hap 2>/dev/null | head -1)"
  [ -n "$hap" ] || die "the build made no HAP under build/ohos/hap"
  [ -n "$(personal_libs_in "$hap")" ] || die "the HAP carries no Apple Music player: overlay not packed"
  mkdir -p "$AM_HOME/out"
  local out
  out="$AM_HOME/out/yoin-personal-am-$(date +%Y%m%d-%H%M%S)-$(basename "$hap")"
  cp "$hap" "$out"
  chmod 600 "$out"
  say "personal HAP (owner's devices only, never distribute): $out"
}

case "${1:-build}" in
  populate) populate ;;
  build) build ;;
  check-hap) check_hap "${2:-}" ;;
  -h|--help|help) sed -n '2,32p' "$0" ;;
  *) die "unknown command: $1 (populate | build | check-hap <hap>)" ;;
esac
