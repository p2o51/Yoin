#!/usr/bin/env bash
#
# Idempotent development-environment bootstrap for Linux / Cursor Cloud Agents.
#
# The macOS instructions in README.md / CONTRIBUTING.md assume Android Studio's
# bundled JBR and SDK. On a headless Linux box (and in Cursor Cloud Agents) this
# script provisions an equivalent toolchain instead:
#
#   * Java: uses the JDK already on PATH. Gradle's daemon toolchain is pinned to
#     Java 21 (gradle/gradle-daemon-jvm.properties), which Gradle auto-detects
#     from the system JVMs; no JDK install is performed here.
#   * Android SDK: installed under ${ANDROID_HOME:-$HOME/android-sdk} via the
#     command-line tools when missing (platform-tools, platforms;android-36,
#     build-tools;36.0.0 — matching app/build.gradle.kts compileSdk = 36).
#   * local.properties: (re)written with sdk.dir so Gradle finds the SDK on a
#     fresh checkout (the file is git-ignored and never committed).
#
# Safe to run repeatedly: existing SDK components and Gradle caches are reused.

set -euo pipefail

ANDROID_HOME="${ANDROID_HOME:-$HOME/android-sdk}"
ANDROID_SDK_ROOT="$ANDROID_HOME"
export ANDROID_HOME ANDROID_SDK_ROOT

CMDLINE_TOOLS_VERSION="11076708"
PLATFORM="platforms;android-36"
BUILD_TOOLS="build-tools;36.0.0"

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$repo_root"

log() { printf '\033[1;36m[cloud-install]\033[0m %s\n' "$*"; }

install_cmdline_tools() {
  local dest="$ANDROID_HOME/cmdline-tools/latest"
  if [ -x "$dest/bin/sdkmanager" ]; then
    return
  fi
  log "Installing Android command-line tools into $dest"
  local url="https://dl.google.com/android/repository/commandlinetools-linux-${CMDLINE_TOOLS_VERSION}_latest.zip"
  local tmp
  tmp="$(mktemp -d)"
  curl -fSL --retry 4 --retry-delay 4 -o "$tmp/cmdtools.zip" "$url"
  unzip -q "$tmp/cmdtools.zip" -d "$tmp"
  mkdir -p "$ANDROID_HOME/cmdline-tools"
  rm -rf "$dest"
  mv "$tmp/cmdline-tools" "$dest"
  rm -rf "$tmp"
}

install_sdk_packages() {
  local sdkmanager="$ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager"
  log "Ensuring SDK packages (licenses, platform-tools, $PLATFORM, $BUILD_TOOLS)"
  yes | "$sdkmanager" --licenses >/dev/null 2>&1 || true
  "$sdkmanager" "platform-tools" "$PLATFORM" "$BUILD_TOOLS" >/dev/null
}

write_local_properties() {
  log "Writing local.properties (sdk.dir=$ANDROID_HOME)"
  printf 'sdk.dir=%s\n' "$ANDROID_HOME" > local.properties
}

warm_build() {
  log "Warming Gradle build (assembleDebug)"
  ./gradlew --no-daemon assembleDebug
}

install_cmdline_tools
install_sdk_packages
write_local_properties
warm_build

log "Environment ready. Debug APK: app/build/outputs/apk/debug/app-debug.apk"
