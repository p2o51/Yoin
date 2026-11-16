# Yoin on Windows (shared Flutter code)

The HarmonyOS app and a future Windows build are one Flutter codebase. Everything
under `lib/` is shared except the platform seams below, which sit behind Dart
interfaces and are picked in **one place**: `main.dart` →
`YoinPlatform.forHost()` (`lib/platform/yoin_platform.dart`), wired into Riverpod
by `platformOverrides()` (`lib/app/platform_overrides.dart`). No widget or view
model talks to a method channel or checks the OS.

There is no `windows/` runner yet and no Windows-only package in `pubspec.yaml`.
`YoinPlatform.windows()` is a set of pure-Dart stand-ins
(`lib/platform/windows/windows_platform.dart`), so a Windows build compiles and
starts, browses a Subsonic server and keeps its settings. It cannot play audio yet.

## What is shared

- All UI (`lib/home`, `lib/library`, `lib/detail`, `lib/nowplaying`,
  `lib/memories`, `lib/settings`, `lib/shell`, `lib/ui`), theme, motion and tokens.
- Data: models, `MusicSource`, Subsonic and the Apple Music Web API client
  (both plain `dart:io` HTTP), profiles, the Memory and activity stores (on top of
  `KeyValueStore`).
- The playback chain in Dart (`lib/playback`): queue, play modes, scrobbling,
  history, ratings. It drives whatever `PlaybackEngine` the bundle gives it.
- Desktop input (`lib/ui/component/desktop_input.dart`,
  `lib/shell/desktop_shortcuts.dart`): hover state layers, cursors, right-click
  menus, focus rings and the keyboard shortcuts already work for the MateBook
  2in1 and apply unchanged on Windows.

## The seams

