#!/bin/zsh
# Pre-install QA driver for Yoin iOS (see docs/qa-script.md).
#
# Launches every scripted state with launch arguments, in light and dark,
# takes a still and/or records a clip, and cuts the clip into frame strips so
# flashes, snaps and pop-ins are visible frame by frame.
#
# Usage:
#   scripts/qa-record.sh [-d <udid>] [-o <outdir>] [-a <app>] [-m light|dark|both]
#                        [-f <fps>] [-s <animationSpeed>] [case-filter ...]
#
#   -d  simulator udid (default: creates a throwaway "yoin-qa" iPhone 17 and
#       deletes it at the end; never point this at a simulator another session uses)
#   -o  output dir (default: build/qa/<timestamp>)
#   -a  Yoin.app (default: build/dd/Build/Products/Debug-iphonesimulator/Yoin.app)
#   -m  appearance(s) (default both)
#   -f  frame-strip fps (default 30)
#   -s  -animationSpeed for recorded cases (default 0.5; stills use 1)
#   case-filter: only run cases whose name contains one of these words
#
# Real-data cases (`srv-*`) need the demo Subsonic on :4995:
#   python3 ~/Developer/Yoin-archive/promo-demo-2026-11-02/demo_subsonic.py 4995
# They are skipped when nothing answers on that port. Audio is always silent.
#
# Output: <out>/<mode>/<case>.png (still), <case>.mp4 (clip),
#         <case>-strip.png (contact sheet of the clip's frames, 6 per row),
#         <case>-frames/NNNN.png, plus index.txt with the launch line per case.
set -e
export DEVELOPER_DIR=${DEVELOPER_DIR:-/Applications/Xcode.app/Contents/Developer}
cd "$(dirname "$0")/.."
FFMPEG=${FFMPEG:-$(command -v ffmpeg || echo /opt/homebrew/bin/ffmpeg)}
UDID=""; OUT=""; APP=build/dd/Build/Products/Debug-iphonesimulator/Yoin.app
MODES=(light dark); FPS=30; SPEED=0.5; OWN_SIM=0
while getopts "d:o:a:m:f:s:" opt; do
  case $opt in
    d) UDID=$OPTARG ;; o) OUT=$OPTARG ;; a) APP=$OPTARG ;;
    m) [[ $OPTARG == both ]] || MODES=($OPTARG) ;; f) FPS=$OPTARG ;; s) SPEED=$OPTARG ;;
  esac
done
shift $((OPTIND - 1)); FILTERS=("$@")
OUT=${OUT:-build/qa/$(date +%Y%m%d-%H%M%S)}
mkdir -p "$OUT"

if [[ -z $UDID ]]; then
  RT=$(xcrun simctl list runtimes | grep -o 'com.apple.CoreSimulator.SimRuntime.iOS-[0-9-]*' | tail -1)
  UDID=$(xcrun simctl create yoin-qa "iPhone 17" "$RT"); OWN_SIM=1
fi
cleanup() {
  xcrun simctl terminate "$UDID" com.gpo.yoin 2>/dev/null || true
  if (( OWN_SIM )); then xcrun simctl shutdown "$UDID" 2>/dev/null; xcrun simctl delete "$UDID"; fi
}
trap cleanup EXIT
xcrun simctl boot "$UDID" 2>/dev/null || true
xcrun simctl bootstatus "$UDID" -b >/dev/null 2>&1 || sleep 8
xcrun simctl status_bar "$UDID" override --time 9:41 --batteryState charged --batteryLevel 100 --wifiBars 3 --cellularBars 4
xcrun simctl install "$UDID" "$APP"

SRV="-subsonicURL http://127.0.0.1:4995 -subsonicUser demo -subsonicPass demo"
SERVER_UP=0; curl -s -m 2 "http://127.0.0.1:4995/rest/ping.view?u=demo&p=demo&v=1.16.1&c=qa" >/dev/null && SERVER_UP=1

