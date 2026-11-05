# Yoin for Mac

SwiftUI, macOS 26, Subsonic / Navidrome or Apple Music. Target `YoinMac` in `iosApp/project.yml`, bundle id `com.gpo.yoin.mac`, App Sandbox (network client).

```bash
cd iosApp && xcodegen generate
DEVELOPER_DIR=/Applications/Xcode.app/Contents/Developer xcodebuild -project Yoin.xcodeproj -scheme YoinMac \
  -destination 'platform=macOS' -derivedDataPath build/ddmac build
```

## What it shares with iOS

The Mac UI lives in `YoinMac/`. It compiles these iOS files as they are: `Model/Models.swift`, `Data/SubsonicMapping.swift`, `Data/CoverArtwork.swift`, `Data/Cache/AudioCache*.swift` + `SubsonicCacheKey.swift`, `Playback/PlayerEngine.swift`, `AudioOutput.swift`, `AVAudioOutput.swift`, `PlayableTrack.swift`, `Theme/Tokens.swift`, `TypeRoles.swift`, `CoverScheme.swift`, `Shapes.swift`, `PlatformBridge.swift`, `NowPlaying/WavyProgress.swift`. `PlatformBridge.swift` maps UIImage / NSImage and the colour components; `StreamConditions` (AVAudioSession) is iOS-only.

`ServerSession`, `LibraryStore` and the account list are not shared: they reach into Apple Music, Memories, downloads and cloud sync. The Mac has its own `MacSession` (one account, Keychain service `com.gpo.yoin.mac.subsonic`) and `MacLibrary` on the same `SubsonicClient` and mapping. `Yoin` colours / springs, `Font.gsf` and `YoinIcon` have a Mac copy in `YoinMac/Theme/MacTheme.swift`.

YoinShared has a `macos-arm64` slice; the Darwin Ktor engine and the Foundation actuals are in `shared/src/appleMain`.

## Shell

- Window: hidden title bar, traffic lights over the page. No sidebar.
- Bar: glass [Home][pill][Library]; with a detail column [Home][Library][pill][Play ▾][Shuffle], max 720.
- Detail: right column (45 : 55, 24 pt handle) from 840 wide, else pushed with a back button.
- Now Playing: closed → 420 panel → full window; straight to full when the panel leaves < 320 (< 744 with a column). Cover click = focus. Esc: focus → full → panel → closed, then search, then the detail page.
- Media keys / Control Center: `MacPlaybackSystem` (MPRemoteCommandCenter + MPNowPlayingInfoCenter).
- Lyrics: server (`lyricsFor`) first, then YoinShared `LyricsRegistry.autoFetch` (QQ → NetEase → LRCLIB).

## Accounts

`MacSession.profiles`: any number of Subsonic servers plus Apple Music, one in use (`activeId`), stored as one Keychain item (`com.gpo.yoin.mac.subsonic` / `profiles`). First launch with the list migrates the single-account item (`default`, left in place for older builds; deleted when that account is removed) and the old `activeSource` choice. Settings › Accounts: Switch, Remove, Add Account… (the sign-in page over the shell, Cancel goes back); Controls › Switch Account. A switch resets the player, library, lyrics and downloads' scope (`MacModel.switchAccount`).

## Offline downloads

The iOS `OfflineDownloads` (and `DetailDiskCache` for its pins) compile into the Mac; its AppModel rows stay iOS-only. `MacModel` sets `scope` (the signed-in Subsonic account), `sign` (stream url at the chosen quality, 320 by default) and the player's `downloadedFile`. Album right-click / detail action and song right-click: Download / Downloading n/m (cancel) / Remove download; a downloaded song shows `download_done` on its row. Library › Downloads lists them; Settings › Downloads: quality, metered networks (URLSession expensive / constrained), size, Remove All. Files: Application Support/YoinOffline (sandbox container).

## Mini player

`MacMiniPlayer` (window id `mini`, ⌥⌘M): cover, the spine beside it (title → artist → album → year, one at a time, every 5 s or on scroll; an item longer than the spine marquees instead of being cut), title / artist, prev · play · next, progress. Floats over other apps by default; Controls ▸ Float Mini Player on Top turns it off (`miniPlayerFloats`).

## Menu bar

`MenuBarExtra` (.window, Yoin `music_note` symbol): `MacMenuBarCard` — cover, title / artist, progress, prev · play · next, Open Yoin.

## Settings (⌘,)

Tabs: Accounts (switch / remove / add), Playback (streaming quality Original · 320 · 192 · 128 → `macStreamQuality`, the engine's `streamQuality`; mini player on top), Downloads (quality, metered networks, size, remove all), General (lyrics translations, appearance System / Light / Dark → `macAppearanceSetting`).

## Lyrics tools

Now Playing › Lyrics header: Translate (the source's own translation from the shared `LyricsRegistry`: the current source, else the next automatic source that serves the language, whose lyrics come with it) and Search (all providers; a pick is remembered per account and song, `lyricsChoice.<account>.<song>`). Gemini translation (needs a key) isn't on the Mac.

## Playlists, history, Home

Library › Playlists and the playlist page (Subsonic and Apple Music library playlists); the song menu's Add to Playlist (Subsonic). `HistoryStore` (iOS) records this Mac's plays per account; Home's Memories shelf reads it. Home › Edit orders and hides sections (`homeLayout`).

## Keys (Controls menu)

Space play / pause · ⌘→ / ⌘← next / previous · ⌘D like · ⇧⌘P Now Playing panel · ⌘L full window in / out · ⌘F focus (from closed: full window in focus) · ⌘K search · ⌥⌘M mini player · Esc back chain. ⌘↑ / ⌘↓ are left alone (no in-app volume). System full screen (⌃⌘F) is the window's own.

## QA launch arguments (Debug)

`-silentAudio YES` (muted), `-ApplePersistenceIgnoreState YES`, `-qaServer URL -qaUser U -qaPassword P` (signs in when signed out), `-macScreen library|album|artist|np-panel|np-full|np-focus|search`, `-macAppearance light|dark`, `-macWindowSize 1280x820`. Fake server: `python3 ~/Developer/Yoin-archive/promo-demo-2026-11-02/demo_subsonic.py 4977`.

## Apple Music

The iOS `AppleMusicSource`, `AppleMusicMapping` and `AppleMusicAudioOutput` compile into the Mac as they are, except playlist backup / restore (iOS-only, `#if os(iOS)`) and `MusicLibrary.add`, which macOS lacks: the Mac adds a song with the API's `POST /v1/me/library` (202 = accepted only; the heart waits for `isInLibrary`). Sign-in: "Use Apple Music instead" asks the system for access; `activeSource` in the defaults remembers it. Player output is the iOS `RoutingAudioOutput` (AVQueuePlayer / ApplicationMusicPlayer); MusicKit owns the Now Playing card while it plays. Heart = in the library (add only); no rating.

Needs, once, in the developer portal: an explicit App ID `com.gpo.yoin.mac` with the MusicKit App Service, and a signing profile for it (the wildcard `M75T74PTC4.*` can't carry MusicKit).

## Not in P0

Spotify, cloud sync, NeoDB, Gemini, Memories, Write / notes, Devices, playlists, stream-quality settings.