| Seam (interface) | Provider | HarmonyOS (unchanged) | Windows today | What Windows needs |
| --- | --- | --- | --- | --- |
| `KeyValueStore` | `keyValueStoreProvider` | `ChannelKeyValueStore`: `yoin/storage` → Preferences | `FileKeyValueStore`: `prefs.json` under `%APPDATA%\Yoin`, atomic writes | Good enough. Swap the folder lookup for `path_provider`'s `getApplicationSupportDirectory()`. |
| `KeyValueStore` (caches) | `cacheStoreProvider` | `ChannelKeyValueStore(file: 'yoin_cache')`: a second Preferences file for the lyrics cache, so its ~10 MiB never rides along with a settings write | `FileKeyValueStore(fileName: 'cache.json')` next to `prefs.json` | As above. |
| `SecretStore` | `secretStoreProvider` | `ChannelSecretStore`: `yoin/storage` → Asset Store Kit | `SessionSecretStore`: memory only, so accounts are asked for again on each launch (never written in the clear) | `flutter_secure_storage` (DPAPI / Credential Manager). |
| `PlaybackEngine` / `QueuePlaybackEngine` | `playbackEngineProvider` | `ChannelPlaybackEngine`: `yoin/playback` (+ events): AVPlayer, AVSession, continuous task. Apple Music plays through the same engine. | `UnsupportedPlaybackEngine`: starting a queue throws `UnsupportedError` (the player shows its playback error); pause, stop and settings are no-ops | `media_kit` (libmpv, gapless, HTTP headers) for audio, plus System Media Transport Controls (`smtc_windows`, or `audio_service` with its Windows backend) for media keys, the lock screen and the taskbar overlay. Implement `QueuePlaybackEngine` in Dart: the queue, repeat/shuffle order and `EngineState` ticks the ArkTS engine does today. Apple Music needs MusicKit JS in a WebView or Apple's catalog previews: out of scope for v1. |
| `AppleMusicAuth` | `appleMusicAuthProvider` | `ChannelAppleMusicAuth`: `yoin/applemusic_auth` → ArkWeb + MusicKit JS | `UnsupportedAppleMusicAuth`: "not available on this build" | `webview_windows` (WebView2) hosting the same MusicKit JS page. |
| `CardsHost` | `cardsHostProvider` | `ChannelCardsHost`: `yoin/cards` → Form Kit service cards | `NoCardsHost`: no-op | Optional: a jump list or taskbar thumbnail buttons (`windows_taskbar`). |
| `AppFilesHost` | `appFilesHostProvider` (`offline/offline_providers.dart`) | `ChannelAppFilesHost`: `yoin/storage` `filesDir` → the sandbox's files folder (offline downloads live in `offline_media/`; the player opens them as a file descriptor) | `DirectoryAppFilesHost`: the app-data folder, so downloads work once audio plays | Good enough. Swap the folder for `path_provider`'s `getApplicationSupportDirectory()`; the playback engine must play `EngineItem.localUri` (a `file://` address) when it is set and the file is there, and `uri` otherwise. |
| `NetworkBearerSource` | `networkBearerProvider` (`settings/stream_quality.dart`) | `ChannelNetworkBearer`: `yoin/network` → `@ohos.net.connection` | `FixedNetworkBearer(other)`: treated as unmetered | `connectivity_plus`, or WinRT `NetworkInformation` for the metered flag. |
| `NameTransliterator` | `nameTransliteratorProvider` | `ChannelNameTransliterator`: `yoin/i18n` → LocalizationKit Han→Latin | `IdentityNameTransliterator`: Han names sort into the trailing section | Optional: a Dart pinyin table, or ICU through FFI (`icu4x`). |
| `WindowControls` | `windowControlsProvider` | `ChannelWindowControls`: `yoin/window` → keep screen on, system toast | `NoWindowControls`: no-op | `wakelock_plus` (SetThreadExecutionState); a toast through `local_notifier`. |
| `CloudSyncHost` | `cloudSyncHostProvider` (`data/sync/cloud_sync_providers.dart`) | `ChannelCloudSyncHost`: `yoin/cloud_sync` → the browser through a Want, a transient task while the user signs in, the market name, `filesDir` | `DesktopCloudSyncHost`: the default browser through the shell, nothing to hold, the host name, the app-data folder. Google sign-in (loopback + PKCE) and Drive are plain Dart, so Cloud sync works as is (with today's session-only `SecretStore` it asks to sign in again after a restart) | Nothing; `url_launcher` could replace the shell call. |
| `CastHost` | `castHost` in the bundle; `platformOverrides` builds the `PlaybackRouter` (`playbackRouterProvider`, also the `playbackEngineProvider`) over it | `ChannelCastHost`: `yoin/cast` → `AVCastPickerHelper` (the system's output / cast panel), the session's `outputDeviceChange`, and the `AVCastController` as a renderer while the output is a remote cast device. The session mirrors any remote renderer through `yoin/playback` `setRemote` (keys come back as `SessionTransportCommand`s; the continuous task switches to `MULTI_DEVICE_CONNECTION`) | `NoCastHost`: no cast row. DLNA speakers (SSDP + SOAP in plain `dart:io`, `lib/playback/output/`) work as they are | Nothing for DLNA. A system output picker could be the Windows audio-endpoint list (`win32` `IMMDeviceEnumerator`); casting needs a DIAL/Cast stack: out of scope. |
| `WindowCaptionHost` | passed to `YoinWindowCaption` (`YoinApp.windowCaption`) | `ChannelWindowCaptionHost`: `yoin/window`. The 2in1 hides the title bar, so pages draw under it, the caption height becomes the top inset and the strip left of the three buttons moves or maximizes the window. | `SystemTitleBarHost`: the system title bar stays, no inset | `window_manager`: `TitleBarStyle.hidden`. `read()` returns `active: true`, height ≈ 32 and buttons ≈ 138 wide at the right (or draw your own caption buttons); `startMoving` → `windowManager.startDragging()`, `toggleMaximize` → maximize/unmaximize. Everything above the host already lays out for it. |

Already portable, no seam needed: `SystemChrome` (edge-to-edge, bar styles: no-ops
on Windows), `HapticFeedback` (no-op), `dart:io` HTTP and `Platform.environment`.

## Platform selection

`YoinPlatform.forHost()` reads `Platform.operatingSystem`: `ohos` gives the ArkTS
channels, `windows` (and `linux` / `macos`, for desktop runs during development)
gives the stand-ins, and anything else falls back to the ArkTS channels. Tests
never call it; they override the providers one by one.

## Adding the Windows runner later

The OHOS fork is the toolchain. Its Flutter 3.41 keeps upstream's desktop
support, but Windows has to be built **on Windows**, with Visual Studio 2022 and
its "Desktop development with C++" workload.

1. On a Windows machine with the same fork checked out:
   `flutter config --enable-windows-desktop`, then in `harmony/`:
   `flutter create --platforms=windows --org com.gpo .`. This adds `windows/` and
   leaves `lib/` alone.
2. Add the packages for the seams you are filling (table above). Keep each Windows
   implementation in `lib/platform/windows/` and switch the matching
   `YoinPlatform.windows()` field from its stand-in. Each package must either
   support OHOS through the fork's `ohos` plugin variant, or be imported only from
   `lib/platform/windows/`, so the HAP never links it. With a plugin that has no
   OHOS implementation, `flutter build hap` must still pass, because the registrant
   only lists plugins for the target platform.
3. Window chrome: with `window_manager` hiding the title bar, return the caption
   from `WindowCaptionHost.read()` and the shell keeps its content clear of the
   buttons exactly as on the 2in1.
4. Fonts: Google Sans Flex ships in `assets/`. No change needed.
5. Run `flutter analyze` and `flutter test` (they run on any host), then
   `flutter build windows --release`. Then check `flutter build hap --release`
   on the Mac to confirm the shared code still builds for HarmonyOS.

## Keyboard and pointer (shared by 2in1 and Windows)

The shortcuts follow the Mac app's Controls menu, with Ctrl in place of ⌘. They
live in `lib/shell/desktop_shortcuts.dart`, which also has the table. While a text
field has focus, every shortcut is off and the key goes to the field. Hover,
cursor, right-click and focus-ring behaviour lives in
`lib/ui/component/desktop_input.dart`.
