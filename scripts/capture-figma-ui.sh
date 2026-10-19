#!/usr/bin/env bash
# Runs only on a fresh, isolated Actions emulator. Uses existing debug fixtures
# rendering the production Composables. No credentials, accounts or network data.
set -euo pipefail

out=outputs/figma-ui-capture
mkdir -p "$out"
app_id=com.gpo.yoin
adb wait-for-device
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell wm size 824x1784
adb shell wm density 320
adb shell settings put system accelerometer_rotation 0
adb shell settings put system user_rotation 0
adb shell settings put global window_animation_scale 1
adb shell settings put global transition_animation_scale 1
adb shell settings put global animator_duration_scale 1
adb shell settings put secure sysui_demo_allowed 1
adb shell am broadcast -a com.android.systemui.demo -e command enter >/dev/null
adb shell am broadcast -a com.android.systemui.demo -e command clock -e hhmm 0941 >/dev/null
adb shell am broadcast -a com.android.systemui.demo -e command battery -e level 100 -e plugged false >/dev/null
adb shell am broadcast -a com.android.systemui.demo -e command network -e wifi show -e level 4 -e mobile hide >/dev/null
adb shell input keyevent 82
adb logcat -c

collect_diagnostics() {
  local result=$?
  trap - EXIT
  timeout 10 adb logcat -d > "$out/logcat.txt" 2>&1 || true
  timeout 10 adb shell dumpsys package "$app_id" > "$out/package.txt" 2>&1 || true
  free -h > "$out/host-memory.txt" || true
  ps -eo pid,comm,rss --sort=-rss | head -20 > "$out/host-largest-processes.txt" || true
  printf '\nCapture script exit: %s\n' "$result" >> "$out/capture-environment.txt"
  exit "$result"
}
trap collect_diagnostics EXIT
adb shell am start -W -n "$app_id/.debug.DesignTokenExportActivity"
adb exec-out run-as "$app_id" cat files/figma-design-tokens.json > "$out/design-tokens.json"

{
  printf 'Source commit: '
  git rev-parse HEAD
  printf '\nPinned symbol commit: '
  git -C yoin-symbols rev-parse HEAD
  printf '\nAndroid build fingerprint: '
  adb shell getprop ro.build.fingerprint
  printf '\nDisplay: '
  adb shell wm size
  adb shell wm density
  printf '\nCapture scope: production Compose screens using repository debug fixtures; no user accounts.\n'
  printf 'Home/Library fixture activities omit the shell bar; MainActivity and BarMorph capture shipping shell chrome.\n'
  printf 'Covers in fixture activities are generated swatches or the app icon, not real user album art.\n'
  printf 'Light/dark colors use the emulator system dynamic palette, except fixtures that explicitly override their scheme.\n'
} > "$out/capture-environment.txt"

failures=0
printf 'theme\timage\tactivity\tintent extras\n' > "$out/manifest.tsv"

capture() {
  local name="$1"
  local activity="$2"
  shift 2
  local path="$out/$theme/$name.png"
  mkdir -p "$out/$theme"
  printf '%s\t%s\t%s\t%s\n' "$theme" "$theme/$name.png" "$activity" "$*" >> "$out/manifest.tsv"
  if ! adb shell am start -S -W -n "$app_id/$activity" "$@" > "$out/$theme/$name-launch.txt" 2>&1; then
    printf 'Launch failed: %s (%s)\n' "$name" "$theme"
    failures=$((failures + 1))
    return
  fi
  sleep 3
  adb exec-out screencap -p > "$path"
  local resumed
  resumed="$(adb shell dumpsys activity activities | grep -E 'mResumedActivity|topResumedActivity' || true)"
  printf '%s\n' "$resumed" > "$out/$theme/$name-resumed.txt"
  if [[ "$resumed" != *"$app_id"* ]]; then
    printf 'App did not remain resumed: %s (%s)\n' "$name" "$theme"
    failures=$((failures + 1))
  fi
}

capture_current() {
  local name="$1"
  sleep 2
  adb exec-out screencap -p > "$out/$theme/$name.png"
  printf '%s\t%s\t%s\t%s\n' "$theme" "$theme/$name.png" 'current activity' 'gesture on prior fixture' >> "$out/manifest.tsv"
}

for theme in light dark; do
  if [ "$theme" = dark ]; then night_mode=yes; else night_mode=no; fi
  adb shell cmd uimode night "$night_mode"
  sleep 2

  capture 00-shell-first-run .MainActivity
  capture 01-shell-bottom-bar .debug.BarMorphPreviewActivity

  capture 10-home-top .debug.MemoriesScreenshotActivity --es bubble seen --ez rediscover true --ez marks true
  capture 13-home-edit .debug.MemoriesScreenshotActivity --es bubble seen --ez rediscover true --ez marks true --ez edit true

  capture 20-library-all .debug.LibraryScreenshotActivity --es tab All
  capture 21-library-albums .debug.LibraryScreenshotActivity --es tab Albums
  capture 22-library-artists .debug.LibraryScreenshotActivity --es tab Artists
  capture 23-library-songs .debug.LibraryScreenshotActivity --es tab Songs
  capture 24-library-playlists .debug.LibraryScreenshotActivity --es tab Playlists
  capture 25-library-favorites .debug.LibraryScreenshotActivity --es tab Favorites

  capture 30-album-detail .debug.DetailScreenshotActivity
  adb shell input swipe 412 1200 412 500 500
  capture_current 31-album-detail-lower

  capture 40-now-playing .debug.MotionAuditActivity
  adb shell input keyevent 4
  capture_current 41-player-collapsed-bar
  capture 42-lyrics-full .debug.LyricsMotionPreviewActivity --es mode full --ez translation true --ei song 1 --el startAt 22000
  capture 43-lyrics-compact .debug.LyricsMotionPreviewActivity --es mode compact --ez translation true --ei song 1 --el startAt 22000

  capture 50-settings-accounts .debug.SettingsPreviewActivity --es variant accounts
  capture 51-settings-provider-picker .debug.SettingsPreviewActivity --es variant picker
  capture 52-service-add-spotify .debug.SettingsPreviewActivity --es variant addSpotify
  capture 53-service-manage-subsonic .debug.SettingsPreviewActivity --es variant manageSubsonic
  capture 54-service-manage-spotify .debug.SettingsPreviewActivity --es variant manageSpotify

  if [ "$theme" = dark ]; then dark_extra=true; else dark_extra=false; fi
  capture 60-memories-card .debug.MemoryCardScreenshotActivity --es mode showcase --ei page 0 --ez dark "$dark_extra"
  capture 61-memories-diary .debug.MemoryCardScreenshotActivity --es mode showcase --ei page 0 --ez dark "$dark_extra" --ez diary true
  capture 62-memories-track .debug.MemoryCardScreenshotActivity --es mode showcase --ei page 1 --ez dark "$dark_extra"
  capture 63-memories-album .debug.MemoryCardScreenshotActivity --es mode showcase --ei page 2 --ez dark "$dark_extra"
  capture 64-memories-empty .debug.MemoriesEmptyPreviewActivity --es variant fresh
done

adb logcat -d > "$out/logcat.txt"
adb shell dumpsys package "$app_id" > "$out/package.txt"
adb shell am force-stop "$app_id"
printf '\nLaunch failures: %s\n' "$failures" >> "$out/capture-environment.txt"
printf 'Captured %s PNG files\n' "$(find "$out" -name '*.png' | wc -l)"
if [ "$failures" -gt 0 ]; then exit 1; fi
