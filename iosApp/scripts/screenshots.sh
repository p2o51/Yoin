#!/bin/zsh
# Usage: scripts/screenshots.sh <device-udid> <prefix> screen[:extra args] ...
# Builds nothing; expects build/dd from a prior xcodebuild run.
set -e
export DEVELOPER_DIR=${DEVELOPER_DIR:-/Applications/Xcode.app/Contents/Developer}
cd "$(dirname "$0")/.."
DEV=$1; PREFIX=$2; shift 2
APP=build/dd/Build/Products/Debug-iphonesimulator/Yoin.app
xcrun simctl boot "$DEV" 2>/dev/null || true
# bootstatus can hang on a first boot; give it a moment instead
sleep 5
xcrun simctl status_bar "$DEV" override --time 9:41 --batteryState charged --batteryLevel 100 --wifiBars 3 --cellularBars 4
xcrun simctl install "$DEV" "$APP"
for spec in "$@"; do
  name=${spec%%:*}; extra=""
  [[ "$spec" == *:* ]] && extra=${spec#*:}
  xcrun simctl terminate "$DEV" com.gpo.yoin 2>/dev/null || true
  # Drop UIKit scene restoration so one shot's state (e.g. an open
  # inspector) never leaks into the next launch.
  DATA=$(xcrun simctl get_app_container "$DEV" com.gpo.yoin data)
  rm -rf "$DATA/Library/Saved Application State" "$DATA/Library/SplashBoard"
  xcrun simctl launch "$DEV" com.gpo.yoin -screen ${name%%@*} ${=extra} >/dev/null
  sleep 3
  xcrun simctl io "$DEV" screenshot --type=png "screenshots/${PREFIX}-${name/@/-}.png" >/dev/null 2>&1
  echo "screenshots/${PREFIX}-${name/@/-}.png"
done
