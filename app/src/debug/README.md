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
| `BarMorphPreviewActivity` | The bottom bar's nav ⇄ Play-split morph in isolation, inside a `SharedTransitionLayout`, so bar poses can be scrubbed without the shell. |
| `DetailScreenshotActivity` | `AlbumDetailScreen` against fixed fake data. |
| `LibraryScreenshotActivity` | The Library page against fixed fake data. Pick the tab with a string extra: `--es tab Albums`. |
| `MemoriesScreenshotActivity` | The whole redesigned home feed — Activities bento, Jump Back In widget grid, compact Recently Added. |

Example:

```bash
adb shell am start -n com.gpo.yoin/com.gpo.yoin.debug.LibraryScreenshotActivity --es tab Albums
```

## Token bridge

`SpotifyTokenExportProvider` exposes the current Spotify access token to local tooling
(the `playground/track-match` research subproject). It is guarded by the platform
`android.permission.DUMP`, which the ADB shell holds and ordinary apps do not.

```bash
adb shell content query --uri content://com.gpo.yoin.debug.spotifytoken/access_token
```

This is debug-build research plumbing, not release behaviour.
