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

## Spotify

The Mac compiles the iOS Web API layer in `Yoin/Data/Spotify` as it is (auth, DTOs, mapping, source, search, playlists). Sign-in: PKCE in `ASWebAuthenticationSession`, redirect `yoin://auth/spotify/callback`, the listener's Client ID (Settings › General › Spotify). Tokens: Keychain `com.gpo.yoin.spotify`, one item per account. Playback: no App Remote on macOS, so `MacSpotifyOutput` is a Spotify Connect remote over the Web API: when Spotify is active on a device (phone, computer, speaker) Yoin plays on and controls that device (51, 2026-11-06), Now Playing says "Playing on …"; with nothing active, this Mac's Spotify app, opened in the background if needed. Every command names the device; a move to another device on the same song is followed; another song there means the listener took over and Yoin lets go. Controls › Play on This Mac transfers here (never automatic). One song at a time; the engine starts the next at the end. Progress / end come from polling `GET /me/player` every second. `MacRoutingOutput` routes AV / MusicKit / Spotify. Heart = Liked Songs; no rating. Nothing is drawn over Spotify artwork.

## Accounts

`MacSession.profiles`: any number of Subsonic servers plus Apple Music, one in use (`activeId`), stored as one Keychain item (`com.gpo.yoin.mac.subsonic` / `profiles`). First launch with the list migrates the single-account item (`default`, left in place for older builds; deleted when that account is removed) and the old `activeSource` choice. Settings › Accounts: Switch, Remove, Add Account… (the sign-in page over the shell, Cancel goes back); Controls › Switch Account. A switch resets the player, library, lyrics and downloads' scope (`MacModel.switchAccount`).

## Offline downloads

The iOS `OfflineDownloads` (and `DetailDiskCache` for its pins) compile into the Mac; its AppModel rows stay iOS-only. `MacModel` sets `scope` (the signed-in Subsonic account), `sign` (stream url at the chosen quality, 320 by default) and the player's `downloadedFile`. Album right-click / detail action and song right-click: Download / Downloading n/m (cancel) / Remove download; a downloaded song shows `download_done` on its row. Library › Downloads lists them; Settings › Downloads: quality, metered networks (URLSession expensive / constrained), size, Remove All. Files: Application Support/YoinOffline (sandbox container).

## Local stores and cloud sync

The iOS stores compile into the Mac (LocalRatings, NotesStore, AlbumMemoryStore, LyricsUserStore, HomeLayoutStore, PlaylistBackupStore, HistoryStore, SongAboutStore, GeminiSettings) with Mac copies of `AccountScope` and `KeychainItem`; ratings are local for every provider, Subsonic written through. Notes: Now Playing › Write. Memories: Library › Memories (iOS MemoryDeck), the diary edits title and review.

Cloud sync is iOS `CloudSync` + `SyncDomainBridge` on the shared Kotlin core (same Drive appDataFolder files as Android / iOS). Settings › Sync. Google client: the Mac's own OAuth client (type iOS, bundle `com.gpo.yoin.mac`) in project yoin-510700, in `Config/YoinMac.xcconfig`. Debug signs manually with the Mac development profile "Yoin Mac Development" (com.gpo.yoin.mac, this MacBook Pro, all Apple Development certs, expires 2027-11-06; installed under ~/Library/Developer/Xcode/UserData/Provisioning Profiles), which carries the `keychain-access-groups` entitlement Google Sign-In needs. Another Mac needs its own device added to that profile.

## Mini player

`MacMiniPlayer` (window id `mini`, ⌥⌘M): cover, the spine beside it (title → artist → album → year, one at a time, every 5 s or on scroll; an item longer than the spine marquees instead of being cut), title / artist, prev · play · next, progress. Floats over other apps by default; Controls ▸ Float Mini Player on Top turns it off (`miniPlayerFloats`).

## Menu bar

`MenuBarExtra` (.window, Yoin `music_note` symbol): `MacMenuBarCard` — cover, title / artist, progress, prev · play · next, Open Yoin.

## Settings (⌘,)

