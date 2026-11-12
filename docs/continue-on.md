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

1. switches to the same account here (matched by `AccountKeys`; with no key
   on one side, the only account of that service, else the active one). A
   switch that fails stops here, with the other device still playing, and
   its error is acknowledged so Settings doesn't open on it later;
2. asks the device that handed off to pause (Subsonic, Apple Music). Its
   answer names the song it is on by then and where in it;
3. reads the queue back from that song: the album / playlist / Liked songs in
   one go when the song is in it, else the song and the next 30 one by one
   (`getTrack`). A song that can't be read here falls back to the cursor's;
4. plays from where the other device stopped and opens Now Playing. A song
   that was paused there loads paused here (`PlaybackManager.play(startPaused)`),
   also when this device was playing something else.

No matching account: the card says so. Spotify needs no pause: playing on
this device moves Spotify Connect here, and the phone stops by itself.

The cursor can be old. The system asks a window for its hand-off data as the
window goes to the background and keeps the answer, so with the phone's
screen off the cursor names the song and the spot of that moment. That is
why step 2 asks, and why the card's own line can be behind. A cursor the
other device can't be asked about is caught up by the clock for ten minutes
at most; past that the song starts where the card shows it. Spotify has no
pause channel, so there the tablet starts the cursor's song.

A task reopened from Recents is started again with the intent that first
started it, cursor included; `ContinueOnWindows.handoffExtras` leaves those
out (`FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY`).

## The pause channel

Android sends nothing back to the device that handed off, so Yoin does it on
the local network the two devices already share. When the system asks for a
hand-off while something plays, `ContinueOnPauseServer` opens a local port
and the cursor carries the device's Wi-Fi addresses, the port and a one-time
token. "Continue here" sends one line, `PAUSE <token> <trackId>`; the device
pauses and answers `OK <positionMs> <trackId>` with the song it is on by
then, and the other side starts that song from that exact position. The port
closes after one good pause, five wrong tokens, an account change, or the
first two-minute check that finds no song loaded here any more (or one cast
elsewhere): the system may hand a cursor over long after it read it. When
the port can't be reached (a router that isolates its devices), this device
plays anyway and the card says the other one may still be playing.

The addresses are those of the Wi-Fi or Ethernet itself
(`ConnectivityManager.localNetwork`). With a VPN up, the network in use is
the VPN, whose addresses are the tunnel's; the device taking over ties its
socket to its own Wi-Fi the same way, so its VPN doesn't swallow the PAUSE.

`MainActivity` is exported, so a cursor's extras can come from any app, not
only from the system. Nothing plays before the tap, and the pause channel of
a cursor is used only when its hosts are written-out addresses (no name is
looked up) and its token is in the alphabet Yoin makes tokens in, so a made-up
cursor can't aim the PAUSE line at another host or add lines to it.

The Tips card (`HomeBubbleTip.ContinueOn`) is listed only on Android 17 and
later (`HomeBubbleTip.available`).

Yoin targets API 36. Android 17 restricts local network access for apps that
target it; moving to target 37 will need that permission for this channel
(and for DLNA).
