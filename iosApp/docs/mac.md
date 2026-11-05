# Yoin for Mac (P0)

SwiftUI, macOS 26, Subsonic / Navidrome only. Target `YoinMac` in `iosApp/project.yml`, bundle id `com.gpo.yoin.mac`, App Sandbox (network client).

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

## Keys (Controls menu)

Space play / pause · ⌘→ / ⌘← next / previous · ⌘D like · ⇧⌘P Now Playing panel · ⌘L full window in / out · ⌘F focus (from closed: full window in focus) · ⌘K search · Esc back chain. ⌘↑ / ⌘↓ are left alone (no in-app volume). System full screen (⌃⌘F) is the window's own.

## QA launch arguments (Debug)

`-silentAudio YES` (muted), `-ApplePersistenceIgnoreState YES`, `-qaServer URL -qaUser U -qaPassword P` (signs in when signed out), `-macScreen library|album|artist|np-panel|np-full|np-focus|search`, `-macAppearance light|dark`, `-macWindowSize 1280x820`. Fake server: `python3 ~/Developer/Yoin-archive/promo-demo-2026-11-02/demo_subsonic.py 4977`.

## Not in P0

Apple Music, Spotify, cloud sync, NeoDB, Gemini, Memories, mini player, menu bar extra, Write / notes, Devices, playlists, offline downloads, stream-quality settings.
