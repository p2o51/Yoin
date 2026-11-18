#!/bin/zsh
# Serialises shared-state steps (Gradle, xcodegen, git commit) when several
# agents work in this worktree at once:  scripts/with-lock.sh <command…>
LOCK=/tmp/yoin-wt-ios.lock
until mkdir "$LOCK" 2>/dev/null; do sleep 2; done
trap 'rmdir "$LOCK"' EXIT
"$@"
