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
adb logcat -v threadtime > "$out/logcat-live.txt" 2>&1 &
log_pid=$!
(
  while true; do
    date -u +'%Y-%m-%dT%H:%M:%SZ'
    free -m
    ps -eo pid,comm,rss --sort=-rss | head -8
    sleep 2
  done
) > "$out/host-memory-timeline.txt" 2>&1 &
monitor_pid=$!

collect_diagnostics() {
  local result=$?
  trap - EXIT
  kill "$log_pid" "$monitor_pid" 2>/dev/null || true
  sudo dmesg -T | tail -160 > "$out/kernel-tail.txt" 2>&1 || true
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
  printf 'API32 uses the shipped pre-API33 Path fallback for AGSL seams; production UI source is unchanged.\n'
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
  printf 'Capturing %s/%s at %s\n' "$theme" "$name" "$(date -u +%H:%M:%S)"
  if ! timeout 45 adb shell am start -S -W -n "$app_id/$activity" "$@" > "$out/$theme/$name-launch.txt" 2>&1; then
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

  # Capture screens independently before Home's most complex shader/motion surface.
  capture 20-library-all .debug.LibraryScreenshotActivity --es tab All
  capture 40-now-playing .debug.MotionAuditActivity
  capture 50-settings-accounts .debug.SettingsPreviewActivity --es variant accounts
  capture 30-album-detail .debug.DetailScreenshotActivity
  if [ "$theme" = dark ]; then dark_extra=true; else dark_extra=false; fi
  capture 60-memories-card .debug.MemoryCardScreenshotActivity --es mode showcase --ei page 0 --ez dark "$dark_extra"
  capture 10-home-top .debug.MemoriesScreenshotActivity --es bubble seen --ez rediscover true --ez marks true

done

adb logcat -d > "$out/logcat.txt"
adb shell dumpsys package "$app_id" > "$out/package.txt"
adb shell am force-stop "$app_id"
printf '\nLaunch failures: %s\n' "$failures" >> "$out/capture-environment.txt"
printf 'Captured %s PNG files\n' "$(find "$out" -name '*.png' | wc -l)"
if [ "$failures" -gt 0 ]; then exit 1; fi
