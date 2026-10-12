# Debug-only surfaces

Everything in this source set is packaged into the **debug variant only** — it never
reaches a release build, so none of it is a shipping-surface risk. The activities are
`android:exported="true"` because that is what lets `adb shell am start` launch them
directly; they take no input from other apps and hold no user data.

Nothing in `app/src/main` references any of these by name, which makes them easy to
forget. This file is the index so they don't rot.

## Visual QA harnesses

Launch with `adb shell am start -n com.gpo.yoin/com.gpo.yoin.debug.<Activity>`.

| Activity | What it shows |
| --- | --- |
| `AuroraPreviewActivity` | Both aurora effects with no playback and no server: top half is the Now Playing Gemini-thinking wash pinned active, bottom half the Memories ambient wash. |
| `MotionAuditActivity` | Real Now Playing → detail → nested player navigation with a silent in-memory track and shared cover scopes. No configured account required; this debug-only fixture does not persist credentials or start playback. |
| `BarMorphPreviewActivity` | The bottom bar's nav ⇄ Play-split morph in isolation, inside a `SharedTransitionLayout`, so bar poses can be scrubbed without the shell. |
| `ShadowReturnAuditActivity` | Shell-like window wired like `YoinNavHost`'s bar, opening the real `AlbumDetailActivity` (fake id → error page) so back runs the shipping commit and close dissolve (the bar is shadowless since the 2026-10-01 dissolve round). `--el autoOpenMs 800` auto-opens; `--ez timecode true` draws a per-frame uptime code (logged under `ShadowReturnAudit`) to match screen-recording frames to draw times. |
| `DetailScreenshotActivity` | `AlbumDetailScreen` against fixed fake data. |
| `LibraryScreenshotActivity` | The Library page against fixed fake data. Pick the tab with a string extra: `--es tab Albums`. |
| `MemoriesScreenshotActivity` | The whole redesigned home feed — Activities bento, Jump Back In widget grid, compact Recently Added. |
| `MemoriesEmptyPreviewActivity` | Prototype of the redesigned Memories empty state. Pick the variant with a string extra: `--es variant fresh` (ghost seal + gates) or `--es variant almost` (near-miss album + CTA). |

Example:

```bash
adb shell am start -n com.gpo.yoin/com.gpo.yoin.debug.LibraryScreenshotActivity --es tab Albums
```

## 详情页 QA 通道

Debug manifest 把 `AlbumDetailActivity` 放开成 exported（release 保持
false），让 adb 能在无账号数据的设备上直接以**独立窗口**启动它——这是
Compact / Medium / 矮窗下的推入页（Pattern B）：

```bash
adb shell am start -n com.gpo.yoin/.ui.detail.AlbumDetailActivity --es albumId any-id
```

fake id 会让 detail 显示加载失败态，底栏与返回照常可验。

Wide（≥ 840 且高 ≥ 480）窗口里**新打开**的详情不是 Activity，而是 shell 窗口里的
右列（`ui/navigation/pane/`，适配原则 3），adb 推 Activity 触发不了列（adb 推出来的
Activity 在 Wide 窗里保持整窗，属预期）。
列的 QA 走 MainActivity：横屏平板（或 `wm size 1280x800 && wm density 160`）
里从 Home / Library 点任意专辑 / 歌手 / 歌单。验完
`adb shell wm size reset && adb shell wm density reset`。

## Token bridge

`SpotifyTokenExportProvider` exposes the current Spotify access token to local tooling
(the `playground/track-match` research subproject). It is guarded by the platform
`android.permission.DUMP`, which the ADB shell holds and ordinary apps do not.

```bash
adb shell content query --uri content://com.gpo.yoin.debug.spotifytoken/access_token
```

This is debug-build research plumbing, not release behaviour.

## App Remote library-state probe

`LibraryStateProbeReceiver` asks the Spotify app, over App Remote, whether each given
URI (track or album) is in the user's library (`UserApi.getLibraryState`) and logs
`isAdded` / `canAdd` / time / error under the `YoinProbe` tag. Read-only: it reuses
Yoin's warm connection, starts no playback, touches no queue and logs no token.
Guarded by `android.permission.DUMP` like the token bridge. Open Yoin first (App Remote
connects only while a Yoin Activity is started).

```bash
adb shell am broadcast -n com.gpo.yoin/.debug.LibraryStateProbeReceiver \
  -a com.gpo.yoin.debug.LIBRARY_STATE --esa uris spotify:track:<id>,spotify:album:<id>
adb logcat -d -s YoinProbe:*
```

Details and the output format: `docs/perf/yoinperf-logging.md`.
