# Provider alignment validation — 2026-10-01

## Delivered behavior

- Apple Music: explicit catalog/library search, paginated saved-song loading, and a separate song-library action in search and all Now Playing layouts. Accepted writes remain pending until exact authenticated membership confirms them. Library membership, favorites and local ratings remain distinct.
- Shared state: account-scoped membership, library refresh and busy operations. Switching accounts cannot publish an outgoing result or clear a new account's busy operation for the same song.
- Spotify: supported search page size and the current library endpoint for removing a saved playlist.
- Subsonic: optional `readonly` parsing and owner-aware playlist editing permissions.
- Responsive search album grids reuse existing library sizing tokens. Existing shell/back motion infrastructure is retained.

[Official API research and service boundaries](provider-alignment.md) · [Apple Music implementation and SDK notes](apple-music.md)

## Build and automated verification

Ran with Android Studio's bundled JBR:

```sh
./gradlew :app:testDebugUnitTest :app:ktlintCheck :app:assembleDebug :app:assembleDebugAndroidTest :app:assembleReleaseDebugSigned
```

Result: **427 JVM tests, zero failures/errors/skips; ktlint and both app builds passed.** Regression coverage includes asynchronous membership confirmation, personal API errors, exact library identity, account switches during writes/search/loading, independent busy operations, Spotify request contracts and Subsonic playlist permissions.

Pixel Tablet instrumentation on the final debug source: **3 tests passed** (`AppleMusicProfileIntegrationTest`'s two opt-in account checks and `AppleMusicSdkSmokeTest`). Read-only checks returned 25 catalog songs, 10 requested library songs and confirmed personal-library membership. The SDK test confirmed a 1,073,741,824-byte process-memory budget while keeping the tracked native-allocation budget at 268,435,456 bytes.

Logs: `outputs/provider-alignment-20261001/gradle-final.log` and `apple-final-live-test.log`.

## Physical-device checks

Used only the paired Pixel Tablet (`tangorpro`, Android 17 / API 37), through dedicated wireless ADB port **5038**. No phone or emulator was used. Existing encrypted profiles and app data were preserved.

| Check | Observed result |
| --- | --- |
| Apple Music actual API write | `ocean eyes` (`applemusic:1440899467`) changed from `NotAdded` to `Added`. Later exact personal-library search also confirmed the catalog identity. The first immediate search lagged the successful membership read, so index confirmation is tracked separately. |
| Apple Music actual UI write, optimized APK | Tapped the add button for `Ocean Eyes (Blackbear Remix)`. The row displayed a confirmed check and `Added to library`; switching to the personal Library search returned that saved track. |
| Library semantics | Apple Music Songs uses saved songs rather than the random-song endpoint. Catalog and personal-library chips were exercised independently. |
| Playback | The saved catalog-backed song played beyond 30 seconds with the full 3:20 duration. Pause/resume and the confirmed library control were exercised. This records SDK state and playback progress; no audio recording was made. |
| Responsive UI | Physical landscape 2560×1600 @320dpi (1280dp width), physical portrait 1600×2560 @320dpi (800dp), and simulated Compact 1080×2400 @420dpi (about 411dp). Search/result rows and Now Playing library controls were visually inspected. |
| Native memory regression, optimized APK | Before the fix, playback plus layout changes and account switching caused a JavaCPP fatal error at 515 MiB against its 512 MiB guard. After the bounded startup-property fix, pause/resume, portrait/landscape changes and playing Apple Music → Spotify switch completed in the same app process. Pre-switch total RSS was 749,496 KiB (about 732 MiB); exit history showed no new crash. |
| Spotify live search | The existing Spotify profile returned artists, albums, playlists and songs for `Taylor`; repeated search on the optimized APK also succeeded. Playlist removal was covered by HTTP regression tests, without deleting an existing user playlist. |
| Subsonic | DTO/permission regressions passed. No Subsonic account was present on this tablet, so live server behavior remains unverified. |

These checks added **two test songs** to the Apple Music account: the original `ocean eyes` and `Ocean Eyes (Blackbear Remix)`. They were left in the library; this implementation does not expose removal. No favorite was changed.

Evidence is under `outputs/provider-alignment-20261001/`: `apple-ui-add-feedback.png`, `apple-ui-add-library-readback.png`, `compact-library-search.png`, `np-compact.png`, `final-minified-portrait.png`, `spotify-search-songs.png`, `final-spotify-search.png`, `final-before-switch-memory.txt` and `final-exit-info.txt`.

## Installed artifact and remaining boundaries

Installed with `adb install -r`, verified version **0.5.0 / code 5**, and launched the optimized debug-signed APK:

`release/Yoin-0.5.0-provider-alignment-debug-signed-20261001.apk`

SHA-256: `d9bfd8f2092568520b81a20b7b04ba56766359073b2c09e5c4d7d7b0e5fbdf20`.

Apple Music is left active for inspection. Display size, density, auto-rotation, fixed-rotation mode and charging wake behavior are restored to their captured original values.

Apple Music library removal, favorite mutation, playlist editing, offline downloads and Cast remain unavailable. New authorization/reconnect, Bluetooth hardware, long-duration decoder stability and a live Subsonic account were not validated in this pass. The bounded memory policy fixes the reproduced guard failure; it does not establish that the vendor decoder has no leak. Existing Apple SDK bytecode and 16 KB native-library warnings remain documented separately.
