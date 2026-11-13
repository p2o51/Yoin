# Pre-install QA script (iOS)

Run this before every install. It has two halves: the **automated pass**
(`scripts/qa-record.sh`: launch-arg states, stills and recordings, light and
dark) and the **hand pass** (interactions no launch arg can drive yet). Log
every finding in `docs/qa-log.md` (append; never overwrite) and keep clear
defects apart from design questions for 51.

## 0. Setup

```bash
cd iosApp
# own derivedData, own throwaway simulator (never one another session is using)
SP=build/qa; mkdir -p $SP
UDID=$(xcrun simctl create yoin-qa "iPhone 17" com.apple.CoreSimulator.SimRuntime.iOS-26-5)
DEVELOPER_DIR=/Applications/Xcode.app/Contents/Developer xcodebuild -project Yoin.xcodeproj \
  -scheme Yoin -destination "id=$UDID" -derivedDataPath $SP/dd build
# real data: the demo Subsonic (demo/demo); kill it afterwards
python3 ~/Developer/Yoin-archive/promo-demo-2026-11-02/demo_subsonic.py 4995 &
```

Audio is always silent (`-silentAudio YES` is added to every launch).

## 1. Automated pass

```bash
scripts/qa-record.sh -d $UDID -a $SP/dd/Build/Products/Debug-iphonesimulator/Yoin.app -o $SP/run
# a subset: scripts/qa-record.sh -d $UDID ... np album      (case names containing "np" or "album")
# iPad: same with an iPad Pro 13 udid
xcrun simctl delete $UDID   # when done
```

Output per appearance: `<case>.png` stills; for recorded cases `<case>.mp4`,
`<case>-frames/` (30 fps) and contact sheets `<case>-strip-NN.png`.
Recordings run at `-animationSpeed 0.5`.

How to read a clip quickly:

- **Hitches:** list recorded-frame gaps. The recorder writes a frame every
  ~25 ms even on a still screen, so a gap over ~70 ms (outside the launch) is
  the app not rendering.
  `ffprobe -v error -select_streams v -show_entries frame=pts_time -of csv=p=0 x.mp4 | awk '{if(NR>1&&($1-p)>0.07)print p" -> "$1; p=$1}'`
- **Flashes / snaps:** per-frame mean luminance; a one-frame jump of > 12
  (0–255) is a cut, not a spring.
  `ffmpeg -i x.mp4 -vf "fps=60,scale=160:-1,signalstats,metadata=print:key=lavfi.signalstats.YAVG:file=-" -f null -`
- **Look at every recorded frame** of a transition, not a resampled strip:
  `ffmpeg -ss A -to B -i x.mp4 -fps_mode passthrough -vf scale=300:-1 f%03d.png`, then tile them.

### Cases the driver covers (light + dark)