Tabs: Accounts (switch / remove / add), Playback (streaming quality Original · 320 · 192 · 128 → `macStreamQuality`, the engine's `streamQuality`; mini player on top), Storage (playback cache size / Clear, iOS StorageSettingsSection; downloads' quality, metered networks, size, remove all), General (lyrics translations, Gemini key, Spotify Client ID, appearance System / Light / Dark → `macAppearanceSetting`), Sync (cloud sync on / off / now, per-account status, Apple Music start / link), Backups (playlist backups, below), NeoDB (below), About (version, open source licenses from iOS `OpenSourceLicensesView`'s list, less the Spotify iOS SDK).

## Playlist backups

iOS `Data/Backup` (PlaylistBackupStore, PlaylistBackups, PlaylistRestore, SpotifyBackups) compiles into the Mac; `MacBackupAccounts` is the Mac's `BackupAccounts` (Subsonic, Apple Music, Spotify sources and restore destinations from `MacSession`). Settings › Backups: Back Up a Playlist (the account in use), the backups (Restore… / Delete), and the restore sheet (choose an account, match, review, Create Playlist). Followed backups are checked again a few seconds after Yoin comes to the front (`followDue`). Cloud sync carries them like iOS / Android.

## NeoDB

iOS `Data/NeoDB` and `AlbumNeoDBSync` compile into the Mac (the iOS rate-sheet views stay iOS-only). Settings › NeoDB: instance, Sign In (OAuth in `ASWebAuthenticationSession`, `yoin://auth/neodb/callback`), an access token as the fallback, Sign Out (keeps the instance). Token: Keychain `com.gpo.yoin.neodb`. The album page's Rate action opens `MacAlbumRateSheet` (0–10 in tenths plus words; up to 360 characters is a NeoDB short comment, longer a review); a changed score / words is flagged in `NeoDBPendingStore` and pushed when the sheet closes, with Synced / Couldn't sync + Retry on its last line. The Memories diary's words are flagged and pushed the same way.

## Lyrics tools

Now Playing › Lyrics header: Translate (the source's own translation from the shared `LyricsRegistry`: the current source, else the next automatic source that serves the language, whose lyrics come with it) and Search (all providers; a pick is remembered per account and song, `lyricsChoice.<account>.<song>`). Gemini lyrics translation isn't offered on the Mac yet (the key in Settings drives Song About only).

## Playlists, history, Home

Library › Playlists and the playlist page (Subsonic and Apple Music library playlists); the song menu's Add to Playlist (Subsonic). `HistoryStore` (iOS) records this Mac's plays per account; Home's Memories shelf reads it. Home › Edit orders and hides sections (`homeLayout`).

## Keys (Controls menu)

Space play / pause · ⌘→ / ⌘← next / previous · ⌘D like · ⇧⌘P Now Playing panel · ⌘L full window in / out · ⌘F focus (from closed: full window in focus) · ⌘K search · ⌥⌘M mini player · Esc back chain. ⌘↑ / ⌘↓ are left alone (no in-app volume). System full screen (⌃⌘F) is the window's own.

## QA launch arguments (Debug)

`-silentAudio YES` (muted), `-ApplePersistenceIgnoreState YES`, `-qaServer URL -qaUser U -qaPassword P` (signs in when signed out), `-macScreen library|album|artist|np-panel|np-full|np-focus|search`, `-macAppearance light|dark`, `-macWindowSize 1280x820`. Fake server: `python3 ~/Developer/Yoin-archive/promo-demo-2026-11-02/demo_subsonic.py 4977`.

## Apple Music

The iOS `AppleMusicSource`, `AppleMusicMapping` and `AppleMusicAudioOutput` compile into the Mac as they are (playlist backup / restore included), except `MusicLibrary.add`, which macOS lacks: the Mac adds a song with the API's `POST /v1/me/library` (202 = accepted only; the heart waits for `isInLibrary`). Sign-in: "Use Apple Music instead" asks the system for access; `activeSource` in the defaults remembers it. Player output is the iOS `RoutingAudioOutput` (AVQueuePlayer / ApplicationMusicPlayer); MusicKit owns the Now Playing card while it plays. Heart = in the library (add only); no rating.

Needs, once, in the developer portal: an explicit App ID `com.gpo.yoin.mac` with the MusicKit App Service, and a signing profile for it (the wildcard `M75T74PTC4.*` can't carry MusicKit).

## Not on the Mac yet

- Gemini lyrics translation (Song About uses the key; translation still comes only from the lyrics source).
- The album scrapbook (iOS album page: memory stamp, notes on the album, song notes in place); the Mac album page has the Rate sheet only.
- Library sorting and the fast scroller; the newest-album songs pager.
- The first-run Landing (mascot intro); the Mac opens on sign-in.
- Spotify plays one song at a time over Connect (no Spotify-side queue); no App Remote features (cover from Spotify's player, its own up-next).
- Spotify / Apple Music five-star ratings stay local (the services have none); Apple Music library add is add-only.
- Offline downloads for Apple Music and Spotify (Subsonic only, as on iOS).
- Home-screen / desktop widgets, Handoff, Shortcuts / App Intents.
- Real-device Spotify Connect and NeoDB sign-in have only had compile checks on the Mac.
