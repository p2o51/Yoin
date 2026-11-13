#!/usr/bin/env bash
# Yoin HarmonyOS device helper. Usage:
#   scripts/device.sh install            # release build + install + launch
#   scripts/device.sh launch             # force-stop + launch
#   scripts/device.sh shot <name>        # screenshot to $SHOTS/<name>.jpeg (+ a 1000px copy)
#   scripts/device.sh tap <x> <y>        # tap at device pixels
#   scripts/device.sh swipe <x1> <y1> <x2> <y2> [ms]
#   scripts/device.sh back               # system back
#   scripts/device.sh log [pattern]      # app log lines (pid-filtered)
#   scripts/device.sh demo               # reverse-forward the demo Subsonic server (4995)
# DEVICE defaults to the Mate 70 Air; MateBook Pro S is 8VM0226725000812.
set -euo pipefail
cd "$(dirname "$0")/.."
source "$HOME/Developer/flutter_ohos/ohos-env.sh"
DEVICE="${DEVICE:-5ZGYD25B20051376}"
BUNDLE=com.gpo.yoin
SHOTS="${SHOTS:-/tmp/yoin-harmony-shots}"
h() { hdc -t "$DEVICE" "$@"; }
case "${1:-}" in
  install)
    flutter build hap --release
    h install -r build/ohos/hap/entry-default-signed.hap
    h shell aa force-stop "$BUNDLE" >/dev/null || true
    h shell aa start -a EntryAbility -b "$BUNDLE" ;;
  launch)
    h shell aa force-stop "$BUNDLE" >/dev/null || true
    h shell aa start -a EntryAbility -b "$BUNDLE" ;;
  shot)
    mkdir -p "$SHOTS"; name="${2:-shot}"
    h shell snapshot_display -f "/data/local/tmp/$name.jpeg" >/dev/null
    h file recv "/data/local/tmp/$name.jpeg" "$SHOTS/$name.jpeg" >/dev/null
    sips -Z 1000 "$SHOTS/$name.jpeg" --out "$SHOTS/$name.small.jpeg" >/dev/null
    echo "$SHOTS/$name.small.jpeg" ;;
  tap) h shell uitest uiInput click "$2" "$3" >/dev/null ;;
  swipe) h shell uitest uiInput swipe "$2" "$3" "$4" "$5" "${6:-600}" >/dev/null ;;
  back) h shell uitest uiInput keyEvent Back >/dev/null ;;
  log)
    pid="$(h shell pidof "$BUNDLE" | tr -d '\r')"
    h shell "hilog -x" | grep " $pid " | grep -iE "${2:-flutter|error|exception}" | tail -60 ;;
  demo) h rport tcp:4995 tcp:4995 ;;
  *) sed -n '2,13p' "$0"; exit 1 ;;
esac