| Case | Launch args | Check |
|---|---|---|
| home, home-idle, home-scroll-end | `-screen home` / `idle` / `home-bottom` | bar forms (mini player vs Home/Library halves), last card clears the bar |
| library-* | `-screen library[-albums/-songs/-artists/-playlists/-favorites/-scroller/-idle]` | chip track, accent count (one: the selected tab; the on chip is the Library accent), scroller asleep/awake |
| search, search-results, search-empty | `-screen search -searchFocused YES`, `search-results`, `search-empty-results` | empty / no-results copy, field position |
| search-longpress (rec) | `-screen search-longpress` | field travels from the Library header to the Search header, never mid-screen |
| album, album-list, album-pullup-mid, album-page-mid/held, album-scrapbook | `-screen album`, `album-list -listScroll 160`, `album-mid`, `album -pageFrac 0.5`, `album-scrapbook -pageHold 1,0.75`, `album-scrapbook` | band, back circle legible at every p, cover flight |
| album-listscroll (rec) | `-screen album-list -listAutoScroll YES` | band blur over moving rows |
| artist, playlist, settings, landing | `-screen artist` / `playlist` / `settings` / `landing` | explicit back, header breathing |
| tips, tip | `-screen tips` (Settings › About › Tips), `-screen tip -tip LyricHighlight` (that tip's page; ids in Settings/Tips.swift, demos in Tips/TipIllustration.swift) | the demo phone loops (the touch dot taps, holds, drags; the scene answers), steps aside for 5 s once touched; the pager turns from a swipe that starts on the phone; Read / Next clear the bar; a tip without a demo opens on its title |
| np, np-short, np-touch, np-airplay, np-panel | `-screen np…` | lyrics under transport, held rating lift |
| np-about/note/lyrics-full (rec), np-about-tab, np-note-tab, np-focus (rec), np-short-focus, np-compose | `-npTab …`, `-npFocus YES`, `-npShortFocus YES`, `-npCompose words` (these fire 4 s after launch) | stage transition, tab switch |
| np-queue, song-menu | `-screen np-queue`, `-screen song-menu` | sheet content, depth behind (recede/dim/blur, no black) |
| np-open-close, np-drag-dismiss, np-morph-slow, np-focus-demo, np-about-cycle (rec) | `-npAutoCycle YES`, `-npScriptedDrag YES`, `-screen np-morph`, `-npFocusDemo YES`, `-npAutoCycle YES -npAutoCycleHold 5 -npTab about-full` | R3/R5: shell visible (blurred) on every frame, NP never a blank slab, no end-of-settle snap |
| srv-home, srv-np, srv-album | `-subsonicURL http://127.0.0.1:4995 -subsonicUser demo -subsonicPass demo` (+ `-historySeed demo`, `-serverPlay album:al-field -serverOpenNowPlaying YES`, `-serverRoute album:al-field`) | real covers, unrated, no lyrics, real queue |
| srv-memory-np, srv-memory-queue | the same sign-in + `-historySeed demo -memoriesShot al-lowtide -memoriesShotDiary YES -memoriesShotPlay YES` and `-memoriesShotNowPlaying YES` / `-memoriesShotQueue YES` | a memory's liner row played: Now Playing's header and the queue sheet name the memory's album (Playing from), not the page under Memories |
| spotify-pill, spotify-pill-under-np | `-screen home -spotifyElsewhereSeed YES`; `-screen np -spotifyElsewhereSeed YES -spotifyElsewhereSeedCloseNP 10` | another Spotify account's pill (made up) in Home's header; its 5 s count only once the header can be seen: under Now Playing it waits, and stays 5 s after Now Playing closes |
| srv-memories-switch | the same sign-in + `-historySeed demo -profilesScript "wait:8000;add:http://localhost:4995,spare;wait:2500;open:all-memories" -allMemoriesShotSwitch YES -homeOtherProfilesSwitchNoticeSeen NO` | a second account (spare) in use, All Memories open, the first "From demo" card tapped: the one-off switch notice comes out of that card ("To open …", Switch and open). `-homeOtherProfilesSwitchNoticeSeen NO` asks on every launch (Home's other-accounts shelf shares the flag); without it a confirmed notice never returns |
| srv-memories-lift | the same sign-in + `-historySeed demo -serverRoute all-memories -allMemoriesShotScroll 0` / `140` / `230` / `520` (iPad: `260` / `600`), or `-allMemoriesShotScrollSweep YES` recorded | All Memories scrolled: at rest the grid in place; past it the squares rise out of the grid (oldest first) and piece together as the top bar's mosaic of square pixels, a light veil under the back button; the sweep brings them home on the way back. Reduce Motion: the bar fades in where it ends up |
| srv-memories-wide | the same sign-in + `-historySeed promo -historySeedSongLookups YES -serverRoute all-memories` (+ `-allMemoriesShotScroll 1400` / `9000`, `-memoryLedgerView list -memoryLedgerSort name`) | iPad: stats large over a centred grid capped at 760, Timeline and Cards in 2–3 masonry columns; the month in the serif; covers alternate on a phone with text full width under them, song scores on dashed rules; song notes on soft patches; Northern Field Recordings named from its songs (`getSong`), the two songs the server lacks on one Song score card; the bar title fades in and turns white over the pixel bar |
| srv-memories-other | the same sign-in + `-historySeed demo -profilesScript "wait:8000;add:http://localhost:4995,spare;wait:3000;seed:demo;wait:5000;memories:open;wait:3500;memories:other;wait:1500"` (+ `;memories:diary`, `;memories:album` with `-homeOtherProfilesSwitchNoticeSeen NO`) | two accounts with memories (spare in use): the deck turned to demo's card, "From demo" under its buttons, read-only; Go to album asks through the switch notice |
| srv-lyric-count | the same sign-in + `-historySeed demo -lyricHighlightSeed YES -homeLyricCountShot 3` (or `-homeLyricCountSet 2`) | Edit Home with Lyric Cards moved first and its dots held open at 3 beside the ⌟ (label "3 cards", dots 2–3 filled, 3 larger); with `-homeLyricCountSet 2` the shelf drops to two cards, one Undo step. By hand: hold the ⌟ and pull down 40 pt to open, then each 36 pt down / up is a card more / less with a tick, release keeps it |
| account-card, account-card-reconnect, account-card-switch | two accounts (`-profilesScript "wait:6000;add:http://localhost:4995,spare;wait:3000;switch:0"` after the demo sign-in, once), then `-accountCardOpen home` / `library` (+ `-accountCardForceReconnect YES`, `-accountCardPick 1`) | the header avatar's short tap: the card grows out of the avatar (its colour, then the song sheet's glass and rows; the page behind steps back as under a sheet), contents late; the account in use, Manage accounts, the other accounts, Add account, Settings, Send feedback, Edit Home (Home only); with Reconnect forced, the dot on the header avatar and Reconnect beside Manage accounts; a pick colours its row, the card goes back into the avatar and the header shows the new account |
| format tags (not in the driver) | the srv args + `-serverRoute playlist:pl-commute -playlistDemo menu` (song sheet) or `-serverPlay album:al-field -serverOpenNowPlaying YES` (Now Playing); `-formatDemo flac,0,16,44100,6` (suffix, kbps, depth, Hz, channels) makes every song claim that format | a block per tag under the sheet's artist and after Now Playing's artist ("FLAC 16/44.1", "5.1"; the demo server's own songs read "WAV 44.1"); no row without tags (`-screen song-menu`) |
| Spotify can't play here (not in the driver) | `-spotifyFake <url> -spotifyIssueDemo install` or `premium`, with `-screen settings` for the card and `-settingsOpenSetup active` for the account's page (§4; the demo starts QA Mix's first song) | install: the simulator has no Spotify app, so the start is held with "Install the Spotify app to play Spotify tracks." and an Install Spotify button (it opens Spotify's App Store page; on a simulator Safari, which can't show it), the Spotify card's pill and its page's Status say "Install Spotify". premium: the same with "Spotify Premium is required for in-app playback.", no button, and "Premium required" on that account only. The toast sits above the phone's banner, not on it |
| artist without releases (not in the driver) | the srv args + `-serverRoute artist:ar-kiri -artistDemo empty` (the loaded page with its releases taken away; the demo server has no such artist) | "Discography" with "0 releases", then "No releases" in the muted body colour; no chips, no year in the header |
| ratings the server missed (not in the driver) | your own demo server on a spare port (`demo_subsonic.py 4997`): sign in with the srv args on that port and `-serverPlay album:al-field`; stop that server; relaunch with `-serverPlay album:al-field -serverRating 8.4` (the playing song rated as a released drag) | `Library/Application Support/Yoin/pending-ratings.json` in the app container lists the song under that account; start the server again and bring Yoin to the front (or relaunch): its log shows `/rest/setRating.view`, `getSong` reads 4 stars, and the file is gone |
| scrapbook Notes tab (not in the driver) | the srv args + `-screen album-scrapbook -serverRoute album:al-field -scrapNotesDemo rows` (or `all`, `edit`, `draft`, `plain`, `plain-draft`, `loading`); it chooses timed lyrics for the first song in the lyrics store and adds three notes to it | the first song's sheet opens on Notes: each note under its line with three lines either side, "· · ·" between passages, All lyrics ⇄ Notes only; a tap on a line: New note / Play from; on a note: Edit / Play from; the editor's buttons above the keyboard; `plain`: the notes alone with New note under them; `loading`: "Loading lyrics…" only while there are no notes |
| Start fresh put off (not in the driver) | `-screen settings -syncOpenPage YES -syncDemo fresh-held` (a stand-in state, no Drive: iosApp/docs/cloud-sync-ios.md) | Accounts: "demo" reads "Paused", and 4 s after launch "Changes waiting to upload" fades in under it while Keep syncing as before / Start fresh and the groups below slide down on the spring; both buttons stay |
| Handoff received (not in the driver) | the srv args + `-handoffDemo "subsonic\|s-field-03\|al-field\|95\|0"` (service\|song\|album\|seconds\|playing; it arrives 6 s after launch, as from another device), or `-handoffDemo "spotify\|x"` for an account this device lacks | the song from its album at 1:35, paused, Now Playing open (iPad: the panel when it fits); without the account a toast says so, with Settings. Sending (the App Switcher / Dock offer, the sender pausing when another device takes it) needs two devices on one Apple ID: not checked |

## 2. Hand pass (light and dark, iPhone then iPad)

No launch arg drives these yet; do them by hand (or add a DEBUG driver and
move the row up into the table above). Record each with
`xcrun simctl io <udid> recordVideo --codec h264 --force x.mp4` and check the
frames as above.

**Shell**
- [ ] Tap the mini player: opens; ⌄ closes; system edge-back closes; drag down closes; short drag springs back.
- [ ] Long-press the mini player: song menu sheet (no black flash, behind recedes).
- [ ] Home ⇄ Library tab: selection morphs inside the bar's glass; page cross-fades, no hard cut.
- [ ] Long-press Library: lands in Search with the field focused.
- [ ] Bar over light and over dark covers (scroll Library): accent and labels stay legible.

**Now Playing (compact)**
- [ ] PAUSE ⇄ PLAY (morph), Next, Previous (restart vs previous), repeat / shuffle mode cycle.
- [ ] Seek: drag the wave, scrub bubble (Google Sans Flex, legible both modes), release; tap-to-seek.
- [ ] Rating: drag (lift, 1.06, glass), release, relative drag from a set value, unrated → first rating, scrapbook Clear.
- [ ] Heart: on / off, Spotify-style rollback on failure.
- [ ] Cover tap → focus, tap again → back; lyric line moves as one object.
- [ ] Tabs Lyrics / About / Note; pull to the expanded stage and back.
- [ ] Queue pill (sheet: rows, reorder, swipe to remove, Clear; empty queue state), Devices (AirPlay), Write (composer, keyboard, save).
- [ ] Lyrics actions (expanded): Search, Translate, Select, Recenter; share sheet; edit sheet.
- [ ] About: Ask Gemini bar, answer, Retry / Settings buttons (no key, bad key, offline).
- [ ] Note page: add, edit, delete, long-press menu; moment anchor jumps.
- [ ] Paused state: wave and track still drawn, PLAY label.
- [ ] No lyrics / loading lyrics / failed lyrics states.

**Library + Search**
- [ ] Each filter chip on / off (lozenge slides; ✕ clears); sort row; fast scroller drag + bubble.
- [ ] Search: type, scope Apple Music / Library, type filters, no results, failed search, clear, cancel.
- [ ] Long-press a row: song menu → Add to playlist (sheet chain, new playlist alert), Go to album / artist.

**Detail pages** (album, artist, playlist)
- [ ] Push from Home, Library, Search, NP; pop with ← and with the edge swipe (shared back language).
- [ ] Album: pull the list up / down (p continuous; ← legible at every p), page 1 ⇄ 2 swipe, edge-back from page 2, Play split + menu.
- [ ] Scrapbook: rate a track (sheet), Clear, notes, album note.
- [ ] Artist: follow star, Most Played, releases. Playlist: ⋮ menu, rename, delete.

**Settings**
- [ ] Server sign-in (empty, wrong password, success), playback quality expander, Apple Music connect, AI features expander + key field + language menu, Storage, Cloud sync page (sign-in, sync now, delete cloud data alert).

**Every screen:** Dynamic Island and home indicator never cover content or
controls; no SF Symbols (YoinIcon only); Google Sans Flex everywhere (scrapbook
serif / mono are intentional); labels, not sentences; one accent per layer;
glass only where content moves underneath.

## 3. iPad

Run the automated subset on an iPad Pro 13 (`home library album settings np
search srv`), portrait and landscape; check the Pad shell, split columns and
NP panel.

## 4. Spotify without a Spotify account (fake Web API)

Debug builds only. `scripts/fake_spotify.py` is an in-memory Spotify Web API (no network, no covers,
no audio); `-spotifyFake <url>` signs in a stand-in account (`yoin-qa`, "Yoin QA") with dummy tokens
that never expire and points every Web API call at it. App Remote and the Spotify app are never
touched: a play start goes nowhere, and on a simulator (no Spotify app) it is held with the Install
Spotify banner, as on a phone without Spotify. Always pair it with `-silentAudio YES`.

```sh
python3 scripts/fake_spotify.py                      # :4997; Ctrl-C to stop (state resets on restart)
xcrun simctl launch $UDID com.gpo.yoin -silentAudio YES -spotifyFake http://127.0.0.1:4997
```

The account: 350 Liked Songs (offset paging, so Songs reads past its first 300), 12 saved albums,
6 followed artists, and four playlists: **QA Mix** (yours; holds "Satellite Quiet" twice),
**Road Trip** (yours), **Shared Jam** (someone else's, collaborative) and **Editorial Picks**
(someone else's, followed: its songs answer 403, as for a new Client ID since 2026-02). The fake keeps
the Development Mode limits (search and artist albums refuse `limit` > 10) and takes the writes
(create, rename, add, positional remove with `snapshot_id`, me/library save / follow / unfollow).
Inspect it with curl, e.g. `curl -H 'Authorization: Bearer x' 127.0.0.1:4997/playlists/pl_owned/items`.

Walk-throughs (UI tests in `YoinSpotifyQA`, its own scheme, never part of the Yoin tests): Library
Songs to the foot and Playlists, Search Library | Spotify with the Playlists filter reading three
pages, an owned playlist's ⋮ (Cloud backup, Rename, Delete), the followed one (no ⋮, the "Spotify only
shows…" line, 25 tracks), a song's Add to playlist (owned + collaborative only), removing the first
"Satellite Quiet" from QA Mix (the server keeps the second), Settings › Cloud sync › Playlist cloud
backup with Spotify as source and restore destination, and the Install Spotify banner with its card
and its button (`testInstallSpotifyWhenTheAppIsMissing`; the button's step needs the network). Each
step writes a screenshot and the element tree to `/tmp/yoin-spotify-qa` (`TEST_RUNNER_YOIN_QA_OUT` to
change; `TEST_RUNNER_YOIN_FAKE_SPOTIFY=http://127.0.0.1:<port>` for a fake on another port).

```sh
python3 scripts/fake_spotify.py &
YOIN_SKIP_SHARED_BUILD=1 xcodebuild test -project Yoin.xcodeproj -scheme YoinSpotifyQA \
  -destination id=$UDID -derivedDataPath /tmp/yoin-dd-<topic>
# one: -only-testing:YoinSpotifyQA/SpotifyQAUITests/testRemoveDuplicateOccurrence
```

Restart the fake between runs that write (a restore adds a playlist; a removal shortens QA Mix). The
stand-in account stays on the simulator after a fake launch; launched without the flag it would ask
the real Spotify with dummy tokens and turn to "Sign in again": use a throwaway simulator.

Another lane's fake may hold :4997: start yours with `--port <n>`, launch with `-spotifyFake
http://127.0.0.1:<n>`, and give the UI tests `TEST_RUNNER_YOIN_QA_FAKE=http://127.0.0.1:<n>` (and
`TEST_RUNNER_YOIN_QA_OUT=<dir>` for where the screenshots go).

### The fake's Connect player (Spotify starts, without Spotify)

The fake also keeps a silent Spotify Connect: the account's devices, the active one, the list a start
handed it and a clock that runs through it. `-spotifyFakePlayer YES` beside `-spotifyFake` lets the
Spotify output run its starts, reads and commands against it in a `-silentAudio YES` launch (without it
a silent launch starts nothing, as always). App Remote stays off, so on an iPhone or iPad only the
remote path (a device that is playing) can be seen; a Mac's whole path can (section 5).

```sh
python3 scripts/fake_spotify.py --device "QA Mac:Computer" --device "Pixel 11 Pro:Smartphone"
curl -X POST 127.0.0.1:4997/_qa/devices -d '{"devices":[{"name":"QA Mac","type":"Computer"}]}'   # the list, replaced
curl -X POST 127.0.0.1:4997/_qa/play -d '{"device":"Pixel 11 Pro","track":"tr0003","playing":true}'  # played in Spotify there
curl -X POST 127.0.0.1:4997/_qa/stop     # nothing active
curl -X POST 127.0.0.1:4997/_qa/reset    # no devices, nothing active, the command log empty
curl 127.0.0.1:4997/_qa/player           # devices, the active one, its state, the commands it was sent
```

With `-spotifyFake`, `-serverPlay album:<id>` and `-serverRoute …` act on the stand-in account (its
ids: `-serverPlay album:al00 -serverOpenNowPlaying YES`).

A device spec may add `"noId": true` (Spotify names it with `id: null`), `"restricted": true` (a
transfer answers 403) and `"hidden": true` (missing from `me/player/devices` while `me/player` names
it): together, a Sonos Spotify plays on. `-npDevices YES` opens Now Playing's Devices. The sheet lists
that speaker first with Switch through Spotify (a tap opens `spotify:`), keeps every device it has
seen for 30 days as Recently used, and reads the list again when Yoin comes back to the front.
Two launches show it: one with `Kitchen` listed, then `_qa/devices` without it and the Sonos playing.

## 5. The iPad app on a Mac

The same target runs on a Mac as "Designed for iPad". Rules that differ there hang on one flag,
`Platform.current.isMac` (`App/Platform.swift`); nothing else reads `ProcessInfo.isiOSAppOnMac`.

- Compile check, never run (the debug build shares its bundle id and data with the installed copy):
  `xcodebuild build -project Yoin.xcodeproj -scheme Yoin -destination "platform=macOS,variant=Designed for iPad" -derivedDataPath build/dd-mac -allowProvisioningUpdates`
- Stand in for a Mac: `-platformMac YES` on the iPad simulator (debug builds), or
  `Platform.$standIn.withValue(.mac) { … }` in a test.
- The window stops at 360 × 480 (`App/WindowFloor.swift`: one phone column wide, and the height under
  which Android switches to the landscape handset's shell, a form iOS doesn't have). iPad windows keep
  the system's minimum. To see the shell at that size:
  `-padWindowWidth 360 -padWindowHeight 480 -padWindowClass compact -screen home|np|album|library`.
- Not checked on a real Mac window yet: that the system holds the drag at the floor, and what a window
  restored smaller than the floor does.
- Right-click (a mouse's right button, a two-finger click, Control-click; iPad with a pointer too) on a
  song row, the Now Playing cover or the mini player opens the song's sheet, the one the long press
  opens, and never plays, opens Now Playing or focuses the cover (`Theme/SecondaryClick.swift`,
  `onSecondaryClick`; attach it inside the view's tap). Touch is unchanged. Albums, playlists and
  artists have no long-press actions on Android or iOS, so a right-click there does what a click does.
  On the iPad simulator, `PadLayoutShotsUITests` drives it with the steps `rightclick:x,y`,
  `ctrlclick:x,y`, `click:x,y` and `hover:x,y` (`press:x,y,s` is a touch hold; on the simulator it
  plays the row instead of opening the sheet, with or without this change).
- Hover (a pointer; never a finger, never on an iPhone; `Theme/PointerHover.swift`): song rows, the
  expanded lyric lines, the bar's pill and the split handle wear the M3 hovered state layer (the
  content colour at 8 %, on the effects spring); the seek bar's unplayed track deepens by the same
  8 %. The split handle shows iPadOS's resize pointer (a vertical beam with an arrow either side;
  SwiftUI's `columnResize` is macOS-only), and its region passes every touch to the drag. The
  simulator's UI tests deliver no hover (`hover:x,y` changes nothing), so `-hoverPreview YES`
  (debug, iPad) shows every hover state at once for screenshots. Not checked on a real pointer or
  a real Mac: the hover itself, and what cursor the Mac draws for the handle's pointer style.
- Drag and drop (`Library/YoinDrag.swift`, the native Mac's `MacDrag`): song rows (Library, Search,
  album and playlist pages) and Library's album and playlist cells and playlist rows drag out as a
  `com.gpo.yoin.item` (other apps get the title over the artist as plain text). Drops: Now Playing's
  Queue pill and its wide-layout queue icon (after what's queued), the queue sheet (a row: right
  before it), and a writable playlist's cell or row (the song sheet's Remove rule; "Added to …").
  A drag from another account (or another run) drops nothing. The target wears M3's dragged state
  layer (16 %, effects spring) while a drag is over it; `-dropPreview YES` (debug, iPad) lights every
  target for screenshots. A pointer drags at once; a finger on a song row gets its long press (the
  sheet) and never lifts it, while album and playlist cells lift after a hold. `PadLayoutShotsUITests`
  drives it with `clickdrag:x,y,x2,y2` (pointer) and `lift:x,y,x2,y2` (touch). Checked on the iPad
  simulator against the demo server: album cell → playlist cell (touch), song row → playlist cell
  and song row → Queue pill (pointer). Not checked on a real Mac.

### Spotify on a Mac

No App Remote there (the desktop app doesn't speak it; `SpotifyRemote` is off on a Mac). A start reads
`me/player`, then the account's devices when nothing plays, and goes (`SpotifyAudioOutput.macTarget`,
the native Mac's `MacSpotifyOutput.decide`; 51, 2026-11-06):

1. to the device that is playing, as its remote (Now Playing's Devices names it);
2. else to this Mac's own Spotify app, by its device id (never named: Devices just says Devices);
3. else nowhere: the banner says Spotify isn't open on this Mac, with Open Spotify (it opens `spotify:`,
   nothing plays) and Retry. A paused or idle device elsewhere never takes the start, and no other
   computer does.

This Mac's device is the `Computer` whose name is the Mac's (`SpotifyThisDevice`). An iPad app can't
read the computer's name, so it compares with the host name, both folded to plain letters and digits
("51的Mac mini" and `51deMac-mini.local` agree), and with whatever UIKit calls the device.

Stand-in run on the iPad (or iPhone) simulator; `-spotifyThisMac` names the Mac the fake lists:

```sh
python3 scripts/fake_spotify.py --port <n> &
xcrun simctl launch $UDID com.gpo.yoin -silentAudio YES -platformMac YES -spotifyFake http://127.0.0.1:<n> \
  -spotifyFakePlayer YES -spotifyThisMac "QA Mac" -serverPlay album:al00 [-serverOpenNowPlaying YES]
```

- no device named QA Mac in the fake: the banner; `_qa/player` shows no command (also with a phone
  paused and active);
- QA Mac listed: `PUT me/player/play device_id=qamac`, Now Playing runs on the fake's clock;
- another device playing (`_qa/play`): the start goes to it, and the Devices pill reads its name.

`YoinSpotifyQA/SpotifyQAUITests/testStartOnAMac` walks the three with real taps (banner, Retry after
the device joins, the next song on the phone that started playing) and checks the fake's command log.

Not checked on a real Mac: that the host name (or UIKit's name) matches the name Spotify lists the Mac
under, that `spotify:` opens the desktop app from the iPad app, and everything against real Spotify.
