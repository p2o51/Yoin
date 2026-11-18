#!/bin/zsh
# Build Yoin for the iPhone 17 Pro simulator and open it with Glass Lab.
# Glass Lab: drag its header to move it, "Dock top" while tuning the bar,
# "Fold" to collapse; a three-finger long-press (Option-drag in Simulator)
# toggles it. "Export JSON" copies the values and writes Documents/glass-lab.json.
set -e
export DEVELOPER_DIR=${DEVELOPER_DIR:-/Applications/Xcode.app/Contents/Developer}
cd "$(dirname "$0")/.."
command -v xcodegen >/dev/null && xcodegen generate >/dev/null
DEV=$(xcrun simctl list devices available | grep -m1 "iPhone 17 Pro (" | sed -E 's/.*\(([0-9A-F-]{36})\).*/\1/')
xcodebuild -project Yoin.xcodeproj -scheme Yoin -destination "platform=iOS Simulator,id=$DEV" \
  -derivedDataPath build/dd build -quiet
xcrun simctl boot "$DEV" 2>/dev/null || true
open -a Simulator
xcrun simctl install "$DEV" build/dd/Build/Products/Debug-iphonesimulator/Yoin.app
xcrun simctl terminate "$DEV" com.gpo.yoin 2>/dev/null || true
xcrun simctl launch "$DEV" com.gpo.yoin -glassLab 1 -screen home