# name | kind (still | still:<wait s> | rec:<seconds>) | launch args.  Every launch gets
# -silentAudio YES. Keep this table in step with docs/qa-script.md.
CASES=(
  "home|still|-screen home"
  "home-idle|still|-screen idle"
  "home-scroll-end|still|-screen home-bottom"
  "library|still|-screen library"
  "library-albums|still|-screen library-albums"
  "library-songs|still|-screen library-songs"
  "library-artists|still|-screen library-artists"
  "library-playlists|still|-screen library-playlists"
  "library-favorites|still|-screen library-favorites"
  "library-scroller|still|-screen library-scroller"
  "library-idle|still|-screen library-idle"
  "search|still|-screen search -searchFocused YES"
  "search-results|still|-screen search-results"
  "search-empty|still|-screen search-empty-results"
  "search-longpress|rec:5|-screen search-longpress"
  "search-from-bar|rec:5|-screen home-search"
  "search-bar-close|rec:9|-screen home-search -searchAutoClose YES"
  "album|still|-screen album"
  "album-list|still|-screen album-list -listScroll 160"
  "album-pullup-mid|still|-screen album-mid"
  "album-page-mid|still|-screen album -pageFrac 0.5"
  "album-page-held|still|-screen album-scrapbook -pageHold 1,0.75"
  "album-scrapbook|still|-screen album-scrapbook"
  "album-listscroll|rec:7|-screen album-list -listAutoScroll YES"
  "artist|still|-screen artist"
  "playlist|still|-screen playlist"
  "settings|still|-screen settings"
  "landing|still|-screen landing"
  "np|still|-screen np"
  "np-short|still|-screen np-short"
  "np-touch|still|-screen np-touch"
  "np-airplay|still|-screen np-airplay"
  "np-panel|still|-screen np-panel"
  "np-about-full|rec:8|-screen np -npTab about-full"
  "np-note-full|rec:8|-screen np -npTab note-full"
  "np-lyrics-full|rec:8|-screen np -npTab lyrics-full"
  "np-about-tab|still:6|-screen np -npTab about"
  "np-note-tab|still:6|-screen np -npTab note"
  "np-focus|rec:8|-screen np -npFocus YES"
  "np-short-focus|still:6|-screen np-short -npShortFocus YES"
  "np-compose|still:7|-screen np -npCompose Bass_walks_up"
  "np-queue|still|-screen np-queue"
  "song-menu|still|-screen song-menu"
  "np-open-close|rec:11|-screen home -npAutoCycle YES"
  "np-drag-dismiss|rec:11|-screen home -npScriptedDrag YES"
  "np-morph-slow|rec:9|-screen np-morph"
  "np-focus-demo|rec:8|-screen np -npFocusDemo YES"
  "np-about-cycle|rec:16|-screen home -npAutoCycle YES -npAutoCycleHold 5 -npTab about-full"
  "srv-home|still|$SRV -historySeed demo"
  "srv-np|still|$SRV -serverPlay album:al-field -serverOpenNowPlaying YES"
  "srv-album|still|$SRV -serverRoute album:al-field"
)

match() {
  (( ${#FILTERS} == 0 )) && return 0
  for f in $FILTERS; do [[ $1 == *$f* ]] && return 0; done
  return 1
}

for mode in $MODES; do
  xcrun simctl ui "$UDID" appearance "$mode"
  mkdir -p "$OUT/$mode"
  for c in $CASES; do
    name=${c%%|*}; rest=${c#*|}; kind=${rest%%|*}; args=${rest#*|}
    match "$name" || continue
    if [[ $name == srv-* ]] && (( ! SERVER_UP )); then echo "skip $name (no demo server)"; continue; fi
    xcrun simctl terminate "$UDID" com.gpo.yoin 2>/dev/null || true
    DATA=$(xcrun simctl get_app_container "$UDID" com.gpo.yoin data)
    rm -rf "$DATA/Library/Saved Application State" "$DATA/Library/SplashBoard"
    base="$OUT/$mode/$name"
    echo "$mode/$name: $args" >> "$OUT/index.txt"
    if [[ $kind == still* ]]; then
      # still:N waits N s (the NP stage launch aids fire 4 s after launch).
      wait_s=3; [[ $kind == still:* ]] && wait_s=${kind#still:}; [[ $name == srv-* ]] && wait_s=8
      xcrun simctl launch "$UDID" com.gpo.yoin ${=args} -silentAudio YES >/dev/null
      sleep $wait_s
      xcrun simctl io "$UDID" screenshot --type=png "$base.png" >/dev/null 2>&1
      echo "$base.png"
    else
      secs=${kind#rec:}
      xcrun simctl io "$UDID" recordVideo --codec h264 --force "$base.mp4" >/dev/null 2>&1 &
      rec=$!; sleep 1
      xcrun simctl launch "$UDID" com.gpo.yoin ${=args} -silentAudio YES -animationSpeed "$SPEED" >/dev/null
      sleep "$secs"
      kill -INT $rec; wait $rec 2>/dev/null || true
      rm -rf "$base-frames"; mkdir -p "$base-frames"
      "$FFMPEG" -loglevel error -i "$base.mp4" -vf "fps=$FPS,scale=320:-1" "$base-frames/%04d.png"
      "$FFMPEG" -loglevel error -y -i "$base.mp4" -vf "fps=$((FPS / 3)),scale=200:-1,tile=6x0:padding=4:color=gray" -frames:v 1 "$base-strip.png" 2>/dev/null \
        || "$FFMPEG" -loglevel error -y -i "$base.mp4" -vf "fps=$((FPS / 3)),scale=200:-1,tile=6x12:padding=4:color=gray" "$base-strip-%02d.png"
      echo "$base.mp4 ($(ls "$base-frames" | wc -l | tr -d ' ') frames)"
    fi
  done
done
xcrun simctl ui "$UDID" appearance light
echo "done: $OUT"
