# Continue On on HarmonyOS (应用接续)

Android 17's Continue On (`../../docs/continue-on.md`) hands what Yoin is playing to
another of the user's devices. HarmonyOS has the same idea built in as **应用接续**
(app continuation): with both devices signed in to the same HUAWEI ID, Bluetooth and
Wi-Fi on, and 设置 › 多设备协同 › 接续 turned on, the app running on one device shows
up on the other (the dock / taskbar's continuation icon, or the recent tasks), and
picking it there starts Yoin with the first device's data.

The Dart side is shared with Android's design rule for rule; only the transport is
HarmonyOS's own.

| Piece | Where |
| --- | --- |
| Cursor (what is handed over; the same keys and values as Android) | `lib/continueon/continue_on_cursor.dart` |
| Pause channel (local TCP line protocol, the same as Android) | `lib/continueon/continue_on_pause_channel.dart` |
| Both ends: offering, answering, the card's states, "Continue here" | `lib/continueon/continue_on_controller.dart` |
| The card | `lib/continueon/continue_on_card.dart` (in `main.dart`, over everything, the first-run landing included) |
| Platform seam | `lib/platform/continueon/continue_on_host.dart` (Windows: `NoContinueOnHost`) |
| ArkTS | `ohos/entry/src/main/ets/yoin/continueon/ContinueOnPlugin.ets`, `EntryAbility.ets` |
| Single songs read back | `MusicTrackLookup` (Subsonic `getSong`, Apple Music `songs/{id}`) |
| The account matched across devices | `lib/data/sync/identity/account_keys.dart` (AccountKeys) |

## Handing off (the device playing)

- `module.json5`: `EntryAbility` is `"continuable": true`. It starts **INACTIVE**
  (`setMissionContinueState`) and Dart turns it ACTIVE while a song is loaded, INACTIVE
  when the queue empties (Android's `setHandoffEnabled`). Dart also leaves a snapshot
  of the cursor with ArkTS each time the song, play / pause, a seek, the queue or the
  play mode changes. `onForeground` says the state again.
- When the user picks Yoin on the other device, the system calls
  `EntryAbility.onContinue(wantParam)` here. `ContinueOnHost.fillHandoff` asks Dart for
  a fresh cursor (1.5 s at most; it then carries a pause channel when the song plays),
  else uses the snapshot (no pause channel), and writes it into the wantParam as
  `yoin.continueOn.*`. It also sets
  - `ohos.extra.param.key.supportContinuePageStack = false`: Flutter has one ArkUI page;
  - `ohos.extra.param.key.supportContinueSourceExit = false`: Yoin keeps playing here
    until the person taps "Continue here" over there (owner 2026-11-08). The default
    would close the app here at once.
  It always answers AGREE (async `onContinue`, API 18+).
- The pause channel: as on Android, a local port with a one-time token, open while a
  song is loaded here. The addresses are those of the Wi-Fi / Ethernet networks
  (`@ohos.net.connection`, never a VPN's or mobile data's), IPv4 first, at most three.

## Taking over (the other device)

- The system starts `EntryAbility` with `launchParam.launchReason == CONTINUATION`
  (`onCreate` cold, `onNewWant` hot) and the cursor in `want.parameters`.
  `ContinueOnHost.deliverFrom` hands it to Dart (a cold start's waits for `ready`).
- A continuation start calls `onWindowStageRestore` instead of `onWindowStageCreate`.
  Cold, `EntryAbility` loads Flutter's page there as a normal start does; hot, the page
  is already up and nothing is loaded again.
- Nothing plays at once: the card (Android's `ContinueOnCard`) shows the song, "From
  <device>", where the other device is in it, and **Continue here** / **Not now**.
  "Continue here": switch to the same account here (matched by AccountKeys; with no key
  on one side, the only account of that service, else the active one) → ask the other
  device to pause → read the queue back (album / playlist / Liked songs in one go when
  the song is in it, else the song and the next 30 one by one) → play from where the
  other device stopped, paused if it was paused, with its repeat / shuffle → open Now
  Playing. No matching account: the card says so. The other device not reachable: this
  one plays anyway and the card says the other may still be playing (6 s).

Differences from Android: there is no cast or Spotify Connect playback on HarmonyOS to
rule out; the taking-over socket is not tied to the Wi-Fi under a VPN (Dart can't bind a
socket to a network), so a VPN on the taking-over device may swallow the PAUSE and the
card then says the other device may still be playing. No Tips card yet: Android's copy
names the tablet's taskbar, and HarmonyOS's needs its own words from the owner's copy
model.

## What needs two Huawei devices to verify

None of this can be tested on one device or in `flutter test` (which covers the cursor,
the pause protocol over loopback, the controller's every stage and the card). On two
HarmonyOS devices (e.g. the Mate 70 Air and the MateBook Pro S), **same HUAWEI ID**,
Bluetooth and Wi-Fi on, the same Wi-Fi network, 接续 turned on in both devices'
settings, the same release HAP installed on both:

1. **Offer follows the queue.** With nothing loaded, Yoin is not offered on the other
   device. Play a song: Yoin appears in the other device's dock / continuation entry.
   Clear the queue: it goes again.
2. **Cold start.** With Yoin closed on device B, pick it from the continuation entry while
   device A plays: B opens Yoin normally (Home draws, no blank window) and the card shows
   the song, "From <A's market name>", Playing and the position. A keeps playing.
3. **Continue here.** Tap it on B: A pauses, B plays the same song from where A stopped
   (within a second), opens Now Playing, and the card goes. The queue on B is A's (from
   an album / playlist: the whole of it, at the right song; else the song and what came
   next). Repeat / shuffle match A.
4. **Not now.** The card goes; A plays on.
5. **Paused on A.** Pause A, continue to B: the card says Paused; Continue here loads the
   song paused at that spot.
6. **Hot start.** With Yoin already open on B (on another song), continue from A: the
   card shows over whatever B shows (Now Playing included), and nothing is loaded twice
   (no flash, no second Flutter view).
7. **Moved on.** Continue to B, wait for A to go to the next song, then tap Continue here:
   B starts the song A is on by now, at A's position.
8. **Accounts.** Two accounts on B, A playing from the one B isn't using: the card names
   that account and Continue here switches to it. An account B doesn't have: the card
   says it isn't on this device and offers OK only.
9. **No local path.** Put A on mobile data only (or a guest Wi-Fi that isolates
   devices): B plays and the card says A may still be playing.
10. **Source keeps running.** After the hand-off A's Yoin is still running (the system did
    not close it: `supportContinueSourceExit` false) and its media card in 播控中心 shows
    the paused song.
11. **Permission.** Plain app continuation should need no permission beyond the
    `continuable` flag (`ohos.permission.DISTRIBUTED_DATASYNC` is for distributed data
    objects, which Yoin doesn't use). If Yoin never shows up on the other device, check
    hilog `YoinContinueOn` (domain 0x5909) on A for `handing off` and on B for
    `continued with a cursor`, and whether the system asks for that permission.
