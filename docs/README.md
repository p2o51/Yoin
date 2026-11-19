# Yoin Documentation Index

Yoin is a multi-provider, multi-platform music client supporting **Subsonic / OpenSubsonic** (including Navidrome), **Spotify**, and **Apple Music** across **Android**, **iOS & iPadOS**, **macOS** (via iPad-on-Mac multiplatform), and **HarmonyOS**.

> **Repository Topology**
> - **`main`** unifies all four platform modules and top-level docs: Android (`app/`), HarmonyOS (`harmony/` and `harmony/docs/`), Kotlin Multiplatform `:shared` (`shared/`), Apple (`iosApp/` and `iosApp/docs/` for iOS, iPadOS, and iPad-on-Mac), and cross-platform docs (`docs/`).
> - **Native `YoinMac` is frozen.** macOS builds run the `iosApp/` target under "Designed for iPad" (`Platform.current.isMac`).

---

## Architecture Map

```text
Yoin/
├── docs/                          # Product truth, multi-platform specs & glossary (main)
│   ├── README.md                  # You are here
│   ├── design.md                  # Canonical UI, motion, Lyrics, Memories, AI & Home spec (0.6.3+)
│   ├── platforms.md               # 4-platform architecture, capability matrix & known limits
│   ├── adaptive-principles.md     # Window classes, feedDensity & responsive column rules
│   ├── cloud-sync.md              # Google Drive appDataFolder sync across platforms
│   ├── continue-on.md             # Android Continue On / Apple Handoff / HarmonyOS 应用接续
│   ├── haptic-feedback.md         # Multi-platform tactile system & vibration compositions
│   ├── playlist-transfer.md       # Cross-provider playlist cloud backup & restore
│   ├── spotoolfy-migration.md     # Spotoolfy archive import design
│   ├── ci-cd.md                   # Signing, versioning & release automation
│   ├── privacy-policy-draft.md    # Privacy policy reference (2026-11-13 draft)
│   ├── i18n/zh-glossary.md        # Canonical Simplified Chinese UI terminology & copy bounds
│   ├── integrations/              # Provider alignment, Apple Music Android & export specs
│   └── perf/yoinperf-logging.md   # YoinPerf trace logging guide
├── harmony/
│   ├── README.md, AGENTS.md       # HarmonyOS Flutter + ArkTS structure & rules
│   └── docs/                      # HarmonyOS-specific architecture, windows & port specs (main)
├── shared/                        # KMP core: MCU, tokens, Subsonic, Lyrics, Cloud Sync, Transfer
│   └── ...                        # Shared across Android & Apple on main
└── iosApp/
    └── docs/                      # Apple platform specs, fidelity docs & Codex reviews (main)
```

---

## Top-Level Documentation (`docs/`)

### Product, Platforms & Adaptive UI

| Document | Scope | Status |
| :--- | :--- | :--- |
| [design.md](design.md) | Canonical product design truth (Yoin 0.6.3+): MD3 Expressive, motion springs, Predictive Back, Now Playing, Lyrics pipeline, Memories & NeoDB Voices, Online AI v2 & On-Device AI, Home sections, ReplayGain, shortcuts | Current (0.6.3+) |
| [platforms.md](platforms.md) | 4-platform architecture (`:shared` KMP, Android, iOS/iPadOS/macOS, HarmonyOS), full feature & provider parity matrix, security/storage per OS, platform limits | Current (0.6.3+) |
| [adaptive-principles.md](adaptive-principles.md) | Responsive design principles, window size classes, `feedUnits` (`feedDensity`), column layouts & edge-bleed shelves | Current |
| [haptic-feedback.md](haptic-feedback.md) | Semantic `YoinHaptics` triggers, lyric translation wave pluck, Memories Award composition beats, and iOS/HarmonyOS/desktop parity | Current (0.6.3+) |

### Continuity, Cloud Sync & Migration

| Document | Scope | Status |
| :--- | :--- | :--- |
| [cloud-sync.md](cloud-sync.md) | Google Drive `appDataFolder` sync protocol, per-platform Drive clients (`SyncEngine` / `CloudSyncCore`), identity binding & AI/credential exclusions | Current (0.6.3+) |
| [continue-on.md](continue-on.md) | Cross-device handoff: Android 17 `ContinueOnPauseServer`, Apple Handoff (`com.gpo.yoin.playing`), HarmonyOS 应用接续 (`ContinueOnPlugin.ets`) | Current (0.6.3+) |
| [playlist-transfer.md](playlist-transfer.md) | Cross-provider playlist snapshots in Drive, fuzzy `TrackMatcher`, `RestoreReview`, Live Updates / Live Activities, Spotify 2026-02 / Apple limits | Current |
| [spotoolfy-migration.md](spotoolfy-migration.md) | Spotoolfy export/archive migration path into Yoin | Current |
| [integrations/spotoolfy-export-format.md](integrations/spotoolfy-export-format.md) | Export schema specification from Spotoolfy | Reference |

### Search & memories

| Document | Scope | Status |
| :--- | :--- | :--- |
| [search.md](search.md) | What library search may return: Memories and notes as results, cross-account ledger scope (`#178`), per-type caps, ordering and badges | Current (2026-11-19) |

### Providers, Terminology & Operations

