#!/bin/bash
# Build a debug-signed release APK (R8 + shrink, debug keystore) for the
# current HEAD and drop it in release/ with the repo's established naming:
#   Yoin-<versionName>-release-debug-signed-<yyyymmdd>-<sha7>.apk
#
# Invoked in the background by .git/hooks/post-commit after every commit;
# safe to run by hand too. Logs to release/auto-build.log. Serialized with a
# lock dir so rapid consecutive commits queue instead of racing; each run
# builds whatever HEAD is when it starts (an outdated queued run is skipped
# if its target sha already has an APK).
set -u
cd "$(git rev-parse --show-toplevel)" || exit 1

if [ -z "${JAVA_HOME:-}" ] || [ ! -d "${JAVA_HOME:-}" ]; then
    if [ -d "/Applications/Android Studio.app/Contents/jbr/Contents/Home" ]; then
        export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
    else
        JAVA_HOME="$(/usr/libexec/java_home 2>/dev/null)" && export JAVA_HOME
    fi
fi

mkdir -p release
LOG="release/auto-build.log"
LOCK="release/.auto-build.lock"

# Queue behind a running build (gradle would serialize anyway, but this keeps
# the log readable and lets us skip stale runs). Stale locks (>30min) break.
tries=0
while ! mkdir "$LOCK" 2>/dev/null; do
    if [ "$(find "$LOCK" -maxdepth 0 -mmin +30 2>/dev/null | wc -l)" -gt 0 ]; then
        rm -rf "$LOCK"
        continue
    fi
    tries=$((tries + 1))
    [ "$tries" -gt 360 ] && exit 0
    sleep 5
done
trap 'rm -rf "$LOCK"' EXIT

SHA="$(git rev-parse --short=7 HEAD)"
DATE="$(date +%Y%m%d)"
VERSION="$(sed -n 's/^[[:space:]]*versionName = "\(.*\)"/\1/p' app/build.gradle.kts | head -1)"
OUT="release/Yoin-${VERSION:-unknown}-release-debug-signed-${DATE}-${SHA}.apk"

if [ -f "$OUT" ]; then
    echo "[$(date '+%F %T')] $SHA already built ($OUT), skipping" >> "$LOG"
    exit 0
fi

echo "[$(date '+%F %T')] building $SHA -> $OUT" >> "$LOG"
if ./gradlew assembleReleaseDebugSigned -q >> "$LOG" 2>&1; then
    cp app/build/outputs/apk/releaseDebugSigned/app-releaseDebugSigned.apk "$OUT"
    echo "[$(date '+%F %T')] OK $OUT ($(du -h "$OUT" | cut -f1))" >> "$LOG"
else
    echo "[$(date '+%F %T')] BUILD FAILED for $SHA — see log above" >> "$LOG"
fi
