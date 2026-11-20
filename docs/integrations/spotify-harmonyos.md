# Spotify on HarmonyOS (via the Android compatibility container)

Status (2026-11-19): feasibility measured on a HUAWEI Mate 70 Air (SUP-AL90, API 26)
with Spotify installed inside 出境易 / 卓易通, the Android compatibility container.
Everything below was observed on the device; nothing here is implemented in Yoin yet.

HarmonyOS has no Spotify App Remote SDK, so the Android path (`SPTAppRemote` driving the
local Spotify app) cannot be reused. The workable model is the one the Android build
already uses for Connect: **launch the Spotify app, then read and control it over the
Web API**.

## Launching it

The container registers its Android apps with the HarmonyOS want system, so a plain
`startAbility` opens Spotify:

```ts
const want: Want = {
  bundleName: 'com.spotify.music',
  abilityName: 'com.spotify.music.MainActivity',
};
context.startAbility(want);
```

- **`abilityName` is required.** With only `bundleName`, HarmonyOS opens a "choose how to
  open" chooser listing four Spotify entries, which is a dead end for a user tapping
  "Play" in Yoin.
- This is the OS-supported route, not a bypass: the system's own notification for the
  same app carries `WantAgentUtil: bundleNameInWant: com.spotify.music`, and ScenarioBoard
  reports it as `com.spotify.music` / `com.spotify.music.MainActivity`.
- Cold start works: after `aa force-stop com.easy.hmos.abroad` (the container's host
  app), `aa start -b com.spotify.music -a com.spotify.music.MainActivity` went straight
  into the Spotify player and the device reappeared in the account's Connect list.

## Controlling it

Measured with a PKCE token against the public Web API:

| Step | Call | Result |
| :--- | :--- | :--- |
| Device visible | `GET /v1/me/player/devices` | the phone is listed as a `Smartphone` device |
| Transfer playback | `PUT /v1/me/player` `{"device_ids":[…],"play":true}` | `204`, then that device reports `is_active: true` |
| Pause | `PUT /v1/me/player/pause` | `200`, `is_playing: false` |
| Next | `POST /v1/me/player/next` | `200`, track changes |

The phone's own now-playing bar followed every command, so the calls take effect inside
the container rather than only in Spotify's cloud state.

## What does not work

- **LAN discovery.** `_spotify-connect._tcp` browsing from the same network finds the
  Sonos speakers and a desktop Spotify but never the phone — the container blocks
  multicast (or sits behind NAT). Any design that assumes local discovery is out.
- **Reaching into the container.** There is no root, `/data` is unreadable, and the
  container's package manager is not visible to `bm dump -a`, so Yoin cannot inspect or
  drive the Android side directly.
- **Spotify Web Playback SDK inside ArkWeb.** Measured on a Mate 70 Air (OpenHarmony 7.0,
  ArkWeb 7.0.0.111, HuaweiBrowser 6.1.9.351): with a genuine secure context
  (`window.isSecureContext === true`), `com.widevine.alpha`, `com.microsoft.playready`
  and even `org.w3.clearkey` all reject with `NotSupportedError`. The EME API exists but
  no CDM is registered, so the SDK cannot initialise — and Spotify asks for Widevine
  specifically, never WisePlay. Do not offer "Web Playback SDK" in setup guidance; this
  provider path is Connect-control only.

  A trap worth recording: `navigator.requestMediaKeySystemAccess` is a `[SecureContext]`
  API, so on a plain `http://<lan-ip>` page it is simply *undefined* — which reads
  exactly like "unsupported". Always probe EME from `https://` or `127.0.0.1` (a `hdc
  rport` forward is enough) and print `window.isSecureContext` on the page.

## Device identity

The device id stayed the same across a container force-stop and cold relaunch, so it is
stable for one installation and will change on reinstall or account change. Match in this
order: cached id → `name` + `type: Smartphone` → ask the user to open Spotify once.

## Lifecycle

| Situation | Result |
| :--- | :--- |
| Backgrounded 90 s | still listed, still active |
| Container host force-stopped | still listed; `next` still returned `200` and changed the track; the phone kept playing |
| Full device reboot / container reclaimed under memory pressure | **not tested** |

The container runs its Android apps from `anco_service_broker`, independent of the host
app's foreground state, so the user does not have to keep Spotify on screen.

## Shape for Yoin

Yoin on HarmonyOS should be a **remote control**, not a player: device list, current
track, transport, volume, and playlist hand-off, with the audio produced by the Spotify
app (the same contract the Android build has for Spotify). Start the app, wait a second
or two, then look for the device; if it is absent, tell the user to check that 出境易 is
installed and Spotify is signed in — rather than pretending Yoin can play Spotify audio
natively.

## Reproducing

`tools/spotify_webapi.py` (PKCE + device list) and `tools/spotify_connect_scan.py` (mDNS
scan) live with the investigation notes; both are standard-library only. The device side
is plain `hdc`: `power-shell wakeup`, `aa start …`, `snapshot_display -f …`.
