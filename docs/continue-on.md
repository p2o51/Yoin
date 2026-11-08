# Continue On (Android 17 cross-device handoff)

Android 17 (API 37) lets an app hand what it is doing to another of the
user's devices: `Activity.setHandoffEnabled`, `onHandoffActivityDataRequested`,
`HandoffActivityData` (framework `android.app`, no Jetpack library). At launch
Google supports phone → tablet; the tablet shows the offer at the right end of
its taskbar (desktop windowing). Both devices need the same Google account,
Bluetooth, the same Wi-Fi, and Settings › Connected devices › Connection
preferences › Cross-device services › Continue activity › Tasks.

Design note and the owner's decisions: Notion 公文包, "Yoin 接入 Android 17
Continue On（跨设备接力）方案".

## What Yoin hands over

`player/continueon/ContinueOnCursor` — a light cursor in the hand-off's
extras (Android caps them below 50 KB, and they must not carry credentials):
provider, the account's cross-device id (`AccountKeys`), the track and a
window of at most 200 queue ids around it, position and when it was read,
play mode, the album / playlist / Liked songs it came from, the device's
model name, and a pause channel (below). Lyrics need nothing: they follow the
position.

The shell and the album / artist / playlist pages offer the hand-off while a
song is loaded here (not while casting, nor while Spotify plays on another
Connect device). Every hand-off opens `MainActivity` on the other device.

## The other device

Nothing plays at once (owner 2026-11-08): `ContinueOnCard` shows the song,
where the other device is in it, and "Continue here". Only that tap:

1. asks the device that handed off to pause (Subsonic, Apple Music);
2. switches to the same account here (matched by `AccountKeys`; with no key
   on one side, the only account of that service, else the active one);
3. reads the queue back: the album / playlist / Liked songs in one go when the
   song is in it, else the song and the next 30 one by one (`getTrack`);
4. plays from where the other device stopped and opens Now Playing.

No matching account: the card says so. Spotify needs no pause: playing on
this device moves Spotify Connect here, and the phone stops by itself.

## The pause channel

Android sends nothing back to the device that handed off, so Yoin does it on
the local network the two devices already share. When the system asks for a
hand-off while something plays, `ContinueOnPauseServer` opens a local port
for two minutes and the cursor carries the device's Wi-Fi addresses, the port
and a one-time token. "Continue here" sends one line, `PAUSE <token>
<trackId>`; the device pauses and answers `OK <positionMs>`, and the other
side starts from that exact position. The port closes after one good pause,
five wrong tokens, or two minutes. When it can't be reached (a router that
isolates its devices), this device plays anyway and the card says the other
one may still be playing.

Yoin targets API 36. Targeting 37 brings Android 17's local network
permission, which this channel (and DLNA) will then need.
