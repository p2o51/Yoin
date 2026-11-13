# Yoin for HarmonyOS

Flutter UI + ArkTS modules (playback, AVSession, background task, cards). The
tech choice and its reasons are in Notion: 公文包 →「Yoin 鸿蒙版技术选型」.
`docs/design.md` at the repo root is still the design source of truth.

## Toolchain

| Piece | Version | Where |
| --- | --- | --- |
| Flutter for OpenHarmony | 3.41.10-ohos-1.0.1 (Dart 3.11.5) | `~/Developer/flutter_ohos`, from gitcode.com/CPF-Flutter/flutter_flutter |
| DevEco Studio | **26.0.0 or newer** (API 26) — the Flutter HAR uses API 26 symbols (`Window.isInFreeWindowMode`, `autoFillManager`); 6.0.1 / API 21 fails ArkTS compile | `/Applications/DevEco-Studio.app` |
| Yoin Symbols | sibling checkout | `../../yoin-symbols/flutter` |

```sh
source ~/Developer/flutter_ohos/ohos-env.sh   # PATH for flutter, hvigorw, ohpm, node, hdc; JAVA_HOME = DevEco's JBR (PackageHap needs java)
flutter devices                                # Mate 70 Air (phone) and MateBook Pro S (2in1)
flutter build hap --release && hdc -t <device-id> install -r build/ohos/hap/entry-default-signed.hap
hdc -t <device-id> shell aa start -a EntryAbility -b com.gpo.yoin
flutter analyze && flutter test
```

Do not set `FLUTTER_STORAGE_BASE_URL` to the Huawei OBS (the fork's README FAQ 10 is stale for
3.41): OHOS artifacts already come from `FLUTTER_OHOS_STORAGE_BASE_URL` (default OBS), upstream
ones must stay on the default storage.

## Signing

Debug installs need a DevEco auto-generated signature for `com.gpo.yoin`, made with the owner's
Huawei account: open `harmony/ohos` in DevEco → File → Project Structure → Signing Configs →
Automatically generate signature, with the devices plugged in. It writes `signingConfigs` into
`ohos/build-profile.json5` pointing at `~/.ohos/config/default_ohos_*`; those paths and the encrypted
passwords are machine-local, so don't commit that block.

## Layout

- `lib/theme/` — `tokens.dart` mirrors `:shared` YoinTokens.kt; `motion.dart` springs; `theme.dart`
  Google Sans Flex; `color_scheme.dart` + `mcu2025/` colours.
- `lib/data/` — models, `MusicSource`, Subsonic, profiles. `lib/platform/` — ArkTS channel clients.
- `lib/app/providers.dart` — Riverpod dependency graph. Conventions for agents: `AGENTS.md`.
- `lib/shell/` — the shell and the floating bar (idle pose so far).
- `lib/settings/` — Subsonic account setup.
- `lib/home/`, `lib/library/` — pages.
- `ohos/` — the HarmonyOS host (EntryAbility = FlutterAbility). ArkTS modules go here.

## Known gaps

- Colour: SPEC_2025 is ported in `lib/theme/mcu2025/` and golden-tested against Android's vendored
  Kotlin MCU (generators in `test/theme/fixtures/generator/`). Only the 2025 variants are ported.
- Bar: only the idle pose (nothing playing). No press bulge, no Now Playing pill yet.
- Back: HarmonyOS side-swipe back has no progress API; system back commits through the same
  controller (Android's three-button path). Write this into the predictive-back skill before
  the first overlay.