| Document | Scope | Status |
| :--- | :--- | :--- |
| [integrations/provider-alignment.md](integrations/provider-alignment.md) | Subsonic/OpenSubsonic × Spotify × Apple Music 0.6.3 capability matrix across all 4 platforms + historical 2026-10-01 audit appendix | Current (0.6.3+) |
| [integrations/apple-music.md](integrations/apple-music.md) | Android MusicKit SDK setup, 2-RT album open, RELRO 16 KB & large catalog ID ASM fixes, JavaCPP guard | Current |
| [i18n/zh-glossary.md](i18n/zh-glossary.md) | Canonical 2026-11-18 Simplified Chinese glossary, UI navigation labels, forbidden terms & factual/privacy copy rules | Current (2026-11-18) |
| [ci-cd.md](ci-cd.md) | Build variants, signing secrets, release tags & workflow automation | Current |
| [privacy-policy-draft.md](privacy-policy-draft.md) | Privacy policy baseline (2026-11-13); live website updates are tracked against `yoin-site` | Reference |
| [perf/yoinperf-logging.md](perf/yoinperf-logging.md) | Instrumentation format and trace interpretation for `YoinPerf` | Current |

---

## Platform-Specific Documentation

### HarmonyOS (`harmony/docs/` on `main`)

| Document | Focus |
| :--- | :--- |
| [harmony/README.md](../harmony/README.md) & [harmony/AGENTS.md](../harmony/AGENTS.md) | Flutter + ArkTS directory map, Windows dev-loop seam, build & test commands |
| [harmony/docs/cloud-sync.md](../harmony/docs/cloud-sync.md) | Loopback OAuth PKCE, `yoin-sync.json.gz`, OHOS platform notes & no-Spotify caveat |
| [harmony/docs/continue-on.md](../harmony/docs/continue-on.md) | OHOS `ContinueOn` ability handoff & TCP pause channel |
| [harmony/docs/applemusic.md](../harmony/docs/applemusic.md) | HarmonyOS Apple Music WebView MusicKit JS auth + Music API playback path |
| [harmony/docs/backup.md](../harmony/docs/backup.md) | OHOS `BackupExtensionAbility` integration & playlist backup behavior |
| [harmony/docs/neodb.md](../harmony/docs/neodb.md) | HarmonyOS NeoDB client & Asset Store Kit token handling |
| [harmony/docs/windows.md](../harmony/docs/windows.md) | Windows 2-in-1 / desktop development runner & keyboard/windowing adapter |
| `harmony/docs/specs/` (`home.md`, `library.md`, `nowplaying.md`, `playback.md`) | HarmonyOS port fidelity specifications derived from Android components |

### Apple & KMP `:shared` (`iosApp/docs/` on `main`)

Apple and KMP specs under `iosApp/docs/`:

- **Shared Core & Sync**: `iosApp/docs/shared-kmp.md`, `iosApp/docs/shared-data.md`, `iosApp/docs/cloud-sync-ios.md`, `iosApp/docs/symbols-ios.md`
- **iPadOS & macOS**: `iosApp/docs/ipad.md` (covers 2-column / 3-column shells, trackpad hover, keyboard menu `YoinCommands`, and multiplatform "Designed for iPad" on macOS while `YoinMac` stays frozen)
- **Playback & Spotify Audits**: `iosApp/docs/spotify-background.md`, `iosApp/docs/spotify-audit-1115.md`, `iosApp/docs/np-gaps-1116.md`
- **Rendering, Glass & Fidelity**: `iosApp/docs/glass-layers.md`, `iosApp/docs/glass-hierarchy-gemini.md`, `iosApp/docs/gradient-parameters.md`, `iosApp/docs/wave-progress.md`, `iosApp/docs/fidelity-home-np.md`, `iosApp/docs/fidelity/*.md`
- **QA & Code Reviews**: `iosApp/docs/codex-review*.md`, `iosApp/docs/codex-verdicts/*.md`, `iosApp/docs/qa-log.md`, `iosApp/docs/qa-script.md`

### Icons (`../yoin-symbols`)

All UI icons come from the sibling repository **`../yoin-symbols`** (`p2o51/yoin-symbols`):
- Android compiles them directly via Gradle `includeBuild("../yoin-symbols")` as `com.gpo.yoin.symbols.YoinSymbols.*`.
- Apple consumes generated SF Symbolsets (`iosApp/YoinWidgets/WidgetSymbols.xcassets/` and app symbol assets — see `iosApp/docs/symbols-ios.md`).
- Never draw custom SVG/Vector paths for standard UI icons inside Yoin; add missing icons in `yoin-symbols` first.

---

## Historical & Archival Notes

The subdirectories and dated files below are **historical records preserved for provenance**. Do not treat them as current product truth when they conflict with `docs/design.md` or `docs/platforms.md`:

- **`docs/handoff/`** — Point-in-time design handoffs, UI copy audits, interactive HTML/JS showcase prototypes (`memories-showcase/`, `dissolve-reference/`), and feature kickoff notes (`home-edit-mode/`, `handoff-2026-11-14.md`). Relevant finalized decisions have been folded into `docs/design.md`.
- **`docs/validation/`** — Dated hardware/feature validation reports and structured evidence (`dlna-2026-10-20.md`, `yoin-playlist-*-2026-10-20.md`).
- **`docs/temp/`** — Early design scratchpads (`neodb-rating-memory-design.md`, `qq-lyrics-endpoint-research-handoff.md`); canonical rules now live in `docs/design.md`.
- **`docs/icons/`** — First-round local SVG generator & preview scripts, superseded by `../yoin-symbols`.
- **`docs/rive-prototypes/`** — Early animation/Rive HTML explorations, superseded by Compose/SwiftUI/Shader implementations.
- **`docs/animation-refactor-plan.md`**, **`docs/motion-audit-matrix.md`**, **`docs/integrations/provider-alignment-validation-20261001.md`**, **`docs/release-0.5-closed-test.md`** — Completed refactor plans, early integration checks, and 0.5 launch checklist retained for history.
