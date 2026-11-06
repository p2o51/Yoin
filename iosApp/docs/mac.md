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

The Mac compiles the iOS Web API layer in `Yoin/Data/Spotify` as it is (auth, DTOs, mapping, source, search, playlists). Sign-in: PKCE in `ASWebAuthenticationSession`, redirect `yoin://auth/spotify/callback`, the listener's Client ID (Settings › General › Spotify). Tokens: Keychain `com.gpo.yoin.spotify`, one item per account. Playback: no App Remote on macOS, so `MacSpotifyOutput` is a Spotify Connect remote over the Web API. A song started in Yoin goes to the device Spotify is playing on, wherever it is (phone, computer, speaker; Now Playing says "Playing on …"); when nothing plays — nothing active, or a device active but paused, like a phone's Spotify asleep in the background — it goes to this Mac's Spotify app, opened in the background if needed; only when that can't be reached does a paused active device take it (51, 2026-11-06; `MacSpotifyOutput.decide`). Spotify's own shuffle / repeat go off before a start, and only when one is on. No command is ever sent as error handling; a device that drops a start within 20 s is reported. On launch, on foreground and every 10 s Yoin reads `/me/player`: something playing while Yoin plays nothing else is shown in Now Playing and controlled there. Every command names the device; a move to another device on the same song is followed; another song there means the listener took over and Yoin lets go. Controls › Play on This Mac transfers here (never automatic). One song at a time; the engine starts the next at the end. Progress / end come from polling `GET /me/player` every second. `MacRoutingOutput` routes AV / MusicKit / Spotify. Heart = Liked Songs; no rating. Nothing is drawn over Spotify artwork.

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

