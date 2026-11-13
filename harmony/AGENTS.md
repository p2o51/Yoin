# AGENTS.md — Yoin for HarmonyOS

Applies to everything under `harmony/`. The repo-root `AGENTS.md` product rules still hold
(animation is mandatory, MD3 Expressive is intentional, YAGNI, Yoin Symbols only); this file adds
how they map onto Flutter + ArkTS. Toolchain, build and install commands: `README.md`.

## Sources of truth

- Design: `../docs/design.md` and `../docs/adaptive-principles.md`. The Android app (`../app`) is the
  behaviour reference: when a screen exists there, match it — layout, motion, copy, edge cases.
- Numbers: `lib/theme/tokens.dart` mirrors `:shared` `YoinTokens.kt` (iOS branch). Never type a
  dp/spring literal in a screen; add it to tokens (and say so in your report so the Kotlin side follows).
- Colours: `lib/theme/color_scheme.dart` (SPEC_2025 Expressive, same as Android). Read colours from
  `Theme.of(context).colorScheme`, never hard-code.

## Layout of the code

| Path | What lives there |
| --- | --- |
| `lib/data/model/` | Provider-neutral models (`MediaId`, `Track`, `Album`…). No provider DTO leaves its source. |
| `lib/data/source/music_source.dart` | The provider interface, sliced + `Capability`-gated. New backend feature = method + capability, implemented in every source. |
| `lib/data/subsonic/` | The Subsonic provider. |
| `lib/data/profile/` | Profiles and credentials (credentials only in `SecretStore`). |
| `lib/platform/` | Dart clients of ArkTS channels (`yoin/playback`, `yoin/storage`). Widgets never touch channels. |
| `ohos/entry/src/main/ets/yoin/` | ArkTS: playback engine (AVPlayer + AVSession + continuous task), storage, later cards and fold status. |
| `lib/app/providers.dart` | Riverpod providers (no codegen) — the one dependency graph. |
| `lib/ui/<feature>/` | One folder per feature: screen widgets + its view model. |
| `lib/theme/` | Tokens, springs (`motion.dart`), theme, colour. |

## State

- One Riverpod `Notifier` / `AsyncNotifier` per screen = the Android ViewModel. UI state is a
  `sealed class` (Loading / Content / Error …), widgets switch on it exhaustively.
- Widgets stay stateless except for purely visual animation state (`SpringValue`s).
- Feature code reaches the backend only through `MusicSource` from the active profile; gate
  affordances on `source.capabilities`.

## Motion

- Every meaningful change animates. Use `SpringValue` / `YoinSprings` (`lib/theme/motion.dart`):
  spatial springs for position/size, effects springs for colour/alpha. No `Duration` + `Curve`
  tweens for navigation or surfaces. Retarget from current value and velocity, never restart.
- Honour reduced motion (`MediaQuery.disableAnimationsOf`): fade in place instead of moving.
- One primary progress value per surface; everything on that surface reads it.

## Navigation and back

- Shell = Home + Library (RootSection). Never intercept root back.
- Pushed pages (detail, settings): `YoinPushRoute` only, with an explicit back button that calls
  `Navigator.pop` — the same path the system back takes.
- HarmonyOS back (side swipe, back key) delivers no progress. System back therefore *commits*
  through the same controller as the button/gesture (Android's three-button path). Overlays (Now
  Playing, Memories) own one controller fed by their drag gesture, their buttons and system back.
- No raw `PopScope`/`WillPopScope` in feature code; add shared back infrastructure instead.

## Adaptive

- Read `YoinWindowInfo.of(context)` only (height first, then width; 600 / 840; columns provide
  `forPaneWidth`). Never branch on device model. Test portrait, landscape, the MateBook window,
  and a narrow window; Pura X's outer screen is a ~1:1 compact window.

## Icons, strings, fonts

- Icons: `yoin_symbols` only. Missing one → add it in `../../yoin-symbols` first (its CONTRIBUTING).
- Strings: English + Simplified Chinese only, copied from Android `res/values{,-zh-rCN}/strings.xml`
  with the same key in a comment. Do not invent UI copy; new copy comes from the owner's copy model.
- Text uses the theme's text styles (Google Sans Flex with opsz; `gsf()` for ROND/weight axes).

## Testing and devices

- `flutter analyze` clean and `flutter test` green before you report. Test names:
  `should_expectedBehavior_when_condition`. View models get unit tests; key interactions get widget tests.
- Device: Mate 70 Air `5ZGYD25B20051376` (phone), MateBook Pro S `8VM0226725000812` (2in1), both
  HarmonyOS 7 / API 26. Install the release HAP (debug is JIT and lies about smoothness). Never play
  audible audio at night; never touch other apps' data.

## Working in parallel

- One agent per feature folder. `pubspec.yaml`, `lib/main.dart`, `lib/app/providers.dart`,
  `lib/theme/**`, `ohos/**/module.json5` and `EntryAbility.ets` are shared: change them only when your
  task says so, and list the change in your report.
- Don't commit, push or merge unless told to.
