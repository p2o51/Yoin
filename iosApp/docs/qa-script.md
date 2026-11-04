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
| np, np-short, np-touch, np-airplay, np-panel | `-screen np…` | lyrics under transport, held rating lift |
| np-about/note/lyrics-full (rec), np-about-tab, np-note-tab, np-focus (rec), np-short-focus, np-compose | `-npTab …`, `-npFocus YES`, `-npShortFocus YES`, `-npCompose words` (these fire 4 s after launch) | stage transition, tab switch |
| np-queue, song-menu | `-screen np-queue`, `-screen song-menu` | sheet content, depth behind (recede/dim/blur, no black) |
| np-open-close, np-drag-dismiss, np-morph-slow, np-focus-demo, np-about-cycle (rec) | `-npAutoCycle YES`, `-npScriptedDrag YES`, `-screen np-morph`, `-npFocusDemo YES`, `-npAutoCycle YES -npAutoCycleHold 5 -npTab about-full` | R3/R5: shell visible (blurred) on every frame, NP never a blank slab, no end-of-settle snap |
| srv-home, srv-np, srv-album | `-subsonicURL http://127.0.0.1:4995 -subsonicUser demo -subsonicPass demo` (+ `-historySeed demo`, `-serverPlay album:al-field -serverOpenNowPlaying YES`, `-serverRoute album:al-field`) | real covers, unrated, no lyrics, real queue |

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