Tabs: Accounts (each account opens its page, `MacAccountPage`: who it is, Switch / Remove, then only that service's settings at the bottom — 51's rule, 2026-11-04 / 11-06: a Subsonic server's streaming quality Original · 320 · 192 · 128 → `macStreamQuality` and its downloads (quality, metered networks; size and Remove All for the account in use); Spotify's Client ID; Apple Music has none), Storage (playback cache size / Clear, iOS StorageSettingsSection; the space downloads take), General (lyrics translations, Gemini key, mini player on top, appearance System / Light / Dark → `macAppearanceSetting`), Sync (cloud sync on / off / now, per-account status, Apple Music start / link), Backups (playlist backups, below), NeoDB (below), About (version, open source licenses from iOS `OpenSourceLicensesView`'s list, less the Spotify iOS SDK).

## Playlist backups

iOS `Data/Backup` (PlaylistBackupStore, PlaylistBackups, PlaylistRestore, SpotifyBackups) compiles into the Mac; `MacBackupAccounts` is the Mac's `BackupAccounts` (Subsonic, Apple Music, Spotify sources and restore destinations from `MacSession`). Settings › Backups: Back Up a Playlist (the account in use), the backups (Restore… / Delete), and the restore sheet (choose an account, match, review, Create Playlist). Followed backups are checked again a few seconds after Yoin comes to the front (`followDue`). Cloud sync carries them like iOS / Android.

## NeoDB

iOS `Data/NeoDB` and `AlbumNeoDBSync` compile into the Mac (the iOS rate-sheet views stay iOS-only). Settings › NeoDB: instance, Sign In (OAuth in `ASWebAuthenticationSession`, `yoin://auth/neodb/callback`), an access token as the fallback, Sign Out (keeps the instance). Token: Keychain `com.gpo.yoin.neodb`. The album page's Rate action opens `MacAlbumRateSheet` (0–10 in tenths plus words; up to 360 characters is a NeoDB short comment, longer a review); a changed score / words is flagged in `NeoDBPendingStore` and pushed when the sheet closes, with Synced / Couldn't sync + Retry on its last line. The Memories diary's words are flagged and pushed the same way.

## Lyrics tools

Now Playing › Lyrics header: Translate (the source's own translation from the shared `LyricsRegistry`: the current source, else the next automatic source that serves the language, whose lyrics come with it) and Search (all providers; a pick is remembered per account and song, `lyricsChoice.<account>.<song>`). Lyrics a source can't translate go to Gemini with the listener's key, as on iOS: only the sung lines, cached per song / language / model (LyricsUserStore), one target language from Settings for the sources and Gemini; a note says when the lyrics are already that language, there's no key, or Gemini failed.

## Album scrapbook

The album page switches Songs | Scrapbook. `MacAlbumScrapbook` lays out iOS AlbumScrapbookView's pieces on one centered lane: the cover with the `ScoreEmblem` stamp (its award plays once the page rests; no haptics, no tilt on the Mac), the Memory title (click to rename; the sparkle restores Yoin's), Last Play, the review clipping (click: the Rate sheet) or the blank pen, tickets (clusters for notes / the best score, contact sheets of two), notes clippings, the receipt. A ticket's click opens its score (0–10, Clear) and notes (add, edit, delete) in place. iOS `ScoreEmblem`, `GrooveAward`, `GrooveTilt` (stand-in), `YoinFont` (with the variable font) and `ScrapbookPaper` compile into the Mac.

## Library

Albums / Artists / Playlists sort as on iOS (`LibrarySort.swift` compiled in: Recents, Recently added, Alphabetical, Creator — only the orders the data backs), per view (`librarySort.<view>`), Recents from this Mac's history. A jump bar on the right edge lists the sort's runs (`LibraryScrollIndex`); a click jumps there. Subsonic Songs come from iOS `NewestAlbumSongsPager` (newest albums' songs, more near the end, Retry on a failed page).

## First-run landing

`MacLandingView`: the iOS mascot (`LandingMascot`, `LandingMascotPaths`, `YoinMarkPaths`) flies in, waves and says the hello bubble (`landingBubble`); then iOS's own Pick and About scenes (`LandingScenes.swift`, its connect steps fenced to iOS); Sign in → the sign-in page. Settings › About › Welcome Guide re-runs it over the app (Back on the first step or Done closes it). `LandingGate` (iOS `LandingModels`) decides: once per install (`landing_done_v1`), never for an install that already has an account. `-macScreen landing` forces it.

## Playlists, history, Home

Library › Playlists and the playlist page (Subsonic and Apple Music library playlists); the song menu's Add to Playlist (Subsonic). `HistoryStore` (iOS) records this Mac's plays per account; Home's Memories shelf reads it. Home › Edit orders and hides sections (`homeLayout`).

## Shortcuts and Handoff

App Intents in the app itself (`MacIntents.swift`; no extension, no entitlement): Play or Pause, Next Song, Previous Song, Like the Current Song, Play Album, Play Playlist (`AlbumEntity` / `PlaylistEntity`, string queries over the account in use's library). App Shortcuts phrases in English and Chinese; the tiles' icons are SF Symbol names (the system draws them). The intents reach the app through `MacModel.current`.

Handoff: while a song plays the Mac advertises `com.gpo.yoin.playing` (declared in `NSUserActivityTypes`): `provider`, `songId`, `albumId`, `title`, `artist`, `album`, `positionSec`, `playing`. No server address or token. The iOS app doesn't take it yet; it would declare the same type and handle it with `onContinueUserActivity`, finding the song in its own account for that service.

## Widgets (not built)

A macOS widget is an extension with its own bundle id, so it needs, once, in the developer portal:
1. An App ID `com.gpo.yoin.mac.widgets` (explicit), with App Groups.
2. An App Group (e.g. `group.com.gpo.yoin`) added to both `com.gpo.yoin.mac` and the widget's App ID, so the app can hand the widget what's playing and the Memory cards.
3. A Mac development profile for the widget App ID on this Mac ("Yoin Mac Widgets Development"), and the app's profile regenerated with the App Group.
4. Then: a `YoinMacWidgets` target (widget extension, macOS 26) in project.yml embedded in YoinMac, both entitlements files with `com.apple.security.application-groups`, and the shared Glance-like Play / Memory families (Android / iOS designs).

## Owner decisions (51, 2026-11-06)

- Focus mode: the cover may grow 1.7× (more than design.md's 1.5×) on the Mac.
- Spotify: when nothing plays and this Mac's Spotify app can't be reached, a start does not go to a paused device elsewhere; the banner says Spotify on this Mac isn't open, with Open Spotify (nothing plays) and Retry (`MacSpotifyOutput.decide`).
- ⌘K search keeps its Liquid Glass panel: it counts as a hero surface, an exception to "glass only on floating controls".
- The scrapbook's score stamp keeps its glass lens (51, 11-05).
- Now Playing's cover keeps Android's 16 corner (`NOW_PLAYING_COVER`); the bar's thumbnail uses the thumbnail token (4).

## Keys (Controls menu)

Space play / pause · ⌘→ / ⌘← next / previous · ⌘D like · ⇧⌘P Now Playing panel · ⌘L full window in / out · ⌘F focus (from closed: full window in focus) · ⌘K search · ⌥⌘M mini player · Esc back chain. ⌘↑ / ⌘↓ are left alone (no in-app volume). System full screen (⌃⌘F) is the window's own.

## QA launch arguments (Debug)

Keychain (2026-11-07): the QA copy (bundle id `com.gpo.yoin.mac.qa`) never touches the real app's Keychain items. Its account list lives in its own defaults (`qaProfiles`), every other service name gets a `.qa` suffix (`KeychainNamespace`), and it skips the Google sign-in restore. Before this, an ad-hoc-signed QA build reading `com.gpo.yoin.mac.subsonic` made macOS ask for the login keychain password; never answer that dialog for a QA build. Pass `-landing_done_v1 YES` to skip the welcome guide on its clean state.

Rule (2026-11-06): every QA / tour launch ends by quitting it and opening the normal app with no arguments (`open …/Debug/Yoin.app`), then `ps -o args` to check. A `-silentAudio` run neither plays nor reads Spotify, so left running it looks like a broken Yoin; in Debug the window shows an orange "QA · …" marker while any QA argument is set.


`-silentAudio YES` (muted), `-ApplePersistenceIgnoreState YES`, `-qaServer URL -qaUser U -qaPassword P` (signs in when signed out), `-macScreen library|album|artist|scrapbook|np-panel|np-full|np-focus|search|landing|tour|fulltour`, `-macAppearance light|dark`, `-macWindowSize 1280x820`. Fake server: `python3 ~/Developer/Yoin-archive/promo-demo-2026-11-02/demo_subsonic.py 4977`.

## Apple Music

The iOS `AppleMusicSource`, `AppleMusicMapping` and `AppleMusicAudioOutput` compile into the Mac as they are (playlist backup / restore included), except `MusicLibrary.add`, which macOS lacks: the Mac adds a song with the API's `POST /v1/me/library` (202 = accepted only; the heart waits for `isInLibrary`). Sign-in: "Use Apple Music instead" asks the system for access; `activeSource` in the defaults remembers it. Player output is the iOS `RoutingAudioOutput` (AVQueuePlayer / ApplicationMusicPlayer); MusicKit owns the Now Playing card while it plays. Heart = in the library (add only); no rating.

Needs, once, in the developer portal: an explicit App ID `com.gpo.yoin.mac` with the MusicKit App Service, and a signing profile for it (the wildcard `M75T74PTC4.*` can't carry MusicKit).

## Not on the Mac yet

- The landing's in-landing sign-in forms (iOS's connect steps); the Mac's landing ends on its sign-in page.
- An album note (the scrapbook's sticky; not synced or written on iOS either).
- Spotify plays one song at a time over Connect (no Spotify-side queue); no App Remote features (cover from Spotify's player, its own up-next).
- Spotify / Apple Music five-star ratings stay local (the services have none); Apple Music library add is add-only.
- Offline downloads for Apple Music and Spotify (Subsonic only).
- Home-screen / desktop widgets, Handoff, Shortcuts / App Intents.
- Real-device Spotify Connect and NeoDB sign-in have only had compile checks on the Mac.
